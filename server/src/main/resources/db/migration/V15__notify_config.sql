-- 告警配置（ADR-0006）。
--
-- 为什么进库：application.yaml 在 app\chat-job.jar 内，分发包里没有它，绿色版用户
-- 实际上没有配置入口；而 data\ 目录是升级与备份时唯一保留的东西。
--
-- 单行表，形状对齐 ai_config：开关存 INTEGER 0/1，不用键值表（value 只能是 TEXT，
-- 开关写成 "1"/"true" 之后还得再约定怎么判）。
--
-- wecom_webhook_cipher 存密文，与 provider.api_key_cipher 同一口径：webhook 地址里带
-- key=，拿到它就能往群里发任意消息，属于凭据，不以明文落 SQLite。
--
-- 三列开关默认 1：升级后行为与今天完全一致，改动只在控制台点了开关之后才发生。
-- 这里不插默认行 —— 与 ai_config 一样，缺行由 ConfigStore.notifyConfig() 懒建。

CREATE TABLE IF NOT EXISTS notify_config (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    wecom_webhook_cipher  TEXT    NOT NULL DEFAULT '',
    alert_abnormal        INTEGER NOT NULL DEFAULT 1, -- 异常：run-failed / login-lost / circuit
    alert_standby         INTEGER NOT NULL DEFAULT 1, -- 进入待命
    alert_duty_end        INTEGER NOT NULL DEFAULT 1, -- 值守时段结束
    updated_at            TEXT    NOT NULL
);
