@echo off
rem Chrome Handoff: adds the "Fedora Pad Handoff" extension to Google Chrome, so the
rem page open in Chrome can be continued on the tablet and iPhone (and back).
rem Chrome only lets you add your own extension by hand: this opens the page and says what to click.
set "DIR=%~dp0..\host\chrome-handoff"
for %%I in ("%DIR%") do set "DIR=%%~fI"
echo.
echo 1. In Chrome, turn on "Developer mode" (top right of the page that opens).
echo 2. Click "Load unpacked" and choose this folder:
echo       %DIR%
echo 3. "Fedora Pad Handoff" appears in the list. Done: open any page in Chrome,
echo    and the tablet's Dock shows the globe (the iPhone shows "Continue: ...").
echo.
echo|set /p="%DIR%"| clip
echo (The folder path is copied: paste it with Ctrl+V in the folder box.)
start "" chrome chrome://extensions 2>nul || echo Chrome wasn't found: open chrome://extensions in Chrome yourself.
pause
