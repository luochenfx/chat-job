# 告警配置存进数据库，并从控制台配置

## Status

accepted（2026-11-01）。已实现：`V15`、`ConfigStore.notifyConfig()`、`NotifySettings`（解析层）、`AlertCategory`、`WecomRobotNotifier` 改读库、`GET/PUT /api/config/notify` 与 `DELETE /api/config/notify/webhook`、系统页卡片；新增单测 14 例（手写假对象，不用 mock 框架），全量 287 例通过。

## Context

告警只有一条通道：企业微信群机器人 `WecomRobotNotifier`，webhook 取自 `chat-job.notify-config.wecom-webhook`（`application.yaml:83-84`），经 `AppProperties.NotifyProps` 在启动时绑定。控制台系统页有一个「消息通知」卡片（`console/pages/system.vue:138-145`），但只有一段说明和一个「发送测试消息」按钮，没有任何输入框。

也就是说配置入口只有配置文件。而这个配置在分发包里并不存在：`dist/` 下没有 `application.yaml`，它只在 `app\chat-job.jar` 内；`scripts/dist-template/使用说明.txt` 全文没提 webhook。绿色版用户若要配告警，只有两条未文档化的路——解 jar 改 yaml，或用环境变量 / `--chat-job.notify-config.wecom-webhook=` 启动参数。

同时，现有告警的事件构成让「全有或全无」成为问题：`run-failed`（driver 不可用、自检未通过、异常终止）、`login-lost`、`circuit` 是「今天可能白跑一天」的信号，而 `standby`（搜索结束、值守继续）与 `duty-end`（值守时段结束）每天必然各发一条。`WecomRobotNotifier` 自己的注释写过「否则手机被自家告警刷屏，最后你会把它静音 —— 那告警就失效了」，但除了忍着，用户没有任何手段。

## Decision

1. **新增单行表 `notify_config`**（`V15`），列：`wecom_webhook_cipher TEXT`、`alert_abnormal INTEGER`、`alert_standby INTEGER`、`alert_duty_end INTEGER`、`updated_at TEXT`。形状对齐 `ai_config`：单行、开关存 INTEGER 0/1，不采用键值表。缺行由 `ConfigStore.notifyConfig()` 懒建，与 `aiConfig()` 一致，迁移里不插默认行。
2. **地址与 API Key 同等待遇**：`wecom_webhook_cipher` 存 `SecretCipher` 密文，不存明文。webhook 地址里带 `key=`，拿到它就能往群里发任意消息。列名带 `_cipher` 后缀，与 `provider.api_key_cipher` 对齐。
3. **配置入口放在系统页原「消息通知」卡片就地改造**，标题改为「告警通知」，与 driver 就绪、登录态并列——三者都是「这台机器现在能不能干活」的体检项。卡片上显示当前是否已配置。
4. **webhook 不回显**：`GET` 只返回 `configured` 与 `source`，输入框留空表示不修改，与 `config.vue` 里 API Key 的做法一致。删除已保存的 webhook 用单独的「清除地址」按钮（`DELETE /api/config/notify/webhook`）+ 二次确认，不用空字符串或 `null` 编码「清除」。
5. **库里为空时回落读 yaml**（只读，不回写），`source` 区分四态：`db` / `yaml` / `none` / `broken`。**`broken` 不回落**——库里有密文却解不开（换了 Windows 账户或电脑）时绝不退回 yaml，那份可能指向另一个群。
6. **没有任何钩子生效时不留静默**：未配置 webhook 时点「发送测试消息」返回 `delivered:false` 与明确文案，不用 200 空成功；卡片显示最近一次发送的时间、成败与原因（存内存，重启清零）。企业微信的失败有两种，HTTP 200 + `errcode != 0`（例如机器人 key 失效）也算失败，只看状态码会报「已发送」而手机上什么都没有。
7. **三个告警类别开关，全局一套，默认全开**：异常（`run-failed` + `login-lost` + `circuit`）／进入待命（`standby`）／时段结束（`duty-end`）。`test` 不受开关约束；未登记的 `kind` 归到「异常」——漏发一条真告警比多发一条没人认识的消息严重得多。默认值与今天的实际行为一致，升级不制造意外。
8. **测试消息不参与节流**。节流原本按 `kind` 计，`test` 也会被 5 分钟窗口吃掉，现象是「连点两次只有一条」，读起来就是按钮坏了。用户自己点的动作要立刻看到结果。

