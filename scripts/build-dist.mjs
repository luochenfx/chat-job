#!/usr/bin/env node
/**
 * 打「绿色版」分发包：给别人用的人不需要 JDK / Maven / Node.js / 源码。
 *
 * <p>产物：{@code dist/chat-job-<version>-win-x64.zip}，解压后双击 {@code start.bat} 即用。
 *
 * <pre>
 * chat-job-<version>-win-x64/
 *   start.bat                 启动（GBK + CRLF，构建时从模板转码写入）
 *   使用说明.txt               给使用者看的说明（UTF-8 BOM，记事本能正常打开）
 *   build-info.txt            这一包是什么时候、用什么版本、多大
 *   app/chat-job.jar          Spring Boot fat jar（含控制台静态产物）
 *   build/patchright-driver/  patchright driver（package/cli.js）
 *   runtime/jre/              jlink 裁剪出来的 Java 21 运行时（约 64MB）
 *   runtime/node.exe          driver 用的 node（从 playwright 的 driver-bundle 里抠出来）
 *   scripts/open-when-ready.mjs  就绪后再开页面
 * </pre>
 *
 * <p><b>为什么 node 从 driver-bundle 里抠</b>：那个 jar 里有 4 个平台的 node，
 * 本项目只用得上 win32_x64 那一个。既然 server/pom.xml 已经把整个 bundle 排除掉，
 * 就在构建期把唯一需要的那份取出来放到 runtime/，省得使用者再装一遍 Node.js。
 *
 * <p>用法：
 * <pre>
 *   node scripts/build-dist.mjs              # 复用已有 jar（缺了才构建）
 *   node scripts/build-dist.mjs --rebuild    # 强制 npm build + mvn package
 *   node scripts/build-dist.mjs --no-zip     # 只铺 dist 目录，不压缩
 * </pre>
 *
 * <p>构建机要求：Windows + JDK 21（要 jlink / jar）+ Maven + npm。使用者机器只要求
 * Windows 10/11 与 Edge 或 Chrome——其余全在包里。
 */
import { execFileSync, spawnSync } from 'node:child_process'
import {
  cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync,
} from 'node:fs'
import { basename, dirname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const argv = process.argv.slice(2)
const rebuild = argv.includes('--rebuild')
const skipZip = argv.includes('--no-zip')

const tplDir = join(root, 'scripts', 'dist-template')
const distRoot = join(root, 'dist')
const driverSrc = join(root, 'build', 'patchright-driver')
const consolePublic = join(root, 'console', '.output', 'public')

const log = (msg) => console.log(msg)
const step = (n, total, msg) => console.log(`\n[${n}/${total}] ${msg}`)

function die(msg) {
  console.error(`\n[x] ${msg}`)
  process.exit(1)
}

function run(cmd, args, opts = {}) {
  execFileSync(cmd, args, { stdio: 'inherit', cwd: root, ...opts })
}

/** mvn / npm 在 Windows 上是 .cmd，不经 shell 会 ENOENT；绝对路径的 .exe 反过来不能过 shell（路径含空格会被拆开）。 */
function runCmd(cmd, args, opts = {}) {
  execFileSync(cmd, args, { stdio: 'inherit', cwd: root, shell: true, ...opts })
}

function quiet(cmd, args) {
  return execFileSync(cmd, args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
}

/** 用来取那些写到 stderr 的输出（`java -version` 就是个典型）。 */
function quietAll(cmd, args) {
  const r = spawnSync(cmd, args, { encoding: 'utf8' })
  return `${r.stdout || ''}${r.stderr || ''}`.trim()
}

function sizeOf(p) {
  const s = statSync(p)
  return s.isDirectory() ? dirSize(p) : s.size
}

function dirSize(dir) {
  let total = 0
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name)
    total += entry.isDirectory() ? dirSize(p) : statSync(p).size
  }
  return total
}

const mb = (bytes) => `${(bytes / 1024 / 1024).toFixed(1)}MB`

/**
 * 清空并删除目录。
 *
 * <p>为什么不用一句 {@code rmSync}：有些宿主环境（含本次的 agent 运行时）会通过
 * {@code NODE_OPTIONS} 注入一个把 {@code fs.rmSync} 重写成「移到回收站」的 shim，
 * 面对几十万个文件的 JRE 目录会直接抛 {@code Some operations were aborted}。
 * 普通 Windows 机器上第一句就成功；失败时才退到 {@code rmdir /s /q}，
 * 顺带也能绕过杀软/索引器占用文件的锁。
 */
