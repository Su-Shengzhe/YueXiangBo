param([ValidateSet('registry','gateway','parking-service')][string]$Module='parking-service')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$images=Get-Content (Join-Path $root 'deploy/images.lock.json') -Raw | ConvertFrom-Json
if (!$images.javaRuntime) { throw 'Java runtime image digest is not locked.' }
Push-Location $root
try {
    docker build -f deploy/Dockerfile --build-arg "JAVA_RUNTIME_IMAGE=$($images.javaRuntime)" --build-arg "MODULE=$Module" -t "community-parking/$Module`:dev" .
    if ($LASTEXITCODE -ne 0) { throw 'Application image build failed.' }
} finally { Pop-Location }
