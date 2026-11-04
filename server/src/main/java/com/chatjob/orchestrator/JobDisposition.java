package com.chatjob.orchestrator;

import com.chatjob.evaluate.ActiveLevel;
import com.chatjob.evaluate.AiJudge;
import com.chatjob.evaluate.BlacklistMatcher;
import com.chatjob.platform.Models.DeliveryOptions;
import com.chatjob.platform.Models.DeliveryResult;
import com.chatjob.platform.Models.JobDetail;
import com.chatjob.platform.Models.SearchQuery;
import com.chatjob.platform.PlatformAdapter;
import com.chatjob.runlog.RunLog;
import com.chatjob.runlog.RunLogs;
import com.chatjob.scan.ScanBoundary;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.DeliveryEntity;
import com.chatjob.store.entity.JobPostingEntity;
import com.chatjob.store.entity.PlatformConfigEntity;
import com.chatjob.store.entity.RunEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个岗位怎么被处置（CONTEXT.md「处置」「投递判定」「投递」）。
 *
 * <p>它是扫描段与「判定 + 投递」之间的那一段：黑名单 → HR 活跃度下限 → 冷却与已投递 →
 * AI 判定 → 演练 → 投递。顺序是这里唯一重要的东西，而顺序恰恰是原来最难验证的 ——
 * 改造前它是一个 121 行的私有方法，七个早退混着四类副作用（落投递表、改计数、推 SSE、写日志），
 * 除了起一整轮真机，没有任何办法回答「AI 失败到底记的是 FAILED 还是 SKIPPED」。
 *
 * <p><b>本类不拥有</b>：抓哪张卡、什么时候停（{@link ScanBoundary}）、投不投（{@link AiJudge}）、
 * 投递动作本身（{@link PlatformAdapter}）。它只负责把它们按固定顺序串起来，并把结果落进
 * {@link JobStore} 与 {@link RunStore}。运行状态（动作、SSE）走 {@link RunControl}，
 * 与两条循环同一个出口。
 *
 * <p><b>它实现 {@link JobProcessor} 是为了让扫描段看不见这些东西</b>：扫描段的构造依赖里
 * 没有 {@code JobStore} / {@code AiJudge} / {@code BlacklistMatcher}，那三样要真库与真网络，
 * 进来之后循环就只能在整机跑起来时测得了（ADR-0009 3b 补记第 3 条）。
 */
final class JobDisposition implements JobProcessor {

    private final RunEntity run;
    private final PlatformAdapter adapter;
    private final ConfigStore configStore;
    private final RunStore runStore;
    private final JobStore jobStore;
    private final AiJudge aiJudge;
    private final RunLogs runLogs;
    private final RunControl host;

    JobDisposition(RunEntity run, PlatformAdapter adapter, ConfigStore configStore, RunStore runStore,
                   JobStore jobStore, AiJudge aiJudge, RunLogs runLogs, RunControl host) {
        this.run = run;
        this.adapter = adapter;
        this.configStore = configStore;
        this.runStore = runStore;
        this.jobStore = jobStore;
        this.aiJudge = aiJudge;
        this.runLogs = runLogs;
        this.host = host;
    }

