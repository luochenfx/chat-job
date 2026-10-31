package com.chatjob.platform.boss;

import com.chatjob.store.OptionStore;
import com.chatjob.store.entity.PlatformConfigEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BossUrlBuilderTest {

    private static BossUrlBuilder builder() {
        OptionStore store = OptionStore.inMemory(Map.of(
                "boss|city", Map.of("北京", "101010100"),
                "boss|jobType", Map.of("全职", "1901"),
                "boss|salary", Map.of("10-20K", "404", "20-50K", "405"),
                "boss|experience", Map.of("3-5年", "104"),
                "boss|degree", Map.of("本科", "203")));
        return new BossUrlBuilder(store, new ObjectMapper());
    }

    private static PlatformConfigEntity config() {
        PlatformConfigEntity cfg = new PlatformConfigEntity();
        cfg.setPlatform("boss");
        cfg.setSalary("[]");
        cfg.setExperience("[]");
        cfg.setDegree("[]");
        cfg.setScale("[]");
        cfg.setIndustry("[]");
        cfg.setStage("[]");
        cfg.setJobType("");
        return cfg;
    }

    @Test
    void 城市与关键词能拼出可用的搜索URL() {
        String url = builder().build(config(), "北京", "Java");
        assertThat(url).startsWith("https://www.zhipin.com/web/geek/jobs?");
        assertThat(url).contains("city=101010100");
        assertThat(url).contains("query=Java");
    }

    @Test
    void 列表参数用逗号分隔() {
        PlatformConfigEntity cfg = config();
        cfg.setSalary("[\"10-20K\",\"20-50K\"]");
        String url = builder().build(cfg, "北京", "Java");
        assertThat(url).contains("salary=404,405");
    }

    @Test
    void 出现不限时整个参数不拼() {
        PlatformConfigEntity cfg = config();
        cfg.setSalary("[\"不限\"]");
        String url = builder().build(cfg, "北京", "Java");
        assertThat(url).doesNotContain("salary=");
    }

    @Test
    void 字典里查不到的条件被忽略而不是拼成错误参数() {
        PlatformConfigEntity cfg = config();
        cfg.setDegree("[\"博士研究生\"]");
        String url = builder().build(cfg, "北京", "Java");
        assertThat(url).doesNotContain("degree=");
    }

    @Test
    void 关键词做URL编码() {
        String url = builder().build(config(), "北京", "Java 后端");
        assertThat(url).contains("query=Java+%E5%90%8E%E7%AB%AF");
    }

    @Test
    void 城市为空时不拼city参数() {
        String url = builder().build(config(), null, "Java");
        assertThat(url).doesNotContain("city=");
    }

    @Test
    void 单值参数与多值参数各自生效() {
        PlatformConfigEntity cfg = config();
        cfg.setJobType("全职");
        cfg.setExperience("[\"3-5年\"]");
        cfg.setDegree("[\"本科\"]");
        String url = builder().build(cfg, "北京", "Java");
        assertThat(url).contains("jobType=1901");
        assertThat(url).contains("experience=104");
        assertThat(url).contains("degree=203");
    }

    @Test
    void 手工填的逗号分隔也能解析() {
        PlatformConfigEntity cfg = config();
        cfg.setSalary("10-20K, 20-50K");
        String url = builder().build(cfg, "北京", "Java");
        assertThat(url).contains("salary=404,405");
    }
}
