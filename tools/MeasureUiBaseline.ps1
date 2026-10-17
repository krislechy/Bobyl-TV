param(
    [string]$TestClass = 'com.bobyl.tv.macrobenchmark.StartupBenchmark',
    [string]$Serial = 'emulator-5554',
    [ValidateSet('None', 'Partial')][string]$CompilationMode = 'None',
    [switch]$CompareProfiles
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$toolsRoot = if ($env:BOBYL_TOOLS_ROOT) { $env:BOBYL_TOOLS_ROOT } else { Join-Path (Split-Path -Parent $repoRoot) '.tools' }
$adb = Join-Path $toolsRoot 'platform-tools\adb.exe'
$lockDirectory = Join-Path $toolsRoot 'locks'
[void][IO.Directory]::CreateDirectory($lockDirectory)
$deviceLock = [IO.File]::Open((Join-Path $lockDirectory 'bobyl-build-device.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$previousJava = $env:JAVA_HOME
$previousTemp = $env:TEMP
$previousTmp = $env:TMP
Push-Location $repoRoot
try {
    $env:JAVA_HOME = Join-Path $toolsRoot 'android-dev\jdk-17'
    $env:TEMP = $null
    $env:TMP = $null
    $output = Join-Path $repoRoot ('captures\ui-baseline-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    [void][IO.Directory]::CreateDirectory($output)
    Write-Output "Artifacts: $output"
    & .\gradlew.bat --no-daemon :app:assembleBenchmark :macrobenchmark:assembleBenchmark *> (Join-Path $output 'build.txt')
    if ($LASTEXITCODE -ne 0) {
        Get-Content (Join-Path $output 'build.txt') -Tail 35
        throw 'Benchmark build failed'
    }
    Write-Output 'Build: PASS'
    $remoteOutput = '/sdcard/Android/media/com.bobyl.tv.macrobenchmark/' + (Split-Path $output -Leaf)
    git rev-parse HEAD | Out-File (Join-Path $output 'commit.txt') -Encoding utf8
    git diff --binary 2> (Join-Path $output 'git-diff-warnings.txt') | Out-File (Join-Path $output 'working-tree.patch') -Encoding utf8
    Get-FileHash 'app\build\outputs\apk\benchmark\app-benchmark.apk' | Format-List | Out-File (Join-Path $output 'apk-sha256.txt')
    Get-FileHash 'macrobenchmark\build\outputs\apk\benchmark\macrobenchmark-benchmark.apk' | Format-List | Out-File (Join-Path $output 'test-apk-sha256.txt')
    if ($Serial -notmatch '^emulator-\d+$') {
        & $adb connect $Serial
        if ($LASTEXITCODE -ne 0) { throw 'ADB connection failed' }
    }
    & $adb -s $Serial get-state
    if ($LASTEXITCODE -ne 0) { throw 'Device unavailable' }
    & $adb -s $Serial shell getprop ro.product.model | Out-File (Join-Path $output 'device.txt')
    & $adb -s $Serial shell getprop ro.build.version.release | Out-File (Join-Path $output 'android.txt')
    & $adb -s $Serial shell dumpsys display | Out-File (Join-Path $output 'display.txt')
    & $adb -s $Serial shell dumpsys thermalservice | Out-File (Join-Path $output 'thermal-before.txt')
    & $adb -s $Serial install -r 'app\build\outputs\apk\benchmark\app-benchmark.apk'
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark app installation failed' }
    & $adb -s $Serial install -r 'macrobenchmark\build\outputs\apk\benchmark\macrobenchmark-benchmark.apk'
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark test installation failed' }
    $benchmarkArgs = @()
    if ($Serial -match '^emulator-\d+$') {
        $benchmarkArgs = @('-e', 'androidx.benchmark.suppressErrors', 'EMULATOR')
        'Emulator diagnostics only; not representative of EON performance.' | Out-File (Join-Path $output 'emulator-only.txt')
    }
    # ABBA order reduces a simple first/last-run bias. Each batch has its own artifacts.
    $modes = if ($CompareProfiles) { @('None', 'Partial', 'Partial', 'None') } else { @($CompilationMode) }
    for ($batch = 0; $batch -lt $modes.Count; $batch++) {
        $mode = $modes[$batch]
        $name = '{0:D2}-{1}' -f $batch, $mode
        $batchOutput = Join-Path $output $name
        [void][IO.Directory]::CreateDirectory($batchOutput)
        Write-Output "Batch $name started ($TestClass)"
        & $adb -s $Serial shell am instrument -w -r -e class $TestClass -e bobyl.compilation $mode -e additionalTestOutputDir "$remoteOutput/$name" @benchmarkArgs com.bobyl.tv.macrobenchmark/androidx.test.runner.AndroidJUnitRunner *> (Join-Path $batchOutput 'instrumentation.txt')
        $instrumentExit = $LASTEXITCODE
        $result = Get-Content (Join-Path $batchOutput 'instrumentation.txt') -Raw
        & $adb -s $Serial pull "$remoteOutput/$name" (Join-Path $batchOutput 'baseline') *> (Join-Path $batchOutput 'pull.txt')
        if ($instrumentExit -ne 0 -or $result -notmatch 'OK \(\d+ tests?\)') {
            Get-Content (Join-Path $batchOutput 'instrumentation.txt') -Tail 35
            throw "Batch $name failed; see instrumentation.txt"
        }
        if ($LASTEXITCODE -ne 0) { throw "Batch $name artifacts could not be pulled" }
        $metrics = $result -split "`n" | Where-Object { $_ -match '^\s*(timeToInitialDisplayMs|frameDurationCpuMs|frameOverrunMs)\s' } | ForEach-Object { ($_ -replace '\]\(file://[^)]*\)', '' -replace '\[', '').Trim() } | Select-Object -Unique
        Write-Output "Batch ${name}: PASS"
        $metrics
        $metrics | Out-File (Join-Path $batchOutput 'summary.txt')
    }
    & $adb -s $Serial shell am start -W -n com.bobyl.tv.benchmark/com.bobyl.tv.MainActivity | Out-File (Join-Path $output 'memory-launch.txt')
    Start-Sleep -Seconds 15
    & $adb -s $Serial shell dumpsys meminfo com.bobyl.tv.benchmark | Out-File (Join-Path $output 'memory-after.txt')
    & $adb -s $Serial shell dumpsys thermalservice | Out-File (Join-Path $output 'thermal-after.txt')
    Write-Output "Artifacts: $output"
}
finally {
    Pop-Location
    $env:JAVA_HOME = $previousJava
    $env:TEMP = $previousTemp
    $env:TMP = $previousTmp
    $deviceLock.Dispose()
}
