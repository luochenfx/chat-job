<template>
  <div>
    <div class="page-head">
      <div>
        <h1>系统</h1>
        <p>浏览器与登录状态、告警通知、数据清理</p>
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

      <!--
        「告警通知」而不是「消息通知」：这里发的是系统自己的事（没跑起来、登录失效、
        进入待命、时段结束），与值守时要处理的 HR 消息是两件事。旧名字会让人以为
        「HR 发来消息时会提醒我」，而那个功能并没有做（CONTEXT.md「告警」词条）。
      -->
      <div class="card">
        <h2>告警通知</h2>
        <p class="hint">
          通过企业微信群机器人发到手机。没配置时告警只写运行日志，手机上收不到。
          同类消息 {{ throttleText }}内合并成一条，正文里会写明是哪个平台
        </p>

        <div class="notify-status">
          <span class="tag" :class="notify.configured ? 'ok' : 'warn'">{{ statusLabel }}</span>
          <span class="hint-inline" :style="notify.source === 'broken' ? 'color: var(--danger)' : ''">
            {{ sourceLabel }}
          </span>
        </div>

        <div class="field">
          <label for="wecom-webhook">企业微信群机器人 Webhook</label>
          <input
            id="wecom-webhook"
            v-model="notifyForm.webhook"
            type="text"
            spellcheck="false"
            placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=…"
          />
          <div class="desc">
            已保存的地址不会显示在框里。留空表示不改动，要换地址才需要填
          </div>
        </div>

        <!-- 三组开关：异常要人处理，待命与时段结束每天必然发生，能单独关掉才不会
             逼着人把整个告警静音（关了就等于出事也没人知道） -->
        <div class="switches">
          <label class="switch" title="任务没跑起来或跑挂了：驱动不可用、自检未通过、登录失效、熔断、异常终止">
            <input v-model="notifyForm.alertAbnormal" type="checkbox" />
            <span>异常告警<b>{{ notifyForm.alertAbnormal ? '开' : '关' }}</b></span>
          </label>
          <label class="switch" title="扫描结束、值守继续。每天一次">
            <input v-model="notifyForm.alertStandby" type="checkbox" />
            <span>进入待命<b>{{ notifyForm.alertStandby ? '开' : '关' }}</b></span>
          </label>
          <label class="switch" title="值守时段结束，当天不再操作页面。每天一次">
            <input v-model="notifyForm.alertDutyEnd" type="checkbox" />
            <span>时段结束<b>{{ notifyForm.alertDutyEnd ? '开' : '关' }}</b></span>
          </label>
        </div>

        <div class="row wrap" style="margin-top: 16px">
          <button class="btn" :disabled="busy || !notifyDirty" @click="saveNotify">保存</button>
          <!-- 测试发的是「已保存的地址」，框里还有没保存的内容时按钮没有意义 -->
          <button
            class="btn"
            :disabled="busy || webhookPending || !notify.configured"
            :title="webhookPending ? '先把上面填的地址保存下来' : ''"
            @click="testNotify"
          >
            发送测试消息
          </button>
          <!-- 只在地址来自控制台时给清除：地址来自配置文件时，清库什么也不会变 -->
          <button
            v-if="notify.source === 'db'"
            class="btn danger"
            :disabled="busy"
            @click="clearWebhook"
          >
            清除地址
          </button>
        </div>
        <div v-if="webhookPending" class="hint-inline" style="margin-top: 8px">
          上面填的地址还没保存，测试按钮发的是已保存的那一个
        </div>

        <div class="hint-inline" style="margin-top: 12px">{{ lastDeliveryText }}</div>
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

/**
 * 告警配置。`notify` 是服务端说的生效状态（含「地址来自配置文件」这种来源信息），
 * `notifyForm` 是表单。两者分开：输入框里是「准备保存的地址」，它永远不从服务端回填 ——
 * 地址里带 key，回显就等于把凭据交给页面、截图和浏览器缓存。
 */
