# Dot-source this file: . .\scripts\Use-Toolchain.ps1
$projectRoot = Split-Path $PSScriptRoot -Parent
$jdkDir = Get-ChildItem (Join-Path $projectRoot '.tools/jdk') -Directory | Select-Object -First 1
$nodeDir = Get-ChildItem (Join-Path $projectRoot '.tools/node') -Directory | Select-Object -First 1
$mavenDir = Get-ChildItem (Join-Path $projectRoot '.tools/maven') -Directory | Select-Object -First 1
if (!$jdkDir -or !$nodeDir -or !$mavenDir) { throw 'Run scripts/Install-Toolchain.ps1 first.' }
$env:JAVA_HOME = $jdkDir.FullName
$env:MAVEN_USER_HOME = Join-Path $projectRoot '.tools/maven-user'
$env:npm_config_cache = Join-Path $projectRoot '.tools/npm-cache'
$env:npm_config_prefix = Join-Path $projectRoot '.tools/npm-global'
$env:PATH = "$($jdkDir.FullName)\bin;$($nodeDir.FullName);$($mavenDir.FullName)\bin;$env:PATH"
