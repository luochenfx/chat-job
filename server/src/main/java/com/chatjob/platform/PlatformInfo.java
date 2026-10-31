package com.chatjob.platform;

import java.util.List;
import java.util.Locale;

/**
 * 平台元信息：给人看的名字，以及「这个平台支持哪些筛选组」。
 *
 * <p>为什么需要它：两个平台的筛选组不是同一套。Boss 有薪资/经验/学历/规模/行业/阶段六组，
 * 智联这一期只做「城市 + 关键词」。控制台如果照 Boss 的版式把所有筛选都摆出来，
 * 用户会填一堆"看着生效、实际被忽略"的条件 —— 那比不做筛选更糟。
 *
 * <p>所以能力表放在服务端、由 {@code /api/system/platforms}（单平台版是
 * {@code /api/system/platform}）下发，前端只做渲染，不自己判断平台。
 */
public final class PlatformInfo {

    private PlatformInfo() {
    }

    /** @param filters 该平台真正支持的搜索条件键名（与 platform_config 的列名一致） */
    public record Info(String platform, String label, List<String> filters, String note) {
    }

    /**
     * 平台<b>显示顺序</b>清单。控制台卡片按它排列，不按状态、不按活跃度 ——
     * 卡片会跳位置的话，人无法建立空间记忆，每次都要重读一遍平台名。
     *
     * <p><b>它不再等于「有哪些平台」</b>（阶段 2b）。能跑哪些平台由
     * {@link PlatformAdapters} 的键决定，那份来自实际装配的适配器；这里只负责顺序。
     * 两处不一致时的行为：声明了但没适配器 → 不出现（点了也跑不起来，不该显示）；
     * 有适配器但没声明 → 出现在末尾（宁可顺序不理想，也不让它从界面上消失）。
     */
    public static final List<String> KNOWN = List.of("boss", "zhaopin");

    /** 全部已知平台的元信息，顺序同 {@link #KNOWN} */
    public static List<Info> all() {
        return KNOWN.stream().map(PlatformInfo::of).toList();
    }

    public static Info of(String platform) {
        String key = platform == null ? "" : platform.toLowerCase(Locale.ROOT).trim();
        return switch (key) {
            case "zhaopin" -> new Info("zhaopin", "智联招聘",
                    // 只有城 + 关键词：其余筛选组的 code 表尚未建立（tech/0003 的 U4）
                    List.of("city", "keyword"),
                    "智联这一期只按「城市 + 关键词」搜索，其余筛选条件会被忽略");
            default -> new Info("boss", "BOSS 直聘",
                    List.of("city", "keyword", "jobType", "salary", "experience", "degree",
                            "scale", "industry", "stage"),
                    "六组筛选按配置叠加");
        };
    }
}
