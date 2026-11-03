package com.chatjob.platform;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台 port 的数据形状守卫。
 *
 * <p>这两个 record 曾经各挂一个没人读的字段：{@code ScrollParams.waitTimeSeconds} 被调用方老实地
 * 从配置里抄进去，两个适配器却只读 {@code roundLimit}；{@code SearchQuery.cityCode} 更是从未被赋值
 * （唯一构造点传的是字面量 {@code null}）。删掉它们不需要改任何断言，所以删除本身不会让测试变红 ——
 * 反过来，加回来也不会。这类「签名说一套、实现做一套」只能靠结构断言挡住。
 *
 * <p>不用 grep：注释、文档、以及 {@code ZhaopinUrlBuilder} 里那个同名的局部变量都会被算进去，
 * 而真正要问的是「类型上还有没有这个组件」。
 *
 * <p>加字段之前先回答一句：谁读它？回答不出来就别加 —— 用户看到的是配置页上的旋钮，
 * 而旋钮拧不拧，取决于有没有人读这个值。
 */
class PlatformPortShapeTest {

    @Test
    void 搜索组合只带城市与关键词() {
        assertThat(recordComponentNames(Models.SearchQuery.class))
                .as("SearchQuery 只该有城市与关键词。城市码由各平台自己查表（智联的 ZhaopinUrlBuilder）")
                .containsExactly("city", "keyword");
    }

    @Test
    void 滚动参数不再打包成对象() {
        assertThatThrownBy(() -> Class.forName("com.chatjob.platform.Models$ScrollParams"))
                .as("ScrollParams 已删除：只有轮数上限一个值，单独造个参数对象等于让读代码的人多跳一次定义")
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void 滚动只收一个轮数上限() {
        Method scroll = Arrays.stream(PlatformAdapter.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("scrollUntilEnd"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PlatformAdapter 里找不到 scrollUntilEnd"));

        assertThat(Arrays.asList(scroll.getParameterTypes()))
                .as("scrollUntilEnd 多一个参数，就得能说清谁读它。卡间停顿属编排层（ScanSegment.pauseFor），"
                        + "适配器内部的滚动节奏属人类化交互层，两者都不该从这里传进来")
                .containsExactly(int.class, BooleanSupplier.class);
    }

    private static List<String> recordComponentNames(Class<?> type) {
        assertThat(type.isRecord()).as("%s 应当仍然是 record", type.getName()).isTrue();
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
