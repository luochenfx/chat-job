package com.chatjob.driver.human;

import com.chatjob.driver.human.HumanizeConfig.Gauss;
import com.chatjob.driver.human.HumanizeConfig.Range;

import java.util.SplittableRandom;

/**
 * 本层唯一的随机数来源。
 *
 * <p>为什么不用 {@link java.util.concurrent.ThreadLocalRandom}：它没法给种子，出问题的那一次轨迹
 * 无法在本地重放。这里 {@code seed != 0} 时同一种子得到完全相同的序列，配上 {@code run_log} 里的
 * 种子值就能把一次异常点击复现出来。
 *
 * <p>为什么不是线程安全的：本层的实例由某个平台的浏览器会话持有，所有调用都发生在该平台
 * 那条 Playwright 线程上（{@code PlaywrightExecutor}）。跨线程共享会让"同种子可复现"失效，
 * 因为取数顺序变得不确定。
 *
 * <p>参数为什么用 {@link Range} / {@link Gauss} 而不是 {@code (int min, int max)}：
 * 分布形态是配置的一部分（spec 0006 §3.6），把形态和取值范围一起传，调用点看不出"这次用的是哪种"。
 */
public final class Rng {

    /**
     * 截断高斯的采样次数上限。超过就取边界值。
     *
     * <p>不用 while(true)：{@code sigma} 很小而 {@code maxDeviation} 又卡在边缘时，
     * 命中概率会低到让一次点击等上几秒。宁可取边界，也不要让交互卡在随机数上。
     */
    private static final int GAUSS_ATTEMPTS = 32;

    private final SplittableRandom random;

    private Rng(SplittableRandom random) {
        this.random = random;
    }

    /**
     * @param seed {@code 0} = 真随机（正常运行用这个，每次启动都不同）；
     *             非 {@code 0} = 可复现，同一值得到同一条序列
     */
    public static Rng of(long seed) {
        long actual = seed != 0
                ? seed
                // 真随机也要有确定的形式：时间戳与线程 id 混合，避免同毫秒起两份得到同一条序列
                : System.nanoTime() ^ (Thread.currentThread().threadId() * 0x9E3779B97F4A7C15L);
        return new Rng(new SplittableRandom(actual));
    }

    public double nextDouble() {
        return random.nextDouble();
    }

    /**
     * 以 {@code probability} 的概率返回 true。{@code <=0} 恒 false、{@code >=1} 恒 true，
     * 不再消耗随机数——否则"关掉错字"这件事仍会改变后续序列，让可复现性变得有条件。
     */
    public boolean chance(double probability) {
        if (probability <= 0) {
            return false;
        }
        if (probability >= 1) {
            return true;
        }
        return random.nextDouble() < probability;
    }

    public double uniform(Range range) {
        return range.min() + random.nextDouble() * range.span();
    }

    /**
     * 区间内的整数，两端都含。{@code 18..34} 得到 18 或 34 的概率与其它值相同（均匀），
     * 不做四舍五入后再夹取——那样两端只有半个区间的概率。
     */
    public int uniformInt(Range range) {
        int lo = range.minInt();
        int hi = range.maxInt();
        if (hi <= lo) {
            return lo;
        }
        return lo + random.nextInt(hi - lo + 1);
    }

    /**
     * 截断高斯：围绕 {@code center} 取值，超出 {@code maxDeviation} 的样本丢弃重采。
     *
     * <p>"截断"而不是"夹取"：夹取会把大量样本堆在边界上，落点分布出现两道明显的直线边界，
     * 那比均匀分布更容易被统计出来。
     */
    public double gauss(Gauss distribution) {
        double center = distribution.center();
        double sigma = distribution.sigma();
        double limit = distribution.maxDeviation();
        for (int attempt = 0; attempt < GAUSS_ATTEMPTS; attempt++) {
            double value = center + nextGaussian() * sigma;
            if (Math.abs(value - center) <= limit) {
                return value;
            }
        }
        return center + Math.signum(nextGaussian()) * limit;
    }

    /** Box–Muller。{@link SplittableRandom} 没有现成的高斯采样。 */
    private double nextGaussian() {
        double u1 = Math.max(random.nextDouble(), Double.MIN_NORMAL);
        double u2 = random.nextDouble();
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }
}
