package com.chatjob.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 通知的平台归属（spec 0004 阶段 2c）。
 *
 * <p>只测「标题怎么拼、节流键怎么拼」这两件纯函数的事：真正发 HTTP 的那一段在单测里
 * 无法断言，而这两件恰好是「两个平台的通知会不会互相顶替」的全部判据。
 */
class WecomRobotNotifierTest {

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
}
