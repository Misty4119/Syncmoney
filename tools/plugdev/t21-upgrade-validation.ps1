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

function Get-T21PlatformEndpoint(
    [ValidateSet('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')]
    [string]$PlatformEndpoint = 'Minimum'
) {
    switch -CaseSensitive ($PlatformEndpoint) {
        'Minimum' {
            return [ordered]@{
                taskSuffix = 'minimum-Paper'
                software = 'paper'
                minecraftVersion = '1.21.6'
                build = 48
                sha256 = '35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214'
            }
        }
        'Maximum' {
            return [ordered]@{
                taskSuffix = 'maximum-Paper'
                software = 'paper'
                minecraftVersion = '26.3'
                build = 166
                sha256 = 'dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd'
            }
        }
        'CurrentStable' {
            return [ordered]@{
                taskSuffix = 'current-stable-Paper'
                software = 'paper'
                minecraftVersion = '26.3'
                build = 194
                sha256 = '5fac0b6937b7aecea1b5b40e6a992f7b0802e4271413be35a6913d30df960784'
            }
        }
        'MinimumFolia' {
            return [ordered]@{
                taskSuffix = 'minimum-Folia'
                software = 'folia'
                minecraftVersion = '1.21.6'
                build = 6
                sha256 = '917a8abaa542c1d0ef0e969693e227a7bfe1c42e671aaa806309e00a64efc234'
            }
        }
        'MaximumFolia' {
            return [ordered]@{
                taskSuffix = 'maximum-Folia'
                software = 'folia'
                minecraftVersion = '26.2'
                build = 7
                sha256 = '128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3'
            }
        }
        'MinimumCanvas' {
            return [ordered]@{
                taskSuffix = 'minimum-Canvas'
                software = 'canvas'
                minecraftVersion = '1.21.8'
                build = 603
                sha256 = '6577181e4bc8ac40a7a48ff00e262f2ac7a798f48ada446a07eb06618dc36f1b'
            }
        }
        'MaximumCanvas' {
            return [ordered]@{
                taskSuffix = 'maximum-Canvas'
                software = 'canvas'
                minecraftVersion = '26.3'
                build = 972
                sha256 = 'aac025fe36a61f84fecb04de74e94bbf933c7081c4c88f13a9d4b96de42243f5'
            }
        }
    }
    throw "Unsupported T21 platform endpoint: $PlatformEndpoint"
}

function Get-T21PaperEndpoint([ValidateSet('Minimum', 'Maximum', 'CurrentStable')][string]$PaperEndpoint = 'Minimum') {
    return Get-T21PlatformEndpoint -PlatformEndpoint $PaperEndpoint
}

function Test-T21M04PlatformEndpointReport(
    [object]$report,
    [ValidateSet('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')]
    [string]$PlatformEndpoint = 'Minimum'
) {
    if ($null -eq $report) { return $false }
    $expected = Get-T21PlatformEndpoint -PlatformEndpoint $PlatformEndpoint
    return ([string](Get-T21ObservedValue $report 'platform') -ceq $expected.software -and
        [string](Get-T21ObservedValue $report 'minecraftVersion') -ceq $expected.minecraftVersion -and
        [string](Get-T21ObservedValue $report 'buildNumber') -ceq [string]$expected.build -and
        [string](Get-T21ObservedValue $report 'expectedServerSha256') -ceq $expected.sha256)
}

function Test-T21M04PlatformStartupIdentity(
    [string]$serverLogText,
    [ValidateSet('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')]
    [string]$PlatformEndpoint = 'Minimum'
) {
    if ([string]::IsNullOrWhiteSpace($serverLogText)) { return $false }
    try {
        $observed = Get-T21PlatformIdentity $serverLogText
    } catch {
        return $false
    }
    $expected = Get-T21PlatformEndpoint -PlatformEndpoint $PlatformEndpoint
    return ($observed.software -ceq $expected.software -and
        $observed.runtimeVersion -ceq $expected.minecraftVersion -and
        [int]$observed.build -eq [int]$expected.build -and
        $observed.minecraftVersion -ceq $expected.minecraftVersion)
}

function Test-T21M04PaperEndpointReport(
    [object]$report,
    [ValidateSet('Minimum', 'Maximum')][string]$PaperEndpoint = 'Minimum'
) {
    return Test-T21M04PlatformEndpointReport $report -PlatformEndpoint $PaperEndpoint
}

