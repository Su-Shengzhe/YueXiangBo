param([int]$ReadyTimeoutSeconds = 180)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Load-LocalConfig.ps1')
$headers = @{'X-Probe-Token'=$env:PROBE_TOKEN}
$base = 'http://127.0.0.1:8080/api/dev'
$deadline = [DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
$ready=$false
do {
    try {
        $null=Invoke-RestMethod "$base/probes" -Headers $headers -TimeoutSec 5
        $ready=$true
        break
    } catch { Start-Sleep -Seconds 2 }
} while ([DateTime]::UtcNow -lt $deadline)
if (!$ready) { throw 'Gateway to parking-service did not become ready; inspect .runtime logs.' }
$results=@()
function Check([string]$name,[bool]$passed) {
    if (!$passed) { throw "Failed: $name" }
    $script:results += @{check=$name;status='PASS'}
    Write-Host "PASS $name"
}
$label='smoke-'+[Guid]::NewGuid().ToString('N')
$created=Invoke-RestMethod "$base/probes" -Method Post -Headers $headers -ContentType 'application/json' -Body (@{label=$label}|ConvertTo-Json)
$rows=Invoke-RestMethod "$base/probes" -Headers $headers
Check 'gateway_discovery_mysql_write_read' ([bool]($rows | Where-Object { $_.id -eq $created.id -and $_.label -eq $label }))
$rollbackLabel='rollback-'+[Guid]::NewGuid().ToString('N')
$status=0
try {
    $null=Invoke-WebRequest "$base/rollback" -Method Post -Headers $headers -ContentType 'application/json' -Body (@{label=$rollbackLabel}|ConvertTo-Json)
} catch { $status=[int]$_.Exception.Response.StatusCode }
Check 'mysql_transaction_rollback_http' ($status -eq 409)
$rows=Invoke-RestMethod "$base/probes" -Headers $headers
Check 'mysql_transaction_rollback_no_row' (!($rows | Where-Object { $_.label -eq $rollbackLabel }))
$status=0
try { $null=Invoke-WebRequest "$base/probes" } catch { $status=[int]$_.Exception.Response.StatusCode }
Check 'probe_requires_token' ($status -eq 401)
$status=0
try { $null=Invoke-WebRequest "$base/probes" -Method Post -Headers $headers -ContentType 'application/json' -Body '{"label":""}' } catch { $status=[int]$_.Exception.Response.StatusCode }
Check 'invalid_input_rejected' ($status -eq 400)
$redis=Invoke-RestMethod "$base/redis" -Headers $headers
Check 'redis_write_read' ($redis.status -eq 'ok')
$message=Invoke-RestMethod "$base/messages" -Method Post -Headers $headers
$received=$false
for ($attempt=0; $attempt -lt 20; $attempt++) {
    $state=Invoke-RestMethod "$base/messages/$($message.id)" -Headers $headers
    if ($state.received) { $received=$true; break }
    Start-Sleep -Milliseconds 500
}
Check 'rabbit_publish_consume_mysql_receipt' $received
$blocked=0
try { $null=Invoke-WebRequest 'http://127.0.0.1:8080/internal/probe' } catch { $blocked=[int]$_.Exception.Response.StatusCode }
Check 'gateway_does_not_route_internal' ($blocked -eq 404)
$evidence=@{executedAt=[DateTime]::UtcNow.ToString('o');scope='Environment probe only, not business acceptance';record=$created;messageId=$message.id;results=$results}
$evidence | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 (Join-Path $root '.runtime/smoke-result.json')
Write-Host 'Evidence saved to .runtime/smoke-result.json'
