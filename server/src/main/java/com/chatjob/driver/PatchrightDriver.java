package com.chatjob.driver;

import com.chatjob.config.AppProperties;
import com.chatjob.config.ProjectHome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * patchright driver 装配。
 *
 * <p>playwright-java 只是个壳，真正干活的是它拉起的 Node driver 进程。patchright 是打过
 * 反检测补丁的 Playwright（禁用 Console API 泄漏、去掉 {@code --enable-automation}、
 * 屏蔽 {@code navigator.webdriver} 等），官方没有 Java 绑定，但 Java 客户端支持
 * {@code -Dplaywright.cli.dir} 指向一个预装的 driver 目录，于是可以让 Java 客户端
 * 去驱动 patchright 的 Node driver。
 *
 * <p>目录结构（见 {@code com.microsoft.playwright.impl.driver.Driver}）：
 * {@code <driverDir>/package/cli.js}。node 可执行文件通过 {@code PLAYWRIGHT_NODEJS_PATH} 传。
 */
@Slf4j
@Component
public class PatchrightDriver {

    private final AppProperties props;
    private volatile Status cached;

    public PatchrightDriver(AppProperties props) {
        this.props = props;
    }

    /**
     * @param available  是否可直接用于启动浏览器
     * @param driverDir  实际命中的 driver 目录
     * @param nodePath   实际使用的 node 可执行文件
     * @param message    给人看的说明（控制台系统页直接展示）
     */
    public record Status(boolean available, String driverDir, String nodePath, String message) {
    }

    public Status inspect() {
        Status local = cached;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cached != null) {
                return cached;
            }
            Path dir = locateDriverDir();
            String node = locateNode();
            Status status;
            if (dir == null) {
                status = new Status(false, null, node,
                        "未找到 patchright driver（缺 " + props.driver().dir() + "/package/cli.js）。"
                                + "请先执行 scripts/install-patchright.mjs 装配 driver。");
            } else if (node == null) {
                status = new Status(false, dir.toString(), null,
                        "找到了 patchright driver，但没找到 node：PATH 与 "
                                + ProjectHome.resolve().resolve("runtime") + " 下都没有 node.exe。"
                                + "请安装 Node.js，或把 node.exe 放到 runtime/ 下。");
            } else {
                status = new Status(true, dir.toString(), node, "patchright driver 就绪");
            }
            log.info("driver 检查: available={}, dir={}, node={}", status.available(),
                    status.driverDir(), status.nodePath());
            cached = status;
            return status;
        }
    }

    /** 供 Playwright.create 使用的环境变量；driver 不可用时返回空 map（调用方应先判 fail-closed） */
    public Map<String, String> createOptionsEnv() {
        Status status = inspect();
        Map<String, String> env = new LinkedHashMap<>();
        if (status.driverDir() != null) {
            // Driver 从这个系统属性读 cli 目录，环境变量只负责 node
            System.setProperty("playwright.cli.dir", status.driverDir());
        }
        if (status.nodePath() != null) {
            env.put("PLAYWRIGHT_NODEJS_PATH", status.nodePath());
        }
        return env;
    }

    private Path locateDriverDir() {
        String configured = props.driver() == null ? null : props.driver().dir();
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        List<Path> candidates = new java.util.ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            candidates.add(cwd.resolve(configured).normalize());
            candidates.add(Paths.get(configured).toAbsolutePath().normalize());
        }
        candidates.add(cwd.resolve("build").resolve("patchright-driver"));
        candidates.add(cwd.resolve("patchright-driver"));
        candidates.add(cwd.resolve("server").resolve("build").resolve("patchright-driver"));
        if (cwd.getParent() != null) {
            candidates.add(cwd.getParent().resolve("build").resolve("patchright-driver"));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate.resolve("package").resolve("cli.js"))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 找 node 可执行文件的顺序：显式指定 → 随包内置 → PATH。
     *
     * <p><b>为什么要有「随包内置」这一档</b>：分发版（{@code node scripts/build-dist.mjs} 产出）
     * 把 node.exe 放在 {@code <home>/runtime/node.exe}，使用者不需要自己装 Node.js。
     * 只认 PATH 的话，对方机器上驱动必然判定为不可用，任务被 fail-closed 拦在起点，
     * 而报错读起来像是「驱动坏了」，指不到真正缺的东西。
     *
     * <p>显式指定的两条路（{@code -Dchat-job.node} / {@code CHAT_JOB_NODE}）留给
     * 「用自己那套 node 跑」的场景，例如想换 node 版本排查驱动兼容性。
     */
    private String locateNode() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
        String exe = windows ? "node.exe" : "node";

        Path explicit = explicitNode();
        if (explicit != null) {
            return explicit.toString();
        }

        for (Path candidate : bundledNodeCandidates(exe)) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }

        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        List<String> names = windows
                ? List.of("node.exe", "node.cmd", "node.bat", "node")
                : List.of("node");
        for (String entry : path.split(File.pathSeparator)) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            for (String name : names) {
                try {
                    Path candidate = Paths.get(entry.trim()).resolve(name);
                    if (Files.isRegularFile(candidate)) {
                        return candidate.toAbsolutePath().toString();
                    }
                } catch (Exception ignore) {
                    // PATH 里可能有非法路径
                }
            }
        }
        return null;
    }

    /** {@code -Dchat-job.node} / {@code CHAT_JOB_NODE}：指向不存在时打 warn 并继续往下找，不直接失败。 */
    private Path explicitNode() {
        String value = System.getProperty("chat-job.node");
        if (value == null || value.isBlank()) {
            value = System.getenv("CHAT_JOB_NODE");
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        Path candidate = Paths.get(value.trim());
        if (Files.isRegularFile(candidate)) {
            return candidate.toAbsolutePath();
        }
        log.warn("CHAT_JOB_NODE 指向的 node 不存在，忽略: {}", candidate);
        return null;
    }

    /**
     * 随包内置的 node 位置。两种布局都认：{@code runtime/node.exe}（当前分发包）
     * 与 {@code runtime/node/node.exe}（node 官方 zip 解出来的形状）。
     */
    private List<Path> bundledNodeCandidates(String exe) {
        Path runtime = ProjectHome.resolve().resolve("runtime");
        return List.of(runtime.resolve(exe), runtime.resolve("node").resolve(exe));
    }
}
