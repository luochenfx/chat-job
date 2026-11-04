package com.chatjob.orchestrator;

import com.chatjob.watcher.UnreadWatcher;

/**
 * 一条 Run 的运行状态与出口，<b>扫描段与待命段共用的那一半</b>。
 *
 * <p>两条循环问的是同一批问题：能不能继续、要不要等、现在在干什么、把什么推给界面。
 * 这些问题与「扫不扫」无关，所以单独成接口；{@link ScanHost} 在它之上再加两条只有卡片边界
 * 才问的（见那里的注释）。
 *
 * <p><b>为什么拆开而不是一个接口七个方法</b>：待命段每轮自己判时段（{@code StandbyGate.PARK}），
 * 从不碰 {@code dutyEnded}；而 {@code endIfOutOfDuty} 会把它置真。「谁判到点」只能有一处，
 * 让待命段在类型上就够不到那两个方法，比在注释里写一句「别用」可靠。
 *
 * <p>实现是每轮 Run 一个（{@code RunService} 的内部类），因为它绑定 {@code RunEntity}。
 */
public interface RunControl {

    /** 用户点了停止。{@code scrollUntilEnd} 也把它当停止信号 */
    boolean stopRequested();

    /** 暂停时在这里等。返回即表示可以继续（或已被停止，调用方需自己再判一次） */
    void awaitResume();

    /** 界面上的「现在在干什么」。两条循环的每个动作前后都要写 */
    void act(String action);

    /** 推一条 SSE 事件给控制台。只推状态与计数，日志不走这条通道（ADR-0008） */
    void publish(String type, Object data);

    /** 一轮值守的结果推给界面。两条循环共用同一份载荷口径，所以放在宿主上 */
    void publishUnread(UnreadWatcher.SweepResult sweep);

    /**
     * 发一条企业微信告警。
     *
     * <p>放在宿主上是为了让「唯一的通知出口」仍然是唯一的：那边要拼平台展示名
     * （{@code PlatformInfo.label()}），而待命段手里只有 slug。让它自己注入 {@code Notifier}
     * 再算一遍，同一个出口就变成两个。
     */
    void notify(String kind, String title, String body);
}
