param(
    [Parameter(Mandatory = $true)][string]$NotesPath,
    [Parameter(Mandatory = $true)][string]$CommitMessage
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = if ($env:BOBYL_TOOLS_ROOT) { $env:BOBYL_TOOLS_ROOT } else { Join-Path (Split-Path -Parent $repoRoot) '.tools' }
$stage = 'Prepare'
$resultCode = 0
Push-Location -LiteralPath $repoRoot
try {
    if (!(Test-Path -LiteralPath $NotesPath -PathType Leaf)) { throw 'Release notes file is required' }
    $notesAbsolute = (Resolve-Path -LiteralPath $NotesPath).Path
    $remote = (git remote get-url origin).Trim()
    if ($LASTEXITCODE -ne 0 -or $remote -notmatch '^(https://github\.com/|git@github\.com:)krislechy/Bobyl-TV(?:\.git)?$') { throw 'Unexpected GitHub repository' }
    if ((git branch --show-current).Trim() -ne 'main') { throw 'Publication requires main branch' }
    $scannerCommand = Get-Command gitleaks -ErrorAction SilentlyContinue | Select-Object -First 1
    $scanner = if ($scannerCommand) { $scannerCommand.Source } else { Join-Path $toolsRoot 'privacy\gitleaks.exe' }
    if (!(Test-Path -LiteralPath $scanner -PathType Leaf)) { throw 'Install Gitleaks before publishing' }
    $stage = 'Commit'
    if (@(git status --porcelain).Count) {
        $accountJson = & gh api user --jq '{login:.login,id:.id}'
        if ($LASTEXITCODE -ne 0) { throw 'GitHub authentication required' }
        $account = $accountJson | ConvertFrom-Json
        $publicEmail = "$($account.id)+$($account.login)@users.noreply.github.com"
        git add --all
        if ($LASTEXITCODE -ne 0) { throw 'Staging failed' }
        git -c "user.name=$($account.login)" -c "user.email=$publicEmail" commit -m $CommitMessage
        if ($LASTEXITCODE -ne 0) { throw 'Commit failed' }
    }
    $stage = 'Privacy'
    [void][IO.Directory]::CreateDirectory((Join-Path $repoRoot 'captures'))
    $ErrorActionPreference = 'Continue'
    & $scanner git . --redact=100 --log-opts='--all' --no-banner *> .\captures\publish-privacy.log
    $scanExitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($scanExitCode -ne 0) { throw 'Secret scan blocked publication; see captures/publish-privacy.log' }
    $files = @(git ls-files)
    if (@($files | Where-Object { $_ -match '(?i)(\.apk$|\.jks$|\.keystore$|^reference/original-apk/|^captures/|^\.serena/memories/|\.local\.(json|ps1)$|(^|/)\.env($|\.))' }).Count) {
        throw 'Private or binary artifacts are tracked; publication blocked'
    }
    $emails = @(git log --all --format='%ae%n%ce')
    if (@($emails | Where-Object { $_ -notmatch '@users\.noreply\.github\.com$' }).Count) { throw 'Personal commit metadata blocks publication' }
    $privatePattern = '([A-Za-z]:[\\/](Users|My)[\\/]|192[.]168[.][0-9]+[.][0-9]+|[[:alnum:]._%+-]+@(gmail|mail|yandex|outlook|hotmail)[.][[:alpha:]]{2,})'
    $privateFiles = @(git grep -I -l -E $privatePattern HEAD -- '*.md' '*.ps1' '*.kt' '*.kts' '*.json' '*.yml' '*.xml' '*.properties')
    if ($LASTEXITCODE -gt 1) { throw 'Personal data scan failed' }
    if ($privateFiles.Count) { throw 'Personal information in tracked files blocks publication' }
    $stage = 'Push'
    git push origin main
    if ($LASTEXITCODE -ne 0) { throw 'Push failed; no release was published' }
    $stage = 'Release'
    & "$PSScriptRoot\PublishProdGitHub.ps1" -Publish -NotesPath $notesAbsolute
    if ($LASTEXITCODE -ne 0) { throw 'Release publication failed; see the production publication log' }
} catch {
    $resultCode = 1
    Write-Output "${stage} failed: $($_.Exception.Message)"
} finally { Pop-Location }
exit $resultCode
