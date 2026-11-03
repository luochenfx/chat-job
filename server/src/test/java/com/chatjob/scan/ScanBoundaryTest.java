package com.chatjob.scan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 卡片边界的四个判据与它们的优先级。
 *
 * <p>这一段原来长在 {@code RunService.processCards} 的循环体里，而那个循环要开浏览器、要 sleep、
 * 要写库，所以「配额和上限同时到顶时先记哪一个」「刚好用满时间上限算不算超时」这两个问题
 * 只能靠读代码确认。判据搬出来之后它们就是普通的参数组合（ADR-0009）。
 *
 * <p>用参数化的名字而不是 {@code atCard(30, 30, 150, 150, ...)}：这个测试的价值在于
 * 「读一眼就知道哪条规则被钉住了」，一串裸数字做不到这件事。
 */
class ScanBoundaryTest {

    private static final int QUOTA = 30;
    private static final int EVAL_LIMIT = 150;
    private static final int LIMIT = 1200;

    @Test
    void 都还没到顶_这张卡照常处理() {
        assertThat(ScanBoundary.atCard(true, 3, QUOTA, 40, EVAL_LIMIT, 60, LIMIT)).isEmpty();
    }

    /**
     * 扫描开关排在配额之前：关掉扫描之后不得再有任何搜索页导航，
     * 哪怕配额同时也用完了，这一段的结束原因也该是「不扫了」而不是「投满了」。
     */
    @Test
    void 扫描开关关掉_压过已到顶的配额和上限() {
        assertThat(ScanBoundary.atCard(false, 99, QUOTA, 999, EVAL_LIMIT, 9999, LIMIT))
                .contains(ScanStop.SCAN_DISABLED);
    }

    @Test
    void 投递配额到顶_中止扫描() {
        assertThat(ScanBoundary.atCard(true, QUOTA, QUOTA, 0, EVAL_LIMIT, 10, LIMIT))
                .contains(ScanStop.QUOTA);
    }

    @Test
    void 配额差一次_还不算到顶() {
        assertThat(ScanBoundary.atCard(true, QUOTA - 1, QUOTA, 0, EVAL_LIMIT, 10, LIMIT)).isEmpty();
    }

    /** 两项同时到顶时记配额：改造前就是这个顺序，调换会让日报把「配额用完」读成「AI 配额用完」。 */
    @Test
    void 配额与评估同时到顶_记配额() {
        assertThat(ScanBoundary.atCard(true, QUOTA, QUOTA, EVAL_LIMIT, EVAL_LIMIT, 10, LIMIT))
                .contains(ScanStop.QUOTA);
    }

    @Test
    void 评估上限到顶_中止扫描() {
        assertThat(ScanBoundary.atCard(true, 1, QUOTA, EVAL_LIMIT, EVAL_LIMIT, 10, LIMIT))
                .contains(ScanStop.EVAL_LIMIT);
    }

    /**
     * 刚好用满不算超时：改造前的判据是 {@code usedSeconds > limitSeconds}，
     * 改成 {@code >=} 会让「跑满 1200 秒」从一个正常结局变成一次超时记录。
     */
    @Test
    void 刚好用满时间上限_不算超时() {
        assertThat(ScanBoundary.atCard(true, 1, QUOTA, 1, EVAL_LIMIT, LIMIT, LIMIT)).isEmpty();
    }

    @Test
    void 超时一秒_记组合超时() {
        assertThat(ScanBoundary.atCard(true, 1, QUOTA, 1, EVAL_LIMIT, LIMIT + 1, LIMIT))
                .contains(ScanStop.COMBINATION_TIMEOUT);
    }

    /** 0 与负数都是「不限时间」（配置页允许这么填），此时超时判据整段跳过。 */
    @Test
    void 时间上限为零或负_永远不因时间中止() {
        assertThat(ScanBoundary.atCard(true, 1, QUOTA, 1, EVAL_LIMIT, 999_999, 0)).isEmpty();
        assertThat(ScanBoundary.atCard(true, 1, QUOTA, 1, EVAL_LIMIT, 999_999, -1)).isEmpty();
    }

    /**
     * 「组合队列耗尽」不是卡片边界能产出的原因，它由 executeCombinations 在队列末尾给出。
     * 这里钉住返回值域，是为了让 <b>RunService 里 switch 的那条 throw 分支真的不可达</b>——
     * 否则它只是一句没人验证的断言。
     */
    @Test
    void 卡片边界永不返回组合耗尽() {
        for (boolean scanEnabled : new boolean[]{true, false}) {
            for (int delivered : new int[]{0, QUOTA - 1, QUOTA, QUOTA + 1}) {
                for (int evaluated : new int[]{0, EVAL_LIMIT - 1, EVAL_LIMIT, EVAL_LIMIT + 1}) {
                    for (long used : new long[]{0, LIMIT, LIMIT + 1}) {
                        for (int limit : new int[]{0, LIMIT}) {
                            // orElse(null) 而不是 OptionalAssert 的否定式：后者在旧版 AssertJ 上没有
                            assertThat(ScanBoundary.atCard(scanEnabled, delivered, QUOTA, evaluated,
                                    EVAL_LIMIT, used, limit).orElse(null))
                                    .as("scanEnabled=%s delivered=%s evaluated=%s used=%s limit=%s",
                                            scanEnabled, delivered, evaluated, used, limit)
                                    .isNotEqualTo(ScanStop.COMBOS_EXHAUSTED);
                        }
                    }
                }
            }
        }
    }
}
