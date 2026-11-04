package com.chatjob.testing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 仓库根目录。给「扫源码」的守卫测试用（{@code SseEventContractTest}、
 * {@code ConfigDefaultsAgreeTest}）。
 *
 * <p><b>mvn 在 {@code server/} 下跑</b>，而 {@code console/} 在仓库根，
 * 所以不能直接拿 {@code user.dir} 拼。两种启动位置都认：<ul>
 *   <li>{@code .../chat-job/server} → 有 {@code src/main/java}，取上一级；</li>
 *   <li>{@code .../chat-job} → 直接用它。</li>
 * </ul>
 *
 * <p>拿不到就抛，不返回 null：这类守卫一旦静默跳过就等于没写。
 */
public final class RepoRoot {

    private RepoRoot() {
    }

    public static Path of() {
        Path cwd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        Path root = Files.isDirectory(cwd.resolve("src/main/java")) ? cwd.getParent() : cwd;
        if (!Files.isDirectory(root.resolve("console"))) {
            throw new IllegalStateException("从 " + cwd + " 找不到仓库根（缺 console/ 目录）：" + root);
        }
        return root;
    }
}
