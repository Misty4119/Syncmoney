[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ServerJar,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedServerSha256,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedCandidateSha256,
    [Parameter(Mandatory = $true)][string]$PlugDevRoot,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$MySqlBin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin',
    [string]$MariaDbBin = 'C:\Program Files\MariaDB 12.3\bin',
    [string]$PostgreSqlBin = 'C:\Program Files\PostgreSQL\18\bin',
    [ValidateSet('MySQL', 'MariaDB', 'PostgreSQL')][string]$DatabaseProduct = 'MySQL',
    [ValidateSet('Minimum', 'Maximum', 'CurrentStable')][string]$PaperEndpoint = 'Minimum',
    [string]$CandidateVersion = '1.4.0-alpha.1'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'T21 SQL runtime validation requires PowerShell 7 or newer.' }
. (Join-Path $PSScriptRoot 't21-upgrade-validation.ps1')

$requestedDatabaseProduct = $DatabaseProduct
$script:databaseProduct = Resolve-T21SqlRuntimeProduct $requestedDatabaseProduct
$script:databaseServerVersion = switch ($script:databaseProduct) { 'MariaDB' { '12.3.3-MariaDB' } 'PostgreSQL' { '18.1' } default { '8.0.42' } }
$script:databaseServerVersionPattern = switch ($script:databaseProduct) { 'MariaDB' { '^12\.3\.3-MariaDB(?:\b|$)' } 'PostgreSQL' { '^18\.1(?:\b|$)' } default { '^8\.0\.42(?:\b|$)' } }
$script:databaseExecutableVersionPattern = switch ($script:databaseProduct) { 'MariaDB' { '\bVer\s+12\.3\.3-MariaDB(?:\b|$)' } 'PostgreSQL' { '^postgres \(PostgreSQL\) 18\.1$' } default { '\bVer\s+8\.0\.42(?:\b|$)' } }
$script:paperTarget = Get-T21PaperEndpoint -PaperEndpoint $PaperEndpoint
$script:databaseBin = switch ($script:databaseProduct) { 'MariaDB' { $MariaDbBin } 'PostgreSQL' { $PostgreSqlBin } default { $MySqlBin } }
$script:root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$script:serverPath = (Resolve-Path -LiteralPath $ServerJar).Path
$script:candidatePath = Join-Path $script:root "build\libs\Syncmoney-$CandidateVersion.jar"
$script:acceptancePath = Join-Path $script:root 'build\acceptance\SyncmoneyAcceptance.jar'
$script:apiConsumerPath = Join-Path $script:root 'build\acceptance\SyncmoneyApiConsumer.jar'
$script:plugDevRoot = (Resolve-Path -LiteralPath $PlugDevRoot).Path
$script:javaHomePath = (Resolve-Path -LiteralPath $JavaHome).Path
$script:mysqlBinPath = (Resolve-Path -LiteralPath $script:databaseBin).Path
$script:node = (Get-Command node.exe, node -ErrorAction Stop | Select-Object -First 1).Source
$script:reportsRoot = [IO.Path]::GetFullPath((Join-Path $script:root 'build\reports\foundation\T21-sql-runtime'))
$script:runId = [guid]::NewGuid().ToString('N')
$script:runMarker = $script:databaseProduct.ToLowerInvariant() + '-sync-' + $script:runId
$script:databaseName = 'syncmoney_t21_' + $script:runId.Substring(0, 8)
$script:databaseUser = 'sm_t21_' + $script:runId.Substring(0, 8)
$script:databasePassword = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$script:runRoot = [IO.Path]::GetFullPath((Join-Path $script:reportsRoot ($script:databaseProduct.ToLowerInvariant() + '-sync-' + $script:paperTarget.taskSuffix.ToLowerInvariant() + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $script:runId)))
$script:workspace = Join-Path $script:runRoot 'workspace'
$script:gradleUserHome = Join-Path $script:runRoot '.gradle-user-home'
$script:mysqlRoot = Join-Path $script:runRoot $(if ($script:databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' })
$script:mysqlData = Join-Path $script:mysqlRoot 'data'
$script:redisRoot = Join-Path $script:runRoot 'redis'
$script:redisData = Join-Path $script:redisRoot 'data'
$script:logsRoot = Join-Path $script:runRoot 'logs'
$script:reportPath = Join-Path $script:runRoot 'result.json'
$script:mysqlServerName = switch ($script:databaseProduct) { 'MariaDB' { 'mariadbd.exe' } 'PostgreSQL' { 'postgres.exe' } default { 'mysqld.exe' } }
$script:mysqlServer = Join-Path $script:mysqlBinPath $script:mysqlServerName
$script:mysqlClient = Join-Path $script:mysqlBinPath $(if ($script:databaseProduct -ceq 'PostgreSQL') { 'psql.exe' } else { 'mysql.exe' })
$script:mysqlAdmin = Join-Path $script:mysqlBinPath $(if ($script:databaseProduct -ceq 'PostgreSQL') { 'pg_ctl.exe' } else { 'mysqladmin.exe' })
$script:mysqlInstallDb = if ($script:databaseProduct -ceq 'MariaDB') { Join-Path $script:mysqlBinPath 'mariadb-install-db.exe' } else { $null }
$script:postgresInitDb = if ($script:databaseProduct -ceq 'PostgreSQL') { Join-Path $script:mysqlBinPath 'initdb.exe' } else { $null }
$script:sqlReportKey = if ($script:databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
$script:redisServer = $null
$script:redisCli = $null
$script:redisProcess = $null
$script:mysqlProcess = $null
$script:mysqlLauncherProcess = $null
$script:postgresStartCommandExitCode = $null
$script:serverLogPath = Join-Path $script:workspace '.plugdev\run\logs\latest.log'
$script:gamePort = 0
$script:mysqlPort = 0
$script:redisPort = 0
$script:report = $null
$script:failure = $null
$script:cleanupFailures = [Collections.Generic.List[string]]::new()
$script:startups = [Collections.Generic.List[object]]::new()
$script:paperServerLogs = [Collections.Generic.List[object]]::new()
$script:normalStops = [Collections.Generic.List[object]]::new()
$script:snapshots = [Collections.Generic.List[object]]::new()
$script:backendShutdowns = [Collections.Generic.List[object]]::new()
$script:startedAtUtc = [DateTime]::UtcNow.ToString('o')

if ($ExpectedServerSha256.ToLowerInvariant() -cne $script:paperTarget.sha256) {
    throw "The $($script:paperTarget.taskSuffix) Paper runtime requires its exact user-pinned server SHA-256."
}
if ($CandidateVersion -cne '1.4.0-alpha.1') { throw 'This T21 runtime cell is pinned to candidate 1.4.0-alpha.1.' }

function Assert-NoReparsePointAncestors([string]$path) {
    $current = [IO.Path]::GetFullPath($path)
    while (-not [string]::IsNullOrWhiteSpace($current)) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Refusing a path whose ancestor is a reparse point: $current"
            }
        }
        $parent = Split-Path -Parent $current
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $current) { break }
        $current = $parent
    }
}

function Test-FreeLoopbackPort([int]$port) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $listener.Start(); return $true }
    catch { return $false }
    finally { $listener.Stop() }
}

function Get-FreeLoopbackPort([int]$minimum, [int]$maximum, [int[]]$reserved = @(), [int]$derivedOffset = 0) {
    for ($attempt = 0; $attempt -lt 300; $attempt++) {
        $port = Get-Random -Minimum $minimum -Maximum ($maximum + 1)
        if ($port -in $reserved -or -not (Test-FreeLoopbackPort $port)) { continue }
        if ($derivedOffset -gt 0 -and (($port + $derivedOffset) -in $reserved -or -not (Test-FreeLoopbackPort ($port + $derivedOffset)))) { continue }
        return $port
    }
    throw "Could not reserve an unused loopback port in $minimum-$maximum."
}

function Get-OfflineUuid([string]$name) {
    $bytes = [Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes('OfflinePlayer:' + $name))
    $bytes[6] = [byte](($bytes[6] -band 15) -bor 48)
    $bytes[8] = [byte](($bytes[8] -band 63) -bor 128)
    $hex = [Convert]::ToHexString($bytes).ToLowerInvariant()
    return ($hex.Substring(0, 8) + '-' + $hex.Substring(8, 4) + '-' + $hex.Substring(12, 4) + '-' + $hex.Substring(16, 4) + '-' + $hex.Substring(20, 12))
}

function Get-T21ServerProcesses {
    @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction Stop | Where-Object {
        $_.CommandLine -and $_.CommandLine.Contains("-Dsyncmoney.t21.database.run=$script:runMarker")
    })
}

