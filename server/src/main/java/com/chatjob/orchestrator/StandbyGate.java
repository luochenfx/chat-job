package com.chatjob.orchestrator;

import java.time.LocalTime;

/**
 * 「现在待命要不要转回扫描」的纯判定。
 *
 * <p>抽成静态纯函数是为了能直接喂参数测：待命循环本身要 sleep、要碰浏览器，
 * 用 fake 驱动只能验证「它会不会调 sweep」，验证不了「配额被调大之后会不会回扫」——
 * 而那正是这个功能最容易出错的判断。
 *
 * <p>不碰 IO、不读时钟（{@code now} 与 {@code today} 都由调用方传进来），
 * 所以跨自然日这类场景在单测里不需要改系统时间。
 */
public final class StandbyGate {

    /**
     * 扫描为什么停。
     *
     * <p>前三个是「今天不再找岗位」的三个理由，共同出口是待命而不是结束值守期。
     * {@link RunStatus#QUOTA_REACHED} 已不再写入，配额这一档现在由 {@link #QUOTA} 表达。
     *
     * <p>{@link #SCAN_DISABLED} 与前三个不同类：它不是「今天扫完了」，而是「这个平台根本不扫」，
     * 由扫描开关关闭引起（spec 0005）。{@link #COMBOS_EXHAUSTED} 不能替代它——只值守的 Run
     * 从未扫描，写「搜索组合已全部扫完」会让排查的人去翻一张空表。
     */
    public enum StopReason {
        /** 今日投递数达配额 */
        QUOTA,
        /** 今日评估数达上限 */
        EVAL_LIMIT,
        /** 组合队列已扫完 */
        COMBOS_EXHAUSTED,
        /** 该平台未启用扫描：本次运行从头到尾不扫 */
        SCAN_DISABLED
    }

    /** 待命一轮之后的走向 */
    public enum Action {
        /** 回扫：配额被调高，或已经跨自然日 */
        SCAN,
        /** 继续待命巡检 */
        WATCH,
        /** 时段外，收起页面挂起 */
        PARK
    }

    private StandbyGate() {
    }

    /**
     * 判定顺序即优先级，每条都有代价：
     * <ol>
     *   <li>时段外 → {@code PARK}。这一条必须在最前：时段外一律不操作页面是硬约束，
     *       哪怕配额刚被调高也不能顺手扫一下。</li>
     *   <li>未启用扫描 → {@code WATCH}。关掉扫描的平台上没有任何回扫理由；这一条单独成行而不加到
     *       下面每条 {@code SCAN} 分支上，是因为 {@code scanDay == null} 在下一行等价于跨日，
     *       而只值守的 Run 的 {@code scanDay} 恰好一直是 null。逐个加条件的话，以后新增回扫理由时
     *       还会漏掉这一条。</li>
     *   <li>跨自然日 → {@code SCAN}。配额与评估都已归零，必须重扫；否则第二天只会空转值守。</li>
     *   <li>配额被调高 → {@code SCAN}。</li>
     *   <li>评估上限被调高 → {@code SCAN}。</li>
     *   <li>其余 → {@code WATCH}。包括「组合已扫完」与「配额被调小」——
     *       前者再扫一遍是空转，后者说明用户想收着点，都不该回扫。</li>
     * </ol>
     *
     * @param scanDay     当前扫描所属自然日，null 表示这一轮从没扫过（按跨日处理）
     * @param today       今天的自然日，{@link RunStore#today()}
     * @param scanEnabled 该平台是否参与扫描；false 时任何情况都不返回 {@code SCAN}。
     *                    放最后一位，是为了既有用例只需在尾部补一个 {@code true}
     */
    public static Action decide(LocalTime now, DutyWindow window, StopReason reason,
                                String scanDay, String today,
                                int deliveredToday, int quota, int evaluatedToday, int evalLimit,
                                boolean scanEnabled) {
        if (window == null || now == null || !window.contains(now)) {
            return Action.PARK;
        }
        if (!scanEnabled) {
            return Action.WATCH;
        }
        if (scanDay == null || today == null || !scanDay.equals(today)) {
            return Action.SCAN;
        }
        if (reason == StopReason.QUOTA && deliveredToday < quota) {
            return Action.SCAN;
        }
        if (reason == StopReason.EVAL_LIMIT && evaluatedToday < evalLimit) {
            return Action.SCAN;
        }
        return Action.WATCH;
    }
}
