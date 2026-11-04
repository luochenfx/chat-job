# 扫描段的判据先抽出来，循环后搬

## Status

accepted（2026-11-04，架构审查「重构顺序」第 3 步）。分四次落地：3a 判据（`com.chatjob.scan`：
`ScanStop`、`ScanBoundary`），3b 循环（`orchestrator.ScanSegment` + `ScanHost` + `JobProcessor`），
3c 待命段（`orchestrator.StandbySegment` + `RunControl`），3d 单卡处置（`orchestrator.JobDisposition`）。
守卫：`ScanBoundaryTest`、`ScanBoundarySingleSiteTest`、`ScanSegmentTest`、`StandbyGateTest`、
`StandbySegmentTest`、`JobDispositionTest`。

## Context

`RunService` 在本步之前是 1596 行、52 个方法、14 个构造依赖，扫描段与待命段、生命周期守卫、
SSE 出口全在一个类里。其中扫描段最混杂：分页、AI 判定、投递、配额、时间上限混在同一个循环体里。

判据的位置尤其成问题：

- 「扫描开关 / 投递配额 / 评估上限 / 组合时间上限」四条判据写在 `processCards` 的循环体里。
  那个循环要开浏览器、要 sleep、要写库，所以单测覆盖为 0 —— 「配额与上限同时到顶先记哪一个」
  「刚好用满时间上限算不算超时」这两个问题只能靠读代码回答。
- 同类判据散在别处：`platformScanEnabled` 读了 3 处（启动时一次、组合入口、卡片边界），
  `stopRequested || dutyEnded` 作为复合条件出现 3 处。加一种中止原因要改 4 个位置。

架构审查给这一步写的验收条件是「`RunService` 降到 800 行以下」+「fake adapter 驱动 4 个用例」。
两个都不成立：

1. 扫描段可搬的只有约 480 行（`executeCombinations` 101 + `processCards` 115 + `processOneJob` 111
   + `publishUnread` 20 + `jobEvent` 14 + `ensureScanDay` 27 + `runLoop` 的扫描分支约 70）。
   只抽一个模块，`RunService` 只能降到约 1116；要摸到 800 还得同时抽走待命状态机与生命周期，
   那是另外两步。
2. 它列的 4 个用例（配额到 / 上限到 / 超时 / 关闭扫描）全部发生在 `fetchDetail` 之前，
   用不到 adapter。真正需要测试替身的是「断点续扫」「每 5 张卡插空巡检」「关掉扫描后不再导航」，
   这三条它没列。

## Decision

1. **扫描段独立成模块，判据先于循环。** 本步只做「判据搬出去」，循环与调用顺序不动。
   理由是失败模式可分：判据错了在单测里立刻可见，搬循环错了才是序列问题，两者混在一次提交里
   会互相掩盖。

2. **`ScanStop` 从 `StandbyGate` 搬到 `com.chatjob.scan`，`StandbyGate` 反过来依赖它。**
   这不是改名：3b 之后 `orchestrator` 要依赖 `scan.ScanSegment`，若枚举留在 `orchestrator`，
   `scan` 反过来依赖 `orchestrator` 就是包级环。方向是「扫描段在上游，待命是它之后的状态」。

3. **`ScanBoundary.atCard` 是静态纯函数，判定顺序即优先级**：
   扫描开关 → 配额 → 评估上限 → 时间上限。不读时钟、不碰配置、不写库，全部参数由调用方量好传进来，
   与 `StandbyGate` 同一模式。配置缺省值（配额 30、上限 150）**不进来** —— 待命与收尾文案读同一份
   缺省，抄进来就是第二份真相。

4. **日志留在调用点，四个原因分开写。** 原因决定界面上的下一步（继续值守 / 留待回扫 / 不再导航），
   合并成一句会把「已经停了」读成「还会继续」。判据与文案分开，判据才能被直接测。

5. **中断编码本步不改。** `null` 仍表示「没有中止原因」，用户停止与时段到点仍靠运行标志区分。
   原计划在本步把 `null` 换成 `INTERRUPTED`，实现时发现不成立：`processCards` 的 `null` 同时承担
   「跑完最后一张」与「被打断」两种含义，只换常量而不改控制面，等于把同一份隐式约定换个写法。
   等 3b 把控制面收成一个接口时再一起改。

6. **验收条件重定标。** 「降到 800 行以下」作废，改为结构性断言：`ScanBoundarySingleSiteTest` 钉住
   三个比较表达式只出现在 `scan` 包内，并且 `ScanBoundary` 确实带着它们（防止守卫自己腐烂）。
   第 0 步的 `run_log` 基线必须在第 2 步之后重取 —— 第 2 步改变了日志内容，旧基线必然对不上，
   而一条必然失败的断言会被放宽或删掉。

7. **组合入口的扫描开关判定不并入 `ScanBoundary`。** 它守的是「不再导航」，卡片边界守的是
   「不再投递」，合成一个函数只是把两个不同时机塞进一个签名。合并成一个函数的冲动来自
   「同一概念一处」的口号，但这两处本来就不是同一次判断。

