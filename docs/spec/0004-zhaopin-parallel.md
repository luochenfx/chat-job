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

### 阶段 3：收尾

| 项 | 内容 |
|---|---|
| 范围 | 配置页加「平台开关」；`ai_reply_max_per_sweep` 文案改为「每个平台各自的上限」；ADR-0003 `Open Questions` 四条假设的结论回填文档 |
| 验收 | 关掉一个平台后，另一个照常跑且不再开第二个浏览器 |
| 依赖 | 阶段 2 |
| 回滚 | 都是配置与文案 |

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

- [ ] 智联单平台全链路跑通（扫描 / 投递 / 值守 / 被动发简历各至少 1 次真实发生）
- [ ] 两平台同时运行时，任一平台的浏览器被手动关闭，另一个不受影响
- [ ] 「今日主动投递」与 `delivery` 表当日 `DELIVERED` 条数相等
- [ ] 「值守发简历数」与 `reply_log` 当日 `REPLY_RESUME` 条数相等
- [ ] `grep -r "if (platform" server/src/main/java/com/chatjob/{orchestrator,watcher,evaluate}` 为空
- [ ] 杀掉服务重启后，两个平台的 Run 状态正确（不自动接管，符合 ADR-0002）

---

## 6. 最终审查

（实现完成后回填：哪些假设被推翻、哪些取舍在真实运行中显出问题、下一个平台接入还差什么。）
