$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or newer is required for these T21 runner validation tests.' }
. (Join-Path $PSScriptRoot 't21-upgrade-validation.ps1')

function Assert-ThrowsContaining([string]$name, [scriptblock]$action, [string]$message) {
    try {
        $null = & $action
    } catch {
        if ($_.Exception.Message -notlike ('*' + $message + '*')) {
            throw ($name + ' failed for the wrong reason: ' + $_.Exception.Message)
        }
        Write-Output ('PASS: ' + $name)
        return
    }
    throw ($name + ' was accepted')
}

$paper = Get-T21PlatformIdentity '[ServerMain/INFO]: [bootstrap] Loading Paper 1.21.6-48-main@abc (2025-06-30T09:48:37Z) for Minecraft 1.21.6'
Assert-T21PlatformIdentity $paper 'paper' '1.21.6' 48
Write-Output 'PASS: parse minimum Paper startup identity'
$paperMaximum = Get-T21PlatformIdentity '[ServerMain/INFO]: [bootstrap] Loading Paper 26.3-166-main@abc (2026-10-09T00:54:07Z) for Minecraft 26.3'
Assert-T21PlatformIdentity $paperMaximum 'paper' '26.3' 166
Write-Output 'PASS: parse maximum user-supplied Paper startup identity'

$foliaMinimum = Get-T21PlatformIdentity '[ServerMain/INFO]: [bootstrap] Loading Folia 1.21.6-6-main@abc (2025-06-30T09:48:37Z) for Minecraft 1.21.6'
Assert-T21PlatformIdentity $foliaMinimum 'folia' '1.21.6' 6
Write-Output 'PASS: parse minimum Folia startup identity'
$folia = Get-T21PlatformIdentity '[ServerMain/INFO]: [bootstrap] Loading Folia 26.2-7-main@abc (2026-10-10T00:00:00Z) for Minecraft 26.2'
Assert-T21PlatformIdentity $folia 'folia' '26.2' 7
Write-Output 'PASS: parse maximum Folia startup identity'

$canvas = Get-T21PlatformIdentity '[ServerMain/INFO]: [bootstrap] Loading Canvas 1.21.8-603-main@abc (2025-08-10T00:00:00Z) for Minecraft 1.21.8'
Assert-T21PlatformIdentity $canvas 'canvas' '1.21.8' 603
Write-Output 'PASS: parse minimum Canvas startup identity'
$canvasMaximum = Get-T21PlatformIdentity '[ServerMain/INFO]: [bootstrap] Loading Canvas 26.3-972-main@abc (2026-10-10T00:00:00Z) for Minecraft 26.3'
Assert-T21PlatformIdentity $canvasMaximum 'canvas' '26.3' 972
Write-Output 'PASS: parse maximum Canvas startup identity'

Assert-ThrowsContaining 'reject a runtime that differs from the requested endpoint' {
    Assert-T21PlatformIdentity $paper 'paper' '26.3' 166
} 'runtime identity mismatch'
Assert-ThrowsContaining 'reject a startup log with no supported runtime identity' {
    Get-T21PlatformIdentity '[Server thread/INFO]: Done (7.9s)! For help, type "help"'
} 'Could not derive'
$conflictingLog = @(
    '[ServerMain/INFO]: [bootstrap] Loading Paper 1.21.6-48-main@abc for Minecraft 1.21.6',
    '[ServerMain/INFO]: [bootstrap] Loading Paper 26.3-166-main@def for Minecraft 26.3'
) -join "`n"
Assert-ThrowsContaining 'reject conflicting runtime identities in one startup log' {
    Get-T21PlatformIdentity $conflictingLog
} 'conflicting'

