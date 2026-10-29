#!/usr/bin/env node
/**
 * 装配 patchright driver。
 *
 * playwright-java 只是个壳，真正干活的是它拉起的 Node driver 进程。patchright 是打过
 * 反检测补丁的 Playwright（禁用 Console API 泄漏、去掉 --enable-automation、
 * 屏蔽 navigator.webdriver 等），官方没有 Java 绑定；但 Java 客户端支持
 * -Dplaywright.cli.dir 指向一个预装的 driver 目录，于是可以让 Java 客户端去驱动
 * patchright 的 Node driver。
 *
 * 目录结构要求（见 com.microsoft.playwright.impl.driver.Driver）：
 *   <driverDir>/package/cli.js
 *
 * 版本必须与 server/pom.xml 里的 playwright.version 同 minor：
 * 当前锁 1.62.x（playwright-java 1.62.0 + patchright-core 1.62.1）。
 * 不要降到 1.51 —— patchright-core 1.51.3 的 locator.count() 是坏的。
 *
 * 实现说明：用 `npm pack` 取单个 tarball 再解包，而不是 `npm install --prefix`。
 * tarball 顶层目录就叫 package/，解开即是我们要的形状，既不用 cpSync 复制整棵树，
 * 也不用事后 rmSync 掉 node_modules（那是一次上百项的批量删除，容易触发宿主侧的
 * 安全删除确认）。整个流程只落地一个 .tgz，用完删掉即可。
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync, readdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const PATCHRIGHT_VERSION = '1.62.1'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const driverDir = join(root, 'build', 'patchright-driver')
const packageDir = join(driverDir, 'package')
const marker = join(driverDir, '.version')

function ready() {
  if (!existsSync(marker) || !existsSync(join(packageDir, 'cli.js'))) return false
  return readFileSync(marker, 'utf8').trim() === PATCHRIGHT_VERSION
}

if (ready()) {
  console.log(`patchright-core ${PATCHRIGHT_VERSION} 已就绪: ${packageDir}`)
  process.exit(0)
}

console.log(`正在装配 patchright-core@${PATCHRIGHT_VERSION} …`)
rmSync(driverDir, { recursive: true, force: true })
mkdirSync(driverDir, { recursive: true })

const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
execFileSync(
  npm,
  ['pack', `patchright-core@${PATCHRIGHT_VERSION}`, '--pack-destination', driverDir,
    '--loglevel=error'],
  { stdio: 'inherit', shell: process.platform === 'win32' }
)

const tgz = readdirSync(driverDir).find((f) => f.endsWith('.tgz'))
if (!tgz) {
  console.error(`npm pack 没产出 tarball，目录内容: ${readdirSync(driverDir).join(', ') || '(空)'}`)
  process.exit(1)
}

// tarball 顶层就是 package/，直接解到 driverDir 即得到 <driverDir>/package/cli.js。
// 必须 cwd 进目录用相对文件名：Git Bash 里的 GNU tar 会把 "C:\..." 中的 C: 当成
// 远程主机（rsh），报 "Cannot connect to C: resolve failed"。
execFileSync('tar', ['-xzf', tgz], { stdio: 'inherit', cwd: driverDir })

if (!existsSync(join(packageDir, 'cli.js'))) {
  console.error(`解包后没找到 cli.js: ${packageDir}`)
  process.exit(1)
}

writeFileSync(marker, PATCHRIGHT_VERSION)
rmSync(join(driverDir, tgz), { force: true })

console.log(`patchright driver 已装配: ${packageDir}`)
console.log('下一步：确认 node 在 PATH 里（driver 用本机 node 运行），然后启动服务。')
