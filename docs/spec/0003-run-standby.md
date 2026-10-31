# 值守期：扫描收工后值守继续 + 可见的运行态

## 1. 上下文锚定

### 1.1 这是什么

两条改动，共用一套状态机：

- **扫描可以收工，值守不能跟着收工。** 投递配额满、评估上限满、组合队列扫完——这三种是「今天不再找岗位」，不是「今天不再回消息」。现在是三种原因都直接把整个 Run 结束，AI 回复随之停摆。
- **运行中任何时候都要能看见「它现在在干什么」。** 等待（卡片间隔、待命巡检、时段外挂起）期间日志完全静默，界面无法区分「在等」和「已卡死」。

一句话：把 Run 从「一次扫描任务」改成「一段值守期」——里面有扫描和值守两条腿，扫描可以瘸，值守不许停；时段外不操作页面，但值守期不结束。

### 1.2 为什么要做

| 现象 | 实测 | 后果 |
|---|---|---|
| 配额在 14:00 用完，之后 HR 回消息没人接 | 三处 `finish()` 直接终结 Run（`RunService.java:255-261`、`:371-382`） | 用户当天下午开始收到的消息全部石沉大海，而这正是投递后转化率最高的时段 |
| 卡片间隔、待命、挂起三种等待期间一行日志都没有 | `pauseFor` 静默 sleep（`:708-718`）；`sweepLegacy` 只在 `threads > 0` 时打汇总（`UnreadWatcher.java:374-377`） | 界面上「日志不滚了」既可能是等 8 秒，也可能是线程死了，人只能反复点刷新猜 |
| 时段外启动 / 时段结束的语义被读成「程序停了」 | `duty.ended` → `COMPLETED / DUTY_END`（`:255-256`），`run.finished` 事件把「等待」渲染成「结束」 | 昨天点的启动，今天早上要重新点一次；用户以为工具只跑了一晚上 |

### 1.3 现状与边界（读代码确认，不是推断）

| 位置 | 现状 | 对本 spec 的含义 |
|---|---|---|
| `RunService.java:401` | `unreadWatcher.sweep(...)` 是**唯一**调用点，埋在 `processCards` 里，每 5 卡或 120s 一次 | 扫描一停，值守必然断。要延续值守，只需让「调用它的循环」活得更久，不需要新线程 |
| `PlaywrightExecutor` | 单线程串行执行所有页面操作 | 任何「另起一个值守线程/进程」的方案都会和扫描抢同一条线程，必须串行 |
| `BrowserSession` | `launchPersistentContext` + `keepAlive` 占位页，profile 有独占锁 | 第二个进程起不来；浏览器整段值守期内常开，关掉要重新加载 profile（登录态虽在 profile 里，但重开有崩溃恢复框风险） |
| `BossAdapter.listUnreadThreads`（`:661-698`） | 复用 `threadPage`，只有 URL 不在 `/web/geek/chat` 时才导航 | 单看这个方法**不能**保证看到新消息 |
| `BossAdapter.closeThread`（`:1020-1027`） | 每轮 `sweep` 的 `finally` 都关掉 `threadPage`（`UnreadWatcher.java:776-782`） | 下一轮必然新建页面 → 导航 → 整页重载。**「必须刷新才看得到新消息」这件事，现有代码是靠关页间接满足的**，不是靠刷新参数 |
| `daily_counter`（`RunStore.java:187-238`） | 按 `platform + day` 计数，跨自然日自动归零 | 「新的一天重新扫」不需要额外清理计数，只需要复位组合队列 |
| `uk_run_running`（`V11`） | 部分唯一索引，状态 ∈ RUNNING/PAUSED/WAITING_DUTY | 新增状态必须进这个集合，否则同一平台能出现两个持有者抢一个浏览器档案 |
| `job_posting.decide` → `incrementEvaluated`（`RunService.java:447-455`） | 先判重跳过、后计评估数 | 跨日重扫不会把已投过的岗位再算一次评估，成本只是重新打开搜索页 |

### 1.4 术语

以 `CONTEXT.md` 为准，本次新增/修订的五个词条：**值守期**（点一次启动到手动停止）、**扫描**、**评估上限**、**值守待命**、**值守时段**；**投递配额** 的定义从「终止当日任务」改为「中止当日扫描」。

> 代码里这段运行叫 `Run`，术语表里叫「值守期」。本 spec 保留 `Run`、`run` 表名不改——改名会牵动迁移脚本、接口路径与全部历史数据，收益只有命名一致性。

## 2. 约束与目标

### 2.1 硬约束

