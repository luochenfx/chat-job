package com.chatjob.platform.page;

import com.chatjob.driver.human.Humanize;
import com.chatjob.platform.Digest;
import com.chatjob.platform.Models.UnreadThread;
import com.chatjob.platform.ThreadTargetUnsafeException;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 两个适配器共用的页面动作：导航重试、会话定位、读未读、填输入框、关标签。
 *
 * <p><b>为什么有这个类。</b>Boss 与智联的页面形状完全不同，但「怎么等它渲染完、导航抛异常时怎么
 * 判断页面其实已经到位、同名会话怎么不被点错、未读数怎么区分『没有』与『读不到』」这些判据
 * 与页面形状无关。改造前它们在两个适配器里各有一份手抄副本，改一次重试策略要改两处，
 * 只改一处不会被任何测试发现 —— 两个适配器本体在这之前零测试引用。
 *
 * <p><b>切分口径。</b>选择器与少量取值（等待时长、日志里的主语）留在 {@link Profile} 里，
 * 由适配器提供；<b>行为收进本类</b>。所以「智联的会话项叫 {@code .im-session-item}」是适配器的知识，
 * 「同名两个就放弃」是这里的规定。
 *
 * <p><b>点击不是本类的。</b>智联的卡片里嵌着 {@code target=_blank} 链接，点完要收掉多开的标签
 * （见 {@code ZhaopinAdapter#clickThenCloseExtraTabs}），Boss 不需要。这条差异通过
 * {@link ThreadOpener} 注入，本类只负责「点哪个、什么时候点」。
 */
@Slf4j
public final class PageKit {

    /** 点开列表里的一个会话项。两个平台的收尾方式不同（智联要把多开的标签收掉），所以由适配器提供 */
    @FunctionalInterface
    public interface ThreadOpener {
        void open(Locator item, Locator.ClickOptions options, String note);
    }

    /**
     * 本平台的页面结构。
     *
     * <p>{@code listName} 不是选择器，是日志里的说法（「聊天列表」/「消息列表」）：
     * 两个平台对同一个位置的叫法不同，硬统一会让排查时对不上界面。
     */
    public record Selectors(
            String listName,
            String listContainer,
            String securityMark,
            List<String> securityUrlHints,
            String threadItem,
            String threadTitle,
            String threadPreview,
            String threadBody,
            String unreadBadge,
            /** 打开会话后的判据；空串表示不检查（Boss） */
            String threadPanel,
            String input) {
    }

    /**
     * @param host              判定「还停在本站」用，导航抛异常时据此判断页面是否已经到位
     * @param fillPauseMinMs    填入之后歇一小段，两侧的数值本来不同，一个都没改
     * @param fillPauseMaxMs
     * @param fillLogSubject    填失败时日志的主语（「填入招呼语失败」/「填入消息失败」）
     */
    public record Profile(String host, Selectors selectors,
                          int fillPauseMinMs, int fillPauseMaxMs, String fillLogSubject) {
    }

    private final Profile profile;
    private final Humanize human;
    private final ThreadOpener opener;

    public PageKit(Profile profile, Humanize human, ThreadOpener opener) {
        this.profile = profile;
        this.human = human;
        this.opener = opener;
    }

    private Selectors sel() {
        return profile.selectors();
    }

    // ---------------------------------------------------------------- 导航

    /**
     * 导航，失败重试一次。
     *
     * <p>并发导航时会抛 {@code "Object doesn't exist"}，但页面实际上已经到位 —— 所以先问
     * {@link #landedOn}，已到位就当作成功，只在真没到位时才算一次失败。重试一次仍失败就抛，
     * 不返回布尔值：调用方没有「原生的导航」可以退回，是否继续由它决定。
     */
    public void navigateWithRetry(Page page, String url, int timeoutMs, int extraRetries) {
        int attempts = 1 + Math.max(0, extraRetries);
        for (int i = 0; i < attempts; i++) {
            try {
                page.navigate(url, new Page.NavigateOptions()
                        .setTimeout(timeoutMs)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                return;
            } catch (Exception e) {
                if (landedOn(page, url)) {
                    log.debug("导航抛异常但页面已到位: {}", e.getMessage());
                    return;
                }
                if (i == attempts - 1) {
                    throw new IllegalStateException("导航失败: " + url + " — " + e.getMessage(), e);
                }
                log.warn("导航失败，重试一次: {}", e.getMessage());
                pause(1_500, 2_500);
            }
        }
    }

    /**
     * 页面是否已经落在目标地址上。
     *
     * <p>先要求还停在本站：被踢到登录页时当前地址也可能恰好包含路径尾巴，那种情况必须算没到位。
     */
    public boolean landedOn(Page page, String url) {
        try {
            String current = page.url();
            if (current == null || !current.contains(profile.host())) {
                return false;
            }
            int query = url.indexOf('?');
            String path = query < 0 ? url : url.substring(0, query);
            String tail = path.substring(path.lastIndexOf('/'));
            // tail 至少是 "/"，isBlank 只是防御 url 不含 '/' 的极端输入
            return tail.isBlank() || current.contains(tail);
        } catch (Exception e) {
            return false;
        }
    }

    /** 等岗位列表渲染出来。超时只记 WARN：风控页与改版都长这样，硬失败会让整轮白跑 */
    public void waitForList(Page page) {
        try {
            page.waitForSelector(sel().listContainer(),
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
        } catch (Exception e) {
            log.warn("等待岗位列表渲染超时（可能被风控或改版）: {}", e.getMessage());
        }
    }

    /**
     * 是否被拦在安全验证上。
     *
     * <p>两条判据：地址栏（被踢到验证子域）与页面里的验证浮层。只看 DOM 会漏掉「已经跳走但浮层
     * 还没渲染」的那一瞬，只看地址会漏掉浮层直接盖在原页面上的情况。
     */
    public boolean isSecurityVerify(Page page) {
        String url = safeUrl(page);
        for (String hint : sel().securityUrlHints()) {
            if (url.contains(hint)) {
                return true;
            }
        }
        try {
            return page.locator(sel().securityMark()).count() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static String safeUrl(Page page) {
        try {
            return page.url() == null ? "" : page.url();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 页面对象失效类的错误。
     *
     * <p>这三个消息的共同点是：<b>页面本身没坏，只是我们手里的句柄过期了</b>。调用方据此决定
     * 「重开页面再试」还是「真的失败了」。
     */
    public static boolean isStaleError(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("Object doesn't exist")
                || message.contains("Execution context was destroyed")
                || message.contains("Cannot find context");
    }

    // ---------------------------------------------------------------- 会话

    /**
     * 打开一个会话。
     *
     * <p>三条判据，前两条是为了<b>不点错人</b>：
     * <ol>
     *   <li><b>标题精确相等</b>：平台侧的 {@code hasText} 是子串匹配，「张伟」会命中「张伟明」。</li>
     *   <li><b>多命中即放弃</b>：同名两个 HR 时没有稳定会话 id 可区分，一律不点。</li>
     *   <li><b>摘要二次校验</b>（仅重试场景）：标题对上但最后一句话已经变了，说明会话被新消息顶掉
     *       或换了人，同样不点。</li>
     * </ol>
     * 后两条抛 {@link ThreadTargetUnsafeException}：上层必须转成 GAVE_UP，而不是重试三次后
     * 「大概就对了」。
     *
     * @return 是否打开了会话；{@code false} 只表示「列表里没找到或点不开」，可重试
     */
    public boolean openThread(Page page, UnreadThread thread) {
        try {
            // 先用平台侧过滤把候选缩到个位数，再逐个做精确比对：直接遍历整列表
            // 每个条目要读两次文本，35 条会话就是 70 次跨进程调用，没必要。
            Locator candidates = page.locator(sel().threadItem())
                    .filter(new Locator.FilterOptions().setHasText(thread.title()));
            int count = Math.min(candidates.count(), 10);
            Locator matched = null;
            int hits = 0;
            for (int i = 0; i < count; i++) {
                Locator item = candidates.nth(i);
                if (!thread.title().equals(nameOf(item))) {
                    continue;
                }
                matched = item;
                if (++hits > 1) {
                    throw new ThreadTargetUnsafeException(
                            ThreadTargetUnsafeException.AMBIGUOUS_TITLE,
                            sel().listName() + "里有 " + hits + " 个同名会话「" + thread.title() + "」");
                }
            }
            if (matched == null) {
                log.debug("{}里找不到会话: {}", sel().listName(), thread.title());
                return false;
            }
            verifyDigest(matched, thread);
            opener.open(matched, new Locator.ClickOptions().setTimeout(8_000),
                    "打开会话「" + thread.title() + "」");
            pause(1_200, 2_000);
            // Boss 点开就在当前标签的右侧面板，没有可判的容器；智联要确认聊天面板真的出来了
            return sel().threadPanel().isEmpty() || page.locator(sel().threadPanel()).count() > 0;
        } catch (ThreadTargetUnsafeException e) {
            throw e;
        } catch (Exception e) {
            log.debug("打开会话失败 {}: {}", thread.title(), e.getMessage());
            return false;
        }
    }

    /**
     * 会话标题文本。{@code \u00A0} 是页面里真实存在的不可见字符，{@code trim()} 删不掉它，
     * 不换掉就会让「看起来一样」的两个标题精确匹配失败 —— 宁可在这里多一行，
     * 也不要让整条值守因为一个空格静默停在「找不到会话」。
     */
    public String nameOf(Locator item) {
        return firstText(item, sel().threadTitle()).replace('\u00A0', ' ').trim();
    }

    /**
     * 重试前的二次校验：列表里的最后一句话必须还是快照里的那一句。
     *
     * <p>读不到摘要（最后一条是卡片之类）时只放行标题匹配并留一条 debug ——
     * 这种情况下没有可比的指纹，硬拦会让所有只发卡片开场白的会话永远回不出去。
     */
    private void verifyDigest(Locator item, UnreadThread thread) {
        String expected = thread.expectedDigest();
        if (expected == null || expected.isBlank()) {
            return;
        }
        String actual = Digest.of(firstText(item, sel().threadPreview()));
        if (actual == null) {
            log.debug("会话「{}」没有可校验的列表摘要，仅按标题精确匹配定位", thread.title());
            return;
        }
        if (!actual.equals(expected)) {
            throw new ThreadTargetUnsafeException(
                    ThreadTargetUnsafeException.DIGEST_MISMATCH,
                    "会话「" + thread.title() + "」的最后一条消息已变化");
        }
    }

    /**
     * 读一个会话项的未读数。
     *
     * <p>返回值语义必须严格三分，混在一起就会静默失效（这正是改造前线上发生的事）：
     * <ul>
     *   <li>{@code 0} —— 确定没有未读</li>
     *   <li>{@code >0} —— 未读条数；纯红点无数字时记 1</li>
     *   <li>{@code -1} —— <b>读不到</b>：结构对不上或读取出错，上层必须当作异常而非「没未读」</li>
     * </ul>
     * 区分「确实没有」与「读不到」靠的是第 1 步的结构自检：整个条目里连会话正文都没有，
     * 说明页面改版了，而不是这人没有新消息。
     */
    public int readUnread(Locator item) {
        try {
            if (item.locator(sel().threadBody()).count() == 0) {
                return -1;
            }
            Locator badge = item.locator(sel().unreadBadge()).first();
            if (badge.count() == 0) {
                return 0;
            }
            String text = badge.textContent();
            if (text == null || text.isBlank()) {
                return 1; // 纯红点没有数字
            }
            String digits = text.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 1 : Integer.parseInt(digits);
        } catch (Exception e) {
            log.debug("读取未读数失败: {}", e.getMessage());
            return -1;
        }
    }

    // ---------------------------------------------------------------- 输入与关闭

    /**
     * 把文本写进输入框。
     *
     * <p>焦点由 {@link Humanize#type} 内部用真实点击建立：某些编辑器在 {@code focus()} 与真实点击下
     * 行为不同。它逐字输入并校验终态，失败时才退回原生写法并记一条 WARN。
     *
     * @return 终态是否与目标文本一致。<b>降级之后也读一次</b>，所以 {@code false} 表示
     *         "两种写法都没写对"，调用方应直接判失败
     */
    public boolean fillInput(Page page, String message) {
        try {
            Locator input = page.locator(sel().input()).first();
            if (!input.isVisible(new Locator.IsVisibleOptions().setTimeout(5_000))) {
                return false;
            }
            boolean written = human.type(input, message);
            pause(profile.fillPauseMinMs(), profile.fillPauseMaxMs());
            return written;
        } catch (Exception e) {
            log.warn("{}: {}", profile.fillLogSubject(), e.getMessage());
            return false;
        }
    }

    /**
     * 关闭一个用完的页面。
     *
     * <p>原来整段是静默的（Boss 侧到现在还是）。静默的代价在 2026-11-03 暴露出来：投递页没关掉时，
     * 浏览器里会累积十几个岗位详情页标签，而 {@code run_log} 里没有任何一行能解释它们从哪来 ——
     * 唯一的线索是"任务结束后停在某个详情页"。所以关不掉要留一条 WARN，关得掉就什么都不写。
     */
    public static void closeQuietly(Page page) {
        if (page == null) {
            return;
        }
        try {
            if (!page.isClosed()) {
                page.close();
            }
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("has been closed") || message.contains("Target page, context or browser")) {
                // 页面已经在导航或浏览器重建中自行关闭，不是残留
                log.debug("页面已自行关闭，无需处理: {}", message);
                return;
            }
            log.warn("关闭页面失败，这个标签可能仍留在浏览器里: {}", message);
        }
    }

    // ---------------------------------------------------------------- 小工具

    /** 取第一个匹配节点的文本；读不到返回空串，不抛 */
    public String firstText(Locator root, String selector) {
        try {
            Locator node = root.locator(selector).first();
            if (node.count() == 0) {
                return "";
            }
            String text = node.textContent();
            return text == null ? "" : text.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 读属性；读不到返回空串，不抛 */
    public String attr(Locator locator, String name) {
        try {
            String value = locator.getAttribute(name);
            return value == null ? "" : value.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 等一会儿。区间语义与改造前的 {@code pause(min, max)} 一致，<b>数值一个都没改</b>。
     *
     * <p>真正的随机源在 {@link Humanize#pacing()} 里，与鼠标、滚动共用一条序列，
     * 种子非 0 时整轮可复现。
     */
    public void pause(int minMs, int maxMs) {
        human.pacing().sleep(minMs, maxMs);
    }
}
