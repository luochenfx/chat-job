# 扫描与值守拆成两个平台级开关

## 1. 上下文锚定

### 1.1 这是什么

把「这个平台要做什么」从一句话拆成两个独立开关：

- **扫描开关**：该平台是否加载岗位列表、执行投递判定、主动投递。
- **值守开关**：该平台是否巡检未读消息、发回复、发简历。

当前只有一个平台开关（`platform_config.enabled`），语义是「这个平台能不能被启动」。两个平台的实际用法不同：Boss 已经投递过一批岗位，主要价值是守住这些会话；智联是新接入的，主要价值是继续投递。现有开关只能整体开或整体关，表达不了「Boss 只值守不投递」和「智联只投递不回复」。

一句话：平台开关决定「能不能启动」，本次两个开关决定「启动之后做什么」。三者互不派生。

### 1.2 为什么要做

| 现象 | 实测 | 后果 |
|---|---|---|
| Boss 不想再投递，只想守住已有会话 | 只能停用整个平台，或把 `daily_delivery_quota` 设为 0 | 停用会连值守一起关掉；配额为 0 时 `executeCombinations` 仍然先 `openSearch` + `scrollUntilEnd`（`RunService.java:648-652`），每天照样打开每个组合的搜索页并滚动到底，再一张卡都不处理，日志还每天写「今日主动投递配额已用完（0/0）」 |
| 智联只投递，不想回复消息 | `ai_reply_enabled=0` 只切换回复策略，`UnreadWatcher.sweepLegacy` 仍会 `listUnreadThreads` 并逐条 `readMessages` | 两个平台的 `readMessages` 都先 `openThread`（`BossAdapter.java:757`、`ZhaopinAdapter.java:655`）。点开会话会清掉平台侧未读标记，形成「读过但不回」，比不读更糟 |
| 想确认某平台今天到底投没投 | 概览卡的「今日主动投递」只报数字 | 只值守平台的这个数字恒为 0，界面没有任何地方说明这是配置结果而不是投递失败 |

### 1.3 现状与边界（读代码确认，不是推断）

| 位置 | 现状 | 对本 spec 的含义 |
|---|---|---|
| `platform_config.enabled`（`V1`） | 每平台一行，`ConfigStore.platformEnabled` 只读、不写库 | 新增两列走同一张表、同一套读写口径 |
| `PlatformSwitches`（`platform/`） | 四个执行阻断点：`RunService.start`、`BrowserSession.ensureReady`、`RunServices.wakeWaitingRuns`、`SystemController` 三入口 | 只管「能不能启动」，本次不改它的判定 |
| `RunService.start`（`:188-192`） | 搜索组合为空即拒绝启动 | 只值守时不扫描，这条校验必须跳过，否则一个没填城市关键词的新平台无法只值守 |
| `RunService.runLoop`（`:330-359`） | 先 `ensureScanDay`，再 `executeCombinations`，然后进入 `standbyLoop` | 三个新分支都落在这一个循环里 |
| `RunService.executeCombinations`（`:648-652`） | 先 `openSearch` + `scrollUntilEnd`，再 `processCards` | 「进组合之前」也要判扫描开关，否则关掉扫描后仍会开搜索页 |
| `RunService.processCards`（`:717-726`） | 配额与评估上限在卡片边界判定，命中返回 `StopReason` | 扫描开关在同一处判定，理由相同：这是一张卡处理完才安全的唯一点 |
| `StandbyGate.decide`（`:66-68`） | `scanDay == null` 按跨日处理并返回 `SCAN` | **只值守的 Run 的 `scanDay` 一直是 null**，不拦这一条，第一次待命循环就会转去扫描 |
| `RunService.sleepInterruptibly`（`:574-588`） | 每秒检查 `stopRequested` 与时段结束 | 值守开关的生效点，不必改签名 |
| `RunService.standbyLoop`（`:413-431`） | 每轮重读配置（配额、上限、间隔） | 值守开关在同一处重读，天然即刻生效 |
| `RunService.enterStandby`（`:486-490`） | `switch` 穷举三个 `StopReason` | 新增 `SCAN_DISABLED` 后，`switch` 表达式必须补支，否则编译不过（这是好事，漏改会被编译器拦住） |
| `RunService.finishDryRun`（`:616-622`） | 演练模式跑完即结束，不进待命 | 扫描关闭时演练没有可跑的内容，要单独收尾 |
| `uk_run_running`（`V12`） | 部分唯一索引，含 `RUNNING / PAUSED / WAITING_DUTY / STANDBY` | 不新增状态，索引不动 |
| `ConfigController.savePlatformConfig`（`:310-317`） | `unreadScanLimit`、`watchIntervalSeconds` 用「未传则保持原值」 | 两个新字段同一口径，否则旧控制台保存任何配置都会把开关打开 |

### 1.4 术语

以 `CONTEXT.md` 为准，本次新增三个词条：**平台启用**、**扫描开关**、**值守开关**；修订四个：**扫描**（不再总是发生）、**值守**（不再总是继续）、**值守待命**（增加「或该平台未启用扫描」这一起因）、**值守期**（只投递平台的这一期在扫描结束时结束）。

> 实现里两个平台级判定的对象不同：`PlatformSwitches` 判「能不能启动」，本次的读法 `ConfigStore.platformScanEnabled` / `platformWatchEnabled` 判「启动后做什么」。两处都在 `platform_config` 上，但一个改动会波及浏览器与调度，另一个只波及 Run 内部的两个分支，所以不合并。

## 2. 约束与目标

### 2.1 硬约束

1. **两个开关默认都开。** `V14` 两列 `NOT NULL DEFAULT 1`，已有两个平台的既有行为不变。
2. **两个都关时拒绝保存。** 报错点名平台，并指向平台开关。平台启用、扫描、值守全关之后与「停用」的差别只剩浏览器没被主动关掉（停用会关浏览器，`ConfigController:324-334`），保留这个状态只会让停用有两种写法。
3. **关闭扫描 = 不加载列表、不判定、不投递、不占投递配额与评估上限。** 不是「扫了不投」。
4. **关闭值守 = 不巡检、不读消息、不发消息、不发简历。** 平台侧的未读标记保持原样。
5. **开关即刻生效**，生效点固定三处：卡片边界（扫描）、睡眠的每秒检查（值守）、插空巡检的调用点（值守）。
6. **关闭扫描后不得再有任何搜索页导航。** 包括「进组合之前」，不只是「卡片之间」。
7. **只开扫描时，组合队列扫完即结束 Run**，状态 `COMPLETED`、`terminal_reason=SCAN_DONE`，不进待命、不跨日回扫。
8. **只开值守时，启动即进 `STANDBY`**，中止原因 `SCAN_DISABLED`；此状态下 `StandbyGate` 永不返回 `SCAN`。
9. **演练模式仍不进待命。** 扫描关闭 + 演练模式时，Run 直接结束并说明「没有可演练的内容」。
10. **不改值守的判定与出站校验。** spec 0002 的 `UnreadWatcher.process` 一行不动，本次只改「什么时候调它」。
11. **不改 `PlatformSwitches` 的四个阻断点。** 停用平台的行为完全不变。
12. **`run.status` 枚举不新增值。** 只值守仍在 `STANDBY`，`uk_run_running` 与所有按状态分支的代码都不用改。

