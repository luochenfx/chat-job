# chat-job MVP 需求与方案

> 状态：待评审 · 版本：v0.1 · 日期：2026-10-27
> 本文只锁定 MVP 边界内的事。边界外的想法一律进「不做」清单，不在这里讨论。

---

## 1. 上下文锚定

### 1.1 这是什么

一个跑在单台 Windows 机器上的求职投递自动化工具。它用真实 Chrome 在招聘平台上按「城市 × 关键词」组合扫描岗位，抓接口数据而非解析 DOM，用 AI 判断该不该投并生成招呼语，投递后在同一循环内轮询未读消息并按关键词策略回复，异常时通过企业微信群机器人告警。

### 1.2 为什么要做

人工在 BOSS 上筛岗、读 JD、想措辞、点沟通，一天投入 3–4 小时，其中 80% 是重复动作。真正有价值的判断（这个岗值不值得去）反而被挤压到只剩几秒钟。

### 1.3 现状与边界

- 全新项目，无存量代码。
- 单机单人自用：一个 Windows 账户、一个 BOSS 账号、一个 SQLite 文件。
- 平台侧无公开 API，全部依赖浏览器操作与接口抓包。
- 目标站点具备自动化指纹检测能力，识别后表现为页面无限刷新、登录态被打回匿名。

### 1.4 术语

以 `CONTEXT.md` 为准。本文出现的岗位、岗位卡片、会话、打招呼、投递、重复沟通、搜索组合、筛选参数、投递配额、投递判定、硬黑名单、偏好说明、HR 活跃度、值守，均按该文件的定义使用。

---

## 2. 约束与目标

### 2.1 硬约束

| # | 约束 | 原因 |
|---|---|---|
| C1 | 单机单人单账号，控制台只绑 `127.0.0.1` | 产品边界；避免任何多租户抽象 |
| C2 | 执行层内嵌在 Spring Boot JVM 内，不起第二个进程 | 单进程同库，省掉跨进程状态同步 |
| C3 | 浏览器必须是系统真实 Chrome（`channel: "chrome"`、有头、专用 profile 目录） | 降低被识别的概率；不复用日常浏览 profile |
| C4 | 不使用官方 Playwright driver，改用装配后的 patchright driver | ADR-0001 |
| C5 | 同一时刻只允许一个 Run 处于 RUNNING | 单 profile 独占，并发没有意义且放大风险。**此条为 MVP 单平台时代的全局约束**；多平台后收紧为「每个平台同一时刻一个 RUNNING」，`uk_run_running` 按 `platform` 建唯一索引，见 `spec/0003`、`spec/0004` |
| C6 | 任何异常一律 fail-closed：停任务，不绕过 | 绕过一次验证码的代价远大于当天少投 30 个岗 |
| C7 | 所有 Playwright 调用收敛到单一专用线程；监控类调用忙则丢弃、不排队 | Playwright Java 非线程安全，多线程操作同一 context 会随机爆 `Object doesn't exist` 与 `ERR_ABORTED` |
| C8 | 所有等待必须显式超时，且只等 `DOMCONTENTLOADED` | SPA + WebSocket 长连接下 `NETWORKIDLE` 可能永远不到 |

### 2.2 目标（MVP 完成的定义）

1. 配置好模型提供商、AI 提示词、平台搜索条件与黑名单后，点一次启动，能无人值守跑完一轮扫描并产生投递。
2. 投递结果（已投递 / 投递失败 / 需人工）在控制台可查，可重投。
3. 日投递达配额或评估数达上限时自动收工，并发出通知。（**已被 spec 0003 取代**：这两种上限只中止扫描，值守继续到时段结束。）
4. 掉登录、遇到验证码、driver 自检失败时立即停机并告警。
5. 断电/重启后能从断点续跑，不重复投递。

### 2.3 非目标（明确不做）

| 不做 | 说明 |
|---|---|
| 智联招聘的实现 | 只留 `PlatformAdapter` 接口位，不写任何智联代码 |
| 图片简历发送 | 投递动作列表化留 seam，本期只发招呼语 |
| 云部署 / 多账号 / 代理池 | 与 C1、C3 冲突 |
| 验证码识别 | 命中即熔断，交人工 |
| 自动回复的语义理解 | 值守只按关键词策略回复，不做 LLM 对话 |
| 移动端、账号体系、权限 | 自用工具 |

