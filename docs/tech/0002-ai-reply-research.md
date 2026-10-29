# AI 回复调研：值守从「关键词模板」到「AI 生成」的前置事实

> 触发场景：未读消息里存在 HR 主动打招呼、无关键词开放提问等情况，模板必然未命中（落到 `UNMATCHED`），值守等于漏回。
> 计划形态：AI 回复做成**开关项**。默认仍是「关键词命中 → 固定文案」；开关打开后，关键词命中结果降级为**引导素材**（意图标签 + 备选文案），由 AI 参考后生成回复。
> 本文只记**能被证据支撑的事实**与由此得出的约束，不做最终方案定稿。文末第 10 节列出需要下一轮 grill / spec 收口的问题。

## 0. 脱敏约定

本文及后续同类文档一律不出现：

| 不记录 | 记录替代形式 |
|---|---|
| 真实会话原文、HR 姓名、公司名、岗位标题 | `<HR 首条消息>`、`<平台通知>` 等占位符 |
| 平台岗位加密 id、会话 id 的真实取值 | 只说「平台侧 id 是否稳定」这一属性 |
| 手机号、微信、邮箱、简历内容 | 只记「是否含联系方式」的判定需求 |
| API Key、Cookie、DPAPI 密文、模型 provider 真实配置 | 只记字段名（如 `api_key_cipher`） |
| 含用户名的绝对路径 | 用相对路径（`server/src/...`） |

本次调研**没有读取任何真实会话内容**，也没有读取登录态（做法见第 8 节）。

## 1. 现状事实（代码侧）

| # | 事实 | 证据（相对路径:行） | 对 AI 回复的影响 |
|---|---|---|---|
| F1 | 值守嵌在扫描主循环内，每 5 张卡片或 120s 触发一次，复用当前 page | `server/src/main/java/com/chatjob/orchestrator/RunService.java:50,52,364-368` | AI 调用量与「每轮未读会话数」成正比，且会直接拖慢扫描主链 |
| F2 | 判定只取**最后一条**消息 | `watcher/UnreadWatcher.java:59`（`messages.get(size-1)`） | 主动打招呼只有一句，AI 拿不到对话上下文；上一条是自己发的则整条跳过 |
| F3 | 消息身份是构造出来的：`序号:文本hash` | `platform/boss/BossAdapter.java:598` | 新消息使序号偏移、相同文本重复，去重键会漂移（见 P1，已有更优解） |
| F4 | 消息模型没有类型维度，只有 `image` 布尔 | `platform/Models.java:74`；`BossAdapter.java:587-598` 遍历消息条目时**无系统消息过滤** | 平台通知/卡片会被当成 HR 消息喂给 AI（判据已找到，见 F15） |
| F5 | 判定顺序：自我 → 重复 → 敏感词 → 最长关键词 → 冷却 → 随机文案 | `watcher/ReplyRuleEngine.java:55-109` | AI 只能插在「命中规则」与「未命中」之间，敏感词与自我消息必须仍在 AI 之前 |
| F6 | 敏感词一票否决，先于关键词 | `ReplyRuleEngine.java:67-74` | 现只校验**入站**文本；AI 的**出站**文本目前无任何风控 |
| F7 | 同会话冷却 3 分钟，且只在「命中规则」之后判定 | `ReplyStore.java:31,119-129`；`ReplyRuleEngine.java:98` | 未命中分支一旦交给 AI，冷却判定必须前移，否则同一会话可被连续刷 |
| F8 | 回复硬约束：单条 ≤ 60 字、纯文本、不带表情 | `docs/spec/0001-chat-job-mvp.md:203` | AI 输出需要同等强度的截断与清洗，不能依赖提示词自觉 |
| F9 | 已有可复用的 AI 调用骨架：单次调用、strict JSON、连接+读双超时、失败即跳过、自定义头、DPAPI 解密 | `evaluate/AiJudge.java:67-152` | 不需要新建 provider 体系，复制这套骨架即可 |
| F10 | 已有「开关」先例：`ai_config.quality_gate_enabled` 单行配置，`collect_effect_metrics` 运行期读取 | `db/migration/V1__schema.sql:27,31`；`store/entity/AiConfigEntity.java:18`；`RunService.java:326` | AI 回复开关可沿用同一载体（单行表 + `/api/config/ai`），改动面最小 |
| F11 | 会话表与回复日志的现有字段 | `V1__schema.sql:115-127`（conversation）、`V1__schema.sql:130-142`（reply_log） | `reply_log` 无「来源/模型/耗时/兜底」字段，AI 回复事后不可回溯 |
| F12 | 前端落点：配置 → 值守规则三块（敏感词 / 规则 / 未命中） | `console/components/ReplyRules.vue`；`docs/spec/0001-chat-job-mvp.md:205` | 开关放「AI 配置」还是「值守规则」需一次产品级取舍 |
| F13 | spec 明确把「自动回复的语义理解」列为**不做** | `docs/spec/0001-chat-job-mvp.md:62` | 本次是显式扩范围，必须先决定改 0001 还是另开 spec |

