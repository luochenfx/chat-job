-- chat-job 基础表结构。
-- 约定：所有时间列存 ISO-8601 文本（UTC 偏移固定为本地时区），便于 SQLite 直接比较与排序。
-- 字典表 platform_option 见 V2；值守规则见 V3。

-- ---------------------------------------------------------------- 模型提供商
CREATE TABLE IF NOT EXISTS provider (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT    NOT NULL,
    base_url        TEXT    NOT NULL,
    model           TEXT    NOT NULL,
    api_key_cipher  TEXT,                        -- DPAPI 密文，接口永不回发明文
    timeout_seconds INTEGER NOT NULL DEFAULT 60,
    extra_headers   TEXT    NOT NULL DEFAULT '{}', -- JSON object，key-value
    enabled         INTEGER NOT NULL DEFAULT 1,
    created_at      TEXT    NOT NULL,
    updated_at      TEXT    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_provider_name ON provider(name);

-- ---------------------------------------------------------------- AI 配置（单行）
CREATE TABLE IF NOT EXISTS ai_config (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    provider_id            INTEGER REFERENCES provider(id) ON DELETE SET NULL,
    introduce              TEXT NOT NULL DEFAULT '',   -- 个人介绍：技能 / 经验 / 专业背景
    prompt_template        TEXT NOT NULL DEFAULT '',   -- 判定提示词，{{占位符}} 替换
    greeting_prompt        TEXT NOT NULL DEFAULT '',   -- 招呼语提示词，可与判定共用一次调用
    quality_gate_enabled   INTEGER NOT NULL DEFAULT 1, -- 质量开关：开=AI 判定，关=模板招呼语直接投
    template_greeting      TEXT NOT NULL DEFAULT '',   -- 质量开关关闭时使用的固定文案
    jd_max_chars           INTEGER NOT NULL DEFAULT 4000,
    ai_timeout_seconds     INTEGER NOT NULL DEFAULT 20,
    collect_effect_metrics INTEGER NOT NULL DEFAULT 0, -- 采集 HR 回复率 / 首响时长（仅统计）
    updated_at             TEXT    NOT NULL
);

-- ---------------------------------------------------------------- 平台配置（每平台单行）
CREATE TABLE IF NOT EXISTS platform_config (
    id                          INTEGER PRIMARY KEY AUTOINCREMENT,
    platform                    TEXT    NOT NULL,
    enabled                     INTEGER NOT NULL DEFAULT 1,
    -- 以下均存"人可读的中文名"，执行时才查 platform_option 换成 code
    cities                      TEXT    NOT NULL DEFAULT '[]',  -- JSON 数组
    keywords                    TEXT    NOT NULL DEFAULT '[]',
    job_type                    TEXT    NOT NULL DEFAULT '',
    salary                      TEXT    NOT NULL DEFAULT '[]',
    experience                  TEXT    NOT NULL DEFAULT '[]',
    degree                      TEXT    NOT NULL DEFAULT '[]',
    scale                       TEXT    NOT NULL DEFAULT '[]',
    industry                    TEXT    NOT NULL DEFAULT '[]',
    stage                       TEXT    NOT NULL DEFAULT '[]',
    active_level_floor          TEXT    NOT NULL DEFAULT '本月', -- 在线|今日|3日内|本周|本月|不限
    wait_time_seconds           INTEGER NOT NULL DEFAULT 10,     -- 实际等待 [w/2, w]
    daily_delivery_quota        INTEGER NOT NULL DEFAULT 30,
    daily_eval_limit            INTEGER NOT NULL DEFAULT 150,
    scroll_round_limit          INTEGER NOT NULL DEFAULT 300,
    combination_time_limit_sec  INTEGER NOT NULL DEFAULT 1200,
    repeat_chat_enabled         INTEGER NOT NULL DEFAULT 0,
    repeat_chat_messages        TEXT    NOT NULL DEFAULT '[]',
    resume_image_enabled        INTEGER NOT NULL DEFAULT 0,      -- 本期不实现，留 seam
    updated_at                  TEXT    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_platform_config ON platform_config(platform);

-- ---------------------------------------------------------------- 黑名单（硬过滤，确定性生效）
CREATE TABLE IF NOT EXISTS blacklist (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    kind       TEXT NOT NULL,   -- company | job
    keyword    TEXT NOT NULL,   -- 原始输入，仅用于展示
    normalized TEXT NOT NULL,   -- trim + 全角转半角 + 小写
    created_at TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_blacklist ON blacklist(kind, normalized);

-- ---------------------------------------------------------------- 岗位
CREATE TABLE IF NOT EXISTS job_posting (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    platform        TEXT    NOT NULL,
    platform_job_id TEXT    NOT NULL,        -- boss: encryptId
    job_name        TEXT    NOT NULL DEFAULT '',
    company_name    TEXT    NOT NULL DEFAULT '',
    salary          TEXT    NOT NULL DEFAULT '',
    area            TEXT    NOT NULL DEFAULT '',
    experience      TEXT    NOT NULL DEFAULT '',
    degree          TEXT    NOT NULL DEFAULT '',
    description     TEXT    NOT NULL DEFAULT '',
    hr_name         TEXT    NOT NULL DEFAULT '',
    hr_title        TEXT    NOT NULL DEFAULT '',
    active_level    TEXT    NOT NULL DEFAULT '未知', -- 在线|刚刚|今日|3日内|本周|本月|2月内|半年前|未知
    active_raw      TEXT    NOT NULL DEFAULT '',
    raw_json        TEXT,                            -- 接口原文，便于改版后复盘
    first_seen_at   TEXT    NOT NULL,
    last_seen_at    TEXT    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_job_posting ON job_posting(platform, platform_job_id);

-- ---------------------------------------------------------------- 投递（每次尝试一行）
CREATE TABLE IF NOT EXISTS delivery (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    job_posting_id INTEGER NOT NULL REFERENCES job_posting(id) ON DELETE CASCADE,
    run_id         INTEGER,
    attempt_no     INTEGER NOT NULL DEFAULT 1,
    status         TEXT    NOT NULL,     -- DELIVERING | DELIVERED | FAILED | MANUAL
    decided_by     TEXT    NOT NULL DEFAULT 'AI', -- AI | TEMPLATE | SKIP
    greeting       TEXT    NOT NULL DEFAULT '',
    score          INTEGER,
    ai_reason      TEXT,
    ai_raw         TEXT,
    failure_reason TEXT,
    started_at     TEXT    NOT NULL,
    finished_at    TEXT
);
CREATE INDEX IF NOT EXISTS idx_delivery_job ON delivery(job_posting_id, status);
CREATE INDEX IF NOT EXISTS idx_delivery_run ON delivery(run_id);

-- ---------------------------------------------------------------- 会话
CREATE TABLE IF NOT EXISTS conversation (
    id                       INTEGER PRIMARY KEY AUTOINCREMENT,
    platform                 TEXT    NOT NULL,
    platform_conversation_id TEXT    NOT NULL,
    job_posting_id           INTEGER REFERENCES job_posting(id) ON DELETE SET NULL,
    last_checked_at          TEXT,
    last_own_reply_at        TEXT,
    last_hr_message_at       TEXT,
    first_hr_reply_at        TEXT,
    hr_reply_count           INTEGER NOT NULL DEFAULT 0,
    created_at               TEXT    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation ON conversation(platform, platform_conversation_id);

-- ---------------------------------------------------------------- 值守动作日志
CREATE TABLE IF NOT EXISTS reply_log (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id    INTEGER REFERENCES conversation(id) ON DELETE CASCADE,
    platform_message_id TEXT,
    incoming_text      TEXT NOT NULL DEFAULT '',
    action             TEXT NOT NULL,    -- REPLIED | SKIPPED_SENSITIVE | SKIPPED_SELF
                                         -- | SKIPPED_DUPLICATE | SKIPPED_THROTTLED | UNMATCHED
    matched_rule_id    INTEGER,
    matched_sensitive  TEXT,
    reply_text         TEXT,
    created_at         TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reply_log_action ON reply_log(action, created_at);

-- ---------------------------------------------------------------- Run
CREATE TABLE IF NOT EXISTS run (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    platform         TEXT    NOT NULL,
    status           TEXT    NOT NULL,   -- CREATED|RUNNING|PAUSED|COMPLETED|QUOTA_REACHED
                                         -- |CIRCUIT_OPEN|LOGIN_LOST|CANCELLED|FAILED
    mode             TEXT    NOT NULL DEFAULT 'NORMAL',  -- NORMAL | DRY_RUN
    terminal_reason  TEXT,
    message          TEXT,
    combination_index INTEGER NOT NULL DEFAULT 0,
    scroll_round     INTEGER NOT NULL DEFAULT 0,
    last_card_index  INTEGER NOT NULL DEFAULT 0,
    delivered_count  INTEGER NOT NULL DEFAULT 0,
    evaluated_count  INTEGER NOT NULL DEFAULT 0,
    started_at       TEXT,
    finished_at      TEXT,
    created_at       TEXT    NOT NULL
);
-- 同一时刻最多一个 RUNNING；PAUSED 也占着 profile 独占锁，不允许并发起第二个
CREATE UNIQUE INDEX IF NOT EXISTS uk_run_running ON run(platform) WHERE status IN ('RUNNING', 'PAUSED');

CREATE TABLE IF NOT EXISTS combination_run (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id      INTEGER NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    seq         INTEGER NOT NULL,
    city        TEXT    NOT NULL DEFAULT '',
    keyword     TEXT    NOT NULL DEFAULT '',
    status      TEXT    NOT NULL,        -- PENDING|RUNNING|DONE|SKIPPED|FAILED
    cards_seen  INTEGER NOT NULL DEFAULT 0,
    evaluated   INTEGER NOT NULL DEFAULT 0,
    delivered   INTEGER NOT NULL DEFAULT 0,
    started_at  TEXT,
    finished_at TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_combination_run ON combination_run(run_id, seq);

CREATE TABLE IF NOT EXISTS run_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id     INTEGER,
    level      TEXT NOT NULL DEFAULT 'INFO',
    message    TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_run_log_run ON run_log(run_id, id);

-- ---------------------------------------------------------------- 每日计数
CREATE TABLE IF NOT EXISTS daily_counter (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    platform  TEXT    NOT NULL,
    day       TEXT    NOT NULL,   -- yyyy-MM-dd
    delivered INTEGER NOT NULL DEFAULT 0,
    evaluated INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_daily_counter ON daily_counter(platform, day);
