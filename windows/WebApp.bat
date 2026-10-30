@echo off
rem Fedora Pad on iPhone / iPad / phones: turn on (or "WebApp.bat off").
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0WebApp.ps1" %*
pause
