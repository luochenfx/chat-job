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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V18 的两件事：把已有的重复行去掉，以及让第二行插不进来。
 *
 * <p>直接把迁移文件当脚本跑（而不是在这里抄一遍 SQL）：抄一遍就只能证明「我抄的这句是对的」，
 * 证明不了「线上会跑的那句是对的」。仓库里没有 Spring 上下文测试，这条路径用真 SQLite +
 * 真脚本最接近实际。
 */
class V18SingleRowConfigTest {

    @TempDir
    Path tmp;

    @Test
    void 去重保留最早一行并挡住第二行() throws Exception {
        Path file = tmp.resolve("single.db");
        String url = "jdbc:sqlite:" + file.toAbsolutePath().toString().replace('\\', '/');
        try (Connection conn = DriverManager.getConnection(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
            jdbc.execute("CREATE TABLE ai_config (id INTEGER PRIMARY KEY AUTOINCREMENT, note TEXT NOT NULL DEFAULT '')");
            jdbc.execute("CREATE TABLE notify_config (id INTEGER PRIMARY KEY AUTOINCREMENT, note TEXT NOT NULL DEFAULT '')");
            // 模拟并发首访插出三行之后的状态
            for (int i = 0; i < 3; i++) {
                jdbc.update("INSERT INTO ai_config (note) VALUES ('row')");
                jdbc.update("INSERT INTO notify_config (note) VALUES ('row')");
            }

            ScriptUtils.executeSqlScript(conn, new EncodedResource(
                    new ClassPathResource("db/migration/V18__single_row_config.sql"), StandardCharsets.UTF_8));

            // 保留 id 最小的一行：ConfigStore 的 get(0) 一直读的就是它
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_config", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT MIN(id) FROM ai_config", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notify_config", Integer.class)).isEqualTo(1);

            // 索引之后，第二行插不进来 —— 「表里最多一行」由库保证，不再靠代码自觉
            assertThatThrownBy(() -> jdbc.update("INSERT INTO ai_config (note) VALUES ('again')"))
                    .hasMessageContaining("UNIQUE");
        }
    }
}
