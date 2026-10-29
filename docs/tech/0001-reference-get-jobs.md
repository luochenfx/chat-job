# 参考实现分析：get_jobs

> 对象：`C:/development/workspace/get_jobs`（Spring Boot 3.5.16 + Java 21 + Playwright Java 1.62.0 + MyBatis-Plus 3.5.9 + SQLite + Next.js 静态导出）
> 用途：为 chat-job 提供可复用的实现依据。本文只记「能被验证的事实」和「我们据此做的修正」，不记推测。

---

## 1. 它解决了什么

92 个 Java 文件，覆盖 Boss / 猎聘 / 51job / 智联四个平台，Boss 是唯一维护完整的（其余三个 README 标注停止维护）。核心价值在于 **Boss 平台从登录到投递的完整链路都跑通了，且把踩过的坑写在代码注释里**。

与 chat-job 的关键差异：

| 维度 | get_jobs | chat-job |
|---|---|---|
| 平台数 | 4（Boss 完整，其余停止维护） | 1（只 Boss） |
| 任务模型 | 无实体，`isRunning` 内存标志 | Run / CombinationRun / DeliveryAttempt + 断点 |
| 配额 | **未实现**（只有平台提示检测） | 30/日可配 + 评估 150/日 |
| 去重 | 只做入库去重，靠平台「继续沟通」按钮兜 | 岗位维度 + 状态判定 |
| 值守 | **未实现** | 主循环内检查点 |
| 凭据 | **明文**，接口直接返回 API Key 与 Cookie，无鉴权 | DPAPI 加密 |
| 建表 | 无迁移工具，release 模板 + 运行期补建 + 整表搬迁 | Flyway |
| 前端 | Next.js 静态导出，后端托管 | Nuxt 3 静态导出，后端托管 |

结论：**执行层的实现直接可搬；上层（任务、配额、去重、值守、凭据）它没有，要自己写。**

---

## 2. 直接采纳

### 2.1 patchright driver 装配（最重要的一条）

上一轮标为「可能推翻方向」的风险，实际路径已经被验证：

- `build.gradle.kts:88-130` 的 `installPatchrightDriver` 任务：`npm install patchright-core@1.62.1 --prefix <buildDir>/patchright-driver`，然后把 `node_modules/patchright-core` 整体拷到 `<driverDir>/package`，要求 `<driverDir>/package/cli.js` 存在。
- 注入靠 **JVM 系统属性 `playwright.cli.dir`**，node 走 **环境变量 `PLAYWRIGHT_NODEJS_PATH`**（`PlaywrightManager.java:406-432`）：

```java
if (System.getProperty("playwright.cli.dir") == null) {
    Path driverDir = locatePatchrightDriver();
    if (driverDir != null) System.setProperty("playwright.cli.dir", driverDir.toString());
    else log.warn("未找到 patchright driver，将退回官方 driver-bundle（反检测能力大幅下降）");
}
Map<String, String> env = new HashMap<>();
if (System.getProperty("playwright.cli.dir") != null && System.getenv("PLAYWRIGHT_NODEJS_PATH") == null) {
    String nodePath = locateNodeExecutable();
    if (nodePath != null) env.put("PLAYWRIGHT_NODEJS_PATH", nodePath);
}
return env.isEmpty() ? Playwright.create() : Playwright.create(new Playwright.CreateOptions().setEnv(env));
```

- **版本组合已被验证可用**：`playwright-java 1.62.0` + `patchright-core 1.62.1`。`build.gradle.kts:36-38` 明确警告：不要降到 1.51，`patchright-core 1.51.3` 的 `locator.count()` 是坏的（`_progress is not defined`）。→ 我们的 spec 写的是 1.62.x，参考实现更精确地落在 **1.62.0 + 1.62.1**。
- driver 目录查找有多个候选项（`build/patchright-driver`、项目根 `patchright-driver`、父目录 `build/patchright-driver`），判定标准就是 `package/cli.js` 存在。

**对 chat-job 的直接影响**：spec 的 P0 Spike 从「方向性验证」降级为「照搬构建任务 + 跑一次自检」。ADR-0001 可以补上具体机制。

### 2.2 浏览器启动参数

