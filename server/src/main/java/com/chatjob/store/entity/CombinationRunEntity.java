package com.chatjob.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 一个搜索组合（城市 × 关键词）的执行记录。队列持久化，重启后从上次位置继续。 */
@Data
@TableName("combination_run")
public class CombinationRunEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Integer seq;
    private String city;
    private String keyword;
    /**
     * PENDING|RUNNING|DONE|SKIPPED|FAILED|INTERRUPTED。
     *
     * <p>INTERRUPTED 只在一种情况下出现：进程被强杀时组合正跑到一半，重启收尾
     * （{@code RunStore.abandonRunningCombinations}）把它从 RUNNING 改写成这个值。
     * 与 SKIPPED 区分开，是因为 SKIPPED 是计划内的收手（配额到顶、值守到点）。
     */
    private String status;
    private Integer cardsSeen;
    private Integer evaluated;
    private Integer delivered;
    private String startedAt;
    private String finishedAt;
}
