# =========================================================================
# Molido VPN • Windows Native Core Supervisor & Proxy Controller
# =========================================================================

param (
    [switch]$Disconnect
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$singboxExe = Join-Path $scriptDir "bin\sing-box.exe"
$configJson = Join-Path $scriptDir "config.json"
$clientHtml = Join-Path $scriptDir "..\client\index.html"

# تابع تنظیم پروکسی ویندوز
function Set-WindowsProxy([bool]$enable, [string]$server = "127.0.0.1:2080") {
    $regKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings"
    if ($enable) {
        Set-ItemProperty -Path $regKey -Name ProxyEnable -Value 1
        Set-ItemProperty -Path $regKey -Name ProxyServer -Value $server
        Set-ItemProperty -Path $regKey -Name ProxyOverride -Value "<local>;*.ir;*.shaparak.ir;192.168.*;10.*"
        Write-Host "[Molido VPN] پروکسی سیستم ویندوز فعال شد: $server" -ForegroundColor Green
    } else {
        Set-ItemProperty -Path $regKey -Name ProxyEnable -Value 0
        Write-Host "[Molido VPN] پروکسی سیستم ویندوز غیرفعال شد." -ForegroundColor Yellow
    }
}

# تابع بررسی آپدیت خودکار
function Check-AutoUpdate {
    try {
        Write-Host "[Molido VPN] در حال بررسی نسخه جدید در گیت‌هاب..." -ForegroundColor Cyan
        $url = "https://api.github.com/repos/hidooch980/MolidoVPN-Ai/releases/latest"
        $release = Invoke-RestMethod -Uri $url -Method Get -TimeoutSec 3 -ErrorAction SilentlyContinue
        if ($release -and $release.tag_name) {
            Write-Host "[Molido VPN] آخرین نسخه منتشرشده در گیت‌هاب: $($release.tag_name)" -ForegroundColor Green
        }
    } catch {
        # در صورت نبود اینترنت بدون خطا ادامه می‌دهد
    }
}

if ($Disconnect) {
    Get-Process -Name "sing-box" -ErrorAction SilentlyContinue | Stop-Process -Force
    Set-WindowsProxy $false
    Write-Host "[Molido VPN] تونل با موفقیت قطع شد." -ForegroundColor Yellow
    exit 0
}

# بررسی و دانلود خودکار هسته در صورت عدم وجود
if (-not (Test-Path $singboxExe)) {
    Write-Host "[Molido VPN] هسته اجرایی Sing-box یافت نشد. در حال دانلود خودکار نسخه رسمی..." -ForegroundColor Cyan
    $binDir = Join-Path $scriptDir "bin"
    New-Item -ItemType Directory -Path $binDir -Force | Out-Null
    $zipPath = Join-Path $binDir "singbox.zip"
    $url = "https://github.com/SagerNet/sing-box/releases/download/v1.14.4/sing-box-1.14.4-windows-amd64.zip"
    Invoke-WebRequest -Uri $url -OutFile $zipPath
    Expand-Archive -Path $zipPath -DestinationPath (Join-Path $binDir "temp") -Force
    Move-Item (Join-Path $binDir "temp\sing-box-1.14.4-windows-amd64\sing-box.exe") $singboxExe -Force
    Move-Item (Join-Path $binDir "temp\sing-box-1.14.4-windows-amd64\libcronet.dll") (Join-Path $binDir "libcronet.dll") -Force -ErrorAction SilentlyContinue
    Remove-Item $zipPath, (Join-Path $binDir "temp") -Recurse -Force
    Write-Host "[Molido VPN] هسته Sing-box با موفقیت راه‌اندازی شد." -ForegroundColor Green
}

# اطمینان از وجود فایل کانفیگ
if (-not (Test-Path $configJson)) {
    $defaultConfig = Join-Path $scriptDir "..\core\multiengine\singbox_universal.json"
    if (Test-Path $defaultConfig) {
        Copy-Item $defaultConfig $configJson
    }
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "       Molido VPN • Holographic Plasma Windows Client     " -ForegroundColor White
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "در حال راه‌اندازی هسته شبکه Sing-box..." -ForegroundColor Gray

# بستن پروسه‌های قبلی
Get-Process -Name "sing-box" -ErrorAction SilentlyContinue | Stop-Process -Force

# اجرای هسته در پس‌زمینه
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $singboxExe
$psi.Arguments = "run -c `"$configJson`""
$psi.WorkingDirectory = $scriptDir
$psi.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
$psi.CreateNoWindow = $true
$psi.UseShellExecute = $false
$process = [System.Diagnostics.Process]::Start($psi)

# فعال‌سازی پروکسی سیستم
Start-Sleep -Seconds 1
Set-WindowsProxy $true

# باز کردن رابط گرافیکی پلاسما در پنجره اپلیکیشن
if (Test-Path $clientHtml) {
    Start-Process -FilePath "msedge.exe" -ArgumentList "--app=`"$clientHtml`" --window-size=440,820" -ErrorAction SilentlyContinue
}

Write-Host "[OK] تونل ایمن برقرار شد! ترافیک ویندوز از هسته محافظ عبور می‌کند." -ForegroundColor Green
Write-Host "برای قطع اتصال، این پنجره را ببندید یا کلید Ctrl+C را فشار دهید." -ForegroundColor Gray

try {
    while (-not $process.HasExited) {
        Start-Sleep -Seconds 1
    }
} finally {
    Set-WindowsProxy $false
    Get-Process -Name "sing-box" -ErrorAction SilentlyContinue | Stop-Process -Force
}
