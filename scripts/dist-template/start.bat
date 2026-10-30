@echo off
rem ---------------------------------------------------------------------------
rem chat-job 启动脚本（绿色版 / 分发包）
rem
rem 本文件由 scripts/build-dist.mjs 从 scripts/dist-template/start.bat 转码生成，
rem 最终产物是 GBK/ANSI + CRLF。不要直接改 dist 里的那份，改模板。
rem
rem 为什么必须是 GBK：cp936 控制台按"两字节一个字"解码 UTF-8 中文时会把行尾换行
rem 一起吃掉，于是 rem 注释里的半截文字被当成命令执行，出现一堆
rem 'xxx 不是内部或外部命令'。改完请双击跑一遍确认窗口里没有这类提示。
rem ---------------------------------------------------------------------------
setlocal enableextensions
cd /d "%~dp0"

rem ---- 端口：全包唯一来源，改这一行 ----
rem 显式传 --server.port，压住 application.yaml 与环境里的 SERVER_PORT；
rem 临时换端口：start.bat 8081   或   set CHAT_JOB_PORT=8081 再双击。
set "__port=8080"
if defined CHAT_JOB_PORT set "__port=%CHAT_JOB_PORT%"
if not "%~1"=="" set "__port=%~1"
echo %__port%|findstr /r /c:"^[0-9][0-9]*$" >nul
if errorlevel 1 (
  echo [x] 端口 "%__port%" 不是纯数字，现在停在这里比跑到一半报错好。
  echo.
  pause
  exit /b 1
)
set "__url=http://127.0.0.1:%__port%/"

rem 数据锚点钉在本目录。不设也能靠 ProjectHome 反查，但显式写死可防止
rem 以后目录结构变动时数据跑到别处（表现为"配置改了没生效""记录不见了"）。
set "CHAT_JOB_HOME=%~dp0"

if not exist data mkdir data
if not exist "data\logs" mkdir "data\logs"

if not exist "app\chat-job.jar" (
  echo [x] 缺少 app\chat-job.jar，包不完整，请重新解压。
  echo.
  pause
  exit /b 1
)

rem ---- 内置 Java 运行时：优先用包里的，没有才退回 PATH 里的 java ----
set "__java=%~dp0runtime\jre\bin\java.exe"
if exist "%__java%" goto :havejava
echo [!] 未找到内置运行时 runtime\jre，改用 PATH 里的 java。
set "__java=java"
where java.exe >nul 2>nul
if errorlevel 1 (
  echo [x] 既没有内置运行时，PATH 里也没有 java，无法启动。
  echo     请重新解压完整分包，或安装 JDK 21。
  echo.
  pause
  exit /b 1
)
:havejava

if not exist "build\patchright-driver\package\cli.js" (
  echo [!] 缺少 build\patchright-driver，任务启动时会被拒绝（fail-closed，不会静默降级）。
  echo.
)
if not exist "runtime\node.exe" (
  echo [!] 缺少 runtime\node.exe，浏览器驱动跑不起来。
  echo.
)

echo.
echo   控制台：%__url%
echo   数据：  %~dp0data
echo   关闭这个窗口即停止服务。
echo.

rem 后台探测：确认应答的确实是 chat-job 再开页面，避免端口被占时把人带到别人的页面。
rem 自测时设 CHAT_JOB_NO_OPEN=1 可跳过自动开页面。
if /i "%CHAT_JOB_NO_OPEN%"=="1" goto :run
if not exist "runtime\node.exe" goto :run
if not exist "scripts\open-when-ready.mjs" goto :run
start "" /b "%~dp0runtime\node.exe" "%~dp0scripts\open-when-ready.mjs" %__port%

:run
"%__java%" -jar "app\chat-job.jar" --server.address=127.0.0.1 --server.port=%__port%
set "__rc=%errorlevel%"

if not "%__rc%"=="0" (
  echo.
  echo [x] 服务异常退出，退出码 %__rc%。原因见 data\logs\chat-job.log 末尾。
  echo.
  pause
)
exit /b %__rc%
