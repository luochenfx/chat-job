package com.chatjob.orchestrator;

import com.chatjob.driver.BlockedException;
import com.chatjob.platform.Models.JobDetail;
import com.chatjob.platform.Models.ScrollOutcome;
import com.chatjob.platform.Models.SearchQuery;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 一个扫描段：从进入扫描到因某个原因停下（CONTEXT.md「扫描段」「扫描中止」）。
 *
 * <p>搬出 {@code RunService} 的原因是它最混杂，而 {@code RunService} 原来的 1600 行里
 * 分页、AI 判定、投递、配额、时间上限、断点记在同一个循环体里，读「一张卡怎么走完」要穿过
 * 生命周期守卫与待命循环。搬出来之后本类只做四件事：跑组合队列、跑卡片、判边界、记账。
 *
 * <p><b>本类不拥有</b>：暂停与停止（{@link ScanHost}）、日志出口（{@link RunLogs}）、
 * SSE 报文口径（宿主）、单卡的判定与投递（{@link JobProcessor}）。它也没有 {@code if (platform == …)}，
 * 平台差异全在 {@link PlatformAdapter} 里。
 *
 * <p><b>放在 orchestrator 包而不是 scan 包</b>：本类要用 {@link RunStore} 与 {@link RunStatus}，
 * 而 {@code com.chatjob.scan} 是纯判据。把循环放进 scan，scan 就要反过来依赖 orchestrator，
 * 与 ADR-0009 搬 {@link ScanStop} 的理由（避免包级环）自相矛盾。ADR-0009 已补记这一点。
 */
final class ScanSegment {

    /** 每处理这么多张卡片做一次未读检查 */
    private static final int WATCH_EVERY_CARDS = 5;
    /** 或者每隔这么久（秒）做一次 */
    private static final int WATCH_EVERY_SECONDS = 120;

    private final RunEntity run;
    private final PlatformAdapter adapter;
    private final ConfigStore configStore;
    private final RunStore runStore;
    private final UnreadWatcher unreadWatcher;
    private final RunLogs runLogs;
    private final TimeSource time;
    private final JobProcessor jobs;
    private final ScanHost host;

    ScanSegment(RunEntity run, PlatformAdapter adapter, ConfigStore configStore, RunStore runStore,
                UnreadWatcher unreadWatcher, RunLogs runLogs, TimeSource time,
                JobProcessor jobs, ScanHost host) {
        this.run = run;
        this.adapter = adapter;
        this.configStore = configStore;
        this.runStore = runStore;
        this.unreadWatcher = unreadWatcher;
        this.runLogs = runLogs;
        this.time = time;
        this.jobs = jobs;
        this.host = host;
    }

