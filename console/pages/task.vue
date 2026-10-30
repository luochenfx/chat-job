<template>
  <div>
    <div class="page-head">
      <div>
        <h1>任务</h1>
        <p>一次启动 = 一段值守期：扫描找岗位、值守回消息；扫描可以停，值守不会跟着停</p>
        <!-- 时段只决定「什么时候开始跑」，不再是启动门禁：窗口内说到点时刻，等待态说还要等多久 -->
        <p v-if="dutyLine" class="hint" style="margin-bottom: 0">{{ dutyLine }}</p>
      </div>
      <div class="row">
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
            <button class="btn" :disabled="busy" @click="pause">暂停</button>
            <button class="btn danger" :disabled="busy" @click="cancel">停止</button>
          </template>
        </template>
      </div>
    </div>

    <!-- 待命横幅：配额/评估/组合耗尽只是「今天不再找岗位」，不是「今天不再回消息」。
         没有这条横幅，界面只剩一个黄标签，人会以为任务已经半死了 -->
    <div v-if="run?.status === 'STANDBY'" class="notice">
      <div class="notice-title">值守待命：扫描已结束，未读消息照常回复</div>
      <p class="notice-body">{{ run.message }}</p>
      <p class="notice-foot">
        配额或评估上限调高、或跨过零点进入新的一天，会自动恢复扫描 —— 不需要停止重来。
        要真正结束这一轮，点「停止」。
      </p>
    </div>

    <!-- 窗口外提示：启动按钮照常可点，这一轮不会立刻执行，而是排到窗口打开时自动开始 -->
    <div v-if="dutyWarning" class="notice warn">
      <div class="notice-title">现在不在值守时段（{{ duty.window }}）</div>
      <p class="notice-body">
        点启动不会立刻执行：这条任务会挂在队列里等窗口打开，{{ duty.hint }}。
        挂起期间不打开浏览器、不发消息、也不动未读红点，期间可以随时「取消等待」。
        <template v-if="duty.opensInMinutes > 0">预计还要等 {{ waitText(duty.opensInMinutes) }}。</template>
      </p>
      <p class="notice-foot">
        想在窗口外立刻验证流程，切到<b>演练模式</b>：它不受时段限制，代价是不发任何消息。
      </p>
    </div>

    <!-- 演练说明：启动前与运行中都必须可见。运行中尤其重要 ——
         那时「投递 0」是正确结果，但看上去像卡住，不解释就一定会被误读成失败。 -->
    <div v-if="dryRunVisible" class="notice">
      <div class="notice-title">演练模式：不会发出任何消息</div>
      <p class="notice-body">
        招呼语和值守回复都不会发出去，对方收不到任何东西。但演练验证的是流程本身，不是「只看不动」。
      </p>
      <ul class="notice-list">
        <li>会真的浏览平台：打开搜索、滚动卡片、抓取岗位详情，与正常模式一样。</li>
        <li>会真的调用 AI 判定并写招呼语，消耗 token，并计入当天的评估上限。</li>
        <li>值守会真的点开会话读消息。<b>点开会话即清红点，平台无法标回未读</b> —— 这些消息之后不会再出现在未读列表里，正常运行也不会回复它们。</li>
        <li>不受值守时段限制，任何时间都能跑；当天的投递配额与评估上限照常生效。</li>
      </ul>
      <p class="notice-foot">
        演练的结论只落在运行日志里（「本会投递」「本会回复」），控制台计数不会因此增加，也不会显示成已发送。
      </p>
    </div>

    <div class="card">
      <div class="row wrap" style="justify-content: space-between; gap: 16px">
        <div>
          <h2>当前运行</h2>
          <p class="hint" style="margin-bottom: 0">
            <template v-if="run">
              #{{ run.id }} ·
              <span class="tag" :class="run.mode === 'DRY_RUN' ? 'warn' : ''">{{ modeText(run.mode) }}</span>
              · {{ run.startedAt || '未开始' }}
            </template>
            <template v-else>还没有任务</template>
          </p>
        </div>
        <div class="row" style="gap: 26px">
          <div>
            <div class="hint-inline">状态</div>
            <span class="tag" :class="runView.tone">{{ runView.label }}</span>
          </div>
          <div>
            <div class="hint-inline">断点</div>
            <div class="num">组合 {{ (run?.combinationIndex ?? 0) + 1 }} / 卡片 {{ run?.lastCardIndex ?? 0 }}</div>
          </div>
          <div>
            <div class="hint-inline">投递 / 评估</div>
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
      <div v-if="run && activityLine" class="row wrap" style="margin-top: 14px; gap: 10px; align-items: baseline">
        <span class="hint-inline">当前动作</span>
        <span>{{ run.currentAction || '—' }}</span>
        <span class="tag" :class="stale ? 'danger' : (runView.judgedIdle ? 'ok' : '')">{{ activityLine }}</span>
      </div>
    </div>

    <div class="grid cols-2">
      <div class="card">
        <h2>组合队列</h2>
        <p class="hint">城市 × 关键词，串行执行；单个组合有轮数与时长双上限</p>
        <div v-if="!combinations.length" class="empty">暂无组合</div>
        <table v-else>
          <thead>
            <tr>
              <th style="width: 36px">#</th>
              <th>城市 / 关键词</th>
              <th style="width: 80px">状态</th>
              <th style="width: 60px">卡片</th>
              <th style="width: 60px">投递</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in combinations" :key="c.id">
              <td class="num">{{ c.seq + 1 }}</td>
              <td>{{ c.city || '不限' }} / {{ c.keyword || '不限' }}</td>
              <td><span class="tag" :class="comboClass(c.status)">{{ c.status }}</span></td>
              <td class="num">{{ c.cardsSeen }}</td>
              <td class="num">{{ c.delivered }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="card">
        <h2>实时事件</h2>
        <p class="hint">只推状态与计数，日志走下方独立流</p>
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
        <button class="btn sm" @click="clearLogs">清屏</button>
      </div>
      <p class="hint">落库的 run_log，保留 14 天；断线后从这里补</p>
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
const { events, connected, open, clear } = useRunStream()

const run = ref<any>(null)
const combinations = ref<any[]>([])
const logs = ref<any[]>([])
const mode = ref('NORMAL')
const busy = ref(false)
// 服务端算好的时段状态（跨天/全天三种写法的判定只在后端做一次，界面不重复实现）
const duty = ref<any>(null)
/** 无响应阈值，后端算好下发；前端只做比较 */
const staleAfter = ref(90)
/** 1 秒一跳，只为让「12 秒前有动作」这个数字在两次轮询之间也能走 */
const nowMs = ref(Date.now())
let lastLogId = 0
let pollTimer: ReturnType<typeof setInterval> | null = null
let tickTimer: ReturnType<typeof setInterval> | null = null
let currentRunId: number | null = null

// 状态 → 标签/色调/能不能暂停，一处定义（原来三处各写一遍，加状态必漏）
const runView = computed(() => runStatusView(run.value?.status))
const active = computed(() => runView.value.active)
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
      ? `已挂起，${waitText(minutes)}后自动开始（值守时段 ${duty.value.window}）`
      : '已挂起，等值守时段开始后自动执行'
  }
  if (duty.value?.open && mode.value === 'NORMAL' && !dryRunVisible.value) {
    return duty.value.minutesLeft >= 0
      ? `值守时段 ${duty.value.window}，还能跑约 ${waitText(duty.value.minutesLeft)}`
      : `值守时段 ${duty.value.window}，${duty.value.hint}`
  }
  return ''
})
const reversed = computed(() => [...events.value].reverse().slice(0, 60))

