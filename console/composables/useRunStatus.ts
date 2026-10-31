/**
 * Run 状态 → 界面表现的唯一映射。
 *
 * <p>为什么要有这个文件：状态原本在三个页面各写一遍（任务页的活跃数组、概览页的 statusText
 * 与活跃数组）。加一个 STANDBY 要改三处，漏一处就是「统计说运行中、按钮却显示启动」——
 * 这种不一致不会被测试发现，只会在真实现场出现。
 *
 * <p>只放「怎么显示」，不放业务规则：陈旧阈值这类判断仍由后端算好下发。
 */
export type RunTone = '' | 'ok' | 'warn' | 'danger'

export interface RunStatusView {
  /** 这一轮还没结束。统计、按钮、轮询都按它判断，不要各处再列一遍状态数组 */
  active: boolean
  /** 给人看的名字，界面上不出现裸枚举 */
  label: string
  tone: RunTone
  /** 有没有可暂停的东西：等待时段与已暂停都没有 */
  canPause: boolean
  canStop: boolean
  /** 要不要做陈旧判定。暂停与挂起本来就该安静，判了只会误报 */
  judgedIdle: boolean
}

const TABLE: Record<string, RunStatusView> = {
  CREATED: { active: true, label: '准备中', tone: '', canPause: false, canStop: true, judgedIdle: false },
  WAITING_DUTY: { active: true, label: '等待时段开始', tone: 'warn', canPause: false, canStop: true, judgedIdle: false },
  RUNNING: { active: true, label: '运行中', tone: 'warn', canPause: true, canStop: true, judgedIdle: true },
  // 扫描停了，值守还在跑：按钮与 RUNNING 一致，因为「停止」才是真正结束值守期
  STANDBY: { active: true, label: '值守待命', tone: 'warn', canPause: true, canStop: true, judgedIdle: true },
  PAUSED: { active: true, label: '已暂停', tone: 'warn', canPause: false, canStop: true, judgedIdle: false },
  COMPLETED: { active: false, label: '已完成', tone: 'ok', canPause: false, canStop: false, judgedIdle: false },
  // 历史数据才有：V12 之后配额用完来自 STANDBY，不再写这个状态
  QUOTA_REACHED: { active: false, label: '联系名额已用完', tone: 'ok', canPause: false, canStop: false, judgedIdle: false },
  CIRCUIT_OPEN: { active: false, label: '已自动停止', tone: 'danger', canPause: false, canStop: false, judgedIdle: false },
  LOGIN_LOST: { active: false, label: '登录状态失效', tone: 'danger', canPause: false, canStop: false, judgedIdle: false },
  CANCELLED: { active: false, label: '已取消', tone: '', canPause: false, canStop: false, judgedIdle: false },
  FAILED: { active: false, label: '失败', tone: 'danger', canPause: false, canStop: false, judgedIdle: false }
}

export function runStatusView(status?: string | null): RunStatusView {
  if (status && TABLE[status]) return TABLE[status]
  return { active: false, label: status || '—', tone: '', canPause: false, canStop: false, judgedIdle: false }
}
