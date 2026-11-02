# 人类化交互层：页面操作收敛到单一入口

## 1. 上下文锚定

### 1.1 这是什么

把两家平台适配器里的页面操作——点击、输入、滚动、导航——从"各处自己写"改成"统一经过一层"。业务代码只说要点哪个元素、要输入什么文字、要滚动多少，至于鼠标怎么走过去、字与字之间隔多久、滚轮每次滚多少，由这一层决定。

这一层只改"怎么操作"，不改"操作什么"。扫描、判定、投递、值守的所有分支逻辑一行不动。

### 1.2 为什么要做

| 现象 | 实测 | 后果 |
|---|---|---|
| 同一个动作有多种写法 | 18 处点击里只有 1 处（`ZhaopinAdapter:340-341`）在点之前先 `scrollIntoViewIfNeeded`，其余直接点；输入在普通框用 `fill()`，Boss 的 contenteditable 用 JS 赋 `innerText` | 想统一调整一次节奏，要改 30 处；想加一种新动作，要先决定在哪抄一份 |
| "随机等一会儿"被复制 | `BossAdapter:1141` 与 `ZhaopinAdapter:1086` 是逐字相同的 `pause(int,int)`，实际调用点 26 处 | 改了其中一份，另一份不会跟着变；两份的区间数值已经出现分叉 |
| 滚动方式暴露 | `page.evaluate("() => window.scrollBy(...)")`（`BossAdapter:192`、`:203`、`:230`，`ZhaopinAdapter:250`、`:271`）——JS 改滚动位置**不派发 `wheel` 事件** | 页面上挂一个监听器即可得"被滚过但零次滚轮事件"的结论 |
| 输入方式暴露 | Boss 的输入框：`input.evaluate("(el,msg) => { el.innerText = msg; ... }")`（`BossAdapter:632`）——内容是凭空出现的，只有 `input` 没有 `keydown` | 采集按键事件的一方看到的是一个不成立的编辑动作 |
| 详情页凭空出现 | `deliver` 用 `session.newPage()` + `detailPage.navigate(detailUrl)`（`BossAdapter:386-391`）。从 `about:blank` 出发的顶层导航，HTTP 层是 `Sec-Fetch-Site: none`、无 `Referer`、历史长度 1 | 这是请求头与导航元数据，鼠标轨迹再自然也改不了 |
| 节奏是均匀的 | `slow-mo-ms: 50`（`application.yaml`）给每个动作固定加 50ms | 均匀延迟本身可统计，且会与本层的随机间隔叠加成两种节奏混杂 |

一句话：**问题不是"不够像人"，是"同一件事有六种写法，而且最明显的那个信号不在鼠标层"。**

### 1.3 现状与边界（读代码确认，不是推断）

| 位置 | 现状 | 对本 spec 的含义 |
|---|---|---|
| `PlatformAdapter`（`platform/`） | 12 个方法，全部要求"在 Playwright 专用线程上调用" | 接口不变。改动全在实现内部，这是分期落地的前提 |
| `BossAdapter` | 点击 8、滚动 3、导航 2、输入 2；另有 `BossChatNavigator` 点击 2 | 阶段 2 的主战场 |
| `ZhaopinAdapter` | 点击 8、滚动 3（含 1 处 `scrollIntoViewIfNeeded`）、导航 1、输入 1 | 阶段 4，阶段 2/3 期间代码不动 |
| `pause(int,int)` | `BossAdapter:1141`、`ZhaopinAdapter:1086`，两份副本 | 删副本、改由统一节奏来源供给，**调用点区间数值原样保留** |
| `BrowserSession:150` | `setSlowMo(props.browser().slowMoMs())`，取值为 50 | 归零。慢动作由本层的随机间隔替代 |
| `AppProperties`（`config/`） | `record(driver, browser, selfCheck, notifyConfig, activePlatform)` | 增加 `Humanize` 分量。**四处测试里的 `new AppProperties(...)` 会编译失败**（`PlatformBrowserIsolationTest:65`、`NotifySettingsTest:90`、`NotifyTestDoubles:28`、`PlatformAdaptersTest:36`），必须同步补参 |
| `Models.ScrollParams`（`platform/`） | `record(int roundLimit, int waitTimeSeconds)`；`roundLimit` 两个适配器都读，`waitTimeSeconds` **两个都没读** | 滚动节奏实际来自硬编码 `pause(400,700)`。`waitTimeSeconds` 是未生效的配置项，本期不改它的语义，只登记 |
| `RunService:788` | `scrollUntilEnd(new ScrollParams(cfg.getScrollRoundLimit(), cfg.getWaitTimeSeconds()), ...)` | `roundLimit` 默认 300，语义是"滚 1.5 屏一次算一轮"。改滚轮后必须重新定义"一轮" |
| `RunStore.log(runId, level, message)` | 唯一的日志出口 | 降级与计数汇总都从这里走，不新建表 |
| 数据库 | 最新迁移 `V16` | **本 spec 不动数据库**，参数全在 yaml |
| `src/test` | 31 个测试类，禁 mock 框架，统一 hand-written fakes | 逻辑必须切到"能脱离浏览器测"的一侧 |

### 1.4 术语

`CONTEXT.md` 本次已新增三个词条：**人类化交互**（只回答怎么操作）、**节奏**（间隔是分布而非固定值）、**交互降级**（没达成预期效果就退回原生写法，且必须记录）。本 spec 再补一个：**预热**——一次标签页在被操作之前先产生一段"被观看"的痕迹（光标移入、滚动几小步、停留），只在该标签页首次交互前做一次。

> 三个词的边界要守住：**人类化交互**说"怎么操作"，**节奏**说"隔多久"，**交互降级**说"没成就怎么办"。不要用"拟人""反检测"来称呼任何一条——它们描述的是目的和别人的手段，不是本系统里的行为。

## 2. 约束与目标

### 2.1 硬约束

1. **只允许一个交互入口。** 适配器不得直接调用 `Locator.click` / `fill` / `type` / `press`、`Page.mouse()` / `keyboard()`、写滚动位置的 `evaluate`。由源码扫描测试守住。
2. **读操作不受限。** `count()`、`getAttribute()`、`isVisible()`、读 DOM 结构的 `evaluate` 照常可用——它们不产生输入事件。
3. **点击保留 Playwright 的可操作性检查。** 只替换"鼠标落到元素"这一段，不自算坐标、不做遮挡判断、不做元素重试。
4. **点击失败不得静默发生。** `mouse.down()` 前重取 `boundingBox`，纵向漂移超过 `max(8px, 元素高 × 25%)` 即作废重算，最多 2 轮；用尽后降级为原生 `locator.click()` 并记 WARN，附实际漂移量。
5. **滚动必须有真实 `wheel` 事件。** 滚之前把指针停在列表容器上。
6. **"加载完了没有"的判据不变**，仍数卡片数量，不看滚动距离。
7. **输入保证终态正确。** 成功判据三条同时成立（无异常 / 读回文本与目标一致 / 短时间内无意外 DOM 结构变化）；任一不满足立即降级并记录是哪一条没过。
8. **降级必须有记录。** 每一次降级写一条 `run_log`，WARN 级别，写明动作类型与失败条件。
9. **`slow-mo-ms` 归零。**
10. **`deliver` 的详情页请求来源必须是站内。** 先到列表页，再进入详情页；不得从 `about:blank` 直接跳详情 URL。
11. **节奏参数按分布建模。** 每个参数是区间或高斯，不是单值；提供随机种子，0 为真随机。
12. **总有开关。** `chat-job.humanize.enabled=false` 时全部行为退回改造前，不残留任何语义变化。
13. **不动数据库。** 参数只在 `application.yaml`。
14. **不改 `PlatformAdapter` 接口。** 12 个方法签名一个都不动。

### 2.2 目标（完成的定义）

