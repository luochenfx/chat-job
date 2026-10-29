package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 一次投递尝试。状态机：DELIVERING → DELIVERED | FAILED，人工干预后为 MANUAL。
 * 失败不自动重试；同一岗位最多一次自动重试由 orchestrator 判定。
 */
@Data
@TableName("delivery")
public class DeliveryEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobPostingId;
    private Long runId;
    private Integer attemptNo;
    private String status;
    /** AI | TEMPLATE | SKIP */
    private String decidedBy;
    private String greeting;
    private Integer score;
    private String aiReason;
    private String aiRaw;
    private String failureReason;
    private String startedAt;
    private String finishedAt;
}