$shutdownLog = @(
    '[ERROR]: DB writer stopped with 1 stale transfer',
    '[ERROR]: Failed to flush 3 audit records',
    '[ERROR]: DB writer drain timed out; pending writes require recovery',
    '[ERROR]: DB writer is still using storage after shutdown deadline; database and Redis dependencies will remain open until that writer exits',
    '[ERROR]: Failed while draining database writer',
    '[ERROR]: Shutdown step failed: database writer',
    '[ERROR]: CRITICAL HEALTH: accepted economy writes remain owned'
) -join "`n"
$shutdownMarkers = @(Get-T21ShutdownFailureMarkers $shutdownLog)
if ($shutdownMarkers.Count -ne 7 -or
    -not $shutdownMarkers[0].Contains('1 stale transfer') -or
    -not $shutdownMarkers[1].Contains('3 audit records') -or
    -not ($shutdownMarkers -match 'DB writer drain timed out').Count -or
    -not ($shutdownMarkers -match 'DB writer is still using storage after shutdown deadline').Count -or
    -not ($shutdownMarkers -match 'Failed while draining database writer').Count -or
    -not ($shutdownMarkers -match 'Shutdown step failed').Count -or
    -not ($shutdownMarkers -match 'CRITICAL HEALTH').Count) {
    throw 'Database-writer, audit, or critical shutdown failure markers were not all detected.'
}
Write-Output 'PASS: detect database-writer, audit, and critical shutdown failure markers'

$healthyMarkers = @(Get-T21ShutdownFailureMarkers 'Syncmoney disabled.')
if ($healthyMarkers.Count -ne 0) { throw 'Healthy shutdown was classified as failed.' }
Write-Output 'PASS: accept a healthy shutdown log'

$runtimeObservations = @(
    [ordered]@{ serverName = 'node-a'; stage = 'baseline-1.3.2-node-1' },
    [ordered]@{ serverName = 'node-a'; stage = 'upgrade-1.4-node-1' },
    [ordered]@{ serverName = 'node-a'; stage = 'rollback-1.3.2-node-1' },
    [ordered]@{ serverName = 'node-b'; stage = 'baseline-1.3.2-node-2' },
    [ordered]@{ serverName = 'node-b'; stage = 'upgrade-1.4-node-2' },
    [ordered]@{ serverName = 'node-b'; stage = 'rollback-1.3.2-node-2' }
)
if (-not (Test-T21RuntimeObservationCoverage $runtimeObservations @('node-a', 'node-b'))) {
    throw 'Valid three-start-per-node runtime observations were rejected.'
}
Write-Output 'PASS: accept one startup identity observation for each node and lifecycle version'

$missingRollbackObservation = @($runtimeObservations | Where-Object { $_.stage -cne 'rollback-1.3.2-node-2' })
if (Test-T21RuntimeObservationCoverage $missingRollbackObservation @('node-a', 'node-b')) {
    throw 'A missing rollback startup identity observation was accepted.'
}
Write-Output 'PASS: reject a missing startup identity observation'

$duplicateNodeObservation = @($runtimeObservations | ForEach-Object {
    if ($_.stage -ceq 'rollback-1.3.2-node-2') {
        [ordered]@{ serverName = 'node-a'; stage = $_.stage }
    } else { $_ }
})
if (Test-T21RuntimeObservationCoverage $duplicateNodeObservation @('node-a', 'node-b')) {
    throw 'Startup observations assigned to the wrong node were accepted.'
}
Write-Output 'PASS: reject startup observations assigned to the wrong node'

if (-not (Test-T21StageObservationCoverage $runtimeObservations @('node-a', 'node-b'))) {
    throw 'Valid one-stop-per-node lifecycle stage observations were rejected.'
}
Write-Output 'PASS: accept exact baseline, candidate, and rollback stage coverage'

$mislabeledShutdownObservations = @($runtimeObservations | ForEach-Object {
    if ($_.stage -ceq 'baseline-1.3.2-node-2') {
        [ordered]@{ serverName = $_.serverName; stage = $_.stage + '-server-identity-shared-state-read' }
    } else { $_ }
})
if (Test-T21StageObservationCoverage $mislabeledShutdownObservations @('node-a', 'node-b')) {
    throw 'A shutdown observation with a contaminated lifecycle stage label was accepted.'
}
Write-Output 'PASS: reject contaminated shutdown stage labels'

