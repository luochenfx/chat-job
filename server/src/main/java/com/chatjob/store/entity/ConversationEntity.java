package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 会话游标。首响时间与回复条数只用于效果统计（可开关），不参与自动决策。
 */
@Data
@TableName("conversation")
public class ConversationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;
    private String platformConversationId;
    private Long jobPostingId;
    private String lastCheckedAt;
    private String lastOwnReplyAt;
    private String lastHrMessageAt;
    private String firstHrReplyAt;
    private Integer hrReplyCount;
    private String createdAt;
}
