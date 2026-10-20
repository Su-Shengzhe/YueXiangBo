$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$toolDir = Join-Path $root '.tools'
New-Item -ItemType Directory -Force $toolDir | Out-Null
$lockFile = Join-Path $PSScriptRoot 'toolchain.lock.json'
if (!(Test-Path $lockFile)) {
    $nodes = Invoke-RestMethod 'https://nodejs.org/dist/index.json'
    $node = $nodes | Where-Object { $_.version -like 'v24.*' -and $_.lts } | Select-Object -First 1
    $jdks = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $jdk = $jdks | Select-Object -First 1
    [xml]$meta = (Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml').Content
    $maven = $meta.metadata.versioning.versions.version | Where-Object { $_ -match '^3\.9\.\d+$' } | Select-Object -Last 1
    $nodeName = "node-$($node.version)-win-x64.zip"
    $sums = (Invoke-WebRequest "https://nodejs.org/dist/$($node.version)/SHASUMS256.txt").Content
    $nodeHash = (($sums -split "`n" | Where-Object { $_.Trim().EndsWith("  $nodeName") }) -split '\s+')[0]
    $mavenUrl = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$maven/apache-maven-$maven-bin.zip"
    $mavenHash = ((Invoke-WebRequest "$mavenUrl.sha512").Content).Trim()
    @{
        jdk = @{version=$jdk.version.openjdk_version; url=$jdk.binary.package.link; sha256=$jdk.binary.package.checksum}
        node = @{version=$node.version; npm=$node.npm; url="https://nodejs.org/dist/$($node.version)/$nodeName"; sha256=$nodeHash}
        maven = @{version=$maven; url=$mavenUrl; sha512=$mavenHash}
    } | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 $lockFile
}
$lock = Get-Content $lockFile -Raw | ConvertFrom-Json
foreach ($name in @('jdk','node','maven')) {
    $entry = $lock.$name
    $destination = Join-Path $toolDir $name
    if (Test-Path (Join-Path $destination '.installed')) { continue }
    $zip = Join-Path $toolDir "$name.zip"
    if (!(Test-Path $zip)) { Invoke-WebRequest $entry.url -OutFile $zip -TimeoutSec 600 }
    $algorithm = if ($name -eq 'maven') { 'SHA512' } else { 'SHA256' }
    $expected = if ($name -eq 'maven') { $entry.sha512 } else { $entry.sha256 }
    if ((Get-FileHash $zip -Algorithm $algorithm).Hash -ne $expected) { throw "$name download checksum mismatch: $zip" }
    New-Item -ItemType Directory -Force $destination | Out-Null
    Expand-Archive -LiteralPath $zip -DestinationPath $destination -Force
    Set-Content (Join-Path $destination '.installed') $entry.version
    Write-Host "$name installed: $($entry.version)"
}
. (Join-Path $PSScriptRoot 'Use-Toolchain.ps1')
java -version
node --version
mvn --version