- 适配器里搜不到任何裸的点击、填写、滚动调用；源码扫描测试通过。
- Boss 完整跑一轮扫描：卡片能点开、详情能抓到、招呼语能发出去、送达判据仍成立，`run_log` 里出现一条本层的交互汇总。
- Boss 的输入框在页面 DevTools 里能看到 `keydown` → `input` 链，而不是只有一次 `input`。
- 列表页滚动期间，页面监听到 `wheel` 事件。
- 投递时详情页请求的 `Referer` 是列表页地址，`Sec-Fetch-Site` 为 `same-origin`。
- 把 `enabled` 关掉重启，行为与改造前完全一致。
- 智联同上（阶段 4）。

### 2.3 非目标（明确不做）

- **不做浏览器指纹相关的事。** `navigator.webdriver`、UA、WebGL、CDP 痕迹由 patchright driver 负责，本层不碰，也不在 `evaluate` 里注入任何隐藏脚本。
- **不做验证码识别、代理轮换、账号池。**
- **不做"点卡片进详情"。** 阶段 3 只做到"先到列表页再 `navigate` 详情"。按岗位在列表里定位到那张卡的能力目前不存在（Boss 卡片没有 `href`，只能靠标题+公司名文本匹配），把它和本层绑在一起会让一个纯交互改造背上一个爬取能力问题。
- **不做参数的控制台 UI。** 参数在 yaml，改配置需重启。
- **不按平台分别配置节奏。** 结构留位置，本期只写全局一份。
- **不改 `wait_time_seconds` 的实际作用。** 它现在是未生效的配置项，改它属于行为变更，另开需求。
- **不建新的指标表或报表。** 计数只进 `run_log`。
- **不给"预热是否有效"下结论。** 无法直接观测，只保证"没有新增限流告警"这个弱证据可查。

## 3. 方案设计

### 3.1 分层：能脱离浏览器测的那一半尽量大

项目不引 mock 框架，而 Playwright 的 `Page` 是巨型接口，自己写一个 fake 要几百行且容易写错。所以这一层切成两半：

```
com.chatjob.driver.human
├── Humanize.java          业务侧唯一的交互面（接口）
├── Humanizer.java         实现：取框、发事件、校验、计数
├── HumanizeConfig.java    yaml 映射，含 Range / Gauss 两种分布
├── Pacing.java            睡眠与采样（接口，测试换记录器）
├── Rng.java               带种子的随机源（uniform / gauss）
├── HumanAim.java          纯计算：落点
├── HumanMotion.java       纯计算：轨迹点集
├── HumanTiming.java       纯计算：字符间隔与错字位置
├── HumanScrollPlan.java   纯计算：滚动步长序列
└── HumanCounters.java     计数
```

纯计算五个（`Aim` / `Motion` / `Timing` / `ScrollPlan` / `Rng`）零 Playwright 依赖，输入参数输出数字，直接单测。`Humanizer` 只剩三件事：取位置、把计算好的数字发出去、事后校验。`Pacing` 是接口，测试里换成记录器，不碰线程。

`Humanize` 接口的形态（不暴露 `Page` 给业务，也不暴露计算细节）：

```java
void warmUp(Page page);                                   // 首次交互前调用，幂等
void click(Locator target, ClickOptions options);         // 人类化点击，失败自动降级
boolean type(Locator input, String text);                 // 人类化输入，返回终态是否正确
void scrollPage(Page page, Locator hotArea, int dy);      // 在 hotArea 上滚 dy 像素
void navigate(Page page, String url, int timeoutMs);       // 带节奏导航
void resetWarmUp(Page page);                              // 页面被复用/重开时清标记
String summary();                                          // 计数汇总，供 Run 结束时写日志
```

`scrollPage` 之所以要 `hotArea`：滚轮作用于"鼠标悬停的可滚动容器"，通用层无法知道哪个是列表。把列表 `Locator` 传进来是平台知识出平台，但这比让通用层猜容器强。

### 3.2 点击

```
1. 若 elements 不在视口：target.scrollIntoViewIfNeeded()
2. box = target.boundingBox()              // 为空 → 直接降级
3. aim = HumanAim.gauss(box, rng)          // 中心 + 高斯偏移，截断在最小可点区
4. HumanMotion.path(pointer, aim) → mouse.move 逐点           // 18–34 步
5. box2 = target.boundingBox()
   |box2.y - box.y| > max(8, box.h × 0.25) → 回到 3，最多 2 轮
6. 微调至 box2 的实际落点 → mouse.down() → 随机 40–120ms → mouse.up()
7. 计数 +1；任一步异常 / 2 轮漂移 → 降级 target.click(options) + WARN
```

两个实现细节：

- **指针位置要自己记。** Playwright 不暴露当前鼠标坐标。每次移动都从上次落点出发，初值取视口内的随机点。若每次都从 `(0,0)` 出发，轨迹起点本身就是特征。
- **"最小可点区"**指把落点限制在元素内缩 10% 的矩形内，避免落在圆角外或贴边处被相邻元素抢走。

### 3.3 滚动

```
1. box = hotArea.boundingBox()   // 为空 → 退化为视口中线
2. HumanMotion 把指针从当前位置移到 box 内随机点
3. HumanScrollPlan.steps(dy) → 每步 mouse.wheel(0, step)，步间 90–260ms
4. 累计位移达到 dy 即停
```

**"一轮"的语义必须重定义。** 现在 `scrollUntilEnd` 的"一轮"是"滚 1.5 屏一次"，`roundLimit` 默认 300。改滚轮后单次位移变小，若不重新定义，"一轮"会退化成一个 300ms 的小步，`roundLimit=300` 的保护直接失效。本层的"一轮"固定为**一次完整的滚动计划，累计位移约 1–1.5 屏**；`ScrollOutcome.rounds()` 的计数口径保持不变，`RunService` 与 `run_log` 的文案都不用改。

**平滑到底的强制动作保留。** `BossAdapter:203` 现在有"连续无新增时强制 `scrollTo` 到底"这一步，改造后改为"一次大位移滚动计划"，不用 JS。

### 3.4 输入

```
1. click(input)                                  // 复用 3.2，保证焦点是真的点进的
2. 判定类型：tagName == textarea/input → 原生框；isContentEditable → 富文本框
3. 原生框：先全选清空（Ctrl+A, Backspace）
4. 逐字符：
     Character.isIdeographic(c) ? keyboard.insertText(c) : keyboard.type(c)
     间隔 = HumanTiming.delayAt(i)                // 55–165ms，每 8–15 字一次长停顿
     mistype：概率命中时先输入一个近似字符 → 停顿 → Backspace → 再输入正确字符
5. 三条件校验：
   a. 全程无异常
   b. 读回文本（textarea/input 取 value，富文本取 innerText 归一化）== 目标
   c. 输入前记 {childElementCount, innerText.length}，静置 300ms 后再记一次；
      childElementCount 增幅超过 2 视为"意外结构变化"
   任一不满足 → 降级：
     富文本：input.evaluate("(el,msg) => { el.innerText = msg; el.dispatchEvent(new Event('input',{bubbles:true})); }", text)
     原生框：input.fill(text)
     记 WARN，写明是 a/b/c 哪一条
6. 计数：交互 +1，降级时 fallback +1
```

**必须写进代码注释的限制**：中文经 `insertText` 插入，只有 `input`、没有 `keydown`。所以中文的节奏只能做到"字与字之间停顿"，做不到每个字都有按键事件。不写清楚，后来的人会以为中文也是逐键输入，并按"`keydown` 全链路"去评估检测规避是否完整。

条件 c 的阈值 2 是初始值，需要在阶段 2 用真实页面标定一次（见 5.3）。

### 3.5 导航

`deliver` 现在是"新页从 `about:blank` 直接跳详情 URL"。改成两跳：

```
page = session.newPage()
human.warmUp(page)
human.navigate(page, listUrl)        // 第 1 跳：搜索列表页，带齐城市/关键词
   → 等列表页出现至少一张卡片，最多 8 秒
human.navigateInPage(page, detailUrl) // 第 2 跳：详情页，此时 Referer 是列表页
```

**第 2 跳必须由当前文档发起（2026-11-02 实测修正）**：本节初稿写的是两次 `navigate`，实测不成立。
`page.navigate` 在协议层是浏览器发起的导航，没有发起方文档，所以**前一个页面是不是同站都不影响**——
两跳之后仍是 `Referer` 空、`Sec-Fetch-Site: none`。四种进入方式的实测表见 ADR-0007 Decision 6。
结论：第 2 跳用页面内跳转（`location.href`），第 1 跳照常 `navigate`（列表页自己没有来源可言）。

