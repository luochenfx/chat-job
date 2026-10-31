<template>
  <div>
    <div class="page-head">
      <div>
        <h1>任务</h1>
        <p>
          点一次启动，就会按设定搜索岗位、发招呼语，同时盯着 HR 的消息自动回复。
          搜索停下来之后，消息照样会回
        </p>
        <!-- 时段只决定「什么时候开始跑」，不再是启动门禁：窗口内说到点时刻，等待态说还要等多久 -->
        <p v-if="dutyLine" class="hint" style="margin-bottom: 0">{{ dutyLine }}</p>
      </div>
      <div class="row wrap" style="justify-content: flex-end">
        <!-- 控件与它作用的对象同屏：整页内容都是这个平台的，所以切换器放在页头 -->
        <PlatformSwitch v-model="platformPick" :platforms="platforms" />
        <!--
          启动前就说清这一轮会做什么。「只值守不会扫描」「只扫描扫完即结束」都是启动之后
          才看得出差别的事，等跑起来才说已经晚了。
        -->
        <span v-if="startHint" class="start-hint">{{ startHint }}</span>
        <select v-model="mode" style="width: 170px" :disabled="active">
          <option value="NORMAL">正常投递</option>
          <option value="DRY_RUN">演练模式（不发消息）</option>
        </select>
        <button v-if="!active" class="btn primary" :disabled="busy" @click="start">启动</button>
        <template v-else>
          <button v-if="run.status === 'PAUSED'" class="btn" :disabled="busy" @click="resume">继续</button>
          <!-- 等待态没有可暂停的东西，只能取消；取消会直接落库，不等窗口打开 -->
          <button v-else-if="run.status === 'WAITING_DUTY'" class="btn danger" :disabled="busy" @click="cancel">
            取消等待
          </button>
          <template v-else>
            <!-- 两个平台可能同时在跑，裸的「停止」会被点错平台，文案一律带上平台名 -->
            <button class="btn" :disabled="busy" @click="pause">暂停 {{ platformLabel }}</button>
            <button class="btn danger" :disabled="busy" @click="cancel">停止 {{ platformLabel }}</button>
          </template>
        </template>
      </div>
    </div>

    <!-- 待命横幅：配额/评估/组合耗尽只是「今天不再找岗位」，不是「今天不再回消息」。
         没有这条横幅，界面只剩一个黄标签，人会以为任务已经半死了。
         未启用扫描的平台从来没有扫过，标题与页脚都不能沿用「扫描已结束」那套说法。 -->
    <div v-if="run?.status === 'STANDBY'" class="notice">
      <div class="notice-title">
        {{ scanOn ? '正在盯着消息：搜索已结束，未读消息照常回复' : '正在盯着消息：这个平台没有开启「搜索岗位」，只处理未读消息' }}
      </div>
      <p class="notice-body">{{ run.message }}</p>
      <p class="notice-foot">
        <template v-if="scanOn">
          把「每天最多主动联系多少个岗位」或「每天最多查看多少个岗位」调高，或者过了零点进入新的一天，
          就会自动继续搜索，不需要停止再重新启动。
        </template>
        <template v-else>
          这一轮不会再去搜索岗位，因为这个平台的「搜索岗位」开关是关着的。
        </template>
        如果想彻底结束这一轮，点「停止」。
      </p>
    </div>

    <!-- 窗口外提示：启动按钮照常可点，这一轮不会立刻执行，而是排到窗口打开时自动开始 -->
    <div v-if="dutyWarning" class="notice warn">
      <div class="notice-title">现在不在自动回复时段（{{ duty.window }}）</div>
      <p class="notice-body">
        点启动不会立刻开始：任务会先排队，等到时间段开始再执行，{{ duty.hint }}。
        排队期间不会打开浏览器、不发消息、也不会把未读消息点成已读，随时可以「取消等待」。
        <template v-if="duty.opensInMinutes > 0">预计还要等 {{ waitText(duty.opensInMinutes) }}。</template>
      </p>
      <p class="notice-foot">
        想在时间段外马上试一遍流程，可以切到<b>演练模式</b>：它不受时间段限制，但不会发出任何消息。
      </p>
    </div>

    <!-- 演练说明：启动前与运行中都必须可见。运行中尤其重要 ——
         那时「投递 0」是正确结果，但看上去像卡住，不解释就一定会被误读成失败。 -->
    <div v-if="dryRunVisible" class="notice">
      <div class="notice-title">演练模式：不会发出任何消息</div>
      <p class="notice-body">
        招呼语和自动回复都不会发出去，对方什么也收不到。演练会真的走一遍完整流程，不是只看不操作。
      </p>
      <ul class="notice-list">
        <li>会真的打开平台的搜索页、翻动岗位列表、读取岗位详情，和正常运行时一样。</li>
        <li>会真的调用 AI 判断岗位并写招呼语，消耗 AI 用量，也计入当天查看岗位的数量。</li>
        <li>
          自动回复会真的打开对话读消息。打开对话就会清掉未读标记，平台没有「标回未读」的功能，
          所以这些消息之后不会再出现在未读列表里，正常运行时也不会再回复它们。
        </li>
        <li>不受自动回复时段限制，任何时间都能跑；当天的投递名额和查看上限照常生效。</li>
      </ul>
      <p class="notice-foot">
        演练的结果只写进运行日志（会标明「本会投递」「本会回复」）。页面上的数字不会增加，
        也不会显示成已发送。
      </p>
    </div>

    <div class="card">
      <div class="row wrap" style="justify-content: space-between; gap: 16px">
        <div>
          <h2>这一轮的进度</h2>
          <p class="hint" style="margin-bottom: 0">
            <template v-if="run">
              #{{ run.id }} ·
              <span class="tag" :class="run.mode === 'DRY_RUN' ? 'warn' : ''">{{ modeText(run.mode) }}</span>
              · {{ (run.startedAt || '未开始').replace('T', ' ') }}
            </template>
            <template v-else>{{ platformLabel }} 还没有启动过</template>
          </p>
        </div>
        <div class="row" style="gap: 26px">
          <div>
            <div class="hint-inline">状态</div>
            <span class="tag" :class="runView.tone">{{ runView.label }}</span>
          </div>
          <div>
            <div class="hint-inline">进度</div>
            <div class="num">搜索组合 {{ (run?.combinationIndex ?? 0) + 1 }} / 岗位 {{ run?.lastCardIndex ?? 0 }}</div>
          </div>
          <div>
            <div class="hint-inline">主动联系 / 已查看</div>
            <!-- 演练下投递恒为 0：显示 0 会被读成「投了但全失败」，用 — 表示「没有发生」 -->
            <div class="num">{{ runIsDryRun ? '—' : (run?.deliveredCount ?? 0) }} / {{ run?.evaluatedCount ?? 0 }}</div>
          </div>
          <div>
            <div class="hint-inline">实时连接</div>
            <span class="tag" :class="connected ? 'ok' : ''">{{ connected ? '已连接' : '未连接' }}</span>
          </div>
        </div>
      </div>

      <!-- 「现在在干什么」+「上次动是什么时候」：等待期间日志是静默的，
           没有这一行，界面无法区分「在等」和「卡死」 -->
      <div
        v-if="run && (activity.action || activity.text)"
        class="row wrap"
        style="margin-top: 14px; gap: 10px; align-items: baseline"
      >
        <span class="hint-inline">正在做什么</span>
        <span>{{ activity.action || '—' }}</span>
        <span v-if="activity.text" class="tag" :class="activity.tone">{{ activity.text }}</span>
      </div>
    </div>

    <div class="grid cols-2">
      <div class="card">
        <h2>搜索组合</h2>
        <p class="hint">
          每个组合就是一组「城市 + 关键词」，逐个执行；每个组合都有翻页次数和时长上限
        </p>
        <div v-if="!combinations.length" class="empty">还没有搜索组合</div>
        <table v-else>
          <thead>
            <tr>
              <th style="width: 36px">#</th>
              <th>城市 / 关键词</th>
              <th style="width: 80px">状态</th>
              <th style="width: 60px">岗位</th>
              <th style="width: 84px">主动联系</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in combinations" :key="c.id">
              <td class="num">{{ c.seq + 1 }}</td>
              <td>{{ c.city || '不限' }} / {{ c.keyword || '不限' }}</td>
              <td><span class="tag" :class="comboView(c).tone">{{ comboView(c).label }}</span></td>
              <td class="num">{{ c.cardsSeen }}</td>
              <td class="num">{{ c.delivered }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="card">
        <h2>实时事件</h2>
        <p class="hint">这里只显示状态和数量变化，详细记录在下面的运行日志里</p>
        <div v-if="!events.length" class="empty">等待事件…</div>
        <div v-else class="log" style="max-height: 320px">
          <div v-for="(e, i) in reversed" :key="i">
            <span class="t">{{ time(e.at) }}</span> {{ describe(e) }}
          </div>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="row" style="justify-content: space-between; margin-bottom: 4px">
        <h2>运行日志</h2>
        <button class="btn sm" @click="clearLogs">清空</button>
      </div>
      <p class="hint">运行记录保存在本机，保留 14 天；连接中断后可以在这里补齐</p>
      <div class="log" style="max-height: 340px">
        <div v-if="!logs.length" class="empty">暂无日志</div>
        <div v-for="l in logs" :key="l.id">
          <span class="t">{{ (l.createdAt || '').replace('T', ' ') }}</span>
          <span :style="{ color: l.level === 'WARN' ? 'var(--warn)' : l.level === 'ERROR' ? 'var(--danger)' : 'inherit' }">
            [{{ l.level }}]
          </span>
          {{ l.message }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
const { call } = useApi()
const { show } = useToast()
const { events, connected, open, clear, close } = useRunStream()
const { platforms, ensure, current, platform: platformSlug, label: platformLabel, select } = usePlatform()
// 秒级时钟抽在 composable 里，概览卡与这里共用同一句「多久没动了」
const nowMs = useTickClock()

/** 分段控件的 v-model：读的是选中的平台，写的时候走 select（记忆到 URL 与 localStorage） */
const platformPick = computed({
  get: () => platformSlug.value,
  set: (value: string) => {
    select(value)
  }
})

const run = ref<any>(null)
const combinations = ref<any[]>([])
const logs = ref<any[]>([])
const mode = ref('NORMAL')
const busy = ref(false)
// 服务端算好的时段状态（跨天/全天三种写法的判定只在后端做一次，界面不重复实现）
const duty = ref<any>(null)
/** 无响应阈值，后端算好下发；前端只做比较 */
const staleAfter = ref(90)
let lastLogId = 0
let pollTimer: ReturnType<typeof setInterval> | null = null
let currentRunId: number | null = null

// 状态 → 标签/色调/能不能暂停，一处定义（原来三处各写一遍，加状态必漏）
const runView = computed(() => runStatusView(run.value?.status))
const active = computed(() => runView.value.active)

/**
 * 当前平台的扫描开关，取自平台清单（`/api/system/platforms`）。
 *
 * <p>不在任务页再请求一次平台配置：清单里已经带了这两个布尔，各页共用同一份快照；
 * 多请求一次就多一个「清单说 A、配置说 B」的时间窗。缺省按开，与后端缺省口径一致。
 */
const scanOn = computed(() => current.value?.scanEnabled !== false)

/**
 * 启动按钮旁的一句话：这一轮会做什么、不会做什么。
 *
 * <p>只在没有进行中 Run 时给 —— 跑起来之后由待命横幅与状态说明，页头再说一遍会与之矛盾
 * （两个开关都能在运行中改，页头那句是启动时刻的判断）。
 */
const startHint = computed(() => {
  if (active.value) return ''
  const desc = current.value
  if (!desc) return ''
  if (desc.enabled === false) return '这个平台已停用，无法启动：请先到配置页启用它'
  const scan = desc.scanEnabled !== false
  const watch = desc.watchEnabled !== false
  if (!scan && !watch) return '这个平台的「搜索岗位」和「自动回复」都关着，启动后没有可以做的事'
  if (!scan) return '这个平台没有开启「搜索岗位」：启动后不搜岗位，直接开始回复消息'
  if (!watch) return '这个平台没有开启「自动回复」：照常搜索和投递，搜索跑完就结束'
  return ''
})
// 窗口外 + 正常模式 + 还没跑：点启动不会立刻执行，而是排到窗口打开（见模板里的 warn 块）。
// 演练模式不发消息，本来就是窗口外唯一能立刻验证流程的方式，所以不提示。
const dutyWarning = computed(() =>
  duty.value && !duty.value.open && mode.value === 'NORMAL' && !active.value)

// 分钟数说成人话：隔夜的任务是「13 小时 20 分钟」，不是「800 分钟」
function waitText(minutes: number) {
  const total = Math.max(0, Math.round(minutes))
  const h = Math.floor(total / 60)
  const m = total % 60
  if (!h) return `${m} 分钟`
  return m ? `${h} 小时 ${m} 分钟` : `${h} 小时`
}
// 说明块跟随「当前生效的模式」：没启动时看下拉（运行中下拉被禁用），跑起来后看 Run 自己的 mode ——
// 只读下拉的话，「上一轮就是演练、现在还在跑」这种情况会被漏掉
const dryRunVisible = computed(() => (
  (active.value ? run.value?.mode : mode.value) || 'NORMAL'
) === 'DRY_RUN')
// 计数格跟随「这一轮 Run 的模式」：跑完之后 active 变 false，但那一轮确实一条没投，仍要显示 —
const runIsDryRun = computed(() => run.value?.mode === 'DRY_RUN')

// 头部那一行：等待态说「还要等多久」，窗口内说「还能跑多久」，其余不占地方。
// 判定放这里而不是模板里，是因为它要同时看 run 状态、模式与时段三处。
const dutyLine = computed(() => {
  if (run.value?.status === 'WAITING_DUTY') {
    const minutes = duty.value?.opensInMinutes
    return minutes > 0
      ? `任务已排队，${waitText(minutes)}后自动开始（自动回复时段 ${duty.value.window}）`
      : '任务已排队，等自动回复时段开始后执行'
  }
  if (duty.value?.open && mode.value === 'NORMAL' && !dryRunVisible.value) {
    return duty.value.minutesLeft >= 0
      ? `自动回复时段 ${duty.value.window}，还能跑约 ${waitText(duty.value.minutesLeft)}`
      : `自动回复时段 ${duty.value.window}，${duty.value.hint}`
  }
  return ''
})
const reversed = computed(() => [...events.value].reverse().slice(0, 60))

// 「上次动是什么时候」：判断规则在 useActivity 里，这里只把阈值传进去
const activity = computed(() =>
  activityView(run.value, { nowMs: nowMs.value, staleAfterSeconds: staleAfter.value })
)

/**
 * 组合状态 → 标签。
 *
 * <p>两个理由不直接显示枚举：一是 `SKIPPED`/`INTERRUPTED` 这两个词本身就说不清
 * 「为什么没跑完」；二是「进行中」只有在 Run 还活着时才成立 —— 服务被强杀后
 * 库里可能还留着 RUNNING（后端重启收尾会补成 INTERRUPTED，这里再兜一层旧数据），
 * 那一行绝不能显示成还在扫。
 */
const COMBO_LABEL: Record<string, string> = {
  PENDING: '未开始',
  RUNNING: '进行中',
  DONE: '已完成',
  SKIPPED: '已跳过',
  FAILED: '失败',
  INTERRUPTED: '已中断'
}

function comboView(c: any) {
  const status = c.status === 'RUNNING' && !active.value ? 'INTERRUPTED' : c.status
  const tone = status === 'DONE' ? 'ok' : status === 'FAILED' ? 'danger' : status === 'PENDING' ? '' : 'warn'
  return { label: COMBO_LABEL[status] || status, tone }
}

// 界面上不出现裸的 DRY_RUN：那是内部枚举，不是一个能让人看懂「这意味着什么」的说法
function modeText(m: string) {
  return m === 'DRY_RUN' ? '演练模式' : '正常投递'
}

function time(at: number) {
  return new Date(at).toLocaleTimeString('zh-CN', { hour12: false })
}

// 事件里的 reason 是内部代号，界面上要变成一句能读懂的话
function stopReasonText(reason: string) {
  return ({
    QUOTA: '今天主动联系的名额已经用完',
    EVAL_LIMIT: '今天查看岗位的数量已达上限',
    COMBOS_EXHAUSTED: '所有搜索组合都跑完了',
    // 未启用扫描的平台一启动就是待命，这个原因下「扫描结束」不成立（spec 0005）
    SCAN_DISABLED: '这个平台没有开启「搜索岗位」'
  } as any)[reason] || reason
}

function rescanReasonText(reason: string) {
  return ({
    QUOTA_RAISED: '主动联系名额已调高',
    EVAL_LIMIT_RAISED: '查看岗位上限定已调高',
    NEW_DAY: '进入新的一天，搜索组合从头开始'
  } as any)[reason] || reason
}

function describe(e: { type: string; data: any }) {
  const d = e.data || {}
  switch (e.type) {
    case 'run.started':
      return `任务已启动（${modeText(d.mode)}）`
    case 'run.waiting':
      // 两种挂起来源文案必须分开：启动时就不在窗口内 vs 跑着跑着到点了。
      // 混成一句「不在值守时段」，到点挂起会被读成任务被中断
      return d.reason === 'DUTY_END'
        ? `自动回复时段已结束（${d.window}，${d.at}），任务先暂停；这一轮还没有结束`
        : `现在不在自动回复时段（${d.window}，${d.at}），任务已排队等待`
    case 'run.standby':
      // 未启用扫描的平台从未扫过，「扫描结束（…）」这句与事实不符：它只有在扫描真的
      // 中止时才成立（spec 0005 §3.5 的文案要求）
      return d.reason === 'SCAN_DISABLED'
        ? `没有开启「搜索岗位」，直接开始处理未读消息：主动联系 ${d.delivered} / 已查看 ${d.evaluated}`
        : `搜索结束（${stopReasonText(d.reason)}），继续处理未读消息：主动联系 ${d.delivered} / 已查看 ${d.evaluated}`
    case 'run.rescan':
      return `继续搜索：${rescanReasonText(d.reason)}`
    case 'run.awakened':
      return `自动回复时段已到（${d.at}），开始执行排队的任务`
    case 'combination.started':
      return `开始第 ${d.seq + 1} 个搜索组合：${d.city || '不限'} / ${d.keyword || '不限'}`
    case 'job.evaluated':
      return `${d.decision === 'PASS' ? '决定投递' : d.decision === 'AI_REJECT' ? '决定不投' : '判断失败'}：${d.jobName} @ ${d.company}${d.reason ? ' — ' + d.reason : ''}`
    case 'delivery.updated':
      return `投递结果 ${d.status}：${d.jobName}${d.failureReason ? ' — ' + d.failureReason : ''}`
    case 'unread.detected':
      // 打开 AI 模式后，一次扫描可能既没有回复也没有未命中，但有待重试/已失败的任务 ——
      // 那才是需要人看见的部分，不能因为前两个数是 0 就显示成「什么也没发生」
      if (d.dryRunWouldReply) {
        // 演练下 replied 恒为 0。显示成「回复 N」等于告诉操作者消息已经发出去了，
        // 而这正是这次要消灭的错觉。
        return `检查消息（演练）：${d.threads} 个未读，本来会回复 ${d.dryRunWouldReply} 个，没匹配到规则 ${d.unmatched} 个`
      }
      return `检查消息：${d.threads} 个未读，已回复 ${d.replied} 个，没匹配到规则 ${d.unmatched} 个`
        + (d.retried ? `，重试 ${d.retried} 个（失败 ${d.failed} 个）` : '')
    case 'circuit.opened':
      return `已自动停止：${d.kind} — ${d.message}`
    case 'run.finished':
      return `任务结束：${d.status}（主动联系 ${d.delivered}，已查看 ${d.evaluated}）`
    default:
      return e.type
  }
}

async function refresh() {
  const want = platformSlug.value
  try {
    const cur = await call('/runs/current', { query: { platform: want } })
    // 切平台时 query 已变、请求还没回：这一帧描述的是上一个平台，丢掉。
    // 不丢的话，切到智联的瞬间会先看到 BOSS 的日志，然后日志"消失"变空
    if (platformSlug.value !== want) return
    run.value = cur.run
    duty.value = cur.duty || null
    staleAfter.value = cur.staleAfterSeconds ?? 90
    combinations.value = cur.combinations || []
    if (cur.run && cur.run.id !== currentRunId) {
      currentRunId = cur.run.id
      clear()
      open(cur.run.id)
      logs.value = []
      lastLogId = 0
    }
    // 没有 Run 时也要断开：否则切到「今天还没跑过」的平台，SSE 还连着上一平台那条
    if (!cur.run && connected.value) {
      close()
      currentRunId = null
    }
    if (cur.run) await pullLogs(cur.run.id)
  } catch (e: any) {
    show(e.message, true)
  }
}

/**
 * 换平台：先把上一平台的东西全清掉，再发请求。
 *
 * <p>顺序不能反。等响应回来才清的话，界面会有一段时间挂着另一个平台的日志、组合队列与
 * 「实时连接：已连接」——那比看到空白更让人困惑：人以为切过去了，实际看的是别人。
 */
function resetForPlatformChange() {
  close()
  clear()
  run.value = null
  duty.value = null
  combinations.value = []
  logs.value = []
  lastLogId = 0
  currentRunId = null
}

async function pullLogs(runId: number) {
  const items = await call(`/runs/${runId}/logs`, { query: { afterId: lastLogId, limit: 200 } })
  if (items?.length) {
    logs.value = [...logs.value, ...items].slice(-400)
    lastLogId = items[items.length - 1].id
  }
}

async function start() {
  busy.value = true
  try {
    await call('/runs', {
      method: 'POST',
      query: { platform: platformSlug.value },
      body: { mode: mode.value }
    })
    // 窗口外启动不再报错，而是挂起：toast 必须说「排到几点」，否则人会以为任务已经跑起来了
    if (duty.value && !duty.value.open && mode.value === 'NORMAL') {
      show(`任务已排队，${duty.value.hint}`)
    } else {
      show('任务已启动')
    }
    await refresh()
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function pause() {
  busy.value = true
  try {
    await call(`/runs/${run.value.id}/pause`, { method: 'POST' })
    show('已暂停')
    await refresh()
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function resume() {
  busy.value = true
  try {
    await call(`/runs/${run.value.id}/resume`, { method: 'POST' })
    show('已继续')
    await refresh()
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function cancel() {
  busy.value = true
  try {
    await call(`/runs/${run.value.id}/cancel`, { method: 'POST' })
    show('已发送停止指令')
    await refresh()
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

function clearLogs() {
  logs.value = []
  lastLogId = 0
}

onMounted(async () => {
  // 先拿到清单：没有清单就不知道该问哪个平台的 Run（缺省平台是后端配置，不是这里该猜的）
  await ensure().catch(() => {})
  await refresh()
  pollTimer = setInterval(refresh, 3000)
})

// 换平台 = 换一条流水线：上一平台的日志、事件、组合队列一律不留
watch(platformSlug, (next, prev) => {
  if (!prev || !next || next === prev) return
  resetForPlatformChange()
  refresh()
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
/* 启动提示与按钮同一行：限宽并允许折行，否则一段长提示会把「启动」挤到下一行，
   正好让「提示」和它解释的动作分家 */
.start-hint {
  max-width: 300px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--warn);
  text-align: right;
}
</style>
