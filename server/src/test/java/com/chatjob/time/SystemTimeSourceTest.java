package com.chatjob.time;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时间源的契约。
 *
 * <p>这些断言守的是<b>数据契约</b>而不是实现：库里所有时间都是可比较文本，字典序即时间序，
 * 一旦格式变了（比如把 {@code T} 换成空格），历史数据全部失去可比性，
 * 而读取侧只会表现为「解析失败」——离这里很远的地方。所以格式本身要钉在测试里。
 */
class SystemTimeSourceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final SystemTimeSource time = new SystemTimeSource(SHANGHAI);

    @Test
    void 落库文本带T且精确到秒() {
        // 形状与第 11 个字符是 'T' 由既有库数据决定，不是随便选的
        assertThat(time.stamp()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
        assertThat(time.stamp()).hasSize(19);
    }

    @Test
    void 自然日与当前时刻同源() {
        assertThat(time.today()).isEqualTo(time.now().toLocalDate().toString()).hasSize(10);
    }

    @Test
    void 时刻与文本往返一致() {
        LocalDateTime moment = LocalDateTime.of(2026, 3, 15, 9, 0, 0);
        assertThat(time.parse(time.format(moment))).contains(moment);
    }

    @Test
    void 清理窗口这类算出来的时刻也能落成同一形态() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 3, 15, 9, 0, 0).minusDays(30);
        assertThat(time.format(cutoff)).isEqualTo("2026-02-13T09:00:00");
        // 字典序即时间序：这是所有「按时间范围查」的 SQL 依赖的前提
        assertThat(time.format(cutoff)).isLessThan(time.format(cutoff.plusDays(1)));
    }

    @Test
    void 脏数据与空值都返回空而不是抛异常() {
        assertThat(time.parse(null)).isEmpty();
        assertThat(time.parse("")).isEmpty();
        assertThat(time.parse("   ")).isEmpty();
        assertThat(time.parse("2026-03-15 09:00:00")).isEmpty();   // 空格分隔的旧格式
        assertThat(time.parse("not-a-timestamp")).isEmpty();
        assertThat(time.parse("2026-13-45T99:99:99")).isEmpty();
    }

    @Test
    void 时区在构造时钉死而不是每次读系统默认值() {
        ZoneId utc = ZoneId.of("UTC");
        SystemTimeSource utcTime = new SystemTimeSource(utc);

        LocalDateTime before = LocalDateTime.now(SHANGHAI);
        LocalDateTime shanghaiNow = time.now();
        LocalDateTime utcNow = utcTime.now();
        LocalDateTime after = LocalDateTime.now(SHANGHAI);

        assertThat(shanghaiNow).isBetween(before.minusSeconds(1), after.plusSeconds(1));
        // 两个实例各自按自己的时区取值：差 480 分钟是上海与 UTC 的固定偏移。
        // 取到分钟而不是精确相等，因为两次 now() 之间会差几十微秒
        assertThat(Duration.between(utcNow, shanghaiNow).toMinutes()).isEqualTo(480);
    }
}
