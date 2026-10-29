package com.chatjob.watcher;

import com.chatjob.store.entity.UnreadTaskEntity;
import com.chatjob.store.mapper.UnreadTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务状态机单测。
 *
 * <p>用 hand-written fake 的 mapper（不引 mock 框架，与项目其余单测一致）。
 *
 * <p><b>它的边界要写清楚</b>：fake 复刻了 SQL 的过滤条件（在途状态、到期时间、次数上限），
 * 所以这里验证的是「状态机怎么流转」，不是「SQL 写得对不对」。SQL 本身由实机 drill 验证 ——
 * 填一个不可达的 provider baseUrl，看任务真的进 FAILED、1 分钟后重试、3 次后 GAVE_UP。
 * 别把这两件事混为一谈：fake 全绿不代表那段 SQL 在 SQLite 上跑得通。
 */
class UnreadTaskStoreTest {

    private static final String PLATFORM = "boss";
    private static final String KEY = "张三";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static UnreadTaskStore store(FakeMapper mapper) {
        return new UnreadTaskStore(mapper);
    }

    @Test
    void 同一会话重复落任务不堆积只刷新快照() {
        FakeMapper mapper = new FakeMapper();
        UnreadTaskStore store = store(mapper);

        UnreadTaskEntity first = store.openOrTouch(PLATFORM, KEY, 1L, 2, "aaaa");
        UnreadTaskEntity second = store.openOrTouch(PLATFORM, KEY, 1L, 5, "bbbb");

        assertThat(mapper.rows).hasSize(1);
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(mapper.rows.get(0).getUnreadCount()).isEqualTo(5);
        assertThat(mapper.rows.get(0).getLastMessageDigest()).isEqualTo("bbbb");
        assertThat(mapper.rows.get(0).getStatus()).isEqualTo(UnreadTaskStore.Status.PENDING.name());
        // 新消息刷新快照但不重置次数预算，否则持续失败的会话会被新消息无限续命
        assertThat(mapper.rows.get(0).getAttemptCount()).isZero();
    }

    @Test
    void DONE之后可以再建新任务() {
        FakeMapper mapper = new FakeMapper();
        UnreadTaskStore store = store(mapper);

        UnreadTaskEntity first = store.openOrTouch(PLATFORM, KEY, 1L, 1, "aaaa");
        store.markDone(first, "mid-1", UnreadTaskStore.Action.REPLIED, null, null);
        UnreadTaskEntity second = store.openOrTouch(PLATFORM, KEY, 1L, 1, "cccc");

        assertThat(mapper.rows).hasSize(2);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(mapper.rows.get(0).getStatus()).isEqualTo(UnreadTaskStore.Status.DONE.name());
    }

    @Test
    void 残留PROCESSING退回PENDING() {
        FakeMapper mapper = new FakeMapper();
        UnreadTaskStore store = store(mapper);

        UnreadTaskEntity task = store.openOrTouch(PLATFORM, KEY, 1L, 1, "aaaa");
        store.claim(task);
        assertThat(task.getStatus()).isEqualTo(UnreadTaskStore.Status.PROCESSING.name());

        UnreadTaskEntity again = store.openOrTouch(PLATFORM, KEY, 1L, 1, "aaaa");
        assertThat(again.getStatus()).isEqualTo(UnreadTaskStore.Status.PENDING.name());
    }

    @Test
    void 第一次失败排在一分钟后重试() {
        FakeMapper mapper = new FakeMapper();
        UnreadTaskStore store = store(mapper);
        UnreadTaskEntity task = store.openOrTouch(PLATFORM, KEY, 1L, 1, "aaaa");

        store.markFailed(task, UnreadTaskStore.Action.AI_FAILED, null, "boom");

        assertThat(task.getStatus()).isEqualTo(UnreadTaskStore.Status.FAILED.name());
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(minutesFromNow(task.getNextRetryAt())).isBetween(0.8, 1.2);
        assertThat(store.waitingForRetry(task)).isTrue();
        // 还没到期，本轮不该被捞出来
        assertThat(store.dueForRetry(10)).isEmpty();
    }

