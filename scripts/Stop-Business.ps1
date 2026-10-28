#requires -Version 7.0
param([switch]$Infrastructure)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Common.ps1"
$guard=[IO.File]::Open((Join-Path $root '.runtime/business/process-control.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {
    $state=Join-Path $root '.runtime/business/processes.json'
    if(Test-Path $state){$records=@(Get-Content $state -Raw | ConvertFrom-Json);Stop-OwnedProcesses $records;Write-AtomicJson $state @()}
    $front=Join-Path $root '.runtime/business/frontend/process.json'
    if(Test-Path $front){Stop-OwnedProcesses @((Get-Content $front -Raw | ConvertFrom-Json))}
    if($Infrastructure){
        Push-Location $root
        try {docker compose --env-file deploy/.env.business -f deploy/compose.business.yaml stop;if($LASTEXITCODE -ne 0){throw 'Compose stop failed'}} finally {Pop-Location}
    }
} finally {$guard.Dispose()}
