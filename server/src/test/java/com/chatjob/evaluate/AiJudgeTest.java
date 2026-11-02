package com.chatjob.evaluate;

import com.chatjob.config.SecretCipher;
import com.chatjob.platform.Models.JobDetail;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.AiConfigEntity;
import com.chatjob.store.entity.ProviderEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只测纯逻辑部分（prompt 渲染 + JSON 解析）。真实 HTTP 调用属于集成测试，
 * 需要有效 key，不放在单测里。
 */
class AiJudgeTest {

    private static AiJudge judge() {
        // 这几个依赖在纯逻辑路径上不会被用到
        ObjectMapper json = new ObjectMapper();
        return new AiJudge((ConfigStore) null, new SecretCipher(), json, new ChatClient(json));
    }

    private static JobDetail job(String description) {
        return new JobDetail("e1", "Java 工程师", "某公司", "10-20K", "北京 · 3-5年 · 本科",
                "3-5年", "本科", description, "张女士", "HR", "本月", "本月活跃",
                "{}", "https://www.zhipin.com/job_detail/e1.html");
    }

    private static AiConfigEntity aiConfig() {
        AiConfigEntity entity = new AiConfigEntity();
        entity.setIntroduce("5 年 Java 后端");
        entity.setJdMaxChars(20);
        return entity;
    }

    @Test
    void 提示词里出现百分号不会抛异常() {
        String template = "岗位 {{jobName}} 要求提升 100% 性能，JD：{{jd}}";
        String rendered = judge().render(template, aiConfig(), job("要求提升 100%"), null);
        // 关键点是"没抛异常且 % 原样保留"，不是被当成格式符
        assertThat(rendered).contains("提升 100%");
        assertThat(rendered).contains("Java 工程师");
    }

    @Test
    void JD按配置截断() {
        String rendered = judge().render("{{jd}}", aiConfig(), job("一二三四五六七八九十一二三四五六七八九十超出部分"), null);
        assertThat(rendered).hasSize(20);
    }

    @Test
    void 占位符全部被替换() {
        String template = "{{introduce}}|{{jobName}}|{{company}}|{{salary}}|{{activeLevel}}|{{blacklist}}";
        String rendered = judge().render(template, aiConfig(), job("jd"), null);
        assertThat(rendered).doesNotContain("{{");
        assertThat(rendered).contains("5 年 Java 后端");
        assertThat(rendered).contains("本月");
    }

    @Test
    void 解析严格JSON() {
        AiJudge.Verdict verdict = judge().parse(
                "{\"shouldApply\": true, \"score\": 85, \"reason\": \"匹配\", \"greeting\": \"您好\"}");
        assertThat(verdict.isFailure()).isFalse();
        assertThat(verdict.shouldApply()).isTrue();
        assertThat(verdict.score()).isEqualTo(85);
        assertThat(verdict.greeting()).isEqualTo("您好");
    }

    @Test
    void 容忍代码块包裹() {
        AiJudge.Verdict verdict = judge().parse(
                "```json\n{\"shouldApply\": false, \"score\": 10, \"reason\": \"方向不符\", \"greeting\": \"\"}\n```");
        assertThat(verdict.isFailure()).isFalse();
        assertThat(verdict.shouldApply()).isFalse();
    }

    @Test
    void 非法JSON视为失败而不是默认通过() {
        AiJudge.Verdict verdict = judge().parse("我觉得这个岗位挺合适的");
        assertThat(verdict.isFailure()).isTrue();
        assertThat(verdict.shouldApply()).isFalse();
    }

    @Test
    void 判定通过但没给招呼语也算失败() {
        AiJudge.Verdict verdict = judge().parse(
                "{\"shouldApply\": true, \"score\": 90, \"reason\": \"匹配\", \"greeting\": \"\"}");
        assertThat(verdict.isFailure()).isTrue();
    }

