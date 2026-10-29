/**
 * P1 会话精确定位的运行时验证（spec 0002 §3.4）。
 *
 * 单测覆盖不了 Playwright 的 Locator 行为，P0 用「从 BossSelectors.java 解析常量
 * 再拿去页面上跑」的办法绕过了这个空洞，这里沿用同一套做法。
 *
 * 验证目标：
 *   1. `.name-box` 的文本里到底有没有 \u00A0（P1 加 replace 的前提是否成立）
 *   2. **写入侧（firstText: trim）与比较侧（nameOf: replace+trim）是否等价**
 *      —— 不等价就是「所有会话都找不到」，且只打 debug 日志，静默失效
 *   3. 精确匹配的命中数分布（是否存在同名会话 → AMBIGUOUS_TITLE）
 *   4. Digest 在真实 last-msg 上是否可用（是否大量为空、是否重复）
 *
 * 副作用：**零**。不点开任何会话、不发消息、不清红点，只读 DOM 文本。
 *
 * 用法：node tools/verify-p1.mjs [--json]
 */
import path from 'node:path';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { chromium } from '../build/patchright-driver/package/index.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const PROFILE = path.join(ROOT, 'browser-data');
const SEL_FILE = path.join(ROOT, 'server/src/main/java/com/chatjob/platform/boss/BossSelectors.java');
const CHAT_URL = 'https://www.zhipin.com/web/geek/chat';
const WAIT_MS = Number(process.env.WAIT_MS || 120_000);
const JSON_ONLY = process.argv.includes('--json');
const log = (...a) => { if (!JSON_ONLY) console.log(`[${new Date().toISOString().slice(11, 19)}]`, ...a); };

/** 从 Java 源码解析 `public static final String NAME = "...";`（含跨行拼接） */
function readSelectors(file, names) {
  const src = fs.readFileSync(file, 'utf8');
  const out = {};
  for (const name of names) {
    const m = src.match(new RegExp(`String\\s+${name}\\s*=\\s*((?:"[^"]*"\\s*\\+?\\s*)+);`));
    if (!m) throw new Error(`BossSelectors.java 里找不到常量 ${name}`);
    out[name] = [...m[1].matchAll(/"([^"]*)"/g)].map((x) => x[1]).join('');
  }
  return out;
}

const NEEDED = [
  'CHAT_LIST_ITEM',
  'CHAT_LIST_TITLE',
  'CHAT_LIST_LAST_MSG',
  'CHAT_LIST_UNREAD',
  'CHAT_LIST_ITEM_BODY',
];

/** 页面侧：原样取出每条的标题原文与最后一句（不做任何归一化） */
const PROBE = (sel) => {
  const items = [...document.querySelectorAll(sel.CHAT_LIST_ITEM)];
  return items.map((el, i) => {
    const t = el.querySelector(sel.CHAT_LIST_TITLE);
    const l = el.querySelector(sel.CHAT_LIST_LAST_MSG);
    return {
      i,
      raw: t ? (t.textContent || '') : '',
      last: l ? (l.textContent || '') : '',
      hasBody: el.querySelectorAll(sel.CHAT_LIST_ITEM_BODY).length > 0,
      hasBadge: !!el.querySelector(sel.CHAT_LIST_UNREAD),
      text: (el.textContent || '').slice(0, 200),
    };
  });
};

/** 与 Digest.of 同口径 */
const digestOf = (text) => {
  const t = (text || '').trim();
  if (!t) return null;
  return crypto.createHash('sha256').update(t, 'utf8').digest('hex').slice(0, 16);
};

const sel = readSelectors(SEL_FILE, NEEDED);
log('选择器（来自 BossSelectors.java）：');
for (const [k, v] of Object.entries(sel)) log(`  ${k} = ${v}`);

const ctx = await chromium.launchPersistentContext(PROFILE, {
  headless: false,
  channel: 'msedge',
  viewport: null,
  args: [
    '--start-maximized',
    '--test-type',
    '--disable-session-crashed-bubble',
    '--hide-crash-restore-bubble',
    '--no-first-run',
    '--no-default-browser-check',
    '--no-proxy-server',
    '--proxy-bypass-list=*',
    '--disable-blink-features=AutomationControlled',
  ],
});

