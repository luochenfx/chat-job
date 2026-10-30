@echo off
rem ---------------------------------------------------------------------------
rem chat-job 启动脚本（Windows，生产式：java -jar）
rem
rem 编码约定（勿改）：本文件必须保存为 GBK/ANSI + CRLF。
rem 存成 UTF-8 时，cp936 控制台按「两字节一个字」解码，会把行尾换行一起吃掉，
rem 于是 rem 注释里的半截文字被当成命令执行，报错形如
rem   'ion.yaml' 不是内部或外部命令
rem 改完用 cmd 双击跑一遍，确认没有任何「不是内部或外部命令」才算数。
rem ---------------------------------------------------------------------------
setlocal enableextensions
cd /d "%~dp0"

rem ---- 端口：全仓库唯一来源就是下面这一行 ----
rem 本脚本把 --server.port 显式传给 Spring。命令行参数优先级最高，压得住
rem application.yaml 与环境变量里的 SERVER_PORT / SERVER__PORT，
rem 所以「改 yaml 的 server.port」其实不生效——换端口只改这里。
rem 临时换端口（不改文件）：start.bat 8081    或    set CHAT_JOB_PORT=8081 后再跑
rem 自动打开的页面用同一个变量拼出来，不会出现「开了 8080 其实跑在 8081」。
set "__port=8080"
if defined CHAT_JOB_PORT set "__port=%CHAT_JOB_PORT%"
if not "%~1"=="" set "__port=%~1"
echo %__port%|findstr /r /c:"^[0-9][0-9]*$" >nul
if errorlevel 1 (
  echo [x] 端口 "%__port%" 不是纯数字。改成 8080 这样的数再试。
  echo.
  pause
  exit /b 1
)
set "__url=http://127.0.0.1:%__port%/"
set "__jar=server\target\chat-job-0.1.0-SNAPSHOT.jar"

if not exist data mkdir data
if not exist data\logs mkdir data\logs

where java.exe >nul 2>nul
if errorlevel 1 (
  echo [x] 未找到 java，请先安装 JDK 21 并确保它在 PATH 里。
  echo.
  pause
  exit /b 1
)

if not exist "build\patchright-driver\package\cli.js" (
  echo [!] 未找到 patchright driver，反检测能力将不可用，任务会被拒绝启动。
  echo     请先执行：node scripts\install-patchright.mjs
  echo.
)

if not exist "%__jar%" (
  echo [x] 未找到后端 jar：%__jar%
  echo     请先执行 build.bat
  echo.
  pause
  exit /b 1
)

echo 控制台地址：%__url%
echo 服务能应答之后会自动打开浏览器（由 scripts\open-when-ready.mjs 轮询触发）。
echo 日志首行会打印「运行时数据位置」，核对数据库路径。
echo 只绑本机回环，不对外监听。
echo.

rem 后台起一个探测进程：它自己轮询 /api/system/status，确认对面确实是 chat-job 才开浏览器。
rem 端口被别的程序占着时它不会开页面，只在窗口里留一行提示。
where node.exe >nul 2>nul
if errorlevel 1 (
  echo [!] 未找到 node，无法自动打开控制台页面。就绪后请手动访问 %__url%
  echo.
) else (
  start "" /b node "scripts\open-when-ready.mjs" %__port%
)

java -jar "%__jar%" --server.address=127.0.0.1 --server.port=%__port%
set "__rc=%errorlevel%"

if not "%__rc%"=="0" (
  echo.
  echo [x] 服务已退出，退出码 %__rc%。往上翻日志看原因。
  echo.
  pause
)
exit /b %__rc%
