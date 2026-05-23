@echo off
setlocal enabledelayedexpansion

set ADB=C:\Users\Lenovo\Downloads\platform-tools-latest-windows\platform-tools\adb.exe
set DEVICE=ZD222NBFT9
set REPLAY_DIR_PHONE=/sdcard/Android/data/com.amar.vault/files/replays
set REPLAY_DIR_LOCAL=%~dp0replays

echo ===================================================
echo Regression smoke test on device %DEVICE%
echo ===================================================
echo.
echo MANUAL TEST INSTRUCTIONS:
echo.
echo Submit each of these as goals in Amar Vault, in order:
echo.
echo   1. search hi on whatsapp
echo   2. search hello on telegram
echo   3. search puppy on twitter
echo   4. search pizza on instagram
echo   5. search inbox on gmail
echo   6. search dog on gemini
echo   7. search hello on chatgpt
echo.
echo Wait ~15 sec between each one. Press any key when ALL done.
pause

echo.
echo Pulling replays from phone...
if not exist "%REPLAY_DIR_LOCAL%" mkdir "%REPLAY_DIR_LOCAL%"
%ADB% -s %DEVICE% pull %REPLAY_DIR_PHONE% "%REPLAY_DIR_LOCAL%" 2>nul

echo.
echo Running offline regression analyzer...
call gradlew.bat regression

echo.
echo Done. Exit code: %errorlevel%