package com.chatjob.evaluate;

import com.chatjob.config.SecretCipher;
import com.chatjob.platform.Models.ChatMessage;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.ProviderEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 岗位质量判断（CONTEXT.md「岗位质量」/「判断点」）。
 *
 * <p>与 {@link AiJudge} 是两件事、两个时机：那个回答「我要不要投」，发生在投递之前；
 * 这个回答「这个岗位本身值不值得重点跟进」，发生在对话已经聊起来之后（ADR-0015）。
 * 所以它看得到 HR 说了什么，而 {@link AiJudge} 看不到。
 *
 * <p>三个刻意的差别，都不是疏忽：
 * <ul>
 *   <li><b>逐项给结论，不只给一个总分。</b> 界面要能显示「双休 ✓、五险一金 ✗」，
 *       人才能判断这个分数可不可信；只有分数的话，它进「重点关注」的理由无从解释。</li>
 *   <li><b>某一项缺失不算失败。</b> 模型可能漏掉一两条判断点，此时补一条「看不出」，
 *       而不是整次判失败重来 —— 判断结果只影响展示，没有 fail-closed 的必要。</li>
 *   <li><b>只缺 {@code score} 才重试一次。</b> 没有分数就没有排序依据，那一行存下来没意义。</li>
 * </ul>
 *
 * <p>不做的事：不决定投不投、不改写任何消息、不碰值守任务表。
 */
@Slf4j
@Component
public class QualityJudge {

    private static final String SYSTEM_PROMPT = "你是一名求职顾问，只输出严格 JSON，不要任何额外文字。";

    /** 首次 + 契约不满足时的一次重试，与 {@link AiJudge#judgeWith} 同一口径 */
    private static final int MAX_ATTEMPTS = 2;

    /**
     * 对话最多塞多少条给模型。
     *
     * <p>20 条的由来：一次真实沟通在拿到面试邀约之前平均不到 10 条，20 条足够覆盖
     * 「HR 有没有认真问、有没有给具体信息」；再往上收益很小，而输入长度会线性涨。
     */
    private static final int MAX_CONTEXT_MESSAGES = 20;

    /** 一句话结论的字数上限，截断用 */
    private static final int MAX_SUMMARY_CHARS = 60;

    private final ConfigStore configStore;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final ChatClient chatClient;

    public QualityJudge(ConfigStore configStore, SecretCipher cipher, ObjectMapper json, ChatClient chatClient) {
        this.configStore = configStore;
        this.cipher = cipher;
        this.json = json;
        this.chatClient = chatClient;
    }

    /** 判断点的三档结论。{@code UNKNOWN} 不是「没判断」，是「信息里看不出来」 */
    public enum Verdict {
        YES, NO, UNKNOWN
    }

    /**
     * 一项判断点的结论。
     *
     * @param evidence 一句依据。{@code UNKNOWN} 时说明缺了什么信息
     */
    public record Item(String criterion, Verdict verdict, String evidence) {
    }

    /**
     * 岗位信息快照。取自 {@code job_posting}，反查不到时全为空串 ——
     * 那时仍然能判 HR 维度的判断点，不应该整次跳过。
     */
    public record Snapshot(String jobName, String companyName, String salary,
                           String area, String experience, String degree, String description) {

        public static Snapshot empty() {
            return new Snapshot("", "", "", "", "", "", "");
        }

        public boolean found() {
            return !jobName.isBlank() || !description.isBlank();
        }
    }

    /**
     * @param items 按请求的判断点顺序返回，长度一定等于请求的判断点数
     * @param error 非空表示这次判断失败，调用方不要落库
     */
    public record Assessment(int score, List<Item> items, String summary, String raw, String error) {

        public static Assessment fail(String error) {
            return new Assessment(0, List.of(), "", null, error);
        }

        public boolean isFailure() {
            return error != null;
        }
    }

