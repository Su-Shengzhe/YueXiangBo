#requires -Version 7.0
. (Join-Path $PSScriptRoot 'Common.ps1')
$projectRoot=Split-Path $PSScriptRoot -Parent
$localConfig=Read-LocalConfig (Join-Path $projectRoot 'deploy/.env.local')
$imageLock=Get-Content (Join-Path $projectRoot 'deploy/images.lock.json') -Raw | ConvertFrom-Json -AsHashtable
Assert-LocalConfig $localConfig $imageLock
$defaults=@{MYSQL_PORT=13306;REDIS_PORT=16379;RABBITMQ_PORT=25672;RABBITMQ_UI_PORT=25673;REGISTRY_PORT=8761;GATEWAY_PORT=8080;PARKING_PORT=8082}
foreach ($key in $defaults.Keys) { if (!$localConfig.ContainsKey($key)) { $localConfig[$key]="$($defaults[$key])" } }
$allowed=@('MYSQL_IMAGE','REDIS_IMAGE','RABBITMQ_IMAGE','MYSQL_ROOT_PASSWORD','PARKING_DB_PASSWORD','REDIS_PASSWORD','RABBITMQ_PASSWORD','PROBE_TOKEN')+@($defaults.Keys)
foreach ($key in $allowed) { [Environment]::SetEnvironmentVariable($key,$localConfig[$key],'Process') }