### 2.2 目标（完成的定义）

- Boss 设为只值守后可正常启动：不打开任何搜索页，日志不出现「开始组合」「开始扫描」，直接进入值守并按巡检间隔回复未读。
- 智联设为只投递后可正常启动：扫描与投递照旧，扫完组合后 Run 立即结束，状态 `COMPLETED`，不再「整夜待命」。
- 运行中关掉扫描，当前这张卡处理完后停止导航，Run 进入 `STANDBY`，值守继续。
- 运行中关掉值守，最多一秒内停止巡检；若扫描已停，Run 立即结束。
- 概览卡能一眼看出每个平台在做什么，不需要打开配置页确认。

### 2.3 非目标（明确不做）

- **不做「扫描但不投递」（只看不投）。** 那是一条新的执行路径，而且每天重复判定同一批岗位会白烧 AI 与评估上限。要做应另开需求。
- **不做独立线程/定时器。** 仍是一条 `run-worker` 线程内的时间片交替（ADR-0002、ADR-0003 的结论不变）。
- **不做「只开扫描时每天自动继续投递」。** 需要继续就重新点启动；若要自动化，应做成值守时段那样的定时唤醒，而不是让待命循环空转。
- **不新增 `run.status` 值，不改 `uk_run_running`。**
- **不改 AI 配置、规则引擎、出站四检、通知渠道。**
- **不做「重启后自动接管」。** 只值守的 Run 重启后同样标 `FAILED / SERVICE_RESTART`，与现有口径一致。

## 3. 方案设计

### 3.1 数据

`V14__scan_watch_switches.sql`：

```sql
ALTER TABLE platform_config ADD COLUMN scan_enabled  INTEGER NOT NULL DEFAULT 1;
ALTER TABLE platform_config ADD COLUMN watch_enabled INTEGER NOT NULL DEFAULT 1;
```

`ConfigStore.defaults()` 同步两个字段为 1（默认值的唯一来源；漏这一处会让「没配过的平台」在新字段上读出 null）。

读法照抄 `platformEnabled` 的形状——只读、不写库、缺省按开：

```java
/** 该平台是否参与扫描。只读，绝不写库：它会在每张卡的边界被调用。 */
public boolean platformScanEnabled(String platform) {
    PlatformConfigEntity cfg = peekPlatformConfig(platform);
    return cfg.getScanEnabled() == null || cfg.getScanEnabled() != 0;
}

public boolean platformWatchEnabled(String platform) { ... 同上 ... }
```

### 3.2 四种开关组合与状态走向

`enabled` 为 0 时两个开关不起作用（平台根本起不来）。下表都是 `enabled=1` 的情形。

| 扫描 | 值守 | 启动后 | 扫描结束时 | 跨日 |
|---|---|---|---|---|
| 开 | 开 | `RUNNING` | `STANDBY` 继续巡检 | 待命循环回扫（现状） |
| 开 | 关 | `RUNNING` | `COMPLETED / SCAN_DONE` 结束 | 不适用（已结束） |
| 关 | 开 | 直接 `STANDBY / SCAN_DISABLED` | 不适用（从未扫描） | 不回扫（第 3.4 条） |
| 关 | 关 | 不允许保存（2.1 第 2 条） | — | — |

```text
 enabled=1 且 扫描=1 ──► RUNNING ──► 扫描 ──┬─ 配额/评估/组合耗尽 ─► STANDBY（值守=1）
                                            ├─ 值守=0 ─────────────► COMPLETED / SCAN_DONE
                                            └─ 时段到点 ───────────► WAITING_DUTY ──► 唤醒续扫

 enabled=1 且 扫描=0 ──► STANDBY / SCAN_DISABLED ──┬─ 值守=1 ──► 按间隔巡检
                                                   └─ 时段到点 ─► WAITING_DUTY ──► 唤醒仍进 STANDBY

 enabled=0（停用）──► 不建 Run、不开浏览器（PlatformSwitches，本次不动）
```

### 3.3 `runLoop` 的三个新分支

新分支插在 `runStore.markRunning(run)` 与 `run.started` 事件之后（`RunService:319-326` 那一段），自检、探测登录、`logDutyDeadline` 都不动——只值守的平台同样需要浏览器与登录态。

```java
// 入口分叉：只值守的平台不扫就没有组合可跑，也没有可演练的内容
if (!scanEnabled) {
    if ("DRY_RUN".equals(run.getMode())) {
        finish(run, RunStatus.COMPLETED, "SCAN_DISABLED",
                "演练模式：该平台未启用扫描，没有可演练的内容");
        return;
    }
    enterStandby(run, StandbyGate.StopReason.SCAN_DISABLED);
} else {
    act(run, "开始扫描");
}

StandbyGate.StopReason reason = scanEnabled ? null : StandbyGate.StopReason.SCAN_DISABLED;

while (true) {
    if (scanEnabled) {
        ensureScanDay(run);
        reason = executeCombinations(run);

        // 既有三个判定，顺序不变
        if (stopRequested) { finish(run, RunStatus.CANCELLED, "CANCELLED", "用户主动停止"); return; }
        if ("DRY_RUN".equals(run.getMode())) { finishDryRun(run, reason); return; }
        if (dutyEnded) { park(run, "DUTY_END"); return; }

        // 扫描开、值守关：扫完就结束，不进待命
        if (!watchEnabled) {
            finish(run, RunStatus.COMPLETED, "SCAN_DONE",
                    "扫描已结束（" + whyOf(reason) + "），该平台未启用值守，任务结束");
            return;
        }
        enterStandby(run, reason);
    }

    StandbyExit exit = standbyLoop(run, reason);
    if (exit == StandbyExit.STOPPED) { finish(run, RunStatus.CANCELLED, "CANCELLED", "用户主动停止"); return; }
    if (exit == StandbyExit.PARKED) { return; }      // 挂起已落库
    if (exit == StandbyExit.ENDED) {                 // 运行中关掉了值守
        finish(run, RunStatus.COMPLETED, "WATCH_DISABLED",
                "值守已关闭，该平台没有可继续的工作，任务结束");
        return;
    }
    // RESCAN：扫描关闭时不可达（StandbyGate 第 2 条保证），保留该分支以维持循环结构
}
```

三个要点：

