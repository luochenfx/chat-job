package com.chatjob.orchestrator;

import com.chatjob.store.entity.PlatformConfigEntity;

/**
 * 扫描段额外要问的两件事：被打断没有、这张卡是不是越过了值守时段。
 *
 * <p>其余六个（停止 / 暂停 / 动作 / 推送 / 巡检载荷 / 告警）在 {@link RunControl} 里，
 * 待命段用的是那一半。本接口只加扫描段独有的两条 —— 它们都会碰 {@code dutyEnded}，
 * 而待命段每轮自己用 {@link StandbyGate} 判时段，不该有第二条路径能把它置真。
 *
 * <p>扫描段不持有 {@code RunService}：它要的是<b>一条 Run 的运行状态</b>，不是整个流水线。
 * 拿不到整个对象，也就改不动 {@code worker}、{@code pausedFrom} 这类生命周期字段 ——
 * 这条约束靠类型表达，比靠注释可靠。
 *
 * <p>实现是每轮 Run 一个（{@code RunService} 的内部类），因为它绑定 {@code RunEntity}。
 */
public interface ScanHost extends RunControl {

    /**
     * 停止 <b>或</b> 时段到点。两者的共同后果是「这一轮不再继续」，
     * 但落库的状态不同（{@code CANCELLED} 对 {@code WAITING_DUTY}），所以另一个方法仍然单列。
     */
    boolean interrupted();

    /**
     * 卡片边界的值守检查：越过结束时刻就置「已到点」并返回 {@code true}。
     *
     * <p>名字里带 {@code end} 是因为它会改状态，不是纯查询 —— 原来是 {@code checkDutyWindow}，
     * 读起来像只读。
     *
     * @param cfg 调用方手里那份配置快照，传进来是为了不在每张卡上多读一次库
     */
    boolean endIfOutOfDuty(PlatformConfigEntity cfg);
}
