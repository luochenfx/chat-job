<template>
  <div>
    <div class="page-head">
      <div>
        <h1>系统</h1>
        <p>driver 装配、启动自检、登录探测、通知与数据清理</p>
      </div>
      <button class="btn" :disabled="busy" @click="load">刷新</button>
    </div>

    <!-- 值守任务与告警放最前面：它是唯一能回答「有没有人漏掉」的地方，
         排在 driver 自检后面会让人先看一堆绿色再去想有没有人没被回复 -->
    <UnreadTasks />

    <div class="grid cols-2">
      <div class="card">
        <h2>patchright driver</h2>
        <p class="hint">找不到 driver 时任务会被拒绝启动（fail-closed），绝不静默退回官方 driver</p>
        <div class="stack">
          <div class="row" style="justify-content: space-between">
            <span>可用性</span>
            <span class="tag" :class="status?.driverAvailable ? 'ok' : 'danger'">
              {{ status?.driverAvailable ? '就绪' : '不可用' }}
            </span>
          </div>
          <div>
            <div class="hint-inline">driver 目录</div>
            <div style="word-break: break-all">{{ status?.driverDir || '未找到' }}</div>
          </div>
          <div>
            <div class="hint-inline">node 可执行文件</div>
            <div style="word-break: break-all">{{ status?.nodePath || 'PATH 里没有 node' }}</div>
          </div>
          <div v-if="status && !status.driverAvailable" class="hint-inline" style="color: var(--danger)">
            {{ status.driverMessage }}
          </div>
        </div>
      </div>

      <div class="card">
        <h2>启动自检</h2>
        <p class="hint">断言 navigator.webdriver 为 false、无 cdc_ 残留、确实跑在 patchright 上</p>
        <div class="row" style="margin-bottom: 14px">
          <button class="btn primary" :disabled="busy" @click="runSelfCheck">运行自检</button>
          <span class="hint-inline">会启动一次浏览器</span>
        </div>
        <div v-if="!selfCheck" class="empty">尚未运行</div>
        <div v-else class="stack">
          <span class="tag" :class="selfCheck.ok ? 'ok' : 'danger'">
            {{ selfCheck.ok ? '通过' : '未通过' }}
          </span>
          <div v-for="(f, i) in selfCheck.failures" :key="i" class="hint-inline" style="color: var(--danger)">
            · {{ f }}
          </div>
          <div class="log" style="max-height: 180px">{{ JSON.stringify(selfCheck.details, null, 2) }}</div>
        </div>
      </div>

      <div class="card">
        <h2>登录态</h2>
        <p class="hint">优先看登录态 cookie（bst / wt2 / zp_at / geek_zp_token），DOM 探测只作兜底</p>
        <div class="row" style="margin-bottom: 14px">
          <button class="btn" :disabled="busy" @click="probeLogin">探测登录</button>
          <button class="btn primary" :disabled="busy" @click="openLogin">打开登录页</button>
        </div>
        <div v-if="!login" class="empty">尚未探测</div>
        <div v-else class="stack">
          <span class="tag" :class="login.loggedIn ? 'ok' : 'danger'">
            {{ login.loggedIn ? '已登录' : '未登录' }}
          </span>
          <div class="hint-inline">{{ login.reason }}</div>
        </div>
      </div>

      <div class="card">
        <h2>通知</h2>
        <p class="hint">企业微信群机器人；未配置时只写日志。同类型事件 5 分钟内合并</p>
        <button class="btn" :disabled="busy" @click="testNotify">发送测试消息</button>
      </div>
    </div>

    <div class="card">
      <h2>数据清理</h2>
      <p class="hint">
        扫描明细保留 30 天，但只清「未投递 且 超期」的记录 —— 否则去重记录被清掉会导致同一岗位被重复投递
      </p>
      <div class="row">
        <button class="btn danger" :disabled="busy" @click="cleanup">立即清理</button>
        <span class="hint-inline">run_log 保留 14 天，reply_log 保留 90 天</span>
      </div>
      <div v-if="cleanupResult" class="hint-inline" style="margin-top: 12px">
        已清理：岗位 {{ cleanupResult.jobsRemoved }} 条，值守日志 {{ cleanupResult.replyLogsRemoved }} 条，
        运行日志 {{ cleanupResult.runLogsRemoved }} 条
      </div>
    </div>

    <div class="card">
      <h2>当日计数</h2>
      <p class="hint">配额与评估上限都读这里，重启不归零</p>
      <div class="grid cols-2">
        <div>
          <div class="hint-inline">日期</div>
          <div>{{ status?.serverTime }}</div>
        </div>
        <div>
          <div class="hint-inline">今日主动投递 / 评估</div>
          <div class="num">{{ status?.deliveredToday }} / {{ status?.evaluatedToday }}</div>
        </div>
        <div>
          <div class="hint-inline">值守发简历</div>
          <!-- 数据源是 reply_log 的 REPLY_RESUME，与上面的主动投递不是一个口径 -->
          <div class="num">{{ status?.resumeOnRequestToday }}</div>
        </div>
        <div>
          <div class="hint-inline">字典条目</div>
          <div class="num">{{ status?.dictionaryTotal }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
const { call } = useApi()
const { show } = useToast()

const status = ref<any>(null)
const selfCheck = ref<any>(null)
const login = ref<any>(null)
const cleanupResult = ref<any>(null)
const busy = ref(false)

async function load() {
  busy.value = true
  try {
    status.value = await call('/system/status')
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function runSelfCheck() {
  busy.value = true
  try {
    selfCheck.value = await call('/system/self-check', { method: 'POST' })
    show(selfCheck.value.ok ? '自检通过' : '自检未通过', !selfCheck.value.ok)
    await load()
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function probeLogin() {
  busy.value = true
  try {
    login.value = await call('/system/probe-login', { method: 'POST' })
    show(login.value.loggedIn ? '已登录' : '未登录', !login.value.loggedIn)
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function openLogin() {
  busy.value = true
  try {
    const res = await call('/system/open-login', { method: 'POST' })
    show(res?.hint || '已打开登录页，请在浏览器窗口扫码')
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function testNotify() {
  busy.value = true
  try {
    await call('/system/notify-test', { method: 'POST' })
    show('已发送（未配置 webhook 时只写日志）')
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function cleanup() {
  if (!confirm('清理超期扫描明细与日志？投递记录不会被清理。')) return
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
