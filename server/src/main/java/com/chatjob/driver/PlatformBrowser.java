package com.chatjob.driver;

/**
 * 一个平台的浏览器全套：调度线程 + 会话。
 *
 * <p>两者必须成对出现，不能分开取 —— {@link BrowserSession} 的所有页面操作都被要求调度到
 * <b>它自己的</b>那条线程上。如果让调用方分别注入，就可能把 A 平台的会话提交到 B 平台的线程，
 * 症状是随机 {@code Object doesn't exist}，而且只在两个平台同时跑时复现。
 *
 * @param platform 平台标识（{@code boss} / {@code zhaopin}）
 * @param executor 该平台专属的单线程调度器
 * @param session  该平台专属的浏览器会话
 */
public record PlatformBrowser(String platform, PlaywrightExecutor executor, BrowserSession session) {
}
