package com.chatjob.scan;

import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.PlatformConfigEntity;

import java.util.Optional;

/**
 * 卡片边界上「该不该停、为什么停」的判定，以及两个当日上限的缺省口径。
 *
 * <p>抽成静态纯函数是为了能直接喂参数测：这段判定原来写在卡片循环体里，而循环要开浏览器、
 * 要 sleep、要写库，用测试替身驱动只能验证「它会不会调下一个动作」，验证不了
 * 「配额和上限同时到顶时先记哪一个」——而那正是这一段最容易改错的地方（与
 * {@code StandbyGate} 同一模式）。
 *
 * <p><b>两个判定方法不碰 IO、不读配置、不读时钟</b>：配额、上限、已用量、已耗时全部由调用方
 * 量好后传进来。{@link #quotaOf} / {@link #evalLimitOf} 只负责「没配过时按多少算」，
 * 它们跟判据住在一起，是因为「配额是 30」这件事只有配上「到了就停」才有意义 ——
 * 分开放就会出现「判据用 30、文案用 150」那种只在日志里看得见的错位。
 * 数字本身（30 / 150）定义在 {@code ConfigStore}，这里只引用：建行时写的就是同一个常量，
 * 再抄一份就会在「新装的行」与「刚构造的实体」之间分叉。
 *
 * <p><b>判定顺序即优先级</b>，顺序本身是被测对象：
 * <ol>
 *   <li>扫描开关 → {@link ScanStop#SCAN_DISABLED}。关掉之后不得再有任何搜索页导航，
 *       所以它排在最前：哪怕配额同时也到顶了，这一段的结束原因也是「不扫了」。</li>
 *   <li>投递配额 → {@link ScanStop#QUOTA}。</li>
 *   <li>评估上限 → {@link ScanStop#EVAL_LIMIT}。三项都只在卡片边界判：这里之前的动作
 *       要么不发消息、要么已经发完，是唯一可靠的断点。</li>
 *   <li>组合时间上限 → {@link ScanStop#COMBINATION_TIMEOUT}。{@code limitSeconds <= 0}
 *       表示不限时，整段跳过这一判据。</li>
 * </ol>
 */
public final class ScanBoundary {

    private ScanBoundary() {
    }

    /**
     * 投递配额的缺省值。库里这一列有 DEFAULT，读到 null 只可能是实体刚构造还没落库。
     *
     * <p>数字本身定义在 {@link ConfigStore}：建行时写的就是它，这里再抄一份就会在
     * 「新装的行」与「刚构造的实体」之间分叉。判据留在本类，缺省值只引用不定义。
     */
    public static int quotaOf(PlatformConfigEntity cfg) {
        return cfg.getDailyDeliveryQuota() == null
                ? ConfigStore.DEFAULT_DAILY_DELIVERY_QUOTA
                : cfg.getDailyDeliveryQuota();
    }

    /** 评估上限的缺省值，口径同上 */
    public static int evalLimitOf(PlatformConfigEntity cfg) {
        return cfg.getDailyEvalLimit() == null
                ? ConfigStore.DEFAULT_DAILY_EVAL_LIMIT
                : cfg.getDailyEvalLimit();
    }

    /**
     * @param scanEnabled  该平台此刻是否参与扫描（读当下的配置，不是本轮启动时的值）
     * @param delivered    今日主动投递数，口径是 {@code daily_counter.delivered}（ADR-0004）
     * @param quota        今日投递配额
     * @param evaluated    今日评估数
     * @param evalLimit    今日评估上限
     * @param usedSeconds  本组合已用时间（秒）
     * @param limitSeconds 组合时间上限（秒），{@code <= 0} 表示不限
     * @return 要停时给出原因；返回 empty 表示「这张卡照常处理」
     */
    public static Optional<ScanStop> atCard(boolean scanEnabled,
                                           int delivered, int quota,
                                           int evaluated, int evalLimit,
                                           long usedSeconds, int limitSeconds) {
        if (!scanEnabled) {
            return Optional.of(ScanStop.SCAN_DISABLED);
        }
        if (delivered >= quota) {
            return Optional.of(ScanStop.QUOTA);
        }
        if (evaluated >= evalLimit) {
            return Optional.of(ScanStop.EVAL_LIMIT);
        }
        // 严格大于：刚好用满 limitSeconds 不算超时，与改造前逐字一致
        if (limitSeconds > 0 && usedSeconds > limitSeconds) {
            return Optional.of(ScanStop.COMBINATION_TIMEOUT);
        }
        return Optional.empty();
    }
}