    public Assessment assess(Snapshot job, List<ChatMessage> messages, List<String> criteria) {
        var ai = configStore.aiConfig();
        ProviderEntity provider = configStore.activeProvider();
        if (provider == null) {
            return Assessment.fail("未配置模型提供商");
        }
        String apiKey = cipher.decrypt(provider.getApiKeyCipher());
        if (apiKey == null || apiKey.isBlank()) {
            return Assessment.fail("模型提供商的 API Key 未配置或无法解密");
        }
        int timeout = ai.getAiTimeoutSeconds() == null ? 20 : ai.getAiTimeoutSeconds();
        int providerTimeout = provider.getTimeoutSeconds() == null ? 60 : provider.getTimeoutSeconds();
        int jdMaxChars = ai.getJdMaxChars() == null ? 4000 : ai.getJdMaxChars();
        return assessWith(provider, apiKey, render(job, messages, criteria, jdMaxChars), criteria,
                Math.min(timeout, providerTimeout));
    }

    Assessment assessWith(ProviderEntity provider, String apiKey, String prompt, List<String> criteria,
                          int timeoutSeconds) {
        Assessment last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String body;
            try {
                // 0.2：这是评价而不是创作，同一段会话换两次问不该给出两个差很多的分数
                body = chatClient.complete(provider, apiKey, SYSTEM_PROMPT, prompt, timeoutSeconds, 0.2);
            } catch (Exception e) {
                // 传输层失败不重试：再等一个完整超时只会拖长本轮值守，而且大概率一样
                log.warn("岗位质量判断失败（不重试）: {}", e.getMessage());
                return Assessment.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            last = parse(body, criteria);
            if (!last.isFailure()) {
                return last;
            }
            log.warn("岗位质量判断第 {} 次没按格式返回：{}", attempt, last.error());
        }
        return last;
    }

    /**
     * 拼提示词。用字符串拼接而不是 {@code String.format}：HR 原话里一个百分号就会抛格式化异常，
     * 然后这次判断被记成失败 —— 排查起来极难（与 {@code AiJudge.render} 同一理由）。
     */
    String render(Snapshot job, List<ChatMessage> messages, List<String> criteria, int jdMaxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("我正在找工作，已经和这个岗位的招聘方聊上了。\n\n");

        sb.append("岗位信息：\n");
        if (job == null || !job.found()) {
            sb.append("（这次没有取到岗位信息，只能根据对话判断；岗位维度的判断点一律写 UNKNOWN）\n");
        } else {
            sb.append("岗位名称：").append(nvl(job.jobName())).append('\n');
            sb.append("公司：").append(nvl(job.companyName())).append('\n');
            sb.append("薪资：").append(nvl(job.salary())).append('\n');
            sb.append("城市：").append(nvl(job.area())).append('\n');
            sb.append("经验要求：").append(nvl(job.experience())).append('\n');
            sb.append("学历要求：").append(nvl(job.degree())).append('\n');
            String jd = nvl(job.description()).strip();
            if (jdMaxChars > 0 && jd.length() > jdMaxChars) {
                jd = jd.substring(0, jdMaxChars);
            }
            sb.append("\n岗位描述：\n").append(jd).append('\n');
        }
        sb.append('\n');

        List<ChatMessage> recent = tail(messages, MAX_CONTEXT_MESSAGES);
        sb.append("对话记录（从早到晚，「我」是求职者本人，「HR」是招聘方）：\n");
        if (recent.isEmpty()) {
            sb.append("（读不到消息）\n");
        } else {
            if (messages.size() > recent.size()) {
                sb.append("（更早的 ").append(messages.size() - recent.size()).append(" 条省略）\n");
            }
            for (ChatMessage m : recent) {
                String who = m.fromSelf() ? "我" : "HR";
                String text = m.text() == null || m.text().isBlank()
                        ? (m.image() ? "[图片]" : "[非文字消息]")
                        : m.text().strip();
                sb.append(who).append("：").append(text).append('\n');
            }
        }
        sb.append('\n');

        sb.append("请逐项判断下面这些判断点：\n");
        for (int i = 0; i < criteria.size(); i++) {
            sb.append(i + 1).append(". ").append(criteria.get(i)).append('\n');
        }
        sb.append('\n');

        sb.append("""
                要求：
                1. 只输出 JSON，不要任何解释性文字。
                2. items 必须覆盖上面每一项判断点，criterion 用原话照抄。
                3. verdict 三选一：YES（符合）、NO（不符合）、UNKNOWN（现有信息里看不出来）。
                   看不出来就写 UNKNOWN，不要猜，也不要用「大概」「应该」硬凑成 YES。
                4. evidence 不超过 30 字：写你依据的是哪句话，或 UNKNOWN 时缺什么信息。
                   「岗位描述写了周末双休」是合格的依据；「感觉不错」不是。
                5. score 是 0-100，这个岗位作为「值得重点跟进的机会」的整体质量。
                   岗位条件（福利、工时、是否外包）与 HR 的沟通质量都要算进去。
                6. summary 不超过 40 字，一句话说明为什么是这个分数。
                """);
        sb.append('\n');
        sb.append("输出格式：{\"score\": 82, \"items\": [{\"criterion\": \"双休\", ")
                .append("\"verdict\": \"YES\", \"evidence\": \"岗位描述写了周末双休\"}], ")
                .append("\"summary\": \"条件齐、HR 问得具体\"}\n");
        return sb.toString();
    }

