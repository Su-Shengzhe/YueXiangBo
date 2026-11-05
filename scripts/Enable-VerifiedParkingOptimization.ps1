#requires -Version 7.0
param(
 [Parameter(Mandatory)][string]$ReportFile,
 [Parameter(Mandatory)][string]$BaselineCorrectnessFile,
 [Parameter(Mandatory)][string]$CandidateCorrectnessFile,
 [Parameter(Mandatory)][string]$DecisionFile
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Common.ps1"
& "$PSScriptRoot/Assert-PerformanceStability.ps1" -ReportFile $ReportFile
$report=Get-Content -LiteralPath $ReportFile -Raw|ConvertFrom-Json
$build=Get-Content "$root/.runtime/backend-build.json" -Raw|ConvertFrom-Json
if($report.runs[0].sourceFingerprint -ne $build.source.fingerprint -or (Get-BuildSource $root).fingerprint -ne $build.source.fingerprint){throw 'Performance evidence does not match current build/source'}
$variant=$false
foreach($file in @($BaselineCorrectnessFile,$CandidateCorrectnessFile)){
 $check=Get-Content -LiteralPath $file -Raw|ConvertFrom-Json
 if($check.status -ne 'PASS' -or $check.mode -ne 'correctness' -or $check.sharedCommunityLock -ne $variant -or @($check.checks).Count -ne 13 -or @($check.checks|Where-Object status -ne 'PASS').Count -ne 0 -or $check.sourceFingerprint -ne $build.source.fingerprint -or $check.runnerSha256 -ne $report.runs[0].runnerSha256){throw 'Missing matching correctness evidence'}
 $variant=$true
}
$summaryFile=Join-Path (Split-Path -Parent (Resolve-Path -LiteralPath $ReportFile)) 'gain-review.json'
& "$PSScriptRoot/Summarize-PerformanceComparison.ps1" -ReportFile $ReportFile -OutputFile $summaryFile
$summary=Get-Content -LiteralPath $summaryFile -Raw|ConvertFrom-Json
foreach($row in $summary.comparison){
 if($row.scenario.StartsWith('same/') -and $row.throughputChangePercent -lt 20){throw 'Same-community gain is below the adoption threshold'}
 if($row.scenario.StartsWith('different/') -and ($row.throughputChangePercent -lt -10 -or $row.candidateMeanP95Ms -gt 1.15*$row.baselineMeanP95Ms)){throw 'Different-community regression exceeds the adoption threshold'}
}
# This is an opt-in for the local business launcher; the JAR fallback stays false.
$configFile=Join-Path $root 'deploy/.env.business'
$lines=@(Get-Content -LiteralPath $configFile|Where-Object {$_ -notmatch '^PARKING_COMMUNITY_SHARED_LOCK='})
$lines+='PARKING_COMMUNITY_SHARED_LOCK=true'
Set-Content -LiteralPath $configFile -Value $lines -Encoding utf8
Write-AtomicJson $DecisionFile @{status='APPROVED';scope='local business launcher';enabled=$true;sourceFingerprint=$build.source.fingerprint;reportSha256=(Get-FileHash -LiteralPath $ReportFile).Hash.ToLowerInvariant();summary=$summary.comparison;restartRequired=$true;rollback='Set PARKING_COMMUNITY_SHARED_LOCK=false in deploy/.env.business, then Stop-Business.ps1 and Start-Business.ps1';at=[DateTime]::UtcNow.ToString('o')}
Write-Host 'Verified parking optimization configured; restart business services to apply.'
