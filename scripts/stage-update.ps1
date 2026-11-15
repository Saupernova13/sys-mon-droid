<#
.SYNOPSIS
  Stage a built sys-mon-droid debug APK where sys-mon serves app updates from.

.DESCRIPTION
  Optionally builds :app:assembleDebug, reads versionCode/versionName from
  app/build/outputs/apk/debug/output-metadata.json, hashes the APK and copies it
  as sys-mon-droid-<versionName>-<versionCode>.apk plus a matching .json sidecar
  into -Dest. Dropping the files in the folder sys-mon watches is what publishes
  the update (the sys-mon prod folder is C:\prod\sys-mon\droid-updates by default).

.PARAMETER Dest
  Destination folder. Defaults to $env:SYS_MON_DROID_UPDATES_DIR. Created if missing.
.PARAMETER Notes
  Release notes stored in the sidecar (may be empty).
.PARAMETER SkipBuild
  Reuse the existing APK instead of running Gradle.
.PARAMETER DryRun
  Print what would happen; write nothing.
.PARAMETER Force
  Stage even if versionCode is not higher than the highest already in -Dest.
#>
param(
    [string]$Dest = $env:SYS_MON_DROID_UPDATES_DIR,
    [string]$Notes = '',
    [switch]$SkipBuild,
    [switch]$DryRun,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Dest)) {
    throw 'No destination: pass -Dest or set SYS_MON_DROID_UPDATES_DIR.'
}

$repo = Split-Path -Parent $PSScriptRoot
$metaPath = Join-Path $repo 'app\build\outputs\apk\debug\output-metadata.json'

function Find-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { return $env:JAVA_HOME }
    $roots = @()
    foreach ($pf in @($env:ProgramFiles, ${env:ProgramFiles(x86)})) {
        if ($pf) { $roots += (Join-Path $pf 'Eclipse Adoptium') }
    }
    foreach ($r in $roots) {
        if (Test-Path $r) {
            $jdk = Get-ChildItem -Path $r -Directory -Filter 'jdk-17*' | Sort-Object Name -Descending | Select-Object -First 1
            if ($jdk) { return $jdk.FullName }
        }
    }
    return $null
}

if (-not $SkipBuild) {
    $jh = Find-JavaHome
    if (-not $jh) { throw 'JDK 17 not found: set JAVA_HOME or install Eclipse Adoptium JDK 17.' }
    if ($DryRun) {
        Write-Host "[dry-run] would build with JAVA_HOME=$jh : gradlew :app:assembleDebug"
    } else {
        $env:JAVA_HOME = $jh
        Push-Location $repo
        try {
            & (Join-Path $repo 'gradlew.bat') ':app:assembleDebug'
            if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit $LASTEXITCODE)." }
        } finally {
            Pop-Location
        }
    }
}

if (-not (Test-Path $metaPath)) { throw "Missing $metaPath - build first (omit -SkipBuild)." }
$meta = Get-Content -Raw -Path $metaPath | ConvertFrom-Json
$elem = $meta.elements | Select-Object -First 1
$versionCode = [int]$elem.versionCode
$versionName = [string]$elem.versionName
$apk = Join-Path (Split-Path -Parent $metaPath) ([string]$elem.outputFile)
if (-not (Test-Path $apk)) { throw "APK not found: $apk" }

$sha = (Get-FileHash -Algorithm SHA256 -Path $apk).Hash.ToLowerInvariant()
$base = "sys-mon-droid-$versionName-$versionCode"
$apkOut = Join-Path $Dest "$base.apk"
$jsonOut = Join-Path $Dest "$base.json"

# Highest versionCode already staged (from the file names).
$highest = 0
if (Test-Path $Dest) {
    foreach ($f in Get-ChildItem -Path $Dest -Filter 'sys-mon-droid-*-*.apk') {
        if ($f.BaseName -match '-(\d+)$') {
            $n = [int]$Matches[1]
            if ($n -gt $highest) { $highest = $n }
        }
    }
}
if ($versionCode -le $highest -and -not $Force) {
    throw "versionCode $versionCode is not higher than $highest already in $Dest (use -Force to override)."
}

$sidecar = [ordered]@{
    versionCode = $versionCode
    versionName = $versionName
    sha256      = $sha
    notes       = $Notes
    builtAt     = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
} | ConvertTo-Json -Compress

if ($DryRun) {
    Write-Host "[dry-run] version   : $versionName ($versionCode)"
    Write-Host "[dry-run] sha256    : $sha"
    Write-Host "[dry-run] highest in dest: $highest"
    Write-Host "[dry-run] would create $Dest if missing"
    Write-Host "[dry-run] would copy $apk -> $apkOut"
    Write-Host "[dry-run] would write $jsonOut : $sidecar"
    Write-Host '[dry-run] nothing written.'
    return
}

if (-not (Test-Path $Dest)) { New-Item -ItemType Directory -Path $Dest | Out-Null }
Copy-Item -Path $apk -Destination $apkOut -Force
[System.IO.File]::WriteAllText($jsonOut, $sidecar, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Staged $base in $Dest"
