package com.chatjob.driver.human;

import com.chatjob.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 参数绑定。
 *
 * <p>为什么要在这一阶段就测：参数分两处声明——yaml 里写一遍（给人看），
 * {@link HumanizeConfig} 里写一遍（缺项时生效）。两处漂移的表现是"我明明调了参数，
 * 行为没变"或者反过来，而这两种都只能靠读代码发现。这里用真实的
 * {@code ApplicationContextRunner} 绑一遍，比对的就是"yaml 缺项时到底取到了什么"。
 */
class HumanizeConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfig.class);

    @Configuration
    @EnableConfigurationProperties(AppProperties.class)
    static class BindingConfig {
    }

    @Test
    void 整段缺失时取到停用加默认值() {
        runner.run(context -> {
            HumanizeConfig config = context.getBean(AppProperties.class).humanize();

            // 缺省必须是停用：装了但没配的情况下，行为不能悄悄变化
            assertThat(config.enabled()).isFalse();
            assertThat(config.seed()).isZero();
            assertThat(config.mouse().steps()).isEqualTo(HumanizeConfig.Range.of(18, 34));
            assertThat(config.mouse().jitter()).isEqualTo(new HumanizeConfig.Gauss(0.5, 0.15, 0.4));
            assertThat(config.mouse().driftPx()).isEqualTo(8);
            assertThat(config.mouse().driftRatio()).isEqualTo(0.25);
            assertThat(config.mouse().realignRounds()).isEqualTo(2);
            assertThat(config.mouse().holdMs()).isEqualTo(HumanizeConfig.Range.of(40, 120));
            assertThat(config.keyboard().charDelayMs()).isEqualTo(HumanizeConfig.Range.of(55, 165));
            assertThat(config.keyboard().thinkingPauseMs()).isEqualTo(HumanizeConfig.Range.of(250, 900));
            assertThat(config.keyboard().mistypeChance()).isEqualTo(0.02);
            assertThat(config.keyboard().verifySettleMs()).isEqualTo(300);
            assertThat(config.scroll().stepRatio()).isEqualTo(HumanizeConfig.Range.of(0.35, 0.85));
            assertThat(config.scroll().stepIntervalMs()).isEqualTo(HumanizeConfig.Range.of(90, 260));
            assertThat(config.warmUp().enabled()).isTrue();
            assertThat(config.warmUp().maxDurationMs()).isEqualTo(3000);
            assertThat(config.pacing().betweenActionsMs()).isEqualTo(HumanizeConfig.Range.of(400, 900));
        });
    }

    @Test
    void 代码默认值与空配置的绑定结果一致() {
        runner.run(context -> {
            HumanizeConfig bound = context.getBean(AppProperties.class).humanize();

            // 两个来源（注解/规范构造器 与 defaults()）必须产出同一份值：
            // 不一致的后果是"yaml 里删掉一段"与"整段都不写"得到不同参数
            assertThat(bound).isEqualTo(HumanizeConfig.defaults());
        });
    }

    @Test
    void 只改一项时其余仍是默认值() {
        runner.withPropertyValues(
                        "chat-job.humanize.enabled=true",
                        "chat-job.humanize.mouse.drift-px=12",
                        "chat-job.humanize.mouse.steps.min=10",
                        "chat-job.humanize.mouse.steps.max=12",
                        "chat-job.humanize.keyboard.mistype-chance=0")
                .run(context -> {
                    HumanizeConfig config = context.getBean(AppProperties.class).humanize();

                    assertThat(config.enabled()).isTrue();
                    assertThat(config.mouse().driftPx()).isEqualTo(12);
                    assertThat(config.mouse().steps()).isEqualTo(HumanizeConfig.Range.of(10, 12));
                    assertThat(config.keyboard().mistypeChance()).isZero();
                    // 没被覆盖的保持默认
                    assertThat(config.mouse().realignRounds()).isEqualTo(2);
                    assertThat(config.scroll().stepRatio()).isEqualTo(HumanizeConfig.Range.of(0.35, 0.85));
                });
    }

    @Test
    void 区间写反时构造期交换而不是抛异常() {
        HumanizeConfig.Range swapped = HumanizeConfig.Range.of(900, 250);

        assertThat(swapped.min()).isEqualTo(250);
        assertThat(swapped.max()).isEqualTo(900);
        assertThat(swapped.minInt()).isEqualTo(250);
        assertThat(swapped.maxInt()).isEqualTo(900);
    }

    @Test
    void 分组整段缺失时按默认值补齐而不是留空() {
        // 直接构造（不经过 Spring）：AppProperties 的规范构造器负责把 null 换成默认值
        AppProperties props = new AppProperties(null, null, null, null, "boss", null);

        assertThat(props.humanize()).isEqualTo(HumanizeConfig.defaults());
        assertThat(props.humanize().mouse().steps()).isEqualTo(HumanizeConfig.Range.of(18, 34));
    }
}
