package com.chatjob.notify;

/**
 * 通知通道。抽成接口的唯一理由是"以后换通道"不用改业务代码 ——
 * 企微机器人、Server酱、系统托盘都能挂上来。
 */
public interface Notifier {

    /**
     * @param kind     事件类型，用于节流合并（同一 {@code kind} + 同一平台在一个窗口内只发一条）
     * @param platform 平台展示名（如「BOSS 直聘」）。{@code null} 表示与平台无关的通知
     * @param title    标题（企微 markdown 里加粗）
     * @param body     正文，支持 markdown
     */
    void notify(String kind, String platform, String title, String body);

    /**
     * 与平台无关的通知（测试消息、全局故障）。
     *
     * <p>默认落到 4 参版本、平台传 {@code null}：这样「有没有平台归属」在通道侧是可见的，
     * 而不是靠一个字符串前缀悄悄表达。
     */
    default void notify(String kind, String title, String body) {
        notify(kind, null, title, body);
    }
}
