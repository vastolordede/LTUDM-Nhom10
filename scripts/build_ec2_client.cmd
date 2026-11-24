@echo off
setlocal
cd /d "%~dp0.."
python scripts\demo_launch.py package %*
exit /b %errorlevel%
