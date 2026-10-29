package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 规则下的一条候选文案；同一规则命中时随机取一条，避免同句话发给几十个 HR。 */
@Data
@TableName("reply_rule_text")
public class ReplyRuleTextEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ruleId;
    private String text;
    private Integer sortOrder;
}
