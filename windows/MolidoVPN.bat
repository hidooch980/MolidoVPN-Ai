@echo off
title Molido VPN - Plasma Windows Launcher
cd /d "%~dp0"

:: بررسی دسترسی ادمین
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [Molido VPN] در حال درخواست دسترسی Administrator برای ایجاد کارت شبکه و پروکسی...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

:: اجرای سوپروایزر ویندوز
powershell -ExecutionPolicy Bypass -File "%~dp0start_vpn.ps1"
pause
