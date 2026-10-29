package com.chatjob.orchestrator;

import java.util.ArrayList;
import java.util.List;

/**
 * 组合队列 = 城市 × 关键词 的笛卡尔积。
 *
 * <p>六组筛选参数（薪资 / 经验 / 学历 / 规模 / 行业 / 阶段）是<b>全局一套、只叠加不展开</b>：
 * 它们也参与笛卡尔积会立刻把组合数炸掉，而它们的真实作用是缩小每个组合的返回集，
 * 让"横向铺"时每个组合只返回几十条 —— 300 轮上限因此基本碰不到。
 */
public final class CombinationPlanner {

    private CombinationPlanner() {
    }

    public record Combo(int seq, String city, String keyword) {
    }

    public static List<Combo> plan(List<String> cities, List<String> keywords) {
        List<String> cityList = clean(cities);
        List<String> keywordList = clean(keywords);
        if (cityList.isEmpty()) {
            cityList = List.of("");
        }
        if (keywordList.isEmpty()) {
            keywordList = List.of("");
        }
        List<Combo> combos = new ArrayList<>(cityList.size() * keywordList.size());
        int seq = 0;
        for (String city : cityList) {
            for (String keyword : keywordList) {
                combos.add(new Combo(seq++, city, keyword));
            }
        }
        return combos;
    }

    private static List<String> clean(List<String> input) {
        if (input == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(input.size());
        for (String value : input) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim());
            }
        }
        return out;
    }
}
