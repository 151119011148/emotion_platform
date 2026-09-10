@echo off
chcp 65001 >nul
setlocal

REM ============================================================
REM Emotion Platform 后端启动脚本
REM ------------------------------------------------------------
REM 用法:
REM   start.bat              编译并启动（默认端口 8080，前台运行）
REM   start.bat --port=9090  编译并启动，覆盖端口
REM   start.bat --skip-build 跳过编译，直接运行已有 jar
REM
REM 说明:
REM   前台运行，日志实时输出到当前窗口。
REM   看到 "Started EmotionApplication" 且无异常 = 启动成功。
REM   要停止: Ctrl+C（当前窗口），或单独运行 stop.bat
REM ============================================================

set APP_NAME=emotion-server
set APP_JAR=target\emotion-server-1.0.0.jar
set PROFILE=local
set PORT=8080
set SKIP_BUILD=0

REM --- 解析命令行参数 ---
:parse_args
if "%~1"=="" goto after_args
if /i "%~1"=="--skip-build" set SKIP_BUILD=1
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

REM --- 编译打包 ---
if "%SKIP_BUILD%"=="0" (
    echo ============================================================
    echo  [1/2] Maven 编译打包 (跳过测试)
    echo ============================================================
    call mvn clean package -DskipTests
    if errorlevel 1 (
        echo.
        echo [ERROR] Maven 编译失败，请检查上方错误信息。
        exit /b 1
    )
    echo.
    echo [OK] 编译完成。
) else (
    echo [1/2] 跳过编译 (--skip-build)
)

REM --- 检查 jar 是否存在 ---
if not exist "%APP_JAR%" (
    echo [ERROR] 找不到 %APP_JAR%
    echo         请去掉 --skip-build 先执行一次编译，或检查 Maven 构建输出。
    exit /b 1
)

REM --- 检查端口是否被占用 ---
netstat -ano 2>nul | findstr ":%PORT% " | findstr "LISTENING" >nul
if not errorlevel 1 (
    echo [WARN] 端口 %PORT% 已被占用，尝试终止...
    for /f "tokens=5" %%p in ('netstat -ano 2^>nul ^| findstr ":%PORT% " ^| findstr "LISTENING"') do (
        taskkill /F /PID %%p >nul 2>&1
        echo        已终止 PID=%%p
    )
    timeout /t 2 /nobreak >nul
)

REM --- 确保 logs 目录存在 ---
if not exist "logs" mkdir logs

REM --- 前台启动 Spring Boot（实时输出日志） ---
echo.
echo ============================================================
echo  [2/2] 启动 %APP_NAME%
echo    Profile : %PROFILE%
echo    Port    : %PORT%
echo    PID     : %PID%
echo    访问    : http://localhost:%PORT%
echo    停止    : Ctrl+C
echo ============================================================
echo.

java -jar "%APP_JAR%" ^
    --spring.profiles.active=%PROFILE% ^
    --server.port=%PORT% ^
    --logging.file.name=logs\emotion.log

echo.
echo [进程已退出，退出码=%ERRORLEVEL%]
endlocal
