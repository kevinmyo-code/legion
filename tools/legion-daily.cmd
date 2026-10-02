@echo off
rem LEGION daily: log in to Bank of America, the script pulls transaction CSVs (current and closed periods) to Drive.
rem Shortcut: right-click this file > Show more options > Send to > Desktop (create shortcut).
rem
rem Settings live on this laptop only, never in the repo: create
rem   %USERPROFILE%\.legion\legion-daily.env.cmd
rem containing
rem   set LEGION_GOOGLE_CLIENT_ID=...
rem   set LEGION_GOOGLE_CLIENT_SECRET=...
rem   set LEGION_STATEMENTS_FOLDER=<folder id or URL>
rem Extra arguments pass through, e.g.  legion-daily.cmd --dry-run
setlocal
if exist "%USERPROFILE%\.legion\legion-daily.env.cmd" call "%USERPROFILE%\.legion\legion-daily.env.cmd"
python "%~dp0connect_session.py" bofa %*
echo.
if errorlevel 1 (echo LEGION daily did NOT finish. Read the message above.) else (echo LEGION daily finished.)
pause
endlocal
