# 页面交互收敛到单层，并把导航来源当作比鼠标轨迹更重要的信号

## Status

accepted（2026-11-01，spec 0006）。本文只记录三条容易被后人"顺手改回去"的选择，具体实现见 spec。

## Context

页面交互现在散在约 30 处：`BossAdapter` 8 处点击、3 处滚动、2 处导航、2 处输入，`BossChatNavigator` 2 处点击，`ZhaopinAdapter` 8 处点击、3 处滚动、1 处导航、1 处输入。同一件事有不同写法——有的先 `scrollIntoViewIfNeeded` 再点，有的直接点；输入有的 `fill()`，Boss 的 contenteditable 因为填不进去改成用 JS 赋值。"随机等一会儿"这个方法在两个适配器里各抄了一份，一字不差；实际调用点 26 处。

平台判断一个会话是不是机器，看两类东西：一类是浏览器指纹（`navigator.webdriver`、UA、WebGL 等），本项目由 patchright driver 处理，不在本文范围；另一类是行为——鼠标有没有轨迹、点击落点分不分散、输入节奏、滚动方式。要处理的是第二类，但动代码的过程中发现，**最明显的那个信号并不在鼠标层**。

`deliver` 打开岗位详情页的方式是：`session.newPage()` 得到一张从 `about:blank` 起始的空页，然后 `detailPage.navigate(detailUrl)`（`BossAdapter:386-391`）。这个顶层导航在 HTTP 层是 `Sec-Fetch-Site: none`、`Referer` 缺失、页面历史长度为 1、整页生命周期只有一次导航。四项都是请求头与导航元数据，鼠标轨迹再自然也改不了其中任何一项。

## Decision

1. **交互收敛到 `com.chatjob.driver.human` 下的单一入口**，适配器只依赖这个入口，不再直接调用 `Locator.click` / `fill` / `type` / `press`、`Page.mouse()` / `keyboard()`、以及写滚动位置的 `evaluate`。用一条源码扫描测试守住这条约束（禁列表只针对写入类调用，读 DOM 的 `evaluate` 与 `count()` / `getAttribute()` 放行）。不引入 ArchUnit，用 `Files.readString` + 正则即可。

2. **点击保留 Playwright 的可操作性检查，只替换"鼠标落到元素"这一段。** 不自算坐标、不做命中检测。落点按高斯围绕元素中心、截断在最小可点区内；`mouse.down()` 之前重取一次 `boundingBox`，纵向漂移超过 `max(8px, 元素高 × 25%)` 则作废重算，最多 2 轮，用尽后降级为原生 `locator.click()` 并记 WARN。

3. **滚动改为发真实滚轮事件**（`Page.mouse().wheel`），因为 `window.scrollBy` 不产生 `wheel` 事件，而真实用户滚动一定有。滚轮的生效对象是"鼠标当前悬停的可滚动容器"，所以滚之前必须先把指针停在列表上。"加载完了没有"的判据不变——仍数卡片数量，不看滚动距离。

4. **输入保证终态正确，中间允许一次可配的错字与回删。** contenteditable 从 `innerText` 赋值改为真实键盘输入，成功判据三条同时成立：无异常、读回文本与目标一致、短时间内无意外 DOM 结构变化。任一不满足立即回退到 `innerText + dispatchEvent(new Event('input', {bubbles:true}))`，并把是哪一条没过写进 `run_log`。

5. **`slow-mo-ms` 归零。** 它给每个动作固定加 50ms，是均匀延迟；均匀本身即可统计，且会与本层的随机间隔叠加，形成两种节奏混杂。

6. **协议层优先于行为层：`deliver` 改为先到列表页、再进入详情页**，让详情页请求的来源变成站内（`Sec-Fetch-Site: same-origin`，`Referer` 为列表页），代价是每岗位多一次列表页加载。**不提供 `humanNavigateToDeepPage()` 这类方法**——"深浅"是平台概念，通用层不该知道；名字还会诱导人以为"调了它就像人"，掩盖它无法处理的请求头问题。

   **第二跳必须由当前文档发起，这是本决策的必要条件（2026-11-02 实测修正）**：当初写这条时以为"上一个文档是同站页面"就够了，实测不是。`page.navigate` 在协议层是浏览器发起的导航，没有发起方文档，所以**前一个页面是什么都不影响**——两跳之后仍然是 `Referer` 空、`Sec-Fetch-Site: none`。同一详情地址、四种进入方式的实测：

   | 进入方式 | Referer | Sec-Fetch-Site |
   |---|---|---|
   | 空白页 `page.navigate` | 空 | `none` |
   | 列表页后 `page.navigate` | 空 | `none` |
   | `page.navigate` + 显式 referer 参数 | 列表页 | `none` |
   | 列表页里由文档发起（点链接 / `location.href`） | 列表页 | `same-origin` |

   所以第二跳用 `Humanize.navigateInPage`（页面内 `location.href`），第一跳照常用 `Humanize.navigate`。取证见 `ListFirstEntryProbeTest`，四个用例里专门留了一个固定"连跳两次不带来源"，防止后人把它改回去。

