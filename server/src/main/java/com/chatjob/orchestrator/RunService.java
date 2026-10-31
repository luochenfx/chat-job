package com.chatjob.orchestrator;

import com.chatjob.driver.BlockedException;
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
import com.chatjob.platform.PlatformAdapters;
import com.chatjob.platform.PlatformInfo;
import com.chatjob.platform.PlatformSwitches;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.CombinationRunEntity;
import com.chatjob.store.entity.DeliveryEntity;
import com.chatjob.store.entity.JobPostingEntity;
import com.chatjob.store.entity.PlatformConfigEntity;
import com.chatjob.store.entity.RunEntity;
import com.chatjob.watcher.UnreadWatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
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
 */
@Slf4j
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class RunService {

    /** 每处理这么多张卡片做一次未读检查 */
    private static final int WATCH_EVERY_CARDS = 5;
    /** 或者每隔这么久（秒）做一次 */
    private static final int WATCH_EVERY_SECONDS = 120;

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
                      PlatformSwitches switches) {
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
        boolean parked = !"DRY_RUN".equals(effectiveMode) && !DutyWindow.of(cfg).contains(LocalTime.now());

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
            log(run, "INFO", "任务启动，模式 " + modeLabel(run.getMode()));
            logDutyDeadline(run);

            // 本次运行要不要扫描，只在启动这一刻读一次。运行中把它关掉由两个卡片/组合边界生效；
            // 关掉后重新打开要等下次启动 —— 这个值在整轮里不再变，是为了让「循环要不要扫」
            // 与传给 StandbyGate 的判断始终是同一个（spec 0005 §3.3 第 1 条）。
            boolean scanEnabled = configStore.platformScanEnabled(platform);
            // 只扫描不值守的这一轮，正常结局就是「扫完即收尾」。提前写一行说明，否则最后的
            // COMPLETED / SCAN_DONE 会被读成任务提前退出 —— 这一轮的 unread.detected 事件
            // 本来就一条都不会有，日志里没有别的痕迹能解释它为什么结束。
            if (!configStore.platformWatchEnabled(platform)) {
                log(run, "INFO", "该平台未启用值守：本轮只扫描，扫描结束后收尾，不进入值守待命");
            }
            if (!scanEnabled) {
                // 只值守：不建组合、不开搜索页，日志里也不出现「开始扫描」「开始组合」
                if ("DRY_RUN".equals(run.getMode())) {
                    // 演练会真点开会话，没有可扫的内容时它的唯一产出也没有了，直接收尾不进待命
                    finish(run, RunStatus.COMPLETED, "SCAN_DISABLED",
                            "演练模式：该平台未启用扫描，没有可演练的内容");
                    return;
                }
                enterStandby(run, StandbyGate.StopReason.SCAN_DISABLED);
            } else {
                act(run, "开始搜索岗位");
            }

            // 值守期是「扫描 + 值守」两条腿：扫描因配额 / 评估上限 / 组合耗尽停下时，
            // 只回到待命，不结束整轮；待命期配额被调高或跨了自然日，再从这里回到扫描。
            // 关闭扫描的平台只有值守这一条腿：整个循环不经过扫描分支，只反复待命巡检。
            StandbyGate.StopReason reason = scanEnabled ? null : StandbyGate.StopReason.SCAN_DISABLED;
            while (true) {
                if (scanEnabled) {
                    ensureScanDay(run);
                    reason = executeCombinations(run);

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
                    // reason 在这里必不为 null：executeCombinations 只在停止或到点两处返回 null，
                    // 而这两种情况上面已经各自 return 了。
                    // 读当下的配置而不是启动那一刻的值：扫描中途被关掉的值守，要在走到这里时
                    // 直接收尾，而不是先进待命发一条「值守继续」的横幅与企业微信通知、再立刻结束。
                    if (!configStore.platformWatchEnabled(run.getPlatform())) {
                        finish(run, RunStatus.COMPLETED, "SCAN_DONE",
                                "扫描已结束（" + whyOf(reason, run.getPlatform())
                                        + "），该平台未启用值守，任务结束");
                        return;
                    }
                    enterStandby(run, reason);
                }

                StandbyExit exit = standbyLoop(run, reason, scanEnabled);
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
            log.error("任务执行失败", e);
            finish(run, RunStatus.FAILED, "EXCEPTION", e.getClass().getSimpleName() + ": " + e.getMessage());
            notify(run, "run-failed", "任务异常终止", String.valueOf(e.getMessage()));
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
    private StandbyExit standbyLoop(RunEntity run, StandbyGate.StopReason reason, boolean scanEnabled) {
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
                log(run, "INFO", "该平台已关闭值守，本轮不再巡检，任务结束");
                return StandbyExit.ENDED;
            }
            DutyWindow window = DutyWindow.of(cfg);
            LocalTime now = LocalTime.now();
            int interval = ConfigStore.clampWatchInterval(cfg.getWatchIntervalSeconds());
            int delivered = runStore.deliveredToday(run.getPlatform());
            int evaluated = runStore.evaluatedToday(run.getPlatform());

            StandbyGate.Action action = StandbyGate.decide(now, window, reason, run.getScanDay(), RunStore.today(),
                    delivered, quota(cfg), evaluated, evalLimit(cfg), scanEnabled);
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
            act(run, "检查未读消息：读取消息列表");
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
            sleepInterruptibly(run, window, jittered * 1000L);
        }
    }

    /**
     * 进入值守待命。四种扫描中止原因共用这一个出口 —— 分开写就会有人漏改一处，
     * 而漏改的那一处正好是「配额用完就收工」的老行为。
     *
     * <p>{@link StandbyGate.StopReason#SCAN_DISABLED} 与其余三种不是一类事：其余三种是
     * 「今天不扫了」，这一种是「从来没有扫」。所以横幅与通知标题都要另写一句，
     * 沿用「扫描已结束（…）」会与实际不符，读日志的人会去翻组合表，而那张表是空的。
     */
    private void enterStandby(RunEntity run, StandbyGate.StopReason reason) {
        PlatformConfigEntity cfg = configStore.platformConfig(run.getPlatform());
        DutyWindow window = DutyWindow.of(cfg);
        int delivered = runStore.deliveredToday(run.getPlatform());
        int evaluated = runStore.evaluatedToday(run.getPlatform());
        StandbyGate.StopReason why0 = reason == null ? StandbyGate.StopReason.COMBOS_EXHAUSTED : reason;
        String interval = intervalLabel(ConfigStore.clampWatchInterval(cfg.getWatchIntervalSeconds()));
        String tail = window.alwaysOn()
                ? "自动回复时段设为全天，中途不会停下"
                : "到 " + DutyWindow.format(window.end()) + " 会停止操作并进入等待";
        boolean neverScanned = why0 == StandbyGate.StopReason.SCAN_DISABLED;
        String message = neverScanned
                ? "这个平台没有开启「搜索岗位」，本轮只处理消息：每 " + interval + "检查一次未读消息，" + tail
                : "搜索已结束（" + whyOf(why0, cfg, delivered, evaluated) + "），继续处理消息：每 "
                + interval + "检查一次未读消息，" + tail;

        runStore.markStandby(run, message);
        act(run, neverScanned ? "正在盯着消息：没有开启「搜索岗位」，只检查消息" : "正在盯着消息：等下一次检查");
        log(run, "INFO", message);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", why0.name());
        data.put("delivered", delivered);
        data.put("evaluated", evaluated);
        data.put("quota", quota(cfg));
        publish(run, "run.standby", data);
        notify(run, "standby", neverScanned ? "没有开启搜索，正在处理消息" : "搜索已结束，继续处理消息", message);
    }

    /**
     * 「扫描为什么停」的一句话，横幅与结束文案共用。
     *
     * <p>不共用就会出现两份「配额已用完 / 评估已达上限 / 组合已扫完」的措辞，改一处漏一处；
     * 而那种漏改在日志里表现为同一件事有两种说法，排查时会当成两件事。
     */
    private String whyOf(StandbyGate.StopReason reason, PlatformConfigEntity cfg, int delivered, int evaluated) {
        return switch (reason) {
            case QUOTA -> "今天主动联系的名额已经用完（" + delivered + "/" + quota(cfg) + "）";
            case EVAL_LIMIT -> "今天查看岗位的数量已达上限（" + evaluated + "/" + evalLimit(cfg) + "）";
            case COMBOS_EXHAUSTED -> "所有搜索组合都跑完了";
            // 只值守的 Run 是从这里进待命的，横幅另写了文案；保留这一支是为了让 switch 覆盖全枚举
            case SCAN_DISABLED -> "没有开启「搜索岗位」";
        };
    }

    /**
     * 同上一句，当日计数自己取。
     *
     * <p>给 {@code runLoop} 的「只扫描」收尾用：那里没有 {@code enterStandby} 已经算好的数字，
     * 而这句话必须带上实际值 ——「配额已用完（30/30）」与不带数字的「配额已用完」，
     * 在排查时是两件事。
     */
    private String whyOf(StandbyGate.StopReason reason, String platform) {
        return whyOf(reason, configStore.platformConfig(platform),
                runStore.deliveredToday(platform), runStore.evaluatedToday(platform));
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
        String message = "已到自动回复结束时间（" + window.describe() + "，现在 " + DutyWindow.format(now) + "），"
                + "已停止操作；" + window.nextOpenHint(now) + "，这一轮还没有结束，不需要重新启动";
        runStore.markWaiting(run, message);
        act(run, "已排队，等 " + window.describe() + " 这个时段开始");
        log(run, "INFO", message);
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
            if (stopRequested || !window.contains(LocalTime.now())
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

    private static String stopLabel(StandbyGate.StopReason reason) {
        return switch (reason) {
            case QUOTA -> "每天主动联系的名额";
            case EVAL_LIMIT -> "每天查看岗位的上限";
            case COMBOS_EXHAUSTED -> "搜索组合已跑完";
            // 单独文案在 executeCombinations 里：那里「留待回扫时继续」这半句不成立，不能复用整句
            case SCAN_DISABLED -> "没有开启「搜索岗位」";
        };
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
    private void finishDryRun(RunEntity run, StandbyGate.StopReason stop) {
        switch (stop == null ? StandbyGate.StopReason.COMBOS_EXHAUSTED : stop) {
            case QUOTA -> finish(run, RunStatus.COMPLETED, "QUOTA_REACHED",
                    "演练模式：今日主动投递配额已用完，演练结束（不进入待命）");
            case EVAL_LIMIT -> finish(run, RunStatus.COMPLETED, "EVAL_LIMIT_REACHED",
                    "演练模式：今日评估已达上限，演练结束（不进入待命）");
            case SCAN_DISABLED -> finish(run, RunStatus.COMPLETED, "SCAN_DISABLED",
                    "演练模式：扫描已在运行中被关闭，演练结束（不进入待命）");
            case COMBOS_EXHAUSTED -> finish(run, RunStatus.COMPLETED, "COMPLETED", "组合队列已跑完");
        }
    }

    /**
     * 跑组合队列。
     *
     * <p>进组合之前先判一次扫描开关，而不只依赖卡片边界的判定：再往下一步就是
     * {@code openSearch}，关掉扫描之后不得再有任何搜索页导航（spec 0005 §3.5）。
     *
     * @return 扫描为什么停；{@code null} 表示被人停 / 到点 / 组合超时这类「不需要待命理由」的退出
     */
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
            // 组合入口也要判扫描开关，而不是只判卡片边界：再往下一步就是 openSearch，
            // 关掉扫描之后不得再有任何搜索页导航（spec 0005 §3.5）。放在「开始组合」之前，
            // 所以关掉之后日志里不会再出现这一行 —— 验收时正是看它。
            if (!configStore.platformScanEnabled(run.getPlatform())) {
                log(run, "INFO", "该平台已关闭扫描，停止扫描；组合 #" + combo.getSeq() + " 未开始");
                return StandbyGate.StopReason.SCAN_DISABLED;
            }
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
                adapter().openSearch(new SearchQuery(combo.getCity(), null, combo.getKeyword()));
                ScrollOutcome scroll = adapter().scrollUntilEnd(
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
                    if (stop == StandbyGate.StopReason.SCAN_DISABLED) {
                        // 「留待回扫时继续」在这里不成立：关掉扫描的 Run 不会被 StandbyGate 送回扫描
                        log(run, "INFO", "组合 #" + combo.getSeq() + " 中止：该平台已关闭扫描，剩余卡片不再处理");
                    } else {
                        log(run, "INFO", "组合 #" + combo.getSeq() + " 在「" + stopLabel(stop)
                                + "」处中止，剩余卡片留待回扫时继续");
                    }
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
            // 扫描开关也在卡片边界生效，与配额同一处：这是「上一张卡已经处理完」之后第一个安全点，
            // 半条招呼语比晚几秒停止更糟。这里必须返回 SCAN_DISABLED 而不是 null ——
            // executeCombinations 把 null 当作「这个组合超时」，会推进到下一个组合继续导航。
            // 读配置而不读 runLoop 里的局部变量：那个值只在启动时读一次，运行中关掉不会变。
            if (!configStore.platformScanEnabled(run.getPlatform())) {
                log(run, "INFO", "该平台已关闭扫描，在第 " + (card + 1) + " 张卡前停止；值守继续");
                return StandbyGate.StopReason.SCAN_DISABLED;
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

            act(run, "查看第 " + (card + 1) + " 个岗位的详情");
            JobDetail detail = adapter().fetchDetail(card);
            if (detail == null) {
                log(run, "WARN", "第 " + (card + 1) + " 张卡片没抓到详情，跳过");
                continue;
            }

            // 组合维度的账以前没人记：daily_counter 是平台维度的当日配额，run.evaluatedCount 是
            // 整个 Run 的累计，两者都不是「这个城市×关键词」这一格的数，于是 combination_run 的
            // evaluated / delivered 从建表起就一直是 0（Run 上写着 20，组合上写 0）。
            // 这里按单张卡处理前后的差值补记，而不是在 processOneJob 里再判一次「算不算评估/投递」——
            // 那里 incr 的两个点才是唯一口径，照抄一遍只会多出第二份真相。
            int evaluatedBefore = count(run.getEvaluatedCount());
            int deliveredBefore = count(run.getDeliveredCount());
            processOneJob(run, cfg, detail);
            int evaluatedDelta = count(run.getEvaluatedCount()) - evaluatedBefore;
            int deliveredDelta = count(run.getDeliveredCount()) - deliveredBefore;
            if (evaluatedDelta > 0 || deliveredDelta > 0) {
                combo.setEvaluated(count(combo.getEvaluated()) + evaluatedDelta);
                combo.setDelivered(count(combo.getDelivered()) + deliveredDelta);
                // 紧跟 checkpoint 落库：组合被配额 / 超时 / 停止打断时，已经扫过的那几张卡的账不能丢
                runStore.updateCombination(combo);
            }

            cardsSinceWatch++;
            boolean timeUp = (System.currentTimeMillis() - lastWatchAt) / 1000 >= WATCH_EVERY_SECONDS;
            if (cardsSinceWatch >= WATCH_EVERY_CARDS || timeUp) {
                // 计数先归零再判开关：关掉值守期间不该累计卡片数，否则重新打开的那一刻
                // 会立刻补一次巡检（那一刻并没有到间隔）。
                cardsSinceWatch = 0;
                lastWatchAt = System.currentTimeMillis();
                // 插空巡检是「即刻生效」的第二个点：扫描进行中关掉值守，扫描继续，但不再碰消息列表
                // （spec 0005 §3.6）。读配置而不读 runLoop 里的局部值 —— 这个开关可以在扫描中途被改。
                // 跳过时不打日志：每 5 张卡一行会盖住投递日志，而「没有 unread.detected 事件」
                // 本身就是可核对的事实。
                if (configStore.platformWatchEnabled(run.getPlatform())) {
                    UnreadWatcher.SweepResult sweep = unreadWatcher.sweep(
                            adapter, run.getPlatform(), collectMetrics, dryRun);
                    publishUnread(run, sweep);
                }
            }

            runStore.checkpoint(run, run.getCombinationIndex(), 0, card + 1);
            run.setLastCardIndex(card + 1);

            // 节奏：每张卡片后随机 [waitTime/2, waitTime]
            String next = card + 2 <= cardCount ? "查看第 " + (card + 2) + " 个岗位" : "进入下一组搜索条件";
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
            act(run, "AI 正在判断：" + job.getJobName() + " @ " + job.getCompanyName());
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
        LocalTime now = LocalTime.now();
        if (window.contains(now)) {
            return true;
        }
        long waitMinutes = window.opensInMinutes(now);
        // nextOpenHint 自带「之后才会真正执行」，这里不要再接「自动开始」，否则读成「真正执行自动开始」
        String message = "现在是 " + DutyWindow.format(now) + "，不在自动回复时段 " + window.describe()
                + " 内，任务先排队；" + window.nextOpenHint(now);
        runStore.markWaiting(run, message);
        act(run, "已排队，等 " + window.describe() + " 这个时段开始");
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
                log(run, "WARN", "上一轮中断时有 " + abandoned + " 个组合停在扫描中间，已记为「已中断」");
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

    /**
     * 唯一的运行日志出口。
     *
     * <p>并发之后两条时间线会在同一个文件里交错（spec 0004 §5.1 把「日志交错难以排查」
     * 列为 2b 的上线前提之一），所以每条都带平台前缀：{@code [zhaopin] [run 12] ...}。
     * 用 {@code run.getPlatform()} 而不是本实例绑定的平台 —— 两者理论上相同，
     * 但日志的读者只有 Run 这一张脸，写它自己的来源最不容易错。
     */
    private void log(RunEntity run, String level, String message) {
        runStore.log(run.getId(), level, message);
        log.info("[{}] [run {}] {}", run.getPlatform(), run.getId(), message);
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
}
