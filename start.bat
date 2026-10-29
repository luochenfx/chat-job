@echo off
rem chat-job 启动脚本（Windows）
rem 端口显式指定：宿主环境可能注入 SERVER_PORT / SERVER__PORT，
rem 那会覆盖 application.yaml 里的 8080，表现为"配置明明写了却没生效"。
setlocal
cd /d "%~dp0"

if not exist data mkdir data
if not exist data\logs mkdir data\logs

if not exist "build\patchright-driver\package\cli.js" (
  echo [!] 未找到 patchright driver，反检测能力将不可用，任务会被拒绝启动。
  echo     请先执行: node scripts\install-patchright.mjs
  echo.
)

if not exist "server\target\chat-job-0.1.0-SNAPSHOT.jar" (
  echo [!] 未找到后端 jar，请先执行 build.bat
  pause
  exit /b 1
)

rem 只绑本机回环，绝不对外监听
java -jar "server\target\chat-job-0.1.0-SNAPSHOT.jar" --server.address=127.0.0.1 --server.port=8080
endlocal
