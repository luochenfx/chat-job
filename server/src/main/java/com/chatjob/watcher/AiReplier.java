package com.chatjob.watcher;

import com.chatjob.config.SecretCipher;
import com.chatjob.evaluate.ChatClient;
import com.chatjob.platform.Models.ChatMessage;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.AiConfigEntity;
import com.chatjob.store.entity.ProviderEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 值守回复生成（spec 0002 §3.6）。把「未命中规则」那条路从占位失败换成真正的 AI 回复。
 *
 * <p>三个刻意分开的职责，混在一起就会出问题：
 * <ul>
 *   <li><b>说什么</b>（{@code ai_config.introduce}）是事实素材，天然简历腔；</li>
 *   <li><b>怎么说</b>（{@code ai_config.ai_reply_style}）才是语气约束 ——
 *       拿 introduce 当人设直接喂模型，产出必是「本人具备 X 年经验」这种一眼假的句子；</li>
 *   <li><b>能不能发</b>（{@link ReplyGuard}）是代码判定。风格是软约束，模型可以不遵守，
 *       所以改了 prompt 不等于收紧了出口。</li>
 * </ul>
 *
 * <p>允许模型主动 {@code skip}：宁可这条不回，也不要硬凑一句。但<b>必须给出枚举内的理由</b>，
 * 否则统计面板上「模型在躲」和「接口在坏」会混成一个数字。
 *
 * <p>技术性失败（超时、非 200、JSON 非法、reply 为空）走 {@code error}，不算 skip ——
 * 它要进重试链，而 skip 是终态。
 */
@Slf4j
@Component
public class AiReplier {

    /**
     * 默认表达风格。DB 里存空串时用它兜底，这样用户在控制台清空输入框也不会退化成无约束状态。
     *
     * <p>每一条都对应一个真实失败模式：分点小标题像周报、复述对方像机器人、套话像客服、
     * 感叹号堆叠像营销号。最后一条「不超过 60 字」与 {@link ReplyGuard#MAX_CHARS} 对齐 ——
     * 让模型自己收住，比事后截断体面。
     */
    public static final String DEFAULT_STYLE = """
            像真人聊天一样回话：一两句话，口语化，短句为主。
            不复述对方的话，不总结、不分点、不用小标题。
            不用书面语和套话（如"本人具备""贵公司""期待与您共事"）。
            不堆感叹号，不加表情，不主动提薪资、联系方式、面试时间。
            整条不超过 60 字。
            """;

    /** 全部合法的 {@code skipReason}。枚举外的值一律归一化为 {@link #FALLBACK_SKIP_REASON}。 */
    public static final List<String> SKIP_REASONS = List.of(
            "NO_SUBSTANCE", "SENSITIVE_TOPIC", "ALREADY_HANDLED", "LOW_CONFIDENCE", "HUMAN_REQUIRED");

    /**
     * 模型给出枚举外理由时的落点。选 LOW_CONFIDENCE 而不是新造一个 UNKNOWN：
     * 前者语义就是「拿不准」，后者会让统计多出一个永远为 0 的桶。
     */
    public static final String FALLBACK_SKIP_REASON = "LOW_CONFIDENCE";

    /**
     * 带进 prompt 的历史消息条数。消息区分页 {@code pageSize=20}，8 条足够看出「刚刚在聊什么」，
     * 又不至于把 prompt 撑到几百字。
     */
    public static final int CONTEXT_SIZE = 8;

    /** {@code reason} 的长度上限（spec 3.6.2）。超出截断，避免模型写小作文。 */
    public static final int MAX_NOTE_CHARS = 200;

    private final ConfigStore configStore;
    private final SecretCipher cipher;
    private final ChatClient chatClient;
    private final ObjectMapper json;

    public AiReplier(ConfigStore configStore, SecretCipher cipher, ChatClient chatClient, ObjectMapper json) {
        this.configStore = configStore;
        this.cipher = cipher;
        this.chatClient = chatClient;
        this.json = json;
    }

    /**
     * @param introduce 事实素材（年限、技能、项目），可为空
     * @param style     表达风格，空串 → {@link #DEFAULT_STYLE}
     * @param recent    最近若干条消息（从早到晚，最后一条是刚收到的），会再截断到 {@link #CONTEXT_SIZE}
     * @param ruleName  命中的规则名；未命中时为 {@code null}
     * @param ruleText  命中的文案（作为参考素材，不要求照搬）；未命中时为 {@code null}
     * @param forbidden 禁止提及的词（敏感词表）
     */
    public record Input(String conversation, String introduce, String style, List<ChatMessage> recent,
                        String ruleName, String ruleText, List<String> forbidden) {
    }

