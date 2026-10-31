package com.chatjob.platform.zhaopin;

import com.chatjob.platform.Models.JobDetail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 智联页面的解析。刻意做成纯函数：不碰 Playwright，可以拿真实页面文本直接单测。
 *
 * <p>数据有两个来源，职责不同，<b>不能互相替代</b>：
 * <ol>
 *   <li>{@link PanelView}：点开卡片后右侧面板的原文。岗位编号、城市、经验、学历、公司、
 *       HR 活跃度、完整 JD 全在这里，是<b>主来源</b>。</li>
 *   <li>{@code <script>__INITIAL_STATE__={…}</script>} 里的 {@code positionList}（首屏 20 条）：
 *       只当备用来源与字段补充（{@code hasAppliedPosition} 这类平台侧标记），覆盖不到滚动加载出来的卡片。</li>
 * </ol>
 * 早期版本反过来（拿 positionList 的下标去对 DOM 卡片下标），一旦无限滚动把卡片从 20 张撑到
 * 196 张，多出来的 176 张就全部没有编号、被静默跳过 —— 这是实测踩过的坑，别再退回去。
 */
public final class ZhaopinRecord {

    private ZhaopinRecord() {
    }

    /**
     * 右侧详情面板的一次快照（字段就是面板上肉眼可见的那几段文字，未做语义加工）。
     *
     * @param number     从详情链接里抠出来的岗位编号，形如 {@code CCL1260832240J40831666816}
     * @param detailUrl  详情链接原文（带 {@code srccode} 等跟踪参数）
     * @param tags       摘要区标签原文，如 {@code [福州·台江区, 3-5年, 学历不限, 兼职/临时, 招30人]}
     * @param hrActivity 发布者活跃度标签，如 {@code 今日回复19次}；平台不给时为空串
     * @param companyMeta 公司信息串，如 {@code /B轮 · 20-99人 · 产业互联网平台}
     */
    public record PanelView(String number,
                            String detailUrl,
                            String title,
                            String salary,
                            List<String> tags,
                            String companyName,
                            String companyMeta,
                            String hrName,
                            String hrActivity,
                            String hrTitle,
                            String applyText,
                            String jd,
                            String rawJson) {

        public PanelView {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }

        /** 面板没读到时用它占位：所有字段为空串，映射会完整退回列表数据 */
        public static PanelView empty() {
            return new PanelView("", "", "", "", List.of(), "", "", "", "", "", "", "", "");
        }

        /** 单测只需要 JD 时的便捷构造 */
        public static PanelView ofJd(String jd) {
            return new PanelView("", "", "", "", List.of(), "", "", "", "", "", "", jd, "");
        }
    }