## 2. 站点侧事实（从线上静态资源取证）

取证方式：站点在 CDP 挂载下会周期性自我重载（约 3 秒，见第 8 节），运行时 DOM 无法稳定读取；先用同版本公开静态资源做结构与语义分析（版本 `chat-new/v5575`，构建哈希 `01b38f51`，涉及 `app.01b38f51.js`、`chat.01b38f51.js`、`app.01b38f51.css`、`chat.01b38f51.css`），再用项目自带 patchright 驱动补齐真实页面取证（第 8、9 节）。两轮结论一致的部分标为已确认，不一致的部分以实测为准并注明。

| # | 事实 | 证据形态 | 对设计的影响 |
|---|---|---|---|
| F14 | 每条消息是 `li.message-item`，且**带 `data-mid`**（平台消息 id） | 模板：`n("li",{class:["message-item",getClass(m)],attrs:{"data-mid":m.mid}})` | **P1 有现成解**：稳定消息键就是 `data-mid`，不必再用序号+hash |
| F15 | 系统/本人/对方三类由同一个 `getClass` 判定 | `text & templateId===3 & extend.textFormat===1` → `item-system content-left`；`text & templateId===3` 或 `status===3` 或 `hyperLink` 或 `action` → `item-system`；`isSelf` → `item-myself`；否则 `item-friend` | **P3 有现成判据**：DOM 类名已区分三类，系统消息可直接按 `item-system` 跳过 |
| F16 | 消息有 `messageType` 与 `templateId` 两个维度 | `messageType` 取值含 `text / image / sticker / sound / video / hyperLink / articles / dialog / action / listCard / interview / jobDesc / resume / comDesc` | 非文本消息按类型白名单过滤，AI 只吃 `text` |
| F16b | 系统消息判定还有 `isSystemText = templateId===3 \|\| status===3`，用它隐藏「送达状态」组件 | 同组件 `isSystemText` 计算属性 | 与我们自己的「系统消息跳过」判据天然一致 |
| F17 | `templateId` 在文本消息里承载语义 | 3 = 系统样式文本；5 = 交换联系方式卡片（含 mobileVisible/weixinVisible）；12 = 「BOSS 预留回答」卡片；13 = IconText | 涉及联系方式的卡片必须整条跳过（合规） |
| F18 | 未读徽标实际是 `span.notice-badge`（>999 显示 999），无数字时是 `span.dot` | 列表项组件 `boss-item` 的模板；两个 CSS 中 `.notice-badge` 均有定义 | 项目 `CHAT_LIST_UNREAD = ".badge-count, .unread-count"` **在全部静态资源中 0 命中**（见第 3 节） |
| F19 | 消息区结构是 `div.chat-record > div.chat-message`（滚动容器）`> li.message-item` | 组件 `chat-record` 模板中 `ref="scrollContainer"` 挂在 `.chat-message` 上 | 项目 `MESSAGE_ITEM` 的 `.chat-message-list` 前缀 **0 命中**；实际容器是 `.chat-message` |
| F20 | 消息列表**分页加载**，`pageSize: 20`、向上翻页用 `msgMinId` | 组件 `message-list` 的 `data`：`{msgMinId, page, pageSize:20}` | 「上下文窗口」的天然上限就是最近 20 条；再往前需要滚动触发 load |
| F21 | 会话列表是**虚拟滚动**（`VirtualList`，`data-key="uniqueId"`，`data-sources=list`） | 模板：`div.chat-content > div.user-list > VirtualList.user-list-content` | 只有视口内的会话存在于 DOM。**值守扫描覆盖面天然受限**，不是「列表里所有未读都能读到」 |
| F22 | 消息状态枚举：`LOADING 0 / DELIVERED 1 / READ 2 / UNKNOWN 3 / ERROR 4` | 常量表 `ge={LOADING:0,DELIVERED:1,READ:2,UNKNOWN:3,ERROR:4}` | 「送达判据」可以对齐平台语义，不必只靠条数变化 |
| F23 | 平台会给消息挂 `extend.aiChatMsgMark`，非本人文本消息下方渲染 `.chat-helper` 提示 | 模板：`!isSelf && extend.aiChatMsgMark → div.chat-helper` | 平台对「AI 参与对话」有可见标注机制，AI 回复的合规风险需要单列（本项含义待确认） |
| F24 | `extend.greetingQuestionAnswer === 1` 时渲染 `MessageOpener` 卡片（`.chat-opener-box`，含标题+文本+按钮） | 模板分支：`extend.question && 1==extend.greetingQuestionAnswer ? MessageOpener` | **实测修正**：在「对方连发 2 条、我方一条没回」的会话里，两条消息都是普通 `li.message-item.item-friend`，`.chat-opener-box` 命中 **0**。不能假定「主动打招呼必然是 opener 卡片」，上下文组装必须以 `li.message-item` 为唯一入口，opener 只作可选增强 |

