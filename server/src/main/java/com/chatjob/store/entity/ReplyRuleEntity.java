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
    private Integer enabled;
    private Integer sortOrder;
    private String createdAt;
    private String updatedAt;
}
