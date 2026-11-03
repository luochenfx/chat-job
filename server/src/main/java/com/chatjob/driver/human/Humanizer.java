package com.chatjob.driver.human;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ViewportSize;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * {@link Humanize} 的实现：取位置、把纯计算类算好的数字发出去、事后校验、失败降级、计数。
 *
 * <p>本类刻意做得很薄。能算的东西全在 {@link HumanAim} / {@link HumanMotion} / {@link HumanTiming} /
 * {@link HumanScrollPlan} 里，那些类零 Playwright 依赖、直接单测；这里只剩"与页面打交道"的部分。
 * 项目禁 mock 框架，而 {@code Page} 是巨型接口，自己写一个 fake 要几百行且容易写错
 * （spec 0006 Q9），所以本类不做单测，正确性靠真实页面验证。
 *
 * <p><b>降级记录的落点</b>：每一次降级先写应用日志（WARN，含动作类型与失败条件），
 * 再通过 {@link FallbackListener} 交给调用方。{@code run_log} 由编排层（{@code RunService}）
 * 注册的监听者转写——本类拿不到 {@code runId}，在这里塞一个 {@code RunStore}
 * 会让它反过来依赖编排层。
 *
 * <p><b>指针位置要自己记。</b>Playwright 不暴露当前鼠标坐标。每次都从 {@code (0,0)} 出发，
 * 轨迹的起点本身就是特征；本类记着上一次的落点，页面切换时清空（新页面里上一页的坐标没有意义）。
 */
@Slf4j
public class Humanizer implements Humanize {

    /**
     * 轨迹点之间的间隔，毫秒。没有为它单开配置项：它只影响一次点击的总时长
     * （18–34 点 × 8ms ≈ 150–270ms，与旧 {@code slow-mo-ms: 50} 给每个动作加的固定延迟同量级），
     * 不值得再占一个 yaml 字段。要调就改这里。
     */
    private static final int MOVE_POINT_MS = 8;

    /** 落点重算之后的微调只走几步：长轨迹会让"微调"变成第二次完整移动。 */
    private static final int REALIGN_STEPS = 6;

    /**
     * 富文本"意外结构变化"的容差：{@code childElementCount} 增加超过这个值就判为异常。
     *
     * <p>初始值 2，<b>在阶段 2 用真实页面标定</b>（spec 0006 §5.3 第 3 条）。太松的表现是
     * 输入内容被编辑器丢弃却不降级；太严的表现是降级率虚高。
     */
    private static final int STRUCTURE_CHANGE_TOLERANCE = 2;

    private static final String JS_READ_TEXT = "el => (typeof el.value === 'string' ? el.value : el.innerText)";

    private static final String JS_CHILD_COUNT = "el => el.childElementCount";

    private static final String JS_KIND = """
            el => ({tag: (el.tagName || '').toLowerCase(), editable: el.isContentEditable === true})
            """;

    private static final String JS_SET_INNER_TEXT = """
            (el, msg) => {
                el.innerText = msg;
                el.dispatchEvent(new Event('input', {bubbles: true}));
            }
            """;

    private static final String JS_DESCRIBE = """
            el => {
                const tag = (el.tagName || '').toLowerCase();
                const id = el.id ? '#' + el.id : '';
                const cls = typeof el.className === 'string' && el.className.trim()
                    ? '.' + el.className.trim().split(/\\s+/)[0] : '';
                return tag + id + cls;
            }
            """;

    private static final String JS_INNER_HEIGHT = "() => window.innerHeight";

    private static final String JS_INNER_WIDTH = "() => window.innerWidth";

    /**
     * 由当前文档发起跳转。{@code location.href} 与页面里点一个同站链接在请求头上等价
     * （{@code Referer} + {@code Sec-Fetch-Site: same-origin}），而 {@code page.navigate}
     * 在协议层是浏览器发起的导航，永远不带来源（实测见 {@code ListFirstEntryProbeTest}）。
     */
    private static final String JS_NAVIGATE = "url => { location.href = url; }";

    /** 视口读不到时的备用尺寸，与 {@code HumanScrollPlan} 用的是同一个取值。 */
    private static final int FALLBACK_VIEWPORT = 800;

