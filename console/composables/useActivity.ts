import { runStatusView } from './useRunStatus'

/**
 * 「上次动是什么时候」与「现在在干什么」的唯一实现。
 *
 * <p>这段判断原本只写在任务页（299-260 行附近）。2c 之后概览页每张平台卡也要同一句话 ——
 * 两处各写一遍的话，「暂停该不该做陈旧判定」这种规则只要改一处就会分叉，
 * 表现为「概览说卡住了、任务页说正常」。所以抽成一个文件。
 *
 * <p>只做「怎么显示」，不做「阈值该是多少」：阈值（几倍巡检间隔）由后端算好下发，
 * 前端拿它做比较，不重复实现规则。
 */

/** 秒级时钟：让「12 秒前有动作」在两次轮询之间也在走 */
export function useTickClock() {
  const nowMs = ref(Date.now())
  let timer: ReturnType<typeof setInterval> | null = null

  onMounted(() => {
    timer = setInterval(() => {
      nowMs.value = Date.now()
    }, 1000)
  })
  onUnmounted(() => {
    if (timer) clearInterval(timer)
  })

  return nowMs
}

/** 距上次动作过了多少秒；时间戳缺失或不合法时返回 null（而不是 0：0 表示「刚刚」） */
export function idleSeconds(lastActivityAt: string | null | undefined, nowMs: number): number | null {
  if (!lastActivityAt) return null
  const at = new Date(lastActivityAt).getTime()
  if (Number.isNaN(at)) return null
  return Math.max(0, Math.round((nowMs - at) / 1000))
}

/** 秒级数字对人没意义：12 秒 / 3 分钟 / 1 小时 5 分钟 */
export function idleText(seconds: number): string {
  if (seconds < 60) return `${seconds} 秒`
  const total = Math.floor(seconds / 60)
  const h = Math.floor(total / 60)
  const m = total % 60
  if (!h) return `${m} 分钟`
  return m ? `${h} 小时 ${m} 分钟` : `${h} 小时`
}

export interface ActivityView {
  text: string
  tone: '' | 'ok' | 'warn' | 'danger'
  stale: boolean
}

/**
 * 一句话说清「这一轮现在什么状态 + 上次动是什么时候」。
 *
 * @param withLabel 概览卡的卡头已经有状态标签，卡内再说一遍「值守待命」是重复，传 false
 */
export function activityView(
  run: any,
  opts: { nowMs: number; staleAfterSeconds?: number; withLabel?: boolean } = { nowMs: Date.now() }
): ActivityView {
  const { nowMs, staleAfterSeconds, withLabel = true } = opts
  const status = run?.status
  if (!status) return { text: '', tone: '', stale: false }

  const view = runStatusView(status)
  // 暂停与挂起本来就该安静，做陈旧判定只会误报
  if (status === 'PAUSED') return { text: '已暂停：扫描与值守都停', tone: 'warn', stale: false }
  if (status === 'WAITING_DUTY') {
    return { text: '已挂起：值守期没有结束，窗口打开会自动继续', tone: 'warn', stale: false }
  }
  if (!view.active) return { text: `已结束：${view.label}`, tone: '', stale: false }

  const idle = idleSeconds(run?.lastActivityAt, nowMs)
  if (idle == null) return { text: '刚启动，还没有动作', tone: '', stale: false }

  const stale = !!staleAfterSeconds && idle > staleAfterSeconds
  if (stale) {
    const minutes = Math.max(1, Math.floor(idle / 60))
    return { text: `⚠️ 无响应 ${minutes} 分钟（阈值 ${staleAfterSeconds} 秒）`, tone: 'danger', stale: true }
  }

  const ago = `${idleText(idle)}前有动作`
  return {
    text: withLabel ? `${view.label} · ${ago}` : ago,
    tone: withLabel && view.judgedIdle ? 'ok' : '',
    stale: false
  }
}
