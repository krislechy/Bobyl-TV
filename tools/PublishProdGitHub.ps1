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
    $stage = 'Validate APK'
    $buildToolsDirectory = Join-Path $toolsRoot 'android-dev\sdk\build-tools'
    $buildTools = Get-ChildItem -LiteralPath $buildToolsDirectory -Directory |
        Where-Object { (Test-Path -LiteralPath (Join-Path $_.FullName 'aapt.exe') -PathType Leaf) -and
            (Test-Path -LiteralPath (Join-Path $_.FullName 'apksigner.bat') -PathType Leaf) } |
        Sort-Object { [version]($_.Name -replace '-.*$', '') } -Descending |
        Select-Object -First 1
    if (!$buildTools) { throw 'Android APK validation tools are required before publication' }
    $ErrorActionPreference = 'Continue'
    $apkBadging = @(& (Join-Path $buildTools.FullName 'aapt.exe') dump badging $apkPath 2>&1)
    $badgingExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($badgingExit -ne 0) { throw 'Production APK manifest validation failed' }
    $packageLine = @($apkBadging | Where-Object { "$_" -match '^package:' })
    if ($packageLine.Count -ne 1) { throw 'Expected one production package declaration' }
    $packageMatch = [regex]::Match("$($packageLine[0])",
        "^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'")
    if (!$packageMatch.Success -or $packageMatch.Groups[1].Value -cne 'com.bobyl.tv' -or
        [long]$packageMatch.Groups[2].Value -ne [long]$element.versionCode -or
        $packageMatch.Groups[3].Value -cne $element.versionName -or
        @($apkBadging | Where-Object { "$_" -match '^application-debuggable(?:\s|$)' }).Count) {
        throw 'Production APK package, version or non-debuggable requirement failed'
    }
    $ErrorActionPreference = 'Continue'
    $apkSignatures = @(& (Join-Path $buildTools.FullName 'apksigner.bat') verify --print-certs $apkPath 2>&1)
    $signatureExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($signatureExit -ne 0) { throw 'Production APK signature verification failed' }
    $signerDigests = @($apkSignatures | ForEach-Object {
        $digestMatch = [regex]::Match("$_", '^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$')
        if ($digestMatch.Success) { $digestMatch.Groups[1].Value.ToLowerInvariant() }
    })
    $expectedCertificate = '6c1be0e85a78335b98228d4339d0b3df228ce529d5b99a7062f9cf9109b2b02a'
    if ($signerDigests.Count -ne 1 -or $signerDigests[0] -cne $expectedCertificate) {
        throw 'Production APK certificate does not match the existing release certificate'
    }
    Write-Output "Verified production APK: com.bobyl.tv $($element.versionName) ($($element.versionCode)), non-debuggable, certificate $expectedCertificate"
    $stage = 'Package'
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