1. **一个平台同一时刻只有一个值守期。** 沿用 `uk_run_running`，新增状态一并纳入；内存标志双保险不变。
2. **一个浏览器档案只有一个持有者。** 值守不得另起进程/独立线程/第二个 context；待命巡检必须复用当前 `PlatformAdapter` 与同一个串行执行器。
3. **配额、评估上限、组合耗尽 = 中止扫描，不是结束值守期。** 三者的共同出口只有一个。
4. **故障与人为操作仍然全停**：熔断、登录失效、driver 不可用、未捕获异常、用户暂停——不降级为待命。分类只有一种分法：计划内 vs 故障/人为。
5. **时段外不操作页面**：不导航、不刷新、不点开会话、不发消息、不花 AI 调用。但值守期不结束，Run 进入挂起等待。
6. **演练模式不进入待命**：演练跑完组合即结束（它会真点开会话清红点，整夜待命会把第二天的未读提前吃掉）。
7. **待命期不得消耗配额与评估计数**：不开搜索页、不抓详情、不调投递判定。
8. **每次等待前必须落下「当前动作」**，等待期间必须有定时日志：待命每轮一行，挂起每 5 分钟一行。
9. **待命期每轮重读配额与评估上限**：被调高、或进入新自然日，即恢复扫描。
10. **跨自然日恢复扫描必须先复位组合队列与断点**，否则第二天只会空转值守、一个都不投。
11. **巡检间隔可配**（默认 120 秒，范围 30–600），页面必须写明 120 的由来；实际等待加 ±10% 抖动，不做精确周期。
12. **不改值守的判定与出站校验**：spec 0002 的 `UnreadWatcher.process` 一行不动，本 spec 只改「什么时候调它」。

### 2.2 目标（完成的定义）

- 配额用完后的整段时间里，HR 发来的消息仍会被回复，且回复链路与扫描期完全一致（同样的四检、同样的重试、同样的日志）。
- 任何时刻打开界面，都能在 3 秒内看到：当前状态、当前动作、距上次动作多久。
- 时段结束后不再有任何页面操作，但值守期仍在，第二天窗口打开自动继续（不需要人再点启动）。
- 用户调大配额后，下一轮待命判定即自动回扫，不需要停止重来。

### 2.3 非目标（明确不做）

- **不做服务重启后自动接管。** 重启时正在扫描或待命的 Run 仍然标 `FAILED / SERVICE_RESTART`（`RunService.java:623-634` 不动）。理由：重启后浏览器是重新拉起的，页面可能停在验证码、半截导航或登录失效，自动接管的出错方式比「明确告诉你中断了」更糟。挂起态（`WAITING_DUTY`）跨重启保留不变——它一个页面都没碰过，恢复没有风险。
- **不做独立值守进程/服务。** 浏览器档案独占锁 + Playwright 单线程，做不了也不值得做。
- **不做平台消息推送。** 平台没有推流接口，只能轮询页面；本 spec 不改这一事实，只把轮询节奏做成配置。
- **不改投递判定、招呼语、出站四检、规则引擎。**
- **不新增通知渠道**（企业微信机器人已有）。

## 3. 方案设计

### 3.1 状态机

新增一个进行态 `STANDBY`（值守待命）。它和 `PAUSED` 一样属于 `isActive()`、不属于 `isTerminal()`。

```
                     ┌──────────── 窗口外点启动 ─────────────┐
                     ▼                                      │
 start() ──► CREATED ──► WAITING_DUTY ◄── 时段结束收起页面 ──┤
                     │        │                             │
                     │        └── 窗口打开（调度唤醒）──┐     │
                     ▼                                 ▼     │
                  RUNNING ── 配额/评估/组合耗尽 ──► STANDBY ──┘
                     │                                 │
                     │                        配额回升 / 新的一天
                     │                                 ▼
                     └────────────────────────────► RUNNING
   任何状态 ── 熔断 / 登录失效 / 异常 ──► 终止态（不降级）
   任何进行态 ── 用户停止 ──► CANCELLED
```

