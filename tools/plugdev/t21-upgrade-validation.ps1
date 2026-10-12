function Get-T21PlatformIdentity([string]$serverLogText) {
    $pattern = '(?im)^.*?Loading\s+(?<software>Paper|Folia|Canvas)\s+(?<version>\d+(?:\.\d+){1,2})-(?<build>\d+)\b.*?\bfor Minecraft\s+(?<minecraftVersion>\d+(?:\.\d+){1,2})\b.*$'
    $matches = @([regex]::Matches($serverLogText, $pattern))
    if ($matches.Count -eq 0) {
        throw 'Could not derive Paper/Folia/Canvas runtime identity from the server startup log.'
    }

    $identities = @($matches | ForEach-Object {
        '{0}|{1}|{2}|{3}' -f $_.Groups['software'].Value.ToLowerInvariant(),
            $_.Groups['version'].Value, $_.Groups['build'].Value, $_.Groups['minecraftVersion'].Value
    } | Select-Object -Unique)
    if ($identities.Count -ne 1) {
        throw 'Server startup log contains conflicting Paper/Folia/Canvas runtime identities.'
    }

    $match = $matches[0]
    return [ordered]@{
        software = $match.Groups['software'].Value.ToLowerInvariant()
        runtimeVersion = $match.Groups['version'].Value
        build = [int]$match.Groups['build'].Value
        minecraftVersion = $match.Groups['minecraftVersion'].Value
    }
}

function Assert-T21PlatformIdentity(
    [object]$observed,
    [string]$expectedSoftware,
    [string]$expectedMinecraftVersion,
    [int]$expectedBuild
) {
    if ($null -eq $observed -or
        $observed.software -cne $expectedSoftware.ToLowerInvariant() -or
        $observed.minecraftVersion -cne $expectedMinecraftVersion -or
        [int]$observed.build -ne $expectedBuild -or
        $observed.runtimeVersion -cne $expectedMinecraftVersion) {
        $observedIdentity = if ($null -eq $observed) { '<missing>' } else {
            '{0} {1}#{2} (Minecraft {3})' -f $observed.software, $observed.runtimeVersion,
                $observed.build, $observed.minecraftVersion
        }
        throw "Server runtime identity mismatch: expected $expectedSoftware $expectedMinecraftVersion`#$expectedBuild; observed $observedIdentity."
    }
}

function Get-T21ShutdownFailureMarkers([string]$serverLogText) {
    $pattern = '(?im)(?:CRITICAL HEALTH:|Shutdown step failed:|DB writer stopped with \d+ stale transfer|DB writer drain timed out|DB writer is still using storage after shutdown deadline|Failed while draining database writer|Failed to process batch to DB|Failed to flush \d+ audit records)'
    return @([regex]::Matches($serverLogText, $pattern) |
        ForEach-Object { $_.Value.Trim() } | Select-Object -Unique)
}

function Test-T21RuntimeObservationCoverage([object[]]$runtimeObservations, [string[]]$serverNames) {
    return Test-T21StageObservationCoverage $runtimeObservations $serverNames
}

function Test-T21StageObservationCoverage([object[]]$observations, [string[]]$serverNames) {
    if ($serverNames.Count -eq 0 -or $observations.Count -ne (3 * $serverNames.Count)) {
        return $false
    }

    foreach ($observation in $observations) {
        if ($null -eq $observation) { return $false }
        if ($observation -is [Collections.IDictionary]) {
            if (-not $observation.Contains('serverName') -or -not $observation.Contains('stage')) { return $false }
        } elseif ($null -eq $observation.PSObject.Properties['serverName'] -or
            $null -eq $observation.PSObject.Properties['stage']) {
            return $false
        }
    }

    $stageKinds = @('baseline-1.3.2', 'upgrade-1.4', 'rollback-1.3.2')
    for ($nodeIndex = 0; $nodeIndex -lt $serverNames.Count; $nodeIndex++) {
        $serverName = $serverNames[$nodeIndex]
        $nodeObservations = @($observations | Where-Object { $_.serverName -ceq $serverName })
        if ($nodeObservations.Count -ne $stageKinds.Count) { return $false }

        $stageSuffix = '-node-' + ($nodeIndex + 1)
        foreach ($stageKind in $stageKinds) {
            if (@($nodeObservations | Where-Object { $_.stage -ceq ($stageKind + $stageSuffix) }).Count -ne 1) {
                return $false
            }
        }
    }

    return $true
}

function Test-T21RuntimeArtifactPinCoverage([object[]]$runtimePluginArtifacts, [string[]]$serverNames) {
    if ($runtimePluginArtifacts.Count -ne (3 * $serverNames.Count) -or
        -not (Test-T21StageObservationCoverage $runtimePluginArtifacts $serverNames)) {
        return $false
    }

    foreach ($artifactObservation in $runtimePluginArtifacts) {
        if ($artifactObservation -is [Collections.IDictionary]) {
            if (-not $artifactObservation.Contains('syncmoneyArtifactPin')) { return $false }
            $pinObservation = $artifactObservation['syncmoneyArtifactPin']
        } else {
            $pinProperty = $artifactObservation.PSObject.Properties['syncmoneyArtifactPin']
            if ($null -eq $pinProperty) { return $false }
            $pinObservation = $pinProperty.Value
        }
        if ($null -eq $pinObservation) { return $false }

        if ($pinObservation -is [Collections.IDictionary]) {
            if (-not $pinObservation.Contains('matchesExpectedPin')) { return $false }
            $matchesExpectedPin = $pinObservation['matchesExpectedPin']
        } else {
            $pinProperty = $pinObservation.PSObject.Properties['matchesExpectedPin']
            if ($null -eq $pinProperty) { return $false }
            $matchesExpectedPin = $pinProperty.Value
        }
        if ($matchesExpectedPin -ne $true) { return $false }
    }

    return $true
}

