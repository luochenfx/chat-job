package com.chatjob.orchestrator;

/**
 * 「多久没动就算无响应」的阈值。
 *
 * <p>只做一件事、只在一个地方做：阈值规则如果前后端各写一份，两侧迟早分叉，
 * 表现是「界面报无响应、后端日志还在滚」这种最消耗信任的故障。
 *
 * <p>阈值 = {@code max(90 秒, 3 × 当前阶段期望间隔)}：
 * <ul>
 *   <li>{@code RUNNING} 的期望间隔是动作间隔（默认 10 秒），90 秒下限来自实测：
 *       一次投递含送达复核最坏可合法占用 60 秒以上（{@code confirmByChatButton} 的轮询上限），
 *       阈值压到 60 会误报。</li>
 *   <li>{@code STANDBY} 的期望间隔是巡检间隔（默认 120 秒），3 倍留出一次抖动 + 一次慢轮询的余量。</li>
 *   <li>其余状态不做陈旧判定 —— 暂停与挂起本来就该安静。它们也返回下限值，
 *       以免调用方拿到 0 之后在界面上算出「刚动过」。</li>
 * </ul>
 */
public final class ActivityStaleness {

    /** 下限：短于它一定误报 */
    public static final int FLOOR_SECONDS = 90;

    private ActivityStaleness() {
    }

    public static int staleAfterSeconds(RunStatus status, int waitSeconds, int watchSeconds) {
        if (status == null) {
            return FLOOR_SECONDS;
        }
        int expected = switch (status) {
            case RUNNING -> waitSeconds;
            case STANDBY -> watchSeconds;
            default -> -1;
        };
        if (expected <= 0) {
            return FLOOR_SECONDS;
        }
        return Math.max(FLOOR_SECONDS, expected * 3);
    }
}
