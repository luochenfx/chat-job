<template>
  <div>
    <div class="page-head">
      <div>
        <h1>岗位</h1>
        <p>
          搜索到的岗位，以及每一次主动联系的结果。投递失败不会无限重试：
          同一个岗位连续发三次可能被平台封号
        </p>
      </div>
      <div class="row">
        <!-- 默认「全部平台」而不是当前平台：这页是审阅用途，用户想看的是「两平台一共扫到什么」。
             默认单一平台会让人以为只有这些岗位 -->
        <select v-model="platformFilter" style="width: 150px" @change="refresh">
          <option value="">全部平台</option>
          <option v-for="p in platforms" :key="p.platform" :value="p.platform">{{ p.label }}</option>
        </select>
        <select v-model="filter" style="width: 140px">
          <option value="">全部状态</option>
          <option value="DELIVERED">已投递</option>
          <option value="FAILED">投递失败</option>
          <option value="SKIPPED">未联系</option>
          <option value="NONE">未处理</option>
        </select>
        <button class="btn" :disabled="busy" @click="refresh">刷新</button>
      </div>
    </div>

    <div class="card">
      <div v-if="!filtered.length" class="empty">还没有岗位记录</div>
      <table v-else>
        <thead>
          <tr>
            <th>岗位 / 公司</th>
            <th style="width: 92px">薪资</th>
            <th style="width: 80px">活跃度</th>
            <th style="width: 92px">状态</th>
            <!-- 时间列紧跟状态：两格都在回答「这岗位新鲜不新鲜」，
                 隔一列再放会让人把「扫到」读成「状态发生的时刻」 -->
            <th style="width: 120px">时间</th>
            <th>判断结果 / 失败原因</th>
            <th style="width: 130px"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="job in filtered" :key="job.id">
            <td>
              <div style="font-weight: 500">{{ job.jobName || '（无岗位名）' }}</div>
              <!-- 平台列跟着岗位走，不单独占一列：选「全部平台」时没有它分不清哪条是哪个平台的 -->
              <div class="hint-inline">
                <span class="tag plat">{{ job.platformLabel || job.platform || '—' }}</span>
                {{ job.companyName || '—' }}<span v-if="job.area"> · {{ job.area }}</span>
              </div>
            </td>
            <td class="num">{{ job.salary || '—' }}</td>
            <td>
              <span class="tag" :class="activeClass(job.activeLevel)">{{ job.activeLevel || '未知' }}</span>
            </td>
            <td>
              <span class="tag" :class="statusTag(job.deliveryStatus)">{{ statusText(job.deliveryStatus) }}</span>
            </td>
            <td class="when">
              <div :title="fullTime(job.lastSeenAt)">
                <span class="when-label">发现</span>{{ relTime(job.lastSeenAt, nowMs) }}
              </div>
              <!-- 没有处置就整行不显示：状态列已经写着「未处理」，
                   底下再来一个「处置 —」是重复；重投清掉投递记录后同样走这一支 -->
              <div v-if="job.disposedAt" class="hint-inline" :title="fullTime(job.disposedAt)">
                <span class="when-label">处理</span>{{ relTime(job.disposedAt, nowMs) }}
              </div>
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
                  重新投递
                </button>
                <button
                  class="btn sm"
                  title="黑名单两个平台共用：拉黑后哪个平台都不会再投它"
                  @click="blacklistCompany(job)"
                >
                  拉黑
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card">
      <h2>黑名单 · 两个平台共用</h2>
      <p class="hint">
        名单里的词一旦命中，这个岗位会直接跳过：不调用 AI，也不记录投递。
        名单两个平台共用，拉黑一家公司，两个平台都不会再投它。
        关键词至少 2 个字，只要岗位名或公司名里包含这个词就算命中，不分大小写
      </p>
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
const { platforms, ensure } = usePlatform()

const jobs = ref<any[]>([])
const blacklist = ref<any[]>([])
// 30 秒走一次字就够：这页的精度只到分钟，每秒重算两百多行不划算
const nowMs = useTickClock(30000)
const filter = ref('')
// 空串 = 全部平台。与后端 /jobs 的缺省一致（不传就是全部）
const platformFilter = ref('')
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
    ({ DELIVERING: '投递中', DELIVERED: '已投递', FAILED: '失败', SKIPPED: '未联系', MANUAL: '需人工处理' } as any)[status] ||
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
    await ensure().catch(() => {})
    const [j, b] = await Promise.all([
      call('/jobs', { query: { limit: 200, platform: platformFilter.value || undefined } }),
      call('/blacklist')
    ])
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
    show(`已重置 ${job.jobName}，下次搜索时会重新判断`)
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

<style scoped>
/* 两行时间共用一个列宽：标签定宽，后面的值才能对齐着扫 */
.when {
  font-variant-numeric: tabular-nums;
}

.when-label {
  display: inline-block;
  width: 26px;
  margin-right: 3px;
  color: var(--muted);
  font-size: 10.5px;
}

/* 平台标签比正文小一号、且不抢公司名：它的作用是「归属」而不是「内容」 */
.plat {
  margin-right: 6px;
  font-size: 10.5px;
  padding: 0 5px;
  color: var(--ink-2);
  background: var(--surface-2);
}
</style>
