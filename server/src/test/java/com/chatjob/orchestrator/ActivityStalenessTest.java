package com.chatjob.orchestrator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 陈旧阈值。压错的代价是误报：界面报「无响应」而其实只是在投递，用户会去掐掉一个正常任务。
 */
class ActivityStalenessTest {

    @Test
    void 扫描态按动作间隔的三倍_但不少于九十秒() {
        assertThat(ActivityStaleness.staleAfterSeconds(RunStatus.RUNNING, 10, 120)).isEqualTo(90);
        assertThat(ActivityStaleness.staleAfterSeconds(RunStatus.RUNNING, 60, 120)).isEqualTo(180);
    }

    @Test
    void 待命态按巡检间隔的三倍() {
        assertThat(ActivityStaleness.staleAfterSeconds(RunStatus.STANDBY, 10, 120)).isEqualTo(360);
        assertThat(ActivityStaleness.staleAfterSeconds(RunStatus.STANDBY, 10, 600)).isEqualTo(1800);
    }

    /** 小间隔不能把下限压破：动作间隔配成 3 秒，阈值仍是 90 秒 */
    @Test
    void 下限不被小间隔压破() {
        assertThat(ActivityStaleness.staleAfterSeconds(RunStatus.RUNNING, 3, 30)).isEqualTo(90);
        assertThat(ActivityStaleness.staleAfterSeconds(RunStatus.STANDBY, 3, 30)).isEqualTo(90);
    }

    /** 暂停与挂起不做陈旧判定，但也不该返回 0 —— 界面上「0 秒前动过」是假信息 */
    @Test
    void 安静的状态给下限值() {
        assertThat(ActivityStaleness.staleAfterSeconds(RunStatus.PAUSED, 10, 120)).isEqualTo(90);
        assertThat(ActivityStaleness.staleAfterSeconds(RunStatus.WAITING_DUTY, 10, 120)).isEqualTo(90);
        assertThat(ActivityStaleness.staleAfterSeconds(null, 10, 120)).isEqualTo(90);
    }
}
