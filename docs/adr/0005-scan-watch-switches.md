# 扫描与值守拆成两个平台级开关

## Status

accepted（2026-11-01，spec 0005）。实现进度：P0（`V14` 两列、`ConfigStore` 两个读法、`ConfigController` 读写与双关拒绝、`SystemController` 两个 DTO）已完成；P1（关闭扫描真正改变运行行为：`start` 跳过空组合校验、`runLoop` 的 `!scanEnabled` 分支、组合入口与卡片边界两处判定、`StandbyGate` 新增 `SCAN_DISABLED` 与 `scanEnabled` 入参、`enterStandby` / `stopLabel` / `finishDryRun` 文案）已完成；P2（关闭值守：`runLoop` 的 `SCAN_DONE` 与 `WATCH_DISABLED` 两条收尾、`StandbyExit.ENDED`、`standbyLoop` 顶部与 `sleepInterruptibly` 每秒检查、插空巡检跳过）已完成；P3（控制台）未实现。

## Context

每个平台现在只有一个开关 `platform_config.enabled`，它控制的是「这个平台能不能被启动」：停用后不开浏览器、不新建 Run、不唤醒挂起的 Run（`PlatformSwitches` 的四个调用点，读侧是 `ConfigStore.platformEnabled`）。它管启动链路，不管启动之后做什么。

两个平台的实际用法不一样。Boss 已经投递过一批岗位，主要价值是守住这些会话；智联是新接入的，主要价值是继续投递。当前开关只能整体开或整体关，表达不了「Boss 只值守不投递」和「智联只投递不回复」。

用现有配置模拟这两种用法都不成立：

1. **把 `daily_delivery_quota` 设成 0 来禁止投递。** 配额判定在卡片边界（`RunService.processCards:717`），配额为 0 时第一张卡就中止，看起来可行。但中止点在 `processCards` 内部，它之前的 `openSearch` 与 `scrollUntilEnd`（`RunService.executeCombinations:648-652`）已经执行完了。结果是每天仍然打开每个组合的搜索页、滚动到底，然后一张卡都不处理。日志还会每天写一条「今日主动投递配额已用完（0/0）」，与「这个平台本来就不投递」不是一回事，排查时会误导。

2. **把 `ai_reply_enabled` 设成 0 来禁止回复。** 这个字段只切换回复策略。关闭时走 `UnreadWatcher.sweepLegacy`，它仍然 `listUnreadThreads`，并对每条未读调用 `adapter.readMessages`；两个平台的 `readMessages` 都先 `openThread` 再读消息（`BossAdapter:757`、`ZhaopinAdapter:655`）。点开会话会清掉平台侧的未读标记，也就是「读过但不回」，比不读更糟。

3. **清空值守规则。** 规则引擎没有命中时不发消息，但巡检本身照旧执行，问题与第 2 条相同。

ADR-0002 拒绝过「配额用完是否继续值守」这个开关。本次加的是「这个平台是否参与扫描、是否参与值守」，与那一条不同：ADR-0002 决定的是「一次扫描结束之后要不要继续值守」，本次决定的是「这次扫描和这次值守要不要发生」。ADR-0002 的结论仍然成立——扫描因为配额停下时，只要值守开着，就进待命而不是结束。

## Decision

1. `platform_config` 增加两个独立布尔列 `scan_enabled`、`watch_enabled`，默认都是 1（新增 `V14`）。两个开关互不派生，各自只表达一个方向：关闭即不做那件事。
2. **关闭扫描**：不加载岗位列表、不做投递判定、不投递、不占用投递配额与评估上限。
3. **关闭值守**：不巡检未读、不读消息、不发消息、不发简历。平台侧的未读标记保持原样，不清除。
4. **两个都关时拒绝保存**，报错提示改用平台总开关。理由：平台启用、扫描、值守全关之后，它与「停用」的差别只剩浏览器没有被主动关掉，而停用恰好会关掉浏览器（`ConfigController:324-334`）。保留这个状态只会让「停用」有两种写法。
5. **两个开关即刻生效**，生效边界按位置决定：
   - 扫描开关在卡片边界生效，与配额、评估上限同一处判定。手上这张卡处理完才停——半条招呼语比晚几秒停止更糟，这条约定已有，不变。
   - 值守开关在待命循环的睡眠检查里生效。`sleepInterruptibly` 每秒检查一次，关掉值守最多一秒返回循环顶部。
   - 扫描进行中关掉值守时，插空巡检（每 5 张卡或 120 秒）直接跳过，扫描继续。
