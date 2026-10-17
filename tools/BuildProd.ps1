param([ValidateSet('Tv', 'Emulator')][string]$Target = 'Tv')
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = if ($env:BOBYL_TOOLS_ROOT) { $env:BOBYL_TOOLS_ROOT } else { Join-Path (Split-Path -Parent $repoRoot) '.tools' }
$lockDirectory = Join-Path $toolsRoot 'locks'
$logPath = Join-Path $repoRoot 'captures\build-prod.log'
if ($Target -eq 'Emulator') { $logPath = Join-Path $repoRoot 'captures\build-prod-emulator.log' }
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
    Write-Host 'Building production...'
    # Native stderr is build output; use the process exit code to detect failure.
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat --no-daemon --console=plain :app:assembleTvRelease *> $logPath
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw "Gradle exit code: $LASTEXITCODE" }
    $adbPath = Join-Path $toolsRoot 'platform-tools\adb.exe'
    if ($Target -eq 'Tv') {
    $stage = 'Connect'
    # ADB startup messages on stderr are not an installation failure.
    $ErrorActionPreference = 'Continue'
    & $adbPath connect $deviceSerial >> $logPath 2>&1
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw "ADB connect exit code: $LASTEXITCODE" }
    }
    $stage = 'Install'
    Write-Host "Installing production on $deviceSerial..."
    $ErrorActionPreference = 'Continue'
    & $adbPath -s $deviceSerial install -r `
        (Join-Path $repoRoot 'app\build\outputs\apk\tvRelease\app-tvRelease.apk') >> $logPath 2>&1
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw "ADB exit code: $LASTEXITCODE" }
    Write-Host "Production installed. Log: $logPath"
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
