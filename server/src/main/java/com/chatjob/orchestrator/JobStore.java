package com.chatjob.orchestrator;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chatjob.platform.Models.DeliveryResult;
import com.chatjob.platform.Models.JobDetail;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.DeliveryEntity;
import com.chatjob.store.entity.JobPostingEntity;
import com.chatjob.store.mapper.DeliveryMapper;
import com.chatjob.store.mapper.JobPostingMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 岗位与投递的持久化，以及「这个岗位还要不要处理」的判定。
 *
 * <p>去重锚定岗位（platform_job_id），但不只看"投没投过"：
 * <ul>
 *   <li>已投递 / 投递中 → 跳过</li>
 *   <li>失败且已重试过一次 → 转人工，不再自动投（同一岗位连发三次是封号主因）</li>
 *   <li>AI 判定不通过、24 小时内 → 跳过（省 token），超过 24 小时允许重新评估</li>
 * </ul>
 */
@Slf4j
@Component
public class JobStore {

    public static final String STATUS_DELIVERING = "DELIVERING";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_MANUAL = "MANUAL";

    /** 自动重试上限：失败 1 次后允许再试 1 次，之后转人工 */
    private static final int MAX_AUTO_ATTEMPTS = 2;
    /** AI 判定不通过的冷却时间 */
    private static final int SKIP_COOLDOWN_HOURS = 24;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final JobPostingMapper jobMapper;
    private final DeliveryMapper deliveryMapper;

    public JobStore(JobPostingMapper jobMapper, DeliveryMapper deliveryMapper) {
        this.jobMapper = jobMapper;
        this.deliveryMapper = deliveryMapper;
    }

    /** @param reason 非空表示跳过，原因直接展示在控制台 */
    public record Decision(boolean skip, String reason) {
        public static Decision proceed() {
            return new Decision(false, null);
        }

        public static Decision skip(String reason) {
            return new Decision(true, reason);
        }
    }

    public JobPostingEntity upsert(JobDetail detail, String platform, String activeLevel) {
        JobPostingEntity existing = jobMapper.selectOne(Wrappers.<JobPostingEntity>lambdaQuery()
                .eq(JobPostingEntity::getPlatform, platform)
                .eq(JobPostingEntity::getPlatformJobId, detail.platformJobId()));
        String now = ConfigStore.now();
        if (existing == null) {
            JobPostingEntity entity = new JobPostingEntity();
            entity.setPlatform(platform);
            entity.setPlatformJobId(detail.platformJobId());
            fill(entity, detail, activeLevel);
            entity.setFirstSeenAt(now);
            entity.setLastSeenAt(now);
            jobMapper.insert(entity);
            return entity;
        }
        fill(existing, detail, activeLevel);
        existing.setLastSeenAt(now);
        jobMapper.updateById(existing);
        return existing;
    }

    private void fill(JobPostingEntity entity, JobDetail detail, String activeLevel) {
        entity.setJobName(nvl(detail.jobName()));
        entity.setCompanyName(nvl(detail.companyName()));
        entity.setSalary(nvl(detail.salary()));
        entity.setArea(nvl(detail.area()));
        entity.setExperience(nvl(detail.experience()));
        entity.setDegree(nvl(detail.degree()));
        entity.setDescription(nvl(detail.description()));
        entity.setHrName(nvl(detail.hrName()));
        entity.setHrTitle(nvl(detail.hrTitle()));
        entity.setActiveLevel(nvl(activeLevel));
        entity.setActiveRaw(nvl(detail.activeRaw()));
        entity.setRawJson(detail.rawJson());
    }

    public Decision decide(Long jobId) {
        List<DeliveryEntity> deliveries = deliveryMapper.selectList(
                Wrappers.<DeliveryEntity>lambdaQuery()
                        .eq(DeliveryEntity::getJobPostingId, jobId)
                        .orderByDesc(DeliveryEntity::getId));
        if (deliveries.isEmpty()) {
            return Decision.proceed();
        }
        DeliveryEntity latest = deliveries.get(0);
        if (STATUS_DELIVERED.equals(latest.getStatus()) || STATUS_DELIVERING.equals(latest.getStatus())) {
            return Decision.skip("已投递");
        }
        if (STATUS_MANUAL.equals(latest.getStatus())) {
            return Decision.skip("已转人工处理");
        }
        long attempts = deliveries.stream()
                .filter(d -> STATUS_FAILED.equals(d.getStatus()))
                .count();
        if (STATUS_FAILED.equals(latest.getStatus()) && attempts >= MAX_AUTO_ATTEMPTS) {
            return Decision.skip("已失败 " + attempts + " 次，转人工");
        }
        if (STATUS_SKIPPED.equals(latest.getStatus()) && withinHours(latest.getFinishedAt(),
                SKIP_COOLDOWN_HOURS)) {
            return Decision.skip("24 小时内已判定不投");
        }
        return Decision.proceed();
    }

