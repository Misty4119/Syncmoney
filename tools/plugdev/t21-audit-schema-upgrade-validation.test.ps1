$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$validationPath = Join-Path $PSScriptRoot 't21-audit-schema-upgrade-validation.ps1'
if (-not (Test-Path -LiteralPath $validationPath -PathType Leaf)) {
    throw 'The T21 v10-to-v11 audit-schema upgrade validator is missing.'
}
. $validationPath

function Assert-T21Upgrade([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

function Copy-T21UpgradeState([System.Collections.IDictionary]$State) {
    $copy = [ordered]@{}
    foreach ($key in $State.Keys) { $copy[$key] = $State[$key] }
    return $copy
}

function New-T21UpgradeState([string]$DatabaseProduct, [bool]$AfterUpgrade) {
    $v10Columns = @(
        'id', 'timestamp', 'type', 'player_uuid', 'player_name', 'amount', 'balance_after',
        'source', 'server', 'target_uuid', 'target_name', 'reason', 'merged_count'
    )
    $v11Columns = @($v10Columns + @('request_id', 'sequence'))
    $names = if ($AfterUpgrade) { $v11Columns } else { $v10Columns }
    $columns = @($names | ForEach-Object { [ordered]@{ column_name = $_ } })
    $primaryIndex = if ($DatabaseProduct -ceq 'PostgreSQL') { 'syncmoney_audit_log_pkey' } else { 'PRIMARY' }
    $indexNames = if ($AfterUpgrade) {
        @($primaryIndex, 'idx_audit_timestamp', 'idx_audit_player', 'idx_audit_type_timestamp_sequence')
    } else { @($primaryIndex) }
    $indexes = @(
        foreach ($indexName in $indexNames) {
            if ($DatabaseProduct -ceq 'PostgreSQL') {
                $indexColumnSql = switch ($indexName) {
                    'syncmoney_audit_log_pkey' { 'id' }
                    'idx_audit_timestamp' { 'timestamp DESC, sequence DESC' }
                    'idx_audit_player' { 'player_uuid, timestamp DESC, sequence DESC' }
                    'idx_audit_type_timestamp_sequence' { 'type, timestamp DESC, sequence DESC' }
                }
                $kind = if ($indexName -ceq 'syncmoney_audit_log_pkey') { 'CREATE UNIQUE INDEX' } else { 'CREATE INDEX' }
                [ordered]@{ name = $indexName; definition = "$kind $indexName ON public.syncmoney_audit_log USING btree ($indexColumnSql)" }
            } else {
                $indexColumns = switch ($indexName) {
                    'PRIMARY' { @([ordered]@{ position = 1; columnName = 'id'; direction = 'A'; subPart = $null }) }
                    'idx_audit_timestamp' { @(
                        [ordered]@{ position = 1; columnName = 'timestamp'; direction = 'D'; subPart = $null }
                        [ordered]@{ position = 2; columnName = 'sequence'; direction = 'D'; subPart = $null }
                    ) }
                    'idx_audit_player' { @(
                        [ordered]@{ position = 1; columnName = 'player_uuid'; direction = 'A'; subPart = $null }
                        [ordered]@{ position = 2; columnName = 'timestamp'; direction = 'D'; subPart = $null }
                        [ordered]@{ position = 3; columnName = 'sequence'; direction = 'D'; subPart = $null }
                    ) }
                    'idx_audit_type_timestamp_sequence' { @(
                        [ordered]@{ position = 1; columnName = 'type'; direction = 'A'; subPart = $null }
                        [ordered]@{ position = 2; columnName = 'timestamp'; direction = 'D'; subPart = $null }
                        [ordered]@{ position = 3; columnName = 'sequence'; direction = 'D'; subPart = $null }
                    ) }
                }
                [ordered]@{ name = $indexName; nonUnique = [int]($indexName -cne 'PRIMARY'); indexType = 'BTREE'; visibility = 'YES'; columns = $indexColumns }
            }
        }
    )

    $row = [ordered]@{
        id = '00000000-0000-0000-0000-000000000330'
        timestamp = 1791936000000
        type = 'DEPOSIT'
        player_uuid = '00000000-0000-0000-0000-000000000330'
        player_name = 'T21Fixture'
        amount = 1.25
        balance_after = 99.99
        source = 'TEST'
        server = 't21-schema-upgrade'
        target_uuid = $null
        target_name = $null
        reason = 'v10 upgrade preservation sentinel'
        merged_count = 1
    }
    if ($AfterUpgrade) {
        $row.request_id = $null
        $row.sequence = 0
    }
    $auditVersion = if ($AfterUpgrade) { 11 } else { 10 }
    $description = if ($AfterUpgrade) { 'v1.1.0 Cursor pagination optimization' } else { 'T21 v10 migration sentinel' }
    return [ordered]@{
        mainVersion = 1
        mainVersionRowJson = '{"id":"1","version":1,"description":"T21 pre-existing main schema"}'
        auditVersion = $auditVersion
        auditVersionRowJson = (ConvertTo-Json -InputObject ([ordered]@{ id = 'audit'; version = $auditVersion; description = $description }) -Compress)
        auditTableExists = $true
        playersTableExists = [bool]$AfterUpgrade
        playerRows = if ($AfterUpgrade) { [long]0 } else { $null }
        auditColumnsJson = ConvertTo-Json -InputObject ([object[]]$columns) -Depth 8 -Compress
        auditIndexesJson = ConvertTo-Json -InputObject ([object[]]$indexes) -Depth 8 -Compress
        auditRowsJson = ConvertTo-Json -InputObject ([object[]]@($row)) -Depth 8 -Compress
    }
}

foreach ($databaseProduct in @('MySQL', 'MariaDB', 'PostgreSQL')) {
    Assert-T21Upgrade (Test-T21AuditSchemaV10UpgradeTarget -DatabaseProduct $databaseProduct -PlatformEndpoint Minimum) `
        "The minimum-Paper target was not admitted for $databaseProduct."
    $before = New-T21UpgradeState $databaseProduct $false
    $after = New-T21UpgradeState $databaseProduct $true
    Assert-T21Upgrade (Test-T21AuditSchemaV10UpgradeState $before $after -DatabaseProduct $databaseProduct) `
        "The valid $databaseProduct v10-to-v11 schema and sentinel transition was rejected."

    $wrongBalance = Copy-T21UpgradeState $after
    $rows = @(ConvertFrom-Json -InputObject $wrongBalance.auditRowsJson -AsHashtable)
    $rows[0].balance_after = 100.00
    $wrongBalance.auditRowsJson = ConvertTo-Json -InputObject ([object[]]$rows) -Depth 8 -Compress
    Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $wrongBalance -DatabaseProduct $databaseProduct)) `
        "The $databaseProduct validator accepted a changed audit sentinel balance."

    $missingIndex = Copy-T21UpgradeState $after
    $indexes = @(ConvertFrom-Json -InputObject $missingIndex.auditIndexesJson -AsHashtable)
    $missingIndex.auditIndexesJson = ConvertTo-Json -InputObject ([object[]]@($indexes | Where-Object { $_.name -cne 'idx_audit_player' })) -Depth 8 -Compress
    Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $missingIndex -DatabaseProduct $databaseProduct)) `
        "The $databaseProduct validator accepted a missing v11 player index."

    $wrongVersion = Copy-T21UpgradeState $after
    $wrongVersion.auditVersion = 10
    Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $wrongVersion -DatabaseProduct $databaseProduct)) `
        "The $databaseProduct validator accepted v10 metadata after migration."

    $wrongSentinel = Copy-T21UpgradeState $after
    $rows = @(ConvertFrom-Json -InputObject $wrongSentinel.auditRowsJson -AsHashtable)
    $rows[0].reason = 'different sentinel'
    $wrongSentinel.auditRowsJson = ConvertTo-Json -InputObject ([object[]]$rows) -Depth 8 -Compress
    Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $wrongSentinel -DatabaseProduct $databaseProduct)) `
        "The $DatabaseProduct validator accepted changed sentinel data."

    $wrongIndexOrder = Copy-T21UpgradeState $after
    $indexes = @(ConvertFrom-Json -InputObject $wrongIndexOrder.auditIndexesJson -AsHashtable)
    if ($databaseProduct -ceq 'PostgreSQL') {
        $indexes | Where-Object { $_.name -ceq 'idx_audit_timestamp' } | ForEach-Object {
            $_.definition = 'CREATE INDEX idx_audit_timestamp ON public.syncmoney_audit_log USING btree (sequence DESC, timestamp DESC)'
        }
    } else {
        $indexes | Where-Object { $_.name -ceq 'idx_audit_timestamp' } | ForEach-Object { $_.columns[0].columnName = 'sequence' }
    }
    $wrongIndexOrder.auditIndexesJson = ConvertTo-Json -InputObject ([object[]]$indexes) -Depth 8 -Compress
    Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $wrongIndexOrder -DatabaseProduct $databaseProduct)) `
        "The $DatabaseProduct validator accepted the wrong v11 index column order."
}

foreach ($endpoint in @('Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')) {
    Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeTarget -DatabaseProduct MySQL -PlatformEndpoint $endpoint)) `
        "The bounded positive-migration runner admitted unexercised endpoint $endpoint."
}

