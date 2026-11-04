/**
 * 任务页的 SSE 事件：文案与订阅清单来自同一处。
 *
 * 为什么合成一份：原来订阅清单在 useRunStream.ts、文案在 task.vue 的 describe()，
 * 两处各写一遍事件名。漏订阅的后果是**静默**的 —— 事件到了浏览器但没有监听器，
 * 页面上什么都不会发生（run.standby 与 run.rescan 漏过一次）；漏文案的后果同样静默 ——
 * 落到 default 分支，界面上直接显示裸露的 `run.something`。
 *
 * 合成之后这两件事在结构上不可能不一致：订阅清单就是 Object.keys(TEXT)。
 * 后端是否真的在发这些事件，由 server 侧的 SseEventContractTest 双向比对。
 */

// 界面上不出现裸的 DRY_RUN：那是内部枚举，不是一个能让人看懂「这意味着什么」的说法
function modeText(m?: string) {
  return m === 'DRY_RUN' ? '演练模式' : '正常投递'
}

// 事件里的 reason 是内部代号，界面上要变成一句能读懂的话
function stopReasonText(reason: string) {
  return ({
    QUOTA: '今天主动联系的名额已经用完',
    EVAL_LIMIT: '今天查看岗位的数量已达上限',
    COMBOS_EXHAUSTED: '所有搜索组合都跑完了',
    // 和「跑完了」必须分开：超时是没看完就停下，断点留在原地
    COMBINATION_TIMEOUT: '组合时间上限到了，还有岗位没看完',
    // 未启用扫描的平台一启动就是待命，这个原因下「扫描结束」不成立（spec 0005）
    SCAN_DISABLED: '这个平台没有开启「搜索岗位」'
  } as any)[reason] || reason
}

function rescanReasonText(reason: string) {
  return ({
    QUOTA_RAISED: '主动联系名额已调高',
    EVAL_LIMIT_RAISED: '查看岗位上限定已调高',
    NEW_DAY: '进入新的一天，搜索组合从头开始'
  } as any)[reason] || reason
}

const TEXT: Record<string, (d: any) => string> = {
  'run.started': (d) => `任务已启动（${modeText(d.mode)}）`,

  'run.waiting': (d) =>
    // 两种挂起来源文案必须分开：启动时就不在窗口内 vs 跑着跑着到点了。
    // 混成一句「不在值守时段」，到点挂起会被读成任务被中断
    d.reason === 'DUTY_END'
      ? `自动回复时段已结束（${d.window}，${d.at}），任务先暂停；这一轮还没有结束`
      : `现在不在自动回复时段（${d.window}，${d.at}），任务已排队等待`,

  'run.standby': (d) =>
    // 未启用扫描的平台从未扫过，「扫描结束（…）」这句与事实不符：它只有在扫描真的
    // 中止时才成立（spec 0005 §3.5 的文案要求）
    d.reason === 'SCAN_DISABLED'
      ? `没有开启「搜索岗位」，直接开始处理未读消息：主动联系 ${d.delivered} / 已查看 ${d.evaluated}`
      : d.reason === 'COMBINATION_TIMEOUT'
        ? `搜索暂停（${stopReasonText(d.reason)}），继续处理未读消息：主动联系 ${d.delivered} / 已查看 ${d.evaluated}`
        : `搜索结束（${stopReasonText(d.reason)}），继续处理未读消息：主动联系 ${d.delivered} / 已查看 ${d.evaluated}`,

  'run.rescan': (d) => `继续搜索：${rescanReasonText(d.reason)}`,

  'run.awakened': (d) => `自动回复时段已到（${d.at}），开始执行排队的任务`,

  'combination.started': (d) =>
    `开始第 ${d.seq + 1} 个搜索组合：${d.city || '不限'} / ${d.keyword || '不限'}`,

  'job.evaluated': (d) =>
    `${d.decision === 'PASS' ? '决定投递' : d.decision === 'AI_REJECT' ? '决定不投' : '判断失败'}：` +
    `${d.jobName} @ ${d.company}${d.reason ? ' — ' + d.reason : ''}`,

  'delivery.updated': (d) =>
    `投递结果 ${d.status}：${d.jobName}${d.failureReason ? ' — ' + d.failureReason : ''}`,

  // 打开 AI 模式后，一次扫描可能既没有回复也没有未命中，但有待重试/已失败的任务 ——
  // 那才是需要人看见的部分，不能因为前两个数是 0 就显示成「什么也没发生」
  'unread.detected': (d) =>
    d.dryRunWouldReply
      // 演练下 replied 恒为 0。显示成「回复 N」等于告诉操作者消息已经发出去了，
      // 而这正是这次要消灭的错觉。
      ? `检查消息（演练）：${d.threads} 个未读，本来会回复 ${d.dryRunWouldReply} 个，没匹配到规则 ${d.unmatched} 个`
      : `检查消息：${d.threads} 个未读，已回复 ${d.replied} 个，没匹配到规则 ${d.unmatched} 个` +
        (d.retried ? `，重试 ${d.retried} 个（失败 ${d.failed} 个）` : ''),

  'circuit.opened': (d) => `已自动停止：${d.kind} — ${d.message}`,

  'run.finished': (d) => `任务结束：${d.status}（主动联系 ${d.delivered}，已查看 ${d.evaluated}）`
}

/** 订阅清单。useRunStream 只订阅这些名字，别的类型不会到达 describeEvent。 */
export const RUN_EVENT_TYPES = Object.keys(TEXT)

/**
 * 一条事件变成一行中文。
 *
 * 未知类型原样返回事件名 —— 不吞掉。这比默认返回空串有用：界面上出现 `run.something`
 * 就是「后端新增了事件、前端没接」的直接证据，而空串只会让人以为是没发生。
 */
export function describeEvent(type: string, data: any): string {
  const render = TEXT[type]
  return render ? render(data || {}) : type
}
