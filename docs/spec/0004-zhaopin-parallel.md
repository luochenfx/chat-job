# 智联接入与多平台并行

> 本 spec 把两份 ADR 串成一条可执行的顺序。设计已定，不在这里重述 ——
> 架构决策见 [`adr/0003-parallel-platforms.md`](../adr/0003-parallel-platforms.md)，
> 投递语义见 [`adr/0004-delivery-vs-resume-on-request.md`](../adr/0004-delivery-vs-resume-on-request.md)，
> 页面事实见 [`tech/0003-zhaopin-research.md`](../tech/0003-zhaopin-research.md)。

## 1. 上下文锚定

### 1.1 这是什么

新增第二个招聘平台（智联招聘），并让两个平台**同时**运行：各自扫描、各自值守、互不阻塞。

### 1.2 为什么要做

Boss 一条流水线一天能扫的组合有限，而且每个平台的岗位池不重叠。多一个平台等于多一份候选池，但代价不能是"跑完一个再跑另一个"——扫描耗时被投递等待支配（一次投递要等按钮响应与送达确认），串行就是时长直接相加。

### 1.3 现状与边界（读代码确认，不是推断）

| 层 | 现状 |
|---|---|
| 数据库 | **已按平台隔离**：`uk_run_running(platform)`、`uk_unread_task_open(platform, …)`、`uk_conversation(platform, …)`、`uk_job_posting(platform, …)`、`uk_daily_counter(platform, day)`、`uk_platform_config(platform)` |
| Java 装配 | 挡并行的是 5 个单例：`PlatformAdapter`（唯一实现 `BossAdapter`）、`BrowserSession`（单 `userDataDir`）、`PlaywrightExecutor`（单线程）、`RunService`（6 个单任务字段）、前端 `/runs/current`（单数） |
| 平台差异 | 已收口在 `PlatformAdapter`，编排层没有平台分支（`grep "if (platform" orchestrator/ watcher/ evaluate/` 为空） |
| 配置层 | `ConfigStore.platformConfig(String platform)` **已参数化**，`PLATFORM_BOSS` 常量定义后从未被使用 —— 配置层不需要改 |
| 值守 | 嵌在扫描循环里（每 5 张卡或 120 秒调一次 `sweep`），因此**扫描并行 = 值守并行** |
| 投递语义 | 主动投递写 `delivery` 表，被动发简历只写 `reply_log` —— Boss 侧已经是这样了（`grep delivery watcher/` 零命中） |

### 1.4 术语

见 [`CONTEXT.md`](../../CONTEXT.md)。本 spec 只用其中已定义的词：投递、值守发简历、投递配额、筛选参数、值守。

---

## 2. 约束与目标

### 2.1 硬约束

1. 平台差异只许出现在 `PlatformAdapter` 实现里，编排层禁止 `if (platform == ...)`。
2. `delivery` 表不改结构（`job_posting_id` 保持 `NOT NULL`），被动发简历不进这张表。
3. 每个平台一个独立的浏览器 profile 目录 —— 同一目录有独占锁，第二个实例起不来。
4. 值守不另起线程、不另起进程，复用本平台那条流水线的浏览器上下文。
5. 不引入并发信号量、不限制同时运行的平台数。

### 2.2 目标（完成的定义）

- 智联能独立完成：扫描 → 投递 → 值守 → 应对方索要发简历，全链路可观测（日志 + 库里有据）。
- 两个平台可同时运行：各自的 Run 独立，一个平台的浏览器崩溃/卡死不影响另一个。
- 「今日投递」这个数字只含主动投递，与库里的投递表对得上账。
- 接第三个平台时，编排层改动为 0 行。

### 2.3 非目标

- 不做跨平台的统一配额（配额本来就是按平台的）。
- 不做跨平台的规则共享评估（规则目前无平台维度，问题已知，本 spec 不动）。
- 不做智联的投递上限实测（U1，未知就先不设默认值之外的推测）。
- 不改值守的 AI 回复链路，只改它能不能被智联触发。

---

## 3. 方案设计

两份 ADR 的分工：

| | 决策 | 何时生效 |
|---|---|---|
| ADR-0004 | 投递与值守发简历的分野、口径改名、`delivery` 表不动 | 阶段 0，立刻 |
| ADR-0003 | 每平台一条独立流水线、装配方式、值守随扫描并行 | 阶段 2，等智联适配器验证通过后 |

**为什么不是先做架构改造**，四条理由：

1. **不确定性最大的东西要先碰**。智联适配器是整个需求里唯一"不知道能不能成"的部分——选择器、投递判据、卡片文本可读性都可能变。并行改造是纯机械的装配工作，风险低。先把不确定的做掉，再动地基。
2. **并行框架做完后，没有东西能验证智联适配器**。并行要两个平台都跑起来才有意义；只有一个平台时，并行框架等于没启用。
3. **必须把适配器的 bug 和并行的 bug 隔开**。先上并行，智联适配器出问题时会和"线程打架"混在一起，排查时分不清是选择器错了还是并发错了。
4. **阶段 1 的装配代码在阶段 2 会废弃约 20 行**（单值注入 → Map 注入）。这是刻意的取舍：让阶段 1 尽量小、只有一个新文件和一处配置，比让它"提前适配未来"更重要。

---

## 4. 落地实施

