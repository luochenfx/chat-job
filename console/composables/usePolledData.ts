import type { Ref } from 'vue'

/**
 * 一次取数的生命周期：挂载取一次、按节奏重取、失败保留上一份数据、卸载清表。
 *
 * <p>收的是「一次取数返回什么」，不是接口路径：概览页在请求前要先取平台清单，
 * 岗位页要把两个接口的响应合成一份视图，质量页要顺带拉一次清单。这些差别留给各自的
 * 取数函数，模块不认识它们，也不替调用处决定拿到数据后写进哪个状态。
 *
 * <p>intervalMs 传 0 表示只在挂载时取一次，不设定时器。任务页那一轮的实时视图走
 * useRunSession（有事件流、日志续拉与切平台清理），形状不同，不走这里。
 *
 * <p>busy 是只读的「正在取数」，调用处不能拿它当「动作进行中」：一次轮询、或者动作
 * 末尾那次重读结束，都会把它复位，而那时动作可能还没结束。要表达动作进行中，调用处
 * 自己建一个标记，两个一起决定按钮禁用。
 *
 * <p>失败只弹提示、保留上一份数据：一次取数失败不该把已经渲染出来的内容清空；
 * 需要重试的地方由调用处显式调 refresh，有轮询的下一次就是重试。
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
    if (intervalMs > 0) timer = setInterval(refresh, intervalMs)
  })

  onUnmounted(() => {
    if (timer) clearInterval(timer)
    timer = null
  })

  return { data, busy: computed(() => busy.value), refresh }
}
