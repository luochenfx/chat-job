package com.chatjob.platform.zhaopin;

import com.chatjob.store.OptionStore;
import com.chatjob.store.entity.PlatformConfigEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 拼智联的搜索 URL：{@code /jobs/?jl=<城市码>&kw=<关键词>}。
 *
 * <p>只拼这两个参数，是刻意的取舍（spec 0004 阶段 1）：智联的筛选组与 Boss 不是同一套
 * （地铁 / 薪资 / 学历 / 经验 / 公司性质 / 融资阶段 / 公司人数 / 工作性质 / 职位类别 / 公司行业），
 * 配置层存的却是 Boss 那套中文名。硬把 Boss 的名字往智联的 code 上映射，会得到
 * 「看着生效、其实筛掉了全部岗位」这种最难查的空结果。
 *
 * <p>所以这里的选择是：<b>只做确定成立的城 + 关键词</b>，其余筛选在配置里存在时
 * 打一条警告说"该平台忽略它"，把差异暴露出来而不是假装支持。
 */
@Slf4j
@Component
public class ZhaopinUrlBuilder {

    private final OptionStore options;

    public ZhaopinUrlBuilder(OptionStore options) {
        this.options = options;
    }

    public String build(PlatformConfigEntity cfg, String city, String keyword) {
        List<String> params = new ArrayList<>();
        String cityCode = codeOf(city);
        if (cityCode == null || cityCode.isBlank()) {
            throw new IllegalStateException("智联城市字典里没有「" + city + "」的城市码，"
                    + "请先在 platform_option 里补一条 platform=zhaopin/type=city 的记录");
        }
        params.add("jl=" + cityCode);
        if (keyword != null && !keyword.isBlank()) {
            params.add("kw=" + URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8));
        }
        warnIgnoredFilters(cfg);
        return ZhaopinEndpoints.LIST_URL + "?" + String.join("&", params);
    }

    /**
     * 城市码查字典。字典缺失时<b>不推测城市码</b>——猜错的城市码会安静地返回别的城市的岗位，
     * 而「投递到错误城市」比「本轮扫不到岗位」严重得多。
     */
    String codeOf(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        return options.codeOf("zhaopin", "city", city.trim());
    }

    /** 智联这一期只支持城 + 关键词，配置了别的筛选就明确说出来 */
    private void warnIgnoredFilters(PlatformConfigEntity cfg) {
        List<String> ignored = new ArrayList<>();
        addIfConfigured(ignored, "薪资", cfg.getSalary());
        addIfConfigured(ignored, "经验", cfg.getExperience());
        addIfConfigured(ignored, "学历", cfg.getDegree());
        addIfConfigured(ignored, "公司规模", cfg.getScale());
        addIfConfigured(ignored, "行业", cfg.getIndustry());
        addIfConfigured(ignored, "融资阶段", cfg.getStage());
        if (cfg.getJobType() != null && !cfg.getJobType().isBlank()) {
            ignored.add("职位类型");
        }
        if (!ignored.isEmpty()) {
            log.warn("智联这一期只用「城市 + 关键词」搜索，以下筛选条件被忽略：{}（配置里留着不影响 Boss）",
                    String.join(" / ", ignored));
        }
    }

    private void addIfConfigured(List<String> out, String label, String rawJson) {
        if (rawJson == null || rawJson.isBlank() || "[]".equals(rawJson.trim())) {
            return;
        }
        if (rawJson.contains("不限")) {
            return;
        }
        out.add(label);
    }
}
