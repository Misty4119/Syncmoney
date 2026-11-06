Set-StrictMode -Version Latest

function Get-T21PlatformLogSnapshotStageName([string]$StopStage) {
    switch ($StopStage) {
        'after-write' { return 'candidate-write' }
        'after-restart-read' { return 'candidate-restart-read' }
        'after-legacy-seed' { return 'legacy-seed' }
        'after-candidate-existing-read' { return 'candidate-existing-read' }
        'after-legacy-rollback-read' { return 'legacy-rollback-read' }
        'after-startup-failure' { return 'candidate-startup-failure' }
        'after-database-manager-init-failure' { return 'candidate-database-manager-init-failure' }
        'after-audit-v10-upgrade' { return 'candidate-audit-v10-upgrade' }
        default { throw "Unexpected platform log snapshot stage: $StopStage" }
    }
}

function Get-T21DatabaseManagerInitFailureValue([object]$Value, [string]$Name) {
    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Collections.IDictionary]) {
        if ($Value.Contains($Name)) { return ,$Value[$Name] }
        return $null
    }

    if ($Value -is [System.Management.Automation.PSCustomObject]) {
        $property = $Value.PSObject.Properties[$Name]
        if ($null -ne $property) { return ,$property.Value }
    }
    return $null
}

function Test-T21DatabaseManagerInitFailureFields([object]$Value, [string[]]$ExpectedFields) {
    if ($null -eq $Value) { return $false }

    if ($Value -is [System.Collections.IDictionary]) {
        $actualFields = @($Value.Keys | ForEach-Object { [string]$_ })
    } elseif ($Value -is [System.Management.Automation.PSCustomObject]) {
        $actualFields = @($Value.PSObject.Properties | Where-Object MemberType -eq 'NoteProperty' | ForEach-Object Name)
    } else {
        return $false
    }

    if ($actualFields.Count -ne $ExpectedFields.Count) { return $false }
    foreach ($field in $ExpectedFields) {
        if ($field -cnotin $actualFields) { return $false }
    }
    return $true
}

function Test-T21DatabaseManagerInitFailureBoolean([object]$Value, [string]$Name, [bool]$Expected) {
    $observed = Get-T21DatabaseManagerInitFailureValue $Value $Name
    return $observed -is [bool] -and $observed -eq $Expected
}

function Test-T21DatabaseManagerInitFailureInteger([object]$Value, [string]$Name, [long]$Expected) {
    $observed = Get-T21DatabaseManagerInitFailureValue $Value $Name
    if ($null -eq $observed) { return $false }
    $integerTypes = @([byte], [sbyte], [int16], [uint16], [int32], [uint32], [int64], [uint64])
    return $observed.GetType() -in $integerTypes -and [decimal]$observed -eq [decimal]$Expected
}

function Test-T21DatabaseManagerInitFailureString([object]$Value, [string]$Expected) {
    return $Value -is [string] -and $Value -ceq $Expected
}

function Test-T21DatabaseManagerInitFailureStringField([object]$Value, [string]$Name, [string]$Expected) {
    return Test-T21DatabaseManagerInitFailureString (Get-T21DatabaseManagerInitFailureValue $Value $Name) $Expected
}

function Test-T21DatabaseManagerInitFailureStringFields([object]$Value, [System.Collections.IDictionary]$ExpectedFields) {
    if ($null -eq $Value -or $null -eq $ExpectedFields) { return $false }
    foreach ($name in $ExpectedFields.Keys) {
        $expected = $ExpectedFields[$name]
        if ($expected -isnot [string] -or -not (Test-T21DatabaseManagerInitFailureStringField $Value ([string]$name) $expected)) {
            return $false
        }
    }
    return $true
}

function Test-T21DatabaseManagerInitFailureCleanGitStatus([object]$StatusEntries, [object]$ExitCode) {
    return $ExitCode -is [int] -and $ExitCode -eq 0 -and @($StatusEntries).Count -eq 0
}

function Test-T21DatabaseManagerInitFailureZeroInteger([object]$Value, [string]$Name) {
    return Test-T21DatabaseManagerInitFailureInteger $Value $Name 0
}

function New-T21DatabaseManagerInitFailureResult([string[]]$Failures) {
    return [pscustomobject]@{
        passed = @($Failures).Count -eq 0
        failures = @($Failures)
    }
}

