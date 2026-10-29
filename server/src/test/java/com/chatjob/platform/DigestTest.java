package com.chatjob.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DigestTest {

    @Test
    void 同一文本两次摘要一致() {
        assertThat(Digest.of("你好，方便聊聊吗")).isEqualTo(Digest.of("你好，方便聊聊吗"));
    }

    @Test
    void 首尾空白不影响摘要() {
        assertThat(Digest.of("  你好  ")).isEqualTo(Digest.of("你好"));
    }

    @Test
    void 文本变化即摘要变化() {
        assertThat(Digest.of("你好")).isNotEqualTo(Digest.of("你好啊"));
    }

    @Test
    void 摘要固定16位十六进制() {
        assertThat(Digest.of("随便一句话")).hasSize(16).matches("[0-9a-f]{16}");
    }

    @Test
    void 空白输入返回null表示无摘要可校验() {
        // null 不是"空摘要"，而是"这条会话没有可比的指纹"（例如最后一条是职位卡片）。
        // 调用方据此退化为仅标题精确匹配，而不是拿空串去比。
        assertThat(Digest.of(null)).isNull();
        assertThat(Digest.of("   ")).isNull();
    }
}
