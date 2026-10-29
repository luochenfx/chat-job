package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 硬黑名单：命中即丢弃，不调 AI、不落投递。 */
@Data
@TableName("blacklist")
public class BlacklistEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** company | job */
    private String kind;
    private String keyword;
    private String normalized;
    private String createdAt;
}
