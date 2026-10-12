$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$runnerPath = Join-Path $PSScriptRoot 'run-quiesced-network-upgrade.ps1'
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($runnerPath, [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) { throw "The quiesced upgrade runner does not parse: $($errors[0].Message)" }

function Get-RunnerFunctionText([string]$name) {
    $matches = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name }, $true))
    if ($matches.Count -ne 1) { throw "Expected exactly one runner function named $name." }
    return $matches[0].Extent.Text
}

function Assert-RunnerCondition([string]$name, [bool]$condition) {
    if (-not $condition) { throw "Quiesced upgrade runner regression: $name." }
    Write-Output "PASS: $name"
}

function Test-T21RollbackJournalGatePlacement([string]$scriptText) {
    $parseTokens = $null
    $parseErrors = $null
    $parsed = [Management.Automation.Language.Parser]::ParseInput($scriptText, [ref]$parseTokens, [ref]$parseErrors)
    if ($parseErrors.Count -gt 0) { return $false }

    $rollbackLoops = @($parsed.FindAll({
        param($node)
        $node -is [Management.Automation.Language.ForEachStatementAst] -and
            $node.Body.Extent.Text.Contains('smaccept rollback-read')
    }, $true))
    if ($rollbackLoops.Count -ne 1) { return $false }

    $loopBody = $rollbackLoops[0].Body.Extent.Text
    $normalStop = $loopBody.IndexOf('Stop-Node $name', [StringComparison]::Ordinal)
    $journalGate = $loopBody.IndexOf('Assert-RecoveryJournalCheckpointsUnchanged $rollbackJournalCheckpoints', [StringComparison]::Ordinal)
    return $normalStop -ge 0 -and $journalGate -gt $normalStop
}

$initializeConfig = Get-RunnerFunctionText 'Initialize-NodeConfig'
$setVersion = Get-RunnerFunctionText 'Set-NodeVersion'
$setApiConsumer = Get-RunnerFunctionText 'Set-NodeApiConsumer'
$startNode = Get-RunnerFunctionText 'Start-Node'
$normalNodeStop = Get-RunnerFunctionText 'Stop-Node'
$saveReport = Get-RunnerFunctionText 'Save-Report'
$configObservation = Get-RunnerFunctionText 'Get-NodeConfigObservation'
$paperCleanup = Get-RunnerFunctionText 'Stop-PossiblyStartedNode'
$processWait = Get-RunnerFunctionText 'Wait-ProcessExit'
$schemaGate = Get-RunnerFunctionText 'Assert-SnapshotSchemaRows'
$drainAssessment = Get-RunnerFunctionText 'Get-T21NormalDrainAssessment'
$backendVersions = Get-RunnerFunctionText 'Assert-BackendVersions'
$journalCheckpoint = Get-RunnerFunctionText 'Get-RecoveryJournalCheckpoint'
$journalRollbackGate = Get-RunnerFunctionText 'Assert-RecoveryJournalCheckpointsUnchanged'

