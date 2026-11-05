#requires -Version 7.0
param([Parameter(Mandatory)][string]$ReportFile,[Parameter(Mandatory)][string]$OutputFile)
$ErrorActionPreference='Stop'
$report=Get-Content -LiteralPath $ReportFile -Raw|ConvertFrom-Json
if($report.status -ne 'PASS'){throw 'Cannot summarize an incomplete experiment'}
$culture=[Globalization.CultureInfo]::InvariantCulture
$samples=@(foreach($run in $report.runs){
 foreach($metric in $run.metrics){
  if($metric -match '^METRIC communities=(\w+) concurrency=(\d+) cycles=(\d+) cyclesPerSecond=([\d.]+) p50Ms=([\d.]+) p95Ms=([\d.]+) p99Ms=([\d.]+) errors=\{\} warmErrors=0 active=0$'){
   [pscustomobject]@{scenario="$($Matches[1])/$($Matches[2])";candidate=[bool]$run.sharedCommunityLock;run=$run.directory;rate=[double]::Parse($Matches[4],$culture);p95Ms=[double]::Parse($Matches[6],$culture)}
  }
 }
})
if($samples.Count -ne 6*$report.runs.Count){throw 'Missing or failed load metrics'}
if(@($report.runs.sourceFingerprint|Select-Object -Unique).Count -ne 1 -or @($report.runs.runnerSha256|Select-Object -Unique).Count -ne 1){throw 'Mixed code or measurement implementations'}
$comparison=@(foreach($scenario in ($samples.scenario|Sort-Object -Unique)){
 $baseline=@($samples|Where-Object {$_.scenario -eq $scenario -and !$_.candidate})
 $candidate=@($samples|Where-Object {$_.scenario -eq $scenario -and $_.candidate})
 if($baseline.Count -lt 2 -or $baseline.Count -ne $candidate.Count){throw 'Unbalanced or insufficient repetitions'}
 $before=($baseline.rate|Measure-Object -Average).Average
 $after=($candidate.rate|Measure-Object -Average).Average
 [pscustomobject]@{scenario=$scenario;baselineMeanCyclesPerSecond=$before;candidateMeanCyclesPerSecond=$after;throughputChangePercent=100*($after/$before-1);baselineMeanP95Ms=($baseline.p95Ms|Measure-Object -Average).Average;candidateMeanP95Ms=($candidate.p95Ms|Measure-Object -Average).Average;baselineSamples=$baseline;candidateSamples=$candidate}
})
@{sourceReport=(Resolve-Path -LiteralPath $ReportFile).Path;sourceReportSha256=(Get-FileHash -LiteralPath $ReportFile).Hash.ToLowerInvariant();sourceFingerprint=$report.runs[0].sourceFingerprint;runnerSha256=$report.runs[0].runnerSha256;stableBaseline=$report.stableBaseline;stability=$report.stability;comparison=$comparison;interpretation='Descriptive repeated local controller/MySQL measurements; not HTTP throughput or a statistical confidence interval. An unstable experiment cannot establish an optimization gain.'}|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $OutputFile -Encoding utf8
