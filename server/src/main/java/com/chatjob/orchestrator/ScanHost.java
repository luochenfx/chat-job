package com.chatjob.orchestrator;

import com.chatjob.store.entity.PlatformConfigEntity;
import com.chatjob.watcher.UnreadWatcher;

/**
 * 扫描段回问宿主的那几个问题：能不能继续、要不要等、到点没有、现在在干什么、把什么推给界面。
 *
 * <p>扫描段不持有 {@code RunService}：它要的是<b>一条 Run 的运行状态</b>，不是整个流水线。
 * 拿不到整个对象，也就改不动 {@code worker}、{@code pausedFrom} 这类生命周期字段 ——
 * 这条约束靠类型表达，比靠注释可靠。
 *
 * <p><b>本接口也服务待命段</b>。{@code stopRequested} 与 {@code awaitResume} 是两条循环
 * 共用的东西，只给扫描段走接口、待命段继续裸读字段，就成了同一概念两种写法：以后加一个运行标志，
 * 改一处只影响一条循环，另一条静默漏掉。
 *
 * <p>实现是每轮 Run 一个（{@code RunService} 的内部类），因为它绑定 {@code RunEntity}。
 */
public interface ScanHost {

    /** 用户点了停止。{@code scrollUntilEnd} 也把它当停止信号 */
    boolean stopRequested();

    /**
     * 停止 <b>或</b> 时段到点。两者的共同后果是「这一轮不再继续」，
     * 但落库的状态不同（{@code CANCELLED} 对 {@code WAITING_DUTY}），所以另一个方法仍然单列。
     */
    boolean interrupted();

    /** 暂停时在这里等。返回即表示可以继续（或已被停止，调用方需自己再判一次） */
    void awaitResume();

    /**
     * 卡片边界的值守检查：越过结束时刻就置「已到点」并返回 {@code true}。
     *
     * <p>名字里带 {@code end} 是因为它会改状态，不是纯查询 —— 原来是 {@code checkDutyWindow}，
     * 读起来像只读。
     *
     * @param cfg 调用方手里那份配置快照，传进来是为了不在每张卡上多读一次库
     */
    boolean endIfOutOfDuty(PlatformConfigEntity cfg);

    /** 界面上的「现在在干什么」。扫描段的每个动作前后都要写 */
    void act(String action);

    /** 推一条 SSE 事件给控制台。只推状态与计数，日志不走这条通道（ADR-0008） */
    void publish(String type, Object data);

    /** 一轮值守的结果推给界面。两条循环共用同一份载荷口径，所以放在宿主上 */
    void publishUnread(UnreadWatcher.SweepResult sweep);
}
