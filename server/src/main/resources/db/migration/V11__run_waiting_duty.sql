-- 等待值守时段的 Run 也算「进行中」。
--
-- 不加进这条部分唯一索引的话，同一个平台可以同时挂起多条等待中的 Run：
-- 第二天窗口一开，几条会一起醒来抢同一个浏览器档案。索引是启动校验之外的第二道保险，
-- 因为校验与写入之间总有窗口期（连点、双标签页）。
DROP INDEX IF EXISTS uk_run_running;

CREATE UNIQUE INDEX IF NOT EXISTS uk_run_running
    ON run(platform)
    WHERE status IN ('RUNNING', 'PAUSED', 'WAITING_DUTY');
