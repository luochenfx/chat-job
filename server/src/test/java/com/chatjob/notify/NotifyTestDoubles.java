package com.chatjob.notify;

import com.chatjob.config.AppProperties;
import com.chatjob.config.SecretCipher;
import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.NotifyConfigEntity;

/**
 * 通知相关的测试替身。项目约定不引入 mock 框架，用到什么就手写一个。
 *
 * <p>{@link ConfigStore} 是具体类，这里只子类化覆盖 {@code notifyConfig()} ——
 * 告警这条路径不碰任何 mapper，构造时传 null 是安全的。
 */
final class NotifyTestDoubles {

    private NotifyTestDoubles() {
    }

    static FakeConfigStore configStore() {
        return new FakeConfigStore();
    }

    static AppProperties props(String yamlWebhook) {
        return props(yamlWebhook, 300);
    }

    static AppProperties props(String yamlWebhook, int throttleSeconds) {
        return new AppProperties(null, null, null,
                new AppProperties.NotifyProps(yamlWebhook, throttleSeconds), "boss");
    }

    /** 解密结果可控：{@code plain} 为 null 表示「解不开」 */
    static SecretCipher cipher(String plain) {
        return new FakeCipher(plain);
    }

    static class FakeConfigStore extends ConfigStore {
        final NotifyConfigEntity entity = new NotifyConfigEntity();

        FakeConfigStore() {
            super(null, null, null, null, null, null);
            entity.setWecomWebhookCipher("");
            entity.setAlertAbnormal(1);
            entity.setAlertStandby(1);
            entity.setAlertDutyEnd(1);
        }

        @Override
        public NotifyConfigEntity notifyConfig() {
            return entity;
        }
    }

    static final class ThrowingConfigStore extends FakeConfigStore {
        @Override
        public NotifyConfigEntity notifyConfig() {
            throw new IllegalStateException("database is locked");
        }
    }

    private static final class FakeCipher extends SecretCipher {
        private final String plain;

        FakeCipher(String plain) {
            this.plain = plain;
        }

        @Override
        public String decrypt(String cipherText) {
            return plain;
        }
    }
}
