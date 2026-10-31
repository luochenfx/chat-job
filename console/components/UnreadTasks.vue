<template>
  <div class="card">
    <div class="row" style="justify-content: space-between; align-items: flex-start">
      <div>
        <h2>未读任务与告警</h2>
        <p class="hint">
          点开会话就清红点，平台没有「标回未读」的能力。所以每条未读先在任务表落一行，
          处理失败按 1 / 5 / 15 分钟退避重试，3 次后放弃
        </p>
      </div>
      <button class="btn sm" :disabled="busy" @click="load">刷新</button>
    </div>

    <!-- 告警提示条：四档状态各自上色。判定在后端做，这里只按 state 上色，
         不自己算比例 —— 否则「样本不足」和「未超阈值」很容易被写成同一个绿色 -->
    <div v-if="stats" class="alert" :class="alertClass">
      <span>{{ alertText }}</span>
      <NuxtLink v-if="stats.state === 'ALERT'" to="/config" class="alert-link">查看原因分布</NuxtLink>
    </div>

    <div class="grid cols-3" style="margin-top: 14px">
      <div class="metric">
        <div class="metric-label">待处理 · 合计</div>
        <div class="metric-value">{{ counts.pending }}</div>
        <div class="metric-hint">已落任务，等本轮或下一轮处理</div>
      </div>
      <div class="metric">
        <div class="metric-label">重试中 · 合计</div>
        <div class="metric-value">{{ counts.retrying }}</div>
        <div class="metric-hint">处理失败，已排好下次重试时间</div>
      </div>
      <div class="metric">
        <div class="metric-label">已放弃 · 合计</div>
        <div class="metric-value">{{ counts.gaveUp }}</div>
        <div class="metric-hint">重试 3 次仍失败，不会再自动处理</div>
      </div>
    </div>

    <!-- 合计与明细一起给：只说「合计 8」的话，人不知道这 8 条属于哪个平台，
         也就不知道是哪条流水线在漏消息 -->
    <div v-if="platformRows.length" class="plat-rows">
      <div v-for="row in platformRows" :key="row.platform" class="plat-row">
        <span class="plat-name">{{ row.label }}</span>
        <span class="plat-cell">待处理 <b>{{ row.pending }}</b></span>
        <span class="plat-cell">重试 <b>{{ row.retrying }}</b></span>
        <span class="plat-cell">已放弃 <b>{{ row.gaveUp }}</b></span>
      </div>
    </div>

    <!-- 默认视图只有计数，不出现任何按钮：GAVE_UP 多数是真的不该回（同名混淆、列表已变、
         对方是骚扰），把「再试一次」摆在明面上会诱导人反复点，反而制造错发风险 -->
    <button class="btn sm" style="margin-top: 16px" @click="toggleDetail">
      {{ showDetail ? '收起明细' : '查看明细' }}
    </button>

    <div v-if="showDetail">
      <div v-if="!tasks.length" class="empty">近 7 天没有任务记录</div>
      <table v-else>
        <thead>
          <tr>
            <th style="width: 96px">平台</th>
            <th>会话</th>
            <th style="width: 90px">状态</th>
            <th style="width: 110px">原因</th>
            <th style="width: 90px">尝试</th>
            <th style="width: 130px">更新时间</th>
            <th style="width: 60px"></th>
          </tr>
        </thead>
        <tbody>
          <template v-for="t in tasks" :key="t.id">
            <tr class="task-row" @click="toggleExpand(t.id)">
              <td class="hint-inline">{{ labelOf(t.platform) }}</td>
              <td>{{ t.conversation }}</td>
              <td>
                <span class="tag" :class="statusClass(t.status)">{{ statusText(t.status) }}</span>
              </td>
              <td class="hint-inline">{{ t.lastAction || '—' }}</td>
              <td class="hint-inline">{{ t.attemptCount }} / {{ t.maxAttempts }}</td>
              <td class="hint-inline">{{ fmt(t.updatedAt) }}</td>
              <td class="hint-inline">{{ expanded === t.id ? '收起' : '详情' }}</td>
            </tr>
            <tr v-if="expanded === t.id" class="detail-row">
              <td colspan="7">
                <div class="stack">
                  <div v-if="t.lastReasonCode" class="hint-inline">
                    原因码：<code>{{ t.lastReasonCode }}</code>
                  </div>
                  <div v-if="t.lastError" class="hint-inline">失败信息：{{ t.lastError }}</div>
                  <div v-if="t.nextRetryAt" class="hint-inline">
                    下次重试：{{ fmt(t.nextRetryAt) }}
                  </div>
                  <div v-if="t.status === 'GAVE_UP'" class="row" style="gap: 10px">
                    <button class="btn sm danger" :disabled="busy" @click="retry(t)">再试一次</button>
                    <span class="hint-inline">会重置为 3 次机会，下一轮值守执行</span>
                  </div>
                  <div v-else class="hint-inline">只有「已放弃」的任务可以手动重试</div>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
const { call } = useApi()
const { show } = useToast()
const { platforms, ensure } = usePlatform()

const stats = ref<any>(null)
const counts = reactive<any>({ pending: 0, retrying: 0, gaveUp: 0 })
const tasks = ref<any[]>([])
const busy = ref(false)
const showDetail = ref(false)
const expanded = ref<number | null>(null)

