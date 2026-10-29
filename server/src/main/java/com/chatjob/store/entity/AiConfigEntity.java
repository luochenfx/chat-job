package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** AI 配置，单行。qualityGateEnabled 就是 spec 里的「质量开关」。 */
@Data
@TableName("ai_config")
public class AiConfigEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long providerId;
    private String introduce;
    private String promptTemplate;
    private String greetingPrompt;
    private Integer qualityGateEnabled;
    private String templateGreeting;
    private Integer jdMaxChars;
    private Integer aiTimeoutSeconds;
    private Integer collectEffectMetrics;
    /** 值守 AI 回复总开关。0 = 值守走改造前那条路径，逐行一致。 */
    private Integer aiReplyEnabled;
    /** AI 味检查开关（出站四检的第四检）。默认 1 */
    private Integer aiReplyToneCheckEnabled;
    /** AI 味词表，换行分隔。空串 = 用 ReplyGuard.DEFAULT_TONE_TERMS，不是「关闭检查」 */
    private String aiReplyToneTerms;
    /** 表达风格（怎么说）。空串 = 用 AiReplier.DEFAULT_STYLE，不是「无约束」 */
    private String aiReplyStyle;
    /** 单次回复生成超时。实际取 min(本值, provider.timeoutSeconds) */
    private Integer aiReplyTimeoutSeconds;
    /** 单轮值守最多调几次 AI。超出的任务退回 PENDING 等下一轮，不消耗重试预算 */
    private Integer aiReplyMaxPerSweep;
    private String updatedAt;
}
