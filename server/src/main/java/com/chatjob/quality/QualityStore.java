package com.chatjob.quality;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chatjob.evaluate.QualityJudge;
import com.chatjob.store.entity.ConversationEntity;
import com.chatjob.store.entity.QualityAssessmentEntity;
import com.chatjob.store.mapper.ConversationMapper;
import com.chatjob.store.mapper.QualityAssessmentMapper;
import com.chatjob.time.TimeSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 岗位质量的持久化：评估行、接管状态、提醒标记。
 *
 * <p>只做读写，不做「要不要判」的判断 —— 那是 {@link QualityAssessor} 的事。
 * 分开的理由与 {@code JobStore} / {@code ScanSegment} 一样：判据要能脱离数据库单独断言。
 */
@Slf4j
@Component
public class QualityStore {

    /**
     * 列表的一行：一次评估 + 它所属的会话。
     *
     * @param items 逐项结论，从 {@code criteria_json} 读回。解析失败时为空列表，不影响这一行本身
     */
    public record Entry(QualityAssessmentEntity assessment, ConversationEntity conversation,
                        List<QualityJudge.Item> items) {
    }

    private final QualityAssessmentMapper assessmentMapper;
    private final ConversationMapper conversationMapper;
    private final ObjectMapper json;
    private final TimeSource time;

    public QualityStore(QualityAssessmentMapper assessmentMapper, ConversationMapper conversationMapper,
                        ObjectMapper json, TimeSource time) {
        this.assessmentMapper = assessmentMapper;
        this.conversationMapper = conversationMapper;
        this.json = json;
        this.time = time;
    }

    public void save(QualityAssessmentEntity entity) {
        if (entity.getAssessedAt() == null) {
            entity.setAssessedAt(time.stamp());
        }
        assessmentMapper.insert(entity);
    }

    /** 同一段会话的最新一次评估。重判条件要用它的 {@code hr_messages} 与 {@code assessed_at} */
    public QualityAssessmentEntity latest(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        List<QualityAssessmentEntity> list = assessmentMapper.selectList(
                Wrappers.<QualityAssessmentEntity>lambdaQuery()
                        .eq(QualityAssessmentEntity::getConversationId, conversationId)
                        .orderByDesc(QualityAssessmentEntity::getId)
                        .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 「重点关注」列表：每段会话取最新一次评估，只要分数过线的。
     *
     * <p>用子查询取每段会话的最大 id，而不是「先把全部读出来再去重」——
     * 后者在评估次数多了之后会把整张表读进内存。
     *
     * <p>按分数从高到低，同分时新的在前。不加时间窗：一段会话几个月后还在列表中，
     * 恰恰说明它值得回头看，而「什么时候评的」已经在时间列上写着。
     */
    public List<Entry> list(int scoreLine, int limit) {
        List<QualityAssessmentEntity> assessments = assessmentMapper.latestAbove(scoreLine, Math.max(1, limit));
        if (assessments.isEmpty()) {
            return List.of();
        }
        List<Long> conversationIds = assessments.stream()
                .map(QualityAssessmentEntity::getConversationId)
                .distinct()
                .toList();
        Map<Long, ConversationEntity> conversations = conversationsOf(conversationIds);
        List<Entry> out = new ArrayList<>(assessments.size());
        for (QualityAssessmentEntity a : assessments) {
            out.add(new Entry(a, conversations.get(a.getConversationId()), itemsOf(a)));
        }
        return out;
    }

    /** 当日该平台已评估次数。与投递判定的 {@code daily_counter.evaluated} 是两个名额 */
    public long countOnDay(String platform, String from, String to) {
        return assessmentMapper.selectCount(Wrappers.<QualityAssessmentEntity>lambdaQuery()
                .eq(QualityAssessmentEntity::getPlatform, platform)
                .ge(QualityAssessmentEntity::getAssessedAt, from)
                .lt(QualityAssessmentEntity::getAssessedAt, to));
    }

    /** 接管：此后值守不打开这段会话、不读消息。返回 false 表示会话不存在 */
    public boolean takeOver(Long conversationId) {
        ConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            return false;
        }
        conversation.setAutoReplyPaused(1);
        conversation.setTakenOverAt(time.stamp());
        conversationMapper.updateById(conversation);
        return true;
    }

    /** 交还 AI。走显式 SQL：{@code updateById} 写不进 null（见 {@code ConversationMapper}） */
    public boolean release(Long conversationId) {
        return conversationMapper.releaseTakeover(conversationId) > 0;
    }

    /**
     * 记下「已经为这段会话发过提醒」。
     *
     * <p>先用「读一遍 → 空才写」而不是无条件 UPDATE：两个平台的 Run 并行时，
     * 同一段会话理论上不会同时被评（会话按平台归属），但这里多一次判空只花一条 SELECT，
     * 换掉「同一段会话收到两条提醒」的可能。
     */
    public boolean markNotified(Long conversationId) {
        if (conversationId == null) {
            return false;
        }
        ConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            return false;
        }
        if (conversation.getQualityNotifiedAt() != null && !conversation.getQualityNotifiedAt().isBlank()) {
            return false;
        }
        conversation.setQualityNotifiedAt(time.stamp());
        conversationMapper.updateById(conversation);
        return true;
    }

    /** 逐项结论从 JSON 读回。解析失败返回空列表：一行结论坏掉不该让整个列表打不开 */
    public List<QualityJudge.Item> itemsOf(QualityAssessmentEntity entity) {
        String raw = entity.getCriteriaJson();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = json.readTree(raw);
            List<QualityJudge.Item> items = new ArrayList<>();
            for (JsonNode node : array) {
                items.add(new QualityJudge.Item(
                        node.path("criterion").asText(""),
                        QualityJudge.verdictOf(node.path("verdict").asText("")),
                        node.path("evidence").asText("")));
            }
            return items;
        } catch (Exception e) {
            log.warn("质量评估的逐项结论读不出来，按空处理 | id={} | {}", entity.getId(), e.getMessage());
            return List.of();
        }
    }

    /** {@code selectMaps} 的列名是下划线，这里按实体字段名取回来 */
    private Map<Long, ConversationEntity> conversationsOf(Collection<Long> ids) {
        Map<Long, ConversationEntity> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (ConversationEntity c : conversationMapper.selectBatchIds(ids)) {
            out.put(c.getId(), c);
        }
        return out;
    }
}
