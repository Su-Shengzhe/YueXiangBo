#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Use-Toolchain.ps1"
$config=Read-LocalConfig "$root/deploy/.env.business"
foreach($entry in $config.GetEnumerator()){[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
$env:WEEK56_REPORT_DIR=Join-Path $root ('.runtime/business/week56/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
Push-Location $root
try { node scripts/week56-check.mjs; if($LASTEXITCODE -ne 0){throw 'Week56 checks failed'}; Write-Host "Week56 checks: $env:WEEK56_REPORT_DIR" } finally {Pop-Location}
