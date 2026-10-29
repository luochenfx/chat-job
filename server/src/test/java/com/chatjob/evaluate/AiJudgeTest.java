package com.chatjob.evaluate;

import com.chatjob.config.SecretCipher;
import com.chatjob.platform.Models.JobDetail;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.AiConfigEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只测纯逻辑部分（prompt 渲染 + JSON 解析）。真实 HTTP 调用属于集成测试，
 * 需要有效 key，不放在单测里。
 */
class AiJudgeTest {

    private static AiJudge judge() {
        // 这两个依赖在纯逻辑路径上不会被用到
        return new AiJudge((ConfigStore) null, new SecretCipher(), new ObjectMapper());
    }

    private static JobDetail job(String description) {
        return new JobDetail("e1", "Java 工程师", "某公司", "10-20K", "福州 · 3-5年 · 本科",
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
}
