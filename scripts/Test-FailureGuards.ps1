#requires -Version 7.0
# Run after builds/container checks; temporarily edits only the local build report.
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Common.ps1')
$id=[Guid]::NewGuid().ToString('N')
$report=@{id=$id;status='RUNNING';checks=@();startedAt=[DateTime]::UtcNow.ToString('o')}
$buildPath=Join-Path $root '.runtime/backend-build.json'
$original=[IO.File]::ReadAllBytes($buildPath)
try {
    # Reserve a local port without serving HTTP, guaranteeing an unavailable endpoint.
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    $listener.Start()
    try {
        $caught=$false
        try {
            & (Join-Path $PSScriptRoot 'Invoke-Smoke.ps1') -GatewayUrl "http://127.0.0.1:$($listener.LocalEndpoint.Port)" -ReadyTimeoutSeconds 1
        } catch { $caught=$true }
        $failure=Get-Content (Join-Path $root '.runtime/smoke-result.json') -Raw | ConvertFrom-Json
        if(!$caught -or $failure.status -ne 'FAIL' -or $failure.failure.step -ne 'readiness'){throw 'Smoke failure was not recorded correctly.'}
        $report.checks+=@{check='real_http_failure_records_fail';status='PASS';smokeRunId=$failure.id}
    } finally {$listener.Stop()}
    foreach($case in @('source','jar')){
        $record=[Text.Encoding]::UTF8.GetString($original) | ConvertFrom-Json -AsHashtable
        if($case -eq 'source'){$record.source.fingerprint='0'*64}else{$record.artifacts['parking-service'].sha256='0'*64}
        Write-AtomicJson $buildPath $record
        $caught=$false
        try { & (Join-Path $PSScriptRoot 'Build-AppImage.ps1') -Module parking-service -UseVerifiedBuild } catch {
            if($_.Exception.Message -notmatch 'stale JAR|JAR does not match'){throw}
            $caught=$true
        }
        if(!$caught){throw "Stale $case was accepted."}
        $report.checks+=@{check="stale_${case}_rejected_before_docker";status='PASS'}
    }
    $report.status='PASS'
} catch {
    $report.status='FAIL'
    throw
} finally {
    [IO.File]::WriteAllBytes($buildPath,$original)
    $report.endedAt=[DateTime]::UtcNow.ToString('o')
    Write-AtomicJson (Join-Path $root ".runtime/failure-guards/$id.json") $report
}
# End with a fresh successful local smoke; preserve the failed run in its own directory.
& (Join-Path $PSScriptRoot 'Invoke-Smoke.ps1')
Write-Host 'HTTP failure persistence and stale build rejection verified.'
