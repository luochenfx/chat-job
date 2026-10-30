package com.chatjob.watcher;

import com.chatjob.platform.Models.ChatMessage;
import com.chatjob.platform.Models.ResumeResult;
import com.chatjob.platform.Models.UnreadThread;
import com.chatjob.platform.PlatformAdapter;
import com.chatjob.platform.ThreadTargetUnsafeException;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.ConversationEntity;
import com.chatjob.store.entity.ReplyLogEntity;
import com.chatjob.store.entity.UnreadTaskEntity;
import com.chatjob.store.mapper.UnreadTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 值守编排（{@code sweepWithTasks}）的单测。
 *
 * <p>这一层之前完全没有测试：{@code UnreadTaskStoreTest} 只管状态机，{@code DigestTest}
 * 只管摘要，两者之间的「先重试还是先扫新」「退避窗口里要不要再点开」「定位不安全该不该
 * 继续重试」全靠读代码确认。而这些恰恰是最容易在后续改动里被悄悄破坏的部分。
 *
 * <p>用 hand-written fake（不引 mock 框架，与项目其余单测一致）。{@code ReplyStore} /
 * {@code ConfigStore} 不是 final，直接继承覆盖 —— 比给五个 mapper 各写一个 fake
 * 少几百行，也不会因为 fake 的 SQL 语义失真而得出错误结论。
 *
 * <p><b>边界</b>：这里验证编排顺序与状态迁移，不验证 Playwright 行为。
 * 后者由 {@code tools/verify-p1.mjs}（真实页面只读）与 {@code tools/verify-p0.mjs} 覆盖。
 */
class UnreadWatcherTest {

    private static final String PLATFORM = "boss";

    // ------------------------------------------------------------------ 用例

    @Test
    void 关闭态不碰任务表也不落任务() {
        Fixture f = new Fixture(false);
        f.adapter.unread.add(UnreadThread.of("张三", "张三", "你还在看机会吗", 1));

        UnreadWatcher.SweepResult r = f.watcher.sweep(f.adapter, PLATFORM, false, false);

        // 关闭态与改造前逐行一致：读完就发，不建任务
        assertThat(f.mapper.rows).isEmpty();
        assertThat(f.adapter.readCalls).containsExactly("read:张三");
        assertThat(r.threads()).isEqualTo(1);
        // 未命中规则 → 关闭态只是计数，不建任务（UNMATCHED 不落库是既有行为）
        assertThat(r.unmatched()).isEqualTo(1);
    }

    @Test
    void 打开态先落任务再点开会话() {
        Fixture f = new Fixture(true);
        f.adapter.unread.add(UnreadThread.of("张三", "张三", "你好", 2));

        f.watcher.sweep(f.adapter, PLATFORM, false, false);

        // 点开之前任务必须已经在库里：点开即清红点，顺序错了这条消息就再也捞不回来
        assertThat(f.adapter.events).containsSubsequence("task→张三", "read:张三");
        assertThat(f.mapper.rows).hasSize(1);
        assertThat(f.mapper.rows.get(0).getPlatformConversationId()).isEqualTo("张三");
    }

    @Test
    void 未命中时由AI生成并发送() {
        Fixture f = new Fixture(true);
        f.replier.reply = AiReplier.Reply.ok("还招的，您这边主要看哪块？", "");
        f.adapter.unread.add(UnreadThread.of("张三", "张三", "这个岗位还招人吗", 1));

        UnreadWatcher.SweepResult r = f.watcher.sweep(f.adapter, PLATFORM, false, false);

        assertThat(f.adapter.sendCalls).containsExactly("send:张三:还招的，您这边主要看哪块？");
        UnreadTaskEntity task = f.mapper.rows.get(0);
        assertThat(task.getStatus()).isEqualTo(UnreadTaskStore.Status.DONE.name());
        assertThat(task.getLastAction()).isEqualTo(UnreadTaskStore.Action.REPLIED);
        assertThat(r.replied()).isEqualTo(1);
        // 未命中时不该带参考素材
        assertThat(f.replier.inputs).hasSize(1);
        assertThat(f.replier.inputs.get(0).ruleText()).isNull();
        assertThat(f.replyStore.logs.get(0).getReplySource()).isEqualTo(ReplyLogEntity.SOURCE_AI);
    }

    @Test
    void 命中规则时模板降级为参考素材而不是直接发() {
        Fixture f = new Fixture(true);
        f.replyStore.engine = engineWith("简历", "方便发一份简历给您");
        f.replier.reply = AiReplier.Reply.ok("可以的，我整理下就发您", "");
        f.adapter.messages = List.of(new ChatMessage("mid-1", "可以发份简历吗", false, false));
        f.adapter.unread.add(UnreadThread.of("李四", "李四", "可以发份简历吗", 1));

        f.watcher.sweep(f.adapter, PLATFORM, false, false);

        // 打开态的关键差异：命中规则也不能直接甩模板，否则 40 个会话回的全是同一句
        assertThat(f.adapter.sendCalls).containsExactly("send:李四:可以的，我整理下就发您");
        assertThat(f.replier.inputs.get(0).ruleText()).isEqualTo("方便发一份简历给您");
        assertThat(f.replier.inputs.get(0).ruleName()).isNotNull();
    }

