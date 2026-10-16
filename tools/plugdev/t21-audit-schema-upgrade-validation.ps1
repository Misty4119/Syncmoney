. (Join-Path $PSScriptRoot 't21-upgrade-validation.ps1')

function Test-T21AuditSchemaV10UpgradeTarget(
    [ValidateSet('MySQL', 'MariaDB', 'PostgreSQL')][string]$DatabaseProduct,
    [ValidateSet('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')]
    [string]$PlatformEndpoint
) {
    return $DatabaseProduct -in @('MySQL', 'MariaDB', 'PostgreSQL') -and $PlatformEndpoint -ceq 'Minimum'
}

function Test-T21AuditUpgradeHasField([object]$observation, [string]$name) {
    if ($null -eq $observation) { return $false }
    if ($observation -is [Collections.IDictionary]) { return $observation.Contains($name) }
    return $null -ne $observation.PSObject.Properties[$name]
}

function Get-T21AuditSchemaUpgradeJsonArray([object]$value) {
    if ($value -isnot [string] -or [string]::IsNullOrWhiteSpace($value)) { return $null }
    try {
        $decoded = ConvertFrom-Json -InputObject $value -AsHashtable -ErrorAction Stop
        if ($null -eq $decoded) { return @() }
        return ,@($decoded)
    } catch { return $null }
}

