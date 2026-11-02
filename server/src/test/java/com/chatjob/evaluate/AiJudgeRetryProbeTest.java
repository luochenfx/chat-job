package com.chatjob.evaluate;

import com.chatjob.config.SecretCipher;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.ProviderEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 HTTP 探针：模型返回残缺 JSON 时，服务到底会怎么走。
 *
 * <p><b>为什么用本地服务顶替真实的 chat/completions。</b>要验的是"收到残缺内容之后
 * 的行为"——补齐、重试一次、失败记录原因。这些与模型说什么无关，而真实的残缺响应
 * 概率约 0.3%（370 次里 1 次），不可能按需复现。本地服务能把指定的响应按顺序发回来，
 * 于是这条路径变成了可重复的回归证据。真实模型那一侧的验收在服务里跑（DRY_RUN 观察
 * 日志与 delivery 记录）。
 *
 * <p><b>走的是真 {@link ChatClient}</b>，不是替身：HTTP 请求、OpenAI 响应包、content
 * 提取、finish_reason 都在真实代码路径上，只有对端换成了本地服务。
 *
 * <p><b>默认不跑</b>（要起 HTTP 服务，且会把测试时间拉长）：
 * {@code mvn test -Dtest=AiJudgeRetryProbeTest -Dchat-job.ai.probe=true}
 */
@EnabledIfSystemProperty(named = "chat-job.ai.probe", matches = "true")
class AiJudgeRetryProbeTest {

    /** 2026-11-02 真实运行里被丢掉的那条内容：缺右括号，尾部多一个 ) */
    private static final String BROKEN = """
            {"shouldApply": true, "score": 72, "reason": "Java及前端Vue匹配，证券外包需注意", \
            "greeting": "您好，6年Java经验，熟悉Spring Boot、Vue与Oracle，关注AI提效，期待沟通证券项目。")""";

    private static final String GOOD = """
            {"shouldApply": true, "score": 88, "reason": "匹配", "greeting": "您好，我有 6 年 Java 经验。"}""";

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> replies = new CopyOnWriteArrayList<>();
    private final List<String> finishReasons = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String content = replies.isEmpty() ? "" : replies.remove(0);
        String finishReason = finishReasons.isEmpty() ? "stop" : finishReasons.remove(0);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode message = mapper.createObjectNode().put("role", "assistant").put("content", content);
        ObjectNode choice = mapper.createObjectNode().put("finish_reason", finishReason).set("message", message);
        String body = mapper.createObjectNode()
                .set("choices", mapper.createArrayNode().add(choice))
                .toString();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private AiJudge judge() {
        ObjectMapper json = new ObjectMapper();
        return new AiJudge((ConfigStore) null, new SecretCipher(), json, new ChatClient(json));
    }

    private ProviderEntity provider() {
        ProviderEntity provider = new ProviderEntity();
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        provider.setModel("local-probe");
        provider.setTimeoutSeconds(5);
        return provider;
    }

    @Test
    void 残缺内容补齐后直接使用_不触发第二次调用() {
        replies.add(BROKEN);

        AiJudge.Verdict verdict = judge().judgeWith(provider(), "key", "prompt", 5);

        assertThat(verdict.isFailure()).as(verdict.error()).isFalse();
        assertThat(verdict.score()).isEqualTo(72);
        assertThat(verdict.greeting()).endsWith("期待沟通证券项目。");
        assertThat(requests).as("补齐已经能救回来，不该再多花一次调用").hasSize(1);
    }

    @Test
    void 断在字符串内时重试一次_第二次正常即通过() {
        replies.addAll(List.of(
                "{\"shouldApply\": true, \"score\": 80, \"greeting\": \"您好，我有 6 年 Java 经",
                GOOD));

        AiJudge.Verdict verdict = judge().judgeWith(provider(), "key", "prompt", 5);

        assertThat(verdict.isFailure()).as(verdict.error()).isFalse();
        assertThat(verdict.score()).isEqualTo(88);
        assertThat(requests).as("第一次断在字符串里，必须重试且只重试一次").hasSize(2);
    }

    @Test
    void 两次都不行即失败_原因里带得出解析问题() {
        replies.addAll(List.of("我觉得这个岗位挺合适的", "还是不合适"));

        AiJudge.Verdict verdict = judge().judgeWith(provider(), "key", "prompt", 5);

        assertThat(verdict.isFailure()).isTrue();
        assertThat(verdict.error()).contains("无法识别");
        assertThat(requests).hasSize(2);
    }

    @Test
    void 输出被截断时内容照样交给解析层处理() {
        replies.add(BROKEN);
        finishReasons.add("length");

        AiJudge.Verdict verdict = judge().judgeWith(provider(), "key", "prompt", 5);

        // finish_reason=length 只是告警（ChatClient 里那行 WARN），不改变解析口径：
        // 能补齐就照用，补不出来就走重试
        assertThat(verdict.isFailure()).as(verdict.error()).isFalse();
        assertThat(requests).hasSize(1);
    }

    @Test
    void 请求体里带上严格JSON与模型名() {
        replies.add(GOOD);

        judge().judgeWith(provider(), "key", "prompt", 5);

        assertThat(requests.get(0)).contains("\"response_format\":{\"type\":\"json_object\"}");
        assertThat(requests.get(0)).contains("\"model\":\"local-probe\"");
    }
}
