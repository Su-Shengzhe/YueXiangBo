#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$reportDir=Join-Path $root ('.runtime/business/channel-faults/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$null=New-Item -ItemType Directory -Force $reportDir
$originalOptions=$env:JAVA_TOOL_OPTIONS
$report=@{status='RUNNING';scenario='durable-success-with-lost-submit-response';checks=@()}
function Sql([string]$sql){
    $rows=$sql | docker compose --env-file deploy/.env.business -f deploy/compose.business.yaml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot trade_db --batch --skip-column-names'
    if($LASTEXITCODE -ne 0){throw 'Channel verification SQL failed'}
    return $rows
}
Push-Location $root
try {
    ./scripts/Stop-Business.ps1
    $env:JAVA_TOOL_OPTIONS=($originalOptions+' -Dbusiness.channel.lose-submit-response=true').Trim()
    ./scripts/Start-Business.ps1
    $start=Sql 'SELECT CURRENT_TIMESTAMP(3);'
    ./scripts/Test-Business.ps1
    $lost=[int](Sql "SELECT COUNT(*) FROM channel_call WHERE started_at>='$start' AND operation='SUBMIT' AND error_code='CHANNEL_RESPONSE_LOST';")
    $recovered=[int](Sql "SELECT COUNT(DISTINCT c.channel_no) FROM channel_call c JOIN channel_call q ON q.channel_no=c.channel_no AND q.operation='QUERY' AND q.outcome='SUCCESS' JOIN ledger_entry l ON l.channel_no=c.channel_no WHERE c.started_at>='$start' AND c.error_code='CHANNEL_RESPONSE_LOST';")
    $duplicate=[int](Sql "SELECT COUNT(*) FROM (SELECT channel_no FROM ledger_entry GROUP BY channel_no HAVING COUNT(*)>1) x;")
    if($lost -lt 1 -or $recovered -ne $lost -or $duplicate -ne 0){throw "Channel assertions failed: lost=$lost recovered=$recovered duplicate=$duplicate"}
    $report.checks=@(@{name='lost responses are durably recorded';count=$lost},@{name='each lost response recovered by query and posted once';count=$recovered},@{name='duplicate ledger entries';count=$duplicate})
    $report.status='PASS'
} catch {$report.status='FAIL';$report.error=$_.Exception.Message;throw}
finally {
    try {
        ./scripts/Stop-Business.ps1
        $env:JAVA_TOOL_OPTIONS=$originalOptions
        ./scripts/Start-Business.ps1
        $report.normalConfigurationRestored=$true
    } catch {$report.status='FAIL';$report.restoreError=$_.Exception.Message;throw}
    finally {
        $env:JAVA_TOOL_OPTIONS=$originalOptions
        $report | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $reportDir 'results.json') -Encoding utf8
        Pop-Location
        Write-Host "Channel response loss: $($report.status), $reportDir"
    }
}
