package com.chatjob.runlog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chatjob.store.entity.RunEntity;
import com.chatjob.time.FixedTimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运行日志出口的两条通道（ADR-0008）。
 *
 * <p>验三件事，每一件都曾经以另一种形式出过问题：
 * <ol>
 *   <li><b>两处副本同源</b>：同一句话同时进 {@code run_log} 与文件。改造前值守那 41 条只进文件，
 *       控制台面板读的是库，于是「值守跑了」在界面上无从确认。</li>
 *   <li><b>级别在文件里也对</b>：改造前无论 INFO 还是 WARN 都调 {@code log.info}，
 *       文件里全是 INFO —— 按级别过滤时 WARN 会消失。</li>
 *   <li><b>没有 Run 就没有日志</b>：{@code run_log.run_id} 允许为空只是 V1 的历史形状，
 *       不提供 no-op 变体，否则「写不进去」会变成静默行为。</li>
 * </ol>
 *
 * <p>用真实 SQLite 而不是桩：要验的是「写进哪张表、写成什么样」，桩只会把断言变成
 * 「自己塞进去的值又读回来了」。临时库落在 JUnit 的 {@code @TempDir}，跑完即丢。
 */
class RunLogTest {

    private static final FixedTimeSource TIME = FixedTimeSource.of("2026-03-15T09:00:00");
    private static final String PLATFORM = "boss";

    @TempDir
    Path tmp;

    private JdbcTemplate jdbc;
    private RunLogs runLogs;

    @BeforeEach
    void setUp() {
        String url = "jdbc:sqlite:" + tmp.resolve("runlog.db").toAbsolutePath().toString().replace('\\', '/');
        jdbc = new JdbcTemplate(new SimpleDriverDataSource(new org.sqlite.JDBC(), url));
        // 与 V1__schema.sql 的 run_log 同构
        jdbc.execute("""
                CREATE TABLE run_log (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id     INTEGER,
                    level      TEXT NOT NULL DEFAULT 'INFO',
                    message    TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """);
        runLogs = new RunLogs(jdbc, TIME);
    }

    private static RunEntity run(long id) {
        RunEntity run = new RunEntity();
        run.setId(id);
        run.setPlatform(PLATFORM);
        return run;
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("SELECT run_id, level, message, created_at FROM run_log ORDER BY id");
    }

    @Test
    void 一条日志同时写库与文件且内容一致() {
        List<ILoggingEvent> events = capture(() -> runLogs.of(run(7)).info("任务启动，模式 FULL"));

        assertThat(rows()).hasSize(1);
        Map<String, Object> row = rows().get(0);
        assertThat(((Number) row.get("run_id")).longValue()).isEqualTo(7L);
        assertThat(row.get("level")).isEqualTo("INFO");
        assertThat(row.get("message")).isEqualTo("任务启动，模式 FULL");
        assertThat(row.get("created_at")).isEqualTo(TIME.stamp());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.INFO);
        // 前缀只在这一层拼：并发时两条时间线在同一个文件里交错，没有前缀就分不清是谁
        assertThat(events.get(0).getFormattedMessage()).isEqualTo("[boss] [run 7] 任务启动，模式 FULL");
    }

    @Test
    void 警告在文件里也是警告而不是信息() {
        List<ILoggingEvent> events = capture(() -> runLogs.of(run(9)).warn("投递失败（未确认送达）"));

        assertThat(rows().get(0).get("level")).isEqualTo("WARN");
        assertThat(events.get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void 级别由运行结果决定时经log分派() {
        RunLog runLog = runLogs.of(run(11));
        List<ILoggingEvent> events = capture(() -> {
            runLog.log(RunLogLevel.INFO, "正常收尾");
            runLog.log(RunLogLevel.WARN, "异常收尾");
            runLog.log(RunLogLevel.ERROR, "崩溃");
        });

        assertThat(rows()).extracting(row -> row.get("level"))
                .containsExactly("INFO", "WARN", "ERROR");
        assertThat(events).extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.INFO, Level.WARN, Level.ERROR);
    }

    /**
     * 堆栈只进文件。
     *
     * <p>库里的行要能在列表里读，塞进堆栈会把一行撑成几十行；而需要堆栈的人本来就在看文件。
     */
    @Test
    void 带异常时堆栈只进文件() {
        RuntimeException boom = new IllegalStateException("browser closed");
        List<ILoggingEvent> events = capture(
                () -> runLogs.of(run(13)).error("任务执行失败：IllegalStateException: browser closed", boom));

        assertThat(rows().get(0).get("message").toString()).doesNotContain("\tat ");
        assertThat(rows().get(0).get("level")).isEqualTo("ERROR");
        assertThat(events.get(0).getLevel()).isEqualTo(Level.ERROR);
        assertThat(events.get(0).getThrowableProxy()).isNotNull();
        assertThat(events.get(0).getThrowableProxy().getMessage()).isEqualTo("browser closed");
    }

    @Test
    void 没有已落库的Run就没有日志出口() {
        assertThatThrownBy(() -> runLogs.of(null)).isInstanceOf(IllegalArgumentException.class);
        // 还没落库（没有 id）：写出来的行挂不到任何 Run 上，界面上会显示成一条孤儿日志
        assertThatThrownBy(() -> runLogs.of(new RunEntity())).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<ILoggingEvent> capture(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(RunLog.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
