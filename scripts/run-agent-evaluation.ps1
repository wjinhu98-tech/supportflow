param(
    [switch]$Offline,
    [string]$MavenCommand = 'mvn.cmd',
    [string]$NodeCommand = 'node'
)

$ErrorActionPreference = 'Stop'
$projectDirectory = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location -LiteralPath $projectDirectory
try {
    & $NodeCommand --test scripts/compare-agent-evaluations.test.mjs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $evaluationArguments = @('-B', '-ntp')
    if ($Offline) { $evaluationArguments += '-o' }
    # These explicit goals avoid package/repackage/clean and keep an already running application jar intact.
    $evaluationArguments += @('-Dtest=AgentEvaluationContractTest', '-Dit.test=AgentScenarioEvaluationIT',
        'test-compile', 'surefire:test', 'failsafe:integration-test', 'failsafe:verify')
    & $MavenCommand @evaluationArguments
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Output 'Offline Agent evaluation passed. Report: target/agent-evaluation/latest.md'
} finally {
    Pop-Location
}