## 3. 现有选择器的偏差（已实测确认，不再是推断）

取证方式：用项目自带 patchright 驱动 + 专用 profile（`browser-data`）打开真实沟通页，把 `BossSelectors` 的每个常量拿到页面上数一遍。下表 `count` 是真实 `querySelectorAll().length`：

| 项目常量 | 取值 | 实测命中 | 判断 |
|---|---|---|---|
| `CHAT_LIST_ITEM` | `li[role='listitem']` | 35 | ✅ |
| `CHAT_LIST_TITLE` | `div.title-box span.name-box` | 35 | ✅ |
| `CHAT_LIST_LAST_MSG` | `div.gray.last-msg span.last-msg-text` | 35 | ✅ |
| `CHAT_LIST_UNREAD` | `.badge-count, .unread-count` | **0** | ❌ 失效。实际是 `span.notice-badge`（同页 9~10 个，内含纯数字），挂在 `div.figure` 上、祖父为 `div.friend-content`；`.dot` 该页为 0 |
| `MESSAGE_ITEM` 候选1 | `.chat-message-list li.message-item` | **0** | ❌ `.chat-message-list` 不存在 |
| `MESSAGE_ITEM` 候选2 | `.dialog-con li.message-item` | **0** | ❌ `.dialog-con` 不存在 |
| `MESSAGE_ITEM` 裸写法 | `li.message-item` | 2 | ✅ 真实消息项就是 `li.message-item`，父容器 `ul.im-list`，再上为 `div.chat-message` |
| `MESSAGE_SELF` | `li.message-item.item-myself` | 1（另一会话） | ✅ |
| `MESSAGE_TEXT` | `.text, .message-text, .content` | 37 | ⚠️ 可用但过宽，`.content` 会误命中卡片容器 |
| `MESSAGE_IMAGE` | `img.message-image` | 0 | ⚠️ 该会话无图片消息，无法判定 |
| `SEND_BUTTON` | `div.send-message, button[type='send'].btn-send, button.btn-send` | 0 / 1 / 1 | ✅ 后两个候选命中（实为 `button.btn-v2.btn-sure-v2.btn-send`） |
| `CHAT_INPUT` | `div#chat-input.chat-input[contenteditable='true']` | 1 | ✅（输入框实为 `div.chat-input[contenteditable]`，外层 `div.chat-im.chat-editor`） |

**两处致命失效，且是串联的：**

1. `CHAT_LIST_UNREAD` → 未读恒读成 0 → `UnreadWatcher` 跳过所有会话。因果链：`firstText` 拿到空串 → `parseInt` 的 `isBlank()` 分支返回 0（`BossAdapter.java:568-571`）→ `UnreadWatcher.java:49` 判 `unread <= 0` 直接 `continue`。**净效果是「值守一条都不回」，且日志上一切正常**（不打 warn）。
2. `MESSAGE_ITEM` → 两个候选都 0 → **即使未读修好，消息也读不到**。

合起来的含义比静态阶段判断的更重：当前线上版本的值守链路是**「入口断」+「读取断」双断点**，不是单点失效。两处都只需改选择器，不动结构，风险极低。

修法（三个细节都来自实测，不要凭推测写）：

