#requires -Version 7.0
param([switch]$UseVerifiedBuild)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Common.ps1')
if (!$UseVerifiedBuild) { & (Join-Path $PSScriptRoot 'Build-Backend.ps1') }
foreach($module in @('registry','gateway','parking-service')) { & (Join-Path $PSScriptRoot 'Build-AppImage.ps1') -Module $module -UseVerifiedBuild }
$id=[Guid]::NewGuid().ToString('N').Substring(0,12)
$project="community-parking-check-$id"
$runDir=Join-Path $root ".runtime/container-checks/$id"
$null=New-Item -ItemType Directory -Force $runDir
$images=Get-Content (Join-Path $root 'deploy/images.lock.json') -Raw | ConvertFrom-Json -AsHashtable
$config=@{MYSQL_IMAGE=$images.mysql;REDIS_IMAGE=$images.redis;RABBITMQ_IMAGE=$images.rabbitmq}
foreach($key in @('MYSQL_ROOT_PASSWORD','PARKING_DB_PASSWORD','REDIS_PASSWORD','RABBITMQ_PASSWORD','PROBE_TOKEN')){$config[$key]=New-LocalSecret}
$listeners=@()
try {
    foreach($key in @('MYSQL_PORT','REDIS_PORT','RABBITMQ_PORT','RABBITMQ_UI_PORT','REGISTRY_PORT','GATEWAY_PORT','PARKING_PORT')) {
        $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$listener.Start()
        $listeners+=$listener
        $config[$key]="$($listener.LocalEndpoint.Port)"
    }
} finally { foreach($listener in $listeners){$listener.Stop()} }
Assert-LocalConfig $config $images
$envFile=Join-Path $runDir '.env.check'
Save-EnvFile $envFile $config
$previous=@{}
foreach($key in $config.Keys){$previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$config[$key],'Process')}
$compose=@('compose','-p',$project,'--env-file',$envFile,'-f',(Join-Path $root 'deploy/compose.yaml'),'-f',(Join-Path $root 'deploy/compose.apps.yaml'))
$report=@{id=$id;project=$project;status='RUNNING';startedAt=[DateTime]::UtcNow.ToString('o');cleanup='PENDING'}
try {
    # The unique project must have no pre-existing volumes or containers.
    Assert-NoExistingVolumes $project
    & docker @compose up -d *> (Join-Path $runDir 'compose-up.log')
    if($LASTEXITCODE -ne 0){throw 'Container startup failed.'}
    & (Join-Path $PSScriptRoot 'Invoke-Smoke.ps1') -ConfigPath $envFile -GatewayUrl "http://127.0.0.1:$($config.GATEWAY_PORT)" -ParkingUrl "http://127.0.0.1:$($config.PARKING_PORT)" -ReadyTimeoutSeconds 180
    $smoke=Get-Content (Join-Path $root '.runtime/smoke-result.json') -Raw | ConvertFrom-Json
    $report.smokeRunId=$smoke.id
    # Remove the probe profile and recreate only the verification applications.
    $override=Join-Path $runDir 'probe-off.yaml'
    "services:`n  parking-service:`n    environment:`n      SPRING_PROFILES_ACTIVE: container`n  gateway:`n    environment:`n      SPRING_PROFILES_ACTIVE: container" | Set-Content $override
    & docker @compose -f $override up -d parking-service gateway *> (Join-Path $runDir 'probe-off.log')
    if($LASTEXITCODE -ne 0){throw 'Cannot disable probe profile.'}
    Wait-HttpReady "http://127.0.0.1:$($config.PARKING_PORT)/actuator/health" -TimeoutSeconds 90
    Wait-HttpReady "http://127.0.0.1:$($config.GATEWAY_PORT)/actuator/health" -TimeoutSeconds 90
    foreach($uri in @("http://127.0.0.1:$($config.PARKING_PORT)/api/dev/probes","http://127.0.0.1:$($config.PARKING_PORT)/internal/environment-probe","http://127.0.0.1:$($config.GATEWAY_PORT)/api/dev/probes")){
        $response=Invoke-WebRequest $uri -Headers @{'X-Probe-Token'=$config.PROBE_TOKEN} -SkipHttpErrorCheck -TimeoutSec 5
        if($response.StatusCode -ne 404){throw 'Probe endpoint remains exposed without probe profile.'}
    }
    $report.probeDisabled='PASS'
    $report.status='PASS'
    Write-Host "Clean-volume container verification passed: $project"
} catch {
    $report.status='FAIL'
    $report.error=$_.Exception.GetType().Name
    throw
} finally {
    & docker @compose logs --no-color *> (Join-Path $runDir 'services.log')
    # Only this UUID-named test project is cleaned. Never remove the development volumes.
    & docker @compose down --volumes --remove-orphans *> (Join-Path $runDir 'cleanup.log')
    $report.cleanup=if($LASTEXITCODE -eq 0){'PASS'}else{'FAIL'}
    if($report.cleanup -ne 'PASS'){$report.status='FAIL'}
    $report.endedAt=[DateTime]::UtcNow.ToString('o')
    Write-AtomicJson (Join-Path $runDir 'result.json') $report
    foreach($key in $previous.Keys){[Environment]::SetEnvironmentVariable($key,$previous[$key],'Process')}
    if($report.cleanup -ne 'PASS'){throw "Test project cleanup failed; inspect $runDir/cleanup.log"}
}
