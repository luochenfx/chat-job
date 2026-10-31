package com.chatjob.notify;

import com.chatjob.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.chatjob.notify.NotifyTestDoubles.cipher;
import static com.chatjob.notify.NotifyTestDoubles.configStore;
import static com.chatjob.notify.NotifyTestDoubles.props;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生效地址的解析（ADR-0006）：控制台配置优先，库里为空才回落 application.yaml。
 *
 * <p>这套判断只能有一处 —— 散在通知器和 Controller 里各写一遍，迟早出现
 * 「页面说未配置、实际还在往老群发」。所以这里逐条锁住四种来源。
 */
class NotifySettingsTest {

    private static final String DB_WEBHOOK = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=db";
    private static final String YAML_WEBHOOK = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=yaml";

    @Test
    @DisplayName("库里有地址时以库为准，配置文件里那份不再参与")
    void dbWins() {
        NotifyTestDoubles.FakeConfigStore store = configStore();
        store.entity.setWecomWebhookCipher("cipher-text");

        NotifySettings.Resolved resolved =
                new NotifySettings(store, cipher(DB_WEBHOOK), props(YAML_WEBHOOK)).resolve();

        assertThat(resolved.source()).isEqualTo(NotifySettings.Source.DB);
        assertThat(resolved.webhook()).isEqualTo(DB_WEBHOOK);
        assertThat(resolved.configured()).isTrue();
    }

    @Test
    @DisplayName("库里为空时回落配置文件，并标明来源")
    void fallsBackToYaml() {
        NotifySettings.Resolved resolved =
                new NotifySettings(configStore(), cipher(null), props("  " + YAML_WEBHOOK + " ")).resolve();

        assertThat(resolved.source()).isEqualTo(NotifySettings.Source.YAML);
        assertThat(resolved.webhook()).isEqualTo(YAML_WEBHOOK);
    }

    @Test
    @DisplayName("两边都没有就是没配置")
    void nothingConfigured() {
        NotifySettings.Resolved resolved =
                new NotifySettings(configStore(), cipher(null), props("")).resolve();

        assertThat(resolved.source()).isEqualTo(NotifySettings.Source.NONE);
        assertThat(resolved.webhook()).isNull();
        assertThat(resolved.configured()).isFalse();
    }

    @Test
    @DisplayName("库里有密文却解不开时，绝不回落到配置文件")
    void brokenDoesNotFallBack() {
        NotifyTestDoubles.FakeConfigStore store = configStore();
        store.entity.setWecomWebhookCipher("cipher-from-another-machine");

        // 配置文件里明明有值：回落会把告警发到用户以为早就换掉的群里
        NotifySettings.Resolved resolved =
                new NotifySettings(store, cipher(null), props(YAML_WEBHOOK)).resolve();

        assertThat(resolved.source()).isEqualTo(NotifySettings.Source.BROKEN);
        assertThat(resolved.webhook()).isNull();
    }

    @Test
    @DisplayName("读库出错按未配置处理，不把异常抛给正在报表的通知器")
    void readFailureIsSwallowed() {
        NotifySettings.Resolved resolved = new NotifySettings(
                new NotifyTestDoubles.ThrowingConfigStore(), cipher(DB_WEBHOOK), props("")).resolve();

        assertThat(resolved.source()).isEqualTo(NotifySettings.Source.NONE);
        assertThat(resolved.configured()).isFalse();
    }

    @Test
    @DisplayName("节流窗口：未配置 yaml 或写成 0 都按 300 秒，其余原样")
    void throttleWindow() {
        assertThat(new NotifySettings(configStore(), cipher(null), props("", 0))
                .throttleWindowSeconds()).isEqualTo(NotifySettings.DEFAULT_THROTTLE_WINDOW_SECONDS);
        assertThat(new NotifySettings(configStore(), cipher(null), props("", 600))
                .throttleWindowSeconds()).isEqualTo(600);

        AppProperties noNotify = new AppProperties(null, null, null, null, "boss");
        assertThat(new NotifySettings(configStore(), cipher(null), noNotify)
                .throttleWindowSeconds()).isEqualTo(NotifySettings.DEFAULT_THROTTLE_WINDOW_SECONDS);
    }

    @Test
    @DisplayName("三个开关读到 null 按开处理：列上有 DEFAULT 1，null 只可能是还没落库")
    void switchesDefaultToOn() {
        NotifyTestDoubles.FakeConfigStore store = configStore();
        store.entity.setAlertAbnormal(null);
        store.entity.setAlertStandby(0);

        NotifySettings settings = new NotifySettings(store, cipher(null), props(""));

        assertThat(settings.alertEnabled(AlertCategory.ABNORMAL)).isTrue();
        assertThat(settings.alertEnabled(AlertCategory.STANDBY)).isFalse();
        assertThat(settings.alertEnabled(AlertCategory.DUTY_END)).isTrue();
        // 测试消息不受开关约束
        assertThat(settings.alertEnabled(null)).isTrue();
    }
}