四个阶段。**关键路径是 1 → 2 → 3**，阶段 0 可以插在任何位置（与阶段 1 文件不重叠，可并行，但一个人做时串行更省心）。

### 阶段 0：口径与词汇（ADR-0004 的软部分）

| 项 | 内容 |
|---|---|
| 范围 | 控制台与日报的「投递」文案改为「主动投递」；新增「值守发简历数」（数据源 `reply_log` 的 `REPLY_RESUME`）；`deliveredToday` 确认只数主动投递（现在就是这样，需加断言防回退） |
| 交付物 | `console/` 相关文案、`RunStore.deliveredToday` 的注释与单测 |
| 验收 | 手动投递 1 次 + 值守发简历 1 次，两个数字分别 +1，且「主动投递」不因后者变化 |
| 依赖 | 无 |
| 回滚 | 文案改动，直接 revert |

### 阶段 1：智联适配器（单平台验证）

| 项 | 内容 |
|---|---|
| 范围 | 新增 `platform/zhaopin/ZhaopinAdapter implements PlatformAdapter` 全套方法；加一个配置项 `chat-job.active-platform`（默认 `boss`）决定注入哪个实现 |
| 关键实现点 | ① 列表：读 `/jobs/?jl=&kw=` 的内嵌 `positionList`（一次拿全字段，含 `positionNumber` 与 `activityLevel`）；② 详情：打开 `/jobdetail/<positionNumber>.htm`；③ 投递：点「立即投递」→ 等 `deliver-greeting-modal` 文案 → `greeting` 参数忽略；④ 已投：`hasAppliedPosition` / `deliveryState` / 本地 `delivery` 三路判，命中即 SKIPPED；⑤ 值守：切 `i.zhaopin.com/im`，未读数读会话行角标；⑥ 发简历：点工具栏「发简历」，照搬 `BossAdapter.sendResume` 的判据 |
| 验收 | 用 `active-platform=zhaopin` 跑完：一次完整扫描（含至少 1 次真实投递）、一次值守（含 1 次被动发简历）；切回 `active-platform=boss` 后 Boss 功能不受影响 |
| 依赖 | 阶段 0 的文案（否则日志里两个"投递"分不清） |
| 回滚 | 改配置项即可切回 Boss，代码留着不影响 |
| 待验证 | 收据见 ADR-0004 的两条 Open Question：卡片 `text()` 可读性、工具栏按钮与「同意」的可用条件 |

**阶段 1 实施记录（2026-10-31，真实页面 + 演练运行取证）**

计划里的 ① 被实测推翻了一半，落地时按页面事实改了做法，四处偏差都留了证据：

| 偏差 | 计划 | 实际做法 | 证据 |
|---|---|---|---|
| 岗位身份来源 | 读内嵌 `positionList` 一次性拿全字段 | **点开卡片读右侧面板**，编号从面板里 `/jobdetail/<编号>.htm` 链接抠 | 首屏 `positionList` 只有 20 条，无限滚动后 DOM 有 195–196 张卡片；按下标映射时日志为「DOM 上有 196 张卡片，但只解析到 20 个岗位编号，多出的 176 张会被跳过」 |
| 翻页数据 | 监听 `POST /c/i/search/positions` 的响应 | **整条废弃**，不再监听该接口 | 监听挂上后一次响应都没抓到（`grep search/positions` = 0），而卡片照样从 20 涨到 196 |
| 职位描述 | 列表内嵌 `jobDescription` | **面板全文优先**（谁长用谁）+ 清洗站点噪声 | 列表版本是截断且混着 `版权所有 zhaopin.com`、`看看大家怎么写 · 1/3`、HTML 标签；面板版 304–512 字干净正文 |
| 活跃度 | 适配器内归一到三档 | **只带原文**（`activeRaw`），归一交给 evaluate 层 `ActiveLevel.of` | 全系统只有一套档位（在线/今日/3日内/本周/本月），Boss 也是这么做（`""` + `activeRaw`）；智联原文有「今日回复19次」「近7日回复100+次」等多形态 |

顺带结了 ADR-0004 的 Open Question 1：**简历索要卡片的文案可读**（`.im-message` 顶层 `innerText` 能拿到「我想要一份你的简历，你是否同意？」），
所以「简历索取」规则不需要另开路径。另一条 Open Question（工具栏「发简历」的可用条件）仍未验证 —— 要等真实值守发简历那一步。

### 阶段 2：并行改造（ADR-0003）

拆成三步，每步独立可编译、可运行、可回滚：

| 步 | 范围 | 验收 |
|---|---|---|
| 2a | `BrowserSession` / `PlaywrightExecutor` 改为每平台一份；`userDataDir` 按平台分目录（`profile/boss`、`profile/zhaopin`） | 两个浏览器实例能同时起来，各自独立导航互不干扰 |
| 2b | `RunService` 改为按平台实例化（prototype + registry），内部 6 个字段一行不改；`PlatformAdapter` 改为 `Map<String, PlatformAdapter>` 注入 | 两个平台各有一个活跃 Run，`uk_run_running` 不冲突；杀掉一个平台的浏览器，另一个照常跑 |
| 2c | 控制台改造（界面规格见 [`ui/0002-parallel-console.md`](../ui/0002-parallel-console.md)）：概览并排、其余四页单平台切换；`/runs/current` 带 platform；写进 `data/logs/chat-job.log` 的每条日志带平台前缀；企业微信通知带平台名 | 概览不滚动能看到两平台状态；任务页切平台不串流；通知能看出是哪个平台 |