$misassignedStageObservation = @($runtimeObservations | ForEach-Object {
    if ($_.stage -ceq 'rollback-1.3.2-node-2') {
        [ordered]@{ serverName = 'node-a'; stage = $_.stage }
    } else { $_ }
})
if (Test-T21StageObservationCoverage $misassignedStageObservation @('node-a', 'node-b')) {
    throw 'A lifecycle stage observation assigned to the wrong node was accepted.'
}
Write-Output 'PASS: reject lifecycle stage observations assigned to the wrong node'

$observationWithoutNode = @($runtimeObservations | ForEach-Object {
    [ordered]@{ stage = $_.stage }
})
if (Test-T21StageObservationCoverage $observationWithoutNode @('node-a', 'node-b')) {
    throw 'Lifecycle observations without node identities were accepted.'
}
Write-Output 'PASS: reject lifecycle observations without node identities'

$runtimeArtifactObservations = @($runtimeObservations | ForEach-Object {
    [ordered]@{
        serverName = $_.serverName
        stage = $_.stage
        syncmoneyArtifactPin = [ordered]@{ matchesExpectedPin = $true }
    }
})
if (-not (Test-T21RuntimeArtifactPinCoverage $runtimeArtifactObservations @('node-a', 'node-b'))) {
    throw 'Exact lifecycle observations with pinned runtime artifacts were rejected.'
}
Write-Output 'PASS: accept pinned Syncmoney artifacts across exact node lifecycle stages'

$runtimeArtifactWithoutPin = @($runtimeArtifactObservations | ForEach-Object {
    $observation = [ordered]@{ serverName = $_.serverName; stage = $_.stage }
    if ($_.stage -cne 'rollback-1.3.2-node-2') { $observation['syncmoneyArtifactPin'] = $_.syncmoneyArtifactPin }
    $observation
})
if (Test-T21RuntimeArtifactPinCoverage $runtimeArtifactWithoutPin @('node-a', 'node-b')) {
    throw 'A runtime artifact observation without its pin result was accepted.'
}
Write-Output 'PASS: reject runtime artifact observations missing pin results'

$runtimeArtifactWithMismatch = @($runtimeArtifactObservations | ForEach-Object {
    $pin = if ($_.stage -ceq 'upgrade-1.4-node-2') { [ordered]@{ matchesExpectedPin = $false } } else { $_.syncmoneyArtifactPin }
    [ordered]@{ serverName = $_.serverName; stage = $_.stage; syncmoneyArtifactPin = $pin }
})
if (Test-T21RuntimeArtifactPinCoverage $runtimeArtifactWithMismatch @('node-a', 'node-b')) {
    throw 'A mismatched runtime artifact pin was accepted by lifecycle coverage.'
}
Write-Output 'PASS: reject runtime artifact pin mismatches in lifecycle coverage'

