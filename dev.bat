@echo off
rem 开发模式启动：直接从源码跑，改完 java 重启即可，不用打包。
rem
rem 两个必须显式处理的坑：
rem   1) 工作目录。application.yaml 的 ./data 与 ./build/patchright-driver 都相对工作目录解析。
rem      在 server/ 下跑 spring-boot:run 会让数据库落到 server/data，与 start.bat 的 ./data
rem      分成两份，表现为"配置改了没生效"。这里用 -Dspring-boot.run.workingDirectory=. 钉回仓库根。
rem   2) 端口。宿主/IDE 环境可能注入 SERVER__PORT（Spring 的宽松绑定会把它当成 server.port），
rem      覆盖 yaml 里的 8080，表现为"配了 8080 却监听到随机端口"。这里显式传参压住。
setlocal
cd /d "%~dp0"

if not exist data mkdir data
if not exist data\logs mkdir data\logs

call mvn -B -f server\pom.xml spring-boot:run ^
  -Dspring-boot.run.workingDirectory=. ^
  -Dspring-boot.run.arguments="--server.address=127.0.0.1 --server.port=8080"
endlocal
