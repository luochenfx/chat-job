package com.chatjob.orchestrator;

import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.PlatformConfigEntity;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 值守时段——"什么时候允许打扰人"的硬边界。
 *
 * <p>它约束的不是"能不能点启动"，而是"启动后能不能持续跑、能不能发消息"。
 * 凌晨三点给 HR 发招呼语，换来的只会是被标记成骚扰，所以窗口外整条链路一起收工，
 * 而不是只挑几个动作跳过。
 *
 * <p>因此窗口的判定只发生在运行期，从不发生在启动期：人任何时候都可以点启动，
 * 窗口外启动的 Run 会在打开浏览器之前就以「不在值守时段」收工（见 RunService）。
 * 两处语义差别很关键——前者是拒绝操作，后者是「这次跑不了」。
 *
 * <p>三种写法：
 * <ul>
 *   <li>{@code start < end}：当天窗口，如 09:00–22:00。左闭右开，22:00:00 整已经在窗外</li>
 *   <li>{@code start > end}：跨天窗口，如 22:00–06:00</li>
 *   <li>{@code start == end}：全天不限，等价于关掉这个限制</li>
 * </ul>
 *
 * <p>容错方向是保守：格式写错时回落到默认窗口并告警，而不是当成全天——
 * 一个手滑的 "9点" 不该导致系统在凌晨继续发消息。
 */
@Slf4j
public record DutyWindow(LocalTime start, LocalTime end) {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private static final Pattern TIME = Pattern.compile("^(\\d{1,2}):(\\d{2})(?::\\d{2})?$");

    /** 格式非法的兜底窗口，与配置默认值一致 */
    private static final LocalTime FALLBACK_START = LocalTime.of(9, 0);
    private static final LocalTime FALLBACK_END = LocalTime.of(22, 0);

    public static DutyWindow of(PlatformConfigEntity cfg) {
        return new DutyWindow(
                parseOrDefault(cfg == null ? null : cfg.getDutyStartTime(), FALLBACK_START),
                parseOrDefault(cfg == null ? null : cfg.getDutyEndTime(), FALLBACK_END));
    }

    /** 解析 HH:mm 或 HH:mm:ss，失败返回 null（供接口层做严格校验） */
    public static LocalTime parse(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = TIME.matcher(raw.trim());
        if (!matcher.matches()) {
            return null;
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        if (hour > 23 || minute > 59) {
            return null;
        }
        return LocalTime.of(hour, minute);
    }

    private static LocalTime parseOrDefault(String raw, LocalTime fallback) {
        if (raw == null || raw.isBlank()) {
            // 迁移之前写进去的旧行可能没值，按默认窗口算，不按全天
            return fallback;
        }
        LocalTime parsed = parse(raw);
        if (parsed == null) {
            log.warn("值守时段格式不正确（{}），按默认 {}–{} 处理", raw, FALLBACK_START, FALLBACK_END);
            return fallback;
        }
        return parsed;
    }

    public static String format(LocalTime time) {
        return time.format(HHMM);
    }

    /** 全天不限 */
    public boolean alwaysOn() {
        return start.equals(end);
    }

    public boolean contains(LocalTime now) {
        if (alwaysOn()) {
            return true;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        // 跨天：窗口是 [start, 24:00) ∪ [00:00, end)
        return !now.isBefore(start) || now.isBefore(end);
    }

    public boolean allowsNow() {
        return contains(LocalTime.now());
    }

    /** 给人看的一句话，如 "09:00–22:00"、"22:00–次日 06:00"、"全天" */
    public String describe() {
        if (alwaysOn()) {
            return "全天";
        }
        return start.isBefore(end)
                ? format(start) + "–" + format(end)
                : format(start) + "–次日 " + format(end);
    }

    /** 不在窗口内时，告诉人下一次能真正执行是什么时候 */
    public String nextOpenHint(LocalTime now) {
        if (alwaysOn()) {
            return "";
        }
        if (start.isBefore(end)) {
            return now.isBefore(start) ? "今天 " + format(start) + " 之后才会真正执行"
                    : "明天 " + format(start) + " 之后才会真正执行";
        }
        return now.isBefore(start) ? "今天 " + format(start) + " 之后才会真正执行"
                : "今晚 " + format(start) + " 之后才会真正执行";
    }

    /**
     * 距收工的剩余分钟数，用来回答「这次能跑多久」。
     *
     * <p>窗口外与全天不限都返回 -1：前者根本没有可跑的时间，后者没有收工时刻，
     * 两者都不该被界面上算成「还剩 0 分钟」。
     */
    public long closesInMinutes(LocalTime now) {
        if (alwaysOn() || !contains(now)) {
            return -1;
        }
        long minutes = Duration.between(now, end).toMinutes();
        // 跨天窗口：end 在次日，Duration 求出负数，补一整天才对得上「还剩多久」
        return minutes < 0 ? minutes + 24 * 60 : minutes;
    }

    /**
     * 距窗口打开的分钟数，用来回答「还要等多久才开始」。
     *
     * <p>窗口内与全天不限都返回 -1：前者没必要等，后者没有关闭的时候。
     */
    public long opensInMinutes(LocalTime now) {
        if (alwaysOn() || contains(now)) {
            return -1;
        }
        long minutes = Duration.between(now, start).toMinutes();
        // 已过今天这个时刻（如 20:00 等明天 09:00），补一整天才对得上「还要等多久」
        return minutes < 0 ? minutes + 24 * 60 : minutes;
    }

    /** 约定：库里没配过时按默认窗口，而不是放任全天 */
    public static String normalize(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        LocalTime parsed = parse(raw);
        if (parsed == null) {
            throw new IllegalArgumentException("时间格式不正确：" + raw + "，请用 HH:mm，例如 "
                    + ConfigStore.DEFAULT_DUTY_START);
        }
        return format(parsed);
    }
}
