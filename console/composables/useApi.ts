/** 极简 API 客户端：只做 JSON 与错误消息提取，不引入 axios */
export function useApi() {
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

  return { call }
}
