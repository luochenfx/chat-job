-- 值守规则的建表与默认种子。默认值对应 docs/ui/0001-reply-rules.html 的界面草案。
-- 幂等：重复执行不会产生重复规则（按 name 判重）。

CREATE TABLE IF NOT EXISTS reply_rule (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL,
    keywords   TEXT    NOT NULL,              -- 逗号分隔，子串包含匹配
    enabled    INTEGER NOT NULL DEFAULT 1,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_reply_rule_name ON reply_rule(name);

CREATE TABLE IF NOT EXISTS reply_rule_text (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    rule_id    INTEGER NOT NULL REFERENCES reply_rule(id) ON DELETE CASCADE,
    text       TEXT    NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_reply_rule_text_rule ON reply_rule_text(rule_id);

CREATE TABLE IF NOT EXISTS sensitive_word (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    word       TEXT    NOT NULL,
    created_at TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sensitive_word ON sensitive_word(word);

-- ---------------------------------------------------------------- 敏感词
INSERT INTO sensitive_word (word) VALUES
    ('薪资'), ('工资'), ('待遇'), ('月薪'), ('年薪'),
    ('多少钱'), ('期望薪资'), ('收费'), ('押金'), ('培训费')
ON CONFLICT(word) DO NOTHING;

-- ---------------------------------------------------------------- 回复规则
INSERT INTO reply_rule (name, keywords, enabled, sort_order) VALUES
    ('约面试',        '面试,面谈,约面',                        1, 10),
    ('简历索取',      '简历,发一份,看一下',                    1, 20),
    ('到岗时间',      '到岗,入职时间,什么时候能来,离职状态',    1, 30),
    ('到岗方式/出差', '远程,出差',                             1, 40),
    ('岗位仍在招',    '还招人吗,还在招',                       1, 50),
    ('婉拒/不合适',   '不合适,暂不需要,已招满',                1, 60),
    ('礼貌收尾',      '谢谢,收到',                             0, 70)
ON CONFLICT(name) DO UPDATE SET
    keywords = excluded.keywords, enabled = excluded.enabled, sort_order = excluded.sort_order;

-- ---------------------------------------------------------------- 文案（每条规则多选一随机）
INSERT INTO reply_rule_text (rule_id, text, sort_order)
SELECT r.id, t.text, t.ord FROM reply_rule r
JOIN (
    SELECT '约面试' AS name, '好的，我这边时间比较灵活，您方便约个具体时间吗？' AS text, 1 AS ord
    UNION ALL SELECT '约面试', '收到，我随时可以安排，您定时间我来配合。', 2
    UNION ALL SELECT '约面试', '方便的，线上或线下都可以。', 3

    UNION ALL SELECT '简历索取', '简历已在沟通里发过，如果您需要更详细的版本我可以再整理一份。', 1
    UNION ALL SELECT '简历索取', '可以的，我整理好发您，也可以先聊聊岗位的具体方向。', 2

    UNION ALL SELECT '到岗时间', '目前可以随时到岗，具体时间可以按您的安排协调。', 1
    UNION ALL SELECT '到岗时间', '随时可以入职，提前一周沟通就能安排好。', 2

    UNION ALL SELECT '到岗方式/出差', '我接受远程协作，也可以按需到岗配合。', 1
    UNION ALL SELECT '到岗方式/出差', '短期出差没问题，长期的话想了解一下频率。', 2

    UNION ALL SELECT '岗位仍在招', '在的，我对这个岗位很感兴趣，方便的话可以进一步沟通。', 1
    UNION ALL SELECT '岗位仍在招', '还在关注，想了解一下目前团队的侧重点。', 2

    UNION ALL SELECT '婉拒/不合适', '明白，感谢告知，祝招聘顺利。', 1

    UNION ALL SELECT '礼貌收尾', '感谢回复，期待进一步沟通。', 1
) t ON t.name = r.name
WHERE NOT EXISTS (
    SELECT 1 FROM reply_rule_text x WHERE x.rule_id = r.id AND x.text = t.text
);
