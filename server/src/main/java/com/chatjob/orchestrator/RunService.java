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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Run（值守期）的生命周期与主循环。
 *
 * <p>四条硬规则，任何改动都不能破坏：
 * <ol>
 *   <li>同一时刻只有一个未终结的 Run（DB 部分唯一索引 + 内存标志双保险），等待时段与值守待命的那条也算。</li>
 *   <li>扫描中止条件按优先级判：投递配额 → 评估上限 → 组合耗尽。三者只中止<b>扫描</b>，
 *       Run 进入待命继续值守；熔断优先于全部，且不降级为待命。</li>
 *   <li>任何异常都终止 Run，不重试穿越。绕过一次验证码的代价远大于当天少投几十个岗。</li>
 *   <li>窗口外启动 / 窗口到点 = 挂起等待，不是收工：状态落库、由调度唤醒，进程重启也不丢
 *       （见 {@link #wakeWaitingRuns()}）。</li>
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

    /** 落在日志与界面上的巡检时刻用 HH:mm:ss：只说 HH:mm 会让人算不出「还有多久」 */
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 挂起期心跳间隔 */
    private static final long PARK_HEARTBEAT_MILLIS = 5 * 60 * 1000L;

    private volatile Thread worker;
    private volatile boolean stopRequested;
    private volatile boolean pauseRequested;
    /** 越过值守结束时刻。与 stopRequested 分开记，因为终止原因不同：一个是人停的，一个是到点收工 */
    private volatile boolean dutyEnded;
    /**
     * 暂停前处于哪个进行态。只有内存一份：暂停不跨进程重启（重启本来就把进行态标失败），
     * 而它决定「继续」之后回 RUNNING 还是回 STANDBY —— 从待命暂停的，恢复后不该重新开扫。
     */
    private volatile RunStatus pausedFrom;
    private volatile Long currentRunId;
    /** 挂起心跳的节流状态：哪一条 Run、上次打点是什么时候 */
    private volatile Long parkedHeartbeatRunId;
    private volatile long parkedHeartbeatAt;

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
            act(run, "开始扫描");

            // 值守期是「扫描 + 值守」两条腿：扫描因配额 / 评估上限 / 组合耗尽停下时，
            // 只回到待命，不结束整轮；待命期配额被调高或跨了自然日，再从这里回到扫描。
            while (true) {
                ensureScanDay(run);
                StandbyGate.StopReason stop = executeCombinations(run);

                if (stopRequested) {
                    finish(run, RunStatus.CANCELLED, "CANCELLED", "用户主动停止");
                    return;
                }
                if ("DRY_RUN".equals(run.getMode())) {
                    // 演练模式不进入待命：它会真点开会话清红点，整夜待命会把第二天的未读提前吃掉
                    finishDryRun(run, stop);
                    return;
                }
                if (dutyEnded) {
                    park(run, "DUTY_END");
                    return;
                }
                enterStandby(run, stop);

                StandbyExit exit = standbyLoop(run, stop);
                if (exit == StandbyExit.STOPPED) {
                    finish(run, RunStatus.CANCELLED, "CANCELLED", "用户主动停止");
                    return;
                }
                if (exit == StandbyExit.PARKED) {
                    // 挂起是「收起页面」，不是结束：状态已由 park 落库，finally 不会推 run.finished
                    return;
                }
                // RESCAN：状态已置回 RUNNING，回到循环顶部接着扫
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

    /**
     * 跑组合队列。
     *
     * @return 扫描为什么停；{@code null} 表示被人停 / 到点 / 组合超时这类「不需要待命理由」的退出
     */
    // ---------------------------------------------------------------- 值守待命

    /** 待命循环的三种出口 */
    private enum StandbyExit { RESCAN, STOPPED, PARKED }

    /**
     * 待命循环：扫描停了，值守不停。
     *
     * <p>为什么复用当前线程而不是另起一个：{@code PlaywrightExecutor} 是单线程串行的，
     * {@code BossAdapter} 又是单例持有 {@code listPage} / {@code threadPage} 两个字段。
     * 并行意味着两个线程交替操作同一批页面，历史上那正是「导航被打断成 net::ERR_ABORTED」的来源。
     *
     * <p>待命期不碰搜索页（不调 {@code openSearch}），{@code listPage} 保持上次扫描停在的位置。
     */
    private StandbyExit standbyLoop(RunEntity run, StandbyGate.StopReason reason) {
        int round = 0;
        long enteredAt = System.currentTimeMillis();
        boolean collectMetrics = configStore.aiConfig().getCollectEffectMetrics() != null
                && configStore.aiConfig().getCollectEffectMetrics() == 1;

        while (true) {
            if (stopRequested) {
                return StandbyExit.STOPPED;
            }
            awaitResume(run);
            if (stopRequested) {
                return StandbyExit.STOPPED;
            }

            // 每轮重读配置：配额、评估上限、巡检间隔都可能在待命期被改
            PlatformConfigEntity cfg = configStore.platformConfig(run.getPlatform());
            DutyWindow window = DutyWindow.of(cfg);
            LocalTime now = LocalTime.now();
            int interval = ConfigStore.clampWatchInterval(cfg.getWatchIntervalSeconds());
            int delivered = runStore.deliveredToday(run.getPlatform());
            int evaluated = runStore.evaluatedToday(run.getPlatform());

            StandbyGate.Action action = StandbyGate.decide(now, window, reason, run.getScanDay(), RunStore.today(),
                    delivered, quota(cfg), evaluated, evalLimit(cfg));
            if (action == StandbyGate.Action.PARK) {
                park(run, "DUTY_END");
                return StandbyExit.PARKED;
            }
            if (action == StandbyGate.Action.SCAN) {
                boolean newDay = run.getScanDay() == null || !RunStore.today().equals(run.getScanDay());
                String code = newDay ? "NEW_DAY"
                        : reason == StandbyGate.StopReason.EVAL_LIMIT ? "EVAL_LIMIT_RAISED" : "QUOTA_RAISED";
                String why = newDay ? "已跨自然日（" + RunStore.today() + "），恢复扫描"
                        : reason == StandbyGate.StopReason.EVAL_LIMIT
                        ? "评估上限已回升（" + evaluated + "/" + evalLimit(cfg) + "），恢复扫描"
                        : "主动投递配额已回升（" + delivered + "/" + quota(cfg) + "），恢复扫描";
                log(run, "INFO", why);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("reason", code);
                data.put("at", DutyWindow.format(now));
                publish(run, "run.rescan", data);
                ensureScanDay(run);
                // 只改状态，重写 started_at 会让「已运行多久」在每次回扫时归零 ——
                // 值守期是一次启动到手动停止，中间来回切换扫描/待命不该重置这个计时
                runStore.markStatus(run, RunStatus.RUNNING);
                run.setStatus(RunStatus.RUNNING.name());
                return StandbyExit.RESCAN;
            }

            round++;
            act(run, "待命巡检：读取消息列表");
            UnreadWatcher.SweepResult sweep = unreadWatcher.sweep(
                    adapter, run.getPlatform(), collectMetrics, false);
            publishUnread(run, sweep);
            // 抖动：每 120 秒整刷一次列表是个太像机器的特征
            int jittered = jitterSeconds(interval);
            long standbyMinutes = (System.currentTimeMillis() - enteredAt) / 60_000;
            // 0 未读也要有一行：等待期间完全静默时，人无法区分「在等」和「卡死」
            log(run, "INFO", "值守待命第 " + round + " 轮：会话 " + sweep.threads()
                    + "，回复 " + sweep.replied() + "，未命中 " + sweep.unmatched()
                    + (sweep.retried() > 0 ? "，重试 " + sweep.retried() : "")
                    + (sweep.failed() > 0 ? "，失败 " + sweep.failed() : "")
                    + "；下次巡检 " + LocalTime.now().plusSeconds(jittered).format(HHMMSS)
                    + "（已待命 " + standbyMinutes + " 分钟）");
            sleepInterruptibly(window, jittered * 1000L);
        }
    }

    /**
     * 进入值守待命。三种扫描中止原因共用这一个出口 —— 分开写就会有人漏改一处，
     * 而漏改的那一处正好是「配额用完就收工」的老行为。
     */
    private void enterStandby(RunEntity run, StandbyGate.StopReason reason) {
        PlatformConfigEntity cfg = configStore.platformConfig(run.getPlatform());
        DutyWindow window = DutyWindow.of(cfg);
        int delivered = runStore.deliveredToday(run.getPlatform());
        int evaluated = runStore.evaluatedToday(run.getPlatform());
        StandbyGate.StopReason why0 = reason == null ? StandbyGate.StopReason.COMBOS_EXHAUSTED : reason;
        String why = switch (why0) {
            case QUOTA -> "今日主动投递配额已用完（" + delivered + "/" + quota(cfg) + "）";
            case EVAL_LIMIT -> "今日评估已达上限（" + evaluated + "/" + evalLimit(cfg) + "）";
            case COMBOS_EXHAUSTED -> "搜索组合已全部扫完";
        };
        String tail = window.alwaysOn()
                ? "值守时段为全天，不会挂起"
                : "到 " + DutyWindow.format(window.end()) + " 停止操作页面并挂起";
        String message = "扫描已结束（" + why + "），值守继续：每 "
                + intervalLabel(ConfigStore.clampWatchInterval(cfg.getWatchIntervalSeconds()))
                + "检查一次未读消息，" + tail;

        runStore.markStandby(run, message);
        act(run, "值守待命：等下一轮巡检");
        log(run, "INFO", message);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", why0.name());
        data.put("delivered", delivered);
        data.put("evaluated", evaluated);
        data.put("quota", quota(cfg));
        publish(run, "run.standby", data);
        notifier.notify("standby", "扫描已结束，值守继续", message);
    }

    /**
     * 时段到点：收起页面，值守期不结束。
     *
     * <p>文案必须写清「已挂起、不用重新启动」——原来那句「本轮收工」会让人第二天早上
     * 再点一次启动，而那正好会造出第二条 Run。
     */
    /**
     * 落一次「当前动作」。
     *
     * <p>每次等待之前都必须调：界面上「日志不滚了」既可能是等 8 秒，也可能是线程死了，
     * 只有这一列能把两者分开。它不落日志表 —— 每张卡两行日志，跑一天就是几百行噪音。
     */
    private void act(RunEntity run, String action) {
        run.setCurrentAction(action);
        run.setLastActivityAt(ConfigStore.now());
        runStore.touchActivity(run.getId(), action);
    }

    private void park(RunEntity run, String reason) {
        DutyWindow window = DutyWindow.of(configStore.platformConfig(run.getPlatform()));
        LocalTime now = LocalTime.now();
        String message = "已到值守结束时间（" + window.describe() + "，现在 " + DutyWindow.format(now) + "），"
                + "停止操作页面；" + window.nextOpenHint(now) + "，值守期没有结束，不需要重新启动";
        runStore.markWaiting(run, message);
        act(run, "已挂起，等 " + window.describe() + " 的下一个窗口");
        log(run, "INFO", message);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason);
        data.put("window", window.describe());
        data.put("at", DutyWindow.format(now));
        data.put("opensInMinutes", window.opensInMinutes(now));
        publish(run, "run.waiting", data);
        notifier.notify("duty-end", "值守时段已结束，已挂起", message);
    }

    /**
     * 跨自然日恢复扫描前调用；同日重复调用是 no-op。
     *
     * <p>复位而不是「继续」：昨天扫完的组合队列状态是 DONE，原样再跑一遍只会全部跳过，
     * 第二天一个岗位都不投，而日志上看不出任何异常。
     */
    private void ensureScanDay(RunEntity run) {
        String today = RunStore.today();
        if (today.equals(run.getScanDay())) {
            return;
        }
        boolean first = run.getScanDay() == null;
        run.setScanDay(today);
        if (!first) {
            run.setCombinationIndex(0);
            run.setScrollRound(0);
            run.setLastCardIndex(0);
            runStore.resetCombinations(run.getId());
            log(run, "INFO", "进入新的一天（" + today + "），组合队列复位，从第 1 个组合重新扫描");
        }
        runStore.update(run);
    }

    /**
     * 可打断的睡眠：每秒检查一次停止与时段结束。
     *
     * <p>不能直接 {@code Thread.sleep(interval)}：22:00 到点最多要等两分钟才收起页面，
     * 而「时段外不操作页面」是硬约束。暂停也靠这里尽快生效。
     */
    private void sleepInterruptibly(DutyWindow window, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested || !window.contains(LocalTime.now())) {
                return;
            }
            long left = deadline - System.currentTimeMillis();
            try {
                Thread.sleep(Math.min(1000L, Math.max(1L, left)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** ±10% 抖动 */
    private static int jitterSeconds(int seconds) {
        double factor = 0.9 + ThreadLocalRandom.current().nextDouble() * 0.2;
        return Math.max(1, (int) Math.round(seconds * factor));
    }

    /** 人话化的间隔，如 120 → 「2 分钟」、45 → 「45 秒」 */
    private static String intervalLabel(int seconds) {
        return seconds % 60 == 0 ? (seconds / 60) + " 分钟" : seconds + " 秒";
    }

    private static String stopLabel(StandbyGate.StopReason reason) {
        return switch (reason) {
            case QUOTA -> "今日主动投递配额";
            case EVAL_LIMIT -> "今日评估上限";
            case COMBOS_EXHAUSTED -> "组合耗尽";
        };
    }

    /**
     * 演练模式跑完即结束，不进入待命。
     *
     * <p>演练会真点开会话清红点，整夜待命会把第二天的未读提前吃掉 —— 那会让演练本身失真。
     * 三种中止原因在演练下都收敛成 {@code COMPLETED}，与改造前的行为一致。
     */
    private void finishDryRun(RunEntity run, StandbyGate.StopReason stop) {
        switch (stop == null ? StandbyGate.StopReason.COMBOS_EXHAUSTED : stop) {
            case QUOTA -> finish(run, RunStatus.COMPLETED, "QUOTA_REACHED",
                    "演练模式：今日主动投递配额已用完，演练结束（不进入待命）");
            case EVAL_LIMIT -> finish(run, RunStatus.COMPLETED, "EVAL_LIMIT_REACHED",
                    "演练模式：今日评估已达上限，演练结束（不进入待命）");
            case COMBOS_EXHAUSTED -> finish(run, RunStatus.COMPLETED, "COMPLETED", "组合队列已跑完");
        }
    }

    private StandbyGate.StopReason executeCombinations(RunEntity run) {
        PlatformConfigEntity cfg = configStore.platformConfig(run.getPlatform());
        List<CombinationRunEntity> combos = runStore.combinations(run.getId());
        int startIndex = run.getCombinationIndex() == null ? 0 : run.getCombinationIndex();

        for (int i = startIndex; i < combos.size(); i++) {
            if (stopRequested || dutyEnded) {
                return null;
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

                StandbyGate.StopReason stop = processCards(run, cfg, combo, scroll.cards(), resumeCard, comboStartedAt);
                if (stop != null) {
                    // 配额 / 评估到顶：这个组合没跑完，既不能记 DONE，也不能推进断点 ——
                    // 待命期配额被调高后要回到这张卡继续，而不是从下一个组合重来
                    combo.setStatus("SKIPPED");
                    log(run, "INFO", "组合 #" + combo.getSeq() + " 在「" + stopLabel(stop)
                            + "」处中止，剩余卡片留待回扫时继续");
                    return stop;
                }

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
                return null;
            }
            // 组合跑完，断点前进到下一个组合的第 0 张卡
            runStore.checkpoint(run, i + 1, 0, 0);
            run.setCombinationIndex(i + 1);
            run.setLastCardIndex(0);
        }
        return StandbyGate.StopReason.COMBOS_EXHAUSTED;
    }

    private StandbyGate.StopReason processCards(RunEntity run, PlatformConfigEntity cfg, CombinationRunEntity combo,
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
                return null;
            }
            awaitResume(run);
            // 卡片边界是唯一可靠的收工点：这里之前的所有动作要么不发消息，要么已经发完了
            if (checkDutyWindow(run, cfg)) {
                return null;
            }
            // 配额与评估上限的检查位置不变（卡片边界）—— 那里之前的所有动作要么不发消息，
            // 要么已经发完，是唯一可靠的断点。命中后只中止扫描：值守照旧（spec 0003 3.2）。
            int delivered = runStore.deliveredToday(run.getPlatform());
            if (delivered >= quota(cfg)) {
                log(run, "INFO", "今日主动投递已达配额 " + quota(cfg) + "（" + delivered + "），中止扫描；值守继续");
                return StandbyGate.StopReason.QUOTA;
            }
            int evaluated = runStore.evaluatedToday(run.getPlatform());
            if (evaluated >= evalLimit(cfg)) {
                log(run, "INFO", "今日评估已达上限 " + evalLimit(cfg) + "（" + evaluated + "），中止扫描；值守继续");
                return StandbyGate.StopReason.EVAL_LIMIT;
            }
            if ((System.currentTimeMillis() - comboStartedAt) / 1000 > limitSeconds) {
                log(run, "WARN", "组合 #" + combo.getSeq() + " 超时（" + limitSeconds + "s），进入下一个");
                return null;
            }

            act(run, "读取第 " + (card + 1) + " 张卡的岗位详情");
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
                publishUnread(run, sweep);
            }

            runStore.checkpoint(run, run.getCombinationIndex(), 0, card + 1);
            run.setLastCardIndex(card + 1);

            // 节奏：每张卡片后随机 [waitTime/2, waitTime]
            String next = card + 2 <= cardCount ? "处理第 " + (card + 2) + " 张卡" : "进入下一个组合";
            pauseFor(run, cfg, next);
        }
        return null;
    }

    /**
     * 把一轮值守的结果推给界面。
     *
     * <p>重试与失败也要推：打开 AI 模式后，一个会话的未读可能本轮没被回复，
     * 但「它失败了、排了下次重试」正是控制台最需要看见的信息。
     * 演练态的 wouldReply 同样要推：它不发消息，但「本会回复哪几条」是演练唯一的产出。
     */
    private void publishUnread(RunEntity run, UnreadWatcher.SweepResult sweep) {
        if (sweep.replied() > 0 || sweep.unmatched() > 0 || sweep.sensitive() > 0
                || sweep.retried() > 0 || sweep.failed() > 0 || sweep.wouldReply() > 0) {
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
            act(run, "AI 判定中：" + job.getJobName() + " @ " + job.getCompanyName());
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
        act(run, "投递中：" + job.getJobName() + " @ " + job.getCompanyName());
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
        act(run, "已挂起，等 " + window.describe() + " 的下一个窗口");
        log(run, "INFO", message);
        Map<String, Object> data = new LinkedHashMap<>();
        // reason 与 park() 的 DUTY_END 区分开：这里是「启动时就不在窗口内」，
        // 那边是「跑着跑着到点了」。界面文案不同，混用会让人以为任务是被中断的。
        data.put("reason", "OUT_OF_WINDOW");
        data.put("window", window.describe());
        data.put("at", DutyWindow.format(now));
        data.put("opensInMinutes", waitMinutes);
        publish(run, "run.waiting", data);
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
            logParkedHeartbeat(waiting, window, now);
            return;
        }
        parkedHeartbeatRunId = null;
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
     * <p>单进程部署下，启动那一刻必然不持有任何线程，所以 CREATED/RUNNING/PAUSED/STANDBY 全是上一轮的孤儿。
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

    /**
     * 挂起期心跳：每 5 分钟一行。
     *
     * <p>挂起期一次页面都不碰，确实没有新信息 —— 但「没有新信息」不等于「没有输出」。
     * 一晚上 132 行，不刷屏，也不至于让人盯着一个整夜不变的界面猜它是不是死了。
     * 调度每 30 秒跑一次，这里只负责把频率压到 5 分钟。
     */
    private void logParkedHeartbeat(RunEntity waiting, DutyWindow window, LocalTime now) {
        long nowMs = System.currentTimeMillis();
        boolean sameRun = waiting.getId().equals(parkedHeartbeatRunId);
        if (sameRun && nowMs - parkedHeartbeatAt < PARK_HEARTBEAT_MILLIS) {
            return;
        }
        parkedHeartbeatRunId = waiting.getId();
        parkedHeartbeatAt = nowMs;
        log(waiting, "INFO", "仍挂起：现在 " + DutyWindow.format(now) + "，值守时段 "
                + window.describe() + "，" + window.nextOpenHint(now)
                + "（值守期 #" + waiting.getId() + " 仍在）");
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
                + " 停止操作页面并挂起" + left);
    }

    /**
     * 卡片边界的值守检查。越过结束时刻就收工，不再发下一条招呼语。
     *
     * <p>为什么只在这里判：滚动卡池和投递中途都不该被时间打断——半条招呼语比晚几分钟更糟。
     * 一张卡的处理时间是秒级，所以边界检查的延迟上限就是「一张卡 + 一次间隔」，可以接受。
     *
     * @return true 表示本轮已收工，调用方应立即返回
     */
    private boolean checkDutyWindow(RunEntity run, PlatformConfigEntity cfg) {
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
        // 事件、日志、告警文案统一在 park() 里发：只有那里知道「这是挂起，不是收工」。
        // 在这里发会写成「本轮收工」，与「值守期没有结束」直接矛盾。
        return true;
    }

    private void awaitResume(RunEntity run) {
        if (!pauseRequested) {
            return;
        }
        if (RunStatus.valueOf(runStore.get(run.getId()).getStatus()) != RunStatus.PAUSED) {
            // 记下暂停前是什么状态：从待命暂停的，恢复后要回待命，
            // 否则一点「继续」就顺手把扫描也开了，而用户只是想让值班的先歇一下
            pausedFrom = runStatusOf(run);
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
            if (pausedFrom == RunStatus.STANDBY) {
                // 只改状态，不重写 started_at：值守期的起点没变
                runStore.markStatus(run, RunStatus.STANDBY);
                run.setStatus(RunStatus.STANDBY.name());
            } else {
                runStore.markRunning(run);
                run.setStatus(RunStatus.RUNNING.name());
            }
            pausedFrom = null;
        }
    }

    private void pauseFor(RunEntity run, PlatformConfigEntity cfg, String next) {
        int wait = cfg.getWaitTimeSeconds() == null ? 10 : cfg.getWaitTimeSeconds();
        int min = Math.max(1, wait / 2);
        int max = Math.max(min + 1, wait);
        int millis = ThreadLocalRandom.current().nextInt(min * 1000, max * 1000);
        // 等待前先写「在等多久、等完干什么」：这是界面区分「在等」与「卡死」的唯一依据
        act(run, "等待 " + Math.max(1, millis / 1000) + " 秒后" + next);
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
                "到 " + DutyWindow.format(window.end()) + " 停止操作页面并挂起（值守期不结束）",
                window.closesInMinutes(now), -1);
    }

    public BrowserSession session() {
        return session;
    }

    /**
     * 「多久没动就算无响应」。阈值只在后端算一次、随 {@code /runs/current} 下发，
     * 前端只做比较 —— 这条规则写两份，两侧迟早分叉，而分叉的表现是
     * 「界面报无响应、日志还在滚」，最消耗信任。
     */
    public int staleAfterSeconds(RunEntity run) {
        PlatformConfigEntity cfg = configStore.platformConfig(adapter.platform());
        RunStatus status = run == null || run.getStatus() == null ? null : RunStatus.valueOf(run.getStatus());
        return ActivityStaleness.staleAfterSeconds(status,
                cfg.getWaitTimeSeconds() == null ? 10 : cfg.getWaitTimeSeconds(),
                ConfigStore.clampWatchInterval(cfg.getWatchIntervalSeconds()));
    }
}