// 「上次动是什么时候」：后端只给时间戳，人话与陈旧判定在这里做（阈值本身来自后端）
const idleSeconds = computed(() => {
  const raw = run.value?.lastActivityAt
  if (!raw) return null
  const at = new Date(raw).getTime()
  if (Number.isNaN(at)) return null
  return Math.max(0, Math.round((nowMs.value - at) / 1000))
})
const stale = computed(() =>
  runView.value.judgedIdle && idleSeconds.value != null && idleSeconds.value > staleAfter.value)
const activityLine = computed(() => {
  const s = run.value?.status
  if (!s) return ''
  // 暂停与挂起本来就该安静，做陈旧判定只会误报
  if (s === 'PAUSED') return '已暂停：扫描与值守都停'
  if (s === 'WAITING_DUTY') return '已挂起：值守期没有结束，窗口打开会自动继续'
  if (!runView.value.active) return `已结束：${runView.value.label}`
  if (idleSeconds.value == null) return '刚启动，还没有动作'
  if (stale.value) {
    const minutes = Math.max(1, Math.floor(idleSeconds.value / 60))
    return `⚠️ 无响应 ${minutes} 分钟（阈值 ${staleAfter.value} 秒）`
  }
  return `${runView.value.label} · ${idleText(idleSeconds.value)}前有动作`
})

// 秒级数字对人没意义：12 秒 / 3 分钟 / 1 小时 5 分钟
function idleText(seconds: number) {
  if (seconds < 60) return `${seconds} 秒`
  const total = Math.floor(seconds / 60)
  const h = Math.floor(total / 60)
  const m = total % 60
  if (!h) return `${m} 分钟`
  return m ? `${h} 小时 ${m} 分钟` : `${h} 小时`
}

