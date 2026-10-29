/**
 * 探测「发送简历」入口在沟通页的 DOM 位置与可用性判据。
 *
 * 与 probe-chat-dom.mjs 同源：patchright 驱动 + 专用 profile，因此不会被站点的
 * 调试端口探测触发自我重载（用扩展 CDP 直连会被打成 about:blank）。
 *
 * 脱敏约定（比 probe-chat-dom 更严，因为这里要看输入框区域）：
 *   - 只输出按钮/控件自身的 className 与文字标签，不读任何消息正文；
 *   - 不输出会话标题、HR 姓名、公司名、加密 id；
 *   - 不点击任何「发送」类按钮，只点开列表里的一个会话。
 *
 * 用法：
 *   node tools/probe-resume-entry.mjs                 # 点开第一个无未读的会话，扫工具栏
 *   node tools/probe-resume-entry.mjs --pick unread    # 优先点有未读的（会清红点，慎用）
 */
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { chromium } from '../build/patchright-driver/package/index.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const OUT_DIR = path.join(HERE, 'out');

const argOfProfile = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};
/**
 * profile 路径要跟着「服务从哪个工作目录启动」走：application.yaml 写的是
 * {@code ./browser-data}，相对工作目录解析。服务在 {@code server/} 下跑，
 * 真实登录态就在 {@code server/browser-data}；仓库根的 {@code browser-data}
 * 是另一份（未登录），拿它打开只会停在 /web/user。
 */
const DEFAULT_PROFILE = fs.existsSync(path.join(ROOT, 'server', 'browser-data', 'Local State'))
  ? path.join(ROOT, 'server', 'browser-data')
  : path.join(ROOT, 'browser-data');
const PROFILE = argOfProfile('--profile', DEFAULT_PROFILE);

const argOf = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};
const PICK = argOf('--pick', 'read');
const WAIT_MS = Number(argOf('--wait-ms', '180000'));

