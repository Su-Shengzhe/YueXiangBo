#requires -Version 7.0
param([switch]$SkipInfrastructure,[ValidateRange(5,300)][int]$ReadyTimeoutSeconds=90)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Use-Toolchain.ps1')
. (Join-Path $PSScriptRoot 'Load-LocalConfig.ps1')
$null=New-Item -ItemType Directory -Force (Join-Path $root '.runtime')
$guard=[IO.File]::Open((Join-Path $root '.runtime/process-control.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
$runId=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$runDir=Join-Path $root ".runtime/startups/$runId"
$null=New-Item -ItemType Directory -Force $runDir
$stateFile=Join-Path $root '.runtime/processes.json'
$existing=@()
$newProcesses=@()
$run=@{id=$runId;startedAt=[DateTime]::UtcNow.ToString('o');status='RUNNING';created=@();reused=@();error=$null}
Push-Location $root
try {
    $modules=@(@{name='registry';port=[int]$env:REGISTRY_PORT},@{name='parking-service';port=[int]$env:PARKING_PORT},@{name='gateway';port=[int]$env:GATEWAY_PORT})
    $records=if(Test-Path $stateFile){@(Get-Content $stateFile -Raw | ConvertFrom-Json)}else{@()}
    # Preserve every still-owned process even if an early precheck fails.
    $existing=@($records | Where-Object { Test-OwnedProcess $_ })
    foreach ($module in $modules) {
        $jar=Join-Path $root "backend/$($module.name)/target/$($module.name)-0.1.0-SNAPSHOT.jar"
        if (!(Test-Path $jar)) { throw "Missing JAR for $($module.name). Build before starting." }
        $module.jar=$jar
        $module.jarSha256=(Get-FileHash $jar).Hash
        $record=$records | Where-Object { $_.module -eq $module.name -and (Test-OwnedProcess $_) } | Select-Object -First 1
        if ($record) {
            if ($record.jarSha256 -ne $module.jarSha256) { throw "$($module.name) runs a different/unrecorded JAR. Stop-Local.ps1 before restarting." }
            Wait-HttpReady "http://127.0.0.1:$($module.port)/actuator/health" -TimeoutSeconds 5 -ProcessId $record.pid
            $run.reused+=$module.name
            $module.reuse=$true
        } elseif (Get-NetTCPConnection -State Listen -LocalPort $module.port -ErrorAction SilentlyContinue) {
            throw "Port $($module.port) is occupied by an unowned process; nothing was started."
        }
    }
    # All application prechecks precede any infrastructure mutation.
    docker compose --env-file deploy/.env.local -f deploy/compose.yaml config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration validation failed.' }
    if (!$SkipInfrastructure) {
        docker compose --env-file deploy/.env.local -f deploy/compose.yaml up -d --wait --wait-timeout 180
        if ($LASTEXITCODE -ne 0) { throw 'Infrastructure readiness failed; volumes are preserved.' }
    }
    foreach ($module in $modules) {
        if ($module.reuse) { continue }
        $runtimeJar=Join-Path $runDir "$($module.name).jar"
        Copy-Item -LiteralPath $module.jar -Destination $runtimeJar
        $app=Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') `
            -ArgumentList @('-Xms64m','-Xmx384m','-jar',"`"$runtimeJar`"",'--spring.profiles.active=local,probe') `
            -WorkingDirectory $root -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $runDir "$($module.name).stdout.log") `
            -RedirectStandardError (Join-Path $runDir "$($module.name).stderr.log")
        $record=@{module=$module.name;pid=$app.Id;startedAt=$app.StartTime.ToUniversalTime().ToString('o');jarSha256=$module.jarSha256;runId=$runId}
        $newProcesses+=$record
        $run.created+=$record
        Write-AtomicJson $stateFile @($existing+$newProcesses)
        Wait-HttpReady "http://127.0.0.1:$($module.port)/actuator/health" -TimeoutSeconds $ReadyTimeoutSeconds -ProcessId $app.Id
    }
    Wait-HttpReady "http://127.0.0.1:$env:GATEWAY_PORT/api/dev/probes" -Headers @{'X-Probe-Token'=$env:PROBE_TOKEN} -TimeoutSeconds $ReadyTimeoutSeconds
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