---

## 3. 方案设计

### 3.1 分层

```
┌──────────────────────────────────────────────┐
│ console (Nuxt 3 静态产物，由 Spring Boot 托管) │
└───────────────────┬──────────────────────────┘
                    │ REST 下行 / SSE 上行
┌───────────────────▼──────────────────────────┐
│ web        控制器：任务、配置、岗位、系统        │
├──────────────────────────────────────────────┤
│ orchestrator  Run 生命周期 / 组合队列 / 断点    │
│              投递配额 / 评估上限 / 熔断         │
├──────────────────────────────────────────────┤
│ evaluate     硬黑名单 → 活跃度 → AI 投递判定    │
├──────────────────────────────────────────────┤
│ platform      PlatformAdapter (Boss 实现)      │
├──────────────────────────────────────────────┤
│ driver       patchright driver 装配 / 上下文    │
│              登录态探测 / 未读值守              │
├──────────────────────────────────────────────┤
│ store        MyBatis-Plus + SQLite + 迁移       │
└──────────────────────────────────────────────┘
```

### 3.2 执行层

**driver 装配**（机制已在参考实现 get_jobs 中验证；结论见本节末尾「参考实现依据」）：

- 构建期任务：`npm install patchright-core@1.62.1 --prefix build/patchright-driver`，把 `node_modules/patchright-core` 拷到 `build/patchright-driver/package`，以 `package/cli.js` 存在作为就绪判据。
- 运行时注入：设置 JVM 系统属性 `playwright.cli.dir=<driverDir>`，环境变量 `PLAYWRIGHT_NODEJS_PATH=<node 绝对路径>`，再 `Playwright.create(new CreateOptions().setEnv(env))`。
- 未命中则**拒绝启动任务**（fail-closed），控制台系统页显示红色不可用。

**版本锁定**：`playwright-java 1.62.0` + `patchright-core 1.62.1`（已验证组合）。升级必须两者同时动并重打 driver；不得降到 1.51 —— `patchright-core 1.51.3` 的 `locator.count()` 存在缺陷。

**启动自检**：浏览器起来后打开一个本地自检页，断言 `navigator.webdriver === false` 且运行在 patched driver 上；任一失败即拒绝启动任务。

**浏览器参数**：`channel("chrome")` + `launchPersistentContext`（专用 `browser-data/<platform>/`，多平台后按平台分目录）+ `headless(false)` + `slowMo(50)` + `viewportSize(null)` + `chromiumSandbox(true)`，args 为 `--start-maximized`、`--test-type`、`--disable-session-crashed-bubble`、`--hide-crash-restore-bubble`、`--no-first-run`、`--no-default-browser-check`、`--no-proxy-server`、`--proxy-bypass-list=*`、`--disable-blink-features=AutomationControlled`。不设 UA，不加 `--no-sandbox`。

后四个稳定性参数不是反检测，是防止崩溃恢复弹窗挂死 CDP；同时启动前把 profile 的 `Preferences` 里 `exit_type` 置 `Normal`、`exited_cleanly` 置 `true`。另外两处必须记住：**启动自带的空白页不能复用**（必须新建页并关掉旧页，否则 evaluate/locator 会无限阻塞）；**落点不用首页**（受控标签页下首页 navigate 要 60s），直接进岗位列表页。

**登录态**：登录态由持久化 profile 维持，回写 Cookie 到 SQLite 作备份。探测信号优先用登录态 Cookie 集合（`bst` / `wt2` / `zp_at` / `geek_zp_token` 任一非空），其余「URL 落到登录页 ∪ 业务接口返回未登录」作为判据；DOM 探测只作兜底且必须带 5s 超时。启动前探一次，每完成一个搜索组合再探一次；连续 2 次命中才判定失效。

**线程与等待纪律**

| 规则 | 要求 |
|---|---|
| 线程 | 所有 Playwright 调用经单线程 executor 调度；嵌套调用就地执行；定时监控忙则丢本轮 |
| 加载等待 | 显式超时，只等 `DOMCONTENTLOADED` |
| 可见性探测 | 一律包 5s 超时，不用裸 `isVisible()` |
| 导航 | 45s 超时 + 重试 1 次 + 先判断「是否已到位」 |
| 假异常 | `Object doesn't exist` / `Execution context was destroyed` 视为可重试；滚动期累计 > 20 次放弃 |

