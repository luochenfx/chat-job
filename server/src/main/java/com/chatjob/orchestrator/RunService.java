package com.chatjob.orchestrator;

import com.chatjob.driver.BlockedException;
import com.chatjob.driver.BrowserSessions;
import com.chatjob.driver.PatchrightDriver;
import com.chatjob.driver.SelfCheck;
import com.chatjob.driver.human.HumanCounters;
import com.chatjob.driver.human.Humanize;
import com.chatjob.evaluate.ActiveLevel;
import com.chatjob.evaluate.AiJudge;
import com.chatjob.evaluate.BlacklistMatcher;
import com.chatjob.notify.Notifier;
import com.chatjob.platform.Models.DeliveryOptions;
import com.chatjob.platform.Models.DeliveryResult;
import com.chatjob.platform.Models.JobDetail;
import com.chatjob.platform.Models.LoginState;
import com.chatjob.platform.Models.ScrollOutcome;
import com.chatjob.platform.Models.SearchQuery;
import com.chatjob.platform.PlatformAdapter;
import com.chatjob.platform.PlatformAdapters;
import com.chatjob.platform.PlatformInfo;
import com.chatjob.platform.PlatformSwitches;
import com.chatjob.runlog.RunLog;
import com.chatjob.runlog.RunLogLevel;
import com.chatjob.runlog.RunLogs;
import com.chatjob.scan.ScanBoundary;
import com.chatjob.scan.ScanStop;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.CombinationRunEntity;
import com.chatjob.store.entity.DeliveryEntity;
import com.chatjob.store.entity.JobPostingEntity;
import com.chatjob.store.entity.PlatformConfigEntity;
import com.chatjob.store.entity.RunEntity;
import com.chatjob.time.TimeSource;
import com.chatjob.watcher.UnreadWatcher;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Run（值守期）的生命周期与主循环。
 *
 * <p>四条硬规则，任何改动都不能破坏：
 * <ol>
 *   <li>同一时刻<b>每个平台</b>只有一个未终结的 Run（DB 部分唯一索引 + 内存标志双保险），
 *       等待时段与值守待命的那条也算。这是本类在 2b 之后唯一变化的约束范围：
 *       原来写的是「全库一个」，而 {@code uk_run_running ON run(platform)} 从建表起算的就是
 *       「每个平台一个持有者」。</li>
 *   <li>扫描中止条件按优先级判：投递配额 → 评估上限 → 组合耗尽。三者只中止<b>扫描</b>，
 *       Run 进入待命继续值守；熔断优先于全部，且不降级为待命。
 *       两个平台级开关改变这条规则的适用范围（spec 0005）：未启用扫描时不进扫描段、启动即待命；
 *       未启用值守时不进待命，扫描结束即收尾。</li>
 *   <li>任何异常都终止 Run，不重试穿越。绕过一次验证码的代价远大于当天少投几十个岗。</li>
 *   <li>窗口外启动 / 窗口到点 = 挂起等待，不是收工：状态落库、由调度唤醒，进程重启也不丢
 *       （见 {@link RunServices#wakeWaitingRuns()}）。</li>
 * </ol>
 *
 * <p><b>2b：按平台实例化（prototype）</b>。本类内部 {@code worker / stopRequested /
 * pauseRequested / dutyEnded / pausedFrom / currentRunId} 六个字段是「只有一个任务」的假设，
 * 但它们描述的是<b>一条流水线</b>的内部状态，而不是全进程的状态 —— 所以不必改写，
 * 只要每个平台各有一份这个对象（ADR-0003 Consequences）。实例由
 * {@link RunServices} 按平台创建并绑定适配器；<b>本类不做任何 {@code if (platform == ...)}</b>，
 * 平台差异全部在 {@link PlatformAdapter} 的实现里。
 *
 * <p><b>本类不直接打文件日志</b>：运行日志的唯一出口是 {@link RunLog}（ADR-0008）。
 * 改造前这里另有一个 slf4j 的 logger，它同时写库和写文件，于是「同一件事两个出口」；
 * 现在连带堆栈的那两处也经 RunLog：异常只进文件，那句话进库。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class RunService {

    private final RunStore runStore;
    private final JobStore jobStore;
    private final ConfigStore configStore;
    private final PlatformAdapters adapters;
    private final AiJudge aiJudge;
    private final UnreadWatcher unreadWatcher;
    private final Notifier notifier;
    private final SelfCheck selfCheck;
    private final PatchrightDriver patchright;
    private final com.chatjob.web.SseHub sse;
    /** 平台开关：停用的平台不启动任务（spec 0004 阶段 3） */
    private final PlatformSwitches switches;
    /**
     * 按平台取人类化交互实例。它是<b>平台级</b>对象（与浏览器会话同寿），比一次 Run 活得久，
     * 所以这里只用它取计数快照与注册降级回调，不持有它。
     */
    private final BrowserSessions sessions;

    /** 运行日志出口的装配点。每处写日志用 {@code runLogs.of(run)} 现取，不缓存（见 ADR-0008） */
    private final RunLogs runLogs;

    /** 业务时间的唯一来源：时段判断、跨日、时间戳落库。页面等待超时不走这里 */
    private final TimeSource time;

    /**
     * 本实例服务的平台适配器。由 {@link #bind(String)} 在创建后一次性赋值。
     *
     * <p>为什么不是构造参数：prototype bean 由 Spring 创建，而「这次要哪个平台」是
     * registry 在取实例那一刻知道的（registry 以平台为键）。构造期注入 {@code platform}
     * 需要为每个平台各写一个 bean 定义，接入第三个平台就要再改一处配置。
     */
    private volatile PlatformAdapter adapter;

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
                      PlatformAdapters adapters,
                      AiJudge aiJudge,
                      UnreadWatcher unreadWatcher,
                      Notifier notifier,
                      SelfCheck selfCheck,
                      PatchrightDriver patchright,
                      com.chatjob.web.SseHub sse,
                      PlatformSwitches switches,
                      BrowserSessions sessions,
                      RunLogs runLogs,
                      TimeSource time) {
        this.runStore = runStore;
        this.jobStore = jobStore;
        this.configStore = configStore;
        this.adapters = adapters;
        this.aiJudge = aiJudge;
        this.unreadWatcher = unreadWatcher;
        this.notifier = notifier;
        this.selfCheck = selfCheck;
        this.patchright = patchright;
        this.sse = sse;
        this.switches = switches;
        this.sessions = sessions;
        this.runLogs = runLogs;
        this.time = time;
    }

    /**
     * 绑定本实例服务的平台。由 {@link RunServices#of(String)} 在取实例后立即调用，只允许一次。
     *
     * <p>重复绑定直接报错而不是覆盖：两个平台共用一实例的后果是「A 平台的 Run 在主循环里
     * 操作 B 平台的浏览器」，而症状只会表现为页面上的随机错位，几乎不可能从日志反推。
     */
    public RunService bind(String platform) {
        if (this.adapter != null) {
            throw new IllegalStateException("RunService 已绑定平台 " + this.adapter.platform()
                    + "，不能重新绑定为 " + platform);
        }
        this.adapter = adapters.of(platform);
        return this;
    }

    /** 防御式取用：未绑定就调用说明装配走错了路，宁可当场炸，也不要用 null 平台跑出一轮假数据 */
    private PlatformAdapter adapter() {
        PlatformAdapter bound = adapter;
        if (bound == null) {
            throw new IllegalStateException("RunService 未绑定平台：必须经 RunServices.of(platform) 获取实例");
        }
        return bound;
    }

    public record StartRequest(String platform, String mode) {
    }

    // ---------------------------------------------------------------- 生命周期

    public synchronized RunEntity start(String mode) {
        String platform = adapter().platform();
        // 停用的平台不允许新建任务。放在这里而不是控制器里：唤醒、重试、未来的任何入口
        // 所有入口都要执行这项校验，漏一处就会出现「已停用的平台仍被拉起」
        switches.require(platform);
        runStore.active(platform).ifPresent(run -> {
            if (RunStatus.WAITING_DUTY.name().equals(run.getStatus())) {
                throw new IllegalStateException("已经有一条任务在等待时段开始（编号 " + run.getId()
                        + "），要重新启动请先取消它");
            }
            throw new IllegalStateException("已经有一个任务在运行（编号 " + run.getId()
                    + "），请先停止它，或等它结束");
        });
        if (worker != null && worker.isAlive()) {
            throw new IllegalStateException("上一个任务还在收尾，请稍等一下");
        }

        PlatformConfigEntity cfg = configStore.platformConfig(platform);
        // 关闭扫描的平台不建组合：城市与关键词是扫描的输入，只值守时它们为空也不影响启动
        // （spec 0005 / ADR-0005 第 8 条）。这条校验留着会给「只想守住已有会话的新平台」一个
        // 没有意义的启动门槛。
        boolean scanEnabled = configStore.platformScanEnabled(platform);
        List<CombinationPlanner.Combo> combos = scanEnabled
                ? CombinationPlanner.plan(configStore.readList(cfg.getCities()), configStore.readList(cfg.getKeywords()))
                : List.of();
        if (scanEnabled && combos.isEmpty()) {
            throw new IllegalStateException("搜索条件为空：请先在平台配置里填写城市和关键词");
        }

        String effectiveMode = mode == null ? "NORMAL" : mode;
        // 挂起的 Run 这一刻不开浏览器，所以不在这里校验 driver —— 否则「晚上想排明早的任务」
        // 会因为此刻 driver 起不来而被直接拒掉。真正的校验留到唤醒后的 runLoop 入口。
        boolean parked = !"DRY_RUN".equals(effectiveMode) && !DutyWindow.of(cfg).contains(time.now().toLocalTime());

        if (!parked) {
            // fail-closed 前置到启动这一刻：driver 不可用时直接拒绝，而不是建一个 Run
            // 让它在后台线程里失败 —— 后者会让控制台先看到"已启动"再看到"失败"，误导操作。
            PatchrightDriver.Status driver = patchright.inspect();
            if (!driver.available()) {
                throw new IllegalStateException("无法启动：" + driver.message());
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
        RunEntity waiting = runStore.waiting(adapter().platform()).orElse(null);
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

    /**
     * 本平台「当前（或最近一次）」的 Run。
     *
     * <p>2b 之后退回最近一条时必须带平台：{@code recent(1)} 是全库最新，会把
     * <b>另一个平台</b>的 Run 显示在本平台的任务页上（这条坑 RunStore.latest 的注释里已写明）。
     */
    public RunEntity current() {
        Long id = currentRunId;
        if (id != null) {
            RunEntity run = runStore.get(id);
            if (run != null && run.getStatus() != null
                    && RunStatus.valueOf(run.getStatus()).isActive()) {
                return run;
            }
        }
        return runStore.latest(adapter().platform()).orElse(null);
    }

    public List<CombinationRunEntity> combinationsOf(Long runId) {
        return runId == null ? List.of() : runStore.combinations(runId);
    }

    // ---------------------------------------------------------------- 主循环

    private void runLoop(RunEntity run) {
        String platform = run.getPlatform();
        RunLog runLog = runLogs.of(run);
        // 本轮的宿主：扫描段与待命段都通过它问「能不能继续 / 要不要等 / 到点没有」。
        // 每轮 Run 一个，因为它绑定 run 实体；两条循环共用同一个实例，运行状态只有一种读法。
        RunHost host = new RunHost(run);
        long startedAt = System.currentTimeMillis();
        // 交互计数的起点。为 null 表示这条 Run 还没走到"真的开始干活"（时段外挂起、
        // driver 不可用、自检失败、登录态失效都会在此之前返回），那种情况下不该写汇总行
        HumanCounters.Snapshot interactionsAtStart = null;
        try {
            // 时段守卫放在最前面，连 driver 自检都不做：窗口外的 Run 只该挂起等待，
            // 不该在等待期间自检、开浏览器，也不该用「driver 不可用」这类次要原因盖掉真正的原因。
            if (!readyToRun(run)) {
                return;
            }

            PatchrightDriver.Status driver = patchright.inspect();
            if (!driver.available()) {
                finish(run, RunStatus.FAILED, "DRIVER_UNAVAILABLE", driver.message());
                notify(run, "run-failed", "任务无法启动：driver 不可用", driver.message());
                return;
            }

            // 自检必须检「本平台这一份浏览器」：两个平台并行时，A 平台的指纹通过不能替 B 平台作保
            SelfCheck.Result check = selfCheck.run(adapter().platform());
            if (!check.ok()) {
                finish(run, RunStatus.FAILED, "SELF_CHECK_FAILED", String.join("；", check.failures()));
                notify(run, "run-failed", "启动自检未通过", String.join("\n", check.failures()));
                return;
            }

            LoginState login = adapter().probeLogin();
            if (!login.loggedIn()) {
                finish(run, RunStatus.LOGIN_LOST, "LOGIN_LOST", login.reason());
                notify(run, "login-lost", "登录态失效，任务未启动", login.reason());
                return;
            }

            runStore.markRunning(run);
            publish(run, "run.started", Map.of(
                    "runId", run.getId(),
                    "platform", platform,
                    "mode", run.getMode()));
            runLog.info("任务启动，模式 " + modeLabel(run.getMode()));
            logDutyDeadline(run);
            interactionsAtStart = beginInteractionTracking(run, platform);

            // 本次运行要不要扫描，只在启动这一刻读一次。运行中把它关掉由两个卡片/组合边界生效；
            // 关掉后重新打开要等下次启动 —— 这个值在整轮里不再变，是为了让「循环要不要扫」
            // 与传给 StandbyGate 的判断始终是同一个（spec 0005 §3.3 第 1 条）。
            boolean scanEnabled = configStore.platformScanEnabled(platform);
            // 只扫描不值守的这一轮，正常结局就是「扫完即收尾」。提前写一行说明，否则最后的
            // COMPLETED / SCAN_DONE 会被读成任务提前退出 —— 这一轮的 unread.detected 事件
            // 本来就一条都不会有，日志里没有别的痕迹能解释它为什么结束。
            if (!configStore.platformWatchEnabled(platform)) {
                runLog.info("该平台未启用值守：本轮只扫描，扫描结束后收尾，不进入值守待命");
            }
            if (!scanEnabled) {
                // 只值守：不建组合、不开搜索页，日志里也不出现「开始扫描」「开始组合」
                if ("DRY_RUN".equals(run.getMode())) {
                    // 演练会真点开会话，没有可扫的内容时它的唯一产出也没有了，直接收尾不进待命
                    finish(run, RunStatus.COMPLETED, "SCAN_DISABLED",
                            "演练模式：该平台未启用扫描，没有可演练的内容");
                    return;
                }
                enterStandby(run, ScanStop.SCAN_DISABLED);
            } else {
                act(run, "开始搜索岗位");
            }

            // 值守期是「扫描 + 值守」两条腿：扫描因配额 / 评估上限 / 组合耗尽停下时，
            // 只回到待命，不结束整轮；待命期配额被调高或跨了自然日，再从这里回到扫描。
            // 关闭扫描的平台只有值守这一条腿：整个循环不经过扫描分支，只反复待命巡检。
            ScanStop reason = scanEnabled ? null : ScanStop.SCAN_DISABLED;
            while (true) {
                if (scanEnabled) {
                    ensureScanDay(run);
                    ScanSegment segment = new ScanSegment(run, adapter(), configStore, runStore,
                            unreadWatcher, runLogs, time, this::processOneJob, host);
                    reason = segment.run().orElse(null);

                    // 三个既有判定顺序不变：停止是 CANCELLED、演练不进待命、到点挂起。
                    // 下面的「未启用值守」只能排在它们之后 —— 排在前面就会把用户点停止
                    // 和时段到点都记成 SCAN_DONE。
                    if (stopRequested) {
                        finish(run, RunStatus.CANCELLED, "CANCELLED", "用户主动停止");
                        return;
                    }
                    if ("DRY_RUN".equals(run.getMode())) {
                        // 演练模式不进入待命：它会真点开会话清红点，整夜待命会把第二天的未读消息提前标记为已读
                        finishDryRun(run, reason);
                        return;
                    }
                    if (dutyEnded) {
                        park(run, "DUTY_END");
                        return;
                    }
                    // 扫描开、值守关：扫完就结束，不进待命（spec 0005 §3.2 第二行）。
                    // reason 在这里必不为 null：扫描段只在停止或到点两处返回空，
                    // 而这两种情况上面已经各自 return 了。
                    // 读当下的配置而不是启动那一刻的值：扫描中途被关掉的值守，要在走到这里时
                    // 直接收尾，而不是先进待命发一条「值守继续」的横幅与企业微信通知、再立刻结束。
                    if (!configStore.platformWatchEnabled(run.getPlatform())) {
                        // 超时是「停下」不是「结束」：这一轮确实收尾了，但组合还剩卡没看完，
                        // 写成「已结束」会让人以为 300 张都过了一遍
                        String stopWord = reason == ScanStop.COMBINATION_TIMEOUT ? "扫描暂停" : "扫描已结束";
                        finish(run, RunStatus.COMPLETED, "SCAN_DONE",
                                stopWord + "（" + whyOf(reason, run)
                                        + "），该平台未启用值守，任务结束");
                        return;
                    }
                    enterStandby(run, reason);
                }

                StandbyExit exit = standbyLoop(run, host, reason, scanEnabled);
                if (exit == StandbyExit.STOPPED) {
                    finish(run, RunStatus.CANCELLED, "CANCELLED", "用户主动停止");
                    return;
                }
                if (exit == StandbyExit.PARKED) {
                    // 挂起是「收起页面」，不是结束：状态已由 park 落库，finally 不会推 run.finished
                    return;
                }
                if (exit == StandbyExit.ENDED) {
                    // 与 SCAN_DONE 分开记：一个是「该做的做完了」，一个是「中途被关掉了值守」，
                    // 合并成一个码之后日报里这两种结束就分不开了（spec 0005 §3.6）。
                    finish(run, RunStatus.COMPLETED, "WATCH_DISABLED",
                            "值守已关闭，该平台没有可继续的工作，任务结束");
                    return;
                }
                // RESCAN：状态已置回 RUNNING，回到循环顶部接着扫（scanEnabled 为 false 时不会走到这里）
            }
        } catch (BlockedException e) {
            RunStatus status = switch (e.kind()) {
                case LOGIN_LOST -> RunStatus.LOGIN_LOST;
                case DRIVER_UNAVAILABLE -> RunStatus.FAILED;
                default -> RunStatus.CIRCUIT_OPEN;
            };
            finish(run, status, e.kind().name(), e.getMessage());
            publish(run, "circuit.opened", Map.of("kind", e.kind().name(), "message", e.getMessage()));
            notify(run, "circuit", "任务已熔断：" + e.kind().name(), e.getMessage());
        } catch (Exception e) {
            runLog.error("任务执行失败：" + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            finish(run, RunStatus.FAILED, "EXCEPTION", e.getClass().getSimpleName() + ": " + e.getMessage());
            notify(run, "run-failed", "任务异常终止", String.valueOf(e.getMessage()));
        } finally {
            long costSeconds = (System.currentTimeMillis() - startedAt) / 1000;
            finishInteractionTracking(run, interactionsAtStart);
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

    // ---------------------------------------------------------------- 值守待命

    /** 待命循环的四种出口 */
    private enum StandbyExit {
        /** 回扫：跨自然日或配额/上限被调高，状态已置回 RUNNING */
        RESCAN,
        /** 用户点了停止 */
        STOPPED,
        /** 越过值守结束时刻，收起页面等下一个窗口（值守期没结束） */
        PARKED,
        /**
         * 运行中把值守开关关掉了：值守不再继续，待命也就没有理由继续。
         *
         * <p>不复用 {@link #STOPPED}：那一条落 {@code CANCELLED}，而这里不是用户停的，
         * 落 {@code CANCELLED} 会让日报把「配置被改」记成「被人中途停掉」。
         */
        ENDED
    }

    /**
     * 待命循环：扫描停了，值守不停。
     *
     * <p>为什么复用当前线程而不是另起一个：{@code PlaywrightExecutor} 是单线程串行的，
     * {@code BossAdapter} 又是单例持有 {@code listPage} / {@code threadPage} 两个字段。
     * 并行意味着两个线程交替操作同一批页面，历史上那正是「导航被打断成 net::ERR_ABORTED」的来源。
     *
     * <p>待命期不碰搜索页（不调 {@code openSearch}），{@code listPage} 保持上次扫描停在的位置。
     *
     * <p>四个出口里只有 {@link StandbyExit#RESCAN} 会回到扫描，而它的唯一来源是
     * {@link StandbyGate} 的 {@code SCAN} 判定 —— 那个判定在 {@code scanEnabled} 为 false 时
     * 直接返回 {@code WATCH}，所以「只值守」的 Run 不会因为跨日被判去回扫。
     *
     * @param scanEnabled 本轮是否参与扫描，取自 {@code runLoop} 启动时那一次读取
     */
    private StandbyExit standbyLoop(RunEntity run, ScanHost host, ScanStop reason, boolean scanEnabled) {
        int round = 0;
        long enteredAt = System.currentTimeMillis();
        RunLog runLog = runLogs.of(run);
        boolean collectMetrics = configStore.aiConfig().getCollectEffectMetrics() != null
                && configStore.aiConfig().getCollectEffectMetrics() == 1;

        while (true) {
            if (host.stopRequested()) {
                return StandbyExit.STOPPED;
            }
            host.awaitResume();
            if (host.stopRequested()) {
                return StandbyExit.STOPPED;
            }

            // 每轮重读配置：配额、评估上限、巡检间隔都可能在待命期被改。
            // 扫描开关故意不在这里重读：它在启动时定下「这一轮扫不扫」，重读会让
            // 「循环要不要扫」（runLoop 的局部变量）与「能不能回扫」（这里的入参）分成两个值，
            // 出现「发了回扫事件、状态改回 RUNNING，却没有真的去扫」。
            PlatformConfigEntity cfg = configStore.platformConfig(run.getPlatform());
            // 值守开关则相反，必须每轮重读：它是「即刻生效」的三个点之一（另外两个是插空巡检
            // 与 sleepInterruptibly 的每秒检查）。关掉之后待命没有继续的理由 —— STANDBY 的定义
            // 是「扫描已中止、值守仍在继续」，两者都不成立了就该结束，由 runLoop 收尾。
            // 走 ConfigStore 的读法而不复用上面那份 cfg：「缺省按开」的规则只有那一处，
            // 在调用点照抄一遍 `== null || != 0` 就等于第二份默认值。
            if (!configStore.platformWatchEnabled(run.getPlatform())) {
                runLog.info("该平台已关闭值守，本轮不再巡检，任务结束");
                return StandbyExit.ENDED;
            }
            DutyWindow window = DutyWindow.of(cfg);
            LocalTime now = time.now().toLocalTime();
            int interval = ConfigStore.clampWatchInterval(cfg.getWatchIntervalSeconds());
            int delivered = runStore.deliveredToday(run.getPlatform());
            int evaluated = runStore.evaluatedToday(run.getPlatform());

            StandbyGate.Action action = StandbyGate.decide(now, window, reason, run.getScanDay(), time.today(),
                    delivered, ScanBoundary.quotaOf(cfg), evaluated, ScanBoundary.evalLimitOf(cfg), scanEnabled);
            if (action == StandbyGate.Action.PARK) {
                park(run, "DUTY_END");
                return StandbyExit.PARKED;
            }
            if (action == StandbyGate.Action.SCAN) {
                boolean newDay = run.getScanDay() == null || !time.today().equals(run.getScanDay());
                String code = newDay ? "NEW_DAY"
                        : reason == ScanStop.EVAL_LIMIT ? "EVAL_LIMIT_RAISED" : "QUOTA_RAISED";
                String why = newDay ? "已跨自然日（" + time.today() + "），恢复扫描"
                        : reason == ScanStop.EVAL_LIMIT
                        ? "评估上限已回升（" + evaluated + "/" + ScanBoundary.evalLimitOf(cfg) + "），恢复扫描"
                        : "主动投递配额已回升（" + delivered + "/" + ScanBoundary.quotaOf(cfg) + "），恢复扫描";
                runLog.info(why);
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
            act(run, "检查未读消息：读取消息列表");
            UnreadWatcher.SweepResult sweep = unreadWatcher.sweep(
                    adapter, run.getPlatform(), collectMetrics, false, runLog);
            host.publishUnread(sweep);
            // 抖动：每 120 秒整刷一次列表是个太像机器的特征
            int jittered = jitterSeconds(interval);
            long standbyMinutes = (System.currentTimeMillis() - enteredAt) / 60_000;
            // 0 未读也要有一行：等待期间完全静默时，人无法区分「在等」和「卡死」。
            // 这一行现在会落进 run_log —— 值守期的每一轮都留痕，是「不产生疑惑」的代价。
            runLog.info("值守待命第 " + round + " 轮：会话 " + sweep.threads()
                    + "，回复 " + sweep.replied() + "，未命中 " + sweep.unmatched()
                    + (sweep.retried() > 0 ? "，重试 " + sweep.retried() : "")
                    + (sweep.failed() > 0 ? "，失败 " + sweep.failed() : "")
                    + "；下次巡检 " + time.now().toLocalTime().plusSeconds(jittered).format(HHMMSS)
                    + "（已待命 " + standbyMinutes + " 分钟）");
            sleepInterruptibly(run, window, jittered * 1000L);
        }
    }

    /**
     * 进入值守待命。四种扫描中止原因共用这一个出口 —— 分开写就会有人漏改一处，
     * 而漏改的那一处正好是「配额用完就收工」的老行为。
     *
     * <p>{@link ScanStop#SCAN_DISABLED} 与其余三种不是一类事：其余三种是
     * 「今天不扫了」，这一种是「从来没有扫」。所以横幅与通知标题都要另写一句，
     * 沿用「扫描已结束（…）」会与实际不符，读日志的人会去翻组合表，而那张表是空的。
     */
    private void enterStandby(RunEntity run, ScanStop reason) {
        RunLog runLog = runLogs.of(run);
        PlatformConfigEntity cfg = configStore.platformConfig(run.getPlatform());
        DutyWindow window = DutyWindow.of(cfg);
        int delivered = runStore.deliveredToday(run.getPlatform());
        int evaluated = runStore.evaluatedToday(run.getPlatform());
        ScanStop why0 = reason == null ? ScanStop.COMBOS_EXHAUSTED : reason;
        String interval = intervalLabel(ConfigStore.clampWatchInterval(cfg.getWatchIntervalSeconds()));
        String tail = window.alwaysOn()
                ? "自动回复时段设为全天，中途不会停下"
                : "到 " + DutyWindow.format(window.end()) + " 会停止操作并进入等待";
        boolean neverScanned = why0 == ScanStop.SCAN_DISABLED;
        // 超时不是「扫描结束」：还剩卡没看，断点也停在原地。沿用「已结束」会让人以为这一轮
        // 把 300 张都过了一遍，而界面上「岗位 N」又恰好能证明没走完，两句话互相矛盾。
        boolean timedOut = why0 == ScanStop.COMBINATION_TIMEOUT;
        String message = neverScanned
                ? "这个平台没有开启「搜索岗位」，本轮只处理消息：每 " + interval + "检查一次未读消息，" + tail
                : (timedOut ? "搜索暂停（" : "搜索已结束（") + whyOf(why0, cfg, delivered, evaluated, scannedCards(run))
                + "），继续处理消息：每 " + interval + "检查一次未读消息，" + tail
                // 超时保留断点，所以「怎么接着扫」必须写出来：不写的话，唯一的动作暗示是
                // 「调高名额」，而真实需求可能只是等明天
                + (timedOut ? "；想过零点或把名额调高后继续搜索，不需要重新启动" : "");

        runStore.markStandby(run, message);
        act(run, neverScanned ? "正在盯着消息：没有开启「搜索岗位」，只检查消息" : "正在盯着消息：等下一次检查");
        runLog.info(message);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", why0.name());
        data.put("delivered", delivered);
        data.put("evaluated", evaluated);
        data.put("quota", ScanBoundary.quotaOf(cfg));
        publish(run, "run.standby", data);
        notify(run, "standby",
                neverScanned ? "没有开启搜索，正在处理消息" : timedOut ? "搜索超时，继续处理消息" : "搜索已结束，继续处理消息",
                message);
    }

    /**
     * 「扫描为什么停」的一句话，横幅与结束文案共用。
     *
     * <p>不共用就会出现两份「配额已用完 / 评估已达上限 / 组合已扫完」的措辞，改一处漏一处；
     * 而那种漏改在日志里表现为同一件事有两种说法，排查时会当成两件事。
     *
     * <p><b>组合耗尽这一支必须带上实际用量</b>：它是唯一一种「没到任何上限却不再扫描」的停法，
     * 只写「所有搜索组合都跑完了」时，读日志的人第一反应是"是不是提前退出了"——
     * 而真实原因是候选池就这么大（或者上一环漏了岗位）。把 {@code 49/400}、{@code 15/100}
     * 摆在同一行，才能把这两种情况分开。
     *
     * @param cards 本轮已加载过的卡片总数（各组合 {@code cards_seen} 之和）
     */
    private String whyOf(ScanStop reason, PlatformConfigEntity cfg, int delivered, int evaluated,
                         int cards) {
        return switch (reason) {
            case QUOTA -> "今天主动联系的名额已经用完（" + delivered + "/" + ScanBoundary.quotaOf(cfg) + "）";
            case EVAL_LIMIT -> "今天查看岗位的数量已达上限（" + evaluated + "/" + ScanBoundary.evalLimitOf(cfg) + "）";
            // 不再套一层括号：调用方已经把这一句包在「搜索已结束（…）」里，再套一层读起来像嵌套条件
            case COMBOS_EXHAUSTED -> "所有搜索组合都跑完了，共 " + cards + " 张卡；评估 " + evaluated + "/"
                    + ScanBoundary.evalLimitOf(cfg) + "、投递 " + delivered + "/" + ScanBoundary.quotaOf(cfg) + " 都还没到上限";
            // 带上具体上限值与剩余量：只说「超时」，读的人分不清是配置上限到了还是页面卡死了
            case COMBINATION_TIMEOUT -> "单个搜索组合跑到了时间上限 "
                    + ConfigStore.normalizeCombinationTimeLimit(cfg.getCombinationTimeLimitSec())
                    + "s，这个组合还有岗位没有看完";
            // 只值守的 Run 是从这里进待命的，横幅另写了文案；保留这一支是为了让 switch 覆盖全枚举
            case SCAN_DISABLED -> "没有开启「搜索岗位」";
        };
    }

    /**
     * 同上一句，当日计数与卡片数自己取。
     *
     * <p>给 {@code runLoop} 的「只扫描」收尾用：那里没有 {@code enterStandby} 已经算好的数字，
     * 而这句话必须带上实际值 ——「配额已用完（30/30）」与不带数字的「配额已用完」，
     * 在排查时是两件事。
     */
    private String whyOf(ScanStop reason, RunEntity run) {
        String platform = run.getPlatform();
        return whyOf(reason, configStore.platformConfig(platform),
                runStore.deliveredToday(platform), runStore.evaluatedToday(platform), scannedCards(run));
    }

    /** 本轮已加载过的卡片总数。只用于日志：组合表是唯一记得「这一轮到底有多少张卡」的地方。 */
    private int scannedCards(RunEntity run) {
        if (run.getId() == null) {
            return 0;
        }
        int total = 0;
        for (CombinationRunEntity combo : runStore.combinations(run.getId())) {
            total += count(combo.getCardsSeen());
        }
        return total;
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
        run.setLastActivityAt(time.stamp());
        runStore.touchActivity(run.getId(), action);
    }

    private void park(RunEntity run, String reason) {
        DutyWindow window = DutyWindow.of(configStore.platformConfig(run.getPlatform()));
        LocalTime now = time.now().toLocalTime();
        String message = "已到自动回复结束时间（" + window.describe() + "，现在 " + DutyWindow.format(now) + "），"
                + "已停止操作；" + window.nextOpenHint(now) + "，这一轮还没有结束，不需要重新启动";
        runStore.markWaiting(run, message);
        act(run, "已排队，等 " + window.describe() + " 这个时段开始");
        runLogs.of(run).info(message);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason);
        data.put("window", window.describe());
        data.put("at", DutyWindow.format(now));
        data.put("opensInMinutes", window.opensInMinutes(now));
        publish(run, "run.waiting", data);
        notify(run, "duty-end", "自动回复时段已结束，任务先排队", message);
    }

    /**
     * 跨自然日恢复扫描前调用；同日重复调用是 no-op。
     *
     * <p>复位而不是「继续」：昨天扫完的组合队列状态是 DONE，原样再跑一遍只会全部跳过，
     * 第二天一个岗位都不投，而日志上看不出任何异常。
     */
    private void ensureScanDay(RunEntity run) {
        String today = time.today();
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
            runLogs.of(run).info("进入新的一天（" + today + "），组合队列复位，从第 1 个组合重新扫描");
        }
        runStore.update(run);
    }

    /**
     * 可打断的睡眠：每秒检查一次停止、时段结束与值守开关。
     *
     * <p>不能直接 {@code Thread.sleep(interval)}：22:00 到点最多要等两分钟才收起页面，
     * 而「时段外不操作页面」是硬约束。暂停也靠这里尽快生效。
     *
     * <p>值守开关也放在这个每秒检查里：只靠 {@code standbyLoop} 顶部的判定，关掉之后最坏要等满
     * 一个巡检间隔（按分钟计）才停；而「关掉值守后最多一秒停止巡检」是 spec 0005 的验收口径。
     * 代价是每秒读一次本地 SQLite 的一行，比等满一个间隔便宜得多。
     */
    private void sleepInterruptibly(RunEntity run, DutyWindow window, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested || !window.contains(time.now().toLocalTime())
                    || !configStore.platformWatchEnabled(run.getPlatform())) {
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

    /**
     * 演练模式跑完即结束，不进入待命。
     *
     * <p>演练会真点开会话清红点，整夜待命会把第二天的未读消息提前标记为已读 —— 那会让演练本身失真。
     * 三个中止原因在演练下都收敛成 {@code COMPLETED}，与改造前的行为一致。
     *
     * <p>{@code SCAN_DISABLED} 在这里只有一个来源：跑到一半扫描被关掉。启动时就没开扫描的那个
     * 分支在 {@code runLoop} 入口直接收尾，走不到这里 —— 所以两者文案不同，前者不能说
     * 「没有可演练的内容」，它已经演练过几张卡了。
     */
    private void finishDryRun(RunEntity run, ScanStop stop) {
        switch (stop == null ? ScanStop.COMBOS_EXHAUSTED : stop) {
            case QUOTA -> finish(run, RunStatus.COMPLETED, "QUOTA_REACHED",
                    "演练模式：今日主动投递配额已用完，演练结束（不进入待命）");
            case EVAL_LIMIT -> finish(run, RunStatus.COMPLETED, "EVAL_LIMIT_REACHED",
                    "演练模式：今日评估已达上限，演练结束（不进入待命）");
            case SCAN_DISABLED -> finish(run, RunStatus.COMPLETED, "SCAN_DISABLED",
                    "演练模式：扫描已在运行中被关闭，演练结束（不进入待命）");
            case COMBOS_EXHAUSTED -> finish(run, RunStatus.COMPLETED, "COMPLETED", "组合队列已跑完");
            case COMBINATION_TIMEOUT -> finish(run, RunStatus.COMPLETED, "COMBINATION_TIMEOUT",
                    "演练模式：组合时间上限已到，演练结束（不进入待命）");
        }
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

    /**
     * 单岗位的决策与投递。
     *
     * @param query 这张卡所属的搜索组合。投递时随 {@link DeliveryOptions} 一起交给适配器，
     *              用来拼"详情页的第 1 跳"那个列表页地址（spec 0006 §3.5）
     */
    private void processOneJob(RunEntity run, PlatformConfigEntity cfg, JobDetail detail, SearchQuery query) {
        RunLog runLog = runLogs.of(run);
        BlacklistMatcher blacklist = new BlacklistMatcher(configStore.blacklist());
        var hit = blacklist.match(detail.companyName(), detail.jobName());
        if (hit.isPresent()) {
            BlacklistMatcher.Hit h = hit.get();
            runLog.info("丢弃（黑名单「" + h.keyword() + "」）：" + detail.companyName()
                    + " / " + detail.jobName());
            return;
        }

        ActiveLevel level = ActiveLevel.of(detail.activeRaw());
        ActiveLevel floor = ActiveLevel.fromLabel(cfg.getActiveLevelFloor());
        if (ActiveLevel.isBelowFloor(level, floor)) {
            runLog.info("丢弃（HR 活跃度 " + level.label() + " 低于下限 "
                    + cfg.getActiveLevelFloor() + "）：" + detail.jobName());
            return;
        }

        JobPostingEntity job = jobStore.upsert(detail, run.getPlatform(), level.label());

        JobStore.Decision decision = jobStore.decide(job.getId());
        if (decision.skip()) {
            runLog.info("跳过（" + decision.reason() + "）：" + job.getJobName() + " @ " + job.getCompanyName());
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
            act(run, "AI 正在判断：" + job.getJobName() + " @ " + job.getCompanyName());
            AiJudge.Verdict verdict = aiJudge.judge(detail, blacklist);
            if (verdict.isFailure()) {
                // fail-closed：判定不出来就不投
                DeliveryEntity delivery = jobStore.startDelivery(job.getId(), run.getId(), "AI");
                // 记 FAILED 而不是 SKIPPED：SKIPPED 在 JobStore.decide 里意味着"已判定不投"，
                // 会给该岗位打 24 小时冷却。而这里根本没有判定结论 —— 模型不可达、返回坏了，
                // 都被冷却吸收的话，一次模型故障会让整批岗位一天内不再尝试，且看起来像"AI 认为不合适"。
                // 记成 FAILED 则下一轮会重新评估，连续失败达到上限后转人工。
                jobStore.finishDelivery(delivery, DeliveryResult.failed("AI 判定失败：" + verdict.error()),
                        null, verdict.error(), null);
                publish(run, "job.evaluated", jobEvent(job, "AI_FAILED", 0, verdict.error()));
                runLog.warn("AI 判定失败，本轮不投（下轮重试）：" + job.getJobName() + " — " + verdict.error());
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
                runLog.info("AI 判定不投（" + score + " 分）：" + job.getJobName() + " — " + verdict.reason());
                return;
            }
            greeting = verdict.greeting();
            decidedBy = "AI";
        } else {
            greeting = configStore.aiConfig().getTemplateGreeting();
            decidedBy = "TEMPLATE";
            if (greeting == null || greeting.isBlank()) {
                runLog.warn("质量开关已关闭但模板招呼语为空，跳过");
                return;
            }
        }

        publish(run, "job.evaluated", jobEvent(job, "PASS", score == null ? 0 : score, aiReason));

        if ("DRY_RUN".equals(run.getMode())) {
            DeliveryEntity delivery = jobStore.startDelivery(job.getId(), run.getId(), decidedBy);
            jobStore.finishDelivery(delivery, DeliveryResult.skipped("演练模式，未实际投递"),
                    score, aiReason, aiRaw);
            runLog.info("演练模式：本会投递 " + job.getJobName() + " @ " + job.getCompanyName());
            return;
        }

        DeliveryEntity delivery = jobStore.startDelivery(job.getId(), run.getId(), decidedBy);
        boolean allowRepeat = cfg.getRepeatChatEnabled() != null && cfg.getRepeatChatEnabled() == 1;
        DeliveryOptions options = new DeliveryOptions(allowRepeat, configStore.repeatMessages(cfg), query);
        act(run, "投递中：" + job.getJobName() + " @ " + job.getCompanyName());
        DeliveryResult result = adapter().deliver(detail, greeting, options, this::shouldStop);
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
            runLog.info("已投递（" + run.getDeliveredCount() + "/" + ScanBoundary.quotaOf(cfg) + "）："
                    + job.getJobName() + " @ " + job.getCompanyName());
        } else if (result.status() == DeliveryResult.Status.SKIPPED) {
            runLog.info("未投递（" + result.failureReason() + "）：" + job.getJobName());
        } else {
            runLog.warn("投递失败（" + result.failureReason() + "）：" + job.getJobName());
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

    /** 计数列取值的 null 安全版本：老库的行、内存里刚 new 出来的实体都可能是 null。 */
    private static int count(Integer value) {
        return value == null ? 0 : value;
    }

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
        LocalTime now = time.now().toLocalTime();
        if (window.contains(now)) {
            return true;
        }
        long waitMinutes = window.opensInMinutes(now);
        // nextOpenHint 自带「之后才会真正执行」，这里不要再接「自动开始」，否则读成「真正执行自动开始」
        String message = "现在是 " + DutyWindow.format(now) + "，不在自动回复时段 " + window.describe()
                + " 内，任务先排队；" + window.nextOpenHint(now);
        runStore.markWaiting(run, message);
        act(run, "已排队，等 " + window.describe() + " 这个时段开始");
        runLogs.of(run).info(message);
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
     * 等待态的唤醒器（<b>本平台</b>那一条）。
     *
     * <p>为什么不让线程 sleep 到明天：线程活不过进程重启，而「昨晚点的启动、今早九点还在等着」
     * 恰恰是这个功能最有价值的场景。等待态落库 + 这里每 30s 轮询一次，重启后也能接上。
     *
     * <p>synchronized 与 start()/stop() 同一把锁：唤醒本质上就是「替用户再点一次启动」，
     * 必须和人工启动互斥，否则会同时起两个 worker 抢同一个浏览器档案。
     *
     * <p>2b：本方法不再自带 {@code @Scheduled} —— 本类是 prototype，给它挂调度注解会变成
     * 「每创建一份实例就多注册一个定时任务」。定时的唯一入口是
     * {@link RunServices#wakeWaitingRuns()}，它遍历已装配平台逐个调到这里。
     */
    public synchronized void wakeIfWaiting() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        RunEntity waiting = runStore.waiting(adapter().platform()).orElse(null);
        if (waiting == null) {
            return;
        }
        DutyWindow window = DutyWindow.of(configStore.platformConfig(waiting.getPlatform()));
        LocalTime now = time.now().toLocalTime();
        if (!window.contains(now)) {
            logParkedHeartbeat(waiting, window, now);
            return;
        }
        parkedHeartbeatRunId = null;
        runLogs.of(waiting).info("值守时段已到（现在 " + DutyWindow.format(now) + "），开始执行挂起的任务");
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
     * 进程重启后的收尾：把库里「没有任何线程持有」的 Run 标为失败（<b>本平台</b>那几条）。
     *
     * <p>单进程部署下，启动那一刻必然不持有任何线程，所以 CREATED/RUNNING/PAUSED/STANDBY 全是上一轮的孤儿。
     * 不清掉它们会两处出事：uk_run_running 唯一索引挡住新 Run 的创建；active() 让用户看到
     * 「已有进行中的任务」却找不到那个任务。等待态（WAITING_DUTY）不在此列 —— 它本来就该留着等。
     *
     * <p>2b：{@code orphaned()} 是全局查询，这里按平台过滤 —— 每个平台一份实例，各自收各自的，
     * 合计与改造前的「一次全清」等价。不自带 {@code @EventListener}：prototype 上注册监听
     * 会随实例创建次数重复触发。唯一入口是 {@link RunServices#recoverAfterRestart()}。
     */
    public synchronized void recoverOrphans() {
        String platform = adapter().platform();
        for (RunEntity run : runStore.orphaned()) {
            if (!platform.equals(run.getPlatform())) {
                continue;
            }
            // 组合表不会跟着 Run 一起收尾：进程被强杀时组合循环的 finally 不会执行，
            // 断在半路的那个组合会永远停在 RUNNING。Run 标成失败之后，这一行就成了
            // 界面上出现与事实不符的状态（Run 已结束，组合仍显示「进行中」），所以在 finish 之前先更新
            int abandoned = runStore.abandonRunningCombinations(run.getId());
            if (abandoned > 0) {
                runLogs.of(run).warn("上一轮中断时有 " + abandoned + " 个组合停在扫描中间，已记为「已中断」");
            }
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
        runLogs.of(waiting).info("仍挂起：现在 " + DutyWindow.format(now) + "，值守时段 "
                + window.describe() + "，" + window.nextOpenHint(now)
                + "（值守期 #" + waiting.getId() + " 仍在）");
    }

    /** 启动后立刻把「这一轮能跑多久」写进日志——时段是运行时长上限，不是启动门槛 */
    private void logDutyDeadline(RunEntity run) {
        RunLog runLog = runLogs.of(run);
        if ("DRY_RUN".equals(run.getMode())) {
            runLog.info("演练模式不受值守时段约束，本轮跑到组合队列耗尽为止");
            return;
        }
        DutyWindow window = DutyWindow.of(configStore.platformConfig(run.getPlatform()));
        if (window.alwaysOn()) {
            runLog.info("值守时段为全天，本轮不会因时段收工");
            return;
        }
        long minutes = window.closesInMinutes(time.now().toLocalTime());
        String left = minutes < 0 ? "" : "（还能跑约 " + minutes + " 分钟）";
        runLog.info("值守时段 " + window.describe() + "，到 " + DutyWindow.format(window.end())
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
        LocalTime now = time.now().toLocalTime();
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

    private boolean shouldStop() {
        return stopRequested;
    }

    /**
     * 运行日志是控制台上给操作者看的主要证据流，里面不该出现裸的 {@code DRY_RUN} ——
     * 那是个内部枚举，读起来不知道「这意味着什么」。带括号的说明放在这里，
     * 因为日志里第一次出现模式名就是这一行，读者需要当场知道「不会发消息」。
     */
    private static String modeLabel(String mode) {
        return "DRY_RUN".equals(mode) ? "演练模式（不发送任何消息）" : "正常投递";
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
        runLogs.of(run).log(status == RunStatus.COMPLETED || status == RunStatus.QUOTA_REACHED
                ? RunLogLevel.INFO : RunLogLevel.WARN, status + " / " + reason + " / " + message);
    }

    // ---------------------------------------------------------------- 人类化交互的观测

    /**
     * 开始记录本条 Run 的交互计数与降级明细，返回起始快照。
     *
     * <p>为什么用采样差而不是"开始时清零"：这个实例是<b>平台级</b>的，比 Run 活得久
     * （spec 0006 §3.7）。清零会让交叠的两条 Run 互相清掉对方的数；而且清零这个动作
     * 本身要写进别人的状态里，多一条隐式依赖。
     *
     * <p>降级回调在 Run 开始时注册、结束时撤销：它要写 {@code run_log}，而 {@code run_log}
     * 需要 {@code runId}——本层拿不到，只有编排层知道。
     */
    private HumanCounters.Snapshot beginInteractionTracking(RunEntity run, String platform) {
        Humanize human = sessions.of(platform).humanize();
        RunLog runLog = runLogs.of(run);
        human.onFallback((kind, reason) -> runLog.warn("交互降级[" + kindLabel(kind) + "] " + reason));
        // 页面自己打开的标签页（点到 target=_blank 的链接）。记 INFO 而不是 WARN：
        // 它不是故障，但必须留下痕迹 —— 否则「任务结束后浏览器里多了一堆详情页标签」
        // 只能靠人去数标签，日志里一句话都没有。
        human.onNewTab((url, note) -> runLog.info(
                "多出一个标签页（" + note + "）：" + (url == null || url.isBlank() ? "地址未知" : url)
                        + "，已关闭"));
        return human.counters().snapshot();
    }

    /**
     * 撤销降级回调并写一行计数汇总。
     *
     * <p><b>一次交互都没有时不写这一行。</b>这既是"没有内容就不占日志"，
     * 也是 {@code humanize.enabled=false} 的验收前提：关掉之后本层不做任何计数，
     * 于是这行永远不出现，{@code run_log} 与改造前逐行相同（spec 0006 §2.1 第 12 条）。
     */
    private void finishInteractionTracking(RunEntity run, HumanCounters.Snapshot start) {
        if (start == null) {
            return;
        }
        Humanize human = sessions.of(run.getPlatform()).humanize();
        human.onFallback(null);
        // 与新标签页监听同寿：Run 结束之后再冒出来的页面不属于任何一条 Run，不写进日志
        human.onNewTab(null);
        HumanCounters.Snapshot delta = human.counters().snapshot().minus(start);
        if (delta.interactions() == 0 && delta.fallbacks() == 0) {
            return;
        }
        runLogs.of(run).info(HumanCounters.format(delta)
                + " / 降级率 " + (Math.round(delta.fallbackRate() * 1000) / 10.0) + "%");
    }

    /** 动作类型的中文名。只用于日志，所以放在这里而不是 {@code HumanCounters}。 */
    private static String kindLabel(HumanCounters.Kind kind) {
        return switch (kind) {
            case CLICK -> "点击";
            case TYPE -> "输入";
            case SCROLL -> "滚动";
        };
    }

    private void publish(RunEntity run, String type, Object data) {
        sse.publish(run.getId(), type, data);
    }

    /**
     * 唯一的通知出口，带平台展示名。
     *
     * <p>两个平台并行之后，手机上的「任务已熔断」不说是哪个平台等于没说
     * （spec 0004 阶段 2c 的验收：通知能看出是哪个平台）。平台名取展示名而不是 slug ——
     * 通知是给人看的，{@code zhaopin} 这个词在手机上没有意义。
     *
     * <p>用 {@code run.getPlatform()} 而不是本实例绑定的平台，理由与 {@link #log} 相同。
     */
    private void notify(RunEntity run, String kind, String title, String body) {
        notifier.notify(kind, PlatformInfo.of(run.getPlatform()).label(), title, body);
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
        DutyWindow window = DutyWindow.of(configStore.platformConfig(adapter().platform()));
        LocalTime now = time.now().toLocalTime();
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

    /**
     * 本实例服务的平台。
     *
     * <p>替代原来的 {@code session()}：会话不该从编排层借道传出（那是 driver 层的对象，
     * 借出去之后谁在哪个线程上操作页面就说不清了）。要查「这个平台的浏览器起来没有」，
     * 走 {@code BrowserSessions.existing(platform)}。
     */
    public String platform() {
        return adapter().platform();
    }

    /**
     * 「多久没动就算无响应」。阈值只在后端算一次、随 {@code /runs/current} 下发，
     * 前端只做比较 —— 这条规则写两份，两侧迟早分叉，而分叉的表现是
     * 「界面报无响应、日志还在滚」，最消耗信任。
     */
    public int staleAfterSeconds(RunEntity run) {
        PlatformConfigEntity cfg = configStore.platformConfig(adapter().platform());
        RunStatus status = run == null || run.getStatus() == null ? null : RunStatus.valueOf(run.getStatus());
        return ActivityStaleness.staleAfterSeconds(status,
                cfg.getWaitTimeSeconds() == null ? 10 : cfg.getWaitTimeSeconds(),
                ConfigStore.clampWatchInterval(cfg.getWatchIntervalSeconds()));
    }

    /**
     * 一条 Run 的宿主实现：把 {@link ScanHost} 的问题翻成本类的运行状态与出口。
     *
     * <p>为什么是内部类而不是让 {@code RunService} 直接实现接口：接口方法不带 {@code run} 参数，
     * 而这里每个动作都要知道「哪条 Run」。内部类把它绑在实例上，本类也就不用回答
     * 「现在跑的是哪条 Run」—— 那个问题在 {@code wakeIfWaiting} 里没有正确答案
     * （手里的 Run 与 worker 正在跑的不是同一条，见 ADR-0008 第 2 条）。
     */
    private final class RunHost implements ScanHost {

        private final RunEntity run;

        private RunHost(RunEntity run) {
            this.run = run;
        }

        @Override
        public boolean stopRequested() {
            return RunService.this.stopRequested;
        }

        @Override
        public boolean interrupted() {
            return RunService.this.stopRequested || RunService.this.dutyEnded;
        }

        @Override
        public void awaitResume() {
            RunService.this.awaitResume(run);
        }

        @Override
        public boolean endIfOutOfDuty(PlatformConfigEntity cfg) {
            return RunService.this.checkDutyWindow(run, cfg);
        }

        @Override
        public void act(String action) {
            RunService.this.act(run, action);
        }

        @Override
        public void publish(String type, Object data) {
            RunService.this.publish(run, type, data);
        }

        @Override
        public void publishUnread(UnreadWatcher.SweepResult sweep) {
            RunService.this.publishUnread(run, sweep);
        }
    }
}
