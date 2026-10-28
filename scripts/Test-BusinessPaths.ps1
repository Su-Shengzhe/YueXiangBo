#requires -Version 7.0
$ErrorActionPreference='Stop'
. "$PSScriptRoot/Business-Paths.ps1"
$root=Join-Path (Split-Path $PSScriptRoot -Parent) ('.runtime/path-tests/'+[Guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Force (Join-Path $root '.runtime/business/secrets')
$config=@{JWT_PRIVATE_FILE='.runtime/business/secrets/private.pem';JWT_PUBLIC_FILE='.runtime/business/secrets/public.pem';BUSINESS_CLOCK_FILE='.runtime/business/clock.txt'}
foreach($key in $config.Keys){[IO.File]::WriteAllText((Join-Path $root $config[$key]),'unchanged-fixture')}
function Assert-Rejected([scriptblock]$Action) { $rejected=$false;try{& $Action | Out-Null}catch{$rejected=$true};if(!$rejected){throw 'Expected missing path rejection'} }
$resolved=Resolve-BusinessPaths $config $root
if(![IO.Path]::IsPathRooted($resolved.JWT_PRIVATE_FILE)){throw 'Expected absolute resolved path'}
$old=$config.Clone();foreach($key in $old.Keys.Clone()){$old[$key]='Z:/moved-project/'+$old[$key]}
Assert-Rejected {Resolve-BusinessPaths $old $root}
$repaired=Resolve-BusinessPaths $old $root -RepairMovedPaths
if($repaired.JWT_PRIVATE_FILE -ne $resolved.JWT_PRIVATE_FILE){throw 'Default relocation failed'}
$custom=$old.Clone();$custom.JWT_PRIVATE_FILE='Z:/custom/key.pem'
Assert-Rejected {Resolve-BusinessPaths $custom $root -RepairMovedPaths}
$missing=$config.Clone();$missing.BUSINESS_CLOCK_FILE='.runtime/business/missing-clock.txt'
Assert-Rejected {Resolve-BusinessPaths $missing $root}
foreach($key in $config.Keys){if([IO.File]::ReadAllText((Join-Path $root $config[$key])) -ne 'unchanged-fixture'){throw 'Original file changed'}}
Write-Host 'PASS: relative resolution, explicit relocation, implicit/custom relocation rejection, missing clock rejection, original files preserved.'
