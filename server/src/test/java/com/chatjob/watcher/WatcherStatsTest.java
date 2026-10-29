package com.chatjob.watcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 四档状态的边界。这里每一个用例都对应一种「看起来没问题、实际把坏消息说成好消息」的写法。
 */
class WatcherStatsTest {

    @Test
    @DisplayName("阈值 0 → DISABLED，而不是「占比 0% 一切正常」")
    void 阈值零是关闭态而不是正常态() {
        WatcherStats stats = WatcherStats.judge(100, 90, 0, 10);

        assertThat(stats.state()).isEqualTo(WatcherStats.State.DISABLED);
        // 即便 90% 都跳过了，关掉告警就是关掉 —— 但数字仍要如实给出来，让面板能说「关了，但 90%」
        assertThat(stats.pct()).isEqualTo(90);
    }

    @Test
    @DisplayName("样本不足 → INSUFFICIENT，绝不能是 OK")
    void 样本不足不能渲染成正常() {
        WatcherStats stats = WatcherStats.judge(3, 3, 60, 10);

        assertThat(stats.state()).isEqualTo(WatcherStats.State.INSUFFICIENT);
        // 3 条里 3 条都跳过，占比 100% —— 但样本不够，不能据此告警，也不能说正常
        assertThat(stats.pct()).isEqualTo(100);
        assertThat(stats.total()).isEqualTo(3);
        assertThat(stats.minSample()).isEqualTo(10);
    }

    @Test
    @DisplayName("占比等于阈值 → OK（判据是 > 不是 >=）")
    void 恰好等于阈值不告警() {
        WatcherStats stats = WatcherStats.judge(10, 6, 60, 10);

        assertThat(stats.pct()).isEqualTo(60);
        assertThat(stats.state()).isEqualTo(WatcherStats.State.OK);
    }

    @Test
    @DisplayName("占比超阈值 → ALERT")
    void 超过阈值告警() {
        WatcherStats stats = WatcherStats.judge(10, 7, 60, 10);

        assertThat(stats.pct()).isEqualTo(70);
        assertThat(stats.state()).isEqualTo(WatcherStats.State.ALERT);
    }

    @Test
    @DisplayName("样本数刚好达到下限 → 参与判定")
    void 样本数达下限即判定() {
        assertThat(WatcherStats.judge(10, 7, 60, 10).state()).isEqualTo(WatcherStats.State.ALERT);
        assertThat(WatcherStats.judge(9, 9, 60, 10).state()).isEqualTo(WatcherStats.State.INSUFFICIENT);
    }

    @Test
    @DisplayName("分母为 0 不崩，且落 INSUFFICIENT")
    void 没有任何样本时() {
        WatcherStats stats = WatcherStats.judge(0, 0, 60, 10);

        assertThat(stats.pct()).isZero();
        assertThat(stats.state()).isEqualTo(WatcherStats.State.INSUFFICIENT);
    }

    @Test
    @DisplayName("关闭态优先于样本不足：用户关掉告警时不该显示「样本不足」")
    void 关闭态优先级最高() {
        WatcherStats stats = WatcherStats.judge(0, 0, 0, 10);

        assertThat(stats.state()).isEqualTo(WatcherStats.State.DISABLED);
    }

    @Test
    @DisplayName("占比四舍五入到整数，且判定与显示用同一个数字")
    void 占比与判定口径一致() {
        // 1/3 = 33.33% → 显示 33%，阈值 33 → 不告警（若用精确比值则会告警）
        WatcherStats third = WatcherStats.judge(3, 1, 33, 1);
        assertThat(third.pct()).isEqualTo(33);
        assertThat(third.state()).isEqualTo(WatcherStats.State.OK);

        // 2/3 = 66.67% → 显示 67%，阈值 66 → 告警
        WatcherStats twoThirds = WatcherStats.judge(3, 2, 66, 1);
        assertThat(twoThirds.pct()).isEqualTo(67);
        assertThat(twoThirds.state()).isEqualTo(WatcherStats.State.ALERT);
    }

    @Test
    @DisplayName("阈值被夹到 [0,100]，越界配置不让面板显示 120%")
    void 阈值越界被夹住() {
        assertThat(WatcherStats.judge(10, 5, 120, 10).thresholdPct()).isEqualTo(100);
        assertThat(WatcherStats.judge(10, 5, -5, 10).thresholdPct()).isZero();
    }

    @Test
    @DisplayName("分子大于分母属于脏数据，按分母截断而不是算出 >100%")
    void 分子不大于分母() {
        WatcherStats stats = WatcherStats.judge(10, 15, 60, 10);

        assertThat(stats.skipped()).isEqualTo(10);
        assertThat(stats.pct()).isEqualTo(100);
    }
}
