@echo off
setlocal EnableExtensions
cd /d "%~dp0.."
if not exist "gradlew.bat" (
  echo ERROR: Run from a valid FileShare-P2P repository.
  exit /b 1
)
echo Running clean tests...
call gradlew.bat clean test
if errorlevel 1 exit /b 1
echo Building all modules...
call gradlew.bat build
if errorlevel 1 exit /b 1
echo Verifying whitespace and patch safety...
git diff --check
if errorlevel 1 exit /b 1
echo PASS: local automated build and tests. Manual acceptance is still required.
exit /b 0
