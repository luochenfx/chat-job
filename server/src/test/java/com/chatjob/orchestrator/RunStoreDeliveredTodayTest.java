package com.chatjob.orchestrator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「今日主动投递」的口径守卫（ADR-0004 第 1、3 条）。
 *
 * <p>这个数字直接决定配额刹车什么时候踩下去，而它现在只靠一行约定：
 * <b>只读 {@code daily_counter.delivered}</b>。约定没有编译器保护，所以用测试钉住。
 * 最容易发生的回退是「顺手把投递表也数进来」——那样一个 SKIPPED 或一次演练
 * 都会吃掉一次真实配额，而界面上看不出任何异常。
 *
 * <p>用真实 SQLite 而不是打桩：待验的是那条 SQL 的语义（平台隔离、按天、ON CONFLICT 累加），
 * 桩只会把断言变成「我自己塞进去的值又读回来了」。{@code sqlite-jdbc} 本就是正式依赖，
 * 临时库落在 JUnit 的 {@code @TempDir}，跑完即丢。
 */
class RunStoreDeliveredTodayTest {

    @TempDir
    Path tmp;

    private JdbcTemplate jdbc;
    private RunStore runStore;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:sqlite:" + tmp.resolve("counter.db").toAbsolutePath().toString().replace('\\', '/');
        jdbc = new JdbcTemplate(new SimpleDriverDataSource(new org.sqlite.JDBC(), url));

        // 与 V1__schema.sql 同构，只留本测试要碰的列，避免测试跟着无关的加列一起改
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
        jdbc.execute("""
                CREATE TABLE reply_log (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    action      TEXT    NOT NULL,
                    reply_source TEXT,
                    created_at  TEXT    NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE delivery (
                    id     INTEGER PRIMARY KEY AUTOINCREMENT,
                    status TEXT NOT NULL,
                    started_at TEXT NOT NULL
                )
                """);

        // 四个 mapper 为了构造 RunStore 必须占位，deliveredToday 一个都不用。
        // 传 null 是刻意的：将来若把它改成依赖某个 mapper，这里会立刻 NPE 而不是静默通过
        runStore = new RunStore(null, null, null, null, jdbc);
    }

    @Test
    void 空库当日为零() {
        assertThat(runStore.deliveredToday("boss")).isZero();
    }

    @Test
    void 每次确认送达累加一次() {
        runStore.incrementDelivered("boss");
        runStore.incrementDelivered("boss");

        assertThat(runStore.deliveredToday("boss")).isEqualTo(2);
    }

    @Test
    void 值守发简历不进主动投递() {
        runStore.incrementDelivered("boss");
        // 同一天里发了三份简历、失败了四次、还有两次演练跳过
        jdbc.update("INSERT INTO reply_log (action, reply_source, created_at) VALUES (?, ?, ?)",
                "REPLY_RESUME", "RESUME", RunStore.today() + "T10:00:00");
        jdbc.update("INSERT INTO reply_log (action, reply_source, created_at) VALUES (?, ?, ?)",
                "REPLY_RESUME", "RESUME", RunStore.today() + "T11:00:00");
        jdbc.update("INSERT INTO reply_log (action, reply_source, created_at) VALUES (?, ?, ?)",
                "REPLY_RESUME", "RESUME", RunStore.today() + "T12:00:00");
        jdbc.update("INSERT INTO delivery (status, started_at) VALUES ('FAILED', ?)", RunStore.today() + "T10:00:00");
        jdbc.update("INSERT INTO delivery (status, started_at) VALUES ('SKIPPED', ?)", RunStore.today() + "T10:01:00");
        jdbc.update("INSERT INTO delivery (status, started_at) VALUES ('DELIVERED', ?)", RunStore.today() + "T10:02:00");

        // 主动投递仍然是 1：别人先开口的事、失败的事、没有发生的事都不吃配额
        assertThat(runStore.deliveredToday("boss")).isEqualTo(1);
    }

    @Test
    void 昨天与明天都不算今天() {
        String yesterday = LocalDate.now().minusDays(1).toString();
        String tomorrow = LocalDate.now().plusDays(1).toString();
        jdbc.update("INSERT INTO daily_counter (platform, day, delivered, evaluated) VALUES (?, ?, ?, 0)",
                "boss", yesterday, 9);
        jdbc.update("INSERT INTO daily_counter (platform, day, delivered, evaluated) VALUES (?, ?, ?, 0)",
                "boss", tomorrow, 7);

        assertThat(runStore.deliveredToday("boss")).isZero();
    }

    @Test
    void 一个平台的配额不吃另一个平台的投递() {
        runStore.incrementDelivered("boss");
        runStore.incrementDelivered("zhaopin");
        runStore.incrementDelivered("zhaopin");

        assertThat(runStore.deliveredToday("boss")).isEqualTo(1);
        assertThat(runStore.deliveredToday("zhaopin")).isEqualTo(2);
    }

    @Test
    void 同一天重复累加走的是同一个计数行() {
        runStore.incrementDelivered("boss");
        runStore.incrementDelivered("boss");
        runStore.incrementDelivered("boss");

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM daily_counter WHERE platform = 'boss'", Integer.class);
        assertThat(rows).isEqualTo(1);
        assertThat(runStore.deliveredToday("boss")).isEqualTo(3);
    }
}