    /**
     * 从整篇 {@code script} 文本里切出 {@code __INITIAL_STATE__=} 之后的那段 JSON。
     *
     * <p>不能直接 {@code substring} 到结尾：脚本后面还可能跟别的语句。这里按大括号配平扫，
     * 并且必须跳过字符串里的括号与转义（岗位描述里就含有成对的花括号）。
     *
     * @return 切片后的 JSON 文本；找不到标记或括号不配平时返回 {@code null}
     */
    public static String sliceInitialState(String scriptText) {
        if (scriptText == null || scriptText.isBlank()) {
            return null;
        }
        int mark = scriptText.indexOf(ZhaopinEndpoints.INITIAL_STATE_MARK);
        if (mark < 0) {
            return null;
        }
        int start = mark + ZhaopinEndpoints.INITIAL_STATE_MARK.length();
        while (start < scriptText.length() && Character.isWhitespace(scriptText.charAt(start))) {
            start++;
        }
        if (start >= scriptText.length() || scriptText.charAt(start) != '{') {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < scriptText.length(); i++) {
            char c = scriptText.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return scriptText.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /** 首屏记录。解析失败返回空表 —— 调用方按"本组合没岗位"处理并留日志。 */
    public static List<JsonNode> parseInitialState(String scriptText, ObjectMapper json) {
        String slice = sliceInitialState(scriptText);
        if (slice == null) {
            return List.of();
        }
        try {
            return findPositionList(json.readTree(slice));
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 在任意层级的 JSON 里找 {@code positionList} 数组。
     *
     * <p>不写死路径（{@code data.positionList} / 顶层 {@code positionList}）是有原因的：
     * 首屏在顶层，接口在 {@code data} 下，写死一处另一处就静默空表。
     */
    public static List<JsonNode> findPositionList(JsonNode root) {
        JsonNode found = search(root, ZhaopinSelectors.POSITION_LIST_FIELD, 0);
        if (found == null || !found.isArray()) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>(found.size());
        found.forEach(out::add);
        return out;
    }

    private static JsonNode search(JsonNode node, String field, int depth) {
        if (node == null || depth > 6) {
            return null;
        }
        if (node.isObject()) {
            JsonNode direct = node.get(field);
            if (direct != null && direct.isArray()) {
                return direct;
            }
            var it = node.fields();
            while (it.hasNext()) {
                var entry = it.next();
                JsonNode hit = search(entry.getValue(), field, depth + 1);
                if (hit != null) {
                    return hit;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode hit = search(child, field, depth + 1);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 记录 → 中性模型

    /** 平台内稳定编号，形如 {@code CC378471810J40886738807}。去重与复查都锚定它。 */
    public static String positionNumber(JsonNode record) {
        String number = text(record, "positionNumber");
        if (!number.isBlank()) {
            return number;
        }
        // 备用来源：从详情链接里解析（两者始终一致）
        String url = positionUrl(record);
        int slash = url.lastIndexOf('/');
        int dot = url.lastIndexOf('.');
        return slash >= 0 && dot > slash ? url.substring(slash + 1, dot) : "";
    }

    public static String positionUrl(JsonNode record) {
        String url = text(record, "positionUrl");
        if (url.isBlank()) {
            url = text(record, "positionURL");
        }
        return url.isBlank() ? ZhaopinEndpoints.jobDetail(positionNumber(record)) : url;
    }

    /** 平台自己记的"我已投过"，是投递前最便宜的一道判据 */
    public static boolean alreadyApplied(JsonNode record) {
        JsonNode node = record == null ? null : record.get("hasAppliedPosition");
        return node != null && node.asBoolean(false);
    }

    /**
     * HR 活跃度原文。智联给的是一个字符串数组（如 {@code ["今日回复50+次"]}、{@code ["高回复率","今日活跃"]}），
     * 空数组表示平台没给。
     */
    public static String activityRaw(JsonNode record) {
        JsonNode list = record == null ? null : record.path("jobDetailData").path("staff").path("activityLevel");
        if (list == null || !list.isArray() || list.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode item : list) {
            String value = item.asText("").trim();
            if (!value.isEmpty()) {
                parts.add(value);
            }
        }
        return String.join("，", parts);
    }

    /**
     * 只把平台原文带出去，归一化交给 evaluate 层（{@code ActiveLevel.of}）。
     *
     * <p>不在这里自己归一：智联的文案有「今日回复19次」「近7日回复100+次」「高回复率」等多种形态，
     * 而全系统只有一套档位（在线 / 今日 / 3日内 / 本周 / 本月）。适配器另搞一套
     * （比如「今日活跃」「今日有回复」）会让同一句话在两张表里出现两种说法，且下游还得再映射一次。
     */

    /**
     * 从详情页链接里抠出岗位编号。
     *
     * <p>形如 {@code https://www.zhaopin.com/jobdetail/CCL1260832240J40831666816.htm?srccode=401901&…}：
     * 取最后一段路径、去掉 {@code .htm} 与查询串。
     */
    public static String numberFromDetailUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        int slash = path.lastIndexOf('/');
        String tail = slash >= 0 ? path.substring(slash + 1) : path;
        return tail.endsWith(".htm") ? tail.substring(0, tail.length() - 4) : tail;
    }

    /**
     * 组装中性岗位模型。面板为主、列表数据为辅（列表里有的字段面板不一定有，
     * 例如 {@code hasAppliedPosition}；面板有的字段列表只有截断版，例如 JD）。
     *
     * @param panel  右侧面板快照；读不到时传 {@link PanelView#empty()}
     * @param record 该岗位在首屏 {@code positionList} 里的原始记录；滚动出来的岗位没有，传 {@code null}
     */
    public static JobDetail toJobDetail(PanelView panel, JsonNode record) {
        PanelView view = panel == null ? PanelView.empty() : panel;
        String number = !view.number().isBlank() ? view.number() : positionNumber(record);

        String fromPanel = cleanDescription(view.jd());
        String fromList = cleanDescription(text(record, "jobDescription"));
        // 列表内嵌的 jobDescription 常是截断版（实测 81 字 vs 面板 516 字），谁长用谁的
        String jd = fromPanel.length() > fromList.length() ? fromPanel : fromList;

        // 「今日回复19次」这类标签面板有、列表数据也可能有，两个都取到再判档
        String activeRaw = firstNonBlank(view.hrActivity(), activityRaw(record));
        String detailUrl = !view.detailUrl().isBlank() ? view.detailUrl() : positionUrl(record);

        return new JobDetail(
                number,
                firstNonBlank(view.title(), text(record, "name")),
                firstNonBlank(view.companyName(), text(record, "companyName")),
                firstNonBlank(view.salary(), text(record, "salary60")),
                firstNonBlank(areaOfTags(view.tags()), area(record)),
                firstNonBlank(pickExperience(view.tags()), text(record, "workingExp")),
                firstNonBlank(pickDegree(view.tags()), text(record, "education")),
                jd,
                firstNonBlank(view.hrName(), text(staff(record), "staffName")),
                firstNonBlank(stripCompany(view.hrTitle()), text(staff(record), "hrJob")),
                "", // 活跃度归一化在 evaluate 层做（与 BossAdapter 一致）
                activeRaw,
                !view.rawJson().isBlank() ? view.rawJson() : (record == null ? "" : record.toString()),
                detailUrl);
    }

    /** 摘要区的城市标签，形如「福州·台江区」；没有 · 时退回第一枚不像资历的标签 */
    static String areaOfTags(List<String> tags) {
        for (String tag : tags) {
            if (tag.contains("·")) {
                return tag;
            }
        }
        for (String tag : tags) {
            if (!pickExperience(List.of(tag)).isEmpty() || !pickDegree(List.of(tag)).isEmpty()
                    || tag.startsWith("招") || tag.contains("全职") || tag.contains("兼职")
                    || tag.contains("实习") || tag.contains("临时")) {
                continue;
            }
            return tag;
        }
        return "";
    }

    /** 经验标签：「3-5年」「经验不限」「应届生」「1年以内」 */
    static String pickExperience(List<String> tags) {
        for (String tag : tags) {
            if (tag.contains("年") || tag.contains("应届") || tag.contains("经验不限")) {
                return tag;
            }
        }
        return "";
    }

    /** 学历标签：「学历不限」「本科」「大专」「硕士及以上」 */
    static String pickDegree(List<String> tags) {
        for (String tag : tags) {
            if (tag.contains("学历") || tag.contains("本科") || tag.contains("大专")
                    || tag.contains("硕士") || tag.contains("博士") || tag.contains("中专")
                    || tag.contains("高中") || tag.contains("初中") || tag.contains("MBA")) {
                return tag;
            }
        }
        return "";
    }

    /** 「人事管理 · 福建省卓正能源科技有限公司」→「人事管理」（公司名另有字段，不要重复塞进岗位头衔） */
    static String stripCompany(String hrTitle) {
        if (hrTitle == null || hrTitle.isBlank()) {
            return "";
        }
        int sep = hrTitle.indexOf(" · ");
        return sep > 0 ? hrTitle.substring(0, sep).strip() : hrTitle.strip();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a.strip() : (b == null ? "" : b.strip());
    }

    private static JsonNode staff(JsonNode record) {
        return record == null ? null : record.path("jobDetailData").path("staff");
    }

    /**
     * 职位描述清洗。
     *
     * <p>实测（2026-10-31）智联的 JD 里混着站点噪声：HTML 标签、
     * 「版权所有 zhaopin.com」、「看看大家怎么写 · 1/3」这类占位块。
     * 它们对判岗毫无信息量，却会占掉 prompt 的字符预算（默认截断 4000 字），
     * 把真正的任职要求挤出去 —— 表现为 AI 给出的理由跟岗位要求对不上。
     */
    static String cleanDescription(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.replaceAll("<[^>]+>", " ");
        text = text.replace("版权所有 zhaopin.com", " ")
                .replace("版权所有zhaopin.com", " ")
                .replace("看看大家怎么写", " ")
                .replaceAll("·\\s*\\d+\\s*/\\s*\\d+", " ");
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(trimmed);
        }
        return out.toString().replaceAll("[ \\t\\u00A0]{2,}", " ").strip();
    }

    private static String area(JsonNode record) {
        List<String> parts = new ArrayList<>();
        for (String field : List.of("workCity", "cityDistrict", "streetName")) {
            String value = text(record, field);
            if (!value.isBlank()) {
                parts.add(value);
            }
        }
        return String.join(" ", parts);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }
}
