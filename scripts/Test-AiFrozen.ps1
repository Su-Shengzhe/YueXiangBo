#requires -Version 7.0
param([switch]$BillRaceOnly,[string]$ResultsFile)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Use-Toolchain.ps1"
$suite=if($BillRaceOnly){'ai-frozen-bill-race'}else{'ai-frozen'}
if($ResultsFile){
    $ResultsFile=(Resolve-Path -LiteralPath $ResultsFile).Path
    $suite='ai-frozen-offline'
}else{
    $cfg=Read-LocalConfig "$root/deploy/.env.business"
    foreach($entry in $cfg.GetEnumerator()){[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
    $lib=Get-ChildItem "$root/.runtime/business/trade-faults" -Directory|Sort-Object LastWriteTime -Descending|Select-Object -First 1
    $env:EVAL_JAVA_CP=Join-Path $lib.FullName 'lib/*'
}
$env:BUSINESS_REPORT_DIR=Join-Path $root (".runtime/business/$suite/"+(Get-Date -Format 'yyyyMMdd-HHmmss-fffffff'))
Write-Output "Report: $env:BUSINESS_REPORT_DIR"
Push-Location $root
$options=@()
if($BillRaceOnly){$options+='--bill-race-only'}
if($ResultsFile){$options+=@('--results-file',$ResultsFile)}
try{node scripts/ai-frozen.mjs @options;if($LASTEXITCODE -ne 0){throw "Frozen evaluation failed: $env:BUSINESS_REPORT_DIR"}}finally{Pop-Location;Remove-Item Env:MYSQL_ROOT_PASSWORD -ErrorAction SilentlyContinue}
