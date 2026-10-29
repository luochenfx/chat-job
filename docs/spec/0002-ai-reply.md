# 值守 AI 回复（开关式）

## 1. 上下文锚定

### 1.1 这是什么

在现有「关键词命中即固定回复」的值守上加一个**全局开关**：

- **关闭（默认）**：行为与今天完全一致，命中关键词发模板、未命中静默丢弃。
- **打开**：关键词命中仍然生效，但**命中的文案降级为参考素材**交给 AI，由 AI 结合最近几条对话生成回复；未命中的会话也交给 AI 判断能否回。

一句话：模板从「答案」变成「提示」。

### 1.2 为什么要做

两个具体缺口，都能在数据上指出来：

- **对方主动打招呼没有固定套路。** 实测 4 个采样会话，其中 3 个是「对方连发 2 条、我方零回复」，内容是「打招呼一句话 + 一张职位卡片」。这类开场用关键词匹配几乎必然落空。
- **未命中等于静默丢弃。** 现状未命中只落一条 `UNMATCHED`（`ReplyRuleEngine.java:95-97`、`UnreadWatcher.java:81-82`），除了人工补规则没有其他去处。人的问法是长尾，规则永远追不上。

### 1.3 现状与边界（实测，不是推断）

**先说最要紧的：当前线上值守链路是断的，本 spec 的第一步不是 AI。**

| 位置 | 现状 | 实测 |
|---|---|---|
| 未读识别 | `CHAT_LIST_UNREAD = ".badge-count, .unread-count"` | **命中 0**；真实类名 `.notice-badge`，同页 9~10 个且带数字 |
| 消息读取 | `MESSAGE_ITEM` 两个候选 `.chat-message-list` / `.dialog-con` | **均命中 0**；真实结构 `div.chat-record > div.chat-message > ul.im-list > li.message-item` |
| 消息 id | `i + ":" + text.hashCode()`（`BossAdapter.java:598`） | 拼出来的，列表一长就漂移；真实 `data-mid` 稳定存在（15 位、非空、两次读取一致） |
| 会话 id | `new UnreadThread(title, title, ...)`（`BossAdapter.java:546`） | 会话 id **就是 HR 标题** |
| 会话定位 | `setHasText(thread.title())`（`BossAdapter.java:625`） | **包含匹配**，「张三」会命中「张三丰」 |

会话层还有一条硬事实：会话条目上**没有任何稳定标识**。实测 `li[role='listitem']` 只有 `role` / `class` 与 Vue 作用域属性，无 `data-key` / `data-uid`，点开会话 URL 也不变。这是本方案绕不开的限制，见 3.4。

### 1.4 术语

- **AI 回复开关**：`ai_config.ai_reply_enabled`，单行配置，默认 0。先例是同一行的 `quality_gate_enabled`。
- **未读任务**：点开会话之前先落的一行记录（`unread_task`），承载「这条未读还没处理完」这件事。
- **参考素材**：命中规则的关键词与文案，交给 AI 时明确标注「这是你可以参考的说法，不是必须照抄」。
- **放弃**：AI 或风控判定这条不该回，落一条记录后不再重试。

## 2. 约束与目标

### 2.1 硬约束

1. **关闭即 no-op。** 开关为 0 时，`UnreadWatcher` 的判定顺序、冷却、日志、发送路径与现在逐行一致。不允许"顺手优化"。
2. **先落任务，再点开会话。** 实测点开会话即清未读，平台没有「标回未读」的能力。顺序错了就会丢消息。
3. **重试上限 3 次。** 超过即 `GAVE_UP`，不再自动处理，在控制台可见。
4. **会话定位禁止包含匹配。** 必须 `name-box` 文本**精确相等**；点开前再用「最后一条消息摘要」做一次校验。理由见 3.4。
5. **单条回复 ≤ 60 字**，纯文本。
6. **出站四重校验**：敏感词、联系方式、薪资/承诺词、AI 味。任一命中即整条不发。
7. **AI 失败不阻塞扫描主链。** 与现有值守同样的容错口径（`UnreadWatcher.java:88-90`）。
8. **AI 调用超时 ≤ 8s，单轮 AI 调用 ≤ 5 条。** 现状 `ai_timeout_seconds` 默认 20s，10 个会话串行会把主链拖死 3 分钟以上。
9. **表达风格由配置约束，且不信任 prompt。** `ai_config.ai_reply_style` 注入 prompt，定义为「软约束」；出站必须再过一道代码层的「AI 味」检查（3.7）。理由是 prompt 约束本质是建议，模型随时可以不遵守，唯一可靠的落点是自己写的那段代码。
10. **AI 主动不回必须记原因，且原因是枚举。** 允许 `skip`，但不接受「静默不回」。原因落 `reply_log.detail`，取值限定在 3.6 的白名单内（自由文本只作为补充说明），否则运维无法统计「它到底在躲什么」。
11. **`GAVE_UP` 的手动重试不默认展示。** 界面上默认只给计数，按钮藏在展开的失败详情里。避免把「已放弃」变成一条鼓励人反复点的主路径。
12. **词表与告警阈值可配，但默认值必须是有效值。** 清空词表 = 用默认 31 条，**不是**关闭检查；阈值 0 = 关闭告警，但界面必须显式写「告警已关闭」。任何「看起来空着所以大概没在工作」的状态都不允许存在。
13. **词表与告警只影响展示与拦截判断，不得自动改 AI 行为。** 不因为占比超标就自动关开关，不因为误杀就自动删词。自动化的只有统计，决策留给人。

### 2.2 目标（完成的定义）

- 开关关闭：不改任何现有行为，现有单测全绿。
- 开关打开：对「对方主动打招呼」这类未命中会话，能生成一条 ≤60 字、通过四重校验的回复并发出。
- 生成的回复**不含典型 AI 话术**（通过 3.7 的「AI 味」检查），且风格符合 `ai_reply_style` 的描述。
- AI 主动不回时，`reply_log` 里有一条带枚举原因的记录，能按原因分组统计（例如「`LOW_CONFIDENCE` 占比过高」说明 prompt 需要调）。
- 控制台能看见 skip 占比与告警状态，且「样本不足」「告警已关闭」各有明确文案，不与「正常」混同。
- 任何一环失败（AI 超时、JSON 非法、风控拦截、发送失败），都能在控制台看到这条消息的去向，且**不会**因为红点已消失而永久丢失。
- 控制台能看见：待处理 / 已处理 / 重试中 / 已放弃 四类计数，以及每条失败的原因。

### 2.3 非目标（明确不做）

- 不做多轮长对话。一次未读只回一条，不做追问链。
- 不做自动约面、自动发简历、自动索要联系方式。
- 不改投递链路的 AI 判定（`AiJudge` 只读复用，不改其语义）。
- 不做「AI 判断要不要主动发起会话」——只在对方先说话时回。
- 不解决「同名 HR 混淆」。这是平台数据限制，见 5.3。
- **不做对抗平台检测的技术手段**（浏览器指纹改写、模拟人类输入节奏等）。本 spec 的「AI 味」只针对**文案本身**是否像模板套话，不涉及行为伪装。这条边界必须守住：前者是产品质量，后者是另一类风险。

## 3. 方案设计

### 3.1 分层与改动边界

```
platform/boss/BossSelectors.java    ← P0 选择器修正（唯一必改的平台层文件）
platform/boss/BossAdapter.java      ← P0 消息 id 改用 data-mid；会话定位改精确匹配
watcher/UnreadWatcher.java          ← 落任务 → 读消息 → 判定 → 回复 的编排
watcher/ReplyRuleEngine.java        ← 不改判定顺序，仅新增「AI 分支的输入输出契约」
watcher/AiReplier.java              ← 新增：AI 回复生成（人设 + 风格 + skip 原因）
watcher/ReplyGuard.java             ← 新增：出站四检，纯函数
watcher/UnreadTaskStore.java        ← 新增：任务的落库/重试/放弃/手动重试
store/entity/UnreadTaskEntity.java  ← 新增
web/ConfigController.java           ← 新增 4 个配置字段
web/WatcherController.java          ← 新增：任务查询 + 手动重试
```

平台层只动 `BossSelectors` 与 `BossAdapter` 的两处读取，不新增抽象。

### 3.2 P0 选择器修正（不修则后面全部无效）

