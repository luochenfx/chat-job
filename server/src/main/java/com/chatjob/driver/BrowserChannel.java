package com.chatjob.driver;

import com.chatjob.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 决定用本机哪个 Chromium 系浏览器来跑。
 *
 * <p>原始设计写死 {@code channel("chrome")}，在只装了 Edge 的机器上会直接抛
 * "Chromium distribution 'chrome' is not found" —— 报错信息指向的仍是官方 Playwright CLI，
 * 而当前 driver 已被替换成 patchright，照着提示去执行 {@code install chrome} 是无效动作。
 * 所以这里显式做一次解析：配置优先，其次按 chrome → msedge 探测，最后才落到内置 Chromium。
 *
 * <p>为什么不用内置 Chromium 当默认：它是一个全新的浏览器，没有用户日常积累的登录态，
 * 而本项目的登录态复用是本机真浏览器 profile 的核心价值。内置 Chromium 只作为兜底，
 * 并在状态里标明"需要另行安装"。
 *
 * <p>注意这不是"静默降级"：解析结果会出现在 {@code /api/system/status} 与自检详情里，
 * 用户能看见自己实际跑的是 Chrome 还是 Edge。
 */
@Slf4j
@Component
public class BrowserChannel {

    /**
     * @param channel        传给 {@code setChannel} 的值，null 表示不指定（用内置 Chromium）
     * @param executablePath 显式可执行文件路径，优先于 channel
     * @param label          给人看的名字
     * @param note           解析说明，会显示在控制台
     * @param resolved       是否解析到了一个确定存在的浏览器
     */
    public record Choice(String channel, String executablePath, String label, String note, boolean resolved) {
    }

    private final AppProperties props;

    public BrowserChannel(AppProperties props) {
        this.props = props;
    }

    public Choice resolve() {
        String explicitPath = props.browser().executablePath();
        if (explicitPath != null && !explicitPath.isBlank()) {
            try {
                Path path = Paths.get(explicitPath);
                if (Files.isRegularFile(path)) {
                    return new Choice(null, path.toString(), "自定义浏览器",
                            "使用配置的 executable-path", true);
                }
            } catch (InvalidPathException e) {
                return new Choice(null, null, "自定义浏览器",
                        "executable-path 不是合法路径: " + explicitPath, false);
            }
            return new Choice(null, null, "自定义浏览器",
                    "配置的 executable-path 不存在: " + explicitPath, false);
        }

        String configured = props.browser().channel();
        boolean auto = configured == null || configured.isBlank()
                || configured.equalsIgnoreCase("auto");
        if (!auto) {
            // 显式指定就不做探测：配置写了 chrome 就只认 chrome，不要在别的浏览器上跑
            return new Choice(configured, null, configured,
                    "按配置使用 channel=" + configured + "（未做可用性探测）", true);
        }

        List<String> missing = new ArrayList<>();
        for (Candidate candidate : CANDIDATES) {
            String found = candidate.locate();
            if (found != null) {
                log.info("浏览器自动解析: 使用 {}（{}）", candidate.label(), found);
                return new Choice(candidate.channel(), null, candidate.label(),
                        "自动识别命中，可执行文件: " + found, true);
            }
            missing.add(candidate.label());
        }

        return new Choice(null, null, "内置 Chromium",
                "未找到 " + String.join(" / ", missing)
                        + "。将尝试 patchright 内置 Chromium，若未下载则启动失败；"
                        + "也可安装 Chrome 后在配置页把 channel 指定为 chrome", false);
    }

    /** 一个候选浏览器：channel 名 + 若干可能的安装路径 */
    private record Candidate(String channel, String label, List<String> relativePaths) {

        String locate() {
            for (String root : ROOTS) {
                for (String relative : relativePaths) {
                    try {
                        Path path = Paths.get(root, relative);
                        if (Files.isRegularFile(path)) {
                            return path.toString();
                        }
                    } catch (InvalidPathException ignore) {
                        // 环境变量里有非法字符，跳过这个根目录
                    }
                }
            }
            return null;
        }
    }

    /** 只在 Windows 上找：DPAPI 加密与 .bat 启动脚本已经把本项目钉在 Windows 上了 */
    private static final List<String> ROOTS = rootCandidates();

    private static List<String> rootCandidates() {
        List<String> roots = new ArrayList<>();
        for (String key : List.of("LOCALAPPDATA", "PROGRAMFILES", "PROGRAMFILES(X86)", "PROGRAMW6432")) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                roots.add(value);
            }
        }
        return List.copyOf(roots);
    }

    private static final List<Candidate> CANDIDATES = List.of(
            new Candidate("chrome", "Google Chrome", List.of(
                    "Google\\Chrome\\Application\\chrome.exe")),
            new Candidate("msedge", "Microsoft Edge", List.of(
                    "Microsoft\\Edge\\Application\\msedge.exe")));

    /** 供状态接口输出当前的浏览器画像 */
    public String describe() {
        Choice choice = resolve();
        return String.format(Locale.ROOT, "%s（%s）", choice.label(), choice.note());
    }
}