    private final HumanizeConfig config;
    private final Rng rng;
    private final Pacing pacing;
    private final HumanCounters counters = new HumanCounters();

    /**
     * 当前注册的降级回调。不是 {@code final}：它由编排层在每次 Run 开始时替换
     * （{@link #onFallback(FallbackListener)}），而本实例比 Run 活得久。
     *
     * <p>{@code volatile} 是因为注册发生在编排线程、读取发生在 Playwright 线程。
     */
    private volatile FallbackListener fallbackListener;

    /** 同 {@link #fallbackListener}：页面自己打开的标签页也会进 {@code run_log}。 */
    private volatile NewTabListener newTabListener = NewTabListener.NONE;

    /**
     * 已预热的页面。用 {@link WeakHashMap} 按页面身份计，页面被关闭后条目自动消失，
     * 不需要在 {@code close} 路径上补一句"记得清理"——那种清理漏一次就是内存里攒着一堆死页面。
     */
    private final Map<Page, Boolean> warmedPages = Collections.synchronizedMap(new WeakHashMap<>());

    /** 上一次的鼠标位置。{@code null} 表示还没动过，第一次移动从视口内随机点出发。 */
    private HumanAim pointer;

    /**
     * 鼠标当前是否处于按下状态。只为"异常发生在按下与抬起之间"这一个场景存在
     * —— 那种情况一旦漏掉，鼠标会一直按着，之后所有移动都变成拖拽。
     */
    private boolean pressed;

    /**
     * 生产用构造：随机源、节奏、计数都按配置自建。
     *
     * <p>降级回调此时是一个空实现，由编排层在 Run 开始时用
     * {@link #onFallback(FallbackListener)} 替换。
     *
     * @param config 参数；{@code enabled=false} 时本实例退化为原生写法
     */
    public static Humanizer create(HumanizeConfig config) {
        Rng rng = Rng.of(config.seed());
        return new Humanizer(config, rng, Pacing.system(rng));
    }

    public Humanizer(HumanizeConfig config, Rng rng, Pacing pacing) {
        this(config, rng, pacing, FallbackListener.NONE);
    }

    public Humanizer(HumanizeConfig config, Rng rng, Pacing pacing, FallbackListener fallbackListener) {
        this.config = config != null ? config : HumanizeConfig.defaults();
        this.rng = rng;
        this.pacing = pacing;
        this.fallbackListener = fallbackListener != null ? fallbackListener : FallbackListener.NONE;
    }

    @Override
    public void onFallback(FallbackListener listener) {
        this.fallbackListener = listener != null ? listener : FallbackListener.NONE;
    }

    @Override
    public void onNewTab(NewTabListener listener) {
        this.newTabListener = listener != null ? listener : NewTabListener.NONE;
    }

    /**
     * 关闭一个多余标签页。
     *
     * <p>顺序是<b>先上报再关闭</b>：关掉之后 {@code page.url()} 就读不到了，而"它是什么地址"
     * 正是这条日志唯一有价值的部分——只说"多了一个标签"没法判断是点到了公司名链接、
     * 还是平台弹出的推广页。
     *
     * <p>关不掉不算失败：页面可能已经在导航中自行关闭，或浏览器正处于重建。
     */
    @Override
    public String closeExtraTab(Page tab, String note) {
        if (tab == null) {
            return "";
        }
        String url = safePageUrl(tab);
        try {
            newTabListener.onOpened(url, note == null ? "" : note);
        } catch (RuntimeException e) {
            // 监听者是编排层写的，它出错不能连累正在跑的扫描
            log.debug("上报新标签页失败: {}", e.getMessage());
        }
        warmedPages.remove(tab);
        try {
            if (!tab.isClosed()) {
                tab.close();
            }
        } catch (PlaywrightException e) {
            log.warn("关闭多余标签页失败（{}）: {}", url, e.getMessage());
        }
        return url;
    }

    private static String safePageUrl(Page page) {
        try {
            String url = page.url();
            return url == null ? "" : url;
        } catch (PlaywrightException e) {
            // 页面在创建瞬间就被关掉时，地址还没提交
            return "";
        }
    }

