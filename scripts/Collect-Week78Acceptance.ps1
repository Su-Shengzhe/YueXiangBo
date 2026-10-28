#requires -Version 7.0
param(
 [Parameter(Mandatory)][string]$MigrationRun,
 [Parameter(Mandatory)][string]$CorrectnessRun,
 [Parameter(Mandatory)][string]$LoadRun
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Use-Toolchain.ps1"
foreach($id in @($MigrationRun,$CorrectnessRun,$LoadRun)){if($id -notmatch '^\d{14}$'){throw 'Expected explicit run ID'}}
$build=Get-Content "$root/.runtime/backend-build.json" -Raw|ConvertFrom-Json
if($build.status -ne 'PASS' -or (Get-BuildSource $root).fingerprint -ne $build.source.fingerprint){throw 'Unverified backend source'}
$out=Join-Path $root 'docs/evidence/2026-10-27-week78-acceptance'
$null=New-Item -ItemType Directory -Force $out
$reports=@{}
foreach($name in @('migrations','correctness','load')){
 $id=switch($name){migrations{$MigrationRun} correctness{$CorrectnessRun} load{$LoadRun}}
 $category=if($name -eq 'migrations'){'migrations'}else{'parking-concurrency'}
 $path=Join-Path $root ".runtime/business/$category/$id"
 $report=Get-Content "$path/results.json" -Raw|ConvertFrom-Json
 if($report.sourceFingerprint -ne $build.source.fingerprint){throw 'Report source mismatch'}
 if($name -eq 'migrations'){
  if($report.results.Count -ne 13 -or @($report.results|Where-Object status -ne 'PASS').Count){throw 'Migration checks incomplete'}
  $migrationLines=@(Get-ChildItem $path -Filter '*.log'|ForEach-Object {Get-Content $_.FullName|Where-Object {$_ -match '^PASS '}})
  if($migrationLines.Count -ne 9){throw 'Expected nine migrated/validated databases'}
  $report|Add-Member -NotePropertyName migrationSummaries -NotePropertyValue $migrationLines
 }else{
  $expected=if($name -eq 'load'){6}else{13}
  if($report.status -ne 'PASS' -or $report.checks.Count -ne $expected){throw 'Concurrency checks incomplete'}
  $lines=Get-Content "$path/run.log"
  if(!($lines -contains 'CLEANUP isolated-schema-dropped')){throw 'Missing cleanup evidence'}
  $report|Add-Member -Force -NotePropertyName environment -NotePropertyValue @($lines|Where-Object {$_ -like 'ENV *'})
  $report|Add-Member -Force -NotePropertyName metrics -NotePropertyValue @($lines|Where-Object {$_ -like 'METRIC *'})
  if($name -eq 'load' -and $report.metrics.Count -ne 6){throw 'Incomplete load metrics'}
 }
 $report|Add-Member -NotePropertyName sourceRun -NotePropertyValue ".runtime/business/$category/$id"
 $report|ConvertTo-Json -Depth 12|Set-Content "$out/$name.json" -Encoding utf8
 $reports[$name]=$id
}
$sources=@('scripts/VerifyParkingConcurrency.java','scripts/Test-ParkingConcurrency.ps1','scripts/Test-Week56Migrations.ps1','scripts/VerifyBusinessMigrations.java','scripts/Collect-Week78Acceptance.ps1')|ForEach-Object {@{path=$_;sha256=(Get-FileHash (Join-Path $root $_)).Hash.ToLowerInvariant()}}
@{createdAt=[DateTime]::UtcNow.ToString('o');buildId=$build.id;backendFingerprint=$build.source.fingerprint;reports=$reports;testSources=$sources;scope='MySQL migration and packaged-controller transactions; excludes gateway HTTP throughput'}|ConvertTo-Json -Depth 8|Set-Content "$out/manifest.json" -Encoding utf8
Write-Host "Acceptance evidence: $out"
