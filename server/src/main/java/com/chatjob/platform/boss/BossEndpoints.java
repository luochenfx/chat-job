package com.chatjob.platform.boss;

/** BOSS 的接口路径。抓数据一律走接口，不解析 DOM —— 抗改版。 */
public final class BossEndpoints {

    private BossEndpoints() {
    }

    public static final String ORIGIN = "https://www.zhipin.com";

    /** 岗位详情接口，点卡片时由页面自己发起 */
    public static final String JOB_DETAIL = "/wapi/zpgeek/job/detail.json";

    /** 聊天相关接口前缀（值守阶段用于判断是否真的发了消息） */
    public static final String CHAT_PREFIX = "/wapi/zpchat/";

    /** 登录页 */
    public static final String LOGIN_URL = ORIGIN + "/web/user/?ka=header-login";

    /** 详情页 URL 模板；encryptId 从 a.more-job-btn 的 href 里取 */
    public static String jobDetail(String encryptId) {
        return ORIGIN + "/job_detail/" + encryptId + ".html";
    }
}