| 从 | 事件 | 到 | 落库 | 事件推送 |
|---|---|---|---|---|
| `CREATED` | 窗口外 | `WAITING_DUTY` | `message` | `run.waiting`（reason=`OUT_OF_WINDOW`） |
| `CREATED` | 窗口内且自检通过 | `RUNNING` | `started_at` | `run.started` |
| `RUNNING` | 配额 / 评估上限 / 组合耗尽 | `STANDBY` | `current_action` | `run.standby`（reason=`QUOTA`/`EVAL_LIMIT`/`COMBOS_DONE`） |
| `RUNNING` | 时段结束（卡片边界） | `WAITING_DUTY` | `message` | `run.waiting`（reason=`DUTY_END`） |
| `STANDBY` | 时段结束（巡检轮边界） | `WAITING_DUTY` | `message` | `run.waiting`（reason=`DUTY_END`） |
| `STANDBY` | 配额/评估上限回升、或 `scan_day` 变化 | `RUNNING` | `scan_day`（必要时复位组合） | `run.rescan`（reason=`QUOTA_RAISED`/`NEW_DAY`） |
| `WAITING_DUTY` | 调度发现窗口已开 | `RUNNING` | `scan_day`（必要时复位组合） | `run.awakened`（已有） |
| `STANDBY` / `RUNNING` | 用户暂停 | `PAUSED` | `terminal_reason=null` | — |
| `PAUSED` | 用户继续 | 回到暂停前的状态（内存记 `pausedFrom`） | `started_at` 不变 | — |
| 任何进行态 | 用户停止 | `CANCELLED` | `finished_at` | `run.finished` |

**`QUOTA_REACHED` 不再作为 `run.status` 写入**（枚举保留，历史数据要能读）；演练模式 `RunService.finishDryRun()` 的 QUOTA 分支仍以字符串 `QUOTA_REACHED` 作为 `terminal_reason`，状态本身是 `COMPLETED`。`COMPLETED` 只剩两条来源：演练模式跑完组合、`DUTY_END` 的旧数据。

### 3.2 扫描中止：三个原因，一个出口

现在三处各自 `finish()` 后 `return`（`RunService.java:371-382`、`:341-348` 的循环退出）。改成：

- `processCards` 命中配额/评估上限时，记下 `StopReason` 并返回，不再 `finish`。
- `executeCombinations` 返回 `StopReason`（配额 / 评估上限 / 组合耗尽）。
- `runLoop` 在 `executeCombinations` 之后：
  - 演练模式 → 保持今天的 `COMPLETED / 组合队列已跑完`；
  - 命中 `stopRequested` → `CANCELLED`；
  - 命中 `dutyEnded` → 挂起（`WAITING_DUTY`）；
  - 否则 → `STANDBY`，进 `standbyLoop`。

> 落地补充：演练模式的 `terminal_reason` 按中止原因分三支 —— 配额命中 → `QUOTA_REACHED`、评估上限命中 → `EVAL_LIMIT_REACHED`、组合耗尽 → `COMPLETED`（见 `RunService.finishDryRun()`）。上面「组合队列已跑完」只对应最后一支。

配额与评估上限的**检查位置不变**（仍在卡片边界，`RunService.java:371-382`）——那里之前的所有动作要么不发消息，要么已经发完，是唯一可靠的断点。

### 3.3 待命循环

```java
private void standbyLoop(RunEntity run, StopReason reason) {
    int round = 0;
    long enteredAt = System.currentTimeMillis();
    // 进入待命先立刻巡检一轮：上一次扫描期的巡检可能已经过去 120 秒
    while (true) {
        if (stopRequested) return;                    // 外层收尾为 CANCELLED
        awaitResume(run);                             // 暂停：停下等继续
        if (!windowOf(run).contains(LocalTime.now())) {  // 时段结束 → 收起页面
            park(run, "DUTY_END"); return;
        }
        StandbyGate.Action action = gate.decide(...);  // 纯函数，见 3.4
        if (action == SCAN) {                          // 配额回升 / 新的一天
            runStore.markRunning(run); publish(run, "run.rescan", ...);
            return;                                    // 回到 runLoop 的扫描段
        }
        round++;
        act(run, "待命巡检：读取消息列表");              // 落 current_action
        UnreadWatcher.SweepResult r = unreadWatcher.sweep(adapter, platform, collectMetrics, false);
        log(run, "INFO", "值守待命第 " + round + " 轮：会话 " + r.threads() + "，回复 " + r.replied()
                + "，未命中 " + r.unmatched() + (r.retried() > 0 ? "，重试 " + r.retried() : "")
                + "；下次巡检 " + nextAt + "（已待命 " + mins + " 分钟）");
        publishStatsIfChanged(run, r);
        sleepInterruptibly(jitter(watchInterval));      // 1 秒一跳，检查 stop / 时段结束 / 配置变化
    }
}
```

三个实现要点：

1. **睡眠必须可打断**。每秒检查 `stopRequested` 与时段结束，否则 22:00 到点要等最多 2 分钟才收起页面，而「时段外不操作页面」是硬约束。
2. **抖动**：`interval × [0.9, 1.1]` 随机，避免每 120 秒整刷新一次这种机器特征。
3. **待命不碰搜索页**：`sweep` 只用 `threadPage`，不调 `openSearch`；`listPage` 保持上次扫描停留的页面不动。

