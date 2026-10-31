package com.chatjob.orchestrator;

import com.chatjob.platform.PlatformAdapters;
import com.chatjob.platform.PlatformSwitches;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台 → {@link RunService} 的注册表（ADR-0003 第 1、5 条：每个平台一条独立流水线，不做并发上限）。
 *
 * <p><b>为什么是 registry 而不是给每个平台各写一个 bean 定义</b>：{@code RunService} 那六个字段
 * （{@code worker / stopRequested / pauseRequested / dutyEnded / pausedFrom / currentRunId}）
 * 描述的是一条流水线的内部状态，不需要改写；需要的是「每个平台一份这个对象」。
 * prototype + 一个按平台建实例的工厂，正好只改装配、不改内部 —— 接入第三个平台时
 * 这里也是 0 行改动。
 *
 * <p><b>为什么定时与启动收尾住在这里</b>：{@code RunService} 已经是 prototype，
 * 给它挂 {@code @Scheduled} / {@code @EventListener} 会随实例创建次数重复注册 ——
 * 三个平台就是三份定时器，而且随控制台点开几个平台而增长。全进程只需要一份调度，
 * 它遍历已装配平台、逐个委托。
 *
 * <p><b>惰性创建</b>：只在第一次 {@link #of(String)} 时建实例。服务启动本身不创建任何
 * RunService，也不启浏览器 —— 「刷一次控制台就拉起两个 Chrome」不会因为 2b 而变成事实。
 */
@Slf4j
@Component
public class RunServices {

    private final Map<String, RunService> byPlatform = new ConcurrentHashMap<>();
    private final ObjectProvider<RunService> provider;
    private final PlatformAdapters adapters;
    private final PlatformSwitches switches;

    public RunServices(ObjectProvider<RunService> provider, PlatformAdapters adapters,
                       PlatformSwitches switches) {
        this.provider = provider;
        this.adapters = adapters;
        this.switches = switches;
    }

    /**
     * 取该平台的 Run 流水线，没有就建一个（并绑定平台）。
     *
     * @param platform 平台标识；空值按 {@code chat-job.active-platform} 处理
     * @throws IllegalArgumentException 平台未装配适配器
     */
    public RunService of(String platform) {
        String key = adapters.platformKey(platform);
        return byPlatform.computeIfAbsent(key, p -> {
            RunService service = provider.getObject().bind(p);
            log.debug("[{}] 已建立独立 Run 流水线", p);
            return service;
        });
    }

    /** 已建的流水线（不建新的）：控制台查询「某平台在跑吗」不能顺手把实例造出来 */
    public boolean anyRunning() {
        return byPlatform.values().stream().anyMatch(RunService::isRunning);
    }

    /** 某平台是否建有流水线（不建新的） */
    public Optional<RunService> existing(String platform) {
        return Optional.ofNullable(byPlatform.get(adapters.platformKey(platform)));
    }

    /** 全进程只有这一份：遍历已装配平台，把各自挂起的 Run 唤醒 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void wakeWaitingRuns() {
        for (String platform : adapters.platforms()) {
            // 停用的平台连唤醒都不做：唤醒的终点是开浏览器（ADR-0003 第 5 条）
            if (!switches.isEnabled(platform)) {
                continue;
            }
            try {
                of(platform).wakeIfWaiting();
            } catch (Exception e) {
                // 一个平台唤醒失败不能影响另一个：并行架构的全部意义就是这个
                log.warn("[{}] 唤醒挂起的 Run 失败: {}", platform, e.getMessage());
            }
        }
    }

    /**
     * 进程重启后的收尾：每个平台各自把库里的孤儿 Run 标失败。
     *
     * <p>顺序无所谓，各平台之间没有共享状态；失败也只影响本平台那一份。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterRestart() {
        for (String platform : adapters.platforms()) {
            try {
                of(platform).recoverOrphans();
            } catch (Exception e) {
                log.warn("[{}] 重启收尾失败: {}", platform, e.getMessage());
            }
        }
        log.info("[runs] 重启收尾完成，已装配平台 {}", adapters.platforms());
    }
}
