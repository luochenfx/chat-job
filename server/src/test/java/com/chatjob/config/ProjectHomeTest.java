package com.chatjob.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectHomeTest {

    @Test
    void 从源码运行的classes目录反查出项目根(@TempDir Path root) throws IOException {
        Path classes = Files.createDirectories(root.resolve("server").resolve("target").resolve("classes"));
        Files.createFile(root.resolve("CONTEXT.md"));

        assertThat(ProjectHome.resolveFrom(classes, root.resolve("elsewhere"), null)).isEqualTo(root);
    }

    @Test
    void 从jar运行时反查出项目根(@TempDir Path root) throws IOException {
        Path target = Files.createDirectories(root.resolve("server").resolve("target"));
        Path jar = Files.createFile(target.resolve("chat-job-0.1.0-SNAPSHOT.jar"));
        Files.createFile(root.resolve("start.bat"));

        assertThat(ProjectHome.resolveFrom(jar, root.resolve("server"), null)).isEqualTo(root);
    }

    @Test
    void 工作目录在模块内也锚定到项目根(@TempDir Path root) throws IOException {
        Path classes = Files.createDirectories(root.resolve("server").resolve("target").resolve("classes"));
        Files.createFile(root.resolve("CONTEXT.md"));
        Path moduleDir = root.resolve("server");

        // 这正是 dev.bat 曾经的处境：cwd = server/，但数据必须落在仓库根。
        assertThat(ProjectHome.resolveFrom(classes, moduleDir, null)).isEqualTo(root);
    }

    @Test
    void 显式指定的项目根优先于一切探测(@TempDir Path root) throws IOException {
        Path classes = Files.createDirectories(root.resolve("server").resolve("target").resolve("classes"));
        Files.createFile(root.resolve("CONTEXT.md"));
        Path explicit = Files.createDirectories(root.resolve("deploy"));

        assertThat(ProjectHome.resolveFrom(classes, root, explicit.toString())).isEqualTo(explicit);
    }

    @Test
    void 陌生目录不冒充项目根时退回工作目录(@TempDir Path root) throws IOException {
        // 没有 CONTEXT.md / start.bat / .git 的 server 目录：不认，宁可用 cwd。
        Path classes = Files.createDirectories(root.resolve("server").resolve("target").resolve("classes"));
        Path workDir = Files.createDirectories(root.resolve("work"));

        assertThat(ProjectHome.resolveFrom(classes, workDir, null)).isEqualTo(workDir);
    }

    @Test
    void codeSource缺失时退回工作目录(@TempDir Path workDir) {
        assertThat(ProjectHome.resolveFrom(null, workDir, null)).isEqualTo(workDir);
    }

    @Test
    void 返回值不留尾部分隔符(@TempDir Path root) throws IOException {
        Path classes = Files.createDirectories(root.resolve("server").resolve("target").resolve("classes"));
        Files.createFile(root.resolve("CONTEXT.md"));

        String home = ProjectHome.resolveFrom(classes, root, null).toString();
        assertThat(home).doesNotEndWith("\\").doesNotEndWith("/");
    }
}
