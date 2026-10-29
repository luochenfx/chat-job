package com.chatjob.watcher;

import com.chatjob.evaluate.TextNormalizer;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 出站四重校验（spec 0002 §3.7）。{@code sendText} 之前的最后一道闸门。
 *
 * <p>四检顺序即优先级：长度 → 联系方式 → 薪资与承诺 → AI 味。长度排第一是因为它<b>不是拦截
 * 而是修复</b>（能截就截），后面三检都是「命中即整条不发」。
 *
 * <p>为什么宁可不发：发出去一条带手机号的、带「贵公司」的机器话术，比这条不回更伤。
 * 这与 spec 5.3 的「宁可漏回」同一个口径。
 *
 * <p>纯函数，不碰数据库、不碰 Playwright：词表与敏感词都由 {@link GuardOptions} 传入。
 * 这样「默认词表 31 条全部逐条命中」这种断言可以直接参数化生成，不需要起 Spring 上下文。
 *
 * <p><b>这个类只是兜底，不是保证。</b>决定回复像不像人的是 prompt 里的风格约束与模型本身；
 * 这里做的是「拦住明显不该发的」，以及「拦住之后留下可归因的计数」。
 */
public final class ReplyGuard {

    /** 单条回复上限（字）。超过先截断，截断后仍超就整条不发。 */
    public static final int MAX_CHARS = 60;

    /**
     * 默认 AI 味词表，31 条，全部是整串匹配、口语里几乎不会出现的书面套话。
     *
     * <p>几条取舍（删之前先读，否则会被当成脏数据清掉）：
     * <ul>
     *   <li>{@code 贵司} 与 {@code 贵公司} 都留 —— 二者互不包含，不是重复项。</li>
     *   <li>{@code 希望对您有帮助} 只留短的：整串匹配下它已经覆盖「希望对您有所帮助」。</li>
     *   <li>{@code 我将} 是唯一有误杀风险的（「我将简历发您」勉强能说），留着是因为它在聊天里
     *       极少见、在模型输出里极常见。被误杀时按 {@code BLOCKED_TONE} 计数就能看出来，删掉即可。</li>
     *   <li>{@code 感谢} / {@code 您好} / {@code 首先} 一律不进表：真人天天在用，
     *       单词级匹配会把正常回复全杀掉。</li>
     * </ul>
     *
     * <p>加词比删词危险：加之前先确认它在正常聊天里不会出现，并补一条对应的反例单测。
     */
    public static final List<String> DEFAULT_TONE_TERMS = List.of(
            "本人具备", "本人拥有", "本人从事",
            "贵公司", "贵司", "期待与您共事", "期待能有机会",
            "很高兴为您服务", "很高兴为您解答",
            "感谢您的耐心", "感谢您的关注",
            "如有需要请随时", "如有任何问题",
            "希望对您有帮助", "希望对你有所帮助", "希望以上回答",
            "请问还有什么", "以上是我的回答",
            "综上所述", "总而言之", "值得一提的是", "需要注意的是",
            "以下是", "我将",
            "作为AI", "作为一个AI", "我是一个AI", "我是AI", "语言模型", "人工智能助手",
            "我理解您的需求");

    // ---------------------------------------------------------------- 固定结构规则

    private static final Pattern NUMBERED_LIST = Pattern.compile("(?m)^\\s*\\d+[.、)]");
    private static final Pattern CIRCLED_LIST = Pattern.compile("[①-⑨]");
    private static final Pattern MARKDOWN_LIST = Pattern.compile("(?m)^\\s*[-*+]\\s");
    private static final Pattern MARKDOWN_BOLD = Pattern.compile("\\*\\*");
    private static final Pattern HEADING = Pattern.compile("：\\n|##");

    // ---------------------------------------------------------------- 联系方式

    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{6,}");
    /**
     * 联系方式的词面。{@code 威信} / {@code v我} 这类变体必须一起覆盖 ——
     * 模型越界最常见的形式就是换个说法要联系方式，只拦「微信」等于没拦。
     */
    private static final Pattern CONTACT_WORD =
            Pattern.compile("微信|weixin|wechat|vx|v信|威信|v我|qq|扣扣|邮箱|email|e-mail");

