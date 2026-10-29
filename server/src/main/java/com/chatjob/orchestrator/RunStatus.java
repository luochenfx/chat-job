package com.chatjob.orchestrator;

/** Run 的七个终止态 + 三个进行态。 */
public enum RunStatus {
    CREATED,
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
        return this != CREATED && this != RUNNING && this != PAUSED;
    }

    public boolean isActive() {
        return this == RUNNING || this == PAUSED;
    }
}
