package com.chatjob.time;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 进程内唯一的「现在几点」来源。
 *
 * <p>它同时承担两件事：给出当前时刻，以及把时刻与库里的文本格式互转。
 * 这两件事必须同处一地 —— 库里所有时间都是 {@code yyyy-MM-dd'T'HH:mm:ss} 的可比较文本
 * （字典序即时间序），谁写的就要谁能读回来；分成两个工具类之后，
 * 「写用 A 的格式、读用 B 的格式」这种错会以「解析失败」的形式出现在很远的地方。
 *
 * <p>为什么是 interface 而不是直接调 {@code LocalDateTime.now()}：配额、冷却、退避、
 * 值守时段这四件事全都是时间判断，而它们的正确性只能在固定时刻上断言。
 * 生产用系统时钟、测试用固定时钟 —— 两个 adapter 撑起一个真 seam。
 *
 * <p>边界：只覆盖<b>业务判定与落库</b>。页面等待超时（适配器里的 deadline 轮询、
 * 人类化交互的预热与节奏）、SSE 心跳、企业微信节流仍然用系统时钟 ——
 * 它们是「一个页面操作等多久」，与「一条 Run 的时间」不是同一件事。
 */
public interface TimeSource {

    /** 当前时刻，按本进程启动时钉死的时区 */
    LocalDateTime now();

    /**
     * 当前时刻的库内文本形态（{@code yyyy-MM-dd'T'HH:mm:ss}）。
     *
     * <p>单独给一个方法而不是让调用方 {@code now().format(...)}：这个格式是<b>数据契约</b>
     * 不是展示选择，散在几十个调用点里格式化，改一次格式就要全仓搜索。
     */
    String stamp();

    /**
     * 任意时刻的库内文本形态。{@link #stamp()} 是 {@code format(now())} 的便捷形态。
     *
     * <p>存在的原因很具体：清理任务的 {@code cutoff = now - N 天}、按日查询的左闭右开区间
     * （{@code day} / {@code day+1}）都要把<b>算出来的</b>时刻写成同样的文本再比较。
     * 没有它，这些地方会各自留一份格式串，从而绕开这个 module。
     */
    String format(LocalDateTime moment);

    /** 当前自然日（{@code yyyy-MM-dd}），配额与断点的分区键 */
    String today();

    /**
     * 把库里的时间文本读回时刻。
     *
     * <p>空值与不可解析都返回 {@link Optional#empty()}，不抛异常：脏时间戳是预期内的
     * 数据质量问题（手工改库、旧版本写入），不是程序缺陷。用返回值逼每个调用点
     * 显式回答「读不出来时怎么办」，而不是让它悄悄走进一个 catch。
     */
    Optional<LocalDateTime> parse(String text);
}
