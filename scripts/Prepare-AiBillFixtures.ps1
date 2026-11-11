#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Use-Toolchain.ps1"
$config=Read-LocalConfig "$root/deploy/.env.business"
foreach($entry in $config.GetEnumerator()){[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
$env:BUSINESS_REPORT_DIR=Join-Path $root ('.runtime/business/bill-fixtures/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
Push-Location $root
try{node scripts/prepare-ai-bill-fixtures.mjs;if($LASTEXITCODE -ne 0){throw "Bill fixture generation failed: $env:BUSINESS_REPORT_DIR"};Write-Output $env:BUSINESS_REPORT_DIR}finally{Pop-Location}
