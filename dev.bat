@echo off
rem ---------------------------------------------------------------------------
rem 开发模式启动：直接从源码跑，改完 java 重启即可，不用打包。
rem
rem 编码约定（勿改）：本文件必须保存为 GBK/ANSI + CRLF。
rem 存成 UTF-8 时，cp936 控制台会把中文按两字节拆分并吃掉行尾换行，
rem 表现为注释里的半截文字被当成命令执行。改完务必双击跑一遍确认无报错。
rem
rem 四个必须显式处理的坑：
rem   1) 工作目录。spring-boot:run 的 workingDirectory 是 File 类型参数，Maven 会
rem      「按模块 basedir（server/）解析相对路径」——所以写 workingDirectory=. 得到的是
rem      server/，而不是仓库根。曾经因此把数据库写到 server/data，与 start.bat 的 /data
rem      分成两份，表现为「配置改了没生效」「投递记录不见了」。这里传绝对路径。
rem      数据落点本身已由 chat-job.home 锚定（见 ProjectHome），cwd 不再影响它；
rem      这条只为让进程的当前目录与 start.bat 保持一致。
rem   2) 端口。宿主/IDE 环境可能注入 SERVER__PORT（Spring 的宽松绑定会把它当成
rem      server.port），覆盖 yaml 里的 8080，表现为「配了 8080 却监听到随机端口」。
rem      这里显式传参压住，端口值同样只认本文件的 __port，与 start.bat 保持一致。
rem   3) 启动后请看日志首行的「运行时数据位置」，它打印数据库的绝对路径。发现两行
rem      不同的路径就是又跑偏了，不要靠猜。
rem   4) 控制台页面同样自动打开，机制见 scripts\open-when-ready.mjs。
rem ---------------------------------------------------------------------------
setlocal enableextensions
cd /d "%~dp0"

rem ---- 端口：与 start.bat 同一套规则（改端口两处都要改）----
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

if not exist data mkdir data
if not exist data\logs mkdir data\logs

where mvn.cmd >nul 2>nul
if errorlevel 1 (
  echo [x] 未找到 mvn，请先安装 Maven 并确保它在 PATH 里。
  echo.
  pause
  exit /b 1
)
where java.exe >nul 2>nul
if errorlevel 1 (
  echo [x] 未找到 java，请先安装 JDK 21 并确保它在 PATH 里。
  echo.
  pause
  exit /b 1
)

echo 开发模式启动，控制台地址：%__url%
echo 服务能应答之后会自动打开浏览器（由 scripts\open-when-ready.mjs 轮询触发）。
echo.

where node.exe >nul 2>nul
if errorlevel 1 (
  echo [!] 未找到 node，无法自动打开控制台页面。就绪后请手动访问 %__url%
  echo.
) else (
  start "" /b node "scripts\open-when-ready.mjs" %__port% 180
)

call mvn -B -f server\pom.xml spring-boot:run ^
  -Dspring-boot.run.workingDirectory=%~dp0. ^
  -Dspring-boot.run.arguments="--server.address=127.0.0.1 --server.port=%__port%"
set "__rc=%errorlevel%"

if not "%__rc%"=="0" (
  echo.
  echo [x] 进程已退出，退出码 %__rc%。往上翻日志看原因。
  echo.
  pause
)
exit /b %__rc%
