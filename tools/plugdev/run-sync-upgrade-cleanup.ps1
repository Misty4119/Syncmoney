function Invoke-RunOwnedPaperCleanupFallback(
    [object[]]$ownedProcesses,
    [scriptblock]$stopProcess,
    [scriptblock]$verifyStopped
) {
    $processes = @($ownedProcesses)
    if ($processes.Count -eq 0) {
        return [ordered]@{ action = 'ALREADY_EXITED'; processId = $null }
    }
    if ($processes.Count -ne 1) {
        throw 'Refusing forced cleanup because run-owned Paper process identity is ambiguous.'
    }
    if ($null -eq $stopProcess -or $null -eq $verifyStopped) {
        throw 'Forced cleanup requires both a stop operation and an exit verification.'
    }

    $processId = [int]$processes[0].ProcessId
    if ($processId -le 0) { throw 'Refusing forced cleanup with an invalid run-owned Paper process ID.' }
    $null = & $stopProcess $processId
    $null = & $verifyStopped $processId
    return [ordered]@{ action = 'FORCE_STOP_VERIFIED'; processId = $processId }
}

function Invoke-RunOwnedGradleDaemonCleanup(
    [object[]]$ownedDaemons,
    [scriptblock]$stopDaemons,
    [scriptblock]$verifyStopped
) {
    $daemons = @($ownedDaemons)
    $processIds = @($daemons | ForEach-Object { [int]$_.ProcessId })
    if ($processIds.Count -eq 0) {
        return [ordered]@{
            action = 'NO_RUN_OWNED_GRADLE_DAEMON'
            processIds = @()
            stopExitCode = $null
            remainingProcessIds = @()
        }
    }
    if (@($processIds | Where-Object { $_ -le 0 }).Count -gt 0 -or
        @($processIds | Select-Object -Unique).Count -ne $processIds.Count) {
        throw 'Refusing isolated Gradle cleanup because run-owned daemon identities are invalid or ambiguous.'
    }
    if ($null -eq $stopDaemons -or $null -eq $verifyStopped) {
        throw 'Isolated Gradle cleanup requires a stop operation and process-exit verification.'
    }

    $stopResult = & $stopDaemons
    if ($null -eq $stopResult -or $null -eq $stopResult.exitCode) {
        throw 'Isolated Gradle --stop did not report an exit code.'
    }
    $stopExitCode = [int]$stopResult.exitCode
    if ($stopExitCode -ne 0) {
        throw "Isolated Gradle --stop exited $stopExitCode."
    }

    $remainingDaemons = @(& $verifyStopped)
    $remainingProcessIds = @($remainingDaemons | ForEach-Object { [int]$_.ProcessId })
    if ($remainingProcessIds.Count -gt 0) {
        throw "Run-owned Gradle daemon remained after isolated --stop; process IDs: $($remainingProcessIds -join ', ')."
    }
    return [ordered]@{
        action = 'RUN_OWNED_GRADLE_DAEMONS_STOPPED'
        processIds = @($processIds)
        stopExitCode = $stopExitCode
        remainingProcessIds = @()
    }
}

function Get-RunOwnedPostgresStatusKind([int]$exitCode, [object[]]$output) {
    $lines = @($output | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ -ne '' })
    if ($exitCode -eq 0 -and $lines.Count -ge 1 -and
        $lines[0] -match '^pg_ctl: server is running \(PID: [1-9][0-9]*\)$') {
        return 'RUNNING'
    }
    if ($exitCode -eq 3 -and $lines.Count -eq 1 -and $lines[0] -ceq 'pg_ctl: no server running') {
        return 'STOPPED'
    }
    return 'UNKNOWN'
}

function Assert-RunOwnedPostgresStatus(
    [ValidateSet('RUNNING', 'STOPPED')][string]$expected,
    [int]$exitCode,
    [object[]]$output
) {
    $observed = Get-RunOwnedPostgresStatusKind $exitCode $output
    if ($observed -cne $expected) {
        $detail = (@($output | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ -ne '' }) -join ' | ')
        throw "PostgreSQL status expected $expected, observed $observed (exit=$exitCode): $detail"
    }
    return $observed
}

function Assert-RunOwnedRedisProcessMayBeAlreadyExited([bool]$wasReady, [bool]$processExited) {
    if ($wasReady -and $processExited) {
        throw 'Run-owned Redis exited before the runner verified its requested SHUTDOWN SAVE.'
    }
    return $true
}

function Get-RunOwnedProcessExitCode(
    [Diagnostics.Process]$process,
    [int]$expectedProcessId
) {
    if ($null -eq $process) { return $null }
    try {
        if ([int]$process.Id -ne $expectedProcessId) { return $null }
        $process.Refresh()
        if (-not $process.HasExited) { return $null }
        return [int]$process.ExitCode
    } catch {
        return $null
    }
}

function Get-T21PaperCleanupAction([int[]]$ownedProcessIds, [bool]$startupMayHaveLaunched) {
    $ids = @($ownedProcessIds | ForEach-Object { [int]$_ })
    if (@($ids | Where-Object { $_ -le 0 }).Count -gt 0 -or
        @($ids | Select-Object -Unique).Count -ne $ids.Count) {
        throw 'Run-owned Paper process identities are invalid or duplicated.'
    }
    if ($ids.Count -gt 1) {
        throw 'Refusing Paper cleanup because run-owned process identity is ambiguous.'
    }
    if ($ids.Count -eq 0) { return 'ALREADY_EXITED' }
    if (-not $startupMayHaveLaunched) { return 'STOP_UNEXPECTED_RUN_OWNED_PROCESS' }
    return 'TRY_GRACEFUL_STOP'
}

function Get-T21RedisCleanupPlan([int[]]$ownedProcessIds, [int[]]$listenerProcessIds) {
    $owned = @($ownedProcessIds | ForEach-Object { [int]$_ } | Select-Object -Unique)
    $listeners = @($listenerProcessIds | ForEach-Object { [int]$_ } | Select-Object -Unique)
    if (@($owned + $listeners | Where-Object { $_ -le 0 }).Count -gt 0) {
        throw 'Redis cleanup received an invalid process ID.'
    }
    if ($owned.Count -gt 1) {
        throw 'Refusing Redis cleanup because run-owned process identity is ambiguous.'
    }
    if ($owned.Count -eq 0) {
        if ($listeners.Count -gt 0) {
            throw 'Refusing Redis cleanup because the isolated port is owned by an unrelated process.'
        }
        return [ordered]@{ action = 'ALREADY_EXITED'; processId = $null }
    }
    if ($listeners.Count -eq 1 -and $listeners[0] -eq $owned[0]) {
        return [ordered]@{ action = 'SHUTDOWN_RUN_OWNED_LISTENER'; processId = $owned[0] }
    }
    return [ordered]@{ action = 'STOP_RUN_OWNED_PROCESS'; processId = $owned[0] }
}

function Get-T21PostgresStopAction([string]$statusKind) {
    switch -CaseSensitive ($statusKind) {
        'RUNNING' { return 'STOP_AND_VERIFY' }
        'STOPPED' { return 'ALREADY_STOPPED' }
        'UNKNOWN' { return 'ATTEMPT_BOUNDED_STOP_AND_FAIL' }
        default { throw "Unsupported run-owned PostgreSQL status '$statusKind'." }
    }
}