1. **入口读一次开关只用于分叉**。循环内两处「运行中改了要不要生效」的判定各自从配置重读：卡片边界读扫描开关，`standbyLoop` 顶部与插空巡检读值守开关。把入口读到的值当缓存用，等于让开关变成「下次启动生效」。
2. **`!watchEnabled` 的判定必须排在 `stopRequested` / `DRY_RUN` / `dutyEnded` 之后**。用户点停止仍是 `CANCELLED`，时段到点仍是挂起（第二天唤醒后继续扫），只有「扫描正常结束」这一支才落到 `SCAN_DONE`。
3. **只值守的 Run 在 `standbyLoop` 里永远拿不到 `RESCAN`**（3.4 第 2 条保证），所以回到循环顶部后不会进 `if (scanEnabled)` 分支，而是再次 `standbyLoop`。
4. **实现里 `!watchEnabled` 那一步读的是当下的配置，不是上面伪码里启动时那个值**（P2 实施记录里记为与伪码的一处偏离）。理由：它是「扫描结束后进不进待命」的最后一道门，用启动值的话，运行中关掉值守的这一轮会先进待命、发出「值守继续」的横幅与企业微信通知，随后被 `standbyLoop` 顶部判到 `ENDED` 立刻结束——那条横幅与通知在发出时已经是假的。改读当下值后，同一场景直接以 `SCAN_DONE` 收尾。

代码块里的 `whyOf(reason)` 是从 `enterStandby` 现有 `switch` 里抽出来的私有方法（`RunService:486-490`），结束文案与待命文案共用同一句原因。抽出来的理由是：不抽就会有两份「配额已用完 / 评估已达上限 / 组合已扫完」的措辞，改一处漏一处，而这类漏改在日志里表现为「同一件事两种说法」，排查时会以为是两件事。

### 3.4 `StandbyGate`：多一个入参

```java
static Action decide(LocalTime now, DutyWindow window, StopReason reason,
                     String scanDay, String today,
                     int deliveredToday, int quota, int evaluatedToday, int evalLimit,
                     boolean scanEnabled)          // ← 新增，放最后
```

判定顺序改为：

| 顺序 | 条件 | 结果 | 为什么 |
|---|---|---|---|
| 1 | `!window.contains(now)` | `PARK` | 时段外一律不操作页面，优先级最高 |
| 2 | `!scanEnabled` | `WATCH` | 关掉扫描的平台上不能有任何回扫理由 |
| 3 | `scanDay != today` | `SCAN` | 新的一天，配额与评估归零 |
| 4 | `reason == QUOTA && deliveredToday < quota` | `SCAN` | 配额被调高 |
| 5 | `reason == EVAL_LIMIT && evaluatedToday < evalLimit` | `SCAN` | 评估上限被调高 |
| 6 | 其余 | `WATCH` | 包括「组合已扫完」「配额被调小」 |

第 2 条独立成一条、放在跨日之前，而不是「在每条 `SCAN` 分支上加 `&& scanEnabled`」：`scanDay == null`（只值守的 Run 一直是 null）按现有逻辑等价于跨日，逐个加条件的话，以后新增回扫理由时仍会漏掉这一条。新参数放最后一位，是为了让现有 6 个既有用例只需在尾部补一个 `true`。

### 3.5 关闭扫描的生效点

两处，缺一处就会在关掉之后继续打开搜索页：

| 位置 | 判定 | 返回 |
|---|---|---|
| `executeCombinations` 组合循环顶部（`openSearch` 之前） | `!configStore.platformScanEnabled(platform)` | `StopReason.SCAN_DISABLED` |
| `processCards` 卡片边界（与配额判定同处） | 同上 | `StopReason.SCAN_DISABLED` |

返回 `SCAN_DISABLED` 而不是 `null`：`null` 在 `executeCombinations` 里表示「组合超时」，会被当成「这个组合跑完了」推进到下一个组合继续导航（`RunService:727-730`）。这是本次最容易写错的一处。

`enterStandby` 与 `stopLabel` 的 `switch` 各补一支：

```java
case SCAN_DISABLED -> "未启用扫描";
```

`enterStandby` 的整句文案在 `SCAN_DISABLED` 下要换，不能沿用「扫描已结束（…），值守继续」：这句话在这个场景里是假的，从未扫过。改为「未启用扫描，本次只值守：每 N 分钟检查一次未读消息，…」。通知标题同理（`notify(run, "standby", ...)` 的标题原文是「扫描已结束，值守继续」）。

### 3.6 关闭值守的生效点

三处：

| 位置 | 判定 | 行为 |
|---|---|---|
| `processCards` 的插空巡检（每 5 卡 / 120 秒，`:756-764`） | `!platformWatchEnabled` | 跳过 `sweep`，扫描继续 |
| `standbyLoop` 每轮顶部（重读配置之后） | 同上 | 返回新的 `StandbyExit.ENDED`，由 `runLoop` 收尾为 `COMPLETED / WATCH_DISABLED` |
| `sleepInterruptibly` 的每秒循环（`:574-588`） | 同上，加进既有的 `stopRequested` / 时段检查 | 立即 return，让上一行的判定尽快生效 |

`StandbyExit` 现有三个值（`RESCAN / STOPPED / PARKED`）都不能复用：`STOPPED` 会落 `CANCELLED`，而这次不是用户停的；`PARKED` 是挂起，第二天还会被唤醒。加 `ENDED` 一支，收尾用 `COMPLETED / WATCH_DISABLED`，与「只开扫描扫完了」的 `SCAN_DONE` 分开记——两种结束的触发条件不同，混成一个码之后，日报里「扫完了」与「被人中途关掉值守」将无法区分。

`sleepInterruptibly` 返回 `void`，不必改签名：它返回后 `standbyLoop` 回到循环顶部，在那里重读配置并返回 `ENDED`。把「关掉值守后最多一秒停止巡检」建立在这条既有通道上，不新增唤醒机制。

**插空巡检的判定要读配置而不是读循环入口缓存的值**：`processCards` 里 `dryRun` 之类的值在整段扫描中不变，而值守开关可以在扫描中途被关掉。

### 3.7 配置读写与接口

| 接口 | 改动 |
|---|---|
| `GET /api/config/platform/{platform}` | 回发 `scanEnabled` / `watchEnabled` 两个布尔 |
| `PUT /api/config/platform/{platform}` | 接收两个字段；`null` 表示保持原值（与 `unreadScanLimit` 同口径）；合并出最终值后若两个都关，抛 `IllegalArgumentException`（全局异常处理映射为 400） |
| `GET /api/system/platforms` | `descriptor()` 增加两个布尔。控制台各页共用这份清单，概览徽标与任务页提示都从这里取，不新增请求 |
| `GET /api/system/overview` | `platformCard()` 增加两个布尔 |

「双关拒绝」抽成静态方法，与 `PlatformSwitches.disabledMessage` 放在一起——两个类都在做「开关 + 一句能照做的报错文案」：

```java
/** 扫描与值守不能同时关闭；否则这个平台启动后什么都不做。 */
public static void requireSomethingOn(String platform, boolean scanOn, boolean watchOn) {
    if (!scanOn && !watchOn) {
        throw new IllegalArgumentException("「" + PlatformInfo.of(platform).label()
                + "」的扫描与值守不能同时关闭：那样它启动后什么都不做。要整体停用它，请用平台开关");
    }
}
```

### 3.8 控制台

