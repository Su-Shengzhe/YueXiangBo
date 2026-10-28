#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$runId=Get-Date -Format 'yyyyMMddHHmmss'
$prefix="w56_$runId"
if($prefix -notmatch '^w56_\d{14}$'){throw 'Invalid isolated schema prefix'}
$reportDir=Join-Path $root ".runtime/business/migrations/$runId"
New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
function Invoke-TestSql([string]$sql){
 $output=$sql | docker compose --env-file deploy/.env.business -f deploy/compose.business.yaml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names'
 if($LASTEXITCODE -ne 0){throw 'Migration SQL failed'}
 return ($output -join "`n")
}
$created=@()
$results=@()
Push-Location $root
try {
 foreach($entry in @(@{service='identity-service';name='identity'},@{service='parking-service';name='parking'},@{service='trade-service';name='trade'})){
  $schema="${prefix}_fresh_$($entry.name)"
  $null=Invoke-TestSql "CREATE DATABASE $schema CHARACTER SET utf8mb4;"
  $created+=$schema
  foreach($file in Get-ChildItem "backend/$($entry.service)/src/main/resources/db/business/V*.sql" | Sort-Object Name){$null=Invoke-TestSql ("USE $schema;`n"+(Get-Content $file.FullName -Raw))}
  $results+=@{name="fresh-$($entry.name)-all-migrations";status='PASS'}
 }
 $backup=Get-ChildItem '.runtime/baselines/database-week56-*/business-before.sql' | Sort-Object FullName | Select-Object -Last 1
 if(!$backup){throw 'Pre-migration backup is required'}
 $restore=Get-Content $backup.FullName -Raw
 foreach($name in @('identity','parking','trade')){ $schema="${prefix}_restore_$name";$restore=$restore.Replace(('`'+$name+'_db`'),('`'+$schema+'`'));$created+=$schema }
 $null=Invoke-TestSql $restore
 $results+=@{name='three-database-backup-restores-to-isolated-schemas';status='PASS'}
 $parking="${prefix}_restore_parking"
 $before=Invoke-TestSql "SELECT id,user_id,vehicle_id,space_id,community_id,plate,hourly_fen,start_at,end_at,pay_deadline,prepay_fen,overtime_fen,status FROM $parking.reservation_order ORDER BY id;"
 $null=Invoke-TestSql ("USE $parking;`n"+(Get-Content 'backend/parking-service/src/main/resources/db/business/V004__resource_management.sql' -Raw))
 $after=Invoke-TestSql "SELECT id,user_id,vehicle_id,space_id,community_id,plate,hourly_fen,start_at,end_at,pay_deadline,prepay_fen,overtime_fen,status FROM $parking.reservation_order ORDER BY id;"
 if($before -cne $after){throw 'Existing reservation facts changed during migration'}
 $null=Invoke-TestSql ("USE ${prefix}_restore_identity;`n"+(Get-Content 'backend/identity-service/src/main/resources/db/business/V003__resource_management.sql' -Raw))
 $missingRoles=Invoke-TestSql "SELECT COUNT(*) FROM ${prefix}_restore_identity.app_user u WHERE NOT EXISTS(SELECT 1 FROM ${prefix}_restore_identity.user_role r WHERE r.user_id=u.id AND r.role_code=u.role);"
 if($missingRoles -ne '0'){throw 'Legacy roles were not preserved'}
 $wrongPrice=Invoke-TestSql "SELECT COUNT(*) FROM $parking.share_window w JOIN $parking.parking_space s ON s.id=w.space_id WHERE w.hourly_fen<>s.hourly_fen;"
 if($wrongPrice -ne '0'){throw 'Legacy window prices were not preserved'}
 $results+=@{name='upgrade-preserves-all-existing-reservation-fields-roles-and-prices';status='PASS'}
} catch { $results+=@{name='migration-verification';status='FAIL';error=$_.Exception.Message};throw }
finally {
 # These schemas are created by this run, never the three application databases.
 foreach($schema in $created){if($schema -notmatch "^${prefix}_(fresh|restore)_(identity|parking|trade)$"){throw 'Unsafe temporary schema cleanup'};$null=Invoke-TestSql "DROP DATABASE IF EXISTS $schema;"}
 $results | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $reportDir 'results.json')
 Pop-Location
 Write-Host "Migration results: $reportDir"
}
