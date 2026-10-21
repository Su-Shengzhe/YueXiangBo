#requires -Version 7.0
# Deliberately fails the second Java launch, then restores the normal local stack.
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Common.ps1')
$id=[Guid]::NewGuid().ToString('N')
$report=@{id=$id;status='RUNNING';checks=@();startedAt=[DateTime]::UtcNow.ToString('o')}
$global:ParkingVerificationLaunches=0
& (Join-Path $PSScriptRoot 'Stop-Local.ps1')
function Start-Process {
    param($FilePath,$ArgumentList,$WorkingDirectory,$WindowStyle,[switch]$PassThru,$RedirectStandardOutput,$RedirectStandardError)
    $global:ParkingVerificationLaunches++
    if ($global:ParkingVerificationLaunches -eq 2) { throw 'Injected second launch failure' }
    Microsoft.PowerShell.Management\Start-Process @PSBoundParameters
}
try {
    $caught=$false
    try { & (Join-Path $PSScriptRoot 'Start-Local.ps1') -SkipInfrastructure } catch {
        if ($_.Exception.Message -notmatch 'Injected second launch failure') { throw }
        $caught=$true
    }
    if (!$caught) { throw 'Startup fault injection did not execute.' }
    $state=@(Get-Content (Join-Path $root '.runtime/processes.json') -Raw | ConvertFrom-Json)
    if($state.Count){throw 'New process records survived failed startup.'}
    $startupDir=Get-ChildItem (Join-Path $root '.runtime/startups') -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $startup=Get-Content (Join-Path $startupDir.FullName 'result.json') -Raw | ConvertFrom-Json
    foreach($record in $startup.created){if(Test-OwnedProcess $record){throw 'A process created by the failed run is still alive.'}}
    $report.checks+=@{check='second_launch_failure_cleans_first_process';status='PASS';startupRun=$startup.id}
    $report.status='PASS'
} catch {
    $report.status='FAIL'
    throw
} finally {
    Remove-Item -LiteralPath Function:\Start-Process
    Remove-Variable -Name ParkingVerificationLaunches -Scope Global
    $report.endedAt=[DateTime]::UtcNow.ToString('o')
    Write-AtomicJson (Join-Path $root ".runtime/recovery-checks/$id.json") $report
    & (Join-Path $PSScriptRoot 'Start-Local.ps1')
}
Write-Host 'Startup failure cleanup verified; local services restored.'
