$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot 'run-sync-upgrade-cleanup.ps1')

function Assert-CleanupAction([string]$name, [string]$expected, [object]$actual) {
    if ($actual.action -cne $expected) { throw "$name expected action '$expected', observed '$($actual.action)'" }
    Write-Output "PASS: $name"
}

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

$noProcess = Invoke-RunOwnedPaperCleanupFallback @() {
    param($processId)
    throw 'Stop callback must not run without a process.'
} {
    param($processId)
    throw 'Exit callback must not run without a process.'
}
Assert-CleanupAction 'accept an already exited run-owned process without stopping another process' 'ALREADY_EXITED' $noProcess

$calls = [Collections.Generic.List[string]]::new()
$singleProcess = Invoke-RunOwnedPaperCleanupFallback @([pscustomobject]@{ ProcessId = 1042 }) {
    param($processId)
    $calls.Add("stop:$processId")
} {
    param($processId)
    $calls.Add("verify:$processId")
}
Assert-CleanupAction 'force-stop and verify the unique run-owned process' 'FORCE_STOP_VERIFIED' $singleProcess
if (($calls -join ',') -cne 'stop:1042,verify:1042' -or $singleProcess.processId -ne 1042) {
    throw 'Fallback did not stop and verify exactly the uniquely owned process.'
}
Write-Output 'PASS: cleanup callbacks receive only the unique run-owned PID'

$powershellPath = (Get-Command pwsh -ErrorAction Stop).Source
$exitProbe = Start-Process -FilePath $powershellPath -ArgumentList @('-NoLogo', '-NoProfile', '-Command', 'exit 23') -PassThru -Wait -WindowStyle Hidden
$observedExitCode = Get-RunOwnedProcessExitCode $exitProbe $exitProbe.Id
if ($observedExitCode -ne 23) {
    throw "A completed process exit code was not observed accurately: $observedExitCode"
}
if ($null -ne (Get-RunOwnedProcessExitCode $exitProbe ($exitProbe.Id + 1))) {
    throw 'An exit code was attributed to a PID other than the retained process handle.'
}
if ($null -ne (Get-RunOwnedProcessExitCode (Get-Process -Id $PID) $PID)) {
    throw 'A running process was reported as exited.'
}
Write-Output 'PASS: read only the retained process handle exit code for its matching PID'

$ambiguousCalls = [Collections.Generic.List[string]]::new()
Assert-ThrowsContaining 'refuse cleanup when run-owned process identity is ambiguous' {
    Invoke-RunOwnedPaperCleanupFallback @(
        [pscustomobject]@{ ProcessId = 1042 },
        [pscustomobject]@{ ProcessId = 1043 }
    ) {
        param($processId)
        $ambiguousCalls.Add("stop:$processId")
    } {
        param($processId)
        $ambiguousCalls.Add("verify:$processId")
    }
} 'ambiguous'
if ($ambiguousCalls.Count -ne 0) { throw 'Ambiguous cleanup invoked a stop or verification callback.' }
Write-Output 'PASS: ambiguous ownership invokes no cleanup callback'

$gradleNoDaemonCalls = [Collections.Generic.List[string]]::new()
$noGradleDaemon = Invoke-RunOwnedGradleDaemonCleanup @() {
    param($processIds)
    $gradleNoDaemonCalls.Add('stop')
    return [ordered]@{ exitCode = 0 }
} {
    param($processIds)
    $gradleNoDaemonCalls.Add('verify')
    return @()
}
if ($noGradleDaemon.action -cne 'NO_RUN_OWNED_GRADLE_DAEMON' -or $gradleNoDaemonCalls.Count -ne 0) {
    throw 'No-daemon cleanup called a stop callback or returned the wrong action.'
}
Write-Output 'PASS: isolated Gradle cleanup skips --stop when this run owns no daemon'

$gradleCalls = [Collections.Generic.List[string]]::new()
$stoppedGradleDaemons = Invoke-RunOwnedGradleDaemonCleanup @(
    [pscustomobject]@{ ProcessId = 2042 },
    [pscustomobject]@{ ProcessId = 2043 }
) {
    $gradleCalls.Add('stop')
    return [ordered]@{ exitCode = 0 }
} {
    $gradleCalls.Add('verify')
    return @()
}
if ($stoppedGradleDaemons.action -cne 'RUN_OWNED_GRADLE_DAEMONS_STOPPED' -or
    ($stoppedGradleDaemons.processIds -join ',') -cne '2042,2043' -or
    $stoppedGradleDaemons.stopExitCode -ne 0 -or
    ($gradleCalls -join '|') -cne 'stop|verify') {
    throw 'Gradle cleanup did not stop and verify exactly the run-owned daemon set.'
}
Write-Output 'PASS: stop and verify all isolated run-owned Gradle daemons'

$failedGradleCalls = [Collections.Generic.List[string]]::new()
Assert-ThrowsContaining 'fail cleanup when isolated Gradle --stop fails' {
    Invoke-RunOwnedGradleDaemonCleanup @([pscustomobject]@{ ProcessId = 2044 }) {
        $failedGradleCalls.Add('stop')
        return [ordered]@{ exitCode = 7 }
    } {
        $failedGradleCalls.Add('verify')
        return @()
    }
} 'exited 7'
if (($failedGradleCalls -join ',') -cne 'stop') { throw 'Failed --stop must not be accepted or verified as successful.' }

