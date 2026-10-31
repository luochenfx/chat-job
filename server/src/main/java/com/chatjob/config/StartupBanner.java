package com.chatjob.config;

import com.chatjob.driver.BrowserChannel;
import com.chatjob.driver.PatchrightDriver;
import com.chatjob.platform.PlatformInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

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

    public StartupBanner(AppProperties props, PatchrightDriver patchright, BrowserChannel browserChannel) {
        this.props = props;
        this.patchright = patchright;
        this.browserChannel = browserChannel;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void print() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path dataDir = Paths.get("data").toAbsolutePath();
        Path driverDir = Paths.get(props.driver().dir()).toAbsolutePath();
        BrowserChannel.Choice choice = browserChannel.resolve();

        log.info("""
                
                ────────────────────────────────────────────────────────────
                 chat-job 已启动
                 平台     : {}（chat-job.active-platform；换平台要重启）
                 工作目录 : {}
                 数据目录 : {}（相对 ./data，以工作目录为基准）
                 profile  : {}（根目录 {}，每平台一个子目录）
                 浏览器   : {} —— {}
                 driver   : {} —— {}
                 提示     : 请始终从仓库根启动；从 server/ 启动会生成另一份数据库
                ────────────────────────────────────────────────────────────""",
                PlatformInfo.of(props.activePlatform()).label() + " / " + props.activePlatform(),
                cwd,
                dataDir,
                Paths.get(props.browser().userDataDirFor(props.activePlatform())).toAbsolutePath(),
                Paths.get(props.browser().userDataDir()).toAbsolutePath(),
                choice.label(), choice.note(),
                driverDir, patchright.inspect().message());
    }
}
