package com.chatjob.driver.human;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 源码守卫：已改造的适配器里不得再出现裸的交互调用。
 *
 * <p>为什么用源码扫描而不是 ArchUnit：这条规则不是"类型之间不许依赖"，而是"某个方法名不许出现在
 * 某个包里"，ArchUnit 表达不了。扫一遍几百个文件只要几百毫秒，成本可以忽略
 * （spec 0006 Q8）。
 *
 * <p>防的是什么：不是今天这批调用点（它们会被改完），而是三个月后新加的那一处
 * {@code item.click()}——它不会报错、测试不会失败，只是绕过了这一层。
 *
 * <p><b>阶段状态（spec 0006 §4.2）</b>：阶段 1 只建骨架、不接线，{@link #CONVERTED} 为空；
 * 阶段 2 起 {@code boss} 与 {@code zhaopin} 都已接线，下面两个测试都会真的扫到文件。
 * 阶段 1 那条「没有任何适配器依赖本层」的断言已删除——它的前提（不接线）已经不成立。
 */
class HumanizeSourceGuardTest {

    /**
     * 已改造的平台标识。**阶段 2 加入 {@code boss}；{@code zhaopin} 随同一阶段接线
     * （spec 0006 阶段 4 的交互部分提前做），阶段 3 只补导航。**
     *
     * <p>加进来的前提是那个平台的交互已经全部走 {@link Humanize}；加太早会让守卫在
     * 改造未完成时就变红，而"先加进白名单再改"就等于把守卫关掉了。
     */
    private static final Set<String> CONVERTED = Set.of("boss", "zhaopin");

    /**
     * 禁用的调用。只针对写入类调用：读 DOM 的 {@code evaluate}、{@code count()}、
     * {@code getAttribute()} 一律放行——它们不产生输入事件（spec 0006 §2.1 第 2 条）。
     */
    private static final List<Pattern> FORBIDDEN = List.of(
            // 点击与输入：Playwright 的 Locator 写入类方法
            Pattern.compile("\\.\\s*(click|dblclick|fill|type|press|pressSequentially|hover|check|uncheck|selectOption)\\s*\\("),
            // 直接拿鼠标/键盘：绕过了轨迹与节奏
            Pattern.compile("\\.\\s*(mouse|keyboard)\\s*\\(\\s*\\)"),
            // JS 改滚动位置：不派发 wheel 事件
            Pattern.compile("window\\s*\\.\\s*scroll(By|To)\\s*\\("));

    /**
     * 放行的形式：经本层的调用，例如 {@code human.click(target, options)}。
     *
     * <p>适配器里保存 {@link Humanize} 实例的字段名固定为 {@code human}（少数地方用 {@code humanize}），
     * 本测试按这两个名字放行。没有这条，阶段 2 一接线守卫就会把每一次合法调用都算成违规。
     *
     * <p>代价是：把本层实例改名成别的字段名，守卫就会误报。那是有意的——宁可在接线时立刻发现，
     * 也不要因为"名字对不上"而让守卫静默放过真实的裸调用。
     */
    private static final Pattern THROUGH_LAYER = Pattern.compile("\\b(human|humanize)\\s*\\.\\s*[A-Za-z_$][\\w$]*\\s*\\(");

    @Test
    void 检测器能认出裸调用() {
        String source = """
                class Sample {
                    void act(Locator item, Page page) {
                        item.click();
                        item.fill("x");
                        page.mouse().wheel(0, 100);
                        page.evaluate("() => window.scrollBy(0, 100)");
                    }
                }
                """;

        assertThat(violations(source)).hasSize(4);
    }

    @Test
    void 注释里的调用不算违规() {
        // 阶段 2 会在适配器里写下"原来是 item.click()"这类注释，把注释算成违规会让守卫无法维护
        String source = """
                class Sample {
                    // 原来是 item.click()，现在走 humanize
                    /* page.mouse().wheel(0, 100) 已删除 */
                    void act() {
                    }
                }
                """;

        assertThat(violations(source)).isEmpty();
    }

    @Test
    void 经本层的调用不算违规() {
        // 阶段 2 接线后适配器里全是这种形状，守卫必须放行
        String source = """
                class Sample {
                    void act(Locator item, String msg, int dy) {
                        human.click(item, null);
                        human.type(item, msg);
                        human.scrollPage(item.page(), item, dy);
                        humanize.navigate(item.page(), "https://example.com", 30000);
                    }
                }
                """;

        assertThat(violations(source)).isEmpty();
    }

    @Test
    void 读操作不算违规() {
        String source = """
                class Sample {
                    void read(Locator item) {
                        int n = item.count();
                        String text = item.getAttribute("href");
                        Object tag = item.evaluate("el => el.tagName");
                    }
                }
                """;

        assertThat(violations(source)).isEmpty();
    }

    @Test
    void 已改造平台的适配器里没有裸交互调用() {
        for (String platform : CONVERTED) {
            Path dir = javaSourceRoot().resolve("com/chatjob/platform").resolve(platform);
            List<Path> files = javaFiles(dir);
            // 目录写错时上面那个循环会变成空循环，守卫就静默失效了
            assertThat(files)
                    .as("平台 %s 的源码目录必须存在且含 java 文件：%s", platform, dir)
                    .isNotEmpty();
            for (Path file : files) {
                assertThat(violations(read(file)))
                        .as("%s 里还有裸的交互调用，应改为经 Humanize", file.getFileName())
                        .isEmpty();
            }
        }
    }

    /**
     * 已改造的适配器必须真的走本层。
     *
     * <p>只断言"没有裸调用"是不够的：把整段交互代码删掉同样没有裸调用。所以再断言
     * 每个已改造平台的适配器源码里确实出现了 {@code com.chatjob.driver.human} 的引用，
     * 以及 `human.` 形式的调用。
     */
    @Test
    void 已改造平台确实经本层() {
        assertThat(CONVERTED).as("阶段 2 起至少有两个平台已改造").isNotEmpty();
        for (String platform : CONVERTED) {
            Path dir = javaSourceRoot().resolve("com/chatjob/platform").resolve(platform);
            List<Path> files = javaFiles(dir);
            assertThat(files).as("平台 %s 的源码目录必须存在且含 java 文件：%s", platform, dir).isNotEmpty();

            boolean importsLayer = false;
            int layerCalls = 0;
            for (Path file : files) {
                String source = read(file);
                if (source.contains("com.chatjob.driver.human")) {
                    importsLayer = true;
                }
                layerCalls += countLayerCalls(stripComments(source));
            }
            assertThat(importsLayer).as("%s 的适配器应引用 com.chatjob.driver.human", platform).isTrue();
            assertThat(layerCalls).as("%s 的适配器应有经 human.* 的交互调用", platform).isPositive();
        }
    }

    /**
     * 已经搬进 {@code PageKit} 的那批助手，不得以私有方法的形式回到适配器里。
     *
     * <p>改造前两个适配器各有一份手抄副本：{@code navigateWithRetry} / {@code isStaleError} /
     * {@code safeUrl} / {@code openThread} / {@code verifyDigest} / {@code readUnread} /
     * {@code firstText} / {@code attr} / {@code fillInput} / {@code closeQuietly}。改一次重试策略
     * 要改两处，只改一处不会被任何测试发现 —— 两个适配器本体在这之前零测试引用。
     *
     * <p><b>为什么列名单而不是断言「所有同名私有方法」</b>：{@code openDetailPage} /
     * {@code messageId} / {@code countSelfMessages} / {@code ensureListPage} / {@code ensureThreadPage}
     * 今天仍然同名，但它们不是副本 —— 前三个是同一件事在两套页面结构上的不同实现
     * （Boss 数 DOM、智联读 JS 面板；详情页一个是两跳导航、一个是点卡片读右侧面板），
     * 后两个是 6 行页面缓存判据，状态（两个 {@code Page} 字段，还要参与身份比较）属于适配器。
     * 把它们也搬走会让 {@code PageKit} 从无状态变成有状态，为 12 行换来一个有状态的 module 不划算。
     * 一条会立刻需要五个例外的规则，最后一定会被整条关掉。
     */
    @Test
    void 搬进共享模块的助手没有回到适配器里() {
        java.util.List<String> MOVED_TO_KIT = java.util.List.of(
                "navigateWithRetry", "isStaleError", "safeUrl", "openThread", "verifyDigest",
                "readUnread", "firstText", "attr", "fillInput", "closeQuietly", "nameOf",
                "landedOn", "waitForList", "isSecurityVerify", "pause");

        for (String platform : CONVERTED) {
            Path dir = javaSourceRoot().resolve("com/chatjob/platform").resolve(platform);
            java.util.Set<String> present = new java.util.TreeSet<>();
            for (Path file : javaFiles(dir)) {
                present.addAll(privateMethodNames(read(file)));
            }
            present.retainAll(MOVED_TO_KIT);
            assertThat(present)
                    .as("%s 的适配器里又出现了这些私有助手，它们已经在 PageKit 里", platform)
                    .isEmpty();
        }
    }

    /**
     * 共享的页面动作自身也必须经 {@link Humanize}。
     *
     * <p>把交互搬进 {@code platform/page} 之后，那条「不得裸调用」的规则如果只扫
     * {@code boss} / {@code zhaopin} 两个目录，就等于把最该守的那一段放到了栅栏外面。
     */
    @Test
    void 共享的页面动作也经本层() {
        Path dir = javaSourceRoot().resolve("com/chatjob/platform/page");
        List<Path> files = javaFiles(dir);
        assertThat(files).as("共享页面动作的目录必须存在且含 java 文件：%s", dir).isNotEmpty();

        int layerCalls = 0;
        for (Path file : files) {
            String source = read(file);
            assertThat(violations(source))
                    .as("%s 里还有裸的交互调用，应改为经 Humanize", file.getFileName())
                    .isEmpty();
            layerCalls += countLayerCalls(stripComments(source));
        }
        assertThat(layerCalls).as("共享页面动作里应有经 human.* 的交互调用").isPositive();
    }

    // ---------------------------------------------------------------- 实现

    /**
     * 私有方法名。
     *
     * <p>逐行判断，不做跨行匹配：方法声明在本仓库里都是单行，而跨行匹配会出真问题 ——
     * 上一版用 {@code \s} 让某个 {@code private} 行跟下面几行拼成一次匹配，把
     * {@code kit.closeQuietly(page)} 误报成了私有方法。
     *
     * <p>{@code [^=;]*?} 是为了跳过字段声明：{@code private static final Pattern P = Pattern.compile(...)}
     * 里第一个括号属于 {@code compile}，不是本行声明的方法。
     */
    private static java.util.Set<String> privateMethodNames(String source) {
        Pattern pattern = Pattern.compile("^private\\b[^=;]{0,200}?\\b(\\w+)[ \\t]*\\(");
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("private ")) {
                continue;
            }
            Matcher matcher = pattern.matcher(trimmed);
            if (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }


    private static int countLayerCalls(String source) {
        Matcher matcher = THROUGH_LAYER.matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** 命中禁列表的片段，用于断言与报错信息。 */
    static List<String> violations(String source) {
        String stripped = stripComments(source);
        // 先把经本层的调用抹成中性形式，再扫禁用列表：否则 human.click(...) 也会被算成裸点击
        String neutralized = THROUGH_LAYER.matcher(stripped).replaceAll("LAYER_CALL(");
        List<String> hits = new ArrayList<>();
        for (Pattern pattern : FORBIDDEN) {
            Matcher matcher = pattern.matcher(neutralized);
            while (matcher.find()) {
                hits.add(matcher.group());
            }
        }
        return hits;
    }

    /**
     * 去掉注释。行注释到行尾、块注释到它自己的结束标记，字符串常量里的内容原样保留。
     *
     * <p>仍然只是近似实现：Java 文本块（三引号）会让"是否在字符串里"的判断失准，
     * 那种情形下可能少扫到代码，但不会把注释误判成代码。守卫选择"宁可漏报不可误报"，
     * 因为一次误报就足以让人把这个测试直接关掉。
     */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inBlockComment = false;
        boolean inString = false;
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                } else if (current == '\n') {
                    out.append('\n');
                }
                continue;
            }
            if (inString) {
                out.append(current);
                if (current == '\\' && next != '\0') {
                    out.append(next);
                    i++;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
                out.append(current);
                continue;
            }
            if (current == '/' && next == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
                out.append('\n');
                continue;
            }
            if (current == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            out.append(current);
        }
        return out.toString();
    }

    private static List<Path> javaFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 主代码根目录。测试的工作目录是模块目录（{@code server/}，Surefire 与 IDE 都如此），
     * 所以相对 {@code user.dir} 解析；找不到就说明工作目录变了，直接失败而不是静默跳过。
     */
    private static Path javaSourceRoot() {
        Path root = Paths.get(System.getProperty("user.dir", ".")).resolve("src/main/java").toAbsolutePath();
        assertThat(root).as("找不到主代码目录：%s", root).isDirectory();
        return root;
    }
}
