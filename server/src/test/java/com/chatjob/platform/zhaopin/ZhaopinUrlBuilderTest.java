package com.chatjob.platform.zhaopin;

import com.chatjob.store.OptionStore;
import com.chatjob.store.entity.PlatformConfigEntity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 搜索 URL 的拼装规则。与 Boss 的关键差别：智联只认「城 + 关键词」，
 * 其余筛选必须被明确忽略（并留日志），而不是往 code 上乱映射。
 */
class ZhaopinUrlBuilderTest {

    private static ZhaopinUrlBuilder builder() {
        OptionStore store = OptionStore.inMemory(Map.of(
                "zhaopin|city", Map.of("福州", "681")));
        return new ZhaopinUrlBuilder(store);
    }

    private static PlatformConfigEntity config() {
        PlatformConfigEntity cfg = new PlatformConfigEntity();
        cfg.setPlatform("zhaopin");
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
    void 城市码与关键词拼成列表地址() {
        String url = builder().build(config(), "福州", "java");
        assertThat(url).startsWith("https://www.zhaopin.com/jobs/?");
        assertThat(url).contains("jl=681");
        assertThat(url).contains("kw=java");
    }

    @Test
    void 关键词做URL编码() {
        String url = builder().build(config(), "福州", "Java 后端");
        assertThat(url).contains("kw=Java+%E5%90%8E%E7%AB%AF");
    }

    /**
     * 城市码猜错的后果是「安静地返回别的城市的岗位」，比扫不到更严重，
     * 所以字典里没有时必须显式失败。
     */
    @Test
    void 字典里没有的城市直接报错而不是猜() {
        assertThatThrownBy(() -> builder().build(config(), "厦门", "java"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("城市码");
    }

    @Test
    void Boss的那套筛选条件不会被拼进智联的URL() {
        PlatformConfigEntity cfg = config();
        cfg.setSalary("[\"10-20K\"]");
        cfg.setDegree("[\"本科\"]");
        cfg.setIndustry("[\"软件/IT服务\"]");
        cfg.setJobType("全职");
        String url = builder().build(cfg, "福州", "java");
        assertThat(url).doesNotContain("sl=");
        assertThat(url).doesNotContain("salary");
        assertThat(url).doesNotContain("degree");
        assertThat(url).doesNotContain("jobType");
    }

    @Test
    void 关键词为空时只拼城市() {
        String url = builder().build(config(), "福州", " ");
        assertThat(url).endsWith("?jl=681");
    }
}
