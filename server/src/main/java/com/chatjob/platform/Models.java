package com.chatjob.platform;

import java.util.List;

/**
 * 平台层的中性数据模型。这些类型不依赖 Playwright，便于单测用 hand-written fakes。
 */
public final class Models {

    private Models() {
    }

    /**
     * 一个搜索组合：城市 + 关键词。筛选参数在 openSearch 时由 adapter 自己从配置读取。
     *
     * <p>只有城市名，没有城市码：智联的 {@code jl=} 要的是字典里的 code，由它自己的 urlBuilder
     * 查表算出（查不到就报错，不推测）。城市码若放进这里，就成了「调用方必须知道某个平台要查表」
     * 的隐性契约 —— 而调用方从来只持有城市名。
     */
    public record SearchQuery(String city, String keyword) {
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

    /**
     * 「接受工作地点」的结果。
     *
     * <p>五种状态分开，因为调用方的处置不同：
     * <ul>
     *   <li>{@link Status#ACCEPTED} —— 点下了「可以接受」，并且按钮不再可点，可以记一条值守日志。</li>
     *   <li>{@link Status#NO_CARD} —— 整个消息区没有这类卡片，照常走原来的回复流程。</li>
     *   <li>{@link Status#NOT_CLICKABLE} —— 有卡片但按钮不可点。2026-11-09 实测：点过之后
 *       平台给两个按钮都加上 {@code disabled} 类，并自动代发一条我方文字消息，所以这个状态
 *       通常就是「这张卡片已经处理过」，是业务终态不是故障，不重试、不记失败提醒。</li>
     *   <li>{@link Status#FAILED} —— 点了但按钮仍可点，记一条提醒；不主动排重试。</li>
     *   <li>{@link Status#UNSUPPORTED} —— 这个平台还没有这类卡片（智联暂未实现）。</li>
     * </ul>
     *
     * @param cardMessageId 卡片那条消息的平台 id，用来写值守日志；没有卡片时为 {@code null}
     * @param cardIsLast     卡片是不是消息区里最后一条，决定点击后还要不要按原来的流程回复
     */
    public record WorkLocationResult(Status status, String cardMessageId, boolean cardIsLast, String detail) {

        public enum Status {
            ACCEPTED, NO_CARD, NOT_CLICKABLE, FAILED, UNSUPPORTED
        }

        public static WorkLocationResult noCard() {
            return new WorkLocationResult(Status.NO_CARD, null, false, "消息区里没有工作地点确认卡片");
        }

        public static WorkLocationResult unsupported() {
            return new WorkLocationResult(Status.UNSUPPORTED, null, false, "这个平台还没有这类卡片");
        }

        /**
         * 按钮已经不可点。{@code cardIsLast} 必须按页面真实顺序传：平台会代发一条我方消息，
         * 点过的卡片通常不再是最后一条；写死 true 会让这个会话之后的消息全被跳过回复。
         */
        public static WorkLocationResult notClickable(String detail, boolean cardIsLast) {
            return new WorkLocationResult(Status.NOT_CLICKABLE, null, cardIsLast, detail);
        }

        public static WorkLocationResult accepted(String cardMessageId, boolean cardIsLast, String detail) {
            return new WorkLocationResult(Status.ACCEPTED, cardMessageId, cardIsLast, detail);
        }

        public static WorkLocationResult failed(String cardMessageId, boolean cardIsLast, String detail) {
            return new WorkLocationResult(Status.FAILED, cardMessageId, cardIsLast, detail);
        }

        public boolean accepted() {
            return status == Status.ACCEPTED;
        }

        /**
         * 卡片是最后一条时不该再交给回复流程：那段文本是卡片标题、地址与按钮字，
         * 不是对方说的话，拿去判定只会回一句不相干的话。没点成也一样跳过。
         */
        public boolean blocksReply() {
            return cardIsLast && (status == Status.ACCEPTED
                    || status == Status.NOT_CLICKABLE || status == Status.FAILED);
        }
    }

    public record LoginState(boolean loggedIn, String reason) {
        public static LoginState ok() {
            return new LoginState(true, "已找到登录信息");
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

    /**
     * 一条消息。
     *
     * <p>{@code fromSelf} 与 {@code system} 是<b>两个独立的维度</b>，不能合并成一个
     * 「要不要回复」的布尔：前者是「我打的字」，后者是平台替我播报的回执
     * （「附件简历请求已发送」「对方已查看了您的附件简历」）。
     *
     * <p>系统消息必须单独成维，是因为它既不是对方说的话，也不是我打的字，却<b>带着对方
     * 说话时的关键词</b>。2026-11-06 的真实故障：那条「您的附件简历已发送给对方」整条命中
     * 「简历索取」规则，于是刚发完的简历又被发了一次，一串回执连发三份。
     *
     * @param image 含图片（图片消息的文字通常读不到，只能靠这个标记说明「不是没内容」）
     * @param system 平台系统消息。判据见 docs/tech/0002 F15：Boss 的 DOM 类名
     *               {@code item-system} 已经把「本人 / 对方 / 系统」三分开了
     * @param workLocationCard 这条消息是不是工作地点确认卡片。演练路径不能点按钮，
     *                         靠这个标记才知道「本来会接受工作地点」；真实路径以页面读回为准
     */
    public record ChatMessage(String id, String text, boolean fromSelf, boolean image, boolean system,
                              boolean workLocationCard) {

        /** 兼容非系统消息、非卡片的构造点（两个适配器之外，大量单测直接用这个形状） */
        public ChatMessage(String id, String text, boolean fromSelf, boolean image) {
            this(id, text, fromSelf, image, false, false);
        }

        /** 兼容带系统标记、没有卡片标记的构造点 */
        public ChatMessage(String id, String text, boolean fromSelf, boolean image, boolean system) {
            this(id, text, fromSelf, image, system, false);
        }
    }

    /**
     * 平台无关的投递选项。
     *
     * @param search 本次投递所属的搜索组合。投递时要用它先把详情页的<b>来源</b>变成站内
     *               （先进列表页、再进详情页，见 spec 0006 §3.5）；为 {@code null} 时
     *               只能从空白页直接打开详情页，那一跳的请求来源不是站内。
     *               不是"投递条件"——筛选条件在平台配置里，这里只用来拼列表页 URL。
     */
    public record DeliveryOptions(boolean allowRepeatChat, List<String> repeatMessages, SearchQuery search) {
    }
}
