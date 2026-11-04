package com.chatjob.watcher;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import com.chatjob.time.TimeSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 值守规则的读写与会话游标。 */
@Slf4j
@Component
public class ReplyStore {

    /** 同会话回复冷却：3 分钟 */
    public static final int REPLY_COOLDOWN_MINUTES = 3;

    private final ReplyRuleMapper ruleMapper;
    private final ReplyRuleTextMapper textMapper;
    private final SensitiveWordMapper sensitiveMapper;
    private final ReplyLogMapper logMapper;
    private final ConversationMapper conversationMapper;
    /** 冷却窗口与按日区间的唯一来源 */
    private final TimeSource time;

    public ReplyStore(ReplyRuleMapper ruleMapper,
                      ReplyRuleTextMapper textMapper,
                      SensitiveWordMapper sensitiveMapper,
                      ReplyLogMapper logMapper,
                      ConversationMapper conversationMapper,
                      TimeSource time) {
        this.ruleMapper = ruleMapper;
        this.textMapper = textMapper;
        this.sensitiveMapper = sensitiveMapper;
        this.logMapper = logMapper;
        this.conversationMapper = conversationMapper;
        this.time = time;
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

    /** 单条规则，编辑时用来取旧值。不存在返回 null */
    public ReplyRuleEntity rule(Long id) {
        return id == null ? null : ruleMapper.selectById(id);
    }

    /**
     * 保存一条规则并全量替换它的文案。
     *
     * <p>两次写必须同生共死：文案是「这一组」，界面上不存在「规则改了、文案还是旧的」这种
     * 状态。没有事务时，文案写到一半失败会留下**部分文案**，而规则名已经改了 ——
     * 用户看到的是「保存失败」，库里却是半新半旧。
     *
     * <p>文案走「先删后插」而不是逐条比对：界面上提交的就是完整的一组，部分更新会留下孤儿行。
     */
    @Transactional
    public Long saveRule(ReplyRuleEntity rule, List<String> texts) {
        if (rule.getId() == null) {
            ruleMapper.insert(rule);
        } else {
            ruleMapper.updateById(rule);
        }
        textMapper.delete(Wrappers.<ReplyRuleTextEntity>lambdaQuery()
                .eq(ReplyRuleTextEntity::getRuleId, rule.getId()));
        int order = 0;
        for (String text : texts) {
            ReplyRuleTextEntity entity = new ReplyRuleTextEntity();
            entity.setRuleId(rule.getId());
            entity.setText(text);
            entity.setSortOrder(order++);
            textMapper.insert(entity);
        }
        return rule.getId();
    }

    /**
     * 删除一条规则连同它的文案。
     *
     * <p>顺序是「先文案、后规则」：反过来的话，第二步失败会留下一条**没有文案但仍在生效**的
     * 规则 —— 引擎按关键词命中它，却没有话可说。
     */
    @Transactional
    public void deleteRule(Long id) {
        textMapper.delete(Wrappers.<ReplyRuleTextEntity>lambdaQuery()
                .eq(ReplyRuleTextEntity::getRuleId, id));
        ruleMapper.deleteById(id);
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
        String now = time.stamp();
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

    /**
     * 我方是否还在该会话的回复冷却窗口内（{@link #REPLY_COOLDOWN_MINUTES} 分钟）。
     *
     * <p>空值表示「从没回过」，不构成冷却，返回 false。
     *
     * <p>读不出来时返回 true，即「仍在冷却」。这一步判错会连着给同一个 HR 发两条，
     * 代价高于漏回一条，所以保守处理并留 WARN。
     */
    public boolean inCooldown(ConversationEntity conversation) {
        if (conversation == null || conversation.getLastOwnReplyAt() == null) {
            return false;
        }
        var lastReply = time.parse(conversation.getLastOwnReplyAt());
        if (lastReply.isEmpty()) {
            log.warn("回复时间读不出来，按仍在冷却处理（本会话暂不回复）| 会话={} | timestamp={}",
                    conversation.getPlatformConversationId(), conversation.getLastOwnReplyAt());
            return true;
        }
        return lastReply.get().isAfter(time.now().minusMinutes(REPLY_COOLDOWN_MINUTES));
    }

    public void markOwnReply(ConversationEntity conversation) {
        conversation.setLastOwnReplyAt(time.stamp());
        conversationMapper.updateById(conversation);
    }

    /**
     * 接管期间仍然更新会话列表读到的那点信息（ADR-0016 第 3 条）。
     *
     * <p>接管后不打开会话 —— 打开会清掉平台上的未读红点，人不在电脑前就会看到「已读没人回」。
     * 但 {@code listUnreadThreads} 本来就给了最后一句与未读数，留下来界面才看得到
     * 「接管中，HR 又说了 2 条」，用户才有理由回到这个页面。
     */
    public void recordThreadObservation(ConversationEntity conversation, String lastMessage, int unread) {
        if (conversation == null) {
            return;
        }
        conversation.setLastThreadMessage(lastMessage == null ? "" : lastMessage);
        conversation.setLastThreadUnread(Math.max(0, unread));
        conversationMapper.updateById(conversation);
    }

    /** 效果采集（可开关）：只记首响时间与条数，不参与任何自动决策 */
    public void recordHrMessage(ConversationEntity conversation) {
        String now = time.stamp();
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
            entity.setCreatedAt(time.stamp());
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
        String cutoff = time.format(time.now().minusDays(days));
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
     * 被动应答就会消耗主动外呼的配额。
     *
     * <p>边界按本地自然日、左闭右开：昨天 23:59:59 那一份不该出现在今天的计数里，
     * 今天 00:00:00 的那一份必须算今天。
     */
    public int resumeOnRequestToday(String platform) {
        LocalDate day = time.now().toLocalDate();
        String from = time.format(day.atStartOfDay());
        String to = time.format(day.plusDays(1).atStartOfDay());
        return (int) logMapper.countResumeOnRequest(platform, from, to);
    }

    public void deleteLogOlderThan(int days) {
        String cutoff = time.format(time.now().minusDays(days));
        logMapper.delete(Wrappers.<ReplyLogEntity>lambdaQuery()
                .lt(ReplyLogEntity::getCreatedAt, cutoff));
    }
}
