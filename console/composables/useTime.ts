/**
 * 时间戳怎么显示给人看，全部在这里一处写定。
 *
 * <p>库里存的是本地时间、不带时区后缀（{@code yyyy-MM-dd'T'HH:mm:ss}），
 * 把 T 换成空格就是给人看的样子；截到哪一位由调用处传一个档位，不再自己 slice。
 * 以前六个页面各截各的，同一个时刻在概览页是「11-07 23:14」、规则页是
 * 「2026-11-07 23:14」、质量页是「2026-11-07 23:14:20」，改一次口径要搜六个文件。
 *
 * <p>另有两类输入不塞进 {@link stamp}：{@link relTime} 要拿当前时钟算「多久之前」，
 * 它是纯计算，多久走一次字由页面定；{@link clockTime} 收的是 SSE 事件里的毫秒数，
 * 不是库里的时间字符串。合进一个函数就要同时接字符串、时钟和数字三种东西。
 *
 * <p>{@code new Date(str)} 恰好按浏览器本地时区解析这个不带后缀的值 ——
 * 两边语义对得上，不需要额外换算。
 */

/** 绝对时间戳显示到哪一位 */
export type TimeStyle =
  | 'full'          // 2026-11-07 23:14:20（原样，保留秒与毫秒）
  | 'date-minute'   // 2026-11-07 23:14
  | 'month-day'     // 11-07 23:14

/**
 * 绝对时间戳 → 显示用的字符串。空值返回空串，由调用处决定没有值时显示什么
 * （「—」「未开始」或者干脆留空），不在这里替所有页面定。
 */
export function stamp(ts: string | null | undefined, style: TimeStyle): string {
  if (!ts) return ''
  const text = ts.replace('T', ' ')
  if (style === 'date-minute') return text.slice(0, 16)
  if (style === 'month-day') return text.slice(5, 16)
  return text
}

/** SSE 事件带的是毫秒数，只要时分秒 */
export function clockTime(at: number): string {
  return new Date(at).toLocaleTimeString('zh-CN', { hour12: false })
}

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
