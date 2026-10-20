$projectRoot = Split-Path $PSScriptRoot -Parent
$configFile = Join-Path $projectRoot 'deploy/.env.local'
if (!(Test-Path $configFile)) { throw 'Run scripts/Initialize-Local.ps1 first.' }
foreach ($line in Get-Content $configFile) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')
    }
}
