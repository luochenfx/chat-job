package com.chatjob.web;

import com.chatjob.evaluate.QualityJudge;
import com.chatjob.platform.PlatformInfo;
import com.chatjob.quality.QualityAssessor;
import com.chatjob.quality.QualityStore;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.ConversationEntity;
import com.chatjob.store.entity.QualityAssessmentEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「重点关注」：过了质量线的会话，以及人工接管。
 *
 * <p>列表按会话排，不是按岗位排 —— 评估的对象就是一段会话，而会话与岗位之间没有可靠关联
 * （ADR-0016 的 Context）。岗位名、公司、薪资是评估那一刻的快照，断链时也显示得出来。
 */
@RestController
@RequestMapping("/api/quality")
public class QualityController {

    private final QualityAssessor assessor;
    private final QualityStore store;
    private final ConfigStore configStore;

    public QualityController(QualityAssessor assessor, QualityStore store, ConfigStore configStore) {
        this.assessor = assessor;
        this.store = store;
        this.configStore = configStore;
    }

    /**
     * 过线的会话，按分数从高到低。
     *
     * <p>分数线回给界面是为了写清楚「多少分算过线」—— 用户改了配置页的分数线之后，
     * 列表会立刻少几条，不说清楚会像丢数据。
     */
    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (QualityStore.Entry entry : assessor.list()) {
            rows.add(toRow(entry));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scoreLine", configStore.qualityScoreLine());
        out.put("items", rows);
        return out;
    }

    /** 接管：此后系统不再替这段会话发消息，也不再打开它（ADR-0016） */
    @PostMapping("/{conversationId}/takeover")
    public Map<String, Object> takeOver(@PathVariable Long conversationId) {
        return Map.of("ok", store.takeOver(conversationId));
    }

    /** 交还 AI：回到自动回复 */
    @DeleteMapping("/{conversationId}/takeover")
    public Map<String, Object> release(@PathVariable Long conversationId) {
        return Map.of("ok", store.release(conversationId));
    }

    private Map<String, Object> toRow(QualityStore.Entry entry) {
        QualityAssessmentEntity a = entry.assessment();
        ConversationEntity c = entry.conversation();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("conversationId", a.getConversationId());
        row.put("platform", a.getPlatform());
        row.put("platformLabel", PlatformInfo.of(a.getPlatform()).label());
        row.put("title", c == null ? "" : c.getPlatformConversationId());
        row.put("jobName", a.getJobName());
        row.put("companyName", a.getCompanyName());
        row.put("salary", a.getSalary());
        row.put("score", a.getScore());
        row.put("summary", a.getSummary());
        row.put("assessedAt", a.getAssessedAt());
        // 岗位信息取不到时界面要写「未取到」：靠这一列判断，不靠 jobName 是不是空串自己猜
        row.put("jobFound", a.getJobPostingId() != null);
        row.put("takenOver", c != null && c.takenOver());
        row.put("takenOverAt", c == null ? null : c.getTakenOverAt());
        row.put("notified", c != null && c.getQualityNotifiedAt() != null
                && !c.getQualityNotifiedAt().isBlank());
        // 接管期间不打开会话，这两项来自会话列表页 —— 「HR 又说了什么」只有这一个来源
        row.put("lastMessage", c == null ? "" : c.getLastThreadMessage());
        row.put("unread", c == null || c.getLastThreadUnread() == null ? 0 : c.getLastThreadUnread());
        List<Map<String, String>> items = new ArrayList<>();
        for (QualityJudge.Item item : entry.items()) {
            Map<String, String> one = new LinkedHashMap<>();
            one.put("criterion", item.criterion());
            one.put("verdict", item.verdict().name());
            one.put("evidence", item.evidence());
            items.add(one);
        }
        row.put("criteria", items);
        return row;
    }
}