    /**
     * 走完一个岗位。
     *
     * @param cfg   本组合开始时的平台配置快照（配额、活跃度下限、重复沟通开关）
     * @param query 这张卡所属的搜索组合。投递时随 {@link DeliveryOptions} 一起交给适配器，
     *              用来拼"详情页的第 1 跳"那个列表页地址（spec 0006 §3.5）
     */
    @Override
    public void process(PlatformConfigEntity cfg, JobDetail detail, SearchQuery query) {
        RunLog runLog = runLogs.of(run);
        BlacklistMatcher blacklist = new BlacklistMatcher(configStore.blacklist());
        var hit = blacklist.match(detail.companyName(), detail.jobName());
        if (hit.isPresent()) {
            BlacklistMatcher.Hit h = hit.get();
            runLog.info("丢弃（黑名单「" + h.keyword() + "」）：" + detail.companyName()
                    + " / " + detail.jobName());
            return;
        }

        ActiveLevel level = ActiveLevel.of(detail.activeRaw());
        ActiveLevel floor = ActiveLevel.fromLabel(cfg.getActiveLevelFloor());
        if (ActiveLevel.isBelowFloor(level, floor)) {
            runLog.info("丢弃（HR 活跃度 " + level.label() + " 低于下限 "
                    + cfg.getActiveLevelFloor() + "）：" + detail.jobName());
            return;
        }

        JobPostingEntity job = jobStore.upsert(detail, run.getPlatform(), level.label());

        JobStore.Decision decision = jobStore.decide(job.getId());
        if (decision.skip()) {
            runLog.info("跳过（" + decision.reason() + "）：" + job.getJobName() + " @ " + job.getCompanyName());
            return;
        }

        runStore.incrementEvaluated(run.getPlatform());
        run.setEvaluatedCount((run.getEvaluatedCount() == null ? 0 : run.getEvaluatedCount()) + 1);

        boolean qualityGate = configStore.qualityGateEnabled();
        String greeting;
        String decidedBy;
        Integer score = null;
        String aiReason = null;
        String aiRaw = null;

        if (qualityGate) {
            host.act("AI 正在判断：" + job.getJobName() + " @ " + job.getCompanyName());
            AiJudge.Verdict verdict = aiJudge.judge(detail, blacklist);
            if (verdict.isFailure()) {
                // fail-closed：判定不出来就不投
                DeliveryEntity delivery = jobStore.startDelivery(job.getId(), run.getId(), "AI");
                // 记 FAILED 而不是 SKIPPED：SKIPPED 在 JobStore.decide 里意味着"已判定不投"，
                // 会给该岗位打 24 小时冷却。而这里根本没有判定结论 —— 模型不可达、返回坏了，
                // 都被冷却吸收的话，一次模型故障会让整批岗位一天内不再尝试，且看起来像"AI 认为不合适"。
                // 记成 FAILED 则下一轮会重新评估，连续失败达到上限后转人工。
                jobStore.finishDelivery(delivery, DeliveryResult.failed("AI 判定失败：" + verdict.error()),
                        null, verdict.error(), null);
                host.publish("job.evaluated", jobEvent(job, "AI_FAILED", 0, verdict.error()));
                runLog.warn("AI 判定失败，本轮不投（下轮重试）：" + job.getJobName() + " — " + verdict.error());
                return;
            }
            score = verdict.score();
            aiReason = verdict.reason();
            aiRaw = verdict.raw();
            if (!verdict.shouldApply()) {
                DeliveryEntity delivery = jobStore.startDelivery(job.getId(), run.getId(), "AI");
                jobStore.finishDelivery(delivery, DeliveryResult.skipped("AI 判定不投：" + verdict.reason()),
                        score, aiReason, aiRaw);
                host.publish("job.evaluated", jobEvent(job, "AI_REJECT", score, verdict.reason()));
                runLog.info("AI 判定不投（" + score + " 分）：" + job.getJobName() + " — " + verdict.reason());
                return;
            }
            greeting = verdict.greeting();
            decidedBy = "AI";
        } else {
            greeting = configStore.aiConfig().getTemplateGreeting();
            decidedBy = "TEMPLATE";
            if (greeting == null || greeting.isBlank()) {
                runLog.warn("质量开关已关闭但模板招呼语为空，跳过");
                return;
            }
        }

        host.publish("job.evaluated", jobEvent(job, "PASS", score == null ? 0 : score, aiReason));

        if ("DRY_RUN".equals(run.getMode())) {
            DeliveryEntity delivery = jobStore.startDelivery(job.getId(), run.getId(), decidedBy);
            jobStore.finishDelivery(delivery, DeliveryResult.skipped("演练模式，未实际投递"),
                    score, aiReason, aiRaw);
            runLog.info("演练模式：本会投递 " + job.getJobName() + " @ " + job.getCompanyName());
            return;
        }

        DeliveryEntity delivery = jobStore.startDelivery(job.getId(), run.getId(), decidedBy);
        boolean allowRepeat = cfg.getRepeatChatEnabled() != null && cfg.getRepeatChatEnabled() == 1;
        DeliveryOptions options = new DeliveryOptions(allowRepeat, configStore.repeatMessages(cfg), query);
        host.act("投递中：" + job.getJobName() + " @ " + job.getCompanyName());
        DeliveryResult result = adapter.deliver(detail, greeting, options, host::stopRequested);
        jobStore.finishDelivery(delivery, result, score, aiReason, aiRaw);

        host.publish("delivery.updated", Map.of(
                "jobId", job.getId(),
                "jobName", job.getJobName(),
                "company", job.getCompanyName(),
                "status", result.status().name(),
                "failureReason", result.failureReason() == null ? "" : result.failureReason()));

        if (result.status() == DeliveryResult.Status.DELIVERED) {
            runStore.incrementDelivered(run.getPlatform());
            run.setDeliveredCount((run.getDeliveredCount() == null ? 0 : run.getDeliveredCount()) + 1);
            runLog.info("已投递（" + run.getDeliveredCount() + "/" + ScanBoundary.quotaOf(cfg) + "）："
                    + job.getJobName() + " @ " + job.getCompanyName());
        } else if (result.status() == DeliveryResult.Status.SKIPPED) {
            runLog.info("未投递（" + result.failureReason() + "）：" + job.getJobName());
        } else {
            runLog.warn("投递失败（" + result.failureReason() + "）：" + job.getJobName());
        }
    }

    private Map<String, Object> jobEvent(JobPostingEntity job, String decision, int score, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", job.getId());
        data.put("jobName", job.getJobName());
        data.put("company", job.getCompanyName());
        data.put("decision", decision);
        data.put("score", score);
        data.put("reason", reason == null ? "" : reason);
        return data;
    }
}
