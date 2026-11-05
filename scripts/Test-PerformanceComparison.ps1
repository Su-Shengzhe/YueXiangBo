#requires -Version 7.0
param([ValidateRange(5,60)][int]$WarmupSeconds=20,[ValidateRange(10,180)][int]$MeasureSeconds=60,[switch]$CandidateFirst,[ValidateRange(2,6)][int]$Pairs=2,[string]$ConfirmAfter,[ValidateSet(23306,23316)][int]$MySqlPort=23306)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$reportDir=Join-Path $root ('.runtime/business/performance-comparison/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$null=New-Item -ItemType Directory -Force $reportDir
$report=@{status='RUNNING';mysqlPort=$MySqlPort;candidateFirst=[bool]$CandidateFirst;warmupSeconds=$WarmupSeconds;measureSeconds=$MeasureSeconds;applicationStoppedDuringMeasurements=$false;runs=@();optimizationDefault='disabled'}
$previous=$null
$newPairs=$Pairs
if($ConfirmAfter){
 $previous=Get-Content -LiteralPath $ConfirmAfter -Raw|ConvertFrom-Json
 if($previous.status -ne 'PASS' -or $previous.runs.Count -ne 4 -or $previous.stableBaseline){throw 'Confirmation requires a completed, unstable four-run experiment'}
 if($previous.warmupSeconds -ne $WarmupSeconds -or $previous.measureSeconds -ne $MeasureSeconds){throw 'Confirmation must preserve measurement durations'}
 $previousPort=if($previous.mysqlPort){[int]$previous.mysqlPort}else{23306}
 if($previousPort -ne $MySqlPort){throw 'Confirmation cannot combine different database endpoints'}
 $currentBuild=Get-Content "$root/.runtime/backend-build.json" -Raw|ConvertFrom-Json
 $currentRunner=(Get-FileHash "$PSScriptRoot/VerifyParkingConcurrency.java").Hash.ToLowerInvariant()
 if(@($previous.runs|Where-Object {$_.sourceFingerprint -ne $currentBuild.source.fingerprint -or $_.runnerSha256 -ne $currentRunner}).Count){throw 'Confirmation cannot combine different builds or runners'}
 $report.basedOn=(Resolve-Path -LiteralPath $ConfirmAfter).Path
 $report.basedOnSha256=(Get-FileHash -LiteralPath $ConfirmAfter).Hash.ToLowerInvariant()
 $report.conditioningRuns=@($previous.runs|Select-Object -First 2)
 $report.runs=@($previous.runs|Select-Object -Last 2)
 $report.method='Exploratory initial pair retained as conditioning evidence; confirm the subsequent pair with one additional reversed pair. This does not erase instability in the original full experiment.'
 $newPairs=1
}
function Get-MeasurementHost {
 try {
  $cpu=Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'"
  $disk=Get-CimInstance Win32_PerfFormattedData_PerfDisk_PhysicalDisk -Filter "Name='_Total'"
  return @{at=[DateTime]::UtcNow.ToString('o');cpuPercent=$cpu.PercentProcessorTime;diskQueue=$disk.CurrentDiskQueueLength;diskWritesPerSecond=$disk.DiskWritesPersec;diskWriteBytesPerSecond=$disk.DiskWriteBytesPersec;availableMemoryMB=(Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory).AvailableMBytes}
 }catch{return @{at=[DateTime]::UtcNow.ToString('o');unavailable=$_.Exception.Message}}
}
Push-Location $root
$hostMonitor=Start-Job -ArgumentList (Join-Path $reportDir 'host-samples.jsonl') -ScriptBlock {
 param($output)
 while($true){
  try{
   $cpu=Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'"
   $disks=@(Get-CimInstance Win32_PerfFormattedData_PerfDisk_PhysicalDisk|Select-Object Name,CurrentDiskQueueLength,DiskBytesPersec)
   @{at=[DateTime]::UtcNow.ToString('o');cpuPercent=$cpu.PercentProcessorTime;disks=$disks;availableMB=(Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory).AvailableMBytes}|ConvertTo-Json -Depth 4 -Compress|Add-Content -LiteralPath $output
  }catch{@{at=[DateTime]::UtcNow.ToString('o');error=$_.Exception.Message}|ConvertTo-Json -Compress|Add-Content -LiteralPath $output}
  Start-Sleep -Seconds 5
 }
}
try {
 ./scripts/Stop-Business.ps1
 $report.applicationStoppedDuringMeasurements=$true
 # No builds or other regression jobs are launched between these sequential runs.
 $order=if($CandidateFirst){@($true,$false)}else{@($false,$true)}
 if($previous){$order=@([bool]$previous.runs[-1].sharedCommunityLock,(-not [bool]$previous.runs[-1].sharedCommunityLock))}
 if(!$previous){foreach($shared in $order){
  ./scripts/Test-ParkingConcurrency.ps1 -Mode correctness -SharedCommunityLock:$shared -MySqlPort $MySqlPort
 }}
 for($pair=0;$pair -lt $newPairs;$pair++){
 if($pair -gt 0){[array]::Reverse($order)}
 foreach($shared in $order){
  $hostBefore=Get-MeasurementHost
  ./scripts/Test-ParkingConcurrency.ps1 -Mode load -SharedCommunityLock:$shared -WarmupSeconds $WarmupSeconds -MeasureSeconds $MeasureSeconds -MySqlPort $MySqlPort
  $run=Get-ChildItem .runtime/business/parking-concurrency -Directory|Sort-Object Name -Descending|Select-Object -First 1
  $data=Get-Content (Join-Path $run.FullName 'results.json') -Raw|ConvertFrom-Json
  if($data.status -ne 'PASS' -or $data.sharedCommunityLock -ne $shared){throw 'Unexpected performance result'}
  $report.runs+=@{pair=$pair;directory=$run.Name;sharedCommunityLock=$shared;sourceFingerprint=$data.sourceFingerprint;runnerSha256=$data.runnerSha256;metrics=$data.metrics;environment=$data.environment;hostBefore=$hostBefore;hostAfter=(Get-MeasurementHost)}
 }
 }
 if(@($report.runs.sourceFingerprint|Select-Object -Unique).Count -ne 1 -or @($report.runs.runnerSha256|Select-Object -Unique).Count -ne 1){throw 'Comparison build or runner mismatch'}
 $samples=@()
 foreach($run in $report.runs){foreach($metric in $run.metrics){if($metric -match '^METRIC communities=(\w+) concurrency=(\d+) cycles=\d+ cyclesPerSecond=([\d.]+)'){$samples+=@{group="$($run.sharedCommunityLock)/$($Matches[1])/$($Matches[2])";rate=[double]::Parse($Matches[3],[Globalization.CultureInfo]::InvariantCulture)}}}}
 $report.stability=@($samples|Group-Object group|ForEach-Object {
  $values=@($_.Group.rate);$mean=($values|Measure-Object -Average).Average
  $variance=($values|ForEach-Object {[math]::Pow($_-$mean,2)}|Measure-Object -Average).Average
  $cv=[math]::Sqrt($variance)/$mean
  @{group=$_.Name;samples=$values.Count;meanCyclesPerSecond=$mean;coefficientOfVariation=$cv;stable=($values.Count -ge 2 -and $cv -le 0.15)}
 })
 $report.stableBaseline=@($report.stability|Where-Object {!$_.stable}).Count -eq 0
 $report.performanceAcceptance=if($report.stableBaseline){'PASS'}else{'UNSTABLE'}
 $report.optimizationDecision='KEEP_DISABLED'
 $report.decisionReason=if($report.stableBaseline){'Stable repeated measurements; enabling requires a separate gain review.'}else{'Variation exceeds 15 percent; do not claim a stable baseline or enable the candidate.'}
 $report.status='PASS'
}catch{$report.status='FAIL';$report.error=$_.Exception.Message;throw}
finally {
 try{./scripts/Start-Business.ps1;$report.servicesRestored=$true}catch{$report.servicesRestored=$false;$report.restoreError=$_.Exception.Message;$report.status='FAIL';throw}
 finally{Stop-Job $hostMonitor;Remove-Job $hostMonitor;$report|ConvertTo-Json -Depth 10|Set-Content (Join-Path $reportDir 'results.json') -Encoding utf8;Pop-Location;Write-Host "Performance execution: $($report.status); acceptance: $($report.performanceAcceptance), $reportDir"}
}
& "$PSScriptRoot/Assert-PerformanceStability.ps1" -ReportFile (Join-Path $reportDir 'results.json')