    @Override
    public Pacing pacing() {
        return pacing;
    }

    // ---------------------------------------------------------------- 预热

    @Override
    public void warmUp(Page page) {
        if (!config.active() || !config.warmUp().enabled()) {
            return;
        }
        if (warmedPages.putIfAbsent(page, Boolean.TRUE) != null) {
            return;
        }
        long deadline = System.currentTimeMillis() + config.warmUp().maxDurationMs();
        try {
            int width = viewportWidth(page);
            int height = viewportHeight(page);
            // 光标移入：先把指针放进页面中间偏上的位置，再做小步滚动
            movePointer(page, new HumanAim(width * (0.35 + rng.nextDouble() * 0.3),
                    height * (0.3 + rng.nextDouble() * 0.3)), 6);
            int scrollSteps = rng.uniformInt(config.warmUp().scrollSteps());
            for (int i = 0; i < scrollSteps; i++) {
                if (System.currentTimeMillis() >= deadline) {
                    return;
                }
                // 预热只滚很小的一步（几十像素）：目的是留下滚轮事件，不是把页面移走
                page.mouse().wheel(0, rng.uniformInt(HumanizeConfig.Range.of(40, 110)));
                pacing.sleepMs(rng.uniformInt(config.scroll().stepIntervalMs()) / 2);
            }
            long remaining = deadline - System.currentTimeMillis();
            int dwell = Math.min(rng.uniformInt(config.warmUp().dwellMs()), (int) Math.max(0, remaining));
            pacing.sleepMs(dwell);
        } catch (PlaywrightException e) {
            // 预热失败不影响业务：它不改变任何判据，只是让页面看起来更早被打开过
            log.debug("预热失败，忽略: {}", e.getMessage());
        }
    }

    @Override
    public void resetWarmUp(Page page) {
        warmedPages.remove(page);
        pointer = null;
    }

    // ---------------------------------------------------------------- 点击

    @Override
    public void click(Locator target, Locator.ClickOptions options) {
        Locator.ClickOptions effective = options != null ? options : new Locator.ClickOptions();
        if (!config.active()) {
            target.click(effective);
            return;
        }
        Page page = target.page();
        // 预热按页面惰性触发：这里是"这个标签页真的要被操作了"的第一个时机。
        // 不能放在 newPage() 之后——那时页面还是空的，没有内容可看（Q12）。
        warmUp(page);
        try {
            // 不在视口内就先滚进来。这是 Playwright 自己的判断，比用坐标猜可靠
            target.scrollIntoViewIfNeeded();
            HumanAim.Box current = box(target);
            if (current == null) {
                fallbackClick(target, effective, "取不到元素外框（元素不可见或已从 DOM 移除）");
                return;
            }
            int rounds = config.mouse().realignRounds();
            for (int round = 1; round <= rounds; round++) {
                HumanAim aim = HumanAim.gauss(current, config.mouse().jitter(), rng);
                movePointer(page, aim, rng.uniformInt(config.mouse().steps()));

                HumanAim.Box after = box(target);
                if (after == null) {
                    fallbackClick(target, effective, "移动后取不到元素外框");
                    return;
                }
                if (!drifted(current, after)) {
                    // 元素在移动过程中挪过（但没超出阈值）时按新外框微调落点，不重新走一遍长轨迹
                    HumanAim finalAim = aim;
                    if (movedAtAll(current, after)) {
                        finalAim = HumanAim.gauss(after, config.mouse().jitter(), rng);
                        movePointer(page, finalAim, REALIGN_STEPS);
                    }
                    press(page, finalAim);
                    counters.interaction();
                    return;
                }
                counters.realignDiscard();
                log.debug("落点作废（第 {} 轮）：纵向漂移 {}px > 阈值 {}px",
                        round, Math.round(Math.abs(after.y() - current.y())), (int) driftLimit(current));
                current = after;
            }
            fallbackClick(target, effective, "元素连续移动，" + rounds + " 轮后放弃");
        } catch (PlaywrightException e) {
            // 如果异常发生在"按下"之后、"抬起"之前，鼠标就停在按下状态。
            // Playwright 会把后续所有移动当成拖拽，于是接下来每一次点击都会失败，
            // 而失败原因看起来与这里的事毫无关系。所以先补齐抬起，再做降级。
            releaseIfPressed(page);
            fallbackClick(target, effective, "点击过程中异常: " + e.getMessage());
        }
    }