    /** 数字之间的分隔符：手机号被写成「138 0013 8000」也要认出来 */
    private static final Pattern DIGIT_SEPARATOR = Pattern.compile("(?<=\\d)[\\s\\-_.·、](?=\\d)");

    // ---------------------------------------------------------------- 薪资与承诺

    private static final Pattern SALARY_WORD =
            Pattern.compile("薪资|工资|月薪|年薪|k/月|到岗|保证|一定");

    private ReplyGuard() {
    }

    /** 四检结论。{@code rejection} 为 {@code NONE} 表示放行。 */
    public enum Rejection {
        NONE,
        EMPTY,
        TOO_LONG,
        CONTACT,
        SENSITIVE,
        TONE
    }

    /**
     * 落库用的拦截动作码：{@code BLOCKED_TOO_LONG} / {@code BLOCKED_CONTACT} 等。
     *
     * <p>由枚举名派生，不在别处再列一遍 —— 未命中列表要按这些码筛选，
     * 两处各写一份的后果是「新增一种拦截，面板上永远看不到它」。
     * {@code NONE} / {@code EMPTY} 不在这里：前者是放行，后者走重试而不是终态。
     */
    public static final List<String> BLOCKED_ACTIONS = Arrays.stream(Rejection.values())
            .filter(r -> r != Rejection.NONE && r != Rejection.EMPTY)
            .map(r -> "BLOCKED_" + r.name())
            .toList();

    /**
     * @param allowed   是否允许发
     * @param text      放行时的最终文本（可能被截短过）；拦截时为 {@code null}
     * @param rejection 拦截原因；放行时为 {@link Rejection#NONE}
     * @param detail    归因说明，进日志用。**不含手机号等联系方式原文**，避免把要拦的东西记进日志
     */
    public record Verdict(boolean allowed, String text, Rejection rejection, String detail) {

        public static Verdict pass(String text) {
            return new Verdict(true, text, Rejection.NONE, "");
        }

        public static Verdict block(Rejection rejection, String detail) {
            return new Verdict(false, null, rejection, detail);
        }

        public boolean blocked() {
            return !allowed;
        }
    }

    /**
     * @param toneTerms        配置的 AI 味词表；**空表示用默认 31 条**，不是「不检查」
     * @param toneCheckEnabled 第四检总开关
     * @param sensitiveWords   敏感词表（与规则引擎同一份数据，AI 之前已经拦过一轮，这里再拦一次）
     */
    public record GuardOptions(List<String> toneTerms, boolean toneCheckEnabled,
                               List<String> sensitiveWords) {

        public GuardOptions {
            toneTerms = toneTerms == null ? List.of() : List.copyOf(toneTerms);
            sensitiveWords = sensitiveWords == null ? List.of() : List.copyOf(sensitiveWords);
        }

        public static GuardOptions defaults() {
            return new GuardOptions(List.of(), true, List.of());
        }

        public List<String> effectiveToneTerms() {
            return toneTerms.isEmpty() ? DEFAULT_TONE_TERMS : toneTerms;
        }
    }

