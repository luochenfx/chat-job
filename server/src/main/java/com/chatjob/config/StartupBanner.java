package com.chatjob.config;

import com.chatjob.driver.BrowserChannel;
import com.chatjob.driver.PatchrightDriver;
import com.chatjob.platform.PlatformAdapters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

/**
 * 启动横幅：把「相对路径最终解析成了哪里」和「用哪个浏览器」直接打出来。
 *
 * <p>存在的理由很具体：application.yaml 里的 {@code ./data} 与 {@code ./build/patchright-driver}
 * 都相对工作目录解析。从仓库根启动和从 server/ 启动，会得到两个不同的数据库文件，
 * 表现是"明明配置过，重启后全是初始值"。这类问题靠事后排查成本极高，
 * 一行日志就能当场暴露，所以宁可啰嗦。
 */
@Slf4j
@Component
public class StartupBanner {

    private final AppProperties props;
    private final PatchrightDriver patchright;
    private final BrowserChannel browserChannel;
    private final PlatformAdapters adapters;

    public StartupBanner(AppProperties props, PatchrightDriver patchright, BrowserChannel browserChannel,
                         PlatformAdapters adapters) {
        this.props = props;
        this.patchright = patchright;
        this.browserChannel = browserChannel;
        this.adapters = adapters;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void print() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path dataDir = Paths.get("data").toAbsolutePath();
        Path driverDir = Paths.get(props.driver().dir()).toAbsolutePath();
        BrowserChannel.Choice choice = browserChannel.resolve();
        // 每个平台一条：并行之后「profile 是哪一个」不再是单数，只打根目录会让人以为两平台共用一个
        String profiles = adapters.platforms().stream()
                .map(platform -> platform + " = "
                        + Paths.get(props.browser().userDataDirFor(platform)).toAbsolutePath())
                .collect(Collectors.joining("，"));

        log.info("""
                
                ────────────────────────────────────────────────────────────
                 chat-job 已启动
                 已装配平台: {}（缺省 {}，chat-job.active-platform）
                 工作目录 : {}
                 数据目录 : {}（相对 ./data，以工作目录为基准）
                 profile  : {}（根目录 {}，每平台一个子目录）
                 浏览器   : {} —— {}
                 driver   : {} —— {}
                 提示     : 请始终从仓库根启动；从 server/ 启动会生成另一份数据库
                ────────────────────────────────────────────────────────────""",
                adapters.platforms(), props.activePlatform(),
                cwd,
                dataDir,
                profiles,
                Paths.get(props.browser().userDataDir()).toAbsolutePath(),
                choice.label(), choice.note(),
                driverDir, patchright.inspect().message());
    }
}
