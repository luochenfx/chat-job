package com.chatjob.orchestrator;

import com.chatjob.platform.PlatformAdapter;
import com.chatjob.runlog.RunLog;
import com.chatjob.runlog.RunLogs;
import com.chatjob.scan.ScanBoundary;
import com.chatjob.scan.ScanStop;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.CombinationRunEntity;
import com.chatjob.store.entity.PlatformConfigEntity;
import com.chatjob.store.entity.RunEntity;
import com.chatjob.time.TimeSource;
import com.chatjob.watcher.UnreadWatcher;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 一个待命段：扫描停下之后，值守怎么守着、守到哪一步为止（CONTEXT.md「值守待命」）。
 *
 * <p>搬出 {@code RunService} 的理由与 {@link ScanSegment} 相同：原来的 {@code standbyLoop}
 * 要开浏览器、要 sleep、要写库，那一段的单测覆盖为 0 —— 「配额被调高会不会回扫」
 * 「关掉值守后多久停」只能靠起一整轮来看。搬出来之后，判据（{@link StandbyGate}）与循环
 * 分别可以喂假宿主驱动。
 *
 * <p><b>进入横幅与循环是一件事</b>：{@code enterStandby} 与 {@code standbyLoop} 原来成对出现
 * 两次（只值守的 Run 启动时、扫描停下时），中间没有任何别的动作。合成 {@link #enter} 之后，
 * 「先发横幅再进循环」这条不成文的约定不再需要调用方记得。
 *
 * <p><b>本类不拥有</b>：终止态（{@code CANCELLED} / {@code COMPLETED} / {@code WATCH_DISABLED}）
 * 的落法 —— 那是 {@code RunService.finish} 的事，由调用方按出口映射。本类只写三个<b>非终止态</b>：
 * 进入时 {@code markStandby}、回扫时 {@code RUNNING}、挂起时 {@code WAITING_DUTY}。
 * 它也不拥有暂停与停止（{@link RunControl}）与日志出口（{@link RunLogs}）。
 *
 * <p>唯一一处「不只是待命」的持有是 {@link #ensureScanDay}：它在扫描入口与回扫出口各用一次，
 * 而回扫出口在本类里。理由写在那个方法上。
 *
 * <p><b>放在 orchestrator 包而不是新建包</b>：它要用 {@link RunStore}，而「待命」在依赖方向上
 * 在扫描段之后（ADR-0009 Decision 2 的同一套理由）。
 */
final class StandbySegment {

    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 挂起原因码。与「启动时就不在窗口内」的 {@code OUT_OF_WINDOW} 区分，界面文案不同 */
    private static final String PARK_REASON = "DUTY_END";

    private final RunEntity run;
    private final PlatformAdapter adapter;
    private final ConfigStore configStore;
    private final RunStore runStore;
    private final UnreadWatcher unreadWatcher;
    private final RunLogs runLogs;
    private final TimeSource time;
    private final RunControl host;

    StandbySegment(RunEntity run, PlatformAdapter adapter, ConfigStore configStore, RunStore runStore,
                   UnreadWatcher unreadWatcher, RunLogs runLogs, TimeSource time, RunControl host) {
        this.run = run;
        this.adapter = adapter;
        this.configStore = configStore;
        this.runStore = runStore;
        this.unreadWatcher = unreadWatcher;
        this.runLogs = runLogs;
        this.time = time;
        this.host = host;
    }

    /** 待命段的四种出口 */
    enum Exit {
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
     * 进入值守待命，一直守到某个出口。
     *
     * <p>为什么复用当前线程而不是另起一个：{@code PlaywrightExecutor} 是单线程串行的，
     * {@code BossAdapter} 又是单例持有 {@code listPage} / {@code threadPage} 两个字段。
     * 并行意味着两个线程交替操作同一批页面，历史上那正是「导航被打断成 net::ERR_ABORTED」的来源。
     *
     * <p>待命期不碰搜索页（不调 {@code openSearch}），{@code listPage} 保持上次扫描停在的位置。
     *
     * <p>四个出口里只有 {@link Exit#RESCAN} 会回到扫描，而它的唯一来源是
     * {@link StandbyGate} 的 {@code SCAN} 判定 —— 那个判定在 {@code scanEnabled} 为 false 时
     * 直接返回 {@code WATCH}，所以「只值守」的 Run 不会因为跨日被判去回扫。
     *
     * @param reason      扫描段是怎么停的；{@code null} 按「组合队列耗尽」处理
     * @param scanEnabled 本轮是否参与扫描，取自调用方启动时那一次读取
     *                    （运行中改扫描开关不改变这个值，见 spec 0005 §3.3）
     */
    Exit enter(ScanStop reason, boolean scanEnabled) {
        announce(reason);
        int round = 0;
        long enteredAt = System.currentTimeMillis();
        RunLog runLog = runLogs.of(run);
        boolean collectMetrics = configStore.aiConfig().getCollectEffectMetrics() != null
                && configStore.aiConfig().getCollectEffectMetrics() == 1;

        while (true) {
            if (host.stopRequested()) {
                return Exit.STOPPED;
            }
            host.awaitResume();
            if (host.stopRequested()) {
                return Exit.STOPPED;
            }

            // 每轮重读配置：配额、评估上限、巡检间隔都可能在待命期被改。
            // 扫描开关故意不在这里重读：它在启动时定下「这一轮扫不扫」，重读会让
            // 「循环要不要扫」（调用方的局部变量）与「能不能回扫」（这里的入参）分成两个值，
            // 出现「发了回扫事件、状态改回 RUNNING，却没有真的去扫」。
            PlatformConfigEntity cfg = configStore.platformConfig(run.getPlatform());
            // 值守开关则相反，必须每轮重读：它是「即刻生效」的三个点之一（另外两个是插空巡检
            // 与 sleepInterruptibly 的每秒检查）。关掉之后待命没有继续的理由 —— STANDBY 的定义
            // 是「扫描已中止、值守仍在继续」，两者都不成立了就该结束，由调用方收尾。
            // 走 ConfigStore 的读法而不复用上面那份 cfg：「缺省按开」的规则只有那一处，
            // 在调用点照抄一遍 `== null || != 0` 就等于第二份默认值。
            if (!configStore.platformWatchEnabled(run.getPlatform())) {
                runLog.info("该平台已关闭值守，本轮不再巡检，任务结束");
                return Exit.ENDED;
            }
            DutyWindow window = DutyWindow.of(cfg);
            LocalTime now = time.now().toLocalTime();
            int interval = ConfigStore.clampWatchInterval(cfg.getWatchIntervalSeconds());
            int delivered = runStore.deliveredToday(run.getPlatform());
            int evaluated = runStore.evaluatedToday(run.getPlatform());

            StandbyGate.Action action = StandbyGate.decide(now, window, reason, run.getScanDay(), time.today(),
                    delivered, ScanBoundary.quotaOf(cfg), evaluated, ScanBoundary.evalLimitOf(cfg), scanEnabled);
            if (action == StandbyGate.Action.PARK) {
                park();
                return Exit.PARKED;
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
                host.publish("run.rescan", data);
                ensureScanDay();
                // 只改状态，重写 started_at 会让「已运行多久」在每次回扫时归零 ——
                // 值守期是一次启动到手动停止，中间来回切换扫描/待命不该重置这个计时
                runStore.markStatus(run, RunStatus.RUNNING);
                run.setStatus(RunStatus.RUNNING.name());
                return Exit.RESCAN;
            }

            round++;
            host.act("检查未读消息：读取消息列表");
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
            sleepInterruptibly(window, jittered * 1000L);
        }
    }

    /**
     * 进入待命时的一句话横幅：落库、推事件、发通知。
     *
     * <p>四种扫描中止原因共用这一个出口 —— 分开写就会有人漏改一处，
     * 而漏改的那一处正好是「配额用完就收工」的老行为。
     *
     * <p>{@link ScanStop#SCAN_DISABLED} 与其余三种不是一类事：其余三种是
     * 「今天不扫了」，这一种是「从来没有扫」。所以横幅与通知标题都要另写一句，
     * 沿用「扫描已结束（…）」会与实际不符，读日志的人会去翻组合表，而那张表是空的。
     */
    private void announce(ScanStop reason) {
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
                : (timedOut ? "搜索暂停（" : "搜索已结束（") + whyOf(why0, cfg, delivered, evaluated, scannedCards())
                + "），继续处理消息：每 " + interval + "检查一次未读消息，" + tail
                // 超时保留断点，所以「怎么接着扫」必须写出来：不写的话，唯一的动作暗示是
                // 「调高名额」，而真实需求可能只是等明天
                + (timedOut ? "；想过零点或把名额调高后继续搜索，不需要重新启动" : "");

        runStore.markStandby(run, message);
        host.act(neverScanned ? "正在盯着消息：没有开启「搜索岗位」，只检查消息" : "正在盯着消息：等下一次检查");
        runLog.info(message);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", why0.name());
        data.put("delivered", delivered);
        data.put("evaluated", evaluated);
        data.put("quota", ScanBoundary.quotaOf(cfg));
        host.publish("run.standby", data);
        host.notify("standby",
                neverScanned ? "没有开启搜索，正在处理消息" : timedOut ? "搜索超时，继续处理消息" : "搜索已结束，继续处理消息",
                message);
    }

    /**
     * 时段到点：收起页面，值守期不结束。
     *
     * <p>文案必须写清「已挂起、不用重新启动」——原来那句「本轮收工」会让人第二天早上
     * 再点一次启动，而那正好会造出第二条 Run。
     *
     * <p>调用方有两处：待命循环里的 {@code PARK} 出口，以及扫描刚停下就发现到点的那一条
     * （那时还没进待命，所以它必须能单独调）。
     */
    void park() {
        DutyWindow window = DutyWindow.of(configStore.platformConfig(run.getPlatform()));
        LocalTime now = time.now().toLocalTime();
        String message = "已到自动回复结束时间（" + window.describe() + "，现在 " + DutyWindow.format(now) + "），"
                + "已停止操作；" + window.nextOpenHint(now) + "，这一轮还没有结束，不需要重新启动";
        runStore.markWaiting(run, message);
        host.act("已排队，等 " + window.describe() + " 这个时段开始");
        runLogs.of(run).info(message);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", PARK_REASON);
        data.put("window", window.describe());
        data.put("at", DutyWindow.format(now));
        data.put("opensInMinutes", window.opensInMinutes(now));
        host.publish("run.waiting", data);
        host.notify("duty-end", "自动回复时段已结束，任务先排队", message);
    }

    /**
     * 「扫描为什么停」的一句话，横幅与结束文案共用。
     *
     * <p>给「扫描开、值守关」那条收尾路径用：它不进待命，但要说同一句话，
     * 否则同一个原因在日志里会出现两种说法。
     */
    String describeStop(ScanStop reason) {
        return whyOf(reason, configStore.platformConfig(run.getPlatform()),
                runStore.deliveredToday(run.getPlatform()),
                runStore.evaluatedToday(run.getPlatform()),
                scannedCards());
    }

    /**
     * 不共用就会出现两份「配额已用完 / 评估已达上限 / 组合已扫完」的措辞，改一处漏一处；
     * 而那种漏改在日志里表现为同一件事有两种说法，排查时会当成两件事。
     *
     * <p><b>组合耗尽这一支必须带上实际用量</b>：它是唯一一种「没到任何上限却不再扫描」的停法，
     * 只写「所有搜索组合都跑完了」时，读日志的人第一反应是"是不是提前退出了"——
     * 而真实原因是候选池就这么大（或者上一环漏了岗位）。把 {@code 49/400}、{@code 15/100}
     * 摆在同一行，才能把这两种情况分开。
     *
     * @param cards 本轮已加载过的卡片总数（各组合 {@code cards_seen} 之和）
     */
    private String whyOf(ScanStop reason, PlatformConfigEntity cfg, int delivered, int evaluated, int cards) {
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
     * 跨自然日恢复扫描前调用；同日重复调用是 no-op。
     *
     * <p>复位而不是「继续」：昨天扫完的组合队列状态是 DONE，原样再跑一遍只会全部跳过，
     * 第二天一个岗位都不投，而日志上看不出任何异常。
     *
     * <p><b>它为什么住在本类而不是扫描段</b>：两个调用点是「扫描入口」（调用方在每段扫描前调）
     * 与「回扫出口」（{@link Exit#RESCAN} 之前调），后者在本类里。三次调用同一个 no-op 无害，
     * 但两处各抄一份复位逻辑就会分叉 —— 而分叉的表现是第二天一个岗位都不投。
     */
    void ensureScanDay() {
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

    /** 本轮已加载过的卡片总数。只用于日志：组合表是唯一记得「这一轮到底有多少张卡」的地方。 */
    private int scannedCards() {
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
     * 可打断的睡眠：每秒检查一次停止、时段结束与值守开关。
     *
     * <p>不能直接 {@code Thread.sleep(interval)}：22:00 到点最多要等两分钟才收起页面，
     * 而「时段外不操作页面」是硬约束。暂停也靠这里尽快生效。
     *
     * <p>值守开关也放在这个每秒检查里：只靠 {@code enter} 顶部的判定，关掉之后最坏要等满
     * 一个巡检间隔（按分钟计）才停；而「关掉值守后最多一秒停止巡检」是 spec 0005 的验收口径。
     * 代价是每秒读一次本地 SQLite 的一行，比等满一个间隔便宜得多。
     */
    private void sleepInterruptibly(DutyWindow window, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (host.stopRequested() || !window.contains(time.now().toLocalTime())
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

    /** 计数列取值的 null 安全版本：老库的行、内存里刚 new 出来的实体都可能是 null。 */
    private static int count(Integer value) {
        return value == null ? 0 : value;
    }
}
