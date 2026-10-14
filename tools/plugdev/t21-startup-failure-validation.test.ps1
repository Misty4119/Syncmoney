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
        [ValidateSet('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')][string]$PlatformEndpoint = 'Minimum',
        [ValidateSet('NewerAuditSchema', 'IncompleteV10AuditTable')][string]$FailureScenario = 'NewerAuditSchema'
    ) {
        return Test-T21NewerAuditSchemaStartupFailureReport $candidateReport $expectedServerSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha `
            -ExpectedPlugDevRoot $expectedPlugDevRoot -ExpectedPlugDevSourceCommit $expectedPlugDevSourceCommit -ExpectedPlugDevCliSha256 $expectedPlugDevCliSha256 `
            -ExpectedPlugDevCliDistManifestSha256 $expectedPlugDevCliDistManifestSha256 `
            -DatabaseProduct $DatabaseProduct -PlatformEndpoint $PlatformEndpoint -FailureScenario $FailureScenario
    }

    if (-not (Test-T21StartupFailureReportFixture $report $endpoint.sha256 ('a' * 64) ('b' * 64) ('c' * 64) -DatabaseProduct PostgreSQL -PlatformEndpoint Minimum)) {
        throw 'The complete bounded newer-audit-schema failure report was rejected.'
    }
    Write-Output 'PASS: accept a complete minimum-Paper PostgreSQL startup rollback report'

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
        '[07:44:00] [Server thread/INFO]: [Syncmoney] Syncmoney disabled.'
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

    foreach ($endpointName in @('Minimum', 'Maximum', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas', 'CurrentStable')) {
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

    $startupFailureEndpoints = @('Minimum', 'Maximum', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas', 'CurrentStable')
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

    foreach ($endpointName in @('Minimum', 'Maximum', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas', 'CurrentStable')) {
        foreach ($databaseProduct in @('MySQL', 'MariaDB', 'PostgreSQL')) {
            $shouldAccept = if ($endpointName -ceq 'MaximumFolia') {
                $true
            } elseif ($endpointName -ceq 'MinimumCanvas') {
                $true
            } else {
                $endpointName -in @('Minimum', 'Maximum', 'MinimumFolia')
            }
            $accepted = Test-T21IncompleteAuditTableStartupFailureTarget -DatabaseProduct $databaseProduct -PlatformEndpoint $endpointName
            if ($accepted -ne $shouldAccept) {
                throw "The incomplete-v10-audit-table target guard returned $accepted for $databaseProduct / $endpointName; expected $shouldAccept."
            }
        }
    }
    Write-Output 'PASS: allow incomplete-v10 targets on minimum and maximum Paper and Folia plus minimum Canvas across all SQL dialects, and reject the remaining endpoint/dialect combinations'

    $expectedIncompleteV10Diagnostic = 'The incomplete-v10-audit-table startup-failure case supports MySQL, MariaDB, or PostgreSQL on minimum/maximum Paper, minimum/maximum Folia, and minimum Canvas; maximum Canvas, current-stable Paper, and unpinned endpoints are not supported.'
    $actualIncompleteV10Diagnostic = $null
    try {
        & $runnerPath -ServerJar 'unused.jar' -ExpectedServerSha256 ('a' * 64) -ExpectedCandidateSha256 ('b' * 64) `
            -PlugDevRoot 'unused' -JavaHome 'unused' -DatabaseProduct PostgreSQL -PlatformEndpoint MaximumCanvas `
            -IncompleteV10AuditTableStartupFailure
    } catch {
        $actualIncompleteV10Diagnostic = $_.Exception.Message
    }
    if ($actualIncompleteV10Diagnostic -cne $expectedIncompleteV10Diagnostic) {
        throw "The unsupported PostgreSQL / MaximumCanvas diagnostic was '$actualIncompleteV10Diagnostic'; expected '$expectedIncompleteV10Diagnostic'."
    }
    Write-Output 'PASS: reject PostgreSQL on MaximumCanvas with a diagnostic that names the bounded Paper/Folia and Canvas allowances'

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
            '[07:44:00] [Server thread/INFO]: [Syncmoney] Syncmoney disabled.'
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

        $platformSoftware = if ($endpoint.software -ceq 'folia') { 'Folia' } else { 'Paper' }
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
            '[07:44:00] [Server thread/INFO]: [Syncmoney] Syncmoney disabled.'
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
        $runnerSource -notmatch 'Test-T21NewerAuditSchemaStartupFailureTarget' -or
        $runnerSource -notmatch 'Test-T21IncompleteAuditTableStartupFailureTarget' -or
        $runnerSource -notmatch 'function Seed-T21NewerAuditSchema' -or
        $runnerSource -notmatch 'function Seed-T21IncompleteV10AuditTable' -or
        $runnerSource -notmatch 'function Start-T21ServerExpectingAuditSchemaFailure' -or
        $runnerSource -notmatch 'Test-T21NewerAuditSchemaStartupFailureReport' -or
        $runnerSource -notmatch 'Get-T21PlugDevCliDistManifest' -or
        $runnerSource -notmatch 'cliDistInvocationObservations' -or
        $runnerSource -notmatch 'cliDistManifestSha256After' -or
        $runnerSource -notmatch '(?s)SELECT \*\s+FROM information_schema\.columns') {
        throw 'The SQL runtime runner does not implement the bounded newer-audit-schema startup-failure mode.'
    }
    $seedIndex = $runnerSource.IndexOf('$script:schemaStateBefore = Seed-T21NewerAuditSchema', [StringComparison]::Ordinal)
    $incompleteSeedIndex = $runnerSource.IndexOf('$script:schemaStateBefore = Seed-T21IncompleteV10AuditTable', [StringComparison]::Ordinal)
    $startupFailureIndex = $runnerSource.IndexOf('$startup = Start-T21ServerExpectingAuditSchemaFailure', [StringComparison]::Ordinal)
    if ($seedIndex -lt 0 -or $startupFailureIndex -le $seedIndex) {
        throw 'The runner must seed the newer audit schema before starting the candidate plugin.'
    }
    if ($incompleteSeedIndex -lt 0 -or $startupFailureIndex -le $incompleteSeedIndex) {
        throw 'The runner must seed the incomplete v10 audit table before starting the candidate plugin.'
    }
    $targetGuardIndex = $runnerSource.IndexOf('Test-T21IncompleteAuditTableStartupFailureTarget', [StringComparison]::Ordinal)
    $runtimePathResolutionIndex = $runnerSource.IndexOf('$script:serverPath = (Resolve-Path -LiteralPath $ServerJar).Path', [StringComparison]::Ordinal)
    if ($targetGuardIndex -lt 0 -or $runtimePathResolutionIndex -le $targetGuardIndex) {
        throw 'The startup-failure target guard must run before resolving runtime paths or creating resources.'
    }
    Write-Output 'PASS: seed the newer schema before the expected-failure platform start and validate its report'

    $startupValidatorSource = Get-Content -LiteralPath $validatorPath -Raw
    if ($startupValidatorSource -notmatch 'FailureScenario' -or
        $startupValidatorSource -notmatch 'IncompleteV10AuditTable' -or
        $startupValidatorSource -notmatch 'Required v10 audit columns are missing:' -or
        $startupValidatorSource -notmatch 'T21-V03-SYNC-incomplete-v10-audit-table-startup-failure') {
        throw 'The startup-failure report predicate does not verify the incomplete-v10-audit-table case.'
    }
    Write-Output 'PASS: report predicate checks v9 metadata preservation and the failed v10 migration cause'

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
