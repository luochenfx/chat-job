package com.chatjob.store;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chatjob.store.entity.AiConfigEntity;
import com.chatjob.store.entity.BlacklistEntity;
import com.chatjob.store.entity.PlatformConfigEntity;
import com.chatjob.store.entity.ProviderEntity;
import com.chatjob.store.mapper.AiConfigMapper;
import com.chatjob.store.mapper.BlacklistMapper;
import com.chatjob.store.mapper.PlatformConfigMapper;
import com.chatjob.store.mapper.ProviderMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 配置读写。所有"单行表"的默认值都在这里兜底，避免空库启动后处处判空。 */
@Slf4j
@Component
public class ConfigStore {

    public static final String PLATFORM_BOSS = "boss";

    /** 值守时段默认值，与 V4 迁移里的列默认值保持一致 */
    public static final String DEFAULT_DUTY_START = "09:00";
    public static final String DEFAULT_DUTY_END = "22:00";

    /** 未读列表一轮扫描条数的默认值，与 V6 迁移里的列默认值保持一致 */
    public static final int DEFAULT_UNREAD_SCAN_LIMIT = 100;

    /**
     * 未读列表扫描条数的安全上限。不是业务限制，而是防止列表异常膨胀时
     * 一轮值守把整个扫描节奏吃掉 —— 每条要读 3 次 DOM，500 条已经是几秒到十几秒。
     */
    public static final int MAX_UNREAD_SCAN_LIMIT = 500;

    /** AI 回复单次超时默认值，与 V8 迁移里的列默认值保持一致 */
    public static final int DEFAULT_AI_REPLY_TIMEOUT_SECONDS = 8;

    /**
     * 单轮最多调几次 AI。默认 5：单条最坏 8s，5 条就是 40s，已经接近一次值守的间隔。
     *
     * <p>这个上限不是成本控制（那是日评估上限的事），而是<b>节奏保护</b>：列表里有 40 个会话时，
     * 全调一遍会把扫描主链饿死。
     */
    public static final int DEFAULT_AI_REPLY_MAX_PER_SWEEP = 5;

    /** 上限的合理区间。配成 100 等于关掉这道保护，所以留个天花板 */
    public static final int MAX_AI_REPLY_MAX_PER_SWEEP = 10;

    /** skip 占比告警阈值默认值，与 V9 迁移里的列默认值保持一致 */
    public static final int DEFAULT_AI_REPLY_SKIP_ALERT_PCT = 60;

    /** 告警最小样本数默认值。低于此数不判定，面板显示「样本不足」 */
    public static final int DEFAULT_AI_REPLY_SKIP_ALERT_MIN_SAMPLE = 10;

    /** 词表条数上限与单条字数上限。超长行是「误粘整段文本」的特征，直接丢弃而不是截断 */
    public static final int MAX_TONE_TERMS = 200;
    public static final int MAX_TONE_TERM_CHARS = 20;

    /** 表达风格长度上限。超出截断保存 —— 宁可少一段约束，也不让一次误粘把 prompt 撑爆 */
    public static final int MAX_AI_REPLY_STYLE_CHARS = 500;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final List<String> DEFAULT_REPEAT_MESSAGES = List.of(
            "请问是有什么顾虑需要沟通吗？",
            "我可以去贵公司面试吗？",
            "请问贵公司还招人吗？",
            "对于这个岗位有什么技术需求呢？",
            "我可以把我的简历发给您看看吗？",
            "能问一下贵公司办公地点在哪里吗？需不需要经常出差");

