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
    /** CREATED|WAITING_DUTY|RUNNING|PAUSED|STANDBY|COMPLETED|QUOTA_REACHED|CIRCUIT_OPEN|LOGIN_LOST|CANCELLED|FAILED */
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
    /**
     * 一句人话的「现在在干什么」，例：等待 7 秒后处理第 13 张卡 / 待命巡检：读取消息列表。
     *
     * <p>不落日志表：卡片间隔若每张卡打两行日志，跑一天就是几百行噪音，
     * 而这些信息在界面上只是「同一个格子的值」。界面靠它区分「在等」和「卡死」。
     */
    private String currentAction;
    /** 每次动作、每次巡检轮开始、每次进入等待时刷新；停滞判定用它算「多久没动了」 */
    private String lastActivityAt;
    /** 当前扫描所属自然日（yyyy-MM-dd）。跨日恢复扫描时靠它判断要不要复位组合队列 */
    private String scanDay;
}
