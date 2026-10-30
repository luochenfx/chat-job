@echo off
rem ---------------------------------------------------------------------------
rem 双击即出包：生成「绿色版」分发包，别人不用装 JDK / Maven / Node.js，也不需要源码。
rem
rem 实际干活的是 scripts\build-dist.mjs，这里只负责三件事：
rem   1) 预检 node / mvn / JAVA_HOME（缺了立刻停，不让人等一分钟才看到报错）
rem   2) 不带参数时默认 --rebuild（双击的语义是「按现在的代码出一份包」）
rem   3) 结束时留住窗口，并把 zip 的绝对路径打在最后，方便直接复制
rem
rem 本文件必须保存为 GBK/ANSI + CRLF。
rem 存成 UTF-8 时，cp936 控制台按两字节解码中文会把行尾换行吃掉，
rem rem 注释里的半截文字被当成命令执行，满屏「不是内部或外部命令」。
rem ---------------------------------------------------------------------------
setlocal enableextensions
cd /d "%~dp0"

set "__step=环境预检"

call :need node.exe "Node.js LTS（npm 随它一起装）"
if errorlevel 1 goto :fail
call :need mvn.cmd "Maven"
if errorlevel 1 goto :fail
call :need java.exe "JDK 21"

rem 只判断「有没有」和「有没有 jlink」。不要写成 %JAVA_HOME:"=% 去引号：
rem 变量未定义时那段替换不展开，JH 会拿到字面量，判断反而失效。
if not defined JAVA_HOME goto :javahome
if not exist "%JAVA_HOME%\bin\jlink.exe" goto :javahome

rem 不带参数时默认 --rebuild：复用旧 jar 会让人以为改了代码却没生效
set "__args=%*"
if "%~1"=="" set "__args=--rebuild"

echo.
echo [*] 开始打包，参数：%__args%
echo     driver -^> jar -^> JRE/node -^> zip，约 0.5~2 分钟，中途别关窗口。
echo.

rem set 的双引号内不处理 ^ 转义，这里的 -> 不能写成 -^>
set "__step=打包（driver -> jar -> JRE/node -> zip）"
call node "scripts\build-dist.mjs" %__args%
if errorlevel 1 goto :fail

rem 取 dist 下按修改时间最新的那个 zip（不依赖版本号，换版本也不用改脚本）
set "__zip="
for /f "delims=" %%f in ('dir /b /o-d "dist\*.zip" 2^>nul') do if not defined __zip set "__zip=%CD%\dist\%%f"

echo.
echo [OK] 生成完毕。
if defined __zip (
  echo      分发包：%__zip%
  echo      直接把这个 zip 发给别人，对方解压后双击 start.bat 即可。
) else (
  echo      [!] dist 下没有 zip（这次是 --no-zip？），只有解压后的目录。
)
echo.
call :hold
exit /b 0

:javahome
set "__rc=1"
echo.
echo [x] 没有可用的 JAVA_HOME。
echo     jlink 与 jar 都从 JAVA_HOME\bin 取，用来裁剪内置 Java 运行时。
if defined JAVA_HOME (echo     当前值：%JAVA_HOME%) else (echo     当前值：（未设置）)
echo     装好 JDK 21 后设置一次（新开窗口生效）：
echo         setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-21.0.x-hotspot"
goto :fail

:fail
if not defined __rc set "__rc=%errorlevel%"
echo.
echo [x] 打包失败，停在：%__step%
echo     退出码：%__rc%
echo     往上翻，最后一条 [x] 开头的行就是真正原因（node 脚本的报错也以 [x] 开头）。
echo     常见三类：
echo       - dist 目录被占着：有资源管理器窗口或上一次的 start.bat 停在里面
echo       - JAVA_HOME 没设或不是 JDK 21
echo       - server\pom.xml 的 driver-bundle exclusion 被改回去了
echo.
call :hold
exit /b %__rc%

:need
where %~1 >nul 2>nul
if errorlevel 1 (
  echo [x] 没找到 %~1，需要先安装 %~2 并确保它在 PATH 里。
  exit /b 1
)
exit /b 0

:hold
echo 按任意键关闭窗口...
pause >nul
exit /b 0
