@echo off
rem 一键构建：装配 patchright driver → 构建控制台 → 打包后端
setlocal
cd /d "%~dp0"

echo [1/3] 装配 patchright driver
node scripts\install-patchright.mjs
if errorlevel 1 goto :fail

echo [2/3] 构建控制台（Nuxt 静态产物）
pushd console
if not exist node_modules (
  call npm install
  if errorlevel 1 goto :fail
)
call npm run build
if errorlevel 1 goto :fail
popd

echo [3/3] 打包后端
call mvn -B -f server\pom.xml -DskipTests package
if errorlevel 1 goto :fail

echo.
echo 构建完成。双击 start.bat 启动，浏览器打开 http://127.0.0.1:8080
exit /b 0

:fail
echo.
echo [!] 构建失败，请查看上方输出
exit /b 1
