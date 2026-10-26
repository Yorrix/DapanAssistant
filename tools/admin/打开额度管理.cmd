@echo off
set "PYTHON=%~dp0..\..\backend\.venv\Scripts\pythonw.exe"
if exist "%PYTHON%" (
  start "" "%PYTHON%" "%~dp0quota_console.py"
) else (
  python "%~dp0quota_console.py"
)
