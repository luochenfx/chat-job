package com.chatjob.driver;

import com.chatjob.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 把「当前装配平台」的那份浏览器会话暴露成普通 bean，供只关心单平台的调用点注入。
 *
 * <p>为什么需要这一层：2a 只把 driver 层改成每平台一份，<b>编排层与适配器还不该动</b> ——
 * 那属于 2b（RunService 按平台实例化 + PlatformAdapter 改成 Map 注入）。所以在 2a 期间，
 * {@code RunService}、{@code SelfCheck}、{@code BossAdapter}、{@code ZhaopinAdapter}
 * 继续写「注入一个 BrowserSession」，由这里保证注入到的确实是<b>它自己平台</b>的那一份。
 *
 * <p>这个 bean 的存在期限是刻意的：2b 之后适配器会自己按平台从
 * {@link BrowserSessions} 取，届时删掉这两个方法即可，调用点无需再改。
 */
@Configuration
public class BrowserWiring {

    /** 当前平台（{@code chat-job.active-platform}）的调度线程 */
    @Bean
    @Primary
    public PlaywrightExecutor activePlaywrightExecutor(BrowserSessions sessions, AppProperties props) {
        return sessions.of(props.activePlatform()).executor();
    }

    /** 当前平台的浏览器会话 */
    @Bean
    @Primary
    public BrowserSession activeBrowserSession(BrowserSessions sessions, AppProperties props) {
        return sessions.of(props.activePlatform()).session();
    }
}