`PlaywrightManager.java:303-332` 的清单比我们之前写的更完整，多出四个稳定性参数：

```java
context = playwright.chromium().launchPersistentContext(USER_DATA_DIR,   // 项目根 browser-data/
        new BrowserType.LaunchPersistentContextOptions()
                .setChannel("chrome").setHeadless(false).setSlowMo(50).setViewportSize(null)
                .setChromiumSandbox(true)
                .setArgs(List.of("--start-maximized", "--test-type",
                        "--disable-session-crashed-bubble", "--hide-crash-restore-bubble",
                        "--no-first-run", "--no-default-browser-check",
                        "--no-proxy-server", "--proxy-bypass-list=*",
                        "--disable-blink-features=AutomationControlled")));
```

`--test-type` / `--disable-session-crashed-bubble` / `--hide-crash-restore-bubble` / `--no-first-run` 不是反检测，是**防崩溃恢复弹窗把 CDP 挂死**。配套还要改 profile 的 `Preferences`：

`PlaywrightManager.java:227-262` — `profileNode.put("exit_type","Normal"); profileNode.put("exit_cleanly", true);`

### 2.3 线程模型（我们之前的 spec 缺这一条）

Playwright Java **非线程安全**。参考项目把全部调用收敛到单线程 executor `playwright-thread`，并提供 `callOnPlaywright`；嵌套调用就地执行防自锁（`PlaywrightManager.java:139-225`）。注释写明了症状：

> 原来同时从 main、ForkJoinPool、scheduling-1、http-nio 多个线程操作同一个 BrowserContext，会随机爆出 `Object doesn't exist: request@/response@`，以及导航被打断的 `net::ERR_ABORTED`。

监控类任务用 `tryRunOnPlaywright`——**忙就丢这一轮，不排队**，避免心跳把投递挤死。

### 2.4 等待纪律（本项目最大的类问题）

| 规则 | 证据 | 原因 |
|---|---|---|
| `waitForLoadState` 必须显式超时 | `PlaywrightManager.java:104-108` | 无超时会无限等 |
| 只能等 `DOMCONTENTLOADED` | 同上 | SPA + WebSocket 长连接，`NETWORKIDLE` 可能永远不到 |
| `locator.isVisible()` 一律包一层 5s 超时 | `:619-632` | 无默认超时，Boss 首页客户端跳转导致 frame 反复重建，实测焊死初始化线程 2 分钟以上 |
| `navigate` 45s + 重试 1 次 + 先判断「是否其实已到位」 | `Boss.java:785-828` | 受控标签页下首页 navigate 要 60s |
| 打开列表只重试 2 次 | `Boss.java:881-900` | 算过账：3 次会让单关键词耗几分钟，界面像卡死 |

### 2.5 搜索与抓取

- **不模拟 UI 搜索，直接拼 URL**（`Boss.java:69-77`，`USE_UI_SEARCH=false`）。注释：Boss 首页在受控标签页里一直转圈加载不完，navigate 要 60 秒，期间 evaluate / locator 全部无限期阻塞；岗位列表页在同一个浏览器里是秒开的。
- URL 参数：`city`（单值）、`jobType`（单值）、`query`（`URLEncoder` UTF-8）、`salary / experience / degree / scale / industry / stage`（**逗号分隔多值**）。`UNLIMITED_CODE = "0"` 或"不限"时**整个参数不拼**（`JobUtils.java:12-31`）。
- 抓取用 `waitForResponse(url.contains("/wapi/zpgeek/job/detail.json") && GET, card::click)`，取 `zpData.jobInfo / brandComInfo / bossInfo`（`Boss.java:339-411`）。
- **第一张卡片默认展开、不会触发详情请求** → 必须先点第二张再点回第一张，并在"点回第一张"时挂监听（`Boss.java:341-352`）。这一条如果不看代码几乎不可能想到。
- 卡死兜底：`Object doesn't exist` / `Execution context was destroyed` 视为假异常重试，滚动期累计超过 20 次放弃（`Boss.java:865-875`、`:302-312`）。

### 2.6 滚动判据（与我们之前定的完全一致，等于被验证）