$serverInputPath = [IO.Path]::GetFullPath((Join-Path $env:TEMP 't21-provenance\paperclip-bootstrap.jar'))
$serverRunRoot = [IO.Path]::GetFullPath((Join-Path $env:TEMP 't21-provenance\node-a'))
$serverRunDirectory = Join-Path $serverRunRoot '.plugdev\run'
$serverProfilePath = Join-Path $serverRunRoot '.plugdev\profile.yml'
$bootstrapPath = Join-Path $serverRunDirectory 'server.jar'
$runtimeJarPath = Join-Path $serverRunDirectory 'versions\26.3\paper-26.3.jar'
$serverInputSha = 'a' * 64
$serverRuntimeSha = 'b' * 64
$serverProvenanceStages = @('baseline-1.3.2-node-1', 'upgrade-1.4-node-1', 'rollback-1.3.2-node-1')
$serverProvenanceObservations = @($serverProvenanceStages | ForEach-Object {
    [ordered]@{
        serverName = 'node-a'
        stage = $_
        serverJarProvenance = [ordered]@{
            runDirectory = $serverRunDirectory
            input = [ordered]@{ path = $serverInputPath; bytes = 54212025; sha256 = $serverInputSha }
            profile = [ordered]@{
                path = $serverProfilePath; server = 'custom'; version = '26.3'; serverJar = $serverInputPath
            }
            plugDev = [ordered]@{
                markerPath = Join-Path $serverRunDirectory '.plugdev-server-jar.json'
                source = $serverInputPath; preparedSource = $serverInputPath; sourceBytes = 54212025
            }
            bootstrap = [ordered]@{
                path = $bootstrapPath; runRelativePath = '.plugdev/run/server.jar'; sha256 = $serverInputSha
                linkType = 'SYMBOLIC_LINK'; linkTarget = $serverInputPath
                manifest = [ordered]@{ mainClass = 'io.papermc.paperclip.Main' }
                launchCommand = [ordered]@{ observed = $true; processId = 1234; argumentPath = $bootstrapPath }
            }
            runtimeOutput = [ordered]@{
                path = $runtimeJarPath; runRelativePath = '.plugdev/run/versions/26.3/paper-26.3.jar'
                sha256 = $serverRuntimeSha
                manifest = [ordered]@{
                    mainClass = 'org.bukkit.craftbukkit.Main'; implementationTitle = 'Paper'
                    implementationVersion = '26.3-166-cc7b98a'; buildNumber = '166'
                }
            }
        }
    }
})
if (-not (Test-T21ServerJarProvenanceCoverage $serverProvenanceObservations @('node-a') $serverInputPath $serverInputSha 54212025 'paper' '26.3' 166)) {
    throw 'A pinned Paperclip input linked to a distinct, correctly identified Paper runtime JAR was rejected.'
}
Write-Output 'PASS: accept end-to-end server JAR provenance with distinct bootstrap and runtime hashes'

$wrongServerInputPath = @($serverProvenanceObservations | ForEach-Object {
    $copy = [ordered]@{ serverName = $_.serverName; stage = $_.stage; serverJarProvenance = $_.serverJarProvenance }
    if ($_.stage -ceq 'upgrade-1.4-node-1') {
        $provenance = [ordered]@{}
        foreach ($key in $_.serverJarProvenance.Keys) { $provenance[$key] = $_.serverJarProvenance[$key] }
        $input = [ordered]@{}
        foreach ($key in $provenance.input.Keys) { $input[$key] = $provenance.input[$key] }
        $input.path = $serverInputPath + '.wrong'
        $provenance.input = $input
        $copy.serverJarProvenance = $provenance
    }
    $copy
})
if (Test-T21ServerJarProvenanceCoverage $wrongServerInputPath @('node-a') $serverInputPath $serverInputSha 54212025 'paper' '26.3' 166) {
    throw 'A runtime stage with a different pinned input path was accepted.'
}
Write-Output 'PASS: reject a mismatched pinned server input path'

$wrongServerBootstrapHash = @($serverProvenanceObservations | ForEach-Object {
    $copy = [ordered]@{ serverName = $_.serverName; stage = $_.stage; serverJarProvenance = $_.serverJarProvenance }
    if ($_.stage -ceq 'upgrade-1.4-node-1') {
        $provenance = [ordered]@{}
        foreach ($key in $_.serverJarProvenance.Keys) { $provenance[$key] = $_.serverJarProvenance[$key] }
        $bootstrap = [ordered]@{}
        foreach ($key in $provenance.bootstrap.Keys) { $bootstrap[$key] = $provenance.bootstrap[$key] }
        $bootstrap.sha256 = 'c' * 64
        $provenance.bootstrap = $bootstrap
        $copy.serverJarProvenance = $provenance
    }
    $copy
})
if (Test-T21ServerJarProvenanceCoverage $wrongServerBootstrapHash @('node-a') $serverInputPath $serverInputSha 54212025 'paper' '26.3' 166) {
    throw 'A bootstrap JAR with a different SHA-256 from the pinned input was accepted.'
}
Write-Output 'PASS: reject a bootstrap hash that differs from the pinned input'

