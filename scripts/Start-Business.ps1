#requires -Version 7.0
param([switch]$SkipInfrastructure,[ValidateRange(5,300)][int]$ReadyTimeoutSeconds=90)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Use-Toolchain.ps1')
. (Join-Path $PSScriptRoot 'Business-Paths.ps1')
$config=Resolve-BusinessPaths (Read-LocalConfig (Join-Path $root 'deploy/.env.business')) $root
foreach($entry in $config.GetEnumerator()){[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
# This launcher explicitly opts into local demo data and clock controls.
$env:BUSINESS_DEMO='true'
$null=New-Item -ItemType Directory -Force (Join-Path $root '.runtime')
$guard=[IO.File]::Open((Join-Path $root '.runtime/business/process-control.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
$runId=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$runDir=Join-Path $root ".runtime/business/startups/$runId"
$null=New-Item -ItemType Directory -Force $runDir
$stateFile=Join-Path $root '.runtime/business/processes.json'
$existing=@()
$newProcesses=@()
$run=@{id=$runId;startedAt=[DateTime]::UtcNow.ToString('o');status='RUNNING';created=@();reused=@();error=$null}
Push-Location $root
try {
    $build=Get-Content (Join-Path $root '.runtime/backend-build.json') -Raw | ConvertFrom-Json
    if($build.status -ne 'PASS' -or $build.source.fingerprint -ne (Get-BuildSource $root).fingerprint){throw 'Backend build is missing or stale. Run Build-Backend.ps1.'}
    $modules=@(@{name='registry';port=9761},@{name='identity-service';port=9081},@{name='parking-service';port=9082},@{name='trade-service';port=9083},@{name='gateway';port=9090})
    $records=if(Test-Path $stateFile){@(Get-Content $stateFile -Raw | ConvertFrom-Json)}else{@()}
    # Preserve every still-owned process even if an early precheck fails.
    $existing=@($records | Where-Object { Test-OwnedProcess $_ })
    foreach ($module in $modules) {
        $jar=Join-Path $root "backend/$($module.name)/target/$($module.name)-0.1.0-SNAPSHOT.jar"
        if (!(Test-Path $jar)) { throw "Missing JAR for $($module.name). Build before starting." }
        $module.jar=$jar
        if((Get-FileHash $jar).Hash.ToLowerInvariant() -ne $build.artifacts.($module.name).sha256){throw "Unverified JAR: $($module.name)"}
        $module.jarSha256=(Get-FileHash $jar).Hash
        $record=$records | Where-Object { $_.module -eq $module.name -and (Test-OwnedProcess $_) } | Select-Object -First 1
        if ($record) {
            if ($record.jarSha256 -ne $module.jarSha256) { throw "$($module.name) runs a different/unrecorded JAR. Stop-Business.ps1 before restarting." }
            Wait-HttpReady "http://127.0.0.1:$($module.port)/actuator/health" -TimeoutSeconds 5 -ProcessId $record.pid
            $run.reused+=$module.name
            $module.reuse=$true
        } elseif (Get-NetTCPConnection -State Listen -LocalPort $module.port -ErrorAction SilentlyContinue) {
            throw "Port $($module.port) is occupied by an unowned process; nothing was started."
        }
    }
    # All application prechecks precede any infrastructure mutation.
    docker compose --env-file deploy/.env.business -f deploy/compose.business.yaml config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration validation failed.' }
    if (!$SkipInfrastructure) {
        docker compose --env-file deploy/.env.business -f deploy/compose.business.yaml up -d --wait --wait-timeout 180
        if ($LASTEXITCODE -ne 0) { throw 'Infrastructure readiness failed; volumes are preserved.' }
    }
    foreach ($module in $modules) {
        if ($module.reuse) { continue }
        $runtimeJar=Join-Path $runDir "$($module.name).jar"
        Copy-Item -LiteralPath $module.jar -Destination $runtimeJar
        $app=Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') `
            -ArgumentList @('-Xms64m','-Xmx384m','-jar',"`"$runtimeJar`"",'--spring.profiles.active=local,business') `
            -WorkingDirectory $root -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $runDir "$($module.name).stdout.log") `
            -RedirectStandardError (Join-Path $runDir "$($module.name).stderr.log")
        $record=@{module=$module.name;pid=$app.Id;startedAt=$app.StartTime.ToUniversalTime().ToString('o');jarSha256=$module.jarSha256;runId=$runId}
        $newProcesses+=$record
        $run.created+=$record
        Write-AtomicJson $stateFile @($existing+$newProcesses)
        Wait-HttpReady "http://127.0.0.1:$($module.port)/actuator/health" -TimeoutSeconds $ReadyTimeoutSeconds -ProcessId $app.Id
    }
    Wait-HttpReady "http://127.0.0.1:9090/api/v1/communities" -TimeoutSeconds $ReadyTimeoutSeconds
    $run.status='PASS'
    Write-Host "Applications and gateway route ready. Reused: $($run.reused.Count). Logs: $runDir"
} catch {
    $run.status='FAIL'
    $run.error=$_.Exception.Message
    Stop-OwnedProcesses $newProcesses
    Write-AtomicJson $stateFile @($existing)
    throw "Startup failed; this run's Java processes were stopped. Logs: $runDir. $($run.error)"
} finally {
    $run.endedAt=[DateTime]::UtcNow.ToString('o')
    Write-AtomicJson (Join-Path $runDir 'result.json') $run
    Pop-Location
    $guard.Dispose()
}