function comboClass(s: string) {
  return s === 'DONE' ? 'ok' : s === 'RUNNING' ? 'warn' : s === 'FAILED' ? 'danger' : ''
}

// 界面上不出现裸的 DRY_RUN：那是内部枚举，不是一个能让人看懂「这意味着什么」的说法
function modeText(m: string) {
  return m === 'DRY_RUN' ? '演练模式' : '正常投递'
}

function time(at: number) {
  return new Date(at).toLocaleTimeString('zh-CN', { hour12: false })
}

// 事件里的 reason 是枚举，界面上要变成一句人话
function stopReasonText(reason: string) {
  return ({ QUOTA: '今日投递配额已用完', EVAL_LIMIT: '今日评估已达上限', COMBOS_EXHAUSTED: '搜索组合已扫完' } as any)[reason] || reason
}

function rescanReasonText(reason: string) {
  return ({ QUOTA_RAISED: '配额已调高', EVAL_LIMIT_RAISED: '评估上限已调高', NEW_DAY: '进入新的一天，组合队列已复位' } as any)[reason] || reason
}

function describe(e: { type: string; data: any }) {
  const d = e.data || {}
  switch (e.type) {
    case 'run.started':
      return `任务启动（${modeText(d.mode)}）`
    case 'run.waiting':
      // 两种挂起来源文案必须分开：启动时就不在窗口内 vs 跑着跑着到点了。
      // 混成一句「不在值守时段」，到点挂起会被读成任务被中断
      return d.reason === 'DUTY_END'
        ? `值守时段已结束（${d.window}，${d.at}），已挂起；值守期未结束`
        : `不在值守时段（${d.window}，${d.at}），已挂起等待`
    case 'run.standby':
      return `扫描结束（${stopReasonText(d.reason)}），转入值守待命：投递 ${d.delivered} / 评估 ${d.evaluated}`
    case 'run.rescan':
      return `恢复扫描：${rescanReasonText(d.reason)}`
    case 'run.awakened':
      return `值守时段已到（${d.at}），开始执行挂起的任务`
    case 'combination.started':
      return `开始组合 #${d.seq + 1} ${d.city || '不限'} / ${d.keyword || '不限'}`
    case 'job.evaluated':
      return `${d.decision === 'PASS' ? '通过' : d.decision === 'AI_REJECT' ? '不投' : '判定失败'}：${d.jobName} @ ${d.company}${d.reason ? ' — ' + d.reason : ''}`
    case 'delivery.updated':
      return `投递 ${d.status}：${d.jobName}${d.failureReason ? ' — ' + d.failureReason : ''}`
    case 'unread.detected':
      // 打开 AI 模式后，一次扫描可能既没有回复也没有未命中，但有待重试/已失败的任务 ——
      // 那才是需要人看见的部分，不能因为前两个数是 0 就显示成「什么也没发生」
      if (d.dryRunWouldReply) {
        // 演练下 replied 恒为 0。显示成「回复 N」等于告诉操作者消息已经发出去了，
        // 而这正是这次要消灭的错觉。
        return `值守（演练）：${d.threads} 个未读，本会回复 ${d.dryRunWouldReply}，未命中 ${d.unmatched}`
      }
      return `值守：${d.threads} 个未读，回复 ${d.replied}，未命中 ${d.unmatched}`
        + (d.retried ? `，重试 ${d.retried}（失败 ${d.failed}）` : '')
    case 'circuit.opened':
      return `熔断：${d.kind} — ${d.message}`
    case 'run.finished':
      return `任务结束：${d.status}（投递 ${d.delivered}，评估 ${d.evaluated}）`
    default:
      return e.type
  }
}

async function refresh() {
  try {
    const cur = await call('/runs/current')
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
    if (cur.run) await pullLogs(cur.run.id)
  } catch (e: any) {
    show(e.message, true)
  }
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
    await call('/runs', { method: 'POST', body: { mode: mode.value } })
    // 窗口外启动不再报错，而是挂起：toast 必须说「排到几点」，否则人会以为任务已经跑起来了
    if (duty.value && !duty.value.open && mode.value === 'NORMAL') {
      show(`已挂起，${duty.value.hint}`)
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

onMounted(() => {
  refresh()
  pollTimer = setInterval(refresh, 3000)
  // 单独一跳：轮询是 3 秒一次，但「12 秒前有动作」这个数字要每秒都在走
  tickTimer = setInterval(() => { nowMs.value = Date.now() }, 1000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
  if (tickTimer) clearInterval(tickTimer)
})
</script>
