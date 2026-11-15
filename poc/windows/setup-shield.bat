@echo off
REM Sets up the proof of concept on the SHIELD from a Windows laptop.
REM Usage (from the repo's poc folder):  windows\setup-shield.bat 192.168.1.50
REM Needs: adb on PATH, Termux APK downloaded to this folder as termux.apk

setlocal
if "%~1"=="" (
  echo Usage: windows\setup-shield.bat SHIELD_IP
  exit /b 1
)
set SHIELD=%~1:5555

echo == Connecting to %SHIELD% (accept the prompt on the TV the first time)
adb connect %SHIELD% || exit /b 1
adb -s %SHIELD% shell getprop ro.build.version.release
adb -s %SHIELD% shell getprop ro.product.cpu.abilist

if exist termux.apk (
  echo == Installing Termux
  adb -s %SHIELD% install -r termux.apk || exit /b 1
) else (
  echo termux.apk not found here, assuming Termux is already installed
)

echo == Letting Termux use /sdcard/Download
adb -s %SHIELD% shell pm grant com.termux android.permission.READ_EXTERNAL_STORAGE
adb -s %SHIELD% shell pm grant com.termux android.permission.WRITE_EXTERNAL_STORAGE

echo == Copying the proof of concept
adb -s %SHIELD% shell mkdir -p /sdcard/Download/eufy-poc/out
adb -s %SHIELD% push eufy-poc.js package.json bootstrap.sh /sdcard/Download/eufy-poc/

if not exist "%USERPROFILE%\.ssh\id_ed25519.pub" (
  echo == Creating an SSH key for this laptop
  ssh-keygen -t ed25519 -N "" -f "%USERPROFILE%\.ssh\id_ed25519"
)
adb -s %SHIELD% push "%USERPROFILE%\.ssh\id_ed25519.pub" /sdcard/Download/eufy-poc/id_ed25519.pub

echo == Opening Termux on the TV
adb -s %SHIELD% shell am start -n com.termux/.app.TermuxActivity
echo Waiting 30 seconds for Termux to finish its first-time setup...
timeout /t 30 /nobreak >nul

echo == Typing the bootstrap command into Termux
adb -s %SHIELD% shell input text "bash%%s/sdcard/Download/eufy-poc/bootstrap.sh"
adb -s %SHIELD% shell input keyevent 66

echo.
echo Watch the TV. When it says "Done", run:
echo    ssh -p 8022 %~1
echo    cd eufy-poc ^&^& node eufy-poc.js
endlocal
