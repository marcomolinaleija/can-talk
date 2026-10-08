@echo off
setlocal
set "PWSH=%LOCALAPPDATA%\can-talk\powershell\pwsh.exe"
if exist "%PWSH%" goto build
set "PWSH=%USERPROFILE%\can-talk-tools\powershell\pwsh.exe"
if exist "%PWSH%" goto build
where pwsh.exe >nul 2>nul
if errorlevel 1 (
    echo PowerShell 7 is required. See COMPILACION.md. 1>&2
    exit /b 1
)
set "PWSH=pwsh.exe"
:build
"%PWSH%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0compilar.ps1" %*
exit /b %errorlevel%
