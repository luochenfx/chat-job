package com.chatjob.notify;

import com.chatjob.config.AppProperties;
import com.chatjob.config.SecretCipher;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.NotifyConfigEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * 告警配置的解析层：把「库里的密文」与「yaml 里的明文」合成一个生效值（ADR-0006）。
 *
 * <p>为什么单独一层：配置有两个来源，而「谁优先、什么时候回落、回落时算哪边配的」
 * 必须只有一处判断。散在通知器和 Controller 里各写一遍，迟早会出现
 * 「页面显示未配置、实际却还在往旧群发」这种两边不一致的状态。
 */
@Slf4j
@Component
public class NotifySettings {

    /** 节流窗口缺省值，与 application.yaml 的 throttle-window-seconds 默认一致 */
    public static final int DEFAULT_THROTTLE_WINDOW_SECONDS = 300;

    /** 生效值来自哪一侧。展示在控制台上，让「没配」与「配在文件里」不再看起来一样 */
    public enum Source {
        /** 控制台配置的（库里存的） */
        DB,
        /** 库里为空，回落到 application.yaml / 启动参数 */
        YAML,
        /** 两边都没有 */
        NONE,
        /** 库里有密文但解不开 —— 换了 Windows 账户或换了电脑 */
        BROKEN
    }

    public record Resolved(Source source, String webhook) {
        public boolean configured() {
            return webhook != null;
        }
    }

    private final ConfigStore configStore;
    private final SecretCipher cipher;
    private final AppProperties props;

    public NotifySettings(ConfigStore configStore, SecretCipher cipher, AppProperties props) {
        this.configStore = configStore;
        this.cipher = cipher;
        this.props = props;
    }

    /**
     * 解析当前生效的 webhook。
     *
     * <p>读库失败按「未配置」处理并记日志，不抛出去：调用它的通知器运行在异常路径上
     * （任务熔断、启动失败），那条路径上再多一个异常，会把真正的失败原因盖掉。
     */
    public Resolved resolve() {
        String stored;
        try {
            stored = configStore.notifyConfig().getWecomWebhookCipher();
        } catch (Exception e) {
            log.warn("读取告警配置失败，本次按「未配置」处理: {}", e.getMessage());
            return new Resolved(Source.NONE, null);
        }
        if (stored != null && !stored.isBlank()) {
            String plain = cipher.decrypt(stored);
            if (plain != null && !plain.isBlank()) {
                return new Resolved(Source.DB, plain.trim());
            }
            // 有密文却解不开时不能悄悄回落到 yaml：那份可能指向另一个群，
            // 等于把告警发到用户以为早就换掉的地方去
            log.warn("告警 webhook 已配置但无法解密（可能换了 Windows 账户或换了电脑），请在系统页重新填写");
            return new Resolved(Source.BROKEN, null);
        }
        String fromYaml = props.notifyConfig() == null ? null : props.notifyConfig().wecomWebhook();
        if (fromYaml != null && !fromYaml.isBlank()) {
            return new Resolved(Source.YAML, fromYaml.trim());
        }
        return new Resolved(Source.NONE, null);
    }

    public boolean configured() {
        return resolve().configured();
    }

    /**
     * 这一类告警要不要发。
     *
     * <p>{@code null} 类别表示不受开关约束（用户自己点的测试消息）。
     * 开关读到 {@code null} 按<b>开</b>处理：列上有 {@code DEFAULT 1}，
     * 真读到 null 只可能是实体刚构造还没落库，此时「发」才是它真正的行为。
     */
    public boolean alertEnabled(AlertCategory category) {
        if (category == null) {
            return true;
        }
        NotifyConfigEntity cfg = configStore.notifyConfig();
        Integer flag = switch (category) {
            case ABNORMAL -> cfg.getAlertAbnormal();
            case STANDBY -> cfg.getAlertStandby();
            case DUTY_END -> cfg.getAlertDutyEnd();
        };
        return flag == null || flag != 0;
    }

    /** 节流窗口。0 按缺省值处理 —— 写成 0 的人想要的是「别关掉节流」，不是「每次都发」 */
    public int throttleWindowSeconds() {
        if (props.notifyConfig() == null) {
            return DEFAULT_THROTTLE_WINDOW_SECONDS;
        }
        int configured = props.notifyConfig().throttleWindowSeconds();
        return configured <= 0 ? DEFAULT_THROTTLE_WINDOW_SECONDS : configured;
    }

    /**
     * 校验并归一控制台提交的 webhook 地址。
     *
     * <p>只拦「明显填错」的两种：粘了半截、带了 http。网址通不通只有真发一次才知道，
     * 那件事交给「发送测试消息」。
     */
    public static String requireWebhookUrl(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("请填写企业微信群机器人的 Webhook 地址");
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException("这个地址格式不对，请重新复制企业微信机器人的 Webhook 地址");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Webhook 地址必须以 https:// 开头，并且包含完整域名");
        }
        return trimmed;
    }
}
