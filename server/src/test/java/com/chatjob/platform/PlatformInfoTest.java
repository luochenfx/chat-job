package com.chatjob.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台清单的形状（`/api/system/platforms` 的内容来源，spec 0004 阶段 2c-1）。
 *
 * <p>两件事值得钉住，都是「改错了界面会静默变错」的类型：
 * <ol>
 *   <li><b>顺序</b>：概览卡片按清单顺序固定排列，不按状态排。顺序一变，卡片会跳位置，
 *       人每次都得重读平台名。</li>
 *   <li><b>筛选组</b>：声明了但适配器不拼进搜索地址，用户就会填一堆「看着生效、实际被忽略」
 *       的条件 —— 那比不做筛选更糟（智联这一期只有城市 + 关键词）。</li>
 * </ol>
 */
class PlatformInfoTest {

    @Test
    void 清单顺序固定为_boss_在前() {
        assertThat(PlatformInfo.KNOWN).containsExactly("boss", "zhaopin");
        assertThat(PlatformInfo.all())
                .extracting(PlatformInfo.Info::platform)
                .containsExactly("boss", "zhaopin");
    }

    @Test
    void 智联只声明城市与关键词() {
        assertThat(PlatformInfo.of("zhaopin").filters()).containsExactly("city", "keyword");
    }

    @Test
    void boss_六组筛选都声明了() {
        assertThat(PlatformInfo.of("boss").filters())
                .containsExactly("city", "keyword", "jobType", "salary", "experience", "degree",
                        "scale", "industry", "stage");
    }

    /**
     * 往 {@link PlatformInfo#KNOWN} 里加平台却忘了给 {@code of()} 加分支时，它会落到
     * 默认分支拿到 BOSS 的名字 —— 界面上两张卡片同名，比报错难发现得多。
     * 用「名字互不相同」把这个遗漏挡在构建期。
     */
    @Test
    void 每个已知平台都有自己的名字() {
        assertThat(PlatformInfo.all())
                .extracting(PlatformInfo.Info::label)
                .doesNotHaveDuplicates();
        assertThat(PlatformInfo.all())
                .allSatisfy(info -> assertThat(info.label()).isNotBlank());
    }
}
