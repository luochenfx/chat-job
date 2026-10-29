package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 敏感词：命中即整条不回复，优先级高于所有关键词规则。 */
@Data
@TableName("sensitive_word")
public class SensitiveWordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String word;
    private String createdAt;
}
