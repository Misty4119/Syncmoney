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
            mysql = [ordered]@{ product='MySQL'; version='8.0.42'; processId=1111; runOwned=$true; listenerOwnerVerified=$true }
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

try {
    $valid = New-T21ValidFixtureReport
    if (-not (Test-T21MySqlLegacyDataUpgradeReport $valid $endpoint.sha256 $expectedBaselineSha $expectedCandidateSha $expectedAcceptanceSha $expectedApiConsumerSha)) {
        throw 'A complete minimum-Paper MySQL legacy-data upgrade report was rejected.'
    }
    Write-Output 'PASS: accept existing 1.3.2 MySQL data through candidate startup/read and 1.3.2 rollback'

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
