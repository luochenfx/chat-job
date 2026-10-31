package com.chatjob.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chatjob.evaluate.BlacklistMatcher;
import com.chatjob.evaluate.TextNormalizer;
import com.chatjob.orchestrator.JobStore;
import com.chatjob.platform.PlatformInfo;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.BlacklistEntity;
import com.chatjob.store.entity.DeliveryEntity;
import com.chatjob.store.entity.JobPostingEntity;
import com.chatjob.store.mapper.DeliveryMapper;
import com.chatjob.store.mapper.JobPostingMapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class JobController {

    private final JobPostingMapper jobMapper;
    private final DeliveryMapper deliveryMapper;
    private final JobStore jobStore;
    private final ConfigStore configStore;

    public JobController(JobPostingMapper jobMapper, DeliveryMapper deliveryMapper,
                         JobStore jobStore, ConfigStore configStore) {
        this.jobMapper = jobMapper;
        this.deliveryMapper = deliveryMapper;
        this.jobStore = jobStore;
        this.configStore = configStore;
    }

    /**
     * 岗位列表，带上最新一次投递状态与判定理由，控制台一屏能看完。
     *
     * <p>{@code platform} 不传 = <b>全部平台</b>。岗位页是审阅用途，用户想看的是
     * 「两个平台一共扫到了什么」；默认只回一个平台，会让人以为另一个平台没扫到岗位。
     * 也正因为默认是全部，每条都必须带平台名（{@code platformLabel}），否则分不清归属。
     *
     * <p>参数是 slug（{@code boss} / {@code zhaopin}），不接受中文名 —— 中文名是展示层的事，
     * 拿它当查询条件会在改文案时把接口一起改坏。
     */
    @GetMapping("/jobs")
    public List<Map<String, Object>> jobs(@RequestParam(defaultValue = "100") int limit,
                                          @RequestParam(required = false) String platform) {
        String scope = platform == null || platform.isBlank()
                ? null : platform.trim().toLowerCase(Locale.ROOT);
        List<JobPostingEntity> jobs = jobMapper.selectList(Wrappers.<JobPostingEntity>lambdaQuery()
                .eq(scope != null, JobPostingEntity::getPlatform, scope)
                .orderByDesc(JobPostingEntity::getLastSeenAt)
                .last("LIMIT " + Math.clamp(limit, 1, 500)));
        List<Map<String, Object>> out = new ArrayList<>(jobs.size());
        for (JobPostingEntity job : jobs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", job.getId());
            item.put("platform", job.getPlatform());
            item.put("platformLabel", PlatformInfo.of(job.getPlatform()).label());
            item.put("jobName", job.getJobName());
            item.put("companyName", job.getCompanyName());
            item.put("salary", job.getSalary());
            item.put("area", job.getArea());
            item.put("activeLevel", job.getActiveLevel());
            item.put("activeRaw", job.getActiveRaw());
            item.put("hrName", job.getHrName());
            item.put("hrTitle", job.getHrTitle());
            item.put("lastSeenAt", job.getLastSeenAt());
            DeliveryEntity latest = latestDelivery(job.getId());
            if (latest != null) {
                item.put("deliveryStatus", latest.getStatus());
                // 处置时间：以 finishedAt 为准 —— 它是结果落定的那一刻。两个兜底都要，
                // 否则列会莫名空着：startDelivery 只写 startedAt（扫到一半崩掉的「投递中」
                // 就只有它），markManual 只改状态不写时间（部分「需人工」也只有它）。
                item.put("disposedAt", latest.getFinishedAt() != null
                        ? latest.getFinishedAt() : latest.getStartedAt());
                item.put("deliveryReason", latest.getFailureReason());
                item.put("aiReason", latest.getAiReason());
                item.put("score", latest.getScore());
                item.put("decidedBy", latest.getDecidedBy());
                item.put("greeting", latest.getGreeting());
            }
            out.add(item);
        }
        return out;
    }

    private DeliveryEntity latestDelivery(Long jobId) {
        List<DeliveryEntity> list = deliveryMapper.selectList(Wrappers.<DeliveryEntity>lambdaQuery()
                .eq(DeliveryEntity::getJobPostingId, jobId)
                .orderByDesc(DeliveryEntity::getId)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    @GetMapping("/jobs/{id}/deliveries")
    public List<DeliveryEntity> deliveries(@PathVariable Long id) {
        return jobStore.deliveriesOfJob(id);
    }

    /**
     * 人工重投：把该岗位的失败/跳过记录挪出自动决策视野，下次扫描会重新评估。
     * 不做"直接重发"，因为那等于绕过风控节奏。
     */
    @PostMapping("/jobs/{id}/reset")
    public Map<String, Object> reset(@PathVariable Long id) {
        int removed = deliveryMapper.delete(Wrappers.<DeliveryEntity>lambdaQuery()
                .eq(DeliveryEntity::getJobPostingId, id)
                .in(DeliveryEntity::getStatus, List.of(JobStore.STATUS_FAILED,
                        JobStore.STATUS_SKIPPED, JobStore.STATUS_MANUAL)));
        return Map.of("ok", true, "removed", removed);
    }

    // ---------------------------------------------------------------- 黑名单

    @GetMapping("/blacklist")
    public List<BlacklistEntity> blacklist() {
        return configStore.blacklist();
    }

    public record BlacklistBody(String kind, String keyword) {
    }

    @PostMapping("/blacklist")
    public Map<String, Object> addBlacklist(@RequestBody BlacklistBody body) {
        String kind = "job".equals(body.kind()) ? "job" : "company";
        if (body.keyword() == null || body.keyword().isBlank()) {
            throw new IllegalArgumentException("关键词不能为空");
        }
        if (!BlacklistMatcher.isValidKeyword(body.keyword())) {
            throw new IllegalArgumentException("关键词至少需要 "
                    + BlacklistMatcher.MIN_KEYWORD_LENGTH + " 个字符（太短会误杀）");
        }
        boolean added = configStore.addBlacklist(kind, body.keyword().trim(),
                TextNormalizer.normalize(body.keyword()));
        return Map.of("ok", true, "added", added);
    }

    @DeleteMapping("/blacklist/{id}")
    public Map<String, Object> deleteBlacklist(@PathVariable Long id) {
        configStore.removeBlacklist(id);
        return Map.of("ok", true);
    }
}
