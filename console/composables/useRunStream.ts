export interface StreamEvent {
  type: string
  data: any
  at: number
}

const EVENT_TYPES = [
  'run.started',
  // 挂起与唤醒：白名单漏一个是静默失效，前端只会「什么都没发生」
  'run.waiting',
  'run.awakened',
  'combination.started',
  'job.evaluated',
  'delivery.updated',
  'unread.detected',
  'circuit.opened',
  'duty.ended',
  'run.finished'
]

/**
 * SSE 客户端。断线后指数退避重连（1s 起，×1.7，上限 30s，带 30% 抖动），
 * 并用 lastEventId 让服务端补发断点之后的事件。
 */
export function useRunStream() {
  const events = ref<StreamEvent[]>([])
  const connected = ref(false)

  let source: EventSource | null = null
  let runId: number | null = null
  let lastEventId: string | null = null
  let retry = 1000
  let timer: ReturnType<typeof setTimeout> | null = null
  let closed = false

  function push(type: string, payload: any) {
    events.value.push({ type, data: payload, at: Date.now() })
    if (events.value.length > 200) events.value.splice(0, events.value.length - 200)
  }

  function open(id: number) {
    close()
    closed = false
    runId = id
    connect()
  }

  function connect() {
    if (closed || runId === null) return
    let url = `/api/runs/${runId}/events`
    if (lastEventId) url += `?lastEventId=${encodeURIComponent(lastEventId)}`
    source = new EventSource(url)

    source.onopen = () => {
      connected.value = true
      retry = 1000
    }

    EVENT_TYPES.forEach((type) => {
      source?.addEventListener(type, (e: MessageEvent) => {
        if (e.lastEventId) lastEventId = e.lastEventId
        let payload: any = e.data
        try {
          payload = JSON.parse(e.data)
        } catch {
          /* 保持原始文本 */
        }
        push(type, payload)
      })
    })

    source.onerror = () => {
      connected.value = false
      source?.close()
      source = null
      if (closed) return
      const jitter = 0.7 + Math.random() * 0.6
      const delay = Math.min(retry * jitter, 30000)
      retry = Math.min(retry * 1.7, 30000)
      timer = setTimeout(connect, delay)
    }
  }

  function close() {
    closed = true
    connected.value = false
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    source?.close()
    source = null
  }

  function clear() {
    events.value = []
    lastEventId = null
  }

  onUnmounted(close)

  return { events, connected, open, close, clear }
}
