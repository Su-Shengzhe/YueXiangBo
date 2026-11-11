#requires -Version 7.0
param()
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Use-Toolchain.ps1"
$config=Read-LocalConfig "$root/deploy/.env.business"
$env:MYSQL_ROOT_PASSWORD=$config.MYSQL_ROOT_PASSWORD
$id=Get-Date -Format 'yyyyMMddHHmmss'
$directory=Join-Path $root ".runtime/business/trade-faults/$id"
$null=New-Item -ItemType Directory -Force "$directory/lib","$directory/classes"
$build=Get-Content "$root/.runtime/backend-build.json" -Raw|ConvertFrom-Json
if((Get-BuildSource $root).fingerprint -ne $build.source.fingerprint){throw 'Backend build does not match source'}
$jar=Join-Path $root $build.artifacts.'trade-service'.path
if((Get-FileHash $jar).Hash.ToLowerInvariant() -ne $build.artifacts.'trade-service'.sha256){throw 'Trade artifact changed'}
$report=@{status='RUNNING';mode='correctness';sourceFingerprint=$build.source.fingerprint;checks=@();method='Packaged PaymentWorker and refund application services, actual Spring transactions, isolated MySQL schema; no gateway HTTP';runnerSha256=(Get-FileHash "$PSScriptRoot/VerifyTradeFaults.java").Hash.ToLowerInvariant()}
Push-Location $root
try {
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $zip=[IO.Compression.ZipFile]::OpenRead($jar)
 try{foreach($entry in $zip.Entries){
  if($entry.FullName -match '^BOOT-INF/lib/[^/]+\.jar$'){[IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path "$directory/lib" $entry.Name))}
  elseif($entry.FullName -match '^BOOT-INF/classes/(cn/[A-Za-z0-9_/$]+\.class)$'){$target=Join-Path "$directory/classes" $Matches[1];$null=New-Item -ItemType Directory -Force (Split-Path $target);[IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target)}
 }}finally{$zip.Dispose()}
 & java -cp "$directory/classes;$directory/lib/*" scripts/VerifyTradeFaults.java "w78_parking_$id" *> "$directory/run.log"
 if($LASTEXITCODE -ne 0){throw 'Trade MySQL fault test failed; inspect private run.log'}
 foreach($line in (Get-Content "$directory/run.log"|Where-Object {$_ -like 'PASS *'})){$report.checks+=@{status='PASS';name=$line}}
 $expected=19
 if($report.checks.Count -ne $expected){throw "Incomplete concurrency evidence: expected $expected checks"}
 $report.metrics=@(Get-Content "$directory/run.log"|Where-Object {$_ -like 'METRIC *'})
 $report.environment=@(Get-Content "$directory/run.log"|Where-Object {$_ -like 'ENV *'})
 if(!(Select-String -LiteralPath "$directory/run.log" -Pattern '^CLEANUP isolated-schema-dropped$' -Quiet)){throw 'Missing isolated schema cleanup evidence'}
 $report.status='PASS'
}catch{$report.status='FAIL';$report.error=$_.Exception.Message;throw}
finally{$report.createdAt=[DateTime]::UtcNow.ToString('o');Write-AtomicJson "$directory/results.json" $report;Remove-Item Env:MYSQL_ROOT_PASSWORD;Pop-Location;Write-Host "Trade faults: $($report.status), $directory"}
