package com.chatjob.orchestrator;

import com.chatjob.platform.Models.JobDetail;
import com.chatjob.platform.Models.SearchQuery;
import com.chatjob.runlog.RunLog;
import com.chatjob.runlog.RunLogs;
import com.chatjob.scan.ScanStop;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.AiConfigEntity;
import com.chatjob.store.entity.CombinationRunEntity;
import com.chatjob.store.entity.PlatformConfigEntity;
import com.chatjob.store.entity.RunEntity;
import com.chatjob.store.mapper.RunLogMapper;
import com.chatjob.testing.FakePlatformAdapter;
import com.chatjob.time.FixedTimeSource;
import com.chatjob.watcher.UnreadWatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 扫描段的循环行为：组合队列怎么走、卡片从哪张开始、什么时候停、断点推进到哪。
 *
 * <p>这一段在改造前是 {@code RunService} 里的两个私有方法，只能靠起浏览器真跑一轮来看。
 * 现在它是独立对象，宿主与单卡处理都是接口，所以这些行为可以在毫秒级验证。
 *
 * <p>用 hand-written fake（项目不用 mock 框架）。{@code RunStore} / {@code ConfigStore}
 * 不是 final，直接继承覆盖那几个方法 —— 比给六个 mapper 各写一个 fake 少几百行，
 * 也不会因为 fake 的 SQL 语义失真得出错误结论。
 *
 * <p><b>边界</b>：这里验证编排顺序与状态迁移，不验证 Playwright 行为，也不验证判据本身
 * （那在 {@code ScanBoundaryTest}）。
 */
class ScanSegmentTest {

    private static final FixedTimeSource TIME = FixedTimeSource.of("2026-03-15T09:00:00");

    @Test
    void 配额到顶_第一张卡前就停且一张详情都不抓() {
        Fixture f = new Fixture();
        f.runStore.delivered = 30;      // 等于当前配额
        f.adapter.cards = 8;

        Optional<ScanStop> stop = f.segment().run();

        assertThat(stop).contains(ScanStop.QUOTA);
        // 搜索页开过（组合已经开始），但一张卡都没抓：判据在 fetchDetail 之前
        assertThat(f.adapter.searches).hasSize(1);
        assertThat(f.adapter.detailCalls).isEmpty();
        assertThat(f.jobs.processed).isEmpty();
        // 断点一步都没推进
        assertThat(f.runStore.checkpoints).isEmpty();
        assertThat(f.combos.get(0).getStatus()).isEqualTo("SKIPPED");
    }

    /** 关掉扫描之后不得再有任何搜索页导航（spec 0005 §3.5）：连 openSearch 都不该发生。 */
    @Test
    void 扫描开关关着_连搜索页都不开() {
        Fixture f = new Fixture();
        f.config.scanEnabled = false;
        f.adapter.cards = 8;

        Optional<ScanStop> stop = f.segment().run();

        assertThat(stop).contains(ScanStop.SCAN_DISABLED);
        assertThat(f.adapter.searches).isEmpty();
        assertThat(f.adapter.detailCalls).isEmpty();
    }

    /**
     * 断点续扫：同日回扫要接着上次那张卡，而不是从第 1 张重来。
     * 这一条最容易在「重构搬代码」时被悄悄改成从 0 开始。
     */
    @Test
    void 断点从上次停下的那张卡继续() {
        Fixture f = new Fixture();
        f.run.setLastCardIndex(2);
        f.adapter.cards = 5;

        Optional<ScanStop> stop = f.segment().run();

        assertThat(stop).contains(ScanStop.COMBOS_EXHAUSTED);
        assertThat(f.adapter.detailCalls).containsExactly(2, 3, 4);
        // 每张卡都落一次断点（组合 0、第 0 轮、卡片号一路前进），最后推进到下一个组合的第 0 张
        assertThat(f.runStore.checkpointCalls)
                .containsExactly("0/0/3", "0/0/4", "0/0/5", "1/0/0");
    }

    @Test
    void 组合跑完_记DONE并推进到下一个组合() {
        Fixture f = new Fixture();
        f.adapter.cards = 1;

        Optional<ScanStop> stop = f.segment().run();

        assertThat(stop).contains(ScanStop.COMBOS_EXHAUSTED);
        assertThat(f.combos.get(0).getStatus()).isEqualTo("DONE");
        // 组合跑完 → 断点前进到「下一个组合的第 0 张」
        assertThat(f.runStore.checkpointCalls).last().isEqualTo("1/0/0");
    }

    /**
     * 人工停止只在卡片边界生效：停止之后不再抓下一张，也不把断点推到「组合跑完」的位置。
     * 这一条区分「停了」与「跑完了」——两者都返回 empty，靠的是宿主的运行标志。
     */
    @Test
    void 人工停止_不再抓下一张卡() {
        Fixture f = new Fixture();
        f.adapter.cards = 5;
        f.jobs.onProcess = () -> f.host.stopRequested = true;   // 第一张卡处理完就点停止

        Optional<ScanStop> stop = f.segment().run();

        assertThat(stop).isEmpty();
        assertThat(f.adapter.detailCalls).containsExactly(0);
        assertThat(f.combos.get(0).getStatus()).isEqualTo("SKIPPED");
        assertThat(f.runStore.checkpointCalls).noneMatch(s -> s.startsWith("1/"));
    }

    // ------------------------------------------------------------------ fixture

    private static final class Fixture {
        final FakePlatformAdapter adapter = new FakePlatformAdapter();
        final FakeConfig config = new FakeConfig();
        final FakeRunStore runStore = new FakeRunStore();
        final FakeHost host = new FakeHost();
        final RecordingJobs jobs = new RecordingJobs();
        final RecordingRunLogs runLogs = new RecordingRunLogs();
        final RunEntity run = new RunEntity();
        final List<CombinationRunEntity> combos = new ArrayList<>();

