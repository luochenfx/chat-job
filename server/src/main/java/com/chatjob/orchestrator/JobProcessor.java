package com.chatjob.orchestrator;

import com.chatjob.platform.Models.JobDetail;
import com.chatjob.platform.Models.SearchQuery;

import com.chatjob.store.entity.PlatformConfigEntity;
import com.chatjob.store.entity.RunEntity;

/**
 * 扫描段处理单张卡片的方式。
 *
 * <p>它是扫描段与「判定 + 投递」之间唯一的接缝。存在的理由只有一个：把 {@code JobStore}、
 * {@code AiJudge}、{@code BlacklistMatcher} 挡在扫描段的构造依赖之外 —— 那三样都要真库与
 * 真网络，让它们进来，扫描段的循环就只能在整机跑起来之后才测得了。
 *
 * <p>返回 {@code void}：卡片处理的结果（评估 / 投递计数）落在 {@code RunEntity} 与库里，
 * 由扫描段按处理前后的差值记账。这里再返回一份统计就是第二份真相。
 */
@FunctionalInterface
public interface JobProcessor {

    /**
     * @param run   本轮 Run，处理过程中会累加 {@code evaluatedCount} / {@code deliveredCount}
     * @param cfg   本组合开始时的平台配置快照
     * @param detail 卡片详情
     * @param query  这张卡所属的搜索组合，投递时用来拼「从列表页进详情页」的第一跳
     */
    void process(RunEntity run, PlatformConfigEntity cfg, JobDetail detail, SearchQuery query);
}