Assert-ThrowsContaining 'fail cleanup while an owned Gradle daemon remains alive' {
    Invoke-RunOwnedGradleDaemonCleanup @([pscustomobject]@{ ProcessId = 2045 }) {
        return [ordered]@{ exitCode = 0 }
    } {
        return @([pscustomobject]@{ ProcessId = 2045 })
    }
} '2045'

$runningPostgresStatus = @(
    'pg_ctl: server is running (PID: 32872)',
    'C:/Program Files/PostgreSQL/18/bin/postgres.exe "-D" "C:/isolated/postgres/data"'
)
if ((Get-RunOwnedPostgresStatusKind 0 $runningPostgresStatus) -cne 'RUNNING') {
    throw 'A verified PostgreSQL running status was not recognized.'
}
Write-Output 'PASS: recognize a verified PostgreSQL running status'

$stoppedPostgresStatus = @('pg_ctl: no server running')
if ((Get-RunOwnedPostgresStatusKind 3 $stoppedPostgresStatus) -cne 'STOPPED') {
    throw 'PostgreSQL exit 3 with the exact no-server marker was not recognized as stopped.'
}
Write-Output 'PASS: recognize PostgreSQL exit 3 with its exact stopped marker'

Assert-ThrowsContaining 'reject an unrecognized PostgreSQL status failure' {
    Assert-RunOwnedPostgresStatus 'STOPPED' 2 @('pg_ctl: could not open postmaster.pid')
} 'observed UNKNOWN'
Assert-ThrowsContaining 'reject a generic nonzero PostgreSQL status as stopped' {
    Assert-RunOwnedPostgresStatus 'STOPPED' 1 @('pg_ctl: status command failed')
} 'observed UNKNOWN'
Assert-ThrowsContaining 'reject an absent PostgreSQL process before the requested stop' {
    Assert-RunOwnedPostgresStatus 'RUNNING' 3 $stoppedPostgresStatus
} 'observed STOPPED'

if (-not (Assert-RunOwnedRedisProcessMayBeAlreadyExited $false $true)) {
    throw 'A Redis process that exited before readiness should not block failure cleanup.'
}
Write-Output 'PASS: allow an unready Redis process to have already exited after start failure'
Assert-ThrowsContaining 'reject an unexpectedly exited ready Redis process' {
    Assert-RunOwnedRedisProcessMayBeAlreadyExited $true $true
} 'before the runner verified'

if ((Get-T21PaperCleanupAction @() $true) -cne 'ALREADY_EXITED' -or
    (Get-T21PaperCleanupAction @(1046) $true) -cne 'TRY_GRACEFUL_STOP' -or
    (Get-T21PaperCleanupAction @(1047) $false) -cne 'STOP_UNEXPECTED_RUN_OWNED_PROCESS') {
    throw 'Paper startup/cleanup states were not mapped to safe ownership actions.'
}
Write-Output 'PASS: clean up only the unique run-owned Paper PID after a possibly launched startup'
Assert-ThrowsContaining 'refuse Paper cleanup when run-owned startup processes are ambiguous' {
    Get-T21PaperCleanupAction @(1046, 1047) $true
} 'ambiguous'

$noRedis = Get-T21RedisCleanupPlan @() @()
if ($noRedis.action -cne 'ALREADY_EXITED' -or $null -ne $noRedis.processId) {
    throw 'An unstarted Redis process should not trigger a shutdown action.'
}
Write-Output 'PASS: accept an unready Redis process that already exited with no listener'
$readyRedis = Get-T21RedisCleanupPlan @(2051) @(2051)
if ($readyRedis.action -cne 'SHUTDOWN_RUN_OWNED_LISTENER' -or $readyRedis.processId -ne 2051) {
    throw 'A uniquely owned Redis listener did not select graceful shutdown.'
}
Write-Output 'PASS: gracefully stop only the matching run-owned Redis listener'
$unreadyRedis = Get-T21RedisCleanupPlan @(2052) @()
if ($unreadyRedis.action -cne 'STOP_RUN_OWNED_PROCESS' -or $unreadyRedis.processId -ne 2052) {
    throw 'An unready run-owned Redis process was not selected for bounded fallback cleanup.'
}
Write-Output 'PASS: clean up a run-owned Redis process whose listener PID was never recorded'
$foreignListener = Get-T21RedisCleanupPlan @(2053) @(9053)
if ($foreignListener.action -cne 'STOP_RUN_OWNED_PROCESS' -or $foreignListener.processId -ne 2053) {
    throw 'Redis fallback cleanup did not preserve an unrelated listener PID.'
}
Write-Output 'PASS: preserve an unrelated Redis port listener while stopping this run-owned process'
Assert-ThrowsContaining 'refuse to touch a Redis listener with no run-owned process' {
    Get-T21RedisCleanupPlan @() @(9054)
} 'unrelated process'
Assert-ThrowsContaining 'refuse Redis cleanup when run-owned process identity is ambiguous' {
    Get-T21RedisCleanupPlan @(2055, 2056) @()
} 'ambiguous'

if ((Get-T21PostgresStopAction 'RUNNING') -cne 'STOP_AND_VERIFY' -or
    (Get-T21PostgresStopAction 'STOPPED') -cne 'ALREADY_STOPPED' -or
    (Get-T21PostgresStopAction 'UNKNOWN') -cne 'ATTEMPT_BOUNDED_STOP_AND_FAIL') {
    throw 'PostgreSQL stop status did not select the bounded, fail-closed action.'
}
Write-Output 'PASS: attempt bounded PostgreSQL cleanup but fail an unknown status'

Write-Output 'Sync upgrade cleanup tests PASS'
