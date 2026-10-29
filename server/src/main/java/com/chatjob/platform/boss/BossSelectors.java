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

    // ---------------------------------------------------------------- 工具栏：发简历
    /**
     * 输入框上方的工具栏容器。实测（chat-new/v5575）里面三个按钮：发简历 / 换电话 / 换微信。
     * 这三个按钮的「可用」不是原生 {@code disabled}，而是类名 {@code unable} ——
     * 与发送按钮的 {@code disabled} 是两个不同的判据，混用会导致对着灰按钮狂点。
     */
    public static final String CHAT_CONTROLS = ".chat-controls";
    public static final String TOOLBAR_BTN = "div.toolbar-btn";
    /** 工具栏按钮的不可用类名。带它时点下去没有任何反应，必须提前判。 */
    public static final String TOOLBAR_UNABLE_CLASS = "unable";
    /**
     * 点「发简历」后弹出的确认气泡（实测类名 {@code panel-resume sentence-popover}）。
     *
     * <p>这一步容易漏：按钮点下去<b>不会直接发</b>，只是弹层。只点按钮不等弹层、
     * 或者点完就走，都会表现为「看起来发了、其实没发」。
     */
    public static final String RESUME_CONFIRM_PANEL = ".panel-resume, .sentence-popover";
    /** 确认气泡里的「确定」。气泡只有「取消 / 确定」两个短文本，按文本精确匹配最稳。 */
    public static final String RESUME_CONFIRM_TEXT = "确定";
    /**
     * 简历发出后的凭据：一条系统消息，内含绿色简历卡片。
     *
     * <p>实测发出后新增两条 {@code li.message-item.item-system}：
     * 第一条文本「附件简历请求已发送」，第二条是卡片（标题=文件名、按钮=点击预览附件简历）。
     * 判据取卡片而不是文本 —— 文案会变，卡片结构跟着功能走。
     */
    public static final String RESUME_CARD =
            "li.message-item.item-system .message-card-wrap.boss-green";

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
