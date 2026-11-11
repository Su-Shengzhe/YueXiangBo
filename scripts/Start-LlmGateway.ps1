#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$config=Join-Path $root 'deploy/secrets/llm-gateway.env'
if(!(Test-Path -LiteralPath $config)){throw 'Run Initialize-LlmGateway.ps1 first.'}
docker compose --env-file $config -f (Join-Path $root 'deploy/compose.llm.yaml') config --quiet
if($LASTEXITCODE -ne 0){throw 'Gateway configuration invalid.'}
docker compose --env-file $config -f (Join-Path $root 'deploy/compose.llm.yaml') up -d
if($LASTEXITCODE -ne 0){throw 'Gateway startup failed; data volume preserved.'}
$deadline=[DateTime]::UtcNow.AddSeconds(60)
do {
    try {
        $status=Invoke-RestMethod 'http://127.0.0.1:3300/api/status' -TimeoutSec 3
        if($status.success){Write-Host 'Model gateway ready: http://127.0.0.1:3300';return}
    }catch{}
    Start-Sleep -Seconds 1
}while([DateTime]::UtcNow -lt $deadline)
throw 'Gateway did not become ready in 60 seconds. Data volume preserved.'
