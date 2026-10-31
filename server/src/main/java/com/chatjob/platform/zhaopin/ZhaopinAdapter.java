package com.chatjob.platform.zhaopin;

import com.chatjob.driver.BlockedException;
import com.chatjob.driver.BrowserSession;
import com.chatjob.driver.BrowserSessions;
import com.chatjob.driver.PlatformBrowser;
import com.chatjob.driver.PlaywrightExecutor;
import com.chatjob.platform.Digest;
import com.chatjob.platform.Models.ChatMessage;
import com.chatjob.platform.Models.DeliveryOptions;
import com.chatjob.platform.Models.DeliveryResult;
import com.chatjob.platform.Models.JobDetail;
import com.chatjob.platform.Models.LoginState;
import com.chatjob.platform.Models.ResumeResult;
import com.chatjob.platform.Models.ScrollOutcome;
import com.chatjob.platform.Models.ScrollParams;
import com.chatjob.platform.Models.SearchQuery;
import com.chatjob.platform.Models.UnreadThread;
import com.chatjob.platform.PlatformAdapter;
import com.chatjob.platform.ThreadTargetUnsafeException;
import com.chatjob.platform.UnreadScanLimit;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.PlatformConfigEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * 智联招聘的实现。只做平台动作，不做决策（与 {@link com.chatjob.platform.boss.BossAdapter} 同构）。
 *
 * <p><b>装配条件与 Boss 一致</b>：阶段 2b 起两个适配器<b>同时</b>注册，由
 * {@link com.chatjob.platform.PlatformAdapters} 按平台索引；{@code chat-job.active-platform}
 * 退化成一个「没传 platform 时的缺省值」，不再是二选一开关。
 *
 * <p>与 Boss 的三处结构性差异（都是页面事实，不是风格选择）：
 * <ol>
 *   <li><b>岗位身份来自点开后的面板，而不是卡片</b>：卡片只有一个 class，没有编号也没有链接；
 *       {@code __INITIAL_STATE__} 只覆盖首屏 20 条，无限滚动会把卡片撑到近 200 张。
 *       所以 {@code fetchDetail(i)} = 点第 i 张卡 → 读面板 → 从面板的
 *       {@code /jobdetail/<编号>.htm} 链接里抠编号。早期按"数组下标对卡片下标"实现过，
 *       实测 196 张卡片只有 20 张能对上编号，多出来的被静默跳过。</li>
 *   <li><b>投递不需要说话</b>：点「立即投递」平台自己发一份简历 + 一句固定话术，
 *       所以 {@code greeting} 参数在这里无处可用（ADR-0004 D1），成功判据是弹窗文案。</li>
 *   <li><b>值守在另一个子域</b>：{@code i.zhaopin.com/im}，所以探测登录态直接看是否被踢到
 *       passport，比读 cookie 名可靠（智联的登录 cookie 名不稳定）。</li>
 * </ol>
 */
@Slf4j
@Component
public class ZhaopinAdapter implements PlatformAdapter {

    public static final String PLATFORM = "zhaopin";

    private static final int DELIVER_CONFIRM_TIMEOUT_MS = 45_000;
    private static final int RESUME_CONFIRM_TIMEOUT_MS = 20_000;
    private static final int DEFAULT_COMMAND_TIMEOUT_MS = 45_000;

    private final BrowserSession session;
    private final PlaywrightExecutor executor;
    private final ConfigStore configStore;
    private final ZhaopinUrlBuilder urlBuilder;
    private final ObjectMapper json;

    private Page listPage;
    private Page threadPage;

    /**
     * 首屏 {@code positionList} 的原始记录，按岗位编号索引。
     *
     * <p>只当<b>字段补充/备用来源</b>用（{@code hasAppliedPosition}、以及面板没渲染出来时的字段来源），
     * 不再承担"卡片下标 → 记录"的映射职责：滚动加载出来的卡片不在这个表里。
     */
    private final Map<String, JsonNode> listRecords = new ConcurrentHashMap<>();
    /** 平台自己标的「我已投过」，投递前最便宜的一道判据（覆盖不全，详情页/面板仍是权威） */
    private final Map<String, Boolean> appliedFlags = new ConcurrentHashMap<>();

