package com.chatjob.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业微信群机器人。
 *
 * <p>免费、不要公众号资质、支持 markdown、手机端直接收。选它是因为"自用工具不该为了
 * 收一条告警去申请一个服务号"。
 *
 * <p>地址来自 {@link NotifySettings}（控制台配置优先，留空回落 application.yaml），
 * 每次发送前现取，所以控制台保存后立即生效，不需要重启。
 *
 * <p>节流：一次运行里可能连续触发多次熔断，5 分钟窗口内同类型事件只发一条，
 * 否则手机被自家告警刷屏，最后你会把它静音 —— 那告警就失效了。用户自己点的测试消息
 * 不参与节流：那是为了立刻看到结果，合并掉就等于按钮失灵。
 */
@Slf4j
@Component
public class WecomRobotNotifier implements Notifier {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NotifySettings settings;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** kind|平台 → 上次发送时间戳 */
    private final Map<String, Long> lastSentAt = new ConcurrentHashMap<>();
    /** kind|平台 → 窗口内被合并掉的次数 */
    private final Map<String, Integer> suppressed = new ConcurrentHashMap<>();

    /** 最近一次投递尝试。只在内存里，重启清零 —— 它是运行态，不进配置表 */
    private volatile Delivery lastDelivery;

    public WecomRobotNotifier(NotifySettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @Override
    public void notify(String kind, String platform, String title, String body) {
        AlertCategory category = AlertCategory.ofKind(kind);
        if (!settings.alertEnabled(category)) {
            log.debug("告警类别已关闭，不发送: kind={} 类别={}", kind, category);
            return;
        }
        NotifySettings.Resolved target = settings.resolve();
        String head = headOf(platform, title);
        if (!target.configured()) {
            // 未配置也要留一条可见的记录：手机上收不到，页面上得能看出「为什么没收到」
            String why = target.source() == NotifySettings.Source.BROKEN
                    ? "已配置的地址解不开（可能换了 Windows 账户或电脑），请在系统页重新填写"
                    : "还没配置企业微信群机器人";
            log.info("[通知未发出] {} — {}（{}）", head, body, why);
            lastDelivery = new Delivery(LocalDateTime.now().format(TS), false, why);
            return;
        }
        String merged = mergeOrThrottle(kind, platform);
        if (merged == null) {
            return;
        }
        lastDelivery = send(target.webhook(), head, body + merged);
    }

    /**
     * 节流：窗口内同类型事件只发一条，把合并掉的次数交回去追加在正文里。
     *
     * @return 要追加的正文尾巴；被节流掉时返回 {@code null}
     */
    private String mergeOrThrottle(String kind, String platform) {
        if ("test".equals(kind)) {
            return "";
        }
        String throttleKey = throttleKeyOf(kind, platform);
        long now = System.currentTimeMillis();
        long windowMs = settings.throttleWindowSeconds() * 1000L;
        Long last = lastSentAt.get(throttleKey);
        if (last != null && now - last < windowMs) {
            suppressed.merge(throttleKey, 1, Integer::sum);
            log.debug("通知节流命中，已合并: key={}", throttleKey);
            return null;
        }
        lastSentAt.put(throttleKey, now);
        int count = suppressed.getOrDefault(throttleKey, 0);
        suppressed.remove(throttleKey);
        return count > 0 ? "\n\n> 此外还有 " + count + " 次同类事件被合并" : "";
    }

    /**
     * 标题带平台名：两个平台并行之后，手机上的「任务已熔断」不说是哪个平台等于没说
     * （spec 0004 阶段 2c 的验收：通知能看出是哪个平台）。
     *
     * <p>抽成静态方法纯粹为了可断言 —— 真正发 HTTP 的那一段在单测里没法验证。
     */
    static String headOf(String platform, String title) {
        return platform == null || platform.isBlank() ? title : "[" + platform + "] " + title;
    }

    /**
     * 节流键带平台：否则 BOSS 熔断之后 5 分钟内智联熔断会被合并掉，
     * 而两条告警说的是两条流水线，合并等于隐瞒了一条。
     */
    static String throttleKeyOf(String kind, String platform) {
        return platform == null || platform.isBlank() ? kind : kind + "|" + platform;
    }

    @Override
    public Delivery lastDelivery() {
        return lastDelivery;
    }

    private Delivery send(String webhook, String title, String body) {
        String at = LocalDateTime.now().format(TS);
        try {
            ObjectNode payload = json.createObjectNode();
            payload.put("msgtype", "markdown");
            payload.putObject("markdown").put("content", "**" + title + "**\n" + body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhook))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("企业微信通知失败: HTTP {} {}", response.statusCode(), response.body());
                return new Delivery(at, false, "HTTP " + response.statusCode() + "：" + brief(response.body()));
            }
            // 企业微信的失败有两种：HTTP 码和包体里的 errcode。机器人 key 失效时
            // HTTP 仍是 200，只看状态码会报「已发送」而手机上什么都没有
            String error = errcodeOf(response.body());
            if (error != null) {
                log.warn("企业微信通知被拒绝: {}", error);
                return new Delivery(at, false, error);
            }
            return new Delivery(at, true, "已发送");
        } catch (Exception e) {
            // 通知失败绝不能影响主流程
            log.warn("企业微信通知异常: {}", e.getMessage());
            return new Delivery(at, false, "发送异常：" + e.getMessage());
        }
    }

    /**
     * 读包体里的 errcode，非 0 时返回一句人能看懂的说明。
     *
     * <p>包体不是 JSON 时返回 {@code null} 按成功处理：HTTP 已经 200 了，
     * 解析不了只说明对方返回了别的东西，不该反过来判成「没发出去」。
     */
    private String errcodeOf(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(body);
            int errcode = node.path("errcode").asInt(0);
            if (errcode == 0) {
                return null;
            }
            // errmsg 后面常跟一长串 hint 与调试地址，界面上只留前半句
            return "被企业微信拒绝（errcode " + errcode + "）：" + brief(node.path("errmsg").asText(""));
        } catch (Exception e) {
            return null;
        }
    }

    /** 对方返回的内容可能很长，页面上只留一句 */
    private static String brief(String raw) {
        if (raw == null) {
            return "";
        }
        String one = raw.replaceAll("\\s+", " ").trim();
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }
}