function labelOf(platform?: string) {
  if (!platform) return '—'
  return platforms.value.find((p) => p.platform === platform)?.label || platform
}

/**
 * 按平台的计数明细。后端无条件覆盖清单里的每个平台（今天没有任务时也给 0），
 * 所以这里不会因为某个平台「没出现」而漏掉它 —— 0 是真实发生的 0。
 */
const platformRows = computed(() => {
  const byPlatform: Record<string, any> = counts.byPlatform || {}
  return Object.entries(byPlatform).map(([platform, v]) => ({
    platform,
    label: labelOf(platform),
    pending: (v as any).pending ?? 0,
    retrying: (v as any).retrying ?? 0,
    gaveUp: (v as any).gaveUp ?? 0
  }))
})

/**
 * 告警文案。四档都要有话说 —— 尤其 DISABLED 与 INSUFFICIENT：
 * 一个「我关了它」、一个「还没法判断」，把它们显示成正常就是这次要根治的病。
 */
const alertText = computed(() => {
  const s = stats.value
  if (!s) return ''
  const joined = `近 7 天 AI 参与 ${s.total} 次，主动不回 ${s.skipped} 次`
  switch (s.state) {
    case 'DISABLED':
      return `告警已关闭（阈值 0）。${joined}`
    case 'INSUFFICIENT':
      return `近 7 天 AI 参与 ${s.total} 次，样本不足（需 ${s.minSample} 次），未判定`
    case 'ALERT':
      return `${joined}（${s.pct}%），超过阈值 ${s.thresholdPct}%`
    default:
      return `${joined}（${s.pct}%），阈值 ${s.thresholdPct}%`
  }
})

const alertClass = computed(() => {
  switch (stats.value?.state) {
    case 'ALERT':
      return 'warn'
    case 'OK':
      return 'ok'
    default:
      // DISABLED / INSUFFICIENT 都是灰底：不是坏消息，但也绝不是「正常」
      return 'muted'
  }
})

function statusText(status: string) {
  return (
    { PENDING: '待处理', PROCESSING: '处理中', FAILED: '重试中', GAVE_UP: '已放弃', DONE: '已完成' }[
      status
    ] || status
  )
}

function statusClass(status: string) {
  if (status === 'GAVE_UP') return 'danger'
  if (status === 'FAILED') return 'warn'
  if (status === 'DONE') return 'ok'
  return ''
}

function fmt(value?: string) {
  return value ? value.replace('T', ' ').slice(5, 16) : '—'
}

function toggleDetail() {
  showDetail.value = !showDetail.value
  if (!showDetail.value) expanded.value = null
}

function toggleExpand(id: number) {
  expanded.value = expanded.value === id ? null : id
}

async function load() {
  busy.value = true
  try {
    await ensure().catch(() => {})
    const [s, t] = await Promise.all([
      call('/watcher/stats', { query: { days: 7 } }),
      call('/watcher/unread-tasks', { query: { days: 7, limit: 50 } })
    ])
    stats.value = s || null
    // byPlatform 一起塞进 counts：合计与明细来自同一次响应，不会出现两者对不上
    Object.assign(counts, t?.counts || {})
    tasks.value = t?.tasks || []
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function retry(t: any) {
  if (!confirm(`将重新给「${t.conversation}」3 次机会，可能向对方发出消息。继续？`)) return
  busy.value = true
  try {
    await call(`/watcher/unread-tasks/${t.id}/retry`, { method: 'POST' })
    show('已重置为待处理，下一轮值守执行')
    await load()
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.alert {
  margin-top: 14px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.alert.muted {
  background: var(--surface-2);
  color: var(--muted);
}

.alert.ok {
  background: color-mix(in srgb, var(--ok) 9%, var(--surface));
  border-color: color-mix(in srgb, var(--ok) 26%, var(--line));
  color: var(--ok);
}

.alert.warn {
  background: color-mix(in srgb, var(--warn) 12%, var(--surface));
  border-color: color-mix(in srgb, var(--warn) 32%, var(--line));
  color: var(--warn);
}

.alert-link {
  flex-shrink: 0;
  color: inherit;
  text-decoration: underline;
  font-size: 12px;
}

.metric {
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface-2);
}

.metric-label {
  font-size: 12px;
  color: var(--muted);
}

.metric-value {
  font-size: 24px;
  font-weight: 600;
  color: var(--ink);
  line-height: 1.3;
}

.metric-hint {
  font-size: 11.5px;
  color: var(--muted);
  margin-top: 2px;
}

.task-row {
  cursor: pointer;
}

.task-row:hover {
  background: var(--surface-2);
}

.detail-row td {
  background: var(--surface-2);
}

/* 按平台明细：三行字，比再摆三张卡更省地方，也不会让人以为那是另一组合计 */
.plat-rows {
  margin-top: 12px;
  border-top: 1px solid var(--line);
  padding-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.plat-row {
  display: flex;
  align-items: baseline;
  gap: 18px;
  font-size: 12.5px;
  color: var(--muted);
}

.plat-name {
  min-width: 96px;
  color: var(--ink-2);
}

.plat-cell b {
  color: var(--ink);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
</style>
