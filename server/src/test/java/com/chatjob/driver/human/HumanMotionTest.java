package com.chatjob.driver.human;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 轨迹点集。
 *
 * <p>三个断言各自对应一个具体的故障：点数不对 → 轨迹要么瞬移要么拖慢点击；
 * 末点没落在目标上 → 点偏；到目标的距离不单调 → 曲线越过目标又折回，
 * 那比直线更不自然（真实移动不会先冲过头再回来）。
 */
class HumanMotionTest {

    private static final HumanAim FROM = new HumanAim(120, 800);
    private static final HumanAim TO = new HumanAim(430, 310);

    @Test
    void 点数等于请求的步数且末点就是目标() {
        List<HumanAim> path = HumanMotion.path(FROM, TO, 24, Rng.of(3L));

        assertThat(path).hasSize(24);
        assertThat(path.get(path.size() - 1)).isEqualTo(TO);
    }

    @Test
    void 到目标的距离单调不增() {
        List<HumanAim> path = HumanMotion.path(FROM, TO, 30, Rng.of(11L));

        double previous = Double.MAX_VALUE;
        for (HumanAim point : path) {
            double distance = Math.hypot(point.x() - TO.x(), point.y() - TO.y());
            assertThat(distance).isLessThanOrEqualTo(previous);
            previous = distance;
        }
    }

    @Test
    void 轨迹不是直线() {
        // 直线轨迹上每一点都落在起终两点连线上，叉积恒为 0。这里要求至少有一点明显偏离
        List<HumanAim> path = HumanMotion.path(FROM, TO, 30, Rng.of(5L));
        double dx = TO.x() - FROM.x();
        double dy = TO.y() - FROM.y();
        double maxCross = 0;
        for (HumanAim point : path) {
            double cross = Math.abs((point.x() - FROM.x()) * dy - (point.y() - FROM.y()) * dx) / Math.hypot(dx, dy);
            maxCross = Math.max(maxCross, cross);
        }
        assertThat(maxCross).isGreaterThan(1.0);
    }

    @Test
    void 起点几乎等于目标时只给一个点() {
        List<HumanAim> path = HumanMotion.path(TO, new HumanAim(TO.x() + 0.2, TO.y() - 0.2), 20, Rng.of(1L));

        assertThat(path).containsExactly(new HumanAim(TO.x() + 0.2, TO.y() - 0.2));
    }

    @Test
    void 步数为零按一步处理() {
        assertThat(HumanMotion.path(FROM, TO, 0, Rng.of(1L))).containsExactly(TO);
    }

    @Test
    void 同一种子得到同一条轨迹() {
        assertThat(HumanMotion.path(FROM, TO, 20, Rng.of(77L)))
                .isEqualTo(HumanMotion.path(FROM, TO, 20, Rng.of(77L)));
    }
}