function Test-T21DatabaseManagerInitFailureStartupLog(
    [string]$LogText,
    [string]$CandidateVersion,
    [ValidateSet('PostgreSQL', 'MySQL')][string]$DatabaseProduct = 'PostgreSQL',
    [string]$MySqlAppRole
) {
    $failures = [System.Collections.Generic.List[string]]::new()
    if ([string]::IsNullOrWhiteSpace($LogText)) {
        $failures.Add('server log is empty')
        return New-T21DatabaseManagerInitFailureResult $failures.ToArray()
    }
    if ([string]::IsNullOrWhiteSpace($CandidateVersion)) {
        $failures.Add('candidate version is empty')
        return New-T21DatabaseManagerInitFailureResult $failures.ToArray()
    }

    $databaseFailureMarker = if ($DatabaseProduct -ceq 'PostgreSQL') {
        'Failed to initialize database schema: ERROR: permission denied for schema public'
    } else {
        if ($MySqlAppRole -notmatch '^sm_t21_[0-9a-f]{8}$') {
            $failures.Add('MySQL application account is missing or malformed')
            return New-T21DatabaseManagerInitFailureResult $failures.ToArray()
        }
        "Failed to initialize database schema: CREATE command denied to user '$MySqlAppRole'@'127.0.0.1' for table 'players'"
    }
    $markers = @(
        'Loading server plugin Syncmoney v' + $CandidateVersion
        'Syncmoney-HikariCP - Start completed.'
    ) + @($databaseFailureMarker) + @(
        'Syncmoney-HikariCP - Shutdown completed.'
        'Syncmoney initialization failed; rolling back initialized owners'
        'Disabling Syncmoney v' + $CandidateVersion
    )
    $positions = [System.Collections.Generic.List[int]]::new()
    foreach ($marker in $markers) {
        $first = $LogText.IndexOf($marker, [StringComparison]::Ordinal)
        $last = $LogText.LastIndexOf($marker, [StringComparison]::Ordinal)
        if ($first -lt 0) {
            $failures.Add("missing startup marker: $marker")
        } elseif ($first -ne $last) {
            $failures.Add("duplicate startup marker: $marker")
        }
        $positions.Add($first)
    }

    if (@($positions | Where-Object { $_ -lt 0 }).Count -eq 0) {
        for ($index = 1; $index -lt $positions.Count; $index++) {
            if ($positions[$index] -le $positions[$index - 1]) {
                $failures.Add('startup, database denial, pool close, rollback, and plugin disable markers are out of order')
                break
            }
        }
    }
    if ($LogText.Contains('Syncmoney enabled successfully')) {
        $failures.Add('successfully enabled marker is present')
    }

    return New-T21DatabaseManagerInitFailureResult $failures.ToArray()
}

function Test-T21DatabaseManagerInitFailurePrivileges([object]$Privileges) {
    $expectedFields = @(
        'appRole', 'appRoleIsSuperuser', 'appRoleCanCreateDatabase', 'appRoleOwnsDatabase',
        'appRoleOwnsPublicSchema', 'targetDatabaseOwner', 'publicSchemaOwner',
        'bootstrapConnectGranted', 'targetConnectGranted', 'appRoleHasPublicUsage',
        'appRoleHasPublicCreate', 'publicHasCreate'
    )
    $failures = [System.Collections.Generic.List[string]]::new()
    if (-not (Test-T21DatabaseManagerInitFailureFields $Privileges $expectedFields)) {
        $failures.Add('privilege evidence fields are missing, extra, or malformed')
        return New-T21DatabaseManagerInitFailureResult $failures.ToArray()
    }

    $appRole = Get-T21DatabaseManagerInitFailureValue $Privileges 'appRole'
    $databaseOwner = Get-T21DatabaseManagerInitFailureValue $Privileges 'targetDatabaseOwner'
    $schemaOwner = Get-T21DatabaseManagerInitFailureValue $Privileges 'publicSchemaOwner'
    if ($appRole -isnot [string] -or [string]::IsNullOrWhiteSpace($appRole) -or $appRole -ceq 'postgres') {
        $failures.Add('application role is not a distinct non-empty role')
    }
    if ($databaseOwner -isnot [string] -or $databaseOwner -cne 'postgres' -or $databaseOwner -ceq $appRole) {
        $failures.Add('target database is not owned by postgres')
    }
    if ($schemaOwner -isnot [string] -or [string]::IsNullOrWhiteSpace($schemaOwner) -or $schemaOwner -ceq $appRole) {
        $failures.Add('application role owns public schema or its owner is unknown')
    }

    foreach ($field in @('appRoleIsSuperuser', 'appRoleCanCreateDatabase', 'appRoleOwnsDatabase', 'appRoleOwnsPublicSchema', 'appRoleHasPublicCreate', 'publicHasCreate')) {
        if (-not (Test-T21DatabaseManagerInitFailureBoolean $Privileges $field $false)) {
            $failures.Add("$field must be the Boolean value false")
        }
    }
    foreach ($field in @('bootstrapConnectGranted', 'targetConnectGranted', 'appRoleHasPublicUsage')) {
        if (-not (Test-T21DatabaseManagerInitFailureBoolean $Privileges $field $true)) {
            $failures.Add("$field must be the Boolean value true")
        }
    }

    return New-T21DatabaseManagerInitFailureResult $failures.ToArray()
}

