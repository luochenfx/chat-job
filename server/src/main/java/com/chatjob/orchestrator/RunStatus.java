package com.chatjob.orchestrator;

/** Run 的六个终止态 + 五个非终止态（CREATED / WAITING_DUTY / RUNNING / PAUSED / STANDBY）。 */
public enum RunStatus {
    CREATED,
    /**
     * 已经点过启动，但当前不在值守时段：挂在队列里等窗口打开自动开跑。
     *
     * <p>它不是终止态 —— 这是「等」和「收工」的区别。窗口外启动若直接结束，
     * 用户第二天必须再点一次启动，且新 Run 会从第一个组合重扫，前一晚的意图就白费了。
     */
    WAITING_DUTY,
    RUNNING,
    PAUSED,
    /**
     * 值守待命：扫描已中止（投递配额、评估上限或组合队列耗尽），值守仍在继续。
     *
     * <p>配额与评估上限约束的是「今天不再找岗位」，不是「今天不再回消息」——
     * 投递后的当天下午恰恰是 HR 回消息最密集的时段，扫描一停就把 Reply 一起停，
     * 等于把投递的产出丢掉一半。所以命中三者的共同出口是这里，而不是终止态。
     *
     * <p>它和 {@link #RUNNING} 的区别只在「扫不扫」：页面操作、巡检节奏、暂停与停止的语义完全一致。
     */
    STANDBY,
    /** 组合队列耗尽，或评估数达日上限 */
    COMPLETED,
    /** 投递数达日配额。V12 之后不再产生，枚举保留是为了让历史数据在界面上仍可读 */
    QUOTA_REACHED,
    /** 安全验证 / 沟通次数上限 */
    CIRCUIT_OPEN,
    /** 登录态失效 */
    LOGIN_LOST,
    CANCELLED,
    FAILED;

    public boolean isTerminal() {
        return this != CREATED && this != WAITING_DUTY && this != RUNNING
                && this != PAUSED && this != STANDBY;
    }

    public boolean isActive() {
        return this == WAITING_DUTY || this == RUNNING || this == PAUSED || this == STANDBY;
    }
}
