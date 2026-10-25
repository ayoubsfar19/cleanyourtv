# Restore only the first saved HOME component; keep every installed app and its data.
[CmdletBinding()]
param([string]$TvHost = $env:TV_HOST)
$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$homePackage = 'com.gathin.personalhome'
$homeComponent = 'com.gathin.personalhome/com.gathin.tvhome.MainActivity'
$componentPattern = '^[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+$'
$backupPath = Join-Path $scriptDir 'private\home-before.json'
$restorePath = Join-Path $scriptDir 'restore-personal-home.ps1'
$adb = $null
foreach ($candidate in @((Join-Path $scriptDir 'adb.exe'), (Join-Path (Get-Location).Path 'adb.exe'))) {
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { $adb = $candidate; break }
}
if (-not $adb) { $command = Get-Command adb.exe -ErrorAction SilentlyContinue; if ($command) { $adb = $command.Source } }
if (-not $adb) { throw 'Place adb.exe beside these scripts, run from platform-tools, or add platform-tools to PATH.' }
function Invoke-Adb([string[]]$Arguments) {
    # Windows PowerShell 5.1 turns native stderr into ErrorRecord objects. Inspect it after execution.
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $result = @(& $adb @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    $output = ($result | ForEach-Object { "$_" }) -join "`n"
    if ($exitCode -ne 0 -or $output -match '(?im)^\s*(Error\b|Exception\b|java\.[A-Za-z.]*Exception|Permission Denial|Failure\b|adb: error:|SecurityException)') {
        throw "ADB failed ($exitCode): $output"
    }
    return $output
}
function Invoke-Tv([string[]]$Arguments) { return Invoke-Adb -Arguments (@('-s', $serial) + $Arguments) }
function Get-HomeComponent {
    $output = Invoke-Tv -Arguments @('shell', 'cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME')
    $components = @($output -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -match $componentPattern })
    if ($components.Count -eq 0) { throw 'Could not resolve a HOME component; no HOME change was made.' }
    return $components[-1]
}
function Get-SavedHome {
    if (-not (Test-Path -LiteralPath $backupPath -PathType Leaf)) { throw 'Missing private\home-before.json. Run install-home-preview.ps1 first.' }
    $saved = Get-Content -LiteralPath $backupPath -Raw | ConvertFrom-Json
    if ($saved.Device -ne $serial) { throw 'This backup belongs to a different TV. Keep it and use a separate preview folder for the other TV.' }
    if ($saved.OriginalHome -notmatch $componentPattern -or $saved.OriginalHome -like 'com.gathin.personalhome/*' -or $saved.OriginalHome -match '(?i)factory|hkcfac|setupwraith|FallbackHome') { throw 'The original HOME backup is invalid; it was left unchanged.' }
    return $saved
}
if ([string]::IsNullOrWhiteSpace($TvHost)) {
    if (-not (Test-Path -LiteralPath $backupPath -PathType Leaf)) { throw 'Missing private\home-before.json.' }
    $backupDevice = Get-Content -LiteralPath $backupPath -Raw | ConvertFrom-Json
    $TvHost = $backupDevice.Device
}
if ([string]::IsNullOrWhiteSpace($TvHost) -or $TvHost -notmatch '^[A-Za-z0-9.:-]+$') { throw 'Supply -TvHost <tvip> or set TV_HOST; an optional :5555 port is supported.' }
$serial = if ($TvHost -match ':\d+$') { $TvHost } else { "${TvHost}:5555" }
Invoke-Adb -Arguments @('connect', $serial) | Out-Null
if ((Invoke-Tv -Arguments @('get-state')).Trim() -ne 'device') { throw 'The TV is not connected and authorized. Check the debugging prompt on the TV.' }
$saved = Get-SavedHome
Invoke-Tv -Arguments @('shell', "cmd package set-home-activity --user 0 $($saved.OriginalHome)") | Out-Null
Invoke-Tv -Arguments @('shell', "am start -W -n $($saved.OriginalHome)") | Out-Null
if ((Get-HomeComponent) -ne $saved.OriginalHome) { throw 'Android did not resolve HOME to the saved component. Keep the backup and inspect the TV.' }
Write-Host 'Original HOME restored and opened. The personal preview and all other apps were kept.'
