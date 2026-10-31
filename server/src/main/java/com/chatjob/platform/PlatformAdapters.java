package com.chatjob.platform;

import com.chatjob.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 平台 → 适配器 的注册表（ADR-0003 第 2 条在编排层的落点）。
 *
 * <p><b>为什么不再用单值注入</b>：阶段 1 时两个适配器靠
 * {@code @ConditionalOnProperty(chat-job.active-platform)} 二选一装配，注入点才能是单个
 * {@code PlatformAdapter}。那等于「一个进程只能有一个平台」，与并行直接矛盾。2b 起
 * 适配器<b>全部装配</b>，按平台索引取 —— 于是「有哪些平台」第一次真正等于「写了哪些适配器」。
 *
 * <p><b>为什么顺序要固定</b>：控制台的平台卡片按这个顺序排列。顺序随 bean 扫描结果变化时，
 * 卡片会自己换位置，人无法建立空间记忆。所以排序先按 {@link PlatformInfo#KNOWN} 的声明顺序，
 * 再把「注册了但没在 KNOWN 里声明」的平台按名字追加在后面 —— 接第三个平台时只改
 * {@code KNOWN} 一处，漏改也不会让新平台直接消失（会沉到末尾，仍然可见）。
 */
@Slf4j
@Component
public class PlatformAdapters {

    private final Map<String, PlatformAdapter> byPlatform;
    private final AppProperties props;

    public PlatformAdapters(List<PlatformAdapter> adapters, AppProperties props) {
        this.props = props;

        Map<String, PlatformAdapter> declared = new LinkedHashMap<>();
        for (PlatformAdapter adapter : adapters) {
            declared.put(normalizeKey(adapter.platform()), adapter);
        }

        Map<String, PlatformAdapter> ordered = new LinkedHashMap<>();
        for (String known : PlatformInfo.KNOWN) {
            PlatformAdapter adapter = declared.remove(normalizeKey(known));
            if (adapter != null) {
                ordered.put(normalizeKey(known), adapter);
            }
        }
        // 未在 KNOWN 声明的适配器排到末尾：宁可顺序不理想，也不要让它从界面上消失
        declared.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));

        this.byPlatform = Collections.unmodifiableMap(ordered);
        log.info("[adapters] 已装配平台: {}（未指定平台时缺省 {}）",
                byPlatform.keySet(), props.activePlatform());
    }

    /**
     * 取某个平台的适配器。
     *
     * @param platform 平台标识；空值按 {@code chat-job.active-platform} 处理 —— 与
     *                 {@code BrowserSessions.of(null)} 同一套兜底，避免调用点各自猜出不同结果
     * @throws IllegalArgumentException 该平台没有装配适配器。宁可当场报错，也不返回 null 让
     *         调用方在几百行之后炸在 NPE 上（那时已经分不清是哪个平台配错了）
     */
    public PlatformAdapter of(String platform) {
        String key = platformKey(platform);
        PlatformAdapter adapter = byPlatform.get(key);
        if (adapter == null) {
            throw new IllegalArgumentException("平台「" + key + "」没有适配器，已装配: " + byPlatform.keySet());
        }
        return adapter;
    }

    public Optional<PlatformAdapter> existing(String platform) {
        return Optional.ofNullable(byPlatform.get(platformKey(platform)));
    }

    /** 已装配的全部平台，顺序即控制台卡片顺序 */
    public Set<String> platforms() {
        return byPlatform.keySet();
    }

    /** 按平台索引的只读视图（{@code Map<String, PlatformAdapter>} 的注入语义） */
    public Map<String, PlatformAdapter> all() {
        return byPlatform;
    }

    /**
     * 平台标识归一化：空值落到缺省平台，其余转小写。
     *
     * <p>用 {@link Locale#ROOT} 而不是默认 locale —— 土耳其语环境下 {@code "BOSS".toLowerCase()}
     * 得到的是 {@code "boss"} 之外的东西（无点 i），那会让同一台机器上的平台标识随系统语言变化。
     */
    public String platformKey(String platform) {
        if (platform == null || platform.isBlank()) {
            return normalizeKey(props.activePlatform());
        }
        return normalizeKey(platform);
    }

    private static String normalizeKey(String platform) {
        return platform.trim().toLowerCase(Locale.ROOT);
    }
}