| 常量 | 改为 | 依据 |
|---|---|---|
| `CHAT_LIST_UNREAD` | `.friend-content .notice-badge, .friend-content .dot` | 实测 `.notice-badge` 命中 9~10 且含数字；限域到 `.friend-content` 是为了排除 `header-nav-dot`（导航栏红点），否则会把导航红点算成一个未读会话 |
| `MESSAGE_ITEM` | `ul.im-list li.message-item` | 实测父容器是 `ul.im-list`；`.chat-message-list` 与 `.dialog-con` 均不存在 |
| `MESSAGE_SELF` | 保持 `li.message-item.item-myself, .item-myself` | 实测命中（会话 #5 有 1 条） |
| `MESSAGE_TEXT` | `.text, .message-text` | 去掉过宽的 `.content`：实测命中 37 个，会把卡片容器也算进来 |
| `SEND_BUTTON` | 保持 | `button.btn-send` 实测命中（实为 `button.btn-v2.btn-sure-v2.btn-send`） |
| `CHAT_INPUT` | 保持 | `div#chat-input.chat-input[contenteditable='true']` 实测命中 |

顺带修 `parseInt` 的静默失败（`BossAdapter.java:568-574`）：目前读不到文本时返回 0，而 0 会被 `UnreadWatcher.java:49` 当成「没有未读」。改为返回 `-1` 表示读取失败，并在日志里打一次 warn —— 让「读不到」和「确实没未读」在日志上可区分。这条改动很小，但它决定了以后同类问题是被发现还是被隐藏。

### 3.3 消息 id 与上下文组装

**消息 id** 改用 `data-mid`（`BossAdapter.java:598`）。`ChatMessage.id` 语义不变（字符串主键），只是来源从「构造」换成「官方」。这一处改动同时解决三件事：去重不再漂移、`reply_log.platform_message_id` 可对账、任务重试能精确定位到具体某条消息。

**上下文窗口**：取最近 8 条（不足则全取），上限受平台分页 `pageSize: 20` 约束。组装成对话数组，每条标注 `HR:` / `我:`。

**非文本消息**：`li.message-item` 内没有 `.text` 且含 `.message-card-wrap` 或 `.articles-center` 的，判定为卡片，跳过不喂给 AI。实测「取不到文本」的消息**全部**是职位卡片（`messageCard:1 + articlesCenter:1 + img:8`），不是读取失败。

### 3.4 会话定位（为什么不能用包含匹配）

`openThread` 现在是 `setHasText(thread.title())`，这是包含匹配。碰上「张三」与「张三丰」两个 HR，它会点开先命中的那个。**在重试场景下这意味着把回复发给错误的人**。

而会话条目没有稳定 id（1.3），所以只能这样做：

1. **精确匹配**：遍历 `li[role='listitem']`，取 `.name-box` 的 `innerText.trim()`，与快照里的标题**全等**才命中。
2. **二次校验**：命中后读该条目的 `.last-msg-text`，与任务快照里的摘要比对；不一致说明列表已更新（有新消息或换人），此时**放弃本次重试并记 `GAVE_UP`**，不冒险发送。
3. 匹配到多条同标题时，一律放弃并记 `AMBIGUOUS_TITLE`，交人工。

宁可漏回，不可错发。这与现有 `fail-closed` 口径一致。

### 3.5 未读任务与重试

新增表 `unread_task`。**在 `readMessages` 之前落库**，这是整个方案的关键时序：

```
listUnreadThreads()
  → 对每个 unread > 0 的会话：upsert unread_task(status=PENDING)   ← 红点此刻还在
  → readMessages()                                                  ← 红点在这里消失
  → 判定 + 回复
  → 成功：status=DONE，回填 platform_message_id
  → 失败：status=FAILED，attempt_count+1，设 next_retry_at
```

**重试优先于新未读**：每轮 `sweep` 先取出 `status='FAILED' AND attempt_count < 3 AND next_retry_at <= now` 的任务处理，再去扫新未读。因为这些会话的红点已经消失了，列表里再也看不见，只能靠任务表捞回来。

**退避**：第 1 次失败 1 分钟后重试，第 2 次 5 分钟，第 3 次 15 分钟。到 3 次仍失败 → `GAVE_UP`。

**手动重试**（3.8.3）只改状态不绕过校验：`GAVE_UP → PENDING`、`attempt_count = 0`、`next_retry_at = now`。它走的仍是同一条重试路径，所以 3.4 的精确匹配与摘要校验照样生效 —— 人工点击不构成「跳过安全检查」的理由。

**去重键**：`(platform, platform_conversation_id)` 上做部分唯一索引，只约束未终结状态。这样同一会话同时只有一条在途任务，不会因为多轮扫描堆积。

```sql
CREATE UNIQUE INDEX IF NOT EXISTS uk_unread_task_open
    ON unread_task(platform, platform_conversation_id)
    WHERE status IN ('PENDING', 'PROCESSING', 'FAILED');
```

### 3.6 AI 回复编排

判定顺序（**冷却前移到 AI 之前**，这是与现状唯一的结构性差异）：

| 步骤 | 判断 | 关闭时 | 打开时 |
|---|---|---|---|
| 1 | 自己发的 | 跳过 | 跳过 |
| 2 | 消息已处理过（`data-mid` 去重） | 跳过 | 跳过 |
| 3 | 冷却期内（3 分钟） | 命中规则才判 | **一律在调用 AI 前判**，避免白花 token |
| 4 | 命中敏感词 | 整条不回 | 整条不回（AI 也不参与） |
| 5 | 命中关键词 | 发模板文案 | 模板文案作为**参考素材**交 AI |
| 6 | 未命中 | 记 `UNMATCHED` | **交 AI**（这是本次要打开的口子） |
| 7 | 出站四检 | 只做长度截断 | 四重校验，任一命中即整条不发 |

第 4 步不变的理由：薪资、面试时间这类话题，模板和 AI 都不该碰，先把闸门关掉最省事。

**AI 调用契约**（沿用 `AiJudge` 的骨架：单次调用、strict JSON、连接+读双超时、失败即跳过）：

- 输入：人设素材（`ai_config.introduce`）、**表达风格（`ai_config.ai_reply_style`）**、最近 8 条对话、参考素材（规则名 + 命中文案，仅在命中时）、禁止清单（敏感词）。
- 输出：`{"reply": "...", "skip": false, "skipReason": null, "reason": ""}`；允许 AI 主动 `skip`，这是重要的安全阀。
- 超时：`min(ai_reply_timeout_seconds=8, provider.timeout_seconds)`。
- 失败：记 `AI_FAILED`，若命中规则则降级用模板文案，否则不回。

**降级策略**：AI 技术性失败时，若命中规则 → 用模板文案（人工审核过，安全）；未命中 → 记 `AI_FAILED` 进重试链。这样「AI 挂了」不会比现在更差。

> **与原稿的偏差（P3 实施时裁决）**：本节原写「未命中 → 记 `AI_SKIPPED`」，与 §3.6.2「技术性失败（超时/非 200/JSON 非法/reply 为空）**不算 skip**，走 `AI_FAILED`」直接冲突。按 §3.6.2 执行：`AI_SKIPPED` 是终态，`AI_FAILED` 要重试，混用会让「模型在躲」以「任务在重试」的形式出现，skip 占比告警的分母随之失真。

#### 3.6.1 人设与表达风格为什么不是同一个字段

`introduce` 是**投递判定**用的素材：技能、年限、项目背景，天然是简历腔、书面语、名词堆叠。拿它当「人设」直接喂进回复 prompt，产出必然是「本人具备 X 年 Y 经验，擅长 Z」这种一眼假的句子 —— 这正是要避免的形态。

所以职责必须拆开：

| 字段 | 职责 | 谁写 |
|---|---|---|
| `ai_config.introduce` | **说什么** —— 事实素材（年限、技能、项目） | 现状已有 |
| `ai_config.ai_reply_style` | **怎么说** —— 语气、句长、禁用表达 | 本 spec 新增 |

`ai_reply_style` 默认值不为空，走代码内常量 `AiReplier.DEFAULT_STYLE`（DB 里存空串时用它兜底）。这样用户在控制台清空输入框，也不会退化成无约束状态：

```
像真人聊天一样回话：一两句话，口语化，短句为主。
不复述对方的话，不总结、不分点、不用小标题。
不用书面语和套话（如"本人具备""贵公司""期待与您共事"）。
不堆感叹号，不加表情，不主动提薪资、联系方式、面试时间。
整条不超过 60 字。
```

**风格是软约束，不可信。** 模型可以不遵守，所以风格只负责「让它大概率像人」，最终判定交给 3.7 的代码检查。这两层的关系要写在注释里，避免后来者以为改了 prompt 就够了。

#### 3.6.2 允许不回，但必须说清为什么

`skip` 的原因限定在以下枚举内（落 `reply_log.reason_code`，便于 `GROUP BY` 统计）。`reason` 字段只作为补充说明（≤200 字，禁止复述对方原话）：

