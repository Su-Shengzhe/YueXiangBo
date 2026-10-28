#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Use-Toolchain.ps1"
$config=Read-LocalConfig "$root/deploy/.env.business"
. "$PSScriptRoot/Business-Paths.ps1"
$config=Resolve-BusinessPaths $config $root
$state=Join-Path $root '.runtime/business/processes.json'
$result=@{status='RUNNING';checks=@();realAiAccepted=$false}
$directory=Join-Path $root ('.runtime/business/recovery/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$null=New-Item -ItemType Directory -Force $directory
function Call-Business {
    param([string]$Path,[string]$Token,$Body=$null,[string]$Key='')
    $headers=@{}
    if($Token){$headers.Authorization="Bearer $Token"}
    if($Key){$headers['Idempotency-Key']=$Key}
    $args=@{Uri="http://127.0.0.1:9090/api/v1$Path";Headers=$headers;TimeoutSec=10;SkipHttpErrorCheck=$true}
    if($null -ne $Body){$args.Method='POST';$args.ContentType='application/json';$args.Body=ConvertTo-Json $Body -Compress}
    $response=Invoke-WebRequest @args
    return @{status=[int]$response.StatusCode;body=($response.Content | ConvertFrom-Json)}
}
function Success($response) {
    if($response.body.code -ne 'OK'){throw "Business request failed: $($response.status) $($response.body.code)"}
    return $response.body.data
}
function Await-Value([scriptblock]$Get,[scriptblock]$Accept) {
    $until=[DateTime]::UtcNow.AddSeconds(25)
    while([DateTime]::UtcNow -lt $until){$value=&$Get;if(&$Accept $value){return $value};Start-Sleep -Milliseconds 500}
    throw 'Recovery condition timed out'
}
function Stop-Module([string]$Name) {
    $records=@(Get-Content $state -Raw | ConvertFrom-Json)
    $target=@($records | Where-Object {$_.module -eq $Name})
    if($target.Count -ne 1 -or !(Test-OwnedProcess $target[0])){throw "Owned service unavailable: $Name"}
    Stop-OwnedProcesses $target
    Write-AtomicJson $state @($records | Where-Object {$_.module -ne $Name})
}
try {
    $driver=(Success (Call-Business '/auth/login' '' @{username='driver';password=$config.DEMO_DRIVER_PASSWORD})).accessToken
    $n=[DateTimeOffset]::Parse((Success (Call-Business '/demo/clock' $driver)).now)
    $start=[DateTimeOffset]::FromUnixTimeSeconds([long]([Math]::Ceiling(($n.ToUnixTimeSeconds()+7200)/900)*900))
    $body=@{vehicleId='40';spaceId='20';startAt=$start.ToString('o');endAt=$start.AddMinutes(30).ToString('o')}
    $order=Success (Call-Business '/reservations' $driver $body ([Guid]::NewGuid().ToString()))
    $id=$order.reservationId
    $null=Await-Value {Call-Business "/bills/$id" $driver} {param($v) $v.body.code -eq 'OK'}
    # Stop only this project's registered process. Queued payment remains durable.
    Stop-Module 'parking-service'
    $attempt=Success (Call-Business '/payments' $driver @{reservationId=$id;kind='PREPAY'} ([Guid]::NewGuid().ToString()))
    $null=Await-Value {Call-Business "/payments/$($attempt.id)" $driver} {param($v) $v.body.data.status -eq 'SUCCESS'}
    # Advance the isolated demo offset while parking is offline, without altering JWT time.
    $offset=[long](Get-Content -LiteralPath $config.BUSINESS_CLOCK_FILE -Raw)
    $temporary=$config.BUSINESS_CLOCK_FILE+'.recovery.tmp'
    [IO.File]::WriteAllText($temporary,($offset+660000).ToString())
    Move-Item -LiteralPath $temporary -Destination $config.BUSINESS_CLOCK_FILE -Force
    & "$PSScriptRoot/Start-Business.ps1" -SkipInfrastructure
    $null=Await-Value {Call-Business "/reservations/$id" $driver} {param($v) $v.body.data.status -eq 'EXPIRED'}
    $refund=Await-Value {Call-Business "/bills/$id" $driver} {param($v) $v.body.data.refundFen -eq 250}
    if(@($refund.body.data.attempts | Where-Object kind -eq REFUND).Count -ne 1){throw 'Duplicate refund'}
    $result.checks+=@{name='Late payment after parking restart refunds without reviving reservation';status='PASS';reservationId=$id}
    Stop-Module 'identity-service'
    $blocked=Call-Business '/vehicles' $driver
    if($blocked.status -ne 503 -or $blocked.body.code -ne 'IDENTITY_UNAVAILABLE'){throw 'Protected request did not fail closed'}
    & "$PSScriptRoot/Start-Business.ps1" -SkipInfrastructure
    $null=Success (Call-Business '/vehicles' $driver)
    $result.checks+=@{name='Identity outage fails closed; existing session works after recovery';status='PASS'}
    $result.status='PASS'
} catch {
    $result.status='FAIL';$result.error=$_.Exception.Message
    throw
} finally {
    try { & "$PSScriptRoot/Start-Business.ps1" -SkipInfrastructure } catch { $result.restoreError=$_.Exception.Message; $result.status='FAIL' }
    $result.at=[DateTime]::UtcNow.ToString('o')
    Write-AtomicJson "$directory/result.json" $result
    Write-Host "Recovery result: $($result.status). $directory"
    if($result.restoreError){throw "Business services were not restored. See $directory/result.json"}
}
