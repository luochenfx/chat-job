package com.chatjob.platform;

import com.chatjob.config.AppProperties;
import com.chatjob.config.ProjectHome;
import com.chatjob.driver.BrowserChannel;
import com.chatjob.driver.BrowserSession;
import com.chatjob.driver.BrowserSessions;
import com.chatjob.driver.PatchrightDriver;
import com.chatjob.driver.PlatformBrowser;
import com.chatjob.driver.human.Humanize;
import com.chatjob.driver.human.HumanizeConfig;
import com.chatjob.driver.human.Humanizer;
import com.chatjob.platform.Models.DeliveryOptions;
import com.chatjob.platform.boss.BossSelectors;
import com.microsoft.playwright.Page;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实浏览器探针：量"投递的两跳到底在请求头上产生了什么"。
 *
 * <p><b>为什么用本地 HTTP 服务而不是真站点。</b>要验的是 {@code Referer} 与
 * {@code Sec-Fetch-Site}，它们由"这次导航由谁发起"决定，与站点内容无关；而真站点上这两个头
 * 只有服务端能看到。本地服务能把收到的请求头原样读回来。真站点那一侧的验收在服务里跑
 * （spec 0006 §5.2）。
 *
 * <p><b>三个用例合起来是一份回归证据</b>：先用"连跳两次不带来源"固定住被证伪的那个假设，
 * 再断言生产路径（{@link ListFirstEntry}）确实带上了来源。少了第一个用例，后人很容易
 * 把 {@code navigateInPage} 改回"两次 {@code page.navigate}"，而那看起来完全合理。
 *
 * <p><b>默认不跑</b>（要起一次真实浏览器）：
 * <pre>
 * mvn test -Dtest=ListFirstEntryProbeTest -Dchat-job.humanize.probe=true
 * </pre>
 */
@EnabledIfSystemProperty(named = "chat-job.humanize.probe", matches = "true")
class ListFirstEntryProbeTest {

    /** 与 Boss 用同一个卡片选择器，这样探针里的"列表页"与生产判据一致。 */
    private static final String CARD = BossSelectors.CARD;

    private static final String LIST_HTML = """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>列表页</title></head>
            <body><ul class="rec-job-list">
              <li class="job-card-box">岗位 A</li>
              <li class="job-card-box">岗位 B</li>
            </ul></body></html>
            """;

    private static final String EMPTY_LIST_HTML = """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>空列表页</title></head>
            <body><p>没有结果</p></body></html>
            """;

    private static final String DETAIL_HTML = """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>详情页</title></head>
            <body><h1>岗位 A</h1></body></html>
            """;

    private HttpServer server;
    private String base;

    /** 每个详情页请求收到的头。 */
    private final List<Request> detailRequests = new CopyOnWriteArrayList<>();
    /** 收到过多少次列表页请求，用来判"开关关掉时没有多出来的那一跳"。 */
    private final List<String> listRequests = new CopyOnWriteArrayList<>();

