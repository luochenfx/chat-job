package com.chatjob.driver.human;

import java.util.ArrayList;
import java.util.List;

/**
 * 滚动步长序列。纯计算：给定"要滚多少像素"和视口高，输出每一步的位移。
 *
 * <p>为什么不像以前那样一次 {@code window.scrollBy(0, dy)}：JS 改滚动位置不派发
 * {@code wheel} 事件，页面上挂一个监听器就能得到"被滚过、但一次滚轮事件都没有"的结论
 * （spec 0006 §1.2）。改成滚轮之后一次位移变小，所以要拆成多步。
 *
 * <p><b>这里定义的"一轮"= 一次完整的滚动计划（累计位移达到目标），不是以前那个
 * "滚 1.5 屏一次"。</b>{@code ScrollParams.roundLimit} 的 300 是按旧口径定的；如果不重新定义，
 * 改滚轮后"一轮"会变成一次几百毫秒的小步，轮数会涨几十倍，保护直接失效
 * （ADR-0007 Consequences）。所以：一次 {@link #steps} 的结果 = 一轮，
 * {@code ScrollOutcome.rounds()} 的口径不变，{@code RunService} 与 {@code run_log} 的文案也不用改。
 */
public final class HumanScrollPlan {

    /** 视口高不可信时（读不到、为 0）改用它，单位像素。 */
    private static final int FALLBACK_VIEWPORT_HEIGHT = 800;

    /** 单步至少 1 像素：为 0 的步长等于空转，会把轮数拖长而位移不变。 */
    private static final int MIN_STEP_PX = 1;

    private HumanScrollPlan() {
    }

    /**
     * 把一次滚动拆成若干步。
     *
     * @param dy             目标累计位移，正数向下、负数向上
     * @param viewportHeight 视口高，单步位移按它的比例取
     * @return 带符号的步长序列，求和结果恰好等于 {@code dy}；{@code dy == 0} 时为空数组
     */
    public static int[] steps(int dy, int viewportHeight, HumanizeConfig.Range stepRatio, Rng rng) {
        if (dy == 0) {
            return new int[0];
        }
        int viewport = viewportHeight > 0 ? viewportHeight : FALLBACK_VIEWPORT_HEIGHT;
        int maxStep = Math.max(MIN_STEP_PX, (int) Math.round(stepRatio.max() * viewport));
        int minStep = Math.max(MIN_STEP_PX, (int) Math.round(stepRatio.min() * viewport));

        int sign = dy > 0 ? 1 : -1;
        int remaining = Math.abs(dy);
        List<Integer> plan = new ArrayList<>();
        while (remaining > 0) {
            int step = rng.uniformInt(HumanizeConfig.Range.of(minStep, maxStep));
            step = Math.min(step, maxStep);
            // 剩余量比"最小步"还小的时候直接走完：否则会拖出一个 3 像素的收尾小步，
            // 与前面几步的量级差一个数量级，看起来比直接停更奇怪。
            // 仍然受 maxStep 限制 —— 测试断言"单步不超上限"，这里不能为了收尾破例。
            if (remaining - step < minStep) {
                step = Math.min(remaining, maxStep);
            }
            plan.add(sign * step);
            remaining -= step;
        }
        int[] result = new int[plan.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = plan.get(i);
        }
        return result;
    }
}