**参考实现依据（原调研稿结论，已并入本文）**

执行层若干写法沿用参考项目 get_jobs 的已验证结论。driver 在构建期用 npm 把 `patchright-core@1.62.1` 装进 `build/patchright-driver` 并拷成 `package/`，以 `package/cli.js` 存在为就绪；运行时注入 JVM 属性 `playwright.cli.dir` 与环境变量 `PLAYWRIGHT_NODEJS_PATH` 后再 `Playwright.create(setEnv)`，未命中即拒绝启动（fail-closed）。Playwright Java 非线程安全，故全部调用收敛到单线程 executor、嵌套调用就地执行防自锁、监控忙则丢弃本轮；否则会随机报 `Object doesn't exist` 并使导航被 `ERR_ABORTED` 打断。等待须显式超时、只等 `DOMCONTENTLOADED`（SPA 长连接下 `NETWORKIDLE` 可能永不触发）、`isVisible` 一律包 5 秒超时、`navigate` 45 秒重试一次。搜索直接拼 URL，不模拟 UI；第一张卡片默认展开不发详情请求，须先点第二张再点回第一张并挂响应监听。

仍未实测：BOSS 今日沟通次数上限阈值、有间隔时触发安全验证的详情量（仅「无间隔 7 分钟 297 个」一个数据点）、搜索瀑布流真实深度；首次点「立即沟通」是否自动发招呼语已用「先读消息列表」兼容，但读到的历史消息边界待实测确认。

### 3.3 编排

**Run 状态机**

```
CREATED → RUNNING ⇄ PAUSED
                └→ COMPLETED | QUOTA_REACHED | CIRCUIT_OPEN
                   | LOGIN_LOST | CANCELLED | FAILED
```

**终止条件优先级**：`QUOTA_REACHED`（投递达配额）→ `COMPLETED`（组合队列耗尽或评估数达上限）→ 单组合轮数/时间上限 → 熔断。

> 上图的终止语义与上面这条优先级**已被 spec 0003 修订**：投递配额、评估上限、组合耗尽三者改为「中止扫描，进入值守待命（`STANDBY`）」，`QUOTA_REACHED` 不再产生，时段结束改为挂起（`WAITING_DUTY`）而非收工。

**主循环**

```
for 组合 in 队列:
    打开搜索页 → 反复滚动
    每轮：取新卡片 → 逐张处理
        硬黑名单命中?         → 丢弃，不落库
        HR 活跃度 > 7 天?     → 丢弃，不落库
        岗位已投递/投递中?     → 跳过
        抓接口数据 → 落 job_posting
        评估数 +1；达上限 → 收工
        质量开关开? → AI 判定，否则跳过
        判定通过 → 进会话 → 读消息 → 发招呼语 → 等送达
        投递数 +1；达配额 → 收工
    到点做一次未读检查
    到底（footer 可见且连续 2 轮无新增，或连续 8 轮无新卡，或 300 轮）→ 下一个组合
```

**关键判据**

- 加载完：footer 可见**且**连续 2 轮无新增，或连续 8 轮无新卡片，硬上限 300 轮。footer 不能一可见就判定——窗口最大化时首屏很短，第一轮 footer 就可见；且部分版式没有 `#footer`，所以「连续 8 轮无新增」是必需的第二道判据。连续 3 轮无新增时强制触底一次再继续判。
- 抓详情：`waitForResponse(url.contains("/wapi/zpgeek/job/detail.json") && GET, card::click)`。**第一张卡片默认展开、不会触发请求**，必须先点第二张再点回第一张，并在「点回第一张」时挂监听。
- 招呼语发不发：进会话后先读消息列表，已有自己发的消息就跳过，否则发。不依赖按钮文案——平台是否自动发默认招呼是可配置的，会变。
- 进会话（参考实现的四条硬规则）：确认框只在点击后 **1 秒**内找且只点一次；「已向BOSS发送消息」提示**不是**旧会话的证据（首次沟通也会弹）；旧页面的输入框即使可见也不能用；每轮重建 locator，不复用导航前的 frame 引用。
- 送达：**看平台自己的状态，不看气泡**。首次沟通（按钮初值「立即沟通」且会话内无我方消息）→ 发送后回详情页复核按钮是否变成「继续沟通」，最长 45s，途中 15s 时重载一次（服务端落库可能晚于首次渲染）。已经是「继续沟通」的重复沟通没有状态跃迁可看，回退为「聊天区本人消息条数 +1」。
  - 为什么不用气泡做主判据：气泡会被 SPA 重渲染、列表分页/虚拟滚动裁切、挂载时机影响，实测出现过「界面明明已经发出、判据数到 0 条」的假失败（2026-10-29，delivery 110/111/112 三条真实投递被记成 FAILED，配额同步少记）。按钮文案是服务端状态，没有会话就渲染不出「继续沟通」。
  - 失败语义要留证：失败原因里必须带最后一次读到的按钮文案，否则「真的没发」与「发了没确认」分不开，运营动作（能否重发）也分不开。
