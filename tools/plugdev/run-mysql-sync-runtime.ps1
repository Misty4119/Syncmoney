[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ServerJar,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedServerSha256,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedCandidateSha256,
    [Parameter(Mandatory = $true)][string]$PlugDevRoot,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$RuntimeReportsRoot,
    [string]$MySqlBin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin',
    [string]$MariaDbBin = 'C:\Program Files\MariaDB 12.3\bin',
    [string]$PostgreSqlBin = 'C:\Program Files\PostgreSQL\18\bin',
    [ValidateSet('MySQL', 'MariaDB', 'PostgreSQL')][string]$DatabaseProduct = 'MySQL',
    [Alias('PaperEndpoint')]
    [ValidateSet('Minimum', 'Maximum', 'CurrentMaximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'CurrentMaximumFolia', 'MinimumCanvas', 'MaximumCanvas', 'CurrentMaximumCanvas')]
    [string]$PlatformEndpoint = 'Minimum',
    [string]$CandidateVersion = '1.4.0-alpha.1',
    [switch]$LegacySqlDataUpgrade,
    [switch]$NewerAuditSchemaStartupFailure,
    [switch]$DatabaseManagerInitFailureStartupFailure,
    [switch]$IncompleteV10AuditTableStartupFailure,
    [switch]$IncompleteV10AuditTablePlayerRowPreservation,
    [switch]$RequiredV11DdlPermissionDeniedStartupFailure,
    [switch]$SuccessfulV10AuditSchemaUpgrade,
    [switch]$SuccessfulV10AuditSchemaPlayerRowPreservation,
    [switch]$SuccessfulV10AuditSchemaMultiplePlayerRowPreservation,
    [string]$BaselinePluginJar,
    [ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedBaselinePluginSha256
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'T21 SQL runtime validation requires PowerShell 7 or newer.' }
. (Join-Path $PSScriptRoot 't21-upgrade-validation.ps1')
. (Join-Path $PSScriptRoot 't21-legacy-sql-upgrade-validation.ps1')
. (Join-Path $PSScriptRoot 't21-startup-failure-validation.ps1')
. (Join-Path $PSScriptRoot 't21-database-manager-init-failure-validation.ps1')
. (Join-Path $PSScriptRoot 't21-audit-schema-upgrade-validation.ps1')

$requestedDatabaseProduct = $DatabaseProduct
$script:databaseProduct = Resolve-T21SqlRuntimeProduct $requestedDatabaseProduct
$script:databaseServerVersion = switch ($script:databaseProduct) { 'MariaDB' { '12.3.3-MariaDB' } 'PostgreSQL' { '18.1' } default { '8.0.42' } }
$script:databaseServerVersionPattern = switch ($script:databaseProduct) { 'MariaDB' { '^12\.3\.3-MariaDB(?:\b|$)' } 'PostgreSQL' { '^18\.1(?:\b|$)' } default { '^8\.0\.42(?:\b|$)' } }
$script:databaseExecutableVersionPattern = switch ($script:databaseProduct) { 'MariaDB' { '\bVer\s+12\.3\.3-MariaDB(?:\b|$)' } 'PostgreSQL' { '^postgres \(PostgreSQL\) 18\.1$' } default { '\bVer\s+8\.0\.42(?:\b|$)' } }
$script:platformTarget = Get-T21PlatformEndpoint -PlatformEndpoint $PlatformEndpoint
if ($NewerAuditSchemaStartupFailure.IsPresent -and
    -not (Test-T21NewerAuditSchemaStartupFailureTarget -DatabaseProduct $script:databaseProduct -PlatformEndpoint $PlatformEndpoint)) {
    throw 'The newer-audit-schema startup-failure case requires one of the supported SQL products and an explicitly pinned T21 platform endpoint.'
}
if ($DatabaseManagerInitFailureStartupFailure.IsPresent -and
    -not (Test-T21DatabaseManagerInitFailureTarget -DatabaseProduct $script:databaseProduct -PlatformEndpoint $PlatformEndpoint)) {
    throw 'The DatabaseManager initial-schema failure case is limited to PostgreSQL 18.1 and current-maximum Paper 26.3#200.'
}
if ($IncompleteV10AuditTableStartupFailure.IsPresent -and
    -not (Test-T21IncompleteAuditTableStartupFailureTarget -DatabaseProduct $script:databaseProduct -PlatformEndpoint $PlatformEndpoint)) {
    throw 'The incomplete-v10-audit-table startup-failure case supports MySQL, MariaDB, or PostgreSQL on minimum/maximum Paper, minimum/maximum Folia, minimum/maximum Canvas, and supplemental current-stable Paper; unpinned endpoints are not supported.'
}
if ($IncompleteV10AuditTablePlayerRowPreservation.IsPresent -and
    (-not $IncompleteV10AuditTableStartupFailure.IsPresent -or
        -not (Test-T21IncompleteAuditTablePlayerRowPreservationTarget -DatabaseProduct $script:databaseProduct -PlatformEndpoint $PlatformEndpoint))) {
    throw 'Pre-existing player-row preservation is scoped to all three SQL products on the user-pinned minimum/maximum Paper, Folia, and Canvas endpoints.'
}
if ($RequiredV11DdlPermissionDeniedStartupFailure.IsPresent -and
    -not (Test-T21RequiredV11DdlFailureTarget -DatabaseProduct $script:databaseProduct -PlatformEndpoint $PlatformEndpoint)) {
    throw 'The required-v11-DDL-permission-failure case supports all three SQL products on the user-pinned minimum and maximum Paper, Folia, and Canvas endpoints, plus supplemental current-stable Paper; unpinned endpoints are not supported.'
}
if ($SuccessfulV10AuditSchemaUpgrade.IsPresent -and
    -not (Test-T21AuditSchemaV10UpgradeTarget -DatabaseProduct $script:databaseProduct -PlatformEndpoint $PlatformEndpoint)) {
    throw 'The successful audit-schema upgrade case supports MySQL, MariaDB, or PostgreSQL on the six exact user-pinned minimum/maximum Paper, Folia, and Canvas endpoints plus the separately pinned supplemental current-stable Paper endpoint.'
}
if ($SuccessfulV10AuditSchemaPlayerRowPreservation.IsPresent -and
    -not (Test-T21AuditSchemaV10UpgradePlayerRowPreservationTarget -DatabaseProduct $script:databaseProduct -PlatformEndpoint $PlatformEndpoint)) {
    throw 'Successful audit-schema migration with an existing player-row sentinel supports MySQL 8.0.42, MariaDB 12.3.3-MariaDB, or PostgreSQL 18.1 on the six exact user-pinned Paper, Folia, and Canvas endpoints only.'
}
if ($SuccessfulV10AuditSchemaMultiplePlayerRowPreservation.IsPresent -and
    -not (Test-T21AuditSchemaV10UpgradePlayerRowPreservationTarget -DatabaseProduct $script:databaseProduct -PlatformEndpoint $PlatformEndpoint)) {
    throw 'Successful audit-schema migration with multiple existing player rows supports MySQL 8.0.42, MariaDB 12.3.3-MariaDB, or PostgreSQL 18.1 on the six exact user-pinned Paper, Folia, and Canvas endpoints only.'
}
$script:databaseBin = switch ($script:databaseProduct) { 'MariaDB' { $MariaDbBin } 'PostgreSQL' { $PostgreSqlBin } default { $MySqlBin } }
$script:root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$script:sourceCommit = (& git -C $script:root rev-parse HEAD 2>$null | Out-String).Trim().ToLowerInvariant()
if ($LASTEXITCODE -ne 0 -or $script:sourceCommit -notmatch '^[0-9a-f]{40}$') { throw 'Could not pin the Syncmoney source commit.' }
$script:sourceDirtyEntries = @(& git -C $script:root status --porcelain --untracked-files=all 2>$null)
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the Syncmoney source worktree state.' }
$script:sourceWorkingTreeDirty = $script:sourceDirtyEntries.Count -gt 0
if ($DatabaseManagerInitFailureStartupFailure.IsPresent -and $script:sourceWorkingTreeDirty) {
    throw 'Refusing the DatabaseManager initial-schema failure cell while the Syncmoney source worktree has tracked or untracked changes.'
}
$script:serverPath = (Resolve-Path -LiteralPath $ServerJar).Path
$script:candidatePath = Join-Path $script:root "build\libs\Syncmoney-$CandidateVersion.jar"
$script:baselinePath = $null
$script:baselineSha256 = $null
$script:baselineSourceCommit = $null
$script:legacySqlStages = [Collections.Generic.List[object]]::new()
$script:legacySqlDataUpgrade = $LegacySqlDataUpgrade.IsPresent
$script:newerAuditSchemaStartupFailure = $NewerAuditSchemaStartupFailure.IsPresent
$script:databaseManagerInitFailureStartupFailure = $DatabaseManagerInitFailureStartupFailure.IsPresent
$script:incompleteV10AuditTableStartupFailure = $IncompleteV10AuditTableStartupFailure.IsPresent
$script:incompleteV10AuditTablePlayerRowPreservation = $IncompleteV10AuditTablePlayerRowPreservation.IsPresent
$script:requiredV11DdlPermissionDeniedStartupFailure = $RequiredV11DdlPermissionDeniedStartupFailure.IsPresent
$ordinarySuccessfulV10AuditSchemaUpgradeRequested = $SuccessfulV10AuditSchemaUpgrade.IsPresent
$script:successfulV10AuditSchemaSinglePlayerRowPreservation = $SuccessfulV10AuditSchemaPlayerRowPreservation.IsPresent
$script:successfulV10AuditSchemaMultiplePlayerRowPreservation = $SuccessfulV10AuditSchemaMultiplePlayerRowPreservation.IsPresent
$script:successfulV10AuditSchemaPlayerRowPreservation = $script:successfulV10AuditSchemaSinglePlayerRowPreservation -or
    $script:successfulV10AuditSchemaMultiplePlayerRowPreservation
$script:successfulV10AuditSchemaUpgrade = $SuccessfulV10AuditSchemaUpgrade.IsPresent -or $script:successfulV10AuditSchemaPlayerRowPreservation
$script:auditSchemaStartupFailure = $script:newerAuditSchemaStartupFailure -or $script:databaseManagerInitFailureStartupFailure -or $script:incompleteV10AuditTableStartupFailure -or
    $script:requiredV11DdlPermissionDeniedStartupFailure
$script:schemaStateBefore = $null
$script:schemaStateAfter = $null
$script:databaseDdlPrivileges = $null
$script:databaseManagerInitFailureSchemaBefore = $null
$script:databaseManagerInitFailureSchemaAfter = $null
$script:resourceCleanupObservation = $null
$script:mysqlGeneralLogRestoreRequired = $false
$script:mysqlGeneralLogOutputBefore = $null
$script:deniedDdlEvidence = $null
$startupFailureModeCount = 0
if ($script:newerAuditSchemaStartupFailure) { $startupFailureModeCount++ }
if ($script:databaseManagerInitFailureStartupFailure) { $startupFailureModeCount++ }
if ($script:incompleteV10AuditTableStartupFailure) { $startupFailureModeCount++ }
if ($script:requiredV11DdlPermissionDeniedStartupFailure) { $startupFailureModeCount++ }
if ($script:successfulV10AuditSchemaUpgrade) { $startupFailureModeCount++ }
if (($script:successfulV10AuditSchemaSinglePlayerRowPreservation -and $script:successfulV10AuditSchemaMultiplePlayerRowPreservation) -or
    ($ordinarySuccessfulV10AuditSchemaUpgradeRequested -and $script:successfulV10AuditSchemaPlayerRowPreservation) -or
    ($script:legacySqlDataUpgrade -and ($script:auditSchemaStartupFailure -or $script:successfulV10AuditSchemaUpgrade)) -or
    $startupFailureModeCount -gt 1) {
    throw "The selected T21 SQL runtime case modes are mutually exclusive (ordinary=$ordinarySuccessfulV10AuditSchemaUpgradeRequested, single-row=$($script:successfulV10AuditSchemaSinglePlayerRowPreservation), multi-row=$($script:successfulV10AuditSchemaMultiplePlayerRowPreservation), startupFailureModes=$startupFailureModeCount, legacy=$($script:legacySqlDataUpgrade))."
}
$script:acceptancePath = Join-Path $script:root 'build\acceptance\SyncmoneyAcceptance.jar'
$script:apiConsumerPath = Join-Path $script:root 'build\acceptance\SyncmoneyApiConsumer.jar'
$script:plugDevRoot = (Resolve-Path -LiteralPath $PlugDevRoot).Path
$script:plugDevSourceCommit = (& git -C $script:plugDevRoot rev-parse HEAD 2>$null | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $script:plugDevSourceCommit -notmatch '^[0-9a-fA-F]{40}$') { throw 'Could not pin the local PlugDev source commit.' }
$script:plugDevCliPath = Join-Path $script:plugDevRoot 'packages\cli\dist\cli.js'
if (-not (Test-Path -LiteralPath $script:plugDevCliPath -PathType Leaf)) { throw 'The local PlugDev CLI artifact is unavailable.' }
$script:plugDevCliSha256 = (Get-FileHash -LiteralPath $script:plugDevCliPath -Algorithm SHA256).Hash.ToLowerInvariant()
$script:plugDevCliDistManifest = Get-T21PlugDevCliDistManifest $script:plugDevRoot
if (-not (Test-T21PlugDevCliDistManifest $script:plugDevCliDistManifest $script:plugDevCliDistManifest.sha256 $script:plugDevCliSha256)) {
    throw 'The local PlugDev CLI entrypoint is missing from or inconsistent with its complete runtime manifest.'
}
$script:plugDevCliInvocationObservations = [Collections.Generic.List[object]]::new()
$script:javaHomePath = (Resolve-Path -LiteralPath $JavaHome).Path
$script:mysqlBinPath = (Resolve-Path -LiteralPath $script:databaseBin).Path
$script:node = (Get-Command node.exe, node -ErrorAction Stop | Select-Object -First 1).Source
$script:reportsRoot = Resolve-T21RuntimeReportsRoot $script:root $RuntimeReportsRoot
$script:runId = [guid]::NewGuid().ToString('N')
$script:runMarker = $script:databaseProduct.ToLowerInvariant() + '-sync-' + $script:runId
$script:databaseName = 'syncmoney_t21_' + $script:runId.Substring(0, 8)
$script:databaseUser = 'sm_t21_' + $script:runId.Substring(0, 8)
$script:databasePassword = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$script:runRoot = [IO.Path]::GetFullPath((Join-Path $script:reportsRoot ($script:databaseProduct.ToLowerInvariant() + '-sync-' + $script:platformTarget.taskSuffix.ToLowerInvariant() + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $script:runId)))
$script:workspace = Join-Path $script:runRoot 'workspace'
$script:gradleUserHome = Join-Path $script:runRoot '.gradle-user-home'
$script:mysqlRoot = Join-Path $script:runRoot $(if ($script:databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' })
$script:mysqlData = Join-Path $script:mysqlRoot 'data'
$script:redisRoot = Join-Path $script:runRoot 'redis'
$script:redisData = Join-Path $script:redisRoot 'data'
$script:logsRoot = Join-Path $script:runRoot 'logs'
$script:reportPath = Join-Path $script:runRoot 'result.json'
$script:mysqlServerName = switch ($script:databaseProduct) { 'MariaDB' { 'mariadbd.exe' } 'PostgreSQL' { 'postgres.exe' } default { 'mysqld.exe' } }
$script:mysqlServer = Join-Path $script:mysqlBinPath $script:mysqlServerName
$script:mysqlClient = Join-Path $script:mysqlBinPath $(if ($script:databaseProduct -ceq 'PostgreSQL') { 'psql.exe' } else { 'mysql.exe' })
$script:mysqlAdmin = Join-Path $script:mysqlBinPath $(if ($script:databaseProduct -ceq 'PostgreSQL') { 'pg_ctl.exe' } else { 'mysqladmin.exe' })
$script:mysqlInstallDb = if ($script:databaseProduct -ceq 'MariaDB') { Join-Path $script:mysqlBinPath 'mariadb-install-db.exe' } else { $null }
$script:postgresInitDb = if ($script:databaseProduct -ceq 'PostgreSQL') { Join-Path $script:mysqlBinPath 'initdb.exe' } else { $null }
$script:sqlReportKey = if ($script:databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
$script:redisServer = $null
$script:redisCli = $null
$script:redisProcess = $null
$script:mysqlProcess = $null
$script:mysqlLauncherProcess = $null
$script:postgresStartCommandExitCode = $null
$script:serverLogPath = Join-Path $script:workspace '.plugdev\run\logs\latest.log'
$script:gamePort = 0
$script:mysqlPort = 0
$script:redisPort = 0
$script:report = $null
$script:failure = $null
$script:cleanupFailures = [Collections.Generic.List[string]]::new()
$script:startups = [Collections.Generic.List[object]]::new()
$script:platformServerLogs = [Collections.Generic.List[object]]::new()
$script:normalStops = [Collections.Generic.List[object]]::new()
$script:snapshots = [Collections.Generic.List[object]]::new()
$script:backendShutdowns = [Collections.Generic.List[object]]::new()
$script:startedAtUtc = [DateTime]::UtcNow.ToString('o')

if ($ExpectedServerSha256.ToLowerInvariant() -cne $script:platformTarget.sha256) {
    throw "The $($script:platformTarget.taskSuffix) runtime requires its exact pinned server SHA-256."
}
if ($CandidateVersion -cne '1.4.0-alpha.1') { throw 'This T21 runtime cell is pinned to candidate 1.4.0-alpha.1.' }
if ($script:legacySqlDataUpgrade) {
    if ([string]::IsNullOrWhiteSpace($BaselinePluginJar) -or [string]::IsNullOrWhiteSpace($ExpectedBaselinePluginSha256)) {
        throw 'The legacy SQL data upgrade requires an explicit pinned 1.3.2 plugin artifact and SHA-256.'
    }
    if (-not (Test-T21LegacySqlRuntimeTarget -DatabaseProduct $script:databaseProduct -PlatformEndpoint $PlatformEndpoint)) {
        throw 'The legacy SQL data upgrade requires a supported SQL backend and one of the exact pinned Paper, Folia, or Canvas endpoints.'
    }
    $script:baselinePath = (Resolve-Path -LiteralPath $BaselinePluginJar).Path
    $script:baselineSha256 = (Get-FileHash -LiteralPath $script:baselinePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($script:baselineSha256 -cne $ExpectedBaselinePluginSha256.ToLowerInvariant()) {
        throw "Baseline plugin SHA-256 mismatch: $script:baselineSha256."
    }
    $baselineSourceRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $script:baselinePath))
    $script:baselineSourceCommit = (& git -C $baselineSourceRoot rev-parse HEAD 2>$null | Out-String).Trim().ToLowerInvariant()
    if ($LASTEXITCODE -ne 0 -or $script:baselineSourceCommit -notmatch '^[0-9a-f]{40}$') {
        throw 'Could not resolve the baseline artifact source commit.'
    }
} elseif (-not [string]::IsNullOrWhiteSpace($BaselinePluginJar) -or -not [string]::IsNullOrWhiteSpace($ExpectedBaselinePluginSha256)) {
    throw 'Baseline plugin inputs are accepted only with -LegacySqlDataUpgrade.'
}

function Assert-NoReparsePointAncestors([string]$path) {
    $current = [IO.Path]::GetFullPath($path)
    while (-not [string]::IsNullOrWhiteSpace($current)) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Refusing a path whose ancestor is a reparse point: $current"
            }
        }
        $parent = Split-Path -Parent $current
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $current) { break }
        $current = $parent
    }
}

function Test-FreeLoopbackPort([int]$port) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $listener.Start(); return $true }
    catch { return $false }
    finally { $listener.Stop() }
}

function Get-FreeLoopbackPort([int]$minimum, [int]$maximum, [int[]]$reserved = @(), [int]$derivedOffset = 0) {
    for ($attempt = 0; $attempt -lt 300; $attempt++) {
        $port = Get-Random -Minimum $minimum -Maximum ($maximum + 1)
        if ($port -in $reserved -or -not (Test-FreeLoopbackPort $port)) { continue }
        if ($derivedOffset -gt 0 -and (($port + $derivedOffset) -in $reserved -or -not (Test-FreeLoopbackPort ($port + $derivedOffset)))) { continue }
        return $port
    }
    throw "Could not reserve an unused loopback port in $minimum-$maximum."
}

function Get-OfflineUuid([string]$name) {
    $bytes = [Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes('OfflinePlayer:' + $name))
    $bytes[6] = [byte](($bytes[6] -band 15) -bor 48)
    $bytes[8] = [byte](($bytes[8] -band 63) -bor 128)
    $hex = [Convert]::ToHexString($bytes).ToLowerInvariant()
    return ($hex.Substring(0, 8) + '-' + $hex.Substring(8, 4) + '-' + $hex.Substring(12, 4) + '-' + $hex.Substring(16, 4) + '-' + $hex.Substring(20, 12))
}

function Get-T21ServerProcesses {
    @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction Stop | Where-Object {
        $_.CommandLine -and $_.CommandLine.Contains("-Dsyncmoney.t21.database.run=$script:runMarker")
    })
}