function Wait-T21ServerStopped([int]$timeoutSeconds = 35) {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    do {
        if (@(Get-T21ServerProcesses).Count -eq 0) { return $true }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    return (@(Get-T21ServerProcesses).Count -eq 0)
}

function Get-ServerLogLineCount {
    if (Test-Path -LiteralPath $script:serverLogPath -PathType Leaf) { return @(Get-Content -LiteralPath $script:serverLogPath).Count }
    return 0
}

function Wait-ServerLogMarker([string]$pattern, [string]$failurePattern, [int]$afterLine, [int]$timeoutSeconds = 75) {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    do {
        if (-not (Test-Path -LiteralPath $script:serverLogPath -PathType Leaf)) {
            Start-Sleep -Milliseconds 250
            continue
        }
        $allLines = @(Get-Content -LiteralPath $script:serverLogPath -ErrorAction SilentlyContinue)
        $skip = if ($allLines.Count -ge $afterLine) { $afterLine } else { 0 }
        $lines = @($allLines | Select-Object -Skip $skip)
        if ($failurePattern) {
            $failureMatch = @($lines | Select-String -Pattern $failurePattern | Select-Object -First 1)
            if ($failureMatch.Count -gt 0) { throw "Server reported failure marker: $($failureMatch[0].Line)" }
        }
        $match = @($lines | Select-String -Pattern $pattern | Select-Object -Last 1)
        if ($match.Count -gt 0) { return $match[0].Line.Trim() }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for server log marker '$pattern'."
}

function Invoke-LoggedCommand([string]$executable, [string[]]$arguments, [string]$label, [string]$logPath, [switch]$allowExitOne) {
    $output = @(& $executable @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath $logPath -Encoding utf8
    if ($exitCode -ne 0 -and -not ($allowExitOne -and $exitCode -eq 1)) {
        throw "$label exited $exitCode; see $logPath."
    }
    return [pscustomobject]@{ exitCode = [int]$exitCode; output = ($output -join "`n").Trim(); logPath = $logPath }
}

function Invoke-PostgresStart([string]$executable, [string[]]$arguments, [string]$label, [string]$logPath, [int]$timeoutSeconds = 60) {
    $stdoutPath = $logPath + '.stdout.txt'
    $stderrPath = $logPath + '.stderr.txt'
    $argumentLine = ($arguments | ForEach-Object { '"' + ([string]$_).Replace('"', '\"') + '"' }) -join ' '
    $process = $null
    $processExited = $false
    $cleanupAttempted = $false
    $processExitedAfterCleanup = $false
    $exitCode = $null
    $startupError = $null
    $cleanupError = $null
    try {
        $process = Start-Process -FilePath $executable -ArgumentList $argumentLine -WorkingDirectory (Split-Path -Parent $executable) `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
        $processExited = $process.WaitForExit($timeoutSeconds * 1000)
        $process.Refresh()
        if (-not $processExited) {
            $cleanupAttempted = $true
            try {
                if (-not $process.HasExited) { $process.Kill() }
            } catch {
                $cleanupError = $_.Exception.Message
            }
            try {
                $processExitedAfterCleanup = $process.WaitForExit(10000)
                $process.Refresh()
                $processExitedAfterCleanup = $processExitedAfterCleanup -or $process.HasExited
            } catch {
                if ([string]::IsNullOrWhiteSpace($cleanupError)) { $cleanupError = $_.Exception.Message }
            }
        }
        if ($processExited -or $processExitedAfterCleanup) { $exitCode = [int]$process.ExitCode }
    } catch {
        $startupError = $_.Exception.Message
    }
    @(
        "processId=$(if ($null -ne $process) { $process.Id } else { '' })",
        "processExited=$([bool]$processExited)",
        "exitCode=$exitCode",
        "cleanupAttempted=$([bool]$cleanupAttempted)",
        "processExitedAfterCleanup=$([bool]$processExitedAfterCleanup)",
        "startupError=$startupError",
        "cleanupError=$cleanupError",
        "stdoutPath=$stdoutPath",
        "stderrPath=$stderrPath"
    ) | Set-Content -LiteralPath $logPath -Encoding utf8
    if ($null -ne $startupError) { throw "$label could not be launched: $startupError; see $logPath and its redirected output files." }
    if (-not $processExited) { throw "$label did not exit within $timeoutSeconds seconds; see $logPath and its redirected output files." }
    if ($exitCode -ne 0) { throw "$label exited $exitCode; see $logPath and its redirected output files." }
    return [pscustomobject]@{ exitCode = $exitCode; output = ''; logPath = $logPath; processId = [int]$process.Id; processExited = $true }
}

function Get-MySqlLoopbackClientArgs {
    if ($script:databaseProduct -ceq 'MariaDB') {
        # This runner binds the disposable database to loopback and needs clean, parseable client result rows.
        return @('--skip-ssl-verify-server-cert')
    }
    return @()
}

function Invoke-MySqlQuery([string]$sql, [string]$label, [switch]$asRoot) {
    $previousPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
    $logPath = Join-Path $script:logsRoot ($label + '.txt')
    try {
        if ($script:databaseProduct -ceq 'PostgreSQL') {
            return @(Invoke-PostgresQuery $sql $label -asRoot:$asRoot)
        }
        if ($asRoot) {
            $env:MYSQL_PWD = if ($script:databaseProduct -ceq 'MariaDB') { $script:databasePassword } else { '' }
        } else {
            $env:MYSQL_PWD = $script:databasePassword
        }
        $arguments = @('--no-defaults') + (Get-MySqlLoopbackClientArgs) + @('--protocol=TCP', '--host=127.0.0.1', "--port=$script:mysqlPort", "--user=$(if ($asRoot) { 'root' } else { $script:databaseUser })")
        if (-not $asRoot) { $arguments += "--database=$script:databaseName" }
        $arguments += @('--batch', '--skip-column-names', '--raw', "--execute=$sql")
        $result = Invoke-LoggedCommand $script:mysqlClient $arguments $label $logPath
        return @($result.output -split "`r?`n" | Where-Object { $_.Length -gt 0 })
    } finally {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previousPassword, 'Process')
    }
}

function Invoke-MySqlAdmin([string[]]$arguments, [string]$label, [switch]$allowExitOne) {
    $previousPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
    $logPath = Join-Path $script:logsRoot ($label + '.txt')
    try {
        $env:MYSQL_PWD = if ($script:databaseProduct -ceq 'MariaDB') { $script:databasePassword } else { '' }
        return Invoke-LoggedCommand $script:mysqlAdmin (@('--no-defaults') + (Get-MySqlLoopbackClientArgs) + @('--protocol=TCP', '--host=127.0.0.1', "--port=$script:mysqlPort", '--user=root') + $arguments) $label $logPath -allowExitOne:$allowExitOne
    } finally {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previousPassword, 'Process')
    }
}

function Invoke-PostgresQuery([string]$sql, [string]$label, [switch]$asRoot) {
    $previousPassword = [Environment]::GetEnvironmentVariable('PGPASSWORD', 'Process')
    $logPath = Join-Path $script:logsRoot ($label + '.txt')
    try {
        $env:PGPASSWORD = $script:databasePassword
        $user = if ($asRoot) { 'postgres' } else { $script:databaseUser }
        $database = if ($asRoot) { 'postgres' } else { $script:databaseName }
        $arguments = @('-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1', '-p', [string]$script:mysqlPort, '-U', $user, '-d', $database)
        $output = @($sql | & $script:mysqlClient @arguments 2>&1)
        $exitCode = $LASTEXITCODE
        $output | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath $logPath -Encoding utf8
        if ($exitCode -ne 0) { throw "PostgreSQL query $label exited $exitCode; see $logPath." }
        return @(($output | ForEach-Object { $_.ToString() } | Out-String) -split "`r?`n" | Where-Object { $_.Length -gt 0 })
    } finally {
        [Environment]::SetEnvironmentVariable('PGPASSWORD', $previousPassword, 'Process')
    }
}

function Assert-BackendListenerOwner([string]$name, [int]$port, [int]$processId) {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
    $owners = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
    $addresses = @($listeners | Select-Object -ExpandProperty LocalAddress -Unique)
    if ($listeners.Count -eq 0 -or $owners.Count -ne 1 -or [int]$owners[0] -ne $processId -or
        @($addresses | Where-Object { $_ -notin @('127.0.0.1', '::1') }).Count -gt 0) {
        $observedOwners = if ($owners.Count -eq 0) { '<none>' } else { $owners -join ',' }
        $observedAddresses = if ($addresses.Count -eq 0) { '<none>' } else { $addresses -join ',' }
        throw "$name listener on port $port failed ownership verification (expected PID $processId; owners $observedOwners; addresses $observedAddresses)."
    }
    return $true
}

function Get-MySqlListenerProcess {
    if ($script:databaseProduct -ceq 'PostgreSQL') { return Get-PostgresListenerProcess }
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $script:mysqlPort -ErrorAction SilentlyContinue)
    if ($listeners.Count -eq 0) { return $null }
    $owners = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
    $addresses = @($listeners | Select-Object -ExpandProperty LocalAddress -Unique)
    if ($owners.Count -ne 1 -or @($addresses | Where-Object { $_ -notin @('127.0.0.1', '::1') }).Count -gt 0) {
        $observedOwners = if ($owners.Count -eq 0) { '<none>' } else { $owners -join ',' }
        $observedAddresses = if ($addresses.Count -eq 0) { '<none>' } else { $addresses -join ',' }
        throw "$script:databaseProduct port $script:mysqlPort has ambiguous or non-loopback listeners (owners $observedOwners; addresses $observedAddresses)."
    }

    $ownerId = [int]$owners[0]
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $ownerId" -ErrorAction Stop
    if ($null -eq $processInfo) { throw "$script:databaseProduct listener PID $ownerId could not be inspected." }
    $expectedExecutable = [IO.Path]::GetFullPath($script:mysqlServer)
    $actualExecutable = if ([string]::IsNullOrWhiteSpace([string]$processInfo.ExecutablePath)) { '' } else { [IO.Path]::GetFullPath([string]$processInfo.ExecutablePath) }
    $commandLine = [string]$processInfo.CommandLine
    $hasRunDataDirectory = $commandLine.IndexOf($script:mysqlData, [StringComparison]::OrdinalIgnoreCase) -ge 0
    $hasRunPort = $commandLine.IndexOf("--port=$script:mysqlPort", [StringComparison]::OrdinalIgnoreCase) -ge 0
    if ($actualExecutable -ine $expectedExecutable -or -not $hasRunDataDirectory -or -not $hasRunPort) {
        throw "$script:databaseProduct listener PID $ownerId on port $script:mysqlPort does not match the run-owned executable, data directory, and port."
    }
    $process = Get-Process -Id $ownerId -ErrorAction Stop
    return [ordered]@{ process=$process; processInfo=$processInfo; addresses=@($addresses | Sort-Object) }
}

function Get-PostgresListenerProcess {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $script:mysqlPort -ErrorAction SilentlyContinue)
    if ($listeners.Count -eq 0) { return $null }
    $owners = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
    $addresses = @($listeners | Select-Object -ExpandProperty LocalAddress -Unique)
    if ($owners.Count -ne 1 -or @($addresses | Where-Object { $_ -notin @('127.0.0.1', '::1') }).Count -gt 0) {
        throw "PostgreSQL port $script:mysqlPort has ambiguous or non-loopback listeners."
    }

    $ownerId = [int]$owners[0]
    $ownedProcess = Get-PostgresPidFileProcess
    if ($null -eq $ownedProcess -or [int]$ownedProcess.process.Id -ne $ownerId -or
        -not (Test-T21PostgresListenerIdentity -ListenerOwners $owners -ListenerAddresses $addresses -ProcessId $ownerId `
            -ExpectedExecutablePath ([IO.Path]::GetFullPath($script:mysqlServer)) `
            -ActualExecutablePath ([IO.Path]::GetFullPath([string]$ownedProcess.processInfo.ExecutablePath)) `
            -CommandLine ([string]$ownedProcess.processInfo.CommandLine) -PostmasterPidLines $ownedProcess.pidLines `
            -ExpectedDataDirectory $script:mysqlData -ExpectedPort $script:mysqlPort `
            -ProcessStartTimeUtc $ownedProcess.process.StartTime.ToUniversalTime())) {
        throw "PostgreSQL listener PID $ownerId does not match the run-owned executable, data directory, and port."
    }
    return [ordered]@{ process=$ownedProcess.process; processInfo=$ownedProcess.processInfo; addresses=@($addresses | Sort-Object) }
}

function Get-PostgresPidFileProcess {
    $pidFile = Join-Path $script:mysqlData 'postmaster.pid'
    if (-not (Test-Path -LiteralPath $pidFile -PathType Leaf)) { return $null }
    $pidLines = @(Get-Content -LiteralPath $pidFile -TotalCount 4 -ErrorAction Stop)
    $pidFileProcessId = 0
    if ($pidLines.Count -lt 4 -or -not [int]::TryParse(([string]$pidLines[0]).Trim(), [ref]$pidFileProcessId) -or $pidFileProcessId -le 0) {
        throw 'PostgreSQL postmaster.pid does not contain a valid process ID and identity fields.'
    }

    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $pidFileProcessId" -ErrorAction Stop
    if ($null -eq $processInfo) { return $null }
    $process = Get-Process -Id $pidFileProcessId -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $null }
    $expectedExecutable = [IO.Path]::GetFullPath($script:mysqlServer)
    $actualExecutable = if ([string]::IsNullOrWhiteSpace([string]$processInfo.ExecutablePath)) { '' } else { [IO.Path]::GetFullPath([string]$processInfo.ExecutablePath) }
    if (-not (Test-T21PostgresProcessIdentity -ProcessId $pidFileProcessId `
        -ExpectedExecutablePath $expectedExecutable -ActualExecutablePath $actualExecutable `
        -CommandLine ([string]$processInfo.CommandLine) -PostmasterPidLines $pidLines `
        -ExpectedDataDirectory $script:mysqlData -ExpectedPort $script:mysqlPort `
        -ProcessStartTimeUtc $process.StartTime.ToUniversalTime())) {
        throw "PostgreSQL PID $pidFileProcessId does not match the run-owned executable, data directory, process start time, and port."
    }
    return [ordered]@{ process=$process; processInfo=$processInfo; pidLines=$pidLines }
}

function Wait-MySqlReady([int]$timeoutSeconds = 45) {
    if ($script:databaseProduct -ceq 'PostgreSQL') { return Wait-PostgresReady -timeoutSeconds $timeoutSeconds }
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    do {
    if ($null -eq $script:mysqlLauncherProcess) { throw "The isolated $script:databaseProduct launcher process was not created." }
        $listenerProcess = Get-MySqlListenerProcess
        if ($null -ne $listenerProcess) {
            $script:mysqlProcess = $listenerProcess.process
            $script:report.mysql.processId = [int]$script:mysqlProcess.Id
            $script:report.mysql.listenerAddresses = @($listenerProcess.addresses)
            $null = Assert-BackendListenerOwner $script:databaseProduct $script:mysqlPort $script:mysqlProcess.Id
            try {
                $ping = Invoke-MySqlAdmin @('ping') 'mysql-ping' -allowExitOne
                if ($ping.output -match 'mysqld is alive') { return }
            } catch {
                # The run-owned listener exists but can still be initializing; retry until ready.
            }
        } elseif ($script:mysqlLauncherProcess.HasExited) {
            $script:mysqlLauncherProcess.Refresh()
            throw "Isolated $script:databaseProduct launcher exited before a run-owned listener appeared (code $($script:mysqlLauncherProcess.ExitCode))."
        }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for isolated $script:databaseProduct readiness."
}

function Wait-PostgresReady([int]$timeoutSeconds = 45) {
    if ($script:postgresStartCommandExitCode -ne 0) { throw 'The isolated PostgreSQL start command did not exit successfully.' }
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    do {
        $listenerProcess = Get-PostgresListenerProcess
        if ($null -ne $listenerProcess) {
            $script:mysqlProcess = $listenerProcess.process
            $script:report.postgresql.processId = [int]$script:mysqlProcess.Id
            $script:report.postgresql.listenerAddresses = @($listenerProcess.addresses)
            $null = Assert-BackendListenerOwner 'PostgreSQL' $script:mysqlPort $script:mysqlProcess.Id
            try {
                $version = @(Invoke-PostgresQuery "SELECT current_setting('server_version');" 'postgresql-readiness' -asRoot)
                if ($version.Count -eq 1 -and $version[0].Trim() -ceq '18.1') { return }
            } catch {
                # The owned listener can exist before PostgreSQL accepts client sessions.
            }
        }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Timed out waiting for isolated PostgreSQL readiness.'
}

function Wait-RedisReady([int]$timeoutSeconds = 30) {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    do {
        if ($null -eq $script:redisProcess -or $script:redisProcess.HasExited) {
            throw 'Isolated Redis exited before readiness.'
        }
        try {
            $null = Assert-BackendListenerOwner 'Redis' $script:redisPort $script:redisProcess.Id
            $ping = Invoke-LoggedCommand $script:redisCli @('-h', '127.0.0.1', '-p', [string]$script:redisPort, 'PING') 'redis-ping' (Join-Path $script:logsRoot 'redis-ping.txt')
            if ($ping.output -ceq 'PONG') { return }
        } catch {
            if ($_.Exception.Message -match 'not uniquely owned') { throw }
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Timed out waiting for isolated Redis readiness.'
}

function Initialize-T21ProjectWorkspace([string]$projectRoot) {
    New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'tools\plugdev'),
        (Join-Path $projectRoot 'src\main\resources'), (Join-Path $projectRoot 'build\libs'),
        (Join-Path $projectRoot 'build\acceptance'), (Join-Path $projectRoot 'gradle') -ErrorAction Stop | Out-Null
    foreach ($name in @('build.gradle', 'settings.gradle', 'gradlew.bat')) {
        Copy-Item -LiteralPath (Join-Path $script:root $name) -Destination (Join-Path $projectRoot $name)
    }
    Copy-Item -LiteralPath (Join-Path $script:root 'gradle\wrapper') -Destination (Join-Path $projectRoot 'gradle') -Recurse
    $settingsText = Get-Content -LiteralPath (Join-Path $projectRoot 'settings.gradle') -Raw
    foreach ($include in [regex]::Matches($settingsText, '(?m)^\s*include\s*\(([^)]*)\)')) {
        foreach ($projectMatch in [regex]::Matches($include.Groups[1].Value, '["'']([^"'']+)["'']')) {
            $relative = $projectMatch.Groups[1].Value.Replace(':', '\')
            $targetDirectory = [IO.Path]::GetFullPath((Join-Path $projectRoot $relative))
            if (-not $targetDirectory.StartsWith(($projectRoot.TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase)) {
                throw "Gradle include escapes the isolated workspace: $relative"
            }
            $sourceBuild = Join-Path (Join-Path $script:root $relative) 'build.gradle'
            if (-not (Test-Path -LiteralPath $sourceBuild -PathType Leaf)) { throw "Missing included Gradle build file: $sourceBuild" }
            New-Item -ItemType Directory -Force -Path $targetDirectory | Out-Null
            Copy-Item -LiteralPath $sourceBuild -Destination (Join-Path $targetDirectory 'build.gradle')
        }
    }
    Copy-Item -LiteralPath (Join-Path $script:root 'tools\plugdev\prepare.mjs') -Destination (Join-Path $projectRoot 'tools\plugdev\prepare.mjs')
    foreach ($name in @('config.yml', 'plugin.yml')) {
        Copy-Item -LiteralPath (Join-Path $script:root "src\main\resources\$name") -Destination (Join-Path $projectRoot "src\main\resources\$name")
    }
    Copy-Item -LiteralPath $script:candidatePath -Destination (Join-Path $projectRoot "build\libs\Syncmoney-$CandidateVersion.jar")
    Copy-Item -LiteralPath $script:acceptancePath -Destination (Join-Path $projectRoot 'build\acceptance\SyncmoneyAcceptance.jar')
    Copy-Item -LiteralPath $script:apiConsumerPath -Destination (Join-Path $projectRoot 'build\acceptance\SyncmoneyApiConsumer.jar')
}

function Invoke-PlugDev([string[]]$arguments, [string]$label) {
    $logPath = Join-Path $script:logsRoot ($label + '.txt')
    Push-Location $script:workspace
    try {
        return Invoke-LoggedCommand $script:node (@((Join-Path $script:plugDevRoot 'packages\cli\dist\cli.js')) + $arguments) $label $logPath
    } finally {
        Pop-Location
    }
}

function Set-T21RunMarkerInProfile {
    $editor = @'
const fs = require("node:fs");
const path = require("node:path");
const { createRequire } = require("node:module");
const yaml = createRequire(path.join(process.env.PLUGDEV_ROOT, "package.json"))("yaml");
const file = path.resolve(".plugdev/profile.yml");
const profile = yaml.parse(fs.readFileSync(file, "utf8"));
profile.jvm.args = [
  `-Dsyncmoney.acceptance.version=${process.env.SYNCMONEY_CANDIDATE_VERSION}`,
  `-Dsyncmoney.t21.database.run=${process.env.T21_RUN_MARKER}`,
];
fs.writeFileSync(file, yaml.stringify(profile), "utf8");
'@
    $editorPath = Join-Path $script:runRoot 'set-run-marker.cjs'
    Set-Content -LiteralPath $editorPath -Value $editor -Encoding utf8
    Push-Location $script:workspace
    try {
        $result = Invoke-LoggedCommand $script:node @($editorPath) 'set-run-marker' (Join-Path $script:logsRoot 'set-run-marker.txt')
    } finally { Pop-Location }
    if ($result.exitCode -ne 0) { throw 'Could not set the unique run marker in the isolated PlugDev profile.' }
}

function Assert-IsolatedSyncConfig {
    $scriptPath = Join-Path $script:runRoot 'audit-config.cjs'
    $content = @'
const fs=require("node:fs"); const path=require("node:path"); const {createRequire}=require("node:module");
const yaml=createRequire(path.join(process.env.PLUGDEV_ROOT,"package.json"))("yaml");
const p=process.argv[2], c=yaml.parse(fs.readFileSync(p,"utf8"));
const expected={dbType:process.env.SYNCMONEY_TEST_DB_TYPE,dbPort:Number(process.env.SYNCMONEY_TEST_DB_PORT),redisPort:Number(process.env.SYNCMONEY_TEST_REDIS_PORT),dbName:process.env.SYNCMONEY_TEST_DB_NAME,dbUser:process.env.SYNCMONEY_TEST_DB_USER,dbPassword:process.env.SYNCMONEY_TEST_DB_PASSWORD};
const ok=c["config-version"]===12&&c["db-enabled"]===true&&c["pubsub-enabled"]===true&&c.economy.mode==="sync"&&
  c.database.enabled===true&&c.database.type===expected.dbType&&c.database.host==="127.0.0.1"&&Number(c.database.port)===expected.dbPort&&
  c.database.database===expected.dbName&&c.database.username===expected.dbUser&&c.database.password===expected.dbPassword&&
  c.redis.enabled===true&&c.redis.host==="127.0.0.1"&&Number(c.redis.port)===expected.redisPort&&c.redis.database===15;
if(!ok) throw new Error("The isolated Paper profile does not match the required SQL/Redis SYNC configuration.");
console.log(JSON.stringify({schema:c["config-version"],mode:c.economy.mode,databaseType:c.database.type,databaseHost:c.database.host,databasePort:c.database.port,databaseName:c.database.database,redisHost:c.redis.host,redisPort:c.redis.port,redisDatabase:c.redis.database,credentialsConfigured:Boolean(c.database.username&&c.database.password)}));
'@
    Set-Content -LiteralPath $scriptPath -Value $content -Encoding utf8
    Push-Location $script:workspace
    try {
        $result = Invoke-LoggedCommand $script:node @($scriptPath, (Join-Path $script:workspace '.plugdev\run\plugins\Syncmoney\config.yml')) 'config-audit' (Join-Path $script:logsRoot 'config-audit.json')
    } finally { Pop-Location }
    if ($result.exitCode -ne 0) { throw 'Isolated Syncmoney config did not match the SQL and Redis runtime pins.' }
    return ($result.output | ConvertFrom-Json)
}

function Get-PinnedCandidatePlugin {
    $plugins = Join-Path $script:workspace '.plugdev\run\plugins'
    $observed = @(Get-ChildItem -LiteralPath $plugins -Filter '*.jar' -File | ForEach-Object {
        [ordered]@{ name=$_.Name; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(); path=$_.FullName }
    })
    $matching = @($observed | Where-Object { $_.sha256 -ceq $script:candidateSha256 })
    if ($matching.Count -ne 1) { throw "Expected exactly one deployed candidate JAR by SHA-256; observed $($matching.Count)." }
    return $matching[0]
}

function Start-T21Server([string]$stage) {
    if (@(Get-T21ServerProcesses).Count -ne 0) { throw 'A run-marked server process exists before startup.' }
    $before = Get-ServerLogLineCount
    $start = Invoke-PlugDev @('server', 'start', '--config', '.plugdev/profile.yml') ($stage + '-start')
    if ($start.exitCode -ne 0) { throw "PlugDev server start failed at $stage." }
    $null = Wait-ServerLogMarker 'Syncmoney enabled successfully' 'Error occurred while enabling Syncmoney|Could not load plugin|T21_\w+_FAIL' $before
    $null = Wait-ServerLogMarker 'API CLASSLOADER PASS provider=Syncmoney' 'API CLASSLOADER FAIL|Error occurred while enabling Syncmoney|T21_\w+_FAIL' $before
    $fullLog = Get-Content -LiteralPath $script:serverLogPath -Raw
    $identity = Get-T21PlatformIdentity $fullLog
    Assert-T21PlatformIdentity $identity $script:paperTarget.software $script:paperTarget.minecraftVersion $script:paperTarget.build
    if ($fullLog -notmatch '(?m)\[Syncmoney\] Loading server plugin Syncmoney v1\.4\.0-alpha\.1') {
        throw 'The minimum Paper server did not load the expected candidate version.'
    }
    $pluginPin = Get-PinnedCandidatePlugin
    $processes = @(Get-T21ServerProcesses)
    if ($processes.Count -ne 1) { throw "Expected one run-owned Java server after $stage; observed $($processes.Count)." }
    $observation = [ordered]@{
        stage = $stage
        enabled = $true
        pluginVersion = '1.4.0-alpha.1'
        artifactSha256 = $pluginPin.sha256
        artifactName = $pluginPin.name
        processId = [int]$processes[0].ProcessId
        runtimeIdentity = $identity
        startupLogMarker = 'Syncmoney enabled successfully'
        apiConsumerMarker = 'API CLASSLOADER PASS provider=Syncmoney'
    }
    $script:startups.Add($observation)
    return $observation
}

function Stop-T21Server([string]$stage) {
    $before = Get-ServerLogLineCount
    $owned = @(Get-T21ServerProcesses)
    if ($owned.Count -ne 1) { throw "Refusing graceful RCON stop at $stage; expected one run-owned Paper process, observed $($owned.Count)." }
    $sessionPath = Join-Path $script:workspace '.plugdev\session.json'
    if (-not (Test-Path -LiteralPath $sessionPath -PathType Leaf)) { throw "PlugDev session is absent before normal stop at $stage." }
    $session = Get-Content -LiteralPath $sessionPath -Raw | ConvertFrom-Json
    if ([int]$session.pid -ne [int]$owned[0].ProcessId -or [int]$session.gamePort -ne $script:gamePort) {
        throw "Refusing graceful RCON stop at $stage because the PlugDev session does not match the run-owned server and game port."
    }
    $serverProcess = Get-Process -Id ([int]$owned[0].ProcessId) -ErrorAction Stop
    $stop = Invoke-PlugDev @('server', 'command', 'stop') ($stage + '-stop')
    if ($stop.exitCode -ne 0) { throw "PlugDev RCON stop command failed at $stage with exit code $($stop.exitCode)." }
    $processExited = Wait-T21ServerStopped
    if (-not $processExited) { throw "Run-owned server did not exit after $stage." }
    $serverProcess.Refresh()
    if (-not $serverProcess.HasExited) { throw "Captured run-owned Paper PID $($serverProcess.Id) remained active after $stage." }
    $processExitCode = [int]$serverProcess.ExitCode
    if ($processExitCode -ne 0) { throw "Run-owned Paper exited with code $processExitCode after $stage." }
    $allLines = @(Get-Content -LiteralPath $script:serverLogPath -ErrorAction Stop)
    $skip = if ($allLines.Count -ge $before) { $before } else { 0 }
    $stopLines = @($allLines | Select-Object -Skip $skip)
    $disabled = @($stopLines | Select-String -Pattern 'Syncmoney disabled\.' | Select-Object -Last 1)
    if ($disabled.Count -eq 0) { throw "Syncmoney did not log its normal disable marker for $stage." }
    $failureMarkers = @(Get-T21ShutdownFailureMarkers ($stopLines -join "`n"))
    if ($failureMarkers.Count -gt 0) { throw "Server shutdown failure marker observed at ${stage}: $($failureMarkers -join '; ')." }
    $paperLogStage = switch ($stage) {
        'after-write' { 'candidate-write' }
        'after-restart-read' { 'candidate-restart-read' }
        default { throw "Unexpected Paper log snapshot stage: $stage" }
    }
    $paperLogRelativePath = 'logs\paper-' + $paperLogStage + '.txt'
    $paperLogPath = Join-Path $script:runRoot $paperLogRelativePath
    Copy-Item -LiteralPath $script:serverLogPath -Destination $paperLogPath -Force -ErrorAction Stop
    $paperLogFile = Get-Item -LiteralPath $paperLogPath -ErrorAction Stop
    $script:paperServerLogs.Add([ordered]@{
        stage = $paperLogStage
        path = $paperLogRelativePath
        bytes = [long]$paperLogFile.Length
        sha256 = (Get-FileHash -LiteralPath $paperLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    })
    Remove-Item -LiteralPath $sessionPath -Force -ErrorAction Stop
    $script:normalStops.Add([ordered]@{
        stage = $stage
        commandExitCode = [int]$stop.exitCode
        processId = [int]$serverProcess.Id
        processExitCode = $processExitCode
        processExitCodeObserved = $true
        processExited = $processExited
        disabledMarker = $true
    })
}

function Invoke-Redis([string[]]$arguments, [string]$label, [switch]$allowExitOne) {
    $logPath = Join-Path $script:logsRoot ($label + '.txt')
    $null = Assert-BackendListenerOwner 'Redis' $script:redisPort $script:redisProcess.Id
    return Invoke-LoggedCommand $script:redisCli (@('-h', '127.0.0.1', '-p', [string]$script:redisPort) + $arguments) $label $logPath -allowExitOne:$allowExitOne
}

function Get-BackendSnapshot([string]$stage) {
    $uuidA = Get-OfflineUuid 'AcceptanceA'
    $uuidB = Get-OfflineUuid 'AcceptanceB'
    $sql = if ($script:databaseProduct -ceq 'PostgreSQL') {
        "SELECT player_name || '|' || player_uuid::text || '|' || balance::text || '|' || version::text FROM players WHERE player_uuid IN ('$uuidA','$uuidB') ORDER BY player_name;"
    } else {
        "SELECT CONCAT_WS('|', player_name, player_uuid, CAST(balance AS CHAR), CAST(version AS CHAR)) FROM players WHERE player_uuid IN ('$uuidA','$uuidB') ORDER BY player_name;"
    }
    $rows = @(Invoke-MySqlQuery $sql ($stage + '-' + $script:databaseProduct.ToLowerInvariant() + '-snapshot'))
    if ($rows.Count -ne 2) { throw "Expected exactly two synthetic $script:databaseProduct player rows after $stage; observed $($rows.Count)." }
    $sqlAccounts = [Collections.Generic.List[object]]::new()
    foreach ($row in $rows) {
        $parts = $row.Split('|')
        if ($parts.Count -ne 4 -or $parts[0] -notin @('AcceptanceA', 'AcceptanceB')) { throw "Unexpected MySQL account row after $stage." }
        $expectedUuid = if ($parts[0] -ceq 'AcceptanceA') { $uuidA } else { $uuidB }
        if ($parts[1].ToLowerInvariant() -cne $expectedUuid) { throw "MySQL UUID mismatch for $($parts[0]) after $stage." }
        $balance = ([decimal]::Parse($parts[2], [Globalization.CultureInfo]::InvariantCulture)).ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
        $version = [long]::Parse($parts[3], [Globalization.CultureInfo]::InvariantCulture)
        if ($version -lt 1) { throw "$script:databaseProduct returned a non-positive version for $($parts[0]) after $stage." }
        $sqlAccounts.Add([ordered]@{ name=$parts[0]; uuid=$expectedUuid; balance=$balance; version=$version })
    }
    $redisAccounts = [Collections.Generic.List[object]]::new()
    foreach ($account in @([ordered]@{ name='AcceptanceA'; uuid=$uuidA }, [ordered]@{ name='AcceptanceB'; uuid=$uuidB })) {
        $balanceResult = Invoke-Redis @('-n', '15', 'GET', ('syncmoney:balance:' + $account.uuid)) ($stage + '-redis-' + $account.name + '-balance')
        $versionResult = Invoke-Redis @('-n', '15', 'GET', ('syncmoney:version:' + $account.uuid)) ($stage + '-redis-' + $account.name + '-version')
        $balanceRaw = $balanceResult.output.Trim()
        $versionRaw = $versionResult.output.Trim()
        if ([string]::IsNullOrWhiteSpace($balanceRaw) -or $balanceRaw -ceq '(nil)' -or $versionRaw -notmatch '^[1-9]\d*$') {
            throw "Redis balance/version is missing for $($account.name) after $stage."
        }
        $balance = ([decimal]::Parse($balanceRaw, [Globalization.CultureInfo]::InvariantCulture)).ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
        $redisAccounts.Add([ordered]@{ name=$account.name; uuid=$account.uuid; balance=$balance; version=[long]$versionRaw })
    }
    if (-not (Test-T21MySqlRuntimeAccountParity @($sqlAccounts) @($redisAccounts))) {
        throw "$script:databaseProduct and Redis disagree for the synthetic accounts after $stage."
    }
    return [ordered]@{ stage=$stage; sqlAccounts=@($sqlAccounts); redisAccounts=@($redisAccounts) }
}

function Stop-RunOwnedServerAfterFailure {
    $owned = @(Get-T21ServerProcesses)
    foreach ($process in $owned) {
        try {
            Stop-Process -Id ([int]$process.ProcessId) -Force -ErrorAction Stop
            $script:cleanupFailures.Add("Forced cleanup of run-owned Paper PID $($process.ProcessId) after failure.")
        } catch {
            if (Get-Process -Id ([int]$process.ProcessId) -ErrorAction SilentlyContinue) {
                $script:cleanupFailures.Add("Could not stop run-owned Paper PID $($process.ProcessId): $($_.Exception.Message)")
            }
        }
    }
    if (@(Get-T21ServerProcesses).Count -gt 0) { $script:cleanupFailures.Add('A run-owned Paper process remained after failure cleanup.') }
}

function Stop-MySqlBackend {
    if ($script:databaseProduct -ceq 'PostgreSQL') { return Stop-PostgresBackend }
    if ($null -eq $script:mysqlLauncherProcess) { return }
    $process = $script:mysqlProcess
    $launcher = $script:mysqlLauncherProcess
    $commandExitCode = $null
    $forced = $false
    $shutdownTargetVerified = $false
    if ($null -ne $process -and -not $process.HasExited) {
        try {
            $listenerProcess = Get-MySqlListenerProcess
            if ($null -eq $listenerProcess -or [int]$listenerProcess.process.Id -ne [int]$process.Id) {
                throw "The listener no longer resolves to the captured $script:databaseProduct server process."
            }
            $null = Assert-BackendListenerOwner $script:databaseProduct $script:mysqlPort $process.Id
            $shutdownTargetVerified = $true
        } catch {
            $script:cleanupFailures.Add("Refused mysqladmin shutdown because the run-owned listener could not be verified: $($_.Exception.Message)")
        }
    }
    if ($shutdownTargetVerified -and -not $process.HasExited) {
        try {
            $result = Invoke-MySqlAdmin @('shutdown') 'mysql-shutdown'
            $commandExitCode = [int]$result.exitCode
        } catch {
            $script:cleanupFailures.Add("mysqladmin shutdown failed: $($_.Exception.Message)")
        }
    }
    if ($null -ne $process -and -not $process.HasExited) {
        try { $process.WaitForExit(30000) | Out-Null } catch { }
        $process.Refresh()
        if (-not $process.HasExited -and $shutdownTargetVerified) {
            $forced = $true
            try { $process.Kill(); $process.WaitForExit(10000) | Out-Null }
            catch { $script:cleanupFailures.Add("Could not stop verified run-owned $script:databaseProduct PID $($process.Id): $($_.Exception.Message)") }
        }
    }

    if ($null -ne $launcher -and -not $launcher.HasExited -and ($null -eq $process -or $launcher.Id -ne $process.Id)) {
        try { $launcher.WaitForExit(30000) | Out-Null } catch { }
        $launcher.Refresh()
        if (-not $launcher.HasExited) {
            $forced = $true
            try { $launcher.Kill(); $launcher.WaitForExit(10000) | Out-Null }
            catch { $script:cleanupFailures.Add("Could not stop run-created $script:databaseProduct launcher PID $($launcher.Id): $($_.Exception.Message)") }
        }
    }

    if ($null -ne $process) { $process.Refresh() }
    $processExited = $null -ne $process -and $process.HasExited
    $processExitCode = if ($processExited) { [int]$process.ExitCode } else { -1 }
    if ($null -ne $launcher) { $launcher.Refresh() }
    $launcherExited = $null -ne $launcher -and $launcher.HasExited
    $launcherExitCode = if ($launcherExited) { [int]$launcher.ExitCode } else { -1 }
    if ($forced -or -not $shutdownTargetVerified -or -not $processExited -or -not $launcherExited -or
        $commandExitCode -ne 0 -or $processExitCode -ne 0 -or $launcherExitCode -ne 0) {
        $script:cleanupFailures.Add("$script:databaseProduct shutdown was not clean (command=$commandExitCode process=$processExitCode launcher=$launcherExitCode targetVerified=$shutdownTargetVerified forced=$forced).")
    }
    $script:backendShutdowns.Add([ordered]@{
        backend='mysql'; command='mysqladmin shutdown'; commandExitCode=$commandExitCode
        processId=if ($null -eq $process) { 0 } else { [int]$process.Id }
        shutdownTargetVerified=$shutdownTargetVerified
        processExitCode=$processExitCode; processExitCodeObserved=$processExited; processExited=$processExited
        launchProcessId=[int]$launcher.Id; launchProcessExitCode=$launcherExitCode
        launchProcessExitCodeObserved=$launcherExited; launchProcessExited=$launcherExited; forced=$forced
    })
}

function Stop-PostgresBackend {
    $process = $script:mysqlProcess
    $commandExitCode = $null
    $forced = $false
    $shutdownTargetVerified = $false
    if ($null -eq $process) {
        try {
            $listener = Get-PostgresListenerProcess
            if ($null -ne $listener) {
                $process = $listener.process
            } else {
                # pg_ctl start can time out after the postmaster has written its PID file
                # but before it has opened the configured listener.
                $pidFileProcess = Get-PostgresPidFileProcess
                if ($null -ne $pidFileProcess) { $process = $pidFileProcess.process }
            }
            if ($null -ne $process) { $script:mysqlProcess = $process }
        } catch {
            $script:cleanupFailures.Add("Could not identify the run-owned PostgreSQL process: $($_.Exception.Message)")
        }
    }
    if ($null -eq $process) {
        if (Test-Path -LiteralPath (Join-Path $script:mysqlData 'postmaster.pid') -PathType Leaf) {
            $script:cleanupFailures.Add('PostgreSQL postmaster.pid remains but the listener process could not be verified.')
        }
        return
    }
    $process.Refresh()
    if (-not $process.HasExited) {
        $listenerVerificationError = $null
        try {
            $listener = Get-PostgresListenerProcess
            if ($null -eq $listener -or [int]$listener.process.Id -ne [int]$process.Id) {
                throw 'The listener no longer resolves to the captured PostgreSQL process.'
            }
            $null = Assert-BackendListenerOwner 'PostgreSQL' $script:mysqlPort $process.Id
            $shutdownTargetVerified = $true
        } catch {
            $listenerVerificationError = $_.Exception.Message
        }
        if (-not $shutdownTargetVerified) {
            try {
                $pidFileProcess = Get-PostgresPidFileProcess
                if ($null -eq $pidFileProcess -or [int]$pidFileProcess.process.Id -ne [int]$process.Id) {
                    throw 'The run-owned postmaster.pid no longer resolves to the captured PostgreSQL process.'
                }
                $shutdownTargetVerified = $true
            } catch {
                $script:cleanupFailures.Add("Refused pg_ctl stop because neither the listener nor postmaster.pid identity could be verified: listener=$listenerVerificationError; pidFile=$($_.Exception.Message)")
            }
        }
    } else {
        $shutdownTargetVerified = $true
    }
    if ($shutdownTargetVerified -and -not $process.HasExited) {
        try {
            $result = Invoke-LoggedCommand $script:mysqlAdmin @('stop', '-D', $script:mysqlData, '-m', 'fast', '-w', '-t', '30') 'postgresql-shutdown' (Join-Path $script:logsRoot 'postgresql-shutdown.txt')
            $commandExitCode = [int]$result.exitCode
        } catch {
            $script:cleanupFailures.Add("PostgreSQL pg_ctl stop failed: $($_.Exception.Message)")
        }
        try { $process.WaitForExit(30000) | Out-Null } catch { }
        $process.Refresh()
        if (-not $process.HasExited -and $shutdownTargetVerified) {
            $forced = $true
            try { $process.Kill(); $process.WaitForExit(10000) | Out-Null }
            catch { $script:cleanupFailures.Add("Could not stop verified run-owned PostgreSQL PID $($process.Id): $($_.Exception.Message)") }
        }
    }
    $process.Refresh()
    $processExited = $process.HasExited
    $processExitCode = if ($processExited) { [int]$process.ExitCode } else { -1 }
    $listenerAfter = @(Get-NetTCPConnection -State Listen -LocalPort $script:mysqlPort -ErrorAction SilentlyContinue)
    if ($forced -or -not $shutdownTargetVerified -or $commandExitCode -ne 0 -or -not $processExited -or $processExitCode -ne 0 -or $listenerAfter.Count -ne 0) {
        $script:cleanupFailures.Add("PostgreSQL shutdown was not clean (command=$commandExitCode process=$processExitCode listenerCount=$($listenerAfter.Count) targetVerified=$shutdownTargetVerified forced=$forced).")
    }
    $script:backendShutdowns.Add([ordered]@{
        backend='postgresql'; command='pg_ctl stop -m fast -w'; commandExitCode=$commandExitCode
        processId=[int]$process.Id; shutdownTargetVerified=$shutdownTargetVerified
        processExitCode=$processExitCode; processExitCodeObserved=$processExited; processExited=$processExited; forced=$forced
    })
}

function Stop-RedisBackend {
    if ($null -eq $script:redisProcess) { return }
    $process = $script:redisProcess
    $commandExitCode = $null
    $forced = $false
    if (-not $process.HasExited) {
        try {
            $result = Invoke-Redis @('SHUTDOWN', 'SAVE') 'redis-shutdown' -allowExitOne
            $commandExitCode = [int]$result.exitCode
        } catch {
            $script:cleanupFailures.Add("Redis SHUTDOWN SAVE failed: $($_.Exception.Message)")
        }
        try { $process.WaitForExit(20000) | Out-Null } catch { }
        $process.Refresh()
        if (-not $process.HasExited) {
            $forced = $true
            try { $process.Kill(); $process.WaitForExit(10000) | Out-Null }
            catch { $script:cleanupFailures.Add("Could not stop run-owned Redis PID $($process.Id): $($_.Exception.Message)") }
        }
    }
    $process.Refresh()
    $exited = $process.HasExited
    $exitCode = if ($exited) { [int]$process.ExitCode } else { -1 }
    if ($forced -or -not $exited -or $commandExitCode -notin @(0, 1) -or $exitCode -ne 0) {
        $script:cleanupFailures.Add("Redis shutdown was not clean (command=$commandExitCode process=$exitCode forced=$forced).")
    }
    $script:backendShutdowns.Add([ordered]@{
        backend='redis'; command='SHUTDOWN SAVE'; commandExitCode=$commandExitCode
        processExitCode=$exitCode; processExitCodeObserved=$exited; processExited=$exited; forced=$forced
    })
}

function Get-RunOwnedGradleDaemons {
    $isolatedGradleHome = [IO.Path]::GetFullPath($script:gradleUserHome)
    @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop | Where-Object {
        $_.Name -in @('java.exe', 'javaw.exe') -and $_.CommandLine -and
        $_.CommandLine.IndexOf($isolatedGradleHome, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $_.CommandLine -match 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon'
    })
}

function Stop-T21RunOwnedGradleDaemons {
    $before = @(Get-RunOwnedGradleDaemons)
    $beforeIds = @($before | ForEach-Object { [int]$_.ProcessId })
    $stopCommandExitCode = $null
    $stopLogPath = $null
    if ($before.Count -gt 0) {
        $wrapper = Join-Path $script:workspace 'gradlew.bat'
        if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) { throw 'The run-owned Gradle wrapper is unavailable for isolated daemon shutdown.' }
        if ([IO.Path]::GetFullPath($env:GRADLE_USER_HOME) -cne [IO.Path]::GetFullPath($script:gradleUserHome)) {
            throw 'Refusing Gradle --stop because GRADLE_USER_HOME does not match this run-owned daemon home.'
        }
        $stopLogPath = 'logs\gradle-daemon-stop.txt'
        $stopLog = Join-Path $script:runRoot $stopLogPath
        Push-Location $script:workspace
        try {
            $null = & $wrapper --stop *> $stopLog
            $stopCommandExitCode = $LASTEXITCODE
        } finally { Pop-Location }
        if ($stopCommandExitCode -ne 0) { $script:cleanupFailures.Add("Isolated Gradle --stop exited $stopCommandExitCode.") }
        $deadline = [DateTime]::UtcNow.AddSeconds(20)
        do {
            $after = @(Get-RunOwnedGradleDaemons)
            if ($after.Count -eq 0) { break }
            Start-Sleep -Milliseconds 250
        } while ([DateTime]::UtcNow -lt $deadline)
    }
    $after = @(Get-RunOwnedGradleDaemons)
    $afterIds = @($after | ForEach-Object { [int]$_.ProcessId })
    $processExitVerified = $after.Count -eq 0
    if (-not $processExitVerified) { $script:cleanupFailures.Add('A run-owned isolated Gradle daemon remained after --stop.') }
    return [ordered]@{
        isolatedUserHome=[IO.Path]::GetFullPath($script:gradleUserHome)
        processIdsBeforeStop=$beforeIds
        stopCommandExitCode=$stopCommandExitCode
        stopLogPath=$stopLogPath
        processIdsAfterStop=$afterIds
        processExitVerified=$processExitVerified
    }
}

function Restore-TestEnvironment {
    foreach ($name in $script:environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $script:originalEnvironment[$name], 'Process')
    }
}

$script:environmentNames = @(
    'JAVA_HOME', 'Path', 'PLUGDEV_ROOT', 'PLUGDEV_HOME', 'GRADLE_USER_HOME', 'SYNCMONEY_TEST_SERVER_JAR',
    'SYNCMONEY_CANDIDATE_VERSION', 'SYNCMONEY_TEST_GAME_PORT', 'SYNCMONEY_TEST_DB_TYPE',
    'SYNCMONEY_TEST_DB_PORT', 'SYNCMONEY_TEST_DB_USER', 'SYNCMONEY_TEST_DB_PASSWORD',
    'SYNCMONEY_TEST_DB_NAME', 'SYNCMONEY_TEST_REDIS_PORT', 'T21_RUN_MARKER', 'MYSQL_PWD', 'PGPASSWORD'
)
$script:originalEnvironment = [ordered]@{}
foreach ($name in $script:environmentNames) { $script:originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

$serverSha256 = (Get-FileHash -LiteralPath $script:serverPath -Algorithm SHA256).Hash.ToLowerInvariant()
$script:candidateSha256 = (Get-FileHash -LiteralPath $script:candidatePath -Algorithm SHA256).Hash.ToLowerInvariant()
$acceptanceSha256 = (Get-FileHash -LiteralPath $script:acceptancePath -Algorithm SHA256).Hash.ToLowerInvariant()
$apiConsumerSha256 = (Get-FileHash -LiteralPath $script:apiConsumerPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($serverSha256 -cne $script:paperTarget.sha256 -or $serverSha256 -cne $ExpectedServerSha256.ToLowerInvariant()) { throw "Paper server JAR does not match the pinned $($script:paperTarget.taskSuffix) build." }
if ($script:candidateSha256 -cne $ExpectedCandidateSha256.ToLowerInvariant()) { throw 'Candidate JAR SHA-256 does not match the required pin.' }
if (-not (Test-Path -LiteralPath (Join-Path $script:plugDevRoot 'packages\cli\dist\cli.js') -PathType Leaf) -or
    -not (Test-Path -LiteralPath (Join-Path $script:plugDevRoot 'package.json') -PathType Leaf)) { throw 'The local PlugDev source checkout or built CLI is unavailable.' }
foreach ($path in @($script:candidatePath, $script:acceptancePath, $script:apiConsumerPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required acceptance artifact is missing: $path" }
}

$java = Join-Path $script:javaHomePath 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'Java 25 executable was not found.' }
$javaBanner = (& $java -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaBanner -notmatch 'version "25(?:\.|"|\+)') { throw 'The runtime cell requires Java 25.' }
$javaFeature = 25
$mysqlVersionOutput = (& $script:mysqlServer --version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $mysqlVersionOutput -notmatch $script:databaseExecutableVersionPattern) {
    throw "The runtime cell requires $script:databaseProduct $script:databaseServerVersion."
}
foreach ($path in @($script:mysqlServer, $script:mysqlClient, $script:mysqlAdmin)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "$script:databaseProduct $script:databaseServerVersion tool is missing: $path" }
}
if ($null -ne $script:mysqlInstallDb -and -not (Test-Path -LiteralPath $script:mysqlInstallDb -PathType Leaf)) {
    throw "MariaDB $script:databaseServerVersion initializer is missing: $script:mysqlInstallDb"
}
$redisServerCommand = Get-Command redis-server.exe, redis-server -ErrorAction SilentlyContinue | Select-Object -First 1
$redisCliCommand = Get-Command redis-cli.exe, redis-cli -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $redisServerCommand -or $null -eq $redisCliCommand) { throw 'The local Redis server and CLI are required.' }
$script:redisServer = $redisServerCommand.Source
$script:redisCli = $redisCliCommand.Source
foreach ($kind in @('redisServer', 'redisCli')) {
    $value = [string](Get-Variable -Name $kind -Scope Script).Value
    if ($value -match '(?i)\\scoop\\shims\\(redis-server|redis-cli)\.exe$') {
        $exeName = if ($kind -eq 'redisServer') { 'redis-server.exe' } else { 'redis-cli.exe' }
        $realPath = Join-Path (Split-Path -Parent (Split-Path -Parent $value)) (Join-Path 'apps\redis\current' $exeName)
        if (Test-Path -LiteralPath $realPath -PathType Leaf) { Set-Variable -Name $kind -Scope Script -Value (Resolve-Path -LiteralPath $realPath).Path }
    }
}
$redisVersionOutput = (& $script:redisServer '--version' 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $redisVersionOutput -notmatch 'v=8\.10\.1') { throw 'The runtime cell requires local Redis 8.10.1.' }

Assert-NoReparsePointAncestors $script:root
Assert-NoReparsePointAncestors $script:reportsRoot
New-Item -ItemType Directory -Force -Path $script:logsRoot, $script:mysqlData, $script:redisData | Out-Null
Assert-NoReparsePointAncestors $script:runRoot
$script:gamePort = Get-FreeLoopbackPort 20000 30000 @() 10000
$script:mysqlPort = Get-FreeLoopbackPort 33001 43000 @($script:gamePort, ($script:gamePort + 10000))
$script:redisPort = Get-FreeLoopbackPort 15000 25000 @($script:gamePort, ($script:gamePort + 10000), $script:mysqlPort)
$script:accounts = [ordered]@{ AcceptanceA = Get-OfflineUuid 'AcceptanceA'; AcceptanceB = Get-OfflineUuid 'AcceptanceB' }

$script:report = [ordered]@{
    schemaVersion = 3
    task = "T21-M05-SYNC-$script:databaseProduct-$($script:paperTarget.taskSuffix)"
    status = 'IN_PROGRESS'
    startedAtUtc = $script:startedAtUtc
    runId = $script:runId
    runDirectory = $script:runRoot
    server = [ordered]@{ software=$script:paperTarget.software; minecraftVersion=$script:paperTarget.minecraftVersion; build=$script:paperTarget.build; sha256=$serverSha256; bytes=[long](Get-Item -LiteralPath $script:serverPath).Length; path=$script:serverPath }
    candidate = [ordered]@{ version=$CandidateVersion; sha256=$script:candidateSha256; path=$script:candidatePath }
    acceptance = [ordered]@{ sha256=$acceptanceSha256 }
    apiConsumer = [ordered]@{ sha256=$apiConsumerSha256 }
    java = [ordered]@{ feature=$javaFeature; banner=$javaBanner.Trim() }
    plugDev = [ordered]@{ root=$script:plugDevRoot; sourceCommit=(& git -C $script:plugDevRoot rev-parse HEAD 2>$null | Out-String).Trim(); cliSha256=(Get-FileHash -LiteralPath (Join-Path $script:plugDevRoot 'packages\cli\dist\cli.js') -Algorithm SHA256).Hash.ToLowerInvariant() }
    configuration = [ordered]@{
        schema=12; mode='sync'; databaseEnabled=$true; databaseType=$(if ($script:databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }); databaseIsolated=$true
        databaseHost='127.0.0.1'; databasePort=$script:mysqlPort; databaseName=$script:databaseName
        redisEnabled=$true; redisDatabase=15; redisIsolated=$true; redisHost='127.0.0.1'; redisPort=$script:redisPort
    }
    mysql = [ordered]@{ product=$script:databaseProduct; version=$script:databaseServerVersion; serverVersion=$null; executableVersion=$mysqlVersionOutput; bin=$script:mysqlBinPath; dataDirectory=$script:mysqlData; processId=$null; launchProcessId=$null; listenerAddresses=@() }
    redis = [ordered]@{ version='8.10.1'; serverVersion=$redisVersionOutput; executable=$script:redisServer; dataDirectory=$script:redisData; processId=$null }
    backendProcesses = $null
    startupObservations = @()
    writeReceipts = @()
    restartReadMarker = $null
    restartReadObservation = $null
    normalStops = @()
    snapshots = @()
    backendShutdowns = @()
    gradleDaemonCleanup = $null
    cleanupFailures = @()
    failure = $null
    logs = [ordered]@{}
}
if ($script:databaseProduct -ceq 'PostgreSQL') {
    $script:report.Remove('mysql')
    $script:report.postgresql = [ordered]@{
        product='PostgreSQL'; version=$script:databaseServerVersion; serverVersion=$null; executableVersion=$mysqlVersionOutput
        bin=$script:mysqlBinPath; dataDirectory=$script:mysqlData; processId=$null; listenerAddresses=@()
        startCommand='pg_ctl start -w'; startCommandExitCode=$null; startCommandProcessExited=$false
    }
}

try {
    $env:JAVA_HOME = $script:javaHomePath
    $env:Path = "$($script:javaHomePath)\bin;$($script:mysqlBinPath);$env:Path"
    $env:PLUGDEV_ROOT = $script:plugDevRoot
    $env:PLUGDEV_HOME = Join-Path $script:runRoot '.plugdev-home'
    $env:GRADLE_USER_HOME = $script:gradleUserHome
    $env:SYNCMONEY_TEST_SERVER_JAR = $script:serverPath
    $env:SYNCMONEY_CANDIDATE_VERSION = $CandidateVersion
    $env:SYNCMONEY_TEST_GAME_PORT = [string]$script:gamePort
    $env:SYNCMONEY_TEST_DB_TYPE = if ($script:databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
    $env:SYNCMONEY_TEST_DB_PORT = [string]$script:mysqlPort
    $env:SYNCMONEY_TEST_DB_USER = $script:databaseUser
    $env:SYNCMONEY_TEST_DB_PASSWORD = $script:databasePassword
    $env:SYNCMONEY_TEST_DB_NAME = $script:databaseName
    $env:SYNCMONEY_TEST_REDIS_PORT = [string]$script:redisPort
    $env:T21_RUN_MARKER = $script:runMarker

    Initialize-T21ProjectWorkspace $script:workspace
    New-Item -ItemType Directory -Force -Path (Join-Path $script:runRoot '.plugdev-home'), (Join-Path $script:runRoot '.gradle-user-home') | Out-Null

    if ($script:databaseProduct -ceq 'PostgreSQL') {
        $postgresInitLog = Join-Path $script:logsRoot 'postgresql-initialize.stdout.txt'
        $postgresPasswordFile = Join-Path $script:mysqlRoot 'postgres-superuser-password.txt'
        [IO.File]::WriteAllText($postgresPasswordFile, $script:databasePassword + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
        try {
            $initResult = Invoke-LoggedCommand $script:postgresInitDb @('-D', $script:mysqlData, '-U', 'postgres', '--auth-host=scram-sha-256', '--auth-local=scram-sha-256', '--pwfile', $postgresPasswordFile, '--encoding=UTF8') 'postgresql-initialize' $postgresInitLog
        } finally {
            if (Test-Path -LiteralPath $postgresPasswordFile -PathType Leaf) { Remove-Item -LiteralPath $postgresPasswordFile -Force }
        }
        if ($initResult.exitCode -ne 0) { throw "PostgreSQL 18.1 isolated data-directory initialization failed with exit code $($initResult.exitCode); see $postgresInitLog." }

        $postgresServerLog = Join-Path $script:logsRoot 'postgresql-server.log'
        $postgresOptions = "-h 127.0.0.1 -p $script:mysqlPort -c listen_addresses=127.0.0.1"
        $postgresStart = Invoke-PostgresStart $script:mysqlAdmin @('start', '-D', $script:mysqlData, '-l', $postgresServerLog, '-o', $postgresOptions, '-w', '-t', '45') 'postgresql-start' (Join-Path $script:logsRoot 'postgresql-start.txt')
        $script:postgresStartCommandExitCode = [int]$postgresStart.exitCode
        $script:report.postgresql.startCommandExitCode = [int]$postgresStart.exitCode
        $script:report.postgresql.startCommandProcessExited = [bool]$postgresStart.processExited
        if ($postgresStart.exitCode -ne 0) { throw "PostgreSQL pg_ctl start exited $($postgresStart.exitCode); see $postgresStart.logPath." }
        Wait-PostgresReady

        $mysqlVersion = @(Invoke-PostgresQuery "SELECT current_setting('server_version');" 'postgresql-server-version' -asRoot)
        if ($mysqlVersion.Count -ne 1 -or $mysqlVersion[0].Trim() -cne $script:databaseServerVersion) {
            throw "Isolated PostgreSQL server returned unexpected version '$($mysqlVersion -join ',')'."
        }
        $script:report.postgresql.serverVersion = $mysqlVersion[0].Trim()
        $null = Invoke-PostgresQuery "CREATE ROLE $script:databaseUser LOGIN PASSWORD '$script:databasePassword';" 'postgresql-create-isolated-role' -asRoot
        $null = Invoke-PostgresQuery "CREATE DATABASE $script:databaseName OWNER $script:databaseUser;" 'postgresql-create-isolated-database' -asRoot
    } else {
    $mysqlInitLog = Join-Path $script:logsRoot ($script:databaseProduct.ToLowerInvariant() + '-initialize.stdout.txt')
    $mysqlHome = Split-Path -Parent $script:mysqlBinPath
    if ($script:databaseProduct -ceq 'MariaDB') {
        $mysqlInitArgs = @("--datadir=$script:mysqlData", "--password=$script:databasePassword", '--allow-remote-root-access', '--silent')
        $initOutput = @(& $script:mysqlInstallDb @mysqlInitArgs 2>&1)
        $initExitCode = $LASTEXITCODE
        $initOutput | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath $mysqlInitLog -Encoding utf8
        if ($initExitCode -ne 0) { throw "MariaDB isolated data-directory initialization failed with exit code $initExitCode; see $mysqlInitLog." }
    } else {
        $mysqlInitArgs = @('--no-defaults', '--initialize-insecure', "--basedir=$mysqlHome", "--datadir=$script:mysqlData")
        $initOutput = @(& $script:mysqlServer @mysqlInitArgs 2>&1)
        $initExitCode = $LASTEXITCODE
        $initOutput | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath $mysqlInitLog -Encoding utf8
        if ($initExitCode -ne 0) { throw "MySQL isolated data-directory initialization failed with exit code $initExitCode; see $mysqlInitLog." }
    }

    $mysqlErrorLog = Join-Path $script:logsRoot ($script:databaseProduct.ToLowerInvariant() + '-error.log')
    $mysqlPidFile = Join-Path $script:mysqlRoot ($script:databaseProduct.ToLowerInvariant() + '.pid')
    $mysqlArgs = @('--no-defaults', "--basedir=$mysqlHome", "--datadir=$script:mysqlData", "--port=$script:mysqlPort", '--bind-address=127.0.0.1', "--log-error=$mysqlErrorLog", "--pid-file=$mysqlPidFile", '--console')
    if ($script:databaseProduct -ceq 'MySQL') { $mysqlArgs += '--mysqlx=OFF' }
    $mysqlArgumentLine = ($mysqlArgs | ForEach-Object { '"' + ([string]$_).Replace('"', '\"') + '"' }) -join ' '
    $script:mysqlLauncherProcess = Start-Process -FilePath $script:mysqlServer -ArgumentList $mysqlArgumentLine -WorkingDirectory $mysqlHome -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $script:logsRoot ($script:databaseProduct.ToLowerInvariant() + '-stdout.log')) -RedirectStandardError (Join-Path $script:logsRoot ($script:databaseProduct.ToLowerInvariant() + '-stderr.log'))
    $script:report.mysql.launchProcessId = [int]$script:mysqlLauncherProcess.Id
    Wait-MySqlReady
    $mysqlVersion = @(Invoke-MySqlQuery 'SELECT VERSION();' 'mysql-server-version' -asRoot)
    if ($mysqlVersion.Count -ne 1 -or $mysqlVersion[0] -notmatch $script:databaseServerVersionPattern) {
        throw "Isolated $script:databaseProduct server returned unexpected version '$($mysqlVersion -join ',')'."
    }
    $script:report.mysql.serverVersion = $mysqlVersion[0]

    $collation = if ($script:databaseProduct -ceq 'MariaDB') { 'utf8mb4_general_ci' } else { 'utf8mb4_0900_ai_ci' }
    $sql = "CREATE DATABASE $($script:databaseName) CHARACTER SET utf8mb4 COLLATE $collation; CREATE USER '$($script:databaseUser)'@'127.0.0.1' IDENTIFIED BY '$($script:databasePassword)'; CREATE USER '$($script:databaseUser)'@'localhost' IDENTIFIED BY '$($script:databasePassword)'; GRANT ALL PRIVILEGES ON $($script:databaseName).* TO '$($script:databaseUser)'@'127.0.0.1'; GRANT ALL PRIVILEGES ON $($script:databaseName).* TO '$($script:databaseUser)'@'localhost'; FLUSH PRIVILEGES;"
    $null = Invoke-MySqlQuery $sql 'mysql-create-isolated-schema' -asRoot
    }

    $redisConfig = Join-Path $script:redisRoot 'redis.conf'
    $redisLines = @(
        'bind 127.0.0.1', "port $script:redisPort", 'protected-mode yes', 'daemonize no',
        'dir ./', 'appendonly yes', 'appendfsync always', 'save ""', 'databases 16',
        'appendfilename appendonly.aof', 'appenddirname appendonlydir', 'logfile redis.log'
    )
    [IO.File]::WriteAllLines($redisConfig, [string[]]$redisLines, [Text.UTF8Encoding]::new($false))
    $script:redisProcess = Start-Process -FilePath $script:redisServer -ArgumentList 'redis.conf' -WorkingDirectory $script:redisRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $script:logsRoot 'redis-stdout.log') -RedirectStandardError (Join-Path $script:logsRoot 'redis-stderr.log')
    $script:report.redis.processId = [int]$script:redisProcess.Id
    Wait-RedisReady
    $redisInfo = Invoke-Redis @('-n', '15', 'INFO', 'server') 'redis-server-info'
    if ($redisInfo.output -notmatch '(?m)^redis_version:8\.10\.1$') { throw 'Isolated Redis server did not report version 8.10.1.' }
    if (((Invoke-Redis @('-n', '15', 'DBSIZE') 'redis-initial-dbsize').output.Trim()) -ne '0') { throw 'Isolated Redis DB 15 was not empty before the runtime cycle.' }

    $script:report.backendProcesses = [ordered]@{}
    if ($script:databaseProduct -ceq 'PostgreSQL') {
        $script:report.backendProcesses.postgresql = [ordered]@{
            product='PostgreSQL'; version=$mysqlVersion[0].Trim(); processId=[int]$script:mysqlProcess.Id; runOwned=$true
            listenerOwnerVerified=(Assert-BackendListenerOwner 'PostgreSQL' $script:mysqlPort $script:mysqlProcess.Id)
            listenerAddresses=@($script:report.postgresql.listenerAddresses)
        }
    } else {
        $script:report.backendProcesses.mysql = [ordered]@{
            product=$script:databaseProduct; version=$mysqlVersion[0]; processId=[int]$script:mysqlProcess.Id
            launchProcessId=[int]$script:mysqlLauncherProcess.Id; runOwned=$true
            listenerOwnerVerified=(Assert-BackendListenerOwner $script:databaseProduct $script:mysqlPort $script:mysqlProcess.Id)
            listenerAddresses=@($script:report.mysql.listenerAddresses)
        }
    }
    $script:report.backendProcesses.redis = [ordered]@{ version='8.10.1'; processId=[int]$script:redisProcess.Id; runOwned=$true; listenerOwnerVerified=(Assert-BackendListenerOwner 'Redis' $script:redisPort $script:redisProcess.Id) }

    Push-Location $script:workspace
    try {
        $prepareOutput = @(& $script:node (Join-Path $script:workspace 'tools\plugdev\prepare.mjs') $script:paperTarget.software $script:paperTarget.minecraftVersion 'sync' 'off' $CandidateVersion 2>&1)
        $prepareExit = $LASTEXITCODE
        $prepareOutput | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath (Join-Path $script:logsRoot 'prepare-profile.txt') -Encoding utf8
        if ($prepareExit -ne 0) { throw "Could not prepare the isolated $($script:paperTarget.taskSuffix) $script:databaseProduct SYNC profile." }
    } finally { Pop-Location }
    Set-T21RunMarkerInProfile
    $configObservation = Assert-IsolatedSyncConfig
    $script:report.configuration += [ordered]@{
        configSchema=[int]$configObservation.schema
        observedMode=[string]$configObservation.mode
        databaseNameObserved=[string]$configObservation.databaseName
        configCredentialsConfigured=[bool]$configObservation.credentialsConfigured
    }

    Start-T21Server 'candidate-write'
    $versionLineStart = Get-ServerLogLineCount
    $null = Invoke-PlugDev @('server', 'command', 'smaccept version') 'candidate-version-command'
    $null = Wait-ServerLogMarker 'T21_VERSION_PASS expected=1\.4\.0-alpha\.1 actual=1\.4\.0-alpha\.1' 'T21_VERSION_FAIL|ACCEPTANCE VERSION FAIL' $versionLineStart

    $writeLineStart = Get-ServerLogLineCount
    $null = Invoke-PlugDev @('server', 'command', 'smaccept write 1337.25 765.50') 'candidate-vault-write-command'
    $writeLine = Wait-ServerLogMarker 'T21_WRITE_PASS expected-a=1337\.25 expected-b=765\.50 receipt-a=SUCCESS:DEPOSIT:1337\.25:1337\.25 receipt-b=SUCCESS:DEPOSIT:765\.50:765\.50' 'T21_WRITE_FAIL|ACCEPTANCE FAIL' $writeLineStart
    $script:report.writeReceipts = @('SUCCESS:DEPOSIT:1337.25:1337.25', 'SUCCESS:DEPOSIT:765.50:765.50')
    $script:report.writeMarker = $writeLine

    Stop-T21Server 'after-write'
    $script:snapshots.Add((Get-BackendSnapshot 'after-write-stop'))

    Start-T21Server 'candidate-restart-read'
    $readLineStart = Get-ServerLogLineCount
    $null = Invoke-PlugDev @('server', 'command', 'smaccept read 1337.25 765.50') 'candidate-restart-read-command'
    $readObservation = Wait-ServerLogMarker 'ACCEPTANCE READ cold-vault-a=NaN cold-vault-unlocked-b=null a=1337\.25 b=765\.50 vault-unlocked-b=765\.50' 'T21_READ_FAIL|ACCEPTANCE FAIL' $readLineStart
    $readLine = Wait-ServerLogMarker 'T21_READ_PASS expected-a=1337\.25 expected-b=765\.50' 'T21_READ_FAIL|ACCEPTANCE FAIL' $readLineStart
    $script:report.restartReadMarker = $readLine
    $script:report.restartReadObservation = $readObservation
    Stop-T21Server 'after-restart-read'
    $script:snapshots.Add((Get-BackendSnapshot 'after-restart-read-stop'))

} catch {
    $script:failure = $_.Exception.ToString()
    [Console]::Error.WriteLine("T21 MySQL runtime case failed: $($script:failure)")
} finally {
    try {
        if (@(Get-T21ServerProcesses).Count -gt 0) { Stop-RunOwnedServerAfterFailure }
    } catch { $script:cleanupFailures.Add("Paper cleanup check failed: $($_.Exception.Message)") }
    try { Stop-MySqlBackend } catch { $script:cleanupFailures.Add("MySQL cleanup failed: $($_.Exception.Message)") }
    try { Stop-RedisBackend } catch { $script:cleanupFailures.Add("Redis cleanup failed: $($_.Exception.Message)") }
    try { $script:report.gradleDaemonCleanup = Stop-T21RunOwnedGradleDaemons } catch {
        $script:cleanupFailures.Add("Isolated Gradle daemon cleanup failed: $($_.Exception.Message)")
        $script:report.gradleDaemonCleanup = [ordered]@{
            isolatedUserHome=[IO.Path]::GetFullPath($script:gradleUserHome)
            processIdsBeforeStop=@()
            stopCommandExitCode=$null
            stopLogPath=$null
            processIdsAfterStop=@()
            processExitVerified=$false
        }
    }
    try { Restore-TestEnvironment } catch { $script:cleanupFailures.Add("Process environment restore failed: $($_.Exception.Message)") }
    try {
        $script:report.logs = [ordered]@{}
        foreach ($log in @(Get-ChildItem -LiteralPath $script:logsRoot -File -Recurse)) {
            $relative = $log.FullName.Substring($script:runRoot.Length).TrimStart('\')
            $script:report.logs[$relative] = [ordered]@{ bytes=[long]$log.Length; sha256=(Get-FileHash -LiteralPath $log.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
        }
        $script:report.plugDev.sourceCommit = (& git -C $script:plugDevRoot rev-parse HEAD 2>$null | Out-String).Trim()
        $script:report.runner = [ordered]@{
            path='tools/plugdev/run-mysql-sync-runtime.ps1'
            sha256=(Get-FileHash -LiteralPath (Join-Path $script:root 'tools\plugdev\run-mysql-sync-runtime.ps1') -Algorithm SHA256).Hash.ToLowerInvariant()
            validatorSha256=(Get-FileHash -LiteralPath (Join-Path $script:root 'tools\plugdev\t21-upgrade-validation.ps1') -Algorithm SHA256).Hash.ToLowerInvariant()
            testSha256=(Get-FileHash -LiteralPath (Join-Path $script:root 'tools\plugdev\t21-upgrade-validation.test.ps1') -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    } catch {
        $script:failure = "Could not finalize runtime evidence hashes: $($_.Exception.Message)"
        $script:cleanupFailures.Add($script:failure)
    }

    $script:report.finishedAtUtc = [DateTime]::UtcNow.ToString('o')
    $script:report.startupObservations = @($script:startups)
    $script:report.paperLogs = @($script:paperServerLogs)
    $script:report.normalStops = @($script:normalStops)
    $script:report.snapshots = @($script:snapshots)
    $script:report.backendShutdowns = @($script:backendShutdowns)
    $script:report.cleanupFailures = @($script:cleanupFailures)
    $script:report.failure = $script:failure
    if ($null -eq $script:failure -and $script:cleanupFailures.Count -eq 0) {
        $script:report.status = 'PASS_BOUNDED_SUBCASE'
        $reportAccepted = if ($script:databaseProduct -ceq 'PostgreSQL') {
            Test-T21PostgresRuntimeReport $script:report $ExpectedServerSha256 $ExpectedCandidateSha256 -PaperEndpoint $PaperEndpoint
        } else {
            Test-T21MySqlRuntimeReport $script:report $ExpectedServerSha256 $ExpectedCandidateSha256 -DatabaseProduct $script:databaseProduct -PaperEndpoint $PaperEndpoint
        }
        if (-not $reportAccepted) {
            $script:report.status = 'FAIL'
            $script:failure = 'The completed runtime evidence did not satisfy Test-T21MySqlRuntimeReport.'
        }
    } else {
        $script:report.status = 'FAIL'
    }
    $script:report.failure = $script:failure
    try {
        $script:report | ConvertTo-Json -Depth 24 | Set-Content -LiteralPath $script:reportPath -Encoding utf8
    } catch {
        $script:failure = "Could not persist the machine report: $($_.Exception.Message)"
        [Console]::Error.WriteLine($script:failure)
    }
}

if ($null -ne $script:failure -or $script:report.status -cne 'PASS_BOUNDED_SUBCASE') {
    throw "T21 $script:databaseProduct SYNC runtime did not pass. Report: $script:reportPath"
}
$databaseRuntimeLabel = switch ($script:databaseProduct) { 'MariaDB' { 'MariaDB 12.3.3 using the MySQL protocol' } 'PostgreSQL' { 'PostgreSQL 18.1' } default { 'MySQL 8.0.42' } }
Write-Output "PASS_BOUNDED_SUBCASE: Paper $($script:paperTarget.minecraftVersion)#$($script:paperTarget.build) SYNC / $databaseRuntimeLabel / Redis 8.10.1; report=$script:reportPath"
