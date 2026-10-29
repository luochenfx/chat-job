package com.chatjob.watcher;

import com.chatjob.store.ConfigStore;
import com.chatjob.store.entity.UnreadTaskEntity;
import com.chatjob.store.mapper.UnreadTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 未读任务的持久化与状态机（spec 0002 §3.5）。
 *
 * <p>状态只有一个方向：{@code PENDING → PROCESSING → DONE / FAILED / GAVE_UP}，
 * 唯一的倒流是人工重试（{@code GAVE_UP → PENDING}，且只接受 GAVE_UP）。
 *
 * <p>为什么失败要重试而不是直接放弃：点开会话时红点已经消失，这条未读在列表里
 * 再也看不见了。不重试就等于静默丢消息。上限 3 次是为了不把一条注定回不出去的
 * 会话变成永动机 —— 到点转 {@code GAVE_UP}，交人工判断。
 *
 * <p>内存里的 {@link UnreadTaskEntity} 与库里同源：所有写方法都先改对象再整行 update，
 * 调用方拿到的对象始终是最新状态。这在单线程值守路径里够用（值守全部跑在
 * Playwright 专用线程上，不存在并发改同一行）。
 */
@Slf4j
@Component
public class UnreadTaskStore {

    public enum Status {
        PENDING, PROCESSING, DONE, FAILED, GAVE_UP
    }

    /**
     * {@code last_action} 的取值。
     *
     * <p>跳过类的取值直接复用 {@link ReplyRuleEngine.Action} 的名字（{@code SKIP_*}），
     * 不在这里重复定义一份 —— 两份枚举迟早会分叉。这里只放值守链路自己的动作。
     */
    public static final class Action {
        public static final String REPLIED = "REPLIED";
        public static final String SEND_FAILED = "SEND_FAILED";
        public static final String READ_FAILED = "READ_FAILED";
        public static final String AI_FAILED = "AI_FAILED";
        public static final String LOCATE_FAILED = "LOCATE_FAILED";
        public static final String MANUAL_RETRY = "MANUAL_RETRY";

        private Action() {
        }
    }

    /** 默认重试预算。到 3 次转 GAVE_UP（spec 2.1 第 3 条） */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    /**
     * 退避阶梯：第 1 次失败 1 分钟、第 2 次 5 分钟、第 3 次 15 分钟。
     *
     * <p>默认 {@code max_attempts=3} 时只会用到前两档 —— 第 3 次失败已经转 GAVE_UP。
     * 保留第三档是为了以后调高 {@code max_attempts} 时不必改代码。
     */
    private static final int[] BACKOFF_MINUTES = {1, 5, 15};

    /** 与 {@code ConfigStore.now()} 同格式；时间存可比较文本，字典序即时间序 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final UnreadTaskMapper mapper;

    public UnreadTaskStore(UnreadTaskMapper mapper) {
        this.mapper = mapper;
    }

    // ------------------------------------------------------------ 创建

    /**
     * 落一条任务，或在已有在途任务上刷新快照。**必须在 {@code readMessages} 之前调用** ——
     * 点开会话红点就没了，顺序错了这条消息就丢了。
     *
     * <p>为什么刷新而不新建：`uk_unread_task_open` 只允许一个会话有一条在途任务。
     * 同一会话连续来新消息时，更新的只是「未读数 + 摘要」这两项快照，
     * 不走新建；次数预算也不重置 —— 否则一条持续失败的会话会被 HR 的新消息
     * 无限续命，永远到不了 GAVE_UP。
     *
     * <p>PROCESSING 残留（上一轮被进程中断）在这里退回 PENDING：调用方既然又扫到了
     * 这条未读，就说明上一轮没有收尾。
     */
    public UnreadTaskEntity openOrTouch(String platform, String conversationKey, Long conversationId,
                                        int unread, String digest) {
        String now = ConfigStore.now();
        UnreadTaskEntity existing = mapper.findOpen(platform, conversationKey);
        if (existing == null) {
            UnreadTaskEntity task = new UnreadTaskEntity();
            task.setPlatform(platform);
            task.setPlatformConversationId(conversationKey);
            task.setConversationId(conversationId);
            task.setUnreadCount(unread);
            task.setLastMessageDigest(digest);
            task.setStatus(Status.PENDING.name());
            task.setAttemptCount(0);
            task.setMaxAttempts(DEFAULT_MAX_ATTEMPTS);
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            mapper.insert(task);
            return task;
        }
        existing.setConversationId(conversationId);
        existing.setUnreadCount(unread);
        if (digest != null) {
            existing.setLastMessageDigest(digest);
        }
        if (Status.PROCESSING.name().equals(existing.getStatus())) {
            log.warn("未读任务残留 PROCESSING，按中断处理，退回 PENDING | 会话={} | 次数={}",
                    conversationKey, existing.getAttemptCount());
            existing.setStatus(Status.PENDING.name());
        }
        existing.setUpdatedAt(now);
        mapper.update(existing);
        return existing;
    }

    // ------------------------------------------------------------ 查询

    public List<UnreadTaskEntity> dueForRetry(int limit) {
        return mapper.findDueForRetry(ConfigStore.now(), Math.max(1, limit));
    }

    public UnreadTaskEntity findById(Long id) {
        return id == null ? null : mapper.findById(id);
    }

