param(
    [Parameter(Mandatory = $true)]
    [string]$ArchivePath,
    [int]$MinimumPackageCount = 2,
    [int]$MinimumApkCount = 1
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

$resolvedArchive = (Resolve-Path -LiteralPath $ArchivePath).Path
$zip = [System.IO.Compression.ZipFile]::OpenRead($resolvedArchive)
try {
    $metadataEntry = $zip.GetEntry("metadata/device-and-packages.json")
    $manifestEntry = $zip.GetEntry("metadata/manifest.json")
    if ($null -eq $metadataEntry -or $null -eq $manifestEntry) {
        throw "Migration archive metadata is missing."
    }

    $metadataReader = [System.IO.StreamReader]::new($metadataEntry.Open())
    try { $metadata = $metadataReader.ReadToEnd() | ConvertFrom-Json } finally { $metadataReader.Dispose() }
    $manifestReader = [System.IO.StreamReader]::new($manifestEntry.Open())
    try { $manifest = $manifestReader.ReadToEnd() | ConvertFrom-Json } finally { $manifestReader.Dispose() }

    $packageCount = @($metadata.packages).Count
    $apkCount = @($zip.Entries | Where-Object { $_.FullName -like "apks/*.apk" }).Count
    if ($packageCount -lt $MinimumPackageCount) {
        throw "Only $packageCount package(s) were captured; expected at least $MinimumPackageCount."
    }
    if ($apkCount -lt $MinimumApkCount) {
        throw "Only $apkCount APK(s) were captured; expected at least $MinimumApkCount."
    }

    $entriesByName = @{}
    foreach ($entry in $zip.Entries) { $entriesByName[$entry.FullName] = $entry }
    foreach ($expected in @($manifest.entries)) {
        $entry = $entriesByName[[string]$expected.path]
        if ($null -eq $entry) { throw "Missing ZIP entry: $($expected.path)" }
        if ($entry.Length -ne [long]$expected.size) {
            throw "Size mismatch for $($expected.path): $($entry.Length) != $($expected.size)"
        }
        $sha = [System.Security.Cryptography.SHA256]::Create()
        $stream = $entry.Open()
        try {
            $actualHash = [Convert]::ToHexString($sha.ComputeHash($stream)).ToLowerInvariant()
        } finally {
            $stream.Dispose()
            $sha.Dispose()
        }
        if ($actualHash -ne [string]$expected.sha256) {
            throw "SHA-256 mismatch for $($expected.path)"
        }
    }

    [pscustomobject]@{
        Archive = $resolvedArchive
        Packages = $packageCount
        Apks = $apkCount
        Entries = $zip.Entries.Count
        ManifestEntries = @($manifest.entries).Count
        UncompressedBytes = [long]$manifest.uncompressedBytes
        Status = "VALID"
    } | Format-List
} finally {
    $zip.Dispose()
}
