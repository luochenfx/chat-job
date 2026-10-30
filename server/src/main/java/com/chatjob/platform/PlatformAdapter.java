package com.chatjob.platform;

import java.util.List;
import java.util.function.BooleanSupplier;

import com.chatjob.platform.Models.ChatMessage;
import com.chatjob.platform.Models.DeliveryOptions;
import com.chatjob.platform.Models.DeliveryResult;
import com.chatjob.platform.Models.JobDetail;
import com.chatjob.platform.Models.LoginState;
import com.chatjob.platform.Models.ScrollOutcome;
import com.chatjob.platform.Models.ScrollParams;
import com.chatjob.platform.Models.SearchQuery;
import com.chatjob.platform.Models.UnreadThread;

/**
 * 平台适配器：只负责"平台动作"，不做任何决策。
 *
 * <p>分工是刻意的 —— 该不该投、投什么文案由 orchestrator / evaluate 决定，
 * adapter 只回答"点了这个按钮之后平台发生了什么"。这样换成智联时，
 * 变的只有这个实现类，决策逻辑一行都不用动。
 *
 * <p>所有方法都必须在 Playwright 专用线程上调用（由调用方通过 PlaywrightExecutor 调度）。
 */
public interface PlatformAdapter {

    String platform();

    /** 结构性信号优先：登录态 cookie 存在性；DOM 探测只作兜底 */
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

    /** 反复滚动直到判据确认加载完 */
    ScrollOutcome scrollUntilEnd(ScrollParams params, BooleanSupplier stop);

    int cardCount();

    /**
     * 点第 index 张卡片并抓详情接口。返回 null 表示这张卡没抓到（已改版 / 请求超时）。
     * 抓不到时由调用方决定是跳过还是熔断。
     */
    JobDetail fetchDetail(int cardIndex);

    /**
     * 投递：打开详情页 → 进会话 → 判断是否重复沟通 → 发招呼语 → 等送达。
     * 返回 SKIPPED 表示"平台侧已是旧会话且不允许重复沟通"，这不是失败。
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

    /** 关闭值守期间打开的会话页，回到列表页 */
    void closeThread();
}
