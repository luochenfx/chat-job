package com.chatjob.web;

import com.chatjob.driver.BrowserChannel;
import com.chatjob.driver.BrowserSessions;
import com.chatjob.driver.PatchrightDriver;
import com.chatjob.driver.SelfCheck;
import com.chatjob.notify.Notifier;
import com.chatjob.orchestrator.JobStore;
import com.chatjob.orchestrator.RunService;
import com.chatjob.orchestrator.RunServices;
import com.chatjob.orchestrator.RunStore;
import com.chatjob.platform.Models.LoginState;
import com.chatjob.platform.PlatformAdapter;
import com.chatjob.platform.PlatformAdapters;
import com.chatjob.platform.PlatformInfo;
import com.chatjob.platform.PlatformSwitches;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统页与概览页的读接口。
 *
 * <p><b>2b 之后的寻址方式</b>：单平台接口（status / self-check / probe-login / open-login /
 * platform / counters）都接受可选的 {@code platform} 查询参数，缺省落
 * {@code chat-job.active-platform}。缺省值不是「兼容残留」，而是给「只想操作那一个平台」的
 * 调用点用的；概览与清单（overview / platforms）不接参数，它们本来就是并排的。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final PatchrightDriver patchright;
    private final BrowserChannel browserChannel;
    private final BrowserSessions sessions;
    private final SelfCheck selfCheck;
    private final PlatformAdapters adapters;
    private final RunStore runStore;
    private final JobStore jobStore;
    private final ReplyStore replyStore;
    private final OptionStore optionStore;
    private final ConfigStore configStore;
    private final RunServices runServices;
    private final Notifier notifier;
    private final JdbcTemplate jdbc;
    private final PlatformSwitches switches;

    public SystemController(PatchrightDriver patchright, BrowserChannel browserChannel, BrowserSessions sessions,
                            SelfCheck selfCheck, PlatformAdapters adapters, RunStore runStore, JobStore jobStore,
                            ReplyStore replyStore, OptionStore optionStore, ConfigStore configStore,
                            RunServices runServices, Notifier notifier, JdbcTemplate jdbc,
                            PlatformSwitches switches) {
        this.patchright = patchright;
        this.browserChannel = browserChannel;
        this.sessions = sessions;
        this.selfCheck = selfCheck;
        this.adapters = adapters;
        this.runStore = runStore;
        this.jobStore = jobStore;
        this.replyStore = replyStore;
        this.optionStore = optionStore;
        this.configStore = configStore;
        this.runServices = runServices;
        this.notifier = notifier;
        this.jdbc = jdbc;
        this.switches = switches;
    }

    /**
     * 概览页的指示灯都从这里取。刻意不主动启浏览器，避免刷一次页面就拉起一个 Chrome
     */
    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam(required = false) String platform) {
        String key = adapters.platformKey(platform);
        Map<String, Object> out = new LinkedHashMap<>();
        // 平台信息随状态一起下发：控制台所有「平台配置 / 字典」请求都要带平台名，
        // 前端不该自己猜（猜错的表现是读写 BOSS 的配置，而人以为在看智联的）
        PlatformInfo.Info info = PlatformInfo.of(key);
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
        // 问的是「这个平台」的浏览器：查的是已创建的那份，不会因为看一眼就造出浏览器
        out.put("browserReady", browserReady(key));
        out.put("running", runServices.of(key).isRunning());
        // 两个数字口径不同，一个都不能省（ADR-0004）：deliveredToday 只数主动投递，
        // resumeOnRequestToday 只数值守里应对方索要发出的简历。合并会让被动应答消耗主动投递配额
        out.put("deliveredToday", runStore.deliveredToday(key));
        out.put("evaluatedToday", runStore.evaluatedToday(key));
        out.put("resumeOnRequestToday", replyStore.resumeOnRequestToday(key));
        out.put("dictionaryTotal", optionStore.total());
        out.put("sseSubscribers", 0);
        out.put("serverTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return out;
    }

    /**
     * 自检 / 探测 / 打开登录页都要先过开关：这三个动作的共同点是「会去开那个平台的浏览器」，
     * 而停用的平台不该再被拉起来 —— 否则开关关掉的只是任务，浏览器照样起。
     */
    @PostMapping("/self-check")
    public SelfCheck.Result selfCheck(@RequestParam(required = false) String platform) {
        String key = adapters.platformKey(platform);
        switches.require(key);
        return selfCheck.run(key);
    }

    @PostMapping("/probe-login")
    public LoginState probeLogin(@RequestParam(required = false) String platform) {
        String key = adapters.platformKey(platform);
        switches.require(key);
        return adapter(key).probeLogin();
    }

    /**
     * 打开登录页去扫码。
     *
     * <p>没有这个入口，"第一次登录"就没有发生的地点：Run 一律先 probeLogin，
     * 未登录即退出，人连浏览器窗口都看不到，于是永远卡在 LOGIN_LOST。
     */
    @PostMapping("/open-login")
    public Map<String, Object> openLogin(@RequestParam(required = false) String platform) {
        switches.require(adapters.platformKey(platform));
        PlatformAdapter adapter = adapter(platform);
        adapter.openLogin();
        return Map.of("ok", true, "platform", adapter.platform(),
                "hint", "已打开登录页，请在浏览器窗口里扫码；登录完成后点「检测登录状态」确认");
    }

    /**
     * 发一条测试消息。
     *
     * <p>回的是<b>这次到底发出去了没有</b>，不是「请求处理完了」。原来无论有没有配置
     * 都返回 {@code ok:true}，用户点了按钮、看到成功、以为通了，实际一条都没发出去 ——
     * 告警失效最典型的发现方式就是「出事那天才发现从来没配上」。
     *
     * <p>没配置不算错误：它不是客户端写错，也不是服务端坏了，是一种合法但没有效果的状态，
     * 所以仍是 200，只是把原因说清楚。
     */
    @PostMapping("/notify-test")
    public Map<String, Object> notifyTest() {
        notifier.notify("test", "chat-job 通知测试",
                "如果你能看到这条消息，说明企业微信机器人配置正确。");
        Notifier.Delivery delivery = notifier.lastDelivery();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("delivered", delivery != null && delivery.sent());
        out.put("message", delivery == null ? "没有走到发送这一步" : delivery.detail());
        return out;
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
     * 只看「某个平台」的元信息。控制台首屏、配置页、任务页都要用；
     * 单开一个轻接口，避免为了拿一个字符串去拉整份状态（那份会碰 driver 探测）。
     *
     * <p>2c 起前端改用 {@link #platforms()}（清单）+ 自身的平台选择，这个接口保留给
     * 「只需要一个平台」的调用点；两者语义不同，不能相互替代。
     */
    @GetMapping("/platform")
    public PlatformInfo.Info platform(@RequestParam(required = false) String platform) {
        return PlatformInfo.of(adapters.platformKey(platform));
    }

    /**
     * 平台清单。控制台的「有哪些平台」唯一来源。
     *
     * <p>2b 之前这份清单是常量；现在它等于<b>实际装配了适配器的平台</b>（顺序仍按
     * {@link PlatformInfo#KNOWN} 声明，未声明的沉到末尾）。这样「界面上有的平台」
     * 与「点了能跑起来的平台」第一次是同一件事。
     *
     * <p>{@code enabled} 读的是 {@code platform_config.enabled}，这一期只显示、不提供开关
     * （开关属于 spec 0004 阶段 3）。没配过的平台按建表默认值 1 显示，并且<b>不落库</b>。
     */
    @GetMapping("/platforms")
    public List<Map<String, Object>> platforms() {
        List<Map<String, Object>> out = new ArrayList<>(adapters.platforms().size());
        for (String platform : adapters.platforms()) {
            out.add(descriptor(PlatformInfo.of(platform)));
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
     * （那个按钮带 platform 参数）。
     */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("global", globalBar());
        List<Map<String, Object>> cards = new ArrayList<>(adapters.platforms().size());
        for (String platform : adapters.platforms()) {
            cards.add(platformCard(PlatformInfo.of(platform)));
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
        // 两个开关随卡片一起下发：概览要标「仅值守 / 仅扫描」，若不在同一份快照里，
        // 徽标会和状态栏读到不同时刻的配置（这张卡每 5 秒被轮询一次）
        out.put("scanEnabled", on(cfg.getScanEnabled()));
        out.put("watchEnabled", on(cfg.getWatchEnabled()));
        out.put("run", runCard(platform));
        // 两个数字口径不同，一个都不能省（ADR-0004）：delivered 只数主动投递，
        // resumeOnRequest 只数值守里应对方索要发出的简历。合并会让被动应答消耗主动投递配额
        out.put("delivered", runStore.deliveredToday(platform));
        out.put("quota", cfg.getDailyDeliveryQuota());
        out.put("evaluated", runStore.evaluatedToday(platform));
        out.put("evalLimit", cfg.getDailyEvalLimit());
        out.put("resumeOnRequest", replyStore.resumeOnRequestToday(platform));
        out.put("browserReady", browserReady(platform));
        out.put("running", runServices.of(platform).isRunning());
        return out;
    }

    private Map<String, Object> descriptor(PlatformInfo.Info info) {
        PlatformConfigEntity cfg = configStore.peekPlatformConfig(info.platform());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform", info.platform());
        out.put("label", info.label());
        out.put("filters", info.filters());
        out.put("note", info.note());
        out.put("enabled", enabled(cfg));
        // 各页共用这份清单，概览徽标与任务页的启动提示都从这里取，不再单独请求配置
        out.put("scanEnabled", on(cfg.getScanEnabled()));
        out.put("watchEnabled", on(cfg.getWatchEnabled()));
        return out;
    }

    /** enabled 缺省按启用：与 {@code platform_config.enabled} 的建表默认值 1 一致 */
    private static boolean enabled(PlatformConfigEntity cfg) {
        return on(cfg.getEnabled());
    }

    /** platform_config 里的开关列都是 0/1，缺省按开：与各列的建表默认值 1 一致 */
    private static boolean on(Integer flag) {
        return flag == null || flag != 0;
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
     * <p>每个平台有自己的一份 {@link com.chatjob.driver.BrowserSession}，这里问的就是「那个平台」。
     * 查的是<b>已创建</b>的那份，不会因为看一眼状态就把浏览器造出来（{@code existing} 不创建）。
     */
    private boolean browserReady(String platform) {
        return sessions.existing(platform)
                .map(browser -> browser.session().isReady())
                .orElse(false);
    }

    @GetMapping("/counters")
    public Map<String, Object> counters(@RequestParam(required = false) String platform) {
        String key = adapters.platformKey(platform);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform", key);
        out.put("day", RunStore.today());
        out.put("delivered", runStore.deliveredToday(key));
        out.put("evaluated", runStore.evaluatedToday(key));
        out.put("resumeOnRequest", replyStore.resumeOnRequestToday(key));
        return out;
    }

    private PlatformAdapter adapter(String platform) {
        return adapters.of(adapters.platformKey(platform));
    }
}