`listUrl` 由 `BossUrlBuilder` / `ZhaopinUrlBuilder` 提供；`deliver` 只拿到 `JobDetail`，没有搜索条件，所以需要把 `SearchQuery` 一并传进 `deliver`，或在 `DeliveryOptions` 里带上。**这是本 spec 唯一改动 `Models` 的地方**，只加字段，不改已有字段语义。

第 1 跳失败（列表页 8 秒内没有卡片）时**不阻断投递**，直接跳到详情页并记 WARN——投递成功率优先于来源一致性。

`fetchDetail` 的节奏补齐：现在在列表页连续点 30 张卡片，中间几乎不停。改为每 5–8 张插入一次 800–2000ms 的停顿。这一条不改任何判据，只改间隔。

### 3.6 参数

`application.yaml` 新增一段，与 `browser` 同级：

```yaml
chat-job:
  humanize:
    enabled: true
    seed: 0                    # 0 = 真随机；非 0 可复现同一条轨迹
    mouse:
      steps: {min: 18, max: 34}
      jitter: {kind: gauss, center: 0.5, sigma: 0.15, max-deviation: 0.4}
      drift-px: 8
      drift-ratio: 0.25
      realign-rounds: 2
      hold-ms: {min: 40, max: 120}
    keyboard:
      char-delay-ms: {min: 55, max: 165}
      thinking-pause-ms: {min: 250, max: 900}
      mistype-chance: 0.02
      verify-settle-ms: 300
    scroll:
      step-ratio: {min: 0.35, max: 0.85}       # 视口高的比例
      step-interval-ms: {min: 90, max: 260}
    warm-up:
      enabled: true
      max-duration-ms: 3000
      scroll-steps: {min: 2, max: 4}
      dwell-ms: {min: 600, max: 1500}
    pacing:
      between-actions-ms: {min: 400, max: 900}
```

两种分布，只实现这两种：

| 形态 | 字段 | 用途 |
|---|---|---|
| 均匀 | `{min, max}` | 时长、步数、概率区间 |
| 截断高斯 | `{kind: gauss, center, sigma, max-deviation}` | 落点相对位置 |

`lognormal` 之类不实现——加进去就是没人调的死配置。单位写进字段名（`...Ms` / `...Px`），不靠注释约定。

### 3.7 计数与观测

`HumanCounters` 四个单调递增计数：交互次数、降级次数、漂移作废次数、输入字符数。

跨 Run 的归属用**采样差**，不做重置：`RunService` 在 Run 开始时记一次快照，结束时再记一次，差值写一条 `run_log`（INFO）：

```
人类化交互 412 次 / 降级 3 次（输入 1、点击 2、滚动 0）/ 漂移作废 5 次
```

理由：`Humanizer` 是平台级实例，比 Run 活得久；让 Run 去重置别人的状态会引入时序耦合。降级明细仍按 Q4 逐次记 WARN，汇总只是让人一眼看到比例。

结果侧（投递成功率、详情抓取成功率）沿用现有 DB 数据做改造前后对比，不新建指标。

### 3.8 与现有代码的接缝

- `PlatformBrowser` 从 `{executor, session}` 扩到 `{executor, session, humanize}`，三者成对取——和 ADR-0003 定下的"不许分头注入"一致。
- 适配器构造时接收 `Humanize`，内部把每个 `pause(a,b)` 换成 `pacing.uniform(a,b)`，**区间数值不动**。
- `PlatformAdapter` 12 个方法签名不变；`deliver` 需要的 `SearchQuery` 走 `DeliveryOptions` 新字段。
- 源码扫描测试放在 `src/test/java/com/chatjob/driver/human/HumanizeSourceGuardTest.java`，扫 `src/main/java/com/chatjob/platform/**/*.java`，命中禁列表即失败，白名单只有 `com/chatjob/driver/human/` 自身。

## 4. 落地实施

### 4.1 文件清单

**新增（主代码）**

| 路径 | 作用 |
|---|---|
| `server/src/main/java/com/chatjob/driver/human/Humanize.java` | 业务侧唯一交互面 |
| `…/human/Humanizer.java` | 施加层：取框、发事件、校验、降级、计数 |
| `…/human/HumanizeConfig.java` | yaml 映射，含 `Range` / `Gauss` |
| `…/human/Pacing.java` | 睡眠与采样接口 |
| `…/human/Rng.java` | 带种子随机源 |
| `…/human/HumanAim.java` | 落点计算 |
| `…/human/HumanMotion.java` | 轨迹点集 |
| `…/human/HumanTiming.java` | 字符间隔与错字位置 |
| `…/human/HumanScrollPlan.java` | 滚动步长序列 |
| `…/human/HumanCounters.java` | 计数 |

**新增（测试）**

| 路径 | 覆盖 |
|---|---|
| `…/human/HumanAimTest.java` | 截断边界、中心偏好、0 面积元素 |
| `…/human/HumanMotionTest.java` | 步数、首尾点、单调性、同种子可复现 |
| `…/human/HumanTimingTest.java` | 间隔落在区间内、长停顿出现、错字位置分布 |
| `…/human/HumanScrollPlanTest.java` | 累计和等于目标、单步不超上限 |
| `…/human/HumanizeSourceGuardTest.java` | 适配器内无禁用调用 |

**修改**

| 路径 | 改动 |
|---|---|
| `config/AppProperties.java` | 增加 `Humanize` 记录 |
| `server/src/main/resources/application.yaml` | 增加 `humanize` 段；`slow-mo-ms: 50 → 0` |
| `driver/PlatformBrowser.java`、`driver/BrowserSessions.java` | 成对提供 `Humanize` |
| `platform/Models.java` | `DeliveryOptions` 增加搜索条件；`ScrollParams` 补注释说明"一轮"的语义 |
| `platform/boss/BossAdapter.java`、`BossChatNavigator.java` | 30 处交互改走本层；`deliver` 改两跳；`fetchDetail` 补节奏 |
| `platform/zhaopin/ZhaopinAdapter.java` | 同上（阶段 4） |
| `orchestrator/RunService.java` | Run 首尾采样计数并写汇总日志 |
| 四处测试 | `new AppProperties(...)` 补参 |

### 4.2 分期（每步独立可验证）

**阶段 1：只建骨架，不动行为**

新增十个类与五个测试，`enabled=false` 默认生效但不接线。验收：全量单测通过（现有 267 例 + 新增约 20 例），`mvn package` 通过，运行时行为与改造前完全一致（日志、DB 无差异）。这一阶段的价值是把"能脱离浏览器测的那一半"先钉死，后面接线时不用一边改算法一边改调用点。

**阶段 2：接入 Boss 的点击、输入、滚动**

改 `BossAdapter` 与 `BossChatNavigator`，智联代码一行不动。`enabled` 通过 yaml 控制。验收：Boss 完整跑一轮扫描（至少 10 个岗位、至少 1 次真实投递），四个判据——卡片点得开、详情抓得到、招呼语发得出、送达判据仍成立——全部为真；降级率低于 5%；`run_log` 出现交互汇总。同时标定条件 c 的阈值（3.4）。

**阶段 3：导航改造**

`deliver` 改两跳，`DeliveryOptions` 带搜索条件，`fetchDetail` 补节奏。验收：投递时详情页请求的 `Referer` 是列表页地址、`Sec-Fetch-Site: same-origin`（用 DevTools 或 CDP 抓一次真实请求确认）；投递成功率与阶段 2 相比不下降；单岗位耗时增量在 2–4 秒区间。

**阶段 4：接入智联 + 全量对比**

智联三处滚动、八处点击、一处输入改走本层；`RunService` 加计数汇总。验收：两个平台各跑一轮，降级率均低于 5%；同一搜索组合改造前后的卡片数、详情抓取成功率、投递成功率三项对比无下降。

每阶段结束都必须做一次"`enabled=false` 复跑"，确认关掉之后行为回到原样——否则开关是假的。

