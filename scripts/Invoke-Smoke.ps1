#requires -Version 7.0
param([ValidateRange(1,300)][int]$ReadyTimeoutSeconds=90,[string]$GatewayUrl,[string]$ParkingUrl,[string]$ConfigPath)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')
$root=Split-Path $PSScriptRoot -Parent
$runId=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$runDir=Join-Path $root ".runtime/checks/$runId"
$null=New-Item -ItemType Directory -Force $runDir
$report=@{id=$runId;startedAt=[DateTime]::UtcNow.ToString('o');status='RUNNING';scope='Environment probe only';results=@();failure=$null}
$latest=Join-Path $root '.runtime/smoke-result.json'
Write-AtomicJson $latest $report
$step='configuration'
function Check([string]$Name,[bool]$Passed) {
    $report.results+=@{check=$Name;status=$(if($Passed){'PASS'}else{'FAIL'})}
    if (!$Passed) { throw "Assertion failed: $Name" }
    Write-Host "PASS $Name"
}
function Request([string]$Uri,[string]$Method='GET',[hashtable]$RequestHeaders=$headers,[string]$Body='') {
    $parameters=@{Uri=$Uri;Method=$Method;Headers=$RequestHeaders;TimeoutSec=5;SkipHttpErrorCheck=$true}
    if ($Body) { $parameters.Body=$Body; $parameters.ContentType='application/json' }
    return Invoke-WebRequest @parameters
}
try {
    if ($ConfigPath) {
        $config=Read-LocalConfig $ConfigPath
        Assert-LocalConfig $config (Get-Content (Join-Path $root 'deploy/images.lock.json') -Raw | ConvertFrom-Json -AsHashtable)
    } else {
        . (Join-Path $PSScriptRoot 'Load-LocalConfig.ps1')
        $config=$localConfig
    }
    if (!$GatewayUrl) { $GatewayUrl="http://127.0.0.1:$($config.GATEWAY_PORT)" }
    if (!$ParkingUrl) { $ParkingUrl="http://127.0.0.1:$($config.PARKING_PORT)" }
    $headers=@{'X-Probe-Token'=$config.PROBE_TOKEN}
    $base=$GatewayUrl.TrimEnd('/')+'/api/dev'
    $step='readiness'
    Wait-HttpReady "$base/probes" -Headers $headers -TimeoutSeconds $ReadyTimeoutSeconds
    $step='database_commit'
    $label='smoke-'+[Guid]::NewGuid().ToString('N')
    $response=Request "$base/probes" POST $headers (@{label=$label}|ConvertTo-Json)
    Check 'create_http_201' ($response.StatusCode -eq 201)
    $created=$response.Content | ConvertFrom-Json
    $rows=(Request "$base/probes").Content | ConvertFrom-Json
    Check 'gateway_discovery_mysql_write_read' ([bool]($rows | Where-Object { $_.id -eq $created.id -and $_.label -eq $label }))
    $report.record=$created
    $step='database_rollback'
    $rollbackLabel='rollback-'+[Guid]::NewGuid().ToString('N')
    $response=Request "$base/rollback" POST $headers (@{label=$rollbackLabel}|ConvertTo-Json)
    Check 'mysql_transaction_rollback_http' ($response.StatusCode -eq 409)
    $exists=(Request "$base/probes/exists?label=$rollbackLabel").Content | ConvertFrom-Json
    Check 'mysql_transaction_rollback_no_row' (!$exists.exists)
    $step='authorization_and_input'
    Check 'missing_token_rejected' ((Request "$base/probes" GET @{}).StatusCode -eq 401)
    Check 'wrong_token_rejected' ((Request "$base/probes" GET @{'X-Probe-Token'=('0'*64)}).StatusCode -eq 401)
    Check 'invalid_input_rejected' ((Request "$base/probes" POST $headers '{"label":""}').StatusCode -eq 400)
    $step='redis'
    Check 'redis_write_read' (((Request "$base/redis").Content | ConvertFrom-Json).status -eq 'ok')
    $step='rabbitmq'
    $response=Request "$base/messages" POST
    Check 'message_submission_http_202' ($response.StatusCode -eq 202)
    $message=$response.Content | ConvertFrom-Json
    $received=$false
    $deadline=[DateTime]::UtcNow.AddSeconds(15)
    do {
        $state=(Request "$base/messages/$($message.id)").Content | ConvertFrom-Json
        if ($state.received) { $received=$true; break }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    Check 'rabbit_publish_consume_mysql_receipt' $received
    $report.messageId=$message.id
    $step='internal_route_isolation'
    $response=Request ($ParkingUrl.TrimEnd('/')+'/internal/environment-probe')
    Check 'internal_endpoint_exists_on_backend' ($response.StatusCode -eq 200 -and ($response.Content | ConvertFrom-Json).marker -eq 'parking-internal-probe')
    Check 'gateway_blocks_existing_internal_endpoint' ((Request ($GatewayUrl.TrimEnd('/')+'/internal/environment-probe')).StatusCode -eq 404)
    $report.status='PASS'
} catch {
    $report.status='FAIL'
    # Do not serialize request headers, response dumps, or raw exception objects.
    $report.failure=@{step=$step;type=$_.Exception.GetType().Name;message="Step '$step' failed; inspect service logs and configuration. Credentials are omitted."}
    throw "Smoke run $runId failed at $step. Report: $runDir/result.json"
} finally {
    $report.endedAt=[DateTime]::UtcNow.ToString('o')
    Write-AtomicJson (Join-Path $runDir 'result.json') $report
    Write-AtomicJson $latest $report
    Write-Host "Smoke status: $($report.status). Report: $runDir/result.json"
}
