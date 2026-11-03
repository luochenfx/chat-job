# 运行日志只有一个出口，库与文件是它的两份副本

## Status

accepted（2026-11-03，架构审查「入口与出口」第 2 步）。实现：`com.chatjob.runlog` 包；
守卫：`RunLogSingleExitTest`、`RunLogTest`、`UnreadWatcherTest`。

## Context

`run_log` 在改造前只有一个写点（`RunStore.log`），但「运行日志」有两个出口，而且这两个出口的覆盖面不同：

- `RunService.log(run, level, message)` 一次做两件事：插一行 `run_log`，再用 `log.info` 打一行文件日志。
  平台前缀 `[平台] [run N]` 只加在文件那一侧。
- `UnreadWatcher` 里的 41 条日志**只**调 slf4j：一条都不进 `run_log`，也**没有平台前缀**。
  后果是两件具体的事：控制台的日志面板读的是 `run_log`，所以「值守跑了但什么都没发生」在界面上
  无从归因；两个平台并行时，文件里那些没有前缀的行分不清是哪一边在回消息。
- `RunService` 里还有两处带堆栈的裸调用（任务失败、组合失败），同样只进文件。

另外两个已存在的偏差借这次一并处理：

1. `RunService.log` 无论 `INFO` 还是 `WARN` 都调 `log.info`，于是文件里全是 `INFO` ——
   按级别过滤时 WARN 会消失，而这恰好是排查时最先用到的一步。
2. 组合失败那一处写了两次：`log(run, "WARN", …)` 与 `log.warn("组合 {} 失败", seq, e)`，
   库里一行、文件里两行，且两行的措辞不同。

## Decision

1. **唯一出口是 `RunLog`，由 `RunLogs.of(run)` 创建并绑定一条 Run。**
   接口方法是 `info` / `warn` / `error` / `error(msg, cause)` / `warn(msg, cause)` / `log(level, msg)`；
   `log` 内部按级别分派到前三者。`log(level, …)` 存在的唯一理由是「级别由运行结果决定」：
   Run 收尾日志按终态选 INFO 或 WARN，没有它就要写三条几乎一样的分支。

2. **`RunLogs.of` 每次新建，不缓存、不做 `ThreadLocal`。**
   它只持有 runId、平台与两个无状态依赖。反过来做成字段就得回答「现在跑的是哪条 Run」，
   而在 `RunService.wakeIfWaiting` 里，手里的 Run 与 worker 线程正在跑的那条**不是同一条** ——
   一个隐式的「当前 Run」在这个位置必然写错行。

3. **不提供「没有 Run 的日志」。** `run_log.run_id` 允许为空只是 V1 的历史形状；现在的每次写入都属于
   某条 Run（值守也只在 Run 内被调用）。`of(null)` 与「还没落库的 Run」直接抛
   `IllegalArgumentException`，不做 no-op 变体 —— no-op 会让「写不进去」变成静默行为。

4. **两条通道各自独立，日志永不进 SSE。** `RunLog` 只写 `run_log` 与文件；
   SSE 仍只推状态与计数（`SseHub` 的类注释）。`publish` 与日志没有共有语义，本步**不搬**
   `SseHub` 的任何东西，`RunService` 的 `publish(run, type, data)` 保持原样。

5. **进库的门槛是「用户回看时会不会疑惑」，不是「是不是一个动作」。**
   最初的设计是「有动作才写，扫了 0 条不写」，评审时这个口径被推翻：跳过（我方消息 / 已处理 /
   冷却期内 / 重试退避中）、AI 主动不回、AI 本轮配额用尽、以及待命期的轮次心跳，全部要留一行。
   代价是 `run_log` 变大（见 Consequences），换来的是「这条未读为什么没回」在界面上能直接读到。
   为此新增了四类原本不存在的行：AI 主动不回、AI 配额用尽、重试退避中、失败重试（`fail()` 统一写）。

6. **级别在文件里也对齐。** `INFO` 打 `log.info`，`WARN` 打 `log.warn`，`ERROR` 打 `log.error`
   （Decision 1 的第 1 条 Context 缺陷）。堆栈只进文件：`run_log` 是列表视图，一行文本放不下堆栈，
   要看堆栈的人本来就在翻文件。

7. **`RunService` 与 `UnreadWatcher` 不再持有 slf4j 的 `Logger`。** 两处的 `@Slf4j` 删除，
   连带堆栈的两处也改走 `runLog.error(msg, e)` / `runLog.warn(msg, e)`。这条约束由源码扫描守
   （`RunLogSingleExitTest`）：一旦有人加回 `@Slf4j` 或裸 `log.info(`，测试直接红。

8. **写库用 `JdbcTemplate` 而不是 mapper。** 一条固定列顺序的 INSERT，没有条件拼装；
   而走 `BaseMapper` 会让这个包为了造一个测试替身去实现整套接口 —— `UnreadTaskMapper` 的类注释里
   记着同一个理由。走 jdbc 之后这条路径的单测用真实 SQLite 验「到底写进去什么」。

## Consequences

- **`run_log` 的日增量上来了。** 待命期每轮一行（巡检间隔 120s → 一天约 720 行/平台），
  有未读时每个会话 3–5 行。控制台面板只保留最近 400 条（`console/pages/task.vue` 的 `slice(-400)`），
  长跑一天之后早期行要查库或翻文件。这是 Decision 5 的直接代价，接受它是因为「静默」的代价更高。
- **`RunStore.log` 已删除**，`RunStore` 只剩读（`logs(runId, afterId, limit)`）。
  这是「单一出口」在结构上的落实：想再写一条，只能拿 `RunLog`。
- **`RunStore.finish` 里那行 slf4j 还在**：`.awaitResume` 的暂停路径直接调 `runStore.finish(…PAUSED…)`，
  不经过 `RunService.finish`，所以那一行只进文件、没有 `run_log` 行。这一处本轮**没有动**
  （暂停不是值守动作，且它会牵出「暂停要不要进运行记录」这个独立问题）。
- **`UnreadWatcher.sweep` 多了一个参数**：`RunLog` 显式下传，而不是让 watcher 自己找「当前 Run」。
  本类仍是单例、仍然无状态；代价是 5 个私有方法各多一个参数。
- **测试口径变了**：`UnreadWatcherTest.每个进入回复流程的会话都留下可读日志` 原本挂 logback 的
  `ListAppender` 断言文件行，现在断言 `RunLog` 收到的内容 —— 因为用户看的那一份是库，不是文件。
- **顺手修掉的两个偏差**：WARN 在文件里也是 WARN；组合失败从「库 1 行 + 文件 2 行、措辞不同」
  合并成「库 1 行 + 文件 1 行、同一句话」。
- **源码守卫的口径**：`RunLogSingleExitTest` 只钉「`run_log` 的写只出现在 `runlog` 包内」与
  「两个日志密集类不再有 slf4j 字段/裸调用」。它不禁止别处用 slf4j 记进程日志 —— 那是另一件事。

## Open Questions

| # | 问题 | 现状 |
|---|---|---|
| 1 | 暂停（`PAUSED`）要不要也进运行日志 | 未做。它现在只有 `RunStore.finish` 的一行文件日志。要补的话先定「暂停是动作还是状态」，否则会与 `run.status` 的展示重复 |
| 2 | `SseHub.publishGlobal` 至今 0 调用 | 与本步无关。留着是因为全局事件（登录态、driver 状态）本来是它的位置，但目前没有生产者 |
| 3 | 演练（`DRY_RUN`）的行要不要带独立标记 | 未做。现在只能靠文案前缀「演练」辨认；`run_log` 没有 `mode` 列，加列要迁移 |