### 4.3 测试

| 类型 | 内容 | 是否进 `mvn test` |
|---|---|---|
| 纯计算单测 | 五个纯计算类，输入参数断言输出 | 是 |
| 源码守卫 | 扫适配器包，禁列表命中即失败 | 是 |
| 配置绑定 | `HumanizeConfig` 从 yaml 段绑定、缺省值 | 是 |
| 真浏览器手验 | 点击/输入/滚动的实际效果、`Referer`、降级日志 | 否，人工一次 |
| 前后对比 | 卡片数、抓取成功率、投递成功率 | 否，查 DB |

`Humanizer` 本身不做单测——它是"把数字发到页面"的薄层，fake 一个 `Page` 的成本高于收益（项目禁 mock）。它的正确性靠阶段 2/3 的真实验证。

## 5. 风险与验收

### 5.1 风险 Top 5

| # | 风险 | 判据 | 处置 |
|---|---|---|---|
| 1 | 点击成功率下降，投递量减少 | 降级率 > 5%，或阶段 2 出现"点了没反应" | `enabled=false` 整体退回；再逐个动作放开关 |
| 2 | 滚轮对虚拟列表无效 | 滚 3 次后卡片数不变 | 该平台降级回 JS 滚动，`wheel` 信号放弃，其余不受影响 |
| 3 | 提前到列表页增加搜索请求，触发限流 | 出现新的限流/安全验证告警 | 阶段 3 单独可关；退回从 `about:blank` 出发的旧路径 |
| 4 | 条件 c 阈值不准 | 降级率虚高（太严）或输入被吞却不降级（太松） | 阶段 2 用真实页面标定一次，写进配置 |
| 5 | "一轮"语义没重定义导致轮数保护失效 | 单组合滚动轮数远超 300 | 已在 3.3 固定；若发现轮数异常，先查这条 |

### 5.2 验收口径

本层没有"看起来更像人"这种判据，只有四个可查的数字与一件事：

1. 源码扫描测试通过（收口是否真的收住了）。
2. 降级率 < 5%（实现是否可靠）。
3. 投递成功率、详情抓取成功率不低于改造前（是否打坏了结果）。
4. 页面侧 `wheel` 事件与 `keydown` 事件真实存在（信号是否真的产生）。
5. `enabled=false` 后行为与改造前一致（开关是否是真的）。

### 5.3 未闭环的事实

| # | 事项 | 现状 |
|---|---|---|
| 1 | 两个平台的列表容器能否用现有选择器稳定命中，滚轮在其上真有位移 | 未取证。阶段 2 第一件要验的事 |
| 2 | 中文 `insertText` 是否真的只产生 `input` 不产生 `keydown` | 未取证。按已知行为写进注释，实测后可能要改 |
| 3 | 条件 c 的"意外结构变化"阈值 | 初始 2，待标定 |
| 4 | 按岗位在列表里定位卡片（点卡片进详情） | 不具备。Boss 卡片无 `href`，只能文本匹配，本期不做 |
| 5 | `ScrollParams.waitTimeSeconds` 两个适配器都没读 | 已确认是死配置。本期不动，另开需求 |
| 6 | 预热是否真的改变平台判定 | 无法观测。只能靠"没有新增限流告警"作弱证据 |
| 7 | 从列表页进入详情后，详情页的 XHR 是否带上了预期的 `Referer` | 阶段 3 实测确认 |

## 6. 已确认的决策（2026-11-01）

十五条，逐条对应设计树上的一个分支。左列是问题，中间是决定，右边是当初的取舍依据。

| # | 问题 | 决定 | 依据 |
|---|---|---|---|
| Q1 | 收口还是可选 | **强制收口**，留显式逃生口 | 目标是消除散落，只有单入口能靠类型与测试守住 |
| Q2 | 谁算点击坐标 | 保留 Playwright 检查，只换鼠标段 | 点得中是投递量上限；列表会重排，自算坐标失效概率高 |
| Q3 | 滚动的通道 | 真实滚轮事件；到底判据仍是 DOM 计数 | JS 滚动不派发 `wheel`，是低成本检测信号 |
| Q4 | 输入的语义 | 终态正确 + 可配错字回删；富文本走真键盘 | 凭空赋值只有 `input` 没有 `keydown` |
| Q5 | 要不要 `humanNavigateToDeepPage` | **不要**。改为协议层修正 + 通用 `navigate` | 最明显的信号在请求头，不在鼠标层 |
| Q6 | 参数放哪 | yaml，按分布建模，带种子 | 算法参数、Playwright 线程上零 IO；库里放就要配 UI 与迁移 |
| Q7 | 详情页从哪跳 | 先到列表页再进详情 | 修正 `Sec-Fetch-Site` 与 `Referer`；代价是每岗位一次列表页加载 |
| Q8 | 怎么保证不回退 | 源码扫描测试 | 不引 ArchUnit，几百毫秒；防的是三个月后的新裸调用 |
| Q9 | 代码怎么切 | 纯计算 + 施加层 | 禁 mock 且 `Page` 是巨型接口，逻辑必须挤到能测的一侧 |
| Q10 | 分布参数的形态 | 默认均匀区间；落点用截断高斯；实现两种 | 只实现用得到的；`lognormal` 是死配置 |
| Q11 | 漂移阈值与放弃边界 | `max(8px, 25%)`，最多 2 轮，之后降级并记 WARN | 两轮还在动说明页面持续变化，继续等只吃值守时段 |
| Q12 | 预热的粒度 | 按 `Page` 惰性触发，每页一次，上限 3 秒 | 跟着页面生命周期走最贴合语义；按 Run 算是失效的缓存 |
| Q13 | 两份 `pause()` 怎么收 | 删副本改走统一节奏来源，**区间数值原样保留** | 目标是收口不是重调参数；混在一起出问题分不清是谁改的 |
| Q14 | 节奏是否按平台分 | 本期只写全局一份 | 覆盖是"被限流后"的手段，现在没有这个事实 |
| Q15 | 怎么证明有效 | 只加交互侧计数，结果侧用现有数据对比 | 两套指标会打架；降级率是唯一能反映实现质量的数 |

---

**落地顺序**：阶段 1（骨架 + 测试）→ 阶段 2（Boss 交互）→ 阶段 3（导航）→ 阶段 4（智联 + 对比）。每个阶段独立可验证、可回滚，不在一个提交里跨阶段。

## 7. 实施记录：阶段 1（2026-11-01）

只建骨架，**没有接线**：`com.chatjob.platform` 包里搜不到对 `com.chatjob.driver.human` 的引用
（由 `HumanizeSourceGuardTest` 断言），适配器与服务的行为、日志、数据库与改造前一致。

### 7.1 交付物

| 类型 | 内容 |
|---|---|
| 主代码 | `driver/human/` 10 个类：`Humanize`、`Humanizer`、`HumanizeConfig`、`Pacing`、`Rng`、`HumanAim`、`HumanMotion`、`HumanTiming`、`HumanScrollPlan`、`HumanCounters` |
| 配置 | `AppProperties` 增加 `humanize` 分量（`HumanizeConfig`）；`application.yaml` 增加 `chat-job.humanize` 段，`enabled: false` |
| 测试 | 6 个类共 36 例：`HumanAimTest`(5)、`HumanMotionTest`(6)、`HumanTimingTest`(7)、`HumanScrollPlanTest`(7)、`HumanizeConfigTest`(5)、`HumanizeSourceGuardTest`(6) |
| 全量用例 | `mvn test` 323 例通过（改造前 287 例 + 新增 36 例），1 例按系统属性跳过（`PlatformBrowserIsolationTest`，需真实浏览器） |

### 7.2 与设计的偏差（都是为了少留隐患，不是缩范围）