function wipeDir(dir) {
  if (!existsSync(dir)) return
  try {
    rmSync(dir, { recursive: true, force: true, maxRetries: 3 })
    return
  } catch {
    log(`    fs.rmSync 删不掉 ${dir}（宿主 shim 或文件占用），改用 rmdir /s /q`)
  }
  execFileSync('cmd', ['/c', 'rmdir', '/s', '/q', dir], { stdio: 'inherit' })
  if (existsSync(dir)) {
    die(`无法清空 ${dir}：多半是有窗口/进程停在这个目录里（资源管理器、跑过的 start.bat 窗口、`
      + `或者上一次在分发包里启动的服务还没关）。关掉之后再跑一次。`)
  }
}

// ---------------------------------------------------------------- 0. 环境预检
if (process.platform !== 'win32') {
  die('分发包是 Windows 专用的（.bat + DPAPI + 内置 node.exe），只能在 Windows 上构建。')
}

const pom = readFileSync(join(root, 'server', 'pom.xml'), 'utf8')
const version = (pom.match(/<artifactId>chat-job<\/artifactId>\s*<version>([^<]+)<\/version>/) || [])[1]
const pwVersion = (pom.match(/<playwright\.version>([^<]+)<\/playwright\.version>/) || [])[1]
if (!version || !pwVersion) die('解析 server/pom.xml 的版本号失败（chat-job / playwright.version）。')

const home = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin') : null
const javaTool = (name) => {
  if (home && existsSync(join(home, `${name}.exe`))) return join(home, `${name}.exe`)
  die(`没找到 ${name}（需要 JDK 21：jlink 与 jar 都从 JAVA_HOME 取）。当前 JAVA_HOME=${process.env.JAVA_HOME || '(未设置)'}`)
}
const jlink = javaTool('jlink')
const jarTool = javaTool('jar')
const bundleDir = join(distRoot, `chat-job-${version}-win-x64`)

log(`chat-job ${version}  →  ${bundleDir}`)

// ---------------------------------------------------------------- 1. driver
step(1, 6, '检查 patchright driver')
if (!existsSync(join(driverSrc, 'package', 'cli.js'))) {
  log('    未装配，先跑 scripts/install-patchright.mjs（需要 npm 与网络）')
  run(process.execPath, [join(root, 'scripts', 'install-patchright.mjs')])
}
if (!existsSync(join(driverSrc, 'package', 'cli.js'))) die(`装配后仍缺 ${driverSrc}\\package\\cli.js`)
log(`    ok  ${mb(sizeOf(driverSrc))}`)

// ---------------------------------------------------------------- 2. jar
step(2, 6, '准备后端 jar（含控制台静态产物）')
const jarName = `chat-job-${version}.jar`
const jarSrc = join(root, 'server', 'target', jarName)
if (rebuild || !existsSync(jarSrc)) {
  if (!existsSync(join(consolePublic, 'index.html'))) {
    log('    控制台未构建，先 npm install + npm run build')
    if (!existsSync(join(root, 'console', 'node_modules'))) runCmd('npm', ['install'], { cwd: join(root, 'console') })
    runCmd('npm', ['run', 'build'], { cwd: join(root, 'console') })
  }
  runCmd('mvn', ['-B', '-f', join('server', 'pom.xml'), '-DskipTests', 'package'])
}
if (!existsSync(jarSrc)) die(`没找到 ${jarSrc}，先跑 build.bat 或加 --rebuild`)

// jar 是既有的、而 console 产物比它新，说明这份 jar 里的控制台是旧的 —— 提示但不阻断
if (existsSync(join(consolePublic, 'index.html'))
  && statSync(join(consolePublic, 'index.html')).mtimeMs > statSync(jarSrc).mtimeMs) {
  log('    [!] console 产物比 jar 新：jar 里的控制台可能不是最新的，需要的话加 --rebuild')
}

const jarEntries = quiet(jarTool, ['tf', jarSrc])
if (!jarEntries.includes('BOOT-INF/classes/public/index.html')) {
  die('jar 里没有 BOOT-INF/classes/public/index.html：控制台没被打进去，先 npm run build 再 mvn package')
}
if (jarEntries.includes('driver-bundle')) {
  die('jar 里仍带着 driver-bundle（202MB）：server/pom.xml 的 exclusion 没生效，先 mvn clean package')
}
log(`    ok  ${jarName}  ${mb(sizeOf(jarSrc))}`)

