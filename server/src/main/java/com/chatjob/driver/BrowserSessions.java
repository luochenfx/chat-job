package com.chatjob.driver;

import com.chatjob.config.AppProperties;
import com.chatjob.platform.PlatformSwitches;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台 → 浏览器全套 的注册表。ADR-0003 第 1 条「每个平台一条独立流水线」在 driver 层就落在这里。
 *
 * <p>三条刻意的规则：
 * <ul>
 *   <li><b>惰性创建</b>：只在第一次 {@link #of(String)} 时 new 出对象，<b>不启动浏览器</b>。
 *       浏览器仍然要等到 {@code ensureReady()} / {@code newPage()} 才真的起来，
 *       所以「控制台刷一次页面就把两个 Chrome 拉起来」不会因为 2a 而变成事实。</li>
 *   <li><b>按平台闭环</b>：每个平台有自己的一份 {@link Playwright} 与 profile 目录，
 *       一个平台的浏览器崩溃、被子进程杀掉、被用户手动关窗口，都只影响它自己那份
 *       {@code closed} 状态，另一份照常跑。</li>
 *   <li><b>不预建全部平台</b>：{@link #of} 只创建被问到的那一个。第三、第四个平台接进来时，
 *       没启用的平台不会白占一条线程和一个空目录。</li>
 * </ul>
 *
 * <p>唯一允许「问但不建」的入口是 {@link #existing(String)}：控制台要显示某平台浏览器是否已起，
 * 而那个查询绝不能顺手把浏览器创建出来。
 */
@Slf4j
@Component
public class BrowserSessions {

    private final Map<String, PlatformBrowser> byPlatform = new ConcurrentHashMap<>();

    private final AppProperties props;
    private final PatchrightDriver patchright;
    private final BrowserChannel browserChannel;
    private final PlatformSwitches switches;

    public BrowserSessions(AppProperties props, PatchrightDriver patchright, BrowserChannel browserChannel,
                           PlatformSwitches switches) {
        this.props = props;
        this.patchright = patchright;
        this.browserChannel = browserChannel;
        this.switches = switches;
    }

    /**
     * 取该平台的浏览器全套，没有就建一个空壳（不启动浏览器）。
     *
     * @param platform 平台标识；空值按当前装配的平台处理，避免调用点各自兜底出不同结果
     */
    public PlatformBrowser of(String platform) {
        String key = normalize(platform);
        return byPlatform.computeIfAbsent(key, p -> {
            PlaywrightExecutor executor = new PlaywrightExecutor(p);
            BrowserSession session = new BrowserSession(p, props, executor, patchright, browserChannel, switches);
            log.debug("[{}] 已建立独立浏览器上下文声明（profile: {}，线程: {}）",
                    p, session.userDataDir(), executor.threadName());
            return new PlatformBrowser(p, executor, session);
        });
    }

    /** 只查不建：控制台展示用，不能因为看一眼就把浏览器造出来 */
    public Optional<PlatformBrowser> existing(String platform) {
        return Optional.ofNullable(byPlatform.get(normalize(platform)));
    }

    /** 已创建的平台（顺序即创建顺序，便于日志与调试对齐） */
    public Set<String> platforms() {
        return new LinkedHashSet<>(byPlatform.keySet());
    }

    /**
     * 应用退出时收摊：先关浏览器（它跑在自己的线程上），再停线程池。
     *
     * <p>顺序反了会留下一个关不掉的 Chrome —— {@code close()} 要靠那条线程去执行。
     */
    @PreDestroy
    public void closeAll() {
        List<PlatformBrowser> all = new ArrayList<>(byPlatform.values());
        for (PlatformBrowser browser : all) {
            try {
                browser.session().close();
            } catch (Exception e) {
                log.debug("[{}] 关闭浏览器失败: {}", browser.platform(), e.getMessage());
            }
        }
        for (PlatformBrowser browser : all) {
            try {
                browser.executor().shutdown();
            } catch (Exception e) {
                log.debug("[{}] 停止 Playwright 线程失败: {}", browser.platform(), e.getMessage());
            }
        }
        if (!all.isEmpty()) {
            log.info("已关闭 {} 个平台的浏览器会话: {}", all.size(), platforms());
        }
    }

    private String normalize(String platform) {
        if (platform == null || platform.isBlank()) {
            return props.activePlatform();
        }
        // 与 PlatformInfo 一致用 ROOT：默认 locale 下 "BOSS" 在土耳其语环境会变成 "boss" 之外的东西
        return platform.trim().toLowerCase(Locale.ROOT);
    }
}