| # | 设计原文 | 实现 | 理由 |
|---|---|---|---|
| 1 | `Humanize` 7 个方法 | 增加 `counters()` | §3.7 要求 Run 首尾采样差，而 `summary()` 只给一行文本，没法相减 |
| 2 | 4 个计数 | 7 个（降级按点击/输入/滚动拆开） | §3.7 的日志格式本身就要求分类型，只用 4 个计数无法生成那行文本 |
| 3 | 参数默认值 | 全部字段用包装类型，默认值只写在规范构造器里，**不用 `@DefaultValue`** | `@DefaultValue` 对嵌套记录的每个字段各取自己注释上的默认值，于是"整段缺失"与"部分缺失"会落到两套不同的值上（实测：`mouse.steps` 整段缺失时拿到 `{0,0}` 而不是 `{18,34}`）。改成"null 表示没配"，一个来源 |
| 4 | — | `Range` 用静态工厂 `Range.of(...)`，不提供 `Range(int,int)` 构造器 | 多一个构造器会让 Spring 的类型绑定选不出该用哪一个，症状是**不报错、参数静默不生效** |
| 5 | `Pacing` 睡眠与采样 | 只有 `sleep(min,max)` / `sleepMs(ms)`，没有 `await` | 等待条件成立仍由适配器用 `waitForSelector` 做（阶段 3），本层不接管"等到什么" |
| 6 | 降级写 `run_log` | 本层写 WARN 应用日志，并通过 `Humanizer.FallbackListener` 交给调用方 | `run_log` 需要 `runId`，本层持有 `RunStore` 会让 driver 反向依赖编排层。阶段 2 由适配器实现该回调 |
| 7 | `slow-mo-ms: 50 → 0` | 阶段 1 保持 50 | 阶段 1 不接线，归零就是纯粹的行为变更；归零随阶段 2 一起做 |
| 8 | 源码守卫扫全部适配器 | 守卫按"已改造平台"集合扫，阶段 1 集合为空；`human.*` / `humanize.*` 形式放行 | 阶段 1 适配器还没改，直接扫会立刻变红。空集合期间由三条检测器自检 + "没有适配器依赖本层"承担实际断言。放行规则要求适配器里本层实例的字段名固定为 `human`，这条写进了 `Humanize` 的注释 |

### 7.3 运行时验证（2026-11-01，智联）

Boss 处于风控期，因此用智联做主验证。服务用 `java -jar` 从仓库根启动，`chat-job.humanize.enabled=false`。

| 检查项 | 结果 |
|---|---|
| 启动 | 正常，`已装配平台: [boss, zhaopin]`，driver 就绪，无新增 WARN |
| 配置确实被绑定 | 用 `--chat-job.humanize.mouse.steps.min=abc` 启动 → `APPLICATION FAILED TO START`，报 `Failed to bind properties under 'chat-job.humanize.mouse.steps.min'`。证明该前缀是真绑定，不是写了没人读 |
| 一次真实扫描 | 智联 `DRY_RUN`，Run 63：`组合 #0 加载 193 张卡片（10 轮，确认到底）`，评估 6 个岗位（1 个被 AI 判为不投），0 投递，手动取消后收尾正常 |
| 页面 | 运行中外置观测到 `https://www.zhaopin.com/jobs/?jl=681&kw=java`（福州 / java），与库里的组合一致 |
| 数据库 | `flyway_schema_history` 仍 V16，表仍是 20 张，无新迁移、无新表；`run_log` 只增本次 Run 的 11 行 |
| 日志格式 | 与改造前一致（卡片轮数文案、演练模式文案、取消收尾文案均未变）；**没有**出现"人类化交互 …"汇总行——符合"未接线"的预期 |
| 开关后的行为 | 阶段 1 无接线，开关取真或取假都只影响那段配置本身，运行期不可区分；这一点会在阶段 2 重新验证 |

### 7.4 阶段 2 接手时要先做的事

1. 给 `PlatformBrowser` 增加 `humanize` 分量（三者成对取），`BossAdapter`/`BossChatNavigator` 的 30 处交互换成 `Humanize`，`pause(a,b)` 换成 `pacing.sleep(a,b)`（区间数值不动），并把 `slow-mo-ms` 归零。
2. 用真实页面标定 §3.4 条件 c 的 `childElementCount` 容差（现为 2）。
3. 确认 `Ctrl+A` 在 Boss 的 contenteditable 里只选中编辑器内容；若不是，输入降级率会立刻升高（读回文本不一致）。
4. 把 `boss` 加进 `HumanizeSourceGuardTest.CONVERTED`，同时删掉"阶段一没有任何适配器依赖本层"那条断言。
5. 适配器实现 `Humanizer.FallbackListener`，把每次降级写进 `run_log`。


## 8. 实施记录：阶段 2（2026-11-01）

Boss 处于风控期，跑不出页面级证据。为了让"滚轮真的有 `wheel`、输入真的有 `keydown`"能被实测，
**智联的交互接线随本阶段一起做**（原计划在阶段 4），导航两跳仍留在阶段 3。
`HumanizeSourceGuardTest.CONVERTED` 因此是 `{boss, zhaopin}`。

### 8.1 交付物

| 类型 | 内容 |
|---|---|
| 本层 | `Humanize` 增加 `pacing()`、`onFallback(FallbackListener)`、`scrollPageByScreens`、`scrollPageToBottom`、`scrollPageToTop`；`FallbackListener` 由 `Humanizer` 的内部类型提为顶层接口 |
| 装配 | `PlatformBrowser` 增加 `humanize` 分量；`BrowserSessions.of()` 按平台建 `Humanizer`（每个平台一份计数，否则降级率是两个平台混在一起的平均数） |
| Boss | `BossAdapter` 8 处点击、2 处输入、3 处滚动全部改走本层；`BossChatNavigator` 2 处点击 |
| 智联 | `ZhaopinAdapter` 8 处点击、1 处输入、2 处滚动全部改走本层 |
| 节奏 | `BossAdapter`/`ZhaopinAdapter` 里两份逐字相同的 `pause(int,int)` 实现删除，改为转发 `human.pacing().sleep(min, max)`，**26 个调用点的区间数值一个都没改** |
| 观测 | `RunService` 在 Run 首尾各取一次计数快照，差值写一行 INFO；降级回调在 Run 开始时注册、结束时撤销，逐次写 WARN |
| 配置 | `application.yaml`：`slow-mo-ms: 50 → 0`，`humanize.enabled: false → true` |
| 守卫 | `CONVERTED = {boss, zhaopin}`；删掉"阶段一没有任何适配器依赖本层"，新增"已改造平台确实经本层"（只断言"没有裸调用"挡不住把整段交互删掉） |
| 测试 | 新增 `HumanizeBrowserProbeTest`（默认跳过，`-Dchat-job.humanize.probe=true` 手动触发），用本地 `data:` 页量 wheel、keydown、条件 c |

### 8.2 与设计的偏差

| # | 设计原文 | 实现 | 理由 |
|---|---|---|---|
| 1 | 阶段 2 只改 Boss，智联一行不动 | 智联同时接线 | Boss 风控期间，只有智联能跑出页面证据；不接线的话，新的点击/输入/滚动路径在运行期一次都不会执行，验证只能停在"服务能起、开关能绑" |
| 2 | `Humanize` 7 个方法 | 11 个 | 多出来的 5 个都是"把适配器里会各写一份的换算收进本层"：视口高换算（`scrollPageByScreens`）、到底/到顶的位移计算（`scrollPageToBottom/Top`）、节奏出口（`pacing`）、降级回调注册（`onFallback`） |
| 3 | `FallbackListener` 在 `Humanizer` 内部 | 提为顶层接口 | `Humanize` 接口要引用它，而接口引用自己的实现类是反向依赖 |
| 4 | `warmUp` 由调用方显式调 | `click` 与 `scrollPage` 在首次使用某页时自动调一次 | 阶段 2 没有接 `navigate`，显式入口没人调，预热就是一条死配置。放在"页面要被操作"的那一刻也符合 Q12 的"按 Page 惰性触发"——`newPage()` 之后页面还是空的 |
| 5 | 预热的滚动步数不参与位移计算 | 首次滚动的实际位移会比目标多 2–4 小步（实测 +148…+203px） | 预热自己会滚 2–4 次、每次 40–110px。这是 §1.4 定义的预热行为，不额外补偿；影响只在该页第一次滚动 |
| 6 | 3.3 把"一轮"定义为 1–1.5 屏 | Boss 按 1.5 屏；**智联仍按"滚到文档底部"** | 智联每轮的判据是"卡片数是否增加"，它原本就是"跳到底再看"。若改成 1.5 屏，第一轮根本到不了底，卡片数不增会被 `stable` 判成"已加载完"，直接丢掉后面的岗位 |
| 7 | — | `hoverOnScrollArea` 把落点夹进视口 | 列表容器通常比视口高，外框会上下溢出，落点算出来可能是负数；滚轮命中的是"该坐标下的元素"，坐标在视口外等于没停在任何容器上 |
| 8 | 条件 c 容差在阶段 2 标定 | 仍是 2 | 智联的输入框是 `textarea`（`childElementCount` 恒为 0，条件 c 恒成立），真 Boss `contenteditable` 无法验证，见 8.3 |
| 9 | `enabled=false` 时退化为"原生写法" | 停用路径里补了一次 `click(5s)` 才写值 | 两家适配器原来的写法都是「click 建立焦点 → `fill` / 赋 `innerText`」，而 `fill` 只调 DOM 的 `focus()`、不发鼠标事件。少这一次点击，页面侧会少一对 `mousedown`/`mouseup`，"关掉即回到改造前"就不成立。Run 66 是在这行补上之前跑的：它没有触发输入路径，结论不受影响 |

