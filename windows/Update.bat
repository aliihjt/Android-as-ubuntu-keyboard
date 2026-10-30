@echo off
rem Fedora Pad for Windows: get the newest version and restart it.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0update.ps1"
pause
