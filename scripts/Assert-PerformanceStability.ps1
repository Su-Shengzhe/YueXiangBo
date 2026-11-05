#requires -Version 7.0
param([Parameter(Mandatory)][string]$ReportFile)
$ErrorActionPreference='Stop'
$report=Get-Content -LiteralPath $ReportFile -Raw|ConvertFrom-Json
if($report.status -ne 'PASS'){throw 'Performance execution did not pass'}
if(@($report.stability).Count -ne 12){throw 'Incomplete repeated performance matrix'}
$unstable=@($report.stability|Where-Object {$_.samples -lt 2 -or $_.coefficientOfVariation -gt 0.15 -or !$_.stable})
if(!$report.stableBaseline -or $unstable.Count){
 throw "Performance stability acceptance failed: $($unstable.Count) groups; keep optimization disabled. Raw measurements remain available in $ReportFile"
}
Write-Host 'Performance stability acceptance: PASS'
