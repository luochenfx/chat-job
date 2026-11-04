# 配置缺省值只有一处定义，跨语言的初始值由测试守

## Status

accepted（2026-11-04，架构审查候选 04）。实现：`ConfigStore` 的四个常量与两个归一方法；
守卫：`ConfigDefaultsAgreeTest`。共用工具：`com.chatjob.testing.RepoRoot`。

## Context

七个数字，每一个最多有四个住所：

| 住所 | 例子 |
|---|---|
| SQL 列 DEFAULT | `V1__schema.sql:52` `daily_delivery_quota INTEGER NOT NULL DEFAULT 30` |
| 建行时显式赋值 | `ConfigStore.readOrCreate` `created.setDailyDeliveryQuota(30)` |
| 读时 null 回退 | `ScanBoundary.quotaOf` `== null ? 30 : …` |
| 前端初始值 | `config.vue` `defaultPlatformConfig()` `dailyDeliveryQuota: 30` |

同一段建行代码里，改造前是**两种风格并存**：

```java
created.setWaitTimeSeconds(10);                          // 字面量
created.setDailyDeliveryQuota(30);                       // 字面量
created.setDailyEvalLimit(150);                          // 字面量
created.setScrollRoundLimit(300);                        // 字面量
created.setCombinationTimeLimitSec(DEFAULT_COMBINATION_TIME_LIMIT_SEC);  // 常量
created.setUnreadScanLimit(DEFAULT_UNREAD_SCAN_LIMIT);                   // 常量
created.setWatchIntervalSeconds(DEFAULT_WATCH_INTERVAL_SECONDS);         // 常量
```

前四项是上一轮「缺省值常量化」没走完的部分。真正会造成故障的不是数字重复本身，而是
**只改一处不会报错**：产品把默认配额从 30 改成 50，改了后端、忘了前端，配置页在
「还没加载完」与「请求失败」两种情况下会显示 30；只改前端，新建的行还是 30。两种偏差都只在
有人盯着数据时才发现。

`waitTimeSeconds` 还多一层：它有**两个**读时回退点 —— 扫描段的卡间停顿、`RunService` 的
「活动中断」阈值。两处不同源，界面会在还在正常等待时报「卡死」。

## Decision

1. **四个常量进 `ConfigStore`**：`DEFAULT_WAIT_TIME_SECONDS`、`DEFAULT_DAILY_DELIVERY_QUOTA`、
   `DEFAULT_DAILY_EVAL_LIMIT`、`DEFAULT_SCROLL_ROUND_LIMIT`，与既有 `DEFAULT_*` 同风格，
   注释写明「与哪个迁移的列默认值保持一致」。

2. **新增两个归一方法** `clampWaitTimeSeconds` / `clampScrollRoundLimit`，与
   `clampUnreadScanLimit` / `clampWatchInterval` / `normalizeCombinationTimeLimit` 并列。
   建行赋值与读时回退都只引用常量，Java 侧不再出现这七个数字中的任何一个字面量。

3. **判据仍留在 `scan` 包**。`ScanBoundary.quotaOf` / `evalLimitOf` 只是把
   `30` / `150` 换成 `ConfigStore.DEFAULT_*` 的引用，方法本身没搬家。ADR-0009 划的边界
   （判据 + 缺省口径同处）不变 —— 变的只是数字定义在哪。

4. **不比对 SQL 的列 DEFAULT**。表列是 `NOT NULL`，而建行时每个字段都由 `ConfigStore` 显式赋值，
   所以迁移里的 `DEFAULT` 只在「手工 `INSERT` 少写一列」时才生效，不参与运行。
   把它拉进守卫只会多依赖一种文件格式，换不来实际保护。

5. **跨语言那一侧用守卫**：`ConfigDefaultsAgreeTest` 读 `config.vue` 的
   `defaultPlatformConfig()`，逐项与 `ConfigStore` 的常量比对。后端一侧的值全部取自常量引用，
   测试自己不抄数字 —— 否则守卫自己就成了第五处住所。

## Consequences

- 改缺省值要动两处：`ConfigStore` 常量（若涉及新装的行，还要新迁移）与前端初始值。
  漏掉前端会红，界面不会在「加载失败」时显示一套别的值。
- `waitTimeSeconds` 的两个读者从此同源，「等 10 秒」与「10 秒没动作算卡死」不再可能分叉。
- 守卫只做单向：后端表里的每个键，前端值必须相等。前端多出来的数字不管 ——
  它对后端没有约束，误报只会逼人维护一张排除表。
- 反向验证做过一次：把前端 `dailyDeliveryQuota` 改成 31 → 断言红，输出
  `{"dailyDeliveryQuota"=31}`。已还原。

## Open Questions

1. **`SystemController:188` 的 `? 30`** 是「观察多少天的数据」，与投递配额同名不同义，未纳入。
2. **字符串缺省值没有守卫**。`dutyStartTime` / `dutyEndTime` 在前端同样各有一份
   （`'09:00'` / `'22:00'`），`DEFAULT_ACTIVE_LEVEL_FLOOR`（`'本月'`）也是。补齐需要另一种解析，
   本次只覆盖数字。
3. **`ConfigStore.readOrCreate` 的建行赋值是否可以省掉**，改为依赖 SQL 的列 DEFAULT：
   那样能少一处副本，但会把「实体刚构造、还没落库」这一刻的读值变成 `null`，
   与 `NOT NULL` 列的设计冲突。目前两侧都留，靠第 2 条保证同源。
