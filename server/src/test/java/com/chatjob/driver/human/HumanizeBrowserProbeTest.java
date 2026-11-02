package com.chatjob.driver.human;

import com.chatjob.config.AppProperties;
import com.chatjob.config.ProjectHome;
import com.chatjob.driver.BrowserChannel;
import com.chatjob.driver.BrowserSession;
import com.chatjob.driver.PatchrightDriver;
import com.chatjob.driver.PlatformBrowser;
import com.chatjob.driver.BrowserSessions;
import com.chatjob.platform.PlatformSwitches;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实浏览器探针：把本层"到底在页面上产生了什么"量出来。
 *
 * <p><b>为什么需要它。</b>spec 0006 §4.3 把"真浏览器手验"列为不进 {@code mvn test} 的一类。
 * 但其中三件事有明确的数字，可以自动化，而且不依赖任何一个平台的页面结构：
 * <ol>
 *   <li>滚动是否真的派发 {@code wheel} 事件（§2.1 第 5 条，改造前用 {@code window.scrollBy}
 *       时是 0 次）；</li>
 *   <li>输入是否真的产生 {@code keydown}，以及<b>中文走 {@code insertText} 时到底有没有
 *       {@code keydown}</b>——这是 spec §5.3 第 2 条一直没有取证的开放问题；</li>
 *   <li>条件 c 的 {@code childElementCount} 在真实编辑器里涨多少（§3.4 的容差 2 是否够）。</li>
 * </ol>
 *
 * <p>用本地 {@code data:} 页而不是真站点：这里量的是浏览器事件，不是平台页面。
 * 真站点的验收（卡片点得开、详情抓得到、招呼语发得出）在服务里跑，见 spec §5.2。
 *
 * <p><b>默认不跑</b>（要起一次真实浏览器）。开启方式：
 * <pre>
 * mvn test -Dtest=HumanizeBrowserProbeTest -Dchat-job.humanize.probe=true
 * </pre>
 * profile 用 {@code build/verify-2p/} 下的一次性目录，不碰任何真实登录态。
 */
@EnabledIfSystemProperty(named = "chat-job.humanize.probe", matches = "true")
class HumanizeBrowserProbeTest {

    /**
     * 探针页。三块东西各对应一个问题：
     * <ul>
     *   <li>4000px 高的容器：滚动目标；</li>
     *   <li>{@code textarea}：原生输入框，与智联的消息输入框同类；</li>
     *   <li>{@code div[contenteditable]}：富文本，与 Boss 的聊天输入框同类；</li>
     * </ul>
     * 三个计数器挂在 window 上，{@code evaluate} 直接读。
     */
    private static final String PROBE_HTML = """
            <!doctype html>
            <html><head><meta charset="utf-8"><style>
              html, body { margin: 0; padding: 0; }
              #tall { height: 4000px; background: linear-gradient(#fff, #ccc); }
              textarea { width: 400px; height: 80px; }
              #rich { width: 400px; min-height: 60px; border: 1px solid #999; }
            </style></head>
            <body>
              <div id="tall"></div>
              <textarea id="ta"></textarea>
              <div id="rich" contenteditable="true"></div>
              <script>
                window.__wheels = 0;
                window.__keylog = [];
                window.__inputs = 0;
                window.addEventListener('wheel', () => window.__wheels++);
                // 记按键名而不只是计数：清空输入的 Ctrl+A / Backspace 也是按键，
                // 只数个数会把它们算进"中文输入的 keydown"，得出错误结论
                window.addEventListener('keydown', (e) => window.__keylog.push(e.key));
                window.addEventListener('input', () => window.__inputs++);
              </script>
            </body></html>
            """;

