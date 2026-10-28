#requires -Version 7.0
param([string]$Backup)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Use-Toolchain.ps1"
$config=Read-LocalConfig "$root/deploy/.env.business"
$env:MYSQL_ROOT_PASSWORD=$config.MYSQL_ROOT_PASSWORD
$runId=Get-Date -Format 'yyyyMMddHHmmss'
$prefix="w56_$runId"
$reportDir=Join-Path $root ".runtime/business/migrations/$runId"
$null=New-Item -ItemType Directory -Path $reportDir -Force
$created=@();$results=@()
function Invoke-TestSql([string]$sql){
 $output=$sql | docker compose --env-file deploy/.env.business -f deploy/compose.business.yaml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names'
 if($LASTEXITCODE -ne 0){throw 'Migration SQL failed'}
 return ($output -join "`n")
}
function Invoke-Flyway([string]$schema,[string]$name){
 & java -cp "$reportDir/lib/*" scripts/VerifyBusinessMigrations.java $schema "$name-service" *> (Join-Path $reportDir "$schema.log")
 if($LASTEXITCODE -ne 0){throw "Flyway failed for $schema; see private log"}
 $script:results+=@{name="$schema-flyway-migrate-validate-repeat";status='PASS'}
}
function Get-Facts([string]$kind){
 $parking="${prefix}_${kind}_parking";$trade="${prefix}_${kind}_trade";$identity="${prefix}_${kind}_identity"
 $orders=Invoke-TestSql "SELECT id,user_id,vehicle_id,space_id,community_id,plate,hourly_fen,start_at,end_at,pay_deadline,prepay_fen,overtime_fen,status FROM $parking.reservation_order ORDER BY id;"
 $ledger=Invoke-TestSql "SELECT * FROM $trade.ledger_entry ORDER BY id;"
 $accounts=Invoke-TestSql "SELECT id,username,password_hash,enabled FROM $identity.app_user ORDER BY id;"
 $hasRoles=Invoke-TestSql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$identity' AND table_name='user_role';"
 $roles=if($hasRoles -eq '1'){Invoke-TestSql "SELECT user_id,role_code FROM $identity.user_role ORDER BY user_id,role_code;"}else{Invoke-TestSql "SELECT id,role FROM $identity.app_user ORDER BY id,role;"}
 $hasGrants=Invoke-TestSql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$identity' AND table_name='community_admin';"
 $grants=if($hasGrants -eq '1'){Invoke-TestSql "SELECT user_id,community_id FROM $identity.community_admin ORDER BY user_id,community_id;"}else{Invoke-TestSql "SELECT id,community_id FROM $identity.app_user WHERE role='PROPERTY' AND community_id IS NOT NULL ORDER BY id,community_id;"}
 $hasPrice=Invoke-TestSql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$parking' AND table_name='share_window' AND column_name='hourly_fen';"
 $price=if($hasPrice -eq '1'){'w.hourly_fen'}else{'s.hourly_fen'}
 $windows=Invoke-TestSql "SELECT w.id,w.space_id,w.start_at,w.end_at,$price FROM $parking.share_window w JOIN $parking.parking_space s ON s.id=w.space_id ORDER BY w.id;"
 return "$orders`n$ledger`n$accounts`n$roles`n$grants`n$windows"
}
Push-Location $root
try {
 if(!$Backup){$Backup=(Get-ChildItem '.runtime/baselines/week56-final-*/business-before.sql' | Sort-Object FullName | Select-Object -Last 1).FullName}
 if(!$Backup -or !(Test-Path -LiteralPath $Backup)){throw 'A baseline backup is required'}
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $null=New-Item -ItemType Directory "$reportDir/lib"
 $zip=[IO.Compression.ZipFile]::OpenRead((Join-Path $root 'backend/identity-service/target/identity-service-0.1.0-SNAPSHOT.jar'))
 try{foreach($entry in $zip.Entries){if($entry.FullName -match '^BOOT-INF/lib/[^/]+\.jar$'){[IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path "$reportDir/lib" $entry.Name))}}}finally{$zip.Dispose()}
 foreach($name in @('identity','parking','trade')){
  $schema="${prefix}_fresh_$name";$null=Invoke-TestSql "CREATE DATABASE $schema CHARACTER SET utf8mb4;";$created+=$schema
  Invoke-Flyway $schema $name
 }
 $backups=@(@{kind='restore';path=$Backup})
 $legacy=Get-ChildItem '.runtime/baselines/database-week56-*/business-before.sql' | Sort-Object FullName | Select-Object -First 1
 if($legacy){$backups+=@{kind='legacy';path=$legacy.FullName}}
 foreach($item in $backups){
  $restore=Get-Content -LiteralPath $item.path -Raw -Encoding utf8
  foreach($name in @('identity','parking','trade')){$schema="${prefix}_$($item.kind)_$name";$restore=$restore.Replace(('`'+$name+'_db`'),('`'+$schema+'`'));$created+=$schema}
  $null=Invoke-TestSql $restore
  $before=Get-Facts $item.kind
  foreach($name in @('identity','parking','trade')){Invoke-Flyway "${prefix}_$($item.kind)_$name" $name}
  if($before -cne (Get-Facts $item.kind)){throw 'Historical orders, ledger or accounts changed during migration'}
  $results+=@{name="$($item.kind)-restore-and-historical-facts-preserved";status='PASS'}
  $parking="${prefix}_$($item.kind)_parking"
  $unlinked=Invoke-TestSql "SELECT COUNT(*) FROM $parking.reservation_order WHERE window_id IS NULL;"
  $results+=@{name="$($item.kind)-unlinked-historical-orders";status='PASS';count=[int]$unlinked}
 }
} catch {$results+=@{name='migration-verification';status='FAIL';error=$_.Exception.Message};throw}
finally {
 foreach($schema in $created){
  if($schema -notmatch "^${prefix}_(fresh|restore|legacy)_(identity|parking|trade)$"){throw 'Unsafe temporary schema cleanup'}
  try{$null=Invoke-TestSql "DROP DATABASE IF EXISTS $schema;"}catch{$results+=@{name='temporary-schema-cleanup';status='FAIL'}}
 }
 $build=Get-Content '.runtime/backend-build.json' -Raw|ConvertFrom-Json
 @{createdAt=[DateTime]::UtcNow.ToString('o');sourceFingerprint=$build.source.fingerprint;results=$results} | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $reportDir 'results.json')
 $env:MYSQL_ROOT_PASSWORD=$null
 Pop-Location
 Write-Host "Migration results: $reportDir"
}
