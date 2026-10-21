#requires -Version 7.0
param([switch]$SyncImages)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')
$root=Split-Path $PSScriptRoot -Parent
$file=Join-Path $root 'deploy/.env.local'
$images=Get-Content (Join-Path $root 'deploy/images.lock.json') -Raw | ConvertFrom-Json -AsHashtable
if (Test-Path $file) {
    $config=Read-LocalConfig $file
    Assert-LocalConfig $config $images -IgnoreImageDrift:$SyncImages
    if ($SyncImages) {
        $null=New-Item -ItemType Directory -Force (Join-Path $root '.runtime/config-backups')
        Copy-Item -LiteralPath $file -Destination (Join-Path $root ".runtime/config-backups/env-$([Guid]::NewGuid().ToString('N')).local")
        $config.MYSQL_IMAGE=$images.mysql
        $config.REDIS_IMAGE=$images.redis
        $config.RABBITMQ_IMAGE=$images.rabbitmq
        Save-EnvFile $file $config
    }
    Write-Host 'Existing credentials validated and preserved.'
    return
}
Assert-NoExistingVolumes 'community-parking-dev'
$config=@{MYSQL_IMAGE=$images.mysql;REDIS_IMAGE=$images.redis;RABBITMQ_IMAGE=$images.rabbitmq}
foreach ($key in @('MYSQL_ROOT_PASSWORD','PARKING_DB_PASSWORD','REDIS_PASSWORD','RABBITMQ_PASSWORD','PROBE_TOKEN')) { $config[$key]=New-LocalSecret }
Assert-LocalConfig $config $images
Save-EnvFile $file $config
Write-Host 'Created local credentials. Keep a private backup while volumes exist.'
