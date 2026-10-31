package com.chatjob.driver;

import com.chatjob.config.AppProperties;
import com.chatjob.config.ProjectHome;
import com.chatjob.platform.PlatformSwitches;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 2a 的验收：两个平台各自的浏览器会话能<b>同时</b>存在，各自导航互不干扰。
 *
 * <p>为什么用测试而不是加一个 HTTP 入口：2a 只改 driver 层，产品上还没有「同时跑两个平台」
 * 的操作路径（那是 2b）。为了验收就临时在产品里开一个「按平台拉起浏览器」的接口，
 * 等于把调试后门留在生产代码里。这里直接用 {@link BrowserSessions} 起两份，
 * 既是对被测代码的真实调用，也不碰数据库、不启 Spring。
 *
 * <p>默认不跑（要开真实浏览器窗口、要几十秒），显式开启：
 * <pre>
 * mvn test -Dtest=PlatformBrowserIsolationTest -Dchat-job.browser.verify=true \
 *          -Dchat-job.browser.verify.holdSeconds=90
 * </pre>
 * {@code holdSeconds} 是留给人/外部工具观测的时间：测试会在这段时间里让两个浏览器
 * 停在各自的探针页上，结束后再验证「关掉一个，另一个照常」。
 *
 * <p>profile 根默认是 {@code build/verify-2a/}（一次性目录，不碰真实登录态）。
 * 需要让外部工具观测<b>带扩展的真实 profile</b> 时用
 * {@code -Dchat-job.browser.verify.profileRoot=<仓库根>/browser-data} 切过去 ——
 * 那次会真的用 boss 的登录态 profile，跑之前必须确认服务已停
 * （Chrome 对同一 profile 目录有独占锁）。
 */
@EnabledIfSystemProperty(named = "chat-job.browser.verify", matches = "true")
class PlatformBrowserIsolationTest {

    private static final String BOSS_PROBE_TITLE = "boss-probe";
    private static final String ZHAOPIN_PROBE_TITLE = "zhaopin-probe";

    private static String probePage(String title) {
        return "data:text/html,<title>" + title + "</title><h1>" + title + "</h1>";
    }

