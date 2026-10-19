Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$helperPath = Join-Path $PSScriptRoot 't21-database-manager-init-audit-log.ps1'
if (-not (Test-Path -LiteralPath $helperPath -PathType Leaf)) {
    throw 'The database-manager initialization audit-log helper is missing.'
}
. $helperPath

$script:assertions = 0
function Assert-AuditLog([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

function New-ValidAuditLogFixture {
    $path = 'logs\platform-candidate-database-manager-init-failure.txt'
    $entry = [ordered]@{
        stage = 'candidate-database-manager-init-failure'
        path = $path
        bytes = 37
        sha256 = 'a' * 64
    }
    return @{
        report = @{ platformLogs = $entry }
        logs = @{ $path = @{ bytes = 37; sha256 = 'a' * 64 } }
    }
}

function Assert-AuditLogRejected([scriptblock]$Mutation, [string]$Message) {
    $fixture = New-ValidAuditLogFixture
    & $Mutation $fixture
    $rejected = $false
    try {
        $null = Get-T21DatabaseManagerInitAuditLogEvidence -Report $fixture.report -Logs $fixture.logs
    } catch {
        $rejected = $true
    }
    Assert-AuditLog $rejected $Message
}

$fixture = New-ValidAuditLogFixture
$selected = Get-T21DatabaseManagerInitAuditLogEvidence -Report $fixture.report -Logs $fixture.logs
Assert-AuditLog ($selected.path -ceq 'logs\platform-candidate-database-manager-init-failure.txt') 'The retained platform snapshot must be selected as the startup log.'
Assert-AuditLog ($selected.stage -ceq 'candidate-database-manager-init-failure') 'The selected log must be the database-manager initialization-failure stage.'

Assert-AuditLogRejected { param($value) $value.report.Remove('platformLogs') } 'A report without platform-log evidence must be rejected.'
Assert-AuditLogRejected { param($value) $value.report.platformLogs.stage = 'candidate-startup-failure' } 'A platform log from the wrong startup stage must be rejected.'
Assert-AuditLogRejected { param($value) $value.report.platformLogs.path = 'logs\unmanifested.log' } 'A platform log absent from the retained-log manifest must be rejected.'
Assert-AuditLogRejected { param($value) $value.report.platformLogs.path = '..\outside.log' } 'A traversal path must be rejected.'
Assert-AuditLogRejected { param($value) $value.report.platformLogs.bytes++ } 'A platform-log byte count that differs from its manifest must be rejected.'
Assert-AuditLogRejected { param($value) $value.report.platformLogs.sha256 = 'b' * 64 } 'A platform-log SHA that differs from its manifest must be rejected.'
Assert-AuditLogRejected { param($value) $value.report.platformLogs = @($value.report.platformLogs, $value.report.platformLogs) } 'Duplicate platform-log evidence must be rejected.'

$pgPrivilegePaths = @(Get-T21DatabaseManagerInitPrivilegeLogPaths -RunRoot 'C:\t21-run' -DatabaseProduct 'PostgreSQL')
Assert-AuditLog ($pgPrivilegePaths -is [array]) 'PostgreSQL privilege evidence paths must remain an array when exactly one path is returned.'
Assert-AuditLog ($pgPrivilegePaths.Count -eq 1) 'PostgreSQL must return exactly one privilege evidence path.'
Assert-AuditLog ($pgPrivilegePaths[0] -ceq 'C:\t21-run\logs\postgresql-database-manager-init-privileges.txt') 'PostgreSQL privilege evidence must preserve the full path instead of indexing its first character.'
$mysqlPrivilegePaths = @(Get-T21DatabaseManagerInitPrivilegeLogPaths -RunRoot 'C:\t21-run' -DatabaseProduct 'MySQL')
Assert-AuditLog ($mysqlPrivilegePaths.Count -eq 2) 'MySQL must return both authenticated-identity and grant evidence paths.'
Assert-AuditLog (($mysqlPrivilegePaths -join ';') -ceq 'C:\t21-run\logs\mysql-database-manager-init-authenticated-identity.txt;C:\t21-run\logs\mysql-database-manager-init-grants.txt') 'MySQL privilege evidence paths must stay product-specific.'
$allowedDelta = @(
    ' M tools/plugdev/audit-t21-database-manager-init-failure.ps1',
    ' M tools/plugdev/t21-database-manager-init-audit-log.ps1',
    ' M tools/plugdev/t21-database-manager-init-audit-log.test.ps1'
)
Assert-AuditLog (Test-T21DatabaseManagerInitAuditWorktreeDelta $allowedDelta) 'Only the post-run audit implementation and its focused test may differ from the pinned source commit.'
Assert-AuditLog (-not (Test-T21DatabaseManagerInitAuditPorcelainSeparator ' Mxtools/plugdev/t21-database-manager-init-audit-log.ps1')) 'A porcelain entry without the required separator after its two-character status must be rejected.'
Assert-AuditLog (-not (Test-T21DatabaseManagerInitAuditWorktreeDelta @(' Mxtools/plugdev/t21-database-manager-init-audit-log.ps1'))) 'A malformed porcelain separator must not be accepted as an allowed audit-source change.'
Assert-AuditLog (-not (Test-T21DatabaseManagerInitAuditWorktreeDelta @(' M src/main/java/noietime/syncmoney/storage/db/DatabaseManager.java'))) 'A product-source change must be rejected during offline report audit.'
Assert-AuditLog (-not (Test-T21DatabaseManagerInitAuditWorktreeDelta @(' D tools/plugdev/audit-t21-database-manager-init-failure.ps1'))) 'A deleted audit source must be rejected.'
Assert-AuditLog (-not (Test-T21DatabaseManagerInitAuditWorktreeDelta @('?? build/reports/unexpected.txt'))) 'An unrelated worktree change must be rejected.'

$auditSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'audit-t21-database-manager-init-failure.ps1') -Raw
Assert-AuditLog ($auditSource.Contains('Test-T21DatabaseManagerInitAuditPorcelainSeparator $_')) 'The offline auditor must validate the porcelain separator before parsing changed paths.'
$privilegePathCall = '$privilegeLogPaths = @(Get-T21DatabaseManagerInitPrivilegeLogPaths -RunRoot $runRoot -DatabaseProduct $DatabaseProduct)'
Assert-AuditLog ($auditSource.Contains($privilegePathCall)) 'The offline auditor must execute the product-specific privilege-path helper inside an array wrapper.'
Assert-AuditLog ($auditSource -match '"auditScriptSha256=\$\(Get-T21Hash \$PSCommandPath\)"') 'The offline audit result must pin the executing auditor implementation.'
Assert-AuditLog ($auditSource -match 'auditLogHelperTestSha256=.*t21-database-manager-init-audit-log\.test\.ps1') 'The offline audit result must pin the focused audit-helper regression test.'

Write-Output ("Assertions passed: {0}/{0}" -f $script:assertions)
