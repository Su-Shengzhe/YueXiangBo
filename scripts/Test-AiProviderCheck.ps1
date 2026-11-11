#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Common.ps1"
$config=Read-LocalConfig "$root/deploy/.env.business"
$base='http://127.0.0.1:9090/api/v1'
$auth=Invoke-RestMethod "$base/auth/login" -Method Post -ContentType application/json -Body (@{username='admin';password=$config.DEMO_ADMIN_PASSWORD}|ConvertTo-Json)
$headers=@{Authorization='Bearer '+$auth.data.accessToken}
$before=(Invoke-RestMethod "$base/admin/ai-gateway" -Headers $headers).data
$id=$before.route.providerRevisionId
if(!$id){throw 'No active provider; configure an approved provider first'}
$report=@{startedAt=[DateTime]::UtcNow.ToString('o');providerRevisionId=$id;routeBefore=$before.route;status='RUNNING'}
$dir=Join-Path $root ('.runtime/business/provider-check/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $dir | Out-Null
try{
 $report.check=(Invoke-RestMethod "$base/admin/ai-gateway/providers/$id/check" -Headers $headers -Method Post -ContentType application/json -Body '{}' -TimeoutSec 25).data
 $after=(Invoke-RestMethod "$base/admin/ai-gateway" -Headers $headers).data
 if($after.route.version -ne $before.route.version){throw 'Capability check changed the active route'}
 $report.budget=$after.budget;$report.status='PASS'
 Write-Output "PASS provider capability check; active route unchanged. Evidence: $dir"
}catch{$report.status='FAIL';$report.errorType=$_.Exception.GetType().Name;throw}finally{$report|ConvertTo-Json -Depth 12|Set-Content (Join-Path $dir 'result.json') -Encoding utf8}
