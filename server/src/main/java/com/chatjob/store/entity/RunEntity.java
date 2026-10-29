package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 一次任务。checkpoint 直接内联成列（combinationIndex / scrollRound / lastCardIndex），
 * 比塞 JSON 更好查、更好在控制台展示。
 */
@Data
@TableName("run")
public class RunEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;
    /** CREATED|RUNNING|PAUSED|COMPLETED|QUOTA_REACHED|CIRCUIT_OPEN|LOGIN_LOST|CANCELLED|FAILED */
    private String status;
    /** NORMAL | DRY_RUN */
    private String mode;
    private String terminalReason;
    private String message;
    private Integer combinationIndex;
    private Integer scrollRound;
    private Integer lastCardIndex;
    private Integer deliveredCount;
    private Integer evaluatedCount;
    private String startedAt;
    private String finishedAt;
    private String createdAt;
}
