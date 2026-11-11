#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Use-Toolchain.ps1"
$config=Read-LocalConfig "$root/deploy/.env.business"
foreach($entry in $config.GetEnumerator()){[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
$env:BUSINESS_REPORT_DIR=Join-Path $root ('.runtime/business/ai-stage/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
Push-Location $root
try {node scripts/ai-stage-evaluation.mjs;if($LASTEXITCODE -ne 0){throw 'Stage evaluation interrupted'};Write-Output $env:BUSINESS_REPORT_DIR}finally{Pop-Location}