- 未读选择器用 **`.friend-content .notice-badge, .friend-content .dot`**，限定在会话条目内。理由是页面上另有 `header-nav-dot`（导航栏红点），不加限域会误判成一个未读会话。
- 消息项用 `ul.im-list li.message-item`（或裸 `li.message-item`），删掉两个不存在的祖先候选。
- 消息区的滚动容器是 `div.chat-message`（不是 `.chat-message-list`），外层还有 `div.chat-record`；深度扫描确认消息区内**没有** `dialog-con`。

## 4. 关键结论：开开关之前必须先修的 4 件事

**P1 消息身份要稳（去重的前提）**
用 `li.message-item[data-mid]` 取代「序号 + 文本 hash」。现状 `wasProcessed`（`ReplyStore.java:149-157`）依赖构造键，列表增长就漂移；AI 每轮都可能「有话可说」，一旦去重失效，同一个 HR 会连收多条不同内容。

**P2 上下文要有窗口**
取最近 6~10 条（含自己发的），而不是最后一条。天然上限 20 条（F20），够用；改动集中在 `UnreadWatcher` 的组装段，不牵动平台层。`MessageOpener` 卡片（`extend.question`）实测在「对方主动打招呼」场景并未出现（F24），因此以 `li.message-item` 为唯一入口；若某条消息确实是 opener，再额外读 `extend.question`。

**P3 消息要分类**
直接按 DOM 类名三分：`item-myself` / `item-friend` / `item-system`（F15），再叠加 `messageType`（F16）与 `templateId`（F17）过滤非文本与联系方式卡片。系统消息、时间分隔（`.item-time` 是消息项的子节点，不是独立条目）都要跳过。

**P4 出站文本要过风控**
AI 生成后必须：①敏感词二次校验 ②长度截断 60 字 ③空白与异常字符清洗 ④联系方式正则拦截 → 命中任一条即降级为「不回复」并落日志。理由：F23 说明平台对 AI 消息有标注痕迹，被判定滥用的代价比漏回高。

## 5. 开关粒度（候选，未定稿）

| 粒度 | 落点 | 优点 | 代价 |
|---|---|---|---|
| 全局单开关 | `ai_config` 增一列 + `/api/config/ai` | 改动最小，与你描述一致；默认关闭=零行为变化 | 无法只对某类会话放开 |
| 规则级 | `reply_rule` 增 `ai_enabled` | 可让「约面试」走 AI、「简历索取」仍走固定文案 | 解决不了「未命中」冷启动，而冷启动正是这次要解决的 |
| 会话级 | `conversation` 增标记 | 能专门处理 HR 主动打招呼的首轮 | 没有规则承载处，配置面最重 |

倾向：**全局开关 + 规则继续作为引导素材**。本次要解决的是「模板没命中」，规则级开关只优化已命中项，方向不对。

## 6. AI 真正要覆盖的五种形态

| 形态 | 平台侧事实 | 模板能否覆盖 | AI 的价值 |
|---|---|---|---|
| HR 主动打招呼 | `.chat-opener-box` + `greetingQuestionAnswer`（F24） | 否 | 唯一兜底手段，也是原始诉求 |
| 对方普通文本问候 | `item-friend` + `text`（F15/F16） | 否（无关键词） | 同上 |
| 无关键词的开放提问 | 同上 | 否 | 同上 |
| 命中规则但语义偏差 | 命中「简历索取」，实际在问作品集 | 部分 | 命中结果当**意图标签**，文案当**参考**，AI 改写 |
| 系统通知 / 卡片误入 | `item-system`、`jobDesc`、`resume`、联系方式卡片（F15/F17） | 不应回复 | 应 skip，不进 AI |

第 4 行是「AI 参考关键词」这个设计的核心价值：规则不复位为内容源，而复位为**意图 + 语气参考**。

## 7. 成本与延迟

- 单轮调用量的上界：未读会话数 × 1 次。但受 F21 限制，实际能读到的会话数取决于视口，**先按 ≤ 10 估**。
- 现有超时不适合直接套用：`ai_timeout_seconds` 默认 20s（`V1__schema.sql:30`）。10 个会话串行 × 20s = 3 分钟以上，会把扫描主链拖死。需要：AI 回复单独更短超时（建议 6~8s）+ 每轮 AI 调用上限（建议 ≤ 5 条）+ 超出即跳过。
- 线程位置：`sweep` 内的判定段（`UnreadWatcher.java:62`）在 Playwright executor 之外执行，AI 的 HTTP 调用放这里不会死锁；但它仍在扫描主线程内串行，**上限与超时是硬要求**。

