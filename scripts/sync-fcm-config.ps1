# Sync FCM config untuk Smile 2.1
# Prioritas:
# 1. google-services.json dari smile-1.1 (jika ada)
# 2. Export otomatis via BE artisan (butuh service-account.json di mfg-datacore-v2-be)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root "composeApp\google-services.json"
$legacy = Join-Path (Split-Path -Parent $root) "smile-1.1\android\app\google-services.json"
$beRoot = Join-Path (Split-Path -Parent (Split-Path -Parent $root)) "mfg-datacore-v2-be"

Write-Host "Target: $target"

if (Test-Path $legacy) {
    Copy-Item $legacy $target -Force
    Write-Host "OK: disalin dari smile-1.1 -> composeApp/google-services.json"
    exit 0
}

$sa = Join-Path $beRoot "storage\app\fcm\service-account.json"
if (-not (Test-Path $sa)) {
    Write-Host "google-services.json tidak ditemukan di smile-1.1."
    Write-Host "Service account BE juga belum ada di:"
    Write-Host "  $sa"
    Write-Host ""
    Write-Host "Langkah manual:"
    Write-Host "  1. Salin service-account.json dari server production ERP ke path di atas"
    Write-Host "  2. Set mfg-datacore-v2-be/.env:"
    Write-Host "       FCM_SERVICE_ACCOUNT_PATH=storage/app/fcm/service-account.json"
    Write-Host "       FCM_PROJECT_ID=smile-multifab-v2"
    Write-Host "  3. Jalankan ulang script ini"
    exit 1
}

Push-Location $beRoot
try {
    php artisan smile:export-google-services --output="../mfg-datacore-webview/smile-2.1/composeApp/google-services.json"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "OK: google-services.json diekspor dari Firebase via BE"
} finally {
    Pop-Location
}