$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('t21-audit-schema-upgrade-validation-' + [guid]::NewGuid().ToString('N'))
try {
    $fixtureLogs = Join-Path $fixtureRoot 'logs'
    New-Item -ItemType Directory -Force -Path $fixtureLogs | Out-Null
    $platformLogPath = Join-Path $fixtureLogs 'platform-candidate-audit-v10-upgrade.txt'
    $platformLogText = @'
Syncmoney audit schema version: current 10, target 11
Starting audit schema upgrade: from version 10 to 11
Audit schema version recorded: 11 - v1.1.0 Cursor pagination optimization
Audit schema upgrade completed at version 11
Syncmoney enabled successfully
Syncmoney disabled.
'@
    [IO.File]::WriteAllText($platformLogPath, $platformLogText, [Text.UTF8Encoding]::new($false))
    $logItem = Get-Item -LiteralPath $platformLogPath
    $logHash = (Get-FileHash -LiteralPath $platformLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $platform = Get-T21PlatformEndpoint -PlatformEndpoint Minimum
    $candidateSha = 'a' * 64
    $databaseName = 'syncmoney_t21_0123abcd'
    $sqlKey = if ($databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
    $databaseVersion = switch ($databaseProduct) { 'MariaDB' { '12.3.3-MariaDB' } 'PostgreSQL' { '18.1' } default { '8.0.42' } }
    $databaseType = if ($databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
    $sqlPid = 101
    $redisPid = 202
    $runnerPath = Join-Path $PSScriptRoot 'run-mysql-sync-runtime.ps1'
    $validatorPath = Join-Path $PSScriptRoot 't21-audit-schema-upgrade-validation.ps1'
    $testPath = Join-Path $PSScriptRoot 't21-audit-schema-upgrade-validation.test.ps1'
    $sharedValidatorPath = Join-Path $PSScriptRoot 't21-upgrade-validation.ps1'
    $sharedTestPath = Join-Path $PSScriptRoot 't21-upgrade-validation.test.ps1'
    $failureValidatorPath = Join-Path $PSScriptRoot 't21-startup-failure-validation.ps1'
    $failureTestPath = Join-Path $PSScriptRoot 't21-startup-failure-validation.test.ps1'
    $runnerInfo = [ordered]@{
        path='tools/plugdev/run-mysql-sync-runtime.ps1'
        sha256=(Get-FileHash -LiteralPath $runnerPath -Algorithm SHA256).Hash.ToLowerInvariant()
        auditSchemaUpgradeValidatorSha256=(Get-FileHash -LiteralPath $validatorPath -Algorithm SHA256).Hash.ToLowerInvariant()
        auditSchemaUpgradeTestSha256=(Get-FileHash -LiteralPath $testPath -Algorithm SHA256).Hash.ToLowerInvariant()
        validatorSha256=(Get-FileHash -LiteralPath $sharedValidatorPath -Algorithm SHA256).Hash.ToLowerInvariant()
        testSha256=(Get-FileHash -LiteralPath $sharedTestPath -Algorithm SHA256).Hash.ToLowerInvariant()
        startupFailureValidatorSha256=(Get-FileHash -LiteralPath $failureValidatorPath -Algorithm SHA256).Hash.ToLowerInvariant()
        startupFailureValidatorTestSha256=(Get-FileHash -LiteralPath $failureTestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    foreach ($databaseProduct in @('MySQL', 'MariaDB', 'PostgreSQL')) {
        $databaseVersion = switch ($databaseProduct) { 'MariaDB' { '12.3.3-MariaDB' } 'PostgreSQL' { '18.1' } default { '8.0.42' } }
        $databaseType = if ($databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
        $sqlKey = if ($databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
        $sqlBackend = [ordered]@{ product=$databaseProduct; version=$databaseVersion; processId=$sqlPid; runOwned=$true; listenerOwnerVerified=$true }
        $sqlShutdown = [ordered]@{ backend=$sqlKey; commandExitCode=0; processId=$sqlPid; processExitCode=0; processExitCodeObserved=$true; processExited=$true; forced=$false; shutdownTargetVerified=$true }
        if ($databaseProduct -in @('MySQL', 'MariaDB')) {
            $sqlShutdown.launchProcessExitCode = 0
        }
        $backendProcessFixture = [ordered]@{ redis=[ordered]@{ version='8.10.1'; processId=$redisPid; runOwned=$true; listenerOwnerVerified=$true } }
        $backendProcessFixture[$sqlKey] = $sqlBackend
        $report = [ordered]@{
            schemaVersion=4
            task="T21-V03-SYNC-v10-to-v11-audit-schema-upgrade-$databaseProduct-minimum-Paper"
            status='PASS_BOUNDED_SUBCASE'
            runDirectory=$fixtureRoot
            failure=$null
            cleanupFailures=@()
            server=[ordered]@{ software='paper'; minecraftVersion='1.21.6'; build=48; sha256=$platform.sha256 }
            candidate=[ordered]@{ version='1.4.0-alpha.1'; sha256=$candidateSha }
            java=[ordered]@{ feature=25 }
            configuration=[ordered]@{
                schema=12; configSchema=12; mode='sync'; observedMode='sync'; databaseEnabled=$true; databaseType=$databaseType
                databaseIsolated=$true; databaseHost='127.0.0.1'; databasePort=3310; databaseName=$databaseName; databaseNameObserved=$databaseName
                redisEnabled=$true; redisDatabase=15; redisIsolated=$true; redisHost='127.0.0.1'; redisPort=6379
            }
            backendProcesses=$backendProcessFixture
            redis=[ordered]@{ version='8.10.1' }
            startupObservations=@([ordered]@{
                stage='candidate-audit-v10-upgrade'; pluginVersion='1.4.0-alpha.1'; artifactSha256=$candidateSha; enabled=$true
                processId=$sqlPid; apiConsumerMarker='PASS'; runtimeIdentity=[ordered]@{ software='paper'; runtimeVersion='1.21.6'; minecraftVersion='1.21.6'; build=48 }
            })
            normalStops=@([ordered]@{
                stage='after-audit-v10-upgrade'; commandExitCode=0; processId=$sqlPid; processExitCode=0
                processExitCodeObserved=$true; processExited=$true; disabledMarker=$true; forced=$false
            })
            schemaStateBefore=(New-T21UpgradeState $databaseProduct $false)
            schemaStateAfter=(New-T21UpgradeState $databaseProduct $true)
            platformLogs=@([ordered]@{ stage='candidate-audit-v10-upgrade'; path='logs\platform-candidate-audit-v10-upgrade.txt'; bytes=[long]$logItem.Length; sha256=$logHash })
            logs=[ordered]@{ 'logs\platform-candidate-audit-v10-upgrade.txt'=[ordered]@{ bytes=[long]$logItem.Length; sha256=$logHash } }
            backendShutdowns=@($sqlShutdown, [ordered]@{
                backend='redis'; commandExitCode=1; processId=$redisPid; processExitCode=0; processExitCodeObserved=$true; processExited=$true; forced=$false
            })
            runner=$runnerInfo
            plugDev=[ordered]@{ sourceCommit=('b' * 40); cliSha256=('c' * 64); cliDistManifestSha256Before=('d' * 64); cliDistManifestSha256After=('d' * 64) }
        }
        $report[$sqlKey] = [ordered]@{ product=$databaseProduct; version=$databaseVersion }
        Assert-T21Upgrade (Test-T21AuditSchemaV10UpgradeReport $report $platform.sha256 $candidateSha -DatabaseProduct $databaseProduct -PlatformEndpoint Minimum) `
            "The complete successful $databaseProduct minimum-Paper upgrade report fixture was rejected."
        $invalidReport = $report | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
        $invalidReport.schemaStateAfter.auditRowsJson = '[]'
        Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeReport $invalidReport $platform.sha256 $candidateSha -DatabaseProduct $databaseProduct -PlatformEndpoint Minimum)) `
            "The $databaseProduct report validator accepted a report with a missing sentinel row."
    }
} finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]@('\', '/'))
        $rootFull = [IO.Path]::GetFullPath($fixtureRoot).TrimEnd([char[]]@('\', '/'))
        if ([IO.Path]::GetDirectoryName($rootFull) -cne $tempRoot -or
            [IO.Path]::GetFileName($rootFull) -notmatch '^t21-audit-schema-upgrade-validation-[0-9a-f]{32}$' -or
            (Test-T21PathContainsReparsePoint $rootFull)) {
            throw "Refusing to remove unexpected T21 audit-upgrade fixture path: $rootFull"
        }
        Remove-Item -LiteralPath $rootFull -Recurse -Force
    }
}
$runnerSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'run-mysql-sync-runtime.ps1') -Raw -ErrorAction Stop
foreach ($requiredRunnerContract in @(
        '[switch]$SuccessfulV10AuditSchemaUpgrade',
        'Test-T21AuditSchemaV10UpgradeTarget -DatabaseProduct',
        'Seed-T21AuditSchemaV10Upgrade',
        'Get-T21StartupFailureSchemaState -AfterAuditSchemaV10Upgrade',
        'Test-T21AuditSchemaV10UpgradeState',
        'Test-T21AuditSchemaV10UpgradeReport')) {
    Assert-T21Upgrade ($runnerSource.Contains($requiredRunnerContract)) `
        "The runtime runner is missing successful v10-to-v11 migration contract: $requiredRunnerContract"
}
Write-Output 'PASS: T21 v10-to-v11 target and migration-state predicates'