| 项 | 内容 |
|---|---|
| 依赖 | 阶段 1 通过（否则在未验证的适配器上改架构） |
| 回滚 | 2a/2b/2c 各自一个提交，出问题单独 revert |
| 注意 | 2a 完成后用户需要**为智联的自动化 profile 单独扫码登录一次**（现有登录态在日常浏览器，本来就不能复用） |

**阶段 2a 实施记录（2026-10-31，真实浏览器 + 服务启动取证）**

落地结构与计划一致（每平台一份 `PlaywrightExecutor` + `BrowserSession`，由 `BrowserSessions` 注册表按需创建），三处按实际情况调整：

| 项 | 计划 | 实际做法 | 依据 |
|---|---|---|---|
| profile 目录 | `profile/boss`、`profile/zhaopin` | **`browser-data/boss`、`browser-data/zhaopin`**：根目录沿用既有 `browser-data`，只在其下按平台分 | 现有 boss 登录态（`browser-data/Default/Network/Cookies`）就在这个根下。换根等于逼 boss 也重新扫码一次，而这与「分目录是为了让两平台并行」无关。迁移做成一次性搬迁，不改配置根即可保持登录态 |
| 单值注入点 | 编排层与适配器「一行不改」，等 2b 改成 Map 注入 | 加一个过渡 `@Configuration`（`BrowserWiring`），把 activePlatform 那份导出成 `@Primary` bean | `BrowserSession` / `PlaywrightExecutor` 已经没有 `@Component`，而 `RunService`、`SelfCheck`、两个适配器仍按单值注入。这个 bean 是 **2b 的删除项**，不是长期结构 |
| 线程归属判据 | — | `onPlaywrightThread()` 从静态线程名常量改成实例字段（`playwright-boss` / `playwright-zhaopin`） | 原实现用 `static final THREAD_NAME` 比较线程名；两条线程并存时，A 平台会误判「我在自己的线程上」，从而在错误线程上就地执行页面操作 |

验证方式与结果（三方交叉）：

- **代码**：`PlatformBrowserIsolationTest`（默认 `@EnabledIfSystemProperty` 关闭，手动 `-Dchat-job.browser.verify=true` 开启）断言：同平台复用同一份、跨平台会话与线程互异、profile 目录按平台分离、创建对象不启浏览器、起一个不会牵动另一个、各自导航互不干扰、关掉一个另一个仍可用、关掉的能自己重建。
- **日志**：`[playwright-boss] [boss] 浏览器已启动（profile: …\browser-data\boss）` / `[playwright-zhaopin] [zhaopin] …browser-data\zhaopin`；`DataRootReporter` 现在按平台列出两行档案目录。改前同一处日志是 `[playwright-thread]` + 单一 `browser-data`。
- **进程**：两棵独立的 `msedge` 进程树，`--user-data-dir` 分别指向 `…\boss` 与 `…\zhaopin`，同时存活。
- **页面**：服务以 `active-platform=boss` 起时控制台侧栏是「BOSS 直聘」、自检灯「浏览器会话 已启动」；切 `CHAT_JOB_ACTIVE_PLATFORM=zhaopin` 重启后侧栏变为「智联招聘」，且 `browser-data/` 下才出现 `zhaopin` 目录；切回 boss 后 `probe-login` 仍返回 `loggedIn=true`（登录态没因分目录丢失）。
- **bsk（自动化浏览器本体，二次实测已含两平台各一份扩展）**：
  - 单平台（智联服务）：`bsk browsers` 列出 `f1c52653 自动化浏览器2（EXT 0.4.0）`；`bsk session start --browser f1c52653` + `bsk tab list` 读到服务 `open-login` 打开的 `福州招聘网…智联招聘` / `https://www.zhaopin.com/`；`bsk tab borrow` + 只读 `evaluate` 取到 `{url: https://www.zhaopin.com/, title: 智联, webdriver: false}`，页面 banner 显示已登录账号。
  - 双平台并存（`PlatformBrowserIsolationTest` + 真实 `browser-data`）：`bsk browsers` 同时列出 `9a198826 自动化浏览器（EXT 0.3.3）` 与 `f1c52653 自动化浏览器2（EXT 0.4.0）`；分别开会话读标签，`9a198826` → `BOSS直聘…` / `https://www.zhipin.com/`，`f1c52653` → `智联招聘` / `https://www.zhaopin.com/`，各自停在自己平台上，没有交叉。

一条必须记住的限制：**bsk 能不能观测到某个自动化浏览器，取决于那个 profile 里有没有装、以及装的是哪一版 bsk 扩展**，与「bsk 是否支持 Playwright 浏览器」无关。

| profile | bsk 扩展 | `bsk browsers` 显示 | 说明 |
|---|---|---|---|
| `browser-data/boss` | 有（`emacgiaaaiojkkpkddmmdfhmokgmnikg` 0.3.3） | `自动化浏览器`（`9a198826`） | 2a 之前就在用的 profile，扩展一直跟着；`tab list` 能读到 `https://www.zhipin.com/` |
| `browser-data/zhaopin` | 有（同 ID，0.4.0，2026-10-31 手动安装并命名为「自动化浏览器2」） | `自动化浏览器2`（`f1c52653`） | 装扩展后立刻可见，`tab list` 读到 `https://www.zhaopin.com/`；装之前该目录连 `Default/Extensions` 都没有，30 秒轮询不出现 |
| `build/verify-2a/*` | 无 | 不出现 | 验证测试用的一次性空目录 |

