package com.chatjob.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.ReplyLogEntity;
import com.chatjob.store.entity.ReplyRuleEntity;
import com.chatjob.store.entity.ReplyRuleTextEntity;
import com.chatjob.store.entity.SensitiveWordEntity;
import com.chatjob.store.mapper.ReplyRuleMapper;
import com.chatjob.store.mapper.ReplyRuleTextMapper;
import com.chatjob.store.mapper.SensitiveWordMapper;
import com.chatjob.watcher.ReplyStore;
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

/** 值守规则配置。界面草案见 docs/ui/0001-reply-rules.html。 */
@RestController
@RequestMapping("/api/reply")
public class ReplyRuleController {

    private final ReplyRuleMapper ruleMapper;
    private final ReplyRuleTextMapper textMapper;
    private final SensitiveWordMapper sensitiveMapper;
    private final ReplyStore replyStore;

    public ReplyRuleController(ReplyRuleMapper ruleMapper, ReplyRuleTextMapper textMapper,
                               SensitiveWordMapper sensitiveMapper, ReplyStore replyStore) {
        this.ruleMapper = ruleMapper;
        this.textMapper = textMapper;
        this.sensitiveMapper = sensitiveMapper;
        this.replyStore = replyStore;
    }

    // ---------------------------------------------------------------- 规则

    @GetMapping("/rules")
    public List<Map<String, Object>> rules() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReplyRuleEntity rule : replyStore.rules()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rule.getId());
            item.put("name", rule.getName());
            item.put("keywords", splitKeywords(rule.getKeywords()));
            item.put("enabled", rule.getEnabled() != null && rule.getEnabled() == 1);
            item.put("sortOrder", rule.getSortOrder());
            item.put("texts", replyStore.textsOf(rule.getId()).stream()
                    .map(ReplyRuleTextEntity::getText).toList());
            out.add(item);
        }
        return out;
    }

    public record RuleBody(Long id, String name, List<String> keywords, List<String> texts,
                           Boolean enabled, Integer sortOrder) {
    }

    @PostMapping("/rules")
    public Map<String, Object> saveRule(@RequestBody RuleBody body) {
        if (body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("规则名不能为空");
        }
        List<String> keywords = body.keywords() == null ? List.of()
                : body.keywords().stream().filter(k -> k != null && !k.isBlank())
                .map(String::trim).distinct().toList();
        if (keywords.isEmpty()) {
            throw new IllegalArgumentException("至少要有一个关键词");
        }
        List<String> texts = body.texts() == null ? List.of()
                : body.texts().stream().filter(t -> t != null && !t.isBlank())
                .map(String::trim).toList();
        if (texts.isEmpty()) {
            throw new IllegalArgumentException("至少要有一条文案");
        }

        ReplyRuleEntity rule = body.id() == null ? null : ruleMapper.selectById(body.id());
        boolean created = rule == null;
        if (created) {
            rule = new ReplyRuleEntity();
            rule.setCreatedAt(ConfigStore.now());
        }
        rule.setName(body.name().trim());
        rule.setKeywords(String.join(",", keywords));
        rule.setEnabled(body.enabled() == null || body.enabled() ? 1 : 0);
        // 排序位不能跟着表单走：界面上只管规则内容，不暴露 sortOrder。
        // 以前这里写死 0，导致"编辑一次，规则就跳到列表最前面"——编辑被误认为没生效。
        rule.setSortOrder(body.sortOrder() != null ? body.sortOrder()
                : (created ? nextSortOrder() : rule.getSortOrder()));
        rule.setUpdatedAt(ConfigStore.now());
        if (created) {
            ruleMapper.insert(rule);
        } else {
            ruleMapper.updateById(rule);
        }

        // 文案全量替换：界面上就是"这一组文案"，部分更新会留下孤儿行
        textMapper.delete(Wrappers.<ReplyRuleTextEntity>lambdaQuery()
                .eq(ReplyRuleTextEntity::getRuleId, rule.getId()));
        int order = 0;
        for (String text : texts) {
            ReplyRuleTextEntity entity = new ReplyRuleTextEntity();
            entity.setRuleId(rule.getId());
            entity.setText(text);
            entity.setSortOrder(order++);
            textMapper.insert(entity);
        }
        return Map.of("ok", true, "id", rule.getId());
    }

    /** 新增规则追加到队尾，间隔 10 留出插队空间 */
    private int nextSortOrder() {
        int max = 0;
        for (ReplyRuleEntity existing : replyStore.rules()) {
            if (existing.getSortOrder() != null && existing.getSortOrder() > max) {
                max = existing.getSortOrder();
            }
        }
        return max + 10;
    }

    @DeleteMapping("/rules/{id}")
    public Map<String, Object> deleteRule(@PathVariable Long id) {
        textMapper.delete(Wrappers.<ReplyRuleTextEntity>lambdaQuery()
                .eq(ReplyRuleTextEntity::getRuleId, id));
        ruleMapper.deleteById(id);
        return Map.of("ok", true);
    }

    // ---------------------------------------------------------------- 敏感词

    @GetMapping("/sensitive")
    public List<Map<String, Object>> sensitiveWords() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SensitiveWordEntity entity : sensitiveMapper.selectList(Wrappers.emptyWrapper())) {
            out.add(Map.of("id", entity.getId(), "word", entity.getWord()));
        }
        return out;
    }

    public record SensitiveBody(String word) {
    }

    @PostMapping("/sensitive")
    public Map<String, Object> addSensitive(@RequestBody SensitiveBody body) {
        if (body.word() == null || body.word().isBlank()) {
            throw new IllegalArgumentException("敏感词不能为空");
        }
        String word = body.word().trim();
        Long exists = sensitiveMapper.selectCount(Wrappers.<SensitiveWordEntity>lambdaQuery()
                .eq(SensitiveWordEntity::getWord, word));
        if (exists != null && exists > 0) {
            return Map.of("ok", true, "added", false);
        }
        SensitiveWordEntity entity = new SensitiveWordEntity();
        entity.setWord(word);
        entity.setCreatedAt(ConfigStore.now());
        sensitiveMapper.insert(entity);
        return Map.of("ok", true, "added", true);
    }

    @DeleteMapping("/sensitive/{id}")
    public Map<String, Object> deleteSensitive(@PathVariable Long id) {
        sensitiveMapper.deleteById(id);
        return Map.of("ok", true);
    }

    // ---------------------------------------------------------------- 未命中与日志

    /** 未命中消息是规则库唯一的进化路径，所以它和规则配置在同一页 */
    @GetMapping("/unmatched")
    public List<ReplyLogEntity> unmatched(@RequestParam(defaultValue = "7") int days,
                                          @RequestParam(defaultValue = "100") int limit) {
        return replyStore.unmatched(days, limit);
    }

    @GetMapping("/logs")
    public List<ReplyLogEntity> logs(@RequestParam(defaultValue = "100") int limit) {
        return replyStore.recentLogs(limit);
    }

    private static List<String> splitKeywords(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }
}
