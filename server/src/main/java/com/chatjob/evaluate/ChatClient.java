package com.chatjob.evaluate;

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
 * 一次 chat/completions 调用。从 {@code AiJudge} 里抽出来，给投递判定与值守回复共用。
 *
 * <p>抽出来而不是复制一份，是因为这里面每条都是踩过坑的：
 * <ul>
 *   <li><b>连接超时与读超时都要设</b>。只设 {@code connectTimeout} 时，服务端接了连接
 *       却不回包可以无限等 —— 表现为整个扫描主循环停住，日志一行不写。</li>
 *   <li><b>strict JSON</b>（{@code response_format: json_object}）。参考实现靠字符串里
 *       有没有 "false" 判断，误判率不可控。</li>
 *   <li><b>端点拼接</b>要能吃下「带 /v1」「带 /chat/completions」「带尾斜杠」三种写法，
 *       否则用户填什么全看运气。</li>
 *   <li>{@code temperature} 由调用方给：判定要稳定（0.3），回复要像人（更高）。</li>
 * </ul>
 *
 * <p>失败一律抛异常，由调用方决定是「跳过这个岗位」还是「这条消息进重试」——
 * 这类决策不该埋在客户端里。
 */
@Slf4j
@Component
public class ChatClient {

    private final ObjectMapper json;

    public ChatClient(ObjectMapper json) {
        this.json = json;
    }

    /**
     * @param timeoutSeconds 既当连接上限（取 min(10, timeout)）也当整体读上限
     * @return 模型返回的 content 原文（可能被 ```json 包裹，由调用方提取）
     */
    public String complete(ProviderEntity provider, String apiKey, String systemPrompt, String userPrompt,
                           int timeoutSeconds, double temperature) throws Exception {
        String endpoint = chatEndpoint(provider.getBaseUrl());
        int timeout = Math.max(1, timeoutSeconds);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, timeout)))
                .build();

        ObjectNode payload = json.createObjectNode();
        payload.put("model", provider.getModel());
        payload.put("temperature", temperature);
        ObjectNode responseFormat = payload.putObject("response_format");
        responseFormat.put("type", "json_object");
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                // 这个 timeout 同时覆盖连接后的读取阶段 —— 只设 connectTimeout 不够
                .timeout(Duration.ofSeconds(timeout))
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

    /** 模型偶尔会用 ```json 包裹，或前后带一句话 */
    public static String extractJson(String content) {
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

    /** 日志与错误信息里的截断：模型响应可能有几十 KB，落日志前必须收口 */
    public static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
