package com.chatjob.driver;

import com.chatjob.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 浏览器会话：用系统真实 Chrome 起一个持久化上下文的专用 profile。
 *
 * <p>三个刻意的取舍：
 * <ul>
 *   <li>不复用日常浏览 profile —— 自动化行为会污染日常 tab，且被封时牵连主号。</li>
 *   <li>不设 UserAgent —— 伪造 UA 只改字符串，改不了 {@code navigator.platform} 和
 *       {@code navigation.userAgentData}，反而制造"UA 说 Mac、platform 说 Win32"的矛盾。</li>
 *   <li>不加 {@code --no-sandbox} —— 它本身就是自动化特征。</li>
 * </ul>
 *
 * <p>另外 {@code --test-type} / {@code --disable-session-crashed-bubble} 这几个不是反检测，
 * 是防止"要恢复页面吗"的模态框把 CDP 挂死：那个框会让 navigate 永不返回，连超时都不触发。
 */
@Slf4j
@Component
public class BrowserSession {

    private final AppProperties props;
    private final PlaywrightExecutor executor;
    private final PatchrightDriver patchright;
    private final BrowserChannel browserChannel;

    private Playwright playwright;
    private BrowserContext context;
    /**
     * 常驻占位页。持久化上下文里"最后一个页面被关闭"就等于关闭浏览器，
     * 所以必须始终留一页，否则下一次 newPage 会直接 Target.createTarget 失败。
     * 它不承载任何业务，固定停在 about:blank。
     */
    private Page keepAlive;
    private volatile boolean closed = true;
    private volatile String unavailableReason;

    public BrowserSession(AppProperties props, PlaywrightExecutor executor, PatchrightDriver patchright,
                          BrowserChannel browserChannel) {
        this.props = props;
        this.executor = executor;
        this.patchright = patchright;
        this.browserChannel = browserChannel;
    }

    public boolean isReady() {
        return playwright != null && context != null && !closed;
    }

    public String unavailableReason() {
        return unavailableReason;
    }

    /**
     * 确保浏览器可用。已经在跑就直接返回；浏览器被手动关闭或崩过就重建。
     * 必须在 Playwright 专用线程上调用（内部会自动调度）。
     */
    public synchronized void ensureReady() {
        if (isReady()) {
            return;
        }
        executor.run(this::initOnThread);
    }

    private void initOnThread() {
        if (isReady()) {
            return;
        }
        PatchrightDriver.Status driver = patchright.inspect();
        if (!driver.available() && props.driver().failClosed()) {
            // fail-closed：宁可拒绝启动任务，也不要在"以为有防护其实没有"的状态下批量投递
            unavailableReason = driver.message();
            throw new IllegalStateException("driver 不可用，拒绝启动: " + driver.message());
        }

        discardStale();

        Map<String, String> env = patchright.createOptionsEnv();
        playwright = env.isEmpty()
                ? Playwright.create()
                : Playwright.create(new Playwright.CreateOptions().setEnv(env));

        Path userDataDir = Paths.get(props.browser().userDataDir()).toAbsolutePath();
        sanitizeProfileExitState(userDataDir);

        BrowserChannel.Choice choice = browserChannel.resolve();
        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(false)
                .setSlowMo(props.browser().slowMoMs())
                .setViewportSize(null)
                .setChromiumSandbox(true)
                .setArgs(List.of(
                        "--start-maximized",
                        "--test-type",
                        "--disable-session-crashed-bubble",
                        "--hide-crash-restore-bubble",
                        "--no-first-run",
                        "--no-default-browser-check",
                        "--no-proxy-server",
                        "--proxy-bypass-list=*",
                        "--disable-blink-features=AutomationControlled"));
        if (choice.executablePath() != null) {
            options.setExecutablePath(Paths.get(choice.executablePath()));
        } else if (choice.channel() != null) {
            options.setChannel(choice.channel());
        }
        // choice 两个字段都为空 = 落到内置 Chromium，此时不设 channel，由 Playwright 自己找

        context = playwright.chromium().launchPersistentContext(userDataDir, options);

        closed = false;
        unavailableReason = null;
        context.onClose(c -> {
            closed = true;
            log.warn("浏览器已关闭（手动关窗口或崩溃），下次操作会自动重新初始化");
        });

        // 持久化上下文自带的启动标签页不能复用：它可能永远停在加载中，而 evaluate / locator
        // 没有默认超时，会把线程无限期焊死。一律新建页、关掉旧页。
        //
        // 但顺序不能反：先关后建会让 context 出现"零个页面"的中间态，而持久化上下文里
        // 关掉最后一个页面等同于关闭浏览器 —— 之后再 newPage 只会拿到
        // Protocol error (Target.createTarget): Failed to open a new tab，
        // 启动自检因此必然失败，整个投递流程被卡在起点。
        // 所以先建常驻页，等它建好再关启动页；常驻页平时就停在 about:blank 占位。
        List<Page> startupPages = new ArrayList<>(context.pages());
        keepAlive = context.newPage();
        keepAlive.setDefaultTimeout(props.browser().defaultTimeoutMs());
        for (Page startup : startupPages) {
            try {
                startup.close();
            } catch (Exception e) {
                log.debug("关闭启动空白页失败（忽略）: {}", e.getMessage());
            }
        }
        log.info("浏览器已启动（{}，profile: {}，driver: {}）", choice.label(), userDataDir, driver.driverDir());
    }

