<template>
  <div>
    <div class="page-head">
      <div>
        <h1>重点关注</h1>
        <p>
          聊得起来的岗位上，AI 按下面这些判断点给出的质量结论。{{ scoreLine }} 分以上才会出现在这里。
          点「人工接管」之后，系统不再替这段会话发消息，也不再打开它
        </p>
      </div>
      <div class="row">
        <button class="btn" :disabled="busy" @click="refresh">刷新</button>
      </div>
    </div>

    <div v-if="!items.length" class="card">
      <div class="empty">
        还没有岗位过线。AI 只在对方已经回复过几条之后才开始判断 —— 那时候「邀约是否认真」
        「沟通深度够不够」才看得出来
      </div>
    </div>

    <div v-for="item in items" :key="item.conversationId" class="card">
      <div class="row" style="justify-content: space-between; align-items: flex-start; gap: 16px">
        <div style="min-width: 0">
          <div style="font-weight: 600; font-size: 16px">
            {{ item.jobName || item.title || '（未取到岗位信息）' }}
          </div>
          <div class="hint-inline">
            <span class="tag">{{ item.platformLabel }}</span>
            <span v-if="item.companyName"> {{ item.companyName }}</span>
            <span v-if="item.salary"> · {{ item.salary }}</span>
            <!-- 反查不到岗位时明说，否则「没有公司名」会被读成「这岗位没写公司」 -->
            <span v-if="!item.jobFound" style="color: var(--warn)">
              · 没取到岗位信息，这次只按对话判断
            </span>
          </div>
        </div>
        <div class="row" style="gap: 10px; align-items: baseline; flex: none">
          <span class="tag" :class="item.takenOver ? 'warn' : 'ok'">
            {{ item.takenOver ? '你已接管' : '过线' }}
          </span>
          <span style="font-size: 26px; font-weight: 600; font-variant-numeric: tabular-nums">
            {{ item.score }}
          </span>
          <span class="hint-inline">分</span>
        </div>
      </div>

      <div v-if="item.summary" style="margin-top: 10px">{{ item.summary }}</div>

      <!-- 逐项结论是「为什么是这个分数」的全部依据。只给分数的话，
           用户没法判断这条到底该不该信 -->
      <div class="row wrap" style="gap: 6px; margin-top: 12px">
        <span
          v-for="c in item.criteria"
          :key="c.criterion"
          class="tag"
          :class="verdictTag(c.verdict)"
          :title="c.evidence || '没有给出依据'"
        >
          {{ verdictMark(c.verdict) }} {{ c.criterion }}
        </span>
      </div>

      <div class="hint-inline" style="margin-top: 12px">
        <template v-if="item.lastMessage">
          最近一句：{{ item.lastMessage }}
          <span v-if="item.unread > 0" style="color: var(--warn)">（未读 {{ item.unread }} 条）</span>
        </template>
        <template v-else>
          {{ item.takenOver ? '接管后不再读取消息内容，未读留在平台上' : '还没有新的消息' }}
        </template>
        <span v-if="item.assessedAt"> · 判断于 {{ item.assessedAt.replace('T', ' ') }}</span>
      </div>

      <div class="row" style="justify-content: flex-end; gap: 8px; margin-top: 12px">
        <button v-if="!item.takenOver" class="btn primary sm" :disabled="busy" @click="takeOver(item)">
          人工接管
        </button>
        <template v-else>
          <button class="btn sm" :disabled="busy" @click="release(item)">交还 AI</button>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
const { call } = useApi()
const { show } = useToast()

const items = ref<any[]>([])
const scoreLine = ref(80)
const busy = ref(false)

/**
 * 三档结论的配色与记号。
 *
 * <p>看不出（UNKNOWN）用灰记号而不是「✗」：它说的是「信息里没有」，与「不符合」是两件事 ——
 * 用同一个记号会把一个没写福利的岗位显示成「福利差」。
 */
function verdictMark(verdict: string) {
  return verdict === 'YES' ? '✓' : verdict === 'NO' ? '✗' : '?'
}

function verdictTag(verdict: string) {
  return verdict === 'YES' ? 'ok' : verdict === 'NO' ? 'danger' : ''
}

async function refresh() {
  busy.value = true
  try {
    const data = await call('/quality')
    items.value = data?.items || []
    scoreLine.value = data?.scoreLine ?? 80
  } catch (e: any) {
    show(e.message, true)
  } finally {
    busy.value = false
  }
}

async function takeOver(item: any) {
  try {
    await call(`/quality/${item.conversationId}/takeover`, { method: 'POST' })
    show(`已接管「${item.jobName || item.title}」，系统不会再替它回消息`)
    await refresh()
  } catch (e: any) {
    show(e.message, true)
  }
}

async function release(item: any) {
  try {
    await call(`/quality/${item.conversationId}/takeover`, { method: 'DELETE' })
    show(`已交还 AI：「${item.jobName || item.title}」`)
    await refresh()
  } catch (e: any) {
    show(e.message, true)
  }
}

onMounted(refresh)
</script>
