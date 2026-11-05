#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
 $sql=@'
START TRANSACTION;
INSERT INTO trade_db.audit_log(actor_id,action,resource_id,reason)
 SELECT NULL,'REFUND_TIME_BACKFILL',CAST(t.reservation_id AS CHAR),CONCAT('parking.exited_at=',CAST(p.exited_at AS CHAR))
 FROM trade_db.trade_order t JOIN parking_db.reservation_order p ON p.id=t.reservation_id
 WHERE t.status='COMPLETED' AND p.status='COMPLETED' AND t.completed_at IS NULL AND p.exited_at IS NOT NULL;
UPDATE trade_db.trade_order t JOIN parking_db.reservation_order p ON p.id=t.reservation_id
 SET t.completed_at=p.exited_at,t.bill_version=t.bill_version+1
 WHERE t.status='COMPLETED' AND p.status='COMPLETED' AND t.completed_at IS NULL AND p.exited_at IS NOT NULL;
SELECT ROW_COUNT() AS backfilled_orders;
COMMIT;
SELECT COUNT(*) AS unresolved_orders FROM trade_db.trade_order WHERE status='COMPLETED' AND completed_at IS NULL;
'@
 $sql | docker compose --env-file deploy/.env.business -f deploy/compose.business.yaml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch'
 if($LASTEXITCODE -ne 0){throw 'Completion timestamp backfill failed'}
} finally {Pop-Location}