## 8. 取证过程：为什么没有运行时 DOM

1. 目标：只读取证沟通页的 DOM 结构（消息 id、系统消息特征、开场白结构）。
2. 第一轮观察（**已推翻的误判**）：读到的总是 `document.readyState = loading`、`body` 为空、截图纯白，据此初判为「站点在 CDP 挂载下主动阻断渲染」。该结论错误。
3. 反证（人证）：用户在同一个窗口里能看到会话内容与未读红点，说明渲染能力本身存在。之前的「阻断」是**抓取时机**造成的错觉。
4. 判别实验（静置法，用于检验「是否操作过快触发风控」）：打开页面后注入 `window.__probe`，随后 **42 秒内零操作**，再读一次。结果：`probeAlive = false`、`performance.timeOrigin` 变化、`sinceStartMs ≈ 1.0s`。→ 页面在此期间被重新导航过，**与操作频率无关，「刷太快触发风控」不成立**。
5. 重载特征：`navigation.type = navigate`（不是 `reload`）、`document.referrer` 等于页面自身路径、`history.length = 2`。→ 是页面脚本主动跳转到同一地址，形成**自我重载循环**。
6. 周期估计：两次采样分别落在 `sinceStartMs ≈ 2866ms` 与 `2918ms`（真实间隔 3 秒）→ 重载周期约 3 秒。窗口期内曾读到 `ready = complete` 且 `body` 有 7 个顶层节点，证实**渲染能完成，但很快被重置**。
7. 机制指向：控制台里站点安全脚本反复探测 `ws://127.0.0.1:<端口>`（本地调试端口）。结合 ADR-0001 关于 `Runtime.enable` 检测的记录，判断为**站点识别到 CDP 挂载后触发的自我重载**。生产链路走 patchright + 专用 profile，不经这条路径，因此不受影响。
8. 替代方案（本次采用）：下载同版本公开静态资源，用类名/模板/常量表反推结构与语义。**不涉及登录态、不读任何会话内容**，天然满足脱敏要求。
9. 局限：静态分析给的是「代码里存在什么」，不是「这台机器上现在渲染成什么」。因此第 3 节的选择器偏差标为「高置信、待运行确认」。
10. 运行时取证已完成：`tools/probe-chat-dom.mjs` 用 `build/patchright-driver` + `browser-data`（用户扫码一次）打开真实页面，逐个数出 `BossSelectors` 每个常量的命中数。**patchright 下页面稳定（`ready=complete`，无自我重载）**，与 CDP 直连的表现形成对照，印证第 7 条的机制判断。

## 9. 运行时验证结果（已执行）

工具：`tools/probe-chat-dom.mjs`。原始输出在 `tools/out/`（`probe-selectors.json` 是逐项命中表）。

| # | 原待确认项 | 结果 |
|---|---|---|
| 1 | `CHAT_LIST_UNREAD` 是否读不到 | **确认读不到**：现用选择器命中 0；实际 `.notice-badge` 命中 9~10 且内含数字 |
| 2 | 消息容器类名 | **`ul.im-list`**（父 `div.chat-message`，再上 `div.chat-record`）；`.chat-message-list` 与 `.dialog-con` 均 0 命中 |
| 3 | `data-mid` 是否稳定 | **确认存在**：挂在 `li.message-item` 上，长度统一 15 位，采样 2/2 非空。「两次读取前缀一致」尚未比对，开发时补一次即可 |
| 4 | `.chat-opener-box` 出现频率 | 在「对方主动发 2 条、我方未回」的会话中命中 **0**，消息是普通 `item-friend`。**不能作为主路径依赖** |
| 5 | 发送控件类名 | **`button.btn-v2.btn-sure-v2.btn-send`**，现有 `button.btn-send` 候选已能命中；输入框为 `div.chat-input[contenteditable]` |
| 6 | `extend.aiChatMsgMark` 含义 | **仍未确认**：消息区无任何 ai/robot/helper 类名，该标注未在 DOM 暴露（可能是数据字段或特定场景才渲染）。与 AI 回复无强依赖，可留到上线后观察 |

> 取证脚本只读类名、标签名与计数，未读取任何消息正文；全程未输出 HR 姓名、公司、岗位或加密 id。

## 10. 功能可用前置条件自检

