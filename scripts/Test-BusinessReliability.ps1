#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Common.ps1"
$config=Read-LocalConfig "$root/deploy/.env.business"
$report=@{status='RUNNING';checks=@();realAiAccepted=$false}
$key='reliability-test:'+([Guid]::NewGuid().ToString('N'))
function Redis([string[]]$Arguments) {
    $value=& docker compose --env-file deploy/.env.business -f deploy/compose.business.yaml exec -T redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli "$@"' sh @Arguments
    if($LASTEXITCODE -ne 0 -or "$value" -match '^(ERR|WRONGTYPE|NOAUTH)'){throw 'Redis regression command failed'}
    return $value
}
Push-Location $root
try {
    # Load the exact Lua program used by IdentityApi, not a second implementation.
    $source=Get-Content backend/identity-service/src/main/java/cn/community/identity/LoginLimit.java -Raw
    $lua=[regex]::Match($source,'(?s)"""(.*?)"""').Groups[1].Value
    if(!$lua){throw 'Login limit Lua source missing'}
    $sha=$lua | docker compose --env-file deploy/.env.business -f deploy/compose.business.yaml exec -T redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli -x SCRIPT LOAD'
    if($LASTEXITCODE -ne 0 -or "$sha" -notmatch '^[a-f0-9]{40}$'){throw 'Lua load failed'}
    $first=[long](Redis @('EVALSHA',"$sha",'1',$key,'60000'))
    $ttl=[long](Redis @('PTTL',$key))
    if($first -ne 1 -or $ttl -le 0 -or $ttl -gt 60000){throw 'First increment lacks expiry'}
    $null=Redis @('SET',$key,'10')
    if([long](Redis @('PTTL',$key)) -ne -1){throw 'Legacy fixture lacks expected orphan TTL'}
    $repaired=[long](Redis @('EVALSHA',"$sha",'1',$key,'60000'))
    $ttl=[long](Redis @('PTTL',$key))
    if($repaired -ne 11 -or $ttl -le 0){throw 'Orphan counter was not repaired while preserving its count'}
    $report.checks+=@{name='atomic-login-limit-and-orphan-TTL-repair';status='PASS'}
    foreach($port in @(9081,9082,9083)) {
        $uri="http://127.0.0.1:$port/internal/v1/outbox/status"
        $unauthorized=Invoke-WebRequest $uri -SkipHttpErrorCheck -TimeoutSec 10
        if([int]$unauthorized.StatusCode -ne 403){throw 'Internal outbox endpoint must reject an absent service token'}
        $status=Invoke-RestMethod $uri -Headers @{'X-Service-Token'=$config.SERVICE_TOKEN} -TimeoutSec 10
        if($null -eq $status.oldestPendingAgeSeconds){throw 'Outbox age metric missing'}
        if($port -ne 9081 -and (!$status.brokerReachable -or $status.deadLetterCount -lt 0)){throw 'Dead letter queue metric unavailable'}
        $report.checks+=@{name="outbox-operations-$port";status='PASS';metrics=$status}
    }
    $report.status='PASS'
    Write-Host 'Redis atomic limiting and protected outbox metrics passed.'
} catch { $report.status='FAIL'; $report.error=$_.Exception.Message; throw }
finally {
    try { $null=Redis @('DEL',$key) } finally {
        $report.endedAt=[DateTime]::UtcNow.ToString('o')
        Write-AtomicJson "$root/.runtime/optimization-reliability.json" $report
        Pop-Location
    }
}
