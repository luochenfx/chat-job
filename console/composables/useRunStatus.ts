/**
 * 后端返回的状态值 → 界面上显示什么，全部在这里一处写定。
 *
 * <p>为什么收在一起：Run 状态、处置、未读任务、搜索组合、质量判断、未命中原因、告警档位、
 * 运行日志级别原本分别在 7 个页面里各写一张表，标签和配色还常常拆成两个函数。加一个取值
 * 要改好几处，漏一处就是「同一个状态在两页显示成两种颜色」，编译器和浏览器都不会报错。
 * 现在加一个取值只改这里一行，页面只负责把拿到的标签和颜色摆上去。
 *
 * <p>这里只放「一个取值对应什么标签、什么颜色、什么记号」，以及按取值分支的短文案。
 * 时间戳怎么显示、平台名怎么取、演练模式怎么说、平台开关的长提示都不在这里，各有各的住处。
 *
 * <p>状态标签的语义色只有三档：ok / warn / danger，空串表示不着色。告警提示条多一档
 * muted，那是它自己的样式，不是状态配色。
 */

export type ViewTone = '' | 'ok' | 'warn' | 'danger'

/**
 * Run 状态分成的几类。界面按类别分支，不再直接比较库里的状态字面量 ——
 * 否则加一个状态就要把所有页面里比过字面量的地方找一遍。
 */
export type RunKind =
  | 'created'
  | 'waiting'
  | 'running'
  | 'standby'
  | 'paused'
  | 'finished'
  | 'failed'
  | 'unknown'

export interface RunStatusView {
  /** 这一轮还没结束。统计、按钮、轮询都按它判断，不要各处再列一遍状态数组 */
  active: boolean
  /** 给人看的名字，界面上不出现裸枚举 */
  label: string
  tone: ViewTone
  kind: RunKind
  /** 有没有可暂停的东西：等待时段与已暂停都没有 */
  canPause: boolean
  canStop: boolean
  /** 要不要做陈旧判定。暂停与挂起本来就该安静，判了只会误报 */
  judgedIdle: boolean
}

const RUN_TABLE: Record<string, RunStatusView> = {
  CREATED: { active: true, label: '准备中', tone: '', kind: 'created', canPause: false, canStop: true, judgedIdle: false },
  WAITING_DUTY: { active: true, label: '等待时段开始', tone: 'warn', kind: 'waiting', canPause: false, canStop: true, judgedIdle: false },
  RUNNING: { active: true, label: '运行中', tone: 'warn', kind: 'running', canPause: true, canStop: true, judgedIdle: true },
  // 扫描停了，值守还在跑：按钮与 RUNNING 一致，因为「停止」才是真正结束值守期
  STANDBY: { active: true, label: '值守待命', tone: 'warn', kind: 'standby', canPause: true, canStop: true, judgedIdle: true },
  PAUSED: { active: true, label: '已暂停', tone: 'warn', kind: 'paused', canPause: false, canStop: true, judgedIdle: false },
  COMPLETED: { active: false, label: '已完成', tone: 'ok', kind: 'finished', canPause: false, canStop: false, judgedIdle: false },
  // 历史数据才有：V12 之后配额用完来自 STANDBY，不再写这个状态
  QUOTA_REACHED: { active: false, label: '联系名额已用完', tone: 'ok', kind: 'finished', canPause: false, canStop: false, judgedIdle: false },
  CIRCUIT_OPEN: { active: false, label: '已自动停止', tone: 'danger', kind: 'finished', canPause: false, canStop: false, judgedIdle: false },
  LOGIN_LOST: { active: false, label: '登录状态失效', tone: 'danger', kind: 'finished', canPause: false, canStop: false, judgedIdle: false },
  CANCELLED: { active: false, label: '已取消', tone: '', kind: 'finished', canPause: false, canStop: false, judgedIdle: false },
  FAILED: { active: false, label: '失败', tone: 'danger', kind: 'failed', canPause: false, canStop: false, judgedIdle: false }
}

export function runStatusView(status?: string | null): RunStatusView {
  if (status && RUN_TABLE[status]) return RUN_TABLE[status]
  return {
    active: false, label: status || '—', tone: '', kind: 'unknown',
    canPause: false, canStop: false, judgedIdle: false
  }
}

// ------------------------------------------------------------ 岗位的处置

export interface DeliveryView {
  label: string
  tone: ViewTone
  /** 失败、未联系、需人工这几种才给「重新投递」 */
  canReset: boolean
}

const DELIVERY_TABLE: Record<string, DeliveryView> = {
  DELIVERING: { label: '投递中', tone: 'warn', canReset: false },
  DELIVERED: { label: '已投递', tone: 'ok', canReset: false },
  FAILED: { label: '失败', tone: 'danger', canReset: true },
  SKIPPED: { label: '未联系', tone: '', canReset: true },
  MANUAL: { label: '需人工处理', tone: 'danger', canReset: true }
}

export function deliveryView(status?: string | null): DeliveryView {
  if (!status) return { label: '未处理', tone: '', canReset: false }
  return DELIVERY_TABLE[status] || { label: status, tone: '', canReset: false }
}

/** HR 活跃度档位 → 配色。档位由后端归一，前端只认这几个说法 */
export function activeLevelTone(level?: string): ViewTone {
  if (!level || level === '未知') return ''
  if (['在线', '刚刚', '今日', '3日内'].includes(level)) return 'ok'
  if (['2月内', '半年前'].includes(level)) return 'danger'
  return 'warn'
}

// ------------------------------------------------------------ 未读任务