| 页面 | 改动 |
|---|---|
| `config.vue` 平台配置 tab | 三枚开关收成一组，标签分别是「平台启用」「扫描」「值守」；原 `switchHint` 拆成三条；`off-note` 从一条变两条（仅值守 / 仅扫描各一句后果说明）。保存仍与其余字段同批，不做即时保存 |
| `config.vue` | 只值守时把「搜索条件」「配额与节奏」两张卡置灰并注明「未启用扫描，这些字段不生效」——留可编辑但没作用的字段，等于让人以为生效了 |
| `index.vue` 概览卡 | 平台名旁加徽标：`仅值守` / `仅扫描` / `已停用`。只值守平台的「今日主动投递」显示为 `—` 而不是 `0`，否则会被读成投递失败 |
| `task.vue` | 无进行中 Run 时，在启动按钮旁给一句提示：只值守「该平台未启用扫描，启动后只值守」；只扫描「该平台未启用值守，扫完即结束」 |
| `system.vue` | 不改。自检 / 探测登录 / 打开登录页在只值守时照常可用（它们只关心浏览器与登录态） |

### 3.9 事件与通知

- `run.standby` 的 `reason` 会出现新值 `SCAN_DISABLED`。SSE 事件文案在 `console/pages/task.vue` 的 `describe()`（`task.vue:298`），要能显示它；只值守时的「值守待命」横幅正文需另写一句，现有正文假定扫描发生过。
- `run.finished` 结构与语义不变。两个新的 `terminal_reason` 都落在 `COMPLETED` 上：`SCAN_DONE`（只开扫描，扫完了）与 `WATCH_DISABLED`（运行中关掉了值守）。`finish()` 对 `COMPLETED` 记 INFO 级别，因此这两条不会出现在 WARN 里。
- 企业微信通知：`enterStandby` 在 `SCAN_DISABLED` 下标题改为「未启用扫描，值守中」；两个 `COMPLETED` 收尾都不发通知（与现有 `COMPLETED` 一致）。

## 4. 落地实施

### 4.1 文件清单

| 文件 | 改动 |
|---|---|
| `server/src/main/resources/db/migration/V14__scan_watch_switches.sql` | 新增两列 |
| `server/.../store/entity/PlatformConfigEntity.java` | `scanEnabled` / `watchEnabled` |
| `server/.../store/ConfigStore.java` | `defaults()` 补两列；新增 `platformScanEnabled` / `platformWatchEnabled` |
| `server/.../platform/PlatformSwitches.java` | 新增静态 `requireSomethingOn(platform, scanOn, watchOn)` |
| `server/.../orchestrator/StandbyGate.java` | `StopReason` 加 `SCAN_DISABLED`；`decide` 加 `scanEnabled` 参数与第 2 条判定 |
| `server/.../orchestrator/RunService.java` | `start()` 跳过空组合校验；`runLoop` 三个分支（不算、只扫、只守）与 `StandbyExit.ENDED` 收尾；`executeCombinations` 与 `processCards` 加扫描判定；`enterStandby` / `stopLabel` 补 `SCAN_DISABLED` 文案；`whyOf` 抽出；插空巡检与 `sleepInterruptibly` 加值守判定；`finishDryRun` 分支 |
| `server/.../web/ConfigController.java` | 两个字段的 GET/PUT 与双关拒绝 |
| `server/.../web/SystemController.java` | `descriptor()` / `platformCard()` 带两个布尔 |
| `console/pages/config.vue` | 三枚开关一组、两条 `off-note`、只值守时置灰两张卡 |
| `console/pages/index.vue` | 徽标与 `—` 口径 |
| `console/pages/task.vue` | 启动前提示；`describe()` 补 `SCAN_DISABLED`；只值守时的待命横幅正文 |
| `server/src/test/.../orchestrator/StandbyGateTest.java` | 既有 6 例补参数；新增 3 例 |
| `server/src/test/.../platform/PlatformSwitchesTest.java` | 新增 2 例（双关拒绝、单关放行） |
| `server/src/test/.../store/ConfigStoreTest.java` | 默认值两列 |

### 4.2 分期（每步独立可验证）

| 期 | 内容 | 验证方式 |
|---|---|---|
| **P0 数据与读写** | `V14`、实体两列、`defaults()`、`ConfigStore` 两个读法、`ConfigController` GET/PUT 与双关拒绝、`SystemController` 两个 DTO | 起服务看 Flyway 通过；老库两个平台读出 `true/true`；把两个都关提交，返回 400 且文案点名平台 |
| **P1 扫描关闭** | `start()` 跳过空组合校验；`runLoop` 的 `!scanEnabled` 分支；`executeCombinations` / `processCards` 判定；`StandbyGate` 新参数与第 2 条；`enterStandby` / `stopLabel` 文案；`finishDryRun` | 把 Boss 的扫描关掉、城市关键词清空后启动：能启动、日志无「开始组合」、状态 `STANDBY`；给 HR 发一条消息能被回复；手动改 `scan_day` 成昨天，确认不回扫 |
| **P2 值守关闭** | `runLoop` 的 `!watchEnabled` 结束分支；插空巡检判定；`standbyLoop` 顶部判定与 `sleepInterruptibly` 检查 | 把智联的值守关掉启动：扫完组合后状态 `COMPLETED`、`terminal_reason=SCAN_DONE`；运行中关掉值守，一秒内日志停止巡检并结束；两个平台同时跑，互不影响 |
| **P3 控制台** | 三枚开关一组、两条 `off-note`、置灰两张卡、概览徽标与 `—`、任务页提示、`describe()` | 逐页对照四种组合截图核对；改开关后不保存即离开，确认没有「半保存」状态 |
| **P4 回归** | 全量单测；两个平台各跑一次真实流程 | `mvn test` 全绿；Boss 只值守、智联只投递各跑一轮，状态与日志与本 spec 第 2.2 条一致 |

**P0 实施记录（2026-11-01，真实库 + 服务启动 + 接口取证）**

落点：

| 文件 | 实际改动 |
|---|---|
| `V14__scan_watch_switches.sql` | 两列 `INTEGER NOT NULL DEFAULT 1` |
| `PlatformConfigEntity` | `scanEnabled` / `watchEnabled` |
| `ConfigStore` | `defaults()` 设 1/1；新增 `platformScanEnabled` / `platformWatchEnabled`（走 `peekPlatformConfig`，只读不写库）；`defaults()` 由 `private` 改为包内可见，供 `ConfigStoreTest` 断言这份默认值 |
| `PlatformSwitches` | 静态 `requireSomethingOn`，与 `disabledMessage` 同处 |
| `ConfigController` | `PlatformConfigBody` 加两个字段；GET 回发两个布尔；PUT 先合并再校验 |
| `SystemController` | `descriptor()` / `platformCard()` 各加两个布尔；`enabled(cfg)` 与两个新字段共用 `on(Integer)` |

取证（`java -jar target/chat-job.jar --server.port=8099`，库为 `data/chat-job.db`）：

