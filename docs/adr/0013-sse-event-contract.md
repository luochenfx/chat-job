# SSE 事件名只有一份清单，订阅与文案都从它派生

## Status

accepted（2026-11-04，架构审查候选 06）。实现：`console/composables/runEvents.ts`（新增）、
`console/composables/useRunStream.ts`、`console/pages/task.vue`。
守卫：`SseEventContractTest`。

## Context

一个事件名要跨三种地方写三遍：

| 清单 | 位置 | 改造前的数量 |
|---|---|---|
| 发出 | 后端 `publish(...)` 的字面量，散在 5 个类 | 11 |
| 订阅 | `useRunStream.ts` 的 `EVENT_TYPES` 数组 | 12 |
| 文案 | `task.vue` 的 `describe()` 分支 | 11 |

三者只在运行时才对得上，而且**三种不一致全部是静默的**：

- **漏订阅**：事件到了浏览器但没有监听器，页面上什么都不会发生。`run.standby` 与 `run.rescan`
  就是这样漏过一次 —— `describe()` 里文案早就写好了，界面上却一直不出现，只在断言上看是对的。
- **漏文案**：落到 `default` 分支，界面直接显示裸露的 `run.something`。
- **后端多发**：前端静默丢弃，没有任何反馈。

改造前的实际差集只有一处：`duty.ended` 在订阅清单里，但后端一次都没发过、`describe()` 也没有分支。
它是 spec 0003 早期的设计，后来事件改成 `run.finished` + `COMPLETED / DUTY_END`，白名单没跟着删。

## Decision

1. **前端只有一份清单**：`runEvents.ts` 里的 `TEXT` 对象，键是事件名、值是文案函数；
   `RUN_EVENT_TYPES = Object.keys(TEXT)`，`useRunStream` 订阅的就是它。
   于是「订阅了但没文案」「有文案但没订阅」在结构上不可能出现 —— 剩下的只有跨语言那一侧需要守。

2. **未知类型原样返回事件名**，不返回空串。界面上出现 `run.something` 就是「后端新增了事件、
   前端没接」的直接证据；空串只会让人以为这件事没发生。

3. **删掉 `duty.ended`**。spec 0003 是历史决策记录，按原样保留，不回改。

4. **不生成代码、不运行时下发、不在后端建常量类。**
   - 代码生成要引入构建期依赖去读 Java 源码，收益只是省一次手抄。
   - 运行时下发（一条 meta 事件或一个 `/api/events` 端点）把「前端知道有哪些类型」变成
     「连上之后才知道」，而 `addEventListener` 必须在连接前就注册，会绕一圈再回到原问题。
   - 后端常量类只能保证后端内部一致，跨语言那一侧照样要守，反而多一层间接。

5. **守卫是源码扫描**：`SseEventContractTest` 取后端所有 `publish(` 的字面量事件名，
   与前端 `TEXT` 的键做双向集合比较，差集直接指出是哪一边多了。

6. **守卫的边界要显式钉住**：扫描只认字面量，`publish(run, type, data)` 这类扫不出来。
   所以另有一条断言固定「用变量发事件的调用恰好 2 处」，且两处都是通道转发
   （`RunService.publish` 与 `RunHost.publish`，它们把字面量原样传下去）。
   新增第三处就会红 —— 否则「守卫看着在跑、其实覆盖不到」就是下一种静默失效。

## Consequences

- 新增一个事件必须前后端一起改，否则 `mvn test` 直接红。这条检查在 CI 里，不在运行时。
- 后端 11 处 `publish` 调用点一行未改，事件名、载荷字段、发送时机全部不变。
- 前端 `describe()` 从 47 行缩成 3 行转调；`stopReasonText` / `rescanReasonText` 一起搬进新模块。
- 守卫只保证「名字两边对得上」，不保证文案读起来对。前端没有测试框架，文案仍然靠人看。
- 反向验证做过两次：往 `TEXT` 注入 `'run.bogus'` → 第一条断言红并列出 `run.bogus`；
  往 `RunService.publish` 多注入一行 `sse.publish(run.getId(), type, data)` → 第二条断言红并给出
  `RunService.java:836/837/960` 三个位置。两次注入均已还原。

## Open Questions

1. **`SseHub.publishGlobal` 零调用点**（本次扫描时发现）。它是死方法，删还是留着备用，未决。
   留着的话，它发的事件 `runId` 为空，而前端只订阅 `/api/runs/{id}/events`，多半收不到。
2. **`modeText` 现在有三份**：`index.vue`、`task.vue`、`runEvents.ts`。后两者语义相同（`DRY_RUN`
   的界面说法），前者属于另一个页面的本地文案。本次只保证 `runEvents.ts` 自洽，没有跨页合并。
3. **文案无测试**。若将来给前端引入测试框架，`TEXT` 的渲染结果可以做成快照；
   在那之前，「文案改动是否影响别处」只能靠搜字符串。