    @Test
    void AI主动不回时记枚举原因并收尾为DONE() {
        Fixture f = new Fixture(true);
        f.replier.reply = AiReplier.Reply.skip("NO_SUBSTANCE", "只有表情");
        f.adapter.messages = List.of(new ChatMessage("mid-1", "[图片]", false, true));
        f.adapter.unread.add(UnreadThread.of("王五", "王五", "[图片]", 1));

        UnreadWatcher.SweepResult r = f.watcher.sweep(f.adapter, PLATFORM, false, false);

        UnreadTaskEntity task = f.mapper.rows.get(0);
        assertThat(task.getStatus()).isEqualTo(UnreadTaskStore.Status.DONE.name());
        assertThat(task.getLastAction()).isEqualTo(UnreadTaskStore.Action.AI_SKIPPED);
        assertThat(task.getLastReasonCode()).isEqualTo("NO_SUBSTANCE");
        // skip 是终态：不发送、不重试
        assertThat(f.adapter.sendCalls).isEmpty();
        assertThat(f.replier.inputs).hasSize(1);
        assertThat(f.replyStore.logs.get(0).getAction()).isEqualTo(UnreadTaskStore.Action.AI_SKIPPED);
        assertThat(f.replyStore.logs.get(0).getReasonCode()).isEqualTo("NO_SUBSTANCE");
        assertThat(r.replied()).isZero();
    }

    @Test
    void AI技术失败且未命中时进重试链() {
        Fixture f = new Fixture(true);
        f.replier.reply = AiReplier.Reply.fail("ReadTimeoutException: request timed out");
        f.adapter.unread.add(UnreadThread.of("张三", "张三", "这个岗位还招人吗", 1));

        UnreadWatcher.SweepResult r = f.watcher.sweep(f.adapter, PLATFORM, false, false);

        UnreadTaskEntity task = f.mapper.rows.get(0);
        assertThat(task.getStatus()).isEqualTo(UnreadTaskStore.Status.FAILED.name());
        assertThat(task.getLastAction()).isEqualTo(UnreadTaskStore.Action.AI_FAILED);
        assertThat(task.getNextRetryAt()).isNotNull();
        assertThat(f.adapter.sendCalls).isEmpty();
        assertThat(r.failed()).isEqualTo(1);
    }

    @Test
    void AI技术失败但命中规则时降级用模板() {
        Fixture f = new Fixture(true);
        f.replyStore.engine = engineWith("简历", "方便发一份简历给您");
        f.replier.reply = AiReplier.Reply.fail("HTTP 500");
        f.adapter.messages = List.of(new ChatMessage("mid-1", "可以发份简历吗", false, false));
        f.adapter.unread.add(UnreadThread.of("李四", "李四", "可以发份简历吗", 1));

        UnreadWatcher.SweepResult r = f.watcher.sweep(f.adapter, PLATFORM, false, false);

        // 模板是人工审核过的，AI 挂了也不能比改造前更差
        assertThat(f.adapter.sendCalls).containsExactly("send:李四:方便发一份简历给您");
        assertThat(f.mapper.rows.get(0).getStatus()).isEqualTo(UnreadTaskStore.Status.DONE.name());
        assertThat(f.replyStore.logs.get(0).getReplySource()).isEqualTo(ReplyLogEntity.SOURCE_TEMPLATE);
        assertThat(r.replied()).isEqualTo(1);
    }

    @Test
    void 出站校验拦截时整条不发且不重试() {
        Fixture f = new Fixture(true);
        f.replier.reply = AiReplier.Reply.ok("可以加我微信 13800138000 详聊", "");
        f.adapter.unread.add(UnreadThread.of("张三", "张三", "方便聊聊吗", 1));

        f.watcher.sweep(f.adapter, PLATFORM, false, false);

        assertThat(f.adapter.sendCalls).isEmpty();
        UnreadTaskEntity task = f.mapper.rows.get(0);
        assertThat(task.getStatus()).isEqualTo(UnreadTaskStore.Status.DONE.name());
        assertThat(task.getLastAction()).isEqualTo("BLOCKED_CONTACT");
        // 四检是确定性规则，重试只会再拦一次 —— 不能消耗重试预算
        assertThat(task.getAttemptCount()).isZero();
    }

    @Test
    void 单轮AI调用次数用完后退回待办而不是判失败() {
        Fixture f = new Fixture(true);
        f.config.maxPerSweep = 1;
        f.adapter.unread.add(UnreadThread.of("张三", "张三", "第一个问题", 1));
        f.adapter.unread.add(UnreadThread.of("李四", "李四", "第二个问题", 1));

        f.watcher.sweep(f.adapter, PLATFORM, false, false);

        assertThat(f.replier.inputs).hasSize(1);
        UnreadTaskEntity second = f.mapper.rows.stream()
                .filter(t -> "李四".equals(t.getPlatformConversationId()))
                .findFirst().orElseThrow();
        // 超预算是「下一轮再说」，不是「这条失败了」：用 FAILED 表示会让它 3 轮后被 GAVE_UP
        assertThat(second.getStatus()).isEqualTo(UnreadTaskStore.Status.PENDING.name());
        assertThat(second.getAttemptCount()).isZero();
    }