- 节奏：每处理一张卡片后随机 `[waitTime/2, waitTime]`，`waitTime` 默认 10s（即 5–10s）；文字逐字输入，间隔 140ms；每处理 5 张卡片下滑 140px，避免点下方卡片触发列表刷新。

**断点**：`checkpoint = {combinationIndex, scrollRound, lastCardIndex, deliveredCount, evaluatedCount}` 落 `run` 表；部分唯一索引保证最多一个 RUNNING。

### 3.4 决策与风控

**硬规则先于 AI**

| 顺序 | 规则 | 命中后 |
|---|---|---|
| 1 | 硬黑名单（公司名 / 岗位名关键词） | 丢弃，不调 AI、不落投递 |
| 2 | HR 活跃度低于配置档位（默认「本月内」） | 丢弃 |
| 3 | 已投递 / 投递中 | 跳过 |
| 4 | AI 投递判定 | 决定投不投 + 出招呼语 |

活跃度阈值只能按**文案粒度**定档，不能按天数：平台给的文案是「在线 / 刚刚 / 今日 / 3 日内 / 本周 / 本月 / 2 月内 / 半年前」，7 天落在「本周」与「本月」之间，无法分辨。所以配置项取值为 `在线|今日|3日内|本周|本月|不限`，默认 `本月`。映射表外的未知文案归为「未知」，**不参与硬过滤**，交给 AI 判断。

**黑名单归一化**：trim → 全角转半角 → 大小写不敏感 → 子串包含；关键词最短 2 字符，空值拒绝保存；公司名或岗位名任一命中即丢弃。

**AI 调用**：一次调用同时产出 `{shouldApply, score, reason, greeting}`，`response_format: json_object`；JD 截断 4k 字符；**连接超时与读超时都要显式设置**（只设 connectTimeout 会允许无限等待响应），总超时 20s；失败即跳过该岗位（不投）。原始 response 落库便于复盘。提示词用 `{{占位符}}` 替换，**不用 `String.format`**——JD 里出现 `%`（如"提升 100%"）会直接抛格式化异常。

**质量开关**：控制 AI 是否参与「该不该投」的判断。**开启** = AI 判定 + 生成招呼语；**关闭** = 不调 AI，硬规则通过即投，使用配置里的模板招呼语。开关存在的目的就是过滤掉不值得投的岗位，所以关闭时应退回人工写好的固定文案，而不是停止投递。

**演练模式**：与质量开关无关的独立运行选项——只扫描与评估落库、不发起投递，也不发值守回复。用于换模型、调提示词前验证效果。

它**不是只读预览**，这一点必须在界面与文档里都说清，否则「演练」二字本身就会误导。仍然发生的副作用：真实浏览平台（打开搜索、滚动卡片、抓取详情）；真实调用 AI 判定与写招呼语（消耗 token、计入当日评估上限）；值守会真实点开会话读消息——**点开会话即清红点，平台无「标回未读」能力，这些消息之后不会再出现在未读列表里，正常运行也不会回复它们**。不受值守时段限制；当日投递配额与评估上限照常生效。结论只落在 `run_log`（「本会投递」「本会回复」），`delivery` 记 `SKIPPED`，控制台计数不增加、也不显示成已发送。实现见 spec 0002 §4.2。

**熔断**：验证码 / 今日沟通次数上限 / 连续 2 次登录态失效 / driver 自检失败 → 停止 + 存断点 + 告警。

### 3.5 值守

