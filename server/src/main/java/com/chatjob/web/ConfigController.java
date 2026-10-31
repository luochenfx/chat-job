package com.chatjob.web;

import com.chatjob.config.SecretCipher;
import com.chatjob.driver.BrowserSessions;
import com.chatjob.orchestrator.DutyWindow;
import com.chatjob.orchestrator.RunStatus;
import com.chatjob.orchestrator.RunStore;
import com.chatjob.platform.PlatformSwitches;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.OptionStore;
import com.chatjob.store.entity.AiConfigEntity;
import com.chatjob.store.entity.PlatformConfigEntity;
import com.chatjob.store.entity.ProviderEntity;
import com.chatjob.watcher.AiReplier;
import com.chatjob.watcher.ReplyGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置读写。
 *
 * <p>API Key 永远不回发。接口只告诉你"配没配"，避免出现参考项目那种
 * 明文 Key 直接躺在 GET 响应里、再被浏览器缓存或截图带走的情况。
 */
@Slf4j
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigStore configStore;
    private final OptionStore optionStore;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final RunStore runStore;
    private final BrowserSessions sessions;

    public ConfigController(ConfigStore configStore, OptionStore optionStore,
                            SecretCipher cipher, ObjectMapper json,
                            RunStore runStore, BrowserSessions sessions) {
        this.configStore = configStore;
        this.optionStore = optionStore;
        this.cipher = cipher;
        this.json = json;
        this.runStore = runStore;
        this.sessions = sessions;
    }

    // ---------------------------------------------------------------- 提供商

    public record ProviderBody(Long id, String name, String baseUrl, String model, String apiKey,
                               Integer timeoutSeconds, Map<String, String> extraHeaders, Boolean enabled) {
    }

    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProviderEntity entity : configStore.providers()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entity.getId());
            item.put("name", entity.getName());
            item.put("baseUrl", entity.getBaseUrl());
            item.put("model", entity.getModel());
            item.put("timeoutSeconds", entity.getTimeoutSeconds());
            item.put("extraHeaders", parseHeaders(entity.getExtraHeaders()));
            item.put("enabled", entity.getEnabled());
            // 只暴露"有没有配"，不暴露内容
            item.put("keyConfigured", cipher.isConfigured(entity.getApiKeyCipher()));
            out.add(item);
        }
        return out;
    }

    @PostMapping("/providers")
    public Map<String, Object> saveProvider(@RequestBody ProviderBody body) {
        if (body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("服务商名称不能为空");
        }
        if (body.baseUrl() == null || body.baseUrl().isBlank()) {
            throw new IllegalArgumentException("接口地址不能为空");
        }
        if (body.model() == null || body.model().isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        ProviderEntity entity = body.id() == null ? new ProviderEntity() : configStore.provider(body.id());
        if (entity == null) {
            throw new IllegalArgumentException("这个 AI 服务商不存在");
        }
        entity.setName(body.name().trim());
        entity.setBaseUrl(body.baseUrl().trim());
        entity.setModel(body.model().trim());
        entity.setTimeoutSeconds(body.timeoutSeconds() == null ? 60 : body.timeoutSeconds());
        entity.setExtraHeaders(writeHeaders(body.extraHeaders()));
        entity.setEnabled(body.enabled() == null || body.enabled() ? 1 : 0);
        if (body.apiKey() != null && !body.apiKey().isBlank()) {
            entity.setApiKeyCipher(cipher.encrypt(body.apiKey().trim()));
        } else if (entity.getId() == null) {
            throw new IllegalArgumentException("添加 AI 服务商时必须填写密钥");
        }
        configStore.saveProvider(entity);
        return Map.of("id", entity.getId(), "ok", true);
    }

    @DeleteMapping("/providers/{id}")
    public Map<String, Object> deleteProvider(@PathVariable Long id) {
        configStore.deleteProvider(id);
        return Map.of("ok", true);
    }

    // ---------------------------------------------------------------- AI 配置

    public record AiConfigBody(Long providerId, String introduce, String promptTemplate,
                               String greetingPrompt, Boolean qualityGateEnabled,
                               String templateGreeting, Integer jdMaxChars, Integer aiTimeoutSeconds,
                               Boolean collectEffectMetrics, Boolean aiReplyEnabled,
                               String aiReplyStyle, Integer aiReplyTimeoutSeconds,
                               Integer aiReplyMaxPerSweep, Boolean aiReplyToneCheckEnabled,
                               String aiReplyToneTerms, Integer aiReplySkipAlertPct,
                               Integer aiReplySkipAlertMinSample) {
    }

    @GetMapping("/ai")
    public Map<String, Object> aiConfig() {
        AiConfigEntity entity = configStore.aiConfig();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entity.getId());
        out.put("providerId", entity.getProviderId());
        out.put("introduce", entity.getIntroduce());
        out.put("promptTemplate", entity.getPromptTemplate());
        out.put("greetingPrompt", entity.getGreetingPrompt());
        out.put("qualityGateEnabled", entity.getQualityGateEnabled() != null && entity.getQualityGateEnabled() == 1);
        out.put("templateGreeting", entity.getTemplateGreeting());
        out.put("jdMaxChars", entity.getJdMaxChars());
        out.put("aiTimeoutSeconds", entity.getAiTimeoutSeconds());
        out.put("collectEffectMetrics", entity.getCollectEffectMetrics() != null
                && entity.getCollectEffectMetrics() == 1);
        out.put("aiReplyEnabled", entity.getAiReplyEnabled() != null && entity.getAiReplyEnabled() == 1);
        out.put("aiReplyStyle", entity.getAiReplyStyle());
        // 只读默认值：控制台用它填 placeholder，不必在前端硬编码一份 —— 两份迟早分叉
        out.put("aiReplyStyleDefault", AiReplier.DEFAULT_STYLE);
        out.put("aiReplyTimeoutSeconds", ConfigStore.clampAiReplyTimeoutSeconds(entity.getAiReplyTimeoutSeconds()));
        out.put("aiReplyMaxPerSweep", ConfigStore.clampAiReplyMaxPerSweep(entity.getAiReplyMaxPerSweep()));
        out.put("aiReplyToneCheckEnabled", entity.getAiReplyToneCheckEnabled() == null
                || entity.getAiReplyToneCheckEnabled() == 1);
        out.put("aiReplyToneTerms", entity.getAiReplyToneTerms() == null ? "" : entity.getAiReplyToneTerms());
        // 只读默认词表与生效条数：控制台用它渲染 placeholder 和「留空则使用默认（31 条）」。
        // 「生效条数」留空时是默认条数而不是 0 —— 只显示输入框为空会让人以为检查没在工作
        out.put("aiReplyToneTermsDefault", String.join("\n", ReplyGuard.DEFAULT_TONE_TERMS));
        List<String> configuredTerms = configStore.toneTerms();
        out.put("aiReplyToneTermsEffectiveCount",
                configuredTerms.isEmpty() ? ReplyGuard.DEFAULT_TONE_TERMS.size() : configuredTerms.size());
        out.put("aiReplySkipAlertPct", ConfigStore.clampSkipAlertPct(entity.getAiReplySkipAlertPct()));
        out.put("aiReplySkipAlertMinSample",
                ConfigStore.clampSkipAlertMinSample(entity.getAiReplySkipAlertMinSample()));
        return out;
    }

    @PutMapping("/ai")
    public Map<String, Object> saveAiConfig(@RequestBody AiConfigBody body) {
        AiConfigEntity entity = configStore.aiConfig();
        entity.setProviderId(body.providerId());
        entity.setIntroduce(nullToEmpty(body.introduce()));
        entity.setPromptTemplate(nullToEmpty(body.promptTemplate()));
        entity.setGreetingPrompt(nullToEmpty(body.greetingPrompt()));
        entity.setQualityGateEnabled(Boolean.TRUE.equals(body.qualityGateEnabled()) ? 1 : 0);
        entity.setTemplateGreeting(nullToEmpty(body.templateGreeting()));
        entity.setJdMaxChars(body.jdMaxChars() == null ? 4000 : body.jdMaxChars());
        entity.setAiTimeoutSeconds(body.aiTimeoutSeconds() == null ? 20 : body.aiTimeoutSeconds());
        entity.setCollectEffectMetrics(Boolean.TRUE.equals(body.collectEffectMetrics()) ? 1 : 0);
        // 未传则保持原值：控制台 P4 之前不会提交这个字段，
        // 若按「null 当 false」处理，保存任何一个无关配置都会静默把值守关掉。
        if (body.aiReplyEnabled() != null) {
            entity.setAiReplyEnabled(body.aiReplyEnabled() ? 1 : 0);
        }
        // 同样「未传则保持原值」：清空风格是合法的（回落到 DEFAULT_STYLE），
        // 但「没提交这个字段」不等于「清空」—— 两者必须能区分，否则保存任何无关配置都会抹掉风格
        if (body.aiReplyStyle() != null) {
            entity.setAiReplyStyle(ConfigStore.clampStyle(body.aiReplyStyle()));
        }
        if (body.aiReplyTimeoutSeconds() != null) {
            entity.setAiReplyTimeoutSeconds(ConfigStore.clampAiReplyTimeoutSeconds(body.aiReplyTimeoutSeconds()));
        }
        if (body.aiReplyMaxPerSweep() != null) {
            entity.setAiReplyMaxPerSweep(ConfigStore.clampAiReplyMaxPerSweep(body.aiReplyMaxPerSweep()));
        }
        if (body.aiReplyToneCheckEnabled() != null) {
            entity.setAiReplyToneCheckEnabled(body.aiReplyToneCheckEnabled() ? 1 : 0);
        }
        // 词表：清洗后落库，并把丢弃行数回给前端 —— 用户粘了一大段文本却只看到「已保存」，
        // 会以为整段都生效了
        int droppedTerms = 0;
        if (body.aiReplyToneTerms() != null) {
            ConfigStore.ToneTerms terms = ConfigStore.normalizeToneTerms(body.aiReplyToneTerms());
            entity.setAiReplyToneTerms(terms.text());
            droppedTerms = terms.dropped();
        }
        if (body.aiReplySkipAlertPct() != null) {
            entity.setAiReplySkipAlertPct(ConfigStore.clampSkipAlertPct(body.aiReplySkipAlertPct()));
        }
        if (body.aiReplySkipAlertMinSample() != null) {
            entity.setAiReplySkipAlertMinSample(
                    ConfigStore.clampSkipAlertMinSample(body.aiReplySkipAlertMinSample()));
        }
        configStore.saveAiConfig(entity);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("toneTermsDropped", droppedTerms);
        return out;
    }

    // ---------------------------------------------------------------- 平台配置

    public record PlatformConfigBody(
            Boolean enabled, Boolean scanEnabled, Boolean watchEnabled,
            List<String> cities, List<String> keywords, String jobType,
            List<String> salary, List<String> experience, List<String> degree, List<String> scale,
            List<String> industry, List<String> stage, String activeLevelFloor,
            Integer waitTimeSeconds, Integer dailyDeliveryQuota, Integer dailyEvalLimit,
            Integer scrollRoundLimit, Integer combinationTimeLimitSec, Integer unreadScanLimit,
            Integer watchIntervalSeconds,
            Boolean repeatChatEnabled,
            List<String> repeatChatMessages, Boolean resumeImageEnabled,
            String dutyStartTime, String dutyEndTime) {
    }

    @GetMapping("/platform/{platform}")
    public Map<String, Object> platformConfig(@PathVariable String platform) {
        PlatformConfigEntity entity = configStore.platformConfig(platform);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform", entity.getPlatform());
        out.put("enabled", entity.getEnabled() != null && entity.getEnabled() == 1);
        // 两个开关按「null 即开」回发，与 ConfigStore 的读法一致：库里这一列有 DEFAULT 1，
        // 真读到 null 只可能是实体刚构造还没落库，此时「默认开着」才是它真正的行为
        out.put("scanEnabled", entity.getScanEnabled() == null || entity.getScanEnabled() != 0);
        out.put("watchEnabled", entity.getWatchEnabled() == null || entity.getWatchEnabled() != 0);
        out.put("cities", configStore.readList(entity.getCities()));
        out.put("keywords", configStore.readList(entity.getKeywords()));
        out.put("jobType", entity.getJobType());
        out.put("salary", configStore.readList(entity.getSalary()));
        out.put("experience", configStore.readList(entity.getExperience()));
        out.put("degree", configStore.readList(entity.getDegree()));
        out.put("scale", configStore.readList(entity.getScale()));
        out.put("industry", configStore.readList(entity.getIndustry()));
        out.put("stage", configStore.readList(entity.getStage()));
        out.put("activeLevelFloor", entity.getActiveLevelFloor());
        out.put("activeLevelOptions", com.chatjob.evaluate.ActiveLevel.selectable());
        out.put("waitTimeSeconds", entity.getWaitTimeSeconds());
        out.put("dailyDeliveryQuota", entity.getDailyDeliveryQuota());
        out.put("dailyEvalLimit", entity.getDailyEvalLimit());
        out.put("scrollRoundLimit", entity.getScrollRoundLimit());
        out.put("combinationTimeLimitSec", entity.getCombinationTimeLimitSec());
        // 回发归一后的值：库里若被手工改脏，界面显示的就是实际生效的那个数
        out.put("unreadScanLimit", ConfigStore.clampUnreadScanLimit(entity.getUnreadScanLimit()));
        out.put("watchIntervalSeconds", ConfigStore.clampWatchInterval(entity.getWatchIntervalSeconds()));
        out.put("repeatChatEnabled", entity.getRepeatChatEnabled() != null && entity.getRepeatChatEnabled() == 1);
        out.put("repeatChatMessages", configStore.readList(entity.getRepeatChatMessages()));
        out.put("resumeImageEnabled", entity.getResumeImageEnabled() != null
                && entity.getResumeImageEnabled() == 1);
        DutyWindow window = DutyWindow.of(entity);
        out.put("dutyStartTime", DutyWindow.format(window.start()));
        out.put("dutyEndTime", DutyWindow.format(window.end()));
        return out;
    }

    @PutMapping("/platform/{platform}")
    public Map<String, Object> savePlatformConfig(@PathVariable String platform,
                                                  @RequestBody PlatformConfigBody body) {
        PlatformConfigEntity entity = configStore.platformConfig(platform);
        boolean turningOff = body.enabled() != null && !body.enabled()
                && (entity.getEnabled() == null || entity.getEnabled() != 0);
        // 停用前先看这个平台有没有未结束的 Run：有的话「关掉开关」会留下一条还在跑的任务，
        // 而它的浏览器已经打开、值守还在继续 —— 开关等于没关，人却以为关了
        if (turningOff) {
            runStore.latest(platform).ifPresent(run -> {
                RunStatus status = RunStatus.valueOf(run.getStatus());
                if (!status.isTerminal()) {
                    throw new IllegalArgumentException("「"
                            + com.chatjob.platform.PlatformInfo.of(entity.getPlatform()).label()
                            + "」还有一个没结束的任务（编号 " + run.getId()
                            + "）：请先停止它，再停用这个平台");
                }
            });
        }
        entity.setEnabled(Boolean.TRUE.equals(body.enabled()) ? 1 : 0);
        entity.setCities(configStore.writeList(body.cities()));
        entity.setKeywords(configStore.writeList(body.keywords()));
        entity.setJobType(nullToEmpty(body.jobType()));
        entity.setSalary(configStore.writeList(body.salary()));
        entity.setExperience(configStore.writeList(body.experience()));
        entity.setDegree(configStore.writeList(body.degree()));
        entity.setScale(configStore.writeList(body.scale()));
        entity.setIndustry(configStore.writeList(body.industry()));
        entity.setStage(configStore.writeList(body.stage()));
        entity.setActiveLevelFloor(body.activeLevelFloor() == null ? "本月" : body.activeLevelFloor());
        entity.setWaitTimeSeconds(body.waitTimeSeconds() == null ? 10 : body.waitTimeSeconds());
        entity.setDailyDeliveryQuota(body.dailyDeliveryQuota() == null ? 30 : body.dailyDeliveryQuota());
        entity.setDailyEvalLimit(body.dailyEvalLimit() == null ? 150 : body.dailyEvalLimit());
        entity.setScrollRoundLimit(body.scrollRoundLimit() == null ? 300 : body.scrollRoundLimit());
        entity.setCombinationTimeLimitSec(body.combinationTimeLimitSec() == null
                ? 1200 : body.combinationTimeLimitSec());
        // 未传则保持原值（与 aiReplyEnabled 同一口径）：旧版控制台会把整个 platform 对象 PUT 回来，
        // 按「null 当默认值」处理的话，保存任何一个无关配置都会把扫描上限重置掉。
        if (body.unreadScanLimit() != null) {
            entity.setUnreadScanLimit(ConfigStore.clampUnreadScanLimit(body.unreadScanLimit()));
        }
        // 同一口径：未传则保持原值。旧版控制台不会提交这个字段，
        // 按「null 当默认 120」处理会把用户调过的间隔静默改回去。
        if (body.watchIntervalSeconds() != null) {
            entity.setWatchIntervalSeconds(ConfigStore.clampWatchInterval(body.watchIntervalSeconds()));
        }
        entity.setRepeatChatEnabled(Boolean.TRUE.equals(body.repeatChatEnabled()) ? 1 : 0);
        entity.setRepeatChatMessages(configStore.writeList(body.repeatChatMessages()));
        entity.setResumeImageEnabled(Boolean.TRUE.equals(body.resumeImageEnabled()) ? 1 : 0);
        entity.setDutyStartTime(DutyWindow.normalize(body.dutyStartTime(), ConfigStore.DEFAULT_DUTY_START));
        entity.setDutyEndTime(DutyWindow.normalize(body.dutyEndTime(), ConfigStore.DEFAULT_DUTY_END));
        // 两个开关同「未传则保持原值」口径：旧控制台不提交这两个字段，若按默认值处理，
        // 保存任何一个无关配置都会把用户关掉的开关重新打开（与 unreadScanLimit 同一个坑）。
        // 判定用合并后的最终值，不是请求里那个字段 —— 只传一个时，另一个要按库里的旧值参与判断。
        boolean scanOn = body.scanEnabled() != null
                ? body.scanEnabled()
                : (entity.getScanEnabled() == null || entity.getScanEnabled() != 0);
        boolean watchOn = body.watchEnabled() != null
                ? body.watchEnabled()
                : (entity.getWatchEnabled() == null || entity.getWatchEnabled() != 0);
        PlatformSwitches.requireSomethingOn(entity.getPlatform(), scanOn, watchOn);
        entity.setScanEnabled(scanOn ? 1 : 0);
        entity.setWatchEnabled(watchOn ? 1 : 0);
        configStore.savePlatformConfig(entity);
        if (turningOff) {
            // 停用的同一刻收掉它的浏览器：开着没意义，而且这正是「不再开第二个浏览器」要的效果
            sessions.existing(entity.getPlatform()).ifPresent(browser -> {
                try {
                    browser.session().close();
                    log.info("[{}] 平台已停用，浏览器会话已关闭", entity.getPlatform());
                } catch (Exception e) {
                    log.warn("[{}] 停用后关闭浏览器失败: {}", entity.getPlatform(), e.getMessage());
                }
            });
        }
        return Map.of("ok", true);
    }

    // ---------------------------------------------------------------- 字典

    @GetMapping("/dictionary")
    public List<Map<String, String>> dictionary(@RequestParam String platform,
                                                @RequestParam String type) {
        return optionStore.list(platform, type);
    }

    @PostMapping("/dictionary/reload")
    public Map<String, Object> reloadDictionary() {
        optionStore.reload();
        return Map.of("ok", true, "total", optionStore.total());
    }

    // ---------------------------------------------------------------- 工具

    @SuppressWarnings("unchecked")
    private Map<String, String> parseHeaders(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeHeaders(Map<String, String> headers) {
        try {
            return json.writeValueAsString(headers == null ? Map.of() : headers);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
