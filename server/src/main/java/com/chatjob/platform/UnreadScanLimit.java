package com.chatjob.platform;

/**
 * 一轮未读扫描的取数范围。
 *
 * <p>存在的理由是一个真实的静默故障：{@code listUnreadThreads} 里曾写死
 * {@code Math.min(items.count(), 30)}。聊天列表整页渲染、没有滚动加载，所以 DOM 里本来
 * 就有全部会话，但只读前 30 条 —— 列表长到 35 条时，排在后面的 5 个会话永远不会被扫到、
 * 不会被回复，<b>而且不抛异常、不打日志</b>。任务表解决的是「发现了但处理失败」，
 * 解决不了「压根没发现」。
 *
 * <p>上限本身有必要保留：列表异常膨胀时，一轮值守要读 3 次 DOM/条，不能让它吃掉整个
 * 扫描节奏。所以它变成配置项（{@code platform_config.unread_scan_limit}），并且
 * <b>一旦触发就必须留下日志</b>——{@link Plan#truncated()} 就是这个信号的唯一来源。
 *
 * <p>这里只做纯数学，不读配置、不碰 DOM：默认值与上下限的归一是 {@code ConfigStore} 的职责。
 * 分开的原因是这段判定必须在没有浏览器、没有数据库的单测里能被钉住。
 */
public final class UnreadScanLimit {

    private UnreadScanLimit() {
    }

    /**
     * @param total 列表里实际有多少个会话项
     * @param limit 本轮允许读多少个（调用方保证已归一为 >= 1）
     */
    public static Plan plan(int total, int limit) {
        int safeTotal = Math.max(total, 0);
        int safeLimit = Math.max(limit, 1);
        int take = Math.min(safeTotal, safeLimit);
        return new Plan(take, safeTotal, safeLimit);
    }

    /**
     * @param take      本轮实际读取的条数
     * @param total     列表总条数
     * @param limit     本轮上限
     * @param truncated {@code true} 表示有会话被上限挡在门外 —— 调用方必须打日志，不能静默
     */
    public record Plan(int take, int total, int limit, boolean truncated) {

        public Plan(int take, int total, int limit) {
            this(take, total, limit, total > take);
        }

        /** 被上限挡在门外的条数；没有截断时恒为 0。 */
        public int skipped() {
            return total - take;
        }
    }
}
