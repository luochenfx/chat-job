package com.chatjob.driver.human;

/**
 * 节奏的唯一出口：所有"等一会儿"都从这里出去。
 *
 * <p>抽成接口而不是直接 {@link Thread#sleep} 的原因：适配器里那 26 处调用点要能被测到。
 * 直接 sleep 的话，任何覆盖"扫描一轮"的测试都必须真的等待几分钟；换成接口后，
 * 测试注入一个记录器，断言"在这里等了 1500–2500ms"，一秒内跑完。
 *
 * <p>为什么睡眠时间由本接口自己抽随机，而不是调用方算好再传一个数：
 * 适配器原来的 {@code pause(min, max)} 语义就是"区间内随机"，把随机留给调用方
 * 会让每个调用点各写一次 {@code min + Math.random() * (max - min)}，
 * 那正是这一轮要消除的重复（spec 0006 §1.2）。
 */
public interface Pacing {

    /**
     * 在 {@code [minMs, maxMs]} 内随机等待。
     *
     * <p>两端相同表示固定间隔。这不是推荐用法，只为原样替换旧的 {@code pause(a, a)} 调用点。
     */
    void sleep(int minMs, int maxMs);

    /** 固定等待，用于"按下到抬起"这类必须精确的短间隔。 */
    void sleepMs(int ms);

    /** 真的睡。{@code rng} 与其它随机量共用一条序列，种子非 0 时整体可复现。 */
    static Pacing system(Rng rng) {
        return new SystemPacing(rng);
    }

    /** 默认实现。 */
    final class SystemPacing implements Pacing {

        private final Rng rng;

        SystemPacing(Rng rng) {
            this.rng = rng;
        }

        @Override
        public void sleep(int minMs, int maxMs) {
            sleepMs(rng.uniformInt(HumanizeConfig.Range.of(minMs, maxMs)));
        }

        /**
         * 被中断时恢复中断标记并立即返回，不抛异常。
         *
         * <p>等待被打断只发生在停服或取消 Run 的时候，此时调用方要的是"尽快退出"，
         * 而不是收到一个需要逐层处理的受检异常——那会让每个调用点写 try/catch。
         */
        @Override
        public void sleepMs(int ms) {
            if (ms <= 0) {
                return;
            }
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
