import type { Ref } from 'vue'

/**
 * 当前平台这一轮 Run 的实时视图。
 *
 * <p>页面只需要读这些值、调 refresh 与 clearLogs，不必知道也不必按顺序写下面这些事：
 * 挂载时先取平台清单再取快照、每 3 秒补一次快照、请求回来时核对平台有没有变、
 * 换了一轮 Run 就先清事件再开新连接、日志按最后一条的 id 续拉、切平台前把上一平台的
 * 东西全清掉。这些顺序原本写在 task.vue 的注释里，漏写任何一步都不会报错，只会让人
 * 看到另一个平台的日志，或者一条本该断开却还显示「已连接」的连接。
 *
 * <p>只服务任务页。概览页取的是 /system/overview（所有平台一次拿全），没有事件流、
 * 没有日志续拉、也不按平台切换，形状不同，走 usePolledData 那条路，不在这里兼两种模式。
 *
 * <p>事件名与文案仍在 runEvents.ts 一份（ADR-0013），这里通过 useRunStream 用它，
 * 不复制、不拆开。
 */
export function useRunSession(platform: Ref<string>) {
  const { call, attempt } = useApi()
  const { ensure } = usePlatform()
  const { events, connected, open, clear, close } = useRunStream()
  const nowMs = useTickClock()

  const run = ref<any>(null)
  const combinations = ref<any[]>([])
  const logs = ref<any[]>([])
  const duty = ref<any>(null)
  /** 无响应阈值，后端算好下发；前端只做比较 */
  const staleAfter = ref(90)

  let lastLogId = 0
  let pollTimer: ReturnType<typeof setInterval> | null = null
  let currentRunId: number | null = null

  // 「上次动是什么时候」：判断规则在 useActivity 里，这里只把阈值传进去
  const activity = computed(() =>
    activityView(run.value, { nowMs: nowMs.value, staleAfterSeconds: staleAfter.value })
  )

  async function pullLogs(runId: number) {
    const items = await call(`/runs/${runId}/logs`, { query: { afterId: lastLogId, limit: 200 } })
    if (items?.length) {
      logs.value = [...logs.value, ...items].slice(-400)
      lastLogId = items[items.length - 1].id
    }
  }

  async function refresh() {
    const want = platform.value
    await attempt(async () => {
      const cur = await call('/runs/current', { query: { platform: want } })
      // 切平台时 query 已变、请求还没回：这一帧描述的是上一个平台，丢掉。
      // 不丢的话，切到智联的瞬间会先看到 BOSS 的日志，然后日志"消失"变空
      if (platform.value !== want) return
      run.value = cur.run
      duty.value = cur.duty || null
      staleAfter.value = cur.staleAfterSeconds ?? 90
      combinations.value = cur.combinations || []
      if (cur.run && cur.run.id !== currentRunId) {
        currentRunId = cur.run.id
        clear()
        open(cur.run.id)
        logs.value = []
        lastLogId = 0
      }
      // 没有 Run 时也要断开：否则切到「今天还没跑过」的平台，SSE 还连着上一平台那条
      if (!cur.run && connected.value) {
        close()
        currentRunId = null
      }
      if (cur.run) await pullLogs(cur.run.id)
    })
  }

  /**
   * 换平台：先把上一平台的东西全清掉，再发请求。
   *
   * <p>顺序不能反。等响应回来才清的话，界面会有一段时间挂着另一个平台的日志、组合队列与
   * 「实时连接：已连接」——那比看到空白更让人困惑：人以为切过去了，实际看的是别人。
   */
  function resetForPlatformChange() {
    close()
    clear()
    run.value = null
    duty.value = null
    combinations.value = []
    logs.value = []
    lastLogId = 0
    currentRunId = null
  }

  function clearLogs() {
    logs.value = []
    lastLogId = 0
  }

  onMounted(async () => {
    // 先拿到清单：没有清单就不知道该问哪个平台的 Run（缺省平台是后端配置，不是这里该猜的）
    await ensure().catch(() => {})
    await refresh()
    pollTimer = setInterval(refresh, 3000)
  })

  // 换平台 = 换一条流水线：上一平台的日志、事件、组合队列一律不留
  watch(platform, (next, prev) => {
    if (!prev || !next || next === prev) return
    resetForPlatformChange()
    refresh()
  })

  onUnmounted(() => {
    if (pollTimer) clearInterval(pollTimer)
  })

  return { run, combinations, logs, duty, events, connected, activity, refresh, clearLogs }
}
