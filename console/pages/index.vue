<template>
  <div>
    <div class="page-head">
      <div>
        <h1>概览</h1>
        <p>今日进度与各平台运行状态，一张卡一个平台</p>
      </div>
      <button class="btn" :disabled="busy" @click="refresh">刷新</button>
    </div>

    <!--
      全局条：与平台无关的那几项（driver、浏览器渠道、字典、服务器时间）。
      它们只有一个值，放进任何一张平台卡都会暗示「这张卡的问题只是这个平台的」。
      driver 不可用时整条变红 —— 那才是「两个平台都跑不起来」。
    -->
    <div class="gbar" :class="{ bad: global && global.driverAvailable === false }">
      <div class="gbar-main">
        <span class="gbar-item">
          patchright
          <b v-if="global">{{ global.driverAvailable ? '就绪' : '不可用' }}</b>
        </span>
        <span class="gbar-item">浏览器 <b>{{ global?.browserLabel || global?.browserChannel || '—' }}</b></span>
        <span class="gbar-item">字典 <b>{{ global?.dictionaryTotal ?? 0 }}</b> 条</span>
        <span class="gbar-item mono">{{ global?.serverTime || '—' }}</span>
      </div>
      <div v-if="global && !global.driverAvailable" class="gbar-msg">
        两个平台都无法启动：{{ global.driverMessage || 'driver 不可用' }}
      </div>
    </div>

    <div v-if="!cards.length" class="card">
      <div class="empty">正在读取平台清单…</div>
    </div>

    <!-- 卡片顺序固定按平台清单，不按状态排序：会跳位置的话，人无法建立空间记忆 -->
    <div v-else class="pcards">
      <div v-for="c in cards" :key="c.platform" class="pcard" :class="{ off: c.enabled === false }">
        <div class="pcard-head">
          <div>
            <div class="pcard-title">
              {{ c.label }}
              <!--
                徽标紧贴平台名：概览要回答的第一个问题是「这个平台现在在做什么」，只报数字答不了。
                只值守平台的「今日主动投递」恒为 0，没有这行标注会被读成投递失败（spec 0005 R5）。
              -->
              <span
                v-if="scopeBadge(c).label"
                class="pcard-badge"
                :class="scopeBadge(c).tone"
              >{{ scopeBadge(c).label }}</span>
            </div>
            <div class="pcard-sub">
              <template v-if="c.run">
                #{{ c.run.id }} · {{ modeText(c.run.mode) }} · {{ shortTime(c.run.startedAt) }}
              </template>
              <!-- 0 是真实发生的 0，不拿 — 盖掉；— 只留给「这一轮不可能产生该数字」 -->
              <template v-else>今天还没有跑过</template>
            </div>
          </div>
          <div class="pcard-tags">
            <span v-if="c.enabled === false" class="tag">已停用</span>
            <span class="tag" :class="viewOf(c).tone">{{ viewOf(c).label }}</span>
          </div>
        </div>

        <div class="pstats">
          <!-- 与下一行分开：一个是我们主动外呼，一个是对方先开口后我们才发简历。
               合成一个数字会让人以为今天被动的那些也消耗了配额（ADR-0004） -->
          <div class="pstat">
            <div class="pstat-label">今日主动投递</div>
            <!-- 未启用扫描时这个数字不可能产生：显示 0 与「投了但全失败」在视觉上是同一件事，
                 用 — 并在 title 里说明原因，读者不必回配置页确认 -->
            <div class="pstat-value" :title="scanOff(c) ? '该平台未启用扫描，今日不会产生主动投递' : ''">
              <template v-if="scanOff(c)">—</template>
              <template v-else>
                {{ c.delivered ?? 0 }}<span class="pstat-den"> / {{ c.quota ?? '—' }}</span>
              </template>
            </div>
          </div>
          <div class="pstat">
            <div class="pstat-label">值守发简历</div>
            <div class="pstat-value">{{ c.resumeOnRequest ?? 0 }}</div>
          </div>
          <div class="pstat">
            <div class="pstat-label">今日评估</div>
            <!-- 与主动投递同一判据：评估也只在扫描时发生 -->
            <div class="pstat-value" :title="scanOff(c) ? '该平台未启用扫描，今日不会产生投递判定' : ''">
              <template v-if="scanOff(c)">—</template>
              <template v-else>
                {{ c.evaluated ?? 0 }}<span class="pstat-den"> / {{ c.evalLimit ?? '—' }}</span>
              </template>
            </div>
          </div>
        </div>

        <div class="prows">
          <div class="prow">
            <span class="prow-key">当前动作</span>
            <!-- 单行 + 省略：动作文案里带岗位名与公司名，能长到折成两行；
                 两张卡并排时一旦折行，下面的「浏览器 / 组合」就跟着错位，扫视就废了 -->
            <span class="prow-val prow-val-action">
              <span class="prow-action" :title="activityOf(c).action || ''">
                {{ activityOf(c).action || (c.run ? '—' : '无') }}
              </span>
              <!-- 已结束的 Run 不再说「多久没动」，但它怎么结束的要说清楚：
                   手动停止 → 已结束：已取消；进程没了 → 最后一句动作 + 疑似异常退出 -->
              <span v-if="activityOf(c).text" class="tag" :class="activityOf(c).tone">
                {{ activityOf(c).text }}
              </span>
            </span>
          </div>
          <div class="prow">
            <span class="prow-key">浏览器</span>
            <span class="prow-val">
              <span class="tag" :class="c.browserReady ? 'ok' : ''">
                {{ c.browserReady ? '已启动' : '未启动' }}
              </span>
            </span>
          </div>
          <div class="prow">
            <span class="prow-key">组合</span>
            <span class="prow-val num">{{ comboText(c) }}</span>
          </div>
        </div>

        <div class="pcard-foot">
          <button
            class="btn"
            :class="actionOf(c).cls"
            :disabled="busy || actionOf(c).disabled"
            :title="actionOf(c).reason"
            @click="runAction(c)"
          >
            {{ actionOf(c).text }}
          </button>
          <NuxtLink class="pcard-link" :to="{ path: '/task', query: { platform: c.platform } }">
            查看任务 →
          </NuxtLink>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
