@echo off
title Nongxin Agent Launcher
setlocal EnableExtensions

rem ---------------------------------------------------------------
rem  Pure ASCII on purpose: cmd.exe parses batch files byte-wise, so
rem  non-ASCII text (UTF-8 or GBK) can shift the parser and truncate
rem  commands. Keep every line here ASCII-only.
rem ---------------------------------------------------------------

set "ROOT=D:\aic"
set "SRV=%ROOT%\server"
set "JAR=%SRV%\target\nongxin-agent.jar"

echo ==================================================
echo    Nongxin Agent - one click start
echo    Frontend : http://localhost:3000
echo    Backend  : http://127.0.0.1:8080
echo ==================================================
echo.

if not defined JAVA_HOME if exist "D:\develop\jdk21" set "JAVA_HOME=D:\develop\jdk21"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"

call :portbusy 8080
if not errorlevel 1 goto :backend_running
if not exist "%JAR%" goto :need_package
call :start_backend
goto :frontend

:need_package
echo   [setup] building backend jar, may take 1-3 minutes...
pushd "%SRV%"
call mvn -q package -DskipTests
popd
if not exist "%JAR%" goto :fail_package
call :start_backend
goto :frontend

:start_backend
start "Nongxin Backend :8080" /D "%SRV%" cmd /k java -Dfile.encoding=UTF-8 -jar "%JAR%"
echo   [start] backend  :8080
exit /b 0

:backend_running
echo   [skip]  backend already running on :8080

:frontend
call :portbusy 3000
if not errorlevel 1 goto :frontend_running
if not exist "%ROOT%\node_modules" goto :need_install
call :start_frontend
goto :wait

:need_install
echo   [setup] installing frontend dependencies, may take 1-3 minutes...
pushd "%ROOT%"
call npm install
popd
if not exist "%ROOT%\node_modules" goto :fail_install
call :start_frontend
goto :wait

:start_frontend
start "Nongxin Frontend :3000" /D "%ROOT%" cmd /k npm run dev
echo   [start] frontend :3000
exit /b 0

:frontend_running
echo   [skip]  frontend already running on :3000

:wait
echo.
echo   waiting for services, the browser will open automatically...
call :sleep 13
start "" http://localhost:3000
echo.
echo   Done. Services keep running in their own windows.
echo   Close those two windows to stop the services.
call :sleep 6
exit /b 0

:portbusy
rem returns 0 when the port is already listening, 1 when it is free
netstat -ano | findstr /c:"LISTENING" | findstr /c:":%~1 " >nul 2>&1
exit /b %errorlevel%

:sleep
ping -n %~1 127.0.0.1 >nul 2>&1
exit /b 0

:fail_package
echo.
echo   [error] backend build failed. Check JAVA_HOME and Maven, then retry.
goto :hold

:fail_install
echo.
echo   [error] frontend install failed. Check Node.js 22.12+ then retry.
goto :hold

:hold
echo   Press any key to close this window.
pause >nul
exit /b 1
