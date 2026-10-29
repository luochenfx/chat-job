<template>
  <div>
    <div class="page-head">
      <div>
        <h1>岗位</h1>
        <p>扫到的岗位与每次投递尝试。失败不自动无限重试：同一岗位连发三次是封号主因</p>
      </div>
      <div class="row">
        <select v-model="filter" style="width: 140px">
          <option value="">全部状态</option>
          <option value="DELIVERED">已投递</option>
          <option value="FAILED">投递失败</option>
          <option value="SKIPPED">未投 / 跳过</option>
          <option value="NONE">未处理</option>
        </select>
        <button class="btn" :disabled="busy" @click="refresh">刷新</button>
      </div>
    </div>

    <div class="card">
      <div v-if="!filtered.length" class="empty">暂无岗位记录</div>
      <table v-else>
        <thead>
          <tr>
            <th>岗位 / 公司</th>
            <th style="width: 92px">薪资</th>
            <th style="width: 80px">活跃度</th>
            <th style="width: 92px">状态</th>
            <th>判定 / 失败原因</th>
            <th style="width: 130px"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="job in filtered" :key="job.id">
            <td>
              <div style="font-weight: 500">{{ job.jobName || '（无岗位名）' }}</div>
              <div class="hint-inline">{{ job.companyName || '—' }}<span v-if="job.area"> · {{ job.area }}</span></div>
            </td>
            <td class="num">{{ job.salary || '—' }}</td>
            <td>
              <span class="tag" :class="activeClass(job.activeLevel)">{{ job.activeLevel || '未知' }}</span>
            </td>
            <td>
              <span class="tag" :class="statusTag(job.deliveryStatus)">{{ statusText(job.deliveryStatus) }}</span>
            </td>
            <td class="hint-inline">
              <span v-if="job.score != null">[{{ job.score }}] </span>
              {{ job.aiReason || job.deliveryReason || '—' }}
            </td>
            <td>
              <div class="row" style="gap: 6px; justify-content: flex-end">
                <button
                  v-if="['FAILED', 'SKIPPED', 'MANUAL'].includes(job.deliveryStatus)"
                  class="btn sm"
                  @click="reset(job)"
                >
                  重投
                </button>
                <button class="btn sm" @click="blacklistCompany(job)">拉黑</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card">
      <h2>黑名单</h2>
      <p class="hint">命中即丢弃：不调 AI、不落投递记录。关键词至少 2 个字符，子串匹配，忽略全角与大小写</p>
      <div class="row" style="margin-bottom: 14px">
        <select v-model="newKind" style="width: 110px">
          <option value="company">公司名</option>
          <option value="job">岗位名</option>
        </select>
        <input v-model="newKeyword" type="text" placeholder="例如：外包、销售、派遣" @keyup.enter="addBlacklist" />
        <button class="btn" :disabled="!newKeyword.trim()" @click="addBlacklist">添加</button>
      </div>
      <div v-if="!blacklist.length" class="empty">还没有黑名单</div>
      <div v-else class="row wrap" style="gap: 8px">
        <span v-for="b in blacklist" :key="b.id" class="chip" :class="b.kind === 'company' ? '' : 'danger'">
          <span style="color: var(--muted); font-size: 11px">{{ b.kind === 'company' ? '公司' : '岗位' }}</span>
          {{ b.keyword }}
          <button @click="removeBlacklist(b)">×</button>
        </span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
const { call } = useApi()
const { show } = useToast()

const jobs = ref<any[]>([])
const blacklist = ref<any[]>([])
const filter = ref('')
const busy = ref(false)
const newKind = ref('company')
const newKeyword = ref('')

const filtered = computed(() => {
  if (!filter.value) return jobs.value
  if (filter.value === 'NONE') return jobs.value.filter((j) => !j.deliveryStatus)
  return jobs.value.filter((j) => j.deliveryStatus === filter.value)
})

function statusText(status?: string) {
  if (!status) return '未处理'
  return (
    ({ DELIVERING: '投递中', DELIVERED: '已投递', FAILED: '失败', SKIPPED: '未投', MANUAL: '需人工' } as any)[status] ||
    status
  )
}

function statusTag(status?: string) {
  if (status === 'DELIVERED') return 'ok'
  if (status === 'FAILED' || status === 'MANUAL') return 'danger'
  if (status === 'DELIVERING') return 'warn'
  return ''
}

function activeClass(level?: string) {
  if (!level || level === '未知') return ''
  if (['在线', '刚刚', '今日', '3日内'].includes(level)) return 'ok'
  if (['2月内', '半年前'].includes(level)) return 'danger'
  return 'warn'
}

async function refresh() {
  busy.value = true
  try {
    const [j, b] = await Promise.all([call('/jobs', { query: { limit: 200 } }), call('/blacklist')])
    jobs.value = j || []
    blacklist.value = b || []
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function reset(job: any) {
  try {
    await call(`/jobs/${job.id}/reset`, { method: 'POST' })
    show(`已重置 ${job.jobName}，下次扫描会重新评估`)
    await refresh()
  } catch (e: any) {
    show(e.message, true)
  }
}

// 函数名不能叫 blacklist —— 与上面的 ref 同名会在 <script setup> 里重复声明
function blacklistCompany(job: any) {
  addKeyword('company', job.companyName)
}

async function addKeyword(kind: string, keyword: string) {
  if (!keyword || !keyword.trim()) return
  try {
    await call('/blacklist', { method: 'POST', body: { kind, keyword: keyword.trim() } })
    show(`已加入黑名单：${keyword}`)
    await refresh()
  } catch (e: any) {
    show(e.message, true)
  }
}

async function addBlacklist() {
  await addKeyword(newKind.value, newKeyword.value)
  newKeyword.value = ''
}

async function removeBlacklist(item: any) {
  try {
    await call(`/blacklist/${item.id}`, { method: 'DELETE' })
    await refresh()
  } catch (e: any) {
    show(e.message, true)
  }
}

onMounted(refresh)
</script>
