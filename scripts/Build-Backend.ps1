#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Use-Toolchain.ps1')
$source=Get-BuildSource $root
$runId=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$runDir=Join-Path $root ".runtime/builds/$runId"
$null=New-Item -ItemType Directory -Force $runDir
$record=@{id=$runId;status='RUNNING';source=$source;startedAt=[DateTime]::UtcNow.ToString('o');artifacts=@{}}
$latest=Join-Path $root '.runtime/backend-build.json'
Write-AtomicJson $latest $record
Push-Location $root
try {
    & .\mvnw.cmd -B -ntp clean verify *> (Join-Path $runDir 'maven.log')
    if ($LASTEXITCODE -ne 0) { throw "Maven verification failed. See $runDir/maven.log" }
    $after=Get-BuildSource $root
    if ($after.fingerprint -ne $source.fingerprint) { throw 'Sources changed during build; rebuild before packaging.' }
    foreach ($module in @('registry','gateway','identity-service','parking-service','trade-service')) {
        $path="backend/$module/target/$module-0.1.0-SNAPSHOT.jar"
        $record.artifacts[$module]=@{path=$path;sha256=(Get-FileHash (Join-Path $root $path)).Hash.ToLowerInvariant()}
    }
    $record.status='PASS'
    Write-Host "Backend verified: $runId"
} catch {
    $record.status='FAIL'
    throw
} finally {
    $record.endedAt=[DateTime]::UtcNow.ToString('o')
    Write-AtomicJson (Join-Path $runDir 'result.json') $record
    Write-AtomicJson $latest $record
    Pop-Location
}
