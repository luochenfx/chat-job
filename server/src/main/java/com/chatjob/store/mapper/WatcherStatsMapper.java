package com.chatjob.store.mapper;

import com.chatjob.store.entity.UnreadTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 值守运维的只读查询（告警统计 + 任务面板）。
 *
 * <p>与 {@link UnreadTaskMapper} 分开：那边是任务状态机要用的读写，这边是给人看的聚合视图。
 * 混在一起的后果是「改统计口径」会牵动「改状态机」，而这两件事的变更频率和风险完全不同。
 *
 * <p>两个 count 用两条轻量 SQL 而不是一条 {@code SUM(CASE)}：7 天数据量在百条量级，
 * 可读性比省一次查询值钱；真要慢再加索引或合成一条。
 */
@Mapper
public interface WatcherStatsMapper {

    /**
     * 分母：AI 参与判定的总次数。
     *
     * <p>{@code reply_source = 'AI'} 这个条件不能省。混入 {@code TEMPLATE} 与历史 NULL 行，
     * 占比会被模板回复稀释成一个永远正常的数字 —— 分母算宽了，告警就永远不响。
     */
    @Select("SELECT COUNT(*) FROM reply_log WHERE reply_source = 'AI' AND created_at >= #{cutoff}")
    long countAiJudged(@Param("cutoff") String cutoff);

    /**
     * 分子：其中 AI 主动不回的次数。
     *
     * <p>只算 {@code AI_SKIPPED}。{@code AI_FAILED} 是接口坏了不是模型在躲；
     * {@code BLOCKED_*} 是校验层在拦 —— 混进来会让三个不同的问题互相掩盖。
     */
    @Select("SELECT COUNT(*) FROM reply_log "
            + "WHERE reply_source = 'AI' AND action = 'AI_SKIPPED' AND created_at >= #{cutoff}")
    long countAiSkipped(@Param("cutoff") String cutoff);

    /**
     * 近 N 天按「平台 + 状态」分组计数。
     *
     * <p>刻意<b>不带</b>平台条件：面板要同时给出「合计」（受筛选影响）和「分平台明细」
     * （永远描述整个窗口）。两次查询会因中间写入而对不上，而人只会觉得「面板算错了」；
     * 一次查全、在内存里分组，两份数字天然自洽。
     */
    @Select("SELECT platform, status, COUNT(*) AS cnt FROM unread_task "
            + "WHERE created_at >= #{cutoff} GROUP BY platform, status")
    List<Map<String, Object>> countTasksByStatus(@Param("cutoff") String cutoff);

    /**
     * 任务列表。刻意不返回 {@code last_message_digest} —— 它是消息内容的摘要，
     * 面板只需要「这条为什么失败」，不需要知道对方说了什么。
     *
     * <p>返回 {@code platform} 列（实体自带）：面板默认显示全部平台，明细必须能分清归属。
     */
    @Select("<script>SELECT id, platform, platform_conversation_id, conversation_id, status, "
            + "attempt_count, max_attempts, last_action, last_reason_code, last_error, "
            + "next_retry_at, created_at, updated_at "
            + "FROM unread_task WHERE created_at &gt;= #{cutoff} "
            + "<if test='status != null'> AND status = #{status} </if>"
            + "<if test='platform != null'> AND platform = #{platform} </if>"
            + "ORDER BY updated_at DESC LIMIT #{limit}</script>")
    List<UnreadTaskEntity> listTasks(@Param("cutoff") String cutoff,
                                     @Param("status") String status,
                                     @Param("platform") String platform,
                                     @Param("limit") int limit);
}
