package com.chatjob.driver.human;

/**
 * 一次点击之后，页面自己打开了新标签页时的回调。
 *
 * <p>为什么要单独一个类型、而不是让本层写 {@code run_log}：理由与
 * {@link FallbackListener} 完全相同 —— {@code run_log} 需要 {@code runId}，
 * 而 {@link Humanizer} 是平台级实例，比一次 Run 活得久。
 *
 * <p><b>什么算"页面自己打开的"</b>：本轮的页面（列表页、消息页、投递页）都是
 * {@code BrowserSession.newPage()} 主动创建的；页面里 {@code <a target="_blank">} 被点到时
 * 浏览器创建的标签不在这份集合里。后者不是业务需要，留着只会越积越多——
 * 而且它停在最前面，会让"最后停在哪个页面"变成假象。
 */
@FunctionalInterface
public interface NewTabListener {

    /**
     * @param url  新标签的地址。读不到时是空串（页面可能刚创建、还没来得及导航）
     * @param note 触发场景，人话。例如「点第 12 张卡片」。会原样进 {@code run_log}
     */
    void onOpened(String url, String note);

    /** 没有监听者时用它：不必在每个调用点判空。 */
    NewTabListener NONE = (url, note) -> {
    };
}
