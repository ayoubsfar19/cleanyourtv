# Windows PowerShell 5.1. Writes only this app's picture folder; original photos are never touched.
[CmdletBinding()]
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$Folder = (Join-Path $PSScriptRoot 'photos'),
    [ValidateRange(5,3600)][int]$Seconds = 30,
    [switch]$Replace,
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Folder -PathType Container)) { throw "Photo folder not found: $Folder" }
$files = @(Get-ChildItem -LiteralPath $Folder -File | Where-Object { $_.Extension -match '^\.(jpg|jpeg|png|webp)$' } | Sort-Object Name)
if ($files.Count -eq 0) { throw 'No supported pictures in the photos folder. No settings were changed.' }
if ($files.Count -gt 100) { throw 'Maximum: 100 pictures.' }
foreach ($f in $files) {
    if ($f.Name -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*\.(jpg|jpeg|png|webp)$') { throw "Rename $($f.Name): use ASCII letters, digits, dot, dash and underscore only." }
    if ($f.Length -eq 0 -or $f.Length -gt 10MB) { throw "Picture is empty or over 10 MB: $($f.Name)" }
}
if ($ValidateOnly) { Write-Host "$($files.Count) pictures ready."; return }
if ([string]::IsNullOrWhiteSpace($TvHost) -or $TvHost -notmatch '^[A-Za-z0-9.:-]+$') { throw 'Supply -TvHost with the TV IP, optionally followed by :5555.' }
$serial = if ($TvHost -match ':\d+$') { $TvHost } else { "${TvHost}:5555" }
$adb = $null
foreach ($candidate in @((Join-Path $PSScriptRoot 'adb.exe'), (Join-Path (Get-Location).Path 'adb.exe'))) {
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { $adb = $candidate; break }
}
if (-not $adb) { $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue; if ($cmd) { $adb = $cmd.Source } }
if (-not $adb) { throw 'Run this script from platform-tools, where adb.exe is located.' }
function Invoke-Tv([string[]]$Arguments) {
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $result = @(& $adb -s $serial @Arguments 2>&1)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    $output = ($result | ForEach-Object { "$_" }) -join "`n"
    if ($code -ne 0 -or $output -match '(?im)^\s*(Error:|Exception|java\.[A-Za-z.]*Exception|Permission Denial|Failure|adb: error:|SecurityException|cmd:.*not found)') {
        throw "ADB failed ($code): $output"
    }
    return $output
}
$state = Invoke-Tv -Arguments @('get-state')
if ($state.Trim() -ne 'device') { throw 'TV is not connected. Run adb.exe connect with the TV address first.' }
Invoke-Tv -Arguments @('shell','am start -W -n com.gathin.ambienttv/.SlideshowActivity') | Out-Null
$uri = 'content://com.gathin.ambienttv.files/pictures'
$deviceFolder = '/sdcard/Android/data/com.gathin.ambienttv/files/pictures'
$tag = 'ambient-' + [guid]::NewGuid().ToString('N')
$names = @($files | ForEach-Object { $_.Name })
foreach ($f in $files) {
    Write-Host "Photo: $($f.Name)"
    $temp = "/data/local/tmp/$tag-$($f.Name)"
    try {
        Invoke-Tv -Arguments @('push', $f.FullName, $temp) | Out-Null
        Invoke-Tv -Arguments @('shell', "content write --uri $uri/$($f.Name) < $temp") | Out-Null
        $confirmed = Invoke-Tv -Arguments @('shell', "test -s $deviceFolder/$($f.Name) && echo COPIED")
        if ($confirmed.Trim() -ne 'COPIED') { throw "Photo was not written: $($f.Name)" }
    } finally {
        Invoke-Tv -Arguments @('shell', "rm -f $temp") | Out-Null
    }
}
# Remove stale files only after every desired picture has been successfully written.
if ($Replace) {
    $listed = Invoke-Tv -Arguments @('shell', "ls -1 $deviceFolder")
    foreach ($name in ($listed -split "`r?`n")) {
        $name = $name.Trim()
        if ($name -match '^[A-Za-z0-9][A-Za-z0-9_.-]*\.(jpg|jpeg|png|webp)$' -and $names -notcontains $name) {
            Invoke-Tv -Arguments @('shell', "content delete --uri $uri/$name") | Out-Null
            Write-Host "Removed from this screensaver: $name"
        }
    }
}
$temp = "/data/local/tmp/$tag-seconds.txt"
try {
    Invoke-Tv -Arguments @('shell', "echo $Seconds > $temp") | Out-Null
    Invoke-Tv -Arguments @('shell', "content write --uri content://com.gathin.ambienttv.files/seconds.txt < $temp") | Out-Null
} finally { Invoke-Tv -Arguments @('shell', "rm -f $temp") | Out-Null }
# Reopen the slideshow so it sees file changes immediately.
Invoke-Tv -Arguments @('shell','am force-stop com.gathin.ambienttv') | Out-Null
Invoke-Tv -Arguments @('shell','am start -W -n com.gathin.ambienttv/.SlideshowActivity') | Out-Null
Write-Host "$($files.Count) photos synced; $Seconds seconds each. The launcher is unchanged."
