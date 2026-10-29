package com.chatjob.evaluate;

import java.util.List;

/**
 * HR 活跃度归一化。
 *
 * <p>平台给的是一句文案而不是枚举，文案里也<b>没有</b>「7 天」这一档
 * （只有 在线 / 刚刚 / 今日 / 3日内 / 本周 / 2周内 / 本月 / 2月内 / 半年前），
 * 所以阈值只能按档位定，不能按天数算。
 *
 * <p>映射表之外的未知文案归为「未知」，<b>不参与硬过滤</b>，交给 AI 判断 ——
 * 平台改文案时宁可放过，也不要因为全部落到"未知"而把整批岗位误杀。
 */
public enum ActiveLevel {

    ONLINE("在线", 0),
    JUST_NOW("刚刚", 1),
    TODAY("今日", 2),
    THREE_DAYS("3日内", 3),
    THIS_WEEK("本周", 4),
    TWO_WEEKS("2周内", 5),
    THIS_MONTH("本月", 6),
    TWO_MONTHS("2月内", 7),
    HALF_YEAR("半年前", 8),
    UNKNOWN("未知", 99);

    private final String label;
    private final int rank;

    ActiveLevel(String label, int rank) {
        this.label = label;
        this.rank = rank;
    }

    public String label() {
        return label;
    }

    public int rank() {
        return rank;
    }

    /** 配置可选项，控制台下拉直接用 */
    public static List<String> selectable() {
        return List.of("在线", "今日", "3日内", "本周", "本月", "不限");
    }

    /**
     * 子串匹配。顺序必须"越具体越先判" —— 「2月内活跃」也含「月」，
     * 「本月活跃」也含「月」，先判短词就会全部落到错的档位。
     */
    public static ActiveLevel of(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String text = raw.trim();
        if (text.contains("半年前") || text.contains("年前") || text.contains("年前活跃")) {
            return HALF_YEAR;
        }
        if (text.contains("2月内") || text.contains("两月内")) {
            return TWO_MONTHS;
        }
        if (text.contains("2周内") || text.contains("两周内")) {
            return TWO_WEEKS;
        }
        if (text.contains("本月")) {
            return THIS_MONTH;
        }
        if (text.contains("本周")) {
            return THIS_WEEK;
        }
        if (text.contains("3日") || text.contains("三日") || text.contains("3天")) {
            return THREE_DAYS;
        }
        if (text.contains("今日") || text.contains("今天")) {
            return TODAY;
        }
        if (text.contains("刚刚")) {
            return JUST_NOW;
        }
        if (text.contains("在线") || text.contains("活跃")) {
            // 「活跃」单独出现时无法判断档位，按未知处理交给 AI
            return text.contains("在线") ? ONLINE : UNKNOWN;
        }
        return UNKNOWN;
    }

    /** 档位名 → 枚举（配置里存的是 label） */
    public static ActiveLevel fromLabel(String label) {
        if (label == null || label.isBlank() || "不限".equals(label.trim())) {
            return null;
        }
        for (ActiveLevel level : values()) {
            if (level.label.equals(label.trim())) {
                return level;
            }
        }
        return null;
    }

    /**
     * 是否低于配置下限。
     *
     * @param level 岗位的实际档位
     * @param floor 配置下限，null 或「不限」表示不过滤
     */
    public static boolean isBelowFloor(ActiveLevel level, ActiveLevel floor) {
        if (floor == null || level == null || level == UNKNOWN) {
            return false;
        }
        return level.rank > floor.rank;
    }
}