    @Test
    void 发送未确认送达按失败重试而不是标记完成() {
        Fixture f = new Fixture(true);
        f.replyStore.engine = engineWith("简历", "方便发一份简历给您");
        f.adapter.sendOk = false;
        f.adapter.messages = List.of(new ChatMessage("mid-1", "可以发份简历吗", false, false));
        f.adapter.unread.add(UnreadThread.of("李四", "李四", "可以发份简历吗", 1));

        f.watcher.sweep(f.adapter, PLATFORM, false, false);

        UnreadTaskEntity task = f.mapper.rows.get(0);
        assertThat(task.getStatus()).isEqualTo(UnreadTaskStore.Status.FAILED.name());
        assertThat(task.getLastAction()).isEqualTo(UnreadTaskStore.Action.SEND_FAILED);
    }

    @Test
    void 定位不安全直接放弃不再重试() {
        Fixture f = new Fixture(true);
        f.adapter.throwOnRead = new ThreadTargetUnsafeException(
                ThreadTargetUnsafeException.AMBIGUOUS_TITLE, "列表里有 2 个同名会话");
        f.adapter.unread.add(UnreadThread.of("张三", "张三", "你好", 1));

        UnreadWatcher.SweepResult r = f.watcher.sweep(f.adapter, PLATFORM, false, false);

        UnreadTaskEntity task = f.mapper.rows.get(0);
        // 同名/摘要变了是确定性失败，重试一万次也不会变好 —— 必须 GAVE_UP 而不是排重试
        assertThat(task.getStatus()).isEqualTo(UnreadTaskStore.Status.GAVE_UP.name());
        assertThat(task.getLastReasonCode()).isEqualTo(ThreadTargetUnsafeException.AMBIGUOUS_TITLE);
        assertThat(task.getNextRetryAt()).isNull();
        // 放弃不算「本轮失败」，它是已定性的结论
        assertThat(r.failed()).isZero();
    }

    @Test
    void 退避窗口内的任务本轮不再点开() {
        Fixture f = new Fixture(true);
        // 先让它失败一次，才有「退避窗口」可谈
        f.replier.reply = AiReplier.Reply.fail("HTTP 500");
        f.adapter.unread.add(UnreadThread.of("张三", "张三", "你好", 1));

        f.watcher.sweep(f.adapter, PLATFORM, false, false);   // 第一次：落任务 → 失败 → 排重试
        assertThat(f.mapper.rows.get(0).getStatus()).isEqualTo(UnreadTaskStore.Status.FAILED.name());

        f.adapter.events.clear();
        f.watcher.sweep(f.adapter, PLATFORM, false, false);   // 第二次：还在 1 分钟退避窗口内

        // 红点已清，列表里本来就不该再出现；但即使出现，也不能立刻再点开 ——
        // 否则 3 分钟一轮的扫描会把「退避」退化成「一直重试」
        assertThat(f.adapter.events).doesNotContain("read:张三");
        assertThat(f.mapper.rows).hasSize(1);
        assertThat(f.mapper.rows.get(0).getAttemptCount()).isEqualTo(1);
    }

    @Test
    void 先重试到期任务再扫新未读() {
        Fixture f = new Fixture(true);
        // 造一条已在退避、且已到期的任务
        UnreadTaskEntity old = f.taskStore.openOrTouch(PLATFORM, "甲", f.replyStore.upsert(PLATFORM, "甲"), 1, "dig");
        f.taskStore.markFailed(old, UnreadTaskStore.Action.READ_FAILED, null, "读不到");
        f.mapper.row("甲").setNextRetryAt("2000-01-01T00:00:00");

        f.adapter.unread.add(UnreadThread.of("乙", "乙", "你好", 1));
        f.adapter.unread.add(UnreadThread.of("丙", "丙", "你好", 1));

        UnreadWatcher.SweepResult r = f.watcher.sweep(f.adapter, PLATFORM, false, false);

        List<String> reads = f.adapter.events.stream().filter(e -> e.startsWith("read:")).toList();
        // 顺序不能反：先扫新的话，一个失败的会话要等下一轮才可能被重试，而下一轮可能不会来
        assertThat(reads).containsExactly("read:甲", "read:乙", "read:丙");
        assertThat(r.retried()).isEqualTo(1);
    }

    @Test
    void 重试时带上摘要快照供二次校验() {
        Fixture f = new Fixture(true);
        f.taskStore.openOrTouch(PLATFORM, "甲", f.replyStore.upsert(PLATFORM, "甲"), 1, "digest-abc");
        f.mapper.row("甲").setStatus(UnreadTaskStore.Status.FAILED.name());
        f.mapper.row("甲").setAttemptCount(1);
        f.mapper.row("甲").setMaxAttempts(3);
        f.mapper.row("甲").setNextRetryAt("2000-01-01T00:00:00");

        f.watcher.sweep(f.adapter, PLATFORM, false, false);

        // 重试路径只拿得到「会话 id + 摘要」，标题退化为会话 id；
        // 摘要必须原样带过去，否则 3.4 的二次校验形同虚设（重试会发给换了人的会话）
        assertThat(f.adapter.digests).containsExactly("digest-abc");
        assertThat(f.adapter.readTitles).containsExactly("甲");
    }

