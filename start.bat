@echo off
chcp 949 > nul
setlocal

cd /d "%~dp0"

echo ============================================
echo   LLM Manager - Startup
echo ============================================
echo.
echo [사용법] start.bat [옵션...]
echo   --api.server.enabled=true     내장 API 서버 활성화
echo   --api.server.port=8185        API 서버 포트 (기본: 8185)
echo   --runtime.python=python3      Python 실행 명령어
echo   --runtime.java=java           Java 실행 명령어
echo   --install.base=D:\llm-svcs   서비스 기본 설치 경로
echo.
echo [설정 우선순위] CLI 인수 > settings.json > application.yml
echo.

rem -- Java check --
where java > nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Java not found. Please install Java 17+ and add to PATH.
    pause
    exit /b 1
)

for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VER=%%v
set JAVA_VER=%JAVA_VER:"=%
for /f "delims=." %%m in ("%JAVA_VER%") do set JAVA_MAJOR=%%m

if %JAVA_MAJOR% lss 17 (
    echo [ERROR] Java %JAVA_VER% detected. Java 17+ is required.
    pause
    exit /b 1
)
echo [OK] Java %JAVA_VER%

rem -- Run from distribution if available, otherwise build first --
set DIST_BAT=%~dp0build\install\llm-manage\bin\llm-manage.bat

if exist "%DIST_BAT%" (
    echo [OK] Distribution found. Starting...
    echo.
    call "%DIST_BAT%" %*
    goto end
)

rem -- Distribution not found: build with Gradle --
echo [INFO] Distribution not found. Building now...
echo.

where gradle > nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] gradle command not found. Please install Gradle and add to PATH.
    pause
    exit /b 1
)

gradle installDist
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Build failed. Check the error messages above.
    pause
    exit /b 1
)

echo.
echo [OK] Build successful. Starting app...
echo.
call "%DIST_BAT%" %*

:end
endlocal