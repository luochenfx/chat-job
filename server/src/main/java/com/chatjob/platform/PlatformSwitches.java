package com.chatjob.platform;

import com.chatjob.store.ConfigStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * 平台开关：某个平台「现在要不要跑」（spec 0004 阶段 3，ADR-0003 第 5 条的处置顺序）。
 *
 * <p><b>为什么单独一个类</b>：开关的值在 {@code platform_config.enabled} 里，但
 * 「停用意味着什么」散落在四处 —— 不许开浏览器、不许新建 Run、不许探测登录、不许被定时唤醒。
 * 让每个调用点自己去读配置，就会各自写出不同的缺省值（有人按启用、有人按停用），
 * 于是「关掉一个平台」变成只关掉一半。判定与文案收在这里，调用点只调
 * {@link #isEnabled} / {@link #require}。
 *
 * <p><b>为什么不用缓存</b>：读的是本地 SQLite 的一行，比维护一份会过期的缓存便宜。
 * 控制台概览 5 秒轮询一次，缓存只会带来「刚启用却显示停用」这类更难解释的问题。
 *
 * <p><b>缺省是启用</b>：与 {@code platform_config.enabled} 的建表默认值 1 一致。
 * 反过来（缺省停用）会让刚接进来的第三个平台静默不跑 —— 那种故障没有任何提示。
 */
@Component
public class PlatformSwitches {

    private final Predicate<String> enabledSource;

    @Autowired
    public PlatformSwitches(ConfigStore config) {
        this(config::platformEnabled);
    }

    /**
     * 不经数据库的构造：driver 层的隔离测试要在没有库的情况下装配整条流水线，
     * 那时「所有平台都启用」正是它要测的前提。
     */
    public PlatformSwitches(Predicate<String> enabledSource) {
        this.enabledSource = enabledSource;
    }

    public boolean isEnabled(String platform) {
        return enabledSource.test(key(platform));
    }

    /**
     * 停用即拒绝，抛出 {@link IllegalArgumentException}（全局异常处理映射成 400）。
     *
     * <p>为什么是拒绝而不是「静默跳过」：控制台点了「启动」却毫无反应，
     * 人只会认为按钮坏了。宁可把「它已停用」写在弹回来的那句话里。
     */
    public void require(String platform) {
        if (!isEnabled(platform)) {
            throw new IllegalArgumentException(disabledMessage(platform));
        }
    }

    public static String disabledMessage(String platform) {
        return "平台「" + PlatformInfo.of(platform).label() + "」已停用：不会为它开浏览器，"
                + "也不会启动它的任务。要跑它请先在「配置 → 平台配置」里启用";
    }

    /**
     * 扫描与值守不能同时关闭（spec 0005 / ADR-0005 第 4 条）。
     *
     * <p>为什么拒绝而不是存下来：平台启用、扫描、值守全关之后，它与「停用」的差别只剩
     * 浏览器没有被主动关掉（停用会关浏览器），保留这个状态只会让「停用」有两种写法。
     * 报错里点名平台并指向平台开关，是因为这时要做的是「整体停用」，不是再调这两个开关。
     *
     * <p>与 {@link #disabledMessage} 放在同一个类里：两者都在做「开关 + 一句能照做的报错文案」，
     * 分开会让同一类文案散到两个包。
     */
    public static void requireSomethingOn(String platform, boolean scanOn, boolean watchOn) {
        if (!scanOn && !watchOn) {
            throw new IllegalArgumentException("「" + PlatformInfo.of(platform).label()
                    + "」的扫描与值守不能同时关闭：那样它启动后什么都不做。"
                    + "要整体停用它，请用平台开关");
        }
    }

    /** 与 {@link PlatformAdapters#platformKey(String)} 同一套归一化：大小写与空格不该产生两个平台 */
    public static String key(String platform) {
        return platform == null ? "" : platform.trim().toLowerCase(Locale.ROOT);
    }
}
