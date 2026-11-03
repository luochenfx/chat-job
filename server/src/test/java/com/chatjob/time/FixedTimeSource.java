package com.chatjob.time;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 测试用的固定时钟。
 *
 * <p>放在测试源码里而不是主源码：生产代码不该有「可以被人拨动的时钟」这个入口。
 *
 * <p>可变而不是不可变，是因为待命与退避的用例需要「先断言这一刻，再把时间推过去」。
 * 推动只经 {@link #advance(Duration)} 一个入口，不会出现「测试自己 new 一个时间」的写法。
 */
public final class FixedTimeSource implements TimeSource {

    private LocalDateTime at;

    public FixedTimeSource(String stamp) {
        this.at = parseOrFail(stamp);
    }

    public FixedTimeSource(LocalDateTime at) {
        this.at = at;
    }

    /** {@code 2026-11-03T09:00:00} 这类库内格式的字面量 */
    public static FixedTimeSource of(String stamp) {
        return new FixedTimeSource(stamp);
    }

    public void set(String stamp) {
        this.at = parseOrFail(stamp);
    }

    public void advance(Duration delta) {
        this.at = at.plus(delta);
    }

    @Override
    public LocalDateTime now() {
        return at;
    }

    @Override
    public String stamp() {
        return at.format(TimeFormats.STAMP);
    }

    @Override
    public String format(LocalDateTime moment) {
        return moment.format(TimeFormats.STAMP);
    }

    @Override
    public String today() {
        return at.toLocalDate().format(TimeFormats.DAY);
    }

    @Override
    public Optional<LocalDateTime> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(text, TimeFormats.STAMP));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static LocalDateTime parseOrFail(String stamp) {
        return LocalDateTime.parse(stamp, TimeFormats.STAMP);
    }
}
