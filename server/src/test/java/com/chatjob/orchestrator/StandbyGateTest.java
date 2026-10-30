package com.chatjob.orchestrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalTime;

import static com.chatjob.orchestrator.StandbyGate.Action.PARK;
import static com.chatjob.orchestrator.StandbyGate.Action.SCAN;
import static com.chatjob.orchestrator.StandbyGate.Action.WATCH;
import static com.chatjob.orchestrator.StandbyGate.StopReason.COMBOS_EXHAUSTED;
import static com.chatjob.orchestrator.StandbyGate.StopReason.EVAL_LIMIT;
import static com.chatjob.orchestrator.StandbyGate.StopReason.QUOTA;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 待命期「要不要回扫」的判定。
 *
 * <p>判错的两种代价都不小：该回扫却继续待命 = 用户调大配额后干等一天；
 * 不该回扫却回扫 = 组合队列空转，每轮都重新开搜索页。
 */
class StandbyGateTest {

    private static final DutyWindow WINDOW = new DutyWindow(LocalTime.of(9, 0), LocalTime.of(22, 0));
    private static final LocalTime IN_WINDOW = LocalTime.of(14, 0);
    private static final String TODAY = "2026-10-30";
    private static final String YESTERDAY = "2026-10-29";

    @Test
    void 配额用完且在时段内_继续待命() {
        assertThat(StandbyGate.decide(IN_WINDOW, WINDOW, QUOTA, TODAY, TODAY, 30, 30, 80, 150))
                .isEqualTo(WATCH);
    }

    @Test
    void 配额被调高_回扫() {
        assertThat(StandbyGate.decide(IN_WINDOW, WINDOW, QUOTA, TODAY, TODAY, 30, 35, 80, 150))
                .isEqualTo(SCAN);
    }

    @Test
    void 评估上限被调高_回扫() {
        assertThat(StandbyGate.decide(IN_WINDOW, WINDOW, EVAL_LIMIT, TODAY, TODAY, 12, 30, 150, 200))
                .isEqualTo(SCAN);
    }

    /**
     * 组合扫完说明「今天这个搜索条件已经翻到底了」，配额再多也没有新岗位可看。
     * 这一条不回扫，是为了防止同日空转死循环。
     */
    @Test
    void 组合已扫完_即使还有配额也不回扫() {
        assertThat(StandbyGate.decide(IN_WINDOW, WINDOW, COMBOS_EXHAUSTED, TODAY, TODAY, 3, 30, 40, 150))
                .isEqualTo(WATCH);
    }

    @Test
    void 跨自然日_无论如何回扫() {
        assertThat(StandbyGate.decide(IN_WINDOW, WINDOW, COMBOS_EXHAUSTED, YESTERDAY, TODAY, 30, 30, 150, 150))
                .isEqualTo(SCAN);
    }

    /** 从没扫过（scan_day 为空的旧数据）等同于跨日：要扫，但不复位组合队列 */
    @Test
    void 扫描日为空_按新的一天回扫() {
        assertThat(StandbyGate.decide(IN_WINDOW, WINDOW, COMBOS_EXHAUSTED, null, TODAY, 0, 30, 0, 150))
                .isEqualTo(SCAN);
    }

    /** 时段外不操作页面是硬约束，优先级高于「配额刚被调高」 */
    @Test
    void 时段外_挂起优先于一切() {
        assertThat(StandbyGate.decide(LocalTime.of(23, 30), WINDOW, QUOTA, TODAY, TODAY, 0, 30, 0, 150))
                .isEqualTo(PARK);
    }

    /**
     * 配额被调小（已投递数 ≥ 新配额）不该回扫：用户是在收紧，不是放开。
     * 与「调高即回扫」必须区分开，否则改小配额反而触发一次扫描。
     */
    @ParameterizedTest(name = "已投递 {0} / 配额 {1} → WATCH")
    @CsvSource({"30, 20", "30, 30"})
    void 配额持平或调小_继续待命(int delivered, int quota) {
        assertThat(StandbyGate.decide(IN_WINDOW, WINDOW, QUOTA, TODAY, TODAY, delivered, quota, 0, 150))
                .isEqualTo(WATCH);
    }
}