    /** 默认一次性的验证目录；可用 {@code -Dchat-job.browser.verify.profileRoot=} 指向真实 profile 根 */
    private static Path profileRoot() {
        String override = System.getProperty("chat-job.browser.verify.profileRoot");
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath();
        }
        return ProjectHome.resolve().resolve("build").resolve("verify-2a").resolve("browser-data");
    }

    @Test
    void twoPlatformsGetTheirOwnBrowserAndDoNotInterfere() throws Exception {
        Path profileRoot = profileRoot();
        AppProperties props = new AppProperties(
                new AppProperties.Driver(
                        ProjectHome.resolve().resolve("build").resolve("patchright-driver").toString(), true),
                new AppProperties.Browser(profileRoot.toString(), 0, 30000, "auto", ""),
                new AppProperties.SelfCheckProps(false),
                new AppProperties.NotifyProps("", 300),
                "boss");

        // 开关恒开：这一组测的是「两个平台各自隔离」，不涉及停用语义
        BrowserSessions sessions = new BrowserSessions(props, new PatchrightDriver(props),
                new BrowserChannel(props), new PlatformSwitches(p -> true));
        try {
            PlatformBrowser boss = sessions.of("boss");
            PlatformBrowser zhaopin = sessions.of("zhaopin");

            // 1) 同一平台重复取 = 同一份；不同平台 = 不同份（含执行线程）
            assertSame(boss.session(), sessions.of("boss").session(), "同一平台应复用同一份会话");
            assertNotSame(boss.session(), zhaopin.session(), "两个平台不能共用一份会话");
            assertNotSame(boss.executor(), zhaopin.executor(), "两个平台不能共用一条 Playwright 线程");
            assertEquals("playwright-boss", boss.executor().threadName());
            assertEquals("playwright-zhaopin", zhaopin.executor().threadName());

            // 2) profile 按平台分目录
            assertEquals(profileRoot.resolve("boss").toAbsolutePath(), boss.session().userDataDir());
            assertEquals(profileRoot.resolve("zhaopin").toAbsolutePath(), zhaopin.session().userDataDir());

            // 3) 让注册表知道有这个平台，不等于把浏览器拉起来
            assertFalse(boss.session().isReady(), "创建会话对象时不应自动启动浏览器");
            assertFalse(zhaopin.session().isReady());

            // 4) 起 boss：另一个平台不能被动跟着起来
            boss.session().ensureReady();
            assertTrue(boss.session().isReady(), "boss 浏览器应已就绪");
            assertFalse(zhaopin.session().isReady(), "未用到的平台不该跟着启动浏览器");

            // 5) 再起 zhaopin：两份实例同时存在
            zhaopin.session().ensureReady();
            assertTrue(zhaopin.session().isReady(), "zhaopin 浏览器应已就绪");
            assertNotSame(boss.session().context(), zhaopin.session().context(), "两个平台必须是两个独立 BrowserContext");

            // 6) 磁盘证据：两个 profile 各自被真正写入
            assertTrue(Files.isDirectory(profileRoot.resolve("boss").resolve("Default")),
                    "boss profile 应被创建: " + boss.session().userDataDir());
            assertTrue(Files.isDirectory(profileRoot.resolve("zhaopin").resolve("Default")),
                    "zhaopin profile 应被创建: " + zhaopin.session().userDataDir());
            System.out.println("[2a] profile 根目录: " + profileRoot);
            System.out.println("[2a] boss     profile: " + boss.session().userDataDir());
            System.out.println("[2a] zhaopin  profile: " + zhaopin.session().userDataDir());

            // 7) 线程归属：在 boss 的线程里，只有 boss 认为"在自己的线程上"
            assertTrue(boss.executor().call(() -> boss.executor().onPlaywrightThread()));
            assertTrue(boss.executor().call(() -> !zhaopin.executor().onPlaywrightThread()),
                    "boss 的调度线程不能被认为是 zhaopin 的线程");

            // 8) 各自导航到自己的探针页
            Page bossPage = boss.executor().call(() -> {
                Page page = boss.session().newPage();
                page.navigate(probePage(BOSS_PROBE_TITLE));
                return page;
            });
            Page zhaopinPage = zhaopin.executor().call(() -> {
                Page page = zhaopin.session().newPage();
                page.navigate(probePage(ZHAOPIN_PROBE_TITLE));
                return page;
            });

            assertEquals(BOSS_PROBE_TITLE, boss.executor().call(bossPage::title));
            assertEquals(ZHAOPIN_PROBE_TITLE, zhaopin.executor().call(zhaopinPage::title));

            // 9) 只动 boss：zhaopin 的页面必须纹丝不动
            boss.executor().run(() -> {
                try {
                    bossPage.navigate("https://www.zhipin.com/");
                } catch (Exception e) {
                    System.out.println("[2a] boss 真实站点导航失败（不影响验收，仅作页面证据）: " + e.getMessage());
                }
            });
            assertEquals(ZHAOPIN_PROBE_TITLE, zhaopin.executor().call(zhaopinPage::title),
                    "boss 的导航不该改动 zhaopin 的页面");

            // 10) 再各自去一趟真实站点，留给人眼看两个窗口确实是两个不同的站
            zhaopin.executor().run(() -> {
                try {
                    zhaopinPage.navigate("https://www.zhaopin.com/");
                } catch (Exception e) {
                    System.out.println("[2a] zhaopin 真实站点导航失败（不影响验收，仅作页面证据）: " + e.getMessage());
                }
            });
            assertNotEqualsUrl(boss.executor().call(() -> bossPage.url()),
                    zhaopin.executor().call(() -> zhaopinPage.url()));

            System.out.println("[2a] boss    页面: " + boss.executor().call(() -> bossPage.url()));
            System.out.println("[2a] zhaopin 页面: " + zhaopin.executor().call(() -> zhaopinPage.url()));

            // 10) 留观测窗口：外部工具在这段时间里能看到两个浏览器实例并存
            long holdSeconds = Long.getLong("chat-job.browser.verify.holdSeconds", 0L);
            if (holdSeconds > 0) {
                System.out.println("[2a] 两个浏览器实例保持打开 " + holdSeconds + " 秒，供外部观测…");
                Thread.sleep(holdSeconds * 1000L);
            }

            // 11) 关掉一个平台，另一个照常工作（无共享状态）
            String zpUrlBeforeClose = zhaopin.executor().call(zhaopinPage::url);
            boss.session().close();
            assertFalse(boss.session().isReady(), "boss 关闭后应为未就绪");
            assertTrue(zhaopin.session().isReady(), "boss 关闭不该波及 zhaopin");
            assertEquals(zpUrlBeforeClose, zhaopin.executor().call(zhaopinPage::url),
                    "boss 关闭后 zhaopin 的页面仍应停留在原处");
            assertTrue(zhaopin.executor().call(zhaopinPage::title).length() > 0,
                    "boss 关闭后 zhaopin 的页面仍应可读");

            // 12) 关掉的平台可以自己重建，重建后仍是它自己的 profile
            boss.session().ensureReady();
            assertTrue(boss.session().isReady(), "boss 应能在自己的平台上重建");
            assertEquals(profileRoot.resolve("boss").toAbsolutePath(), boss.session().userDataDir());
            assertTrue(zhaopin.session().isReady(), "boss 重建不该影响 zhaopin");

            System.out.println("[2a] 通过：两平台会话独立创建、独立导航、互不影响、可各自重建");
        } finally {
            sessions.closeAll();
        }
    }

    private static void assertNotEqualsUrl(String bossUrl, String zhaopinUrl) {
        assertNotEquals(bossUrl, zhaopinUrl, "两个平台的页面地址不应相同");
    }
}