export interface UnreadTaskView {
  label: string
  tone: ViewTone
  /** 只有「已放弃」才需要手动重试 */
  canRetry: boolean
}

const UNREAD_TASK_TABLE: Record<string, UnreadTaskView> = {
  PENDING: { label: '待处理', tone: '', canRetry: false },
  PROCESSING: { label: '处理中', tone: '', canRetry: false },
  FAILED: { label: '重试中', tone: 'warn', canRetry: false },
  GAVE_UP: { label: '已放弃', tone: 'danger', canRetry: true },
  DONE: { label: '已完成', tone: 'ok', canRetry: false }
}

export function unreadTaskView(status?: string): UnreadTaskView {
  return UNREAD_TASK_TABLE[status || ''] || { label: status || '', tone: '', canRetry: false }
}

// ------------------------------------------------------------ 搜索组合

const COMBO_TABLE: Record<string, { label: string; tone: ViewTone }> = {
  PENDING: { label: '未开始', tone: '' },
  RUNNING: { label: '进行中', tone: 'warn' },
  DONE: { label: '已完成', tone: 'ok' },
  SKIPPED: { label: '已跳过', tone: 'warn' },
  // 与 DONE 分开：超时的组合只剩一部分卡没看，下次扫描接着扫，不是「跳过」
  TIMEOUT: { label: '超时未跑完', tone: 'warn' },
  FAILED: { label: '失败', tone: 'danger' },
  INTERRUPTED: { label: '已中断', tone: 'warn' }
}

/**
 * 组合状态 → 标签。
 *
 * <p>「进行中」只有在 Run 还活着时才成立：服务被强杀后库里可能还留着 RUNNING
 * （后端重启收尾会补成 INTERRUPTED，这里再兜一层旧数据），那一行绝不能显示成还在扫。
 */
export function comboView(c: any, runActive: boolean): { label: string; tone: ViewTone } {
  const status = c.status === 'RUNNING' && !runActive ? 'INTERRUPTED' : c.status
  return COMBO_TABLE[status] || { label: status, tone: 'warn' }
}

// ------------------------------------------------------------ 质量判断点

export interface VerdictView {
  mark: string
  tone: ViewTone
}

/** 看不出（UNKNOWN）用灰记号而不是「✗」：没有写福利不等于福利差 */
export function verdictView(verdict?: string): VerdictView {
  if (verdict === 'YES') return { mark: '✓', tone: 'ok' }
  if (verdict === 'NO') return { mark: '✗', tone: 'danger' }
  return { mark: '?', tone: '' }
}

// ------------------------------------------------------------ 运行日志级别

/** 库里只存 INFO / WARN / ERROR，按它选颜色（CONTEXT.md 的 RunLogLevel） */
export function runLogLevelColor(level?: string): string {
  if (level === 'WARN') return 'var(--warn)'
  if (level === 'ERROR') return 'var(--danger)'
  return 'inherit'
}

// ------------------------------------------------------------ 没有回复的消息

const UNMATCHED_REASON: Record<string, string> = {
  NO_SUBSTANCE: '没有需要回答的内容',
  SENSITIVE_TOPIC: '话题敏感',
  ALREADY_HANDLED: '之前已经聊过',
  LOW_CONFIDENCE: 'AI 不确定怎么回',
  HUMAN_REQUIRED: '需要你自己回'
}

const BLOCKED_REASON: Record<string, string> = {
  TONE: '听起来像 AI 写的',
  CONTACT: '含联系方式',
  SENSITIVE: '敏感词',
  TOO_LONG: '字数太多',
  FALSE_CLAIM: '说了没做过的事'
}

/** 把落库的动作码翻成人话。直接显示 NO_SUBSTANCE 之类等于让人去翻代码 */
export function unmatchedReasonText(m: any): string {
  const action = m.action || ''
  if (action === 'AI_SKIPPED') {
    const why = UNMATCHED_REASON[m.reasonCode] || m.reasonCode || '没有给出原因'
    return `AI 判断不用回 · ${why}`
  }
  if (action === 'AI_FAILED') return 'AI 没有正常返回'
  if (action.startsWith('BLOCKED_')) {
    const kind = action.slice('BLOCKED_'.length)
    return `发送前被拦下 · ${BLOCKED_REASON[kind] || kind}`
  }
  if (action === 'UNMATCHED') return '没有匹配到规则'
  return action || '—'
}

// ------------------------------------------------------------ 未读告警提示条

/** 四档都要有话说：DISABLED 是「我关了它」，INSUFFICIENT 是「还没法判断」 */
export function alertTextView(s: any): { text: string; tone: 'muted' | 'ok' | 'warn' } {
  if (!s) return { text: '', tone: 'muted' }
  const joined = `最近 7 天 AI 判断了 ${s.total} 次，其中决定不回 ${s.skipped} 次`
  switch (s.state) {
    case 'DISABLED':
      return { text: `提醒已关闭（阈值填了 0）。${joined}`, tone: 'muted' }
    case 'INSUFFICIENT':
      return { text: `最近 7 天 AI 判断了 ${s.total} 次，样本不足（至少 ${s.minSample} 次才能比较），暂不判断`, tone: 'muted' }
    case 'ALERT':
      return { text: `${joined}（${s.pct}%），超过了设定的 ${s.thresholdPct}%`, tone: 'warn' }
    default:
      return { text: `${joined}（${s.pct}%），设定阈值 ${s.thresholdPct}%`, tone: s.state === 'OK' ? 'ok' : 'muted' }
  }
}
