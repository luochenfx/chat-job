package com.chatjob.orchestrator;

import com.chatjob.evaluate.AiJudge;
import com.chatjob.platform.Models.DeliveryOptions;
import com.chatjob.platform.Models.DeliveryResult;
import com.chatjob.platform.Models.JobDetail;
import com.chatjob.platform.Models.SearchQuery;
import com.chatjob.runlog.RunLog;
import com.chatjob.runlog.RunLogLevel;
import com.chatjob.runlog.RunLogs;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.AiConfigEntity;
import com.chatjob.store.entity.BlacklistEntity;
import com.chatjob.store.entity.DeliveryEntity;
import com.chatjob.store.entity.JobPostingEntity;
import com.chatjob.store.entity.PlatformConfigEntity;
import com.chatjob.store.entity.RunEntity;
import com.chatjob.testing.FakePlatformAdapter;
import com.chatjob.time.FixedTimeSource;
import com.chatjob.watcher.UnreadWatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单卡处置的七条出口：谁被丢弃、谁被跳过、失败与不投分别落什么、什么时候真的投。
 *
 * <p>这一段原来是 {@code RunService} 里 121 行的私有方法，七个早退混着四类副作用。
 * 那时唯一能回答「AI 判定失败到底记 FAILED 还是 SKIPPED」的办法是起一整轮真机 ——
 * 而那个答案决定了该岗位会不会被打上 24 小时冷却（{@code JobStore.decide} 的语义）。
 *
 * <p>用 hand-written fake（项目不用 mock 框架）。{@code JobStore} / {@code RunStore} /
 * {@code ConfigStore} / {@code AiJudge} 都不是 final，直接继承覆盖用到的那几个方法。
 *
 * <p><b>边界</b>：这里验证顺序与落库的结果，不验证 AI 的解析（{@code AiJudgeTest}）、
 * 不验证黑名单匹配规则（{@code BlacklistMatcherTest}）、不验证投递本身（适配器）。
 */
class JobDispositionTest {

    private static final FixedTimeSource TIME = FixedTimeSource.of("2026-03-15T09:00:00");

    @Test
    void 黑名单命中_不落库也不调AI() {
        Fixture f = new Fixture();
        f.config.blacklist.add(blacklist("job", "外包"));
        f.detail = detail("外包岗位", "今日活跃");

        f.run();

        assertThat(f.jobStore.upserts).isEmpty();
        assertThat(f.aiJudge.calls).isEmpty();
        assertThat(f.jobStore.finished).isEmpty();
        assertThat(f.runLogs.messages).anyMatch(m -> m.contains("丢弃（黑名单「外包」）"));
    }

    @Test
    void 活跃度低于下限_不落库也不调AI() {
        Fixture f = new Fixture();
        f.detail = detail("Java 开发", "半年前活跃");

        f.run();

        assertThat(f.jobStore.upserts).isEmpty();
        assertThat(f.aiJudge.calls).isEmpty();
        assertThat(f.runLogs.messages).anyMatch(m -> m.contains("低于下限"));
    }

    @Test
    void 冷却中_不调AI也不计评估() {
        Fixture f = new Fixture();
        f.jobStore.decision = JobStore.Decision.skip("24 小时内已判定不投");

        f.run();

        assertThat(f.aiJudge.calls).isEmpty();
        assertThat(f.runStore.evaluated).isZero();
        assertThat(f.jobStore.finished).isEmpty();
        assertThat(f.runLogs.messages).anyMatch(m -> m.contains("跳过（24 小时内已判定不投）"));
    }

    @Test
    void AI判定技术失败_落FAILED而不是SKIPPED() {
        Fixture f = new Fixture();
        f.aiJudge.verdict = AiJudge.Verdict.fail("ReadTimeoutException: 超时");

        f.run();

        // 记 SKIPPED 会给该岗位打 24 小时冷却，一次模型故障会让整批岗位一天内不再尝试
        assertThat(f.jobStore.finished).containsExactly("FAILED");
        assertThat(f.adapter.greetings).isEmpty();
        assertThat(f.host.events).contains("job.evaluated");
        assertThat(f.runLogs.messages).anyMatch(m -> m.contains("AI 判定失败"));
    }

