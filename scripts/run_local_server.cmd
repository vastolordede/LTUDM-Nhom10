@echo off
setlocal
cd /d "%~dp0.."
python scripts\demo_launch.py server %*
exit /b %errorlevel%
