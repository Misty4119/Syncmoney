param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('paper-1216-min', 'paper-263-max', 'folia-1216-min', 'folia-262-max', 'canvas-1218-min', 'canvas-263-max')]
    [string]$Endpoint,
    [string]$JavaHome = 'C:\Program Files\Java\jdk-25.0.4',
    [string]$PlugDevRoot = 'C:\Users\margo\IdeaProjects\plugdev-main',
    [string]$MineflayerRoot = 'C:\Users\margo\IdeaProjects\mineflayer-master',
    [int]$RedisPauseMilliseconds = 2500,
    [switch]$CrossOwner,
    [switch]$Guard
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$pins = @{
    'paper-1216-min' = [ordered]@{ software = 'paper'; version = '1.21.6'; build = 48; artifact = 'paper-1.21.6-48.jar'; sha256 = '35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214' }
    'paper-263-max' = [ordered]@{ software = 'paper'; version = '26.3'; build = 166; artifact = 'paper-26.3-166.jar'; sha256 = 'dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd' }
    'folia-1216-min' = [ordered]@{ software = 'folia'; version = '1.21.6'; build = 6; artifact = 'folia-1.21.6-6.jar'; sha256 = '917a8abaa542c1d0ef0e969693e227a7bfe1c42e671aaa806309e00a64efc234' }
    'folia-262-max' = [ordered]@{ software = 'folia'; version = '26.2'; build = 7; artifact = 'folia-26.2-7.jar'; sha256 = '128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3' }
    'canvas-1218-min' = [ordered]@{ software = 'canvas'; version = '1.21.8'; build = 603; artifact = 'canvas-1.21.8-603.jar'; sha256 = '6577181e4bc8ac40a7a48ff00e262f2ac7a798f48ada446a07eb06618dc36f1b' }
    'canvas-263-max' = [ordered]@{ software = 'canvas'; version = '26.3'; build = 972; artifact = 'canvas-26.3-972.jar'; sha256 = 'aac025fe36a61f84fecb04de74e94bbf933c7081c4c88f13a9d4b96de42243f5' }
}

$pin = $pins[$Endpoint]
if ($CrossOwner.IsPresent -and $pin.software -ne 'folia') {
    throw '-CrossOwner is only supported for Folia endpoints.'
}
if ($Guard.IsPresent -and $pin.software -ne 'canvas') {
    throw '-Guard is only supported for Canvas endpoints.'
}
if ($Guard.IsPresent -and $CrossOwner.IsPresent) {
    throw '-Guard and -CrossOwner select separate platform-specific cases and cannot be combined.'
}
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$candidateVersion = '1.4.0-alpha.1'
$candidateJar = Join-Path $root "build\libs\Syncmoney-$candidateVersion.jar"
$acceptanceJar = Join-Path $root 'build\acceptance\SyncmoneyAcceptance.jar'
$apiConsumerJar = Join-Path $root 'build\acceptance\SyncmoneyApiConsumer.jar'
$serverJar = Join-Path $root ('build\reports\foundation\T15-runtime-pins\artifacts\' + $pin.artifact)
$prepareScript = Join-Path $root 'tools\plugdev\prepare.mjs'
$clientScript = Join-Path $root 'tools\plugdev\player-scheduler-lifecycle.mjs'
$assertionModule = Join-Path $root 'tools\plugdev\player-scheduler-lifecycle-assertions.mjs'
$assertionTest = Join-Path $root 'tools\plugdev\player-scheduler-lifecycle-assertions.test.mjs'
foreach ($path in @($candidateJar, $acceptanceJar, $apiConsumerJar, $serverJar, $prepareScript, $clientScript, $assertionModule, $assertionTest)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "T15 scheduler input is missing: $path" }
}

$serverSha = (Get-FileHash -LiteralPath $serverJar -Algorithm SHA256).Hash.ToLowerInvariant()
if ($serverSha -cne $pin.sha256) { throw "Exact server artifact hash mismatch: expected $($pin.sha256), observed $serverSha." }
$candidateSha = (Get-FileHash -LiteralPath $candidateJar -Algorithm SHA256).Hash.ToLowerInvariant()
if ($candidateSha -cne '5fdc79aae7ae715f6fcdb3cddbb858ba001855420755c46458102979ca495dbd') {
    throw "Candidate artifact does not match the T21 source pin: $candidateSha"
}

$javaHomePath = (Resolve-Path -LiteralPath $JavaHome).Path
$java = Join-Path $javaHomePath 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw "Java executable not found: $java" }
$javaVersion = (& $java -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "25(?:\.|"|\+)') { throw "T21 requires Java 25; observed: $javaVersion" }

$plugDevRootPath = (Resolve-Path -LiteralPath $PlugDevRoot).Path
$plugDevCli = Join-Path $plugDevRootPath 'packages\cli\dist\cli.js'
if (-not (Test-Path -LiteralPath $plugDevCli -PathType Leaf)) { throw "Local PlugDev CLI was not found: $plugDevCli" }
$plugDevPackage = Get-Content -LiteralPath (Join-Path $plugDevRootPath 'packages\cli\package.json') -Raw | ConvertFrom-Json
$mineflayerRootPath = (Resolve-Path -LiteralPath $MineflayerRoot).Path
$mineflayerPackagePath = Join-Path $mineflayerRootPath 'package.json'
if (-not (Test-Path -LiteralPath $mineflayerPackagePath -PathType Leaf)) { throw "Local Mineflayer checkout was not found: $mineflayerPackagePath" }
$mineflayerPackage = Get-Content -LiteralPath $mineflayerPackagePath -Raw | ConvertFrom-Json

