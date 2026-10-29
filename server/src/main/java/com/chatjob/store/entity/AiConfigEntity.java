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
    private String updatedAt;
}
