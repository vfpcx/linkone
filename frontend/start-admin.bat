@echo off
echo ===========================================
echo  仓储云 admin 前端开发服务器
echo  端口: 5173
echo  代理: /api -^> http://localhost:8080
echo ===========================================
echo.
echo 启动后请用浏览器打开 http://localhost:5173/
echo 按 Ctrl+C 停止
echo.
cd /d "%~dp0"
pnpm dev:admin
pause
