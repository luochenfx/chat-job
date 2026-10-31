-- 唯一约束从 name 单列改成 (name, model) 联合。
--
-- 控制台上这一页的主体是「模型」，service 只是它的归属：同一个服务商（DeepSeek）下
-- 常见并存 deepseek-chat 与 deepseek-reasoner。原来的 uk_provider_name 让 name 必须唯一，
-- 用户只能给同一服务商编不同的名字（Deepseek / Deepseekpro）来绕开，模型反而成了附属信息。
--
-- 真正不允许的是「同一服务商下的同一个模型」，所以换成联合唯一索引：
-- name 可以重复，同一 name 下的 model 不能重复。
--
-- 旧数据不需要清洗：name 原本唯一，任何两行的 name 都不同，联合索引必然成立。

DROP INDEX uk_provider_name;
CREATE UNIQUE INDEX uk_provider_name_model ON provider (name, model);
