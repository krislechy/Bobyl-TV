param([string]$Serial = 'emulator-5556')
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'This generator is restricted to a local emulator.' }
$repoRoot = Split-Path $PSScriptRoot -Parent
$toolsRoot = if ($env:BOBYL_TOOLS_ROOT) { $env:BOBYL_TOOLS_ROOT } else { Join-Path (Split-Path -Parent $repoRoot) '.tools' }
$adb = Join-Path $toolsRoot 'platform-tools/adb.exe'
$deviceLock = [IO.File]::Open((Join-Path $toolsRoot 'locks/bobyl-build-device.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$previousJava = $env:JAVA_HOME
$previousTemp = $env:TEMP
$previousTmp = $env:TMP
Push-Location $repoRoot
try {
    & $adb -s $Serial get-state
    if ($LASTEXITCODE -ne 0) { throw 'Profile emulator unavailable' }
    $api = (& $adb -s $Serial shell getprop ro.build.version.sdk).Trim()
    if ([int]$api -lt 33) { throw 'Use the dedicated API 34 emulator; API 31 requires root.' }
    $env:JAVA_HOME = Join-Path $toolsRoot 'android-dev/jdk-17'
    $env:TEMP = $null
    $env:TMP = $null
    & ./gradlew.bat --no-daemon :app:assembleProfile :macrobenchmark:assembleProfile
    if ($LASTEXITCODE -ne 0) { throw 'Profile build failed' }
    & $adb -s $Serial install -r app/build/outputs/apk/profile/app-profile.apk
    if ($LASTEXITCODE -ne 0) { throw 'Profile app install failed' }
    & $adb -s $Serial install -r macrobenchmark/build/outputs/apk/profile/macrobenchmark-profile.apk
    if ($LASTEXITCODE -ne 0) { throw 'Profile runner install failed' }
    $output = Join-Path $repoRoot ('captures/profile-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    [void][IO.Directory]::CreateDirectory($output)
    $remote = '/sdcard/Android/media/com.bobyl.tv.macrobenchmark/' + (Split-Path $output -Leaf)
    git rev-parse HEAD | Out-File (Join-Path $output 'commit.txt')
    Get-FileHash app/build/outputs/apk/profile/app-profile.apk | Format-List | Out-File (Join-Path $output 'apk-sha256.txt')
    & $adb -s $Serial shell am instrument -w -r -e class com.bobyl.tv.macrobenchmark.BaselineProfileGenerator -e androidx.benchmark.enabledRules BaselineProfile -e additionalTestOutputDir $remote com.bobyl.tv.macrobenchmark/androidx.test.runner.AndroidJUnitRunner 2>&1 | Tee-Object -FilePath (Join-Path $output 'instrumentation.txt')
    $instrumentExit = $LASTEXITCODE
    & $adb -s $Serial pull $remote (Join-Path $output 'profiles')
    Write-Output "Artifacts: $output"
    $result = Get-Content (Join-Path $output 'instrumentation.txt') -Raw
    if ($instrumentExit -ne 0 -or $result -notmatch 'OK \(\d+ tests?\)') { throw 'Profile generation failed' }
    $profiles = @(Get-ChildItem (Join-Path $output 'profiles') -Recurse -Filter '*baseline-prof.txt')
    if ($profiles.Count -eq 0) { throw 'No generated profile found' }
    # Review and validate generated rules before copying them into app/src/main.
    $profiles | Select-Object FullName, Length
}
finally {
    Pop-Location
    $env:JAVA_HOME = $previousJava
    $env:TEMP = $previousTemp
    $env:TMP = $previousTmp
    $deviceLock.Dispose()
}