| `skipReason` | 含义 | 典型场景 |
|---|---|---|
| `NO_SUBSTANCE` | 对方只是表情/无指向内容，接一句反而奇怪 | 单发一个表情、一条没有文字的卡片 |
| `SENSITIVE_TOPIC` | 涉薪资、面试安排、个人信息交换 | 「期望薪资多少」「什么时候能到岗」 |
| `ALREADY_HANDLED` | 上下文中已经答过同类内容 | 对方重复问同一个问题 |
| `LOW_CONFIDENCE` | 拿不准对方意图，宁可不回 | 信息不全、语义含糊 |
| `HUMAN_REQUIRED` | 需要本人决策，AI 不该代替 | 约具体面试时间、要作品集 |

技术性失败（超时、非 200、JSON 非法、`reply` 为空）**不算 skip**，走 `AI_FAILED`，两者在统计上要分开 —— 否则「模型在躲」和「接口在坏」会混成一个数字。

**枚举之外的 `skipReason` 一律按 `LOW_CONFIDENCE` 处理**，并打一次 warn：模型爱自由发挥，不能让它的即兴措辞污染统计口径。

### 3.7 出站四重校验

| 检查 | 规则 | 命中处理 |
|---|---|---|
| 长度 | > 60 字 | 截到最后一个句号/问号/感叹号；仍超则整条不发，记 `BLOCKED_TOO_LONG` |
| 联系方式 | 手机号 `1[3-9]\d{9}`、`微信/weixin/vx/qq/邮箱`、连续 6 位以上数字 | 整条不发，记 `BLOCKED_CONTACT` |
| 薪资与承诺 | 敏感词表 + `薪资\|工资\|月薪\|年薪\|k/月\|到岗\|保证\|一定` | 整条不发，记 `BLOCKED_SENSITIVE` |
| **AI 味** | 见下方词表与结构规则 | 整条不发，记 `BLOCKED_TONE`；命中规则时降级用模板 |

校验在 `sendText` 之前，是最后一道闸门。**校验不通过时绝不允许"发个短的凑数"**。

**「AI 味」分两层：词表可配，结构规则固定。**

| 层 | 可配 | 落点 |
|---|---|---|
| 词表（整串包含） | ✅ 可增删 | `ai_config.ai_reply_tone_terms`，换行分隔；空串 = 用 `ReplyGuard.DEFAULT_TONE_TERMS` |
| 结构规则（编号列表、小标题、markdown） | ❌ 固定 | 代码内，不可配。理由：这些不是「词」，配成文本极易配错，且它们的误杀风险本身很低 |
| 总开关 | ✅ | `ai_config.ai_reply_tone_check_enabled`，默认 1 |

**结构规则**（固定）：

| 判据 | 正则 |
|---|---|
| 编号列表 | 行首 `^\s*\d+[.、)]` |
| 圈号列表 | 含 `①`–`⑨` |
| markdown 列表/加粗 | 行首 `^\s*[-*+]\s`、`**` 成对出现 |
| 小标题 | 出现 `：\n` 或 `##` |
| 复述开场 | 以对方最后一句的前 8 个字开头（模板腔的典型特征） |

**`DEFAULT_TONE_TERMS`（默认 31 条）**，全部是整串匹配、口语里几乎不会出现的书面套话。下面按分组用 `/` 隔开只为便于阅读，**实际存储与控制台展示都是一行一条**：

```
本人具备 / 本人拥有 / 本人从事
贵公司 / 贵司 / 期待与您共事 / 期待能有机会
很高兴为您服务 / 很高兴为您解答
感谢您的耐心 / 感谢您的关注
如有需要请随时 / 如有任何问题
希望对您有帮助 / 希望对你有所帮助 / 希望以上回答 / 请问还有什么 / 以上是我的回答
综上所述 / 总而言之 / 值得一提的是 / 需要注意的是
以下是 / 我将
作为AI / 作为一个AI / 我是一个AI / 我是AI / 语言模型 / 人工智能助手
我理解您的需求
```

几条默认值的取舍要说明，免得以后被当成脏数据删掉：

- **`贵司` / `贵公司` 都留**：二者互不包含（「贵公司」不含连续子串「贵司」），不能只留一个。
- **`希望对您有帮助` 只留短的**：整串匹配下它能命中「希望对您有所帮助」，长的写了也是冗余。
- **`我将` 是唯一有误杀风险的一条**（「我将简历发您」勉强能说）。留着的理由是它在聊天里极少见、在 AI 输出里极常见。被误杀时按 3.8.2 的告警就能看出来，删掉即可。
- **`感谢` / `您好` / `首先` 一律不进词表**：真人天天在用，单词级匹配会把正常回复全杀掉。

词表可配带来一个新的责任：**加词比删词危险**。文档和控制台提示都写「加词请先确认它在正常聊天里不会出现；每次加词必须补一条对应的反例单测」（4.3）。

命中即整条不发而不是"提醒一下照发" —— 理由是**发出去一条明显的机器话术，比这条不回更伤**，这与 5.3 的「宁可漏回」口径一致。

### 3.8 配置与控制台

#### 3.8.1 配置项（AI 配置页）

| 项 | 字段 | 类型 | 默认 | 约束 |
|---|---|---|---|---|
| 值守 AI 回复 | `ai_reply_enabled` | 开关 | 关 | 关闭时全链路 no-op |
| 回复风格约束 | `ai_reply_style` | 多行文本 | 空 → `DEFAULT_STYLE` | ≤500 字，超出截断保存 |
| AI 味检查 | `ai_reply_tone_check_enabled` | 开关 | 开 | 关掉即不做第四重校验 |
| AI 味词表 | `ai_reply_tone_terms` | 多行文本（一行一条） | 空 → `DEFAULT_TONE_TERMS` | ≤200 条、单条 ≤20 字；去空行、去重、去首尾空白后保存 |
| skip 占比告警阈值 | `ai_reply_skip_alert_pct` | 数字 % | 60 | 0 = 关闭告警；上限 100 |
| 告警最小样本数 | `ai_reply_skip_alert_min_sample` | 数字 | 10 | 低于此数不判定，显示「样本不足」 |
| AI 超时秒数 | `ai_reply_timeout_seconds` | 数字 | 8 | 上限 120（原定 15，见 P3 实施记录第 6 条）；实际生效再与提供商超时取较小值 |
| 单轮 AI 回复上限 | `ai_reply_max_per_sweep` | 数字 | 5 | 上限 10 |

**两个多行输入框的交互约定**（风格、词表），这是最容易做错的地方：

- 空着**不等于关闭**，等于「用默认值」。placeholder 必须显示出默认内容，下方一行小字「留空则使用默认（N 条）」。
- 各配一个「恢复默认」按钮，点了把默认内容填进输入框并置为脏状态，由用户自己保存 —— 不直接落库。
- 词表另显示「当前生效 N 条」，N 在留空时是默认条数。**不能只显示输入框为空就完事**，否则用户会以为检查没在工作。
- 词表加词的即时校验：命中下方「高频口语词」黑名单（`感谢`/`您好`/`好的`/`方便`/`可以` 等）时给出黄字提示但不阻止保存 —— 拦下来会让人困惑，提示到位即可。

#### 3.8.2 skip 占比告警

目的是回答一个具体问题：**「开关开了，但它是不是一直在躲？」** 没有这个指标，`AI_SKIPPED` 只会在日志里安静堆积。

**口径**（写死在代码里，不配窗口）：

| 量 | 定义 |
|---|---|
| 窗口 | 近 7 天（与任务面板一致） |
| 分母 | `reply_log` 中 `reply_source = 'AI'` 的记录数（= AI 参与判定的总次数） |
| 分子 | 其中 `action = 'AI_SKIPPED'` 的记录数 |
| 占比 | 分子 / 分母 |
| 判定 | 分母 ≥ `min_sample` **且** 占比 > 阈值 → 告警 |

三个刻意的排除：

1. **`AI_FAILED` 不进分子**。接口坏了不是「在躲」，混进来会让两个问题互相掩盖。
2. **`BLOCKED_*` 不进分子**。被风控拦下是校验层在工作，不是模型在躲；这几类单独在面板上列计数。
3. **样本不足时不显示「正常」**。分母 < `min_sample` 时状态是灰色的「样本不足（3/10），未判定」。这一条是硬约束：把「没法判断」渲染成「一切正常」，正是这次要根治的病（见 1.2）。

**展示**：任务面板顶部一条提示条，三档状态：

| 状态 | 文案 |
|---|---|
| 阈值 = 0 | 灰底：「告警已关闭（阈值 0）。近 7 天 AI 参与 42 次，主动不回 29 次」—— **不能什么都不显示**，否则等于把「我关了它」伪装成「一切正常」（见 R5） |
| 样本不足 | 灰底：「近 7 天 AI 参与 3 次，样本不足（需 10 次），未判定」 |
| 未超阈值 | 绿底：「近 7 天 AI 参与 42 次，主动不回 18 次（43%），阈值 60%」 |
| 超阈值 | 黄底：「近 7 天 AI 参与 42 次，主动不回 29 次（69%），超过阈值 60%。建议查看原因分布」+ 原因分布链接 |