    private static final String DEFAULT_PROMPT = """
            我在找工作。个人情况：{{introduce}}

            需要你判断下面这个岗位值不值得投递，并给出一句打招呼的开场白。

            岗位名称：{{jobName}}
            公司：{{company}}
            薪资：{{salary}}
            招聘者活跃度：{{activeLevel}}
            已排除的公司/岗位关键词（仅作参考，不要重复判断）：{{blacklist}}

            岗位描述：
            {{jd}}

            要求：
            1. 只输出 JSON，不要任何解释性文字。
            2. shouldApply：岗位与我的背景、期望方向基本匹配才为 true；明显不匹配（方向完全无关、要求远高于我的经验、外包派遣）为 false。
            3. score：0-100 的匹配度。
            4. reason：20 字以内说明理由。
            5. greeting：一句中文打招呼，不超过 40 字，不要出现薪资、公司名以外的敏感信息，不要客套堆砌。

            输出格式：{"shouldApply": true, "score": 85, "reason": "...", "greeting": "..."}
            """;

    private final PlatformConfigMapper platformConfigMapper;
    private final AiConfigMapper aiConfigMapper;
    private final ProviderMapper providerMapper;
    private final BlacklistMapper blacklistMapper;
    private final ObjectMapper json;

    public ConfigStore(PlatformConfigMapper platformConfigMapper,
                       AiConfigMapper aiConfigMapper,
                       ProviderMapper providerMapper,
                       BlacklistMapper blacklistMapper,
                       ObjectMapper json) {
        this.platformConfigMapper = platformConfigMapper;
        this.aiConfigMapper = aiConfigMapper;
        this.providerMapper = providerMapper;
        this.blacklistMapper = blacklistMapper;
        this.json = json;
    }

    public static String now() {
        return LocalDateTime.now().format(TS);
    }

    // ------------------------------------------------------------ 平台配置

    public PlatformConfigEntity platformConfig(String platform) {
        PlatformConfigEntity existing = platformConfigMapper.selectOne(
                Wrappers.<PlatformConfigEntity>lambdaQuery().eq(PlatformConfigEntity::getPlatform, platform));
        if (existing != null) {
            return existing;
        }
        PlatformConfigEntity created = new PlatformConfigEntity();
        created.setPlatform(platform);
        created.setEnabled(1);
        created.setCities("[]");
        created.setKeywords("[]");
        created.setJobType("");
        created.setSalary("[]");
        created.setExperience("[]");
        created.setDegree("[]");
        created.setScale("[]");
        created.setIndustry("[]");
        created.setStage("[]");
        created.setActiveLevelFloor("本月");
        created.setWaitTimeSeconds(10);
        created.setDailyDeliveryQuota(30);
        created.setDailyEvalLimit(150);
        created.setScrollRoundLimit(300);
        created.setCombinationTimeLimitSec(1200);
        created.setUnreadScanLimit(DEFAULT_UNREAD_SCAN_LIMIT);
        created.setRepeatChatEnabled(0);
        created.setRepeatChatMessages(writeList(DEFAULT_REPEAT_MESSAGES));
        created.setResumeImageEnabled(0);
        created.setDutyStartTime(DEFAULT_DUTY_START);
        created.setDutyEndTime(DEFAULT_DUTY_END);
        created.setUpdatedAt(now());
        platformConfigMapper.insert(created);
        log.info("已创建平台配置默认值: platform={}", platform);
        return created;
    }

    public void savePlatformConfig(PlatformConfigEntity entity) {
        entity.setUpdatedAt(now());
        if (entity.getId() == null) {
            platformConfigMapper.insert(entity);
        } else {
            platformConfigMapper.updateById(entity);
        }
    }

    /**
     * 值守时一轮最多读多少个会话项。
     *
     * <p>读之前必须过这一层，调用方拿到的永远是合法值 —— 也好过让每个调用点各写一遍
     * {@code null} 判断，然后漏掉一处。
     */
    public int unreadScanLimit(String platform) {
        return clampUnreadScanLimit(platformConfig(platform).getUnreadScanLimit());
    }

    /**
     * 扫描条数归一。
     *
     * <p>{@code null} / 小于 1 一律按默认值处理，而<b>不是</b>按「一条都不读」处理：
     * 后者会让整个值守静默空转，正是这个配置项要避免的失败模式。
     */
    public static int clampUnreadScanLimit(Integer configured) {
        if (configured == null || configured < 1) {
            return DEFAULT_UNREAD_SCAN_LIMIT;
        }
        return Math.min(configured, MAX_UNREAD_SCAN_LIMIT);
    }

