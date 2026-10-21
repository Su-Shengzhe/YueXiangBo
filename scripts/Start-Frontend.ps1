$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Use-Toolchain.ps1')
. (Join-Path $PSScriptRoot 'Load-LocalConfig.ps1')
Push-Location (Join-Path $root 'frontend')
try { npm.cmd run dev } finally { Pop-Location }
