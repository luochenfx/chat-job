package com.chatjob.web;

import com.chatjob.store.ConfigStore;
import com.chatjob.store.PlatformConfigChange;
import com.chatjob.store.entity.PlatformConfigEntity;
import com.chatjob.time.FixedTimeSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「没传 = 不动」的口径。
 *
 * <p>请求体直接用 JSON 字面量构造，而不是手写一个 26 个 null 的 record —— 一来参数一旦错位
 * 断言会变得莫名其妙，二来这样走的正是 Jackson 那条绑定路径，「字段缺失绑成 null」这个前提
 * 本身也被测到了。
 *
 * <p>逐字段比较走序列化快照：改造前那个坑（改一个配额重置另外三个）恰恰是「某个字段被悄悄
 * 覆盖」，只断言被改的那个等于没测。
 */
class PlatformConfigMergeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 没连库的实例：merge 只用得到 writeList，不碰任何 mapper */
    private static ConfigStore store() {
        return new ConfigStore(null, null, null, null, null, JSON,
                FixedTimeSource.of("2026-11-04T09:00:00"));
    }

    private static PlatformConfigChange change(String json) throws Exception {
        return JSON.readValue(json, PlatformConfigChange.class);
    }

    private static Map<String, Object> snapshot(PlatformConfigEntity cfg) {
        return JSON.convertValue(cfg, new TypeReference<Map<String, Object>>() {
        });
    }

    /** 每个字段都不是默认值：这样任何一处被覆盖都会在快照里现形 */
    private static PlatformConfigEntity current() {
        PlatformConfigEntity cfg = new PlatformConfigEntity();
        cfg.setId(7L);
        cfg.setPlatform("boss");
        cfg.setEnabled(1);
        cfg.setScanEnabled(1);
        cfg.setWatchEnabled(1);
        cfg.setJobQualityEnabled(1);
        cfg.setWorkLocationAcceptEnabled(1);
        cfg.setCities("[\"杭州\"]");
        cfg.setKeywords("[\"Java\"]");
        cfg.setJobType("全职");
        cfg.setSalary("[\"20-30K\"]");
        cfg.setExperience("[\"3-5年\"]");
        cfg.setDegree("[\"本科\"]");
        cfg.setScale("[\"100-499人\"]");
        cfg.setIndustry("[\"互联网\"]");
        cfg.setStage("[\"C轮\"]");
        cfg.setActiveLevelFloor("今日");
        cfg.setWaitTimeSeconds(7);
        cfg.setDailyDeliveryQuota(11);
        cfg.setDailyEvalLimit(22);
        cfg.setScrollRoundLimit(33);
        cfg.setCombinationTimeLimitSec(1800);
        cfg.setUnreadScanLimit(44);
        cfg.setWatchIntervalSeconds(60);
        cfg.setRepeatChatEnabled(1);
        cfg.setRepeatChatMessages("[\"在吗\"]");
        cfg.setResumeImageEnabled(1);
        cfg.setDutyStartTime("08:30");
        cfg.setDutyEndTime("21:30");
        cfg.setUpdatedAt("2026-11-01T00:00:00");
        return cfg;
    }

    @Test
    void 空请求体不改动任何字段() throws Exception {
        PlatformConfigEntity cfg = current();
        Map<String, Object> before = snapshot(cfg);

        PlatformConfigMerge.merge(store(), cfg, change("{}"));

        assertThat(snapshot(cfg)).isEqualTo(before);
    }

    @Test
    void 同一份改动应用两次不再变化() throws Exception {
        PlatformConfigEntity cfg = current();
        PlatformConfigChange once = change(
                "{\"cities\":[\"上海\"],\"watchIntervalSeconds\":10,\"unreadScanLimit\":999}");

        PlatformConfigMerge.merge(store(), cfg, once);
        Map<String, Object> afterFirst = snapshot(cfg);
        PlatformConfigMerge.merge(store(), cfg, once);

        assertThat(snapshot(cfg)).isEqualTo(afterFirst);
    }

    @Test
    void 只传一个配额时其余字段逐个不变() throws Exception {
        PlatformConfigEntity cfg = current();
        Map<String, Object> before = snapshot(cfg);

        PlatformConfigMerge.merge(store(), cfg, change("{\"dailyDeliveryQuota\":5}"));

        Map<String, Object> after = snapshot(cfg);
        Set<String> changed = new HashSet<>(after.keySet());
        changed.removeIf(key -> Objects.equals(after.get(key), before.get(key)));
        assertThat(changed).containsExactly("dailyDeliveryQuota");
        assertThat(cfg.getDailyDeliveryQuota()).isEqualTo(5);
    }

    @Test
    void 清空列表要显式传空数组() throws Exception {
        PlatformConfigEntity cfg = current();

        PlatformConfigMerge.merge(store(), cfg, change("{\"cities\":[]}"));

        assertThat(cfg.getCities()).isEqualTo("[]");
        // 没提到的那个列表不能被顺带清掉 —— 这正是「空数组」与「没传」必须分开的理由
        assertThat(cfg.getKeywords()).isEqualTo("[\"Java\"]");
    }

    @Test
    void 数值按各自口径归一() throws Exception {
        PlatformConfigEntity cfg = current();

        PlatformConfigMerge.merge(store(), cfg, change("""
                {"watchIntervalSeconds":10,"unreadScanLimit":0,"combinationTimeLimitSec":0}
                """));

        assertThat(cfg.getWatchIntervalSeconds()).isEqualTo(ConfigStore.MIN_WATCH_INTERVAL_SECONDS);
        assertThat(cfg.getUnreadScanLimit()).isEqualTo(ConfigStore.DEFAULT_UNREAD_SCAN_LIMIT);
        // 0 表示不限时间，原样保留而不是回落默认的 6 小时
        assertThat(cfg.getCombinationTimeLimitSec()).isZero();

        PlatformConfigMerge.merge(store(), cfg, change("{\"combinationTimeLimitSec\":99999}"));
        assertThat(cfg.getCombinationTimeLimitSec())
                .isEqualTo(ConfigStore.MAX_COMBINATION_TIME_LIMIT_SEC);
    }

    @Test
    void 岗位质量开关没传时保持原值() throws Exception {
        // 这一列的默认值是关，但「没传」仍然不等于「关」：否则任何一个不提交它的调用方
        // （curl、脚本、旧版本控制台）都会把用户打开的开关悄悄关掉（ADR-0017）
        PlatformConfigEntity cfg = current();

        PlatformConfigMerge.merge(store(), cfg, change("{\"dailyDeliveryQuota\":5}"));

        assertThat(cfg.getJobQualityEnabled()).isEqualTo(1);

        PlatformConfigMerge.merge(store(), cfg, change("{\"jobQualityEnabled\":false}"));

        assertThat(cfg.getJobQualityEnabled()).isZero();
    }

    @Test
    void 自动接受工作地点开关没传时保持原值() throws Exception {
        // 这一列默认关，但「没传」仍然不等于「关」：否则旧版本控制台或 curl 只改一个配额，
        // 就会把用户打开的开关悄悄关掉（ADR-0020）
        PlatformConfigEntity cfg = current();

        PlatformConfigMerge.merge(store(), cfg, change("{\"dailyDeliveryQuota\":5}"));

        assertThat(cfg.getWorkLocationAcceptEnabled()).isEqualTo(1);

        PlatformConfigMerge.merge(store(), cfg, change("{\"workLocationAcceptEnabled\":false}"));

        assertThat(cfg.getWorkLocationAcceptEnabled()).isZero();
    }

    @Test
    void 活跃度下限留空回落到默认() throws Exception {
        PlatformConfigEntity cfg = current();

        PlatformConfigMerge.merge(store(), cfg, change("{\"activeLevelFloor\":\"\"}"));

        // 空串会存成一个不在 ActiveLevel.selectable() 里的值，界面上就是一条空白下拉
        assertThat(cfg.getActiveLevelFloor()).isEqualTo(ConfigStore.DEFAULT_ACTIVE_LEVEL_FLOOR);
    }
}
