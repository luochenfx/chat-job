import type { Ref } from 'vue'

/**
 * 极简 API 客户端：只做 JSON 与错误消息提取，不引入 axios。
 *
 * <p>另提供 {@link attempt}，把「失败弹一句错误、忙碌标记成对设置」收在这里。
 * 以前每个页面各写一遍 try/catch/finally，37 处一模一样；成功后要说什么、要重读什么、
 * 要不要确认，都是每个动作自己的业务，仍留在调用处，不塞进参数里。
 */
export function useApi() {
  const { show } = useToast()

  async function call<T = any>(path: string, options: {
    method?: string
    body?: unknown
    query?: Record<string, string | number | undefined>
  } = {}): Promise<T> {
    let url = '/api' + path
    if (options.query) {
      const params = new URLSearchParams()
      Object.entries(options.query).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') params.set(k, String(v))
      })
      const qs = params.toString()
      if (qs) url += (url.includes('?') ? '&' : '?') + qs
    }
    const res = await fetch(url, {
      method: options.method || 'GET',
      headers: options.body ? { 'Content-Type': 'application/json' } : undefined,
      body: options.body ? JSON.stringify(options.body) : undefined
    })
    const text = await res.text()
    let data: any = null
    try {
      data = text ? JSON.parse(text) : null
    } catch {
      data = text
    }
    if (!res.ok) {
      const message = data && typeof data === 'object' ? data.message : String(data || res.statusText)
      throw new Error(message || `HTTP ${res.status}`)
    }
    return data as T
  }

  /**
   * 一次带错误提示的操作：失败已经弹过提示，不必调用处再接一次。
   *
   * @param busy 传页面的忙碌标记，进来置 true、结束一定复位；不传就不动它
   * @returns 成功 true、失败 false。调用处一般不必看，成功后要做的事直接写在 work 里
   */
  async function attempt(work: () => Promise<void>, options: { busy?: Ref<boolean> } = {}): Promise<boolean> {
    const flag = options.busy
    if (flag) flag.value = true
    try {
      await work()
      return true
    } catch (e: any) {
      show(e.message, true)
      return false
    } finally {
      if (flag) flag.value = false
    }
  }

  return { call, attempt }
}
