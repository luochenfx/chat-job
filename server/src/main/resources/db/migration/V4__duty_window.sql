-- 值守时段：限制「什么时候可以发消息」。
-- 存 HH:mm 文本而非整数分钟，是为了让人打开库就能读懂，也省掉一次进制换算。
-- 默认 09:00–22:00：招聘沟通的常规作息，凌晨发招呼语只会被当成骚扰。
ALTER TABLE platform_config ADD COLUMN duty_start_time TEXT NOT NULL DEFAULT '09:00';
ALTER TABLE platform_config ADD COLUMN duty_end_time TEXT NOT NULL DEFAULT '22:00';
