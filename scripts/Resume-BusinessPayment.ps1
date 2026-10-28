#requires -Version 7.0
param([Parameter(Mandatory)][ValidateRange(1,[long]::MaxValue)][long]$AttemptId)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
# Run only after inspecting the original channel receipt and fixing the failure cause.
# This reuses the existing attempt/channel number; it never creates another charge.
$sql=@"
START TRANSACTION;
SET @reservation=(SELECT reservation_id FROM payment_attempt WHERE id=$AttemptId);
SELECT reservation_id FROM trade_order WHERE reservation_id=@reservation FOR UPDATE;
UPDATE payment_attempt SET status='UNKNOWN',retry_count=0,next_retry_at=UTC_TIMESTAMP(3),lease_until=NULL,lease_token=NULL
WHERE id=$AttemptId AND status='REVIEW';
SET @resumed=ROW_COUNT();
UPDATE trade_order SET bill_version=bill_version+1 WHERE reservation_id=@reservation AND @resumed=1;
INSERT INTO audit_log(actor_id,action,resource_id) SELECT NULL,'PAYMENT_REVIEW_RETRY','$AttemptId' WHERE @resumed=1;
COMMIT;
SELECT @resumed AS resumed;
"@
Push-Location $root
try {
    $sql | docker compose --env-file deploy/.env.business -f deploy/compose.business.yaml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root trade_db'
    if($LASTEXITCODE -ne 0){throw 'Payment review retry failed'}
} finally {Pop-Location}
