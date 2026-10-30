/**
 * 当前启用的平台。
 *
 * 平台是**装配期**决定的（后端 chat-job.active-platform，改完要重启），前端不做任何猜测：
 * 一律从 `/api/system/platform` 取。之前这些地方写死了 `boss`，接第二个平台时会出现
 * 「界面在配智联、实际读写的是 BOSS 的配置」——那种错很难从界面上看出来，
 * 所以宁可每次多一次请求。
 */
export function usePlatform() {
  const { call } = useApi()

  const info = useState<any>('platform-info', () => null)
  let inflight: Promise<any> | null = null

  async function ensure() {
    if (info.value) return info.value
    if (!inflight) {
      inflight = call('/system/platform')
        .then((data) => {
          info.value = data
          return data
        })
        .finally(() => {
          inflight = null
        })
    }
    return inflight
  }

  // 兜底用 boss：与后端 @DefaultValue("boss") 一致。取不到时宁可退回默认平台，
  // 也不要让界面卡在空白——那会让人以为服务没起来
  const platform = computed<string>(() => info.value?.platform || 'boss')
  const label = computed<string>(() => info.value?.label || 'BOSS 直聘')
  const note = computed<string>(() => info.value?.note || '')

  /** 该平台是否支持某个搜索条件（city / keyword / salary / experience / industry …） */
  function supports(key: string) {
    const list: string[] = info.value?.filters || ['city', 'keyword']
    return list.includes(key)
  }

  return { ensure, platform, label, note, supports }
}
