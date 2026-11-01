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
human.navigate(page, detailUrl)      // 第 2 跳：详情页，此时 Referer 是列表页
```

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

