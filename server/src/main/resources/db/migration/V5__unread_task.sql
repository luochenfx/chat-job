-- 未读任务（spec 0002 §3.5）：把「这条未读还没处理完」落成一行。
--
-- 存在的理由是一条硬事实：点开会话即清未读，平台没有「标回未读」的能力。
-- 所以必须在点开之前先把这条未读落库，否则任何一环失败（AI 超时、发送失败、
-- 进程被杀）都会让这条消息永久消失 —— 它在列表里再也看不见了。
--
-- 本迁移只放 P1（任务与重试）需要的结构。P3/P4 才用到的 reply_log.reason_code、
-- ai_config 其余 7 个字段放在后续迁移里：一个阶段一次加列，避免出现
-- 「列已经在库里、代码里没人用」这种没人敢确认的中间态。

-- 值守 AI 回复总开关。默认 0 = 行为与改造前逐行一致（spec 2.1 第 1 条）。
ALTER TABLE ai_config ADD COLUMN ai_reply_enabled INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS unread_task (
    id                       INTEGER PRIMARY KEY AUTOINCREMENT,
    platform                 TEXT    NOT NULL,
    platform_conversation_id TEXT    NOT NULL,   -- 当前等于 HR 标题，平台不提供稳定会话 id（spec 5.3）
    conversation_id          INTEGER REFERENCES conversation(id) ON DELETE CASCADE,
    unread_count             INTEGER NOT NULL DEFAULT 0,
    last_message_digest      TEXT,               -- 列表摘要的 SHA-256 前 16 位，不落原文
    platform_message_id      TEXT,               -- 点开后回填的 data-mid
    status                   TEXT    NOT NULL,   -- PENDING|PROCESSING|DONE|FAILED|GAVE_UP
    attempt_count            INTEGER NOT NULL DEFAULT 0,
    max_attempts             INTEGER NOT NULL DEFAULT 3,
    last_action              TEXT,               -- REPLIED|SKIP_*|AI_FAILED|SEND_FAILED|LOCATE_FAILED|MANUAL_RETRY
    last_reason_code         TEXT,               -- 与 reply_log.reason_code 同枚举，便于面板直接展示
    last_error               TEXT,
    next_retry_at            TEXT,
    created_at               TEXT    NOT NULL,
    updated_at               TEXT    NOT NULL
);

-- 同一会话同时只允许一条在途任务：多轮扫描不会堆积，
-- 也不会出现两条任务抢同一个会话（重复发送的直接来源）。
-- 部分唯一索引只约束未终结状态，DONE / GAVE_UP 之后再建新任务是正常的。
CREATE UNIQUE INDEX IF NOT EXISTS uk_unread_task_open
    ON unread_task(platform, platform_conversation_id)
    WHERE status IN ('PENDING', 'PROCESSING', 'FAILED');

CREATE INDEX IF NOT EXISTS idx_unread_task_retry
    ON unread_task(status, next_retry_at);