function Wait-T21ServerStopped([int]$timeoutSeconds = 35) {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    do {
        if (@(Get-T21ServerProcesses).Count -eq 0) { return $true }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    return (@(Get-T21ServerProcesses).Count -eq 0)
}

function Get-ServerLogLineCount {
    if (Test-Path -LiteralPath $script:serverLogPath -PathType Leaf) { return @(Get-Content -LiteralPath $script:serverLogPath).Count }
    return 0
}

function Wait-ServerLogMarker([string]$pattern, [string]$failurePattern, [int]$afterLine, [int]$timeoutSeconds = 75) {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    do {
        if (-not (Test-Path -LiteralPath $script:serverLogPath -PathType Leaf)) {
            Start-Sleep -Milliseconds 250
            continue
        }
        $allLines = @(Get-Content -LiteralPath $script:serverLogPath -ErrorAction SilentlyContinue)
        $skip = if ($allLines.Count -ge $afterLine) { $afterLine } else { 0 }
        $lines = @($allLines | Select-Object -Skip $skip)
        if ($failurePattern) {
            $failureMatch = @($lines | Select-String -Pattern $failurePattern | Select-Object -First 1)
            if ($failureMatch.Count -gt 0) { throw "Server reported failure marker: $($failureMatch[0].Line)" }
        }
        $match = @($lines | Select-String -Pattern $pattern | Select-Object -Last 1)
        if ($match.Count -gt 0) { return $match[0].Line.Trim() }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for server log marker '$pattern'."
}

function Invoke-LoggedCommand(
    [string]$executable,
    [string[]]$arguments,
    [string]$label,
    [string]$logPath,
    [switch]$allowExitOne,
    [switch]$RedactMariaDbGrantOutput
) {
    $output = @(& $executable @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $outputLines = @($output | ForEach-Object { $_.ToString() })
    if ($RedactMariaDbGrantOutput) {
        $executeArguments = @($arguments | Where-Object { $_ -match '^--execute=' })
        if ($script:databaseProduct -cne 'MariaDB' -or $executeArguments.Count -ne 1 -or
            $executeArguments[0] -notmatch '^--execute=\s*SHOW\s+GRANTS\b') {
            throw 'MariaDB grant-output redaction was requested outside a MariaDB SHOW GRANTS query; output was discarded before logging.'
        }
        $outputLines = @(Protect-T21MariaDbGrantLines -GrantLines ([string[]]$outputLines))
    }
    $outputLines | Set-Content -LiteralPath $logPath -Encoding utf8
    if ($exitCode -ne 0 -and -not ($allowExitOne -and $exitCode -eq 1)) {
        throw "$label exited $exitCode; see $logPath."
    }
    return [pscustomobject]@{ exitCode = [int]$exitCode; output = ($outputLines -join "`n").Trim(); logPath = $logPath }
}

function Invoke-PostgresStart([string]$executable, [string[]]$arguments, [string]$label, [string]$logPath, [int]$timeoutSeconds = 60) {
    $stdoutPath = $logPath + '.stdout.txt'
    $stderrPath = $logPath + '.stderr.txt'
    $argumentLine = ($arguments | ForEach-Object { '"' + ([string]$_).Replace('"', '\"') + '"' }) -join ' '
    $process = $null
    $processExited = $false
    $cleanupAttempted = $false
    $processExitedAfterCleanup = $false
    $exitCode = $null
    $startupError = $null
    $cleanupError = $null
    try {
        $process = Start-Process -FilePath $executable -ArgumentList $argumentLine -WorkingDirectory (Split-Path -Parent $executable) `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
        $processExited = $process.WaitForExit($timeoutSeconds * 1000)
        $process.Refresh()
        if (-not $processExited) {
            $cleanupAttempted = $true
            try {
                if (-not $process.HasExited) { $process.Kill() }
            } catch {
                $cleanupError = $_.Exception.Message
            }
            try {
                $processExitedAfterCleanup = $process.WaitForExit(10000)
                $process.Refresh()
                $processExitedAfterCleanup = $processExitedAfterCleanup -or $process.HasExited
            } catch {
                if ([string]::IsNullOrWhiteSpace($cleanupError)) { $cleanupError = $_.Exception.Message }
            }
        }
        if ($processExited -or $processExitedAfterCleanup) { $exitCode = [int]$process.ExitCode }
    } catch {
        $startupError = $_.Exception.Message
    }
    @(
        "processId=$(if ($null -ne $process) { $process.Id } else { '' })",
        "processExited=$([bool]$processExited)",
        "exitCode=$exitCode",
        "cleanupAttempted=$([bool]$cleanupAttempted)",
        "processExitedAfterCleanup=$([bool]$processExitedAfterCleanup)",
        "startupError=$startupError",
        "cleanupError=$cleanupError",
        "stdoutPath=$stdoutPath",
        "stderrPath=$stderrPath"
    ) | Set-Content -LiteralPath $logPath -Encoding utf8
    if ($null -ne $startupError) { throw "$label could not be launched: $startupError; see $logPath and its redirected output files." }
    if (-not $processExited) { throw "$label did not exit within $timeoutSeconds seconds; see $logPath and its redirected output files." }
    if ($exitCode -ne 0) { throw "$label exited $exitCode; see $logPath and its redirected output files." }
    return [pscustomobject]@{ exitCode = $exitCode; output = ''; logPath = $logPath; processId = [int]$process.Id; processExited = $true }
}

function Get-MySqlLoopbackClientArgs {
    if ($script:databaseProduct -ceq 'MariaDB') {
        # This runner binds the disposable database to loopback and needs clean, parseable client result rows.
        return @('--skip-ssl-verify-server-cert')
    }
    return @()
}

function Invoke-MySqlQuery([string]$sql, [string]$label, [switch]$asRoot) {
    $previousPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
    $logPath = Join-Path $script:logsRoot ($label + '.txt')
    try {
        if ($script:databaseProduct -ceq 'PostgreSQL') {
            return @(Invoke-PostgresQuery $sql $label -asRoot:$asRoot)
        }
        if ($asRoot) {
            $env:MYSQL_PWD = if ($script:databaseProduct -ceq 'MariaDB') { $script:databasePassword } else { '' }
        } else {
            $env:MYSQL_PWD = $script:databasePassword
        }
        $arguments = @('--no-defaults') + (Get-MySqlLoopbackClientArgs) + @('--protocol=TCP', '--host=127.0.0.1', "--port=$script:mysqlPort", "--user=$(if ($asRoot) { 'root' } else { $script:databaseUser })")
        if (-not $asRoot) { $arguments += "--database=$script:databaseName" }
        $arguments += @('--batch', '--skip-column-names', '--raw', "--execute=$sql")
        $redactMariaDbGrantOutput = $script:databaseProduct -ceq 'MariaDB' -and $sql -match '^\s*SHOW\s+GRANTS\b'
        $result = Invoke-LoggedCommand $script:mysqlClient $arguments $label $logPath -RedactMariaDbGrantOutput:$redactMariaDbGrantOutput
        return @($result.output -split "`r?`n" | Where-Object { $_.Length -gt 0 })
    } finally {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previousPassword, 'Process')
    }
}

function Invoke-MySqlAdmin([string[]]$arguments, [string]$label, [switch]$allowExitOne) {
    $previousPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
    $logPath = Join-Path $script:logsRoot ($label + '.txt')
    try {
        $env:MYSQL_PWD = if ($script:databaseProduct -ceq 'MariaDB') { $script:databasePassword } else { '' }
        return Invoke-LoggedCommand $script:mysqlAdmin (@('--no-defaults') + (Get-MySqlLoopbackClientArgs) + @('--protocol=TCP', '--host=127.0.0.1', "--port=$script:mysqlPort", '--user=root') + $arguments) $label $logPath -allowExitOne:$allowExitOne
    } finally {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previousPassword, 'Process')
    }
}

function Invoke-PostgresQuery([string]$sql, [string]$label, [switch]$asRoot, [string]$Database = '') {
    $previousPassword = [Environment]::GetEnvironmentVariable('PGPASSWORD', 'Process')
    $logPath = Join-Path $script:logsRoot ($label + '.txt')
    try {
        $env:PGPASSWORD = $script:databasePassword
        $user = if ($asRoot) { 'postgres' } else { $script:databaseUser }
        $databaseName = if ([string]::IsNullOrWhiteSpace($Database)) {
            if ($asRoot) { 'postgres' } else { $script:databaseName }
        } else {
            $Database
        }
        $arguments = @('-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1', '-p', [string]$script:mysqlPort, '-U', $user, '-d', $databaseName)
        $output = @($sql | & $script:mysqlClient @arguments 2>&1)
        $exitCode = $LASTEXITCODE
        $output | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath $logPath -Encoding utf8
        if ($exitCode -ne 0) { throw "PostgreSQL query $label exited $exitCode; see $logPath." }
        return @(($output | ForEach-Object { $_.ToString() } | Out-String) -split "`r?`n" | Where-Object { $_.Length -gt 0 })
    } finally {
        [Environment]::SetEnvironmentVariable('PGPASSWORD', $previousPassword, 'Process')
    }
}

function Get-T21DatabaseManagerInitFailurePrivileges {
    if ($script:databaseUser -notmatch '^sm_t21_[0-9a-f]{8}$' -or $script:databaseName -notmatch '^syncmoney_t21_[0-9a-f]{8}$') {
        throw 'Refusing to query PostgreSQL privileges for an unexpected isolated role or database name.'
    }
    $sql = @"
SELECT json_build_object(
    'appRole', app.rolname,
    'appRoleIsSuperuser', app.rolsuper,
    'appRoleCanCreateDatabase', app.rolcreatedb,
    'appRoleOwnsDatabase', target_db.datdba = app.oid,
    'appRoleOwnsPublicSchema', public_schema.nspowner = app.oid,
    'targetDatabaseOwner', database_owner.rolname,
    'publicSchemaOwner', schema_owner.rolname,
    'bootstrapConnectGranted', has_database_privilege(app.rolname, 'postgres', 'CONNECT'),
    'targetConnectGranted', has_database_privilege(app.rolname, target_db.oid, 'CONNECT'),
    'appRoleHasPublicUsage', has_schema_privilege(app.rolname, public_schema.oid, 'USAGE'),
    'appRoleHasPublicCreate', has_schema_privilege(app.rolname, public_schema.oid, 'CREATE'),
    'publicHasCreate', EXISTS (
        SELECT 1
        FROM pg_catalog.aclexplode(COALESCE(public_schema.nspacl, pg_catalog.acldefault('n', public_schema.nspowner))) privilege
        WHERE privilege.grantee = 0 AND privilege.privilege_type = 'CREATE'
    )
)::text
FROM pg_catalog.pg_roles app
JOIN pg_catalog.pg_database target_db ON target_db.datname = '$script:databaseName'
JOIN pg_catalog.pg_roles database_owner ON database_owner.oid = target_db.datdba
JOIN pg_catalog.pg_namespace public_schema ON public_schema.nspname = 'public'
JOIN pg_catalog.pg_roles schema_owner ON schema_owner.oid = public_schema.nspowner
WHERE app.rolname = '$script:databaseUser';
"@
    $lines = @(Invoke-PostgresQuery $sql 'postgresql-database-manager-init-privileges' -asRoot -Database $script:databaseName)
    if ($lines.Count -ne 1) { throw 'PostgreSQL returned no unique initial-schema privilege evidence.' }
    try { $privileges = ConvertFrom-Json -InputObject $lines[0] -AsHashtable -ErrorAction Stop }
    catch { throw 'PostgreSQL initial-schema privilege evidence was not valid JSON.' }
    $validation = Test-T21DatabaseManagerInitFailurePrivileges $privileges
    if (-not $validation.passed) { throw "PostgreSQL initial-schema privilege boundary failed: $($validation.failures -join '; ')" }
    return $privileges
}

function Get-T21DatabaseManagerInitFailureSchemaSnapshot([ValidateSet('before', 'after')][string]$Stage) {
    $sql = @'
SELECT json_build_object(
    'playersTableExists', to_regclass('public.players') IS NOT NULL,
    'schemaVersionTableExists', to_regclass('public.syncmoney_schema_version') IS NOT NULL,
    'publicTableCount', (SELECT COUNT(*)::integer FROM pg_catalog.pg_tables WHERE schemaname = 'public'),
    'publicObjectCount', (
        SELECT COUNT(*)::integer
        FROM (
            SELECT relation.oid
            FROM pg_catalog.pg_class relation
            WHERE relation.relnamespace = 'public'::regnamespace
            UNION ALL
            SELECT routine.oid
            FROM pg_catalog.pg_proc routine
            WHERE routine.pronamespace = 'public'::regnamespace
            UNION ALL
            SELECT user_type.oid
            FROM pg_catalog.pg_type user_type
            WHERE user_type.typnamespace = 'public'::regnamespace
              AND user_type.typtype NOT IN ('c', 'p')
              AND user_type.typelem = 0
              AND user_type.typisdefined
        ) public_schema_objects
    )
)::text;
'@
    $lines = @(Invoke-PostgresQuery $sql ("postgresql-database-manager-schema-" + $Stage) -asRoot -Database $script:databaseName)
    if ($lines.Count -ne 1) { throw "PostgreSQL returned no unique $Stage initial-schema snapshot." }
    try { return ConvertFrom-Json -InputObject $lines[0] -ErrorAction Stop }
    catch { throw "PostgreSQL $Stage initial-schema snapshot was not valid JSON." }
}

function Assert-BackendListenerOwner([string]$name, [int]$port, [int]$processId) {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
    $owners = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
    $addresses = @($listeners | Select-Object -ExpandProperty LocalAddress -Unique)
    if ($listeners.Count -eq 0 -or $owners.Count -ne 1 -or [int]$owners[0] -ne $processId -or
        @($addresses | Where-Object { $_ -notin @('127.0.0.1', '::1') }).Count -gt 0) {
        $observedOwners = if ($owners.Count -eq 0) { '<none>' } else { $owners -join ',' }
        $observedAddresses = if ($addresses.Count -eq 0) { '<none>' } else { $addresses -join ',' }
        throw "$name listener on port $port failed ownership verification (expected PID $processId; owners $observedOwners; addresses $observedAddresses)."
    }
    return $true
}

function Get-MySqlListenerProcess {
    if ($script:databaseProduct -ceq 'PostgreSQL') { return Get-PostgresListenerProcess }
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $script:mysqlPort -ErrorAction SilentlyContinue)
    if ($listeners.Count -eq 0) { return $null }
    $owners = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
    $addresses = @($listeners | Select-Object -ExpandProperty LocalAddress -Unique)
    if ($owners.Count -ne 1 -or @($addresses | Where-Object { $_ -notin @('127.0.0.1', '::1') }).Count -gt 0) {
        $observedOwners = if ($owners.Count -eq 0) { '<none>' } else { $owners -join ',' }
        $observedAddresses = if ($addresses.Count -eq 0) { '<none>' } else { $addresses -join ',' }
        throw "$script:databaseProduct port $script:mysqlPort has ambiguous or non-loopback listeners (owners $observedOwners; addresses $observedAddresses)."
    }

    $ownerId = [int]$owners[0]
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $ownerId" -ErrorAction Stop
    if ($null -eq $processInfo) { throw "$script:databaseProduct listener PID $ownerId could not be inspected." }
    $expectedExecutable = [IO.Path]::GetFullPath($script:mysqlServer)
    $actualExecutable = if ([string]::IsNullOrWhiteSpace([string]$processInfo.ExecutablePath)) { '' } else { [IO.Path]::GetFullPath([string]$processInfo.ExecutablePath) }
    $commandLine = [string]$processInfo.CommandLine
    $hasRunDataDirectory = $commandLine.IndexOf($script:mysqlData, [StringComparison]::OrdinalIgnoreCase) -ge 0
    $hasRunPort = $commandLine.IndexOf("--port=$script:mysqlPort", [StringComparison]::OrdinalIgnoreCase) -ge 0
    if ($actualExecutable -ine $expectedExecutable -or -not $hasRunDataDirectory -or -not $hasRunPort) {
        throw "$script:databaseProduct listener PID $ownerId on port $script:mysqlPort does not match the run-owned executable, data directory, and port."
    }
    $process = Get-Process -Id $ownerId -ErrorAction Stop
    return [ordered]@{ process=$process; processInfo=$processInfo; addresses=@($addresses | Sort-Object) }
}

function Get-PostgresListenerProcess {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $script:mysqlPort -ErrorAction SilentlyContinue)
    if ($listeners.Count -eq 0) { return $null }
    $owners = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
    $addresses = @($listeners | Select-Object -ExpandProperty LocalAddress -Unique)
    if ($owners.Count -ne 1 -or @($addresses | Where-Object { $_ -notin @('127.0.0.1', '::1') }).Count -gt 0) {
        throw "PostgreSQL port $script:mysqlPort has ambiguous or non-loopback listeners."
    }

    $ownerId = [int]$owners[0]
    $ownedProcess = Get-PostgresPidFileProcess
    if ($null -eq $ownedProcess -or [int]$ownedProcess.process.Id -ne $ownerId -or
        -not (Test-T21PostgresListenerIdentity -ListenerOwners $owners -ListenerAddresses $addresses -ProcessId $ownerId `
            -ExpectedExecutablePath ([IO.Path]::GetFullPath($script:mysqlServer)) `
            -ActualExecutablePath ([IO.Path]::GetFullPath([string]$ownedProcess.processInfo.ExecutablePath)) `
            -CommandLine ([string]$ownedProcess.processInfo.CommandLine) -PostmasterPidLines $ownedProcess.pidLines `
            -ExpectedDataDirectory $script:mysqlData -ExpectedPort $script:mysqlPort `
            -ProcessStartTimeUtc $ownedProcess.process.StartTime.ToUniversalTime())) {
        throw "PostgreSQL listener PID $ownerId does not match the run-owned executable, data directory, and port."
    }
    return [ordered]@{ process=$ownedProcess.process; processInfo=$ownedProcess.processInfo; addresses=@($addresses | Sort-Object) }
}

function Get-PostgresPidFileProcess {
    $pidFile = Join-Path $script:mysqlData 'postmaster.pid'
    if (-not (Test-Path -LiteralPath $pidFile -PathType Leaf)) { return $null }
    $pidLines = @(Get-Content -LiteralPath $pidFile -TotalCount 4 -ErrorAction Stop)
    $pidFileProcessId = 0
    if ($pidLines.Count -lt 4 -or -not [int]::TryParse(([string]$pidLines[0]).Trim(), [ref]$pidFileProcessId) -or $pidFileProcessId -le 0) {
        throw 'PostgreSQL postmaster.pid does not contain a valid process ID and identity fields.'
    }

    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $pidFileProcessId" -ErrorAction Stop
    if ($null -eq $processInfo) { return $null }
    $process = Get-Process -Id $pidFileProcessId -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $null }
    $expectedExecutable = [IO.Path]::GetFullPath($script:mysqlServer)
    $actualExecutable = if ([string]::IsNullOrWhiteSpace([string]$processInfo.ExecutablePath)) { '' } else { [IO.Path]::GetFullPath([string]$processInfo.ExecutablePath) }
    if (-not (Test-T21PostgresProcessIdentity -ProcessId $pidFileProcessId `
        -ExpectedExecutablePath $expectedExecutable -ActualExecutablePath $actualExecutable `
        -CommandLine ([string]$processInfo.CommandLine) -PostmasterPidLines $pidLines `
        -ExpectedDataDirectory $script:mysqlData -ExpectedPort $script:mysqlPort `
        -ProcessStartTimeUtc $process.StartTime.ToUniversalTime())) {
        throw "PostgreSQL PID $pidFileProcessId does not match the run-owned executable, data directory, process start time, and port."
    }
    return [ordered]@{ process=$process; processInfo=$processInfo; pidLines=$pidLines }
}

function Wait-MySqlReady([int]$timeoutSeconds = 45) {
    if ($script:databaseProduct -ceq 'PostgreSQL') { return Wait-PostgresReady -timeoutSeconds $timeoutSeconds }
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    do {
    if ($null -eq $script:mysqlLauncherProcess) { throw "The isolated $script:databaseProduct launcher process was not created." }
        $listenerProcess = Get-MySqlListenerProcess
        if ($null -ne $listenerProcess) {
            $script:mysqlProcess = $listenerProcess.process
            $script:report.mysql.processId = [int]$script:mysqlProcess.Id
            $script:report.mysql.listenerAddresses = @($listenerProcess.addresses)
            $null = Assert-BackendListenerOwner $script:databaseProduct $script:mysqlPort $script:mysqlProcess.Id
            try {
                $ping = Invoke-MySqlAdmin @('ping') 'mysql-ping' -allowExitOne
                if ($ping.output -match 'mysqld is alive') { return }
            } catch {
                # The run-owned listener exists but can still be initializing; retry until ready.
            }
        } elseif ($script:mysqlLauncherProcess.HasExited) {
            $script:mysqlLauncherProcess.Refresh()
            throw "Isolated $script:databaseProduct launcher exited before a run-owned listener appeared (code $($script:mysqlLauncherProcess.ExitCode))."
        }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for isolated $script:databaseProduct readiness."
}

function Wait-PostgresReady([int]$timeoutSeconds = 45) {
    if ($script:postgresStartCommandExitCode -ne 0) { throw 'The isolated PostgreSQL start command did not exit successfully.' }
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    do {
        $listenerProcess = Get-PostgresListenerProcess
        if ($null -ne $listenerProcess) {
            $script:mysqlProcess = $listenerProcess.process
            $script:report.postgresql.processId = [int]$script:mysqlProcess.Id
            $script:report.postgresql.listenerAddresses = @($listenerProcess.addresses)
            $null = Assert-BackendListenerOwner 'PostgreSQL' $script:mysqlPort $script:mysqlProcess.Id
            try {
                $version = @(Invoke-PostgresQuery "SELECT current_setting('server_version');" 'postgresql-readiness' -asRoot)
                if ($version.Count -eq 1 -and $version[0].Trim() -ceq '18.1') { return }
            } catch {
                # The owned listener can exist before PostgreSQL accepts client sessions.
            }
        }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Timed out waiting for isolated PostgreSQL readiness.'
}

function Wait-RedisReady([int]$timeoutSeconds = 30) {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    do {
        if ($null -eq $script:redisProcess -or $script:redisProcess.HasExited) {
            throw 'Isolated Redis exited before readiness.'
        }
        try {
            $null = Assert-BackendListenerOwner 'Redis' $script:redisPort $script:redisProcess.Id
            $ping = Invoke-LoggedCommand $script:redisCli @('-h', '127.0.0.1', '-p', [string]$script:redisPort, 'PING') 'redis-ping' (Join-Path $script:logsRoot 'redis-ping.txt')
            if ($ping.output -ceq 'PONG') { return }
        } catch {
            if ($_.Exception.Message -match 'not uniquely owned') { throw }
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Timed out waiting for isolated Redis readiness.'
}

function Initialize-T21ProjectWorkspace([string]$projectRoot) {
    New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'tools\plugdev'),
        (Join-Path $projectRoot 'src\main\resources'), (Join-Path $projectRoot 'build\libs'),
        (Join-Path $projectRoot 'build\acceptance'), (Join-Path $projectRoot 'gradle') -ErrorAction Stop | Out-Null
    foreach ($name in @('build.gradle', 'settings.gradle', 'gradlew.bat')) {
        Copy-Item -LiteralPath (Join-Path $script:root $name) -Destination (Join-Path $projectRoot $name)
    }
    Copy-Item -LiteralPath (Join-Path $script:root 'gradle\wrapper') -Destination (Join-Path $projectRoot 'gradle') -Recurse
    $settingsText = Get-Content -LiteralPath (Join-Path $projectRoot 'settings.gradle') -Raw
    foreach ($include in [regex]::Matches($settingsText, '(?m)^\s*include\s*\(([^)]*)\)')) {
        foreach ($projectMatch in [regex]::Matches($include.Groups[1].Value, '["'']([^"'']+)["'']')) {
            $relative = $projectMatch.Groups[1].Value.Replace(':', '\')
            $targetDirectory = [IO.Path]::GetFullPath((Join-Path $projectRoot $relative))
            if (-not $targetDirectory.StartsWith(($projectRoot.TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase)) {
                throw "Gradle include escapes the isolated workspace: $relative"
            }
            $sourceBuild = Join-Path (Join-Path $script:root $relative) 'build.gradle'
            if (-not (Test-Path -LiteralPath $sourceBuild -PathType Leaf)) { throw "Missing included Gradle build file: $sourceBuild" }
            New-Item -ItemType Directory -Force -Path $targetDirectory | Out-Null
            Copy-Item -LiteralPath $sourceBuild -Destination (Join-Path $targetDirectory 'build.gradle')
        }
    }
    Copy-Item -LiteralPath (Join-Path $script:root 'tools\plugdev\prepare.mjs') -Destination (Join-Path $projectRoot 'tools\plugdev\prepare.mjs')
    foreach ($name in @('config.yml', 'plugin.yml')) {
        Copy-Item -LiteralPath (Join-Path $script:root "src\main\resources\$name") -Destination (Join-Path $projectRoot "src\main\resources\$name")
    }
    Copy-Item -LiteralPath $script:candidatePath -Destination (Join-Path $projectRoot "build\libs\Syncmoney-$CandidateVersion.jar")
    if ($script:legacySqlDataUpgrade) {
        Copy-Item -LiteralPath $script:baselinePath -Destination (Join-Path $projectRoot 'build\libs\Syncmoney-1.3.2.jar')
    }
    Copy-Item -LiteralPath $script:acceptancePath -Destination (Join-Path $projectRoot 'build\acceptance\SyncmoneyAcceptance.jar')
    Copy-Item -LiteralPath $script:apiConsumerPath -Destination (Join-Path $projectRoot 'build\acceptance\SyncmoneyApiConsumer.jar')
}

function Invoke-PlugDev([string[]]$arguments, [string]$label) {
    $logPath = Join-Path $script:logsRoot ($label + '.txt')
    $expectedManifestSha256 = [string]$script:plugDevCliDistManifest.sha256
    $manifestBefore = Get-T21PlugDevCliDistManifest $script:plugDevRoot
    $completed = $false
    Push-Location $script:workspace
    try {
        if ($manifestBefore.sha256 -cne $expectedManifestSha256) { throw 'The local PlugDev CLI bundle changed before an invocation.' }
        $result = Invoke-LoggedCommand $script:node (@((Join-Path $script:plugDevRoot 'packages\cli\dist\cli.js')) + $arguments) $label $logPath
        $completed = $true
        return $result
    } finally {
        try {
            $manifestAfter = Get-T21PlugDevCliDistManifest $script:plugDevRoot
            $script:plugDevCliInvocationObservations.Add([ordered]@{
                label=$label
                beforeSha256=$manifestBefore.sha256
                afterSha256=$manifestAfter.sha256
                commandCompleted=$completed
            })
            if ($manifestBefore.sha256 -cne $expectedManifestSha256 -or $manifestAfter.sha256 -cne $expectedManifestSha256) {
                throw 'The local PlugDev CLI bundle changed during a runtime invocation.'
            }
        } finally {
            Pop-Location
        }
    }
}

function Set-T21PluginInProfile([string]$pluginVersion) {
    $editor = @'
const fs = require("node:fs");
const path = require("node:path");
const { createRequire } = require("node:module");
const yaml = createRequire(path.join(process.env.PLUGDEV_ROOT, "package.json"))("yaml");
const file = path.resolve(".plugdev/profile.yml");
const profile = yaml.parse(fs.readFileSync(file, "utf8"));
profile.build.jarPattern = process.env.T21_PLUGIN_JAR_PATTERN;
profile.jvm.args = [
  `-Dsyncmoney.acceptance.version=${process.env.T21_PLUGIN_VERSION}`,
  `-Dsyncmoney.t21.database.run=${process.env.T21_RUN_MARKER}`,
];
fs.writeFileSync(file, yaml.stringify(profile), "utf8");
console.log(JSON.stringify({ version: process.env.T21_PLUGIN_VERSION, exactJarPattern: profile.build.jarPattern }));
'@
    $env:T21_PLUGIN_VERSION = $pluginVersion
    $env:T21_PLUGIN_JAR_PATTERN = "build/libs/Syncmoney-$pluginVersion.jar"
    $editorPath = Join-Path $script:runRoot 'set-plugin-profile.cjs'
    Set-Content -LiteralPath $editorPath -Value $editor -Encoding utf8
    Push-Location $script:workspace
    try {
        $result = Invoke-LoggedCommand $script:node @($editorPath) ('set-plugin-profile-' + $pluginVersion) (Join-Path $script:logsRoot ('set-plugin-profile-' + $pluginVersion + '.json'))
    } finally { Pop-Location }
    if ($result.exitCode -ne 0) { throw "Could not set the isolated PlugDev profile for Syncmoney $pluginVersion." }
    $observation = $result.output | ConvertFrom-Json
    if ($observation.version -cne $pluginVersion -or $observation.exactJarPattern -cne $env:T21_PLUGIN_JAR_PATTERN) {
        throw "PlugDev profile did not select the exact Syncmoney $pluginVersion artifact."
    }
}

function Assert-IsolatedSyncConfig {
    $scriptPath = Join-Path $script:runRoot 'audit-config.cjs'
    $content = @'
const fs=require("node:fs"); const path=require("node:path"); const {createRequire}=require("node:module");
const yaml=createRequire(path.join(process.env.PLUGDEV_ROOT,"package.json"))("yaml");
const p=process.argv[2], c=yaml.parse(fs.readFileSync(p,"utf8"));
const expected={dbType:process.env.SYNCMONEY_TEST_DB_TYPE,dbPort:Number(process.env.SYNCMONEY_TEST_DB_PORT),redisPort:Number(process.env.SYNCMONEY_TEST_REDIS_PORT),dbName:process.env.SYNCMONEY_TEST_DB_NAME,dbUser:process.env.SYNCMONEY_TEST_DB_USER,dbPassword:process.env.SYNCMONEY_TEST_DB_PASSWORD};
const ok=c["config-version"]===12&&c["db-enabled"]===true&&c["pubsub-enabled"]===true&&c.economy.mode==="sync"&&
  c.database.enabled===true&&c.database.type===expected.dbType&&c.database.host==="127.0.0.1"&&Number(c.database.port)===expected.dbPort&&
  c.database.database===expected.dbName&&c.database.username===expected.dbUser&&c.database.password===expected.dbPassword&&
  c.redis.enabled===true&&c.redis.host==="127.0.0.1"&&Number(c.redis.port)===expected.redisPort&&c.redis.database===15;
if(!ok) throw new Error("The isolated platform profile does not match the required SQL/Redis SYNC configuration.");
console.log(JSON.stringify({schema:c["config-version"],mode:c.economy.mode,databaseType:c.database.type,databaseHost:c.database.host,databasePort:c.database.port,databaseName:c.database.database,redisHost:c.redis.host,redisPort:c.redis.port,redisDatabase:c.redis.database,credentialsConfigured:Boolean(c.database.username&&c.database.password)}));
'@
    Set-Content -LiteralPath $scriptPath -Value $content -Encoding utf8
    Push-Location $script:workspace
    try {
        $result = Invoke-LoggedCommand $script:node @($scriptPath, (Join-Path $script:workspace '.plugdev\run\plugins\Syncmoney\config.yml')) 'config-audit' (Join-Path $script:logsRoot 'config-audit.json')
    } finally { Pop-Location }
    if ($result.exitCode -ne 0) { throw 'Isolated Syncmoney config did not match the SQL and Redis runtime pins.' }
    return ($result.output | ConvertFrom-Json)
}

function Get-PinnedSyncmoneyPlugin([string]$pluginVersion, [string]$expectedSha256) {
    $plugins = Join-Path $script:workspace '.plugdev\run\plugins'
    $observed = @(Get-ChildItem -LiteralPath $plugins -Filter '*.jar' -File | ForEach-Object {
        [ordered]@{ name=$_.Name; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(); path=$_.FullName }
    })
    $matching = @($observed | Where-Object { $_.sha256 -ceq $expectedSha256 })
    $expectedName = "Syncmoney-$pluginVersion.jar"
    if ($matching.Count -ne 1 -or $matching[0].name -cne $expectedName) {
        throw "Expected exactly one deployed $expectedName with SHA-256 $expectedSha256; observed $($matching.Count)."
    }
    return $matching[0]
}

function Set-T21PluginStage([string]$pluginVersion) {
    $plugins = Join-Path $script:workspace '.plugdev\run\plugins'
    $pluginJars = @(Get-ChildItem -LiteralPath $plugins -Filter 'Syncmoney-*.jar' -File -ErrorAction Stop)
    foreach ($pluginJar in $pluginJars) { Remove-Item -LiteralPath $pluginJar.FullName -Force -ErrorAction Stop }
    $consumerPath = Join-Path $plugins 'SyncmoneyApiConsumer.jar'
    if ($pluginVersion -ceq '1.3.2') {
        if (Test-Path -LiteralPath $consumerPath -PathType Leaf) { Remove-Item -LiteralPath $consumerPath -Force -ErrorAction Stop }
    } else {
        Copy-Item -LiteralPath $script:apiConsumerPath -Destination $consumerPath -Force -ErrorAction Stop
        $consumerSha = (Get-FileHash -LiteralPath $consumerPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($consumerSha -cne $script:apiConsumerSha256) { throw 'The candidate API consumer artifact pin changed while staging.' }
    }
    Set-T21PluginInProfile $pluginVersion
}

function Start-T21Server([string]$stage, [string]$pluginVersion = $CandidateVersion) {
    if (@(Get-T21ServerProcesses).Count -ne 0) { throw 'A run-marked server process exists before startup.' }
    if ($script:legacySqlDataUpgrade) {
        Set-T21PluginStage $pluginVersion
    } elseif ($pluginVersion -cne $CandidateVersion) {
        throw 'The normal SQL runtime mode accepts only the pinned candidate plugin version.'
    }
    $before = Get-ServerLogLineCount
    $start = Invoke-PlugDev @('server', 'start', '--config', '.plugdev/profile.yml') ($stage + '-start')
    if ($start.exitCode -ne 0) { throw "PlugDev server start failed at $stage." }
    $null = Wait-ServerLogMarker 'Syncmoney enabled successfully' 'Error occurred while enabling Syncmoney|Could not load plugin|T21_\w+_FAIL' $before
    $null = Wait-ServerLogMarker ("Loading server plugin Syncmoney v" + [regex]::Escape($pluginVersion)) 'Error occurred while enabling Syncmoney|Could not load plugin|T21_\w+_FAIL' $before
    $apiConsumerMarker = 'NOT_REQUIRED_FOR_BASELINE'
    if ($pluginVersion -cne '1.3.2') {
        $null = Wait-ServerLogMarker 'API CLASSLOADER PASS provider=Syncmoney' 'API CLASSLOADER FAIL|Error occurred while enabling Syncmoney|T21_\w+_FAIL' $before
        $apiConsumerMarker = 'PASS'
    } elseif (Test-Path -LiteralPath (Join-Path $script:workspace '.plugdev\run\plugins\SyncmoneyApiConsumer.jar') -PathType Leaf) {
        throw 'The 1.3.2 baseline stage must not load the 1.4 API consumer.'
    }
    $fullLog = Get-Content -LiteralPath $script:serverLogPath -Raw
    $identity = Get-T21PlatformIdentity $fullLog
    Assert-T21PlatformIdentity $identity $script:platformTarget.software $script:platformTarget.minecraftVersion $script:platformTarget.build
    if ($fullLog -notmatch ('(?m)\[Syncmoney\] Loading server plugin Syncmoney v' + [regex]::Escape($pluginVersion))) {
        throw "The selected platform server did not load Syncmoney $pluginVersion."
    }
    $expectedPluginSha = if ($pluginVersion -ceq '1.3.2') { $script:baselineSha256 } else { $script:candidateSha256 }
    $pluginPin = Get-PinnedSyncmoneyPlugin $pluginVersion $expectedPluginSha
    $processes = @(Get-T21ServerProcesses)
    if ($processes.Count -ne 1) { throw "Expected one run-owned Java server after $stage; observed $($processes.Count)." }
    $observation = [ordered]@{
        stage = $stage
        enabled = $true
        pluginVersion = $pluginVersion
        artifactSha256 = $pluginPin.sha256
        artifactName = $pluginPin.name
        processId = [int]$processes[0].ProcessId
        runtimeIdentity = $identity
        startupLogMarker = 'Syncmoney enabled successfully'
        apiConsumerMarker = $apiConsumerMarker
    }
    $script:startups.Add($observation)
    return $observation
}

function Stop-T21Server([string]$stage) {
    $before = Get-ServerLogLineCount
    $owned = @(Get-T21ServerProcesses)
    if ($owned.Count -ne 1) { throw "Refusing graceful RCON stop at $stage; expected one run-owned platform process, observed $($owned.Count)." }
    $sessionPath = Join-Path $script:workspace '.plugdev\session.json'
    if (-not (Test-Path -LiteralPath $sessionPath -PathType Leaf)) { throw "PlugDev session is absent before normal stop at $stage." }
    $session = Get-Content -LiteralPath $sessionPath -Raw | ConvertFrom-Json
    if ([int]$session.pid -ne [int]$owned[0].ProcessId -or [int]$session.gamePort -ne $script:gamePort) {
        throw "Refusing graceful RCON stop at $stage because the PlugDev session does not match the run-owned server and game port."
    }
    $serverProcess = Get-Process -Id ([int]$owned[0].ProcessId) -ErrorAction Stop
    $stop = Invoke-PlugDev @('server', 'command', 'stop') ($stage + '-stop')
    if ($stop.exitCode -ne 0) { throw "PlugDev RCON stop command failed at $stage with exit code $($stop.exitCode)." }
    $processExited = Wait-T21ServerStopped
    if (-not $processExited) { throw "Run-owned server did not exit after $stage." }
    $serverProcess.Refresh()
    if (-not $serverProcess.HasExited) { throw "Captured run-owned platform PID $($serverProcess.Id) remained active after $stage." }
    $processExitCode = [int]$serverProcess.ExitCode
    if ($processExitCode -ne 0) { throw "Run-owned platform exited with code $processExitCode after $stage." }
    $allLines = @(Get-Content -LiteralPath $script:serverLogPath -ErrorAction Stop)
    $skip = if ($allLines.Count -ge $before) { $before } else { 0 }
    $stopLines = @($allLines | Select-Object -Skip $skip)
    $disabled = @($stopLines | Select-String -Pattern 'Syncmoney disabled\.' | Select-Object -Last 1)
    $startupFailureDisabled = if ($script:databaseManagerInitFailureStartupFailure) {
        (Test-T21DatabaseManagerInitFailureStartupLog -LogText ($allLines -join "`n") -CandidateVersion $CandidateVersion).passed
    } else {
        $script:auditSchemaStartupFailure -and
            (Test-T21StartupRollbackCompletionLog ($allLines -join "`n") $CandidateVersion)
    }
    if ($disabled.Count -eq 0 -and -not $startupFailureDisabled) { throw "Syncmoney did not log its normal disable marker for $stage." }
    $failureMarkers = @(Get-T21ShutdownFailureMarkers ($stopLines -join "`n"))
    if ($failureMarkers.Count -gt 0) { throw "Server shutdown failure marker observed at ${stage}: $($failureMarkers -join '; ')." }
    $platformLogStage = Get-T21PlatformLogSnapshotStageName $stage
    $platformLogRelativePath = 'logs\platform-' + $platformLogStage + '.txt'
    $platformLogPath = Join-Path $script:runRoot $platformLogRelativePath
    Copy-Item -LiteralPath $script:serverLogPath -Destination $platformLogPath -Force -ErrorAction Stop
    $platformLogFile = Get-Item -LiteralPath $platformLogPath -ErrorAction Stop
    $script:platformServerLogs.Add([ordered]@{
        stage = $platformLogStage
        path = $platformLogRelativePath
        bytes = [long]$platformLogFile.Length
        sha256 = (Get-FileHash -LiteralPath $platformLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    })
    Remove-Item -LiteralPath $sessionPath -Force -ErrorAction Stop
    $script:normalStops.Add([ordered]@{
        stage = $stage
        commandExitCode = [int]$stop.exitCode
        processId = [int]$serverProcess.Id
        processExitCode = $processExitCode
        processExitCodeObserved = $true
        processExited = $processExited
        disabledMarker = [bool]($disabled.Count -gt 0 -or $startupFailureDisabled)
        forced = $false
    })
}

function Invoke-Redis([string[]]$arguments, [string]$label, [switch]$allowExitOne) {
    $logPath = Join-Path $script:logsRoot ($label + '.txt')
    $null = Assert-BackendListenerOwner 'Redis' $script:redisPort $script:redisProcess.Id
    return Invoke-LoggedCommand $script:redisCli (@('-h', '127.0.0.1', '-p', [string]$script:redisPort) + $arguments) $label $logPath -allowExitOne:$allowExitOne
}

function Get-T21LegacyAuditSchemaMetadata([string]$stage) {
    if ($script:databaseProduct -ceq 'PostgreSQL') {
        $columnsSql = @"
SELECT json_build_array(TABLE_NAME, ORDINAL_POSITION, COLUMN_NAME, DATA_TYPE, IS_NULLABLE,
    COLUMN_DEFAULT, '', '', '', '')::text
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = current_schema() AND TABLE_NAME = 'syncmoney_audit_log'
ORDER BY ORDINAL_POSITION, COLUMN_NAME;
"@
        $indexesSql = @"
SELECT json_build_array(index_relation.relname, index_info.indisprimary, CASE WHEN index_info.indisunique THEN 0 ELSE 1 END,
    key_column.ordinality, COALESCE(table_column.attname, '<EXPRESSION>'),
    CASE WHEN (index_info.indoption[key_column.ordinality - 1] & 1) <> 0 THEN 'D' ELSE 'A' END,
    upper(access_method.amname), NULL)::text
FROM pg_class table_relation
JOIN pg_namespace table_namespace ON table_namespace.oid = table_relation.relnamespace
JOIN pg_index index_info ON index_info.indrelid = table_relation.oid
JOIN pg_class index_relation ON index_relation.oid = index_info.indexrelid
JOIN pg_am access_method ON access_method.oid = index_relation.relam
CROSS JOIN LATERAL unnest(index_info.indkey) WITH ORDINALITY AS key_column(attnum, ordinality)
LEFT JOIN pg_attribute table_column ON table_column.attrelid = table_relation.oid AND table_column.attnum = key_column.attnum
WHERE table_namespace.nspname = current_schema() AND table_relation.relname = 'syncmoney_audit_log'
    AND key_column.ordinality <= index_info.indnkeyatts
ORDER BY index_relation.relname, key_column.ordinality;
"@
    } else {
        $columnsSql = @"
SELECT JSON_ARRAY(TABLE_NAME, ORDINAL_POSITION, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE,
    COLUMN_DEFAULT, EXTRA, CHARACTER_SET_NAME, COLLATION_NAME, GENERATION_EXPRESSION)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'syncmoney_audit_log'
ORDER BY ORDINAL_POSITION, COLUMN_NAME;
"@
    $indexesSql = @"
SELECT JSON_ARRAY(INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, COLLATION, INDEX_TYPE, SUB_PART)
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'syncmoney_audit_log'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;
"@
    }
    $columns = @(Invoke-MySqlQuery $columnsSql ($stage + '-audit-columns-snapshot'))
    $indexes = @(Invoke-MySqlQuery $indexesSql ($stage + '-audit-indexes-snapshot'))
    return [ordered]@{ columns=$columns; indexes=$indexes }
}

function Get-BackendSnapshot(
    [string]$stage,
    [string]$expectedBalanceA = '1337.25',
    [string]$expectedBalanceB = '765.50',
    [switch]$IncludeSchemaVersionRows
) {
    $uuidA = Get-OfflineUuid 'AcceptanceA'
    $uuidB = Get-OfflineUuid 'AcceptanceB'
    $sql = if ($script:databaseProduct -ceq 'PostgreSQL') {
        "SELECT player_name || '|' || player_uuid::text || '|' || balance::text || '|' || version::text FROM players WHERE player_uuid IN ('$uuidA','$uuidB') ORDER BY player_name;"
    } else {
        "SELECT CONCAT_WS('|', player_name, player_uuid, CAST(balance AS CHAR), CAST(version AS CHAR)) FROM players WHERE player_uuid IN ('$uuidA','$uuidB') ORDER BY player_name;"
    }
    $rows = @(Invoke-MySqlQuery $sql ($stage + '-' + $script:databaseProduct.ToLowerInvariant() + '-snapshot'))
    if ($rows.Count -ne 2) { throw "Expected exactly two synthetic $script:databaseProduct player rows after $stage; observed $($rows.Count)." }
    $sqlAccounts = [Collections.Generic.List[object]]::new()
    foreach ($row in $rows) {
        $parts = $row.Split('|')
        if ($parts.Count -ne 4 -or $parts[0] -notin @('AcceptanceA', 'AcceptanceB')) { throw "Unexpected MySQL account row after $stage." }
        $expectedUuid = if ($parts[0] -ceq 'AcceptanceA') { $uuidA } else { $uuidB }
        if ($parts[1].ToLowerInvariant() -cne $expectedUuid) { throw "MySQL UUID mismatch for $($parts[0]) after $stage." }
        $balance = ([decimal]::Parse($parts[2], [Globalization.CultureInfo]::InvariantCulture)).ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
        $version = [long]::Parse($parts[3], [Globalization.CultureInfo]::InvariantCulture)
        if ($version -lt 1) { throw "$script:databaseProduct returned a non-positive version for $($parts[0]) after $stage." }
        $sqlAccounts.Add([ordered]@{ name=$parts[0]; uuid=$expectedUuid; balance=$balance; version=$version })
    }
    $redisAccounts = [Collections.Generic.List[object]]::new()
    foreach ($account in @([ordered]@{ name='AcceptanceA'; uuid=$uuidA }, [ordered]@{ name='AcceptanceB'; uuid=$uuidB })) {
        $balanceResult = Invoke-Redis @('-n', '15', 'GET', ('syncmoney:balance:' + $account.uuid)) ($stage + '-redis-' + $account.name + '-balance')
        $versionResult = Invoke-Redis @('-n', '15', 'GET', ('syncmoney:version:' + $account.uuid)) ($stage + '-redis-' + $account.name + '-version')
        $balanceRaw = $balanceResult.output.Trim()
        $versionRaw = $versionResult.output.Trim()
        if ([string]::IsNullOrWhiteSpace($balanceRaw) -or $balanceRaw -ceq '(nil)' -or $versionRaw -notmatch '^[1-9]\d*$') {
            throw "Redis balance/version is missing for $($account.name) after $stage."
        }
        $balance = ([decimal]::Parse($balanceRaw, [Globalization.CultureInfo]::InvariantCulture)).ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
        $redisAccounts.Add([ordered]@{ name=$account.name; uuid=$account.uuid; balance=$balance; version=[long]$versionRaw })
    }
    $snapshot = [ordered]@{ stage=$stage; sqlAccounts=@($sqlAccounts); redisAccounts=@($redisAccounts) }
    if ($IncludeSchemaVersionRows) {
        $schemaSql = if ($script:databaseProduct -ceq 'PostgreSQL') {
            'SELECT id || ''|'' || version::text FROM syncmoney_schema_version ORDER BY id;'
        } else {
            "SELECT CONCAT_WS('|', id, version) FROM syncmoney_schema_version ORDER BY id;"
        }
        $snapshot.schemaVersionRows = @(Invoke-MySqlQuery $schemaSql ($stage + '-schema-version-snapshot'))
        if ($script:legacySqlDataUpgrade) {
            $snapshot.auditSchema = Get-T21LegacyAuditSchemaMetadata $stage
        }
    }
    $validState = if ($script:legacySqlDataUpgrade) {
        Test-T21LegacySqlAccountState $snapshot $expectedBalanceA $expectedBalanceB
    } else {
        Test-T21MySqlRuntimeAccountParity @($sqlAccounts) @($redisAccounts)
    }
    if (-not $validState) { throw "$script:databaseProduct and Redis did not match the expected synthetic account state after $stage." }
    return $snapshot
}

function Get-T21StartupFailureSchemaState([switch]$AfterAuditSchemaV10Upgrade) {
    if ($script:databaseProduct -ceq 'PostgreSQL') {
        $stateSql = @"
SELECT COALESCE((SELECT version::text FROM syncmoney_schema_version WHERE id = '1'), 'NULL') || '|' ||
       COALESCE((SELECT version::text FROM syncmoney_schema_version WHERE id = 'audit'), 'NULL') || '|' ||
       CASE WHEN EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'syncmoney_audit_log') THEN 'true' ELSE 'false' END || '|' ||
       CASE WHEN EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'players') THEN 'true' ELSE 'false' END;
"@
    } else {
        $stateSql = @"
SELECT CONCAT_WS('|',
    COALESCE((SELECT CAST(version AS CHAR) FROM syncmoney_schema_version WHERE id = '1'), 'NULL'),
    COALESCE((SELECT CAST(version AS CHAR) FROM syncmoney_schema_version WHERE id = 'audit'), 'NULL'),
    IF(EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'syncmoney_audit_log'), 'true', 'false'),
    IF(EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'players'), 'true', 'false'));
"@
    }
    $rows = @(Invoke-MySqlQuery $stateSql 'newer-audit-schema-state')
    if ($rows.Count -ne 1) { throw 'Could not observe the newer audit-schema fixture state.' }
    $parts = $rows[0].Trim().Split('|')
    if ($parts.Count -ne 4 -or $parts[2] -notin @('true', 'false') -or $parts[3] -notin @('true', 'false')) {
        throw 'The newer audit-schema state query returned an unexpected shape.'
    }
    $mainVersion = if ($parts[0] -ceq 'NULL') { $null } else { [int]::Parse($parts[0], [Globalization.CultureInfo]::InvariantCulture) }
    $auditVersion = if ($parts[1] -ceq 'NULL') { $null } else { [int]::Parse($parts[1], [Globalization.CultureInfo]::InvariantCulture) }
    if ($script:databaseProduct -ceq 'PostgreSQL') {
        $mainVersionRowJson = @(Invoke-MySqlQuery "SELECT COALESCE((SELECT to_jsonb(v)::text FROM syncmoney_schema_version AS v WHERE id = '1'), 'null');" 'newer-audit-schema-main-version-row')[0].Trim()
        $auditVersionRowJson = @(Invoke-MySqlQuery "SELECT COALESCE((SELECT to_jsonb(v)::text FROM syncmoney_schema_version AS v WHERE id = 'audit'), 'null');" 'newer-audit-schema-audit-version-row')[0].Trim()
    } else {
        $mainVersionRowJson = @(Invoke-MySqlQuery "SELECT COALESCE((SELECT JSON_OBJECT('id', id, 'version', version, 'applied_at', applied_at, 'description', description) FROM syncmoney_schema_version WHERE id = '1'), 'null');" 'newer-audit-schema-main-version-row')[0].Trim()
        $auditVersionRowJson = @(Invoke-MySqlQuery "SELECT COALESCE((SELECT JSON_OBJECT('id', id, 'version', version, 'applied_at', applied_at, 'description', description) FROM syncmoney_schema_version WHERE id = 'audit'), 'null');" 'newer-audit-schema-audit-version-row')[0].Trim()
    }
    $auditTableExists = $parts[2] -ceq 'true'
    $playersTableExists = $parts[3] -ceq 'true'
    $playerRows = $null
    $playerRowsJson = $null
    if ($playersTableExists) {
        $playerCount = @(Invoke-MySqlQuery 'SELECT COUNT(*) FROM players;' 'newer-audit-schema-player-row-count')
        if ($playerCount.Count -ne 1) { throw 'Could not observe player rows after the failed startup.' }
        $playerRows = [long]::Parse($playerCount[0].Trim(), [Globalization.CultureInfo]::InvariantCulture)
        $playerRowsSql = if ($script:databaseProduct -ceq 'PostgreSQL') { @"
SELECT COALESCE(json_agg(json_build_object(
    'player_uuid', player_uuid::text, 'player_name', player_name, 'balance', balance::text,
    'version', version::text, 'last_server', last_server, 'updated_at', to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS.US'))
    ORDER BY player_uuid)::text, '[]')
FROM players;
"@ } else { @"
SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT(
    'player_uuid', player_uuid, 'player_name', player_name, 'balance', CAST(balance AS CHAR),
    'version', CAST(version AS CHAR), 'last_server', last_server, 'updated_at', DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s'))), JSON_ARRAY())
FROM players;
"@ }
        $rawPlayerRowsJson = @(Invoke-MySqlQuery $playerRowsSql 'newer-audit-schema-player-rows')[0].Trim()
        $playerRowsJson = ConvertTo-T21AuditSchemaUpgradePlayerRowsJson $rawPlayerRowsJson
    }
    $auditColumnsJson = $null
    $auditIndexesJson = $null
    $auditRowsJson = $null
    if ($auditTableExists) {
        if ($script:databaseProduct -ceq 'PostgreSQL') {
            $auditColumnsSql = @"
SELECT COALESCE(json_agg(to_jsonb(c) ORDER BY c.ordinal_position)::text, '[]')
FROM (
    SELECT *
    FROM information_schema.columns
    WHERE table_schema = current_schema() AND table_name = 'syncmoney_audit_log'
) AS c;
"@
            $auditIndexesSql = if ($script:successfulV10AuditSchemaUpgrade) { @"
SELECT COALESCE(json_agg(json_build_object(
    'name', index_class.relname,
    'definition', pg_get_indexdef(index_class.oid),
    'isPrimaryConstraint', EXISTS (
        SELECT 1 FROM pg_constraint AS constraint_row
        WHERE constraint_row.conindid = index_class.oid AND constraint_row.contype = 'p'
    ),
    'isPrimaryIndex', index_state.indisprimary,
    'isUnique', index_state.indisunique,
    'isValid', index_state.indisvalid,
    'isReady', index_state.indisready,
    'isLive', index_state.indislive
) ORDER BY index_class.relname)::text, '[]')
FROM pg_class AS table_class
JOIN pg_namespace AS table_namespace ON table_namespace.oid = table_class.relnamespace
JOIN pg_index AS index_state ON index_state.indrelid = table_class.oid
JOIN pg_class AS index_class ON index_class.oid = index_state.indexrelid
WHERE table_namespace.nspname = current_schema() AND table_class.relname = 'syncmoney_audit_log';
"@ } else { @"
SELECT COALESCE(json_agg(json_build_object('name', indexname, 'definition', indexdef) ORDER BY indexname)::text, '[]')
FROM pg_indexes
WHERE schemaname = current_schema() AND tablename = 'syncmoney_audit_log';
"@ }
            $auditRowsSql = @"
SELECT COALESCE(json_agg(to_jsonb(a) ORDER BY id)::text, '[]')
FROM syncmoney_audit_log AS a;
"@
            $auditColumnsJson = @(Invoke-MySqlQuery $auditColumnsSql 'newer-audit-schema-columns')[0].Trim()
            $auditIndexesJson = @(Invoke-MySqlQuery $auditIndexesSql 'newer-audit-schema-indexes')[0].Trim()
            $auditRowsJson = @(Invoke-MySqlQuery $auditRowsSql 'newer-audit-schema-rows')[0].Trim()
        } else {
            $auditColumnsSql = @"
SELECT JSON_OBJECT(
    'table_schema', TABLE_SCHEMA, 'table_name', TABLE_NAME, 'column_name', COLUMN_NAME,
    'ordinal_position', ORDINAL_POSITION, 'column_default', COLUMN_DEFAULT,
    'column_default_is_null', IF(COLUMN_DEFAULT IS NULL, 'true', 'false'), 'is_nullable', IS_NULLABLE,
    'column_type', COLUMN_TYPE, 'extra', EXTRA, 'character_set_name', CHARACTER_SET_NAME,
    'collation_name', COLLATION_NAME, 'generation_expression', GENERATION_EXPRESSION)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'syncmoney_audit_log'
ORDER BY ORDINAL_POSITION, COLUMN_NAME;
"@
            $columnRows = @(Invoke-MySqlQuery $auditColumnsSql 'newer-audit-schema-columns')
            $columnObjects = [Collections.Generic.List[object]]::new()
            foreach ($columnRow in $columnRows) { $columnObjects.Add((ConvertFrom-Json -InputObject $columnRow -AsHashtable -ErrorAction Stop)) }
            $auditColumnsJson = ConvertTo-Json -InputObject ([object[]]$columnObjects.ToArray()) -Depth 10 -Compress

            $visibilityExpression = if ($script:databaseProduct -ceq 'MariaDB') {
                "IF(IGNORED = 'YES', 'NO', 'YES')"
            } else {
                'IS_VISIBLE'
            }
            $auditIndexesSql = @"
SELECT JSON_OBJECT('name', INDEX_NAME, 'nonUnique', NON_UNIQUE, 'position', SEQ_IN_INDEX,
    'columnName', COLUMN_NAME, 'direction', COLLATION, 'indexType', INDEX_TYPE,
    'visibility', $visibilityExpression, 'subPart', SUB_PART)
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'syncmoney_audit_log'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;
"@
            $indexRows = @(
                @(Invoke-MySqlQuery $auditIndexesSql 'newer-audit-schema-indexes') |
                    ForEach-Object { ConvertFrom-Json -InputObject $_ -AsHashtable -ErrorAction Stop }
            )
            $indexObjects = [Collections.Generic.List[object]]::new()
            foreach ($indexGroup in @($indexRows | Group-Object -Property name | Sort-Object -Property Name)) {
                $orderedRows = @($indexGroup.Group | Sort-Object { [int]$_.position })
                $indexColumns = @(
                    foreach ($indexRow in $orderedRows) {
                        [ordered]@{
                            position = [int]$indexRow.position
                            columnName = [string]$indexRow.columnName
                            direction = [string]$indexRow.direction
                            subPart = $indexRow.subPart
                        }
                    }
                )
                $indexObjects.Add([ordered]@{
                    name = [string]$indexGroup.Name
                    nonUnique = [int]$orderedRows[0].nonUnique
                    indexType = [string]$orderedRows[0].indexType
                    visibility = [string]$orderedRows[0].visibility
                    columns = $indexColumns
                })
            }
            $auditIndexesJson = ConvertTo-Json -InputObject ([object[]]$indexObjects.ToArray()) -Depth 10 -Compress

            $auditRowsSql = if ($script:incompleteV10AuditTableStartupFailure) {
                'SELECT JSON_OBJECT(''id'', id) FROM syncmoney_audit_log ORDER BY id;'
            } elseif ($script:requiredV11DdlPermissionDeniedStartupFailure) { @"
SELECT JSON_OBJECT('id', id, 'timestamp', timestamp, 'type', type, 'player_uuid', player_uuid,
    'player_name', player_name, 'amount', amount, 'balance_after', balance_after, 'source', source,
    'server', server, 'target_uuid', target_uuid, 'target_name', target_name, 'reason', reason,
    'merged_count', merged_count, 't21_sentinel', t21_sentinel)
FROM syncmoney_audit_log
ORDER BY id;
"@ } elseif ($script:successfulV10AuditSchemaUpgrade -and $AfterAuditSchemaV10Upgrade) { @"
SELECT JSON_OBJECT('id', id, 'timestamp', timestamp, 'type', type, 'player_uuid', player_uuid,
    'player_name', player_name, 'amount', amount, 'balance_after', balance_after, 'source', source,
    'server', server, 'target_uuid', target_uuid, 'target_name', target_name, 'reason', reason,
    'merged_count', merged_count, 'request_id', request_id, 'sequence', sequence)
FROM syncmoney_audit_log
ORDER BY id;
"@ } elseif ($script:successfulV10AuditSchemaUpgrade) { @"
SELECT JSON_OBJECT('id', id, 'timestamp', timestamp, 'type', type, 'player_uuid', player_uuid,
    'player_name', player_name, 'amount', amount, 'balance_after', balance_after, 'source', source,
    'server', server, 'target_uuid', target_uuid, 'target_name', target_name, 'reason', reason,
    'merged_count', merged_count)
FROM syncmoney_audit_log
ORDER BY id;
"@ } else { @"
SELECT JSON_OBJECT('id', id, 'timestamp', timestamp, 'type', type, 'player_uuid', player_uuid,
    'player_name', player_name, 'amount', amount, 'balance_after', balance_after, 'source', source,
    'server', server, 'target_uuid', target_uuid, 'target_name', target_name, 'reason', reason,
    'merged_count', merged_count, 'request_id', request_id, 'sequence', sequence, 't21_sentinel', t21_sentinel)
FROM syncmoney_audit_log
ORDER BY id;
"@ }
            $auditRowLines = @(Invoke-MySqlQuery $auditRowsSql 'newer-audit-schema-rows')
            $auditRowsJson = '[' + ($auditRowLines -join ',') + ']'
        }
    }
    return [ordered]@{
        mainVersion = $mainVersion
        mainVersionRowJson = $mainVersionRowJson
        auditVersion = $auditVersion
        auditVersionRowJson = $auditVersionRowJson
        auditTableExists = [bool]$auditTableExists
        playersTableExists = $playersTableExists
        playerRows = $playerRows
        playerRowsJson = $playerRowsJson
        auditColumnsJson = $auditColumnsJson
        auditIndexesJson = $auditIndexesJson
        auditRowsJson = $auditRowsJson
    }
}

function Seed-T21AuditSchemaV10Upgrade([switch]$SeedExistingPlayerRow, [switch]$SeedMultipleExistingPlayerRows) {
    if (($SeedExistingPlayerRow.IsPresent -or $SeedMultipleExistingPlayerRows.IsPresent) -and
        -not (Test-T21AuditSchemaV10UpgradePlayerRowPreservationTarget -DatabaseProduct $script:databaseProduct -PlatformEndpoint $PlatformEndpoint)) {
        throw 'Successful audit-schema migration with an existing player-row sentinel supports MySQL 8.0.42, MariaDB 12.3.3-MariaDB, or PostgreSQL 18.1 on the six exact user-pinned Paper, Folia, and Canvas endpoints only.'
    }
    $ddl = @'
CREATE TABLE syncmoney_schema_version (
    id VARCHAR(50) PRIMARY KEY,
    version INT NOT NULL DEFAULT 0,
    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    description VARCHAR(255)
);
INSERT INTO syncmoney_schema_version (id, version, applied_at, description)
VALUES ('1', 1, TIMESTAMP '2026-10-14 00:00:00', 'T21 pre-existing main schema');
INSERT INTO syncmoney_schema_version (id, version, applied_at, description)
VALUES ('audit', 10, TIMESTAMP '2026-10-14 00:00:00', 'T21 v10 migration sentinel');
CREATE TABLE syncmoney_audit_log (
    id VARCHAR(36) PRIMARY KEY,
    timestamp BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    player_name VARCHAR(16),
    amount DECIMAL(20, 2) NOT NULL,
    balance_after DECIMAL(20, 2) NOT NULL,
    source VARCHAR(30) NOT NULL,
    server VARCHAR(50) NOT NULL,
    target_uuid VARCHAR(36),
    target_name VARCHAR(16),
    reason VARCHAR(255),
    merged_count INT DEFAULT 1
);
INSERT INTO syncmoney_audit_log (
    id, timestamp, type, player_uuid, player_name, amount, balance_after, source, server,
    target_uuid, target_name, reason, merged_count
) VALUES (
    '00000000-0000-0000-0000-000000000330', 1791936000000, 'DEPOSIT',
    '00000000-0000-0000-0000-000000000330', 'T21Fixture', 1.25, 99.99,
    'TEST', 't21-schema-upgrade', NULL, NULL, 'v10 upgrade preservation sentinel', 1
);
'@
    $null = Invoke-MySqlQuery $ddl 'seed-v10-audit-schema-upgrade'
    if ($SeedExistingPlayerRow.IsPresent -or $SeedMultipleExistingPlayerRows.IsPresent) {
        if ($script:databaseProduct -ceq 'PostgreSQL') {
            $playerDdl = @'
CREATE TABLE players (
    player_uuid VARCHAR(36) PRIMARY KEY,
    player_name VARCHAR(16),
    balance DECIMAL(20,2) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    last_server VARCHAR(64),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_player_name ON players (player_name);
'@
            $playerInsert = if ($SeedMultipleExistingPlayerRows.IsPresent) {
@'
INSERT INTO players (player_uuid, player_name, balance, version, last_server, updated_at) VALUES
('00000000-0000-0000-0000-00000000cafe', 'T21-ROW-SENTINEL', 1234.56, 73, 't21-player-preservation', TIMESTAMP '2026-10-16 12:34:56.000000'),
('00000000-0000-0000-0000-00000000caff', 'T21-ROW-SECOND', 987.65, 19, 't21-row-second', TIMESTAMP '2026-10-16 12:34:57.000000'),
('00000000-0000-0000-0000-00000000cab0', 'T21-ROW-THIRD', 0.01, 0, 't21-row-third', TIMESTAMP '2026-10-16 12:34:58.000000');
'@
            } else {
@'
INSERT INTO players (player_uuid, player_name, balance, version, last_server, updated_at)
VALUES ('00000000-0000-0000-0000-00000000cafe', 'T21-ROW-SENTINEL', 1234.56, 73, 't21-player-preservation', TIMESTAMP '2026-10-16 12:34:56.000000');
'@
            }
        } else {
            $playerDdl = @'
CREATE TABLE players (
    player_uuid VARCHAR(36) PRIMARY KEY,
    player_name VARCHAR(16),
    balance DECIMAL(20,2) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    last_server VARCHAR(64),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_player_name (player_name)
);
'@
            $playerInsert = if ($SeedMultipleExistingPlayerRows.IsPresent) {
@'
INSERT INTO players (player_uuid, player_name, balance, version, last_server, updated_at) VALUES
('00000000-0000-0000-0000-00000000cafe', 'T21-ROW-SENTINEL', 1234.56, 73, 't21-player-preservation', '2026-10-16 12:34:56'),
('00000000-0000-0000-0000-00000000caff', 'T21-ROW-SECOND', 987.65, 19, 't21-row-second', '2026-10-16 12:34:57'),
('00000000-0000-0000-0000-00000000cab0', 'T21-ROW-THIRD', 0.01, 0, 't21-row-third', '2026-10-16 12:34:58');
'@
            } else {
@'
INSERT INTO players (player_uuid, player_name, balance, version, last_server, updated_at)
VALUES ('00000000-0000-0000-0000-00000000cafe', 'T21-ROW-SENTINEL', 1234.56, 73, 't21-player-preservation', '2026-10-16 12:34:56');
'@
            }
        }
        $null = Invoke-MySqlQuery $playerDdl 'seed-v10-audit-schema-existing-player-row-table'
        $insertStage = if ($SeedMultipleExistingPlayerRows.IsPresent) {
            'seed-v10-audit-schema-existing-multiple-player-rows'
        } else {
            'seed-v10-audit-schema-existing-player-row'
        }
        $null = Invoke-MySqlQuery $playerInsert $insertStage
    }
    $state = Get-T21StartupFailureSchemaState
    $columns = @(ConvertFrom-Json -InputObject $state.auditColumnsJson -AsHashtable -ErrorAction Stop)
    $indexes = @(ConvertFrom-Json -InputObject $state.auditIndexesJson -AsHashtable -ErrorAction Stop)
    $rows = @(ConvertFrom-Json -InputObject $state.auditRowsJson -AsHashtable -ErrorAction Stop)
    $playerFixtureValid = if ($SeedMultipleExistingPlayerRows.IsPresent) {
        $state.playersTableExists -and $state.playerRows -eq 3 -and
            (Test-T21AuditSchemaUpgradePlayerRowsPreserved $state.playerRowsJson -DatabaseProduct $script:databaseProduct -ExpectedRowCount 3)
    } elseif ($SeedExistingPlayerRow.IsPresent) {
        $state.playersTableExists -and $state.playerRows -eq 1 -and
            (Test-T21AuditSchemaUpgradePlayerRowSentinel $state.playerRowsJson $script:databaseProduct)
    } else { -not $state.playersTableExists -and $null -eq $state.playerRows }
    if ($state.mainVersion -ne 1 -or $state.auditVersion -ne 10 -or
        -not $state.auditTableExists -or -not $playerFixtureValid -or
        $state.mainVersionRowJson -notmatch 'T21 pre-existing main schema' -or
        $state.auditVersionRowJson -notmatch 'T21 v10 migration sentinel' -or
        $columns.Count -ne 13 -or $indexes.Count -ne 1 -or $rows.Count -ne 1 -or
        $rows[0].id -cne '00000000-0000-0000-0000-000000000330' -or
        $rows[0].reason -cne 'v10 upgrade preservation sentinel') {
        throw "The isolated $script:databaseProduct database did not retain the exact seeded audit v10 fixture."
    }
    return $state
}

function Seed-T21NewerAuditSchema {
    $ddl = @'
CREATE TABLE syncmoney_schema_version (
    id VARCHAR(50) PRIMARY KEY,
    version INT NOT NULL DEFAULT 0,
    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    description VARCHAR(255)
);
INSERT INTO syncmoney_schema_version (id, version, applied_at, description)
VALUES ('audit', 12, TIMESTAMP '2026-10-14 00:00:00', 'T21 newer audit schema preservation sentinel');
CREATE TABLE syncmoney_audit_log (
    id VARCHAR(36) PRIMARY KEY,
    timestamp BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    player_name VARCHAR(16),
    amount DECIMAL(20, 2) NOT NULL,
    balance_after DECIMAL(20, 2) NOT NULL,
    source VARCHAR(30) NOT NULL,
    server VARCHAR(50) NOT NULL,
    target_uuid VARCHAR(36),
    target_name VARCHAR(16),
    reason VARCHAR(255),
    merged_count INT DEFAULT 1,
    request_id VARCHAR(36),
    sequence BIGINT DEFAULT 0,
    t21_sentinel TEXT NOT NULL
);
INSERT INTO syncmoney_audit_log (
    id, timestamp, type, player_uuid, player_name, amount, balance_after, source, server,
    target_uuid, target_name, reason, merged_count, request_id, sequence, t21_sentinel
) VALUES (
    '00000000-0000-0000-0000-000000000321', 1791936000000, 'DEPOSIT',
    '00000000-0000-0000-0000-000000000321', 'T21Fixture', 1.25, 99.99,
    'TEST', 't21-startup-failure', NULL, NULL, 'v12 preservation sentinel', 1,
    '00000000-0000-0000-0000-000000000322', 77, 't21-v12-preservation-sentinel'
);
CREATE INDEX idx_audit_timestamp ON syncmoney_audit_log (timestamp DESC, sequence DESC);
CREATE INDEX idx_audit_player ON syncmoney_audit_log (player_uuid, timestamp DESC, sequence DESC);
CREATE INDEX idx_audit_type_timestamp_sequence ON syncmoney_audit_log (type, timestamp DESC, sequence DESC);
'@
    $null = Invoke-MySqlQuery $ddl 'seed-newer-audit-schema'
    $state = Get-T21StartupFailureSchemaState
    if ($null -ne $state.mainVersion -or $state.auditVersion -ne 12 -or
        -not $state.auditTableExists -or $state.playersTableExists -or $null -ne $state.playerRows -or
        $state.auditVersionRowJson -notmatch '"description": "T21 newer audit schema preservation sentinel"' -or
        $state.auditColumnsJson -notmatch '"column_name"\s*:\s*"t21_sentinel"' -or
        $state.auditIndexesJson -notmatch 'idx_audit_type_timestamp_sequence' -or
        $state.auditRowsJson -notmatch 't21-v12-preservation-sentinel') {
        throw 'The isolated SQL database did not retain the exact pre-start newer audit-schema fixture.'
    }
    return $state
}

function Seed-T21IncompleteV10AuditTable([switch]$SeedExistingPlayerRow) {
    if ($script:databaseProduct -notin @('MySQL', 'MariaDB', 'PostgreSQL')) { throw 'The incomplete-v10-audit-table fixture requires MySQL, MariaDB, or PostgreSQL.' }
    if ($SeedExistingPlayerRow.IsPresent -and
        -not (Test-T21IncompleteAuditTablePlayerRowPreservationTarget -DatabaseProduct $script:databaseProduct -PlatformEndpoint $PlatformEndpoint)) {
        throw 'The pre-existing player-row sentinel is restricted to all three SQL products on the user-pinned minimum/maximum Paper, Folia, and Canvas endpoints.'
    }
    $ddl = @'
CREATE TABLE syncmoney_schema_version (
    id VARCHAR(50) PRIMARY KEY,
    version INT NOT NULL DEFAULT 0,
    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    description VARCHAR(255)
);
INSERT INTO syncmoney_schema_version (id, version, applied_at, description)
VALUES ('1', 1, TIMESTAMP '2026-10-14 00:00:00', 'T21 pre-existing main schema');
INSERT INTO syncmoney_schema_version (id, version, applied_at, description)
VALUES ('audit', 9, TIMESTAMP '2026-10-14 00:00:00', 'T21 incomplete audit v9 sentinel');
CREATE TABLE syncmoney_audit_log (id VARCHAR(36) PRIMARY KEY);
INSERT INTO syncmoney_audit_log (id) VALUES ('00000000-0000-0000-0000-000000000329');
'@
    $null = Invoke-MySqlQuery $ddl 'seed-incomplete-v10-audit-table'
    if ($SeedExistingPlayerRow.IsPresent) {
        if ($script:databaseProduct -ceq 'PostgreSQL') {
            $playerDdl = @'
CREATE TABLE players (
    player_uuid VARCHAR(36) PRIMARY KEY,
    player_name VARCHAR(16),
    balance DECIMAL(20,2) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    last_server VARCHAR(64),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_player_name ON players (player_name);
INSERT INTO players (player_uuid, player_name, balance, version, last_server, updated_at)
VALUES ('00000000-0000-0000-0000-00000000cafe', 'T21-ROW-SENTINEL', 1234.56, 73, 't21-player-preservation', TIMESTAMP '2026-10-16 12:34:56');
'@
        } else {
            $playerDdl = @'
CREATE TABLE players (
    player_uuid VARCHAR(36) PRIMARY KEY,
    player_name VARCHAR(16),
    balance DECIMAL(20,2) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    last_server VARCHAR(64),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_player_name (player_name)
);
INSERT INTO players (player_uuid, player_name, balance, version, last_server, updated_at)
VALUES ('00000000-0000-0000-0000-00000000cafe', 'T21-ROW-SENTINEL', 1234.56, 73, 't21-player-preservation', '2026-10-16 12:34:56');
'@
        }
        $null = Invoke-MySqlQuery $playerDdl 'seed-incomplete-v10-existing-player-row'
    }
    $state = Get-T21StartupFailureSchemaState
    $columns = @(ConvertFrom-Json -InputObject $state.auditColumnsJson -AsHashtable -ErrorAction Stop)
    $rows = @(ConvertFrom-Json -InputObject $state.auditRowsJson -AsHashtable -ErrorAction Stop)
    $playerFixtureValid = if ($SeedExistingPlayerRow.IsPresent) {
        $state.playersTableExists -and $state.playerRows -eq 1 -and
            (Test-T21IncompleteV10PlayerRowSentinel $state.playerRowsJson -DatabaseProduct $script:databaseProduct)
    } else { -not $state.playersTableExists -and $null -eq $state.playerRows }
    if ($state.mainVersion -ne 1 -or $state.auditVersion -ne 9 -or
        -not $state.auditTableExists -or -not $playerFixtureValid -or
        $state.mainVersionRowJson -notmatch 'T21 pre-existing main schema' -or
        $state.auditVersionRowJson -notmatch 'T21 incomplete audit v9 sentinel' -or
        $columns.Count -ne 1 -or $columns[0].column_name -cne 'id' -or
        $rows.Count -ne 1 -or $rows[0].id -cne '00000000-0000-0000-0000-000000000329') {
        throw "The isolated $script:databaseProduct database did not retain the exact pre-start incomplete-v10 audit fixture."
    }
    return $state
}

function Test-T21MySqlCreateWithoutAlterGrantSet(
    [string[]]$GrantLines,
    [string]$DatabaseName,
    [string]$UserName,
    [string]$HostName,
    [ValidateSet('MySQL', 'MariaDB')][string]$DatabaseProduct = 'MySQL'
) {
    if ($null -eq $GrantLines -or $GrantLines.Count -ne 2 -or
        [string]::IsNullOrWhiteSpace($DatabaseName) -or [string]::IsNullOrWhiteSpace($UserName) -or [string]::IsNullOrWhiteSpace($HostName)) {
        return $false
    }

    $grantPattern = '^\s*GRANT\s+(?<privileges>.*?)\s+ON\s+(?<scope>\*\.\*|\x60(?<schema>[^\x60]+)\x60\.\*)\s+TO\s+\x60(?<user>[^\x60]+)\x60@\x60(?<host>[^\x60]+)\x60\s*$'
    $usageGrantCount = 0
    $schemaGrantCount = 0
    foreach ($grantLine in $GrantLines) {
        if ($grantLine -isnot [string] -or [string]::IsNullOrWhiteSpace($grantLine)) { return $false }
        $hasAuthenticationSuffix = $false
        if ($grantLine -match '(?i)IDENTIFIED\s+BY\s+PASSWORD\b') {
            if ($DatabaseProduct -cne 'MariaDB') { return $false }
            $authenticationSuffixMatch = [regex]::Match(
                $grantLine,
                "^(?<base>\s*GRANT\s+USAGE\s+ON\s+\*\.\*\s+TO\s+\x60[^\x60]+\x60@\x60[^\x60]+\x60)\s+IDENTIFIED\s+BY\s+PASSWORD\s+'<redacted>'\s*$",
                [Text.RegularExpressions.RegexOptions]::IgnoreCase
            )
            if (-not $authenticationSuffixMatch.Success) { return $false }
            $grantLine = $authenticationSuffixMatch.Groups['base'].Value
            $hasAuthenticationSuffix = $true
        }
        $grantMatch = [regex]::Match($grantLine, $grantPattern, [Text.RegularExpressions.RegexOptions]::IgnoreCase)
        if (-not $grantMatch.Success -or
            $grantMatch.Groups['user'].Value -cne $UserName -or
            $grantMatch.Groups['host'].Value -cne $HostName) { return $false }

        $privilegeNames = @($grantMatch.Groups['privileges'].Value.Split(',') | ForEach-Object { $_.Trim().ToUpperInvariant() })
        if ($privilegeNames.Count -eq 0 -or @($privilegeNames | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -gt 0) {
            return $false
        }

        if ($grantMatch.Groups['scope'].Value -ceq '*.*') {
            if ($privilegeNames.Count -ne 1 -or $privilegeNames[0] -cne 'USAGE') { return $false }
            $usageGrantCount++
            continue
        }

        if ($hasAuthenticationSuffix -or $grantMatch.Groups['schema'].Value -cne $DatabaseName -or
            'ALTER' -in $privilegeNames -or 'CREATE' -notin $privilegeNames) { return $false }
        $schemaGrantCount++
    }

    return $usageGrantCount -eq 1 -and $schemaGrantCount -eq 1
}

function Seed-T21RequiredV11DdlPermissionFailure {
    if (-not (Test-T21RequiredV11DdlFailureTarget -DatabaseProduct $script:databaseProduct -PlatformEndpoint $PlatformEndpoint)) {
        throw 'The required-v11-DDL-permission-failure seed supports all three SQL products on the user-pinned minimum and maximum Paper, Folia, and Canvas endpoints; supplemental current-stable Paper and unpinned endpoints are not supported.'
    }
    $ddl = @'
CREATE TABLE syncmoney_schema_version (
    id VARCHAR(50) PRIMARY KEY,
    version INT NOT NULL DEFAULT 0,
    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    description VARCHAR(255)
);
INSERT INTO syncmoney_schema_version (id, version, applied_at, description)
VALUES ('1', 1, TIMESTAMP '2026-10-14 00:00:00', 'T21 pre-existing main schema');
INSERT INTO syncmoney_schema_version (id, version, applied_at, description)
VALUES ('audit', 10, TIMESTAMP '2026-10-14 00:00:00', 'T21 required v11 DDL failure sentinel');
CREATE TABLE syncmoney_audit_log (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    timestamp BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    player_name VARCHAR(16),
    amount DECIMAL(20, 2) NOT NULL,
    balance_after DECIMAL(20, 2) NOT NULL,
    source VARCHAR(30) NOT NULL,
    server VARCHAR(50) NOT NULL,
    target_uuid VARCHAR(36),
    target_name VARCHAR(16),
    reason VARCHAR(255),
    merged_count INT DEFAULT 1,
    t21_sentinel TEXT NOT NULL
);
INSERT INTO syncmoney_audit_log (
    id, timestamp, type, player_uuid, player_name, amount, balance_after, source, server,
    target_uuid, target_name, reason, merged_count, t21_sentinel
) VALUES (
    '00000000-0000-0000-0000-000000000330', 1791936000000, 'DEPOSIT',
    '00000000-0000-0000-0000-000000000330', 'T21Fixture', 1.25, 99.99,
    'TEST', 't21-v11-ddl-permission', NULL, NULL, 'v10 preservation sentinel', 1,
    't21-v10-preservation-sentinel'
);
'@
    if ($script:databaseProduct -ceq 'PostgreSQL') {
        $null = Invoke-PostgresQuery $ddl 'seed-v11-audit-ddl-permission-failure' -asRoot -Database $script:databaseName
        $null = Invoke-PostgresQuery "GRANT SELECT, INSERT, UPDATE, DELETE ON syncmoney_schema_version, syncmoney_audit_log TO $script:databaseUser;" `
            'grant-postgresql-v11-audit-dml' -asRoot -Database $script:databaseName
        $privilegeSql = @"
SELECT json_build_object(
    'databaseName', current_database(),
    'databaseOwner', pg_get_userbyid(d.datdba),
    'schemaName', n.nspname,
    'schemaOwner', pg_get_userbyid(n.nspowner),
    'grantee', r.rolname,
    'roleSuperuser', r.rolsuper,
    'roleMemberOfAuditOwner', pg_has_role(r.oid, c.relowner, 'MEMBER'),
    'auditTableOwner', pg_get_userbyid(c.relowner),
    'databaseConnectGranted', has_database_privilege(r.oid, d.oid, 'CONNECT'),
    'schemaUsageGranted', has_schema_privilege(r.oid, n.oid, 'USAGE'),
    'schemaCreateGranted', has_schema_privilege(r.oid, n.oid, 'CREATE'),
    'tableSelectGranted', has_table_privilege(r.oid, c.oid, 'SELECT'),
    'tableInsertGranted', has_table_privilege(r.oid, c.oid, 'INSERT'),
    'tableUpdateGranted', has_table_privilege(r.oid, c.oid, 'UPDATE'),
    'tableDeleteGranted', has_table_privilege(r.oid, c.oid, 'DELETE'))::text
FROM pg_database AS d
JOIN pg_roles AS r ON r.rolname = '$script:databaseUser'
JOIN pg_namespace AS n ON n.nspname = 'public'
JOIN pg_class AS c ON c.relnamespace = n.oid AND c.relname = 'syncmoney_audit_log' AND c.relkind = 'r'
WHERE d.datname = current_database();
"@
        $privilegeRows = @(Invoke-PostgresQuery $privilegeSql 'postgresql-audit-ddl-privileges' -asRoot -Database $script:databaseName)
        if ($privilegeRows.Count -ne 1) { throw 'PostgreSQL returned no unique app-role audit-table ownership evidence.' }
        $evidenceJson = $privilegeRows[0].Trim()
        $evidence = ConvertFrom-Json -InputObject $evidenceJson -AsHashtable -ErrorAction Stop
        $script:databaseDdlPrivileges = [ordered]@{
            product = 'PostgreSQL'
            grantee = [string]$evidence.grantee
            databaseName = [string]$evidence.databaseName
            schemaName = [string]$evidence.schemaName
            databaseOwner = [string]$evidence.databaseOwner
            schemaOwner = [string]$evidence.schemaOwner
            auditTableOwner = [string]$evidence.auditTableOwner
            roleSuperuser = [bool]$evidence.roleSuperuser
            roleMemberOfAuditOwner = [bool]$evidence.roleMemberOfAuditOwner
            databaseConnectGranted = [bool]$evidence.databaseConnectGranted
            schemaUsageGranted = [bool]$evidence.schemaUsageGranted
            schemaCreateGranted = [bool]$evidence.schemaCreateGranted
            tableSelectGranted = [bool]$evidence.tableSelectGranted
            tableInsertGranted = [bool]$evidence.tableInsertGranted
            tableUpdateGranted = [bool]$evidence.tableUpdateGranted
            tableDeleteGranted = [bool]$evidence.tableDeleteGranted
            tableOwnedByGrantee = [string]$evidence.auditTableOwner -ceq [string]$evidence.grantee
            alterAllowed = [bool]$evidence.roleSuperuser -or
                ([string]$evidence.auditTableOwner -ceq [string]$evidence.grantee) -or [bool]$evidence.roleMemberOfAuditOwner
            createGranted = [bool]$evidence.schemaCreateGranted
            verifiedBeforeStartup = $true
            evidenceJson = $evidenceJson
        }
        if (-not (Test-T21PostgreSqlAuditOwnerBoundary $script:databaseDdlPrivileges $script:databaseName $script:databaseUser)) {
            throw 'PostgreSQL role, schema, or audit-table owner metadata did not establish the expected v11 DDL denial boundary.'
        }
    } else {
        $null = Invoke-MySqlQuery $ddl 'seed-v11-audit-ddl-permission-failure'

        $revokeAlter = "REVOKE ALTER ON $($script:databaseName).* FROM '$($script:databaseUser)'@'127.0.0.1'; REVOKE ALTER ON $($script:databaseName).* FROM '$($script:databaseUser)'@'localhost'; FLUSH PRIVILEGES;"
        $null = Invoke-MySqlQuery $revokeAlter 'revoke-runtime-user-alter' -asRoot
        $grants127 = @(Invoke-MySqlQuery "SHOW GRANTS FOR '$($script:databaseUser)'@'127.0.0.1';" 'runtime-user-grants-127' -asRoot)
        $grantsLocal = @(Invoke-MySqlQuery "SHOW GRANTS FOR '$($script:databaseUser)'@'localhost';" 'runtime-user-grants-local' -asRoot)
        if ($grants127.Count -eq 0 -or $grantsLocal.Count -eq 0) {
            throw "$($script:databaseProduct) returned no application-user grant evidence after the v11 DDL privilege setup."
        }
        $validGrants127 = Test-T21MySqlCreateWithoutAlterGrantSet ([string[]]$grants127) $script:databaseName $script:databaseUser '127.0.0.1' $script:databaseProduct
        $validGrantsLocal = Test-T21MySqlCreateWithoutAlterGrantSet ([string[]]$grantsLocal) $script:databaseName $script:databaseUser 'localhost' $script:databaseProduct
        if (-not $validGrants127 -or -not $validGrantsLocal) {
            throw "$($script:databaseProduct) SHOW GRANTS did not establish the expected schema-scoped CREATE-without-ALTER privilege boundary for each runtime account host."
        }
        $script:databaseDdlPrivileges = [ordered]@{
            product = $script:databaseProduct
            grantee = "$($script:databaseUser)@127.0.0.1"
            grants = [string[]]$grants127
            localGrants = [string[]]$grantsLocal
            alterGranted = $false
            createGranted = $true
            verifiedBeforeStartup = $true
        }
    }

    $state = Get-T21StartupFailureSchemaState
    $columns = @(ConvertFrom-Json -InputObject $state.auditColumnsJson -AsHashtable -ErrorAction Stop)
    $columnNames = @($columns | ForEach-Object { [string]$_['column_name'] })
    $rows = @(ConvertFrom-Json -InputObject $state.auditRowsJson -AsHashtable -ErrorAction Stop)
    $indexes = @(ConvertFrom-Json -InputObject $state.auditIndexesJson -AsHashtable -ErrorAction Stop)
    $expectedColumns = @('id', 'timestamp', 'type', 'player_uuid', 'player_name', 'amount', 'balance_after',
        'source', 'server', 'target_uuid', 'target_name', 'reason', 'merged_count', 't21_sentinel')
    if ($state.mainVersion -ne 1 -or $state.auditVersion -ne 10 -or
        -not $state.auditTableExists -or $state.playersTableExists -or $null -ne $state.playerRows -or
        $state.mainVersionRowJson -notmatch 'T21 pre-existing main schema' -or
        $state.auditVersionRowJson -notmatch 'T21 required v11 DDL failure sentinel' -or
        $columnNames.Count -ne $expectedColumns.Count -or
        @($expectedColumns | Where-Object { $_ -cnotin $columnNames }).Count -ne 0 -or
        @($columnNames | Where-Object { $_ -in @('request_id', 'sequence') }).Count -ne 0 -or
        $indexes.Count -ne 1 -or
        $indexes[0].name -cne $(if ($script:databaseProduct -ceq 'PostgreSQL') { 'syncmoney_audit_log_pkey' } else { 'PRIMARY' })) {
        throw "The isolated $script:databaseProduct database did not retain the exact pre-start v10 schema, privilege boundary, and data fixture."
    }
    if ($rows.Count -ne 1 -or -not (Test-T21RequiredV11AuditSentinelRow $rows[0])) {
        throw 'The isolated MySQL database did not retain the exact seeded v10 audit row before candidate startup.'
    }
    return $state
}

function Enable-T21MySqlGeneralLogCapture {
    if ($script:databaseProduct -notin @('MySQL', 'MariaDB') -or -not $script:requiredV11DdlPermissionDeniedStartupFailure -or
        $PlatformEndpoint -notin @('Maximum', 'CurrentMaximum')) {
        throw 'MySQL/MariaDB general-log capture is scoped to the maximum-Paper required-v11-DDL denial cell.'
    }
    $stateRows = @(Invoke-MySqlQuery 'SELECT CONCAT(UPPER(@@GLOBAL.general_log), ''|'', UPPER(@@GLOBAL.log_output));' 'mysql-ddl-general-log-state-before' -asRoot)
    if ($stateRows.Count -ne 1) { throw 'Could not read the isolated MySQL general-log state before candidate startup.' }
    $stateParts = $stateRows[0].Trim().Split('|')
    if ($stateParts.Count -ne 2 -or $stateParts[0] -notin @('OFF', '0') -or
        $stateParts[1] -notmatch '^(FILE|TABLE|FILE,TABLE|TABLE,FILE)$') {
        throw 'The isolated MySQL server has an unexpected general-log state before candidate startup.'
    }
    $script:mysqlGeneralLogOutputBefore = $stateParts[1]
    $script:mysqlGeneralLogRestoreRequired = $true
    $null = Invoke-MySqlQuery "SET GLOBAL log_output = 'TABLE';" 'mysql-ddl-general-log-table-enable' -asRoot
    $null = Invoke-MySqlQuery 'SET GLOBAL general_log = ON;' 'mysql-ddl-general-log-enable' -asRoot
    $verifiedRows = @(Invoke-MySqlQuery 'SELECT CONCAT(UPPER(@@GLOBAL.general_log), ''|'', UPPER(@@GLOBAL.log_output));' 'mysql-ddl-general-log-state-enabled' -asRoot)
    if ($verifiedRows.Count -ne 1 -or $verifiedRows[0].Trim() -notmatch '^(ON|1)\|TABLE$') {
        throw 'The isolated MySQL general log was not enabled with TABLE output before candidate startup.'
    }
}

function Disable-T21MySqlGeneralLogCapture {
    if (-not $script:mysqlGeneralLogRestoreRequired) { return }
    if ($script:mysqlGeneralLogOutputBefore -notmatch '^(FILE|TABLE|FILE,TABLE|TABLE,FILE)$') {
        throw 'Refusing to restore an unrecognized MySQL general-log output value.'
    }
    $null = Invoke-MySqlQuery 'SET GLOBAL general_log = OFF;' 'mysql-ddl-general-log-disable' -asRoot
    $null = Invoke-MySqlQuery "SET GLOBAL log_output = '$script:mysqlGeneralLogOutputBefore';" 'mysql-ddl-general-log-output-restore' -asRoot
    $verifiedRows = @(Invoke-MySqlQuery 'SELECT CONCAT(UPPER(@@GLOBAL.general_log), ''|'', UPPER(@@GLOBAL.log_output));' 'mysql-ddl-general-log-state-restored' -asRoot)
    if ($verifiedRows.Count -ne 1 -or $verifiedRows[0].Trim() -notmatch ('^(OFF|0)\|' + [regex]::Escape($script:mysqlGeneralLogOutputBefore) + '$')) {
        throw 'The isolated MySQL general-log settings were not restored after evidence capture.'
    }
    $script:mysqlGeneralLogRestoreRequired = $false
}

function Get-T21MySqlDeniedRequestIdDdlEvidence {
    if (-not $script:mysqlGeneralLogRestoreRequired -or $script:databaseProduct -notin @('MySQL', 'MariaDB') -or
        $PlatformEndpoint -notin @('Maximum', 'CurrentMaximum')) {
        throw 'The maximum-Paper MySQL/MariaDB denied-request_id evidence capture was not initialized.'
    }
    try {
        $query = @"
SELECT JSON_OBJECT(
    'userHost', CONVERT(user_host USING utf8mb4),
    'commandType', command_type,
    'argument', CONVERT(argument USING utf8mb4))
FROM mysql.general_log
WHERE command_type = 'Query'
  AND user_host LIKE '$($script:databaseUser)%'
  AND argument REGEXP '^[[:space:]]*ALTER[[:space:]]+TABLE'
ORDER BY event_time;
"@
        $eventLines = @(Invoke-MySqlQuery $query 'mysql-denied-request-id-ddl-general-log-query' -asRoot)
        $evidencePath = 'logs\mysql-denied-request-id-ddl-evidence.jsonl'
        $evidenceFullPath = Join-Path $script:logsRoot 'mysql-denied-request-id-ddl-evidence.jsonl'
        [IO.File]::WriteAllLines($evidenceFullPath, [string[]]$eventLines, [Text.UTF8Encoding]::new($false))
        $script:deniedDdlEvidence = [ordered]@{
            source = 'mysql.general_log'
            databaseName = $script:databaseName
            runtimeUser = $script:databaseUser
            eventCount = [int]$eventLines.Count
            logPath = $evidencePath
        }
        if ($eventLines.Count -ne 1 -or [string]::IsNullOrWhiteSpace($eventLines[0])) {
            throw "Expected one MySQL/MariaDB ALTER event from the candidate account; observed $($eventLines.Count)."
        }
        $event = ConvertFrom-Json -InputObject $eventLines[0] -AsHashtable -ErrorAction Stop
        if (-not (Test-T21MySqlDeniedRequestIdDdlEvent $event $script:databaseUser)) {
            throw 'The captured MySQL/MariaDB ALTER event did not identify the exact audit request_id DDL from the candidate SQL account.'
        }
    } finally {
        Disable-T21MySqlGeneralLogCapture
    }
    return $script:deniedDdlEvidence
}

function Get-T21StartupFailureResourceCleanup {
    $sqlConnectionSql = if ($script:databaseProduct -ceq 'PostgreSQL') {
        'SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid();'
    } else {
        'SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB = DATABASE() AND ID <> CONNECTION_ID();'
    }
    $sqlConnections = @(Invoke-MySqlQuery $sqlConnectionSql 'startup-failure-sql-connections-after-disable')
    if ($sqlConnections.Count -ne 1) { throw 'Could not observe SQL connections after startup rollback.' }
    $connectedClients = @(Invoke-Redis @('-n', '15', 'INFO', 'clients') 'startup-failure-redis-clients-after-disable')
    $clientMarker = @($connectedClients | ForEach-Object { $_ -split "`r?`n" } | Where-Object { $_ -match '^connected_clients:\d+$' })
    if ($clientMarker.Count -ne 1) { throw 'Could not observe Redis clients after startup rollback.' }
    $redisDbSize = (Invoke-Redis @('-n', '15', 'DBSIZE') 'startup-failure-redis-dbsize-after-disable').output.Trim()
    return [ordered]@{
        sqlOtherConnections = [int]::Parse($sqlConnections[0].Trim(), [Globalization.CultureInfo]::InvariantCulture)
        redisOtherClients = [Math]::Max(0, [int]($clientMarker[0] -replace '^connected_clients:', '') - 1)
        redisDbSize = [int]::Parse($redisDbSize, [Globalization.CultureInfo]::InvariantCulture)
    }
}

function Get-T21DatabaseManagerInitFailureFinalCleanup {
    $afterDisable = $script:resourceCleanupObservation
    if ($null -eq $afterDisable) { throw 'The post-disable SQL/Redis resource snapshot is absent.' }
    $serverProcesses = @(Get-T21ServerProcesses)
    $postgresShutdown = @($script:backendShutdowns | Where-Object { [string]$_.backend -ceq 'postgresql' })
    $redisShutdown = @($script:backendShutdowns | Where-Object { [string]$_.backend -ceq 'redis' })
    $normalStopForced = @($script:normalStops | Where-Object { $_.forced -eq $true }).Count -gt 0
    $backendStopForced = @($script:backendShutdowns | Where-Object { $_.forced -eq $true }).Count -gt 0
    $postgresListenerCount = @(Get-NetTCPConnection -State Listen -LocalPort $script:mysqlPort -ErrorAction SilentlyContinue).Count
    $redisListenerCount = @(Get-NetTCPConnection -State Listen -LocalPort $script:redisPort -ErrorAction SilentlyContinue).Count
    $serverListenerCount = 0
    foreach ($port in @($script:gamePort, ($script:gamePort + 10000))) {
        $serverListenerCount += @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue).Count
    }
    $postgresProcessExited = $null -ne $script:mysqlProcess -and $script:mysqlProcess.HasExited
    $redisProcessExited = $null -ne $script:redisProcess -and $script:redisProcess.HasExited
    $postgresStopped = $postgresProcessExited -and $postgresShutdown.Count -eq 1 -and
        [bool]$postgresShutdown[0].processExited -and -not [bool]$postgresShutdown[0].forced -and $postgresListenerCount -eq 0
    $redisStopped = $redisProcessExited -and $redisShutdown.Count -eq 1 -and
        [bool]$redisShutdown[0].processExited -and -not [bool]$redisShutdown[0].forced -and $redisListenerCount -eq 0
    $runOwnedPortsClosed = $postgresListenerCount -eq 0 -and $redisListenerCount -eq 0 -and $serverListenerCount -eq 0
    return [ordered]@{
        forcedCleanup = [bool]($normalStopForced -or $backendStopForced)
        serverStopped = [bool]($serverProcesses.Count -eq 0)
        serverProcessCount = [int]$serverProcesses.Count
        postgresStopped = [bool]$postgresStopped
        redisStopped = [bool]$redisStopped
        runOwnedPortsClosed = [bool]$runOwnedPortsClosed
        sqlOtherConnections = [int]$afterDisable.sqlOtherConnections
        redisOtherClients = [int]$afterDisable.redisOtherClients
        redisDbSize = [int]$afterDisable.redisDbSize
        cleanupFailures = @($script:cleanupFailures.ToArray())
    }
}

function Start-T21ServerExpectingAuditSchemaFailure([ValidateSet('NewerAuditSchema', 'DatabaseManagerInitFailure', 'IncompleteV10AuditTable', 'IncompleteV10AuditTablePlayerRowPreservation', 'RequiredV11DdlPermissionDenied')][string]$FailureScenario) {
    if (@(Get-T21ServerProcesses).Count -ne 0) { throw 'A run-marked server process exists before expected-failure startup.' }
    Set-T21PluginInProfile $CandidateVersion
    $before = Get-ServerLogLineCount
    $start = Invoke-PlugDev @('server', 'start', '--config', '.plugdev/profile.yml') 'candidate-startup-failure-start'
    if ($start.exitCode -ne 0) { throw 'PlugDev could not keep the platform alive for the expected plugin startup failure.' }
    $disableMarkerPattern = 'Disabling Syncmoney v' + [regex]::Escape($CandidateVersion)
    $null = Wait-ServerLogMarker $disableMarkerPattern '' $before 90
    $fullLog = Get-Content -LiteralPath $script:serverLogPath -Raw -ErrorAction Stop
    $expectedFailureMessage = switch ($FailureScenario) {
        'NewerAuditSchema' { 'Audit schema version 12 is newer than this plugin supports (11); no audit schema changes were made.' }
        'DatabaseManagerInitFailure' { 'Failed to initialize database schema: ERROR: permission denied for schema public' }
        'IncompleteV10AuditTable' { 'Failed audit schema migration to version 10; version metadata was not advanced past 9' }
        'IncompleteV10AuditTablePlayerRowPreservation' { 'Failed audit schema migration to version 10; version metadata was not advanced past 9' }
        default { 'Failed audit schema migration to version 11; version metadata was not advanced past 10' }
    }
    $expectedFailureKind = switch ($FailureScenario) {
        'NewerAuditSchema' { 'newer-audit-schema' }
        'DatabaseManagerInitFailure' { 'database-manager-initial-schema-failure' }
        'IncompleteV10AuditTable' { 'incomplete-v10-audit-table' }
        'IncompleteV10AuditTablePlayerRowPreservation' { 'incomplete-v10-audit-table' }
        default { 'required-v11-audit-ddl-permission-failure' }
    }
    $failureDetailMarker = switch ($FailureScenario) {
        'DatabaseManagerInitFailure' { 'permission denied for schema public' }
        'IncompleteV10AuditTable' { 'Required v10 audit columns are missing:' }
        'IncompleteV10AuditTablePlayerRowPreservation' { 'Required v10 audit columns are missing:' }
        'RequiredV11DdlPermissionDenied' {
            if ($script:databaseProduct -ceq 'PostgreSQL') { 'must be owner of table syncmoney_audit_log' }
            else { 'ALTER command denied' }
        }
        default { $null }
    }
    $sqlPoolShutdownObserved = $fullLog -match '(?m)^\[[^\]\r\n]+\] \[[^\]\r\n]+/INFO\]: \[noietime\.libs\.hikari\.HikariDataSource\] Syncmoney-HikariCP - Shutdown completed\.\r?$'
    $sqlPoolStartObserved = $fullLog.Contains('Syncmoney-HikariCP - Start completed.')
    $scenarioMarkers = switch ($FailureScenario) {
        'NewerAuditSchema' { @() }
        'DatabaseManagerInitFailure' { @('Syncmoney-HikariCP - Start completed.') }
        'IncompleteV10AuditTable' { @('Syncmoney audit schema version: current 9, target 11', $failureDetailMarker) }
        'IncompleteV10AuditTablePlayerRowPreservation' { @('Syncmoney audit schema version: current 9, target 11', $failureDetailMarker) }
        default { @('Syncmoney audit schema version: current 10, target 11', $failureDetailMarker) }
    }
    foreach ($marker in @(
            'Loading server plugin Syncmoney v1.4.0-alpha.1',
            'Syncmoney initialization failed; rolling back initialized owners',
            $expectedFailureMessage) + $scenarioMarkers + @(
            '[noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Shutdown completed.',
            ('Disabling Syncmoney v' + $CandidateVersion))) {
        if (-not $fullLog.Contains($marker)) { throw "Expected startup-failure marker was absent: $marker" }
    }
    if (-not $sqlPoolShutdownObserved) { throw 'The INFO-level Syncmoney Hikari pool shutdown marker was absent after startup rollback.' }
    if ($FailureScenario -ceq 'DatabaseManagerInitFailure') {
        $startupLogResult = Test-T21DatabaseManagerInitFailureStartupLog -LogText $fullLog -CandidateVersion $CandidateVersion
        if (-not $startupLogResult.passed) {
            throw "DatabaseManager initial-schema failure log was not accepted: $($startupLogResult.failures -join '; ')"
        }
    } elseif (-not (Test-T21StartupRollbackCompletionLog $fullLog $CandidateVersion)) {
        throw 'Startup rollback completion requires ordered rollback, INFO-level SQL pool shutdown, and Bukkit plugin-disable markers.'
    }
    if ($fullLog.Contains('Syncmoney enabled successfully')) { throw 'Syncmoney unexpectedly completed startup against the seeded audit schema failure.' }
    $identity = Get-T21PlatformIdentity $fullLog
    Assert-T21PlatformIdentity $identity $script:platformTarget.software $script:platformTarget.minecraftVersion $script:platformTarget.build
    $pluginPin = Get-PinnedSyncmoneyPlugin $CandidateVersion $script:candidateSha256
    if ($FailureScenario -ceq 'RequiredV11DdlPermissionDenied' -and
        $script:databaseProduct -in @('MySQL', 'MariaDB') -and $PlatformEndpoint -in @('Maximum', 'CurrentMaximum')) {
        $script:deniedDdlEvidence = Get-T21MySqlDeniedRequestIdDdlEvidence
    }
    $processes = @(Get-T21ServerProcesses)
    if ($processes.Count -ne 1) { throw "Expected one run-owned platform process after rollback; observed $($processes.Count)." }
    $observation = [ordered]@{
        stage = 'candidate-startup-failure'
        pluginVersion = $CandidateVersion
        artifactSha256 = $pluginPin.sha256
        enabled = $false
        processId = [int]$processes[0].ProcessId
        runtimeIdentity = $identity
        expectedFailure = $expectedFailureKind
        failureObserved = $true
        failureMessage = $expectedFailureMessage
        # This flag records Syncmoney owner cleanup after startup failure; SQL preservation is measured by the explicit schema/row snapshots.
        rollbackComplete = $true
        sqlPoolStartObserved = [bool]$sqlPoolStartObserved
        sqlPoolShutdownObserved = [bool]$sqlPoolShutdownObserved
        disabledMarker = $true
        successMarkerObserved = $false
    }
    if ($null -ne $failureDetailMarker) { $observation.failureDetailMarker = $failureDetailMarker }
    $script:startups.Add($observation)
    return $observation
}

function Add-T21LegacySqlStage(
    [string]$stage,
    [string]$pluginVersion,
    [string]$acceptanceMarker,
    [object]$startup,
    [object]$snapshot
) {
    $stopMatches = @($script:normalStops | Where-Object { [string]$_.stage -ceq "after-$stage" })
    if ($stopMatches.Count -ne 1) { throw "Expected one normal-stop observation for legacy SQL stage $stage." }
    $stop = $stopMatches[0]
    $script:legacySqlStages.Add([ordered]@{
        stage=$stage
        pluginVersion=$pluginVersion
        artifactName=[string]$startup.artifactName
        artifactSha256=[string]$startup.artifactSha256
        enabled=[bool]$startup.enabled
        apiConsumerMarker=[string]$startup.apiConsumerMarker
        processId=[int]$startup.processId
        runtimeIdentity=$startup.runtimeIdentity
        acceptanceMarker=$acceptanceMarker
        normalStop=[ordered]@{
            stage=[string]$stop.stage
            commandExitCode=[int]$stop.commandExitCode
            processId=[int]$stop.processId
            processExitCode=[int]$stop.processExitCode
            processExitCodeObserved=[bool]$stop.processExitCodeObserved
            processExited=[bool]$stop.processExited
            disabledMarker=[bool]$stop.disabledMarker
            forced=[bool]$stop.forced
        }
        snapshot=$snapshot
    })
}

function Stop-RunOwnedServerAfterFailure {
    $owned = @(Get-T21ServerProcesses)
    foreach ($process in $owned) {
        try {
            Stop-Process -Id ([int]$process.ProcessId) -Force -ErrorAction Stop
            $script:cleanupFailures.Add("Forced cleanup of run-owned platform PID $($process.ProcessId) after failure.")
        } catch {
            if (Get-Process -Id ([int]$process.ProcessId) -ErrorAction SilentlyContinue) {
                $script:cleanupFailures.Add("Could not stop run-owned platform PID $($process.ProcessId): $($_.Exception.Message)")
            }
        }
    }
    if (@(Get-T21ServerProcesses).Count -gt 0) { $script:cleanupFailures.Add('A run-owned platform process remained after failure cleanup.') }
}

function Stop-MySqlBackend {
    if ($script:databaseProduct -ceq 'PostgreSQL') { return Stop-PostgresBackend }
    if ($null -eq $script:mysqlLauncherProcess) { return }
    $process = $script:mysqlProcess
    $launcher = $script:mysqlLauncherProcess
    $commandExitCode = $null
    $forced = $false
    $shutdownTargetVerified = $false
    if ($null -ne $process -and -not $process.HasExited) {
        try {
            $listenerProcess = Get-MySqlListenerProcess
            if ($null -eq $listenerProcess -or [int]$listenerProcess.process.Id -ne [int]$process.Id) {
                throw "The listener no longer resolves to the captured $script:databaseProduct server process."
            }
            $null = Assert-BackendListenerOwner $script:databaseProduct $script:mysqlPort $process.Id
            $shutdownTargetVerified = $true
        } catch {
            $script:cleanupFailures.Add("Refused mysqladmin shutdown because the run-owned listener could not be verified: $($_.Exception.Message)")
        }
    }
    if ($shutdownTargetVerified -and -not $process.HasExited) {
        try {
            $result = Invoke-MySqlAdmin @('shutdown') 'mysql-shutdown'
            $commandExitCode = [int]$result.exitCode
        } catch {
            $script:cleanupFailures.Add("mysqladmin shutdown failed: $($_.Exception.Message)")
        }
    }
    if ($null -ne $process -and -not $process.HasExited) {
        try { $process.WaitForExit(30000) | Out-Null } catch { }
        $process.Refresh()
        if (-not $process.HasExited -and $shutdownTargetVerified) {
            $forced = $true
            try { $process.Kill(); $process.WaitForExit(10000) | Out-Null }
            catch { $script:cleanupFailures.Add("Could not stop verified run-owned $script:databaseProduct PID $($process.Id): $($_.Exception.Message)") }
        }
    }

    if ($null -ne $launcher -and -not $launcher.HasExited -and ($null -eq $process -or $launcher.Id -ne $process.Id)) {
        try { $launcher.WaitForExit(30000) | Out-Null } catch { }
        $launcher.Refresh()
        if (-not $launcher.HasExited) {
            $forced = $true
            try { $launcher.Kill(); $launcher.WaitForExit(10000) | Out-Null }
            catch { $script:cleanupFailures.Add("Could not stop run-created $script:databaseProduct launcher PID $($launcher.Id): $($_.Exception.Message)") }
        }
    }

    if ($null -ne $process) { $process.Refresh() }
    $processExited = $null -ne $process -and $process.HasExited
    $processExitCode = if ($processExited) { [int]$process.ExitCode } else { -1 }
    if ($null -ne $launcher) { $launcher.Refresh() }
    $launcherExited = $null -ne $launcher -and $launcher.HasExited
    $launcherExitCode = if ($launcherExited) { [int]$launcher.ExitCode } else { -1 }
    if ($forced -or -not $shutdownTargetVerified -or -not $processExited -or -not $launcherExited -or
        $commandExitCode -ne 0 -or $processExitCode -ne 0 -or $launcherExitCode -ne 0) {
        $script:cleanupFailures.Add("$script:databaseProduct shutdown was not clean (command=$commandExitCode process=$processExitCode launcher=$launcherExitCode targetVerified=$shutdownTargetVerified forced=$forced).")
    }
    $script:backendShutdowns.Add([ordered]@{
        backend='mysql'; command='mysqladmin shutdown'; commandExitCode=$commandExitCode
        processId=if ($null -eq $process) { 0 } else { [int]$process.Id }
        shutdownTargetVerified=$shutdownTargetVerified
        processExitCode=$processExitCode; processExitCodeObserved=$processExited; processExited=$processExited
        launchProcessId=[int]$launcher.Id; launchProcessExitCode=$launcherExitCode
        launchProcessExitCodeObserved=$launcherExited; launchProcessExited=$launcherExited; forced=$forced
    })
}

function Stop-PostgresBackend {
    $process = $script:mysqlProcess
    $commandExitCode = $null
    $forced = $false
    $shutdownTargetVerified = $false
    if ($null -eq $process) {
        try {
            $listener = Get-PostgresListenerProcess
            if ($null -ne $listener) {
                $process = $listener.process
            } else {
                # pg_ctl start can time out after the postmaster has written its PID file
                # but before it has opened the configured listener.
                $pidFileProcess = Get-PostgresPidFileProcess
                if ($null -ne $pidFileProcess) { $process = $pidFileProcess.process }
            }
            if ($null -ne $process) { $script:mysqlProcess = $process }
        } catch {
            $script:cleanupFailures.Add("Could not identify the run-owned PostgreSQL process: $($_.Exception.Message)")
        }
    }
    if ($null -eq $process) {
        if (Test-Path -LiteralPath (Join-Path $script:mysqlData 'postmaster.pid') -PathType Leaf) {
            $script:cleanupFailures.Add('PostgreSQL postmaster.pid remains but the listener process could not be verified.')
        }
        return
    }
    $process.Refresh()
    if (-not $process.HasExited) {
        $listenerVerificationError = $null
        try {
            $listener = Get-PostgresListenerProcess
            if ($null -eq $listener -or [int]$listener.process.Id -ne [int]$process.Id) {
                throw 'The listener no longer resolves to the captured PostgreSQL process.'
            }
            $null = Assert-BackendListenerOwner 'PostgreSQL' $script:mysqlPort $process.Id
            $shutdownTargetVerified = $true
        } catch {
            $listenerVerificationError = $_.Exception.Message
        }
        if (-not $shutdownTargetVerified) {
            try {
                $pidFileProcess = Get-PostgresPidFileProcess
                if ($null -eq $pidFileProcess -or [int]$pidFileProcess.process.Id -ne [int]$process.Id) {
                    throw 'The run-owned postmaster.pid no longer resolves to the captured PostgreSQL process.'
                }
                $shutdownTargetVerified = $true
            } catch {
                $script:cleanupFailures.Add("Refused pg_ctl stop because neither the listener nor postmaster.pid identity could be verified: listener=$listenerVerificationError; pidFile=$($_.Exception.Message)")
            }
        }
    } else {
        $shutdownTargetVerified = $true
    }
    if ($shutdownTargetVerified -and -not $process.HasExited) {
        try {
            $result = Invoke-LoggedCommand $script:mysqlAdmin @('stop', '-D', $script:mysqlData, '-m', 'fast', '-w', '-t', '30') 'postgresql-shutdown' (Join-Path $script:logsRoot 'postgresql-shutdown.txt')
            $commandExitCode = [int]$result.exitCode
        } catch {
            $script:cleanupFailures.Add("PostgreSQL pg_ctl stop failed: $($_.Exception.Message)")
        }
        try { $process.WaitForExit(30000) | Out-Null } catch { }
        $process.Refresh()
        if (-not $process.HasExited -and $shutdownTargetVerified) {
            $forced = $true
            try { $process.Kill(); $process.WaitForExit(10000) | Out-Null }
            catch { $script:cleanupFailures.Add("Could not stop verified run-owned PostgreSQL PID $($process.Id): $($_.Exception.Message)") }
        }
    }
    $process.Refresh()
    $processExited = $process.HasExited
    $processExitCode = if ($processExited) { [int]$process.ExitCode } else { -1 }
    $listenerAfter = @(Get-NetTCPConnection -State Listen -LocalPort $script:mysqlPort -ErrorAction SilentlyContinue)
    if ($forced -or -not $shutdownTargetVerified -or $commandExitCode -ne 0 -or -not $processExited -or $processExitCode -ne 0 -or $listenerAfter.Count -ne 0) {
        $script:cleanupFailures.Add("PostgreSQL shutdown was not clean (command=$commandExitCode process=$processExitCode listenerCount=$($listenerAfter.Count) targetVerified=$shutdownTargetVerified forced=$forced).")
    }
    $script:backendShutdowns.Add([ordered]@{
        backend='postgresql'; command='pg_ctl stop -m fast -w'; commandExitCode=$commandExitCode
        processId=[int]$process.Id; shutdownTargetVerified=$shutdownTargetVerified
        processExitCode=$processExitCode; processExitCodeObserved=$processExited; processExited=$processExited; forced=$forced
    })
}

function Stop-RedisBackend {
    if ($null -eq $script:redisProcess) { return }
    $process = $script:redisProcess
    $commandExitCode = $null
    $forced = $false
    if (-not $process.HasExited) {
        try {
            $result = Invoke-Redis @('SHUTDOWN', 'SAVE') 'redis-shutdown' -allowExitOne
            $commandExitCode = [int]$result.exitCode
        } catch {
            $script:cleanupFailures.Add("Redis SHUTDOWN SAVE failed: $($_.Exception.Message)")
        }
        try { $process.WaitForExit(20000) | Out-Null } catch { }
        $process.Refresh()
        if (-not $process.HasExited) {
            $forced = $true
            try { $process.Kill(); $process.WaitForExit(10000) | Out-Null }
            catch { $script:cleanupFailures.Add("Could not stop run-owned Redis PID $($process.Id): $($_.Exception.Message)") }
        }
    }
    $process.Refresh()
    $exited = $process.HasExited
    $exitCode = if ($exited) { [int]$process.ExitCode } else { -1 }
    if ($forced -or -not $exited -or $commandExitCode -notin @(0, 1) -or $exitCode -ne 0) {
        $script:cleanupFailures.Add("Redis shutdown was not clean (command=$commandExitCode process=$exitCode forced=$forced).")
    }
    $script:backendShutdowns.Add([ordered]@{
        backend='redis'; command='SHUTDOWN SAVE'; commandExitCode=$commandExitCode; processId=[int]$process.Id
        processExitCode=$exitCode; processExitCodeObserved=$exited; processExited=$exited; forced=$forced
    })
}

function Get-RunOwnedGradleDaemons {
    $isolatedGradleHome = [IO.Path]::GetFullPath($script:gradleUserHome)
    @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop | Where-Object {
        $_.Name -in @('java.exe', 'javaw.exe') -and $_.CommandLine -and
        $_.CommandLine.IndexOf($isolatedGradleHome, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $_.CommandLine -match 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon'
    })
}

function Stop-T21RunOwnedGradleDaemons {
    $before = @(Get-RunOwnedGradleDaemons)
    $beforeIds = @($before | ForEach-Object { [int]$_.ProcessId })
    $stopCommandExitCode = $null
    $stopLogPath = $null
    if ($before.Count -gt 0) {
        $wrapper = Join-Path $script:workspace 'gradlew.bat'
        if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) { throw 'The run-owned Gradle wrapper is unavailable for isolated daemon shutdown.' }
        if ([IO.Path]::GetFullPath($env:GRADLE_USER_HOME) -cne [IO.Path]::GetFullPath($script:gradleUserHome)) {
            throw 'Refusing Gradle --stop because GRADLE_USER_HOME does not match this run-owned daemon home.'
        }
        $stopLogPath = 'logs\gradle-daemon-stop.txt'
        $stopLog = Join-Path $script:runRoot $stopLogPath
        Push-Location $script:workspace
        try {
            $null = & $wrapper --stop *> $stopLog
            $stopCommandExitCode = $LASTEXITCODE
        } finally { Pop-Location }
        if ($stopCommandExitCode -ne 0) { $script:cleanupFailures.Add("Isolated Gradle --stop exited $stopCommandExitCode.") }
        $deadline = [DateTime]::UtcNow.AddSeconds(20)
        do {
            $after = @(Get-RunOwnedGradleDaemons)
            if ($after.Count -eq 0) { break }
            Start-Sleep -Milliseconds 250
        } while ([DateTime]::UtcNow -lt $deadline)
    }
    $after = @(Get-RunOwnedGradleDaemons)
    $afterIds = @($after | ForEach-Object { [int]$_.ProcessId })
    $processExitVerified = $after.Count -eq 0
    if (-not $processExitVerified) { $script:cleanupFailures.Add('A run-owned isolated Gradle daemon remained after --stop.') }
    return [ordered]@{
        isolatedUserHome=[IO.Path]::GetFullPath($script:gradleUserHome)
        processIdsBeforeStop=$beforeIds
        stopCommandExitCode=$stopCommandExitCode
        stopLogPath=$stopLogPath
        processIdsAfterStop=$afterIds
        processExitVerified=$processExitVerified
    }
}

function Restore-TestEnvironment {
    foreach ($name in $script:environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $script:originalEnvironment[$name], 'Process')
    }
}

$script:environmentNames = @(
    'JAVA_HOME', 'Path', 'PLUGDEV_ROOT', 'PLUGDEV_HOME', 'GRADLE_USER_HOME', 'SYNCMONEY_TEST_SERVER_JAR',
    'SYNCMONEY_CANDIDATE_VERSION', 'SYNCMONEY_TEST_GAME_PORT', 'SYNCMONEY_TEST_DB_TYPE',
    'SYNCMONEY_TEST_DB_PORT', 'SYNCMONEY_TEST_DB_USER', 'SYNCMONEY_TEST_DB_PASSWORD',
    'SYNCMONEY_TEST_DB_NAME', 'SYNCMONEY_TEST_REDIS_PORT', 'T21_RUN_MARKER', 'T21_PLUGIN_VERSION',
    'T21_PLUGIN_JAR_PATTERN', 'MYSQL_PWD', 'PGPASSWORD'
)
$script:originalEnvironment = [ordered]@{}
foreach ($name in $script:environmentNames) { $script:originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

$serverSha256 = (Get-FileHash -LiteralPath $script:serverPath -Algorithm SHA256).Hash.ToLowerInvariant()
$script:candidateSha256 = (Get-FileHash -LiteralPath $script:candidatePath -Algorithm SHA256).Hash.ToLowerInvariant()
$script:apiConsumerSha256 = $null
$acceptanceSha256 = (Get-FileHash -LiteralPath $script:acceptancePath -Algorithm SHA256).Hash.ToLowerInvariant()
$apiConsumerSha256 = (Get-FileHash -LiteralPath $script:apiConsumerPath -Algorithm SHA256).Hash.ToLowerInvariant()
$script:apiConsumerSha256 = $apiConsumerSha256
if ($serverSha256 -cne $script:platformTarget.sha256 -or $serverSha256 -cne $ExpectedServerSha256.ToLowerInvariant()) { throw "Server JAR does not match the pinned $($script:platformTarget.taskSuffix) build." }
if ($script:candidateSha256 -cne $ExpectedCandidateSha256.ToLowerInvariant()) { throw 'Candidate JAR SHA-256 does not match the required pin.' }
if (-not (Test-Path -LiteralPath (Join-Path $script:plugDevRoot 'packages\cli\dist\cli.js') -PathType Leaf) -or
    -not (Test-Path -LiteralPath (Join-Path $script:plugDevRoot 'package.json') -PathType Leaf)) { throw 'The local PlugDev source checkout or built CLI is unavailable.' }
foreach ($path in @($script:candidatePath, $script:acceptancePath, $script:apiConsumerPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required acceptance artifact is missing: $path" }
}
if ($script:legacySqlDataUpgrade -and -not (Test-Path -LiteralPath $script:baselinePath -PathType Leaf)) {
    throw 'The pinned 1.3.2 baseline artifact is missing.'
}

$java = Join-Path $script:javaHomePath 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'Java 25 executable was not found.' }
$javaBanner = (& $java -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaBanner -notmatch 'version "25(?:\.|"|\+)') { throw 'The runtime cell requires Java 25.' }
$javaFeature = 25
$mysqlVersionOutput = (& $script:mysqlServer --version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $mysqlVersionOutput -notmatch $script:databaseExecutableVersionPattern) {
    throw "The runtime cell requires $script:databaseProduct $script:databaseServerVersion."
}
foreach ($path in @($script:mysqlServer, $script:mysqlClient, $script:mysqlAdmin)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "$script:databaseProduct $script:databaseServerVersion tool is missing: $path" }
}
if ($null -ne $script:mysqlInstallDb -and -not (Test-Path -LiteralPath $script:mysqlInstallDb -PathType Leaf)) {
    throw "MariaDB $script:databaseServerVersion initializer is missing: $script:mysqlInstallDb"
}
$redisServerCommand = Get-Command redis-server.exe, redis-server -ErrorAction SilentlyContinue | Select-Object -First 1
$redisCliCommand = Get-Command redis-cli.exe, redis-cli -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $redisServerCommand -or $null -eq $redisCliCommand) { throw 'The local Redis server and CLI are required.' }
$script:redisServer = $redisServerCommand.Source
$script:redisCli = $redisCliCommand.Source
foreach ($kind in @('redisServer', 'redisCli')) {
    $value = [string](Get-Variable -Name $kind -Scope Script).Value
    if ($value -match '(?i)\\scoop\\shims\\(redis-server|redis-cli)\.exe$') {
        $exeName = if ($kind -eq 'redisServer') { 'redis-server.exe' } else { 'redis-cli.exe' }
        $realPath = Join-Path (Split-Path -Parent (Split-Path -Parent $value)) (Join-Path 'apps\redis\current' $exeName)
        if (Test-Path -LiteralPath $realPath -PathType Leaf) { Set-Variable -Name $kind -Scope Script -Value (Resolve-Path -LiteralPath $realPath).Path }
    }
}
$redisVersionOutput = (& $script:redisServer '--version' 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $redisVersionOutput -notmatch 'v=8\.10\.1') { throw 'The runtime cell requires local Redis 8.10.1.' }

Assert-NoReparsePointAncestors $script:root
Assert-NoReparsePointAncestors $script:reportsRoot
New-Item -ItemType Directory -Force -Path $script:logsRoot, $script:mysqlData, $script:redisData | Out-Null
Assert-NoReparsePointAncestors $script:runRoot
$script:gamePort = Get-FreeLoopbackPort 20000 30000 @() 10000
$script:mysqlPort = Get-FreeLoopbackPort 33001 43000 @($script:gamePort, ($script:gamePort + 10000))
$script:redisPort = Get-FreeLoopbackPort 15000 25000 @($script:gamePort, ($script:gamePort + 10000), $script:mysqlPort)
$script:accounts = [ordered]@{ AcceptanceA = Get-OfflineUuid 'AcceptanceA'; AcceptanceB = Get-OfflineUuid 'AcceptanceB' }

$script:report = [ordered]@{
    schemaVersion = 4
    sourceCommit = $script:sourceCommit
    sourceWorkingTreeDirty = $script:sourceWorkingTreeDirty
    task = if ($script:databaseManagerInitFailureStartupFailure) {
        'T21-SQL-POOL-INITIAL-SCHEMA-FAILURE-PostgreSQL-CurrentMaximum'
    } elseif ($script:newerAuditSchemaStartupFailure) {
        "T21-V03-SYNC-newer-audit-schema-startup-failure-$script:databaseProduct-$($script:platformTarget.taskSuffix)"
    } elseif ($script:databaseManagerInitFailureStartupFailure) {
        "T21-SQL-POOL-INITIAL-SCHEMA-FAILURE-$script:databaseProduct-$($script:platformTarget.taskSuffix)"
    } elseif ($script:incompleteV10AuditTablePlayerRowPreservation) {
        "T21-V03-SYNC-incomplete-v10-audit-table-player-row-preservation-MySQL-$($script:platformTarget.taskSuffix)"
    } elseif ($script:incompleteV10AuditTableStartupFailure) {
        "T21-V03-SYNC-incomplete-v10-audit-table-startup-failure-$script:databaseProduct-$($script:platformTarget.taskSuffix)"
    } elseif ($script:requiredV11DdlPermissionDeniedStartupFailure) {
        "T21-V03-SYNC-v11-audit-ddl-permission-failure-$script:databaseProduct-$($script:platformTarget.taskSuffix)"
    } elseif ($script:successfulV10AuditSchemaMultiplePlayerRowPreservation) {
        "T21-V03-SYNC-v10-to-v11-audit-schema-multiple-player-row-preservation-$script:databaseProduct-$($script:platformTarget.taskSuffix)"
    } elseif ($script:successfulV10AuditSchemaSinglePlayerRowPreservation) {
        "T21-V03-SYNC-v10-to-v11-audit-schema-player-row-preservation-$script:databaseProduct-$($script:platformTarget.taskSuffix)"
    } elseif ($script:successfulV10AuditSchemaUpgrade) {
        "T21-V03-SYNC-v10-to-v11-audit-schema-upgrade-$script:databaseProduct-$($script:platformTarget.taskSuffix)"
    } else { "T21-M05-SYNC-$script:databaseProduct-$($script:platformTarget.taskSuffix)" }
    status = 'IN_PROGRESS'
    startedAtUtc = $script:startedAtUtc
    runId = $script:runId
    runDirectory = $script:runRoot
    server = [ordered]@{ software=$script:platformTarget.software; minecraftVersion=$script:platformTarget.minecraftVersion; build=$script:platformTarget.build; sha256=$serverSha256; bytes=[long](Get-Item -LiteralPath $script:serverPath).Length; path=$script:serverPath }
    candidate = [ordered]@{ version=$CandidateVersion; sha256=$script:candidateSha256; path=$script:candidatePath }
    acceptance = [ordered]@{ sha256=$acceptanceSha256 }
    apiConsumer = [ordered]@{ sha256=$apiConsumerSha256 }
    java = [ordered]@{ feature=$javaFeature; banner=$javaBanner.Trim() }
    plugDev = [ordered]@{
        root=$script:plugDevRoot
        sourceCommit=$script:plugDevSourceCommit
        cliSha256=$script:plugDevCliSha256
        cliDistManifest=$script:plugDevCliDistManifest
        cliDistManifestSha256Before=$script:plugDevCliDistManifest.sha256
        cliDistManifestSha256After=$null
        cliDistInvocationObservations=@()
    }
    configuration = [ordered]@{
        schema=12; mode='sync'; databaseEnabled=$true; databaseType=$(if ($script:databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }); databaseIsolated=$true
        databaseHost='127.0.0.1'; databasePort=$script:mysqlPort; databaseName=$script:databaseName
        redisEnabled=$true; redisDatabase=15; redisIsolated=$true; redisHost='127.0.0.1'; redisPort=$script:redisPort
    }
    mysql = [ordered]@{ product=$script:databaseProduct; version=$script:databaseServerVersion; serverVersion=$null; executableVersion=$mysqlVersionOutput; bin=$script:mysqlBinPath; dataDirectory=$script:mysqlData; processId=$null; launchProcessId=$null; listenerAddresses=@() }
    redis = [ordered]@{ version='8.10.1'; serverVersion=$redisVersionOutput; executable=$script:redisServer; dataDirectory=$script:redisData; processId=$null }
    backendProcesses = $null
    startupObservations = @()
    writeReceipts = @()
    restartReadMarker = $null
    restartReadObservation = $null
    normalStops = @()
    snapshots = @()
    schemaStateBefore = $null
    schemaStateAfter = $null
    backendShutdowns = @()
    gradleDaemonCleanup = $null
    cleanupFailures = @()
    failure = $null
    logs = [ordered]@{}
}
if ($script:databaseProduct -ceq 'PostgreSQL') {
    $script:report.Remove('mysql')
    $script:report.postgresql = [ordered]@{
        product='PostgreSQL'; version=$script:databaseServerVersion; serverVersion=$null; executableVersion=$mysqlVersionOutput
        bin=$script:mysqlBinPath; dataDirectory=$script:mysqlData; processId=$null; listenerAddresses=@()
        startCommand='pg_ctl start -w'; startCommandExitCode=$null; startCommandProcessExited=$false
    }
}

try {
    $env:JAVA_HOME = $script:javaHomePath
    $env:Path = "$($script:javaHomePath)\bin;$($script:mysqlBinPath);$env:Path"
    $env:PLUGDEV_ROOT = $script:plugDevRoot
    $env:PLUGDEV_HOME = Join-Path $script:runRoot '.plugdev-home'
    $env:GRADLE_USER_HOME = $script:gradleUserHome
    $env:SYNCMONEY_TEST_SERVER_JAR = $script:serverPath
    $env:SYNCMONEY_CANDIDATE_VERSION = $CandidateVersion
    $env:SYNCMONEY_TEST_GAME_PORT = [string]$script:gamePort
    $env:SYNCMONEY_TEST_DB_TYPE = if ($script:databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
    $env:SYNCMONEY_TEST_DB_PORT = [string]$script:mysqlPort
    $env:SYNCMONEY_TEST_DB_USER = $script:databaseUser
    $env:SYNCMONEY_TEST_DB_PASSWORD = $script:databasePassword
    $env:SYNCMONEY_TEST_DB_NAME = $script:databaseName
    $env:SYNCMONEY_TEST_REDIS_PORT = [string]$script:redisPort
    $env:T21_RUN_MARKER = $script:runMarker

    Initialize-T21ProjectWorkspace $script:workspace
    New-Item -ItemType Directory -Force -Path (Join-Path $script:runRoot '.plugdev-home'), (Join-Path $script:runRoot '.gradle-user-home') | Out-Null

    if ($script:databaseProduct -ceq 'PostgreSQL') {
        $postgresInitLog = Join-Path $script:logsRoot 'postgresql-initialize.stdout.txt'
        $postgresPasswordFile = Join-Path $script:mysqlRoot 'postgres-superuser-password.txt'
        [IO.File]::WriteAllText($postgresPasswordFile, $script:databasePassword + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
        try {
            $initResult = Invoke-LoggedCommand $script:postgresInitDb @('-D', $script:mysqlData, '-U', 'postgres', '--auth-host=scram-sha-256', '--auth-local=scram-sha-256', '--pwfile', $postgresPasswordFile, '--encoding=UTF8') 'postgresql-initialize' $postgresInitLog
        } finally {
            if (Test-Path -LiteralPath $postgresPasswordFile -PathType Leaf) { Remove-Item -LiteralPath $postgresPasswordFile -Force }
        }
        if ($initResult.exitCode -ne 0) { throw "PostgreSQL 18.1 isolated data-directory initialization failed with exit code $($initResult.exitCode); see $postgresInitLog." }

        $postgresServerLog = Join-Path $script:logsRoot 'postgresql-server.log'
        $postgresOptions = "-h 127.0.0.1 -p $script:mysqlPort -c listen_addresses=127.0.0.1"
        $postgresStart = Invoke-PostgresStart $script:mysqlAdmin @('start', '-D', $script:mysqlData, '-l', $postgresServerLog, '-o', $postgresOptions, '-w', '-t', '45') 'postgresql-start' (Join-Path $script:logsRoot 'postgresql-start.txt')
        $script:postgresStartCommandExitCode = [int]$postgresStart.exitCode
        $script:report.postgresql.startCommandExitCode = [int]$postgresStart.exitCode
        $script:report.postgresql.startCommandProcessExited = [bool]$postgresStart.processExited
        if ($postgresStart.exitCode -ne 0) { throw "PostgreSQL pg_ctl start exited $($postgresStart.exitCode); see $postgresStart.logPath." }
        Wait-PostgresReady

        $mysqlVersion = @(Invoke-PostgresQuery "SELECT current_setting('server_version');" 'postgresql-server-version' -asRoot)
        if ($mysqlVersion.Count -ne 1 -or $mysqlVersion[0].Trim() -cne $script:databaseServerVersion) {
            throw "Isolated PostgreSQL server returned unexpected version '$($mysqlVersion -join ',')'."
        }
        $script:report.postgresql.serverVersion = $mysqlVersion[0].Trim()
        if ($script:databaseManagerInitFailureStartupFailure) {
            $null = Invoke-PostgresQuery "CREATE ROLE $script:databaseUser WITH LOGIN PASSWORD '$script:databasePassword' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION;" `
                'postgresql-create-isolated-role' -asRoot
            $null = Invoke-PostgresQuery "CREATE DATABASE $script:databaseName OWNER postgres;" 'postgresql-create-isolated-database' -asRoot
            $null = Invoke-PostgresQuery "REVOKE CONNECT ON DATABASE postgres FROM PUBLIC; GRANT CONNECT ON DATABASE postgres TO $script:databaseUser; REVOKE CONNECT ON DATABASE $script:databaseName FROM PUBLIC; GRANT CONNECT ON DATABASE $script:databaseName TO $script:databaseUser;" `
                'postgresql-grant-isolated-bootstrap-and-target-connect' -asRoot
            $null = Invoke-PostgresQuery "REVOKE ALL ON SCHEMA public FROM PUBLIC; REVOKE ALL ON SCHEMA public FROM $script:databaseUser; GRANT USAGE ON SCHEMA public TO $script:databaseUser;" `
                'postgresql-deny-initial-schema-create' -asRoot -Database $script:databaseName
        } elseif ($script:requiredV11DdlPermissionDeniedStartupFailure) {
            $null = Invoke-PostgresQuery "CREATE ROLE $script:databaseUser WITH LOGIN PASSWORD '$script:databasePassword' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION;" `
                'postgresql-create-isolated-role' -asRoot
            $null = Invoke-PostgresQuery "CREATE DATABASE $script:databaseName OWNER postgres;" 'postgresql-create-isolated-database' -asRoot
            $null = Invoke-PostgresQuery "REVOKE CONNECT ON DATABASE $script:databaseName FROM PUBLIC; GRANT CONNECT ON DATABASE $script:databaseName TO $script:databaseUser;" `
                'postgresql-grant-isolated-database-connect' -asRoot
            $null = Invoke-PostgresQuery "REVOKE ALL ON SCHEMA public FROM PUBLIC; GRANT USAGE, CREATE ON SCHEMA public TO $script:databaseUser;" `
                'postgresql-v11-schema-create-grant' -asRoot -Database $script:databaseName
        } else {
            $null = Invoke-PostgresQuery "CREATE ROLE $script:databaseUser LOGIN PASSWORD '$script:databasePassword';" 'postgresql-create-isolated-role' -asRoot
            $null = Invoke-PostgresQuery "CREATE DATABASE $script:databaseName OWNER $script:databaseUser;" 'postgresql-create-isolated-database' -asRoot
        }
    } else {
    $mysqlInitLog = Join-Path $script:logsRoot ($script:databaseProduct.ToLowerInvariant() + '-initialize.stdout.txt')
    $mysqlHome = Split-Path -Parent $script:mysqlBinPath
    if ($script:databaseProduct -ceq 'MariaDB') {
        $mysqlInitArgs = @("--datadir=$script:mysqlData", "--password=$script:databasePassword", '--allow-remote-root-access', '--silent')
        $initOutput = @(& $script:mysqlInstallDb @mysqlInitArgs 2>&1)
        $initExitCode = $LASTEXITCODE
        $initOutput | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath $mysqlInitLog -Encoding utf8
        if ($initExitCode -ne 0) { throw "MariaDB isolated data-directory initialization failed with exit code $initExitCode; see $mysqlInitLog." }
    } else {
        $mysqlInitArgs = @('--no-defaults', '--initialize-insecure', "--basedir=$mysqlHome", "--datadir=$script:mysqlData")
        $initOutput = @(& $script:mysqlServer @mysqlInitArgs 2>&1)
        $initExitCode = $LASTEXITCODE
        $initOutput | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath $mysqlInitLog -Encoding utf8
        if ($initExitCode -ne 0) { throw "MySQL isolated data-directory initialization failed with exit code $initExitCode; see $mysqlInitLog." }
    }

    $mysqlErrorLog = Join-Path $script:logsRoot ($script:databaseProduct.ToLowerInvariant() + '-error.log')
    $mysqlPidFile = Join-Path $script:mysqlRoot ($script:databaseProduct.ToLowerInvariant() + '.pid')
    $mysqlArgs = @('--no-defaults', "--basedir=$mysqlHome", "--datadir=$script:mysqlData", "--port=$script:mysqlPort", '--bind-address=127.0.0.1', "--log-error=$mysqlErrorLog", "--pid-file=$mysqlPidFile", '--console')
    if ($script:databaseProduct -ceq 'MySQL') { $mysqlArgs += '--mysqlx=OFF' }
    $mysqlArgumentLine = ($mysqlArgs | ForEach-Object { '"' + ([string]$_).Replace('"', '\"') + '"' }) -join ' '
    $script:mysqlLauncherProcess = Start-Process -FilePath $script:mysqlServer -ArgumentList $mysqlArgumentLine -WorkingDirectory $mysqlHome -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $script:logsRoot ($script:databaseProduct.ToLowerInvariant() + '-stdout.log')) -RedirectStandardError (Join-Path $script:logsRoot ($script:databaseProduct.ToLowerInvariant() + '-stderr.log'))
    $script:report.mysql.launchProcessId = [int]$script:mysqlLauncherProcess.Id
    Wait-MySqlReady
    $mysqlVersion = @(Invoke-MySqlQuery 'SELECT VERSION();' 'mysql-server-version' -asRoot)
    if ($mysqlVersion.Count -ne 1 -or $mysqlVersion[0] -notmatch $script:databaseServerVersionPattern) {
        throw "Isolated $script:databaseProduct server returned unexpected version '$($mysqlVersion -join ',')'."
    }
    $script:report.mysql.serverVersion = $mysqlVersion[0]

    $collation = if ($script:databaseProduct -ceq 'MariaDB') { 'utf8mb4_general_ci' } else { 'utf8mb4_0900_ai_ci' }
    $sql = "CREATE DATABASE $($script:databaseName) CHARACTER SET utf8mb4 COLLATE $collation; CREATE USER '$($script:databaseUser)'@'127.0.0.1' IDENTIFIED BY '$($script:databasePassword)'; CREATE USER '$($script:databaseUser)'@'localhost' IDENTIFIED BY '$($script:databasePassword)'; GRANT ALL PRIVILEGES ON $($script:databaseName).* TO '$($script:databaseUser)'@'127.0.0.1'; GRANT ALL PRIVILEGES ON $($script:databaseName).* TO '$($script:databaseUser)'@'localhost'; FLUSH PRIVILEGES;"
    $null = Invoke-MySqlQuery $sql 'mysql-create-isolated-schema' -asRoot
    }

    if ($script:databaseManagerInitFailureStartupFailure) {
        $script:databaseDdlPrivileges = Get-T21DatabaseManagerInitFailurePrivileges
        $script:databaseManagerInitFailureSchemaBefore = Get-T21DatabaseManagerInitFailureSchemaSnapshot 'before'
        $script:schemaStateBefore = $script:databaseManagerInitFailureSchemaBefore
        $initialSchemaCheck = Test-T21DatabaseManagerInitFailureSchemaState ([pscustomobject]@{
            before = $script:databaseManagerInitFailureSchemaBefore
            after = $script:databaseManagerInitFailureSchemaBefore
        })
        if (-not $initialSchemaCheck.passed) {
            throw "PostgreSQL initial schema was not empty before startup: $($initialSchemaCheck.failures -join '; ')"
        }
    } elseif ($script:newerAuditSchemaStartupFailure) {
        $script:schemaStateBefore = Seed-T21NewerAuditSchema
    } elseif ($script:incompleteV10AuditTableStartupFailure) {
        $script:schemaStateBefore = Seed-T21IncompleteV10AuditTable -SeedExistingPlayerRow:$script:incompleteV10AuditTablePlayerRowPreservation
    } elseif ($script:requiredV11DdlPermissionDeniedStartupFailure) {
        $script:schemaStateBefore = Seed-T21RequiredV11DdlPermissionFailure
    } elseif ($script:successfulV10AuditSchemaUpgrade) {
        $script:schemaStateBefore = Seed-T21AuditSchemaV10Upgrade `
            -SeedExistingPlayerRow:$script:successfulV10AuditSchemaSinglePlayerRowPreservation `
            -SeedMultipleExistingPlayerRows:$script:successfulV10AuditSchemaMultiplePlayerRowPreservation
    }
    if ($script:requiredV11DdlPermissionDeniedStartupFailure -and
        $script:databaseProduct -in @('MySQL', 'MariaDB') -and $PlatformEndpoint -in @('Maximum', 'CurrentMaximum')) {
        Enable-T21MySqlGeneralLogCapture
    }

    $redisConfig = Join-Path $script:redisRoot 'redis.conf'
    $redisLines = @(
        'bind 127.0.0.1', "port $script:redisPort", 'protected-mode yes', 'daemonize no',
        'dir ./', 'appendonly yes', 'appendfsync always', 'save ""', 'databases 16',
        'appendfilename appendonly.aof', 'appenddirname appendonlydir', 'logfile redis.log'
    )
    [IO.File]::WriteAllLines($redisConfig, [string[]]$redisLines, [Text.UTF8Encoding]::new($false))
    $script:redisProcess = Start-Process -FilePath $script:redisServer -ArgumentList 'redis.conf' -WorkingDirectory $script:redisRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $script:logsRoot 'redis-stdout.log') -RedirectStandardError (Join-Path $script:logsRoot 'redis-stderr.log')
    $script:report.redis.processId = [int]$script:redisProcess.Id
    Wait-RedisReady
    $redisInfo = Invoke-Redis @('-n', '15', 'INFO', 'server') 'redis-server-info'
    if ($redisInfo.output -notmatch '(?m)^redis_version:8\.10\.1$') { throw 'Isolated Redis server did not report version 8.10.1.' }
    if (((Invoke-Redis @('-n', '15', 'DBSIZE') 'redis-initial-dbsize').output.Trim()) -ne '0') { throw 'Isolated Redis DB 15 was not empty before the runtime cycle.' }

    $script:report.backendProcesses = [ordered]@{}
    if ($script:databaseProduct -ceq 'PostgreSQL') {
        $script:report.backendProcesses.postgresql = [ordered]@{
            product='PostgreSQL'; version=$mysqlVersion[0].Trim(); processId=[int]$script:mysqlProcess.Id; runOwned=$true
            listenerOwnerVerified=(Assert-BackendListenerOwner 'PostgreSQL' $script:mysqlPort $script:mysqlProcess.Id)
            listenerAddresses=@($script:report.postgresql.listenerAddresses)
        }
    } else {
        $script:report.backendProcesses.mysql = [ordered]@{
            product=$script:databaseProduct; version=$mysqlVersion[0]; processId=[int]$script:mysqlProcess.Id
            launchProcessId=[int]$script:mysqlLauncherProcess.Id; runOwned=$true
            listenerOwnerVerified=(Assert-BackendListenerOwner $script:databaseProduct $script:mysqlPort $script:mysqlProcess.Id)
            listenerAddresses=@($script:report.mysql.listenerAddresses)
        }
    }
    $script:report.backendProcesses.redis = [ordered]@{ version='8.10.1'; processId=[int]$script:redisProcess.Id; runOwned=$true; listenerOwnerVerified=(Assert-BackendListenerOwner 'Redis' $script:redisPort $script:redisProcess.Id) }

    Push-Location $script:workspace
    try {
        $prepareOutput = @(& $script:node (Join-Path $script:workspace 'tools\plugdev\prepare.mjs') $script:platformTarget.software $script:platformTarget.minecraftVersion 'sync' 'off' $CandidateVersion 2>&1)
        $prepareExit = $LASTEXITCODE
        $prepareOutput | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath (Join-Path $script:logsRoot 'prepare-profile.txt') -Encoding utf8
        if ($prepareExit -ne 0) { throw "Could not prepare the isolated $($script:platformTarget.taskSuffix) $script:databaseProduct SYNC profile." }
    } finally { Pop-Location }
    Set-T21PluginInProfile $CandidateVersion
    $configObservation = Assert-IsolatedSyncConfig
    $script:report.configuration += [ordered]@{
        configSchema=[int]$configObservation.schema
        observedMode=[string]$configObservation.mode
        databaseNameObserved=[string]$configObservation.databaseName
        configCredentialsConfigured=[bool]$configObservation.credentialsConfigured
    }

    if ($script:auditSchemaStartupFailure) {
        $failureScenario = switch ($true) {
            $script:databaseManagerInitFailureStartupFailure { 'DatabaseManagerInitFailure'; break }
            $script:newerAuditSchemaStartupFailure { 'NewerAuditSchema'; break }
            $script:incompleteV10AuditTablePlayerRowPreservation { 'IncompleteV10AuditTablePlayerRowPreservation'; break }
            $script:incompleteV10AuditTableStartupFailure { 'IncompleteV10AuditTable'; break }
            default { 'RequiredV11DdlPermissionDenied' }
        }
        $startup = Start-T21ServerExpectingAuditSchemaFailure $failureScenario
        if ($script:databaseManagerInitFailureStartupFailure) {
            $script:databaseManagerInitFailureSchemaAfter = Get-T21DatabaseManagerInitFailureSchemaSnapshot 'after'
            $script:schemaStateAfter = $script:databaseManagerInitFailureSchemaAfter
            $schemaCheck = Test-T21DatabaseManagerInitFailureSchemaState ([pscustomobject]@{
                before = $script:databaseManagerInitFailureSchemaBefore
                after = $script:databaseManagerInitFailureSchemaAfter
            })
            if (-not $schemaCheck.passed) {
                throw "PostgreSQL initial schema changed during the denied CREATE TABLE attempt: $($schemaCheck.failures -join '; ')"
            }
            $script:resourceCleanupObservation = Get-T21StartupFailureResourceCleanup
            if ($script:resourceCleanupObservation.sqlOtherConnections -ne 0 -or
                $script:resourceCleanupObservation.redisOtherClients -ne 0 -or
                $script:resourceCleanupObservation.redisDbSize -ne 0) {
                throw 'SQL or Redis resources remained active after DatabaseManager startup rollback.'
            }
            Stop-T21Server 'after-database-manager-init-failure'
        } else {
        $script:schemaStateAfter = Get-T21StartupFailureSchemaState
        $expectedAuditVersion = switch ($failureScenario) {
            'NewerAuditSchema' { 12 }
            'IncompleteV10AuditTable' { 9 }
            'IncompleteV10AuditTablePlayerRowPreservation' { 9 }
            default { 10 }
        }
        $expectedStartupFailurePlayerRows = if ($script:incompleteV10AuditTablePlayerRowPreservation) { 1 } else { 0 }
        if ($null -eq $script:schemaStateBefore -or
            $script:schemaStateAfter.mainVersion -ne 1 -or $script:schemaStateAfter.auditVersion -ne $expectedAuditVersion -or
            -not $script:schemaStateAfter.auditTableExists -or -not $script:schemaStateAfter.playersTableExists -or
            $script:schemaStateAfter.playerRows -ne $expectedStartupFailurePlayerRows -or
            $script:schemaStateBefore.auditVersionRowJson -cne $script:schemaStateAfter.auditVersionRowJson -or
            $script:schemaStateBefore.auditColumnsJson -cne $script:schemaStateAfter.auditColumnsJson -or
            $script:schemaStateBefore.auditIndexesJson -cne $script:schemaStateAfter.auditIndexesJson -or
            $script:schemaStateBefore.auditRowsJson -cne $script:schemaStateAfter.auditRowsJson -or
            ($script:incompleteV10AuditTablePlayerRowPreservation -and
                ($script:schemaStateBefore.playerRows -ne 1 -or
                    $script:schemaStateBefore.playerRowsJson -cne $script:schemaStateAfter.playerRowsJson)) -or
            (($script:incompleteV10AuditTableStartupFailure -or $script:requiredV11DdlPermissionDeniedStartupFailure) -and
                $script:schemaStateBefore.mainVersionRowJson -cne $script:schemaStateAfter.mainVersionRowJson)) {
            throw 'The failed candidate startup mutated the audit schema version, audit table columns/indexes/data, pre-existing main schema row, or player state.'
        }
        $script:resourceCleanupObservation = Get-T21StartupFailureResourceCleanup
        if ($script:resourceCleanupObservation.sqlOtherConnections -ne 0 -or
            $script:resourceCleanupObservation.redisOtherClients -ne 0 -or
            $script:resourceCleanupObservation.redisDbSize -ne 0) {
            throw 'SQL or Redis resources remained active after the startup rollback completed.'
        }
        Stop-T21Server 'after-startup-failure'
        }
    } elseif ($script:successfulV10AuditSchemaUpgrade) {
        $null = Start-T21Server 'candidate-audit-v10-upgrade'
        $upgradeLog = Get-Content -LiteralPath $script:serverLogPath -Raw -ErrorAction Stop
        $missingUpgradeMarkers = @(@(
                'Syncmoney audit schema version: current 10, target 11',
                'Starting audit schema upgrade: from version 10 to 11',
                'Audit schema version recorded: 11 - v1.1.0 Cursor pagination optimization',
                'Audit schema upgrade completed at version 11') | Where-Object { -not $upgradeLog.Contains($_) })
        Stop-T21Server 'after-audit-v10-upgrade'
        $script:schemaStateAfter = Get-T21StartupFailureSchemaState -AfterAuditSchemaV10Upgrade
        if ($missingUpgradeMarkers.Count -gt 0) { throw "Successful audit-schema migration log markers are missing: $($missingUpgradeMarkers -join '; ')" }
        if (-not (Test-T21AuditSchemaV10UpgradeState $script:schemaStateBefore $script:schemaStateAfter `
                -DatabaseProduct $script:databaseProduct `
                -PreserveExistingPlayerRow:$script:successfulV10AuditSchemaSinglePlayerRowPreservation `
                -PreserveMultipleExistingPlayerRows:$script:successfulV10AuditSchemaMultiplePlayerRowPreservation)) {
            throw 'The successful v10-to-v11 migration did not preserve the sentinel and produce the required v11 schema.'
        }
    } elseif ($script:legacySqlDataUpgrade) {
        $baselineStartup = Start-T21Server 'legacy-seed' '1.3.2'
        $seedLineStart = Get-ServerLogLineCount
        $null = Invoke-PlugDev @('server', 'command', 'smaccept write 1000.00 520.00') 'legacy-seed-write-command'
        $seedMarker = Wait-ServerLogMarker 'T21_WRITE_PASS expected-a=1000\.00 expected-b=520\.00 receipt-a=SUCCESS:DEPOSIT:1000\.00:1000\.00 receipt-b=SUCCESS:DEPOSIT:520\.00:520\.00' 'T21_WRITE_FAIL|ACCEPTANCE FAIL' $seedLineStart
        Stop-T21Server 'after-legacy-seed'
        $baselineSnapshot = Get-BackendSnapshot 'after-legacy-seed-stop' '1000.00' '520.00' -IncludeSchemaVersionRows
        $null = Assert-IsolatedSyncConfig
        $script:snapshots.Add($baselineSnapshot)
        Add-T21LegacySqlStage 'legacy-seed' '1.3.2' $seedMarker $baselineStartup $baselineSnapshot

        $candidateStartup = Start-T21Server 'candidate-existing-read' $CandidateVersion
        $candidateLineStart = Get-ServerLogLineCount
        $null = Invoke-PlugDev @('server', 'command', 'smaccept read 1000.00 520.00') 'candidate-existing-read-command'
        $candidateMarker = Wait-ServerLogMarker 'T21_READ_PASS expected-a=1000\.00 expected-b=520\.00' 'T21_READ_FAIL|ACCEPTANCE FAIL' $candidateLineStart
        Stop-T21Server 'after-candidate-existing-read'
        $candidateSnapshot = Get-BackendSnapshot 'after-candidate-existing-read-stop' '1000.00' '520.00' -IncludeSchemaVersionRows
        $null = Assert-IsolatedSyncConfig
        $script:snapshots.Add($candidateSnapshot)
        Add-T21LegacySqlStage 'candidate-existing-read' $CandidateVersion $candidateMarker $candidateStartup $candidateSnapshot

        $rollbackStartup = Start-T21Server 'legacy-rollback-read' '1.3.2'
        $rollbackLineStart = Get-ServerLogLineCount
        $null = Invoke-PlugDev @('server', 'command', 'smaccept rollback-read 1000.00 520.00') 'legacy-rollback-read-command'
        $rollbackMarker = Wait-ServerLogMarker 'T21_ROLLBACK_READ_PASS expected-a=1000\.00 expected-b=520\.00 actual-a=1000\.00 actual-b=520\.00' 'T21_READ_FAIL|ACCEPTANCE FAIL' $rollbackLineStart
        Stop-T21Server 'after-legacy-rollback-read'
        $rollbackSnapshot = Get-BackendSnapshot 'after-legacy-rollback-read-stop' '1000.00' '520.00' -IncludeSchemaVersionRows
        $null = Assert-IsolatedSyncConfig
        $script:snapshots.Add($rollbackSnapshot)
        Add-T21LegacySqlStage 'legacy-rollback-read' '1.3.2' $rollbackMarker $rollbackStartup $rollbackSnapshot
    } else {
        Start-T21Server 'candidate-write'
        $versionLineStart = Get-ServerLogLineCount
        $null = Invoke-PlugDev @('server', 'command', 'smaccept version') 'candidate-version-command'
        $null = Wait-ServerLogMarker 'T21_VERSION_PASS expected=1\.4\.0-alpha\.1 actual=1\.4\.0-alpha\.1' 'T21_VERSION_FAIL|ACCEPTANCE VERSION FAIL' $versionLineStart

        $writeLineStart = Get-ServerLogLineCount
        $null = Invoke-PlugDev @('server', 'command', 'smaccept write 1337.25 765.50') 'candidate-vault-write-command'
        $writeLine = Wait-ServerLogMarker 'T21_WRITE_PASS expected-a=1337\.25 expected-b=765\.50 receipt-a=SUCCESS:DEPOSIT:1337\.25:1337\.25 receipt-b=SUCCESS:DEPOSIT:765\.50:765\.50' 'T21_WRITE_FAIL|ACCEPTANCE FAIL' $writeLineStart
        $script:report.writeReceipts = @('SUCCESS:DEPOSIT:1337.25:1337.25', 'SUCCESS:DEPOSIT:765.50:765.50')
        $script:report.writeMarker = $writeLine

        Stop-T21Server 'after-write'
        $script:snapshots.Add((Get-BackendSnapshot 'after-write-stop'))

        Start-T21Server 'candidate-restart-read'
        $readLineStart = Get-ServerLogLineCount
        $null = Invoke-PlugDev @('server', 'command', 'smaccept read 1337.25 765.50') 'candidate-restart-read-command'
        $readObservation = Wait-ServerLogMarker 'ACCEPTANCE READ cold-vault-a=NaN cold-vault-unlocked-b=null a=1337\.25 b=765\.50 vault-unlocked-b=765\.50' 'T21_READ_FAIL|ACCEPTANCE FAIL' $readLineStart
        $readLine = Wait-ServerLogMarker 'T21_READ_PASS expected-a=1337\.25 expected-b=765\.50' 'T21_READ_FAIL|ACCEPTANCE FAIL' $readLineStart
        $script:report.restartReadMarker = $readLine
        $script:report.restartReadObservation = $readObservation
        Stop-T21Server 'after-restart-read'
        $script:snapshots.Add((Get-BackendSnapshot 'after-restart-read-stop'))
    }

} catch {
    $script:failure = $_.Exception.ToString()
    [Console]::Error.WriteLine("T21 $script:databaseProduct runtime case failed: $($script:failure)")
} finally {
    try { Disable-T21MySqlGeneralLogCapture } catch { $script:cleanupFailures.Add("MySQL general-log cleanup failed: $($_.Exception.Message)") }
    try {
        if (@(Get-T21ServerProcesses).Count -gt 0) {
            if ($script:successfulV10AuditSchemaUpgrade) {
                try { Stop-T21Server 'after-audit-v10-upgrade' } catch {
                    $script:cleanupFailures.Add("Graceful Paper cleanup failed after the audit-schema upgrade case: $($_.Exception.Message)")
                }
            }
            if (@(Get-T21ServerProcesses).Count -gt 0) { Stop-RunOwnedServerAfterFailure }
        }
    } catch { $script:cleanupFailures.Add("Platform cleanup check failed: $($_.Exception.Message)") }
    try { Stop-MySqlBackend } catch { $script:cleanupFailures.Add("MySQL cleanup failed: $($_.Exception.Message)") }
    try { Stop-RedisBackend } catch { $script:cleanupFailures.Add("Redis cleanup failed: $($_.Exception.Message)") }
    try { $script:report.gradleDaemonCleanup = Stop-T21RunOwnedGradleDaemons } catch {
        $script:cleanupFailures.Add("Isolated Gradle daemon cleanup failed: $($_.Exception.Message)")
        $script:report.gradleDaemonCleanup = [ordered]@{
            isolatedUserHome=[IO.Path]::GetFullPath($script:gradleUserHome)
            processIdsBeforeStop=@()
            stopCommandExitCode=$null
            stopLogPath=$null
            processIdsAfterStop=@()
            processExitVerified=$false
        }
    }
    try { Restore-TestEnvironment } catch { $script:cleanupFailures.Add("Process environment restore failed: $($_.Exception.Message)") }
    if ($script:databaseManagerInitFailureStartupFailure) {
        try { $script:resourceCleanupObservation = Get-T21DatabaseManagerInitFailureFinalCleanup }
        catch { $script:cleanupFailures.Add("DatabaseManager startup-failure cleanup evidence failed: $($_.Exception.Message)") }
    }
    try {
        $script:report.logs = [ordered]@{}
        foreach ($log in @(Get-ChildItem -LiteralPath $script:logsRoot -File -Recurse)) {
            $relative = $log.FullName.Substring($script:runRoot.Length).TrimStart('\')
            $script:report.logs[$relative] = [ordered]@{ bytes=[long]$log.Length; sha256=(Get-FileHash -LiteralPath $log.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
        }
        $plugDevSourceCommitAfter = (& git -C $script:plugDevRoot rev-parse HEAD 2>$null | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or $plugDevSourceCommitAfter -cne $script:plugDevSourceCommit) {
            $script:cleanupFailures.Add('The local PlugDev source commit changed during runtime acceptance.')
        }
        $script:report.plugDev.sourceCommit = $plugDevSourceCommitAfter
        $plugDevCliDistManifestAfter = Get-T21PlugDevCliDistManifest $script:plugDevRoot
        $script:report.plugDev.cliDistManifestSha256After = $plugDevCliDistManifestAfter.sha256
        $script:report.plugDev.cliDistInvocationObservations = @($script:plugDevCliInvocationObservations.ToArray())
        if ($plugDevCliDistManifestAfter.sha256 -cne $script:plugDevCliDistManifest.sha256) {
            $script:cleanupFailures.Add('The local PlugDev CLI bundle changed during runtime acceptance.')
        }
        $sourceCommitAfter = (& git -C $script:root rev-parse HEAD 2>$null | Out-String).Trim().ToLowerInvariant()
        $sourceDirtyAfter = @(& git -C $script:root status --porcelain --untracked-files=all 2>$null)
        if ($LASTEXITCODE -ne 0 -or $sourceCommitAfter -cne $script:sourceCommit -or $sourceDirtyAfter.Count -ne 0) {
            $script:cleanupFailures.Add('The Syncmoney source commit or clean worktree state changed during runtime acceptance.')
        }
        $script:report.sourceCommitAfter = $sourceCommitAfter
        $script:report.sourceWorkingTreeDirtyAfter = [bool]($sourceDirtyAfter.Count -gt 0)
        $script:report.runner = [ordered]@{
            path='tools/plugdev/run-mysql-sync-runtime.ps1'
            sha256=(Get-FileHash -LiteralPath (Join-Path $script:root 'tools\plugdev\run-mysql-sync-runtime.ps1') -Algorithm SHA256).Hash.ToLowerInvariant()
            databaseManagerInitFailureValidatorSha256=(Get-FileHash -LiteralPath (Join-Path $script:root 'tools\plugdev\t21-database-manager-init-failure-validation.ps1') -Algorithm SHA256).Hash.ToLowerInvariant()
            databaseManagerInitFailureTestSha256=(Get-FileHash -LiteralPath (Join-Path $script:root 'tools\plugdev\t21-database-manager-init-failure-validation.test.ps1') -Algorithm SHA256).Hash.ToLowerInvariant()
            validatorSha256=(Get-FileHash -LiteralPath (Join-Path $script:root 'tools\plugdev\t21-upgrade-validation.ps1') -Algorithm SHA256).Hash.ToLowerInvariant()
            testSha256=(Get-FileHash -LiteralPath (Join-Path $script:root 'tools\plugdev\t21-upgrade-validation.test.ps1') -Algorithm SHA256).Hash.ToLowerInvariant()
            startupFailureValidatorSha256=(Get-FileHash -LiteralPath (Join-Path $script:root 'tools\plugdev\t21-startup-failure-validation.ps1') -Algorithm SHA256).Hash.ToLowerInvariant()
            startupFailureValidatorTestSha256=(Get-FileHash -LiteralPath (Join-Path $script:root 'tools\plugdev\t21-startup-failure-validation.test.ps1') -Algorithm SHA256).Hash.ToLowerInvariant()
            legacyValidatorSha256=(Get-FileHash -LiteralPath (Join-Path $script:root 'tools\plugdev\t21-legacy-sql-upgrade-validation.ps1') -Algorithm SHA256).Hash.ToLowerInvariant()
            legacyTestSha256=(Get-FileHash -LiteralPath (Join-Path $script:root 'tools\plugdev\t21-legacy-sql-upgrade-validation.test.ps1') -Algorithm SHA256).Hash.ToLowerInvariant()
            auditSchemaUpgradeValidatorSha256=(Get-FileHash -LiteralPath (Join-Path $script:root 'tools\plugdev\t21-audit-schema-upgrade-validation.ps1') -Algorithm SHA256).Hash.ToLowerInvariant()
            auditSchemaUpgradeTestSha256=(Get-FileHash -LiteralPath (Join-Path $script:root 'tools\plugdev\t21-audit-schema-upgrade-validation.test.ps1') -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    } catch {
        $script:failure = "Could not finalize runtime evidence hashes: $($_.Exception.Message)"
        $script:cleanupFailures.Add($script:failure)
    }

    $script:report.finishedAtUtc = [DateTime]::UtcNow.ToString('o')
    $script:report.startupObservations = @($script:startups)
    $script:report.platformLogs = @($script:platformServerLogs)
    $script:report.normalStops = @($script:normalStops)
    $script:report.snapshots = @($script:snapshots)
    $script:report.backendShutdowns = @($script:backendShutdowns)
    $script:report.cleanupFailures = @($script:cleanupFailures)
    $script:report.failure = $script:failure
    $script:report.schemaStateBefore = $script:schemaStateBefore
    $script:report.schemaStateAfter = $script:schemaStateAfter
    if ($null -eq $script:failure -and $script:cleanupFailures.Count -eq 0) {
        $script:report.status = 'PASS_BOUNDED_SUBCASE'
        if ($script:auditSchemaStartupFailure) {
            $script:finalReport = [ordered]@{
                schemaVersion=1
                sourceCommit=$script:sourceCommit
                sourceCommitAfter=$script:report.sourceCommitAfter
                sourceWorkingTreeDirty=$script:sourceWorkingTreeDirty
                sourceWorkingTreeDirtyAfter=$script:report.sourceWorkingTreeDirtyAfter
                task=if ($script:databaseManagerInitFailureStartupFailure) {
                    'T21-SQL-POOL-INITIAL-SCHEMA-FAILURE-PostgreSQL-CurrentMaximum'
                } elseif ($script:newerAuditSchemaStartupFailure) {
                    'T21-V03-SYNC-newer-audit-schema-startup-failure'
                } elseif ($script:incompleteV10AuditTablePlayerRowPreservation) {
                    $productTaskSuffix = if ($script:databaseProduct -ceq 'MySQL') { 'MySQL' } else { $script:databaseProduct }
                    "T21-V03-SYNC-incomplete-v10-audit-table-player-row-preservation-$productTaskSuffix-$($script:platformTarget.taskSuffix)"
                } elseif ($script:incompleteV10AuditTableStartupFailure) {
                    'T21-V03-SYNC-incomplete-v10-audit-table-startup-failure'
                } else {
                    'T21-V03-SYNC-v11-audit-ddl-permission-failure'
                }
                status='PASS_BOUNDED_SUBCASE'
                runId=$script:runId
                runDirectory=$script:runRoot
                server=[ordered]@{
                    software=$script:platformTarget.software
                    minecraftVersion=$script:platformTarget.minecraftVersion
                    build=$script:platformTarget.build
                    sha256=$serverSha256
                    bytes=[long](Get-Item -LiteralPath $script:serverPath).Length
                    path=$script:serverPath
                }
                candidate=[ordered]@{ version=$CandidateVersion; sha256=$script:candidateSha256 }
                artifacts=[ordered]@{ acceptanceSha256=$acceptanceSha256; apiConsumerSha256=$apiConsumerSha256 }
                java=[ordered]@{ feature=$javaFeature; banner=$javaBanner.Trim() }
                plugDev=[ordered]@{
                    root=$script:report.plugDev.root
                    sourceCommit=$script:report.plugDev.sourceCommit
                    cliSha256=$script:report.plugDev.cliSha256
                    cliDistManifest=$script:report.plugDev.cliDistManifest
                    cliDistManifestSha256Before=$script:report.plugDev.cliDistManifestSha256Before
                    cliDistManifestSha256After=$script:report.plugDev.cliDistManifestSha256After
                    cliDistInvocationObservations=$script:report.plugDev.cliDistInvocationObservations
                }
                sqlBackend=[ordered]@{ product=$script:databaseProduct; version=$script:databaseServerVersion }
                redis=[ordered]@{ version='8.10.1' }
                configuration=[ordered]@{
                    mode='sync'
                    databaseEnabled=$true
                    databaseType=$(if ($script:databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' })
                    databaseIsolated=$true
                    databaseName=$script:report.configuration.databaseName
                    databaseNameObserved=[string]$configObservation.databaseName
                    redisEnabled=$true
                    redisDatabase=15
                    redisIsolated=$true
                }
                backendProcesses=$script:report.backendProcesses
                startupObservation=$script:startups[0]
                schemaStateBefore=$script:schemaStateBefore
                schemaStateAfter=$script:schemaStateAfter
                databaseDdlPrivileges=$script:databaseDdlPrivileges
                deniedDdlEvidence=$script:deniedDdlEvidence
                resourceCleanup=$script:resourceCleanupObservation
                platformLogs=@($script:platformServerLogs)
                normalStops=@($script:normalStops)
                backendShutdowns=@($script:backendShutdowns)
                gradleDaemonCleanup=$script:report.gradleDaemonCleanup
                cleanupFailures=@($script:cleanupFailures)
                failure=$script:failure
                logs=$script:report.logs
                sourcePins=[ordered]@{
                    sourceCommit=$script:sourceCommit
                    runnerSha256=$script:report.runner.sha256
                    validatorSha256=$script:report.runner.startupFailureValidatorSha256
                    validatorTestSha256=$script:report.runner.startupFailureValidatorTestSha256
                    sharedValidatorSha256=$script:report.runner.validatorSha256
                    sharedValidatorTestSha256=$script:report.runner.testSha256
                    acceptanceJarSha256=$acceptanceSha256
                    apiConsumerJarSha256=$apiConsumerSha256
                    plugDevCliDistManifestSha256=$script:report.plugDev.cliDistManifestSha256Before
                    plugDevCommit=$script:report.plugDev.sourceCommit
                    plugDevCliSha256=$script:report.plugDev.cliSha256
                    databaseManagerInitFailureValidatorSha256=$script:report.runner.databaseManagerInitFailureValidatorSha256
                    databaseManagerInitFailureTestSha256=$script:report.runner.databaseManagerInitFailureTestSha256
                    databaseManagerSourceSha256=(Get-FileHash -LiteralPath (Join-Path $script:root 'syncmoney-bukkit\src\main\java\noietime\syncmoney\storage\db\DatabaseManager.java') -Algorithm SHA256).Hash.ToLowerInvariant()
                    storageManagerSourceSha256=(Get-FileHash -LiteralPath (Join-Path $script:root 'syncmoney-bukkit\src\main\java\noietime\syncmoney\storage\StorageManager.java') -Algorithm SHA256).Hash.ToLowerInvariant()
                    syncmoneyLifecycleSourceSha256=(Get-FileHash -LiteralPath (Join-Path $script:root 'syncmoney-bukkit\src\main\java\noietime\syncmoney\Syncmoney.java') -Algorithm SHA256).Hash.ToLowerInvariant()
                    paperJarSha256=$serverSha256
                    candidateJarSha256=$script:candidateSha256
                }
            }
            if ($script:databaseManagerInitFailureStartupFailure) {
                $script:finalReport.runner = $script:report.runner
            }
            $failureScenario = switch ($true) {
                $script:databaseManagerInitFailureStartupFailure { 'DatabaseManagerInitFailure'; break }
                $script:newerAuditSchemaStartupFailure { 'NewerAuditSchema'; break }
                $script:incompleteV10AuditTablePlayerRowPreservation { 'IncompleteV10AuditTablePlayerRowPreservation'; break }
                $script:incompleteV10AuditTableStartupFailure { 'IncompleteV10AuditTable'; break }
                default { 'RequiredV11DdlPermissionDenied' }
            }
            if ($script:databaseManagerInitFailureStartupFailure) {
                $reportAccepted = Test-T21DatabaseManagerInitFailureReport $script:finalReport `
                    $ExpectedServerSha256 $ExpectedCandidateSha256 $script:plugDevSourceCommit $script:plugDevCliSha256 `
                    $script:plugDevCliDistManifest.sha256 $acceptanceSha256 $apiConsumerSha256 $script:sourceCommit
            } else {
                $reportAccepted = Test-T21NewerAuditSchemaStartupFailureReport $script:finalReport `
                    $ExpectedServerSha256 $ExpectedCandidateSha256 $acceptanceSha256 $apiConsumerSha256 `
                    -ExpectedPlugDevRoot $script:plugDevRoot `
                    -ExpectedPlugDevSourceCommit $script:plugDevSourceCommit -ExpectedPlugDevCliSha256 $script:plugDevCliSha256 `
                    -ExpectedPlugDevCliDistManifestSha256 $script:plugDevCliDistManifest.sha256 `
                    -DatabaseProduct $script:databaseProduct -PlatformEndpoint $PlatformEndpoint -FailureScenario $failureScenario
            }
        } elseif ($script:successfulV10AuditSchemaUpgrade) {
            $reportAccepted = Test-T21AuditSchemaV10UpgradeReport $script:report `
                $ExpectedServerSha256 $ExpectedCandidateSha256 `
                -DatabaseProduct $script:databaseProduct -PlatformEndpoint $PlatformEndpoint
            $script:finalReport = $script:report
        } elseif ($script:legacySqlDataUpgrade) {
            $backendProcesses = $script:report.backendProcesses
            $databaseProcess = $backendProcesses[$script:sqlReportKey]
            $redisProcess = $backendProcesses.redis
            $databaseEvidence = [ordered]@{
                product=$script:databaseProduct
                version=$script:databaseServerVersion
            }
            $databaseProcessEvidence = [ordered]@{
                product=$script:databaseProduct
                version=$script:databaseServerVersion
                processId=[int]$databaseProcess.processId
                runOwned=[bool]$databaseProcess.runOwned
                listenerOwnerVerified=[bool]$databaseProcess.listenerOwnerVerified
            }
            if ($script:databaseProduct -in @('MySQL', 'MariaDB')) {
                $databaseProcessEvidence.launchProcessId = [int]$databaseProcess.launchProcessId
            }
            $backendProcessEvidence = [ordered]@{}
            $backendProcessEvidence[$script:sqlReportKey] = $databaseProcessEvidence
            $backendProcessEvidence.redis = [ordered]@{
                version=[string]$redisProcess.version
                processId=[int]$redisProcess.processId
                runOwned=[bool]$redisProcess.runOwned
                listenerOwnerVerified=[bool]$redisProcess.listenerOwnerVerified
            }
            $script:finalReport = [ordered]@{
                schemaVersion=2
                task="T21-M05-LEGACY-SQL-DATA-$($script:databaseProduct)-$($script:platformTarget.taskSuffix)"
                status='PASS_BOUNDED_SUBCASE'
                runDirectory=$script:runRoot
                server=[ordered]@{
                    software=$script:platformTarget.software
                    minecraftVersion=$script:platformTarget.minecraftVersion
                    build=$script:platformTarget.build
                    sha256=$serverSha256
                }
                baseline=[ordered]@{ version='1.3.2'; sha256=$script:baselineSha256; sourceCommit=$script:baselineSourceCommit }
                candidate=[ordered]@{ version=$CandidateVersion; sha256=$script:candidateSha256 }
                artifacts=[ordered]@{ acceptanceSha256=$acceptanceSha256; apiConsumerSha256=$apiConsumerSha256 }
                java=[ordered]@{ feature=$javaFeature; banner=$javaBanner.Trim() }
                redis=[ordered]@{ version='8.10.1' }
                configuration=[ordered]@{
                    schema=[int]$configObservation.schema
                    mode=[string]$configObservation.mode
                    databaseEnabled=$true
                    databaseType=$(if ($script:databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' })
                    databaseIsolated=$true
                    redisEnabled=$true
                    redisDatabase=15
                    redisIsolated=$true
                }
                stages=@($script:legacySqlStages)
                platformLogs=@($script:platformServerLogs)
                logs=$script:report.logs
                backendProcesses=$backendProcessEvidence
                backendShutdowns=@($script:backendShutdowns)
                gradleDaemonCleanup=$script:report.gradleDaemonCleanup
                cleanupFailures=@($script:cleanupFailures)
                failure=$script:failure
                sourcePins=[ordered]@{
                    runnerSha256=$script:report.runner.sha256
                    validatorSha256=$script:report.runner.legacyValidatorSha256
                    validatorTestSha256=$script:report.runner.legacyTestSha256
                    sharedValidatorSha256=$script:report.runner.validatorSha256
                    sharedValidatorTestSha256=$script:report.runner.testSha256
                    plugDevCommit=$script:report.plugDev.sourceCommit
                    plugDevCliSha256=$script:report.plugDev.cliSha256
                    paperJarSha256=$serverSha256
                    baselineJarSha256=$script:baselineSha256
                    candidateJarSha256=$script:candidateSha256
                    acceptanceJarSha256=$acceptanceSha256
                    apiConsumerJarSha256=$apiConsumerSha256
                }
            }
            $script:finalReport[$script:sqlReportKey] = $databaseEvidence
            $reportAccepted = switch ($script:databaseProduct) {
                'PostgreSQL' { Test-T21PostgresLegacyDataUpgradeReport $script:finalReport $ExpectedServerSha256 $ExpectedBaselinePluginSha256 $ExpectedCandidateSha256 $acceptanceSha256 $apiConsumerSha256 -PlatformEndpoint $PlatformEndpoint }
                'MariaDB' { Test-T21MariaDbLegacyDataUpgradeReport $script:finalReport $ExpectedServerSha256 $ExpectedBaselinePluginSha256 $ExpectedCandidateSha256 $acceptanceSha256 $apiConsumerSha256 -PlatformEndpoint $PlatformEndpoint }
                default { Test-T21MySqlLegacyDataUpgradeReport $script:finalReport $ExpectedServerSha256 $ExpectedBaselinePluginSha256 $ExpectedCandidateSha256 $acceptanceSha256 $apiConsumerSha256 -PlatformEndpoint $PlatformEndpoint }
            }
        } else {
            $reportAccepted = if ($script:databaseProduct -ceq 'PostgreSQL') {
                Test-T21PostgresRuntimeReport $script:report $ExpectedServerSha256 $ExpectedCandidateSha256 -PlatformEndpoint $PlatformEndpoint -ExpectedApiConsumerSha256 $apiConsumerSha256
            } else {
                Test-T21MySqlRuntimeReport $script:report $ExpectedServerSha256 $ExpectedCandidateSha256 -DatabaseProduct $script:databaseProduct -PlatformEndpoint $PlatformEndpoint -ExpectedApiConsumerSha256 $apiConsumerSha256
            }
            $script:finalReport = $script:report
        }
        if (-not $reportAccepted) {
            $script:finalReport.status = 'FAIL'
            $script:failure = if ($script:legacySqlDataUpgrade) {
                'The completed report did not satisfy the product-specific T21 legacy SQL data upgrade predicate.'
            } elseif ($script:successfulV10AuditSchemaUpgrade) {
                'The completed report did not satisfy Test-T21AuditSchemaV10UpgradeReport.'
            } elseif ($script:databaseManagerInitFailureStartupFailure) {
                'The completed report did not satisfy Test-T21DatabaseManagerInitFailureReport.'
            } elseif ($script:auditSchemaStartupFailure) {
                'The completed report did not satisfy Test-T21NewerAuditSchemaStartupFailureReport.'
            } else {
                'The completed runtime evidence did not satisfy Test-T21MySqlRuntimeReport.'
            }
        }
    } else {
        $script:finalReport = $script:report
        $script:finalReport.status = 'FAIL'
    }
    $script:finalReport.failure = $script:failure
    try {
        $script:finalReport | ConvertTo-Json -Depth 24 | Set-Content -LiteralPath $script:reportPath -Encoding utf8
    } catch {
        $script:failure = "Could not persist the machine report: $($_.Exception.Message)"
        [Console]::Error.WriteLine($script:failure)
    }
}

if ($null -ne $script:failure -or $script:finalReport.status -cne 'PASS_BOUNDED_SUBCASE') {
    throw "T21 $script:databaseProduct SYNC runtime did not pass. Report: $script:reportPath"
}
$databaseRuntimeLabel = if ($script:legacySqlDataUpgrade) { "$script:databaseProduct $script:databaseServerVersion legacy-data upgrade" } else { switch ($script:databaseProduct) { 'MariaDB' { 'MariaDB 12.3.3 using the MySQL protocol' } 'PostgreSQL' { 'PostgreSQL 18.1' } default { 'MySQL 8.0.42' } } }
$platformRuntimeLabel = switch ($script:platformTarget.software) { 'folia' { 'Folia' } 'canvas' { 'Canvas' } default { 'Paper' } }
Write-Output "PASS_BOUNDED_SUBCASE: $platformRuntimeLabel $($script:platformTarget.minecraftVersion)#$($script:platformTarget.build) SYNC / $databaseRuntimeLabel / Redis 8.10.1; report=$script:reportPath"