    @Test
    void 本层在真实页面上产生了滚轮与按键事件() {
        Path profileRoot = ProjectHome.resolve().resolve("build").resolve("verify-2p").resolve("browser-data");
        AppProperties props = new AppProperties(
                new AppProperties.Driver(
                        ProjectHome.resolve().resolve("build").resolve("patchright-driver").toString(), true),
                new AppProperties.Browser(profileRoot.toString(), 0, 30000, "auto", ""),
                new AppProperties.SelfCheckProps(false),
                new AppProperties.NotifyProps("", 300),
                "boss",
                enabledHumanize());

        BrowserSessions sessions = new BrowserSessions(props, new PatchrightDriver(props),
                new BrowserChannel(props), new PlatformSwitches(p -> true));
        try {
            PlatformBrowser browser = sessions.of("boss");
            BrowserSession session = browser.session();
            Humanize human = browser.humanize();
            Page page = session.newPage();
            page.setContent(PROBE_HTML);

            // ---------------------------------------------------- 1. 滚动 → wheel
            int before = intOf(page, "() => window.__wheels");
            int startY = intOf(page, "() => Math.round(window.scrollY)");
            human.scrollPage(page, page.locator("#tall"), 600);
            int wheels = intOf(page, "() => window.__wheels");
            int deltaY = intOf(page, "() => Math.round(window.scrollY)") - startY;
            System.out.println("[probe] 滚动 600px：wheel 事件 " + (wheels - before) + " 次，实际位移 " + deltaY + "px");

            assertThat(wheels - before)
                    .as("改造前 window.scrollBy 产生 0 次 wheel；本层必须产生真实滚轮事件")
                    .isPositive();
            // 实际位移会略大于 600：预热（本页第一次交互）自己会滚 2–4 小步，每步 40–110px。
            // 这不是误差而是设计的一部分（spec §1.4 的"预热 = 光标移入 + 滚动几小步 + 停留"），
            // 所以断言写成"至少滚到目标、最多多出一次预热的量"，把这个已知增量写明。
            assertThat(deltaY)
                    .as("位移应达到目标，且超出部分只能来自预热的小步滚动")
                    .isBetween(600, 600 + 4 * 110);

            // ---------------------------------------------------- 2. ASCII 输入 → keydown
            page.evaluate("() => { window.__keylog = []; window.__inputs = 0; }");
            boolean asciiOk = human.type(page.locator("#ta"), "hello2026");
            String asciiLog = String.valueOf(page.evaluate("() => JSON.stringify(window.__keylog)"));
            System.out.println("[probe] 输入 ASCII \"hello2026\"：keydown " + asciiLog + "，终态正确=" + asciiOk);
            assertThat(asciiOk).as("原生输入框的终态必须与目标一致").isTrue();
            assertThat(asciiLog)
                    .as("ASCII 逐键输入必然产生每个字符的 keydown")
                    .contains("\"h\"", "\"e\"", "\"l\"", "\"o\"", "\"2\"", "\"0\"", "\"6\"");

            // ---------------------------------------------------- 3. 中文输入 → keydown?
            page.evaluate("() => { window.__keylog = []; window.__inputs = 0; }");
            String chinese = "您好，看到贵公司招聘后端开发";
            boolean cjkOk = human.type(page.locator("#ta"), chinese);
            String cjkLog = String.valueOf(page.evaluate("() => JSON.stringify(window.__keylog)"));
            int cjkInputs = intOf(page, "() => window.__inputs");
            String value = String.valueOf(page.evaluate("() => document.querySelector('#ta').value"));
            System.out.println("[probe] 输入中文（" + chinese.length() + " 字）：keydown " + cjkLog
                    + "，input " + cjkInputs + " 次，终态正确=" + cjkOk + "，读回=「" + value + "」");
            assertThat(cjkOk).as("中文输入的终态必须与目标一致").isTrue();
            assertThat(value).isEqualTo(chinese);
            // spec 0006 §5.3 第 2 条的取证。清空输入用的 Ctrl+A / Backspace 也是 keydown，
            // 所以断言写成"只有这三个键、没有任何一个中文字符的 keydown"——这才是那条
            // "中文只有 input 没有 keydown" 的已知限制的正确表述。
            // 键名是 "A" 而不是 "a"：按住 Control 时浏览器上报的是大写键值。
            assertThat(cjkLog)
                    .as("纯中文输入期间应只有清空用的 Control/A/Backspace，没有字符 keydown")
                    .isEqualTo("[\"Control\",\"A\",\"Backspace\"]");
            // 15 个汉字各触发一次 input，说明"字与字之间停顿"确实生效在 input 上
            assertThat(cjkInputs).as("每个汉字都应产生一次 input").isGreaterThanOrEqualTo(chinese.length() - 1);

            // ---------------------------------------------------- 4. 富文本 + 条件 c
            int structureBefore = intOf(page, "() => document.querySelector('#rich').childElementCount");
            boolean richOk = human.type(page.locator("#rich"), "您好，方便聊聊这个岗位吗");
            int structureAfter = intOf(page, "() => document.querySelector('#rich').childElementCount");
            String richText = String.valueOf(page.evaluate("() => document.querySelector('#rich').innerText"));
            System.out.println("[probe] 富文本输入：终态正确=" + richOk
                    + "，childElementCount " + structureBefore + " → " + structureAfter
                    + "，读回=「" + richText.replace("\n", "\\n") + "」");
            assertThat(richOk).as("富文本的终态必须与目标一致").isTrue();
            // 与 Humanizer.STRUCTURE_CHANGE_TOLERANCE 同一个口径（那里是私有常量，这里写死 2）。
            // 单段文字没有换行时，contenteditable 的子元素数不该变；真变了说明编辑器把每个字
            // 包成了独立节点，那时容差 2 会偏紧，降级率会虚高 —— 这正是 §5.3 第 3 条要标定的东西
            assertThat(structureAfter - structureBefore)
                    .as("条件 c 的实际增量必须落在容差内，否则输入会走降级路径")
                    .isLessThanOrEqualTo(2);

            System.out.println("[probe] 计数汇总: " + human.summary());
            page.close();
        } finally {
            sessions.closeAll();
        }
    }

    /**
     * 与 yaml 里那份等价的一份配置，只改 {@code enabled=true}。
     *
     * <p>不复用 {@code application.yaml}：探针要在"总开关打开"的前提下量事件，
     * 而 yaml 的值会随验证目的被改来改去，写死一份才不受影响。
     */
    private static HumanizeConfig enabledHumanize() {
        return new HumanizeConfig(true, 0L, null, null, null, null, null);
    }

    private static int intOf(Page page, String expression) {
        Object value = page.evaluate(expression);
        return value instanceof Number number ? number.intValue() : -1;
    }
}
