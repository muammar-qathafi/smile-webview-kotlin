# Generate / validasi project Xcode untuk iosApp.
# Di Windows: hanya validasi struktur + cetak instruksi Mac.
# Di Mac (PowerShell Core / bash twin): jalankan xcodegen generate.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$ios = Join-Path $root "iosApp"
$yml = Join-Path $ios "project.yml"

Write-Host "Smile Multifab — iOS Xcode project helper"
Write-Host "iosApp: $ios"

if (-not (Test-Path $yml)) {
    throw "project.yml tidak ditemukan di $ios"
}

$required = @(
    "SmileMultifab\SmileMultifabApp.swift",
    "SmileMultifab\SmileWebView.swift",
    "SmileMultifab\SmileJsBridge.swift",
    "SmileMultifab\AppConfig.swift",
    "SmileMultifab\Info.plist",
    "project.yml"
)
foreach ($rel in $required) {
    $p = Join-Path $ios $rel
    if (-not (Test-Path $p)) { throw "Missing: $rel" }
    Write-Host "  OK $rel"
}

$isMac = $IsMacOS -or ((Test-Path Env:OS) -eq $false -and (uname 2>$null) -match "Darwin")
$xcodegen = Get-Command xcodegen -ErrorAction SilentlyContinue

if ($xcodegen) {
    Write-Host "Menjalankan: xcodegen generate"
    Push-Location $ios
    try {
        & xcodegen generate
        Write-Host "Selesai. Buka: iosApp/SmileMultifab.xcodeproj"
    } finally {
        Pop-Location
    }
} else {
    Write-Host ""
    Write-Host "XcodeGen belum tersedia di PATH (normal di Windows)."
    Write-Host "Struktur sumber sudah valid. Di Mac jalankan:"
    Write-Host "  brew install xcodegen"
    Write-Host "  cd iosApp"
    Write-Host "  xcodegen generate"
    Write-Host "  open SmileMultifab.xcodeproj"
    Write-Host ""
    Write-Host "Atau di Cursor (Mac): perintah 'Smile: Generate iOS Xcode Project'"
}
