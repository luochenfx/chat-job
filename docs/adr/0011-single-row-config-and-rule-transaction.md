# 单行配置由唯一索引保证，规则与文案的写回放进事务

## Status

accepted（2026-11-04，架构审查第 7 步，第 6 步的后续）。
实现：`V18__single_row_config.sql`、`ConfigStore.readOrCreate`、`ReplyStore.saveRule/deleteRule`；
守卫：`V18SingleRowConfigTest`、`ConfigStoreReadOrCreateTest`。

## Context

三张「全局只有一份」的配置表里，只有 `platform_config` 有唯一约束（`uk_platform_config`，V1）。
`ai_config` 与 `notify_config` 没有，而三处的读法都是同一个形状：

```java
selectList(...) → 空 → insert → get(0)
```

两个线程同时首访就会插出两行，之后 `get(0)` 取到哪一行不确定。症状不是报错，而是
**配置页显示的值和实际生效的值不是同一行** —— 这类故障没有堆栈、没有日志，只能靠人怀疑。

另一处独立的问题：`POST /api/reply/rules` 与 `DELETE /api/reply/rules/{id}` 各要写两张表
（`reply_rule` + `reply_rule_text`），全仓当时没有一处 `@Transactional`。保存中途失败会留下
「规则名改了、文案只写进去一半」的库，而接口返回的是 500 —— 用户以为没保存，实际存了半份。

## Decision

1. **V18 用常量表达式上的唯一索引表达「最多一行」**：
   `CREATE UNIQUE INDEX uk_ai_config_single ON ai_config((1))`。不增列、意图直白。
   `platform_config` 不动，它早就有。

2. **建索引之前先去重，保留 `id` 最小的一行。** 分发出的副本可能在某个时点撞出过两行，
   有重复行时唯一索引会让迁移失败、应用直接起不来，而报错只是一句 SQLite 约束原文。
   选 `MIN(id)` 而不是「`updated_at` 最新」：`get(0)` 读的一直是 `MIN(id)` 那一行
   （SQLite 按 rowid 返回），也是历次 PUT 写回去的那一行；按 `updated_at` 选，可能保住
   用户从来没在界面上看见过的那份内容。

3. **三处「读或建」收成一个 `ConfigStore.readOrCreate(读, 建, 插)`。** 撞约束时重读一次
   并打一行 WARN；**重读仍为空就把原异常抛回去** —— 那说明撞的不是「别人先建好了」，
   而是 NOT NULL、外键这类真问题，不能吞。正常路径不写库，与 `peekPlatformConfig` 的
   「读接口不改库」一致。

4. **规则的两次写收进 `ReplyStore.saveRule` / `deleteRule`，`@Transactional` 打在 store 方法上。**
   控制器不再持有 `ReplyRuleMapper` / `ReplyRuleTextMapper`（它们只在这两个方法里用过）。
   删除的顺序是「先文案、后规则」：反过来若第二步失败，会留下一条**没有文案但仍在生效**的规则 ——
   引擎按关键词命中它，却没有话可说。

5. **不引入 `@SpringBootTest`。** 门①拆成两条能独立证的：迁移脚本级测试（临时 SQLite 跑
   V18 原文，断言去重与第二行被拒）+ `readOrCreate` 的 lambda 单测（三种走法，不起线程）。
   门②用手验：临时实例里把 `reply_rule_text` 改名 → `POST /rules` 得 500 → 查 `reply_rule`
   名字未变。仓库「没有上下文测试、全靠纯逻辑 + 手写假件」这条线值得保住，为两条断言破线的
   代价大于它证明的东西。

## Consequences

- **升级时会静默删掉多余的配置行**（仅当副本里已存在重复时才发生）。这是有意的：另一种选择是
  让应用起不来。删掉的是 `get(0)` 从来没读过的那几行。
- **「单行」这个假设从代码挪到了数据库**：以后有人写 `insert` 到这两张表，第二条会被库拒绝，
  而不是悄悄多一行。
- **`@Transactional` 只覆盖 `ReplyStore` 的两个方法**。绕过它们直接调 mapper 就没有事务 ——
  这条约束靠代码结构而不是守卫，改动时要知道。
- **`ConfigStore.readOrCreate` 是包级可见的**，只为让测试直接调它，与 `defaults(String)` 同一理由。
- **测试口径**：411 例 / 53 类 / 12 跳过。新增两条都不起 Spring 上下文，也不起线程。

## Open Questions

| # | 问题 | 现状 |
|---|---|---|
| 1 | `POST /api/config/providers` 的并发插入 | 未处理。控制器先查重再插，并发时第二个请求会拿到 SQLite 的约束原文（500）。它不像单行配置那样有「重读」这种正确答案 —— 重名本来就该失败，只是报错文案该是中文 |
| 2 | 其它多写的接口 | 已核：`blacklist`、`sensitive`、`provider` 都是单条写，不需要事务。将来出现「两张表一起改」的地方，事务边界仍应放在 store |
| 3 | `platformConfig()` 的读或建会写库 | 既有行为，本步没有改。控制台概览每 5 秒轮询走的是 `peekPlatformConfig`，所以只在真正要跑的时候才建行 |
