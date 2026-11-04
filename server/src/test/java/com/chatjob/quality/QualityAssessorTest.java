package com.chatjob.quality;

import com.chatjob.time.FixedTimeSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 触发判据（{@link QualityAssessor#due}）。纯函数，不连库也不调模型。
 *
 * <p>这两条判据都是「省钱但会漏」的类型：门槛太松会让一句「在吗」就触发一次调用，
 * 太紧会让一段越聊越深的会话永远停在第一次的分数上。
 */
class QualityAssessorTest {

    private static final FixedTimeSource TIME = FixedTimeSource.of("2026-03-15T09:00:00");

    @Test
    void 对话还没起来时不判() {
        assertThat(QualityAssessor.due(1, null, null, 2, 6, TIME)).isFalse();
        assertThat(QualityAssessor.due(0, null, null, 2, 6, TIME)).isFalse();
    }

    @Test
    void 达到门槛且从没判过就判() {
        assertThat(QualityAssessor.due(2, null, null, 2, 6, TIME)).isTrue();
    }

    @Test
    void 新增消息不够三条不重判() {
        // 上次判的时候已经 2 条，现在 4 条 —— 只多两句，结论大概率没变
        assertThat(QualityAssessor.due(4, 2, "2026-03-15T08:00:00", 2, 6, TIME)).isFalse();
    }

    @Test
    void 新增够了但间隔不到六小时也不重判() {
        assertThat(QualityAssessor.due(6, 2, "2026-03-15T08:00:00", 2, 6, TIME)).isFalse();
    }

    @Test
    void 新增够且间隔够就重判() {
        assertThat(QualityAssessor.due(6, 2, "2026-03-15T02:00:00", 2, 6, TIME)).isTrue();
    }

    @Test
    void 重判间隔填零表示每多三条就判() {
        assertThat(QualityAssessor.due(6, 2, "2026-03-15T08:59:00", 2, 0, TIME)).isTrue();
    }

    @Test
    void 上次时刻读不出来时偏向再判一次() {
        // 多花一次调用，好过这段会话因为一个脏时间戳从此不再被评估
        assertThat(QualityAssessor.due(6, 2, "不是时间", 2, 6, TIME)).isTrue();
    }

    @Test
    void 门槛配成零或负数按一条处理() {
        // 配成 0 的本意不可能是「对方一个字没说也判」，那是纯浪费
        assertThat(QualityAssessor.due(1, null, null, 0, 6, TIME)).isTrue();
        assertThat(QualityAssessor.due(0, null, null, -3, 6, TIME)).isFalse();
    }
}
