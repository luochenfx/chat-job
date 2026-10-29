-- P2：出站四检需要的两个字段（spec 0002 §3.7 / §3.9）。
--
-- 只加这两个，其余 6 个 ai_config 字段（风格、告警阈值、超时、单轮上限）随 P3 / P4
-- 各自的迁移加 —— 一个阶段一次加列，避免出现「列已经在库里、代码里没人用」的中间态。
--
-- 空串的语义是「用代码里的 DEFAULT_TONE_TERMS」，不是「关闭检查」（§3.8.1）：
-- 两件事各有一个字段，混用会让「清空输入框」静默把校验关掉。
ALTER TABLE ai_config ADD COLUMN ai_reply_tone_check_enabled INTEGER NOT NULL DEFAULT 1;
ALTER TABLE ai_config ADD COLUMN ai_reply_tone_terms TEXT NOT NULL DEFAULT '';