    private void discardStale() {
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception ignore) {
            // 浏览器已经没了，关不掉很正常
        }
        playwright = null;
        context = null;
        keepAlive = null;
        closed = true;
    }

    /**
     * 新建一个标签页。调用方负责关闭。
     *
     * <p>常驻页被用户手动关掉、或浏览器进程在两次操作之间退了，第一次 newPage 会失败。
     * 这种情况不该把异常抛给业务：重建一次会话再试，比让整轮扫描报错更划算。
     */
    public Page newPage() {
        ensureReady();
        return executor.call(() -> {
            try {
                return openPage();
            } catch (PlaywrightException e) {
                if (!recoverable(e)) {
                    throw e;
                }
                log.warn("新建标签页失败（{}），重建浏览器会话后重试一次", e.getMessage());
                closed = true;
                context = null;
                playwright = null;
                initOnThread();
                return openPage();
            }
        });
    }

    private Page openPage() {
        // openPage 顺便维护常驻页：它被关掉时，这里补一个回来
        if (keepAlive == null || keepAlive.isClosed()) {
            keepAlive = context.newPage();
            keepAlive.setDefaultTimeout(props.browser().defaultTimeoutMs());
        }
        Page page = context.newPage();
        page.setDefaultTimeout(props.browser().defaultTimeoutMs());
        return page;
    }

    /** 「打开新标签失败」「浏览器已经关了」都算可恢复：重建会话即可，其余异常照旧抛 */
    private static boolean recoverable(PlaywrightException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("Failed to open a new tab")
                || message.contains("Target page, context or browser has been closed")
                || message.contains("browser has been closed")
                || message.contains("Target.createTarget");
    }

    public BrowserContext context() {
        ensureReady();
        return context;
    }

    public Playwright playwright() {
        ensureReady();
        return playwright;
    }

    /**
     * 应用被强制停止（IDE 停止按钮 / kill / 崩溃）后，Chrome 下次启动会弹"要恢复页面吗？"。
     * 命令行标志偶尔不生效，所以这里再把 Preferences 里的退出状态直接改掉。
     */
    private void sanitizeProfileExitState(Path userDataDir) {
        Path preferences = userDataDir.resolve("Default").resolve("Preferences");
        if (!Files.isRegularFile(preferences)) {
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(preferences.toFile());
            if (!(root instanceof ObjectNode rootNode)) {
                return;
            }
            if (!(rootNode.get("profile") instanceof ObjectNode profileNode)) {
                return;
            }
            String exitType = profileNode.path("exit_type").asText("");
            boolean exitedCleanly = profileNode.path("exited_cleanly").asBoolean(true);
            if ("Normal".equals(exitType) && exitedCleanly) {
                return;
            }
            profileNode.put("exit_type", "Normal");
            profileNode.put("exited_cleanly", true);
            mapper.writeValue(preferences.toFile(), rootNode);
            log.info("已清除 profile 崩溃退出标记（原 exit_type={}）", exitType);
        } catch (Exception e) {
            log.debug("清理 profile 退出状态失败（不影响启动）: {}", e.getMessage());
        }
    }

    public synchronized void close() {
        try {
            executor.run(this::discardStale);
        } catch (Exception e) {
            log.debug("关闭浏览器失败: {}", e.getMessage());
        }
    }
}
