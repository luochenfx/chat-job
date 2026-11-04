package com.chatjob.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后端发布的事件名与前端订阅清单必须一一对应。
 *
 * <p><b>为什么需要这条守卫</b>：事件名跨了语言，编译器管不着。前端漏订阅不会报错 ——
 * 事件到了浏览器但没有监听器，页面上什么都不会发生（{@code run.standby} 与
 * {@code run.rescan} 就漏过一次）；后端多发一个也不会报错 —— 它只会被前端静默丢弃。
 * 两边都只有「运行时才看得出来」，而运行时没人盯着。
 *
 * <p><b>前端只有一份清单</b>：{@code console/composables/runEvents.ts} 里的 {@code TEXT}
 * 既是文案表也是订阅来源（{@code RUN_EVENT_TYPES = Object.keys(TEXT)}），
 * 所以那一侧「订阅了但没文案」在结构上不可能出现。本测试只需要比对两份：
 * 后端实际 publish 的字面量 ↔ 那份对象的键。
 *
 * <p><b>扫描的已知边界</b>：只认字面量。用变量发事件（{@code publish(run, type, data)}）
 * 扫不出来，所以 {@link #用变量发事件的地方只有两处通道转发} 单独钉住个数 ——
 * 新增一处就会红，而不是静默失去覆盖。
 *
 * <p>不用 ArchUnit：一条 {@code Files.readString} + 正则就够，与
 * {@code RunLogSingleExitTest}、{@code HumanizeSourceGuardTest} 同一种做法。
 */
class SseEventContractTest {

    /**
     * 现有两处都是通道转发而不是事件源：{@code RunService.publish(run, type, data)}
     * 与 {@code RunHost.publish} 委托给 {@code SseHub}。它们把字面量原样传下去。
     */
    private static final int CHANNEL_CALLS = 2;

    @Test
    void 后端发的每个事件前端都订阅_前端订阅的每个事件后端都在发() throws IOException {
        Set<String> backend = scanBackend(repoRoot()).literals();
        Set<String> frontend = declaredByFrontend(repoRoot());

        assertThat(backend)
                .as("后端 publish 的字面量必须与 console/composables/runEvents.ts 的键一一对应"
                        + "（差集说明：左边多=后端发了前端没订阅，右边多=前端订阅了没人发）")
                .containsExactlyInAnyOrderElementsOf(frontend);
    }

    @Test
    void 用变量发事件的地方只有两处通道转发() throws IOException {
        List<String> variableCalls = scanBackend(repoRoot()).variableCalls();

        assertThat(variableCalls)
                .as("新增了用变量发事件的地方：上一条守卫扫不到它。要么改成字面量，"
                        + "要么先确认它确实是通道转发再更新本断言")
                .hasSize(CHANNEL_CALLS);
    }

    // ------------------------------------------------------------------ 扫描

    /** @param literals 字面量事件名（去重） @param variableCalls 变量调用的位置，形如 {@code RunService.java:836} */
    private record Scan(Set<String> literals, List<String> variableCalls) {
    }

    /**
     * 扫后端所有 {@code publish(} 调用。
     *
     * <p>两种写法都要认：{@code host.publish("job.evaluated", …)} 与
     * {@code publish(run, "run.started", …)}。方法声明 {@code void publish(...)} 不是事件源。
     */
    private static Scan scanBackend(Path repoRoot) throws IOException {
        Path src = repoRoot.resolve("server/src/main/java");
        assertThat(src).as("找不到后端源码目录：%s", src).exists();

        Pattern call = Pattern.compile("publish\\(");
        // 可选的第一个实参（run / run.getId()，不含逗号与引号），随后必须是字符串字面量
        Pattern literal = Pattern.compile("^\\s*(?:[^,\"]*,\\s*)?\"([a-z][a-z.]*)\"");

        Set<String> literals = new TreeSet<>();
        List<String> variableCalls = new ArrayList<>();

        try (Stream<Path> files = Files.walk(src)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = call.matcher(text);
                while (m.find()) {
                    int at = m.start();
                    if (at >= 5 && text.startsWith("void ", at - 5)) {
                        continue;
                    }
                    String tail = text.substring(m.end(), Math.min(text.length(), m.end() + 200));
                    Matcher lit = literal.matcher(tail);
                    if (lit.find()) {
                        literals.add(lit.group(1));
                    } else {
                        variableCalls.add(file.getFileName() + ":" + lineOf(text, at));
                    }
                }
            }
        }
        return new Scan(literals, variableCalls);
    }

    /** 前端唯一清单：{@code TEXT} 对象里行首两个空格的键。 */
    private static Set<String> declaredByFrontend(Path repoRoot) throws IOException {
        Path ts = repoRoot.resolve("console/composables/runEvents.ts");
        assertThat(ts).as("找不到前端事件清单：%s", ts).exists();

        Matcher m = Pattern.compile("(?m)^  '([a-z][a-z.]*)':").matcher(
                Files.readString(ts, StandardCharsets.UTF_8));
        Set<String> names = new TreeSet<>();
        while (m.find()) {
            names.add(m.group(1));
        }
        assertThat(names)
                .as("没从 %s 里解析出任何事件名：正则与文件格式已经对不上了", ts)
                .isNotEmpty();
        return names;
    }

    /**
     * 仓库根。<b>mvn 在 {@code server/} 下跑</b>，而前端在仓库根的 {@code console/}，
     * 所以不能直接拿 {@code user.dir} 拼。两种启动位置都认。
     */
    private static Path repoRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        return Files.isDirectory(cwd.resolve("src/main/java")) ? cwd.getParent() : cwd;
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
