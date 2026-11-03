package com.chatjob.runlog;

import com.chatjob.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 绑定到一条 Run 的实现。
 *
 * <p>包内可见：外部只能经 {@link RunLogs#of} 拿到接口，绕不过「runId 与平台成对给出」这件事。
 * 两者分开传是能编译的，但写出来的行会带着另一个平台的前缀 —— 排查时正是最误导人的一种错。
 *
 * <p>文件日志的 logger 名固定在接口上，不随实现类名走：{@code application.yaml} 里
 * {@code com.chatjob: DEBUG} 覆盖到它，换实现也不会让已有日志配置失效。
 */
final class BoundRunLog implements RunLog {

    private static final Logger FILE = LoggerFactory.getLogger(RunLog.class);

    private final long runId;
    private final String platform;
    private final JdbcTemplate jdbc;
    private final TimeSource time;

    BoundRunLog(long runId, String platform, JdbcTemplate jdbc, TimeSource time) {
        this.runId = runId;
        this.platform = platform;
        this.jdbc = jdbc;
        this.time = time;
    }

    @Override
    public void info(String message) {
        append(RunLogLevel.INFO, message, null);
    }

    @Override
    public void warn(String message) {
        append(RunLogLevel.WARN, message, null);
    }

    @Override
    public void error(String message) {
        append(RunLogLevel.ERROR, message, null);
    }

    @Override
    public void error(String message, Throwable cause) {
        append(RunLogLevel.ERROR, message, cause);
    }

    @Override
    public void warn(String message, Throwable cause) {
        append(RunLogLevel.WARN, message, cause);
    }

    @Override
    public void log(RunLogLevel level, String message) {
        switch (level) {
            case WARN -> warn(message);
            case ERROR -> error(message);
            default -> info(message);
        }
    }

    private void append(RunLogLevel level, String message, Throwable cause) {
        jdbc.update("INSERT INTO run_log (run_id, level, message, created_at) VALUES (?, ?, ?, ?)",
                runId, level.name(), message, time.stamp());

        String line = "[" + platform + "] [run " + runId + "] " + message;
        // 带异常时用「一个占位符 + 两个实参」的写法：slf4j 只把最后一个参数当异常，
        // 正文里出现的花括号就不会被误当成占位符吃掉异常
        switch (level) {
            case WARN -> {
                if (cause == null) {
                    FILE.warn(line);
                } else {
                    FILE.warn("{}", line, cause);
                }
            }
            case ERROR -> {
                if (cause == null) {
                    FILE.error(line);
                } else {
                    FILE.error("{}", line, cause);
                }
            }
            default -> {
                if (cause == null) {
                    FILE.info(line);
                } else {
                    FILE.info("{}", line, cause);
                }
            }
        }
    }
}