`Boss.java:246-316`：footer 可见**且**连续 2 轮无新增 → 结束；连续 8 轮无新增 → 结束；300 轮硬上限。三个细节要抄：

1. **footer 不能立刻当"加载完"**——窗口最大化时首屏很短，第一轮 footer 就可见，所以必须叠加"滚过 + 连续 2 轮无新增"。
2. **有的版式根本没有 `#footer`** → 只等 footer 是死循环，必须有"连续 8 轮无新增"。
3. 连续 3 轮无新增时**强制 `scrollTo(0, document.body.scrollHeight)` 一次**，再继续判。

### 2.7 聊天页状态机（BossChatNavigator）

`BossChatNavigator.java:13-102` 把"点按钮 → 聊天页"做成了一个带超时的状态机，而不是三步直线。四条规则必须抄：

1. **确认框只在点击后 1 秒内找**（`confirmationDeadline = now + 1s`），且只点一次。
2. **"已向BOSS发送消息"这种确认框不是"旧会话"的证据**，首次沟通也会弹它——误判会把新会话当成重复沟通。
3. **旧页面的输入框即使可见也不能用**，必须等新聊天页（否则消息发到错误页面）。
4. **每轮重建 locator**，不要用导航前的 frame 引用。

> 参考项目为这段写了离线 DOM 测试（`BossChatNavigatorDomTest.java`），覆盖"同标签页/新窗口 × 有/无弹框""旧输入框不能误用""1.4s 后才出现的弹框不点"。这个测试方式我们直接复用。

### 2.8 SSE

`SseEmitter(0L)` 永不超时 + 30s 心跳（事件名 `ping`）+ 固定事件名 `progress` + 断连识别 `AsyncRequestNotUsableException / ClientAbortException / 中止了一个已建立的连接`。前端用指数退避 SSE 客户端（1000ms 起，×1.7，上限 30s，30% 抖动）。

**但参考项目的前端只订阅了登录态 SSE，进度 SSE 服务端实现了却没人消费**——我们别复制这个。

### 2.9 反检测的负向经验

- **不伪造 UA**（`PlaywrightManager.java:333-334`）：伪造 UA 只改字符串，改不了 `navigator.platform` 和 `navigator.userAgentData`，反而制造"UA 说 Mac、platform 说 Win32"的矛盾。
- **不加 `--no-sandbox`**：它本身就是自动化特征（`:311`）。
- **强制直连**：`--no-proxy-server` + `--proxy-bypass-list=*`。
- 旧的 `launch + newContext + addInitScript` stealth 方案已被整体删除（`PlaywrightUtil.java:9-12`）。
- 自带指纹自检接口 `GET /api/playwright/fingerprint`，检查 `navigator.webdriver / userAgentData / cdc_ 残留`——**这正好是我们要做的启动自检**。

---

## 3. 必须避开的缺陷

### 3.1 现存 bug（照抄会踩）

| # | 位置 | 问题 |
|---|---|---|
| D1 | `Boss.java:1810-1832` | 滑块验证 `continue` 放在超时判断之前 → **5 分钟超时永远不可达**，且 `System.in.read()` 在无控制台 jar 下立即返回，变成忙等 |
| D2 | `Boss.java:1128-1135` | **文字消息发送成功判据极弱**：只要发送按钮存在并点击成功就记成功，不校验气泡出现 → 大量"假成功" |
| D3 | `BossService.java:615` | 表结构变更走**整表搬迁**（建新表 → 复制 → DROP → RENAME），DROP 后失败只 warn + ROLLBACK |
| D4 | `Boss.java:1703` | prompt 用 `String.format` → prompt 里出现 `%`（如"提升 100%"）直接抛异常，静默退回默认招呼语 |
| D5 | `AiService.java:51-53` | 只设 connectTimeout，**没有读超时** → 读响应可无限等待 |
| D6 | `Boss.java:231` | `shouldStopCallback.get()` 未判空 |
| D7 | `BossJobService.java:137-145` | 停止看门狗 15s 后强置 `isRunning=false`，但线程还在跑 → 界面可再次启动，两个任务竞争同一 context |
| D8 | `Boss.java:1565-1601`、`:1685-1692`、`:136-225` | 薪资过滤、deadStatus 配置、黑名单自学习**全是死代码**（定义了从未调用），README 宣称的功能没接线 |
| D9 | `PlaywrightController.java:151-162` | 在 http 线程直接调 `page.navigate()`，绕过专用线程 |
| D10 | `ConfigController.java:29-47`、`CookieController.java:41-49` | 接口明文返回 API Key 与完整 Cookie，`@CrossOrigin("*")` 且**无任何鉴权** |
| D11 | `application.yaml:14-22` | SQLite **没开 WAL / busy_timeout**，Hikari 只有 5 连接，表迁移在任务启动时执行 → 并发有锁风险 |
| D12 | `Boss.java:1232-1245` | 详情 JSON 无限追加写 `target/job.txt`，无大小/轮转 |

