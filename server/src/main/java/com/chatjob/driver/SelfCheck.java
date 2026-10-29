package com.chatjob.driver;

import com.chatjob.config.AppProperties;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动自检：断言"确实跑在打过补丁的 driver 上，且指纹没露馅"。
 *
 * <p>存在的意义是消除 fail-open 的隐性状态 —— 用户以为有防护、实际没有，
 * 等到账号被限制才发现。这里把它变成"启动即失败"，而不是一条 warn 日志。
 */
@Slf4j
@Component
public class SelfCheck {

    private final BrowserSession session;
    private final PatchrightDriver patchright;
    private final BrowserChannel browserChannel;
    private final AppProperties props;

    /** 页面侧断言，返回一个 JSON 友好的 map */
    private static final String PROBE = """
            () => {
              const cdcKeys = Object.keys(window).filter(k => k.startsWith('cdc_') || k.startsWith('$cdc_'));
              return {
                webdriver: navigator.webdriver,
                cdcKeys: cdcKeys,
                uaData: !!navigator.userAgentData,
                languages: (navigator.languages || []).length,
                plugins: navigator.plugins ? navigator.plugins.length : -1,
                platform: navigator.platform || '',
                chromeObject: typeof window.chrome === 'object' && window.chrome !== null
              };
            }
            """;

    public SelfCheck(BrowserSession session, PatchrightDriver patchright, BrowserChannel browserChannel,
                     AppProperties props) {
        this.session = session;
        this.patchright = patchright;
        this.browserChannel = browserChannel;
        this.props = props;
    }

    /**
     * @param ok       是否通过
     * @param driver   实际使用的 driver 目录
     * @param details  指纹探测结果
     * @param failures 未通过的具体原因（空表示通过）
     */
    public record Result(boolean ok, String driver, Map<String, Object> details, List<String> failures) {
    }

    public Result run() {
        if (!props.selfCheck().enabled()) {
            return new Result(true, patchright.inspect().driverDir(), Map.of(), List.of());
        }
        Map<String, Object> details = new LinkedHashMap<>();
        List<String> failures = new java.util.ArrayList<>();

        String driverDir = patchright.inspect().driverDir();
        details.put("driver", driverDir == null ? "未装配" : driverDir);
        if (driverDir == null) {
            failures.add("未使用 patchright driver");
        }

        BrowserChannel.Choice choice = browserChannel.resolve();
        details.put("browser", choice.label());
        details.put("browserNote", choice.note());
        if (!choice.resolved()) {
            // 不直接判失败：显式指定的 channel 走的是"未探测"分支，仍可能启动成功。
            // 但"一个都没找到"这种情况必须提前讲清楚，否则用户看到的只是 Playwright 一句
            // 指向官方 CLI 的英文报错，而那套 CLI 在这里根本不生效。
            details.put("warning", choice.note());
        }

        Page page = null;
        try {
            page = session.newPage();
            page.navigate("about:blank");
            Object raw = page.evaluate(PROBE);
            if (raw instanceof Map<?, ?> map) {
                details.putAll((Map<String, Object>) map);
            }

            Object webdriver = details.get("webdriver");
            boolean webdriverExposed = webdriver instanceof Boolean b && b;
            if (webdriverExposed) {
                failures.add("navigator.webdriver 为 true");
            }
            Object cdc = details.get("cdcKeys");
            if (cdc instanceof List<?> list && !list.isEmpty()) {
                failures.add("检测到 cdc_ 残留: " + list);
            }
            Object uaData = details.get("uaData");
            if (uaData instanceof Boolean b && !b) {
                // 不是硬失败：某些旧版 Chrome 没有 userAgentData，只记录
                details.put("warning", "navigator.userAgentData 不存在（可能是旧版 Chrome）");
            }
        } catch (Exception e) {
            failures.add("自检执行异常: " + e.getMessage());
        } finally {
            if (page != null) {
                try {
                    page.close();
                } catch (Exception ignore) {
                    // 自检页关不掉不影响结论
                }
            }
        }

        boolean ok = failures.isEmpty();
        log.info("启动自检 {} | driver={} | details={} | failures={}",
                ok ? "通过" : "失败", driverDir, details, failures);
        return new Result(ok, driverDir, details, failures);
    }
}
