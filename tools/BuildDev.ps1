param(
    [ValidateSet('Emulator', 'Tv')][string]$Target = 'Emulator',
    [switch]$Performance,
    [switch]$DebugTv
)
$ErrorActionPreference = 'Stop'
if ($DebugTv -and $Target -ne 'Tv') {
    throw '-DebugTv requires -Target Tv; no build or installation was started'
}
if ($DebugTv -and $Performance) {
    throw '-DebugTv cannot be combined with -Performance; no build or installation was started'
}
# The TV always receives optimized Dev unless ordinary debug was explicitly requested.
$optimizedDev = $Performance.IsPresent -or ($Target -eq 'Tv' -and -not $DebugTv.IsPresent)
$devMode = if ($optimizedDev) { 'optimized (non-debuggable, R8, resource shrinking)' } else { 'debug (debuggable, no R8 or resource shrinking)' }
Write-Host "Dev target: $Target; mode: $devMode"
$repoRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = if ($env:BOBYL_TOOLS_ROOT) { $env:BOBYL_TOOLS_ROOT } else { Join-Path (Split-Path -Parent $repoRoot) '.tools' }
$lockDirectory = Join-Path $toolsRoot 'locks'
$logPath = Join-Path $repoRoot 'captures\build-dev.log'
if ($Target -eq 'Tv') { $logPath = Join-Path $repoRoot 'captures\build-dev-tv.log' }
if ($Performance -and $Target -eq 'Emulator') { $logPath = Join-Path $repoRoot 'captures\build-dev-performance.log' }
$localConfigPath = Join-Path $toolsRoot 'bobyl.local.json'
$localConfig = if (Test-Path -LiteralPath $localConfigPath) { Get-Content -Raw -LiteralPath $localConfigPath | ConvertFrom-Json } else { $null }
$deviceSerial = if ($Target -eq 'Tv') { if ($env:BOBYL_TV_ADB) { $env:BOBYL_TV_ADB } else { $localConfig.tvAdb } } else { 'emulator-5554' }
if (!$deviceSerial) { throw 'Set BOBYL_TV_ADB or tvAdb in the private bobyl.local.json file' }
[void][IO.Directory]::CreateDirectory($lockDirectory)
[void][IO.Directory]::CreateDirectory((Split-Path -Parent $logPath))
$buildDeviceLock = $null
$previousJavaHome = $env:JAVA_HOME
$previousTemp = $env:TEMP
$previousTmp = $env:TMP
$locationPushed = $false
$stage = 'Lock'
$resultCode = 0
try {
    $buildDeviceLock = [IO.File]::Open(
        (Join-Path $lockDirectory 'bobyl-build-device.lock'),
        [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    Push-Location -LiteralPath $repoRoot
    $locationPushed = $true
    $env:JAVA_HOME = Join-Path $toolsRoot 'android-dev\jdk-17'
    $env:TEMP = $null
    $env:TMP = $null
    $stage = 'Build'
    $performanceValue = $optimizedDev.ToString().ToLowerInvariant()
    Write-Host "Building Dev for $Target in $devMode mode..."
    # Always pass the chosen value explicitly; never inherit a previous build's mode.
    $gradleArguments = @('--no-daemon', '--console=plain', ':app:assembleDebug', "-Pbobyl.devPerformance=$performanceValue")
    # Native stderr is build output; use the process exit code to detect failure.
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat @gradleArguments *> $logPath
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw "Gradle exit code: $LASTEXITCODE" }
    $adbPath = Join-Path $toolsRoot 'platform-tools\adb.exe'
    if ($Target -eq 'Tv') {
        $stage = 'Connect'
        $ErrorActionPreference = 'Continue'
        & $adbPath connect $deviceSerial >> $logPath 2>&1
        $ErrorActionPreference = 'Stop'
        if ($LASTEXITCODE -ne 0) { throw "ADB connect exit code: $LASTEXITCODE" }
    }
    $stage = 'Install'
    Write-Host "Installing Dev on $Target ($devMode): $deviceSerial..."
    # Windows PowerShell can treat ADB startup messages on stderr as terminating errors.
    $ErrorActionPreference = 'Continue'
    & $adbPath -s $deviceSerial install -r `
        (Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk') >> $logPath 2>&1
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw "ADB exit code: $LASTEXITCODE" }
    Write-Host "Dev installed on $Target in $devMode mode. Log: $logPath"
}
catch {
    $resultCode = 1
    Write-Host "${stage} failed: $($_.Exception.Message)"
    Write-Host "Build/install log: $logPath"
}
finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:TEMP = $previousTemp
    $env:TMP = $previousTmp
    if ($locationPushed) { Pop-Location }
    if ($null -ne $buildDeviceLock) { $buildDeviceLock.Dispose() }
}
exit $resultCode
