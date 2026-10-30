<template>
  <div>
    <div class="page-head">
      <div>
        <h1>概览</h1>
        <p>今日进度、运行状态与三项自检灯</p>
      </div>
      <div class="row">
        <button class="btn" :disabled="busy" @click="refresh">刷新</button>
        <button v-if="run && active" class="btn danger" @click="cancel">停止任务</button>
        <button v-else class="btn primary" :disabled="busy || !status?.driverAvailable" @click="start">
          开始投递
        </button>
      </div>
    </div>

    <div class="grid cols-5" style="margin-bottom: 20px">
      <div class="metric">
        <div class="label">今日主动投递</div>
        <div class="value">{{ status?.deliveredToday ?? 0 }}<span style="font-size: 14px; color: var(--muted)"> / {{ quota }}</span></div>
        <div class="sub">配额达到即中止扫描，值守继续</div>
      </div>
      <!-- 与左边分开显示：一个是我们主动外呼，一个是对方先开口后我们才发简历。
           合成一个数字会让人以为今天被动的那些也吃掉了配额 -->
      <div class="metric">
        <div class="label">值守发简历</div>
        <div class="value">{{ status?.resumeOnRequestToday ?? 0 }}</div>
        <div class="sub">应对方索要而发，不占投递配额</div>
      </div>
      <div class="metric">
        <div class="label">今日评估</div>
        <div class="value">{{ status?.evaluatedToday ?? 0 }}<span style="font-size: 14px; color: var(--muted)"> / {{ evalLimit }}</span></div>
        <div class="sub">评估上限管成本与暴露</div>
      </div>
      <div class="metric">
        <div class="label">本次运行</div>
        <div class="value" style="font-size: 19px">{{ runStatusText }}</div>
        <div class="sub">{{ run?.terminalReason || (active ? '进行中' : '尚未启动') }}</div>
      </div>
      <div class="metric">
        <div class="label">字典条目</div>
        <div class="value">{{ status?.dictionaryTotal ?? 0 }}</div>
        <div class="sub">城市 / 行业 / 薪资等 code</div>
      </div>
    </div>

    <div class="grid cols-2">
      <div class="card">
        <h2>自检灯</h2>
        <p class="hint">任一红灯都会阻止任务启动</p>
        <div class="stack">
          <div class="row" style="justify-content: space-between">
            <span>patchright driver</span>
            <span class="tag" :class="status?.driverAvailable ? 'ok' : 'danger'">
              {{ status?.driverAvailable ? '就绪' : '不可用' }}
            </span>
          </div>
          <div class="row" style="justify-content: space-between">
            <span>浏览器会话</span>
            <span class="tag" :class="status?.browserReady ? 'ok' : ''">
              {{ status?.browserReady ? '已启动' : '未启动（首次任务时拉起）' }}
            </span>
          </div>
          <div class="row" style="justify-content: space-between">
            <span>任务</span>
            <span class="tag" :class="active ? runView.tone : ''">
              {{ run ? runView.label : '空闲' }}
            </span>
          </div>
          <div v-if="status && !status.driverAvailable" class="hint-inline" style="color: var(--danger)">
            {{ status.driverMessage }}
          </div>
        </div>
      </div>

      <div class="card">
        <h2>组合队列</h2>
        <p class="hint">城市 × 关键词，按顺序串行执行</p>
        <div v-if="!combinations.length" class="empty">还没有任务记录</div>
        <table v-else>
          <thead>
            <tr>
              <th style="width: 40px">#</th>
              <th>城市 / 关键词</th>
              <th style="width: 92px">状态</th>
              <th style="width: 92px">主动投递</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in combinations.slice(0, 8)" :key="c.id">
              <td class="num">{{ c.seq + 1 }}</td>
              <td>{{ c.city || '不限' }} / {{ c.keyword || '不限' }}</td>
              <td>
                <span class="tag" :class="comboClass(c.status)">{{ comboText(c.status) }}</span>
              </td>
              <td class="num">{{ c.delivered }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="card">
      <h2>最近一次运行</h2>
      <p class="hint">扫描中止（配额 / 评估上限 / 组合耗尽）只停下找岗位，值守继续；熔断与异常才终止</p>
      <div v-if="!run" class="empty">还没有运行记录</div>
      <div v-else class="grid cols-3">
        <div>
          <div class="hint-inline">状态</div>
          <div>
            {{ runStatusText }}
            <span class="tag" :class="run.mode === 'DRY_RUN' ? 'warn' : (run.status === 'COMPLETED' ? 'ok' : '')">
              {{ modeText(run.mode) }}
            </span>
          </div>
        </div>
        <div>
          <div class="hint-inline">主动投递 / 评估</div>
          <!-- 演练那条 Run 一条都没投，显示 0 会被读成「投了但全失败」 -->
          <div class="num">{{ run.mode === 'DRY_RUN' ? '—' : run.deliveredCount }} / {{ run.evaluatedCount }}</div>
        </div>
        <div>
          <div class="hint-inline">开始时间</div>
          <div>{{ run.startedAt || '—' }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
const { call } = useApi()
const { show } = useToast()

const status = ref<any>(null)
const run = ref<any>(null)
const combinations = ref<any[]>([])
const cfg = ref<any>({ dailyDeliveryQuota: 30, dailyEvalLimit: 150 })
const busy = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

// 状态 → 标签/色调/是否活跃，一处定义（与任务页共用，避免加一个状态要改三处）
const runView = computed(() => runStatusView(run.value?.status))
const active = computed(() => runView.value.active)
const quota = computed(() => cfg.value?.dailyDeliveryQuota ?? 30)
const evalLimit = computed(() => cfg.value?.dailyEvalLimit ?? 150)

const runStatusText = computed(() => (run.value ? runView.value.label : '—'))

function comboClass(s: string) {
  return s === 'DONE' ? 'ok' : s === 'RUNNING' ? 'warn' : s === 'FAILED' ? 'danger' : ''
}

function comboText(s: string) {
  return ({ PENDING: '等待', RUNNING: '执行中', DONE: '完成', SKIPPED: '跳过', FAILED: '失败' } as any)[s] || s
}

// 界面上不出现裸的 DRY_RUN：那是内部枚举，看不出「这意味着什么」
function modeText(m: string) {
  return m === 'DRY_RUN' ? '演练模式' : '正常投递'
}

async function refresh() {
  busy.value = true
  try {
    const [s, cur] = await Promise.all([call('/system/status'), call('/runs/current')])
    status.value = s
    run.value = cur.run
    combinations.value = cur.combinations || []
    // 配额与评估上限是「当前平台」的配置：平台随装配期配置变化，不能写死 boss
    cfg.value = await call(`/config/platform/${s?.platform || 'boss'}`)
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function start() {
  busy.value = true
  try {
    await call('/runs', { method: 'POST', body: { mode: 'NORMAL' } })
    show('任务已启动')
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

onMounted(() => {
  refresh()
  timer = setInterval(refresh, 5000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>
