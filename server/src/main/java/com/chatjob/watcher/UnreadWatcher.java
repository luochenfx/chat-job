package com.chatjob.watcher;

import com.chatjob.platform.Digest;
import com.chatjob.platform.Models.ChatMessage;
import com.chatjob.platform.Models.ResumeResult;
import com.chatjob.platform.Models.UnreadThread;
import com.chatjob.platform.PlatformAdapter;
import com.chatjob.platform.ThreadTargetUnsafeException;
import com.chatjob.quality.QualityAssessor;
import com.chatjob.runlog.RunLog;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.ConversationEntity;
import com.chatjob.store.entity.ReplyLogEntity;
import com.chatjob.store.entity.UnreadTaskEntity;
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
 * <p><b>三条路径，按演练标记与开关分流（spec 0002）：</b>
 * <ul>
 *   <li>{@code dryRun} → {@link #sweepDryRun}，只感知、判定、记日志。方法体里没有外发动作，
 *       所以「演练不发消息」在结构上成立。</li>
 *   <li>{@code ai_reply_enabled = 0} → {@link #sweepLegacy}，与改造前逐行一致。
 *       刻意保留成一份独立的代码而不是在主循环里插 if，是为了让「关闭即 no-op」
 *       这件事<b>在结构上成立</b>，而不是靠每次改动都小心翼翼不去碰它。</li>
 *   <li>{@code ai_reply_enabled = 1} → {@link #sweepWithTasks}，先落任务再点开会话，
 *       失败按退避重试，AI 分支在 P3 接入。</li>
 * </ul>
 *
 * <p>三条路径的循环体各不相同 —— 那是真实的差异，不合并。但它们对「一轮」的处理逐字相同：
 * 读列表、跳过读不到未读数的会话、按结论计数、轮末告警、关会话。这些收在
 * {@link UnreadSweep} 一处，本类只写循环体。
 *
 * <p><b>日志走 {@link RunLog}，本类不再持有 slf4j 的 {@code Logger}（ADR-0008）。</b>
 * 改之前这里的 41 条日志只进文件、一条都不进 {@code run_log}：控制台上「值守跑了但什么都没发生」
 * 无法归因，而且这些行连平台前缀都没有 —— 两个平台并行时根本分不清是谁在回消息。
 * 现在连 {@link #sweepDryRun} 的「本会回复」也落库：那是演练唯一的产出。
 */
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
    private final QualityAssessor qualityAssessor;

    public UnreadWatcher(ReplyStore replyStore, UnreadTaskStore taskStore, ConfigStore configStore,
                         AiReplier aiReplier, QualityAssessor qualityAssessor) {
        this.replyStore = replyStore;
        this.taskStore = taskStore;
        this.configStore = configStore;
        this.aiReplier = aiReplier;
        this.qualityAssessor = qualityAssessor;
    }

    /**
     * @param unmatched  未命中规则<b>且没有兜底文案可发</b>的会话数 —— 关闭态里它是「漏回」的计数。
     *                   打开态恒为 0 且应当恒为 0：未命中的消息一律交给 AI，结局只有「已回复 /
     *                   AI 主动跳过 / 失败重试」三种，不存在「未命中就漏回」这一栏。若把它改成
     *                   「判定时未命中规则」，{@code replied} 与它会同时 +1，控制台那句
     *                   「已回复 N 个，没匹配到规则 M 个」就变成两栏重复计同一批人。
     * @param retried    本轮重试的任务数（这些会话的红点在上一轮就已经消失）
     * @param failed     本轮失败、已排入下次重试的任务数
     * @param wouldReply 演练模式专属：本轮「本会回复」的条数。{@code replied} 在演练下恒为 0，
     *                   否则控制台会把「本会回复」显示成「已回复」，给操作者一个假的成功信号
     */
    public record SweepResult(int threads, int replied, int unmatched, int sensitive,
                              int retried, int failed, int wouldReply,
                              List<QualityAssessor.Assessed> assessed) {
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
     * @param runLog 本轮的运行日志出口，由调用方（{@code RunService}）为当前 Run 创建。
     *               做成参数而不是本类字段：本类是单例、值守本身无状态，而 RunLog 绑定的是
     *               某一条 Run —— 存成字段就得回答「现在跑的是哪条」，那个答案只有编排层有。
     */
    public SweepResult sweep(PlatformAdapter adapter, String platform, boolean collectMetrics,
                             boolean dryRun, RunLog runLog) {
        // 演练支路必须排在开关判断之前：它与 ai_reply_enabled 无关 —— 开着 AI 回复时
        // 演练照样不能发。把它放进下面两个分支里，等于要求两处都记得判断。
        if (dryRun) {
            return sweepDryRun(adapter, platform, runLog);
        }
        // 关闭态必须与改造前逐行一致（spec 2.1 第 1 条）。这里不「顺手」做任何优化。
        if (!configStore.aiReplyEnabled()) {
            return sweepLegacy(adapter, platform, collectMetrics, runLog);
        }
        return sweepWithTasks(adapter, platform, collectMetrics, runLog);
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
    private SweepResult sweepDryRun(PlatformAdapter adapter, String platform, RunLog runLog) {
        UnreadSweep round = new UnreadSweep(adapter, runLog);
        try {
            ReplyRuleEngine engine = replyStore.engine();
            ReplyGuard.GuardOptions guard = new ReplyGuard.GuardOptions(
                    configStore.toneTerms(), configStore.toneCheckEnabled(), engine.sensitiveWords());
            AiBudget budget = new AiBudget(configStore.aiReplyMaxPerSweep());
            for (UnreadThread thread : round.list()) {
                if (round.skip(thread)) {
                    continue;
                }
                try {
                    // 接管与演练无关：演练会真的点开会话，而点开就清掉平台红点（ADR-0016）。
                    // 演练唯一的副作用本来就是这个，接管中的会话没有理由替用户付这个代价
                    ConversationEntity rehearsalConversation = replyStore.findConversation(
                            platform, thread.conversationId());
                    if (rehearsalConversation != null && rehearsalConversation.takenOver()) {
                        runLog.info("演练：跳过（人工接管中）| 会话=" + thread.title());
                        continue;
                    }
                    runLog.info("演练：进入会话 | 会话=" + thread.title() + " | 未读=" + thread.unread()
                            + " | 摘要=「" + clipLog(thread.lastMessage()) + "」");
                    List<ChatMessage> messages = adapter.readMessages(thread);
                    if (messages.isEmpty()) {
                        runLog.warn("演练：会话「" + thread.title() + "」消息区读不到任何消息");
                        continue;
                    }
                    ChatMessage last = messages.get(messages.size() - 1);
                    // duplicate / throttled 一律传 false：见方法注释，它们读的是真实痕迹
                    ReplyRuleEngine.Decision decision =
                            engine.decide(last.text(), last.fromSelf(), last.system(), false, false);
                    runLog.info("演练判定 | 会话=" + thread.title() + " | 动作=" + decision.action()
                            + " | 规则=" + ruleNameOf(engine, decision.ruleId())
                            + " | 命中词=" + nvl(decision.matchedKeyword()) + " | 理由=" + decision.reason());
                    if (decision.action() == ReplyRuleEngine.Action.SKIP_SYSTEM) {
                        // 演练要把这条说出来：真实路径会跳过它，演练却报「本会回复」就对不上
                        runLog.info("演练：跳过（最后一条是平台系统消息）| 会话=" + thread.title());
                        continue;
                    }
                    if (decision.action() == ReplyRuleEngine.Action.SKIP_SENSITIVE) {
                        round.addSensitive();
                        continue;
                    }
                    // 演练也要把「本来会发简历」说出来，否则演练结果和真实结果对不上：
                    // 真实路径会点按钮发卡片，演练路径却只报「未命中」。
                    if (decision.action() == ReplyRuleEngine.Action.SEND_RESUME) {
                        round.addWouldReply();
                        runLog.info("演练模式：本会发简历（不发文字）| 会话=" + thread.title()
                                + " | 理由=" + decision.reason());
                        continue;
                    }
                    if (decision.action() == ReplyRuleEngine.Action.UNMATCHED) {
                        round.addUnmatched();
                    }
                    // 演练也过一遍 AI：不然「AI 到底会回什么」只能等真实运行，而真实运行会发出去。
                    // 这是 P3 之后演练的主要价值 —— 它是唯一能安全看到 AI 输出的地方。
                    String ruleText = decision.shouldReply() ? decision.replyText() : null;
                    if (!budget.tryUse()) {
                        runLog.info("演练：AI 调用已达本轮上限 " + budget.max() + " 次，其余会话不预演");
                        break;
                    }
                    AiReplier.Reply preview = aiReplier.reply(new AiReplier.Input(
                            thread.title(), configStore.aiIntroduce(), configStore.aiReplyStyle(),
                            messages, ruleNameOf(engine, decision.ruleId()), ruleText,
                            engine.sensitiveWords()));
                    if (preview.isFailure()) {
                        if (ruleText != null && !ruleText.isBlank()) {
                            round.addWouldReply();
                            runLog.info("演练模式：本会回复「" + thread.title()
                                    + "」→ AI 失败，降级用模板「" + ruleText + "」");
                        } else {
                            runLog.info("演练模式：本不会回复「" + thread.title()
                                    + "」→ AI 失败且无模板可降级");
                        }
                        continue;
                    }
                    if (preview.skip()) {
                        runLog.info("演练模式：本不会回复「" + thread.title() + "」→ AI 主动跳过（"
                                + preview.skipReason() + "）：" + preview.note());
                        continue;
                    }
                    ReplyGuard.Verdict verdict = ReplyGuard.check(preview.text(), last.text(), guard);
                    if (verdict.blocked()) {
                        runLog.info("演练模式：本不会回复「" + thread.title() + "」→ AI 草稿被出站校验拦下（"
                                + verdict.rejection() + "）：" + verdict.detail());
                        continue;
                    }
                    round.addWouldReply();
                    runLog.info("演练模式：本会回复「" + thread.title() + "」→ AI「" + verdict.text() + "」");
                } catch (ThreadTargetUnsafeException e) {
                    // 演练要把这类问题暴露出来 —— 会话定位不安全正是「半夜验证」最该验到的部分
                    runLog.warn("演练：会话「" + thread.title() + "」定位不安全（" + e.code() + "）："
                            + e.getMessage());
                } catch (Exception e) {
                    runLog.warn("演练：处理会话「" + thread.title() + "」失败: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            runLog.warn("演练值守扫描失败（不影响扫描主链）: " + e.getMessage());
        } finally {
            round.close();
        }
        round.warnUnreadable();
        SweepResult result = round.result();
        if (result.threads() > 0) {
            runLog.info("演练值守扫描完成（不发消息）: 会话 " + result.threads() + "，本会回复 "
                    + result.wouldReply() + "，未命中 " + result.unmatched()
                    + "，敏感跳过 " + result.sensitive());
        }
        return result;
    }

    // ================================================================ 关闭态：改造前路径

    private SweepResult sweepLegacy(PlatformAdapter adapter, String platform, boolean collectMetrics,
                                   RunLog runLog) {
        UnreadSweep round = new UnreadSweep(adapter, runLog);
        try {
            ReplyRuleEngine engine = replyStore.engine();
            for (UnreadThread thread : round.list()) {
                if (round.skip(thread)) {
                    continue;
                }
                try {
                    ConversationEntity conversation = replyStore.upsertConversation(
                            platform, thread.conversationId(), null);
                    // 接管与 AI 开关无关：含义是「我自己聊」，不管系统用什么方式替它说话（ADR-0016 第 4 条）
                    if (conversation.takenOver()) {
                        replyStore.recordThreadObservation(conversation, thread.lastMessage(), thread.unread());
                        runLog.info("跳过（人工接管中，不打开会话）| 会话=" + thread.title()
                                + " | 未读=" + thread.unread());
                        continue;
                    }
                    List<ChatMessage> messages = adapter.readMessages(thread);
                    if (messages.isEmpty()) {
                        // 「读不到」不是「没有新消息」：留一行，否则这条未读在日志里凭空消失
                        runLog.warn("会话「" + thread.title() + "」消息区读不到任何消息，本轮跳过");
                        continue;
                    }
                    ChatMessage last = messages.get(messages.size() - 1);
                    boolean duplicate = replyStore.wasProcessed(conversation.getId(), last.id());
                    boolean throttled = replyStore.inCooldown(conversation);
                    ReplyRuleEngine.Decision decision = engine.decide(
                            last.text(), last.fromSelf(), last.system(), duplicate, throttled);

                    runLog.info("进入回复流程 | 会话=" + thread.title() + " | 未读=" + thread.unread()
                            + " | 摘要=「" + clipLog(thread.lastMessage()) + "」");
                    runLog.info("判定 | 会话=" + thread.title() + " | 动作=" + decision.action()
                            + " | 规则=" + ruleNameOf(engine, decision.ruleId())
                            + " | 命中词=" + nvl(decision.matchedKeyword()) + " | 理由=" + decision.reason());

                    if (decision.action() == ReplyRuleEngine.Action.SKIP_DUPLICATE) {
                        continue;
                    }
                    // 系统消息不记效果采集：它不是 HR 说的话，记进去会把首响时间与条数算虚
                    if (!last.fromSelf() && !last.system() && collectMetrics) {
                        replyStore.recordHrMessage(conversation);
                    }

                    boolean sent = false;
                    String reason = decision.reason();
                    if (decision.action() == ReplyRuleEngine.Action.SEND_RESUME) {
                        // 关闭 AI 也要能发简历：这是「动作」不是「回复风格」，
                        // 没道理跟着 AI 开关一起消失。
                        if (resumeAlreadySent(conversation.getId(), thread.title(), runLog)) {
                            // 这是缺陷修复，不是「顺手优化」：平台回执曾经整条命中简历规则，
                            // 关闭态一样会给同一个 HR 连发三份。处置与「按钮不可用」同形 ——
                            // 发不成就说一句话，别把会话晾死。
                            String fallback = decision.replyText();
                            if (fallback != null && !fallback.isBlank()) {
                                sent = adapter.sendText(thread, fallback);
                                if (sent) {
                                    replyStore.markOwnReply(conversation);
                                    round.addReplied();
                                    runLog.info("已发送（该会话已发过简历，改发兜底文案）| 会话="
                                            + thread.title() + " | 内容=「" + fallback + "」");
                                }
                            }
                            logAction(conversation.getId(), last,
                                    sent ? ReplyRuleEngine.Action.REPLY.name()
                                            : UnreadTaskStore.Action.AI_SKIPPED,
                                    decision.ruleId(), null, sent ? fallback : null,
                                    sent ? ReplyLogEntity.SOURCE_TEMPLATE : ReplyLogEntity.SOURCE_RESUME,
                                    "RESUME_ALREADY_SENT", reason);
                            continue;
                        }
                        ResumeResult resume = adapter.sendResume(thread);
                        if (resume.status() == ResumeResult.Status.SENT) {
                            replyStore.markOwnReply(conversation);
                            round.addReplied();
                            logAction(conversation.getId(), last, ReplyLogEntity.ACTION_REPLY_RESUME,
                                    decision.ruleId(), null, null, ReplyLogEntity.SOURCE_RESUME, null, reason);
                            runLog.info("已发出简历（未补文字）| 会话=" + thread.title() + " | 理由=" + reason);
                            continue;
                        }
                        if (resume.status() == ResumeResult.Status.UNAVAILABLE
                                && decision.replyText() != null && !decision.replyText().isBlank()) {
                            // 按钮灰了才说话；能发的时候一句话都不加
                            reason = reason + "；按钮不可用（" + resume.detail() + "），改发兜底文案";
                            sent = adapter.sendText(thread, decision.replyText());
                            if (sent) {
                                replyStore.markOwnReply(conversation);
                                round.addReplied();
                                runLog.info("已发送（简历不可用降级）| 会话=" + thread.title()
                                        + " | 内容=「" + decision.replyText() + "」");
                            }
                        } else {
                            runLog.warn("发简历失败 | 会话=" + thread.title() + " | " + resume.status()
                                    + " | " + resume.detail());
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
                            round.addReplied();
                            runLog.info("已发送 | 会话=" + thread.title() + " | 来源=模板"
                                    + " | 规则=" + ruleNameOf(engine, decision.ruleId())
                                    + " | 内容=「" + decision.replyText() + "」");
                        } else {
                            runLog.warn("值守回复发送失败 | 会话=" + thread.title()
                                    + " | 规则=" + decision.ruleId());
                        }
                    } else if (decision.action() == ReplyRuleEngine.Action.UNMATCHED) {
                        round.addUnmatched();
                    } else if (decision.action() == ReplyRuleEngine.Action.SKIP_SENSITIVE) {
                        round.addSensitive();
                    }

                    String action = decision.shouldReply() && !sent
                            ? ReplyRuleEngine.Action.UNMATCHED.name()
                            : decision.action().name();
                    logAction(conversation.getId(), last, action, decision.ruleId(),
                            decision.sensitive(), decision.replyText(),
                            ReplyLogEntity.SOURCE_TEMPLATE, null, reason);
                } catch (Exception e) {
                    runLog.warn("处理会话 " + thread.title() + " 失败: " + e.getMessage());
                }
            }
            round.warnUnreadable();
        } catch (Exception e) {
            runLog.warn("值守扫描失败（不影响扫描主链）: " + e.getMessage());
        } finally {
            round.close();
        }
        SweepResult result = round.result();
        if (result.threads() > 0) {
            runLog.info("值守扫描完成: 会话 " + result.threads() + "，回复 " + result.replied()
                    + "，未命中 " + result.unmatched() + "，敏感跳过 " + result.sensitive());
        }
        return result;
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
    private SweepResult sweepWithTasks(PlatformAdapter adapter, String platform, boolean collectMetrics,
                                       RunLog runLog) {
        UnreadSweep round = new UnreadSweep(adapter, runLog);
        try {
            ReplyRuleEngine engine = replyStore.engine();
            // 出站四检的入参在一轮内不变，构造一次即可
            ReplyGuard.GuardOptions guard = new ReplyGuard.GuardOptions(
                    configStore.toneTerms(), configStore.toneCheckEnabled(), engine.sensitiveWords());
            AiBudget budget = new AiBudget(configStore.aiReplyMaxPerSweep());
            // 质量判断另有一个预算：它与回复是两个用途，共用会让一段长对话把回复的名额吃掉
            QualityAssessor.Budget qualityBudget = qualityAssessor.budget();

            for (UnreadTaskEntity task : taskStore.dueForRetry(MAX_RETRY_PER_SWEEP)) {
                round.addRetried();
                // 接管发生在任务排进重试之后也可能：那样这条任务会一直重试到上限。
                // 每一轮都判一次，命中就收尾，不留一条注定发不出去的任务在表里
                ConversationEntity retryConversation = replyStore.findById(task.getConversationId());
                if (retryConversation != null && retryConversation.takenOver()) {
                    taskStore.markDone(task, null, UnreadTaskStore.Action.SKIP_TAKEOVER, null,
                            "人工接管中，不再替这段会话回复");
                    runLog.info("跳过重试（人工接管中）| 会话=" + task.getPlatformConversationId());
                    continue;
                }
                UnreadThread thread = UnreadThread.retry(
                        task.getPlatformConversationId(), task.getLastMessageDigest());
                tally(round, process(adapter, engine, thread, task, collectMetrics, guard, budget,
                        qualityBudget, round, runLog));
            }

            for (UnreadThread thread : round.list()) {
                if (round.skip(thread)) {
                    continue;
                }
                try {
                    ConversationEntity conversation = replyStore.upsertConversation(
                            platform, thread.conversationId(), null);
                    // 人工接管（ADR-0016）：不打开会话、不读消息、不落任务。打开会清掉平台上的
                    // 未读红点，人不在电脑前就会看到「已读没人回」，比不读更糟。
                    // 但列表页本来就给了最后一句与未读数，留下来界面才看得到「HR 又说了 N 条」
                    if (conversation.takenOver()) {
                        replyStore.recordThreadObservation(conversation, thread.lastMessage(), thread.unread());
                        runLog.info("跳过（人工接管中，不打开会话）| 会话=" + thread.title()
                                + " | 未读=" + thread.unread() + " | 摘要=「"
                                + clipLog(thread.lastMessage()) + "」");
                        continue;
                    }
                    // 先落任务，再点开会话：点开后红点就没了，顺序错了这条消息就丢（spec 2.1 第 2 条）
                    UnreadTaskEntity task = taskStore.openOrTouch(
                            platform, thread.conversationId(), conversation.getId(),
                            thread.unread(), Digest.of(thread.lastMessage()));
                    if (taskStore.waitingForRetry(task)) {
                        // 还在退避窗口里：它已经排好下次重试时间，现在处理等于把退避废掉。
                        // 但要留一行：否则「这条未读一直没回」在日志里找不到任何解释（ADR-0008 第 3 条）
                        runLog.info("跳过（重试退避中，下次重试 " + task.getNextRetryAt() + "）| 会话="
                                + thread.title());
                        continue;
                    }
                    tally(round, process(adapter, engine, thread, task, collectMetrics, guard, budget,
                            qualityBudget, round, runLog));
                } catch (Exception e) {
                    runLog.warn("处理会话 " + thread.title() + " 失败: " + e.getMessage());
                }
            }

            round.warnUnreadable();
        } catch (Exception e) {
            runLog.warn("值守扫描失败（不影响扫描主链）: " + e.getMessage());
        } finally {
            round.close();
        }
        SweepResult result = round.result();
        if (result.threads() > 0 || result.retried() > 0) {
            runLog.info("值守扫描完成（AI 模式）: 会话 " + result.threads() + "，重试 " + result.retried()
                    + "（失败 " + result.failed() + "），回复 " + result.replied() + "，未命中 "
                    + result.unmatched() + "，敏感跳过 " + result.sensitive());
        }
        return result;
    }

    /**
     * 把一条未读的收尾结论落到本轮计数。
     *
     * <p>重试循环与新未读循环各判一次同样的四值枚举，合并在这里 —— 两处各写一份
     * 正是这一层最容易漏掉某个分支的地方（打开态曾经忘了累加 {@code unmatched}）。
     * {@code SKIPPED} 与 {@code ABANDONED} 只影响任务状态，不进本轮计数。
     */
    private static void tally(UnreadSweep round, Outcome outcome) {
        switch (outcome) {
            case REPLIED -> round.addReplied();
            case SENSITIVE -> round.addSensitive();
            case FAILED -> round.addFailed();
            default -> {
            }
        }
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
                            ReplyGuard.GuardOptions guard, AiBudget budget,
                            QualityAssessor.Budget qualityBudget, UnreadSweep round, RunLog runLog) {
        Long conversationId = task.getConversationId();
        ConversationEntity conversation = replyStore.findById(conversationId);
        taskStore.claim(task);

        // 每个会话一条入口日志。改造前这里一条都不打，成功路径全程静默 ——
        // 真发了消息的人在日志里找不到任何痕迹，只能靠「轮末那行汇总里 replied=1」反推。
        runLog.info("进入回复流程 | 会话=" + thread.title() + " | 未读=" + thread.unread()
                + " | 摘要=「" + clipLog(thread.lastMessage()) + "」");

        List<ChatMessage> messages;
        try {
            messages = adapter.readMessages(thread);
        } catch (ThreadTargetUnsafeException e) {
            // 同名多条 / 摘要已变：重试一万次也不会变好，直接放弃（spec 3.4）
            runLog.warn("会话定位不安全，放弃这条未读 | 会话=" + thread.title() + " | " + e.code()
                    + " | " + e.getMessage());
            taskStore.giveUp(task, e.code(), e.getMessage());
            return Outcome.ABANDONED;
        } catch (Exception e) {
            return fail(thread, task, UnreadTaskStore.Action.READ_FAILED, null, e.getMessage(), runLog);
        }
        if (messages.isEmpty()) {
            // 「读不到」和「没有消息」在这里无法区分，交重试：3 次都空就 GAVE_UP，控制台可见
            return fail(thread, task, UnreadTaskStore.Action.READ_FAILED, null, "消息区读不到任何消息", runLog);
        }

        ChatMessage last = messages.get(messages.size() - 1);
        if (last.fromSelf()) {
            // 最后一条是我方：可能是上一轮发送其实成功了（送达判据没确认住），
            // 这里靠消息归属自然收敛，不会重复发。
            taskStore.markDone(task, last.id(), ReplyRuleEngine.Action.SKIP_SELF.name(), null,
                    "最后一条是我方消息「" + clipLog(last.text()) + "」，等对方先回");
            runLog.info("跳过（最后一条是我方消息）| 会话=" + thread.title());
            return Outcome.SKIPPED;
        }
        if (last.system()) {
            // 平台播报的回执：「附件简历已发送给对方」这类。它带着「简历」这种关键词，
            // 交给引擎就会再发一次简历 —— 2026-11-06 连发三份就是这么来的。
            // 收尾成 DONE 而不是跳过重试：下一轮读到的还是这条回执，重试没有意义。
            taskStore.markDone(task, last.id(), ReplyRuleEngine.Action.SKIP_SYSTEM.name(), null,
                    "最后一条是平台系统消息「" + clipLog(last.text()) + "」，不作为回复依据");
            runLog.info("跳过（最后一条是系统消息）| 会话=" + thread.title()
                    + " | 内容=「" + clipLog(last.text()) + "」");
            return Outcome.SKIPPED;
        }
        if (replyStore.wasProcessed(conversationId, last.id())) {
            taskStore.markDone(task, last.id(), ReplyRuleEngine.Action.SKIP_DUPLICATE.name(), null,
                    "消息 " + last.id() + " 上一轮已处理过");
            runLog.info("跳过（这条消息已处理过）| 会话=" + thread.title() + " | mid=" + last.id());
            return Outcome.SKIPPED;
        }
        if (conversation != null && replyStore.inCooldown(conversation)) {
            logAction(conversationId, last, ReplyRuleEngine.Action.SKIP_THROTTLED.name(), null, null, null,
                    ReplyLogEntity.SOURCE_TEMPLATE, null,
                    "同会话 " + ReplyStore.REPLY_COOLDOWN_MINUTES + " 分钟冷却期内");
            taskStore.markDone(task, last.id(), ReplyRuleEngine.Action.SKIP_THROTTLED.name(),
                    null, "同会话 " + ReplyStore.REPLY_COOLDOWN_MINUTES + " 分钟冷却期内");
            runLog.info("跳过（" + ReplyStore.REPLY_COOLDOWN_MINUTES + " 分钟冷却期内）| 会话=" + thread.title());
            return Outcome.SKIPPED;
        }
        if (collectMetrics && conversation != null) {
            replyStore.recordHrMessage(conversation);
        }

        // 岗位质量判断（ADR-0015）。放在这里而不是回复之后：消息已经读在手里，
        // 而「判不判」与「这条回不回、回什么」无关 —— 后面每个分支都要重复一次调用就太蠢了
        if (conversation != null) {
            QualityAssessor.Assessed assessed = qualityAssessor.assessIfDue(
                    conversation, messages, qualityBudget, runLog);
            if (assessed != null) {
                round.addAssessed(assessed);
            }
        }

        // 自己发的 / 系统 / 重复 / 冷却 已在上面判完，这里只让引擎回答「敏不敏感、命中哪条规则」
        ReplyRuleEngine.Decision decision = engine.decide(last.text(), false, false, false, false);

        // 「为什么这么回」必须在日志里能直接读到，不能只落库：出事的时候人是先翻日志的。
        runLog.info("判定 | 会话=" + thread.title() + " | 动作=" + decision.action()
                + " | 规则=" + ruleNameOf(engine, decision.ruleId())
                + " | 命中词=" + nvl(decision.matchedKeyword()) + " | 理由=" + decision.reason());

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
            return sendResumeOutcome(adapter, thread, task, last, conversation, conversationId, decision, runLog);
        }

        // 命中与未命中都走 AI，差别只在「带不带参考素材」
        String ruleText = decision.shouldReply() ? decision.replyText() : null;
        String ruleName = ruleNameOf(engine, decision.ruleId());

        if (!budget.tryUse()) {
            // 「什么都没发生」也要有一行：用户看到的是「这条未读一直没回」，
            // 而真实原因是本轮的 AI 配额用完了、它被排到下一轮（ADR-0008 第 3 条）
            runLog.info("本轮 AI 调用已达上限 " + budget.max() + " 次，这条留到下一轮 | 会话=" + thread.title());
            taskStore.release(task, "本轮 AI 调用已达上限 " + budget.max() + " 次，留到下一轮");
            return Outcome.SKIPPED;
        }

        AiReplier.Reply draft = aiReplier.reply(new AiReplier.Input(
                thread.title(), configStore.aiIntroduce(), configStore.aiReplyStyle(),
                messages, ruleName, ruleText, engine.sensitiveWords()));

        if (draft.isFailure()) {
            return degradeToTemplate(adapter, thread, task, last, conversation, conversationId,
                    decision, ruleText, draft.error(), runLog);
        }

        if (draft.skip()) {
            // AI 主动不回：这是安全阀，不是故障。记枚举原因，别让它混进失败统计
            logAction(conversationId, last, UnreadTaskStore.Action.AI_SKIPPED, decision.ruleId(),
                    null, null, ReplyLogEntity.SOURCE_AI, draft.skipReason(), draft.note());
            taskStore.markDone(task, last.id(), UnreadTaskStore.Action.AI_SKIPPED,
                    draft.skipReason(), draft.note());
            runLog.info("AI 主动不回 | 会话=" + thread.title() + " | 原因=" + draft.skipReason()
                    + " | " + nvl(draft.note()));
            return Outcome.SKIPPED;
        }

        ReplyGuard.Verdict verdict = ReplyGuard.check(draft.text(), last.text(), guard);
        if (verdict.rejection() == ReplyGuard.Rejection.EMPTY) {
            return fail(thread, task, UnreadTaskStore.Action.AI_FAILED, null, "AI 给出空回复", runLog);
        }
        if (verdict.blocked()) {
            String action = blockedAction(verdict.rejection());
            logAction(conversationId, last, action, decision.ruleId(), null, null,
                    ReplyLogEntity.SOURCE_AI, null, verdict.detail());
            // 不重试：同一 prompt 再问一次大概率给同样的东西，四检又是确定性规则，
            // 重试只会多花一次调用、把同样的结果再拦一次
            taskStore.markDone(task, last.id(), action, null, verdict.detail());
            runLog.warn("AI 回复被出站校验拦下（整条不发）| 会话=" + thread.title()
                    + " | " + verdict.rejection() + " | " + verdict.detail());
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
            runLog.info("已发送 | 会话=" + thread.title() + " | 来源=AI | 内容=「" + verdict.text()
                    + "」 | 理由=" + reason);
            return Outcome.REPLIED;
        }
        return fail(thread, task, UnreadTaskStore.Action.SEND_FAILED, null, "发送未确认送达（AI 回复）", runLog);
    }

    /**
     * 命中「发简历」规则时的处置。
     *
     * <p>四条分支对应四种完全不同的现实，不能合并：
     * <ul>
     *   <li><b>这个会话已经发过</b> —— 一份简历只有一个版本，同一个 HR 收到第二份只会觉得
     *       这边是机器人。降级成说一句话（与「按钮是灰的」同形），不重复消耗动作。</li>
     *   <li><b>发出去了</b> —— 什么都别补。用户明确要求发完简历不要再说话，
     *       而且连发两条（卡片 + 一句话）在 HR 眼里很像机器人。</li>
     *   <li><b>按钮是灰的</b> —— 平台要求「双方回复后可用」。这是业务状态不是故障，
     *       重试没有意义，降级成说一句话，别把这个会话晾死。</li>
     *   <li><b>点了没成功</b> —— 可能慢、可能改版，进重试链。</li>
     * </ul>
     */
    private Outcome sendResumeOutcome(PlatformAdapter adapter, UnreadThread thread, UnreadTaskEntity task,
                                      ChatMessage last, ConversationEntity conversation, Long conversationId,
                                      ReplyRuleEngine.Decision decision, RunLog runLog) {
        String reason = decision.reason();
        if (resumeAlreadySent(conversationId, thread.title(), runLog)) {
            return resumeAlreadySentOutcome(adapter, thread, task, last, conversation, conversationId,
                    decision, reason, runLog);
        }
        ResumeResult result = adapter.sendResume(thread);

        if (result.status() == ResumeResult.Status.SENT) {
            if (conversation != null) {
                replyStore.markOwnReply(conversation);
            }
            logAction(conversationId, last, ReplyLogEntity.ACTION_REPLY_RESUME, decision.ruleId(),
                    null, null, ReplyLogEntity.SOURCE_RESUME, null, reason);
            taskStore.markDone(task, last.id(), UnreadTaskStore.Action.REPLIED, null, reason);
            runLog.info("已发出简历（未补文字）| 会话=" + thread.title() + " | 理由=" + reason
                    + " | 凭据=" + result.detail());
            return Outcome.REPLIED;
        }

        if (result.status() == ResumeResult.Status.UNAVAILABLE) {
            String fallback = decision.replyText();
            runLog.warn("简历按钮不可用，降级成文字 | 会话=" + thread.title() + " | 平台原因="
                    + result.detail() + " | 兜底文案=" + (fallback == null ? "（无）" : "「" + fallback + "」"));
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
                return fail(thread, task, UnreadTaskStore.Action.SEND_FAILED, null,
                        "简历按钮不可用，兜底文字也没发出去", runLog);
            }
            if (conversation != null) {
                replyStore.markOwnReply(conversation);
            }
            String note = reason + "；按钮不可用（" + result.detail() + "），改发兜底文案";
            logAction(conversationId, last, ReplyRuleEngine.Action.REPLY.name(), decision.ruleId(),
                    null, fallback, ReplyLogEntity.SOURCE_TEMPLATE, "RESUME_UNAVAILABLE", note);
            taskStore.markDone(task, last.id(), UnreadTaskStore.Action.REPLIED, null, note);
            runLog.info("已发送（简历不可用降级）| 会话=" + thread.title() + " | 内容=「" + fallback
                    + "」 | 理由=" + note);
            return Outcome.REPLIED;
        }

        return fail(thread, task, UnreadTaskStore.Action.SEND_FAILED, null,
                "发简历失败：" + reason + " | " + result.detail(), runLog);
    }

    /**
     * 「这个会话已经发过简历」时的收尾：不发动作，有兜底文案就说一句话。
     *
     * <p>为什么说一句话而不是静默：对方可能真的还在等（隔天又提了一次），静默等于这条消息
     * 永远没有回应。兜底文案是人工审核过的，比「不回」安全 —— 与「按钮不可用」同一套处置。
     */
    private Outcome resumeAlreadySentOutcome(PlatformAdapter adapter, UnreadThread thread,
                                             UnreadTaskEntity task, ChatMessage last,
                                             ConversationEntity conversation, Long conversationId,
                                             ReplyRuleEngine.Decision decision, String reason,
                                             RunLog runLog) {
        String fallback = decision.replyText();
        if (fallback == null || fallback.isBlank()) {
            logAction(conversationId, last, UnreadTaskStore.Action.AI_SKIPPED, decision.ruleId(), null, null,
                    ReplyLogEntity.SOURCE_RESUME, "RESUME_ALREADY_SENT",
                    reason + "；该会话已发过简历，且没有兜底文案");
            taskStore.markDone(task, last.id(), UnreadTaskStore.Action.AI_SKIPPED,
                    "RESUME_ALREADY_SENT", reason + "；该会话已发过简历");
            return Outcome.SKIPPED;
        }
        boolean sent = adapter.sendText(thread, fallback);
        if (!sent) {
            return fail(thread, task, UnreadTaskStore.Action.SEND_FAILED, null,
                    "该会话已发过简历，兜底文字也没发出去", runLog);
        }
        if (conversation != null) {
            replyStore.markOwnReply(conversation);
        }
        String note = reason + "；该会话已发过简历，不再重复发送，改发兜底文案";
        logAction(conversationId, last, ReplyRuleEngine.Action.REPLY.name(), decision.ruleId(),
                null, fallback, ReplyLogEntity.SOURCE_TEMPLATE, "RESUME_ALREADY_SENT", note);
        taskStore.markDone(task, last.id(), UnreadTaskStore.Action.REPLIED, "RESUME_ALREADY_SENT", note);
        runLog.info("已发送（该会话已发过简历，不再重复发）| 会话=" + thread.title()
                + " | 内容=「" + fallback + "」");
        return Outcome.REPLIED;
    }

    /**
     * 「同一会话只发一次简历」的唯一判据，两条路径共用。
     *
     * <p>判据是 {@code reply_log} 里已经有一条 {@code REPLY_RESUME}：那一行只在
     * <b>卡片真的出现</b>时才写，失败与降级都不写 —— 所以它挡得住重复发送，
     * 又不会把「上次点了没成功」的重试一起挡掉。
     */
    private boolean resumeAlreadySent(Long conversationId, String title, RunLog runLog) {
        if (conversationId == null || !replyStore.resumeAlreadySent(conversationId)) {
            return false;
        }
        runLog.info("发简历前拦下（该会话已经发过一份）| 会话=" + title);
        return true;
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
                                      ReplyRuleEngine.Decision decision, String ruleText, String error,
                                      RunLog runLog) {
        if (ruleText == null || ruleText.isBlank()) {
            return fail(thread, task, UnreadTaskStore.Action.AI_FAILED, null, error, runLog);
        }
        boolean sent = adapter.sendText(thread, ruleText);
        if (!sent) {
            return fail(thread, task, UnreadTaskStore.Action.SEND_FAILED, null,
                    "AI 失败（" + error + "）后降级模板也发送失败", runLog);
        }
        if (conversation != null) {
            replyStore.markOwnReply(conversation);
        }
        logAction(conversationId, last, ReplyRuleEngine.Action.REPLY.name(), decision.ruleId(),
                null, ruleText, ReplyLogEntity.SOURCE_TEMPLATE, null, "AI 失败，降级用模板");
        taskStore.markDone(task, last.id(), UnreadTaskStore.Action.REPLIED, null, "AI 失败，降级用模板");
        runLog.info("AI 失败，已降级用模板文案 | 会话=" + thread.title() + " | " + error);
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

    /**
     * 记一次失败并排进重试。
     *
     * <p>日志在这里统一写：失败有八条来源（读不到、定位不安全之外的读异常、空回复、
     * 发送未确认、简历按钮不可用且无兜底、降级也发不出去……），逐处补一行必然漏；
     * 而「发了没成功」正是用户最需要看见的那一类（ADR-0008 第 3 条）。
     */
    private Outcome fail(UnreadThread thread, UnreadTaskEntity task, String action,
                         String reasonCode, String error, RunLog runLog) {
        runLog.warn("未读处理失败（" + action + "），已排下次重试 | 会话=" + thread.title() + " | " + error);
        taskStore.markFailed(task, action, reasonCode, error);
        return Outcome.FAILED;
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
