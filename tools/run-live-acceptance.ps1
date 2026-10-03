#Requires -Version 5.1
<#
.SYNOPSIS
Runs the full Fake/None service and emulator acceptance matrix on Windows.
.DESCRIPTION
Start an Android emulator first. Detects local tools, builds an isolated test server,
runs acceptance.py --live --serial, and stops only the server started by this script.
No publication, file deletion, emulator creation or production database access.
.EXAMPLE
.\tools\run-live-acceptance.ps1
.EXAMPLE
.\tools\run-live-acceptance.ps1 -Serial emulator-5554 -CheckOnly
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$DotnetPath = $env:DOTNET_HOST_PATH,
    [string]$Python = 'python',
    [string]$Version = '0.1.0-SNAPSHOT',
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$serverRoot = Join-Path (Split-Path $root -Parent) 'featbit/modules/evaluation-server'

function Require-File([string]$Path, [string]$Hint) {
    if (-not $Path -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Hint Missing file: $Path"
    }
}
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}
function Assert-FreeServicePort {
    if (Get-NetTCPConnection -LocalPort 5189 -State Listen -ErrorAction SilentlyContinue) {
        throw 'Port 5189 is already in use. Stop the existing service yourself before running acceptance.'
    }
}
function Restore-EnvironmentVariable([string]$Name, $Value) {
    # PowerShell can coerce a null argument to an empty string for the .NET API.
    # Preserve the difference between an absent variable and a present empty one.
    if ($null -eq $Value) { Remove-Item -LiteralPath "Env:$Name" -ErrorAction SilentlyContinue }
    else { [Environment]::SetEnvironmentVariable($Name, $Value, 'Process') }
}

