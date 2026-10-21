#requires -Version 7.0
function Write-AtomicJson {
    param([string]$Path, $Value)
    $null = New-Item -ItemType Directory -Force (Split-Path $Path -Parent)
    $temporary = "$Path.$([Guid]::NewGuid().ToString('N')).tmp"
    ConvertTo-Json -InputObject $Value -Depth 12 | Set-Content -Encoding utf8 $temporary
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Assert-ChildPath {
    param([string]$Path, [string]$Parent)
    $absolute = [IO.Path]::GetFullPath($Path)
    $prefix = [IO.Path]::GetFullPath($Parent).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if (!$absolute.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)) { throw 'Path is outside the permitted task directory.' }
    return $absolute
}

function Read-ToolchainLock {
    param([string]$Root)
    $path = Join-Path $Root 'scripts/toolchain.lock.json'
    if (!(Test-Path $path)) { throw 'toolchain.lock.json is missing. Restore the version-controlled file; installation never chooses latest versions.' }
    $lock = Get-Content $path -Raw | ConvertFrom-Json -AsHashtable
    foreach ($name in @('jdk','node','maven')) {
        $entry = $lock[$name]
        if (!$entry -or !$entry.version -or $entry.directory -notmatch '^[a-zA-Z0-9._+-]+$' -or $entry.url -notmatch '^https://') { throw "Invalid $name lock entry." }
        if ($entry.sha256 -notmatch '^[a-fA-F0-9]{64}$') { throw "Invalid $name SHA-256." }
    }
    return $lock
}

function Assert-InstalledTool {
    param([string]$Name, [hashtable]$Entry, [string]$Directory, [string]$JavaDirectory)
    switch ($Name) {
        jdk {
            $output = & (Join-Path $Directory 'bin/java.exe') -version 2>&1 | Out-String
            if ($LASTEXITCODE -ne 0 -or $output -notmatch [regex]::Escape($Entry.version)) { throw 'Installed JDK differs from lock.' }
        }
        node {
            $output = & (Join-Path $Directory 'node.exe') --version
            if ($LASTEXITCODE -ne 0 -or $output.Trim() -ne $Entry.version) { throw 'Installed Node differs from lock.' }
            $output = & (Join-Path $Directory 'node.exe') (Join-Path $Directory 'node_modules/npm/bin/npm-cli.js') --version
            if ($LASTEXITCODE -ne 0 -or $output.Trim() -ne $Entry.npm) { throw 'Installed npm differs from lock.' }
        }
        maven {
            $previous = $env:JAVA_HOME
            try {
                $env:JAVA_HOME = $JavaDirectory
                $output = & (Join-Path $Directory 'bin/mvn.cmd') --version 2>&1 | Out-String
                if ($LASTEXITCODE -ne 0 -or $output -notmatch ('Apache Maven '+[regex]::Escape($Entry.version)+'\b')) { throw 'Installed Maven differs from lock.' }
            } finally { $env:JAVA_HOME = $previous }
        }
    }
}

function Read-LocalConfig {
    param([string]$Path)
    if (!(Test-Path $Path)) { throw 'Local configuration missing. Run Initialize-Local.ps1; if volumes exist, restore the original credentials.' }
    $config = @{}
    foreach ($line in Get-Content $Path) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') { throw 'Invalid configuration line; use KEY=value without shell expressions.' }
        if ($config.ContainsKey($Matches[1])) { throw "Duplicate configuration key: $($Matches[1])" }
        $config[$Matches[1]] = $Matches[2]
    }
    return $config
}

