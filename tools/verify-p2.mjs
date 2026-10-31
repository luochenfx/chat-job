/**
 * spec 0005 P2（关闭值守）的运行时验收。
 *
 * 单测覆盖不了这一段：`runLoop` / `standbyLoop` / `processCards` 是带 sleep 与真实浏览器调用的
 * 循环，用 fake 只能验证「有没有调 sweep」，验证不了「关掉值守之后还会不会去巡检」。
 * 所以这里对着真实服务跑，判据只看落库的结果：run 行、run_log、combination_run。
 *
 * 三个场景（对应 spec 0005 §5.2 第 4、6 条与 §3.6 的三个生效点）：
 *
 *   A 只扫描（watch=0, scan=1, 正常模式）——期望 COMPLETED / SCAN_DONE，
 *     不进待命、没有 run.standby 横幅。
 *   B 插空巡检（watch=0, 演练模式）——期望处理到 5 张卡以上之后仍然没有一次巡检日志。
 *     用演练模式是因为它不发消息、不投递，而 processCards 的其余动作照常执行。
 *   C 运行中关值守（watch=1 → 扫描结束后进 STANDBY → 改配置关掉）——期望一秒内结束，
 *     COMPLETED / WATCH_DISABLED。巡检间隔在这一步设成 600 秒，这样「立刻结束」只可能
 *     来自睡眠中的每秒检查，而不是间隔刚好到期。
 *
 * 副作用与还原：
 *   - 会临时改该平台的扫描/值守开关、值守时段（改成全天，否则当前时刻可能不在窗口内而直接挂起）、
 *     关键词、滚动轮上限、单组合时间上限、巡检间隔。结束时按开始前读取的整份配置 PUT 回去，
 *     并再读一次确认还原成功 —— 只改一两个字段 PUT 会把其余字段写成默认值（P0 已经踩过）。
 *   - 关键词在 A/C 用一串不会命中任何岗位的字符，是为了让扫描以「0 张卡片」结束：
 *     这一轮要验的是开关，不需要真的投递，也不该在凌晨给 HR 发消息。
 *
 * 用法：
 *   node tools/verify-p2.mjs                 # 跑 A、B、C
 *   node tools/verify-p2.mjs --only A        # 只跑某个场景
 *   node tools/verify-p2.mjs --platform boss
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const OUT = path.join(ROOT, 'build/verify-p2');

const argv = process.argv.slice(2);
const argOf = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
};
const BASE = argOf('--base', process.env.CHAT_JOB_BASE || 'http://127.0.0.1:8099');
const PLATFORM = argOf('--platform', 'boss');
const ONLY = argOf('--only', null);

/** 扫描要「有内容」时用真实关键词，要「立刻扫完」时用这串不存在的字符 */
const NO_HIT_KEYWORD = 'zzzz-p2-no-such-job';
const TERMINAL = new Set(['COMPLETED', 'FAILED', 'CANCELLED', 'LOGIN_LOST', 'CIRCUIT_OPEN', 'QUOTA_REACHED']);

/** 日志前缀用本地时间：报告里的 run_log 时间戳也是本地时间，混用 UTC 会让两边对不上 */
const log = (...a) => console.log(`[${new Date().toLocaleTimeString('zh-CN', { hour12: false })}]`, ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(method, url, body) {
  const res = await fetch(`${BASE}${url}`, {
    method,
    headers: body === undefined ? undefined : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* 非 JSON 由调用方按 text 处理 */ }
  if (!res.ok) {
    throw new Error(`${method} ${url} → ${res.status} ${text.slice(0, 400)}`);
  }
  return json;
}

/** GET 回发的只读字段，PUT 不接受，必须剔除后再回传 */
const READ_ONLY = new Set(['platform', 'activeLevelOptions']);

const getConfig = () => api('GET', `/api/config/platform/${PLATFORM}`);

async function putConfig(current, patch) {
  const body = {};
  for (const [k, v] of Object.entries({ ...current, ...patch })) {
    if (!READ_ONLY.has(k)) body[k] = v;
  }
  await api('PUT', `/api/config/platform/${PLATFORM}`, body);
  return getConfig();
}

const startRun = (mode = 'NORMAL') => api('POST', `/api/runs?platform=${PLATFORM}`, { mode });
const current = () => api('GET', `/api/runs/current?platform=${PLATFORM}`);
const logsOf = (id) => api('GET', `/api/runs/${id}/logs?limit=500`);

async function waitTerminal(runId, timeoutMs, onPoll) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const now = await current();
    if (now.run && now.run.id === runId && TERMINAL.has(now.run.status)) return now.run;
    if (onPoll) await onPoll(now);
    await sleep(300);
  }
  throw new Error(`run ${runId} 在 ${timeoutMs}ms 内没有进入终态`);
}

/** 等到某条 Run 满足条件（例如进入 STANDBY）。与 waitTerminal 分开：待命不是终态，会一直等下去 */
async function waitFor(runId, predicate, timeoutMs, what) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const now = await current();
    if (now.run && now.run.id === runId && predicate(now.run)) return now.run;
    await sleep(300);
  }
  throw new Error(`run ${runId} 在 ${timeoutMs}ms 内没有进入「${what}」`);
}