function Test-T21AuditSchemaV10UpgradeState(
    [object]$Before,
    [object]$After,
    [ValidateSet('MySQL', 'MariaDB', 'PostgreSQL')][string]$DatabaseProduct
) {
    try {
        if ($null -eq $Before -or $null -eq $After) { return $false }
        if ((Get-T21ObservedValue $Before 'mainVersion') -ne 1 -or
            (Get-T21ObservedValue $After 'mainVersion') -ne 1 -or
            (Get-T21ObservedValue $Before 'mainVersionRowJson') -cne (Get-T21ObservedValue $After 'mainVersionRowJson') -or
            (Get-T21ObservedValue $Before 'auditVersion') -ne 10 -or
            (Get-T21ObservedValue $After 'auditVersion') -ne 11 -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $Before 'auditTableExists') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $After 'auditTableExists') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $Before 'playersTableExists') $false) -or
            (Get-T21ObservedValue $Before 'playerRows') -ne $null -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $After 'playersTableExists') $true) -or
            (Get-T21ObservedValue $After 'playerRows') -ne 0) { return $false }

        $beforeVersion = Get-T21AuditSchemaUpgradeJsonArray (Get-T21ObservedValue $Before 'auditVersionRowJson')
        $afterVersion = Get-T21AuditSchemaUpgradeJsonArray (Get-T21ObservedValue $After 'auditVersionRowJson')
        if ($null -eq $beforeVersion -or $beforeVersion.Count -ne 1 -or
            $null -eq $afterVersion -or $afterVersion.Count -ne 1 -or
            (Get-T21ObservedValue $beforeVersion[0] 'id') -cne 'audit' -or
            (Get-T21ObservedValue $beforeVersion[0] 'version') -ne 10 -or
            (Get-T21ObservedValue $beforeVersion[0] 'description') -cne 'T21 v10 migration sentinel' -or
            (Get-T21ObservedValue $afterVersion[0] 'id') -cne 'audit' -or
            (Get-T21ObservedValue $afterVersion[0] 'version') -ne 11 -or
            (Get-T21ObservedValue $afterVersion[0] 'description') -cne 'v1.1.0 Cursor pagination optimization') { return $false }

        $v10Columns = @('id', 'timestamp', 'type', 'player_uuid', 'player_name', 'amount', 'balance_after',
            'source', 'server', 'target_uuid', 'target_name', 'reason', 'merged_count')
        $v11Columns = @($v10Columns + @('request_id', 'sequence'))
        $beforeColumns = Get-T21AuditSchemaUpgradeJsonArray (Get-T21ObservedValue $Before 'auditColumnsJson')
        $afterColumns = Get-T21AuditSchemaUpgradeJsonArray (Get-T21ObservedValue $After 'auditColumnsJson')
        if ($null -eq $beforeColumns -or $null -eq $afterColumns) { return $false }
        $beforeColumnNames = @($beforeColumns | ForEach-Object { [string](Get-T21ObservedValue $_ 'column_name') })
        $afterColumnNames = @($afterColumns | ForEach-Object { [string](Get-T21ObservedValue $_ 'column_name') })
        if ($beforeColumnNames.Count -ne $v10Columns.Count -or
            @($v10Columns | Where-Object { $_ -cnotin $beforeColumnNames }).Count -ne 0 -or
            $afterColumnNames.Count -ne $v11Columns.Count -or
            @($v11Columns | Where-Object { $_ -cnotin $afterColumnNames }).Count -ne 0) { return $false }

        $primaryIndex = if ($DatabaseProduct -ceq 'PostgreSQL') { 'syncmoney_audit_log_pkey' } else { 'PRIMARY' }
        $expectedBeforeIndexes = @($primaryIndex)
        $expectedAfterIndexes = @($primaryIndex, 'idx_audit_timestamp', 'idx_audit_player', 'idx_audit_type_timestamp_sequence')
        $beforeIndexes = Get-T21AuditSchemaUpgradeJsonArray (Get-T21ObservedValue $Before 'auditIndexesJson')
        $afterIndexes = Get-T21AuditSchemaUpgradeJsonArray (Get-T21ObservedValue $After 'auditIndexesJson')
        if ($null -eq $beforeIndexes -or $null -eq $afterIndexes) { return $false }
        $beforeIndexNames = @($beforeIndexes | ForEach-Object { [string](Get-T21ObservedValue $_ 'name') })
        $afterIndexNames = @($afterIndexes | ForEach-Object { [string](Get-T21ObservedValue $_ 'name') })
        if ($beforeIndexNames.Count -ne $expectedBeforeIndexes.Count -or
            @($expectedBeforeIndexes | Where-Object { $_ -cnotin $beforeIndexNames }).Count -ne 0 -or
            $afterIndexNames.Count -ne $expectedAfterIndexes.Count -or
            @($expectedAfterIndexes | Where-Object { $_ -cnotin $afterIndexNames }).Count -ne 0) { return $false }
        $expectedIndexColumns = [ordered]@{
            PRIMARY = @(@{ column='id'; direction='A' })
            idx_audit_timestamp = @(@{ column='timestamp'; direction='D' }, @{ column='sequence'; direction='D' })
            idx_audit_player = @(@{ column='player_uuid'; direction='A' }, @{ column='timestamp'; direction='D' }, @{ column='sequence'; direction='D' })
            idx_audit_type_timestamp_sequence = @(@{ column='type'; direction='A' }, @{ column='timestamp'; direction='D' }, @{ column='sequence'; direction='D' })
        }
        if ($DatabaseProduct -ceq 'PostgreSQL') {
            $expectedIndexColumns['syncmoney_audit_log_pkey'] = $expectedIndexColumns['PRIMARY']
            foreach ($index in $afterIndexes) {
                $name = [string](Get-T21ObservedValue $index 'name')
                $definition = ([string](Get-T21ObservedValue $index 'definition')).ToLowerInvariant() -replace '"', '' -replace '\s+', ' '
                if ($name -cnotin $expectedAfterIndexes -or
                    $definition -notmatch ('using btree \(' + [regex]::Escape((@($expectedIndexColumns[$name] | ForEach-Object {
                        if ($_.direction -ceq 'D') { $_.column + ' desc' } else { $_.column }
                    }) -join ', ')) + '\)$')) { return $false }
            }
            foreach ($index in $beforeIndexes) {
                $definition = ([string](Get-T21ObservedValue $index 'definition')).ToLowerInvariant() -replace '"', '' -replace '\s+', ' '
                if ($definition -notmatch 'using btree \(id\)$') { return $false }
            }
        } else {
            foreach ($index in @($beforeIndexes) + @($afterIndexes)) {
                $name = [string](Get-T21ObservedValue $index 'name')
                if ($name -cnotin $expectedAfterIndexes -or
                    (Get-T21ObservedValue $index 'indexType') -cne 'BTREE' -or
                    (Get-T21ObservedValue $index 'visibility') -cne 'YES' -or
                    (Get-T21ObservedValue $index 'nonUnique') -ne [int]($name -cne 'PRIMARY')) { return $false }
                $actualColumns = @(Get-T21ObservedValue $index 'columns')
                $expectedColumns = @($expectedIndexColumns[$name])
                if ($actualColumns.Count -ne $expectedColumns.Count) { return $false }
                for ($position = 0; $position -lt $expectedColumns.Count; $position++) {
                    if ((Get-T21ObservedValue $actualColumns[$position] 'position') -ne ($position + 1) -or
                        (Get-T21ObservedValue $actualColumns[$position] 'columnName') -cne $expectedColumns[$position].column -or
                        (Get-T21ObservedValue $actualColumns[$position] 'direction') -cne $expectedColumns[$position].direction -or
                        $null -ne (Get-T21ObservedValue $actualColumns[$position] 'subPart')) { return $false }
                }
            }
        }

        $beforeRows = Get-T21AuditSchemaUpgradeJsonArray (Get-T21ObservedValue $Before 'auditRowsJson')
        $afterRows = Get-T21AuditSchemaUpgradeJsonArray (Get-T21ObservedValue $After 'auditRowsJson')
        if ($null -eq $beforeRows -or $beforeRows.Count -ne 1 -or $null -eq $afterRows -or $afterRows.Count -ne 1) { return $false }
        $oldRow = $beforeRows[0]
        $newRow = $afterRows[0]
        foreach ($field in @('id', 'timestamp', 'type', 'player_uuid', 'player_name', 'source', 'server',
                'target_uuid', 'target_name', 'reason', 'merged_count')) {
            if ((Get-T21ObservedValue $oldRow $field) -cne (Get-T21ObservedValue $newRow $field)) { return $false }
        }
        if ((Get-T21ObservedValue $oldRow 'id') -cne '00000000-0000-0000-0000-000000000330' -or
            (Get-T21ObservedValue $oldRow 'reason') -cne 'v10 upgrade preservation sentinel' -or
            (Test-T21AuditUpgradeHasField $oldRow 'request_id') -or
            (Test-T21AuditUpgradeHasField $oldRow 'sequence') -or
            -not (Test-T21AuditUpgradeHasField $newRow 'request_id') -or
            -not (Test-T21AuditUpgradeHasField $newRow 'sequence') -or
            (Get-T21ObservedValue $newRow 'request_id') -ne $null -or
            (Get-T21ObservedValue $newRow 'sequence') -ne 0) { return $false }
        $oldAmount = [decimal]::Parse([string](Get-T21ObservedValue $oldRow 'amount'), [Globalization.CultureInfo]::InvariantCulture)
        $newAmount = [decimal]::Parse([string](Get-T21ObservedValue $newRow 'amount'), [Globalization.CultureInfo]::InvariantCulture)
        $oldBalance = [decimal]::Parse([string](Get-T21ObservedValue $oldRow 'balance_after'), [Globalization.CultureInfo]::InvariantCulture)
        $newBalance = [decimal]::Parse([string](Get-T21ObservedValue $newRow 'balance_after'), [Globalization.CultureInfo]::InvariantCulture)
        return $oldAmount -eq [decimal]1.25 -and $newAmount -eq $oldAmount -and
            $oldBalance -eq [decimal]99.99 -and $newBalance -eq $oldBalance
    } catch { return $false }
}