    // ------------------------------------------------------------ AI 配置

    public AiConfigEntity aiConfig() {
        List<AiConfigEntity> list = aiConfigMapper.selectList(Wrappers.emptyWrapper());
        if (!list.isEmpty()) {
            return list.get(0);
        }
        AiConfigEntity created = new AiConfigEntity();
        created.setProviderId(null);
        created.setIntroduce("");
        created.setPromptTemplate(DEFAULT_PROMPT);
        created.setGreetingPrompt("");
        created.setQualityGateEnabled(1);
        created.setTemplateGreeting("您好，我对这个岗位很感兴趣，方便发一份简历给您看看吗？");
        created.setJdMaxChars(4000);
        created.setAiTimeoutSeconds(20);
        created.setCollectEffectMetrics(0);
        created.setAiReplyEnabled(0);
        created.setAiReplyToneCheckEnabled(1);
        created.setAiReplyToneTerms("");
        created.setAiReplyStyle("");
        created.setAiReplyTimeoutSeconds(DEFAULT_AI_REPLY_TIMEOUT_SECONDS);
        created.setAiReplyMaxPerSweep(DEFAULT_AI_REPLY_MAX_PER_SWEEP);
        created.setAiReplySkipAlertPct(DEFAULT_AI_REPLY_SKIP_ALERT_PCT);
        created.setAiReplySkipAlertMinSample(DEFAULT_AI_REPLY_SKIP_ALERT_MIN_SAMPLE);
        created.setUpdatedAt(now());
        aiConfigMapper.insert(created);
        log.info("已创建 AI 配置默认值");
        return created;
    }

    public void saveAiConfig(AiConfigEntity entity) {
        entity.setUpdatedAt(now());
        if (entity.getId() == null) {
            aiConfigMapper.insert(entity);
        } else {
            aiConfigMapper.updateById(entity);
        }
    }

    public boolean qualityGateEnabled() {
        Integer v = aiConfig().getQualityGateEnabled();
        return v != null && v == 1;
    }

    /**
     * 值守 AI 回复开关。关闭时 {@code UnreadWatcher} 必须走改造前那条路径 ——
     * 这里刻意只返回布尔值，不给调用方「顺便读一下其他 AI 字段」的机会。
     */
    public boolean aiReplyEnabled() {
        Integer v = aiConfig().getAiReplyEnabled();
        return v != null && v == 1;
    }

    public List<String> repeatMessages(PlatformConfigEntity cfg) {
        List<String> list = readList(cfg.getRepeatChatMessages());
        return list.isEmpty() ? DEFAULT_REPEAT_MESSAGES : list;
    }

    // ------------------------------------------------------------ 出站校验（P2）

    /**
     * AI 味检查开关。{@code null} 按开处理 —— 列有 {@code NOT NULL DEFAULT 1}，
     * 真读到 null 只可能是实体刚构造还没落库，此时「默认开着」比「默认关着」安全。
     */
    public boolean toneCheckEnabled() {
        Integer v = aiConfig().getAiReplyToneCheckEnabled();
        return v == null || v == 1;
    }

    /**
     * AI 味词表。空结果表示「没配，用代码里的默认 31 条」，由 {@code ReplyGuard} 兜底 ——
     * 默认值只有一份，放在校验器那一侧，控制台要展示时也是从那里取。
     */
    public List<String> toneTerms() {
        return parseToneTerms(aiConfig().getAiReplyToneTerms());
    }