### 3.4 `StandbyGate`：待命要不要回扫的纯判定

抽成纯函数（不碰 IO、不碰时间源），单测直接喂参数：

```java
enum StopReason { QUOTA, EVAL_LIMIT, COMBOS_EXHAUSTED }
enum Action { SCAN, WATCH, PARK }

static Action decide(LocalTime now, DutyWindow window, StopReason reason,
                     String scanDay, String today,
                     int deliveredToday, int quota, int evaluatedToday, int evalLimit)
```

| 顺序 | 条件 | 结果 | 为什么排这个位置 |
|---|---|---|---|
| 1 | `!window.contains(now)` | `PARK` | 时段外一律不操作页面，优先级最高 |
| 2 | `scanDay != today` | `SCAN` | 新的一天：配额与评估都归零，必须重扫（Q8） |
| 3 | `reason == QUOTA && deliveredToday < quota` | `SCAN` | 用户把配额调大了（Q12） |
| 4 | `reason == EVAL_LIMIT && evaluatedToday < evalLimit` | `SCAN` | 同上 |
| 5 | 其余 | `WATCH` | 包括「组合已扫完」「配额被调小」——都不该再扫 |

把「昨天扫完的组合队列」原样再扫一遍会空转，所以第 2 条复位必须是**复位**而不是「继续」：

```java
/** 跨自然日恢复扫描前调用；同日重复调用是 no-op */
private void ensureScanDay(RunEntity run) {
    String today = RunStore.today();
    if (today.equals(run.getScanDay())) return;
    boolean first = run.getScanDay() == null;
    run.setScanDay(today);
    if (!first) {
        runStore.resetCombinations(run.getId());   // 状态回 PENDING，计数清零
        runStore.checkpoint(run, 0, 0, 0);
        log(run, "INFO", "进入新的一天（" + today + "），组合队列复位，从第 1 个组合重新扫描");
    }
    runStore.update(run);
}
```

调用点两个：`runLoop` 进入扫描段之前、`standbyLoop` 判定为 `SCAN` 之后。

### 3.5 时段结束：收起页面，值守期不结束

`checkDutyWindow`（`:661-684`）现在设置 `dutyEnded` 并让 `runLoop` 收工。改成设置 `parkRequested`，由外层统一走 `park(run, "DUTY_END")`：

```java
private void park(RunEntity run, String reason) {
    String message = "已到值守结束时间（" + window.describe() + "，现在 " + now + "），"
            + "停止操作页面；" + window.nextOpenHint(now) + "，值守期没有结束，不需要重新启动";
    runStore.markWaiting(run, message);
    act(run, "已挂起，等 " + window.describe() + " 的下一个窗口");
    log(run, "INFO", message);
    publish(run, "run.waiting", Map.of("reason", reason, "window", ..., "at", ..., "opensInMinutes", ...));
    notifier.notify("duty-end", "值守时段已结束，已挂起", message);
}
```

**告警文案必须改**：现在的企业微信通知是「值守时段已结束…本轮收工」，配合新的挂起语义会让人以为要重新点启动。

`wakeWaitingRuns`（`:588-614`）不动，但挂起期要多做一件事——**每 5 分钟打一行「仍在挂起」**：

```java
// 调度每 30 秒跑一次，这里只在距离上次心跳 ≥5 分钟时落一行
if (now - lastParkLogAt >= 5min) {
    log(waiting, "INFO", "仍挂起：现在 " + hhmm + "，值守时段 " + window.describe()
            + "，" + wait + " 后自动开工（值守期 #" + id + " 仍在）");
}
```

理由：挂起期页面一次都不碰，没有新信息，但**「没有新信息」不等于「没有输出」**——用户的原话是等待时的日志也要有。5 分钟是折中：一晚上 132 行，不刷屏，也不至于静默一整夜。

### 3.6 可观测性：当前动作 + 活跃度

三样东西，缺一样就还是分不清「在等」和「卡死」：

**(1) `run.current_action` / `run.last_activity_at`**（新增两列）

- `current_action`：一句人话，例 `等待 7 秒后处理第 13 张卡`、`待命巡检：读取消息列表`、`已挂起，等 09:00 的下一个窗口`、`AI 判定中：Java 后端 @ 某公司`。
- `last_activity_at`：每次动作、每次巡检轮开始、每次进入等待时刷新。
- 写入方式：`UPDATE run SET current_action=?, last_activity_at=?`，一次动作一行 UPDATE，**不落日志表**——卡片间隔若每张卡打两行日志，跑一天几百行噪音，而这些信息在界面上是「同一个格子的值」，不该变成事件流。

