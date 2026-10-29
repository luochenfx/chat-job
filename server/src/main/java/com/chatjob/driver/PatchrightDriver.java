package com.chatjob.driver;

import com.chatjob.config.AppProperties;
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
                        "找到了 patchright driver，但 PATH 里没有 node。请先安装 Node.js。");
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

    private String locateNode() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
        List<String> names = windows
                ? List.of("node.exe", "node.cmd", "node.bat", "node")
                : List.of("node");
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
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
}