    private boolean withinHours(String timestamp, int hours) {
        if (timestamp == null) {
            return false;
        }
        try {
            return LocalDateTime.parse(timestamp, TS).isAfter(LocalDateTime.now().minusHours(hours));
        } catch (Exception e) {
            return false;
        }
    }

    public int attemptCount(Long jobId) {
        Long count = deliveryMapper.selectCount(Wrappers.<DeliveryEntity>lambdaQuery()
                .eq(DeliveryEntity::getJobPostingId, jobId));
        return count == null ? 0 : count.intValue();
    }

    public DeliveryEntity startDelivery(Long jobId, Long runId, String decidedBy) {
        DeliveryEntity entity = new DeliveryEntity();
        entity.setJobPostingId(jobId);
        entity.setRunId(runId);
        entity.setAttemptNo(attemptCount(jobId) + 1);
        entity.setStatus(STATUS_DELIVERING);
        entity.setDecidedBy(decidedBy);
        entity.setStartedAt(ConfigStore.now());
        deliveryMapper.insert(entity);
        return entity;
    }

    public void finishDelivery(DeliveryEntity delivery, DeliveryResult result,
                               Integer score, String aiReason, String aiRaw) {
        delivery.setStatus(switch (result.status()) {
            case DELIVERED -> STATUS_DELIVERED;
            case FAILED -> STATUS_FAILED;
            case SKIPPED -> STATUS_SKIPPED;
        });
        delivery.setGreeting(nvl(result.greeting()));
        delivery.setFailureReason(result.failureReason());
        delivery.setScore(score);
        delivery.setAiReason(aiReason);
        delivery.setAiRaw(aiRaw);
        delivery.setFinishedAt(ConfigStore.now());
        deliveryMapper.updateById(delivery);
    }

    public DeliveryEntity markManual(Long deliveryId) {
        DeliveryEntity entity = deliveryMapper.selectById(deliveryId);
        if (entity != null) {
            entity.setStatus(STATUS_MANUAL);
            deliveryMapper.updateById(entity);
        }
        return entity;
    }

    public List<DeliveryEntity> deliveriesOfJob(Long jobId) {
        return deliveryMapper.selectList(Wrappers.<DeliveryEntity>lambdaQuery()
                .eq(DeliveryEntity::getJobPostingId, jobId)
                .orderByDesc(DeliveryEntity::getId));
    }

    public List<DeliveryEntity> recentDeliveries(int limit) {
        return deliveryMapper.selectList(Wrappers.<DeliveryEntity>lambdaQuery()
                .orderByDesc(DeliveryEntity::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    public JobPostingEntity job(Long id) {
        return jobMapper.selectById(id);
    }

    /** 清理：只删「未投递 且 超期」的扫描明细，否则去重记录被清掉会导致重复投递 */
    public int cleanScannedOlderThan(int days) {
        String cutoff = LocalDateTime.now().minusDays(days).format(TS);
        List<JobPostingEntity> candidates = jobMapper.selectList(
                Wrappers.<JobPostingEntity>lambdaQuery()
                        .lt(JobPostingEntity::getLastSeenAt, cutoff));
        int removed = 0;
        for (JobPostingEntity job : candidates) {
            boolean delivered = deliveryMapper.selectCount(Wrappers.<DeliveryEntity>lambdaQuery()
                    .eq(DeliveryEntity::getJobPostingId, job.getId())
                    .in(DeliveryEntity::getStatus, List.of(STATUS_DELIVERED, STATUS_DELIVERING,
                            STATUS_FAILED, STATUS_MANUAL))) > 0;
            if (delivered) {
                continue;
            }
            deliveryMapper.delete(Wrappers.<DeliveryEntity>lambdaQuery()
                    .eq(DeliveryEntity::getJobPostingId, job.getId())
                    .eq(DeliveryEntity::getStatus, STATUS_SKIPPED));
            jobMapper.deleteById(job.getId());
            removed++;
        }
        return removed;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
