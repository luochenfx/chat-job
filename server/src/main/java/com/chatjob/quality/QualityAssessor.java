package com.chatjob.quality;

import com.chatjob.evaluate.QualityJudge;
import com.chatjob.notify.AlertCategory;
import com.chatjob.notify.Notifier;
import com.chatjob.notify.NotifySettings;
import com.chatjob.platform.Models.ChatMessage;
import com.chatjob.platform.PlatformInfo;
import com.chatjob.runlog.RunLog;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.ConversationEntity;
import com.chatjob.store.entity.JobPostingEntity;
import com.chatjob.store.entity.QualityAssessmentEntity;
import com.chatjob.store.mapper.JobPostingMapper;
import com.chatjob.time.TimeSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 岗位质量评估的编排：什么时候判、判完做什么（ADR-0015）。
 *
 * <p>位置在值守巡检里，会话处理完之后。判断不需要再打开页面 ——
 * {@code readMessages} 的结果就在调用方手上。
 *
 * <p>五个刻意的决定：
 * <ul>
 *   <li><b>首次门槛是「HR 至少发了 N 条」。</b> 六条判断点里两条只能看对话，
 *       对方只说了一个「你好」时判出来的分数没有意义。</li>
 *   <li><b>重判要「多 3 条 + 隔 6 小时」。</b> 只看条数会让一句「在吗」就触发一次调用；
 *       只看时间会让同一段对话被反复判同一个结论。</li>
 *   <li><b>每天单独一个名额（默认 30）。</b> 不共用投递判定的 150 ——
 *       共用会让质量判断挤掉投递名额，而投递才是主链。</li>
 *   <li><b>同一段会话只提醒一次。</b> 分数跌下去再升上来也不再发：反复提醒比不提醒更烦。</li>
 *   <li><b>失败什么都不写。</b> 判断失败不落一行「0 分」的记录 ——
 *       那会让一个没判成的会话出现在列表底部，看起来像「它很差」。</li>
 * </ul>
 */
@Slf4j
@Component
public class QualityAssessor {

    /** 重判要求「比上次多几条 HR 消息」。3 条约等于一次往返加一句追问 */
    static final int REEVAL_MIN_NEW_MESSAGES = 3;

    /** 列表一次最多回多少行。重点关注是给人看的清单，不是全量导出 */
    private static final int LIST_LIMIT = 200;

    private final ConfigStore configStore;
    private final QualityJudge judge;
    private final QualityStore store;
    private final JobPostingMapper jobMapper;
    private final Notifier notifier;
    private final NotifySettings notifySettings;
    private final ObjectMapper json;
    private final TimeSource time;

    public QualityAssessor(ConfigStore configStore, QualityJudge judge, QualityStore store,
                           JobPostingMapper jobMapper, Notifier notifier, NotifySettings notifySettings,
                           ObjectMapper json, TimeSource time) {
        this.configStore = configStore;
        this.judge = judge;
        this.store = store;
        this.jobMapper = jobMapper;
        this.notifier = notifier;
        this.notifySettings = notifySettings;
        this.json = json;
        this.time = time;
    }

    /**
     * 本轮新产生的评估，交给调用方去推 SSE。{@code notified} 表示这次是不是刚发了提醒。
     *
     * @param title 会话标题（= HR 名字）。界面上「谁在聊」靠它
     */
    public record Assessed(Long conversationId, String platform, String title, String jobName,
                           String companyName, int score, String summary, boolean notified) {
    }

    /**
     * 单轮的判断预算，形状与 {@code UnreadWatcher.AiBudget} 一致。
     *
     * <p>用完不是失败，只是「下一轮再说」—— 用失败表示会让这个会话在三次之后被标成放弃，
     * 而它一次错都没犯。
     */
    public static final class Budget {
        private final int max;
        private int used;

        public Budget(int max) {
            this.max = Math.max(1, max);
        }

        boolean tryUse() {
            if (used >= max) {
                return false;
            }
            used++;
            return true;
        }

        public int max() {
            return max;
        }
    }

    /** 一轮巡检一个预算，由编排层创建并透传 */
    public Budget budget() {
        return new Budget(configStore.qualityMaxPerSweep());
    }