- Flyway：`Migrating schema "main" to version "14 - scan watch switches"` → `Successfully applied 1 migration`，现处 v14；
- 老库两个平台读回 `scanEnabled=true, watchEnabled=true`（`GET /api/config/platform/{boss,zhaopin}`、`/api/system/platforms`、`/api/system/overview` 三处都是）；
- `PUT {"scanEnabled":false,"watchEnabled":false}` → **400**
  `「BOSS 直聘」的扫描与值守不能同时关闭：那样它启动后什么都不做。要整体停用它，请用平台开关`；
- `PUT {"scanEnabled":false}` → 200，读回 `scan=false, watch=true`（未传的那个保持原值）；随后只传 `{"enabled":true,"watchEnabled":false}` → 仍 400（判定用的是**合并后的最终值**，不是请求里那个字段）；
- 全量单测 **270 例通过**（`PlatformSwitchesTest` +2、`ConfigStoreTest` +1）。

**实施中发现的一条接口口径差异，未改，先记下**：`PUT /api/config/platform/{platform}` 里只有
`unreadScanLimit`、`watchIntervalSeconds`、`scanEnabled`、`watchEnabled` 是「未传保持原值」，
其余字段（含 `enabled`、`cities`、`keywords`、`quota`、`duty*`）未传即按缺省值写入。
所以手工用 curl 验证这个接口时，请求体必须带全字段，否则会把该平台的其他配置重置成默认值。
本次 P0 验证踩到这一点：验证脚本只带了两个开关，Boss 的 `cities`/`keywords`/`salary`/`duty_end_time`
被写成默认值，已按 `data/backup/chat-job-before-p1-verify.db` 与 `run_log` 里的实际运行记录（组合
「福州 / java」、配额 30/30、时段 09:00–19:00）逐项恢复。控制台一直是整份对象 PUT，不受影响。

**P1 实施记录（2026-11-01，真实服务 + 真实库 + 真实浏览器）**

落点：

| 文件 | 实际改动 |
|---|---|
| `StandbyGate` | `StopReason` 加 `SCAN_DISABLED`；`decide` 尾加 `scanEnabled`，第 2 条判定放在 `PARK` 之后、跨日之前 |
| `RunService.start` | 关闭扫描时不生成组合（`plan` 不调用），并跳过「搜索条件为空」校验 |
| `RunService.runLoop` | 启动时读一次 `scanEnabled`；为 false 时 `DRY_RUN` 直接 `finish(COMPLETED / SCAN_DISABLED)`，否则 `enterStandby(SCAN_DISABLED)`；循环内扫描段整段包在 `if (scanEnabled)` 里；`reason` 变量提到循环外 |
| `RunService.standbyLoop` | 新参数 `scanEnabled` 透传给 `decide` |
| `RunService.executeCombinations` | 组合循环顶部（写「开始组合」之前）判开关，返回 `SCAN_DISABLED` |
| `RunService.processCards` | 卡片边界（时段检查之后、配额之前）判开关，返回 `SCAN_DISABLED` |
| `RunService.enterStandby` | 抽出 `whyOf`；`SCAN_DISABLED` 用另一句横幅与通知标题 |
| `RunService.stopLabel` / `finishDryRun` | 各补 `SCAN_DISABLED` 一支；`finishDryRun` 的文案与入口分支分开写 |
| `StandbyGateTest` | 既有 8 处调用尾部补 `true`；新增 3 例 |

取证（`java -jar server/target/chat-job.jar --server.port=8099`，库为 `data/chat-job.db`）：

- Flyway：`Current version of schema "main": 14` → `up to date. No migration necessary`（P1 无新迁移）；
- **验收 9**（演练 + 扫描关）：run 48 → `COMPLETED / SCAN_DISABLED`，`combination_run` 0 行，日志只有 3 行，
  无「开始扫描」「开始组合」，末行为「演练模式：该平台未启用扫描，没有可演练的内容」；
- **验收 1**（只值守，正常模式）：run 49 / 51 / 53 → `STANDBY`，横幅「未启用扫描，本次只值守：每 30 秒检查
  一次未读消息，到 23:59 停止操作页面并挂起」，`combination_run` 0 行；run 51 的 SSE 抓到
  `run.standby {"reason":"SCAN_DISABLED","delivered":0,"evaluated":0,"quota":30}`；
- **验收 3**（不回扫）：run 51 的 `scan_day` 在内存里是 null（只值守时的正常值，也是 R2 的触发条件），
  同时把库里的 `scan_day` 手工改成 `2026-10-31`；下一轮仍是巡检，没有「已跨自然日…恢复扫描」，
  状态仍 `STANDBY`，库里的 `scan_day` 也没有被 `ensureScanDay` 覆盖 —— 说明整个循环没有进入扫描分支；
- **验收 5**（运行中关扫描）：run 50 / 52 都是扫描进行中（组合 #0 已 `openSearch` + `scrollUntilEnd`，
  300 张卡 / 40 轮）把开关关掉，日志顺序为
  「该平台已关闭扫描，在第 1 张卡前停止；值守继续」→「组合 #0 中止：该平台已关闭扫描，剩余卡片不再处理」，
  没有进入第 2 个组合，`combination_run.evaluated = 0`。演练模式按 `finishDryRun` 收尾为
  `COMPLETED / SCAN_DISABLED`；
- **验收 10**（时段外启动）：run 54 的值守时段临时设为 `02:50–23:59`，02:46 启动 → `WAITING_DUTY`；
  02:50 由调度唤醒 → 直接 `STANDBY`，日志无「开始扫描」；
- 通知标题：`WecomRobotNotifier` 的日志是「[BOSS 直聘] 未启用扫描，值守中 — 未启用扫描，本次只值守：…」。
  本机 `notify.wecom-webhook` 为空，所以只走到「通知未配置」这一支，没有真的投递；
- 页面（bsk 观测 boss profile 那个实例的标签页）：扫描关时标签页是 `zhipin.com/web/geek/chat`；
  扫描开时是 `zhipin.com/web/geek/jobs?city=101230100&salary=405&query=java`。只值守期间没有
  `/web/geek/jobs` 的新导航；`threadPage` 每轮巡检结束就关闭，所以会话页标签是瞬时的，
  观测窗口要落在巡检过程中；
- 全量单测 **273 例通过**（`StandbyGateTest` +3）。

实施中确认、并决定写进代码注释的两条边界：

1. **扫描开关只在启动时读一次。** 只值守的 Run 在运行中把扫描打开，本轮仍然不扫，要重新启动。
   反过来（扫描开着时关掉）由组合入口与卡片边界即刻生效。这样做的理由是让「循环要不要扫」
   与「`StandbyGate` 能不能回扫」始终是同一个值，否则会出现「发了 `run.rescan`、状态改回 `RUNNING`，
   却没有真的去扫」。
2. **停止请求在巡检轮次内不抢占。** run 49 点停止后约 30 秒才落 `CANCELLED`，因为那一刻正在一轮
   涵盖 40 个会话的巡检里。与扫描期的「卡片边界」是同一约定：手上这一轮做完再停。

