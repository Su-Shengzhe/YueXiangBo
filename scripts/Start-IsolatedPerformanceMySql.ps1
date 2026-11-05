#requires -Version 7.0
param([Parameter(Mandatory)][string]$StorageRoot)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Common.ps1"
$config=Read-LocalConfig "$root/deploy/.env.business"
$resolved=[IO.Path]::GetFullPath($StorageRoot)
if($resolved -notmatch '^[A-Za-z]:\\codex-performance-yuexiangbo$'){throw 'Use a dedicated drive-root codex-performance-yuexiangbo directory'}
if(Get-NetTCPConnection -State Listen -LocalPort 23316 -ErrorAction SilentlyContinue){throw 'Performance port is already occupied'}
$id=Get-Date -Format yyyyMMddHHmmss
$container="yuexiangbo-perf-$id"
$data=Join-Path $resolved $id
if(Test-Path -LiteralPath $data){throw 'Refusing to reuse an existing database directory'}
$directory=Join-Path $root ".runtime/business/isolated-performance/$id"
$null=New-Item -ItemType Directory -Force $data,$directory
$env:MYSQL_ROOT_PASSWORD=$config.MYSQL_ROOT_PASSWORD
try {
 docker run -d --name $container --label codex.project=yuexiangbo-performance --memory 1g -p 127.0.0.1:23316:3306 --mount "type=bind,source=$data,target=/var/lib/mysql" -e MYSQL_ROOT_PASSWORD $config.MYSQL_IMAGE --innodb-flush-log-at-trx-commit=1 --sync-binlog=1 | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Isolated MySQL could not start'}
 $deadline=[DateTime]::UtcNow.AddMinutes(4)
 do {
  docker exec $container sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -Nse "SELECT 1"' *> "$directory/readiness.log"
  if($LASTEXITCODE -eq 0){break}
  Start-Sleep -Seconds 2
 }while([DateTime]::UtcNow -lt $deadline)
 if($LASTEXITCODE -ne 0){throw 'Isolated MySQL did not become ready'}
 $image=docker inspect --format '{{.Image}}' $container
 $physical=(Get-Partition -DriveLetter $resolved.Substring(0,1)|Get-Disk|Select-Object Number,FriendlyName,BusType,Size)
 $state=@{container=$container;dataDirectory=$data;image=$config.MYSQL_IMAGE;imageId=$image;port=23316;storage=$physical;flushLogAtCommit=1;syncBinlog=1;memoryLimit='1g';createdAt=[DateTime]::UtcNow.ToString('o');status='READY'}
 Write-AtomicJson "$directory/environment.json" $state
 Write-AtomicJson "$root/.runtime/business/isolated-performance/current.json" $state
 Write-Host "Isolated persistent MySQL ready: $container on port 23316; $data"
}catch{
 docker logs $container *> "$directory/container.log"
 throw
}finally{Remove-Item Env:MYSQL_ROOT_PASSWORD -ErrorAction SilentlyContinue}
