package com.chatjob.driver.human;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 本层的计数。四个单调递增的值，不重置、不落库，只用来算"这一层工作得怎么样"。
 *
 * <p><b>为什么要采样差而不是重置。</b>实例是平台级的，比一次 Run 活得久（浏览器会话跟着进程走，
 * Run 是一次扫描）。让 Run 去重置别人的计数会引入时序耦合：两个 Run 交叠时，后开始的会把
 * 先开始的计数清掉。所以 Run 开始时取一次快照、结束时再取一次，用差 {@link Snapshot#minus}。
 *
 * <p><b>为什么只有交互侧指标。</b>投递成功率、详情抓取成功率本来就在数据库里，这一层再算一遍
 * 就会有两套数，出问题时先要判断哪套是对的。降级率是唯一能反映本层实现质量的数
 * （spec 0006 Q15）。
 */
public final class HumanCounters {

    /** 动作类型。降级要按类型分开计数：点击降级通常意味着元素在动，输入降级通常意味着页面在改 DOM。 */
    public enum Kind {
        CLICK, TYPE, SCROLL
    }

    private final AtomicLong interactions = new AtomicLong();
    private final AtomicLong fallbacks = new AtomicLong();
    private final AtomicLong fallbackClick = new AtomicLong();
    private final AtomicLong fallbackType = new AtomicLong();
    private final AtomicLong fallbackScroll = new AtomicLong();
    private final AtomicLong realignDiscards = new AtomicLong();
    private final AtomicLong typedChars = new AtomicLong();

    /**
     * 一次完成的交互（点击、输入、一轮滚动、一次导航）。
     *
     * <p>降级也算一次交互：它确实完成了一个动作，只是没走人类化路径。
     * 若把降级排除在外，降级率高时"交互次数"会变小，比例被算得更大。
     */
    public void interaction() {
        interactions.incrementAndGet();
    }

    /** 一次降级。{@code reason} 由调用方写进日志，本类只计数。 */
    public void fallback(Kind kind) {
        fallbacks.incrementAndGet();
        switch (kind) {
            case CLICK -> fallbackClick.incrementAndGet();
            case TYPE -> fallbackType.incrementAndGet();
            case SCROLL -> fallbackScroll.incrementAndGet();
        }
    }

    /** 一次因元素漂移而作废的落点。作废本身不是降级，重算成功就仍然走人类化路径。 */
    public void realignDiscard() {
        realignDiscards.incrementAndGet();
    }

    public void typedChars(int count) {
        if (count > 0) {
            typedChars.addAndGet(count);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(interactions.get(), fallbacks.get(), fallbackClick.get(),
                fallbackType.get(), fallbackScroll.get(), realignDiscards.get(), typedChars.get());
    }

    /** 本实例到目前为止的汇总，格式见 {@link #format}。 */
    public String summary() {
        return format(snapshot());
    }

    /**
     * 汇总成一行日志。字段固定，便于 {@code run_log} 里按行比对改造前后。
     *
     * <p>示例：{@code 人类化交互 412 次 / 降级 3 次（输入 1、点击 2、滚动 0）/ 漂移作废 5 次 / 输入字符 1860}
     */
    public static String format(Snapshot snapshot) {
        return "人类化交互 %d 次 / 降级 %d 次（输入 %d、点击 %d、滚动 %d）/ 漂移作废 %d 次 / 输入字符 %d"
                .formatted(snapshot.interactions(), snapshot.fallbacks(), snapshot.fallbacksType(),
                        snapshot.fallbacksClick(), snapshot.fallbacksScroll(), snapshot.realignDiscards(),
                        snapshot.typedChars());
    }

    /** 某一时刻的读数。不可变，两个快照相减得到区间内的增量。 */
    public record Snapshot(long interactions, long fallbacks, long fallbacksType, long fallbacksClick,
                           long fallbacksScroll, long realignDiscards, long typedChars) {

        /** 本快照相对更早一次快照的增量。参数顺序反了会得到负数，这里的减法不纠正它——让错误立刻可见。 */
        public Snapshot minus(Snapshot earlier) {
            return new Snapshot(interactions - earlier.interactions,
                    fallbacks - earlier.fallbacks,
                    fallbacksType - earlier.fallbacksType,
                    fallbacksClick - earlier.fallbacksClick,
                    fallbacksScroll - earlier.fallbacksScroll,
                    realignDiscards - earlier.realignDiscards,
                    typedChars - earlier.typedChars);
        }

        /**
         * 降级率 = 降级次数 / 交互次数。没有交互时返回 0。
         *
         * <p>验收口径是 {@code < 0.05}（spec 0006 §5.2）。持续偏高说明实现有问题，
         * 与投递成功率无关——投递失败还有别的可能，降级只由本层产生。
         */
        public double fallbackRate() {
            return interactions == 0 ? 0 : (double) fallbacks / interactions;
        }
    }
}
