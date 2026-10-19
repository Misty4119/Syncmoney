Set-StrictMode -Version Latest

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

function Test-T21DatabaseManagerInitFailureStartupLog([string]$LogText, [string]$CandidateVersion) {
    $failures = [System.Collections.Generic.List[string]]::new()
    if ([string]::IsNullOrWhiteSpace($LogText)) {
        $failures.Add('server log is empty')
        return New-T21DatabaseManagerInitFailureResult $failures.ToArray()
    }
    if ([string]::IsNullOrWhiteSpace($CandidateVersion)) {
        $failures.Add('candidate version is empty')
        return New-T21DatabaseManagerInitFailureResult $failures.ToArray()
    }

    $markers = @(
        'Loading server plugin Syncmoney v' + $CandidateVersion
        'Syncmoney-HikariCP - Start completed.'
        'Failed to initialize database schema: ERROR: permission denied for schema public'
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
                $failures.Add('startup, PostgreSQL denial, pool close, rollback, and plugin disable markers are out of order')
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

function Test-T21DatabaseManagerInitFailureTarget([string]$DatabaseProduct, [string]$PlatformEndpoint) {
    return $DatabaseProduct -ceq 'PostgreSQL' -and $PlatformEndpoint -ceq 'CurrentMaximum'
}

function Test-T21DatabaseManagerInitFailureSchemaState([object]$SchemaState) {
    $failures = [System.Collections.Generic.List[string]]::new()
    if (-not (Test-T21DatabaseManagerInitFailureFields $SchemaState @('before', 'after'))) {
        $failures.Add('schema evidence must contain exactly before and after snapshots')
        return New-T21DatabaseManagerInitFailureResult $failures.ToArray()
    }

    foreach ($stage in @('before', 'after')) {
        $snapshot = Get-T21DatabaseManagerInitFailureValue $SchemaState $stage
        if (-not (Test-T21DatabaseManagerInitFailureFields $snapshot @('playersTableExists', 'schemaVersionTableExists', 'publicTableCount', 'publicObjectCount'))) {
            $failures.Add("$stage schema snapshot fields are missing, extra, or malformed")
            continue
        }
        if (-not (Test-T21DatabaseManagerInitFailureBoolean $snapshot 'playersTableExists' $false)) {
            $failures.Add("players table exists in the $stage schema snapshot")
        }
        if (-not (Test-T21DatabaseManagerInitFailureBoolean $snapshot 'schemaVersionTableExists' $false)) {
            $failures.Add("schema-version table exists in the $stage schema snapshot")
        }
        if (-not (Test-T21DatabaseManagerInitFailureZeroInteger $snapshot 'publicTableCount')) {
            $failures.Add("public schema table count is not the integer zero in the $stage snapshot")
        }
        if (-not (Test-T21DatabaseManagerInitFailureZeroInteger $snapshot 'publicObjectCount')) {
            $failures.Add("public schema object count is not the integer zero in the $stage snapshot")
        }
    }

    return New-T21DatabaseManagerInitFailureResult $failures.ToArray()
}

function Test-T21DatabaseManagerInitFailureCleanup([object]$Cleanup) {
    $expectedFields = @(
        'forcedCleanup', 'serverStopped', 'serverProcessCount', 'postgresStopped', 'redisStopped',
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
    foreach ($field in @('serverStopped', 'postgresStopped', 'redisStopped', 'runOwnedPortsClosed')) {
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
    [string]$ExpectedSourceCommit
) {
    $failure = Get-T21DatabaseManagerInitFailureValue $Report 'failure'
    if ($null -eq $Report -or
        -not (Test-T21DatabaseManagerInitFailureStringField $Report 'task' 'T21-SQL-POOL-INITIAL-SCHEMA-FAILURE-PostgreSQL-CurrentMaximum') -or
        -not (Test-T21DatabaseManagerInitFailureStringField $Report 'status' 'PASS_BOUNDED_SUBCASE') -or
        $null -ne $failure) { return $false }

    $sourceCommit = Get-T21DatabaseManagerInitFailureValue $Report 'sourceCommit'
    if ($sourceCommit -isnot [string] -or $sourceCommit -notmatch '^[0-9a-f]{40}$' -or
        $sourceCommit -cne $ExpectedSourceCommit.ToLowerInvariant() -or
        -not (Test-T21DatabaseManagerInitFailureStringField $Report 'sourceCommitAfter' $sourceCommit) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $Report 'sourceWorkingTreeDirty' $false) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $Report 'sourceWorkingTreeDirtyAfter' $false)) { return $false }

    $server = Get-T21DatabaseManagerInitFailureValue $Report 'server'
    if (-not (Test-T21DatabaseManagerInitFailureStringField $server 'software' 'paper') -or
        -not (Test-T21DatabaseManagerInitFailureStringField $server 'minecraftVersion' '26.3') -or
        -not (Test-T21DatabaseManagerInitFailureStringField $server 'build' '200') -or
        -not (Test-T21DatabaseManagerInitFailureStringField $server 'sha256' $ExpectedServerSha256.ToLowerInvariant())) { return $false }

    $candidate = Get-T21DatabaseManagerInitFailureValue $Report 'candidate'
    if (-not (Test-T21DatabaseManagerInitFailureStringField $candidate 'version' '1.4.0-alpha.1') -or
        -not (Test-T21DatabaseManagerInitFailureStringField $candidate 'sha256' $ExpectedCandidateSha256.ToLowerInvariant())) { return $false }

    $artifacts = Get-T21DatabaseManagerInitFailureValue $Report 'artifacts'
    if (-not (Test-T21DatabaseManagerInitFailureStringField $artifacts 'acceptanceSha256' $ExpectedAcceptanceSha256.ToLowerInvariant()) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $artifacts 'apiConsumerSha256' $ExpectedApiConsumerSha256.ToLowerInvariant())) { return $false }

    $sqlBackend = Get-T21DatabaseManagerInitFailureValue $Report 'sqlBackend'
    if (-not (Test-T21DatabaseManagerInitFailureStringField $sqlBackend 'product' 'PostgreSQL') -or
        -not (Test-T21DatabaseManagerInitFailureStringField $sqlBackend 'version' '18.1')) { return $false }
    $redis = Get-T21DatabaseManagerInitFailureValue $Report 'redis'
    if (-not (Test-T21DatabaseManagerInitFailureStringField $redis 'version' '8.10.1')) { return $false }

    $configuration = Get-T21DatabaseManagerInitFailureValue $Report 'configuration'
    if (-not (Test-T21DatabaseManagerInitFailureStringField $configuration 'mode' 'sync') -or
        -not (Test-T21DatabaseManagerInitFailureStringField $configuration 'databaseType' 'postgresql') -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $configuration 'databaseEnabled' $true) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $configuration 'databaseIsolated' $true) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $configuration 'redisEnabled' $true) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $configuration 'redisIsolated' $true) -or
        -not (Test-T21DatabaseManagerInitFailureInteger $configuration 'redisDatabase' 15)) { return $false }

    $startup = Get-T21DatabaseManagerInitFailureValue $Report 'startupObservation'
    if (-not (Test-T21DatabaseManagerInitFailureStringField $startup 'stage' 'candidate-startup-failure') -or
        -not (Test-T21DatabaseManagerInitFailureStringField $startup 'pluginVersion' '1.4.0-alpha.1') -or
        -not (Test-T21DatabaseManagerInitFailureStringField $startup 'expectedFailure' 'database-manager-initial-schema-failure') -or
        -not (Test-T21DatabaseManagerInitFailureStringField $startup 'failureMessage' 'Failed to initialize database schema: ERROR: permission denied for schema public') -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $startup 'enabled' $false) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $startup 'failureObserved' $true) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $startup 'rollbackComplete' $true) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $startup 'sqlPoolStartObserved' $true) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $startup 'sqlPoolShutdownObserved' $true) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $startup 'disabledMarker' $true) -or
        -not (Test-T21DatabaseManagerInitFailureBoolean $startup 'successMarkerObserved' $false)) { return $false }

    $privilegeResult = Test-T21DatabaseManagerInitFailurePrivileges (Get-T21DatabaseManagerInitFailureValue $Report 'databaseDdlPrivileges')
    if (-not $privilegeResult.passed) { return $false }
    $schemaPair = [pscustomobject]@{
        before = Get-T21DatabaseManagerInitFailureValue $Report 'schemaStateBefore'
        after = Get-T21DatabaseManagerInitFailureValue $Report 'schemaStateAfter'
    }
    if (-not (Test-T21DatabaseManagerInitFailureSchemaState $schemaPair).passed) { return $false }

    $cleanupResult = Test-T21DatabaseManagerInitFailureCleanup (Get-T21DatabaseManagerInitFailureValue $Report 'resourceCleanup')
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
    if (-not (Test-T21DatabaseManagerInitFailureStringField $sourcePins 'sourceCommit' $sourceCommit) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $sourcePins 'plugDevCommit' $ExpectedPlugDevSourceCommit.ToLowerInvariant()) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $sourcePins 'plugDevCliSha256' $ExpectedPlugDevCliSha256.ToLowerInvariant()) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $sourcePins 'plugDevCliDistManifestSha256' $ExpectedPlugDevCliDistManifestSha256.ToLowerInvariant()) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $sourcePins 'paperJarSha256' $ExpectedServerSha256.ToLowerInvariant()) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $sourcePins 'candidateJarSha256' $ExpectedCandidateSha256.ToLowerInvariant()) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $sourcePins 'acceptanceJarSha256' $ExpectedAcceptanceSha256.ToLowerInvariant()) -or
        -not (Test-T21DatabaseManagerInitFailureStringField $sourcePins 'apiConsumerJarSha256' $ExpectedApiConsumerSha256.ToLowerInvariant())) { return $false }

    return $true
}
