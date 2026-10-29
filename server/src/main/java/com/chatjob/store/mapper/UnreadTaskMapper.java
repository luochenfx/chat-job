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
     * <p>两类会被捞出来：
     * <ul>
     *   <li>{@code FAILED} 且还没耗尽次数 —— 正常的退避重试。{@code next_retry_at} 为空时
     *       也算到期（与 {@code waitingForRetry} 的「读不出时间就立即重试」一致）：
     *       卡住的任务既不重试、又不会再出现在未读列表，等于静默丢消息；</li>
     *   <li>{@code PENDING} 且 {@code next_retry_at} 已到期 —— <b>手动重试</b>专用。
     *       人工把一条 {@code GAVE_UP} 放回 {@code PENDING} 并写了到期时间，
     *       但它的红点在当初点开时就没了，永远不会再出现在未读列表里，
     *       只能靠这条查询捞起来。少了这个分支，按钮点了等于没点。</li>
     * </ul>
     *
     * <p>PENDING 这一支必须要求 {@code next_retry_at} 非空：正常新建的任务是 PENDING + null，
     * 它属于「本轮还没开始处理」，归当轮流程管，不该被重试通道抢先捞走。
     */
    @Select("SELECT " + COLUMNS + " FROM unread_task "
            + "WHERE (status = 'FAILED' AND attempt_count < max_attempts "
            + "       AND (next_retry_at IS NULL OR next_retry_at <= #{now})) "
            + "   OR (status = 'PENDING' AND next_retry_at IS NOT NULL AND next_retry_at <= #{now}) "
            + "ORDER BY COALESCE(next_retry_at, created_at) LIMIT #{limit}")
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
