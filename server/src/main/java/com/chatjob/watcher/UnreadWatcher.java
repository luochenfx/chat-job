package com.chatjob.watcher;

import com.chatjob.platform.Digest;
import com.chatjob.platform.Models.ChatMessage;
import com.chatjob.platform.Models.ResumeResult;
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
    private final AiReplier aiReplier;

    public UnreadWatcher(ReplyStore replyStore, UnreadTaskStore taskStore, ConfigStore configStore,
                         AiReplier aiReplier) {
        this.replyStore = replyStore;
        this.taskStore = taskStore;
        this.configStore = configStore;
        this.aiReplier = aiReplier;
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
     * 单轮 AI 调用预算。
     *
     * <p>存在理由：列表里有 40 个会话时，未命中的会话可能连着来好几条，全调一遍既慢又是
     * 一笔真金白银。超预算的任务不是失败，只是「下一轮再说」——用失败去表示，会让它
     * 在 3 轮之后被 GAVE_UP，而它一次错都没犯。
     */
    private static final class AiBudget {
        private final int max;
        private int used;

        AiBudget(int max) {
            this.max = Math.max(1, max);
        }

        boolean tryUse() {
            if (used >= max) {
                return false;
            }
            used++;
            return true;
        }

        int max() {
            return max;
        }
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
     *       靠任务重试补回）。演练建了任务却永不收尾，反而会污染真实运行的数据。</li>
     * </ul>
     *
     * <p><b>演练会真的调 AI</b>（花 token、计入真实成本），这是有意的：P3 之后它成了
     * 唯一能安全看到「AI 到底会回什么」的地方 —— 真实运行看到的是已经发出去的消息。
     * 调用次数受 {@code ai_reply_max_per_sweep} 约束，日志里逐条给出结论
     * （会回复 / AI 主动跳过 / 被四检拦下 / AI 失败降级模板）。
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
            ReplyGuard.GuardOptions guard = new ReplyGuard.GuardOptions(
                    configStore.toneTerms(), configStore.toneCheckEnabled(), engine.sensitiveWords());
            AiBudget budget = new AiBudget(configStore.aiReplyMaxPerSweep());
            for (UnreadThread thread : list) {
                if (thread.unread() < 0) {
                    unreadUnreadable++;
                    continue;
                }
                if (thread.unread() == 0) {
                    continue;
                }
                try {
                    log.info("演练：进入会话 | 会话={} | 未读={} | 摘要=「{}」",
                            thread.title(), thread.unread(), clipLog(thread.lastMessage()));
                    List<ChatMessage> messages = adapter.readMessages(thread);
                    if (messages.isEmpty()) {
                        log.warn("演练：会话「{}」消息区读不到任何消息", thread.title());
                        continue;
                    }
                    ChatMessage last = messages.get(messages.size() - 1);
                    // duplicate / throttled 一律传 false：见方法注释，它们读的是真实痕迹
                    ReplyRuleEngine.Decision decision =
                            engine.decide(last.text(), last.fromSelf(), false, false);
                    log.info("演练判定 | 会话={} | 动作={} | 规则={} | 命中词={} | 理由={}",
                            thread.title(), decision.action(), ruleNameOf(engine, decision.ruleId()),
                            nvl(decision.matchedKeyword()), decision.reason());
                    if (decision.action() == ReplyRuleEngine.Action.SKIP_SENSITIVE) {
                        sensitive++;
                        continue;
                    }
                    // 演练也要把「本来会发简历」说出来，否则演练结果和真实结果对不上：
                    // 真实路径会点按钮发卡片，演练路径却只报「未命中」。
                    if (decision.action() == ReplyRuleEngine.Action.SEND_RESUME) {
                        wouldReply++;
                        log.info("演练模式：本会发简历（不发文字）| 会话={} | 理由={}",
                                thread.title(), decision.reason());
                        continue;
                    }
                    if (decision.action() == ReplyRuleEngine.Action.UNMATCHED) {
                        unmatched++;
                    }
                    // 演练也过一遍 AI：不然「AI 到底会回什么」只能等真实运行，而真实运行会发出去。
                    // 这是 P3 之后演练的主要价值 —— 它是唯一能安全看到 AI 输出的地方。
                    String ruleText = decision.shouldReply() ? decision.replyText() : null;
                    if (!budget.tryUse()) {
                        log.info("演练：AI 调用已达本轮上限 {} 次，其余会话不预演", budget.max());
                        break;
                    }
                    AiReplier.Reply preview = aiReplier.reply(new AiReplier.Input(
                            thread.title(), configStore.aiIntroduce(), configStore.aiReplyStyle(),
                            messages, ruleNameOf(engine, decision.ruleId()), ruleText,
                            engine.sensitiveWords()));
                    if (preview.isFailure()) {
                        if (ruleText != null && !ruleText.isBlank()) {
                            wouldReply++;
                            log.info("演练模式：本会回复「{}」→ AI 失败，降级用模板「{}」",
                                    thread.title(), ruleText);
                        } else {
                            log.info("演练模式：本不会回复「{}」→ AI 失败且无模板可降级", thread.title());
                        }
                        continue;
                    }
                    if (preview.skip()) {
                        log.info("演练模式：本不会回复「{}」→ AI 主动跳过（{}）：{}",
                                thread.title(), preview.skipReason(), preview.note());
                        continue;
                    }
                    ReplyGuard.Verdict verdict = ReplyGuard.check(preview.text(), last.text(), guard);
                    if (verdict.blocked()) {
                        log.info("演练模式：本不会回复「{}」→ AI 草稿被出站校验拦下（{}）：{}",
                                thread.title(), verdict.rejection(), verdict.detail());
                        continue;
                    }
                    wouldReply++;
                    log.info("演练模式：本会回复「{}」→ AI「{}」", thread.title(), verdict.text());
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

                    log.info("进入回复流程 | 会话={} | 未读={} | 摘要=「{}」",
                            thread.title(), thread.unread(), clipLog(thread.lastMessage()));
                    log.info("判定 | 会话={} | 动作={} | 规则={} | 命中词={} | 理由={}",
                            thread.title(), decision.action(), ruleNameOf(engine, decision.ruleId()),
                            nvl(decision.matchedKeyword()), decision.reason());

                    if (decision.action() == ReplyRuleEngine.Action.SKIP_DUPLICATE) {
                        continue;
                    }
                    if (!last.fromSelf() && collectMetrics) {
                        replyStore.recordHrMessage(conversation);
                    }

                    boolean sent = false;
                    String reason = decision.reason();
                    if (decision.action() == ReplyRuleEngine.Action.SEND_RESUME) {
                        // 关闭 AI 也要能发简历：这是「动作」不是「回复风格」，
                        // 没道理跟着 AI 开关一起消失。
                        ResumeResult resume = adapter.sendResume(thread);
                        if (resume.status() == ResumeResult.Status.SENT) {
                            replyStore.markOwnReply(conversation);
                            replied++;
                            logAction(conversation.getId(), last, ReplyLogEntity.ACTION_REPLY_RESUME,
                                    decision.ruleId(), null, null, ReplyLogEntity.SOURCE_RESUME, null, reason);
                            log.info("已发出简历（未补文字）| 会话={} | 理由={}", thread.title(), reason);
                            continue;
                        }
                        if (resume.status() == ResumeResult.Status.UNAVAILABLE
                                && decision.replyText() != null && !decision.replyText().isBlank()) {
                            // 按钮灰了才说话；能发的时候一句话都不加
                            reason = reason + "；按钮不可用（" + resume.detail() + "），改发兜底文案";
                            sent = adapter.sendText(thread, decision.replyText());
                            if (sent) {
                                replyStore.markOwnReply(conversation);
                                replied++;
                                log.info("已发送（简历不可用降级）| 会话={} | 内容=「{}」",
                                        thread.title(), decision.replyText());
                            }
                        } else {
                            log.warn("发简历失败 | 会话={} | {} | {}", thread.title(),
                                    resume.status(), resume.detail());
                        }
                        logAction(conversation.getId(), last, sent
                                        ? ReplyRuleEngine.Action.REPLY.name()
                                        : UnreadTaskStore.Action.SEND_FAILED,
                                decision.ruleId(), null, sent ? decision.replyText() : null,
                                sent ? ReplyLogEntity.SOURCE_TEMPLATE : ReplyLogEntity.SOURCE_RESUME,
                                "RESUME_UNAVAILABLE", reason);
                        continue;
                    }

                    if (decision.shouldReply()) {
                        sent = adapter.sendText(thread, decision.replyText());
                        if (sent) {
                            replyStore.markOwnReply(conversation);
                            replied++;
                            log.info("已发送 | 会话={} | 来源=模板 | 规则={} | 内容=「{}」",
                                    thread.title(), ruleNameOf(engine, decision.ruleId()), decision.replyText());
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
                    logAction(conversation.getId(), last, action, decision.ruleId(),
                            decision.sensitive(), decision.replyText(),
                            ReplyLogEntity.SOURCE_TEMPLATE, null, reason);
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
            // 出站四检的入参在一轮内不变，构造一次即可
            ReplyGuard.GuardOptions guard = new ReplyGuard.GuardOptions(
                    configStore.toneTerms(), configStore.toneCheckEnabled(), engine.sensitiveWords());
            AiBudget budget = new AiBudget(configStore.aiReplyMaxPerSweep());

            for (UnreadTaskEntity task : taskStore.dueForRetry(MAX_RETRY_PER_SWEEP)) {
                retried++;
                UnreadThread thread = UnreadThread.retry(
                        task.getPlatformConversationId(), task.getLastMessageDigest());
                Outcome outcome = process(adapter, engine, thread, task, collectMetrics, guard, budget);
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
                    Outcome outcome = process(adapter, engine, thread, task, collectMetrics, guard, budget);
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
     *
     * <p><b>命中规则在打开态不再直接发模板</b>，而是把模板当「参考素材」交给 AI（spec 3.6 第 5 步）。
     * 不这么做的话，命中「简历」「薪资」的那些会话回的全是同一句 —— 那正是本 spec 要解决的问题。
     * 模板只在 AI 技术性失败时使用：它是人工审核过的，比「不回」安全。
     */
    private Outcome process(PlatformAdapter adapter, ReplyRuleEngine engine, UnreadThread thread,
                            UnreadTaskEntity task, boolean collectMetrics,
                            ReplyGuard.GuardOptions guard, AiBudget budget) {
        Long conversationId = task.getConversationId();
        ConversationEntity conversation = replyStore.findById(conversationId);
        taskStore.claim(task);

        // 每个会话一条入口日志。改造前这里一条都不打，成功路径全程静默 ——
        // 真发了消息的人在日志里找不到任何痕迹，只能靠「轮末那行汇总里 replied=1」反推。
        log.info("进入回复流程 | 会话={} | 未读={} | 摘要=「{}」",
                thread.title(), thread.unread(), clipLog(thread.lastMessage()));

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
            taskStore.markDone(task, last.id(), ReplyRuleEngine.Action.SKIP_SELF.name(), null,
                    "最后一条是我方消息「" + clipLog(last.text()) + "」，等对方先回");
            log.info("跳过（最后一条是我方消息）| 会话={}", thread.title());
            return Outcome.SKIPPED;
        }
        if (replyStore.wasProcessed(conversationId, last.id())) {
            taskStore.markDone(task, last.id(), ReplyRuleEngine.Action.SKIP_DUPLICATE.name(), null,
                    "消息 " + last.id() + " 上一轮已处理过");
            log.info("跳过（这条消息已处理过）| 会话={} | mid={}", thread.title(), last.id());
            return Outcome.SKIPPED;
        }
        if (conversation != null && replyStore.inCooldown(conversation)) {
            logAction(conversationId, last, ReplyRuleEngine.Action.SKIP_THROTTLED.name(), null, null, null,
                    ReplyLogEntity.SOURCE_TEMPLATE, null,
                    "同会话 " + ReplyStore.REPLY_COOLDOWN_MINUTES + " 分钟冷却期内");
            taskStore.markDone(task, last.id(), ReplyRuleEngine.Action.SKIP_THROTTLED.name(),
                    null, "同会话 " + ReplyStore.REPLY_COOLDOWN_MINUTES + " 分钟冷却期内");
            log.info("跳过（{} 分钟冷却期内）| 会话={}", ReplyStore.REPLY_COOLDOWN_MINUTES, thread.title());
            return Outcome.SKIPPED;
        }
        if (collectMetrics && conversation != null) {
            replyStore.recordHrMessage(conversation);
        }

        // 自己发的 / 重复 / 冷却 已在上面判完，这里只让引擎回答「敏不敏感、命中哪条规则」
        ReplyRuleEngine.Decision decision = engine.decide(last.text(), false, false, false);

        // 「为什么这么回」必须在日志里能直接读到，不能只落库：出事的时候人是先翻日志的。
        log.info("判定 | 会话={} | 动作={} | 规则={} | 命中词={} | 理由={}",
                thread.title(), decision.action(), ruleNameOf(engine, decision.ruleId()),
                nvl(decision.matchedKeyword()), decision.reason());

        if (decision.action() == ReplyRuleEngine.Action.SKIP_SENSITIVE) {
            // 敏感话题连 AI 都不问：问了也没用，还白白把薪资这类内容送进模型
            logAction(conversationId, last, decision.action().name(), null, decision.sensitive(), null,
                    ReplyLogEntity.SOURCE_TEMPLATE, null, decision.reason());
            taskStore.markDone(task, last.id(), decision.action().name(), null, decision.reason());
            return Outcome.SENSITIVE;
        }

        // 命中「发简历」类规则：动作是发，不是回。这里不调 AI ——
        // 一旦让模型决定「要不要发简历」，它就有能力把简历发给说「简历收到了吗」的人。
        if (decision.action() == ReplyRuleEngine.Action.SEND_RESUME) {
            return sendResumeOutcome(adapter, thread, task, last, conversation, conversationId, decision);
        }

        // 命中与未命中都走 AI，差别只在「带不带参考素材」
        String ruleText = decision.shouldReply() ? decision.replyText() : null;
        String ruleName = ruleNameOf(engine, decision.ruleId());

        if (!budget.tryUse()) {
            taskStore.release(task, "本轮 AI 调用已达上限 " + budget.max() + " 次，留到下一轮");
            return Outcome.SKIPPED;
        }

        AiReplier.Reply draft = aiReplier.reply(new AiReplier.Input(
                thread.title(), configStore.aiIntroduce(), configStore.aiReplyStyle(),
                messages, ruleName, ruleText, engine.sensitiveWords()));

        if (draft.isFailure()) {
            return degradeToTemplate(adapter, thread, task, last, conversation, conversationId,
                    decision, ruleText, draft.error());
        }

        if (draft.skip()) {
            // AI 主动不回：这是安全阀，不是故障。记枚举原因，别让它混进失败统计
            logAction(conversationId, last, UnreadTaskStore.Action.AI_SKIPPED, decision.ruleId(),
                    null, null, ReplyLogEntity.SOURCE_AI, draft.skipReason(), draft.note());
            taskStore.markDone(task, last.id(), UnreadTaskStore.Action.AI_SKIPPED,
                    draft.skipReason(), draft.note());
            return Outcome.SKIPPED;
        }

        ReplyGuard.Verdict verdict = ReplyGuard.check(draft.text(), last.text(), guard);
        if (verdict.rejection() == ReplyGuard.Rejection.EMPTY) {
            return fail(task, UnreadTaskStore.Action.AI_FAILED, null, "AI 给出空回复");
        }
        if (verdict.blocked()) {
            String action = blockedAction(verdict.rejection());
            logAction(conversationId, last, action, decision.ruleId(), null, null,
                    ReplyLogEntity.SOURCE_AI, null, verdict.detail());
            // 不重试：同一 prompt 再问一次大概率给同样的东西，四检又是确定性规则，
            // 重试只会多花一次调用、把同样的结果再拦一次
            taskStore.markDone(task, last.id(), action, null, verdict.detail());
            log.warn("AI 回复被出站校验拦下（整条不发）| 会话={} | {} | {}",
                    thread.title(), verdict.rejection(), verdict.detail());
            return Outcome.SKIPPED;
        }

        boolean sent = adapter.sendText(thread, verdict.text());
        if (sent) {
            if (conversation != null) {
                replyStore.markOwnReply(conversation);
            }
            String reason = aiReason(engine, decision, draft);
            logAction(conversationId, last, ReplyRuleEngine.Action.REPLY.name(),
                    decision.ruleId(), null, verdict.text(), ReplyLogEntity.SOURCE_AI, null, reason);
            taskStore.markDone(task, last.id(), UnreadTaskStore.Action.REPLIED, null, reason);
            log.info("已发送 | 会话={} | 来源=AI | 内容=「{}」 | 理由={}",
                    thread.title(), verdict.text(), reason);
            return Outcome.REPLIED;
        }
        return fail(task, UnreadTaskStore.Action.SEND_FAILED, null, "发送未确认送达（AI 回复）");
    }

    /**
     * 命中「发简历」规则时的处置。
     *
     * <p>三条分支对应三种完全不同的现实，不能合并：
     * <ul>
     *   <li><b>发出去了</b> —— 什么都别补。用户明确要求发完简历不要再说话，
     *       而且连发两条（卡片 + 一句话）在 HR 眼里很像机器人。</li>
     *   <li><b>按钮是灰的</b> —— 平台要求「双方回复后可用」。这是业务状态不是故障，
     *       重试没有意义，降级成说一句话，别把这个会话晾死。</li>
     *   <li><b>点了没成功</b> —— 可能慢、可能改版，进重试链。</li>
     * </ul>
     */
    private Outcome sendResumeOutcome(PlatformAdapter adapter, UnreadThread thread, UnreadTaskEntity task,
                                      ChatMessage last, ConversationEntity conversation, Long conversationId,
                                      ReplyRuleEngine.Decision decision) {
        ResumeResult result = adapter.sendResume(thread);
        String reason = decision.reason();

        if (result.status() == ResumeResult.Status.SENT) {
            if (conversation != null) {
                replyStore.markOwnReply(conversation);
            }
            logAction(conversationId, last, ReplyLogEntity.ACTION_REPLY_RESUME, decision.ruleId(),
                    null, null, ReplyLogEntity.SOURCE_RESUME, null, reason);
            taskStore.markDone(task, last.id(), UnreadTaskStore.Action.REPLIED, null, reason);
            log.info("已发出简历（未补文字）| 会话={} | 理由={} | 凭据={}",
                    thread.title(), reason, result.detail());
            return Outcome.REPLIED;
        }

        if (result.status() == ResumeResult.Status.UNAVAILABLE) {
            String fallback = decision.replyText();
            log.warn("简历按钮不可用，降级成文字 | 会话={} | 平台原因={} | 兜底文案={}",
                    thread.title(), result.detail(), fallback == null ? "（无）" : "「" + fallback + "」");
            if (fallback == null || fallback.isBlank()) {
                logAction(conversationId, last, UnreadTaskStore.Action.AI_SKIPPED, decision.ruleId(),
                        null, null, ReplyLogEntity.SOURCE_RESUME, "RESUME_UNAVAILABLE",
                        reason + "；按钮不可用且没有兜底文案");
                taskStore.markDone(task, last.id(), UnreadTaskStore.Action.AI_SKIPPED,
                        "RESUME_UNAVAILABLE", reason);
                return Outcome.SKIPPED;
            }
            boolean sent = adapter.sendText(thread, fallback);
            if (!sent) {
                return fail(task, UnreadTaskStore.Action.SEND_FAILED, null,
                        "简历按钮不可用，兜底文字也没发出去");
            }
            if (conversation != null) {
                replyStore.markOwnReply(conversation);
            }
            String note = reason + "；按钮不可用（" + result.detail() + "），改发兜底文案";
            logAction(conversationId, last, ReplyRuleEngine.Action.REPLY.name(), decision.ruleId(),
                    null, fallback, ReplyLogEntity.SOURCE_TEMPLATE, "RESUME_UNAVAILABLE", note);
            taskStore.markDone(task, last.id(), UnreadTaskStore.Action.REPLIED, null, note);
            log.info("已发送（简历不可用降级）| 会话={} | 内容=「{}」 | 理由={}",
                    thread.title(), fallback, note);
            return Outcome.REPLIED;
        }

        log.warn("发简历失败 | 会话={} | 理由={} | {}", thread.title(), reason, result.detail());
        return fail(task, UnreadTaskStore.Action.SEND_FAILED, null, "发简历失败：" + result.detail());
    }

    /** 把「为什么这么回」拼成一句话：规则命中与 AI 自主判断必须能一眼区分。 */
    private String aiReason(ReplyRuleEngine engine, ReplyRuleEngine.Decision decision, AiReplier.Reply draft) {
        String modelSaid = draft.note() == null ? "" : draft.note().strip();
        if (decision.ruleId() != null) {
            String base = "命中规则「" + nvl(ruleNameOf(engine, decision.ruleId())) + "」"
                    + "（关键词「" + nvl(decision.matchedKeyword()) + "」），AI 据此改写";
            return modelSaid.isEmpty() ? base : base + "：" + modelSaid;
        }
        return modelSaid.isEmpty() ? "未命中规则，AI 自主判断" : "未命中规则，AI 自主判断：" + modelSaid;
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    /** 日志里的摘要别把整段 HR 原话打出来，一行日志会被撑成十几行。 */
    private static String clipLog(String text) {
        if (text == null) {
            return "";
        }
        String one = text.replaceAll("\\s+", " ").strip();
        return one.length() <= 40 ? one : one.substring(0, 40) + "…";
    }

    /**
     * AI 技术性失败时的降级。
     *
     * <p>命中规则 → 发模板：人工审核过的文案，确定性可用，比「这条不回」安全得多。
     * 未命中 → 记 {@code AI_FAILED} 进重试链：没有备用模板时，重试是唯一不丢消息的路。
     *
     * <p>注意这里<b>不能</b>按 spec 3.6 那句「未命中 → 记 AI_SKIPPED」落地 ——
     * 与 3.6.2「技术性失败不算 skip，走 AI_FAILED」直接冲突。按 3.6.2 走：
     * 「模型在躲」和「接口在坏」混成一个数字，统计面板就废了。
     */
    private Outcome degradeToTemplate(PlatformAdapter adapter, UnreadThread thread, UnreadTaskEntity task,
                                      ChatMessage last, ConversationEntity conversation, Long conversationId,
                                      ReplyRuleEngine.Decision decision, String ruleText, String error) {
        if (ruleText == null || ruleText.isBlank()) {
            return fail(task, UnreadTaskStore.Action.AI_FAILED, null, error);
        }
        boolean sent = adapter.sendText(thread, ruleText);
        if (!sent) {
            return fail(task, UnreadTaskStore.Action.SEND_FAILED, null,
                    "AI 失败（" + error + "）后降级模板也发送失败");
        }
        if (conversation != null) {
            replyStore.markOwnReply(conversation);
        }
        logAction(conversationId, last, ReplyRuleEngine.Action.REPLY.name(), decision.ruleId(),
                null, ruleText, ReplyLogEntity.SOURCE_TEMPLATE, null, "AI 失败，降级用模板");
        taskStore.markDone(task, last.id(), UnreadTaskStore.Action.REPLIED, null, "AI 失败，降级用模板");
        log.info("AI 失败，已降级用模板文案 | 会话={} | {}", thread.title(), error);
        return Outcome.REPLIED;
    }

    /**
     * 四检拦截 → 落库动作。
     *
     * <p>{@code BLOCKED_TOO_LONG} 是 P2 遗留的待裁决项（当时没有写入点，不发明枚举）。
     * 现在有了：长度超限确实要整条不发，而把它塞进 {@code BLOCKED_TONE} 会让「AI 味」这个
     * 指标被长度问题污染 —— 一眼看不出到底是套话还是话太多。
     */
    private static String blockedAction(ReplyGuard.Rejection rejection) {
        return switch (rejection) {
            // EMPTY 已在调用处单独处理（要重试，不是终态）；NONE 不该走到这里。
            // 其余四种由枚举名派生，与 ReplyGuard.BLOCKED_ACTIONS 同源，不再各写一份
            case EMPTY, NONE -> UnreadTaskStore.Action.AI_FAILED;
            default -> "BLOCKED_" + rejection.name();
        };
    }

    private static String ruleNameOf(ReplyRuleEngine engine, Long ruleId) {
        if (ruleId == null) {
            return null;
        }
        for (ReplyRuleEngine.Rule rule : engine.rules()) {
            if (ruleId.equals(rule.id())) {
                return rule.name();
            }
        }
        return null;
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
        logAction(conversationId, message, action, ruleId, sensitive, replyText,
                ReplyLogEntity.SOURCE_TEMPLATE, null, null);
    }

    private void logAction(Long conversationId, ChatMessage message, String action,
                           Long ruleId, String sensitive, String replyText,
                           String source, String reasonCode, String reasonNote) {
        ReplyLogEntity entity = new ReplyLogEntity();
        entity.setConversationId(conversationId);
        entity.setPlatformMessageId(message.id());
        entity.setIncomingText(message.text() == null ? "" : message.text());
        entity.setAction(action);
        entity.setMatchedRuleId(ruleId);
        entity.setMatchedSensitive(sensitive);
        entity.setReplyText(replyText);
        entity.setReplySource(source);
        entity.setReasonCode(reasonCode);
        entity.setReasonNote(reasonNote);
        replyStore.log(entity);
    }
}
