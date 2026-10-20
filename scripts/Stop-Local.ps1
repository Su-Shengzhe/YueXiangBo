$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$file = Join-Path $root '.runtime/processes.json'
if (!(Test-Path $file)) { return }
foreach ($app in (Get-Content $file -Raw | ConvertFrom-Json)) {
    $process = Get-Process -Id $app.pid -ErrorAction SilentlyContinue
    if (!$process) { continue }
    if ($process.ProcessName -ne 'java' -or $process.StartTime.ToUniversalTime().ToString('o') -ne $app.startedAt) {
        throw "PID identity mismatch for $($app.module); refusing to stop."
    }
    Stop-Process -Id $app.pid
}
Write-Host 'Stopped project Java processes. Infrastructure and volumes are preserved.'
