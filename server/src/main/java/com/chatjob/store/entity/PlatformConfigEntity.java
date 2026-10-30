package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 平台配置，每平台单行。
 * 筛选类字段存的是中文名称的 JSON 数组（配置层面向人），执行时才换 code。
 */
@Data
@TableName("platform_config")
public class PlatformConfigEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;
    private Integer enabled;
    private String cities;
    private String keywords;
    private String jobType;
    private String salary;
    private String experience;
    private String degree;
    private String scale;
    private String industry;
    private String stage;
    private String activeLevelFloor;
    private Integer waitTimeSeconds;
    private Integer dailyDeliveryQuota;
    private Integer dailyEvalLimit;
    private Integer scrollRoundLimit;
    private Integer combinationTimeLimitSec;
    /** 值守时一轮最多读多少个会话项。原先写死 30，超出部分静默漏扫（V6） */
    private Integer unreadScanLimit;
    /**
     * 值守待命期的巡检间隔（秒）。它同时是「AI 回复的最长延迟」和「页面被刷新的频率」，
     * 所以可配：120 是延迟与风控的折中，实际间隔会在 ±10% 内抖动（V12）。
     */
    private Integer watchIntervalSeconds;
    private Integer repeatChatEnabled;
    private String repeatChatMessages;
    private Integer resumeImageEnabled;
    /** 值守时段，HH:mm。窗口外一律不发消息、也不会长时间运行；两值相同表示不限。它不拦启动 */
    private String dutyStartTime;
    private String dutyEndTime;
    private String updatedAt;
}