两侧启动参数都没有 `--load-extension`：扩展来自 profile 内部，不是启动时注入。所以 2a **之前**智联（那时和 boss 共用 `browser-data`）是有插件、bsk 可见、也带着登录态的；2a 之后它成了独立空 profile，这三样都要从零来一次 —— 不是 bsk 退化了，是独立 profile 的必然代价。

截至 2026-10-31 已把这三样补回：`browser-data/zhaopin` 装上了 bsk 扩展 0.4.0（bsk 里命名「自动化浏览器2」），并已完成登录（页面 banner 显示账号）。所以后续开发不需要再为智联单独扫码，两平台可以各自带着登录态并行。

判「bsk 看不看得到这个浏览器」的正确顺序：先看该 profile 的 `Default/Extensions`，再看 `bsk browsers`。反过来做，会把「空 profile 没扩展」误判成「bsk 不具备观测能力」—— 我第一版就栽在这里。

**阶段 2b 实施记录（2026-10-31，真实浏览器 + 两平台各一条活跃 Run 取证）**

落地结构与计划一致（`PlatformAdapter` 改成按平台索引、`RunService` 改成 prototype + registry、那 6 个单任务字段一行没改），六处按实际情况调整：

| 项 | 计划 | 实际做法 | 依据 |
|---|---|---|---|
| 适配器注入形状 | `Map<String, PlatformAdapter>` 注入 | **`PlatformAdapters` 组件**（内部就是那张 Map，另带归一化与顺序） | 直接注入 `Map<String, PlatformAdapter>` 时 key 取的是 **bean 名**（`bossAdapter`），而 `of("boss")` 要的是平台标识；另写一个显式 `Map` bean 又会与 Spring 的 Map 自动装配语义撞车。做成组件与 `BrowserSessions` 同构，key 直接取 `adapter.platform()` |
| 定时与启动收尾 | 留在 `RunService` | **搬到 `RunServices`**（`wakeWaitingRuns` 每 30s 遍历平台、`recoverAfterRestart` 遍历平台收孤儿） | prototype 上挂 `@Scheduled`/`@EventListener` 会**随实例创建次数重复注册** —— 三个平台就是三份定时器，且随控制台点开几个平台而增长 |
| `@Primary` 单值会话 | 2b 删除项 | **已删除**（`BrowserWiring` 整个文件）；两个适配器与 `SelfCheck` 改为 `BrowserSessions.of(自己的平台)` | 两个适配器同时装配后，那个 bean 只可能指向其中一个平台；另一个适配器会在**别人的 profile** 上操作页面，症状是随机 `Object doesn't exist`，且只在两平台同时跑时复现 |
| 自检 | 未提及 | `SelfCheck.run(platform)`，检查结果里带 `platform` | 指纹是**浏览器实例**的属性。原来注入单值会话，并行后等于「A 平台的自检替 B 平台作保」 |
| 日志平台前缀 | 2c | **提前到 2b**：`RunService.log()` 唯一出口改成 `[boss] [run 39] …` | spec §5.1 把「日志交错难以排查」列为 2b 的上线前提；这一处只改一行，等到 2c 再做等于带着已知风险上线 |
| `RunService.current()` 兜底 | 未提及 | `recent(1)` → **`latest(platform)`** | `recent(1)` 是全库最新，并行后会把**另一个平台**的 Run 显示在本平台任务页上（`RunStore.latest` 的注释早已写明这条坑） |

API 形状（2c 只改前端，不再动后端）：新建/查当前带可选 `platform`（缺省 `chat-job.active-platform`），暂停/继续/取消只给 `runId` 并由 id 反查平台 —— 传错平台的后果是暂停了另一条流水线而界面看起来毫无变化。

验证方式与结果（四方交叉，全部来自同一次真实运行）：

- **代码**：新增 `PlatformAdaptersTest`（4 例：按平台取、大小写归一、未知平台当场 400 而非返回 null、顺序按 `KNOWN` 且未声明平台沉到末尾）。全量单测 **255 例通过**（原 251 + 4）。
- **日志**：`[adapters] 已装配平台: [boss, zhaopin]（未指定平台时缺省 boss）`；两平台各一条 `[playwright-boss]` / `[playwright-zhaopin]` 线程上的 `浏览器已启动（profile: …\boss / …\zhaopin）`；`[boss] [run 39]` 与 `[zhaopin] [run 40]` 两条日志流交错且可分辨；`[runs] 重启收尾完成，已装配平台 [boss, zhaopin]`。
- **库**：run 39 / 40 同时 `RUNNING`，`select platform,count(*) … group by platform` = `boss:1, zhaopin:1` —— `uk_run_running` 按平台各放一条，不冲突。
- **页面（bsk）**：`9a198826 自动化浏览器`（EXT 0.3.3）`tab list` → `BOSS直聘「福州招聘」` / `https://www.zhipin.com/web/geek/jobs?city=…&query=java`；`f1c52653 自动化浏览器2`（EXT 0.4.0）→ `https://i.zhaopin.com/im` + `https://www.zhaopin.com/jobs/?jl=681&kw=java`。两个实例各停自己平台，无交叉。
- **故障隔离（两个方向都测了）**：
  - 杀掉 zhaopin 的浏览器进程树（`taskkill /T /F`）→ zhaopin run 40 从第 N 张卡起逐张「没抓到详情，跳过」，组合跑完后 `COMPLETED`；**boss run 39 全程照跑**（`last_activity_at` 持续前进，仍在「等待 8 秒后处理第 2 张卡」）。
  - 反过来 cancel boss run 39 → boss 在卡片边界 `CANCELLED`，**zhaopin run 41 仍在「读取第 4 张卡的岗位详情」**。