### 8.3 取证结果（`HumanizeBrowserProbeTest`，本地 `data:` 页）

| 问题 | 实测 | 结论 |
|---|---|---|
| 滚轮是否真的派发 `wheel` | 600px 的滚动计划 → **4 次** `wheel` 事件 | 改造前 `window.scrollBy` 是 0 次。§2.1 第 5 条达成 |
| ASCII 输入是否有逐字 `keydown` | `["Control","A","Backspace","h","e","l","l","o","2","0","2","6"]` | 每个字符一次 `keydown` |
| 中文是否只有 `input` 没有 `keydown` | 纯中文 14 字：`keydown` 只有 `["Control","A","Backspace"]`（清空用），`input` 15 次 | **§5.3 第 2 条结案**：中文确实不产生字符级 `keydown`，§3.4 的已知限制成立，注释无需修改 |
| 条件 c 的实际增量 | 单段中文写入 `contenteditable`：`childElementCount` 0 → 0 | 容差 2 对"单段文字"有富余；带换行的文本仍未知 |
| 起止语 | 两次三条件校验全部通过，降级 0 次 | — |

以下三条仍未闭环：① Boss 的真实页面（风控）；② 真 `contenteditable` 上的条件 c 与 `Ctrl+A` 作用域；
③ 智联的真实输入路径（见 8.4 最后一行）。

### 8.4 运行时验证（2026-11-01，智联）

服务用 `java -jar server/target/chat-job.jar` 从仓库根启动。Boss 未启动浏览器（懒加载），
`bsk browsers` 只看到智联那一个实例。

| 检查项 | 结果 |
|---|---|
| 启动（`enabled=true`） | `已装配平台: [boss, zhaopin]`；两个平台的会话声明都打印 `人类化交互: 启用`；无新增 WARN |
| 滚动判据未被打坏 | Run 64/65 均为 `组合 #0 加载 193 张卡片（6 轮，确认到底）`，与阶段 1 记录（Run 63）的 **193 张** 一致 |
| 点击路径 | Run 65 依次读开 7 张卡片的面板（含 5 次新评估、2 次 24 小时内已判定跳过），全部走本层点击 |
| 降级率 | Run 65：`人类化交互 19 次 / 降级 0 次（输入 0、点击 0、滚动 0）/ 漂移作废 0 次 / 输入字符 0 / 降级率 0.0%`，低于 5% 的验收线 |
| 页面 | `bsk tab list` 读到 `https://www.zhaopin.com/jobs/?jl=681&kw=java`，标题「福州热门职位招聘」，与配置（福州 / java）和日志一致 |
| 数据库 | `flyway_schema_history` 仍 V16，表仍是 20 张（无迁移、无新表）；`combination_run.cards_seen = 193`，与日志里的 193 一致；汇总行确实落进 `run_log` |
| `enabled=false` 复跑（Run 66） | 193 张卡片、**10 轮**，与阶段 1 记录的 10 轮逐字一致；`run_log` 共 7 行，**没有**"人类化交互 …"汇总行 —— 关掉之后日志与改造前逐行相同 |
| 滚动耗时 | 同一组合的滚动阶段：启用 42 秒（6 轮）、停用 26 秒（10 轮），单组合约 **+16 秒**。轮数反而更少，因为滚轮期间懒加载有更多时间追加，`stable` 更少被误置 |
| 输入路径 | **本次未触发**。智联的投递不发招呼语（平台自己发），`type` 只在值守回复时发生；为避免向真实 HR 发消息，验证只跑到 `DRY_RUN` |
| DOM 级页面证据 | 未取到：`bsk cooperate sync` 报 `Could not establish connection`（该页在会话建立前就已加载），按"失败一次即停手"的规则没有重试。页面证据停在 URL/标题层面，卡片数由日志与数据库两侧互证 |

### 8.5 已知代价与后续

- 单组合滚动阶段约 +16 秒（+60%）。按 30 个岗位、若干组合估算，单次扫描增加分钟级，值守时段内可接受。
- `slow-mo-ms` 已归零。要复现改造前的节奏，用 `--chat-job.humanize.enabled=false --chat-job.browser.slow-mo-ms=50` 启动（Run 66 就是这样跑的）。
- 阶段 3 接手时：`deliver` 改两跳 + `DeliveryOptions` 带搜索条件；`fetchDetail` 每 5–8 张插一次停顿。
  届时 `human.navigate` 才会第一次被调用。

## 9. 实施记录：阶段 3（2026-11-02）

`deliver` 改两跳、`DeliveryOptions` 带搜索条件、`fetchDetail` 补批次停顿。**过程中推翻了本节 §3.5 的一个前提**，
这是本阶段最重要的一条，先写在最前面。

### 9.1 被推翻的前提：两跳不等于有来源

§3.5 初稿的推理是"上一个文档是列表页，所以下一次导航会带上 `Referer`"。实测不成立。
`page.navigate` 在协议层是**浏览器发起**的导航，没有发起方文档，所以结果与上一页无关。
同一详情地址、四种进入方式的请求头（`ListFirstEntryProbeTest`，本地 HTTP 服务读请求头）：

| 进入方式 | Referer | Sec-Fetch-Site |
|---|---|---|
| 空白页 `page.navigate` | 空 | `none` |
| 列表页之后 `page.navigate` | 空 | `none` |
| `page.navigate` + `setReferer(列表页)` | 列表页 | `none` |
| 列表页里由文档发起（点链接 / `location.href`） | 列表页 | `same-origin` |

推论：来源由"**这次导航由谁发起**"决定，不由历史决定。所以第二跳改为页面内跳转
（`Humanize.navigateInPage`），第一跳仍用 `navigate`。`setReferer` 虽然能补上 `Referer`，
但那会得到"有 Referer 却是 `none`"这种真实浏览器不会出现的组合，因此没有采用。

### 9.2 交付物

| 类型 | 内容 |
|---|---|
| 本层 | `Humanize` 增加 `navigateInPage`（页面内跳转）与 `active()`（开关状态查询）；`navigate` 明确以 `DOMCONTENTLOADED` 为到达时刻 |
| 本层 | 新增 `HumanBatchPacer`：每 5–8 个条目停 800–2000ms，批次长度每次重抽；停顿打一条 DEBUG，运行期可核对 |
| 本层 | `Pacing` 增加 `uniformInt(min, max)`：批次长度这类"次数阈值"也要随机，而适配器不该持有随机源 |
| 平台层 | 新增 `ListFirstEntry`：两跳的顺序与失败处置（第 1 跳失败不阻断投递）只写一份，URL 与卡片选择器由适配器传入 |
| Boss | `deliver` 改两跳；`fetchDetail` 接批次停顿 |
| 智联 | 同上 |
| 编排 | `RunService` 每个组合建一份 `SearchQuery`，既给 `openSearch` 也给投递（同一个对象，避免"扫描用城市 A、投递拼出城市 B"） |
| 模型 | `DeliveryOptions` 增加 `search` 字段 |
| 测试 | `HumanBatchPacerTest`（5 例，纯逻辑）；`ListFirstEntryProbeTest`（5 例，真实浏览器 + 本地 HTTP 服务读请求头，默认跳过） |

