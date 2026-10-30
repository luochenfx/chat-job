package com.chatjob.platform.zhaopin;

/**
 * 智联的入口地址与接口特征串。改版时优先改这里与 {@link ZhaopinSelectors}。
 *
 * <p>三个地址各管一件事，互相不能替代（实测 2026-10-31）：
 * <ul>
 *   <li>{@code www…/jobs/} —— 列表页，左卡片右详情。首屏 20 个岗位的数据内嵌在
 *       {@code __INITIAL_STATE__} 里；滚动加载出来的岗位只能从点开后的面板读。</li>
 *   <li>{@code www…/jobdetail/<positionNumber>.htm} —— 独立详情页，投递按钮与成功弹窗都在这里。</li>
 *   <li>{@code i.zhaopin.com/im} —— 消息中心，<b>另一个子域</b>，不在 www 下。</li>
 * </ul>
 */
public final class ZhaopinEndpoints {

    private ZhaopinEndpoints() {
    }

    public static final String ORIGIN = "https://www.zhaopin.com";
    public static final String LOGIN_URL = "https://www.zhaopin.com/";
    public static final String LIST_URL = ORIGIN + "/jobs/";
    public static final String IM_ORIGIN = "https://i.zhaopin.com";
    public static final String IM_URL = IM_ORIGIN + "/im";

    /**
     * 列表页首屏数据的内嵌变量名。整段 {@code script} 的内容是
     * {@code __INITIAL_STATE__=<一大段 JSON>}，顶层就有 {@code positionList}。
     */
    public static final String INITIAL_STATE_MARK = "__INITIAL_STATE__=";

    /*
     * 曾经在这里记过翻页 XHR「/c/i/search/positions」，靠监听它的响应补全滚动出来的岗位编号。
     * 实测（2026-10-31）那条监听一条响应都抓不到，而卡片照样从 20 涨到 196；
     * 岗位编号在面板的详情链接里就有，于是这条路整条废弃 —— 不再留一个抓不到东西的特征串。
     */

    public static String jobDetail(String positionNumber) {
        return ORIGIN + "/jobdetail/" + positionNumber + ".htm";
    }
}
