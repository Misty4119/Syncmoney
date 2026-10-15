$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot 't21-upgrade-validation.ps1')
. (Join-Path $PSScriptRoot 't21-legacy-sql-upgrade-validation.ps1')

$expectedBaselineSha = '442a404276b538ce807b0f063fb37920be85e60961a52a475b3f3524d08f4f10'
$expectedCandidateSha = '55d42c2b72e86fdfd1d36e4f909e140f88256527a8e189c0c699d0cef89935d0'
$expectedAcceptanceSha = 'a' * 64
$expectedApiConsumerSha = 'b' * 64
$endpoint = Get-T21PlatformEndpoint -PlatformEndpoint Minimum
$root = Join-Path ([IO.Path]::GetTempPath()) ('t21-legacy-sql-upgrade-validation-' + [guid]::NewGuid().ToString('N'))
$logsRoot = Join-Path $root 'logs'
New-Item -ItemType Directory -Path $logsRoot -Force | Out-Null

function New-T21FixtureLog([string]$stage, [string]$pluginVersion, [string]$marker) {
    $path = "logs\platform-$stage.txt"
    $fullPath = Join-Path $root $path
    $text = @(
        "[20:30:00] [ServerMain/INFO]: Loading Paper 1.21.6-48 for Minecraft 1.21.6",
        "[20:30:01] [Server thread/INFO]: [Syncmoney] Loading server plugin Syncmoney v$pluginVersion",
        '[20:30:02] [Server thread/INFO]: Syncmoney enabled successfully',
        $marker
    ) -join "`n"
    [IO.File]::WriteAllText($fullPath, $text + "`n", [Text.UTF8Encoding]::new($false))
    $item = Get-Item -LiteralPath $fullPath
    $sha = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToLowerInvariant()
    return [ordered]@{
        stage = $stage
        path = $path
        bytes = [long]$item.Length
        sha256 = $sha
        manifest = [ordered]@{ bytes = [long]$item.Length; sha256 = $sha }
    }
}

