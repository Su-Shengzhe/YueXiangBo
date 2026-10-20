$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$file = Join-Path $root 'deploy/.env.local'
if (Test-Path $file) { Write-Host 'Existing local configuration preserved.'; return }
$images = Get-Content (Join-Path $root 'deploy/images.lock.json') -Raw | ConvertFrom-Json
$lines = @(
    "MYSQL_IMAGE=$($images.mysql)"
    "REDIS_IMAGE=$($images.redis)"
    "RABBITMQ_IMAGE=$($images.rabbitmq)"
)
foreach ($key in @('MYSQL_ROOT_PASSWORD','PARKING_DB_PASSWORD','REDIS_PASSWORD','RABBITMQ_PASSWORD','PROBE_TOKEN')) {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $secret = ([BitConverter]::ToString($bytes)).Replace('-','').ToLowerInvariant()
    $lines += "$key=$secret"
}
$lines | Set-Content -Encoding utf8 $file
Write-Host 'Created deploy/.env.local with random local credentials. Do not commit this file.'
