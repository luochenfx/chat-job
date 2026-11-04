package com.chatjob.watcher;

import com.chatjob.platform.Models.UnreadThread;
import com.chatjob.platform.PlatformAdapter;
import com.chatjob.runlog.RunLog;

import java.util.List;

/**
 * 一轮未读扫描的运行上下文：待办清单、计数器、收尾。
 *
 * <p><b>存在理由</b>：{@link UnreadWatcher} 有三条路径（演练、关闭态、打开态），它们的循环体
 * 各不相同，但它们对「一轮」的处理逐字相同 —— 读列表、跳过读不到未读数的会话、按结论计数、
 * 收尾告警、关会话。这些原来各写一份，代价不是难看而是<b>漏改</b>：某个计数声明之后忘了自增，
 * 日志与控制台就会长期显示 0，而这种错没有任何测试会变红。计数改成只在本类自增后，
 * 这类遗漏只剩一个地方可犯。
 *
 * <p><b>本类不做的事</b>：不判定、不发送、不写任何 store。它只回答「还有哪些会话要看」
 * 与「这一轮发生过什么」。因此它出现在三条路径里都不会破坏演练的「结构上不发消息」——
 * 这里没有任何外发动作可被误用，唯一的平台调用是 {@link #close()}。
 */
final class UnreadSweep {

    private final PlatformAdapter adapter;
    private final RunLog runLog;

    private int threads;
    private int replied;
    private int unmatched;
    private int sensitive;
    private int retried;
    private int failed;
    private int wouldReply;

    /**
     * 未读数读不到的会话数。<b>不是</b>「没有未读」：两者混同会让值守整体静默空转，
     * 所以单独计数并在轮末告警，绝不悄悄跳过。
     */
    private int unreadUnreadable;

    UnreadSweep(PlatformAdapter adapter, RunLog runLog) {
        this.adapter = adapter;
        this.runLog = runLog;
    }

    /** 本轮要看的会话。会话总数在这里记下，三条路径都不必再自己数一次。 */
    List<UnreadThread> list() {
        List<UnreadThread> list = adapter.listUnreadThreads();
        threads = list.size();
        return list;
    }

    /**
     * 这一条是否跳过。
     *
     * <p>{@code < 0} 是「读不到未读数」而不是「没有未读」—— 计一次数，轮末统一告警。
     * 返回 true 时调用方 {@code continue} 即可，判定规则只有这一处。
     */
    boolean skip(UnreadThread thread) {
        if (thread.unread() < 0) {
            unreadUnreadable++;
            return true;
        }
        return thread.unread() == 0;
    }

    void addReplied() {
        replied++;
    }

    void addUnmatched() {
        unmatched++;
    }

    void addSensitive() {
        sensitive++;
    }

    void addRetried() {
        retried++;
    }

    void addFailed() {
        failed++;
    }

    void addWouldReply() {
        wouldReply++;
    }

    /** 轮末告警。只有真的出现过「读不到」才出声，正常轮次不产生噪音。 */
    void warnUnreadable() {
        if (unreadUnreadable > 0) {
            runLog.warn("值守未读数为「读不到」: " + unreadUnreadable + " / " + threads
                    + " 个会话。这不是「没有新消息」，"
                    + "而是选择器或页面结构对不上，这些会话本轮被跳过 —— "
                    + "先核对 BossSelectors.CHAT_LIST_UNREAD 与 CHAT_LIST_ITEM_BODY");
        }
    }

    UnreadWatcher.SweepResult result() {
        return new UnreadWatcher.SweepResult(threads, replied, unmatched, sensitive, retried, failed, wouldReply);
    }

    /** 关掉会话页。关不掉不影响结论，这是三条路径共同的收尾动作。 */
    void close() {
        try {
            adapter.closeThread();
        } catch (Exception ignore) {
            // 关不掉会话页不影响结论
        }
    }
}
