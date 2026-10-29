package com.chatjob.platform;

/**
 * 会话无法被安全定位：打开它就有可能把回复发给错误的人。
 *
 * <p>为什么用异常而不是返回 false：调用方必须对「没找到」和「找到了但不敢点」做不同处理 ——
 * 前者可以重试（页面可能还没加载完），后者重试一万次也不会变好，必须直接放弃（spec 3.4）。
 * 返回值把这两件事压成同一个布尔时，唯一的实现方式就是「三次重试后放弃」，
 * 而每一次重试都离错发更近一步。
 */
public class ThreadTargetUnsafeException extends RuntimeException {

    /** 列表里存在多条同名会话，无法确定是哪一条 */
    public static final String AMBIGUOUS_TITLE = "AMBIGUOUS_TITLE";
    /** 标题对上了，但最后一句话已经变了：会话被新消息顶掉或换了人 */
    public static final String DIGEST_MISMATCH = "DIGEST_MISMATCH";

    private final String code;

    public ThreadTargetUnsafeException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 落 {@code unread_task.last_reason_code} 的枚举值 */
    public String code() {
        return code;
    }
}
