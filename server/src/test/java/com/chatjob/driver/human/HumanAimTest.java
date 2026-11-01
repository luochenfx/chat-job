package com.chatjob.driver.human;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 落点计算。
 *
 * <p>这个类值得单独测，是因为落点的两个错误都不会报错：落在元素外面 → 点到相邻元素，
 * 表现是"投递给了错的人"；全部落在正中心 → 每次点击的坐标完全相同，
 * 一个坐标分布就能看出不是手点的。两条都只能靠断言钉住。
 */
class HumanAimTest {

    private static final HumanizeConfig.Gauss JITTER = new HumanizeConfig.Gauss(0.5, 0.15, 0.4);

    /** 100×40 的元素放在 (200, 500)。内缩 10% 后是 x∈[210,290]、y∈[504,536]。 */
    private static final HumanAim.Box BOX = new HumanAim.Box(200, 500, 100, 40);

    @Test
    void 落点始终在元素内缩百分之十的矩形内() {
        Rng rng = Rng.of(20261101L);
        for (int i = 0; i < 500; i++) {
            HumanAim aim = HumanAim.gauss(BOX, JITTER, rng);
            assertThat(aim.x()).isBetween(BOX.x() + BOX.width() * HumanAim.INSET_RATIO,
                    BOX.x() + BOX.width() * (1 - HumanAim.INSET_RATIO));
            assertThat(aim.y()).isBetween(BOX.y() + BOX.height() * HumanAim.INSET_RATIO,
                    BOX.y() + BOX.height() * (1 - HumanAim.INSET_RATIO));
        }
    }

    @Test
    void 落点围绕中心分散而不是固定一点() {
        Rng rng = Rng.of(7L);
        int samples = 2000;
        double sumX = 0;
        double sumY = 0;
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        for (int i = 0; i < samples; i++) {
            HumanAim aim = HumanAim.gauss(BOX, JITTER, rng);
            sumX += aim.x();
            sumY += aim.y();
            minX = Math.min(minX, aim.x());
            maxX = Math.max(maxX, aim.x());
        }
        // 均值应该贴着元素中心（截断是对称的，不会把分布推向一侧）
        assertThat(sumX / samples).isCloseTo(BOX.centerX(), org.assertj.core.data.Offset.offset(1.5));
        assertThat(sumY / samples).isCloseTo(BOX.centerY(), org.assertj.core.data.Offset.offset(1.5));
        // 而且确实散开了：X 方向跨度要接近整个内缩宽度（80px）
        assertThat(maxX - minX).isGreaterThan(60);
    }

    @Test
    void 没有面积时退回中心点() {
        HumanAim.Box collapsed = new HumanAim.Box(200, 500, 0, 0);

        HumanAim aim = HumanAim.gauss(collapsed, JITTER, Rng.of(1L));

        assertThat(aim).isEqualTo(new HumanAim(200, 500));
    }

    @Test
    void 同一种子得到同一串落点() {
        Rng first = Rng.of(99L);
        Rng second = Rng.of(99L);

        for (int i = 0; i < 50; i++) {
            assertThat(HumanAim.gauss(BOX, JITTER, first)).isEqualTo(HumanAim.gauss(BOX, JITTER, second));
        }
    }

    @Test
    void 种子为零时每次都是新的序列() {
        // 0 表示真随机。取两个实例比较前 5 个落点，只要有一条不同就说明没被固定成同一串
        Rng one = Rng.of(0L);
        Rng two = Rng.of(0L);

        boolean differs = false;
        for (int i = 0; i < 5 && !differs; i++) {
            differs = !HumanAim.gauss(BOX, JITTER, one).equals(HumanAim.gauss(BOX, JITTER, two));
        }
        assertThat(differs).isTrue();
    }
}
