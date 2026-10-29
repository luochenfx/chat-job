-- skip 占比告警（spec 0002 §3.8.2）：回答一个具体问题 ——「开关开了，但它是不是一直在躲？」
--
-- 只在控制台提示，不改任何行为：不自动关开关、不调阈值、不发通知。
-- 这类指标早期噪声大，让它自动动开关比不动更危险。
--
-- 窗口固定 7 天不配置：与任务面板同口径。窗口一旦可配，就会出现
-- 「面板说 42 次、提示条说 12 次」这种自相矛盾，比不灵活更糟。

-- 0 = 关闭告警。注意关闭状态也要在面板上显示（灰底），
-- 不能什么都不显示 —— 否则等于把「我关了它」伪装成「一切正常」。
ALTER TABLE ai_config ADD COLUMN ai_reply_skip_alert_pct INTEGER NOT NULL DEFAULT 60;

-- 低于此数不判定，面板显示「样本不足（3/10），未判定」。
-- 硬约束：不能渲染成「正常」，把「没法判断」说成「一切正常」正是本 spec 要根治的病。
ALTER TABLE ai_config ADD COLUMN ai_reply_skip_alert_min_sample INTEGER NOT NULL DEFAULT 10;
