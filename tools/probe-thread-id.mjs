/**
 * 探测会话条目上是否存在「稳定会话标识」。
 *
 * 背景：现有 listUnreadThreads 把 conversationId 直接填成 HR 标题，
 * openThread 又用 setHasText 定位 —— 同名/包含关系（张三 vs 张三丰）会点错人。
 * 重试机制依赖「能准确回到同一个会话」，所以必须先确认 DOM 上有没有稳定 id。
 *
 * 脱敏：只输出属性名与值长度/格式特征，不输出任何真值。
 *
 * 用法：node tools/probe-thread-id.mjs
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

/** 只描述属性形态，不回传真值 */
const PROBE_IDS = () => {
  const describe = (v) => {
    if (v == null) return 'null';
    const n = v.length;
    if (/^\d+$/.test(v)) return `digits(${n})`;
    if (/^[0-9a-f-]{16,}$/i.test(v)) return `hexish(${n})`;
    if (/^[A-Za-z0-9_=-]{16,}$/.test(v)) return `b64ish(${n})`;
    return `text(${n})`;
  };
  const items = [...document.querySelectorAll("li[role='listitem']")];
  const sample = items.slice(0, 3).map((li) => ({
    ownAttrs: [...li.attributes].map((a) => `${a.name}=${describe(a.value)}`),
    descendants: [...li.querySelectorAll('*')]
      .flatMap((el) => [...el.attributes].filter((a) => a.name.startsWith('data-') || a.name === 'href' || a.name === 'id'))
      .slice(0, 14)
      .map((a) => `${a.name}@${a.value.startsWith('/') ? 'url' : describe(a.value)}`),
    hrefPaths: [...new Set([...li.querySelectorAll('a[href]')].map((a) => {
      const p = a.getAttribute('href') || '';
      return p.replace(/[0-9a-zA-Z_-]{12,}/g, '<id>');
    }))].slice(0, 4),
  }));
  return {
    path: location.pathname,
    itemCount: items.length,
    sample,
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
  try {
    await page.goto(CHAT_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  } catch (e) {
    log(`导航未完成（${String(e).slice(0, 60)}）`);
  }
  for (let i = 0; i < 30; i += 1) {
    const n = await page.evaluate(() => document.querySelectorAll("li[role='listitem']").length).catch(() => 0);
    if (n > 0) break;
    await page.waitForTimeout(1500);
  }
  const probe = await page.evaluate(PROBE_IDS).catch((e) => ({ error: String(e).slice(0, 120) }));

  // 点开第一个会话，看 URL 是否带稳定 id（建立 标题→URL 映射的可能性）
  const urlBefore = page.url();
  await page.evaluate(() => {
    const li = document.querySelector("li[role='listitem']");
    if (li) li.setAttribute('data-probe-target', '1');
  });
  await page.click('[data-probe-target="1"]').catch(() => {});
  await page.waitForTimeout(2500);
  const urlAfterOpen = page.url();

  const result = {
    at: new Date().toISOString(),
    probe,
    urlBefore: urlBefore.replace(/[0-9a-zA-Z_-]{12,}/g, '<id>'),
    urlAfterOpen: urlAfterOpen.replace(/[0-9a-zA-Z_-]{12,}/g, '<id>'),
    urlChanged: urlBefore !== urlAfterOpen,
  };
  fs.mkdirSync(OUT_DIR, { recursive: true });
  fs.writeFileSync(path.join(OUT_DIR, 'thread-id.json'), JSON.stringify(result, null, 2), 'utf8');

  console.log('\n=== 会话条目属性形态 ===');
  console.log(JSON.stringify(probe, null, 2));
  console.log(`\nURL 是否随点开变化：${result.urlChanged}`);
  console.log(`点开前：${result.urlBefore}`);
  console.log(`点开后：${result.urlAfterOpen}`);

  await page.waitForTimeout(4000);
  await context.close().catch(() => {});
  log('完成');
};

main().catch((e) => {
  console.error('探测失败：', e);
  process.exit(1);
});
