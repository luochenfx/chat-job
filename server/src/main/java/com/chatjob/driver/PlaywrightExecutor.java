package com.chatjob.driver;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Playwright 单线程调度器，<b>每个平台一条</b>。
 *
 * <p>Playwright Java 不是线程安全的：Playwright 对象以及由它创建的所有对象，必须在创建它的
 * 那个线程上调用。多线程同时操作同一个 BrowserContext 会随机抛
 * {@code Object doesn't exist: request@/response@}，导航还会被打断成 {@code net::ERR_ABORTED}。
 * 所以所有页面操作都收敛到这一条线程上串行执行。
 *
 * <p>为什么是「每平台一条」而不是全进程一条：串行化只需要落在<b>同一个浏览器上下文</b>内部，
 * 把两个互不相干的平台也焊在同一根线程上，等于让 Boss 一次投递的 30 秒等待把智联的巡检
 * 一起挂住 —— 那正是 ADR-0003 要消灭的「时长相加」。线程名带平台后缀
 * （{@code playwright-boss} / {@code playwright-zhaopin}），出现问题时从 jstack 一眼能看出
 * 卡在哪个平台。
 *
 * <p>监控类调用（登录态心跳）走 {@link #tryRun}：线程忙就丢掉本轮，不排队 ——
 * 心跳排到投递前面会把主流程挤死。
 */
@Slf4j
public class PlaywrightExecutor {

    private final String platform;
    /** 线程名同时是「当前是否在这条线程上」的唯一判据，所以必须按实例区分，不能用静态常量 */
    private final String threadName;

    private final ExecutorService executor;

    /** 排队中 + 执行中的任务数，用于"忙就跳过"判断 */
    private final AtomicInteger depth = new AtomicInteger();

    public PlaywrightExecutor(String platform) {
        this.platform = platform;
        this.threadName = "playwright-" + platform;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    public String platform() {
        return platform;
    }

    public String threadName() {
        return threadName;
    }

    public boolean onPlaywrightThread() {
        return threadName.equals(Thread.currentThread().getName());
    }

    public boolean isBusy() {
        return depth.get() > 0;
    }

    /**
     * 在专用线程上执行并等待结果。已经在专用线程上时就地执行 ——
     * 否则自己等自己会死锁。
     */
    public <T> T call(Callable<T> task) {
        if (onPlaywrightThread()) {
            try {
                return task.call();
            } catch (Exception e) {
                throw asRuntime(e);
            }
        }
        depth.incrementAndGet();
        try {
            return executor.submit(() -> {
                try {
                    return task.call();
                } finally {
                    depth.decrementAndGet();
                }
            }).get();
        } catch (ExecutionException e) {
            throw asRuntime(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Playwright 任务被中断", e);
        }
    }

    public void run(Runnable task) {
        call(() -> {
            task.run();
            return null;
        });
    }

    /**
     * 监控类任务专用：忙就跳过，不排队。
     *
     * @return 是否真的提交了
     */
    public boolean tryRun(Runnable task) {
        if (depth.get() > 0) {
            return false;
        }
        depth.incrementAndGet();
        executor.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.debug("[{}] Playwright 后台任务异常: {}", platform, e.getMessage());
            } finally {
                depth.decrementAndGet();
            }
        });
        return true;
    }

    /** 关掉这条线程池。只由 {@link BrowserSessions#closeAll()} 在应用退出时调用 */
    public void shutdown() {
        executor.shutdownNow();
    }

    private static RuntimeException asRuntime(Throwable t) {
        if (t instanceof RuntimeException runtime) {
            return runtime;
        }
        if (t instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(t);
    }
}