扫描主循环内每 5 张卡片或每 120s 触发一次未读检查，复用当前 page，不新开 tab。命中未读 → 进会话读消息 → 判定是否该回 → 回。值守失败不中断扫描。「10 分钟」定义为最坏感知延迟，不是硬实时。

**判定顺序（顺序即优先级）**

| 步骤 | 判断 | 结果 |
|---|---|---|
| 1 | 是不是自己发的消息 | 是 → 忽略 |
| 2 | 该消息是否已处理过 | 是 → 忽略（按平台消息 id 去重） |
| 3 | 是否命中敏感词（薪资、面试时间、offer、社保等） | 是 → **整条不回复**，记一条「因敏感跳过」 |
| 4 | 是否命中值守规则的任一关键词 | 是 → 取该规则下的一条文案回复（同会话 3 分钟内最多回一次） |
| 5 | 都没命中 | 不回复，落一条「未命中」记录 |

**规则配置**：一条规则 = 关键词组 + 文案列表 + 启用开关。命中多个规则时取关键词最长的那条。敏感词优先于关键词，一定先判——否则「你们薪资多少」会同时命中「薪资」敏感词和某条关键词，回复出去就出事了。

**「未命中」记录是自增长的**：控制台岗位页给一个「未命中消息」列表，你把新规则补进去，规则库就慢慢长起来。这是值守能力唯一的进化路径，不要省。

**回复长度**：单条 ≤ 60 字，纯文本，不带动图/表情，避免触发平台的内容检测。

**界面**：配置 → 值守规则，单页三块——敏感词（红调 chips，命中即不回复）、回复规则（关键词 chips + 文案池，其中一条标「本次命中」）、未命中消息（近 7 天，每条带「转为规则」）。草案见 `docs/ui/0001-reply-rules.html`。

**默认种子**：7 条规则 / 13 条文案 / 10 个敏感词，已固化进 `server/src/main/resources/db/migration/V3__seed_reply_rule.sql`（已验证可执行且幂等）。其中「礼貌收尾」默认关闭——和 HR 的客套话容易形成无意义往返。

### 3.6 配置与控制台

| 页面 | 内容 |
|---|---|
| 概览 | 配额进度、今日投递数、最近 Run 状态、登录态与 driver 自检灯 |
| 任务 | 启动/暂停/停止、组合队列、当前组合进度、实时日志 |
| 岗位 | 岗位与投递记录，支持「需人工」重投 |
| 配置 | 模型提供商（名称 / API Key / base URL / 超时 / 自定义头，隐藏主键）/ AI 配置（个人介绍 + 提示词 + 质量开关）/ 平台配置（搜索条件、六组筛选、黑名单）/ 值守规则（敏感词 + 规则 + 未命中）/ 使用说明 |
| 系统 | 登录态检测、driver 自检、通知测试、数据清理 |

### 3.7 数据

| 表 | 职责 |
|---|---|
| `provider` / `ai_config` | 模型提供商与 AI 配置；API Key 密文 |
| `platform_config` | 平台开关、搜索参数、六组筛选、模板招呼语、活跃度档位 |
| `platform_option` | 字典：`platform / type / name / code / sort_order`，唯一索引 `(platform,type,code)`，由种子迁移导入 |
| `blacklist` | 公司 / 岗位关键词 |
| `reply_rule` / `reply_rule_text` / `sensitive_word` | 值守规则、规则下的文案池、敏感词 |
| `job_posting` | 岗位数据（接口 JSON 落 `raw_json`） |
| `delivery` | 每次投递尝试：状态、招呼语、失败原因 |
| `conversation` | 会话游标：最后检查时间、首条 HR 回复时间、回复条数、我方最后回复时间 |
| `reply_log` | 值守动作：收到的消息摘要、是否回复、命中的规则或敏感词、未命中原因 |
| `run` / `combination_run` | 任务与组合执行记录、断点 |
| `run_log` | 运行日志（独立流） |
| `daily_counter` | 当日投递数 / 评估数 |

**保留策略**：投递记录永久；扫描明细保留 30 天，但清理条件必须是「未投递 且 超 30 天」，否则去重失效会重复投递；`reply_log` 保留 90 天（规则优化的原料）；`run_log` 保留 14 天。

**效果采集（可开关）**：开启后由 `conversation` 的三个时间字段算出回复率与首响时长，只做统计展示，不参与任何自动决策。

