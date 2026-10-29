package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 字典项：城市 / 行业 / 薪资 / 经验 / 学历 / 规模 / 阶段 / 职位类型 的 name-code 映射。 */
@Data
@TableName("platform_option")
public class PlatformOptionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;
    private String type;
    private String name;
    private String code;
    private Integer sortOrder;
}
