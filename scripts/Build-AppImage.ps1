#requires -Version 7.0
param([ValidateSet('registry','gateway','parking-service')][string]$Module='parking-service',[switch]$UseVerifiedBuild)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Common.ps1')
if (!$UseVerifiedBuild) { & (Join-Path $PSScriptRoot 'Build-Backend.ps1') }
$buildPath=Join-Path $root '.runtime/backend-build.json'
if (!(Test-Path $buildPath)) { throw 'No verified build record. Run Build-Backend.ps1.' }
$build=Get-Content $buildPath -Raw | ConvertFrom-Json -AsHashtable
$source=Get-BuildSource $root
if ($build.status -ne 'PASS' -or $source.fingerprint -ne $build.source.fingerprint) { throw 'Build record is failed or source has changed. Refusing stale JAR packaging.' }
$artifact=$build.artifacts[$Module]
$jar=Join-Path $root $artifact.path
if (!(Test-Path $jar) -or (Get-FileHash $jar).Hash.ToLowerInvariant() -ne $artifact.sha256) { throw 'JAR does not match the verified build.' }
$images=Get-Content (Join-Path $root 'deploy/images.lock.json') -Raw | ConvertFrom-Json -AsHashtable
if ($images.javaRuntime -notmatch '@sha256:[a-f0-9]{64}$') { throw 'Java image must be locked to a digest.' }
$tag="community-parking/$Module`:dev"
$runDir=Join-Path $root ".runtime/builds/$($build.id)"
$attemptId=[Guid]::NewGuid().ToString('N')
$attemptPath=Join-Path $runDir "image-attempts/$Module-$attemptId.json"
$logPath=Join-Path $runDir "image-attempts/$Module-$attemptId.log"
$result=@{status='RUNNING';module=$Module;buildId=$build.id;attemptId=$attemptId;log=$logPath;createdAt=[DateTime]::UtcNow.ToString('o')}
Write-AtomicJson $attemptPath $result
Write-AtomicJson (Join-Path $runDir "$Module-image.json") $result
Push-Location $root
try {
    docker build -f deploy/Dockerfile --build-arg "JAVA_RUNTIME_IMAGE=$($images.javaRuntime)" --build-arg "MODULE=$Module" `
        --label "org.opencontainers.image.revision=$($source.commit)" --label "cn.community.source=$($source.fingerprint)" `
        --label "cn.community.jar=$($artifact.sha256)" -t $tag . *> $logPath
    if ($LASTEXITCODE -ne 0) { throw "Image build failed. See $logPath" }
    if ((Get-BuildSource $root).fingerprint -ne $source.fingerprint -or (Get-FileHash $jar).Hash.ToLowerInvariant() -ne $artifact.sha256) { throw 'Inputs changed while building image; result must not be accepted.' }
    $imageId=docker image inspect $tag --format '{{.Id}}'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect built image.' }
    $result=@{status='PASS';module=$Module;buildId=$build.id;attemptId=$attemptId;log=$logPath;source=$source;jarSha256=$artifact.sha256;imageId=$imageId;tag=$tag;runtime=$images.javaRuntime;dockerfileSha256=(Get-FileHash deploy/Dockerfile).Hash;createdAt=$result.createdAt}
    Write-Host "Built $tag from verified build $($build.id): $imageId"
} catch {
    $result.status='FAIL'
    $result.error=$_.Exception.GetType().Name
    throw
} finally {
    $result.endedAt=[DateTime]::UtcNow.ToString('o')
    Write-AtomicJson $attemptPath $result
    Write-AtomicJson (Join-Path $runDir "$Module-image.json") $result
    Pop-Location
}
