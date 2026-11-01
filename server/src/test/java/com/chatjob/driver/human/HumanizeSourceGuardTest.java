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
 * <p><b>阶段状态（spec 0006 §4.2）</b>：阶段 1 只建骨架、不接线，所以 {@link #CONVERTED} 为空，
 * 真正的扫描此时是空循环。为了让这个测试在阶段 1 也有实际内容，它同时验证两件事：
 * 检测器本身认得出裸调用（{@link #检测器能认出裸调用()}），以及<b>没有任何适配器依赖本层</b>
 * （{@link #阶段一没有任何适配器依赖本层()}）——后者是"不接线"这条验收的唯一机器证据。
 */
class HumanizeSourceGuardTest {

    /**
     * 已改造的平台标识。**阶段 2 加入 {@code boss}，阶段 4 加入 {@code zhaopin}。**
     *
     * <p>加进来的前提是那个平台的交互已经全部走 {@link Humanize}；加太早会让守卫在
     * 改造未完成时就变红，而"先加进白名单再改"就等于把守卫关掉了。
     */
    private static final Set<String> CONVERTED = Set.of();

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

    @Test
    void 阶段一没有任何适配器依赖本层() {
        // "不接线"的可验证形式：platform 包里搜不到对本层的引用。
        // 阶段 2 开始接线时删掉这条断言，并把 boss 加进 CONVERTED。
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles(javaSourceRoot().resolve("com/chatjob/platform"))) {
            if (read(file).contains("com.chatjob.driver.human")) {
                offenders.add(file.getFileName().toString());
            }
        }

        assertThat(offenders).as("阶段 1 不应有适配器引用 com.chatjob.driver.human").isEmpty();
    }

    // ---------------------------------------------------------------- 实现

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