- **重启**：直接杀 JVM 后重启，run 35（boss，`RUNNING`）被标 `FAILED / SERVICE_RESTART`，收尾由 `RunServices` 按平台分片执行，合计等价于改造前的一次全清。

一条与代码无关、但会误导排查的环境坑：**强杀浏览器后若有 msedge 子进程残留，该 profile 后续每次启动都会让 Playwright 报
`Protocol error (Network.setCacheDisabled): session closed`，进而 `pipe closed` 自检失败**。现象很像「2b 把平台装配弄坏了」（同一时刻另一平台却完全正常）。判据是看进程而不是看日志：
`Get-CimInstance Win32_Process -Filter "Name='msedge.exe'" | ? { $_.CommandLine -like '*browser-data\<平台>*' }`，
清干净残留后立刻恢复（实测 zhaopin 在清理后一次起成功）。所以「一个平台起不来」先查残留进程，再怀疑装配。

**阶段 2c 实施记录（2026-10-31，真实库 + 服务启动 + bsk 页面取证）**

先纠正一处计划与现实的对账：**2c-2 的写路径在 2b 已顺手做完**（`RunController` 按 `{id}` 反查平台、
`POST /runs` 带 `platform`、probe/open-login 带 `platform`），所以 2c 剩下的实际是
「2c-3/2c-4 前端 + 一处被漏掉的后端项（通知带平台名）」。

| 项 | 计划 | 实际做法 | 依据 |
|---|---|---|---|
| 通知带平台名 | 2c 改标题 | **改 `Notifier` 契约**：加 `platform` 参数（4 参），3 参版本降为 default，节流键改为 `kind|平台` | 原实现按 `kind` 节流：BOSS 熔断后 5 分钟内智联熔断会被合并掉，而那是两条流水线 —— 只加标题前缀会让「合并」这件事更隐蔽。`WecomRobotNotifierTest` 4 例锁住标题与节流键 |
| `{id}` 停错 | 期望 404 | 实际 400（`Run 不存在: 99999`） | `GlobalExceptionHandler` 把 `IllegalArgumentException` 映射成 400。结论不变：不存在的 id 不会落到缺省平台 |
| 概览的「多久没动」 | 复用任务页逻辑 | 抽成 `composables/useActivity.ts`（`useTickClock` / `idleText` / `activityView`），两页共用 | 两处各写一遍的话，「暂停该不该做陈旧判定」这类规则改一处就分叉：概览说卡住、任务页说正常 |
| 概览卡不传陈旧阈值 | — | `activityView(..., { withLabel:false })` 且不传 `staleAfterSeconds`；已结束的 Run 由 `action`/`text` 两个字段改写（见下条） | `/system/overview` 契约里没有阈值，而卡头已有状态标签。概览只回答「还活着吗」，判「卡死」留在任务页（那里有后端下发的阈值） |
| 终止态的「当前动作」 | 只处理「多久没动」 | 复查后补：已结束的 Run 不再把 `current_action`（过去时）当现在时显示 —— 手动停止/跑完/熔断/登录失效 → 「已结束：<状态名>」；**FAILED 保留最后一句 + 红色「疑似异常退出」** | 实测看到「已取消」的卡里写着「等待 9 秒后处理第 14 张卡」。进程被关掉时 `current_action` 停在死前那一步（重启收尾标成 `FAILED/SERVICE_RESTART`），这句没有别的记录可替代，所以只在 FAILED 保留 |
| 通知测试按钮 | — | 仍走 3 参（无平台），日志为 `[通知未配置] chat-job 通知测试` | 证明 default 方法没有把「无平台」写成空的 `[]` |

前端落地点（files 全部在 `console/`）：

| 文件 | 改动 |
|---|---|
| `composables/usePlatform.ts` | 重写：清单来自 `/system/platforms`，选中项 = URL `?platform=` → localStorage → 清单第一个；不再出现「唯一平台」语义 |
| `components/PlatformSwitch.vue` | 新增分段控件，任务页头 / 配置「平台配置」tab 两处复用 |
| `pages/index.vue` | 重写为并排平台卡（`auto-fit minmax(380px,1fr)`）：全局条 + 每卡六行固定顺序 + 单操作按钮 |
| `pages/task.vue` | 页头分段控件、`/runs/current?platform=`、平台变化先清空再请求（含丢弃迟到响应）、停止/暂停按钮带平台名 |
| `pages/jobs.vue` | 平台筛选（默认「全部平台」）、平台标签跟在岗位名下方、拉黑旁注「两平台共用」 |
| `pages/config.vue` | tab 标注作用域、平台配置 tab 内切换器、先重置默认再 assign、dirty 拦截（切 tab/切平台都拦）、保存后回读 |
| `pages/system.vue` | 浏览器与登录态按平台分行（探测/登录/自检都带 platform）、当日计数按平台分组 |
| `components/UnreadTasks.vue` | 明细加平台列、计数加「按平台明细」 |

