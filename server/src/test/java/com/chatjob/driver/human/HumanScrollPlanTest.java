package com.chatjob.driver.human;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 滚动步长序列。
 *
 * <p>关键的一条是"累计和恰好等于目标"：差几百像素意味着列表没滚到位，
 * 到底判据（数卡片）会在轮数上限处失败，而日志只会显示"轮数用尽"，看不出是少滚了。
 */
class HumanScrollPlanTest {

    private static final HumanizeConfig.Range RATIO = HumanizeConfig.Range.of(0.35, 0.85);
    private static final int VIEWPORT = 900;

    @Test
    void 累计位移恰好等于目标() {
        int[] steps = HumanScrollPlan.steps(2400, VIEWPORT, RATIO, Rng.of(6L));

        assertThat(sum(steps)).isEqualTo(2400);
    }

    @Test
    void 单步不超过视口高乘以比例上限() {
        int maxStep = (int) Math.round(0.85 * VIEWPORT);

        int[] steps = HumanScrollPlan.steps(5000, VIEWPORT, RATIO, Rng.of(9L));

        for (int step : steps) {
            assertThat(Math.abs(step)).as("单步位移").isLessThanOrEqualTo(maxStep);
        }
    }

    @Test
    void 步数不少于按最大步长估算的下限() {
        // 5000px / 765px ≈ 6.5 → 至少 7 步。步数偏少说明单步被放大了，滚轮看起来像跳转
        int[] steps = HumanScrollPlan.steps(5000, VIEWPORT, RATIO, Rng.of(9L));

        assertThat(steps.length).isGreaterThanOrEqualTo(7);
    }

    @Test
    void 向上滚动给出负步长() {
        int[] steps = HumanScrollPlan.steps(-800, VIEWPORT, RATIO, Rng.of(2L));

        for (int step : steps) {
            assertThat(step).isNegative();
        }
        assertThat(sum(steps)).isEqualTo(-800);
    }

    @Test
    void 位移为零时没有步长() {
        assertThat(HumanScrollPlan.steps(0, VIEWPORT, RATIO, Rng.of(2L))).isEmpty();
    }

    @Test
    void 目标小于一步时一步走完且不超上限() {
        int[] steps = HumanScrollPlan.steps(120, VIEWPORT, RATIO, Rng.of(2L));

        assertThat(sum(steps)).isEqualTo(120);
        assertThat(steps[0]).isLessThanOrEqualTo(120);
    }

    @Test
    void 视口高不可信时用备用尺寸而不是产生零步长() {
        int[] steps = HumanScrollPlan.steps(1000, 0, RATIO, Rng.of(2L));

        for (int step : steps) {
            assertThat(step).isNotZero();
        }
        assertThat(sum(steps)).isEqualTo(1000);
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }
}