    /**
     * 解析换行分隔的词表：去空行、去首尾空白、去重（保持输入顺序）。
     *
     * <p>去重不是洁癖：同一行粘两遍在整串匹配下没有额外作用，但会让「当前生效 N 条」这个
     * 数字比实际大，用户会以为检查覆盖得更多。
     */
    public static List<String> parseToneTerms(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String line : raw.split("\\R")) {
            String term = line.strip();
            if (!term.isEmpty()) {
                seen.add(term);
            }
        }
        return new ArrayList<>(seen);
    }

    /**
     * 保存前的词表清洗：去空行、去首尾空白、去重（保序）、超 200 条截断。
     *
     * <p><b>超长行整行丢弃，不截断。</b> 一行 50 字基本是误粘的整段文本，截到 20 字会造出
     * 半截词（「如有任何问题请随时联系我」→「如有任何问题请随时联系我」恰好 20 字，
     * 但「感谢您的关注，期待与您共事」截断后就是个没人认识的碎片），既拦不住原意又可能误杀。
     */
    public static ToneTerms normalizeToneTerms(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ToneTerms("", 0, 0);
        }
        Set<String> seen = new LinkedHashSet<>();
        int dropped = 0;
        for (String line : raw.split("\\R")) {
            String term = line.strip();
            if (term.isEmpty()) {
                continue;
            }
            if (term.length() > MAX_TONE_TERM_CHARS) {
                dropped++;
                continue;
            }
            if (seen.size() >= MAX_TONE_TERMS) {
                dropped++;
                continue;
            }
            seen.add(term);
        }
        return new ToneTerms(String.join("\n", seen), seen.size(), dropped);
    }

    /** @param dropped 被丢弃的行数（超长或超出条数上限），控制台据此提示用户 */
    public record ToneTerms(String text, int kept, int dropped) {
    }

    /** 风格长度截断。空串保留空串（= 用默认风格），不在这里填默认值 */
    public static String clampStyle(String raw) {
        if (raw == null) {
            return "";
        }
        String style = raw.strip();
        return style.length() > MAX_AI_REPLY_STYLE_CHARS
                ? style.substring(0, MAX_AI_REPLY_STYLE_CHARS)
                : style;
    }

    /**
     * skip 占比告警阈值（%）。0 = 关闭告警，是合法值 —— 关闭状态必须在面板上显示出来，
     * 不能因为「没配」就退化成默认 60，也不能因为关了就不显示任何东西。
     */
    public int skipAlertPct() {
        return clampSkipAlertPct(aiConfig().getAiReplySkipAlertPct());
    }

    public static int clampSkipAlertPct(Integer configured) {
        if (configured == null || configured < 0) {
            return DEFAULT_AI_REPLY_SKIP_ALERT_PCT;
        }
        return Math.min(configured, 100);
    }

    /** 告警最小样本数。小于 1 会让「分母 0」也进入判定，直接按 1 兜住 */
    public int skipAlertMinSample() {
        return clampSkipAlertMinSample(aiConfig().getAiReplySkipAlertMinSample());
    }

    public static int clampSkipAlertMinSample(Integer configured) {
        if (configured == null || configured < 1) {
            return DEFAULT_AI_REPLY_SKIP_ALERT_MIN_SAMPLE;
        }
        return Math.min(configured, 1000);
    }

    // ------------------------------------------------------------ AI 回复（P3）

    /**
     * 事实素材（<b>说什么</b>：年限、技能、项目）。与风格是两件事 ——
     * 它天然是简历腔，直接当人设喂模型，产出必是「本人具备 X 年经验」这类一眼假的句子。
     */
    public String aiIntroduce() {
        String v = aiConfig().getIntroduce();
        return v == null ? "" : v;
    }

    /**
     * 表达风格。返回空串表示「没配」，由 {@code AiReplier} 换成默认风格 ——
     * 这里不兜默认值，默认值只有一份，放在用到它的那一侧。
     */
    public String aiReplyStyle() {
        String style = aiConfig().getAiReplyStyle();
        return style == null ? "" : style;
    }

    /** 单次生成超时。合法区间 [1, 120]：小于 1 秒必然全超时，大于 120 秒等于把扫描冻住 */
    public int aiReplyTimeoutSeconds() {
        return clampAiReplyTimeoutSeconds(aiConfig().getAiReplyTimeoutSeconds());
    }

    public static int clampAiReplyTimeoutSeconds(Integer configured) {
        if (configured == null || configured < 1) {
            return DEFAULT_AI_REPLY_TIMEOUT_SECONDS;
        }
        return Math.min(configured, 120);
    }

    /**
     * 单轮 AI 调用上限。{@code null} / 小于 1 按默认值处理，而不是按 0 ——
     * 「0 次调用」等于值守 AI 永久空转，正是这类配置项最该避免的失败模式。
     */
    public int aiReplyMaxPerSweep() {
        return clampAiReplyMaxPerSweep(aiConfig().getAiReplyMaxPerSweep());
    }

    public static int clampAiReplyMaxPerSweep(Integer configured) {
        if (configured == null || configured < 1) {
            return DEFAULT_AI_REPLY_MAX_PER_SWEEP;
        }
        return Math.min(configured, MAX_AI_REPLY_MAX_PER_SWEEP);
    }

    // ------------------------------------------------------------ 提供商

    public List<ProviderEntity> providers() {
        return providerMapper.selectList(Wrappers.<ProviderEntity>lambdaQuery()
                .orderByAsc(ProviderEntity::getId));
    }

    public ProviderEntity provider(Long id) {
        return id == null ? null : providerMapper.selectById(id);
    }

    public ProviderEntity activeProvider() {
        AiConfigEntity ai = aiConfig();
        ProviderEntity provider = provider(ai.getProviderId());
        if (provider != null) {
            return provider;
        }
        List<ProviderEntity> list = providerMapper.selectList(Wrappers.<ProviderEntity>lambdaQuery()
                .eq(ProviderEntity::getEnabled, 1)
                .orderByAsc(ProviderEntity::getId));
        return list.isEmpty() ? null : list.get(0);
    }

    public ProviderEntity saveProvider(ProviderEntity entity) {
        entity.setUpdatedAt(now());
        if (entity.getId() == null) {
            entity.setCreatedAt(now());
            providerMapper.insert(entity);
        } else {
            providerMapper.updateById(entity);
        }
        return entity;
    }

    public void deleteProvider(Long id) {
        providerMapper.deleteById(id);
    }

    // ------------------------------------------------------------ 黑名单

    public List<BlacklistEntity> blacklist() {
        return blacklistMapper.selectList(Wrappers.<BlacklistEntity>lambdaQuery()
                .orderByAsc(BlacklistEntity::getKind)
                .orderByAsc(BlacklistEntity::getId));
    }

    public List<BlacklistEntity> blacklist(String kind) {
        return blacklistMapper.selectList(Wrappers.<BlacklistEntity>lambdaQuery()
                .eq(BlacklistEntity::getKind, kind)
                .orderByAsc(BlacklistEntity::getId));
    }

    public boolean addBlacklist(String kind, String keyword, String normalized) {
        BlacklistEntity existing = blacklistMapper.selectOne(Wrappers.<BlacklistEntity>lambdaQuery()
                .eq(BlacklistEntity::getKind, kind)
                .eq(BlacklistEntity::getNormalized, normalized));
        if (existing != null) {
            return false;
        }
        BlacklistEntity entity = new BlacklistEntity();
        entity.setKind(kind);
        entity.setKeyword(keyword);
        entity.setNormalized(normalized);
        entity.setCreatedAt(now());
        blacklistMapper.insert(entity);
        return true;
    }

    public void removeBlacklist(Long id) {
        blacklistMapper.deleteById(id);
    }

    // ------------------------------------------------------------ 工具

    public List<String> readList(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return json.readValue(jsonArray, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("解析 JSON 数组失败，按空处理: {}", jsonArray);
            return new ArrayList<>();
        }
    }

    public String writeList(List<String> values) {
        try {
            return json.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }

    public static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
