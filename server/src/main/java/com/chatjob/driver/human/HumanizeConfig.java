package com.chatjob.driver.human;

/**
 * 人类化交互的全部参数，绑定 {@code chat-job.humanize}。
 *
 * <p>为什么在 yaml 而不在数据库：这些是算法参数，在 Playwright 线程上读取且必须零 IO；
 * 进数据库要配迁移、控制台 UI 与"整份对象 PUT"的兼容处理，而这一层还在验证阶段，
 * 参数没稳定到值得给人调（spec 0006 Q6）。
 *
 * <p>为什么不按平台分别配置：覆盖机制是"某个平台被限流之后"的处置手段，现在没有这个事实依据
 * （spec 0006 Q14）。结构上留了位置（{@link #enabled()} 关掉即整体退回），但只有全局一份。
 *
 * <p>两种分布形态，只实现这两种：{@link Range} 是均匀区间，用于时长、步数、概率；
 * {@link Gauss} 是截断高斯，只用于落点的相对位置。{@code lognormal} 之类不实现——
 * 加进去就是没人调的死配置。
 *
 * <p>单位写进字段名（{@code ...Ms} / {@code ...Px}），不靠注释约定。
 *
 * <p><b>缺省值的唯一来源是本文件的规范构造器。</b>所有字段都是包装类型：{@code null} 表示
 * "yaml 里没写这一项"，由所属记录的规范构造器换成默认值。这样"没配"与"配成 0"是两件事，
 * 不必靠"0 就当作没配"这类猜测；也不需要 Spring 的 {@code @DefaultValue}——它对嵌套记录的
 * 每个字段各取自己注释上的默认值，反而会让"整段缺失"与"部分缺失"落到两套不同的值上。
 *
 * <p>与之配套的一条约定：<b>区间必须成对写</b>。只写 {@code min} 时另一端按同一个值处理（退化为
 * 固定间隔），而不是补一个"技术默认值"——后者会悄悄改变节奏。
 *
 * @param enabled 总开关。{@code false} 时本层不参与任何交互，适配器走改造前的原生写法
 *                （spec 0006 §2.1 第 12 条）
 * @param seed    随机种子。{@code 0} = 真随机；非 {@code 0} 可复现同一条轨迹
 */