    @Test
    void 读不到未读数时不落任务也不当成没有未读() {
        Fixture f = new Fixture(true);
        f.adapter.unread.add(UnreadThread.of("张三", "张三", "你好", -1));

        UnreadWatcher.SweepResult r = f.watcher.sweep(f.adapter, PLATFORM, false, false);

        // -1 是「读不到」，不是「没有未读」：既不能落任务，也不能静默当 0
        assertThat(f.mapper.rows).isEmpty();
        assertThat(f.adapter.readCalls).isEmpty();
        assertThat(r.threads()).isEqualTo(1);
    }

    @Test
    void 消息区为空按读失败重试() {
        Fixture f = new Fixture(true);
        f.adapter.emptyMessages = true;
        f.adapter.unread.add(UnreadThread.of("张三", "张三", "你好", 1));

        f.watcher.sweep(f.adapter, PLATFORM, false, false);

        assertThat(f.mapper.rows.get(0).getStatus()).isEqualTo(UnreadTaskStore.Status.FAILED.name());
        assertThat(f.mapper.rows.get(0).getLastAction()).isEqualTo(UnreadTaskStore.Action.READ_FAILED);
    }

    @Test
    void 最后一条是自己发的按跳过收尾不重复回复() {
        Fixture f = new Fixture(true);
        f.adapter.messages = List.of(new ChatMessage("mid-2", "方便发一份简历给您", true, false));
        f.adapter.unread.add(UnreadThread.of("张三", "张三", "你好", 1));

        f.watcher.sweep(f.adapter, PLATFORM, false, false);

        assertThat(f.mapper.rows.get(0).getStatus()).isEqualTo(UnreadTaskStore.Status.DONE.name());
        assertThat(f.mapper.rows.get(0).getLastAction()).isEqualTo(ReplyRuleEngine.Action.SKIP_SELF.name());
        assertThat(f.adapter.sendCalls).isEmpty();
    }

    // ------------------------------------------------------------------ 演练模式

    /**
     * 这一组用例守的是一个真实事故：演练模式只判了投递，没判值守，
     * 于是「半夜用演练模式验证链路」变成真的给 HR 发消息。
     */
    @Test
    void 演练模式一条也不发且三张表一行不写() {
        // 打开态同样走演练支路：演练与 ai_reply_enabled 无关，开着 AI 回复时更不能发
        Fixture f = new Fixture(true);
        f.replyStore.engine = engineWith("简历", "方便发一份简历给您");
        f.adapter.messages = List.of(new ChatMessage("mid-1", "可以发份简历吗", false, false));
        f.adapter.unread.add(UnreadThread.of("李四", "李四", "可以发份简历吗", 1));

        UnreadWatcher.SweepResult r = f.watcher.sweep(f.adapter, PLATFORM, false, true);

        // 1) 不发消息 —— 本用例存在的全部理由
        assertThat(f.adapter.sendCalls).isEmpty();
        // 2) 不写 reply_log：wasProcessed 查的就是它，写进去会让真实运行把这条消息判成重复，永久不回
        assertThat(f.replyStore.logs).isEmpty();
        // 3) 不写冷却时间戳：否则真实运行的前 3 分钟会被自己演练时留下的痕迹挡住
        assertThat(f.replyStore.byId.values())
                .allSatisfy(c -> assertThat(c.getLastOwnReplyAt()).isNull());
        // 4) 不落任务、也不建会话：任务表是真实路径的补偿机制，演练建了却不收尾只会毒化它
        assertThat(f.mapper.rows).isEmpty();
        assertThat(f.replyStore.byKey).isEmpty();
        // 5) replied 恒为 0，「本会回复」单独计数 —— 控制台不能把演练说成已回复
        assertThat(r.replied()).isZero();
        assertThat(r.wouldReply()).isEqualTo(1);
        // 流程本身仍然走完：会话确实被点开读了消息区
        assertThat(f.adapter.readCalls).containsExactly("read:李四");
    }

    @Test
    void 关闭态演练同样不发消息() {
        Fixture f = new Fixture(false);
        f.replyStore.engine = engineWith("简历", "方便发一份简历给您");
        f.adapter.messages = List.of(new ChatMessage("mid-1", "可以发份简历吗", false, false));
        f.adapter.unread.add(UnreadThread.of("李四", "李四", "可以发份简历吗", 1));

        UnreadWatcher.SweepResult r = f.watcher.sweep(f.adapter, PLATFORM, false, true);

        assertThat(f.adapter.sendCalls).isEmpty();
        assertThat(f.mapper.rows).isEmpty();
        assertThat(f.replyStore.logs).isEmpty();
        assertThat(r.wouldReply()).isEqualTo(1);
    }