$redisServerCommand = Get-Command redis-server.exe, redis-server -ErrorAction SilentlyContinue | Select-Object -First 1
$redisCliCommand = Get-Command redis-cli.exe, redis-cli -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $redisServerCommand -or $null -eq $redisCliCommand -or
    $redisServerCommand.CommandType -ne 'Application' -or $redisCliCommand.CommandType -ne 'Application') {
    throw 'T15 scheduler validation requires local redis-server and redis-cli executables.'
}
$redisServer = $redisServerCommand.Source
$redisCli = $redisCliCommand.Source
if ($redisServer -match '(?i)\\scoop\\shims\\redis-server\.exe$') {
    $scoopRoot = Split-Path -Parent (Split-Path -Parent $redisServer)
    $realPath = Join-Path $scoopRoot 'apps\redis\current\redis-server.exe'
    if (Test-Path -LiteralPath $realPath -PathType Leaf) { $redisServer = (Resolve-Path -LiteralPath $realPath).Path }
}
if ($redisCli -match '(?i)\\scoop\\shims\\redis-cli\.exe$') {
    $scoopRoot = Split-Path -Parent (Split-Path -Parent $redisCli)
    $realPath = Join-Path $scoopRoot 'apps\redis\current\redis-cli.exe'
    if (Test-Path -LiteralPath $realPath -PathType Leaf) { $redisCli = (Resolve-Path -LiteralPath $realPath).Path }
}
$redisServerVersion = (& $redisServer --version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Could not read local Redis server version.' }

$reportsRoot = [IO.Path]::GetFullPath((Join-Path $root 'build\reports\foundation\T21-player-scheduler'))
if (-not (Test-Path -LiteralPath $reportsRoot -PathType Container)) { New-Item -ItemType Directory -Path $reportsRoot | Out-Null }
$runId = "$Endpoint-$(Get-Date -Format 'yyyyMMdd-HHmmss')-$([guid]::NewGuid().ToString('N'))"
$runRoot = [IO.Path]::GetFullPath((Join-Path $reportsRoot $runId))
if (-not $runRoot.StartsWith($reportsRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $runRoot)) {
    throw "Refusing an invalid or reused run directory: $runRoot"
}
New-Item -ItemType Directory -Path $runRoot | Out-Null
$logsRoot = Join-Path $runRoot 'logs'
$projectRoot = Join-Path $runRoot 'project'
$redisRoot = Join-Path $runRoot 'redis'
$redisData = Join-Path $redisRoot 'data'
$plugDevHome = Join-Path $runRoot 'plugdev-home'
$gradleHome = Join-Path $runRoot 'gradle-user-home'
New-Item -ItemType Directory -Path $logsRoot, $projectRoot, $redisRoot, $redisData, $plugDevHome, $gradleHome | Out-Null
function Test-LocalPortCanBind([int]$probePort) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $probePort)
    try {
        $listener.Start()
        return $true
    } catch [Net.Sockets.SocketException] {
        return $false
    } finally { $listener.Stop() }
}

function Get-AvailableLocalPort([int]$minimum, [int]$maximum, [int[]]$offsets = @()) {
    for ($attempt = 0; $attempt -lt 128; $attempt++) {
        $candidate = Get-Random -Minimum $minimum -Maximum $maximum
        $portsToProbe = @($candidate) + @($offsets | ForEach-Object { $candidate + $_ })
        $bindable = $true
        foreach ($probePort in $portsToProbe) {
            if (-not (Test-LocalPortCanBind $probePort)) { $bindable = $false; break }
        }
        if ($bindable) { return $candidate }
    }
    throw "Could not find a bindable local port between $minimum and $($maximum - 1)."
}

$gamePort = Get-AvailableLocalPort 25000 39000 @(0, 10000)
$rconPort = $gamePort + 10000
$redisPort = Get-AvailableLocalPort 50000 60000
$runMarker = [IO.Path]::GetFullPath($runRoot)
$redisConfigName = "redis-$runId.conf"
$redisConfigPath = [IO.Path]::GetFullPath((Join-Path $redisRoot $redisConfigName))
$redisDataConfig = $redisData.Replace('\', '/')
$redisLogConfig = (Join-Path $logsRoot 'redis-server.log').Replace('\', '/')
@"
bind 127.0.0.1
protected-mode yes
port $redisPort
daemonize no
supervised no
loglevel notice
logfile "$redisLogConfig"
databases 16
save ""
appendonly yes
appendfsync always
appenddirname "appendonlydir"
appendfilename "appendonly.aof"
dir "$redisDataConfig"
dbfilename "dump.rdb"
"@ | Set-Content -LiteralPath $redisConfigPath -Encoding ascii

$envNames = @('JAVA_HOME', 'Path', 'PLUGDEV_ROOT', 'PLUGDEV_HOME', 'GRADLE_USER_HOME', 'SYNCMONEY_TEST_SERVER_JAR',
    'SYNCMONEY_CANDIDATE_VERSION', 'SYNCMONEY_TEST_GAME_PORT', 'SYNCMONEY_TEST_REDIS_PORT', 'T21_PLAYER_SCHEDULER_RUN')
$savedEnv = [ordered]@{}
foreach ($name in $envNames) { $savedEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$script:redisProcess = $null
$script:redisProcessId = $null
$script:serverProcessId = $null
$script:normalServerStopExitCode = $null
$script:normalRedisStopExitCode = $null
$script:redisExitCode = $null
$script:forcedCleanup = $false
$script:failure = $null
$script:cleanupFailure = $null
$script:result = $null
$script:gradleCleanup = $null
$script:guardConfigSha256 = $null

function Write-RunnerLog([string]$message) {
    $line = "[$([DateTime]::UtcNow.ToString('o'))] $message"
    Add-Content -LiteralPath (Join-Path $logsRoot 'runner.log') -Value $line
    Write-Output $line
}

function Assert-LocalPortUnused([int]$port) {
    if (-not (Test-LocalPortCanBind $port)) { throw "Local TCP port $port is in use or excluded by the operating system." }
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $client.Connect('127.0.0.1', $port)
        throw "Refusing already-listening test port $port."
    } catch [Net.Sockets.SocketException] {
        if ($_.Exception.SocketErrorCode -ne [Net.Sockets.SocketError]::ConnectionRefused) {
            throw "Could not establish that test port $port is unused: $($_.Exception.Message)"
        }
    } finally { $client.Dispose() }
}

function Get-OwnedServerProcesses {
    @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
        $_.Name -in @('java.exe', 'javaw.exe') -and $_.CommandLine -and
        $_.CommandLine.IndexOf('-Dsyncmoney.t21.player-scheduler.run=' + $runMarker, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $_.CommandLine -notmatch 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon'
    })
}

function Get-OwnedRedisProcesses {
    @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
        $_.Name -match '^redis-server(?:\.exe)?$' -and $_.CommandLine -and
        $_.CommandLine.IndexOf($redisConfigName, [StringComparison]::OrdinalIgnoreCase) -ge 0
    })
}

function Invoke-Redis([string[]]$command, [switch]$Raw) {
    $arguments = @('-h', '127.0.0.1', '-p', [string]$redisPort, '-n', '15')
    if ($Raw) { $arguments += '--raw' }
    $arguments += $command
    $output = @(& $redisCli @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw "redis-cli exited $exitCode for $($command -join ' ')." }
    return ($output | ForEach-Object { $_.ToString().TrimEnd() }) -join "`n"
}

