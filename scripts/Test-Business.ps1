#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Use-Toolchain.ps1"
$config=Read-LocalConfig "$root/deploy/.env.business"
foreach($entry in $config.GetEnumerator()){[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
$id=Get-Date -Format 'yyyyMMdd-HHmmss'
$env:BUSINESS_REPORT_DIR=Join-Path $root ".runtime/business/checks/$id"
Push-Location $root
try {
    node scripts/business-smoke.mjs
    if($LASTEXITCODE -ne 0){throw "Business checks failed: $env:BUSINESS_REPORT_DIR"}
    Write-Host "Business checks passed: $env:BUSINESS_REPORT_DIR"
} finally {Pop-Location}
