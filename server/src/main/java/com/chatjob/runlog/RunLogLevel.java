package com.chatjob.runlog;

/**
 * 运行日志的级别。
 *
 * <p>{@link #name()} 直接写进 {@code run_log.level}，控制台按它选颜色
 * （{@code console/pages/task.vue} 里 WARN 与 ERROR 各有配色）。所以枚举常量名就是落库值，
 * 不要另加一层「显示名」再转换。
 *
 * <p>故意只有三档：文件日志里的 DEBUG 是给开发看的，它对应的行不该进运行日志表 ——
 * 那张表是给用户回看「这一轮到底做了什么」的。
 */
public enum RunLogLevel {
    INFO,
    WARN,
    ERROR
}