function Invoke-PlugDev([string[]]$arguments, [string]$logName) {
    Push-Location $projectRoot
    try {
        $output = @(& node $plugDevCli @arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally { Pop-Location }
    $output | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath (Join-Path $logsRoot $logName) -Encoding utf8
    if ($exitCode -ne 0) { throw "PlugDev '$($arguments -join ' ')' failed with exit $exitCode; see logs/$logName." }
    return $exitCode
}

function Wait-ServerLog([string]$pattern, [int]$timeoutSeconds, [string]$description) {
    $path = Join-Path $projectRoot '.plugdev\run\logs\latest.log'
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    do {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $contents = Get-Content -LiteralPath $path -Raw
            if ($contents -match $pattern) { return $contents }
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $description."
}

function Wait-ServerExit([int]$timeoutSeconds = 60) {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    do {
        if (@(Get-OwnedServerProcesses).Count -eq 0) { return }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'The run-marked server JVM did not exit after normal stop.'
}

function Invoke-Client([string]$scenario) {
    $logPath = Join-Path $logsRoot "$scenario-mineflayer.log"
    $clientArgs = @($clientScript, $scenario, $pin.version, [string]$gamePort, [string]$redisPort,
        [string]$RedisPauseMilliseconds, $redisCli, $mineflayerRootPath)
    $output = @(& node @clientArgs 2>&1)
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath $logPath -Encoding utf8
    if ($exitCode -ne 0) { throw "Mineflayer $scenario probe failed with exit $exitCode; see $logPath." }
    return [ordered]@{ exitCode = $exitCode; logPath = $logPath; logSha256 = (Get-FileHash $logPath -Algorithm SHA256).Hash.ToLowerInvariant(); lines = @($output | ForEach-Object { $_.ToString() }) }
}

function Assert-GuardOnlyProfile {
    $settingsPath = Join-Path $projectRoot '.plugdev\run\plugins\Syncmoney\config.yml'
    if (-not (Test-Path -LiteralPath $settingsPath -PathType Leaf)) { throw 'Prepared Syncmoney config is missing for the guard-only case.' }
    $auditScript = Join-Path $logsRoot 'audit-guard-profile.cjs'
    @'
const fs = require("node:fs");
const path = require("node:path");
const { createRequire } = require("node:module");
const yaml = createRequire(path.join(process.env.PLUGDEV_ROOT, "package.json"))("yaml");
const config = yaml.parse(fs.readFileSync(process.argv[2], "utf8"));
const required = [
  [config.economy?.mode === "local_redis", "LOCAL_REDIS mode"],
  [config["db-enabled"] === false && config.database?.enabled === false, "SQL disabled"],
  [config["pubsub-enabled"] === true && config.redis?.enabled === true, "Redis enabled"],
  [config["circuit-breaker"]?.enabled === false, "global circuit breaker disabled"],
  [config["circuit-breaker"]?.["player-protection"]?.enabled === true, "per-player protection enabled"],
  [config["circuit-breaker"]?.["player-protection"]?.["global-lock"]?.enabled === false, "global lock monitor disabled"],
  [config["shadow-sync"]?.enabled === false, "Shadow Sync disabled"],
  [config.audit?.enabled === false, "audit disabled"],
  [config["transfer-guard"]?.enabled === false, "transfer guard disabled"],
  [config["web-admin"]?.enabled === false, "Web Admin disabled"],
  [config["discord-webhook"]?.enabled === false, "Discord webhook disabled"],
];
const failed = required.filter(([condition]) => !condition).map(([, name]) => name);
if (failed.length) throw new Error(`Guard-only config failed: ${failed.join(", ")}`);
console.log("GUARD_PROFILE_PASS mode=local_redis player-protection=on optional-modules=off");
'@ | Set-Content -LiteralPath $auditScript -Encoding utf8
    $output = @(& node $auditScript $settingsPath 2>&1)
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath (Join-Path $logsRoot 'guard-profile-audit.txt') -Encoding utf8
    if ($exitCode -ne 0 -or -not (($output | ForEach-Object { $_.ToString() }) -contains 'GUARD_PROFILE_PASS mode=local_redis player-protection=on optional-modules=off')) {
        throw "Prepared config did not isolate the LOCAL_REDIS per-player guard: $($output -join ' ')"
    }
    return (Get-FileHash -LiteralPath $settingsPath -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Add-ServerRunIdentity {
    $editor = Join-Path $projectRoot 'add-run-identity.cjs'
    @'
const fs = require("node:fs");
const path = require("node:path");
const { createRequire } = require("node:module");
const yaml = createRequire(path.join(process.env.PLUGDEV_ROOT, "package.json"))("yaml");
const file = path.resolve(".plugdev/profile.yml");
const profile = yaml.parse(fs.readFileSync(file, "utf8"));
profile.jvm ??= {};
profile.jvm.args ??= [];
profile.jvm.args.push(`-Dsyncmoney.t21.player-scheduler.run=${process.env.T21_PLAYER_SCHEDULER_RUN}`);
fs.writeFileSync(file, yaml.stringify(profile), "utf8");
console.log("run marker configured");
'@ | Set-Content -LiteralPath $editor -Encoding utf8
    Push-Location $projectRoot
    try {
        $output = @(& node $editor 2>&1)
        $code = $LASTEXITCODE
    } finally { Pop-Location }
    $output | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath (Join-Path $logsRoot 'profile-run-marker.txt') -Encoding utf8
    if ($code -ne 0) { throw 'Could not add a unique run marker to the PlugDev JVM arguments.' }
}

function Stop-IsolatedRedis {
    if ($null -eq $script:redisProcess) { return }
    $script:redisProcess.Refresh()
    if ($script:redisProcess.HasExited) { throw 'Redis exited before its requested normal shutdown.' }
    $owned = @(Get-OwnedRedisProcesses)
    if ($owned.Count -ne 1 -or [int]$owned[0].ProcessId -ne $script:redisProcessId) { throw 'Refusing Redis shutdown because the process identity is ambiguous.' }
    $output = @(& $redisCli '-h' '127.0.0.1' '-p' ([string]$redisPort) 'SHUTDOWN' 'SAVE' 2>&1)
    $script:normalRedisStopExitCode = $LASTEXITCODE
    $output | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath (Join-Path $logsRoot 'redis-shutdown.txt') -Encoding utf8
    $script:redisProcess.WaitForExit(15000) | Out-Null
    $script:redisProcess.Refresh()
    if (-not $script:redisProcess.HasExited) { throw 'Isolated Redis did not exit after SHUTDOWN SAVE.' }
    $script:redisExitCode = [int]$script:redisProcess.ExitCode
    if ($script:normalRedisStopExitCode -ne 0 -or $script:redisExitCode -ne 0) {
        throw "Redis normal shutdown failed (CLI=$($script:normalRedisStopExitCode), process=$($script:redisExitCode))."
    }
}

function Stop-OwnedServerAfterFailure {
    $serverOwned = @(Get-OwnedServerProcesses)
    if ($serverOwned.Count -gt 1) { throw 'Refusing server cleanup because multiple run-marked JVMs exist.' }
    if ($serverOwned.Count -eq 1) {
        $serverProcessIdForCleanup = [int]$serverOwned[0].ProcessId
        $sessionPath = Join-Path $projectRoot '.plugdev\session.json'
        if (Test-Path -LiteralPath $sessionPath -PathType Leaf) {
            $session = Get-Content -LiteralPath $sessionPath -Raw | ConvertFrom-Json
            if ([int]$session.pid -ne $serverProcessIdForCleanup) { throw 'Refusing cleanup because PlugDev PID differs from the uniquely marked server JVM.' }
        }
        try {
            $null = Invoke-PlugDev @('server', 'command', 'stop') 'cleanup-server-stop.txt'
            Wait-ServerExit 35
        } catch {
            $remaining = @(Get-OwnedServerProcesses)
            if ($remaining.Count -ne 1 -or [int]$remaining[0].ProcessId -ne $serverProcessIdForCleanup) { throw 'Refusing forced cleanup after run identity changed.' }
            Stop-Process -Id $serverProcessIdForCleanup -Force -ErrorAction Stop
            $deadline = [DateTime]::UtcNow.AddSeconds(20)
            do {
                if (@(Get-OwnedServerProcesses).Count -eq 0) { $script:forcedCleanup = $true; break }
                Start-Sleep -Milliseconds 250
            } while ([DateTime]::UtcNow -lt $deadline)
            if (@(Get-OwnedServerProcesses).Count -ne 0) { throw 'Could not verify exit of the uniquely marked server JVM.' }
        }
        $serverLogPath = Join-Path $projectRoot '.plugdev\run\logs\latest.log'
        if (Test-Path -LiteralPath $serverLogPath -PathType Leaf) {
            Copy-Item -LiteralPath $serverLogPath -Destination (Join-Path $logsRoot 'server.log') -ErrorAction SilentlyContinue
        }
    }

}

function Stop-OwnedRedisAfterFailure {
    if ($null -ne $script:redisProcess) {
        $script:redisProcess.Refresh()
        if (-not $script:redisProcess.HasExited) {
            $redisOwned = @(Get-OwnedRedisProcesses)
            if ($redisOwned.Count -ne 1 -or [int]$redisOwned[0].ProcessId -ne $script:redisProcessId) {
                throw 'Refusing Redis cleanup because its unique config/PID identity is not verified.'
            }
            try { Stop-IsolatedRedis }
            catch {
                $redisRemaining = @(Get-OwnedRedisProcesses)
                if ($redisRemaining.Count -ne 1 -or [int]$redisRemaining[0].ProcessId -ne $script:redisProcessId) {
                    throw 'Refusing forced Redis cleanup after its run-specific identity changed.'
                }
                Stop-Process -Id $script:redisProcessId -Force -ErrorAction Stop
                $script:redisProcess.WaitForExit(15000) | Out-Null
                $script:redisProcess.Refresh()
                if (-not $script:redisProcess.HasExited) { throw 'Could not verify exit of the uniquely identified Redis process.' }
                $script:forcedCleanup = $true
            }
        }
    }
}

function Stop-OwnedGradleDaemons {
    $homePath = [IO.Path]::GetFullPath($gradleHome)
    $owned = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
        $_.Name -in @('java.exe', 'javaw.exe') -and $_.CommandLine -and
        $_.CommandLine.IndexOf($homePath, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $_.CommandLine -match 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon'
    })
    if ($owned.Count -eq 0) { return [ordered]@{ processIds = @(); stopExitCode = $null; processExitVerified = $true } }
    Push-Location $projectRoot
    try {
        $null = & (Join-Path $projectRoot 'gradlew.bat') --stop *> (Join-Path $logsRoot 'gradle-daemon-stop.txt')
        $code = $LASTEXITCODE
    } finally { Pop-Location }
    if ($code -ne 0) { throw "Isolated Gradle --stop exited $code." }
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        $remaining = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
            $_.Name -in @('java.exe', 'javaw.exe') -and $_.CommandLine -and
            $_.CommandLine.IndexOf($homePath, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
            $_.CommandLine -match 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon'
        })
        if ($remaining.Count -eq 0) { return [ordered]@{ processIds = @($owned | ForEach-Object { [int]$_.ProcessId }); stopExitCode = $code; processExitVerified = $true } }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'A run-owned Gradle daemon remained after isolated --stop.'
}

function Assert-ObservedScenario(
    [string[]]$serverLines, [object]$teleport, [object]$logout, [object]$crossOwnerClient = $null) {
    if (@($serverLines | Where-Object { $_ -match 'T15_LIFECYCLE_FAIL' }).Count -gt 0) { throw 'Server logged T15_LIFECYCLE_FAIL.' }
    if (@($serverLines | Where-Object { $_ -match 'API CLASSLOADER PASS' }).Count -ne 1) { throw 'Expected exactly one API CLASSLOADER PASS marker.' }
    $writes = @($serverLines | Where-Object { $_ -match 'T15_ACCEPTED_WRITE ' })
    if ($writes.Count -ne 4) { throw "Expected four successful Vault writes, observed $($writes.Count)." }
    foreach ($scenario in @('teleport', 'logout')) {
        foreach ($index in @(1, 2)) {
            $pattern = "T15_ACCEPTED_WRITE scenario=$scenario index=$index .* amount=1\.00 response=SUCCESS"
            if (@($writes | Where-Object { $_ -match $pattern }).Count -ne 1) { throw "Missing/duplicate successful $scenario Vault write $index." }
        }
    }

    $pending = [ordered]@{}
    $threads = [ordered]@{}
    foreach ($scenario in @('teleport', 'logout')) {
        $lines = @($serverLines | Where-Object { $_ -match "T15_PENDING_AT_BOUNDARY scenario=$scenario " })
        if ($lines.Count -ne 1) { throw "Expected one $scenario pending-write boundary marker, saw $($lines.Count)." }
        $count = [regex]::Match($lines[0], 'pending=(\d+)')
        if (-not $count.Success -or [int]$count.Groups[1].Value -lt 2) { throw "$scenario did not retain two pending writes at the lifecycle boundary." }
        $pending[$scenario] = [int]$count.Groups[1].Value
        $action = @($serverLines | Where-Object { $_ -match "T15_BOUNDARY_ACTION scenario=$scenario " })
        if ($action.Count -ne 1) { throw "Expected one $scenario entity-scheduler action marker, saw $($action.Count)." }
        $thread = [regex]::Match($action[0], "T15_BOUNDARY_ACTION scenario=$scenario thread=(.+)$")
        if (-not $thread.Success -or [string]::IsNullOrWhiteSpace($thread.Groups[1].Value)) { throw "$scenario scheduler thread marker is empty." }
        $threads[$scenario] = $thread.Groups[1].Value.Trim()
    }

    $teleportEventLines = @($serverLines | Where-Object { $_ -match 'T15_TELEPORT_EVENT .* cause=PLUGIN' })
    $teleportEventStates = @()
    foreach ($line in $teleportEventLines) {
        $eventState = [regex]::Match($line, 'cancelled=(true|false)')
        if (-not $eventState.Success) { throw 'Teleport event omitted its cancellation state.' }
        $teleportEventStates += ($eventState.Groups[1].Value -ceq 'true')
    }
    $futureEvidenceLines = @($serverLines | Where-Object { $_ -match 'T15_TELEPORT_FUTURE_(?:RESULT|FAILURE)' })
    $futureEvidenceJson = ConvertTo-Json -InputObject ([ordered]@{ logLines = @($futureEvidenceLines) }) -Compress
    $futureEvidenceOutput = @(& node $assertionModule '--has-single-successful-teleport-future-json' $futureEvidenceJson 2>&1)
    $futureEvidenceExit = $LASTEXITCODE
    $futureSuccessText = ($futureEvidenceOutput | ForEach-Object { $_.ToString() } | Select-Object -Last 1).Trim()
    if ($futureEvidenceExit -ne 0 -or $futureSuccessText -cne 'true') {
        throw "teleportAsync did not produce exactly one successful future result: $($futureEvidenceOutput -join ' ')."
    }
    $futureSuccess = $true
    $entitySchedulerReached = @($serverLines | Where-Object { $_ -match 'T15_TELEPORT_REACHED .* verifiedBy=entity-scheduler-future' }).Count -eq 1
    $teleportEvidenceJson = ConvertTo-Json -InputObject ([ordered]@{
        eventCancellationStates = @($teleportEventStates)
        teleportFutureSucceeded = $futureSuccess
        entitySchedulerFutureConfirmed = ($futureSuccess -and $entitySchedulerReached)
    }) -Compress
    $teleportAssertionOutput = @(& node $assertionModule '--classify-teleport-outcome-json' $teleportEvidenceJson 2>&1)
    $teleportAssertionExit = $LASTEXITCODE
    if ($teleportAssertionExit -ne 0) { throw "Invalid teleport lifecycle sequence: $($teleportAssertionOutput -join ' ')." }
    $teleportDisposition = ($teleportAssertionOutput | ForEach-Object { $_.ToString() } | Select-Object -Last 1).Trim()
    if (@($serverLines | Where-Object { $_ -match 'T15_TELEPORT_REACHED .* world=world_nether' }).Count -ne 1) { throw 'Server did not confirm Nether arrival.' }
    if (-not ((@($teleport.lines) -join "`n") -match 'BOT_NETHER_ARRIVAL dimension=(?:the_nether|nether)(?:\s|$)')) { throw 'Mineflayer did not observe the Nether dimension.' }
    $logoutClientLog = @($logout.lines) -join "`n"
    $clientObservedKickReason = $logoutClientLog -match '(?m)^BOT_KICK_REASON_PASS reason=T15 accepted-write logout scenario$'
    $clientObservedTransportAbort = $logoutClientLog -match '(?m)^BOT_LOGOUT_ABORTED_AFTER_COMMAND code=ECONNABORTED$'
    if (-not $clientObservedKickReason -and -not $clientObservedTransportAbort) { throw 'Mineflayer observed neither the expected kick packet nor the logout socket-close race.' }
    $kickEvidenceLines = @($serverLines | Where-Object { $_ -match 'AcceptanceA lost connection: T15 accepted-write logout scenario' })
    $kickEvidenceJson = ConvertTo-Json -InputObject ([ordered]@{ logLines = @($kickEvidenceLines); playerName = 'AcceptanceA' }) -Compress
    $kickEvidenceOutput = @(& node $assertionModule '--has-exact-server-kick-json' $kickEvidenceJson 2>&1)
    $kickEvidenceExit = $LASTEXITCODE
    $exactKickVerified = ($kickEvidenceOutput | ForEach-Object { $_.ToString() } | Select-Object -Last 1).Trim() -ceq 'true'
    if ($kickEvidenceExit -ne 0 -or -not $exactKickVerified) { throw 'Expected exact logout reason for AcceptanceA is absent, duplicated, or suffixed.' }
    $crossOwnerEvidence = $null
    if ($null -ne $crossOwnerClient) {
        $ownerEvidenceLines = @($serverLines | Where-Object { $_ -match 'T21_(?:REGION_OWNER(?:\b|_)|CROSS_OWNER_)' })
        $ownerEvidenceJson = ConvertTo-Json -InputObject ([ordered]@{ logLines = @($ownerEvidenceLines) }) -Compress
        $ownerEvidenceOutput = @(& node $assertionModule '--has-two-region-ownership-proof-json' $ownerEvidenceJson 2>&1)
        $ownerEvidenceExit = $LASTEXITCODE
        $ownerEvidenceResult = ($ownerEvidenceOutput | ForEach-Object { $_.ToString() } | Select-Object -Last 1).Trim()
        if ($ownerEvidenceExit -ne 0 -or $ownerEvidenceResult -cne 'true') {
            throw "Two-region owner proof failed offline validation: $($ownerEvidenceOutput -join ' ')."
        }
        $clientPassMarkers = @($crossOwnerClient.lines | Where-Object { $_ -match '^BOT_CROSS_OWNER_PASS player=(AcceptanceA|AcceptanceB)$' })
        $passedClients = @($clientPassMarkers | ForEach-Object { if ($_ -match '^BOT_CROSS_OWNER_PASS player=(AcceptanceA|AcceptanceB)$') { $Matches[1] } } | Sort-Object -Unique)
        if ($clientPassMarkers.Count -ne 2 -or $passedClients.Count -ne 2 -or
            $passedClients[0] -cne 'AcceptanceA' -or $passedClients[1] -cne 'AcceptanceB') {
            throw 'Both live clients must receive their own owner-proof completion marker.'
        }
        $crossOwnerEvidence = [ordered]@{
            serverOwnerProof = $true
            clientCompletionMessage = $true
            clientLogPath = $crossOwnerClient.logPath
            clientLogSha256 = $crossOwnerClient.logSha256
            ownerEvidenceLines = $ownerEvidenceLines
        }
    }
    return [ordered]@{ acceptedWriteMarkers = 4; pendingBoundaries = $pending; actionThreads = $threads; teleportDisposition = $teleportDisposition; teleportEventCancellationStates = $teleportEventStates; teleportFutureSucceeded = $futureSuccess; deferredTeleport = ($teleportDisposition -eq 'deferred-retry'); serverNetherArrival = $true; clientNetherArrival = $true; expectedLogoutKick = $true; clientObservedExpectedKickPacket = $clientObservedKickReason; clientObservedExpectedLogoutAbort = $clientObservedTransportAbort; exactServerKickReasonVerified = $exactKickVerified; crossOwner = $crossOwnerEvidence }
}

try {
    if ($RedisPauseMilliseconds -lt 100 -or $RedisPauseMilliseconds -gt 30000) { throw 'RedisPauseMilliseconds must be 100..30000.' }
    foreach ($port in @($gamePort, $rconPort, $redisPort)) { Assert-LocalPortUnused $port }
    $env:JAVA_HOME = $javaHomePath
    $env:Path = "$javaHomePath\bin;$env:Path"
    $env:PLUGDEV_ROOT = $plugDevRootPath
    $env:PLUGDEV_HOME = $plugDevHome
    $env:GRADLE_USER_HOME = $gradleHome
    $env:SYNCMONEY_TEST_SERVER_JAR = $serverJar
    $env:SYNCMONEY_CANDIDATE_VERSION = $candidateVersion
    $env:SYNCMONEY_TEST_GAME_PORT = [string]$gamePort
    $env:SYNCMONEY_TEST_REDIS_PORT = [string]$redisPort
    $env:T21_PLAYER_SCHEDULER_RUN = $runMarker

    New-Item -ItemType Directory -Path (Join-Path $projectRoot 'tools\plugdev'), (Join-Path $projectRoot 'src\main\resources'),
        (Join-Path $projectRoot 'build\libs'), (Join-Path $projectRoot 'build\acceptance'), (Join-Path $projectRoot 'gradle\wrapper') -Force | Out-Null
    @"
plugins {
    id 'java'
}
// paper-api marker for PlugDev plugin-project detection.
"@ | Set-Content -LiteralPath (Join-Path $projectRoot 'build.gradle') -Encoding utf8
    "rootProject.name = 'Syncmoney'" | Set-Content -LiteralPath (Join-Path $projectRoot 'settings.gradle') -Encoding utf8
    Copy-Item -LiteralPath (Join-Path $root 'gradlew.bat') -Destination (Join-Path $projectRoot 'gradlew.bat')
    foreach ($file in @('gradle-wrapper.jar', 'gradle-wrapper.properties')) {
        Copy-Item -LiteralPath (Join-Path $root "gradle\wrapper\$file") -Destination (Join-Path $projectRoot "gradle\wrapper\$file")
    }
    Copy-Item -LiteralPath (Join-Path $root 'src\main\resources\config.yml') -Destination (Join-Path $projectRoot 'src\main\resources\config.yml')
    Copy-Item -LiteralPath (Join-Path $root 'src\main\resources\plugin.yml') -Destination (Join-Path $projectRoot 'src\main\resources\plugin.yml')
    Copy-Item -LiteralPath $prepareScript -Destination (Join-Path $projectRoot 'tools\plugdev\prepare.mjs')
    Copy-Item -LiteralPath $candidateJar -Destination (Join-Path $projectRoot "build\libs\Syncmoney-$candidateVersion.jar")
    Copy-Item -LiteralPath $acceptanceJar -Destination (Join-Path $projectRoot 'build\acceptance\SyncmoneyAcceptance.jar')
    Copy-Item -LiteralPath $apiConsumerJar -Destination (Join-Path $projectRoot 'build\acceptance\SyncmoneyApiConsumer.jar')

    $script:redisProcess = Start-Process -FilePath $redisServer -ArgumentList @($redisConfigName) -WorkingDirectory $redisRoot -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logsRoot 'redis.stdout.log') -RedirectStandardError (Join-Path $logsRoot 'redis.stderr.log')
    $script:redisProcessId = [int]$script:redisProcess.Id
    $redisDeadline = [DateTime]::UtcNow.AddSeconds(25)
    do {
        $script:redisProcess.Refresh()
        if ($script:redisProcess.HasExited) { throw "Isolated Redis exited with code $($script:redisProcess.ExitCode)." }
        try {
            if ((Invoke-Redis @('PING') -Raw).Trim() -eq 'PONG') {
                $ownedRedis = @(Get-OwnedRedisProcesses)
                if ($ownedRedis.Count -ne 1 -or [int]$ownedRedis[0].ProcessId -ne $script:redisProcessId) { throw 'Redis identity did not match this run config.' }
                break
            }
        } catch { }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $redisDeadline)
    if ((Invoke-Redis @('PING') -Raw).Trim() -ne 'PONG') { throw 'Timed out waiting for isolated Redis.' }

    Push-Location $projectRoot
    try {
        $prepareArguments = @($pin.software, $pin.version, 'local_redis', 'off', $candidateVersion)
        if ($Guard.IsPresent) { $prepareArguments += 'guard-on' }
        $prepared = @(& node (Join-Path $projectRoot 'tools\plugdev\prepare.mjs') @prepareArguments 2>&1)
        $prepareExit = $LASTEXITCODE
    } finally { Pop-Location }
    $prepared | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath (Join-Path $logsRoot 'prepare-profile.txt') -Encoding utf8
    if ($prepareExit -ne 0) { throw "Local PlugDev profile preparation exited $prepareExit." }
    if ($Guard.IsPresent) { $script:guardConfigSha256 = Assert-GuardOnlyProfile }
    Add-ServerRunIdentity

    $startExit = Invoke-PlugDev @('server', 'start', '--config', '.plugdev/profile.yml') 'server-start.txt'
    $serverLog = Wait-ServerLog 'Done \([^\r\n]*\)! For help' 180 'server readiness'
    $serverLog = Wait-ServerLog 'API CLASSLOADER PASS' 90 'API consumer startup acceptance'
    if ($serverLog -notmatch 'Syncmoney enabled successfully\.') { throw 'Syncmoney successful-enable marker is missing.' }
    $sessionPath = Join-Path $projectRoot '.plugdev\session.json'
    if (-not (Test-Path -LiteralPath $sessionPath -PathType Leaf)) { throw 'PlugDev did not record a server session PID.' }
    $session = Get-Content -LiteralPath $sessionPath -Raw | ConvertFrom-Json
    $ownedServer = @(Get-OwnedServerProcesses)
    if ($ownedServer.Count -ne 1 -or [int]$ownedServer[0].ProcessId -ne [int]$session.pid) { throw 'Server PID was not the unique run-marked Java process.' }
    $script:serverProcessId = [int]$session.pid

    $crossOwnerEnabled = $CrossOwner.IsPresent
    $crossOwnerClient = $null
    if ($crossOwnerEnabled) {
        $crossOwnerClient = Invoke-Client 'cross-owner'
        $null = Wait-ServerLog 'T21_CROSS_OWNER_PASS players=AcceptanceA,AcceptanceB' 30 'two-region owner proof'
    }

    $guardClient = $null
    if ($Guard.IsPresent) {
        $guardClient = Invoke-Client 'guard'
        $null = Wait-ServerLog 'T21_GUARD_TRANSACTIONS player=AcceptanceGuard uuid=[0-9a-f-]{36} accepted=5 rejected=1 outcomes=SSSSSF before=0\.00 after=5\.00' 30 'entity-owner guard outcome'
    }

    $teleport = Invoke-Client 'teleport'
    $null = Wait-ServerLog 'T15_TELEPORT_REACHED .* world=world_nether' 30 'server-reported Nether arrival'
    $null = Wait-ServerLog 'T15_PLAYER_QUIT' 30 'teleport client disconnect'
    $logout = Invoke-Client 'logout'
    $null = Wait-ServerLog 'lost connection: T15 accepted-write logout scenario' 30 'expected server logout kick'

    $script:normalServerStopExitCode = Invoke-PlugDev @('server', 'command', 'stop') 'server-stop.txt'
    Wait-ServerExit
    $serverLogPath = Join-Path $projectRoot '.plugdev\run\logs\latest.log'
    $serverLog = Get-Content -LiteralPath $serverLogPath -Raw
    Copy-Item -LiteralPath $serverLogPath -Destination (Join-Path $logsRoot 'server.log')
    if (-not $serverLog.Contains('Syncmoney disabled.')) { throw 'Normal shutdown did not log Syncmoney disabled.' }
    $serverLines = @($serverLog -split "`r?`n")
    $scenarioEvidence = Assert-ObservedScenario $serverLines $teleport $logout $crossOwnerClient

    $uuid = '35c93f74-c540-3b38-8093-b5cb3c2263be'
    $balanceKey = "syncmoney:balance:$uuid"
    $versionKey = "syncmoney:version:$uuid"
    $redisBalance = (Invoke-Redis @('GET', $balanceKey) -Raw).Trim()
    $accountVersion = (Invoke-Redis @('GET', $versionKey) -Raw).Trim()
    if ($redisBalance -cne '4.00' -or $accountVersion -cne '4') { throw "Expected Redis balance/version 4.00/v4; observed $redisBalance/v$accountVersion." }
    $guardEvidence = $null
    $guardBalance = $null
    $guardVersion = $null
    $guardBalanceKey = $null
    $guardVersionKey = $null
    if ($Guard.IsPresent) {
        $ownerLines = @($serverLines | Where-Object { $_ -match 'T21_GUARD_OWNER' })
        if ($ownerLines.Count -ne 1) { throw "Expected one guard owner proof, observed $($ownerLines.Count)." }
        $ownerMatch = [regex]::Match($ownerLines[0], 'T21_GUARD_OWNER player=AcceptanceGuard uuid=([0-9a-f-]{36})')
        if (-not $ownerMatch.Success) { throw 'The guard owner proof did not contain a valid player UUID.' }
        $guardUuid = $ownerMatch.Groups[1].Value.ToLowerInvariant()
        $guardBalanceKey = "syncmoney:balance:$guardUuid"
        $guardVersionKey = "syncmoney:version:$guardUuid"
        $guardBalance = (Invoke-Redis @('GET', $guardBalanceKey) -Raw).Trim()
        $guardVersion = (Invoke-Redis @('GET', $guardVersionKey) -Raw).Trim()
        $guardServerEvidenceLines = @($serverLines | Where-Object {
            $_ -match 'T21_GUARD_(?:OWNER|TRANSACTIONS|FAIL)|Deposit rejected by PlayerTransactionGuard:'
        })
        $guardClientEvidenceLines = @($guardClient.lines | Where-Object { $_ -match '^BOT_GUARD_(?:RATE_LIMIT_NOTICE_PASS|FAIL)' })
        $guardEvidenceJson = ConvertTo-Json -InputObject ([ordered]@{
            serverLines = @($guardServerEvidenceLines)
            clientLines = @($guardClientEvidenceLines)
            redisBalance = $guardBalance
            redisVersion = $guardVersion
        }) -Compress
        $guardEvidenceOutput = @(& node $assertionModule '--has-guard-runtime-proof-json' $guardEvidenceJson 2>&1)
        $guardEvidenceExit = $LASTEXITCODE
        $guardEvidenceResult = ($guardEvidenceOutput | ForEach-Object { $_.ToString() } | Select-Object -Last 1).Trim()
        if ($guardEvidenceExit -ne 0 -or $guardEvidenceResult -cne 'true') {
            throw "Canvas player guard evidence failed offline validation: $($guardEvidenceOutput -join ' ')."
        }
        $guardEvidence = [ordered]@{
            ownerProof = $true
            rateLimitRejection = $true
            playerNotification = $true
            redisBalance = $guardBalance
            redisVersion = [int]$guardVersion
            ownerEvidenceLines = $guardServerEvidenceLines
            clientNotificationLines = $guardClientEvidenceLines
        }
    }
    $redisDbSize = [int](Invoke-Redis @('DBSIZE') -Raw).Trim()
    Stop-IsolatedRedis
    $redisFiles = @(Get-ChildItem -LiteralPath $redisData -Recurse -File | ForEach-Object {
        [ordered]@{ path = $_.FullName.Substring($runRoot.Length + 1).Replace('\', '/'); bytes = $_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
    $notEstablished = @('CMI callback retirement', 'hard-kill recovery', 'fleet-wide quiescence', 'full backend/failure matrix')
    if (-not $crossOwnerEnabled) { $notEstablished += 'Folia two-region cross-owner behavior' }
    $notEstablished += 'full Canvas owner/guard combination matrix outside the tested rate-limit path'
    $script:result = [ordered]@{
        schemaVersion = 1
        status = 'PASS'
        evidenceLevel = 'E2_REAL_SERVER_AND_ISOLATED_REDIS'
        task = if ($Guard.IsPresent) { 'T21 Canvas entity-owner guard and player lifecycle scheduler subcase' } else { 'T21 player lifecycle scheduler subcase' }
        runId = $runId
        endpoint = [ordered]@{ label = $Endpoint; software = $pin.software; minecraftVersion = $pin.version; build = $pin.build; serverJar = $serverJar; serverJarSha256 = $serverSha }
        runtime = [ordered]@{ java = $javaVersion; node = (& node --version); plugDevVersion = $plugDevPackage.version; mineflayerVersion = $mineflayerPackage.version; redis = $redisServerVersion }
        source = [ordered]@{ repository = $root; commit = ((& git -C $root rev-parse HEAD 2>$null | Out-String).Trim()); candidateJarSha256 = $candidateSha; acceptanceProbeModifiedForPendingObservation = $true }
        artifacts = [ordered]@{
            candidateJarSha256 = $candidateSha
            acceptanceJarSha256 = (Get-FileHash $acceptanceJar -Algorithm SHA256).Hash.ToLowerInvariant()
            apiConsumerJarSha256 = (Get-FileHash $apiConsumerJar -Algorithm SHA256).Hash.ToLowerInvariant()
            runnerSha256 = (Get-FileHash $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
            clientHelperSha256 = (Get-FileHash $clientScript -Algorithm SHA256).Hash.ToLowerInvariant()
            assertionModuleSha256 = (Get-FileHash $assertionModule -Algorithm SHA256).Hash.ToLowerInvariant()
            assertionTestSha256 = (Get-FileHash $assertionTest -Algorithm SHA256).Hash.ToLowerInvariant()
            prepareScriptSha256 = (Get-FileHash $prepareScript -Algorithm SHA256).Hash.ToLowerInvariant()
            acceptanceProbeSourceSha256 = (Get-FileHash (Join-Path $root 'src\acceptance\java\noietime\syncmoney\acceptance\AcceptanceProbe.java') -Algorithm SHA256).Hash.ToLowerInvariant()
            localPlugDevCliSha256 = (Get-FileHash $plugDevCli -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        isolation = [ordered]@{ runRoot = $runRoot; projectRoot = $projectRoot; plugDevHome = $plugDevHome; gradleUserHome = $gradleHome; redisData = $redisData; runMarker = $runMarker; gamePort = $gamePort; rconPort = $rconPort; redisPort = $redisPort; redisDatabase = 15; redisAof = $true; appendFsync = 'always'; mode = 'local_redis'; playerProtection = $Guard.IsPresent; playerProtectionOnlyConfigSha256 = $script:guardConfigSha256 }
        server = [ordered]@{ processId = $script:serverProcessId; normalStartExitCode = $startExit; normalStopExitCode = $script:normalServerStopExitCode; processExitVerified = $true; syncmoneyDisabledMarker = $true; logSha256 = (Get-FileHash (Join-Path $logsRoot 'server.log') -Algorithm SHA256).Hash.ToLowerInvariant() }
        redis = [ordered]@{ processId = $script:redisProcessId; normalShutdownExitCode = $script:normalRedisStopExitCode; processExitCode = $script:redisExitCode; processExitVerified = $true; databaseSize = $redisDbSize; balanceKey = $balanceKey; balance = $redisBalance; versionKey = $versionKey; version = [int]$accountVersion; guardBalanceKey = $guardBalanceKey; guardBalance = $guardBalance; guardVersionKey = $guardVersionKey; guardVersion = if ($null -eq $guardVersion) { $null } else { [int]$guardVersion }; files = $redisFiles }
        client = [ordered]@{
            crossOwner = if ($null -eq $crossOwnerClient) { $null } else { [ordered]@{ protocol = $pin.version; exitCode = $crossOwnerClient.exitCode; logSha256 = $crossOwnerClient.logSha256 } }
            guard = if ($null -eq $guardClient) { $null } else { [ordered]@{ protocol = $pin.version; exitCode = $guardClient.exitCode; logSha256 = $guardClient.logSha256 } }
            teleport = [ordered]@{ protocol = $pin.version; exitCode = $teleport.exitCode; logSha256 = $teleport.logSha256; observedNetherDimension = $true }
            logout = [ordered]@{ protocol = $pin.version; exitCode = $logout.exitCode; logSha256 = $logout.logSha256; expectedKickReason = 'T15 accepted-write logout scenario'; clientObservedExpectedKickPacket = $scenarioEvidence.clientObservedExpectedKickPacket; clientObservedExpectedLogoutAbort = $scenarioEvidence.clientObservedExpectedLogoutAbort; exactServerKickReasonVerified = $scenarioEvidence.exactServerKickReasonVerified }
        }
        observed = [ordered]@{ lifecycle = $scenarioEvidence; guard = $guardEvidence }
        forcedCleanup = $false
        notEstablished = $notEstablished
    }
    Write-RunnerLog "PASS endpoint=$Endpoint Redis=$redisBalance/v$accountVersion guard=$guardBalance/v$guardVersion teleport=$($scenarioEvidence.teleportDisposition) pending=$($scenarioEvidence.pendingBoundaries.teleport)/$($scenarioEvidence.pendingBoundaries.logout)"
} catch {
    $script:failure = $_.Exception.Message
    Write-RunnerLog "FAIL endpoint=$Endpoint error=$($script:failure)"
} finally {
    try { Stop-OwnedServerAfterFailure } catch { $script:cleanupFailure = $_.Exception.Message }
    try { Stop-OwnedRedisAfterFailure } catch { if ($null -eq $script:cleanupFailure) { $script:cleanupFailure = $_.Exception.Message } }
    try {
        $homePath = [IO.Path]::GetFullPath($gradleHome)
        $gradleDaemons = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
            $_.Name -in @('java.exe', 'javaw.exe') -and $_.CommandLine -and
            $_.CommandLine.IndexOf($homePath, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
            $_.CommandLine -match 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon'
        })
        if ($gradleDaemons.Count -gt 0) { $script:gradleCleanup = Stop-OwnedGradleDaemons }
        else { $script:gradleCleanup = [ordered]@{ processIds = @(); stopExitCode = $null; processExitVerified = $true } }
    } catch { if ($null -eq $script:cleanupFailure) { $script:cleanupFailure = $_.Exception.Message } }
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $savedEnv[$name], 'Process') }

    if ($null -eq $script:result) {
        $serverLogPath = Join-Path $projectRoot '.plugdev\run\logs\latest.log'
        $serverLogCopy = Join-Path $logsRoot 'server.log'
        $serverLogHash = $null
        if (Test-Path -LiteralPath $serverLogCopy -PathType Leaf) {
            try { $serverLogHash = (Get-FileHash -LiteralPath $serverLogCopy -Algorithm SHA256).Hash.ToLowerInvariant() } catch { }
        }
        if ($null -eq $serverLogHash -and (Test-Path -LiteralPath $serverLogPath -PathType Leaf)) {
            try { $serverLogHash = (Get-FileHash -LiteralPath $serverLogPath -Algorithm SHA256).Hash.ToLowerInvariant() } catch { }
        }
        $script:result = [ordered]@{
            schemaVersion = 1
            status = 'FAIL'
            evidenceLevel = 'E2_REAL_SERVER_AND_ISOLATED_REDIS'
            task = 'T21 player lifecycle scheduler subcase'
            runId = $runId
            endpoint = [ordered]@{ label = $Endpoint; software = $pin.software; minecraftVersion = $pin.version; build = $pin.build; serverJarSha256 = $serverSha }
            candidateJarSha256 = $candidateSha
            isolation = [ordered]@{ runRoot = $runRoot; projectRoot = $projectRoot; plugDevHome = $plugDevHome; gradleUserHome = $gradleHome; redisData = $redisData; runMarker = $runMarker; gamePort = $gamePort; rconPort = $rconPort; redisPort = $redisPort; redisDatabase = 15 }
            server = [ordered]@{ processId = $script:serverProcessId; normalStopExitCode = $script:normalServerStopExitCode; processExitVerified = (@(Get-OwnedServerProcesses).Count -eq 0); logSha256 = $serverLogHash }
            redis = [ordered]@{ processId = $script:redisProcessId; normalShutdownExitCode = $script:normalRedisStopExitCode; processExitCode = $script:redisExitCode; processExitVerified = ($null -eq $script:redisProcess -or $script:redisProcess.HasExited) }
            failure = $script:failure
            cleanupFailure = $script:cleanupFailure
            forcedCleanup = $script:forcedCleanup
        }
    } elseif ($null -ne $script:cleanupFailure) {
        $script:result.status = 'FAIL'
        $script:result.cleanupFailure = $script:cleanupFailure
        $script:result.forcedCleanup = $script:forcedCleanup
    }
    if ($null -ne $script:gradleCleanup) { $script:result.gradleCleanup = $script:gradleCleanup }
    $resultPath = Join-Path $runRoot 'result.json'
    $script:result | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $resultPath -Encoding utf8
    Write-RunnerLog "result=$resultPath status=$($script:result.status)"
}

if ($script:result.status -cne 'PASS') { throw "T21 player-scheduler run failed; inspect $(Join-Path $runRoot 'result.json'). $($script:failure) $($script:cleanupFailure)" }
Write-Output (Join-Path $runRoot 'result.json')
