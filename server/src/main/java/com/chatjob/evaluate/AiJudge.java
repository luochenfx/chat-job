package com.chatjob.evaluate;

import com.chatjob.config.SecretCipher;
import com.chatjob.platform.Models.JobDetail;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.AiConfigEntity;
import com.chatjob.store.entity.ProviderEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI 投递判定：一次调用同时产出「投不投」与「招呼语」。
 *
 * <p>五个刻意的设计：
 * <ul>
 *   <li>只要一次调用。两次调用（先判定再写文案）会让成本翻倍，而且第二次失败时
 *       你已经知道该投了却发不出话。</li>
 *   <li>strict JSON（{@code response_format: json_object}），原始响应落库便于复盘。
 *       参考实现靠字符串里有没有 "false" 判断，误判率不可控。</li>
 *   <li>连接超时与读超时<b>都</b>要设。只设 connectTimeout 时，服务端不回包可以无限等。</li>
 *   <li>模型没按契约输出时重试一次（见 {@link #judgeWith}）：模型偶发把 JSON 收错，
 *       而一次坏响应会让一个岗位被跳过 24 小时。</li>
 *   <li>重试仍失败即跳过（fail-closed）。错投一个不匹配岗位的代价 > 漏投一个。</li>
 * </ul>
 */
@Slf4j
@Component
public class AiJudge {

    private static final String SYSTEM_PROMPT = "你是一名求职顾问，只输出严格 JSON，不要任何额外文字。";

    /** 总调用次数上限：首次 + 契约不满足时的一次重试 */
    private static final int MAX_ATTEMPTS = 2;

    private final ConfigStore configStore;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final ChatClient chatClient;

    public AiJudge(ConfigStore configStore, SecretCipher cipher, ObjectMapper json, ChatClient chatClient) {
        this.configStore = configStore;
        this.cipher = cipher;
        this.json = json;
        this.chatClient = chatClient;
    }

    /**
     * @param shouldApply AI 判定结论
     * @param greeting    招呼语；判定不通过时为空
     * @param error       非空表示调用或解析失败，调用方应跳过该岗位
     */
    public record Verdict(boolean shouldApply, int score, String reason, String greeting,
                          String raw, String error) {

        public static Verdict fail(String error) {
            return new Verdict(false, 0, "", "", null, error);
        }

        public boolean isFailure() {
            return error != null;
        }
    }

    public Verdict judge(JobDetail job, BlacklistMatcher blacklist) {
        AiConfigEntity ai = configStore.aiConfig();
        ProviderEntity provider = configStore.activeProvider();
        if (provider == null) {
            return Verdict.fail("未配置模型提供商");
        }
        String apiKey = cipher.decrypt(provider.getApiKeyCipher());
        if (apiKey == null || apiKey.isBlank()) {
            return Verdict.fail("模型提供商的 API Key 未配置或无法解密");
        }

        String prompt = render(ai.getPromptTemplate(), ai, job, blacklist);
        int timeout = ai.getAiTimeoutSeconds() == null ? 20 : ai.getAiTimeoutSeconds();
        int providerTimeout = provider.getTimeoutSeconds() == null ? 60 : provider.getTimeoutSeconds();
        return judgeWith(provider, apiKey, prompt, Math.min(timeout, providerTimeout));
    }

    /**
     * 调模型并把结果解析成判定；模型没按契约输出时再调一次。
     *
     * <p><b>为什么要重试一次</b>：实测 370 次判定里有 1 次模型把 JSON 收错
     * （见 {@link AiJson} 的类注释）。这类事件随模型概率出现，而代价是这个岗位本轮被跳过 ——
     * 它还会占掉一次失败次数，两次之后就转人工。重试的代价是一次额外的模型调用，
     * 只在这种极少数情况下发生。
     *
     * <p><b>传输层失败不重试</b>：模型不可达时再等一个完整的超时只会拖长整轮扫描，
     * 而且大概率第二次也一样。这两种失败在日志里必须能区分开。
     *
     * <p>重试仍失败即 fail-closed：判定不出来就不投（spec 0001 的 C6）。
     */
    Verdict judgeWith(ProviderEntity provider, String apiKey, String prompt, int timeoutSeconds) {
        Verdict last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String body;
            try {
                body = chatClient.complete(provider, apiKey, SYSTEM_PROMPT, prompt, timeoutSeconds, 0.3);
            } catch (Exception e) {
                log.warn("AI 判定失败（该岗位跳过，不重试）: {}", e.getMessage());
                return Verdict.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            last = parse(body);
            if (!last.isFailure()) {
                return last;
            }
            log.warn("AI 判定第 {} 次没按契约返回：{}", attempt, last.error());
        }
        return last;
    }

    /**
     * 用占位符替换渲染 prompt。绝不用 String.format —— JD 里只要出现一个百分号
     * （"性能提升 100%"）就会抛格式化异常，然后静默退回默认文案，排查起来极难。
     */
    String render(String template, AiConfigEntity ai, JobDetail job, BlacklistMatcher blacklist) {
        int limit = ai.getJdMaxChars() == null ? 4000 : ai.getJdMaxChars();
        String jd = job.description() == null ? "" : job.description();
        if (jd.length() > limit) {
            jd = jd.substring(0, limit);
        }
        ActiveLevel level = ActiveLevel.of(job.activeRaw());
        String base = template == null || template.isBlank() ? "" : template;
        return base
                .replace("{{introduce}}", nullToEmpty(ai.getIntroduce()))
                .replace("{{jobName}}", nullToEmpty(job.jobName()))
                .replace("{{company}}", nullToEmpty(job.companyName()))
                .replace("{{salary}}", nullToEmpty(job.salary()))
                .replace("{{activeLevel}}", level.label())
                .replace("{{blacklist}}", blacklist == null ? "无" : blacklist.summaryForAi())
                .replace("{{jd}}", jd);
    }

    Verdict parse(String rawContent) {
        AiJson.Read read = AiJson.read(json, rawContent);
        if (read.isFailure()) {
            // 带上失败原因：只写"无法识别"时，残余信息不足以判断是模型截断、编码问题还是改了契约
            return Verdict.fail("AI 返回的内容无法识别（" + read.error() + "）："
                    + truncate(ChatClient.extractJson(rawContent)));
        }
        if (read.repaired()) {
            log.warn("模型输出末尾不完整，已补齐后解析：{}", truncate(ChatClient.extractJson(rawContent)));
        }
        JsonNode node = read.node();
        // 补齐会丢掉没写完的字段，包括 shouldApply 本身；缺这个字段就无从判断，按失败处理，
        // 而不是让 asBoolean(false) 默认成"不投"——那会把一次坏响应记成一次判定结论
        if (!node.has("shouldApply")) {
            return Verdict.fail("AI 返回的 JSON 缺少 shouldApply：" + truncate(ChatClient.extractJson(rawContent)));
        }
        boolean shouldApply = node.path("shouldApply").asBoolean(false);
        int score = node.path("score").asInt(0);
        String reason = node.path("reason").asText("");
        String greeting = node.path("greeting").asText("");
        if (shouldApply && greeting.isBlank()) {
            return new Verdict(false, score, reason, "", rawContent, "判定通过但没给招呼语");
        }
        return new Verdict(shouldApply, score, reason, greeting, rawContent, null);
    }

    /**
     * 保留这个入口只为「端点拼接」保持可单测：实现已经搬到 {@link ChatClient}，
     * 这里不再复制一份逻辑（两份迟早会分叉，而分叉的表现是 HTTP 404）。
     */
    static String chatEndpoint(String baseUrl) {
        return ChatClient.chatEndpoint(baseUrl);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s) {
        return ChatClient.truncate(s);
    }
}
