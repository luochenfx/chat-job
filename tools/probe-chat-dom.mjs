/**
 * 用项目自带的 patchright 驱动 + 专用 profile 取证沟通页 DOM 结构。
 *
 * 与生产同源：启动参数逐项对齐 server/.../driver/BrowserSession.java，
 * 走 patchright（不是 CDP 直连），因此不会被站点的调试端口探测触发自我重载。
 *
 * 脱敏约定：
 *   - 只读 className / tagName / 计数 / 属性是否存在，不读任何消息正文；
 *   - 不输出 HR 姓名、公司名、岗位名、加密 id 的具体值；
 *   - data-mid 只报「非空条数」，不落具体值。
 *
 * 用法：
 *   node tools/probe-chat-dom.mjs --stage list          # 只抓会话列表（不点开任何会话）
 *   node tools/probe-chat-dom.mjs --stage open          # 再点开第一个会话，抓消息区结构
 *   WAIT_MS=600000 node tools/probe-chat-dom.mjs ...    # 调整等待扫码的时长
 */
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { chromium } from '../build/patchright-driver/package/index.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const OUT_DIR = path.join(HERE, 'out');
const PROFILE = path.join(ROOT, 'browser-data');

const argOf = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};
const STAGE = argOf('--stage', 'list');
const PICK = argOf('--pick', 'read'); // read=优先点无未读的；unread=优先点有未读的（验证开场白）
const WAIT_MS = Number(argOf('--wait-ms', '300000'));

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
 * 探索式探测：不预设选择器，直接扫全站类名词表。
 * 只读 className / tagName / 计数，不读任何文本内容。
 */
const PROBE_DEEP = () => {
  const all = [...document.querySelectorAll('*')];
  const tokenFreq = new Map();
  const badgeLike = new Map();
  const listLike = new Map();
  let badgeTextNodes = 0;

  for (const el of all) {
    const cls = typeof el.className === 'string' ? el.className : '';
    if (!cls) continue;
    for (const t of cls.split(/\s+/)) {
      if (!t) continue;
      tokenFreq.set(t, (tokenFreq.get(t) || 0) + 1);
      if (/badge|unread|notice|dot|red-?point|msg-?count/i.test(t)) badgeLike.set(t, (badgeLike.get(t) || 0) + 1);
      if (/list|item|row|card|thread|conversation/i.test(t)) listLike.set(t, (listLike.get(t) || 0) + 1);
    }
    // 纯数字的小文本节点（未读数通常长这样），只统计不取值
    if (el.children.length === 0) {
      const txt = (el.textContent || '').trim();
      if (/^\d{1,2}$/.test(txt)) badgeTextNodes += 1;
    }
  }

  const top = (map, n) => [...map.entries()].sort((a, b) => b[1] - a[1]).slice(0, n);

  return {
    path: location.pathname,
    ready: document.readyState,
    aliveMs: Math.round(performance.now()),
    bodyTextLen: document.body ? document.body.innerText.length : -1,
    totalElements: all.length,
    liCount: document.querySelectorAll('li').length,
    listItemRole: document.querySelectorAll('[role=listitem]').length,
    digitOnlyLeaves: badgeTextNodes,
    badgeTokens: top(badgeLike, 20),
    listTokens: top(listLike, 30),
    allTokensTop: top(tokenFreq, 50),
  };
};

