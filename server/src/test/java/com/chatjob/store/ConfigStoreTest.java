package com.chatjob.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 巡检间隔的归一规则。纯函数，不需要 Spring 上下文。
 *
 * <p>这里的边界语义和 {@code unreadScanLimit} 不同：越界是「按最近边界收敛」，
 * 不是「回落默认值」。填 10 秒的人想更频繁，收敛到 30 秒是顺着他限一下；
 * 回落 120 会让他以为配置没保存成功，然后反复来调。
 */
class ConfigStoreTest {

    @Test
    void 没配过按默认一百二十秒() {
        assertThat(ConfigStore.clampWatchInterval(null))
                .isEqualTo(ConfigStore.DEFAULT_WATCH_INTERVAL_SECONDS);
    }

    @Test
    void 低于下限收敛到三十秒() {
        assertThat(ConfigStore.clampWatchInterval(10))
                .isEqualTo(ConfigStore.MIN_WATCH_INTERVAL_SECONDS);
        assertThat(ConfigStore.clampWatchInterval(0))
                .isEqualTo(ConfigStore.MIN_WATCH_INTERVAL_SECONDS);
    }

    @Test
    void 高于上限收敛到六百秒() {
        assertThat(ConfigStore.clampWatchInterval(900))
                .isEqualTo(ConfigStore.MAX_WATCH_INTERVAL_SECONDS);
    }

    @Test
    void 区间内原样返回() {
        assertThat(ConfigStore.clampWatchInterval(30)).isEqualTo(30);
        assertThat(ConfigStore.clampWatchInterval(300)).isEqualTo(300);
        assertThat(ConfigStore.clampWatchInterval(600)).isEqualTo(600);
    }
}
