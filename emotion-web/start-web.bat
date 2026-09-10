@echo off
chcp 65001 >nul
setlocal

REM ============================================================
REM Emotion Platform 前端启动脚本
REM ------------------------------------------------------------
REM 用法:
REM   start-web.bat              启动开发服务器（默认端口 5173）
REM   start-web.bat --port=5174  覆盖端口
REM   start-web.bat --install    先 npm install 再启动
REM
REM 说明:
REM   前台运行，日志实时输出。
REM   看到 "Local:" + 端口 = 启动成功。
REM   API 代理: /api -> http://localhost:8080（后端端口要匹配）
REM   停止: Ctrl+C
REM ============================================================

set PORT=5173
set DO_INSTALL=0

:parse_args
if "%~1"=="" goto after_args
if /i "%~1"=="--install" set DO_INSTALL=1
if /i "%~1"=="--port" (
    set PORT=%~2
    shift
)
for /f "tokens=2 delims==" %%a in ("%~1") do (
    if /i "%~1"=="--port=%%a" set PORT=%%a
)
shift
goto parse_args
:after_args

cd /d "%~dp0"

REM --- 检查 Node ---
where node >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 未检测到 Node.js，请先安装 Node.js 18+。
    exit /b 1
)

REM --- 可选: npm install ---
if "%DO_INSTALL%"=="1" (
    echo [1/2] npm install ...
    call npm install
    if errorlevel 1 (
        echo [ERROR] npm install 失败。
        exit /b 1
    )
) else (
    echo [1/2] 跳过 npm install（加 --install 可执行）
)

REM --- 检查依赖是否存在 ---
if not exist "node_modules" (
    echo [WARN] node_modules 不存在，先执行 npm install ...
    call npm install
    if errorlevel 1 (
        echo [ERROR] npm install 失败。
        exit /b 1
    )
)

REM --- 启动 Vite 开发服务器 ---
echo.
echo ============================================================
echo  [2/2] 启动前端 dev server
echo    Port : %PORT%
echo    API  : /api -> http://localhost:8080
echo    访问 : http://localhost:%PORT%
echo    停止 : Ctrl+C
echo ============================================================
echo.

call npm run dev -- --port %PORT% --host 0.0.0.0

echo.
echo [进程已退出，退出码=%ERRORLEVEL%]
endlocal