验证方式与结果（四方交叉，同一次真实运行）：

- **代码**：全量单测 **259 例通过**（原 255 + `WecomRobotNotifierTest` 4），1 例按注解跳过。
- **日志**：`[boss] [run 42] 当前 19:19 不在值守时段 09:00–19:00 内，已挂起等待`；
  文件里 `[boss] [run …]` 与 `[zhaopin] [run …]` 两类前缀并存（26 / 221 条）。
- **库**：run 42 由控制台的 BOSS 卡启动、由卡上的「取消等待」结束（`NORMAL → CANCELLED`，`started_at` 为空 =
  从未离开等待态）；智联 run 41 全程未变。`daily_counter` 今日只有 zhaopin 一行（49 次评估），
  与概览卡「BOSS 0/150、智联 49/150」逐一对上。
- **页面（bsk，主浏览器观测控制台）**：
  - 概览：两平台卡并排、不滚动可见；BOSS 卡「等待时段」+「取消等待」，智联卡同时保持「已取消」+「启动」——
    一张卡的操作没有牵动另一张。
  - 任务页：切到智联后 run `#41`、组合卡片数 195、日志为 08:24–08:25 的智联内容，值守时段提示变成
    `09:00–22:00，还能跑约 2 小时 50 分钟`（BOSS 是 `09:00–19:00` 且当时不在时段）——四组数据同时换掉，
    没有一处残留上一个平台。
  - 不带 query 直接打开 `/task`，仍停在智联（localStorage）；点分段控件后 URL 变为 `?platform=zhaopin`。
  - 配置页：改配额 → 出现「有未保存改动」；点另一平台 → 原生确认框文案 `有未保存的平台配置改动，放弃并切换到 BOSS 直聘？`；
    取消 → 仍停在智联且配额仍是 31；接受 → tab 后缀变「平台配置 · BOSS 直聘」，六组筛选出现；
    再切回智联 → 六组筛选消失、配额回到 30（草稿未落库）。
  - 系统页：对「智联招聘」行点探测 → 该行「已登录 / 登录态 cookie 存在」，BOSS 行仍「尚未探测」；
    对「BOSS 直聘」行点探测 → 该行「浏览器 已启动」，智联行仍「未启动」。
- **进程**：探测后 `bsk browsers` 多出 `9a198826 自动化浏览器（EXT 0.3.3）`——0.3.3 就是
  `browser-data/boss` 那一份扩展，说明控制台点的是 BOSS 自己的 profile 而不是共用的一个。

**复查修正（同日）：终止态的「当前动作」**

第一版把已结束的 Run 的标签写对了，但「当前动作」那格仍绑 `run.currentAction`，
于是「已取消」的卡里写着「等待 9 秒后处理第 14 张卡」。改成按状态分摆法后，用两次真实运行取证：

| 场景 | 造法 | 库里的证据 | 页面显示 |
|---|---|---|---|
| 手动停止 | 概览卡上「停止」 | run 43 `CANCELLED / 用户主动停止`，`current_action` 仍是「等待 9 秒后处理第 14 张卡」 | 「当前动作」= **已结束：已取消**，无标签（长文案也不再出现） |
| 进程被关掉 | `taskkill /F` 杀 java（run 45 正跑到「AI 判定中：… @ …」）→ 重启 | 重启收尾把它标成 `FAILED / SERVICE_RESTART`，`current_action` 保留死前那句；日志 `[zhaopin] [run 45] FAILED / SERVICE_RESTART / 服务重启，上一轮中断` | 「当前动作」= 那句原样保留 + 红色 **疑似异常退出**；任务页同一句不截断，概览单行省略（`title` 带全文） |

顺带修了概览「当前动作」的换行问题：动作文案里带岗位名与公司名，折成两行会把并排两张卡的
「浏览器 / 组合」行错开，现在这一行固定单行 + 省略号。

**同一轮的收尾缺口：组合停在「进行中」**

强杀那次还在任务页留了一个尾巴：组合队列里那一行状态停在 `RUNNING`。原因是组合循环
把 `RUNNING` 写进库之后，收尾写在 `finally` 里 —— 进程没了，finally 不执行，
而 `recoverOrphans()` 只修 Run、不看组合表，界面于是出现「Run 已失败、组合还在扫」。

| 项 | 做法 | 依据 |
|---|---|---|
| 新增 `RunStore.abandonRunningCombinations(runId)` | 把该 Run 仍为 `RUNNING` 的组合改成 `INTERRUPTED` 并补 `finished_at`，返回行数 | 只按 `run_id + status='RUNNING'` 收，不碰其它状态、不跨 Run |
| `recoverOrphans()` 调用它 | 在标 FAILED 之前调，并往运行日志写一行「上一轮中断时有 N 个组合停在扫描中间，已记为「已中断」」 | 收尾这件事必须留痕，否则人只看到状态变了却不知道发生过什么 |
| 状态名用 `INTERRUPTED` 而不是 `SKIPPED` | 前端加中文映射（未开始/进行中/已完成/已跳过/失败/已中断） | `SKIPPED` 的既有含义是配额到顶、值守到点这类**计划内收手**；与「进程没了」混用会让「为什么跳过」无从回答 |
| 前端再兜一层 | Run 已结束而组合仍是 `RUNNING`（旧数据）时显示「已中断」 | 数据修复只在重启时跑；展示层不该因为库里一行脏数据就把「结束」说成「进行中」 |
| `RunStoreAbandonCombinationsTest` | 4 例：只动 RUNNING、不跨 Run、已 SKIPPED 不被改写、没有 RUNNING 时返回 0 | 待验的是 UPDATE 的**范围**，打桩会把断言退化成「自己塞的值又读回来」 |