const notify = ref<any>({
  configured: false,
  source: 'none',
  alertAbnormal: true,
  alertStandby: true,
  alertDutyEnd: true,
  throttleWindowSeconds: 300,
  lastDelivery: null
})
const notifyForm = reactive({
  webhook: '',
  alertAbnormal: true,
  alertStandby: true,
  alertDutyEnd: true
})

/** 框里有内容＝有没保存的地址。测试按钮此时发的是旧地址，必须挡住 */
const webhookPending = computed(() => notifyForm.webhook.trim() !== '')

const notifyDirty = computed(
  () =>
    webhookPending.value ||
    notifyForm.alertAbnormal !== notify.value.alertAbnormal ||
    notifyForm.alertStandby !== notify.value.alertStandby ||
    notifyForm.alertDutyEnd !== notify.value.alertDutyEnd
)

const statusLabel = computed(() => {
  if (notify.value.configured) return '已配置'
  return notify.value.source === 'broken' ? '配置已失效' : '未配置'
})

const sourceLabel = computed(() => {
  switch (notify.value.source) {
    case 'db':
      return '地址来自控制台'
    case 'yaml':
      // 说得具体一点：用户改的是这里，但生效的是文件里那份
      return '地址来自 application.yaml（或启动参数），在下面保存会覆盖它'
    case 'broken':
      return '已保存的地址解不开（可能换了 Windows 账户或电脑），请重新填写'
    default:
      return '还没有地址，告警只写运行日志'
  }
})

const throttleText = computed(() => {
  const seconds = Number(notify.value.throttleWindowSeconds || 300)
  return seconds >= 60 && seconds % 60 === 0 ? `${seconds / 60} 分钟` : `${seconds} 秒`
})

const lastDeliveryText = computed(() => {
  const d = notify.value.lastDelivery
  if (!d) return '本次启动后还没有发送过告警'
  return `最近一次发送：${d.at} · ${d.sent ? '成功' : '没发出去'} · ${d.detail}`
})

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
    const [s, o, n] = await Promise.all([
      call('/system/status'),
      call('/system/overview'),
      call('/config/notify')
    ])
    status.value = s
    overview.value = o
    applyNotify(n)
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

function applyNotify(n: any) {
  notify.value = n
  // 开关按服务端的值回填（它才是生效值），地址永不回填
  notifyForm.alertAbnormal = n?.alertAbnormal !== false
  notifyForm.alertStandby = n?.alertStandby !== false
  notifyForm.alertDutyEnd = n?.alertDutyEnd !== false
  notifyForm.webhook = ''
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
    const res = await call('/system/notify-test', { method: 'POST' })
    // 服务端回的是「这次到底发出去了没有」，不再无条件报成功
    show(res?.message || (res?.delivered ? '已发送' : '没有发出去'), !res?.delivered)
    await load()
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function saveNotify() {
  busy.value = true
  try {
    const body: any = {
      alertAbnormal: notifyForm.alertAbnormal,
      alertStandby: notifyForm.alertStandby,
      alertDutyEnd: notifyForm.alertDutyEnd
    }
    // 留空＝不修改：不把空串发上去，接口那边也按同样口径处理
    const webhook = notifyForm.webhook.trim()
    if (webhook) body.webhook = webhook
    await call('/config/notify', { method: 'PUT', body })
    show('已保存')
    await load()
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function clearWebhook() {
  if (!confirm('清除已保存的 Webhook 地址？清除后告警只写运行日志，手机上收不到。')) return
  busy.value = true
  try {
    await call('/config/notify/webhook', { method: 'DELETE' })
    show('已清除')
    await load()
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

/* 状态一行、来源一行：挤在同一行时来源那句会被压成三四行，标签被挤成竖条 */
.notify-status {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  margin-bottom: 14px;
}

/* 三枚开关一组：与配置页的三个平台开关同一观感，间距比卡片外的小，
   读起来是「同一组状态」而不是三个独立选项 */
.switches {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
}

.switches b {
  margin-left: 5px;
  font-weight: 500;
  color: var(--ink);
}
</style>
