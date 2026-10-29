package com.chatjob.watcher;

import com.chatjob.platform.Models.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 回复器里<b>不依赖网络</b>的那部分：JSON 解析、枚举归一、上下文裁剪、prompt 组装。
 *
 * <p>真实调用不进单测（要有效 key，且结果不确定）。这一层的目的是钉住三条契约：
 * <ul>
 *   <li>「不回」必须有枚举内的理由 —— 模型自由发挥不能让统计口径失真；</li>
 *   <li>「既没 reply 也没 skip」是技术失败，不是「不想回」—— 两者一个进重试、一个是终态；</li>
 *   <li>风格是软约束，但<b>必须真的出现在 prompt 里</b>（否则改 prompt 等于没改）。</li>
 * </ul>
 */
class AiReplierTest {

    private static AiReplier replier() {
        ObjectMapper json = new ObjectMapper();
        // 解析与渲染都不碰这三个依赖
        return new AiReplier(null, null, null, json);
    }

    private static AiReplier.Input input(String style, List<ChatMessage> recent,
                                        String ruleName, String ruleText) {
        return new AiReplier.Input("张三", "5 年 Java", style, recent, ruleName, ruleText, List.of("薪资"));
    }

    // ------------------------------------------------------------------ 解析

    @Test
    void 正常回复被原样取出() {
        AiReplier.Reply r = replier().parse(
                "{\"reply\":\"还招的，您主要看哪块？\",\"skip\":false,\"skipReason\":null,\"reason\":\"\"}");

        assertThat(r.isFailure()).isFalse();
        assertThat(r.skip()).isFalse();
        assertThat(r.text()).isEqualTo("还招的，您主要看哪块？");
    }

    @Test
    void 模型用反引号包裹JSON也能解析() {
        AiReplier.Reply r = replier().parse(
                "```json\n{\"reply\":\"可以的\",\"skip\":false}\n```");

        assertThat(r.text()).isEqualTo("可以的");
    }

    @Test
    void 主动不回时保留枚举原因与说明() {
        AiReplier.Reply r = replier().parse(
                "{\"reply\":\"\",\"skip\":true,\"skipReason\":\"SENSITIVE_TOPIC\",\"reason\":\"问到薪资了\"}");

        assertThat(r.skip()).isTrue();
        assertThat(r.text()).isNull();
        assertThat(r.skipReason()).isEqualTo("SENSITIVE_TOPIC");
        assertThat(r.note()).isEqualTo("问到薪资了");
    }

    @Test
    void 枚举外的跳过原因归一为低置信度() {
        assertThat(AiReplier.normalizeSkipReason("not_applicable")).isEqualTo(AiReplier.FALLBACK_SKIP_REASON);
        assertThat(AiReplier.normalizeSkipReason("")).isEqualTo(AiReplier.FALLBACK_SKIP_REASON);
        assertThat(AiReplier.normalizeSkipReason(null)).isEqualTo(AiReplier.FALLBACK_SKIP_REASON);
        // 大小写与连字符是模型最常写歪的两种，要能救回来
        assertThat(AiReplier.normalizeSkipReason("no_substance")).isEqualTo("NO_SUBSTANCE");
        assertThat(AiReplier.normalizeSkipReason("Human-Required")).isEqualTo("HUMAN_REQUIRED");
    }

    @Test
    void 既不回复也不跳过算技术失败() {
        AiReplier.Reply r = replier().parse("{\"reply\":\"\",\"skip\":false}");

        // 这是模型没遵守契约，不能当成「不想回」—— 后者是终态，前者要重试
        assertThat(r.isFailure()).isTrue();
        assertThat(r.error()).contains("既没有给 reply 也没有 skip");
    }

    @Test
    void 非法JSON算技术失败而不是跳过() {
        AiReplier.Reply r = replier().parse("抱歉，我无法回答");

        assertThat(r.isFailure()).isTrue();
        assertThat(r.skip()).isFalse();
    }

    @Test
    void 跳过说明超长被截断() {
        String longNote = "很长的理由".repeat(80);
        AiReplier.Reply r = replier().parse(
                "{\"reply\":\"\",\"skip\":true,\"skipReason\":\"LOW_CONFIDENCE\",\"reason\":\"" + longNote + "\"}");

        assertThat(r.note()).hasSizeLessThanOrEqualTo(AiReplier.MAX_NOTE_CHARS + 1);
    }

    // ------------------------------------------------------------------ 上下文与 prompt

    @Test
    void 上下文只取最后8条() {
        List<ChatMessage> all = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            all.add(new ChatMessage("m" + i, "第" + i + "条", i % 2 == 1, false));
        }

        List<ChatMessage> tail = AiReplier.tail(all, AiReplier.CONTEXT_SIZE);

        assertThat(tail).hasSize(8);
        assertThat(tail.get(7).text()).isEqualTo("第19条");
    }

    @Test
    void prompt里风格与参考素材都要出现() {
        String prompt = replier().render(
                input("像朋友聊天，别超过两句。", List.of(new ChatMessage("m1", "在吗", false, false)),
                        "简历规则", "方便发一份简历给您"),
                "像朋友聊天，别超过两句。",
                List.of(new ChatMessage("m1", "在吗", false, false)));

        assertThat(prompt).contains("像朋友聊天，别超过两句。");
        assertThat(prompt).contains("简历规则");
        assertThat(prompt).contains("方便发一份简历给您");
        assertThat(prompt).contains("HR：在吗");
        assertThat(prompt).contains("薪资");
        // 输出契约必须写清楚，否则模型会自由发挥
        assertThat(prompt).contains("skipReason");
        assertThat(prompt).contains("NO_SUBSTANCE");
    }

    @Test
    void 未命中规则时不带参考素材也不留空段() {
        String prompt = replier().render(
                input("", List.of(new ChatMessage("m1", "你好", false, false)), null, null),
                AiReplier.DEFAULT_STYLE, List.of(new ChatMessage("m1", "你好", false, false)));

        assertThat(prompt).contains(AiReplier.DEFAULT_STYLE);
        assertThat(prompt).doesNotContain("参考它的意思");
        assertThat(prompt).doesNotContain("未分类");
    }

    @Test
    void 空消息按非文字处理而不是塞空串() {
        String prompt = replier().render(
                input("", List.of(new ChatMessage("m1", "", false, true)), null, null),
                AiReplier.DEFAULT_STYLE, List.of(new ChatMessage("m1", "", false, true)));

        assertThat(prompt).contains("[非文字消息]");
    }
}
