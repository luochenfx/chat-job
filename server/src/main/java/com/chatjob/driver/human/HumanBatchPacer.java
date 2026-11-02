package com.chatjob.driver.human;

import lombok.extern.slf4j.Slf4j;

/**
 * 批次停顿：连续处理若干条目之后插一次长停顿，用掉多少条之后停这一件事本身也是随机的。
 *
 * <p>为什么需要它：列表里的条目是连续读的（Boss 逐张点卡片、智联逐张点开右侧面板），
 * 每张之间只有几百毫秒，几十张连起来就是一段没有任何停顿的连续操作。改造前没有这段停顿，
 * 现在补上（spec 0006 §3.5）。停顿的<b>周期</b>也随机，否则"每 5 张停 1.2 秒"这种
 * 固定节拍本身就是一个可统计的规律。
 *
 * <p>为什么单独一个类而不是在适配器里写个计数器：两个适配器要用同一套规则，
 * 而计数器 + 阈值重抽是纯逻辑，能脱离浏览器测（{@link HumanBatchPacerTest}）。
 * 写在适配器里就变成两份，迟早只改一处。
 *
 * <p>不线程安全：它跟着适配器的那条 Playwright 线程走，与 {@link Humanize} 同一约束。
 */
@Slf4j
public final class HumanBatchPacer {

    /** 详情页连读的默认批次：每 5–8 张停一次，停 800–2000ms（spec 0006 §3.5 的取值）。 */
    private static final int DEFAULT_MIN_CARDS = 5;
    private static final int DEFAULT_MAX_CARDS = 8;
    private static final int DEFAULT_MIN_PAUSE_MS = 800;
    private static final int DEFAULT_MAX_PAUSE_MS = 2_000;

    private final Pacing pacing;
    private final int minCards;
    private final int maxCards;
    private final int minPauseMs;
    private final int maxPauseMs;

    /** 距离下一次停顿还剩几个条目。初值在构造时抽，所以第一批的条数也是随机的。 */
    private int remaining;

    /** 当前这一批抽到的条数，只用于停顿时的 DEBUG 记录。 */
    private int batch;

    public HumanBatchPacer(Pacing pacing) {
        this(pacing, DEFAULT_MIN_CARDS, DEFAULT_MAX_CARDS, DEFAULT_MIN_PAUSE_MS, DEFAULT_MAX_PAUSE_MS);
    }

    /**
     * @param minCards    批次最少条数（含）
     * @param maxCards    批次最多条数（含）
     * @param minPauseMs  停顿最短毫秒
     * @param maxPauseMs  停顿最长毫秒
     */
    public HumanBatchPacer(Pacing pacing, int minCards, int maxCards, int minPauseMs, int maxPauseMs) {
        if (minCards < 1 || maxCards < minCards) {
            throw new IllegalArgumentException("批次条数区间不合法: " + minCards + "–" + maxCards);
        }
        if (minPauseMs < 0 || maxPauseMs < minPauseMs) {
            throw new IllegalArgumentException("停顿区间不合法: " + minPauseMs + "–" + maxPauseMs);
        }
        this.pacing = pacing;
        this.minCards = minCards;
        this.maxCards = maxCards;
        this.minPauseMs = minPauseMs;
        this.maxPauseMs = maxPauseMs;
        this.batch = drawBatch();
        this.remaining = batch;
    }

    /**
     * 处理下一个条目前调用一次：到点就停顿，并重新抽下一批的条数。
     *
     * <p>放在"之前"而不是"之后"：批次边界落在两个条目之间，此时页面处于静止状态
     * （上一张已经读完、下一张还没点），停顿不会插在某个动作的中间。
     */
    public void beforeNext() {
        remaining--;
        if (remaining > 0) {
            return;
        }
        // 记一条 DEBUG：运行期要能核对"停顿真的发生了、周期是多少"。
        // 它不进 run_log —— 那里记的是使用者关心的结果侧信息，这既不是一次交互也不是一次降级
        log.debug("连续读完一批 {} 个条目，停顿 {}–{}ms", batch, minPauseMs, maxPauseMs);
        batch = drawBatch();
        remaining = batch;
        pacing.sleep(minPauseMs, maxPauseMs);
    }

    /** 距离下一次停顿还差几个条目。只给测试用，生产代码不该依赖这个数。 */
    int remaining() {
        return remaining;
    }

    private int drawBatch() {
        return pacing.uniformInt(minCards, maxCards);
    }
}
