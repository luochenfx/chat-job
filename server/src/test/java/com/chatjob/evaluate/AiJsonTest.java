package com.chatjob.evaluate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只测解析与末尾补齐，不发请求。
 *
 * <p>最早的那条用例（{@link #真实样本_缺右括号且尾部多一个字符_补齐后可解析}）
 * 是 2026-11-02 真实运行里唯一一次「AI 返回的内容无法识别」的原样内容：
 * 一个 72 分、判定要投的岗位，因为少了最后的右括号被整条丢掉。
 */
class AiJsonTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 正常内容_直接解析且不标记补齐() {
        AiJson.Read read = AiJson.read(json,
                "{\"shouldApply\": true, \"score\": 85, \"reason\": \"匹配\", \"greeting\": \"您好\"}");

        assertThat(read.isFailure()).isFalse();
        assertThat(read.repaired()).isFalse();
        assertThat(read.node().path("score").asInt()).isEqualTo(85);
    }

    @Test
    void 真实样本_缺右括号且尾部多一个字符_补齐后可解析() {
        // 原样复制自 delivery 表：结尾是 。") 而不是 。"}
        String sample = "{\"shouldApply\": true, \"score\": 72, \"reason\": \"Java及前端Vue匹配，证券外包需注意\", "
                + "\"greeting\": \"您好，6年Java经验，熟悉Spring Boot、Vue与Oracle，关注AI提效，期待沟通证券项目。\")";

        AiJson.Read read = AiJson.read(json, sample);

        assertThat(read.isFailure()).as("补齐后应可解析，原因：%s", read.error()).isFalse();
        assertThat(read.repaired()).isTrue();
        assertThat(read.node().path("shouldApply").asBoolean()).isTrue();
        assertThat(read.node().path("score").asInt()).isEqualTo(72);
        assertThat(read.node().path("greeting").asText())
                .isEqualTo("您好，6年Java经验，熟悉Spring Boot、Vue与Oracle，关注AI提效，期待沟通证券项目。");
    }

    @Test
    void 尾部悬空逗号_补齐后不残留非法逗号() {
        AiJson.Read read = AiJson.read(json, "{\"shouldApply\": false, \"score\": 10,");

        assertThat(read.isFailure()).as(read.error()).isFalse();
        assertThat(read.repaired()).isTrue();
        assertThat(read.node().path("score").asInt()).isEqualTo(10);
    }

    @Test
    void 嵌套数组未闭合_按逆序补齐() {
        AiJson.Read read = AiJson.read(json, "{\"tags\": [\"java\", \"spring\", {\"k\": 1}]");

        assertThat(read.isFailure()).as(read.error()).isFalse();
        assertThat(read.node().path("tags")).hasSize(3);
    }

    @Test
    void 断在字符串内_不补齐() {
        // 招呼语只写了半句：补上引号就等于把半句话当成完整招呼语发出去
        AiJson.Read read = AiJson.read(json,
                "{\"shouldApply\": true, \"score\": 80, \"reason\": \"匹配\", \"greeting\": \"您好，我有 6 年 Java 经");

        assertThat(read.isFailure()).isTrue();
        assertThat(read.error()).contains("字符串内");
    }

    @Test
    void 键还没有值_不补齐() {
        AiJson.Read read = AiJson.read(json, "{\"shouldApply\": true, \"greeting\":");

        assertThat(read.isFailure()).isTrue();
    }

    @Test
    void 根本不是JSON_失败且原因可读() {
        AiJson.Read read = AiJson.read(json, "我觉得这个岗位挺合适的");

        assertThat(read.isFailure()).isTrue();
        // 原因里不能带上整段原文：模型响应可能有几十 KB，落日志前必须收口
        assertThat(read.error()).isNotBlank();
        assertThat(read.error().length()).isLessThan(300);
    }
}
