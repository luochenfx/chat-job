package com.chatjob.orchestrator;

import com.chatjob.platform.PlatformAdapter;
import com.chatjob.runlog.RunLog;
import com.chatjob.runlog.RunLogLevel;
import com.chatjob.runlog.RunLogs;
import com.chatjob.scan.ScanStop;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.AiConfigEntity;
import com.chatjob.store.entity.CombinationRunEntity;
import com.chatjob.store.entity.PlatformConfigEntity;
import com.chatjob.store.entity.RunEntity;
import com.chatjob.testing.FakePlatformAdapter;
import com.chatjob.time.FixedTimeSource;
import com.chatjob.watcher.UnreadWatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 待命段的四个出口与两句话术：什么时候继续守着、什么时候回扫、什么时候挂起、什么时候结束。
 *
 * <p>这一段在改造前是 {@code RunService} 里的 {@code standbyLoop} + {@code enterStandby}，
 * 只能靠起一整轮来验证 —— 「配额被调高会不会回扫」「关掉值守后多久停」都写在注释里，
 * 没有断言。现在判据（{@link StandbyGate}）与循环（{@link StandbySegment}）分开，
 * 这里用假宿主驱动循环本身。
 *
 * <p>用 hand-written fake（项目不用 mock 框架）。{@code RunStore} / {@code ConfigStore}
 * 不是 final，直接继承覆盖那几个方法 —— 与 {@code ScanSegmentTest} 同一写法。
 *
 * <p><b>边界</b>：这里只验证出口与落库的状态，不验证判据（那在 {@code StandbyGateTest}），
 * 也不验证巡检本身（那在 {@code UnreadWatcherTest}）。
 */
class StandbySegmentTest {

    private static final FixedTimeSource TIME = FixedTimeSource.of("2026-03-15T09:00:00");

    @Test
    void 用户停止_立刻退出且一轮都不巡检() {
        Fixture f = new Fixture();
        f.host.stopRequested = true;

        StandbySegment.Exit exit = f.segment().enter(ScanStop.QUOTA, true);

        assertThat(exit).isEqualTo(StandbySegment.Exit.STOPPED);
        assertThat(f.watcher.sweeps).isZero();
    }

    @Test
    void 值守开关被关掉_结束待命并且不再巡检() {
        Fixture f = new Fixture();
        f.config.watchEnabled = false;

        StandbySegment.Exit exit = f.segment().enter(ScanStop.QUOTA, true);

        assertThat(exit).isEqualTo(StandbySegment.Exit.ENDED);
        assertThat(f.watcher.sweeps).isZero();
        assertThat(f.runLogs.messages).anyMatch(m -> m.contains("该平台已关闭值守"));
    }

    @Test
    void 配额被调高_回扫且状态回到RUNNING() {
        Fixture f = new Fixture();
        f.runStore.delivered = 0;              // 配额 30，已用 0：回升了
        f.run.setScanDay(TIME.today());        // 没有跨日，回扫只能来自配额

        StandbySegment.Exit exit = f.segment().enter(ScanStop.QUOTA, true);

        assertThat(exit).isEqualTo(StandbySegment.Exit.RESCAN);
        // 只改状态，不重置 started_at：值守期的起点没变
        assertThat(f.runStore.statuses).containsExactly(RunStatus.RUNNING);
        assertThat(f.host.events).contains("run.rescan");
        assertThat(f.run.getStatus()).isEqualTo(RunStatus.RUNNING.name());
        assertThat(f.watcher.sweeps).isZero();
    }

    @Test
    void 时段外_挂起并落等待态() {
        Fixture f = new Fixture();
        f.config.cfg.setDutyStartTime("10:00");   // 现在 09:00，在窗口外
        f.config.cfg.setDutyEndTime("22:00");

        StandbySegment.Exit exit = f.segment().enter(ScanStop.QUOTA, true);

        assertThat(exit).isEqualTo(StandbySegment.Exit.PARKED);
        assertThat(f.runStore.waitingMessages).hasSize(1);
        // 文案必须说「还没有结束，不需要重新启动」，否则第二天早上会被再点一次启动
        assertThat(f.runStore.waitingMessages.get(0))
                .contains("这一轮还没有结束，不需要重新启动");
        assertThat(f.host.events).contains("run.waiting");
        assertThat(f.host.notifications).contains("duty-end");
        assertThat(f.watcher.sweeps).isZero();
    }

    @Test
    void 未启用扫描的横幅不说搜索已结束() {
        Fixture f = new Fixture();
        f.host.stopRequested = true;           // 只看横幅，不进循环

        f.segment().enter(ScanStop.SCAN_DISABLED, false);

        assertThat(f.runStore.standbyMessages).hasSize(1);
        // 这一轮从来没有扫过，说「搜索已结束」会让人去翻组合表，而那张表是空的
        assertThat(f.runStore.standbyMessages.get(0))
                .contains("没有开启「搜索岗位」")
                .doesNotContain("搜索已结束");
        assertThat(f.host.notifications).contains("standby");
    }

