@echo off
title Nongxin Agent - Mobile Mode
setlocal EnableExtensions EnableDelayedExpansion

rem ---------------------------------------------------------------
rem  Pure ASCII on purpose: cmd.exe parses batch files byte-wise, so
rem  non-ASCII text (UTF-8 or GBK) can shift the parser and truncate
rem  commands. Keep every line here ASCII-only.
rem
rem  Mobile mode = same services as the desktop launcher, but the dev
rem  server binds to 0.0.0.0 so a phone on the same Wi-Fi can open it.
rem  The backend stays on 127.0.0.1: the dev server proxies /api from
rem  the PC, so the phone only ever talks to one address.
rem ---------------------------------------------------------------

set "ROOT=D:\aic"
set "SRV=%ROOT%\server"
set "JAR=%SRV%\target\nongxin-agent.jar"
set "PORT=3000"

echo ==================================================
echo    Nongxin Agent - MOBILE MODE (same Wi-Fi)
echo    PC      : http://localhost:%PORT%
echo    Phone   : http://YOUR-PC-IP:%PORT%   (see below)
echo ==================================================
echo.

if not defined JAVA_HOME if exist "D:\develop\jdk21" set "JAVA_HOME=D:\develop\jdk21"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"

rem ---- backend (loopback only, the dev server proxies to it) ----
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
call :portbusy %PORT%
if not errorlevel 1 goto :frontend_running
if not exist "%ROOT%\node_modules" goto :need_install
call :start_frontend
goto :show
:need_install
echo   [setup] installing frontend dependencies, may take 1-3 minutes...
pushd "%ROOT%"
call npm install
popd
if not exist "%ROOT%\node_modules" goto :fail_install
call :start_frontend
goto :show

:start_frontend
rem --host makes Vite listen on 0.0.0.0 (LAN reachable); --port keeps it fixed
start "Nongxin Frontend :%PORT% (LAN)" /D "%ROOT%" cmd /k npm run dev -- --host 0.0.0.0 --port %PORT%
echo   [start] frontend :%PORT% (listening on all interfaces)
exit /b 0

:frontend_running
echo   [skip]  frontend already running on :%PORT%
echo   [note]  if it was started without --host, the phone cannot reach it.
echo           Close that window and run this script again.

:show
echo.
echo   Looking up this PC's LAN address...
set "LAN="
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /r /c:"IPv4"') do (
  if not defined LAN (
    for /f "tokens=1" %%b in ("%%a") do set "IP=%%b"
    set "IP=!IP: =!"
    echo !IP! | findstr /b /c:"169.254." >nul || set "LAN=!IP!"
  )
)
if not defined LAN goto :no_lan

echo.
echo ==================================================
echo    Open this on your phone (same Wi-Fi):
echo.
echo        http://%LAN%:%PORT%/
echo.
echo    In the app: menu - "Phone access" shows the
echo    same address as a QR code you can scan.
echo ==================================================
echo.
echo   First run: Windows Firewall will ask about Node.js -
echo   allow it on PRIVATE networks, otherwise the phone
echo   cannot connect.
echo.
echo   Notes:
echo     - Camera / photo picker works over plain HTTP.
echo     - Microphone and GPS need HTTPS (browser rule).
echo     - Anyone on this Wi-Fi can open the address, so
echo       use it at home or on your own hotspot only.
echo.
call :sleep 2
start "" http://localhost:%PORT%
echo   Done. Close the two service windows to stop everything.
call :sleep 6
exit /b 0

:no_lan
echo.
echo   [warn] Could not detect a LAN IPv4 address.
echo          Run "ipconfig" and use the IPv4 of your Wi-Fi adapter,
echo          then open http://THAT-IP:%PORT%/ on the phone.
call :sleep 8
exit /b 1

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