本次未取证的验收项，如实记录：

- 5.2 第 2 条「给 HR 发一条消息 → 在巡检间隔内被回复」需要对方真的发消息进来，本次只取到巡检本身
  的证据（日志「值守待命第 N 轮：会话 40，回复 0」），「被回复」这一环没有现场证据；
- 5.2 第 4、6 条属 P2（关闭值守），本次未实现；
- 控制台的徽标、`—` 口径、任务页提示属 P3，本次未改，`task.vue` 的 `describe()` 暂时还显示不出
  `SCAN_DISABLED` 这个新原因。

**P2 实施记录（2026-11-01，真实服务 + 真实库 + 真实浏览器）**

落点：

| 文件 | 实际改动 |
|---|---|
| `RunService.runLoop` | 启动后若值守关闭，先写一行「本轮只扫描…」；扫描段结束后，在 `stopRequested` / `DRY_RUN` / `dutyEnded` 三个既有判定之后加「未启用值守 → `finish(COMPLETED, SCAN_DONE)`」；`StandbyExit.ENDED` 收尾为 `COMPLETED / WATCH_DISABLED` |
| `RunService.standbyLoop` | 每轮重读配置之后判值守开关，关闭则返回 `ENDED`；`sleepInterruptibly` 调用处改为传 `run` |
| `RunService.StandbyExit` | 新增 `ENDED`（与 `STOPPED` 分开的理由写在枚举注释里） |
| `RunService.sleepInterruptibly` | 每秒检查加入值守开关（签名由 `(window, millis)` 改为 `(run, window, millis)`——它需要平台才能读配置；返回类型仍是 `void`） |
| `RunService.processCards` | 插空巡检命中时先判值守开关，关闭则跳过 `sweep`；计数器先归零再判，避免关掉期间累计卡片数 |
| `RunService.whyOf` | 新增 `(reason, platform)` 重载：`runLoop` 手上没有 `enterStandby` 算好的当日数字 |
| `RunService.executeCombinations` | 补方法注释（原来那句「跑组合队列」的 javadoc 落在段分隔线上，没有挂在方法上；P1 插入枚举后位置错开） |
| `tools/verify-p2.mjs` | 新增：A/B/C 三个场景的运行时验收脚本，结束时把开始前读到的整份配置 PUT 回去并逐字段核对 |

与 §3.3 伪码的一处偏离：伪码在循环里用启动时读到的 `watchEnabled`，实现改成在
`!configStore.platformWatchEnabled(...)` 处读当下配置。理由已在 §3.3 第 4 条写明。

取证（`java -jar server/target/chat-job.jar --server.port=8099`，库为 `data/chat-job.db`，
配置改动由脚本临时施加并还原，`GET` 前后逐字段一致）：

| run | 平台 | 模式 | 结果 | `combination_run` | 巡检日志 |
|---|---|---|---|---|---|
| 55 | boss | NORMAL | `COMPLETED / SCAN_DONE` | 1 行 DONE，0 张卡 | 0 行 |
| 56 | boss | DRY_RUN | `COMPLETED / COMPLETED` | 1 行 DONE，30 张卡，评估 26 | 0 行 |
| 57 | boss | DRY_RUN | `COMPLETED / COMPLETED` | 1 行 DONE，30 张卡，评估 8 | 0 行 |
| 58 | zhaopin | NORMAL | `COMPLETED / WATCH_DISABLED` | 1 行 DONE，24 张卡，评估 19 | 1 行（第 1 轮） |

- **验收 4（只开扫描，run 55）**：关键词换成不会命中的串，日志共 6 行 ——
  「任务启动」→「该平台未启用值守：本轮只扫描，扫描结束后收尾，不进入值守待命」→「开始组合 #0」
  →「加载 0 张卡片」→「COMPLETED / SCAN_DONE / 扫描已结束（搜索组合已全部扫完），该平台未启用值守，
  任务结束」。没有待命横幅，`combination_run` 一行 DONE。
- **插空巡检跳过（run 56 / 57）**：演练模式不发消息、不投递，但 `processCards` 照常逐卡判定，
  所以它是这一处的可用场景。run 56 评估 26 次、36 行日志、0 条 WARN，巡检行 0 —— 每 5 张卡一个巡检点，
  26 张意味着至少 5 个巡检点被跳过；run 57 评估 8 次，同样 0 条巡检（见下面安全验证一条，
  run 57 的卡片只处理到第 8 张）。
- **验收 6（运行中关值守，run 58）**：智联，间隔临时设 600 秒。时间线：
  03:09:18 组合超时 → 进待命（横幅「扫描已结束（搜索组合已全部扫完），值守继续：每 10 分钟检查一次
  未读消息，到 23:59 停止操作页面并挂起」）→ 03:09:29 第 1 轮巡检（会话 20，回复 0），
  下一轮排在 03:19:05 → 03:09:29 关掉值守 → 03:09:30.589「该平台已关闭值守，本轮不再巡检，任务结束」
  → 03:09:30.595 `COMPLETED / WATCH_DISABLED`。**关开关到落终态 1017ms**，第 1 轮之后没有第 2 轮。
  下一轮巡检本来排在 03:19:05，所以这一秒内的结束只可能来自 `sleepInterruptibly` 的每秒检查，
  不是间隔到期。
- **页面（bsk）**：智联实例（`f1c52653`，扩展 0.4.0）跑完后标签页停在
  `zhaopin.com/jobs/?jl=681&kw=zzzz-p2-no-such-job`，没有再出现会话页 —— 巡检期的会话页是瞬时的
  （`threadPage` 每轮结束就关闭），关掉值守之后也没有新的导航。截图留在 `build/verify-p2/`（不入库）。
- 全量单测 **273 例通过**，P2 未新增单测：`StandbyExit` 是私有枚举，`standbyLoop` / `processCards`
  需要真实浏览器与 sleep，理由与 §4.3 末尾那段一致。

实施中确认的运行环境事实（不是代码问题，但会影响验收）：

- **Boss 的安全验证会打断扫描，症状是「卡片没抓到详情」而不是报错。** 本轮 run 55 → 57 连续三次
  导航后，Boss 返回 `zhipin.com/web/passport/zp/verify.html`，页面上写「您的账号可能存在异常访问行为，
  完成验证后即可正常使用」。run 57 的后 22 张卡全部记「第 N 张卡片没抓到详情，跳过」，
  组合仍然按「已跑完」收尾。因此验收 4 与插空巡检都改用不会触发真实投递的配置，
  验收 6 换到智联上做。已把这条写进 `docs/README.md` 的排错表。
- **智联对不存在的关键词不返回空结果**，而是给热门职位（本次 24 张卡），所以「用无命中关键词
  让扫描快结束」这条在智联上不成立。run 58 因此真的判定了 19 个岗位，全部落在「AI 判定不投」，
  投递 0。

本次未取证的验收项，如实记录：

