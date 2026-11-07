<template>
  <div>
    <div class="page-head">
      <div>
        <h1>重点关注</h1>
        <p>
          聊得起来的岗位上，AI 按下面这些判断点给出的质量结论。分数达到配置页设定的分数线
          （当前 {{ scoreLine ?? '—' }} 分）才会出现在这里。点「人工接管」之后，
          系统不再替这段会话发消息，也不再打开它
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
      <!--
        空列表要说出原因：这个功能默认关，不说的话「一直空着」会被读成「没人理我」。
        只列没开的平台，全开时不出现这一句。
      -->
      <p v-if="qualityOff.length" class="card-note">
        {{ qualityOff.join('、') }} 没有开启「岗位质量判断」，那些平台聊起来的会话不会出现在这里。
        开关在「配置 · 平台配置」里，每个平台各一份
      </p>
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
          :class="verdictView(c.verdict).tone"
          :title="c.evidence || '没有给出依据'"
        >
          {{ verdictView(c.verdict).mark }} {{ c.criterion }}
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
        <span v-if="item.assessedAt"> · 判断于 {{ stamp(item.assessedAt, 'full') }}</span>
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
const { call, attempt } = useApi()
const { show } = useToast()
// 清单与配置页共用一份（useState）。这一页不需要「当前平台」，只要每个平台的开关状态
const { platforms, ensure: ensurePlatform } = usePlatform()

// 挂载取一次、失败保留上一份、卸载清表都在 usePolledData 里；0 表示不轮询
const { data, busy, refresh } = usePolledData(async () => {
  // 清单只用来判断「哪些平台没开」；拉不到就不显示那一句，不拦着列表本身。
  // ensure 内部有短路，只有第一次会真的发请求
  void ensurePlatform().catch(() => {})
  return call('/quality')
}, 0)
const items = computed<any[]>(() => data.value?.items || [])
// 不写默认分数：配置页的分数线可能被改过，前端再写一个 80 就是第二处住所
const scoreLine = computed<number | null>(() => data.value?.scoreLine ?? null)

/** 没开岗位质量判断的平台，只用于空列表时那句说明 */
const qualityOff = computed(() =>
  (platforms.value || [])
    .filter((p) => !p.jobQualityEnabled)
    .map((p) => p.label || p.platform)
)

async function takeOver(item: any) {
  await attempt(async () => {
    await call(`/quality/${item.conversationId}/takeover`, { method: 'POST' })
    show(`已接管「${item.jobName || item.title}」，系统不会再替它回消息`)
    await refresh()
  })
}

async function release(item: any) {
  await attempt(async () => {
    await call(`/quality/${item.conversationId}/takeover`, { method: 'DELETE' })
    show(`已交还 AI：「${item.jobName || item.title}」`)
    await refresh()
  })
}

</script>
