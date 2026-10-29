package com.chatjob.store;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chatjob.store.entity.PlatformOptionEntity;
import com.chatjob.store.mapper.PlatformOptionMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 字典查询：中文名 → code。
 *
 * <p>配置层存中文名（"福州"），执行层才换成 code（{@code 101070300}）。
 * 好处是配置可读，且字典更新不影响人已经填好的内容。
 */
@Slf4j
@Component
public class OptionStore {

    /** 平台 + 类型 → (名称 → code) */
    private final Map<String, Map<String, String>> index = new ConcurrentHashMap<>();

    private final PlatformOptionMapper mapper;

    public OptionStore(PlatformOptionMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void load() {
        if (mapper == null) {
            // 测试用实例：字典由 inMemory 预置，不碰数据库
            return;
        }
        reload();
    }

    /** 测试用：直接注入 name→code 映射，不启动 Spring、不连库 */
    public static OptionStore inMemory(Map<String, Map<String, String>> seed) {
        OptionStore store = new OptionStore(null);
        seed.forEach((key, value) -> store.index.put(key, new LinkedHashMap<>(value)));
        return store;
    }

    public synchronized void reload() {
        index.clear();
        List<PlatformOptionEntity> all = mapper.selectList(Wrappers.emptyWrapper());
        for (PlatformOptionEntity option : all) {
            index.computeIfAbsent(key(option.getPlatform(), option.getType()),
                            k -> new LinkedHashMap<>())
                    .put(option.getName(), option.getCode());
        }
        log.info("字典已加载: {} 组 / {} 条", index.size(), all.size());
    }

    public String codeOf(String platform, String type, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Map<String, String> map = index.get(key(platform, type));
        if (map == null) {
            return null;
        }
        String trimmed = name.trim();
        String code = map.get(trimmed);
        if (code == null) {
            log.warn("字典里找不到 {} / {} / {}，该筛选条件将被忽略", platform, type, trimmed);
        }
        return code;
    }

    public List<Map<String, String>> list(String platform, String type) {
        Map<String, String> map = index.getOrDefault(key(platform, type), Map.of());
        List<Map<String, String>> out = new ArrayList<>(map.size());
        map.forEach((name, code) -> out.add(Map.of("name", name, "code", code)));
        return out;
    }

    public int size(String platform, String type) {
        return index.getOrDefault(key(platform, type), Map.of()).size();
    }

    public int total() {
        return index.values().stream().mapToInt(Map::size).sum();
    }

    private static String key(String platform, String type) {
        return platform + "|" + type;
    }
}