告警**只提示不改行为**：不自动关开关、不调阈值、不发消息通知。理由是这类指标早期噪声大，让它自动动开关比不动更危险。

#### 3.8.3 未命中列表与任务面板

**配置 → 值守规则** 的未命中列表：AI 关闭时显示 `UNMATCHED`；开启后显示 `AI_SKIPPED`（带原因枚举）/ `AI_FAILED`（带失败类型）/ `BLOCKED_*`，每条保留「转为规则」入口。

**概览或系统页的「未读任务」小面板**：

- 默认视图只有三个计数：待处理 / 重试中 / 已放弃（近 7 天），**不出现任何按钮**。
- 「再试一次」只在用户点开某条 `GAVE_UP` 记录的详情后才出现，一次一条，不做批量。
- 点击后二次确认：「将重新给这个会话 3 次机会，可能向对方发出消息」。确认后 `status → PENDING`、`attempt_count → 0`、`next_retry_at → now`，并记 `last_action = MANUAL_RETRY`。
- 同一会话同时只允许一条 open 任务（`uk_unread_task_open` 保证），重复点击返回 409 而不是堆任务。

默认折叠的用意：`GAVE_UP` 多数是真的不该回（同名混淆、列表已变、对方是骚扰），把按钮摆在明面上会诱导人反复点，反而制造错发风险。

### 3.9 数据

```sql
-- V5__unread_task.sql
ALTER TABLE ai_config ADD COLUMN ai_reply_enabled INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_config ADD COLUMN ai_reply_style TEXT NOT NULL DEFAULT '';            -- 空串 = 用 DEFAULT_STYLE
ALTER TABLE ai_config ADD COLUMN ai_reply_tone_check_enabled INTEGER NOT NULL DEFAULT 1;
ALTER TABLE ai_config ADD COLUMN ai_reply_tone_terms TEXT NOT NULL DEFAULT '';        -- 换行分隔，空串 = 用 DEFAULT_TONE_TERMS
ALTER TABLE ai_config ADD COLUMN ai_reply_skip_alert_pct INTEGER NOT NULL DEFAULT 60; -- 0 = 关闭告警
ALTER TABLE ai_config ADD COLUMN ai_reply_skip_alert_min_sample INTEGER NOT NULL DEFAULT 10;
ALTER TABLE ai_config ADD COLUMN ai_reply_timeout_seconds INTEGER NOT NULL DEFAULT 8;
ALTER TABLE ai_config ADD COLUMN ai_reply_max_per_sweep INTEGER NOT NULL DEFAULT 5;

-- 让「AI 不回」这件事可统计：code 走枚举便于 GROUP BY，note 只放补充说明
ALTER TABLE reply_log ADD COLUMN reason_code TEXT;   -- NO_SUBSTANCE|SENSITIVE_TOPIC|...
ALTER TABLE reply_log ADD COLUMN reason_note TEXT;   -- ≤200 字，禁止复述对方原话
ALTER TABLE reply_log ADD COLUMN reply_source TEXT;  -- TEMPLATE|AI；历史行为 NULL，读时按 TEMPLATE 处理
CREATE INDEX IF NOT EXISTS idx_reply_log_reason ON reply_log(reason_code, created_at);
CREATE INDEX IF NOT EXISTS idx_reply_log_source ON reply_log(reply_source, created_at);

CREATE TABLE IF NOT EXISTS unread_task (
    id                       INTEGER PRIMARY KEY AUTOINCREMENT,
    platform                 TEXT    NOT NULL,
    platform_conversation_id TEXT    NOT NULL,   -- 当前等于 HR 标题，见 5.3
    conversation_id          INTEGER REFERENCES conversation(id) ON DELETE CASCADE,
    unread_count             INTEGER NOT NULL DEFAULT 0,
    last_message_digest      TEXT,               -- 最后一条文本的 SHA-256 前 16 位，不落原文
    platform_message_id      TEXT,               -- 点开后回填的 data-mid
    status                   TEXT    NOT NULL,   -- PENDING|PROCESSING|DONE|FAILED|GAVE_UP
    attempt_count            INTEGER NOT NULL DEFAULT 0,
    max_attempts             INTEGER NOT NULL DEFAULT 3,
    last_action              TEXT,               -- REPLIED|AI_SKIPPED|AI_FAILED|BLOCKED_*|MANUAL_RETRY
    last_reason_code         TEXT,               -- 与 reply_log.reason_code 同枚举，便于面板直接展示
    last_error               TEXT,
    next_retry_at            TEXT,
    created_at               TEXT    NOT NULL,
    updated_at               TEXT    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_unread_task_open
    ON unread_task(platform, platform_conversation_id)
    WHERE status IN ('PENDING', 'PROCESSING', 'FAILED');
CREATE INDEX IF NOT EXISTS idx_unread_task_retry
    ON unread_task(status, next_retry_at);
```

`last_message_digest` 存摘要不存原文：既够做「列表是否变化」的校验，又不把 HR 原话明文落库（`reply_log.incoming_text` 已经是明文，不再扩大这个面）。

`ai_reply_style` / `ai_reply_tone_terms` 用 `NOT NULL DEFAULT ''` 而不是可空：空串在代码里是「用默认值」的明确信号，NULL 是会往上层冒的类型噪声。

`reply_source` 是 3.8.2 告警口径的前提，不能省：现有 `reply_log` 无法区分「模板发的」和「AI 发的」，分母一旦算宽，skip 占比会被模板回复稀释成一个永远正常的数字。历史数据为 NULL，统计时按 `TEMPLATE` 处理（改造前确实全是模板）。

**告警统计 SQL**（`WatcherController` 内实现，不走全表扫描）：

```sql
SELECT COUNT(*) AS total,
       SUM(CASE WHEN action = 'AI_SKIPPED' THEN 1 ELSE 0 END) AS skipped
FROM reply_log
WHERE reply_source = 'AI'
  AND created_at >= datetime('now', '-7 days');
```

### 3.10 接口

- `GET/PUT /api/config/ai` 增加 8 个字段：`aiReplyEnabled`、`aiReplyStyle`、`aiReplyToneCheckEnabled`、`aiReplyToneTerms`、`aiReplySkipAlertPct`、`aiReplySkipAlertMinSample`、`aiReplyTimeoutSeconds`、`aiReplyMaxPerSweep`。
- `GET` 额外返回三个**只读默认值**：`aiReplyStyleDefault`、`aiReplyToneTermsDefault`（31 条）、`aiReplyToneTermsEffectiveCount`（当前生效条数）。前端用它填 placeholder、算「留空则使用默认（N 条）」，无需在前端硬编码一份默认词表 —— 两份默认值迟早会分叉。
- `GET /api/watcher/stats?days=7` 返回告警判定结果：`{total, skipped, pct, thresholdPct, minSample, state}`，`state ∈ DISABLED | INSUFFICIENT | OK | ALERT`。**判定在服务端做**，前端只负责按 `state` 上色 —— 否则「样本不足」和「未超阈值」在不同页面上可能被实现成同一个绿色。
- `GET /api/watcher/unread-tasks?status=&days=` 供控制台小面板使用，只返回计数与 `lastAction` / `lastReasonCode` / `lastError`，**不返回消息原文**。
- `POST /api/watcher/unread-tasks/{id}/retry` 手动重试：仅接受 `status = GAVE_UP`，执行 3.8.3 描述的重置；非 `GAVE_UP` 返回 409，任务不存在返回 404。
- 新增 `web/WatcherController.java` 承载后三个接口 —— 现在没有 watcher 相关的控制器，不要塞进 `ConfigController`（职责不同：一个是配置读写，一个是任务运维与统计）。

## 4. 落地实施

### 4.1 文件清单

新增：

- `server/src/main/java/com/chatjob/watcher/AiReplier.java`
- `server/src/main/java/com/chatjob/watcher/ReplyGuard.java`（出站四检，纯函数，便于单测）
- `server/src/main/java/com/chatjob/watcher/UnreadTaskStore.java`
- `server/src/main/java/com/chatjob/web/WatcherController.java`（任务查询 + 手动重试）
- `server/src/main/java/com/chatjob/store/entity/UnreadTaskEntity.java`
- `server/src/main/java/com/chatjob/store/mapper/UnreadTaskMapper.java`
- `server/src/main/resources/db/migration/V5__unread_task.sql`
- `console/components/UnreadTasks.vue`（任务面板，含折叠的「再试一次」）
- 单测：`ReplyGuardTest`、`AiReplierTest`、`UnreadTaskStoreTest`、`WatcherStatsTest`

修改：

