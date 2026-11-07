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
 * 「一次取数的生命周期」只在 {@code console/composables/usePolledData.ts} 一处写定。
 *
 * <p><b>为什么需要这条守卫</b>：改造前这个模块只有概览页一个使用者，其余页面各自写
 * {@code onMounted(取数)}、本地忙碌标记与本地 refresh；加一个「失败保留上一份数据」的口径
 * 要改好几处，漏一处不报错，只表现为某个页面一闪空白。现在概览页（5 秒）、质量页与
 * 岗位页（都只在挂载取一次）共用同一份实现，页面里再写一份同样不会有人发现。
 *
 * <p><b>范围</b>：配置页、系统页、规则页、未读任务页是「多接口合并 + 表单回填 + 局部重拉」，
 * 取数结果要写进可编辑状态，形状不同，仍各自管理；任务页那一轮走 useRunSession。
 * 所以只钉住已经接入的三个页面，不扫全部页面，也不要求全站统一取数。
 *
 * <p><b>扫描的已知边界</b>：只认下面几组字面写法，与 ADR-0013 的守卫边界相同。
 */
class PolledDataSingleSiteTest {

    private static final Path POLLER = Path.of("console/composables/usePolledData.ts");
    private static final List<String> JOINED = List.of(
            "console/pages/index.vue", "console/pages/quality.vue", "console/pages/jobs.vue");

    /** 接入前这三个页面本地的那一套挂载取数写法 */
    private static final List<String> LOCAL_WIRING = List.of(
            "onMounted(", "const busy = ref(false)", "async function refresh()", "setInterval(");

    @Test
    void 已接入的页面不再自己写挂载取数样板() throws IOException {
        List<String> found = new ArrayList<>();
        for (String rel : JOINED) {
            Path file = RepoRoot.of().resolve(rel);
            assertThat(file).as("找不到已接入的页面：%s", file).exists();
            String text = Files.readString(file, StandardCharsets.UTF_8);
            for (String s : LOCAL_WIRING) {
                if (text.contains(s)) found.add(file.getFileName() + " 又写了 " + s);
            }
            assertThat(text)
                    .as("%s 必须从 usePolledData 拿 data / busy / refresh", file.getFileName())
                    .contains("usePolledData(");
        }

        assertThat(found)
                .as("挂载取一次、按节奏重取、失败保留上一份数据请走 usePolledData(取数函数, 间隔毫秒)，"
                        + "间隔传 0 表示不轮询；不要在页面里再写 onMounted / 本地 busy / 本地 refresh")
                .isEmpty();
    }

    @Test
    void 两种节奏与只读的忙碌标记都还在模块里() throws IOException {
        Path poller = RepoRoot.of().resolve(POLLER);
        assertThat(poller).as("找不到取数模块：%s", poller).exists();

        assertThat(Files.readString(poller, StandardCharsets.UTF_8))
                .as("删掉任何一步都会让上一条守卫看着在跑，其实页面又得自己写")
                .contains("export function usePolledData", "data.value = await fetcher()",
                        "if (intervalMs > 0)", "timer = setInterval(refresh, intervalMs)",
                        "clearInterval(timer)", "busy: computed(() => busy.value)");
    }

    @Test
    void 概览页的动作忙碌标记与取数忙碌标记是分开的() throws IOException {
        Path page = RepoRoot.of().resolve("console/pages/index.vue");
        assertThat(Files.readString(page, StandardCharsets.UTF_8))
                .as("动作进行中不能复用轮询的 busy：动作末尾的重读或一次轮询会提前把按钮解锁")
                .contains("const actionBusy = ref(false)", "{ busy: actionBusy }",
                        ":disabled=\"busy || actionBusy\"");
    }
}