### 3.2 架构层面的缺失（我们不跟）

- 无 Run 实体 → 无断点续跑，每次从第一个城市/关键词重来。
- 无配额 → 靠平台提示兜底（只有 51job 做了"日投递上限"toast 检测）。
- 去重靠平台按钮文案 + `allowRepeatChat` 开关，**不靠本地记录**。
- 表结构靠 release 里的 `getjobs.db.template` + 运行期零散 `CREATE TABLE IF NOT EXISTS`；**没有 Flyway**。
- Cookie 与 API Key 明文，设计文档里写的"对称加密存储"未实现。

### 3.3 他们踩过的运营坑（数字很有价值）

- 随机延迟实现：`[wait_time/2, wait_time]`，`wait_time` 默认 10s，调试模式固定取上限（`Boss.java:1750-1791`）。
- 注释原话：**「上一轮实测 7 分钟连刷 297 个岗位详情，直接把 Boss 风控触发了，后续关键词全部被弹到安全验证页」**——这是无节流情况下触发的数据点。
- 逐字输入 `pressSequentially` 140ms 间隔（`Boss.java:726-727`）。
- 每点 5 张卡就下滑 140px，避免点下方卡片触发列表刷新（`Boss.java:455-461`）。
- 验证码/安全页处理是**阻塞等人按回车**（`Boss.java:1793-1832`），不是熔断。我们改成熔断 + 告警。
- 端口坑：`application.yaml:28-29` 注明别用 9679，落在 Windows Hyper-V 保留段 9628-9727 里绑不上。

---

## 4. 对 chat-job 的具体修正

| # | 原设计 | 修正为 | 依据 |
|---|---|---|---|
| 1 | P0 Spike 是方向性 Go/No-Go | 降级为「照搬 `installPatchrightDriver` + 跑一次指纹自检」，风险从"可能推翻方向"降到"配置问题" | §2.1 |
| 2 | 只约束「单 worker」 | 补：**所有 Playwright 调用收敛到单一专用线程**，监控类调用忙则丢弃 | §2.3 |
| 3 | 等待无统一纪律 | 补：显式超时 + 只等 `DOMCONTENTLOADED` + `isVisible` 包装 + navigate 45s | §2.4 |
| 4 | 抓取直接 `waitForResponse` | 补：**首卡点 2 回 1**；假异常重试；滚动期 stale > 20 次放弃 | §2.5 |
| 5 | 发送成功 = 等文本消息出现 | 保持（比参考实现更严），并把判据结构抄过来 | §3.1 D2 |
| 6 | 「进会话读消息列表再发」 | 补 `BossChatNavigator` 四条规则（1s 确认窗 / 不把"已发送"当旧会话 / 拒旧输入框 / 每轮重建 locator） | §2.7 |
| 7 | 随机间隔 4–9s | 改为 `[waitTime/2, waitTime]`，`waitTime` 默认 **10s**（即 5–10s），可配 | §3.3 |
| 8 | 评估上限 150/日 | 保留 150，但**必须与每卡 5–10s 间隔绑定**；建议每 50 个评估插入一次长休（60–120s） | §3.3 |
| 9 | 端口 8080 | 保留（已核实本机排除段为 5357 / 50000-50059 / 52875-58648，不含 8080），但启动时做一次端口可绑校验 | 本机验证 |
| 10 | Flyway 做迁移 | 保留。Flyway 自带 SQLite 支持（file 型数据库无需额外模块），但 SQLite 的 `ALTER TABLE` 能力弱，复杂变更必须手写"建新表 → 搬迁 → 改名"脚本，并禁用 `DROP` 前无备份的写法 | 检索 + §3.1 D3 |
| 11 | AI prompt 用模板 | 明确**不用 `String.format`**，用 `{{placeholder}}` 替换 | §3.1 D4 |
| 12 | AI 调用超时 20s | 补：**connectTimeout 与 readTimeout 都要设** | §3.1 D5 |
| 13 | 启动自检 | 直接复用参考项目的指纹自检检查项：`navigator.webdriver` / `userAgentData` / `cdc_` 残留 | §2.9 |

