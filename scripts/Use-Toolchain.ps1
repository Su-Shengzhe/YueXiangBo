#requires -Version 7.0
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')
$projectRoot=Split-Path $PSScriptRoot -Parent
$toolLock=Read-ToolchainLock $projectRoot
$toolPaths=@{}
foreach ($toolName in @('jdk','node','maven')) {
    $toolPaths[$toolName]=Join-Path $projectRoot ".tools/$toolName/$($toolLock[$toolName].directory)"
    Assert-InstalledTool $toolName $toolLock[$toolName] $toolPaths[$toolName] $toolPaths.jdk
}
$wrapper=Get-Content (Join-Path $projectRoot '.mvn/wrapper/maven-wrapper.properties') -Raw | ConvertFrom-StringData
if ($wrapper.distributionUrl -ne $toolLock.maven.url -or $wrapper.distributionSha256Sum -ne $toolLock.maven.sha256) { throw 'Maven Wrapper and toolchain lock differ.' }
$env:JAVA_HOME=$toolPaths.jdk
$env:MAVEN_USER_HOME=Join-Path $projectRoot '.tools/maven-user'
$env:npm_config_cache=Join-Path $projectRoot '.tools/npm-cache'
$env:npm_config_prefix=Join-Path $projectRoot '.tools/npm-global'
$prepend=@((Join-Path $toolPaths.jdk 'bin'),$toolPaths.node,(Join-Path $toolPaths.maven 'bin'))
$remaining=@($env:PATH -split ';' | Where-Object { $_ -and $_ -notin $prepend })
$env:PATH=($prepend+$remaining) -join ';'
