package com.chatjob.platform;

import java.util.List;
import java.util.function.BooleanSupplier;

import com.chatjob.platform.Models.ChatMessage;
import com.chatjob.platform.Models.DeliveryOptions;
import com.chatjob.platform.Models.DeliveryResult;
import com.chatjob.platform.Models.JobDetail;
import com.chatjob.platform.Models.LoginState;
import com.chatjob.platform.Models.ScrollOutcome;
import com.chatjob.platform.Models.SearchQuery;
import com.chatjob.platform.Models.UnreadThread;

/**
 * 平台适配器：只负责"平台动作"，不做任何决策。
 *
 * <p>分工是刻意的 —— 该不该投、投什么文案由 orchestrator / evaluate 决定，
 * adapter 只回答"点了这个按钮之后平台发生了什么"。这样换成智联时，
 * 变的只有这个实现类，决策逻辑一行都不用动。
 *
 * <p>调度在实现内部完成：每个方法自己经 {@link com.chatjob.driver.PlaywrightExecutor}
 * 切到 Playwright 专用线程（有返回值的走 {@code call}，void 的走 {@code run}）。
 * 调用方在业务线程上直接调即可，不需要、也没有权限自己调度。
 */
public interface PlatformAdapter {

    String platform();

    /** 结构性信号优先：登录态 cookie 存在性；DOM 探测只作备用手段 */
    LoginState probeLogin();

    /**
     * 打开登录页，交给人在浏览器窗口里扫码。
     *
     * <p>不做自动登录：验证码与扫码都只能由人完成，机器代劳只会把账号送进风控。
     * 这个入口存在的意义是让"第一次登录"有地方发生 —— 否则 probeLogin 永远拿不到 cookie，
     * 任务永远停在 LOGIN_LOST，人连浏览器窗口都看不到。
     */
    void openLogin();

    /** 打开列表页（拼 URL，不走首页 UI 搜索） */
    void openSearch(SearchQuery query);

    /**
     * 反复滚动直到判据确认加载完。
     *
     * @param roundLimit 轮数上限，只是保险丝。真正的"到底了"由各平台自己的判据认定，
     *                   撞到上限时返回的 {@link ScrollOutcome#loadedAll()} 为 false ——
     *                   两者不是同一件事，别把"轮数到顶"读成"已经加载完"
     * @param stop       调用方请求停止（人工停止 / 时段到点），实现应在轮首检查
     */
    ScrollOutcome scrollUntilEnd(int roundLimit, BooleanSupplier stop);

    /**
     * 点第 {@code cardIndex} 张卡片并抓详情接口。返回 null 表示这张卡没抓到（已改版 / 请求超时）。
     * 抓不到时由调用方决定是跳过还是熔断。
     *
     * <p><b>{@code cardIndex} 是「列表里按滚动顺序的第几张」，不是岗位标识。</b>
     * 它只有配上「刚滚动过的那个列表」才有意义，所以调用方必须先用 {@link #scrollUntilEnd}
     * 把列表加载到底；实现内部每次都按索引重新定位，不缓存卡片句柄 ——
     * 两个平台的列表都会重渲染，缓存的 Locator 迟早失效。
     *
     * <p>由此有一条已知限制：同日续扫时，断点里的索引若碰上列表重排，会指向另一张卡。
     * 表现是漏扫或重扫几张，不会错投 —— 每张卡仍要过一遍已投判定与冷却。
     * 要彻底解决得让断点记岗位 id 并在列表里回找，那是另一件事。
     */
    JobDetail fetchDetail(int cardIndex);

    /**
     * 投递：打开详情页 → 进会话 → 判断是否重复沟通 → 发招呼语 → 等送达。
     * 返回 SKIPPED 表示"平台侧已是旧会话且不允许重复沟通"，这不是失败。
     *
     * <p>打开详情页必须<b>先从列表页进</b>（{@code ListFirstEntry}）：详情页请求的
     * {@code Referer} 与 {@code Sec-Fetch-Site} 由上一个文档决定，从空白页直接打开就是
     * "外部直达"。列表页地址由 {@link Models.DeliveryOptions#search()} 里的搜索组合拼出来。
     */
    DeliveryResult deliver(JobDetail job, String greeting, DeliveryOptions options, BooleanSupplier stop);

    /** 聊天列表里未读的会话 */
    List<UnreadThread> listUnreadThreads();

    /** 读某个会话的消息；按 DOM 顺序返回 */
    List<ChatMessage> readMessages(UnreadThread thread);

    /** 发送一条文本；返回是否确认送达 */
    boolean sendText(UnreadThread thread, String text);

    /**
     * 向对方发送一份简历（点工具栏「发简历」→ 若弹确认气泡则点「确定」→ 等绿色简历卡片出现）。
     *
     * <p>确认气泡是<b>可选</b>的：对方已主动索要过简历时平台不发二次确认，点了直接发。
     * 因此「气泡弹没弹」不作为判据，成功只有一种凭据 —— 简历卡片出现。
     *
     * <p>与 {@link #sendText} 分成两个方法而不是加个 flag：它们的成功判据完全不同。
     * 文字靠「己方消息条数 +1」，简历靠「出现简历卡片」—— 简历发出去不会增加
     * {@code item-myself} 消息条数，用文字的判据去等会一直超时。
     *
     * <p>按钮灰掉时返回 {@link Models.ResumeResult.Status#UNAVAILABLE}，上层应降级成文字，
     * 不要把这种情况当成发送失败去重试（灰按钮重试多少次都是灰的）。
     */
    Models.ResumeResult sendResume(UnreadThread thread);

    /**
     * 接受工作地点：在当前会话的<b>整个消息区</b>里找工作地点确认卡片，检查「可以接受」
     * 能不能点，点一下，再读回按钮状态确认。
     *
     * <p>它不产生文字消息，也不占投递配额。返回 {@link Models.WorkLocationResult.Status#NO_CARD}
     * 表示这次没有这类卡片，调用方照常走原来的回复流程。
     *
     * <p>与 {@link #sendResume} 分开而不是加个标记：两者的成功凭据不同。发简历看「简历卡片
     * 出现」，这里看「『可以接受』按钮不再可点」，拿对方的判据来等会一直超时。
     */
    Models.WorkLocationResult acceptWorkLocation(UnreadThread thread);

    /** 关闭值守期间打开的会话页，回到列表页 */
    void closeThread();
}