---

## 5. 未解问题

### 5.1 需要实测（代码回答不了）

| # | 问题 | 影响 |
|---|---|---|
| M1 | BOSS 当前「今日沟通次数上限」的真实阈值 | 决定配额 30 是保守还是激进 |
| M2 | **有 5–10s 间隔**时，单日点多少个岗位详情会触发安全验证（参考项目只有「无间隔 7 分钟 297 个」这一个数据点） | 决定评估上限 150 是否安全 |
| M3 | 搜索结果瀑布流的真实深度（到底是多少条） | 决定单组合是否需要处理上限 |
| M4 | 发出招呼语后，聊天区"本人消息"的 DOM 结构是否仍是 `li.message-item.item-myself` | 决定 `waitForDelivered` 判据写法 |
| M5 | 首次点「立即沟通」时，平台是否已自动发出预设招呼语（你说受配置影响会变） | 已用「先读消息列表」兼容，但需要实测确认读到的历史消息边界 |

### 5.2 需要决策（第一性原理，参考实现给不了答案）

| # | 问题 | 为什么代码答不了 |
|---|---|---|
| P1 | **值守回复的关键词策略具体是什么**：什么消息回、回什么、什么情况装死 | 参考项目完全没有值守功能，这是 chat-job 独有的价值主张 |
| P2 | 「AI 质量判定」关闭后是否真的只有 dry-run 一种用途 | 这取决于你会怎么用这个开关，不是技术问题 |
| P3 | 要不要采集 HR 回复率/回复时长作为效果反馈 | 决定是否新增统计表与页面；参考项目未采集 |
| P4 | 日配额 30 是否改成**按周配额**（应对某天没有合适岗位时的浪费） | 属于产品取舍 |
| P5 | 字典数据（城市 / 行业 / 薪资 / 经验 / 学历 / 规模 / 阶段 的 code）从哪来 | 参考项目的字典在 release 的 `getjobs.db.template` 里，**源码中没有**，需要你提供该库或自建抓取 |

### 5.3 需要外部输入

| # | 事项 |
|---|---|
| E1 | ~~`getjobs.db.template` 是否在手边~~ **已解决**，见 §6 |
| E2 | 智联的登录探测与发送判据（本期不做，但参考项目有实现，将来接入时可直接读） |

---

## 6. 字典与配置形状（读 `db/getjobs.db` 实得）

源库：`C:/development/workspace/get_jobs/db/getjobs.db`（225 KB，实为 `getjobs.db.template` 改名）。提取工具：`tools/extract-boss-dict.py`。

### 6.1 `boss_option` 是完整的（579 条 / 8 类）

| type | 条数 | 样例 |
|---|---|---|
| city | 375 | 鞍山 `101070300`、阿拉善盟 `101081200` |
| industry | 161 | 互联网/AI `100000`、电子商务 `100001` |
| experience | 9 | 应届毕业生 `102`、经验不限 `101` |
| stage | 9 | 未融资 `801`、天使轮 `802` |
| degree | 8 | 中专/中技 `208`、高中 `206` |
| salary | 7 | 3K以下 `402`、5-10K `404` |
| scale | 7 | 0-20人 `301`、100-499人 `303` |
| jobType | 3 | 全职 `1901`、兼职 `1903` |

每一类都含「不限 / `0`」，对应 URL 拼接时「整个参数不拼」的规则。**城市只有 375 个**（不是 1494），覆盖大中城市，MVP 够用；如果要小城市需要另外补。

> 实体类里存在但没有实际数据表：`boss_industry`。行业实际在 `boss_option` 的 `type='industry'` 里，别被实体类误导。

