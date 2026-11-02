package com.chatjob.driver.human;

/**
 * 一次交互降级时的回调。
 *
 * <p>为什么单独一个类型、而不是让本层自己写 {@code run_log}：{@code run_log} 需要 {@code runId}，
 * 而 {@link Humanizer} 是平台级实例，比一次 Run 活得久。让 driver 层去拿 {@code RunStore}
 * 会让它反向依赖编排层，并且"当前是哪个 Run"这件事在 driver 层没有答案。
 *
 * <p>所以分工是：本层在降级时<b>只抛事件</b>（动作类型 + 失败条件），由编排层
 * （{@code RunService}）在 Run 开始时注册、结束时撤销，把事件写成一条 WARN 级别的
 * {@code run_log}。降级明细要能按次查到（spec 0006 §2.1 第 8 条），只留汇总是不够的。
 */
@FunctionalInterface
public interface FallbackListener {

    /**
     * @param kind   动作类型（点击 / 输入 / 滚动）
     * @param reason 失败条件。<b>必须写明是哪一条判据没过</b>，不是"出错了"——
     *               这条文本会原样进 {@code run_log}，是排查降级率的唯一线索
     */
    void onFallback(HumanCounters.Kind kind, String reason);

    /** 没有任何监听者时用它：不必在每个调用点判空。 */
    FallbackListener NONE = (kind, reason) -> {
    };
}