function Test-T21DatabaseManagerInitFailureMySqlPrivileges([object]$Privileges) {
    $expectedFields = @('product', 'appRole', 'targetDatabase', 'authenticatedIdentity', 'grantStatements')
    $failures = [System.Collections.Generic.List[string]]::new()
    if (-not (Test-T21DatabaseManagerInitFailureFields $Privileges $expectedFields)) {
        $failures.Add('MySQL privilege evidence fields are missing, extra, or malformed')
        return New-T21DatabaseManagerInitFailureResult $failures.ToArray()
    }

    $appRole = Get-T21DatabaseManagerInitFailureValue $Privileges 'appRole'
    $targetDatabase = Get-T21DatabaseManagerInitFailureValue $Privileges 'targetDatabase'
    $identity = Get-T21DatabaseManagerInitFailureValue $Privileges 'authenticatedIdentity'
    $grants = Get-T21DatabaseManagerInitFailureValue $Privileges 'grantStatements'
    if (-not (Test-T21DatabaseManagerInitFailureStringField $Privileges 'product' 'MySQL')) {
        $failures.Add('privilege evidence is not for MySQL')
    }
    if ($appRole -isnot [string] -or $appRole -notmatch '^sm_t21_[0-9a-f]{8}$') {
        $failures.Add('MySQL application account is not the isolated run account')
    }
    if ($targetDatabase -isnot [string] -or $targetDatabase -notmatch '^syncmoney_t21_[0-9a-f]{8}$') {
        $failures.Add('MySQL target database is not the isolated run database')
    }
    if ($appRole -is [string] -and $targetDatabase -is [string]) {
        $account = [char]96 + $appRole + [char]96 + '@' + [char]96 + '127.0.0.1' + [char]96
        $database = [char]96 + $targetDatabase + [char]96
        $expectedIdentity = $appRole + '@127.0.0.1' + [char]9 + $targetDatabase
        $expectedGrants = @(
            "GRANT USAGE ON *.* TO $account"
            "GRANT SELECT, INSERT, UPDATE, DELETE ON $database.* TO $account"
        )
        if (-not (Test-T21DatabaseManagerInitFailureString $identity $expectedIdentity)) {
            $failures.Add('MySQL application connection did not authenticate to the isolated database as the pinned loopback account')
        }
        if ($grants -isnot [System.Array] -or $grants.Count -ne $expectedGrants.Count) {
            $failures.Add('MySQL grant evidence must contain exactly the isolated USAGE and DML grants')
        } else {
            for ($index = 0; $index -lt $expectedGrants.Count; $index++) {
                if ($grants[$index] -isnot [string] -or $grants[$index] -cne $expectedGrants[$index]) {
                    $failures.Add('MySQL grants include unexpected privileges or do not match the isolated account/database')
                    break
                }
            }
        }
    }

    return New-T21DatabaseManagerInitFailureResult $failures.ToArray()
}

function Test-T21DatabaseManagerInitFailureTarget([string]$DatabaseProduct, [string]$PlatformEndpoint) {
    return $DatabaseProduct -ceq 'PostgreSQL' -and $PlatformEndpoint -in @('Minimum', 'MinimumFolia', 'CurrentMaximum', 'CurrentMaximumFolia', 'MinimumCanvas', 'CurrentMaximumCanvas')
}