    /**
     * 按下 → 保持 → 抬起。
     *
     * <p>{@code pressed} 标记在 {@code down()} 之后立刻置位、{@code up()} 成功之后清除：
     * 中间任何一步抛异常，{@link #releaseIfPressed(Page)} 都能把鼠标恢复成抬起状态。
     */
    private void press(Page page, HumanAim aim) {
        page.mouse().down();
        pressed = true;
        pacing.sleepMs(rng.uniformInt(config.mouse().holdMs()));
        page.mouse().up();
        pressed = false;
        log.trace("按下 ({}, {})", Math.round(aim.x()), Math.round(aim.y()));
    }

    private void releaseIfPressed(Page page) {
        if (!pressed) {
            return;
        }
        pressed = false;
        try {
            page.mouse().up();
        } catch (PlaywrightException e) {
            // 页面已经不在了，请求也无从发出。这里只能放弃，但至少不再把状态留成"按着"
            log.debug("补抬起失败: {}", e.getMessage());
        }
    }

    /**
     * 鼠标是否算"漂移"。
     *
     * <p>只看纵向：两个平台的岗位列表都是纵向列表，重排、懒加载造成的位移几乎全在 Y 轴上，
     * X 轴的抖动在阈值内的概率远高于真正的重排。
     */
    private boolean drifted(HumanAim.Box before, HumanAim.Box after) {
        return Math.abs(after.y() - before.y()) > driftLimit(before);
    }

    private double driftLimit(HumanAim.Box box) {
        return Math.max(config.mouse().driftPx(), box.height() * config.mouse().driftRatio());
    }

    private boolean movedAtAll(HumanAim.Box before, HumanAim.Box after) {
        return before.x() != after.x() || before.y() != after.y()
                || before.width() != after.width() || before.height() != after.height();
    }

    private void fallbackClick(Locator target, Locator.ClickOptions options, String reason) {
        // 先记账再点：原生点击也可能失败，失败时这次降级同样发生过
        log.warn("点击降级为原生写法: {}（元素: {}）", reason, describe(target));
        counters.fallback(HumanCounters.Kind.CLICK);
        fallbackListener.onFallback(HumanCounters.Kind.CLICK, reason);
        target.click(options);
    }

    // ---------------------------------------------------------------- 滚动

    @Override
    public void scrollPage(Page page, Locator hotArea, int dy) {
        if (dy == 0) {
            return;
        }
        if (!config.active()) {
            // 关掉开关时的行为必须与改造前逐字一致：那时就是一次 JS 改滚动位置
            page.evaluate("(dy) => window.scrollBy(0, dy)", dy);
            return;
        }
        try {
            int height = viewportHeight(page);
            // 与 click 同一条惰性预热规则：滚动通常是页面上第一个发生的交互
            warmUp(page);
            hoverOnScrollArea(page, hotArea, height);
            int[] plan = HumanScrollPlan.steps(dy, height, config.scroll().stepRatio(), rng);
            for (int step : plan) {
                // wheel 的生效对象是"鼠标当前悬停的可滚动容器"，所以滚之前指针必须已经在它上面
                page.mouse().wheel(0, step);
                pacing.sleep(config.scroll().stepIntervalMs().minInt(), config.scroll().stepIntervalMs().maxInt());
            }
            counters.interaction();
        } catch (PlaywrightException e) {
            log.warn("滚动降级为 JS scrollBy: {}（滚轮不可用）", e.getMessage());
            counters.fallback(HumanCounters.Kind.SCROLL);
            fallbackListener.onFallback(HumanCounters.Kind.SCROLL, "滚轮滚动异常: " + e.getMessage());
            page.evaluate("(dy) => window.scrollBy(0, dy)", dy);
            counters.interaction();
        }
    }

