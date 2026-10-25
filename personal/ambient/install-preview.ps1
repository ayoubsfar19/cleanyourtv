# Install the independent preview, back up the exact prior screensaver, then activate it.
[CmdletBinding()]
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$Folder = '',
    [string]$Apk = ''
)
$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($Folder)) { $Folder = Join-Path $scriptDir 'photos' }
if ([string]::IsNullOrWhiteSpace($Apk)) { $Apk = Join-Path $scriptDir 'photos-football-preview.apk' }
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK not found: $Apk" }
& (Join-Path $scriptDir 'sync-photos.ps1') -Folder $Folder -ValidateOnly
if ([string]::IsNullOrWhiteSpace($TvHost) -or $TvHost -notmatch '^[A-Za-z0-9.:-]+$') { throw 'Supply -TvHost with the TV IP, optionally followed by :5555.' }
$serial = if ($TvHost -match ':\d+$') { $TvHost } else { "${TvHost}:5555" }
$adb = $null
foreach ($candidate in @((Join-Path $scriptDir 'adb.exe'), (Join-Path (Get-Location).Path 'adb.exe'))) {
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { $adb = $candidate; break }
}
if (-not $adb) { $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue; if ($cmd) { $adb = $cmd.Source } }
if (-not $adb) { throw 'Run this script from platform-tools, where adb.exe is located.' }
$savedPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $connection = @(& $adb connect $serial 2>&1)
    $connectCode = $LASTEXITCODE
} finally { $ErrorActionPreference = $savedPreference }
if ($connectCode -ne 0) { throw ($connection -join "`n") }
function Invoke-Tv([string[]]$Arguments) {
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $result = @(& $adb -s $serial @Arguments 2>&1)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    $output = ($result | ForEach-Object { "$_" }) -join "`n"
    if ($code -ne 0 -or $output -match '(?im)^\s*(Error:|Exception|java\.[A-Za-z.]*Exception|Permission Denial|Failure|adb: error:|SecurityException)') {
        throw "ADB failed ($code): $output"
    }
    return $output
}
if ((Invoke-Tv -Arguments @('get-state')).Trim() -ne 'device') { throw 'TV is not connected or authorized.' }
$backup = Join-Path $scriptDir 'screensaver-before.json'
$restore = Join-Path $scriptDir 'restore-screensaver.ps1'
if (-not (Test-Path -LiteralPath $backup)) {
    $settings = @()
    foreach ($entry in @(
        @('secure','screensaver_components'),
        @('secure','screensaver_enabled'),
        @('secure','screensaver_activate_on_sleep'),
        @('secure','screensaver_activate_on_dock'),
        @('system','screen_off_timeout')
    )) {
        $value = (Invoke-Tv -Arguments @('shell', "settings get $($entry[0]) $($entry[1])")).Trim()
        if ($value -notmatch '^[A-Za-z0-9_.,/:-]+$') { throw "Unexpected setting value for $($entry[1]); no changes made." }
        $settings += [pscustomobject]@{ Scope=$entry[0]; Name=$entry[1]; Value=$value }
    }
    [pscustomobject]@{ Device=$serial; Settings=$settings } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $backup -Encoding UTF8
} else {
    $saved = Get-Content -LiteralPath $backup -Raw | ConvertFrom-Json
    if ($saved.Device -ne $serial) { throw 'The existing backup belongs to a different TV; keep it and use a new preview folder.' }
}
# The restore file exists before installing or changing any setting. Re-running preserves the first backup.
@'
[CmdletBinding()]
param([string]$TvHost)
$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$saved = Get-Content -LiteralPath (Join-Path $scriptDir 'screensaver-before.json') -Raw | ConvertFrom-Json
$serial = if ($TvHost) { if ($TvHost -match ':\d+$') { $TvHost } else { "${TvHost}:5555" } } else { $saved.Device }
if ($serial -notmatch '^[A-Za-z0-9.:-]+$') { throw 'Invalid TV address.' }
$adb = $null
foreach ($candidate in @((Join-Path $scriptDir 'adb.exe'),(Join-Path (Get-Location).Path 'adb.exe'))) {
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { $adb=$candidate; break }
}
if (-not $adb) { $cmd=Get-Command adb.exe -ErrorAction SilentlyContinue; if($cmd){$adb=$cmd.Source} }
if (-not $adb) { throw 'Run from platform-tools, where adb.exe is located.' }
& $adb connect $serial
if ($LASTEXITCODE) { throw 'ADB connection failed.' }
foreach ($s in $saved.Settings) {
    if ($s.Scope -notin @('secure','system') -or $s.Name -notmatch '^[a-z_]+$' -or $s.Value -notmatch '^[A-Za-z0-9_.,/:-]+$') { throw 'Invalid backup content.' }
    $command = if ($s.Value -eq 'null') { "settings delete $($s.Scope) $($s.Name)" } else { "settings put $($s.Scope) $($s.Name) $($s.Value)" }
    $output = @(& $adb -s $serial shell $command 2>&1)
    if ($LASTEXITCODE -or ($output -join "`n") -match 'Exception|Error:|Permission Denial') { throw ($output -join "`n") }
}
& $adb -s $serial shell am force-stop com.gathin.ambienttv
if ($LASTEXITCODE) { throw 'Could not close the preview.' }
Write-Host 'Previous screensaver settings restored. Photos and launcher were kept.'
'@ | Set-Content -LiteralPath $restore -Encoding UTF8
Write-Host "Backup saved. Undo: $restore"
$installed = Invoke-Tv -Arguments @('install','-r',$Apk)
if ($installed -notmatch '(?m)^Success\s*$') { throw "Install did not report Success: $installed" }
Invoke-Tv -Arguments @('shell','pm enable --user 0 com.gathin.ambienttv') | Out-Null
& (Join-Path $scriptDir 'sync-photos.ps1') -TvHost $TvHost -Folder $Folder -Seconds 30 -Replace
Invoke-Tv -Arguments @('shell','settings put secure screensaver_components com.gathin.ambienttv/.PhotoDream') | Out-Null
Invoke-Tv -Arguments @('shell','settings put secure screensaver_enabled 1') | Out-Null
Invoke-Tv -Arguments @('shell','settings put secure screensaver_activate_on_sleep 1') | Out-Null
Invoke-Tv -Arguments @('shell','settings put system screen_off_timeout 300000') | Out-Null
Invoke-Tv -Arguments @('shell','am start -W -n com.gathin.ambienttv/.SlideshowActivity') | Out-Null
Write-Host 'Ready: photo 30 s, idle 5 min. Football opens with OK; Back returns to photos; Home returns to launcher.'
Write-Host 'Keep screensaver-before.json and restore-screensaver.ps1 for undo.'