/** 页面侧探测：只数元素、读类名，不碰正文 */
const PROBE_LIST = () => {
  const q = (s) => document.querySelectorAll(s).length;
  const cls = (el) => (typeof el.className === 'string' ? el.className : '');
  const firstOf = (s) => {
    const el = document.querySelector(s);
    return el ? `${el.tagName.toLowerCase()}.${cls(el)}` : null;
  };
  // 找「挂着徽标的最小祖先」，这决定了我们该在哪个层级读未读
  const badgeHosts = [...document.querySelectorAll('.notice-badge, .dot, .badge-count, .unread-count')]
    .slice(0, 6)
    .map((b) => {
      const text = (b.innerText || '').trim();
      return {
        badgeTag: b.tagName.toLowerCase(),
        badgeCls: cls(b),
        textIsDigits: /^\d{1,3}$/.test(text),
        textLen: text.length,
        parent: b.parentElement ? `${b.parentElement.tagName.toLowerCase()}.${cls(b.parentElement)}` : null,
        grand: b.parentElement?.parentElement
          ? `${b.parentElement.parentElement.tagName.toLowerCase()}.${cls(b.parentElement.parentElement)}`
          : null,
      };
    });

  // 会话条目：取「包含对话入口」的容器类名，不取文本
  const itemCandidates = ['[class*=boss-item]', '[class*=user-item]', '[class*=friend-item]', '[class*=chat-user]']
    .map((s) => ({ selector: s, count: q(s), first: firstOf(s) }))
    .filter((x) => x.count > 0);

  return {
    path: location.pathname,
    ready: document.readyState,
    aliveMs: Math.round(performance.now()),
    bodyTextLen: document.body ? document.body.innerText.length : -1,
    counts: {
      friendList: q('[class*=friend-list]'),
      chatUserList: q('[class*=chat-user-list]'),
      bossItem: q('[class*=boss-item]'),
      userItem: q('[class*=user-item]'),
      friendContentWarp: q('[class*=friend-content-warp]'),
      li: q('li'),
      noticeBadge: q('.notice-badge'),
      dot: q('.dot'),
      badgeCount: q('.badge-count'),
      unreadCount: q('.unread-count'),
      qrCode: q('[class*=qr]'),
      loginPanel: q('[class*=login]'),
    },
    itemCandidates,
    badgeHosts,
    badgeParentClasses: [...new Set(badgeHosts.map((b) => b.parent).filter(Boolean))].slice(0, 4),
  };
};

/** 消息区探测：类名 + 计数 + data-mid 是否非空，不读正文 */
const PROBE_MESSAGES = () => {
  const q = (s) => document.querySelectorAll(s).length;
  const cls = (el) => (typeof el.className === 'string' ? el.className : '');
  const firstOf = (s) => {
    const el = document.querySelector(s);
    return el ? `${el.tagName.toLowerCase()}.${cls(el)}` : null;
  };
  const msgs = [...document.querySelectorAll('li.message-item')];
  const mids = msgs.map((m) => m.getAttribute('data-mid') || '');

  return {
    path: location.pathname,
    ready: document.readyState,
    aliveMs: Math.round(performance.now()),
    counts: {
      messageItem: msgs.length,
      itemFriend: q('.item-friend'),
      itemMyself: q('.item-myself'),
      itemSystem: q('.item-system'),
      opener: q('.chat-opener-box'),
      chatRecord: q('.chat-record'),
      chatMessage: q('.chat-message'),
      chatMessageList: q('.chat-message-list'),
    },
    msgParent: msgs.length ? `${msgs[0].parentElement.tagName.toLowerCase()}.${cls(msgs[0].parentElement)}` : null,
    msgParentParent: msgs.length ? `${msgs[0].parentElement.parentElement.tagName.toLowerCase()}.${cls(msgs[0].parentElement.parentElement)}` : null,
    midTotal: mids.length,
    midNonEmpty: mids.filter((m) => m.length > 8).length,
    // 只报长度分布，不报具体值
    midLengths: [...new Set(mids.map((m) => m.length))].slice(0, 5),
    sendCandidates: ['.btn-send', '.send-message', '[class*=send]']
      .map((s) => ({ selector: s, count: q(s), first: firstOf(s) }))
      .filter((x) => x.count > 0),
    editorCandidates: ['.chat-editor', '[contenteditable=true]', 'textarea']
      .map((s) => ({ selector: s, count: q(s), first: firstOf(s) }))
      .filter((x) => x.count > 0),
    openerSeen: q('.chat-opener-box') > 0,
  };
};

