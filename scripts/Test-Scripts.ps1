#requires -Version 7.0
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')
$root=Split-Path $PSScriptRoot -Parent
$runDir=Join-Path $root ('.runtime/script-tests/'+[Guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Force $runDir
$checks=@()
function Check([string]$Name,[scriptblock]$Action) {
    & $Action
    $script:checks+=@{name=$Name;status='PASS'}
    Write-Host "PASS $Name"
}
function Reject([scriptblock]$Action,[string]$Pattern) {
    $rejected=$false
    try { & $Action } catch {
        if ($_.Exception.Message -notmatch $Pattern) { throw }
        $rejected=$true
    }
    if (!$rejected) { throw "Expected rejection: $Pattern" }
}
$images=Get-Content (Join-Path $root 'deploy/images.lock.json') -Raw | ConvertFrom-Json -AsHashtable
$valid=@{MYSQL_IMAGE=$images.mysql;REDIS_IMAGE=$images.redis;RABBITMQ_IMAGE=$images.rabbitmq}
foreach($key in @('MYSQL_ROOT_PASSWORD','PARKING_DB_PASSWORD','REDIS_PASSWORD','RABBITMQ_PASSWORD','PROBE_TOKEN')){$valid[$key]='a'*64}
try {
    Check 'missing_lock_does_not_choose_latest' { Reject { Read-ToolchainLock $runDir } 'missing' }
    Check 'exact_runtime_version_checked' {
        $lock=Read-ToolchainLock $root
        $wrong=$lock.node.Clone(); $wrong.version='v24.0.0'
        Reject { Assert-InstalledTool node $wrong (Join-Path $root ".tools/node/$($lock.node.directory)") '' } 'differs from lock'
    }
    Check 'valid_configuration_accepted' { Assert-LocalConfig $valid $images }
    Check 'missing_secret_rejected' { $c=$valid.Clone();$c.Remove('PROBE_TOKEN');Reject { Assert-LocalConfig $c $images } 'PROBE_TOKEN' }
    Check 'image_drift_rejected' { $c=$valid.Clone();$c.MYSQL_IMAGE='mysql:latest';Reject { Assert-LocalConfig $c $images } 'Image drift' }
    Check 'duplicate_ports_rejected' { $c=$valid.Clone();$c.GATEWAY_PORT='8082';Reject { Assert-LocalConfig $c $images } 'distinct' }
    Check 'duplicate_keys_rejected' {
        $file=Join-Path $runDir 'duplicate.env'
        Set-Content $file "A=1`nA=2"
        Reject { Read-LocalConfig $file } 'Duplicate'
    }
    Check 'missing_credentials_with_existing_volumes_rejected' {
        # Replace only the Docker CLI in this test's scope; no actual volume mutation.
        function docker { $global:LASTEXITCODE=0; 'existing-project-volume' }
        Reject { Assert-NoExistingVolumes 'fixture' } 'Existing project volumes'
    }
    Check 'empty_process_list_is_written' {
        $file=Join-Path $runDir 'processes.json'
        Write-AtomicJson $file @()
        if ((Get-Content $file -Raw).Trim() -ne '[]') { throw 'Empty array was not preserved.' }
    }
    Check 'path_boundary_rejects_escape' { Reject { Assert-ChildPath (Join-Path $runDir '../escape') $runDir } 'outside' }
    Check 'failed_run_replaces_latest_success' {
        $file=Join-Path $runDir 'latest.json'
        Write-AtomicJson $file @{status='PASS';id='previous'}
        Write-AtomicJson $file @{status='FAIL';id='current'}
        $report=Get-Content $file -Raw | ConvertFrom-Json
        if ($report.status -ne 'FAIL' -or $report.id -ne 'current') { throw 'Stale success survived.' }
    }
    Write-AtomicJson (Join-Path $runDir 'result.json') @{status='PASS';checks=$checks;executedAt=[DateTime]::UtcNow.ToString('o')}
} catch {
    Write-AtomicJson (Join-Path $runDir 'result.json') @{status='FAIL';checks=$checks}
    throw
}
Write-Host "Script regression report: $runDir/result.json"
