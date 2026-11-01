package com.chatjob.driver.human;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 输入节奏与错字位置。
 *
 * <p>这三个断言限定了这一层能承诺的范围：间隔必须落在配置区间内（否则参数是死的）、
 * 长停顿必须真的出现（否则"每 8–15 字停一次"只是注释）、错字只对能用单键打出的字符生效
 * （否则会声称一个中文逐键输入的能力，而那件事并不存在）。
 */
class HumanTimingTest {

    private static final HumanizeConfig.Keyboard CONFIG =
            new HumanizeConfig.Keyboard(HumanizeConfig.Range.of(55, 165),
                    HumanizeConfig.Range.of(250, 900), 0.02, 300);

    @Test
    void 每个间隔都在配置区间内或区间加一次长停顿() {
        int[] delays = HumanTiming.delays(200, CONFIG, Rng.of(2026L));

        assertThat(delays).hasSize(200);
        for (int delay : delays) {
            assertThat(delay).isBetween(55, 165 + 900);
        }
    }

    @Test
    void 长停顿按每八到十五字一次的密度出现() {
        int length = 300;
        int[] delays = HumanTiming.delays(length, CONFIG, Rng.of(31L));

        int longPauses = 0;
        for (int delay : delays) {
            // 长停顿是"加上去"的，所以超过字符区间上界就说明这一次加了长停顿
            if (delay > 165) {
                longPauses++;
            }
        }
        // 理论密度是 length/15 到 length/8，留一格容差（窗口边界会让次数差一次）
        assertThat(longPauses).isBetween(length / 15 - 1, length / 8 + 1);
    }

    @Test
    void 概率为零时不打错且不消耗随机数() {
        HumanizeConfig.Keyboard noMistype = new HumanizeConfig.Keyboard(null, null, 0d, 300);
        Rng withMistype = Rng.of(8L);
        Rng withoutMistype = Rng.of(8L);

        assertThat(HumanTiming.mistype("hello world", noMistype, withoutMistype)).isNull();
        // 关掉错字之后，后续取到的随机数应与"从来没考虑过错字"一致：
        // 否则"把错字概率调成 0"仍会改变轨迹，可复现性就变成有条件的东西
        assertThat(HumanAim.gauss(new HumanAim.Box(0, 0, 100, 40),
                new HumanizeConfig.Gauss(0.5, 0.15, 0.4), withMistype))
                .isEqualTo(HumanAim.gauss(new HumanAim.Box(0, 0, 100, 40),
                        new HumanizeConfig.Gauss(0.5, 0.15, 0.4), withoutMistype));
    }

    @Test
    void 概率为一且在可错字符上时一定打错() {
        HumanizeConfig.Keyboard always = new HumanizeConfig.Keyboard(null, null, 1d, 300);

        HumanTiming.Mistype mistype = HumanTiming.mistype("hello world", always, Rng.of(4L));

        assertThat(mistype).isNotNull();
        assertThat(mistype.index()).isBetween(0, "hello world".length() - 2);
        assertThat(mistype.wrong()).isNotEqualTo("hello world".charAt(mistype.index()));
    }

    @Test
    void 纯中文文本不会被打错() {
        HumanizeConfig.Keyboard always = new HumanizeConfig.Keyboard(null, null, 1d, 300);

        // 中文经 insertText 一次性插入，没有"相邻键"可言；这里不假装能错
        assertThat(HumanTiming.mistype("您好我想了解一下这个岗位", always, Rng.of(4L))).isNull();
    }

    @Test
    void 单字符文本没有可错位置() {
        HumanizeConfig.Keyboard always = new HumanizeConfig.Keyboard(null, null, 1d, 300);

        assertThat(HumanTiming.mistype("a", always, Rng.of(4L))).isNull();
    }

    @Test
    void 错字是同一行内的相邻键且大小写跟随原字符() {
        assertThat(HumanTiming.neighbour('d', Rng.of(1L))).isIn('s', 'f');
        assertThat(HumanTiming.neighbour('D', Rng.of(1L))).isIn('S', 'F');
        assertThat(HumanTiming.neighbour('q', Rng.of(1L))).isIn('w');
        assertThat(HumanTiming.neighbour('m', Rng.of(1L))).isIn('n');
    }
}
