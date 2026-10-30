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
import com.chatjob.store.OptionStore;
import com.chatjob.watcher.ReplyStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
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
    private final RunService runService;
    private final Notifier notifier;
    private final JdbcTemplate jdbc;

    public SystemController(PatchrightDriver patchright, BrowserChannel browserChannel, BrowserSession session,
                            SelfCheck selfCheck, PlatformAdapter adapter, RunStore runStore, JobStore jobStore,
                            ReplyStore replyStore, OptionStore optionStore, RunService runService,
                            Notifier notifier, JdbcTemplate jdbc) {
        this.patchright = patchright;
        this.browserChannel = browserChannel;
        this.session = session;
        this.selfCheck = selfCheck;
        this.adapter = adapter;
        this.runStore = runStore;
        this.jobStore = jobStore;
        this.replyStore = replyStore;
        this.optionStore = optionStore;
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
     */
    @GetMapping("/platform")
    public PlatformInfo.Info platform() {
        return PlatformInfo.of(adapter.platform());
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
