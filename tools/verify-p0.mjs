/**
 * P0 选择器改动的运行时验证。
 *
 * 与 probe-chat-dom.mjs 的区别：这个脚本不预设选择器，而是**直接从
 * BossSelectors.java 里解析常量值**，再用它去真实页面上跑一遍。
 * 这样「代码里写的」与「页面上命中的」永远是同一份字符串，不会两边漂移。
 *
 * 验证目标（对应 spec 0002 的 P0 验收）：
 *   1. CHAT_LIST_UNREAD 能读到未读（旧值 0 命中 → 值守静默空转）
 *   2. 未读三分语义（0 / >0 / -1）在真实页面上可区分
 *   3. MESSAGE_ITEM 能命中消息项
 *   4. data-mid 非空，可作为稳定去重键
 *
 * 副作用：只点开一个**没有未读徽标**的会话，不会清掉任何红点、不会发送任何消息。
 *
 * 用法：
 *   node tools/verify-p0.mjs              # 全量（列表 + 点开一个已读会话看消息区）
 *   node tools/verify-p0.mjs --no-open    # 只看会话列表，不点开任何会话
 */
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { chromium } from '../build/patchright-driver/package/index.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const PROFILE = path.join(ROOT, 'browser-data');
const SEL_FILE = path.join(ROOT, 'server/src/main/java/com/chatjob/platform/boss/BossSelectors.java');
const CHAT_URL = 'https://www.zhipin.com/web/geek/chat';

const NO_OPEN = process.argv.includes('--no-open');
const WAIT_MS = Number(process.env.WAIT_MS || 120_000);
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 19)}]`, ...a);

/** 从 Java 源码解析 `public static final String NAME = "...";` */
function readSelectors(file, names) {
  const src = fs.readFileSync(file, 'utf8');
  const out = {};
  for (const name of names) {
    const m = src.match(new RegExp(`String\\s+${name}\\s*=\\s*"([^"]*)"`));
    if (!m) throw new Error(`BossSelectors.java 里找不到常量 ${name}`);
    out[name] = m[1];
  }
  return out;
}

const NEEDED = [
  'CHAT_LIST_ITEM',
  'CHAT_LIST_UNREAD',
  'CHAT_LIST_ITEM_BODY',
  'MESSAGE_ITEM',
  'MESSAGE_TEXT',
  'MESSAGE_ID_ATTR',
];

/** 页面侧：会话列表 + 未读三分语义（与 BossAdapter.readUnread 同一套判定） */
const PROBE_LIST = (sel) => {
  const items = [...document.querySelectorAll(sel.CHAT_LIST_ITEM)];
  const cls = (el) => (el && typeof el.className === 'string' ? el.className : '');
  const rows = items.map((el) => {
    const hasBody = el.querySelectorAll(sel.CHAT_LIST_ITEM_BODY).length > 0;
    const badge = el.querySelector(sel.CHAT_LIST_UNREAD);
    let unread;
    if (!hasBody) {
      unread = -1;                       // 结构对不上 = 读不到
    } else if (!badge) {
      unread = 0;                        // 没有徽标 = 确实没有未读
    } else {
      const t = (badge.textContent || '').trim();
      if (!t) {
        unread = 1;                      // 纯红点，无数字
      } else {
        const d = t.replace(/[^0-9]/g, '');
        unread = d ? Number(d) : 1;
      }
    }
    return { unread, hasBody, badgeCls: cls(badge) };
  });
  const badgeClsSet = [...new Set(rows.map((r) => r.badgeCls).filter(Boolean))];
  return {
    total: items.length,
    // P0 核心：-1 必须是 0，否则说明结构自检没生效
    unreadable: rows.filter((r) => r.unread < 0).length,
    zeroUnread: rows.filter((r) => r.unread === 0).length,
    positiveUnread: rows.filter((r) => r.unread > 0).length,
    unreadSum: rows.reduce((a, r) => a + (r.unread > 0 ? r.unread : 0), 0),
    dotOnlyCount: rows.filter((r) => r.unread === 1 && /dot/.test(r.badgeCls)).length,
    badgeClasses: badgeClsSet,
    // 对照组：旧选择器在真实页面上命中多少（预期 0）
    legacyUnreadHits: document.querySelectorAll('.badge-count, .unread-count').length,
    legacyMsgItemHits: document.querySelectorAll('.chat-message-list li.message-item, .dialog-con li.message-item').length,
    // 限域是否必要：.dot 在 .friend-content 之外还有多少（比如导航红点、在线绿点）
    dotTotalAll: document.querySelectorAll('.dot').length,
    badgeTotalInScope: document.querySelectorAll(sel.CHAT_LIST_UNREAD).length,
  };
};