function Get-T21DatabaseManagerInitServerJarPinName([string]$PlatformEndpoint, [object]$SourcePins) {
    if ($PlatformEndpoint -in @('MinimumFolia', 'CurrentMaximumFolia', 'MinimumCanvas', 'CurrentMaximumCanvas')) { return 'serverJarSha256' }
    if ($null -ne (Get-T21DatabaseManagerInitFailureValue $SourcePins 'serverJarSha256')) { return 'serverJarSha256' }
    return 'paperJarSha256'
}

function Test-T21DatabaseManagerInitExpectedPlatformServerPin([string]$PlatformEndpoint, [string]$ExpectedServerSha256) {
    if ($ExpectedServerSha256 -notmatch '^[0-9a-fA-F]{64}$') { return $false }
    try {
        $endpoint = Get-T21PlatformEndpoint -PlatformEndpoint $PlatformEndpoint
        return $endpoint.sha256 -ceq $ExpectedServerSha256.ToLowerInvariant()
    } catch {
        return $false
    }
}

function Test-T21DatabaseManagerInitFailureSchemaState(
    [object]$SchemaState,
    [ValidateSet('PostgreSQL', 'MySQL')][string]$DatabaseProduct = 'PostgreSQL'
) {
    $failures = [System.Collections.Generic.List[string]]::new()
    if (-not (Test-T21DatabaseManagerInitFailureFields $SchemaState @('before', 'after'))) {
        $failures.Add('schema evidence must contain exactly before and after snapshots')
        return New-T21DatabaseManagerInitFailureResult $failures.ToArray()
    }

    $snapshotFields = if ($DatabaseProduct -ceq 'PostgreSQL') {
        @('playersTableExists', 'schemaVersionTableExists', 'publicTableCount', 'publicObjectCount')
    } else {
        @('playersTableExists', 'schemaVersionTableExists', 'databaseTableCount', 'databaseObjectCount')
    }
    $tableCountField = if ($DatabaseProduct -ceq 'PostgreSQL') { 'publicTableCount' } else { 'databaseTableCount' }
    $objectCountField = if ($DatabaseProduct -ceq 'PostgreSQL') { 'publicObjectCount' } else { 'databaseObjectCount' }
    foreach ($stage in @('before', 'after')) {
        $snapshot = Get-T21DatabaseManagerInitFailureValue $SchemaState $stage
        if (-not (Test-T21DatabaseManagerInitFailureFields $snapshot $snapshotFields)) {
            $failures.Add("$stage schema snapshot fields are missing, extra, or malformed")
            continue
        }
        if (-not (Test-T21DatabaseManagerInitFailureBoolean $snapshot 'playersTableExists' $false)) {
            $failures.Add("players table exists in the $stage schema snapshot")
        }
        if (-not (Test-T21DatabaseManagerInitFailureBoolean $snapshot 'schemaVersionTableExists' $false)) {
            $failures.Add("schema-version table exists in the $stage schema snapshot")
        }
        if (-not (Test-T21DatabaseManagerInitFailureZeroInteger $snapshot $tableCountField)) {
            $failures.Add("database schema table count is not the integer zero in the $stage snapshot")
        }
        if (-not (Test-T21DatabaseManagerInitFailureZeroInteger $snapshot $objectCountField)) {
            $failures.Add("database schema object count is not the integer zero in the $stage snapshot")
        }
    }

    return New-T21DatabaseManagerInitFailureResult $failures.ToArray()
}

function Test-T21DatabaseManagerInitFailureCleanup(
    [object]$Cleanup,
    [ValidateSet('PostgreSQL', 'MySQL')][string]$DatabaseProduct = 'PostgreSQL'
) {
    $sqlStoppedField = if ($DatabaseProduct -ceq 'PostgreSQL') { 'postgresStopped' } else { 'mysqlStopped' }
    $expectedFields = @(
        'forcedCleanup', 'serverStopped', 'serverProcessCount', $sqlStoppedField, 'redisStopped',
        'runOwnedPortsClosed', 'sqlOtherConnections', 'redisOtherClients', 'redisDbSize', 'cleanupFailures'
    )
    $failures = [System.Collections.Generic.List[string]]::new()
    if (-not (Test-T21DatabaseManagerInitFailureFields $Cleanup $expectedFields)) {
        $failures.Add('cleanup evidence fields are missing, extra, or malformed')
        return New-T21DatabaseManagerInitFailureResult $failures.ToArray()
    }

    foreach ($field in @('forcedCleanup')) {
        if (-not (Test-T21DatabaseManagerInitFailureBoolean $Cleanup $field $false)) {
            $failures.Add("$field must be the Boolean value false")
        }
    }
    foreach ($field in @('serverStopped', $sqlStoppedField, 'redisStopped', 'runOwnedPortsClosed')) {
        if (-not (Test-T21DatabaseManagerInitFailureBoolean $Cleanup $field $true)) {
            $failures.Add("$field must be the Boolean value true")
        }
    }
    foreach ($field in @('serverProcessCount', 'sqlOtherConnections', 'redisOtherClients', 'redisDbSize')) {
        if (-not (Test-T21DatabaseManagerInitFailureZeroInteger $Cleanup $field)) {
            $failures.Add("$field must be the integer zero")
        }
    }

    $cleanupFailures = Get-T21DatabaseManagerInitFailureValue $Cleanup 'cleanupFailures'
    if ($cleanupFailures -isnot [System.Array] -or $cleanupFailures.Count -ne 0) {
        $failures.Add('cleanupFailures must be an empty JSON array')
    }
    return New-T21DatabaseManagerInitFailureResult $failures.ToArray()
}

