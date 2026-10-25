# Preview only: this installer never changes the default HOME.
[CmdletBinding()]
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$Apk = '',
    [switch]$GrantLocation
)
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
if ([string]::IsNullOrWhiteSpace($Apk)) { $Apk = Join-Path $scriptDir 'home-personnel-preview.apk' }
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK not found: $Apk" }
if (-not (Test-Path -LiteralPath $restorePath -PathType Leaf)) { throw 'Keep restore-personal-home.ps1 beside this installer before continuing.' }
if ([string]::IsNullOrWhiteSpace($TvHost) -or $TvHost -notmatch '^[A-Za-z0-9.:-]+$') { throw 'Supply -TvHost <tvip> or set TV_HOST; an optional :5555 port is supported.' }
$serial = if ($TvHost -match ':\d+$') { $TvHost } else { "${TvHost}:5555" }
Invoke-Adb -Arguments @('connect', $serial) | Out-Null
if ((Invoke-Tv -Arguments @('get-state')).Trim() -ne 'device') { throw 'The TV is not connected and authorized. Check the debugging prompt on the TV.' }
if (Test-Path -LiteralPath $backupPath) {
    $saved = Get-SavedHome
} else {
    $originalHome = Get-HomeComponent
    if ($originalHome -match '(?i)factory|hkcfac|setupwraith|FallbackHome') { throw 'Resolved HOME is a recovery/test screen; open the normal launcher before retrying.' }
    if ($originalHome -like 'com.gathin.personalhome/*') { throw 'The preview is already HOME but no original backup exists. Recover the original HOME component before installing again.' }
    New-Item -ItemType Directory -Path (Split-Path -Parent $backupPath) -Force | Out-Null
    $saved = [pscustomobject]@{ Version = 1; Device = $serial; OriginalHome = $originalHome }
    $saved | ConvertTo-Json | Set-Content -LiteralPath $backupPath -Encoding UTF8
}
# The first backup is never rewritten on a later install or activation.
Write-Host "Original HOME preserved. Restore: .\restore-personal-home.ps1"
$installed = Invoke-Tv -Arguments @('install', '-r', $Apk)
if ($installed -notmatch '(?m)^Success\s*$') { throw "Install did not report Success: $installed" }
Invoke-Tv -Arguments @('shell', "pm enable --user 0 $homePackage") | Out-Null
$enabled = Invoke-Tv -Arguments @('shell', "pm list packages -e --user 0 $homePackage")
if ($enabled -notmatch '(?m)^package:com\.gathin\.personalhome\s*$') { throw 'The personal launcher is installed but not enabled. Its HOME default was not changed.' }
if ($GrantLocation) {
    Invoke-Tv -Arguments @('shell', "pm grant --user 0 $homePackage android.permission.ACCESS_COARSE_LOCATION") | Out-Null
    Invoke-Tv -Arguments @('shell', "pm grant --user 0 $homePackage android.permission.ACCESS_FINE_LOCATION") | Out-Null
}
Invoke-Tv -Arguments @('shell', "am start -W -n $homeComponent") | Out-Null
# The companion and this launcher use the same certificate; Android grants the signature bridge.
$statusFile = Join-Path $scriptDir 'private\recommendation-status.txt'
if (Test-Path -LiteralPath $statusFile) { Remove-Item -LiteralPath $statusFile }
try {
    Invoke-Tv -Arguments @('shell', 'am start -W -n com.gathin.ambienttv/.RecommendationsProbeActivity') | Out-Null
    Invoke-Tv -Arguments @('pull', '/sdcard/Android/data/com.gathin.ambienttv/files/recommendation-status.txt', $statusFile) | Out-Null
    Write-Host 'Recommendation probe saved in private\recommendation-status.txt.'
} catch {
    Write-Warning "Companion probe was unavailable: $($_.Exception.Message)"
} finally {
    Invoke-Tv -Arguments @('shell', "am start -W -n $homeComponent") | Out-Null
}
Write-Host 'Preview opened. HOME default is unchanged. Test the real remote and apps before running activate-personal-home.ps1.'
