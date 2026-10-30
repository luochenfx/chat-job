package com.chatjob.watcher;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.ConversationEntity;
import com.chatjob.store.entity.ReplyLogEntity;
import com.chatjob.store.entity.ReplyRuleEntity;
import com.chatjob.store.entity.ReplyRuleTextEntity;
import com.chatjob.store.mapper.ConversationMapper;
import com.chatjob.store.mapper.ReplyLogMapper;
import com.chatjob.store.mapper.ReplyRuleMapper;
import com.chatjob.store.mapper.ReplyRuleTextMapper;
import com.chatjob.store.mapper.SensitiveWordMapper;
import com.chatjob.store.entity.SensitiveWordEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 值守规则的读写与会话游标。 */
@Slf4j
@Component
public class ReplyStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    /** 同会话回复冷却：3 分钟 */
    public static final int REPLY_COOLDOWN_MINUTES = 3;

    private final ReplyRuleMapper ruleMapper;
    private final ReplyRuleTextMapper textMapper;
    private final SensitiveWordMapper sensitiveMapper;
    private final ReplyLogMapper logMapper;
    private final ConversationMapper conversationMapper;

    public ReplyStore(ReplyRuleMapper ruleMapper,
                      ReplyRuleTextMapper textMapper,
                      SensitiveWordMapper sensitiveMapper,
                      ReplyLogMapper logMapper,
                      ConversationMapper conversationMapper) {
        this.ruleMapper = ruleMapper;
        this.textMapper = textMapper;
        this.sensitiveMapper = sensitiveMapper;
        this.logMapper = logMapper;
        this.conversationMapper = conversationMapper;
    }

    public List<ReplyRuleEntity> rules() {
        return ruleMapper.selectList(Wrappers.<ReplyRuleEntity>lambdaQuery()
                .orderByAsc(ReplyRuleEntity::getSortOrder)
                .orderByAsc(ReplyRuleEntity::getId));
    }

    public List<ReplyRuleTextEntity> textsOf(Long ruleId) {
        return textMapper.selectList(Wrappers.<ReplyRuleTextEntity>lambdaQuery()
                .eq(ReplyRuleTextEntity::getRuleId, ruleId)
                .orderByAsc(ReplyRuleTextEntity::getSortOrder));
    }

    public List<String> sensitiveWords() {
        return sensitiveMapper.selectList(Wrappers.emptyWrapper()).stream()
                .map(SensitiveWordEntity::getWord)
                .filter(w -> w != null && !w.isBlank())
                .toList();
    }

    /** 从库构建引擎。规则库很小（几十条），每次重建的代价可以忽略 */
    public ReplyRuleEngine engine() {
        List<ReplyRuleEngine.Rule> rules = new ArrayList<>();
        for (ReplyRuleEntity rule : rules()) {
            String rawKeywords = rule.getKeywords() == null ? "" : rule.getKeywords();
            List<String> keywords = Arrays.stream(rawKeywords.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            List<String> texts = textsOf(rule.getId()).stream()
                    .map(ReplyRuleTextEntity::getText)
                    .filter(t -> t != null && !t.isBlank())
                    .toList();
            rules.add(new ReplyRuleEngine.Rule(rule.getId(), rule.getName(), keywords, texts,
                    rule.getEnabled() != null && rule.getEnabled() == 1, rule.getAction()));
        }
        return new ReplyRuleEngine(rules, sensitiveWords());
    }

    // ------------------------------------------------------------ 会话

    public ConversationEntity upsertConversation(String platform, String conversationKey, Long jobPostingId) {
        ConversationEntity existing = findConversation(platform, conversationKey);
        String now = ConfigStore.now();
        if (existing == null) {
            ConversationEntity entity = new ConversationEntity();
            entity.setPlatform(platform);
            entity.setPlatformConversationId(conversationKey);
            entity.setJobPostingId(jobPostingId);
            entity.setLastCheckedAt(now);
            entity.setHrReplyCount(0);
            entity.setCreatedAt(now);
            conversationMapper.insert(entity);
            return entity;
        }
        existing.setLastCheckedAt(now);
        if (jobPostingId != null) {
            existing.setJobPostingId(jobPostingId);
        }
        conversationMapper.updateById(existing);
        return existing;
    }

    public ConversationEntity findConversation(String platform, String conversationKey) {
        return conversationMapper.selectOne(Wrappers.<ConversationEntity>lambdaQuery()
                .eq(ConversationEntity::getPlatform, platform)
                .eq(ConversationEntity::getPlatformConversationId, conversationKey));
    }

    /**
     * 按主键取会话。重试路径上只拿得到 {@code unread_task.conversation_id}，
     * 拿不到平台会话 id（那玩意儿就是 HR 标题，任务表里存的是同一个值，但语义不同，不要混用）。
     */
    public ConversationEntity findById(Long conversationId) {
        return conversationId == null ? null : conversationMapper.selectById(conversationId);
    }

    public boolean inCooldown(ConversationEntity conversation) {
        if (conversation == null || conversation.getLastOwnReplyAt() == null) {
            return false;
        }
        try {
            return LocalDateTime.parse(conversation.getLastOwnReplyAt(), TS)
                    .isAfter(LocalDateTime.now().minusMinutes(REPLY_COOLDOWN_MINUTES));
        } catch (Exception e) {
            return false;
        }
    }

    public void markOwnReply(ConversationEntity conversation) {
        conversation.setLastOwnReplyAt(ConfigStore.now());
        conversationMapper.updateById(conversation);
    }

    /** 效果采集（可开关）：只记首响时间与条数，不参与任何自动决策 */
    public void recordHrMessage(ConversationEntity conversation) {
        String now = ConfigStore.now();
        conversation.setLastHrMessageAt(now);
        if (conversation.getFirstHrReplyAt() == null) {
            conversation.setFirstHrReplyAt(now);
        }
        conversation.setHrReplyCount((conversation.getHrReplyCount() == null ? 0 : conversation.getHrReplyCount()) + 1);
        conversationMapper.updateById(conversation);
    }

    // ------------------------------------------------------------ 日志

    public boolean wasProcessed(Long conversationId, String messageId) {
        if (conversationId == null || messageId == null) {
            return false;
        }
        Long count = logMapper.selectCount(Wrappers.<ReplyLogEntity>lambdaQuery()
                .eq(ReplyLogEntity::getConversationId, conversationId)
                .eq(ReplyLogEntity::getPlatformMessageId, messageId));
        return count != null && count > 0;
    }

    public void log(ReplyLogEntity entity) {
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(ConfigStore.now());
        }
        logMapper.insert(entity);
    }

    /**
     * 未命中列表（spec 3.8.3）。口径随 AI 开关切换：
     *
     * <ul>
     *   <li>关闭态：只显示 {@code UNMATCHED} —— 这就是改造前的行为，一眼可比；</li>
     *   <li>打开态：{@code AI_SKIPPED}（带原因枚举）/ {@code AI_FAILED}（带失败类型）/
     *       {@code BLOCKED_*}。这三种正是「发现了但没回」的全部形态，
     *       而 {@code UNMATCHED} 在打开态已经不会再产生。</li>
     * </ul>
     *
     * <p>刻意不含 {@code REPLIED} 与 {@code SKIP_*}：前者是成功，后者是「重复 / 冷却」这类
     * 正常跳过，混进来会让「需要人补规则」的那批被噪声淹掉。
     */
    public List<ReplyLogEntity> unmatched(int days, int limit, boolean includeAiOutcomes) {
        String cutoff = LocalDateTime.now().minusDays(days).format(TS);
        var query = Wrappers.<ReplyLogEntity>lambdaQuery()
                .ge(ReplyLogEntity::getCreatedAt, cutoff)
                .orderByDesc(ReplyLogEntity::getId)
                .last("LIMIT " + Math.max(1, limit));
        if (includeAiOutcomes) {
            List<String> actions = new ArrayList<>();
            // 这两个常量在 UnreadTaskStore.Action 上：AI 分支的落库动作与任务状态机同源
            actions.add(UnreadTaskStore.Action.AI_SKIPPED);
            actions.add(UnreadTaskStore.Action.AI_FAILED);
            actions.addAll(ReplyGuard.BLOCKED_ACTIONS);
            query.in(ReplyLogEntity::getAction, actions);
        } else {
            query.eq(ReplyLogEntity::getAction, ReplyRuleEngine.Action.UNMATCHED.name());
        }
        return logMapper.selectList(query);
    }

    public List<ReplyLogEntity> recentLogs(int limit) {
        return logMapper.selectList(Wrappers.<ReplyLogEntity>lambdaQuery()
                .orderByDesc(ReplyLogEntity::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    /**
     * 「值守发简历数」：今日 {@code REPLY_RESUME} 的条数（ADR-0004 第 4 条）。
     *
     * <p>刻意与 {@code RunStore.deliveredToday()} 分开：那个数主动投递（扫描侧发起的沟通），
     * 这个数值守里应对方索要而发出的简历。两件事风控语义相反，一旦合并成一个数字，
     * 被动应答就会吃掉主动外呼的配额。
     *
     * <p>边界按本地自然日、左闭右开：昨天 23:59:59 那一份不该出现在今天的计数里，
     * 今天 00:00:00 的那一份必须算今天。
     */
    public int resumeOnRequestToday(String platform) {
        LocalDate day = LocalDate.now();
        String from = day.atStartOfDay().format(TS);
        String to = day.plusDays(1).atStartOfDay().format(TS);
        return (int) logMapper.countResumeOnRequest(platform, from, to);
    }

    public void deleteLogOlderThan(int days) {
        String cutoff = LocalDateTime.now().minusDays(days).format(TS);
        logMapper.delete(Wrappers.<ReplyLogEntity>lambdaQuery()
                .lt(ReplyLogEntity::getCreatedAt, cutoff));
    }
}
