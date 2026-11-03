package com.chatjob.watcher;

import com.chatjob.store.entity.ConversationEntity;
import com.chatjob.time.FixedTimeSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同会话回复冷却（{@link ReplyStore#REPLY_COOLDOWN_MINUTES} 分钟）。
 *
 * <p>它是「连着给同一个 HR 发两条」的最后一道闸。判错的方向不对称：判成「不冷却」会多发一条，
 * 判成「冷却」只是这一轮不回，下一轮还有机会。
 */
class ReplyStoreTest {

    private static final FixedTimeSource TIME = FixedTimeSource.of("2026-03-15T09:00:00");

    private final ReplyStore store = new ReplyStore(null, null, null, null, null, TIME);

    @Test
    void 从没回过的会话不构成冷却() {
        assertThat(store.inCooldown(null)).isFalse();
        assertThat(store.inCooldown(conversation(null))).isFalse();
    }

    @Test
    void 三分钟内回过算冷却() {
        assertThat(store.inCooldown(conversation("2026-03-15T08:58:00"))).isTrue();
        // 边界：差一秒还在窗口内
        assertThat(store.inCooldown(conversation("2026-03-15T08:57:01"))).isTrue();
    }

    @Test
    void 超过三分钟可以再回() {
        assertThat(store.inCooldown(conversation("2026-03-15T08:56:59"))).isFalse();
        assertThat(store.inCooldown(conversation("2026-03-14T09:00:00"))).isFalse();
    }

    @Test
    void 时间戳读不出来时按仍在冷却处理() {
        // 读不懂就不发：这一侧判错的代价是不可撤销的
        assertThat(store.inCooldown(conversation("2026-03-15 08:59:00"))).isTrue();
        assertThat(store.inCooldown(conversation("garbage"))).isTrue();
        assertThat(store.inCooldown(conversation(""))).isTrue();
    }

    private static ConversationEntity conversation(String lastOwnReplyAt) {
        ConversationEntity entity = new ConversationEntity();
        entity.setPlatform("boss");
        entity.setPlatformConversationId("张三");
        entity.setLastOwnReplyAt(lastOwnReplyAt);
        return entity;
    }
}
