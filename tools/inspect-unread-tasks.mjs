#!/usr/bin/env node
/**
 * 未读任务查看器（只执行 SELECT，不改库）。
 *
 * 用途：验证 spec 0002 P1 的「失败 → 退避重试 → 3 次后 GAVE_UP」这条链。
 * 控制台面板要到 P4 才有，在那之前这里是唯一能看见任务去向的地方。
 *
 * 用法：
 *   node tools/inspect-unread-tasks.mjs            # 汇总 + 最近 20 条
 *   node tools/inspect-unread-tasks.mjs --due      # 只看当前可重试的
 *   node tools/inspect-unread-tasks.mjs --all      # 最近 100 条
 *
 * 说明：库是 WAL 模式，用 node:sqlite 只读打开在库不干净时可能直接报错，
 * 所以这里以默认模式打开但**只跑 SELECT** —— 不写、不建表、不 checkpoint。
 */
import { DatabaseSync } from 'node:sqlite';
import { existsSync } from 'node:fs';
import path from 'node:path';

const dbPath = path.resolve('server/data/chat-job.db');
if (!existsSync(dbPath)) {
    console.error(`找不到数据库：${dbPath}\n（在项目根目录执行；server 的工作目录是 server/，所以库在 server/data/）`);
    process.exit(1);
}

const args = new Set(process.argv.slice(2));
const db = new DatabaseSync(dbPath);

const hasTable = db.prepare(
    "SELECT name FROM sqlite_master WHERE type='table' AND name='unread_task'").get();
if (!hasTable) {
    console.error('unread_task 表不存在：V5 迁移还没应用。启动一次后端即可（Flyway 自动迁移）。');
    db.close();
    process.exit(1);
}

const ai = db.prepare('SELECT ai_reply_enabled FROM ai_config LIMIT 1').get();
console.log(`AI 回复开关：${ai?.ai_reply_enabled === 1 ? '已打开' : '关闭（值为 0，值守走改造前路径）'}`);

const statuses = db.prepare(
    'SELECT status, COUNT(*) AS n FROM unread_task GROUP BY status ORDER BY status').all();
console.log('\n状态分布：' + (statuses.length
    ? statuses.map(r => `${r.status}=${r.n}`).join('  ')
    : '（空，还没有任何任务）'));

const now = new Date();
const pad = n => String(n).padStart(2, '0');
const ts = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T`
    + `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;

const due = db.prepare(
    "SELECT COUNT(*) AS n FROM unread_task WHERE status='FAILED' "
    + 'AND attempt_count < max_attempts AND next_retry_at IS NOT NULL AND next_retry_at <= ?').get(ts);
console.log(`当前可重试（已到退避时间）：${due.n}`);

const limit = args.has('--all') ? 100 : 20;
const where = args.has('--due')
    ? "WHERE status='FAILED' AND attempt_count < max_attempts AND next_retry_at <= '" + ts + "'"
    : '';
const rows = db.prepare(
    `SELECT id, platform_conversation_id, status, attempt_count, max_attempts,
            last_action, last_reason_code, next_retry_at, last_error, updated_at
     FROM unread_task ${where} ORDER BY id DESC LIMIT ${limit}`).all();

console.log(`\n任务（最近 ${rows.length} 条，按 id 倒序）：`);
if (!rows.length) {
    console.log('  （空）');
} else {
    console.log('  id  会话              状态             次数  动作            下次重试             错误');
    for (const r of rows) {
        const attempts = `${r.attempt_count}/${r.max_attempts}`;
        console.log([
            String(r.id).padEnd(4),
            String(r.platform_conversation_id ?? '').slice(0, 14).padEnd(16),
            String(r.status).padEnd(16),
            attempts.padEnd(5),
            String(r.last_action ?? '-').padEnd(15),
            String(r.next_retry_at ?? '-').padEnd(20),
            (r.last_error ?? '').slice(0, 40),
        ].join(' '));
    }
}
db.close();
