package com.chatjob.web;

import com.chatjob.testing.RepoRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「分段控件 ↔ 当前平台」的双向绑定只在 {@code console/composables/usePlatform.ts} 的
 * {@code picker} 一处写定。
 *
 * <p><b>为什么需要这条守卫</b>：任务页与配置页原来各写一份 {@code computed({ get, set })}，
 * 读的是同一个「当前平台」，空值与「还是当前平台」的拦截只有配置页写了。页面里再抄一份
 * 不会报错，只会让两处控件在无效点击时的行为慢慢分叉（配置页的无效点击会弹草稿确认框）。
 *
 * <p><b>范围</b>：只收双向绑定的骨架。切换前拦什么（配置页拦没保存的草稿）、切换后重拉
 * 什么，仍是各页自己的业务，留在传给 picker 的回调里。任务页的实时视图清理在
 * useRunSession，由 {@link RunSessionSingleSiteTest} 单独钉住，不在这里重复。
 *
 * <p><b>扫描的已知边界</b>：只认下面几组字面写法，与 ADR-0013 的守卫边界相同。
 */
class PlatformPickerSingleSiteTest {

    private static final Path MODULE = Path.of("console/composables/usePlatform.ts");
    private static final List<String> PAGES = List.of(
            "console/pages/task.vue", "console/pages/config.vue");

    /** 接入前两个页面本地的那份双向绑定 */
    private static final List<String> LOCAL_BINDING = List.of(
            "const platformPick = computed({", "get: () => platformSlug.value",
            "set: (value: string) => {");

    @Test
    void 页面里不再自己写分段控件的双向绑定() throws IOException {
        List<String> found = new ArrayList<>();
        for (String rel : PAGES) {
            Path file = RepoRoot.of().resolve(rel);
            assertThat(file).as("找不到页面：%s", file).exists();
            String text = Files.readString(file, StandardCharsets.UTF_8);
            for (String s : LOCAL_BINDING) {
                if (text.contains(s)) found.add(file.getFileName() + " 又写了 " + s);
            }
            assertThat(text)
                    .as("%s 的分段控件必须走 usePlatform 的 picker", file.getFileName())
                    .contains("platformPick = picker(", "v-model=\"platformPick\"");
        }

        assertThat(found)
                .as("请走 usePlatform().picker(onPick)；空值与当前平台的拦截在 picker 里，"
                        + "不要在页面里再写一份 computed({ get, set })")
                .isEmpty();
    }

    @Test
    void 入口与无效值拦截都还在模块里() throws IOException {
        Path module = RepoRoot.of().resolve(MODULE);
        assertThat(module).as("找不到平台模块：%s", module).exists();

        assertThat(Files.readString(module, StandardCharsets.UTF_8))
                .as("删掉入口会让上一条守卫看着在跑，其实页面又得自己写")
                .contains("function picker(", "if (!value || value === platform.value) return",
                        "onPick(value)", "select, picker, known");
    }
}