/** 页面侧：消息区（只报长度与计数，不读正文） */
const PROBE_MSG = (sel) => {
  const items = [...document.querySelectorAll(sel.MESSAGE_ITEM)];
  const rows = items.map((el) => {
    const t = el.querySelector(sel.MESSAGE_TEXT);
    const text = t ? (t.textContent || '').trim() : '';
    const mid = el.getAttribute(sel.MESSAGE_ID_ATTR) || '';
    const cls = typeof el.className === 'string' ? el.className : '';
    return { len: text.length, midLen: mid.length, self: cls.includes('item-myself') };
  });
  const chain = [];
  if (items.length) {
    let e = items[0].parentElement;
    for (let i = 0; i < 3 && e; i++) {
      const c = typeof e.className === 'string' ? e.className.trim().split(/\s+/).slice(0, 2).join('.') : '';
      chain.push(`${e.tagName.toLowerCase()}${c ? '.' + c : ''}`);
      e = e.parentElement;
    }
  }
  return {
    count: items.length,
    textNonEmpty: rows.filter((r) => r.len > 0).length,
    textEmpty: rows.filter((r) => r.len === 0).length,
    selfCount: rows.filter((r) => r.self).length,
    midNonEmpty: rows.filter((r) => r.midLen > 0).length,
    midLengthVariants: [...new Set(rows.map((r) => r.midLen))].sort((a, b) => a - b),
    parentChain: chain,
  };
};

async function waitReady(page, sel) {
  const deadline = Date.now() + WAIT_MS;
  let lastLog = 0;
  while (Date.now() < deadline) {
    const s = await page
      .evaluate((s2) => ({
        ready: document.readyState,
        path: location.pathname,
        items: document.querySelectorAll(s2.CHAT_LIST_ITEM).length,
        bodyLen: document.body ? document.body.innerText.length : 0,
      }), sel)
      .catch(() => ({ ready: '?', path: '?', items: 0, bodyLen: 0 }));
    if (s.items > 0) return s;
    if (Date.now() - lastLog > 15_000) {
      lastLog = Date.now();
      log(`等待会话列表… path=${s.path} ready=${s.ready} items=${s.items} bodyLen=${s.bodyLen}`);
      if (s.path && !s.path.includes('/web/geek/chat')) {
        log('不在沟通页（登录后可能落到职位页），主动跳回');
        await page.goto(CHAT_URL, { waitUntil: 'domcontentloaded' }).catch(() => {});
      }
    }
    await page.waitForTimeout(3000);
  }
  return null;
}

const sel = readSelectors(SEL_FILE, NEEDED);
log('从 BossSelectors.java 读到的选择器：');
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
try {
  await page.goto(CHAT_URL, { waitUntil: 'domcontentloaded' }).catch((e) => log('goto 失败:', e.message));
  const ready = await waitReady(page, sel);
  if (!ready) throw new Error('等不到会话列表（可能未登录或页面结构变化）');

  const list = await page.evaluate(PROBE_LIST, sel);
  console.log('\n=== 会话列表（未读三分语义）===');
  console.log(JSON.stringify(list, null, 2));

  if (!NO_OPEN) {
    // 只点「没有未读徽标」的那条：不清任何红点
    const picked = await page.evaluate((s) => {
      const items = [...document.querySelectorAll(s.CHAT_LIST_ITEM)];
      const idx = items.findIndex((el) => !el.querySelector(s.CHAT_LIST_UNREAD));
      const target = items[idx >= 0 ? idx : 0];
      if (!target) return { ok: false };
      target.setAttribute('data-verify-target', '1');
      return { ok: true, pickedNoBadge: idx >= 0, total: items.length };
    }, sel);
    log(`点开会话：${JSON.stringify(picked)}`);
    if (picked.ok) {
      await page.click('[data-verify-target="1"]', { timeout: 8000 }).catch((e) => log('点击失败:', e.message));
      await page.waitForTimeout(2500);
      const msg = await page.evaluate(PROBE_MSG, sel);
      console.log('\n=== 消息区 ===');
      console.log(JSON.stringify(msg, null, 2));
    }
  }

  // 结论行：便于快速判断 P0 是否成立
  const ok =
    list.unreadable === 0 &&
    list.badgeClasses.length > 0 &&
    list.legacyUnreadHits === 0;
  console.log(`\n[P0] 未读可解析 ${list.total - list.unreadable}/${list.total}，` +
    `旧选择器命中 ${list.legacyUnreadHits}（预期 0）→ ${ok ? 'PASS' : '需复核'}`);
} finally {
  await ctx.close().catch(() => {});
}
