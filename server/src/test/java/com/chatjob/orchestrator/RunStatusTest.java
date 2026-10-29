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
