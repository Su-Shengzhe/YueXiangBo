#requires -Version 7.0
param([string]$Image)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$envFile=Join-Path $root 'deploy/secrets/llm-gateway.env'
if(Test-Path -LiteralPath $envFile){Write-Host 'Existing gateway configuration preserved.';return}
$lockFile=Join-Path $root 'deploy/llm-image.lock.json'
if(!$Image){
    if(Test-Path -LiteralPath $lockFile){$Image=(Get-Content -LiteralPath $lockFile -Raw | ConvertFrom-Json).image}
    else{$Image='calciumion/new-api:latest'}
}
if(!$Image){throw 'Gateway image lock is invalid.'}
docker pull $Image
if($LASTEXITCODE -ne 0){throw 'Gateway image pull failed.'}
$imageInfo=docker image inspect $Image | ConvertFrom-Json
if($LASTEXITCODE -ne 0){throw 'Gateway image inspection failed.'}
$digest=@($imageInfo[0].RepoDigests | Where-Object {$_ -match '(^|/)calciumion/new-api@sha256:'}) | Select-Object -First 1
if(!$digest){throw 'No immutable New API repository digest found.'}
$secret=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
$null=New-Item -ItemType Directory -Force (Split-Path $envFile -Parent)
$content="LLM_GATEWAY_IMAGE=$digest`nLLM_SESSION_SECRET=$secret`n"
# CreateNew prevents overwriting credentials if two initializers race.
$stream=[IO.File]::Open($envFile,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
try{$bytes=[Text.Encoding]::UTF8.GetBytes($content);$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
$client=Join-Path $root 'deploy/secrets/llm-gateway.properties'
if(!(Test-Path -LiteralPath $client)){Copy-Item -LiteralPath (Join-Path $root 'deploy/llm-gateway.properties.example') -Destination $client}
@{image=$digest;initializedAt=[DateTime]::UtcNow.ToString('o');purpose='Local single-node development gateway; SQLite volume'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'deploy/llm-image.lock.json') -Encoding utf8
Write-Host "Gateway image locked: $digest. Credentials remain in deploy/secrets/."
