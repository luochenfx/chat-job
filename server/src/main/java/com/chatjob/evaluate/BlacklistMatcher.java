package com.chatjob.evaluate;

import com.chatjob.store.entity.BlacklistEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 硬黑名单匹配：确定性生效，命中即丢弃，不调 AI、不落投递。
 *
 * <p>黑名单必须是确定性的。如果把它交给 AI"参考"，你明确拒绝的「XX 外包」
 * 仍可能被 AI 打高分投出去，而且每次都要花 token。
 *
 * <p>规则：关键词最短 2 字符（否则「科技」会误杀一大片）；子串包含；公司名或岗位名任一命中即丢弃。
 */
public class BlacklistMatcher {

    public static final int MIN_KEYWORD_LENGTH = 2;

    public record Hit(String kind, String keyword, String field, String text) {
    }

    private final List<String> companyKeywords = new ArrayList<>();
    private final List<String> jobKeywords = new ArrayList<>();

    public BlacklistMatcher(Collection<BlacklistEntity> entries) {
        for (BlacklistEntity entry : entries) {
            String normalized = entry.getNormalized() == null
                    ? TextNormalizer.normalize(entry.getKeyword())
                    : TextNormalizer.normalize(entry.getNormalized());
            if (normalized.length() < MIN_KEYWORD_LENGTH) {
                continue;
            }
            if ("company".equals(entry.getKind())) {
                companyKeywords.add(normalized);
            } else if ("job".equals(entry.getKind())) {
                jobKeywords.add(normalized);
            }
        }
    }

    public Optional<Hit> match(String companyName, String jobName) {
        String company = TextNormalizer.normalize(companyName);
        String job = TextNormalizer.normalize(jobName);
        for (String keyword : companyKeywords) {
            if (!company.isEmpty() && company.contains(keyword)) {
                return Optional.of(new Hit("company", keyword, "companyName", companyName));
            }
        }
        for (String keyword : jobKeywords) {
            if (!job.isEmpty() && job.contains(keyword)) {
                return Optional.of(new Hit("job", keyword, "jobName", jobName));
            }
        }
        return Optional.empty();
    }

    public int size() {
        return companyKeywords.size() + jobKeywords.size();
    }

    /** 供 AI 参考的"已排除方向"摘要；不参与否决 */
    public String summaryForAi() {
        List<String> all = new ArrayList<>(companyKeywords);
        all.addAll(jobKeywords);
        return all.isEmpty() ? "无" : String.join("、", all);
    }

    /** 保存前校验：太短的关键词一律拒绝，避免误杀 */
    public static boolean isValidKeyword(String keyword) {
        return TextNormalizer.normalize(keyword).length() >= MIN_KEYWORD_LENGTH;
    }
}
