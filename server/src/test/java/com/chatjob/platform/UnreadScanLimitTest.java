package com.chatjob.platform;

import com.chatjob.store.ConfigStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 未读扫描上限（{@code platform_config.unread_scan_limit}）。
 *
 * <p>这个类之所以存在：旧代码在 {@code listUnreadThreads} 里写死
 * {@code Math.min(items.count(), 30)}，那段逻辑夹在 Playwright 调用中间，没有任何测试
 * 覆盖得到 —— 于是「列表 35 条、后 5 个人永远收不到回复，且不报错」是在线上靠人发现的。
 * 把「本轮读多少条」抽成纯函数之后，这个故障模式可以用一条断言钉住。
 *
 * <p>{@link ConfigStore#clampUnreadScanLimit(Integer)} 也在这一层验：它与
 * {@link UnreadScanLimit#plan(int, int)} 是同一条语义的两半（一个决定上限、一个决定实际取数），
 * 拆到两个测试类反而要来回对照。
 */
class UnreadScanLimitTest {

    @Test
    void 列表短于上限时全读且不报截断() {
        UnreadScanLimit.Plan plan = UnreadScanLimit.plan(12, 100);

        assertThat(plan.take()).isEqualTo(12);
        assertThat(plan.truncated()).isFalse();
        assertThat(plan.skipped()).isZero();
    }

    @Test
    void 列表超过上限时明确算出漏掉多少条() {
        // 修复前的现场原样复现：列表 35 条、上限写死 30 → 5 个人永远不会被发现
        UnreadScanLimit.Plan plan = UnreadScanLimit.plan(35, 30);

        assertThat(plan.take()).isEqualTo(30);
        assertThat(plan.truncated()).isTrue();
        assertThat(plan.skipped()).isEqualTo(5);
    }

    @Test
    void 正好等于上限不算截断() {
        // 相等时不该告警：否则每轮都刷一条 warn，很快就没人看了
        UnreadScanLimit.Plan plan = UnreadScanLimit.plan(100, 100);

        assertThat(plan.truncated()).isFalse();
        assertThat(plan.skipped()).isZero();
    }

    @Test
    void 上限非法时至少读一条而不是静默不读() {
        assertThat(UnreadScanLimit.plan(10, 0).take()).isEqualTo(1);
        assertThat(UnreadScanLimit.plan(10, -5).take()).isEqualTo(1);
    }

    @Test
    void 列表读不到时取零条() {
        assertThat(UnreadScanLimit.plan(-1, 100).take()).isZero();
        assertThat(UnreadScanLimit.plan(0, 100).truncated()).isFalse();
    }

    @Test
    void 配置归一() {
        assertThat(ConfigStore.clampUnreadScanLimit(null))
                .isEqualTo(ConfigStore.DEFAULT_UNREAD_SCAN_LIMIT);
        assertThat(ConfigStore.clampUnreadScanLimit(0))
                .isEqualTo(ConfigStore.DEFAULT_UNREAD_SCAN_LIMIT);
        assertThat(ConfigStore.clampUnreadScanLimit(-3))
                .isEqualTo(ConfigStore.DEFAULT_UNREAD_SCAN_LIMIT);
        assertThat(ConfigStore.clampUnreadScanLimit(60)).isEqualTo(60);
        assertThat(ConfigStore.clampUnreadScanLimit(9999))
                .isEqualTo(ConfigStore.MAX_UNREAD_SCAN_LIMIT);
    }
}
