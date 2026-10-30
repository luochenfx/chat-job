# 文档

> 面向改代码、调接口、排查实现细节的人。以**当前代码与实机接口响应**为准（核对日期 2026-10-30）。
> `spec/` 是设计文档，其中标注「待评审」「非目标」「分期」的条目不代表已经实现；有冲突时以代码和实际接口为准。
> 只想用不想改的读者看仓库根 [`README.md`](../README.md) 就够了。

## 文档地图

| 想知道 | 看这里 |
|---|---|
| 某个能力落在哪段代码 | [已实现的能力与代码落点](#已实现的能力与代码落点) |
| 技术栈、分层与目录 | [技术栈与分层](#技术栈与分层)、[目录结构](#目录结构) |
| 怎么把环境跑起来 | [环境要求](#环境要求)、[构建与启动细节](#构建与启动细节) |
| 发给别人的包里有什么 | [分发包构成](#分发包构成) |
| 出厂默认值是多少 | [默认配置与运行行为](#默认配置与运行行为) |
| 出问题了怎么查 | [数据落点与排错](#数据落点与排错) |
| 有哪些接口 | [接口速查](#接口速查) |
| 需求、决策、调研 | [`spec/`](spec/)、[`adr/`](adr/)、[`tech/`](tech/)、[`ui/`](ui/) |
| 术语口径 | [`CONTEXT.md`](../CONTEXT.md) |

---

## 已实现的能力与代码落点

| 能力 | 说明 | 代码落点 |
|---|---|---|
| 组合扫描 | 城市 × 关键词队列、滚动加载、抓接口数据落库、卡片去重与断点续跑 | `server/.../orchestrator/RunService.java`、`platform/boss/BossAdapter.java` |
| 硬规则过滤 | 硬黑名单（公司/岗位名）→ HR 活跃度档位 → 已投递跳过，命中即不调 AI | `evaluate/BlacklistMatcher.java`、`evaluate/ActiveLevel.java` |
| AI 投递判定 | 单次调用产出「投不投 + 理由 + 招呼语」；关闭质量开关时用模板招呼语直接投 | `evaluate/AiJudge.java`、`evaluate/ChatClient.java` |
| 投递与确认 | 进会话先读消息再决定是否发，用「立即沟通 → 继续沟通」按钮文案复核送达；投递记录可查、可重置 | `platform/boss/BossChatNavigator.java`、`orchestrator/JobStore.java` |
| 配额与运行态 | 日投递配额、日评估上限、组合时间/轮数上限；配额/上限/组合耗尽只中止扫描 → `STANDBY`（值守待命），时段结束 → `WAITING_DUTY` 挂起（值守期不结束）；`CIRCUIT_OPEN` / `LOGIN_LOST` / `CANCELLED` / `FAILED` 等终止态 | `orchestrator/RunService.java`、`RunStatus.java`、`orchestrator/StandbyGate.java` |
| 值守时段 | 默认 09:00–22:00；窗口外点启动会落一条 `WAITING_DUTY` 挂起，调度每 30 秒轮询唤醒（跨重启可续，跨自然日复位组合队列），演练模式不受限制 | `orchestrator/DutyWindow.java`、`RunService.java` |
| 值守回复 | 关键词规则 + 文案池、敏感词优先拦截；可选「AI 回复」开关（默认关）；出站四重校验；未读任务落库 → 退避重试 3 次 → `GAVE_UP`，控制台可手动重试 | `watcher/`（`UnreadWatcher`、`ReplyRuleEngine`、`AiReplier`、`ReplyGuard`、`UnreadTaskStore`） |
| 自动发简历 | 仅当规则动作显式设为 `SEND_RESUME` 时点一次「发简历」，发完不补话；不交给 AI 决定。确认气泡**是可选步骤**（对方已索要过简历时不弹），成败一律看「简历卡片 +1（20 秒内）」 | `watcher/UnreadWatcher.java`、`BossAdapter.sendResume`、`web/ReplyRuleController.java` |
| 可见的活动状态 | `current_action` / `last_activity_at` / `scan_day` 三列 + `staleAfterSeconds`：等待期间也写「在等什么」，前端据此区分「在等」与「卡死」 | `RunService.park/act`、`RunController`、`console/composables/useRunStatus.ts` |
| 通知与自检 | 企业微信群机器人告警（可节流）、driver 指纹自检、登录态探测与扫码入口、数据清理 | `notify/`、`driver/SelfCheck.java`、`web/SystemController.java` |
| 控制台 | 概览 / 任务 / 岗位 / 配置 / 系统 五页，任务页有 SSE 实时事件与运行日志 | `console/pages/` |
| 凭据保护 | API Key 与 Cookie 密文落库（Windows 用 DPAPI，其他平台退化为 AES-GCM + 本地 key 文件），GET 接口只回「是否已配置」 | `config/SecretCipher.java`、`web/ConfigController.java` |

**未接通项与容易误判的口径**（避免按 spec 或直觉产生误判）：

- `platform_config.resume_image_enabled`（投递时追加图片简历）目前只在配置读写里出现，执行链路未消费，默认关。不要与已实现的值守规则 `SEND_RESUME` 混为一谈。
- 同名 HR 无法区分（平台会话无稳定 id），靠精确标题匹配 + 摘要二次校验，多命中即放弃，见 [`spec/0002-ai-reply.md`](spec/0002-ai-reply.md) §5.3。
- `QUOTA_REACHED` **V12 之后不再产生**，枚举保留只为让历史数据在界面上仍可读；配额命中的新出口是 `STANDBY`。
- 服务重启不自动接管进行中的 Run（`RUNNING` / `STANDBY` 一律标 `FAILED / SERVICE_RESTART`），`WAITING_DUTY` 挂起态跨重启保留 —— 这是 [`spec/0003-run-standby.md`](spec/0003-run-standby.md) 明确的非目标之一。
- **「今日主动投递」与「值守发简历」是两个口径，不能相加也不能互相顶替**（[ADR-0004](adr/0004-delivery-vs-resume-on-request.md)）：前者只读 `daily_counter.delivered`，由扫描侧在确认送达后累加，决定投递配额；后者只数 `reply_log` 里当日的 `REPLY_RESUME`，是值守里应对方索要发出去的简历，不占配额。守卫见 `RunStoreDeliveredTodayTest`。

## 技术栈与分层

| 组件 | 版本 / 说明 | 来源 |
|---|---|---|
| Java | 21（本机实测 Temurin 21.0.12.1） | `server/pom.xml` |
| Spring Boot | 3.5.16（Web + JDBC） | `server/pom.xml` |
| 存储 | SQLite 3.45.1.0 + Flyway 迁移（`V1`–`V12`）+ MyBatis-Plus 3.5.9 | `server/pom.xml`、`server/src/main/resources/db/migration/` |
| 浏览器驱动 | playwright-java 1.62.0 的 Java 壳 + **patchright-core 1.62.1** 的 Node driver（反检测），版本必须同 minor | `scripts/install-patchright.mjs`、[`adr/0001-patchright-as-playwright-driver.md`](adr/0001-patchright-as-playwright-driver.md) |
| 前端 | Nuxt 3（SPA 静态产物，`nuxt generate`），由 Spring Boot 托管，运行时不需要 Node | `console/package.json`、`console/nuxt.config.ts` |
| 本机工具 | Maven 3.8.6、Node.js 22 + npm 10（driver 进程用本机 node）、Chrome 或 Edge（本机实测解析到 Edge） | 实机核对 |

```
console (Nuxt 静态产物，Spring Boot 托管)
   │  REST 下行 / SSE 上行
web ── orchestrator ── evaluate ── platform(boss) ── driver(patchright + 真实浏览器)
             │                                            │
             └────────────── store (SQLite + Flyway) ──────┘
                              watcher / notify
```

## 目录结构

| 路径 | 作用 |
|---|---|
| `build.bat` | 一键构建：装配 patchright driver → 生成控制台 → 打包后端 jar |
| `start.bat` | 生产式启动：`java -jar`，显式绑定 `127.0.0.1:8080`，服务就绪后自动打开控制台 |
| `dev.bat` | 开发启动：`spring-boot:run`，改 Java 重启即可，不打 jar |
| `scripts/install-patchright.mjs` | 用 `npm pack` 装配 driver 到 `build/patchright-driver/package/cli.js` |
| `scripts/open-when-ready.mjs` | 轮询 `/api/system/status`，确认服务真的起来了再 `start` 浏览器；端口被别的程序占着时**不开**页面 |
| `scripts/build-dist.mjs`、`scripts/dist-template/` | 打绿色版分发包（内置 JRE + node + driver）与它的启动脚本模板 |
| `dist/` | 分发包产物，体积 100MB 以上，**不入库** |
| `server/` | Spring Boot 后端；迁移脚本在 `src/main/resources/db/migration/` |
| `console/` | Nuxt 控制台；静态产物 `console/.output/public`（`console/dist` 是它的软链） |
| `data/` | **活跃运行时数据**：`chat-job.db`（WAL）、`logs/chat-job.log`、备份（不入库） |
| `browser-data/` | 浏览器持久化 profile，登录态在这里（体积大、不入库） |
| `build/patchright-driver/` | 构建期下载的 driver（不入库，可重新生成） |
| `tools/` | 一次性探测 / 验证脚本与字典提取工具（不进 jar） |
| `docs/` | 本页（`README.md`）是文档入口；`spec/` 需求、`adr/` 架构决策、`tech/` 调研、`ui/` 界面草案 |
| `CONTEXT.md` | 项目术语的唯一口径 |

`browser-data.legacy-*` 与 `server/data.orphan-*` 是历史数据归位时留下的归档目录，**不是活跃数据**，勿当成当前库使用。

## 环境要求

1. **Windows**（`.bat` 脚本 + DPAPI 加密）；非 Windows 下凭据会退化为 AES-GCM + `data/.master-key`，保护强度低于 DPAPI。
2. **JDK 21**、**Maven**（本机 3.8.6）、**Node.js + npm 在 PATH 里**（driver 进程用本机 node 运行）。
3. **本机安装 Chrome 或 Edge**。`chat-job.browser.channel=auto` 会按 chrome → msedge 探测，解析结果显示在「系统」页；也可在 `application.yaml` 里显式指定 `channel` 或 `executable-path`。
4. **首次构建需联网**：`scripts/install-patchright.mjs` 用 `npm pack patchright-core@1.62.1` 下载 driver，`npm install` 也要拉 Nuxt 依赖；装好之后运行阶段不需要外网（除模型调用与平台访问）。
5. 一个 BOSS 直聘账号（建议小号试跑），以及一个兼容 OpenAI Chat Completions 的模型服务（Base URL / 模型名 / API Key）。
6. 可选：企业微信群机器人 Webhook（填在 `chat-job.notify-config.wecom-webhook`，不填则通知被跳过）。

> 第 2 条只针对**从源码构建**。绿色版自带 jlink JRE 与 node，对方机器只需要 Windows 10/11 + Edge 或 Chrome。

> **端口只认 `.bat` 里的 `__port`。** 宿主环境可能注入 `SERVER_PORT` / `SERVER__PORT`，Spring 宽松绑定会覆盖 yaml 里的 8080，所以两个 `.bat` 都显式传了 `--server.port`（命令行参数优先级最高）。后果是**改 `application.yaml` 的 `server.port` 不生效**：换端口改 bat 顶部那一行，或临时 `start.bat 8081` / `set CHAT_JOB_PORT=8081` 再跑。自动打开的页面用同一个变量拼出来，不会出现「开了 8080 其实跑在 8081」。

## 构建与启动细节

`build.bat` 依次执行：`node scripts/install-patchright.mjs` → `cd console && npm run build`（`nuxt generate`）→ `mvn -B -f server/pom.xml -DskipTests package`。

`start.bat` 会创建 `data/`、`data/logs/`，检查 driver 与 jar 是否存在，然后：

```
java -jar server\target\chat-job.jar --server.address=127.0.0.1 --server.port=8080
```

**浏览器会自动打开 <http://127.0.0.1:8080/>** —— 不是硬等固定秒数，而是由 `scripts/open-when-ready.mjs` 在后台轮询 `/api/system/status`，确认应答的确实是 chat-job 后才调 `start`。因此：JVM/Flyway 慢只会晚开一会儿；端口被别的程序占着时窗口里会留一行 `[open] port 8080 answers, but it is NOT chat-job (occupied?)`，**不会**把你带到别人的页面上。不想自动开就删掉 bat 里那行 `start "" /b node ...`，手动访问即可。启动日志首行横幅会打印工作目录、数据目录、profile、浏览器与 driver 状态。

两个脚本结束时都会停住等你按键：构建成功与否、失败中断在哪一步、退出码多少，都直接印在窗口里，不会「一闪而过」。失败时窗口里最后一句通常是 `[x] 构建失败，中断于：<步骤名>`，往上翻最近一段报错即根因。

> **`.bat` 必须存成 GBK/ANSI + CRLF。** 存成 UTF-8 时，cp936 控制台按「两字节一个字」解码，会把行尾换行也吃掉，于是 `rem` 注释里的半截文字被当成命令执行，出现一堆莫名其妙的 `'ion.yaml' 不是内部或外部命令` / `'鍏堟墽琛?build.bat' 不是内部或外部命令`。改完脚本请双击跑一遍，窗口里不该有任何「不是内部或外部命令」。

### 开发模式（改代码后不用重新打包）

```bat
node scripts\install-patchright.mjs
dev.bat
```

需要前端热更新时另开一个终端：

```bash
cd console
npm install        # 首次
npm run dev        # http://127.0.0.1:3000，/api 已代理到 8080
```

### 用 HTTP 接口跑一轮（可复制）

下面命令在 **Git Bash** 中执行；服务已在 `127.0.0.1:8080` 运行。

```bash
# 1) 看状态灯：driver、浏览器、字典条数、今日计数
curl -s http://127.0.0.1:8080/api/system/status

# 2) 打开登录页扫码（会弹出浏览器窗口），扫完探测一次
curl -s -X POST http://127.0.0.1:8080/api/system/open-login
curl -s -X POST http://127.0.0.1:8080/api/system/probe-login

# 3) 启动一轮演练（NORMAL 请改 mode；不传 mode 默认 NORMAL）
curl -s -X POST http://127.0.0.1:8080/api/runs \
  -H "Content-Type: application/json" -d '{"mode":"DRY_RUN"}'

# 4) 看当前 Run、组合队列与值守时段状态
curl -s http://127.0.0.1:8080/api/runs/current

# 5) 实时事件流（Ctrl+C 退出）；日志走独立接口
curl -sN http://127.0.0.1:8080/api/runs/1/events
curl -s "http://127.0.0.1:8080/api/runs/1/logs?afterId=0&limit=200"

# 6) 停止（把 1 换成 /api/runs/current 里的 run.id）
curl -s -X POST http://127.0.0.1:8080/api/runs/1/cancel
```

若返回「已有进行中的任务（id=N，STANDBY）」或「已有一条任务在等待值守时段」，先从 `/api/runs/current` 取 `run.id`，调 `POST /api/runs/{id}/cancel` 再启动——`uk_run_running` 把 `RUNNING` / `PAUSED` / `WAITING_DUTY` / `STANDBY` 都算「持有浏览器」，同一平台只允许一条。

**实机响应片段**（2026-10-30 00:02 采集，服务已在运行）：

```json
// GET /api/system/status
{"driverAvailable":true,"browserChannel":"msedge","browserResolved":true,
 "browserReady":true,"running":false,"deliveredToday":0,"evaluatedToday":0,
 "dictionaryTotal":579,"serverTime":"2026-10-30 00:02:59"}

// GET /api/runs/current（当时确有一条挂起任务）
{"run":{"id":21,"status":"WAITING_DUTY","mode":"NORMAL",
       "message":"当前 23:49 不在值守时段 09:00–19:00 内，已挂起等待；明天 09:00 之后才会真正执行"},
 "running":false,"duty":{"window":"09:00–19:00","open":false,"opensInMinutes":536},
 "combinations":[{"city":"福州","keyword":"java","status":"PENDING"}]}
```

> 上面这条也说明：**值守时段是库里的配置，不一定是出厂默认值**。本机当前配的是 `09:00–19:00`，默认值是 `09:00–22:00`。
> 另注：该片段采集于 V12 之前。现在挂起文案是「已到值守结束时间（…），停止操作页面；…值守期没有结束，不需要重新启动」，`currentAction` 落「已挂起，等 … 的下一个窗口」（`RunService.park()`）；待命事件是 `run.standby`，通知标题「扫描已结束，值守继续」。

## 分发包构成

产物 `dist/chat-job-<version>-win-x64.zip`（约 125MB，解压 209MB），解压后双击 `start.bat` 即用：

| 路径 | 内容 | 说明 |
|---|---|---|
| `start.bat` | 启动脚本 | 由 `scripts/dist-template/start.bat` 转码生成，**GBK + CRLF** |
| `使用说明.txt` | 给使用者的说明 | UTF-8 BOM，记事本可直接看；同样来自 `scripts/dist-template/` |
| `build-info.txt` | 这一包是什么时候、用什么版本、多大 | 排查「对方装的到底是哪一版」用 |
| `app/chat-job.jar` | 后端 + 控制台静态产物 | 46MB（已剔除 `driver-bundle`） |
| `build/patchright-driver/` | patchright driver | 13MB |
| `runtime/jre/` | jlink 裁剪的 Java 21 | 62MB 上下，`java.base` 等 6 个模块 |
| `runtime/node.exe` | driver 用的 node | 88MB，构建期从 `driver-bundle` 里抠出来的 win32_x64 那份 |
| `scripts/open-when-ready.mjs` | 就绪后再开页面 | 与服务端同一份脚本 |

**包里刻意不含** `data/` 与 `browser-data/`：前者是 SQLite + DPAPI 密文（换了 Windows 账户解不开），后者是登录态（等于把账号送出去）。首次运行由 Flyway 建空库、由 patchright 建空 profile。

`server/pom.xml` 里对 `driver-bundle` 的 exclusion 是这条分发链的前提：那份 jar 有 202MB，装着 4 个平台的 node，而本项目一个都不用（真正干活的是 patchright driver，node 由 `runtime/node.exe` 或 PATH 提供）。剔掉后 fat jar 从 250MB 降到 46MB。代价只有一条：driver 不可用且 `fail-closed=false` 时不再退回官方 driver —— `BrowserSession` 把那条路径改成了显式报错，而不是抛一句「找不到 driver 资源」。

真机验收（本机实测，`PATH` 只留 `C:\Windows\system32`，即 java 与 node 都不可见）：

1. `start.bat` 启动 → `/api/system/status` 里 `driverAvailable=true`、`nodePath` 指向包内 `runtime\node.exe`、`dictionaryTotal=579`（Flyway 从空库迁移完成）。
2. `POST /api/system/self-check` → `ok=true`、`webdriver=false`，真实 Edge 被拉起（证明 JRE → node → patchright → 浏览器整条链通）。
3. `GET /` → 200，`<title>chat-job 控制台</title>`。
4. 把 `runtime\node.exe` 改名再启动 → 状态页明确报「PATH 与 `<home>\runtime` 下都没有 node.exe」，而不是笼统的「驱动坏了」。

分发时的三条硬要求：解压到**可写目录**（别放 `Program Files`）；对方自备一个兼容 OpenAI Chat Completions 的模型服务；`start.bat` 别另存成 UTF-8。

## 默认配置与运行行为

| 配置 | 默认值 | 位置 |
|---|---|---|
| 日投递配额 / 日评估上限 | 30 / 150 | `platform_config`（V1） |
| 卡片间隔基准 / 滚动轮上限 / 单组合时间上限 | 10 秒（实际随机 5–10 秒）/ 300 轮 / 1200 秒 | 同上 |
| HR 活跃度下限 | 本月 | 同上 |
| 未读列表一轮扫描上限 | 100（可配，上限 500，截断会打 warn） | `platform_config.unread_scan_limit`（V6） |
| 待命/值守巡检间隔 | 120 秒（钳制 30–600，实际 ±10% 抖动；它同时是「AI 回复最长延迟」与「页面刷新频率」） | `platform_config.watch_interval_seconds`（V12） |
| 值守时段 | 09:00–22:00（起止相同 = 全天） | `duty_start_time` / `duty_end_time`（V4） |
| 质量开关 | 开（AI 判定；关则用模板招呼语直接投） | `ai_config.quality_gate_enabled` |
| 值守 AI 回复 | **关** | `ai_config.ai_reply_enabled`（V5） |
| AI 味检查 / 默认词表 | 开 / 31 条（清空输入框 = 用默认，不是关闭） | `ai_config.ai_reply_tone_check_enabled`、`ai_reply_tone_terms`（V7） |
| skip 占比告警 | 阈值 60%、最小样本 10（阈值 0 = 关闭但界面显式提示） | `ai_config.ai_reply_skip_alert_*`（V9） |
| AI 回复超时 / 单轮调用上限 | 8 秒（与提供商超时取较小值）/ 5 条 | `ai_config.ai_reply_timeout_seconds`、`ai_reply_max_per_sweep`（V8） |

配置在控制台保存后**每次读取都查库，无需重启**。API Key 不回发，接口只给 `keyConfigured`。

## 数据落点与排错

| 数据 | 路径（相对仓库根） |
|---|---|
| 数据库 | `data/chat-job.db`（WAL 模式，伴随 `.db-wal` / `.db-shm`） |
| 运行日志 | `data/logs/chat-job.log`（保留 14 天，单文件 10MB 滚动） |
| 浏览器 profile | `browser-data/` |
| patchright driver | `build/patchright-driver/` |

| 现象 | 处理 |
|---|---|
| 「patchright driver 不可用」，任务被拒绝启动 | 执行 `node scripts/install-patchright.mjs`，确认 `build/patchright-driver/package/cli.js` 存在且 `node` 在 PATH |
| 浏览器报 `Chromium distribution 'chrome' is not found` | 装 Chrome/Edge，或在 `application.yaml` 配 `chat-job.browser.executable-path`；「系统」页能看到实际解析结果 |
| 配置/端口「明明写了却没生效」 | 看启动横幅里的**数据目录与端口**；只从仓库根用 `.bat` 启动，别从 `server/` 直接跑 |
| 启动后立刻 `LOGIN_LOST` | 系统页点「打开登录页」扫码 → 「探测登录」确认；Run 启动前会先探测登录态 |
| 任务停在 `WAITING_DUTY` | 正常行为：不在值守时段。检查配置页的起止时间；挂起不是收工，调度每 30 秒唤醒一次，重启后也能接上 |
| 任务停在 `STANDBY`（值守待命） | 正常行为：配额 / 评估上限 / 组合耗尽，只中止扫描。值守照常巡检，配额被调高或跨自然日后自动回扫 |
| 界面显示「可能已卡住」 | `staleAfterSeconds`（STANDBY 下 3 倍巡检间隔，默认 360 秒；终止态/无 Run 为 90 秒）内没有活动就提示。先看 `currentAction` 与 `data/logs/chat-job.log` 再判断是不是真卡死 |
| 想看到值守任务去向 | 控制台「系统」页 → 未读任务面板（待处理/重试中/已放弃 + 手动重试） |
| 数据库出现两份 | 对比启动横幅路径；历史归档 `server/data.orphan-*` / `browser-data.legacy-*` 不是活跃库 |

> **`tools/inspect-unread-tasks.mjs` 目前路径过时**：脚本里写死 `server/data/chat-job.db`，而活跃库已归位到 `data/chat-job.db`，直接跑会报「找不到数据库」。排查请优先用控制台的未读任务面板。

## 测试与诊断脚本

```bash
# 后端单测（hand-written fakes，项目不使用 mock 框架）
mvn -B -f server/pom.xml test

# 只构建前端
cd console && npm run build
```

| 脚本 | 用途 | 副作用 |
|---|---|---|
| `tools/precheck.mjs` | 值守主链 10 项前置自检（会话列表、未读、消息读取、输入与发送按钮状态） | 只读；**不实际点击发送**，需已登录的 profile |
| `tools/verify-p0.mjs` / `verify-p1.mjs` | driver 指纹与选择器回归 | 只读，不清红点 |
| `tools/probe-*.mjs` | 页面结构 / 简历入口 / 会话 id 的一次性探测 | 浏览器实机操作，按需运行 |
| `tools/inspect-unread-tasks.mjs` | 未读任务汇总（只跑 SELECT） | 只读，但库路径待更新，见上节 |
| `tools/extract-boss-dict.py` | 从参考项目字典表生成 `V2__seed_platform_option.sql` | 生成用，不在运行链路 |

## 需求与决策索引

| 文档 | 内容 |
|---|---|
| [`spec/0001-chat-job-mvp.md`](spec/0001-chat-job-mvp.md) | 主需求：扫描、判定、投递、批处理边界 |
| [`spec/0002-ai-reply.md`](spec/0002-ai-reply.md) | 值守 AI 回复与实施记录（含出站四重校验） |
| [`spec/0003-run-standby.md`](spec/0003-run-standby.md) | 值守期：扫描收工后值守继续 + 可见的运行态 |
| [`spec/0004-zhaopin-parallel.md`](spec/0004-zhaopin-parallel.md) | 智联接入与多平台并行：阶段划分与实现顺序 |
| [`adr/0001-patchright-as-playwright-driver.md`](adr/0001-patchright-as-playwright-driver.md) | 为什么用 patchright 当 playwright 的 driver |
| [`adr/0002-standby-instead-of-finish.md`](adr/0002-standby-instead-of-finish.md) | 配额用完不结束，改为值守待命 |
| [`adr/0003-parallel-platforms.md`](adr/0003-parallel-platforms.md) | 每个平台一条独立流水线，并行运行 |
| [`adr/0004-delivery-vs-resume-on-request.md`](adr/0004-delivery-vs-resume-on-request.md) | 投递只算主动投递，应对方要求发的简历不进投递表 |
| [`tech/0001-reference-get-jobs.md`](tech/0001-reference-get-jobs.md) | 参考实现与未闭环事实 |
| [`tech/0002-ai-reply-research.md`](tech/0002-ai-reply-research.md) | 值守 AI 回复调研 |
| [`tech/0003-zhaopin-research.md`](tech/0003-zhaopin-research.md) | 智联接入依据：页面实测事实、与 get_jobs 的交叉验证、并行评估 |
| [`ui/0001-reply-rules.html`](ui/0001-reply-rules.html) | 回复规则界面草案 |

## 接口速查

| 分组 | 接口 |
|---|---|
| 任务 | `POST /api/runs`（body `{"mode":"NORMAL"\|"DRY_RUN"}`）、`POST /api/runs/{id}/pause\|resume\|cancel`、`GET /api/runs/current`、`GET /api/runs`、`GET /api/runs/{id}/events`（SSE）、`GET /api/runs/{id}/logs` |
| 配置 | `GET\|POST /api/config/providers`、`DELETE /api/config/providers/{id}`、`GET\|PUT /api/config/ai`、`GET\|PUT /api/config/platform/{platform}`、`GET /api/config/dictionary?platform=&type=`、`POST /api/config/dictionary/reload` |
| 岗位 | `GET /api/jobs`、`GET /api/jobs/{id}/deliveries`、`POST /api/jobs/{id}/reset`、`GET\|POST /api/blacklist`、`DELETE /api/blacklist/{id}` |
| 值守 | `GET\|POST /api/reply/rules`、`DELETE /api/reply/rules/{id}`、`GET\|POST /api/reply/sensitive`、`GET /api/reply/unmatched`、`GET /api/reply/logs`、`GET /api/watcher/stats`、`GET /api/watcher/unread-tasks`、`POST /api/watcher/unread-tasks/{id}/retry` |
| 系统 | `GET /api/system/status`、`POST /api/system/self-check`、`POST /api/system/probe-login`、`POST /api/system/open-login`、`POST /api/system/notify-test`、`POST /api/system/cleanup`、`GET /api/system/counters` |

## 失败即停机（fail-closed）

driver 不可用、自检失败、掉登录、遇验证码 —— 一律停机并告警（企业微信 Webhook 配了才发）。程序不尝试绕过任何平台风控，也不做 E2E 自动化测试。要改这部分行为，先读 [`spec/0001-chat-job-mvp.md`](spec/0001-chat-job-mvp.md) 的非目标章节。