### 9.3 与设计的偏差

| # | 设计原文 | 实现 | 理由 |
|---|---|---|---|
| 1 | 两跳都用 `human.navigate` | 第 2 跳用新增的 `navigateInPage` | 上面 9.1：`navigate` 拿不到来源，两跳等于没改 |
| 2 | `Humanize` 11 个方法 | +2（`navigateInPage`、`active`） | `active` 是给层外的复合动作用的：开关关掉时"两跳"必须整段跳过，否则多出一次列表页加载 |
| 3 | 两跳各写在两个适配器里 | 抽成 `platform/ListFirstEntry` | 顺序与"第 1 跳失败不阻断投递"是共同策略；平台知识（URL、卡片选择器）仍留在适配器。放在 `Humanize` 里会变成 ADR-0007 否决过的"深层导航"命名 |
| 4 | `fetchDetail` 的停顿直接写在适配器 | 抽成 `HumanBatchPacer` + `Pacing.uniformInt` | 计数器与阈值重抽是纯逻辑，能脱离浏览器测；写两份迟早只改一处 |
| 5 | 停顿只加在"读详情"上 | 两个适配器都在 `human.active()` 为真时才停 | 停顿是本期新增行为，开关关掉时留着它，"关掉即回到改造前"就不成立 |
| 6 | 未提 `navigate` 的到达判据 | 明确 `DOMCONTENTLOADED` | 列表页与详情页都挂长连接与广告位，默认 `LOAD` 可能等到超时；改造前的导航用的就是 `DOMCONTENTLOADED` |
| 7 | 阶段 3 验收要求"用 DevTools 抓一次真实请求" | 用本地 HTTP 服务读请求头 | 真实请求只出现在真实投递里，会给某家公司投一次简历；请求头由"谁发起导航"决定，与站点内容无关，本地服务能原样读回。真实站点的列表 URL 与页面状态另有日志与 bsk 佐证 |
| 8 | 两跳的导航没有重试 | 第二跳失败重试一次 | 改造前智联的详情页导航是 `navigateWithRetry(..., 1)`；一次重试的成本远低于丢掉一次投递。Boss 原来没有重试，两跳之后统一成一次，差异只在"更容易成功"这一侧 |

### 9.4 运行时验证（2026-11-02，智联）

服务用 `java -jar server/target/chat-job.jar` 从仓库根启动，`humanize.enabled=true`。

| 检查项 | 结果 |
|---|---|
| 启动 | `已装配平台: [boss, zhaopin]`；两个平台的会话声明都打印 `人类化交互: 启用` |
| 滚动判据未被打坏 | Run 70：`列表加载完成：DOM 卡片 192 个（6 轮，确认到底）`，与改造前 Run 68 的 **192 张** 一致 |
| 批次停顿确实发生、周期在变 | 应用日志两条：`连续读完一批 5 个条目，停顿 800–2000ms`（10:15:32）、`连续读完一批 8 个条目`（10:21:27）——先 5 后 8，证明批次长度每次重抽 |
| 降级率 | Run 70：`人类化交互 21 次 / 降级 0 次（输入 0、点击 0、滚动 0）/ 漂移作废 0 次 / 降级率 0.0%` |
| 数据库 | `run` 70 = `CANCELLED`，`last_card_index=13`、`evaluated_count=5`；`combination_run.cards_seen=192`，与日志一致；表仍是 20 张、迁移仍是 V16 |
| 页面 | `bsk tab list` 读到 `https://www.zhaopin.com/jobs/?jl=681&kw=java`，标题「福州热门职位招聘」，与配置（福州 / java）一致 |
| `enabled=false` 复跑（Run 71） | 192 张卡片、**10 轮**，与阶段 1/2 记录的停用态逐字一致；`run_log` 13 行，**没有**"人类化交互 …"汇总行，也**没有**批次停顿日志 |
| DOM 级页面证据 | 未取到：`bsk cooperate sync` 报 `Could not establish connection`，与阶段 2 §8.4 同一现象；按"失败一次即停手"没有重试。页面证据停在 URL/标题层面 |

### 9.5 未闭环

- **真实投递上的实时请求头**没有取（本阶段刻意不跑真实投递）。要补的话只需跑一次正常模式的投递，
  在服务端或代理上看详情页请求的 `Referer`；请求头一旦按"文档发起"实现，两者结论应当一致。
- 第 1 跳会给每个岗位多一次列表页加载（约 2–4 秒 + 一次搜索请求）。Boss 处在风控期，
  本阶段的运行数据全部来自智联，Boss 侧只有本地探针的结论。
- 页面内跳转没有一次真实的 `mousedown`（见 ADR-0007 Consequences）。要补，需要"按岗位在列表里
  定位到那张卡"的能力，仍是本 spec 的非目标。

## 10. 实施记录：阶段 4（2026-11-02）

阶段 4 的**代码**部分在阶段 2 就落地了：智联的点击/输入/滚动接线与 `RunService` 的计数汇总都是那时
加进去的（§8.2 偏差 1 记了原因——Boss 风控期间只有智联能跑出页面证据）。所以本阶段交付的是**验收**：
Boss 风控解除后，两个平台各跑一轮真实运行，把 §2.2 与 §4.2 的判据逐条落成可查的事实。

本阶段**没有新增主代码、没有新增迁移、没有改配置默认值**，只改了文档与验证产物。

### 10.1 环境与前置

| 项 | 值 |
|---|---|
| 启动方式 | `java -jar server/target/chat-job.jar --server.port=8080`（仓库根，`chat-job.home` 解析到仓库根） |
| 关键配置 | `humanize.enabled=true`、`slow-mo-ms=0`（均为 `application.yaml` 默认值，未改） |
| 装配 | `已装配平台: [boss, zhaopin]`，两个平台的会话声明都打印 `人类化交互: 启用` |
| 登录 | 两个平台 `probe-login` 均返回 `已登录`；`browser-data/boss` 不再处于风控期（Run 74 能正常加载列表） |

> 启动时 `SERVER__PORT=59271` 这个环境变量会被 Spring 当作 `server.port` 读走，与服务自身端口冲突并报
> `Port 59271 was already in use`。命令行显式传 `--server.port=8080` 即可（命令行参数优先级高于环境变量）。

### 10.2 代码侧核对（改之前先确认没有被绕开的地方）

| 检查项 | 结果 |
|---|---|
| 适配器里的裸交互 | `com/chatjob/platform/**` 下搜不到 `click/fill/type/press/hover/selectOption`、`mouse()`、`keyboard()`、`window.scrollBy\|scrollTo`；命中的全是注释 |
| 源码守卫 | `HumanizeSourceGuardTest` 的 `CONVERTED = {boss, zhaopin}`，两条断言都真实扫描（"无裸调用" + "确实经本层"） |
| 两跳 | 两个适配器的 `deliver` 都经 `openDetailPage` → `ListFirstEntry.open`，第二跳是 `navigateInPage` |
| 批次停顿 | 两个适配器的 `fetchDetail` 都持有 `HumanBatchPacer`，由 `human.pacing()` 供给 |
| 计数汇总 | `RunService` 在 Run 首尾各取 `counters().snapshot()`，差值写一行 INFO；降级回调在 Run 开始时注册、结束时撤销 |
| 全量测试 | `mvn test`：42 个测试类、**352 例**、0 失败 0 错误、12 例按设计跳过（需真实浏览器或系统属性） |

### 10.3 两个平台各一轮（Run 74 / Run 75）

两轮都是 `NORMAL` 模式（真实投递），跑完各自要验证的路径后手动取消。取消是刻意的——两轮的最后一个
判据（"打招呼/投递真的发出去"）在取消之前就已经成立，继续跑只会多给真实 HR 发消息。