    @Test
    void AI判定不投_落SKIPPED且不调适配器() {
        Fixture f = new Fixture();
        f.aiJudge.verdict = new AiJudge.Verdict(false, 42, "方向不符", "", "{}", null);

        f.run();

        assertThat(f.jobStore.finished).containsExactly("SKIPPED");
        assertThat(f.adapter.greetings).isEmpty();
        assertThat(f.runStore.delivered).isZero();
    }

    @Test
    void 模板招呼语为空_不留投递行() {
        Fixture f = new Fixture();
        f.config.qualityGate = false;
        f.config.templateGreeting = "   ";

        f.run();

        assertThat(f.jobStore.finished).isEmpty();
        assertThat(f.adapter.greetings).isEmpty();
        // 评估计数在 AI 之前就加了，这一支已经算过一次评估 —— 保持改造前的口径，不在这里挪动
        assertThat(f.runStore.evaluated).isEqualTo(1);
        assertThat(f.runLogs.messages).anyMatch(m -> m.contains("模板招呼语为空"));
    }

    @Test
    void 演练模式_不调适配器但留一行SKIPPED() {
        Fixture f = new Fixture();
        f.run.setMode("DRY_RUN");

        f.run();

        assertThat(f.jobStore.finished).containsExactly("SKIPPED");
        assertThat(f.adapter.greetings).isEmpty();
        assertThat(f.runStore.delivered).isZero();
        assertThat(f.runLogs.messages).anyMatch(m -> m.contains("演练模式：本会投递"));
    }

    @Test
    void 正常投递_把招呼语与重复沟通开关交给适配器() {
        Fixture f = new Fixture();
        f.config.cfg.setRepeatChatEnabled(1);

        f.run();

        assertThat(f.jobStore.finished).containsExactly("DELIVERED");
        // 发出去的是判定给出的那一句，不是模板
        assertThat(f.adapter.greetings).containsExactly("您好，我对这个岗位很感兴趣");
        assertThat(f.adapter.allowRepeat).containsExactly(true);
        assertThat(f.runStore.delivered).isEqualTo(1);
        assertThat(f.run.getDeliveredCount()).isEqualTo(1);
        assertThat(f.host.events).contains("delivery.updated");
    }

    // ------------------------------------------------------------------ fixture

    private static final class Fixture {
        final RecordingAdapter adapter = new RecordingAdapter();
        final FakeConfig config = new FakeConfig();
        final FakeRunStore runStore = new FakeRunStore();
        final FakeJobStore jobStore = new FakeJobStore();
        final FakeAiJudge aiJudge = new FakeAiJudge();
        final FakeHost host = new FakeHost();
        final RecordingRunLogs runLogs = new RecordingRunLogs();
        final RunEntity run = new RunEntity();
        JobDetail detail = detail("Java 开发", "今日活跃");

        Fixture() {
            run.setId(7L);
            run.setPlatform("boss");
            run.setMode("NORMAL");
            run.setStatus(RunStatus.RUNNING.name());
            run.setEvaluatedCount(0);
            run.setDeliveredCount(0);
            config.cfg.setActiveLevelFloor("本月");
            jobStore.job.setId(11L);
            jobStore.job.setJobName("Java 开发");
            jobStore.job.setCompanyName("某公司");
        }

        void run() {
            new JobDisposition(run, adapter, config, runStore, jobStore, aiJudge, runLogs, host)
                    .process(config.cfg, detail, new SearchQuery("福州", "java"));
        }
    }