**(2) 日志**

| 场合 | 频次 | 内容 |
|---|---|---|
| 待命巡检 | 每轮（120s±10%） | 会话数、回复、重试、下次巡检时刻、已待命时长 |
| 挂起 | 每 5 分钟 | 现在几点、窗口、还有多久开工 |
| 扫描 | 每卡（已有） | 投递/跳过/丢弃结论不变；等待**不单独落行**，只更新 `current_action` |
| 状态迁移 | 每次 | `run.standby` / `run.rescan` / `run.waiting` 各一行 |

**(3) 界面**

顶栏固定三段：`状态标签` + `当前动作：…` + `运行中 · 12 秒前有动作`。

**陈旧判定**（「无响应」）只在 `RUNNING` / `STANDBY` 生效：

```
staleAfter = max(90 秒, 3 × 当前阶段期望间隔)
        RUNNING  → 期望间隔 = wait_time_seconds（默认 10s）→ 90s
        STANDBY  → 期望间隔 = watch_interval_seconds（默认 120s）→ 360s
```

超过 `staleAfter` 无活动 → 黄色 `⚠️ 无响应 N 分钟`。90 秒这个下限来自实测：一次投递含送达复核最多可合法占用 60 秒以上（`confirmByChatButton` 的轮询上限），阈值压到 60 会误报。

阈值由纯函数算，后端一处实现（`ActivityStaleness.staleAfterSeconds(status, waitSec, watchSec)`），随 `/runs/current` 以 `staleAfterSeconds` 下发，前端只做比较，不重复实现规则。

`PAUSED` / `WAITING_DUTY` 不做陈旧判定——它们本来就该安静，显示各自的专属文案。

### 3.7 配置与控制台

**新增配置项（平台配置 → 配额与节奏）**

| 项 | 字段 | 类型 | 默认 | 约束 |
|---|---|---|---|---|
| 值守巡检间隔（秒） | `watch_interval_seconds` | 数字 | 120 | 30–600，越界按最近边界收敛 |

字段下方说明必须写清「为什么是 120」（这是用户明确要求的）：

> 消息页不是推送的，每轮巡检都要重新加载一次消息列表才能看到新消息，所以这个数字同时是「AI 回复的最长延迟」和「页面被刷新的频率」。120 秒 ≈ 最坏两分钟内回上；再短（如 60 秒）等于每 8 小时按分钟级刷新几百次，不像人在用，反而更容易被风控盯上。实际间隔会在 ±10% 内随机浮动。

**「配额与节奏」卡片顶部那句 hint 也要改**：现在写「四个上限各管一件事」，加一个后变五个，且要说明「投递配额与评估上限只中止扫描，不影响值守」。

**界面状态文案**

| 状态 | 任务页 | 概览页 |
|---|---|---|
| `STANDBY` | 标签 `值守待命`（黄）；下方横幅：「扫描已结束（今日投递配额已用完），值守继续：每 2 分钟检查一次未读消息，到 22:00 停止操作页面并挂起」 | `值守待命` |
| `WAITING_DUTY` | 已有文案，补一句「值守期没有结束」 | `等待时段` |
| `PAUSED` | `已暂停`（补充：扫描与值守都停） | `已暂停` |

`STANDBY` 下按钮与 `RUNNING` 一致（暂停 / 停止），因为值守还在干活，点「停止」才是真正结束。

**一个必须顺手做的收口**：前端现在有三处各自硬编码活跃状态数组（`task.vue:172`、`index.vue:136`、`index.vue:154` 的 `statusText` 映射）。加一个状态要改三处，漏一处就是「统计显示运行中、按钮却显示启动」。这次抽成一个共享映射 `composables/useRunStatus.ts`（`active / label / tone / canPause`）。

### 3.8 数据

```sql
-- V12__run_standby.sql

-- 巡检间隔：120 秒的由来见配置页说明，不在库里解释
ALTER TABLE platform_config ADD COLUMN watch_interval_seconds INTEGER NOT NULL DEFAULT 120;

-- 「现在在干什么」与「上次动是什么时候」：界面靠这两列区分等待与卡死
ALTER TABLE run ADD COLUMN current_action   TEXT;
ALTER TABLE run ADD COLUMN last_activity_at TEXT;

-- 当前扫描所属自然日：跨日恢复扫描时靠它判断要不要复位组合队列
ALTER TABLE run ADD COLUMN scan_day        TEXT;

-- STANDBY 也必须进这条部分唯一索引：它是「持有浏览器的那一个」的状态之一
DROP INDEX IF EXISTS uk_run_running;
CREATE UNIQUE INDEX IF NOT EXISTS uk_run_running
    ON run(platform)
    WHERE status IN ('RUNNING', 'PAUSED', 'WAITING_DUTY', 'STANDBY');
```

