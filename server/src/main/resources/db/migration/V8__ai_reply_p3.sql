-- P3：AI 回复真正接进值守（开关仍默认关），风格可配，AI 的「不回」可归因统计。
--
-- 一次阶段只加这一阶段用得到的列：告警两项（skip_alert_pct / skip_alert_min_sample）
-- 属于 P4 的统计面板，现在加进来就是「列在库里、代码里没人用」的中间态。

-- 表达风格。空串 = 用 AiReplier.DEFAULT_STYLE，不是「无约束」：
-- NOT NULL DEFAULT '' 让「没配」有明确信号，而不是一个会往上层冒的类型噪声。
ALTER TABLE ai_config ADD COLUMN ai_reply_style TEXT NOT NULL DEFAULT '';

-- AI 回复超时。最终取值 min(本值, provider.timeout_seconds)：
-- 提供商那边可能配了 60s，但值守线程等不起 —— 单轮最多 5 条，一条 60s 就把整轮扫描拖死。
ALTER TABLE ai_config ADD COLUMN ai_reply_timeout_seconds INTEGER NOT NULL DEFAULT 8;

-- 单轮最多调几次 AI。列表里有 40 个会话，全调一遍既慢又是一笔真金白银；
-- 超出的任务退回 PENDING 等下一轮，不消耗重试预算（见 UnreadTaskStore.release）。
ALTER TABLE ai_config ADD COLUMN ai_reply_max_per_sweep INTEGER NOT NULL DEFAULT 5;

-- 「AI 不回」必须可归因：code 走枚举便于 GROUP BY，note 只放补充说明（禁止复述对方原话）。
ALTER TABLE reply_log ADD COLUMN reason_code TEXT;
ALTER TABLE reply_log ADD COLUMN reason_note TEXT;

-- 区分模板回复与 AI 回复。skip 占比告警的分母只算 AI：
-- 分母一旦算宽，会被模板回复稀释成一个永远正常的数字。
-- 历史行为 NULL，读时按 TEMPLATE 处理（改造前确实全是模板）。
ALTER TABLE reply_log ADD COLUMN reply_source TEXT;

CREATE INDEX IF NOT EXISTS idx_reply_log_reason ON reply_log(reason_code, created_at);
CREATE INDEX IF NOT EXISTS idx_reply_log_source ON reply_log(reply_source, created_at);
