package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 值守动作留痕。UNMATCHED 记录是规则库唯一的进化路径，不要省。 */
@Data
@TableName("reply_log")
public class ReplyLogEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String platformMessageId;
    private String incomingText;
    /** REPLIED | SKIPPED_SENSITIVE | SKIPPED_SELF | SKIPPED_DUPLICATE | SKIPPED_THROTTLED | UNMATCHED */
    private String action;
    private Long matchedRuleId;
    private String matchedSensitive;
    private String replyText;
    private String createdAt;
}
