$ErrorActionPreference = 'Stop'
$VerbosePreference = 'Continue'
Set-StrictMode -Version Latest

if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or newer is required for T21 startup-failure validator tests.' }
. (Join-Path $PSScriptRoot 't21-startup-failure-validation.ps1')

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('t21-startup-failure-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path (Join-Path $testRoot 'logs') | Out-Null
try {
    $platformLogPath = Join-Path $testRoot 'logs\platform-candidate-startup-failure.txt'
    $platformLog = @(
        '[07:43:59] [Server thread/INFO]: [Bootstrap] Loading Paper 1.21.6-48-main@4d854e6 for Minecraft 1.21.6'
        '[07:44:00] [Server thread/INFO]: Loading server plugin Syncmoney v1.4.0-alpha.1'
        '[07:44:00] [Server thread/INFO]: [Syncmoney] Syncmoney audit schema version: current 12, target 11'
        '[07:44:00] [Server thread/SEVERE]: [Syncmoney] Syncmoney initialization failed; rolling back initialized owners'
        'java.lang.IllegalStateException: Audit schema version 12 is newer than this plugin supports (11); no audit schema changes were made.'
        '[07:44:01] [Syncmoney-DbWriter/INFO]: [noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Shutdown initiated...'
        '[07:44:01] [Syncmoney-DbWriter/INFO]: [noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Shutdown completed.'
        '[07:44:00] [Server thread/INFO]: [Syncmoney] Syncmoney disabled.'
    )
    [IO.File]::WriteAllLines($platformLogPath, [string[]]$platformLog, [Text.UTF8Encoding]::new($false))
    $logInfo = Get-Item -LiteralPath $platformLogPath
    $logHash = (Get-FileHash -LiteralPath $platformLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $endpoint = Get-T21PlatformEndpoint -PlatformEndpoint Minimum
    $runnerPath = Join-Path $PSScriptRoot 'run-mysql-sync-runtime.ps1'
    $sharedValidatorPath = Join-Path $PSScriptRoot 't21-upgrade-validation.ps1'
    $sharedTestPath = Join-Path $PSScriptRoot 't21-upgrade-validation.test.ps1'
    $validatorPath = Join-Path $PSScriptRoot 't21-startup-failure-validation.ps1'
    $testPath = $PSCommandPath
    $auditVersionRowJson = '{"id":"audit","version":12,"applied_at":"2026-10-14T00:00:00","description":"T21 newer audit schema preservation sentinel"}'
    $columnNames = @('id', 'timestamp', 'type', 'player_uuid', 'player_name', 'amount', 'balance_after', 'source', 'server', 'target_uuid', 'target_name', 'reason', 'merged_count', 'request_id', 'sequence', 't21_sentinel')
    $columnMetadataFields = @(
        'table_catalog', 'table_schema', 'table_name', 'column_name', 'ordinal_position', 'column_default', 'is_nullable', 'data_type',
        'character_maximum_length', 'character_octet_length', 'numeric_precision', 'numeric_precision_radix', 'numeric_scale',
        'datetime_precision', 'interval_type', 'interval_precision', 'character_set_catalog', 'character_set_schema', 'character_set_name',
        'collation_catalog', 'collation_schema', 'collation_name', 'domain_catalog', 'domain_schema', 'domain_name', 'udt_catalog',
        'udt_schema', 'udt_name', 'scope_catalog', 'scope_schema', 'scope_name', 'maximum_cardinality', 'dtd_identifier',
        'is_self_referencing', 'is_identity', 'identity_generation', 'identity_start', 'identity_increment', 'identity_maximum',
        'identity_minimum', 'identity_cycle', 'is_generated', 'generation_expression', 'is_updatable'
    )
    $columnRows = @(
        for ($index = 0; $index -lt $columnNames.Count; $index++) {
            $columnRow = [ordered]@{}
            foreach ($field in $columnMetadataFields) { $columnRow[$field] = $null }
            $columnRow.table_catalog = 'syncmoney_t21_fixture'
            $columnRow.table_schema = 'public'
            $columnRow.table_name = 'syncmoney_audit_log'
            $columnRow.column_name = $columnNames[$index]
            $columnRow.ordinal_position = $index + 1
            $columnRow.data_type = 'text'
            $columnRow.udt_name = 'text'
            $columnRow.is_nullable = 'YES'
            $columnRow.is_self_referencing = 'NO'
            $columnRow.is_identity = 'NO'
            $columnRow.is_generated = 'NEVER'
            $columnRow.is_updatable = 'YES'
            [pscustomobject]$columnRow
        }
    )
    $auditColumnsJson = ConvertTo-Json -InputObject ([object[]]$columnRows) -Depth 10 -Compress
    $auditIndexesJson = '[{"name":"idx_audit_player","definition":"CREATE INDEX idx_audit_player ON public.syncmoney_audit_log USING btree (player_uuid, timestamp DESC, sequence DESC)"},{"name":"idx_audit_timestamp","definition":"CREATE INDEX idx_audit_timestamp ON public.syncmoney_audit_log USING btree (timestamp DESC, sequence DESC)"},{"name":"idx_audit_type_timestamp_sequence","definition":"CREATE INDEX idx_audit_type_timestamp_sequence ON public.syncmoney_audit_log USING btree (type, timestamp DESC, sequence DESC)"},{"name":"syncmoney_audit_log_pkey","definition":"CREATE UNIQUE INDEX syncmoney_audit_log_pkey ON public.syncmoney_audit_log USING btree (id)"}]'
    $auditRowsJson = '[{"id":"00000000-0000-0000-0000-000000000321","timestamp":1791936000000,"type":"DEPOSIT","player_uuid":"00000000-0000-0000-0000-000000000321","player_name":"T21Fixture","amount":1.25,"balance_after":99.99,"source":"TEST","server":"t21-startup-failure","target_uuid":null,"target_name":null,"reason":"v12 preservation sentinel","merged_count":1,"request_id":"00000000-0000-0000-0000-000000000322","sequence":77,"t21_sentinel":"t21-v12-preservation-sentinel"}]'
    $hash = { param([string]$path) (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
    $report = [ordered]@{
        schemaVersion = 1
        task = 'T21-V03-SYNC-newer-audit-schema-startup-failure'
        status = 'PASS_BOUNDED_SUBCASE'
        runId = [guid]::NewGuid().ToString('N')
        runDirectory = $testRoot
        server = [ordered]@{ software = 'paper'; minecraftVersion = '1.21.6'; build = 48; sha256 = $endpoint.sha256 }
        candidate = [ordered]@{ version = '1.4.0-alpha.1'; sha256 = ('a' * 64) }
        artifacts = [ordered]@{ acceptanceSha256 = ('b' * 64); apiConsumerSha256 = ('c' * 64) }
        java = [ordered]@{ feature = 25; banner = 'openjdk version "25.0.4" 2026-07-21 LTS' }
        plugDev = [ordered]@{ sourceCommit = ('d' * 40); cliSha256 = ('e' * 64) }
        configuration = [ordered]@{
            mode = 'sync'; databaseEnabled = $true; databaseType = 'postgresql'; databaseIsolated = $true
            redisEnabled = $true; redisDatabase = 15; redisIsolated = $true
        }
        sqlBackend = [ordered]@{ product = 'PostgreSQL'; version = '18.1' }
        backendProcesses = [ordered]@{
            postgresql = [ordered]@{ product = 'PostgreSQL'; version = '18.1'; processId = 3001; runOwned = $true; listenerOwnerVerified = $true }
            redis = [ordered]@{ version = '8.10.1'; processId = 3002; runOwned = $true; listenerOwnerVerified = $true }
        }
        startupObservation = [ordered]@{
            stage = 'candidate-startup-failure'; pluginVersion = '1.4.0-alpha.1'; artifactSha256 = ('a' * 64)
            enabled = $false; processId = 4001; runtimeIdentity = [ordered]@{ software = 'paper'; minecraftVersion = '1.21.6'; build = 48 }
            expectedFailure = 'newer-audit-schema'; failureObserved = $true; failureMessage = 'Audit schema version 12 is newer than this plugin supports (11); no audit schema changes were made.'
            rollbackComplete = $true; sqlPoolShutdownObserved = $true; disabledMarker = $true; successMarkerObserved = $false
        }
        schemaStateBefore = [ordered]@{
            mainVersion = $null; mainVersionRowJson = 'null'; auditVersion = 12; auditTableExists = $true; playersTableExists = $false; playerRows = $null
            auditVersionRowJson = $auditVersionRowJson; auditColumnsJson = $auditColumnsJson; auditIndexesJson = $auditIndexesJson; auditRowsJson = $auditRowsJson
        }
        schemaStateAfter = [ordered]@{
            mainVersion = 1; mainVersionRowJson = '{"id":"1","version":1,"applied_at":"2026-10-14T00:00:00","description":"main schema"}'; auditVersion = 12; auditTableExists = $true; playersTableExists = $true; playerRows = 0
            auditVersionRowJson = $auditVersionRowJson; auditColumnsJson = $auditColumnsJson; auditIndexesJson = $auditIndexesJson; auditRowsJson = $auditRowsJson
        }
        resourceCleanup = [ordered]@{ sqlOtherConnections = 0; redisOtherClients = 0; redisDbSize = 0 }
        platformLogs = @([ordered]@{ stage = 'candidate-startup-failure'; path = 'logs\platform-candidate-startup-failure.txt'; bytes = [long]$logInfo.Length; sha256 = $logHash })
        normalStops = @([ordered]@{ stage = 'after-startup-failure'; commandExitCode = 0; processId = 4001; processExitCode = 0; processExitCodeObserved = $true; processExited = $true; disabledMarker = $true; forced = $false })
        backendShutdowns = @(
            [ordered]@{ backend = 'postgresql'; command = 'pg_ctl stop -m fast -w'; commandExitCode = 0; processId = 3001; shutdownTargetVerified = $true; processExitCode = 0; processExitCodeObserved = $true; processExited = $true; forced = $false }
            [ordered]@{ backend = 'redis'; command = 'SHUTDOWN SAVE'; commandExitCode = 0; processId = 3002; processExitCode = 0; processExitCodeObserved = $true; processExited = $true; forced = $false }
        )
        cleanupFailures = @()
        failure = $null
        logs = [ordered]@{ 'logs\platform-candidate-startup-failure.txt' = [ordered]@{ bytes = [long]$logInfo.Length; sha256 = $logHash } }
        sourcePins = [ordered]@{
            runnerSha256 = & $hash $runnerPath
            validatorSha256 = & $hash $validatorPath
            validatorTestSha256 = & $hash $testPath
            sharedValidatorSha256 = & $hash $sharedValidatorPath
            sharedValidatorTestSha256 = & $hash $sharedTestPath
            acceptanceJarSha256 = ('b' * 64)
            apiConsumerJarSha256 = ('c' * 64)
        }
    }

    if (-not (Test-T21NewerAuditSchemaStartupFailureReport $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum)) {
        throw 'The complete bounded newer-audit-schema failure report was rejected.'
    }
    Write-Output 'PASS: accept a complete minimum-Paper PostgreSQL startup rollback report'

    $sqlShutdown = @($report.backendShutdowns | Where-Object { $_.backend -ceq 'postgresql' })
    if ($sqlShutdown.Count -ne 1) { throw 'The fixture must contain exactly one PostgreSQL shutdown observation.' }
    $sqlShutdown[0].shutdownTargetVerified = $false
    if (Test-T21NewerAuditSchemaStartupFailureReport $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted a PostgreSQL shutdown whose target was not verified.'
    }
    $sqlShutdown[0].shutdownTargetVerified = $true
    Write-Output 'PASS: reject an unverified PostgreSQL shutdown target'

    foreach ($target in @(
            [ordered]@{ databaseProduct = 'MySQL'; endpoint = 'Minimum' }
            [ordered]@{ databaseProduct = 'MariaDB'; endpoint = 'Minimum' }
            [ordered]@{ databaseProduct = 'PostgreSQL'; endpoint = 'Maximum' }
            [ordered]@{ databaseProduct = 'PostgreSQL'; endpoint = 'MinimumFolia' }
            [ordered]@{ databaseProduct = 'PostgreSQL'; endpoint = 'MinimumCanvas' }
        )) {
        if (Test-T21NewerAuditSchemaStartupFailureTarget -DatabaseProduct $target.databaseProduct -PlatformEndpoint $target.endpoint) {
            throw "The startup-failure target guard accepted $($target.databaseProduct) / $($target.endpoint)."
        }
    }
    if (-not (Test-T21NewerAuditSchemaStartupFailureTarget -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum)) {
        throw 'The startup-failure target guard rejected the required PostgreSQL / minimum-Paper cell.'
    }
    Write-Output 'PASS: restrict the startup-failure case to PostgreSQL and minimum Paper'

    foreach ($property in @('auditVersionRowJson', 'auditColumnsJson', 'auditIndexesJson', 'auditRowsJson')) {
        $beforeValue = $report.schemaStateBefore[$property]
        $report.schemaStateAfter[$property] = $beforeValue + ' '
        if (Test-T21NewerAuditSchemaStartupFailureReport $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
            throw "The report accepted changed audit preservation evidence for $property."
        }
        $report.schemaStateAfter[$property] = $beforeValue
    }
    Write-Output 'PASS: reject changes to the audit version row, columns, indexes, or sentinel data'

    $report.server.build = 49
    if (Test-T21NewerAuditSchemaStartupFailureReport $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted a server build outside the pinned endpoint.'
    }
    $report.server.build = 48
    Write-Output 'PASS: reject a mismatched server build'

    $report.schemaStateAfter.auditVersion = 11
    if (Test-T21NewerAuditSchemaStartupFailureReport $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted a changed newer schema version.'
    }
    $report.schemaStateAfter.auditVersion = 12
    Write-Output 'PASS: reject mutation of the newer audit schema version'

    $originalColumnsJson = $report.schemaStateBefore.auditColumnsJson
    $incompleteColumns = @(ConvertFrom-Json -InputObject $originalColumnsJson -AsHashtable -ErrorAction Stop)
    $null = $incompleteColumns[0].Remove('identity_generation')
    $incompleteColumnsJson = ConvertTo-Json -InputObject ([object[]]$incompleteColumns) -Depth 10 -Compress
    $report.schemaStateBefore.auditColumnsJson = $incompleteColumnsJson
    $report.schemaStateAfter.auditColumnsJson = $incompleteColumnsJson
    if (Test-T21NewerAuditSchemaStartupFailureReport $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted an audit-column snapshot missing a PostgreSQL information_schema.columns field.'
    }
    $report.schemaStateBefore.auditColumnsJson = $originalColumnsJson
    $report.schemaStateAfter.auditColumnsJson = $originalColumnsJson
    Write-Output 'PASS: require every PostgreSQL information_schema.columns metadata field'

    $originalPlatformLog = [IO.File]::ReadAllLines($platformLogPath)
    $warnPoolShutdown = @($originalPlatformLog | ForEach-Object { $_ -replace '/INFO\]: \[noietime\.libs\.hikari\.HikariDataSource\]', '/WARN]: [noietime.libs.hikari.HikariDataSource]' })
    [IO.File]::WriteAllLines($platformLogPath, [string[]]$warnPoolShutdown, [Text.UTF8Encoding]::new($false))
    $updatedLogInfo = Get-Item -LiteralPath $platformLogPath
    $updatedLogHash = (Get-FileHash -LiteralPath $platformLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $report.platformLogs[0].bytes = [long]$updatedLogInfo.Length
    $report.platformLogs[0].sha256 = $updatedLogHash
    $report.logs['logs\platform-candidate-startup-failure.txt'].bytes = [long]$updatedLogInfo.Length
    $report.logs['logs\platform-candidate-startup-failure.txt'].sha256 = $updatedLogHash
    if (Test-T21NewerAuditSchemaStartupFailureReport $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted a WARN-level Hikari shutdown marker in place of INFO.'
    }
    [IO.File]::WriteAllLines($platformLogPath, [string[]]$originalPlatformLog, [Text.UTF8Encoding]::new($false))
    $logInfo = Get-Item -LiteralPath $platformLogPath
    $logHash = (Get-FileHash -LiteralPath $platformLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $report.platformLogs[0].bytes = [long]$logInfo.Length
    $report.platformLogs[0].sha256 = $logHash
    $report.logs['logs\platform-candidate-startup-failure.txt'].bytes = [long]$logInfo.Length
    $report.logs['logs\platform-candidate-startup-failure.txt'].sha256 = $logHash
    Write-Output 'PASS: reject severity-only substitution of the INFO-level SQL pool shutdown marker'

    $withoutPoolShutdown = @($originalPlatformLog | Where-Object { $_ -notmatch 'Syncmoney-HikariCP - Shutdown completed\.' })
    [IO.File]::WriteAllLines($platformLogPath, [string[]]$withoutPoolShutdown, [Text.UTF8Encoding]::new($false))
    $updatedLogInfo = Get-Item -LiteralPath $platformLogPath
    $updatedLogHash = (Get-FileHash -LiteralPath $platformLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $report.platformLogs[0].bytes = [long]$updatedLogInfo.Length
    $report.platformLogs[0].sha256 = $updatedLogHash
    $report.logs['logs\platform-candidate-startup-failure.txt'].bytes = [long]$updatedLogInfo.Length
    $report.logs['logs\platform-candidate-startup-failure.txt'].sha256 = $updatedLogHash
    if (Test-T21NewerAuditSchemaStartupFailureReport $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted missing SQL connection-pool shutdown evidence.'
    }
    [IO.File]::WriteAllLines($platformLogPath, [string[]]$originalPlatformLog, [Text.UTF8Encoding]::new($false))
    $report.platformLogs[0].bytes = [long]$logInfo.Length
    $report.platformLogs[0].sha256 = $logHash
    $report.logs['logs\platform-candidate-startup-failure.txt'].bytes = [long]$logInfo.Length
    $report.logs['logs\platform-candidate-startup-failure.txt'].sha256 = $logHash
    Write-Output 'PASS: require the observed INFO-level SQL pool shutdown marker'

    $report.resourceCleanup.sqlOtherConnections = 1
    if (Test-T21NewerAuditSchemaStartupFailureReport $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted an open SQL connection after rollback.'
    }
    $report.resourceCleanup.sqlOtherConnections = 0
    Write-Output 'PASS: reject an open SQL connection after rollback'

    $report.resourceCleanup.redisOtherClients = 1
    if (Test-T21NewerAuditSchemaStartupFailureReport $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted an open Redis client after rollback.'
    }
    $report.resourceCleanup.redisOtherClients = 0
    Write-Output 'PASS: reject an open Redis client after rollback'

    $report.startupObservation.successMarkerObserved = $true
    if (Test-T21NewerAuditSchemaStartupFailureReport $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted successful plugin startup alongside the expected failure.'
    }
    $report.startupObservation.successMarkerObserved = $false
    Write-Output 'PASS: reject contradictory startup success evidence'

    $report.normalStops[0].processExitCode = 1
    if (Test-T21NewerAuditSchemaStartupFailureReport $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted a non-zero platform exit.'
    }
    $report.normalStops[0].processExitCode = 0
    Write-Output 'PASS: reject a non-zero platform exit'

    $report.cleanupFailures = @('unverified cleanup')
    if (Test-T21NewerAuditSchemaStartupFailureReport $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted a cleanup failure.'
    }
    Write-Output 'PASS: reject cleanup failures'

    $runnerSource = Get-Content -LiteralPath $runnerPath -Raw
    if ($runnerSource -notmatch '\[switch\]\$NewerAuditSchemaStartupFailure' -or
        $runnerSource -notmatch 'Test-T21NewerAuditSchemaStartupFailureTarget' -or
        $runnerSource -notmatch 'function Seed-T21NewerAuditSchema' -or
        $runnerSource -notmatch 'function Start-T21ServerExpectingNewerAuditSchemaFailure' -or
        $runnerSource -notmatch 'Test-T21NewerAuditSchemaStartupFailureReport' -or
        $runnerSource -notmatch '(?s)SELECT \*\s+FROM information_schema\.columns') {
        throw 'The SQL runtime runner does not implement the bounded newer-audit-schema startup-failure mode.'
    }
    $seedIndex = $runnerSource.IndexOf('$script:schemaStateBefore = Seed-T21NewerAuditSchema', [StringComparison]::Ordinal)
    $startupFailureIndex = $runnerSource.IndexOf('$startup = Start-T21ServerExpectingNewerAuditSchemaFailure', [StringComparison]::Ordinal)
    if ($seedIndex -lt 0 -or $startupFailureIndex -le $seedIndex) {
        throw 'The runner must seed the newer audit schema before starting the candidate plugin.'
    }
    $targetGuardIndex = $runnerSource.IndexOf('Test-T21NewerAuditSchemaStartupFailureTarget', [StringComparison]::Ordinal)
    $runtimePathResolutionIndex = $runnerSource.IndexOf('$script:serverPath = (Resolve-Path -LiteralPath $ServerJar).Path', [StringComparison]::Ordinal)
    if ($targetGuardIndex -lt 0 -or $runtimePathResolutionIndex -le $targetGuardIndex) {
        throw 'The startup-failure target guard must run before resolving runtime paths or creating resources.'
    }
    Write-Output 'PASS: seed the newer schema before the expected-failure Paper start and validate its report'

    Write-Output 'T21 newer-audit-schema failure validator tests PASS'
} finally {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]@('\', '/'))
    $testPath = [IO.Path]::GetFullPath($testRoot).TrimEnd([char[]]@('\', '/'))
    if (-not $testPath.StartsWith(($tempRoot + [IO.Path]::DirectorySeparatorChar), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to remove a startup-failure fixture outside the temporary directory.'
    }
    if (Test-Path -LiteralPath $testPath -PathType Container) {
        $fixture = Get-Item -LiteralPath $testPath -Force
        if (($fixture.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            Remove-Item -LiteralPath $testPath -Force
        } else {
            Remove-Item -LiteralPath $testPath -Recurse -Force
        }
    }
}
