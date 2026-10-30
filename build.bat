@echo off
rem ---------------------------------------------------------------------------
rem 一键构建：装配 patchright driver -> 构建控制台（Nuxt）-> 打包后端 jar
rem
rem 编码约定（勿改）：本文件必须保存为 GBK/ANSI + CRLF。
rem 存成 UTF-8 时，cp936 控制台按「两字节一个字」解码，会把行尾换行一起吃掉，
rem 于是 rem 注释里的半截文字被当成命令执行，报错形如
rem   'ion.yaml' 不是内部或外部命令
rem   '鍏堟墽琛?build.bat' 不是内部或外部命令
rem 改完用 cmd 双击跑一遍，确认没有任何「不是内部或外部命令」才算数。
rem ---------------------------------------------------------------------------
setlocal enableextensions
cd /d "%~dp0"

set "__jar=server\target\chat-job-0.1.0-SNAPSHOT.jar"
set "__step=环境检查"

call :need node.exe "Node.js"
if errorlevel 1 goto :fail
call :need npm.cmd "npm（随 Node.js 一起安装）"
if errorlevel 1 goto :fail
call :need mvn.cmd "Maven"
if errorlevel 1 goto :fail
call :need java.exe "JDK 21"
if errorlevel 1 goto :fail

echo [1/3] 装配 patchright driver
set "__step=1/3 装配 patchright driver"
node scripts\install-patchright.mjs
if errorlevel 1 goto :fail

echo.
echo [2/3] 构建控制台（Nuxt 静态产物）
set "__step=2/3 构建控制台"
pushd console
if not exist node_modules (
  echo     未发现 node_modules，先执行 npm install（首次比较慢，属正常）
  call npm install
  if errorlevel 1 (
    set "__rc=%errorlevel%"
    set "__step=2/3 npm install（console）"
    popd
    goto :fail
  )
)
call npm run build
if errorlevel 1 (
  set "__rc=%errorlevel%"
  set "__step=2/3 npm run build（console）"
  popd
  goto :fail
)
popd

echo.
echo [3/3] 打包后端
set "__step=3/3 打包后端（mvn package）"
call mvn -B -f server\pom.xml -DskipTests package
if errorlevel 1 goto :fail

echo.
echo [OK] 构建完成。
if exist "%__jar%" (
  echo      产物：%__jar%
) else (
  echo      [!] 未找到预期产物 %__jar%，请检查 server\pom.xml 是否改过 finalName
)
echo      下一步：双击 start.bat 启动，浏览器打开 http://127.0.0.1:8080
echo.
call :hold
exit /b 0

:fail
if not defined __rc set "__rc=%errorlevel%"
echo.
echo [x] 构建失败，中断于：%__step%
echo     退出码：%__rc%
echo     请往上看最近的一段报错，那才是真正原因。
echo.
call :hold
exit /b %__rc%

:need
where %~1 >nul 2>nul
if errorlevel 1 (
  echo [x] 未找到 %~1，请先安装 %~2 并确保它在 PATH 里。
  exit /b 1
)
exit /b 0

:hold
echo 按任意键关闭窗口...
pause >nul
exit /b 0
