package com.chatjob.evaluate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveLevelTest {

    @Test
    void 具体档位优先于泛化词组() {
        assertThat(ActiveLevel.of("2月内活跃")).isEqualTo(ActiveLevel.TWO_MONTHS);
        assertThat(ActiveLevel.of("本月活跃")).isEqualTo(ActiveLevel.THIS_MONTH);
        assertThat(ActiveLevel.of("2周内活跃")).isEqualTo(ActiveLevel.TWO_WEEKS);
        assertThat(ActiveLevel.of("3日内活跃")).isEqualTo(ActiveLevel.THREE_DAYS);
        assertThat(ActiveLevel.of("半年前活跃")).isEqualTo(ActiveLevel.HALF_YEAR);
    }

    @Test
    void 常见文案都能落到正确档位() {
        assertThat(ActiveLevel.of("在线")).isEqualTo(ActiveLevel.ONLINE);
        assertThat(ActiveLevel.of("刚刚活跃")).isEqualTo(ActiveLevel.JUST_NOW);
        assertThat(ActiveLevel.of("今日活跃")).isEqualTo(ActiveLevel.TODAY);
        assertThat(ActiveLevel.of("本周活跃")).isEqualTo(ActiveLevel.THIS_WEEK);
    }

    @Test
    void 未知文案不参与硬过滤() {
        ActiveLevel unknown = ActiveLevel.of("很久没来了");
        assertThat(unknown).isEqualTo(ActiveLevel.UNKNOWN);
        // 未知档位即使下限配成「在线」也不应被过滤掉 —— 平台改文案时宁可放过
        assertThat(ActiveLevel.isBelowFloor(unknown, ActiveLevel.ONLINE)).isFalse();
    }

    @Test
    void 低于下限才过滤_等于下限不过滤() {
        assertThat(ActiveLevel.isBelowFloor(ActiveLevel.TWO_MONTHS, ActiveLevel.THIS_MONTH)).isTrue();
        assertThat(ActiveLevel.isBelowFloor(ActiveLevel.THIS_MONTH, ActiveLevel.THIS_MONTH)).isFalse();
        assertThat(ActiveLevel.isBelowFloor(ActiveLevel.ONLINE, ActiveLevel.THIS_MONTH)).isFalse();
    }

    @Test
    void 不限表示不过滤() {
        assertThat(ActiveLevel.fromLabel("不限")).isNull();
        assertThat(ActiveLevel.isBelowFloor(ActiveLevel.HALF_YEAR, null)).isFalse();
    }

    @Test
    void 空值当作未知() {
        assertThat(ActiveLevel.of(null)).isEqualTo(ActiveLevel.UNKNOWN);
        assertThat(ActiveLevel.of("  ")).isEqualTo(ActiveLevel.UNKNOWN);
    }
}
