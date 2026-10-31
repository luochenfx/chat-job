package com.chatjob.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Paths;

/**
 * 应用级配置。只放"启动时决定、运行期不改"的参数，
 * 业务可调参数（配额、筛选条件、黑名单）一律在数据库里。
 */
@ConfigurationProperties(prefix = "chat-job")
public record AppProperties(Driver driver, Browser browser, SelfCheckProps selfCheck,
                            NotifyProps notifyConfig,
                            @DefaultValue("boss") String activePlatform) {

    /**
     * 缺省平台标识（{@code boss} / {@code zhaopin}）。
     *
     * <p><b>阶段 2b 之后它不再是「启用哪个平台」的开关</b>：两个适配器同时装配，
     * 由 {@code PlatformAdapters} 按平台索引，编排层与浏览器按平台各取一份。
     * 它只在调用方<b>没有显式指定平台</b>时起作用 —— 控制台没带 {@code platform} 参数、
     * 命令行没给 {@code -Dchat-job.active-platform} 的那类请求，落到这里。
     *
     * <p>保留默认值是为了「只关心一个平台」的调用点（脚本、单平台调试）不必每次带参数，
     * 而不是为了二选一 —— 那个语义在 2b 里已经删掉了。
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
     * @param userDataDir      专用 profile 的<b>根</b>目录，与日常浏览完全隔离。
     *                         实际目录是它下面按平台分出的子目录，见 {@link #userDataDirFor(String)}
     * @param slowMoMs         每个动作放慢的毫秒数
     * @param defaultTimeoutMs Page 的默认超时
     * @param channel          浏览器 channel：auto（默认，按 chrome → msedge 探测）/ chrome / msedge
     * @param executablePath   显式指定浏览器可执行文件，优先级高于 channel
     */
    public record Browser(String userDataDir, int slowMoMs, int defaultTimeoutMs,
                          @DefaultValue("auto") String channel,
                          @DefaultValue("") String executablePath) {

        /**
         * 平台各自的 profile 目录：{@code <root>/boss}、{@code <root>/zhaopin}。
         *
         * <p>必须是两个目录而不是一个：Chrome 对同一 user-data-dir 加独占锁，第二个实例
         * 起不来（报 {@code ProcessSingleton} 冲突）。而两个平台的登录态本来也不共用 ——
         * 都是各自扫码各自记住，共用同一 profile 会导致两个平台读到同一份登录态。
         *
         * <p>目录名直接用平台标识，不做大小写或别名映射：它出现在日志、控制台与磁盘上，
         * 三处必须能一眼对上。
         */
        public String userDataDirFor(String platform) {
            return Paths.get(userDataDir).resolve(platform).toString();
        }
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