public record HumanizeConfig(Boolean enabled, Long seed, Mouse mouse, Keyboard keyboard,
                            Scroll scroll, WarmUp warmUp, PacingSpec pacing) {

    /** 全部取默认值且停用的一份。yaml 整段缺失、或测试里直接 new 出 null 时用它回退。 */
    public static HumanizeConfig defaults() {
        return new HumanizeConfig(null, null, null, null, null, null, null);
    }

    public HumanizeConfig {
        enabled = enabled != null ? enabled : Boolean.FALSE;
        seed = seed != null ? seed : 0L;
        mouse = mouse != null ? mouse : new Mouse(null, null, null, null, null, null);
        keyboard = keyboard != null ? keyboard : new Keyboard(null, null, null, null);
        scroll = scroll != null ? scroll : new Scroll(null, null);
        warmUp = warmUp != null ? warmUp : new WarmUp(null, null, null, null);
        pacing = pacing != null ? pacing : new PacingSpec(null);
    }

    /** 是否真的走人类化路径。关掉时本层退化为原生写法。 */
    public boolean active() {
        return enabled;
    }

    /**
     * 均匀区间 {@code {min, max}}，两端都含。
     *
     * <p>{@code min > max}（写反了）在构造期交换，不抛异常：这是配置笔误，
     * 让程序起不来对现场没有任何帮助，交换后行为仍然正确。
     */
    public record Range(Double min, Double max) {

        /**
         * 读起来更像参数表的构造入口：{@code Range.of(18, 34)}、{@code Range.of(0.35, 0.85)}。
         * 整数会自动放宽成 {@code double}，不必区分整数区间与比例区间。
         *
         * <p>用静态工厂而不是 {@code Range(int,int)} 构造器：多一个构造器会让 Spring 的类型绑定
         * 选不出该用哪一个（记录只有规范构造器是可绑定的），结果是不报错、参数静默不生效。
         */
        public static Range of(double min, double max) {
            return new Range(min, max);
        }

        public Range {
            min = min != null ? min : 0d;
            max = max != null ? max : min;
            if (min > max) {
                double swap = min;
                min = max;
                max = swap;
            }
        }

        public int minInt() {
            return (int) Math.ceil(min);
        }

        public int maxInt() {
            return (int) Math.floor(max);
        }

        public double span() {
            return max - min;
        }
    }

    /**
     * 截断高斯 {@code {center, sigma, max-deviation}}。
     *
     * @param center       期望中心。落点用 0.5 表示元素中线
     * @param sigma        标准差。越大越分散
     * @param maxDeviation 允许偏离中心的最大绝对值，超出即丢弃重采
     */
    public record Gauss(Double center, Double sigma, Double maxDeviation) {

        public Gauss {
            center = center != null ? center : 0.5;
            // sigma 为 0 会让所有样本落在中心，看起来像"没生效"；给一个极小值保持分布形态
            sigma = sigma != null && sigma > 0 ? sigma : 1e-6;
            maxDeviation = maxDeviation != null ? Math.abs(maxDeviation) : 0.4;
        }
    }

    /**
     * 点击的鼠标参数。
     *
     * @param steps         一次移动拆成多少个轨迹点。太少像瞬移，太多拖慢点击
     * @param jitter        落点相对元素内缩区的偏移
     * @param driftPx       纵向漂移阈值（像素）。与 {@code driftRatio} 取较大者
     * @param driftRatio    纵向漂移阈值（占元素高的比例）
     * @param realignRounds 漂移后重算落点的次数上限。用尽即降级为原生点击
     * @param holdMs        按下到抬起的间隔
     */
    public record Mouse(Range steps, Gauss jitter, Integer driftPx, Double driftRatio,
                        Integer realignRounds, Range holdMs) {

        public Mouse {
            steps = steps != null ? steps : Range.of(18, 34);
            jitter = jitter != null ? jitter : new Gauss(null, 0.15, 0.4);
            driftPx = driftPx != null && driftPx > 0 ? driftPx : 8;
            driftRatio = driftRatio != null && driftRatio > 0 ? driftRatio : 0.25;
            realignRounds = realignRounds != null && realignRounds > 0 ? realignRounds : 2;
            holdMs = holdMs != null ? holdMs : Range.of(40, 120);
        }
    }

    /**
     * 输入参数。
     *
     * @param charDelayMs     相邻两个字符的间隔
     * @param thinkingPauseMs 每 8–15 个字之后额外加的一次长停顿
     * @param mistypeChance   每个字符位置打成错字的概率，0 表示不打错
     * @param verifySettleMs  输入完成后静置多久再读回文本、比对 DOM 结构
     */
    public record Keyboard(Range charDelayMs, Range thinkingPauseMs, Double mistypeChance,
                           Integer verifySettleMs) {

        public Keyboard {
            charDelayMs = charDelayMs != null ? charDelayMs : Range.of(55, 165);
            thinkingPauseMs = thinkingPauseMs != null ? thinkingPauseMs : Range.of(250, 900);
            mistypeChance = mistypeChance != null ? Math.max(mistypeChance, 0) : 0.02;
            verifySettleMs = verifySettleMs != null && verifySettleMs > 0 ? verifySettleMs : 300;
        }
    }

    /**
     * 滚动参数。
     *
     * @param stepRatio      单步位移占视口高的比例
     * @param stepIntervalMs 两步之间的间隔
     */
    public record Scroll(Range stepRatio, Range stepIntervalMs) {

        public Scroll {
            stepRatio = stepRatio != null ? stepRatio : Range.of(0.35, 0.85);
            stepIntervalMs = stepIntervalMs != null ? stepIntervalMs : Range.of(90, 260);
        }
    }

    /**
     * 预热参数。预热指一个标签页被真正操作之前先留下"被看过"的痕迹（spec 0006 §1.4）。
     *
     * @param enabled       预热开关。它单独可关：出问题时先关它，再看点击/输入是否仍正常
     * @param maxDurationMs 一次预热的总时长上限。它是硬上限，不是目标值
     *                      —— 列表页慢的时候不能因为预热把节奏拖长
     * @param scrollSteps   预热时滚动几次
     * @param dwellMs       预热结束时停留多久
     */
    public record WarmUp(Boolean enabled, Integer maxDurationMs, Range scrollSteps, Range dwellMs) {

        public WarmUp {
            enabled = enabled != null ? enabled : Boolean.TRUE;
            maxDurationMs = maxDurationMs != null && maxDurationMs > 0 ? maxDurationMs : 3000;
            scrollSteps = scrollSteps != null ? scrollSteps : Range.of(2, 4);
            dwellMs = dwellMs != null ? dwellMs : Range.of(600, 1500);
        }
    }

    /**
     * 两次交互之间的间隔。适配器里原来的 {@code pause(min, max)} 由它接管，
     * <b>区间数值原样保留</b>——这一轮的目标是收口，不是重新标定参数（spec 0006 Q13）。
     */
    public record PacingSpec(Range betweenActionsMs) {

        public PacingSpec {
            betweenActionsMs = betweenActionsMs != null ? betweenActionsMs : Range.of(400, 900);
        }
    }
}