- `platform/boss/BossSelectors.java`（3 个常量）
- `platform/boss/BossAdapter.java`（消息 id、会话精确匹配、`parseInt` 失败语义）
- `watcher/UnreadWatcher.java`（落任务 → 读消息 → AI 分支 → 收尾）
- `watcher/ReplyRuleEngine.java`（只加「AI 分支的输入契约」，判定顺序不动）
- `store/entity/AiConfigEntity.java` + `web/ConfigController.java`（8 个字段 + 3 个只读默认值）
- `store/entity/ReplyLogEntity.java`（`reason_code` / `reason_note` / `reply_source`）
- `console/components/ReplyRules.vue`、`console/pages/config.vue`（开关 + 风格 + 词表 + 告警两项 + 未命中列表口径）
- `console/pages/system.vue`（挂 `UnreadTasks.vue`）

### 4.2 分期（每步独立可验证）

| 阶段 | 内容 | 验证方式 |
|---|---|---|
| P0 | 选择器 + 消息 id + `parseInt` 语义 | 跑一轮 `sweep`，日志里 `threads / replied / unmatched` 不再是 0；`reply_log` 有真实 `platform_message_id` |
| P1 | `unread_task` 表 + 落任务 + 重试 | 人工制造一次 AI 失败（填错的 baseUrl），确认任务进 FAILED 并在 1 分钟后重试、3 次后 GAVE_UP |
| P2 | 出站四检（词表可配） | 单测覆盖：超长、手机号、微信号、薪资词、空串；AI 味分三组 —— 默认 31 条逐条命中、自定义词表整体替换默认、`tone_check_enabled=0` 时直接放行；再加 3 条正常口语反例必须通过 |
| P3 | AI 回复（开关默认关）+ 风格注入 + `skipReason` 落库 + `reply_source` 落库 | 开关关：现有行为逐行不变；开关开：未命中会话产出回复，日志能看到 prompt 摘要、风格是否生效、生成长度；构造 `skip` 场景确认 `reason_code` 落库、`reply_source='AI'` |
| P4 | 控制台 | 8 个配置项可保存并生效；清空风格/词表后仍受默认值约束且显示生效条数；告警提示条四档状态各自渲染正确（含「样本不足」不能被渲染成绿色）；未命中列表显示新口径；任务面板计数正确；`GAVE_UP` 详情里能手动重试 |
| P5 | **真发送冒烟** | 用一个可控会话发一条，确认 `sendText` 真的成功（这一项前置条件未验证，见 5.3） |

P5 单独列出来的原因：前置条件自检时「真发送」没有验证（按钮的禁用是 CSS 类而非原生属性，`isDisabled()` 判据无效）。这条链路必须早暴露，但它有真实副作用，所以放在最后一步单独执行。

**P1 实施记录（2026-10-29，已完成）**

与本文原方案有 5 处偏差，都是实施时才暴露的：

1. **迁移拆分**：`V5__unread_task.sql` 只含 P1 需要的结构（`ai_config.ai_reply_enabled` + `unread_task` 表 + 两个索引）。§3.9 里 `reply_log` 的三列与 `ai_config` 其余 7 个字段随 P3 / P4 各自迁移 —— 一个阶段一次加列，避免出现「列在库里、代码里没人用」这种没人敢确认的中间态。
2. **会话精确定位（§3.4）提前到 P1 落地**：原方案把它算进 P0 的 `BossAdapter` 改动，但 P0 实际只做了选择器与消息 id。重试路径上「包含匹配」意味着可能把回复发给「张三丰」，所以它必须先于重试上线，不能等。
3. **`UnreadTaskMapper` 不继承 `BaseMapper`**：需要的都是带业务语义的查询（在途任务、到期待重试），注解 SQL 比 wrapper 拼条件清楚，也让单测能用 hand-written fake 直接实现 5 个方法。副作用是 fake 复刻了 SQL 的过滤条件 —— 所以另用一次性 `@SpringBootTest` 在临时库上真跑过一遍 MyBatis（Flyway V1→V5 → 落任务 → 失败 → 到期查询 → 人工重试），跑完即删，不留测试基建。
4. **AI 分支在 P1 占位为 `AI_FAILED`**：P1 时 `AiReplier` 还不存在，未命中会话没有可走的分支。若只记一条 `UNMATCHED` 就算完，任务永远到不了 FAILED，「失败 → 1 分钟重试 → 3 次后 GAVE_UP」这条链在 P3 之前无法验证。占位实现让本轮验证可以用真实链路跑通，P3 直接替换该分支。
5. **关闭态是独立代码路径而不是循环里的 `if`**：`sweepLegacy()` 与 `sweepWithTasks()` 分开，让「关闭即 no-op」在结构上成立。代价是两段相似代码长期共存 —— 这是有意的：只读一次 PR 就能确认关闭态没被动过，比每次改动都小心翼翼去核对更可靠。

新增只读工具 `tools/inspect-unread-tasks.mjs`（P4 面板之前的唯一观测手段）：打印开关状态、状态分布、到期可重试数、最近任务明细。不写库。

**运行时验证（2026-10-29，真实环境）**

单测覆盖不到 Playwright 的 Locator 行为，所以另做了一轮只读的实机核对。新增 `tools/verify-p1.mjs`：从 `BossSelectors.java` 解析常量后在真实页面跑（与 P0 的 `verify-p0.mjs` 同款做法，避免 JS/Java 两份选择器漂移），**不点开任何会话、不发消息、不清红点**。

| 项 | 实测 | 结论 |
|---|---|---|
| 会话条目 | 35 条，结构对不上 0 | 选择器作用域仍有效 |
| 带未读徽标 | 7 条 | 未读三分语义有真实样本 |
| 标题含 `\u00A0` | **0 条** | `nameOf` 的替换当前是空转，保留作防御 |
| 同名会话 | **0 组** | 精确匹配可唯一命中，不会误触 `AMBIGUOUS_TITLE` |
| 写入值 ≠ 比较值 | **0 条** | 见下「已修缺陷」 |
| `Digest` 为空 | 0 条 | 列表 last-msg 都读得到，二次校验有输入 |

**已修缺陷（本轮验证发现）**：`listUnreadThreads` 取标题用 `firstText`（只 `trim`），而 `openThread` 比较用 `nameOf`（`replace('\u00A0',' ')` 再 `trim`）。两边口径不同，一旦标题里出现不可见空格，写进去的标题就永远匹配不上，表现为**值守整体静默空转**（只打 debug 日志）。当前页面没有 `\u00A0` 所以未暴露，但这是「换一个 HR 名字就炸」的定时炸弹。已统一为 `nameOf`。

**两个遗留问题（不在 P1 范围内，需单独裁决）**：

1. ~~**只扫前 30 条**~~ **已修（2026-10-29）**：`listUnreadThreads` 里 `Math.min(items.count(), 30)`，实测列表有 35 条 —— 排在 30 名之后的会话永远不会被发现，且没有任何日志。任务表解决的是「发现了但处理失败」，解决不了「压根没发现」。

   修法不是把 30 改大，而是让它成为配置项：`platform_config.unread_scan_limit`（V6，默认 100、上限 500），并把「本轮读多少条」从 Playwright 调用中间抽成纯函数 `UnreadScanLimit.plan(total, limit)`（有单测覆盖 35 条 / 30 条漏 5 条这一现场）。上限本身保留 —— 列表异常膨胀时一轮值守不能吃掉整个扫描节奏 —— 但**触发时必须留证据**：`plan.truncated()` 是唯一信号，`BossAdapter` 在截断时打 warn 并报出「总条数 / 上限 / 被挡在门外的条数」。即把「静默漏人」换成「可见的漏人」。控制台入口在「平台配置 → 配额与节奏 → 未读列表扫描上限」。
