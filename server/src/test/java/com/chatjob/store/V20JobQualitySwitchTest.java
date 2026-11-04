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
 * V20 加列之后，升级上来的库拿到的也是「关」。
 *
 * <p>这条用例只钉一件事：已有的 {@code platform_config} 行读出来是 0，不是 1。ADR-0017 说
 * 「迁移把已有行也改成关」，实现方式正是 {@code ADD COLUMN ... DEFAULT 0} —— 若有人后来把它
 * 改成「加列后再 UPDATE 回 1」，这里会红。
 *
 * <p>为什么值得单独跑脚本：加列失败的表现是「应用起不来」，错误只有一句 SQLite 原文，
 * 单元测试再多也不会碰到 {@code ALTER TABLE}。做法与 {@code V19JobQualityTest} 相同，
 * 直接跑迁移文件本身，不抄 SQL。
 */
class V20JobQualitySwitchTest {

    @TempDir
    Path tmp;

    @Test
    void 升级上来的平台行拿到的是关() throws Exception {
        Path file = tmp.resolve("v20.db");
        String url = "jdbc:sqlite:" + file.toAbsolutePath().toString().replace('\\', '/');
        try (Connection conn = DriverManager.getConnection(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
            jdbc.execute("CREATE TABLE platform_config (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "platform TEXT NOT NULL DEFAULT '')");
            // 造一行升级前的数据：它必须还在，并且拿到列默认值
            jdbc.update("INSERT INTO platform_config (platform) VALUES ('boss')");

            ScriptUtils.executeSqlScript(conn, new EncodedResource(
                    new ClassPathResource("db/migration/V20__job_quality_switch.sql"),
                    StandardCharsets.UTF_8));

            assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_config", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT job_quality_enabled FROM platform_config", Integer.class)).isZero();
        }
    }
}
