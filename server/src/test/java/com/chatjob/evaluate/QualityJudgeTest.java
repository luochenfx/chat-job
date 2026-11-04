package com.chatjob.evaluate;

import com.chatjob.config.SecretCipher;
import com.chatjob.platform.Models.ChatMessage;
import com.chatjob.store.ConfigStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 岗位质量判断的纯逻辑部分：提示词拼装与 JSON 解析。真实 HTTP 调用不在这里。
 *
 * <p>重点钉住三件事：漏掉一项判断点补 UNKNOWN 而不是整次失败、缺 score 才算失败、
 * 认不出来的 verdict 不能当成「符合」。
 */
class QualityJudgeTest {

    private static final List<String> CRITERIA = List.of("双休", "五险一金", "HR 邀约认真");

    private static QualityJudge judge() {
        ObjectMapper json = new ObjectMapper();
        return new QualityJudge(null, new SecretCipher(), json, new ChatClient(json));
    }

    private static QualityJudge.Snapshot job() {
        return new QualityJudge.Snapshot("Java 工程师", "某公司", "10-20K", "北京", "3-5年", "本科",
                "岗位描述：周末双休，入职即缴五险一金。");
    }

    private static List<ChatMessage> messages() {
        return List.of(
                new ChatMessage("m1", "你好，方便发一份简历吗", false, false),
                new ChatMessage("m2", "好的，稍后发您", true, false),
                new ChatMessage("m3", "简历收到了，下周二方便过来面试吗？地址在望京", false, false));
    }

    @Test
    void 提示词带上岗位信息与对话双方身份() {
        String prompt = judge().render(job(), messages(), CRITERIA, 4000);

        assertThat(prompt).contains("Java 工程师").contains("周末双休");
        assertThat(prompt).contains("HR：你好，方便发一份简历吗");
        // 不加「我」这个前缀，模型分不清哪句是我说的，会把我的话当成 HR 认真沟通的证据
        assertThat(prompt).contains("我：好的，稍后发您");
        assertThat(prompt).contains("1. 双休").contains("2. 五险一金").contains("3. HR 邀约认真");
    }

    @Test
    void 没有岗位信息时明说只看对话() {
        String prompt = judge().render(QualityJudge.Snapshot.empty(), messages(), CRITERIA, 4000);
        assertThat(prompt).contains("没有取到岗位信息");
        // 这时不能把空字符串当岗位描述塞进去，模型会以为「岗位没有任何福利说明」
        assertThat(prompt).doesNotContain("岗位描述：\n\n\n");
    }

    @Test
    void 岗位描述按配置截断() {
        QualityJudge.Snapshot longJob = new QualityJudge.Snapshot("岗位", "公司", "", "", "", "",
                "一二三四五六七八九十超出部分");
        String prompt = judge().render(longJob, messages(), CRITERIA, 10);
        assertThat(prompt).contains("一二三四五六七八九十");
        assertThat(prompt).doesNotContain("超出部分");
    }

    @Test
    void 对话只带最近二十条并写明省略了多少() {
        List<ChatMessage> many = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            many.add(new ChatMessage("m" + i, "第" + i + "条", i % 2 == 0, false));
        }
        String prompt = judge().render(job(), many, CRITERIA, 4000);
        assertThat(prompt).contains("更早的 5 条省略");
        assertThat(prompt).doesNotContain("第1条");
        assertThat(prompt).contains("第25条");
    }

    @Test
    void 按请求顺序返回结论_模型漏掉的那项补看不出() {
        String raw = "{\"score\": 82, \"items\": ["
                + "{\"criterion\": \"五险一金\", \"verdict\": \"YES\", \"evidence\": \"入职即缴\"}],"
                + " \"summary\": \"条件齐\"}";
        QualityJudge.Assessment a = judge().parse(raw, CRITERIA);

        assertThat(a.isFailure()).isFalse();
        assertThat(a.score()).isEqualTo(82);
        assertThat(a.items()).hasSize(3);
        assertThat(a.items().get(0).criterion()).isEqualTo("双休");
        assertThat(a.items().get(0).verdict()).isEqualTo(QualityJudge.Verdict.UNKNOWN);
        assertThat(a.items().get(1).verdict()).isEqualTo(QualityJudge.Verdict.YES);
    }

    @Test
    void 缺分数算失败_缺逐项结论不算() {
        assertThat(judge().parse("{\"summary\": \"还行\"}", CRITERIA).isFailure()).isTrue();
        // 逐项结论全缺也要能落一行：分数与理由仍然有用，而失败会让这段会话下一轮再花一次调用
        QualityJudge.Assessment a = judge().parse("{\"score\": 90}", CRITERIA);
        assertThat(a.isFailure()).isFalse();
        assertThat(a.items()).allMatch(i -> i.verdict() == QualityJudge.Verdict.UNKNOWN);
    }

    @Test
    void 分数越界被收敛到0到100() {
        assertThat(judge().parse("{\"score\": 130}", CRITERIA).score()).isEqualTo(100);
        assertThat(judge().parse("{\"score\": -5}", CRITERIA).score()).isZero();
    }

    @Test
    void 认不出来的结论按看不出处理_不能当成符合() {
        assertThat(QualityJudge.verdictOf("YES")).isEqualTo(QualityJudge.Verdict.YES);
        assertThat(QualityJudge.verdictOf("符合")).isEqualTo(QualityJudge.Verdict.YES);
        assertThat(QualityJudge.verdictOf("no")).isEqualTo(QualityJudge.Verdict.NO);
        assertThat(QualityJudge.verdictOf("大概吧")).isEqualTo(QualityJudge.Verdict.UNKNOWN);
        assertThat(QualityJudge.verdictOf(null)).isEqualTo(QualityJudge.Verdict.UNKNOWN);
    }

    @Test
    void 模型改写了判断点的措辞也能对上() {
        String raw = "{\"score\": 70, \"items\": ["
                + "{\"criterion\": \"是否双休\", \"verdict\": \"YES\", \"evidence\": \"写了双休\"}]}";
        QualityJudge.Assessment a = judge().parse(raw, CRITERIA);
        assertThat(a.items().get(0).verdict()).isEqualTo(QualityJudge.Verdict.YES);
    }
}