2. ~~**`DRY_RUN` 不防护值守发送**~~ **已修（2026-10-29）**：`RunService` 里 `deliver` 有 `DRY_RUN` 分支（不实际投递），但 `unreadWatcher.sweep(...)` 在同一个循环里无条件调用，命中规则时照样 `sendText` —— 用演练模式验证重试链，会给真实 HR 发消息。P3 接 AI 之后更危险。

   修法不是补 `if`，而是给 `UnreadWatcher` 加一条**独立的演练支路** `sweepDryRun`：方法体里没有 `sendText`、没有 `replyStore.log`、没有 `markOwnReply`、没有任何任务表写操作，所以「演练不发消息」在结构上成立，不依赖后续维护者的记性。`sweep(...)` 多一个 `boolean dryRun` 参数，且**排在 `ai_reply_enabled` 判断之前**（演练与开关无关）。

   同时堵住三处比「真的发出去」更隐蔽的污染 —— 它们会让演练把「发错消息」变成「静默丢消息」：

   | 副作用 | 原位置 | 后果 |
   |---|---|---|
   | 写 `reply_log` | `logAction` / `ReplyStore.log` | `wasProcessed` 查的就是这张表 → 真实运行时这条消息判 `SKIP_DUPLICATE`，**永久不回** |
   | 写 `last_own_reply_at` | `ReplyStore.markOwnReply` | 真实运行前 3 分钟被自己演练留下的冷却挡住 |
   | 推进 `unread_task` | `UnreadTaskStore.markDone(REPLIED)` | 任务 DONE、重试链断，且 `PENDING` 任务不会被 `dueForRetry` 捡回（只查 `FAILED`） |

   演练态另有两处刻意「不查真实状态」：不查 `wasProcessed`、不查冷却（两者读的都是真实回复留下的痕迹）；也不落 `unread_task`（演练建了任务却永不收尾，只会毒化真实运行）。

   `SweepResult` 新增 `wouldReply` 字段，`replied` 在演练下恒为 0，控制台显示「值守（演练）：N 个未读，本会回复 M」而不是「回复 M」—— 否则等于给操作者一个假的成功信号。

   **已知代价**：`readMessages` 必须点开会话，红点因此消失，演练过后真实运行时这条未读不会再出现在列表里。接受它，是因为不验证 `openThread` 与消息区选择器就上线，代价更大。打开态（`ai_reply_enabled=1`）也没有补偿 —— 补偿手段 `unread_task` 恰好是演练刻意不碰的那张表。

   测试守门：`UnreadWatcherTest` 新增 4 个用例，断言「一条没发 + `reply_log`/`unread_task`/`conversation` 三张表一行没写 + 冷却为空 + `replied=0`」，把上述结构钉住。


**P2 实施记录（2026-10-29，已完成）**

| 项 | 落地 |
|---|---|
| 校验器 | 新增 `watcher/ReplyGuard.java`，纯静态函数，词表与敏感词由 `GuardOptions` 传入，不读库、不碰浏览器 |
| 配置落点 | `V7__ai_reply_tone.sql` 只加 `ai_reply_tone_check_enabled` / `ai_reply_tone_terms` 两列；其余 6 个 `ai_config` 字段仍留给 P3 / P4 |
| 读取 | `ConfigStore.toneCheckEnabled()` / `toneTerms()` / `parseToneTerms()`（去空行、去空白、去重、保序） |
| 控制台 | P2 不动。8 个配置项的界面与 `GET/PUT /api/config/ai` 扩展一起在 P4 做，避免接口先出现半成品字段 |

三处实施结论：

1. **结构规则必须拿原文判，不能拿归一化后的文本判。** `TextNormalizer` 会把换行折叠成空格，`^` 行首与 `：\n` 这两条判据在归一化之后全部失效 —— 小标题和列表一条都拦不住，而日志上看起来是「检查通过」。词表匹配仍走归一化（大小写、全角），结构判定走原文，分界写在注释里。
2. **长度超限暂不映射为 `BLOCKED_*` action。** §3.7 只给联系方式 / 薪资 / AI 味定了三个 `BLOCKED_*`，长度没有对应值，但「超长且截不动」确实要整条不发。P2 只让 `Verdict.rejection()` 报 `TOO_LONG`，action 映射留给 P3 —— 那时才有 `reply_log` 的写入点，现在不发明 spec 外的枚举值。
3. **空词表 = 用默认，不是关闭。** `tone_check_enabled` 管开关、`tone_terms` 管内容，所以「清空词表」只会回落到 31 条默认值，不会把检查关掉（有单测钉住）。

单测 `ReplyGuardTest` 共 53 个用例：常规 6 类 + AI 味三组（31 条默认词参数化逐条命中、自定义词表整体替换、`tone_check_enabled=0` 放行且不影响前三检）+ 结构规则 5 条 + 3 条口语反例 + 词表解析。

**P3 实施记录（2026-10-29，已完成）**

| 项 | 落地 |
|---|---|
| 迁移 | `V8__ai_reply_p3.sql`：`ai_config` 加 `ai_reply_style` / `ai_reply_timeout_seconds` / `ai_reply_max_per_sweep`；`reply_log` 加 `reason_code` / `reason_note` / `reply_source` + 2 索引 |
| 生成器 | 新增 `watcher/AiReplier.java`：`DEFAULT_STYLE`、`SKIP_REASONS` 五枚举、`render`/`parse`/`normalizeSkipReason`/`tail` |
| 共用 HTTP | 新增 `evaluate/ChatClient.java`，把端点拼接、双超时、strict JSON、`extractJson` 从 `AiJudge` 抽出来只留一份；`AiJudge` 改为委托，`chatEndpoint` 保留同名入口以免端点拼接失去可测性 |
| 编排 | `UnreadWatcher.process`：命中与未命中**都**交 AI（模板只做参考素材），AI 技术失败降级发模板，四检拦截记 `BLOCKED_*`，单轮 AI 调用预算 |
| 预算 | `UnreadTaskStore.release()`：超预算退回 `PENDING`，不消耗重试预算 |
| 接口 | `GET/PUT /api/config/ai` 增加上述 3 个字段 + 只读 `aiReplyStyleDefault`（前端填 placeholder 用，不硬编码第二份默认值） |
| 控制台 | AI 配置页新增「值守 AI 回复」卡片：开关 + 风格 + 超时 + 单轮上限（见下方第 5 条）。AI 味两项与告警仍留 P4 |

四处实施结论：

1. **打开态「命中规则」不再直接发模板。** P1 的实现是命中即发模板；照旧的话，40 个会话里命中「简历」「薪资」的回的全是同一句 —— 这正是本 spec 要解决的问题。现在模板降级为**参考素材**交给 AI，只在 AI 技术性失败时兜底发送（它是人工审核过的，比「不回」安全）。
2. **`BLOCKED_TOO_LONG` 补进 §3.7。** 这是 P2 遗留的待裁决项（当时没有写入点）。现在有了：超长确实要整条不发，而塞进 `BLOCKED_TONE` 会让「AI 味」这个指标被「话太多」污染 —— 一眼看不出到底是套话还是超长。
3. **四检拦截不重试，记 `DONE` 且 `attempt_count` 保持 0。** 同一 prompt 再问一次大概率给同样输出，四检又是确定性规则，重试只会多花一次调用再把同样的东西拦一次。用重试预算去表示「确定性的拒绝」，会让真正需要重试的任务更早 `GAVE_UP`。
4. **单轮 AI 预算用 `release` 而不是 `markFailed`。** 默认 5 次（`ai_reply_max_per_sweep`）。列表有 40 个会话时未命中可能连着来几条，全调一遍会把扫描主链饿死；而「这轮没轮到你」不是失败 —— 记 `FAILED` 会让一条一次错都没犯的消息在 3 轮后被 `GAVE_UP`。
5. **控制台提前做了 4 项（P3 收尾时补，不等 P4）。** 原计划控制台整体留给 P4，但那意味着风格/超时/预算三个字段只能靠 `PUT /api/config/ai` 手改 —— 这三项恰恰是上线后最需要现场调的。于是先补「开关 + 风格 + 超时 + 单轮上限」的最小面：风格框用 placeholder 显示 `DEFAULT_STYLE`（后端经 `aiReplyStyleDefault` 提供唯一一份，前端不复制文案）、「填入默认」只填框不落库、数字框清空时拦下并提示（不去猜默认值填回去）、保存后回读一次让后端的 clamp 结果回显。AI 味两项、告警提示条、任务面板仍在 P4。
6. **超时上限做成 120 秒，不是原定的 15 秒。** 15 秒对慢模型太紧，一次正常的长回复会被当成技术失败进重试链，而重试几乎必然同样超时 —— 3 次后 `GAVE_UP`，表现为「AI 莫名其妙不回这个人」。改成 120 秒并与提供商超时取较小值（`min(configured, providerTimeout)`）后，真正兜住等待时间的是提供商那一侧，语义更准确。控制台把压缩后的实际值显示出来（「实际生效 N 秒 —— 当前提供商的超时更短」），不让用户以为设 120 就是 120。

单测 **159 个**（P2 时 142，+17）：新增 `AiReplierTest` 11 个（解析三类结果、枚举归一、上下文裁剪、prompt 里必须带风格与参考素材）；`UnreadWatcherTest` 16 → 22（AI 生成发送、命中也走 AI、AI 主动 skip、技术失败进重试、命中时降级模板、四检拦截、超预算退回待办、演练模式未命中不落任务）。

控制台这 4 项的运行时验证（2026-10-29）：改值保存 → 接口回读 → 数据库 → 刷新回显四处一致；风格清空保存后回读为空串（`aiReplyStyle()` 返回空 → `AiReplier` 用 `DEFAULT_STYLE`）；「填入默认」点了只改输入框、接口值不变；数字框清空后点保存被拦下且接口值不变；开关开→`true` / 关→`false` 往返一致。配置读取每次查库（`ConfigStore.aiConfig()` 无缓存），改完即生效，不用重启。

**P4 实施记录（2026-10-29，已完成）**