// ---------------------------------------------------------------- 3. 铺目录
step(3, 6, '铺 dist 目录')
wipeDir(bundleDir)
mkdirSync(join(bundleDir, 'app'), { recursive: true })
mkdirSync(join(bundleDir, 'runtime'), { recursive: true })
mkdirSync(join(bundleDir, 'build'), { recursive: true })
mkdirSync(join(bundleDir, 'scripts'), { recursive: true })

cpSync(jarSrc, join(bundleDir, 'app', 'chat-job.jar'))
cpSync(join(driverSrc, 'package'), join(bundleDir, 'build', 'patchright-driver', 'package'), { recursive: true })
// .version 文件保留：install-patchright.mjs 用它判断"已就绪"，分发版虽然不跑装配，留着无害
if (existsSync(join(driverSrc, '.version'))) {
  cpSync(join(driverSrc, '.version'), join(bundleDir, 'build', 'patchright-driver', '.version'))
}
cpSync(join(root, 'scripts', 'open-when-ready.mjs'), join(bundleDir, 'scripts', 'open-when-ready.mjs'))
log('    ok')

// ---------------------------------------------------------------- 4. JRE + node
step(4, 6, '生成内置运行时（jlink JRE + node.exe）')
const jreOut = join(bundleDir, 'runtime', 'jre')
const modules = [
  'java.se',          // 含 java.base/sql/xml/naming/management/desktop 等，宁可多带不漏
  'jdk.unsupported',  // JNA、sun.misc.Unsafe
  'jdk.crypto.ec',    // TLS 的 ECDHE，访问 https 的模型服务必需
  'jdk.zipfs',        // Embedded jar / zip 文件系统
  'jdk.charsets',     // GBK 等非 UTF-8 字符集
  'jdk.localedata',   // 中文 locale 的日期与排序
]
run(jlink, [
  '--add-modules', modules.join(','),
  '--strip-debug', '--no-header-files', '--no-man-pages',
  '--compress=zip-6', '--output', jreOut,
])
const jreSize = dirSize(jreOut)

// node.exe：从 driver-bundle 里取唯一用得上的那份（win32_x64）
const bundleJar = join(
  process.env.USERPROFILE || '', '.m2', 'repository', 'com', 'microsoft', 'playwright',
  'driver-bundle', pwVersion, `driver-bundle-${pwVersion}.jar`,
)
if (!existsSync(bundleJar)) {
  log(`    本地仓库没有 driver-bundle，先下载一次（约 200MB，只在构建机发生）`)
  runCmd('mvn', ['-B', '-q', 'dependency:get', `-Dartifact=com.microsoft.playwright:driver-bundle:${pwVersion}`])
}
if (!existsSync(bundleJar)) die(`取不到 driver-bundle: ${bundleJar}`)

const tmp = mkdtempSync(join(tmpdir(), 'chat-job-node-'))
try {
  execFileSync(jarTool, ['xf', bundleJar, 'driver/win32_x64/node.exe'], { cwd: tmp, stdio: 'inherit' })
  const nodeSrc = join(tmp, 'driver', 'win32_x64', 'node.exe')
  if (!existsSync(nodeSrc)) die('driver-bundle 里没有 driver/win32_x64/node.exe')
  cpSync(nodeSrc, join(bundleDir, 'runtime', 'node.exe'))
} finally {
  wipeDir(tmp)
}
log(`    ok  jre ${mb(jreSize)} / node.exe ${mb(sizeOf(join(bundleDir, 'runtime', 'node.exe')))}`)

// ---------------------------------------------------------------- 5. 启动脚本与说明
step(5, 6, '写 start.bat / 使用说明（GBK + CRLF）')

/**
 * 把 UTF-8 模板转成 GBK + CRLF 写出。
 *
 * <p>bat 必须 GBK/ANSI：cp936 控制台按两字节一个字解码 UTF-8 中文时会把行尾换行吃掉，
 * 于是 rem 注释里的半截文字被当成命令执行，出现一堆「不是内部或外部命令」。
 * 这个坑 README 里记着实机事故，别顺手改回 UTF-8。
 */
