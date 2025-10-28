@echo off
setlocal
call gradlew.bat -q formatAll
if %errorlevel% neq 0 exit /b %errorlevel%
call gradlew.bat -q staticCheck
if %errorlevel% neq 0 exit /b %errorlevel%
