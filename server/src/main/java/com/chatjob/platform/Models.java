package com.chatjob.platform;

import java.util.List;

/**
 * 平台层的中性数据模型。这些类型不依赖 Playwright，便于单测用 hand-written fakes。
 */
public final class Models {

    private Models() {
    }

    /** 一个搜索组合：城市 + 关键词。筛选参数在 openSearch 时由 adapter 自己从配置读取。 */
    public record SearchQuery(String city, String cityCode, String keyword) {
    }

    public record ScrollParams(int roundLimit, int waitTimeSeconds) {
    }

    /**
     * @param loadedAll 是否由判据确认"确实加载完"，而不是撞到轮数上限
     */
    public record ScrollOutcome(boolean loadedAll, int rounds, int cards) {
    }

    public record JobDetail(String platformJobId,
                            String jobName,
                            String companyName,
                            String salary,
                            String area,
                            String experience,
                            String degree,
                            String description,
                            String hrName,
                            String hrTitle,
                            String activeLevel,
                            String activeRaw,
                            String rawJson,
                            String detailUrl) {
    }

    public record DeliveryResult(Status status, String greeting, String failureReason) {
        public enum Status {
            DELIVERED, FAILED, SKIPPED
        }

        public static DeliveryResult delivered(String greeting) {
            return new DeliveryResult(Status.DELIVERED, greeting, null);
        }

        public static DeliveryResult failed(String reason) {
            return new DeliveryResult(Status.FAILED, null, reason);
        }

        public static DeliveryResult skipped(String reason) {
            return new DeliveryResult(Status.SKIPPED, null, reason);
        }
    }

    /**
     * 「发简历」的结果。
     *
     * <p>三态必须分开，因为调用方对它们的处置完全不同：
     * <ul>
     *   <li>{@link Status#SENT} —— 卡片已出现，可以收尾。</li>
     *   <li>{@link Status#UNAVAILABLE} —— 工具栏按钮是灰的（平台要求「双方回复后可用」）。
     *       这是<b>业务状态不是故障</b>：重试一万次也还是灰的，所以不能进重试链，
     *       只能降级成文字回复。</li>
     *   <li>{@link Status#FAILED} —— 点了确认但没等到卡片。可能网络慢、可能平台改版，
     *       值得重试。</li>
     * </ul>
     */
    public record ResumeResult(Status status, String detail) {

        public enum Status {
            SENT, UNAVAILABLE, FAILED
        }

        public static ResumeResult sent(String detail) {
            return new ResumeResult(Status.SENT, detail);
        }

        public static ResumeResult unavailable(String detail) {
            return new ResumeResult(Status.UNAVAILABLE, detail);
        }

        public static ResumeResult failed(String detail) {
            return new ResumeResult(Status.FAILED, detail);
        }

        public boolean sent() {
            return status == Status.SENT;
        }
    }

    public record LoginState(boolean loggedIn, String reason) {
        public static LoginState ok() {
            return new LoginState(true, "登录态 cookie 存在");
        }

        public static LoginState lost(String reason) {
            return new LoginState(false, reason);
        }
    }

    /**
     * 会话列表里的一条。conversationId 用平台侧的稳定 id（拿不到时退化为标题）。
     *
     * @param unread 未读条数。{@code 0} = 确定没有未读；{@code >0} = 条数；
     *               {@code -1} = <b>读不到</b>（选择器失效或页面改版）。
     *               调用方必须把 -1 当异常处理，不能当成「没有未读」直接跳过。
     * @param expectedDigest 重试场景的定位凭据：要求列表条目的最后一条摘要与快照一致才允许打开。
     *                       扫描场景为 {@code null} —— 摘要就是刚读到的内容，不必自己校验自己。
     *                       语义见 spec 0002 §3.4：会话没有稳定 id，只能靠「标题精确 + 摘要未变」兜住。
     */
    public record UnreadThread(String conversationId, String title, String lastMessage, int unread,
                               String expectedDigest) {

        /** 扫描时读到的会话项：不需要二次校验 */
        public static UnreadThread of(String conversationId, String title, String lastMessage, int unread) {
            return new UnreadThread(conversationId, title, lastMessage, unread, null);
        }

        /** 由 {@code unread_task} 快照重建：打开前必须过摘要校验 */
        public static UnreadThread retry(String conversationId, String expectedDigest) {
            return new UnreadThread(conversationId, conversationId, null, 0, expectedDigest);
        }
    }

    public record ChatMessage(String id, String text, boolean fromSelf, boolean image) {
    }

    /** 平台无关的投递选项 */
    public record DeliveryOptions(boolean allowRepeatChat, List<String> repeatMessages) {
    }
}