const { call } = useApi()
const { show } = useToast()
const { ensure } = usePlatform()
const nowMs = useTickClock()

const data = ref<any>(null)
const busy = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const global = computed<any>(() => data.value?.global || null)
const cards = computed<any[]>(() => data.value?.platforms || [])

// 状态 → 标签/色调，一处定义（与任务页共用）
function viewOf(c: any) {
  return runStatusView(c.run?.status)
}

function activityOf(c: any) {
  // 概览卡不传阈值：卡头已有状态标签，卡内只需要「多久没动了」这一句
  return activityView(c.run, { nowMs: nowMs.value, withLabel: false })
}

function modeText(m?: string) {
  return m === 'DRY_RUN' ? '演练模式' : '正常投递'
}

function shortTime(at?: string) {
  return at ? at.replace('T', ' ').slice(5, 16) : '—'
}

/** 组合进度：只有跑过才谈得上「第几个」 */
function comboText(c: any) {
  if (!c.run) return '—'
  const total = c.run.combinationTotal ?? 0
  // total 为 0 说明这一轮没有组合（未启用扫描的平台）：此时「第 1 个」是凭 index 默认值算出来的
  // 假信息，指向一个不存在的组合
  if (!total) return '—'
  return `${(c.run.combinationIndex ?? 0) + 1} / ${total}`
}

/** 该平台是否关掉了扫描。两个统计数字与组合进度都按它切换显示口径 */
function scanOff(c: any) {
  return c.scanEnabled === false
}

/**
 * 平台名旁的开关徽标，一句话说清「这个平台启动后做什么」。
 *
 * <p>「已停用」不在这里标 —— 右侧标签区已经有一个，重复标会稀释两个徽标各自的含义。
 * 扫描与值守都关的组合后端拒绝保存，正常不会出现；真出现（比如直接改库）也不该沉默，
 * 用错误色标出来比显示成「正常运行」诚实。
 */
function scopeBadge(c: any) {
  if (c.enabled === false) return { label: '', tone: '' }
  const scan = c.scanEnabled !== false
  const watch = c.watchEnabled !== false
  if (!scan && !watch) return { label: '扫描与值守都关', tone: 'danger' }
  if (!scan) return { label: '仅值守', tone: 'warn' }
  if (!watch) return { label: '仅扫描', tone: 'warn' }
  return { label: '', tone: '' }
}

/**
 * 一张卡同时只有一个操作按钮 —— 两个按钮并排就一定会被误点。
 *
 * <p>停止类按钮的文案带平台名（「停止 BOSS 直聘」），这是本方案防反直觉最省事的一招：
 * 两个平台同时跑时，「停止」是唯一一个点错就无法挽回的动作。
 */
function actionOf(c: any): { kind: string; text: string; cls: string; disabled: boolean; reason?: string } {
  const view = viewOf(c)
  const driverDown = global.value && global.value.driverAvailable === false
  const disabledReason = driverDown
    ? `patchright driver 不可用：${global.value?.driverMessage || ''}`
    : c.enabled === false ? '该平台已停用' : undefined

  if (!view.active) {
    return { kind: 'start', text: '启动', cls: 'primary', disabled: !!disabledReason, reason: disabledReason }
  }
  if (c.run?.status === 'WAITING_DUTY') {
    return { kind: 'cancel', text: '取消等待', cls: 'danger', disabled: false }
  }
  // 已暂停时给「继续」：卡片上不给暂停，但把「继续」也藏起来的话，这一轮就只能去任务页救
  if (c.run?.status === 'PAUSED') {
    return { kind: 'resume', text: '继续', cls: '', disabled: false }
  }
  return { kind: 'cancel', text: `停止 ${c.label}`, cls: 'danger', disabled: false }
}

