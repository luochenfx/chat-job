package com.chatjob.scan;

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
 * 「卡片边界的判据只有一处」的结构守卫（ADR-0009）。
 *
 * <p>约定没有编译器保护：谁都可以在 {@code processCards} 里再抄一次
 * {@code if (delivered >= quota)}——它不会让任何测试变红，只会让「配额到底在哪判」重新变成
 * 全文搜索才能回答的问题。而这一类回退最容易发生在 3b 搬循环的时候：代码挪了位置，
 * 顺手补一句判据看起来毫无风险。
 *
 * <p>口径的边界：这里只钉三个比较表达式的出现位置，不解析语法树。写成
 * {@code quota(cfg) <= delivered} 这种等价形式会漏过 —— 与 {@code RunLogSingleExitTest} 一样，
 * 它挡的是复制粘贴式的回退，不是刻意绕过。
 */
class ScanBoundarySingleSiteTest {

    /** 判据唯一允许出现的位置 */
    private static final String OWNER = "com/chatjob/scan";

    private static final List<String> JUDGEMENTS = List.of(
            ">= quota",
            ">= evalLimit",
            "> limitSeconds");

    @Test
    void 配额与上限的判据只出现在扫描段里() {
        List<String> offenders = sources().stream()
                .filter(path -> !relative(path).startsWith(OWNER))
                .filter(path -> JUDGEMENTS.stream().anyMatch(read(path)::contains))
                .map(ScanBoundarySingleSiteTest::relative)
                .toList();

        assertThat(offenders)
                .as("配额 / 评估上限 / 时间上限的判据只允许在 %s；要改判据请改 ScanBoundary", OWNER)
                .isEmpty();
    }

    @Test
    void 扫描段自己确实带着这三条判据() {
        String source = read(javaSourceRoot().resolve(OWNER + "/ScanBoundary.java"));
        assertThat(JUDGEMENTS).allSatisfy(judgement -> assertThat(source)
                .as("ScanBoundary 里找不到判据「%s」——守卫指向了不存在的东西", judgement)
                .contains(judgement));
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