/** 把我们代码里 BossSelectors 的每个常量拿到真实页面上数一遍 */
const PROBE_SELECTORS = () => {
  const check = (name, sel) => {
    let n = -1;
    let err = null;
    try {
      n = document.querySelectorAll(sel).length;
    } catch (e) {
      err = String(e).slice(0, 60);
    }
    return { name, sel, count: n, err };
  };
  const cases = [
    ['CHAT_LIST_ITEM', "li[role='listitem']"],
    ['CHAT_LIST_TITLE', 'div.title-box span.name-box, .title-box .name-box'],
    ['CHAT_LIST_LAST_MSG', 'div.gray.last-msg span.last-msg-text, .last-msg-text'],
    ['CHAT_LIST_UNREAD（现用）', '.badge-count, .unread-count'],
    ['未读实际类名', '.notice-badge'],
    ['MESSAGE_ITEM 候选1', '.chat-message-list li.message-item'],
    ['MESSAGE_ITEM 候选2', '.dialog-con li.message-item'],
    ['MESSAGE_ITEM 裸写法', 'li.message-item'],
    ['MESSAGE_SELF', 'li.message-item.item-myself, .item-myself'],
    ['MESSAGE_TEXT', '.text, .message-text, .content'],
    ['MESSAGE_IMAGE', 'img.message-image, .image-message img'],
    ['SEND_BUTTON 候选1', 'div.send-message'],
    ['SEND_BUTTON 候选2', "button[type='send'].btn-send"],
    ['SEND_BUTTON 候选3', 'button.btn-send'],
    ['CHAT_INPUT 候选1', "div#chat-input.chat-input[contenteditable='true']"],
    ['CHAT_INPUT 候选2', 'textarea.input-area'],
    ['输入框 actual', 'div.chat-input[contenteditable=true]'],
    ['#chat-input 是否存在', '#chat-input'],
    ['.dialog-con 是否存在', '.dialog-con'],
    ['.chat-conversation', '.chat-conversation'],
  ];
  return cases.map(([name, sel]) => check(name, sel));
};

/** 消息区深度扫描：以 .chat-conversation 为范围，只读类名与属性存在性 */
const PROBE_MSG_DEEP = () => {
  const scope = document.querySelector('.chat-conversation') || document.body;
  const all = [...scope.querySelectorAll('*')];
  const tokenFreq = new Map();
  for (const el of all) {
    const cls = typeof el.className === 'string' ? el.className : '';
    for (const t of cls.split(/\s+/)) if (t) tokenFreq.set(t, (tokenFreq.get(t) || 0) + 1);
  }
  const midNodes = [...scope.querySelectorAll('[data-mid]')];
  const clsOf = (el) => (typeof el.className === 'string' ? el.className : '');
  const mids = midNodes.map((m) => m.getAttribute('data-mid') || '');
  // 平台侧的 AI/助手标识（用于判断「对方是否在用 AI」这类提示）
  const aiTokens = [...tokenFreq.entries()].filter(([t]) => /(^|-)ai(-|$)|robot|helper|assist/i.test(t));

  return {
    scopeFound: scope !== document.body,
    totalElements: all.length,
    liCount: scope.querySelectorAll('li').length,
    dataMidNodes: midNodes.length,
    dataMidSamples: midNodes.slice(0, 4).map((e) => `${e.tagName.toLowerCase()}.${clsOf(e)}`),
    midNonEmpty: mids.filter((m) => m.length > 8).length,
    midLengths: [...new Set(mids.map((m) => m.length))].slice(0, 5),
    classes: {
      itemFriend: scope.querySelectorAll('.item-friend').length,
      itemMyself: scope.querySelectorAll('.item-myself').length,
      itemSystem: scope.querySelectorAll('.item-system').length,
      messageItem: scope.querySelectorAll('li.message-item').length,
      opener: scope.querySelectorAll('.chat-opener-box').length,
    },
    tokensTop: [...tokenFreq.entries()].sort((a, b) => b[1] - a[1]).slice(0, 45),
    aiTokens,
    editor: ['.chat-editor', '[contenteditable=true]', 'textarea'].map((s) => ({ s, n: document.querySelectorAll(s).length })),
    sendCands: ['.btn-send', '.send-message', '[class*=send]'].map((s) => ({ s, n: document.querySelectorAll(s).length })).filter((x) => x.n > 0),
  };
};