    @Override
    public void scrollPageByScreens(Page page, Locator hotArea, double screens) {
        if (screens == 0) {
            return;
        }
        double pixels = screens * viewportHeight(page);
        // 正数向下取 floor、负数向上取 ceil：两个方向都是"不要多滚"。
        // 正数用 floor 是为了与改造前 Boss 的 `Math.floor(innerHeight * 1.5)` 得到同一个整数。
        int dy = (int) (screens > 0 ? Math.floor(pixels) : Math.ceil(pixels));
        scrollPage(page, hotArea, dy);
    }

    @Override
    public void scrollPageToBottom(Page page, Locator hotArea) {
        scrollPage(page, hotArea, remainingToBottom(page));
    }

    @Override
    public void scrollPageToTop(Page page, Locator hotArea) {
        scrollPage(page, hotArea, -scrollOffset(page));
    }

    /**
     * 距文档底部还有多少像素，单位为 0 表示已在底部或读不到。
     *
     * <p>取两个 {@code scrollHeight} 的较大者：哪个元素承载滚动条取决于页面 CSS，
     * 两个平台都不保证，只取其中一个会在某次改版后静默变成"滚不动"。
     */
    private int remainingToBottom(Page page) {
        Object value = safeEvaluate(page, """
                () => {
                  const doc = document.documentElement;
                  const height = Math.max(document.body ? document.body.scrollHeight : 0,
                                          doc ? doc.scrollHeight : 0);
                  return Math.max(0, Math.round(height - window.scrollY));
                }
                """);
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    /** 已从顶部滚过多少像素。 */
    private int scrollOffset(Page page) {
        Object value = safeEvaluate(page, "() => Math.round(window.scrollY)");
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    /** 把指针放进列表容器（没有容器就用视口中线）。指针不在容器上时滚轮滚的是整页。 */
    private void hoverOnScrollArea(Page page, Locator hotArea, int viewportHeight) {
        HumanAim.Box area = hotArea == null ? null : box(hotArea);
        int width = viewportWidth(page);
        HumanAim hover = area == null || area.degenerate()
                ? new HumanAim(width / 2.0, viewportHeight / 2.0)
                : HumanAim.gauss(area, config.mouse().jitter(), rng);
        // 容器比视口高时（长列表很常见），外框会向上/向下溢出视口，落点算出来可能是负数或
        // 超出视口。滚轮事件命中的是"该坐标下的元素"，坐标在视口外就等于没停在任何东西上。
        // 所以夹一次：宁可偏离中心，也要保证指针确实落在页面内。
        movePointer(page, clampToViewport(hover, width, viewportHeight),
                rng.uniformInt(config.mouse().steps()));
    }

    private static HumanAim clampToViewport(HumanAim point, int width, int height) {
        return new HumanAim(
                Math.min(Math.max(point.x(), 1), Math.max(1, width - 2)),
                Math.min(Math.max(point.y(), 1), Math.max(1, height - 2)));
    }

    // ---------------------------------------------------------------- 输入

    @Override
    public boolean type(Locator input, String text) {
        String target = text != null ? text : "";
        if (!config.active()) {
            // 关掉开关时要与改造前逐字一致，包括这一次点击：两家适配器原来的写法都是
            // 「click 建立焦点 → fill / 赋 innerText」，而 fill 只调 DOM 的 focus()，
            // 不发鼠标事件。少这一次点击，页面侧会少一对 mousedown/mouseup。
            input.click(new Locator.ClickOptions().setTimeout(5_000));
            writeNative(input, target);
            // 不读回：旧路径没有这次往返，多一次 CDP 调用就是行为差异
            return true;
        }
        String failure;
        try {
            failure = humanType(input, target);
        } catch (PlaywrightException e) {
            failure = "输入过程中异常: " + e.getMessage();
        }
        counters.interaction();
        if (failure == null) {
            return true;
        }
        log.warn("输入降级为原生写法: {}（元素: {}）", failure, describe(input));
        counters.fallback(HumanCounters.Kind.TYPE);
        fallbackListener.onFallback(HumanCounters.Kind.TYPE, failure);
        writeNative(input, target);
        return textMatches(input, target);
    }

    /**
     * 人类化输入。返回 {@code null} 表示三条判据都成立，否则返回<b>没过的那一条</b>。
     *
     * <p>三条判据（spec 0006 §2.1 第 7 条）必须同时成立：无异常 / 读回文本与目标一致 /
     * 短时间内无意外 DOM 结构变化。第二条是最基本的，第三条用来抓"字被编辑器丢弃但没报错"。
     */
    private String humanType(Locator input, String target) {
        // 焦点要真的点进去：某些编辑器在 focus() 与真实点击下的行为不同
        click(input, new Locator.ClickOptions());

        String kind = elementKind(input);
        boolean rich = "rich".equals(kind);
        if (!rich && !"native".equals(kind)) {
            return "元素既不是原生输入框也不是富文本（" + kind + "）";
        }

        int structureBefore = structureCount(input);
        clear(input);

        int[] delays = HumanTiming.delays(target.length(), config.keyboard(), rng);
        HumanTiming.Mistype mistype = HumanTiming.mistype(target, config.keyboard(), rng);
        for (int i = 0; i < target.length(); i++) {
            pacing.sleepMs(delays[i]);
            if (mistype != null && mistype.index() == i) {
                typeChar(input, mistype.wrong());
                // 打错之后也要留出"发现自己打错了"的时间，再按退格
                pacing.sleepMs(delays[i]);
                // 用键盘的 Backspace 而不是 element 上的：此时焦点在输入框里，
                // 一次真实按键才符合"人发现打错了"的动作
                input.page().keyboard().press("Backspace");
            }
            typeChar(input, target.charAt(i));
        }
        counters.typedChars(target.length());

        pacing.sleepMs(config.keyboard().verifySettleMs());
        if (!textMatches(input, target)) {
            return "读回文本与目标不一致（" + preview(readText(input)) + "）";
        }
        int growth = structureCount(input) - structureBefore;
        if (growth > STRUCTURE_CHANGE_TOLERANCE) {
            return "输入期间子元素增加 " + growth + " 个，超过容差 " + STRUCTURE_CHANGE_TOLERANCE;
        }
        return null;
    }

    /**
     * 清空已有内容。先全选再删，而不是 {@code fill("")}：{@code fill} 是赋予终态，
     * 只有 {@code input} 没有删除过程，与这一层要处理的信号是同一类问题。
     *
     * <p>已知风险：若某个编辑器把 {@code Ctrl+A} 解释成"全选整个页面"，
     * 后续输入会写到别处。阶段 2 用真实页面确认；这条判据由"读回文本不一致"兜住，
     * 不会静默送出错误的话。
     */
    private void clear(Locator input) {
        input.page().keyboard().press("Control+A");
        input.page().keyboard().press("Backspace");
    }

    /**
     * 输入一个字符。
     *
     * <p><b>中文（以及任何非 ASCII 字符）只能走 {@code insertText}</b>：Playwright 只有这条路能
     * 把任意 Unicode 字符打出来。它产生 {@code input} 事件，<b>没有</b> {@code keydown}。
     * 所以中文的节奏只能做到"字与字之间停顿"，做不到每个字都有按键事件——
     * 不要据此推论"本层已覆盖 keydown 全链路"（spec 0006 §3.4、ADR-0007 Consequences）。
     */
    private void typeChar(Locator input, char c) {
        String one = String.valueOf(c);
        if (c > 0x7E) {
            input.page().keyboard().insertText(one);
        } else {
            input.page().keyboard().type(one);
        }
    }

    /**
     * 元素种类。
     *
     * <p>一次 {@code evaluate} 同时取标签名与 {@code isContentEditable}，减少一次往返：
     * 输入框通常很小，但每次往返都是一次 CDP 调用，在输入路径上会放大成可测量的延迟。
     */
    private String elementKind(Locator input) {
        Object raw = input.evaluate(JS_KIND);
        if (!(raw instanceof Map<?, ?> map)) {
            return "unknown";
        }
        Object tag = map.get("tag");
        Object editable = map.get("editable");
        String name = tag != null ? String.valueOf(tag) : "";
        if ("input".equals(name) || "textarea".equals(name)) {
            return "native";
        }
        return Boolean.TRUE.equals(editable) ? "rich" : name;
    }

    private int structureCount(Locator input) {
        Object value = input.evaluate(JS_CHILD_COUNT);
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * 写回原生的方式：富文本赋 {@code innerText} 并派发 {@code input}，原生框用 {@code fill}。
     *
     * <p>这就是改造前两处代码各自的做法（{@code BossAdapter} 的 JS 赋值、其它输入框的
     * {@code fill}），所以它同时承担"降级写法"和"开关关掉时的旧行为"两个角色。
     */
    private void writeNative(Locator input, String text) {
        if ("rich".equals(elementKind(input))) {
            input.evaluate(JS_SET_INNER_TEXT, text);
        } else {
            input.fill(text);
        }
    }

    private boolean textMatches(Locator input, String target) {
        try {
            return normalize(readText(input)).equals(normalize(target));
        } catch (PlaywrightException e) {
            return false;
        }
    }

    private String readText(Locator input) {
        Object value = input.evaluate(JS_READ_TEXT);
        return value != null ? String.valueOf(value) : "";
    }

    /**
     * 归一化后比较：行尾统一成 {@code \n}、不换行空格变成普通空格、去首尾空白。
     *
     * <p>富文本编辑器常把用户输入的空格换成 {@code &nbsp;}、把换行换成 {@code <div>}，
     * 不归一化会让"内容其实是对的"被判成降级，降级率因此虚高（spec 0006 §5.1 风险 4）。
     */
    private String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\u00A0', ' ').trim();
    }

    private String preview(String text) {
        String flat = normalize(text).replace('\n', ' ');
        return flat.length() <= 40 ? flat : flat.substring(0, 40) + "…";
    }

    // ---------------------------------------------------------------- 导航

    @Override
    public void navigate(Page page, String url, int timeoutMs) {
        // 两家适配器的导航一律以「DOM 可用」为到达时刻，不用默认的 LOAD：
        // 列表页与详情页都挂着长连接与广告位，LOAD 可能要等到超时才返回，
        // 而后续判据（卡片数、按钮文案）本来就是自己等 DOM。关掉开关时也保留这一点，
        // 否则"关掉即回到改造前"就不成立（改造前用的就是 DOMCONTENTLOADED）。
        Page.NavigateOptions options = navOptions(timeoutMs);
        if (!config.active()) {
            page.navigate(url, options);
            return;
        }
        warmUp(page);
        // 新页面的起点与上一页的指针位置无关；继续沿用旧坐标会让第一次移动横跨整个视口
        pointer = null;
        pacing.sleep(config.pacing().betweenActionsMs().minInt(),
                config.pacing().betweenActionsMs().maxInt());
        // 导航失败不降级、不忽略：没有"原生的导航"可以退回，调用方必须自己决定是否继续
        page.navigate(url, options);
        counters.interaction();
    }

    @Override
    public void navigateInPage(Page page, String url, int timeoutMs) {
        if (!config.active()) {
            // 停用路径就是改造前的写法：一次普通的顶层导航（也因此不带来源）
            page.navigate(url, navOptions(timeoutMs));
            return;
        }
        warmUp(page);
        pointer = null;
        pacing.sleep(config.pacing().betweenActionsMs().minInt(),
                config.pacing().betweenActionsMs().maxInt());
        try {
            // waitForNavigation 包住 evaluate：先建立等待、再发起跳转，避免"跳转已经提交、
            // 再去等加载状态"那个竞态（那时旧文档已加载完，等待会立刻返回，读页面读到一半）
            page.waitForNavigation(new Page.WaitForNavigationOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(timeoutMs), () -> {
                try {
                    page.evaluate(JS_NAVIGATE, url);
                } catch (PlaywrightException e) {
                    // 跳转一提交，执行上下文就被销毁，evaluate 常以异常结束。这不是失败：
                    // 这次导航有没有发生由外面的 waitForNavigation 判定
                    log.trace("跳转已发起，evaluate 以异常结束: {}", e.getMessage());
                }
            });
        } catch (PlaywrightException e) {
            // waitForNavigation 只等"有导航提交"，不等目标地址：超时意味着跳转根本没发生，
            // 与 page.navigate 失败同级，交给调用方决定（投递路径会把它记成一次失败）
            log.warn("页面内跳转失败: {} → {}", url, e.getMessage());
            throw e;
        }
        counters.interaction();
    }

    @Override
    public boolean active() {
        return config.active();
    }

    private static Page.NavigateOptions navOptions(int timeoutMs) {
        return new Page.NavigateOptions()
                .setTimeout(timeoutMs)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED);
    }

    // ---------------------------------------------------------------- 计数

    @Override
    public String summary() {
        return counters.summary();
    }

    @Override
    public HumanCounters counters() {
        return counters;
    }

    // ---------------------------------------------------------------- 指针与视口

    /**
     * 移动指针。{@code paths} 由 {@link HumanMotion} 算好，这里只负责逐点发出并留出间隔。
     *
     * <p>没有按点缓存坐标：每次移动都从 {@link #pointer} 出发，它是本类唯一的状态，
     * 也是"下一次轨迹自然衔接"的唯一来源。
     */
    private void movePointer(Page page, HumanAim to, int steps) {
        HumanAim from = pointer != null ? pointer : randomPointInViewport(page);
        List<HumanAim> path = HumanMotion.path(from, to, steps, rng);
        for (HumanAim point : path) {
            page.mouse().move(point.x(), point.y());
            pacing.sleepMs(MOVE_POINT_MS);
        }
        pointer = to;
    }

    private HumanAim randomPointInViewport(Page page) {
        int width = viewportWidth(page);
        int height = viewportHeight(page);
        return new HumanAim(width * (0.2 + rng.nextDouble() * 0.6), height * (0.2 + rng.nextDouble() * 0.6));
    }

    /**
     * 视口尺寸。
     *
     * <p>两条来源：{@code page.viewportSize()} 与页面里的 {@code window.innerWidth/Height}。
     * 本项目用 {@code setViewportSize(null)} 让视口跟随真实窗口大小，此时
     * {@code viewportSize()} 返回 {@code null}，所以必须能读页面里的值。
     */
    private int viewportWidth(Page page) {
        ViewportSize size = page.viewportSize();
        if (size != null && size.width > 0) {
            return size.width;
        }
        Object value = safeEvaluate(page, JS_INNER_WIDTH);
        return value instanceof Number number && number.intValue() > 0 ? number.intValue() : FALLBACK_VIEWPORT;
    }

    private int viewportHeight(Page page) {
        ViewportSize size = page.viewportSize();
        if (size != null && size.height > 0) {
            return size.height;
        }
        Object value = safeEvaluate(page, JS_INNER_HEIGHT);
        return value instanceof Number number && number.intValue() > 0 ? number.intValue() : FALLBACK_VIEWPORT;
    }

    private Object safeEvaluate(Page page, String expression) {
        try {
            return page.evaluate(expression);
        } catch (PlaywrightException e) {
            // 页面正在导航时 evaluate 会失败（执行上下文已销毁）。尺寸读不到就用备用值，
            // 不值得因此让一次点击失败
            log.debug("读取页面尺寸失败，使用备用尺寸: {}", e.getMessage());
            return null;
        }
    }

    private HumanAim.Box box(Locator target) {
        try {
            BoundingBox box = target.boundingBox();
            return box == null ? null : new HumanAim.Box(box.x, box.y, box.width, box.height);
        } catch (PlaywrightException e) {
            log.debug("取元素外框失败: {}", e.getMessage());
            return null;
        }
    }

    /** 元素的可读标识，只用于日志。取不到就退回 Playwright 的默认描述，不抛异常。 */
    private String describe(Locator target) {
        try {
            Object value = target.evaluate(JS_DESCRIBE);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        } catch (PlaywrightException e) {
            log.trace("描述元素失败: {}", e.getMessage());
        }
        return String.valueOf(target);
    }
}