    Assessment parse(String rawContent, List<String> criteria) {
        AiJson.Read read = AiJson.read(json, rawContent);
        if (read.isFailure()) {
            return Assessment.fail("AI 返回的内容无法识别（" + read.error() + "）："
                    + ChatClient.truncate(ChatClient.extractJson(rawContent)));
        }
        if (read.repaired()) {
            log.warn("岗位质量判断的输出末尾不完整，已补齐后解析：{}",
                    ChatClient.truncate(ChatClient.extractJson(rawContent)));
        }
        JsonNode node = read.node();
        if (!node.has("score")) {
            // 没有分数就没有排序依据，这一行存下来没有意义；逐项结论缺失则不算失败
            return Assessment.fail("AI 返回的 JSON 缺少 score："
                    + ChatClient.truncate(ChatClient.extractJson(rawContent)));
        }
        int score = Math.max(0, Math.min(node.path("score").asInt(0), 100));
        String summary = clip(strip(nvl(node.path("summary").asText(""))), MAX_SUMMARY_CHARS);

        Map<String, Item> byCriterion = new LinkedHashMap<>();
        for (JsonNode item : node.path("items")) {
            String criterion = strip(item.path("criterion").asText(""));
            if (criterion.isEmpty()) {
                continue;
            }
            byCriterion.put(criterion, new Item(criterion,
                    verdictOf(item.path("verdict").asText("")),
                    strip(item.path("evidence").asText(""))));
        }
        // 按请求的顺序回，长度一定等于请求条数：漏掉的补 UNKNOWN。
        // 模型偶尔会把判断点改写一两个字，所以先按原话找，找不到再按包含关系找一次
        List<Item> items = new ArrayList<>();
        for (String criterion : criteria) {
            Item item = byCriterion.get(criterion);
            if (item == null) {
                item = byCriterion.entrySet().stream()
                        .filter(e -> e.getKey().contains(criterion) || criterion.contains(e.getKey()))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(new Item(criterion, Verdict.UNKNOWN, "模型没有给出这一项"));
            }
            items.add(item);
        }
        return new Assessment(score, items, summary, rawContent, null);
    }

    /**
     * 模型给的 verdict 字符串 → 三档枚举。
     *
     * <p>认不出来的按 {@code UNKNOWN} 处理，<b>不</b>按 YES 处理：把「看不懂」当成「符合」
     * 会让一个条件不明的岗位进重点关注，而用户点进去只会看到一句自相矛盾的理由。
     */
    public static Verdict verdictOf(String raw) {
        String v = raw == null ? "" : raw.strip().toUpperCase(java.util.Locale.ROOT);
        return switch (v) {
            case "YES", "TRUE", "符合", "满足" -> Verdict.YES;
            case "NO", "FALSE", "不符合", "不满足" -> Verdict.NO;
            default -> Verdict.UNKNOWN;
        };
    }

    private static List<ChatMessage> tail(List<ChatMessage> messages, int limit) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        if (messages.size() <= limit) {
            return messages;
        }
        return messages.subList(messages.size() - limit, messages.size());
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }

    /** 按字数截断，不补省略号 —— 这句要拼进通知正文，多两个字符没有意义 */
    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
