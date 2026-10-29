package com.chatjob.notify;

import com.chatjob.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业微信群机器人。
 *
 * <p>免费、不要公众号资质、支持 markdown、手机端直接收。选它是因为"自用工具不该为了
 * 收一条告警去申请一个服务号"。
 *
 * <p>节流：一次运行里可能连续触发多次熔断，5 分钟窗口内同类型事件只发一条，
 * 否则手机被自家告警刷屏，最后你会把它静音 —— 那告警就失效了。
 */
@Slf4j
@Component
public class WecomRobotNotifier implements Notifier {

    private final AppProperties props;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** kind → 上次发送时间戳 */
    private final Map<String, Long> lastSentAt = new ConcurrentHashMap<>();
    /** kind → 窗口内被合并掉的次数 */
    private final Map<String, Integer> suppressed = new ConcurrentHashMap<>();

    public WecomRobotNotifier(AppProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    @Override
    public void notify(String kind, String title, String body) {
        String webhook = props.notifyConfig() == null ? null : props.notifyConfig().wecomWebhook();
        if (webhook == null || webhook.isBlank()) {
            log.info("[通知未配置] {} — {}", title, body);
            return;
        }
        long now = System.currentTimeMillis();
        int windowMs = (props.notifyConfig().throttleWindowSeconds() == 0
                ? 300 : props.notifyConfig().throttleWindowSeconds()) * 1000;
        Long last = lastSentAt.get(kind);
        if (last != null && now - last < windowMs) {
            suppressed.merge(kind, 1, Integer::sum);
            log.debug("通知节流命中，已合并: kind={}", kind);
            return;
        }
        lastSentAt.put(kind, now);
        int merged = suppressed.getOrDefault(kind, 0);
        suppressed.remove(kind);
        String suffix = merged > 0 ? "\n\n> 此外还有 " + merged + " 次同类事件被合并" : "";
        send(title, body + suffix);
    }

    private void send(String title, String body) {
        try {
            ObjectNode payload = json.createObjectNode();
            payload.put("msgtype", "markdown");
            payload.putObject("markdown").put("content", "**" + title + "**\n" + body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.notifyConfig().wecomWebhook()))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("企业微信通知失败: HTTP {} {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            // 通知失败绝不能影响主流程
            log.warn("企业微信通知异常: {}", e.getMessage());
        }
    }
}
