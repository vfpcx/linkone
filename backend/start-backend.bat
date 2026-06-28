@echo off
echo ===========================================
echo  仓储云 后端服务器
echo  端口: 8080
echo  数据库: MySQL cangchu_dev
echo  缓存: Redis (Memurai) localhost:6379
echo ===========================================
echo.
echo 启动后请访问 http://localhost:8080/actuator/health
echo 按 Ctrl+C 停止
echo.
cd /d "%~dp0"
set JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8
set PATH=%JAVA_HOME%\bin;%PATH%
mvn -DskipTests spring-boot:run -Dspring-boot.run.profiles=dev,local
pause