7. **节奏参数按分布建模**（默认均匀区间，落点用截断高斯），放 `application.yaml` 而不进数据库；提供随机种子，0 为真随机、非 0 可复现。

## Considered Options

- **完全自算点击坐标（取框 → 自己找元素 → 自己发按下抬起）**：被否。Boss 与智联的岗位列表都是"只渲染可见区域、滚动时复用节点"的结构，自算的坐标在算好到按下之间就可能失效；而可操作性检查、遮挡判断、元素重试这些是 Playwright 已经做对的、且有大量真实故障经验的部分（`BossChatNavigator` 与两处 `clickSend` 的注释里记录过多次"点了但实际没点到"）。点击成功率是投递量的直接上限，不能拿它换轨迹的真实性。

- **保留 `window.scrollBy`，只把节奏做随机**：被否。JS 改动滚动位置不派发 `wheel`，页面上挂一个监听器就能得到"被滚过但一次滚轮事件都没有"这个结论；改滚轮的成本只是把"一次 1.5 屏"拆成"多次小步"，不影响任何现有判据。

- **只把 `humanNavigateToDeepPage()` 做成"带节奏地 navigate"**：被否，理由是本文 Context 里那段——它解决的层次和问题所在的层次不一致，做完了 `Sec-Fetch-Site: none` 依然存在。

- **节奏参数进数据库、控制台可调**：被否（本期）。`AppProperties` 自己的注释写明它只放"启动时决定、运行期不改"的参数；节奏属于算法参数，且要在 Playwright 线程上零 IO 读取。进数据库就要配迁移、控制台 UI 与整份对象 PUT 的兼容处理，而这一层还在验证阶段，参数还没稳定到值得给人调。

- **按平台分别配置节奏**：被否（本期）。覆盖机制是"某个平台被限流之后"的处置手段，现在没有这个事实依据。先留结构位置，不写默认值。

## Consequences

- `AppProperties` 增加 `Humanize` 记录后，**四处测试里的 `new AppProperties(...)` 会编译失败**（`PlatformBrowserIsolationTest`、`NotifySettingsTest`、`NotifyTestDoubles`、`PlatformAdaptersTest`），必须同步补参。这是编译期可见的，不会漏。
- `PlatformAdapter` 接口不变，改动全在实现内部——这是本决策能分期落地的前提：阶段 2 只接 Boss，智联代码一行不动，`enabled=false` 即可整体退回原行为。
- `ScrollParams.roundLimit` 的语义必须重新定义。现在"一轮"是"滚 1.5 屏一次"，默认上限 300；改滚轮后单轮位移变小，若不重新定义，"一轮"会变成一次 300ms 的小步滚动，轮数会暴涨数十倍，`roundLimit=300` 的保护随之失效。本层的"一轮"固定为"一次完整的滚动计划，累计位移约 1–1.5 屏"。
- 每岗位多一次列表页加载（约 2–4 秒）。按 30 个岗位估算，单个 Run 增加 1–2 分钟，值守时段内可接受；但列表页请求同时也是搜索请求，会占用平台的搜索请求额度，这是新增的风控面，必须观察。
- 中文经 `insertText` 插入，只有 `input` 没有 `keydown`。这一限制写进代码注释与 spec，不能让后来的人误以为中文也是逐键输入，从而按"keydown 全链路"去设计检测规避。
- **来源只能靠"由文档发起的导航"拿到，`page.navigate` 做不到**（2026-11-02 实测，见 Decision 6 的表）。这会持续影响后续改动：任何"让请求看起来来自站内"的需求，都不能用 `page.navigate` 实现，也不能靠"先把页面导航到站内某个地址"来凑。
- 页面内跳转（`location.href`）与点链接在请求头上等价，但页面上没有一次真实的 `mousedown`。要做到"点链接"，需要先在列表里按岗位定位到那张卡，这项能力本期没有（spec 0006 §2.3 非目标）。
- 交互侧计数（交互次数 / 降级次数 / 漂移作废次数）成为这一层唯一的自检指标。降级率持续偏高即说明实现有问题，与投递成功率无关。

