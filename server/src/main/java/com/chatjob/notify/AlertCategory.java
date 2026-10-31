package com.chatjob.notify;

/**
 * 告警类别（CONTEXT.md「告警类别」）。
 *
 * <p>分组依据是「要本人做什么」，不是「事件发生在线路的哪一段」：
 * 异常要人立刻处理，待命与时段结束只要知道就好。后者每天必然发生，
 * 能单独关掉才不会逼着人把整个告警静音。
 */
public enum AlertCategory {

    /** 系统没跑起来或跑挂了：driver 不可用、自检未过、异常终止、熔断、登录失效 */
    ABNORMAL("异常告警"),

    /** 扫描已中止、值守继续。每天一次 */
    STANDBY("进入待命"),

    /** 值守时段结束，当天不再操作页面。每天一次 */
    DUTY_END("时段结束");

    private final String label;

    AlertCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * 事件类型 → 类别。{@code null} 表示「不受开关约束」。
     *
     * <p>只有用户自己点的测试消息不受约束。未知事件类型<b>归到异常</b>而不是放行：
     * 漏发一条真告警，比多发一条没人认识的消息严重得多 —— 前者会让人以为今天很顺利。
     */
    public static AlertCategory ofKind(String kind) {
        if (kind == null) {
            return ABNORMAL;
        }
        return switch (kind) {
            case "test" -> null;
            case "standby" -> STANDBY;
            case "duty-end" -> DUTY_END;
            default -> ABNORMAL;
        };
    }
}
