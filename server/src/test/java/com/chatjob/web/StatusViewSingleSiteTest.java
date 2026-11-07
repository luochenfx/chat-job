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
 * 「状态值 → 界面上显示什么」只在 {@code console/composables/useRunStatus.ts} 一份里写定。
 *
 * <p><b>为什么需要这条守卫</b>：这批映射原本散在 7 个页面里，标签与配色还常拆成两个函数。
 * 页面里再抄一份不会报错，只会让同一个状态在两页显示成两种颜色。编译器管不着，浏览器也
 * 不会报错，只有人盯着屏幕才看得出来。
 *
 * <p><b>扫描的已知边界</b>：只认下面两组字面写法。它钉住的是「别把这张表抄回页面」，
 * 不保证清单里的文案读起来对 —— 后者仍靠人看，与 ADR-0013 的守卫边界相同。
 */
class StatusViewSingleSiteTest {

    private static final Path VIEW = Path.of("console/composables/useRunStatus.ts");

    /** 原先各页面本地定义的那批映射函数与表 */
    private static final List<String> LOCAL_MAPPINGS = List.of(
            "function statusText", "function statusTag", "function statusClass",
            "function verdictMark", "function verdictTag", "function reasonText",
            "function activeClass", "function comboView",
            "const COMBO_LABEL", "const REASON_TEXT", "const alertText", "const alertClass");

    /** 页面里不该再出现的 Run 状态字面量；界面按类别分支，不再直接比枚举 */
    private static final List<String> RUN_LITERALS = List.of(
            "'WAITING_DUTY'", "'PAUSED'", "'STANDBY'", "'CREATED'",
            "'QUOTA_REACHED'", "'CIRCUIT_OPEN'", "'LOGIN_LOST'", "'CANCELLED'");

    @Test
    void 页面里不再各自写一份状态映射() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : frontEndSources(RepoRoot.of())) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            for (String s : LOCAL_MAPPINGS) {
                if (text.contains(s)) found.add(file.getFileName() + " 又定义了 " + s);
            }
            for (String s : RUN_LITERALS) {
                if (text.contains(s)) found.add(file.getFileName() + " 又直接比了 " + s);
            }
        }

        assertThat(found)
                .as("状态到标签/配色的映射请写进 console/composables/useRunStatus.ts，"
                        + "界面按 runStatusView().kind 分支；不要在页面里再抄一张表")
                .isEmpty();
    }

    @Test
    void 清单里每个域的取值表都还在() throws IOException {
        Path view = RepoRoot.of().resolve(VIEW);
        assertThat(view).as("找不到状态清单：%s", view).exists();

        assertThat(Files.readString(view, StandardCharsets.UTF_8))
                .as("清单缺了一张域表：删掉某张表会让上一条守卫看着在跑，其实页面又得自己写")
                .contains("const RUN_TABLE", "const DELIVERY_TABLE", "const UNREAD_TASK_TABLE",
                        "const COMBO_TABLE", "const UNMATCHED_REASON", "const BLOCKED_REASON");
    }

    /** 只扫三个源码目录的第一层，清单文件本身排除在外 */
    private static List<Path> frontEndSources(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String dir : List.of("pages", "components", "composables")) {
            Path d = root.resolve("console").resolve(dir);
            assertThat(d).as("找不到前端源码目录：%s", d).isDirectory();
            try (Stream<Path> files = Files.list(d)) {
                files.filter(p -> p.toString().endsWith(".vue") || p.toString().endsWith(".ts"))
                        .filter(p -> !p.getFileName().toString().equals("useRunStatus.ts"))
                        .forEach(out::add);
            }
        }
        assertThat(out).as("没扫到任何前端源文件：目录结构变了，守卫已经失去覆盖").isNotEmpty();
        return out;
    }
}
