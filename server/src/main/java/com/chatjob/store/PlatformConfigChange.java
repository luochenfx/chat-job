package com.chatjob.store;

import java.util.List;

/**
 * 一次平台配置写回的<b>意图</b>：只有出现在这里的字段才表示「用户改了它」。
 *
 * <p>字段为 {@code null} 一律读作<b>没提到</b>，不是「清空」也不是「回落默认」。因此清空一个
 * 列表要显式传 {@code []}，清空一个字符串要显式传 {@code ""}。这条口径的由来见
 * {@code docs/adr/0010-config-write-back.md}：改造前同一个「没传」在四个方向上各有各的解释
 * （开关变成关、列表变成空、配额回落默认、另两个字段保持原值），用 curl 改一个配额会静默
 * 重置另外三个。
 *
 * <p>为什么这个 record 在 store 包而不是 web 包：它描述的是「配置怎么变」，与
 * {@link ConfigStore#defaults} 是同一件事的两头。合并规则在
 * {@code com.chatjob.web.PlatformConfigMerge}，不在这里 —— 那里要调
 * {@code DutyWindow}，放进 store 会让 store 与 orchestrator 互相依赖。
 *
 * <p>与 JSON 绑定的一个后果：Jackson 把「字段缺失」和「显式 null」都给成 {@code null}，
 * 两者无法区分。这正是「解绑模型」必须走独立端点的原因 —— 用 {@code providerId: null}
 * 表达清除，和「没提交这个字段」是同一份字节。
 */
public record PlatformConfigChange(
        Boolean enabled, Boolean scanEnabled, Boolean watchEnabled, Boolean jobQualityEnabled,
        List<String> cities, List<String> keywords, String jobType,
        List<String> salary, List<String> experience, List<String> degree, List<String> scale,
        List<String> industry, List<String> stage, String activeLevelFloor,
        Integer waitTimeSeconds, Integer dailyDeliveryQuota, Integer dailyEvalLimit,
        Integer scrollRoundLimit, Integer combinationTimeLimitSec, Integer unreadScanLimit,
        Integer watchIntervalSeconds,
        Boolean repeatChatEnabled, List<String> repeatChatMessages, Boolean resumeImageEnabled,
        String dutyStartTime, String dutyEndTime) {
}
