package com.chatjob.platform;

import com.chatjob.driver.human.Humanize;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

/**
 * 打开详情页的第二条路：先到列表页，再从列表页进入详情页。
 *
 * <p><b>要解决的是什么。</b>从 {@code session.newPage()} 的空白页直接打开详情 URL 时，
 * 请求头里没有 {@code Referer}、{@code Sec-Fetch-Site: none}、历史长度为 1。
 * 这三项在请求与导航元数据里，鼠标轨迹改不了。
 *
 * <p><b>为什么"两跳"必须是"当前文档发起"的第二跳。</b>这一点最初的判断是错的：
 * 曾经以为只要上一个文档是同站页面，下一次 {@code page.navigate} 就会带上来源。
 * 实测（{@code ListFirstEntryProbeTest}，同一份本地站点）不是这样——
 * {@code page.navigate} 在协议层是浏览器发起的导航，没有发起方文档，所以
 * <b>上一页是什么都不影响结果</b>，两跳之后仍然是 {@code Referer} 空、{@code none}。
 * 真正决定来源的是"这一次导航由谁发起"：
 *
 * <table border="1">
 *   <caption>同一详情地址、四种进入方式的请求头（实测）</caption>
 *   <tr><th>进入方式</th><th>Referer</th><th>Sec-Fetch-Site</th></tr>
 *   <tr><td>空白页 {@code page.navigate}</td><td>空</td><td>none</td></tr>
 *   <tr><td>列表页后 {@code page.navigate}</td><td>空</td><td>none</td></tr>
 *   <tr><td>{@code page.navigate} + 显式 referer 参数</td><td>列表页</td><td>none</td></tr>
 *   <tr><td>列表页里由文档发起（点链接 / {@code location.href}）</td><td>列表页</td><td>same-origin</td></tr>
 * </table>
 *
 * 所以第二跳用 {@link Humanize#navigateInPage}（由当前文档发起），第一跳照常用
 * {@link Humanize#navigate}——列表页自己没有来源可言，这一跳快一点更重要。
 *
 * <p><b>为什么放在平台层而不是 {@link Humanize}。</b>"列表页"是平台概念：URL 怎么拼、
 * 卡片长什么样只有适配器知道。通用层知道了就会长成
 * {@code humanNavigateToDeepPage()} 那种名字（ADR-0007 Decision 6 否决过）。
 * 这里只保留<b>两跳的顺序与失败处置</b>这一条共同策略，平台知识由调用方传进来。
 *
 * <p><b>第 1 跳失败不阻断投递。</b>列表页没有出现卡片（风控、改版、网络慢）时仍然进详情页，
 * 只记一条 WARN：投递成功率优先于来源一致性（spec 0006 §3.5）。来源修正只是"更好"，
 * 不是投递的前置条件。
 */
@Slf4j
public final class ListFirstEntry {

    /**
     * 打开结果。
     *
     * @param fromList 第 1 跳是否成功（列表页出现了至少一张卡片）。{@code false} 表示详情页的来源仍不是站内
     * @param cards    第 1 跳结束时列表页上的卡片数，{@code fromList=false} 时为 0
     * @param note     失败原因或"没有列表地址"这类说明，成功时为空串
     */
    public record Outcome(boolean fromList, int cards, String note) {
    }

    private ListFirstEntry() {
    }

    /**
     * 两跳打开 {@code detailUrl}。方法返回时页面停在详情页上。
     *
     * @param listUrl      搜索列表页地址；{@code null} 或空表示没有可用的搜索条件，退化为直接打开详情页
     * @param cardSelector 列表页卡片选择器，用来判断"列表真的渲染出来了"
     * @param cardWaitMs   等卡片出现的上限。超时不报错，只降级为"来源不是站内"
     * @param timeoutMs    每一跳的导航超时
     */
    public static Outcome open(Humanize human, Page page, String listUrl, String cardSelector,
                               String detailUrl, int cardWaitMs, int timeoutMs) {
        // 开关关掉时整段跳过：多出来的一次列表页加载本身就是改造前没有的行为
        if (!human.active()) {
            human.navigate(page, detailUrl, timeoutMs);
            return new Outcome(false, 0, "人类化交互已停用，按改造前的方式直接打开详情页");
        }
        if (listUrl == null || listUrl.isBlank()) {
            human.navigate(page, detailUrl, timeoutMs);
            return new Outcome(false, 0, "没有可用的搜索条件，详情页从空白页直接打开");
        }

        int cards = 0;
        String note = "";
        try {
            human.navigate(page, listUrl, timeoutMs);
            cards = waitForCards(page, cardSelector, cardWaitMs);
        } catch (Exception e) {
            // 导航本身失败（超时、被重定向到验证页）与"列表没渲染"在处理上一样：
            // 都是"来源修正这一跳没成"，都不能挡住投递
            note = "列表页没能打开: " + e.getMessage();
            log.warn("投递的第 1 跳未完成，仍直接进入详情页（{}）", note);
        }
        if (note.isEmpty() && cards == 0) {
            note = "列表页 " + cardWaitMs + "ms 内没有出现岗位卡片";
            log.warn("投递的第 1 跳未完成，仍直接进入详情页（{}）", note);
        }

        // 第二跳必须由当前文档发起，这是来源能被修正的唯一原因（类注释里的实测表）。
        // 失败重试一次：改造前智联这里就是 navigateWithRetry(..., 1)，一次重试的成本远低于
        // 丢掉一次投递；Boss 原来没有重试，两跳之后统一成一次，行为差异只在"更容易成功"这一侧。
        try {
            human.navigateInPage(page, detailUrl, timeoutMs);
        } catch (RuntimeException e) {
            log.warn("详情页第一次打开失败，重试一次: {}", e.getMessage());
            human.navigateInPage(page, detailUrl, timeoutMs);
        }
        if (note.isEmpty()) {
            log.info("详情页来源已修正为站内：列表页 {} 张卡片 → 详情页", cards);
        }
        return new Outcome(note.isEmpty(), cards, note);
    }

    /**
     * 等卡片出现，返回等到时的数量；等不到返回 0。
     *
     * <p>用 {@code waitForSelector} 而不是轮询 {@code count()}：等的是"至少一张"，
     * 这正是选择器等待的语义，轮询还要自己决定间隔与次数。
     */
    private static int waitForCards(Page page, String cardSelector, int cardWaitMs) {
        if (cardSelector == null || cardSelector.isBlank() || cardWaitMs <= 0) {
            return 0;
        }
        try {
            page.waitForSelector(cardSelector, new Page.WaitForSelectorOptions().setTimeout(cardWaitMs));
        } catch (Exception e) {
            log.debug("等列表卡片超时: {}", e.getMessage());
            return 0;
        }
        try {
            return page.locator(cardSelector).count();
        } catch (Exception e) {
            return 0;
        }
    }
}