function save(name, data) {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  const file = path.join(OUT_DIR, name);
  fs.writeFileSync(file, JSON.stringify(data, null, 2), 'utf8');
  log(`已写入 ${path.relative(ROOT, file)}`);
}

const main = async () => {
  log(`stage=${STAGE} profile=${PROFILE}`);
  if (!fs.existsSync(path.join(PROFILE, 'Default'))) {
    log('提示：profile 尚无登录态，浏览器打开后请扫码登录');
  }

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
    log('打开沟通页…');
    await page.goto('https://www.zhipin.com/web/geek/chat', { waitUntil: 'domcontentloaded', timeout: 60000 });
  } catch (e) {
    log(`首次导航未完成（${String(e).slice(0, 60)}），继续等待页面自身就绪`);
  }

  // 轮询等待「已登录且会话列表已渲染」
  //
  // 登录成功后站点会把页面送到 /web/geek/jobs 之类的落地页，不会自己回聊天页，
  // 所以这里必须主动跳回去 —— 否则会一直空等（第一版就踩了这个坑）。
  const deadline = Date.now() + WAIT_MS;
  let listProbe = null;
  let lastLog = 0;
  let redirects = 0;
  let settledNoMatch = false;
  let sawChatContentAt = 0;
  const CHAT_URL = 'https://www.zhipin.com/web/geek/chat';

  const goChat = async (why) => {
    redirects += 1;
    log(`${why}，主动回到聊天页（第 ${redirects} 次）`);
    await page
      .goto(CHAT_URL, { waitUntil: 'domcontentloaded', timeout: 60000 })
      .catch((e) => log(`跳转未完成（${String(e).slice(0, 60)}），继续观察`));
    await page.waitForTimeout(3000);
  };

  while (Date.now() < deadline) {
    listProbe = await page.evaluate(PROBE_LIST).catch((e) => ({ error: String(e).slice(0, 100) }));
    const c = listProbe?.counts || {};
    const hasList = (c.friendList || 0) + (c.chatUserList || 0) + (c.bossItem || 0) + (c.userItem || 0) + (c.friendContentWarp || 0) > 0;
    if (hasList) {
      log('检测到会话列表，判定已登录且已进入沟通页');
      break;
    }

    const pathNow = listProbe?.path || '';
    const onLoginPage = pathNow.includes('/web/user');
    const onChatPage = pathNow.includes('/web/geek/chat');
    const bodyLen = listProbe?.bodyTextLen || 0;

    // 沟通页已经渲染出内容，但预设选择器一个都没命中 —— 不再空等，
    // 转为「扫全站类名」的探索式取证，用真实类名反推结构。
    if (onChatPage && bodyLen > 1200) {
      if (!sawChatContentAt) sawChatContentAt = Date.now();
      else if (Date.now() - sawChatContentAt > 6000) {
        settledNoMatch = true;
        log(`页面已渲染（bodyLen=${bodyLen}）但未命中预设选择器，转探索式扫描`);
        break;
      }
    }
    // 已登录（不在登录页）却停在别的页面 —— 典型是登录后的落地页，主动跳回沟通页
    if (!onLoginPage && !onChatPage && redirects < 6 && (listProbe?.bodyTextLen || 0) > 500) {
      await goChat(`当前停在 ${pathNow}`);
      continue;
    }
    // 在沟通页但列表尚未渲染，给它时间
    if (Date.now() - lastLog > 15000) {
      lastLog = Date.now();
      const hint = onLoginPage ? '仍在登录页（如需扫码请扫码）' : onChatPage ? '已在沟通页，等待列表渲染' : '页面状态未知';
      log(`等待中（${hint}）：path=${pathNow || '?'} ready=${listProbe?.ready ?? '?'} bodyLen=${listProbe?.bodyTextLen ?? '?'}`);
    }
    await page.waitForTimeout(3000);
  }

  const c = listProbe?.counts || {};
  const loggedIn = (c.friendList || 0) + (c.chatUserList || 0) + (c.bossItem || 0) + (c.userItem || 0) + (c.friendContentWarp || 0) > 0;
  save('probe-list.json', { stage: 'list', loggedIn, at: new Date().toISOString(), probe: listProbe });
  console.log('\n=== 会话列表取证 ===');
  console.log(JSON.stringify(listProbe, null, 2));

  if (!loggedIn) {
    const bodyLen = listProbe?.bodyTextLen || 0;
    if (bodyLen > 500) {
      const deep = await page.evaluate(PROBE_DEEP).catch((e) => ({ error: String(e).slice(0, 120) }));
      save('probe-deep.json', { stage: 'deep', settledNoMatch, at: new Date().toISOString(), probe: deep });
      console.log('\n=== 探索式类名扫描 ===');
      console.log(JSON.stringify(deep, null, 2));
      log('已拿到真实类名表，据此修正选择器');
    } else {
      log('页面没有渲染出内容，结束。');
    }
  } else if (STAGE === 'open') {
    // 点开一个会话：优先点「没有徽标」的那条，避免清掉真实未读。
    // 选择器按本轮实测结果修正：条目是 .friend-content-warp（原先猜的 boss-item / user-item 都不存在）。
    log('准备点开一个会话（优先选无未读徽标的）…');
    const dismissed = await page.evaluate(() => {
      const d = document.querySelector('.dialog-wrap');
      if (!d || d.offsetParent === null) return false;
      const btn = d.querySelector('.icon-close, .close');
      if (btn) { btn.click(); return true; }
      return false;
    }).catch(() => false);
    if (dismissed) {
      log('检测到弹窗，已尝试关闭');
      await page.waitForTimeout(1200);
    }

    const picked = await page.evaluate((wantUnread) => {
      const items = [...document.querySelectorAll('[class*=friend-content-warp]')];
      const hasBadge = (el) => !!el.querySelector('.notice-badge, .badge');
      const idx = wantUnread ? items.findIndex(hasBadge) : items.findIndex((el) => !hasBadge(el));
      const target = items[idx >= 0 ? idx : 0];
      if (!target) return { clicked: false, reason: 'no items', total: items.length };
      target.setAttribute('data-probe-target', '1');
      return { clicked: true, wantUnread, index: idx, total: items.length, clearingUnread: wantUnread && idx >= 0 };
    }, PICK === 'unread');
    log(`选择结果：${JSON.stringify(picked)}`);

    if (picked.clicked) {
      await page.click('[data-probe-target="1"]', { timeout: 15000 }).catch((e) => log(`点击失败：${String(e).slice(0, 80)}`));
      await page.waitForTimeout(3500);
      const msgProbe = await page.evaluate(PROBE_MESSAGES).catch((e) => ({ error: String(e).slice(0, 120) }));
      save('probe-messages.json', { stage: 'open', at: new Date().toISOString(), probe: msgProbe });
      console.log('\n=== 消息区取证 ===');
      console.log(JSON.stringify(msgProbe, null, 2));

      const msgDeep = await page.evaluate(PROBE_MSG_DEEP).catch((e) => ({ error: String(e).slice(0, 120) }));
      save('probe-messages-deep.json', { stage: 'open', at: new Date().toISOString(), probe: msgDeep });
      console.log('\n=== 消息区深度扫描 ===');
      console.log(JSON.stringify(msgDeep, null, 2));

      const selProbe = await page.evaluate(PROBE_SELECTORS).catch((e) => [{ error: String(e).slice(0, 120) }]);
      save('probe-selectors.json', { stage: 'selectors', at: new Date().toISOString(), probe: selProbe });
      console.log('\n=== BossSelectors 逐项命中 ===');
      for (const r of selProbe) {
        console.log(`${String(r.count).padStart(3)} | ${r.name} | ${r.sel}${r.err ? ' | ERR ' + r.err : ''}`);
      }
    }
  }

  log('保留窗口 8 秒后关闭');
  await page.waitForTimeout(8000);
  await context.close().catch(() => {});
  log('完成');
};

main().catch((e) => {
  console.error('取证失败：', e);
  process.exit(1);
});