老数据兼容：三列可空，`scan_day` 为空的行在首次扫描时补写当天（`ensureScanDay` 的 `first` 分支不触发复位）。

### 3.9 接口与事件

- `GET /api/runs/current` 的 `run` 对象自然多出 `currentAction` / `lastActivityAt` / `scanDay`（MyBatis 直接映射，无需改控制器）；另加 `staleAfterSeconds`，由后端算好（阈值规则只在后端一处实现，前端不算）。
- 新增 SSE 事件：`run.standby`（`reason`、`delivered`、`evaluated`、`quota`）、`run.rescan`（`reason`）。
- `run.waiting` 增加 `reason` 字段（`OUT_OF_WINDOW` / `DUTY_END`）；老客户端忽略该字段即可。
- `run.finished` 只在终止态发，语义不变。

## 4. 落地实施

### 4.1 文件清单

| 文件 | 改动 |
|---|---|
| `server/src/main/resources/db/migration/V12__run_standby.sql` | 新增（见 3.8） |
| `server/.../orchestrator/RunStatus.java` | 加 `STANDBY`；`isActive()` 纳入；注释说明 `QUOTA_REACHED` 不再产生 |
| `server/.../orchestrator/StandbyGate.java` | 新增：纯判定（3.4） |
| `server/.../orchestrator/ActivityStaleness.java` | 新增：陈旧阈值纯函数（3.6） |
| `server/.../orchestrator/RunService.java` | `runLoop` 收尾分流；新增 `standbyLoop` / `park` / `ensureScanDay` / `act`；`executeCombinations` 返回 `StopReason`；`pauseFor` 更新 `current_action`；`wakeWaitingRuns` 加 5 分钟心跳；`logDutyDeadline` 文案 |
| `server/.../orchestrator/RunStore.java` | `resetCombinations(runId)`、`touchActivity(runId, action)`、`markStatus(run, status)`（暂停恢复回 `STANDBY` 用）、`active()` 纳入 `STANDBY`、`orphaned()` 纳入 `STANDBY` |
| `server/.../store/entity/RunEntity.java` | `currentAction` / `lastActivityAt` / `scanDay` |
| `server/.../store/entity/PlatformConfigEntity.java` | `watchIntervalSeconds` |
| `server/.../store/ConfigStore.java` | `watchIntervalSeconds(platform)` + `clampWatchInterval` + 默认 120 / 上下限 30–600 |
| `server/.../web/ConfigController.java` | GET/PUT 平台配置带上新字段（PUT 用 `null 保持原值` 口径，与 `unreadScanLimit` 一致） |
| `server/.../web/RunController.java` | `current()` 增加 `staleAfterSeconds` |
| `console/pages/task.vue` | `STANDBY` 标签/横幅/按钮；顶栏「当前动作 + 距上次动作」；`describe()` 加两个新事件 |
| `console/pages/index.vue` | `statusText` 加 `STANDBY`；活跃数组改从共享映射取 |
| `console/composables/useRunStatus.ts` | 新增：状态 → 标签/色调/是否活跃/能否暂停，一处定义 |
| `console/pages/config.vue` | 巡检间隔字段 + 「为什么是 120」说明；「配额与节奏」hint 改写 |
| `server/src/test/.../orchestrator/StandbyGateTest.java` | 新增（6 个用例，见 4.3） |
| `server/src/test/.../orchestrator/ActivityStalenessTest.java` | 新增：陈旧阈值 `max(90s, 3×间隔)` 的纯函数 |
| `server/src/test/.../orchestrator/RunStatusTest.java` | 加 `STANDBY` 归属断言 |
| `docs/adr/0002-standby-instead-of-finish.md` | 新增（见第 6 章） |

### 4.2 分期（每步独立可验证）

