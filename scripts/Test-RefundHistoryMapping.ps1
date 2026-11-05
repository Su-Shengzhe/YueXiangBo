#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Use-Toolchain.ps1"
$config=Read-LocalConfig "$root/deploy/.env.business"
$directory=Join-Path $root ('.runtime/business/refund-history/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$null=New-Item -ItemType Directory -Force $directory
$report=@{status='RUNNING';passes=@();sourceFingerprint=(Get-Content "$root/.runtime/backend-build.json" -Raw|ConvertFrom-Json).source.fingerprint}
function Facts {
 param([switch]$Mapping)
 $sql="SELECT 'attempt',id,reservation_id,kind,amount_fen,status,channel_no FROM trade_db.payment_attempt ORDER BY id; SELECT 'ledger',id,reservation_id,channel_no,kind,amount_fen FROM trade_db.ledger_entry ORDER BY id; SELECT 'channel',channel_no,kind,amount_fen,result FROM trade_db.channel_operation ORDER BY channel_no;"
 if($Mapping){$sql="SELECT * FROM trade_db.refund_receipt_allocation ORDER BY attempt_id,receipt_id; SELECT id,application_id FROM trade_db.payment_attempt ORDER BY id; SELECT id,reservation_id,amount_fen,status FROM trade_db.refund_application ORDER BY id;"}
 $rows=$sql | docker compose --env-file deploy/.env.business -f deploy/compose.business.yaml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names'
 if($LASTEXITCODE -ne 0){throw 'Snapshot query failed'}
 return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($rows -join "`n"))))
}
Push-Location $root
try {
 $login=Invoke-RestMethod http://127.0.0.1:9090/api/v1/auth/login -Method Post -ContentType application/json -Body (@{username='admin';password=$config.DEMO_ADMIN_PASSWORD}|ConvertTo-Json)
 $headers=@{Authorization='Bearer '+$login.data.accessToken}
 $before=Facts
 foreach($round in 1..2){
  $cursor='0';$items=@()
  do {
   $data=(Invoke-RestMethod "http://127.0.0.1:9090/api/v1/admin/refund-attempts/map-history?after=$cursor" -Method Post -Headers $headers).data
   $items+=@($data.items);$cursor=$data.next
  }while($data.hasMore)
  if($items.Count -eq 0 -or @($items|Where-Object status -ne 'PASS').Count){throw 'Historical mapping is empty or requires review'}
  $after=Facts
  if($before -ne $after){throw 'Mapping changed original payment/channel/ledger facts'}
  $mapped=Facts -Mapping
  if($round -eq 2 -and $mapped -ne $firstMapping){throw 'Repeated mapping changed associations or parent amounts'}
  $firstMapping=$mapped
  $report.passes+=@{round=$round;orders=$items.Count;review=0;originalFactsUnchanged=$true;items=$items}
 }
 $report.status='PASS'
 $report.repeatMappingUnchanged=$true
}catch{$report.status='FAIL';$report.error=$_.Exception.Message;throw}
finally{$report|ConvertTo-Json -Depth 10|Set-Content (Join-Path $directory 'results.json');Pop-Location;Write-Host "History mapping: $($report.status), $directory"}