    @Test
    void 演练模式未命中只计数不排重试() {
        Fixture f = new Fixture(true);
        // P3 起演练也会问 AI（这是唯一能安全看到 AI 输出的地方），所以这里给一个
        // 「AI 主动不回」的结果，验证它同样不计数、不发消息
        f.replier.reply = AiReplier.Reply.skip("NO_SUBSTANCE", "只是寒暄");
        f.adapter.unread.add(UnreadThread.of("张三", "张三", "这个岗位还招人吗", 1));

        UnreadWatcher.SweepResult r = f.watcher.sweep(f.adapter, PLATFORM, false, true);

        // 真实路径这里会落一条 AI_FAILED 任务并排入重试；演练碰任务表就会留下一条
        // 永远等不到真实运行来收尾的僵尸任务
        assertThat(f.mapper.rows).isEmpty();
        assertThat(r.unmatched()).isEqualTo(1);
        assertThat(r.wouldReply()).isZero();
        assertThat(f.adapter.sendCalls).isEmpty();
    }

    @Test
    void 演练模式不因历史回复记录而误判重复或冷却() {
        Fixture f = new Fixture(true);
        f.replyStore.engine = engineWith("简历", "方便发一份简历给您");
        f.adapter.messages = List.of(new ChatMessage("mid-1", "可以发份简历吗", false, false));
        f.adapter.unread.add(UnreadThread.of("李四", "李四", "可以发份简历吗", 1));
        // 造一条「真实运行已经回过这条消息」的痕迹
        Long conversationId = f.replyStore.upsert(PLATFORM, "李四");
        f.replyStore.processed.add(conversationId + ":mid-1");

        UnreadWatcher.SweepResult r = f.watcher.sweep(f.adapter, PLATFORM, false, true);

        // 演练要看的是「规则会怎么判」，不是「库里的旧痕迹怎么拦」——
        // 否则一条已经真实回过的会话在演练里永远显示「未命中」，掩盖规则本身的问题
        assertThat(r.wouldReply()).isEqualTo(1);
        assertThat(f.adapter.sendCalls).isEmpty();
    }

    // ------------------------------------------------------------------ 发简历

    private static ReplyRuleEngine resumeEngine(String keyword, String fallback) {
        return new ReplyRuleEngine(
                List.of(new ReplyRuleEngine.Rule(1L, "简历索取", List.of(keyword),
                        fallback == null ? List.of() : List.of(fallback), true,
                        ReplyRuleEngine.RULE_ACTION_SEND_RESUME)),
                List.of());
    }

    @Test
    void 命中发简历规则时只发简历_不补文字也不花AI调用() {
        Fixture f = new Fixture(true);
        f.replyStore.engine = resumeEngine("简历", "好的，我把整理好的版本发您");
        f.adapter.messages = List.of(new ChatMessage("mid-1", "简历发我一份", false, false));
        f.adapter.unread.add(UnreadThread.of("李四", "李四", "简历发我一份", 1));

        UnreadWatcher.SweepResult r = f.watcher.sweep(f.adapter, PLATFORM, false, false);

        assertThat(f.adapter.resumeCalls).containsExactly("resume:李四");
        // 发完简历什么都不要说：连发两条（卡片 + 一句话）在 HR 眼里很像机器人
        assertThat(f.adapter.sendCalls).isEmpty();
        // 动作是确定性的，没有理由去问模型要不要发 —— 问了它就有能力发给不该发的人
        assertThat(f.replier.inputs).isEmpty();
        assertThat(r.replied()).isEqualTo(1);

        ReplyLogEntity log = f.replyStore.logs.get(0);
        assertThat(log.getAction()).isEqualTo(ReplyLogEntity.ACTION_REPLY_RESUME);
        assertThat(log.getReplySource()).isEqualTo(ReplyLogEntity.SOURCE_RESUME);
        assertThat(log.getReplyText()).isNull();
        // 「为什么这么回」必须留在库里，而不是只在日志里
        assertThat(log.getReasonNote()).contains("发简历").contains("简历索取");
    }

    @Test
    void 简历按钮不可用时降级成兜底文案且留痕说明原因() {
        Fixture f = new Fixture(true);
        f.replyStore.engine = resumeEngine("简历", "好的，我把整理好的版本发您");
        f.adapter.resumeResult = ResumeResult.unavailable("求简历：双方回复后可用");
        f.adapter.messages = List.of(new ChatMessage("mid-1", "简历发我一份", false, false));
        f.adapter.unread.add(UnreadThread.of("李四", "李四", "简历发我一份", 1));

        UnreadWatcher.SweepResult r = f.watcher.sweep(f.adapter, PLATFORM, false, false);

        assertThat(f.adapter.sendCalls).containsExactly("send:李四:好的，我把整理好的版本发您");
        assertThat(r.replied()).isEqualTo(1);
        ReplyLogEntity log = f.replyStore.logs.get(0);
        assertThat(log.getReasonCode()).isEqualTo("RESUME_UNAVAILABLE");
        assertThat(log.getReasonNote()).contains("按钮不可用");
    }

