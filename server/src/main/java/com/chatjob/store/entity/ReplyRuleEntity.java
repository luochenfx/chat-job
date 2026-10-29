package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 值守规则：一条规则 = 关键词组 + 文案池 + 启用开关。 */
@Data
@TableName("reply_rule")
public class ReplyRuleEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    /** 逗号分隔，子串包含匹配 */
    private String keywords;
    /**
     * 命中后的动作：{@code REPLY}（回一句话）或 {@code SEND_RESUME}（真的把简历发出去）。
     *
     * <p>做成字段而不是在代码里按规则名判断：规则名是给人看的、会被改，
     * 用它当分支条件等于把逻辑挂在一个随时能改的展示文案上。
     */
    private String action;
    private Integer enabled;
    private Integer sortOrder;
    private String createdAt;
    private String updatedAt;
}
