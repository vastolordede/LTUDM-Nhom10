@echo off
setlocal
cd /d "%~dp0.."
python scripts\demo_launch.py peer %*
exit /b %errorlevel%
