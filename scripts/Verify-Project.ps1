#requires -Version 7.0
param([switch]$CleanContainers,[switch]$ExerciseStartupFailure)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Use-Toolchain.ps1')
$id=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$runDir=Join-Path $root ".runtime/verifications/$id"
$null=New-Item -ItemType Directory -Force $runDir
$report=@{id=$id;startedAt=[DateTime]::UtcNow.ToString('o');status='RUNNING';steps=@();toolchain=(Get-Content (Join-Path $PSScriptRoot 'toolchain.lock.json') -Raw | ConvertFrom-Json)}
$step='script regression'
try {
    & (Join-Path $PSScriptRoot 'Test-Scripts.ps1')
    $report.steps+=@{step=$step;status='PASS'}
    $step='backend build'
    & (Join-Path $PSScriptRoot 'Build-Backend.ps1')
    $report.steps+=@{step=$step;status='PASS';build=(Get-Content (Join-Path $root '.runtime/backend-build.json') -Raw | ConvertFrom-Json).id}
    $step='frontend clean install and build'
    Push-Location (Join-Path $root 'frontend')
    try {
        npm.cmd ci --no-audit --no-fund *> (Join-Path $runDir 'npm-ci.log')
        if($LASTEXITCODE -ne 0){throw 'npm ci failed'}
        npm.cmd run build *> (Join-Path $runDir 'frontend-build.log')
        if($LASTEXITCODE -ne 0){throw 'Frontend build failed'}
    } finally {Pop-Location}
    $report.steps+=@{step=$step;status='PASS'}
    $step='local start and smoke'
    & (Join-Path $PSScriptRoot 'Initialize-Local.ps1')
    & (Join-Path $PSScriptRoot 'Stop-Local.ps1')
    & (Join-Path $PSScriptRoot 'Start-Local.ps1')
    & (Join-Path $PSScriptRoot 'Invoke-Smoke.ps1')
    $report.steps+=@{step=$step;status='PASS';smoke=(Get-Content (Join-Path $root '.runtime/smoke-result.json') -Raw | ConvertFrom-Json).id}
    $step='browser'
    & (Join-Path $PSScriptRoot 'Test-Frontend.ps1')
    $report.steps+=@{step=$step;status='PASS'}
    if($ExerciseStartupFailure){
        $step='startup recovery'
        & (Join-Path $PSScriptRoot 'Test-StartupRecovery.ps1')
        $report.steps+=@{step=$step;status='PASS'}
    }
    if($CleanContainers){
        $step='clean volume container stack'
        & (Join-Path $PSScriptRoot 'Test-Container.ps1') -UseVerifiedBuild
        $report.steps+=@{step=$step;status='PASS'}
    }
    $step='failure reporting and stale build guards'
    & (Join-Path $PSScriptRoot 'Test-FailureGuards.ps1')
    $report.steps+=@{step=$step;status='PASS'}
    $report.status='PASS'
} catch {
    $report.status='FAIL'
    $report.steps+=@{step=$step;status='FAIL'}
    throw
} finally {
    $report.endedAt=[DateTime]::UtcNow.ToString('o')
    Write-AtomicJson (Join-Path $runDir 'result.json') $report
    Write-Host "Verification $($report.status): $runDir/result.json"
}
