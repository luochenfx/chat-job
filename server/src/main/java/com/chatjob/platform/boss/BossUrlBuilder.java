package com.chatjob.platform.boss;

import com.chatjob.store.OptionStore;
import com.chatjob.store.entity.PlatformConfigEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 拼搜索 URL。不走首页 UI 搜索 —— 受控标签页下 BOSS 首页会一直转圈加载不完，
 * navigate 要 60 秒，期间 evaluate / locator 全部无限期阻塞；而列表页是秒开的。
 *
 * <p>参数规则（与平台实际行为一致）：
 * <ul>
 *   <li>{@code city} / {@code jobType} 单值</li>
 *   <li>{@code salary} / {@code experience} / {@code degree} / {@code scale} / {@code industry}
 *       / {@code stage} 逗号分隔多值</li>
 *   <li>任一列表里出现"不限"，该参数整个不拼</li>
 * </ul>
 */
@Slf4j
@Component
public class BossUrlBuilder {

    /** 字典里表示"不限"的名称；对应 code 是 0 */
    static final String UNLIMITED = "不限";

    private final OptionStore options;
    private final ObjectMapper json;

    public BossUrlBuilder(OptionStore options, ObjectMapper json) {
        this.options = options;
        this.json = json;
    }

    public String build(PlatformConfigEntity cfg, String city, String keyword) {
        String platform = cfg.getPlatform();
        List<String> params = new ArrayList<>();
        addSingle(params, "city", code(platform, "city", city));
        addSingle(params, "jobType", code(platform, "jobType", cfg.getJobType()));
        addList(params, "salary", platform, "salary", cfg.getSalary());
        addList(params, "experience", platform, "experience", cfg.getExperience());
        addList(params, "degree", platform, "degree", cfg.getDegree());
        addList(params, "scale", platform, "scale", cfg.getScale());
        addList(params, "industry", platform, "industry", cfg.getIndustry());
        addList(params, "stage", platform, "stage", cfg.getStage());
        if (keyword != null && !keyword.isBlank()) {
            params.add("query=" + URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8));
        }
        String base = BossSelectors.JOB_LIST_URL;
        return params.isEmpty() ? base : base + "?" + String.join("&", params);
    }

    private void addSingle(List<String> params, String name, String code) {
        if (code == null || code.isBlank() || "0".equals(code)) {
            return;
        }
        params.add(name + "=" + code);
    }

    private void addList(List<String> params, String name, String platform, String type, String rawJson) {
        List<String> names = parseList(rawJson);
        if (names.isEmpty() || names.contains(UNLIMITED)) {
            return;
        }
        List<String> codes = new ArrayList<>();
        for (String n : names) {
            String code = code(platform, type, n);
            if (code != null && !code.isBlank() && !"0".equals(code)) {
                codes.add(code);
            }
        }
        if (!codes.isEmpty()) {
            params.add(name + "=" + String.join(",", codes));
        }
    }

    private String code(String platform, String type, String name) {
        if (name == null || name.isBlank() || UNLIMITED.equals(name.trim())) {
            return null;
        }
        return options.codeOf(platform, type, name);
    }

    /** 配置列是 JSON 数组；同时兼容手工填的逗号分隔 */
    List<String> parseList(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        String trimmed = rawJson.trim();
        if (!trimmed.startsWith("[")) {
            List<String> out = new ArrayList<>();
            for (String part : trimmed.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
            return out;
        }
        try {
            return json.readValue(trimmed, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("解析筛选条件失败，按空处理: {}", trimmed);
            return List.of();
        }
    }
}
