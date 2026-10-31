package com.chatjob.web;

import com.chatjob.orchestrator.RunService;
import com.chatjob.orchestrator.RunServices;
import com.chatjob.orchestrator.RunStore;
import com.chatjob.platform.PlatformAdapters;
import com.chatjob.store.entity.CombinationRunEntity;
import com.chatjob.store.entity.RunEntity;
import com.chatjob.store.entity.RunLogEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run 的读写接口，<b>按平台寻址</b>（spec 0004 阶段 2b）。
 *
 * <p>两条寻址规则，刻意不同：
 * <ul>
 *   <li><b>新建 / 查当前</b>用显式的 {@code platform} 查询参数，缺省落
 *       {@code chat-job.active-platform}。缺省值让 2c 之前的控制台仍然可用，
 *       而「带参数」的能力已经就位（2c 只改前端调用，不用再动后端）。</li>
 *   <li><b>暂停 / 继续 / 取消</b>只给 {@code runId}：Run 自己知道属于哪个平台，
 *       从 id 反查比要求前端「记得同时传对平台」可靠 —— 传错平台的后果是
 *       暂停了另一个平台的流水线，而界面上看起来什么都没发生。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunServices runServices;
    private final PlatformAdapters adapters;
    private final RunStore runStore;
    private final SseHub sse;

    public RunController(RunServices runServices, PlatformAdapters adapters, RunStore runStore, SseHub sse) {
        this.runServices = runServices;
        this.adapters = adapters;
        this.runStore = runStore;
        this.sse = sse;
    }

    public record StartBody(String mode) {
    }

    @PostMapping
    public Map<String, Object> start(@RequestParam(required = false) String platform,
                                     @RequestBody(required = false) StartBody body) {
        String mode = body == null || body.mode() == null ? "NORMAL" : body.mode();
        RunService service = runServices.of(platform);
        RunEntity run = service.start(mode);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.getId());
        out.put("platform", run.getPlatform());
        out.put("status", run.getStatus());
        out.put("mode", run.getMode());
        return out;
    }

    @PostMapping("/{id}/pause")
    public Map<String, Object> pause(@PathVariable Long id) {
        RunService service = serviceOf(id);
        service.pause();
        return Map.of("ok", true, "platform", service.platform(), "status", "PAUSED");
    }

    @PostMapping("/{id}/resume")
    public Map<String, Object> resume(@PathVariable Long id) {
        RunService service = serviceOf(id);
        service.resume();
        return Map.of("ok", true, "platform", service.platform(), "status", "RUNNING");
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id) {
        RunService service = serviceOf(id);
        service.stop();
        return Map.of("ok", true, "platform", service.platform(), "status", "CANCELLING");
    }

    /** 某平台当前（或最近一次）Run + 组合队列；概览与任务页共用 */
    @GetMapping("/current")
    public Map<String, Object> current(@RequestParam(required = false) String platform) {
        RunService service = runServices.of(platform);
        RunEntity run = service.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform", service.platform());
        out.put("run", run);
        out.put("running", service.isRunning());
        // 时段状态随概览一起下发：窗口外也能启动，界面需要提前说清这一轮跑不跑得起来
        out.put("duty", service.dutyStatus());
        List<CombinationRunEntity> combos = run == null ? List.of() : service.combinationsOf(run.getId());
        out.put("combinations", combos);
        // 陈旧阈值由后端算好下发：前端只做比较，不重复实现「几倍间隔」这条规则
        out.put("staleAfterSeconds", service.staleAfterSeconds(run));
        if (run != null) {
            out.put("deliveredToday", runStore.deliveredToday(run.getPlatform()));
            out.put("evaluatedToday", runStore.evaluatedToday(run.getPlatform()));
        }
        return out;
    }

    /**
     * 历史 Run。{@code platform} 可选：不传 = 全部平台（跨平台对比要继续可用），
     * 传了就只回该平台 —— 否则某平台最近没跑时，它的列表会被另一平台的历史挤空。
     */
    @GetMapping
    public List<RunEntity> recent(@RequestParam(defaultValue = "20") int limit,
                                  @RequestParam(required = false) String platform) {
        return platform == null || platform.isBlank()
                ? runStore.recent(limit)
                : runStore.recent(adapters.platformKey(platform), limit);
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long id,
                             @RequestParam(required = false) Long lastEventId,
                             @RequestHeader(value = "Last-Event-ID", required = false) Long headerEventId) {
        // 浏览器自动重连走 header；前端主动重建连接走 query
        return sse.subscribe(id, headerEventId != null ? headerEventId : lastEventId);
    }

    @GetMapping("/{id}/logs")
    public List<RunLogEntity> logs(@PathVariable Long id,
                                   @RequestParam(defaultValue = "0") long afterId,
                                   @RequestParam(defaultValue = "200") int limit) {
        return runStore.logs(id, afterId, limit);
    }

    /**
     * 由 runId 反查平台的流水线。
     *
     * <p>查不到 Run 抛 {@code IllegalArgumentException}（→ 400），而不是悄悄落到缺省平台：
     * 「暂停了一个不存在的 Run」和「暂停了另一个平台的 Run」都是静默事故。
     */
    private RunService serviceOf(Long runId) {
        RunEntity run = runStore.get(runId);
        if (run == null) {
            throw new IllegalArgumentException("找不到这个任务：" + runId);
        }
        return runServices.of(run.getPlatform());
    }
}
