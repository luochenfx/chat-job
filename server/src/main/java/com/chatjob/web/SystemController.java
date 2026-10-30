package com.chatjob.web;

import com.chatjob.driver.BrowserChannel;
import com.chatjob.driver.BrowserSession;
import com.chatjob.driver.PatchrightDriver;
import com.chatjob.driver.SelfCheck;
import com.chatjob.notify.Notifier;
import com.chatjob.orchestrator.JobStore;
import com.chatjob.orchestrator.RunService;
import com.chatjob.orchestrator.RunStore;
import com.chatjob.platform.Models.LoginState;
import com.chatjob.platform.PlatformAdapter;
import com.chatjob.platform.PlatformInfo;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.OptionStore;
import com.chatjob.store.entity.PlatformConfigEntity;
import com.chatjob.store.entity.RunEntity;
import com.chatjob.watcher.ReplyStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final PatchrightDriver patchright;
    private final BrowserChannel browserChannel;
    private final BrowserSession session;
    private final SelfCheck selfCheck;
    private final PlatformAdapter adapter;
    private final RunStore runStore;
    private final JobStore jobStore;
    private final ReplyStore replyStore;
    private final OptionStore optionStore;
    private final ConfigStore configStore;
    private final RunService runService;
    private final Notifier notifier;
    private final JdbcTemplate jdbc;

    public SystemController(PatchrightDriver patchright, BrowserChannel browserChannel, BrowserSession session,
                            SelfCheck selfCheck, PlatformAdapter adapter, RunStore runStore, JobStore jobStore,
                            ReplyStore replyStore, OptionStore optionStore, ConfigStore configStore,
                            RunService runService, Notifier notifier, JdbcTemplate jdbc) {
        this.patchright = patchright;
        this.browserChannel = browserChannel;
        this.session = session;
        this.selfCheck = selfCheck;
        this.adapter = adapter;
        this.runStore = runStore;
        this.jobStore = jobStore;
        this.replyStore = replyStore;
        this.optionStore = optionStore;
        this.configStore = configStore;
        this.runService = runService;
        this.notifier = notifier;
        this.jdbc = jdbc;
    }

    /**
     * 概览页的指示灯都从这里取。刻意不主动启浏览器，避免刷一次页面就拉起一个 Chrome
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        // 平台信息随状态一起下发：控制台所有「平台配置 / 字典」请求都要带平台名，
        // 前端不该自己猜（猜错的表现是读写 BOSS 的配置，而人以为在看智联的）
        PlatformInfo.Info info = PlatformInfo.of(adapter.platform());
        out.put("platform", info.platform());
        out.put("platformLabel", info.label());
        out.put("platformFilters", info.filters());
        out.put("platformNote", info.note());
        PatchrightDriver.Status driver = patchright.inspect();
        out.put("driverAvailable", driver.available());
        out.put("driverDir", driver.driverDir());
        out.put("nodePath", driver.nodePath());
        out.put("driverMessage", driver.message());
        BrowserChannel.Choice channel = browserChannel.resolve();
        out.put("browserChannel", channel.channel() == null ? "builtin" : channel.channel());
        out.put("browserLabel", channel.label());
        out.put("browserNote", channel.note());
        out.put("browserResolved", channel.resolved());
        out.put("browserReady", session.isReady());
        out.put("running", runService.isRunning());
        // 两个数字口径不同，一个都不能省（ADR-0004）：deliveredToday 只数主动投递，
        // resumeOnRequestToday 只数值守里应对方索要发出的简历。合并会让被动应答吃掉主动配额
        out.put("deliveredToday", runStore.deliveredToday(adapter.platform()));
        out.put("evaluatedToday", runStore.evaluatedToday(adapter.platform()));
        out.put("resumeOnRequestToday", replyStore.resumeOnRequestToday(adapter.platform()));
        out.put("dictionaryTotal", optionStore.total());
        out.put("sseSubscribers", 0);
        out.put("serverTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return out;
    }

    @PostMapping("/self-check")
    public SelfCheck.Result selfCheck() {
        return selfCheck.run();
    }

    @PostMapping("/probe-login")
    public LoginState probeLogin() {
        return adapter.probeLogin();
    }

    /**
     * 打开登录页去扫码。
     *
     * <p>没有这个入口，"第一次登录"就没有发生的地点：Run 一律先 probeLogin，
     * 未登录即退出，人连浏览器窗口都看不到，于是永远卡在 LOGIN_LOST。
     */
    @PostMapping("/open-login")
    public Map<String, Object> openLogin() {
        adapter.openLogin();
        return Map.of("ok", true, "hint", "已打开登录页，请在浏览器窗口扫码；登录完成后点「探测登录」确认");
    }

    @PostMapping("/notify-test")
    public Map<String, Object> notifyTest() {
        notifier.notify("test", "chat-job 通知测试",
                "如果你能看到这条消息，说明企业微信机器人配置正确。");
        return Map.of("ok", true);
    }

    public record CleanupBody(Integer scannedDays, Integer logDays) {
    }

    @PostMapping("/cleanup")
    public Map<String, Object> cleanup(@RequestBody(required = false) CleanupBody body) {
        int scannedDays = body == null || body.scannedDays() == null ? 30 : body.scannedDays();
        int logDays = body == null || body.logDays() == null ? 14 : body.logDays();
        // 只清「未投递 且 超期」的扫描明细，否则去重记录被清掉会导致同一岗位被重复投递
        int jobs = jobStore.cleanScannedOlderThan(scannedDays);
        int replies = 0;
        try {
            String cutoff = LocalDateTime.now().minusDays(90)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            replies = jdbc.update("DELETE FROM reply_log WHERE created_at < ?", cutoff);
        } catch (Exception ignore) {
            // 清理失败不影响主流程
        }
        int logs = jdbc.update("DELETE FROM run_log WHERE created_at < ?",
                LocalDateTime.now().minusDays(logDays)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
        return Map.of("ok", true, "jobsRemoved", jobs, "replyLogsRemoved", replies, "runLogsRemoved", logs);
    }

    /**
     * 只看「当前跑哪个平台」。控制台首屏、配置页、任务页都要用；
     * 单开一个轻接口，避免为了拿一个字符串去拉整份状态（那份会碰 driver 探测）。
     *
     * <p>阶段 2c 之后前端改用 {@link #platforms()}（清单），这个接口保留给「只需要装配平台」
     * 的旧调用点；两者语义不同，不能相互替代。
     */
    @GetMapping("/platform")
    public PlatformInfo.Info platform() {
        return PlatformInfo.of(adapter.platform());
    }

    /**
     * 平台清单。控制台的「有哪些平台」唯一来源，替代原来「只有一个平台」的语义。
     *
     * <p>顺序即卡片顺序（{@link PlatformInfo#KNOWN}），前端不该重排。
     *
     * <p>{@code enabled} 读的是 {@code platform_config.enabled}，这一期只显示、不提供开关
     * （开关属于 spec 0004 阶段 3）。没配过的平台按建表默认值 1 显示，并且<b>不落库</b>。
     */
    @GetMapping("/platforms")
    public List<Map<String, Object>> platforms() {
        List<Map<String, Object>> out = new ArrayList<>(PlatformInfo.KNOWN.size());
        for (PlatformInfo.Info info : PlatformInfo.all()) {
            out.add(descriptor(info));
        }
        return out;
    }

    /**
     * 概览页一次取全：全局条 + 每平台一张卡。
     *
     * <p>为什么不让前端拼 {@code status} + N 次 {@code current} + N 次 {@code config}：
     * 那几个请求之间有先后，卡片会显示自相矛盾的组合（Run 已结束、计数还是上一轮的）。
     * 这里一次查询、一次快照。
     *
     * <p><b>刻意不做的事</b>：不探测登录态。登录探测要读浏览器 cookie，会为了刷一次页面
     * 把浏览器拉起来；而概览每 5 秒被轮询一次。登录态留在系统页由人点「探测登录」触发
     * （那个按钮在 2c-2 带上 platform 参数）。
     */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("global", globalBar());
        List<Map<String, Object>> cards = new ArrayList<>(PlatformInfo.KNOWN.size());
        for (PlatformInfo.Info info : PlatformInfo.all()) {
            cards.add(platformCard(info));
        }
        out.put("platforms", cards);
        return out;
    }

    /** 全局条：与平台无关的那些东西（driver、浏览器渠道、字典、服务器时间） */
    private Map<String, Object> globalBar() {
        PatchrightDriver.Status driver = patchright.inspect();
        BrowserChannel.Choice channel = browserChannel.resolve();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("driverAvailable", driver.available());
        out.put("driverMessage", driver.message());
        out.put("browserChannel", channel.channel() == null ? "builtin" : channel.channel());
        out.put("browserLabel", channel.label());
        // 字典是跨平台的一份（platform_option 按平台存，但入口只有一个），所以放在全局条
        out.put("dictionaryTotal", optionStore.total());
        out.put("serverTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return out;
    }

    /**
     * 一张平台卡。固定几项，顺序不变 —— 位置固定是快速扫视的前提，前端也不该重排。
     *
     * <p>{@code run} 在没有记录时是 null，会被 Jackson 的 {@code non_null} 直接省掉；
     * 前端据此显示「今天还没有跑过」，而不是显示 {@code —}（0 是真实发生的 0）。
     */
    private Map<String, Object> platformCard(PlatformInfo.Info info) {
        String platform = info.platform();
        PlatformConfigEntity cfg = configStore.peekPlatformConfig(platform);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform", platform);
        out.put("label", info.label());
        out.put("enabled", enabled(cfg));
        out.put("run", runCard(platform));
        // 两个数字口径不同，一个都不能省（ADR-0004）：delivered 只数主动投递，
        // resumeOnRequest 只数值守里应对方索要发出的简历。合并会让被动应答吃掉主动配额
        out.put("delivered", runStore.deliveredToday(platform));
        out.put("quota", cfg.getDailyDeliveryQuota());
        out.put("evaluated", runStore.evaluatedToday(platform));
        out.put("evalLimit", cfg.getDailyEvalLimit());
        out.put("resumeOnRequest", replyStore.resumeOnRequestToday(platform));
        out.put("browserReady", browserReady(platform));
        return out;
    }

    private Map<String, Object> descriptor(PlatformInfo.Info info) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform", info.platform());
        out.put("label", info.label());
        out.put("filters", info.filters());
        out.put("note", info.note());
        out.put("enabled", enabled(configStore.peekPlatformConfig(info.platform())));
        return out;
    }

    /** enabled 缺省按启用：与 {@code platform_config.enabled} 的建表默认值 1 一致 */
    private static boolean enabled(PlatformConfigEntity cfg) {
        return cfg.getEnabled() == null || cfg.getEnabled() != 0;
    }

    private Map<String, Object> runCard(String platform) {
        RunEntity run = runStore.latest(platform).orElse(null);
        if (run == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", run.getId());
        out.put("status", run.getStatus());
        out.put("mode", run.getMode());
        out.put("currentAction", run.getCurrentAction());
        out.put("lastActivityAt", run.getLastActivityAt());
        out.put("startedAt", run.getStartedAt());
        out.put("finishedAt", run.getFinishedAt());
        out.put("terminalReason", run.getTerminalReason());
        out.put("combinationIndex", run.getCombinationIndex());
        out.put("combinationTotal", runStore.combinationCount(run.getId()));
        return out;
    }

    /**
     * 该平台的浏览器是否已起。
     *
     * <p>阶段 2a 之前一个进程只有一份 {@link BrowserSession}，它属于装配的那个平台。
     * 所以未装配的平台返回 false 是<b>事实</b>而不是占位：它确实没有浏览器。
     * 2a 之后这里改为问该平台自己的 session。
     */
    private boolean browserReady(String platform) {
        return platform.equals(adapter.platform()) && session.isReady();
    }

    @GetMapping("/counters")
    public Map<String, Object> counters() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform", adapter.platform());
        out.put("day", RunStore.today());
        out.put("delivered", runStore.deliveredToday(adapter.platform()));
        out.put("evaluated", runStore.evaluatedToday(adapter.platform()));
        out.put("resumeOnRequest", replyStore.resumeOnRequestToday(adapter.platform()));
        return out;
    }
}