    @Test
    void 按钮不可用且没有兜底文案时不算失败也不刷屏() {
        Fixture f = new Fixture(true);
        f.replyStore.engine = resumeEngine("简历", null);
        f.adapter.resumeResult = ResumeResult.unavailable("求简历：双方回复后可用");
        f.adapter.messages = List.of(new ChatMessage("mid-1", "简历发我一份", false, false));
        f.adapter.unread.add(UnreadThread.of("李四", "李四", "简历发我一份", 1));

        f.watcher.sweep(f.adapter, PLATFORM, false, false);

        // 灰按钮重试一万次还是灰的，不能进重试链
        assertThat(f.adapter.sendCalls).isEmpty();
        assertThat(f.mapper.rows.get(0).getStatus()).isEqualTo(UnreadTaskStore.Status.DONE.name());
        assertThat(f.mapper.rows.get(0).getLastAction()).isEqualTo(UnreadTaskStore.Action.AI_SKIPPED);
    }

    @Test
    void 发简历失败进重试链() {
        Fixture f = new Fixture(true);
        f.replyStore.engine = resumeEngine("简历", "好的，我把整理好的版本发您");
        f.adapter.resumeResult = ResumeResult.failed("已点「发简历」但 20 秒内没等到简历卡片");
        f.adapter.messages = List.of(new ChatMessage("mid-1", "简历发我一份", false, false));
        f.adapter.unread.add(UnreadThread.of("李四", "李四", "简历发我一份", 1));

        UnreadWatcher.SweepResult r = f.watcher.sweep(f.adapter, PLATFORM, false, false);

        UnreadTaskEntity task = f.mapper.rows.get(0);
        // 失败先置 FAILED 并排好下次重试时间（PENDING 只用于「本轮跳过、没消耗重试」）
        assertThat(task.getStatus()).isEqualTo(UnreadTaskStore.Status.FAILED.name());
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getNextRetryAt()).isNotNull();
        assertThat(task.getLastAction()).isEqualTo(UnreadTaskStore.Action.SEND_FAILED);
        assertThat(r.failed()).isEqualTo(1);
        // 没发出去就不该谎报「已回复」
        assertThat(r.replied()).isZero();
    }

    @Test
    void 演练模式会说要发简历但不真发() {
        Fixture f = new Fixture(true);
        f.replyStore.engine = resumeEngine("简历", "好的，我把整理好的版本发您");
        f.adapter.messages = List.of(new ChatMessage("mid-1", "简历发我一份", false, false));
        f.adapter.unread.add(UnreadThread.of("李四", "李四", "简历发我一份", 1));

        UnreadWatcher.SweepResult r = f.watcher.sweep(f.adapter, PLATFORM, false, true);

        assertThat(r.wouldReply()).isEqualTo(1);
        assertThat(f.adapter.resumeCalls).isEmpty();
        assertThat(f.adapter.sendCalls).isEmpty();
    }

    // ------------------------------------------------------------------ 日志留痕

    /**
     * 用户报的第一现场是「进回复流程时一条日志都没有，真发了消息的人在日志里找不到自己」。
     *
     * <p>这类问题不能靠「代码里有 log.info」来证明：日志被配置挡掉、级别不对、logger 取错
     * 都会让它在实际运行时消失。所以这里挂一个 ListAppender 把这一轮真实 emit 的事件收下来，
     * 断言的是「打出来了什么」，不是「写了什么代码」。
     */
    @Test
    void 每个进入回复流程的会话都留下可读日志() {
        Logger logger = (Logger) LoggerFactory.getLogger(UnreadWatcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Fixture f = new Fixture(true);
            f.replyStore.engine = engineWith("简历", "好的，我把整理好的版本发您");
            f.adapter.messages = List.of(new ChatMessage("mid-1", "方便发一份简历吗", false, false));
            f.adapter.unread.add(UnreadThread.of("李四", "李四", "方便发一份简历吗", 1));

            f.watcher.sweep(f.adapter, PLATFORM, false, false);

            List<String> msgs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            // 入口：出了问题人第一件事是翻日志，这里必须能看见「谁进了流程」
            assertThat(msgs).anyMatch(m -> m.contains("进入回复流程") && m.contains("李四"));
            // 判定：为什么这么回，必须能直接读出来，不能只落库
            assertThat(msgs).anyMatch(m -> m.contains("判定") && m.contains("李四"));
            // 结果：发出去了还是没发出去，同样要有一行
            assertThat(msgs).anyMatch(m -> m.contains("已发送") && m.contains("李四"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** 跳过的分支也要说清楚跳过了什么 —— 否则「值守跑了但什么都没发生」依然无法归因。 */
    @Test
    void 跳过时把跳过的原因写进任务而不是留空() {
        Fixture f = new Fixture(true);
        f.adapter.messages = List.of(new ChatMessage("mid-1", "你好", true, false));
        f.adapter.unread.add(UnreadThread.of("张三", "张三", "你好", 1));

        f.watcher.sweep(f.adapter, PLATFORM, false, false);

        UnreadTaskEntity task = f.mapper.rows.get(0);
        assertThat(task.getLastAction()).isEqualTo(ReplyRuleEngine.Action.SKIP_SELF.name());
        // 理由落在 last_error 这个既有字段里（跳过不是错误，但表里没有专门的 note 列，
        // 再为一句说明加一次迁移不划算；人读的话以后统一看 reply_log.reason_note）
        assertThat(task.getLastError()).contains("我方");
    }

    // ------------------------------------------------------------------ 装配

    private static ReplyRuleEngine engineWith(String keyword, String reply) {
        return new ReplyRuleEngine(
                List.of(new ReplyRuleEngine.Rule(1L, "简历", List.of(keyword), List.of(reply), true)),
                List.of());
    }

    /**
     * 把 UnreadWatcher 的四个依赖装起来。
     *
     * <p>「先落任务再点开」这件事必须从**任务表写入**那一刻观测，而不是从建会话观测 ——
     * 所以 fake mapper 的 insert 会把事件打到 adapter 的同一根时间线上，
     * 这样 {@code containsSubsequence} 断言的才是真的顺序。
     */
    private static final class Fixture {
        final FakeMapper mapper = new FakeMapper();
        final FakeAdapter adapter = new FakeAdapter();
        final FakeReplyStore replyStore = new FakeReplyStore(adapter);
        final FakeConfig config;
        final FakeReplier replier = new FakeReplier();
        final UnreadTaskStore taskStore;
        final UnreadWatcher watcher;

        Fixture(boolean aiReplyEnabled) {
            this.config = new FakeConfig(aiReplyEnabled);
            this.mapper.events = adapter.events;
            this.taskStore = new UnreadTaskStore(mapper);
            this.watcher = new UnreadWatcher(replyStore, taskStore, config, replier);
        }
    }

    // ------------------------------------------------------------------ fakes

    /** 只覆盖 UnreadWatcher 用到的那几个方法；mapper 传 null 不影响（不会走到）。 */
    private static class FakeConfig extends ConfigStore {
        private final boolean enabled;
        int maxPerSweep = 5;
        String style = "";
        List<String> toneTerms = List.of();
        boolean toneCheck = true;

        FakeConfig(boolean enabled) {
            super(null, null, null, null, new ObjectMapper());
            this.enabled = enabled;
        }

        @Override
        public boolean aiReplyEnabled() {
            return enabled;
        }

        @Override
        public String aiIntroduce() {
            return "";
        }

        @Override
        public String aiReplyStyle() {
            return style;
        }

        @Override
        public int aiReplyMaxPerSweep() {
            return maxPerSweep;
        }

        @Override
        public List<String> toneTerms() {
            return toneTerms;
        }

        @Override
        public boolean toneCheckEnabled() {
            return toneCheck;
        }
    }

    /**
     * AI 生成器的 fake。真实 {@code AiReplier} 要打网络，单测只关心
     * 「拿到什么输入」和「怎么处理它给出的三类结果」。
     */
    private static class FakeReplier extends AiReplier {
        AiReplier.Reply reply = AiReplier.Reply.ok("方便发个简历给您看看吗", "");
        final List<AiReplier.Input> inputs = new ArrayList<>();

        FakeReplier() {
            super(null, null, null, new ObjectMapper());
        }

        @Override
        public Reply reply(Input input) {
            inputs.add(input);
            return reply;
        }
    }

    private static class FakeReplyStore extends ReplyStore {
        ReplyRuleEngine engine = ReplyRuleEngine.empty();
        final Map<String, ConversationEntity> byKey = new HashMap<>();
        final Map<Long, ConversationEntity> byId = new HashMap<>();
        final Set<String> processed = new HashSet<>();
        final List<ReplyLogEntity> logs = new ArrayList<>();
        private long seq = 0;

        FakeReplyStore(FakeAdapter adapter) {
            super(null, null, null, null, null);
        }

        @Override
        public ReplyRuleEngine engine() {
            return engine;
        }

        @Override
        public ConversationEntity upsertConversation(String platform, String key, Long jobPostingId) {
            ConversationEntity c = byKey.get(key);
            if (c == null) {
                c = new ConversationEntity();
                c.setId(++seq);
                c.setPlatform(platform);
                c.setPlatformConversationId(key);
                byKey.put(key, c);
                byId.put(c.getId(), c);
            }
            return c;
        }

        /** 给用例用的便捷入口：先建会话再拿 id */
        Long upsert(String platform, String key) {
            return upsertConversation(platform, key, null).getId();
        }

        @Override
        public ConversationEntity findById(Long conversationId) {
            return conversationId == null ? null : byId.get(conversationId);
        }

        @Override
        public boolean wasProcessed(Long conversationId, String messageId) {
            return processed.contains(conversationId + ":" + messageId);
        }

        @Override
        public boolean inCooldown(ConversationEntity conversation) {
            return false;
        }

        @Override
        public void markOwnReply(ConversationEntity conversation) {
            conversation.setLastOwnReplyAt(ConfigStore.now());
        }

        @Override
        public void recordHrMessage(ConversationEntity conversation) {
            // 效果采集不参与决策，这里不关心
        }

        @Override
        public void log(ReplyLogEntity entity) {
            logs.add(entity);
        }
    }

    private static final class FakeAdapter implements PlatformAdapter {
        final List<UnreadThread> unread = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> readCalls = new ArrayList<>();
        final List<String> readTitles = new ArrayList<>();
        final List<String> sendCalls = new ArrayList<>();
        final List<String> resumeCalls = new ArrayList<>();
        final List<String> digests = new ArrayList<>();
        List<ChatMessage> messages = List.of(new ChatMessage("mid-1", "你好", false, false));
        boolean emptyMessages = false;
        boolean sendOk = true;
        com.chatjob.platform.Models.ResumeResult resumeResult =
                com.chatjob.platform.Models.ResumeResult.sent("简历卡片已出现");
        @Nullable ThreadTargetUnsafeException throwOnRead;

        @Override
        public String platform() {
            return PLATFORM;
        }

        @Override
        public List<UnreadThread> listUnreadThreads() {
            return unread;
        }

        @Override
        public List<ChatMessage> readMessages(UnreadThread thread) {
            readCalls.add("read:" + thread.title());
            readTitles.add(thread.title());
            digests.add(thread.expectedDigest());
            events.add("read:" + thread.title());
            if (throwOnRead != null) {
                throw throwOnRead;
            }
            return emptyMessages ? List.of() : messages;
        }

        @Override
        public boolean sendText(UnreadThread thread, String text) {
            sendCalls.add("send:" + thread.title() + ":" + text);
            events.add("send:" + thread.title());
            return sendOk;
        }

        @Override
        public com.chatjob.platform.Models.ResumeResult sendResume(UnreadThread thread) {
            resumeCalls.add("resume:" + thread.title());
            events.add("resume:" + thread.title());
            return resumeResult;
        }

        @Override
        public void closeThread() {
        }

        // ---- 以下值守不会调用

        @Override
        public com.chatjob.platform.Models.LoginState probeLogin() {
            return null;
        }

        @Override
        public void openLogin() {
        }

        @Override
        public void openSearch(com.chatjob.platform.Models.SearchQuery query) {
        }

        @Override
        public com.chatjob.platform.Models.ScrollOutcome scrollUntilEnd(
                com.chatjob.platform.Models.ScrollParams params, BooleanSupplier stop) {
            return null;
        }

        @Override
        public int cardCount() {
            return 0;
        }

        @Override
        public com.chatjob.platform.Models.JobDetail fetchDetail(int cardIndex) {
            return null;
        }

        @Override
        public com.chatjob.platform.Models.DeliveryResult deliver(
                com.chatjob.platform.Models.JobDetail job, String greeting,
                com.chatjob.platform.Models.DeliveryOptions options, BooleanSupplier stop) {
            return null;
        }
    }

    /** 与 UnreadTaskStoreTest 同款 fake：返回副本，避免「忘了 update 却以为落库」蒙混过关 */
    private static final class FakeMapper implements UnreadTaskMapper {

        private static final Set<String> OPEN = Set.of("PENDING", "PROCESSING", "FAILED");

        final List<UnreadTaskEntity> rows = new ArrayList<>();
        /** 与 adapter 共用的事件时间线：用来断言「落任务」发生在「点开会话」之前 */
        List<String> events = new ArrayList<>();
        private long seq = 0;

        UnreadTaskEntity row(String key) {
            return rows.stream().filter(r -> key.equals(r.getPlatformConversationId())).findFirst().orElseThrow();
        }

        @Override
        public UnreadTaskEntity findById(Long id) {
            return rows.stream().filter(r -> r.getId().equals(id)).findFirst()
                    .map(UnreadWatcherTest::copy).orElse(null);
        }

        @Override
        public UnreadTaskEntity findOpen(String platform, String key) {
            return rows.stream()
                    .filter(r -> platform.equals(r.getPlatform()) && key.equals(r.getPlatformConversationId()))
                    .filter(r -> OPEN.contains(r.getStatus()))
                    .max(Comparator.comparing(UnreadTaskEntity::getId))
                    .map(UnreadWatcherTest::copy).orElse(null);
        }

        @Override
        public List<UnreadTaskEntity> findDueForRetry(String now, int limit) {
            return rows.stream()
                    .filter(r -> {
                        // 与 UnreadTaskMapper 的 SQL 对齐：FAILED 按退避到期（时间缺失算到期），
                        // PENDING 只认已排到期的那种（手动重试）
                        if ("FAILED".equals(r.getStatus())) {
                            return r.getAttemptCount() < r.getMaxAttempts()
                                    && (r.getNextRetryAt() == null || r.getNextRetryAt().compareTo(now) <= 0);
                        }
                        return "PENDING".equals(r.getStatus())
                                && r.getNextRetryAt() != null
                                && r.getNextRetryAt().compareTo(now) <= 0;
                    })
                    .sorted(Comparator.comparing(
                            r -> r.getNextRetryAt() == null ? r.getCreatedAt() : r.getNextRetryAt()))
                    .limit(limit)
                    .map(UnreadWatcherTest::copy)
                    .toList();
        }

        @Override
        public int insert(UnreadTaskEntity entity) {
            UnreadTaskEntity row = copy(entity);
            row.setId(++seq);
            rows.add(row);
            entity.setId(row.getId());
            events.add("task→" + entity.getPlatformConversationId());
            return 1;
        }

        @Override
        public int update(UnreadTaskEntity entity) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).getId().equals(entity.getId())) {
                    rows.set(i, copy(entity));
                    return 1;
                }
            }
            return 0;
        }
    }

    private static UnreadTaskEntity copy(UnreadTaskEntity source) {
        UnreadTaskEntity target = new UnreadTaskEntity();
        BeanUtils.copyProperties(source, target);
        return target;
    }
}
