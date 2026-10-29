package com.chatjob.platform.boss;

import com.chatjob.driver.BlockedException;
import com.chatjob.driver.BrowserSession;
import com.chatjob.driver.PlaywrightExecutor;
import com.chatjob.platform.Digest;
import com.chatjob.platform.Models.ChatMessage;
import com.chatjob.platform.Models.DeliveryOptions;
import com.chatjob.platform.Models.DeliveryResult;
import com.chatjob.platform.Models.JobDetail;
import com.chatjob.platform.Models.LoginState;
import com.chatjob.platform.Models.ScrollOutcome;
import com.chatjob.platform.Models.ScrollParams;
import com.chatjob.platform.Models.SearchQuery;
import com.chatjob.platform.Models.UnreadThread;
import com.chatjob.platform.PlatformAdapter;
import com.chatjob.platform.ThreadTargetUnsafeException;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.PlatformConfigEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BOSS 直聘的实现。只做平台动作，不做决策。
 *
 * <p>约定：所有 public 方法内部都经过 {@link PlaywrightExecutor} 调度；嵌套调用会就地执行，
 * 所以从 orchestrator 的一次 {@code call} 里连调多个方法不会死锁。
 */
@Slf4j
@Component
public class BossAdapter implements PlatformAdapter {

    public static final String PLATFORM = "boss";

    /** 登录态 cookie：任意一个非空即视为已登录。比 DOM 探测可靠得多 */
    private static final Set<String> LOGIN_COOKIES = Set.of("bst", "wt2", "zp_at", "geek_zp_token");

    private static final Pattern ENCRYPT_ID = Pattern.compile("/job_detail/([^./?]+)\\.html");
    private static final int DELIVER_CONFIRM_TIMEOUT_MS = 45_000;

    private final BrowserSession session;
    private final PlaywrightExecutor executor;
    private final ConfigStore configStore;
    private final BossUrlBuilder urlBuilder;
    private final ObjectMapper json;

    private Page listPage;
    private Page threadPage;

