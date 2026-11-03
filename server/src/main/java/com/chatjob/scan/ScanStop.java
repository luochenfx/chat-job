package com.chatjob.scan;

/**
 * 扫描段为什么停下（术语见 {@code CONTEXT.md}「扫描中止」）。
 *
 * <p>它回答的是「这一段怎么结束的」，与「结束后要不要回扫」（待命期的判定）是两个问题：
 * 这里是结果，那里是结果之后的走向。放在 {@code com.chatjob.scan} 而不是 orchestrator，
 * 是因为扫描段要独立成模块（ADR-0009）：让它反过来依赖待命模块的枚举，会形成
 * {@code scan} ↔ {@code orchestrator} 的包级环。
 *
 * <p><b>人工停止与值守时段到点目前不在这里。</b>它们在 {@code RunService} 里以运行标志
 * （{@code stopRequested} / {@code dutyEnded}）表达，调用方拿到 {@code null} 之后再去读标志 ——
 * 同一个「中断」有两种含义挤在一个载体上。收进本枚举要同时改控制面的形状（ADR-0009 的第 3 步），
 * 单独改这一处只会把隐式约定换个写法。
 */
public enum ScanStop {

    /** 今日投递数达配额。只中止扫描，值守照旧。 */
    QUOTA,

    /** 今日评估数达上限。只中止扫描，值守照旧。 */
    EVAL_LIMIT,

    /** 组合队列已扫完：今天这个平台上没有还没看的搜索组合了。 */
    COMBOS_EXHAUSTED,

    /**
     * 单个组合跑到时间上限（{@code combination_time_limit_sec}）。
     *
     * <p>不并入 {@link #COMBOS_EXHAUSTED}：那个说「都跑完了」，这一个说「还没跑完就停下了」，
     * 而且断点停在原地，同日回扫从这张卡继续。混用会让界面上的「岗位 0」与「已投递 12」
     * 互相矛盾——归零之后没人看得出扫到了哪。
     */
    COMBINATION_TIMEOUT,

    /**
     * 该平台未启用扫描，或运行中被关闭（spec 0005）。
     *
     * <p>与其余四种不同类：那些是「今天不扫了」，这一个是「本来就（或者从此）不扫」。
     * 沿用「搜索组合已全部扫完」会让排查的人去翻一张空表。
     */
    SCAN_DISABLED
}
