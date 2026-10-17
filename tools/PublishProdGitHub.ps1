param([switch]$Publish, [string]$NotesPath = '')
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = if ($env:BOBYL_TOOLS_ROOT) { $env:BOBYL_TOOLS_ROOT } else { Join-Path (Split-Path -Parent $repoRoot) '.tools' }
$repository = 'krislechy/Bobyl-TV'
$outputDirectory = Join-Path $repoRoot 'captures\github-production'
$logPath = Join-Path $repoRoot 'captures\publish-prod-github.log'
$buildLock = $null
$previousJava = $env:JAVA_HOME
$previousTemp = $env:TEMP
$previousTmp = $env:TMP
$stage = 'Prepare'
$resultCode = 0
[void][IO.Directory]::CreateDirectory($outputDirectory)
Push-Location -LiteralPath $repoRoot
try {
    if ($Publish) {
        if (!$NotesPath -or !(Test-Path -LiteralPath $NotesPath -PathType Leaf)) { throw 'Release notes file required for publication' }
        if (@(git status --porcelain).Count -ne 0) { throw 'Commit and push changes before publication' }
        $commit = (git rev-parse HEAD).Trim()
        $remoteCommit = ((git ls-remote origin refs/heads/main) -split '\s+')[0]
        if ($LASTEXITCODE -ne 0 -or $commit -ne $remoteCommit) { throw 'HEAD must match the pushed main branch' }
    }
    $stage = 'Lock'
    [void][IO.Directory]::CreateDirectory((Join-Path $toolsRoot 'locks'))
    $buildLock = [IO.File]::Open((Join-Path $toolsRoot 'locks\bobyl-build-device.lock'),
        [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $env:JAVA_HOME = Join-Path $toolsRoot 'android-dev\jdk-17'
    $env:TEMP = $null
    $env:TMP = $null
    $stage = 'Build'
    Write-Output 'Building production APK for GitHub; no device installation...'
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat --no-daemon --console=plain :app:assembleTvRelease *> $logPath
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw "Gradle exit code: $LASTEXITCODE" }
    $stage = 'Package'
    $apkDirectory = Join-Path $repoRoot 'app\build\outputs\apk\tvRelease'
    $metadata = Get-Content -Raw -LiteralPath (Join-Path $apkDirectory 'output-metadata.json') | ConvertFrom-Json
    if ($metadata.applicationId -ne 'com.bobyl.tv' -or @($metadata.elements).Count -ne 1) { throw 'Expected a single production APK' }
    $element = $metadata.elements[0]
    $tag = "v$($element.versionName)"
    $apkName = "bobyl-tv-production-$tag.apk"
    $apkPath = Join-Path $outputDirectory $apkName
    Copy-Item -LiteralPath (Join-Path $apkDirectory $element.outputFile) -Destination $apkPath
    $manifestPath = Join-Path $outputDirectory 'bobyl-tv-update.json'
    $manifest = [ordered]@{ packageName = 'com.bobyl.tv'; versionCode = [long]$element.versionCode;
        versionName = $element.versionName; apk = $apkName;
        sha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant() }
    [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    $buildLock.Dispose()
    $buildLock = $null
    Write-Output "Prepared: $apkPath"
    if ($Publish) {
        $stage = 'Publish draft'
        & gh release create $tag $apkPath $manifestPath --repo $repository --target $commit --title "Bobyl TV $($element.versionName)" --notes-file $NotesPath --draft
        if ($LASTEXITCODE -ne 0) { throw 'Draft creation/upload failed; no automatic retry' }
        $stage = 'Publish release'
        & gh release edit $tag --repo $repository --draft=false --latest
        if ($LASTEXITCODE -ne 0) { throw 'Release remains draft; publication failed' }
        Write-Output "Published: https://github.com/$repository/releases/tag/$tag"
    }
} catch {
    $resultCode = 1
    Write-Output "${stage} failed: $($_.Exception.Message)"
    Write-Output "Log: $logPath"
} finally {
    if ($null -ne $buildLock) { $buildLock.Dispose() }
    $env:JAVA_HOME = $previousJava
    $env:TEMP = $previousTemp
    $env:TMP = $previousTmp
    Pop-Location
}
exit $resultCode
