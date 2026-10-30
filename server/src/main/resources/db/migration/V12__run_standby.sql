-- 值守待命（STANDBY）：扫描可以收工，值守不能跟着收工。
--
-- 三件事：
--   1. 巡检间隔可配 —— 待命期按这个节奏刷新消息列表，它同时是「AI 回复的最长延迟」
--      和「页面被刷新的频率」，所以写成配置而不是常量。120 秒的由来见配置页说明。
--   2. 「现在在干什么」与「上次动是什么时候」—— 等待期间日志是静默的，
--      没有这两列，界面上无法区分「在等」和「卡死」。
--   3. 当前扫描所属自然日 —— 跨日恢复扫描时要靠它判断要不要复位组合队列，
--      否则第二天只会空转值守、一个都不投。

ALTER TABLE platform_config ADD COLUMN watch_interval_seconds INTEGER NOT NULL DEFAULT 120;

ALTER TABLE run ADD COLUMN current_action   TEXT;
ALTER TABLE run ADD COLUMN last_activity_at TEXT;
ALTER TABLE run ADD COLUMN scan_day         TEXT;

-- STANDBY 也必须进这条部分唯一索引：它是「持有浏览器的那一个」的状态之一。
-- 漏掉的话，同一平台能同时出现两个持有者抢一个浏览器档案（profile 有独占锁，第二个起不来）。
DROP INDEX IF EXISTS uk_run_running;

CREATE UNIQUE INDEX IF NOT EXISTS uk_run_running
    ON run(platform)
    WHERE status IN ('RUNNING', 'PAUSED', 'WAITING_DUTY', 'STANDBY');