6. **只开扫描**（`watch_enabled = 0`）：组合队列扫完后直接结束 Run，状态 `COMPLETED`，`terminal_reason = SCAN_DONE`，不进入待命。跨日不再自动回扫，需要继续投递就重新启动一次。
7. **只开值守**（`scan_enabled = 0`）：Run 启动后不扫描，直接进入 `STANDBY`，并新增中止原因 `SCAN_DISABLED`。
8. **只值守的 Run 启动时不校验搜索条件为空。** 现有这条校验（`RunService.start:188-192`）是为扫描准备的；不扫描时城市与关键词是什么都不影响启动。
9. **`StandbyGate.decide` 增加 `scanEnabled` 参数**，为 false 时任何情况都不返回 `Action.SCAN`。这不是防御性代码：`scanDay` 为 null 表示「这一轮从没扫过」，现有实现对 null 按跨日处理并返回 `SCAN`（`StandbyGate:66-68`），而只值守的 Run 的 `scanDay` 恰好一直是 null。不拦这一条，它在第一次待命循环就会转去扫描。

## Considered Options

- **一个三态字段 `work_mode`（都开 / 仅值守 / 仅扫描）。** 状态更少，但「都关」无处安放：并进 `enabled = 0` 会让停用由两个字段共同表达，需要额外规则防止不一致；留成第四态就与两个布尔没有区别。`CONTEXT.md` 里扫描与值守是并列关系，两个布尔直接对应这两个词。

- **新增状态 `WATCH_ONLY` 表示只值守的 Run。** 要同时改状态机、`uk_run_running` 部分索引（`V12`）和控制台所有按状态分支的地方，而 `STANDBY` 的定义「扫描已中止、值守仍在继续」已经覆盖它，加状态只换来文案更精确。

- **只开扫描时用 `CANCELLED` 收尾。** 与用户点「停止」完全同构，但两件事语义不同：扫完是「该做的做完了」，中途停止是「我不要了」。混用会让日报显示「这个平台总是被中途停掉」，也让 `terminal_reason` 失去区分能力。控制台可以把两者都显示成「已结束」，库里不合并。

- **只开扫描时留在待命、只停巡检。** 能靠跨日回扫省掉每天手动启动，代价是一个不值守的平台整夜占着浏览器和一个 `run` 行，而它此刻没有任何事可做。本次不采用；如果之后确实需要「每天自动继续投递」，应该做成值守时段那样的定时唤醒，而不是让待命循环空转。

- **不新增 `SCAN_DISABLED`，复用 `COMBOS_EXHAUSTED`。** 只值守的 Run 从未扫描，写「搜索组合已全部扫完」与实际不符。排查的人会先去看组合表，而那张表是空的。

## Consequences

- **迁移**：新增 `V14__scan_watch_switches.sql`，两列 `INTEGER NOT NULL DEFAULT 1`。`V1`–`V13` 已应用，按项目约定不改。
- **配置读写**：`ConfigController.savePlatformConfig` 对新字段按「未传则保持原值」处理，与 `unreadScanLimit`、`watchIntervalSeconds` 同一口径。旧控制台不会提交这两个字段，若按默认值处理，保存任何一个无关配置都会把用户关掉的开关重新打开。
- **控制台数据**：`GET /api/config/platform/{platform}` 回发两个字段；`/api/system/platforms` 与 `/api/system/overview` 的 DTO 也要带上，否则概览卡无法标注「仅值守」。只值守平台的「今日主动投递」恒为 0，没有标注时读者会把它读成投递失败。
- **主循环分支**：`RunService.runLoop` 在扫描关闭时跳过 `ensureScanDay` 与 `executeCombinations`，直接 `enterStandby(run, SCAN_DISABLED)`；扫描打开而值守关闭时，`executeCombinations` 返回后先走完既有的 `stopRequested` / `DRY_RUN` / `dutyEnded` 三个判定，再以 `finish(COMPLETED, SCAN_DONE)` 结束，不进入 `standbyLoop`。
- **运行中关掉值守**：待命循环新增出口 `StandbyExit.ENDED`，收尾为 `COMPLETED / WATCH_DISABLED`。不复用 `STOPPED`（会落 `CANCELLED`，但这不是用户停的），也不与 `SCAN_DONE` 合并（两种结束的触发条件不同，合并后「扫完了」与「中途被关掉值守」无法区分）。
- **中止点**：`processCards` 的卡片边界新增一条判定，命中时返回 `SCAN_DISABLED` 而不是 `null`。返回 `null` 会被 `executeCombinations` 当作「组合超时」并推进到下一个组合继续导航（`RunService:727-730`）。
- **待命循环的退出**：`sleepInterruptibly` 现在返回 `void`，不必改签名——它返回后循环回到顶部会重读配置，在那里判断值守是否已关闭并结束即可。
- **`CONTEXT.md`**：新增「平台启用」「扫描开关」「值守开关」三个词，并修正「扫描」「值守」「值守期」「值守待命」四处定义。原文假定扫描一定会发生、值守一定会继续，这两条都不再成立。

## 遗留缺口

- 只值守的 Run 长时间只做巡检，浏览器整夜常开。跨夜的内存占用仍然没有实测数据（ADR-0002 已记录同一缺口）。
- 关闭值守后，平台侧的未读标记会持续累积。未读数增长是否触发平台限流或影响后续主动投递，没有数据。
- 只值守模式下 `run` 表不再产生组合行数据，`combination_run` 全为空。日报如果要按组合统计，需要先判断该 Run 是否扫过。
