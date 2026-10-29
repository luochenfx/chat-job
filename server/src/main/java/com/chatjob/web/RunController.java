package com.chatjob.web;

import com.chatjob.orchestrator.RunService;
import com.chatjob.orchestrator.RunStore;
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

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runService;
    private final RunStore runStore;
    private final SseHub sse;

    public RunController(RunService runService, RunStore runStore, SseHub sse) {
        this.runService = runService;
        this.runStore = runStore;
        this.sse = sse;
    }

    public record StartBody(String mode) {
    }

    @PostMapping
    public Map<String, Object> start(@RequestBody(required = false) StartBody body) {
        String mode = body == null || body.mode() == null ? "NORMAL" : body.mode();
        RunEntity run = runService.start(mode);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.getId());
        out.put("status", run.getStatus());
        out.put("mode", run.getMode());
        return out;
    }

    @PostMapping("/{id}/pause")
    public Map<String, Object> pause(@PathVariable Long id) {
        runService.pause();
        return Map.of("ok", true, "status", "PAUSED");
    }

    @PostMapping("/{id}/resume")
    public Map<String, Object> resume(@PathVariable Long id) {
        runService.resume();
        return Map.of("ok", true, "status", "RUNNING");
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id) {
        runService.stop();
        return Map.of("ok", true, "status", "CANCELLING");
    }

    /** 概览与任务页共用：当前（或最近一次）Run + 组合队列 */
    @GetMapping("/current")
    public Map<String, Object> current() {
        RunEntity run = runService.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", run);
        out.put("running", runService.isRunning());
        // 时段状态随概览一起下发：窗口外也能启动，界面需要提前说清这一轮跑不跑得起来
        out.put("duty", runService.dutyStatus());
        List<CombinationRunEntity> combos = run == null ? List.of() : runService.combinationsOf(run.getId());
        out.put("combinations", combos);
        if (run != null) {
            out.put("deliveredToday", runStore.deliveredToday(run.getPlatform()));
            out.put("evaluatedToday", runStore.evaluatedToday(run.getPlatform()));
        }
        return out;
    }

    @GetMapping
    public List<RunEntity> recent(@RequestParam(defaultValue = "20") int limit) {
        return runStore.recent(limit);
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
}