| 期 | 内容 | 验证方式 |
|---|---|---|
| **P0 数据与状态** | `V12`、`RunStatus.STANDBY`、实体三列、`watch_interval_seconds` 全链路（Store→Controller→配置页）；`RunStore.active/orphaned` 纳入新状态 | 起服务看 Flyway 通过；配置页能改能存；`RunStatusTest` 绿 |
| **P1 状态机骨架** | `StandbyGate` + `StopReason`；三处收工点改为进入待命；`park` + `ensureScanDay`；`runLoop` 分流 | `StandbyGateTest` 绿；手工把配额设 1，跑完 1 个后看状态变 `STANDBY`、组合队列剩余项不再推进 |
| **P2 待命循环** | `standbyLoop`（巡检 + 抖动睡眠 + 可打断）；回扫；时段外挂起；挂起心跳 | 手工：配额 1 → 待命 → 改回 5 → 自动回扫；把时段结束设成 2 分钟后 → 到点挂起，日志 5 分钟一行 |
| **P3 可观测性** | `current_action` / `last_activity_at` 全部写入点；日志文案；SSE 两个新事件；前端状态、当前动作、陈旧告警 | 手工：界面上能看到「当前动作」在每个阶段变化；人为 `jstack` 卡住线程 100 秒，看界面报「无响应」 |
| **P4 文案与回归** | 通知文案、配置页说明、`CONTEXT.md` 校对；跑一次全量单测 + 一次演练模式全流程 | `mvn test` 全绿；演练跑完仍是 `COMPLETED`，不进待命 |

### 4.3 测试

沿用既有口径：**不用 mock 框架，hand-written fakes**（`UnreadWatcherTest` / `UnreadTaskStoreTest` 已有先例）。

| 用例 | 断言 |
|---|---|
| `StandbyGateTest.配额用完且在时段内_继续待命` | `WATCH` |
| `StandbyGateTest.配额被调高_回扫` | `SCAN` |
| `StandbyGateTest.评估上限被调高_回扫` | `SCAN` |
| `StandbyGateTest.组合已扫完_即使还有配额也不回扫` | `WATCH`（防同日空转死循环） |
| `StandbyGateTest.跨自然日_无论如何回扫` | `SCAN`（`scanDay != today` 优先于 `COMBOS_EXHAUSTED`） |
| `StandbyGateTest.时段外_挂起优先于一切` | `PARK`（即使配额刚被调高） |
| `RunStatusTest` | `STANDBY.isActive()==true`、`isTerminal()==false` |
| `ActivityTest.staleAfter` | `RUNNING` 默认 90s；`STANDBY` 360s；下限不被小间隔压破 |

**不写单测的部分，如实说明**：`standbyLoop` 是一个带 `Thread.sleep` 与真实浏览器调用的循环，用 fake 驱动它只能验证「它会不会调 sweep」，验证不了「它会不会在 22:00 收起页面」。这一段靠 P2/P3 的手工验证清单，并写进第 5 章的验收口径——不做「假绿」的测试。

## 5. 风险与验收

### 5.1 风险 Top 5

| # | 风险 | 触发条件 | 缓解 |
|---|---|---|---|
| R1 | 每 2 分钟整页刷新消息列表被判为机器行为 | 长时间待命 | 间隔 ±10% 抖动 + 每天只在时段内待命（默认 13 小时）；观察 2–3 天有无验证码，出现即把间隔调到 300 并实测。**这是本 spec 唯一无法在代码里消除的风险** |
| R2 | 浏览器整夜常开导致内存/连接累积 | 跨 8 小时以上 | 每轮巡检结束都关 `threadPage`（既有行为），常驻页只有 1 个；挂起期不做任何页面操��。仍需观察一次过夜的内存曲线（见 5.3） |
| R3 | 加状态漏改界面，出现「运行中但按钮显示启动」 | 前端三处硬编码 | 本次抽 `useRunStatus.ts` 收口；P4 逐页对照状态表 |
| R4 | 跨日复位漏做，第二天只值守不投递 | `scan_day` 未写入/未判断 | `ensureScanDay` 在回扫与唤醒两个入口都调；`StandbyGateTest.跨自然日` 用假日期兜住纯逻辑，剩下的是接线，靠 P2 手工跨日验证一次（改系统日期或临时把 `scan_day` 改成昨天） |
| R5 | 待命期频繁巡检把 AI 成本推高 | 有多个未命中会话 | 沿用 `ai_reply_max_per_sweep`（默认 5/轮）；**不新增预算机制**，但日志里每轮报出 AI 调用次数，让成本可见 |

### 5.2 验收口径

全部可手工复现，不依赖"看起来对"：