    /**
     * 到条件就判一次，否则什么都不做。
     *
     * <p>任何异常都在这里吞掉：质量评估是附加能力，不能让它把值守或扫描主链带下水
     * （与 {@code UnreadWatcher.sweep} 同一口径）。
     *
     * @return 这一次真的判了才有返回值
     */
    public Assessed assessIfDue(ConversationEntity conversation, List<ChatMessage> messages,
                                Budget budget, RunLog runLog) {
        if (conversation == null || conversation.getId() == null) {
            return null;
        }
        // 平台与标题都从会话本身取：调用方手上那两个值就是从它来的，
        // 传进来只会多一条「万一不一致该信谁」的问题
        String platform = conversation.getPlatform();
        String title = conversation.getPlatformConversationId();
        try {
            int hrMessages = 0;
            for (ChatMessage m : messages == null ? List.<ChatMessage>of() : messages) {
                if (!m.fromSelf()) {
                    hrMessages++;
                }
            }
            QualityAssessmentEntity latest = store.latest(conversation.getId());
            if (!due(hrMessages, latest == null ? null : latest.getHrMessages(),
                    latest == null ? null : latest.getAssessedAt(),
                    configStore.qualityMinHrMessages(), configStore.qualityReevalHours(), time)) {
                return null;
            }
            if (dailyUsedUp(platform)) {
                runLog.info("今日岗位质量判断已达上限 " + configStore.qualityMaxPerDay()
                        + " 次，这段会话留到明天 | 会话=" + title);
                return null;
            }
            if (!budget.tryUse()) {
                runLog.info("本轮岗位质量判断已达上限 " + budget.max() + " 次 | 会话=" + title);
                return null;
            }

            QualityLookup lookup = lookup(platform, title);
            QualityJudge.Snapshot snapshot = lookup.snapshot();
            List<String> criteria = configStore.qualityCriteria();
            QualityJudge.Assessment result = judge.assess(snapshot, messages, criteria);
            if (result.isFailure()) {
                runLog.warn("岗位质量判断失败（不落库，下一轮再试）| 会话=" + title
                        + " | " + result.error());
                return null;
            }

            QualityAssessmentEntity entity = persist(platform, conversation, lookup, result, hrMessages);
            boolean notified = notifyIfHot(platform, entity, conversation);

            runLog.info("岗位质量 " + result.score() + " 分 | 会话=" + title
                    + " | 岗位=" + nvl(snapshot.jobName(), "（未取到）")
                    + " | 过线=" + (result.score() >= configStore.qualityScoreLine())
                    + " | 提醒=" + (notified ? "已发" : "无")
                    + " | " + nvl(result.summary(), ""));
            return new Assessed(conversation.getId(), platform, title,
                    nvl(entity.getJobName(), ""), nvl(entity.getCompanyName(), ""),
                    result.score(), nvl(result.summary(), ""), notified);
        } catch (Exception e) {
            runLog.warn("岗位质量判断出错（不影响值守）| 会话=" + title + " | "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 触发判据（纯函数，便于单独断言）。
     *
     * @param hrMessages   这次读到几条 HR 消息
     * @param lastHrMessages 上次判断时几条；{@code null} 表示从没判过
     * @param lastAssessedAt 上次判断的时刻；解析不出来按「很久以前」处理
     */
    static boolean due(int hrMessages, Integer lastHrMessages, String lastAssessedAt,
                       int minHrMessages, int reevalHours, TimeSource time) {
        if (hrMessages < Math.max(1, minHrMessages)) {
            return false;
        }
        if (lastHrMessages == null) {
            return true;
        }
        if (hrMessages < lastHrMessages + REEVAL_MIN_NEW_MESSAGES) {
            return false;
        }
        if (reevalHours <= 0) {
            return true;
        }
        LocalDateTime last = lastAssessedAt == null ? null : time.parse(lastAssessedAt).orElse(null);
        if (last == null) {
            // 时间读不出来时偏向「再判一次」：多花一次调用，好过这段会话从此不再被评估
            return true;
        }
        return !last.plusHours(reevalHours).isAfter(time.now());
    }

    private boolean dailyUsedUp(String platform) {
        LocalDate today = LocalDate.parse(time.today());
        String from = time.format(today.atStartOfDay());
        String to = time.format(today.plusDays(1).atStartOfDay());
        return store.countOnDay(platform, from, to) >= configStore.qualityMaxPerDay();
    }

    private QualityLookup lookup(String platform, String title) {
        try {
            JobPostingEntity job = jobMapper.latestDeliveredByHr(platform, title);
            if (job == null) {
                return QualityLookup.empty();
            }
            return new QualityLookup(job.getId(), new QualityJudge.Snapshot(
                    nvl(job.getJobName(), ""), nvl(job.getCompanyName(), ""), nvl(job.getSalary(), ""),
                    nvl(job.getArea(), ""), nvl(job.getExperience(), ""), nvl(job.getDegree(), ""),
                    nvl(job.getDescription(), "")));
        } catch (Exception e) {
            log.warn("按 HR 名字反查岗位失败（这次只看对话）| 会话={} | {}", title, e.getMessage());
            return QualityLookup.empty();
        }
    }

    /**
     * 反查结果。
     *
     * @param jobPostingId 反查不到时为 {@code null}。界面靠它区分「没有岗位信息」与「岗位名恰好为空」
     */
    private record QualityLookup(Long jobPostingId, QualityJudge.Snapshot snapshot) {
        static QualityLookup empty() {
            return new QualityLookup(null, QualityJudge.Snapshot.empty());
        }
    }

    private QualityAssessmentEntity persist(String platform, ConversationEntity conversation,
                                            QualityLookup lookup,
                                            QualityJudge.Assessment result, int hrMessages) {
        QualityJudge.Snapshot snapshot = lookup.snapshot();
        QualityAssessmentEntity entity = new QualityAssessmentEntity();
        entity.setPlatform(platform);
        entity.setConversationId(conversation.getId());
        entity.setJobPostingId(lookup.jobPostingId());
        entity.setJobName(snapshot.jobName());
        entity.setCompanyName(snapshot.companyName());
        entity.setSalary(snapshot.salary());
        entity.setScore(result.score());
        entity.setCriteriaJson(writeItems(result.items()));
        entity.setSummary(result.summary());
        entity.setHrMessages(hrMessages);
        entity.setAssessedAt(time.stamp());
        store.save(entity);
        return entity;
    }

    private String writeItems(List<QualityJudge.Item> items) {
        try {
            return json.writeValueAsString(items);
        } catch (Exception e) {
            // 逐项结论写不进去不该让整次评估作废：分数与理由仍然有用
            log.warn("逐项结论序列化失败，按空列表保存: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 过线就提醒一次，并发出「重点关注」告警。
     *
     * @return 这一次是不是真的发了（已经提醒过、没配 webhook、类别被关掉都算没发，
     *         但「已经提醒过」只发一次这件事由 {@code markNotified} 保证）
     */
    private boolean notifyIfHot(String platform, QualityAssessmentEntity entity,
                                ConversationEntity conversation) {
        int line = configStore.qualityScoreLine();
        if (entity.getScore() < line) {
            return false;
        }
        // 先问「这条通道现在会不会真的发」，再决定记不记「已提醒」。
        // 顺序反了的话，开关关着（或还没配 webhook）时这段会话会被永久标成已提醒，
        // 之后打开开关也不会补发 —— 用户看到的是「过线了但从来没收到消息」
        if (!notifySettings.alertEnabled(AlertCategory.HOT_JOB)) {
            log.info("重点关注提醒未发出：这一类告警被关闭 | 会话={}", conversation.getPlatformConversationId());
            return false;
        }
        if (!notifySettings.configured()) {
            log.info("重点关注提醒未发出：还没配置企业微信群机器人 | 会话={}",
                    conversation.getPlatformConversationId());
            return false;
        }
        if (!store.markNotified(conversation.getId())) {
            return false;
        }
        String jobName = nvl(entity.getJobName(), "").isBlank() ? nvl(conversation.getPlatformConversationId(), "这段会话")
                : entity.getJobName();
        String company = nvl(entity.getCompanyName(), "");
        String head = company.isBlank() ? jobName : jobName + " · " + company;
        StringBuilder body = new StringBuilder();
        body.append("质量 **").append(entity.getScore()).append("** 分（过线 ").append(line).append("）\n\n");
        if (!nvl(entity.getSalary(), "").isBlank()) {
            body.append("薪资：").append(entity.getSalary()).append("\n\n");
        }
        if (!nvl(entity.getSummary(), "").isBlank()) {
            body.append(entity.getSummary()).append("\n\n");
        }
        body.append("> 打开「重点关注」可以查看每一项判断点，或者直接人工接管");
        notifier.notify("hot-job", PlatformInfo.of(platform).label(), "发现值得重点关注的岗位：" + head,
                body.toString());
        return true;
    }

    /** 给控制台用：按分数线取列表 */
    public List<QualityStore.Entry> list() {
        return store.list(configStore.qualityScoreLine(), LIST_LIMIT);
    }

    private static String nvl(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
