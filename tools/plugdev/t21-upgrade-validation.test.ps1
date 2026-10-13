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

if ($null -eq (Get-Command Test-T21MySqlRuntimeReport -ErrorAction SilentlyContinue)) {
    throw 'Missing MySQL SYNC runtime report validator for the T21 minimum-Paper dialect cell.'
}

$paperMinimumSha = 'a' * 64
$candidateSha = 'b' * 64
$accountA = '35c93f74-c540-3b38-8093-b5cb3c2263be'
$accountB = 'b748781d-824c-36d9-9de4-59cee731f37f'
$mysqlRuntimeTestRoot = Join-Path ([IO.Path]::GetTempPath()) ('syncmoney-t21-mysql-report-' + [guid]::NewGuid().ToString('N'))
$mysqlRuntimeReparseTarget = $null
try {
$mysqlRuntimeTestLogs = Join-Path $mysqlRuntimeTestRoot 'logs'
New-Item -ItemType Directory -Path $mysqlRuntimeTestLogs -Force | Out-Null
$mysqlWriteMarker = '[13:32:16] [Paper Async Scheduler Thread #1/INFO]: [SyncmoneyAcceptance] T21_WRITE_PASS expected-a=1337.25 expected-b=765.50 receipt-a=SUCCESS:DEPOSIT:1337.25:1337.25 receipt-b=SUCCESS:DEPOSIT:765.50:765.50'
$mysqlRestartReadMarker = '[13:32:16] [Paper Async Scheduler Thread #1/INFO]: [SyncmoneyAcceptance] T21_READ_PASS expected-a=1337.25 expected-b=765.50'
$mysqlRestartReadObservation = '[13:32:16] [Paper Async Scheduler Thread #1/INFO]: [SyncmoneyAcceptance] ACCEPTANCE READ cold-vault-a=NaN cold-vault-unlocked-b=null a=1337.25 b=765.50 vault-unlocked-b=765.50'
$mysqlWriteLogPath = Join-Path $mysqlRuntimeTestLogs 'paper-candidate-write.txt'
$mysqlRestartReadLogPath = Join-Path $mysqlRuntimeTestLogs 'paper-candidate-restart-read.txt'
$null = [IO.File]::WriteAllText($mysqlWriteLogPath, $mysqlWriteMarker + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
$null = [IO.File]::WriteAllText($mysqlRestartReadLogPath, ($mysqlRestartReadMarker + [Environment]::NewLine + $mysqlRestartReadObservation + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
$mysqlWriteLogItem = Get-Item -LiteralPath $mysqlWriteLogPath
$mysqlRestartReadLogItem = Get-Item -LiteralPath $mysqlRestartReadLogPath
$mysqlWriteLogSha = (Get-FileHash -LiteralPath $mysqlWriteLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
$mysqlRestartReadLogSha = (Get-FileHash -LiteralPath $mysqlRestartReadLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
$mysqlRuntimeRows = @(
    [ordered]@{ name='AcceptanceA'; uuid=$accountA; balance='1337.25'; version=1 },
    [ordered]@{ name='AcceptanceB'; uuid=$accountB; balance='765.50'; version=1 }
)
$mysqlRuntimeReport = [ordered]@{
    schemaVersion = 1
    task = 'T21-M05-SYNC-MySQL-minimum-Paper'
    status = 'PASS_BOUNDED_SUBCASE'
    server = [ordered]@{ software='paper'; minecraftVersion='1.21.6'; build=48; sha256=$paperMinimumSha }
    candidate = [ordered]@{ version='1.4.0-alpha.1'; sha256=$candidateSha }
    java = [ordered]@{ feature=25 }
    mysql = [ordered]@{ version='8.0.42'; serverVersion='8.0.42' }
    redis = [ordered]@{ version='8.10.1' }
    backendProcesses = [ordered]@{
        mysql = [ordered]@{ version='8.0.42'; processId=1111; launchProcessId=1110; listenerAddresses=@('127.0.0.1'); runOwned=$true; listenerOwnerVerified=$true }
        redis = [ordered]@{ version='8.10.1'; processId=2222; runOwned=$true; listenerOwnerVerified=$true }
    }
    configuration = [ordered]@{
        schema=12; mode='sync'; databaseEnabled=$true; databaseType='mysql'; databaseIsolated=$true
        databaseHost='127.0.0.1'; databasePort=3306; databaseName='syncmoney_t21'
        redisEnabled=$true; redisDatabase=15; redisIsolated=$true; redisHost='127.0.0.1'; redisPort=16379
        configSchema=12; observedMode='sync'; databaseNameObserved='syncmoney_t21'
    }
    backendShutdowns = @(
        [ordered]@{ backend='mysql'; command='mysqladmin shutdown'; commandExitCode=0; processId=1111; shutdownTargetVerified=$true; processExitCode=0; processExitCodeObserved=$true; processExited=$true; launchProcessId=1110; launchProcessExitCode=0; launchProcessExitCodeObserved=$true; launchProcessExited=$true; forced=$false },
        [ordered]@{ backend='redis'; command='SHUTDOWN SAVE'; commandExitCode=1; processExitCode=0; processExitCodeObserved=$true; processExited=$true; forced=$false }
    )
    startupObservations = @(
        [ordered]@{ stage='candidate-write'; enabled=$true; pluginVersion='1.4.0-alpha.1'; artifactSha256=$candidateSha; processId=3333; runtimeIdentity=[ordered]@{software='paper'; runtimeVersion='1.21.6'; minecraftVersion='1.21.6'; build=48} },
        [ordered]@{ stage='candidate-restart-read'; enabled=$true; pluginVersion='1.4.0-alpha.1'; artifactSha256=$candidateSha; processId=4444; runtimeIdentity=[ordered]@{software='paper'; runtimeVersion='1.21.6'; minecraftVersion='1.21.6'; build=48} }
    )
    writeReceipts = @(
        'SUCCESS:DEPOSIT:1337.25:1337.25',
        'SUCCESS:DEPOSIT:765.50:765.50'
    )
    writeMarker = $mysqlWriteMarker
    paperLogs = @(
        [ordered]@{ stage='candidate-write'; path='logs\paper-candidate-write.txt'; bytes=[long]$mysqlWriteLogItem.Length; sha256=$mysqlWriteLogSha },
        [ordered]@{ stage='candidate-restart-read'; path='logs\paper-candidate-restart-read.txt'; bytes=[long]$mysqlRestartReadLogItem.Length; sha256=$mysqlRestartReadLogSha }
    )
    logs = [ordered]@{
        'logs\paper-candidate-write.txt' = [ordered]@{ bytes=[long]$mysqlWriteLogItem.Length; sha256=$mysqlWriteLogSha }
        'logs\paper-candidate-restart-read.txt' = [ordered]@{ bytes=[long]$mysqlRestartReadLogItem.Length; sha256=$mysqlRestartReadLogSha }
    }
    runDirectory = $mysqlRuntimeTestRoot
    restartReadMarker = $mysqlRestartReadMarker
    restartReadObservation = $mysqlRestartReadObservation
    normalStops = @(
        [ordered]@{ stage='after-write'; commandExitCode=0; processId=3333; processExitCode=0; processExitCodeObserved=$true; processExited=$true; disabledMarker=$true },
        [ordered]@{ stage='after-restart-read'; commandExitCode=0; processId=4444; processExitCode=0; processExitCodeObserved=$true; processExited=$true; disabledMarker=$true }
    )
    snapshots = @(
        [ordered]@{ stage='after-write-stop'; sqlAccounts=$mysqlRuntimeRows; redisAccounts=$mysqlRuntimeRows },
        [ordered]@{ stage='after-restart-read-stop'; sqlAccounts=$mysqlRuntimeRows; redisAccounts=$mysqlRuntimeRows }
    )
    cleanupFailures = @()
    failure = $null
}
if (-not (Test-T21MySqlRuntimeReport $mysqlRuntimeReport $paperMinimumSha $candidateSha)) {
    throw 'The complete isolated minimum-Paper MySQL SYNC runtime report was rejected.'
}
Write-Output 'PASS: accept complete MySQL SYNC write, normal-restart, SQL/Redis parity evidence'

$mysqlRuntimeReportJsonRoundtrip = $mysqlRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json
if (-not (Test-T21MySqlRuntimeReport $mysqlRuntimeReportJsonRoundtrip $paperMinimumSha $candidateSha)) {
    throw 'The complete MySQL SYNC report was rejected after JSON serialization and deserialization.'
}
Write-Output 'PASS: accept the serialized report with rehashed Paper server logs'

$mysqlRuntimeReportV2 = $mysqlRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$mysqlRuntimeReportV2.schemaVersion = 2
$mysqlRuntimeReportV2.mysql.product = 'MySQL'
$mysqlRuntimeReportV2.mysql.executableVersion = 'mysqld.exe  Ver 8.0.42 for Win64 on x86_64'
$mysqlRuntimeReportV2.backendProcesses.mysql.product = 'MySQL'
if (-not (Test-T21MySqlRuntimeReport $mysqlRuntimeReportV2 $paperMinimumSha $candidateSha)) {
    throw 'A complete isolated minimum-Paper MySQL schema-2 report was rejected.'
}
Write-Output 'PASS: accept the current MySQL runtime report schema'

$mysqlRuntimeReportV3 = $mysqlRuntimeReportV2 | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$mysqlRuntimeReportV3.schemaVersion = 3
$mysqlRuntimeReportV3.gradleDaemonCleanup = [ordered]@{
    isolatedUserHome = Join-Path $mysqlRuntimeTestRoot '.gradle-user-home'
    processIdsBeforeStop = @()
    stopCommandExitCode = $null
    processIdsAfterStop = @()
    processExitVerified = $true
}
if (-not (Test-T21MySqlRuntimeReport $mysqlRuntimeReportV3 $paperMinimumSha $candidateSha)) {
    throw 'A clean schema-3 runtime report with verified isolated Gradle daemon cleanup was rejected.'
}
Write-Output 'PASS: accept a schema-3 runtime report with verified isolated Gradle daemon cleanup'

$mysqlRuntimeReportLeakedGradleDaemon = $mysqlRuntimeReportV3 | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$mysqlRuntimeReportLeakedGradleDaemon.gradleDaemonCleanup = [ordered]@{
    isolatedUserHome = Join-Path $mysqlRuntimeTestRoot '.gradle-user-home'
    processIdsBeforeStop = @(5555)
    stopCommandExitCode = 0
    processIdsAfterStop = @(5555)
    processExitVerified = $false
}
if (Test-T21MySqlRuntimeReport $mysqlRuntimeReportLeakedGradleDaemon $paperMinimumSha $candidateSha) {
    throw 'A runtime report with a run-owned isolated Gradle daemon left alive after cleanup was accepted.'
}
Write-Output 'PASS: reject a runtime report with a surviving isolated Gradle daemon'

$mariadbRuntimeReport = $mysqlRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$mariadbRuntimeReport.schemaVersion = 2
$mariadbRuntimeReport.task = 'T21-M05-SYNC-MariaDB-minimum-Paper'
$mariadbRuntimeReport.mysql.product = 'MariaDB'
$mariadbRuntimeReport.mysql.version = '12.3.3-MariaDB'
$mariadbRuntimeReport.mysql.serverVersion = '12.3.3-MariaDB'
$mariadbRuntimeReport.mysql.executableVersion = 'mariadbd.exe  Ver 12.3.3-MariaDB for Win64 on AMD64 (MariaDB Server)'
$mariadbRuntimeReport.backendProcesses.mysql.product = 'MariaDB'
$mariadbRuntimeReport.backendProcesses.mysql.version = '12.3.3-MariaDB'
if (-not (Test-T21MySqlRuntimeReport $mariadbRuntimeReport $paperMinimumSha $candidateSha -DatabaseProduct MariaDB)) {
    throw 'A complete isolated minimum-Paper MariaDB SYNC runtime report was rejected.'
}
Write-Output 'PASS: accept MariaDB runtime evidence through the MySQL protocol'

$mariadbRuntimeReportWrongProduct = $mariadbRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$mariadbRuntimeReportWrongProduct.mysql.product = 'MySQL'
if (Test-T21MySqlRuntimeReport $mariadbRuntimeReportWrongProduct $paperMinimumSha $candidateSha -DatabaseProduct MariaDB) {
    throw 'A MariaDB runtime report with MySQL product identity was accepted.'
}
Write-Output 'PASS: reject a MariaDB report with a different server product'

$mariadbRuntimeReportWrongVersion = $mariadbRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$mariadbRuntimeReportWrongVersion.mysql.serverVersion = '12.3.2-MariaDB for Win64 on AMD64 (MariaDB Server)'
if (Test-T21MySqlRuntimeReport $mariadbRuntimeReportWrongVersion $paperMinimumSha $candidateSha -DatabaseProduct MariaDB) {
    throw 'A MariaDB runtime report with the wrong pinned server version was accepted.'
}
Write-Output 'PASS: reject a MariaDB report with the wrong exact server version'

$paperMaximumSha = 'b' * 64
$maximumPaperRuntimeReport = $mariadbRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$maximumPaperRuntimeReport.schemaVersion = 3
$maximumPaperRuntimeReport.task = 'T21-M05-SYNC-MariaDB-maximum-Paper'
$maximumPaperRuntimeReport.server.minecraftVersion = '26.3'
$maximumPaperRuntimeReport.server.build = 166
$maximumPaperRuntimeReport.server.sha256 = $paperMaximumSha
$maximumPaperRuntimeReport.gradleDaemonCleanup = [ordered]@{
    isolatedUserHome = Join-Path $maximumPaperRuntimeReport.runDirectory '.gradle-user-home'
    processIdsBeforeStop = @()
    stopCommandExitCode = $null
    stopLogPath = $null
    processIdsAfterStop = @()
    processExitVerified = $true
}
foreach ($startup in $maximumPaperRuntimeReport.startupObservations) {
    $startup.runtimeIdentity.runtimeVersion = '26.3'
    $startup.runtimeIdentity.minecraftVersion = '26.3'
    $startup.runtimeIdentity.build = 166
}
if (-not (Test-T21MySqlRuntimeReport $maximumPaperRuntimeReport $paperMaximumSha $candidateSha -DatabaseProduct MariaDB -PaperEndpoint Maximum)) {
    throw 'The complete MariaDB SYNC runtime report for user-pinned maximum Paper 26.3#166 was rejected.'
}
Write-Output 'PASS: accept MariaDB runtime evidence for user-pinned maximum Paper 26.3#166'
if (Test-T21MySqlRuntimeReport $maximumPaperRuntimeReport $paperMaximumSha $candidateSha -DatabaseProduct MariaDB) {
    throw 'A maximum-Paper runtime report was accepted under the default minimum-Paper expectation.'
}
Write-Output 'PASS: reject maximum-Paper evidence under the minimum-Paper expectation'

$maximumPaperWrongReportedProduct = $maximumPaperRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$maximumPaperWrongReportedProduct.mysql.product = 'MySQL'
if (Test-T21MySqlRuntimeReport $maximumPaperWrongReportedProduct $paperMaximumSha $candidateSha -DatabaseProduct MariaDB -PaperEndpoint Maximum) {
    throw 'A schema-3 MariaDB report with a MySQL server product identity was accepted.'
}
Write-Output 'PASS: reject a schema-3 MariaDB report with a mismatched server product identity'

$maximumPaperWrongProcessProduct = $maximumPaperRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$maximumPaperWrongProcessProduct.backendProcesses.mysql.product = 'MySQL'
if (Test-T21MySqlRuntimeReport $maximumPaperWrongProcessProduct $paperMaximumSha $candidateSha -DatabaseProduct MariaDB -PaperEndpoint Maximum) {
    throw 'A schema-3 MariaDB report with a mismatched backend-process product identity was accepted.'
}
Write-Output 'PASS: reject a schema-3 MariaDB report with a mismatched process product identity'

if (Test-T21PathContainsReparsePoint $mysqlWriteLogPath) {
    throw 'A normal temporary Paper report fixture unexpectedly traverses a reparse point.'
}
Write-Output 'PASS: accept the normal report fixture path without reparse points'

$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]@('\', '/'))
$tempRootPrefix = $tempRoot + [IO.Path]::DirectorySeparatorChar
$mysqlRuntimeReparseTarget = Join-Path $tempRoot ('syncmoney-t21-mysql-reparse-target-' + [guid]::NewGuid().ToString('N'))
$mysqlRuntimeReparseTarget = [IO.Path]::GetFullPath($mysqlRuntimeReparseTarget)
if (-not $mysqlRuntimeReparseTarget.StartsWith($tempRootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to create a T21 reparse fixture outside the current temporary directory.'
}
Move-Item -LiteralPath $mysqlRuntimeTestLogs -Destination $mysqlRuntimeReparseTarget
try {
    New-Item -ItemType Junction -Path $mysqlRuntimeTestLogs -Target $mysqlRuntimeReparseTarget -ErrorAction Stop | Out-Null
    if (-not (Test-T21PathContainsReparsePoint $mysqlWriteLogPath)) {
        throw 'The validator did not detect a junction in the retained Paper log path.'
    }
    if (Test-T21MySqlRuntimeReport $mysqlRuntimeReport $paperMinimumSha $candidateSha) {
        throw 'A report that reaches retained Paper logs through a junction was accepted.'
    }
    Write-Output 'PASS: reject a report whose retained Paper log path traverses a junction'
}
finally {
    if (Test-Path -LiteralPath $mysqlRuntimeTestLogs) {
        $junction = Get-Item -LiteralPath $mysqlRuntimeTestLogs -Force
        if (($junction.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            Remove-Item -LiteralPath $mysqlRuntimeTestLogs -Force
        } else {
            throw 'Refusing to replace a non-junction path while restoring the T21 fixture.'
        }
    }
    if (Test-Path -LiteralPath $mysqlRuntimeReparseTarget -PathType Container) {
        if (Test-Path -LiteralPath $mysqlRuntimeTestLogs) {
            throw 'Refusing to restore the original T21 log fixture over an existing path.'
        }
        Move-Item -LiteralPath $mysqlRuntimeReparseTarget -Destination $mysqlRuntimeTestLogs
    }
}

$mysqlWriteLogContents = [IO.File]::ReadAllText($mysqlWriteLogPath)
Remove-Item -LiteralPath $mysqlWriteLogPath -Force
if (Test-T21MySqlRuntimeReport $mysqlRuntimeReport $paperMinimumSha $candidateSha) {
    throw 'A MySQL SYNC report whose retained Paper write log is absent was accepted.'
}
Write-Output 'PASS: reject a missing retained Paper write log'
$null = [IO.File]::WriteAllText($mysqlWriteLogPath, $mysqlWriteLogContents, [Text.UTF8Encoding]::new($false))

$null = [IO.File]::WriteAllText($mysqlWriteLogPath, ($mysqlWriteLogContents + 'tampered' + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
if (Test-T21MySqlRuntimeReport $mysqlRuntimeReport $paperMinimumSha $candidateSha) {
    throw 'A MySQL SYNC report whose retained Paper write log changed after hashing was accepted.'
}
Write-Output 'PASS: reject Paper write-log hash drift after capture'
$null = [IO.File]::WriteAllText($mysqlWriteLogPath, $mysqlWriteLogContents, [Text.UTF8Encoding]::new($false))

$mysqlReportMissingWriteMarker = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportMissingWriteMarker[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportMissingWriteMarker.Remove('writeMarker')
if (Test-T21MySqlRuntimeReport $mysqlReportMissingWriteMarker $paperMinimumSha $candidateSha) {
    throw 'A MySQL SYNC report without its captured Vault write marker was accepted.'
}
Write-Output 'PASS: reject missing captured Vault write marker'

$mysqlReportWrongWriteMarker = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWrongWriteMarker[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportWrongWriteMarker.writeMarker = '[13:32:17] [Paper Async Scheduler Thread #1/INFO]: [SyncmoneyAcceptance] T21_WRITE_PASS expected-a=1337.25 expected-b=765.50 receipt-a=SUCCESS:DEPOSIT:1337.25:1337.25 receipt-b=SUCCESS:DEPOSIT:765.50:765.50'
if (Test-T21MySqlRuntimeReport $mysqlReportWrongWriteMarker $paperMinimumSha $candidateSha) {
    throw 'A MySQL SYNC report whose write marker does not match the recorded capture was accepted.'
}
Write-Output 'PASS: reject a mismatched captured Vault write marker'

$mysqlReportMissingPaperLog = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportMissingPaperLog[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportMissingPaperLog.logs = [ordered]@{
    'logs\paper-candidate-restart-read.txt' = $mysqlRuntimeReport.logs['logs\paper-candidate-restart-read.txt']
}
if (Test-T21MySqlRuntimeReport $mysqlReportMissingPaperLog $paperMinimumSha $candidateSha) {
    throw 'A MySQL SYNC report without the Paper log containing the accepted write was accepted.'
}
Write-Output 'PASS: reject missing hashed Paper server log'

$mysqlReportPaperLogPinMismatch = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportPaperLogPinMismatch[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportPaperLogPinMismatch.logs = [ordered]@{
    'logs\paper-candidate-write.txt' = [ordered]@{ bytes=1024; sha256=('e' * 64) }
    'logs\paper-candidate-restart-read.txt' = $mysqlRuntimeReport.logs['logs\paper-candidate-restart-read.txt']
}
if (Test-T21MySqlRuntimeReport $mysqlReportPaperLogPinMismatch $paperMinimumSha $candidateSha) {
    throw 'A MySQL SYNC report whose Paper log manifest disagrees with the hashed log map was accepted.'
}
Write-Output 'PASS: reject Paper server-log hash metadata that disagrees with the report log map'

$mysqlReportWrongRestartRead = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWrongRestartRead[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportWrongRestartRead.restartReadObservation = '[13:32:16] [Folia Async Scheduler Thread #1/INFO]: [SyncmoneyAcceptance] ACCEPTANCE READ cold-vault-a=NaN cold-vault-unlocked-b=null a=0.00 b=765.50 vault-unlocked-b=765.50'
if (Test-T21MySqlRuntimeReport $mysqlReportWrongRestartRead $paperMinimumSha $candidateSha) {
    throw 'A restart read whose actual SQL-backed balance differs from the accepted write was accepted.'
}
Write-Output 'PASS: reject a restart read with balances that differ from accepted writes'

$mysqlReportColdVaultMismatch = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportColdVaultMismatch[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportColdVaultMismatch.restartReadObservation = '[13:32:16] [Folia Async Scheduler Thread #1/INFO]: [SyncmoneyAcceptance] ACCEPTANCE READ cold-vault-a=0.0 cold-vault-unlocked-b=null a=1337.25 b=765.50 vault-unlocked-b=765.50'
if (Test-T21MySqlRuntimeReport $mysqlReportColdVaultMismatch $paperMinimumSha $candidateSha) {
    throw 'A restart read that violated the accepted cold-Vault unavailable contract was accepted.'
}
Write-Output 'PASS: reject restart read evidence that violates cold-Vault semantics'

$mysqlReportWrongShutdownPid = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWrongShutdownPid[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportWrongShutdownPid.backendShutdowns = @(
    [ordered]@{ backend='mysql'; command='mysqladmin shutdown'; commandExitCode=0; processId=3333; shutdownTargetVerified=$true; processExitCode=0; processExitCodeObserved=$true; processExited=$true; launchProcessId=1110; launchProcessExitCode=0; launchProcessExitCodeObserved=$true; launchProcessExited=$true; forced=$false },
    $mysqlRuntimeReport.backendShutdowns[1]
)
if (Test-T21MySqlRuntimeReport $mysqlReportWrongShutdownPid $paperMinimumSha $candidateSha) {
    throw 'A MySQL shutdown targeting a different listener process was accepted.'
}
Write-Output 'PASS: reject MySQL shutdown evidence for a different listener process'

$mysqlReportUnverifiedShutdown = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportUnverifiedShutdown[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportUnverifiedShutdown.backendShutdowns = @(
    [ordered]@{ backend='mysql'; command='mysqladmin shutdown'; commandExitCode=0; processId=1111; shutdownTargetVerified=$false; processExitCode=0; processExitCodeObserved=$true; processExited=$true; launchProcessId=1110; launchProcessExitCode=0; launchProcessExitCodeObserved=$true; launchProcessExited=$true; forced=$false },
    $mysqlRuntimeReport.backendShutdowns[1]
)
if (Test-T21MySqlRuntimeReport $mysqlReportUnverifiedShutdown $paperMinimumSha $candidateSha) {
    throw 'A MySQL shutdown without run-owned listener verification was accepted.'
}
Write-Output 'PASS: reject MySQL shutdown evidence without run-owned target verification'

$mysqlReportWrongDialect = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWrongDialect[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportWrongDialect.configuration = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.configuration.Keys) { $mysqlReportWrongDialect.configuration[$key] = $mysqlRuntimeReport.configuration[$key] }
$mysqlReportWrongDialect.configuration.databaseType = 'postgresql'
if (Test-T21MySqlRuntimeReport $mysqlReportWrongDialect $paperMinimumSha $candidateSha) {
    throw 'A PostgreSQL report was accepted as MySQL dialect evidence.'
}
Write-Output 'PASS: reject a report for the wrong SQL dialect'

$mysqlReportForeignDatabase = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportForeignDatabase[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportForeignDatabase.configuration = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.configuration.Keys) { $mysqlReportForeignDatabase.configuration[$key] = $mysqlRuntimeReport.configuration[$key] }
$mysqlReportForeignDatabase.configuration.databaseHost = '10.10.10.10'
if (Test-T21MySqlRuntimeReport $mysqlReportForeignDatabase $paperMinimumSha $candidateSha) {
    throw 'A report that used a non-loopback SQL host was accepted as isolated test evidence.'
}
Write-Output 'PASS: reject a SQL snapshot from a non-loopback host'

$mysqlReportInvalidPort = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportInvalidPort[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportInvalidPort.configuration = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.configuration.Keys) { $mysqlReportInvalidPort.configuration[$key] = $mysqlRuntimeReport.configuration[$key] }
$mysqlReportInvalidPort.configuration.redisPort = 0
if (Test-T21MySqlRuntimeReport $mysqlReportInvalidPort $paperMinimumSha $candidateSha) {
    throw 'A report with no isolated Redis port was accepted.'
}
Write-Output 'PASS: reject missing isolated backend port evidence'

$mysqlReportForcedBackendStop = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportForcedBackendStop[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportForcedBackendStop.backendShutdowns = @(
    $mysqlRuntimeReport.backendShutdowns[0],
    [ordered]@{ backend='redis'; command='SHUTDOWN SAVE'; commandExitCode=1; processExitCode=0; processExitCodeObserved=$true; processExited=$true; forced=$true }
)
if (Test-T21MySqlRuntimeReport $mysqlReportForcedBackendStop $paperMinimumSha $candidateSha) {
    throw 'A forced Redis cleanup was accepted as normal backend shutdown evidence.'
}
Write-Output 'PASS: reject forced backend cleanup from a passing runtime report'

$mysqlReportMismatch = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportMismatch[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportMismatch.snapshots = @(
    $mysqlRuntimeReport.snapshots[0],
    [ordered]@{
        stage='after-restart-read-stop'
        sqlAccounts=$mysqlRuntimeRows
        redisAccounts=@(
            [ordered]@{ name='AcceptanceA'; uuid=$accountA; balance='1337.25'; version=3 },
            [ordered]@{ name='AcceptanceB'; uuid=$accountB; balance='765.50'; version=2 }
        )
    }
)
if (Test-T21MySqlRuntimeReport $mysqlReportMismatch $paperMinimumSha $candidateSha) {
    throw 'SQL/Redis version disagreement after restart was accepted.'
}
Write-Output 'PASS: reject SQL/Redis version disagreement after restart'

$mysqlReportMissingStop = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportMissingStop[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportMissingStop.normalStops = @($mysqlRuntimeReport.normalStops[0])
if (Test-T21MySqlRuntimeReport $mysqlReportMissingStop $paperMinimumSha $candidateSha) {
    throw 'A report missing the second normal server stop was accepted.'
}
Write-Output 'PASS: reject incomplete candidate restart/shutdown lifecycle evidence'

$mysqlReportWrongPersistedValues = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWrongPersistedValues[$key] = $mysqlRuntimeReport[$key] }
$wrongPersistedRows = @(
    [ordered]@{ name='AcceptanceA'; uuid=$accountA; balance='0.00'; version=2 },
    [ordered]@{ name='AcceptanceB'; uuid=$accountB; balance='0.00'; version=2 }
)
$mysqlReportWrongPersistedValues.snapshots = @(
    [ordered]@{ stage='after-write-stop'; sqlAccounts=$wrongPersistedRows; redisAccounts=$wrongPersistedRows },
    [ordered]@{ stage='after-restart-read-stop'; sqlAccounts=$wrongPersistedRows; redisAccounts=$wrongPersistedRows }
)
if (Test-T21MySqlRuntimeReport $mysqlReportWrongPersistedValues $paperMinimumSha $candidateSha) {
    throw 'Matching SQL and Redis snapshots with balances different from the accepted Vault writes were accepted.'
}
Write-Output 'PASS: reject SQL/Redis snapshots that agree with each other but disagree with accepted Vault writes'

$mysqlReportWrongStartupIdentity = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWrongStartupIdentity[$key] = $mysqlRuntimeReport[$key] }
$wrongStartupWrite = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.startupObservations[0].Keys) { $wrongStartupWrite[$key] = $mysqlRuntimeReport.startupObservations[0][$key] }
$wrongStartupWrite.runtimeIdentity = [ordered]@{ software='paper'; runtimeVersion='1.21.6'; minecraftVersion='1.21.6'; build=47 }
$mysqlReportWrongStartupIdentity.startupObservations = @($wrongStartupWrite, $mysqlRuntimeReport.startupObservations[1])
if (Test-T21MySqlRuntimeReport $mysqlReportWrongStartupIdentity $paperMinimumSha $candidateSha) {
    throw 'A Paper startup observation for the wrong build was accepted under the minimum-endpoint report label.'
}
Write-Output 'PASS: reject startup runtime identity that differs from the pinned Paper build'

$mysqlReportWithFailure = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWithFailure[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportWithFailure.failure = 'A runtime stage failed.'
if (Test-T21MySqlRuntimeReport $mysqlReportWithFailure $paperMinimumSha $candidateSha) {
    throw 'A passing runtime report with a recorded failure was accepted.'
}
Write-Output 'PASS: reject a passing status with a recorded runtime failure'

$mysqlReportWithCleanupFailure = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWithCleanupFailure[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportWithCleanupFailure.cleanupFailures = @('A run-owned process remained after cleanup.')
if (Test-T21MySqlRuntimeReport $mysqlReportWithCleanupFailure $paperMinimumSha $candidateSha) {
    throw 'A passing runtime report with a cleanup failure was accepted.'
}
Write-Output 'PASS: reject a passing status with a cleanup failure'

$mysqlReportConfigAuditMismatch = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportConfigAuditMismatch[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportConfigAuditMismatch.configuration = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.configuration.Keys) { $mysqlReportConfigAuditMismatch.configuration[$key] = $mysqlRuntimeReport.configuration[$key] }
$mysqlReportConfigAuditMismatch.configuration.databaseNameObserved = 'syncmoney_t21_different'
if (Test-T21MySqlRuntimeReport $mysqlReportConfigAuditMismatch $paperMinimumSha $candidateSha) {
    throw 'A runtime report whose captured database name differs from its isolated profile was accepted.'
}
Write-Output 'PASS: reject disagreement between configured and observed SQL schema names'

$mysqlReportWrongBackendVersion = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWrongBackendVersion[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportWrongBackendVersion.mysql = [ordered]@{ version='8.0.41'; serverVersion='8.0.41' }
if (Test-T21MySqlRuntimeReport $mysqlReportWrongBackendVersion $paperMinimumSha $candidateSha) {
    throw 'A report with conflicting top-level MySQL version evidence was accepted.'
}
Write-Output 'PASS: reject conflicting MySQL runtime version evidence'

$mysqlRunnerPath = Join-Path $PSScriptRoot 'run-mysql-sync-runtime.ps1'
if (-not (Test-Path -LiteralPath $mysqlRunnerPath -PathType Leaf)) {
    throw 'Missing isolated MySQL SYNC runtime runner for the T21 minimum-Paper dialect cell.'
}
$runnerTokens = $null
$runnerParseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($mysqlRunnerPath, [ref]$runnerTokens, [ref]$runnerParseErrors) > $null
if (@($runnerParseErrors).Count -gt 0) {
    $details = @($runnerParseErrors | ForEach-Object { '{0}: {1}' -f $_.Extent.StartLineNumber, $_.Message }) -join '; '
    throw "The isolated MySQL SYNC runtime runner has PowerShell parse errors: $details"
}
Write-Output 'PASS: parse the isolated MySQL SYNC runtime runner'

$runnerSource = Get-Content -LiteralPath $mysqlRunnerPath -Raw
$mysqlPortAssignments = [regex]::Matches($runnerSource, '(?m)^\s*\$script:mysqlPort\s*=\s*Get-FreeLoopbackPort')
if ($mysqlPortAssignments.Count -ne 1) {
    throw "The isolated MySQL SYNC runtime runner must allocate its database port once before publishing it to services and PlugDev; observed $($mysqlPortAssignments.Count) allocations."
}
Write-Output 'PASS: keep one reserved MySQL port across backend and PlugDev configuration'
if ($runnerSource -notmatch "'mariadb-install-db\.exe'" -or
    $runnerSource -notmatch "'--allow-remote-root-access'" -or
    $runnerSource -match "'--service(?:=|')") {
    throw 'MariaDB initialization must use the pinned isolated data directory and must not install a Windows service.'
}
Write-Output 'PASS: initialize MariaDB without registering a Windows service'
$mysqlAdminStart = $runnerSource.IndexOf('function Invoke-MySqlAdmin', [StringComparison]::Ordinal)
$mysqlListenerStart = $runnerSource.IndexOf('function Assert-BackendListenerOwner', [StringComparison]::Ordinal)
if ($mysqlAdminStart -lt 0 -or $mysqlListenerStart -le $mysqlAdminStart) {
    throw 'Could not isolate the MySQL-protocol admin helper for credential-mode validation.'
}
$mysqlAdminSource = $runnerSource.Substring($mysqlAdminStart, $mysqlListenerStart - $mysqlAdminStart)
if (-not $mysqlAdminSource.Contains('MYSQL_PWD') -or
    -not $mysqlAdminSource.Contains('MariaDB') -or
    -not $mysqlAdminSource.Contains('$script:databasePassword') -or
    -not $mysqlAdminSource.Contains("else { '' }")) {
    throw 'The MariaDB mysqladmin helper must authenticate with the isolated root password while MySQL retains passwordless root.'
}
Write-Output 'PASS: authenticate MariaDB admin calls with the isolated root password'

$mysqlSecurityHelperPattern = '(?s)function Get-MySqlLoopbackClientArgs(?:\(\))?\s*\{(?:(?!function ).)*''MariaDB''(?:(?!function ).)*''--skip-ssl-verify-server-cert''(?:(?!function ).)*return @\(\)(?:(?!function ).)*\}'
if ($runnerSource -notmatch $mysqlSecurityHelperPattern -or
    [regex]::Matches($runnerSource, '\(Get-MySqlLoopbackClientArgs\)').Count -ne 2) {
    throw 'MariaDB client calls must explicitly skip certificate verification for isolated loopback connections, without changing MySQL client options.'
}
Write-Output 'PASS: keep MariaDB loopback client output free of default TLS verification warnings'

if ($runnerSource -notmatch "Invoke-PlugDev @\('server', 'command', 'stop'\)" -or
    $runnerSource -match "Invoke-PlugDev @\('server', 'stop'\)") {
    throw 'The runtime runner must send a graceful RCON stop command and then observe process exit; PlugDev server stop may terminate its process tree before plugin shutdown completes.'
}
Write-Output 'PASS: use graceful RCON stop and observe normal plugin shutdown'

if ($runnerSource -notmatch '\$processExitCode\s*=\s*\[int\]\$serverProcess\.ExitCode' -or
    $runnerSource -notmatch 'processExitCodeObserved\s*=\s*\$true') {
    throw 'The runtime report must capture the actual run-owned Paper exit code instead of synthesizing a successful shutdown.'
}
Write-Output 'PASS: report the observed run-owned Paper exit code'

if ($runnerSource -notmatch '--database=\$script:databaseName') {
    throw 'MySQL snapshot reads must select the isolated schema instead of relying on a default database.'
}
Write-Output 'PASS: bind MySQL snapshots to the isolated schema'

$mysqlShutdownCallIndex = $runnerSource.IndexOf('try { Stop-MySqlBackend }', [StringComparison]::Ordinal)
$redisShutdownCallIndex = $runnerSource.IndexOf('try { Stop-RedisBackend }', [StringComparison]::Ordinal)
$logHashIndex = $runnerSource.IndexOf('foreach ($log in @(Get-ChildItem -LiteralPath $script:logsRoot -File -Recurse))', [StringComparison]::Ordinal)
if ($mysqlShutdownCallIndex -lt 0 -or $redisShutdownCallIndex -lt 0 -or $logHashIndex -lt 0 -or
    $logHashIndex -lt [Math]::Max($mysqlShutdownCallIndex, $redisShutdownCallIndex)) {
    throw 'Log hashes must be computed only after run-owned MySQL and Redis processes release their redirected log files.'
}
Write-Output 'PASS: hash runtime logs after isolated backends exit'

if ($runnerSource -notmatch 'Copy-Item -LiteralPath \$script:serverLogPath -Destination \$paperLogPath' -or
    $runnerSource -notmatch '\$script:report\.paperLogs = @\(\$script:paperServerLogs\)') {
    throw 'The runner must preserve each stopped Paper session log and bind its metadata into the report.'
}
Write-Output 'PASS: preserve and report each stage-specific Paper server log'

Write-Output 'T21 runner validation tests PASS'
}
finally {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]@('\', '/'))
    $testRoot = [IO.Path]::GetFullPath($mysqlRuntimeTestRoot).TrimEnd([char[]]@('\', '/'))
    $tempRootPrefix = $tempRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $testRoot.StartsWith($tempRootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to clean a T21 validation fixture outside the current temporary directory.'
    }
    if (Test-Path -LiteralPath $testRoot -PathType Container) {
        $fixtureRoot = Get-Item -LiteralPath $testRoot -Force
        if (($fixtureRoot.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            Remove-Item -LiteralPath $testRoot -Force
        } else {
            $fixtureLogs = Join-Path $testRoot 'logs'
            if (Test-Path -LiteralPath $fixtureLogs -PathType Container) {
                $logsItem = Get-Item -LiteralPath $fixtureLogs -Force
                if (($logsItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                    Remove-Item -LiteralPath $fixtureLogs -Force
                } else {
                    $reparseChildren = @(Get-ChildItem -LiteralPath $fixtureLogs -Force | Where-Object {
                        ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
                    })
                    if ($reparseChildren.Count -gt 0) {
                        throw 'Refusing recursive cleanup while a T21 fixture log path contains a reparse point.'
                    }
                }
            }
            if ($mysqlRuntimeReparseTarget -and (Test-Path -LiteralPath $mysqlRuntimeReparseTarget -PathType Container)) {
                if (Test-Path -LiteralPath $fixtureLogs) {
                    throw 'Refusing to clean the T21 fixture while its reparse target has not been restored.'
                }
                Move-Item -LiteralPath $mysqlRuntimeReparseTarget -Destination $fixtureLogs
            }
            Remove-Item -LiteralPath $testRoot -Recurse -Force
        }
    }
}
