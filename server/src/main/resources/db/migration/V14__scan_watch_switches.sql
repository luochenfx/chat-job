-- 扫描开关与值守开关（spec 0005 / ADR-0005）。
--
-- 已有的 enabled 只回答「这个平台能不能被启动」；这两列回答「启动之后做什么」：
--   scan_enabled  = 0：不加载岗位列表、不做投递判定、不主动投递，也不占用投递配额与评估上限。
--   watch_enabled = 0：不巡检未读、不读消息、不发消息、不发简历；平台侧的未读标记保持原样。
--
-- 默认都是 1：升级后两个平台的既有行为不变，改动只在控制台上点了开关之后才发生。
--
-- 为什么是两个独立布尔、不是一个三态 work_mode：CONTEXT.md 里扫描与值守是并列关系，
-- 两个布尔直接对应这两个词；三态里的「都关」既不能并进 enabled=0（那会让停用由两个字段
-- 共同表达），留成第四态就与两个布尔没有区别（ADR-0005 的 Considered Options）。
--
-- 注意「都关」在接口层是被拒绝的（PlatformSwitches.requireSomethingOn），
-- 所以这一组合正常不会落库；列上不加 CHECK 约束，是因为只读库里的历史行也要能被读出来。

ALTER TABLE platform_config ADD COLUMN scan_enabled  INTEGER NOT NULL DEFAULT 1;
ALTER TABLE platform_config ADD COLUMN watch_enabled INTEGER NOT NULL DEFAULT 1;
