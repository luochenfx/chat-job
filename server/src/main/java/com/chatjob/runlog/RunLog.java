package com.chatjob.runlog;

/**
 * 一条 Run 的运行日志出口。
 *
 * <p>它一次做两件事：往 {@code run_log} 写一行，并把同一句话写进进程日志文件
 * （{@code data/logs/chat-job.log}）。两处内容一字不差，只有格式不同 —— 库里是级别 + 纯文本，
 * 文件里多一个 {@code [平台] [run N]} 前缀。并发时两个平台的时间线会交错，spec 0004 §5.1
 * 把「日志交错难以排查」列为 2b 的上线前提，所以前缀只在这一层拼，调用方不再各拼一份。
 *
 * <p><b>不推 SSE。</b>日志走 {@code GET /api/runs/{id}/logs} 轮询，SSE 只推状态与计数
 * （见 {@code SseHub} 的类注释：混在一起会让进度面板在日志密集时抖动、重连后还要回放大量文本）。
 * 两条通道共用这里只是为了「同一句话的两份副本同源」，不是把日志塞进事件流。
 *
 * <p>实现由 {@link RunLogs#of} 创建并绑定一条 Run。它不持有状态，但<b>不要缓存跨 Run 复用</b>：
 * 那样写进库的行会挂到别的 Run 上，而界面上看起来只是「日志串了」。
 */
public interface RunLog {

    void info(String message);

    void warn(String message);

    void error(String message);

    /**
     * 带异常的错误。
     *
     * <p>堆栈只进文件日志：{@code run_log} 是列表视图，一行文本放不下也不需要堆栈，
     * 需要看堆栈的人本来就会去翻文件。库里仍只存 {@code message}。
     */
    void error(String message, Throwable cause);

    /** 带异常的警告，取舍同 {@link #error(String, Throwable)}。 */
    void warn(String message, Throwable cause);

    /**
     * 级别由运行结果决定时用这个入口。
     *
     * <p>存在理由：Run 的收尾日志按终态选级别（正常收尾 INFO、异常收尾 WARN），
     * 没有这个入口时调用方要写三条几乎一样的分支，级别与文案容易改一处漏一处。
     */
    void log(RunLogLevel level, String message);
}
