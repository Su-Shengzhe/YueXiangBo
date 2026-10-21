#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Use-Toolchain.ps1')
. (Join-Path $PSScriptRoot 'Load-LocalConfig.ps1')
$id=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$runDir=Join-Path $root ".runtime/browser-checks/$id"
$null=New-Item -ItemType Directory -Force $runDir
$previousReport=$env:PROBE_BROWSER_REPORT
$previousScreenshot=$env:PROBE_BROWSER_SCREENSHOT
$env:PROBE_BROWSER_REPORT=Join-Path $runDir 'playwright.json'
$env:PROBE_BROWSER_SCREENSHOT=Join-Path $runDir 'page.png'
$report=@{id=$id;status='RUNNING';startedAt=[DateTime]::UtcNow.ToString('o');node=(& node --version);npm=(& npm.cmd --version);lockSha256=(Get-FileHash (Join-Path $root 'frontend/package-lock.json')).Hash}
Push-Location (Join-Path $root 'frontend')
try {
    npx.cmd --no-install playwright test *> (Join-Path $runDir 'browser.log')
    if ($LASTEXITCODE -ne 0) { throw 'Browser test failed.' }
    $report.status='PASS'
} catch {
    $report.status='FAIL'
    throw "Browser verification failed; see $runDir"
} finally {
    foreach($name in @('playwright.json','browser.log')) {
        $path=Join-Path $runDir $name
        if(Test-Path $path){(Get-Content $path -Raw).Replace($env:PROBE_TOKEN,'[REDACTED]') | Set-Content -Encoding utf8 $path}
    }
    $report.endedAt=[DateTime]::UtcNow.ToString('o')
    Write-AtomicJson (Join-Path $runDir 'result.json') $report
    $env:PROBE_BROWSER_REPORT=$previousReport
    $env:PROBE_BROWSER_SCREENSHOT=$previousScreenshot
    Pop-Location
    Write-Host "Browser result: $($report.status), $runDir"
}