function Assert-LocalConfig {
    param([hashtable]$Config, [hashtable]$Images, [switch]$IgnoreImageDrift)
    foreach ($key in @('MYSQL_ROOT_PASSWORD','PARKING_DB_PASSWORD','REDIS_PASSWORD','RABBITMQ_PASSWORD','PROBE_TOKEN')) {
        if (!$Config.ContainsKey($key) -or $Config[$key] -notmatch '^[a-fA-F0-9]{64}$') { throw "Missing/invalid $key. Restore or deliberately rotate credentials; values are not printed." }
    }
    foreach ($item in @{MYSQL_IMAGE='mysql'; REDIS_IMAGE='redis'; RABBITMQ_IMAGE='rabbitmq'}.GetEnumerator()) {
        if ($Images[$item.Value] -notmatch '@sha256:[a-f0-9]{64}$') { throw "Invalid locked image: $($item.Value)" }
        if (!$IgnoreImageDrift -and $Config[$item.Key] -ne $Images[$item.Value]) { throw "Image drift: $($item.Key). Run Initialize-Local.ps1 -SyncImages; credentials will be retained." }
    }
    $ports = @()
    $defaults=@{MYSQL_PORT=13306;REDIS_PORT=16379;RABBITMQ_PORT=25672;RABBITMQ_UI_PORT=25673;REGISTRY_PORT=8761;GATEWAY_PORT=8080;PARKING_PORT=8082}
    foreach ($key in $defaults.Keys) {
        $number = 0
        $value=if($Config.ContainsKey($key)){$Config[$key]}else{$defaults[$key]}
        if (![int]::TryParse("$value",[ref]$number) -or $number -lt 1024 -or $number -gt 65535) { throw "Invalid port: $key" }
        $ports += $number
    }
    if (@($ports | Sort-Object -Unique).Count -ne $ports.Count) { throw 'Configured host ports must be distinct.' }
}

function New-LocalSecret {
    return [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
}

function Save-EnvFile {
    param([string]$Path, [hashtable]$Config)
    $temporary="$Path.$([Guid]::NewGuid().ToString('N')).tmp"
    $Config.Keys | Sort-Object | ForEach-Object { "$_=$($Config[$_])" } | Set-Content -Encoding utf8 $temporary
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Assert-NoExistingVolumes {
    param([string]$Project)
    $volumes = @(docker volume ls --filter "label=com.docker.compose.project=$Project" --format '{{.Name}}' 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Docker volumes. Start Docker before initializing credentials.' }
    if ($volumes.Count) { throw 'Existing project volumes found without credentials. Restore .env.local from backup; refusing to generate replacement passwords.' }
}

function Get-BuildSource {
    param([string]$Root)
    $files = @(
        Get-Item (Join-Path $Root 'pom.xml'),(Join-Path $Root 'mvnw.cmd'),(Join-Path $Root 'mvnw')
        Get-ChildItem (Join-Path $Root '.mvn') -File -Recurse
        Get-ChildItem (Join-Path $Root 'backend') -File -Recurse | Where-Object { $_.FullName -notmatch '[\\/](target|\.tools)[\\/]' }
    ) | Sort-Object FullName
    $manifest = @($files | ForEach-Object {
        @{path=[IO.Path]::GetRelativePath($Root,$_.FullName).Replace('\','/'); sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
    $canonical = ($manifest | ForEach-Object { "$($_.path) $($_.sha256)" }) -join "`n"
    $fingerprint = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($canonical))).ToLowerInvariant()
    $head = git -C $Root rev-parse HEAD 2>$null
    if ($LASTEXITCODE -ne 0) { $head = 'UNCOMMITTED' }
    return @{commit=$head; fingerprint=$fingerprint; files=$manifest}
}

function Test-OwnedProcess {
    param($Record)
    $process = Get-Process -Id $Record.pid -ErrorAction SilentlyContinue
    return ($process -and $process.ProcessName -eq 'java' -and $process.StartTime.ToUniversalTime().Ticks -eq ([DateTimeOffset]$Record.startedAt).UtcTicks)
}

function Stop-OwnedProcesses {
    param([array]$Records)
    foreach ($record in $Records) {
        if (Test-OwnedProcess $record) {
            Stop-Process -Id $record.pid -ErrorAction Stop
            Wait-Process -Id $record.pid -Timeout 10 -ErrorAction SilentlyContinue
        }
    }
}

function Wait-HttpReady {
    param([string]$Uri, [hashtable]$Headers=@{}, [int]$TimeoutSeconds=90, [Nullable[int]]$ProcessId=$null)
    $deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if ($ProcessId -and !(Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) { throw 'Application exited before readiness. Inspect this run directory.' }
        try {
            $response=Invoke-WebRequest $Uri -Headers $Headers -TimeoutSec 3
            if ($response.StatusCode -eq 200) { return }
        } catch {}
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Readiness timeout: $Uri"
}
