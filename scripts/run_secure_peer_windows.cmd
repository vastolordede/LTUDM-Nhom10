@echo off
setlocal
set "P2P_ENV_FILE=%USERPROFILE%\.fileshare-p2p\.env"
if not exist "%P2P_ENV_FILE%" (
  echo Secure Peer profile missing. Ask your administrator to install the unique client profile.
  exit /b 1
)
set "PEER_START=%~dp0..\peer-client\build\install\peer-client\bin\peer-client.bat"
if not exist "%PEER_START%" (
  echo Build desktop distribution first ^(gradlew.bat :peer-client:installDist^).
  exit /b 1
)
call "%PEER_START%"
exit /b %errorlevel%
