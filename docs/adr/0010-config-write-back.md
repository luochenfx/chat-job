# 配置写回：没传的字段一律保持原值

## Status

accepted（2026-11-04，架构审查第 6 步；第 5 步 page kit 跳过，与本步不同域）。
实现：`com.chatjob.store.PlatformConfigChange`、`com.chatjob.web.PlatformConfigMerge`；
守卫：`PlatformConfigMergeTest`。

## Context

改造前，「请求体里某个字段没传」在同一个 `PUT /api/config/platform/{platform}` 上有四种解释：

| 字段类 | 没传时的行为 | 当时的写法 |
|---|---|---|
| 三枚开关 | 变成**关** | `Boolean.TRUE.equals(body.enabled()) ? 1 : 0` |
| 各筛选列表 | 变成**空** | `writeList(null)` → `"[]"` |
| 五处配额与节奏 | **回落默认值** | `body.dailyDeliveryQuota() == null ? 30 : …` |
| `unreadScanLimit`、`watchIntervalSeconds` | **保持原值** | `if (body.x() != null) …` |

最后一行是后来逐个打的补丁，两处的注释都写着「未传则保持原值……否则保存任何一个无关配置都会
重置掉」。也就是说：哪些字段安全，只能靠记住，不能靠读出来。实际后果是拿 curl 改一个配额，
另外三个配额连同筛选列表一起被重置，接口返回 200。

同一段代码里还有两个与写回相关的缺陷：

1. **解绑模型永远失败。** `AiConfigEntity.providerId` 没有 `@TableField`，MyBatis-Plus 默认
   字段策略是 `NOT_NULL`，「设成 null 再 `updateById`」会把这一列整个跳过。请求成功、库里没变，
   控制台回读后下拉框跳回原值 —— 看起来像「保存没生效」，而不是「这个操作做不到」。
2. **`providerId` 为空并不等于「不用 AI」。** `ConfigStore.activeProvider()` 会回退到第一个
   启用的模型，而界面上的选项写的是「未选择」。

改造前评估过「先按老语义发一版 WARN 观察一轮」，核完调用方之后否掉了：控制台与 jar 一起发布
（`mvn package` 拷 `console/.output/public`），不存在版本错配；`savePlatform` 直接把整个
reactive 对象 PUT 出去；`tools/verify-p2.mjs` 也是先 GET 再整份 PUT。**没有任何调用方依赖
老语义**，过渡版本只会让每次合法的部分写回刷一条 WARN。

## Decision

1. **没传 = 不动，规则只有一处。** `PlatformConfigMerge.merge(store, current, change)` 逐字段
   判 `!= null` 才赋值。控制器不再持有任何「没传算默认还是算清空」的判断。

2. **请求体的 record 在 `store` 包，合并函数在 `web` 包。** `PlatformConfigChange` 描述的是
   「配置怎么变」，与 `ConfigStore.defaults()` 是一件事的两头；但合并要调
   `DutyWindow.normalize`（`orchestrator` 包），而 `orchestrator` 已经大量依赖 `store` ——
   放进 `store` 就是包级双向依赖。合并规则是纯逻辑，放在「不需要环的那一侧」，与 ADR-0009
   把扫描判据放进 `scan` 包同一个取舍。

3. **清空必须显式。** 列表传 `[]`，字符串传 `""`。`activeLevelFloor` 的空串回落到默认，
   与 `DutyWindow.normalize` 对时段的处理同口径 —— 否则会存进一个不在
   `ActiveLevel.selectable()` 里的值，界面上是一条空白下拉。

4. **解绑走 `DELETE /api/config/ai/provider`，用一条显式 UPDATE。**
   JSON 绑定下「字段缺失」与「显式 null」都是 `null`，两者无法区分；用 `providerId: null`
   编码清除，会与「这次不改模型」撞成同一份字节。`/notify/webhook` 早先因为同一个理由也单独
   开了 DELETE。写库用 `AiConfigMapper.clearProviderId`（`provider_id = NULL`），而不是给实体加
   `updateStrategy = ALWAYS`：后者会让今后每一次 `updateById` 都带上这一列。

5. **动词保持 `PUT`，不改成 `PATCH`。** 合并语义在 HTTP 里对应 `PATCH`，但本 API 只有一个消费者、
   没有中间层按 HTTP 语义做缓存或幂等重放；换动词要动 `tools/verify-p2.mjs` 与
   `docs/spec/0005`（那份是历史决策记录，只在「与代码事实冲突」时才改），收益只是名实相符。
   这是一处**有意的偏离**，记在这里免得下一个人把它「修」回去。

6. **代码侧的默认值只剩 `ConfigStore` 一处。** 控制器里那五个三元字面量删除，`"本月"` 收成
   `DEFAULT_ACTIVE_LEVEL_FLOOR`。`V1__schema.sql` 的列默认值保留但不再是语义来源（SQLite 改不了
   列默认值，`combination_time_limit_sec` 的 `DEFAULT 1200` 会一直在）。控制台
   `defaultPlatformConfig()` 那份只是「切平台时防止上一个平台的残留」，不是值的来源 ——
   GET 总是回发全部字段，叠加后它的字面量不生效。

## Consequences

- **单字段 PUT 变安全了**，`tools/verify-p2.mjs` 一行都不用改（它本来就是 GET → 合并 → 整份 PUT）。
- **丢改动比丢数据更难**：以前漏传一个字段是把值重置成默认，现在是不动。也就是说「我以为改了、
  其实没传」不会再有明显迹象 —— 界面回读一次仍然能看出来，脚本里则看不出来。
- **两个标签页同时保存会互相覆盖**：写路径仍是「读出整行 → 改字段 → `updateById` 整行」。
  唯一写入口是控制器，同一平台同一行并发保存的概率低，本步不引入乐观锁。
- **`repeatChatMessages` 的「清空」等于「恢复默认」**：运行时 `ConfigStore.repeatMessages()`
  在列表为空时返回代码里的 6 条默认文案。这是改造前就有的行为，本步没有动它，但它让「清空」
  在这个字段上与其它字段不等价。
- **读侧归一保留双层**：合并层保证「存进去的合法」，`GET` 再钳一次保证「读出来的合法」——
  库里的值可能是手工改的。两层不是重复。
- **解绑后 AI 仍然可用**：`activeProvider()` 回退到第一个启用的模型。界面上那个选项已从
  「未选择」改成「自动（第一个启用的模型）」，并在前端的「实际只等 N 秒」里跟着同一条回退规则，
  否则选了「自动」之后那句提示会消失。

## Open Questions

| # | 问题 | 现状 |
|---|---|---|
| 1 | `/ai` 与 `/notify` 要不要换成同一套语义 | 未做。`/ai` 上还剩 8 处 `if (body.x() != null)`，`/notify` 用「空串 = 不修改 + DELETE 清除」。它们没有 25 字段矩阵的问题，动它们的收益不抵回归面 |
| 2 | 并发保存的丢失更新 | 未做。要处理就加 `version` 列 + 乐观锁，但那会把归一与「未传」判断逼回 SQL 侧 |
| 3 | `GET /api/config/ai` 不回发「实际在用的模型」 | 未做。界面只能从 `providerId` 推断，选了「自动」时看不出真正落到哪一个 |
| 4 | 读侧归一无自动测试 | 要测就得引入 `@WebMvcTest` 或把 GET 的 40 行映射也抽成纯函数。本步用手验（把库里某配额改成负数，看 GET 是否回发合法值） |