取证（run 46，真实强杀）：跑到组合 #1、卡片 193 张时 `taskkill /F` → 重启后
`combination_run` 那行 `RUNNING → INTERRUPTED` 且 `finished_at` 补上、`run_log` 多出两行 WARN、
任务页组合状态显示「已中断」、Run 显示「读取第 4 张卡的岗位详情 + 疑似异常退出」。
全量 **263 例通过**（原 259 + 新增 4）。

**没覆盖到的三处**（不是通过，是没取证）：

1. 概览的零状态卡（「今天还没有跑过」）：真实库里两个平台今天都跑过，这一分支只能靠代码读；
2. driver 红灯 vs browserReady 红灯的视觉区分：driver 当前就绪，无法造故障；
3. 企业微信真实发送：webhook 未配置，只验到日志里的标题拼接与 4 个单测。

### 阶段 3：收尾

| 项 | 内容 |
|---|---|
| 范围 | 配置页加「平台开关」；`ai_reply_max_per_sweep` 文案改为「每个平台各自的上限」；ADR-0003 `Open Questions` 四条假设的结论回填文档 |
| 验收 | 关掉一个平台后，另一个照常跑且不再开第二个浏览器 |
| 依赖 | 阶段 2 |
| 回滚 | 都是配置与文案 |

**阶段 3 实施记录（2026-10-31，真实服务 + 进程/库/接口取证）**

`platform_config.enabled` 这个字段从建表就有，阶段 2 只是「读出来显示」，阶段 3 把它变成真的闸门。
新增 `PlatformSwitches`（判定与文案收在一处），四处落闸：

| 落点 | 拦什么 | 为什么在这个位置 |
|---|---|---|
| `BrowserSession.ensureReady()` | 停用平台不再被拉起浏览器 | 它是启动链路唯一必经点。漏在调用点上各判一次，漏一处就是「关掉的平台又开了个 Chrome」 |
| `RunService.start()` | 停用平台不新建任务 | 放在编排层而不是控制器：唤醒、重试、未来的任何入口都过这道闸 |
| `RunServices.wakeWaitingRuns()` | 停用平台不被 30 秒轮询叫醒 | 唤醒的终点就是开浏览器 |
| 系统页三个入口（自检 / 探测登录 / 打开登录页） | 一律 400 且文案点名平台 | 这三个动作的共同点正是「会去开那个平台的浏览器」 |

另加两条守卫，都是为了不让开关变成半开：

- **还有未结束的 Run 时不允许停用**（`ConfigController`）：否则关掉的只是「下一次启动」，当前那条还在跑、浏览器还开着；
- **停用的同一刻收掉它已开的浏览器**：这正是「不再开第二个浏览器」要的效果。

前端：配置页「平台配置」tab 的作用域条上加开关（与其余字段同批保存 —— 单独给开关做即时保存，会出现
「城市没保存、开关却已经关了」的错位），停用后果写在开关正下方；概览卡显示「已停用」并禁用按钮
（阶段 2 已埋好，这次只是把它接上真数据）；系统页三个按钮对停用平台置灰；分段控件上出现「已停用」小标。
`ai_reply_max_per_sweep` 的说明补了「每个平台各自算一份，N 个平台一轮最多 N × 该值」（ADR-0003 第 4 条）。

取证（同一台机器、同一个服务进程）：

- 停用智联后，启动 / 探测登录 / 打开登录页 / 自检 **四个入口全部 400**，文案都是
  `平台「智联招聘」已停用：不会为它开浏览器，也不会启动它的任务。要跑它请先在「配置 → 平台配置」里启用`；
- 停用 BOSS 时它正挂着一条 `WAITING_DUTY` 的 run 47 → 400
  `「BOSS 直聘」还有未结束的任务（#47，WAITING_DUTY）：请先停止它，再停用这个平台`；取消后再停用 → 200；
- 只启用 BOSS 并打开登录页：进程里只有 `browser-data\boss` 那一棵树（msedge 13 个，zhaopin 为 0），
  `bsk browsers` 只列出 BOSS 那一个自动化实例（`9a198826`，EXT 0.3.3，即 boss 的 profile）；
- 库：`platform_config` 的 `enabled` 随开关走（boss=1 / zhaopin=0 与反向都验过）；
- 代码：`PlatformSwitchesTest` 4 例（放行 / 拒绝并点名平台 / 文案带平台名 / 大小写归一），全量 **267 例通过**。

---

## 5. 风险与验收

### 5.1 阶段级风险

