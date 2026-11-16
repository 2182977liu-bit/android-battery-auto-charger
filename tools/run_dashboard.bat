@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Starting AutoPhoneZ web dashboard...
echo.
python web_dashboard.py %1 %2
echo.
echo exited.
pause