    @Test
    void 三次失败后GAVE_UP且不再被捞出() {
        FakeMapper mapper = new FakeMapper();
        UnreadTaskStore store = store(mapper);
        UnreadTaskEntity task = store.openOrTouch(PLATFORM, KEY, 1L, 1, "aaaa");

        store.markFailed(task, UnreadTaskStore.Action.AI_FAILED, null, "第一次");
        store.markFailed(task, UnreadTaskStore.Action.AI_FAILED, null, "第二次");
        store.markFailed(task, UnreadTaskStore.Action.AI_FAILED, null, "第三次");

        assertThat(task.getAttemptCount()).isEqualTo(UnreadTaskStore.DEFAULT_MAX_ATTEMPTS);
        assertThat(task.getStatus()).isEqualTo(UnreadTaskStore.Status.GAVE_UP.name());
        assertThat(task.getNextRetryAt()).isNull();
        assertThat(task.getLastError()).isEqualTo("第三次");

        // 放弃之后即使手动把时间调到过去也不该再被自动捞出
        mapper.rows.get(0).setNextRetryAt("2000-01-01T00:00:00");
        assertThat(store.dueForRetry(10)).isEmpty();
    }

    @Test
    void 到期的失败任务按时间顺序被捞出() {
        FakeMapper mapper = new FakeMapper();
        UnreadTaskStore store = store(mapper);
        UnreadTaskEntity a = store.openOrTouch(PLATFORM, "甲", 1L, 1, "a");
        UnreadTaskEntity b = store.openOrTouch(PLATFORM, "乙", 2L, 1, "b");
        store.markFailed(a, UnreadTaskStore.Action.READ_FAILED, null, "读不到");
        store.markFailed(b, UnreadTaskStore.Action.READ_FAILED, null, "读不到");

        // 把两条都调到已经过期的时刻，模拟下一轮扫描
        mapper.rows.get(0).setNextRetryAt("2026-01-01T00:00:10");
        mapper.rows.get(1).setNextRetryAt("2026-01-01T00:00:20");

        List<UnreadTaskEntity> due = store.dueForRetry(10);
        assertThat(due).hasSize(2);
        assertThat(due.get(0).getPlatformConversationId()).isEqualTo("甲");
        assertThat(due.get(1).getPlatformConversationId()).isEqualTo("乙");
        assertThat(store.dueForRetry(1)).hasSize(1);
    }

    @Test
    void 手动重试只接受GAVE_UP并重置次数预算() {
        FakeMapper mapper = new FakeMapper();
        UnreadTaskStore store = store(mapper);
        UnreadTaskEntity task = store.openOrTouch(PLATFORM, KEY, 1L, 1, "aaaa");

        // PENDING 不接受
        assertThat(store.manualRetry(task.getId())).isFalse();
        // DONE 不接受
        store.markDone(task, "mid-1", UnreadTaskStore.Action.REPLIED, null, null);
        assertThat(store.manualRetry(task.getId())).isFalse();
        // 不存在不接受
        assertThat(store.manualRetry(999L)).isFalse();

        UnreadTaskEntity fresh = store.openOrTouch(PLATFORM, "李四", 2L, 1, "x");
        store.markFailed(fresh, UnreadTaskStore.Action.AI_FAILED, null, "1");
        store.markFailed(fresh, UnreadTaskStore.Action.AI_FAILED, null, "2");
        store.markFailed(fresh, UnreadTaskStore.Action.AI_FAILED, null, "3");
        assertThat(fresh.getStatus()).isEqualTo(UnreadTaskStore.Status.GAVE_UP.name());

        assertThat(store.manualRetry(fresh.getId())).isTrue();
        UnreadTaskEntity reloaded = mapper.findById(fresh.getId());
        assertThat(reloaded.getStatus()).isEqualTo(UnreadTaskStore.Status.PENDING.name());
        assertThat(reloaded.getAttemptCount()).isZero();
        assertThat(reloaded.getNextRetryAt()).isNull();
        assertThat(reloaded.getLastAction()).isEqualTo(UnreadTaskStore.Action.MANUAL_RETRY);
    }

    @Test
    void 退避阶梯为一分钟五分钟十五分钟() {
        assertThat(UnreadTaskStore.backoffMinutes(0)).isEqualTo(1);
        assertThat(UnreadTaskStore.backoffMinutes(1)).isEqualTo(1);
        assertThat(UnreadTaskStore.backoffMinutes(2)).isEqualTo(5);
        assertThat(UnreadTaskStore.backoffMinutes(3)).isEqualTo(15);
        // 超出阶梯用最后一档，不抛异常
        assertThat(UnreadTaskStore.backoffMinutes(9)).isEqualTo(15);
    }

