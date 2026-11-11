@echo off
rem Makes the tablet just Fedora Pad: removes the maker's extra apps and makes
rem Fedora Pad the home screen. "TabletClean.bat undo" puts them back.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tablet-clean.ps1" %*
pause
