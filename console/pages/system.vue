<template>
  <div>
    <div class="page-head">
      <div>
        <h1>系统</h1>
        <p>浏览器与登录状态、消息通知、数据清理</p>
      </div>
      <button class="btn" :disabled="busy" @click="load">刷新</button>
    </div>

    <!-- 值守任务与告警放最前面：它是唯一能回答「有没有人漏掉」的地方，
         排在 driver 自检后面会让人先看一堆绿色再去想有没有人没被回复 -->
    <UnreadTasks />

    <!--
      登录态必须按平台分行：两个平台是两份独立的浏览器 profile，需要各扫各的码。
      原来只有一行「登录态」，两个平台之后它回答不了「哪个还没登录」——
      而没登录的那个平台，跑起来第一件事就是 LOGIN_LOST 退出。
    -->
    <div class="card">
      <div class="row" style="justify-content: space-between; align-items: flex-start">
        <div>
          <h2>浏览器与登录状态</h2>
          <p class="hint">
            每个平台用各自独立的浏览器，登录状态不通用。检测时会读取该平台浏览器里保存的登录信息
          </p>
        </div>
      </div>

      <div v-if="!cards.length" class="empty">正在读取平台列表…</div>
      <table v-else>
        <thead>
          <tr>
            <th>平台</th>
            <th style="width: 96px">浏览器</th>
            <th>登录状态</th>
            <th style="width: 96px">自动检查</th>
            <th style="width: 268px"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in cards" :key="c.platform">
            <td>
              {{ c.label }}
              <span v-if="c.enabled === false" class="tag">已停用</span>
            </td>
            <td>
              <span class="tag" :class="c.browserReady ? 'ok' : ''">
                {{ c.browserReady ? '已启动' : '未启动' }}
              </span>
            </td>
            <td>
              <template v-if="login[c.platform]">
                <span class="tag" :class="login[c.platform].loggedIn ? 'ok' : 'danger'">
                  {{ login[c.platform].loggedIn ? '已登录' : '未登录' }}
                </span>
                <div class="hint-inline">{{ login[c.platform].reason }}</div>
              </template>
              <span v-else class="hint-inline">还没检测</span>
            </td>
            <td>
              <span v-if="checks[c.platform]" class="tag" :class="checks[c.platform].ok ? 'ok' : 'danger'">
                {{ checks[c.platform].ok ? '通过' : '未通过' }}
              </span>
              <span v-else class="hint-inline">还没运行</span>
            </td>
            <td>
              <!-- 停用的平台不给这三个按钮：它们都会去开那个平台的浏览器，
                   而开关关掉的正是「为它开浏览器」这件事 -->
              <div class="row" style="gap: 6px; justify-content: flex-end">
                <button
                  class="btn sm"
                  :disabled="busy || c.enabled === false"
                  :title="c.enabled === false ? '这个平台已停用' : ''"
                  @click="probeLogin(c.platform)"
                >
                  检测登录状态
                </button>
                <button
                  class="btn sm"
                  :disabled="busy || c.enabled === false"
                  :title="c.enabled === false ? '这个平台已停用' : ''"
                  @click="openLogin(c.platform)"
                >
                  打开登录页
                </button>
                <button
                  class="btn sm"
                  :disabled="busy || c.enabled === false"
                  :title="c.enabled === false ? '这个平台已停用' : ''"
                  @click="runSelfCheck(c.platform)"
                >
                  检查浏览器是否正常
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 自检失败原因逐条列出：只显示一个「未通过」等于让人自己去猜是哪一项断言没过 -->
      <div v-for="c in failedChecks" :key="c.platform" class="check-fail">
        <div class="hint-inline" style="color: var(--danger)">{{ c.label }} 自动检查未通过：</div>
        <div v-for="(f, i) in c.failures" :key="i" class="hint-inline" style="color: var(--danger)">
          · {{ f }}
        </div>
      </div>
    </div>

    <div class="grid cols-2">
      <div class="card">
        <h2>浏览器驱动</h2>
        <p class="hint">
          找不到浏览器驱动时，任务不会启动，也不会偷偷换成普通浏览器把流程跑下去
        </p>
        <div class="stack">
          <div class="row" style="justify-content: space-between">
            <span>是否可用</span>
            <span class="tag" :class="driverReady ? 'ok' : 'danger'">
              {{ driverReady ? '就绪' : '不可用' }}
            </span>
          </div>
          <div class="hint-inline">两个平台共用同一份浏览器驱动；它不可用时，两个平台都启动不了</div>
          <div>
            <div class="hint-inline">驱动所在目录</div>
            <div style="word-break: break-all">{{ status?.driverDir || '未找到' }}</div>
          </div>
          <div>
            <div class="hint-inline">浏览器运行环境</div>
            <div style="word-break: break-all">{{ status?.nodePath || '系统里没有找到' }}</div>
          </div>
          <div v-if="status && !driverReady" class="hint-inline" style="color: var(--danger)">
            {{ status.driverMessage }}
          </div>
        </div>
      </div>

      <div class="card">
        <h2>消息通知</h2>
        <p class="hint">
          通过企业微信群机器人发送；没有配置时只写日志。同类消息 5 分钟内会合并成一条，
          正文里会写明是哪个平台
        </p>
        <button class="btn" :disabled="busy" @click="testNotify">发送测试消息</button>
      </div>
    </div>

    <div class="card">
      <h2>今天的进度</h2>
      <p class="hint">
        投递名额和查看上限都以这里为准，重启软件不会清零。每个平台各算各的，
        两个平台的数字不相加（「24/60」不是还剩 36，而是每个平台各自的上限）
      </p>
      <div v-if="!cards.length" class="empty">正在读取平台列表…</div>
      <table v-else>
        <thead>
          <tr>
            <th>平台</th>
            <th>今天主动联系 / 名额</th>
            <th>应要求发的简历</th>
            <th>今天查看 / 上限</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in cards" :key="c.platform">
            <td>{{ c.label }}</td>
            <td class="num">{{ c.delivered ?? 0 }} / {{ c.quota ?? '—' }}</td>
            <!-- 数据源是 reply_log 的 REPLY_RESUME，与主动投递不是一个口径（ADR-0004） -->
            <td class="num">{{ c.resumeOnRequest ?? 0 }}</td>
            <td class="num">{{ c.evaluated ?? 0 }} / {{ c.evalLimit ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
      <div class="hint-inline" style="margin-top: 12px">
        日期 {{ overview?.global?.serverTime || status?.serverTime || '—' }} · 筛选项
        {{ overview?.global?.dictionaryTotal ?? status?.dictionaryTotal ?? 0 }} 条（两个平台共用一份）
      </div>
    </div>

    <div class="card">
      <h2>数据清理</h2>
      <p class="hint">
        岗位记录保留 30 天，但只清理「没有投递过、并且已经过期」的记录。
        如果把投递过的记录也清掉，同一个岗位可能会被重复投递
      </p>
      <div class="row">
        <button class="btn danger" :disabled="busy" @click="cleanup">立即清理</button>
        <span class="hint-inline">运行日志保留 14 天，回复记录保留 90 天</span>
      </div>
      <div v-if="cleanupResult" class="hint-inline" style="margin-top: 12px">
        已清理：岗位 {{ cleanupResult.jobsRemoved }} 条，回复记录 {{ cleanupResult.replyLogsRemoved }} 条，
        运行日志 {{ cleanupResult.runLogsRemoved }} 条
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
const { call } = useApi()
const { show } = useToast()
const { platforms, ensure } = usePlatform()

const status = ref<any>(null)
const overview = ref<any>(null)
/** 登录态与自检结果都按平台存：一个平台的结果不能替另一个平台作保 */
const login = reactive<Record<string, any>>({})
const checks = reactive<Record<string, any>>({})
const cleanupResult = ref<any>(null)
const busy = ref(false)

const cards = computed<any[]>(() => overview.value?.platforms || [])
/** driver 是两平台共用的一份，指标准取全局条；取不到再退回 status */
const driverReady = computed<boolean>(() => {
  const g = overview.value?.global
  if (g && typeof g.driverAvailable === 'boolean') return g.driverAvailable
  return !!status.value?.driverAvailable
})
const failedChecks = computed(() =>
  cards.value
    .filter((c) => checks[c.platform] && !checks[c.platform].ok)
    .map((c) => ({ platform: c.platform, label: c.label, failures: checks[c.platform].failures || [] }))
)

async function load() {
  busy.value = true
  try {
    await ensure().catch(() => {})
    const [s, o] = await Promise.all([call('/system/status'), call('/system/overview')])
    status.value = s
    overview.value = o
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function runSelfCheck(platform: string) {
  busy.value = true
  try {
    const res = await call('/system/self-check', { method: 'POST', query: { platform } })
    checks[platform] = res
    show(`${labelOf(platform)} 自动检查${res.ok ? '通过' : '未通过'}`, !res.ok)
    await load()
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function probeLogin(platform: string) {
  busy.value = true
  try {
    const res = await call('/system/probe-login', { method: 'POST', query: { platform } })
    login[platform] = res
    show(`${labelOf(platform)} ${res.loggedIn ? '已登录' : '未登录'}`, !res.loggedIn)
    // 探测会把该平台的浏览器拉起来：不重读一次，「浏览器」那列还显示未启动，
    // 人会以为探测没生效（实际是这一行没刷新）
    await load()
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function openLogin(platform: string) {
  busy.value = true
  try {
    const res = await call('/system/open-login', { method: 'POST', query: { platform } })
    show(res?.hint || '已打开登录页，请在浏览器窗口扫码')
    await load()
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

function labelOf(platform: string) {
  return platforms.value.find((p) => p.platform === platform)?.label || platform
}

async function testNotify() {
  busy.value = true
  try {
    await call('/system/notify-test', { method: 'POST' })
    show('已发送（没有配置企业微信机器人时只写日志）')
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function cleanup() {
  if (!confirm('清理过期的岗位记录和日志？投递记录不会被清理。')) return
  busy.value = true
  try {
    cleanupResult.value = await call('/system/cleanup', { method: 'POST', body: { scannedDays: 30, logDays: 14 } })
    show('清理完成')
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.check-fail {
  margin-top: 12px;
  padding: 10px 12px;
  border: 1px solid #e8cdc7;
  border-radius: var(--radius);
  background: var(--danger-soft);
}
</style>
