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
 * 时间戳怎么显示只在 {@code console/composables/useTime.ts} 一份里写定。
 *
 * <p><b>为什么需要这条守卫</b>：改造前六个页面各自写 {@code replace('T',' ')} 再 slice 到不同位数，
 * 同一个时刻在概览页、规则页、质量页显示成三种样子。页面里再写一份不会报错，也不会有人
 * 在编译或运行时发现，只有把几个页面并排看才看得出来。
 *
 * <p><b>扫描的已知边界</b>：只认下面这几组字面写法。它钉住的是「别再把格式化写回页面」，
 * 不保证文案读起来对，也不覆盖自己另起名字的函数 —— 与 ADR-0013 的守卫边界相同。
 */
class TimeFormatSingleSiteTest {

    private static final Path VIEW = Path.of("console/composables/useTime.ts");

    /** 原先各页面本地的格式化写法与函数 */
    private static final List<String> LOCAL_FORMATS = List.of(
            "replace('T', ' ')", "toLocaleTimeString", "slice(0, 16)", "slice(5, 16)",
            "function fmt", "function shortTime", "function time(");

    @Test
    void 页面里不再各自格式化时间戳() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : frontEndSources(RepoRoot.of())) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            for (String s : LOCAL_FORMATS) {
                if (text.contains(s)) found.add(file.getFileName() + " 又写了 " + s);
            }
        }

        assertThat(found)
                .as("时间戳显示请走 useTime.ts 的 stamp(ts, style) 或 clockTime(at)，"
                        + "不要在页面里自己 replace / slice")
                .isEmpty();
    }

    @Test
    void 清单里两个入口都还在() throws IOException {
        Path view = RepoRoot.of().resolve(VIEW);
        assertThat(view).as("找不到时间显示清单：%s", view).exists();

        assertThat(Files.readString(view, StandardCharsets.UTF_8))
                .as("删掉入口会让上一条守卫看着在跑，其实页面又得自己写")
                .contains("export function stamp", "export function clockTime",
                        "export function fullTime", "export function relTime");
    }

    /** 只扫三个源码目录的第一层，清单文件本身排除在外 */
    private static List<Path> frontEndSources(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String dir : List.of("pages", "components", "composables")) {
            Path d = root.resolve("console").resolve(dir);
            assertThat(d).as("找不到前端源码目录：%s", d).isDirectory();
            try (Stream<Path> files = Files.list(d)) {
                files.filter(p -> p.toString().endsWith(".vue") || p.toString().endsWith(".ts"))
                        .filter(p -> !p.getFileName().toString().equals("useTime.ts"))
                        .forEach(out::add);
            }
        }
        assertThat(out).as("没扫到任何前端源文件：目录结构变了，守卫已经失去覆盖").isNotEmpty();
        return out;
    }
}
