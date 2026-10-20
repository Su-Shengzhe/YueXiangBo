param([switch]$SkipInfrastructure)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Use-Toolchain.ps1')
. (Join-Path $PSScriptRoot 'Load-LocalConfig.ps1')
New-Item -ItemType Directory -Force (Join-Path $root '.runtime') | Out-Null
Push-Location $root
try {
    if (!$SkipInfrastructure) {
        docker compose --env-file deploy/.env.local -f deploy/compose.yaml up -d --wait --wait-timeout 180
        if ($LASTEXITCODE -ne 0) { throw 'Infrastructure failed to become healthy.' }
    }
    # Refuse to replace existing applications on any of these ports.
    foreach ($port in @(8761,8080,8082)) {
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Port $port is already in use." }
    }
    $processes = @()
    foreach ($module in @('registry','parking-service','gateway')) {
        $jar = Join-Path $root "backend/$module/target/$module-0.1.0-SNAPSHOT.jar"
        if (!(Test-Path $jar)) { throw "Build the project first: $jar" }
        $app = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') `
            -ArgumentList @('-Xms64m','-Xmx384m','-jar',"`"$jar`"",'--spring.profiles.active=local') `
            -WorkingDirectory $root -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $root ".runtime/$module.stdout.log") `
            -RedirectStandardError (Join-Path $root ".runtime/$module.stderr.log")
        $processes += @{module=$module;pid=$app.Id;startedAt=$app.StartTime.ToUniversalTime().ToString('o')}
        $processes | ConvertTo-Json -AsArray | Set-Content -Encoding utf8 .runtime/processes.json
    }
    Write-Host 'Local applications started. Use scripts/Invoke-Smoke.ps1 to wait for readiness and verify.'
} finally { Pop-Location }
