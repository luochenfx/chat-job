@echo off
rem 开发模式启动：直接从源码跑，改完 java 重启即可，不用打包。
rem
rem 三个必须显式处理的坑：
rem   1) 工作目录。spring-boot:run 的 workingDirectory 是 File 类型参数，Maven 会
rem      「按模块 basedir（server/）解析相对路径」——所以写 workingDirectory=. 得到的是
rem      server/，而不是仓库根。曾经因此把数据库写到 server/data，与 start.bat 的 /data
rem      分成两份，表现为「配置改了没生效」「投递记录不见了」。这里传绝对路径。
rem      数据落点本身已由 chat-job.home 锚定（见 ProjectHome），cwd 不再影响它；
rem      这条只为让进程的当前目录与 start.bat 保持一致。
rem   2) 端口。宿主/IDE 环境可能注入 SERVER__PORT（Spring 的宽松绑定会把它当成 server.port），
rem      覆盖 yaml 里的 8080，表现为「配了 8080 却监听到随机端口」。这里显式传参压住。
rem   3) 启动后请看日志首行的「运行时数据位置」，它打印数据库的绝对路径。发现两行不同的
rem      路径就是又跑偏了，不要靠猜。
setlocal
cd /d "%~dp0"

if not exist data mkdir data
if not exist data\logs mkdir data\logs

call mvn -B -f server\pom.xml spring-boot:run ^
  -Dspring-boot.run.workingDirectory=%~dp0. ^
  -Dspring-boot.run.arguments="--server.address=127.0.0.1 --server.port=8080"
endlocal