async function runAction(c: any) {
  const action = actionOf(c)
  if (action.disabled) return
  busy.value = true
  try {
    if (action.kind === 'start') {
      // 卡片上固定 NORMAL：演练只在任务页选（卡片只有一行按钮，没法同时表达模式）
      await call('/runs', { method: 'POST', query: { platform: c.platform }, body: { mode: 'NORMAL' } })
      show(`${c.label} 任务已启动`)
    } else if (action.kind === 'resume') {
      await call(`/runs/${c.run.id}/resume`, { method: 'POST' })
      show(`${c.label} 已继续`)
    } else {
      const hint = c.run?.status === 'WAITING_DUTY' ? '取消等待' : '停止'
      if (!confirm(`${hint} ${c.label}？运行日志会保留，这一轮值守期将结束。`)) return
      await call(`/runs/${c.run.id}/cancel`, { method: 'POST' })
      show(`已发送${hint}指令：${c.label}`)
    }
    await refresh()
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function refresh() {
  busy.value = true
  try {
    await ensure()
    // 一次取全：分开请求「状态 + N 个当前 Run + N 份配置」之间会有先后，
    // 卡片会显示自相矛盾的组合（Run 已结束、计数还是上一轮的）
    data.value = await call('/system/overview')
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

<style scoped>
/* ---------------------------------------------------------------- 全局条 */

.gbar {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  padding: 12px 16px;
  margin-bottom: var(--gap);
}

.gbar.bad {
  border-color: #e8cdc7;
  background: var(--danger-soft);
}

.gbar-main {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  font-size: 12.5px;
  color: var(--muted);
}

.gbar-item b {
  color: var(--ink-2);
  font-weight: 500;
}

.gbar.bad .gbar-item b,
.gbar.bad .gbar-main {
  color: var(--danger);
}

.gbar-msg {
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--danger);
}

.gbar .mono {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}

/* ---------------------------------------------------------------- 平台卡 */

.pcards {
  display: grid;
  /* 380px 起：两平台各一张并排，第三个平台时自动换行而不挤压内容 */
  grid-template-columns: repeat(auto-fit, minmax(380px, 1fr));
  gap: var(--gap);
}

.pcard {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
}

/* 未启用的平台留卡片、不隐藏：阶段 3 的开关要有落点，且「消失」比「灰着」更难理解 */
.pcard.off {
  opacity: 0.55;
}

.pcard-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.pcard-title {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

/* 开关徽标与卡头右侧的状态标签分开：两者都在说「这个平台怎样」，
   同一个样式会让人以为它是又一个运行状态 */
.pcard-badge {
  margin-left: 8px;
  padding: 0 5px;
  border: 1px solid var(--line-strong);
  border-radius: 2px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: var(--muted);
  vertical-align: 1px;
}

.pcard-badge.warn {
  color: var(--warn);
  border-color: #e6d6ae;
  background: #fbf5e6;
}

.pcard-badge.danger {
  color: var(--danger);
  border-color: #e8cdc7;
  background: var(--danger-soft);
}

.pcard-sub {
  color: var(--muted);
  font-size: 12px;
  margin-top: 2px;
}

.pcard-tags {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.pstats {
  display: flex;
  gap: 22px;
  margin: 16px 0 4px;
}

.pstat-label {
  color: var(--muted);
  font-size: 11.5px;
  letter-spacing: 0.02em;
}

.pstat-value {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
  margin-top: 2px;
}

.pstat-den {
  font-size: 12.5px;
  font-weight: 400;
  color: var(--muted);
}

.prows {
  border-top: 1px solid var(--line);
  margin-top: 12px;
  padding-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.prow {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  font-size: 12.5px;
}

.prow-key {
  color: var(--muted);
  flex-shrink: 0;
}

.prow-val {
  color: var(--ink-2);
  text-align: right;
}

/* 动作格子：文本可省略、标签不换行。min-width: 0 是让 flex 子项允许被压缩的前提，
   少了它长文本会把整行撑宽而不是打省略号 */
.prow-val-action {
  display: flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: 6px;
  min-width: 0;
}

.prow-action {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.prow-val-action .tag {
  flex-shrink: 0;
  white-space: nowrap;
}

.pcard-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-top: 1px solid var(--line);
  margin-top: 14px;
  padding-top: 14px;
}

.pcard-link {
  font-size: 12.5px;
  color: var(--muted);
}

.pcard-link:hover {
  color: var(--ink);
}
</style>
