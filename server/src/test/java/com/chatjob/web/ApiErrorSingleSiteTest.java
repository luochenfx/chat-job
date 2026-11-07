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
 * 请求失败后的提示与忙碌标记只在 {@code console/composables/useApi.ts} 一处处理。
 *
 * <p><b>为什么需要这条守卫</b>：改造前 37 个函数各写一遍
 * {@code try / catch (e: any) { show(e.message, true) } / finally { busy = false }}，
 * 一模一样。以后要改错误口径（例如某类错误不弹提示），漏一个页面不会报错，
 * 只会让那个页面继续用旧口径，编译和运行时都发现不了。
 *
 * <p><b>扫描的已知边界</b>：只认下面几组字面写法。它钉住的是「别再把错误处置写回页面」，
 * 不保证每个动作的成功文案读起来对 —— 后者仍靠人看，与 ADR-0013 的守卫边界相同。
 * 字典加载那种 {@code catch {}}（无参数、自己降级成空列表）不在收拢范围内。
 */
class ApiErrorSingleSiteTest {

    private static final Path CLIENT = Path.of("console/composables/useApi.ts");

    // 不扫 ".value = false"：页面里关弹层、收起明细也在写它，会把正常代码一起拦下
    private static final List<String> LOCAL_HANDLING = List.of(
            "catch (e: any)", "show(e.message, true)", "} finally {");

    @Test
    void 页面里不再自己接请求错误() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : frontEndSources(RepoRoot.of())) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            for (String s : LOCAL_HANDLING) {
                if (text.contains(s)) found.add(file.getFileName() + " 又写了 " + s);
            }
        }

        assertThat(found)
                .as("请求失败后的提示与忙碌标记请走 useApi.ts 的 attempt(work, { busy })，"
                        + "不要在页面里再写 try/catch/finally")
                .isEmpty();
    }

    @Test
    void 收拢后的入口还在() throws IOException {
        Path client = RepoRoot.of().resolve(CLIENT);
        assertThat(client).as("找不到请求模块：%s", client).exists();

        assertThat(Files.readString(client, StandardCharsets.UTF_8))
                .as("删掉 attempt 会让上一条守卫看着在跑，其实错误提示已经没人接了")
                .contains("async function attempt", "async function call", "show(e.message, true)");
    }

    /** 只扫三个源码目录的第一层，请求模块本身排除在外 */
    private static List<Path> frontEndSources(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String dir : List.of("pages", "components", "composables")) {
            Path d = root.resolve("console").resolve(dir);
            assertThat(d).as("找不到前端源码目录：%s", d).isDirectory();
            try (Stream<Path> files = Files.list(d)) {
                files.filter(p -> p.toString().endsWith(".vue") || p.toString().endsWith(".ts"))
                        .filter(p -> !p.getFileName().toString().equals("useApi.ts"))
                        .forEach(out::add);
            }
        }
        assertThat(out).as("没扫到任何前端源文件：目录结构变了，守卫已经失去覆盖").isNotEmpty();
        return out;
    }
}