## Considered Options

- **保持 yaml，只补文档与环境变量。** 绿色版用户不会去解 jar。这条选项等于承认「配置入口存在但没人能用到」，与本轮要解决的问题等价。

- **完全弃用 yaml，只读库。** 少一套回落分支，但谁当初是用启动参数配置的，升级后告警会无声停掉，而现象与「本来就没配」完全一样——正是本轮要消除的那类盲区。

- **首次启动把 yaml 值搬进库，之后 yaml 作废。** 要写一次性迁移代码和一个只跑一次的判据。实测本机 `application.yaml` 是空字符串、分发包内无该文件，是在为不存在的数据写迁移。回落读取能达到同样的兼容效果且不写库。

- **键值表（`key` / `value`）。** `value` 只能是 TEXT，开关存 `"1"` / `"true"` 之后需要一个额外约定才知道怎么判；`ai_config` 的 INTEGER 0/1 已有先例。「以后加通道不用改表」是假收益：真加第二个通道时通道之间是并列关系，会退化成 `wecom.webhook` / `serverchan.key` 这种用点号编码结构的东西。

- **事件开关按事件类型拆成五个 checkbox。** `run-failed` 的三个来源（driver 不可用、自检未过、异常终止）在用户看来是同一件事「没跑起来 / 跑挂了」，拆开只增加选择成本。

- **事件开关按平台各一套。** 共 6 个开关，而用户会做的动作只有一种——「待命和下班这两类别再吵我」，与平台无关。标题里已经带平台名（`headOf`），收到消息能看出是谁出的事。

- **暴露节流窗口秒数。** 300 秒是「防刷屏」的实现手段，用户真正要控制的是「哪些事值得吵我」，即事件开关。让用户调一个自己无法预判效果的数字没有意义。该项继续留在 yaml，只把生效值回发给界面用于文案（否则 yaml 改成 600 之后，界面上的「5 分钟」就是假话）。

- **webhook 明文存库。** 省掉解密路径，但库里就有了一个「谁拿到都能往群里发消息」的凭据，而 `data\chat-job.db` 正是用户会随手复制备份的目录。与 `provider.api_key_cipher` 的口径也不一致。

## Consequences

- **`Notifier` 的读配置方式改了**：`WecomRobotNotifier` 不再从 `AppProperties` 取地址，改为每次发送前经 `NotifySettings` 现取，保存即生效。新增 `Delivery` 记录与 `lastDelivery()`（接口上带默认实现，其他通道可以不支持）。
- **两个来源的合并只能有一处**：`NotifySettings` 是唯一的解析点。散在通知器和 Controller 里各写一遍，迟早出现「页面说未配置、实际还在往老群发」。
- **节流窗口与 webhook 来源可以不同**：前者仍读 yaml，后者优先读库。卡片只标注 webhook 的来源，不要笼统写「来自配置文件」。
- **「保存」与「测试」是两个独立动作**，这产生一个坑：改了输入框没保存时，测试按钮测的是库里那份旧值。前端在输入框有未保存内容时禁用测试按钮并给出说明。
- **三个开关全关是允许的**：它与「清除地址」不同——一个是不想被吵，一个是删掉凭据。界面不拦，但三枚都关时用户自己看得见。
- **`docs/README.md`** 的「可选步骤」与接口清单已改：入口指向系统页，并保留「yaml 仍可配置、库里为空时回落」一句。
- **`CONTEXT.md`** 新增「告警」「告警类别」两词。「告警」的 `_Avoid_` 里收「消息通知」——UI 之前就叫这个名字，而它读起来像「HR 发来消息时提醒我」，那件事本项目并没有做。
- **未做**：告警历史上限、正文长度（企微 markdown 有 4096 字节上限）都没有处理。一条正文被截断时，企微会返回 `errcode != 0`，现在会显示成「被企业微信拒绝」，能被看见，但不会自动改写。
