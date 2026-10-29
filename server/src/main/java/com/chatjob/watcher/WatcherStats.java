package com.chatjob.watcher;

/**
 * skip 占比告警（spec 0002 §3.8.2）。
 *
 * <p>要回答的是一个具体问题：<b>「开关开了，但它是不是一直在躲？」</b>
 * 没有这个指标，{@code AI_SKIPPED} 只会在日志里安静堆积。
 *
 * <p><b>判定放在这里而不是前端</b>，因为四档里有两档是「坏消息」，前端各自实现必然分叉：
 * 「样本不足」被写成绿色、或者「告警已关闭」干脆不显示，都会把「没法判断」伪装成
 * 「一切正常」—— 这正是本 spec 要根治的病。
 *
 * <p>三个刻意的排除（口径写在代码里，不配）：
 * <ul>
 *   <li>{@code AI_FAILED} 不进分子：接口坏了不是「在躲」，混进来会让两个问题互相掩盖；</li>
 *   <li>{@code BLOCKED_*} 不进分子：被校验层拦下是它在工作，不是模型在躲；</li>
 *   <li>分母只算 {@code reply_source = 'AI'}：混入模板回复会把占比稀释成一个永远正常的数字。</li>
 * </ul>
 */
public record WatcherStats(long total, long skipped, int pct, int thresholdPct, int minSample,
                           State state) {

    /** 窗口固定 7 天，与任务面板同口径。窗口可配会出现「面板说 42 次、提示条说 12 次」 */
    public static final int WINDOW_DAYS = 7;

    public enum State {
        /** 阈值 0 = 用户主动关了告警。仍要显示，否则等于把「我关了它」伪装成「一切正常」 */
        DISABLED,
        /** 分母不够，不判定。绝不能渲染成 OK */
        INSUFFICIENT,
        OK,
        ALERT
    }

    /**
     * @param total        近 7 天 AI 参与判定的次数（分母）
     * @param skipped      其中 AI 主动不回的次数（分子）
     * @param thresholdPct 告警阈值（%），0 = 关闭
     * @param minSample    最小样本数
     */
    public static WatcherStats judge(long total, long skipped, int thresholdPct, int minSample) {
        long safeTotal = Math.max(0, total);
        long safeSkipped = Math.max(0, Math.min(skipped, safeTotal));
        int safeThreshold = Math.max(0, Math.min(thresholdPct, 100));
        int safeMinSample = Math.max(1, minSample);

        // 判定用四舍五入后的整数百分比，而不是原始比值：面板显示什么就判什么。
        // 若用精确比值，「显示 60%、判定 60.4% 超阈值」会让人指着屏幕问到底哪个是真的
        int pct = safeTotal == 0 ? 0 : (int) Math.round(safeSkipped * 100.0 / safeTotal);

        State state;
        if (safeThreshold == 0) {
            state = State.DISABLED;
        } else if (safeTotal < safeMinSample) {
            state = State.INSUFFICIENT;
        } else if (pct > safeThreshold) {
            state = State.ALERT;
        } else {
            state = State.OK;
        }
        return new WatcherStats(safeTotal, safeSkipped, pct, safeThreshold, safeMinSample, state);
    }
}
