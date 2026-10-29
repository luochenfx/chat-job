-- 规则动作（spec 0002 追加）：把「发简历」从一句话变成一次真实操作。
--
-- 背景：上一版「简历索取」规则的两条文案里有一条写着「简历已在沟通里发过」。
-- 那句话是种子数据编的，实际上一次都没发过 —— 平台根本不支持在招呼语里带附件。
-- 更糟的是它随机被选中，于是对着真实 HR 撒了谎。
--
-- 结论不是把文案改得客气一点，而是：这个动作本来就不该由文案承担。
-- 命中这类规则时应该去点平台的「发简历」按钮，一句话都不要说。
--
-- 为什么把它做成列而不是硬编码规则名：
--   规则名是给人看的展示字段，会被随手改（"简历索取" → "要简历"），
--   拿它当分支条件，等于把业务流程挂在一个随时能改的标题上。

ALTER TABLE reply_rule ADD COLUMN action TEXT NOT NULL DEFAULT 'REPLY';

-- 现有「简历索取」规则切到真实动作
UPDATE reply_rule SET action = 'SEND_RESUME', updated_at = datetime('now', 'localtime')
WHERE name = '简历索取';

-- 删掉那句谎言。留着的文案只在按钮灰掉（平台要求双方回复后可用）时兜底，
-- 所以它必须表达「我会发」而不是「我已经发了」。
DELETE FROM reply_rule_text
WHERE text LIKE '简历已在沟通里发过%';

-- 兜底文案：按钮不可用时说这句话。真实动作在按钮可用时发生，这条只负责不沉默。
INSERT INTO reply_rule_text (rule_id, text, sort_order)
SELECT r.id, '好的，我把整理好的版本发您。', 1
FROM reply_rule r
WHERE r.name = '简历索取'
  AND NOT EXISTS (SELECT 1 FROM reply_rule_text x WHERE x.rule_id = r.id AND x.text = '好的，我把整理好的版本发您。');

-- 另一条「可以的，我整理好发您，也可以先聊聊岗位的具体方向」原样保留：
-- 它不撒谎，作为兜底文案也没问题。
