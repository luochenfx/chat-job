package com.chatjob.driver;

import com.chatjob.driver.human.Humanize;

/**
 * 一个平台的浏览器全套：调度线程 + 会话 + 人类化交互层。
 *
 * <p>三者必须成对出现，不能分开取 —— {@link BrowserSession} 的所有页面操作都被要求调度到
 * <b>它自己的</b>那条线程上。如果让调用方分别注入，就可能把 A 平台的会话提交到 B 平台的线程，
 * 症状是随机 {@code Object doesn't exist}，而且只在两个平台同时跑时复现。
 *
 * <p>{@link Humanize} 也按平台各一份，理由有两条：它的操作必须发在同一平台的页面上；
 * 它的计数（交互次数、降级次数）要能按平台分开看——两个平台共用一份计数时，
 * 「降级率 5%」这个验收口径就变成两个平台混在一起的平均数，出问题时看不出是谁的。
 *
 * @param platform 平台标识（{@code boss} / {@code zhaopin}）
 * @param executor 该平台专属的单线程调度器
 * @param session  该平台专属的浏览器会话
 * @param humanize 该平台专属的人类化交互实例
 */
public record PlatformBrowser(String platform, PlaywrightExecutor executor, BrowserSession session,
                              Humanize humanize) {
}
