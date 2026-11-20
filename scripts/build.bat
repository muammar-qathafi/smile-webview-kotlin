@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "PROJECT_DIR=%SCRIPT_DIR%.."

if not defined JAVA_HOME (
  for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do set "JAVA_HOME=%%D"
)

if not defined JAVA_HOME (
  echo ERROR: JAVA_HOME tidak ditemukan. Install JDK 17 dulu.
  exit /b 1
)

cd /d "%PROJECT_DIR%"
call gradlew.bat %*
