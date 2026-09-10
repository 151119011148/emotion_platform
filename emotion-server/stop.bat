@echo off
chcp 65001 >nul
setlocal

REM ============================================================
REM Emotion Platform 后端停止脚本
REM ------------------------------------------------------------
REM 用法:
REM   stop.bat              停止默认端口 8080 的服务
REM   stop.bat --port=9090  停止指定端口的服务
REM ============================================================

set PORT=8080

REM --- 解析参数 ---
for /f "tokens=2 delims==" %%a in ("%~1") do (
    if /i "%~1"=="--port=%%a" set PORT=%%a
)
if /i "%~1"=="--port" set PORT=%~2

cd /d "%~dp0"

echo 正在查找端口 %PORT% 对应的进程...

set FOUND=0
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%PORT% " ^| findstr "LISTENING"') do (
    set FOUND=1
    echo 找到 PID=%%p，正在终止...
    taskkill /F /PID %%p
)

if "%FOUND%"=="0" (
    echo [INFO] 端口 %PORT% 没有正在监听的进程，服务可能已停止。
) else (
    echo [OK] 已停止端口 %PORT% 的服务。
)

if exist ".emotion.pid" del ".emotion.pid"
endlocal
