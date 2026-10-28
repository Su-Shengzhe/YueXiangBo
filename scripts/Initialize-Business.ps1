#requires -Version 7.0
param([switch]$RepairMovedPaths)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Common.ps1"
. "$PSScriptRoot/Business-Paths.ps1"
$envFile=Join-Path $root 'deploy/.env.business'
$private=Join-Path $root '.runtime/business'
$null=New-Item -ItemType Directory -Force "$private/secrets","$private/init"
if (!(Test-Path $envFile)) {
    Assert-NoExistingVolumes 'community-parking-business'
    $config=@{}
    foreach($name in @('MYSQL_ROOT_PASSWORD','IDENTITY_DB_PASSWORD','PARKING_DB_PASSWORD','TRADE_DB_PASSWORD','REDIS_PASSWORD','RABBITMQ_PASSWORD','SERVICE_TOKEN','DEMO_DRIVER_PASSWORD','DEMO_DRIVER2_PASSWORD','DEMO_OWNER_PASSWORD','DEMO_PROPERTY_PASSWORD','DEMO_ADMIN_PASSWORD')) { $config[$name]=New-LocalSecret }
    $images=Get-Content "$root/deploy/images.lock.json" -Raw | ConvertFrom-Json -AsHashtable
    $config.MYSQL_IMAGE=$images.mysql; $config.REDIS_IMAGE=$images.redis; $config.RABBITMQ_IMAGE=$images.rabbitmq
    $config.JWT_PUBLIC_FILE=("$private/secrets/public.pem").Replace('\','/')
    $config.JWT_PRIVATE_FILE=("$private/secrets/private.pem").Replace('\','/')
    $config.BUSINESS_CLOCK_FILE=("$private/clock.txt").Replace('\','/')
    $config.REGISTRY_PORT='9761'
    $rsa=[Security.Cryptography.RSA]::Create(2048)
    try {
        [IO.File]::WriteAllText($config.JWT_PRIVATE_FILE,$rsa.ExportPkcs8PrivateKeyPem())
        [IO.File]::WriteAllText($config.JWT_PUBLIC_FILE,$rsa.ExportSubjectPublicKeyInfoPem())
    } finally { $rsa.Dispose() }
    [IO.File]::WriteAllText($config.BUSINESS_CLOCK_FILE,'0')
    Save-EnvFile $envFile $config
} else {
    $config=Read-LocalConfig $envFile
    # Resolve every original file before changing configuration.
    $config=Resolve-BusinessPaths $config $root -RepairMovedPaths:$RepairMovedPaths
}
$persisted=$config.Clone()
foreach($key in @('JWT_PRIVATE_FILE','JWT_PUBLIC_FILE','BUSINESS_CLOCK_FILE')) {
    $relative=[IO.Path]::GetRelativePath($root,$config[$key]).Replace('\','/')
    if($relative.StartsWith('.runtime/business/')){$persisted[$key]=$relative}
}
Copy-Item -LiteralPath $envFile -Destination (Join-Path $private ('env-backup-'+[Guid]::NewGuid().ToString('N')+'.private'))
Save-EnvFile $envFile $persisted
$sql=@()
foreach($name in @('identity','parking','trade')){
    $secret=$config[$name.ToUpper()+'_DB_PASSWORD']
    if($secret -notmatch '^[a-f0-9]{64}$'){throw 'Invalid database secret'}
    $sql+="CREATE DATABASE IF NOT EXISTS $($name)_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    $sql+="CREATE USER IF NOT EXISTS '$name'@'%' IDENTIFIED BY '$secret';"
    $sql+="GRANT ALL PRIVILEGES ON $($name)_db.* TO '$name'@'%';"
}
[IO.File]::WriteAllLines("$private/init/001-databases.sql",$sql)
# Existing clock offsets must never be reset during initialization.
[IO.File]::WriteAllText("$root/frontend/.env.business.local",'GATEWAY_PORT=9090'+[Environment]::NewLine)
Write-Host 'Business configuration ready. Credentials: deploy/.env.business (private; never commit).'