- **5.2 第 8 条（两个平台各配一种组合同时跑，互不影响）**：Boss 当场被安全验证拦住，无法同时起两条
  真实 Run。可依据的仍是 2b 的结构（`RunServices.of(platform)` 每平台一份实例、配置与浏览器按平台取）
  与 P1 的取证（同一时刻两个实例并存）。
- **时段到点优先于「未启用值守」**：需要真实 Run 跑到时段结束，本轮时段临时改为全天，未构造该场景。
  代码里 `dutyEnded` 的判定在值守判定之前，位置在 §3.3 的三个既有判定之内。

**P3 实施记录（2026-11-01，真实服务 + 真实库 + bsk 观测控制台）**

落点：

| 文件 | 实际改动 |
|---|---|
| `console/pages/config.vue` | 平台配置 tab 顶部改成「平台启用 / 扫描 / 值守」一组三枚开关，状态字（开 / 关）跟在标签后；`switchHint` 拆成 `enabledHint` / `scanHint` / `watchHint`；`defaultPlatformConfig()` 补两列默认 `true`；`off-note` 按四种状态各一句（停用 / 双关 / 仅值守 / 仅扫描），同时只出现一条；「搜索条件」卡改成 `fieldset` 并 `:disabled="scanOff"`（整组原生禁用，键盘也进不去） |
| `console/pages/config.vue` | 「配额与节奏」卡**不整卡禁用**：卡内前五项属扫描（配额、评估上限、动作间隔、滚动与时长上限），后两项属值守（未读上限、巡检间隔）。只禁用前五项并加一行说明，理由写在该卡注释里 |
| `console/pages/index.vue` | 平台名后加「仅值守 / 仅扫描」徽标（两个都关时用错误色标「扫描与值守都关」，正常存不进库）；未启用扫描的「今日主动投递」「今日评估」显示 `—` 并带 `title` 说明原因；`combinationTotal` 为 0 时组合进度显示 `—`（原来会用默认 index 算出「1」） |
| `console/pages/task.vue` | 页头加启动前提示（停用 / 双关 / 仅值守 / 仅扫描四种，只在没有进行中 Run 时出）；`stopReasonText` 补 `SCAN_DISABLED`；`describe()` 的 `run.standby` 在 `SCAN_DISABLED` 下换成「未启用扫描，进入值守待命」（原句假定扫描发生过）；待命横幅的标题与页脚按扫描开关分支；页头副标题由「扫描可以停，值守不会跟着停」改为「扫描停了值守照常，除非值守开关也关着」 |
| `console/composables/usePlatform.ts` | `PlatformDesc` 补 `scanEnabled` / `watchEnabled` 两个字段 |
| `console/composables/useRunStream.ts` | **补订阅 `run.standby` 与 `run.rescan`**。原来 `describe()` 写了这两个事件的分支，但 `EVENT_TYPES` 白名单里没有它们，事件从未到达前端：界面上的「扫描结束，转入值守待命」一直是死代码。不补这一处，§3.9 要求的 `SCAN_DISABLED` 文案无从显示 |

与 §3.8 的一处偏离：「配额与节奏」不整卡置灰。规格写的是「只值守时把搜索条件、配额与节奏两张卡置灰并注明『未启用扫描，这些字段不生效』」，但那张卡里混着两个值守侧字段（未读列表扫描上限、值守巡检间隔），整卡禁用会让用户以为巡检节奏也改不了，而它照常生效。实现改为只禁用扫描侧五项，说明文字逐项点名哪几项不生效、哪两项照常。

取证（`java -jar server/target/chat-job.jar --server.port=8099`，库 `data/chat-job.db`，Flyway 仍为 v14；所有配置改动都是「GET 整份 → 改字段 → PUT 整份」，结束时 PUT 回原值并逐字段核对一致。截图在 `build/verify-p3/`，不入库）：

页面（bsk 观测主浏览器实例里的 `127.0.0.1:8099` 标签页）：

- **配置页四种状态**：全开时无提示条；关值守出现「只扫描：…组合扫完后任务直接结束…」；再关扫描出现红色「两个都关时保存会被拒绝。要整体停用…」，「搜索条件」整卡灰化、「配额与节奏」只灰前五项；
- **双关提交**：点「保存平台配置」→ 后端 400，toast 显示「「BOSS 直聘」的扫描与值守不能同时关闭：那样它启动后什么都不做。要整体停用它，请用平台开关」（前端只做就地说明，不复述这句，避免两处措辞漂移）；
- **未保存改动**：改动未保存时点别的 tab，弹 confirm「有未保存的平台配置改动，放弃并切换？」，取消后停在原 tab、改动仍在；整轮验证结束后 BOSS 与智联的配置与验证前逐字段一致（页面上改过开关但都没保存）；
- **概览**：智联设成只值守后平台名旁出现「仅值守」，主动投递与今日评估显示 `—`、组合显示 `—`；BOSS 全开时不显示徽标；
- **任务页**：只扫描时页头提示「该平台未启用值守：扫描照常，扫完组合即结束」；只值守时提示「该平台未启用扫描：启动后不加载岗位，直接进入值守」，启动后横幅标题「值守待命：未启用扫描，只处理未读消息」、页脚「这一轮不会恢复扫描：该平台的扫描开关是关闭的」；
- **实时事件**：只在页面点「启动」的那一轮（run 62）里抓到 `04:07:25 未启用扫描，进入值守待命：主动投递 0 / 评估 19` —— 这一条同时证明了白名单补订阅真的生效。

运行（智联平台；BOSS 处于平台侧安全验证，未启动）：

| run | 配置 | 结果 | 日志 / 库 |
|---|---|---|---|
| 60 | 只扫描（配额临时 0、滚动 20 轮、间隔 3 秒，避免真实投递） | `COMPLETED / SCAN_DONE`，用时 26 秒 | 8 行日志：「任务启动」→「该平台未启用值守：本轮只扫描，扫描结束后收尾，不进入值守待命」→「开始组合 #0 福州 / java」→「组合 #0 加载 218 张卡片（11 轮，确认到底）」→「今日主动投递已达配额 0（0），中止扫描」→「COMPLETED / SCAN_DONE / …该平台未启用值守，任务结束」；**没有任何待命或巡检日志**；`combination_run` 1 行 `SKIPPED`（cards 218 / evaluated 0 / delivered 0） |
| 61 | 只值守（巡检间隔临时 600 秒） | `STANDBY` → 手动停止 `CANCELLED` | 「未启用扫描，本次只值守：每 10 分钟检查一次未读消息」→「值守待命第 1 轮：会话 0，回复 0」；`combination_run` 0 行；终止原因 `CANCELLED`（用户停止，不是 `WATCH_DISABLED`） |
| 62 | 只值守，由页面点「启动」 | `STANDBY` | 用于验证 SSE，见上面「实时事件」 |

其它：

- 后端单测 **273 例通过**（1 例按注解跳过），P3 未改后端；
- 前端构建：`nuxt generate` 报过一次 `EPERM ... .output/public/index.html`，但产物已生成且页面是新版（构建后核对过 bundle 里的新文案）。原因未深究，未复现第二次；
- `console/dist` 是指向 `.output/public` 的软链，重新构建后不需要额外拷贝。