const page = ctx.pages()[0] || (await ctx.newPage());
let report;
try {
  await page.goto(CHAT_URL, { waitUntil: 'domcontentloaded' }).catch((e) => log('goto 失败:', e.message));

  const deadline = Date.now() + WAIT_MS;
  let ready = false;
  while (Date.now() < deadline) {
    const s = await page.evaluate((x) => ({
      path: location.pathname,
      items: document.querySelectorAll(x.CHAT_LIST_ITEM).length,
    }), sel).catch(() => ({ path: '?', items: 0 }));
    if (s.items > 0) { ready = true; break; }
    if (s.path && !s.path.includes('/web/geek/chat')) {
      await page.goto(CHAT_URL, { waitUntil: 'domcontentloaded' }).catch(() => {});
    }
    await page.waitForTimeout(3000);
  }
  if (!ready) throw new Error('等不到会话列表（未登录或结构变化）');

  const rows = await page.evaluate(PROBE, sel);

  // firstText 的语义：textContent + trim()（Java String.trim 只去 <= 空格，\u00A0 去不掉）
  const asFirstText = (s) => s.replace(/^[\t\n\v\f\r ]+|[\t\n\v\f\r ]+$/g, '');
  // nameOf 的语义：replace('\u00A0',' ') 再 trim
  const asNameOf = (s) => asFirstText(s.replace(/\u00A0/g, ' '));

  const parsed = rows.map((r) => {
    const writeTitle = asFirstText(r.raw);      // listUnreadThreads 写入的 title
    const readTitle = asNameOf(r.raw);          // openThread 比较用的 title
    return {
      i: r.i,
      writeTitle,
      readTitle,
      raw: r.raw,
      hasNbsp: r.raw.includes('\u00A0'),
      last: asFirstText(r.last),
      hasBody: r.hasBody,
      hasBadge: r.hasBadge,
    };
  });

  // ---- 1) \u00A0 是否存在
  const nbspRows = parsed.filter((r) => r.hasNbsp);
  // ---- 2) 写入值 vs 比较值是否等价（决定「会话能否被打开」）
  const mismatched = parsed.filter((r) => r.writeTitle !== r.readTitle);
  // ---- 3) 同名
  const byRead = new Map();
  for (const r of parsed) {
    if (!r.readTitle) continue;
    byRead.set(r.readTitle, (byRead.get(r.readTitle) || 0) + 1);
  }
  const dupTitles = [...byRead.entries()].filter(([, n]) => n > 1);
  // ---- 4) Digest
  const digests = parsed.map((r) => digestOf(r.last));
  const emptyDigest = digests.filter((d) => d === null).length;
  const dupDigest = digests.filter((d, i) => d && digests.indexOf(d) !== i).length;

  report = {
    totalThreads: parsed.length,
    unreadable: parsed.filter((r) => !r.hasBody).length,
    withBadge: parsed.filter((r) => r.hasBadge).length,
    nbsp: {
      rowsWithNbsp: nbspRows.length,
      samples: nbspRows.slice(0, 5).map((r) => ({ raw: JSON.stringify(r.raw), writeTitle: r.writeTitle, readTitle: r.readTitle })),
    },
    // 关键结论：>0 说明「落任务时记的标题」永远匹配不上「点开时比较的标题」
    writeReadMismatch: mismatched.length,
    mismatchSamples: mismatched.slice(0, 5).map((r) => ({ raw: JSON.stringify(r.raw), writeTitle: r.writeTitle, readTitle: r.readTitle })),
    duplicateTitles: dupTitles.map(([t, n]) => ({ title: t, count: n })),
    digest: { empty: emptyDigest, duplicated: dupDigest, sample: digests.slice(0, 3) },
    titles: parsed.slice(0, 8).map((r) => r.writeTitle),
  };
} finally {
  await ctx.close().catch(() => {});
}

if (JSON_ONLY) {
  console.log(JSON.stringify(report, null, 2));
} else {
  console.log('\n=== 会话精确定位验证 ===');
  console.log(JSON.stringify(report, null, 2));
  console.log('\n=== 判定 ===');
  console.log(`会话数 ${report.totalThreads}，结构对不上 ${report.unreadable}，带未读徽标 ${report.withBadge}`);
  console.log(`含 \\u00A0 的标题：${report.nbsp.rowsWithNbsp} 条`);
  console.log(`写入值 ≠ 比较值：${report.writeReadMismatch} 条  ${report.writeReadMismatch > 0 ? '❌ 会话永远打不开' : '✅ 一致'}`);
  console.log(`同名会话：${report.duplicateTitles.length} 组  ${report.duplicateTitles.length ? '（会触发 AMBIGUOUS_TITLE）' : '✅ 无'}`);
  console.log(`Digest 为空：${report.digest.empty} 条，重复：${report.digest.duplicated} 条`);
}
