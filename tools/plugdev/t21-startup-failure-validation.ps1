Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 't21-upgrade-validation.ps1')

function Test-T21NewerAuditSchemaStartupFailureTarget(
    [ValidateSet('MySQL', 'MariaDB', 'PostgreSQL')][string]$DatabaseProduct,
    [ValidateSet('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')]
    [string]$PlatformEndpoint
) {
    return $DatabaseProduct -ceq 'PostgreSQL' -and $PlatformEndpoint -ceq 'Minimum'
}

function Test-T21NewerAuditSchemaStartupFailureReport(
    [object]$report,
    [string]$expectedServerSha256,
    [string]$expectedCandidateSha256,
    [string]$expectedAcceptanceSha256,
    [string]$expectedApiConsumerSha256,
    [ValidateSet('MySQL', 'MariaDB', 'PostgreSQL')][string]$DatabaseProduct,
    [ValidateSet('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')]
    [string]$PlatformEndpoint = 'Minimum'
) {
    try {
        if (-not (Test-T21NewerAuditSchemaStartupFailureTarget -DatabaseProduct $DatabaseProduct -PlatformEndpoint $PlatformEndpoint)) { return $false }
        $expectedPlatform = Get-T21PlatformEndpoint -PlatformEndpoint $PlatformEndpoint
        $sqlReportKey = 'postgresql'
        $expectedDatabaseType = 'postgresql'
        if ((Get-T21ObservedValue $report 'schemaVersion') -ne 1 -or
            (Get-T21ObservedValue $report 'task') -cne 'T21-V03-SYNC-newer-audit-schema-startup-failure' -or
            (Get-T21ObservedValue $report 'status') -cne 'PASS_BOUNDED_SUBCASE' -or
            $null -ne (Get-T21ObservedValue $report 'failure') -or
            @(Get-T21ObservedValue $report 'cleanupFailures').Count -ne 0) { return $false }

        $server = Get-T21ObservedValue $report 'server'
        if ((Get-T21ObservedValue $server 'software') -cne $expectedPlatform.software -or
            (Get-T21ObservedValue $server 'minecraftVersion') -cne $expectedPlatform.minecraftVersion -or
            [int](Get-T21ObservedValue $server 'build') -ne [int]$expectedPlatform.build -or
            ([string](Get-T21ObservedValue $server 'sha256')).ToLowerInvariant() -cne $expectedServerSha256.ToLowerInvariant()) { return $false }

        $candidate = Get-T21ObservedValue $report 'candidate'
        $artifacts = Get-T21ObservedValue $report 'artifacts'
        $java = Get-T21ObservedValue $report 'java'
        $plugDev = Get-T21ObservedValue $report 'plugDev'
        if ((Get-T21ObservedValue $candidate 'version') -cne '1.4.0-alpha.1' -or
            ([string](Get-T21ObservedValue $candidate 'sha256')).ToLowerInvariant() -cne $expectedCandidateSha256.ToLowerInvariant() -or
            ([string](Get-T21ObservedValue $artifacts 'acceptanceSha256')).ToLowerInvariant() -cne $expectedAcceptanceSha256.ToLowerInvariant() -or
            ([string](Get-T21ObservedValue $artifacts 'apiConsumerSha256')).ToLowerInvariant() -cne $expectedApiConsumerSha256.ToLowerInvariant() -or
            [int](Get-T21ObservedValue $java 'feature') -ne 25 -or
            ([string](Get-T21ObservedValue $plugDev 'sourceCommit')) -notmatch '^[0-9a-f]{40}$' -or
            ([string](Get-T21ObservedValue $plugDev 'cliSha256')) -notmatch '^[0-9a-f]{64}$') { return $false }

        $configuration = Get-T21ObservedValue $report 'configuration'
        if ((Get-T21ObservedValue $configuration 'mode') -cne 'sync' -or
            (Get-T21ObservedValue $configuration 'databaseType') -cne $expectedDatabaseType -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'databaseEnabled') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'databaseIsolated') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'redisEnabled') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'redisIsolated') $true) -or
            [int](Get-T21ObservedValue $configuration 'redisDatabase') -ne 15) { return $false }

        $sqlBackend = Get-T21ObservedValue $report 'sqlBackend'
        if ((Get-T21ObservedValue $sqlBackend 'product') -cne 'PostgreSQL' -or
            (Get-T21ObservedValue $sqlBackend 'version') -cne '18.1') { return $false }

        $startup = Get-T21ObservedValue $report 'startupObservation'
        $startupIdentity = Get-T21ObservedValue $startup 'runtimeIdentity'
        $expectedFailureMessage = 'Audit schema version 12 is newer than this plugin supports (11); no audit schema changes were made.'
        if ((Get-T21ObservedValue $startup 'stage') -cne 'candidate-startup-failure' -or
            (Get-T21ObservedValue $startup 'pluginVersion') -cne '1.4.0-alpha.1' -or
            ([string](Get-T21ObservedValue $startup 'artifactSha256')).ToLowerInvariant() -cne $expectedCandidateSha256.ToLowerInvariant() -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $startup 'enabled') $false) -or
            (Get-T21ObservedValue $startup 'expectedFailure') -cne 'newer-audit-schema' -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $startup 'failureObserved') $true) -or
            (Get-T21ObservedValue $startup 'failureMessage') -cne $expectedFailureMessage -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $startup 'rollbackComplete') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $startup 'sqlPoolShutdownObserved') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $startup 'disabledMarker') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $startup 'successMarkerObserved') $false) -or
            [int](Get-T21ObservedValue $startupIdentity 'build') -ne [int]$expectedPlatform.build -or
            (Get-T21ObservedValue $startupIdentity 'software') -cne $expectedPlatform.software -or
            (Get-T21ObservedValue $startupIdentity 'minecraftVersion') -cne $expectedPlatform.minecraftVersion) { return $false }

        $schemaBefore = Get-T21ObservedValue $report 'schemaStateBefore'
        $schemaAfter = Get-T21ObservedValue $report 'schemaStateAfter'
        if ($null -ne (Get-T21ObservedValue $schemaBefore 'mainVersion') -or
            (Get-T21ObservedValue $schemaBefore 'mainVersionRowJson') -cne 'null' -or
            [int](Get-T21ObservedValue $schemaBefore 'auditVersion') -ne 12 -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $schemaBefore 'auditTableExists') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $schemaBefore 'playersTableExists') $false) -or
            $null -ne (Get-T21ObservedValue $schemaBefore 'playerRows') -or
            [int](Get-T21ObservedValue $schemaAfter 'mainVersion') -ne 1 -or
            [int](Get-T21ObservedValue $schemaAfter 'auditVersion') -ne 12 -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $schemaAfter 'auditTableExists') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $schemaAfter 'playersTableExists') $true) -or
            [int](Get-T21ObservedValue $schemaAfter 'playerRows') -ne 0) { return $false }
        $mainVersionRow = ConvertFrom-Json -InputObject ([string](Get-T21ObservedValue $schemaAfter 'mainVersionRowJson')) -AsHashtable -ErrorAction Stop
        if ($mainVersionRow['id'] -cne '1' -or [int]$mainVersionRow['version'] -ne 1) { return $false }

        $preservedAuditProperties = @('auditVersionRowJson', 'auditColumnsJson', 'auditIndexesJson', 'auditRowsJson')
        foreach ($property in $preservedAuditProperties) {
            $beforeValue = [string](Get-T21ObservedValue $schemaBefore $property)
            $afterValue = [string](Get-T21ObservedValue $schemaAfter $property)
            if ([string]::IsNullOrWhiteSpace($beforeValue) -or $beforeValue -cne $afterValue) { return $false }
        }
        $auditVersionRow = ConvertFrom-Json -InputObject ([string](Get-T21ObservedValue $schemaBefore 'auditVersionRowJson')) -AsHashtable -ErrorAction Stop
        if ($auditVersionRow['id'] -cne 'audit' -or [int]$auditVersionRow['version'] -ne 12 -or
            $auditVersionRow['description'] -cne 'T21 newer audit schema preservation sentinel' -or
            [string]::IsNullOrWhiteSpace([string]$auditVersionRow['applied_at'])) { return $false }

        $auditColumns = @(ConvertFrom-Json -InputObject ([string](Get-T21ObservedValue $schemaBefore 'auditColumnsJson')) -AsHashtable -ErrorAction Stop)
        $requiredAuditColumns = @('id', 'timestamp', 'type', 'player_uuid', 'player_name', 'amount', 'balance_after',
            'source', 'server', 'target_uuid', 'target_name', 'reason', 'merged_count', 'request_id', 'sequence', 't21_sentinel')
        $actualAuditColumns = @($auditColumns | ForEach-Object { [string]$_['column_name'] })
        $requiredColumnMetadataFields = @(
            'table_catalog', 'table_schema', 'table_name', 'column_name', 'ordinal_position', 'column_default', 'is_nullable', 'data_type',
            'character_maximum_length', 'character_octet_length', 'numeric_precision', 'numeric_precision_radix', 'numeric_scale',
            'datetime_precision', 'interval_type', 'interval_precision', 'character_set_catalog', 'character_set_schema', 'character_set_name',
            'collation_catalog', 'collation_schema', 'collation_name', 'domain_catalog', 'domain_schema', 'domain_name', 'udt_catalog',
            'udt_schema', 'udt_name', 'scope_catalog', 'scope_schema', 'scope_name', 'maximum_cardinality', 'dtd_identifier',
            'is_self_referencing', 'is_identity', 'identity_generation', 'identity_start', 'identity_increment', 'identity_maximum',
            'identity_minimum', 'identity_cycle', 'is_generated', 'generation_expression', 'is_updatable'
        )
        if ($actualAuditColumns.Count -ne $requiredAuditColumns.Count -or $auditColumns.Count -ne $requiredAuditColumns.Count) { return $false }
        $expectedTableCatalog = $null
        for ($columnIndex = 0; $columnIndex -lt $requiredAuditColumns.Count; $columnIndex++) {
            $column = $auditColumns[$columnIndex]
            if ($column -isnot [System.Collections.IDictionary]) { return $false }
            $columnMetadataFields = @($column.Keys | ForEach-Object { [string]$_ })
            if ($columnMetadataFields.Count -ne $requiredColumnMetadataFields.Count -or
                @($requiredColumnMetadataFields | Where-Object { $_ -cnotin $columnMetadataFields }).Count -ne 0 -or
                $actualAuditColumns[$columnIndex] -cne $requiredAuditColumns[$columnIndex] -or
                [int]$column['ordinal_position'] -ne ($columnIndex + 1) -or
                [string]::IsNullOrWhiteSpace([string]$column['table_catalog']) -or
                [string]$column['table_schema'] -cne 'public' -or
                [string]$column['table_name'] -cne 'syncmoney_audit_log') { return $false }
            if ($null -eq $expectedTableCatalog) { $expectedTableCatalog = [string]$column['table_catalog'] }
            elseif ([string]$column['table_catalog'] -cne $expectedTableCatalog) { return $false }
        }

        $auditIndexes = @(ConvertFrom-Json -InputObject ([string](Get-T21ObservedValue $schemaBefore 'auditIndexesJson')) -AsHashtable -ErrorAction Stop)
        $requiredAuditIndexes = @('idx_audit_timestamp', 'idx_audit_player', 'idx_audit_type_timestamp_sequence', 'syncmoney_audit_log_pkey')
        $actualAuditIndexNames = @($auditIndexes | ForEach-Object { [string]$_['name'] })
        if ($auditIndexes.Count -ne $requiredAuditIndexes.Count -or
            @($requiredAuditIndexes | Where-Object { $_ -notin $actualAuditIndexNames }).Count -ne 0) { return $false }
        foreach ($index in $auditIndexes) {
            if ([string]::IsNullOrWhiteSpace([string]$index['definition'])) { return $false }
        }
        foreach ($expectedIndex in @(
                @{ name = 'idx_audit_timestamp'; pattern = 'timestamp.*DESC.*sequence.*DESC' }
                @{ name = 'idx_audit_player'; pattern = 'player_uuid.*timestamp.*DESC.*sequence.*DESC' }
                @{ name = 'idx_audit_type_timestamp_sequence'; pattern = 'type.*timestamp.*DESC.*sequence.*DESC' }
                @{ name = 'syncmoney_audit_log_pkey'; pattern = 'UNIQUE.*\(id\)' }
            )) {
            $matchingIndex = @($auditIndexes | Where-Object { $_['name'] -ceq $expectedIndex.name })
            if ($matchingIndex.Count -ne 1 -or [string]$matchingIndex[0]['definition'] -notmatch $expectedIndex.pattern) { return $false }
        }

        $auditRows = @(ConvertFrom-Json -InputObject ([string](Get-T21ObservedValue $schemaBefore 'auditRowsJson')) -AsHashtable -ErrorAction Stop)
        if ($auditRows.Count -ne 1 -or
            $auditRows[0]['id'] -cne '00000000-0000-0000-0000-000000000321' -or
            $auditRows[0]['t21_sentinel'] -cne 't21-v12-preservation-sentinel') { return $false }

        $resourceCleanup = Get-T21ObservedValue $report 'resourceCleanup'
        if ([int](Get-T21ObservedValue $resourceCleanup 'sqlOtherConnections') -ne 0 -or
            [int](Get-T21ObservedValue $resourceCleanup 'redisOtherClients') -ne 0 -or
            [int](Get-T21ObservedValue $resourceCleanup 'redisDbSize') -ne 0) { return $false }

        $startupPid = [int](Get-T21ObservedValue $startup 'processId')
        if ($startupPid -le 0) { return $false }
        $normalStops = @(Get-T21ObservedValue $report 'normalStops')
        if ($normalStops.Count -ne 1) { return $false }
        $normalStop = $normalStops[0]
        if ((Get-T21ObservedValue $normalStop 'stage') -cne 'after-startup-failure' -or
            [int](Get-T21ObservedValue $normalStop 'processId') -ne $startupPid -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $normalStop 'commandExitCode') 0) -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $normalStop 'processExitCode') 0) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $normalStop 'processExitCodeObserved') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $normalStop 'processExited') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $normalStop 'disabledMarker') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $normalStop 'forced') $false)) { return $false }

        $backendProcesses = Get-T21ObservedValue $report 'backendProcesses'
        $sqlProcess = Get-T21ObservedValue $backendProcesses $sqlReportKey
        $redisProcess = Get-T21ObservedValue $backendProcesses 'redis'
        if ((Get-T21ObservedValue $sqlProcess 'product') -cne $DatabaseProduct -or
            [int](Get-T21ObservedValue $sqlProcess 'processId') -le 0 -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlProcess 'runOwned') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlProcess 'listenerOwnerVerified') $true) -or
            (Get-T21ObservedValue $redisProcess 'version') -cne '8.10.1' -or
            [int](Get-T21ObservedValue $redisProcess 'processId') -le 0 -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisProcess 'runOwned') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisProcess 'listenerOwnerVerified') $true)) { return $false }

        $backendShutdowns = @(Get-T21ObservedValue $report 'backendShutdowns')
        if ($backendShutdowns.Count -ne 2) { return $false }
        $expectedSqlBackendName = $DatabaseProduct.ToLowerInvariant()
        $sqlShutdown = @($backendShutdowns | Where-Object { (Get-T21ObservedValue $_ 'backend') -ceq $expectedSqlBackendName })
        $redisShutdown = @($backendShutdowns | Where-Object { (Get-T21ObservedValue $_ 'backend') -ceq 'redis' })
        if ($sqlShutdown.Count -ne 1 -or $redisShutdown.Count -ne 1 -or
            [int](Get-T21ObservedValue $sqlShutdown[0] 'processId') -ne [int](Get-T21ObservedValue $sqlProcess 'processId') -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $sqlShutdown[0] 'commandExitCode') 0) -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $sqlShutdown[0] 'processExitCode') 0) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'processExitCodeObserved') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'processExited') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'shutdownTargetVerified') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'forced') $false) -or
            [int](Get-T21ObservedValue $redisShutdown[0] 'processId') -ne [int](Get-T21ObservedValue $redisProcess 'processId') -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $redisShutdown[0] 'commandExitCode') 0) -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $redisShutdown[0] 'processExitCode') 0) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisShutdown[0] 'processExitCodeObserved') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisShutdown[0] 'processExited') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisShutdown[0] 'forced') $false)) { return $false }

        $sourcePins = Get-T21ObservedValue $report 'sourcePins'
        $pinFiles = [ordered]@{
            runnerSha256 = (Join-Path $PSScriptRoot 'run-mysql-sync-runtime.ps1')
            validatorSha256 = (Join-Path $PSScriptRoot 't21-startup-failure-validation.ps1')
            validatorTestSha256 = (Join-Path $PSScriptRoot 't21-startup-failure-validation.test.ps1')
            sharedValidatorSha256 = (Join-Path $PSScriptRoot 't21-upgrade-validation.ps1')
            sharedValidatorTestSha256 = (Join-Path $PSScriptRoot 't21-upgrade-validation.test.ps1')
        }
        foreach ($pinName in $pinFiles.Keys) {
            $pinValue = [string](Get-T21ObservedValue $sourcePins $pinName)
            $expectedPin = (Get-FileHash -LiteralPath $pinFiles[$pinName] -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
            if ($pinValue.ToLowerInvariant() -cne $expectedPin) { return $false }
        }
        if (([string](Get-T21ObservedValue $sourcePins 'acceptanceJarSha256')).ToLowerInvariant() -cne $expectedAcceptanceSha256.ToLowerInvariant() -or
            ([string](Get-T21ObservedValue $sourcePins 'apiConsumerJarSha256')).ToLowerInvariant() -cne $expectedApiConsumerSha256.ToLowerInvariant()) { return $false }

        $runRoot = [IO.Path]::GetFullPath([string](Get-T21ObservedValue $report 'runDirectory'))
        if (-not (Test-Path -LiteralPath $runRoot -PathType Container) -or (Test-T21PathContainsReparsePoint $runRoot)) { return $false }
        $platformLogs = @(Get-T21ObservedValue $report 'platformLogs')
        if ($platformLogs.Count -ne 1) { return $false }
        $platformLog = $platformLogs[0]
        $relativePath = [string](Get-T21ObservedValue $platformLog 'path')
        if ($relativePath -cne 'logs\platform-candidate-startup-failure.txt' -or
            (Get-T21ObservedValue $platformLog 'stage') -cne 'candidate-startup-failure') { return $false }
        $fullLogPath = [IO.Path]::GetFullPath((Join-Path $runRoot $relativePath))
        $runRootPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        if (-not $fullLogPath.StartsWith($runRootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
            (Test-T21PathContainsReparsePoint $fullLogPath) -or
            -not (Test-Path -LiteralPath $fullLogPath -PathType Leaf)) { return $false }
        $actualLog = Get-Item -LiteralPath $fullLogPath -ErrorAction Stop
        $actualLogHash = (Get-FileHash -LiteralPath $fullLogPath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
        $expectedBytes = [long](Get-T21ObservedValue $platformLog 'bytes')
        $expectedHash = ([string](Get-T21ObservedValue $platformLog 'sha256')).ToLowerInvariant()
        if ($expectedBytes -le 0 -or [long]$actualLog.Length -ne $expectedBytes -or
            $actualLogHash -cne $expectedHash -or $expectedHash -notmatch '^[0-9a-f]{64}$') { return $false }
        $logMap = Get-T21ObservedValue $report 'logs'
        $mappedLog = Get-T21ObservedValue $logMap $relativePath
        if ([long](Get-T21ObservedValue $mappedLog 'bytes') -ne $expectedBytes -or
            ([string](Get-T21ObservedValue $mappedLog 'sha256')).ToLowerInvariant() -cne $expectedHash) { return $false }
        $logText = Get-Content -LiteralPath $fullLogPath -Raw -ErrorAction Stop
        foreach ($requiredMarker in @(
                'Loading server plugin Syncmoney v1.4.0-alpha.1',
                'Syncmoney initialization failed; rolling back initialized owners',
                'Audit schema version 12 is newer than this plugin supports (11); no audit schema changes were made.',
                '[noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Shutdown completed.',
                'Syncmoney disabled.')) {
            if (-not $logText.Contains($requiredMarker)) { return $false }
        }
        $infoLevelPoolShutdownPattern = '(?m)^\[[^\]\r\n]+\] \[[^\]\r\n]+/INFO\]: \[noietime\.libs\.hikari\.HikariDataSource\] Syncmoney-HikariCP - Shutdown completed\.\r?$'
        if ($logText -notmatch $infoLevelPoolShutdownPattern) { return $false }
        if ($logText.Contains('Syncmoney enabled successfully')) { return $false }
        $loggedRuntimeIdentity = Get-T21PlatformIdentity $logText
        Assert-T21PlatformIdentity $loggedRuntimeIdentity $expectedPlatform.software $expectedPlatform.minecraftVersion $expectedPlatform.build

        return $true
    } catch {
        Write-Verbose ("Startup-failure report validation rejected the report: " + $_.Exception.Message)
        return $false
    }
}
