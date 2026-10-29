package com.chatjob.web;

import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.UnreadTaskEntity;
import com.chatjob.store.mapper.WatcherStatsMapper;
import com.chatjob.watcher.UnreadTaskStore;
import com.chatjob.watcher.WatcherStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 值守运维接口：告警统计 + 未读任务面板 + 手动重试（spec 0002 §3.10）。
 *
 * <p>与 {@code ConfigController} 分开：一个是「配置读写」，一个是「任务运维与统计」。
 * 塞在一起会让权限与变更节奏混作一团 —— 配置改错影响下一轮，重试点错影响某一个人。
 */
@Slf4j
@RestController
@RequestMapping("/api/watcher")
public class WatcherController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 面板窗口上限。开太大等于让一次请求扫全表 */
    private static final int MAX_DAYS = 90;
    private static final int MAX_LIMIT = 200;

    private static final Set<String> KNOWN_STATUS = Set.of(
            "PENDING", "PROCESSING", "DONE", "FAILED", "GAVE_UP");

    private final ConfigStore configStore;
    private final WatcherStatsMapper statsMapper;
    private final UnreadTaskStore taskStore;

    public WatcherController(ConfigStore configStore, WatcherStatsMapper statsMapper,
                             UnreadTaskStore taskStore) {
        this.configStore = configStore;
        this.statsMapper = statsMapper;
        this.taskStore = taskStore;
    }

    /**
     * skip 占比告警判定。
     *
     * <p><b>判定在服务端做</b>，前端只按 {@code state} 上色。把判定下放给前端，
     * 「样本不足」和「未超阈值」很可能在两个页面上被实现成同一种绿色。
     */
    @GetMapping("/stats")
    public WatcherStats stats(@RequestParam(defaultValue = "7") int days) {
        String cutoff = cutoff(days);
        return WatcherStats.judge(
                statsMapper.countAiJudged(cutoff),
                statsMapper.countAiSkipped(cutoff),
                configStore.skipAlertPct(),
                configStore.skipAlertMinSample());
    }

    /**
     * 任务面板。默认只回计数与失败原因，不返回消息原文（表里也只存摘要）。
     *
     * @param status 可选，传了只回该状态；未知值当没传处理，而不是报错 ——
     *               面板的筛选参数来自界面，不该因为拼错一个词就让整块区域白屏
     */
    @GetMapping("/unread-tasks")
    public Map<String, Object> unreadTasks(@RequestParam(required = false) String status,
                                           @RequestParam(defaultValue = "7") int days,
                                           @RequestParam(defaultValue = "50") int limit) {
        String cutoff = cutoff(days);
        String normalized = status == null || status.isBlank() ? null : status.toUpperCase();
        if (normalized != null && !KNOWN_STATUS.contains(normalized)) {
            normalized = null;
        }

        List<UnreadTaskEntity> tasks = statsMapper.listTasks(cutoff, normalized, clampLimit(limit));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", counts(cutoff));
        out.put("tasks", tasks.stream().map(WatcherController::view).toList());
        out.put("days", days);
        return out;
    }

    /**
     * 手动重试。仅接受 {@code GAVE_UP}。
     *
     * <p>默认不展示这个按钮是有原因的（spec 3.8.3）：{@code GAVE_UP} 多数是真的不该回
     * （同名混淆、列表已变、对方是骚扰），摆在明面上会诱导人反复点，反而制造错发风险。
     *
     * @return 200 已重置为待处理；404 任务不存在；409 状态不是已放弃
     */
    @PostMapping("/unread-tasks/{id}/retry")
    public ResponseEntity<Map<String, Object>> retry(@PathVariable Long id) {
        UnreadTaskEntity task = taskStore.findById(id);
        if (task == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("ok", false, "message", "任务不存在"));
        }
        if (!UnreadTaskStore.Status.GAVE_UP.name().equals(task.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("ok", false, "message", "只有已放弃的任务可以重试，当前状态 " + task.getStatus()));
        }
        // 先查再改之间可能有并发，manualRetry 内部会再校验一次，这里兜住 false 的结果
        if (!taskStore.manualRetry(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("ok", false, "message", "任务状态已变化，请刷新后重试"));
        }
        log.info("控制台人工重试任务 | id={} | 会话={}", id, task.getPlatformConversationId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("status", UnreadTaskStore.Status.PENDING.name());
        return ResponseEntity.ok(body);
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> counts(String cutoff) {
        int pending = 0;
        int processing = 0;
        int retrying = 0;
        int gaveUp = 0;
        int done = 0;
        for (Map<String, Object> row : statsMapper.countTasksByStatus(cutoff)) {
            String status = String.valueOf(row.get("status"));
            // MyBatis 把 COUNT(*) 的列名按目标数据库返回，大小写不确定，这里宽松取
            long count = row.values().stream()
                    .filter(v -> v instanceof Number)
                    .mapToLong(v -> ((Number) v).longValue())
                    .findFirst().orElse(0L);
            switch (status) {
                case "PENDING" -> pending += (int) count;
                case "PROCESSING" -> processing += (int) count;
                case "FAILED" -> retrying += (int) count;
                case "GAVE_UP" -> gaveUp += (int) count;
                case "DONE" -> done += (int) count;
                default -> { }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        // 「待处理」把 PROCESSING 一起算进去：它是同一条任务正在被处理的那一瞬间，
        // 单独列一个「正在处理」只会让面板上多一个永远接近 0 的数字
        out.put("pending", pending + processing);
        out.put("retrying", retrying);
        out.put("gaveUp", gaveUp);
        out.put("done", done);
        return out;
    }

    private static Map<String, Object> view(UnreadTaskEntity t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        out.put("conversation", t.getPlatformConversationId());
        out.put("status", t.getStatus());
        out.put("attemptCount", t.getAttemptCount());
        out.put("maxAttempts", t.getMaxAttempts());
        out.put("lastAction", t.getLastAction());
        out.put("lastReasonCode", t.getLastReasonCode());
        out.put("lastError", t.getLastError());
        out.put("nextRetryAt", t.getNextRetryAt());
        out.put("createdAt", t.getCreatedAt());
        out.put("updatedAt", t.getUpdatedAt());
        return out;
    }

    private static String cutoff(int days) {
        int d = Math.max(1, Math.min(days, MAX_DAYS));
        return LocalDateTime.now().minusDays(d).format(TS);
    }

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