$wrongPaperclipOutputHash = @($serverProvenanceObservations | ForEach-Object {
    $copy = [ordered]@{ serverName = $_.serverName; stage = $_.stage; serverJarProvenance = $_.serverJarProvenance }
    if ($_.stage -ceq 'upgrade-1.4-node-1') {
        $provenance = [ordered]@{}
        foreach ($key in $_.serverJarProvenance.Keys) { $provenance[$key] = $_.serverJarProvenance[$key] }
        $runtimeOutput = [ordered]@{}
        foreach ($key in $provenance.runtimeOutput.Keys) { $runtimeOutput[$key] = $provenance.runtimeOutput[$key] }
        $runtimeOutput.sha256 = 'not-a-sha256'
        $provenance.runtimeOutput = $runtimeOutput
        $copy.serverJarProvenance = $provenance
    }
    $copy
})
if (Test-T21ServerJarProvenanceCoverage $wrongPaperclipOutputHash @('node-a') $serverInputPath $serverInputSha 54212025 'paper' '26.3' 166) {
    throw 'A runtime JAR without its independently recorded output hash was accepted.'
}
Write-Output 'PASS: validate the runtime output hash independently from the bootstrap hash'

$wrongPaperclipRuntimeIdentity = @($serverProvenanceObservations | ForEach-Object {
    $copy = [ordered]@{ serverName = $_.serverName; stage = $_.stage; serverJarProvenance = $_.serverJarProvenance }
    if ($_.stage -ceq 'rollback-1.3.2-node-1') {
        $provenance = [ordered]@{}
        foreach ($key in $_.serverJarProvenance.Keys) { $provenance[$key] = $_.serverJarProvenance[$key] }
        $runtimeOutput = [ordered]@{}
        foreach ($key in $provenance.runtimeOutput.Keys) { $runtimeOutput[$key] = $provenance.runtimeOutput[$key] }
        $manifest = [ordered]@{}
        foreach ($key in $runtimeOutput.manifest.Keys) { $manifest[$key] = $runtimeOutput.manifest[$key] }
        $manifest.buildNumber = '175'
        $runtimeOutput.manifest = $manifest
        $provenance.runtimeOutput = $runtimeOutput
        $copy.serverJarProvenance = $provenance
    }
    $copy
})
if (Test-T21ServerJarProvenanceCoverage $wrongPaperclipRuntimeIdentity @('node-a') $serverInputPath $serverInputSha 54212025 'paper' '26.3' 166) {
    throw 'A Paperclip runtime JAR from a different server build was accepted.'
}
Write-Output 'PASS: reject a runtime output manifest for the wrong platform build'

$expectedArtifactHash = 'a' * 64
$wrongArtifactHash = 'b' * 64
$pinnedArtifact = [ordered]@{ name = 'Syncmoney-1.4.0-alpha.1.jar'; sha256 = $expectedArtifactHash }
$artifactPin = Get-T21ArtifactPinObservation @($pinnedArtifact) 'Syncmoney-1.4.0-alpha.1.jar' $expectedArtifactHash
if (-not $artifactPin.matchesExpectedPin -or $artifactPin.observedSha256 -cne $expectedArtifactHash) {
    throw 'A deployed Syncmoney artifact matching its expected SHA-256 pin was rejected.'
}
Write-Output 'PASS: accept the exact deployed Syncmoney artifact pin'

$mismatchedArtifact = [ordered]@{ name = 'Syncmoney-1.4.0-alpha.1.jar'; sha256 = $wrongArtifactHash }
$mismatchedPin = Get-T21ArtifactPinObservation @($mismatchedArtifact) 'Syncmoney-1.4.0-alpha.1.jar' $expectedArtifactHash
if ($mismatchedPin.matchesExpectedPin) { throw 'A deployed Syncmoney artifact with a mismatched SHA-256 was accepted.' }
Write-Output 'PASS: reject a mismatched deployed Syncmoney artifact pin'

