package com.chatjob.notify;

/**
 * 通知通道。抽成接口的唯一理由是"以后换通道"不用改业务代码 ——
 * 企微机器人、Server酱、系统托盘都能挂上来。
 */
public interface Notifier {

    /**
     * @param kind     事件类型，用于节流合并（同一 {@code kind} + 同一平台在一个窗口内只发一条）
     *                 与告警类别过滤（见 {@link AlertCategory#ofKind(String)}）
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

    /**
     * 最近一次投递尝试的结果，给控制台展示用。
     *
     * <p>放进接口而不仅放在企微实现上，是因为「没发出去」必须可见 —— 那是通道自己的责任，
     * 不该由调用方猜。通道不支持或还没有过尝试时返回 {@code null}。
     */
    default Delivery lastDelivery() {
        return null;
    }

    /**
     * @param at     尝试时刻（{@code yyyy-MM-dd HH:mm:ss}）
     * @param sent   是否真的发出去了（HTTP 2xx 才算）
     * @param detail 一句话说明，失败时带原因
     */
    record Delivery(String at, boolean sent, String detail) {
    }
}