function New-T21FixtureSnapshotLog([string]$snapshotStage, [string]$suffix, [string[]]$rows) {
    $path = "logs\$snapshotStage-$suffix.txt"
    $fullPath = Join-Path $root $path
    $content = if ($rows.Count -eq 0) { '' } else { ($rows -join "`n") + "`n" }
    [IO.File]::WriteAllText($fullPath, $content, [Text.UTF8Encoding]::new($false))
    $item = Get-Item -LiteralPath $fullPath
    return [ordered]@{
        path = $path
        manifest = [ordered]@{
            bytes = [long]$item.Length
            sha256 = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
}

function New-T21FixtureAccounts([string]$balanceA = '1000.00', [string]$balanceB = '520.00') {
    return @(
        [ordered]@{ name='AcceptanceA'; uuid='35c93f74-c540-3b38-8093-b5cb3c2263be'; balance=$balanceA; version=1 },
        [ordered]@{ name='AcceptanceB'; uuid='b748781d-824c-36d9-9de4-59cee731f37f'; balance=$balanceB; version=1 }
    )
}

function New-T21FixtureStage(
    [string]$stage,
    [string]$pluginVersion,
    [string]$artifactSha,
    [int]$processId,
    [string]$marker,
    [string]$logPath,
    [object[]]$accounts,
    [string[]]$schemaRows
) {
    return [ordered]@{
        stage = $stage
        pluginVersion = $pluginVersion
        artifactName = "Syncmoney-$pluginVersion.jar"
        artifactSha256 = $artifactSha
        enabled = $true
        apiConsumerMarker = if ($pluginVersion -eq '1.4.0-alpha.1') { 'PASS' } else { 'NOT_REQUIRED_FOR_BASELINE' }
        processId = $processId
        runtimeIdentity = [ordered]@{ software='paper'; runtimeVersion='1.21.6'; minecraftVersion='1.21.6'; build=48 }
        acceptanceMarker = $marker
        normalStop = [ordered]@{
            stage = "after-$stage"
            commandExitCode = 0
            processId = $processId
            processExitCode = 0
            processExitCodeObserved = $true
            processExited = $true
            disabledMarker = $true
            forced = $false
        }
        snapshot = [ordered]@{
            stage = "after-$stage-stop"
            sqlAccounts = @($accounts)
            redisAccounts = @($accounts)
            schemaVersionRows = @($schemaRows)
        }
        platformLogPath = $logPath
    }
}

function New-T21ValidFixtureReport {
    $baselineMarker = '[20:30:03] [Paper Async Scheduler Thread #1/INFO]: [SyncmoneyAcceptance] T21_WRITE_PASS expected-a=1000.00 expected-b=520.00 receipt-a=SUCCESS:DEPOSIT:1000.00:1000.00 receipt-b=SUCCESS:DEPOSIT:520.00:520.00'
    $candidateMarker = '[20:30:03] [Paper Async Scheduler Thread #1/INFO]: [SyncmoneyAcceptance] T21_READ_PASS expected-a=1000.00 expected-b=520.00'
    $rollbackMarker = '[20:30:03] [Paper Async Scheduler Thread #1/INFO]: [SyncmoneyAcceptance] T21_ROLLBACK_READ_PASS expected-a=1000.00 expected-b=520.00 actual-a=1000.00 actual-b=520.00'
    $logEntries = @(
        (New-T21FixtureLog 'legacy-seed' '1.3.2' $baselineMarker),
        (New-T21FixtureLog 'candidate-existing-read' '1.4.0-alpha.1' $candidateMarker),
        (New-T21FixtureLog 'legacy-rollback-read' '1.3.2' $rollbackMarker)
    )
    $accounts = New-T21FixtureAccounts
    $schemaRows = @('1|1')
    $stages = @(
        (New-T21FixtureStage 'legacy-seed' '1.3.2' $expectedBaselineSha 3333 $baselineMarker $logEntries[0].path $accounts $schemaRows),
        (New-T21FixtureStage 'candidate-existing-read' '1.4.0-alpha.1' $expectedCandidateSha 4444 $candidateMarker $logEntries[1].path $accounts $schemaRows),
        (New-T21FixtureStage 'legacy-rollback-read' '1.3.2' $expectedBaselineSha 5555 $rollbackMarker $logEntries[2].path $accounts $schemaRows)
    )
    $columnNames = @('id', 'timestamp', 'sequence', 'type', 'player_uuid', 'player_name', 'amount', 'balance_after', 'source', 'server', 'target_uuid', 'target_name', 'reason', 'merged_count', 'request_id')
    $columnRows = [Collections.Generic.List[string]]::new()
    for ($index = 0; $index -lt $columnNames.Count; $index++) {
        $ordinal = $index + 1
        $row = @('syncmoney_audit_log', $ordinal, $columnNames[$index], 'varchar(36)', 'YES', $null, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', '')
        $columnRows.Add((ConvertTo-Json -InputObject $row -Compress))
    }
    $baselineIndexes = @(
        (ConvertTo-Json -InputObject @('PRIMARY', 0, 1, 'id', 'A', 'BTREE', $null) -Compress)
    )
    $addedIndexes = @(
        (ConvertTo-Json -InputObject @('idx_audit_timestamp', 1, 1, 'timestamp', 'D', 'BTREE', $null) -Compress),
        (ConvertTo-Json -InputObject @('idx_audit_timestamp', 1, 2, 'sequence', 'D', 'BTREE', $null) -Compress),
        (ConvertTo-Json -InputObject @('idx_audit_player', 1, 1, 'player_uuid', 'A', 'BTREE', $null) -Compress),
        (ConvertTo-Json -InputObject @('idx_audit_player', 1, 2, 'timestamp', 'D', 'BTREE', $null) -Compress),
        (ConvertTo-Json -InputObject @('idx_audit_player', 1, 3, 'sequence', 'D', 'BTREE', $null) -Compress),
        (ConvertTo-Json -InputObject @('idx_audit_type', 1, 1, 'type', 'A', 'BTREE', $null) -Compress),
        (ConvertTo-Json -InputObject @('idx_audit_type', 1, 2, 'timestamp', 'D', 'BTREE', $null) -Compress),
        (ConvertTo-Json -InputObject @('idx_audit_type', 1, 3, 'sequence', 'D', 'BTREE', $null) -Compress)
    )
    $candidateIndexes = @($baselineIndexes + $addedIndexes)
    $stages[0].snapshot.auditSchema = [ordered]@{ columns=@($columnRows); indexes=$baselineIndexes }
    $stages[1].snapshot.auditSchema = [ordered]@{ columns=@($columnRows); indexes=$candidateIndexes }
    $stages[2].snapshot.auditSchema = [ordered]@{ columns=@($columnRows); indexes=$candidateIndexes }
    $logManifest = [ordered]@{}
    foreach ($entry in $logEntries) { $logManifest[$entry.path] = $entry.manifest }
    for ($stageIndex = 0; $stageIndex -lt $stages.Count; $stageIndex++) {
        $snapshot = $stages[$stageIndex].snapshot
        $sqlRows = @($snapshot.sqlAccounts | Sort-Object -Property name | ForEach-Object {
            '{0}|{1}|{2}|{3}' -f $_.name, $_.uuid, $_.balance, $_.version
        })
        foreach ($snapshotLog in @(
                (New-T21FixtureSnapshotLog $snapshot.stage 'mysql-snapshot' $sqlRows),
                (New-T21FixtureSnapshotLog $snapshot.stage 'schema-version-snapshot' @($snapshot.schemaVersionRows)),
                (New-T21FixtureSnapshotLog $snapshot.stage 'audit-columns-snapshot' @($snapshot.auditSchema.columns)),
                (New-T21FixtureSnapshotLog $snapshot.stage 'audit-indexes-snapshot' @($snapshot.auditSchema.indexes)))) {
            $logManifest[$snapshotLog.path] = $snapshotLog.manifest
        }
    }
    return [ordered]@{
        schemaVersion = 2
        task = 'T21-M05-LEGACY-SQL-DATA-MySQL-minimum-Paper'
        status = 'PASS_BOUNDED_SUBCASE'
        runDirectory = $root
        server = [ordered]@{ software='paper'; minecraftVersion='1.21.6'; build=48; sha256=$endpoint.sha256 }
        baseline = [ordered]@{ version='1.3.2'; sha256=$expectedBaselineSha }
        candidate = [ordered]@{ version='1.4.0-alpha.1'; sha256=$expectedCandidateSha }
        artifacts = [ordered]@{ acceptanceSha256=$expectedAcceptanceSha; apiConsumerSha256=$expectedApiConsumerSha }
        java = [ordered]@{ feature=25 }
        mysql = [ordered]@{ product='MySQL'; version='8.0.42'; serverVersion='8.0.42' }
        redis = [ordered]@{ version='8.10.1' }
        configuration = [ordered]@{
            schema=12; mode='sync'; databaseEnabled=$true; databaseType='mysql'; databaseIsolated=$true
            redisEnabled=$true; redisDatabase=15; redisIsolated=$true
        }
        stages = $stages
        platformLogs = @($logEntries | ForEach-Object { [ordered]@{ stage=$_.stage; path=$_.path; bytes=$_.bytes; sha256=$_.sha256 } })
        logs = $logManifest
        backendProcesses = [ordered]@{
            mysql = [ordered]@{ product='MySQL'; version='8.0.42'; processId=1111; launchProcessId=1212; runOwned=$true; listenerOwnerVerified=$true }
            redis = [ordered]@{ version='8.10.1'; processId=2222; runOwned=$true; listenerOwnerVerified=$true }
        }
        backendShutdowns = @(
            [ordered]@{ backend='mysql'; processId=1111; commandExitCode=0; shutdownTargetVerified=$true; processExitCode=0; processExitCodeObserved=$true; processExited=$true; launchProcessId=1212; launchProcessExitCode=0; launchProcessExitCodeObserved=$true; launchProcessExited=$true; forced=$false },
            [ordered]@{ backend='redis'; processId=2222; commandExitCode=1; processExitCode=0; processExitCodeObserved=$true; processExited=$true; forced=$false }
        )
        gradleDaemonCleanup = [ordered]@{
            isolatedUserHome = Join-Path $root '.gradle-user-home'
            processIdsBeforeStop = @()
            processIdsAfterStop = @()
            processExitVerified = $true
        }
        cleanupFailures = @()
        failure = $null
        sourcePins = [ordered]@{
            runnerSha256 = '1' * 64
            validatorSha256 = '2' * 64
            validatorTestSha256 = '3' * 64
            sharedValidatorSha256 = '4' * 64
            sharedValidatorTestSha256 = '5' * 64
            plugDevCommit = 'b59871ae8d9f2731d2bcbca061a735edddd23eb2'
            plugDevCliSha256 = '6' * 64
            paperJarSha256 = $endpoint.sha256
            baselineJarSha256 = $expectedBaselineSha
            candidateJarSha256 = $expectedCandidateSha
            acceptanceJarSha256 = $expectedAcceptanceSha
            apiConsumerJarSha256 = $expectedApiConsumerSha
        }
    }
}

function Set-T21FixturePlatform([object]$report, [object]$platform) {
    $mysql = Get-T21ObservedValue $report 'mysql'
    $product = [string](Get-T21ObservedValue $mysql 'product')
    if ([string]::IsNullOrWhiteSpace($product)) {
        $postgresql = Get-T21ObservedValue $report 'postgresql'
        $product = [string](Get-T21ObservedValue $postgresql 'product')
    }
    if ([string]::IsNullOrWhiteSpace($product)) { throw 'Fixture report does not identify a SQL product.' }
    $report.task = "T21-M05-LEGACY-SQL-DATA-$product-$($platform.taskSuffix)"
    $report.server.software = $platform.software
    $report.server.minecraftVersion = $platform.minecraftVersion
    $report.server.build = $platform.build
    $report.server.sha256 = $platform.sha256
    $report.sourcePins.paperJarSha256 = $platform.sha256
    foreach ($stage in $report.stages) {
        $stage.runtimeIdentity.software = $platform.software
        $stage.runtimeIdentity.runtimeVersion = $platform.minecraftVersion
        $stage.runtimeIdentity.minecraftVersion = $platform.minecraftVersion
        $stage.runtimeIdentity.build = $platform.build
    }
    $oldIdentity = '[20:30:00] [ServerMain/INFO]: Loading Paper 1.21.6-48 for Minecraft 1.21.6'
    $softwareDisplay = [string]$platform.software
    $softwareDisplay = $softwareDisplay.Substring(0, 1).ToUpperInvariant() + $softwareDisplay.Substring(1)
    $newIdentity = "[20:30:00] [ServerMain/INFO]: Loading $softwareDisplay $($platform.minecraftVersion)-$($platform.build) for Minecraft $($platform.minecraftVersion)"
    foreach ($entry in $report.platformLogs) {
        $path = Join-Path $root $entry.path
        $content = [IO.File]::ReadAllText($path)
        if (-not $content.Contains($oldIdentity)) { throw "Fixture platform log does not contain the expected startup identity: $($entry.path)" }
        $content = $content.Replace($oldIdentity, $newIdentity)
        [IO.File]::WriteAllText($path, $content, [Text.UTF8Encoding]::new($false))
        $item = Get-Item -LiteralPath $path
        $sha = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        $entry.bytes = [long]$item.Length
        $entry.sha256 = $sha
        $report.logs[$entry.path] = [ordered]@{ bytes=[long]$item.Length; sha256=$sha }
    }
    return $report
}

function Convert-T21FixtureToPostgreSql([object]$report) {
    $report.task = 'T21-M05-LEGACY-SQL-DATA-PostgreSQL-minimum-Paper'
    $report.postgresql = [ordered]@{ product='PostgreSQL'; version='18.1'; serverVersion='18.1' }
    $report.Remove('mysql')
    $report.configuration.databaseType = 'postgresql'
    $report.backendProcesses.postgresql = [ordered]@{
        product='PostgreSQL'; version='18.1'; processId=1111; runOwned=$true; listenerOwnerVerified=$true
    }
    $report.backendProcesses.Remove('mysql')
    $report.backendShutdowns[0] = [ordered]@{
        backend='postgresql'; processId=1111; commandExitCode=0; shutdownTargetVerified=$true
        processExitCode=0; processExitCodeObserved=$true; processExited=$true; forced=$false
    }
    $postgresIndexes = @($report.stages[1].snapshot.auditSchema.indexes | ForEach-Object {
        $row = ConvertFrom-Json -InputObject ([string]$_) -AsHashtable
        if ($row[0] -ceq 'PRIMARY') { $row[0] = 'syncmoney_audit_log_pkey' }
        $postgresRow = @(
            $row[0],
            ([string]$row[0] -ceq 'syncmoney_audit_log_pkey'),
            $row[1],
            $row[2],
            $row[3],
            $row[4],
            'BTREE',
            $row[6]
        )
        ConvertTo-Json -InputObject $postgresRow -Compress
    })
    foreach ($stage in $report.stages) {
        $schema = $stage.snapshot.auditSchema
        for ($index = 0; $index -lt $schema.columns.Count; $index++) {
            $row = ConvertFrom-Json -InputObject ([string]$schema.columns[$index]) -AsHashtable
            $row[3] = 'character varying'
            $row[7] = $null
            $row[8] = $null
            $schema.columns[$index] = ConvertTo-Json -InputObject $row -Compress
        }
        $schema.indexes = @($postgresIndexes)
        $snapshotStage = [string]$stage.snapshot.stage
        foreach ($logBinding in @(
                [ordered]@{ suffix='audit-columns-snapshot'; rows=@($schema.columns) },
                [ordered]@{ suffix='audit-indexes-snapshot'; rows=@($schema.indexes) })) {
            $log = New-T21FixtureSnapshotLog $snapshotStage $logBinding.suffix $logBinding.rows
            $report.logs[$log.path] = $log.manifest
        }
        $oldPath = "logs\$snapshotStage-mysql-snapshot.txt"
        $newRows = @($stage.snapshot.sqlAccounts | Sort-Object -Property name | ForEach-Object {
            '{0}|{1}|{2}|{3}' -f $_.name, $_.uuid, $_.balance, $_.version
        })
        $newLog = New-T21FixtureSnapshotLog $snapshotStage 'postgresql-snapshot' $newRows
        $report.logs.Remove($oldPath)
        $report.logs[$newLog.path] = $newLog.manifest
        $oldFile = Join-Path $root $oldPath
        if (Test-Path -LiteralPath $oldFile) { Remove-Item -LiteralPath $oldFile -Force }
    }
    return $report
}

function Convert-T21FixtureToMariaDb([object]$report) {
    $report.task = 'T21-M05-LEGACY-SQL-DATA-MariaDB-minimum-Paper'
    $report.mysql.product = 'MariaDB'
    $report.mysql.version = '12.3.3-MariaDB'
    $report.mysql.serverVersion = '12.3.3-MariaDB'
    $report.configuration.databaseType = 'mysql'
    $report.backendProcesses.mysql.product = 'MariaDB'
    $report.backendProcesses.mysql.version = '12.3.3-MariaDB'
    $mariaDbIndexes = @(
        (ConvertTo-Json -InputObject @('idx_audit_player', 1, 1, 'player_uuid', 'A', 'BTREE', $null) -Compress),
        (ConvertTo-Json -InputObject @('idx_audit_player', 1, 2, 'timestamp', 'D', 'BTREE', $null) -Compress),
        (ConvertTo-Json -InputObject @('idx_audit_player', 1, 3, 'sequence', 'D', 'BTREE', $null) -Compress),
        (ConvertTo-Json -InputObject @('idx_audit_timestamp', 1, 1, 'timestamp', 'D', 'BTREE', $null) -Compress),
        (ConvertTo-Json -InputObject @('idx_audit_timestamp', 1, 2, 'sequence', 'D', 'BTREE', $null) -Compress),
        (ConvertTo-Json -InputObject @('idx_audit_type', 1, 1, 'type', 'A', 'BTREE', $null) -Compress),
        (ConvertTo-Json -InputObject @('idx_audit_type', 1, 2, 'timestamp', 'D', 'BTREE', $null) -Compress),
        (ConvertTo-Json -InputObject @('idx_audit_type', 1, 3, 'sequence', 'D', 'BTREE', $null) -Compress),
        (ConvertTo-Json -InputObject @('PRIMARY', 0, 1, 'id', 'A', 'BTREE', $null) -Compress)
    )
    foreach ($stage in $report.stages) {
        $stage.snapshot.auditSchema.indexes = @($mariaDbIndexes)
        $snapshotStage = [string]$stage.snapshot.stage
        $sqlRows = @($stage.snapshot.sqlAccounts | Sort-Object -Property name | ForEach-Object {
            '{0}|{1}|{2}|{3}' -f $_.name, $_.uuid, $_.balance, $_.version
        })
        $sqlLog = New-T21FixtureSnapshotLog $snapshotStage 'mariadb-snapshot' $sqlRows
        $report.logs[$sqlLog.path] = $sqlLog.manifest
        $oldSqlPath = "logs\$snapshotStage-mysql-snapshot.txt"
        $report.logs.Remove($oldSqlPath)
        $oldSqlFile = Join-Path $root $oldSqlPath
        if (Test-Path -LiteralPath $oldSqlFile) { Remove-Item -LiteralPath $oldSqlFile -Force }
        $indexLog = New-T21FixtureSnapshotLog $snapshotStage 'audit-indexes-snapshot' $mariaDbIndexes
        $report.logs[$indexLog.path] = $indexLog.manifest
    }
    return $report
}

function New-T21MariaDbFixtureReport {
    $report = New-T21ValidFixtureReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    return Convert-T21FixtureToMariaDb $report
}

try {
    $valid = New-T21ValidFixtureReport
    if (-not (Test-T21MySqlLegacyDataUpgradeReport $valid $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha)) {
        throw 'A complete minimum-Paper MySQL legacy-data upgrade report was rejected.'
    }
    Write-Output 'PASS: accept existing 1.3.2 MySQL data through candidate startup/read and 1.3.2 rollback'

    $maximumEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint Maximum
    $maximumValid = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $maximumValid = Set-T21FixturePlatform $maximumValid $maximumEndpoint
    if (-not (Test-T21MySqlLegacyDataUpgradeReport $maximumValid $maximumEndpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha -PlatformEndpoint Maximum)) {
        throw 'A complete maximum-Paper MySQL legacy-data upgrade report was rejected.'
    }
    Write-Output 'PASS: accept existing 1.3.2 MySQL data on the pinned maximum Paper endpoint'
    if (Test-T21MySqlLegacyDataUpgradeReport $maximumValid $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha -PlatformEndpoint Minimum) {
        throw 'A maximum-Paper report was accepted under the minimum-Paper endpoint contract.'
    }
    Write-Output 'PASS: reject maximum-Paper legacy-data evidence under the minimum-Paper endpoint contract'

    $currentStableEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint CurrentStable
    # Endpoint fixtures share temporary log paths, so regenerate the minimum baseline before mutating it.
    $currentStableBase = New-T21ValidFixtureReport
    $currentStableValid = $currentStableBase | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $currentStableValid = Set-T21FixturePlatform $currentStableValid $currentStableEndpoint
    if (-not (Test-T21MySqlLegacyDataUpgradeReport $currentStableValid $currentStableEndpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha -PlatformEndpoint CurrentStable)) {
        throw 'A complete current-stable Paper MySQL legacy-data upgrade report was rejected.'
    }
    Write-Output 'PASS: accept existing 1.3.2 MySQL data on the supplemental current-stable Paper endpoint'
    if (Test-T21MySqlLegacyDataUpgradeReport $currentStableValid $maximumEndpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha -PlatformEndpoint Maximum) {
        throw 'A current-stable Paper report was accepted under the user-pinned maximum-Paper endpoint contract.'
    }
    Write-Output 'PASS: reject current-stable Paper legacy-data evidence under the user-pinned maximum-Paper endpoint contract'

    $mariaDb = New-T21MariaDbFixtureReport
    if (-not (Test-T21MariaDbLegacyDataUpgradeReport $mariaDb $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha)) {
        throw 'A complete minimum-Paper MariaDB legacy-data read/rollback report was rejected.'
    }
    Write-Output 'PASS: accept MariaDB legacy data with its observed index set preserved through candidate read and rollback'
    if (Test-T21MySqlLegacyDataUpgradeReport $mariaDb $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'MariaDB legacy-data evidence was accepted under the MySQL product contract.'
    }
    Write-Output 'PASS: reject MariaDB legacy-data evidence under the MySQL product contract'

    $mariaDbWrongVersion = $mariaDb | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $mariaDbWrongVersion.mysql.version = '8.0.42'
    if (Test-T21MariaDbLegacyDataUpgradeReport $mariaDbWrongVersion $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A MariaDB legacy-data report with a MySQL version was accepted.'
    }
    Write-Output 'PASS: reject MariaDB legacy-data reports with a mismatched backend version'

    $mariaDbWrongConfiguration = $mariaDb | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $mariaDbWrongConfiguration.configuration.databaseType = 'mariadb'
    if (Test-T21MariaDbLegacyDataUpgradeReport $mariaDbWrongConfiguration $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A MariaDB legacy-data report with a non-Syncmoney database configuration type was accepted.'
    }
    Write-Output 'PASS: require MariaDB report configuration to use Syncmoney database type mysql'

    $mariaDbIndexDrift = $mariaDb | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $candidateIndexes = @($mariaDbIndexDrift.stages[1].snapshot.auditSchema.indexes | Where-Object {
        (ConvertFrom-Json -InputObject ([string]$_) -AsHashtable)[0] -cne 'idx_audit_timestamp'
    })
    $mariaDbIndexDrift.stages[1].snapshot.auditSchema.indexes = $candidateIndexes
    $candidateSnapshotStage = [string]$mariaDbIndexDrift.stages[1].snapshot.stage
    $candidateIndexLog = New-T21FixtureSnapshotLog $candidateSnapshotStage 'audit-indexes-snapshot' $candidateIndexes
    $mariaDbIndexDrift.logs[$candidateIndexLog.path] = $candidateIndexLog.manifest
    if (Test-T21MariaDbLegacyDataUpgradeReport $mariaDbIndexDrift $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A MariaDB candidate report with audit indexes removed after baseline was accepted.'
    }
    Write-Output 'PASS: reject MariaDB candidate audit-index drift after the 1.3.2 baseline'

    $failedMariaDbLauncher = New-T21MariaDbFixtureReport
    $failedMariaDbLauncher.backendShutdowns[0].launchProcessExitCode = 1
    if (Test-T21MariaDbLegacyDataUpgradeReport $failedMariaDbLauncher $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A MariaDB shutdown with a failed launcher exit was accepted.'
    }
    Write-Output 'PASS: reject MariaDB shutdown with a failed launcher process'

    $mariaDbLauncherMismatch = New-T21MariaDbFixtureReport
    $mariaDbLauncherMismatch.backendShutdowns[0].launchProcessId = 1313
    if (Test-T21MariaDbLegacyDataUpgradeReport $mariaDbLauncherMismatch $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A MariaDB shutdown receipt for a different launcher process was accepted.'
    }
    Write-Output 'PASS: reject MariaDB launcher exit evidence for a different startup process'

    $runnerSource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'run-mysql-sync-runtime.ps1')
    if (-not (
        $runnerSource.Contains('if ($script:legacySqlDataUpgrade) {') -and
        $runnerSource.Contains('if (-not (Test-T21LegacySqlRuntimeTarget -DatabaseProduct $script:databaseProduct -PlatformEndpoint $PlatformEndpoint)) {') -and
        -not $runnerSource.Contains('$PlatformEndpoint -notin @(''Minimum'', ''Maximum'', ''CurrentStable'')') -and
        $runnerSource.Contains('task="T21-M05-LEGACY-SQL-DATA-$($script:databaseProduct)-$($script:platformTarget.taskSuffix)"') -and
        $runnerSource.Contains('Get-T21LegacyAuditSchemaMetadata $stage') -and
        $runnerSource.Contains('Test-T21MariaDbLegacyDataUpgradeReport') -and
        $runnerSource.Contains('$databaseProcessEvidence.launchProcessId = [int]$databaseProcess.launchProcessId') -and
        $runnerSource.Contains('databaseType=$(if ($script:databaseProduct -ceq ''PostgreSQL'') { ''postgresql'' } else { ''mysql'' })') -and
        $runnerSource.Contains('Test-T21PostgresLegacyDataUpgradeReport') -and
        $runnerSource.Contains('LEFT JOIN pg_attribute table_column') -and
        $runnerSource.Contains("COALESCE(table_column.attname, '<EXPRESSION>')") -and
        $runnerSource.Contains('index_info.indisprimary')
    )) { throw 'The runner does not expose the expected SQL products and configuration type on the supported Paper legacy-data endpoints.' }
    Write-Output 'PASS: route legacy SQL acceptance through the exact pinned platform and backend target guard'

    $legacyTargets = @('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')
    $legacyProducts = @('MySQL', 'MariaDB', 'PostgreSQL')
    foreach ($product in $legacyProducts) {
        foreach ($targetName in $legacyTargets) {
            if (-not (Test-T21LegacySqlRuntimeTarget -DatabaseProduct $product -PlatformEndpoint $targetName)) {
                throw "The legacy SQL runtime target guard rejected supported target $product / $targetName."
            }
        }
    }
    if ((Test-T21LegacySqlRuntimeTarget -DatabaseProduct SQLite -PlatformEndpoint Minimum) -or
        (Test-T21LegacySqlRuntimeTarget -DatabaseProduct MySQL -PlatformEndpoint Unpinned)) {
        throw 'The legacy SQL runtime target guard accepted an unsupported backend or unpinned platform.'
    }
    Write-Output 'PASS: accept all 21 pinned legacy SQL backend/platform targets and reject unsupported inputs'

    $foliaCanvasTargets = @('MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')
    for ($targetIndex = 0; $targetIndex -lt $foliaCanvasTargets.Count; $targetIndex++) {
        $targetName = $foliaCanvasTargets[$targetIndex]
        $platform = Get-T21PlatformEndpoint -PlatformEndpoint $targetName
        $mismatchName = $foliaCanvasTargets[($targetIndex + 1) % $foliaCanvasTargets.Count]
        $mismatch = Get-T21PlatformEndpoint -PlatformEndpoint $mismatchName
        foreach ($product in $legacyProducts) {
            $fixture = New-T21ValidFixtureReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
            if ($product -ceq 'MariaDB') { $fixture = Convert-T21FixtureToMariaDb $fixture }
            if ($product -ceq 'PostgreSQL') { $fixture = Convert-T21FixtureToPostgreSql $fixture }
            $fixture = Set-T21FixturePlatform $fixture $platform
            $valid = switch ($product) {
                'MariaDB' { Test-T21MariaDbLegacyDataUpgradeReport $fixture $platform.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha -PlatformEndpoint $targetName }
                'PostgreSQL' { Test-T21PostgresLegacyDataUpgradeReport $fixture $platform.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha -PlatformEndpoint $targetName }
                default { Test-T21MySqlLegacyDataUpgradeReport $fixture $platform.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha -PlatformEndpoint $targetName }
            }
            if (-not $valid) { throw "The legacy report predicate rejected $product / $targetName." }
            $mismatched = switch ($product) {
                'MariaDB' { Test-T21MariaDbLegacyDataUpgradeReport $fixture $mismatch.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha -PlatformEndpoint $mismatchName }
                'PostgreSQL' { Test-T21PostgresLegacyDataUpgradeReport $fixture $mismatch.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha -PlatformEndpoint $mismatchName }
                default { Test-T21MySqlLegacyDataUpgradeReport $fixture $mismatch.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha -PlatformEndpoint $mismatchName }
            }
            if ($mismatched) { throw "The legacy report predicate relabeled $product / $targetName as $mismatchName." }
        }
    }
    Write-Output 'PASS: accept and correctly bind MySQL, MariaDB, and PostgreSQL legacy reports on each Folia/Canvas endpoint'

    $legacyPostgresIndexOrderQuery = "position(' DESC' in upper(pg_get_indexdef(index_relation.oid, key_column.ordinality::integer, true)))"
    if (-not $runnerSource.Contains('index_info.indoption[key_column.ordinality - 1] & 1') -or
        $runnerSource.Contains($legacyPostgresIndexOrderQuery)) {
        throw 'PostgreSQL audit index direction must be derived from pg_index.indoption, not a rendered index definition.'
    }
    Write-Output 'PASS: derive PostgreSQL audit index direction from pg_index.indoption'

    $postgresql = New-T21ValidFixtureReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $postgresql = Convert-T21FixtureToPostgreSql $postgresql
    if (-not (Test-T21PostgresLegacyDataUpgradeReport $postgresql $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha)) {
        throw 'A complete minimum-Paper PostgreSQL legacy-data read/rollback report with its initialized audit indexes preserved was rejected.'
    }
    Write-Output 'PASS: accept existing 1.3.2 PostgreSQL data while preserving initialized audit indexes through candidate read and rollback'

    $postgresqlExpressionIndex = $postgresql | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $expressionIndexRow = ConvertTo-Json -InputObject @('unexpected_expression_idx', $false, 1, 1, '<EXPRESSION>', 'A', 'BTREE', $null) -Compress
    foreach ($stage in $postgresqlExpressionIndex.stages) {
        $stage.snapshot.auditSchema.indexes = @($stage.snapshot.auditSchema.indexes) + $expressionIndexRow
        $snapshotStage = [string]$stage.snapshot.stage
        $expressionIndexLog = New-T21FixtureSnapshotLog $snapshotStage 'audit-indexes-snapshot' @($stage.snapshot.auditSchema.indexes)
        $postgresqlExpressionIndex.logs[$expressionIndexLog.path] = $expressionIndexLog.manifest
    }
    if (Test-T21PostgresLegacyDataUpgradeReport $postgresqlExpressionIndex $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A PostgreSQL report containing an extra expression index in every lifecycle stage was accepted.'
    }
    Write-Output 'PASS: reject extra PostgreSQL expression indexes from the full catalog inventory'

    $postgresqlMissingPrimary = $postgresql | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $candidateIndexes = @($postgresqlMissingPrimary.stages[1].snapshot.auditSchema.indexes)
    for ($index = 0; $index -lt $candidateIndexes.Count; $index++) {
        $row = ConvertFrom-Json -InputObject ([string]$candidateIndexes[$index]) -AsHashtable
        if ([string]$row[0] -ceq 'syncmoney_audit_log_pkey') {
            $row[1] = $false
            $candidateIndexes[$index] = ConvertTo-Json -InputObject $row -Compress
        }
    }
    $postgresqlMissingPrimary.stages[1].snapshot.auditSchema.indexes = $candidateIndexes
    $candidateSnapshotStage = [string]$postgresqlMissingPrimary.stages[1].snapshot.stage
    $candidateIndexLog = New-T21FixtureSnapshotLog $candidateSnapshotStage 'audit-indexes-snapshot' @($candidateIndexes)
    $postgresqlMissingPrimary.logs[$candidateIndexLog.path] = $candidateIndexLog.manifest
    if (Test-T21PostgresLegacyDataUpgradeReport $postgresqlMissingPrimary $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A same-named PostgreSQL unique id index without a primary-key constraint was accepted.'
    }
    Write-Output 'PASS: require the PostgreSQL primary-key catalog flag for the audit id index'

    $postgresqlDrift = $postgresql | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $candidateIndexes = $postgresqlDrift.stages[1].snapshot.auditSchema.indexes
    $removedIndex = $candidateIndexes | Where-Object { (ConvertFrom-Json -InputObject ([string]$_) -AsHashtable)[0] -ceq 'idx_audit_timestamp' } | Select-Object -First 1
    $postgresqlDrift.stages[1].snapshot.auditSchema.indexes = @($candidateIndexes | Where-Object { $_ -cne $removedIndex })
    $candidateSnapshotStage = [string]$postgresqlDrift.stages[1].snapshot.stage
    $candidateIndexLog = New-T21FixtureSnapshotLog $candidateSnapshotStage 'audit-indexes-snapshot' @($postgresqlDrift.stages[1].snapshot.auditSchema.indexes)
    $postgresqlDrift.logs[$candidateIndexLog.path] = $candidateIndexLog.manifest
    if (Test-T21PostgresLegacyDataUpgradeReport $postgresqlDrift $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A PostgreSQL candidate report with an audit index removed after baseline was accepted.'
    }
    Write-Output 'PASS: reject PostgreSQL candidate audit-index drift after the 1.3.2 baseline'
    if (Test-T21MySqlLegacyDataUpgradeReport $postgresql $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A PostgreSQL legacy-data report was accepted as MySQL evidence.'
    }
    Write-Output 'PASS: reject PostgreSQL legacy-data evidence under the MySQL report contract'

    # Restore the minimum fixture logs because the endpoint mutation reuses the same isolated fixture paths.
    $valid = New-T21ValidFixtureReport
    $unboundAuditIndexLog = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $candidateIndexPath = Join-Path $root 'logs\after-candidate-existing-read-stop-audit-indexes-snapshot.txt'
    $originalCandidateIndexLog = [IO.File]::ReadAllText($candidateIndexPath)
    try {
        $contradictoryRows = @($unboundAuditIndexLog.stages[0].snapshot.auditSchema.indexes) -join "`n"
        [IO.File]::WriteAllText($candidateIndexPath, $contradictoryRows + "`n", [Text.UTF8Encoding]::new($false))
        $candidateLogItem = Get-Item -LiteralPath $candidateIndexPath
        $candidateRelativePath = 'logs\after-candidate-existing-read-stop-audit-indexes-snapshot.txt'
        $unboundAuditIndexLog.logs[$candidateRelativePath] = [ordered]@{
            bytes = [long]$candidateLogItem.Length
            sha256 = (Get-FileHash -LiteralPath $candidateIndexPath -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        if (Test-T21MySqlLegacyDataUpgradeReport $unboundAuditIndexLog $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
            throw 'A report whose serialized candidate indexes contradicted its retained SQL output was accepted.'
        }
    } finally {
        [IO.File]::WriteAllText($candidateIndexPath, $originalCandidateIndexLog, [Text.UTF8Encoding]::new($false))
    }
    Write-Output 'PASS: reject serialized audit schema that contradicts correctly hashed SQL snapshot logs'

    $missingFailureField = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $missingFailureField.Remove('failure')
    if (Test-T21MySqlLegacyDataUpgradeReport $missingFailureField $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A legacy-data report without explicit failure evidence was accepted.'
    }
    Write-Output 'PASS: reject a legacy-data report with a missing failure field'

    $missingCleanupFailures = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $missingCleanupFailures.Remove('cleanupFailures')
    if (Test-T21MySqlLegacyDataUpgradeReport $missingCleanupFailures $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A legacy-data report without explicit cleanup evidence was accepted.'
    }
    Write-Output 'PASS: reject a legacy-data report with a missing cleanupFailures field'

    $objectCleanupFailures = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $objectCleanupFailures.cleanupFailures = [ordered]@{}
    if (Test-T21MySqlLegacyDataUpgradeReport $objectCleanupFailures $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A legacy-data report with cleanupFailures encoded as an object was accepted.'
    }
    Write-Output 'PASS: reject cleanupFailures encoded as an empty object'

    $invalidMySqlShutdownCommand = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $invalidMySqlShutdownCommand.backendShutdowns[0].commandExitCode = 1
    if (Test-T21MySqlLegacyDataUpgradeReport $invalidMySqlShutdownCommand $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A MySQL shutdown command with a nonzero exit code was accepted.'
    }
    Write-Output 'PASS: reject a MySQL shutdown command failure while accepting Redis SHUTDOWN exit code 1'

    $unverifiedMySqlShutdownTarget = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $unverifiedMySqlShutdownTarget.backendShutdowns[0].shutdownTargetVerified = $false
    if (Test-T21MySqlLegacyDataUpgradeReport $unverifiedMySqlShutdownTarget $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A MySQL shutdown without verified listener ownership was accepted.'
    }
    Write-Output 'PASS: reject MySQL shutdown without verified target ownership'

    $failedMySqlLauncher = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $failedMySqlLauncher.backendShutdowns[0].launchProcessExitCode = 1
    if (Test-T21MySqlLegacyDataUpgradeReport $failedMySqlLauncher $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A MySQL shutdown with a failed launcher exit was accepted.'
    }
    Write-Output 'PASS: reject MySQL shutdown with a failed launcher process'

    $mysqlLauncherMismatch = New-T21ValidFixtureReport
    $mysqlLauncherMismatch.backendShutdowns[0].launchProcessId = 1313
    if (Test-T21MySqlLegacyDataUpgradeReport $mysqlLauncherMismatch $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A MySQL shutdown receipt for a different launcher process was accepted.'
    }
    Write-Output 'PASS: reject MySQL launcher exit evidence for a different startup process'

    $invalidMySqlStartupLauncher = New-T21ValidFixtureReport
    $invalidMySqlStartupLauncher.backendProcesses.mysql.launchProcessId = '1212'
    if (Test-T21MySqlLegacyDataUpgradeReport $invalidMySqlStartupLauncher $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A MySQL startup launcher ID encoded as a string was accepted.'
    }
    Write-Output 'PASS: require an integral MySQL startup launcher process ID'

    $redisZeroShutdownCommand = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $redisZeroShutdownCommand.backendShutdowns[1].commandExitCode = 0
    if (-not (Test-T21MySqlLegacyDataUpgradeReport $redisZeroShutdownCommand $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha)) {
        throw 'A clean Redis shutdown command with exit code zero was rejected.'
    }
    Write-Output 'PASS: accept clean Redis SHUTDOWN SAVE exit codes 0 and 1'

    $wrongBaseline = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    if (Test-T21MySqlLegacyDataUpgradeReport $wrongBaseline $endpoint.sha256 ('0' * 64) $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A legacy-data report with a mismatched baseline JAR pin was accepted.'
    }
    Write-Output 'PASS: reject a baseline plugin artifact pin mismatch'

    $wrongAcceptance = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $wrongAcceptance.artifacts.apiConsumerSha256 = 'c' * 64
    if (Test-T21MySqlLegacyDataUpgradeReport $wrongAcceptance $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A report with an API-consumer artifact pin mismatch was accepted.'
    }
    Write-Output 'PASS: reject an API-consumer artifact pin mismatch'

    $baselineLoadedConsumer = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $baselineLoadedConsumer.stages[0].apiConsumerMarker = 'PASS'
    if (Test-T21MySqlLegacyDataUpgradeReport $baselineLoadedConsumer $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A baseline stage that loaded the 1.4 API consumer was accepted.'
    }
    Write-Output 'PASS: reject loading the 1.4 API consumer in a 1.3.2 stage'

    $wrongState = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $wrongState.stages[1].snapshot.sqlAccounts[0].balance = '999.00'
    if (Test-T21MySqlLegacyDataUpgradeReport $wrongState $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A candidate SQL snapshot that changed legacy balances during a read-only stage was accepted.'
    }
    Write-Output 'PASS: reject a candidate stage that fails to preserve the baseline SQL balance'

    $versionDrift = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $versionDrift.stages[1].snapshot.sqlAccounts[0].version = 2
    $versionDrift.stages[1].snapshot.redisAccounts[0].version = 2
    if (Test-T21MySqlLegacyDataUpgradeReport $versionDrift $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A candidate read-only stage that changed an account version was accepted.'
    }
    Write-Output 'PASS: reject account version drift during a read-only stage'

    $schemaDrift = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $schemaDrift.stages[1].snapshot.schemaVersionRows = @('1|2')
    if (Test-T21MySqlLegacyDataUpgradeReport $schemaDrift $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A candidate schema-version change was accepted.'
    }
    Write-Output 'PASS: reject a schema-version change during the candidate read-only stage'

    $auditIndexDrift = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $auditIndexDrift.stages[1].snapshot.auditSchema.indexes = @($auditIndexDrift.stages[0].snapshot.auditSchema.indexes)
    if (Test-T21MySqlLegacyDataUpgradeReport $auditIndexDrift $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A candidate report that omitted the observed additive audit-index repair was accepted.'
    }
    Write-Output 'PASS: require exact additive audit-index repair evidence across candidate and rollback'

    $auditColumnDrift = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $auditColumnDrift.stages[1].snapshot.auditSchema.columns += (ConvertTo-Json -InputObject @('syncmoney_audit_log', 16, 'unexpected', 'varchar(36)', 'YES', $null, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', '') -Compress)
    if (Test-T21MySqlLegacyDataUpgradeReport $auditColumnDrift $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A candidate report that changed audit table columns was accepted.'
    }
    Write-Output 'PASS: reject audit-column drift across the 1.3.2 / 1.4 / rollback lifecycle'

    $missingSharedValidatorPin = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $missingSharedValidatorPin.sourcePins.Remove('sharedValidatorSha256')
    if (Test-T21MySqlLegacyDataUpgradeReport $missingSharedValidatorPin $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A legacy-data report missing the shared validator source pin was accepted.'
    }
    Write-Output 'PASS: require shared validator and shared test source pins'

    $missingRollback = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $missingRollback.stages = @($missingRollback.stages | Select-Object -First 2)
    if (Test-T21MySqlLegacyDataUpgradeReport $missingRollback $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A report without the 1.3.2 rollback read was accepted.'
    }
    Write-Output 'PASS: reject an incomplete legacy rollback lifecycle'

    $duplicateServerPid = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $duplicateServerPid.stages[2].processId = $duplicateServerPid.stages[0].processId
    if (Test-T21MySqlLegacyDataUpgradeReport $duplicateServerPid $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A lifecycle report that reused a server process ID was accepted.'
    }
    Write-Output 'PASS: reject reused server process identity across lifecycle stages'

    $unownedBackend = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $unownedBackend.backendProcesses.mysql.runOwned = $false
    if (Test-T21MySqlLegacyDataUpgradeReport $unownedBackend $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A report without run-owned MySQL evidence was accepted.'
    }
    Write-Output 'PASS: reject a backend without run-owned process evidence'

    $wrongRedisShutdownPid = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $wrongRedisShutdownPid.backendShutdowns[1].processId = 3333
    if (Test-T21MySqlLegacyDataUpgradeReport $wrongRedisShutdownPid $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A Redis shutdown PID that did not match its owned process was accepted.'
    }
    Write-Output 'PASS: reject a Redis shutdown PID mismatch'

    $wrongPlatform = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $wrongPlatform.server.build = 49
    if (Test-T21MySqlLegacyDataUpgradeReport $wrongPlatform $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A report for an unpinned Paper build was accepted.'
    }
    Write-Output 'PASS: reject a legacy-data report for a different Paper build'

    $wrongLog = $valid | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    [IO.File]::AppendAllText((Join-Path $root $wrongLog.platformLogs[0].path), "wrong hash`n")
    if (Test-T21MySqlLegacyDataUpgradeReport $wrongLog $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha) {
        throw 'A legacy stage log with post-capture hash drift was accepted.'
    }
    Write-Output 'PASS: reject post-capture stage-log drift'
} finally {
    if (Test-Path -LiteralPath $root) {
        $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]@('\', '/'))
        $rootFull = [IO.Path]::GetFullPath($root).TrimEnd([char[]]@('\', '/'))
        $rootParent = [IO.Path]::GetDirectoryName($rootFull)
        $rootName = [IO.Path]::GetFileName($rootFull)
        if (-not (Test-T21PathsEqual $rootParent $tempRoot) -or
            $rootName -notmatch '^t21-legacy-sql-upgrade-validation-[0-9a-f]{32}$' -or
            (Test-T21PathContainsReparsePoint $rootFull)) {
            throw "Refusing to remove unexpected fixture path: $rootFull"
        }
        Remove-Item -LiteralPath $rootFull -Recurse -Force
    }
}
