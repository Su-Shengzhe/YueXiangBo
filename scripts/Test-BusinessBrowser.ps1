#requires -Version 7.0
param([string]$TestFilter)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Use-Toolchain.ps1"
$config=Read-LocalConfig "$root/deploy/.env.business"
foreach($entry in $config.GetEnumerator()){[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
$env:BUSINESS_REPORT_DIR=Join-Path $root ('.runtime/business/browser/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$null=New-Item -ItemType Directory -Force $env:BUSINESS_REPORT_DIR
Push-Location "$root/frontend"
try {
    $testArgs=@('--no-install','playwright','test','--config','playwright.business.config.ts')
    if($TestFilter){$testArgs+=@('--grep',$TestFilter)}
    npx.cmd @testArgs
    if($LASTEXITCODE -ne 0){throw "Business browser test failed: $env:BUSINESS_REPORT_DIR"}
} finally {
    $reports=@((Join-Path $env:BUSINESS_REPORT_DIR 'browser-results.json'))
    $reports+=@(Get-ChildItem "$root/frontend/test-results" -Filter 'error-context.md' -Recurse -ErrorAction SilentlyContinue|ForEach-Object FullName)
    foreach($path in $reports) {
        if(Test-Path $path){
            $text=Get-Content $path -Raw
            foreach($entry in $config.GetEnumerator()){if($entry.Key -match 'PASSWORD|TOKEN'){$text=$text.Replace($entry.Value,'[REDACTED]')}}
            [IO.File]::WriteAllText($path,$text)
        }
    }
    Pop-Location
}
