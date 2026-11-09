-- 自动接受工作地点的开关（ADR-0020）。
--
-- 加在 platform_config 上，与其它平台开关同一处：这个动作按平台跑，
-- 「一个平台开、另一个关」是正常用法。
--
-- DEFAULT 0：新库与升级上来的库都是关的。SQLite 的 ALTER TABLE ADD COLUMN 会把已有行一起
-- 填成这个默认值，这正是「默认关」想要的结果。
--
-- 不叫 accept_enabled：投递、发简历、接受工作地点是三件不同的事，只写「接受」看不出接受的是什么。
ALTER TABLE platform_config ADD COLUMN work_location_accept_enabled INTEGER NOT NULL DEFAULT 0;