function Resolve-T21RuntimeReportsRoot([string]$RepositoryRoot, [string]$RequestedRoot) {
    if ([string]::IsNullOrWhiteSpace($RequestedRoot)) {
        return [IO.Path]::GetFullPath((Join-Path $RepositoryRoot 'build\reports\foundation\T21-sql-runtime'))
    }
    if (-not [IO.Path]::IsPathFullyQualified($RequestedRoot)) {
        throw 'The optional T21 runtime reports root must be an absolute path.'
    }

    $resolvedRoot = [IO.Path]::GetFullPath($RequestedRoot)
    $filesystemRoot = [IO.Path]::GetPathRoot($resolvedRoot)
    $trimmedRoot = $resolvedRoot.TrimEnd([char[]]@('\', '/'))
    $trimmedFilesystemRoot = $filesystemRoot.TrimEnd([char[]]@('\', '/'))
    if ([string]::Equals($trimmedRoot, $trimmedFilesystemRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The optional T21 runtime reports root cannot be a filesystem root.'
    }
    return $resolvedRoot
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
        if (-not (Test-T21ObservedBoolean $matchesExpectedPin $true)) { return $false }
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

function Test-T21M04RuntimeArtifactCopy(
    [object]$sourceArtifact,
    [object]$runtimeArtifact,
    [string]$sourceName,
    [string]$expectedSourcePath,
    [string]$runtimeName,
    [string]$expectedRuntimePath
) {
    if ($null -eq $sourceArtifact -or $null -eq $runtimeArtifact -or
        [string](Get-T21ObservedValue $sourceArtifact 'name') -cne $sourceName -or
        [string](Get-T21ObservedValue $sourceArtifact 'path') -cne $expectedSourcePath -or
        [string](Get-T21ObservedValue $runtimeArtifact 'name') -cne $runtimeName -or
        [string](Get-T21ObservedValue $runtimeArtifact 'path') -cne $expectedRuntimePath) {
        return $false
    }

    $sourceBytes = 0L
    $runtimeBytes = 0L
    if (-not [long]::TryParse([string](Get-T21ObservedValue $sourceArtifact 'bytes'), [ref]$sourceBytes) -or
        -not [long]::TryParse([string](Get-T21ObservedValue $runtimeArtifact 'bytes'), [ref]$runtimeBytes) -or
        $sourceBytes -le 0 -or $sourceBytes -ne $runtimeBytes) {
        return $false
    }

    $sourceSha256 = [string](Get-T21ObservedValue $sourceArtifact 'sha256')
    $runtimeSha256 = [string](Get-T21ObservedValue $runtimeArtifact 'sha256')
    return $sourceSha256 -match '^[a-f0-9]{64}$' -and $runtimeSha256 -ceq $sourceSha256
}

function Test-T21M04StageRecordChronology(
    [object[]]$stageRecords,
    [string[]]$lifecycleStages = @('baseline-1.3.2', 'upgrade-1.4', 'rollback-1.3.2')
) {
    $expected = [System.Collections.Generic.List[object]]::new()
    foreach ($stage in $lifecycleStages) {
        $expected.Add([ordered]@{ stage = $stage; kind = 'start' })
        $expected.Add([ordered]@{ stage = $stage; kind = 'stop' })
    }
    if ($stageRecords.Count -ne $expected.Count) { return $false }

    for ($index = 0; $index -lt $expected.Count; $index++) {
        $record = $stageRecords[$index]
        if ($null -eq $record -or
            [string](Get-T21ObservedValue $record 'stage') -cne [string]$expected[$index].stage) {
            return $false
        }
        $hasStartExit = $null -ne (Get-T21ObservedValue $record 'startExitCode')
        $hasStopExit = $null -ne (Get-T21ObservedValue $record 'stopExitCode')
        if ($expected[$index].kind -ceq 'start') {
            if (-not $hasStartExit -or $hasStopExit) { return $false }
        } elseif ($hasStartExit -or -not $hasStopExit) {
            return $false
        }
    }

    return $true
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
        -not (Test-T21ObservedBoolean (Get-T21ObservedValue $launchCommand 'observed') $true) -or
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

function Test-T21IntegralNumber([object]$value, [long]$minimum, [long]$maximum) {
    if ($null -eq $value -or $value -is [bool]) { return $false }
    $integralTypes = @(
        [byte], [sbyte], [int16], [uint16], [int32], [uint32], [int64], [uint64]
    )
    $valueType = $value.GetType()
    if ($valueType -notin $integralTypes) { return $false }
    try {
        $decimalValue = [decimal]$value
        return $decimalValue -ge [decimal]$minimum -and $decimalValue -le [decimal]$maximum
    } catch {
        return $false
    }
}

function Test-T21ObservedBoolean([object]$value, [bool]$expected) {
    return $value -is [bool] -and $value -eq $expected
}

function Test-T21PostgresProcessId([object]$value) {
    return Test-T21IntegralNumber $value 1 ([int]::MaxValue)
}

function Test-T21PostgresExitCode([object]$value, [int]$expected) {
    return (Test-T21IntegralNumber $value ([int]::MinValue) ([int]::MaxValue)) -and ([int]$value -eq $expected)
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
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redis[0] 'processExitCodeObserved') $true) -or
            -not (Test-T21ObservedBoolean $redis[0].processExitVerified $true)) { return $false }
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
        (Test-T21ObservedBoolean $observation.dbEnabled $true) -and
        (Test-T21ObservedBoolean $observation.pubsubEnabled $true) -and
        (Test-T21ObservedBoolean $observation.redisEnabled $true) -and
        $observation.redisHost -ceq '127.0.0.1' -and
        [int]$observation.redisPort -eq $expectedRedisPort -and
        [int]$observation.redisDatabase -eq 15 -and
        (Test-T21ObservedBoolean $observation.databaseEnabled $true) -and
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

function ConvertFrom-T21WindowsCommandLine([string]$CommandLine) {
    if ([string]::IsNullOrWhiteSpace($CommandLine) -or [Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw 'A nonempty Windows process command line is required.'
    }

    if ($null -eq ('T21WindowsCommandLineParser' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class T21WindowsCommandLineParser
{
    [DllImport("shell32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr CommandLineToArgvW(string commandLine, out int argumentCount);

    [DllImport("kernel32.dll", SetLastError = false)]
    private static extern IntPtr LocalFree(IntPtr memory);

    public static string[] Parse(string commandLine)
    {
        int argumentCount;
        IntPtr arguments = CommandLineToArgvW(commandLine, out argumentCount);
        if (arguments == IntPtr.Zero)
        {
            throw new Win32Exception(Marshal.GetLastWin32Error());
        }

        try
        {
            string[] parsed = new string[argumentCount];
            for (int index = 0; index < argumentCount; index++)
            {
                IntPtr argument = Marshal.ReadIntPtr(arguments, index * IntPtr.Size);
                parsed[index] = Marshal.PtrToStringUni(argument) ?? String.Empty;
            }
            return parsed;
        }
        finally
        {
            LocalFree(arguments);
        }
    }
}
'@ -ErrorAction Stop
    }

    return [T21WindowsCommandLineParser]::Parse($CommandLine)
}

function Test-T21PostgresProcessIdentity(
    [int]$ProcessId,
    [string]$ExpectedExecutablePath,
    [string]$ActualExecutablePath,
    [string]$CommandLine,
    [string[]]$PostmasterPidLines,
    [string]$ExpectedDataDirectory,
    [int]$ExpectedPort,
    [DateTime]$ProcessStartTimeUtc
) {
    try {
        if ($null -eq $PostmasterPidLines -or $PostmasterPidLines.Count -lt 4) { return $false }

        # PostgreSQL postmaster.pid stores PID, data directory, start time, then port.
        $pidFileProcessId = 0
        $pidFileStartTime = [long]0
        $pidFilePort = 0
        if (-not [int]::TryParse(([string]$PostmasterPidLines[0]).Trim(), [ref]$pidFileProcessId) -or
            -not [long]::TryParse(([string]$PostmasterPidLines[2]).Trim(), [Globalization.NumberStyles]::None, [Globalization.CultureInfo]::InvariantCulture, [ref]$pidFileStartTime) -or
            -not [int]::TryParse(([string]$PostmasterPidLines[3]).Trim(), [ref]$pidFilePort) -or
            $pidFileProcessId -ne $ProcessId -or $pidFileStartTime -le 0 -or $pidFilePort -ne $ExpectedPort -or
            $ProcessStartTimeUtc -eq [DateTime]::MinValue) { return $false }
        $processStartUnixTime = [DateTimeOffset]::new($ProcessStartTimeUtc.ToUniversalTime()).ToUnixTimeSeconds()
        if ([Math]::Abs([double]$pidFileStartTime - [double]$processStartUnixTime) -gt 10) { return $false }

        $pidFileDataDirectory = [IO.Path]::GetFullPath(([string]$PostmasterPidLines[1]).Trim())
        $expectedDataDirectoryPath = [IO.Path]::GetFullPath($ExpectedDataDirectory)
        $expectedExecutable = [IO.Path]::GetFullPath($ExpectedExecutablePath)
        $actualExecutable = [IO.Path]::GetFullPath($ActualExecutablePath)
        $commandLineArguments = @(ConvertFrom-T21WindowsCommandLine $CommandLine)
        $postgresOptionsWithSeparateValues = @('-B', '-c', '-C', '-d', '-D', '-h', '-k', '-N', '-p', '-S', '-f', '-t', '-W', '-r')
        $dataDirectoryArgumentCount = 0
        $portArgumentCount = 0
        $hasExpectedDataDirectory = $false
        $hasExpectedPort = $false
        for ($argumentIndex = 0; $argumentIndex -lt $commandLineArguments.Count; $argumentIndex++) {
            $argument = [string]$commandLineArguments[$argumentIndex]
            if ([string]::Equals($argument, '-D', [StringComparison]::Ordinal)) {
                $dataDirectoryArgumentCount++
                if ($argumentIndex + 1 -ge $commandLineArguments.Count) { return $false }
                $value = [string]$commandLineArguments[$argumentIndex + 1]
                if ([IO.Path]::IsPathFullyQualified($value)) {
                    $observedDataDirectoryPath = [IO.Path]::GetFullPath($value.Replace('/', [IO.Path]::DirectorySeparatorChar))
                    if ($observedDataDirectoryPath -ieq $expectedDataDirectoryPath) { $hasExpectedDataDirectory = $true }
                }
                $argumentIndex++
            } elseif ([string]::Equals($argument, '-p', [StringComparison]::Ordinal)) {
                $portArgumentCount++
                if ($argumentIndex + 1 -ge $commandLineArguments.Count) { return $false }
                $value = [string]$commandLineArguments[$argumentIndex + 1]
                $observedPort = 0
                if ([int]::TryParse($value, [Globalization.NumberStyles]::None, [Globalization.CultureInfo]::InvariantCulture, [ref]$observedPort) -and
                    $observedPort -eq $ExpectedPort) { $hasExpectedPort = $true }
                $argumentIndex++
            } elseif ([Array]::IndexOf($postgresOptionsWithSeparateValues, $argument) -ge 0) {
                if ($argumentIndex + 1 -ge $commandLineArguments.Count) { return $false }
                $argumentIndex++
            }
        }
        if ($pidFileDataDirectory -ine $expectedDataDirectoryPath -or
            $actualExecutable -ine $expectedExecutable -or
            $commandLineArguments.Count -lt 1 -or
            $dataDirectoryArgumentCount -ne 1 -or -not $hasExpectedDataDirectory -or
            $portArgumentCount -ne 1 -or -not $hasExpectedPort) { return $false }
        return $true
    } catch {
        return $false
    }
}

function Test-T21PostgresListenerIdentity(
    [object[]]$ListenerOwners,
    [string[]]$ListenerAddresses,
    [int]$ProcessId,
    [string]$ExpectedExecutablePath,
    [string]$ActualExecutablePath,
    [string]$CommandLine,
    [string[]]$PostmasterPidLines,
    [string]$ExpectedDataDirectory,
    [int]$ExpectedPort,
    [DateTime]$ProcessStartTimeUtc
) {
    try {
        if ($null -eq $ListenerOwners -or $null -eq $ListenerAddresses -or
            $ListenerOwners.Count -ne 1 -or $ListenerAddresses.Count -eq 0 -or
            @($ListenerAddresses | Where-Object { $_ -notin @('127.0.0.1', '::1') }).Count -gt 0 -or
            [int]$ListenerOwners[0] -ne $ProcessId) { return $false }

        return Test-T21PostgresProcessIdentity -ProcessId $ProcessId `
            -ExpectedExecutablePath $ExpectedExecutablePath -ActualExecutablePath $ActualExecutablePath `
            -CommandLine $CommandLine -PostmasterPidLines $PostmasterPidLines `
            -ExpectedDataDirectory $ExpectedDataDirectory -ExpectedPort $ExpectedPort `
            -ProcessStartTimeUtc $ProcessStartTimeUtc
    } catch {
        return $false
    }
}

function Resolve-T21SqlRuntimeProduct([string]$requestedProduct) {
    if ($requestedProduct -ieq 'MySQL') { return 'MySQL' }
    if ($requestedProduct -ieq 'MariaDB') { return 'MariaDB' }
    if ($requestedProduct -ieq 'PostgreSQL') { return 'PostgreSQL' }
    throw "Unsupported T21 SQL runtime product: $requestedProduct"
}

function Test-T21SqlRuntimeReport(
    [object]$report,
    [string]$expectedServerSha256,
    [string]$expectedCandidateSha256,
    [ValidateSet('MySQL', 'MariaDB', 'PostgreSQL')][string]$DatabaseProduct = 'MySQL',
    [Alias('PaperEndpoint')]
    [ValidateSet('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')]
    [string]$PlatformEndpoint = 'Minimum'
) {
    try {
        if ($null -eq $report) { return $false }
        $expectedPlatform = Get-T21PlatformEndpoint -PlatformEndpoint $PlatformEndpoint
        $schemaVersion = Get-T21ObservedValue $report 'schemaVersion'
        $isPostgreSql = $DatabaseProduct -ceq 'PostgreSQL'
        $sqlReportKey = if ($isPostgreSql) { 'postgresql' } else { 'mysql' }
        $expectedTask = "T21-M05-SYNC-$DatabaseProduct-$($expectedPlatform.taskSuffix)"
        $legacyMySqlReport = $schemaVersion -eq 1 -and $DatabaseProduct -ceq 'MySQL'
        if ($schemaVersion -notin @(1, 2, 3, 4) -or
            (-not $legacyMySqlReport -and $schemaVersion -notin @(2, 3, 4)) -or
            ($isPostgreSql -and $schemaVersion -notin @(3, 4)) -or
            ($expectedPlatform.software -cne 'paper' -and $schemaVersion -ne 4) -or
            ([string]$expectedServerSha256).ToLowerInvariant() -cne $expectedPlatform.sha256 -or
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
        $cleanupFailures = $null
        if ($report -is [Collections.IDictionary]) {
            if ($report.Contains('cleanupFailures')) { $cleanupFailures = $report['cleanupFailures'] }
        } else {
            $cleanupFailuresProperty = $report.PSObject.Properties['cleanupFailures']
            if ($null -ne $cleanupFailuresProperty) { $cleanupFailures = $cleanupFailuresProperty.Value }
        }
        if (-not $hasFailureField -or -not $hasCleanupFailuresField -or
            $null -ne (Get-T21ObservedValue $report 'failure') -or
            $null -eq $cleanupFailures -or
            $cleanupFailures -isnot [Collections.IList] -or
            $cleanupFailures.Count -ne 0) { return $false }

        $server = Get-T21ObservedValue $report 'server'
        $candidate = Get-T21ObservedValue $report 'candidate'
        $java = Get-T21ObservedValue $report 'java'
        $configuration = Get-T21ObservedValue $report 'configuration'
        if ((Get-T21ObservedValue $server 'software') -cne $expectedPlatform.software -or
            (Get-T21ObservedValue $server 'minecraftVersion') -cne $expectedPlatform.minecraftVersion -or
            (Get-T21ObservedValue $server 'build') -ne $expectedPlatform.build -or
            ([string](Get-T21ObservedValue $server 'sha256')).ToLowerInvariant() -cne $expectedServerSha256.ToLowerInvariant() -or
            (Get-T21ObservedValue $candidate 'version') -cne '1.4.0-alpha.1' -or
            ([string](Get-T21ObservedValue $candidate 'sha256')).ToLowerInvariant() -cne $expectedCandidateSha256.ToLowerInvariant() -or
            (Get-T21ObservedValue $java 'feature') -ne 25) { return $false }

        if ((Get-T21ObservedValue $configuration 'schema') -ne 12 -or
            (Get-T21ObservedValue $configuration 'configSchema') -ne 12 -or
            (Get-T21ObservedValue $configuration 'mode') -cne 'sync' -or
            (Get-T21ObservedValue $configuration 'observedMode') -cne 'sync' -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'databaseEnabled') $true) -or
            (Get-T21ObservedValue $configuration 'databaseType') -cne $(if ($isPostgreSql) { 'postgresql' } else { 'mysql' }) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'databaseIsolated') $true) -or
            (Get-T21ObservedValue $configuration 'databaseHost') -cne '127.0.0.1' -or
            [int](Get-T21ObservedValue $configuration 'databasePort') -lt 1 -or
            [int](Get-T21ObservedValue $configuration 'databasePort') -gt 65535 -or
            [string](Get-T21ObservedValue $configuration 'databaseName') -notmatch '^syncmoney_t21(?:_[a-f0-9]{8})?$' -or
            (Get-T21ObservedValue $configuration 'databaseNameObserved') -cne (Get-T21ObservedValue $configuration 'databaseName') -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'redisEnabled') $true) -or
            (Get-T21ObservedValue $configuration 'redisDatabase') -ne 15 -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'redisIsolated') $true) -or
            (Get-T21ObservedValue $configuration 'redisHost') -cne '127.0.0.1' -or
            [int](Get-T21ObservedValue $configuration 'redisPort') -lt 1 -or
            [int](Get-T21ObservedValue $configuration 'redisPort') -gt 65535 -or
            [int](Get-T21ObservedValue $configuration 'databasePort') -eq [int](Get-T21ObservedValue $configuration 'redisPort')) { return $false }

        $expectedBackendVersion = switch ($DatabaseProduct) {
            'MariaDB' { '12.3.3-MariaDB' }
            'PostgreSQL' { '18.1' }
            default { '8.0.42' }
        }
        $backendProcesses = Get-T21ObservedValue $report 'backendProcesses'
        $sqlBackend = Get-T21ObservedValue $report $sqlReportKey
        $redis = Get-T21ObservedValue $report 'redis'
        $sqlProcess = Get-T21ObservedValue $backendProcesses $sqlReportKey
        $redisProcess = Get-T21ObservedValue $backendProcesses 'redis'
        $sqlListenerAddresses = @(Get-T21ObservedValue $sqlProcess 'listenerAddresses')
        $reportedProduct = Get-T21ObservedValue $sqlBackend 'product'
        $reportedServerVersion = [string](Get-T21ObservedValue $sqlBackend 'serverVersion')
        $reportedExecutableVersion = [string](Get-T21ObservedValue $sqlBackend 'executableVersion')
        $serverVersionValid = if ($isPostgreSql) {
            $reportedServerVersion -ceq '18.1' -and $reportedExecutableVersion -match '(?i)^postgres \(PostgreSQL\) 18\.1$'
        } elseif ($legacyMySqlReport) {
            [regex]::IsMatch($reportedServerVersion, '(?i)(?<![0-9A-Za-z.])8\.0\.42(?![0-9A-Za-z.])')
        } else {
            $reportedServerVersion -ceq $expectedBackendVersion -and
                $reportedExecutableVersion -match ('(?i)\bVer\s+' + [regex]::Escape($expectedBackendVersion) + '(?:\b|$)')
        }
        $launchProcessInvalid = -not $isPostgreSql -and [int](Get-T21ObservedValue $sqlProcess 'launchProcessId') -le 0
        if ((Get-T21ObservedValue $sqlBackend 'version') -cne $expectedBackendVersion -or
            -not $serverVersionValid -or
            ($schemaVersion -ge 2 -and $reportedProduct -cne $DatabaseProduct) -or
            (Get-T21ObservedValue $redis 'version') -cne '8.10.1' -or
            (Get-T21ObservedValue $sqlProcess 'version') -cne $expectedBackendVersion -or
            ($schemaVersion -ge 2 -and (Get-T21ObservedValue $sqlProcess 'product') -cne $DatabaseProduct) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlProcess 'runOwned') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlProcess 'listenerOwnerVerified') $true) -or
            [int](Get-T21ObservedValue $sqlProcess 'processId') -le 0 -or
            $launchProcessInvalid -or
            $sqlListenerAddresses.Count -eq 0 -or
            @($sqlListenerAddresses | Where-Object { $_ -notin @('127.0.0.1', '::1') }).Count -gt 0 -or
            (Get-T21ObservedValue $redisProcess 'version') -cne '8.10.1' -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisProcess 'runOwned') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisProcess 'listenerOwnerVerified') $true) -or
            [int](Get-T21ObservedValue $redisProcess 'processId') -le 0) { return $false }

        if ($isPostgreSql) {
            $runDirectoryForSql = [string](Get-T21ObservedValue $report 'runDirectory')
            $reportedDataDirectory = [string](Get-T21ObservedValue $sqlBackend 'dataDirectory')
            if ([string]::IsNullOrWhiteSpace($runDirectoryForSql) -or [string]::IsNullOrWhiteSpace($reportedDataDirectory) -or
                [IO.Path]::GetFullPath($reportedDataDirectory) -cne [IO.Path]::GetFullPath((Join-Path $runDirectoryForSql 'postgresql\data')) -or
                (Test-T21PathContainsReparsePoint $reportedDataDirectory)) { return $false }

            $reportedPostgresPid = [int](Get-T21ObservedValue $sqlBackend 'processId')
            $backendPostgresPid = [int](Get-T21ObservedValue $sqlProcess 'processId')
            $reportedPostgresAddresses = @((Get-T21ObservedValue $sqlBackend 'listenerAddresses') | ForEach-Object { [string]$_ } | Sort-Object -CaseSensitive)
            $backendPostgresAddresses = @((Get-T21ObservedValue $sqlProcess 'listenerAddresses') | ForEach-Object { [string]$_ } | Sort-Object -CaseSensitive)
            if ((Get-T21ObservedValue $sqlBackend 'startCommand') -cne 'pg_ctl start -w' -or
                -not (Test-T21PostgresExitCode (Get-T21ObservedValue $sqlBackend 'startCommandExitCode') 0) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlBackend 'startCommandProcessExited') $true) -or
                -not (Test-T21PostgresProcessId (Get-T21ObservedValue $sqlBackend 'processId')) -or
                -not (Test-T21PostgresProcessId (Get-T21ObservedValue $sqlProcess 'processId')) -or
                $reportedPostgresPid -ne $backendPostgresPid -or
                $reportedPostgresAddresses.Count -eq 0 -or $reportedPostgresAddresses.Count -ne $backendPostgresAddresses.Count) { return $false }
            foreach ($addressIndex in 0..($reportedPostgresAddresses.Count - 1)) {
                if ($reportedPostgresAddresses[$addressIndex] -cne $backendPostgresAddresses[$addressIndex]) { return $false }
            }
        }

        $backendShutdowns = @(Get-T21ObservedValue $report 'backendShutdowns')
        if ($backendShutdowns.Count -ne 2) { return $false }
        $sqlShutdownBackend = if ($isPostgreSql) { 'postgresql' } else { 'mysql' }
        $sqlShutdown = @($backendShutdowns | Where-Object { (Get-T21ObservedValue $_ 'backend') -ceq $sqlShutdownBackend })
        $redisShutdown = @($backendShutdowns | Where-Object { (Get-T21ObservedValue $_ 'backend') -ceq 'redis' })
        $sqlShutdownValid = if ($isPostgreSql) {
            $sqlShutdown.Count -eq 1 -and
                (Get-T21ObservedValue $sqlShutdown[0] 'command') -ceq 'pg_ctl stop -m fast -w' -and
                (Test-T21PostgresExitCode (Get-T21ObservedValue $sqlShutdown[0] 'commandExitCode') 0) -and
                (Test-T21PostgresProcessId (Get-T21ObservedValue $sqlShutdown[0] 'processId')) -and
                [int](Get-T21ObservedValue $sqlShutdown[0] 'processId') -eq [int](Get-T21ObservedValue $sqlProcess 'processId') -and
                (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'shutdownTargetVerified') $true) -and
                (Test-T21PostgresExitCode (Get-T21ObservedValue $sqlShutdown[0] 'processExitCode') 0) -and
                (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'processExitCodeObserved') $true) -and
                (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'processExited') $true) -and
                (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'forced') $false)
        } else {
            $sqlShutdown.Count -eq 1 -and
                (Get-T21ObservedValue $sqlShutdown[0] 'command') -ceq 'mysqladmin shutdown' -and
                (Test-T21ExpectedExitCode (Get-T21ObservedValue $sqlShutdown[0] 'commandExitCode') 0) -and
                [int](Get-T21ObservedValue $sqlShutdown[0] 'processId') -eq [int](Get-T21ObservedValue $sqlProcess 'processId') -and
                (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'shutdownTargetVerified') $true) -and
                (Test-T21ExpectedExitCode (Get-T21ObservedValue $sqlShutdown[0] 'processExitCode') 0) -and
                (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'processExitCodeObserved') $true) -and
                (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'processExited') $true) -and
                [int](Get-T21ObservedValue $sqlShutdown[0] 'launchProcessId') -eq [int](Get-T21ObservedValue $sqlProcess 'launchProcessId') -and
                (Test-T21ExpectedExitCode (Get-T21ObservedValue $sqlShutdown[0] 'launchProcessExitCode') 0) -and
                (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'launchProcessExitCodeObserved') $true) -and
                (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'launchProcessExited') $true) -and
                (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'forced') $false)
        }
        if (-not $sqlShutdownValid -or $redisShutdown.Count -ne 1 -or
            (Get-T21ObservedValue $redisShutdown[0] 'command') -cne 'SHUTDOWN SAVE' -or
            (Get-T21ObservedValue $redisShutdown[0] 'commandExitCode') -notin @(0, 1) -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $redisShutdown[0] 'processExitCode') 0) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisShutdown[0] 'processExitCodeObserved') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisShutdown[0] 'processExited') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisShutdown[0] 'forced') $false)) { return $false }

        $expectedStages = @('candidate-write', 'candidate-restart-read')
        $startupObservations = @(Get-T21ObservedValue $report 'startupObservations')
        if ($startupObservations.Count -ne $expectedStages.Count) { return $false }
        foreach ($index in 0..($expectedStages.Count - 1)) {
            $startup = $startupObservations[$index]
            $runtimeIdentity = Get-T21ObservedValue $startup 'runtimeIdentity'
            if ((Get-T21ObservedValue $startup 'stage') -cne $expectedStages[$index] -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $startup 'enabled') $true) -or
                (Get-T21ObservedValue $startup 'pluginVersion') -cne '1.4.0-alpha.1' -or
                [int](Get-T21ObservedValue $startup 'processId') -le 0 -or
                ([string](Get-T21ObservedValue $startup 'artifactSha256')).ToLowerInvariant() -cne $expectedCandidateSha256.ToLowerInvariant() -or
                (Get-T21ObservedValue $runtimeIdentity 'software') -cne $expectedPlatform.software -or
                (Get-T21ObservedValue $runtimeIdentity 'runtimeVersion') -cne $expectedPlatform.minecraftVersion -or
                (Get-T21ObservedValue $runtimeIdentity 'minecraftVersion') -cne $expectedPlatform.minecraftVersion -or
                (Get-T21ObservedValue $runtimeIdentity 'build') -ne $expectedPlatform.build) {
                return $false
            }
        }

        $writeReceipts = @(Get-T21ObservedValue $report 'writeReceipts')
        if ($writeReceipts.Count -ne 2 -or
            [string]$writeReceipts[0] -cne 'SUCCESS:DEPOSIT:1337.25:1337.25' -or
            [string]$writeReceipts[1] -cne 'SUCCESS:DEPOSIT:765.50:765.50') { return $false }
        $writeMarker = [string](Get-T21ObservedValue $report 'writeMarker')
        if ($writeMarker -cnotmatch '^\[\d{2}:\d{2}:\d{2}\] \[[^\]]+/INFO\]: \[SyncmoneyAcceptance\] T21_WRITE_PASS expected-a=1337\.25 expected-b=765\.50 receipt-a=SUCCESS:DEPOSIT:1337\.25:1337\.25 receipt-b=SUCCESS:DEPOSIT:765\.50:765\.50$') { return $false }
        $runtimeLogField = if ($schemaVersion -ge 4) { 'platformLogs' } else { 'paperLogs' }
        $runtimeLogs = @(Get-T21ObservedValue $report $runtimeLogField)
        $logMap = Get-T21ObservedValue $report 'logs'
        $expectedRuntimeLogStages = @('candidate-write', 'candidate-restart-read')
        $runtimeLogPrefix = if ($schemaVersion -ge 4) { 'platform' } else { 'paper' }
        $expectedRuntimeLogPaths = @("logs\$runtimeLogPrefix-candidate-write.txt", "logs\$runtimeLogPrefix-candidate-restart-read.txt")
        if ($runtimeLogs.Count -ne $expectedRuntimeLogStages.Count -or
            $null -eq $logMap) { return $false }
        $runDirectory = [string](Get-T21ObservedValue $report 'runDirectory')
        if ([string]::IsNullOrWhiteSpace($runDirectory)) { return $false }
        $runRoot = [IO.Path]::GetFullPath($runDirectory).TrimEnd([char[]]@('\', '/'))
        $runRootPrefix = $runRoot + [IO.Path]::DirectorySeparatorChar
        if ($schemaVersion -ge 3) {
            $logEntries = if ($logMap -is [Collections.IDictionary]) {
                @($logMap.GetEnumerator())
            } else {
                @($logMap.PSObject.Properties | Where-Object { $_.MemberType -in @('NoteProperty', 'Property') })
            }
            $logsRoot = [IO.Path]::GetFullPath((Join-Path $runRoot 'logs')).TrimEnd([char[]]@('\', '/'))
            $logsRootPrefix = $logsRoot + [IO.Path]::DirectorySeparatorChar
            if ($logEntries.Count -eq 0 -or
                (Test-T21PathContainsReparsePoint $logsRoot) -or
                -not (Test-Path -LiteralPath $logsRoot -PathType Container)) { return $false }

            $logComparison = if ([IO.Path]::DirectorySeparatorChar -eq '\') {
                [StringComparison]::OrdinalIgnoreCase
            } else {
                [StringComparison]::Ordinal
            }
            $declaredLogPaths = [Collections.Generic.HashSet[string]]::new(
                $(if ([IO.Path]::DirectorySeparatorChar -eq '\') { [StringComparer]::OrdinalIgnoreCase } else { [StringComparer]::Ordinal })
            )
            foreach ($logEntry in $logEntries) {
                $logPath = if ($logEntry -is [Collections.DictionaryEntry]) { [string]$logEntry.Key } else { [string]$logEntry.Name }
                $logEvidence = if ($logEntry -is [Collections.DictionaryEntry]) { $logEntry.Value } else { $logEntry.Value }
                if ([string]::IsNullOrWhiteSpace($logPath) -or [IO.Path]::IsPathRooted($logPath)) { return $false }
                $fullLogPath = [IO.Path]::GetFullPath((Join-Path $runRoot $logPath))
                if (-not $fullLogPath.StartsWith($logsRootPrefix, $logComparison) -or
                    (Test-T21PathContainsReparsePoint $fullLogPath) -or
                    -not (Test-Path -LiteralPath $fullLogPath -PathType Leaf)) { return $false }
                $relativeLogPath = $fullLogPath.Substring($runRootPrefix.Length)
                if (-not $declaredLogPaths.Add($relativeLogPath)) { return $false }

                $recordedBytes = Get-T21ObservedValue $logEvidence 'bytes'
                $recordedSha256 = [string](Get-T21ObservedValue $logEvidence 'sha256')
                if (-not (Test-T21IntegralNumber $recordedBytes 0 ([long]::MaxValue)) -or
                    $recordedSha256 -notmatch '^[0-9a-fA-F]{64}$') { return $false }
                $actualLog = Get-Item -LiteralPath $fullLogPath -Force -ErrorAction Stop
                $actualLogSha256 = (Get-FileHash -LiteralPath $fullLogPath -Algorithm SHA256 -ErrorAction Stop).Hash
                if ([long]$recordedBytes -ne [long]$actualLog.Length -or
                    $recordedSha256.ToLowerInvariant() -cne $actualLogSha256.ToLowerInvariant()) { return $false }
            }

            $logTreeItems = @(Get-ChildItem -LiteralPath $logsRoot -Recurse -Force -ErrorAction Stop)
            foreach ($treeItem in $logTreeItems) {
                if (($treeItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { return $false }
            }
            $actualLogs = @($logTreeItems | Where-Object { -not $_.PSIsContainer })
            if ($actualLogs.Count -ne $declaredLogPaths.Count) { return $false }
            foreach ($actualLog in $actualLogs) {
                $relativeLogPath = $actualLog.FullName.Substring($runRootPrefix.Length)
                if (-not $declaredLogPaths.Contains($relativeLogPath)) { return $false }
            }
        }
        $hasGradleCleanup = if ($report -is [Collections.IDictionary]) {
            $report.Contains('gradleDaemonCleanup')
        } else {
            $null -ne $report.PSObject.Properties['gradleDaemonCleanup']
        }
        if ($schemaVersion -ge 3 -and -not $hasGradleCleanup) { return $false }
        if ($hasGradleCleanup) {
            $gradleCleanup = Get-T21ObservedValue $report 'gradleDaemonCleanup'
            $expectedGradleHome = [IO.Path]::GetFullPath((Join-Path $runRoot '.gradle-user-home'))
            $reportedGradleHome = [string](Get-T21ObservedValue $gradleCleanup 'isolatedUserHome')
            $processIdsBeforeStop = @(Get-T21ObservedValue $gradleCleanup 'processIdsBeforeStop')
            $processIdsAfterStop = @(Get-T21ObservedValue $gradleCleanup 'processIdsAfterStop')
            $stopExitCode = Get-T21ObservedValue $gradleCleanup 'stopCommandExitCode'
            $processExitVerified = Get-T21ObservedValue $gradleCleanup 'processExitVerified'
            $stopLogPath = [string](Get-T21ObservedValue $gradleCleanup 'stopLogPath')
            if ([string]::IsNullOrWhiteSpace($reportedGradleHome) -or
                [IO.Path]::GetFullPath($reportedGradleHome) -cne $expectedGradleHome -or
                (Test-T21PathContainsReparsePoint $expectedGradleHome) -or
                -not (Test-T21ObservedBoolean $processExitVerified $true) -or $processIdsAfterStop.Count -ne 0 -or
                @($processIdsBeforeStop | Where-Object { [int]$_ -le 0 }).Count -gt 0 -or
                @($processIdsBeforeStop | Select-Object -Unique).Count -ne $processIdsBeforeStop.Count -or
                ($processIdsBeforeStop.Count -gt 0 -and $stopExitCode -ne 0) -or
                ($processIdsBeforeStop.Count -eq 0 -and $stopExitCode -notin @($null, 0))) { return $false }
            if ($processIdsBeforeStop.Count -gt 0) {
                $hasStopLog = if ($logMap -is [Collections.IDictionary]) {
                    $logMap.Contains('logs\gradle-daemon-stop.txt')
                } else {
                    $null -ne $logMap.PSObject.Properties['logs\gradle-daemon-stop.txt']
                }
                if ($stopLogPath -cne 'logs\gradle-daemon-stop.txt' -or -not $hasStopLog) { return $false }
                $stopLogEvidence = Get-T21ObservedValue $logMap $stopLogPath
                $fullStopLogPath = [IO.Path]::GetFullPath((Join-Path $runRoot $stopLogPath))
                if (-not $fullStopLogPath.StartsWith($runRootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
                    (Test-T21PathContainsReparsePoint $fullStopLogPath) -or
                    -not (Test-Path -LiteralPath $fullStopLogPath -PathType Leaf)) { return $false }
                $actualStopLog = Get-Item -LiteralPath $fullStopLogPath -ErrorAction Stop
                $actualStopLogSha256 = (Get-FileHash -LiteralPath $fullStopLogPath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
                if ([long](Get-T21ObservedValue $stopLogEvidence 'bytes') -ne [long]$actualStopLog.Length -or
                    ([string](Get-T21ObservedValue $stopLogEvidence 'sha256')).ToLowerInvariant() -cne $actualStopLogSha256) { return $false }
            } elseif (-not [string]::IsNullOrEmpty($stopLogPath)) { return $false }
        }
        $writeMarkerLines = @($writeMarker)
        $restartReadMarkerLines = @(
            [string](Get-T21ObservedValue $report 'restartReadMarker'),
            [string](Get-T21ObservedValue $report 'restartReadObservation')
        )
        foreach ($index in 0..($expectedRuntimeLogStages.Count - 1)) {
            $runtimeLog = $runtimeLogs[$index]
            $logPath = [string](Get-T21ObservedValue $runtimeLog 'path')
            $logBytes = [long](Get-T21ObservedValue $runtimeLog 'bytes')
            $logSha256 = [string](Get-T21ObservedValue $runtimeLog 'sha256')
            $hasLogEntry = if ($logMap -is [Collections.IDictionary]) {
                $logMap.Contains($logPath)
            } else {
                $null -ne $logMap.PSObject.Properties[$logPath]
            }
            if ((Get-T21ObservedValue $runtimeLog 'stage') -cne $expectedRuntimeLogStages[$index] -or
                $logPath -cne $expectedRuntimeLogPaths[$index] -or
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
            if ($schemaVersion -ge 4) {
                $loggedRuntimeIdentity = Get-T21PlatformIdentity ($actualLogLines -join "`n")
                Assert-T21PlatformIdentity $loggedRuntimeIdentity $expectedPlatform.software $expectedPlatform.minecraftVersion $expectedPlatform.build
            }
            $requiredMarkers = if ($expectedRuntimeLogStages[$index] -ceq 'candidate-write') { $writeMarkerLines } else { $restartReadMarkerLines }
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
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $stop 'processExitCodeObserved') $true) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $stop 'processExited') $true) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $stop 'disabledMarker') $true)) { return $false }
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

function Test-T21MySqlRuntimeReport(
    [object]$report,
    [string]$expectedServerSha256,
    [string]$expectedCandidateSha256,
    [ValidateSet('MySQL', 'MariaDB')][string]$DatabaseProduct = 'MySQL',
    [Alias('PaperEndpoint')]
    [ValidateSet('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')]
    [string]$PlatformEndpoint = 'Minimum'
) {
    return Test-T21SqlRuntimeReport $report $expectedServerSha256 $expectedCandidateSha256 -DatabaseProduct $DatabaseProduct -PlatformEndpoint $PlatformEndpoint
}

function Test-T21PostgresRuntimeReport(
    [object]$report,
    [string]$expectedServerSha256,
    [string]$expectedCandidateSha256,
    [Alias('PaperEndpoint')]
    [ValidateSet('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')]
    [string]$PlatformEndpoint = 'Minimum'
) {
    return Test-T21SqlRuntimeReport $report $expectedServerSha256 $expectedCandidateSha256 -DatabaseProduct PostgreSQL -PlatformEndpoint $PlatformEndpoint
}
