package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("run_log")
public class RunLogEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private String level;
    private String message;
    private String createdAt;
}
