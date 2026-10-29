package com.chatjob.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chatjob.evaluate.BlacklistMatcher;
import com.chatjob.evaluate.TextNormalizer;
import com.chatjob.orchestrator.JobStore;
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

    /** 岗位列表，带上最新一次投递状态与判定理由，控制台一屏能看完 */
    @GetMapping("/jobs")
    public List<Map<String, Object>> jobs(@RequestParam(defaultValue = "100") int limit) {
        List<JobPostingEntity> jobs = jobMapper.selectList(Wrappers.<JobPostingEntity>lambdaQuery()
                .orderByDesc(JobPostingEntity::getLastSeenAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 500))));
        List<Map<String, Object>> out = new ArrayList<>(jobs.size());
        for (JobPostingEntity job : jobs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", job.getId());
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
