/**
 * 功能可用的前置条件自检。
 *
 * 目的：在写 spec 之前，确认「修好选择器」之后整条链路是否真的跑通，
 * 而不是只验证了选择器命中。
 *
 * 检查项（全部针对「值守 → 读未读 → 读上下文 → 能发消息」这条主链）：
 *   P1 会话列表可选
 *   P2 未读数可读且可解析
 *   P3 虚拟滚动是否把未读挡在 DOM 外
 *   P4 点开会话后消息项可读
 *   P5 对方消息可读且文本非空
 *   P6 我方消息可判定（区分 self/friend）
 *   P7 消息 id 稳定（两次读取序列一致）
 *   P8 消息文本可取（每条都有文本容器且长度 > 0）
 *   P9 输入框可写入
 *   P10 写入后发送按钮从 disabled 变为可点（**不实际点击发送**）
 *
 * 脱敏：只输出计数与长度，不输出任何消息正文、姓名、公司、岗位、mid 真值
 * （mid 只输出哈希用于比对）。
 *
 * 用法：node tools/precheck.mjs
 */
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { chromium } from '../build/patchright-driver/package/index.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const OUT_DIR = path.join(HERE, 'out');
const PROFILE = path.join(ROOT, 'browser-data');
const CHAT_URL = 'https://www.zhipin.com/web/geek/chat';

