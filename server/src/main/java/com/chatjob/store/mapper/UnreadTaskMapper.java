package com.chatjob.store.mapper;

import com.chatjob.store.entity.UnreadTaskEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 未读任务读写。
 *
 * <p>刻意<b>不</b>继承 {@code BaseMapper}：这里需要的都是带业务语义的查询
 * （「这条会话有没有在途任务」「哪些任务到点该重试」），用注解写死 SQL 比在
 * wrapper 里拼条件更清楚；顺带让单测可以用 hand-written fake 直接实现这 5 个方法，
 * 不必为了测状态机去实现 BaseMapper 那一整套接口。
 */
@Mapper
public interface UnreadTaskMapper {

    String COLUMNS = "id, platform, platform_conversation_id, conversation_id, unread_count, "
            + "last_message_digest, platform_message_id, status, attempt_count, max_attempts, "
            + "last_action, last_reason_code, last_error, next_retry_at, created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM unread_task WHERE id = #{id}")
    UnreadTaskEntity findById(@Param("id") Long id);

    /** 在途任务 = PENDING / PROCESSING / FAILED。DONE 与 GAVE_UP 是终结态，不占位 */
    @Select("SELECT " + COLUMNS + " FROM unread_task "
            + "WHERE platform = #{platform} AND platform_conversation_id = #{key} "
            + "AND status IN ('PENDING', 'PROCESSING', 'FAILED') "
            + "ORDER BY id DESC LIMIT 1")
    UnreadTaskEntity findOpen(@Param("platform") String platform, @Param("key") String key);

    /**
     * 到点该重试的任务。时间是可比较的文本（yyyy-MM-dd'T'HH:mm:ss），字典序即时间序。
     *
     * <p>只取 FAILED 且未耗尽次数：PENDING 是「还没开始」，PROCESSING 是「正在处理」，
     * GAVE_UP 是「不再试」—— 三者都不该被重试捞出来。
     */
    @Select("SELECT " + COLUMNS + " FROM unread_task "
            + "WHERE status = 'FAILED' AND attempt_count < max_attempts "
            + "AND next_retry_at IS NOT NULL AND next_retry_at <= #{now} "
            + "ORDER BY next_retry_at LIMIT #{limit}")
    List<UnreadTaskEntity> findDueForRetry(@Param("now") String now, @Param("limit") int limit);

    @Insert("INSERT INTO unread_task(platform, platform_conversation_id, conversation_id, unread_count, "
            + "last_message_digest, status, attempt_count, max_attempts, created_at, updated_at) VALUES "
            + "(#{platform}, #{platformConversationId}, #{conversationId}, #{unreadCount}, "
            + "#{lastMessageDigest}, #{status}, #{attemptCount}, #{maxAttempts}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(UnreadTaskEntity entity);

    @Update("UPDATE unread_task SET conversation_id = #{conversationId}, unread_count = #{unreadCount}, "
            + "last_message_digest = #{lastMessageDigest}, platform_message_id = #{platformMessageId}, "
            + "status = #{status}, attempt_count = #{attemptCount}, max_attempts = #{maxAttempts}, "
            + "last_action = #{lastAction}, last_reason_code = #{lastReasonCode}, "
            + "last_error = #{lastError}, next_retry_at = #{nextRetryAt}, updated_at = #{updatedAt} "
            + "WHERE id = #{id}")
    int update(UnreadTaskEntity entity);
}
