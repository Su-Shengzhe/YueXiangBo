#requires -Version 7.0
param([long]$SpaceId=20)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Common.ps1"
$config=Read-LocalConfig (Join-Path $root 'deploy/.env.business')
$login=Invoke-RestMethod 'http://127.0.0.1:9090/api/v1/auth/login' -Method Post -ContentType 'application/json' -Body (@{username='admin';password=$config.DEMO_ADMIN_PASSWORD}|ConvertTo-Json) -TimeoutSec 15
$result=Invoke-RestMethod "http://127.0.0.1:9090/api/v1/demo/windows?spaceId=$SpaceId" -Method Post -Headers @{Authorization=('Bearer '+$login.data.accessToken)} -TimeoutSec 15
$result.data | ConvertTo-Json