    public ZhaopinAdapter(BrowserSessions sessions,
                          ConfigStore configStore,
                          ZhaopinUrlBuilder urlBuilder,
                          ObjectMapper json) {
        // 与 BossAdapter 同一条规则：会话与调度线程按平台成对取，不分头注入
        PlatformBrowser browser = sessions.of(PLATFORM);
        this.session = browser.session();
        this.executor = browser.executor();
        this.configStore = configStore;
        this.urlBuilder = urlBuilder;
        this.json = json;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    // ---------------------------------------------------------------- 登录态

    /**
     * 打开消息中心看会不会被踢到 passport 登录页。
     *
     * <p>为什么不用 cookie 名：智联的登录态 cookie 名不是一个稳定集合（与 Boss 的
     * {@code bst/wt2} 不同），写死一个名单，改版后会变成"永远判定未登录"。
     * 而 {@code i.zhaopin.com/im} 是必须鉴权的页面，跳转与否就是平台自己给的答案。
     */
    @Override
    public LoginState probeLogin() {
        return executor.call(() -> {
            Page page = ensureThreadPage();
            try {
                navigateWithRetry(page, ZhaopinEndpoints.IM_URL, DEFAULT_COMMAND_TIMEOUT_MS, 0);
            } catch (Exception e) {
                return LoginState.lost("打不开消息页：" + e.getMessage());
            }
            String url = safeUrl(page);
            if (url.contains(ZhaopinSelectors.LOGIN_HOST_MARK) || url.contains("/login")) {
                return LoginState.lost("消息页跳到了登录页：" + url);
            }
            if (!url.contains("i.zhaopin.com")) {
                return LoginState.lost("消息页停在了意外的地址：" + url);
            }
            return LoginState.ok();
        });
    }

    @Override
    public void openLogin() {
        executor.run(() -> {
            Page page = ensureListPage();
            log.info("打开智联首页，请在浏览器窗口里扫码登录: {}", ZhaopinEndpoints.LOGIN_URL);
            navigateWithRetry(page, ZhaopinEndpoints.LOGIN_URL, DEFAULT_COMMAND_TIMEOUT_MS, 1);
            pause(1_500, 2_500);
            if (!clickLoginEntry(page)) {
                log.info("首页上没找到「登录」入口 —— 大概率已经登录过了；"
                        + "若仍是未登录状态，页面上直接扫码即可");
            }
        });
    }

    /** 点右上角「登录」。找不到说明已登录（或改版），两种情况都不该报错。 */
    private boolean clickLoginEntry(Page page) {
        try {
            Locator candidates = page.locator("a, button, span").filter(
                    new Locator.FilterOptions().setHasText(ZhaopinSelectors.LOGIN_ENTRY_TEXT));
            int count = Math.min(candidates.count(), 20);
            for (int i = 0; i < count; i++) {
                Locator item = candidates.nth(i);
                String text = item.textContent();
                if (text == null) {
                    continue;
                }
                String trimmed = text.replace('\u00A0', ' ').trim();
                if (!ZhaopinSelectors.LOGIN_ENTRY_TEXT.equals(trimmed)) {
                    continue;
                }
                item.click(new Locator.ClickOptions().setTimeout(8_000));
                pause(1_200, 2_000);
                return true;
            }
        } catch (Exception e) {
            log.debug("查找登录入口失败: {}", e.getMessage());
        }
        return false;
    }

    // ---------------------------------------------------------------- 搜索

    @Override
    public void openSearch(SearchQuery query) {
        executor.run(() -> {
            PlatformConfigEntity cfg = configStore.platformConfig(PLATFORM);
            String url = urlBuilder.build(cfg, query.city(), query.keyword());
            Page page = ensureListPage();
            log.info("打开搜索页: 城市={} 关键词={} → {}", query.city(), query.keyword(), url);
            navigateWithRetry(page, url, DEFAULT_COMMAND_TIMEOUT_MS, 1);
            waitForList(page);
            if (isSecurityVerify(page)) {
                throw new BlockedException(BlockedException.Kind.SECURITY_CHECK,
                        "搜索页被重定向到安全验证页: " + safeUrl(page));
            }
            List<JsonNode> first = readInitialRecords(page);
            listRecords.clear();
            appliedFlags.clear();
            for (JsonNode record : first) {
                String number = ZhaopinRecord.positionNumber(record);
                if (number.isBlank()) {
                    continue;
                }
                listRecords.put(number, record);
                appliedFlags.put(number, ZhaopinRecord.alreadyApplied(record));
            }
            if (first.isEmpty()) {
                log.warn("首屏数据里没解析出岗位（{}）—— 可能是改版，或该组合确实没有结果",
                        ZhaopinEndpoints.INITIAL_STATE_MARK);
            } else {
                log.info("首屏解析到 {} 个岗位，其中已投递 {} 个（这份数据只作兜底，编号与详情以右侧面板为准）",
                        first.size(), first.stream().filter(ZhaopinRecord::alreadyApplied).count());
            }
        });
    }

    private List<JsonNode> readInitialRecords(Page page) {
        Object script = page.evaluate("""
                () => {
                  const scripts = [...document.querySelectorAll('script')].map(s => s.textContent || '');
                  const hit = scripts.find(t => t.includes('__INITIAL_STATE__='));
                  return hit || '';
                }
                """);
        String text = script == null ? "" : script.toString();
        if (text.isEmpty()) {
            return List.of();
        }
        return ZhaopinRecord.parseInitialState(text, json);
    }

    /**
     * 滚动加载。判据与 Boss 不同：智联没有页码也没有「共 N 条」，只能靠
     * 「连续两轮卡片数不增」收口 —— 少一轮会在懒加载间隙误判到底。
     *
     * <p>不再监听 {@code /c/i/search/positions} 的响应：编号改从面板取之后，翻页数据
     * 对扫描毫无用处（实测那条监听一条响应都没抓到，而卡片照样长到 196 张）。
     */
    @Override
    public ScrollOutcome scrollUntilEnd(ScrollParams params, BooleanSupplier stop) {
        return executor.call(() -> {
            Page page = ensureListPage();

            int limit = Math.max(1, params.roundLimit());
            int stable = 0;
            int lastCount = page.locator(ZhaopinSelectors.CARD).count();
            boolean loadedAll = false;
            int round = 0;

            for (; round < limit; round++) {
                if (stop != null && stop.getAsBoolean()) {
                    break;
                }
                try {
                    page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
                    pause(800, 1_300);
                    int current = page.locator(ZhaopinSelectors.CARD).count();
                    if (current == lastCount) {
                        stable++;
                    } else {
                        stable = 0;
                    }
                    lastCount = current;
                    // 懒加载是「滚到底 → 请求下一批 → 追加」，只看一轮会把请求间隙当成到底
                    if (stable >= 2) {
                        loadedAll = true;
                        break;
                    }
                } catch (Exception e) {
                    if (!isStaleError(e)) {
                        throw e;
                    }
                    pause(800, 1_200);
                }
            }
            page.evaluate("() => window.scrollTo(0, 0)");
            int cards = page.locator(ZhaopinSelectors.CARD).count();
            if (!loadedAll) {
                log.warn("滚动到 {} 轮上限仍未确认加载完毕，按当前已加载的 {} 个岗位继续", limit, cards);
            }
            log.info("列表加载完成：DOM 卡片 {} 个（{} 轮，{}）",
                    cards, round, loadedAll ? "确认到底" : "撞到轮数上限");
            return new ScrollOutcome(loadedAll, round, cards);
        });
    }

    /**
     * 能处理的卡数就是 DOM 卡片数。
     *
     * <p>早期要跟"已解析到的记录数"取 min（多出来的卡片没有编号），现在编号来自点开后的面板，
     * 每一张卡片都能处理，取 min 反而会把后面的 176 个岗位挡掉。
     */
    @Override
    public int cardCount() {
        return executor.call(() -> {
            int cards = ensureListPage().locator(ZhaopinSelectors.CARD).count();
            log.debug("列表页当前有 {} 张岗位卡片", cards);
            return cards;
        });
    }

    // ---------------------------------------------------------------- 抓详情

    /**
     * 点第 index 张卡片，把右侧面板整体读成 {@link ZhaopinRecord.PanelView}。
     *
     * <p>为什么不直接打开 {@code /jobdetail/<id>.htm}：那会离开列表页，返回时列表要重新加载、
     * 滚动位置与已加载的分页全部丢失。列表页是左卡片右详情，点卡片不跳页（实测 URL 不变），
     * 读完整张还能继续下一张。
     */
    @Override
    public JobDetail fetchDetail(int cardIndex) {
        return executor.call(() -> {
            Page page = ensureListPage();
            ZhaopinRecord.PanelView panel = clickCardAndReadPanel(page, cardIndex);
            if (panel == null || panel.number().isBlank()) {
                log.warn("第 {} 张卡片的面板读不到岗位编号，跳过（无法去重，投了也记不住）", cardIndex + 1);
                return null;
            }
            JsonNode record = listRecords.get(panel.number());
            JobDetail detail = ZhaopinRecord.toJobDetail(panel, record);
            if (record == null) {
                log.debug("第 {} 张卡片（{}）不在首屏数据里 —— 滚动加载出来的岗位，字段全部来自面板",
                        cardIndex + 1, panel.number());
            }
            return detail;
        });
    }

    /**
     * 点卡片并读面板，返回一次完整快照。
     *
     * <p>关键点是<b>等面板切到这一条</b>：点下去到面板重渲染之间有几百毫秒，这段间隙里
     * 链接里还是上一张卡片的编号。如果不等，就会把上一个岗位的编号安到当前岗位头上，
     * 表现为"投了 A 却记成 B"。判据用「链接里的编号变了」，比固定 sleep 可靠。
     */
    private ZhaopinRecord.PanelView clickCardAndReadPanel(Page page, int cardIndex) {
        try {
            Locator cards = page.locator(ZhaopinSelectors.CARD);
            if (cards.count() <= cardIndex) {
                return null;
            }
            String before = currentDetailNumber(page);
            Locator card = cards.nth(cardIndex);
            card.scrollIntoViewIfNeeded();
            card.click(new Locator.ClickOptions().setTimeout(10_000));
            for (int i = 0; i < 8; i++) {
                pause(300, 500);
                ZhaopinRecord.PanelView view = readPanel(page);
                if (view == null) {
                    continue;
                }
                boolean switched = !view.number().isBlank() && !view.number().equals(before);
                // 第一张卡片可能本来就选中着，此时 before 就是它自己，不能只靠"变了"判断
                if (switched || (i >= 2 && !view.number().isBlank())) {
                    return view;
                }
            }
            return readPanel(page);
        } catch (Exception e) {
            log.debug("读取第 {} 张卡片的面板失败: {}", cardIndex + 1, e.getMessage());
            return null;
        }
    }

    private String currentDetailNumber(Page page) {
        try {
            Object href = page.locator(ZhaopinSelectors.PANEL_DETAIL_LINK).first()
                    .getAttribute("href", new Locator.GetAttributeOptions().setTimeout(2_000));
            return ZhaopinRecord.numberFromDetailUrl(href == null ? "" : href.toString());
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 把面板读成一个 JSON 快照。走一次 {@code evaluate} 而不是十几次 {@code locator}：
     * 面板内容会随点击重渲染，逐字段取容易读到"一半是新岗位、一半是旧岗位"的混合体。
     */
    private ZhaopinRecord.PanelView readPanel(Page page) {
        Map<String, String> sel = new LinkedHashMap<>();
        sel.put("link", ZhaopinSelectors.PANEL_DETAIL_LINK);
        sel.put("title", ZhaopinSelectors.PANEL_TITLE_TEXT);
        sel.put("salary", ZhaopinSelectors.PANEL_SALARY);
        sel.put("tag", ZhaopinSelectors.PANEL_TAG);
        sel.put("company", ZhaopinSelectors.PANEL_COMPANY_NAME);
        sel.put("companyMeta", ZhaopinSelectors.PANEL_COMPANY_META);
        sel.put("hrName", ZhaopinSelectors.PANEL_PUBLISHER_NAME_TEXT);
        sel.put("hrActivity", ZhaopinSelectors.PANEL_PUBLISHER_ACTIVITY);
        sel.put("hrTitle", ZhaopinSelectors.PANEL_PUBLISHER_JOB_TITLE);
        sel.put("apply", ZhaopinSelectors.PANEL_APPLY);
        sel.put("jd", ZhaopinSelectors.PANEL_JD);
        Object raw;
        try {
            raw = page.evaluate("""
                    (sel) => {
                      const text = (el) => el ? (el.innerText || '').replace(/\\s+/g, ' ').trim() : '';
                      const one = (key) => text(document.querySelector(sel[key]));
                      const link = document.querySelector(sel.link);
                      return JSON.stringify({
                        detailUrl: link ? (link.getAttribute('href') || '') : '',
                        title: one('title'),
                        salary: one('salary'),
                        tags: [...document.querySelectorAll(sel.tag)].map(e => text(e)).filter(Boolean),
                        companyName: one('company'),
                        companyMeta: one('companyMeta'),
                        hrName: one('hrName'),
                        hrActivity: one('hrActivity'),
                        hrTitle: one('hrTitle'),
                        applyText: one('apply'),
                        jd: text(document.querySelector(sel.jd))
                      });
                    }
                    """, sel);
        } catch (Exception e) {
            log.debug("读取详情面板失败: {}", e.getMessage());
            return null;
        }
        if (raw == null) {
            return null;
        }
        try {
            JsonNode node = json.readTree(raw.toString());
            String detailUrl = node.path("detailUrl").asText("");
            List<String> tags = new ArrayList<>();
            node.path("tags").forEach(t -> tags.add(t.asText("")));
            return new ZhaopinRecord.PanelView(
                    ZhaopinRecord.numberFromDetailUrl(detailUrl),
                    detailUrl,
                    node.path("title").asText(""),
                    node.path("salary").asText(""),
                    tags,
                    node.path("companyName").asText(""),
                    node.path("companyMeta").asText(""),
                    node.path("hrName").asText(""),
                    node.path("hrActivity").asText(""),
                    node.path("hrTitle").asText(""),
                    node.path("applyText").asText(""),
                    node.path("jd").asText(""),
                    node.toString());
        } catch (Exception e) {
            log.debug("解析面板快照失败: {}", e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- 投递

    /**
     * 投递 = 打开详情页 → 点「立即投递」→ 等平台的成功弹窗。
     *
     * <p>三条与 Boss 不同、且不能照抄的地方：
     * <ol>
     *   <li><b>不说话</b>：智联点一次就把简历和固定话术一起发出去了，所以 {@code greeting}
     *       不进页面，只作为"我们本打算说什么"留空（ADR-0004 D1：{@code delivery.greeting}
     *       写空串是合法的）。</li>
     *   <li><b>成功判据是弹窗文案</b>：没有「立即沟通→继续沟通」这种按钮跃迁可看
     *       （按钮点了直接弹层），也没有己方消息条数可数（简历不算 text 消息）。</li>
     *   <li><b>已投过的岗位按钮直接是「继续沟通」</b>：不点、不重投，记 SKIPPED。</li>
     * </ol>
     */
    @Override
    public DeliveryResult deliver(JobDetail job, String greeting, DeliveryOptions options,
                                  BooleanSupplier stop) {
        return executor.call(() -> {
            if (Boolean.TRUE.equals(appliedFlags.get(job.platformJobId()))) {
                return DeliveryResult.skipped("平台数据标记该岗位已投递（hasAppliedPosition）");
            }
            if (options != null && options.allowRepeatChat()) {
                log.info("智联没有「在会话里补一句话」这个动作，重复沟通配置对本平台无效");
            }
            String url = job.detailUrl() != null && !job.detailUrl().isBlank()
                    ? job.detailUrl()
                    : ZhaopinEndpoints.jobDetail(job.platformJobId());

            Page page = session.newPage();
            try {
                navigateWithRetry(page, url, DEFAULT_COMMAND_TIMEOUT_MS, 1);
                pause(1_000, 1_600);
                if (isSecurityVerify(page)) {
                    throw new BlockedException(BlockedException.Kind.SECURITY_CHECK,
                            "详情页落在安全验证页: " + safeUrl(page));
                }
                String action = waitForActionText(page, stop);
                if (action.isBlank()) {
                    return DeliveryResult.skipped("详情页没找到投递按钮（可能改版）");
                }
                for (String applied : ZhaopinSelectors.DETAIL_APPLIED_TEXTS) {
                    if (action.contains(applied)) {
                        appliedFlags.put(job.platformJobId(), true);
                        return DeliveryResult.skipped("平台显示「" + action + "」，说明已经沟通过");
                    }
                }
                if (!action.contains(ZhaopinSelectors.DETAIL_APPLY_TEXT)) {
                    return DeliveryResult.skipped("详情页按钮文案是「" + action + "」，不可投递");
                }
                Locator button = page.locator(ZhaopinSelectors.DETAIL_ACTION)
                        .locator("button")
                        .filter(new Locator.FilterOptions().setHasText(ZhaopinSelectors.DETAIL_APPLY_TEXT))
                        .first();
                if (button.count() == 0) {
                    return DeliveryResult.skipped("按钮容器里没有「立即投递」");
                }
                button.click(new Locator.ClickOptions().setTimeout(10_000));
                if (waitForDeliverModal(page, stop)) {
                    log.info("投递成功（判据: 弹窗「{}」）| {} @ {}",
                            ZhaopinSelectors.DELIVER_MODAL_OK_TEXT, job.jobName(), job.companyName());
                    appliedFlags.put(job.platformJobId(), true);
                    // 招呼语由平台自己发，我们没写任何东西 —— 返回空串，别在库里编一句没发过的话
                    return DeliveryResult.delivered("");
                }
                return DeliveryResult.failed("点了「立即投递」但 " + (DELIVER_CONFIRM_TIMEOUT_MS / 1000)
                        + "s 内没等到「" + ZhaopinSelectors.DELIVER_MODAL_OK_TEXT + "」弹窗");
            } catch (BlockedException e) {
                throw e;
            } catch (Exception e) {
                log.warn("投递异常 | {} @ {}: {}", job.jobName(), job.companyName(), e.getMessage());
                return DeliveryResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                closeQuietly(page);
            }
        });
    }

    /** 详情页投递区域当前文案；读不到返回空串（渲染中 / 改版） */
    private String waitForActionText(Page page, BooleanSupplier stop) {
        for (int i = 0; i < 6; i++) {
            if (stop != null && stop.getAsBoolean()) {
                return "";
            }
            String text = actionText(page);
            if (!text.isBlank()) {
                return text;
            }
            page.waitForTimeout(800);
        }
        return "";
    }

    private String actionText(Page page) {
        try {
            Locator action = page.locator(ZhaopinSelectors.DETAIL_ACTION).first();
            if (action.count() == 0) {
                return "";
            }
            String text = action.innerText();
            return text == null ? "" : text.replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 等成功弹窗。弹窗常驻 DOM（未投递时 {@code display:none}），所以必须判「可见」；
     * 只判存在性会让每一单都立刻"成功" —— 这是这类判据最典型的假阳性。
     */
    private boolean waitForDeliverModal(Page page, BooleanSupplier stop) {
        long deadline = System.currentTimeMillis() + DELIVER_CONFIRM_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (stop != null && stop.getAsBoolean()) {
                return false;
            }
            try {
                Locator modal = page.locator(ZhaopinSelectors.DELIVER_MODAL).first();
                if (modal.count() > 0
                        && modal.isVisible(new Locator.IsVisibleOptions().setTimeout(1_000))) {
                    String text = modal.innerText();
                    if (text != null && text.contains(ZhaopinSelectors.DELIVER_MODAL_OK_TEXT)) {
                        return true;
                    }
                }
                // 双保险：按钮变成「继续沟通」同样说明平台已落库（弹窗可能被更快关掉）
                if (actionText(page).contains(ZhaopinSelectors.DETAIL_CONTINUE_TEXT)) {
                    log.info("弹窗未捕获，但按钮已变为「继续沟通」，按平台状态判成功");
                    return true;
                }
            } catch (Exception e) {
                log.debug("读取投递弹窗失败（继续轮询）: {}", e.getMessage());
            }
            page.waitForTimeout(700);
        }
        log.warn("{}s 内没等到投递成功弹窗（页面: {}）", DELIVER_CONFIRM_TIMEOUT_MS / 1000, safeUrl(page));
        return false;
    }

    // ---------------------------------------------------------------- 值守

    @Override
    public List<UnreadThread> listUnreadThreads() {
        return executor.call(() -> {
            Page page = ensureThreadPage();
            if (!safeUrl(page).contains("i.zhaopin.com")) {
                navigateWithRetry(page, ZhaopinEndpoints.IM_URL, DEFAULT_COMMAND_TIMEOUT_MS, 0);
                pause(1_500, 2_500);
            }
            Locator items = page.locator(ZhaopinSelectors.IM_SESSION_ITEM);
            UnreadScanLimit.Plan plan = UnreadScanLimit.plan(
                    items.count(), configStore.unreadScanLimit(PLATFORM));
            if (plan.truncated()) {
                log.warn("消息列表有 {} 个会话，超过本轮扫描上限 {}，排在其后的 {} 个本轮不会被发现",
                        plan.total(), plan.limit(), plan.skipped());
            }
            List<UnreadThread> out = new ArrayList<>();
            for (int i = 0; i < plan.take(); i++) {
                try {
                    Locator item = items.nth(i);
                    String title = nameOf(item);
                    if (title.isBlank()) {
                        continue;
                    }
                    out.add(UnreadThread.of(title, title, firstText(item, ZhaopinSelectors.IM_SESSION_PREVIEW),
                            readUnread(item)));
                } catch (Exception e) {
                    log.debug("读取第 {} 个会话项失败: {}", i, e.getMessage());
                }
            }
            return out;
        });
    }

    /**
     * 未读数三态（与 Boss 同语义，混同就会静默失效）：
     * {@code 0} 确定没未读 / {@code >0} 条数 / {@code -1} 读不到。
     */
    private int readUnread(Locator item) {
        try {
            if (item.locator(ZhaopinSelectors.IM_SESSION_BODY).count() == 0) {
                return -1;
            }
            Locator badge = item.locator(ZhaopinSelectors.IM_SESSION_BADGE).first();
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

    /**
     * 读一个会话的消息。
     *
     * <p>两个实测坑：
     * <ul>
     *   <li>消息根节点<b>嵌套</b>（外层带时间、内层是气泡），直接取会把条数翻倍，
     *       "最后一条"于是永远读到同一条消息的内层副本 —— 所以只取顶层。</li>
     *   <li>卡片消息（简历索要 / 地址）没有 {@code .im-msg-text}，文本只能从整块
     *       {@code innerText} 里去掉时间与按钮字。ADR-0004 的待验证项 1 由此有了答案：
     *       <b>卡片文案读得到</b>（「我想要一份你的简历，你是否同意？」），
     *       关键词「简历」能命中，不需要额外归一化。</li>
     * </ul>
     */
    @Override
    public List<ChatMessage> readMessages(UnreadThread thread) {
        return executor.call(() -> {
            Page page = ensureThreadPage();
            if (!openThread(page, thread)) {
                return List.of();
            }
            return readMessagesFromPanel(page);
        });
    }

    private List<ChatMessage> readMessagesFromPanel(Page page) {
        Object raw = page.evaluate("""
                () => {
                  const panel = document.querySelector('.im-main-panel__chat');
                  if (!panel) return '[]';
                  const roots = [...panel.querySelectorAll('.im-message')]
                      .filter(e => !(e.parentElement && e.parentElement.closest('.im-message')));
                  const out = roots.map((e, i) => {
                    const me = !!e.querySelector('.im-message__bubble--me');
                    const parts = [...e.querySelectorAll('.im-msg-text')]
                        .map(t => (t.textContent || '').trim()).filter(Boolean);
                    let text = parts.join(' ');
                    const timeEl = e.querySelector('.im-message__time');
                    const time = timeEl ? (timeEl.textContent || '').replace(/\\s+/g, ' ').trim() : '';
                    if (!text) {
                      let whole = (e.innerText || '');
                      if (timeEl && timeEl.textContent) whole = whole.split(timeEl.textContent).join(' ');
                      text = whole.replace(/\\s+/g, ' ').trim();
                    }
                    text = text.replace(/已读\\s*$/, '')
                               .replace(/\\s*点击查看\\s*$/, '')
                               .replace(/\\s*(拒绝|同意)\\s*$/g, '')
                               .trim();
                    const image = !!e.querySelector('img');
                    return { idx: i, me: me, text: text, time: time, image: image };
                  });
                  return JSON.stringify(out);
                }
                """);
        String text = raw == null ? "[]" : raw.toString();
        List<ChatMessage> out = new ArrayList<>();
        try {
            for (JsonNode node : json.readTree(text)) {
                String body = node.path("text").asText("");
                boolean me = node.path("me").asBoolean(false);
                boolean image = node.path("image").asBoolean(false);
                if (body.isBlank() && !image) {
                    continue;
                }
                out.add(new ChatMessage(messageId(node, body), body, me, image));
            }
        } catch (Exception e) {
            log.warn("解析消息列表失败: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 消息去重键。智联的消息节点上没有稳定 id（不像 Boss 的 {@code data-mid}），
     * 所以用「时间 + 文本 hash」：同一句话在不同时间出现会被区分开，
     * 而"最新一条"这个位置在两次读取之间是不变的 —— 这正是去重真正需要的性质。
     */
    private String messageId(JsonNode node, String text) {
        String time = node.path("time").asText("");
        String base = time.isBlank() ? ("#" + node.path("idx").asInt()) : time;
        return "zp:" + base + ":" + text.hashCode();
    }

    @Override
    public boolean sendText(UnreadThread thread, String text) {
        return executor.call(() -> {
            Page page = ensureThreadPage();
            if (!openThread(page, thread)) {
                return false;
            }
            int before = countSelfMessages(page);
            if (!fillInput(page, text)) {
                return false;
            }
            Locator send = page.locator(ZhaopinSelectors.IM_SEND_BUTTON).first();
            if (send.count() == 0) {
                log.warn("找不到发送按钮");
                return false;
            }
            send.click(new Locator.ClickOptions().setTimeout(8_000));
            return waitForMoreSelfMessages(page, before, 30_000);
        });
    }

    private int countSelfMessages(Page page) {
        try {
            Object count = page.evaluate("""
                    () => {
                      const panel = document.querySelector('.im-main-panel__chat');
                      if (!panel) return 0;
                      return [...panel.querySelectorAll('.im-message')]
                          .filter(e => !(e.parentElement && e.parentElement.closest('.im-message')))
                          .filter(e => e.querySelector('.im-message__bubble--me')).length;
                    }
                    """);
            return count instanceof Number n ? n.intValue() : 0;
        } catch (Exception e) {
            log.debug("读取己方消息条数失败: {}", e.getMessage());
            return 0;
        }
    }

    private boolean waitForMoreSelfMessages(Page page, int before, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (countSelfMessages(page) > before) {
                return true;
            }
            page.waitForTimeout(500);
        }
        return false;
    }

    private boolean fillInput(Page page, String message) {
        try {
            Locator input = page.locator(ZhaopinSelectors.IM_INPUT).first();
            if (!input.isVisible(new Locator.IsVisibleOptions().setTimeout(5_000))) {
                return false;
            }
            input.click(new Locator.ClickOptions().setTimeout(5_000));
            input.fill(message);
            pause(300, 600);
            return true;
        } catch (Exception e) {
            log.warn("填入消息失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 发简历：点工具栏「发简历」→（可选）确认 → 等「附件简历已发送」。
     *
     * <p>与 Boss 的差别在于<b>判据的载体</b>：Boss 是绿色简历卡片，智联是一条
     * {@code im-message--tip} 系统提示。找错载体就会一直超时，所以这里特意不数
     * 「己方消息 +1」—— 简历不算 text 消息（与 Boss 侧踩过的坑完全同源）。
     */
    @Override
    public ResumeResult sendResume(UnreadThread thread) {
        return executor.call(() -> {
            Page page = ensureThreadPage();
            if (!openThread(page, thread)) {
                return ResumeResult.failed("会话打不开，没法发简历");
            }
            Locator button = page.locator(ZhaopinSelectors.IM_RESUME_BUTTON).first();
            if (button.count() == 0) {
                return ResumeResult.unavailable("工具栏上找不到「发简历」按钮（可能改版）");
            }
            if (isDisabled(button)) {
                String why = attr(button, "aria-label");
                return ResumeResult.unavailable(why.isBlank() ? "「发简历」按钮当前不可用" : why);
            }

            int before = countResumeTips(page);
            try {
                button.click(new Locator.ClickOptions().setTimeout(8_000));
            } catch (Exception e) {
                return ResumeResult.failed("点击「发简历」失败: " + e.getMessage());
            }
            pause(800, 1_400);
            clickResumeConfirmIfPresent(page);

            if (waitForResumeTip(page, before, RESUME_CONFIRM_TIMEOUT_MS)) {
                return ResumeResult.sent("收到「" + ZhaopinSelectors.IM_RESUME_SENT_MARK + "」提示");
            }
            return ResumeResult.failed("已点「发简历」但 " + (RESUME_CONFIRM_TIMEOUT_MS / 1000)
                    + " 秒内没等到「" + ZhaopinSelectors.IM_RESUME_SENT_MARK + "」");
        });
    }

    /** 二次确认是可选的（对方已索要时平台直接发），弹了就点、没弹就继续 —— 与 Boss 同一口径 */
    private void clickResumeConfirmIfPresent(Page page) {
        try {
            Locator panel = page.locator("[class*=modal], [class*=dialog], [class*=popover], [class*=confirm]");
            int count = Math.min(panel.count(), 10);
            for (int i = 0; i < count; i++) {
                Locator node = panel.nth(i);
                if (!node.isVisible(new Locator.IsVisibleOptions().setTimeout(300))) {
                    continue;
                }
                Locator confirm = node.locator("button")
                        .filter(new Locator.FilterOptions().setHasText("确定"))
                        .first();
                if (confirm.count() > 0 && confirm.isVisible(new Locator.IsVisibleOptions().setTimeout(300))) {
                    confirm.click(new Locator.ClickOptions().setTimeout(5_000));
                    log.info("「发简历」弹出了二次确认，已点「确定」");
                    pause(600, 1_000);
                    return;
                }
            }
        } catch (Exception e) {
            log.debug("查找发简历确认气泡失败（当作没有气泡）: {}", e.getMessage());
        }
    }

    private int countResumeTips(Page page) {
        try {
            Object count = page.evaluate("""
                    () => {
                      const panel = document.querySelector('.im-main-panel__chat');
                      if (!panel) return 0;
                      return [...panel.querySelectorAll('.im-message')]
                          .filter(e => !(e.parentElement && e.parentElement.closest('.im-message')))
                          .filter(e => (e.innerText || '').includes('附件简历已发送')).length;
                    }
                    """);
            return count instanceof Number n ? n.intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean waitForResumeTip(Page page, int before, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (countResumeTips(page) > before) {
                return true;
            }
            page.waitForTimeout(500);
        }
        return false;
    }

    private boolean isDisabled(Locator locator) {
        String cls = attr(locator, "class");
        if (cls.contains("disabled") || cls.contains("is-disabled")) {
            return true;
        }
        String aria = attr(locator, "aria-disabled");
        return "true".equalsIgnoreCase(aria);
    }

    /** 打开会话。精确定位规则与 Boss 一致：名称精确相等、多命中即放弃、重试时校验摘要。 */
    private boolean openThread(Page page, UnreadThread thread) {
        try {
            Locator candidates = page.locator(ZhaopinSelectors.IM_SESSION_ITEM)
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
                            "消息列表里有 " + hits + " 个同名会话「" + thread.title() + "」");
                }
            }
            if (matched == null) {
                log.debug("消息列表里找不到会话: {}", thread.title());
                return false;
            }
            verifyDigest(matched, thread);
            matched.click(new Locator.ClickOptions().setTimeout(8_000));
            pause(1_200, 2_000);
            return page.locator(ZhaopinSelectors.IM_CHAT_PANEL).count() > 0;
        } catch (ThreadTargetUnsafeException e) {
            throw e;
        } catch (Exception e) {
            log.debug("打开会话失败 {}: {}", thread.title(), e.getMessage());
            return false;
        }
    }

    private String nameOf(Locator item) {
        return firstText(item, ZhaopinSelectors.IM_SESSION_NAME).replace('\u00A0', ' ').trim();
    }

    private void verifyDigest(Locator item, UnreadThread thread) {
        String expected = thread.expectedDigest();
        if (expected == null || expected.isBlank()) {
            return;
        }
        String actual = Digest.of(firstText(item, ZhaopinSelectors.IM_SESSION_PREVIEW));
        if (actual == null) {
            log.debug("会话「{}」没有可校验的列表摘要，仅按名称精确匹配定位", thread.title());
            return;
        }
        if (!actual.equals(expected)) {
            throw new ThreadTargetUnsafeException(
                    ThreadTargetUnsafeException.DIGEST_MISMATCH,
                    "会话「" + thread.title() + "」的最后一条消息已变化");
        }
    }

    @Override
    public void closeThread() {
        executor.run(() -> {
            if (threadPage != null && !threadPage.isClosed()) {
                threadPage.close();
            }
            threadPage = null;
        });
    }

    // ---------------------------------------------------------------- 页面管理

    private Page ensureListPage() {
        if (listPage != null && !listPage.isClosed()) {
            return listPage;
        }
        session.ensureReady();
        listPage = session.newPage();
        return listPage;
    }

    private Page ensureThreadPage() {
        if (threadPage != null && !threadPage.isClosed()) {
            return threadPage;
        }
        session.ensureReady();
        threadPage = session.newPage();
        return threadPage;
    }

    private void navigateWithRetry(Page page, String url, int timeoutMs, int extraRetries) {
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

    private boolean landedOn(Page page, String url) {
        try {
            String current = page.url();
            if (current == null || !(current.contains("zhaopin.com"))) {
                return false;
            }
            int query = url.indexOf('?');
            String path = query < 0 ? url : url.substring(0, query);
            String tail = path.substring(path.lastIndexOf('/'));
            return tail.isBlank() || current.contains(tail);
        } catch (Exception e) {
            return false;
        }
    }

    private void waitForList(Page page) {
        try {
            page.waitForSelector(ZhaopinSelectors.CARD,
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
        } catch (Exception e) {
            log.warn("等待岗位列表渲染超时（可能被风控或改版）: {}", e.getMessage());
        }
    }

    private boolean isSecurityVerify(Page page) {
        String url = safeUrl(page);
        if (url.contains("captcha") || url.contains("verify") || url.contains("security-check")) {
            return true;
        }
        try {
            return page.locator(ZhaopinSelectors.SECURITY_VERIFY_MARK).count() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String firstText(Locator root, String selector) {
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

    private String attr(Locator locator, String name) {
        try {
            String value = locator.getAttribute(name);
            return value == null ? "" : value.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void closeQuietly(Page page) {
        try {
            if (page != null && !page.isClosed()) {
                page.close();
            }
        } catch (Exception ignore) {
            // 关页失败不影响结论
        }
    }

    private static String safeUrl(Page page) {
        try {
            return page.url() == null ? "" : page.url();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isStaleError(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("Object doesn't exist")
                || message.contains("Execution context was destroyed")
                || message.contains("Cannot find context");
    }

    private static void pause(int minMs, int maxMs) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(minMs, Math.max(minMs + 1, maxMs)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 仅供诊断：首屏数据里已知的岗位编号 */
    List<String> snapshotNumbers() {
        return listRecords.keySet().stream().sorted().toList();
    }

    /** 诊断用：编号 → 是否已投 */
    Map<String, Boolean> snapshotApplied() {
        return new LinkedHashMap<>(appliedFlags);
    }
}
