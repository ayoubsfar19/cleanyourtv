# Windows PowerShell 5.1. Copies original MP4s; never writes pictures or TV settings.
[CmdletBinding()]
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$Folder = '',
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($Folder)) { $Folder = Join-Path $scriptDir 'night-videos' }
if (-not (Test-Path -LiteralPath $Folder -PathType Container)) {
    throw "Night video folder not found: $Folder. Create it and add 1.mp4 through 4.mp4, or night-videos.json with direct HTTPS MP4 URLs."
}

# The four slots keep the user's chosen order. Titles use ASCII source text for PS 5.1.
$dot = [char]0x00b7
$defaults = @(
    [ordered]@{ id = '7_Wbmy79wxQ'; title = "The Art Corner $dot 1"; file = '1.mp4'; url = '' },
    [ordered]@{ id = 'BVdHrTfO2PE'; title = "The Art Corner $dot 2"; file = '2.mp4'; url = '' },
    [ordered]@{ id = 'yTRHheKoVhY'; title = "Bedroom R&B $dot 1"; file = '3.mp4'; url = '' },
    [ordered]@{ id = 'BUeCNBg_qYk'; title = "Bedroom R&B $dot 2"; file = '4.mp4'; url = '' }
)
$byId = @{}
foreach ($entry in $defaults) { $byId[$entry.id] = $entry }
$configPath = Join-Path $Folder 'night-videos.json'
$suppliedConfig = Test-Path -LiteralPath $configPath -PathType Leaf
$entries = $defaults
if ($suppliedConfig) {
    if ((Get-Item -LiteralPath $configPath).Length -gt 65536) { throw 'night-videos.json must be at most 65536 bytes.' }
    try { $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json }
    catch { throw 'night-videos.json is not valid JSON.' }
    if ($null -eq $config -or $config.version -ne 1 -or -not ($config.videos -is [System.Array])) {
        throw 'night-videos.json must contain version: 1 and a videos array.'
    }
    $videos = @($config.videos)
    if ($videos.Count -lt 1 -or $videos.Count -gt 4) { throw 'night-videos.json must contain 1 to 4 video entries.' }
    $seen = @{}
    $entries = @()
    foreach ($video in $videos) {
        if ($null -eq $video -or -not ($video.id -is [string]) -or -not $byId.ContainsKey($video.id) -or $byId[$video.id].id -cne $video.id) {
            throw 'night-videos.json contains an unknown video id. Use the four IDs shown in README-night.md.'
        }
        $expected = $byId[$video.id]
        if ($seen.ContainsKey($video.id)) { throw "Duplicate night video id: $($video.id)" }
        $seen[$video.id] = $true
        if (-not ($video.file -is [string]) -or $video.file -cne $expected.file) {
            throw "Video $($video.id) must use file $($expected.file)."
        }
        $title = $expected.title
        if ($null -ne $video.title) {
            if (-not ($video.title -is [string]) -or [string]::IsNullOrWhiteSpace($video.title) -or $video.title.Length -gt 100 -or $video.title -match '\p{Cc}') {
                throw "Video $($video.id) needs a title of 1 to 100 characters without control characters."
            }
            $title = $video.title.Trim()
        }
        $url = ''
        if ($null -ne $video.url) {
            if (-not ($video.url -is [string])) { throw "Video $($video.id) has an invalid URL." }
            $url = $video.url.Trim()
        }
        if ($url.Length -gt 0) {
            if ($url.Length -gt 4096 -or $url -match '\p{Cc}') { throw "Video $($video.id) needs a URL of at most 4096 characters without control characters." }
            $parsedUrl = $null
            if (-not [uri]::TryCreate($url, [UriKind]::Absolute, [ref]$parsedUrl) -or $parsedUrl.Scheme -ne 'https' -or [string]::IsNullOrWhiteSpace($parsedUrl.Host) -or $parsedUrl.UserInfo.Length -gt 0) {
                throw "Video $($video.id) needs a direct HTTPS MP4 URL without embedded credentials."
            }
            $sourceHost = $parsedUrl.DnsSafeHost.ToLowerInvariant()
            if ($sourceHost -match '(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com|googlevideo\.com)$') {
                throw 'Use your original MP4 file or a direct HTTPS MP4 URL. YouTube links are not native video sources.'
            }
        }
        $entries += [ordered]@{ id = $video.id; title = $title; file = $expected.file; url = $url }
    }
}
$files = @(Get-ChildItem -LiteralPath $Folder -File | Where-Object { $_.Extension -ieq '.mp4' } | Sort-Object Name)
foreach ($file in $files) {
    if ($file.Name -cnotmatch '^[1-4]\.mp4$') { throw "Rename $($file.Name) to its slot: 1.mp4, 2.mp4, 3.mp4 or 4.mp4." }
    if ($file.Length -eq 0 -or $file.Length -gt 1GB) { throw "Video is empty or over 1 GiB: $($file.Name)" }
}
if ($files.Count -eq 0 -and -not $suppliedConfig) {
    throw 'No source videos supplied. Add 1.mp4 through 4.mp4, or night-videos.json with your direct HTTPS MP4 URLs. No TV files were changed.'
}
$configText = [ordered]@{ version = 1; videos = @($entries) } | ConvertTo-Json -Depth 5
if ([Text.Encoding]::UTF8.GetByteCount($configText) -gt 65536) { throw 'The normalized Night configuration exceeds 65536 bytes.' }
if ($ValidateOnly) {
    Write-Host "$($files.Count) original MP4 files and $($entries.Count) configured slots ready. No TV files were changed."
    return
}
if ([string]::IsNullOrWhiteSpace($TvHost) -or $TvHost -notmatch '^[A-Za-z0-9.:-]+$') {
    throw 'Supply -TvHost with the TV address, optionally followed by :5555.'
}
$serial = if ($TvHost -match ':\d+$') { $TvHost } else { "${TvHost}:5555" }
$adb = $null
foreach ($candidate in @((Join-Path $scriptDir 'adb.exe'), (Join-Path (Get-Location).Path 'adb.exe'))) {
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { $adb = $candidate; break }
}
if (-not $adb) {
    $command = Get-Command adb.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($command) { $adb = $command.Source }
}
if (-not $adb) { throw 'Run this script from platform-tools, where adb.exe is located.' }
function Invoke-Tv {
    param([string[]]$Arguments)
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $result = @(& $adb -s $serial @Arguments 2>&1)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    $output = ($result | ForEach-Object { "$_" }) -join "`n"
    if ($code -ne 0 -or $output -match '(?im)^\s*(Error\b|Exception\b|java\.[A-Za-z.]*Exception|Permission Denial|Failure\b|Failed\b|adb: error:|SecurityException|cmd:.*not found|No result found)') {
        throw "ADB failed ($code): $output"
    }
    return $output
}
function Remove-StagingFile {
    param([string]$RemotePath)
    try { Invoke-Tv -Arguments @('shell', "rm -f $RemotePath") | Out-Null }
    catch { Write-Warning 'Could not remove a temporary upload file from /data/local/tmp.' }
}
$state = Invoke-Tv -Arguments @('get-state')
if ($state.Trim() -ne 'device') { throw 'TV is not connected. Run adb.exe connect with the TV address first.' }
$provider = 'content://com.gathin.ambienttv.files'
$deviceFolder = '/sdcard/Android/data/com.gathin.ambienttv/files'
$tag = 'ambient-night-' + [guid]::NewGuid().ToString('N')
foreach ($file in $files) {
    Write-Host "Night video: $($file.Name) (original file)"
    $remoteTemp = "/data/local/tmp/$tag-$($file.Name)"
    try {
        Invoke-Tv -Arguments @('push', $file.FullName, $remoteTemp) | Out-Null
        $stagedLength = Invoke-Tv -Arguments @('shell', "wc -c < $remoteTemp")
        if ($stagedLength.Trim() -ne [string]$file.Length) { throw "Video staging size did not match: $($file.Name). The existing slot was not written." }
        Invoke-Tv -Arguments @('shell', "content write --uri $provider/night-videos/$($file.Name) < $remoteTemp") | Out-Null
        $length = Invoke-Tv -Arguments @('shell', "wc -c < $deviceFolder/night-videos/$($file.Name)")
        if ($length.Trim() -ne [string]$file.Length) { throw "Video size did not match after upload: $($file.Name)" }
    } finally { Remove-StagingFile -RemotePath $remoteTemp }
}
# Publish configuration only after all requested local videos were copied successfully.
$localTemp = [IO.Path]::GetTempFileName()
$remoteTemp = "/data/local/tmp/$tag-config.json"
try {
    [IO.File]::WriteAllText($localTemp, $configText, (New-Object System.Text.UTF8Encoding($false)))
    Invoke-Tv -Arguments @('push', $localTemp, $remoteTemp) | Out-Null
    $localLength = (Get-Item -LiteralPath $localTemp).Length
    $stagedLength = Invoke-Tv -Arguments @('shell', "wc -c < $remoteTemp")
    if ($stagedLength.Trim() -ne [string]$localLength) { throw 'Night configuration staging size did not match. The existing configuration was not written.' }
    Invoke-Tv -Arguments @('shell', "content write --uri $provider/night-videos.json < $remoteTemp") | Out-Null
    $length = Invoke-Tv -Arguments @('shell', "wc -c < $deviceFolder/night-videos.json")
    if ($length.Trim() -ne [string]$localLength) { throw 'Night video configuration size did not match after upload.' }
} finally {
    Remove-StagingFile -RemotePath $remoteTemp
    Remove-Item -LiteralPath $localTemp -Force -ErrorAction SilentlyContinue
}
Write-Host "$($files.Count) original MP4 files synced. Reopen the preview and choose a Night video."
Write-Host 'Missing local files were kept on the TV; photos and screensaver settings were unchanged.'
