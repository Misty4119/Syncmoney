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
        '[07:44:02] [Server thread/INFO]: [Syncmoney] Disabling Syncmoney v1.4.0-alpha.1'
    )
    [IO.File]::WriteAllLines($platformLogPath, [string[]]$platformLog, [Text.UTF8Encoding]::new($false))
    $logInfo = Get-Item -LiteralPath $platformLogPath
    $logHash = (Get-FileHash -LiteralPath $platformLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $mysqlPingLogPath = Join-Path $testRoot 'logs\mysql-ping.txt'
    [IO.File]::WriteAllText($mysqlPingLogPath, "mysqld is alive`n", [Text.UTF8Encoding]::new($false))
    $mysqlPingLogInfo = Get-Item -LiteralPath $mysqlPingLogPath
    $mysqlPingLogHash = (Get-FileHash -LiteralPath $mysqlPingLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $gradleStopLogPath = Join-Path $testRoot 'logs\gradle-daemon-stop.txt'
    [IO.File]::WriteAllText($gradleStopLogPath, "No Gradle daemons are running.`n", [Text.UTF8Encoding]::new($false))
    $gradleStopLogInfo = Get-Item -LiteralPath $gradleStopLogPath
    $gradleStopLogHash = (Get-FileHash -LiteralPath $gradleStopLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $endpoint = Get-T21PlatformEndpoint -PlatformEndpoint Minimum
    $runnerPath = Join-Path $PSScriptRoot 'run-mysql-sync-runtime.ps1'
    $sharedValidatorPath = Join-Path $PSScriptRoot 't21-upgrade-validation.ps1'
    $sharedTestPath = Join-Path $PSScriptRoot 't21-upgrade-validation.test.ps1'
    $validatorPath = Join-Path $PSScriptRoot 't21-startup-failure-validation.ps1'
    $testPath = $PSCommandPath
    $expectedPlugDevRoot = [IO.Path]::GetFullPath('C:\PlugDevFixture')
    $expectedPlugDevSourceCommit = 'd' * 40
    $expectedPlugDevCliSha256 = 'e' * 64
    $expectedPlugDevCliDistManifestFiles = @(
        [ordered]@{ path='cli.js'; bytes=1; sha256=$expectedPlugDevCliSha256 }
        [ordered]@{ path='chunk-a.js'; bytes=2; sha256=('f' * 64) }
    )
    $expectedPlugDevCliDistManifestSha256 = Get-T21PlugDevCliDistManifestHash $expectedPlugDevCliDistManifestFiles
    $fixturePlugDevRoot = Join-Path $testRoot 'plugdev'
    $fixturePlugDevDist = Join-Path $fixturePlugDevRoot 'packages\cli\dist'
    New-Item -ItemType Directory -Force -Path (Join-Path $fixturePlugDevDist 'chunks') | Out-Null
    [IO.File]::WriteAllText((Join-Path $fixturePlugDevDist 'cli.js'), 'import "./chunks/runtime.js";', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $fixturePlugDevDist 'chunks\runtime.js'), 'export const runtime = true;', [Text.UTF8Encoding]::new($false))
    $filesystemManifestBefore = Get-T21PlugDevCliDistManifest $fixturePlugDevRoot
    if ($filesystemManifestBefore.files.Count -ne 2 -or
        @($filesystemManifestBefore.files | Where-Object { $_.path -ceq 'chunks/runtime.js' }).Count -ne 1 -or
        -not (Test-T21PlugDevCliDistManifest $filesystemManifestBefore $filesystemManifestBefore.sha256 ((@($filesystemManifestBefore.files | Where-Object { $_.path -ceq 'cli.js' })[0]).sha256))) {
        throw 'The PlugDev CLI manifest did not cover both the entrypoint and its imported runtime chunk.'
    }
    [IO.File]::WriteAllText((Join-Path $fixturePlugDevDist 'chunks\runtime.js'), 'export const runtime = false;', [Text.UTF8Encoding]::new($false))
    $filesystemManifestAfter = Get-T21PlugDevCliDistManifest $fixturePlugDevRoot
    if ($filesystemManifestAfter.sha256 -ceq $filesystemManifestBefore.sha256) {
        throw 'Changing an imported PlugDev CLI runtime chunk did not change the complete bundle manifest.'
    }
    Write-Output 'PASS: deterministically fingerprint the CLI entrypoint and imported runtime chunks'
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
        plugDev = [ordered]@{
            root = $expectedPlugDevRoot
            sourceCommit = ('d' * 40)
            cliSha256 = ('e' * 64)
            cliDistManifest = [ordered]@{ directory='packages/cli/dist'; files=$expectedPlugDevCliDistManifestFiles; sha256=$expectedPlugDevCliDistManifestSha256 }
            cliDistManifestSha256Before = $expectedPlugDevCliDistManifestSha256
            cliDistManifestSha256After = $expectedPlugDevCliDistManifestSha256
            cliDistInvocationObservations = @([ordered]@{ label='fixture'; beforeSha256=$expectedPlugDevCliDistManifestSha256; afterSha256=$expectedPlugDevCliDistManifestSha256 })
        }
        configuration = [ordered]@{
            mode = 'sync'; databaseEnabled = $true; databaseType = 'postgresql'; databaseIsolated = $true
            redisEnabled = $true; redisDatabase = 15; redisIsolated = $true
        }
        sqlBackend = [ordered]@{ product = 'PostgreSQL'; version = '18.1' }
        backendProcesses = [ordered]@{
            postgresql = [ordered]@{ product = 'PostgreSQL'; version = '18.1'; processId = 3001; runOwned = $true; listenerOwnerVerified = $true }
            redis = [ordered]@{ version = '8.10.1'; processId = 3002; runOwned = $true; listenerOwnerVerified = $true }
        }
        gradleDaemonCleanup = [ordered]@{
            isolatedUserHome = [IO.Path]::GetFullPath((Join-Path $testRoot '.gradle-user-home'))
            processIdsBeforeStop = @(5001); stopCommandExitCode = 0; stopLogPath = 'logs\gradle-daemon-stop.txt'
            processIdsAfterStop = @(); processExitVerified = $true
        }
        startupObservation = [ordered]@{
            stage = 'candidate-startup-failure'; pluginVersion = '1.4.0-alpha.1'; artifactSha256 = ('a' * 64)
            enabled = $false; processId = 4001; runtimeIdentity = [ordered]@{ software = 'paper'; runtimeVersion = '1.21.6'; minecraftVersion = '1.21.6'; build = 48 }
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
        logs = [ordered]@{
            'logs\platform-candidate-startup-failure.txt' = [ordered]@{ bytes = [long]$logInfo.Length; sha256 = $logHash }
            'logs\mysql-ping.txt' = [ordered]@{ bytes = [long]$mysqlPingLogInfo.Length; sha256 = $mysqlPingLogHash }
            'logs\gradle-daemon-stop.txt' = [ordered]@{ bytes = [long]$gradleStopLogInfo.Length; sha256 = $gradleStopLogHash }
        }
        sourcePins = [ordered]@{
            runnerSha256 = & $hash $runnerPath
            validatorSha256 = & $hash $validatorPath
            validatorTestSha256 = & $hash $testPath
            sharedValidatorSha256 = & $hash $sharedValidatorPath
            sharedValidatorTestSha256 = & $hash $sharedTestPath
            plugDevCliDistManifestSha256 = $expectedPlugDevCliDistManifestSha256
            acceptanceJarSha256 = ('b' * 64)
            apiConsumerJarSha256 = ('c' * 64)
        }
    }

    function Test-T21StartupFailureReportFixture(
        [object]$candidateReport,
        [string]$expectedServerSha,
        [string]$expectedCandidateSha,
        [string]$expectedAcceptanceSha,
        [string]$expectedApiConsumerSha,
        [ValidateSet('MySQL', 'MariaDB', 'PostgreSQL')][string]$DatabaseProduct = 'PostgreSQL',
        [ValidateSet('Minimum', 'Maximum', 'CurrentMaximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'CurrentMaximumFolia', 'MinimumCanvas', 'MaximumCanvas', 'CurrentMaximumCanvas')][string]$PlatformEndpoint = 'Minimum',
        [ValidateSet('NewerAuditSchema', 'IncompleteV10AuditTable', 'IncompleteV10AuditTablePlayerRowPreservation', 'RequiredV11DdlPermissionDenied')][string]$FailureScenario = 'NewerAuditSchema'
    ) {
        return Test-T21NewerAuditSchemaStartupFailureReport $candidateReport $expectedServerSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha `
            -ExpectedPlugDevRoot $expectedPlugDevRoot -ExpectedPlugDevSourceCommit $expectedPlugDevSourceCommit -ExpectedPlugDevCliSha256 $expectedPlugDevCliSha256 `
            -ExpectedPlugDevCliDistManifestSha256 $expectedPlugDevCliDistManifestSha256 `
            -DatabaseProduct $DatabaseProduct -PlatformEndpoint $PlatformEndpoint -FailureScenario $FailureScenario
    }

    if (-not (Test-T21StartupFailureReportFixture $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum)) {
        throw 'The complete bounded newer-audit-schema failure report was rejected.'
    }
    Write-Output 'PASS: accept startup rollback when Bukkit emits its disable marker after pool cleanup'

    $withoutDisableRequest = @($platformLog | Where-Object { $_ -notmatch 'Disabling Syncmoney v1\.4\.0-alpha\.1' })
    [IO.File]::WriteAllLines($platformLogPath, [string[]]$withoutDisableRequest, [Text.UTF8Encoding]::new($false))
    $updatedLogInfo = Get-Item -LiteralPath $platformLogPath
    $updatedLogHash = (Get-FileHash -LiteralPath $platformLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $report.platformLogs[0].bytes = [long]$updatedLogInfo.Length
    $report.platformLogs[0].sha256 = $updatedLogHash
    $report.logs['logs\platform-candidate-startup-failure.txt'].bytes = [long]$updatedLogInfo.Length
    $report.logs['logs\platform-candidate-startup-failure.txt'].sha256 = $updatedLogHash
    if (Test-T21StartupFailureReportFixture $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted startup rollback without Bukkit plugin-disable evidence.'
    }
    [IO.File]::WriteAllLines($platformLogPath, [string[]]$platformLog, [Text.UTF8Encoding]::new($false))
    $logInfo = Get-Item -LiteralPath $platformLogPath
    $logHash = (Get-FileHash -LiteralPath $platformLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $report.platformLogs[0].bytes = [long]$logInfo.Length
    $report.platformLogs[0].sha256 = $logHash
    $report.logs['logs\platform-candidate-startup-failure.txt'].bytes = [long]$logInfo.Length
    $report.logs['logs\platform-candidate-startup-failure.txt'].sha256 = $logHash
    Write-Output 'PASS: reject startup rollback without Bukkit plugin-disable evidence'

    $incompleteReport = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $report -Depth 30 -Compress) -AsHashtable -ErrorAction Stop
    $incompleteReport.task = 'T21-V03-SYNC-incomplete-v10-audit-table-startup-failure'
    $incompleteReport.schemaStateBefore.mainVersion = 1
    $incompleteReport.schemaStateBefore.mainVersionRowJson = '{"id":"1","version":1,"applied_at":"2026-10-14T00:00:00","description":"T21 pre-existing main schema"}'
    $incompleteReport.schemaStateBefore.auditVersion = 9
    $incompleteReport.schemaStateBefore.playersTableExists = $false
    $incompleteReport.schemaStateBefore.playerRows = $null
    $incompleteReport.schemaStateBefore.auditVersionRowJson = '{"id":"audit","version":9,"applied_at":"2026-10-14T00:00:00","description":"T21 incomplete audit v9 sentinel"}'
    $incompleteReport.schemaStateAfter.mainVersion = 1
    $incompleteReport.schemaStateAfter.mainVersionRowJson = $incompleteReport.schemaStateBefore.mainVersionRowJson
    $incompleteReport.schemaStateAfter.auditVersion = 9
    $incompleteReport.schemaStateAfter.playersTableExists = $true
    $incompleteReport.schemaStateAfter.playerRows = 0
    $incompleteReport.schemaStateAfter.auditVersionRowJson = $incompleteReport.schemaStateBefore.auditVersionRowJson
    foreach ($schemaState in @($incompleteReport.schemaStateBefore, $incompleteReport.schemaStateAfter)) {
        $idColumn = ConvertFrom-Json -InputObject ([string]$schemaState.auditColumnsJson) -AsHashtable -ErrorAction Stop | Select-Object -First 1
        $idColumn.table_schema = 'public'
        $idColumn.table_name = 'syncmoney_audit_log'
        $idColumn.column_name = 'id'
        $idColumn.ordinal_position = 1
        $idColumn.data_type = 'character varying'
        $idColumn.character_maximum_length = 36
        $idColumn.character_octet_length = 144
        $idColumn.is_nullable = 'NO'
        $idColumn.udt_schema = 'pg_catalog'
        $idColumn.udt_name = 'varchar'
        $idColumn.is_identity = 'NO'
        $idColumn.is_generated = 'NEVER'
        $idColumn.is_updatable = 'YES'
        $schemaState.auditColumnsJson = ConvertTo-Json -InputObject @($idColumn) -Depth 10 -Compress
        $schemaState.auditIndexesJson = '[{"name":"syncmoney_audit_log_pkey","definition":"CREATE UNIQUE INDEX syncmoney_audit_log_pkey ON public.syncmoney_audit_log USING btree (id)"}]'
        $schemaState.auditRowsJson = '[{"id":"00000000-0000-0000-0000-000000000329"}]'
    }
    $incompleteReport.startupObservation.expectedFailure = 'incomplete-v10-audit-table'
    $incompleteReport.startupObservation.failureMessage = 'Failed audit schema migration to version 10; version metadata was not advanced past 9'
    $incompleteReport.startupObservation.failureDetailMarker = 'Required v10 audit columns are missing:'
    $incompleteLog = @(
        '[07:43:59] [Server thread/INFO]: [Bootstrap] Loading Paper 1.21.6-48-main@4d854e6 for Minecraft 1.21.6'
        '[07:44:00] [Server thread/INFO]: Loading server plugin Syncmoney v1.4.0-alpha.1'
        '[07:44:00] [Server thread/INFO]: [Syncmoney] Syncmoney audit schema version: current 9, target 11'
        '[07:44:00] [Server thread/SEVERE]: [Syncmoney] Syncmoney initialization failed; rolling back initialized owners'
        'java.lang.IllegalStateException: Failed audit schema migration to version 10; version metadata was not advanced past 9'
        'Caused by: java.sql.SQLException: Required v10 audit columns are missing: [timestamp, type, player_uuid]'
        '[07:44:01] [Syncmoney-DbWriter/INFO]: [noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Shutdown initiated...'
        '[07:44:01] [Syncmoney-DbWriter/INFO]: [noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Shutdown completed.'
        '[07:44:02] [Server thread/INFO]: [Syncmoney] Disabling Syncmoney v1.4.0-alpha.1'
    )
    [IO.File]::WriteAllLines($platformLogPath, [string[]]$incompleteLog, [Text.UTF8Encoding]::new($false))
    $incompleteLogInfo = Get-Item -LiteralPath $platformLogPath
    $incompleteLogHash = (Get-FileHash -LiteralPath $platformLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $incompleteReport.platformLogs[0].bytes = [long]$incompleteLogInfo.Length
    $incompleteReport.platformLogs[0].sha256 = $incompleteLogHash
    $incompleteReport.logs['logs\platform-candidate-startup-failure.txt'].bytes = [long]$incompleteLogInfo.Length
    $incompleteReport.logs['logs\platform-candidate-startup-failure.txt'].sha256 = $incompleteLogHash
    if (-not (Test-T21StartupFailureReportFixture $incompleteReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTable)) {
        throw 'The complete incomplete-v10-audit-table startup-failure report was rejected.'
    }
    Write-Output 'PASS: accept bounded v9 incomplete-audit-table failure with preserved schema and data'

    $incompleteReport.schemaStateAfter.auditVersion = 10
    if (Test-T21StartupFailureReportFixture $incompleteReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTable) {
        throw 'The incomplete audit table report accepted an audit schema version advanced to 10.'
    }
    $incompleteReport.schemaStateAfter.auditVersion = 9
    Write-Output 'PASS: reject an incomplete-audit-table report that advances the audit version'

    $incompleteReport.startupObservation.failureDetailMarker = 'Audit schema version 12'
    if (Test-T21StartupFailureReportFixture $incompleteReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTable) {
        throw 'The incomplete audit table report accepted the wrong startup failure cause.'
    }
    $incompleteReport.startupObservation.failureDetailMarker = 'Required v10 audit columns are missing:'
    Write-Output 'PASS: reject a v9 report without the incomplete-v10-column failure cause'

    [IO.File]::WriteAllLines($platformLogPath, [string[]]$platformLog, [Text.UTF8Encoding]::new($false))
    $logInfo = Get-Item -LiteralPath $platformLogPath
    $logHash = (Get-FileHash -LiteralPath $platformLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $report.platformLogs[0].bytes = [long]$logInfo.Length
    $report.platformLogs[0].sha256 = $logHash
    $report.logs['logs\platform-candidate-startup-failure.txt'].bytes = [long]$logInfo.Length
    $report.logs['logs\platform-candidate-startup-failure.txt'].sha256 = $logHash

    foreach ($endpointName in @('Minimum', 'Maximum', 'CurrentMaximum', 'MinimumFolia', 'MaximumFolia', 'CurrentMaximumFolia', 'MinimumCanvas', 'MaximumCanvas', 'CurrentMaximumCanvas', 'CurrentStable')) {
        $expectedEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint $endpointName
        $endpointReport = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $report -Depth 30 -Compress) -AsHashtable -ErrorAction Stop
        $endpointSoftwareName = switch ($expectedEndpoint.software) {
            'paper' { 'Paper' }
            'folia' { 'Folia' }
            'canvas' { 'Canvas' }
            default { throw "Unsupported fixture platform software '$($expectedEndpoint.software)'." }
        }
        $endpointLog = [string[]]$platformLog.Clone()
        $endpointLog[0] = "[07:43:59] [Server thread/INFO]: [Bootstrap] Loading $endpointSoftwareName $($expectedEndpoint.minecraftVersion)-$($expectedEndpoint.build)-main@fixture for Minecraft $($expectedEndpoint.minecraftVersion)"
        [IO.File]::WriteAllLines($platformLogPath, $endpointLog, [Text.UTF8Encoding]::new($false))
        $endpointLogInfo = Get-Item -LiteralPath $platformLogPath
        $endpointLogHash = (Get-FileHash -LiteralPath $platformLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $endpointReport.server.software = $expectedEndpoint.software
        $endpointReport.server.minecraftVersion = $expectedEndpoint.minecraftVersion
        $endpointReport.server.build = $expectedEndpoint.build
        $endpointReport.server.sha256 = $expectedEndpoint.sha256
        $endpointReport.startupObservation.runtimeIdentity.software = $expectedEndpoint.software
        $endpointReport.startupObservation.runtimeIdentity.runtimeVersion = $expectedEndpoint.minecraftVersion
        $endpointReport.startupObservation.runtimeIdentity.minecraftVersion = $expectedEndpoint.minecraftVersion
        $endpointReport.startupObservation.runtimeIdentity.build = $expectedEndpoint.build
        $endpointReport.platformLogs[0].bytes = [long]$endpointLogInfo.Length
        $endpointReport.platformLogs[0].sha256 = $endpointLogHash
        $endpointReport.logs['logs\platform-candidate-startup-failure.txt'].bytes = [long]$endpointLogInfo.Length
        $endpointReport.logs['logs\platform-candidate-startup-failure.txt'].sha256 = $endpointLogHash
        if (-not (Test-T21StartupFailureReportFixture $endpointReport $expectedEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint $endpointName)) {
            throw "The complete $endpointName PostgreSQL startup rollback report was rejected."
        }
        Write-Output "PASS: accept a complete $endpointName PostgreSQL startup rollback report against its pinned server SHA-256"
        $endpointReport.server.sha256 = '0' * 64
        if (Test-T21StartupFailureReportFixture $endpointReport $expectedEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint $endpointName) {
            throw "The $endpointName startup rollback report accepted a server SHA-256 that differs from its pinned endpoint."
        }
        Write-Output "PASS: reject a $endpointName startup rollback report with a mismatched server SHA-256"
    }
    [IO.File]::WriteAllLines($platformLogPath, [string[]]$platformLog, [Text.UTF8Encoding]::new($false))

    $sqlShutdown = @($report.backendShutdowns | Where-Object { $_.backend -ceq 'postgresql' })
    if ($sqlShutdown.Count -ne 1) { throw 'The fixture must contain exactly one PostgreSQL shutdown observation.' }
    $sqlShutdown[0].shutdownTargetVerified = $false
    if (Test-T21StartupFailureReportFixture $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted a PostgreSQL shutdown whose target was not verified.'
    }
    $sqlShutdown[0].shutdownTargetVerified = $true
    Write-Output 'PASS: reject an unverified PostgreSQL shutdown target'

    $startupFailureEndpoints = @('Minimum', 'Maximum', 'CurrentMaximum', 'MinimumFolia', 'MaximumFolia', 'CurrentMaximumFolia', 'MinimumCanvas', 'MaximumCanvas', 'CurrentMaximumCanvas', 'CurrentStable')
    foreach ($endpointName in $startupFailureEndpoints) {
        foreach ($databaseProduct in @('MySQL', 'MariaDB', 'PostgreSQL')) {
            if (-not (Test-T21NewerAuditSchemaStartupFailureTarget -DatabaseProduct $databaseProduct -PlatformEndpoint $endpointName)) {
                throw "The startup-failure target guard rejected $databaseProduct / $endpointName."
            }
        }
    }
    $unsupportedEndpointRejected = $false
    try {
        [void](Test-T21NewerAuditSchemaStartupFailureTarget -DatabaseProduct PostgreSQL -PlatformEndpoint 'Unpinned')
    } catch {
        $unsupportedEndpointRejected = $true
    }
    if (-not $unsupportedEndpointRejected) { throw 'The startup-failure target guard accepted an endpoint outside the pinned platform set.' }
    Write-Output 'PASS: allow every pinned platform endpoint for all three SQL dialects and reject an unpinned endpoint'

    foreach ($endpointName in @('Minimum', 'Maximum', 'CurrentMaximum', 'MinimumFolia', 'MaximumFolia', 'CurrentMaximumFolia', 'MinimumCanvas', 'MaximumCanvas', 'CurrentMaximumCanvas', 'CurrentStable')) {
        foreach ($databaseProduct in @('MySQL', 'MariaDB', 'PostgreSQL')) {
            $shouldAccept = if ($endpointName -in @('MaximumFolia', 'CurrentMaximumFolia')) {
                $true
            } elseif ($endpointName -ceq 'MinimumCanvas') {
                $true
            } elseif ($endpointName -in @('MaximumCanvas', 'CurrentMaximumCanvas')) {
                $true
            } elseif ($endpointName -ceq 'CurrentStable') {
                $true
            } else {
                $endpointName -in @('Minimum', 'Maximum', 'CurrentMaximum', 'MinimumFolia')
            }
            $accepted = Test-T21IncompleteAuditTableStartupFailureTarget -DatabaseProduct $databaseProduct -PlatformEndpoint $endpointName
            if ($accepted -ne $shouldAccept) {
                throw "The incomplete-v10-audit-table target guard returned $accepted for $databaseProduct / $endpointName; expected $shouldAccept."
            }
        }
    }
    Write-Output 'PASS: allow incomplete-v10 targets on pinned Paper, Folia, and Canvas endpoints plus current-stable Paper across all SQL dialects'

    $unsupportedIncompleteV10EndpointRejected = $false
    try {
        [void](Test-T21IncompleteAuditTableStartupFailureTarget -DatabaseProduct PostgreSQL -PlatformEndpoint 'Unpinned')
    } catch {
        $unsupportedIncompleteV10EndpointRejected = $true
    }
    if (-not $unsupportedIncompleteV10EndpointRejected) { throw 'The incomplete-v10 startup-failure target guard accepted an endpoint outside the pinned platform set.' }
    Write-Output 'PASS: reject an unpinned endpoint for the incomplete-v10 startup-failure case'

    foreach ($preservationCase in @(
            @{ database='MySQL'; endpoint='Minimum'; expected=$true }
            @{ database='MySQL'; endpoint='Maximum'; expected=$true }
            @{ database='MySQL'; endpoint='CurrentMaximum'; expected=$true }
            @{ database='MySQL'; endpoint='CurrentStable'; expected=$false }
            @{ database='MySQL'; endpoint='MinimumFolia'; expected=$true }
            @{ database='MySQL'; endpoint='MaximumFolia'; expected=$true }
            @{ database='MySQL'; endpoint='CurrentMaximumFolia'; expected=$true }
            @{ database='MySQL'; endpoint='MinimumCanvas'; expected=$true }
            @{ database='MySQL'; endpoint='MaximumCanvas'; expected=$true }
            @{ database='MySQL'; endpoint='CurrentMaximumCanvas'; expected=$true }
            @{ database='MariaDB'; endpoint='Minimum'; expected=$true }
            @{ database='PostgreSQL'; endpoint='Minimum'; expected=$true }
            @{ database='MariaDB'; endpoint='Maximum'; expected=$true }
            @{ database='MariaDB'; endpoint='CurrentMaximum'; expected=$true }
            @{ database='PostgreSQL'; endpoint='Maximum'; expected=$true }
            @{ database='PostgreSQL'; endpoint='CurrentMaximum'; expected=$true }
            @{ database='MariaDB'; endpoint='MinimumFolia'; expected=$true }
            @{ database='PostgreSQL'; endpoint='MinimumFolia'; expected=$true }
            @{ database='MariaDB'; endpoint='MaximumFolia'; expected=$true }
            @{ database='MariaDB'; endpoint='CurrentMaximumFolia'; expected=$true }
            @{ database='PostgreSQL'; endpoint='MaximumFolia'; expected=$true }
            @{ database='PostgreSQL'; endpoint='CurrentMaximumFolia'; expected=$true }
            @{ database='MariaDB'; endpoint='MinimumCanvas'; expected=$true }
            @{ database='PostgreSQL'; endpoint='MinimumCanvas'; expected=$true }
            @{ database='MariaDB'; endpoint='MaximumCanvas'; expected=$true }
            @{ database='MariaDB'; endpoint='CurrentMaximumCanvas'; expected=$true }
            @{ database='PostgreSQL'; endpoint='MaximumCanvas'; expected=$true }
            @{ database='PostgreSQL'; endpoint='CurrentMaximumCanvas'; expected=$true }
            @{ database='MariaDB'; endpoint='CurrentStable'; expected=$false }
            @{ database='PostgreSQL'; endpoint='CurrentStable'; expected=$false }
        )) {
        $accepted = Test-T21IncompleteAuditTablePlayerRowPreservationTarget -DatabaseProduct $preservationCase.database -PlatformEndpoint $preservationCase.endpoint
        if ($accepted -ne $preservationCase.expected) {
            throw "The existing-player-row preservation target guard returned $accepted for $($preservationCase.database) / $($preservationCase.endpoint); expected $($preservationCase.expected)."
        }
    }
    Write-Output 'PASS: restrict existing-player-row preservation to user-pinned minimum/maximum Paper, Folia, and Canvas across all SQL products'

    $currentStablePathResolutionObserved = $false
    try {
        & $runnerPath -ServerJar 'unused.jar' -ExpectedServerSha256 ('a' * 64) -ExpectedCandidateSha256 ('b' * 64) `
            -PlugDevRoot 'unused' -JavaHome 'unused' -DatabaseProduct PostgreSQL -PlatformEndpoint CurrentStable `
            -IncompleteV10AuditTableStartupFailure
    } catch {
        $currentStablePathResolutionObserved = $_.Exception.Message -match 'unused\.jar'
    }
    if (-not $currentStablePathResolutionObserved) {
        throw 'The current-stable Paper incomplete-v10 target was rejected before resolving the intentionally missing server JAR.'
    }
    Write-Output 'PASS: accept the current-stable Paper incomplete-v10 target before external resources are resolved'

    $playerRowPreservationPathResolutionObserved = $false
    try {
        & $runnerPath -ServerJar 'unused.jar' -ExpectedServerSha256 ('a' * 64) -ExpectedCandidateSha256 ('b' * 64) `
            -PlugDevRoot 'unused' -JavaHome 'unused' -DatabaseProduct MySQL -PlatformEndpoint Minimum `
            -IncompleteV10AuditTableStartupFailure -IncompleteV10AuditTablePlayerRowPreservation
    } catch {
        $playerRowPreservationPathResolutionObserved = $_.Exception.Message -match 'unused\.jar'
    }
    if (-not $playerRowPreservationPathResolutionObserved) {
        throw 'The minimum-Paper MySQL player-row-preservation target did not pass its guard before external paths were resolved.'
    }
    Write-Output 'PASS: accept the minimum-Paper MySQL player-row-preservation runner target before external resources are resolved'

    $maximumPlayerRowPreservationPathResolutionObserved = $false
    try {
        & $runnerPath -ServerJar 'unused.jar' -ExpectedServerSha256 ('a' * 64) -ExpectedCandidateSha256 ('b' * 64) `
            -PlugDevRoot 'unused' -JavaHome 'unused' -DatabaseProduct MySQL -PlatformEndpoint Maximum `
            -IncompleteV10AuditTableStartupFailure -IncompleteV10AuditTablePlayerRowPreservation
    } catch {
        $maximumPlayerRowPreservationPathResolutionObserved = $_.Exception.Message -match 'unused\.jar'
    }
    if (-not $maximumPlayerRowPreservationPathResolutionObserved) {
        throw 'The maximum-Paper MySQL player-row-preservation target did not pass its guard before external paths were resolved.'
    }
    Write-Output 'PASS: accept the maximum-Paper MySQL player-row-preservation runner target before external resources are resolved'

    $minimumFoliaPlayerRowPreservationPathResolutionObserved = $false
    try {
        & $runnerPath -ServerJar 'unused.jar' -ExpectedServerSha256 ('a' * 64) -ExpectedCandidateSha256 ('b' * 64) `
            -PlugDevRoot 'unused' -JavaHome 'unused' -DatabaseProduct MySQL -PlatformEndpoint MinimumFolia `
            -IncompleteV10AuditTableStartupFailure -IncompleteV10AuditTablePlayerRowPreservation
    } catch {
        $minimumFoliaPlayerRowPreservationPathResolutionObserved = $_.Exception.Message -match 'unused\.jar'
    }
    if (-not $minimumFoliaPlayerRowPreservationPathResolutionObserved) {
        throw 'The minimum-Folia MySQL player-row-preservation target did not pass its guard before external paths were resolved.'
    }
    Write-Output 'PASS: accept the minimum-Folia MySQL player-row-preservation runner target before external resources are resolved'

    $maximumFoliaPlayerRowPreservationPathResolutionObserved = $false
    try {
        & $runnerPath -ServerJar 'unused.jar' -ExpectedServerSha256 ('a' * 64) -ExpectedCandidateSha256 ('b' * 64) `
            -PlugDevRoot 'unused' -JavaHome 'unused' -DatabaseProduct MySQL -PlatformEndpoint MaximumFolia `
            -IncompleteV10AuditTableStartupFailure -IncompleteV10AuditTablePlayerRowPreservation
    } catch {
        $maximumFoliaPlayerRowPreservationPathResolutionObserved = $_.Exception.Message -match 'unused\.jar'
    }
    if (-not $maximumFoliaPlayerRowPreservationPathResolutionObserved) {
        throw 'The maximum-Folia MySQL player-row-preservation target did not pass its guard before external paths were resolved.'
    }
    Write-Output 'PASS: accept the maximum-Folia MySQL player-row-preservation runner target before external resources are resolved'

    $minimumCanvasPlayerRowPreservationPathResolutionObserved = $false
    try {
        & $runnerPath -ServerJar 'unused.jar' -ExpectedServerSha256 ('a' * 64) -ExpectedCandidateSha256 ('b' * 64) `
            -PlugDevRoot 'unused' -JavaHome 'unused' -DatabaseProduct MySQL -PlatformEndpoint MinimumCanvas `
            -IncompleteV10AuditTableStartupFailure -IncompleteV10AuditTablePlayerRowPreservation
    } catch {
        $minimumCanvasPlayerRowPreservationPathResolutionObserved = $_.Exception.Message -match 'unused\.jar'
    }
    if (-not $minimumCanvasPlayerRowPreservationPathResolutionObserved) {
        throw 'The minimum-Canvas MySQL player-row-preservation target did not pass its guard before external paths were resolved.'
    }
    Write-Output 'PASS: accept the minimum-Canvas MySQL player-row-preservation runner target before external resources are resolved'

    $maximumCanvasPlayerRowPreservationPathResolutionObserved = $false
    try {
        & $runnerPath -ServerJar 'unused.jar' -ExpectedServerSha256 ('a' * 64) -ExpectedCandidateSha256 ('b' * 64) `
            -PlugDevRoot 'unused' -JavaHome 'unused' -DatabaseProduct MySQL -PlatformEndpoint MaximumCanvas `
            -IncompleteV10AuditTableStartupFailure -IncompleteV10AuditTablePlayerRowPreservation
    } catch {
        $maximumCanvasPlayerRowPreservationPathResolutionObserved = $_.Exception.Message -match 'unused\.jar'
    }
    if (-not $maximumCanvasPlayerRowPreservationPathResolutionObserved) {
        throw 'The maximum-Canvas MySQL player-row-preservation target did not pass its guard before external paths were resolved.'
    }
    Write-Output 'PASS: accept the maximum-Canvas MySQL player-row-preservation runner target before external resources are resolved'

    foreach ($crossDialectCase in @(
            @{ database='MariaDB'; endpoint='MinimumCanvas' }
            @{ database='PostgreSQL'; endpoint='MaximumCanvas' }
        )) {
        $crossDialectPathResolutionObserved = $false
        try {
            & $runnerPath -ServerJar 'unused.jar' -ExpectedServerSha256 ('a' * 64) -ExpectedCandidateSha256 ('b' * 64) `
                -PlugDevRoot 'unused' -JavaHome 'unused' -DatabaseProduct $crossDialectCase.database -PlatformEndpoint $crossDialectCase.endpoint `
                -IncompleteV10AuditTableStartupFailure -IncompleteV10AuditTablePlayerRowPreservation
        } catch {
            $crossDialectPathResolutionObserved = $_.Exception.Message -match 'unused\.jar'
        }
        if (-not $crossDialectPathResolutionObserved) {
            throw "The $($crossDialectCase.database) / $($crossDialectCase.endpoint) player-row-preservation target was rejected before external paths were resolved."
        }
    }
    Write-Output 'PASS: accept MariaDB and PostgreSQL row-preservation targets before resolving external resources'

    $playerRowWrongEndpointRejected = $false
    try {
        & $runnerPath -ServerJar 'unused.jar' -ExpectedServerSha256 ('a' * 64) -ExpectedCandidateSha256 ('b' * 64) `
            -PlugDevRoot 'unused' -JavaHome 'unused' -DatabaseProduct MySQL -PlatformEndpoint CurrentStable `
            -IncompleteV10AuditTableStartupFailure -IncompleteV10AuditTablePlayerRowPreservation
    } catch {
        $playerRowWrongEndpointRejected = $_.Exception.Message -match 'user-pinned minimum/maximum Paper, Folia, and Canvas endpoints'
    }
    if (-not $playerRowWrongEndpointRejected) {
        throw 'The player-row-preservation runner accepted an unsupported platform endpoint.'
    }
    Write-Output 'PASS: reject an unsupported platform for player-row preservation before external paths are resolved'

    function New-T21MySqlStartupFailureReportFixture([string]$product, [string]$version) {
        $copy = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $report -Depth 30 -Compress) -AsHashtable -ErrorAction Stop
        $copy.configuration.databaseType = 'mysql'
        $copy.sqlBackend = [ordered]@{ product = $product; version = $version }
        $copy.backendProcesses.Remove('postgresql')
        $copy.backendProcesses.mysql = [ordered]@{
            product = $product; version = $version; processId = 3001; launchProcessId = 3003; runOwned = $true; listenerOwnerVerified = $true
        }
        $mysqlShutdown = [ordered]@{
            backend = 'mysql'; command = 'mysqladmin shutdown'; commandExitCode = 0; processId = 3001
            shutdownTargetVerified = $true; processExitCode = 0; processExitCodeObserved = $true; processExited = $true
            launchProcessId = 3003; launchProcessExitCode = 0; launchProcessExitCodeObserved = $true; launchProcessExited = $true; forced = $false
        }
        $redisShutdown = $copy.backendShutdowns | Where-Object { $_.backend -ceq 'redis' }
        $copy.backendShutdowns = @($mysqlShutdown, $redisShutdown)
        $copy.schemaStateBefore.auditVersionRowJson = '{"id":"audit","version":12,"applied_at":"2026-10-14 00:00:00","description":"T21 newer audit schema preservation sentinel"}'
        $copy.schemaStateAfter.auditVersionRowJson = $copy.schemaStateBefore.auditVersionRowJson

        $columnTypes = @('varchar(36)', 'bigint', 'varchar(20)', 'varchar(36)', 'varchar(16)', 'decimal(20,2)', 'decimal(20,2)', 'varchar(30)', 'varchar(50)', 'varchar(36)', 'varchar(16)', 'varchar(255)', 'int', 'varchar(36)', 'bigint', 'text')
        $nullable = @('NO', 'NO', 'NO', 'NO', 'YES', 'NO', 'NO', 'NO', 'NO', 'YES', 'YES', 'YES', 'YES', 'YES', 'YES', 'NO')
        $collation = if ($product -ceq 'MySQL') { 'utf8mb4_0900_ai_ci' } else { 'utf8mb4_general_ci' }
        $characterColumns = @('id', 'type', 'player_uuid', 'player_name', 'source', 'server', 'target_uuid', 'target_name', 'reason', 'request_id', 't21_sentinel')
        $columnRows = @(
            for ($index = 0; $index -lt $columnNames.Count; $index++) {
                $isCharacterColumn = $columnNames[$index] -in $characterColumns
                $hasDeclaredDefault = $columnNames[$index] -in @('merged_count', 'sequence')
                $mariaDbImplicitNull = $product -ceq 'MariaDB' -and $nullable[$index] -ceq 'YES' -and -not $hasDeclaredDefault
                [ordered]@{
                    table_schema = 'syncmoney_t21_fixture'; table_name = 'syncmoney_audit_log'; column_name = $columnNames[$index]
                    ordinal_position = $index + 1; column_type = $columnTypes[$index]; is_nullable = $nullable[$index]
                    column_default = if ($columnNames[$index] -ceq 'merged_count') { '1' } elseif ($columnNames[$index] -ceq 'sequence') { '0' } elseif ($mariaDbImplicitNull) { 'NULL' } else { $null }
                    column_default_is_null = if ($hasDeclaredDefault -or $mariaDbImplicitNull) { 'false' } else { 'true' }
                    extra = ''; character_set_name = if ($isCharacterColumn) { 'utf8mb4' } else { $null }
                    collation_name = if ($isCharacterColumn) { $collation } else { $null }; generation_expression = ''
                }
            }
        )
        $copy.schemaStateBefore.auditColumnsJson = ConvertTo-Json -InputObject ([object[]]$columnRows) -Depth 10 -Compress
        $copy.schemaStateAfter.auditColumnsJson = $copy.schemaStateBefore.auditColumnsJson

        $indexRows = @(
            [ordered]@{ name = 'PRIMARY'; nonUnique = 0; indexType = 'BTREE'; visibility = 'YES'; columns = @([ordered]@{ position = 1; columnName = 'id'; direction = 'A'; subPart = $null }) }
            [ordered]@{ name = 'idx_audit_timestamp'; nonUnique = 1; indexType = 'BTREE'; visibility = 'YES'; columns = @([ordered]@{ position = 1; columnName = 'timestamp'; direction = 'D'; subPart = $null }, [ordered]@{ position = 2; columnName = 'sequence'; direction = 'D'; subPart = $null }) }
            [ordered]@{ name = 'idx_audit_player'; nonUnique = 1; indexType = 'BTREE'; visibility = 'YES'; columns = @([ordered]@{ position = 1; columnName = 'player_uuid'; direction = 'A'; subPart = $null }, [ordered]@{ position = 2; columnName = 'timestamp'; direction = 'D'; subPart = $null }, [ordered]@{ position = 3; columnName = 'sequence'; direction = 'D'; subPart = $null }) }
            [ordered]@{ name = 'idx_audit_type_timestamp_sequence'; nonUnique = 1; indexType = 'BTREE'; visibility = 'YES'; columns = @([ordered]@{ position = 1; columnName = 'type'; direction = 'A'; subPart = $null }, [ordered]@{ position = 2; columnName = 'timestamp'; direction = 'D'; subPart = $null }, [ordered]@{ position = 3; columnName = 'sequence'; direction = 'D'; subPart = $null }) }
        )
        $copy.schemaStateBefore.auditIndexesJson = ConvertTo-Json -InputObject ([object[]]$indexRows) -Depth 10 -Compress
        $copy.schemaStateAfter.auditIndexesJson = $copy.schemaStateBefore.auditIndexesJson

        $emptyLogPath = Join-Path $testRoot 'logs\mysql-stdout.log'
        [IO.File]::WriteAllText($emptyLogPath, '', [Text.UTF8Encoding]::new($false))
        $emptyLogInfo = Get-Item -LiteralPath $emptyLogPath
        $copy.logs['logs\mysql-stdout.log'] = [ordered]@{
            bytes = [long]$emptyLogInfo.Length
            sha256 = (Get-FileHash -LiteralPath $emptyLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        return $copy
    }

    function New-T21MySqlIncompleteAuditReportFixture(
        [string]$product = 'MySQL',
        [string]$version = '8.0.42',
        [string]$platformEndpoint = 'Minimum'
    ) {
        $endpoint = Get-T21PlatformEndpoint -PlatformEndpoint $platformEndpoint
        $copy = New-T21MySqlStartupFailureReportFixture $product $version
        $copy.server = [ordered]@{
            software = $endpoint.software
            minecraftVersion = $endpoint.minecraftVersion
            build = $endpoint.build
            sha256 = $endpoint.sha256
        }
        $copy.startupObservation.runtimeIdentity = [ordered]@{
            software = $endpoint.software
            runtimeVersion = $endpoint.minecraftVersion
            minecraftVersion = $endpoint.minecraftVersion
            build = $endpoint.build
        }
        $copy.configuration.databaseName = 'syncmoney_t21_a1b2c3d4'
        $copy.configuration.databaseNameObserved = 'syncmoney_t21_a1b2c3d4'
        $copy.task = 'T21-V03-SYNC-incomplete-v10-audit-table-startup-failure'
        $copy.schemaStateBefore.mainVersion = 1
        $copy.schemaStateBefore.mainVersionRowJson = '{"id":"1","version":1,"applied_at":"2026-10-14 00:00:00","description":"T21 pre-existing main schema"}'
        $copy.schemaStateBefore.auditVersion = 9
        $copy.schemaStateBefore.auditVersionRowJson = '{"id":"audit","version":9,"applied_at":"2026-10-14 00:00:00","description":"T21 incomplete audit v9 sentinel"}'
        $copy.schemaStateBefore.playersTableExists = $false
        $copy.schemaStateBefore.playerRows = $null
        $copy.schemaStateAfter.mainVersion = 1
        $copy.schemaStateAfter.mainVersionRowJson = $copy.schemaStateBefore.mainVersionRowJson
        $copy.schemaStateAfter.auditVersion = 9
        $copy.schemaStateAfter.auditVersionRowJson = $copy.schemaStateBefore.auditVersionRowJson
        $copy.schemaStateAfter.playersTableExists = $true
        $copy.schemaStateAfter.playerRows = 0

        $columnJson = ConvertTo-Json -InputObject @([ordered]@{
            table_schema = 'syncmoney_t21_a1b2c3d4'
            table_name = 'syncmoney_audit_log'
            column_name = 'id'
            ordinal_position = 1
            column_default = $null
            is_nullable = 'NO'
            column_type = 'varchar(36)'
            column_default_is_null = 'true'
            extra = ''
            character_set_name = 'utf8mb4'
            collation_name = if ($product -ceq 'MySQL') { 'utf8mb4_0900_ai_ci' } else { 'utf8mb4_general_ci' }
            generation_expression = if ($product -ceq 'MariaDB') { $null } else { '' }
        }) -Depth 10 -Compress
        $copy.schemaStateBefore.auditColumnsJson = $columnJson
        $copy.schemaStateAfter.auditColumnsJson = $columnJson
        $indexJson = ConvertTo-Json -InputObject @([ordered]@{
            name = 'PRIMARY'
            nonUnique = 0
            indexType = 'BTREE'
            visibility = 'YES'
            columns = @([ordered]@{ position = 1; columnName = 'id'; direction = 'A'; subPart = $null })
        }) -Depth 10 -Compress
        $copy.schemaStateBefore.auditIndexesJson = $indexJson
        $copy.schemaStateAfter.auditIndexesJson = $indexJson
        $rowsJson = '[{"id":"00000000-0000-0000-0000-000000000329"}]'
        $copy.schemaStateBefore.auditRowsJson = $rowsJson
        $copy.schemaStateAfter.auditRowsJson = $rowsJson
        $copy.startupObservation.expectedFailure = 'incomplete-v10-audit-table'
        $copy.startupObservation.failureMessage = 'Failed audit schema migration to version 10; version metadata was not advanced past 9'
        $copy.startupObservation.failureDetailMarker = 'Required v10 audit columns are missing:'

        $platformSoftware = switch ($endpoint.software) {
            'paper' { 'Paper' }
            'folia' { 'Folia' }
            'canvas' { 'Canvas' }
            default { throw "Unsupported fixture platform software '$($endpoint.software)'." }
        }
        $platformLine = '[07:43:59] [Server thread/INFO]: [Bootstrap] Loading {2} {0}-{1}-main@fixture for Minecraft {0}' -f $endpoint.minecraftVersion, $endpoint.build, $platformSoftware
        $platformLog = @(
            $platformLine
            '[07:44:00] [Server thread/INFO]: Loading server plugin Syncmoney v1.4.0-alpha.1'
            '[07:44:00] [Server thread/INFO]: [Syncmoney] Syncmoney audit schema version: current 9, target 11'
            '[07:44:00] [Server thread/SEVERE]: [Syncmoney] Syncmoney initialization failed; rolling back initialized owners'
            'java.lang.IllegalStateException: Failed audit schema migration to version 10; version metadata was not advanced past 9'
            'Caused by: java.sql.SQLException: Required v10 audit columns are missing: [timestamp, type, player_uuid]'
            '[07:44:01] [Server thread/INFO]: [noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Shutdown initiated...'
            '[07:44:01] [Server thread/INFO]: [noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Shutdown completed.'
            '[07:44:02] [Server thread/INFO]: [Syncmoney] Disabling Syncmoney v1.4.0-alpha.1'
        )
        $platformLogPath = Join-Path $testRoot 'logs\platform-candidate-startup-failure.txt'
        [IO.File]::WriteAllLines($platformLogPath, [string[]]$platformLog, [Text.UTF8Encoding]::new($false))
        $logInfo = Get-Item -LiteralPath $platformLogPath
        $logHash = (Get-FileHash -LiteralPath $platformLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $copy.platformLogs[0].bytes = [long]$logInfo.Length
        $copy.platformLogs[0].sha256 = $logHash
        $copy.logs['logs\platform-candidate-startup-failure.txt'].bytes = [long]$logInfo.Length
        $copy.logs['logs\platform-candidate-startup-failure.txt'].sha256 = $logHash
        return $copy
    }

    $playerRowPreservationReport = New-T21MySqlIncompleteAuditReportFixture 'MySQL' '8.0.42' 'Minimum'
    $playerRowSentinel = [ordered]@{
        player_uuid = '00000000-0000-0000-0000-00000000cafe'
        player_name = 'T21-ROW-SENTINEL'
        balance = '1234.56'
        version = '73'
        last_server = 't21-player-preservation'
        updated_at = '2026-10-16 12:34:56'
    }
    $postgresPlayerRowSentinel = [ordered]@{
        player_uuid = '00000000-0000-0000-0000-00000000cafe'
        player_name = 'T21-ROW-SENTINEL'
        balance = '1234.56'
        version = '73'
        last_server = 't21-player-preservation'
        updated_at = '2026-10-16 12:34:56.000000'
    }
    $postgresPlayerRowsJson = ConvertTo-Json -InputObject @($postgresPlayerRowSentinel) -Depth 10 -Compress
    if (-not (Test-T21IncompleteV10PlayerRowSentinel $postgresPlayerRowsJson -DatabaseProduct PostgreSQL)) {
        throw 'The PostgreSQL player-row sentinel rejected its exact six-digit fractional-second timestamp.'
    }
    $postgresPlayerRowSentinel.updated_at = '2026-10-16 12:34:56.000001'
    $postgresFractionOnlyMutationJson = ConvertTo-Json -InputObject @($postgresPlayerRowSentinel) -Depth 10 -Compress
    if (Test-T21IncompleteV10PlayerRowSentinel $postgresFractionOnlyMutationJson -DatabaseProduct PostgreSQL) {
        throw 'The PostgreSQL player-row sentinel accepted a one-microsecond timestamp mutation.'
    }
    Write-Output 'PASS: preserve PostgreSQL TIMESTAMP microseconds and reject a fractional-only player-row mutation'

    $playerRowsJson = ConvertTo-Json -InputObject @($playerRowSentinel) -Depth 10 -Compress
    $playerRowPreservationReport.task = 'T21-V03-SYNC-incomplete-v10-audit-table-player-row-preservation-MySQL-minimum-Paper'
    $playerRowPreservationReport.schemaStateBefore.playersTableExists = $true
    $playerRowPreservationReport.schemaStateBefore.playerRows = 1
    $playerRowPreservationReport.schemaStateBefore.playerRowsJson = $playerRowsJson
    $playerRowPreservationReport.schemaStateAfter.playersTableExists = $true
    $playerRowPreservationReport.schemaStateAfter.playerRows = 1
    $playerRowPreservationReport.schemaStateAfter.playerRowsJson = $playerRowsJson
    if (-not (Test-T21StartupFailureReportFixture $playerRowPreservationReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTablePlayerRowPreservation)) {
        throw 'The incomplete-v10 startup-failure report rejected an unchanged pre-existing MySQL player balance/version row.'
    }
    Write-Output 'PASS: accept incomplete-v10 startup rejection with an unchanged pre-existing MySQL player row'

    $legacyMinimumPaperTaskReport = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $playerRowPreservationReport -Depth 30 -Compress) -AsHashtable -ErrorAction Stop
    $legacyMinimumPaperTaskReport.task = 'T21-V03-SYNC-incomplete-v10-audit-table-player-row-preservation-MySQL-MinimumPaper'
    if (-not (Test-T21StartupFailureReportFixture $legacyMinimumPaperTaskReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTablePlayerRowPreservation)) {
        throw 'The incomplete-v10 startup-failure report rejected the previously emitted minimum-Paper player-row task label.'
    }
    Write-Output 'PASS: accept the historical MinimumPaper task label for minimum-Paper player-row evidence'

    $legacyMariaDbMinimumPaperTaskReport = New-T21MySqlIncompleteAuditReportFixture 'MariaDB' '12.3.3-MariaDB'
    $legacyMariaDbMinimumPaperTaskReport.task = 'T21-V03-SYNC-incomplete-v10-audit-table-player-row-preservation-MySQL-MinimumPaper'
    $legacyMariaDbMinimumPaperTaskReport.schemaStateBefore.playersTableExists = $true
    $legacyMariaDbMinimumPaperTaskReport.schemaStateBefore.playerRows = 1
    $legacyMariaDbMinimumPaperTaskReport.schemaStateBefore.playerRowsJson = $playerRowsJson
    $legacyMariaDbMinimumPaperTaskReport.schemaStateAfter.playersTableExists = $true
    $legacyMariaDbMinimumPaperTaskReport.schemaStateAfter.playerRows = 1
    $legacyMariaDbMinimumPaperTaskReport.schemaStateAfter.playerRowsJson = $playerRowsJson
    if (Test-T21StartupFailureReportFixture $legacyMariaDbMinimumPaperTaskReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTablePlayerRowPreservation) {
        throw 'The MariaDB incomplete-v10 report accepted the historical MySQL-only MinimumPaper task label.'
    }
    Write-Output 'PASS: reject the historical MySQL MinimumPaper task label for MariaDB evidence'

    $maximumPaperEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint Maximum
    $maximumPlayerRowPreservationReport = New-T21MySqlIncompleteAuditReportFixture 'MySQL' '8.0.42' 'Maximum'
    $maximumPlayerRowPreservationReport.task = 'T21-V03-SYNC-incomplete-v10-audit-table-player-row-preservation-MySQL-maximum-Paper'
    $maximumPlayerRowPreservationReport.schemaStateBefore.playersTableExists = $true
    $maximumPlayerRowPreservationReport.schemaStateBefore.playerRows = 1
    $maximumPlayerRowPreservationReport.schemaStateBefore.playerRowsJson = $playerRowsJson
    $maximumPlayerRowPreservationReport.schemaStateAfter.playersTableExists = $true
    $maximumPlayerRowPreservationReport.schemaStateAfter.playerRows = 1
    $maximumPlayerRowPreservationReport.schemaStateAfter.playerRowsJson = $playerRowsJson
    if (-not (Test-T21StartupFailureReportFixture $maximumPlayerRowPreservationReport $maximumPaperEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Maximum -FailureScenario IncompleteV10AuditTablePlayerRowPreservation)) {
        throw 'The incomplete-v10 startup-failure report rejected an unchanged pre-existing maximum-Paper MySQL player row.'
    }
    Write-Output 'PASS: accept maximum-Paper incomplete-v10 startup rejection with an unchanged pre-existing MySQL player row'

    $minimumFoliaEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint MinimumFolia
    $minimumFoliaPlayerRowPreservationReport = New-T21MySqlIncompleteAuditReportFixture 'MySQL' '8.0.42' 'MinimumFolia'
    $minimumFoliaPlayerRowPreservationReport.task = 'T21-V03-SYNC-incomplete-v10-audit-table-player-row-preservation-MySQL-minimum-Folia'
    $minimumFoliaPlayerRowPreservationReport.schemaStateBefore.playersTableExists = $true
    $minimumFoliaPlayerRowPreservationReport.schemaStateBefore.playerRows = 1
    $minimumFoliaPlayerRowPreservationReport.schemaStateBefore.playerRowsJson = $playerRowsJson
    $minimumFoliaPlayerRowPreservationReport.schemaStateAfter.playersTableExists = $true
    $minimumFoliaPlayerRowPreservationReport.schemaStateAfter.playerRows = 1
    $minimumFoliaPlayerRowPreservationReport.schemaStateAfter.playerRowsJson = $playerRowsJson
    if (-not (Test-T21StartupFailureReportFixture $minimumFoliaPlayerRowPreservationReport $minimumFoliaEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MinimumFolia -FailureScenario IncompleteV10AuditTablePlayerRowPreservation)) {
        throw 'The incomplete-v10 startup-failure report rejected an unchanged pre-existing minimum-Folia MySQL player row.'
    }
    Write-Output 'PASS: accept minimum-Folia incomplete-v10 startup rejection with an unchanged pre-existing MySQL player row'

    $maximumFoliaEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint MaximumFolia
    $maximumFoliaPlayerRowPreservationReport = New-T21MySqlIncompleteAuditReportFixture 'MySQL' '8.0.42' 'MaximumFolia'
    $maximumFoliaPlayerRowPreservationReport.task = 'T21-V03-SYNC-incomplete-v10-audit-table-player-row-preservation-MySQL-maximum-Folia'
    $maximumFoliaPlayerRowPreservationReport.schemaStateBefore.playersTableExists = $true
    $maximumFoliaPlayerRowPreservationReport.schemaStateBefore.playerRows = 1
    $maximumFoliaPlayerRowPreservationReport.schemaStateBefore.playerRowsJson = $playerRowsJson
    $maximumFoliaPlayerRowPreservationReport.schemaStateAfter.playersTableExists = $true
    $maximumFoliaPlayerRowPreservationReport.schemaStateAfter.playerRows = 1
    $maximumFoliaPlayerRowPreservationReport.schemaStateAfter.playerRowsJson = $playerRowsJson
    if (-not (Test-T21StartupFailureReportFixture $maximumFoliaPlayerRowPreservationReport $maximumFoliaEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MaximumFolia -FailureScenario IncompleteV10AuditTablePlayerRowPreservation)) {
        throw 'The incomplete-v10 startup-failure report rejected an unchanged pre-existing maximum-Folia MySQL player row.'
    }
    Write-Output 'PASS: accept maximum-Folia incomplete-v10 startup rejection with an unchanged pre-existing MySQL player row'

    $minimumCanvasEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint MinimumCanvas
    $minimumCanvasPlayerRowPreservationReport = New-T21MySqlIncompleteAuditReportFixture 'MySQL' '8.0.42' 'MinimumCanvas'
    $minimumCanvasPlayerRowPreservationReport.task = 'T21-V03-SYNC-incomplete-v10-audit-table-player-row-preservation-MySQL-minimum-Canvas'
    $minimumCanvasPlayerRowPreservationReport.schemaStateBefore.playersTableExists = $true
    $minimumCanvasPlayerRowPreservationReport.schemaStateBefore.playerRows = 1
    $minimumCanvasPlayerRowPreservationReport.schemaStateBefore.playerRowsJson = $playerRowsJson
    $minimumCanvasPlayerRowPreservationReport.schemaStateAfter.playersTableExists = $true
    $minimumCanvasPlayerRowPreservationReport.schemaStateAfter.playerRows = 1
    $minimumCanvasPlayerRowPreservationReport.schemaStateAfter.playerRowsJson = $playerRowsJson
    if (-not (Test-T21StartupFailureReportFixture $minimumCanvasPlayerRowPreservationReport $minimumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MinimumCanvas -FailureScenario IncompleteV10AuditTablePlayerRowPreservation)) {
        throw 'The incomplete-v10 startup-failure report rejected an unchanged pre-existing minimum-Canvas MySQL player row.'
    }
    Write-Output 'PASS: accept minimum-Canvas incomplete-v10 startup rejection with an unchanged pre-existing MySQL player row'

    $maximumCanvasEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint MaximumCanvas
    $maximumCanvasPlayerRowPreservationReport = New-T21MySqlIncompleteAuditReportFixture 'MySQL' '8.0.42' 'MaximumCanvas'
    $maximumCanvasPlayerRowPreservationReport.task = 'T21-V03-SYNC-incomplete-v10-audit-table-player-row-preservation-MySQL-maximum-Canvas'
    $maximumCanvasPlayerRowPreservationReport.schemaStateBefore.playersTableExists = $true
    $maximumCanvasPlayerRowPreservationReport.schemaStateBefore.playerRows = 1
    $maximumCanvasPlayerRowPreservationReport.schemaStateBefore.playerRowsJson = $playerRowsJson
    $maximumCanvasPlayerRowPreservationReport.schemaStateAfter.playersTableExists = $true
    $maximumCanvasPlayerRowPreservationReport.schemaStateAfter.playerRows = 1
    $maximumCanvasPlayerRowPreservationReport.schemaStateAfter.playerRowsJson = $playerRowsJson
    if (-not (Test-T21StartupFailureReportFixture $maximumCanvasPlayerRowPreservationReport $maximumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MaximumCanvas -FailureScenario IncompleteV10AuditTablePlayerRowPreservation)) {
        throw 'The incomplete-v10 startup-failure report rejected an unchanged pre-existing maximum-Canvas MySQL player row.'
    }
    Write-Output 'PASS: accept maximum-Canvas incomplete-v10 startup rejection with an unchanged pre-existing MySQL player row'

    $maximumCanvasPlayerRowWrongBuildReport = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $maximumCanvasPlayerRowPreservationReport -Depth 30 -Compress) -AsHashtable -ErrorAction Stop
    $maximumCanvasPlayerRowWrongBuildReport.startupObservation.runtimeIdentity.build = 971
    if (Test-T21StartupFailureReportFixture $maximumCanvasPlayerRowWrongBuildReport $maximumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MaximumCanvas -FailureScenario IncompleteV10AuditTablePlayerRowPreservation) {
        throw 'The maximum-Canvas MySQL player-row-preservation report accepted a runtime build that differs from the pinned endpoint.'
    }
    Write-Output 'PASS: reject maximum-Canvas MySQL player-row evidence with a mismatched runtime build'

    $maximumCanvasPlayerRowWrongTaskReport = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $maximumCanvasPlayerRowPreservationReport -Depth 30 -Compress) -AsHashtable -ErrorAction Stop
    $maximumCanvasPlayerRowWrongTaskReport.task = 'T21-V03-SYNC-incomplete-v10-audit-table-player-row-preservation-MySQL-minimum-Canvas'
    if (Test-T21StartupFailureReportFixture $maximumCanvasPlayerRowWrongTaskReport $maximumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MaximumCanvas -FailureScenario IncompleteV10AuditTablePlayerRowPreservation) {
        throw 'The maximum-Canvas MySQL player-row-preservation report accepted a task label for a different endpoint.'
    }
    Write-Output 'PASS: reject maximum-Canvas MySQL player-row evidence with a mismatched endpoint task label'


    $maximumLegacyMinimumPaperLabelReport = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $maximumPlayerRowPreservationReport -Depth 30 -Compress) -AsHashtable -ErrorAction Stop
    $maximumLegacyMinimumPaperLabelReport.task = 'T21-V03-SYNC-incomplete-v10-audit-table-player-row-preservation-MySQL-MinimumPaper'
    if (Test-T21StartupFailureReportFixture $maximumLegacyMinimumPaperLabelReport $maximumPaperEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Maximum -FailureScenario IncompleteV10AuditTablePlayerRowPreservation) {
        throw 'The maximum-Paper incomplete-v10 startup-failure report accepted the historical minimum-Paper task label.'
    }
    Write-Output 'PASS: reject the historical MinimumPaper task label for maximum-Paper player-row evidence'

    $mutatedPlayerRowReport = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $playerRowPreservationReport -Depth 30 -Compress) -AsHashtable -ErrorAction Stop
    $mutatedPlayerRow = [ordered]@{
        player_uuid = '00000000-0000-0000-0000-00000000cafe'
        player_name = 'T21-ROW-SENTINEL'
        balance = '1234.57'
        version = '74'
        last_server = 't21-player-preservation'
        updated_at = '2026-10-16 12:34:57'
    }
    $mutatedPlayerRowReport.schemaStateAfter.playerRowsJson = ConvertTo-Json -InputObject @($mutatedPlayerRow) -Depth 10 -Compress
    if (Test-T21StartupFailureReportFixture $mutatedPlayerRowReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTablePlayerRowPreservation) {
        throw 'The incomplete-v10 startup-failure report accepted a changed pre-existing MySQL player row.'
    }
    Write-Output 'PASS: reject incomplete-v10 startup evidence when the pre-existing MySQL player row changed'

    $missingPlayerRowReport = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $playerRowPreservationReport -Depth 30 -Compress) -AsHashtable -ErrorAction Stop
    $missingPlayerRowReport.schemaStateAfter.playerRows = 0
    $missingPlayerRowReport.schemaStateAfter.playerRowsJson = '[]'
    if (Test-T21StartupFailureReportFixture $missingPlayerRowReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTablePlayerRowPreservation) {
        throw 'The incomplete-v10 startup-failure report accepted a missing pre-existing MySQL player row.'
    }
    Write-Output 'PASS: reject incomplete-v10 startup evidence when the pre-existing MySQL player row is missing'

    function Set-T21MySqlGrantFixtureLines([object]$Report, [string]$Field, [string]$LogRelativePath, [string[]]$Lines) {
        $Report.databaseDdlPrivileges[$Field] = [string[]]$Lines
        $logPath = Join-Path $Report.runDirectory $LogRelativePath
        [IO.File]::WriteAllLines($logPath, [string[]]$Lines, [Text.UTF8Encoding]::new($false))
        $logInfo = Get-Item -LiteralPath $logPath
        $Report.logs[$LogRelativePath].bytes = [long]$logInfo.Length
        $Report.logs[$LogRelativePath].sha256 = (Get-FileHash -LiteralPath $logPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    function Set-T21MySqlDeniedRequestIdDdlEvent([object]$Report, [object]$Event) {
        $logRelativePath = 'logs\mysql-denied-request-id-ddl-evidence.jsonl'
        $logPath = Join-Path $Report.runDirectory $logRelativePath
        $jsonLine = ConvertTo-Json -InputObject $Event -Depth 5 -Compress
        [IO.File]::WriteAllText($logPath, $jsonLine + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
        $logInfo = Get-Item -LiteralPath $logPath
        $Report.logs[$logRelativePath] = [ordered]@{
            bytes = [long]$logInfo.Length
            sha256 = (Get-FileHash -LiteralPath $logPath -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    function Set-T21PostgreSqlPrivilegeFixtureEvidence([object]$Report) {
        $relativePath = 'logs\postgresql-audit-ddl-privileges.txt'
        $logPath = Join-Path $Report.runDirectory $relativePath
        [IO.File]::WriteAllText($logPath, [string]$Report.databaseDdlPrivileges.evidenceJson + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
        $logInfo = Get-Item -LiteralPath $logPath
        $Report.logs[$relativePath] = [ordered]@{
            bytes = [long]$logInfo.Length
            sha256 = (Get-FileHash -LiteralPath $logPath -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    function Set-T21MySqlAuditRowsFixtureJson([object]$Report, [string]$RowsJson) {
        $Report.schemaStateBefore.auditRowsJson = $RowsJson
        $Report.schemaStateAfter.auditRowsJson = $RowsJson
        $logRelativePath = 'logs\newer-audit-schema-rows.txt'
        $logPath = Join-Path $Report.runDirectory $logRelativePath
        [IO.File]::WriteAllText($logPath, $RowsJson, [Text.UTF8Encoding]::new($false))
        $logInfo = Get-Item -LiteralPath $logPath
        $Report.logs[$logRelativePath] = [ordered]@{
            bytes = [long]$logInfo.Length
            sha256 = (Get-FileHash -LiteralPath $logPath -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    function New-T21RequiredV11DdlFailureReportFixture(
        [string]$databaseProduct = 'MySQL',
        [string]$databaseVersion = '8.0.42',
        [string]$platformEndpoint = 'Minimum'
    ) {
        $endpoint = Get-T21PlatformEndpoint -PlatformEndpoint $platformEndpoint
        $copy = if ($databaseProduct -ceq 'PostgreSQL') {
            ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $report -Depth 30 -Compress) -AsHashtable -ErrorAction Stop
        } else {
            New-T21MySqlStartupFailureReportFixture $databaseProduct $databaseVersion
        }
        $v11RunRoot = Join-Path $testRoot ("v11-required-ddl-$($databaseProduct.ToLowerInvariant())-$($platformEndpoint.ToLowerInvariant())")
        $v11LogsRoot = Join-Path $v11RunRoot 'logs'
        New-Item -ItemType Directory -Force -Path $v11LogsRoot | Out-Null
        foreach ($existingLog in Get-ChildItem -LiteralPath (Join-Path $testRoot 'logs') -File) {
            $v11LogPath = Join-Path $v11LogsRoot $existingLog.Name
            Copy-Item -LiteralPath $existingLog.FullName -Destination $v11LogPath
            $v11LogInfo = Get-Item -LiteralPath $v11LogPath
            $copy.logs[('logs\' + $existingLog.Name)] = [ordered]@{
                bytes = [long]$v11LogInfo.Length
                sha256 = (Get-FileHash -LiteralPath $v11LogPath -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        }
        $copy.runDirectory = $v11RunRoot
        $copy.gradleDaemonCleanup.isolatedUserHome = [IO.Path]::GetFullPath((Join-Path $v11RunRoot '.gradle-user-home'))
        $copy.server = [ordered]@{
            software = $endpoint.software
            minecraftVersion = $endpoint.minecraftVersion
            build = $endpoint.build
            sha256 = $endpoint.sha256
        }
        $copy.startupObservation.runtimeIdentity = [ordered]@{
            software = $endpoint.software
            runtimeVersion = $endpoint.minecraftVersion
            minecraftVersion = $endpoint.minecraftVersion
            build = $endpoint.build
        }
        $copy.configuration.databaseName = 'syncmoney_t21_a1b2c3d4'
        $copy.configuration.databaseNameObserved = 'syncmoney_t21_a1b2c3d4'
        if ($databaseProduct -ceq 'PostgreSQL') { $copy.configuration.databaseType = 'postgresql' }
        $copy.task = 'T21-V03-SYNC-v11-audit-ddl-permission-failure'
        $mariaDbAuthentication = if ($databaseProduct -ceq 'MariaDB') { " IDENTIFIED BY PASSWORD '<redacted>'" } else { '' }
        $grantLines127 = @(
            ('GRANT USAGE ON *.* TO `sm_t21_a1b2c3d4`@`127.0.0.1`' + $mariaDbAuthentication)
            'GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, REFERENCES, INDEX, CREATE TEMPORARY TABLES, LOCK TABLES, EXECUTE, CREATE VIEW, SHOW VIEW, CREATE ROUTINE, ALTER ROUTINE, EVENT, TRIGGER ON `syncmoney_t21_a1b2c3d4`.* TO `sm_t21_a1b2c3d4`@`127.0.0.1`'
        )
        $grantLinesLocal = @(
            ('GRANT USAGE ON *.* TO `sm_t21_a1b2c3d4`@`localhost`' + $mariaDbAuthentication)
            'GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, REFERENCES, INDEX, CREATE TEMPORARY TABLES, LOCK TABLES, EXECUTE, CREATE VIEW, SHOW VIEW, CREATE ROUTINE, ALTER ROUTINE, EVENT, TRIGGER ON `syncmoney_t21_a1b2c3d4`.* TO `sm_t21_a1b2c3d4`@`localhost`'
        )
        if ($databaseProduct -ceq 'PostgreSQL') {
            $postgresPrivileges = [ordered]@{
                databaseName = 'syncmoney_t21_a1b2c3d4'
                databaseOwner = 'postgres'
                schemaName = 'public'
                schemaOwner = 'pg_database_owner'
                grantee = 'sm_t21_a1b2c3d4'
                roleSuperuser = $false
                roleMemberOfAuditOwner = $false
                auditTableOwner = 'postgres'
                databaseConnectGranted = $true
                schemaUsageGranted = $true
                schemaCreateGranted = $true
                tableSelectGranted = $true
                tableInsertGranted = $true
                tableUpdateGranted = $true
                tableDeleteGranted = $true
            }
            $postgresEvidenceJson = ConvertTo-Json -InputObject $postgresPrivileges -Depth 5 -Compress
            $copy.databaseDdlPrivileges = [ordered]@{
                product = 'PostgreSQL'
                grantee = 'sm_t21_a1b2c3d4'
                databaseName = 'syncmoney_t21_a1b2c3d4'
                schemaName = 'public'
                databaseOwner = 'postgres'
                schemaOwner = 'pg_database_owner'
                auditTableOwner = 'postgres'
                roleSuperuser = $false
                roleMemberOfAuditOwner = $false
                databaseConnectGranted = $true
                schemaUsageGranted = $true
                schemaCreateGranted = $true
                tableSelectGranted = $true
                tableInsertGranted = $true
                tableUpdateGranted = $true
                tableDeleteGranted = $true
                tableOwnedByGrantee = $false
                alterAllowed = $false
                createGranted = $true
                verifiedBeforeStartup = $true
                evidenceJson = $postgresEvidenceJson
            }
            $postgresPrivilegeLogPath = Join-Path $v11RunRoot 'logs\postgresql-audit-ddl-privileges.txt'
            [IO.File]::WriteAllText($postgresPrivilegeLogPath, $postgresEvidenceJson + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
            $postgresPrivilegeLog = Get-Item -LiteralPath $postgresPrivilegeLogPath
            $copy.logs['logs\postgresql-audit-ddl-privileges.txt'] = [ordered]@{
                bytes = [long]$postgresPrivilegeLog.Length
                sha256 = (Get-FileHash -LiteralPath $postgresPrivilegeLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        } else {
            $copy.databaseDdlPrivileges = [ordered]@{
                product = $databaseProduct
                grantee = 'sm_t21_a1b2c3d4@127.0.0.1'
                grants = $grantLines127
                localGrants = $grantLinesLocal
                alterGranted = $false
                createGranted = $true
                verifiedBeforeStartup = $true
            }
            foreach ($grantLog in @(
                [ordered]@{ path='logs\runtime-user-grants-127.txt'; lines=$grantLines127 }
                [ordered]@{ path='logs\runtime-user-grants-local.txt'; lines=$grantLinesLocal }
            )) {
                $grantLogPath = Join-Path $v11RunRoot $grantLog.path
                [IO.File]::WriteAllLines($grantLogPath, [string[]]$grantLog.lines, [Text.UTF8Encoding]::new($false))
                $grantLogInfo = Get-Item -LiteralPath $grantLogPath
                $copy.logs[$grantLog.path] = [ordered]@{
                    bytes = [long]$grantLogInfo.Length
                    sha256 = (Get-FileHash -LiteralPath $grantLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
                }
            }
        }
        $copy.schemaStateBefore.mainVersion = 1
        $copy.schemaStateBefore.mainVersionRowJson = '{"id":"1","version":1,"applied_at":"2026-10-14 00:00:00","description":"T21 pre-existing main schema"}'
        $copy.schemaStateBefore.auditVersion = 10
        $copy.schemaStateBefore.auditVersionRowJson = '{"id":"audit","version":10,"applied_at":"2026-10-14 00:00:00","description":"T21 required v11 DDL failure sentinel"}'
        $copy.schemaStateBefore.playersTableExists = $false
        $copy.schemaStateBefore.playerRows = $null
        $copy.schemaStateAfter.mainVersion = 1
        $copy.schemaStateAfter.mainVersionRowJson = $copy.schemaStateBefore.mainVersionRowJson
        $copy.schemaStateAfter.auditVersion = 10
        $copy.schemaStateAfter.auditVersionRowJson = $copy.schemaStateBefore.auditVersionRowJson
        $copy.schemaStateAfter.playersTableExists = $true
        $copy.schemaStateAfter.playerRows = 0

        $v10Columns = @('id', 'timestamp', 'type', 'player_uuid', 'player_name', 'amount', 'balance_after',
            'source', 'server', 'target_uuid', 'target_name', 'reason', 'merged_count', 't21_sentinel')
        if ($databaseProduct -ceq 'PostgreSQL') {
            $postgresColumnFields = @(
                'table_catalog', 'table_schema', 'table_name', 'column_name', 'ordinal_position', 'column_default', 'is_nullable', 'data_type',
                'character_maximum_length', 'character_octet_length', 'numeric_precision', 'numeric_precision_radix', 'numeric_scale',
                'datetime_precision', 'interval_type', 'interval_precision', 'character_set_catalog', 'character_set_schema', 'character_set_name',
                'collation_catalog', 'collation_schema', 'collation_name', 'domain_catalog', 'domain_schema', 'domain_name', 'udt_catalog',
                'udt_schema', 'udt_name', 'scope_catalog', 'scope_schema', 'scope_name', 'maximum_cardinality', 'dtd_identifier',
                'is_self_referencing', 'is_identity', 'identity_generation', 'identity_start', 'identity_increment', 'identity_maximum',
                'identity_minimum', 'identity_cycle', 'is_generated', 'generation_expression', 'is_updatable'
            )
            $postgresColumnDefinitions = [ordered]@{
                id = @{ dataType='character varying'; length=36; nullable='NO'; udt='varchar' }
                timestamp = @{ dataType='bigint'; precision=64; radix=2; scale=0; nullable='NO'; udt='int8' }
                type = @{ dataType='character varying'; length=20; nullable='NO'; udt='varchar' }
                player_uuid = @{ dataType='character varying'; length=36; nullable='NO'; udt='varchar' }
                player_name = @{ dataType='character varying'; length=16; nullable='YES'; udt='varchar' }
                amount = @{ dataType='numeric'; precision=20; radix=10; scale=2; nullable='NO'; udt='numeric' }
                balance_after = @{ dataType='numeric'; precision=20; radix=10; scale=2; nullable='NO'; udt='numeric' }
                source = @{ dataType='character varying'; length=30; nullable='NO'; udt='varchar' }
                server = @{ dataType='character varying'; length=50; nullable='NO'; udt='varchar' }
                target_uuid = @{ dataType='character varying'; length=36; nullable='YES'; udt='varchar' }
                target_name = @{ dataType='character varying'; length=16; nullable='YES'; udt='varchar' }
                reason = @{ dataType='character varying'; length=255; nullable='YES'; udt='varchar' }
                merged_count = @{ dataType='integer'; precision=32; radix=2; scale=0; nullable='YES'; default='1'; udt='int4' }
                t21_sentinel = @{ dataType='text'; nullable='NO'; udt='text' }
            }
            $columnRows = @(
                for ($index = 0; $index -lt $v10Columns.Count; $index++) {
                    $columnName = $v10Columns[$index]
                    $definition = $postgresColumnDefinitions[$columnName]
                    $column = [ordered]@{}
                    foreach ($field in $postgresColumnFields) { $column[$field] = $null }
                    $column.table_catalog = 'syncmoney_t21_a1b2c3d4'
                    $column.table_schema = 'public'
                    $column.table_name = 'syncmoney_audit_log'
                    $column.column_name = $columnName
                    $column.ordinal_position = $index + 1
                    $column.column_default = if ($definition.ContainsKey('default')) { $definition.default } else { $null }
                    $column.is_nullable = $definition.nullable
                    $column.data_type = $definition.dataType
                    if ($definition.ContainsKey('length')) {
                        $column.character_maximum_length = $definition.length
                        $column.character_octet_length = $definition.length * 4
                    }
                    if ($definition.ContainsKey('precision')) {
                        $column.numeric_precision = $definition.precision
                        $column.numeric_precision_radix = $definition.radix
                        $column.numeric_scale = $definition.scale
                    }
                    $column.udt_name = $definition.udt
                    $column.is_identity = 'NO'
                    $column.is_generated = 'NEVER'
                    $column.is_updatable = 'YES'
                    [pscustomobject]$column
                }
            )
            $columnJson = ConvertTo-Json -InputObject ([object[]]$columnRows) -Depth 10 -Compress
        } else {
            $allColumns = @(ConvertFrom-Json -InputObject ([string]$copy.schemaStateBefore.auditColumnsJson) -AsHashtable -ErrorAction Stop)
            $columnRows = @($allColumns | Where-Object { [string]$_['column_name'] -cin $v10Columns })
            for ($index = 0; $index -lt $columnRows.Count; $index++) { $columnRows[$index].ordinal_position = $index + 1 }
            $columnJson = ConvertTo-Json -InputObject ([object[]]$columnRows) -Depth 10 -Compress
        }
        $copy.schemaStateBefore.auditColumnsJson = $columnJson
        $copy.schemaStateAfter.auditColumnsJson = $columnJson
        $indexJson = if ($databaseProduct -ceq 'PostgreSQL') {
            '[{"name":"syncmoney_audit_log_pkey","definition":"CREATE UNIQUE INDEX syncmoney_audit_log_pkey ON public.syncmoney_audit_log USING btree (id)"}]'
        } else {
            ConvertTo-Json -InputObject @([ordered]@{
                name = 'PRIMARY'
                nonUnique = 0
                indexType = 'BTREE'
                visibility = 'YES'
                columns = @([ordered]@{ position = 1; columnName = 'id'; direction = 'A'; subPart = $null })
            }) -Depth 10 -Compress
        }
        $copy.schemaStateBefore.auditIndexesJson = $indexJson
        $copy.schemaStateAfter.auditIndexesJson = $indexJson
        $rowJson = '[{"id":"00000000-0000-0000-0000-000000000330","timestamp":1791936000000,"type":"DEPOSIT","player_uuid":"00000000-0000-0000-0000-000000000330","player_name":"T21Fixture","amount":1.25,"balance_after":99.99,"source":"TEST","server":"t21-v11-ddl-permission","target_uuid":null,"target_name":null,"reason":"v10 preservation sentinel","merged_count":1,"t21_sentinel":"t21-v10-preservation-sentinel"}]'
        $copy.schemaStateBefore.auditRowsJson = $rowJson
        $copy.schemaStateAfter.auditRowsJson = $rowJson
        $rowsLogPath = Join-Path $v11RunRoot 'logs\newer-audit-schema-rows.txt'
        [IO.File]::WriteAllText($rowsLogPath, $rowJson, [Text.UTF8Encoding]::new($false))
        $rowsLogInfo = Get-Item -LiteralPath $rowsLogPath
        $copy.logs['logs\newer-audit-schema-rows.txt'] = [ordered]@{
            bytes = [long]$rowsLogInfo.Length
            sha256 = (Get-FileHash -LiteralPath $rowsLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        $copy.startupObservation.expectedFailure = 'required-v11-audit-ddl-permission-failure'
        $copy.startupObservation.failureMessage = 'Failed audit schema migration to version 11; version metadata was not advanced past 10'
        $copy.startupObservation.failureDetailMarker = if ($databaseProduct -ceq 'PostgreSQL') {
            'must be owner of table syncmoney_audit_log'
        } else {
            'ALTER command denied'
        }
        if ($databaseProduct -in @('MySQL', 'MariaDB') -and $platformEndpoint -ceq 'Maximum') {
            $requestIdDdlLogPath = 'logs\mysql-denied-request-id-ddl-evidence.jsonl'
            $requestIdDdlEvent = [ordered]@{
                userHost = 'sm_t21_a1b2c3d4[sm_t21_a1b2c3d4] @ localhost [127.0.0.1]'
                commandType = 'Query'
                argument = 'ALTER TABLE syncmoney_audit_log ADD COLUMN request_id VARCHAR(36)'
            }
            $copy.deniedDdlEvidence = [ordered]@{
                source = 'mysql.general_log'
                databaseName = 'syncmoney_t21_a1b2c3d4'
                runtimeUser = 'sm_t21_a1b2c3d4'
                eventCount = 1
                logPath = $requestIdDdlLogPath
            }
            Set-T21MySqlDeniedRequestIdDdlEvent $copy $requestIdDdlEvent
        }

        $platformSoftware = switch ($endpoint.software) {
            'paper' { 'Paper' }
            'folia' { 'Folia' }
            'canvas' { 'Canvas' }
            default { throw "Unsupported fixture platform software '$($endpoint.software)'." }
        }
        $platformLine = '[07:43:59] [Server thread/INFO]: [Bootstrap] Loading {2} {0}-{1}-main@fixture for Minecraft {0}' -f $endpoint.minecraftVersion, $endpoint.build, $platformSoftware
        $platformLog = @(
            $platformLine
            '[07:44:00] [Server thread/INFO]: Loading server plugin Syncmoney v1.4.0-alpha.1'
            '[07:44:00] [Server thread/INFO]: [Syncmoney] Syncmoney audit schema version: current 10, target 11'
            '[07:44:00] [Server thread/SEVERE]: [Syncmoney] Syncmoney initialization failed; rolling back initialized owners'
            'java.lang.IllegalStateException: Failed audit schema migration to version 11; version metadata was not advanced past 10'
            $(if ($databaseProduct -ceq 'PostgreSQL') {
                'Caused by: org.postgresql.util.PSQLException: ERROR: must be owner of table syncmoney_audit_log'
            } else {
                "Caused by: java.sql.SQLException: ALTER command denied to user 'sm_t21_fixture'@'127.0.0.1' for table 'syncmoney_audit_log'"
            })
            '[07:44:01] [Server thread/INFO]: [noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Shutdown initiated...'
            '[07:44:01] [Server thread/INFO]: [noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Shutdown completed.'
            '[07:44:02] [Server thread/INFO]: [Syncmoney] Disabling Syncmoney v1.4.0-alpha.1'
        )
        $platformLogPath = Join-Path $v11RunRoot 'logs\platform-candidate-startup-failure.txt'
        [IO.File]::WriteAllLines($platformLogPath, [string[]]$platformLog, [Text.UTF8Encoding]::new($false))
        $logInfo = Get-Item -LiteralPath $platformLogPath
        $logHash = (Get-FileHash -LiteralPath $platformLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $copy.platformLogs[0].bytes = [long]$logInfo.Length
        $copy.platformLogs[0].sha256 = $logHash
        $copy.logs['logs\platform-candidate-startup-failure.txt'].bytes = [long]$logInfo.Length
        $copy.logs['logs\platform-candidate-startup-failure.txt'].sha256 = $logHash
        return $copy
    }

    $approvedV11Endpoints = @('Minimum', 'Maximum', 'CurrentMaximum', 'MinimumFolia', 'MaximumFolia', 'CurrentMaximumFolia', 'MinimumCanvas', 'MaximumCanvas', 'CurrentMaximumCanvas', 'CurrentStable')
    $allV11Products = @('MySQL', 'MariaDB', 'PostgreSQL')
    $approvedV11Count = 0
    foreach ($approvedEndpoint in $approvedV11Endpoints) {
        foreach ($databaseProduct in $allV11Products) {
            if (-not (Test-T21RequiredV11DdlFailureTarget -DatabaseProduct $databaseProduct -PlatformEndpoint $approvedEndpoint)) {
                throw ('The required-v11 DDL failure target guard rejected approved target ' + $databaseProduct + '/' + $approvedEndpoint)
            }
            $approvedV11Count++
        }
    }
    if ($approvedV11Count -ne 30) { throw "Expected 30 approved required-v11 product/endpoint combinations; observed $approvedV11Count." }
    Write-Output 'PASS: admit the 30 historical/current maximum, minimum, and supplemental Paper product/endpoint combinations'

    $ddlFailureReport = New-T21RequiredV11DdlFailureReportFixture
    if (-not (Test-T21StartupFailureReportFixture $ddlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete minimum-Paper MySQL required-v11-DDL permission-failure report was rejected.'
    }
    Write-Output 'PASS: accept v11 DDL permission failure only with v10 schema/version preservation and observed privilege boundary'
    $maximumPaperEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint Maximum
    $maximumPaperDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'MySQL' '8.0.42' 'Maximum'
    if (-not (Test-T21StartupFailureReportFixture $maximumPaperDdlFailureReport $maximumPaperEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Maximum -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete maximum-Paper MySQL required-v11-DDL permission-failure report was rejected.'
    }
    Write-Output 'PASS: accept a complete maximum-Paper MySQL v11 DDL-denial report with the exact pinned runtime identity'
    $minimumFoliaEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint MinimumFolia
    $minimumFoliaDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'MySQL' '8.0.42' 'MinimumFolia'
    if (-not (Test-T21StartupFailureReportFixture $minimumFoliaDdlFailureReport $minimumFoliaEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MinimumFolia -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete minimum-Folia MySQL v11 DDL-denial report was rejected.'
    }
    Write-Output 'PASS: accept a complete minimum-Folia MySQL v11 DDL-denial report with the exact pinned runtime identity'
    $minimumFoliaMariaDbDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'MariaDB' '12.3.3-MariaDB' 'MinimumFolia'
    if (-not (Test-T21StartupFailureReportFixture $minimumFoliaMariaDbDdlFailureReport $minimumFoliaEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint MinimumFolia -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete minimum-Folia MariaDB v11-DDL permission-failure report was rejected.'
    }
    Write-Output 'PASS: accept a complete minimum-Folia MariaDB v11 DDL-denial report with the exact pinned runtime identity'
    $minimumFoliaPostgreSqlDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'PostgreSQL' '18.1' 'MinimumFolia'
    if (-not (Test-T21StartupFailureReportFixture $minimumFoliaPostgreSqlDdlFailureReport $minimumFoliaEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint MinimumFolia -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete minimum-Folia PostgreSQL v11-DDL permission-failure report was rejected.'
    }
    Write-Output 'PASS: accept a complete minimum-Folia PostgreSQL v11 DDL-denial report with the exact pinned runtime identity and non-owner app role'
    $maximumFoliaEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint MaximumFolia
    $maximumFoliaDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'MySQL' '8.0.42' 'MaximumFolia'
    if (-not (Test-T21StartupFailureReportFixture $maximumFoliaDdlFailureReport $maximumFoliaEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MaximumFolia -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete maximum-Folia MySQL v11-DDL permission-failure report was rejected.'
    }
    Write-Output 'PASS: accept a complete maximum-Folia MySQL v11 DDL-denial report with the exact pinned runtime identity'
    $maximumFoliaMariaDbDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'MariaDB' '12.3.3-MariaDB' 'MaximumFolia'
    if (-not (Test-T21StartupFailureReportFixture $maximumFoliaMariaDbDdlFailureReport $maximumFoliaEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint MaximumFolia -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete maximum-Folia MariaDB v11-DDL permission-failure report was rejected.'
    }
    Write-Output 'PASS: accept a complete maximum-Folia MariaDB v11 DDL-denial report with the exact pinned runtime identity'
    $maximumFoliaPostgreSqlDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'PostgreSQL' '18.1' 'MaximumFolia'
    if (-not (Test-T21StartupFailureReportFixture $maximumFoliaPostgreSqlDdlFailureReport $maximumFoliaEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint MaximumFolia -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete maximum-Folia PostgreSQL v11-DDL permission-failure report was rejected.'
    }
    Write-Output 'PASS: accept a complete maximum-Folia PostgreSQL v11 DDL-denial report with the exact pinned runtime identity and non-owner app role'
    $minimumCanvasEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint MinimumCanvas
    $minimumCanvasDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'MySQL' '8.0.42' 'MinimumCanvas'
    if (-not (Test-T21StartupFailureReportFixture $minimumCanvasDdlFailureReport $minimumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MinimumCanvas -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete minimum-Canvas MySQL v11 DDL-denial report was rejected.'
    }
    Write-Output 'PASS: accept a complete minimum-Canvas MySQL v11 DDL-denial report with the exact pinned runtime identity'
    $minimumCanvasMariaDbDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'MariaDB' '12.3.3-MariaDB' 'MinimumCanvas'
    if (-not (Test-T21StartupFailureReportFixture $minimumCanvasMariaDbDdlFailureReport $minimumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint MinimumCanvas -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete minimum-Canvas MariaDB v11 DDL-denial report was rejected.'
    }
    Write-Output 'PASS: accept a complete minimum-Canvas MariaDB v11 DDL-denial report with the exact pinned runtime identity'
    $minimumCanvasPostgreSqlDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'PostgreSQL' '18.1' 'MinimumCanvas'
    if (-not (Test-T21StartupFailureReportFixture $minimumCanvasPostgreSqlDdlFailureReport $minimumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint MinimumCanvas -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete minimum-Canvas PostgreSQL v11 DDL-denial report was rejected.'
    }
    Write-Output 'PASS: accept a complete minimum-Canvas PostgreSQL v11 DDL-denial report with the exact pinned runtime identity and non-owner app role'
    $maximumCanvasEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint MaximumCanvas
    $maximumCanvasDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'MySQL' '8.0.42' 'MaximumCanvas'
    if (-not (Test-T21StartupFailureReportFixture $maximumCanvasDdlFailureReport $maximumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MaximumCanvas -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete maximum-Canvas MySQL v11 DDL-denial report was rejected.'
    }
    Write-Output 'PASS: accept a complete maximum-Canvas MySQL v11 DDL-denial report with the exact pinned runtime identity'
    $maximumCanvasMariaDbDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'MariaDB' '12.3.3-MariaDB' 'MaximumCanvas'
    if (-not (Test-T21StartupFailureReportFixture $maximumCanvasMariaDbDdlFailureReport $maximumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint MaximumCanvas -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete maximum-Canvas MariaDB v11 DDL-denial report was rejected.'
    }
    Write-Output 'PASS: accept a complete maximum-Canvas MariaDB v11 DDL-denial report with the exact pinned runtime identity'
    $maximumCanvasPostgreSqlDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'PostgreSQL' '18.1' 'MaximumCanvas'
    if (-not (Test-T21StartupFailureReportFixture $maximumCanvasPostgreSqlDdlFailureReport $maximumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint MaximumCanvas -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete maximum-Canvas PostgreSQL v11 DDL-denial report was rejected.'
    }
    Write-Output 'PASS: accept a complete maximum-Canvas PostgreSQL v11 DDL-denial report with the exact pinned runtime identity and non-owner app role'
    $currentStableEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint CurrentStable
    foreach ($currentStableProduct in $allV11Products) {
        $currentStableVersion = switch ($currentStableProduct) {
            'MariaDB' { '12.3.3-MariaDB' }
            'PostgreSQL' { '18.1' }
            default { '8.0.42' }
        }
        $currentStableReport = New-T21RequiredV11DdlFailureReportFixture $currentStableProduct $currentStableVersion 'CurrentStable'
        if (-not (Test-T21StartupFailureReportFixture $currentStableReport $currentStableEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
                -DatabaseProduct $currentStableProduct -PlatformEndpoint CurrentStable -FailureScenario RequiredV11DdlPermissionDenied)) {
            throw "The complete current-stable Paper $currentStableProduct v11 DDL-denial report was rejected."
        }
        Write-Output "PASS: accept current-stable Paper $currentStableProduct v11 DDL-denial evidence with the exact runtime identity"
    }
    $currentStableMySqlReport = New-T21RequiredV11DdlFailureReportFixture 'MySQL' '8.0.42' 'CurrentStable'
    if (Test-T21StartupFailureReportFixture $currentStableMySqlReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint CurrentStable -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The current-stable Paper v11 DDL-denial report accepted the minimum-Paper server SHA-256.'
    }
    Write-Output 'PASS: reject current-stable Paper v11 DDL-denial evidence with the minimum-Paper server SHA-256'
    $maximumPaperMariaDbEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint Maximum
    $maximumPaperMariaDbDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'MariaDB' '12.3.3-MariaDB' 'Maximum'
    if (-not (Test-T21StartupFailureReportFixture $maximumPaperMariaDbDdlFailureReport $maximumPaperMariaDbEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint Maximum -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete maximum-Paper MariaDB v11 DDL-denial report was rejected.'
    }
    Write-Output 'PASS: accept a complete maximum-Paper MariaDB v11 DDL-denial report with the exact pinned runtime identity'
    $maximumPaperPostgreSqlEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint Maximum
    $maximumPaperPostgreSqlDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'PostgreSQL' '18.1' 'Maximum'
    if (-not (Test-T21StartupFailureReportFixture $maximumPaperPostgreSqlDdlFailureReport $maximumPaperPostgreSqlEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint Maximum -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete maximum-Paper PostgreSQL v11 DDL-denial report was rejected.'
    }
    Write-Output 'PASS: accept a complete maximum-Paper PostgreSQL v11 DDL-denial report with the exact pinned runtime identity'
    if (Test-T21StartupFailureReportFixture $maximumPaperPostgreSqlDdlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint Maximum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The maximum-Paper PostgreSQL v11 DDL-denial report accepted the minimum-Paper server SHA-256.'
    }
    Write-Output 'PASS: reject maximum-Paper PostgreSQL v11 DDL-denial evidence with the minimum-Paper server SHA-256'
    $maximumPaperWrongExpectedHashReport = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $maximumPaperPostgreSqlDdlFailureReport -Depth 30 -Compress) -AsHashtable -ErrorAction Stop
    $maximumPaperWrongExpectedHashReport.server.sha256 = 'd' * 64
    if (Test-T21StartupFailureReportFixture $maximumPaperWrongExpectedHashReport ('d' * 64) ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint Maximum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The maximum-Paper PostgreSQL v11 DDL-denial report accepted a caller-supplied server SHA-256 that was not the selected endpoint pin.'
    }
    Write-Output 'PASS: reject a matching but unpinned maximum-Paper PostgreSQL server SHA-256'
    $maximumPaperMariaDbDdlFailureMissingEvidence = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $maximumPaperMariaDbDdlFailureReport -Depth 30 -Compress) -AsHashtable -ErrorAction Stop
    $maximumPaperMariaDbDdlFailureMissingEvidence.Remove('deniedDdlEvidence')
    if (Test-T21StartupFailureReportFixture $maximumPaperMariaDbDdlFailureMissingEvidence $maximumPaperMariaDbEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint Maximum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The maximum-Paper MariaDB predicate accepted a generic ALTER-denied log without the denied SQL statement.'
    }
    $maximumPaperMariaDbEvidencePath = Join-Path $maximumPaperMariaDbDdlFailureReport.runDirectory 'logs\mysql-denied-request-id-ddl-evidence.jsonl'
    $maximumPaperMariaDbEvidenceEvent = Get-Content -LiteralPath $maximumPaperMariaDbEvidencePath -Raw | ConvertFrom-Json -AsHashtable
    $maximumPaperMariaDbEvidenceEvent.argument = 'ALTER TABLE syncmoney_audit_log ADD COLUMN sequence BIGINT'
    Set-T21MySqlDeniedRequestIdDdlEvent $maximumPaperMariaDbDdlFailureReport $maximumPaperMariaDbEvidenceEvent
    if (Test-T21StartupFailureReportFixture $maximumPaperMariaDbDdlFailureReport $maximumPaperMariaDbEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint Maximum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The maximum-Paper MariaDB predicate accepted a denied ALTER for a column other than request_id.'
    }
    $maximumPaperMariaDbEvidenceEvent.argument = 'ALTER TABLE syncmoney_audit_log ADD COLUMN request_id VARCHAR(36)'
    $maximumPaperMariaDbEvidenceEvent.userHost = 'sm_t21_other[sm_t21_other] @ localhost [127.0.0.1]'
    Set-T21MySqlDeniedRequestIdDdlEvent $maximumPaperMariaDbDdlFailureReport $maximumPaperMariaDbEvidenceEvent
    if (Test-T21StartupFailureReportFixture $maximumPaperMariaDbDdlFailureReport $maximumPaperMariaDbEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint Maximum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The maximum-Paper MariaDB predicate accepted request_id DDL evidence from a different SQL account.'
    }
    $maximumPaperMariaDbEvidenceEvent.userHost = 'sm_t21_a1b2c3d4[sm_t21_a1b2c3d4] @ remote.example [203.0.113.1]'
    Set-T21MySqlDeniedRequestIdDdlEvent $maximumPaperMariaDbDdlFailureReport $maximumPaperMariaDbEvidenceEvent
    if (Test-T21StartupFailureReportFixture $maximumPaperMariaDbDdlFailureReport $maximumPaperMariaDbEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint Maximum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The maximum-Paper MariaDB predicate accepted request_id DDL evidence from an unexpected host and IP.'
    }
    $maximumPaperMariaDbEvidenceEvent.userHost = 'sm_t21_a1b2c3d4[sm_t21_a1b2c3d4] @ localhost [192.0.2.1]'
    Set-T21MySqlDeniedRequestIdDdlEvent $maximumPaperMariaDbDdlFailureReport $maximumPaperMariaDbEvidenceEvent
    if (Test-T21StartupFailureReportFixture $maximumPaperMariaDbDdlFailureReport $maximumPaperMariaDbEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint Maximum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The maximum-Paper MariaDB predicate accepted request_id DDL evidence with a non-loopback source IP.'
    }
    Write-Output 'PASS: require exact request_id ALTER event and candidate account for maximum-Paper MariaDB'
    $maximumPaperDdlFailureReportMissingSqlEvidence = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $maximumPaperDdlFailureReport -Depth 30 -Compress) -AsHashtable -ErrorAction Stop
    $maximumPaperDdlFailureReportMissingSqlEvidence.Remove('deniedDdlEvidence')
    if (Test-T21StartupFailureReportFixture $maximumPaperDdlFailureReportMissingSqlEvidence $maximumPaperEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Maximum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The maximum-Paper MySQL predicate accepted a generic ALTER-denied log without the denied SQL statement.'
    }
    Write-Output 'PASS: require retained SQL evidence for the maximum-Paper MySQL v11 ALTER denial'
    $maximumRequestIdEvidencePath = Join-Path $maximumPaperDdlFailureReport.runDirectory 'logs\mysql-denied-request-id-ddl-evidence.jsonl'
    $maximumRequestIdEvidenceEvent = Get-Content -LiteralPath $maximumRequestIdEvidencePath -Raw | ConvertFrom-Json -AsHashtable
    $maximumRequestIdEvidenceEvent.argument = 'ALTER TABLE syncmoney_audit_log ADD COLUMN sequence BIGINT'
    Set-T21MySqlDeniedRequestIdDdlEvent $maximumPaperDdlFailureReport $maximumRequestIdEvidenceEvent
    if (Test-T21StartupFailureReportFixture $maximumPaperDdlFailureReport $maximumPaperEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Maximum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The maximum-Paper MySQL predicate accepted a denied ALTER for a column other than request_id.'
    }
    $maximumRequestIdEvidenceEvent.argument = 'ALTER TABLE syncmoney_audit_log ADD COLUMN request_id VARCHAR(36)'
    Set-T21MySqlDeniedRequestIdDdlEvent $maximumPaperDdlFailureReport $maximumRequestIdEvidenceEvent
    $maximumRequestIdEvidenceEvent.userHost = 'sm_t21_other[sm_t21_other] @ localhost [127.0.0.1]'
    Set-T21MySqlDeniedRequestIdDdlEvent $maximumPaperDdlFailureReport $maximumRequestIdEvidenceEvent
    if (Test-T21StartupFailureReportFixture $maximumPaperDdlFailureReport $maximumPaperEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Maximum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The maximum-Paper MySQL predicate accepted request_id DDL evidence from a different SQL account.'
    }
    $maximumRequestIdEvidenceEvent.userHost = 'sm_t21_a1b2c3d4[sm_t21_a1b2c3d4] @ remote.example [203.0.113.1]'
    Set-T21MySqlDeniedRequestIdDdlEvent $maximumPaperDdlFailureReport $maximumRequestIdEvidenceEvent
    if (Test-T21StartupFailureReportFixture $maximumPaperDdlFailureReport $maximumPaperEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Maximum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The maximum-Paper MySQL predicate accepted request_id DDL evidence from an unexpected host and IP.'
    }
    $maximumRequestIdEvidenceEvent.userHost = 'sm_t21_a1b2c3d4[sm_t21_a1b2c3d4] @ localhost [192.0.2.1]'
    Set-T21MySqlDeniedRequestIdDdlEvent $maximumPaperDdlFailureReport $maximumRequestIdEvidenceEvent
    if (Test-T21StartupFailureReportFixture $maximumPaperDdlFailureReport $maximumPaperEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Maximum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The maximum-Paper MySQL predicate accepted request_id DDL evidence with a non-loopback source IP.'
    }
    $maximumRequestIdEvidenceEvent.userHost = 'sm_t21_a1b2c3d4[sm_t21_a1b2c3d4] @ localhost [127.0.0.1]'
    Set-T21MySqlDeniedRequestIdDdlEvent $maximumPaperDdlFailureReport $maximumRequestIdEvidenceEvent
    Write-Output 'PASS: require the exact request_id ALTER event from the candidate SQL account'
    if (Test-T21StartupFailureReportFixture $maximumPaperDdlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Maximum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The maximum-Paper MySQL report predicate accepted the minimum-Paper server SHA-256.'
    }
    Write-Output 'PASS: reject maximum-Paper MySQL v11 DDL-denial evidence with the minimum-Paper server SHA-256'
    $fixtureMariaDbHash = '*' + ('A' * 40)
    $rawMariaDbGrant = 'GRANT USAGE ON *.* TO `sm_t21_a1b2c3d4`@`127.0.0.1` IDENTIFIED BY PASSWORD ' + "'$fixtureMariaDbHash'"
    $safeMariaDbGrant = @(Protect-T21MariaDbGrantLines -GrantLines @($rawMariaDbGrant))
    if ($safeMariaDbGrant.Count -ne 1 -or
        $safeMariaDbGrant[0] -cne 'GRANT USAGE ON *.* TO `sm_t21_a1b2c3d4`@`127.0.0.1` IDENTIFIED BY PASSWORD ''<redacted>''' -or
        $safeMariaDbGrant[0].Contains($fixtureMariaDbHash)) {
        throw 'MariaDB SHOW GRANTS redaction must preserve the account/grant while removing its authentication hash.'
    }
    Write-Output 'PASS: redact MariaDB SHOW GRANTS authentication hashes before retaining logs'
    $mariaDbGrantSet = @(
        $safeMariaDbGrant[0]
        'GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, REFERENCES, INDEX, EXECUTE, TRIGGER, DELETE HISTORY, SHOW CREATE ROUTINE ON `syncmoney_t21_a1b2c3d4`.* TO `sm_t21_a1b2c3d4`@`127.0.0.1`'
    )
    if (-not (Test-T21MySqlCreateWithoutAlterGrantSet $mariaDbGrantSet 'syncmoney_t21_a1b2c3d4' 'sm_t21_a1b2c3d4' '127.0.0.1' 'MariaDB')) {
        throw 'The MariaDB grant predicate rejected the redacted global authentication suffix or dialect-specific schema privileges.'
    }
    $schemaGrantWithAuthentication = [string[]]@(
        'GRANT USAGE ON *.* TO `sm_t21_a1b2c3d4`@`127.0.0.1`'
        ('GRANT SELECT, INSERT, CREATE ON `syncmoney_t21_a1b2c3d4`.* TO `sm_t21_a1b2c3d4`@`127.0.0.1` IDENTIFIED BY PASSWORD ''<redacted>''')
    )
    if ((Test-T21MySqlCreateWithoutAlterGrantSet $schemaGrantWithAuthentication 'syncmoney_t21_a1b2c3d4' 'sm_t21_a1b2c3d4' '127.0.0.1' 'MariaDB') -or
        (Test-T21MySqlCreateWithoutAlterGrantSet $mariaDbGrantSet 'syncmoney_t21_a1b2c3d4' 'sm_t21_a1b2c3d4' '127.0.0.1' 'MySQL')) {
        throw 'The grant predicate accepted an authentication suffix on a schema grant or for MySQL.'
    }
    Write-Output 'PASS: allow the redacted authentication suffix only on MariaDB global USAGE grants'
    $malformedMariaDbGrantRejected = $false
    $malformedMariaDbGrantError = $null
    try {
        $unknownFormatHash = 'NOT-A-MARIA-DB-HASH'
        $null = Protect-T21MariaDbGrantLines -GrantLines @("GRANT USAGE ON *.* TO ``sm_t21_a1b2c3d4``@``127.0.0.1`` IDENTIFIED BY PASSWORD '$unknownFormatHash'")
    } catch {
        $malformedMariaDbGrantRejected = $true
        $malformedMariaDbGrantError = $_.Exception.Message
    }
    if (-not $malformedMariaDbGrantRejected -or $malformedMariaDbGrantError.Contains($unknownFormatHash)) {
        throw 'MariaDB grant redaction must reject unrecognized authentication formats without exposing their value in the error.'
    }
    $unrelatedMariaDbGrantRejected = $false
    try {
        $null = Protect-T21MariaDbGrantLines -GrantLines @("GRANT SELECT ON ``syncmoney_t21_a1b2c3d4``.* TO ``sm_t21_a1b2c3d4``@``127.0.0.1`` IDENTIFIED BY PASSWORD '$fixtureMariaDbHash'")
    } catch {
        $unrelatedMariaDbGrantRejected = $true
    }
    if (-not $unrelatedMariaDbGrantRejected) { throw 'MariaDB grant redaction must reject authentication clauses outside the observed global USAGE line.' }
    Write-Output 'PASS: reject unknown or unrelated MariaDB SHOW GRANTS authentication clauses without exposing them'
    $mariaDbDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'MariaDB' '12.3.3-MariaDB'
    if (-not (Test-T21StartupFailureReportFixture $mariaDbDdlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete minimum-Paper MariaDB 12.3.3 required-v11-DDL permission-failure report was rejected.'
    }
    Write-Output 'PASS: accept minimum-Paper MariaDB 12.3.3 v11 DDL denial with exact product and v10 preservation'
    $mariaDbDdlFailureReport.databaseDdlPrivileges.product = 'MySQL'
    if (Test-T21StartupFailureReportFixture $mariaDbDdlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The minimum-Paper MariaDB v11-DDL predicate accepted a MySQL product label.'
    }
    Write-Output 'PASS: reject MariaDB v11 DDL denial evidence with a MySQL product label'
    $postgresDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'PostgreSQL' '18.1'
    if (-not (Test-T21StartupFailureReportFixture $postgresDdlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The complete minimum-Paper PostgreSQL 18.1 required-v11-DDL owner-boundary report was rejected.'
    }
    Write-Output 'PASS: accept minimum-Paper PostgreSQL 18.1 v11 DDL denial with a non-owner app role and preserved v10 state'
    $postgresDdlFailureReport.databaseDdlPrivileges.roleSuperuser = $true
    if (Test-T21StartupFailureReportFixture $postgresDdlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The PostgreSQL owner-boundary predicate accepted a superuser app role.'
    }
    Write-Output 'PASS: reject a PostgreSQL DDL-denial report whose app role is a superuser'
    $postgresDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'PostgreSQL' '18.1'
    $postgresDdlFailureReport.databaseDdlPrivileges.roleMemberOfAuditOwner = $true
    if (Test-T21StartupFailureReportFixture $postgresDdlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The PostgreSQL owner-boundary predicate accepted membership in the audit-table owner role.'
    }
    Write-Output 'PASS: reject PostgreSQL DDL-denial evidence when the app inherits audit-table ownership'
    $postgresDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'PostgreSQL' '18.1'
    $postgresDdlFailureReport.databaseDdlPrivileges.schemaCreateGranted = $false
    if (Test-T21StartupFailureReportFixture $postgresDdlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The PostgreSQL owner-boundary predicate accepted a role that cannot create the startup-created players table.'
    }
    Write-Output 'PASS: require PostgreSQL schema CREATE for startup while the audit table remains owner-protected'
    $postgresDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'PostgreSQL' '18.1'
    $postgresDdlFailureReport.databaseDdlPrivileges.tableOwnedByGrantee = $true
    if (Test-T21StartupFailureReportFixture $postgresDdlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The PostgreSQL owner-boundary predicate accepted an app role that owns the audit table.'
    }
    Write-Output 'PASS: reject PostgreSQL v11 DDL evidence when the application role owns the audit table'
    $postgresDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'PostgreSQL' '18.1'
    $postgresDdlFailureReport.databaseDdlPrivileges.tableDeleteGranted = $false
    if (Test-T21StartupFailureReportFixture $postgresDdlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The PostgreSQL owner-boundary predicate accepted evidence missing the required app-table DML privileges.'
    }
    Write-Output 'PASS: require app-table DML access separately from PostgreSQL DDL ownership'
    $postgresDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'PostgreSQL' '18.1'
    $postgresEvidenceJson = [string]$postgresDdlFailureReport.databaseDdlPrivileges.evidenceJson
    $duplicateEvidenceJson = $postgresEvidenceJson.Replace('"databaseOwner":"postgres"', '"databaseOwner":"sm_t21_a1b2c3d4","databaseOwner":"postgres"', [StringComparison]::Ordinal)
    $postgresDdlFailureReport.databaseDdlPrivileges.evidenceJson = $duplicateEvidenceJson
    Set-T21PostgreSqlPrivilegeFixtureEvidence $postgresDdlFailureReport
    if (Test-T21StartupFailureReportFixture $postgresDdlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The PostgreSQL privilege-evidence predicate accepted duplicate JSON properties.'
    }
    Write-Output 'PASS: reject duplicate properties in PostgreSQL DDL-owner evidence even when the hashed log agrees'
    $postgresDdlFailureReport = New-T21RequiredV11DdlFailureReportFixture 'PostgreSQL' '18.1'
    $postgresPrivilegeLog = Join-Path $postgresDdlFailureReport.runDirectory 'logs\postgresql-audit-ddl-privileges.txt'
    [IO.File]::WriteAllText($postgresPrivilegeLog, '{"databaseOwner":"app"}' + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    $postgresPrivilegeLogInfo = Get-Item -LiteralPath $postgresPrivilegeLog
    $postgresDdlFailureReport.logs['logs\postgresql-audit-ddl-privileges.txt'].bytes = [long]$postgresPrivilegeLogInfo.Length
    $postgresDdlFailureReport.logs['logs\postgresql-audit-ddl-privileges.txt'].sha256 = (Get-FileHash -LiteralPath $postgresPrivilegeLog -Algorithm SHA256).Hash.ToLowerInvariant()
    if (Test-T21StartupFailureReportFixture $postgresDdlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The PostgreSQL DDL-owner report accepted privilege fields that disagreed with its retained query log.'
    }
    Write-Output 'PASS: bind structured PostgreSQL owner privileges to the retained database query log'
    $jsonNumericField = ConvertFrom-Json -InputObject '1.25' -ErrorAction Stop
    if (-not (Test-T21JsonNumber $jsonNumericField) -or
        (Test-T21JsonNumber '1.25') -or
        (Test-T21JsonNumber $true)) {
        throw 'The v11 sentinel JSON-number predicate must accept JSON numeric values and reject strings and booleans.'
    }
    Write-Output 'PASS: classify JSON numbers separately from strings and booleans'
    $validGrants127 = [string[]]$ddlFailureReport.databaseDdlPrivileges.grants.Clone()
    $validGrantsLocal = [string[]]$ddlFailureReport.databaseDdlPrivileges.localGrants.Clone()

    $ddlFailureReport.configuration.databaseNameObserved = 'syncmoney_t21_ff001122'
    if (Test-T21StartupFailureReportFixture $ddlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The required-v11-DDL report accepted a databaseNameObserved value that disagreed with the configured, grant-checked database.'
    }
    $ddlFailureReport.configuration.databaseNameObserved = 'syncmoney_t21_a1b2c3d4'
    Write-Output 'PASS: require the observed config database name to match the schema protected by the app-account grants'

    $validRowsJson = [string]$ddlFailureReport.schemaStateBefore.auditRowsJson
    $alteredRowsJson = $validRowsJson.Replace('"player_name":"T21Fixture"', '"player_name":"ForgedFixture"', [StringComparison]::Ordinal)
    Set-T21MySqlAuditRowsFixtureJson $ddlFailureReport $alteredRowsJson
    if (Test-T21StartupFailureReportFixture $ddlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The required-v11-DDL report accepted an unchanged row whose fixture payload differed from the exact seeded sentinel.'
    }
    Set-T21MySqlAuditRowsFixtureJson $ddlFailureReport $validRowsJson
    Write-Output 'PASS: pin the complete v10 sentinel row payload in the pre-start report predicate'

    foreach ($moneyField in @('amount', 'balance_after')) {
        $validMoneyValue = if ($moneyField -ceq 'amount') { '1.25' } else { '99.99' }
        $stringMoneyRowsJson = $validRowsJson.Replace(('"' + $moneyField + '":' + $validMoneyValue), ('"' + $moneyField + '":"' + $validMoneyValue + '"'), [StringComparison]::Ordinal)
        Set-T21MySqlAuditRowsFixtureJson $ddlFailureReport $stringMoneyRowsJson
        if (Test-T21StartupFailureReportFixture $ddlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
                -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
            throw "The required-v11-DDL report accepted a JSON string in the sentinel '$moneyField' field."
        }
        Write-Output "PASS: reject a JSON string in the sentinel '$moneyField' numeric field"
    }
    foreach ($moneyField in @('amount', 'balance_after')) {
        $validMoneyValue = if ($moneyField -ceq 'amount') { '1.25' } else { '99.99' }
        $nearbyMoneyValue = if ($moneyField -ceq 'amount') { '1.2500000000000001' } else { '99.9900000000000001' }
        $nearbyMoneyRowsJson = $validRowsJson.Replace(('"' + $moneyField + '":' + $validMoneyValue), ('"' + $moneyField + '":' + $nearbyMoneyValue), [StringComparison]::Ordinal)
        Set-T21MySqlAuditRowsFixtureJson $ddlFailureReport $nearbyMoneyRowsJson
        if (Test-T21StartupFailureReportFixture $ddlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
                -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
            throw "The required-v11-DDL report accepted a nearby JSON number in the sentinel '$moneyField' field."
        }
        Write-Output "PASS: reject a nearby JSON number in the sentinel '$moneyField' numeric field"
    }
    foreach ($moneyField in @('amount', 'balance_after')) {
        $validMoneyValue = if ($moneyField -ceq 'amount') { '1.25' } else { '99.99' }
        $longNearbyMoneyValue = if ($moneyField -ceq 'amount') { '1.2500000000000000000000000000000000000001' } else { '99.99000000000000000000000000001' }
        $longNearbyRowsJson = $validRowsJson.Replace(('"' + $moneyField + '":' + $validMoneyValue), ('"' + $moneyField + '":' + $longNearbyMoneyValue), [StringComparison]::Ordinal)
        Set-T21MySqlAuditRowsFixtureJson $ddlFailureReport $longNearbyRowsJson
        if (Test-T21StartupFailureReportFixture $ddlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
                -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
            throw "The required-v11-DDL report accepted a precision-overflow JSON number in the sentinel '$moneyField' field."
        }
        Write-Output "PASS: reject a precision-overflow JSON number in the sentinel '$moneyField' numeric field"
    }
    $equivalentMoneyRowsJson = $validRowsJson.Replace('"amount":1.25', '"amount":125e-2', [StringComparison]::Ordinal).
        Replace('"balance_after":99.99', '"balance_after":999900e-4', [StringComparison]::Ordinal)
    Set-T21MySqlAuditRowsFixtureJson $ddlFailureReport $equivalentMoneyRowsJson
    if (-not (Test-T21StartupFailureReportFixture $ddlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied)) {
        throw 'The required-v11-DDL report rejected JSON numbers exactly equivalent to the expected decimal sentinels.'
    }
    Write-Output 'PASS: accept exact decimal values expressed with exponent notation and insignificant trailing zeros'
    Set-T21MySqlAuditRowsFixtureJson $ddlFailureReport $validRowsJson

    $duplicateAmountRowsJson = $validRowsJson.Replace('"amount":1.25', '"amount":1.26,"amount":1.25', [StringComparison]::Ordinal)
    Set-T21MySqlAuditRowsFixtureJson $ddlFailureReport $duplicateAmountRowsJson
    if (Test-T21StartupFailureReportFixture $ddlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The required-v11-DDL report accepted duplicate amount properties when the later value matched the sentinel.'
    }
    Write-Output 'PASS: reject duplicate JSON properties even when the later money value matches the sentinel'
    Set-T21MySqlAuditRowsFixtureJson $ddlFailureReport $validRowsJson

    $grantLogPath = Join-Path $ddlFailureReport.runDirectory 'logs\runtime-user-grants-127.txt'
    $grantLogLines = [string[]]$ddlFailureReport.databaseDdlPrivileges.grants.Clone()
    $grantLogLines[1] = $grantLogLines[1].Replace('DELETE, CREATE', 'DELETE, ALTER, CREATE', [StringComparison]::Ordinal)
    [IO.File]::WriteAllLines($grantLogPath, $grantLogLines, [Text.UTF8Encoding]::new($false))
    $grantLogInfo = Get-Item -LiteralPath $grantLogPath
    $ddlFailureReport.logs['logs\runtime-user-grants-127.txt'].bytes = [long]$grantLogInfo.Length
    $ddlFailureReport.logs['logs\runtime-user-grants-127.txt'].sha256 = (Get-FileHash -LiteralPath $grantLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if (Test-T21StartupFailureReportFixture $ddlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The required-v11-DDL report accepted safe structured grants that disagreed with a hashed SHOW GRANTS log containing ALTER.'
    }
    [IO.File]::WriteAllLines($grantLogPath, [string[]]$ddlFailureReport.databaseDdlPrivileges.grants, [Text.UTF8Encoding]::new($false))
    $grantLogInfo = Get-Item -LiteralPath $grantLogPath
    $ddlFailureReport.logs['logs\runtime-user-grants-127.txt'].bytes = [long]$grantLogInfo.Length
    $ddlFailureReport.logs['logs\runtime-user-grants-127.txt'].sha256 = (Get-FileHash -LiteralPath $grantLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output 'PASS: reject hashed SHOW GRANTS logs that disagree with the structured privilege evidence'
    $tick = [char]96
    $wrongHostGrants127 = [string[]]$validGrants127.Clone()
    $wrongHostGrants127[1] = $wrongHostGrants127[1].Replace(([string]$tick + '127.0.0.1' + [string]$tick), ([string]$tick + 'localhost' + [string]$tick), [StringComparison]::Ordinal)
    Set-T21MySqlGrantFixtureLines $ddlFailureReport 'grants' 'logs\runtime-user-grants-127.txt' $wrongHostGrants127
    if (Test-T21StartupFailureReportFixture $ddlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The required-v11-DDL report accepted a target-schema grant for the wrong MySQL account host.'
    }
    Set-T21MySqlGrantFixtureLines $ddlFailureReport 'grants' 'logs\runtime-user-grants-127.txt' $validGrants127
    Write-Output 'PASS: reject a schema grant assigned to the wrong MySQL account host when its structured fields and raw log agree'

    $databaseName = 'syncmoney_t21_a1b2c3d4'
    $databaseUser = 'sm_t21_a1b2c3d4'
    $globalCreateWithSchemaUsage = [string[]]@(
        ('GRANT CREATE ON *.* TO ' + $tick + $databaseUser + $tick + '@' + $tick + '127.0.0.1' + $tick)
        ('GRANT USAGE ON ' + $tick + $databaseName + $tick + '.* TO ' + $tick + $databaseUser + $tick + '@' + $tick + '127.0.0.1' + $tick)
    )
    Set-T21MySqlGrantFixtureLines $ddlFailureReport 'grants' 'logs\runtime-user-grants-127.txt' $globalCreateWithSchemaUsage
    if (Test-T21StartupFailureReportFixture $ddlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The required-v11-DDL report accepted global CREATE paired with only a schema-scoped USAGE grant.'
    }
    Set-T21MySqlGrantFixtureLines $ddlFailureReport 'grants' 'logs\runtime-user-grants-127.txt' $validGrants127
    Set-T21MySqlGrantFixtureLines $ddlFailureReport 'localGrants' 'logs\runtime-user-grants-local.txt' $validGrantsLocal
    Write-Output 'PASS: reject global CREATE when the target schema grant has no CREATE privilege'
    $globalCreateWithUnrelatedSchema = [string[]]@(
        ('GRANT CREATE ON *.* TO ' + $tick + $databaseUser + $tick + '@' + $tick + '127.0.0.1' + $tick)
        ('GRANT USAGE ON ' + $tick + 'unrelated_schema' + $tick + '.* TO ' + $tick + $databaseUser + $tick + '@' + $tick + '127.0.0.1' + $tick)
        ('GRANT USAGE ON ' + $tick + $databaseName + $tick + '.* TO ' + $tick + 'sm_t21_ff001122' + $tick + '@' + $tick + '127.0.0.1' + $tick)
    )
    Set-T21MySqlGrantFixtureLines $ddlFailureReport 'grants' 'logs\runtime-user-grants-127.txt' $globalCreateWithUnrelatedSchema
    if (Test-T21StartupFailureReportFixture $ddlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The required-v11-DDL report accepted global CREATE and unrelated schema grants that split database and grantee evidence across statements.'
    }
    Set-T21MySqlGrantFixtureLines $ddlFailureReport 'grants' 'logs\runtime-user-grants-127.txt' $validGrants127
    Write-Output 'PASS: reject global CREATE and schema grants whose expected database and grantee are split across statements'

    $ddlFailureReport.schemaStateAfter.auditVersion = 11
    if (Test-T21StartupFailureReportFixture $ddlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The required-v11-DDL report accepted an audit schema version advanced after denied DDL.'
    }
    $ddlFailureReport.schemaStateAfter.auditVersion = 10
    Write-Output 'PASS: reject v11 DDL failure evidence that advances the audit version'

    $ddlFailureReport.databaseDdlPrivileges.alterGranted = $true
    if (Test-T21StartupFailureReportFixture $ddlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The required-v11-DDL report accepted an application grant that still includes ALTER.'
    }
    $ddlFailureReport.databaseDdlPrivileges.alterGranted = $false
    Write-Output 'PASS: reject a required-v11-DDL report without a verified ALTER-denied privilege boundary'

    $alterGrantLines = [string[]]$validGrants127.Clone()
    $alterGrantLines[1] = $alterGrantLines[1].Replace('DELETE, CREATE', 'DELETE, ALTER, CREATE', [StringComparison]::Ordinal)
    Set-T21MySqlGrantFixtureLines $ddlFailureReport 'grants' 'logs\runtime-user-grants-127.txt' $alterGrantLines
    if (Test-T21StartupFailureReportFixture $ddlFailureReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario RequiredV11DdlPermissionDenied) {
        throw 'The required-v11-DDL report accepted an ALTER grant whose structured fields and hashed raw log agree.'
    }
    Set-T21MySqlGrantFixtureLines $ddlFailureReport 'grants' 'logs\runtime-user-grants-127.txt' $validGrants127
    Write-Output 'PASS: reject an actual ALTER grant when its structured fields and hashed raw log agree'

    function New-T21PostgreSqlIncompleteAuditReportFixture([string]$platformEndpoint = 'MaximumFolia') {
        $endpoint = Get-T21PlatformEndpoint -PlatformEndpoint $platformEndpoint
        $copy = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $report -Depth 30 -Compress) -AsHashtable -ErrorAction Stop
        $postgresRunRoot = Join-Path $testRoot 'postgres-incomplete-v10-fixture'
        $postgresLogsRoot = Join-Path $postgresRunRoot 'logs'
        $postgresGradleHome = Join-Path $postgresRunRoot '.gradle-user-home'
        New-Item -ItemType Directory -Force -Path $postgresLogsRoot, $postgresGradleHome | Out-Null
        $copy.runDirectory = [IO.Path]::GetFullPath($postgresRunRoot)
        $copy.gradleDaemonCleanup.isolatedUserHome = [IO.Path]::GetFullPath($postgresGradleHome)
        $copy.logs = [ordered]@{}
        $copy.task = 'T21-V03-SYNC-incomplete-v10-audit-table-startup-failure'
        $copy.server = [ordered]@{
            software = $endpoint.software
            minecraftVersion = $endpoint.minecraftVersion
            build = $endpoint.build
            sha256 = $endpoint.sha256
        }
        $copy.configuration.databaseType = 'postgresql'
        $copy.configuration.databaseName = 'syncmoney_t21_a1b2c3d4'
        $copy.configuration.databaseNameObserved = 'syncmoney_t21_a1b2c3d4'
        $copy.sqlBackend = [ordered]@{ product = 'PostgreSQL'; version = '18.1' }
        $copy.startupObservation.runtimeIdentity = [ordered]@{
            software = $endpoint.software
            runtimeVersion = $endpoint.minecraftVersion
            minecraftVersion = $endpoint.minecraftVersion
            build = $endpoint.build
        }
        $copy.startupObservation.expectedFailure = 'incomplete-v10-audit-table'
        $copy.startupObservation.failureMessage = 'Failed audit schema migration to version 10; version metadata was not advanced past 9'
        $copy.startupObservation.failureDetailMarker = 'Required v10 audit columns are missing:'

        $mainVersionRowJson = '{"id":"1","version":1,"applied_at":"2026-10-14T00:00:00","description":"T21 pre-existing main schema"}'
        $auditVersionRowJson = '{"id":"audit","version":9,"applied_at":"2026-10-14T00:00:00","description":"T21 incomplete audit v9 sentinel"}'
        foreach ($schema in @($copy.schemaStateBefore, $copy.schemaStateAfter)) {
            $schema.mainVersion = 1
            $schema.mainVersionRowJson = $mainVersionRowJson
            $schema.auditVersion = 9
            $schema.auditTableExists = $true
            $schema.auditVersionRowJson = $auditVersionRowJson
            $schema.playersTableExists = $true
            $schema.auditRowsJson = '[{"id":"00000000-0000-0000-0000-000000000329"}]'
        }
        $copy.schemaStateBefore.playersTableExists = $false
        $copy.schemaStateBefore.playerRows = $null
        $copy.schemaStateAfter.playerRows = 0

        $columnMetadataFields = @(
            'table_catalog', 'table_schema', 'table_name', 'column_name', 'ordinal_position', 'column_default', 'is_nullable', 'data_type',
            'character_maximum_length', 'character_octet_length', 'numeric_precision', 'numeric_precision_radix', 'numeric_scale',
            'datetime_precision', 'interval_type', 'interval_precision', 'character_set_catalog', 'character_set_schema', 'character_set_name',
            'collation_catalog', 'collation_schema', 'collation_name', 'domain_catalog', 'domain_schema', 'domain_name', 'udt_catalog',
            'udt_schema', 'udt_name', 'scope_catalog', 'scope_schema', 'scope_name', 'maximum_cardinality', 'dtd_identifier',
            'is_self_referencing', 'is_identity', 'identity_generation', 'identity_start', 'identity_increment', 'identity_maximum',
            'identity_minimum', 'identity_cycle', 'is_generated', 'generation_expression', 'is_updatable'
        )
        $column = [ordered]@{}
        foreach ($field in $columnMetadataFields) { $column[$field] = $null }
        $column.table_catalog = 'syncmoney_t21_a1b2c3d4'
        $column.table_schema = 'public'
        $column.table_name = 'syncmoney_audit_log'
        $column.column_name = 'id'
        $column.ordinal_position = 1
        $column.is_nullable = 'NO'
        $column.data_type = 'character varying'
        $column.character_maximum_length = 36
        $column.character_octet_length = 144
        $column.udt_name = 'varchar'
        $column.is_identity = 'NO'
        $column.is_generated = 'NEVER'
        $column.is_updatable = 'YES'
        $columnJson = ConvertTo-Json -InputObject ([object[]]@([pscustomobject]$column)) -Depth 10 -Compress
        $copy.schemaStateBefore.auditColumnsJson = $columnJson
        $copy.schemaStateAfter.auditColumnsJson = $columnJson
        $indexJson = '[{"name":"syncmoney_audit_log_pkey","definition":"CREATE UNIQUE INDEX syncmoney_audit_log_pkey ON public.syncmoney_audit_log USING btree (id)"}]'
        $copy.schemaStateBefore.auditIndexesJson = $indexJson
        $copy.schemaStateAfter.auditIndexesJson = $indexJson

        $platformSoftware = switch ($endpoint.software) {
            'folia' { 'Folia' }
            'canvas' { 'Canvas' }
            default { 'Paper' }
        }
        $platformLine = '[07:43:59] [Server thread/INFO]: [Bootstrap] Loading {2} {0}-{1}-main@fixture for Minecraft {0}' -f $endpoint.minecraftVersion, $endpoint.build, $platformSoftware
        $platformLog = @(
            $platformLine
            '[07:44:00] [Server thread/INFO]: Loading server plugin Syncmoney v1.4.0-alpha.1'
            '[07:44:00] [Server thread/INFO]: [Syncmoney] Syncmoney audit schema version: current 9, target 11'
            '[07:44:00] [Server thread/SEVERE]: [Syncmoney] Syncmoney initialization failed; rolling back initialized owners'
            'java.lang.IllegalStateException: Failed audit schema migration to version 10; version metadata was not advanced past 9'
            'Caused by: java.sql.SQLException: Required v10 audit columns are missing: [timestamp, type, player_uuid]'
            '[07:44:01] [Server thread/INFO]: [noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Shutdown initiated...'
            '[07:44:01] [Server thread/INFO]: [noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Shutdown completed.'
            '[07:44:02] [Server thread/INFO]: [Syncmoney] Disabling Syncmoney v1.4.0-alpha.1'
        )
        $postgresPlatformLogPath = Join-Path $postgresLogsRoot 'platform-candidate-startup-failure.txt'
        [IO.File]::WriteAllLines($postgresPlatformLogPath, [string[]]$platformLog, [Text.UTF8Encoding]::new($false))
        $platformLogInfo = Get-Item -LiteralPath $postgresPlatformLogPath
        $platformLogHash = (Get-FileHash -LiteralPath $postgresPlatformLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $postgresVersionLogPath = Join-Path $postgresLogsRoot 'postgres-server-version.txt'
        [IO.File]::WriteAllText($postgresVersionLogPath, "postgres (PostgreSQL) 18.1`n", [Text.UTF8Encoding]::new($false))
        $postgresVersionLogInfo = Get-Item -LiteralPath $postgresVersionLogPath
        $postgresVersionLogHash = (Get-FileHash -LiteralPath $postgresVersionLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $gradleStopLogPath = Join-Path $postgresLogsRoot 'gradle-daemon-stop.txt'
        [IO.File]::WriteAllText($gradleStopLogPath, "No Gradle daemons are running.`n", [Text.UTF8Encoding]::new($false))
        $gradleStopLogInfo = Get-Item -LiteralPath $gradleStopLogPath
        $gradleStopLogHash = (Get-FileHash -LiteralPath $gradleStopLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $copy.platformLogs[0].bytes = [long]$platformLogInfo.Length
        $copy.platformLogs[0].sha256 = $platformLogHash
        $copy.logs['logs\platform-candidate-startup-failure.txt'] = [ordered]@{ bytes = [long]$platformLogInfo.Length; sha256 = $platformLogHash }
        $copy.logs['logs\postgres-server-version.txt'] = [ordered]@{ bytes = [long]$postgresVersionLogInfo.Length; sha256 = $postgresVersionLogHash }
        $copy.logs['logs\gradle-daemon-stop.txt'] = [ordered]@{ bytes = [long]$gradleStopLogInfo.Length; sha256 = $gradleStopLogHash }
        return $copy
    }

    $postgresMinimumEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint Minimum
    $postgresRowPreservationReport = New-T21PostgreSqlIncompleteAuditReportFixture 'Minimum'
    $postgresRowPreservationReport.task = 'T21-V03-SYNC-incomplete-v10-audit-table-player-row-preservation-PostgreSQL-minimum-Paper'
    $postgresPlayerRowSentinel.updated_at = '2026-10-16 12:34:56.000000'
    $postgresPlayerRowsJson = ConvertTo-Json -InputObject @($postgresPlayerRowSentinel) -Depth 10 -Compress
    $postgresRowPreservationReport.schemaStateBefore.playersTableExists = $true
    $postgresRowPreservationReport.schemaStateBefore.playerRows = 1
    $postgresRowPreservationReport.schemaStateBefore.playerRowsJson = $postgresPlayerRowsJson
    $postgresRowPreservationReport.schemaStateAfter.playersTableExists = $true
    $postgresRowPreservationReport.schemaStateAfter.playerRows = 1
    $postgresRowPreservationReport.schemaStateAfter.playerRowsJson = $postgresPlayerRowsJson
    if (-not (Test-T21StartupFailureReportFixture $postgresRowPreservationReport $postgresMinimumEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTablePlayerRowPreservation)) {
        throw 'The PostgreSQL player-row-preservation report rejected its exact six-digit timestamp snapshot.'
    }
    Write-Output 'PASS: accept PostgreSQL player-row-preservation evidence with exact timestamp precision'

    $postgresFractionMutationReport = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $postgresRowPreservationReport -Depth 30 -Compress) -AsHashtable -ErrorAction Stop
    $fractionMutationRow = [ordered]@{
        player_uuid = '00000000-0000-0000-0000-00000000cafe'
        player_name = 'T21-ROW-SENTINEL'
        balance = '1234.56'
        version = '73'
        last_server = 't21-player-preservation'
        updated_at = '2026-10-16 12:34:56.000001'
    }
    $postgresFractionMutationReport.schemaStateAfter.playerRowsJson = ConvertTo-Json -InputObject @($fractionMutationRow) -Depth 10 -Compress
    if (Test-T21StartupFailureReportFixture $postgresFractionMutationReport $postgresMinimumEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTablePlayerRowPreservation) {
        throw 'The PostgreSQL player-row-preservation report accepted a one-microsecond-only row mutation.'
    }
    Write-Output 'PASS: reject PostgreSQL player-row-preservation evidence with a one-microsecond-only mutation'

    $postgresLegacyMinimumPaperTaskReport = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $postgresRowPreservationReport -Depth 30 -Compress) -AsHashtable -ErrorAction Stop
    $postgresLegacyMinimumPaperTaskReport.task = 'T21-V03-SYNC-incomplete-v10-audit-table-player-row-preservation-MySQL-MinimumPaper'
    if (Test-T21StartupFailureReportFixture $postgresLegacyMinimumPaperTaskReport $postgresMinimumEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTablePlayerRowPreservation) {
        throw 'The PostgreSQL incomplete-v10 report accepted the historical MySQL-only MinimumPaper task label.'
    }
    Write-Output 'PASS: reject the historical MySQL MinimumPaper task label for PostgreSQL evidence'

    $minimumCanvasEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint MinimumCanvas
    $minimumCanvasIncompleteReport = New-T21MySqlIncompleteAuditReportFixture 'MySQL' '8.0.42' 'MinimumCanvas'
    if (-not (Test-T21StartupFailureReportFixture $minimumCanvasIncompleteReport $minimumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MinimumCanvas -FailureScenario IncompleteV10AuditTable)) {
        throw 'The complete minimum-Canvas MySQL incomplete-v10 audit-table failure report was rejected.'
    }
    Write-Output 'PASS: accept minimum-Canvas MySQL incomplete-v10 failure with the exact pinned runtime identity'
    $minimumCanvasIncompleteReport.startupObservation.runtimeIdentity.build = 604
    if (Test-T21StartupFailureReportFixture $minimumCanvasIncompleteReport $minimumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MinimumCanvas -FailureScenario IncompleteV10AuditTable) {
        throw 'The minimum-Canvas MySQL incomplete-v10 report accepted a runtime build that differs from the pinned endpoint.'
    }
    Write-Output 'PASS: reject minimum-Canvas MySQL incomplete-v10 evidence with a mismatched runtime build'

    $maximumCanvasEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint MaximumCanvas
    $maximumCanvasIncompleteReport = New-T21MySqlIncompleteAuditReportFixture 'MySQL' '8.0.42' 'MaximumCanvas'
    if (-not (Test-T21StartupFailureReportFixture $maximumCanvasIncompleteReport $maximumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MaximumCanvas -FailureScenario IncompleteV10AuditTable)) {
        throw 'The complete maximum-Canvas MySQL incomplete-v10 audit-table failure report was rejected.'
    }
    Write-Output 'PASS: accept maximum-Canvas MySQL incomplete-v10 failure with the exact pinned runtime identity'

    $maximumCanvasMariaDbIncompleteReport = New-T21MySqlIncompleteAuditReportFixture 'MariaDB' '12.3.3-MariaDB' 'MaximumCanvas'
    if (-not (Test-T21StartupFailureReportFixture $maximumCanvasMariaDbIncompleteReport $maximumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint MaximumCanvas -FailureScenario IncompleteV10AuditTable)) {
        throw 'The complete maximum-Canvas MariaDB incomplete-v10 audit-table failure report was rejected.'
    }
    Write-Output 'PASS: accept maximum-Canvas MariaDB incomplete-v10 failure with the exact pinned runtime identity'

    $maximumCanvasPostgreSqlIncompleteReport = New-T21PostgreSqlIncompleteAuditReportFixture 'MaximumCanvas'
    if (-not (Test-T21StartupFailureReportFixture $maximumCanvasPostgreSqlIncompleteReport $maximumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint MaximumCanvas -FailureScenario IncompleteV10AuditTable)) {
        throw 'The complete maximum-Canvas PostgreSQL incomplete-v10 audit-table failure report was rejected.'
    }
    Write-Output 'PASS: accept maximum-Canvas PostgreSQL incomplete-v10 failure with the exact pinned runtime identity'
    $maximumCanvasPostgreSqlIncompleteReport.startupObservation.runtimeIdentity.build = 971
    if (Test-T21StartupFailureReportFixture $maximumCanvasPostgreSqlIncompleteReport $maximumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint MaximumCanvas -FailureScenario IncompleteV10AuditTable) {
        throw 'The maximum-Canvas PostgreSQL incomplete-v10 report accepted a runtime build that differs from the pinned endpoint.'
    }
    Write-Output 'PASS: reject maximum-Canvas PostgreSQL incomplete-v10 evidence with a mismatched runtime build'

    $currentStableIncompleteEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint CurrentStable
    $currentStableMySqlIncompleteReport = New-T21MySqlIncompleteAuditReportFixture 'MySQL' '8.0.42' 'CurrentStable'
    if (-not (Test-T21StartupFailureReportFixture $currentStableMySqlIncompleteReport $currentStableIncompleteEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint CurrentStable -FailureScenario IncompleteV10AuditTable)) {
        throw 'The complete current-stable Paper MySQL incomplete-v10 audit-table failure report was rejected.'
    }
    Write-Output 'PASS: accept current-stable Paper MySQL incomplete-v10 failure with the exact pinned runtime identity'

    $currentStableMariaDbIncompleteReport = New-T21MySqlIncompleteAuditReportFixture 'MariaDB' '12.3.3-MariaDB' 'CurrentStable'
    if (-not (Test-T21StartupFailureReportFixture $currentStableMariaDbIncompleteReport $currentStableIncompleteEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint CurrentStable -FailureScenario IncompleteV10AuditTable)) {
        throw 'The complete current-stable Paper MariaDB incomplete-v10 audit-table failure report was rejected.'
    }
    Write-Output 'PASS: accept current-stable Paper MariaDB incomplete-v10 failure with the exact pinned runtime identity'

    $currentStablePostgreSqlIncompleteReport = New-T21PostgreSqlIncompleteAuditReportFixture 'CurrentStable'
    if (-not (Test-T21StartupFailureReportFixture $currentStablePostgreSqlIncompleteReport $currentStableIncompleteEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint CurrentStable -FailureScenario IncompleteV10AuditTable)) {
        throw 'The complete current-stable Paper PostgreSQL incomplete-v10 audit-table failure report was rejected.'
    }
    Write-Output 'PASS: accept current-stable Paper PostgreSQL incomplete-v10 failure with the exact pinned runtime identity'

    $minimumCanvasMariaDbIncompleteReport = New-T21MySqlIncompleteAuditReportFixture 'MariaDB' '12.3.3-MariaDB' 'MinimumCanvas'
    if (-not (Test-T21StartupFailureReportFixture $minimumCanvasMariaDbIncompleteReport $minimumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint MinimumCanvas -FailureScenario IncompleteV10AuditTable)) {
        throw 'The complete minimum-Canvas MariaDB incomplete-v10 audit-table failure report was rejected.'
    }
    Write-Output 'PASS: accept minimum-Canvas MariaDB incomplete-v10 failure with the exact pinned runtime identity'
    $minimumCanvasMariaDbIncompleteReport.startupObservation.runtimeIdentity.build = 604
    if (Test-T21StartupFailureReportFixture $minimumCanvasMariaDbIncompleteReport $minimumCanvasEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint MinimumCanvas -FailureScenario IncompleteV10AuditTable) {
        throw 'The minimum-Canvas MariaDB incomplete-v10 report accepted a runtime build that differs from the pinned endpoint.'
    }
    Write-Output 'PASS: reject minimum-Canvas MariaDB incomplete-v10 evidence with a mismatched runtime build'

    $mysqlIncompleteReport = New-T21MySqlIncompleteAuditReportFixture
    if (-not (Test-T21StartupFailureReportFixture $mysqlIncompleteReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTable)) {
        throw 'The complete minimum-Paper MySQL incomplete-v10 audit-table failure report was rejected.'
    }
    Write-Output 'PASS: accept minimum-Paper MySQL incomplete-v10 failure with preserved table metadata'

    $maximumPaperEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint Maximum
    $maximumPaperIncompleteReport = New-T21MySqlIncompleteAuditReportFixture 'MySQL' '8.0.42' 'Maximum'
    if (-not (Test-T21StartupFailureReportFixture $maximumPaperIncompleteReport $maximumPaperEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Maximum -FailureScenario IncompleteV10AuditTable)) {
        throw 'The complete maximum-Paper MySQL incomplete-v10 audit-table failure report was rejected.'
    }
    Write-Output 'PASS: accept maximum-Paper MySQL incomplete-v10 failure with the exact pinned runtime identity'

    $minimumFoliaEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint MinimumFolia
    $minimumFoliaIncompleteReport = New-T21MySqlIncompleteAuditReportFixture 'MySQL' '8.0.42' 'MinimumFolia'
    if (-not (Test-T21StartupFailureReportFixture $minimumFoliaIncompleteReport $minimumFoliaEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MinimumFolia -FailureScenario IncompleteV10AuditTable)) {
        throw 'The complete minimum-Folia MySQL incomplete-v10 audit-table failure report was rejected.'
    }
    Write-Output 'PASS: accept minimum-Folia MySQL incomplete-v10 failure with the exact pinned runtime identity'
    $maximumFoliaEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint MaximumFolia
    $maximumFoliaIncompleteReport = New-T21MySqlIncompleteAuditReportFixture 'MySQL' '8.0.42' 'MaximumFolia'
    if (-not (Test-T21StartupFailureReportFixture $maximumFoliaIncompleteReport $maximumFoliaEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MaximumFolia -FailureScenario IncompleteV10AuditTable)) {
        throw 'The complete maximum-Folia MySQL incomplete-v10 audit-table failure report was rejected.'
    }
    Write-Output 'PASS: accept maximum-Folia MySQL incomplete-v10 failure with the exact pinned runtime identity'
    $maximumFoliaIncompleteReport.startupObservation.runtimeIdentity.build = 6
    if (Test-T21StartupFailureReportFixture $maximumFoliaIncompleteReport $maximumFoliaEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MaximumFolia -FailureScenario IncompleteV10AuditTable) {
        throw 'The maximum-Folia MySQL incomplete-v10 report accepted a runtime build that differs from the pinned endpoint.'
    }
    Write-Output 'PASS: reject maximum-Folia incomplete-v10 evidence with a mismatched runtime build'
    $maximumFoliaMariaDbIncompleteReport = New-T21MySqlIncompleteAuditReportFixture 'MariaDB' '12.3.3-MariaDB' 'MaximumFolia'
    if (-not (Test-T21StartupFailureReportFixture $maximumFoliaMariaDbIncompleteReport $maximumFoliaEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint MaximumFolia -FailureScenario IncompleteV10AuditTable)) {
        throw 'The complete maximum-Folia MariaDB incomplete-v10 audit-table failure report was rejected.'
    }
    Write-Output 'PASS: accept maximum-Folia MariaDB incomplete-v10 failure with the exact pinned runtime identity'
    $maximumFoliaPostgreSqlIncompleteReport = New-T21PostgreSqlIncompleteAuditReportFixture 'MaximumFolia'
    if (-not (Test-T21StartupFailureReportFixture $maximumFoliaPostgreSqlIncompleteReport $maximumFoliaEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint MaximumFolia -FailureScenario IncompleteV10AuditTable)) {
        throw 'The complete maximum-Folia PostgreSQL incomplete-v10 audit-table failure report was rejected.'
    }
    Write-Output 'PASS: accept maximum-Folia PostgreSQL incomplete-v10 failure with the exact pinned runtime identity'
    $maximumFoliaPostgreSqlIncompleteReport.startupObservation.runtimeIdentity.build = 6
    if (Test-T21StartupFailureReportFixture $maximumFoliaPostgreSqlIncompleteReport $maximumFoliaEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct PostgreSQL -PlatformEndpoint MaximumFolia -FailureScenario IncompleteV10AuditTable) {
        throw 'The maximum-Folia PostgreSQL incomplete-v10 report accepted a runtime build that differs from the pinned endpoint.'
    }
    Write-Output 'PASS: reject maximum-Folia PostgreSQL incomplete-v10 evidence with a mismatched runtime build'
    $minimumFoliaIncompleteReport.startupObservation.runtimeIdentity.software = 'paper'
    if (Test-T21StartupFailureReportFixture $minimumFoliaIncompleteReport $minimumFoliaEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MinimumFolia -FailureScenario IncompleteV10AuditTable) {
        throw 'The minimum-Folia MySQL incomplete-v10 report accepted a Paper runtime identity.'
    }
    Write-Output 'PASS: reject minimum-Folia incomplete-v10 evidence with a mismatched runtime platform'
    $minimumFoliaIncompleteReport.startupObservation.runtimeIdentity.software = 'folia'
    $minimumFoliaIncompleteReport.startupObservation.runtimeIdentity.runtimeVersion = '1.21.7'
    if (Test-T21StartupFailureReportFixture $minimumFoliaIncompleteReport $minimumFoliaEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MinimumFolia -FailureScenario IncompleteV10AuditTable) {
        throw 'The minimum-Folia MySQL incomplete-v10 report accepted a runtime version that differs from the pinned Minecraft version.'
    }
    Write-Output 'PASS: reject minimum-Folia incomplete-v10 evidence with a mismatched runtime version'
    $minimumFoliaIncompleteReport.startupObservation.runtimeIdentity.Remove('runtimeVersion')
    if (Test-T21StartupFailureReportFixture $minimumFoliaIncompleteReport $minimumFoliaEndpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint MinimumFolia -FailureScenario IncompleteV10AuditTable) {
        throw 'The minimum-Folia MySQL incomplete-v10 report accepted a missing runtime version.'
    }
    Write-Output 'PASS: reject minimum-Folia incomplete-v10 evidence with a missing runtime version'

    $mariaDbIncompleteReport = New-T21MySqlIncompleteAuditReportFixture 'MariaDB' '12.3.3-MariaDB'
    if (-not (Test-T21StartupFailureReportFixture $mariaDbIncompleteReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTable)) {
        throw 'The complete minimum-Paper MariaDB incomplete-v10 audit-table failure report was rejected.'
    }
    Write-Output 'PASS: accept minimum-Paper MariaDB incomplete-v10 failure with utf8mb4_general_ci metadata'
    foreach ($schemaState in @($mariaDbIncompleteReport.schemaStateBefore, $mariaDbIncompleteReport.schemaStateAfter)) {
        $columns = @(ConvertFrom-Json -InputObject ([string]$schemaState.auditColumnsJson) -AsHashtable -ErrorAction Stop)
        $columns[0].generation_expression = ''
        $schemaState.auditColumnsJson = ConvertTo-Json -InputObject ([object[]]$columns) -Depth 10 -Compress
    }
    if (Test-T21StartupFailureReportFixture $mariaDbIncompleteReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTable) {
        throw 'The MariaDB incomplete-v10 report accepted MySQL-style empty generation-expression metadata.'
    }
    Write-Output 'PASS: require MariaDB generation-expression null metadata'
    $mariaDbIncompleteReport = New-T21MySqlIncompleteAuditReportFixture 'MariaDB' '12.3.3-MariaDB'
    foreach ($schemaState in @($mariaDbIncompleteReport.schemaStateBefore, $mariaDbIncompleteReport.schemaStateAfter)) {
        $columns = @(ConvertFrom-Json -InputObject ([string]$schemaState.auditColumnsJson) -AsHashtable -ErrorAction Stop)
        $columns[0].collation_name = 'utf8mb4_0900_ai_ci'
        $schemaState.auditColumnsJson = ConvertTo-Json -InputObject ([object[]]$columns) -Depth 10 -Compress
    }
    if (Test-T21StartupFailureReportFixture $mariaDbIncompleteReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MariaDB -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTable) {
        throw 'The MariaDB incomplete-v10 report accepted MySQL-only collation metadata.'
    }
    Write-Output 'PASS: reject MySQL collation metadata in a MariaDB incomplete-v10 report'
    $nonUniquePrimaryIndexJson = '[{"name":"PRIMARY","nonUnique":1,"indexType":"BTREE","visibility":"YES","columns":[{"position":1,"columnName":"id","direction":"A","subPart":null}]}]'
    $mysqlIncompleteReport.schemaStateBefore.auditIndexesJson = $nonUniquePrimaryIndexJson
    $mysqlIncompleteReport.schemaStateAfter.auditIndexesJson = $nonUniquePrimaryIndexJson
    if (Test-T21StartupFailureReportFixture $mysqlIncompleteReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
            -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTable) {
        throw 'The MySQL incomplete-v10 report accepted matching before/after snapshots with a non-unique primary index.'
    }
    Write-Output 'PASS: reject matching before/after snapshots with a non-unique MySQL primary index'

    foreach ($indexMutation in @(
            [ordered]@{ scope = 'index'; property = 'nonUnique'; value = $null }
            [ordered]@{ scope = 'index'; property = 'nonUnique'; value = $false }
            [ordered]@{ scope = 'index'; property = 'nonUnique'; value = [double]0.4 }
            [ordered]@{ scope = 'index'; property = 'nonUnique'; value = '0' }
            [ordered]@{ scope = 'index'; property = 'nonUnique'; remove = $true }
            [ordered]@{ scope = 'column'; property = 'subPart'; remove = $true }
        )) {
        $invalidIndexReport = New-T21MySqlIncompleteAuditReportFixture
        foreach ($schemaState in @($invalidIndexReport.schemaStateBefore, $invalidIndexReport.schemaStateAfter)) {
            $indexes = @(ConvertFrom-Json -InputObject ([string]$schemaState.auditIndexesJson) -AsHashtable -ErrorAction Stop)
            $target = if ($indexMutation.scope -ceq 'column') { $indexes[0].columns[0] } else { $indexes[0] }
            if ($indexMutation.Contains('remove') -and $indexMutation['remove'] -eq $true) { $target.Remove($indexMutation.property) }
            else { $target[$indexMutation.property] = $indexMutation.value }
            $schemaState.auditIndexesJson = ConvertTo-Json -InputObject ([object[]]$indexes) -Depth 10 -Compress
        }
        if (Test-T21StartupFailureReportFixture $invalidIndexReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
                -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTable) {
            throw "The MySQL incomplete-v10 report accepted malformed primary-index metadata: $($indexMutation.property)."
        }
    }
    Write-Output 'PASS: reject malformed MySQL primary-index scalar types and missing metadata fields'

    foreach ($columnMutation in @(
            [ordered]@{ property = 'extra'; value = $null }
            [ordered]@{ property = 'ordinal_position'; value = [double]1.4 }
            [ordered]@{ property = 'table_schema'; value = 'other_database' }
        )) {
        $invalidColumnReport = New-T21MySqlIncompleteAuditReportFixture
        foreach ($schemaState in @($invalidColumnReport.schemaStateBefore, $invalidColumnReport.schemaStateAfter)) {
            $columns = @(ConvertFrom-Json -InputObject ([string]$schemaState.auditColumnsJson) -AsHashtable -ErrorAction Stop)
            $columns[0][$columnMutation.property] = $columnMutation.value
            $schemaState.auditColumnsJson = ConvertTo-Json -InputObject ([object[]]$columns) -Depth 10 -Compress
        }
        if (Test-T21StartupFailureReportFixture $invalidColumnReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) `
                -DatabaseProduct MySQL -PlatformEndpoint Minimum -FailureScenario IncompleteV10AuditTable) {
            throw "The MySQL incomplete-v10 report accepted malformed column metadata: $($columnMutation.property)."
        }
    }
    Write-Output 'PASS: reject malformed MySQL column types and an unbound schema identity'
    [IO.File]::WriteAllLines($platformLogPath, [string[]]$platformLog, [Text.UTF8Encoding]::new($false))

    $provenanceReport = New-T21MySqlStartupFailureReportFixture 'MariaDB' '12.3.3-MariaDB'
    $provenanceReport.plugDev.sourceCommit = '0' * 40
    $provenanceReport.plugDev.cliSha256 = '0' * 64
    if (Test-T21StartupFailureReportFixture $provenanceReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct MariaDB -PlatformEndpoint Minimum) {
        throw 'The MariaDB report accepted PlugDev provenance that does not match the authorized source tree.'
    }
    Write-Output 'PASS: reject fabricated PlugDev commit and CLI hashes'

    $plugDevRootReport = New-T21MySqlStartupFailureReportFixture 'MariaDB' '12.3.3-MariaDB'
    $plugDevRootReport.plugDev.root = 'C:\PlugDevOther'
    if (Test-T21StartupFailureReportFixture $plugDevRootReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct MariaDB -PlatformEndpoint Minimum) {
        throw 'The MariaDB report accepted a PlugDev root outside the authorized local source tree.'
    }
    Write-Output 'PASS: reject a mismatched PlugDev source root'

    $chunkMutationReport = New-T21MySqlStartupFailureReportFixture 'MariaDB' '12.3.3-MariaDB'
    $chunkMutationReport.plugDev.cliDistManifest.files[1].sha256 = '0' * 64
    if (Test-T21StartupFailureReportFixture $chunkMutationReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct MariaDB -PlatformEndpoint Minimum) {
        throw 'The MariaDB report accepted a changed PlugDev CLI chunk with unchanged bundle pins.'
    }
    Write-Output 'PASS: reject a changed PlugDev CLI chunk in the complete runtime manifest'

    $invocationMutationReport = New-T21MySqlStartupFailureReportFixture 'MariaDB' '12.3.3-MariaDB'
    $invocationMutationReport.plugDev.cliDistInvocationObservations[0].afterSha256 = '0' * 64
    if (Test-T21StartupFailureReportFixture $invocationMutationReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct MariaDB -PlatformEndpoint Minimum) {
        throw 'The MariaDB report accepted a PlugDev bundle change across a CLI invocation.'
    }
    Write-Output 'PASS: reject PlugDev CLI dist changes across a runtime invocation'

    $gradleUnverifiedExitReport = New-T21MySqlStartupFailureReportFixture 'MariaDB' '12.3.3-MariaDB'
    $gradleUnverifiedExitReport.gradleDaemonCleanup.processExitVerified = $false
    if (Test-T21StartupFailureReportFixture $gradleUnverifiedExitReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct MariaDB -PlatformEndpoint Minimum) {
        throw 'The MariaDB report accepted Gradle daemon cleanup without verified process exit.'
    }
    Write-Output 'PASS: reject unverified isolated Gradle daemon exit'

    $gradleResidualProcessReport = New-T21MySqlStartupFailureReportFixture 'MariaDB' '12.3.3-MariaDB'
    $gradleResidualProcessReport.gradleDaemonCleanup.processIdsAfterStop = @(999999)
    if (Test-T21StartupFailureReportFixture $gradleResidualProcessReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct MariaDB -PlatformEndpoint Minimum) {
        throw 'The MariaDB report accepted a residual isolated Gradle daemon PID.'
    }
    Write-Output 'PASS: reject residual isolated Gradle daemon PID'

    $logManifestReport = New-T21MySqlStartupFailureReportFixture 'MariaDB' '12.3.3-MariaDB'
    $logManifestReport.logs['logs\mysql-ping.txt'].sha256 = '0' * 64
    if (Test-T21StartupFailureReportFixture $logManifestReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct MariaDB -PlatformEndpoint Minimum) {
        throw 'The MariaDB report accepted a mismatched SQL ping log hash.'
    }
    Write-Output 'PASS: reject a mismatched non-platform SQL log hash'

    $missingLogReport = New-T21MySqlStartupFailureReportFixture 'MariaDB' '12.3.3-MariaDB'
    $missingLogReport.logs.Remove('logs\mysql-ping.txt')
    if (Test-T21StartupFailureReportFixture $missingLogReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct MariaDB -PlatformEndpoint Minimum) {
        throw 'The MariaDB report accepted an incomplete log manifest.'
    }
    Write-Output 'PASS: reject an incomplete SQL log manifest'

    foreach ($databaseFixture in @(
            [ordered]@{ product = 'MySQL'; version = '8.0.42' }
            [ordered]@{ product = 'MariaDB'; version = '12.3.3-MariaDB' }
        )) {
        $databaseReport = New-T21MySqlStartupFailureReportFixture $databaseFixture.product $databaseFixture.version
        if (-not (Test-T21StartupFailureReportFixture $databaseReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct $databaseFixture.product -PlatformEndpoint Minimum)) {
            throw "The complete minimum-Paper $($databaseFixture.product) startup-failure report was rejected."
        }
        Write-Output "PASS: accept a complete minimum-Paper $($databaseFixture.product) startup rollback report, including a zero-byte log"

        $databaseReport.backendShutdowns[0].launchProcessId = 3999
        if (Test-T21StartupFailureReportFixture $databaseReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct $databaseFixture.product -PlatformEndpoint Minimum) {
            throw "The minimum-Paper $($databaseFixture.product) report accepted launcher exit evidence for a different process."
        }
        Write-Output "PASS: reject a mismatched $($databaseFixture.product) launcher exit process ID"
        $databaseReport = New-T21MySqlStartupFailureReportFixture $databaseFixture.product $databaseFixture.version

        $databaseReport.schemaStateAfter.auditIndexesJson += ' '
        if (Test-T21StartupFailureReportFixture $databaseReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct $databaseFixture.product -PlatformEndpoint Minimum) {
            throw "The minimum-Paper $($databaseFixture.product) report accepted a changed preserved-index snapshot."
        }
        Write-Output "PASS: reject changed minimum-Paper $($databaseFixture.product) audit-index evidence"

        foreach ($columnMutation in @(
                [ordered]@{ column = 'type'; property = 'column_type'; value = 'varchar(255)' }
                [ordered]@{ column = 'id'; property = 'is_nullable'; value = 'YES' }
                [ordered]@{ column = 'request_id'; property = 'column_default'; value = 'unexpected' }
                [ordered]@{ column = 'id'; property = 'extra'; value = 'auto_increment' }
                [ordered]@{ column = 'id'; property = 'character_set_name'; value = 'latin1' }
                [ordered]@{ column = 'id'; property = 'collation_name'; value = 'latin1_swedish_ci' }
                [ordered]@{ column = 'id'; property = 'generation_expression'; value = 'id' }
            )) {
            $columnReport = New-T21MySqlStartupFailureReportFixture $databaseFixture.product $databaseFixture.version
            foreach ($schemaState in @($columnReport.schemaStateBefore, $columnReport.schemaStateAfter)) {
                $columns = @(ConvertFrom-Json -InputObject ([string]$schemaState.auditColumnsJson) -AsHashtable -ErrorAction Stop)
                $targetColumn = @($columns | Where-Object { $_['column_name'] -ceq $columnMutation.column })[0]
                $targetColumn[$columnMutation.property] = $columnMutation.value
                $schemaState.auditColumnsJson = ConvertTo-Json -InputObject ([object[]]$columns) -Depth 10 -Compress
            }
            if (Test-T21StartupFailureReportFixture $columnReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct $databaseFixture.product -PlatformEndpoint Minimum) {
                throw "The minimum-Paper $($databaseFixture.product) report accepted invalid $($columnMutation.property) metadata for $($columnMutation.column)."
            }
        }
        Write-Output "PASS: reject incorrect minimum-Paper $($databaseFixture.product) audit-column DDL metadata"

        $literalNullDefaultReport = New-T21MySqlStartupFailureReportFixture $databaseFixture.product $databaseFixture.version
        foreach ($schemaState in @($literalNullDefaultReport.schemaStateBefore, $literalNullDefaultReport.schemaStateAfter)) {
            $columns = @(ConvertFrom-Json -InputObject ([string]$schemaState.auditColumnsJson) -AsHashtable -ErrorAction Stop)
            $idColumn = @($columns | Where-Object { $_['column_name'] -ceq 'player_name' })[0]
            $idColumn['column_default'] = if ($databaseFixture.product -ceq 'MariaDB') { "'NULL'" } else { 'NULL' }
            $idColumn['column_default_is_null'] = 'false'
            $schemaState.auditColumnsJson = ConvertTo-Json -InputObject ([object[]]$columns) -Depth 10 -Compress
        }
        if (Test-T21StartupFailureReportFixture $literalNullDefaultReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct $databaseFixture.product -PlatformEndpoint Minimum) {
            throw "The minimum-Paper $($databaseFixture.product) report accepted a literal 'NULL' default as SQL NULL."
        }
        Write-Output "PASS: reject a literal 'NULL' audit-column default for minimum-Paper $($databaseFixture.product)"

        $invisibleIndexReport = New-T21MySqlStartupFailureReportFixture $databaseFixture.product $databaseFixture.version
        foreach ($schemaState in @($invisibleIndexReport.schemaStateBefore, $invisibleIndexReport.schemaStateAfter)) {
            $indexes = @(ConvertFrom-Json -InputObject ([string]$schemaState.auditIndexesJson) -AsHashtable -ErrorAction Stop)
            $playerIndex = @($indexes | Where-Object { $_['name'] -ceq 'idx_audit_player' })[0]
            $playerIndex['visibility'] = 'NO'
            $schemaState.auditIndexesJson = ConvertTo-Json -InputObject ([object[]]$indexes) -Depth 10 -Compress
        }
        if (Test-T21StartupFailureReportFixture $invisibleIndexReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct $databaseFixture.product -PlatformEndpoint Minimum) {
            throw "The minimum-Paper $($databaseFixture.product) report accepted an invisible audit index."
        }
        Write-Output "PASS: reject an invisible minimum-Paper $($databaseFixture.product) audit index"
    }

    foreach ($databaseFixture in @(
            [ordered]@{ product = 'MySQL'; version = '8.0.42' }
            [ordered]@{ product = 'MariaDB'; version = '12.3.3-MariaDB' }
        )) {
        $prefixedIndexReport = New-T21MySqlStartupFailureReportFixture $databaseFixture.product $databaseFixture.version
        foreach ($schemaState in @($prefixedIndexReport.schemaStateBefore, $prefixedIndexReport.schemaStateAfter)) {
            $indexes = @(ConvertFrom-Json -InputObject ([string]$schemaState.auditIndexesJson) -AsHashtable -ErrorAction Stop)
            $playerIndex = @($indexes | Where-Object { $_['name'] -ceq 'idx_audit_player' })[0]
            $playerIndex['columns'][1]['subPart'] = 8
            $schemaState.auditIndexesJson = ConvertTo-Json -InputObject ([object[]]$indexes) -Depth 10 -Compress
        }
        if (Test-T21StartupFailureReportFixture $prefixedIndexReport $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct $databaseFixture.product -PlatformEndpoint Minimum) {
            throw "The report accepted a prefix length on a non-leading $($databaseFixture.product) audit-index key part."
        }
        Write-Output "PASS: reject a prefix length on a non-leading $($databaseFixture.product) audit-index key part"
    }

    foreach ($property in @('auditVersionRowJson', 'auditColumnsJson', 'auditIndexesJson', 'auditRowsJson')) {
        $beforeValue = $report.schemaStateBefore[$property]
        $report.schemaStateAfter[$property] = $beforeValue + ' '
        if (Test-T21StartupFailureReportFixture $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
            throw "The report accepted changed audit preservation evidence for $property."
        }
        $report.schemaStateAfter[$property] = $beforeValue
    }
    Write-Output 'PASS: reject changes to the audit version row, columns, indexes, or sentinel data'

    $report.server.build = 49
    if (Test-T21StartupFailureReportFixture $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted a server build outside the pinned endpoint.'
    }
    $report.server.build = 48
    Write-Output 'PASS: reject a mismatched server build'

    $report.schemaStateAfter.auditVersion = 11
    if (Test-T21StartupFailureReportFixture $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
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
    if (Test-T21StartupFailureReportFixture $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
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
    if (Test-T21StartupFailureReportFixture $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
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
    if (Test-T21StartupFailureReportFixture $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted missing SQL connection-pool shutdown evidence.'
    }
    [IO.File]::WriteAllLines($platformLogPath, [string[]]$originalPlatformLog, [Text.UTF8Encoding]::new($false))
    $report.platformLogs[0].bytes = [long]$logInfo.Length
    $report.platformLogs[0].sha256 = $logHash
    $report.logs['logs\platform-candidate-startup-failure.txt'].bytes = [long]$logInfo.Length
    $report.logs['logs\platform-candidate-startup-failure.txt'].sha256 = $logHash
    Write-Output 'PASS: require the observed INFO-level SQL pool shutdown marker'

    $report.resourceCleanup.sqlOtherConnections = 1
    if (Test-T21StartupFailureReportFixture $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted an open SQL connection after rollback.'
    }
    $report.resourceCleanup.sqlOtherConnections = 0
    Write-Output 'PASS: reject an open SQL connection after rollback'

    $report.resourceCleanup.redisOtherClients = 1
    if (Test-T21StartupFailureReportFixture $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted an open Redis client after rollback.'
    }
    $report.resourceCleanup.redisOtherClients = 0
    Write-Output 'PASS: reject an open Redis client after rollback'

    foreach ($cleanupField in @('sqlOtherConnections', 'redisOtherClients', 'redisDbSize')) {
        $report.resourceCleanup[$cleanupField] = $false
        if (Test-T21StartupFailureReportFixture $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
            throw "The report accepted Boolean false for numeric cleanup field $cleanupField."
        }
        $report.resourceCleanup[$cleanupField] = 0
    }
    Write-Output 'PASS: reject Boolean false in each numeric SQL/Redis cleanup field'

    $report.startupObservation.successMarkerObserved = $true
    if (Test-T21StartupFailureReportFixture $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted successful plugin startup alongside the expected failure.'
    }
    $report.startupObservation.successMarkerObserved = $false
    Write-Output 'PASS: reject contradictory startup success evidence'

    $report.normalStops[0].processExitCode = 1
    if (Test-T21StartupFailureReportFixture $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted a non-zero platform exit.'
    }
    $report.normalStops[0].processExitCode = 0
    Write-Output 'PASS: reject a non-zero platform exit'

    $report.cleanupFailures = @('unverified cleanup')
    if (Test-T21StartupFailureReportFixture $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum) {
        throw 'The report accepted a cleanup failure.'
    }
    Write-Output 'PASS: reject cleanup failures'

    $runnerSource = Get-Content -LiteralPath $runnerPath -Raw
    if ($runnerSource -notmatch '\[switch\]\$NewerAuditSchemaStartupFailure' -or
        $runnerSource -notmatch '\[switch\]\$IncompleteV10AuditTableStartupFailure' -or
        $runnerSource -notmatch '\[switch\]\$RequiredV11DdlPermissionDeniedStartupFailure' -or
        $runnerSource -notmatch 'Test-T21NewerAuditSchemaStartupFailureTarget' -or
        $runnerSource -notmatch 'Test-T21IncompleteAuditTableStartupFailureTarget' -or
        $runnerSource -notmatch 'Test-T21RequiredV11DdlFailureTarget' -or
        $runnerSource -notmatch 'function Seed-T21NewerAuditSchema' -or
        $runnerSource -notmatch 'function Seed-T21IncompleteV10AuditTable' -or
        $runnerSource -notmatch 'function Seed-T21RequiredV11DdlPermissionFailure' -or
        $runnerSource -notmatch 'function Enable-T21MySqlGeneralLogCapture' -or
        $runnerSource -notmatch 'function Get-T21MySqlDeniedRequestIdDdlEvidence' -or
        $runnerSource -notmatch 'mysql\.general_log' -or
        $runnerSource -notmatch 'deniedDdlEvidence=\$script:deniedDdlEvidence' -or
        $runnerSource -notmatch 'Test-T21PostgreSqlAuditOwnerBoundary' -or
        $runnerSource -notmatch 'NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION' -or
        $runnerSource -notmatch 'CREATE DATABASE \$script:databaseName OWNER postgres' -or
        $runnerSource -notmatch 'GRANT USAGE, CREATE ON SCHEMA public TO \$script:databaseUser' -or
        $runnerSource -notmatch 'pg_has_role' -or
        $runnerSource -notmatch 'postgresql-audit-ddl-privileges' -or
        $runnerSource -notmatch 'REVOKE ALTER ON' -or
        $runnerSource -notmatch 'SHOW GRANTS FOR' -or
        $runnerSource -notmatch 'RedactMariaDbGrantOutput' -or
        $runnerSource -notmatch 'Protect-T21MariaDbGrantLines' -or
        $runnerSource -notmatch '(?s)function Invoke-LoggedCommand.*?Protect-T21MariaDbGrantLines.*?Set-Content' -or
        $runnerSource -notmatch 'function Start-T21ServerExpectingAuditSchemaFailure' -or
        $runnerSource -notmatch 'Test-T21NewerAuditSchemaStartupFailureReport' -or
        $runnerSource -notmatch 'Get-T21PlugDevCliDistManifest' -or
        $runnerSource -notmatch 'cliDistInvocationObservations' -or
        $runnerSource -notmatch 'cliDistManifestSha256After' -or
        $runnerSource -notmatch '(?s)SELECT \*\s+FROM information_schema\.columns') {
        throw 'The SQL runtime runner does not implement the bounded newer-audit-schema startup-failure mode.'
    }
    if (-not $runnerSource.Contains('[switch]$IncompleteV10AuditTablePlayerRowPreservation') -or
        -not $runnerSource.Contains('Test-T21IncompleteAuditTablePlayerRowPreservationTarget') -or
        -not $runnerSource.Contains('Seed-T21IncompleteV10AuditTable -SeedExistingPlayerRow:$script:incompleteV10AuditTablePlayerRowPreservation') -or
        -not $runnerSource.Contains('JSON_ARRAYAGG(JSON_OBJECT') -or
        -not $runnerSource.Contains('CREATE INDEX idx_player_name ON players (player_name);') -or
        -not $runnerSource.Contains("if (`$script:databaseProduct -ceq 'PostgreSQL')")) {
        throw 'The runtime runner does not seed and snapshot the bounded pre-existing player-row fixture.'
    }
    if (-not $runnerSource.Contains("to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS.US')")) {
        throw 'The PostgreSQL player-row snapshot must retain TIMESTAMP fractional seconds.'
    }
    if (-not $runnerSource.Contains('Test-T21IncompleteV10PlayerRowSentinel $state.playerRowsJson -DatabaseProduct $script:databaseProduct')) {
        throw 'The runtime pre-start sentinel check must select the exact SQL-product timestamp representation.'
    }
    $playerRowsSqlStart = $runnerSource.IndexOf('SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT(', [StringComparison]::Ordinal)
    $playerRowsSqlEnd = if ($playerRowsSqlStart -ge 0) {
        $runnerSource.IndexOf('"@ }', $playerRowsSqlStart, [StringComparison]::Ordinal)
    } else { -1 }
    if ($playerRowsSqlEnd -le $playerRowsSqlStart -or
        $runnerSource.Substring($playerRowsSqlStart, $playerRowsSqlEnd - $playerRowsSqlStart) -notmatch '(?m)^\s*FROM\s+players\s*;\s*$') {
        throw 'The MySQL/MariaDB sentinel snapshot query must read its JSON rows from the players table.'
    }
    Write-Output 'PASS: read the MySQL/MariaDB sentinel snapshot from the players table'
    $failureScenarioSwitchIndex = $runnerSource.IndexOf('$failureScenario = switch ($true)', [StringComparison]::Ordinal)
    $playerRowFailureScenarioIndex = $runnerSource.IndexOf("`$script:incompleteV10AuditTablePlayerRowPreservation { 'IncompleteV10AuditTablePlayerRowPreservation'; break }", $failureScenarioSwitchIndex, [StringComparison]::Ordinal)
    $failureScenarioStartIndex = $runnerSource.IndexOf('$startup = Start-T21ServerExpectingAuditSchemaFailure $failureScenario', $failureScenarioSwitchIndex, [StringComparison]::Ordinal)
    $expectedStartupFailureFunctionSignature = 'function Start-T21ServerExpectingAuditSchemaFailure([ValidateSet(''NewerAuditSchema'', ''DatabaseManagerInitFailure'', ''IncompleteV10AuditTable'', ''IncompleteV10AuditTablePlayerRowPreservation'', ''RequiredV11DdlPermissionDenied'')][string]$FailureScenario)'
    if (-not $runnerSource.Contains('$expectedStartupFailurePlayerRows = if ($script:incompleteV10AuditTablePlayerRowPreservation) { 1 } else { 0 }') -or
        -not $runnerSource.Contains('$script:schemaStateBefore.playerRowsJson -cne $script:schemaStateAfter.playerRowsJson') -or
        -not $runnerSource.Contains($expectedStartupFailureFunctionSignature) -or
        $failureScenarioSwitchIndex -lt 0 -or $playerRowFailureScenarioIndex -lt $failureScenarioSwitchIndex -or
        $failureScenarioStartIndex -le $playerRowFailureScenarioIndex) {
        throw 'The startup-failure runtime gate must require one unchanged player row for the preservation subcase.'
    }
    Write-Output 'PASS: require one unchanged player row only for the preservation subcase'
    $startupFailureHelperStart = $runnerSource.IndexOf('function Start-T21ServerExpectingAuditSchemaFailure(', [StringComparison]::Ordinal)
    $startupFailureHelperEnd = if ($startupFailureHelperStart -ge 0) {
        $runnerSource.IndexOf("`nfunction ", $startupFailureHelperStart + 1, [StringComparison]::Ordinal)
    } else { -1 }
    $startupFailureHelperSource = if ($startupFailureHelperEnd -gt $startupFailureHelperStart) {
        $runnerSource.Substring($startupFailureHelperStart, $startupFailureHelperEnd - $startupFailureHelperStart)
    } else { '' }
    if (-not $startupFailureHelperSource.Contains("'IncompleteV10AuditTablePlayerRowPreservation' { 'Failed audit schema migration to version 10; version metadata was not advanced past 9' }") -or
        -not $startupFailureHelperSource.Contains("'IncompleteV10AuditTablePlayerRowPreservation' { 'incomplete-v10-audit-table' }") -or
        -not $startupFailureHelperSource.Contains("'IncompleteV10AuditTablePlayerRowPreservation' { 'Required v10 audit columns are missing:' }") -or
        -not $startupFailureHelperSource.Contains("'IncompleteV10AuditTablePlayerRowPreservation' { @('Syncmoney audit schema version: current 9, target 11', `$failureDetailMarker) }")) {
        throw 'The expected-startup-failure helper must classify the row-preservation case as the incomplete-v10 scenario.'
    }
    Write-Output 'PASS: classify row preservation as incomplete-v10 in the server failure observation'
    $seedIndex = $runnerSource.IndexOf('$script:schemaStateBefore = Seed-T21NewerAuditSchema', [StringComparison]::Ordinal)
    $incompleteSeedIndex = $runnerSource.IndexOf('$script:schemaStateBefore = Seed-T21IncompleteV10AuditTable', [StringComparison]::Ordinal)
    $requiredDdlSeedIndex = $runnerSource.IndexOf('$script:schemaStateBefore = Seed-T21RequiredV11DdlPermissionFailure', [StringComparison]::Ordinal)
    $startupFailureIndex = $runnerSource.IndexOf('$startup = Start-T21ServerExpectingAuditSchemaFailure', [StringComparison]::Ordinal)
    if ($seedIndex -lt 0 -or $startupFailureIndex -le $seedIndex) {
        throw 'The runner must seed the newer audit schema before starting the candidate plugin.'
    }
    if ($incompleteSeedIndex -lt 0 -or $startupFailureIndex -le $incompleteSeedIndex) {
        throw 'The runner must seed the incomplete v10 audit table before starting the candidate plugin.'
    }
    if ($requiredDdlSeedIndex -lt 0 -or $startupFailureIndex -le $requiredDdlSeedIndex) {
        throw 'The runner must establish and verify the dialect-specific DDL-denial boundary before starting the candidate plugin.'
    }
    $targetGuardIndex = $runnerSource.IndexOf('Test-T21IncompleteAuditTableStartupFailureTarget', [StringComparison]::Ordinal)
    $runtimePathResolutionIndex = $runnerSource.IndexOf('$script:serverPath = (Resolve-Path -LiteralPath $ServerJar).Path', [StringComparison]::Ordinal)
    if ($targetGuardIndex -lt 0 -or $runtimePathResolutionIndex -le $targetGuardIndex) {
        throw 'The startup-failure target guard must run before resolving runtime paths or creating resources.'
    }
    $playerRowTargetGuardIndex = $runnerSource.IndexOf('Pre-existing player-row preservation is scoped to all three SQL products on the user-pinned minimum/maximum Paper, Folia, and Canvas endpoints.', [StringComparison]::Ordinal)
    if ($playerRowTargetGuardIndex -lt 0 -or $runtimePathResolutionIndex -le $playerRowTargetGuardIndex) {
        throw 'The player-row-preservation target guard must run before resolving runtime paths or creating resources.'
    }
    Write-Output 'PASS: seed the newer schema before the expected-failure platform start and validate its report'

    $generalLogEnableStart = $runnerSource.IndexOf('function Enable-T21MySqlGeneralLogCapture', [StringComparison]::Ordinal)
    if ($generalLogEnableStart -lt 0) { throw 'The runner must expose the isolated general-log capture function.' }
    $generalLogRestoreFlag = $runnerSource.IndexOf('$script:mysqlGeneralLogRestoreRequired = $true', $generalLogEnableStart, [StringComparison]::Ordinal)
    $generalLogFirstMutation = $runnerSource.IndexOf("SET GLOBAL log_output = 'TABLE'", $generalLogEnableStart, [StringComparison]::Ordinal)
    $outerFinallyStart = $runnerSource.LastIndexOf('} finally {', [StringComparison]::Ordinal)
    $restoreBeforePlatformStop = $runnerSource.IndexOf('try { Disable-T21MySqlGeneralLogCapture }', $outerFinallyStart, [StringComparison]::Ordinal)
    $platformStopAfterRestore = $runnerSource.IndexOf('Stop-RunOwnedServerAfterFailure', $outerFinallyStart, [StringComparison]::Ordinal)
    if ($generalLogEnableStart -lt 0 -or $generalLogRestoreFlag -le $generalLogEnableStart -or
        $generalLogFirstMutation -le $generalLogRestoreFlag -or $outerFinallyStart -lt 0 -or
        $restoreBeforePlatformStop -lt $outerFinallyStart -or $platformStopAfterRestore -le $restoreBeforePlatformStop) {
        throw 'General-log restoration must be armed before the first setting change and attempted before platform shutdown.'
    }
    Write-Output 'PASS: restore general-log state after partial enablement and before platform shutdown'

    $startupValidatorSource = Get-Content -LiteralPath $validatorPath -Raw
    if ($startupValidatorSource -notmatch 'FailureScenario' -or
        $startupValidatorSource -notmatch 'IncompleteV10AuditTable' -or
        $startupValidatorSource -notmatch 'Required v10 audit columns are missing:' -or
        $startupValidatorSource -notmatch 'T21-V03-SYNC-incomplete-v10-audit-table-startup-failure' -or
        $startupValidatorSource -notmatch 'RequiredV11DdlPermissionDenied' -or
        $startupValidatorSource -notmatch 'ALTER command denied' -or
        $startupValidatorSource -notmatch 'function Test-T21MySqlDeniedRequestIdDdlEvent' -or
        $startupValidatorSource -notmatch 'request_id' -or
        $startupValidatorSource -notmatch 'must be owner of table syncmoney_audit_log' -or
        $startupValidatorSource -notmatch 'Test-T21PostgreSqlAuditOwnerBoundary' -or
        $startupValidatorSource -notmatch 'T21-V03-SYNC-v11-audit-ddl-permission-failure') {
        throw 'The startup-failure report predicate does not verify the incomplete-v10-audit-table case.'
    }
    Write-Output 'PASS: report predicates check v9 rejection and v10 preservation after denied v11 DDL'

    if ($runnerSource -match 'The newer-audit-schema startup-failure case requires PostgreSQL\.' -or
        $runnerSource -notmatch '(?s)function Get-T21StartupFailureSchemaState.*?else\s*\{.*?information_schema\.COLUMNS' -or
        $runnerSource -notmatch '(?s)function Seed-T21NewerAuditSchema.*?Invoke-MySqlQuery \$ddl' -or
        $runnerSource -notmatch '(?s)function Seed-T21IncompleteV10AuditTable.*?Invoke-MySqlQuery \$ddl') {
        throw 'The SQL startup-failure runner must seed and capture dialect-native metadata for MySQL, MariaDB, and PostgreSQL.'
    }
    Write-Output 'PASS: seed newer and incomplete schemas and capture dialect-native metadata'

    Write-Output 'T21 startup-failure validator tests PASS'
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
