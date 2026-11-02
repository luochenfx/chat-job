package com.chatjob.driver.human;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批次停顿的纯逻辑测试：不碰浏览器、不真的睡眠（{@link Pacing} 换成记录器）。
 *
 * <p>要钉住的是两件事：① 停顿只发生在批次边界上，不能每一张都停；② 批次长度与停顿时长
 * 都取自配置区间——写死成常数会让"随机周期"退化成固定节拍。
 */
class HumanBatchPacerTest {

    @Test
    void 满一批才停顿() {
        RecordingPacing pacing = new RecordingPacing(3);
        HumanBatchPacer pacer = new HumanBatchPacer(pacing);

        pacer.beforeNext();
        pacer.beforeNext();
        assertThat(pacing.pauses).as("前两条不该停").isEmpty();

        pacer.beforeNext();
        assertThat(pacing.pauses).as("第三条到点，停一次").hasSize(1);
    }

    @Test
    void 停顿之后重新抽批次长度() {
        RecordingPacing pacing = new RecordingPacing(2, 4);

        HumanBatchPacer pacer = new HumanBatchPacer(pacing);
        pacer.beforeNext();   // 第 1 条
        pacer.beforeNext();   // 第 2 条：到点，停一次，下一批改成 4 条
        assertThat(pacing.pauses).hasSize(1);

        pacer.beforeNext();   // 第 3 条
        pacer.beforeNext();   // 第 4 条
        pacer.beforeNext();   // 第 5 条
        assertThat(pacing.pauses).as("重新抽的批次是 4 条，第 3–5 条之间不该再停").hasSize(1);

        pacer.beforeNext();   // 第 6 条：第二段的第 4 条，到点
        assertThat(pacing.pauses).hasSize(2);
    }

    @Test
    void 停顿时长与批次长度都取自配置区间() {
        RecordingPacing pacing = new RecordingPacing(5, 8);

        HumanBatchPacer pacer = new HumanBatchPacer(pacing, 5, 8, 800, 2_000);
        for (int i = 0; i < 40; i++) {
            pacer.beforeNext();
        }

        assertThat(pacing.pauses).isNotEmpty();
        assertThat(pacing.pauses).allSatisfy(pause -> {
            assertThat(pause.minMs()).isEqualTo(800);
            assertThat(pause.maxMs()).isEqualTo(2_000);
        });
        // 40 条、每批 5–8 条：至少 5 次、最多 8 次停顿
        assertThat(pacing.pauses.size()).isBetween(5, 8);
        assertThat(pacing.drawRanges).allSatisfy(range -> {
            assertThat(range.min()).isEqualTo(5);
            assertThat(range.max()).isEqualTo(8);
        });
    }

    @Test
    void 参数不合法时直接失败() {
        RecordingPacing pacing = new RecordingPacing(5);
        assertThatThrownBy(() -> new HumanBatchPacer(pacing, 0, 3, 100, 200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HumanBatchPacer(pacing, 5, 3, 100, 200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HumanBatchPacer(pacing, 3, 5, 200, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 默认构造用的是 spec 0006 §3.5 的取值，这里只验证初始批次落在 5–8。 */
    @Test
    void 默认构造的初始批次在五到八之间() {
        RecordingPacing pacing = new RecordingPacing(5);
        HumanBatchPacer pacer = new HumanBatchPacer(pacing);
        assertThat(pacer.remaining()).isBetween(5, 8);
    }

    /**
     * 手写替身（项目禁 mock 框架）。批次长度按脚本顺序给出，用尽后重复最后一个；
     * 睡眠不真的睡，只记下区间。
     */
    private static final class RecordingPacing implements Pacing {

        private final Deque<Integer> scripted = new ArrayDeque<>();
        private final Deque<Integer> last = new ArrayDeque<>();
        final List<Pause> pauses = new ArrayList<>();
        final List<Range> drawRanges = new ArrayList<>();

        RecordingPacing(int... batches) {
            for (int batch : batches) {
                scripted.add(batch);
            }
        }

        @Override
        public void sleep(int minMs, int maxMs) {
            pauses.add(new Pause(minMs, maxMs));
        }

        @Override
        public void sleepMs(int ms) {
        }

        @Override
        public int uniformInt(int min, int max) {
            drawRanges.add(new Range(min, max));
            Integer next = scripted.poll();
            if (next == null) {
                next = last.isEmpty() ? min : last.peek();
            }
            last.clear();
            last.add(next);
            return next;
        }
    }

    private record Pause(int minMs, int maxMs) {
    }

    private record Range(int min, int max) {
    }
}
