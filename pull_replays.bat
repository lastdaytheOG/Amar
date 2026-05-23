@echo off
set ADB=C:\Users\Lenovo\Downloads\platform-tools-latest-windows\platform-tools\adb.exe
set DEVICE=ZD222NBFT9
set REPLAY_DIR_PHONE=/sdcard/Android/data/com.amar.vault/files/replays
set REPLAY_DIR_LOCAL=replays

mkdir %REPLAY_DIR_LOCAL% 2>nul

echo Pulling replays from phone...
%ADB% -s %DEVICE% pull %REPLAY_DIR_PHONE% %REPLAY_DIR_LOCAL%

echo.
echo Files in %REPLAY_DIR_LOCAL%:
dir /b %REPLAY_DIR_LOCAL%