## 3b 实施补记（2026-11-04 同日）

循环搬出去时，有三处按当时的事实改了做法：

1. **`ScanSegment` 落在 `com.chatjob.orchestrator`，不在 `scan` 包。** 它要用 `RunStore` 与
   `RunStatus`（都在 orchestrator）。放进 `scan` 就是 `scan` ↔ `orchestrator` 的包级环 ——
   与 Decision 2 搬 `ScanStop` 的理由自相矛盾。`scan` 因此保持纯判据（`ScanStop`、`ScanBoundary`），
   宿主接口与单卡接缝跟着循环走，住在 orchestrator。

2. **中断编码没改。** 原计划在 3b 把 `null` 换成 `INTERRUPTED`，实际没做：`cards()` 的 `null`
   同时表示「跑完最后一张」与「被打断」，而 `runLoop` 之后仍要靠运行标志区分停止与时段到点。
   只换常量、不改状态机，等于把同一份隐式约定换个写法。Open Question 1 继续挂着，等
   「Run 生命周期」那一步一起改。

3. **单卡处理抽成 `JobProcessor` 接口。** 不抽的话 `ScanSegment` 要直接持有 `JobStore`、
   `AiJudge`、`BlacklistMatcher` —— 那三样要真库与真网络，扫描段的循环就只能等整机跑起来才测得了。
   接口只有一个方法，`RunService` 用 `this::processOneJob` 实现，实现体一行没动。

## 3c 实施补记（2026-11-04 同日，结掉 Open Question 3）

待命段搬出去时，有五处按当时的事实做了决定：

1. **`enterStandby` 与 `standbyLoop` 合成一个 `enter`。** 它们原来成对出现两次（只值守的 Run
   启动时、扫描停下时），中间没有任何别的动作。合成之后「先发横幅再进循环」不再是一条要靠
   调用方记得的约定，runLoop 里少了两处配对。

2. **宿主拆成 `RunControl` 与 `ScanHost`。** 待命段只用六个方法（停止 / 暂停 / 动作 / 推送 /
   巡检载荷 / 告警），`interrupted` 与 `endIfOutOfDuty` 只有卡片边界用，而且都会把 `dutyEnded`
   置真 —— 待命段每轮自己用 `StandbyGate` 判时段，不该有第二条路径能改它。于是 `ScanHost
   extends RunControl`，多出来的两条留在子接口上。`notify` 是这次新加到 `RunControl` 的：
   待命段的横幅要发企业微信，而「带平台展示名的唯一通知出口」不能让第二个类自己拼一遍。

3. **`ensureScanDay` 跟着搬。** 它的两个调用点是扫描入口与回扫出口，后者在 `StandbySegment`
   里。三次调用同一个 no-op 无害，两处各抄一份复位逻辑就会分叉 —— 而分叉的表现是第二天
   一个岗位都不投。这是本模块唯一一处「不只是待命」的持有，理由写在该方法上。

4. **「扫描停下之后这一轮到哪一步」的四条判定留在 `runLoop`。** 停止 / 演练 / 到点 / 值守关
   里面有三个直接落终止态，而终止态的落法只有 `finish` 一处；把它们并进待命段，模块就要同时
   回答「结束了没有」与「待命怎么守」，还得认识 `RunStatus`。待命的定义前提是「值守仍在继续」，
   这四条问的正是「还继续吗」—— 它们发生在待命之外。

5. **`RunLogSingleExitTest` 的名单跟着搬，并补上 `ScanSegment`。** 名单是硬编码的类路径：
   待命段带走了原来那三十多条日志调用，不跟着加，守卫就从「管住两个重日志类」悄悄缩回一个，
   而且不会变红。守卫的 javadoc 里写明了这条。

搬完之后 `RunService` 从 1400 降到 **1105**，另加 `StandbySegment` 384 行、`RunControl` 43 行。
**仍然到不了 800**：剩下的是生命周期、上面第 4 条那四条判定，以及 `processOneJob`（单卡处置，
单独一步）。测试从 413 涨到 **419**：`StandbySegmentTest` 6 例覆盖「停止」「关值守」「配额调高回扫」
「时段外挂起」「未启用扫描的横幅」「组合超时的横幅」；`ScanSegmentTest` 的假宿主补了一个
`notify` 空实现（接口多了一个方法，编译强制）。

## 3d 实施补记（2026-11-04 同日，单卡处置）

3b 把 `processOneJob` 留在 `RunService` 里，只留了一个 `JobProcessor` 接缝。它是最该搬的一段：
七次早退串着四类副作用（落投递表、改两个计数、推 SSE、写日志），而「AI 判定失败记 FAILED 还是
SKIPPED」这个决定只能从行间注释读到 —— 记错会让该岗位被打上 24 小时冷却，一次模型故障就能让
整批岗位一天内不再尝试，且看起来像「AI 认为不合适」。做法与理由：

