package com.chatjob.orchestrator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 强杀之后留在库里的「进行中」组合必须被收尾。
 *
 * <p>待验的事实：组合循环把状态写成 RUNNING 之后，收尾写在 {@code finally} 里。
 * 进程被强杀时 finally 不会执行，于是 {@code combination_run} 留下一行永远 RUNNING ——
 * 任务页据此显示「进行中」，而那一轮早就结束了。这条路径没有编译器保护，只能钉在测试里。
 *
 * <p>用真实 SQLite 而不是打桩：待验的是 UPDATE 的 <b>范围</b>（只动 RUNNING、只动这个 Run），
 * 桩只会把断言变成「我自己塞进去的值又读回来了」。
 */
class RunStoreAbandonCombinationsTest {

    @TempDir
    Path tmp;

    private JdbcTemplate jdbc;
    private RunStore runStore;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:sqlite:" + tmp.resolve("combos.db").toAbsolutePath().toString().replace('\\', '/');
        jdbc = new JdbcTemplate(new SimpleDriverDataSource(new org.sqlite.JDBC(), url));

        // 与 V1__schema.sql 同构，只留本测试要碰的列
        jdbc.execute("""
                CREATE TABLE combination_run (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id      INTEGER NOT NULL,
                    seq         INTEGER NOT NULL,
                    city        TEXT    NOT NULL DEFAULT '',
                    keyword     TEXT    NOT NULL DEFAULT '',
                    status      TEXT    NOT NULL,
                    cards_seen  INTEGER NOT NULL DEFAULT 0,
                    evaluated   INTEGER NOT NULL DEFAULT 0,
                    delivered   INTEGER NOT NULL DEFAULT 0,
                    started_at  TEXT,
                    finished_at TEXT
                )
                """);

        // 四个 mapper 为了构造 RunStore 必须占位，这条 SQL 一个都不用。
        // 传 null 是刻意的：将来若把它改成依赖某个 mapper，这里会立刻 NPE 而不是静默通过
        runStore = new RunStore(null, null, null, null, jdbc);
    }

    @Test
    void 只把进行中的组合收尾成已中断() {
        insertCombo(7, 1, "RUNNING");
        insertCombo(7, 2, "DONE");
        insertCombo(7, 3, "SKIPPED");
        insertCombo(7, 4, "FAILED");
        insertCombo(7, 5, "PENDING");

        assertThat(runStore.abandonRunningCombinations(7L)).isEqualTo(1);

        assertThat(statusOf(7, 1)).isEqualTo("INTERRUPTED");
        assertThat(finishedAtOf(7, 1)).isNotNull();
        assertThat(statusOf(7, 2)).isEqualTo("DONE");
        assertThat(statusOf(7, 3)).isEqualTo("SKIPPED");
        assertThat(statusOf(7, 4)).isEqualTo("FAILED");
        assertThat(statusOf(7, 5)).isEqualTo("PENDING");
    }

    @Test
    void 别的Run的组合不受影响() {
        insertCombo(7, 1, "RUNNING");
        insertCombo(8, 1, "RUNNING");

        assertThat(runStore.abandonRunningCombinations(7L)).isEqualTo(1);

        assertThat(statusOf(7, 1)).isEqualTo("INTERRUPTED");
        // 另一个平台/另一轮正在跑的 Run 不能被上一轮的中断收尾波及
        assertThat(statusOf(8, 1)).isEqualTo("RUNNING");
    }

    @Test
    void 没停在组合中间时什么都不改() {
        insertCombo(7, 1, "DONE");

        assertThat(runStore.abandonRunningCombinations(7L)).isZero();

        assertThat(statusOf(7, 1)).isEqualTo("DONE");
    }

    @Test
    void 已跳过的组合不被改写() {
        // SKIPPED 是计划内的收手（配额到顶、值守到点），不能因为进程没了就被改成「已中断」——
        // 那样「为什么跳过」这个已经有答案的问题会被抹掉
        insertCombo(7, 1, "SKIPPED");

        runStore.abandonRunningCombinations(7L);

        assertThat(statusOf(7, 1)).isEqualTo("SKIPPED");
    }

    private void insertCombo(long runId, int seq, String status) {
        jdbc.update("INSERT INTO combination_run (run_id, seq, city, keyword, status) VALUES (?, ?, '北京', 'java', ?)",
                runId, seq, status);
    }

    private String statusOf(long runId, int seq) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status FROM combination_run WHERE run_id = ? AND seq = ?", runId, seq);
        return (String) rows.get(0).get("status");
    }

    private Object finishedAtOf(long runId, int seq) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT finished_at FROM combination_run WHERE run_id = ? AND seq = ?", runId, seq);
        return rows.get(0).get("finished_at");
    }
}
