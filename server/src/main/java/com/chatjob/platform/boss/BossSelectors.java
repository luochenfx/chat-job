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
     *
     * <p>它<b>只覆盖纯文字气泡</b>：卡片类消息（索要简历、职位卡片、名片）里根本没有
     * {@code .text} 节点 —— 实测 {@code hasTextEl=false}。所以卡片要走
     * {@link #MESSAGE_TIME} 之外的整块正文，见 {@code BossAdapter#cardText}。
     */
    public static final String MESSAGE_TEXT = ".text, .message-text";
    /**
     * 消息项里的时间分隔（实测 {@code .item-time}，是消息项的子节点而不是独立条目）。
     *
     * <p>只有读卡片整块正文时才需要它：纯文字气泡走 {@link #MESSAGE_TEXT}，碰不到时间。
     */
    public static final String MESSAGE_TIME = ".item-time";
    public static final String MESSAGE_IMAGE = "img.message-image, .image-message img";
    /**
     * 消息条目的三个归属类名（读 {@code class} 属性，不是选择器；选择器见 {@link #MESSAGE_SELF}）。
     *
     * <p>{@code item-system} 是平台播报的回执与提示：「附件简历请求已发送」
     * 「对方已同意了您的附件简历」都落在这里。它们不该被当成 HR 说的话 ——
     * 详见 {@code Models.ChatMessage} 的 {@code system} 字段。
     *
     * <p>但<b>只认这一个类名是不够的</b>：卡片消息也会挂在 {@code item-system} 上
     * （{@link #RESUME_CARD} 的实测记录里，我方发出的简历卡片就是）。所以取数时还要叠加
     * 「正文来自 {@link #MESSAGE_TEXT}」这个条件，见 {@code BossAdapter#readMessages}。
     */
    public static final String MESSAGE_SYSTEM_CLASS = "item-system";
    public static final String MESSAGE_SELF_CLASS = "item-myself";
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
     * 点「发简历」后<b>可能</b>弹出的确认气泡（实测类名 {@code panel-resume sentence-popover}）。
     *
     * <p>这一步容易漏：多数情况下按钮点下去<b>不会直接发</b>，只是弹层。只点按钮不等弹层、
     * 或者点完就走，都会表现为「看起来发了、其实没发」。
     *
     * <p>但<b>它不是必经步骤</b>：对方已经主动索要过简历时，平台不发二次确认，
     * 点了就直接发。所以「没弹气泡」不能判成失败，只能用「简历卡片有没有出现」收口。
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

    // ---------------------------------------------------------------- 工作地点确认卡片（V21）
    /**
     * 卡片容器。实测（2026-11-09，chat-new/v5578）：{@code li.message-item.item-friend
     * > .item-dialog > .msg-dialog-position}，里面有标题、地图、地址和两个按钮。
     * 这类卡片没有 {@code .text} 节点，读消息时走 {@code BossAdapter#cardText}。
     */
    public static final String WORK_LOCATION_CARD = ".msg-dialog-position";
    /** 卡片标题。用它确认这张卡确实是「是否接受工作地点」，不是别的对话框消息。 */
    public static final String WORK_LOCATION_TITLE = ".msg-dialog-title";
    public static final String WORK_LOCATION_TITLE_TEXT = "您是否接受此工作地点";
    /**
     * 按钮区里「可以接受」的文案。按精确文本定位，不认 {@code btn-light-v2} 这种通用样式类 ——
     * 样式类在别处也出现，改版时换的是文案的概率更低。
     */
    public static final String WORK_LOCATION_ACCEPT_TEXT = "可以接受";
    /** 按钮不可点时可能出现的类名；{@code unable} 复用 {@link #TOOLBAR_UNABLE_CLASS}。 */
    public static final String WORK_LOCATION_DISABLED_CLASS = "disabled";
    public static final String WORK_LOCATION_IS_DISABLED_CLASS = "is-disabled";

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