const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 19)}]`, ...a);

// 与 BrowserSession.initOnThread 的 setArgs 保持一致
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

/**
 * 工具栏取证：以输入框为锚点，向上找到「输入区」容器，再把容器内所有可点击控件
 * 连同计算样式一起扫出来。判据不靠猜类名 —— 上一轮 chat-new/v5575 就是因为预设
 * 类名全 0 命中才导致「读不到任何消息」。
 */
const PROBE_TOOLBAR = () => {
  const vis = (el) => {
    if (!el) return false;
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' && cs.opacity !== '0';
  };
  const label = (el) => (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 24);

  // 输入框锚点：优先 contenteditable，退化为 textarea
  const input =
    document.querySelector("div#chat-input[contenteditable='true']") ||
    document.querySelector("[contenteditable='true']") ||
    document.querySelector('textarea');
  const anchor = input || document.body;

  // 向上 4 层找输入区容器（包含发送按钮的那一层）
  const chain = [];
  let cur = anchor;
  for (let i = 0; i < 5 && cur && cur !== document.body; i++) {
    chain.push({
      level: i,
      tag: cur.tagName,
      cls: String(cur.className || '').slice(0, 120),
      childCount: cur.children.length,
      rect: (() => {
        const r = cur.getBoundingClientRect();
        return { w: Math.round(r.width), h: Math.round(r.height) };
      })(),
    });
    cur = cur.parentElement;
  }

  // 候选容器 = 输入框向上第 2、3 层（实测发送按钮常与之同级）
  const scopes = chain
    .filter((c) => c.level === 2 || c.level === 3)
    .map((c) => {
      let el = anchor;
      for (let i = 0; i < c.level; i++) el = el.parentElement;
      return el;
    })
    .filter(Boolean);

  const controls = [];
  const seen = new Set();
  for (const scope of scopes) {
    for (const el of scope.querySelectorAll('button, a, div, span, i, li')) {
      if (!vis(el)) continue;
      const cls = String(el.className || '');
      const txt = label(el);
      // 只要「看起来像按钮」的：有 btn 类、有 pointer 光标、或是短文本的可点击元素
      const cs = getComputedStyle(el);
      const clickable = /btn|button|operate|toolbar|icon/i.test(cls) || cs.cursor === 'pointer';
      if (!clickable) continue;
      const key = el.tagName + '|' + cls + '|' + txt;
      if (seen.has(key)) continue;
      seen.add(key);
      controls.push({
        tag: el.tagName,
        cls: cls.slice(0, 140),
        txt,
        hasResumeWord: /简历/.test(txt),
        aria: el.getAttribute('aria-label') || '',
        title: el.getAttribute('title') || '',
        role: el.getAttribute('role') || '',
        cursor: cs.cursor,
      });
    }
  }

  // 全页范围再找一遍「含简历字样」的可点击元素（可能不在输入区容器内，比如顶部信息卡）
  const resumeAnywhere = [];
  for (const el of document.querySelectorAll('button, a, div, span, i, li')) {
    const txt = label(el);
    if (!txt || txt.length > 12 || !/简历/.test(txt)) continue;
    if (!vis(el)) continue;
    const r = el.getBoundingClientRect();
    resumeAnywhere.push({
      tag: el.tagName,
      cls: String(el.className || '').slice(0, 140),
      txt,
      cursor: getComputedStyle(el).cursor,
      aria: el.getAttribute('aria-label') || '',
      title: el.getAttribute('title') || '',
      rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
      // 是否为「禁用态」：Boss 用 CSS 类表达禁用，不是原生 disabled 属性
      clsLooksDisabled: /disabled|disable|forbid/i.test(String(el.className || '')),
      hasDisabledAttr: el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true',
    });
  }

  return {
    url: location.pathname,
    inputFound: !!input,
    inputInfo: input
      ? { tag: input.tagName, cls: String(input.className || '').slice(0, 100), editable: input.isContentEditable }
      : null,
    chain,
    controlCount: controls.length,
    controls,
    resumeAnywhereCount: resumeAnywhere.length,
    resumeAnywhere,
  };
};

const save = (name, data) => {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  const file = path.join(OUT_DIR, name);
  fs.writeFileSync(file, JSON.stringify(data, null, 2), 'utf8');
  log(`已写入 ${path.relative(ROOT, file)}`);
};

const main = async () => {
  log(`profile=${PROFILE} pick=${PICK}`);
  const context = await chromium.launchPersistentContext(PROFILE, {
    channel: 'msedge',
    headless: false,
    slowMo: 50,
    viewport: null,
    chromiumSandbox: true,
    args: LAUNCH_ARGS,
  });
  log('浏览器已启动');

  const pages = context.pages();
  const page = pages.length > 0 ? pages[0] : await context.newPage();
  page.setDefaultTimeout(30000);

  try {
    await page.goto('https://www.zhipin.com/web/geek/chat', { waitUntil: 'domcontentloaded', timeout: 60000 });
  } catch (e) {
    log(`首次导航未完成（${String(e).slice(0, 60)}），继续等待`);
  }

  const deadline = Date.now() + WAIT_MS;
  let ready = false;
  while (Date.now() < deadline) {
    const n = await page
      .evaluate(() => document.querySelectorAll('[class*=friend-content-warp]').length)
      .catch(() => 0);
    const pathNow = await page.evaluate(() => location.pathname).catch(() => '');
    if (n > 0) {
      ready = true;
      log(`会话列表已渲染：${n} 条`);
      break;
    }
    if (!pathNow.includes('/web/geek/chat')) {
      log(`当前在 ${pathNow}，跳回沟通页`);
      await page.goto('https://www.zhipin.com/web/geek/chat', { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(() => {});
      await page.waitForTimeout(3000);
      continue;
    }
    await page.waitForTimeout(2500);
  }
  if (!ready) {
    log('列表未渲染，退出');
    await context.close().catch(() => {});
    return;
  }

  // 关掉可能的弹窗
  await page
    .evaluate(() => {
      const d = document.querySelector('.dialog-wrap');
      if (d && d.offsetParent !== null) {
        const btn = d.querySelector('.icon-close, .close');
        if (btn) btn.click();
        return true;
      }
      return false;
    })
    .catch(() => false);

  log('点开一个会话（不发送任何内容）…');
  const picked = await page.evaluate((wantUnread) => {
    const items = [...document.querySelectorAll('[class*=friend-content-warp]')];
    const hasBadge = (el) => !!el.querySelector('.notice-badge, .dot, .badge');
    const idx = wantUnread ? items.findIndex(hasBadge) : items.findIndex((el) => !hasBadge(el));
    const target = items[idx >= 0 ? idx : 0];
    if (!target) return { clicked: false, total: items.length };
    target.setAttribute('data-probe-target', '1');
    return { clicked: true, total: items.length, clearingUnread: wantUnread && idx >= 0 };
  }, PICK === 'unread');
  log(`选择：${JSON.stringify(picked)}`);

  if (picked.clicked) {
    await page.click('[data-probe-target="1"]', { timeout: 15000 }).catch((e) => log(`点击失败：${String(e).slice(0, 80)}`));
    await page.waitForTimeout(4000);

    const probe = await page.evaluate(PROBE_TOOLBAR).catch((e) => ({ error: String(e).slice(0, 150) }));
    save('probe-resume-entry.json', { at: new Date().toISOString(), probe });

    console.log('\n=== 输入框锚点 ===');
    console.log(JSON.stringify(probe.inputInfo, null, 2));
    console.log('\n=== 输入区容器层级 ===');
    for (const c of probe.chain || []) console.log(`L${c.level} ${c.tag} .${c.cls} children=${c.childCount} ${JSON.stringify(c.rect)}`);
    console.log(`\n=== 输入区可点击控件（${probe.controlCount ?? 0} 个）===`);
    for (const c of probe.controls || []) {
      console.log(`${c.hasResumeWord ? '★' : ' '} ${c.tag} .${c.cls} | "${c.txt}" | cursor=${c.cursor} aria=${c.aria}`);
    }
    console.log(`\n=== 全页含「简历」的可点击元素（${probe.resumeAnywhereCount ?? 0} 个）===`);
    for (const r of probe.resumeAnywhere || []) {
      console.log(`${r.tag} .${r.cls} | "${r.txt}" | clsDisabled=${r.clsLooksDisabled} attrDisabled=${r.hasDisabledAttr} | ${JSON.stringify(r.rect)}`);
    }
  }

  log('保留窗口 6 秒后关闭');
  await page.waitForTimeout(6000);
  await context.close().catch(() => {});
  log('完成');
};

main().catch((e) => {
  console.error('探测失败：', e);
  process.exit(1);
});