    /**
     * @param text       放行时的回复草稿；{@code skip} 或失败时为 {@code null}
     * @param skipReason {@link #SKIP_REASONS} 内的枚举值，仅在 {@code skip} 时有意义
     * @param note       补充说明（≤{@link #MAX_NOTE_CHARS} 字），**禁止复述对方原话**
     * @param error      非空表示技术性失败，调用方应走重试而不是当成「不回」
     */
    public record Reply(String text, boolean skip, String skipReason, String note, String error) {

        public static Reply ok(String text, String note) {
            return new Reply(text, false, null, note == null ? "" : note, null);
        }

        public static Reply skip(String reason, String note) {
            return new Reply(null, true, reason, note, null);
        }

        public static Reply fail(String error) {
            return new Reply(null, false, null, "", error);
        }

        public boolean isFailure() {
            return error != null;
        }
    }

    /**
     * 生成一条回复。所有失败都收敛成 {@link Reply#fail}，不向上抛 ——
     * 值守是附加能力，一次模型故障不该中断整轮扫描。
     */
    public Reply reply(Input input) {
        AiConfigEntity ai = configStore.aiConfig();
        ProviderEntity provider = configStore.activeProvider();
        if (provider == null) {
            return Reply.fail("未配置模型提供商");
        }
        String apiKey = cipher.decrypt(provider.getApiKeyCipher());
        if (apiKey == null || apiKey.isBlank()) {
            return Reply.fail("模型提供商的 API Key 未配置或无法解密");
        }

        String style = input.style() == null || input.style().isBlank() ? DEFAULT_STYLE : input.style();
        String styleSource = input.style() == null || input.style().isBlank() ? "默认" : "自定义";
        List<ChatMessage> recent = tail(input.recent(), CONTEXT_SIZE);
        String prompt = render(input, style, recent);

        // 默认值与上限只走 ConfigStore 那一份：这里若自己写 `== null ? 8`，
        // 改默认值时两处必然漂移，而且绕过 120 秒上限
        int configured = ConfigStore.clampAiReplyTimeoutSeconds(ai.getAiReplyTimeoutSeconds());
        int providerTimeout = provider.getTimeoutSeconds() == null ? 60 : provider.getTimeoutSeconds();
        // 再被提供商超时压一道：模型侧 30 秒断连时，这里写 120 也没用
        int timeout = Math.min(Math.max(1, configured), Math.max(1, providerTimeout));

        long started = System.currentTimeMillis();
        try {
            String body = chatClient.complete(provider, apiKey,
                    "你是求职者本人，在招聘平台上回复 HR。只输出严格 JSON，不要任何额外文字。",
                    prompt, timeout, 0.7);
            Reply reply = parse(body);
            long cost = System.currentTimeMillis() - started;
            log.info("AI 回复：历史 {} 条，参考素材={}，风格={}，耗时 {}ms，{} | 会话={}",
                    recent.size(), input.ruleText() == null ? "无" : "有", styleSource, cost,
                    reply.skip() ? "主动不回(" + reply.skipReason() + ")"
                            : (reply.isFailure() ? "失败(" + reply.error() + ")"
                            : "产出 " + codePoints(reply.text()) + " 字"),
                    input.conversation());
            return reply;
        } catch (Exception e) {
            log.warn("AI 回复失败（进重试链）| 会话={} | {}: {}",
                    input.conversation(), e.getClass().getSimpleName(), e.getMessage());
            return Reply.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * prompt 组装。用占位符/字符串拼接而不是 {@code String.format}：
     * HR 原话里出现一个百分号就会抛格式化异常，然后这条消息被记成失败 —— 排查起来极难。
     */
    String render(Input input, String style, List<ChatMessage> recent) {
        StringBuilder sb = new StringBuilder();
        sb.append("你正在以求职者本人的身份，在招聘平台上回复 HR 的消息。\n\n");

        String introduce = input.introduce() == null ? "" : input.introduce().strip();
        if (!introduce.isEmpty()) {
            sb.append("关于我（事实素材，需要时再提，不要整段复述）：\n").append(introduce).append("\n\n");
        }

        sb.append("怎么说话（必须遵守）：\n").append(style.strip()).append("\n\n");

        // 事实底线：这一条是真实事故换来的。上一版模板对要简历的 HR 回过
        // 「简历已在沟通里发过」，而实际上一次都没发过 —— 对方当场就能识破。
        sb.append("""
                事实底线（比风格重要，违反会让求职者当场翻车）：
                - 只能说你确实做过、或明确打算去做的事。没发过的简历不要说「已经发过」，
                  没约定的面试不要说「已确认」，没聊过的项目不要说「之前和您聊过」。
                - 不编造年限、公司、项目、证书、薪资数字。
                - 拿不准的事情就说不确定，或者说稍后确认，不要用一句顺话糊过去。
                """);
        sb.append('\n');

        sb.append("最近的对话（从早到晚，最后一条是对方刚发的）：\n");
        if (recent.isEmpty()) {
            sb.append("（读不到历史消息）\n");
        } else {
            for (ChatMessage m : recent) {
                String who = m.fromSelf() ? "我" : "HR";
                String text = m.text() == null || m.text().isBlank() ? "[非文字消息]" : m.text().strip();
                sb.append(who).append("：").append(text).append('\n');
            }
        }
        sb.append('\n');

        if (input.ruleText() != null && !input.ruleText().isBlank()) {
            sb.append("这条消息可能属于「").append(nvl(input.ruleName())).append("」这类情况，")
                    .append("我们准备好的说法是：「").append(input.ruleText().strip()).append("」。\n")
                    .append("参考它的意思即可，不要说成一模一样，更不要逐字照搬。\n\n");
        }

        List<String> forbidden = input.forbidden() == null ? List.of() : input.forbidden();
        if (!forbidden.isEmpty()) {
            sb.append("不能提的内容（一个词都不要出现）：")
                    .append(String.join("、", forbidden)).append("\n\n");
        }

        sb.append("""
                输出要求：
                1. 只输出 JSON，不要任何解释性文字。
                2. 该回就回：{"reply":"...","skip":false,"skipReason":null,"reason":"为什么这么回，不超过 20 字"}
                3. 不该回就跳过：skip 设为 true，reply 置空，并在 reason 里用一句不超过 20 字的话说明。
                4. skipReason 只能是这五个之一：NO_SUBSTANCE / SENSITIVE_TOPIC / ALREADY_HANDLED / LOW_CONFIDENCE / HUMAN_REQUIRED。
                5. reply 不超过 60 字，一句话为主，不要分点、不要小标题、不要复述对方的话。
                6. reason 无论 skip 与否都要填：它会被记进留痕表，用来回答「这句为什么这么回」。
                """);
        return sb.toString();
    }

    Reply parse(String rawContent) {
        String content = ChatClient.extractJson(rawContent);
        JsonNode node;
        try {
            node = json.readTree(content);
        } catch (Exception e) {
            return Reply.fail("AI 返回不是合法 JSON: " + ChatClient.truncate(content));
        }
        boolean skip = node.path("skip").asBoolean(false);
        String note = clip(node.path("reason").asText(""), MAX_NOTE_CHARS);
        if (skip) {
            return Reply.skip(normalizeSkipReason(node.path("skipReason").asText("")), note);
        }
        String text = node.path("reply").asText("").strip();
        if (text.isEmpty()) {
            // 既没 skip 又没内容：这是模型没遵守契约，算技术失败而不是「不想回」
            return Reply.fail("模型既没有给 reply 也没有 skip");
        }
        return Reply.ok(text, note);
    }

    /**
     * 枚举外的理由归一化为 {@link #FALLBACK_SKIP_REASON} 并告警。
     *
     * <p>模型爱自由发挥（"NOT_APPLICABLE"、"no_reply" 都见过），不能让它的即兴措辞
     * 污染统计口径 —— 否则面板上会冒出一堆只出现一次的原因值。
     */
    static String normalizeSkipReason(String raw) {
        String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        if (SKIP_REASONS.contains(value)) {
            return value;
        }
        log.warn("模型给出了枚举外的 skipReason「{}」，按 {} 处理", raw, FALLBACK_SKIP_REASON);
        return FALLBACK_SKIP_REASON;
    }

    /** 取最后 n 条；不足则全取 */
    static List<ChatMessage> tail(List<ChatMessage> messages, int n) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, messages.size() - Math.max(1, n));
        return new ArrayList<>(messages.subList(from, messages.size()));
    }

    private static String clip(String text, int max) {
        String t = text == null ? "" : text.strip();
        return codePoints(t) <= max ? t : t.substring(0, Math.min(t.length(), max)) + "…";
    }

    private static int codePoints(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    private static String nvl(String s) {
        return s == null ? "未分类" : s;
    }
}
