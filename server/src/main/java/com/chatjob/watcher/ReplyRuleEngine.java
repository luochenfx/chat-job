package com.chatjob.watcher;

import com.chatjob.evaluate.TextNormalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 值守判定。顺序即优先级，敏感词必须排在关键词前面 ——
 * 否则「你们薪资多少」会同时命中「薪资」敏感词和某条关键词，一旦回复就出事。
 *
 * <p>纯逻辑类，不碰数据库、不碰 Playwright，便于用 hand-written fakes 全量单测。
 */
public class ReplyRuleEngine {

    /** 规则的默认动作：回一句文案 */
    public static final String RULE_ACTION_REPLY = "REPLY";
    /** 规则的动作：真的把简历发出去，而不是回一句话 */
    public static final String RULE_ACTION_SEND_RESUME = "SEND_RESUME";

    public record Rule(Long id, String name, List<String> keywords, List<String> texts, boolean enabled,
                       String action) {

        /** 兼容旧构造点：没写动作的规则一律按「回文案」处理。 */
        public Rule(Long id, String name, List<String> keywords, List<String> texts, boolean enabled) {
            this(id, name, keywords, texts, enabled, RULE_ACTION_REPLY);
        }

        public boolean sendsResume() {
            return RULE_ACTION_SEND_RESUME.equalsIgnoreCase(action == null ? "" : action.trim());
        }
    }

    public enum Action {
        REPLY,
        /** 命中「发简历」类规则：执行真实动作，不产生文字 */
        SEND_RESUME,
        SKIP_SELF,
        SKIP_DUPLICATE,
        SKIP_SENSITIVE,
        SKIP_THROTTLED,
        UNMATCHED
    }

    public record Decision(Action action, Long ruleId, String matchedKeyword, String sensitive,
                           String replyText, String reason) {

        public boolean shouldReply() {
            return action == Action.REPLY;
        }
    }

    private final List<Rule> rules;
    private final List<String> sensitiveWords;

    public ReplyRuleEngine(List<Rule> rules, List<String> sensitiveWords) {
        this.rules = rules == null ? List.of() : rules;
        this.sensitiveWords = sensitiveWords == null ? List.of() : sensitiveWords;
    }

    public static ReplyRuleEngine empty() {
        return new ReplyRuleEngine(List.of(), List.of());
    }

    /**
     * @param text      收到的最后一条消息
     * @param fromSelf  是否自己发的
     * @param duplicate 该消息 id 是否已处理过
     * @param throttled 该会话是否处于回复冷却期
     */
    public Decision decide(String text, boolean fromSelf, boolean duplicate, boolean throttled) {
        if (fromSelf) {
            return new Decision(Action.SKIP_SELF, null, null, null, null, "自己发的消息");
        }
        if (duplicate) {
            return new Decision(Action.SKIP_DUPLICATE, null, null, null, null, "消息已处理过");
        }
        String normalized = TextNormalizer.normalize(text);
        if (normalized.isEmpty()) {
            return new Decision(Action.UNMATCHED, null, null, null, null, "消息为空或是图片");
        }

        for (String word : sensitiveWords) {
            String needle = TextNormalizer.normalize(word);
            if (!needle.isEmpty() && normalized.contains(needle)) {
                // 整条不回复。宁可漏回，也不要在薪资、费用这类话题上留下证据。
                return new Decision(Action.SKIP_SENSITIVE, null, null, word, null,
                        "命中敏感词「" + word + "」");
            }
        }

        Rule bestRule = null;
        String bestKeyword = null;
        for (Rule rule : rules) {
            if (!rule.enabled()) {
                continue;
            }
            for (String keyword : rule.keywords()) {
                String needle = TextNormalizer.normalize(keyword);
                if (needle.isEmpty() || !normalized.contains(needle)) {
                    continue;
                }
                // 命中多条规则时取关键词最长的那条：越具体越应该优先
                if (bestKeyword == null || needle.length() > bestKeyword.length()) {
                    bestRule = rule;
                    bestKeyword = keyword;
                }
            }
        }

        if (bestRule == null) {
            return new Decision(Action.UNMATCHED, null, null, null, null, "未命中任何规则");
        }
        if (throttled) {
            return new Decision(Action.SKIP_THROTTLED, bestRule.id(), bestKeyword, null, null,
                    "同会话回复过于频繁");
        }
        List<String> texts = bestRule.texts();
        String picked = (texts == null || texts.isEmpty())
                ? null
                : texts.get(ThreadLocalRandom.current().nextInt(texts.size()));

        // 命中「发简历」规则：动作是发，不是回。文案降级为兜底 —— 简历按钮是灰的
        // （平台要求双方回复后可用）时，还得有句话能说，否则这条消息就彻底没回应了。
        if (bestRule.sendsResume()) {
            return new Decision(Action.SEND_RESUME, bestRule.id(), bestKeyword, null, picked,
                    "命中规则「" + bestRule.name() + "」（关键词「" + bestKeyword + "」），动作=发简历");
        }

        if (picked == null) {
            return new Decision(Action.UNMATCHED, bestRule.id(), bestKeyword, null, null,
                    "规则「" + bestRule.name() + "」没有配置文案");
        }
        return new Decision(Action.REPLY, bestRule.id(), bestKeyword, null, picked,
                "命中规则「" + bestRule.name() + "」（关键词「" + bestKeyword + "」）");
    }

    /** 供控制台展示：规则覆盖了多少关键词 */
    public int keywordCount() {
        int count = 0;
        for (Rule rule : rules) {
            count += rule.keywords().size();
        }
        return count;
    }

    public List<Rule> rules() {
        return new ArrayList<>(rules);
    }

    public List<String> sensitiveWords() {
        return new ArrayList<>(sensitiveWords);
    }
}
