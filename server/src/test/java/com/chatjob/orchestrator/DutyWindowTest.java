package com.chatjob.orchestrator;

import com.chatjob.store.entity.PlatformConfigEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DutyWindowTest {

    private static DutyWindow window(String start, String end) {
        PlatformConfigEntity cfg = new PlatformConfigEntity();
        cfg.setDutyStartTime(start);
        cfg.setDutyEndTime(end);
        return DutyWindow.of(cfg);
    }

    @Test
    void 当天窗口左闭右开() {
        DutyWindow w = window("09:00", "22:00");
        assertThat(w.contains(LocalTime.of(9, 0))).isTrue();
        assertThat(w.contains(LocalTime.of(21, 59))).isTrue();
        // 22:00:00 整已经在窗外——"结束时间"是收工时刻，不是最后一刻可发时刻
        assertThat(w.contains(LocalTime.of(22, 0))).isFalse();
        assertThat(w.contains(LocalTime.of(8, 59))).isFalse();
        assertThat(w.contains(LocalTime.of(3, 0))).as("凌晨").isFalse();
    }

    @Test
    void 跨天窗口覆盖午夜() {
        DutyWindow w = window("22:00", "06:00");
        assertThat(w.contains(LocalTime.of(22, 0))).isTrue();
        assertThat(w.contains(LocalTime.of(23, 30))).isTrue();
        assertThat(w.contains(LocalTime.of(0, 0))).isTrue();
        assertThat(w.contains(LocalTime.of(5, 59))).isTrue();
        assertThat(w.contains(LocalTime.of(6, 0))).isFalse();
        assertThat(w.contains(LocalTime.of(12, 0))).isFalse();
    }

    @Test
    void 开始等于结束表示全天不限() {
        DutyWindow w = window("09:00", "09:00");
        assertThat(w.alwaysOn()).isTrue();
        assertThat(w.contains(LocalTime.of(3, 0))).isTrue();
        assertThat(w.contains(LocalTime.of(23, 59))).isTrue();
        assertThat(w.describe()).isEqualTo("全天");
    }

    @Test
    void 格式写错按默认窗口处理而非全天() {
        // "9点" 解析不了，必须保守回落到 09:00–22:00，不能当成不限把凌晨放出去
        DutyWindow w = window("9点", "22:00");
        assertThat(w.start()).isEqualTo(LocalTime.of(9, 0));
        assertThat(w.contains(LocalTime.of(3, 0))).isFalse();
    }

    @Test
    void 空值按默认窗口处理() {
        DutyWindow w = window(null, "");
        assertThat(w.start()).isEqualTo(LocalTime.of(9, 0));
        assertThat(w.end()).isEqualTo(LocalTime.of(22, 0));
    }

    @Test
    void 支持带秒的写法() {
        DutyWindow w = window("09:30:00", "18:00:00");
        assertThat(w.start()).isEqualTo(LocalTime.of(9, 30));
        assertThat(w.end()).isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    void 描述文案区分当天与跨天() {
        assertThat(window("09:00", "22:00").describe()).isEqualTo("09:00–22:00");
        assertThat(window("22:00", "06:00").describe()).isEqualTo("22:00–次日 06:00");
    }

    @Test
    void 窗口外给出下一次可启动的提示() {
        DutyWindow w = window("09:00", "22:00");
        assertThat(w.nextOpenHint(LocalTime.of(3, 0))).isEqualTo("今天 09:00 之后可以启动");
        assertThat(w.nextOpenHint(LocalTime.of(23, 0))).isEqualTo("明天 09:00 之后可以启动");
    }

    @Test
    void 接口层严格校验时间格式() {
        assertThat(DutyWindow.normalize("09:00:00", "09:00")).isEqualTo("09:00");
        assertThat(DutyWindow.normalize(null, "22:00")).isEqualTo("22:00");
        assertThatThrownBy(() -> DutyWindow.normalize("9点", "09:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HH:mm");
        assertThatThrownBy(() -> DutyWindow.normalize("25:00", "09:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