function Get-T21ObservedValue([object]$observation, [string]$name) {
    if ($null -eq $observation) { return $null }
    if ($observation -is [Collections.IDictionary]) {
        if (-not $observation.Contains($name)) { return $null }
        return $observation[$name]
    }
    $property = $observation.PSObject.Properties[$name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Test-T21ExpectedExitCode([object]$actual, [int]$expected) {
    if ($null -eq $actual) { return $false }
    $parsed = 0
    if (-not [int]::TryParse([string]$actual, [ref]$parsed)) { return $false }
    return $parsed -eq $expected
}

function Test-T21BackendShutdownCoverage([object[]]$observations) {
    if ($observations.Count -ne 4) { return $false }

    $expectedStages = @('preupgrade-backup', 'final-cleanup')
    foreach ($stage in $expectedStages) {
        $redis = @($observations | Where-Object { $_.backend -ceq 'redis' -and $_.stage -ceq $stage })
        $postgres = @($observations | Where-Object { $_.backend -ceq 'postgresql' -and $_.stage -ceq $stage })
        if ($redis.Count -ne 1 -or $postgres.Count -ne 1) { return $false }
        if ($redis[0].result -cne 'VERIFIED' -or $redis[0].shutdownCommand -cne 'SHUTDOWN SAVE' -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $redis[0] 'shutdownExitCode') 0) -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $redis[0] 'processExitCode') 0) -or
            (Get-T21ObservedValue $redis[0] 'processExitCodeObserved') -ne $true -or
            $redis[0].processExitVerified -ne $true) { return $false }
        if ($postgres[0].result -cne 'VERIFIED' -or $postgres[0].statusBefore -cne 'RUNNING' -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $postgres[0] 'statusBeforeExitCode') 0) -or $postgres[0].shutdownCommand -cne 'pg_ctl stop' -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $postgres[0] 'shutdownExitCode') 0) -or $postgres[0].statusAfter -cne 'STOPPED' -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $postgres[0] 'statusAfterExitCode') 3)) { return $false }
    }

    return $true
}

function Get-T21ArtifactPinObservation(
    [object[]]$artifacts,
    [string]$expectedName,
    [string]$expectedSha256
) {
    $matches = @($artifacts | Where-Object { $_.name -ceq $expectedName })
    $observedSha256 = if ($matches.Count -eq 1) { ([string]$matches[0].sha256).ToLowerInvariant() } else { $null }
    $normalizedExpectedSha256 = $expectedSha256.ToLowerInvariant()
    return [ordered]@{
        expectedName = $expectedName
        expectedSha256 = $normalizedExpectedSha256
        matchingArtifactCount = $matches.Count
        observedSha256 = $observedSha256
        matchesExpectedPin = ($matches.Count -eq 1 -and $observedSha256 -ceq $normalizedExpectedSha256)
    }
}

function Assert-T21NodeConfigObservation(
    [object]$observation,
    [string]$expectedServerName,
    [int]$expectedSqlPort,
    [string]$expectedDatabase,
    [int]$expectedRedisPort
) {
    $valid = $null -ne $observation -and
        $observation.serverName -ceq $expectedServerName -and
        [int]$observation.configVersion -eq 12 -and
        $observation.mode -ceq 'sync' -and
        $observation.dbEnabled -eq $true -and
        $observation.pubsubEnabled -eq $true -and
        $observation.redisEnabled -eq $true -and
        $observation.redisHost -ceq '127.0.0.1' -and
        [int]$observation.redisPort -eq $expectedRedisPort -and
        [int]$observation.redisDatabase -eq 15 -and
        $observation.databaseEnabled -eq $true -and
        $observation.databaseType -ceq 'postgresql' -and
        $observation.databaseHost -ceq '127.0.0.1' -and
        [int]$observation.databasePort -eq $expectedSqlPort -and
        $observation.databaseName -ceq $expectedDatabase -and
        $observation.syntheticOperatorFixture -ceq 'preserve-through-upgrade'
    if (-not $valid) {
        throw "Sync/config/fixture retention assertion failed for $expectedServerName."
    }
    return $true
}

function Assert-T21SchemaVersionRows(
    [string[]]$baselineRows,
    [string[]]$observedRows,
    [string]$stage
) {
    $baseline = @($baselineRows | ForEach-Object { ([string]$_).Trim() })
    $observed = @($observedRows | ForEach-Object { ([string]$_).Trim() })
    if ($baseline.Count -eq 0 -or $observed.Count -eq 0) {
        throw "Schema version rows are missing at $stage."
    }
    foreach ($rows in @(@($baseline), @($observed))) {
        $ids = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($row in $rows) {
            $parts = $row.Split('|')
            if ($parts.Count -ne 2 -or [string]::IsNullOrWhiteSpace($parts[0]) -or $parts[1] -notmatch '^\d+$') {
                throw "Malformed schema version row at $stage."
            }
            if (-not $ids.Add($parts[0])) { throw "Duplicate schema version ID at $stage." }
        }
    }
    if (($baseline -join "`n") -cne ($observed -join "`n")) {
        throw "SQL schema version rows changed at $stage."
    }
    return $true
}
