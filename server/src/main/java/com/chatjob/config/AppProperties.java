package com.chatjob.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 应用级配置。只放"启动时决定、运行期不改"的参数，
 * 业务可调参数（配额、筛选条件、黑名单）一律在数据库里。
 */
@ConfigurationProperties(prefix = "chat-job")
public record AppProperties(Driver driver, Browser browser, SelfCheckProps selfCheck,
                            NotifyProps notifyConfig,
                            @DefaultValue("boss") String activePlatform) {

    /**
     * 当前启用的平台标识（{@code boss} / {@code zhaopin}）。
     *
     * <p>它是「装配期」的开关，不是运行期可切的业务开关：两个适配器同时注册会让
     * {@code PlatformAdapter} 注入点出现两个候选 bean，Spring 在启动期就直接失败。
     * 所以改平台＝改配置＋重启，回滚也是同理 —— 这一点在 spec 0004 阶段 1 里写明了。
     */
    public String activePlatform() {
        return activePlatform == null || activePlatform.isBlank() ? "boss" : activePlatform.trim();
    }

    /**
     * @param dir        patchright driver 目录，就绪判据是 {@code <dir>/package/cli.js} 存在
     * @param failClosed 找不到 driver 时是否拒绝启动任务。必须为 true —— 静默退回官方 driver
     *                   等于"以为有防护其实没有"，出问题是在账号被限制之后才发现
     */
    public record Driver(String dir, boolean failClosed) {
    }

    /**
     * @param userDataDir      专用 profile 目录，与日常浏览完全隔离
     * @param slowMoMs         每个动作放慢的毫秒数
     * @param defaultTimeoutMs Page 的默认超时
     * @param channel          浏览器 channel：auto（默认，按 chrome → msedge 探测）/ chrome / msedge
     * @param executablePath   显式指定浏览器可执行文件，优先级高于 channel
     */
    public record Browser(String userDataDir, int slowMoMs, int defaultTimeoutMs,
                          @DefaultValue("auto") String channel,
                          @DefaultValue("") String executablePath) {
    }

    public record SelfCheckProps(boolean enabled) {
    }

    /**
     * @param wecomWebhook          企业微信群机器人地址，空则不发通知
     * @param throttleWindowSeconds 同类事件合并窗口
     */
    public record NotifyProps(String wecomWebhook, int throttleWindowSeconds) {
    }
}
