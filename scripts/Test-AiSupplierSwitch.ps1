#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Common.ps1"
$config=Read-LocalConfig "$root/deploy/.env.business"
$base='http://127.0.0.1:9090/api/v1'
function Login($name){(Invoke-RestMethod "$base/auth/login" -Method Post -ContentType application/json -Body (@{username=$name;password=$config[('DEMO_'+$name.ToUpper()+'_PASSWORD')]}|ConvertTo-Json)).data.accessToken}
$admin=@{Authorization='Bearer '+(Login 'admin')};$driver=@{Authorization='Bearer '+(Login 'driver')}
function Get-State{(Invoke-RestMethod "$base/admin/ai-gateway" -Headers $admin).data}
function Post($path,$body){(Invoke-RestMethod "$base$path" -Headers $admin -Method Post -ContentType application/json -Body ($body|ConvertTo-Json -Depth 15) -TimeoutSec 25).data}
$initial=Get-State;$original=$initial.route.providerRevisionId
if(!$original){throw 'No original active supplier'}
$originalProvider=$initial.providers|Where-Object id -eq $original|Select-Object -First 1
if(!$originalProvider.checkedAt -or ([DateTimeOffset]::UtcNow-[DateTimeOffset]::Parse($originalProvider.checkedAt)).TotalSeconds -gt 480){Post "/admin/ai-gateway/providers/$original/check" @{}|Out-Null}
$pidsBefore=Get-Content "$root/.runtime/business/processes.json" -Raw
$evidence=@{startedAt=[DateTime]::UtcNow.ToString('o');original=$original;steps=@();status='RUNNING'}
$dir=Join-Path $root ('.runtime/business/supplier-switch/'+(Get-Date -Format 'yyyyMMdd-HHmmss'));New-Item -ItemType Directory -Force $dir | Out-Null
$bill=(Invoke-RestMethod "$base/reservations" -Headers $driver).data|Where-Object {$_.ruleSnapshot -and $_.status -in 'CONFIRMED','COMPLETED'}|Select-Object -First 1
if(!$bill){throw 'A stable driver order with a rule snapshot is required'}
function Ask($expectedVersion){
 $session=(Invoke-RestMethod "$base/ai/sessions" -Headers $driver -Method Post -ContentType application/json -Body '{}').data
 try{$headers=@{Authorization=$driver.Authorization;'Idempotency-Key'=[Guid]::NewGuid().ToString()}
 $answer=(Invoke-RestMethod "$base/ai/sessions/$($session.sessionId)/messages" -Headers $headers -Method Post -ContentType application/json -Body (@{text='解释本人账单';context=@{reservationId=$bill.reservationId};expectedSessionVersion=0}|ConvertTo-Json) -TimeoutSec 25).data
 if($answer.status -ne 'ANSWERED' -or $answer.routeVersion -ne $expectedVersion){$evidence.status='FAIL'}
 return @{requestId=$answer.requestId;routeVersion=$answer.routeVersion;status=$answer.status;fallbackReason=$answer.fallbackReason;expectedRouteVersion=$expectedVersion}
 }finally{Invoke-RestMethod "$base/ai/sessions/$($session.sessionId)" -Headers $driver -Method Delete | Out-Null}
}
try{
 $evidence.steps+=Ask $initial.route.version
 $provider=$initial.providers|Where-Object {$_.baseUrl -eq 'https://api.deepseek.com' -and $_.modelName -eq 'deepseek-flash' -and $_.paidScope -eq 'DEEPSEEK_ACCEPTANCE'}|Select-Object -First 1
 if(!$provider){$provider=Post '/admin/ai-gateway/providers' @{providerName='DeepSeek';protocol='OPENAI_CHAT';baseUrl='https://api.deepseek.com';modelName='deepseek-flash';credentialRef='deepseek.properties';inputPriceMicroyuanPerMillion=2000000;outputPriceMicroyuanPerMillion=8000000;priceVersion='deepseek-cny-peak-20261110';freeAccessApproved=$false}}
 Post "/admin/ai-gateway/providers/$($provider.id)/check" @{} | Out-Null
 $state=Get-State;Post '/admin/ai-gateway/publish' @{version=$state.route.version;providerRevisionId=$provider.id}|Out-Null
 $evidence.steps+=Ask (Get-State).route.version
}catch{$evidence.status='FAIL';$evidence.error=$_.Exception.Message;throw}
finally{
 try{$current=Get-State;if($current.route.providerRevisionId -ne $original){$current=Get-State;Post '/admin/ai-gateway/publish' @{version=$current.route.version;providerRevisionId=$original}|Out-Null};$evidence.steps+=Ask (Get-State).route.version
 if((Get-Content "$root/.runtime/business/processes.json" -Raw) -ne $pidsBefore){throw 'Business process ownership record changed'}
 if($evidence.status -eq 'RUNNING'){$evidence.status='PASS'}
 }catch{$evidence.status='FAIL';$evidence.restoreError=$_.Exception.Message;Write-Warning 'Restoration or verification failed; inspect active supplier before further use'}
 $evidence|ConvertTo-Json -Depth 15|Set-Content "$dir/results.json" -Encoding utf8
 Write-Output "$($evidence.status): $dir"
}
if($evidence.status -ne 'PASS'){throw 'Supplier switch acceptance failed'}
