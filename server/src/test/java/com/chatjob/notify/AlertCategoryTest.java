package com.chatjob.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 告警类别与 webhook 地址校验。
 *
 * <p>分组的判据是「漏发一条真告警」比「多发一条不认识的消息」严重：所以未知事件类型
 * 落到「异常」，而不是被当成「不认识的，别吵人」丢进待命组 —— 后者会让新加的事件
 * 在默认配置下彻底静音。
 */
class AlertCategoryTest {

    @Test
    @DisplayName("三个已知类别各归各位")
    void knownKinds() {
        assertThat(AlertCategory.ofKind("circuit")).isEqualTo(AlertCategory.ABNORMAL);
        assertThat(AlertCategory.ofKind("run-failed")).isEqualTo(AlertCategory.ABNORMAL);
        assertThat(AlertCategory.ofKind("login-lost")).isEqualTo(AlertCategory.ABNORMAL);
        assertThat(AlertCategory.ofKind("standby")).isEqualTo(AlertCategory.STANDBY);
        assertThat(AlertCategory.ofKind("duty-end")).isEqualTo(AlertCategory.DUTY_END);
    }

    @Test
    @DisplayName("测试消息不受任何开关约束")
    void testHasNoCategory() {
        assertThat(AlertCategory.ofKind("test")).isNull();
    }

    @Test
    @DisplayName("未知事件类型归到异常，不会被静音")
    void unknownKindIsAbnormal() {
        assertThat(AlertCategory.ofKind("brand-new-event")).isEqualTo(AlertCategory.ABNORMAL);
        assertThat(AlertCategory.ofKind(null)).isEqualTo(AlertCategory.ABNORMAL);
    }

    @Test
    @DisplayName("webhook 必须是 https 且带域名：粘半截或 http 当场拒绝")
    void requireWebhookUrl() {
        String ok = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc";
        assertThat(NotifySettings.requireWebhookUrl("  " + ok + "  ")).isEqualTo(ok);

        assertThatThrownBy(() -> NotifySettings.requireWebhookUrl("http://qyapi.weixin.qq.com/x?key=1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
        assertThatThrownBy(() -> NotifySettings.requireWebhookUrl("https:///send?key=1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotifySettings.requireWebhookUrl(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请填写");
        assertThatThrownBy(() -> NotifySettings.requireWebhookUrl(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
