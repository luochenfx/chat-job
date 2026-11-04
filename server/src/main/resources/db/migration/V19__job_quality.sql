-- 岗位质量（ADR-0015 / ADR-0016）。
--
-- 三件事：
--   1. quality_assessment：每做一次质量判断落一行。同一段会话会被判断多次，界面只取最新一份，
--      旧的几份留着回答「HR 多说了几句之后结论变了没有」。
--   2. conversation 上接管状态与「已提醒过」：接管挂在会话上而不是岗位上，理由见 ADR-0016
--      （conversation.job_posting_id 至今恒为空，会话与岗位之间没有可用关联）。
--   3. ai_config 上判断点与四个参数；notify_config 上加第四类告警的开关。
--
-- 为什么评估结果不落在 job_posting 上：判断的对象是「一段会话」，而两平台都拿不到会话对应的岗位。
-- 结果留在会话这一侧，岗位信息作为快照一起存（job_name / company_name / salary），
-- 「重点关注」列表因此不依赖那条不存在的关联，断链时也能显示。
--
-- criteria_json 的形状：[{"criterion":"双休","verdict":"YES","evidence":"岗位描述写周末双休"}]
-- verdict 三选一：YES / NO / UNKNOWN。存 JSON 而不是拆一张子表：它只被整体读写，
-- 从不按单个判断点查询。

CREATE TABLE IF NOT EXISTS quality_assessment (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    platform        TEXT    NOT NULL,
    conversation_id INTEGER NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    -- 反查不到岗位时为空：只有对话也照样能判 HR 维度的两条
    job_posting_id  INTEGER REFERENCES job_posting(id) ON DELETE SET NULL,
    job_name        TEXT    NOT NULL DEFAULT '',
    company_name    TEXT    NOT NULL DEFAULT '',
    salary          TEXT    NOT NULL DEFAULT '',
    score           INTEGER NOT NULL DEFAULT 0,
    criteria_json   TEXT    NOT NULL DEFAULT '[]',
    summary         TEXT    NOT NULL DEFAULT '',
    -- 判断这一刻会话里有几条 HR 消息。重判的条件是「比这次多 3 条」，靠它比
    hr_messages     INTEGER NOT NULL DEFAULT 0,
    assessed_at     TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_quality_conversation ON quality_assessment(conversation_id, assessed_at);

-- 列表按分数排、每日上限按日期数，两个查询都要它
CREATE INDEX IF NOT EXISTS idx_quality_assessed_at ON quality_assessment(assessed_at);

-- 接管：0 是默认值，升级后行为不变。taken_over_at 只用于显示「什么时候接手的」。
-- 三列都允许为空串/0，不给 CHECK —— 只读历史行也要能读出来（与 V14 同一口径）。
ALTER TABLE conversation ADD COLUMN auto_reply_paused  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE conversation ADD COLUMN taken_over_at      TEXT;
-- 同一段会话只提醒一次「重点关注」。跌下去再升上来也不再发（ADR-0015 的取舍）
ALTER TABLE conversation ADD COLUMN quality_notified_at TEXT;
-- 接管期间不打开会话（打开会清掉平台上的未读红点），但会话列表页本来就在读
-- 最后一句与未读数 —— 这两列把那两个值留下来，界面才看得到「接管中，HR 又说了 2 条」
ALTER TABLE conversation ADD COLUMN last_thread_message TEXT;
ALTER TABLE conversation ADD COLUMN last_thread_unread  INTEGER NOT NULL DEFAULT 0;

-- 判断点：换行分隔，空串 = 用 ConfigStore 的默认六条。
-- 不做字面匹配，只是交给模型看的判断依据（CONTEXT.md「判断点」）。
ALTER TABLE ai_config ADD COLUMN quality_criteria       TEXT    NOT NULL DEFAULT '';
-- 过线分数。只决定「进不进重点关注、发不发提醒」，不触发任何自动动作
ALTER TABLE ai_config ADD COLUMN quality_score_line     INTEGER NOT NULL DEFAULT 80;
-- 首次判断的门槛：会话里至少要有这么多条 HR 消息
ALTER TABLE ai_config ADD COLUMN quality_min_hr_messages INTEGER NOT NULL DEFAULT 2;
-- 每个平台每天最多判断几次。不共用 daily_counter.evaluated（那是投递判定的名额）
ALTER TABLE ai_config ADD COLUMN quality_max_per_day    INTEGER NOT NULL DEFAULT 30;
-- 每轮值守最多判断几个，与 ai_reply_max_per_sweep 同一形状
ALTER TABLE ai_config ADD COLUMN quality_max_per_sweep  INTEGER NOT NULL DEFAULT 3;
-- 重判间隔：新增 3 条以上 HR 消息、且离上次超过这么多小时，才再判一次
ALTER TABLE ai_config ADD COLUMN quality_reeval_hours   INTEGER NOT NULL DEFAULT 6;

-- 第四类告警（CONTEXT.md「告警类别」）。默认 1：升级后行为与今天一致，
-- 变化只在用户点了开关之后发生（与 V15 三列同一口径）
ALTER TABLE notify_config ADD COLUMN alert_hot_job INTEGER NOT NULL DEFAULT 1;
