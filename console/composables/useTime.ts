/**
 * 时间戳怎么显示给人看的唯一实现。
 *
 * <p>分两套：列表里用 {@link relTime}（相对，扫一眼就知道新旧），悬停用 {@link fullTime}
 * （完整，补回相对表达丢掉的精度）。以前是各写各的 —— 日志页直接 {@code replace('T',' ')}，
 * 规则页再 {@code slice(0,16)}，两处都只给绝对时间，「多久之前」要读者自己心算。
 *
 * <p>库里存的是本地时间、不带时区后缀（{@code yyyy-MM-dd'T'HH:mm:ss}），
 * 而 {@code new Date(str)} 恰好按浏览器本地时区解析同一个不带后缀的值 ——
 * 两边语义对得上，不需要额外换算。
 */

const pad = (n: number) => (n < 10 ? '0' + n : String(n))

/**
 * 完整时间：`2026-10-31 20:05:03`。
 *
 * <p>空值或非法值返回空串而不是 `—`：它只用在 {@code title} 上，空串不渲染属性，
 * 而 `—` 会让人以为「时间就是一个横杠」。
 */
export function fullTime(ts: string | null | undefined): string {
  if (!ts) return ''
  const at = new Date(ts).getTime()
  if (Number.isNaN(at)) return ''
  const d = new Date(at)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
    + ` ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
}

/**
 * 相对时间：刚刚 / N 分钟前 / 今天 HH:mm / 昨天 HH:mm / MM-DD HH:mm。
 *
 * <p>超过一小时就不再往下数「N 小时前」：岗位列表要回答的是「这是今天的还是上周的」，
 * 而「5 小时前」碰上夜里扫到的岗位反而要读者反推日期。跨年补回年份，否则 MM-DD 会误导。
 *
 * <p>未来时间戳（机器时钟偏差）落进「刚刚」，不显示成负数。
 */
export function relTime(ts: string | null | undefined, nowMs: number): string {
  if (!ts) return '—'
  const at = new Date(ts).getTime()
  if (Number.isNaN(at)) return '—'
  const diff = nowMs - at
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  const d = new Date(at)
  const now = new Date(nowMs)
  const hhmm = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  if (sameDay(d, now)) return `今天 ${hhmm}`
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1)
  if (sameDay(d, yesterday)) return `昨天 ${hhmm}`
  if (d.getFullYear() !== now.getFullYear()) {
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  }
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hhmm}`
}
