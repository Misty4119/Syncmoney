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

function Test-T21PathsEqual([string]$actual, [string]$expected) {
    if ([string]::IsNullOrWhiteSpace($actual) -or [string]::IsNullOrWhiteSpace($expected)) { return $false }
    try {
        $actualPath = [IO.Path]::GetFullPath($actual).TrimEnd([char[]]@('\', '/'))
        $expectedPath = [IO.Path]::GetFullPath($expected).TrimEnd([char[]]@('\', '/'))
    } catch {
        return $false
    }
    $comparison = if ([IO.Path]::DirectorySeparatorChar -eq '\') {
        [StringComparison]::OrdinalIgnoreCase
    } else {
        [StringComparison]::Ordinal
    }
    return [string]::Equals($actualPath, $expectedPath, $comparison)
}

function Test-T21PathContainsReparsePoint([string]$path) {
    if ([string]::IsNullOrWhiteSpace($path)) { return $true }
    try {
        $currentPath = [IO.Path]::GetFullPath($path)
        while (-not [string]::IsNullOrWhiteSpace($currentPath)) {
            if (Test-Path -LiteralPath $currentPath) {
                $item = Get-Item -LiteralPath $currentPath -Force -ErrorAction Stop
                if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { return $true }
            }

            $parent = [IO.Directory]::GetParent($currentPath)
            if ($null -eq $parent -or $parent.FullName -ceq $currentPath) { break }
            $currentPath = $parent.FullName
        }
        return $false
    } catch {
        return $true
    }
}

function Test-T21ServerJarProvenanceObservation(
    [object]$observation,
    [string]$expectedInputPath,
    [string]$expectedInputSha256,
    [long]$expectedInputBytes,
    [string]$expectedSoftware,
    [string]$expectedMinecraftVersion,
    [int]$expectedBuild
) {
    if ($null -eq $observation) { return $false }
    $provenance = Get-T21ObservedValue $observation 'serverJarProvenance'
    if ($null -eq $provenance) { return $false }
    $runDirectory = [string](Get-T21ObservedValue $provenance 'runDirectory')
    if ([string]::IsNullOrWhiteSpace($runDirectory)) { return $false }

    $input = Get-T21ObservedValue $provenance 'input'
    if ($null -eq $input -or
        -not (Test-T21PathsEqual ([string](Get-T21ObservedValue $input 'path')) $expectedInputPath) -or
        ([string](Get-T21ObservedValue $input 'sha256')).ToLowerInvariant() -cne $expectedInputSha256.ToLowerInvariant() -or
        [long](Get-T21ObservedValue $input 'bytes') -ne $expectedInputBytes) {
        return $false
    }

    $profile = Get-T21ObservedValue $provenance 'profile'
    $expectedProfilePath = Join-Path (Split-Path -Parent $runDirectory) 'profile.yml'
    if ($null -eq $profile -or
        -not (Test-T21PathsEqual ([string](Get-T21ObservedValue $profile 'path')) $expectedProfilePath) -or
        [string](Get-T21ObservedValue $profile 'server') -cne 'custom' -or
        [string](Get-T21ObservedValue $profile 'version') -cne $expectedMinecraftVersion -or
        -not (Test-T21PathsEqual ([string](Get-T21ObservedValue $profile 'serverJar')) $expectedInputPath)) {
        return $false
    }

    $plugDev = Get-T21ObservedValue $provenance 'plugDev'
    $expectedMarkerPath = Join-Path $runDirectory '.plugdev-server-jar.json'
    if ($null -eq $plugDev -or
        -not (Test-T21PathsEqual ([string](Get-T21ObservedValue $plugDev 'markerPath')) $expectedMarkerPath) -or
        -not (Test-T21PathsEqual ([string](Get-T21ObservedValue $plugDev 'source')) $expectedInputPath) -or
        -not (Test-T21PathsEqual ([string](Get-T21ObservedValue $plugDev 'preparedSource')) $expectedInputPath) -or
        [long](Get-T21ObservedValue $plugDev 'sourceBytes') -ne $expectedInputBytes) {
        return $false
    }

    $bootstrap = Get-T21ObservedValue $provenance 'bootstrap'
    $expectedBootstrapPath = Join-Path $runDirectory 'server.jar'
    if ($null -eq $bootstrap -or
        -not (Test-T21PathsEqual ([string](Get-T21ObservedValue $bootstrap 'path')) $expectedBootstrapPath) -or
        [string](Get-T21ObservedValue $bootstrap 'runRelativePath') -cne '.plugdev/run/server.jar' -or
        ([string](Get-T21ObservedValue $bootstrap 'sha256')).ToLowerInvariant() -cne $expectedInputSha256.ToLowerInvariant()) {
        return $false
    }

    $linkType = [string](Get-T21ObservedValue $bootstrap 'linkType')
    $linkTarget = [string](Get-T21ObservedValue $bootstrap 'linkTarget')
    if ($linkType -ceq 'SYMBOLIC_LINK') {
        if (-not (Test-T21PathsEqual $linkTarget $expectedInputPath)) { return $false }
    } elseif ($linkType -cne 'COPY_OF_PINNED_INPUT' -or -not [string]::IsNullOrWhiteSpace($linkTarget)) {
        return $false
    }

    $bootstrapManifest = Get-T21ObservedValue $bootstrap 'manifest'
    $bootstrapMainClass = [string](Get-T21ObservedValue $bootstrapManifest 'mainClass')
    if ([string]::IsNullOrWhiteSpace($bootstrapMainClass) -or
        ($expectedSoftware -ieq 'paper' -and $bootstrapMainClass -cne 'io.papermc.paperclip.Main')) {
        return $false
    }

    $launchCommand = Get-T21ObservedValue $bootstrap 'launchCommand'
    $launchPid = 0
    if ($null -eq $launchCommand -or
        (Get-T21ObservedValue $launchCommand 'observed') -ne $true -or
        -not [int]::TryParse([string](Get-T21ObservedValue $launchCommand 'processId'), [ref]$launchPid) -or
        $launchPid -le 0 -or
        -not (Test-T21PathsEqual ([string](Get-T21ObservedValue $launchCommand 'argumentPath')) $expectedBootstrapPath)) {
        return $false
    }

    $runtimeOutput = Get-T21ObservedValue $provenance 'runtimeOutput'
    $software = $expectedSoftware.ToLowerInvariant()
    $expectedRuntimePath = Join-Path $runDirectory ("versions\{0}\{1}-{0}.jar" -f $expectedMinecraftVersion, $software)
    $expectedRuntimeRelativePath = ".plugdev/run/versions/$expectedMinecraftVersion/$software-$expectedMinecraftVersion.jar"
    if ($null -eq $runtimeOutput -or
        -not (Test-T21PathsEqual ([string](Get-T21ObservedValue $runtimeOutput 'path')) $expectedRuntimePath) -or
        [string](Get-T21ObservedValue $runtimeOutput 'runRelativePath') -cne $expectedRuntimeRelativePath -or
        [string](Get-T21ObservedValue $runtimeOutput 'sha256') -notmatch '^[0-9a-fA-F]{64}$') {
        return $false
    }

    $manifest = Get-T21ObservedValue $runtimeOutput 'manifest'
    $expectedTitle = switch ($software) {
        'paper' { 'Paper' }
        'folia' { 'Folia' }
        'canvas' { 'Canvas' }
        default { return $false }
    }
    $expectedVersionPrefix = [regex]::Escape($expectedMinecraftVersion + '-' + $expectedBuild)
    if ($null -eq $manifest -or
        [string](Get-T21ObservedValue $manifest 'mainClass') -cne 'org.bukkit.craftbukkit.Main' -or
        [string](Get-T21ObservedValue $manifest 'implementationTitle') -cne $expectedTitle -or
        [string](Get-T21ObservedValue $manifest 'implementationVersion') -notmatch ('^' + $expectedVersionPrefix + '(?:-|$)') -or
        [string](Get-T21ObservedValue $manifest 'buildNumber') -cne [string]$expectedBuild) {
        return $false
    }

    return $true
}

function Test-T21ServerJarProvenanceCoverage(
    [object[]]$runtimeObservations,
    [string[]]$serverNames,
    [string]$expectedInputPath,
    [string]$expectedInputSha256,
    [long]$expectedInputBytes,
    [string]$expectedSoftware,
    [string]$expectedMinecraftVersion,
    [int]$expectedBuild
) {
    if (-not (Test-T21StageObservationCoverage $runtimeObservations $serverNames)) { return $false }
    foreach ($observation in $runtimeObservations) {
        if (-not (Test-T21ServerJarProvenanceObservation $observation $expectedInputPath $expectedInputSha256 $expectedInputBytes $expectedSoftware $expectedMinecraftVersion $expectedBuild)) {
            return $false
        }
    }
    return $true
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

function Test-T21MySqlRuntimeAccountParity([object[]]$sqlAccounts, [object[]]$redisAccounts) {
    try {
        $expected = [ordered]@{
            AcceptanceA = [ordered]@{ uuid='35c93f74-c540-3b38-8093-b5cb3c2263be'; balance='1337.25'; version=1 }
            AcceptanceB = [ordered]@{ uuid='b748781d-824c-36d9-9de4-59cee731f37f'; balance='765.50'; version=1 }
        }
        if ($sqlAccounts.Count -ne 2 -or $redisAccounts.Count -ne 2) { return $false }
        $sqlByName = @{}
        $redisByName = @{}
        foreach ($entry in @(@{ rows = $sqlAccounts; target = $sqlByName }, @{ rows = $redisAccounts; target = $redisByName })) {
            foreach ($row in $entry.rows) {
                $name = [string](Get-T21ObservedValue $row 'name')
                $uuid = ([string](Get-T21ObservedValue $row 'uuid')).ToLowerInvariant()
                $balance = [string](Get-T21ObservedValue $row 'balance')
                $version = [string](Get-T21ObservedValue $row 'version')
                if (-not $expected.Contains($name) -or $uuid -cne $expected[$name].uuid -or
                    $balance -cne $expected[$name].balance -or $version -cne [string]$expected[$name].version -or
                    $entry.target.ContainsKey($name)) { return $false }
                $entry.target[$name] = [ordered]@{ uuid = $uuid; balance = $balance; version = [long]$version }
            }
        }
        foreach ($name in $expected.Keys) {
            if (-not $sqlByName.ContainsKey($name) -or -not $redisByName.ContainsKey($name)) { return $false }
            $sql = $sqlByName[$name]
            $redis = $redisByName[$name]
            if ($sql.uuid -cne $redis.uuid -or $sql.balance -cne $redis.balance -or
                [long]$sql.version -ne [long]$redis.version) { return $false }
        }
        return $true
    } catch {
        return $false
    }
}

function Test-T21MySqlRuntimeReport(
    [object]$report,
    [string]$expectedServerSha256,
    [string]$expectedCandidateSha256,
    [ValidateSet('MySQL', 'MariaDB')][string]$DatabaseProduct = 'MySQL'
) {
    try {
        if ($null -eq $report) { return $false }
        $schemaVersion = Get-T21ObservedValue $report 'schemaVersion'
        $expectedTask = "T21-M05-SYNC-$DatabaseProduct-minimum-Paper"
        $legacyMySqlReport = $schemaVersion -eq 1 -and $DatabaseProduct -ceq 'MySQL'
        if ($schemaVersion -notin @(1, 2) -or
            (-not $legacyMySqlReport -and $schemaVersion -ne 2) -or
            (Get-T21ObservedValue $report 'task') -cne $expectedTask -or
            (Get-T21ObservedValue $report 'status') -cne 'PASS_BOUNDED_SUBCASE') { return $false }

        $hasFailureField = if ($report -is [Collections.IDictionary]) {
            $report.Contains('failure')
        } else {
            $null -ne $report.PSObject.Properties['failure']
        }
        $hasCleanupFailuresField = if ($report -is [Collections.IDictionary]) {
            $report.Contains('cleanupFailures')
        } else {
            $null -ne $report.PSObject.Properties['cleanupFailures']
        }
        if (-not $hasFailureField -or -not $hasCleanupFailuresField -or
            $null -ne (Get-T21ObservedValue $report 'failure') -or
            @(Get-T21ObservedValue $report 'cleanupFailures').Count -ne 0) { return $false }

        $server = Get-T21ObservedValue $report 'server'
        $candidate = Get-T21ObservedValue $report 'candidate'
        $java = Get-T21ObservedValue $report 'java'
        $configuration = Get-T21ObservedValue $report 'configuration'
        if ((Get-T21ObservedValue $server 'software') -cne 'paper' -or
            (Get-T21ObservedValue $server 'minecraftVersion') -cne '1.21.6' -or
            (Get-T21ObservedValue $server 'build') -ne 48 -or
            ([string](Get-T21ObservedValue $server 'sha256')).ToLowerInvariant() -cne $expectedServerSha256.ToLowerInvariant() -or
            (Get-T21ObservedValue $candidate 'version') -cne '1.4.0-alpha.1' -or
            ([string](Get-T21ObservedValue $candidate 'sha256')).ToLowerInvariant() -cne $expectedCandidateSha256.ToLowerInvariant() -or
            (Get-T21ObservedValue $java 'feature') -ne 25) { return $false }

        if ((Get-T21ObservedValue $configuration 'schema') -ne 12 -or
            (Get-T21ObservedValue $configuration 'configSchema') -ne 12 -or
            (Get-T21ObservedValue $configuration 'mode') -cne 'sync' -or
            (Get-T21ObservedValue $configuration 'observedMode') -cne 'sync' -or
            (Get-T21ObservedValue $configuration 'databaseEnabled') -ne $true -or
            (Get-T21ObservedValue $configuration 'databaseType') -cne 'mysql' -or
            (Get-T21ObservedValue $configuration 'databaseIsolated') -ne $true -or
            (Get-T21ObservedValue $configuration 'databaseHost') -cne '127.0.0.1' -or
            [int](Get-T21ObservedValue $configuration 'databasePort') -lt 1 -or
            [int](Get-T21ObservedValue $configuration 'databasePort') -gt 65535 -or
            [string](Get-T21ObservedValue $configuration 'databaseName') -notmatch '^syncmoney_t21(?:_[a-f0-9]{8})?$' -or
            (Get-T21ObservedValue $configuration 'databaseNameObserved') -cne (Get-T21ObservedValue $configuration 'databaseName') -or
            (Get-T21ObservedValue $configuration 'redisEnabled') -ne $true -or
            (Get-T21ObservedValue $configuration 'redisDatabase') -ne 15 -or
            (Get-T21ObservedValue $configuration 'redisIsolated') -ne $true -or
            (Get-T21ObservedValue $configuration 'redisHost') -cne '127.0.0.1' -or
            [int](Get-T21ObservedValue $configuration 'redisPort') -lt 1 -or
            [int](Get-T21ObservedValue $configuration 'redisPort') -gt 65535 -or
            [int](Get-T21ObservedValue $configuration 'databasePort') -eq [int](Get-T21ObservedValue $configuration 'redisPort')) { return $false }

        $expectedBackendVersion = if ($DatabaseProduct -ceq 'MariaDB') { '12.3.3-MariaDB' } else { '8.0.42' }
        $backendProcesses = Get-T21ObservedValue $report 'backendProcesses'
        $mysql = Get-T21ObservedValue $report 'mysql'
        $redis = Get-T21ObservedValue $report 'redis'
        $mysqlProcess = Get-T21ObservedValue $backendProcesses 'mysql'
        $redisProcess = Get-T21ObservedValue $backendProcesses 'redis'
        $mysqlListenerAddresses = @(Get-T21ObservedValue $mysqlProcess 'listenerAddresses')
        $reportedProduct = Get-T21ObservedValue $mysql 'product'
        $reportedServerVersion = [string](Get-T21ObservedValue $mysql 'serverVersion')
        $serverVersionValid = if ($legacyMySqlReport) {
            [regex]::IsMatch($reportedServerVersion, '(?i)(?<![0-9A-Za-z.])8\.0\.42(?![0-9A-Za-z.])')
        } else {
            $reportedExecutableVersion = [string](Get-T21ObservedValue $mysql 'executableVersion')
            $reportedServerVersion -ceq $expectedBackendVersion -and
                $reportedExecutableVersion -match ('(?i)\bVer\s+' + [regex]::Escape($expectedBackendVersion) + '(?:\b|$)')
        }
        if ((Get-T21ObservedValue $mysql 'version') -cne $expectedBackendVersion -or
            -not $serverVersionValid -or
            ($schemaVersion -eq 2 -and $reportedProduct -cne $DatabaseProduct) -or
            (Get-T21ObservedValue $redis 'version') -cne '8.10.1' -or
            (Get-T21ObservedValue $mysqlProcess 'version') -cne $expectedBackendVersion -or
            ($schemaVersion -eq 2 -and (Get-T21ObservedValue $mysqlProcess 'product') -cne $DatabaseProduct) -or
            (Get-T21ObservedValue $mysqlProcess 'runOwned') -ne $true -or
            (Get-T21ObservedValue $mysqlProcess 'listenerOwnerVerified') -ne $true -or
            [int](Get-T21ObservedValue $mysqlProcess 'processId') -le 0 -or
            [int](Get-T21ObservedValue $mysqlProcess 'launchProcessId') -le 0 -or
            $mysqlListenerAddresses.Count -eq 0 -or
            @($mysqlListenerAddresses | Where-Object { $_ -notin @('127.0.0.1', '::1') }).Count -gt 0 -or
            (Get-T21ObservedValue $redisProcess 'version') -cne '8.10.1' -or
            (Get-T21ObservedValue $redisProcess 'runOwned') -ne $true -or
            (Get-T21ObservedValue $redisProcess 'listenerOwnerVerified') -ne $true -or
            [int](Get-T21ObservedValue $redisProcess 'processId') -le 0) { return $false }

        $backendShutdowns = @(Get-T21ObservedValue $report 'backendShutdowns')
        if ($backendShutdowns.Count -ne 2) { return $false }
        $mysqlShutdown = @($backendShutdowns | Where-Object { (Get-T21ObservedValue $_ 'backend') -ceq 'mysql' })
        $redisShutdown = @($backendShutdowns | Where-Object { (Get-T21ObservedValue $_ 'backend') -ceq 'redis' })
        if ($mysqlShutdown.Count -ne 1 -or $redisShutdown.Count -ne 1 -or
            (Get-T21ObservedValue $mysqlShutdown[0] 'command') -cne 'mysqladmin shutdown' -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $mysqlShutdown[0] 'commandExitCode') 0) -or
            [int](Get-T21ObservedValue $mysqlShutdown[0] 'processId') -ne [int](Get-T21ObservedValue $mysqlProcess 'processId') -or
            (Get-T21ObservedValue $mysqlShutdown[0] 'shutdownTargetVerified') -ne $true -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $mysqlShutdown[0] 'processExitCode') 0) -or
            (Get-T21ObservedValue $mysqlShutdown[0] 'processExitCodeObserved') -ne $true -or
            (Get-T21ObservedValue $mysqlShutdown[0] 'processExited') -ne $true -or
            [int](Get-T21ObservedValue $mysqlShutdown[0] 'launchProcessId') -ne [int](Get-T21ObservedValue $mysqlProcess 'launchProcessId') -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $mysqlShutdown[0] 'launchProcessExitCode') 0) -or
            (Get-T21ObservedValue $mysqlShutdown[0] 'launchProcessExitCodeObserved') -ne $true -or
            (Get-T21ObservedValue $mysqlShutdown[0] 'launchProcessExited') -ne $true -or
            (Get-T21ObservedValue $mysqlShutdown[0] 'forced') -ne $false -or
            (Get-T21ObservedValue $redisShutdown[0] 'command') -cne 'SHUTDOWN SAVE' -or
            (Get-T21ObservedValue $redisShutdown[0] 'commandExitCode') -notin @(0, 1) -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $redisShutdown[0] 'processExitCode') 0) -or
            (Get-T21ObservedValue $redisShutdown[0] 'processExitCodeObserved') -ne $true -or
            (Get-T21ObservedValue $redisShutdown[0] 'processExited') -ne $true -or
            (Get-T21ObservedValue $redisShutdown[0] 'forced') -ne $false) { return $false }

        $expectedStages = @('candidate-write', 'candidate-restart-read')
        $startupObservations = @(Get-T21ObservedValue $report 'startupObservations')
        if ($startupObservations.Count -ne $expectedStages.Count) { return $false }
        foreach ($index in 0..($expectedStages.Count - 1)) {
            $startup = $startupObservations[$index]
            $runtimeIdentity = Get-T21ObservedValue $startup 'runtimeIdentity'
            if ((Get-T21ObservedValue $startup 'stage') -cne $expectedStages[$index] -or
                (Get-T21ObservedValue $startup 'enabled') -ne $true -or
                (Get-T21ObservedValue $startup 'pluginVersion') -cne '1.4.0-alpha.1' -or
                [int](Get-T21ObservedValue $startup 'processId') -le 0 -or
                ([string](Get-T21ObservedValue $startup 'artifactSha256')).ToLowerInvariant() -cne $expectedCandidateSha256.ToLowerInvariant() -or
                (Get-T21ObservedValue $runtimeIdentity 'software') -cne 'paper' -or
                (Get-T21ObservedValue $runtimeIdentity 'runtimeVersion') -cne '1.21.6' -or
                (Get-T21ObservedValue $runtimeIdentity 'minecraftVersion') -cne '1.21.6' -or
                (Get-T21ObservedValue $runtimeIdentity 'build') -ne 48) {
                return $false
            }
        }

        $writeReceipts = @(Get-T21ObservedValue $report 'writeReceipts')
        if ($writeReceipts.Count -ne 2 -or
            [string]$writeReceipts[0] -cne 'SUCCESS:DEPOSIT:1337.25:1337.25' -or
            [string]$writeReceipts[1] -cne 'SUCCESS:DEPOSIT:765.50:765.50') { return $false }
        $writeMarker = [string](Get-T21ObservedValue $report 'writeMarker')
        if ($writeMarker -cnotmatch '^\[\d{2}:\d{2}:\d{2}\] \[[^\]]+/INFO\]: \[SyncmoneyAcceptance\] T21_WRITE_PASS expected-a=1337\.25 expected-b=765\.50 receipt-a=SUCCESS:DEPOSIT:1337\.25:1337\.25 receipt-b=SUCCESS:DEPOSIT:765\.50:765\.50$') { return $false }
        $paperLogs = @(Get-T21ObservedValue $report 'paperLogs')
        $logMap = Get-T21ObservedValue $report 'logs'
        $expectedPaperLogStages = @('candidate-write', 'candidate-restart-read')
        $expectedPaperLogPaths = @('logs\paper-candidate-write.txt', 'logs\paper-candidate-restart-read.txt')
        if ($paperLogs.Count -ne $expectedPaperLogStages.Count -or
            $null -eq $logMap) { return $false }
        $runDirectory = [string](Get-T21ObservedValue $report 'runDirectory')
        if ([string]::IsNullOrWhiteSpace($runDirectory)) { return $false }
        $runRoot = [IO.Path]::GetFullPath($runDirectory).TrimEnd([char[]]@('\', '/'))
        $runRootPrefix = $runRoot + [IO.Path]::DirectorySeparatorChar
        $writeMarkerLines = @($writeMarker)
        $restartReadMarkerLines = @(
            [string](Get-T21ObservedValue $report 'restartReadMarker'),
            [string](Get-T21ObservedValue $report 'restartReadObservation')
        )
        foreach ($index in 0..($expectedPaperLogStages.Count - 1)) {
            $paperLog = $paperLogs[$index]
            $logPath = [string](Get-T21ObservedValue $paperLog 'path')
            $logBytes = [long](Get-T21ObservedValue $paperLog 'bytes')
            $logSha256 = [string](Get-T21ObservedValue $paperLog 'sha256')
            $hasLogEntry = if ($logMap -is [Collections.IDictionary]) {
                $logMap.Contains($logPath)
            } else {
                $null -ne $logMap.PSObject.Properties[$logPath]
            }
            if ((Get-T21ObservedValue $paperLog 'stage') -cne $expectedPaperLogStages[$index] -or
                $logPath -cne $expectedPaperLogPaths[$index] -or
                $logBytes -le 0 -or $logSha256 -notmatch '^[0-9a-f]{64}$' -or
                -not $hasLogEntry) { return $false }
            $hashedLog = Get-T21ObservedValue $logMap $logPath
            if ([long](Get-T21ObservedValue $hashedLog 'bytes') -ne $logBytes -or
                ([string](Get-T21ObservedValue $hashedLog 'sha256')).ToLowerInvariant() -cne $logSha256) { return $false }

            $fullLogPath = [IO.Path]::GetFullPath((Join-Path $runRoot $logPath))
            if (-not $fullLogPath.StartsWith($runRootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
                (Test-T21PathContainsReparsePoint $fullLogPath) -or
                -not (Test-Path -LiteralPath $fullLogPath -PathType Leaf)) { return $false }
            $actualLog = Get-Item -LiteralPath $fullLogPath -ErrorAction Stop
            $actualLogSha256 = (Get-FileHash -LiteralPath $fullLogPath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
            if ([long]$actualLog.Length -ne $logBytes -or $actualLogSha256 -cne $logSha256) { return $false }
            $actualLogLines = @(Get-Content -LiteralPath $fullLogPath -ErrorAction Stop)
            $requiredMarkers = if ($expectedPaperLogStages[$index] -ceq 'candidate-write') { $writeMarkerLines } else { $restartReadMarkerLines }
            foreach ($requiredMarker in $requiredMarkers) {
                if (-not ($actualLogLines -ccontains $requiredMarker)) { return $false }
            }
        }
        $restartReadMarker = [string](Get-T21ObservedValue $report 'restartReadMarker')
        $restartReadObservation = [string](Get-T21ObservedValue $report 'restartReadObservation')
        if ($restartReadMarker -cnotmatch '^\[\d{2}:\d{2}:\d{2}\] \[[^\]]+/INFO\]: \[SyncmoneyAcceptance\] T21_READ_PASS expected-a=1337\.25 expected-b=765\.50$' -or
            $restartReadObservation -cnotmatch '^\[\d{2}:\d{2}:\d{2}\] \[[^\]]+/INFO\]: \[SyncmoneyAcceptance\] ACCEPTANCE READ cold-vault-a=NaN cold-vault-unlocked-b=null a=1337\.25 b=765\.50 vault-unlocked-b=765\.50$') { return $false }

        $normalStops = @(Get-T21ObservedValue $report 'normalStops')
        $expectedStopStages = @('after-write', 'after-restart-read')
        if ($normalStops.Count -ne $expectedStopStages.Count) { return $false }
        foreach ($index in 0..($expectedStopStages.Count - 1)) {
            $stop = $normalStops[$index]
            if ((Get-T21ObservedValue $stop 'stage') -cne $expectedStopStages[$index] -or
                [int](Get-T21ObservedValue $stop 'processId') -ne [int](Get-T21ObservedValue $startupObservations[$index] 'processId') -or
                -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $stop 'commandExitCode') 0) -or
                -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $stop 'processExitCode') 0) -or
                (Get-T21ObservedValue $stop 'processExitCodeObserved') -ne $true -or
                (Get-T21ObservedValue $stop 'processExited') -ne $true -or
                (Get-T21ObservedValue $stop 'disabledMarker') -ne $true) { return $false }
        }

        $snapshots = @(Get-T21ObservedValue $report 'snapshots')
        $expectedSnapshotStages = @('after-write-stop', 'after-restart-read-stop')
        if ($snapshots.Count -ne $expectedSnapshotStages.Count) { return $false }
        $priorRows = $null
        foreach ($index in 0..($expectedSnapshotStages.Count - 1)) {
            $snapshot = $snapshots[$index]
            $sqlAccounts = @(Get-T21ObservedValue $snapshot 'sqlAccounts')
            $redisAccounts = @(Get-T21ObservedValue $snapshot 'redisAccounts')
            if ((Get-T21ObservedValue $snapshot 'stage') -cne $expectedSnapshotStages[$index] -or
                -not (Test-T21MySqlRuntimeAccountParity $sqlAccounts $redisAccounts)) { return $false }
            if ($index -eq 0) {
                $priorRows = @($sqlAccounts | Sort-Object name | ForEach-Object { '{0}|{1}|{2}' -f $_.name, $_.balance, $_.version })
            } else {
                $currentRows = @($sqlAccounts | Sort-Object name | ForEach-Object { '{0}|{1}|{2}' -f $_.name, $_.balance, $_.version })
                if (($priorRows -join "`n") -cne ($currentRows -join "`n")) { return $false }
            }
        }
        return $true
    } catch {
        return $false
    }
}
