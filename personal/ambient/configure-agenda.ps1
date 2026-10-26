# Windows PowerShell 5.1. Configure the private Google Calendar feed locally.
[CmdletBinding()]
param(
    [ValidateSet('Configure', 'Status', 'Disable', 'Enable', 'Disconnect', 'Sync')]
    [string]$Action = 'Configure',
    [System.Security.SecureString]$SecretUrl,
    [ValidateSet(0, 5, 10, 15, 30, 60)]
    [int]$DefaultMinutes = 10,
    [string]$TvHost = $env:TV_HOST
)
$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

if ($Action -ne 'Configure' -and ($PSBoundParameters.ContainsKey('SecretUrl') -or $PSBoundParameters.ContainsKey('DefaultMinutes'))) {
    throw 'Seule l action Configure accepte -SecretUrl et -DefaultMinutes.'
}
if ([string]::IsNullOrWhiteSpace($TvHost)) {
    throw 'Indiquez -TvHost avec l adresse de la TV, ou definissez TV_HOST.'
}
$TvHost = $TvHost.Trim()
$hostMatch = [regex]::Match($TvHost, '^[A-Za-z0-9][A-Za-z0-9.-]*(?::([0-9]{1,5}))?$')
if (-not $hostMatch.Success) {
    throw 'Indiquez une adresse IPv4 ou un nom de TV, avec le port ADB facultatif :5555.'
}
if ($hostMatch.Groups[1].Success -and ([int]$hostMatch.Groups[1].Value -lt 1 -or [int]$hostMatch.Groups[1].Value -gt 65535)) {
    throw 'Le port ADB doit etre compris entre 1 et 65535.'
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
if (-not $adb) {
    throw 'Placez ce script pres de adb.exe dans platform-tools, ou ajoutez platform-tools au PATH.'
}

function Invoke-AgendaAdb {
    param([string[]]$Arguments, [switch]$NoDevice)
    $savedPreference = $ErrorActionPreference
    try {
        # Capture native stderr as well: never echo a command or a provider error,
        # since either could contain the reversible, encoded calendar credential.
        $ErrorActionPreference = 'Continue'
        try {
            if ($NoDevice) { $result = @(& $adb @Arguments 2>&1) }
            else { $result = @(& $adb -s $serial @Arguments 2>&1) }
            $code = $LASTEXITCODE
        } catch {
            throw 'Impossible de lancer ADB. Verifiez platform-tools et la connexion a la TV.'
        }
    } finally { $ErrorActionPreference = $savedPreference }
    $output = ($result | ForEach-Object { "$_" }) -join "`n"
    if ($code -ne 0 -or $output -match '(?im)^\s*(Error\b|Exception\b|java\.[A-Za-z.]*Exception|Permission Denial|Failure\b|Failed\b|adb: error:|SecurityException|cmd:.*not found|No result found|cannot connect|unable to connect)') {
        throw 'ADB a refuse la commande. Verifiez la connexion, l autorisation ADB sur la TV et la version 3.6 ou plus recente de Photos & Football. Les details sont masques pour proteger l adresse secrete.'
    }
    return $output
}

function ConvertTo-AgendaUrl64 {
    param([System.Security.SecureString]$Value)
    $pointer = [IntPtr]::Zero
    $plain = $null
    $bytes = $null
    try {
        if ($null -eq $Value -or $Value.Length -eq 0) {
            throw 'L adresse secrete Google Agenda est vide.'
        }
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        if ($plain.Length -gt 4096 -or $plain -match '[\s\\\p{Cc}]') {
            throw 'Adresse non acceptee. Collez uniquement l adresse secrete iCal de Google Agenda, sans espaces.'
        }
        $parsed = $null
        if (-not [uri]::TryCreate($plain, [UriKind]::Absolute, [ref]$parsed) -or
            $parsed.Scheme -ne 'https' -or $parsed.UserInfo.Length -gt 0 -or
            -not $parsed.IsDefaultPort -or $parsed.Query.Length -gt 0 -or $parsed.Fragment.Length -gt 0 -or
            $parsed.DnsSafeHost.ToLowerInvariant() -notin @('calendar.google.com', 'www.google.com')) {
            throw 'Adresse non acceptee. Utilisez l adresse secrete iCal HTTPS de Google Agenda, sans parametre ni fragment.'
        }
        # Check the original path too; Uri can otherwise normalize dot segments.
        $rawMatch = [regex]::Match($plain, '^https://(?<authority>[^/]+)(?<path>/.*)$', [Text.RegularExpressions.RegexOptions]::IgnoreCase)
        $path = $rawMatch.Groups['path'].Value
        $pathMatch = [regex]::Match($path, '^/calendar/ical/(?<id>[A-Za-z0-9._~%+@-]{1,1024})/private-[A-Za-z0-9_-]{1,256}/(?:basic|full)\.ics$')
        if (-not $rawMatch.Success -or
            $rawMatch.Groups['authority'].Value -notin @('calendar.google.com', 'www.google.com') -or
            -not $pathMatch.Success -or $path -match '%(?![A-Fa-f0-9]{2})' -or
            $path -match '(?i)%(?:0[0-9a-f]|1[0-9a-f]|20|25|7f|2f|5c)' -or
            [uri]::UnescapeDataString($pathMatch.Groups['id'].Value) -in @('.', '..')) {
            throw 'Adresse non acceptee. Copiez l adresse secrete au format iCal, terminee par /basic.ics ou /full.ics.'
        }
        $bytes = [Text.Encoding]::UTF8.GetBytes($plain)
        return [Convert]::ToBase64String($bytes)
    } finally {
        if ($null -ne $bytes) { [Array]::Clear($bytes, 0, $bytes.Length) }
        $plain = $null
        if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    }
}

Invoke-AgendaAdb -NoDevice -Arguments @('connect', $serial) | Out-Null
$state = Invoke-AgendaAdb -Arguments @('get-state')
if ($state.Trim() -ne 'device') {
    throw 'TV non connectee. Activez le debogage reseau et acceptez l autorisation ADB sur la TV.'
}
$operation = $Action.ToLowerInvariant()
$remoteCommand = 'content call --uri content://com.gathin.ambienttv.files --method agenda --arg ' + $operation
$url64 = $null
try {
    if ($Action -eq 'Configure') {
        if ($null -eq $SecretUrl) {
            $SecretUrl = Read-Host 'Adresse secrete Google Agenda au format iCal (saisie masquee)' -AsSecureString
        }
        $url64 = ConvertTo-AgendaUrl64 -Value $SecretUrl
        # Only base64 and validated integers reach the Android shell, never a raw
        # URL. Base64 remains a credential, briefly visible in local process args.
        $remoteCommand += ' --extra url64:s:' + $url64 + ' --extra minutes:i:' + $DefaultMinutes
    }
    $output = Invoke-AgendaAdb -Arguments @('shell', $remoteCommand)
} finally {
    $url64 = $null
    $remoteCommand = $null
    $SecretUrl = $null
}
$resultMatch = [regex]::Match($output, '(?s)Bundle\[\{result=(.*)\}\]\s*$')
if (-not $resultMatch.Success) {
    throw 'La TV ne fournit pas le resultat agenda attendu. Installez Photos & Football 3.6 ou plus recent une seule fois, puis reessayez.'
}
try { $response = $resultMatch.Groups[1].Value | ConvertFrom-Json }
catch { throw 'La TV a renvoye un resultat agenda illisible. Aucun detail prive n est affiche.' }
if ($response.success -ne $true) {
    throw 'La demande agenda a ete refusee. Verifiez l adresse secrete, la connexion et la version de l application; puis reessayez. Aucun detail prive n est affiche.'
}
if ($response.configured -isnot [bool] -or $response.enabled -isnot [bool]) {
    throw 'La TV a renvoye un etat agenda incomplet.'
}
if ([string]$response.default_minutes -cnotmatch '^(0|5|10|15|30|60)$') {
    throw 'La TV a renvoye un delai agenda non reconnu.'
}
$minutes = [int]$response.default_minutes
$configuredText = if ($response.configured) { 'oui' } else { 'non' }
$enabledText = if ($response.enabled) { 'oui' } else { 'non' }
Write-Host ('Agenda configure : ' + $configuredText)
Write-Host ('Service agenda active : ' + $enabledText)
Write-Host ('Rappel TV par defaut : ' + $minutes + ' minute(s) avant')
if ($Action -eq 'Configure' -or $Action -eq 'Enable') {
    Invoke-AgendaAdb -Arguments @('shell', 'am start -n com.gathin.ambienttv/.AgendaSettingsActivity') | Out-Null
    Write-Host 'Sur la TV, autorisez les superpositions si demande, puis activez le bouton Activer l agenda et les rappels TV.'
} elseif ($Action -eq 'Disconnect') {
    Write-Host 'L adresse et les donnees agenda locales ont ete retirees de la TV.'
} elseif ($Action -eq 'Sync') {
    Write-Host 'La synchronisation a ete demandee. Les changements Google peuvent prendre du temps a apparaitre.'
}