if ($Version -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?$') { throw 'Invalid artifact version.' }
if (-not $JavaHome) {
    $JavaHome = Get-ChildItem -Path "$env:ProgramFiles/Microsoft/jdk-17*", "$env:ProgramFiles/Eclipse Adoptium/jdk-17*" -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $JavaHome) { throw 'JDK 17 was not found. Supply -JavaHome or set JAVA_HOME.' }
$java = Join-Path $JavaHome 'bin/java.exe'
Require-File $java 'Install JDK 17 or supply -JavaHome.'
# Windows PowerShell 5.1 turns redirected native stderr into ErrorRecords.
# java -version writes normal output there; use its exit code to detect failure.
try {
    $ErrorActionPreference = 'Continue'
    $javaVersion = (& $java -version 2>&1 | Out-String)
}
finally { $ErrorActionPreference = 'Stop' }
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "17\.') { throw 'This matrix requires JDK 17.' }
if (-not $AndroidHome) { $AndroidHome = $env:ANDROID_SDK_ROOT }
if (-not $AndroidHome) { $AndroidHome = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adb = Join-Path $AndroidHome 'platform-tools/adb.exe'
Require-File $adb 'Install Android SDK platform-tools or supply -AndroidHome.'
foreach ($package in @('platforms/android-34/android.jar', 'build-tools/34.0.0/apksigner.bat', 'build-tools/35.0.0/aapt2.exe')) {
    Require-File (Join-Path $AndroidHome $package) 'Install platform 34 and build-tools 34.0.0/35.0.0 in Android Studio.'
}
Require-File (Join-Path $HOME '.android/debug.keystore') 'Build and run an Android Studio Debug app first to create the local test key.'
if (-not $DotnetPath) {
    $localDotnet = Join-Path $HOME '.dotnet/dotnet.exe'
    if (Test-Path -LiteralPath $localDotnet) { $DotnetPath = $localDotnet }
    else { $DotnetPath = (Get-Command dotnet -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source }
}
Require-File $DotnetPath 'Install .NET SDK 10 or supply -DotnetPath.'
$dotnetSdks = & $DotnetPath --list-sdks
if ($LASTEXITCODE -ne 0 -or -not ($dotnetSdks -match '^10\.')) { throw '.NET SDK 10 is required.' }
$pythonCommand = (Get-Command $Python -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
Invoke-Checked $pythonCommand @('-c', "import sys; assert sys.version_info >= (3, 8), 'Python 3.8+ required'")
Require-File (Join-Path $serverRoot 'src/Api/Api.csproj') 'The adjacent featbit checkout is required for live and Domain checks.'

$devices = & $adb devices
if ($LASTEXITCODE -ne 0) { throw 'Unable to list Android devices.' }
$ready = @($devices | ForEach-Object { if ($_ -match '^(emulator-\d+)\s+device\s*$') { $Matches[1] } })
if (-not $Serial) {
    if ($ready.Count -ne 1) { throw 'Start one emulator in Android Studio, or select a running emulator with -Serial emulator-5554.' }
    $Serial = $ready[0]
}
if ($Serial -notmatch '^emulator-\d+$' -or $Serial -notin $ready) { throw 'The selected emulator is not connected and ready.' }
$booted = & $adb -s $Serial shell getprop sys.boot_completed
if ($LASTEXITCODE -ne 0 -or "$booted".Trim() -ne '1') { throw 'Wait for the emulator to finish booting.' }
Assert-FreeServicePort

Write-Host "JDK: $JavaHome"
Write-Host "Android SDK: $AndroidHome"
Write-Host ".NET: $DotnetPath"
Write-Host "Emulator: $Serial"
Write-Host "Artifact version: $Version"
Write-Host 'The full matrix will install test APKs and temporarily change emulator network/idle settings.'
if ($CheckOnly) { Write-Host 'Preflight passed. No server was started and no matrix was run.'; return }

$run = Join-Path $root ('build/live-acceptance/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
$null = New-Item -ItemType Directory -Path $run
$serverOutput = Join-Path $run 'server'
$savedEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'ANDROID_HOME', 'DOTNET_HOST_PATH', 'DbProvider', 'MqProvider', 'CacheProvider', 'ASPNETCORE_URLS')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$server = $null
Push-Location $root
try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidHome
    $env:DOTNET_HOST_PATH = $DotnetPath
    Write-Host "Launcher logs: $run"
    Invoke-Checked git @('rev-parse', 'HEAD')
    Invoke-Checked git @('status', '--short')
    Write-Host 'Building the Fake/None evaluation server...'
    try {
        $ErrorActionPreference = 'Continue'
        & $DotnetPath build (Join-Path $serverRoot 'src/Api/Api.csproj') -c Debug -o $serverOutput --nologo *> (Join-Path $run 'server-build.log')
    }
    finally { $ErrorActionPreference = 'Stop' }
    if ($LASTEXITCODE -ne 0) { throw "Server build failed; see $run/server-build.log" }
    Assert-FreeServicePort
    $env:DbProvider = 'Fake'
    $env:MqProvider = 'None'
    $env:CacheProvider = 'None'
    $env:ASPNETCORE_URLS = 'http://127.0.0.1:5189'
    $server = Start-Process -FilePath $DotnetPath -ArgumentList ('"' + (Join-Path $serverOutput 'Api.dll') + '"') `
        -WorkingDirectory (Join-Path $serverRoot 'src/Api') -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $run 'server-stdout.log') -RedirectStandardError (Join-Path $run 'server-stderr.log')
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        if ($server.HasExited) { throw "Test server exited before readiness; see $run/server-stdout.log and server-stderr.log" }
        $listener = Get-NetTCPConnection -LocalPort 5189 -State Listen -ErrorAction SilentlyContinue
        if ($listener) {
            if (@($listener.OwningProcess | Where-Object { $_ -ne $server.Id }).Count) { throw 'Port 5189 was claimed by another process.' }
            break
        }
        if ([DateTime]::UtcNow -ge $deadline) { throw "Test server did not listen within 60 seconds; see $run/server-stdout.log" }
        Start-Sleep -Milliseconds 250
    } while ($true)
    # The Fake/None values belong only to the test service, not the acceptance children.
    foreach ($name in @('DbProvider', 'MqProvider', 'CacheProvider', 'ASPNETCORE_URLS')) {
        Restore-EnvironmentVariable $name $savedEnvironment[$name]
    }
    Write-Host 'Running the complete live and emulator matrix...'
    try {
        # Retain native stderr in the log without aborting its stream on PowerShell 5.1.
        # The exit code below remains authoritative for acceptance failure.
        $ErrorActionPreference = 'Continue'
        & $pythonCommand -u -B (Join-Path $PSScriptRoot 'acceptance.py') --live --serial $Serial --version $Version 2>&1 |
            Tee-Object -FilePath (Join-Path $run 'acceptance-console.log')
    }
    finally { $ErrorActionPreference = 'Stop' }
    if ($LASTEXITCODE -ne 0) { throw "Acceptance failed; see $run/acceptance-console.log and the reported evidence directory." }
    Write-Host 'Full live and emulator acceptance passed. Evidence directory is printed above.'
}
finally {
    try {
        if ($null -ne $server -and -not $server.HasExited) {
            $server.Kill()
            if (-not $server.WaitForExit(10000)) { throw 'The test server did not stop within 10 seconds.' }
            Write-Host 'Stopped the test server started by this script.'
        }
    }
    finally {
        foreach ($name in $savedEnvironment.Keys) {
            Restore-EnvironmentVariable $name $savedEnvironment[$name]
        }
        Pop-Location
    }
}
