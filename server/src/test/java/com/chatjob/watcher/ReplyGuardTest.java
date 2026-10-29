package com.chatjob.watcher;

import com.chatjob.store.ConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站四重校验（spec 0002 §3.7 / §4.3）。
 *
 * <p>四项检查里，长度是「能修就修」，其余三项是「命中即整条不发」—— 所以这里的断言分两类：
 * 放行的必须<b>还能发出</b>（{@code text} 非空），拦截的必须<b>一个字都不留</b>
 * （{@code text} 为 null）。只断言 {@code allowed=false} 是不够的：曾经出现过
 * 「判了不发但返回体里还带着原文」，调用方一个手滑就把它发出去了。
 */
class ReplyGuardTest {

    private static final ReplyGuard.GuardOptions DEFAULTS = ReplyGuard.GuardOptions.defaults();

    private static ReplyGuard.Verdict check(String draft) {
        return ReplyGuard.check(draft, null, DEFAULTS);
    }

    // ---------------------------------------------------------------- 常规六类

    @Test
    void 正常口语放行且原文不变() {
        String draft = "可以的，方便的话把简历发我看看？";

        ReplyGuard.Verdict verdict = check(draft);

        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.text()).isEqualTo(draft);
        assertThat(verdict.rejection()).isEqualTo(ReplyGuard.Rejection.NONE);
    }

    @Test
    void 空串不放行() {
        assertThat(check("").rejection()).isEqualTo(ReplyGuard.Rejection.EMPTY);
        assertThat(check("   ").rejection()).isEqualTo(ReplyGuard.Rejection.EMPTY);
        assertThat(ReplyGuard.check(null, null, DEFAULTS).rejection())
                .isEqualTo(ReplyGuard.Rejection.EMPTY);
        assertThat(check("").text()).isNull();
    }

    @Test
    void 超长且没有句末标点时整条不发() {
        ReplyGuard.Verdict verdict = check("啊".repeat(100));

        assertThat(verdict.rejection()).isEqualTo(ReplyGuard.Rejection.TOO_LONG);
        assertThat(verdict.text()).isNull();
    }

    @Test
    void 超长但有句末标点时截到句号而不是整条丢弃() {
        // 41 字 + 句号，后面还有 40 字：截断后应该还能发
        String draft = "啊".repeat(40) + "。" + "啊".repeat(40);

        ReplyGuard.Verdict verdict = check(draft);

        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.text()).isEqualTo("啊".repeat(40) + "。");
    }

    @Test
    void 截断后仍然超长时整条不发() {
        // 句号在第 71 个字，截到它还是超过 60 —— 不允许「再往前砍一刀凑数」
        String draft = "啊".repeat(70) + "。" + "啊".repeat(5);

        assertThat(check(draft).rejection()).isEqualTo(ReplyGuard.Rejection.TOO_LONG);
    }

    @Test
    void 手机号拦下且不把号码写进归因() {
        ReplyGuard.Verdict verdict = check("我电话 13800138000，随时联系");

        assertThat(verdict.rejection()).isEqualTo(ReplyGuard.Rejection.CONTACT);
        assertThat(verdict.detail()).doesNotContain("13800138000");
    }

    @Test
    void 手机号被空格拆开也要认出来() {
        assertThat(check("138 0013 8000 打给我").rejection())
                .isEqualTo(ReplyGuard.Rejection.CONTACT);
    }

    @Test
    void 微信与变体一律拦下() {
        assertThat(check("方便加个微信吗").rejection()).isEqualTo(ReplyGuard.Rejection.CONTACT);
        assertThat(check("我威信同号").rejection()).isEqualTo(ReplyGuard.Rejection.CONTACT);
        assertThat(check("v我细聊").rejection()).isEqualTo(ReplyGuard.Rejection.CONTACT);
        assertThat(check("发我邮箱吧").rejection()).isEqualTo(ReplyGuard.Rejection.CONTACT);
    }

    @Test
    void 薪资与承诺词拦下() {
        assertThat(check("这个岗位薪资多少").rejection()).isEqualTo(ReplyGuard.Rejection.SENSITIVE);
        assertThat(check("15k/月可以吗").rejection()).isEqualTo(ReplyGuard.Rejection.SENSITIVE);
        assertThat(check("保证明天到岗").rejection()).isEqualTo(ReplyGuard.Rejection.SENSITIVE);
    }

    @Test
    void 敏感词表由调用方传入() {
        ReplyGuard.GuardOptions options = new ReplyGuard.GuardOptions(
                List.of(), true, List.of("外包"));

        ReplyGuard.Verdict verdict = ReplyGuard.check("是外包岗位吗", null, options);

        assertThat(verdict.rejection()).isEqualTo(ReplyGuard.Rejection.SENSITIVE);
        assertThat(verdict.detail()).contains("外包");
    }

    // ---------------------------------------------------------------- AI 味三组

    /** 31 条默认词逐条命中。参数化生成，防止「加了词但没补用例」——spec 4.3 的硬要求。 */
    static Stream<String> defaultToneTerms() {
        return ReplyGuard.DEFAULT_TONE_TERMS.stream();
    }

    @ParameterizedTest(name = "默认词表命中：{0}")
    @MethodSource("defaultToneTerms")
    void 默认词表每一条都能拦下(String term) {
        ReplyGuard.Verdict verdict = check(term + "，方便吗？");

        assertThat(verdict.rejection())
                .as("词表里的「%s」没有生效，等于这条检查对它失效", term)
                .isEqualTo(ReplyGuard.Rejection.TONE);
        assertThat(verdict.detail()).contains(term);
    }

    @Test
    void 默认词表就是三十一条() {
        // 数量本身是规格的一部分（spec 3.7）：少了说明有人删词却没更新文档
        assertThat(ReplyGuard.DEFAULT_TONE_TERMS).hasSize(31);
    }

    @Test
    void 自定义词表整体替换默认() {
        ReplyGuard.GuardOptions custom = new ReplyGuard.GuardOptions(
                List.of("自定义套话"), true, List.of());

        // 旧默认词在新词表下不再命中 —— 这是「整体替换」而不是「追加」的证据
        assertThat(ReplyGuard.check("本人具备三年经验", null, custom).allowed()).isTrue();
        assertThat(ReplyGuard.check("这是一句自定义套话", null, custom).rejection())
                .isEqualTo(ReplyGuard.Rejection.TONE);
    }

    @Test
    void 词表留空时回落到默认而不是关闭检查() {
        ReplyGuard.GuardOptions blank = new ReplyGuard.GuardOptions(List.of(), true, List.of());

        assertThat(blank.effectiveToneTerms()).hasSize(31);
        assertThat(ReplyGuard.check("贵公司还招人吗", null, blank).rejection())
                .isEqualTo(ReplyGuard.Rejection.TONE);
    }

    @Test
    void 关闭AI味检查时套话直接放行() {
        ReplyGuard.GuardOptions off = new ReplyGuard.GuardOptions(List.of(), false, List.of());

        assertThat(ReplyGuard.check("本人具备三年经验，方便聊聊？", null, off).allowed()).isTrue();
    }

    @Test
    void 关闭AI味检查不影响前三检() {
        ReplyGuard.GuardOptions off = new ReplyGuard.GuardOptions(List.of(), false, List.of());

        // 总开关只管第四检：手机号、薪资该拦还是要拦
        assertThat(ReplyGuard.check("加我微信", null, off).rejection())
                .isEqualTo(ReplyGuard.Rejection.CONTACT);
        assertThat(ReplyGuard.check("薪资多少", null, off).rejection())
                .isEqualTo(ReplyGuard.Rejection.SENSITIVE);
    }

    // ---------------------------------------------------------------- 结构规则

    @Test
    void 结构规则固定的四条() {
        assertThat(check("1. 我们这边招人").rejection()).isEqualTo(ReplyGuard.Rejection.TONE);
        assertThat(check("① 先投个简历").rejection()).isEqualTo(ReplyGuard.Rejection.TONE);
        assertThat(check("- 你好").rejection()).isEqualTo(ReplyGuard.Rejection.TONE);
        assertThat(check("**你好**").rejection()).isEqualTo(ReplyGuard.Rejection.TONE);
        assertThat(check("岗位信息：\n我们招后端").rejection()).isEqualTo(ReplyGuard.Rejection.TONE);
    }

    @Test
    void 复述对方开场算机器腔() {
        String incoming = "我们这边是做企业服务的公司";
        String draft = "我们这边是做企业服务的公司，请问你什么时候方便？";

        ReplyGuard.Verdict verdict = ReplyGuard.check(draft, incoming, DEFAULTS);

        assertThat(verdict.rejection()).isEqualTo(ReplyGuard.Rejection.TONE);
        assertThat(verdict.detail()).contains("复述");
    }

    @Test
    void 对方话太短时不做复述判定() {
        // 不足 8 个字没有可比指纹，硬判会把正常应答全杀掉
        ReplyGuard.Verdict verdict = ReplyGuard.check("在的，方便", "在吗", DEFAULTS);

        assertThat(verdict.allowed()).isTrue();
    }

    // ---------------------------------------------------------------- 反例：词表不能过宽

    @Test
    void 常见口语不得被误杀() {
        // 三条反例（spec 4.3）：词表一旦把这几句拦掉，说明它宽到会伤正常回复
        assertThat(check("感谢您的简历，我看一下").allowed())
                .as("「感谢」不在词表里，只拦「感谢您的耐心/关注」这类整句套话")
                .isTrue();
        assertThat(check("您好，方便聊聊吗？").allowed()).isTrue();
        assertThat(check("我把简历发您看看").allowed()).isTrue();
    }

    @Test
    void 已知误杀_我将() {
        // spec 0002 内部有冲突：§3.7 的默认词表含「我将」，§4.3 又把「我将简历发您」
        // 列为「必须通过」的反例，两者不可能同时成立；§「默认词表 31 条比原计划激进」
        // 一条明确说「我将」是留着观察的边界项。
        // 这里的裁决：保留词表（31 条不动），把这句话记为已知误杀，
        // 等真实 BLOCKED_TONE 样本决定删不删 —— 删词是安全的单向操作。
        assertThat(check("我将简历发您").rejection()).isEqualTo(ReplyGuard.Rejection.TONE);
    }

    // ---------------------------------------------------------------- 虚假声明（第五检）

    @ParameterizedTest(name = "声称已发出简历必须拦下：{0}")
    @MethodSource("falseClaims")
    void 声称已经发过简历的整条不发(String draft) {
        ReplyGuard.Verdict verdict = check(draft);

        assertThat(verdict.rejection()).isEqualTo(ReplyGuard.Rejection.FALSE_CLAIM);
        assertThat(verdict.text()).isNull();
        // 这里不校验 detail 是否含原文：命中的片段本身就是这句话，
        // 「不得回显原文」的约束是针对联系方式（手机号/微信号）的，不是针对自述句
        assertThat(verdict.detail()).isNotEmpty();
    }

    static Stream<String> falseClaims() {
        return Stream.of(
                // 真实事故原句：上一版模板对要简历的 HR 就是这么回的，而一次都没发过
                "简历已在沟通里发过，如果您需要更详细的版本我可以再整理一份。",
                "简历已发您了",
                "已经发送附件简历给您",
                "简历我已经投递了");
    }

    @ParameterizedTest(name = "承诺或中性表述必须放行：{0}")
    @MethodSource("notClaims")
    void 将来时和中性的说法不算撒谎(String draft) {
        // 分界线：过去时是事实陈述（没做就是说谎），将来时是承诺（可能兑现、也可能失信，
        // 但那是「说话不算数」，不是「编造已发生的事」）。把「发您」两个字当关键词拦，
        // 正常回复会被杀光。
        assertThat(check(draft).allowed()).isTrue();
    }

    static Stream<String> notClaims() {
        return Stream.of(
                "我整理好就发您",
                "我把简历发您看看",
                "简历已经准备好了，您看怎么方便",
                "方便的话我发一份简历给您");
    }

    // ---------------------------------------------------------------- 词表解析

    @Test
    void 词表解析去空行去空白去重并保序() {
        List<String> terms = ConfigStore.parseToneTerms("  贵公司 \n\n贵司\n  贵公司\n\n");

        assertThat(terms).containsExactly("贵公司", "贵司");
    }

    @Test
    void 词表留空的两种写法都回落默认() {
        assertThat(ConfigStore.parseToneTerms(null)).isEmpty();
        assertThat(ConfigStore.parseToneTerms("   \n \n")).isEmpty();
    }
}