**凭据**：API Key 与 Cookie 经 DPAPI 保护的 master key 加密后落 SQLite，启动解锁一次。

### 3.8 接口

- 下行：REST（启动 / 暂停 / 停止 / 重投 / 配置读写 / 登录态检测）。
- 上行：SSE `/runs/{id}/events`，只推状态与计数，事件类型 `run.started` / `combination.started` / `job.evaluated` / `delivery.updated` / `unread.detected` / `circuit.opened` / `run.finished`；日志独立 `/runs/{id}/logs`。
- 重连：`Last-Event-ID` + 内存 ring buffer 500 条，不做长期补发。

---

## 4. 落地实施

### 4.1 工程结构

```
chat-job/
├── CONTEXT.md
├── docs/{adr,spec,ui}/
├── tools/                              # 数据提取等一次性脚本，不进 jar
│   ├── extract-boss-dict.py
│   └── out/                            # 生成的种子 SQL 与字典 JSON
├── build/patchright-driver/            # 构建产物，不入库
├── server/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/chatjob/
│       │   ├── config/                 # DPAPI、SSE、静态托管、异常处理
│       │   ├── web/                    # RunController / ConfigController / JobController / SystemController
│       │   ├── orchestrator/           # RunService、RunStateMachine、CombinationQueue、Checkpoint
│       │   ├── driver/                 # PlaywrightFactory、BrowserSession、LoginProbe、SelfCheck
│       │   ├── platform/               # PlatformAdapter、boss/{BossAdapter,BossSelectors,BossEndpoints,BossUrlBuilder}
│       │   ├── evaluate/               # BlacklistMatcher、ActiveLevelNormalizer、AiJudge
│       │   ├── watcher/                # UnreadWatcher、ReplyStrategy
│       │   ├── notify/                 # Notifier、WecomRobotNotifier
│       │   └── store/                  # entity / mapper / migration
│       └── resources/db/migration/     # 版本化 SQL
├── console/                            # Nuxt 3
└── start.bat
```

> 上图是 MVP 规划期的类名快照。当前不存在的规划名包括 `RunStateMachine`、`CombinationQueue`、`Checkpoint`、`PlaywrightFactory`（实为 `PatchrightDriver`）、`LoginProbe`、`ActiveLevelNormalizer`（实为 `ActiveLevel`）、`ReplyStrategy`（实为 `ReplyRuleEngine`）；`JobController` 现已存在。**类名与代码落点以当前 `server/src/main/java/` 为准。**

### 4.2 分期

| 阶段 | 内容 | 出口 |
|---|---|---|
| **P0 验证** | 照搬 driver 装配任务 + 指纹自检 + 登录 + 扫一组 + 手工投一次的全流程脚本 | 自检断言通过（`navigator.webdriver === false`、无 `cdc_` 残留）且单次投递跑通。经参考实现验证，这一步的风险已从「可能推翻方向」降为「配置问题」 |
| P1 | 数据层、Run 状态机、组合队列、断点、SSE、配置读写 | 能空跑出正确状态流转与断点续跑 |
| P2 | 浏览器会话、搜索滚动、接口抓取、硬规则、登录探测与自检 | 能扫完一个组合并把岗位落库 |
| P3 | AI 判定、投递动作、配额与评估上限、终止态 | 能产生真实投递并正确收工 |
| P4 | 值守、关键词回复、通知与节流、数据清理 | 异常能告警，未读能回 |
| P5 | 控制台五页 | 全流程可点 |

P0 必须在写任何业务代码之前完成。P1–P5 严格串行。

### 4.3 测试

| 层 | 方式 |
|---|---|
| 编排层（黑名单归一化、去重、配额、断点、JSON 解析、状态迁移） | hand-written fakes，全量单测，不引 mock 框架 |
| 解析层 | 真实接口 response 存 JSON fixture，纯解析测试，不启动浏览器 |
| 选择器层 | 全部集中到 `BossSelectors` / `BossEndpoints`，改版只改一处 |
| E2E | 不做自动化。真登录成本远大于收益，改为发布前手工跑一遍 |

---

## 5. 风险与验收

### 5.1 风险 Top 3