    /**
     * 跑完组合队列。
     *
     * @return 扫描为什么停；empty 表示「没有判据命中就退出了」—— 跑完最后一个组合之后被停止、
     *         时段到点，或中途被人停。这三种的区分仍靠宿主的运行标志（见 ADR-0009 Open Question 1），
     *         本方法不替它猜。
     */
    Optional<ScanStop> run() {
        RunLog runLog = runLogs.of(run);
        PlatformConfigEntity cfg = configStore.platformConfig(run.getPlatform());
        List<CombinationRunEntity> combos = runStore.combinations(run.getId());
        int startIndex = run.getCombinationIndex() == null ? 0 : run.getCombinationIndex();

        for (int i = startIndex; i < combos.size(); i++) {
            if (host.interrupted()) {
                return Optional.empty();
            }
            host.awaitResume();
            CombinationRunEntity combo = combos.get(i);
            // 组合入口也要判扫描开关，而不是只判卡片边界：再往下一步就是 openSearch，
            // 关掉扫描之后不得再有任何搜索页导航（spec 0005 §3.5）。放在「开始组合」之前，
            // 所以关掉之后日志里不会再出现这一行 —— 验收时正是看它。
            if (!configStore.platformScanEnabled(run.getPlatform())) {
                runLog.info("该平台已关闭扫描，停止扫描；组合 #" + combo.getSeq() + " 未开始");
                return Optional.of(ScanStop.SCAN_DISABLED);
            }
            long comboStartedAt = System.currentTimeMillis();
            combo.setStatus("RUNNING");
            combo.setStartedAt(time.stamp());
            runStore.updateCombination(combo);
            host.publish("combination.started", Map.of(
                    "seq", combo.getSeq(),
                    "city", combo.getCity(),
                    "keyword", combo.getKeyword()));
            runLog.info("开始组合 #" + combo.getSeq() + " " + combo.getCity() + " / " + combo.getKeyword());

            int resumeCard = (i == startIndex && run.getLastCardIndex() != null) ? run.getLastCardIndex() : 0;
            try {
                // 同一个组合的搜索条件既用于打开列表页，也随投递传下去：投递时的详情页要
                // 先从这张列表页进，来源才是站内（spec 0006 §3.5）。两处用同一个对象，
                // 免得"扫描用的是城市 A、投递拼出来的列表页是城市 B"这种只在 URL 上可见的错位。
                SearchQuery query = new SearchQuery(combo.getCity(), combo.getKeyword());
                adapter.openSearch(query);
                ScrollOutcome scroll = adapter.scrollUntilEnd(
                        cfg.getScrollRoundLimit() == null ? 300 : cfg.getScrollRoundLimit(),
                        host::stopRequested);
                combo.setCardsSeen(scroll.cards());
                runLog.info("组合 #" + combo.getSeq() + " 加载 " + scroll.cards() + " 张卡片（"
                        + scroll.rounds() + " 轮，" + (scroll.loadedAll() ? "确认到底" : "撞到轮数上限") + "）");

                Optional<ScanStop> stop = cards(cfg, combo, scroll.cards(), resumeCard, comboStartedAt, query);
                if (stop.isPresent()) {
                    ScanStop why = stop.get();
                    // 配额 / 评估到顶 / 时间上限：这个组合没跑完，既不能记 DONE，也不能推进断点 ——
                    // 待命期配额被调高后要回到这张卡继续，而不是从下一个组合重来
                    if (why == ScanStop.COMBINATION_TIMEOUT) {
                        // 单独一个状态：DONE 会被读成「扫完了」，SKIPPED 会与「人工跳过」混在一起。
                        // 断点停在当前卡（cards() 每张卡都会写一次），同日回扫接着扫。
                        combo.setStatus("TIMEOUT");
                        // 断点语义分两种：同日回扫（配额被调高）从这张卡继续；跨日会复位组合队列
                        // （ensureScanDay），从头重扫。两句都写出来，否则「保留断点」会被当成
                        // 「明天从第 19 张开始」，而实际上明天是第 1 张。
                        runLog.info("组合 #" + combo.getSeq() + " 未跑完：已处理到第 "
                                + count(run.getLastCardIndex()) + "/" + count(combo.getCardsSeen())
                                + " 张；同日回扫从这里继续，跨日会从第 1 张重扫");
                    } else {
                        combo.setStatus("SKIPPED");
                    }
                    if (why == ScanStop.SCAN_DISABLED) {
                        // 「留待回扫时继续」在这里不成立：关掉扫描的 Run 不会被 StandbyGate 送回扫描
                        runLog.info("组合 #" + combo.getSeq() + " 中止：该平台已关闭扫描，剩余卡片不再处理");
                    } else if (why != ScanStop.COMBINATION_TIMEOUT) {
                        runLog.info("组合 #" + combo.getSeq() + " 在「" + stopLabel(why)
                                + "」处中止，剩余卡片留待回扫时继续");
                    }
                    return stop;
                }

                if (host.interrupted()) {
                    combo.setStatus("SKIPPED");
                } else if (currentStatus() == RunStatus.RUNNING) {
                    combo.setStatus("DONE");
                }
            } catch (BlockedException e) {
                combo.setStatus("FAILED");
                throw e;
            } catch (Exception e) {
                combo.setStatus("FAILED");
                // 一句话 + 堆栈走同一个出口：库里的行只有这句话，堆栈进文件。
                // 原来这里分两条（一行写库、一行只写文件），库里一行、文件里两行，且两行的措辞不一致。
                runLog.warn("组合 #" + combo.getSeq() + " 失败已跳过：" + e.getMessage(), e);
            } finally {
                combo.setFinishedAt(time.stamp());
                runStore.updateCombination(combo);
            }

            if (host.interrupted()) {
                return Optional.empty();
            }
            // 组合跑完，断点前进到下一个组合的第 0 张卡。
            // checkpoint 自己会把这三列写回内存实体，这里不再重复 set（改造前那两行是冗余的）。
            runStore.checkpoint(run, i + 1, 0, 0);
        }
        return Optional.of(ScanStop.COMBOS_EXHAUSTED);
    }