1. 配额设 1，跑一轮：投出 1 个后出现 `run.standby`，状态 `STANDBY`，横幅写清原因与下次巡检时间；此后给 HR 发一条消息，**能被自动回复**（这条回复的日志与扫描期格式完全一致）。
2. 待命期每 120s±10% 出现一行巡检日志；即使 0 未读也有这一行，且写「0 未读」。
3. 把配额 1 改回 5：下一轮判定后自动回扫，日志出现「配额已回升，恢复扫描」，状态回 `RUNNING`。
4. 把时段结束设成当前时间 +2 分钟：到点不再有任何页面操作，状态 `WAITING_DUTY`，日志每 5 分钟一行「仍挂起」，界面不出现「无响应」。
5. 把时段开始设成 +1 分钟并跨过该时刻：自动唤醒并**重新扫描**（日志出现「进入新的一天…组合队列复位」仅当确实跨日；同日唤醒则续扫）。
6. 演练模式跑完所有组合：`COMPLETED`，不进待命，日志没有「值守待命」字样。
7. 扫描中人为触发安全验证：`CIRCUIT_OPEN`，不进待命。
8. 待命期点暂停：状态 `PAUSED`，恢复后回到 `STANDBY`（不是 `RUNNING`）。

### 5.3 未闭环的事实

- 平台对「定时整页刷新消息列表」的容忍度未知。R1 只是把节奏调到接近人类，没有证据支撑「不会触发风控」。
- 浏览器跨夜常驻的内存与句柄曲线未测。挂起期不关浏览器的理由是避免重开时的崩溃恢复框，但这个取舍没有量化数据。
- `threadPage` 每轮关闭/新建的累计成本（CDP 连接、页面初始化）未测；如果发现待命期 CPU 偏高，第一步是把巡检改成「同一页面 reload」。

## 6. 最终审查

### 6.1 设计约束落点自检

| 约束 | 落点 |
|---|---|
| 一个值守期一个平台 | `uk_run_running` 加入 `STANDBY`（3.8）+ `start()` 既有校验 |
| 一个浏览器一个持有者 | 待命巡检复用 `adapter`，不新起线程（3.3） |
| 配额/评估/组合耗尽 ≠ 结束 | `executeCombinations` 返回 `StopReason`，`runLoop` 分流（3.2） |
| 故障与人为仍全停 | `runLoop` 的 `catch` 分支不动；`stopRequested` 优先于待命（3.1 表） |
| 时段外不操作页面 | `park` 先于 `sweep`；睡眠每秒检查时段（3.3、3.5） |
| 演练不进待命 | `runLoop` 里 `DRY_RUN` 分支保留 `COMPLETED`（3.2） |
| 待命不耗配额 | 待命循环里没有任何 `increment*` 调用（3.3） |
| 等待必须有日志 | 待命每轮一行 + 挂起每 5 分钟一行 + `current_action`（3.6） |
| 间隔可配且可解释 | `watch_interval_seconds` + 配置页说明（3.7） |

### 6.2 已知取舍

1. **不自动接管重启。** 代价：半夜重启后值守静默到人发现。换取的是「不会在页面状态不可信时自动发消息」。
2. **待命期仍受时段约束。** 22:00 后 HR 的消息要等次日上午。这一条是用户明确要求的硬边界，不是技术限制。
3. **`QUOTA_REACHED` 变成只在历史数据里存在的状态。** 保留枚举而不删，是为了不让老界面读历史行时报未知状态。
4. **卡片间隔不落日志**，只更新 `current_action`。取舍：日志干净 vs 逐步可追溯，选了前者，因为界面已经能回答「现在在干什么」。

### 6.3 已确认的决策（2026-10-30）

| # | 问题 | 结论 |
|---|---|---|
| Q1 | 待命挂在谁身上 | 同一条 Run 增加 `STANDBY`；不另起进程/定时器（浏览器独占 + 单线程执行器） |
| Q2 | 组合耗尽是否同等待遇 | 是，也进待命 |
| Q3 | 时段结束是否仍全停 | 是；但不结束值守期，改为挂起等待下一个窗口 |
| Q4 | 熔断/登录失效/异常/暂停 | 全停，不降级 |
| Q5 | 是否留「配额用完就收工」开关 | 不留；改为定时日志 |
| Q6/Q9 | 巡检间隔 | 默认 120 秒，可配 30–600，页面写明由来，±10% 抖动 |
| Q7 | 可观测性 | 日志 + 界面两层一起改 |
| Q8 | 跨日 | 重新扫：复位组合队列与断点 |
| Q10 | 演练是否待命 | 不进入 |
| Q11 | 服务重启 | **本次不考虑**，沿用「重启即中断」 |
| Q12 | 待命期配额被调高 | 自动回扫 |

### 6.4 仍待观察

- 待命期是否该在「连续 N 轮 0 未读」时自动把间隔调长（自适应降频）。本次不做，先拿真实数据：如果一晚上 200 轮全是 0 未读，说明默认间隔偏短，那时再谈自适应。
- 挂起期是否要在界面上显示「浏览器仍在运行」的显式指示。目前只在「系统」页有登录态/浏览器状态，任务页看不到。
