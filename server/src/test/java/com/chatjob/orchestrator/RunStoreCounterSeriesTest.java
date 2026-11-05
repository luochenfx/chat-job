package com.chatjob.orchestrator;

import com.chatjob.time.FixedTimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 概览卡 7 日走势的读口径（ADR-0018 第 1、3、6 条）。
 *
 * <p>要钉住的是三个容易被「顺手改好」破坏的性质：窗口只有 7 天且右边界是今天、
 * <b>没有行的那天不出现在结果里</b>、偏移由后端算而不是让界面自己减日期。
 * 中间那个最要紧：补 0 会把一个从没跑过的平台画成一条贴着 0 的线，
 * 读起来就是「天天投 0 个」。
 *
 * <p>与 {@code RunStoreDeliveredTodayTest} 同一套做法：真 SQLite 落在 {@code @TempDir}，
 * 因为待验的是那条 SQL 的区间语义，打桩只会把断言变成「我塞进去的值又读回来了」。
 */
class RunStoreCounterSeriesTest {

    /** 固定「今天」= 2026-03-15，窗口就是 03-09 .. 03-15 */
    private static final FixedTimeSource TIME = FixedTimeSource.of("2026-03-15T09:00:00");

    @TempDir
    Path tmp;

    private JdbcTemplate jdbc;
    private RunStore runStore;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:sqlite:" + tmp.resolve("series.db").toAbsolutePath().toString().replace('\\', '/');
        jdbc = new JdbcTemplate(new SimpleDriverDataSource(new org.sqlite.JDBC(), url));

        jdbc.execute("""
                CREATE TABLE daily_counter (
                    id        INTEGER PRIMARY KEY AUTOINCREMENT,
                    platform  TEXT    NOT NULL,
                    day       TEXT    NOT NULL,
                    delivered INTEGER NOT NULL DEFAULT 0,
                    evaluated INTEGER NOT NULL DEFAULT 0
                )
                """);
        jdbc.execute("CREATE UNIQUE INDEX uk_daily_counter ON daily_counter(platform, day)");

        // 四个 mapper 传 null 是刻意的：counterSeries 一个都不用，
        // 将来若改成依赖某个 mapper，这里会立刻 NPE 而不是静默通过
        runStore = new RunStore(null, null, null, null, jdbc, TIME);
    }

    private void row(String platform, String day, int delivered, int evaluated) {
        jdbc.update("INSERT INTO daily_counter (platform, day, delivered, evaluated) VALUES (?, ?, ?, ?)",
                platform, day, delivered, evaluated);
    }

    @Test
    void 空库返回空列表() {
        assertThat(runStore.counterSeries("boss")).isEmpty();
    }

    @Test
    void 只读最近七天_第八天在窗口外() {
        row("boss", "2026-03-08", 9, 90);
        row("boss", "2026-03-09", 1, 10);

        assertThat(runStore.counterSeries("boss"))
                .extracting(RunStore.CounterPoint::day)
                .containsExactly("2026-03-09");
    }

    @Test
    void 缺的那天不出现在结果里_不补零() {
        row("boss", "2026-03-13", 4, 40);
        row("boss", "2026-03-15", 7, 70);

        // 03-14 没有行：结果里就该只有两个点，而不是补一个 delivered=0 的点
        assertThat(runStore.counterSeries("boss")).containsExactly(
                new RunStore.CounterPoint("2026-03-13", 2, 4, 40),
                new RunStore.CounterPoint("2026-03-15", 0, 7, 70));
    }

    @Test
    void 偏移是距今几天_今天为零六天前为六() {
        row("boss", "2026-03-09", 1, 1);
        row("boss", "2026-03-15", 2, 2);

        assertThat(runStore.counterSeries("boss"))
                .extracting(RunStore.CounterPoint::offset)
                .containsExactly(6, 0);
    }

    @Test
    void 一个平台的曲线不混进另一个平台() {
        row("boss", "2026-03-15", 3, 30);
        row("zhaopin", "2026-03-15", 8, 80);

        assertThat(runStore.counterSeries("boss")).containsExactly(
                new RunStore.CounterPoint("2026-03-15", 0, 3, 30));
        assertThat(runStore.counterSeries("zhaopin")).containsExactly(
                new RunStore.CounterPoint("2026-03-15", 0, 8, 80));
    }

    @Test
    void 明天的行不进曲线() {
        row("boss", "2026-03-16", 5, 50);

        assertThat(runStore.counterSeries("boss")).isEmpty();
    }

    @Test
    void 日期脏的行跳过_其余点照常返回() {
        // '2026-03-1' 能通过字符串区间（比 '2026-03-09' 大、是 '2026-03-15' 的前缀），
        // 但 LocalDate.parse 会抛异常 —— 一行脏数据不该让每 5 秒轮询一次的概览 500
        row("boss", "2026-03-1", 3, 30);
        row("boss", "2026-03-15", 2, 20);

        assertThat(runStore.counterSeries("boss")).containsExactly(
                new RunStore.CounterPoint("2026-03-15", 0, 2, 20));
    }
}