    private static JobDetail detail(String jobName, String activeRaw) {
        return new JobDetail("j1", jobName, "某公司", "10-15K", "福州", "3-5年", "本科",
                "描述", "HR", "招聘者", "本月", activeRaw, "{}", "https://example.invalid/j1");
    }

    private static BlacklistEntity blacklist(String kind, String keyword) {
        BlacklistEntity entity = new BlacklistEntity();
        entity.setKind(kind);
        entity.setKeyword(keyword);
        return entity;
    }

    /** 记下招呼语与重复沟通开关；默认投递成功 */
    private static final class RecordingAdapter extends FakePlatformAdapter {
        final List<String> greetings = new ArrayList<>();
        final List<Boolean> allowRepeat = new ArrayList<>();
        DeliveryResult result = DeliveryResult.delivered("您好");

        @Override
        public DeliveryResult deliver(JobDetail job, String greeting, DeliveryOptions options, BooleanSupplier stop) {
            greetings.add(greeting);
            allowRepeat.add(options.allowRepeatChat());
            return result;
        }
    }

    private static class FakeConfig extends ConfigStore {
        boolean qualityGate = true;
        String templateGreeting = "";
        final List<BlacklistEntity> blacklist = new ArrayList<>();
        final PlatformConfigEntity cfg = new PlatformConfigEntity();

        FakeConfig() {
            super(null, null, null, null, null, new ObjectMapper(), TIME);
            cfg.setPlatform("boss");
            cfg.setDailyDeliveryQuota(30);
            cfg.setDailyEvalLimit(150);
        }

        @Override
        public List<BlacklistEntity> blacklist() {
            return blacklist;
        }

        @Override
        public boolean qualityGateEnabled() {
            return qualityGate;
        }

        @Override
        public AiConfigEntity aiConfig() {
            AiConfigEntity ai = new AiConfigEntity();
            ai.setTemplateGreeting(templateGreeting);
            return ai;
        }

        @Override
        public List<String> repeatMessages(PlatformConfigEntity cfg) {
            return List.of("请问是有什么顾虑需要沟通吗？");
        }
    }

    private static final class FakeRunStore extends RunStore {
        int evaluated;
        int delivered;

        FakeRunStore() {
            super(null, null, null, null, null, TIME);
        }

        @Override
        public void incrementEvaluated(String platform) {
            evaluated++;
        }

        @Override
        public void incrementDelivered(String platform) {
            delivered++;
        }
    }

    /** 记下每条投递行的最终结果，也就是 {@code delivery.status} 会落成的那个字面量 */
    private static final class FakeJobStore extends JobStore {
        final List<String> upserts = new ArrayList<>();
        final List<String> finished = new ArrayList<>();
        final JobPostingEntity job = new JobPostingEntity();
        Decision decision = Decision.proceed();

        FakeJobStore() {
            super(null, null, TIME);
        }

        @Override
        public JobPostingEntity upsert(JobDetail detail, String platform, String activeLevel) {
            upserts.add(activeLevel);
            return job;
        }

        @Override
        public Decision decide(Long jobId) {
            return decision;
        }

        @Override
        public DeliveryEntity startDelivery(Long jobId, Long runId, String decidedBy) {
            DeliveryEntity entity = new DeliveryEntity();
            entity.setId(100L);
            entity.setDecidedBy(decidedBy);
            return entity;
        }

        @Override
        public void finishDelivery(DeliveryEntity delivery, DeliveryResult result,
                                   Integer score, String aiReason, String aiRaw) {
            finished.add(result.status().name());
        }
    }

    private static final class FakeAiJudge extends AiJudge {
        final List<String> calls = new ArrayList<>();
        Verdict verdict = new Verdict(true, 88, "匹配", "您好，我对这个岗位很感兴趣", "{}", null);

        FakeAiJudge() {
            super(null, null, null, null);
        }

        @Override
        public Verdict judge(JobDetail job, com.chatjob.evaluate.BlacklistMatcher blacklist) {
            calls.add(job.platformJobId());
            return verdict;
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