const brief = (run) => run && {
  id: run.id, status: run.status, mode: run.mode,
  terminalReason: run.terminalReason, message: run.message,
  deliveredCount: run.deliveredCount, evaluatedCount: run.evaluatedCount,
  startedAt: run.startedAt, finishedAt: run.finishedAt,
};

const lines = (rows) => rows.map((r) => `[${r.level}] ${r.message}`);

/** 一轮里值得单独列出来的日志：能直接判断「有没有扫」「有没有巡检」「为什么结束」 */
const SCAN_MARKERS = ['开始扫描', '开始组合', '张卡片', '张卡前停止', '未启用值守', '未启用扫描', '待命'];
const isWatchLine = (m) => m.includes('值守扫描完成') || m.includes('值守待命第') || m.includes('读取消息列表');

// ---------------------------------------------------------------- 场景

async function scenarioA(base) {
  log('A 只扫描：watch=0、scan=1、正常模式、关键词无命中');
  let cfg = await putConfig(base, {
    scanEnabled: true,
    watchEnabled: false,
    keywords: [NO_HIT_KEYWORD],
    scrollRoundLimit: 1,
    combinationTimeLimitSec: 120,
    waitTimeSeconds: 1,
  });
  const started = await startRun('NORMAL');
  const run = await waitTerminal(started.runId, 180_000);
  const rows = await logsOf(run.id);
  const msgs = lines(rows);
  const after = await getConfig();
  return {
    scenario: 'A 只扫描',
    expect: 'COMPLETED / SCAN_DONE，无待命横幅、无巡检日志',
    config: { scanEnabled: cfg.scanEnabled, watchEnabled: after.watchEnabled },
    run: brief(run),
    cardsSeen: (await current()).combinations.map((c) => ({ seq: c.seq, status: c.status, cardsSeen: c.cardsSeen })),
    watchLines: msgs.filter(isWatchLine),
    keyLines: msgs.filter((m) => SCAN_MARKERS.some((k) => m.includes(k)) || m.includes('COMPLETED') || m.includes('SCAN_DONE')),
    ok: run.status === 'COMPLETED' && run.terminalReason === 'SCAN_DONE'
      && msgs.every((m) => !isWatchLine(m))
      && msgs.some((m) => m.includes('该平台未启用值守')),
  };
}

async function scenarioB(base) {
  log('B 插空巡检：watch=0、演练模式、真实关键词');
  let cfg = await putConfig(base, {
    scanEnabled: true,
    watchEnabled: false,
    keywords: base.keywords,
    scrollRoundLimit: 1,
    combinationTimeLimitSec: 150,
    waitTimeSeconds: 1,
  });
  const started = await startRun('DRY_RUN');
  const run = await waitTerminal(started.runId, 300_000);
  const rows = await logsOf(run.id);
  const msgs = lines(rows);
  // 「卡片被处理过」的证据取 run.evaluatedCount：它每处理一张通过前置筛选的卡就 +1，
  // 是这张卡真正走到判定层的下限。没有这个数就不能说「巡检点是可达的而没有被调用」——
  // 那样「0 次巡检」也可能只是这一轮根本没扫到 5 张卡。
  const cardsProcessed = run.evaluatedCount || 0;
  const sweepPoints = Math.floor(cardsProcessed / 5);
  return {
    scenario: 'B 插空巡检',
    expect: '处理 ≥5 张卡（≥1 个巡检点），且一条巡检日志都没有',
    config: { scanEnabled: cfg.scanEnabled, watchEnabled: cfg.watchEnabled },
    run: brief(run),
    cardsProcessed,
    sweepPoints,
    watchLines: msgs.filter(isWatchLine),
    keyLines: msgs.filter((m) => SCAN_MARKERS.some((k) => m.includes(k)) || m.includes('COMPLETED')),
    ok: cardsProcessed >= 5 && run.mode === 'DRY_RUN' && msgs.every((m) => !isWatchLine(m)),
  };
}