    @Test
    void 失败任务缺少重试时间时视为立即可重试() {
        FakeMapper mapper = new FakeMapper();
        UnreadTaskStore store = store(mapper);
        UnreadTaskEntity task = store.openOrTouch(PLATFORM, KEY, 1L, 1, "aaaa");
        store.markFailed(task, UnreadTaskStore.Action.READ_FAILED, null, "x");

        // 脏数据/人为改库把 next_retry_at 抹掉时，不能让它永久卡在 FAILED：
        // 卡住的任务既不重试、也不会再出现在未读列表里，等于静默丢消息。
        mapper.rows.get(0).setNextRetryAt(null);
        assertThat(store.waitingForRetry(mapper.findById(task.getId()))).isFalse();
    }

    @Test
    void 摘要缺失时不覆盖已有快照() {
        FakeMapper mapper = new FakeMapper();
        UnreadTaskStore store = store(mapper);
        store.openOrTouch(PLATFORM, KEY, 1L, 1, "keep-me");

        UnreadTaskEntity again = store.openOrTouch(PLATFORM, KEY, 1L, 2, null);

        // 读不到摘要（最后一条是卡片）时保留旧摘要，别把用于校验的指纹擦掉
        assertThat(again.getLastMessageDigest()).isEqualTo("keep-me");
    }

    private static double minutesFromNow(String timestamp) {
        LocalDateTime at = LocalDateTime.parse(timestamp, TS);
        return (at.toEpochSecond(java.time.ZoneOffset.UTC)
                - LocalDateTime.now().toEpochSecond(java.time.ZoneOffset.UTC)) / 60.0;
    }

    private static UnreadTaskEntity copy(UnreadTaskEntity source) {
        UnreadTaskEntity target = new UnreadTaskEntity();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    /**
     * hand-written fake。刻意返回**副本**而不是行对象本身：真实 mapper 从库里读出来的
     * 一定是新对象，若 fake 直接给引用，那些「忘了 update 却以为已经落库」的 bug
     * 在单测里就永远暴露不出来。
     */
    private static final class FakeMapper implements UnreadTaskMapper {

        private static final Set<String> OPEN = Set.of("PENDING", "PROCESSING", "FAILED");

        private final List<UnreadTaskEntity> rows = new ArrayList<>();
        private long seq = 0;

        @Override
        public UnreadTaskEntity findById(Long id) {
            return rows.stream().filter(r -> r.getId().equals(id)).findFirst()
                    .map(UnreadTaskStoreTest::copy).orElse(null);
        }

        @Override
        public UnreadTaskEntity findOpen(String platform, String key) {
            return rows.stream()
                    .filter(r -> platform.equals(r.getPlatform()) && key.equals(r.getPlatformConversationId()))
                    .filter(r -> OPEN.contains(r.getStatus()))
                    .max(Comparator.comparing(UnreadTaskEntity::getId))
                    .map(UnreadTaskStoreTest::copy).orElse(null);
        }

        @Override
        public List<UnreadTaskEntity> findDueForRetry(String now, int limit) {
            return rows.stream()
                    .filter(r -> "FAILED".equals(r.getStatus()))
                    .filter(r -> r.getAttemptCount() < r.getMaxAttempts())
                    .filter(r -> r.getNextRetryAt() != null && r.getNextRetryAt().compareTo(now) <= 0)
                    .sorted(Comparator.comparing(UnreadTaskEntity::getNextRetryAt))
                    .limit(limit)
                    .map(UnreadTaskStoreTest::copy)
                    .toList();
        }

        @Override
        public int insert(UnreadTaskEntity entity) {
            UnreadTaskEntity row = copy(entity);
            row.setId(++seq);
            rows.add(row);
            entity.setId(row.getId());
            return 1;
        }

        @Override
        public int update(UnreadTaskEntity entity) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).getId().equals(entity.getId())) {
                    rows.set(i, copy(entity));
                    return 1;
                }
            }
            return 0;
        }
    }
}