function Test-T21DatabaseManagerInitFailureReport(
    [object]$Report,
    [string]$ExpectedServerSha256,
    [string]$ExpectedCandidateSha256,
    [string]$ExpectedPlugDevSourceCommit,
    [string]$ExpectedPlugDevCliSha256,
    [string]$ExpectedPlugDevCliDistManifestSha256,
    [string]$ExpectedAcceptanceSha256,
    [string]$ExpectedApiConsumerSha256,
    [string]$ExpectedSourceCommit,
    [ValidateSet('Minimum', 'MinimumFolia', 'CurrentMaximum', 'CurrentMaximumFolia', 'MinimumCanvas', 'CurrentMaximumCanvas')][string]$ExpectedPlatformEndpoint = 'CurrentMaximum',
    [ValidateSet('PostgreSQL', 'MySQL')][string]$ExpectedDatabaseProduct = 'PostgreSQL'
) {
    $expectedServer = Get-T21PlatformEndpoint -PlatformEndpoint $ExpectedPlatformEndpoint
    $failure = Get-T21DatabaseManagerInitFailureValue $Report 'failure'
    if ($null -eq $Report -or
        -not (Test-T21DatabaseManagerInitFailureTarget $ExpectedDatabaseProduct $ExpectedPlatformEndpoint) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $Report 'task' "T21-SQL-POOL-INITIAL-SCHEMA-FAILURE-$ExpectedDatabaseProduct-$ExpectedPlatformEndpoint") -or
        -not (Test-T21DatabaseManagerInitFailureStringField $Report 'status' 'PASS_BOUNDED_SUBCASE') -or
        $null -ne $failure) { return $false }

    $sourceCommit = Get-T21DatabaseManagerInitFailureValue $Report 'sourceCommit'
    if ($sourceCommit -isnot [string] -or $sourceCommit -notmatch '^[0-9a-f]{40}$' -or
        $sourceCommit -cne $ExpectedSourceCommit.ToLowerInvariant() -or
        -not (Test-T21DatabaseManagerInitFailureStringField $Report 'sourceCommitAfter' $sourceCommit) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $Report 'sourceWorkingTreeDirty' $false) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $Report 'sourceWorkingTreeDirtyAfter' $false)) { return $false }

    $server = Get-T21DatabaseManagerInitFailureValue $Report 'server'
    if (-not (Test-T21DatabaseManagerInitFailureStringField $server 'software' $expectedServer.software) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $server 'minecraftVersion' $expectedServer.minecraftVersion) -or
        -not (Test-T21DatabaseManagerInitFailureInteger $server 'build' $expectedServer.build) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $server 'sha256' $ExpectedServerSha256.ToLowerInvariant())) { return $false }

    $candidate = Get-T21DatabaseManagerInitFailureValue $Report 'candidate'
    if (-not (Test-T21DatabaseManagerInitFailureStringField $candidate 'version' '1.4.0-alpha.1') -or
        -not (Test-T21DatabaseManagerInitFailureStringField $candidate 'sha256' $ExpectedCandidateSha256.ToLowerInvariant())) { return $false }

    $artifacts = Get-T21DatabaseManagerInitFailureValue $Report 'artifacts'
    if (-not (Test-T21DatabaseManagerInitFailureStringField $artifacts 'acceptanceSha256' $ExpectedAcceptanceSha256.ToLowerInvariant()) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $artifacts 'apiConsumerSha256' $ExpectedApiConsumerSha256.ToLowerInvariant())) { return $false }

    $sqlBackend = Get-T21DatabaseManagerInitFailureValue $Report 'sqlBackend'
    $expectedSqlVersion = if ($ExpectedDatabaseProduct -ceq 'PostgreSQL') { '18.1' } else { '8.0.42' }
    if (-not (Test-T21DatabaseManagerInitFailureStringField $sqlBackend 'product' $ExpectedDatabaseProduct) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $sqlBackend 'version' $expectedSqlVersion)) { return $false }
    $redis = Get-T21DatabaseManagerInitFailureValue $Report 'redis'
    if (-not (Test-T21DatabaseManagerInitFailureStringField $redis 'version' '8.10.1')) { return $false }

    $configuration = Get-T21DatabaseManagerInitFailureValue $Report 'configuration'
    $expectedDatabaseType = if ($ExpectedDatabaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
    if (-not (Test-T21DatabaseManagerInitFailureStringField $configuration 'mode' 'sync') -or
        -not (Test-T21DatabaseManagerInitFailureStringField $configuration 'databaseType' $expectedDatabaseType) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $configuration 'databaseEnabled' $true) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $configuration 'databaseIsolated' $true) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $configuration 'redisEnabled' $true) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $configuration 'redisIsolated' $true) -or
        -not (Test-T21DatabaseManagerInitFailureInteger $configuration 'redisDatabase' 15)) { return $false }

    $privileges = Get-T21DatabaseManagerInitFailureValue $Report 'databaseDdlPrivileges'
    $privilegeResult = if ($ExpectedDatabaseProduct -ceq 'PostgreSQL') {
        Test-T21DatabaseManagerInitFailurePrivileges $privileges
    } else {
        Test-T21DatabaseManagerInitFailureMySqlPrivileges $privileges
    }
    if (-not $privilegeResult.passed) { return $false }

    $expectedFailureMessage = if ($ExpectedDatabaseProduct -ceq 'PostgreSQL') {
        'Failed to initialize database schema: ERROR: permission denied for schema public'
    } else {
        $appRole = Get-T21DatabaseManagerInitFailureValue $privileges 'appRole'
        if ($appRole -isnot [string] -or $appRole -notmatch '^sm_t21_[0-9a-f]{8}$') { return $false }
        "Failed to initialize database schema: CREATE command denied to user '$appRole'@'127.0.0.1' for table 'players'"
    }
    $startup = Get-T21DatabaseManagerInitFailureValue $Report 'startupObservation'
    if (-not (Test-T21DatabaseManagerInitFailureStringField $startup 'stage' 'candidate-startup-failure') -or
        -not (Test-T21DatabaseManagerInitFailureStringField $startup 'pluginVersion' '1.4.0-alpha.1') -or
        -not (Test-T21DatabaseManagerInitFailureStringField $startup 'expectedFailure' 'database-manager-initial-schema-failure') -or
        -not (Test-T21DatabaseManagerInitFailureStringField $startup 'failureMessage' $expectedFailureMessage) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $startup 'enabled' $false) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $startup 'failureObserved' $true) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $startup 'rollbackComplete' $true) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $startup 'sqlPoolStartObserved' $true) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $startup 'sqlPoolShutdownObserved' $true) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $startup 'disabledMarker' $true) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $startup 'successMarkerObserved' $false)) { return $false }

    if ($ExpectedDatabaseProduct -ceq 'MySQL' -and
        -not (Test-T21DatabaseManagerInitFailureStringField $configuration 'databaseName' (Get-T21DatabaseManagerInitFailureValue (Get-T21DatabaseManagerInitFailureValue $Report 'databaseDdlPrivileges') 'targetDatabase'))) { return $false }
    $schemaPair = [pscustomobject]@{
        before = Get-T21DatabaseManagerInitFailureValue $Report 'schemaStateBefore'
        after = Get-T21DatabaseManagerInitFailureValue $Report 'schemaStateAfter'
    }
    if (-not (Test-T21DatabaseManagerInitFailureSchemaState $schemaPair $ExpectedDatabaseProduct).passed) { return $false }

    $cleanupResult = Test-T21DatabaseManagerInitFailureCleanup (Get-T21DatabaseManagerInitFailureValue $Report 'resourceCleanup') $ExpectedDatabaseProduct
    if (-not $cleanupResult.passed) { return $false }
    $cleanupFailures = Get-T21DatabaseManagerInitFailureValue $Report 'cleanupFailures'
    if ($cleanupFailures -isnot [System.Array] -or $cleanupFailures.Count -ne 0) { return $false }

    $plugDev = Get-T21DatabaseManagerInitFailureValue $Report 'plugDev'
    if (-not (Test-T21DatabaseManagerInitFailureStringField $plugDev 'sourceCommit' $ExpectedPlugDevSourceCommit.ToLowerInvariant()) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $plugDev 'cliSha256' $ExpectedPlugDevCliSha256.ToLowerInvariant()) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $plugDev 'cliDistManifestSha256Before' $ExpectedPlugDevCliDistManifestSha256.ToLowerInvariant()) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $plugDev 'cliDistManifestSha256After' $ExpectedPlugDevCliDistManifestSha256.ToLowerInvariant())) { return $false }

    $sourcePins = Get-T21DatabaseManagerInitFailureValue $Report 'sourcePins'
    $runner = Get-T21DatabaseManagerInitFailureValue $Report 'runner'
    $sourcePinPairs = @(
        [pscustomobject]@{ sourcePin = 'runnerSha256'; runnerPin = 'sha256' },
        [pscustomobject]@{ sourcePin = 'validatorSha256'; runnerPin = 'startupFailureValidatorSha256' },
        [pscustomobject]@{ sourcePin = 'validatorTestSha256'; runnerPin = 'startupFailureValidatorTestSha256' },
        [pscustomobject]@{ sourcePin = 'sharedValidatorSha256'; runnerPin = 'validatorSha256' },
        [pscustomobject]@{ sourcePin = 'sharedValidatorTestSha256'; runnerPin = 'testSha256' },
        [pscustomobject]@{ sourcePin = 'databaseManagerInitFailureValidatorSha256'; runnerPin = 'databaseManagerInitFailureValidatorSha256' },
        [pscustomobject]@{ sourcePin = 'databaseManagerInitFailureTestSha256'; runnerPin = 'databaseManagerInitFailureTestSha256' }
    )
    foreach ($pair in $sourcePinPairs) {
        $sourcePin = Get-T21DatabaseManagerInitFailureValue $sourcePins $pair.sourcePin
        $runnerPin = Get-T21DatabaseManagerInitFailureValue $runner $pair.runnerPin
        if ($sourcePin -isnot [string] -or $runnerPin -isnot [string] -or
            $sourcePin -notmatch '^[0-9a-f]{64}$' -or $runnerPin -notmatch '^[0-9a-f]{64}$' -or $sourcePin -cne $runnerPin) {
            return $false
        }
    }
    foreach ($field in @('databaseManagerSourceSha256', 'storageManagerSourceSha256', 'syncmoneyLifecycleSourceSha256')) {
        $sourceHash = Get-T21DatabaseManagerInitFailureValue $sourcePins $field
        if ($sourceHash -isnot [string] -or $sourceHash -notmatch '^[0-9a-f]{64}$') { return $false }
    }
    $serverJarSha256 = Get-T21DatabaseManagerInitFailureValue $sourcePins 'serverJarSha256'
    if ($null -eq $serverJarSha256 -and $expectedServer.software -ceq 'paper') {
        # Preserve reports written before the server-neutral pin name was introduced.
        $serverJarSha256 = Get-T21DatabaseManagerInitFailureValue $sourcePins 'paperJarSha256'
    }
    if (-not (Test-T21DatabaseManagerInitFailureString $serverJarSha256 $ExpectedServerSha256.ToLowerInvariant())) { return $false }
    if (-not (Test-T21DatabaseManagerInitFailureStringField $sourcePins 'sourceCommit' $sourceCommit) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $sourcePins 'plugDevCommit' $ExpectedPlugDevSourceCommit.ToLowerInvariant()) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $sourcePins 'plugDevCliSha256' $ExpectedPlugDevCliSha256.ToLowerInvariant()) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $sourcePins 'plugDevCliDistManifestSha256' $ExpectedPlugDevCliDistManifestSha256.ToLowerInvariant()) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $sourcePins 'candidateJarSha256' $ExpectedCandidateSha256.ToLowerInvariant()) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $sourcePins 'acceptanceJarSha256' $ExpectedAcceptanceSha256.ToLowerInvariant()) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $sourcePins 'apiConsumerJarSha256' $ExpectedApiConsumerSha256.ToLowerInvariant())) { return $false }

    return $true
}
