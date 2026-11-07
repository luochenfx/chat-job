import type { Ref } from 'vue'

/**
 * 取数 + 轮询 + 空态。
 *
 * <p>概览页原来自己写了三件套：`ref` 装数据、`setInterval` 每 5 秒重拉、卸载时清表。
 * 第 4 步之后这一页要同时读「总览」与「7 日走势」（两者在同一份快照里），
 * 这类代码再复制一遍就没人记得改两处。抽出来的只有这三件事，
 * `useApi` / `useToast` / `usePlatform` 的语义一概不动。
 *
 * <p>收的是<b>取数的函数</b>而不是接口路径：概览页在请求前要先 `ensure()` 平台清单，
 * 把这段留成调用方的一行，composable 就不必认识 `usePlatform`。
 *
 * <p>空态就是 `data === null` —— 第一次取回来之前没有值，页面照旧显示占位文案；
 * 取数失败<b>保留上一份数据</b>，一次失败的轮询不该把已经渲染出来的卡片清空。
 * 失败只弹 toast，不重试：这是每 5 秒一次的轮询，下一次就是重试。
 */
export function usePolledData<T>(fetcher: () => Promise<T>, intervalMs: number) {
  const { attempt } = useApi()
  const data = ref<T | null>(null) as Ref<T | null>
  const busy = ref(false)
  let timer: ReturnType<typeof setInterval> | null = null

  async function refresh() {
    await attempt(async () => {
      data.value = await fetcher()
    }, { busy })
  }

  onMounted(() => {
    // 挂载时只请求一次：定时器从下一次开始，不能出现「进页面打两次」
    refresh()
    timer = setInterval(refresh, intervalMs)
  })

  onUnmounted(() => {
    if (timer) clearInterval(timer)
    timer = null
  })

  return { data, busy, refresh }
}