Assert-RunnerCondition 'initialize the synthetic operator fixture only in one-time config setup' (
    $initializeConfig.Contains("t21-synthetic-operator-fixture") -and
    $initializeConfig.Contains('fs.writeFileSync(cp') -and
    -not $setVersion.Contains('t21-synthetic-operator-fixture') -and
    -not $setVersion.Contains('fs.writeFileSync(cp')
)
Assert-RunnerCondition 'read and gate config retention before each artifact startup' (
    $configObservation.Contains('Assert-T21NodeConfigObservation') -and
    $setVersion.Contains('Get-NodeConfigObservation')
)
$apiConsumerToggle = $startNode.IndexOf('Set-NodeApiConsumer $nodeName $candidateApiConsumer', [StringComparison]::Ordinal)
$apiConsumerStart = $startNode.IndexOf("@('server','start','--config','.plugdev\profile.yml')", [StringComparison]::Ordinal)
Assert-RunnerCondition 'load the candidate API consumer only with the candidate Syncmoney artifact' (
    $setApiConsumer.Contains('Remove-Item -LiteralPath $consumerPath') -and
    $setApiConsumer.Contains('Copy-Item -LiteralPath $script:apiConsumerPath') -and
    $startNode.Contains('$version -eq $CandidateVersion') -and
    $apiConsumerToggle -ge 0 -and $apiConsumerStart -gt $apiConsumerToggle
)
Assert-RunnerCondition 'derive and assert the exact server runtime identity from each startup log' (
    $startNode.Contains('Get-T21PlatformIdentity') -and
    $startNode.Contains('Assert-T21PlatformIdentity $runtimeIdentity $Project $MinecraftVersion $BuildNumber') -and
    $startNode.Contains('serverRuntime=$runtimeIdentity')
)
$source = Get-Content -LiteralPath $runnerPath -Raw
Assert-RunnerCondition 'use shared-state reads for 1.3.2, candidate, and rollback upgrade observations' (
    $source.Contains("'smaccept state-read 1000.00 520.00'") -and
    $source.Contains("'smaccept state-read 1337.25 765.50'") -and
    $source.Contains("'smaccept rollback-read 1337.25 765.50'")
)
$startupMarker = $startNode.IndexOf('$script:serverMayExist[$nodeName]=$true', [StringComparison]::Ordinal)
$startCommand = $startNode.IndexOf("@('server','start','--config','.plugdev\profile.yml')", [StringComparison]::Ordinal)
Assert-RunnerCondition 'mark Paper as possibly started before invoking PlugDev' ($startupMarker -ge 0 -and $startCommand -gt $startupMarker)
Assert-RunnerCondition 'failure cleanup targets only a unique run-marked Paper PID and verifies exit' (
    $paperCleanup.Contains('Get-RunOwnedProcesses') -and
    $paperCleanup.Contains('Invoke-RunOwnedPaperCleanupFallback') -and
    $paperCleanup.Contains('Wait-ProcessExit')
)
Assert-RunnerCondition 'avoid PowerShell automatic PID variable collision in process-exit checks' (
    $processWait.Contains('$processId') -and -not $processWait.Contains('$pid')
)
$processWaitProbe = [scriptblock]::Create($processWait + "`nWait-ProcessExit -processId `$PID -seconds 0")
$processWaitResult = & $processWaitProbe
Assert-RunnerCondition 'execute the process-exit helper with the current PowerShell PID without variable collision' ($processWaitResult -eq $false)
Assert-RunnerCondition 'gate backend schema rows against the baseline snapshot' $schemaGate.Contains('Assert-T21SchemaVersionRows')
Assert-RunnerCondition 'gate exact SQL and Redis account versions before upgrade, after candidate writes, and after rollback' (
    $source.Contains('Assert-BackendVersions $baselineSnapshot 1 1') -and
    $source.Contains('Assert-BackendVersions $afterRestart 1 1') -and
    $source.Contains('Assert-BackendVersions $candidateSnapshot 2 2') -and
    $source.Contains('Assert-BackendVersions $newNodeSnapshot 2 2') -and
    $source.Contains('Assert-BackendVersions $snap 2 2') -and
    $backendVersions.Contains('redisVersion') -and
    $backendVersions.Contains('account[0].version')
)
$versionProbe = [scriptblock]::Create($backendVersions + "`n`$snapshot = [ordered]@{accounts=@([ordered]@{name='AcceptanceA';version=2;redisVersion=2},[ordered]@{name='AcceptanceB';version=2;redisVersion=2})}; Assert-BackendVersions `$snapshot 2 2 'test'")
$versionProbeResult = $true
try { & $versionProbe } catch { $versionProbeResult = $false }
Assert-RunnerCondition 'accept matching expected SQL and Redis versions' $versionProbeResult
$versionMismatchProbe = [scriptblock]::Create($backendVersions + "`n`$snapshot = [ordered]@{accounts=@([ordered]@{name='AcceptanceA';version=1;redisVersion=1},[ordered]@{name='AcceptanceB';version=2;redisVersion=2})}; try { Assert-BackendVersions `$snapshot 2 2 'test'; `$false } catch { `$true }")
Assert-RunnerCondition 'reject a version that failed to advance after an accepted mutation' (& $versionMismatchProbe)
$source = Get-Content -LiteralPath $runnerPath -Raw
$candidateConfigGateA = $source.IndexOf("Get-NodeConfigObservation 'nodeA' 'after candidate shutdown before rollback'", [StringComparison]::Ordinal)
$candidateConfigGateB = $source.IndexOf("Get-NodeConfigObservation 'nodeB' 'after candidate shutdown before rollback'", [StringComparison]::Ordinal)
$rollbackStart = $source.IndexOf("Start-Node `$name '1.3.2'", [StringComparison]::Ordinal)
Assert-RunnerCondition 'check each candidate config after shutdown and before rollback' (
    $candidateConfigGateA -ge 0 -and $candidateConfigGateB -gt $candidateConfigGateA -and $rollbackStart -gt $candidateConfigGateB
)
$candidateJournalA = $source.IndexOf("Get-RecoveryJournalCheckpoint 'nodeA'", [StringComparison]::Ordinal)
$candidateJournalB = $source.IndexOf("Get-RecoveryJournalCheckpoint 'nodeB'", [StringComparison]::Ordinal)
$journalCheckpointPair = $source.IndexOf('$rollbackJournalCheckpoints=@($nodeARecoveryCheckpoint,$nodeBRecoveryCheckpoint)', [StringComparison]::Ordinal)
$journalRollbackAssertion = $source.IndexOf('$null=Assert-RecoveryJournalCheckpointsUnchanged $rollbackJournalCheckpoints', [StringComparison]::Ordinal)
Assert-RunnerCondition 'checkpoint both present and absent recovery-journal states after candidate shutdown and before rollback' (
    $candidateJournalA -ge 0 -and $candidateJournalB -gt $candidateJournalA -and
    $journalCheckpointPair -gt $candidateJournalB -and $journalRollbackAssertion -gt $journalCheckpointPair -and
    $journalCheckpoint.Contains('pendingCount') -and $journalCheckpoint.Contains('sha256')
)
Assert-RunnerCondition 'verify every node journal after each normal rollback stop and report the observations' (
    $journalRollbackGate.Contains('Assert-RecoveryJournalStateUnchanged') -and
    $journalRollbackGate.Contains('$seen.Contains(''nodeA'')') -and
    $journalRollbackGate.Contains('$seen.Contains(''nodeB'')') -and
    $source.Contains('recoveryJournalRollbackChecks=$script:recoveryJournalRollbackChecks')
)
$journalGateCall = '$null=Assert-RecoveryJournalCheckpointsUnchanged $rollbackJournalCheckpoints ("rollback-"+$name);'
$stopThenGate = 'Stop-Node $name ("rollback-"+$name);' + $journalGateCall + '$snap='
$stopOnly = 'Stop-Node $name ("rollback-"+$name);$snap='
$rollbackLoopTail = '$script:snapshots.Add($snap)}'
$outsideLoopSource = $source.Replace($stopThenGate, $stopOnly).Replace($rollbackLoopTail, $rollbackLoopTail + $journalGateCall)
$beforeStopSource = $source.Replace($stopThenGate, $journalGateCall + 'Stop-Node $name ("rollback-"+$name);$snap=')
Assert-RunnerCondition 'place the journal gate after Stop-Node inside the per-node rollback loop' (Test-T21RollbackJournalGatePlacement $source)
Assert-RunnerCondition 'reject moving the journal gate outside the per-node rollback loop' (-not (Test-T21RollbackJournalGatePlacement $outsideLoopSource))
Assert-RunnerCondition 'reject running the journal gate before each normal rollback stop' (-not (Test-T21RollbackJournalGatePlacement $beforeStopSource))
$backendCoverage = $source.LastIndexOf('Test-T21BackendShutdownCoverage $script:backendShutdown.ToArray()', [StringComparison]::Ordinal)
$finalRedisStop = $source.LastIndexOf("Stop-Redis 'final-cleanup'", [StringComparison]::Ordinal)
$finalPostgresStop = $source.LastIndexOf("Stop-Postgres 'final-cleanup'", [StringComparison]::Ordinal)
Assert-RunnerCondition 'require exact backend shutdown observations only after final backend cleanup' (
    $backendCoverage -gt $finalRedisStop -and $backendCoverage -gt $finalPostgresStop -and
    $source.Contains('if ($script:status -ceq')
)
$redisStop = Get-RunnerFunctionText 'Stop-Redis'
Assert-RunnerCondition 'require observed zero Redis process exit and reject an unexpectedly exited ready instance' (
    $redisStop.Contains('Get-RunOwnedProcessExitCode') -and
    $redisStop.Contains('Assert-RunOwnedRedisProcessMayBeAlreadyExited') -and
    -not $redisStop.Contains('processExitCode=0')
)
$source = Get-Content -LiteralPath $runnerPath -Raw
Assert-RunnerCondition 'resolve the pinned baseline checkout as a sibling managed worktree' (
    $source.Contains("Join-Path `$root '..\..\syncmoney-132-baseline\Syncmoney'")
)
$postgresBootstrap = Get-RunnerFunctionText 'Invoke-Psql'
$redisResolver = Get-RunnerFunctionText 'Resolve-RedisServerExecutable'
$createDatabase = $source.IndexOf('CREATE DATABASE $script:dbName;', [StringComparison]::Ordinal)
$probePostgresVersion = $source.IndexOf("Invoke-Psql 'SELECT version();'", [StringComparison]::Ordinal)
Assert-RunnerCondition 'create the isolated database before querying the configured Syncmoney database' (
    $createDatabase -ge 0 -and $probePostgresVersion -gt $createDatabase
)
Assert-RunnerCondition 'preserve private psql diagnostics when an isolated query fails' (
    $postgresBootstrap.Contains('postgres-query-{0:D2}.txt') -and
    $postgresBootstrap.Contains('Set-Content -LiteralPath $failureLog')
)
Assert-RunnerCondition 'resolve Scoop Redis shim to the real server executable before retaining its process handle' (
    $redisResolver.Contains('scoop.Source which redis-server') -and
    $redisResolver.Contains('Resolve-Path -LiteralPath $paths[0]') -and
    $source.Contains('Resolve-RedisServerExecutable $redisServerCommand') -and
    $source.Contains('$script:redisPid -eq [int]$script:redisProcess.Id')
)
Assert-RunnerCondition 'use PlugDev RCON command for Paper stop without its PID-only server-stop fallback' (
    $normalNodeStop.Contains("@('server','command','stop')") -and
    -not $normalNodeStop.Contains("@('server','stop')")
)
Assert-RunnerCondition 'use shared shutdown markers and record actual markers before asserting a normal drain' (
    $normalNodeStop.Contains('Get-T21ShutdownFailureMarkers $content') -and
    $normalNodeStop.Contains('Get-T21NormalDrainAssessment $content $found ([int]$journal.pendingCount)') -and
    $normalNodeStop.Contains('pluginDrainVerified=$assessment.pluginDrainVerified') -and
    $normalNodeStop.Contains('syncmoneyDisabledMarker=$assessment.syncmoneyDisabledMarker') -and
    $normalNodeStop.Contains('pendingRecoveryRecords=$assessment.pendingRecoveryRecords')
)
$drainAssessmentProbe = [scriptblock]::Create($drainAssessment + "`nGet-T21NormalDrainAssessment -content 'Syncmoney disabled.' -failureMarkers @() -pendingRecoveryCount 0")
$healthyDrainAssessment = & $drainAssessmentProbe
Assert-RunnerCondition 'accept a completed Syncmoney disable with no markers or pending records' (
    $healthyDrainAssessment.pluginDrainVerified -eq $true -and $healthyDrainAssessment.syncmoneyDisabledMarker -eq $true
)
$missingDisableMarkerProbe = [scriptblock]::Create($drainAssessment + "`nGet-T21NormalDrainAssessment -content 'server stopped without plugin completion' -failureMarkers @() -pendingRecoveryCount 0")
$missingDisableAssessment = & $missingDisableMarkerProbe
$serializedMissingDisable = $missingDisableAssessment | ConvertTo-Json -Compress | ConvertFrom-Json
Assert-RunnerCondition 'persist a failed shutdown observation with an explicit missing-disable-marker value' (
    $serializedMissingDisable.pluginDrainVerified -eq $false -and $serializedMissingDisable.syncmoneyDisabledMarker -eq $false
)
$failedShutdownReport = [ordered]@{shutdown=@([ordered]@{pluginDrainVerified=$missingDisableAssessment.pluginDrainVerified;syncmoneyDisabledMarker=$missingDisableAssessment.syncmoneyDisabledMarker})}
$serializedFailedShutdownReport = $failedShutdownReport | ConvertTo-Json -Compress | ConvertFrom-Json
Assert-RunnerCondition 'carry a false shutdown marker through the result report shutdown field' (
    $saveReport.Contains('shutdown=$script:shutdown') -and
    $normalNodeStop.Contains('syncmoneyDisabledMarker=$assessment.syncmoneyDisabledMarker') -and
    $serializedFailedShutdownReport.shutdown[0].pluginDrainVerified -eq $false -and
    $serializedFailedShutdownReport.shutdown[0].syncmoneyDisabledMarker -eq $false
)
Assert-RunnerCondition 'describe the requested platform in the quiescence report' (
    $saveReport.Contains('scenario="Only two synthetic $Project nodes')
)
$failureMarkerProbe = [scriptblock]::Create($drainAssessment + "`nGet-T21NormalDrainAssessment -content 'Syncmoney disabled.' -failureMarkers @('DB writer drain timed out') -pendingRecoveryCount 0")
$failedMarkerAssessment = & $failureMarkerProbe
Assert-RunnerCondition 'fail drain assessment when a shutdown failure marker is present despite disable completion' (
    $failedMarkerAssessment.pluginDrainVerified -eq $false -and $failedMarkerAssessment.syncmoneyDisabledMarker -eq $true
)
$pendingRecoveryProbe = [scriptblock]::Create($drainAssessment + "`nGet-T21NormalDrainAssessment -content 'Syncmoney disabled.' -failureMarkers @() -pendingRecoveryCount 1")
$pendingRecoveryAssessment = & $pendingRecoveryProbe
Assert-RunnerCondition 'fail drain assessment while recovery records remain pending' ($pendingRecoveryAssessment.pluginDrainVerified -eq $false)
$assessmentCall = $normalNodeStop.IndexOf('Get-T21NormalDrainAssessment $content $found ([int]$journal.pendingCount)', [StringComparison]::Ordinal)
$assessmentRecord = $normalNodeStop.IndexOf('$script:shutdown.Add([ordered]@{', [StringComparison]::Ordinal)
$assessmentFailure = $normalNodeStop.IndexOf('if (-not $assessment.pluginDrainVerified)', [StringComparison]::Ordinal)
Assert-RunnerCondition 'record failed drain assessment before throwing so the failure report retains false evidence' (
    $assessmentCall -ge 0 -and $assessmentRecord -gt $assessmentCall -and $assessmentFailure -gt $assessmentRecord
)
$source = Get-Content -LiteralPath $runnerPath -Raw
Assert-RunnerCondition 'bind quiesced runtime reports to acceptance artifacts and harness source hashes' (
    $source.Contains('Get-RunHarnessSourceManifest') -and
    $source.Contains('acceptanceJarSha256=') -and
    $source.Contains('apiConsumerJarSha256=') -and
    $source.Contains("'src/acceptance'") -and
    $source.Contains("'src/api-consumer'")
)
$manifestFunction = Get-RunnerFunctionText 'Get-RunHarnessSourceManifest'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$manifestProbe = [scriptblock]::Create($manifestFunction + "`nGet-RunHarnessSourceManifest '$repositoryRoot'")
$sourceManifest = @(& $manifestProbe)
foreach ($entry in $sourceManifest) {
    $sourcePath = Join-Path $repositoryRoot $entry.path.Replace('/','\')
    $sourceHash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($sourceHash -ne $entry.sha256) { throw "Harness source hash mismatch for $($entry.path)." }
}
Assert-RunnerCondition 'produce a complete readable hash manifest for harness sources and acceptance consumers' (
    $sourceManifest.Count -ge 21 -and
    @($sourceManifest | Where-Object { $_.path -ceq 'src/acceptance/java/noietime/syncmoney/acceptance/AcceptanceProbe.java' }).Count -eq 1 -and
    @($sourceManifest | Where-Object { $_.path -ceq 'src/api-consumer/java/noietime/syncmoney/apiconsumer/ApiClassLoaderIT.java' }).Count -eq 1
)

Write-Output 'Quiesced upgrade runner contract tests PASS'