| 项 | 落地 |
|---|---|
| 迁移 | `V9__ai_reply_alert.sql`：`ai_config` 加 `ai_reply_skip_alert_pct`(60) / `ai_reply_skip_alert_min_sample`(10)。真实库已由 Flyway 应用到 v9 |
| 判定 | 新增 `watcher/WatcherStats.java`：纯函数 `judge(total, skipped, thresholdPct, minSample)` → 四档 + `pct`，判定只在服务端做 |
| 查询 | 新增 `store/mapper/WatcherStatsMapper.java`：两个 count（分母/分子）+ 按状态分组计数 + 任务列表（不返回摘要）。与 `UnreadTaskMapper` 分开：一个服务状态机，一个服务人看的聚合视图 |
| 接口 | 新增 `web/WatcherController.java`：`GET /api/watcher/stats`、`GET /api/watcher/unread-tasks`、`POST /api/watcher/unread-tasks/{id}/retry`（404 / 409 / 200） |
| 配置 | `AiConfigBody` 补齐 4 字段；`GET` 增加 `aiReplyToneTermsDefault` / `aiReplyToneTermsEffectiveCount`；风格按 500 字截断；词表清洗后落库并回 `toneTermsDropped` |
| 面板 | 新增 `console/components/UnreadTasks.vue`，挂在 `system.vue` 最前面（在 driver 自检之前） |
| 未命中列表 | `ReplyStore.unmatched(days, limit, includeAiOutcomes)` 按开关切换口径；`ReplyRules.vue` 增加原因列 |

五处实施结论：

1. **手动重试从 P1 起就是死的，这次才暴露。** `manualRetry` 把任务重置为 `PENDING` 并把 `next_retry_at` 设成 `null`，而 `findDueForRetry` 只捞 `FAILED` —— 两个条件永远碰不上。更要命的是这条路径不会有任何报错：按钮点了、接口返回 200、任务确实变成了 PENDING，然后永远躺在那里。P1 交付时没有按钮，所以没人发现；P4 一做按钮就撞上。修法：`manualRetry` 写 `next_retry_at = now`，`findDueForRetry` 增加「`PENDING` 且已到期」分支（`PENDING` + `null` 仍然不捞，那是当轮还没开始处理的新任务，不能被重试通道抢走）。两条判据都补了单测，并用真实 SQL 在库上对齐验证。
2. **占比取整后判定，不用精确比值。** 精确比值会出现「面板显示 60%、判定 60.4% 超阈值」，让人指着屏幕问到底哪个是真的。取整后「显示什么就判什么」，代价是丢掉不到 0.5% 的精度 —— 换取面板自洽，值得。
3. **`BLOCKED_ACTIONS` 由枚举名派生。** 原本 `UnreadWatcher.blockedAction()` 里手写四个 `BLOCKED_*` 字符串，未命中列表还要再列一遍来筛选。两处各写一份的后果是「新增一种拦截，面板上永远看不到它」。改为从 `Rejection` 派生，`blockedAction` 用 `default -> "BLOCKED_" + name()`。
4. **超长词表行整行丢弃，不截断。** 一行 50 字基本是误粘的整段文本；截到 20 字会造出没人认识的碎片（既拦不住原意又可能误杀）。丢弃行数经 `toneTermsDropped` 回给前端，避免「粘了一大段却只看到已保存」。
5. **口语词提示用「整行等于」判定。** 第一版用「行里包含」，结果默认词表里的「如有需要请随时」命中了「随时」，用户点「填入默认」就看到一条假警告 —— 会让人怀疑默认词表有问题。改成整行相等后只在用户单独加「感谢」这类词时才提示。

运行时验证（2026-10-29，真实浏览器 + 真实库）：

- 四档状态逐个实测：阈值 0 → `DISABLED`；样本 20 > 10 → `INSUFFICIENT`；阈值 100 → `OK`；阈值 60 / 70% → `ALERT`。面板底色分别是灰 / 灰 / 绿 / 黄，「样本不足」是灰不是绿。
- 手动重试：404（不存在）/ 200（`GAVE_UP` → `PENDING`、`attempt_count=0`、`next_retry_at` 非空、`last_action=MANUAL_RETRY`）/ 409（重复点击）。confirm 文案「将重新给「X」3 次机会，可能向对方发出消息」。
- 「重试后能被捞出来」用真实 SQL 在库上验过，同时确认正常新建的 `PENDING + null` 不会被误捞。
- 词表：填入默认 → 31 条、`生效 31 条`；单独加「感谢」→ 只报「感谢」，不再牵连「随时」。
- 测试数据（10 行 `reply_log` + 3 条 `unread_task`，均带 `__P4_VERIFY` 标记）验证后已按 GLOB 精确删除，`ai_config` 保持全默认。

### 4.3 测试
- `ReplyRuleEngine` 现有单测必须全绿且**不改判定顺序**。
- `ReplyGuard`：表驱动覆盖常规 6 类（正常、超长、手机号、微信、薪资、空串）+ AI 味三组 —— ① `DEFAULT_TONE_TERMS` 每条至少一个命中用例（31 条都要有，参数化生成，防止加词不测）；② 自定义词表整体替换默认（旧默认词不再命中）；③ `tone_check_enabled=0` 时套话直接放行。另加 3 条正常口语反例（含 `感谢`、`您好`、`我把简历发您看看`）必须通过，否则说明词表过宽。

  > 「我将简历发您」原本也列在这三条反例里，但它与 §3.7 默认词表中的 `我将` 直接冲突 —— 两者不可能同时成立。P2 实施时的裁决：**保留词表、把这句话记为已知误杀**（§3.7 已说明 `我将` 是留着观察的边界项），并单独一条用例断言它会被拦，等真实的 `BLOCKED_TONE` 样本决定删不删词。删词是安全的单向操作。
- `AiReplier`：拆两半测 —— 纯逻辑（`parse` / `normalizeSkipReason` / `tail` / `render`）直接测；编排侧用 `UnreadWatcherTest` 里的 hand-written `FakeReplier` 覆盖「生成成功 / 主动 skip / 技术失败」三类结果。**没有**为 `HttpClient` 造 fake：网络层已收敛到 `ChatClient` 一处，再包一层 fake 只会测到 fake 自己。
- `UnreadTaskStore`：覆盖「同会话重复落任务不堆积」「第 4 次失败不再重试」「DONE 后可再建新任务」「手动重试把 `attempt_count` 归零并回到 PENDING」「非 `GAVE_UP` 的重试被拒绝」。
- `WatcherStatsTest`：四档状态的边界 —— 阈值 0 → `DISABLED`；总数 < min_sample → `INSUFFICIENT`（**不能是 `OK`**）；占比等于阈值 → `OK`（用 `>` 而非 `>=`）；占比超阈值 → `ALERT`。分母口径（只计 `reply_source='AI'`）在 SQL 里，纯函数测不到：改为运行时验证 —— 库里当时有 6 行历史 `NULL` 与 10 行 `AI`，接口返回 `total=10`，历史行确实没被计入。
- `ConfigController`：风格超 500 字截断保存而不报错；词表去掉空行/重复/首尾空白后入库，超 200 条截断；阈值 >100 存 100、<0 存 0（配置类接口不该因为一个越界数字把整个表单打回）。
- 端到端：`tools/precheck.mjs` 留作回归脚本，改选择器后必须重跑并保持 9 项通过。

## 5. 风险与验收

### 5.1 风险 Top 5

**R1 发给错误的人。** 会话无稳定 id，只能靠标题。缓解：精确匹配 + 摘要二次校验 + 多命中即放弃 + `max_attempts=3` 的上限。残留在 5.3。

**R2 点开即清未读，失败即丢消息。** 缓解：先落任务再点开；任务表承载重试；3 次后转 `GAVE_UP` 并在控制台可见。注意这条**不能完全消除**——如果进程在「落任务」和「点开」之间被杀，红点已清且任务已建，靠重试仍能捞回，这是可接受的。

**R3 AI 说出不该说的话。** 缓解：敏感词在 AI 之前先拦；出站四检在发送之前再拦；单条 ≤60 字；允许 AI 主动 skip。**AI 主动提出加微信、给手机号**是最高频的越界形态，正则必须覆盖 `vx` / `威信` / `v我` 这类变体。

**R4 AI 味检查两头都可能错，而且现在用户能自己把它调坏。** 词表窄了漏检（发出一条明显套话），宽了误杀（正常回复发不出去）。词表开放配置后多了一种坏法：一次粘进去一大段含口语词的文本，导致回复全线被拦。缓解：保存时清洗 + 高频口语词黄字提示 + 面板常驻 `BLOCKED_TONE` 计数 —— 一旦异常升高能立刻归因。**这条检查的作用是兜底，不是保证**：真正决定像不像人的是 `ai_reply_style` 与模型本身。