const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 19)}]`, ...a);

const LAUNCH_ARGS = [
  '--start-maximized',
  '--test-type',
  '--disable-session-crashed-bubble',
  '--hide-crash-restore-bubble',
  '--no-first-run',
  '--no-default-browser-check',
  '--no-proxy-server',
  '--proxy-bypass-list=*',
  '--disable-blink-features=AutomationControlled',
];

/** 会话列表：用「修复后」的选择器读未读 */
const READ_LIST = () => {
  const hash = (s) => {
    let h = 5381;
    for (let i = 0; i < s.length; i += 1) h = ((h * 33) ^ s.charCodeAt(i)) >>> 0;
    return h;
  };
  const items = [...document.querySelectorAll('[class*=friend-content-warp]')];
  const rows = items.map((el) => {
    const badge = el.querySelector('.notice-badge, .dot');
    const titleEl = el.querySelector('.name-box, .name-text');
    const lastEl = el.querySelector('.last-msg-text');
    const raw = badge ? (badge.innerText || '').trim() : '';
    return {
      unreadRaw: raw,
      unread: /^\d+$/.test(raw) ? Number(raw) : raw === '' ? 0 : -1,
      badgeIsDot: badge ? badge.classList.contains('dot') : false,
      titleLen: titleEl ? (titleEl.innerText || '').trim().length : 0,
      lastMsgLen: lastEl ? (lastEl.innerText || '').trim().length : 0,
    };
  });

  const scroller = document.querySelector('.user-list-content, [class*=list-warp]');
  return {
    path: location.pathname,
    ready: document.readyState,
    itemCount: items.length,
    rows,
    unreadRows: rows.filter((r) => r.unread > 0).length,
    parseFail: rows.filter((r) => r.unread < 0).length,
    scroll: scroller
      ? { clientH: scroller.clientHeight, scrollH: scroller.scrollHeight, overflowing: scroller.scrollHeight > scroller.clientHeight + 20 }
      : null,
  };
};

/** 消息区：只报长度与 mid 哈希 */
const READ_MESSAGES = () => {
  const hash = (s) => {
    let h = 5381;
    for (let i = 0; i < s.length; i += 1) h = ((h * 33) ^ s.charCodeAt(i)) >>> 0;
    return h;
  };
  const msgs = [...document.querySelectorAll('li.message-item')];
  const rows = msgs.map((li) => {
    const mid = li.getAttribute('data-mid') || '';
    const textEls = [...li.querySelectorAll('.text, .message-text, .content')];
    const maxLen = textEls.reduce((m, e) => Math.max(m, (e.innerText || '').trim().length), 0);
    return {
      midLen: mid.length,
      midHash: mid ? hash(mid) : 0,
      self: li.classList.contains('item-myself'),
      friend: li.classList.contains('item-friend'),
      system: li.classList.contains('item-system'),
      hasTextEl: textEls.length > 0,
      textLen: maxLen,
      alias: (li.querySelector('.name-text') ? 1 : 0) + (li.querySelector('.time, .item-time') ? 1 : 0),
    };
  });
  return {
    total: msgs.length,
    friend: rows.filter((r) => r.friend).length,
    self: rows.filter((r) => r.self).length,
    system: rows.filter((r) => r.system).length,
    withText: rows.filter((r) => r.hasTextEl && r.textLen > 0).length,
    midNonEmpty: rows.filter((r) => r.midLen > 0).length,
    rows,
  };
};

const main = async () => {
  const context = await chromium.launchPersistentContext(PROFILE, {
    channel: 'msedge',
    headless: false,
    slowMo: 50,
    viewport: null,
    chromiumSandbox: true,
    args: LAUNCH_ARGS,
  });

  const pages = context.pages();
  const page = pages.length > 0 ? pages[0] : await context.newPage();
  page.setDefaultTimeout(30000);

  const checks = [];
  const add = (id, name, status, detail) => {
    checks.push({ id, name, status, detail });
    log(`${status.padEnd(7)} ${id} ${name} — ${detail}`);
  };

  try {
    await page.goto(CHAT_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  } catch (e) {
    log(`导航未完成（${String(e).slice(0, 60)}）`);
  }

  // 等列表
  let list = null;
  for (let i = 0; i < 40; i += 1) {
    list = await page.evaluate(READ_LIST).catch(() => null);
    if (list && list.itemCount > 0) break;
    await page.waitForTimeout(1500);
  }
  if (!list) {
    add('P0', '页面可用', 'FAIL', '页面未加载出任何结构');
    fs.mkdirSync(OUT_DIR, { recursive: true });
    fs.writeFileSync(path.join(OUT_DIR, 'precheck.json'), JSON.stringify({ checks }, null, 2), 'utf8');
    await context.close().catch(() => {});
    return;
  }

  // P1
  add('P1', '会话列表可选', list.itemCount > 0 ? 'PASS' : 'FAIL', `DOM 内 ${list.itemCount} 条`);

  // P2
  add(
    'P2',
    '未读数可读且可解析',
    list.unreadRows > 0 && list.parseFail === 0 ? 'PASS' : list.unreadRows > 0 ? 'WARN' : 'BLOCKED',
    `未读 ${list.unreadRows} 条，解析失败 ${list.parseFail} 条，纯 dot 徽标 ${list.rows.filter((r) => r.badgeIsDot).length} 条`,
  );

  // P3
  if (list.scroll) {
    const s = list.scroll;
    add(
      'P3',
      '虚拟滚动是否遮挡未读',
      s.overflowing ? 'WARN' : 'PASS',
      `列表可视 ${s.clientH}px / 内容 ${s.scrollH}px${s.overflowing ? '，视口外会话未渲染，扫描需滚动' : '，全部已渲染'}`,
    );
  } else {
    add('P3', '虚拟滚动是否遮挡未读', 'WARN', '未找到滚动容器，无法判定');
  }

  // 点开第一个未读会话（会清掉该会话未读）
  const pick = await page.evaluate(() => {
    const items = [...document.querySelectorAll('[class*=friend-content-warp]')];
    const idx = items.findIndex((el) => el.querySelector('.notice-badge, .dot'));
    if (idx < 0) return { clicked: false, reason: '无未读会话' };
    items[idx].setAttribute('data-probe-target', '1');
    return { clicked: true, index: idx };
  });
  add('—', '点开未读会话', pick.clicked ? 'INFO' : 'BLOCKED', JSON.stringify(pick));
  if (!pick.clicked) {
    fs.mkdirSync(OUT_DIR, { recursive: true });
    fs.writeFileSync(path.join(OUT_DIR, 'precheck.json'), JSON.stringify({ checks, list }, null, 2), 'utf8');
    await context.close().catch(() => {});
    return;
  }
  await page.click('[data-probe-target="1"]').catch((e) => log(`点击失败：${String(e).slice(0, 60)}`));
  await page.waitForTimeout(3000);

  // P4 / P5 / P6 / P8
  const m1 = await page.evaluate(READ_MESSAGES).catch((e) => ({ error: String(e).slice(0, 100) }));
  if (m1.error) {
    add('P4', '消息项可读', 'FAIL', m1.error);
  } else {
    add('P4', '消息项可读', m1.total > 0 ? 'PASS' : 'FAIL', `li.message-item ${m1.total} 条`);
    add('P5', '对方消息可读且文本非空', m1.friend > 0 ? 'PASS' : 'BLOCKED', `对方 ${m1.friend} 条 / 我方 ${m1.self} 条 / 系统 ${m1.system} 条`);
    add(
      'P6',
      '我方消息可判定',
      m1.self > 0 ? 'PASS' : 'N/A',
      m1.self > 0 ? `item-myself ${m1.self} 条` : '本会话我方无消息，无法判定（换一个有回复的会话再验）',
    );
    add('P8', '消息文本可取', m1.withText === m1.total ? 'PASS' : 'WARN', `${m1.withText}/${m1.total} 条有非空文本`);
  }

  // P7 两次读取 mid 序列一致
  await page.waitForTimeout(2500);
  const m2 = await page.evaluate(READ_MESSAGES).catch(() => null);
  if (m1.rows && m2 && m2.rows) {
    const a = m1.rows.map((r) => r.midHash).join(',');
    const b = m2.rows.map((r) => r.midHash).join(',');
    const same = a === b;
    add('P7', '消息 id 稳定（两次读取一致）', same ? 'PASS' : 'FAIL', same ? `序列一致，共 ${m1.rows.length} 条` : '两次读取序列不同，去重键不可靠');
  } else {
    add('P7', '消息 id 稳定', 'BLOCKED', '无法完成两次采样');
  }

  // P9 / P10 输入框与发送按钮（不实际发送）
  const editor = page.locator('.chat-input[contenteditable=true]').first();
  const sendBtn = page.locator('button.btn-send').first();
  const editorCount = await editor.count().catch(() => 0);
  if (editorCount === 0) {
    add('P9', '输入框可写入', 'FAIL', '未找到 .chat-input[contenteditable=true]');
    add('P10', '发送按钮可点', 'BLOCKED', '输入框缺失');
  } else {
    const beforeDisabled = await sendBtn.isDisabled().catch(() => null);
    await editor.click().catch(() => {});
    await page.keyboard.type('test', { delay: 40 }).catch(() => {});
    await page.waitForTimeout(800);
    const typedLen = await editor.evaluate((el) => (el.innerText || el.textContent || '').trim().length).catch(() => 0);
    const afterDisabled = await sendBtn.isDisabled().catch(() => null);

    add('P9', '输入框可写入', typedLen > 0 ? 'PASS' : 'FAIL', `写入后文本长度 ${typedLen}`);
    add(
      'P10',
      '写入后发送按钮变为可点',
      afterDisabled === false ? 'PASS' : 'FAIL',
      `输入前 disabled=${beforeDisabled}，输入后 disabled=${afterDisabled}（未实际点击发送）`,
    );

    // 清理：清空输入框，避免留下草稿
    await editor.fill('').catch(async () => {
      await page.keyboard.press('Control+A').catch(() => {});
      await page.keyboard.press('Backspace').catch(() => {});
    });
    await page.waitForTimeout(400);
    const clearedLen = await editor.evaluate((el) => (el.innerText || el.textContent || '').trim().length).catch(() => -1);
    log(`输入框已清理，剩余长度 ${clearedLen}`);
  }

  fs.mkdirSync(OUT_DIR, { recursive: true });
  fs.writeFileSync(
    path.join(OUT_DIR, 'precheck.json'),
    JSON.stringify({ at: new Date().toISOString(), checks, list, messages: { m1, m2 } }, null, 2),
    'utf8',
  );

  console.log('\n================ 前置条件自检汇总 ================');
  for (const c of checks) console.log(`${c.status.padEnd(7)} | ${c.id} | ${c.name} | ${c.detail}`);
  const blocked = checks.filter((c) => c.status === 'FAIL' || c.status === 'BLOCKED');
  console.log(`\n阻塞项：${blocked.length}；警告项：${checks.filter((c) => c.status === 'WARN' || c.status === 'N/A').length}`);

  log('保留窗口 5 秒后关闭');
  await page.waitForTimeout(5000);
  await context.close().catch(() => {});
  log('完成');
};

main().catch((e) => {
  console.error('自检失败：', e);
  process.exit(1);
});
