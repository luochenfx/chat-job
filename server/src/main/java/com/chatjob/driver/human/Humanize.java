package com.chatjob.driver.human;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/**
 * 业务侧唯一的页面交互面。适配器只说"点这个元素""输入这段文字""滚这么多像素"，
 * 鼠标怎么走、字与字隔多久、滚轮每次滚多少由本层决定。
 *
 * <p>本层回答"怎么操作"，不回答"操作什么"。扫描、判定、投递、值守的分支逻辑不在这一层，
 * 也不应该因为这一层而变化。
 *
 * <p><b>调用线程</b>：所有方法只能在<b>该平台自己那条</b> Playwright 线程上调用
 * （{@code PlaywrightExecutor}），与 {@code PlatformAdapter} 的 12 个方法同一约束。
 * 本层持有的随机源与指针坐标都不是线程安全的。
 *
 * <p><b>字段命名约定</b>：适配器里保存本实例的字段名固定为 {@code human}（少数地方用
 * {@code humanize}）。源码守卫
 * （{@code HumanizeSourceGuardTest}）按这两个名字区分"经本层的调用"与"裸的 Playwright 调用"，
 * 改名会让守卫把每一次合法调用都报成违规——那不是守卫坏了，是约定被破坏。
 *
 * <p><b>开关契约</b>：{@code chat-job.humanize.enabled=false} 时，每个方法都必须等价于
 * 改造前的原生写法（点击 = {@code locator.click}、滚动 = {@code window.scrollBy}、
 * 富文本 = JS 赋 {@code innerText}）。这是"关掉就等于没改"能被验证的唯一方式
 * （spec 0006 §2.1 第 12 条）；如果关掉后行为仍有差异，那个开关就是假的。
 *
 * <p><b>失败方式</b>：本层不抛业务异常。做不成预期效果就降级为原生写法并计数，
 * 因为投递成功率优先于交互的真实性（ADR-0007 Decision 2）。
 */
public interface Humanize {

    /**
     * 预热：一个标签页被真正操作之前，先留下"被看过"的痕迹（光标移入、滚动几小步、停留）。
     *
     * <p>按 {@link Page} 计，每个页面只做一次，重复调用无副作用。页面被关闭后
     * 不需要手动清理——标记随页面一起失效。
     *
     * <p><b>{@link #click} 与 {@link #scrollPage} 会在首次使用某个页面时自动调一次</b>，
     * 所以适配器通常不必显式调用它；只有"把页面打开就换页"这类不经过交互的路径
     * （阶段 3 的 {@code navigate} 两跳）才需要手动调。放在这里而不是页面创建之后，
     * 是因为刚 {@code newPage()} 出来的页面还是空白，没有内容可看。
     */
    void warmUp(Page page);

    /**
     * 点击。{@code options} 原样传给降级路径，保证降级时行为与直接调
     * {@code locator.click(options)} 一致。
     *
     * <p>保留 Playwright 的可操作性检查（可见、稳定、可接收事件），只替换"鼠标落到元素"
     * 这一段。不自算坐标、不做遮挡判断：那几件事 Playwright 已经做对，而列表会重排，
     * 自算的坐标在"算好"到"按下"之间就可能失效（ADR-0007 Considered Options）。
     */
    void click(Locator target, Locator.ClickOptions options);

    /**
     * 输入文字。
     *
     * @return 终态是否与目标文本一致。<b>降级之后也会再读一次</b>，所以 {@code false}
     *         表示"人类化与降级都没能写对"，调用方应把它当作一次失败处理，
     *         而不是再重试本方法——重试解决不了页面本身的问题。
     */
    boolean type(Locator input, String text);

    /**
     * 在 {@code hotArea} 上滚动 {@code dy} 像素（正数向下）。
     *
     * <p>为什么要把列表 {@code Locator} 传进来：滚轮的生效对象是"鼠标当前悬停的可滚动容器"，
     * 通用层无法知道页面里哪个是列表。让平台知识渗一点进签名，比让通用层去猜容器强
     * （spec 0006 §3.1）。{@code hotArea} 传 {@code null} 表示"用视口中线"，仅用于没有明确
     * 容器的整页滚动。
     */
    void scrollPage(Page page, Locator hotArea, int dy);

    /**
     * 在 {@code hotArea} 上滚动 {@code screens} 个视口高（正数向下）。
     *
     * <p>存在的理由：两家适配器的滚动判据都是按"屏"描述的（Boss 一轮 1.5 屏、智联一轮滚到底），
     * 而视口高只有本层读得准（{@code page.viewportSize()} 在本项目恒为 {@code null}，
     * 要退到页面里的 {@code window.innerHeight}）。把这个换算留在适配器里，
     * 等于让两个适配器各写一份"读视口高 + 处理读不到"的代码——那正是本层要消除的重复。
     */
    void scrollPageByScreens(Page page, Locator hotArea, double screens);

    /**
     * 滚到文档底部。等价于改造前的 {@code window.scrollTo(0, document.body.scrollHeight)}，
     * 但走滚轮。
     *
     * <p>为什么要单独两个方法而不是让适配器自己算 {@code scrollHeight - scrollY}：
     * 这个减法在两家适配器里都要写一遍，而 {@code document.body} 与
     * {@code documentElement} 哪个承载滚动条取决于页面 CSS，两个平台都不保证。
     * 判断写一次就够，写两次必然有一天只改一处。
     */
    void scrollPageToBottom(Page page, Locator hotArea);

    /** 滚回文档顶部。 */
    void scrollPageToTop(Page page, Locator hotArea);

    /** 导航。带节奏：先预热，再按节奏等一小段，然后才发请求。 */
    void navigate(Page page, String url, int timeoutMs);

    /**
     * 清掉某个页面的预热标记。
     *
     * <p>页面被复用（同一个 {@link Page} 导航到新地址）时需要调用：预热的意义是
     * "这个标签页刚被打开"，复用它去下一个地址时那个前提已经不成立。
     */
    void resetWarmUp(Page page);

    /** 计数汇总，格式见 {@link HumanCounters#format}。供 Run 结束时写一条 {@code run_log}。 */
    String summary();

    /**
     * 计数本体。Run 首尾各取一次 {@link HumanCounters#snapshot()}，相减得到本次 Run 的增量
     * （spec 0006 §3.7：实例比 Run 活得久，所以用采样差而不是重置）。
     */
    HumanCounters counters();

    /**
     * 节奏的唯一出口。适配器里所有"等一会儿"（原来的 {@code pause(min, max)}）都从这里走，
     * <b>区间数值原样保留</b>——这一轮改的是"谁提供节奏"，不是重新标定参数（spec 0006 Q13）。
     *
     * <p>为什么把它暴露出来，而不是给 {@code Humanize} 再加一个 {@code pause(a, b)}：
     * 睡眠是独立的一个概念（它还要能被测试替换成记录器），套一层同义方法只会让人多跳一次。
     */
    Pacing pacing();

    /**
     * 注册降级回调；传 {@code null} 表示不再记录。
     *
     * <p>本层的实例是<b>平台级</b>的，而 {@code run_log} 需要 {@code runId}，所以由编排层
     * 在 Run 开始时注册、结束时撤销。不是构造参数，因为 Run 会一个接一个地跑，
     * 而实例只有一份。
     */
    void onFallback(FallbackListener listener);
}
