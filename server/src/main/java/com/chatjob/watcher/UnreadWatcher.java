package com.chatjob.watcher;

import com.chatjob.platform.Digest;
import com.chatjob.platform.Models.ChatMessage;
import com.chatjob.platform.Models.UnreadThread;
import com.chatjob.platform.PlatformAdapter;
import com.chatjob.platform.ThreadTargetUnsafeException;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.ConversationEntity;
import com.chatjob.store.entity.ReplyLogEntity;
import com.chatjob.store.entity.UnreadTaskEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 未读值守。
 *
 * <p>嵌在扫描主循环里（每 5 张卡片或每 120s 一次），复用当前浏览器上下文，
 * 不另起进程 —— persistent context 的 profile 有独占锁，第二个进程根本起不来。
 *
 * <p>"10 分钟内感知"是<b>最坏延迟</b>的定义，不是硬实时。代价可接受：扫描期间
 * 消息晚几分钟被发现，总比让浏览器分身互踩要好。
 *
 * <p><b>两条路径，按开关分流（spec 0002）：</b>
 * <ul>
 *   <li>{@code ai_reply_enabled = 0} → {@link #sweepLegacy}，与改造前逐行一致。
 *       刻意保留成一份独立的代码而不是在主循环里插 if，是为了让「关闭即 no-op」
 *       这件事<b>在结构上成立</b>，而不是靠每次改动都小心翼翼不去碰它。</li>
 *   <li>{@code ai_reply_enabled = 1} → {@link #sweepWithTasks}，先落任务再点开会话，
 *       失败按退避重试，AI 分支在 P3 接入。</li>
 * </ul>
 */
@Slf4j
@Component
public class UnreadWatcher {

    /**
     * 单轮最多重试几条。重试优先于新未读，但不能反过来把新消息的发现拖死 ——
     * 单轮 5 条 × 单条最坏 8s（AI 超时）已经接近一次扫描的间隔。
     */
    private static final int MAX_RETRY_PER_SWEEP = 5;

    private final ReplyStore replyStore;
    private final UnreadTaskStore taskStore;
    private final ConfigStore configStore;

    public UnreadWatcher(ReplyStore replyStore, UnreadTaskStore taskStore, ConfigStore configStore) {
        this.replyStore = replyStore;
        this.taskStore = taskStore;
        this.configStore = configStore;
    }

    /**
     * @param retried    本轮重试的任务数（这些会话的红点在上一轮就已经消失）
     * @param failed     本轮失败、已排入下次重试的任务数
     * @param wouldReply 演练模式专属：本轮「本会回复」的条数。{@code replied} 在演练下恒为 0，
     *                   否则控制台会把「本会回复」显示成「已回复」，给操作者一个假的成功信号
     */
    public record SweepResult(int threads, int replied, int unmatched, int sensitive,
                              int retried, int failed, int wouldReply) {
    }

    /** 单个任务的收尾结论，只用于本轮计数与日志 */
    private enum Outcome {
        REPLIED, SKIPPED, SENSITIVE, FAILED, ABANDONED
    }

    /**
     * 扫一轮未读。任何异常都吞掉并返回，绝不让值守失败中断扫描 ——
     * 值守是附加能力，扫描才是主链。
     *
     * @param dryRun 演练模式：只走流程，不发消息、不写任何会改变真实状态的数据。
     *               语义见 {@link #sweepDryRun}。
     */
    public SweepResult sweep(PlatformAdapter adapter, String platform, boolean collectMetrics,
                             boolean dryRun) {
        // 演练支路必须排在开关判断之前：它与 ai_reply_enabled 无关 —— 开着 AI 回复时
        // 演练照样不能发。把它放进下面两个分支里，等于要求两处都记得判断。
        if (dryRun) {
            return sweepDryRun(adapter);
        }
        // 关闭态必须与改造前逐行一致（spec 2.1 第 1 条）。这里不「顺手」做任何优化。
        if (!configStore.aiReplyEnabled()) {
            return sweepLegacy(adapter, platform, collectMetrics);
        }
        return sweepWithTasks(adapter, platform, collectMetrics);
    }

    // ================================================================ 演练态：只走流程

    /**
     * 演练模式的一轮值守：<b>感知 + 判定 + 记日志，此外什么都不做</b>。
     *
     * <p>为什么单开一条支路，而不是在 {@link #sweepLegacy} / {@link #sweepWithTasks} 里插
     * {@code if (dryRun)}：演练要的不是「少做几步」，而是「一条不产生后果的路径」。
     * 插 if 的写法要求以后每个人在新增外发动作时都记得补一个判断 —— 而原本那个 bug
     * 正是漏补的结果（{@code RunService} 里 {@code deliver} 判了，这里两处 {@code sendText} 没判）。
     * 这个方法体里没有 {@code sendText}、没有 {@code replyStore.log}、没有
     * {@code markOwnReply}、没有任何 {@code taskStore} 写操作，所以「演练不发消息」
     * 在<b>结构上</b>成立，不依赖谁的记性；{@code UnreadWatcherTest} 再用
     * 「一条没发 + 三张表一行没写」的断言把这个结构钉住。
     *
     * <p>三处刻意为之的差异，都是「不读也不写真实状态」的推论：
     * <ul>
     *   <li><b>不查 {@code wasProcessed}</b>：它读的是 reply_log。演练读到自己的历史，
     *       会把「上次演练判过」当成「已经处理过」。</li>
     *   <li><b>不查冷却</b>：冷却时间戳同样是真实回复留下的痕迹。</li>
     *   <li><b>不落 {@code unread_task}</b>：任务表是真实路径的补偿机制（点开即清红点，
     *       靠任务重试兜回来）。演练建了任务却永不收尾，反而会毒化真实运行。</li>
     * </ul>
     *
     * <p><b>代价</b>：{@code readMessages} 必须点开会话，红点因此消失，演练过后真实运行时
     * 这条未读不会再出现在列表里。接受这个代价，是因为不验证 {@code openThread} 与消息区
     * 选择器就上线，代价更大 —— 这两个地方才是历史故障高发区。
     */
    private SweepResult sweepDryRun(PlatformAdapter adapter) {
        int threads = 0;
        int wouldReply = 0;
        int unmatched = 0;
        int sensitive = 0;
        int unreadUnreadable = 0;
        try {
            List<UnreadThread> list = adapter.listUnreadThreads();
            threads = list.size();
            ReplyRuleEngine engine = replyStore.engine();
            for (UnreadThread thread : list) {
                if (thread.unread() < 0) {
                    unreadUnreadable++;
                    continue;
                }
                if (thread.unread() == 0) {
                    continue;
                }
                try {
                    List<ChatMessage> messages = adapter.readMessages(thread);
                    if (messages.isEmpty()) {
                        log.warn("演练：会话「{}」消息区读不到任何消息", thread.title());
                        continue;
                    }
                    ChatMessage last = messages.get(messages.size() - 1);
                    // duplicate / throttled 一律传 false：见方法注释，它们读的是真实痕迹
                    ReplyRuleEngine.Decision decision =
                            engine.decide(last.text(), last.fromSelf(), false, false);
                    if (decision.shouldReply()) {
                        wouldReply++;
                        log.info("演练模式：本会回复「{}」→ 规则 {}「{}」：{}",
                                thread.title(), decision.ruleId(), decision.matchedKeyword(),
                                decision.replyText());
                    } else if (decision.action() == ReplyRuleEngine.Action.UNMATCHED) {
                        unmatched++;
                    } else if (decision.action() == ReplyRuleEngine.Action.SKIP_SENSITIVE) {
                        sensitive++;
                    }
                } catch (ThreadTargetUnsafeException e) {
                    // 演练要把这类问题暴露出来 —— 会话定位不安全正是「半夜验证」最该验到的部分
                    log.warn("演练：会话「{}」定位不安全（{}）：{}",
                            thread.title(), e.code(), e.getMessage());
                } catch (Exception e) {
                    log.warn("演练：处理会话「{}」失败: {}", thread.title(), e.getMessage());
                }
            }
            warnIfUnreadUnreadable(unreadUnreadable, threads);
        } catch (Exception e) {
            log.warn("演练值守扫描失败（不影响扫描主链）: {}", e.getMessage());
        } finally {
            closeThreadQuietly(adapter);
        }
        if (threads > 0) {
            log.info("演练值守扫描完成（不发消息）: 会话 {}，本会回复 {}，未命中 {}，敏感跳过 {}",
                    threads, wouldReply, unmatched, sensitive);
        }
        return new SweepResult(threads, 0, unmatched, sensitive, 0, 0, wouldReply);
    }

    // ================================================================ 关闭态：改造前路径

    private SweepResult sweepLegacy(PlatformAdapter adapter, String platform, boolean collectMetrics) {
        int threads = 0;
        int replied = 0;
        int unmatched = 0;
        int sensitive = 0;
        int unreadUnreadable = 0;
        try {
            List<UnreadThread> list = adapter.listUnreadThreads();
            threads = list.size();
            ReplyRuleEngine engine = replyStore.engine();
            for (UnreadThread thread : list) {
                // < 0 是「读不到」，不是「没有未读」。两者混同会让值守整体静默空转，
                // 所以这里必须单独计数并在本轮结束时告警，绝不能悄悄 continue。
                if (thread.unread() < 0) {
                    unreadUnreadable++;
                    continue;
                }
                if (thread.unread() == 0) {
                    continue;
                }
                try {
                    ConversationEntity conversation = replyStore.upsertConversation(
                            platform, thread.conversationId(), null);
                    List<ChatMessage> messages = adapter.readMessages(thread);
                    if (messages.isEmpty()) {
                        continue;
                    }
                    ChatMessage last = messages.get(messages.size() - 1);
                    boolean duplicate = replyStore.wasProcessed(conversation.getId(), last.id());
                    boolean throttled = replyStore.inCooldown(conversation);
                    ReplyRuleEngine.Decision decision = engine.decide(
                            last.text(), last.fromSelf(), duplicate, throttled);

                    if (decision.action() == ReplyRuleEngine.Action.SKIP_DUPLICATE) {
                        continue;
                    }
                    if (!last.fromSelf() && collectMetrics) {
                        replyStore.recordHrMessage(conversation);
                    }

                    boolean sent = false;
                    if (decision.shouldReply()) {
                        sent = adapter.sendText(thread, decision.replyText());
                        if (sent) {
                            replyStore.markOwnReply(conversation);
                            replied++;
                        } else {
                            log.warn("值守回复发送失败 | 会话={} | 规则={}", thread.title(), decision.ruleId());
                        }
                    } else if (decision.action() == ReplyRuleEngine.Action.UNMATCHED) {
                        unmatched++;
                    } else if (decision.action() == ReplyRuleEngine.Action.SKIP_SENSITIVE) {
                        sensitive++;
                    }

                    String action = decision.shouldReply() && !sent
                            ? ReplyRuleEngine.Action.UNMATCHED.name()
                            : decision.action().name();
                    logAction(conversation.getId(), last, action,
                            decision.ruleId(), decision.sensitive(), decision.replyText());
                } catch (Exception e) {
                    log.warn("处理会话 {} 失败: {}", thread.title(), e.getMessage());
                }
            }
            warnIfUnreadUnreadable(unreadUnreadable, threads);
            if (threads > 0) {
                log.info("值守扫描完成: 会话 {}，回复 {}，未命中 {}，敏感跳过 {}",
                        threads, replied, unmatched, sensitive);
            }
        } catch (Exception e) {
            log.warn("值守扫描失败（不影响扫描主链）: {}", e.getMessage());
        } finally {
            closeThreadQuietly(adapter);
        }
        return new SweepResult(threads, replied, unmatched, sensitive, 0, 0, 0);
    }

    // ================================================================ 打开态：任务与重试

    /**
     * 打开态编排（spec 3.5）：
     *
     * <pre>
     * 1) 先重试到期任务 —— 这些会话的红点在上一轮就被清掉了，列表里再也看不见
     * 2) 再扫新未读 —— 落任务 → 点开会话（红点在这里才消失）→ 判定 → 回复 → 收尾
     * </pre>
     *
     * 顺序不能反过来：先扫新未读的话，一个失败的会话要等到下一轮才可能被重试，
     * 而下一轮可能因为配额/超时根本不会到来。
     */
    private SweepResult sweepWithTasks(PlatformAdapter adapter, String platform, boolean collectMetrics) {
        int threads = 0;
        int replied = 0;
        int unmatched = 0;
        int sensitive = 0;
        int unreadUnreadable = 0;
        int retried = 0;
        int failed = 0;
        try {
            ReplyRuleEngine engine = replyStore.engine();

            for (UnreadTaskEntity task : taskStore.dueForRetry(MAX_RETRY_PER_SWEEP)) {
                retried++;
                UnreadThread thread = UnreadThread.retry(
                        task.getPlatformConversationId(), task.getLastMessageDigest());
                Outcome outcome = process(adapter, engine, thread, task, collectMetrics);
                switch (outcome) {
                    case REPLIED -> replied++;
                    case SENSITIVE -> sensitive++;
                    case FAILED -> failed++;
                    default -> {
                        // SKIPPED / ABANDONED 只影响任务状态，不进本轮计数
                    }
                }
            }

            List<UnreadThread> list = adapter.listUnreadThreads();
            threads = list.size();
            for (UnreadThread thread : list) {
                if (thread.unread() < 0) {
                    unreadUnreadable++;
                    continue;
                }
                if (thread.unread() == 0) {
                    continue;
                }
                try {
                    ConversationEntity conversation = replyStore.upsertConversation(
                            platform, thread.conversationId(), null);
                    // 先落任务，再点开会话：点开后红点就没了，顺序错了这条消息就丢（spec 2.1 第 2 条）
                    UnreadTaskEntity task = taskStore.openOrTouch(
                            platform, thread.conversationId(), conversation.getId(),
                            thread.unread(), Digest.of(thread.lastMessage()));
                    if (taskStore.waitingForRetry(task)) {
                        // 还在退避窗口里：它已经排好下次重试时间，现在处理等于把退避废掉
                        continue;
                    }
                    Outcome outcome = process(adapter, engine, thread, task, collectMetrics);
                    switch (outcome) {
                        case REPLIED -> replied++;
                        case SENSITIVE -> sensitive++;
                        case FAILED -> failed++;
                        default -> {
                        }
                    }
                } catch (Exception e) {
                    log.warn("处理会话 {} 失败: {}", thread.title(), e.getMessage());
                }
            }

            warnIfUnreadUnreadable(unreadUnreadable, threads);
            if (threads > 0 || retried > 0) {
                log.info("值守扫描完成（AI 模式）: 会话 {}，重试 {}（失败 {}），回复 {}，未命中 {}，敏感跳过 {}",
                        threads, retried, failed, replied, unmatched, sensitive);
            }
        } catch (Exception e) {
            log.warn("值守扫描失败（不影响扫描主链）: {}", e.getMessage());
        } finally {
            closeThreadQuietly(adapter);
        }
        return new SweepResult(threads, replied, unmatched, sensitive, retried, failed, 0);
    }

    /**
     * 处理一条未读（新扫到的或重试的）。
     *
     * <p>判定顺序与关闭态<b>只有一处结构性差异</b>：冷却检查前移到 AI 之前（spec 3.6 第 3 步）。
     * 关闭态里冷却只在「命中规则」之后才生效，未命中的消息照走 UNMATCHED；
     * 打开态如果照旧，会是「先花钱问 AI，再发现 3 分钟内已经回过」。
     */
    private Outcome process(PlatformAdapter adapter, ReplyRuleEngine engine, UnreadThread thread,
                            UnreadTaskEntity task, boolean collectMetrics) {
        Long conversationId = task.getConversationId();
        ConversationEntity conversation = replyStore.findById(conversationId);
        taskStore.claim(task);

        List<ChatMessage> messages;
        try {
            messages = adapter.readMessages(thread);
        } catch (ThreadTargetUnsafeException e) {
            // 同名多条 / 摘要已变：重试一万次也不会变好，直接放弃（spec 3.4）
            log.warn("会话定位不安全，放弃这条未读 | 会话={} | {} | {}",
                    thread.title(), e.code(), e.getMessage());
            taskStore.giveUp(task, e.code(), e.getMessage());
            return Outcome.ABANDONED;
        } catch (Exception e) {
            return fail(task, UnreadTaskStore.Action.READ_FAILED, null, e.getMessage());
        }
        if (messages.isEmpty()) {
            // 「读不到」和「没有消息」在这里无法区分，交重试：3 次都空就 GAVE_UP，控制台可见
            return fail(task, UnreadTaskStore.Action.READ_FAILED, null, "消息区读不到任何消息");
        }

        ChatMessage last = messages.get(messages.size() - 1);
        if (last.fromSelf()) {
            // 最后一条是我方：可能是上一轮发送其实成功了（送达判据没确认住），
            // 这里靠消息归属自然收敛，不会重复发。
            taskStore.markDone(task, last.id(), ReplyRuleEngine.Action.SKIP_SELF.name(), null, null);
            return Outcome.SKIPPED;
        }
        if (replyStore.wasProcessed(conversationId, last.id())) {
            taskStore.markDone(task, last.id(), ReplyRuleEngine.Action.SKIP_DUPLICATE.name(), null, null);
            return Outcome.SKIPPED;
        }
        if (conversation != null && replyStore.inCooldown(conversation)) {
            logAction(conversationId, last, ReplyRuleEngine.Action.SKIP_THROTTLED.name(), null, null, null);
            taskStore.markDone(task, last.id(), ReplyRuleEngine.Action.SKIP_THROTTLED.name(),
                    null, "同会话 " + ReplyStore.REPLY_COOLDOWN_MINUTES + " 分钟冷却期内");
            return Outcome.SKIPPED;
        }
        if (collectMetrics && conversation != null) {
            replyStore.recordHrMessage(conversation);
        }

        // 自己发的 / 重复 / 冷却 已在上面判完，这里只让引擎回答「敏不敏感、命中哪条规则」
        ReplyRuleEngine.Decision decision = engine.decide(last.text(), false, false, false);

        if (decision.action() == ReplyRuleEngine.Action.SKIP_SENSITIVE) {
            logAction(conversationId, last, decision.action().name(), null, decision.sensitive(), null);
            taskStore.markDone(task, last.id(), decision.action().name(), null, decision.reason());
            return Outcome.SENSITIVE;
        }

        if (decision.shouldReply()) {
            boolean sent = adapter.sendText(thread, decision.replyText());
            if (sent) {
                if (conversation != null) {
                    replyStore.markOwnReply(conversation);
                }
                logAction(conversationId, last, ReplyRuleEngine.Action.REPLY.name(),
                        decision.ruleId(), null, decision.replyText());
                taskStore.markDone(task, last.id(), UnreadTaskStore.Action.REPLIED, null, null);
                return Outcome.REPLIED;
            }
            return fail(task, UnreadTaskStore.Action.SEND_FAILED, null,
                    "发送未确认送达（规则 " + decision.ruleId() + "）");
        }

        // 未命中：这正是本 spec 要打开的口子。
        //
        // P1 阶段 AI 还没接入。这里刻意<b>按 AI_FAILED 落库</b>而不是记一条 UNMATCHED 完事：
        // 「点开即清红点」意味着没有重试就等于丢消息，必须让失败进入重试链。
        // 顺带地，这也让 P1 的验证（失败 → 1 分钟重试 → 3 次后 GAVE_UP）在 P3 之前
        // 就能用真实链路跑通，而不必等到填错 baseUrl 那天。
        // P3 接入 AiReplier 后，这个分支替换为「生成 → 四检 → 发送 / AI_SKIPPED」。
        return fail(task, UnreadTaskStore.Action.AI_FAILED, null,
                "AI 回复尚未接入（P1 占位），未命中会话暂不回复");
    }

    private Outcome fail(UnreadTaskEntity task, String action, String reasonCode, String error) {
        taskStore.markFailed(task, action, reasonCode, error);
        return Outcome.FAILED;
    }

    private void closeThreadQuietly(PlatformAdapter adapter) {
        try {
            adapter.closeThread();
        } catch (Exception ignore) {
            // 关不掉会话页不影响结论
        }
    }

    private static void warnIfUnreadUnreadable(int unreadUnreadable, int threads) {
        if (unreadUnreadable > 0) {
            log.warn("值守未读数为「读不到」: {} / {} 个会话。这不是「没有新消息」，"
                            + "而是选择器或页面结构对不上，这些会话本轮被跳过 —— "
                            + "先核对 BossSelectors.CHAT_LIST_UNREAD 与 CHAT_LIST_ITEM_BODY",
                    unreadUnreadable, threads);
        }
    }

    private void logAction(Long conversationId, ChatMessage message, String action,
                           Long ruleId, String sensitive, String replyText) {
        ReplyLogEntity entity = new ReplyLogEntity();
        entity.setConversationId(conversationId);
        entity.setPlatformMessageId(message.id());
        entity.setIncomingText(message.text() == null ? "" : message.text());
        entity.setAction(action);
        entity.setMatchedRuleId(ruleId);
        entity.setMatchedSensitive(sensitive);
        entity.setReplyText(replyText);
        replyStore.log(entity);
    }
}