## Open Questions

| # | 未取证的事 | 若不成立的后果 |
|---|---|---|
| 1 | 两个平台的列表容器能否用现有选择器稳定命中，且 `mouse.wheel` 在其上真的产生位移 | 该平台降级回 JS 滚动，`wheel` 信号放弃；不影响点击与输入两条 |
| 2 | 中文 `insertText` 是否真的只产生 `input`、不产生 `keydown` | 若实际会产生 `keydown`，Q4 的"已知限制"要删掉，节奏参数可以按逐键重新标定 |
| 3 | "意外 DOM 结构变化"的阈值（`childElementCount` 变化多少算意外） | 阈值错了的两种表现都可见：太松则输入被吞却不降级，太严则降级率虚高 |
| 4 | 从列表页点卡片进详情（而非 navigate）是否可行——需要能按岗位在列表里定位到那张卡 | 本期只做到"先到列表页再 navigate"，`Sec-Fetch-Site` 已修正；点卡片进详情留作后续 |
| 5 | 预热能否真的改变平台的判定 | 无法直接观测。只能靠"没有出现新的限流告警"作为弱证据，不宣称有效 |

**取证进度（2026-11-01，随 spec 0006 阶段 2 补记）**：第 1、2 条已有实测结论，第 3 条部分取证，
逐条数据见 spec 0006 §8.3。第 4、5 条仍为未取证状态，结论未变。

**取证进度（2026-11-02，随阶段 3 补记）**：第 4 条不变（按岗位定位卡片仍不具备）。补充一条新事实：
`page.navigate` 无论上一页是什么都不带来源，来源只能由"文档发起的导航"产生——这条已由
`ListFirstEntryProbeTest` 用本地站点实测四次进入方式确认，并写进 Decision 6 与 Consequences。

**取证进度（2026-11-02，随阶段 4 补记，Boss 风控解除后）**：

| # | 现状 |
|---|---|
| 1 | 两个平台的列表容器都能稳定命中：Boss 40 轮加载 300 张卡片、智联 6 轮加载 195 张，`wheel` 在其上有位移（DOM 级证据仍受限，见下） |
| 2 | 保持第 1 条的结论：中文只有 `input`、没有字符级 `keydown` |
| 3 | **已用真实页面标定完成**：Boss 的 `contenteditable` 上 3 条招呼语 + 1 条值守回复全部一次写入成功，`Ctrl+A` 作用域没有溢出，`childElementCount` 没有超容差，降级 0 次。容差 2 不需要调整 |
| 4 | 不变：按岗位在列表里定位卡片仍不具备 |
| 5 | 不变：预热的效果仍无法直接观测，只能说"没有新增限流告警" |

一条新的限制：`browser-data/boss` 里的 bsk 扩展当时是 0.3.3，`cooperate` 在该版本里没有实现，
所以 Boss 侧的 DOM 级观测（`snapshot` / `screenshot`）取不到内容。这不是本层的问题，但它意味着
"页面侧确实产生了 `wheel` / `keydown`"这类证据在 Boss 上只能由 `HumanizeBrowserProbeTest`（本地
`data:` 页）与智联的真实页面承担。要补，得把该 profile 的扩展升到 0.4.x。

**该限制在升级到 0.4.1 后的复验结果（2026-11-02 13:00，详见 spec 0006 §10.9）**：截图这一半解决了——
`screenshot --tab-id` 能取到真实画面（0.3.3 时期是 7KB 空白，现在 302KB 真实列表页），`tab list` 的标题与
URL 也一直可用。文本这一半没解决：`observe` / `snapshot` 被沙箱要求先把标签 `tab borrow` 进 Agent Window，
而借入需要人在那个由 Playwright 拉起的浏览器窗口里点一次确认，无值守会话里两次都超时。`cooperate sync`
的错误也从"方法未实现"变成 `Receiving end does not exist`。所以"`wheel` / `keydown` 真的派发过"这一步
仍然只有 `HumanizeBrowserProbeTest` 与智联的历史结论支撑，Boss 侧要到 DOM 文本级取证，**前提是有人在场点确认**。
