#requires -Version 7.0
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')
$root=Split-Path $PSScriptRoot -Parent
$file=Join-Path $root '.runtime/processes.json'
if (!(Test-Path $file)) { return }
$guard=[IO.File]::Open((Join-Path $root '.runtime/process-control.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {
    $records=@(Get-Content $file -Raw | ConvertFrom-Json)
    foreach ($record in $records) {
        $process=Get-Process -Id $record.pid -ErrorAction SilentlyContinue
        if ($process -and !(Test-OwnedProcess $record)) { throw 'PID identity mismatch; refusing to stop unrelated process.' }
    }
    Stop-OwnedProcesses $records
    Write-AtomicJson $file @()
    Write-Host 'Stopped owned Java processes; containers and data volumes are preserved.'
} finally { $guard.Dispose() }