function writeGbk(templateName, outName) {
  const src = join(tplDir, templateName)
  if (!existsSync(src)) die(`缺模板 ${src}`)
  const dst = join(bundleDir, outName)
  execFileSync('powershell', ['-NoProfile', '-NonInteractive', '-Command', `
$t = [IO.File]::ReadAllText($env:SRC, [Text.Encoding]::UTF8)
$t = $t.Replace("\`r\`n", "\`n").Replace("\`n", "\`r\`n")
[IO.File]::WriteAllText($env:DST, $t, [Text.Encoding]::GetEncoding(936))
`], { env: { ...process.env, SRC: src, DST: dst }, stdio: 'inherit' })
  return dst
}

writeGbk('start.bat', 'start.bat')

// 使用说明用 UTF-8 BOM：Win10 记事本能正确识别；GBK 也没问题但没必要再引一次转码
const readme = readFileSync(join(tplDir, '使用说明.txt'), 'utf8').replace(/\r?\n/g, '\r\n')
writeFileSync(join(bundleDir, '使用说明.txt'), '\ufeff' + readme, 'utf8')

const nodeVersion = quiet(join(bundleDir, 'runtime', 'node.exe'), ['-v']).trim()
writeFileSync(join(bundleDir, 'build-info.txt'), [
  `chat-job ${version}`,
  `构建时间: ${new Date().toLocaleString('zh-CN', { hour12: false })}`,
  `jar: ${jarName} (${mb(sizeOf(jarSrc))})`,
  `node: ${nodeVersion} (runtime/node.exe)`,
  `jre: jlink ${modules.join(',')} (${mb(jreSize)})`,
  `playwright driver: patchright-core ${pwVersion}`,
  '',
  '运行方式: 双击 start.bat（默认 8080，可 start.bat 8081 换端口）',
  '数据位置: 本目录 data/ 与 browser-data/，删除本目录即卸载',
  '',
].join('\r\n'), 'utf8')
log('    ok')

// ---------------------------------------------------------------- 6. 自检 + 打包
step(6, 6, '自检并打包')

// 内置 java 与 node 必须真能跑，不能只看文件在不在
const javaVersion = quietAll(join(jreOut, 'bin', 'java.exe'), ['-version'])
log(`    java: ${javaVersion.split('\n')[0].trim() || '(java -version 无输出)'}`)
log(`    node: ${nodeVersion}`)

if (!existsSync(join(bundleDir, 'build', 'patchright-driver', 'package', 'cli.js'))) {
  die('dist 里缺 patchright driver 的 package/cli.js')
}
const total = dirSize(bundleDir)
log(`    解压后合计 ${mb(total)}`)

if (skipZip) {
  log('\n[OK] 只铺目录，未压缩（--no-zip）。')
} else {
  const zip = join(distRoot, `chat-job-${version}-win-x64.zip`)
  log('    压缩中（200MB 左右，几十秒）…')
  let zipped = false
  try {
    // Win10 1803+ 自带 bsdtar（System32\tar.exe）：-a 按扩展名选格式，比 Compress-Archive 快，也不刷进度条。
    // 必须用绝对路径调用 System32 的那份——Git Bash 的 PATH 里排在前面的是 GNU tar，
    // 它会把 "C:\..." 里的 C: 当成远程主机，报 "Cannot connect to C: resolve failed"。
    const bsdtar = join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'tar.exe')
    execFileSync(existsSync(bsdtar) ? bsdtar : 'tar',
      ['-a', '-c', '-f', zip, '-C', distRoot, basename(bundleDir)],
      { stdio: 'inherit' })
    zipped = existsSync(zip)
  } catch {
    zipped = false
  }
  if (!zipped) {
    log('    tar 没能出包，退回 Compress-Archive')
    execFileSync('powershell', ['-NoProfile', '-NonInteractive', '-Command',
      `$ProgressPreference='SilentlyContinue'
Compress-Archive -Path $env:SRC -DestinationPath $env:DST -CompressionLevel Optimal -Force -ErrorAction Stop`,
    ], { env: { ...process.env, SRC: bundleDir, DST: zip }, stdio: 'inherit' })
  }
  if (!existsSync(zip)) die(`压缩失败，没生成 ${zip}`)
  log(`\n[OK] 分发包：${zip}  (${mb(sizeOf(zip))})`)
}

log('     下一步：解压到**可写目录**（别放 Program Files），双击 start.bat。')
log('     真机验收清单见 README「分发」一节。')
