/**
 * 消息类型构成采样：点开若干「已读」会话（无红点，无副作用），
 * 统计消息构成，用于回答两个前置条件疑点：
 *   - 取不到文本的消息是什么类型（卡片/图片/系统）
 *   - 我方消息（item-myself）能否稳定判定
 *
 * 脱敏：只输出类名、计数、文本长度，不输出任何正文。
 *
 * 用法：node tools/probe-message-kinds.mjs [--sessions 3]
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

const argOf = (n, d) => {
  const i = process.argv.indexOf(n);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : d;
};
const SESSIONS = Number(argOf('--sessions', '3'));
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

/** 逐条消息打标：类型靠类名/内部容器判定，文本只取长度 */
const READ_KINDS = () => {
  const msgs = [...document.querySelectorAll('li.message-item')];
  return msgs.map((li) => {
    const cls = typeof li.className === 'string' ? li.className : '';
    const textEls = [...li.querySelectorAll('.text, .message-text, .content')];
    const maxLen = textEls.reduce((m, e) => Math.max(m, (e.innerText || '').trim().length), 0);
    return {
      self: li.classList.contains('item-myself'),
      friend: li.classList.contains('item-friend'),
      system: li.classList.contains('item-system'),
      textLen: maxLen,
      hasTextEl: textEls.length > 0,
      card: {
        messageCard: li.querySelectorAll('.message-card-wrap').length,
        articlesCenter: li.querySelectorAll('.articles-center').length,
        img: li.querySelectorAll('img').length,
        greetingCard: li.querySelectorAll('.chat-opener-box').length,
        resumeCard: li.querySelectorAll('.resume, .card-resume').length,
      },
      cls,
    };
  });
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

  try {
    await page.goto(CHAT_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  } catch (e) {
    log(`导航未完成（${String(e).slice(0, 60)}）`);
  }
  for (let i = 0; i < 30; i += 1) {
    const n = await page.evaluate(() => document.querySelectorAll('[class*=friend-content-warp]').length).catch(() => 0);
    if (n > 0) break;
    await page.waitForTimeout(1500);
  }

  // 只挑「无红点」的会话
  const readIdx = await page.evaluate(() =>
    [...document.querySelectorAll('[class*=friend-content-warp]')]
      .map((el, i) => ({ i, unread: !!el.querySelector('.notice-badge, .dot') }))
      .filter((x) => !x.unread)
      .map((x) => x.i),
  );
  log(`已读会话候选 ${readIdx.length} 个，将采样前 ${Math.min(SESSIONS, readIdx.length)} 个`);

  const samples = [];
  for (const idx of readIdx.slice(0, SESSIONS)) {
    await page.evaluate((i) => {
      document.querySelectorAll('[data-probe-target]').forEach((e) => e.removeAttribute('data-probe-target'));
      const items = [...document.querySelectorAll('[class*=friend-content-warp]')];
      if (items[i]) items[i].setAttribute('data-probe-target', '1');
    }, idx);
    await page.click('[data-probe-target="1"]').catch((e) => log(`点击失败：${String(e).slice(0, 50)}`));
    await page.waitForTimeout(2600);
    const rows = await page.evaluate(READ_KINDS).catch(() => null);
    if (!rows) {
      log(`会话 #${idx} 读取失败`);
      continue;
    }
    const s = {
      idx,
      total: rows.length,
      friend: rows.filter((r) => r.friend).length,
      self: rows.filter((r) => r.self).length,
      system: rows.filter((r) => r.system).length,
      noText: rows.filter((r) => r.textLen === 0).length,
      rows: rows.map((r) => ({
        who: r.self ? 'self' : r.friend ? 'friend' : r.system ? 'system' : 'unknown',
        textLen: r.textLen,
        hasTextEl: r.hasTextEl,
        card: Object.entries(r.card).filter(([, v]) => v > 0).map(([k, v]) => `${k}:${v}`).join(',') || null,
        cls: r.cls,
      })),
    };
    samples.push(s);
    log(`会话 #${idx}: 共 ${s.total} 条（对方 ${s.friend} / 我方 ${s.self} / 系统 ${s.system}），无文本 ${s.noText} 条`);
  }

  fs.mkdirSync(OUT_DIR, { recursive: true });
  fs.writeFileSync(path.join(OUT_DIR, 'message-kinds.json'), JSON.stringify({ at: new Date().toISOString(), samples }, null, 2), 'utf8');

  console.log('\n================ 消息构成采样 ================');
  for (const s of samples) {
    console.log(`\n--- 会话 #${s.idx}（${s.total} 条）---`);
    for (const r of s.rows) {
      console.log(`  ${r.who.padEnd(7)} textLen=${String(r.textLen).padStart(3)} hasTextEl=${r.hasTextEl ? 'Y' : 'N'} card=${r.card || '-'} | ${r.cls}`);
    }
  }
  const anySelf = samples.some((s) => s.self > 0);
  const anySystem = samples.some((s) => s.system > 0);
  console.log(`\n我方消息（item-myself）样本：${anySelf ? '有' : '无'}`);
  console.log(`系统消息（item-system）样本：${anySystem ? '有' : '无'}`);

  log('保留窗口 5 秒后关闭');
  await page.waitForTimeout(5000);
  await context.close().catch(() => {});
  log('完成');
};

main().catch((e) => {
  console.error('采样失败：', e);
  process.exit(1);
});