$missingPin = Get-T21ArtifactPinObservation @() 'Syncmoney-1.4.0-alpha.1.jar' $expectedArtifactHash
if ($missingPin.matchesExpectedPin) { throw 'A missing deployed Syncmoney artifact was accepted.' }
Write-Output 'PASS: reject a missing deployed Syncmoney artifact'

$duplicateArtifacts = @($pinnedArtifact, $pinnedArtifact)
$duplicatePin = Get-T21ArtifactPinObservation $duplicateArtifacts 'Syncmoney-1.4.0-alpha.1.jar' $expectedArtifactHash
if ($duplicatePin.matchesExpectedPin) { throw 'Duplicate deployed Syncmoney artifacts were accepted.' }
Write-Output 'PASS: reject duplicate deployed Syncmoney artifacts'

$validConfig = [ordered]@{
    serverName = 't21-nodeA'; configVersion = 12; mode = 'sync'; dbEnabled = $true; pubsubEnabled = $true
    redisEnabled = $true; redisHost = '127.0.0.1'; redisPort = 24567; redisDatabase = 15
    databaseEnabled = $true; databaseType = 'postgresql'; databaseHost = '127.0.0.1'; databasePort = 24568
    databaseName = 'syncmoney_t21'; syntheticOperatorFixture = 'preserve-through-upgrade'
}
if (-not (Assert-T21NodeConfigObservation $validConfig 't21-nodeA' 24568 'syncmoney_t21' 24567)) {
    throw 'A valid pinned T21 config observation was rejected.'
}
Write-Output 'PASS: accept the expected SYNC backend and operator-config fixture'
$missingConfigFixture = [ordered]@{}
foreach ($key in $validConfig.Keys) { $missingConfigFixture[$key] = $validConfig[$key] }
$missingConfigFixture.syntheticOperatorFixture = $null
Assert-ThrowsContaining 'reject candidate config that lost the operator fixture before rollback' {
    Assert-T21NodeConfigObservation $missingConfigFixture 't21-nodeA' 24568 'syncmoney_t21' 24567
} 'retention assertion failed'

$schemaRows = @('audit|3', 'players|2', 'recovery|1')
if (-not (Assert-T21SchemaVersionRows $schemaRows $schemaRows 'baseline seed')) {
    throw 'An unchanged SQL schema-version snapshot was rejected.'
}
Write-Output 'PASS: accept unchanged non-empty SQL schema-version rows'
Assert-ThrowsContaining 'reject SQL schema-version changes during upgrade' {
    Assert-T21SchemaVersionRows $schemaRows @('audit|3', 'players|3', 'recovery|1') 'candidate shutdown'
} 'changed'
Assert-ThrowsContaining 'reject missing SQL schema-version rows' {
    Assert-T21SchemaVersionRows $schemaRows @() 'rollback'
} 'missing'
Assert-ThrowsContaining 'reject malformed SQL schema-version rows' {
    Assert-T21SchemaVersionRows $schemaRows @('audit|unknown') 'candidate shutdown'
} 'Malformed'

$backendShutdownObservations = @(
    [ordered]@{ backend='redis'; stage='preupgrade-backup'; result='VERIFIED'; shutdownCommand='SHUTDOWN SAVE'; shutdownExitCode=0; processExitCode=0; processExitCodeObserved=$true; processExitVerified=$true },
    [ordered]@{ backend='postgresql'; stage='preupgrade-backup'; result='VERIFIED'; statusBefore='RUNNING'; statusBeforeExitCode=0; shutdownCommand='pg_ctl stop'; shutdownExitCode=0; statusAfter='STOPPED'; statusAfterExitCode=3 },
    [ordered]@{ backend='redis'; stage='final-cleanup'; result='VERIFIED'; shutdownCommand='SHUTDOWN SAVE'; shutdownExitCode=0; processExitCode=0; processExitCodeObserved=$true; processExitVerified=$true },
    [ordered]@{ backend='postgresql'; stage='final-cleanup'; result='VERIFIED'; statusBefore='RUNNING'; statusBeforeExitCode=0; shutdownCommand='pg_ctl stop'; shutdownExitCode=0; statusAfter='STOPPED'; statusAfterExitCode=3 }
)
if (-not (Test-T21BackendShutdownCoverage $backendShutdownObservations)) {
    throw 'Verified Redis and PostgreSQL shutdown observations were rejected.'
}
Write-Output 'PASS: accept exact isolated Redis and PostgreSQL shutdown coverage'

