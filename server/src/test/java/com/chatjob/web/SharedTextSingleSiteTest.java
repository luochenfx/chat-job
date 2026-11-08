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
 * 「运行模式的说法」「总分钟数的说法」「平台名怎么查」各只有一份实现。
 *
 * <p><b>为什么需要这条守卫</b>：改造前 {@code modeText} 在 runEvents.ts、index.vue、
 * task.vue 三份逐字相同；{@code labelOf} 在 system.vue、UnreadTasks.vue 各重写了一遍
 * 平台清单的查找；「X 小时 Y 分钟」这段说法在 idleText 与 waitText 里各写一遍。页面里
 * 再抄一份不会报错，只会让同一轮 Run 在两页显示成两种模式名、同一个时长显示成两种说法。
 *
 * <p><b>范围</b>：只收共同规则。输入单位换算（秒 / 分钟、取整、夹到 0 以上）与「没有
 * 平台标识时显示什么」是各页自己的语义，仍留在调用处，不塞进共用函数。
 *
 * <p><b>扫描的已知边界</b>：只认下面几组字面写法，与 ADR-0013 的守卫边界相同。
 */
class SharedTextSingleSiteTest {

    private static final List<String> AUTHORITIES = List.of(
            "runEvents.ts", "useActivity.ts", "usePlatform.ts");

    /** 原先散在页面里的那几份副本 */
    private static final List<String> LOCAL_COPIES = List.of(
            "function modeText", "function labelOf", "m === 'DRY_RUN' ? '演练模式'",
            "${h} 小时 ${m} 分钟", "platforms.value.find((p) => p.platform === platform)?.label");

    @Test
    void 页面里不再各自写一份文案实现() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : frontEndSources(RepoRoot.of())) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            for (String s : LOCAL_COPIES) {
                if (text.contains(s)) found.add(file.getFileName() + " 又写了 " + s);
            }
        }

        assertThat(found)
                .as("模式名走 runEvents.ts 的 modeText；时长说法走 useActivity.ts 的 minutesText；"
                        + "平台名走 usePlatform().labelOf。不要在页面里再抄一份")
                .isEmpty();
    }

    @Test
    void 三处入口都还在_四个页面确实接上了() throws IOException {
        Path root = RepoRoot.of();

        assertThat(Files.readString(root.resolve("console/composables/runEvents.ts"), StandardCharsets.UTF_8))
                .contains("export function modeText");
        assertThat(Files.readString(root.resolve("console/composables/useActivity.ts"), StandardCharsets.UTF_8))
                .contains("export function minutesText", "export function idleText",
                        "return minutesText(Math.floor(seconds / 60))");
        assertThat(Files.readString(root.resolve("console/composables/usePlatform.ts"), StandardCharsets.UTF_8))
                .contains("function labelOf(", "labelOf, supports");

        assertThat(Files.readString(root.resolve("console/pages/index.vue"), StandardCharsets.UTF_8))
                .contains("modeText(c.run.mode)");
        assertThat(Files.readString(root.resolve("console/pages/task.vue"), StandardCharsets.UTF_8))
                .contains("modeText(run.mode)", "minutesText(Math.max(0, Math.round(minutes)))");
        assertThat(Files.readString(root.resolve("console/pages/system.vue"), StandardCharsets.UTF_8))
                .contains("ensure, labelOf", "labelOf(platform)");
        assertThat(Files.readString(root.resolve("console/components/UnreadTasks.vue"), StandardCharsets.UTF_8))
                .contains("labelOf: platformLabelOf", "taskLabel(");
    }

    /** 只扫三个源码目录的第一层，三处权威住处排除在外 */
    private static List<Path> frontEndSources(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String dir : List.of("pages", "components", "composables")) {
            Path d = root.resolve("console").resolve(dir);
            assertThat(d).as("找不到前端源码目录：%s", d).isDirectory();
            try (Stream<Path> files = Files.list(d)) {
                files.filter(p -> p.toString().endsWith(".vue") || p.toString().endsWith(".ts"))
                        .filter(p -> !AUTHORITIES.contains(p.getFileName().toString()))
                        .forEach(out::add);
            }
        }
        assertThat(out).as("没扫到任何前端源文件：目录结构变了，守卫已经失去覆盖").isNotEmpty();
        return out;
    }
}
