$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Use-Toolchain.ps1')
. (Join-Path $PSScriptRoot 'Load-LocalConfig.ps1')
Push-Location (Join-Path $root 'frontend')
try {
    npx.cmd --no-install playwright test
    if ($LASTEXITCODE -ne 0) { throw 'Browser verification failed.' }
} finally { Pop-Location }
