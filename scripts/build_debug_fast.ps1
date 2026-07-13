# Android debug build fast path
param(
    [switch]$Install,
    [switch]$ShowProcesses,
    [switch]$CheckOnly,
    [switch]$Clean,
    [switch]$CleanNativeLocks,
    [switch]$ResetNativeCache,
    [switch]$BuildAfterCleanup
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Get-BuildProcesses {
    Get-Process | Where-Object { $_.ProcessName -like '*gradle*' -or $_.ProcessName -like '*java*' }
}

function Get-GradleNativeLocks {
    $nativeRoot = Join-Path $env:USERPROFILE '.gradle\native'
    if (-not (Test-Path -LiteralPath $nativeRoot)) { return @() }
    Get-ChildItem -Path $nativeRoot -Recurse -Force -Filter '*.lock' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -like (Join-Path $nativeRoot '*') }
}

function Get-GradleNativeCacheDirs {
    $nativeRoot = Join-Path $env:USERPROFILE '.gradle\native'
    if (-not (Test-Path -LiteralPath $nativeRoot)) { return @() }
    Get-ChildItem -Path $nativeRoot -Directory -Force -ErrorAction SilentlyContinue |
        Where-Object {
            $windowsDir = Join-Path $_.FullName 'windows-amd64'
            (Test-Path -LiteralPath $windowsDir) -and
                @(Get-ChildItem -Path $windowsDir -Force -Filter 'native-platform*.dll' -ErrorAction SilentlyContinue).Count -gt 0
        }
}

function Show-BuildProcesses {
    $processes = @(Get-BuildProcesses)
    if ($processes.Count -eq 0) {
        Write-Host 'No Gradle/Java processes found.'
        return
    }
    Write-Host 'Possible Gradle/Java processes:'
    $processes | Select-Object ProcessName,Id,CPU,StartTime | Format-Table -AutoSize
}

function Show-NativeLocks($locks) {
    if ($locks.Count -eq 0) {
        Write-Host 'No Gradle native lock files found.'
        return
    }
    Write-Host 'Gradle native lock files:'
    $locks | Select-Object FullName,Length,LastWriteTime | Format-Table -AutoSize
}

if ($ShowProcesses -or $CheckOnly) {
    Show-BuildProcesses
}

$nativeLocks = @(Get-GradleNativeLocks)
if ($ShowProcesses -or $CheckOnly) {
    Show-NativeLocks $nativeLocks
}

if ($CleanNativeLocks -or $ResetNativeCache) {
    $processes = @(Get-BuildProcesses)
    if ($processes.Count -gt 0) {
        Show-BuildProcesses
        throw 'Refusing to change Gradle native cache while Java/Gradle processes are running.'
    }
}

if ($CleanNativeLocks) {
    foreach ($lock in $nativeLocks) {
        $resolved = (Resolve-Path -LiteralPath $lock.FullName -ErrorAction Stop).Path
        $nativeRoot = (Resolve-Path -LiteralPath (Join-Path $env:USERPROFILE '.gradle\native') -ErrorAction Stop).Path
        if (-not $resolved.StartsWith($nativeRoot)) { throw "Unexpected Gradle native lock path: $resolved" }
        Remove-Item -LiteralPath $resolved -Force
        Write-Host "Removed $resolved"
    }
    $nativeLocks = @(Get-GradleNativeLocks)
}

if ($ResetNativeCache) {
    $nativeRoot = (Resolve-Path -LiteralPath (Join-Path $env:USERPROFILE '.gradle\native') -ErrorAction Stop).Path
    foreach ($dir in @(Get-GradleNativeCacheDirs)) {
        $windowsDir = (Resolve-Path -LiteralPath (Join-Path $dir.FullName 'windows-amd64') -ErrorAction Stop).Path
        if (-not $windowsDir.StartsWith($nativeRoot)) { throw "Unexpected Gradle native cache path: $windowsDir" }
        Get-ChildItem -Path $windowsDir -Force -Filter 'native-platform*.dll*' -ErrorAction SilentlyContinue |
            ForEach-Object {
                Remove-Item -LiteralPath $_.FullName -Force
                Write-Host "Removed $($_.FullName)"
            }
    }
    $nativeLocks = @(Get-GradleNativeLocks)
}

if (($CleanNativeLocks -or $ResetNativeCache) -and -not $BuildAfterCleanup) {
    return
}

if ($CheckOnly) {
    return
}

if ($nativeLocks.Count -gt 0) {
    Show-NativeLocks $nativeLocks
    throw 'Gradle native lock files detected. Run .\scripts\build_debug_fast.ps1 -ShowProcesses -CheckOnly, then .\scripts\build_debug_fast.ps1 -CleanNativeLocks after confirming no Java/Gradle processes are running.'
}

$gradleArgs = @('--no-daemon', '--max-workers=1', '--console=plain')
if ($Clean) {
    $gradleArgs += 'clean'
}
$gradleArgs += 'assembleDebug'

Write-Host ('Building debug APK: gradle ' + ($gradleArgs -join ' '))
& gradle @gradleArgs

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
