package com.chatjob.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V19 能不能在真 SQLite 上跑通，以及四个默认值是不是落在该落的地方。
 *
 * <p>为什么值得一条单独的用例：迁移失败的表现是「应用起不来」，而错误只有一句 SQLite 原文；
 * 单元测试跑得再多也不会碰到 {@code ALTER TABLE}。这里直接把迁移文件当脚本跑（不抄 SQL），
 * 与 {@code V18SingleRowConfigTest} 同一种做法。
 *
 * <p>前置表只建 V19 依赖到的那几个最小列 —— 不是为了模拟真实库，而是为了让「加列」
 * 这件事本身被验证：多余的列不参与，缺的列会在这里就红。
 */
class V19JobQualityTest {

    @TempDir
    Path tmp;

    @Test
    void 质量表与四张表的加列都能在真库上跑通() throws Exception {
        Path file = tmp.resolve("v19.db");
        String url = "jdbc:sqlite:" + file.toAbsolutePath().toString().replace('\\', '/');
        try (Connection conn = DriverManager.getConnection(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
            jdbc.execute("CREATE TABLE job_posting (id INTEGER PRIMARY KEY AUTOINCREMENT)");
            jdbc.execute("CREATE TABLE conversation (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "platform TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL DEFAULT '')");
            jdbc.execute("CREATE TABLE ai_config (id INTEGER PRIMARY KEY AUTOINCREMENT)");
            jdbc.execute("CREATE TABLE notify_config (id INTEGER PRIMARY KEY AUTOINCREMENT)");
            // 造一行升级前的数据：加列之后它必须还在，且拿到列默认值
            jdbc.update("INSERT INTO conversation (platform) VALUES ('boss')");
            jdbc.update("INSERT INTO ai_config DEFAULT VALUES");
            jdbc.update("INSERT INTO notify_config DEFAULT VALUES");

            ScriptUtils.executeSqlScript(conn, new EncodedResource(
                    new ClassPathResource("db/migration/V19__job_quality.sql"), StandardCharsets.UTF_8));

            // 1) 新表建出来了
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='quality_assessment'",
                    Integer.class)).isEqualTo(1);
            // 2) 老数据还在，接管默认是「没接管」
            assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT auto_reply_paused FROM conversation", Integer.class)).isZero();
            // 3) 第四类告警默认开：升级后行为与今天一致，变化只在用户点了开关之后发生
            assertThat(jdbc.queryForObject("SELECT alert_hot_job FROM notify_config", Integer.class)).isEqualTo(1);
            // 4) 判断点为空的语义是「用默认六条」，不是「不判断」；分数线 80 与代码常量一致
            // 数字一律引 ConfigStore 的常量：测试自己抄一遍就变成第五处住所（ADR-0014 第 5 条）
            assertThat(jdbc.queryForObject("SELECT quality_criteria FROM ai_config", String.class)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT quality_score_line FROM ai_config", Integer.class))
                    .isEqualTo(ConfigStore.DEFAULT_QUALITY_SCORE_LINE);
            assertThat(jdbc.queryForObject("SELECT quality_min_hr_messages FROM ai_config", Integer.class))
                    .isEqualTo(ConfigStore.DEFAULT_QUALITY_MIN_HR_MESSAGES);
            assertThat(jdbc.queryForObject("SELECT quality_max_per_day FROM ai_config", Integer.class))
                    .isEqualTo(ConfigStore.DEFAULT_QUALITY_MAX_PER_DAY);
            assertThat(jdbc.queryForObject("SELECT quality_max_per_sweep FROM ai_config", Integer.class))
                    .isEqualTo(ConfigStore.DEFAULT_QUALITY_MAX_PER_SWEEP);
            assertThat(jdbc.queryForObject("SELECT quality_reeval_hours FROM ai_config", Integer.class))
                    .isEqualTo(ConfigStore.DEFAULT_QUALITY_REEVAL_HOURS);
        }
    }
}
