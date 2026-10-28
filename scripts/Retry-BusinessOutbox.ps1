#requires -Version 7.0
param([Parameter(Mandatory)][ValidateSet('identity','parking','trade')][string]$Service,[Parameter(Mandatory)][Guid]$EventId)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
# Operator must first repair the cause. Retry only a FAILED event; no SENT records are changed.
$sql="UPDATE outbox_event SET status='PENDING',retry_count=0,next_retry_at=UTC_TIMESTAMP(3),lease_until=NULL,lease_token=NULL WHERE event_id='$EventId' AND status='FAILED'; SELECT ROW_COUNT() AS retried;"
Push-Location $root
try {
    $sql | docker compose --env-file deploy/.env.business -f deploy/compose.business.yaml exec -T mysql sh -c ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root '+$Service+'_db')
    if($LASTEXITCODE -ne 0){throw 'Outbox retry failed'}
} finally {Pop-Location}