| # | 风险 | 影响 | 应对 |
|---|---|---|---|
| R1 | patched driver 无法注入 Playwright Java，或版本被永久锁死 | 项目方向要改（退化到 Node 执行器，+3–5 天） | P0 先验证；两条注入路径都试过再判死 |
| R2 | BOSS 改版或强化反制 | 全链路中断 | 选择器集中 + fixture 测试定位；识别到无限刷立即熔断 |
| R3 | 账号被限制或封禁 | 工具价值归零 | 单串行、配额 30、随机间隔、异常即停、不用主号试跑 |

### 5.2 验收口径

1. 关闭质量开关跑一轮，`daily_counter` 的评估数增长、投递数保持 0。
2. 开启后跑一轮，控制台可见每张卡片的决策原因（黑名单 / 活跃度 / 重复 / AI 通过或不通过）。
3. 投递达 30 时 Run 终止态为 `QUOTA_REACHED`，且企业微信收到一条通知。（**已被 spec 0003 取代**：投递达配额改为进入 `STANDBY`，只中止扫描、值守继续到时段结束；告警仍发。当前 `QUOTA_REACHED` 仅作为演练模式的 `terminal_reason` 字符串保留。）
4. 手动删除 profile 里的登录态后启动，任务拒绝运行且给出告警，不产生任何投递。
5. 中途强杀进程后重启，从最后一张卡片续跑，已投递岗位不再出现投递动作。
6. 硬黑名单关键词「外包」能命中「XX信息技术外包有限公司」并跳过，且不产生 AI 调用。
7. 构造一条同时含敏感词「薪资」与某条值守关键词的消息，处理后**不回复**，`reply_log` 记为「因敏感跳过」。
8. 空库启动后 `platform_option` 有 579 条；用「福州」+「Java」能拼出可正常打开的搜索 URL（证明字典 name→code 映射通了）。

### 5.3 未闭环的事实

当前阻塞项（原调研稿结论已并入本文 §3.2 与下表）：

| 事实 | 现状 | 处理 |
|---|---|---|
| 搜索瀑布流真实深度 | 手动可拉到底，页数未知 | 到底由 footer 判据决定，300 轮仅安全阀 |
| 默认招呼语是否自动发出 | 取决于平台配置，会变 | 已改为「进会话先读消息列表」的判据，两种行为都安全 |
| 今日沟通次数上限阈值 | 未知（参考项目 README 提到约 150/天，非代码约束） | 命中即熔断；配额默认 30 可调 |
| 有间隔时点多少详情会触发风控 | 只有一个无间隔数据点（7 分钟 297 个） | 评估上限 150 需实测校准；每 50 个插入一次长休 |
| 字典数据（城市 / 行业 / 各类 code） | 已解决：从参考项目 `db/getjobs.db` 提取 579 条，工具 `tools/extract-boss-dict.py`，产物已固化进 `server/src/main/resources/db/migration/V2__seed_platform_option.sql`（已校验可执行且幂等） | 无阻塞 |

---

## 6. 最终审查

### 6.1 设计约束落点自检

| 约束 | 落点 |
|---|---|
| C1 单机单账号 | 只绑 127.0.0.1；无账号表 |
| C2 单进程 | `server` 内嵌 driver，无独立执行器进程 |
| C3 真实 Chrome | `BrowserSession` 的 launch 参数 |
| C4 patchright | `PlaywrightFactory` + ADR-0001 |
| C5 单 RUNNING | `run` 表部分唯一索引 |
| C6 fail-closed | `PlaywrightFactory`（driver 缺失）、`AiJudge`（判定失败）、`RunService`（熔断）三处 |

### 6.2 已知取舍

- 用「岗位卡片维度 + 状态」去重而不是「会话是否存在」，换取实现简单，代价是平台侧手工建立的会话不在感知范围内。可接受。
- 值守嵌在主循环里，代价是扫描期间的消息感知最坏延迟 10 分钟。可接受。
- 不做 E2E 自动化，代价是改版后靠 fixture 测试可能漏判，需人工跑一遍。可接受。

### 6.3 待评审确认项

1. 本文是否覆盖了你认为 MVP 必须有的能力？
2. P0 的 Go/No-Go 门是否接受——如果 driver 装不上，是先改 Node 执行器还是先降级用官方 driver 跑一周？
3. `R1` 的备选方案（Node 执行器）现在是否值得预留接口，还是等 P0 结果再定？
