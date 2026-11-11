#requires -Version 7.0
param([switch]$LiveModel)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Use-Toolchain.ps1"
$config=Read-LocalConfig "$root/deploy/.env.business"
foreach($entry in $config.GetEnumerator()){[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
$env:AI_ACCEPTANCE_LIVE=if($LiveModel){'true'}else{'false'}
$env:BUSINESS_REPORT_DIR=Join-Path $root ('.runtime/business/ai/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
Push-Location $root
try { node scripts/ai-acceptance.mjs; if($LASTEXITCODE -ne 0){throw "AI acceptance failed: $env:BUSINESS_REPORT_DIR"} } finally {Pop-Location}
