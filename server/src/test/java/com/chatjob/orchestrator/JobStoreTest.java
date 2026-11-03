package com.chatjob.orchestrator;

import com.chatjob.time.FixedTimeSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「已判定不投」的 24 小时冷却。
 *
 * <p>这条判据决定「同一个岗位今天要不要再花一次评估」。它没有 mapper 参与，唯一的输入是
 * 时间戳与注入的时钟，所以直连这个方法测；走 {@code decide()} 需要一整套 mapper 替身，
 * 而这里要验的是时间策略，不是查询。
 */
class JobStoreTest {

    private static final FixedTimeSource TIME = FixedTimeSource.of("2026-03-15T09:00:00");

    private final JobStore store = new JobStore(null, null, TIME);

    @Test
    void 从没评估过的岗位不在冷却期() {
        assertThat(store.withinHours(null, 24)).isFalse();
    }

    @Test
    void 窗口内评估过的岗位在冷却期() {
        assertThat(store.withinHours("2026-03-15T08:00:00", 24)).isTrue();
        // 边界：差一分钟还在窗口内
        assertThat(store.withinHours("2026-03-14T09:01:00", 24)).isTrue();
    }

    @Test
    void 超过窗口可以重新评估() {
        assertThat(store.withinHours("2026-03-14T08:59:00", 24)).isFalse();
        assertThat(store.withinHours("2026-03-10T09:00:00", 24)).isFalse();
    }

    @Test
    void 时间戳读不出来时按仍在冷却处理() {
        // 一律保守：读不懂的时间不当作「已经过期」，否则同一岗位会被重复投递
        assertThat(store.withinHours("2026-03-15 08:00:00", 24)).isTrue();   // 空格分隔的旧格式
        assertThat(store.withinHours("garbage", 24)).isTrue();
        assertThat(store.withinHours("", 24)).isTrue();
    }
}
