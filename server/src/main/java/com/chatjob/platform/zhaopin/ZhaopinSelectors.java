package com.chatjob.platform.zhaopin;

/**
 * 智联页面的全部选择器。改版时只改这一个文件（与 {@code BossSelectors} 同构）。
 *
 * <p><b>智联有两套详情版式，选择器不能混用</b>（2026-10-31 实测）：
 * <ul>
 *   <li>列表页右侧面板：{@code .job-detail-summary__apply} / {@code .job-description__content}</li>
 *   <li>独立详情页 {@code /jobdetail/<id>.htm}：{@code .summary-planes__action} /
 *       {@code .describtion-card__detail-content}</li>
 * </ul>
 * 抓详情走前者（点卡片不跳页，列表状态不丢），投递走后者（能等到成功弹窗）。
 * 若把两者的选择器写混，症状是「页面上明明有按钮，代码说找不到」。
 */
public final class ZhaopinSelectors {

    private ZhaopinSelectors() {
    }

    // ---------------------------------------------------------------- 列表页
    /**
     * 岗位卡片。卡片上没有任何按钮、没有链接、也没有 positionNumber（只有一个 class），
     * 所以<b>卡片不能作为身份来源</b>——编号从右侧面板的详情链接取，见 {@link #PANEL_DETAIL_LINK}。
     */
    public static final String CARD = ".job-card";
    /** 首屏数据里的岗位数组键名 */
    public static final String POSITION_LIST_FIELD = "positionList";
    public static final String SECURITY_VERIFY_MARK =
            ".verify-slider, .geetest_panel, .captcha-container, .yidun_panel";

    // ---------------------------------------------------------------- 右侧详情面板（列表页内）
    public static final String PANEL = ".job-detail-panel";
    public static final String PANEL_TITLE = ".job-detail-summary__title";
    public static final String PANEL_SALARY = ".job-detail-summary__salary";
    public static final String PANEL_JD = ".job-description__content";
    public static final String PANEL_PUBLISHER_NAME = ".job-detail-publisher__name-row";
    public static final String PANEL_PUBLISHER_DETAIL = ".job-detail-publisher__detail";
    /** 面板上的投递按钮（不是独立详情页那个） */
    public static final String PANEL_APPLY = ".job-detail-summary__apply";

    /**
     * 面板里指向当前岗位详情页的唯一链接（文案「查看更多职位信息」）。
     *
     * <p>这是<b>卡片 → 岗位编号的唯一可靠来源</b>：卡片本身不带任何身份信息，
     * 而面板内容随点击切换，链接 {@code /jobdetail/<编号>.htm} 里的编号就是当前选中岗位的编号。
     * 走这条路之后，翻页加载出来的卡片不需要再跟任何数组对下标。
     */
    public static final String PANEL_DETAIL_LINK = "a[href*='/jobdetail/']";
    public static final String PANEL_TITLE_TEXT = ".job-detail-summary__title-text";
    /** 摘要区的一枚标签：城市·区 / 经验 / 学历 / 类型 / 招N人（顺序不固定，按内容判别） */
    public static final String PANEL_TAG = ".job-detail-summary__tag";
    public static final String PANEL_COMPANY_NAME = ".job-detail-summary__company-name";
    /** 形如「/B轮 · 20-99人 · 产业互联网平台」 */
    public static final String PANEL_COMPANY_META = ".job-detail-summary__company-meta";
    public static final String PANEL_PUBLISHER_NAME_TEXT = ".job-detail-publisher__name";
    /** 发布者的活跃度标签，形如「今日回复19次」；没有标签表示平台没给 */
    public static final String PANEL_PUBLISHER_ACTIVITY = ".job-detail-publisher__activity-tag";
    /** 形如「人事管理 · 福建省卓正能源科技有限公司」 */
    public static final String PANEL_PUBLISHER_JOB_TITLE = ".job-detail-publisher__job-title";

    // ---------------------------------------------------------------- 独立详情页
    /** 按钮容器：里面只有一个按钮，文案是「立即投递」或「继续沟通」/「已投递」 */
    public static final String DETAIL_ACTION = ".summary-planes__action";
    public static final String DETAIL_JD = ".describtion-card__detail-content";
    public static final String DETAIL_APPLY_TEXT = "立即投递";
    public static final String DETAIL_CONTINUE_TEXT = "继续沟通";
    /** 已经投过时按钮容器里出现过的文案（任命中一个即判 SKIPPED，不点） */
    public static final String[] DETAIL_APPLIED_TEXTS = {"继续沟通", "已投递", "已申请", "已发送简历"};
    /**
     * 投递成功弹窗。它<b>常驻在 DOM 里</b>（未投递时不可见），所以判据必须是
     * 「可见 + 文案命中」，只看存在性会永远为真。
     */
    public static final String DELIVER_MODAL = ".deliver-greeting-modal";
    public static final String DELIVER_MODAL_OK_TEXT = "已向对方发送简历和打招呼语";

    // ---------------------------------------------------------------- 消息中心
    public static final String IM_SESSION_ITEM = ".im-session-item";
    public static final String IM_SESSION_NAME = ".im-session-item__name";
    public static final String IM_SESSION_BODY = ".im-session-item__body";
    public static final String IM_SESSION_BADGE = ".im-session-item__badge";
    public static final String IM_SESSION_PREVIEW = ".im-session-item__preview-text";

    public static final String IM_CHAT_PANEL = ".im-main-panel__chat";
    public static final String IM_CHAT_NAME = ".im-chat-header__name";
    /**
     * 一条消息的根节点。注意它<b>会嵌套</b>：外层包着时间、内层是气泡本体，
     * 直接 count 会把条数翻倍（实测 6 条消息数出 12 个），必须只取顶层。
     */
    public static final String IM_MESSAGE = ".im-message";
    public static final String IM_MESSAGE_ME = ".im-message__bubble--me";
    public static final String IM_MESSAGE_TEXT = ".im-msg-text";
    public static final String IM_MESSAGE_TIME = ".im-message__time";
    /** 简历索要卡片上的「同意」（ADR-0004 记为备用路径，主路径是工具栏「发简历」） */
    public static final String IM_RESUME_REQUEST_AGREE = ".im-msg-11__btn--agree";
    /** 简历发出后的系统提示文案。这是发简历唯一的成功凭据。 */
    public static final String IM_RESUME_SENT_MARK = "附件简历已发送";

    public static final String IM_INPUT = "textarea.im-sender__input";
    public static final String IM_SEND_BUTTON = ".im-sender__send-btn";
    public static final String IM_RESUME_BUTTON = ".im-send-resume-nav__chip";

    // ---------------------------------------------------------------- 登录态
    /** 登录页域名特征：落到 passport 就是没登录 */
    public static final String LOGIN_HOST_MARK = "passport.zhaopin.com";
    /** 未登录时首页出现的登录入口文案 */
    public static final String LOGIN_ENTRY_TEXT = "登录";
    public static final String LOGGED_IN_MARK = ".header-nav__user, .zp-header__userinfo, .im-session-item";
}
