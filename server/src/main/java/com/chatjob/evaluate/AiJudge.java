package com.chatjob.evaluate;

import com.chatjob.config.SecretCipher;
import com.chatjob.platform.Models.JobDetail;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.AiConfigEntity;
import com.chatjob.store.entity.ProviderEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;

/**
 * AI 投递判定：一次调用同时产出「投不投」与「招呼语」。
 *
 * <p>四个刻意的设计：
 * <ul>
 *   <li>只要一次调用。两次调用（先判定再写文案）会让成本翻倍，而且第二次失败时
 *       你已经知道该投了却发不出话。</li>
 *   <li>strict JSON（{@code response_format: json_object}），原始响应落库便于复盘。
 *       参考实现靠字符串里有没有 "false" 判断，误判率不可控。</li>
 *   <li>连接超时与读超时<b>都</b>要设。只设 connectTimeout 时，服务端不回包可以无限等。</li>
 *   <li>失败即跳过（fail-closed）。错投一个不匹配岗位的代价 > 漏投一个。</li>
 * </ul>
 */
@Slf4j
@Component
public class AiJudge {

    private final ConfigStore configStore;
    private final SecretCipher cipher;
    private final ObjectMapper json;

    public AiJudge(ConfigStore configStore, SecretCipher cipher, ObjectMapper json) {
        this.configStore = configStore;
        this.cipher = cipher;
        this.json = json;
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
        int effective = Math.min(timeout, providerTimeout);

        try {
            String body = callChat(provider, apiKey, prompt, effective);
            return parse(body);
        } catch (Exception e) {
            log.warn("AI 判定失败（该岗位跳过）: {}", e.getMessage());
            return Verdict.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
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

    private String callChat(ProviderEntity provider, String apiKey, String prompt, int timeoutSeconds)
            throws Exception {
        String endpoint = chatEndpoint(provider.getBaseUrl());
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, timeoutSeconds)))
                .build();

        ObjectNode payload = json.createObjectNode();
        payload.put("model", provider.getModel());
        payload.put("temperature", 0.3);
        ObjectNode responseFormat = payload.putObject("response_format");
        responseFormat.put("type", "json_object");
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system")
                .put("content", "你是一名求职顾问，只输出严格 JSON，不要任何额外文字。");
        messages.addObject().put("role", "user").put("content", prompt);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                // 这个 timeout 同时覆盖连接后的读取阶段 —— 只设 connectTimeout 不够
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        applyExtraHeaders(builder, provider.getExtraHeaders());

        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
        JsonNode root = json.readTree(response.body());
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new IllegalStateException("模型返回内容为空: " + truncate(response.body()));
        }
        return content;
    }

    private void applyExtraHeaders(HttpRequest.Builder builder, String extraHeadersJson) {
        if (extraHeadersJson == null || extraHeadersJson.isBlank()) {
            return;
        }
        try {
            JsonNode node = json.readTree(extraHeadersJson);
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (entry.getKey() != null && !entry.getKey().isBlank()) {
                    builder.header(entry.getKey(), entry.getValue().asText());
                }
            }
        } catch (Exception e) {
            log.warn("自定义请求头解析失败，已忽略: {}", e.getMessage());
        }
    }

    Verdict parse(String rawContent) {
        String content = extractJson(rawContent);
        try {
            JsonNode node = json.readTree(content);
            boolean shouldApply = node.path("shouldApply").asBoolean(false);
            int score = node.path("score").asInt(0);
            String reason = node.path("reason").asText("");
            String greeting = node.path("greeting").asText("");
            if (shouldApply && greeting.isBlank()) {
                return new Verdict(false, score, reason, "", rawContent, "判定通过但没给招呼语");
            }
            return new Verdict(shouldApply, score, reason, greeting, rawContent, null);
        } catch (Exception e) {
            return Verdict.fail("AI 返回不是合法 JSON: " + truncate(content));
        }
    }

    /** 模型偶尔会用 ```json 包裹，或前后带一句话 */
    private static String extractJson(String content) {
        if (content == null) {
            return "";
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content.trim();
    }

    static String chatEndpoint(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        if (normalized.contains("/v1")) {
            return normalized + "/chat/completions";
        }
        return normalized + "/v1/chat/completions";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
