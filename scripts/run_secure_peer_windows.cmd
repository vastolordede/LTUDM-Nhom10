@echo off
setlocal
set "BASE=%~dp0.."
set "BOOT=%BASE%\CLIENT_PUBLIC_BOOTSTRAP"
set "P2P_ENV_FILE=%USERPROFILE%\.fileshare-p2p\.env"
if not exist "%P2P_ENV_FILE%" (
  if not exist "%BOOT%\.env" (
    echo Trusted Tracker bootstrap missing; download official distribution.
    exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install_secure_peer_windows.ps1" -BootstrapDirectory "%BOOT%"
  if errorlevel 1 exit /b 1
)
set "PEER_START=%BASE%\peer-client\build\install\peer-client\bin\peer-client.bat"
if not exist "%PEER_START%" (
  echo Desktop distribution missing; run gradlew.bat :peer-client:installDist.
  exit /b 1
)
call "%PEER_START%"
exit /b %errorlevel%
