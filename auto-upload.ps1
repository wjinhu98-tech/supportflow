# auto-upload.ps1
# Safely stage, commit and push the current working tree to git.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File auto-upload.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File auto-upload.ps1 -Message "feat: my change"
#   powershell -NoProfile -ExecutionPolicy Bypass -File auto-upload.ps1 -Force   (skip secret scan)

param(
    [string]$Message = "",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

function Fail([string]$msg) {
    Write-Host "[ABORT] $msg" -ForegroundColor Red
    exit 1
}

Write-Host "== Auto upload repo: $PSScriptRoot ==" -ForegroundColor Cyan

# ---------------------------------------------------------------
# [1/5] Sensitive files must never enter the repository
# ---------------------------------------------------------------
$sensitivePattern = '(^|/)(\.local|httpRequests)(/|$)|http-client\.private\.env|dataSources\.local|workspace\.xml|secrets\.yml|day4-login'

Write-Host "[1/5] Checking for sensitive files..." -ForegroundColor Cyan

$trackedHits = git ls-files | Select-String -Pattern $sensitivePattern
if ($trackedHits) {
    Fail @"
Sensitive files are tracked by git:
$($trackedHits -join "`n")

Remove them from tracking first:
  git rm -r --cached --ignore-unmatch <path>
"@
}

$pendingHits = git ls-files --others --exclude-standard | Select-String -Pattern $sensitivePattern
if ($pendingHits) {
    Fail @"
Sensitive files are not ignored by .gitignore and would be committed:
$($pendingHits -join "`n")

Add them to .gitignore first.
"@
}

# ---------------------------------------------------------------
# [2/5] Stage all changes
# ---------------------------------------------------------------
Write-Host "[2/5] Staging all changes..." -ForegroundColor Cyan
git add -A
if ($LASTEXITCODE -ne 0) { Fail "git add failed (exit $LASTEXITCODE)" }

# ---------------------------------------------------------------
# [3/5] Scan staged diff for hardcoded secrets
# ---------------------------------------------------------------
if ($Force) {
    Write-Host "[3/5] Secret scan skipped (-Force)." -ForegroundColor Yellow
} else {
    Write-Host "[3/5] Scanning staged content for hardcoded secrets..." -ForegroundColor Cyan
    $secretHits = git diff --cached |
        Select-String -Pattern '^\+.*(password|passwd|secret|api[_-]?key|access[_-]?key|private[_-]?key|token)\s*[:=]' |
        Where-Object {
            $_.Line -notmatch '\$\{' -and
            $_.Line -notmatch '[:=]\s*\{\{' -and
            $_.Line -notmatch '[:=]\s*["'' ]?\s*$'
        }
    if ($secretHits) {
        Fail @"
Possible hardcoded secret found in staged changes:
$($secretHits -join "`n")

Use environment variables or placeholders instead.
If this is a false positive, re-run with -Force.
"@
    }
}

# ---------------------------------------------------------------
# [4/5] Commit (skip when nothing is staged)
# ---------------------------------------------------------------
git diff --cached --quiet
if ($LASTEXITCODE -eq 0) {
    Write-Host "[DONE] Nothing to commit, working tree is clean." -ForegroundColor Green
    exit 0
}

if (-not $Message) {
    $Message = "chore: auto upload at $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
}

Write-Host "[4/5] Committing: $Message" -ForegroundColor Cyan
git commit -m $Message
if ($LASTEXITCODE -ne 0) { Fail "git commit failed (exit $LASTEXITCODE)" }

# ---------------------------------------------------------------
# [5/5] Push to origin
# ---------------------------------------------------------------
$Branch = git rev-parse --abbrev-ref HEAD
Write-Host "[5/5] Pushing branch '$Branch' to origin..." -ForegroundColor Cyan
git push -u origin $Branch
if ($LASTEXITCODE -ne 0) { Fail "git push failed (exit $LASTEXITCODE)" }

Write-Host "[DONE] Uploaded '$Branch' to origin successfully." -ForegroundColor Green