| 阶段 | 风险 | 处置 |
|---|---|---|
| 1 | 智联改版导致选择器失效 | 选择器集中在 `ZhaopinSelectors` 一个类里，与 `BossSelectors` 同构；失效时只改这一个文件 |
| 1 | 卡片文本读不到，关键词漏命中 | 退化为"回一句话"而不是"发简历"。兜底：适配器把卡片归一化成可读文本，编排层不动 |
| 1 | 投递有每日上限或风控（U1/U5） | 首次真实投递后观察；配额配置项按平台已有，可直接压到很低试探 |
| 2 | 两条线程操作各自的浏览器，但共享 Spring 单例资源 | `ChatClient`、`SecretCipher`、各 `*Store` 需确认无状态；`AiBudget` 是方法内局部变量，天然隔离 |
| 2 | 日志交错难以排查 | 2c 强制加平台前缀，否则不做 2b 上线 |
| 2 | 内存翻倍（每个浏览器 400–600MB） | 不预设并发上限；吃紧时先给平台开关，再考虑上限 |

### 5.2 整体验收清单

勾选只给「有证据」的项；没取证的一律留空并在后面写清差在哪一步。

- [x] 智联单平台全链路跑通 —— **扫描 / 投递 / 值守三项有真实数据，被动发简历没有**：
      扫描 54 个岗位、今日 `DELIVERED` 2 条（run 44）、值守处理过未读并落了 1 条 `AI_SKIPPED`；
      `REPLY_RESUME` 全库 **0 条**（两个平台都是），所以第 5 条链路没被走过
- [x] 两平台同时运行时，任一平台的浏览器被手动关闭，另一个不受影响 —— 2b 两个方向都测了
      （杀 zhaopin 的浏览器树 → boss 照跑；反过来 cancel boss → zhaopin 仍在读第 4 张卡）
- [x] 「今日主动投递」与 `delivery` 表当日 `DELIVERED` 条数相等 —— 今日 boss 0 / 0，zhaopin 2 / 2
- [ ] 「值守发简历数」与 `reply_log` 当日 `REPLY_RESUME` 条数相等 —— **两边都是 0，等号成立但没有内容**。
      口径本身被 `RunStoreDeliveredTodayTest.值守发简历不进主动投递` 锁住，缺的是一次真实发生
- [x] `grep -r "if (platform" server/src/main/java/com/chatjob/{orchestrator,watcher,evaluate}` 为空 ——
      唯一命中是注释里那句「本类不做任何 if (platform == ...)」
- [x] 杀掉服务重启后，两个平台的 Run 状态正确 —— 强杀三次取证：run 45 / 46 被标 `FAILED / SERVICE_RESTART`，
      组合收成 `INTERRUPTED`；挂在 `WAITING_DUTY` 的不算孤儿，由唤醒接着跑（设计如此）

---

## 6. 最终审查（2026-10-31）

### 哪些计划被现实推翻

1. **岗位身份来源**（阶段 1）：计划读列表内嵌 `positionList`，实际必须点开卡片读右侧面板 ——
   首屏只有 20 条，滚动后 DOM 有 195+ 张卡。
2. **2c-2 的后端写路径其实在 2b 就做完了**：spec 把它排在 2c，但 `RunController` 按 `{id}` 反查平台、
   `POST /runs` 带 `platform` 在 2b 已顺手落地。2c 真正剩下的是前端 + 通知带平台名。
3. **通知只加标题前缀不够**（2c）：原节流键按 `kind`，BOSS 熔断后 5 分钟内智联熔断会被合并掉 ——
   两条流水线被当成同一件事。改了契约而不是文案。
4. **终止态的「当前动作」是过去时**（2c 复查）：`current_action` 存的是最后一次动作，
   已结束的 Run 照着显示就成了「已取消的卡上写着正在等 9 秒」。
5. **组合不会自己收尾**：收尾写在 `finally` 里，进程没了就不执行，而重启收尾只修 Run、不看组合表。

### 哪些取舍在真实运行中显出问题

- **profile 分目录的代价被低估了**：独立 profile 不只是「要重新扫码」，bsk 扩展、崩溃标记、登录态
  全部要从零来一次。它曾经被误判成「bsk 观测能力退化」。
- **强杀浏览器留下的 msedge 子进程**会让该平台后续每次启动都报 `session closed`，现象极像装配错误。
  判据是看进程，不是看日志。
- **`WAITING_DUTY` 不算孤儿**这条是对的，但它意味着重启后界面上会留一条「等待时段」的 Run，
  直到时段到了它自己醒来 —— 不是 bug，可解释性要靠概览卡上的「取消等待」。

### 下一个平台接入还差什么

按现在的形状，接第三个平台要动的地方：`PlatformInfo.KNOWN` 加一个 slug + 元信息、
写一个 `PlatformAdapter` 实现（含 `ZhaopinSelectors` 那样的选择器类）、`browser-data/<slug>` 里扫码登录一次、
配置页里填它的城市与关键词。**编排层 0 行** —— 这个目标阶段 2b 达成，阶段 3 没破坏它。

### 还开着的口子

1. **被动发简历从未真实发生过**（ADR-0004 第 5 条、ADR-0003 Open Q3）：下一次任一平台真的收到简历索要时
   必须补验：`reply_log` 是否落 `REPLY_RESUME`、「值守发简历数」+1 而「今日主动投递」不变、
   智联工具栏按钮会不会置灰。
2. **概览的零状态卡**（「今天还没有跑过」）没被真实数据走到过 —— 两个平台今天都跑过。
3. **driver 红灯与 browserReady 红灯的视觉区分**没取证 —— driver 当前就绪，造不出故障。
4. **企业微信真实发送**没取证 —— webhook 未配置，只验到日志标题与 4 个单测。
