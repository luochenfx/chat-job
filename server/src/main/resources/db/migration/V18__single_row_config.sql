-- 单行表：一张表最多一行。
--
-- ai_config 与 notify_config 是「全局只有一份」的配置表，但没有任何东西保证这一点：
-- ConfigStore 的读法是「查 → 空则插」，两个线程同时首访就会插出两行，之后
-- selectList().get(0) 取到哪一行不确定 —— 症状是「配置页显示的值」与「实际生效的值」
-- 不是同一行，而两边都不会报错。platform_config 早在 V1 就有 uk_platform_config，
-- 这两张表是漏的。
--
-- 用常量表达式上的唯一索引表达「最多一行」：不增列，意图直白（索引名即文档）。
-- SQLite 3.9+ 支持表达式索引；驱动打包的版本远高于此。
--
-- 去重必须放在建索引之前：分发出的副本可能在某个时点撞出过两行，有重复行时唯一索引
-- 会让迁移失败、应用直接起不来，而报错只是一句 SQLite 的约束原文。
-- 保留 id 最小的一行 —— ConfigStore 的 get(0) 读的一直是它（SQLite 按 rowid 顺序返回），
-- 也是历次 PUT 写回去的那一行。表为空时 MIN(id) 为 NULL，比较式不成立，不会删任何行。
DELETE FROM ai_config WHERE id > (SELECT MIN(id) FROM ai_config);
DELETE FROM notify_config WHERE id > (SELECT MIN(id) FROM notify_config);

CREATE UNIQUE INDEX uk_ai_config_single ON ai_config((1));
CREATE UNIQUE INDEX uk_notify_config_single ON notify_config((1));
