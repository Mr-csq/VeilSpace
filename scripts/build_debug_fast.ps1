# Android debug build fast path
param(
    [switch]$Install,
    [switch]$ShowProcesses
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if ($ShowProcesses) {
    Write-Host 'Possible Gradle/Java processes:'
    Get-Process | Where-Object { $_.ProcessName -like '*gradle*' -or $_.ProcessName -like '*java*' } |
        Select-Object ProcessName,Id,CPU,StartTime
}

Write-Host 'Building debug APK with the stable path used by Codex...'
& gradle --no-daemon --max-workers=1 --console=plain clean assembleDebug

if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed with exit code $LASTEXITCODE"
}

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) {
    throw "Build finished but APK was not found at $apk"
}

Write-Host "APK ready: $apk"

if ($Install) {
    Write-Host 'Installing debug APK...'
    & adb install -r app\build\outputs\apk\debug\app-debug.apk
    if ($LASTEXITCODE -ne 0) {
        throw "adb install failed with exit code $LASTEXITCODE"
    }
}
