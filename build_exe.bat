@echo off
rem Build SSH-Lite.exe (no Python needed to run the exe afterwards)
cd /d "%~dp0"
python -m pip install --upgrade paramiko pywebview pywinpty pyinstaller || goto :error
set EXTRA=
rem 내 서버 정보(defaults.local.json)가 있으면 exe 안에 넣음 -> 처음 켤 때부터 채워져 있음 (저장소에는 안 올림)
if exist defaults.local.json set EXTRA=--add-data "defaults.local.json;."
python -m PyInstaller --noconfirm --onefile --windowed --collect-all winpty --name SSH-Lite --icon icon.ico --add-data "ui;ui" %EXTRA% ssh_lite.pyw || goto :error
echo.
echo Done: %~dp0dist\SSH-Lite.exe
pause
exit /b 0

:error
echo.
echo Build failed. Check the messages above.
pause
exit /b 1
