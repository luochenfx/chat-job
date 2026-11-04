package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 会话游标。首响时间与回复条数只用于效果统计（可开关），不参与自动决策。
 *
 * <p>{@code autoReplyPaused} 是人工接管（ADR-0016）：为 1 时值守不打开这条会话、不读消息、
 * 不落任务。它管的是「系统还替不替这段会话说话」，与 AI 开关无关 —— 关闭态也照样守它。
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
    /** 人工接管：1 = 用户自己接手，系统不再替它发消息，也不再打开它（ADR-0016） */
    private Integer autoReplyPaused;
    /** 接手的时刻，只用于界面显示 */
    private String takenOverAt;
    /** 这段会话已经发过「重点关注」提醒的时刻。只提醒一次，跌下去再升上来也不再发 */
    private String qualityNotifiedAt;
    /** 接管期间仍然更新的最后一句摘要：不打开会话也能从列表页读到（ADR-0016 第 3 条） */
    private String lastThreadMessage;
    private Integer lastThreadUnread;
    private String createdAt;

    /** 没有这一列时（升级前的会话行读完为 null）按「未接管」处理 */
    public boolean takenOver() {
        return autoReplyPaused != null && autoReplyPaused == 1;
    }
}
