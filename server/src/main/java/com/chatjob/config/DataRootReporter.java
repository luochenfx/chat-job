package com.chatjob.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 启动自证：把本进程实际使用的运行时路径打出来，并在磁盘上发现<b>第二个</b>数据库时告警。
 *
 * <p>这个类存在的理由只有一个：「读写哪个库」曾经是不可见的。两个库各自都能正常工作，
 * 所以症状不是报错，而是「昨天的投递记录不见了」「配置改了没生效」——排查成本极高。
 * 把绝对路径写进日志之后，这类问题第一次出现就能看出来。
 *
 * <p>告警不阻止启动（可能是有意的多库并存，比如对照测试），只是把隐性事实变成显性一行。
 */
@Component
public class DataRootReporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataRootReporter.class);
    private static final String SQLITE_PREFIX = "jdbc:sqlite:";
    private static final String DB_FILE = "chat-job.db";

    private final Environment environment;

    public DataRootReporter(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path home = Paths.get(environment.getProperty(ProjectHome.PROPERTY, ".")).toAbsolutePath().normalize();
        Path workDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();

        Map<String, Path> paths = new LinkedHashMap<>();
        paths.put("项目根", home);
        paths.put("工作目录", workDir);
        paths.put("数据库", databaseFile());
        paths.put("日志", configuredPath("logging.file.name"));
        paths.put("浏览器档案", configuredPath("chat-job.browser.user-data-dir"));
        paths.put("patchright driver", configuredPath("chat-job.driver.dir"));

        StringBuilder line = new StringBuilder("运行时数据位置:");
        paths.forEach((label, path) -> {
            if (path == null) {
                return;
            }
            line.append(System.lineSeparator())
                    .append("    ").append(label).append(" = ").append(path);
            if ("数据库".equals(label) && !Files.isRegularFile(path)) {
                line.append("（尚不存在，首次启动会创建）");
            }
        });
        log.info(line.toString());

        warnIfDiverged(home, workDir, paths.get("数据库"));
        if (!home.equals(workDir)) {
            log.warn("工作目录与项目根不一致（{} ≠ {}）。所有运行时路径已按项目根解析，"
                    + "因此不影响数据落点；但如果你在用相对路径访问其它文件，请留意这个差异。", workDir, home);
        }
    }

    /**
     * 找出磁盘上其它位置的 {@code chat-job.db}。命中就说明有人用另一套启动方式跑过，
     * 那份库不会随本次启动更新——这正是「数据看起来丢了」的来源。
     */
    private void warnIfDiverged(Path home, Path workDir, Path active) {
        Set<Path> candidates = new LinkedHashSet<>();
        candidates.add(home.resolve("data").resolve(DB_FILE));
        candidates.add(workDir.resolve("data").resolve(DB_FILE));
        candidates.add(workDir.resolve("server").resolve("data").resolve(DB_FILE));
        if (workDir.getParent() != null) {
            candidates.add(workDir.getParent().resolve("data").resolve(DB_FILE));
        }

        Path activeNormalized = active == null ? null : active.toAbsolutePath().normalize();
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (normalized.equals(activeNormalized) || !Files.isRegularFile(normalized)) {
                continue;
            }
            log.warn("发现第二份数据库 {} —— 本进程不会读写它。"
                    + "通常是从不同工作目录启动留下的（dev.bat / start.bat / IDE 各有自己的 cwd）。"
                    + "确认无用后应删除或归档，否则两边数据会持续分叉。", normalized);
        }
    }

    private Path configuredPath(String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        return Paths.get(value.trim()).toAbsolutePath().normalize();
    }

    /** 从 JDBC URL 里剥出 sqlite 文件路径：{@code jdbc:sqlite:/a/b.db?journal_mode=WAL} → {@code /a/b.db} */
    private Path databaseFile() {
        String url = environment.getProperty("spring.datasource.url", "");
        if (!url.startsWith(SQLITE_PREFIX)) {
            return null;
        }
        String rest = url.substring(SQLITE_PREFIX.length());
        int query = rest.indexOf('?');
        if (query >= 0) {
            rest = rest.substring(0, query);
        }
        // 内存库（:memory:）没有文件路径
        if (rest.isBlank() || rest.startsWith(":")) {
            return null;
        }
        return Paths.get(rest).toAbsolutePath().normalize();
    }
}