P3 实施中发现、本轮没改的两处（都超出控制台范围）：

1. **组合中止日志在只扫描场景下不成立。** `executeCombinations` 在组合因配额中止时写「剩余卡片留待回扫时继续」，而未启用值守的 Run 随即结束、不会回扫（run 60 的日志里这一句就在结束行上面一行）。文案在 `RunService` 里，改它属于后端日志口径，本轮未动。
2. **历史 Run 的「组合」会显示成 `2 / 1`。** `combination_index` 存的是当前组合 seq，`combinationTotal` 是该 Run 的组合行数，`index` 可能等于 `total`（run 55–58 都如此）。P3 只处理了 `total = 0`（未启用扫描）这一种，`index >= total` 未动，任务页的「断点 · 组合 N」同样如此。

### 4.3 测试

沿用既有口径：**不用 mock 框架，hand-written fakes**。

| 用例 | 断言 |
|---|---|
| `StandbyGateTest.扫描关闭_跨日也不回扫` | `WATCH`（`scanDay=null` 也不得返回 `SCAN`） |
| `StandbyGateTest.扫描关闭_配额被调高也不回扫` | `WATCH` |
| `StandbyGateTest.扫描关闭_时段外仍挂起` | `PARK`（第 1 条优先于第 2 条） |
| `StandbyGateTest` 既有 6 例 | 尾部补 `scanEnabled=true`，结论不变 |
| `PlatformSwitchesTest.两个都关_拒绝并指向平台开关` | 抛 `IllegalArgumentException`，文案含平台名与「平台开关」 |
| `PlatformSwitchesTest.只关一个_放行` | 不抛 |
| `ConfigStoreTest.新平台默认启用扫描与值守` | 两列均为 1 |

**不写单测的部分，如实说明**：`runLoop` / `standbyLoop` / `processCards` 是带 `Thread.sleep` 与真实浏览器调用的循环，用 fake 驱动只能验证「有没有调 sweep」，验证不了「关掉扫描后还会不会 openSearch」。这一段靠 P1/P2 的手工验证清单，并写进 5.2。不为覆盖率写假绿测试。

## 5. 风险与验收

### 5.1 风险 Top 5

| # | 风险 | 触发条件 | 缓解 |
|---|---|---|---|
| R1 | 关掉扫描后仍在导航搜索页 | 只在卡片边界判、忘了组合入口；或 `enterStandby` 前已 `openSearch` | 两个判定点成对写（3.5）；P1 验证时看日志里有没有「开始组合」，不是看有没有投出去 |
| R2 | 只值守的 Run 被跨日判定带回扫描 | `decide` 的第 3 条在 `scanDay == null` 时返回 `SCAN` | `decide` 第 2 条独立成一条（3.4）；单测直接覆盖 `scanDay=null` |
| R3 | 关掉值守后残留一次巡检 | 只改了 `standbyLoop` 顶部，没改睡眠检查 | 三个判定点一起改（3.6）；验收里数「关掉之后还有没有新的巡检日志」 |
| R4 | 旧控制台保存配置把开关重置 | PUT 用默认值而不是「保持原值」 | 与 `unreadScanLimit` 同口径；P0 用手工 PUT 少传两个字段验证一次 |
| R5 | 只值守平台的「今日主动投递 0」被读成投递失败 | 概览卡只报数字 | 徽标 + 显示 `—`（3.8）；日报里按「今日主动投递」下判断前先看徽标 |

### 5.2 验收口径

全部可手工复现，不依赖「看起来对」：

1. Boss 扫描关、值守开，城市关键词清空 → 能启动；日志无「开始组合」「开始扫描」，状态 `STANDBY`，横幅写「未启用扫描，本次只值守」。
2. 同上环境给 Boss 的某个会话发一条消息 → 在巡检间隔内被回复，回复日志与扫描期格式一致。
3. 同上环境把 `scan_day` 手工改成昨天 → 下一轮仍是 `WATCH`，没有任何搜索页导航。
4. 智联扫描开、值守关 → 扫完组合后状态 `COMPLETED`、`terminal_reason=SCAN_DONE`，日志最后一行说明「未启用值守」；此后不再有巡检日志。
5. 运行中把某平台的扫描关掉 → 当前这张卡处理完后停止导航（日志不再出现新的「开始组合」与「读取第 N 张卡」），状态转 `STANDBY`，值守继续。
6. 运行中把某平台的值守关掉（此时扫描已停）→ 一秒内不再有巡检日志，Run 结束于 `COMPLETED / WATCH_DISABLED`。
7. 两个开关都关提交 → 400，文案点名平台并指向平台开关；只关一个提交 → 保存成功。
8. 两个平台各配一种组合同时运行 → 任一平台的开关改动不影响另一个平台的日志与状态。
9. 演练模式 + 扫描关 → `COMPLETED / SCAN_DISABLED`，不进待命。
10. 只值守的 Run 在时段外启动 → `WAITING_DUTY`；到点唤醒后直接进 `STANDBY`，不扫描。

### 5.3 未闭环的事实

- 关闭值守后平台侧未读标记持续累积，未读数增长是否触发平台限流或影响后续主动投递，没有数据。
- 只值守的 Run 长时间只做巡检，浏览器整夜常开；跨夜内存占用仍无实测（ADR-0002 已记同一缺口）。
- 只值守模式下 `combination_run` 全为空，按组合统计的报表需要先判断该 Run 是否扫过，目前没有报表依赖这一点，但也没有防呆。
- 「只开扫描」每天需要人手动点启动。这是本次明确接受的代价，但如果实际使用中每天都要点，说明这里需要一个定时唤醒，而不是把待命循环改回来。

## 6. 已确认的决策（2026-11-01）

| # | 问题 | 结论 |
|---|---|---|
| Q1 | 「不主动投递」指关掉扫描还是扫了不投 | 关掉整个扫描（不加载列表、不判定、不投递） |
| Q2 | 两个独立布尔还是一个三态枚举 | 两个独立布尔；两列都关时拒绝保存 |
| Q3 | 只开扫描时组合扫完怎么办 | 直接结束，不进待命 |
| Q4 | 开关什么时候生效 | 即刻生效 |
| Q5 | 只开扫描的终态码 | `COMPLETED` + `terminal_reason=SCAN_DONE`，不用 `CANCELLED` |
| Q6 | 即刻生效的边界 | 扫描在卡片边界；值守在睡眠每秒检查；扫描中插空巡检直接跳过 |
| Q7 | 只开值守的启动态 | 仍用 `STANDBY`，新增中止原因 `SCAN_DISABLED`，不新增状态 |
| Q8 | 只值守时搜索条件为空是否允许启动 | 允许。该校验只为扫描准备 |
| Q9 | 与 ADR-0002 的关系 | 不冲突：ADR-0002 管「一次扫描结束后要不要继续值守」，本次管「这次扫描与这次值守要不要发生」 |
