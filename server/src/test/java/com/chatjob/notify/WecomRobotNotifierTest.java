package com.chatjob.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.chatjob.notify.NotifyTestDoubles.cipher;
import static com.chatjob.notify.NotifyTestDoubles.configStore;
import static com.chatjob.notify.NotifyTestDoubles.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 通知的平台归属（spec 0004 阶段 2c）与类别过滤（ADR-0006）。
 *
 * <p>只测「标题怎么拼、节流键怎么拼、什么情况下不发」这几件纯逻辑的事：真正发 HTTP 的
 * 那一段在单测里无法断言。下面的用例刻意都落在<b>不会真的发出请求</b>的分支上
 * （开关关着，或没有地址），因此不需要网络。
 */
class WecomRobotNotifierTest {

    /** 假的机器人地址：用例都停在「不会真的发出请求」的分支上，它不需要通 */
    private static final String WEBHOOK = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=unit-test";

    @Test
    @DisplayName("标题带平台展示名，用方括号括起来")
    void headHasPlatform() {
        assertEquals("[BOSS 直聘] 任务已熔断：QUOTA", WecomRobotNotifier.headOf("BOSS 直聘", "任务已熔断：QUOTA"));
        assertEquals("[智联招聘] 扫描已结束，值守继续", WecomRobotNotifier.headOf("智联招聘", "扫描已结束，值守继续"));
    }

    @Test
    @DisplayName("没有平台归属的通知（测试消息）保持原标题，不加空的方括号")
    void headWithoutPlatform() {
        assertEquals("chat-job 通知测试", WecomRobotNotifier.headOf(null, "chat-job 通知测试"));
        assertEquals("chat-job 通知测试", WecomRobotNotifier.headOf("", "chat-job 通知测试"));
        assertEquals("chat-job 通知测试", WecomRobotNotifier.headOf("   ", "chat-job 通知测试"));
    }

    @Test
    @DisplayName("节流键带平台：两个平台的同类事件不会被合并成一条")
    void throttleKeySeparatesPlatforms() {
        assertNotEquals(
                WecomRobotNotifier.throttleKeyOf("circuit", "BOSS 直聘"),
                WecomRobotNotifier.throttleKeyOf("circuit", "智联招聘"));
        assertEquals("circuit|BOSS 直聘", WecomRobotNotifier.throttleKeyOf("circuit", "BOSS 直聘"));
    }

    @Test
    @DisplayName("节流键在没有平台时就是事件类型本身：与改造前行为一致")
    void throttleKeyWithoutPlatform() {
        assertEquals("test", WecomRobotNotifier.throttleKeyOf("test", null));
        assertEquals("test", WecomRobotNotifier.throttleKeyOf("test", ""));
    }

    @Test
    @DisplayName("类别开关关掉时整条不发，也不留下投递记录")
    void categoryOffSkipsQuietly() {
        NotifyTestDoubles.FakeConfigStore store = configStore();
        store.entity.setAlertStandby(0);

        WecomRobotNotifier notifier = new WecomRobotNotifier(
                new NotifySettings(store, cipher(WEBHOOK), props("")), new ObjectMapper());

        notifier.notify("standby", "BOSS 直聘", "搜索已结束，值守继续", "正文");

        // 没走到发送：不是「发了但失败」，而是这条路根本没走
        assertThat(notifier.lastDelivery()).isNull();
    }

    @Test
    @DisplayName("没配地址时也要留下「没发出去」的记录，而不是静默成功")
    void unconfiguredLeavesRecord() {
        WecomRobotNotifier notifier = new WecomRobotNotifier(
                new NotifySettings(configStore(), cipher(null), props("")), new ObjectMapper());

        notifier.notify("circuit", "BOSS 直聘", "任务已熔断", "正文");

        assertThat(notifier.lastDelivery()).isNotNull();
        assertThat(notifier.lastDelivery().sent()).isFalse();
        assertThat(notifier.lastDelivery().detail()).contains("还没配置");
    }

    @Test
    @DisplayName("测试消息不受类别开关约束：异常告警关掉后仍会尝试发送")
    void testMessageIgnoresSwitches() {
        NotifyTestDoubles.FakeConfigStore store = configStore();
        store.entity.setAlertAbnormal(0);

        WecomRobotNotifier notifier = new WecomRobotNotifier(
                new NotifySettings(store, cipher(null), props("")), new ObjectMapper());

        notifier.notify("test", "chat-job 通知测试", "正文");

        // 被开关拦下的用例结果是 null（见上一个用例），这里是「尝试过」的记录
        assertThat(notifier.lastDelivery()).isNotNull();
        assertThat(notifier.lastDelivery().sent()).isFalse();
    }
}