    /**
     * @param draft    模型（或模板）给出的草稿
     * @param incoming 对方最后一条消息，用于「复述开场」这一条结构规则；可为 {@code null}
     */
    public static Verdict check(String draft, String incoming, GuardOptions options) {
        GuardOptions opts = options == null ? GuardOptions.defaults() : options;
        String text = draft == null ? "" : draft.strip();
        if (text.isEmpty()) {
            return Verdict.block(Rejection.EMPTY, "草稿为空");
        }

        // 1) 长度：能截就截，截不了就整条不发 —— 不允许「发个短的凑数」
        String fitted = trimToLimit(text);
        if (codePoints(fitted) > MAX_CHARS) {
            return Verdict.block(Rejection.TOO_LONG,
                    "超过 " + MAX_CHARS + " 字且截不到句末标点（" + codePoints(fitted) + " 字）");
        }

        String normalized = TextNormalizer.normalize(fitted);

        // 2) 联系方式
        String contact = contactHit(normalized);
        if (contact != null) {
            return Verdict.block(Rejection.CONTACT, contact);
        }

        // 3) 薪资与承诺：敏感词表 + 固定的薪资/承诺词
        for (String word : opts.sensitiveWords()) {
            String needle = TextNormalizer.normalize(word);
            if (!needle.isEmpty() && normalized.contains(needle)) {
                return Verdict.block(Rejection.SENSITIVE, "命中敏感词「" + word + "」");
            }
        }
        var salary = SALARY_WORD.matcher(normalized);
        if (salary.find()) {
            return Verdict.block(Rejection.SENSITIVE, "命中薪资/承诺词「" + salary.group() + "」");
        }

        // 4) AI 味：词表（可配）+ 结构规则（固定）
        if (opts.toneCheckEnabled()) {
            for (String term : opts.effectiveToneTerms()) {
                String needle = TextNormalizer.normalize(term);
                if (!needle.isEmpty() && normalized.contains(needle)) {
                    return Verdict.block(Rejection.TONE, "命中套话「" + term + "」");
                }
            }
            String structure = structuralHit(fitted, incoming);
            if (structure != null) {
                return Verdict.block(Rejection.TONE, "结构像机器输出：" + structure);
            }
        }

        return Verdict.pass(fitted);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 超长时截到最后一个句末标点。截完仍超（说明根本没有标点可分）就原样返回，交给上层判超长。
     */
    static String trimToLimit(String text) {
        if (codePoints(text) <= MAX_CHARS) {
            return text;
        }
        int end = lastSentenceEnd(text);
        if (end < 1) {
            return text;
        }
        String cut = text.substring(0, end + 1);
        return codePoints(cut) <= MAX_CHARS ? cut : text;
    }

    private static int lastSentenceEnd(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '？' || c == '！' || c == '?' || c == '!' || c == '.') {
                return i;
            }
        }
        return -1;
    }

    private static String contactHit(String normalized) {
        if (PHONE.matcher(normalized).find()) {
            return "含手机号";
        }
        var word = CONTACT_WORD.matcher(normalized);
        if (word.find()) {
            return "含联系方式词「" + word.group() + "」";
        }
        // 「138 0013 8000」这类分开写的：只在数字之间合并分隔符，不会把中文当分隔符吞掉
        String squashed = squashDigits(normalized);
        if (LONG_DIGITS.matcher(squashed).find()) {
            return "含 6 位以上连续数字";
        }
        return null;
    }

    private static String squashDigits(String text) {
        String previous;
        String current = text;
        do {
            previous = current;
            current = DIGIT_SEPARATOR.matcher(current).replaceAll("");
        } while (!current.equals(previous));
        return current;
    }

    /**
     * 结构规则固定不可配：这些不是「词」，配成文本极易配错，误杀风险本身也低。
     *
     * <p>必须拿<b>原文</b>判，不能拿归一化后的文本：{@code TextNormalizer} 会把换行折叠成空格，
     * 行首 {@code ^}、「：\n」这类判据在归一化之后全部失效 —— 那样小标题和列表一条都拦不住，
     * 而且看不出来（检查"通过"了）。
     */
    private static String structuralHit(String text, String incoming) {
        if (NUMBERED_LIST.matcher(text).find()) {
            return "编号列表";
        }
        if (CIRCLED_LIST.matcher(text).find()) {
            return "圈号列表";
        }
        if (MARKDOWN_LIST.matcher(text).find()) {
            return "markdown 列表";
        }
        if (MARKDOWN_BOLD.matcher(text).find()) {
            return "markdown 加粗";
        }
        if (HEADING.matcher(text).find()) {
            return "小标题";
        }
        String echo = echoPrefix(incoming);
        // 复述判定反过来要用归一化：对方原话里可能有换行/全角，跟草稿的空白形态不一定一致
        if (echo != null && TextNormalizer.normalize(text).startsWith(echo)) {
            return "复述对方开场";
        }
        return null;
    }

    /** 对方最后一句的前 8 个字；不足 8 个字就没有可比指纹，不判。 */
    private static String echoPrefix(String incoming) {
        String normalized = TextNormalizer.normalize(incoming);
        return normalized.length() < 8 ? null : normalized.substring(0, 8);
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }
}
