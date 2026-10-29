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
        <div class="metric-label">待处理</div>
        <div class="metric-value">{{ counts.pending }}</div>
        <div class="metric-hint">已落任务，等本轮或下一轮处理</div>
      </div>
      <div class="metric">
        <div class="metric-label">重试中</div>
        <div class="metric-value">{{ counts.retrying }}</div>
        <div class="metric-hint">处理失败，已排好下次重试时间</div>
      </div>
      <div class="metric">
        <div class="metric-label">已放弃</div>
        <div class="metric-value">{{ counts.gaveUp }}</div>
        <div class="metric-hint">重试 3 次仍失败，不会再自动处理</div>
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
              <td colspan="6">
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

const stats = ref<any>(null)
const counts = reactive<any>({ pending: 0, retrying: 0, gaveUp: 0 })
const tasks = ref<any[]>([])
const busy = ref(false)
const showDetail = ref(false)
const expanded = ref<number | null>(null)

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
    const [s, t] = await Promise.all([
      call('/watcher/stats', { query: { days: 7 } }),
      call('/watcher/unread-tasks', { query: { days: 7, limit: 50 } })
    ])
    stats.value = s || null
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
</style>
