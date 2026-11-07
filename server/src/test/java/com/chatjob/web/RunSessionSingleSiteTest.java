package com.chatjob.web;

import com.chatjob.testing.RepoRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「当前平台这一轮 Run 的实时视图」的连接与顺序只在
 * {@code console/composables/useRunSession.ts} 一份里写定。
 *
 * <p><b>为什么需要这条守卫</b>：改造前 {@code task.vue} 自己写了取快照、3 秒轮询、
 * 请求回来核对平台有没有变、换 Run 时先清事件再开连接、日志按 id 续拉、切平台前
 * 七项清零。这些顺序全部只存在于注释里：漏一步不会报错，只会让人看到另一个平台的
 * 日志，或者一条本该断开却仍显示「已连接」的连接。页面里再写一份，同样不会报错。
 *
 * <p><b>扫描的已知边界</b>：只认下面几组字面写法。它钉住的是「别把连接逻辑搬回页面」，
 * 不保证每一步的先后读起来对 —— 后者靠等价验证脚本，与 ADR-0013 的守卫边界相同。
 * 概览页的 {@code useTickClock()} 与 {@code usePolledData} 是另一条取数路径，不在范围内。
 */
class RunSessionSingleSiteTest {

    private static final Path SESSION = Path.of("console/composables/useRunSession.ts");
    private static final Path TASK_PAGE = Path.of("console/pages/task.vue");

    /** 原先任务页本地的那一套连接写法 */
    private static final List<String> LOCAL_WIRING = List.of(
            "'/runs/current'", "afterId: lastLogId", "pullLogs(", "lastLogId", "currentRunId",
            "resetForPlatformChange", "setInterval(refresh, 3000)", "= useRunStream()",
            "open(cur.run.id)");

    @Test
    void 页面里不再自己装配这一轮的连接() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : frontEndSources(RepoRoot.of())) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            for (String s : LOCAL_WIRING) {
                if (text.contains(s)) found.add(file.getFileName() + " 又写了 " + s);
            }
        }

        assertThat(found)
                .as("这一轮的快照、事件流、日志续拉与切平台清理请走 useRunSession(platform)，"
                        + "不要在页面里再写一遍连接顺序")
                .isEmpty();
    }

    @Test
    void 连接顺序都还在模块里_任务页确实接上了它() throws IOException {
        Path session = RepoRoot.of().resolve(SESSION);
        assertThat(session).as("找不到实时视图模块：%s", session).exists();

        assertThat(Files.readString(session, StandardCharsets.UTF_8))
                .as("删掉任何一步都会让上一条守卫看着在跑，其实页面又得自己按顺序写")
                .contains("export function useRunSession", "useRunStream()", "useTickClock()",
                        "await ensure()", "setInterval(refresh, 3000)", "watch(platform,",
                        "afterId: lastLogId", "open(cur.run.id)", "resetForPlatformChange",
                        "activityView(run.value", "clearLogs");

        assertThat(Files.readString(RepoRoot.of().resolve(TASK_PAGE), StandardCharsets.UTF_8))
                .as("任务页必须从模块拿实时视图，不能自己留一份本地副本")
                .contains("useRunSession(platformSlug)");
    }

    /** 只扫三个源码目录的第一层，实时视图模块本身排除在外 */
    private static List<Path> frontEndSources(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String dir : List.of("pages", "components", "composables")) {
            Path d = root.resolve("console").resolve(dir);
            assertThat(d).as("找不到前端源码目录：%s", d).isDirectory();
            try (Stream<Path> files = Files.list(d)) {
                files.filter(p -> p.toString().endsWith(".vue") || p.toString().endsWith(".ts"))
                        .filter(p -> !p.getFileName().toString().equals("useRunSession.ts"))
                        .forEach(out::add);
            }
        }
        assertThat(out).as("没扫到任何前端源文件：目录结构变了，守卫已经失去覆盖").isNotEmpty();
        return out;
    }
}
