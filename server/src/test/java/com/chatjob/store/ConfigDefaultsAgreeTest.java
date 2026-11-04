package com.chatjob.store;

import com.chatjob.testing.RepoRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置页的初始值必须与后端缺省一致。
 *
 * <p><b>为什么需要这条守卫</b>：这批数字跨了语言，编译器管不着。前端
 * {@code defaultPlatformConfig()} 在「配置还没加载回来」与「请求失败」两种情况下都会把它显示给用户；
 * 后端用同一批数字建行（{@code ConfigStore.readOrCreate}）并在读到 {@code null} 时回退。
 * 只改一处不会报错，只会让「界面显示 30、实际按 50 跑」这种偏差活到有人盯日志那天。
 *
 * <p>后端一侧的值全部取自 {@link ConfigStore} 的常量，测试里不再抄数字 ——
 * 否则这条守卫自己就变成第五处住所。
 *
 * <p><b>不比对 SQL 的列 DEFAULT</b>：表列是 NOT NULL，而建行时每个字段都由
 * {@code ConfigStore} 显式赋值，所以迁移里的 DEFAULT 只在「手工 INSERT 少写一列」时生效，
 * 不参与运行。把它拉进来只会让守卫多依赖一种格式，换不来实际保护。
 */
class ConfigDefaultsAgreeTest {

    private static final Map<String, Integer> BACKEND = Map.of(
            "waitTimeSeconds", ConfigStore.DEFAULT_WAIT_TIME_SECONDS,
            "dailyDeliveryQuota", ConfigStore.DEFAULT_DAILY_DELIVERY_QUOTA,
            "dailyEvalLimit", ConfigStore.DEFAULT_DAILY_EVAL_LIMIT,
            "scrollRoundLimit", ConfigStore.DEFAULT_SCROLL_ROUND_LIMIT,
            "combinationTimeLimitSec", ConfigStore.DEFAULT_COMBINATION_TIME_LIMIT_SEC,
            "unreadScanLimit", ConfigStore.DEFAULT_UNREAD_SCAN_LIMIT,
            "watchIntervalSeconds", ConfigStore.DEFAULT_WATCH_INTERVAL_SECONDS);

    @Test
    void 配置页的初始值与后端缺省一致() throws IOException {
        Map<String, Integer> frontend = frontendDefaults(RepoRoot.of());

        Map<String, Integer> drifted = new TreeMap<>();
        BACKEND.forEach((field, backendValue) -> {
            Integer shown = frontend.get(field);
            if (!backendValue.equals(shown)) {
                drifted.put(field, shown);
            }
        });

        assertThat(drifted)
                .as("这些字段在前端 defaultPlatformConfig() 里与后端常量不一致"
                        + "（值=前端当前写的，null=前端没有这个字段）")
                .isEmpty();
    }

    /** {@code defaultPlatformConfig()} 里形如 {@code   waitTimeSeconds: 10,} 的行。 */
    private static Map<String, Integer> frontendDefaults(Path repoRoot) throws IOException {
        Path vue = repoRoot.resolve("console/pages/config.vue");
        assertThat(vue).as("找不到配置页：%s", vue).exists();

        Matcher m = Pattern.compile("(?m)^    '?([a-zA-Z][a-zA-Z0-9]*)'?:\\s*(\\d+),\\s*$")
                .matcher(Files.readString(vue, StandardCharsets.UTF_8));
        Map<String, Integer> out = new TreeMap<>();
        while (m.find()) {
            out.put(m.group(1), Integer.parseInt(m.group(2)));
        }
        assertThat(out)
                .as("没从 %s 里解析出任何初始值：正则与文件格式已经对不上了", vue)
                .isNotEmpty();
        return out;
    }
}
