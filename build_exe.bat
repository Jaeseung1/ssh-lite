@echo off
rem Build SSH-Lite.exe (no Python needed to run the exe afterwards)
cd /d "%~dp0"
python -m pip install --upgrade paramiko pywebview pywinpty pyinstaller || goto :error
python -m PyInstaller --noconfirm --onefile --windowed --collect-all winpty --name SSH-Lite --icon icon.ico --add-data "ui;ui" ssh_lite.pyw || goto :error
echo.
echo Done: %~dp0dist\SSH-Lite.exe
pause
exit /b 0

:error
echo.
echo Build failed. Check the messages above.
pause
exit /b 1
