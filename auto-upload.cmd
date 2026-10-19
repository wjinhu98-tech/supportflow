@echo off
REM Double-click entry point for auto-upload.ps1
REM Optional arguments are forwarded, e.g. a custom commit message:
REM   auto-upload.cmd -Message "feat: my change"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0auto-upload.ps1" %*
if errorlevel 1 pause
