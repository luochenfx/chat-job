package com.chatjob.scan;

import java.util.Optional;

/**
 * 卡片边界上「该不该停、为什么停」的判定。
 *
 * <p>抽成静态纯函数是为了能直接喂参数测：这段判定原来写在卡片循环体里，而循环要开浏览器、
 * 要 sleep、要写库，用测试替身驱动只能验证「它会不会调下一个动作」，验证不了
 * 「配额和上限同时到顶时先记哪一个」——而那正是这一段最容易改错的地方（与
 * {@code StandbyGate} 同一模式）。
 *
 * <p><b>不碰 IO、不读配置、不读时钟</b>：配额、上限、已用量、已耗时全部由调用方量好后传进来。
 * 配置的缺省值（配额 30、上限 150）留在调用方，因为待命期与收尾文案也读同一份缺省，
 * 抄到这里就是第二份真相。
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
