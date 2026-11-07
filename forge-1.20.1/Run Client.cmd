@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Run-Temporal.ps1" -Task runClient
if errorlevel 1 pause
