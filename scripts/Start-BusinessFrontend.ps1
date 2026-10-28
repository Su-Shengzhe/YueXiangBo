#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Use-Toolchain.ps1"
if(!(Test-Path "$root/frontend/.env.business.local")){throw 'Initialize-Business.ps1 first'}
$directory=Join-Path $root '.runtime/business/frontend'
$null=New-Item -ItemType Directory -Force $directory
$guard=[IO.File]::Open((Join-Path $root '.runtime/business/process-control.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {
    $state=Join-Path $directory 'process.json'
    if(Test-Path $state){
        $existing=Get-Content $state -Raw | ConvertFrom-Json
        if(Test-OwnedProcess $existing){Wait-HttpReady 'http://127.0.0.1:5174' -TimeoutSeconds 5 -ProcessId $existing.pid;Write-Host 'Business frontend already ready: http://127.0.0.1:5174';return}
    }
    if(Get-NetTCPConnection -State Listen -LocalPort 5174 -ErrorAction SilentlyContinue){throw 'Port 5174 is owned by another process'}
    $run=Get-Date -Format 'yyyyMMdd-HHmmss'
    $app=Start-Process -FilePath (Get-Command node).Source -ArgumentList @('node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','5174','--mode','business') -WorkingDirectory "$root/frontend" -WindowStyle Hidden -PassThru -RedirectStandardOutput "$directory/$run.stdout.log" -RedirectStandardError "$directory/$run.stderr.log"
    $record=@{module='frontend-business';pid=$app.Id;startedAt=$app.StartTime.ToUniversalTime().ToString('o')}
    Write-AtomicJson $state $record
    try {Wait-HttpReady 'http://127.0.0.1:5174' -TimeoutSeconds 30 -ProcessId $app.Id} catch {Stop-OwnedProcesses @($record);throw}
    Write-Host 'Business frontend ready: http://127.0.0.1:5174'
} finally {$guard.Dispose()}
