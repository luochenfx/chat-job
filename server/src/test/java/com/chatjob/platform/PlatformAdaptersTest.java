package com.chatjob.platform;

import com.chatjob.config.AppProperties;
import com.chatjob.platform.Models.ChatMessage;
import com.chatjob.platform.Models.DeliveryOptions;
import com.chatjob.platform.Models.DeliveryResult;
import com.chatjob.platform.Models.JobDetail;
import com.chatjob.platform.Models.LoginState;
import com.chatjob.platform.Models.ResumeResult;
import com.chatjob.platform.Models.ScrollOutcome;
import com.chatjob.platform.Models.ScrollParams;
import com.chatjob.platform.Models.SearchQuery;
import com.chatjob.platform.Models.UnreadThread;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段 2b：适配器按平台索引的注册表。
 *
 * <p>这一层的三条行为，任何一条错了都只在「两个平台同时跑」时才会暴露，而那正是最难复现的场景：
 * <ul>
 *   <li>取错平台 → 在别人的 profile 上操作页面（症状是随机 {@code Object doesn't exist}）；</li>
 *   <li>未知平台返回 null → 几百行之后炸在 NPE 上，看不出是哪个平台配错；</li>
 *   <li>顺序不稳 → 控制台卡片自己换位置。</li>
 * </ul>
 * 所以用纯单元测试钉住，不需要浏览器、不需要 Spring。手写 fake，不引 mock 框架（项目约定）。
 */
class PlatformAdaptersTest {

    private static AppProperties props(String activePlatform) {
        return new AppProperties(
                new AppProperties.Driver("build/patchright-driver", true),
                new AppProperties.Browser("browser-data", 0, 30000, "auto", ""),
                new AppProperties.SelfCheckProps(false),
                new AppProperties.NotifyProps("", 300),
                activePlatform);
    }

    @Test
    void 按平台取到各自的适配器() {
        PlatformAdapters adapters = new PlatformAdapters(
                List.of(fake("boss"), fake("zhaopin")), props("boss"));

        assertThat(adapters.of("boss").platform()).isEqualTo("boss");
        assertThat(adapters.of("zhaopin").platform()).isEqualTo("zhaopin");
        // 大小写与空格都不该影响寻址：地址栏/脚本里手写的平台名不该因为大小写落到另一个平台
        assertThat(adapters.of(" ZHAOPIN ").platform()).isEqualTo("zhaopin");
    }

    @Test
    void 未指定平台时落到缺省平台() {
        PlatformAdapters adapters = new PlatformAdapters(
                List.of(fake("boss"), fake("zhaopin")), props("zhaopin"));

        assertThat(adapters.of(null).platform()).isEqualTo("zhaopin");
        assertThat(adapters.of("  ").platform()).isEqualTo("zhaopin");
        assertThat(adapters.platformKey(null)).isEqualTo("zhaopin");
    }

    @Test
    void 未装配的平台当场报错而不是返回null() {
        PlatformAdapters adapters = new PlatformAdapters(List.of(fake("boss")), props("boss"));

        assertThatThrownBy(() -> adapters.of("liepin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("liepin")
                .hasMessageContaining("boss");
        assertThat(adapters.existing("liepin")).isEmpty();
    }

    @Test
    void 顺序按声明走且未声明的平台排在末尾() {
        // 注入顺序故意反着来，并且带一个没在 KNOWN 里声明的平台
        PlatformAdapters adapters = new PlatformAdapters(
                List.of(fake("liepin"), fake("zhaopin"), fake("boss")), props("boss"));

        // KNOWN 里声明过的是 boss → zhaopin；liepin 没声明，沉到末尾但仍然可见
        assertThat(adapters.platforms()).containsExactly("boss", "zhaopin", "liepin");
    }

    /**
     * 只实现 {@link PlatformAdapter#platform()} 的手写 fake。
     *
     * <p>不引 mock 框架：这个测试断言的是「按名字索引」，其余方法被调用就说明测试本身跑偏了，
     * 返回 null / 0 会立刻炸出来，比一个安静的 mock 更容易发现错误。
     */
    private static PlatformAdapter fake(String platform) {
        return new PlatformAdapter() {
            @Override
            public String platform() {
                return platform;
            }

            @Override
            public LoginState probeLogin() {
                throw new UnsupportedOperationException("不参与本测试");
            }

            @Override
            public void openLogin() {
                throw new UnsupportedOperationException("不参与本测试");
            }

            @Override
            public void openSearch(SearchQuery query) {
                throw new UnsupportedOperationException("不参与本测试");
            }

            @Override
            public ScrollOutcome scrollUntilEnd(ScrollParams params, BooleanSupplier stop) {
                throw new UnsupportedOperationException("不参与本测试");
            }

            @Override
            public int cardCount() {
                throw new UnsupportedOperationException("不参与本测试");
            }

            @Override
            public JobDetail fetchDetail(int cardIndex) {
                throw new UnsupportedOperationException("不参与本测试");
            }

            @Override
            public DeliveryResult deliver(JobDetail job, String greeting, DeliveryOptions options,
                                          BooleanSupplier stop) {
                throw new UnsupportedOperationException("不参与本测试");
            }

            @Override
            public List<UnreadThread> listUnreadThreads() {
                throw new UnsupportedOperationException("不参与本测试");
            }

            @Override
            public List<ChatMessage> readMessages(UnreadThread thread) {
                throw new UnsupportedOperationException("不参与本测试");
            }

            @Override
            public boolean sendText(UnreadThread thread, String text) {
                throw new UnsupportedOperationException("不参与本测试");
            }

            @Override
            public ResumeResult sendResume(UnreadThread thread) {
                throw new UnsupportedOperationException("不参与本测试");
            }

            @Override
            public void closeThread() {
                throw new UnsupportedOperationException("不参与本测试");
            }
        };
    }
}
