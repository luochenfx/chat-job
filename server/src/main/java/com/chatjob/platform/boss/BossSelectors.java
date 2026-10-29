package com.chatjob.platform.boss;

/**
 * BOSS 页面的全部选择器。改版时只改这一个文件。
 *
 * <p>命名按"用途"而不是"selector 长什么样"，这样改版时一眼能看出改的是哪一步。
 */
public final class BossSelectors {

    private BossSelectors() {
    }

    // ---------------------------------------------------------------- 列表页
    public static final String JOB_LIST_URL = "https://www.zhipin.com/web/geek/jobs";
    public static final String CARD = "ul.rec-job-list li.job-card-box";
    /** 列表容器；用于判断列表是否渲染出来 */
    public static final String LIST_CONTAINER = "div.job-list-container, ul.rec-job-list";
    public static final String FOOTER = "div#footer, #footer";
    /** 详情页入口（"查看更多信息"），href 形如 /job_detail/<encryptId>.html */
    public static final String MORE_INFO_BTN = "a.more-job-btn";
    public static final String SECURITY_VERIFY_MARK = "div.error-content, .verify-slider, .geetest_panel";

    // ---------------------------------------------------------------- 详情页
    public static final String CHAT_BUTTON = "a.btn-startchat, a.op-btn-chat";
    public static final String CHAT_INPUT =
            "div#chat-input.chat-input[contenteditable='true'], textarea.input-area";
    public static final String SEND_BUTTON = "div.send-message, button[type='send'].btn-send, button.btn-send";
    /**
     * 聊天区消息项。本人消息的类名带 item-myself。
     *
     * <p>实测结构（2026-10-29，chat-new/v5575）：{@code div.chat-record > div.chat-message
     * > ul.im-list > li.message-item}。旧值里的 {@code .chat-message-list} 与 {@code .dialog-con}
     * 在该版本全部静态资源中 0 命中 —— 这是值守"读不到任何消息"的直接原因。
     */
    public static final String MESSAGE_ITEM = "ul.im-list li.message-item, .chat-message li.message-item";
    public static final String MESSAGE_SELF = "li.message-item.item-myself, .item-myself";
    /**
     * 消息文本节点。去掉了旧的 {@code .content}：实测命中 37 个，会把职位卡片容器一起算进来，
     * 导致卡片被当成有文字的消息喂给下游。
     */
    public static final String MESSAGE_TEXT = ".text, .message-text";
    public static final String MESSAGE_IMAGE = "img.message-image, .image-message img";
    /** 消息条目的稳定 id（平台下发，长度统一 15 位）。缺失时才退化为「序号+文本hash」。 */
    public static final String MESSAGE_ID_ATTR = "data-mid";

    // ---------------------------------------------------------------- 沟通确认框（BossChatNavigator 用）
    public static final String GREET_CONFIRM = ".greet-boss-dialog:visible .greet-boss-footer a.sure-btn";
    public static final String STAY_THIS_PAGE = ":text-is('留在此页'):visible";
    public static final String CONFIRM_CONTAINER =
            "xpath=ancestor::*[not(self::body or self::html) and .//*[normalize-space(.)='继续沟通']][1]";

    // ---------------------------------------------------------------- 消息中心
    public static final String CHAT_LIST_URL = "https://www.zhipin.com/web/geek/chat";
    public static final String CHAT_LIST_ITEM = "li[role='listitem']";
    public static final String CHAT_LIST_TITLE = "div.title-box span.name-box, .title-box .name-box";
    public static final String CHAT_LIST_LAST_MSG = "div.gray.last-msg span.last-msg-text, .last-msg-text";
    /**
     * 未读徽标。实测：{@code .friend-content} 下的 {@code .notice-badge}（带数字）或 {@code .dot}（纯红点）。
     *
     * <p>两点必须保留：① 限定在 {@code .friend-content} 内，否则会把导航栏的
     * {@code header-nav-dot} 算成一个未读会话；② 旧值 {@code .badge-count} / {@code .unread-count}
     * 在该版本全部静态资源中 0 命中。
     */
    public static final String CHAT_LIST_UNREAD = ".friend-content .notice-badge, .friend-content .dot";
    /**
     * 会话项的内容区。只用来判断选择器作用域是否还有效：
     * 整个列表一条都没有它，说明页面改版了，此时「没读到未读」不能当成「没有未读」。
     */
    public static final String CHAT_LIST_ITEM_BODY = "div.friend-content";

    // ---------------------------------------------------------------- 登录态
    public static final String LOGIN_PAGE_MARK = "a[ka='403_login'], .sign-wrap, .login-register";
    public static final String LOGGED_IN_MARK = "li.nav-figure span.label-text, li.nav-figure";
}
