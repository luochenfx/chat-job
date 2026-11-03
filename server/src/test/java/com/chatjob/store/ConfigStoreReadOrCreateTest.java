package com.chatjob.store;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「读一行，没有就建一行」的三种走法。
 *
 * <p>用 lambda 而不是假件：这里要替换的只是三个动作（查、造、插），写成假件反而要为了一个
 * 接口造一个类。真并发不起线程 —— 「另一个线程刚好建好了」在观察上等价于「插失败之后查得到」，
 * 起线程只会让用例偶发失败。
 */
class ConfigStoreReadOrCreateTest {

    @Test
    void 已有行时不再插() {
        AtomicBoolean inserted = new AtomicBoolean(false);

        String result = ConfigStore.readOrCreate(() -> "existing", () -> "created", v -> inserted.set(true));

        assertThat(result).isEqualTo("existing");
        assertThat(inserted).isFalse();
    }

    @Test
    void 撞唯一约束时改为读取对方建好的那一份() {
        // 查第一次为空 → 插 → 撞约束（对方已建）→ 再查拿到对方那一份
        AtomicReference<String> stored = new AtomicReference<>();

        String result = ConfigStore.readOrCreate(
                stored::get,
                () -> "created-by-me",
                v -> {
                    stored.set("created-by-other");
                    throw new DuplicateKeyException("UNIQUE constraint failed: index 'uk_ai_config_single'");
                });

        assertThat(result).isEqualTo("created-by-other");
    }

    @Test
    void 重读仍为空时把原异常抛回去() {
        // 撞的不是「别人先建好了」，而是 NOT NULL 或外键这类真问题 —— 不能静默吞掉
        DuplicateKeyException real = new DuplicateKeyException("NOT NULL constraint failed: ai_config.id");

        assertThatThrownBy(() -> ConfigStore.readOrCreate(() -> null, () -> "created", v -> {
            throw real;
        })).isSameAs(real);
    }
}
