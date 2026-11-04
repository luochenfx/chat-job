package com.chatjob.quality;

import com.chatjob.config.SecretCipher;
import com.chatjob.evaluate.ChatClient;
import com.chatjob.evaluate.QualityJudge;
import com.chatjob.notify.AlertCategory;
import com.chatjob.notify.Notifier;
import com.chatjob.notify.NotifySettings;
import com.chatjob.platform.Models.ChatMessage;
import com.chatjob.runlog.RunLog;
import com.chatjob.runlog.RunLogLevel;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.ConversationEntity;
import com.chatjob.store.entity.QualityAssessmentEntity;
import com.chatjob.time.FixedTimeSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提醒的发出条件。
 *
 * <p>钉住的是「先问通道能不能发、再记已提醒」这个顺序：反过来的话，告警开关关着时过线的会话
 * 会被永久标成已提醒，用户之后打开开关也收不到 —— 界面上还写着「已提醒」，
 * 而手机里一条都没有。这个错误没有异常、没有日志，只有对不上账时才被发现。
 */
class QualityAssessorNotifyTest {

    private static final FixedTimeSource TIME = FixedTimeSource.of("2026-03-15T09:00:00");

    private static ConversationEntity conversation() {
        ConversationEntity c = new ConversationEntity();
        c.setId(7L);
        c.setPlatform("boss");
        c.setPlatformConversationId("张三");
        return c;
    }

    private static List<ChatMessage> messages() {
        return List.of(
                new ChatMessage("m1", "你好，方便发份简历吗", false, false),
                new ChatMessage("m2", "简历收到了，下周二方便面试吗", false, false));
    }

    @Test
    void 告警类别关着时不发也不标记() {
        Fixture f = new Fixture(false, true);

        QualityAssessor.Assessed assessed = f.assessor.assessIfDue(
                conversation(), messages(), f.assessor.budget(), f.runLog);

        assertThat(assessed).isNotNull();
        assertThat(assessed.notified()).isFalse();
        assertThat(f.store.marked).isZero();
        assertThat(f.notifier.kinds).isEmpty();
    }

    @Test
    void 没配webhook时不发也不标记() {
        Fixture f = new Fixture(true, false);

        QualityAssessor.Assessed assessed = f.assessor.assessIfDue(
                conversation(), messages(), f.assessor.budget(), f.runLog);

        assertThat(assessed).isNotNull();
        assertThat(assessed.notified()).isFalse();
        // 不标记：等用户配好地址，下一轮重判时还能补发
        assertThat(f.store.marked).isZero();
    }

    @Test
    void 通道可用时发一条并标记() {
        Fixture f = new Fixture(true, true);

        QualityAssessor.Assessed assessed = f.assessor.assessIfDue(
                conversation(), messages(), f.assessor.budget(), f.runLog);

        assertThat(assessed).isNotNull();
        assertThat(assessed.score()).isEqualTo(90);
        assertThat(assessed.notified()).isTrue();
        assertThat(f.store.marked).isEqualTo(1);
        assertThat(f.notifier.kinds).containsExactly("hot-job");
        // 反查不到岗位时标题退到会话标题（= HR 名字），而不是只写一句「发现好岗位」
        assertThat(f.notifier.titles.get(0)).contains("张三");
    }

    @Test
    void 没过线不发提醒() {
        Fixture f = new Fixture(true, true);
        f.judge.score = 60;

        QualityAssessor.Assessed assessed = f.assessor.assessIfDue(
                conversation(), messages(), f.assessor.budget(), f.runLog);

        assertThat(assessed).isNotNull();
        assertThat(assessed.notified()).isFalse();
        assertThat(f.store.marked).isZero();
        assertThat(f.notifier.kinds).isEmpty();
    }

    // ------------------------------------------------------------------ 装配

    private static final class Fixture {
        final FakeStore store = new FakeStore();
        final FakeNotifier notifier = new FakeNotifier();
        final FakeJudge judge = new FakeJudge();
        final RecordingRunLog runLog = new RecordingRunLog();
        final QualityAssessor assessor;

        Fixture(boolean alertEnabled, boolean configured) {
            FakeConfig config = new FakeConfig();
            FakeSettings settings = new FakeSettings(alertEnabled, configured);
            assessor = new QualityAssessor(config, judge, store, null, notifier, settings,
                    new ObjectMapper(), TIME);
        }
    }

    private static final class FakeConfig extends ConfigStore {
        FakeConfig() {
            super(null, null, null, null, null, new ObjectMapper(), TIME);
        }

        @Override
        public List<String> qualityCriteria() {
            return List.of("双休");
        }

        @Override
        public int qualityScoreLine() {
            return 80;
        }

        @Override
        public int qualityMinHrMessages() {
            return 2;
        }

        @Override
        public int qualityReevalHours() {
            return 6;
        }

        @Override
        public int qualityMaxPerDay() {
            return 30;
        }

        @Override
        public int qualityMaxPerSweep() {
            return 3;
        }
    }

    private static final class FakeJudge extends QualityJudge {
        int score = 90;

        FakeJudge() {
            super(null, new SecretCipher(), new ObjectMapper(), new ChatClient(new ObjectMapper()));
        }

        @Override
        public Assessment assess(Snapshot job, List<ChatMessage> messages, List<String> criteria) {
            return new Assessment(score,
                    List.of(new Item("双休", Verdict.YES, "岗位描述写了周末双休")), "条件齐、HR 问得具体", null);
        }
    }

    private static final class FakeStore extends QualityStore {
        int marked;

        FakeStore() {
            super(null, null, new ObjectMapper(), TIME);
        }

        @Override
        public QualityAssessmentEntity latest(Long conversationId) {
            return null;
        }

        @Override
        public void save(QualityAssessmentEntity entity) {
            // 这组用例只关心提醒，不关心落库
        }

        @Override
        public long countOnDay(String platform, String from, String to) {
            return 0;
        }

        @Override
        public boolean markNotified(Long conversationId) {
            marked++;
            return true;
        }
    }

    private static final class FakeSettings extends NotifySettings {
        private final boolean enabled;
        private final boolean configured;

        FakeSettings(boolean enabled, boolean configured) {
            super(null, new SecretCipher(), null);
            this.enabled = enabled;
            this.configured = configured;
        }

        @Override
        public boolean alertEnabled(AlertCategory category) {
            return enabled;
        }

        @Override
        public boolean configured() {
            return configured;
        }
    }

    private static final class FakeNotifier implements Notifier {
        final List<String> kinds = new ArrayList<>();
        final List<String> titles = new ArrayList<>();

        @Override
        public void notify(String kind, String platform, String title, String body) {
            kinds.add(kind);
            titles.add(title);
        }
    }

    private static final class RecordingRunLog implements RunLog {
        final List<String> messages = new ArrayList<>();

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
        public void error(String message, Throwable cause) {
            messages.add(message);
        }

        @Override
        public void warn(String message, Throwable cause) {
            messages.add(message);
        }

        @Override
        public void log(RunLogLevel level, String message) {
            messages.add(message);
        }
    }
}
