param(
    [Parameter(Mandatory = $true)][string]$ServerJar,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedServerSha256,
    [Parameter(Mandatory = $true)][ValidateSet('paper', 'folia', 'canvas')][string]$PlatformSoftware,
    [Parameter(Mandatory = $true)][ValidatePattern('^\d+\.\d+(?:\.\d+)?$')][string]$MinecraftVersion,
    [Parameter(Mandatory = $true)][ValidateRange(1, 100000)][int]$PlatformBuildNumber,
    [Parameter(Mandatory = $true)][string]$BaselinePluginJar,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedBaselinePluginSha256,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$BaselineSourceCommit,
    [Parameter(Mandatory = $true)][string]$PlugDevRoot,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\18\bin',
    [string]$CandidateVersion = '1.4.0-alpha.1',
    [string[]]$ServerNames = @('t21-sync-single')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'T21 SYNC upgrade validation requires PowerShell 7 or newer.' }
. (Join-Path $PSScriptRoot 'run-sync-upgrade-cleanup.ps1')
. (Join-Path $PSScriptRoot 't21-upgrade-validation.ps1')

$environmentNames = @(
    'JAVA_HOME', 'Path', 'PLUGDEV_ROOT', 'PLUGDEV_HOME', 'GRADLE_USER_HOME',
    'SYNCMONEY_TEST_SERVER_JAR', 'SYNCMONEY_CANDIDATE_VERSION', 'SYNCMONEY_TEST_GAME_PORT',
    'SYNCMONEY_TEST_DB_TYPE', 'SYNCMONEY_TEST_DB_PORT', 'SYNCMONEY_TEST_DB_USER',
    'SYNCMONEY_TEST_DB_PASSWORD', 'SYNCMONEY_TEST_DB_NAME', 'SYNCMONEY_TEST_REDIS_PORT',
    'T21_RUN_MARKER', 'T21_PLUGIN_JAR_PATTERN', 'T21_PLUGIN_VERSION', 'T21_CANDIDATE_SHA256',
    'T21_BASELINE_SHA256', 'T21_SERVER_NAME', 'PGSSLMODE'
)
$script:originalEnvironment = [ordered]@{}
foreach ($name in $environmentNames) {
    $script:originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Assert-NoReparsePointAncestors([string]$path) {
    $current = [IO.Path]::GetFullPath($path)
    while (-not [string]::IsNullOrWhiteSpace($current)) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Refusing a path with a reparse-point ancestor: $current"
            }
        }
        $parent = Split-Path -Parent $current
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $current) { break }
        $current = $parent
    }
}

function Write-RunnerProgress([string]$message) {
    if ($script:progressPath) {
        $line = (Get-Date).ToUniversalTime().ToString('o') + ' ' + $message
        Add-Content -LiteralPath $script:progressPath -Value $line -Encoding utf8
    }
}

function Test-FreeLoopbackPort([int]$port) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try {
        $listener.Start()
        return $true
    } catch {
        return $false
    } finally {
        $listener.Stop()
    }
}

function Get-FreePort([int]$minimum, [int]$maximum, [int[]]$reserved = @(), [int]$derivedOffset = 0) {
    for ($attempt = 0; $attempt -lt 160; $attempt++) {
        $port = Get-Random -Minimum $minimum -Maximum ($maximum + 1)
        if ($port -in $reserved -or -not (Test-FreeLoopbackPort $port)) { continue }
        if ($derivedOffset -gt 0 -and ($port + $derivedOffset) -in $reserved) { continue }
        if ($derivedOffset -gt 0 -and -not (Test-FreeLoopbackPort ($port + $derivedOffset))) { continue }
        return $port
    }
    throw "Could not reserve a free loopback port between $minimum and $maximum."
}