$missingBackendShutdown = @($backendShutdownObservations | Where-Object {
    -not ($_.backend -ceq 'postgresql' -and $_.stage -ceq 'final-cleanup')
})
if (Test-T21BackendShutdownCoverage $missingBackendShutdown) {
    throw 'Missing PostgreSQL cleanup evidence was accepted.'
}
Write-Output 'PASS: reject missing isolated backend shutdown evidence'

$unverifiedPostgresShutdown = @($backendShutdownObservations | ForEach-Object {
    if ($_.backend -ceq 'postgresql' -and $_.stage -ceq 'final-cleanup') {
        $copy = [ordered]@{}
        foreach ($key in $_.Keys) { $copy[$key] = $_[$key] }
        $copy['statusAfterExitCode'] = 2
        $copy
    } else { $_ }
})
if (Test-T21BackendShutdownCoverage $unverifiedPostgresShutdown) {
    throw 'An unrecognized PostgreSQL status error was accepted as cleanup evidence.'
}
Write-Output 'PASS: reject PostgreSQL cleanup status errors'

$unverifiedRedisShutdown = @($backendShutdownObservations | ForEach-Object {
    if ($_.backend -ceq 'redis' -and $_.stage -ceq 'final-cleanup') {
        $copy = [ordered]@{}
        foreach ($key in $_.Keys) { $copy[$key] = $_[$key] }
        $copy['processExitVerified'] = $false
        $copy
    } else { $_ }
})
if (Test-T21BackendShutdownCoverage $unverifiedRedisShutdown) {
    throw 'Redis cleanup without verified process exit was accepted.'
}
Write-Output 'PASS: reject Redis cleanup without process-exit evidence'

$missingRedisExitCode = @($backendShutdownObservations | ForEach-Object {
    if ($_.backend -ceq 'redis' -and $_.stage -ceq 'final-cleanup') {
        $copy = [ordered]@{}
        foreach ($key in $_.Keys) { $copy[$key] = $_[$key] }
        $null = $copy.Remove('processExitCode')
        $copy
    } else { $_ }
})
if (Test-T21BackendShutdownCoverage $missingRedisExitCode) {
    throw 'Redis cleanup with an absent process exit code was accepted as zero.'
}
Write-Output 'PASS: reject absent Redis process exit code instead of casting it to zero'

$nullRedisExitCode = @($backendShutdownObservations | ForEach-Object {
    if ($_.backend -ceq 'redis' -and $_.stage -ceq 'final-cleanup') {
        $copy = [ordered]@{}
        foreach ($key in $_.Keys) { $copy[$key] = $_[$key] }
        $copy['processExitCode'] = $null
        $copy
    } else { $_ }
})
if (Test-T21BackendShutdownCoverage $nullRedisExitCode) {
    throw 'Redis cleanup with a null process exit code was accepted as zero.'
}
Write-Output 'PASS: reject null Redis process exit code instead of casting it to zero'

$unobservedRedisExitCode = @($backendShutdownObservations | ForEach-Object {
    if ($_.backend -ceq 'redis' -and $_.stage -ceq 'final-cleanup') {
        $copy = [ordered]@{}
        foreach ($key in $_.Keys) { $copy[$key] = $_[$key] }
        $copy['processExitCodeObserved'] = $false
        $copy
    } else { $_ }
})
if (Test-T21BackendShutdownCoverage $unobservedRedisExitCode) {
    throw 'Redis cleanup without an explicit process exit-code observation was accepted.'
}
Write-Output 'PASS: reject Redis process exit code without explicit observation'

Write-Output 'T21 runner validation tests PASS'
