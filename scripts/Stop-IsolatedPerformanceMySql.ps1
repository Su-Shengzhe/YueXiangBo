#requires -Version 7.0
param([switch]$RemoveTestData)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/Common.ps1"
$stateFile=Join-Path $root '.runtime/business/isolated-performance/current.json'
$state=Get-Content -LiteralPath $stateFile -Raw|ConvertFrom-Json -AsHashtable
if($state.container -notmatch '^yuexiangbo-perf-\d{14}$'){throw 'Unexpected container name'}
$data=[IO.Path]::GetFullPath($state.dataDirectory)
if($data -notmatch '^[A-Za-z]:\\codex-performance-yuexiangbo\\\d{14}$' -or (Split-Path $data -Leaf) -ne $state.container.Substring('yuexiangbo-perf-'.Length)){throw 'Unexpected test data path'}
$label=docker inspect --format '{{index .Config.Labels "codex.project"}}' $state.container
if($LASTEXITCODE -ne 0 -or $label -ne 'yuexiangbo-performance'){throw 'Container ownership verification failed'}
docker stop --time 30 $state.container | Out-Null
if($LASTEXITCODE -ne 0){throw 'Container did not stop'}
docker rm $state.container | Out-Null
if($LASTEXITCODE -ne 0){throw 'Container could not be removed'}
if($RemoveTestData -and (Test-Path -LiteralPath $data)){
 if((Resolve-Path -LiteralPath $data).Path -ne $data -or (Get-Item -LiteralPath $data).Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)){throw 'Test directory resolution changed'}
 Remove-Item -LiteralPath $data -Recurse -Force
}
$state.status='STOPPED';$state.removedTestData=[bool]$RemoveTestData;$state.stoppedAt=[DateTime]::UtcNow.ToString('o')
Write-AtomicJson $stateFile $state
Write-Host "Isolated performance container removed: $($state.container)"
