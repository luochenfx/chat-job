package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 未读任务：一条「这个会话有未读、还没处理完」的凭据。
 *
 * <p>之所以要这张表而不是只靠会话列表，是因为点开会话的那一刻平台就把红点清了，
 * 而平台没有「标回未读」的能力 —— 失败之后这条消息在列表里再也看不见，
 * 只能靠这张表捞回来（spec 0002 §3.5）。
 *
 * <p>{@code lastMessageDigest} 只存摘要不存原文：够做「列表是否已变化」的校验，
 * 又不把 HR 原话明文再落一份（原话在 {@code reply_log.incoming_text} 里已有）。
 */
@Data
@TableName("unread_task")
public class UnreadTaskEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;
    /** 当前等于 HR 标题：平台不提供稳定会话 id，见 spec 5.3 */
    private String platformConversationId;
    private Long conversationId;
    private Integer unreadCount;
    /** 列表摘要的 SHA-256 前 16 位 */
    private String lastMessageDigest;
    /** 点开会话后回填的平台消息 id（data-mid） */
    private String platformMessageId;
    /** PENDING | PROCESSING | DONE | FAILED | GAVE_UP */
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    /** REPLIED | SKIP_SENSITIVE | SKIP_THROTTLED | SKIP_SELF | SKIP_DUPLICATE
     *  | AI_FAILED | SEND_FAILED | LOCATE_FAILED | MANUAL_RETRY */
    private String lastAction;
    /** 与 reply_log.reason_code 同枚举，便于控制台直接展示原因 */
    private String lastReasonCode;
    private String lastError;
    private String nextRetryAt;
    private String createdAt;
    private String updatedAt;
}