| 检查项 | Boss（Run 74） | 智联（Run 75） |
|---|---|---|
| 列表加载 | `组合 #0 加载 300 张卡片（40 轮，确认到底）` | `组合 #0 加载 195 张卡片（6 轮，确认到底）` |
| 搜索页（bsk `tab list`） | `zhipin.com/web/geek/jobs?city=101230100&salary=405&query=java`，标题「福州招聘」10-20K-2026年福州人才招聘信息 - BOSS直聘 | `zhaopin.com/jobs/?jl=681&kw=java`，标题「福州热门职位招聘 2026年热门职位招聘信息-智联招聘」；另有值守页 `i.zhaopin.com/im`（标题「消息」） |
| 两跳 | `详情页来源已修正为站内：列表页 15 张卡片 → 详情页`，3 次投递各一次 | `列表页 20 张卡片 → 详情页`，1 次 |
| 投递 | 3 条 DELIVERED，判据 `详情页按钮 立即沟通→继续沟通` | 1 条 DELIVERED，判据 `弹窗「已向对方发送简历和打招呼语」` |
| 招呼语写入 | `human.type` 写入 contenteditable，三条件校验全通过（否则会降级并记 WARN，实际 0 次） | 智联投递不发招呼语，`type` 只在值守回复时触发 |
| 批次停顿 | `连续读完一批 6 个条目，停顿 800–2000ms` | `连续读完一批 5 个条目` |
| 交互汇总行 | `人类化交互 72 次 / 降级 0 次（输入 0、点击 0、滚动 0）/ 漂移作废 0 次 / 输入字符 182 / 降级率 0.0%` | `人类化交互 26 次 / 降级 0 次（…）/ 输入字符 0 / 降级率 0.0%` |
| 数据库对账 | `combination_run.cards_seen=300 / evaluated=7 / delivered=3`，与日志逐项一致 | `cards_seen=195 / evaluated=5 / delivered=1`，一致 |
| 写入的文本 | `delivery.greeting` 三条分别为 60 / 52 / 45 字，与 `run_log` 打印的招呼语逐字相同 | `greeting` 为空（平台自己发招呼语），`status=DELIVERED` |

**`输入字符 182` 与三条招呼语合计 157 的差额（25）已定位**：Run 74 期间值守（未读监听）在 12:14:14
回了一条 25 字的会话消息（`reply_log` id 31：「在看在看 不过我只考虑福州的 您那边岗位是在福州吗」）。
计数是**平台级**的（§3.7），值守与扫描共用同一个 `Humanizer`，所以这 25 字落进了 Run 74 的采样差。
这不是缺陷，但它说明「输入字符」不能直接当作"本 Run 投递写了多少字"来读。

### 10.4 三项对比（§4.2 的验收口径）

| 指标 | 改造前基线 | 本次（`enabled=true`） | 结论 |
|---|---|---|---|
| Boss 卡片数 | 255（Run 67，滚动途中被取消，未确认到底） | 300（Run 74，确认到底） | 未下降 |
| 智联卡片数 | 192–193（Run 70/71） | 195（Run 75） | 未下降（正常区间波动） |
| 详情抓取 | `job_posting` 正文覆盖 boss 172/176、zhaopin 80/80 | 需要 AI 判定的 7 + 5 个岗位全部拿到详情；两轮的应用日志里没有一条 `第 N 张卡片没抓到详情，跳过` | 未下降 |
| 投递成功率 | 93.2%（41/44，run ≤ 62） | 100%（4/4，run 74–75） | 未下降 |
| 降级率 | —（本层不存在） | Boss 0.0%（72 次）、智联 0.0%（26 次） | 低于 5% 的验收线 |

投递成功率用 `DELIVERED / (DELIVERED + FAILED)` 计算，`SKIPPED`（AI 判定不投）不进分母——
它表示"没有尝试投递"，把它算进分母会让 AI 的判定偏好污染投递质量的读数。

### 10.5 `enabled=false` 复跑（Run 76）

用 `--chat-job.humanize.enabled=false --chat-job.browser.slow-mo-ms=50` 重启后跑 Boss 演练模式。

| 检查项 | 结果 |
|---|---|
| 会话声明 | 两个平台都打印 `人类化交互: 停用` |
| 滚动判据 | `组合 #0 加载 300 张卡片（40 轮，确认到底）`——与启用态**卡片数与轮数完全相同** |
| 汇总行 | `run_log` 里没有「人类化交互 …」一行。全文 9 行：启动 2、组合 2、跳过/AI 判定 4、取消 WARN 1 |
| 批次停顿 | 应用日志里没有「连续读完一批」（该日志里只有两行启动期的 `人类化交互: 停用` 声明命中关键词） |
| 投递 | 演练模式，`DELIVERED=0`，未发任何消息 |

Boss 的轮数在两种状态下相同，是因为它的"一轮"在两种状态下都是 1.5 屏：启用态走滚轮分步，停用态走
`window.scrollBy` 一次跳 1.5 屏，位移相同，所以轮数不变。智联的 6 轮 / 10 轮差异来自另一种结构——
它的一轮是"滚到文档底部"，停用态是瞬时跳到底，懒加载间隙更容易被 `stable` 判成到底之前就多滚几轮
（阶段 2 §8.4、阶段 3 §9.4 记录的是 6 对 10）。

### 10.6 代价（实测）

| 项 | Boss | 智联 |
|---|---|---|
| 单组合"打开搜索页 + 滚动到底"耗时 | 启用 132s / 停用 81s，滚动阶段约 **+51s** | 阶段 2/3 记录约 +16s（同一组合 6 轮 vs 10 轮） |
| 每岗位额外一次列表页加载 | 有（两跳的第 1 跳，约 2–4s） | 有 |

Boss 的增量比智联大：40 轮里每轮都是一次完整的滚轮计划（30 次左右的 `wheel` + 步间间隔），
而智联 6 轮就到底。按 30 个岗位、若干组合估算，单次扫描增加分钟级，在值守时段内可接受。

### 10.7 与计划的差异

| # | 计划 | 实际 | 影响 |
|---|---|---|---|
| 1 | 投 1–2 条后停 | Boss 投了 3 条 | 第 3 条在取消指令到达前已经发出。副作用是给 3 位真实 HR 发了招呼语；正面作用是多一条投递样本 |
| 2 | 用 DOM 取证（`wheel`、`keydown`） | Boss 侧取不到：`browser-data/boss` 里的 bsk 扩展是 **0.3.3**，`cooperate` 在该扩展里没有实现（`daemon does not recognise this RPC method`）；`snapshot` 借入后只读回空树，截图是空白 | 页面证据停在 TITLE/URL 一层。该结论与阶段 2 §8.4、阶段 3 §9.4 相同，**不是**本阶段新出现的问题。异常事件的取证仍以 `HumanizeBrowserProbeTest` 为准 |
| 3 | 「真实投递上的实时请求头」仍列为未补 | 未补：本轮有真实投递，但没有同时挂网络观测 | `Referer` / `Sec-Fetch-Site` 的结论仍来自 `ListFirstEntryProbeTest`（本地站点、四种进入方式）。要补就是"一次带观测的真实投递" |

### 10.8 交接：仍未闭环的部分

| # | 事项 | 状态 |
|---|---|---|
| 1 | 按岗位在列表里定位卡片、点卡片进详情 | 不具备（Boss 卡片无 `href`）。仍然是非目标 |
| 2 | 预热是否真的改变平台判定 | 无法直接观测。本轮只能说"没有新增限流/安全验证告警" |
| 3 | `ScrollParams.waitTimeSeconds` 是死配置 | 依旧没人读。另开需求 |
| 4 | 真 `contenteditable` 上的条件 c 容差与 `Ctrl+A` 作用域 | **本轮已用真实页面验证通过**：Boss 三次招呼语与一次值守回复全部一次写入成功，降级 0 次，读回文本与目标逐字一致。容差 2 无需调整 |
| 5 | Boss 侧 DOM 级页面证据 | 受 `browser-data/boss` 的扩展版本限制（0.3.3）。要取需要把该 profile 的扩展升到 0.4.x |
