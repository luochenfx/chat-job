package com.chatjob.runlog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「运行日志只有一个出口」的结构守卫（ADR-0008）。
 *
 * <p>约定没有编译器保护：谁都可以在某个类里再写一次 {@code runLogMapper.insert}，或者顺手加回
 * {@code @Slf4j} 打一行 {@code log.info}。那种回退不会让测试变红，只会让下一次排查时
 * 「库里查不到这一条」—— 而排查的人正是最需要它的人。
 *
 * <p>所以这里扫源码，钉死三件事：
 * <ol>
 *   <li>{@code run_log} 的写操作只出现在 {@code com.chatjob.runlog} 包内；</li>
 *   <li>日志密集的编排类不再持有 slf4j 的 {@code Logger}；</li>
 *   <li>这些类里不出现裸的 {@code log.info/warn/error/debug}。</li>
 * </ol>
 *
 * <p><b>名单要跟着搬迁走。</b>待命段从 {@code RunService} 搬进 {@code StandbySegment} 时，
 * 它带走了原来那 30 多条日志调用；名单不跟着加，守卫就从「管住两个重日志类」悄悄缩回一个，
 * 而且不会变红 —— 这正是这条守卫存在的理由。
 *
 * <p>不用 ArchUnit：一条 {@code Files.readString} + 正则就够，与 {@code HumanizeSourceGuardTest} 同一种做法。
 */
class RunLogSingleExitTest {

    /** 写入 {@code run_log} 的唯一允许位置 */
    private static final String WRITE_OWNER = "com/chatjob/runlog";

    private static final List<String> BOUND_CLASSES = List.of(
            "com/chatjob/orchestrator/RunService.java",
            "com/chatjob/orchestrator/ScanSegment.java",
            "com/chatjob/orchestrator/StandbySegment.java",
            "com/chatjob/orchestrator/JobDisposition.java",
            "com/chatjob/watcher/UnreadWatcher.java");

    @Test
    void run_log只有RunLogs能写() {
        List<Path> offenders = sources().stream()
                .filter(path -> !relative(path).startsWith(WRITE_OWNER))
                .filter(path -> {
                    String source = read(path);
                    // 两种写法都认：走 jdbc 的 INSERT 与走 mapper 的 insert
                    return source.contains("INSERT INTO run_log") || source.contains("runLogMapper.insert");
                })
                .toList();

        assertThat(offenders)
                .as("run_log 的写入口只允许在 %s；要写日志请用 RunLogs.of(run)", WRITE_OWNER)
                .isEmpty();
    }

    @Test
    void 日志密集类不再直接打文件日志() {
        for (String name : BOUND_CLASSES) {
            String source = read(javaSourceRoot().resolve(name));
            assertThat(source)
                    .as("%s 不应再持有 slf4j 的 Logger（运行日志统一走 RunLog）", name)
                    .doesNotContain("@Slf4j")
                    .doesNotContain("LoggerFactory.getLogger");
            assertThat(bareLogCalls(source))
                    .as("%s 里仍有绕过 RunLog 的裸日志调用", name)
                    .isEmpty();
        }
    }

    private static List<String> bareLogCalls(String source) {
        return Stream.of("log.info(", "log.warn(", "log.error(", "log.debug(")
                .filter(source::contains)
                .toList();
    }

    private static List<Path> sources() {
        Path root = javaSourceRoot();
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relative(Path file) {
        return javaSourceRoot().relativize(file).toString().replace('\\', '/');
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 测试的工作目录是模块目录（{@code server/}），找不到就失败而不是静默跳过 */
    private static Path javaSourceRoot() {
        Path root = Paths.get(System.getProperty("user.dir", ".")).resolve("src/main/java").toAbsolutePath();
        assertThat(root).as("找不到主代码目录：%s", root).isDirectory();
        return root;
    }
}