function Invoke-Logged([string]$executable, [string[]]$arguments, [string]$logPath, [string]$operation) {
    Write-RunnerProgress "start: $operation"
    $output = @(& $executable @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath $logPath -Encoding utf8
    if ($exitCode -ne 0) { throw "$operation exited $exitCode; see $logPath." }
    Write-RunnerProgress "complete: $operation"
    return [ordered]@{ exitCode = $exitCode; log = $logPath }
}

function Invoke-PgCtl([string[]]$arguments, [string]$operation, [string]$logPrefix) {
    Write-RunnerProgress "start: $operation"
    $stdoutPath = Join-Path $script:logsRoot ($logPrefix + '.stdout.txt')
    $stderrPath = Join-Path $script:logsRoot ($logPrefix + '.stderr.txt')
    $argumentLine = ($arguments | ForEach-Object { '"' + ([string]$_).Replace('"', '\"') + '"' }) -join ' '
    $process = Start-Process -FilePath $script:pgCtl -ArgumentList $argumentLine -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    if (-not $process.WaitForExit(45000)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw "$operation did not return within 45 seconds. See $stdoutPath and $stderrPath."
    }
    $process.Refresh()
    if ($process.ExitCode -ne 0) { throw "$operation exited $($process.ExitCode). See $stdoutPath and $stderrPath." }
    Write-RunnerProgress "complete: $operation"
}

function Get-OfflineUuid([string]$name) {
    $bytes = [Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes('OfflinePlayer:' + $name))
    $bytes[6] = [byte](($bytes[6] -band 15) -bor 48)
    $bytes[8] = [byte](($bytes[8] -band 63) -bor 128)
    $hex = [Convert]::ToHexString($bytes).ToLowerInvariant()
    return ($hex.Substring(0, 8) + '-' + $hex.Substring(8, 4) + '-' + $hex.Substring(12, 4) + '-' +
        $hex.Substring(16, 4) + '-' + $hex.Substring(20, 12))
}

function Invoke-Psql([string]$sql) {
    $arguments = @(
        '--no-password', '--no-psqlrc', '--host=127.0.0.1', "--port=$script:pgPort",
        "--username=$script:pgUser", "--dbname=$script:dbName", '--no-align',
        '--tuples-only', '--field-separator=|', '--set=ON_ERROR_STOP=1', "--command=$sql"
    )
    $output = @(& $script:psql @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw "Isolated PostgreSQL query exited $exitCode; see $script:logsRoot." }
    return @($output | ForEach-Object { $_.ToString().TrimEnd() } | Where-Object { $_ -ne '' })
}

function Invoke-Redis([string[]]$arguments) {
    $allArguments = @('-h', '127.0.0.1', '-p', [string]$script:redisPort) + $arguments
    $output = @(& $script:redisCli @allArguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw "Isolated Redis command exited $exitCode." }
    return @($output | ForEach-Object { $_.ToString().TrimEnd() })
}

function Assert-IsolatedRedisOwnership {
    if ($null -eq $script:redisProcess -or $script:redisProcess.HasExited) {
        throw 'The tracked isolated Redis process is not alive.'
    }
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $script:redisPort -ErrorAction Stop |
        Where-Object { $_.LocalAddress -eq '127.0.0.1' })
    if ($listeners.Count -ne 1 -or [int]$listeners[0].OwningProcess -ne [int]$script:redisProcess.Id) {
        throw 'The configured Redis loopback listener is not owned by the tracked process.'
    }
    $info = ((Invoke-Redis @('INFO', 'server')) -join "`n").Replace("`r", '')
    $configMatch = [regex]::Match($info, '(?m)^config_file:(.+)$')
    $portMatch = [regex]::Match($info, '(?m)^tcp_port:(\d+)$')
    $expectedConfig = '/cygdrive/' + $script:redisConfig.Substring(0, 1).ToLowerInvariant() +
        $script:redisConfig.Substring(2).Replace('\', '/')
    if (-not $configMatch.Success -or $configMatch.Groups[1].Value -cne $expectedConfig -or
        -not $portMatch.Success -or [int]$portMatch.Groups[1].Value -ne $script:redisPort) {
        $observedConfig = if ($configMatch.Success) { $configMatch.Groups[1].Value } else { '<missing>' }
        $observedPort = if ($portMatch.Success) { $portMatch.Groups[1].Value } else { '<missing>' }
        throw "Redis INFO identity mismatch: expected config '$expectedConfig' / port $script:redisPort; observed config '$observedConfig' / port '$observedPort'."
    }
}

function Get-RedisDatabaseSize([int]$database) {
    $raw = ((Invoke-Redis @('-n', [string]$database, 'DBSIZE')) -join [Environment]::NewLine).Trim()
    Write-RunnerProgress "redis db=$database size response: '$raw'"
    $size = 0
    if (-not [int]::TryParse($raw, [Globalization.NumberStyles]::Integer,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$size)) {
        throw "Redis DB $database returned a non-integer DBSIZE response: '$raw'."
    }
    return $size
}

function Get-BackendSnapshot([string]$stage) {
    $uuidA = $script:accounts.AcceptanceA
    $uuidB = $script:accounts.AcceptanceB
    $rows = @(Invoke-Psql "SELECT player_name || '|' || player_uuid || '|' || balance::text || '|' || version::text FROM players WHERE player_uuid IN ('$uuidA','$uuidB') ORDER BY player_name;")
    if ($rows.Count -ne 2) { throw "Expected exactly two isolated synthetic account rows after $stage; observed $($rows.Count)." }
    $byName = @{}
    foreach ($row in $rows) {
        $parts = $row.Split('|')
        if ($parts.Count -ne 4 -or $parts[0] -notin @('AcceptanceA', 'AcceptanceB')) {
            throw "Unexpected isolated SQL player row after $stage."
        }
        $byName[$parts[0]] = [ordered]@{
            name = $parts[0]
            uuid = $parts[1].ToLowerInvariant()
            balance = ([decimal]::Parse($parts[2], [Globalization.CultureInfo]::InvariantCulture)).ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
            version = [long]$parts[3]
        }
    }
    foreach ($name in @('AcceptanceA', 'AcceptanceB')) {
        if (-not $byName.ContainsKey($name)) { throw "Missing SQL account $name after $stage." }
        if ($byName[$name].uuid -ne $script:accounts[$name]) { throw "SQL UUID mismatch for $name after $stage." }
        $balanceKey = 'syncmoney:balance:' + $script:accounts[$name]
        $versionKey = 'syncmoney:version:' + $script:accounts[$name]
        $redisBalance = ((Invoke-Redis @('-n', '15', 'GET', $balanceKey)) -join [Environment]::NewLine).Trim()
        $redisVersion = ((Invoke-Redis @('-n', '15', 'GET', $versionKey)) -join [Environment]::NewLine).Trim()
        if ($redisBalance -eq '' -or $redisVersion -eq '') { throw "Redis balance/version is missing for $name after $stage." }
        $normalizedBalance = ([decimal]::Parse($redisBalance, [Globalization.CultureInfo]::InvariantCulture)).ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
        if ($normalizedBalance -ne $byName[$name].balance -or [long]$redisVersion -ne $byName[$name].version) {
            throw "Redis and SQL disagree for $name after $stage."
        }
        $byName[$name].redisBalance = $normalizedBalance
        $byName[$name].redisVersion = [long]$redisVersion
    }
    $playerRowCountOutput = @(Invoke-Psql 'SELECT COUNT(*) FROM players;')
    $redisKeyCountOutput = @(Invoke-Redis @('-n', '15', 'DBSIZE'))
    $playerRowCount = 0
    $redisKeyCount = 0
    if ($playerRowCountOutput.Count -ne 1 -or -not [int]::TryParse(
            [string]$playerRowCountOutput[0], [Globalization.NumberStyles]::Integer,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$playerRowCount)) {
        $observed = $playerRowCountOutput -join ', '
        throw "PostgreSQL players COUNT(*) returned an invalid value after ${stage}: [$observed]."
    }
    if ($redisKeyCountOutput.Count -ne 1 -or -not [int]::TryParse(
            [string]$redisKeyCountOutput[0], [Globalization.NumberStyles]::Integer,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$redisKeyCount)) {
        $observed = $redisKeyCountOutput -join ', '
        throw "Redis DB 15 DBSIZE returned an invalid value after ${stage}: [$observed]."
    }
    return [ordered]@{
        stage = $stage
        accounts = @($byName['AcceptanceA'], $byName['AcceptanceB'])
        sqlPlayerRowCount = $playerRowCount
        redisDb15KeyCount = $redisKeyCount
        schemaVersionRows = @(Invoke-Psql 'SELECT id || ''|'' || version::text FROM syncmoney_schema_version ORDER BY id;')
        applicationTables = @(Invoke-Psql "SELECT tablename FROM pg_tables WHERE schemaname = current_schema() AND (tablename = 'players' OR tablename LIKE 'syncmoney_%') ORDER BY tablename;")
    }
}

function Assert-Balances([object]$snapshot, [string]$expectedA, [string]$expectedB, [string]$stage) {
    if ($snapshot.accounts[0].name -ne 'AcceptanceA' -or $snapshot.accounts[0].balance -ne $expectedA -or
        $snapshot.accounts[1].name -ne 'AcceptanceB' -or $snapshot.accounts[1].balance -ne $expectedB) {
        throw "Synthetic balances did not match the expected values after $stage."
    }
    if ($snapshot.sqlPlayerRowCount -ne 2) { throw "The disposable SQL database contains unexpected player rows after $stage." }
}

function Get-RunOwnedPaperProcesses {
    @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop | Where-Object {
        $_.Name -in @('java.exe', 'javaw.exe') -and $_.CommandLine -and
        $_.CommandLine.IndexOf('-Dsyncmoney.t21.run=' + $script:runRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $_.CommandLine -notmatch 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon'
    })
}

function Get-RunOwnedGradleDaemons {
    $isolatedGradleHome = [IO.Path]::GetFullPath($script:gradleHome)
    @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop | Where-Object {
        $_.Name -in @('java.exe', 'javaw.exe') -and $_.CommandLine -and
        $_.CommandLine.IndexOf($isolatedGradleHome, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $_.CommandLine -match 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon'
    })
}

function Stop-RunOwnedGradleDaemons {
    $owned = @(Get-RunOwnedGradleDaemons)
    $script:gradleDaemonsBeforeStop = @($owned | ForEach-Object { [int]$_.ProcessId })
    $stopLog = Join-Path $script:logsRoot 'gradle-daemon-stop.txt'
    try {
        $cleanup = Invoke-RunOwnedGradleDaemonCleanup $owned {
            Push-Location $script:runRoot
            try {
                $null = & (Join-Path $script:runRoot 'gradlew.bat') --stop *> $stopLog
                $script:gradleStopExitCode = $LASTEXITCODE
                return [ordered]@{ exitCode = $script:gradleStopExitCode }
            } finally {
                Pop-Location
            }
        } {
            $deadline = [DateTime]::UtcNow.AddSeconds(20)
            do {
                $remaining = @(Get-RunOwnedGradleDaemons)
                if ($remaining.Count -eq 0) { return @() }
                Start-Sleep -Milliseconds 250
            } while ([DateTime]::UtcNow -lt $deadline)
            return @(Get-RunOwnedGradleDaemons)
        }
        $script:gradleDaemonsAfterStop = @($cleanup.remainingProcessIds)
        $script:gradleStopExitCode = $cleanup.stopExitCode
        $script:cleanupActions.Add([ordered]@{
            stage = $script:currentStage
            action = $cleanup.action
            processIds = @($cleanup.processIds)
            stopExitCode = $cleanup.stopExitCode
            remainingProcessIds = @($cleanup.remainingProcessIds)
            processExitVerified = $true
        })
    } catch {
        try { $script:gradleDaemonsAfterStop = @(Get-RunOwnedGradleDaemons | ForEach-Object { [int]$_.ProcessId }) }
        catch { $script:gradleDaemonsAfterStop = @() }
        throw
    }
}

function Wait-PaperStopped([int]$timeoutSeconds = 25) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    do {
        if (@(Get-RunOwnedPaperProcesses).Count -eq 0) { return }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw 'Run-owned Paper JVM did not exit after the normal stop command.'
}

function Invoke-ProfilePlugin([string]$jarPattern, [string]$version) {
    $editorPath = $script:profileEditor
    $env:T21_PLUGIN_JAR_PATTERN = $jarPattern
    $env:T21_PLUGIN_VERSION = $version
    $logPath = Join-Path $script:logsRoot ("profile-" + $script:currentStage + '.txt')
    Push-Location $script:activeNodeRoot
    try {
        $output = @(& node $editorPath 'set-plugin' 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    $output | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath $logPath -Encoding utf8
    if ($exitCode -ne 0) { throw "Could not configure the exact $version plugin artifact; see $logPath." }
    $profileObservation = (($output | ForEach-Object { $_.ToString() }) -join "`n") | ConvertFrom-Json
    if ($profileObservation.version -ne $version -or $profileObservation.exactJarPattern -ne $jarPattern) {
        throw "PlugDev profile did not select exact artifact '$jarPattern' and expected version '$version'."
    }
}

function Invoke-ConfigAction([string]$action, [string]$stage = $script:currentStage) {
    $editorPath = $script:profileEditor
    $logPath = Join-Path $script:logsRoot ("config-$action-" + $stage + '.txt')
    Push-Location $script:activeNodeRoot
    try {
        $output = @(& node $editorPath $action 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    $output | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath $logPath -Encoding utf8
    if ($exitCode -ne 0) { throw "Could not $action the runtime Syncmoney config; see $logPath." }
    $observation = (($output | ForEach-Object { $_.ToString() }) -join "`n") | ConvertFrom-Json
    $script:configObservations.Add([ordered]@{ stage = $stage; action = $action; observed = $observation })
    return $observation
}

function Set-ServerIdentity([string]$serverName, [string]$stage) {
    if ($serverName -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}$') {
        throw "Unsafe Syncmoney server-name for $stage."
    }
    $env:T21_SERVER_NAME = $serverName
    $observation = Invoke-ConfigAction 'set-server-name' $stage
    if ($observation.serverName -ne $serverName) {
        throw "Runtime config server-name did not match node identity '$serverName' for $stage."
    }
}

function Set-ActiveNode([string]$serverName) {
    if (-not $script:nodeProjects.Contains($serverName)) {
        throw "No isolated PlugDev workspace was prepared for node '$serverName'."
    }
    $script:activeNodeName = $serverName
    $script:activeNodeRoot = $script:nodeProjects[$serverName]
}

function Set-CandidateApiConsumer([bool]$enabled) {
    $pluginsPath = Join-Path $script:activeNodeRoot '.plugdev\run\plugins'
    $consumerPath = Join-Path $pluginsPath 'SyncmoneyApiConsumer.jar'
    if ($enabled) {
        Copy-Item -LiteralPath $script:apiConsumerPath -Destination $consumerPath -Force
        $actual = (Get-FileHash -LiteralPath $consumerPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $expected = (Get-FileHash -LiteralPath $script:apiConsumerPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $expected) { throw 'Could not stage the pinned candidate API consumer.' }
    } elseif (Test-Path -LiteralPath $consumerPath -PathType Leaf) {
        Remove-Item -LiteralPath $consumerPath -Force
    }
}

function Wait-ServerLog([string]$text, [int]$timeoutSeconds = 90) {
    $serverLog = Join-Path $script:activeNodeRoot '.plugdev\run\logs\latest.log'
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    do {
        if (Test-Path -LiteralPath $serverLog -PathType Leaf) {
            $content = Get-Content -LiteralPath $serverLog -Raw
            if ($content -match 'ACCEPTANCE FAIL|T21_(?:WRITE|READ|QUIESCENCE)_FAIL|Error occurred while enabling Syncmoney|Could not load plugin') {
                throw "Runtime failure marker observed while waiting for an acceptance marker; see $serverLog."
            }
            if ($content.Contains($text)) { return }
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for runtime marker '$text'; see $serverLog."
}

function Invoke-PlugDev([string[]]$arguments, [string]$logName) {
    $logPath = Join-Path $script:logsRoot $logName
    Push-Location $script:activeNodeRoot
    try {
        $output = @(& node $script:plugDevCli @arguments 2>&1)
        $exitCode = $LASTEXITCODE
        $output | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath $logPath -Encoding utf8
    } finally {
        Pop-Location
    }
    if ($exitCode -ne 0) { throw "Local PlugDev command exited $exitCode; see $logPath." }
    return [ordered]@{ exitCode = $exitCode; log = $logPath }
}

function Start-Paper([string]$jarPattern, [string]$version, [string]$stage, [string]$serverName) {
    Set-ActiveNode $serverName
    $script:currentStage = $stage
    if (@(Get-RunOwnedPaperProcesses).Count -ne 0) { throw 'A prior run-owned server process is still alive.' }
    if (-not $script:initializedNodeConfigs.Contains($serverName)) {
        $script:currentStage = $stage + '-initial-config'
        $priorServerName = $env:T21_SERVER_NAME
        Remove-Item Env:T21_SERVER_NAME -ErrorAction SilentlyContinue
        try { $null = Invoke-ConfigAction 'seed-config' }
        finally {
            if ($null -eq $priorServerName) { Remove-Item Env:T21_SERVER_NAME -ErrorAction SilentlyContinue }
            else { $env:T21_SERVER_NAME = $priorServerName }
        }
        $script:initializedNodeConfigs[$serverName] = $true
        $script:currentStage = $stage
    }
    Set-ServerIdentity $serverName ($stage + '-server-identity')
    $candidateApiConsumer = $version -ne '1.3.2'
    Set-CandidateApiConsumer $candidateApiConsumer
    Invoke-ProfilePlugin $jarPattern $version
    $script:serverMayExist = $true
    $start = Invoke-PlugDev @('server', 'start', '--config', '.plugdev\profile.yml') ($stage + '-start.txt')
    Wait-ServerLog ("[Syncmoney] Loading server plugin Syncmoney v" + $version)
    $serverLog = Join-Path $script:activeNodeRoot '.plugdev\run\logs\latest.log'
    $runtimeIdentity = Get-T21PlatformIdentity (Get-Content -LiteralPath $serverLog -Raw)
    Assert-T21PlatformIdentity $runtimeIdentity $PlatformSoftware $MinecraftVersion $PlatformBuildNumber
    $serverJarProvenance = Get-T21ServerJarProvenance $stage
    $script:runtimeObservations.Add([ordered]@{
        stage = $stage
        serverName = $serverName
        software = $runtimeIdentity.software
        runtimeVersion = $runtimeIdentity.runtimeVersion
        build = $runtimeIdentity.build
        minecraftVersion = $runtimeIdentity.minecraftVersion
        serverJarProvenance = $serverJarProvenance
    })
    Invoke-PlugDev @('server', 'command', 'smaccept version') ($stage + '-version-command.txt') | Out-Null
    Wait-ServerLog ("T21_VERSION_PASS expected=" + $version + ' actual=' + $version)
    Wait-ServerLog 'Syncmoney enabled successfully'
    $apiMarker = 'NOT_REQUIRED_FOR_BASELINE'
    if ($version -ne '1.3.2') {
        Wait-ServerLog 'API CLASSLOADER PASS provider=Syncmoney'
        $apiMarker = 'PASS'
    }
    $script:stages.Add([ordered]@{
        stage = $stage
        pluginVersion = $version
        serverName = $serverName
        serverRuntime = $runtimeIdentity
        startExitCode = $start.exitCode
        versionProbe = 'PASS'
        candidateApiConsumer = $apiMarker
        candidateApiConsumerEnabled = $candidateApiConsumer
    })
    $pluginManifest = Get-RuntimePluginManifest $stage
    $expectedPluginName = 'Syncmoney-' + $version + '.jar'
    $expectedPluginSha256 = if ($version -eq '1.3.2') { $baselineSha } else { $candidateSha }
    $pluginPin = Get-T21ArtifactPinObservation @($pluginManifest.artifacts) $expectedPluginName $expectedPluginSha256
    $pluginManifest['syncmoneyArtifactPin'] = $pluginPin
    $script:runtimePluginArtifacts.Add($pluginManifest)
    if ($pluginPin.matchesExpectedPin -ne $true) {
        throw "Runtime Syncmoney artifact pin mismatch for ${stage}: expected $expectedPluginName SHA-256 $expectedPluginSha256; found $($pluginPin.matchingArtifactCount) matching artifact(s) with SHA-256 '$($pluginPin.observedSha256)'."
    }
}

function Invoke-PluginCommand([string]$command, [string]$stage, [string]$expectedMarker) {
    $script:currentStage = $stage
    Invoke-PlugDev @('server', 'command', $command) ($stage + '-command.txt') | Out-Null
    Wait-ServerLog $expectedMarker
}

function Get-T21WriteMarkerLines {
    $serverLog = Join-Path $script:activeNodeRoot '.plugdev\run\logs\latest.log'
    if (-not (Test-Path -LiteralPath $serverLog -PathType Leaf)) { return @() }
    return @((Get-Content -LiteralPath $serverLog) | ForEach-Object { $_.ToString() } |
        Where-Object { $_.Contains('T21_WRITE_PASS ') })
}

function Get-T21QuiescenceMarkerLines {
    $serverLog = Join-Path $script:activeNodeRoot '.plugdev\run\logs\latest.log'
    if (-not (Test-Path -LiteralPath $serverLog -PathType Leaf)) { return @() }
    return @((Get-Content -LiteralPath $serverLog) | ForEach-Object { $_.ToString() } |
        Where-Object { $_.Contains('T21_QUIESCENCE_') })
}

function Invoke-T21QuiescenceCheck([string]$stage) {
    $priorCount = @(Get-T21QuiescenceMarkerLines).Count
    Invoke-PlugDev @('server', 'command', 'smaccept quiescence') ($stage + '-quiescence-command.txt') | Out-Null
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $markers = @(Get-T21QuiescenceMarkerLines)
        if ($markers.Count -gt $priorCount) {
            $marker = $markers[-1]
            if (-not $marker.Contains('T21_QUIESCENCE_PASS online-players=0')) {
                throw "T21 quiescence probe failed before stopping $stage. Observed: $marker"
            }
            return $marker.Trim()
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for a fresh T21 quiescence marker before stopping $stage."
}

function Format-T21Amount([decimal]$amount) {
    return $amount.ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
}

function Invoke-VaultWrite(
    [string]$command,
    [string]$stage,
    [string]$beforeA,
    [string]$beforeB,
    [string]$expectedA,
    [string]$expectedB
) {
    $beforeAmountA = [decimal]::Parse($beforeA, [Globalization.CultureInfo]::InvariantCulture)
    $beforeAmountB = [decimal]::Parse($beforeB, [Globalization.CultureInfo]::InvariantCulture)
    $targetAmountA = [decimal]::Parse($expectedA, [Globalization.CultureInfo]::InvariantCulture)
    $targetAmountB = [decimal]::Parse($expectedB, [Globalization.CultureInfo]::InvariantCulture)
    $deltaA = $targetAmountA - $beforeAmountA
    $deltaB = $targetAmountB - $beforeAmountB
    if ($deltaA -eq 0 -or $deltaB -eq 0) { throw 'The SYNC acceptance fixture requires both Vault mutations to change a balance.' }
    $operationA = if ($deltaA -gt 0) { 'DEPOSIT' } else { 'WITHDRAW' }
    $operationB = if ($deltaB -gt 0) { 'DEPOSIT' } else { 'WITHDRAW' }
    $receiptAmountA = Format-T21Amount ([Math]::Abs($deltaA))
    $receiptAmountB = Format-T21Amount ([Math]::Abs($deltaB))
    $expectedMarker = 'T21_WRITE_PASS expected-a=' + (Format-T21Amount $targetAmountA) +
        ' expected-b=' + (Format-T21Amount $targetAmountB) +
        ' receipt-a=SUCCESS:' + $operationA + ':' + $receiptAmountA + ':' + (Format-T21Amount $targetAmountA) +
        ' receipt-b=SUCCESS:' + $operationB + ':' + $receiptAmountB + ':' + (Format-T21Amount $targetAmountB)
    $priorMarkers = @(Get-T21WriteMarkerLines)
    $priorMarkerCount = $priorMarkers.Length
    $script:currentStage = $stage
    Invoke-PlugDev @('server', 'command', $command) ($stage + '-command.txt') | Out-Null

    $deadline = (Get-Date).AddSeconds(45)
    do {
        $markers = @(Get-T21WriteMarkerLines)
        if ($markers.Count -gt $priorMarkerCount) {
            $observedMarker = $markers[-1]
            if (-not $observedMarker.Contains($expectedMarker)) {
                throw "Vault write receipt did not match the expected accepted mutation at $stage. Observed: $observedMarker"
            }
            $script:vaultMutationReceipts.Add([ordered]@{
                stage = $stage
                provider = 'Vault'
                result = 'SUCCESS'
                accounts = @(
                    [ordered]@{ name = 'AcceptanceA'; before = (Format-T21Amount $beforeAmountA); requestedBalance = (Format-T21Amount $targetAmountA); operation = $operationA; receiptAmount = $receiptAmountA; receiptBalance = (Format-T21Amount $targetAmountA) },
                    [ordered]@{ name = 'AcceptanceB'; before = (Format-T21Amount $beforeAmountB); requestedBalance = (Format-T21Amount $targetAmountB); operation = $operationB; receiptAmount = $receiptAmountB; receiptBalance = (Format-T21Amount $targetAmountB) }
                )
                observedServerLogMarker = ($observedMarker.Substring($observedMarker.IndexOf('T21_WRITE_PASS ', [StringComparison]::Ordinal)))
            })
            return
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for the Vault write receipt from $stage."
}

function Stop-Paper([string]$stage) {
    $script:currentStage = $stage
    $sessionPath = Join-Path $script:activeNodeRoot '.plugdev\session.json'
    if (-not (Test-Path -LiteralPath $sessionPath -PathType Leaf)) { throw "PlugDev session is absent before normal stop for $stage." }
    $session = Get-Content -LiteralPath $sessionPath -Raw | ConvertFrom-Json
    $owned = @(Get-RunOwnedPaperProcesses | Where-Object { $_.ProcessId -eq [int]$session.pid })
    if ($owned.Count -ne 1) { throw 'Refusing RCON stop because the PlugDev PID is not the unique run-owned Paper process.' }
    $quiescenceMarker = Invoke-T21QuiescenceCheck $stage
    $stop = Invoke-PlugDev @('server', 'command', 'stop') ($stage + '-stop.txt')
    Wait-PaperStopped
    $serverLog = Join-Path $script:activeNodeRoot '.plugdev\run\logs\latest.log'
    if (-not (Test-Path -LiteralPath $serverLog -PathType Leaf)) { throw "Server log is missing after $stage." }
    Copy-Item -LiteralPath $serverLog -Destination (Join-Path $script:logsRoot ($stage + '-server.log'))
    Remove-Item -LiteralPath $sessionPath -Force
    $script:serverMayExist = $false
    $script:stages.Add([ordered]@{ stage = $stage; normalStopExitCode = $stop.exitCode; processExitVerified = $true; forcedCleanup = $false })
    $script:cleanupActions.Add([ordered]@{ stage = $stage; action = 'GRACEFUL_STOP'; processExitVerified = $true; forcedCleanup = $false })
    $serverLogText = Get-Content -LiteralPath $serverLog -Raw
    $shutdownFailureMarkers = @(Get-T21ShutdownFailureMarkers $serverLogText)
    if ($shutdownFailureMarkers.Count -gt 0) {
        throw "Shutdown failure marker observed for ${stage}: $($shutdownFailureMarkers -join '; ')."
    }
    if (-not $serverLogText.Contains('Syncmoney disabled.')) {
        throw "Syncmoney did not log its normal disable marker for $stage."
    }
    $script:shutdownObservations.Add([ordered]@{
        stage = $stage
        serverName = $script:activeNodeName
        normalStopExitCode = $stop.exitCode
        runOwnedProcessExitVerified = $true
        syncmoneyDisabledMarker = $true
        quiescenceMarker = $quiescenceMarker
        shutdownFailureMarkers = @()
        queueRetryInflightCounters = 'NOT_EXPOSED_BY_RUNTIME'
        requiredPostStopEvidence = 'Recovery-journal validation when applicable plus SQL/Redis balance-version parity readback'
        serverLogSha256 = (Get-FileHash -LiteralPath $serverLog -Algorithm SHA256).Hash.ToLowerInvariant()
    })
}

function Start-IsolatedRedis {
    if ($null -ne $script:redisProcess -and -not $script:redisProcess.HasExited) { throw 'The run-owned Redis process is already running.' }
    $script:redisProcess = Start-Process -FilePath $script:redisServer -ArgumentList 'redis.conf' `
        -WorkingDirectory (Split-Path -Parent $script:redisConfig) -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $script:logsRoot ($script:currentStage + '-redis.stdout.log')) `
        -RedirectStandardError (Join-Path $script:logsRoot ($script:currentStage + '-redis.stderr.log'))
    Write-RunnerProgress "redis process started: pid=$($script:redisProcess.Id)"
    $deadline = (Get-Date).AddSeconds(25)
    do {
        if ($script:redisProcess.HasExited) { throw "Run-owned Redis exited with code $($script:redisProcess.ExitCode)." }
        try {
            $pong = ((Invoke-Redis @('PING')) -join [Environment]::NewLine).Trim()
            if ($pong -eq 'PONG') {
                $script:redisStarted = $true
                Assert-IsolatedRedisOwnership
                Write-RunnerProgress 'redis ready'
                return
            }
        } catch {
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw 'Timed out waiting for the isolated Redis process.'
}

function Stop-IsolatedRedis {
    if ($null -eq $script:redisProcess) {
        Assert-RunOwnedRedisProcessMayBeAlreadyExited $script:redisStarted $true | Out-Null
        return
    }
    $script:redisProcess.Refresh()
    if ($script:redisProcess.HasExited) {
        Assert-RunOwnedRedisProcessMayBeAlreadyExited $script:redisStarted $true | Out-Null
        return
    }
    $stopStage = $script:currentStage
    Assert-IsolatedRedisOwnership
    $output = @(& $script:redisCli '-h' '127.0.0.1' '-p' ([string]$script:redisPort) 'SHUTDOWN' 'SAVE' 2>&1)
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath (Join-Path $script:logsRoot ($script:currentStage + '-redis-shutdown.log')) -Encoding utf8
    $script:redisProcess.WaitForExit(15000) | Out-Null
    $script:redisProcess.Refresh()
    if (-not $script:redisProcess.HasExited) { throw 'Isolated Redis did not exit after SHUTDOWN SAVE.' }
    if ($exitCode -ne 0) { throw "Redis SHUTDOWN SAVE returned $exitCode." }
    if ($script:redisProcess.ExitCode -ne 0) { throw "Isolated Redis exited with code $($script:redisProcess.ExitCode)." }
    $script:redisStarted = $false
    $script:backendShutdownObservations.Add([ordered]@{
        backend = 'redis'
        stage = $stopStage
        result = 'VERIFIED'
        shutdownCommand = 'SHUTDOWN SAVE'
        shutdownExitCode = $exitCode
        processExitCode = $script:redisProcess.ExitCode
        processExitCodeObserved = $true
        processExitVerified = $true
    })
    $script:cleanupActions.Add([ordered]@{
        stage = $stopStage
        backend = 'redis'
        action = 'REDIS_SHUTDOWN_VERIFIED'
        shutdownExitCode = $exitCode
        processExitCode = $script:redisProcess.ExitCode
        processExitVerified = $true
    })
}

function Start-IsolatedPostgres {
    $arguments = @('-D', $script:pgData, '-l', (Join-Path $script:logsRoot ($script:currentStage + '-postgres.log')),
        '-o', "-h 127.0.0.1 -p $script:pgPort -c listen_addresses=127.0.0.1 -c fsync=on", '-w', '-t', '30', 'start')
    Invoke-PgCtl $arguments 'PostgreSQL start' ($script:currentStage + '-pgctl-start')
    $script:pgStarted = $true
    Write-RunnerProgress 'postgres pg_ctl start returned'
    $deadline = (Get-Date).AddSeconds(25)
    do {
        $null = @(& $script:pgIsReady '-h' '127.0.0.1' '-p' ([string]$script:pgPort) 2>&1)
        if ($LASTEXITCODE -eq 0) { Write-RunnerProgress 'postgres readiness probe passed'; return }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw 'Timed out waiting for the isolated PostgreSQL cluster.'
}

function Stop-IsolatedPostgres {
    if (-not $script:pgStarted) { return }
    $stopStage = $script:currentStage
    $statusBefore = @(& $script:pgCtl '-D' $script:pgData 'status' 2>&1)
    $statusBeforeExitCode = $LASTEXITCODE
    $statusBeforeKind = Get-RunOwnedPostgresStatusKind $statusBeforeExitCode $statusBefore
    if ($statusBeforeKind -ceq 'STOPPED') {
        throw 'The isolated PostgreSQL cluster exited before the runner requested its shutdown.'
    }
    if ($statusBeforeKind -ceq 'UNKNOWN') {
        $statusBeforeDetail = (@($statusBefore | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ -ne '' }) -join ' | ')
        Invoke-PgCtl @('-D', $script:pgData, '-m', 'fast', '-w', '-t', '30', 'stop') 'PostgreSQL cleanup after status error' ($script:currentStage + '-pgctl-recovery-stop')
        $statusAfterError = @(& $script:pgCtl '-D' $script:pgData 'status' 2>&1)
        $statusAfterErrorExitCode = $LASTEXITCODE
        $statusAfterErrorKind = Get-RunOwnedPostgresStatusKind $statusAfterErrorExitCode $statusAfterError
        if ($statusAfterErrorKind -ceq 'STOPPED') {
            $script:pgStarted = $false
            $script:backendShutdownObservations.Add([ordered]@{
                backend = 'postgresql'
                stage = $stopStage
                result = 'STATUS_UNVERIFIED'
                statusBefore = 'UNKNOWN'
                statusBeforeExitCode = $statusBeforeExitCode
                statusBeforeDetail = $statusBeforeDetail
                shutdownCommand = 'pg_ctl stop'
                shutdownExitCode = 0
                statusAfter = 'STOPPED'
                statusAfterExitCode = $statusAfterErrorExitCode
            })
        }
        throw "PostgreSQL status was unrecognized before cleanup (exit=$statusBeforeExitCode; $statusBeforeDetail); requested stop and observed $statusAfterErrorKind after."
    }
    Assert-RunOwnedPostgresStatus 'RUNNING' $statusBeforeExitCode $statusBefore | Out-Null
    Invoke-PgCtl @('-D', $script:pgData, '-m', 'fast', '-w', '-t', '30', 'stop') 'PostgreSQL stop' ($script:currentStage + '-pgctl-stop')
    $statusAfter = @(& $script:pgCtl '-D' $script:pgData 'status' 2>&1)
    $statusAfterExitCode = $LASTEXITCODE
    Assert-RunOwnedPostgresStatus 'STOPPED' $statusAfterExitCode $statusAfter | Out-Null
    $script:pgStarted = $false
    $script:backendShutdownObservations.Add([ordered]@{
        backend = 'postgresql'
        stage = $stopStage
        result = 'VERIFIED'
        statusBefore = 'RUNNING'
        statusBeforeExitCode = $statusBeforeExitCode
        shutdownCommand = 'pg_ctl stop'
        shutdownExitCode = 0
        statusAfter = 'STOPPED'
        statusAfterExitCode = $statusAfterExitCode
    })
    $script:cleanupActions.Add([ordered]@{
        stage = $stopStage
        backend = 'postgresql'
        action = 'POSTGRESQL_SHUTDOWN_VERIFIED'
        statusBeforeExitCode = $statusBeforeExitCode
        shutdownExitCode = 0
        statusAfterExitCode = $statusAfterExitCode
    })
}

function Get-FileManifest([string]$path) {
    $rootPath = [IO.Path]::GetFullPath($path)
    $files = @(Get-ChildItem -LiteralPath $rootPath -File -Recurse -Force | Sort-Object FullName)
    return @($files | ForEach-Object {
        [ordered]@{
            path = $_.FullName.Substring($rootPath.Length).TrimStart('\', '/').Replace('\', '/')
            bytes = $_.Length
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    })
}

function Get-RuntimePluginManifest([string]$stage) {
    $pluginsRoot = Join-Path $script:activeNodeRoot '.plugdev\run\plugins'
    $files = @(Get-ChildItem -LiteralPath $pluginsRoot -File -Recurse -Filter '*.jar' -Force | Sort-Object FullName)
    return [ordered]@{
        stage = $stage
        serverName = $script:activeNodeName
        artifacts = @($files | ForEach-Object {
            [ordered]@{
                name = $_.FullName.Substring($pluginsRoot.Length).TrimStart('\', '/').Replace('\', '/')
                bytes = $_.Length
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        })
    }
}

function Get-T21YamlScalar([string]$yamlText, [string]$name) {
    $pattern = '(?m)^' + [regex]::Escape($name) + '\s*:\s*(?<value>.*)\s*$'
    $match = [regex]::Match($yamlText, $pattern)
    if (-not $match.Success) { throw "PlugDev profile is missing '$name'." }
    $value = $match.Groups['value'].Value.Trim()
    if ($value.Length -ge 2 -and $value[0] -eq '"' -and $value[$value.Length - 1] -eq '"') {
        try { return [string](ConvertFrom-Json -InputObject $value -ErrorAction Stop) }
        catch { throw "PlugDev profile has an invalid double-quoted '$name'." }
    }
    if ($value.Length -ge 2 -and $value[0] -eq "'" -and $value[$value.Length - 1] -eq "'") {
        return $value.Substring(1, $value.Length - 2).Replace("''", "'")
    }
    return $value
}

function Get-T21JarManifest([string]$path) {
    $archive = [IO.Compression.ZipFile]::OpenRead($path)
    try {
        $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
        if ($null -eq $entry) { throw "JAR has no META-INF/MANIFEST.MF: $path" }
        $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8, $true)
        try { $contents = $reader.ReadToEnd() }
        finally { $reader.Dispose() }
    } finally {
        $archive.Dispose()
    }

    $contents = [regex]::Replace($contents, "\r?\n ", '')
    $fields = [Collections.Specialized.OrderedDictionary]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($line in ($contents -split "\r?\n")) {
        if ($line -match '^([^:]+):\s?(.*)$') { $fields[$Matches[1]] = $Matches[2] }
    }
    return [ordered]@{
        mainClass = $fields['Main-Class']
        implementationTitle = $fields['Implementation-Title']
        implementationVersion = $fields['Implementation-Version']
        specificationVersion = $fields['Specification-Version']
        buildNumber = $fields['Build-Number']
    }
}

function Get-T21ServerJarProvenance([string]$stage) {
    $profilePath = Join-Path $script:activeNodeRoot '.plugdev\profile.yml'
    $profileText = Get-Content -LiteralPath $profilePath -Raw
    $profileServer = Get-T21YamlScalar $profileText 'server'
    $profileVersion = Get-T21YamlScalar $profileText 'version'
    $profileServerJar = Get-T21YamlScalar $profileText 'serverJar'
    if ($profileServer -cne 'custom' -or $profileVersion -cne $MinecraftVersion -or
        -not (Test-T21PathsEqual $profileServerJar $serverPath)) {
        throw "PlugDev profile at stage $stage does not identify the pinned custom server JAR."
    }

    $runDirectory = Join-Path $script:activeNodeRoot '.plugdev\run'
    $markerPath = Join-Path $runDirectory '.plugdev-server-jar.json'
    if (-not (Test-Path -LiteralPath $markerPath -PathType Leaf)) { throw "PlugDev server JAR metadata is missing at stage $stage." }
    $marker = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
    $serverBytes = [long](Get-Item -LiteralPath $serverPath).Length
    if (-not (Test-T21PathsEqual ([string]$marker.source) $serverPath) -or
        -not (Test-T21PathsEqual ([string]$marker.preparedSource) $serverPath) -or
        [long]$marker.size -ne $serverBytes) {
        throw "PlugDev server JAR metadata does not match the pinned input at stage $stage."
    }

    $bootstrapPath = Join-Path $runDirectory 'server.jar'
    if (-not (Test-Path -LiteralPath $bootstrapPath -PathType Leaf)) { throw "PlugDev bootstrap JAR is missing at stage $stage." }
    $bootstrapItem = Get-Item -LiteralPath $bootstrapPath -Force
    $linkType = 'COPY_OF_PINNED_INPUT'
    $linkTarget = $null
    if ($bootstrapItem.LinkType -eq 'SymbolicLink') {
        $target = [string](@($bootstrapItem.Target)[0])
        if ([IO.Path]::IsPathRooted($target)) { $linkTarget = [IO.Path]::GetFullPath($target) }
        else { $linkTarget = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $bootstrapPath) $target)) }
        if (-not (Test-T21PathsEqual $linkTarget $serverPath)) {
            throw "PlugDev server.jar symbolic-link target differs from the pinned input at stage $stage."
        }
        $linkType = 'SYMBOLIC_LINK'
    }
    $bootstrapSha256 = (Get-FileHash -LiteralPath $bootstrapPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($bootstrapSha256 -cne $serverSha) { throw "PlugDev server.jar SHA-256 differs from the pinned input at stage $stage." }

    $processes = @(Get-RunOwnedPaperProcesses)
    if ($processes.Count -ne 1) { throw "Expected exactly one run-owned Java process while observing server JAR provenance at stage $stage." }
    $commandLine = [string]$processes[0].CommandLine
    $jarArgument = [regex]::Match($commandLine, '(?i)(?:^|\s)-jar\s+(?:"(?<quoted>[^"]+)"|(?<plain>\S+))')
    $argumentPath = if ($jarArgument.Groups['quoted'].Success) {
        $jarArgument.Groups['quoted'].Value
    } else {
        $jarArgument.Groups['plain'].Value
    }
    if (-not $jarArgument.Success -or -not (Test-T21PathsEqual $argumentPath $bootstrapPath)) {
        throw "Run-owned Java process did not launch PlugDev's pinned server.jar with -jar at stage $stage."
    }
    $bootstrapManifest = Get-T21JarManifest $bootstrapPath

    $software = $PlatformSoftware.ToLowerInvariant()
    $runtimeRelativePath = ".plugdev/run/versions/$MinecraftVersion/$software-$MinecraftVersion.jar"
    $runtimePath = Join-Path $runDirectory ("versions\{0}\{1}-{0}.jar" -f $MinecraftVersion, $software)
    if (-not (Test-Path -LiteralPath $runtimePath -PathType Leaf)) { throw "Expected versioned runtime JAR is missing at stage ${stage}: $runtimePath" }
    $runtimeManifest = Get-T21JarManifest $runtimePath
    $expectedTitle = switch ($software) {
        'paper' { 'Paper' }
        'folia' { 'Folia' }
        'canvas' { 'Canvas' }
        default { throw "Unsupported server software in T21 provenance: $software" }
    }
    $expectedVersionPrefix = [regex]::Escape($MinecraftVersion + '-' + $PlatformBuildNumber)
    if ($runtimeManifest.mainClass -cne 'org.bukkit.craftbukkit.Main' -or
        $runtimeManifest.implementationTitle -cne $expectedTitle -or
        [string]$runtimeManifest.implementationVersion -notmatch ('^' + $expectedVersionPrefix + '(?:-|$)') -or
        [string]$runtimeManifest.buildNumber -cne [string]$PlatformBuildNumber) {
        throw "Versioned runtime JAR manifest does not match $expectedTitle $MinecraftVersion`#$PlatformBuildNumber at stage $stage."
    }

    $profileObservation = [ordered]@{
        path = $profilePath
        server = $profileServer
        version = $profileVersion
        serverJar = $profileServerJar
    }
    $provenance = [ordered]@{
        runDirectory = $runDirectory
        input = [ordered]@{ path = $serverPath; bytes = $serverBytes; sha256 = $serverSha }
        profile = $profileObservation
        plugDev = [ordered]@{
            markerPath = $markerPath
            source = [string]$marker.source
            preparedSource = [string]$marker.preparedSource
            sourceBytes = [long]$marker.size
            sourceMtimeMs = $marker.mtimeMs
        }
        bootstrap = [ordered]@{
            path = $bootstrapPath
            runRelativePath = '.plugdev/run/server.jar'
            sha256 = $bootstrapSha256
            linkType = $linkType
            linkTarget = $linkTarget
            manifest = $bootstrapManifest
            launchCommand = [ordered]@{ observed = $true; processId = [int]$processes[0].ProcessId; argumentPath = $argumentPath }
        }
        runtimeOutput = [ordered]@{
            path = $runtimePath
            runRelativePath = $runtimeRelativePath
            sha256 = (Get-FileHash -LiteralPath $runtimePath -Algorithm SHA256).Hash.ToLowerInvariant()
            manifest = $runtimeManifest
        }
    }
    $observation = [ordered]@{ serverName = $script:activeNodeName; stage = $stage; serverJarProvenance = $provenance }
    if (-not (Test-T21ServerJarProvenanceObservation $observation $serverPath $serverSha $serverBytes $software $MinecraftVersion $PlatformBuildNumber)) {
        throw "Server JAR provenance failed its report contract at stage $stage."
    }
    return $provenance
}

function Initialize-T21ProjectWorkspace([string]$projectRoot) {
    New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'tools\plugdev'),
        (Join-Path $projectRoot 'src\main\resources'), (Join-Path $projectRoot 'build\libs'),
        (Join-Path $projectRoot 'build\acceptance'), (Join-Path $projectRoot 'gradle') -ErrorAction Stop | Out-Null
    foreach ($name in @('build.gradle', 'settings.gradle', 'gradlew.bat')) {
        Copy-Item -LiteralPath (Join-Path $root $name) -Destination (Join-Path $projectRoot $name)
    }
    Copy-Item -LiteralPath (Join-Path $root 'gradle\wrapper') -Destination (Join-Path $projectRoot 'gradle') -Recurse
    $settingsText = Get-Content -LiteralPath (Join-Path $projectRoot 'settings.gradle') -Raw
    $includes = [regex]::Matches($settingsText, 'include\s*\(([^)]*)\)')
    foreach ($include in $includes) {
        foreach ($projectMatch in [regex]::Matches($include.Groups[1].Value, "['""]([^'""]+)['""]")) {
            $projectPath = $projectMatch.Groups[1].Value.Replace(':', '\')
            $sourceBuild = Join-Path (Join-Path $root $projectPath) 'build.gradle'
            if (-not (Test-Path -LiteralPath $sourceBuild -PathType Leaf)) { throw "Missing included Gradle build file: $sourceBuild" }
            $targetDirectory = Join-Path $projectRoot $projectPath
            New-Item -ItemType Directory -Force -Path $targetDirectory | Out-Null
            Copy-Item -LiteralPath $sourceBuild -Destination (Join-Path $targetDirectory 'build.gradle')
        }
    }
    Copy-Item -LiteralPath (Join-Path $root 'tools\plugdev\prepare.mjs') -Destination (Join-Path $projectRoot 'tools\plugdev\prepare.mjs')
    foreach ($name in @('config.yml', 'plugin.yml')) {
        Copy-Item -LiteralPath (Join-Path $root "src\main\resources\$name") -Destination (Join-Path $projectRoot "src\main\resources\$name")
    }
    Copy-Item -LiteralPath $candidatePath -Destination (Join-Path $projectRoot "build\libs\Syncmoney-$CandidateVersion.jar")
    Copy-Item -LiteralPath $baselinePath -Destination (Join-Path $projectRoot 'build\libs\Syncmoney-1.3.2.jar')
    Copy-Item -LiteralPath $acceptancePath -Destination (Join-Path $projectRoot 'build\acceptance\SyncmoneyAcceptance.jar')
    Copy-Item -LiteralPath $apiConsumerPath -Destination (Join-Path $projectRoot 'build\acceptance\SyncmoneyApiConsumer.jar')
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Assert-NoReparsePointAncestors $root
if ($ServerNames.Count -lt 1 -or $ServerNames.Count -gt 2) {
    throw 'T21 bounded SYNC upgrade supports one or two sequential node identities per isolated run.'
}
foreach ($serverName in $ServerNames) {
    if ($serverName -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}$') { throw "Invalid Syncmoney server-name '$serverName'." }
}
if (@($ServerNames | Sort-Object -Unique).Count -ne $ServerNames.Count) {
    throw 'Each sequential node must have a distinct Syncmoney server-name.'
}
$script:serverNames = @($ServerNames)
$serverPath = (Resolve-Path -LiteralPath $ServerJar).Path
$serverSha = (Get-FileHash -LiteralPath $serverPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($serverSha -ne $ExpectedServerSha256.ToLowerInvariant()) { throw "Server JAR SHA-256 mismatch: $serverSha." }
$serverBytes = [long](Get-Item -LiteralPath $serverPath).Length
$baselinePath = (Resolve-Path -LiteralPath $BaselinePluginJar).Path
$baselineSha = (Get-FileHash -LiteralPath $baselinePath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($baselineSha -ne $ExpectedBaselinePluginSha256.ToLowerInvariant()) { throw "Baseline plugin SHA-256 mismatch: $baselineSha." }
$baselineSourceRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $baselinePath))
$observedCommit = (& git -C $baselineSourceRoot rev-parse HEAD 2>$null | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $observedCommit -ne $BaselineSourceCommit.ToLowerInvariant()) { throw "Baseline source commit mismatch: observed '$observedCommit'." }
$candidatePath = Join-Path $root "build\libs\Syncmoney-$CandidateVersion.jar"
$acceptancePath = Join-Path $root 'build\acceptance\SyncmoneyAcceptance.jar'
$apiConsumerPath = Join-Path $root 'build\acceptance\SyncmoneyApiConsumer.jar'
$script:apiConsumerPath = $apiConsumerPath
foreach ($path in @($candidatePath, $acceptancePath, $apiConsumerPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required runtime artifact is missing: $path" }
}
$candidateSha = (Get-FileHash -LiteralPath $candidatePath -Algorithm SHA256).Hash.ToLowerInvariant()
$javaHomePath = (Resolve-Path -LiteralPath $JavaHome).Path
$java = Join-Path $javaHomePath 'bin\java.exe'
$javaBanner = (& $java -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaBanner -notmatch 'version "25(?:\.|"|\+)') { throw 'T21 requires Java 25.' }
$plugDevRootPath = (Resolve-Path -LiteralPath $PlugDevRoot).Path
$script:plugDevCli = Join-Path $plugDevRootPath 'packages\cli\dist\cli.js'
if (-not (Test-Path -LiteralPath $script:plugDevCli -PathType Leaf)) { throw 'Local PlugDev CLI was not found.' }

$pgBinPath = (Resolve-Path -LiteralPath $PostgresBin).Path
foreach ($name in @('initdb.exe', 'pg_ctl.exe', 'pg_isready.exe', 'psql.exe', 'pg_dump.exe', 'pg_restore.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $pgBinPath $name) -PathType Leaf)) { throw "PostgreSQL tool is missing: $name" }
}
$script:initdb = Join-Path $pgBinPath 'initdb.exe'
$script:pgCtl = Join-Path $pgBinPath 'pg_ctl.exe'
$script:pgIsReady = Join-Path $pgBinPath 'pg_isready.exe'
$script:psql = Join-Path $pgBinPath 'psql.exe'
$script:pgDump = Join-Path $pgBinPath 'pg_dump.exe'
$script:pgRestore = Join-Path $pgBinPath 'pg_restore.exe'
$pgVersion = (& (Join-Path $pgBinPath 'postgres.exe') '--version' 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Could not read the local PostgreSQL server version.' }

$redisServerCommand = Get-Command redis-server.exe, redis-server -ErrorAction SilentlyContinue | Select-Object -First 1
$redisCliCommand = Get-Command redis-cli.exe, redis-cli -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $redisServerCommand -or $null -eq $redisCliCommand -or
    $redisServerCommand.CommandType -ne 'Application' -or $redisCliCommand.CommandType -ne 'Application') {
    throw 'T21 SYNC upgrade validation requires local redis-server and redis-cli executables.'
}
$script:redisServer = $redisServerCommand.Source
$script:redisCli = $redisCliCommand.Source
if ($script:redisServer -match '(?i)\\scoop\\shims\\redis-server\.exe$') {
    $scoopRoot = Split-Path -Parent (Split-Path -Parent $script:redisServer)
    $realPath = Join-Path $scoopRoot 'apps\redis\current\redis-server.exe'
    if (Test-Path -LiteralPath $realPath -PathType Leaf) { $script:redisServer = (Resolve-Path -LiteralPath $realPath).Path }
}
if ($script:redisCli -match '(?i)\\scoop\\shims\\redis-cli\.exe$') {
    $scoopRoot = Split-Path -Parent (Split-Path -Parent $script:redisCli)
    $realPath = Join-Path $scoopRoot 'apps\redis\current\redis-cli.exe'
    if (Test-Path -LiteralPath $realPath -PathType Leaf) { $script:redisCli = (Resolve-Path -LiteralPath $realPath).Path }
}
$redisVersion = (& $script:redisServer '--version' 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Could not read the local Redis server version.' }

$reportsRoot = [IO.Path]::GetFullPath((Join-Path $root 'build\reports\foundation\T21-sync-upgrade'))
Assert-NoReparsePointAncestors $reportsRoot
New-Item -ItemType Directory -Force -Path $reportsRoot | Out-Null
Assert-NoReparsePointAncestors $reportsRoot
$guid = [guid]::NewGuid().ToString('N')
$runLabel = '{0}-{1}-{2}' -f $PlatformSoftware.ToLowerInvariant(), $MinecraftVersion, $PlatformBuildNumber
$script:runRoot = [IO.Path]::GetFullPath((Join-Path $reportsRoot ($runLabel + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $guid)))
if (-not $script:runRoot.StartsWith($reportsRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -or
    (Test-Path -LiteralPath $script:runRoot)) { throw 'Refusing an invalid or reused T21 run directory.' }
$script:logsRoot = Join-Path $script:runRoot 'logs'
$script:progressPath = Join-Path $script:runRoot 'progress.log'
$script:backupsRoot = Join-Path $script:runRoot 'preupgrade-backup'
$script:plugDevHome = Join-Path $script:runRoot '.plugdev-home'
$script:gradleHome = Join-Path $script:runRoot '.gradle-user-home'
$script:nodePorts = [Collections.Generic.List[object]]::new()
$reservedPorts = [Collections.Generic.List[int]]::new()
foreach ($serverName in $script:serverNames) {
    $nodeGamePort = Get-FreePort 20000 45535 @($reservedPorts.ToArray()) 10000
    $nodeRconPort = $nodeGamePort + 10000
    $script:nodePorts.Add([ordered]@{ serverName = $serverName; gamePort = $nodeGamePort; rconPort = $nodeRconPort })
    $reservedPorts.Add($nodeGamePort)
    $reservedPorts.Add($nodeRconPort)
}
$script:gamePort = [int]$script:nodePorts[0].gamePort
$script:rconPort = [int]$script:nodePorts[0].rconPort
$script:pgPort = Get-FreePort 20000 65535 @($reservedPorts.ToArray())
$reservedPorts.Add($script:pgPort)
$script:redisPort = Get-FreePort 20000 65535 @($reservedPorts.ToArray())
$script:pgUser = 'syncmoney_t21'
$script:dbName = 'syncmoney_t21'
$script:accounts = [ordered]@{ AcceptanceA = Get-OfflineUuid 'AcceptanceA'; AcceptanceB = Get-OfflineUuid 'AcceptanceB' }
$script:pgData = Join-Path $script:runRoot 'postgres\data'
$script:redisData = Join-Path $script:runRoot 'redis'
$script:redisConfig = Join-Path $script:runRoot 'redis\redis.conf'
$script:redisProcess = $null
$script:serverMayExist = $false
$script:currentStage = 'prepare'
$script:failure = $null
$script:cleanupFailure = $null
$script:stages = [Collections.Generic.List[object]]::new()
$script:snapshots = [Collections.Generic.List[object]]::new()
$script:configObservations = [Collections.Generic.List[object]]::new()
$script:shutdownObservations = [Collections.Generic.List[object]]::new()
$script:backendShutdownObservations = [Collections.Generic.List[object]]::new()
$script:recoverySpoolObservations = [Collections.Generic.List[object]]::new()
$script:recoverySpoolRollbackObservations = [Collections.Generic.List[object]]::new()
$script:runtimeObservations = [Collections.Generic.List[object]]::new()
$script:vaultMutationReceipts = [Collections.Generic.List[object]]::new()
$script:runtimePluginArtifacts = [Collections.Generic.List[object]]::new()
$script:cleanupActions = [Collections.Generic.List[object]]::new()
$script:gradleDaemonsBeforeStop = @()
$script:gradleDaemonsAfterStop = @()
$script:gradleStopExitCode = $null
$script:backupManifest = @()
$script:recoverySpool = $null
$initialRedisSize = $null
$script:pgStarted = $false
$script:redisStarted = $false
$script:nodeProjects = [ordered]@{}
$script:nodeProjects[$script:serverNames[0]] = $script:runRoot
$script:activeNodeName = $null
$script:activeNodeRoot = $script:runRoot
$script:initializedNodeConfigs = @{}

New-Item -ItemType Directory -Path $script:runRoot, $script:logsRoot, $script:pgData, $script:redisData,
    $script:plugDevHome, $script:gradleHome -ErrorAction Stop | Out-Null
Assert-NoReparsePointAncestors $script:runRoot
Initialize-T21ProjectWorkspace $script:runRoot
$script:profileEditor = Join-Path $script:runRoot 'edit-profile.cjs'
for ($nodeIndex = 1; $nodeIndex -lt $script:serverNames.Count; $nodeIndex++) {
    $serverName = $script:serverNames[$nodeIndex]
    $projectRoot = Join-Path $script:runRoot ("nodes\node-{0}-{1}" -f ($nodeIndex + 1), $serverName)
    if (-not $projectRoot.StartsWith($script:runRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing a node workspace outside the run-owned root.'
    }
    New-Item -ItemType Directory -Path $projectRoot -ErrorAction Stop | Out-Null
    Initialize-T21ProjectWorkspace $projectRoot
    $script:nodeProjects[$serverName] = $projectRoot
}

$profileEditor = Join-Path $script:runRoot 'edit-profile.cjs'
@'
const fs = require("node:fs");
const path = require("node:path");
const { createRequire } = require("node:module");
const yaml = createRequire(path.join(process.env.PLUGDEV_ROOT, "package.json"))("yaml");
const action = process.argv[2];
const profilePath = path.resolve(".plugdev/profile.yml");
const configPath = path.resolve(".plugdev/run/plugins/Syncmoney/config.yml");
const operatorFixture = { customValue: "preserve-through-upgrade" };
function readConfig() {
  const config = yaml.parse(fs.readFileSync(configPath, "utf8"));
  if (config?.["config-version"] !== 12) throw new Error(`Expected config schema 12, observed ${config?.["config-version"]}`);
  if (config?.economy?.mode !== "sync") throw new Error(`Expected SYNC mode, observed ${config?.economy?.mode}`);
  if (config?.["db-enabled"] !== true || config?.["pubsub-enabled"] !== true) throw new Error("Expected legacy database and Pub/Sub switches enabled.");
  if (config?.redis?.enabled !== true || config?.redis?.database !== 15) throw new Error("Expected isolated Redis database 15 enabled.");
  if (config?.database?.enabled !== true || config?.database?.type !== "postgresql" || config?.database?.database !== "syncmoney_t21") {
    throw new Error("Expected isolated PostgreSQL SYNC database settings.");
  }
  if (process.env.T21_SERVER_NAME && config?.["server-name"] !== process.env.T21_SERVER_NAME) {
    throw new Error(`Expected server-name ${process.env.T21_SERVER_NAME}, observed ${config?.["server-name"]}.`);
  }
  if (JSON.stringify(config?.["t21-synthetic-operator-fixture"]) !== JSON.stringify(operatorFixture)) {
    throw new Error("Synthetic operator config fixture was not preserved.");
  }
  return {
    configSchema: config["config-version"],
    economyMode: config.economy.mode,
    legacyDatabaseEnabled: config["db-enabled"],
    legacyPubsubEnabled: config["pubsub-enabled"],
    redisEnabled: config.redis.enabled,
    redisDatabase: config.redis.database,
    databaseEnabled: config.database.enabled,
    databaseType: config.database.type,
    databaseName: config.database.database,
    serverName: config["server-name"],
    operatorFixturePreserved: true
  };
}
if (action === "seed-config") {
  const config = yaml.parse(fs.readFileSync(configPath, "utf8"));
  config["t21-synthetic-operator-fixture"] = operatorFixture;
  fs.writeFileSync(configPath, yaml.stringify(config), "utf8");
  console.log(JSON.stringify(readConfig()));
} else if (action === "assert-config") {
  console.log(JSON.stringify(readConfig()));
} else if (action === "set-server-name") {
  const serverName = process.env.T21_SERVER_NAME;
  if (!/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}$/.test(serverName ?? "")) throw new Error("Invalid test server-name.");
  const config = yaml.parse(fs.readFileSync(configPath, "utf8"));
  config["server-name"] = serverName;
  fs.writeFileSync(configPath, yaml.stringify(config), "utf8");
  console.log(JSON.stringify(readConfig()));
} else if (action === "set-plugin") {
  const profile = yaml.parse(fs.readFileSync(profilePath, "utf8"));
  profile.build.jarPattern = process.env.T21_PLUGIN_JAR_PATTERN;
  profile.jvm.args = [
    "-Dsyncmoney.acceptance.version=" + process.env.T21_PLUGIN_VERSION,
    "-Dsyncmoney.t21.run=" + process.env.T21_RUN_MARKER
  ];
  fs.writeFileSync(profilePath, yaml.stringify(profile), "utf8");
  console.log(JSON.stringify({ version: process.env.T21_PLUGIN_VERSION, exactJarPattern: profile.build.jarPattern }));
} else {
  throw new Error(`unsupported action ${action}`);
}
'@ | Set-Content -LiteralPath $profileEditor -Encoding utf8

try {
    $env:JAVA_HOME = $javaHomePath
    $env:Path = "$javaHomePath\bin;$env:Path"
    $env:PLUGDEV_ROOT = $plugDevRootPath
    $env:PLUGDEV_HOME = $script:plugDevHome
    $env:GRADLE_USER_HOME = $script:gradleHome
    $env:SYNCMONEY_TEST_SERVER_JAR = $serverPath
    $env:SYNCMONEY_CANDIDATE_VERSION = $CandidateVersion
    $env:SYNCMONEY_TEST_DB_TYPE = 'postgresql'
    $env:SYNCMONEY_TEST_DB_PORT = [string]$script:pgPort
    $env:SYNCMONEY_TEST_DB_USER = $script:pgUser
    $env:SYNCMONEY_TEST_DB_PASSWORD = ''
    $env:SYNCMONEY_TEST_DB_NAME = $script:dbName
    $env:SYNCMONEY_TEST_REDIS_PORT = [string]$script:redisPort
    $env:T21_RUN_MARKER = $script:runRoot
    $env:T21_PLUGIN_JAR_PATTERN = ''
    $env:T21_PLUGIN_VERSION = ''
    $env:T21_CANDIDATE_SHA256 = $candidateSha
    $env:T21_BASELINE_SHA256 = $baselineSha
    $env:PGSSLMODE = 'disable'

    for ($nodeIndex = 0; $nodeIndex -lt $script:serverNames.Count; $nodeIndex++) {
        $serverName = $script:serverNames[$nodeIndex]
        Set-ActiveNode $serverName
        $env:SYNCMONEY_TEST_GAME_PORT = [string]$script:nodePorts[$nodeIndex].gamePort
        Push-Location $script:activeNodeRoot
        try {
            $prepare = @(& node (Join-Path $script:activeNodeRoot 'tools\plugdev\prepare.mjs') $PlatformSoftware.ToLowerInvariant() $MinecraftVersion 'sync' 'off' $CandidateVersion 2>&1)
            $prepareExit = $LASTEXITCODE
            $prepare | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath (Join-Path $script:logsRoot ("prepare-profile-node-{0}.txt" -f ($nodeIndex + 1))) -Encoding utf8
            if ($prepareExit -ne 0) { throw "Could not prepare the isolated $PlatformSoftware SYNC profile for node $serverName." }
        } finally { Pop-Location }
    }

    $redisConfigLines = @(
        'bind 127.0.0.1', "port $script:redisPort", 'protected-mode yes', 'daemonize no',
        'supervised no', 'loglevel notice', 'logfile ""', 'databases 16',
        'dir ./', 'dbfilename dump.rdb',
        'save ""', 'appendonly yes', 'appendfilename "appendonly.aof"',
        'appenddirname "appendonlydir"', 'appendfsync always'
    )
    [IO.File]::WriteAllLines($script:redisConfig, $redisConfigLines, [Text.UTF8Encoding]::new($false))
    $null = Invoke-Logged $script:initdb @('-D', $script:pgData, '-U', $script:pgUser, '--no-locale', '-E', 'UTF8', '--auth-local=trust', '--auth-host=trust') (Join-Path $script:logsRoot 'postgres-initdb.txt') 'PostgreSQL initdb'

    $script:currentStage = 'baseline-1.3.2'
    Write-RunnerProgress 'starting isolated postgres for baseline'
    Start-IsolatedPostgres
    Write-RunnerProgress 'creating isolated syncmoney database'
    $createOutput = @(& $script:psql '--no-password' '--no-psqlrc' '--host=127.0.0.1' "--port=$script:pgPort" "--username=$script:pgUser" '--dbname=postgres' '--set=ON_ERROR_STOP=1' "--command=CREATE DATABASE $($script:dbName);" 2>&1)
    $createExit = $LASTEXITCODE
    $createOutput | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath (Join-Path $script:logsRoot 'postgres-createdb.txt') -Encoding utf8
    if ($createExit -ne 0) { throw 'Could not create the isolated Syncmoney PostgreSQL database.' }
    Write-RunnerProgress 'isolated syncmoney database created'
    Start-IsolatedRedis
    $initialRedisSize = Get-RedisDatabaseSize 15
    if ($initialRedisSize -ne 0) { throw "The new isolated Redis DB 15 was not empty before the baseline (observed $initialRedisSize keys)." }

    $nodeA = $script:serverNames[0]
    Start-Paper 'build/libs/Syncmoney-1.3.2.jar' '1.3.2' 'baseline-1.3.2-node-1' $nodeA
    Invoke-VaultWrite 'smaccept write 1000.00 520.00' 'baseline-write' '0.00' '0.00' '1000.00' '520.00'
    Stop-Paper 'baseline-1.3.2-node-1'
    $script:currentStage = 'baseline-config'
    $null = Invoke-ConfigAction 'assert-config'
    $configPath = Join-Path $script:activeNodeRoot '.plugdev\run\plugins\Syncmoney\config.yml'
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) { throw 'Baseline Syncmoney config was not created.' }
    $configText = Get-Content -LiteralPath $configPath -Raw
    if ($configText -notmatch '(?m)^config-version:\s*12\s*$' -or $configText -notmatch '(?m)^\s+mode:\s*["'']?sync["'']?\s*$') {
        throw 'Baseline runtime config did not preserve schema 12 and SYNC authority.'
    }
    $baselineSnapshot = Get-BackendSnapshot 'after baseline 1.3.2 node 1 normal stop'
    Assert-Balances $baselineSnapshot '1000.00' '520.00' 'baseline 1.3.2 node 1 normal stop'
    $script:snapshots.Add($baselineSnapshot)

    for ($nodeIndex = 1; $nodeIndex -lt $script:serverNames.Count; $nodeIndex++) {
        $nodeName = $script:serverNames[$nodeIndex]
        $stage = 'baseline-1.3.2-node-' + ($nodeIndex + 1)
        Start-Paper 'build/libs/Syncmoney-1.3.2.jar' '1.3.2' $stage $nodeName
        $null = Invoke-ConfigAction 'assert-config'
        Invoke-PluginCommand 'smaccept state-read 1000.00 520.00' ($stage + '-shared-state-read') 'T21_READ_PASS expected-a=1000.00 expected-b=520.00'
        Stop-Paper $stage
        $null = Invoke-ConfigAction 'assert-config'
        $snapshot = Get-BackendSnapshot ('after ' + $stage + ' normal stop')
        Assert-Balances $snapshot '1000.00' '520.00' ($stage + ' normal stop')
        foreach ($index in 0, 1) {
            if ($snapshot.accounts[$index].version -ne $baselineSnapshot.accounts[$index].version) {
                throw "Read-only baseline node $nodeName changed the version for $($snapshot.accounts[$index].name)."
            }
        }
        $script:snapshots.Add($snapshot)
    }

    $script:currentStage = 'preupgrade-backup'
    New-Item -ItemType Directory -Path $script:backupsRoot -ErrorAction Stop | Out-Null
    $pgDumpPath = Join-Path $script:backupsRoot 'syncmoney_t21.pgdump'
    $null = Invoke-Logged $script:pgDump @('--no-password', '--host=127.0.0.1', "--port=$script:pgPort", "--username=$script:pgUser", "--dbname=$script:dbName", '--format=custom', "--file=$pgDumpPath") (Join-Path $script:logsRoot 'preupgrade-pg-dump.txt') 'PostgreSQL pre-upgrade backup'
    $null = Invoke-Logged $script:pgRestore @('--list', $pgDumpPath) (Join-Path $script:logsRoot 'preupgrade-pg-restore-list.txt') 'PostgreSQL backup validation'
    $saveResult = ((Invoke-Redis @('-n', '15', 'SAVE')) -join [Environment]::NewLine).Trim()
    if ($saveResult -ne 'OK') { throw 'Redis SAVE did not confirm a pre-upgrade RDB snapshot.' }
    Stop-IsolatedRedis
    Stop-IsolatedPostgres
    $pluginBackup = Join-Path $script:backupsRoot 'plugin-data'
    New-Item -ItemType Directory -Path $pluginBackup -ErrorAction Stop | Out-Null
    for ($nodeIndex = 0; $nodeIndex -lt $script:serverNames.Count; $nodeIndex++) {
        $serverName = $script:serverNames[$nodeIndex]
        $nodeLabel = "node-{0}-{1}" -f ($nodeIndex + 1), $serverName
        $nodePluginData = Join-Path $script:nodeProjects[$serverName] '.plugdev\run\plugins\Syncmoney'
        Copy-Item -LiteralPath $nodePluginData -Destination (Join-Path $pluginBackup $nodeLabel) -Recurse
    }
    Copy-Item -LiteralPath $script:redisData -Destination (Join-Path $script:backupsRoot 'redis-data') -Recurse
    $script:backupManifest = @(
        [ordered]@{ path = 'syncmoney_t21.pgdump'; bytes = (Get-Item -LiteralPath $pgDumpPath).Length; sha256 = (Get-FileHash -LiteralPath $pgDumpPath -Algorithm SHA256).Hash.ToLowerInvariant() }
    ) + @(Get-FileManifest $pluginBackup | ForEach-Object { $_.path = 'plugin-data/' + $_.path; $_ }) +
        @(Get-FileManifest (Join-Path $script:backupsRoot 'redis-data') | ForEach-Object { $_.path = 'redis-data/' + $_.path; $_ })
    if (-not (Test-Path -LiteralPath (Join-Path $script:backupsRoot 'redis-data\dump.rdb') -PathType Leaf)) { throw 'Pre-upgrade Redis RDB backup is missing.' }
    if (-not (Test-Path -LiteralPath (Join-Path $script:backupsRoot 'redis-data\appendonlydir') -PathType Container)) { throw 'Pre-upgrade Redis AOF backup is missing.' }

    $script:currentStage = 'restart-after-backup'
    Start-IsolatedPostgres
    Start-IsolatedRedis
    $afterBackendRestart = Get-BackendSnapshot 'after pre-upgrade backend restart'
    Assert-Balances $afterBackendRestart '1000.00' '520.00' 'pre-upgrade backend restart'
    $script:snapshots.Add($afterBackendRestart)

    . (Join-Path $root 'tools\plugdev\recovery-spool-validator.ps1')
    $currentA = '1000.00'
    $currentB = '520.00'
    $previousCandidateSnapshot = $baselineSnapshot
    for ($nodeIndex = 0; $nodeIndex -lt $script:serverNames.Count; $nodeIndex++) {
        $nodeName = $script:serverNames[$nodeIndex]
        $stage = 'upgrade-1.4-node-' + ($nodeIndex + 1)
        $targetA = if ($nodeIndex -eq 0) { '1337.25' } else {
            Format-T21Amount ([decimal]::Parse($currentA, [Globalization.CultureInfo]::InvariantCulture) + [decimal]107.00)
        }
        $targetB = if ($nodeIndex -eq 0) { '765.50' } else {
            Format-T21Amount ([decimal]::Parse($currentB, [Globalization.CultureInfo]::InvariantCulture) + [decimal]55.00)
        }
        Start-Paper "build/libs/Syncmoney-$CandidateVersion.jar" $CandidateVersion $stage $nodeName
        $null = Invoke-ConfigAction 'assert-config'
        $readStage = $stage + '-shared-state-read'
        $readMarker = 'T21_READ_PASS expected-a=' + $currentA + ' expected-b=' + $currentB
        Invoke-PluginCommand ("smaccept state-read $currentA $currentB") $readStage $readMarker
        Invoke-VaultWrite ("smaccept write $targetA $targetB") ($stage + '-vault-write') $currentA $currentB $targetA $targetB
        Stop-Paper $stage
        $script:currentStage = $stage + '-config-after-stop'
        $null = Invoke-ConfigAction 'assert-config'
        $snapshot = Get-BackendSnapshot ('after ' + $stage + ' normal stop')
        Assert-Balances $snapshot $targetA $targetB ($stage + ' normal stop')
        foreach ($index in 0, 1) {
            if ($snapshot.accounts[$index].version -le $previousCandidateSnapshot.accounts[$index].version) {
                throw "Version did not advance for $($snapshot.accounts[$index].name) on node $nodeName."
            }
        }
        $script:snapshots.Add($snapshot)
        $previousCandidateSnapshot = $snapshot
        $currentA = $targetA
        $currentB = $targetB

        $journalPath = Join-Path $script:activeNodeRoot '.plugdev\run\plugins\Syncmoney\recovery-v1.jsonl'
        $script:recoverySpool = Assert-RecoveryJournalForRollback (Read-RecoveryJournal $journalPath)
        $script:recoverySpoolObservations.Add([ordered]@{
            stage = $stage
            serverName = $nodeName
            path = $journalPath
            sha256 = $script:recoverySpool.sha256
            frameCount = $script:recoverySpool.frameCount
            recordFrames = $script:recoverySpool.recordFrames
            ackFrames = $script:recoverySpool.ackFrames
            pendingCount = $script:recoverySpool.pendingCount
        })
    }
    $candidateSnapshot = $previousCandidateSnapshot

    for ($nodeIndex = 0; $nodeIndex -lt $script:serverNames.Count; $nodeIndex++) {
        $nodeName = $script:serverNames[$nodeIndex]
        $stage = 'rollback-1.3.2-node-' + ($nodeIndex + 1)
        Start-Paper 'build/libs/Syncmoney-1.3.2.jar' '1.3.2' $stage $nodeName
        $null = Invoke-ConfigAction 'assert-config'
        $readMarker = 'T21_ROLLBACK_READ_PASS expected-a=' + $currentA + ' expected-b=' + $currentB
        Invoke-PluginCommand ("smaccept rollback-read $currentA $currentB") ($stage + '-shared-state-read') $readMarker
        Stop-Paper $stage
        $priorJournal = @($script:recoverySpoolObservations | Where-Object { $_.serverName -ceq $nodeName })
        if ($priorJournal.Count -ne 1) { throw "Expected one pre-rollback recovery journal observation for node $nodeName." }
        $rollbackJournal = Assert-RecoveryJournalUnchanged $priorJournal[0].path $priorJournal[0].sha256
        $script:recoverySpoolRollbackObservations.Add([ordered]@{
            stage = $stage
            serverName = $nodeName
            path = $priorJournal[0].path
            sha256 = $rollbackJournal.sha256
            frameCount = $rollbackJournal.frameCount
            recordFrames = $rollbackJournal.recordFrames
            ackFrames = $rollbackJournal.ackFrames
            pendingCount = $rollbackJournal.pendingCount
            unchangedFromPreRollback = $true
            validation = $rollbackJournal.validation
        })
        $script:currentStage = $stage + '-config-after-stop'
        $null = Invoke-ConfigAction 'assert-config'
        $snapshot = Get-BackendSnapshot ('after ' + $stage + ' normal stop')
        Assert-Balances $snapshot $currentA $currentB ($stage + ' normal stop')
        foreach ($index in 0, 1) {
            if ($snapshot.accounts[$index].version -ne $candidateSnapshot.accounts[$index].version) {
                throw "Read-only rollback on node $nodeName changed the version for $($snapshot.accounts[$index].name)."
            }
        }
        $script:snapshots.Add($snapshot)
        $rollbackSnapshot = $snapshot
    }
} catch {
    $script:failure = $_.Exception.Message
} finally {
    if ($script:serverMayExist) {
        $sessionPath = Join-Path $script:activeNodeRoot '.plugdev\session.json'
        try {
            $owned = @(Get-RunOwnedPaperProcesses)
            if ($owned.Count -eq 1 -and (Test-Path -LiteralPath $sessionPath -PathType Leaf)) {
                $session = Get-Content -LiteralPath $sessionPath -Raw | ConvertFrom-Json
                if ([int]$session.pid -eq [int]$owned[0].ProcessId) {
                    Invoke-PlugDev @('server', 'command', 'stop') 'finally-paper-stop.txt' | Out-Null
                    Wait-PaperStopped 15
                } else {
                    throw 'Refusing RCON cleanup because the session PID is not the run-owned Paper process.'
                }
            } else {
                throw 'Graceful Paper cleanup is unavailable because the run-owned process or its PlugDev session is missing.'
            }
            $script:serverMayExist = $false
            $script:cleanupActions.Add([ordered]@{ stage = $script:currentStage; action = 'FINALLY_GRACEFUL_STOP'; processExitVerified = $true; forcedCleanup = $false })
        } catch {
            $gracefulFailure = $_.Exception.Message
            try {
                $fallback = Invoke-RunOwnedPaperCleanupFallback @(Get-RunOwnedPaperProcesses) {
                    param([int]$processId)
                    Stop-Process -Id $processId -Force -ErrorAction Stop
                } {
                    param([int]$processId)
                    Wait-PaperStopped 15
                }
                $script:cleanupActions.Add([ordered]@{
                    stage = $script:currentStage
                    action = $fallback.action
                    processId = $fallback.processId
                    processExitVerified = $true
                    forcedCleanup = ($fallback.action -eq 'FORCE_STOP_VERIFIED')
                    gracefulFailure = $gracefulFailure
                })
                $script:cleanupFailure = "Graceful Paper cleanup failed: $gracefulFailure. Fallback action $($fallback.action) completed; the run remains failed."
                $script:serverMayExist = $false
            } catch {
                $script:cleanupFailure = "Graceful Paper cleanup failed: $gracefulFailure. Owned-process fallback failed: $($_.Exception.Message)"
                $script:cleanupActions.Add([ordered]@{ stage = $script:currentStage; action = 'FALLBACK_FAILED'; forcedCleanup = $false; detail = $script:cleanupFailure })
            }
        }
    }
    $script:currentStage = 'final-cleanup'
    if ($script:redisStarted -or ($null -ne $script:redisProcess -and -not $script:redisProcess.HasExited)) {
        try { Stop-IsolatedRedis }
        catch {
            if ($null -eq $script:cleanupFailure) { $script:cleanupFailure = $_.Exception.Message }
            $script:cleanupActions.Add([ordered]@{ stage = $script:currentStage; backend = 'redis'; action = 'REDIS_SHUTDOWN_FAILED'; detail = $_.Exception.Message })
        }
    }
    if ($script:pgStarted) {
        try { Stop-IsolatedPostgres }
        catch {
            if ($null -eq $script:cleanupFailure) { $script:cleanupFailure = $_.Exception.Message }
            $script:cleanupActions.Add([ordered]@{ stage = $script:currentStage; backend = 'postgresql'; action = 'POSTGRESQL_SHUTDOWN_FAILED'; detail = $_.Exception.Message })
        }
    }
    try { Stop-RunOwnedGradleDaemons }
    catch {
        if ($null -eq $script:cleanupFailure) { $script:cleanupFailure = $_.Exception.Message }
        else { $script:cleanupFailure = "$script:cleanupFailure; $($_.Exception.Message)" }
        $script:cleanupActions.Add([ordered]@{
            stage = $script:currentStage
            action = 'GRADLE_DAEMON_CLEANUP_FAILED'
            processIds = @($script:gradleDaemonsBeforeStop)
            remainingProcessIds = @($script:gradleDaemonsAfterStop)
            stopExitCode = $script:gradleStopExitCode
            detail = $_.Exception.Message
        })
    }
    foreach ($name in $environmentNames) {
        $value = $script:originalEnvironment[$name]
        if ($null -eq $value) { Remove-Item -LiteralPath ("Env:" + $name) -ErrorAction SilentlyContinue }
        else { Set-Item -LiteralPath ("Env:" + $name) -Value $value }
    }
    $expectedSnapshotCount = (3 * $script:serverNames.Count) + 1
    $expectedMutationReceiptCount = 1 + $script:serverNames.Count
    $expectedShutdownCount = 3 * $script:serverNames.Count
    $backendShutdownCoverage = Test-T21BackendShutdownCoverage @($script:backendShutdownObservations)
    $workspaceCount = @($script:serverNames | ForEach-Object { $script:nodeProjects[$_] } | Sort-Object -Unique).Count
    $portCount = @($script:nodePorts | ForEach-Object { $_.gamePort } | Sort-Object -Unique).Count
    $status = if ($null -eq $script:failure -and $null -eq $script:cleanupFailure -and
        $backendShutdownCoverage -and -not $script:redisStarted -and -not $script:pgStarted -and
        $workspaceCount -eq $script:serverNames.Count -and $portCount -eq $script:serverNames.Count -and
        $script:stages.Count -ge (6 * $script:serverNames.Count) -and
        (Test-T21RuntimeObservationCoverage @($script:runtimeObservations) @($script:serverNames)) -and
        (Test-T21ServerJarProvenanceCoverage @($script:runtimeObservations) @($script:serverNames) $serverPath $serverSha $serverBytes $PlatformSoftware $MinecraftVersion $PlatformBuildNumber) -and
        $script:snapshots.Count -eq $expectedSnapshotCount -and
        $script:shutdownObservations.Count -eq $expectedShutdownCount -and
        (Test-T21StageObservationCoverage @($script:shutdownObservations) @($script:serverNames)) -and
        $script:configObservations.Count -ge (6 * $script:serverNames.Count) -and
        (Test-T21RuntimeArtifactPinCoverage @($script:runtimePluginArtifacts) @($script:serverNames)) -and
        $script:vaultMutationReceipts.Count -eq $expectedMutationReceiptCount -and
        $script:recoverySpoolObservations.Count -eq $script:serverNames.Count -and
        $script:recoverySpoolRollbackObservations.Count -eq $script:serverNames.Count -and
        @($script:recoverySpoolRollbackObservations | Where-Object {
            $_.unchangedFromPreRollback -ne $true -or $_.validation -cne 'PASS_FORMAT_AND_CHECKSUM' -or [long]$_.pendingCount -ne 0
        }).Count -eq 0 -and
        $null -ne $script:recoverySpool -and $script:recoverySpool.frameCount -gt 0) { 'PASS_BOUNDED_SUBCASE' } else { 'FAIL' }
    $script:cleanupActions.Add([ordered]@{ serverMayExistAtFinalization = $script:serverMayExist; status = if ($null -eq $script:cleanupFailure) { 'NO_CLEANUP_FAILURE' } else { 'CLEANUP_FAILURE' }; detail = $script:cleanupFailure })
    $result = [ordered]@{
        task = "T21 M01-M03 bounded SYNC PostgreSQL and Redis $($PlatformSoftware.ToLowerInvariant()) $MinecraftVersion`#$PlatformBuildNumber sequential isolated-node upgrade and normal rollback"
        status = $status
        acceptanceScope = [ordered]@{
            classification = 'BOUNDED_SUBCASE'
            description = if ($script:serverNames.Count -eq 2) { "two $($PlatformSoftware.ToLowerInvariant()) $MinecraftVersion`#$PlatformBuildNumber nodes with independent PlugDev projects, plugin data, configs, ports, and recovery journals were upgraded and rolled back sequentially while server processes were stopped between stages; both shared isolated PostgreSQL/Redis state, and controlled Vault acceptance writes were observed through both nodes" } else { "single-node $($PlatformSoftware.ToLowerInvariant()) $MinecraftVersion`#$PlatformBuildNumber SYNC upgrade, Vault writes, normal stop, isolated PostgreSQL/Redis readback, and normal 1.3.2 rollback" }
            m01 = "partial: $($PlatformSoftware.ToLowerInvariant()) $MinecraftVersion`#$PlatformBuildNumber endpoint, isolated backends, synthetic no-player probe per node, isolated per-node plugin-data backups, and bounded SQL/Redis backup evidence only"
            m02 = if ($script:serverNames.Count -eq 2) { 'partial: both node identities individually loaded the candidate, read prior-node state, performed Vault writes with response receipts, and persisted matching SQL/Redis snapshots' } else { 'partial: one node, config preservation, API consumer, and Vault response evidence only' }
            m03 = if ($script:serverNames.Count -eq 2) { 'partial: each old node identity read the latest post-upgrade state on Java 25 without backend reset; each per-node recovery journal was revalidated and byte-identical after rollback' } else { 'partial: normal rollback on Java 25 without backend reset; the per-node recovery journal was revalidated and byte-identical after rollback' }
            controlledQuiescence = [ordered]@{ noPlayerProbe = 'PASS before each normal stop'; nodesRunConcurrently = $false; externalProducerFencing = 'NOT TESTED' }
            shutdownEvidence = 'normal stop exit, run-owned JVM exit, Syncmoney disabled marker, no recognized shutdown failure marker, post-stop SQL/Redis parity, and zero pending recovery records; runtime queue/retry/in-flight success counters are NOT_EXPOSED_BY_RUNTIME'
            notEstablished = @('fleet-wide quiescence or external producer fencing', 'complete queue/retry/in-flight/audit drain counters', 'all mode/backend/runtime combinations', 'Java 21 rejection', 'rollback of SQLite', 'hard-crash or power-loss recovery', 'simultaneous live mixed-version writers', 'Folia/Canvas scheduler behavior', 'licensed CMI runtime')
        }
        evidenceLevel = 'E2_REAL_SERVER_AND_ISOLATED_BACKENDS'
        platform = [ordered]@{
            software = $PlatformSoftware.ToLowerInvariant()
            minecraftVersion = $MinecraftVersion
            build = $PlatformBuildNumber
            serverJarSha256 = $serverSha
            serverJarInput = [ordered]@{ path = $serverPath; bytes = $serverBytes; sha256 = $serverSha }
        }
        runtimeObservations = @($script:runtimeObservations)
        java = $javaBanner.Trim()
        candidate = [ordered]@{ version = $CandidateVersion; sha256 = $candidateSha }
        baseline = [ordered]@{ version = '1.3.2'; sha256 = $baselineSha; sourceCommit = $observedCommit }
        nodeTopology = [ordered]@{
            serverNames = @($script:serverNames)
            projects = @($script:serverNames | ForEach-Object {
                $nodeIndex = [Array]::IndexOf($script:serverNames, $_)
                [ordered]@{ serverName = $_; projectRoot = $script:nodeProjects[$_]; gamePort = $script:nodePorts[$nodeIndex].gamePort; rconPort = $script:nodePorts[$nodeIndex].rconPort }
            })
            independentProjectWorkspaces = $true
            perNodeConfigAndRecoveryData = $true
            sequentiallyStoppedBetweenStages = $true
            simultaneousNodeProcesses = $false
            sharedPostgresDatabase = $script:dbName
            sharedRedisDatabase = 15
        }
        isolatedBackends = [ordered]@{
            bind = '127.0.0.1'
            postgres = [ordered]@{ version = $pgVersion; port = $script:pgPort; database = $script:dbName; user = $script:pgUser }
        redis = [ordered]@{ version = $redisVersion; port = $script:redisPort; database = 15; initialDb15Keys = if ($null -ne $initialRedisSize) { $initialRedisSize } else { $null }; appendonly = $true; appendfsync = 'always' }
        }
        source = [ordered]@{ commit = ((& git -C $root rev-parse HEAD 2>$null | Out-String).Trim()); candidateJarSha256 = $candidateSha }
        artifacts = [ordered]@{
            runner = [ordered]@{ path = 'tools/plugdev/run-sync-upgrade-cycle.ps1'; sha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant() }
            runnerValidation = [ordered]@{ path = 'tools/plugdev/t21-upgrade-validation.ps1'; sha256 = (Get-FileHash -LiteralPath (Join-Path $root 'tools\plugdev\t21-upgrade-validation.ps1') -Algorithm SHA256).Hash.ToLowerInvariant() }
            runnerValidationTest = [ordered]@{ path = 'tools/plugdev/t21-upgrade-validation.test.ps1'; sha256 = (Get-FileHash -LiteralPath (Join-Path $root 'tools\plugdev\t21-upgrade-validation.test.ps1') -Algorithm SHA256).Hash.ToLowerInvariant() }
            recoveryValidator = [ordered]@{ path = 'tools/plugdev/recovery-spool-validator.ps1'; sha256 = (Get-FileHash -LiteralPath (Join-Path $root 'tools\plugdev\recovery-spool-validator.ps1') -Algorithm SHA256).Hash.ToLowerInvariant() }
            recoveryValidatorTest = [ordered]@{ path = 'tools/plugdev/recovery-spool-validator.test.ps1'; sha256 = (Get-FileHash -LiteralPath (Join-Path $root 'tools\plugdev\recovery-spool-validator.test.ps1') -Algorithm SHA256).Hash.ToLowerInvariant() }
            cleanupHelperTest = [ordered]@{ path = 'tools/plugdev/run-sync-upgrade-cleanup.test.ps1'; sha256 = (Get-FileHash -LiteralPath (Join-Path $root 'tools\plugdev\run-sync-upgrade-cleanup.test.ps1') -Algorithm SHA256).Hash.ToLowerInvariant() }
            cleanupHelper = [ordered]@{ path = 'tools/plugdev/run-sync-upgrade-cleanup.ps1'; sha256 = (Get-FileHash -LiteralPath (Join-Path $root 'tools\plugdev\run-sync-upgrade-cleanup.ps1') -Algorithm SHA256).Hash.ToLowerInvariant() }
            acceptanceProbe = [ordered]@{ path = 'build/acceptance/SyncmoneyAcceptance.jar'; sha256 = (Get-FileHash -LiteralPath $acceptancePath -Algorithm SHA256).Hash.ToLowerInvariant() }
            apiConsumer = [ordered]@{ path = 'build/acceptance/SyncmoneyApiConsumer.jar'; sha256 = (Get-FileHash -LiteralPath $apiConsumerPath -Algorithm SHA256).Hash.ToLowerInvariant() }
            plugDevCliSha256 = (Get-FileHash -LiteralPath $script:plugDevCli -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        runtimeDependencies = [ordered]@{ java = $javaBanner.Trim(); postgres = $pgVersion; redis = $redisVersion; pluginArtifactsByStage = @($script:runtimePluginArtifacts) }
        plugDev = [ordered]@{ root = $plugDevRootPath; cliSha256 = (Get-FileHash -LiteralPath $script:plugDevCli -Algorithm SHA256).Hash.ToLowerInvariant() }
        acceptanceAccounts = $script:accounts
        config = [ordered]@{ schema = 12; economyMode = 'sync'; redisDatabase = 15; generatedProfilePort = $script:gamePort; generatedProfilePorts = @($script:nodePorts) }
        configObservations = @($script:configObservations)
        shutdownObservations = @($script:shutdownObservations)
        backendShutdownObservations = @($script:backendShutdownObservations)
        vaultMutationReceipts = @($script:vaultMutationReceipts)
        backupManifest = @($script:backupManifest)
        snapshots = @($script:snapshots)
        recoverySpool = $script:recoverySpool
        recoverySpoolObservations = @($script:recoverySpoolObservations)
        recoverySpoolRollbackObservations = @($script:recoverySpoolRollbackObservations)
        gradleDaemonCleanup = [ordered]@{
            processIdsBeforeStop = @($script:gradleDaemonsBeforeStop)
            stopExitCode = $script:gradleStopExitCode
            processIdsAfterStop = @($script:gradleDaemonsAfterStop)
        }
        stages = @($script:stages)
        cleanupActions = @($script:cleanupActions)
        failure = $script:failure
        cleanupFailure = $script:cleanupFailure
        runDirectory = $script:runRoot
    }
    $result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $script:runRoot 'result.json') -Encoding utf8
}

Get-Content -LiteralPath (Join-Path $script:runRoot 'result.json')
if ($status -ne 'PASS_BOUNDED_SUBCASE') {
    if ($null -ne $script:cleanupFailure) { throw "T21 SYNC upgrade cycle failed. Cleanup: $($script:cleanupFailure)" }
    throw "T21 SYNC upgrade cycle failed: $($script:failure)"
}