### 6.2 配置存的是**可读名称**，不是 code

`boss_config` 的真实取值：`city_code = "福州"`、`industry = "[互联网,互联网/AI,电子商务,计算机软件]"`、`salary = "[20-50K,10-20K]"`。

也就是说**配置层面向人（中文名），执行层才映射成 code**。这个分层要认真抄：配置页面让你填「福州」，代码在执行时查 `platform_option` 换成 `101010100`。好处是配置可读、字典换了也不影响人填的内容。

其他值得抄的字段：

| 字段 | 说明 |
|---|---|
| `wait_time` | 默认 10，随机取 `[wait_time/2, wait_time]` |
| `enable_ai` + `say_hi` | **关闭 AI 时退回 `say_hi` 固定文案**——正是我们要的质量开关语义 |
| `allow_repeat_chat` + `repeat_chat_messages` | 命中「继续沟通」时的随机跟进语，默认 6 条 |
| `expected_salary_min/max` | 期望薪资，用于话术，未参与筛选 |
| `debugger` | 调试模式，随机延迟固定取上限 |

`repeat_chat_messages` 的真实样本（可直接用作默认值）：

```
请问是有什么顾虑需要沟通吗？
我可以去贵公司面试吗？
请问贵公司还招人吗？
对于这个岗位有什么技术需求呢？
我可以把我的简历发给您看看吗？
能问一下贵公司办公地点在哪里吗？需不需要经常出差
```

### 6.3 HR 活跃度的真实字段与文案粒度

字段是 `bossInfo.activeTimeDesc`（字符串，不是枚举）。参考实现的做法是**子串匹配**：默认逻辑简化为「活跃描述包含『年』即视为不活跃」（`Boss.java:423-427`），可配的 `dead_status` 默认值是 `["2周内活跃","本月活跃","2月内活跃","半年前活跃"]`。

由此反推文案档位为：在线 / 刚刚活跃 / 今日活跃 / 3日内活跃 / 本周活跃 / 本月活跃 / 2月内活跃 / 半年前活跃。

**结论：7 天阈值在文案粒度上不可执行**——文案里没有「7 天」这一档。spec 已改为按档位配置，默认「本月」。参考实现「含『年』才算死号」比我们宽松得多，但它同时也漏掉了「2月内活跃」这类明显低效的岗。

### 6.4 AI 提示词的真实写法（反面教材）

`ai.prompt` 用 `%s` 占位，配合 `String.format` 使用：

```
我目前在找工作,%s,我期望的的岗位方向是【%s】,目前我需要投递的岗位名称是【%s】,
这个岗位的要求是【%s】,如果这个岗位和我的期望与经历基本符合…直接返回false给我…
```

两个问题：① 任何字段含 `%` 就抛异常（3.1 D4）；② 判定信号靠**字符串 "false"**，不是结构化字段。我们的 `json_object` + `shouldApply` 是对的。

`config` 表是纯 key-value（`BASE_URL` / `API_KEY` / `MODEL` / `HOOK_URL` / `BOT_IS_SEND`），印证企微机器人 webhook 方案足够，不需要公众号。

### 6.5 安全提醒

`config.API_KEY` 与 `cookie` 表在这份库里是**明文**，且这个库文件会被打包进 release。任何拿到这个 db 的人都能直接消耗你的 API 余额、接管你的招聘账号。chat-job 的 DPAPI 方案不是过度设计；另外建议你**把这把 key 视为已泄露并轮换**，同时不要再把这个 db 文件往外发。

### 6.6 工具用法

```bash
python tools/extract-boss-dict.py                 # 用默认源库
python tools/extract-boss-dict.py --db <路径> --out <目录>
```

产物（已验证：SQL 可在空库上执行成功，重复执行行数不变，即幂等）：

| 文件 | 用途 |
|---|---|
| `tools/out/V2__seed_platform_option.sql` | Flyway 种子迁移，建 `platform_option` 并写入 579 条 |
| `tools/out/platform-option-dict.json` | 全量字典，人工核对 |
| `tools/out/boss-blacklist-sample.json` | 参考项目的 16 条黑名单，**仅参考不要当种子** |