**R5 告警阈值配错等于没告警。** 阈值为 0 表示关闭，配成 100 等于永不触发；这两种设置都不会有报错，界面上只是安静地什么也不显示 —— 又是一次「看起来一切正常」。缓解：0 时提示条明确显示「告警已关闭」而不是不显示任何东西；100 时同样显示当前占比，让人看见自己把闸门拧死了。`min_sample` 配得过低（如 1）会产生噪声告警，同样在提示条里把样本数一起显示。

### 5.2 验收口径

- 开关关闭时，用同一份未读数据跑两轮，`reply_log` 的 action 分布与改造前一致。
- 开关打开时，对「对方主动打招呼」的会话，能产出并通过四检的回复，且 `unread_task` 从 PENDING 走到 DONE。
- 人为制造 AI 失败，任务 3 次后进入 `GAVE_UP`，控制台可见原因；在详情里点「再试一次」后回到 PENDING 并重新走完 3 次预算。
- 出站四检的常规 6 类单测全过；AI 味三组（默认 31 条逐条命中、自定义词表替换默认、开关关闭放行）全过，且 3 条正常口语反例不被拦。
- 把 `ai_reply_style` 清空后重跑，prompt 里出现 `DEFAULT_STYLE`，产出风格不退化。
- 把 `ai_reply_tone_terms` 清空后重跑，生效条数为 31，且套话仍被拦。
- 告警四档都验一遍：阈值 0 显示「告警已关闭」、样本不足显示「未判定」且**不是绿色**、正常区间、超阈值为黄色并给原因链接。
- AI 主动不回时，`reply_log.reason_code` 落在白名单内，`GROUP BY reason_code` 能出一张分布表；同批数据的 `reply_source` 全为 `AI`。
- `reply_log.platform_message_id` 全部是平台真实 `data-mid`（不再是 `序号:hash`）。

### 5.3 未闭环的事实

| 项 | 状态 | 影响 |
|---|---|---|
| 真发送是否成功 | **未验证** | 前置自检时按钮禁用是 CSS 类（`btn-send disabled`），`isDisabled()` 恒为 false，无法用状态判据。只能真发一条确认，已列为 P5 |
| 同名 HR 混淆 | **无法解决** | 会话无稳定 id。同名不同人会被合并成同一 `conversation`。缓解仅限「多命中即放弃」 |
| `item-system` 识别 | 无样本 | 4 个采样会话均为 0。实现不依赖它（只吃文本），但若某天出现系统消息且带文本，可能被误当 HR 消息 |
| 会话列表虚拟滚动 | 当前未触发 | 实测 35 条全部渲染（905px/905px）。会话数增长后需复核；若启用虚拟滚动，视口外会话既扫不到、重试也可能找不到 |
| `data-mid` 跨天稳定性 | 只验证了同会话 2.5 秒内一致 | 长期稳定性未验证。去重依赖它，若平台会复用 mid，需要退化为「mid + 文本摘要」双键 |

## 6. 最终审查

### 6.1 设计约束落点自检

| 约束 | 落在哪 |
|---|---|
| 关闭即 no-op | 3.6 的判定表，关闭列与现状逐行对齐；2.1 第 1 条 |
| 先落任务再点开 | 3.5 的时序图；`UnreadWatcher` 编排顺序 |
| 重试上限 3 次 | 3.5 退避策略；`unread_task.max_attempts` 默认 3 |
| 禁止包含匹配 | 3.4 两条规则（精确匹配 + 摘要校验） |
| ≤60 字 + 四重校验 | 3.7 表格；`ReplyGuard` 在 `sendText` 之前 |
| 风格由配置约束且不信任 prompt | 3.6.1 两字段职责表；`ai_reply_style` + `DEFAULT_STYLE` 兜底；3.7 的 AI 味检查 |
| AI 不回必须记枚举原因 | 3.6.2 白名单；`reply_log.reason_code`；枚举外归一化到 `LOW_CONFIDENCE` |
| 手动重试默认不展示 | 3.8.3 面板默认只有计数，按钮藏在 `GAVE_UP` 详情内；接口只接受 `GAVE_UP` |
| AI 味词表可配、默认 31 条 | 3.7 两层表（词表可配 / 结构规则固定）+ `DEFAULT_TONE_TERMS`；空串兜底；`reply_source` 保证告警分母可算 |
| skip 占比告警可配 | 3.8.2 口径表 + 字段 `ai_reply_skip_alert_pct` / `_min_sample`；`GET /api/watcher/stats` |
| 「无法判断」不得渲染成「正常」 | 3.8.2 四档状态；`state` 由服务端判定（前端不自行推算）；阈值 0 显示「已关闭」而非隐藏 |
| AI 失败不阻塞主链 | 沿用 `UnreadWatcher.java:88-90` 的 catch 口径 |
| AI 超时 ≤8s、单轮 ≤5 条 | `ai_reply_timeout_seconds` / `ai_reply_max_per_sweep` 默认值 |

### 6.2 已知取舍

- **降级到模板**：AI 失败时回退到人工审核过的文案，牺牲「更像人」换「不空转」。可关（把规则设为停用即可）。
- **摘要不落原文**：`unread_task` 只存 digest，代价是排查时看不到原话，收益是不扩大明文面。原话在 `reply_log.incoming_text` 里已经有了，需要时按 `platform_message_id` 关联。
- **P0 与 AI 强绑在一起**：本质上 P0 是独立缺陷修复。之所以写进本 spec，是因为不修 P0 则 AI 开关打开也无输入可读；但实施时 P0 应该先合入并单独验证。
- **AI 味命中即不发**：牺牲回复率换「不像机器」。代价是某些本来可以发的话被词表误杀，而且用户不会知道「其实生成过一条」。之所以这么选，是因为发出一条明显套话对求职关系的伤害更大，且这种伤害不可撤回。
- **风格字段是自由文本**：不做下拉模板、不做风格预设。好处是能精确描述自己想要的口吻，坏处是没有校验 —— 写「用东北话怼回去」也会照做。这是有意把控制权留给人。
- **默认词表 31 条比原计划激进**：原先打算 ≤12 条，现在一次给足，因为整串匹配 + 全是书面套话，实际误杀概率低于「漏检一条明显机器话术」的代价。代价是首次上线可能拦掉几条本来能发的话，靠 `BLOCKED_TONE` 计数与「恢复默认」按钮兜。若实际误杀偏高，删词是安全的单向操作。
- **告警窗口固定 7 天、不做配置**：与任务面板同口径。窗口可配会出现「面板说 42 次、提示条说 12 次」这种自相矛盾，比不灵活更糟。

### 6.3 已确认的决策（2026-10-29）

| # | 问题 | 决策 |
|---|---|---|
| 1 | 人设来源 | 用 `ai_config.introduce`，**另加 `ai_reply_style` 约束表达风格**。二者职责分离：前者说什么，后者怎么说。见 3.6.1 |
| 2 | AI 能否完全不回 | 允许 `skip`，但**必须记录原因**（枚举 + 补充说明），落 `reply_log.reason_code`。见 3.6.2 |
| 3 | 日志动作命名 | 分开：沿用 `SKIP_*` 表示「规则层拦截」，新增 `AI_*`（`AI_SKIPPED` / `AI_FAILED`）与 `BLOCKED_*`（`BLOCKED_CONTACT` / `BLOCKED_SENSITIVE` / `BLOCKED_TONE`）。三类前缀互不混用 |
| 4 | `GAVE_UP` 能否人工重试 | 给按钮，但**默认不展示**，藏在失败详情内；只接受 `GAVE_UP`，重置 3 次预算。见 3.8.3 |
| 5 | skip 占比告警 | **做成配置**：`ai_reply_skip_alert_pct`（默认 60，0 = 关闭）+ `ai_reply_skip_alert_min_sample`（默认 10）。判定在服务端，四档状态。见 3.8.2 |
| 6 | AI 味词表 | **做成配置**：`ai_reply_tone_terms`（换行分隔，空 = 默认 31 条）+ 独立开关 `ai_reply_tone_check_enabled`。结构规则不开放配置。见 3.7 |

### 6.4 仍待观察

1. **值守频率低时可能长期显示「样本不足」。** 告警窗口固定 7 天、最小样本 10。如果一周只跑出 6 次 AI 调用，提示条会一直是「未判定」—— 状态是诚实的，但也意味着这个指标短期没参考价值。上线 RUN 后先看实际频次，若确实偏低，再考虑把窗口拉长（而不是降低 `min_sample`，降样本数会制造噪声告警）。
2. **`贵司` / `我将` 这两条是默认词表里的边界项。** 前者是标准书面语、后者偶见于正常表述。留在默认里是因为它们在聊天场景中更像模板，但都属于「上线后按实际 `BLOCKED_TONE` 样本决定删不删」的范畴，不需要现在定。
