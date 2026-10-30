@echo off
rem Fedora Pad for Windows: get the newest version and restart it.
git -C "%~dp0.." pull --ff-only
powershell -NoProfile -Command "$p = Join-Path $env:LOCALAPPDATA FedoraPad\host.pid; if (Test-Path $p) { Stop-Process -Id (Get-Content $p) -ErrorAction SilentlyContinue }"
echo Updated. Fedora Pad restarts by itself in a few seconds.
pause
