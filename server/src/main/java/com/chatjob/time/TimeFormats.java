package com.chatjob.time;

import java.time.format.DateTimeFormatter;

/**
 * 库内时间格式的唯一声明。
 *
 * <p>包内可见：只有 {@link TimeSource} 的实现需要它。业务代码一律走
 * {@link TimeSource#stamp()} / {@link TimeSource#parse(String)}，
 * 不该自己拼格式 —— 那是数据契约，改一次要改全仓。
 */
final class TimeFormats {

    /** 落库的时刻：{@code 2026-11-03T13:22:20}。带 T 是为了字典序即时间序 */
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 落库的自然日：{@code 2026-11-03} */
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private TimeFormats() {
    }
}