function Test-T21AuditSchemaV10UpgradeReport(
    [object]$Report,
    [string]$ExpectedServerSha256,
    [string]$ExpectedCandidateSha256,
    [ValidateSet('MySQL', 'MariaDB', 'PostgreSQL')][string]$DatabaseProduct,
    [ValidateSet('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')]
    [string]$PlatformEndpoint
) {
    try {
        if ($null -eq $Report -or -not (Test-T21AuditSchemaV10UpgradeTarget -DatabaseProduct $DatabaseProduct -PlatformEndpoint $PlatformEndpoint)) { return $false }
        $platform = Get-T21PlatformEndpoint -PlatformEndpoint $PlatformEndpoint
        $expectedTask = "T21-V03-SYNC-v10-to-v11-audit-schema-upgrade-$DatabaseProduct-$($platform.taskSuffix)"
        if ((Get-T21ObservedValue $Report 'schemaVersion') -ne 4 -or
            (Get-T21ObservedValue $Report 'task') -cne $expectedTask -or
            (Get-T21ObservedValue $Report 'status') -cne 'PASS_BOUNDED_SUBCASE' -or
            (Get-T21ObservedValue $Report 'failure') -ne $null) { return $false }
        $cleanupFailures = $null
        if ($Report -is [Collections.IDictionary]) {
            if (-not $Report.Contains('cleanupFailures')) { return $false }
            $cleanupFailures = $Report['cleanupFailures']
        } else {
            $property = $Report.PSObject.Properties['cleanupFailures']
            if ($null -eq $property) { return $false }
            $cleanupFailures = $property.Value
        }
        if ($cleanupFailures -isnot [Collections.IList] -or $cleanupFailures.Count -ne 0) { return $false }

        $server = Get-T21ObservedValue $Report 'server'
        $candidate = Get-T21ObservedValue $Report 'candidate'
        $java = Get-T21ObservedValue $Report 'java'
        $configuration = Get-T21ObservedValue $Report 'configuration'
        $expectedDatabaseType = if ($DatabaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
        $expectedDatabaseVersion = switch ($DatabaseProduct) {
            'MariaDB' { '12.3.3-MariaDB' }
            'PostgreSQL' { '18.1' }
            default { '8.0.42' }
        }
        if ((Get-T21ObservedValue $server 'software') -cne 'paper' -or
            (Get-T21ObservedValue $server 'minecraftVersion') -cne '1.21.6' -or
            (Get-T21ObservedValue $server 'build') -ne 48 -or
            ([string](Get-T21ObservedValue $server 'sha256')).ToLowerInvariant() -cne $ExpectedServerSha256.ToLowerInvariant() -or
            $ExpectedServerSha256 -notmatch '^[0-9a-fA-F]{64}$' -or
            (Get-T21ObservedValue $candidate 'version') -cne '1.4.0-alpha.1' -or
            ([string](Get-T21ObservedValue $candidate 'sha256')).ToLowerInvariant() -cne $ExpectedCandidateSha256.ToLowerInvariant() -or
            $ExpectedServerSha256.ToLowerInvariant() -cne $platform.sha256 -or
            (Get-T21ObservedValue $java 'feature') -ne 25) { return $false }
        if ((Get-T21ObservedValue $configuration 'schema') -ne 12 -or
            (Get-T21ObservedValue $configuration 'configSchema') -ne 12 -or
            (Get-T21ObservedValue $configuration 'mode') -cne 'sync' -or
            (Get-T21ObservedValue $configuration 'observedMode') -cne 'sync' -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'databaseEnabled') $true) -or
            (Get-T21ObservedValue $configuration 'databaseType') -cne $expectedDatabaseType -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'databaseIsolated') $true) -or
            (Get-T21ObservedValue $configuration 'databaseHost') -cne '127.0.0.1' -or
            -not (Test-T21IntegralNumber (Get-T21ObservedValue $configuration 'databasePort') 1 65535) -or
            [string](Get-T21ObservedValue $configuration 'databaseName') -notmatch '^syncmoney_t21_[a-f0-9]{8}$' -or
            (Get-T21ObservedValue $configuration 'databaseNameObserved') -cne (Get-T21ObservedValue $configuration 'databaseName') -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'redisEnabled') $true) -or
            (Get-T21ObservedValue $configuration 'redisDatabase') -ne 15 -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'redisIsolated') $true) -or
            (Get-T21ObservedValue $configuration 'redisHost') -cne '127.0.0.1' -or
            -not (Test-T21IntegralNumber (Get-T21ObservedValue $configuration 'redisPort') 1 65535) -or
            (Get-T21ObservedValue $configuration 'databasePort') -eq (Get-T21ObservedValue $configuration 'redisPort')) { return $false }

        $backendProcesses = Get-T21ObservedValue $Report 'backendProcesses'
        $sqlKey = if ($DatabaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
        $sqlBackend = Get-T21ObservedValue $backendProcesses $sqlKey
        $redisBackend = Get-T21ObservedValue $backendProcesses 'redis'
        $sqlEvidence = Get-T21ObservedValue $Report $sqlKey
        $redisEvidence = Get-T21ObservedValue $Report 'redis'
        $expectedRedisVersion = '8.10.1'
        if ((Get-T21ObservedValue $sqlEvidence 'product') -cne $DatabaseProduct -or
            (Get-T21ObservedValue $sqlEvidence 'version') -cne $expectedDatabaseVersion -or
            (Get-T21ObservedValue $sqlBackend 'product') -cne $DatabaseProduct -or
            (Get-T21ObservedValue $sqlBackend 'version') -cne $expectedDatabaseVersion -or
            -not (Test-T21IntegralNumber (Get-T21ObservedValue $sqlBackend 'processId') 1 ([int]::MaxValue)) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlBackend 'runOwned') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlBackend 'listenerOwnerVerified') $true) -or
            (Get-T21ObservedValue $redisEvidence 'version') -cne $expectedRedisVersion -or
            (Get-T21ObservedValue $redisBackend 'version') -cne $expectedRedisVersion -or
            -not (Test-T21IntegralNumber (Get-T21ObservedValue $redisBackend 'processId') 1 ([int]::MaxValue)) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisBackend 'runOwned') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisBackend 'listenerOwnerVerified') $true)) { return $false }

        $startupObservations = @(Get-T21ObservedValue $Report 'startupObservations')
        if ($startupObservations.Count -ne 1) { return $false }
        $startup = $startupObservations[0]
        if ((Get-T21ObservedValue $startup 'stage') -cne 'candidate-audit-v10-upgrade' -or
            (Get-T21ObservedValue $startup 'pluginVersion') -cne '1.4.0-alpha.1' -or
            ([string](Get-T21ObservedValue $startup 'artifactSha256')).ToLowerInvariant() -cne $ExpectedCandidateSha256.ToLowerInvariant() -or
            (Get-T21ObservedValue $startup 'enabled') -isnot [bool] -or
            -not (Get-T21ObservedValue $startup 'enabled') -or
            -not (Test-T21IntegralNumber (Get-T21ObservedValue $startup 'processId') 1 ([int]::MaxValue)) -or
            (Get-T21ObservedValue $startup 'apiConsumerMarker') -cne 'PASS') { return $false }
        Assert-T21PlatformIdentity (Get-T21ObservedValue $startup 'runtimeIdentity') 'paper' '1.21.6' 48

        $stops = @(Get-T21ObservedValue $Report 'normalStops')
        if ($stops.Count -ne 1) { return $false }
        $stop = $stops[0]
        if ((Get-T21ObservedValue $stop 'stage') -cne 'after-audit-v10-upgrade' -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $stop 'commandExitCode') 0) -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $stop 'processExitCode') 0) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $stop 'processExitCodeObserved') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $stop 'processExited') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $stop 'disabledMarker') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $stop 'forced') $false) -or
            (Get-T21ObservedValue $stop 'processId') -ne (Get-T21ObservedValue $startup 'processId')) { return $false }

        $schemaBefore = Get-T21ObservedValue $Report 'schemaStateBefore'
        $schemaAfter = Get-T21ObservedValue $Report 'schemaStateAfter'
        if (-not (Test-T21AuditSchemaV10UpgradeState $schemaBefore $schemaAfter -DatabaseProduct $DatabaseProduct)) { return $false }
        $platformLogs = @(Get-T21ObservedValue $Report 'platformLogs')
        if ($platformLogs.Count -ne 1) { return $false }
        $platformLog = $platformLogs[0]
        if ((Get-T21ObservedValue $platformLog 'stage') -cne 'candidate-audit-v10-upgrade' -or
            (Get-T21ObservedValue $platformLog 'path') -cne 'logs\platform-candidate-audit-v10-upgrade.txt') { return $false }

        $runRoot = [IO.Path]::GetFullPath([string](Get-T21ObservedValue $Report 'runDirectory')).TrimEnd([char[]]@('\', '/'))
        if (-not (Test-Path -LiteralPath $runRoot -PathType Container) -or (Test-T21PathContainsReparsePoint $runRoot)) { return $false }
        $logsRoot = [IO.Path]::GetFullPath((Join-Path $runRoot 'logs')).TrimEnd([char[]]@('\', '/'))
        $relativePath = [string](Get-T21ObservedValue $platformLog 'path')
        $fullLogPath = [IO.Path]::GetFullPath((Join-Path $runRoot $relativePath))
        $pathComparison = if ([OperatingSystem]::IsWindows()) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
        if (-not $fullLogPath.StartsWith(($logsRoot + [IO.Path]::DirectorySeparatorChar), $pathComparison) -or
            (Test-T21PathContainsReparsePoint $fullLogPath) -or -not (Test-Path -LiteralPath $fullLogPath -PathType Leaf)) { return $false }
        $logItem = Get-Item -LiteralPath $fullLogPath -ErrorAction Stop
        $logHash = (Get-FileHash -LiteralPath $fullLogPath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
        if (-not (Test-T21IntegralNumber (Get-T21ObservedValue $platformLog 'bytes') 1 ([long]::MaxValue)) -or
            (Get-T21ObservedValue $platformLog 'bytes') -ne [long]$logItem.Length -or
            ([string](Get-T21ObservedValue $platformLog 'sha256')).ToLowerInvariant() -cne $logHash) { return $false }
        $logManifest = Get-T21ObservedValue $Report 'logs'
        $logEntry = Get-T21ObservedValue $logManifest $relativePath
        if ($null -eq $logEntry -or
            (Get-T21ObservedValue $logEntry 'bytes') -ne [long]$logItem.Length -or
            ([string](Get-T21ObservedValue $logEntry 'sha256')).ToLowerInvariant() -cne $logHash) { return $false }
        $logText = Get-Content -LiteralPath $fullLogPath -Raw -ErrorAction Stop
        foreach ($marker in @(
                'Syncmoney audit schema version: current 10, target 11',
                'Starting audit schema upgrade: from version 10 to 11',
                'Audit schema version recorded: 11 - v1.1.0 Cursor pagination optimization',
                'Audit schema upgrade completed at version 11',
                'Syncmoney enabled successfully',
                'Syncmoney disabled.')) {
            if (-not $logText.Contains($marker)) { return $false }
        }
        if ($logText.Contains('Syncmoney initialization failed') -or $logText.Contains('Failed audit schema migration')) { return $false }

        $backendShutdowns = @(Get-T21ObservedValue $Report 'backendShutdowns')
        if ($backendShutdowns.Count -ne 2) { return $false }
        $sqlShutdown = @($backendShutdowns | Where-Object { (Get-T21ObservedValue $_ 'backend') -ceq $sqlKey })
        $redisShutdown = @($backendShutdowns | Where-Object { (Get-T21ObservedValue $_ 'backend') -ceq 'redis' })
        if ($sqlShutdown.Count -ne 1 -or $redisShutdown.Count -ne 1) { return $false }
        foreach ($shutdown in @($sqlShutdown[0], $redisShutdown[0])) {
            $backendName = [string](Get-T21ObservedValue $shutdown 'backend')
            $commandExitCode = Get-T21ObservedValue $shutdown 'commandExitCode'
            $cleanCommandExit = if ($backendName -ceq 'redis') {
                (Test-T21ExpectedExitCode $commandExitCode 0) -or (Test-T21ExpectedExitCode $commandExitCode 1)
            } else { Test-T21ExpectedExitCode $commandExitCode 0 }
            if (-not $cleanCommandExit -or
                -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $shutdown 'processExitCode') 0) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $shutdown 'processExitCodeObserved') $true) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $shutdown 'processExited') $true) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $shutdown 'forced') $false)) { return $false }
        }
        if ((Get-T21ObservedValue $sqlShutdown[0] 'processId') -ne (Get-T21ObservedValue $sqlBackend 'processId') -or
            (Get-T21ObservedValue $redisShutdown[0] 'processId') -ne (Get-T21ObservedValue $redisBackend 'processId')) { return $false }
        if ($DatabaseProduct -in @('MySQL', 'MariaDB') -and
            (-not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'shutdownTargetVerified') $true) -or
                -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $sqlShutdown[0] 'launchProcessExitCode') 0))) { return $false }
        if ($DatabaseProduct -ceq 'PostgreSQL' -and
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'shutdownTargetVerified') $true)) { return $false }

        $runner = Get-T21ObservedValue $Report 'runner'
        $expectedRunnerPath = 'tools/plugdev/run-mysql-sync-runtime.ps1'
        if ((Get-T21ObservedValue $runner 'path') -cne $expectedRunnerPath) { return $false }
        $repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
        $sourcePins = @(
            [ordered]@{ property='sha256'; path='tools\plugdev\run-mysql-sync-runtime.ps1' },
            [ordered]@{ property='auditSchemaUpgradeValidatorSha256'; path='tools\plugdev\t21-audit-schema-upgrade-validation.ps1' },
            [ordered]@{ property='auditSchemaUpgradeTestSha256'; path='tools\plugdev\t21-audit-schema-upgrade-validation.test.ps1' },
            [ordered]@{ property='validatorSha256'; path='tools\plugdev\t21-upgrade-validation.ps1' },
            [ordered]@{ property='testSha256'; path='tools\plugdev\t21-upgrade-validation.test.ps1' },
            [ordered]@{ property='startupFailureValidatorSha256'; path='tools\plugdev\t21-startup-failure-validation.ps1' },
            [ordered]@{ property='startupFailureValidatorTestSha256'; path='tools\plugdev\t21-startup-failure-validation.test.ps1' }
        )
        foreach ($pin in $sourcePins) {
            $sourcePath = Join-Path $repositoryRoot $pin.path
            if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf) -or
                ([string](Get-T21ObservedValue $runner $pin.property)).ToLowerInvariant() -cne
                    (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()) { return $false }
        }
        $plugDev = Get-T21ObservedValue $Report 'plugDev'
        if ([string](Get-T21ObservedValue $plugDev 'sourceCommit') -notmatch '^[0-9a-f]{40}$' -or
            ([string](Get-T21ObservedValue $plugDev 'cliSha256')).ToLowerInvariant() -notmatch '^[0-9a-f]{64}$' -or
            (Get-T21ObservedValue $plugDev 'cliDistManifestSha256Before') -cne (Get-T21ObservedValue $plugDev 'cliDistManifestSha256After') -or
            (Get-T21ObservedValue $plugDev 'cliDistManifestSha256Before') -notmatch '^[0-9a-f]{64}$') { return $false }
        return $true
    } catch { return $false }
}
