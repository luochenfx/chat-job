package com.chatjob.web;

import com.chatjob.orchestrator.DutyWindow;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.PlatformConfigChange;
import com.chatjob.store.entity.PlatformConfigEntity;

/**
 * 把一次配置写回合并进当前行。<b>「没传 = 不动」的唯一实现处。</b>
 *
 * <p>改造前这一套规则散在控制器里，同一个 {@code null} 有四套读法：{@code enabled} 变成关、
 * 列表变成空、配额回落默认值、{@code unreadScanLimit} 与 {@code watchIntervalSeconds} 保持原值。
 * 后两者是后来逐个打的补丁（注释里都留着一句「未传则保持原值」），所以「哪些字段安全」要靠
 * 记住而不是读出来。收成一个函数之后，这条规则只有一个地方会写错。
 *
 * <p><b>为什么不在 {@code ConfigStore} 里</b>：这里要调 {@link DutyWindow#normalize}（在
 * {@code orchestrator} 包），而 {@code orchestrator} 已经大量依赖 {@code store} —— 放过去就是
 * 包级双向依赖。合并规则是纯逻辑，放在「不需要环的那一侧」与 ADR-0009 把扫描判据放进
 * {@code scan} 包是同一个取舍。
 *
 * <p>写时归一在这里做；读侧（{@link ConfigController#platformConfig}）仍各调一次 {@code clamp}，
 * 因为库里的值可能是手工改的。两层都留着不是重复：这一层保证「存进去的合法」，那一层保证
 * 「读出来的合法」。
 */
final class PlatformConfigMerge {

    private PlatformConfigMerge() {
    }

    /**
     * @param store   只用来把列表编码成 JSON 数组，不读库
     * @param current 当前行；<b>就地修改并返回</b>，调用方拿到的就是写回去的实体
     * @param change  用户这次的改动；{@code null} 字段表示没提到
     */
    static PlatformConfigEntity merge(ConfigStore store, PlatformConfigEntity current,
                                      PlatformConfigChange change) {
        // 三枚开关：只有显式给了布尔值才动。null 不表示「关」—— 改造前它表示关，
        // 于是漏传一个 enabled 就把整个平台停掉了
        if (change.enabled() != null) {
            current.setEnabled(change.enabled() ? 1 : 0);
        }
        if (change.scanEnabled() != null) {
            current.setScanEnabled(change.scanEnabled() ? 1 : 0);
        }
        if (change.watchEnabled() != null) {
            current.setWatchEnabled(change.watchEnabled() ? 1 : 0);
        }

        // 筛选参数：传 [] 是清空，不传是保持。两者必须能分开，否则「只改配额」会顺手清掉城市
        if (change.cities() != null) {
            current.setCities(store.writeList(change.cities()));
        }
        if (change.keywords() != null) {
            current.setKeywords(store.writeList(change.keywords()));
        }
        if (change.jobType() != null) {
            current.setJobType(change.jobType());
        }
        if (change.salary() != null) {
            current.setSalary(store.writeList(change.salary()));
        }
        if (change.experience() != null) {
            current.setExperience(store.writeList(change.experience()));
        }
        if (change.degree() != null) {
            current.setDegree(store.writeList(change.degree()));
        }
        if (change.scale() != null) {
            current.setScale(store.writeList(change.scale()));
        }
        if (change.industry() != null) {
            current.setIndustry(store.writeList(change.industry()));
        }
        if (change.stage() != null) {
            current.setStage(store.writeList(change.stage()));
        }
        // 空串回落到默认，与 DutyWindow.normalize 对时段的处理同口径：
        // 留空一行不在 ActiveLevel.selectable() 里的值，界面上会显示成一个空白下拉
        if (change.activeLevelFloor() != null) {
            current.setActiveLevelFloor(activeLevelFloor(change.activeLevelFloor()));
        }

        // 节奏与配额：未传保持原值。改造前这里按「未传 = 默认值」处理，
        // 于是保存一个无关配置就会把用户调过的配额改回去
        if (change.waitTimeSeconds() != null) {
            current.setWaitTimeSeconds(change.waitTimeSeconds());
        }
        if (change.dailyDeliveryQuota() != null) {
            current.setDailyDeliveryQuota(change.dailyDeliveryQuota());
        }
        if (change.dailyEvalLimit() != null) {
            current.setDailyEvalLimit(change.dailyEvalLimit());
        }
        if (change.scrollRoundLimit() != null) {
            current.setScrollRoundLimit(change.scrollRoundLimit());
        }
        // <=0 表示不限时间，原样保留；正数收敛到合法区间（判据在 normalizeCombinationTimeLimit 里）
        if (change.combinationTimeLimitSec() != null) {
            current.setCombinationTimeLimitSec(
                    ConfigStore.normalizeCombinationTimeLimit(change.combinationTimeLimitSec()));
        }
        if (change.unreadScanLimit() != null) {
            current.setUnreadScanLimit(ConfigStore.clampUnreadScanLimit(change.unreadScanLimit()));
        }
        if (change.watchIntervalSeconds() != null) {
            current.setWatchIntervalSeconds(ConfigStore.clampWatchInterval(change.watchIntervalSeconds()));
        }

        // 重复沟通与简历图片
        if (change.repeatChatEnabled() != null) {
            current.setRepeatChatEnabled(change.repeatChatEnabled() ? 1 : 0);
        }
        if (change.repeatChatMessages() != null) {
            current.setRepeatChatMessages(store.writeList(change.repeatChatMessages()));
        }
        if (change.resumeImageEnabled() != null) {
            current.setResumeImageEnabled(change.resumeImageEnabled() ? 1 : 0);
        }

        // 值守时段：空串与非法格式在这里就拦下，不留到跑起来才发现
        if (change.dutyStartTime() != null) {
            current.setDutyStartTime(
                    DutyWindow.normalize(change.dutyStartTime(), ConfigStore.DEFAULT_DUTY_START));
        }
        if (change.dutyEndTime() != null) {
            current.setDutyEndTime(
                    DutyWindow.normalize(change.dutyEndTime(), ConfigStore.DEFAULT_DUTY_END));
        }
        return current;
    }

    private static String activeLevelFloor(String raw) {
        return raw == null || raw.isBlank() ? ConfigStore.DEFAULT_ACTIVE_LEVEL_FLOOR : raw;
    }
}