    @Test
    void 组合超时的横幅说搜索暂停而不是已结束() {
        Fixture f = new Fixture();
        f.host.stopRequested = true;

        f.segment().enter(ScanStop.COMBINATION_TIMEOUT, true);

        assertThat(f.runStore.standbyMessages).hasSize(1);
        // 超时是「停下」不是「结束」：组合还剩卡没看完
        assertThat(f.runStore.standbyMessages.get(0))
                .startsWith("搜索暂停（")
                .contains("不需要重新启动");
    }

    // ------------------------------------------------------------------ fixture

    private static final class Fixture {
        final FakePlatformAdapter adapter = new FakePlatformAdapter();
        final FakeConfig config = new FakeConfig();
        final FakeRunStore runStore = new FakeRunStore();
        final FakeHost host = new FakeHost();
        final RecordingWatcher watcher = new RecordingWatcher();
        final RecordingRunLogs runLogs = new RecordingRunLogs();
        final RunEntity run = new RunEntity();

        Fixture() {
            run.setId(7L);
            run.setPlatform("boss");
            run.setMode("NORMAL");
            run.setStatus(RunStatus.STANDBY.name());
            run.setScanDay(TIME.today());
            runStore.fixture = this;
        }

        StandbySegment segment() {
            return new StandbySegment(run, adapter, config, runStore, watcher, runLogs, TIME, host);
        }
    }

    /** 只覆盖待命段用到的那几个方法；mapper 传 null 不影响（不会走到） */
    private static class FakeRunStore extends RunStore {
        int delivered;
        int evaluated;
        Fixture fixture;
        final List<RunStatus> statuses = new ArrayList<>();
        final List<String> standbyMessages = new ArrayList<>();
        final List<String> waitingMessages = new ArrayList<>();

        FakeRunStore() {
            super(null, null, null, null, null, TIME);
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
        public List<CombinationRunEntity> combinations(Long runId) {
            return new ArrayList<>();
        }

        @Override
        public void markStandby(RunEntity run, String message) {
            standbyMessages.add(message);
        }

        @Override
        public void markWaiting(RunEntity run, String message) {
            waitingMessages.add(message);
        }

        @Override
        public void markStatus(RunEntity run, RunStatus status) {
            statuses.add(status);
        }

        @Override
        public void resetCombinations(Long runId) {
        }

        @Override
        public void update(RunEntity run) {
        }
    }

    private static class FakeConfig extends ConfigStore {
        boolean watchEnabled = true;
        final PlatformConfigEntity cfg = new PlatformConfigEntity();

        FakeConfig() {
            super(null, null, null, null, null, new ObjectMapper(), TIME);
            cfg.setPlatform("boss");
            cfg.setDailyDeliveryQuota(30);
            cfg.setDailyEvalLimit(150);
            cfg.setWatchIntervalSeconds(30);
            cfg.setDutyStartTime("09:00");
            cfg.setDutyEndTime("22:00");
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
        public boolean platformWatchEnabled(String platform) {
            return watchEnabled;
        }
    }

    /** 巡检返回一份非零计数：日志里那些数字才有东西可写 */
    private static final class RecordingWatcher extends UnreadWatcher {
        int sweeps;

        RecordingWatcher() {
            super(null, null, null, null, null);
        }

        @Override
        public SweepResult sweep(PlatformAdapter adapter, String platform,
                                 boolean collectMetrics, boolean dryRun, RunLog runLog) {
            sweeps++;
            return new SweepResult(3, 1, 2, 0, 0, 0, 0, java.util.List.of());
        }
    }

    private static final class RecordingRunLogs extends RunLogs {
        final List<String> messages = new ArrayList<>();

        RecordingRunLogs() {
            super(null, TIME);
        }

        @Override
        public RunLog of(RunEntity run) {
            return new RunLog() {
                @Override
                public void info(String message) {
                    messages.add(message);
                }

                @Override
                public void warn(String message) {
                    messages.add(message);
                }

                @Override
                public void error(String message) {
                    messages.add(message);
                }

                @Override
                public void warn(String message, Throwable cause) {
                    messages.add(message);
                }

                @Override
                public void error(String message, Throwable cause) {
                    messages.add(message);
                }

                @Override
                public void log(RunLogLevel level, String message) {
                    messages.add(message);
                }
            };
        }
    }

    private static final class FakeHost implements RunControl {
        boolean stopRequested;
        final List<String> acts = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> notifications = new ArrayList<>();

        @Override
        public boolean stopRequested() {
            return stopRequested;
        }

        @Override
        public void awaitResume() {
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
}
