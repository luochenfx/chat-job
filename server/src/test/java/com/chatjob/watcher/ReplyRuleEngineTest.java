package com.chatjob.watcher;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplyRuleEngineTest {

    private static ReplyRuleEngine engine() {
        return new ReplyRuleEngine(
                List.of(
                        new ReplyRuleEngine.Rule(1L, "约面试",
                                List.of("面试", "面谈", "约面"), List.of("方便的话可以约个时间"), true),
                        new ReplyRuleEngine.Rule(2L, "简历索取",
                                List.of("简历", "发一份"), List.of("好的，我整理一份发您"), true),
                        new ReplyRuleEngine.Rule(3L, "礼貌收尾",
                                List.of("谢谢", "收到"), List.of("不客气"), false)),
                List.of("薪资", "工资", "多少钱"));
    }

    @Test
    void 敏感词优先于关键词_整条不回复() {
        // 这条同时命中「薪资」敏感词和某条关键词，必须不回复
        ReplyRuleEngine.Decision decision = engine().decide("你们薪资多少，方便面试吗", false, false, false);
        assertThat(decision.action()).isEqualTo(ReplyRuleEngine.Action.SKIP_SENSITIVE);
        assertThat(decision.replyText()).isNull();
        assertThat(decision.sensitive()).isEqualTo("薪资");
    }

    @Test
    void 命中规则时给出文案() {
        ReplyRuleEngine.Decision decision = engine().decide("方便安排一次面试吗", false, false, false);
        assertThat(decision.action()).isEqualTo(ReplyRuleEngine.Action.REPLY);
        assertThat(decision.ruleId()).isEqualTo(1L);
        assertThat(decision.replyText()).isEqualTo("方便的话可以约个时间");
    }

    @Test
    void 命中多个规则时取关键词更长的那条() {
        // "面试" 与 "发一份" 同时出现，两者长度相同；这里额外构造一条更长的关键词验证选择逻辑
        ReplyRuleEngine custom = new ReplyRuleEngine(
                List.of(
                        new ReplyRuleEngine.Rule(1L, "短", List.of("简历"), List.of("短回复"), true),
                        new ReplyRuleEngine.Rule(2L, "长", List.of("发一份简历"), List.of("长回复"), true)),
                List.of());
        ReplyRuleEngine.Decision decision = custom.decide("麻烦发一份简历过来", false, false, false);
        assertThat(decision.ruleId()).isEqualTo(2L);
        assertThat(decision.replyText()).isEqualTo("长回复");
    }

    @Test
    void 关闭的规则不生效() {
        ReplyRuleEngine.Decision decision = engine().decide("谢谢", false, false, false);
        assertThat(decision.action()).isEqualTo(ReplyRuleEngine.Action.UNMATCHED);
    }

    @Test
    void 自己发的消息直接忽略() {
        ReplyRuleEngine.Decision decision = engine().decide("方便面试吗", true, false, false);
        assertThat(decision.action()).isEqualTo(ReplyRuleEngine.Action.SKIP_SELF);
    }

    @Test
    void 已处理过的消息不重复回复() {
        ReplyRuleEngine.Decision decision = engine().decide("方便面试吗", false, true, false);
        assertThat(decision.action()).isEqualTo(ReplyRuleEngine.Action.SKIP_DUPLICATE);
    }

    @Test
    void 冷却期内命中规则也不回复() {
        ReplyRuleEngine.Decision decision = engine().decide("方便面试吗", false, false, true);
        assertThat(decision.action()).isEqualTo(ReplyRuleEngine.Action.SKIP_THROTTLED);
        assertThat(decision.replyText()).isNull();
    }

    @Test
    void 未命中任何规则时记录未命中() {
        ReplyRuleEngine.Decision decision = engine().decide("我们公司在高新区", false, false, false);
        assertThat(decision.action()).isEqualTo(ReplyRuleEngine.Action.UNMATCHED);
    }

    @Test
    void 空消息不回复() {
        assertThat(engine().decide("  ", false, false, false).action())
                .isEqualTo(ReplyRuleEngine.Action.UNMATCHED);
    }
}
