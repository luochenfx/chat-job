package com.chatjob.orchestrator;

import com.chatjob.driver.BlockedException;
import com.chatjob.driver.BrowserSession;
import com.chatjob.driver.PatchrightDriver;
import com.chatjob.driver.SelfCheck;
import com.chatjob.evaluate.ActiveLevel;
import com.chatjob.evaluate.AiJudge;
import com.chatjob.evaluate.BlacklistMatcher;
import com.chatjob.notify.Notifier;
import com.chatjob.platform.Models.DeliveryOptions;
import com.chatjob.platform.Models.DeliveryResult;
import com.chatjob.platform.Models.JobDetail;
import com.chatjob.platform.Models.LoginState;
import com.chatjob.platform.Models.ScrollOutcome;
import com.chatjob.platform.Models.ScrollParams;
import com.chatjob.platform.Models.SearchQuery;
import com.chatjob.platform.PlatformAdapter;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.CombinationRunEntity;
import com.chatjob.store.entity.DeliveryEntity;
import com.chatjob.store.entity.JobPostingEntity;
import com.chatjob.store.entity.PlatformConfigEntity;
import com.chatjob.store.entity.RunEntity;
import com.chatjob.watcher.UnreadWatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run 生命周期与主循环。
 *
 * <p>四条硬规则，任何改动都不能破坏：
 * <ol>
 *   <li>同一时刻只有一个未终结的 Run（DB 部分唯一索引 + 内存标志双保险），等待值守时段的那条也算。</li>
 *   <li>终止条件按优先级判：投递配额 → 评估上限 → 组合耗尽；熔断优先于全部。</li>
 *   <li>任何异常都终止 Run，不重试穿越。绕过一次验证码的代价远大于当天少投几十个岗。</li>
 *   <li>窗口外启动 = 挂起等待，不是收工：状态落库、由调度唤醒，进程重启也不丢（见 {@link #wakeWaitingRuns()}）。</li>
 * </ol>
 */
@Slf4j
@Service
public class RunService {

    /** 每处理这么多张卡片做一次未读检查 */
    private static final int WATCH_EVERY_CARDS = 5;
    /** 或者每隔这么久（秒）做一次 */
    private static final int WATCH_EVERY_SECONDS = 120;

    private final RunStore runStore;
    private final JobStore jobStore;
    private final ConfigStore configStore;
    private final PlatformAdapter adapter;
    private final AiJudge aiJudge;
    private final UnreadWatcher unreadWatcher;
    private final Notifier notifier;
    private final SelfCheck selfCheck;
    private final PatchrightDriver patchright;
    private final BrowserSession session;
    private final com.chatjob.web.SseHub sse;

    private volatile Thread worker;
    private volatile boolean stopRequested;
    private volatile boolean pauseRequested;
    /** 越过值守结束时刻。与 stopRequested 分开记，因为终止原因不同：一个是人停的，一个是到点收工 */
    private volatile boolean dutyEnded;
    private volatile Long currentRunId;

    public RunService(RunStore runStore,
                      JobStore jobStore,
                      ConfigStore configStore,
                      PlatformAdapter adapter,
                      AiJudge aiJudge,
                      UnreadWatcher unreadWatcher,
                      Notifier notifier,
                      SelfCheck selfCheck,
                      PatchrightDriver patchright,
                      BrowserSession session,
                      com.chatjob.web.SseHub sse) {
        this.runStore = runStore;
        this.jobStore = jobStore;
        this.configStore = configStore;
        this.adapter = adapter;
        this.aiJudge = aiJudge;
        this.unreadWatcher = unreadWatcher;
        this.notifier = notifier;
        this.selfCheck = selfCheck;
        this.patchright = patchright;
        this.session = session;
        this.sse = sse;
    }

    public record StartRequest(String platform, String mode) {
    }

    // ---------------------------------------------------------------- 生命周期

    public synchronized RunEntity start(String mode) {
        String platform = adapter.platform();
        runStore.active(platform).ifPresent(run -> {
            if (RunStatus.WAITING_DUTY.name().equals(run.getStatus())) {
                throw new IllegalStateException("已有一条任务在等待值守时段（id=" + run.getId()
                        + "），要重新启动请先取消它");
            }
            throw new IllegalStateException("已有进行中的任务（id=" + run.getId() + "，" + run.getStatus()
                    + "），请先停止或等待其结束");
        });
        if (worker != null && worker.isAlive()) {
            throw new IllegalStateException("上一个任务的线程尚未退出，请稍候");
        }

        PlatformConfigEntity cfg = configStore.platformConfig(platform);
        List<CombinationPlanner.Combo> combos = CombinationPlanner.plan(
                configStore.readList(cfg.getCities()), configStore.readList(cfg.getKeywords()));
        if (combos.isEmpty()) {
            throw new IllegalStateException("搜索条件为空：请先在平台配置里填写城市与关键词");
        }

        String effectiveMode = mode == null ? "NORMAL" : mode;
        // 挂起的 Run 这一刻不开浏览器，所以不在这里校验 driver —— 否则「晚上想排明早的任务」
        // 会因为此刻 driver 起不来而被直接拒掉。真正的校验留到唤醒后的 runLoop 入口。
        boolean parked = !"DRY_RUN".equals(effectiveMode) && !DutyWindow.of(cfg).contains(LocalTime.now());

        if (!parked) {
            // fail-closed 前置到启动这一刻：driver 不可用时直接拒绝，而不是建一个 Run
            // 让它在后台线程里失败 —— 后者会让控制台先看到"已启动"再看到"失败"，误导操作。
            PatchrightDriver.Status driver = patchright.inspect();
            if (!driver.available()) {
                throw new IllegalStateException("拒绝启动：" + driver.message());
            }
        }

        // 值守时段不在启动这一刻拦人：窗口外照样建 Run，但状态直接是 WAITING_DUTY，
        // 等窗口打开由 wakeWaitingRuns() 拉起（落库 + 轮询，所以隔夜、跨重启都接得上）。
        // 演练模式全程不发消息，本来就不受时段约束，任何时间都能完整跑一遍。
        RunEntity run = runStore.create(platform, effectiveMode);
        List<CombinationRunEntity> entities = new ArrayList<>();
        for (CombinationPlanner.Combo combo : combos) {
            CombinationRunEntity entity = new CombinationRunEntity();
            entity.setRunId(run.getId());
            entity.setSeq(combo.seq());
            entity.setCity(combo.city());
            entity.setKeyword(combo.keyword());
            entity.setStatus("PENDING");
            entity.setCardsSeen(0);
            entity.setEvaluated(0);
            entity.setDelivered(0);
            entities.add(entity);
        }
        runStore.saveCombinations(entities);

        stopRequested = false;
        pauseRequested = false;
        dutyEnded = false;
        currentRunId = run.getId();
        worker = new Thread(() -> runLoop(run), "run-worker-" + run.getId());
        worker.setDaemon(true);
        worker.start();
        return run;
    }

    public synchronized void pause() {
        pauseRequested = true;
    }

    public synchronized void resume() {
        pauseRequested = false;
    }

    public synchronized void stop() {
        stopRequested = true;
        pauseRequested = false;
        // 等待态没有线程在盯着 stopRequested（挂起时线程早已退出），不在这里直接落库，
        // 它会一直挂到明天窗口打开才「跑起来又收工」—— 用户点停止却什么都没发生。
        RunEntity waiting = runStore.waiting(adapter.platform()).orElse(null);
        if (waiting != null && (worker == null || !worker.isAlive())) {
            finish(waiting, RunStatus.CANCELLED, "CANCELLED", "用户主动停止（等待值守时段中）");
            currentRunId = null;
            publish(waiting, "run.finished", Map.of(
                    "runId", waiting.getId(),
                    "status", waiting.getStatus(),
                    "delivered", 0,
                    "evaluated", 0,
                    "costSeconds", 0));
        }
    }

    public RunEntity current() {
        Long id = currentRunId;
        if (id != null) {
            RunEntity run = runStore.get(id);
            if (run != null && run.getStatus() != null
                    && RunStatus.valueOf(run.getStatus()).isActive()) {
                return run;
            }
        }
        return runStore.recent(1).stream().findFirst().orElse(null);
    }

    public List<CombinationRunEntity> combinationsOf(Long runId) {
        return runId == null ? List.of() : runStore.combinations(runId);
    }

    // ---------------------------------------------------------------- 主循环

    private void runLoop(RunEntity run) {
        String platform = run.getPlatform();
        long startedAt = System.currentTimeMillis();
        try {
            // 时段守卫放在最前面，连 driver 自检都不做：窗口外的 Run 只该挂起等待，
            // 不该在等待期间自检、开浏览器，也不该用「driver 不可用」这类次要原因盖掉真正的原因。
            if (!readyToRun(run)) {
                return;
            }

            PatchrightDriver.Status driver = patchright.inspect();
            if (!driver.available()) {
                finish(run, RunStatus.FAILED, "DRIVER_UNAVAILABLE", driver.message());
                notifier.notify("run-failed", "任务无法启动：driver 不可用", driver.message());
                return;
            }

            SelfCheck.Result check = selfCheck.run();
            if (!check.ok()) {
                finish(run, RunStatus.FAILED, "SELF_CHECK_FAILED", String.join("；", check.failures()));
                notifier.notify("run-failed", "启动自检未通过", String.join("\n", check.failures()));
                return;
            }

            LoginState login = adapter.probeLogin();
            if (!login.loggedIn()) {
                finish(run, RunStatus.LOGIN_LOST, "LOGIN_LOST", login.reason());
                notifier.notify("login-lost", "登录态失效，任务未启动", login.reason());
                return;
            }

            runStore.markRunning(run);
            publish(run, "run.started", Map.of(
                    "runId", run.getId(),
                    "platform", platform,
                    "mode", run.getMode()));
            log(run, "INFO", "任务启动，模式 " + modeLabel(run.getMode()));
            logDutyDeadline(run);

            executeCombinations(run);

            if (dutyEnded) {
                finish(run, RunStatus.COMPLETED, "DUTY_END", "已到值守结束时间，本轮收工");
            } else if (stopRequested) {
                finish(run, RunStatus.CANCELLED, "CANCELLED", "用户主动停止");
            } else {
                finish(run, RunStatus.COMPLETED, "COMPLETED", "组合队列已跑完");
            }
        } catch (BlockedException e) {
            RunStatus status = switch (e.kind()) {
                case LOGIN_LOST -> RunStatus.LOGIN_LOST;
                case DRIVER_UNAVAILABLE -> RunStatus.FAILED;
                default -> RunStatus.CIRCUIT_OPEN;
            };
            finish(run, status, e.kind().name(), e.getMessage());
            publish(run, "circuit.opened", Map.of("kind", e.kind().name(), "message", e.getMessage()));
            notifier.notify("circuit", "任务已熔断：" + e.kind().name(), e.getMessage());
        } catch (Exception e) {
            log.error("任务执行失败", e);
            finish(run, RunStatus.FAILED, "EXCEPTION", e.getClass().getSimpleName() + ": " + e.getMessage());
            notifier.notify("run-failed", "任务异常终止", String.valueOf(e.getMessage()));
        } finally {
            long costSeconds = (System.currentTimeMillis() - startedAt) / 1000;
            // 挂起等待的 Run 也走这里退出，但它是「还没开始」而不是「已结束」：
            // 无条件推 run.finished 会让界面闪一条「任务结束：WAITING_DUTY」，把等待读成收工。
            if (run.getStatus() != null && RunStatus.valueOf(run.getStatus()).isTerminal()) {
                publish(run, "run.finished", Map.of(
                        "runId", run.getId(),
                        "status", run.getStatus(),
                        "delivered", run.getDeliveredCount() == null ? 0 : run.getDeliveredCount(),
                        "evaluated", run.getEvaluatedCount() == null ? 0 : run.getEvaluatedCount(),
                        "costSeconds", costSeconds));
            }
        }
    }

    private void executeCombinations(RunEntity run) {
        PlatformConfigEntity cfg = configStore.platformConfig(run.getPlatform());
        List<CombinationRunEntity> combos = runStore.combinations(run.getId());
        int startIndex = run.getCombinationIndex() == null ? 0 : run.getCombinationIndex();

        for (int i = startIndex; i < combos.size(); i++) {
            if (stopRequested || dutyEnded) {
                return;
            }
            awaitResume(run);
            CombinationRunEntity combo = combos.get(i);
            long comboStartedAt = System.currentTimeMillis();
            combo.setStatus("RUNNING");
            combo.setStartedAt(ConfigStore.now());
            runStore.updateCombination(combo);
            publish(run, "combination.started", Map.of(
                    "seq", combo.getSeq(),
                    "city", combo.getCity(),
                    "keyword", combo.getKeyword()));
            log(run, "INFO", "开始组合 #" + combo.getSeq() + " " + combo.getCity() + " / " + combo.getKeyword());

            int resumeCard = (i == startIndex && run.getLastCardIndex() != null) ? run.getLastCardIndex() : 0;
            try {
                adapter.openSearch(new SearchQuery(combo.getCity(), null, combo.getKeyword()));
                ScrollOutcome scroll = adapter.scrollUntilEnd(
                        new ScrollParams(cfg.getScrollRoundLimit() == null ? 300 : cfg.getScrollRoundLimit(),
                                cfg.getWaitTimeSeconds() == null ? 10 : cfg.getWaitTimeSeconds()),
                        this::shouldStop);
                combo.setCardsSeen(scroll.cards());
                log(run, "INFO", "组合 #" + combo.getSeq() + " 加载 " + scroll.cards() + " 张卡片（"
                        + scroll.rounds() + " 轮，" + (scroll.loadedAll() ? "确认到底" : "撞到轮数上限") + "）");

                processCards(run, cfg, combo, scroll.cards(), resumeCard, comboStartedAt);

                if (stopRequested || dutyEnded) {
                    combo.setStatus("SKIPPED");
                } else if (runStatusOf(run) == RunStatus.RUNNING) {
                    combo.setStatus("DONE");
                }
            } catch (BlockedException e) {
                combo.setStatus("FAILED");
                throw e;
            } catch (Exception e) {
                combo.setStatus("FAILED");
                log(run, "WARN", "组合 #" + combo.getSeq() + " 失败已跳过：" + e.getMessage());
                log.warn("组合 {} 失败", combo.getSeq(), e);
            } finally {
                combo.setFinishedAt(ConfigStore.now());
                runStore.updateCombination(combo);
            }

            if (stopRequested || dutyEnded) {
                return;
            }
            // 组合跑完，断点前进到下一个组合的第 0 张卡
            runStore.checkpoint(run, i + 1, 0, 0);
            run.setCombinationIndex(i + 1);
            run.setLastCardIndex(0);
        }
    }

    private void processCards(RunEntity run, PlatformConfigEntity cfg, CombinationRunEntity combo,
                              int cardCount, int startCard, long comboStartedAt) {
        int cardsSinceWatch = 0;
        long lastWatchAt = System.currentTimeMillis();
        int limitSeconds = cfg.getCombinationTimeLimitSec() == null ? 1200 : cfg.getCombinationTimeLimitSec();
        boolean collectMetrics = configStore.aiConfig().getCollectEffectMetrics() != null
                && configStore.aiConfig().getCollectEffectMetrics() == 1;
        // 演练态从这里显式流下去。sweep 的签名要求它，而不是让 UnreadWatcher 自己去猜 ——
        // 上一轮的 bug 就是「投递路径判了 DRY_RUN，值守路径忘了判」，而值守会真的给 HR 发消息。
        boolean dryRun = "DRY_RUN".equals(run.getMode());

        for (int card = startCard; card < cardCount; card++) {
            if (stopRequested) {
                return;
            }
            awaitResume(run);
            // 卡片边界是唯一可靠的收工点：这里之前的所有动作要么不发消息，要么已经发完了
            if (checkDutyWindow(run, cfg, combo)) {
                return;
            }
            if (runStore.deliveredToday(run.getPlatform()) >= quota(cfg)) {
                finish(run, RunStatus.QUOTA_REACHED, "QUOTA_REACHED",
                        "今日投递已达配额 " + quota(cfg));
                notifier.notify("quota", "今日投递配额已用完",
                        "已投递 " + runStore.deliveredToday(run.getPlatform()) + " 个岗位，任务收工。");
                return;
            }
            if (runStore.evaluatedToday(run.getPlatform()) >= evalLimit(cfg)) {
                finish(run, RunStatus.COMPLETED, "EVAL_LIMIT_REACHED",
                        "今日评估已达上限 " + evalLimit(cfg));
                return;
            }
            if ((System.currentTimeMillis() - comboStartedAt) / 1000 > limitSeconds) {
                log(run, "WARN", "组合 #" + combo.getSeq() + " 超时（" + limitSeconds + "s），进入下一个");
                return;
            }

            JobDetail detail = adapter.fetchDetail(card);
            if (detail == null) {
                log(run, "WARN", "第 " + (card + 1) + " 张卡片没抓到详情，跳过");
                continue;
            }

            processOneJob(run, cfg, detail);

            cardsSinceWatch++;
            boolean timeUp = (System.currentTimeMillis() - lastWatchAt) / 1000 >= WATCH_EVERY_SECONDS;
            if (cardsSinceWatch >= WATCH_EVERY_CARDS || timeUp) {
                cardsSinceWatch = 0;
                lastWatchAt = System.currentTimeMillis();
                UnreadWatcher.SweepResult sweep = unreadWatcher.sweep(
                        adapter, run.getPlatform(), collectMetrics, dryRun);
                // 重试与失败也要推：打开 AI 模式后，一个会话的未读可能在本次没被回复，
                // 但「它失败了、排了下次重试」正是控制台最需要看见的信息（spec 2.2）。
                // 演练态的 wouldReply 同样要推：它不发消息，但「本会回复哪几条」是演练唯一的产出。
                if (sweep.replied() > 0 || sweep.unmatched() > 0
                        || sweep.retried() > 0 || sweep.failed() > 0
                        || sweep.wouldReply() > 0) {
                    publish(run, "unread.detected", Map.of(
                            "threads", sweep.threads(),
                            "replied", sweep.replied(),
                            "unmatched", sweep.unmatched(),
                            "sensitive", sweep.sensitive(),
                            "retried", sweep.retried(),
                            "failed", sweep.failed(),
                            "dryRunWouldReply", sweep.wouldReply()));
                }
            }

            runStore.checkpoint(run, run.getCombinationIndex(), 0, card + 1);
            run.setLastCardIndex(card + 1);

            // 节奏：每张卡片后随机 [waitTime/2, waitTime]
            pauseFor(cfg);
        }
    }

    /** 单岗位的决策与投递 */
    private void processOneJob(RunEntity run, PlatformConfigEntity cfg, JobDetail detail) {
        BlacklistMatcher blacklist = new BlacklistMatcher(configStore.blacklist());
        var hit = blacklist.match(detail.companyName(), detail.jobName());
        if (hit.isPresent()) {
            BlacklistMatcher.Hit h = hit.get();
            log(run, "INFO", "丢弃（黑名单「" + h.keyword() + "」）：" + detail.companyName()
                    + " / " + detail.jobName());
            return;
        }

        ActiveLevel level = ActiveLevel.of(detail.activeRaw());
        ActiveLevel floor = ActiveLevel.fromLabel(cfg.getActiveLevelFloor());
        if (ActiveLevel.isBelowFloor(level, floor)) {
            log(run, "INFO", "丢弃（HR 活跃度 " + level.label() + " 低于下限 "
                    + cfg.getActiveLevelFloor() + "）：" + detail.jobName());
            return;
        }

        JobPostingEntity job = jobStore.upsert(detail, run.getPlatform(), level.label());

        JobStore.Decision decision = jobStore.decide(job.getId());
        if (decision.skip()) {
            log(run, "INFO", "跳过（" + decision.reason() + "）：" + job.getJobName() + " @ " + job.getCompanyName());
            return;
        }

        runStore.incrementEvaluated(run.getPlatform());
        run.setEvaluatedCount((run.getEvaluatedCount() == null ? 0 : run.getEvaluatedCount()) + 1);

        boolean qualityGate = configStore.qualityGateEnabled();
        String greeting;
        String decidedBy;
        Integer score = null;
        String aiReason = null;
        String aiRaw = null;

        if (qualityGate) {
            AiJudge.Verdict verdict = aiJudge.judge(detail, blacklist);
            if (verdict.isFailure()) {
                // fail-closed：判定不出来就不投
                DeliveryEntity delivery = jobStore.startDelivery(job.getId(), run.getId(), "AI");
                jobStore.finishDelivery(delivery, DeliveryResult.skipped("AI 判定失败：" + verdict.error()),
                        null, verdict.error(), null);
                publish(run, "job.evaluated", jobEvent(job, "AI_FAILED", 0, verdict.error()));
                log(run, "WARN", "AI 判定失败已跳过：" + job.getJobName() + " — " + verdict.error());
                return;
            }
            score = verdict.score();
            aiReason = verdict.reason();
            aiRaw = verdict.raw();
            if (!verdict.shouldApply()) {
                DeliveryEntity delivery = jobStore.startDelivery(job.getId(), run.getId(), "AI");
                jobStore.finishDelivery(delivery, DeliveryResult.skipped("AI 判定不投：" + verdict.reason()),
                        score, aiReason, aiRaw);
                publish(run, "job.evaluated", jobEvent(job, "AI_REJECT", score, verdict.reason()));
                log(run, "INFO", "AI 判定不投（" + score + " 分）：" + job.getJobName() + " — " + verdict.reason());
                return;
            }
            greeting = verdict.greeting();
            decidedBy = "AI";
        } else {
            greeting = configStore.aiConfig().getTemplateGreeting();
            decidedBy = "TEMPLATE";
            if (greeting == null || greeting.isBlank()) {
                log(run, "WARN", "质量开关已关闭但模板招呼语为空，跳过");
                return;
            }
        }

        publish(run, "job.evaluated", jobEvent(job, "PASS", score == null ? 0 : score, aiReason));

        if ("DRY_RUN".equals(run.getMode())) {
            DeliveryEntity delivery = jobStore.startDelivery(job.getId(), run.getId(), decidedBy);
            jobStore.finishDelivery(delivery, DeliveryResult.skipped("演练模式，未实际投递"),
                    score, aiReason, aiRaw);
            log(run, "INFO", "演练模式：本会投递 " + job.getJobName() + " @ " + job.getCompanyName());
            return;
        }

        DeliveryEntity delivery = jobStore.startDelivery(job.getId(), run.getId(), decidedBy);
        boolean allowRepeat = cfg.getRepeatChatEnabled() != null && cfg.getRepeatChatEnabled() == 1;
        DeliveryOptions options = new DeliveryOptions(allowRepeat, configStore.repeatMessages(cfg));
        DeliveryResult result = adapter.deliver(detail, greeting, options, this::shouldStop);
        jobStore.finishDelivery(delivery, result, score, aiReason, aiRaw);

        publish(run, "delivery.updated", Map.of(
                "jobId", job.getId(),
                "jobName", job.getJobName(),
                "company", job.getCompanyName(),
                "status", result.status().name(),
                "failureReason", result.failureReason() == null ? "" : result.failureReason()));

        if (result.status() == DeliveryResult.Status.DELIVERED) {
            runStore.incrementDelivered(run.getPlatform());
            run.setDeliveredCount((run.getDeliveredCount() == null ? 0 : run.getDeliveredCount()) + 1);
            log(run, "INFO", "已投递（" + run.getDeliveredCount() + "/" + quota(cfg) + "）："
                    + job.getJobName() + " @ " + job.getCompanyName());
        } else if (result.status() == DeliveryResult.Status.SKIPPED) {
            log(run, "INFO", "未投递（" + result.failureReason() + "）：" + job.getJobName());
        } else {
            log(run, "WARN", "投递失败（" + result.failureReason() + "）：" + job.getJobName());
        }
    }

    private Map<String, Object> jobEvent(JobPostingEntity job, String decision, int score, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", job.getId());
        data.put("jobName", job.getJobName());
        data.put("company", job.getCompanyName());
        data.put("decision", decision);
        data.put("score", score);
        data.put("reason", reason == null ? "" : reason);
        return data;
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 启动（或唤醒）时的时段守卫：只决定「现在能不能开跑」，不决定「能不能启动」。
     *
     * <p>窗口外返回 false，并把 Run 置为 {@link RunStatus#WAITING_DUTY} 挂起 —— 不是收工。
     * 位置刻意放在打开浏览器、自检与登录探测之前：挂起期间不留任何副作用，
     * 不浏览平台、不清未读红点、不花一次 AI 调用。
     *
     * @return true 表示现在就能开跑
     */
    private boolean readyToRun(RunEntity run) {
        // 演练模式不发送任何消息，不受时段约束（它的副作用见 console 的演练说明块）
        if ("DRY_RUN".equals(run.getMode())) {
            return true;
        }
        DutyWindow window = DutyWindow.of(configStore.platformConfig(run.getPlatform()));
        LocalTime now = LocalTime.now();
        if (window.contains(now)) {
            return true;
        }
        long waitMinutes = window.opensInMinutes(now);
        // nextOpenHint 自带「之后才会真正执行」，这里不要再接「自动开始」，否则读成「真正执行自动开始」
        String message = "当前 " + DutyWindow.format(now) + " 不在值守时段 " + window.describe()
                + " 内，已挂起等待；" + window.nextOpenHint(now);
        runStore.markWaiting(run, message);
        log(run, "INFO", message);
        publish(run, "run.waiting", Map.of(
                "window", window.describe(),
                "at", DutyWindow.format(now),
                "opensInMinutes", waitMinutes));
        // 挂起是用户点启动的直接后果，人就在界面前，不发企业微信通知：那条通知只会复读屏幕上已经写着的话。
        return false;
    }

    /**
     * 等待态的唤醒器。
     *
     * <p>为什么不让线程 sleep 到明天：线程活不过进程重启，而「昨晚点的启动、今早九点还在等着」
     * 恰恰是这个功能最有价值的场景。等待态落库 + 这里每 30s 轮询一次，重启后也能接上。
     *
     * <p>synchronized 与 start()/stop() 同一把锁：唤醒本质上就是「替用户再点一次启动」，
     * 必须和人工启动互斥，否则会同时起两个 worker 抢同一个浏览器档案。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public synchronized void wakeWaitingRuns() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        RunEntity waiting = runStore.waiting(adapter.platform()).orElse(null);
        if (waiting == null) {
            return;
        }
        DutyWindow window = DutyWindow.of(configStore.platformConfig(waiting.getPlatform()));
        LocalTime now = LocalTime.now();
        if (!window.contains(now)) {
            return;
        }
        log(waiting, "INFO", "值守时段已到（现在 " + DutyWindow.format(now) + "），开始执行挂起的任务");
        publish(waiting, "run.awakened", Map.of(
                "runId", waiting.getId(),
                "at", DutyWindow.format(now),
                "window", window.describe()));
        stopRequested = false;
        pauseRequested = false;
        dutyEnded = false;
        currentRunId = waiting.getId();
        worker = new Thread(() -> runLoop(waiting), "run-worker-" + waiting.getId());
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 进程重启后的收尾：把库里「没有任何线程持有」的 Run 标为失败。
     *
     * <p>单进程部署下，启动那一刻必然不持有任何线程，所以 CREATED/RUNNING/PAUSED 全是上一轮的孤儿。
     * 不清掉它们会两处出事：uk_run_running 唯一索引挡住新 Run 的创建；active() 让用户看到
     * 「已有进行中的任务」却找不到那个任务。等待态（WAITING_DUTY）不在此列 —— 它本来就该留着等。
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void recoverAfterRestart() {
        for (RunEntity run : runStore.orphaned()) {
            finish(run, RunStatus.FAILED, "SERVICE_RESTART", "服务重启，上一轮中断；如需继续请重新启动");
            publish(run, "run.finished", Map.of(
                    "runId", run.getId(),
                    "status", run.getStatus(),
                    "delivered", run.getDeliveredCount() == null ? 0 : run.getDeliveredCount(),
                    "evaluated", run.getEvaluatedCount() == null ? 0 : run.getEvaluatedCount(),
                    "costSeconds", 0));
        }
    }

    /** 启动后立刻把「这一轮能跑多久」写进日志——时段是运行时长上限，不是启动门槛 */
    private void logDutyDeadline(RunEntity run) {
        if ("DRY_RUN".equals(run.getMode())) {
            log(run, "INFO", "演练模式不受值守时段约束，本轮跑到组合队列耗尽为止");
            return;
        }
        DutyWindow window = DutyWindow.of(configStore.platformConfig(run.getPlatform()));
        if (window.alwaysOn()) {
            log(run, "INFO", "值守时段为全天，本轮不会因时段收工");
            return;
        }
        long minutes = window.closesInMinutes(LocalTime.now());
        String left = minutes < 0 ? "" : "（还能跑约 " + minutes + " 分钟）";
        log(run, "INFO", "值守时段 " + window.describe() + "，到 " + DutyWindow.format(window.end())
                + " 自动收工" + left);
    }

    /**
     * 卡片边界的值守检查。越过结束时刻就收工，不再发下一条招呼语。
     *
     * <p>为什么只在这里判：滚动卡池和投递中途都不该被时间打断——半条招呼语比晚几分钟更糟。
     * 一张卡的处理时间是秒级，所以边界检查的延迟上限就是「一张卡 + 一次间隔」，可以接受。
     *
     * @return true 表示本轮已收工，调用方应立即返回
     */
    private boolean checkDutyWindow(RunEntity run, PlatformConfigEntity cfg, CombinationRunEntity combo) {
        if (dutyEnded) {
            return true;
        }
        // 演练模式不发送任何消息，所以不参与时段限制。
        // 注意「不发消息」不等于「无副作用」：它仍会真实浏览平台、调用 AI、点开会话。
        if ("DRY_RUN".equals(run.getMode())) {
            return false;
        }
        DutyWindow window = DutyWindow.of(cfg);
        LocalTime now = LocalTime.now();
        if (window.contains(now)) {
            return false;
        }
        dutyEnded = true;
        String message = "已到值守结束时间（" + window.describe() + "，现在 " + DutyWindow.format(now) + "），本轮收工";
        log(run, "INFO", message);
        publish(run, "duty.ended", Map.of(
                "window", window.describe(),
                "at", DutyWindow.format(now),
                "seq", combo.getSeq()));
        notifier.notify("duty-end", "值守时段已结束", message + "。" + window.nextOpenHint(now) + "。");
        return true;
    }

    private void awaitResume(RunEntity run) {
        if (!pauseRequested) {
            return;
        }
        if (RunStatus.valueOf(runStore.get(run.getId()).getStatus()) != RunStatus.PAUSED) {
            runStore.finish(run, RunStatus.PAUSED, null, "已暂停");
            run.setStatus(RunStatus.PAUSED.name());
        }
        while (pauseRequested && !stopRequested) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!stopRequested) {
            runStore.markRunning(run);
            run.setStatus(RunStatus.RUNNING.name());
        }
    }

    private void pauseFor(PlatformConfigEntity cfg) {
        int wait = cfg.getWaitTimeSeconds() == null ? 10 : cfg.getWaitTimeSeconds();
        int min = Math.max(1, wait / 2);
        int max = Math.max(min + 1, wait);
        int millis = java.util.concurrent.ThreadLocalRandom.current().nextInt(min * 1000, max * 1000);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean shouldStop() {
        return stopRequested;
    }

    private static int quota(PlatformConfigEntity cfg) {
        return cfg.getDailyDeliveryQuota() == null ? 30 : cfg.getDailyDeliveryQuota();
    }

    /**
     * 运行日志是控制台上给操作者看的主要证据流，里面不该出现裸的 {@code DRY_RUN} ——
     * 那是个内部枚举，读起来不知道「这意味着什么」。带括号的说明放在这里，
     * 因为日志里第一次出现模式名就是这一行，读者需要当场知道「不会发消息」。
     */
    private static String modeLabel(String mode) {
        return "DRY_RUN".equals(mode) ? "演练模式（不发送任何消息）" : "正常投递";
    }

    private static int evalLimit(PlatformConfigEntity cfg) {
        return cfg.getDailyEvalLimit() == null ? 150 : cfg.getDailyEvalLimit();
    }

    private RunStatus runStatusOf(RunEntity run) {
        RunEntity fresh = runStore.get(run.getId());
        return fresh == null || fresh.getStatus() == null
                ? RunStatus.FAILED : RunStatus.valueOf(fresh.getStatus());
    }

    private void finish(RunEntity run, RunStatus status, String reason, String message) {
        runStore.finish(run, status, reason, message);
        run.setStatus(status.name());
        run.setTerminalReason(reason);
        log(run, status == RunStatus.COMPLETED || status == RunStatus.QUOTA_REACHED ? "INFO" : "WARN",
                status + " / " + reason + " / " + message);
    }

    private void log(RunEntity run, String level, String message) {
        runStore.log(run.getId(), level, message);
        log.info("[run {}] {}", run.getId(), message);
    }

    private void publish(RunEntity run, String type, Object data) {
        sse.publish(run.getId(), type, data);
    }

    /** 供控制台手动收工用：清掉内存标志，允许下一个 Run 启动 */
    public synchronized void resetWorker() {
        worker = null;
        currentRunId = null;
        stopRequested = false;
        pauseRequested = false;
    }

    public boolean isRunning() {
        return worker != null && worker.isAlive();
    }

    /**
     * 给控制台的时段状态。因为窗口外也允许启动（挂起等待），界面必须先说清
     * 「现在点会立刻开跑，还是排到几点自己开始」，否则操作者不知道自己在等什么。
     *
     * @param open           当前是否在时段内
     * @param hint           窗口外是「何时自动开始」，窗口内是「何时到点收工」
     * @param minutesLeft    窗口内还能跑多久（分钟）；窗口外或全天不限为 -1
     * @param opensInMinutes 窗口外还要等多久才开跑（分钟）；窗口内或全天不限为 -1
     */
    public record DutyStatus(String window, boolean open, String hint,
                             long minutesLeft, long opensInMinutes) {
    }

    public DutyStatus dutyStatus() {
        DutyWindow window = DutyWindow.of(configStore.platformConfig(adapter.platform()));
        LocalTime now = LocalTime.now();
        if (!window.contains(now)) {
            return new DutyStatus(window.describe(), false, window.nextOpenHint(now),
                    -1, window.opensInMinutes(now));
        }
        if (window.alwaysOn()) {
            return new DutyStatus(window.describe(), true, "全天不限，不会到点收工", -1, -1);
        }
        return new DutyStatus(window.describe(), true,
                "到 " + DutyWindow.format(window.end()) + " 自动收工",
                window.closesInMinutes(now), -1);
    }

    public BrowserSession session() {
        return session;
    }
}
