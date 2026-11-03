package com.chatjob.store;

import com.chatjob.store.entity.PlatformConfigEntity;
import com.chatjob.time.FixedTimeSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台配置里两处「归一 / 默认值」的口径。纯逻辑，不需要 Spring 上下文与数据库。
 *
 * <p>巡检间隔的边界语义和 {@code unreadScanLimit} 不同：越界是「按最近边界收敛」，
 * 不是「回落默认值」。填 10 秒的人想更频繁，收敛到 30 秒是顺着他限一下；
 * 回落 120 会让他以为配置没保存成功，然后反复来调。
 */
class ConfigStoreTest {

    /**
     * 没连库的实例：只用来取 {@code defaults()}。
     *
     * <p>mapper 传 null 是因为这条路径不碰它们 —— 真实的 {@code peekPlatformConfig}
     * 读的是库里的行，而「没配过时的默认值」本身就与库无关。
     */
    private static ConfigStore store() {
        return new ConfigStore(null, null, null, null, null, new ObjectMapper(),
                FixedTimeSource.of("2026-03-15T09:00:00"));
    }

    @Test
    void 新平台默认启用扫描与值守() {
        // 这两个默认值必须与 V14 的列默认值一致：它是「库里还没有这一行」时唯一的值来源。
        // 分叉不会立刻报错（读法都把 null 当开），但之后任何一处把 null 当关，
        // 就会出现「刚建出来的平台读出来是关的、库里却是 1」
        PlatformConfigEntity cfg = store().defaults("boss");
        assertThat(cfg.getScanEnabled()).isEqualTo(1);
        assertThat(cfg.getWatchEnabled()).isEqualTo(1);
    }

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

    @Test
    void 单组合时间上限没配过按新默认() {
        // 默认值必须大于「300 张 × 60 秒 = 18000 秒」，否则一个组合永远跑不完；
        // 与 V17 迁移里写进已有行的值也必须是同一个数，否则新建平台和升级上来的平台行为不同
        assertThat(ConfigStore.normalizeCombinationTimeLimit(null))
                .isEqualTo(ConfigStore.DEFAULT_COMBINATION_TIME_LIMIT_SEC)
                .isGreaterThan(18000);
    }

    @Test
    void 时间上限填零或负数表示不限() {
        // 两种写法都表示「不限时间」，原样保留而不是改写成另一个数：
        // 回读要和用户填的一致，判据统一用「> 0 才算设了上限」
        assertThat(ConfigStore.normalizeCombinationTimeLimit(0)).isZero();
        assertThat(ConfigStore.normalizeCombinationTimeLimit(-1)).isEqualTo(-1);
    }

    @Test
    void 时间上限低于下限收敛到一分钟() {
        // 正数才收敛；1 秒这种填法等于「每个组合看一张卡就走」，收敛到 60 秒
        assertThat(ConfigStore.normalizeCombinationTimeLimit(1))
                .isEqualTo(ConfigStore.MIN_COMBINATION_TIME_LIMIT_SEC);
    }

    @Test
    void 时间上限高于上限收敛到一天() {
        assertThat(ConfigStore.normalizeCombinationTimeLimit(999999))
                .isEqualTo(ConfigStore.MAX_COMBINATION_TIME_LIMIT_SEC);
    }

    @Test
    void 时间上限区间内原样返回() {
        assertThat(ConfigStore.normalizeCombinationTimeLimit(1200)).isEqualTo(1200);
        assertThat(ConfigStore.normalizeCombinationTimeLimit(21600)).isEqualTo(21600);
        assertThat(ConfigStore.normalizeCombinationTimeLimit(86400)).isEqualTo(86400);
    }

    @Test
    void 新建平台写的是新默认值() {
        PlatformConfigEntity cfg = store().defaults("boss");
        assertThat(cfg.getCombinationTimeLimitSec())
                .isEqualTo(ConfigStore.DEFAULT_COMBINATION_TIME_LIMIT_SEC);
    }
}
