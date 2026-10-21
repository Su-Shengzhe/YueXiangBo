#requires -Version 7.0
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')
$root=Split-Path $PSScriptRoot -Parent
$lock=Read-ToolchainLock $root
$toolDir=Join-Path $root '.tools'
$null=New-Item -ItemType Directory -Force (Join-Path $toolDir 'downloads')
foreach ($name in @('jdk','node','maven')) {
    $entry=$lock[$name]
    $destination=Assert-ChildPath (Join-Path $toolDir "$name/$($entry.directory)") $toolDir
    $javaDirectory=Join-Path $toolDir "jdk/$($lock.jdk.directory)"
    $valid=$false
    if (Test-Path $destination) {
        try { Assert-InstalledTool $name $entry $destination $javaDirectory; $valid=$true } catch { Write-Host "$name needs repair." }
    }
    if ($valid) { Write-Host "$name matches lock: $($entry.version)"; continue }
    $zip=Join-Path $toolDir "downloads/$name-$($entry.sha256).zip"
    $legacy=Join-Path $toolDir "$name.zip"
    if (!(Test-Path $zip) -and (Test-Path $legacy) -and (Get-FileHash $legacy).Hash -eq $entry.sha256) { Copy-Item -LiteralPath $legacy -Destination $zip }
    if ((Test-Path $zip) -and (Get-FileHash $zip).Hash -ne $entry.sha256) {
        Move-Item -LiteralPath $zip -Destination "$zip.invalid-$([Guid]::NewGuid().ToString('N'))"
    }
    if (!(Test-Path $zip)) {
        for ($attempt=1; $attempt -le 3; $attempt++) {
            $partial="$zip.$([Guid]::NewGuid().ToString('N')).part"
            try {
                Invoke-WebRequest $entry.url -OutFile $partial -TimeoutSec 600
                if ((Get-FileHash $partial).Hash -ne $entry.sha256) { throw 'Downloaded archive checksum mismatch.' }
                Move-Item -LiteralPath $partial -Destination $zip
                break
            } catch {
                Write-Warning "$name download attempt $attempt failed; partial data is never treated as installed."
                if ($attempt -eq 3) { throw }
            }
        }
    }
    $staging=Assert-ChildPath (Join-Path $toolDir "staging-$name-$([Guid]::NewGuid().ToString('N'))") $toolDir
    Expand-Archive -LiteralPath $zip -DestinationPath $staging
    $extracted=Assert-ChildPath (Join-Path $staging $entry.directory) $staging
    $validationJava=if($name -eq 'jdk'){$extracted}else{$javaDirectory}
    Assert-InstalledTool $name $entry $extracted $validationJava
    if (Test-Path $destination) {
        $backup=Assert-ChildPath "$destination.backup-$([Guid]::NewGuid().ToString('N'))" $toolDir
        Move-Item -LiteralPath $destination -Destination $backup
    }
    $null=New-Item -ItemType Directory -Force (Split-Path $destination -Parent)
    Move-Item -LiteralPath $extracted -Destination $destination
    Write-AtomicJson (Join-Path $destination '.installed.json') @{version=$entry.version;sha256=$entry.sha256;installedAt=[DateTime]::UtcNow.ToString('o')}
    Write-Host "$name installed and verified."
}
. (Join-Path $PSScriptRoot 'Use-Toolchain.ps1')
Write-Host 'All installed tools and Maven Wrapper match the lock.'
