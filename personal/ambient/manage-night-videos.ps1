# Windows PowerShell 5.1. Manage the Night YouTube playlist without reinstalling.
[CmdletBinding()]
param(
    [ValidateSet('Add', 'Remove', 'List')]
    [string]$Action = 'List',
    [string]$Url = '',
    [Alias('Id')]
    [string]$VideoId = '',
    [string]$Title = '',
    [string]$TvHost = $env:TV_HOST
)
$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

function Get-YouTubeId {
    param([string]$Value)
    $value = $Value.Trim()
    if ($value.Length -gt 4096 -or $value -match '\p{Cc}') {
        throw 'Supply a YouTube HTTPS URL of at most 4096 characters without control characters.'
    }
    $parsed = $null
    if (-not [uri]::TryCreate($value, [UriKind]::Absolute, [ref]$parsed) -or
        $parsed.Scheme -ne 'https' -or $parsed.UserInfo.Length -gt 0 -or -not $parsed.IsDefaultPort) {
        throw 'Supply a YouTube HTTPS URL without embedded credentials or a custom port.'
    }
    $sourceHost = $parsed.DnsSafeHost.ToLowerInvariant()
    $youtubeHosts = @('youtube.com', 'www.youtube.com', 'm.youtube.com', 'music.youtube.com')
    $embedHosts = @('youtube-nocookie.com', 'www.youtube-nocookie.com')
    $path = $parsed.AbsolutePath.TrimEnd('/')
    $id = ''
    if ($sourceHost -in @('youtu.be', 'www.youtu.be')) {
        if ($path -cmatch '^/([A-Za-z0-9_-]{11})$') { $id = $Matches[1] }
    } elseif ($sourceHost -in $youtubeHosts) {
        if ($path -ceq '/watch') {
            $values = @()
            foreach ($part in $parsed.Query.TrimStart('?').Split('&')) {
                $pair = $part -split '=', 2
                if ($pair[0] -ceq 'v' -and $pair.Length -eq 2) { $values += $pair[1] }
            }
            if ($values.Count -eq 1) { $id = $values[0] }
        } elseif ($path -cmatch '^/(shorts|live|embed)/([A-Za-z0-9_-]{11})$') {
            $id = $Matches[2]
        }
    } elseif ($sourceHost -in $embedHosts -and $path -cmatch '^/embed/([A-Za-z0-9_-]{11})$') {
        $id = $Matches[1]
    }
    if ($id -cnotmatch '^[A-Za-z0-9_-]{11}$') {
        throw 'Use a YouTube watch, youtu.be, shorts, live or embed URL containing one video ID.'
    }
    return $id
}

if ($Action -eq 'List') {
    if ($Url.Length -gt 0 -or $VideoId.Length -gt 0 -or $Title.Length -gt 0) {
        throw 'List does not take -Url, -VideoId or -Title.'
    }
    $operation = 'list'
} else {
    if ([string]::IsNullOrWhiteSpace($Url) -eq [string]::IsNullOrWhiteSpace($VideoId)) {
        throw 'Supply either -Url with one YouTube link or -VideoId with its 11-character ID.'
    }
    if (-not [string]::IsNullOrWhiteSpace($Url)) { $VideoId = Get-YouTubeId -Value $Url }
    else { $VideoId = $VideoId.Trim() }
    if ($VideoId -cnotmatch '^[A-Za-z0-9_-]{11}$') { throw 'Video IDs must contain exactly 11 letters, digits, underscores or hyphens.' }
    $operation = $Action.ToLowerInvariant() + '_' + $VideoId
    if ($Action -eq 'Remove' -and $Title.Length -gt 0) { throw 'Remove does not take -Title.' }
}
if ($Action -eq 'Add' -and $PSBoundParameters.ContainsKey('Title')) {
    if ($Title -match '\p{Cc}') { throw 'Titles must contain 1 to 100 characters without control characters.' }
    $Title = $Title.Trim()
    if ([string]::IsNullOrWhiteSpace($Title) -or $Title.Length -gt 100) {
        throw 'Titles must contain 1 to 100 characters without control characters.'
    }
}
if ([string]::IsNullOrWhiteSpace($TvHost)) { throw 'Supply -TvHost with the TV address, or set the TV_HOST environment variable.' }
$TvHost = $TvHost.Trim()
$hostMatch = [regex]::Match($TvHost, '^[A-Za-z0-9][A-Za-z0-9.-]*(?::([0-9]{1,5}))?$')
if (-not $hostMatch.Success) {
    throw 'Supply the TV IPv4 address or hostname, optionally followed by :5555.'
}
if ($hostMatch.Groups[1].Success -and ([int]$hostMatch.Groups[1].Value -lt 1 -or [int]$hostMatch.Groups[1].Value -gt 65535)) { throw 'The ADB port must be between 1 and 65535.' }
$serial = if ($TvHost -match ':\d+$') { $TvHost } else { "${TvHost}:5555" }
$adb = $null
foreach ($candidate in @((Join-Path $scriptDir 'adb.exe'), (Join-Path (Get-Location).Path 'adb.exe'))) {
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { $adb = $candidate; break }
}
if (-not $adb) {
    $command = Get-Command adb.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($command) { $adb = $command.Source }
}
if (-not $adb) { throw 'Run this script from platform-tools, where adb.exe is located, or add platform-tools to PATH.' }

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$NoDevice)
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        if ($NoDevice) { $result = @(& $adb @Arguments 2>&1) }
        else { $result = @(& $adb -s $serial @Arguments 2>&1) }
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    $output = ($result | ForEach-Object { "$_" }) -join "`n"
    if ($code -ne 0 -or $output -match '(?im)^\s*(Error\b|Exception\b|java\.[A-Za-z.]*Exception|Permission Denial|Failure\b|Failed\b|adb: error:|SecurityException|cmd:.*not found|No result found|cannot connect|unable to connect)') {
        throw "ADB failed ($code): $output"
    }
    return $output
}

Invoke-Adb -NoDevice -Arguments @('connect', $serial) | Out-Null
$state = Invoke-Adb -Arguments @('get-state')
if ($state.Trim() -ne 'device') { throw 'The TV is not connected. Enable network debugging and accept the ADB authorization on the TV.' }
# Only validated ASCII IDs and base64 reach the Android shell, never a raw URL or title.
$remoteCommand = 'content call --uri content://com.gathin.ambienttv.files --method night_playlist --arg ' + $operation
if ($Action -eq 'Add' -and $PSBoundParameters.ContainsKey('Title')) {
    $title64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Title))
    $remoteCommand += ' --extra title64:s:' + $title64
}
$output = Invoke-Adb -Arguments @('shell', $remoteCommand)
$resultMatch = [regex]::Match($output, '(?s)Bundle\[\{result=(.*)\}\]\s*$')
if (-not $resultMatch.Success) {
    throw "The TV did not return a playlist result. Install preview 3.5 or newer once, then retry. $output"
}
try { $response = $resultMatch.Groups[1].Value | ConvertFrom-Json }
catch { throw 'The TV returned an invalid playlist result.' }
if ($response.success -ne $true) { throw "Playlist request failed: $($response.message)" }
if (-not ($response.videos -is [System.Array])) { throw 'The TV returned a playlist result without a videos array.' }
if ($response.message) { Write-Host $response.message }
$index = 0
foreach ($video in @($response.videos)) {
    $index++
    Write-Host ("{0}. {1}`n   https://www.youtube.com/watch?v={2}" -f $index, $video.title, $video.id)
}
if ($Action -ne 'List') { Write-Host 'Return Home, then reopen the moon to load the updated playlist.' }
