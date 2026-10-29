package com.chatjob.orchestrator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CombinationPlannerTest {

    @Test
    void 城市乘关键词形成笛卡尔积() {
        List<CombinationPlanner.Combo> combos = CombinationPlanner.plan(
                List.of("福州", "厦门"), List.of("Java", "后端"));
        assertThat(combos).hasSize(4);
        assertThat(combos.get(0).city()).isEqualTo("福州");
        assertThat(combos.get(0).keyword()).isEqualTo("Java");
        assertThat(combos.get(3).city()).isEqualTo("厦门");
        assertThat(combos.get(3).keyword()).isEqualTo("后端");
        assertThat(combos).extracting(CombinationPlanner.Combo::seq)
                .containsExactly(0, 1, 2, 3);
    }

    @Test
    void 城市为空时退化为不限城市() {
        List<CombinationPlanner.Combo> combos = CombinationPlanner.plan(List.of(), List.of("Java"));
        assertThat(combos).hasSize(1);
        assertThat(combos.get(0).city()).isEmpty();
    }

    @Test
    void 关键词为空时退化为不限关键词() {
        List<CombinationPlanner.Combo> combos = CombinationPlanner.plan(List.of("福州"), List.of());
        assertThat(combos).hasSize(1);
        assertThat(combos.get(0).keyword()).isEmpty();
    }

    @Test
    void 空白项被剔除() {
        List<CombinationPlanner.Combo> combos = CombinationPlanner.plan(
                java.util.Arrays.asList("福州", "  ", null), java.util.Arrays.asList("Java", ""));
        assertThat(combos).hasSize(1);
    }
}