    private record Request(String referer, String secFetchSite) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/list", exchange -> {
            listRequests.add(exchange.getRequestURI().toString());
            respond(exchange, LIST_HTML);
        });
        server.createContext("/empty", exchange -> respond(exchange, EMPTY_LIST_HTML));
        server.createContext("/detail", exchange -> {
            detailRequests.add(new Request(
                    header(exchange, "Referer"),
                    header(exchange, "Sec-Fetch-Site")));
            respond(exchange, DETAIL_HTML);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 被证伪的假设：以为"上一个文档是同站页面"就能让下一次 {@code page.navigate} 带上来源。
     * 实测是不带——所以 {@link Humanize#navigateInPage} 必须存在。
     */
    @Test
    void 连跳两次_page_navigate_仍然不带来源() {
        withPage((page, human) -> {
            String listUrl = base + "/list";
            String detailUrl = base + "/detail";
            human.navigate(page, listUrl, 20_000);
            human.navigate(page, detailUrl, 20_000);

            System.out.println("[probe] 两次 page.navigate 后详情页请求头=" + detailRequests);
            assertThat(detailRequests).hasSize(1);
            assertThat(detailRequests.get(0).referer())
                    .as("page.navigate 是浏览器发起的导航，不带 Referer，与上一页无关")
                    .isEmpty();
            assertThat(detailRequests.get(0).secFetchSite())
                    .as("没有发起方文档，Sec-Fetch-Site 是 none")
                    .isEqualTo("none");
        });
    }

    @Test
    void 生产路径的两跳让详情页请求带上站内来源() {
        withPage((page, human) -> {
            String listUrl = base + "/list";
            String detailUrl = base + "/detail";
            ListFirstEntry.Outcome outcome = ListFirstEntry.open(
                    human, page, listUrl, CARD, detailUrl, 8_000, 20_000);

            String documentReferrer = String.valueOf(page.evaluate("() => document.referrer"));
            System.out.println("[probe] 两跳结果: fromList=" + outcome.fromList()
                    + "，卡片=" + outcome.cards()
                    + "，document.referrer=" + documentReferrer
                    + "，详情页请求头=" + detailRequests);

            assertThat(outcome.fromList()).as("列表页应出现卡片").isTrue();
            assertThat(outcome.cards()).isEqualTo(2);
            assertThat(page.url()).isEqualTo(detailUrl);

            assertThat(detailRequests).hasSize(1);
            Request detail = detailRequests.get(0);
            assertThat(detail.referer())
                    .as("详情页的 Referer 必须是列表页地址（spec 0006 §2.2）")
                    .isEqualTo(listUrl);
            assertThat(detail.secFetchSite())
                    .as("同站导航的 Sec-Fetch-Site 必须是 same-origin")
                    .isEqualTo("same-origin");
            assertThat(documentReferrer)
                    .as("页面自己也能读到来源，方便运行时核对")
                    .isEqualTo(listUrl);
        });
    }

    @Test
    void 列表页没有卡片时仍然进详情页() {
        withPage((page, human) -> {
            String detailUrl = base + "/detail";
            ListFirstEntry.Outcome outcome = ListFirstEntry.open(
                    human, page, base + "/empty", CARD, detailUrl, 1_000, 20_000);

            System.out.println("[probe] 空列表页: fromList=" + outcome.fromList()
                    + "，note=" + outcome.note() + "，页面=" + page.url());
            assertThat(outcome.fromList()).as("没有卡片时不能声称来源已修正").isFalse();
            assertThat(outcome.note()).isNotBlank();
            assertThat(page.url())
                    .as("第 1 跳失败也必须进到详情页：投递成功率优先于来源一致性")
                    .isEqualTo(detailUrl);
            assertThat(detailRequests).hasSize(1);
        });
    }

    @Test
    void 没有搜索条件时退化为一跳() {
        withPage((page, human) -> {
            String detailUrl = base + "/detail";
            DeliveryOptions options = new DeliveryOptions(false, List.of(), null);
            assertThat(options.search()).isNull();

            ListFirstEntry.Outcome outcome = ListFirstEntry.open(
                    human, page, null, CARD, detailUrl, 8_000, 20_000);

            System.out.println("[probe] 一跳: fromList=" + outcome.fromList()
                    + "，note=" + outcome.note() + "，详情页请求头=" + detailRequests);
            assertThat(outcome.fromList()).isFalse();
            assertThat(outcome.note()).contains("没有可用的搜索条件");
            assertThat(page.url()).isEqualTo(detailUrl);
            assertThat(detailRequests).hasSize(1);
            assertThat(detailRequests.get(0).secFetchSite()).isEqualTo("none");
        });
    }

    /** 开关关掉时整段跳过：不能多出一次列表页加载，否则"关掉即回到改造前"不成立。 */
    @Test
    void 开关关掉时只保留改造前的一次导航() {
        withPage((page, human) -> {
            Humanize disabled = Humanizer.create(new HumanizeConfig(false, 0L, null, null, null, null, null));
            String detailUrl = base + "/detail";
            ListFirstEntry.Outcome outcome = ListFirstEntry.open(
                    disabled, page, base + "/list", CARD, detailUrl, 8_000, 20_000);

            System.out.println("[probe] 停用: fromList=" + outcome.fromList()
                    + "，note=" + outcome.note() + "，列表页请求=" + listRequests);
            assertThat(outcome.fromList()).isFalse();
            assertThat(outcome.note()).contains("人类化交互已停用");
            assertThat(listRequests).as("停用路径不该去列表页").isEmpty();
            assertThat(page.url()).isEqualTo(detailUrl);
            assertThat(detailRequests).hasSize(1);
            assertThat(detailRequests.get(0).referer()).isEmpty();
        });
    }

    // ---------------------------------------------------------------- 装配

    private interface PageCase {
        void run(Page page, Humanize human);
    }

    private void withPage(PageCase testCase) {
        BrowserSessions sessions = sessions();
        try {
            PlatformBrowser browser = sessions.of("boss");
            BrowserSession session = browser.session();
            Page page = session.newPage();
            try {
                testCase.run(page, browser.humanize());
            } finally {
                page.close();
            }
        } finally {
            sessions.closeAll();
        }
    }

    private static BrowserSessions sessions() {
        Path profileRoot = ProjectHome.resolve().resolve("build").resolve("verify-3p").resolve("browser-data");
        AppProperties props = new AppProperties(
                new AppProperties.Driver(
                        ProjectHome.resolve().resolve("build").resolve("patchright-driver").toString(), true),
                new AppProperties.Browser(profileRoot.toString(), 0, 30000, "auto", ""),
                new AppProperties.SelfCheckProps(false),
                new AppProperties.NotifyProps("", 300),
                "boss",
                // 与 yaml 等价的一份，只把 enabled 写成 true：探针要在"总开关打开"时量请求头
                new HumanizeConfig(true, 0L, null, null, null, null, null));
        return new BrowserSessions(props, new PatchrightDriver(props),
                new BrowserChannel(props), new PlatformSwitches(p -> true));
    }

    private static String header(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? "" : value;
    }

    private static void respond(HttpExchange exchange, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
