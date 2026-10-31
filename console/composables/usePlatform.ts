/**
 * 平台清单与「当前看哪个平台」。
 *
 * <p>2c 之前这里问的是「唯一平台是谁」，回答只有一个。多平台并行之后语义变成两件事：
 * <b>有哪些平台</b>（清单，来自 `/api/system/platforms`）与 <b>这一页在看哪个</b>（选择，
 * 由 URL query 与 localStorage 决定）。前端仍然不猜平台：清单里没有的 slug 一律不认。
 *
 * <p>选中项写进 `?platform=`，并记忆到 localStorage：刷新、后退、分享链接都停在同一平台。
 * 首次进入取 localStorage，没有则取清单第一个 —— <b>不用</b>「当前有活跃 Run 的平台」
 * 当默认，那个值会随运行状态变化，页面内容会自己跳。
 */

export interface PlatformDesc {
  platform: string
  label: string
  filters?: string[]
  note?: string
  enabled?: boolean
  /**
   * 扫描开关与值守开关（spec 0005）。两者都是「启动之后做什么」，与 enabled
   * 的「能不能启动」并列且互不派生。缺失按开处理 —— 与后端 `platform_config`
   * 两列的建表默认值一致。
   */
  scanEnabled?: boolean
  watchEnabled?: boolean
}

const STORAGE_KEY = 'chat-job.platform'

export function usePlatform() {
  const { call } = useApi()
  const route = useRoute()
  const router = useRouter()

  // 清单全局一份：五个页面与侧边栏共用，不必各拉一次
  const platforms = useState<PlatformDesc[]>('platform-list', () => [])
  let inflight: Promise<PlatformDesc[]> | null = null

  async function ensure(): Promise<PlatformDesc[]> {
    if (platforms.value.length) return platforms.value
    if (!inflight) {
      inflight = call<PlatformDesc[]>('/system/platforms')
        .then((list) => {
          platforms.value = list || []
          return platforms.value
        })
        .finally(() => {
          inflight = null
        })
    }
    return inflight
  }

  /**
   * 强制重拉清单。只有一处会用到它：配置页刚改过某个平台的开关 ——
   * 清单里的 enabled 是缓存的，不刷新的话分段控件上的「已停用」会等到下次进页面才出现。
   */
  async function refresh(): Promise<PlatformDesc[]> {
    const list = await call<PlatformDesc[]>('/system/platforms')
    platforms.value = list || []
    return platforms.value
  }

  function stored(): string {
    // 构建期（nuxt generate 预渲染外壳）没有 window，读不到就当没有记忆
    if (typeof window === 'undefined') return ''
    try {
      return window.localStorage.getItem(STORAGE_KEY) || ''
    } catch {
      return ''
    }
  }

  function known(slug?: string | null): boolean {
    return !!slug && platforms.value.some((p) => p.platform === slug)
  }

  /**
   * 当前平台：URL → localStorage → 清单第一个。
   *
   * <p>URL 优先是为了「分享链接 / 后退」能落在同一平台；清单为空时返回空串，
   * 页面据此显示空态而不是先按某个平台发请求再改口。
   */
  const platform = computed<string>(() => {
    const fromQuery = typeof route.query.platform === 'string' ? route.query.platform : ''
    if (known(fromQuery)) return fromQuery
    const fromStore = stored()
    if (known(fromStore)) return fromStore
    return platforms.value[0]?.platform || ''
  })

  const current = computed<PlatformDesc | null>(
    () => platforms.value.find((p) => p.platform === platform.value) || null
  )
  const label = computed<string>(() => current.value?.label || '')
  const note = computed<string>(() => current.value?.note || '')

  /** 某平台 slug 的展示名。查不到就回 slug —— 宁可露出内部标识，也不要显示空白 */
  function labelOf(slug?: string): string {
    if (!slug) return ''
    return platforms.value.find((p) => p.platform === slug)?.label || slug
  }

  /** 该平台是否支持某个搜索条件（city / keyword / salary / experience / industry …） */
  function supports(key: string): boolean {
    const list = current.value?.filters || ['city', 'keyword']
    return list.includes(key)
  }

  /**
   * 切到某个平台：写 localStorage 与 URL query，不碰任何业务数据 ——
   * 各页面自己决定「换了平台要清掉什么」（任务页的日志与 SSE 必须清，岗位页不必）。
   */
  async function select(slug: string) {
    if (!known(slug) || slug === platform.value) return
    if (typeof window !== 'undefined') {
      try {
        window.localStorage.setItem(STORAGE_KEY, slug)
      } catch {
        /* 隐私模式下写不进去：URL 仍然是准的，不阻断切换 */
      }
    }
    await router.replace({ query: { ...route.query, platform: slug } })
  }

  return { platforms, ensure, refresh, platform, current, label, note, labelOf, supports, select, known }
}
