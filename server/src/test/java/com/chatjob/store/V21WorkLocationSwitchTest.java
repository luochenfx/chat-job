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
 * V21 加列之后，升级上来的库拿到的也是「关」。
 *
 * <p>与 {@link V20JobQualitySwitchTest} 同形：直接跑迁移文件本身，不抄 SQL。
 * 这条用例钉住的是「已有的配置行不会因为加了一列就悄悄开始替 HR 点按钮」。
 */
class V21WorkLocationSwitchTest {

    @TempDir
    Path tmp;

    @Test
    void 升级上来的平台行拿到的是关() throws Exception {
        Path file = tmp.resolve("v21.db");
        String url = "jdbc:sqlite:" + file.toAbsolutePath().toString().replace('\\', '/');
        try (Connection conn = DriverManager.getConnection(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
            jdbc.execute("CREATE TABLE platform_config (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "platform TEXT NOT NULL DEFAULT '')");
            jdbc.update("INSERT INTO platform_config (platform) VALUES ('boss')");

            ScriptUtils.executeSqlScript(conn, new EncodedResource(
                    new ClassPathResource("db/migration/V21__work_location_accept_switch.sql"),
                    StandardCharsets.UTF_8));

            assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_config", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT work_location_accept_enabled FROM platform_config", Integer.class)).isZero();
        }
    }
}