判定标准不是「选择器命中」，而是「发现未读 → 读上下文 → 组装 → 写入 → 发送」这条主链每一环是否真能跑通。

脚本：`tools/precheck.mjs`（端到端）、`tools/probe-message-kinds.mjs`（消息类型采样）。原始输出：`tools/out/precheck.json`、`tools/out/message-kinds.json`。

| 编号 | 前置条件 | 结果 | 证据 |
|---|---|---|---|
| C1 | 会话列表可读 | ✅ | DOM 内 35 条（`[class*=friend-content-warp]`） |
| C2 | 未读数可读且可解析 | ✅ | 未读 9 条，解析失败 0，纯 `dot` 徽标 0（当前未读全部带数字） |
| C3 | 消息项可读 | ✅ | 每会话 `li.message-item` 2 条 |
| C4 | 对方消息可取 | ✅ | `item-friend`，文本长度 22 / 44 / 38 |
| C5 | 我方消息可判定 | ✅ | 采样会话 #5：`item-myself` 1 条，文本长度 261 |
| C6 | 消息 id 稳定 | ✅ | 间隔 2.5s 两次读取，mid 哈希序列完全一致 |
| C7 | 非文本消息可识别并跳过 | ✅ | 「取不到文本」的消息**全部**是职位卡片：`messageCard:1 + articlesCenter:1 + img:8`，无一条属于「文本读取失败」 |
| C8 | 输入框可写入 | ✅ | 写入后文本长度 4，清理后归 0 |
| C9 | 发送按钮存在且可点 | ✅ | `button.btn-v2.btn-sure-v2.btn-send` |
| C10 | **真发送成功** | ⏳ 未验证 | 按钮禁用是 CSS 类（`... btn-send disabled`）而非原生 `disabled` 属性 → `isDisabled()` 恒为 false，**状态判据无效**，只能真发一条才能确认 |
| C11 | 虚拟滚动是否挡住未读 | ✅（当前） | 列表可视 905px / 内容 905px，35 条全部渲染；会话数增长后需复核 |
| C12 | 系统消息识别 | ⏳ 无样本 | 4 个采样会话 `item-system` 均为 0 —— 是**没有样本**而非判定失效。实现不依赖它（只吃 text 白名单） |

**结论：发现、读取、组装上下文、写入四环已实测通过；只差「点击发送」这一步没有真实验证。**

C10 没有当场验证的理由：真发会在真实求职会话里留下一条无法干净撤回的消息，属于污染用户数据。建议把它定为开发阶段的第一个冒烟项，用可控会话执行。

### 10.1 两个必须先定的业务约束

**B1 点开会话即清未读，且平台没有「标回未读」的能力。** 两个后果：

- 程序点开读完、AI 调用却失败/超时 → 红点已消失，下一轮扫描不会再看到这个会话 → 必须靠 `reply_log` 落库兜底「已读未处理」，否则这条消息永久错过；
- 程序会把用户自己的未读提示吃掉。消息还在，红点没了。

**B2 未读数只有数字，不含「最后一条是谁发的」。** 当前 9 个未读样本全部是对方消息，所以「未读 = 对方找你」这条假设暂时成立；但严格实现时应以消息区最后一条的归属为准，不能只信计数。

## 11. 未决问题（下一轮 grill 用）

1. AI 的「人设」从哪来：复用 `ai_config.introduce`，还是新增值守专用提示词字段？
2. 命中规则时，AI 是「改写命中文案」还是「按意图自由生成」？前者可控、后者覆盖面广。
3. 未命中会话是否也要进 `reply_log`（现状记 `UNMATCHED`），以便复盘 AI 未覆盖的形态？
4. AI 是否允许主动追问（如「方便发下作品集吗」）—— 触及「不承诺、不越界」的边界，需要明确禁止清单。
5. 是否需要独立的「AI 回复配额」，还是共用投递配额的思路？
6. spec 收口：改 `docs/spec/0001-chat-job-mvp.md:62` 的不做项，还是新开 spec 0002 专讲值守语义化？
7. 第 3 节的选择器偏差是否单独开一张 ticket 先修（建议先修，否则 AI 开关无处发力）。
8. B1 的取舍：接受「点开即清未读」，还是要求在点开前先把（未读数 + 最后一条摘要 + data-mid）落快照，失败时靠快照重试？
9. C10 冒烟放在哪个环节、用哪个会话执行（建议开发第一步就做，避免整条链路写完才发现发不出去）。