async function scenarioC(base) {
  log('C 运行中关值守：watch=1、扫描结束后进待命，再改配置关掉');
  let cfg = await putConfig(base, {
    scanEnabled: true,
    watchEnabled: true,
    keywords: [NO_HIT_KEYWORD],
    scrollRoundLimit: 1,
    combinationTimeLimitSec: 120,
    waitTimeSeconds: 1,
    // 间隔拉到上限：这样「关掉之后一秒内结束」只能由 sleepInterruptibly 的每秒检查造成，
    // 不可能是间隔自然到期
    watchIntervalSeconds: 600,
  });
  const started = await startRun('NORMAL');
  const standbyRun = await waitFor(started.runId, (r) => r.status === 'STANDBY', 180_000, 'STANDBY');

  // 等第一轮巡检落日志：它是「关掉之前巡检确实在跑」的对照。
  // 一轮巡检要读几十个会话，日志是整轮结束时才写一行，所以窗口按分钟给。
  let firstSweepAt = null;
  for (let i = 0; i < 400 && !firstSweepAt; i++) {
    const rows = await logsOf(started.runId);
    firstSweepAt = rows.find((r) => isWatchLine(r.message)) || null;
    if (!firstSweepAt) await sleep(300);
  }
  if (!firstSweepAt) throw new Error('待命开始后 120 秒内没有出现巡检日志，无法判断「关掉之后有没有新巡检」');

  const t0 = Date.now();
  await putConfig(cfg, { watchEnabled: false });
  const run = await waitTerminal(started.runId, 30_000);
  const elapsedMs = Date.now() - t0;

  const rows = await logsOf(started.runId);
  const msgs = lines(rows);
  // 关掉之后是否还有新的巡检行：以第一轮巡检的时间戳为界
  const afterSwitch = firstSweepAt
    ? rows.filter((r) => isWatchLine(r.message) && r.createdAt > firstSweepAt.createdAt
        && !r.message.includes('已关闭值守'))
    : [];
  return {
    scenario: 'C 运行中关值守',
    expect: 'STANDBY → 关掉值守 → 一秒内 COMPLETED / WATCH_DISABLED，不再有新的巡检行',
    config: { scanEnabled: cfg.scanEnabled, watchEnabled: false, watchIntervalSeconds: cfg.watchIntervalSeconds },
    standbyBanner: standbyRun.message,
    firstWatchLine: firstSweepAt ? { at: firstSweepAt.createdAt, message: firstSweepAt.message } : null,
    switchToTerminalMs: elapsedMs,
    run: brief(run),
    sweepLines: rows.filter((r) => isWatchLine(r.message)).map((r) => ({ at: r.createdAt, message: r.message })),
    sweepsAfterSwitch: afterSwitch.length,
    keyLines: msgs.filter((m) => SCAN_MARKERS.some((k) => m.includes(k)) || m.includes('COMPLETED') || m.includes('WATCH_DISABLED')),
    ok: run.status === 'COMPLETED' && run.terminalReason === 'WATCH_DISABLED'
      && elapsedMs <= 3000 && afterSwitch.length === 0,
  };
}

// ---------------------------------------------------------------- 主流程

fs.mkdirSync(OUT, { recursive: true });
const base = await getConfig();
fs.writeFileSync(path.join(OUT, `${PLATFORM}-config-before.json`), JSON.stringify(base, null, 2));
log(`基线配置已保存：${PLATFORM} scan=${base.scanEnabled} watch=${base.watchEnabled} `
  + `cities=${JSON.stringify(base.cities)} keywords=${JSON.stringify(base.keywords)} `
  + `duty=${base.dutyStartTime}-${base.dutyEndTime} interval=${base.watchIntervalSeconds}`);

// 值守时段改成全天：当前时刻在窗口外时会直接挂起（WAITING_DUTY），三个场景都跑不起来
let work = await putConfig(base, { dutyStartTime: '00:00', dutyEndTime: '23:59' });
log(`值守时段临时改为 ${work.dutyStartTime}-${work.dutyEndTime}`);

const results = [];
try {
  const wanted = ONLY ? [ONLY.toUpperCase()] : ['A', 'B', 'C'];
  if (wanted.includes('A')) results.push(await scenarioA(work));
  if (wanted.includes('B')) results.push(await scenarioB(work));
  if (wanted.includes('C')) results.push(await scenarioC(work));
} finally {
  // 整份 PUT 回去：只改字段会让其余字段落回默认值
  await putConfig(base, base).catch((e) => log('还原配置失败:', e.message));
  const restored = await getConfig();
  const same = JSON.stringify(restored) === JSON.stringify(base);
  results.push({ scenario: 'D 配置还原', expect: '与开始前逐字段一致', ok: same,
    diff: same ? null : { before: base, after: restored } });
  fs.writeFileSync(path.join(OUT, 'p2-report.json'), JSON.stringify({ platform: PLATFORM, at: new Date().toISOString(), results }, null, 2));
  log(`报告已写入 ${path.relative(ROOT, path.join(OUT, 'p2-report.json'))}`);

  console.log('\n=== P2 验收 ===');
  for (const r of results) {
    console.log(`${r.ok ? '✅' : '❌'} ${r.scenario} — ${r.expect}`);
    if (r.run) console.log(`    run #${r.run.id} ${r.run.status} / ${r.run.terminalReason}（投递 ${r.run.deliveredCount}，评估 ${r.run.evaluatedCount}）`);
    if (r.cardsProcessed !== undefined) console.log(`    处理卡片数 ${r.cardsProcessed}，巡检日志 ${r.watchLines.length} 条`);
    if (r.watchLines) console.log(`    巡检日志 ${r.watchLines.length} 条`);
    if (r.switchToTerminalMs !== undefined) console.log(`    关开关到终态 ${r.switchToTerminalMs}ms，之后新增巡检 ${r.sweepsAfterSwitch} 条`);
    if (r.diff) console.log(`    差异：${JSON.stringify(r.diff).slice(0, 800)}`);
  }
  if (results.some((r) => !r.ok)) process.exitCode = 1;
}
