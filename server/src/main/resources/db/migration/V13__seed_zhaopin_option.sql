-- 智联的城市字典（platform_option.type = 'city'）。
--
-- 说明：智联的搜索 URL 用数字城市码（`/jobs/?jl=<code>&kw=`），而配置层存的是中文名，
-- 所以必须补一份 zhaopin 维度的字典，否则 openSearch 会拿到 null 城市码。
--
-- 本文件只放「实测确认过」的条目：福州 = 681（2026-10-31 实测，导航到
-- https://www.zhaopin.com/jobs/?jl=681&kw=java 返回的就是福州站）。
-- 没有实测依据的城市**不猜**：猜错的城市码会安静地返回别的城市的岗位，
-- 而「投到错城市」比「本轮扫不到」严重得多。
--
-- 完整城市码表仍待补（对应 docs/tech/0003 的 U4）。补法有两种，都不要靠推测：
--   1) 在智联页面切城市后读回 `jl` 参数；
--   2) 抓城市选择器那份数据（接口或内嵌 JSON）。
-- 补进来只需追加 INSERT，ZhaopinUrlBuilder 会自动按名字命中。

INSERT INTO platform_option (platform, type, name, code, sort_order) VALUES
  ('zhaopin', 'city', '福州', '681', 0);