    public BossAdapter(BrowserSession session,
                       PlaywrightExecutor executor,
                       ConfigStore configStore,
                       BossUrlBuilder urlBuilder,
                       ObjectMapper json) {
        this.session = session;
        this.executor = executor;
        this.configStore = configStore;
        this.urlBuilder = urlBuilder;
        this.json = json;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    // ---------------------------------------------------------------- 登录态

    @Override
    public LoginState probeLogin() {
        return executor.call(() -> {
            // 最可靠的信号是登录态 cookie：不受页面渲染、跳转、加载速度影响
            try {
                for (com.microsoft.playwright.options.Cookie cookie
                        : session.context().cookies(BossEndpoints.ORIGIN)) {
                    if (LOGIN_COOKIES.contains(cookie.name)
                            && cookie.value != null && !cookie.value.isBlank()) {
                        return LoginState.ok();
                    }
                }
            } catch (Exception e) {
                log.debug("读取 cookie 失败: {}", e.getMessage());
            }
            // 兜底：URL 落在登录页
            Page page = listPage != null && !listPage.isClosed() ? listPage : null;
            if (page == null) {
                return LoginState.lost("尚无打开的页面，且未找到登录态 cookie");
            }
            String url = safeUrl(page);
            if (url.contains("/web/user/")) {
                return LoginState.lost("页面落在登录页: " + url);
            }
            // 兜底：DOM 上出现已登录标记（必须带超时，裸 isVisible 会无限等）
            try {
                Locator marker = page.locator(BossSelectors.LOGGED_IN_MARK).first();
                if (marker.isVisible(new Locator.IsVisibleOptions().setTimeout(5_000))) {
                    return LoginState.ok();
                }
            } catch (Exception ignore) {
                // 探针等不到就当作"没看到"，绝不能阻塞主流程
            }
            return LoginState.lost("未找到登录态 cookie，也未检测到已登录标记");
        });
    }

    @Override
    public void openLogin() {
        executor.run(() -> {
            Page page = ensureListPage();
            log.info("打开 BOSS 登录页，请在浏览器窗口中扫码完成登录: {}", BossEndpoints.LOGIN_URL);
            navigateWithRetry(page, BossEndpoints.LOGIN_URL, 45_000, 1);
        });
    }

    // ---------------------------------------------------------------- 搜索

    @Override
    public void openSearch(SearchQuery query) {
        executor.run(() -> {
            PlatformConfigEntity cfg = configStore.platformConfig(PLATFORM);
            String url = urlBuilder.build(cfg, query.city(), query.keyword());
            Page page = ensureListPage();
            log.info("打开搜索页: 城市={} 关键词={} → {}", query.city(), query.keyword(), url);
            navigateWithRetry(page, url, 45_000, 1);
            waitForList(page);
            if (isSecurityVerify(page)) {
                throw new BlockedException(BlockedException.Kind.SECURITY_CHECK,
                        "搜索页被重定向到安全验证页: " + safeUrl(page));
            }
        });
    }

    @Override
    public ScrollOutcome scrollUntilEnd(ScrollParams params, BooleanSupplier stop) {
        return executor.call(() -> {
            Page page = ensureListPage();
            int lastCount = -1;
            int stable = 0;
            int staleHits = 0;
            boolean loadedAll = false;
            int round = 0;
            int limit = Math.max(1, params.roundLimit());
            for (; round < limit; round++) {
                if (stop != null && stop.getAsBoolean()) {
                    break;
                }
                try {
                    // footer 可见不能立刻当作"加载完了"：窗口最大化时首屏很短，
                    // 第一轮 footer 就可见，会导致只拿到首屏十几个岗位就退出
                    boolean footerVisible = false;
                    try {
                        Locator footer = page.locator(BossSelectors.FOOTER);
                        if (footer.count() > 0
                                && footer.first().isVisible(new Locator.IsVisibleOptions().setTimeout(2_000))) {
                            footerVisible = true;
                        }
                    } catch (Exception ignore) {
                        // 有的版式根本没有 footer，靠"连续无新增"兜底
                    }
                    if (footerVisible && stable >= 2) {
                        log.info("已滚动到底部且连续 {} 轮无新增，判定加载完毕（第 {} 轮）", stable, round);
                        loadedAll = true;
                        break;
                    }

                    page.evaluate("() => window.scrollBy(0, Math.floor(window.innerHeight * 1.5))");
                    int current = page.locator(BossSelectors.CARD).count();
                    if (current == lastCount) {
                        stable++;
                    } else {
                        stable = 0;
                    }
                    lastCount = current;

                    if (stable >= 3) {
                        // 连续无新增时强制触底一次，再继续判
                        page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
                    }
                    if (stable >= 8) {
                        log.info("连续 {} 轮没有新增岗位，判定已加载完毕（第 {} 轮）", stable, round);
                        loadedAll = true;
                        break;
                    }
                    // 滚动本身很便宜，但节奏别太快：懒加载请求密集时会触发风控
                    pause(400, 700);
                } catch (Exception e) {
                    if (!isStaleError(e)) {
                        throw e;
                    }
                    staleHits++;
                    if (staleHits > 20) {
                        log.warn("滚动期间反复出现失效对象异常（{} 次），停止继续加载", staleHits);
                        break;
                    }
                    pause(800, 1_200);
                }
            }
            int cards = page.locator(BossSelectors.CARD).count();
            if (!loadedAll) {
                log.warn("滚动到 {} 轮上限仍未确认加载完毕，按当前已加载的 {} 个岗位继续", limit, cards);
            }
            // 回到顶部，从头开始逐张处理
            try {
                page.evaluate("() => window.scrollTo(0, 0)");
            } catch (Exception ignore) {
                // 滚动失败不影响后续
            }
            return new ScrollOutcome(loadedAll, round, cards);
        });
    }

    @Override
    public int cardCount() {
        return executor.call(() -> ensureListPage().locator(BossSelectors.CARD).count());
    }

    // ---------------------------------------------------------------- 抓详情

    @Override
    public JobDetail fetchDetail(int cardIndex) {
        return executor.call(() -> {
            Page page = ensureListPage();
            Locator cards = page.locator(BossSelectors.CARD);
            int count = cards.count();
            if (cardIndex >= count) {
                return null;
            }
            Response response = null;
            try {
                if (cardIndex == 0 && count > 1) {
                    // 第一张卡片默认展开，点它不会触发详情请求。
                    // 必须先点第二张，再点回第一张，并在"点回"时挂监听。
                    cards.nth(1).click(new Locator.ClickOptions().setTimeout(10_000));
                    pause(800, 1_200);
                    response = page.waitForResponse(BossAdapter::isDetailResponse,
                            () -> page.locator(BossSelectors.CARD).nth(0)
                                    .click(new Locator.ClickOptions().setTimeout(10_000)));
                } else {
                    response = page.waitForResponse(BossAdapter::isDetailResponse,
                            () -> page.locator(BossSelectors.CARD).nth(cardIndex)
                                    .click(new Locator.ClickOptions().setTimeout(10_000)));
                }
            } catch (Exception e) {
                log.warn("抓取第 {} 张卡片详情失败: {}", cardIndex, e.getMessage());
                return null;
            }
            if (response == null) {
                return null;
            }
            String body;
            try {
                body = response.text();
            } catch (Exception e) {
                log.warn("读取详情响应失败: {}", e.getMessage());
                return null;
            }
            return parseDetail(body, detailUrlOf(page));
        });
    }

    private static boolean isDetailResponse(Response response) {
        try {
            return response.url() != null
                    && response.url().contains(BossEndpoints.JOB_DETAIL)
                    && "GET".equalsIgnoreCase(response.request().method());
        } catch (Throwable ignore) {
            return false;
        }
    }

    private String detailUrlOf(Page page) {
        try {
            Locator more = page.locator(BossSelectors.MORE_INFO_BTN).first();
            if (more.count() == 0) {
                return null;
            }
            String href = more.getAttribute("href");
            if (href == null || href.isBlank()) {
                return null;
            }
            return href.startsWith("http") ? href : BossEndpoints.ORIGIN + href;
        } catch (Exception e) {
            return null;
        }
    }

    private JobDetail parseDetail(String body, String detailUrl) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode zp = root.path("zpData");
            JsonNode jobInfo = zp.path("jobInfo");
            JsonNode brand = zp.path("brandComInfo");
            JsonNode boss = zp.path("bossInfo");

            String encryptId = text(jobInfo, "encryptId");
            if (encryptId.isBlank()) {
                encryptId = extractEncryptId(detailUrl);
            }
            if (encryptId.isBlank()) {
                log.warn("详情里没有 encryptId，无法去重与投递，跳过该卡片");
                return null;
            }
            List<String> tags = new ArrayList<>();
            addIfPresent(tags, text(jobInfo, "locationName"));
            addIfPresent(tags, text(jobInfo, "experienceName"));
            addIfPresent(tags, text(jobInfo, "degreeName"));

            return new JobDetail(
                    encryptId,
                    text(jobInfo, "jobName"),
                    text(brand, "brandName"),
                    text(jobInfo, "salaryDesc"),
                    String.join(" · ", tags),
                    text(jobInfo, "experienceName"),
                    text(jobInfo, "degreeName"),
                    text(jobInfo, "postDescription"),
                    text(boss, "name"),
                    text(boss, "title"),
                    "", // 活跃度归一化在 evaluate 层做
                    text(boss, "activeTimeDesc"),
                    body,
                    detailUrl);
        } catch (Exception e) {
            log.warn("解析详情 JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static void addIfPresent(List<String> list, String value) {
        if (value != null && !value.isBlank()) {
            list.add(value);
        }
    }

    static String extractEncryptId(String url) {
        if (url == null) {
            return "";
        }
        Matcher matcher = ENCRYPT_ID.matcher(url);
        return matcher.find() ? matcher.group(1) : "";
    }

    // ---------------------------------------------------------------- 投递

    @Override
    public DeliveryResult deliver(JobDetail job, String greeting, DeliveryOptions options,
                                  BooleanSupplier stop) {
        return executor.call(() -> {
            String detailUrl = job.detailUrl() != null && !job.detailUrl().isBlank()
                    ? job.detailUrl()
                    : BossEndpoints.jobDetail(job.platformJobId());

            Page detailPage = session.newPage();
            try {
                detailPage.navigate(detailUrl, new Page.NavigateOptions()
                        .setTimeout(45_000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                pause(1_000, 1_600);

                String buttonText = waitForChatButton(detailPage, stop);
                if (buttonText == null) {
                    return DeliveryResult.skipped("未找到可用的沟通按钮");
                }
                boolean repeatByButton = buttonText.contains("继续沟通");

                try (BossChatNavigator navigator = new BossChatNavigator(detailPage)) {
                    BossChatNavigator.Chat chat = navigator.open(
                            detailPage.locator(BossSelectors.CHAT_BUTTON).first(),
                            repeatByButton,
                            options.allowRepeatChat(),
                            stop,
                            20_000);
                    if (chat == null) {
                        return DeliveryResult.skipped(repeatByButton ? "已沟通过的会话，按配置跳过" : "进入会话超时");
                    }
                    detailPage = chat.page();

                    // 不依赖按钮文案：进会话后先读消息列表。
                    // 已有我方消息 = 这不是首次沟通（平台自动发的默认招呼也算）。
                    int selfBefore = countSelfMessages(detailPage);
                    boolean alreadyContacted = selfBefore > 0 || repeatByButton;

                    String message;
                    if (alreadyContacted && !options.allowRepeatChat()) {
                        return DeliveryResult.skipped("已沟通过（会话内已有我方消息 " + selfBefore + " 条）");
                    } else if (alreadyContacted) {
                        List<String> pool = options.repeatMessages();
                        if (pool == null || pool.isEmpty()) {
                            return DeliveryResult.skipped("重复沟通已启用但跟进语为空");
                        }
                        message = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
                    } else {
                        message = greeting;
                    }
                    if (message == null || message.isBlank()) {
                        return DeliveryResult.skipped("招呼语为空");
                    }

                    if (!fillInput(detailPage, message)) {
                        return DeliveryResult.failed("输入框填入失败");
                    }
                    if (!clickSend(detailPage)) {
                        return DeliveryResult.failed("未找到发送按钮");
                    }
                    boolean delivered = waitForOwnMessage(detailPage, selfBefore, DELIVER_CONFIRM_TIMEOUT_MS);
                    if (!delivered) {
                        return DeliveryResult.failed("发送后 " + (DELIVER_CONFIRM_TIMEOUT_MS / 1000)
                                + "s 内聊天区未出现本人消息");
                    }
                    log.info("投递成功 | {} @ {} | 招呼语: {}", job.jobName(), job.companyName(), message);
                    return DeliveryResult.delivered(message);
                }
            } catch (BlockedException e) {
                throw e;
            } catch (Exception e) {
                log.warn("投递异常 | {} @ {}: {}", job.jobName(), job.companyName(), e.getMessage());
                return DeliveryResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                try {
                    if (detailPage != null && !detailPage.isClosed()) {
                        detailPage.close();
                    }
                } catch (Exception ignore) {
                    // 关页失败不影响投递结论
                }
            }
        });
    }

    private String waitForChatButton(Page page, BooleanSupplier stop) {
        for (int i = 0; i < 5; i++) {
            if (stop != null && stop.getAsBoolean()) {
                return null;
            }
            try {
                Locator button = page.locator(BossSelectors.CHAT_BUTTON);
                if (button.count() > 0) {
                    String text = button.first().textContent();
                    if (text != null && (text.contains("立即沟通") || text.contains("继续沟通"))) {
                        return text;
                    }
                }
            } catch (Exception e) {
                log.debug("查找沟通按钮时页面仍在渲染: {}", e.getMessage());
            }
            page.waitForTimeout(1_000);
        }
        return null;
    }

    private int countSelfMessages(Page page) {
        try {
            return page.locator(BossSelectors.MESSAGE_SELF).count();
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean fillInput(Page page, String message) {
        try {
            Locator input = page.locator(BossSelectors.CHAT_INPUT).first();
            if (!input.isVisible(new Locator.IsVisibleOptions().setTimeout(5_000))) {
                return false;
            }
            input.click(new Locator.ClickOptions().setTimeout(5_000));
            Object tag = input.evaluate("el => el.tagName.toLowerCase()");
            if ("textarea".equals(tag)) {
                input.fill(message);
            } else {
                // contenteditable 必须派发 input 事件，否则前端状态不更新、发送按钮仍不可点
                input.evaluate("(el, msg) => { el.innerText = msg; el.dispatchEvent(new Event('input')); }", message);
            }
            pause(400, 800);
            return true;
        } catch (Exception e) {
            log.warn("填入招呼语失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean clickSend(Page page) {
        try {
            Locator send = page.locator(BossSelectors.SEND_BUTTON);
            if (send.count() == 0) {
                return false;
            }
            send.first().click(new Locator.ClickOptions().setTimeout(8_000));
            return true;
        } catch (Exception e) {
            log.warn("点击发送失败: {}", e.getMessage());
            return false;
        }
    }

    /** 送达判据：聊天区本人消息条数增加。比"按钮点成功"强得多 —— 后者会大量假成功。 */
    private boolean waitForOwnMessage(Page page, int before, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (countSelfMessages(page) > before) {
                return true;
            }
            page.waitForTimeout(500);
        }
        return false;
    }

    // ---------------------------------------------------------------- 值守

    @Override
    public List<UnreadThread> listUnreadThreads() {
        return executor.call(() -> {
            Page page = ensureThreadPage();
            if (!safeUrl(page).contains("/web/geek/chat")) {
                navigateWithRetry(page, BossSelectors.CHAT_LIST_URL, 45_000, 1);
                pause(1_500, 2_500);
            }
            List<UnreadThread> out = new ArrayList<>();
            Locator items = page.locator(BossSelectors.CHAT_LIST_ITEM);
            int count = Math.min(items.count(), 30);
            for (int i = 0; i < count; i++) {
                try {
                    Locator item = items.nth(i);
                    // 必须与 openThread 比较时用同一个归一化函数（nameOf）。两边口径不同
                    // 就等于「写进去的标题永远匹配不上」—— 而它只会打 debug 日志，
                    // 表现为值守整体静默空转，是最难查的一类故障。
                    String title = nameOf(item);
                    String last = firstText(item, BossSelectors.CHAT_LIST_LAST_MSG);
                    int unread = readUnread(item);
                    if (title.isBlank()) {
                        continue;
                    }
                    out.add(UnreadThread.of(title, title, last, unread));
                } catch (Exception e) {
                    log.debug("读取第 {} 个会话项失败: {}", i, e.getMessage());
                }
            }
            return out;
        });
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

    /**
     * 读一个会话项的未读数。
     *
     * <p>返回值语义必须严格三分，混在一起就会静默失效（这正是改造前线上发生的事）：
     * <ul>
     *   <li>{@code 0} —— 确定没有未读</li>
     *   <li>{@code >0} —— 未读条数；纯红点无数字时记 1</li>
     *   <li>{@code -1} —— <b>读不到</b>：结构对不上或读取出错，上层必须当作异常而非「没未读」</li>
     * </ul>
     * 区分「确实没有」与「读不到」靠的是第 1 步的结构自检：整个条目里连
     * {@code .friend-content} 都没有，说明页面改版了，而不是这人没有新消息。
     */
    private int readUnread(Locator item) {
        try {
            if (item.locator(BossSelectors.CHAT_LIST_ITEM_BODY).count() == 0) {
                return -1;
            }
            Locator badge = item.locator(BossSelectors.CHAT_LIST_UNREAD).first();
            if (badge.count() == 0) {
                return 0;
            }
            String text = badge.textContent();
            if (text == null || text.isBlank()) {
                return 1; // .dot：纯红点没有数字
            }
            String digits = text.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 1 : Integer.parseInt(digits);
        } catch (Exception e) {
            log.debug("读取未读数失败: {}", e.getMessage());
            return -1;
        }
    }

    @Override
    public List<ChatMessage> readMessages(UnreadThread thread) {
        return executor.call(() -> {
            Page page = ensureThreadPage();
            if (!openThread(page, thread)) {
                return List.of();
            }
            List<ChatMessage> out = new ArrayList<>();
            Locator items = page.locator(BossSelectors.MESSAGE_ITEM);
            int count = items.count();
            for (int i = 0; i < count; i++) {
                try {
                    Locator item = items.nth(i);
                    String cls = item.getAttribute("class");
                    boolean fromSelf = item.locator(BossSelectors.MESSAGE_SELF).count() > 0
                            || cls != null && cls.contains("item-myself");
                    String text = firstText(item, BossSelectors.MESSAGE_TEXT);
                    boolean image = item.locator(BossSelectors.MESSAGE_IMAGE).count() > 0;
                    out.add(new ChatMessage(messageId(item, i, text), text, fromSelf, image));
                } catch (Exception e) {
                    log.debug("读取第 {} 条消息失败: {}", i, e.getMessage());
                }
            }
            return out;
        });
    }

    /**
     * 消息的去重键。优先用平台下发的 {@code data-mid}（实测每条都有、长度统一 15 位）。
     *
     * <p>旧的「序号 + 文本 hash」不能作为稳定键：新消息一到序号就整体偏移，
     * 同一句话会被算成不同消息 —— 模板重复回同一句还能忍，接了 AI 之后会变成
     * 「同一个 HR 收到一串内容相近的回复」。这里保留 fallback 只是为了平台改版时
     * 不至于整条读不出来，但每次退化都会打日志，方便发现。
     */
    private String messageId(Locator item, int index, String text) {
        try {
            String mid = item.getAttribute(BossSelectors.MESSAGE_ID_ATTR);
            if (mid != null && !mid.isBlank()) {
                return mid.trim();
            }
        } catch (Exception e) {
            log.debug("读取 data-mid 失败: {}", e.getMessage());
        }
        log.debug("消息缺少 data-mid，退化为序号+文本hash（第 {} 条）", index);
        return "fallback:" + index + ":" + text.hashCode();
    }

    @Override
    public boolean sendText(UnreadThread thread, String text) {
        return executor.call(() -> {
            Page page = ensureThreadPage();
            if (!openThread(page, thread)) {
                return false;
            }
            int before = countSelfMessages(page);
            if (!fillInput(page, text) || !clickSend(page)) {
                return false;
            }
            return waitForOwnMessage(page, before, 30_000);
        });
    }

    /**
     * 打开会话。定位规则见 spec 0002 §3.4 —— 这里是「把回复发给错误的人」的唯一防线。
     *
     * <p>三件事按顺序做：
     * <ol>
     *   <li><b>精确相等</b>：旧的 {@code setHasText} 是包含匹配，HR「张三」会命中「张三丰」。</li>
     *   <li><b>多命中即放弃</b>：同名两个 HR 时没有稳定会话 id 可区分，一律不点。</li>
     *   <li><b>摘要二次校验</b>（仅重试场景）：标题对上但最后一句话已经变了，
     *       说明会话被新消息顶掉或换了人，同样不点。</li>
     * </ol>
     * 后两条抛 {@link ThreadTargetUnsafeException}：上层必须转成 GAVE_UP，而不是重试三次
     * 后「大概就对了」。
     *
     * @return 是否打开了会话；{@code false} 只表示「列表里没找到或点不开」，可重试
     */
    private boolean openThread(Page page, UnreadThread thread) {
        try {
            // 先用平台侧过滤把候选缩到个位数，再逐个做精确比对：直接遍历整列表
            // 每个条目要读两次文本，35 条会话就是 70 次跨进程调用，没必要。
            Locator candidates = page.locator(BossSelectors.CHAT_LIST_ITEM)
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
                            "列表里有 " + hits + " 个同名会话「" + thread.title() + "」");
                }
            }
            if (matched == null) {
                log.debug("聊天列表里找不到会话: {}", thread.title());
                return false;
            }
            verifyDigest(matched, thread);
            matched.click(new Locator.ClickOptions().setTimeout(8_000));
            pause(1_200, 2_000);
            return true;
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
    private String nameOf(Locator item) {
        return firstText(item, BossSelectors.CHAT_LIST_TITLE).replace('\u00A0', ' ').trim();
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
        String actual = Digest.of(firstText(item, BossSelectors.CHAT_LIST_LAST_MSG));
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
                // 并发导航时会抛 "Object doesn't exist"，但页面实际上已经到位
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
            if (current == null || !current.contains("zhipin.com")) {
                return false;
            }
            int query = url.indexOf('?');
            String path = query < 0 ? url : url.substring(0, query);
            String tail = path.substring(path.lastIndexOf('/'));
            return current.contains(tail);
        } catch (Exception e) {
            return false;
        }
    }

    private void waitForList(Page page) {
        try {
            page.waitForSelector(BossSelectors.LIST_CONTAINER,
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
        } catch (Exception e) {
            log.warn("等待岗位列表渲染超时（可能被风控或改版）: {}", e.getMessage());
        }
    }

    private boolean isSecurityVerify(Page page) {
        String url = safeUrl(page);
        if (url.contains("/web/user/") || url.contains("safe/verify") || url.contains("security-check")) {
            return true;
        }
        try {
            return page.locator(BossSelectors.SECURITY_VERIFY_MARK).count() > 0;
        } catch (Exception e) {
            return false;
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
}