    @Test
    void 真实坏样本补齐后按判定通过处理() {
        // 2026-11-02 真实运行里被丢掉的那个岗位：72 分、判定要投，结尾是 。") 而不是 。"}
        AiJudge.Verdict verdict = judge().parse(
                "{\"shouldApply\": true, \"score\": 72, \"reason\": \"Java及前端Vue匹配，证券外包需注意\", "
                        + "\"greeting\": \"您好，6年Java经验，熟悉Spring Boot、Vue与Oracle，关注AI提效，期待沟通证券项目。\")");

        assertThat(verdict.isFailure()).as(verdict.error()).isFalse();
        assertThat(verdict.shouldApply()).isTrue();
        assertThat(verdict.score()).isEqualTo(72);
        assertThat(verdict.greeting()).endsWith("期待沟通证券项目。");
        // 落库的原文保持模型实际说的内容，便于复盘"当时到底收到了什么"
        assertThat(verdict.raw()).endsWith("。\")");
    }

    @Test
    void 缺shouldApply视为失败而不是默认不投() {
        AiJudge.Verdict verdict = judge().parse("{\"score\": 80, \"reason\": \"匹配\", \"greeting\": \"您好\"}");

        assertThat(verdict.isFailure()).isTrue();
        assertThat(verdict.error()).contains("shouldApply");
    }

    @Test
    void 首次契约不满足会重试一次() {
        ScriptedChatClient client = new ScriptedChatClient(
                "{\"shouldApply\": true, \"score\": 80, \"greeting\": \"您好，我有 6 年 Java 经",
                GOOD_JSON);

        AiJudge.Verdict verdict = judgeWith(client).judgeWith(new ProviderEntity(), "key", "prompt", 20);

        assertThat(verdict.isFailure()).as(verdict.error()).isFalse();
        assertThat(verdict.shouldApply()).isTrue();
        assertThat(client.calls).isEqualTo(2);
    }

    @Test
    void 两次都不满足契约则失败且只调用两次() {
        ScriptedChatClient client = new ScriptedChatClient("我觉得这个岗位挺合适的", "还是不合适");

        AiJudge.Verdict verdict = judgeWith(client).judgeWith(new ProviderEntity(), "key", "prompt", 20);

        assertThat(verdict.isFailure()).isTrue();
        assertThat(client.calls).isEqualTo(2);
    }

    @Test
    void 传输失败不重试() {
        ScriptedChatClient client = new ScriptedChatClient(new IllegalStateException("HTTP 502"));

        AiJudge.Verdict verdict = judgeWith(client).judgeWith(new ProviderEntity(), "key", "prompt", 20);

        assertThat(verdict.isFailure()).isTrue();
        assertThat(verdict.error()).contains("HTTP 502");
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void 端点拼接不重复v1() {
        assertThat(AiJudge.chatEndpoint("https://api.deepseek.com/v1"))
                .isEqualTo("https://api.deepseek.com/v1/chat/completions");
        assertThat(AiJudge.chatEndpoint("https://api.deepseek.com"))
                .isEqualTo("https://api.deepseek.com/v1/chat/completions");
        assertThat(AiJudge.chatEndpoint("https://api.deepseek.com/v1/"))
                .isEqualTo("https://api.deepseek.com/v1/chat/completions");
        assertThat(AiJudge.chatEndpoint("https://x.com/v1/chat/completions"))
                .isEqualTo("https://x.com/v1/chat/completions");
    }

    private static final String GOOD_JSON =
            "{\"shouldApply\": true, \"score\": 88, \"reason\": \"匹配\", \"greeting\": \"您好，我有 6 年 Java 经验。\"}";

    private static AiJudge judgeWith(ChatClient client) {
        ObjectMapper json = new ObjectMapper();
        return new AiJudge((ConfigStore) null, new SecretCipher(), json, client);
    }

    /**
     * 手写替身（项目禁 mock 框架）：按脚本依次返回内容，或抛出脚本里的异常。
     * 记录调用次数，用来区分"重试了"和"没重试"。
     */
    private static final class ScriptedChatClient extends ChatClient {

        private final Deque<Object> steps = new ArrayDeque<>();
        private int calls;

        private ScriptedChatClient(Object... steps) {
            super(new ObjectMapper());
            this.steps.addAll(List.of(steps));
        }

        @Override
        public String complete(ProviderEntity provider, String apiKey, String systemPrompt, String userPrompt,
                              int timeoutSeconds, double temperature) throws Exception {
            calls++;
            Object step = steps.isEmpty() ? "" : steps.poll();
            if (step instanceof Exception error) {
                throw error;
            }
            return (String) step;
        }
    }
}
