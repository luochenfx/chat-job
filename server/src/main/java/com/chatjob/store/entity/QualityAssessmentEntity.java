package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 一次岗位质量判断（CONTEXT.md「质量评估」）。
 *
 * <p>一段会话可以有多行，界面只取最新一行。{@code jobName / companyName / salary} 是判断那一刻的
 * 快照：会话与岗位之间没有可靠关联（{@code conversation.job_posting_id} 至今恒为空），
 * 列表要靠这几列自己站住。
 */
@Data
@TableName("quality_assessment")
public class QualityAssessmentEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;
    private Long conversationId;
    /** 反查到才写。为空表示这次只看了对话，没有岗位信息 */
    private Long jobPostingId;
    private String jobName;
    private String companyName;
    private String salary;
    private Integer score;
    /** 逐项结论，JSON 数组。形状见 {@code QualityJudge.Item} */
    private String criteriaJson;
    /** 一句话理由，给列表和通知正文用 */
    private String summary;
    /** 判断这一刻会话里有几条 HR 消息。重判的判据是「比这次多 3 条」 */
    private Integer hrMessages;
    private String assessedAt;
}