        Fixture() {
            run.setId(7L);
            run.setPlatform("boss");
            run.setMode("NORMAL");
            run.setStatus(RunStatus.RUNNING.name());
            run.setCombinationIndex(0);
            run.setLastCardIndex(0);
            run.setEvaluatedCount(0);
            run.setDeliveredCount(0);

            CombinationRunEntity combo = new CombinationRunEntity();
            combo.setId(11L);
            combo.setRunId(7L);
            combo.setSeq(0);
            combo.setCity("福州");
            combo.setKeyword("java");
            combos.add(combo);
            runStore.fixture = this;
            adapter.details = index -> detail("j" + index);
        }

        ScanSegment segment() {
            return new ScanSegment(run, adapter, config, runStore, new NeverCalledWatcher(),
                    runLogs, TIME, jobs, host);
        }
    }

    private static JobDetail detail(String id) {
        return new JobDetail(id, "岗位" + id, "公司" + id, "10-15K", "福州", "3-5年", "本科",
                "描述", "HR", "招聘者", "本周", "今日活跃", "{}", "https://example.invalid/" + id);
    }

    /** 扫描段只在每 5 张卡 / 120 秒时巡检；本测试的卡数都不到 5，走到这里就是编排错了 */
    private static final class NeverCalledWatcher extends UnreadWatcher {
        NeverCalledWatcher() {
            super(null, null, null, null, null);
        }

        @Override
        public SweepResult sweep(com.chatjob.platform.PlatformAdapter adapter, String platform,
                                 boolean collectMetrics, boolean dryRun, RunLog runLog) {
            throw new AssertionError("这几条用例不该触到插空巡检");
        }
    }

    private static final class RecordingJobs implements JobProcessor {
        final List<String> processed = new ArrayList<>();
        Runnable onProcess = () -> {
        };

        @Override
        public void process(PlatformConfigEntity cfg, JobDetail detail, SearchQuery query) {
            processed.add(detail.platformJobId());
            onProcess.run();
        }
    }

    private static final class RecordingRunLogs extends RunLogs {
        RecordingRunLogs() {
            super(null, TIME);
        }

        @Override
        public RunLog of(RunEntity run) {
            return new RunLog() {
                @Override
                public void info(String message) {
                }

                @Override
                public void warn(String message) {
                }

                @Override
                public void error(String message) {
                }

                @Override
                public void warn(String message, Throwable cause) {
                }

                @Override
                public void error(String message, Throwable cause) {
                }

                @Override
                public void log(com.chatjob.runlog.RunLogLevel level, String message) {
                }
            };
        }
    }

    private static final class FakeHost implements ScanHost {
        boolean stopRequested;
        boolean dutyEnded;
        final List<String> acts = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> notifications = new ArrayList<>();

        @Override
        public boolean stopRequested() {
            return stopRequested;
        }

        @Override
        public boolean interrupted() {
            return stopRequested || dutyEnded;
        }

        @Override
        public void awaitResume() {
        }

        @Override
        public boolean endIfOutOfDuty(PlatformConfigEntity cfg) {
            return dutyEnded;
        }

        @Override
        public void act(String action) {
            acts.add(action);
        }

        @Override
        public void publish(String type, Object data) {
            events.add(type);
        }

        @Override
        public void publishUnread(UnreadWatcher.SweepResult sweep) {
            events.add("unread.detected");
        }

        @Override
        public void notify(String kind, String title, String body) {
            notifications.add(kind);
        }
    }

    private static class FakeConfig extends ConfigStore {
        boolean scanEnabled = true;
        boolean watchEnabled = false;
        final PlatformConfigEntity cfg = new PlatformConfigEntity();

        FakeConfig() {
            super(null, null, null, null, null, new ObjectMapper(), TIME);
            cfg.setPlatform("boss");
            cfg.setDailyDeliveryQuota(30);
            cfg.setDailyEvalLimit(150);
            cfg.setWaitTimeSeconds(1);
            cfg.setScrollRoundLimit(10);
            cfg.setCombinationTimeLimitSec(0);   // 不限时：这几条用例都不该因时间停下
        }

        @Override
        public PlatformConfigEntity platformConfig(String platform) {
            return cfg;
        }

        @Override
        public AiConfigEntity aiConfig() {
            return new AiConfigEntity();
        }

        @Override
        public boolean platformScanEnabled(String platform) {
            return scanEnabled;
        }

        @Override
        public boolean platformWatchEnabled(String platform) {
            return watchEnabled;
        }
    }

    /** 只覆盖扫描段用到的那几个方法；mapper 传 null 不影响（不会走到） */
    private static class FakeRunStore extends RunStore {
        int delivered;
        int evaluated;
        Fixture fixture;
        final List<String> checkpoints = new ArrayList<>();
        final List<String> checkpointCalls = new ArrayList<>();

        FakeRunStore() {
            super(null, null, null, null, null, TIME);
        }

        @Override
        public List<CombinationRunEntity> combinations(Long runId) {
            return new ArrayList<>(fixture.combos);
        }

        @Override
        public void updateCombination(CombinationRunEntity combo) {
        }

        @Override
        public int deliveredToday(String platform) {
            return delivered;
        }

        @Override
        public int evaluatedToday(String platform) {
            return evaluated;
        }

        @Override
        public RunEntity get(Long id) {
            return fixture.run;
        }

        @Override
        public void checkpoint(RunEntity run, int combinationIndex, int scrollRound, int lastCardIndex) {
            checkpoints.add(String.valueOf(lastCardIndex));
            checkpointCalls.add(combinationIndex + "/" + scrollRound + "/" + lastCardIndex);
        }
    }
}
