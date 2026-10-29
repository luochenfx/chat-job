package com.chatjob.orchestrator;

/** Run 的七个终止态 + 四个进行态。 */
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
    /** 组合队列耗尽，或评估数达日上限 */
    COMPLETED,
    /** 投递数达日配额 */
    QUOTA_REACHED,
    /** 安全验证 / 沟通次数上限 */
    CIRCUIT_OPEN,
    /** 登录态失效 */
    LOGIN_LOST,
    CANCELLED,
    FAILED;

    public boolean isTerminal() {
        return this != CREATED && this != WAITING_DUTY && this != RUNNING && this != PAUSED;
    }

    public boolean isActive() {
        return this == WAITING_DUTY || this == RUNNING || this == PAUSED;
    }
}
