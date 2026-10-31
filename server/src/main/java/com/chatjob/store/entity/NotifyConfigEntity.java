package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 告警配置，单行（ADR-0006）。
 *
 * <p>{@code wecomWebhookCipher} 存密文，与 {@code provider.api_key_cipher} 同一口径；
 * 控制台只回「配没配」，不回内容。
 */
@Data
@TableName("notify_config")
public class NotifyConfigEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String wecomWebhookCipher;
    /** 异常类：run-failed / login-lost / circuit */
    private Integer alertAbnormal;
    /** 进入待命 */
    private Integer alertStandby;
    /** 值守时段结束 */
    private Integer alertDutyEnd;
    private String updatedAt;
}