    /**
     * 跑一个组合里的卡片。
     *
     * @return 卡片边界上命中的中止原因；empty 表示跑到了最后一张，或中途被停止 / 时段到点
     */
    private Optional<ScanStop> cards(PlatformConfigEntity cfg, CombinationRunEntity combo, int cardCount,
                                     int startCard, long comboStartedAt, SearchQuery query) {
        RunLog runLog = runLogs.of(run);
        int cardsSinceWatch = 0;
        long lastWatchAt = System.currentTimeMillis();
        // <=0 表示不限时间（配置页可填 0 或 -1）：此时只有配额、评估上限、
        // 值守时段结束、人工停止这几个出口能中止组合，超时判据整段跳过
        int limitSeconds = ConfigStore.normalizeCombinationTimeLimit(cfg.getCombinationTimeLimitSec());
        boolean collectMetrics = configStore.aiConfig().getCollectEffectMetrics() != null
                && configStore.aiConfig().getCollectEffectMetrics() == 1;
        // 演练态从这里显式流下去。sweep 的签名要求它，而不是让 UnreadWatcher 自己去猜 ——
        // 上一轮的 bug 就是「投递路径判了 DRY_RUN，值守路径忘了判」，而值守会真的给 HR 发消息。
        boolean dryRun = "DRY_RUN".equals(run.getMode());

        for (int card = startCard; card < cardCount; card++) {
            if (host.stopRequested()) {
                return Optional.empty();
            }
            host.awaitResume();
            // 卡片边界是唯一可靠的收工点：这里之前的所有动作要么不发消息，要么已经发完了，
            // 半条招呼语比晚几秒停止更糟。
            if (host.endIfOutOfDuty(cfg)) {
                return Optional.empty();
            }
            // 四个判据（扫描开关 / 配额 / 评估上限 / 时间上限）与它们的优先级都在 ScanBoundary 里，
            // 这里只做两件事：把参数量出来、按原因写日志。判据与文案分开，判据才能被直接测。
            // 读配置而不读 runLoop 里的局部变量：那个值只在启动时读一次，运行中关掉不会变。
            int delivered = runStore.deliveredToday(run.getPlatform());
            int evaluated = runStore.evaluatedToday(run.getPlatform());
            long usedSeconds = (System.currentTimeMillis() - comboStartedAt) / 1000;
            Optional<ScanStop> boundary = ScanBoundary.atCard(
                    configStore.platformScanEnabled(run.getPlatform()),
                    delivered, ScanBoundary.quotaOf(cfg), evaluated, ScanBoundary.evalLimitOf(cfg),
                    usedSeconds, limitSeconds);
            if (boundary.isPresent()) {
                ScanStop stop = boundary.get();
                // 四种原因分开写：它们在界面上的下一步不同（继续值守 / 留待回扫 / 不再导航），
                // 合并成一句会把「已经停了」读成「还会继续」。
                switch (stop) {
                    case SCAN_DISABLED -> runLog.info(
                            "该平台已关闭扫描，在第 " + (card + 1) + " 张卡前停止；值守继续");
                    case QUOTA -> runLog.info("今日主动投递已达配额 " + ScanBoundary.quotaOf(cfg)
                            + "（" + delivered + "），中止扫描；值守继续");
                    case EVAL_LIMIT -> runLog.info("今日评估已达上限 " + ScanBoundary.evalLimitOf(cfg)
                            + "（" + evaluated + "），中止扫描；值守继续");
                    // 超时点在卡片边界：上一张卡的动作已经做完，这里停下不会留下半条招呼语。
                    // 文案必须带进度：只说「超时（Ns）」时，界面上唯一的痕迹是一句 WARN，
                    // 而进度行已经被断点归零，看不出是扫到一半停的还是根本没扫。
                    case COMBINATION_TIMEOUT -> runLog.warn("组合 #" + combo.getSeq() + " 到达时间上限（"
                            + limitSeconds + "s，实际 " + usedSeconds + "s）：已处理 " + card + "/" + cardCount
                            + " 张卡，评估 " + count(combo.getEvaluated()) + "、投递 " + count(combo.getDelivered())
                            + "；剩余 " + (cardCount - card) + " 张留待下次扫描继续");
                    // 组合队列耗尽由 run() 在队列末尾产出，卡片边界不该出现它。
                    // 真出现了说明 ScanBoundary 的契约被改了，宁可当场炸也不要把「扫完了」记成「停了」。
                    case COMBOS_EXHAUSTED -> throw new IllegalStateException(
                            "卡片边界不应返回 " + stop + "，它由组合队列耗尽产出");
                }
                return Optional.of(stop);
            }

            host.act("查看第 " + (card + 1) + " 个岗位的详情");
            JobDetail detail = adapter.fetchDetail(card);
            if (detail == null) {
                runLog.warn("第 " + (card + 1) + " 张卡片没抓到详情，跳过");
                continue;
            }

            // 组合维度的账以前没人记：daily_counter 是平台维度的当日配额，run.evaluatedCount 是
            // 整个 Run 的累计，两者都不是「这个城市×关键词」这一格的数，于是 combination_run 的
            // evaluated / delivered 从建表起就一直是 0（Run 上写着 20，组合上写 0）。
            // 这里按单张卡处理前后的差值补记，而不是在 JobProcessor 里再判一次「算不算评估/投递」——
            // 那里 incr 的两个点才是唯一口径，照抄一遍只会多出第二份真相。
            int evaluatedBefore = count(run.getEvaluatedCount());
            int deliveredBefore = count(run.getDeliveredCount());
            jobs.process(run, cfg, detail, query);
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
                            adapter, run.getPlatform(), collectMetrics, dryRun, runLog);
                    host.publishUnread(sweep);
                }
            }

            runStore.checkpoint(run, run.getCombinationIndex(), 0, card + 1);

            // 卡间停顿：每张卡片后随机 [waitTime/2, waitTime]。这是编排层的等待，
            // 与适配器内部的滚动节奏无关（后者由人类化交互层自己决定，不受这项配置影响）
            String next = card + 2 <= cardCount ? "查看第 " + (card + 2) + " 个岗位" : "进入下一组搜索条件";
            pauseFor(cfg, next);
        }
        return Optional.empty();
    }

    private void pauseFor(PlatformConfigEntity cfg, String next) {
        int wait = cfg.getWaitTimeSeconds() == null ? 10 : cfg.getWaitTimeSeconds();
        int min = Math.max(1, wait / 2);
        int max = Math.max(min + 1, wait);
        int millis = ThreadLocalRandom.current().nextInt(min * 1000, max * 1000);
        // 等待前先写「在等多久、等完干什么」：这是界面区分「在等」与「卡死」的唯一依据
        host.act("等待 " + Math.max(1, millis / 1000) + " 秒后" + next);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private RunStatus currentStatus() {
        RunEntity fresh = runStore.get(run.getId());
        return fresh == null || fresh.getStatus() == null
                ? RunStatus.FAILED : RunStatus.valueOf(fresh.getStatus());
    }

    private static String stopLabel(ScanStop reason) {
        return switch (reason) {
            case QUOTA -> "每天主动联系的名额";
            case EVAL_LIMIT -> "每天查看岗位的上限";
            case COMBINATION_TIMEOUT -> "单个组合的时间上限";
            case COMBOS_EXHAUSTED -> "搜索条件已全部跑完";
            case SCAN_DISABLED -> "扫描已关闭";
        };
    }

    /** 计数列取值的 null 安全版本：老库的行、内存里刚 new 出来的实体都可能是 null。 */
    private static int count(Integer value) {
        return value == null ? 0 : value;
    }
}
