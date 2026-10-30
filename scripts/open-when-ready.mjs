#!/usr/bin/env node
/**
 * 等 chat-job 真的能应答，再打开控制台页面。
 *
 * <p>为什么不直接在 bat 里 {@code start http://127.0.0.1:8080}：JVM + Flyway 起来要几秒到几十秒，
 * 固定 {@code timeout /t N} 猜时间会不可靠——猜短了打开是白页，猜长了白等；端口被别的程序占着时
 * 还会把人带到别人的页面上。这里轮询 {@code /api/system/status}，只认自己的接口。
 *
 * <p>判定"是自己的服务"：返回 200 且 JSON 里同时有 {@code driverAvailable} 与 {@code serverTime}。
 * 端口上应答了但不是这两个字段，说明被别的程序占了 → 打印提示且**不开**浏览器。
 *
 * <p>输出刻意用英文 ASCII：Windows 控制台是 cp936，node 向 stdout 写的是 UTF-8 字节，
 * 中文在这里会显示成乱码。中文提示交给 bat 打。
 *
 * <p>用法：node scripts/open-when-ready.mjs [port=8080] [timeoutSec=90]
 * 环境变量 CHAT_JOB_OPEN_DISABLED=1 只探测不打开（自测用）。
 */
import { spawn } from 'node:child_process';

const port = Number(process.argv[2] ?? 8080);
const timeoutSec = Number(process.argv[3] ?? 90);
const base = `http://127.0.0.1:${port}`;
const target = `${base}/`;
const deadline = Date.now() + timeoutSec * 1000;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** 返回 { answered, isOurs }：answered=端口上有人应答；isOurs=应答者确实是 chat-job。 */
async function probe() {
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), 1500);
  try {
    const res = await fetch(`${base}/api/system/status`, {
      signal: ac.signal,
      headers: { accept: 'application/json' },
    });
    const text = await res.text();
    let isOurs = false;
    try {
      const j = JSON.parse(text);
      isOurs = res.ok && j !== null && typeof j === 'object'
        && 'driverAvailable' in j && 'serverTime' in j;
    } catch {
      // 不是 JSON，肯定不是我们的接口
    }
    return { answered: true, isOurs };
  } catch {
    // 端口还没起来（ECONNREFUSED）或读超时，都当"没应答"
    return { answered: false, isOurs: false };
  } finally {
    clearTimeout(timer);
  }
}

function openBrowser(url) {
  if (process.env.CHAT_JOB_OPEN_DISABLED === '1') {
    console.log(`[open] (disabled) would open ${url}`);
    return;
  }
  const cmd = process.platform === 'win32' ? ['cmd', ['/c', 'start', '', url]]
    : process.platform === 'darwin' ? ['open', [url]]
      : ['xdg-open', [url]];
  try {
    const child = spawn(cmd[0], cmd[1], { detached: true, stdio: 'ignore' });
    child.unref();
  } catch (e) {
    console.error(`[open] failed to launch browser: ${e.message}`);
  }
}

let foreign = 0;
for (;;) {
  const r = await probe();
  if (r.isOurs) {
    console.log(`[open] chat-job is up, opening ${target}`);
    openBrowser(target);
    process.exit(0);
  }
  if (r.answered) {
    // 连续三次看到"别人"才下结论，避开启动瞬间的偶发应答
    if (++foreign >= 3) {
      console.error(`[open] port ${port} answers, but it is NOT chat-job (occupied?). browser not opened.`);
      process.exit(2);
    }
  } else {
    foreign = 0;
  }
  if (Date.now() >= deadline) {
    console.error(`[open] not ready within ${timeoutSec}s, browser not opened. visit ${target} manually.`);
    process.exit(1);
  }
  await sleep(400);
}
