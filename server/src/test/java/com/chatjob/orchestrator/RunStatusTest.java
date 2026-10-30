package com.chatjob.orchestrator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态归属错了不会报错，只会静默出事：等待态若被当成终止态，
 * 控制台的「这一轮还在」就会消失，用户以为任务已经结束。
 */
class RunStatusTest {

    @Test
    void 等待值守时段算进行中而不是终止() {
        assertThat(RunStatus.WAITING_DUTY.isActive()).isTrue();
        assertThat(RunStatus.WAITING_DUTY.isTerminal()).isFalse();
    }

    @Test
    void 运行与暂停仍是进行中() {
        assertThat(RunStatus.RUNNING.isActive()).isTrue();
        assertThat(RunStatus.PAUSED.isActive()).isTrue();
        assertThat(RunStatus.CREATED.isTerminal()).isFalse();
    }

    /**
     * 待命态若被算成终止态，界面会把「值守还在跑」渲染成「已结束」，
     * 用户看到的就是「扫描停了 = 收工了」，然后手动再点一次启动 —— 正是这个状态要避免的。
     */
    @Test
    void 值守待命算进行中而不是终止() {
        assertThat(RunStatus.STANDBY.isActive()).isTrue();
        assertThat(RunStatus.STANDBY.isTerminal()).isFalse();
    }

    /**
     * QUOTA_REACHED 已不再写入，但枚举留着读历史数据。
     * 它必须仍然是终止态：否则界面上那些历史行会显示成「还在跑」。
     */
    @Test
    void 历史配额态仍按终止态读() {
        assertThat(RunStatus.QUOTA_REACHED.isTerminal()).isTrue();
        assertThat(RunStatus.QUOTA_REACHED.isActive()).isFalse();
    }

    @Test
    void 终止态一个都不能被算成进行中() {
        for (RunStatus status : new RunStatus[]{
                RunStatus.COMPLETED, RunStatus.QUOTA_REACHED, RunStatus.CIRCUIT_OPEN,
                RunStatus.LOGIN_LOST, RunStatus.CANCELLED, RunStatus.FAILED}) {
            assertThat(status.isTerminal()).as(status.name()).isTrue();
            assertThat(status.isActive()).as(status.name()).isFalse();
        }
    }
}
