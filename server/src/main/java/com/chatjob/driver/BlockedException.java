package com.chatjob.driver;

/**
 * 平台侧出现"必须停下来"的信号：安全验证页、今日沟通次数上限、登录失效。
 *
 * <p>抛出它意味着 {@code fail-closed}：调用方应立即终止 Run、保留断点、发通知，
 * 绝不能"重试穿越"。绕过一次验证码的代价远大于当天少投几十个岗。
 */
public class BlockedException extends RuntimeException {

    public enum Kind {
        /** 滑块 / 安全验证页 */
        SECURITY_CHECK,
        /** 平台提示今日沟通次数已达上限 */
        QUOTA_EXHAUSTED,
        /** 登录态失效 */
        LOGIN_LOST,
        /** driver 自检失败或 driver 不可用 */
        DRIVER_UNAVAILABLE
    }

    private final Kind kind;

    public BlockedException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
