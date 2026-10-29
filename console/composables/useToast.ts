const state = reactive({ text: '', error: false })
let timer: ReturnType<typeof setTimeout> | null = null

/** 单例 toast：同一时刻只显示一条，重复调用会刷新内容并重置计时 */
export function useToast() {
  function show(text: string, error = false) {
    state.text = text
    state.error = error
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      state.text = ''
    }, error ? 5200 : 2600)
  }
  return { toast: state, show }
}