1. **`JobDisposition` 实现 `JobProcessor`。** 接缝不变，扫描段仍然只认识那一个接口；变的只是
   实现从 `this::processOneJob` 变成了一个每轮 Run 建一次的实例。

2. **`JobProcessor.process` 去掉 `run` 入参。** 实例已经绑定一条 Run，参数里再传一份，
   同一个事实就有两个来源，而其中一个会被忽略。

3. **`JobStore` 与 `AiJudge` 留在 `RunService`，但本类不再调用它们。** 本类是这一轮的装配点
   （待命段、单卡处置都在这里按 Run 建出来），依赖必须经过它；把它们删掉就得给 registry 换一套
   建实例的方式，为了省两个字段不值得。字段上有注释说明这一点，免得下一个人以为是漏删。

4. **顺序与文案一字未改。** 七次早退、`decidedBy` 的 `AI` / `TEMPLATE`、三条投递日志分支全部原样，
   搬的是位置不是行为。

5. **一个既有口径被钉进测试**：质量开关关闭且模板招呼语为空时，评估计数已经加过，但没有投递行。
   这是改造前就有的行为（下一张卡会重新评估），不是这次引入的；写进测试是为了让它有据可查，
   而不是某天被当成 bug 顺手改掉。

`RunService` 从 1105 降到 **972**，`JobDisposition` 192 行。测试从 419 涨到 **427**：
`JobDispositionTest` 8 例覆盖「黑名单」「活跃度下限」「冷却中」「AI 技术失败」「AI 判定不投」
「模板为空」「演练」「正常投递」。到这一步，「800 行以下」还差 172 行，剩下的是生命周期
（`start` / `stop` / `wake` / `recover` / `dutyStatus` / `staleAfterSeconds`）与 3c 补记第 4 条
那四条判定。

## Consequences

- **行数先涨后降。** 3a 之后 `RunService` 从 1596 涨到 1604（判据搬走后，调用点要量出四个参数、
  要按原因分四种写日志）。3b 之后是 **1401**，另有 `ScanSegment` 328 行、`ScanHost` 51 行、
  `JobProcessor` 29 行。**离架构审查写的「800 行以下」还差约 600** —— 那要把待命状态机与
  Run 生命周期也抽走，属于另外两步，不是这一步没做完。（待命段与单卡处置已在同日 3c / 3d 搬走，
  `RunService` 降到 972；剩下那 172 行是生命周期与「扫描之后」的那四条判定。）
- **每段扫描最多多一次 `evaluatedToday` 查询**：改造前它只在配额未到顶时读，现在两个计数都在
  调用前量好。它发生在「配额已到顶」那一张卡上，紧接着就 `return`。
- **判据的边界逐字保留**：`>=` 而不是 `>`，时间上限用严格大于（刚好用满不算超时），
  `limitSeconds <= 0` 表示不限时。这三条各有一条命名用例钉住，改动会让测试变红。
- **`RunService` 里 switch 的 `COMBOS_EXHAUSTED` 分支是不可达的**，写成 `throw` 而不是静默跳过：
  真发生了说明 `ScanBoundary` 的契约被改了，宁可当场炸也不要把「扫完了」记成「停了」。
  不可达性本身由 `ScanBoundaryTest.卡片边界永不返回组合耗尽` 在一个输入矩阵上验证。
- **三个前置在 3b 都落地了**：`ScanHost` 一个接口、同时服务待命段（`standbyLoop` 的 3 处裸读
  改走它）；`FakePlatformAdapter` 提到 `com.chatjob.testing` 两个测试类共用；`run_log` 基线用
  3a 的五轮真实演练重取。
- **真实演练的对照证据**：`run 94`（3a 代码）与 `run 100`（3b 代码）用同一配置、同一组合跑演练，
  `run_log` 序列逐行一致（都停在 `COMBINATION_TIMEOUT`，都打印「已处理 N/139 张卡，
  剩余 M 张留待下次扫描继续」）。差别只有卡片数 19 对 20（时序）与一行偶发的
  「多出一个标签页」对账提示。两次都没有发送任何消息、没有清未读红点（值守开关全程关闭）。
- **测试从 381 涨到 398**：`ScanSegmentTest` 5 例覆盖「配额在第一张卡前停」「关扫描不导航」
  「断点从上次那张继续」「组合跑完记 DONE」「人工停止不再抓下一张」。

## Open Questions

| # | 问题 | 现状 |
|---|---|---|
| 1 | 人工停止与时段到点要不要进 `ScanStop` | 未做。要进就必须同时改控制面（3b），否则调用方拿到值之后仍要去读标志 |
| 2 | 组合入口的扫描开关判定最终住哪 | 3b 把 `executeCombinations` 搬进 `ScanSegment` 后它与卡片边界同文件；是否合并届时再定 |
| 3 | 待命状态机何时抽 | **已做（3c）**：`StandbySegment` + `RunControl`，见 3c 补记 |
| 4 | 真实一轮的 `run_log` 序列对照 | 本步未做（要起浏览器跑一整段）。它是 3b 之前最后一道确认 |