    /**
     * 是否还在退避窗口内。窗口内的 FAILED 任务本轮不要碰：它已经排好了下次重试时间，
     * 提前处理等于把 3 分钟一次的扫描节奏变成「一直重试」。
     *
     * <p>时间读不出来（为空或脏数据）时返回 {@code false}，即「现在就可以重试」：
     * 宁可多重试一次，也不要让一条任务因为一个空字段永久卡住 ——
     * 卡住的任务既不重试也不再出现在列表里，等于静默丢消息。
     */
    public boolean waitingForRetry(UnreadTaskEntity task) {
        if (task == null || !Status.FAILED.name().equals(task.getStatus())) {
            return false;
        }
        return isAfter(task.getNextRetryAt(), ConfigStore.now());
    }

    // ------------------------------------------------------------ 流转

    /** 开始处理。落 PROCESSING 的好处是进程中途被杀时，下一轮能看出「中断」而不是「没处理」 */
    public UnreadTaskEntity claim(UnreadTaskEntity task) {
        task.setStatus(Status.PROCESSING.name());
        task.setUpdatedAt(ConfigStore.now());
        mapper.update(task);
        return task;
    }

    public void markDone(UnreadTaskEntity task, String platformMessageId, String action,
                         String reasonCode, String note) {
        task.setPlatformMessageId(platformMessageId);
        task.setStatus(Status.DONE.name());
        task.setLastAction(action);
        task.setLastReasonCode(reasonCode);
        task.setLastError(note);
        task.setNextRetryAt(null);
        task.setUpdatedAt(ConfigStore.now());
        mapper.update(task);
    }

    /**
     * 记一次失败：次数 +1，未耗尽则按退避排下次重试，耗尽则转 {@code GAVE_UP}。
     *
     * @param action REPLIED 之外的失败动作（AI_FAILED / SEND_FAILED ...），供控制台归因
     */
    public void markFailed(UnreadTaskEntity task, String action, String reasonCode, String error) {
        int attempt = intOf(task.getAttemptCount()) + 1;
        int max = task.getMaxAttempts() == null ? DEFAULT_MAX_ATTEMPTS : task.getMaxAttempts();
        task.setAttemptCount(attempt);
        task.setLastAction(action);
        task.setLastReasonCode(reasonCode);
        task.setLastError(error);
        task.setUpdatedAt(ConfigStore.now());
        if (attempt >= max) {
            task.setStatus(Status.GAVE_UP.name());
            task.setNextRetryAt(null);
            log.warn("未读任务已放弃（失败 {} 次）| 会话={} | 动作={} | 原因={}",
                    attempt, task.getPlatformConversationId(), action, error);
        } else {
            task.setStatus(Status.FAILED.name());
            task.setNextRetryAt(plusMinutes(backoffMinutes(attempt)));
            log.info("未读任务失败，{} 分钟后重试（第 {} 次失败）| 会话={} | 原因={}",
                    backoffMinutes(attempt), attempt, task.getPlatformConversationId(), error);
        }
        mapper.update(task);
    }

    /**
     * 立即放弃，不消耗重试预算。用于「定位不安全」这类**重试也不会变好**的情况：
     * 同名多条、列表摘要已变（spec 3.4：宁可漏回，不可错发）。
     */
    public void giveUp(UnreadTaskEntity task, String reasonCode, String error) {
        task.setStatus(Status.GAVE_UP.name());
        task.setLastAction(Action.LOCATE_FAILED);
        task.setLastReasonCode(reasonCode);
        task.setLastError(error);
        task.setNextRetryAt(null);
        task.setUpdatedAt(ConfigStore.now());
        mapper.update(task);
    }

    /**
     * 人工重试：只接受 {@code GAVE_UP}，重置 3 次预算回到 PENDING（spec 3.8.3）。
     *
     * <p>只改状态，不绕过校验 —— 它走的是同一条重试路径，精确匹配与摘要校验照旧生效。
     * 人工点击不构成「跳过安全检查」的理由。
     *
     * @return 是否真的重置了（非 GAVE_UP 或任务不存在都返回 false，由调用方转 409 / 404）
     */
    public boolean manualRetry(Long id) {
        UnreadTaskEntity task = mapper.findById(id);
        if (task == null || !Status.GAVE_UP.name().equals(task.getStatus())) {
            return false;
        }
        task.setStatus(Status.PENDING.name());
        task.setAttemptCount(0);
        task.setNextRetryAt(null);
        task.setLastAction(Action.MANUAL_RETRY);
        task.setUpdatedAt(ConfigStore.now());
        mapper.update(task);
        log.info("未读任务人工重试 | id={} | 会话={}", id, task.getPlatformConversationId());
        return true;
    }

    // ------------------------------------------------------------ 纯函数（可单测）

    /** 第 {@code attempt} 次失败后等待几分钟。超出阶梯长度时用最后一档 */
    public static int backoffMinutes(int attempt) {
        if (attempt <= 0) {
            return BACKOFF_MINUTES[0];
        }
        int index = Math.min(attempt, BACKOFF_MINUTES.length) - 1;
        return BACKOFF_MINUTES[index];
    }

    private static String plusMinutes(int minutes) {
        return LocalDateTime.now().plusMinutes(minutes).format(TS);
    }

    /**
     * {@code left > right}（两个时间文本都可解析时）。任一侧为空或不可解析一律返回 false，
     * 即「不算还没到时间」—— 由调用方决定这是「可以动手」。
     */
    static boolean isAfter(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        try {
            return LocalDateTime.parse(left, TS).isAfter(LocalDateTime.parse(right, TS));
        } catch (Exception e) {
            return false;
        }
    }

    private static int intOf(Integer value) {
        return value == null ? 0 : value;
    }
}
