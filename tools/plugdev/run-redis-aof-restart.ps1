param(
    [Parameter(Mandatory = $true)][ValidateSet('paper', 'folia', 'canvas')][string]$Project,
    [Parameter(Mandatory = $true)][string]$MinecraftVersion,
    [Parameter(Mandatory = $true)][int]$BuildNumber,
    [Parameter(Mandatory = $true)][string]$ServerJar,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedServerSha256,
    [Parameter(Mandatory = $true)][string]$PlugDevRoot,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$CandidateVersion = '1.4.0-alpha.1'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-NoReparsePointAncestors([string]$Path) {
    $current = [IO.Path]::GetFullPath($Path)
    while (-not [string]::IsNullOrWhiteSpace($current)) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Refusing a report path whose ancestor is a reparse point: $current"
            }
        }
        $parent = Split-Path -Parent $current
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $current) { break }
        $current = $parent
    }
}

function Get-FreeLoopbackPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return ([Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}

function Invoke-RedisCli([string[]]$Arguments, [switch]$AllowDisconnect) {
    $null = Assert-IsolatedRedisOwnership
    $output = @(& $script:redisCli -h 127.0.0.1 -p $script:redisPort @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowDisconnect) {
        throw "redis-cli failed ($exitCode): $($output -join ' ')"
    }
    return [pscustomobject]@{ exitCode = $exitCode; output = ($output -join "`n").Trim() }
}

function Assert-IsolatedRedisOwnership([switch]$AllowNotReady) {
    if ($null -eq $script:redisProcess -or $script:redisProcess.HasExited) {
        throw 'The run-owned Redis process is not alive.'
    }
    try {
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $script:redisPort -ErrorAction Stop)
    } catch {
        if ($AllowNotReady -and $_.FullyQualifiedErrorId -eq 'CmdletizationQuery_NotFound,Get-NetTCPConnection') { return $false }
        throw
    }
    if ($listeners.Count -eq 0) {
        if ($AllowNotReady) { return $false }
        throw "No listener is bound to the isolated Redis port $script:redisPort."
    }
    $foreign = @($listeners | Where-Object { [int]$_.OwningProcess -ne [int]$script:redisProcess.Id })
    if ($foreign.Count -gt 0) {
        throw "Refusing Redis port $script:redisPort because listener PID(s) $($foreign.OwningProcess -join ', ') do not match run-owned PID $($script:redisProcess.Id)."
    }
    $loopback = @($listeners | Where-Object { $_.LocalAddress -in @('127.0.0.1', '::1') })
    if ($loopback.Count -eq 0) { throw "Run-owned Redis is not bound to a loopback address on port $script:redisPort." }
    return $true
}

function Wait-RedisReady([int]$TimeoutSeconds = 20) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if ($null -ne $script:redisProcess -and $script:redisProcess.HasExited) {
            throw "Isolated Redis exited unexpectedly with code $($script:redisProcess.ExitCode)."
        }
        if (Assert-IsolatedRedisOwnership -AllowNotReady) {
            $response = Invoke-RedisCli @('PING')
            if ($response.output -eq 'PONG') { return }
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for isolated Redis on 127.0.0.1:$script:redisPort."
}

function Start-IsolatedRedis {
    if ($null -ne $script:redisProcess -and -not $script:redisProcess.HasExited) {
        throw 'Refusing to start a second isolated Redis process.'
    }
    $script:redisProcess = Start-Process -FilePath $script:redisServer -ArgumentList 'redis.conf' `
        -WorkingDirectory $script:redisRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $script:logs 'redis-stdout.log') `
        -RedirectStandardError (Join-Path $script:logs 'redis-stderr.log')
    $script:redisStopRequested = $false
    Wait-RedisReady
    $script:redisStages.Add([ordered]@{ action = 'start'; processId = [int]$script:redisProcess.Id; port = $script:redisPort; listenerOwnerVerified = $true })
}

function Stop-IsolatedRedis([switch]$RequireLive) {
    if ($null -eq $script:redisProcess) {
        if ($RequireLive) { throw 'The required pre-restart Redis shutdown has no tracked process.' }
        return
    }
    if ($script:redisProcess.HasExited) {
        if ($RequireLive) { throw "Redis exited before the required graceful AOF restart (code $($script:redisProcess.ExitCode))." }
        if (-not $script:redisStopRequested) { throw "Isolated Redis exited without a requested shutdown (code $($script:redisProcess.ExitCode))." }
        return
    }
    try { $null = Assert-IsolatedRedisOwnership }
    catch {
        if ($RequireLive) { throw }
        Stop-TrackedRedisWithForcedFallback "Redis listener ownership could not be verified: $($_.Exception.Message)"
        return
    }
    $null = Invoke-RedisCli @('SHUTDOWN', 'NOSAVE') -AllowDisconnect
    $script:redisStopRequested = $true
    try { $script:redisProcess.WaitForExit(15000) | Out-Null } catch { }
    if (-not $script:redisProcess.HasExited) {
        if ($RequireLive) { throw 'Redis did not complete the required graceful AOF restart shutdown within 15 seconds.' }
        Stop-TrackedRedisWithForcedFallback 'Redis did not stop after the graceful cleanup request.'
        return
    }
    if (-not $script:redisProcess.HasExited) { throw 'Isolated Redis did not exit after cleanup.' }
    if (-not $script:redisForcedStop -and $script:redisProcess.ExitCode -ne 0) {
        throw "Isolated Redis graceful shutdown returned exit code $($script:redisProcess.ExitCode)."
    }
    $script:redisExitCodes += [int]$script:redisProcess.ExitCode
    $script:redisStages.Add([ordered]@{ action = 'shutdown'; processId = [int]$script:redisProcess.Id; exitCode = [int]$script:redisProcess.ExitCode; forcedFallback = $script:redisForcedStop })
}

function Stop-TrackedRedisWithForcedFallback([string]$Reason) {
    if ($null -eq $script:redisProcess -or $script:redisProcess.HasExited) { return }
    $processId = [int]$script:redisProcess.Id
    $script:redisForcedStop = $true
    try { $script:redisProcess.Kill() }
    catch { if (-not $script:redisProcess.HasExited) { throw } }
    $script:redisProcess.WaitForExit(10000) | Out-Null
    if (-not $script:redisProcess.HasExited) { throw "Run-owned Redis process $processId did not exit after forced cleanup." }
    $exitCode = [int]$script:redisProcess.ExitCode
    $script:redisExitCodes += $exitCode
    $script:redisStages.Add([ordered]@{ action = 'force-stop-run-owned-process'; processId = $processId; exitCode = $exitCode; reason = $Reason; forcedFallback = $true })
}

function Get-RunOwnedServerProcesses {
    @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction Stop | Where-Object {
        $_.CommandLine -and $_.CommandLine.Contains("-Dsyncmoney.t21.run=$script:runMarker")
    })
}

function Wait-RunServerStopped([int]$TimeoutSeconds = 30) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $owned = @(Get-RunOwnedServerProcesses)
        if ($owned.Count -eq 0) { return }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    $owned = @(Get-RunOwnedServerProcesses)
    if ($owned.Count -gt 0) { throw "Run-owned Paper process did not exit: $($owned.ProcessId -join ', ')." }
}

function Stop-RunOwnedServerProcesses([string]$Reason) {
    $owned = @(Get-RunOwnedServerProcesses)
    if ($owned.Count -eq 0) { return }
    if ($owned.Count -ne 1) { throw "Refusing to force-stop ambiguous run-owned Paper processes during $Reason." }
    $processId = [int]$owned[0].ProcessId
    Stop-Process -Id $processId -Force -ErrorAction Stop
    Wait-RunServerStopped 20
    $script:serverCleanupActions.Add([ordered]@{ action = 'force-stop-run-owned-paper'; reason = $Reason; processId = $processId; processExitVerified = $true })
}

function Get-RunOwnedGradleDaemons {
    $gradleHome = [IO.Path]::GetFullPath((Join-Path $script:runRoot '.gradle-user-home'))
    @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction Stop | Where-Object {
        $_.CommandLine -and $_.CommandLine -match 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon' -and
            $_.CommandLine.IndexOf($gradleHome, [StringComparison]::OrdinalIgnoreCase) -ge 0
    })
}

function Invoke-PlugDev([string[]]$Arguments, [string]$LogName) {
    Push-Location $script:runRoot
    try {
        $output = @(& node $script:cli @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
        $output | Set-Content -LiteralPath (Join-Path $script:logs $LogName) -Encoding utf8
        if ($exitCode -ne 0) { throw "PlugDev command failed ($exitCode): $($output -join ' ')" }
        return ($output -join "`n")
    } finally { Pop-Location }
}

function Set-PluginStage([string]$Version, [string]$JarPattern) {
    $profilePath = Join-Path $script:runRoot '.plugdev\profile.yml'
    $editor = @'
const fs = require("node:fs");
const path = require("node:path");
const { createRequire } = require("node:module");
const yaml = createRequire(path.join(process.env.PLUGDEV_ROOT, "package.json"))("yaml");
const profilePath = path.resolve(".plugdev/profile.yml");
const profile = yaml.parse(fs.readFileSync(profilePath, "utf8"));
profile.build.jarPattern = process.argv[2];
profile.jvm.args = [
  `-Dsyncmoney.acceptance.version=${process.argv[3]}`,
  `-Dsyncmoney.t21.run=${process.env.T21_RUN_MARKER}`,
];
fs.writeFileSync(profilePath, yaml.stringify(profile), "utf8");
console.log(JSON.stringify({ version: process.argv[3], jarPattern: profile.build.jarPattern }));
'@
    $editorPath = Join-Path $script:runRoot 'set-plugin-stage.cjs'
    Set-Content -LiteralPath $editorPath -Value $editor -Encoding utf8
    Push-Location $script:runRoot
    try {
        $output = @(& node $editorPath $JarPattern $Version 2>&1)
        if ($LASTEXITCODE -ne 0) { throw "Could not select plugin stage: $($output -join ' ')" }
    } finally { Pop-Location }
}

function Wait-ServerLogMarker([string]$Marker, [int]$TimeoutSeconds = 45) {
    $logPath = Join-Path $script:runRoot '.plugdev\run\logs\latest.log'
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (Test-Path -LiteralPath $logPath) {
            if (Select-String -LiteralPath $logPath -Pattern 'ACCEPTANCE FAIL|T21_WRITE_FAIL|T21_READ_FAIL|API CLASSLOADER FAIL|Error occurred while enabling Syncmoney|Could not load plugin' -Quiet) {
                throw "Runtime failure marker found while waiting for '$Marker'; see $logPath."
            }
            if (Select-String -LiteralPath $logPath -SimpleMatch -Pattern $Marker -Quiet) { return }
        }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for runtime marker '$Marker'; see $logPath."
}

function Start-ServerStage([string]$Version, [string]$JarPattern, [string]$Stage) {
    Set-PluginStage $Version $JarPattern
    $script:serverMayExist = $true
    $null = Invoke-PlugDev @('server', 'start', '--config', '.plugdev/profile.yml') "$Stage-start.txt"
    $owned = @(Get-RunOwnedServerProcesses)
    if ($owned.Count -ne 1) { throw "Expected one run-owned Paper process after $Stage start; observed $($owned.Count)." }
    $logPath = Join-Path $script:runRoot '.plugdev\run\logs\latest.log'
    if (-not (Test-Path -LiteralPath $logPath)) { throw "PlugDev did not produce a server log for stage $Stage." }
    if ($Version -eq $script:CandidateVersion) { Wait-ServerLogMarker 'API CLASSLOADER PASS provider=Syncmoney' }
    $script:stages += [ordered]@{ stage = $Stage; pluginVersion = $Version; processId = [int]$owned[0].ProcessId; started = $true }
}

function Invoke-Acceptance([string]$Arguments, [string]$Stage, [string]$ExpectedMarker) {
    $null = Invoke-PlugDev @('server', 'command', $Arguments) "$Stage-command.txt"
    Wait-ServerLogMarker $ExpectedMarker
    $script:acceptance += [ordered]@{ stage = $Stage; command = $Arguments; marker = $ExpectedMarker; observed = $true }
}

function Stop-ServerStage([string]$Stage) {
    $owned = @(Get-RunOwnedServerProcesses)
    if ($owned.Count -ne 1) { throw "Refusing RCON stop for $Stage because run ownership is not unique." }
    $null = Invoke-PlugDev @('server', 'command', 'stop') "$Stage-stop.txt"
    Wait-RunServerStopped
    $script:stages += [ordered]@{ stage = $Stage; stopped = $true; processExitVerified = $true }
    $script:serverMayExist = $false
}

function Get-AofFiles {
    if (-not (Test-Path -LiteralPath $script:redisRoot -PathType Container)) { return @() }
    @(Get-ChildItem -LiteralPath $script:redisRoot -File -Recurse | Where-Object {
        $_.Name -match '\.aof$|\.rdb$|manifest'
    } | ForEach-Object {
        [ordered]@{ path = $_.FullName.Substring($script:redisRoot.Length).TrimStart('\'); bytes = $_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$reportsRoot = [IO.Path]::GetFullPath((Join-Path $root 'build\reports\foundation\T21-redis-aof'))
Assert-NoReparsePointAncestors $root
Assert-NoReparsePointAncestors $reportsRoot
if (-not (Test-Path -LiteralPath $reportsRoot -PathType Container)) {
    New-Item -ItemType Directory -Force -Path $reportsRoot -ErrorAction Stop | Out-Null
    Assert-NoReparsePointAncestors $reportsRoot
}
if ($MinecraftVersion -notmatch '^\d{1,3}\.\d{1,3}(?:\.\d{1,3})?$' -or $BuildNumber -le 0) { throw 'Invalid server version/build pin.' }
if ($CandidateVersion -notmatch '^\d+\.\d+\.\d+(?:-(?:alpha|beta)\.\d+)?$') { throw 'Invalid candidate version.' }

$serverJarPath = (Resolve-Path -LiteralPath $ServerJar).Path
$serverSha256 = (Get-FileHash -LiteralPath $serverJarPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($serverSha256 -ne $ExpectedServerSha256.ToLowerInvariant()) { throw 'Server artifact SHA-256 mismatch.' }
$candidateJar = Join-Path $root "build\libs\Syncmoney-$CandidateVersion.jar"
$acceptanceJar = Join-Path $root 'build\acceptance\SyncmoneyAcceptance.jar'
$apiConsumerJar = Join-Path $root 'build\acceptance\SyncmoneyApiConsumer.jar'
foreach ($required in @($candidateJar, $acceptanceJar, $apiConsumerJar)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required artifact missing: $required" }
}
$javaHomePath = (Resolve-Path -LiteralPath $JavaHome).Path
$java = Join-Path $javaHomePath 'bin\java.exe'
$javaBanner = (& $java -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaBanner -notmatch 'version "25(?:\.|"|\+)') { throw 'T21 requires Java 25.' }
$plugDevRootPath = (Resolve-Path -LiteralPath $PlugDevRoot).Path
$script:cli = Join-Path $plugDevRootPath 'packages\cli\dist\cli.js'
if (-not (Test-Path -LiteralPath $script:cli -PathType Leaf)) { throw 'Local PlugDev CLI was not found.' }
$redisServerCommand = Get-Command redis-server.exe, redis-server -ErrorAction SilentlyContinue | Select-Object -First 1
$redisCliCommand = Get-Command redis-cli.exe, redis-cli -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $redisServerCommand -or $redisServerCommand.CommandType -ne 'Application' -or $null -eq $redisCliCommand -or $redisCliCommand.CommandType -ne 'Application') {
    throw 'T21 M06 requires local redis-server and redis-cli executables.'
}
$script:redisServer = $redisServerCommand.Source
$script:redisCli = $redisCliCommand.Source
if ($script:redisServer -match '(?i)\\scoop\\shims\\redis-server\.exe$') {
    $scoopRoot = Split-Path -Parent (Split-Path -Parent $script:redisServer)
    $realRedisServer = Join-Path $scoopRoot 'apps\redis\current\redis-server.exe'
    if (Test-Path -LiteralPath $realRedisServer -PathType Leaf) { $script:redisServer = (Resolve-Path -LiteralPath $realRedisServer).Path }
}
if ($script:redisCli -match '(?i)\\scoop\\shims\\redis-cli\.exe$') {
    $scoopRoot = Split-Path -Parent (Split-Path -Parent $script:redisCli)
    $realRedisCli = Join-Path $scoopRoot 'apps\redis\current\redis-cli.exe'
    if (Test-Path -LiteralPath $realRedisCli -PathType Leaf) { $script:redisCli = (Resolve-Path -LiteralPath $realRedisCli).Path }
}
$redisBanner = (& $script:redisServer --version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the local Redis server version.' }

$guid = [guid]::NewGuid().ToString('N')
$script:runMarker = "t21-$guid"
$script:CandidateVersion = $CandidateVersion
$script:runRoot = [IO.Path]::GetFullPath((Join-Path $reportsRoot "${Project}-${MinecraftVersion}-${BuildNumber}-$(Get-Date -Format 'yyyyMMdd-HHmmss')-$guid"))
$reportsPrefix = $reportsRoot.TrimEnd('\') + '\'
if (-not $script:runRoot.StartsWith($reportsPrefix, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $script:runRoot)) { throw 'Invalid or existing run directory.' }
$script:logs = Join-Path $script:runRoot 'logs'
$script:redisRoot = Join-Path $script:runRoot 'redis'
$script:redisPort = Get-FreeLoopbackPort
$script:redisConfig = Join-Path $script:redisRoot 'redis.conf'
$script:redisProcess = $null
$script:redisStopRequested = $false
$script:redisForcedStop = $false
$script:redisStages = [System.Collections.Generic.List[object]]::new()
$script:redisExitCodes = [System.Collections.Generic.List[int]]::new()
$script:serverMayExist = $false
$script:serverCleanupActions = [System.Collections.Generic.List[object]]::new()
$script:stages = [System.Collections.Generic.List[object]]::new()
$script:acceptance = [System.Collections.Generic.List[object]]::new()
$script:failure = $null
$script:cleanupFailure = $null
$script:locationPushed = $false
$script:status = 'NOT_RUN'
$script:gradleStopExit = $null
$script:gradleDaemonsBeforeStop = @()
$script:gradleDaemonsAfterStop = @()
$script:aofBeforeRestart = @()
$configObservation = $null
$pluginDataFiles = @()
$persistenceBefore = $null
$persistenceAfter = $null
$sqliteUuidA = '35c93f74-c540-3b38-8093-b5cb3c2263be'
$sqliteUuidB = 'b748781d-824c-36d9-9de4-59cee731f37f'
$accountObservations = [System.Collections.Generic.List[object]]::new()
$environmentBefore = $null

try {
New-Item -ItemType Directory -Path $script:runRoot -ErrorAction Stop | Out-Null
Assert-NoReparsePointAncestors $script:runRoot
New-Item -ItemType Directory -Force -Path $script:logs, $script:redisRoot, (Join-Path $script:runRoot 'gradle'), (Join-Path $script:runRoot 'tools\plugdev'), (Join-Path $script:runRoot 'src\main\resources'), (Join-Path $script:runRoot 'build\libs'), (Join-Path $script:runRoot 'build\acceptance'), (Join-Path $script:runRoot '.plugdev-home'), (Join-Path $script:runRoot '.gradle-user-home') | Out-Null
foreach ($name in @('build.gradle', 'settings.gradle', 'gradlew.bat')) { Copy-Item -LiteralPath (Join-Path $root $name) -Destination (Join-Path $script:runRoot $name) }
Copy-Item -LiteralPath (Join-Path $root 'gradle\wrapper') -Destination (Join-Path $script:runRoot 'gradle') -Recurse
$settingsText = Get-Content -LiteralPath (Join-Path $script:runRoot 'settings.gradle') -Raw
foreach ($include in [regex]::Matches($settingsText, '(?m)^\s*include\s*\(([^)]*)\)')) {
    foreach ($projectNameMatch in [regex]::Matches($include.Groups[1].Value, '["'']([^"'']+)["'']')) {
        $projectPath = $projectNameMatch.Groups[1].Value.TrimStart(':') -replace ':', '\'
        $targetPath = [IO.Path]::GetFullPath((Join-Path $script:runRoot $projectPath))
        if (-not $targetPath.StartsWith(($script:runRoot.TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase)) { throw "Gradle include escapes run root: $projectPath" }
        $targetProject = Join-Path $targetPath 'build.gradle'
        $sourceProject = Join-Path $root (Join-Path $projectPath 'build.gradle')
        if (-not (Test-Path -LiteralPath $sourceProject -PathType Leaf)) { throw "Missing project build file: $sourceProject" }
        New-Item -ItemType Directory -Force -Path $targetPath | Out-Null
        Copy-Item -LiteralPath $sourceProject -Destination $targetProject
    }
}
Copy-Item -LiteralPath (Join-Path $root 'tools\plugdev\prepare.mjs') -Destination (Join-Path $script:runRoot 'tools\plugdev\prepare.mjs')
foreach ($name in @('config.yml', 'plugin.yml')) { Copy-Item -LiteralPath (Join-Path $root "src\main\resources\$name") -Destination (Join-Path $script:runRoot "src\main\resources\$name") }
Copy-Item -LiteralPath $candidateJar -Destination (Join-Path $script:runRoot "build\libs\Syncmoney-$CandidateVersion.jar")
Copy-Item -LiteralPath $acceptanceJar -Destination (Join-Path $script:runRoot 'build\acceptance\SyncmoneyAcceptance.jar')
Copy-Item -LiteralPath $apiConsumerJar -Destination (Join-Path $script:runRoot 'build\acceptance\SyncmoneyApiConsumer.jar')
$redisConfigLines = @(
    "bind 127.0.0.1",
    "port $script:redisPort",
    'protected-mode yes',
    'daemonize no',
    'dir ./',
    'appendonly yes',
    'appendfsync always',
    'save ""',
    'databases 16',
    'appendfilename appendonly.aof',
    'logfile redis.log'
)
[IO.File]::WriteAllLines($script:redisConfig, [string[]]$redisConfigLines, [Text.UTF8Encoding]::new($false))

$environmentBefore = @{
    JAVA_HOME = $env:JAVA_HOME
    Path = $env:Path
    PLUGDEV_ROOT = $env:PLUGDEV_ROOT
    PLUGDEV_HOME = $env:PLUGDEV_HOME
    GRADLE_USER_HOME = $env:GRADLE_USER_HOME
    SYNCMONEY_TEST_SERVER_JAR = $env:SYNCMONEY_TEST_SERVER_JAR
    SYNCMONEY_CANDIDATE_VERSION = $env:SYNCMONEY_CANDIDATE_VERSION
    SYNCMONEY_TEST_REDIS_PORT = $env:SYNCMONEY_TEST_REDIS_PORT
    T21_RUN_MARKER = $env:T21_RUN_MARKER
}
$env:JAVA_HOME = $javaHomePath
$env:Path = "$javaHomePath\bin;$env:Path"
$env:PLUGDEV_ROOT = $plugDevRootPath
$env:PLUGDEV_HOME = Join-Path $script:runRoot '.plugdev-home'
$env:GRADLE_USER_HOME = Join-Path $script:runRoot '.gradle-user-home'
$env:SYNCMONEY_TEST_SERVER_JAR = $serverJarPath
$env:SYNCMONEY_CANDIDATE_VERSION = $CandidateVersion
$env:SYNCMONEY_TEST_REDIS_PORT = [string]$script:redisPort
$env:T21_RUN_MARKER = $script:runMarker

    Push-Location $script:runRoot
    $script:locationPushed = $true
    $null = & node (Join-Path $script:runRoot 'tools\plugdev\prepare.mjs') $Project $MinecraftVersion local_redis off $CandidateVersion 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Could not prepare the isolated PlugDev LOCAL_REDIS profile.' }
    $pluginConfigPath = Join-Path $script:runRoot '.plugdev\run\plugins\Syncmoney\config.yml'
    $configAudit = @'
const fs=require("node:fs"); const {createRequire}=require("node:module");
const yaml=createRequire(require("node:path").join(process.env.PLUGDEV_ROOT,"package.json"))("yaml");
const p=process.argv[2], c=yaml.parse(fs.readFileSync(p,"utf8"));
if(c["config-version"]!==12||c["db-enabled"]!==false||c.database.enabled!==false||c.economy.mode!=="local_redis"||c.redis.enabled!==true||c.redis.database!==15||String(c.redis.port)!==process.env.SYNCMONEY_TEST_REDIS_PORT) throw new Error("LOCAL_REDIS isolation config mismatch");
console.log(JSON.stringify({configSchema:c["config-version"],mode:c.economy.mode,dbEnabled:c.database.enabled,redisEnabled:c.redis.enabled,redisDatabase:c.redis.database,redisPort:c.redis.port}));
'@
    $configAuditPath = Join-Path $script:runRoot 'verify-config.cjs'
    Set-Content -LiteralPath $configAuditPath -Value $configAudit -Encoding utf8
    $configObservation = @(& node $configAuditPath $pluginConfigPath 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Isolated plugin config audit failed: $($configObservation -join ' ')" }
    $configObservation | Set-Content -LiteralPath (Join-Path $script:logs 'config-audit.json') -Encoding utf8
    Start-IsolatedRedis
    $null = Invoke-RedisCli @('-n', '15', 'SET', "syncmoney:balance:$sqliteUuidA", '77.25')
    $null = Invoke-RedisCli @('-n', '15', 'SET', "syncmoney:version:$sqliteUuidA", '4')
    $null = Invoke-RedisCli @('-n', '15', 'SET', "syncmoney:balance:$sqliteUuidB", '88.50')
    $null = Invoke-RedisCli @('-n', '15', 'SET', "syncmoney:version:$sqliteUuidB", '6')
    $script:accountObservations.Add([ordered]@{ stage = 'legacy-key-seed'; balanceA = (Invoke-RedisCli @('-n','15','GET',"syncmoney:balance:$sqliteUuidA")).output; versionA = (Invoke-RedisCli @('-n','15','GET',"syncmoney:version:$sqliteUuidA")).output; balanceB = (Invoke-RedisCli @('-n','15','GET',"syncmoney:balance:$sqliteUuidB")).output; versionB = (Invoke-RedisCli @('-n','15','GET',"syncmoney:version:$sqliteUuidB")).output })

    Start-ServerStage $CandidateVersion "build/libs/Syncmoney-$CandidateVersion.jar" 'candidate-before-aof-restart'
    Invoke-Acceptance 'smaccept read 77.25 88.50' 'candidate-legacy-key-read' 'T21_READ_PASS expected-a=77.25 expected-b=88.50'
    Invoke-Acceptance 'smaccept write 1337.25 765.50' 'candidate-persist-write' 'T21_WRITE_PASS expected-a=1337.25 expected-b=765.50 receipt-a=SUCCESS:DEPOSIT:1260.00:1337.25 receipt-b=SUCCESS:DEPOSIT:677.00:765.50'
    Stop-ServerStage 'candidate-before-aof-restart'
    $script:accountObservations.Add([ordered]@{ stage = 'before-aof-restart'; balanceA = (Invoke-RedisCli @('-n','15','GET',"syncmoney:balance:$sqliteUuidA")).output; versionA = (Invoke-RedisCli @('-n','15','GET',"syncmoney:version:$sqliteUuidA")).output; balanceB = (Invoke-RedisCli @('-n','15','GET',"syncmoney:balance:$sqliteUuidB")).output; versionB = (Invoke-RedisCli @('-n','15','GET',"syncmoney:version:$sqliteUuidB")).output })
    $persistenceBefore = (Invoke-RedisCli @('-n','15','INFO','persistence')).output
    if ($persistenceBefore -notmatch '(?m)^aof_enabled:1$' -or $persistenceBefore -notmatch '(?m)^aof_last_write_status:ok$') { throw 'Redis did not report healthy AOF persistence before restart.' }
    Stop-IsolatedRedis -RequireLive
    $script:aofBeforeRestart = Get-AofFiles
    if ($script:aofBeforeRestart.Count -lt 2) { throw 'Expected Redis AOF data and manifest files before restart.' }
    Start-IsolatedRedis
    $persistenceAfter = (Invoke-RedisCli @('-n','15','INFO','persistence')).output
    # Redis 8.10.1 omits aof_last_load_status; exact balance/version readback below remains mandatory.
    $aofLoadStatus = [regex]::Match($persistenceAfter, '(?m)^aof_last_load_status:([^\r\n]*)$')
    if ($persistenceAfter -notmatch '(?m)^aof_enabled:1$' -or ($aofLoadStatus.Success -and -not $aofLoadStatus.Groups[1].Value.Equals('ok', [StringComparison]::OrdinalIgnoreCase))) { throw 'Redis did not report enabled AOF or reported an unsuccessful AOF load after restart.' }
    $script:accountObservations.Add([ordered]@{ stage = 'after-aof-restart-before-plugin'; balanceA = (Invoke-RedisCli @('-n','15','GET',"syncmoney:balance:$sqliteUuidA")).output; versionA = (Invoke-RedisCli @('-n','15','GET',"syncmoney:version:$sqliteUuidA")).output; balanceB = (Invoke-RedisCli @('-n','15','GET',"syncmoney:balance:$sqliteUuidB")).output; versionB = (Invoke-RedisCli @('-n','15','GET',"syncmoney:version:$sqliteUuidB")).output })
    $beforeRestart = @($script:accountObservations | Where-Object { $_.stage -eq 'before-aof-restart' })[0]
    $afterRestart = $script:accountObservations[-1]
    if ($afterRestart.balanceA -ne '1337.25' -or $afterRestart.balanceB -ne '765.50') { throw 'AOF restart did not restore candidate balances.' }
    foreach ($key in @('balanceA', 'versionA', 'balanceB', 'versionB')) {
        if ($afterRestart[$key] -ne $beforeRestart[$key]) { throw "AOF restart changed ${key}: before=$($beforeRestart[$key]) after=$($afterRestart[$key])." }
    }
    if ([long]$afterRestart.versionA -le 4 -or [long]$afterRestart.versionB -le 6) { throw 'AOF restart did not preserve versions newer than the legacy fixtures.' }
    Start-ServerStage $CandidateVersion "build/libs/Syncmoney-$CandidateVersion.jar" 'candidate-after-aof-restart'
    Invoke-Acceptance 'smaccept read 1337.25 765.50' 'candidate-aof-restore-read' 'T21_READ_PASS expected-a=1337.25 expected-b=765.50'
    Stop-ServerStage 'candidate-after-aof-restart'
    $pluginDataFiles = @(Get-ChildItem -LiteralPath (Join-Path $script:runRoot '.plugdev\run\plugins\Syncmoney') -File -Recurse | ForEach-Object { $_.FullName.Substring($script:runRoot.Length).TrimStart('\') })
    if (@($pluginDataFiles | Where-Object { $_ -match '\.(db|sqlite|sqlite3)$' }).Count -gt 0) { throw 'LOCAL_REDIS acceptance unexpectedly created a local SQL/SQLite database file.' }
    $script:status = 'PASS'
} catch {
    $script:failure = $_.Exception.Message
    $script:status = 'FAIL'
} finally {
    try {
    if ($script:serverMayExist) {
        try {
            $owned = @(Get-RunOwnedServerProcesses)
            if ($owned.Count -eq 1) {
                Push-Location $script:runRoot
                try { $null = & node $script:cli server command stop *> (Join-Path $script:logs 'finally-server-stop.txt') }
                finally { Pop-Location }
                Wait-RunServerStopped 20
                $script:serverCleanupActions.Add([ordered]@{ action = 'graceful-rcon-stop'; processExitVerified = $true })
            } elseif ($owned.Count -gt 0) { throw 'Multiple or ambiguous run-owned Paper processes during cleanup.' }
        } catch {
            try { Stop-RunOwnedServerProcesses 'graceful cleanup failed' }
            catch { $script:cleanupFailure = $_.Exception.Message }
        }
    }
    try { Stop-IsolatedRedis } catch { if ($null -eq $script:cleanupFailure) { $script:cleanupFailure = $_.Exception.Message } }
    if ($script:redisForcedStop -and $null -eq $script:failure) {
        $script:failure = 'Redis required a forced-stop cleanup fallback; graceful AOF restart evidence is invalid.'
        $script:status = 'FAIL'
    }
    if ($script:locationPushed) {
        try {
            $script:gradleDaemonsBeforeStop = @(Get-RunOwnedGradleDaemons | ForEach-Object { [int]$_.ProcessId })
            $null = & (Join-Path $script:runRoot 'gradlew.bat') --stop *> (Join-Path $script:logs 'gradle-stop.txt')
            $script:gradleStopExit = $LASTEXITCODE
            if ($script:gradleStopExit -ne 0) { throw "Isolated Gradle --stop failed with exit $script:gradleStopExit." }
            $deadline = [DateTime]::UtcNow.AddSeconds(10)
            do {
                $script:gradleDaemonsAfterStop = @(Get-RunOwnedGradleDaemons | ForEach-Object { [int]$_.ProcessId })
                if ($script:gradleDaemonsAfterStop.Count -eq 0) { break }
                Start-Sleep -Milliseconds 250
            } while ([DateTime]::UtcNow -lt $deadline)
            if ($script:gradleDaemonsAfterStop.Count -gt 0) { throw 'Run-owned Gradle daemon remains after isolated --stop.' }
        } catch { if ($null -eq $script:cleanupFailure) { $script:cleanupFailure = $_.Exception.Message } }
        Pop-Location
    }
    try {
        if (@(Get-RunOwnedServerProcesses).Count -gt 0) { $script:cleanupFailure = 'A run-owned Paper process remains after cleanup.' }
    } catch {
        if ($null -eq $script:cleanupFailure) { $script:cleanupFailure = "Could not verify Paper process cleanup: $($_.Exception.Message)" }
    }
    if ($null -ne $script:redisProcess -and -not $script:redisProcess.HasExited) { $script:cleanupFailure = 'The isolated Redis process remains after cleanup.' }
    if ($null -ne $script:cleanupFailure) { $script:status = 'FAIL' }
    $script:manifestPath = $null
    $manifestInRunRoot = $false
    if (Test-Path -LiteralPath $script:runRoot -PathType Container) {
        try {
            Assert-NoReparsePointAncestors $script:runRoot
            $script:manifestPath = Join-Path $script:runRoot 'result.json'
            $manifestInRunRoot = $true
        } catch {
            if ($null -eq $script:cleanupFailure) { $script:cleanupFailure = "Refusing run-directory manifest path: $($_.Exception.Message)" }
            $script:status = 'FAIL'
        }
    } elseif ($null -eq $script:cleanupFailure) {
        $script:cleanupFailure = 'Run directory was not created; using a sibling failure manifest.'
        $script:status = 'FAIL'
    }
    if ($null -eq $script:manifestPath) {
        try {
            Assert-NoReparsePointAncestors $reportsRoot
            $fallbackName = ([IO.Path]::GetFileName($script:runRoot) + '.result.json')
            $fallbackPath = Join-Path $reportsRoot $fallbackName
            if (Test-Path -LiteralPath $fallbackPath) { throw "Refusing to overwrite existing sibling manifest: $fallbackPath" }
            $script:manifestPath = $fallbackPath
            if ($null -eq $script:cleanupFailure) { $script:cleanupFailure = 'Run directory is unavailable or unsafe; using a sibling failure manifest.' }
            $script:status = 'FAIL'
        } catch {
            if ($null -eq $script:cleanupFailure) { $script:cleanupFailure = "Could not select a safe manifest path: $($_.Exception.Message)" }
            $script:status = 'FAIL'
            throw "Could not select a safe manifest path: $($_.Exception.Message)"
        }
    }
    $manifest = [ordered]@{
        task = 'T21 M06 LOCAL_REDIS legacy-key and AOF restart acceptance'
        status = $script:status
        platform = $Project
        minecraftVersion = $MinecraftVersion
        buildNumber = $BuildNumber
        serverJar = [IO.Path]::GetFileName($serverJarPath)
        serverSha256 = $serverSha256
        java = $javaBanner.Trim()
        candidatePlugin = $CandidateVersion
        candidatePluginSha256 = (Get-FileHash -LiteralPath $candidateJar -Algorithm SHA256).Hash.ToLowerInvariant()
        redisServer = $redisBanner
        redisPort = $script:redisPort
        redisDatabase = 15
        redisConfig = [ordered]@{ bind = '127.0.0.1'; appendonly = $true; appendfsync = 'always'; rdbSnapshots = $false }
        configAudit = $configObservation
        accounts = @($accountObservations)
        aofFilesBeforeRestart = @($script:aofBeforeRestart)
        aofFilesAfterRestart = @(Get-AofFiles)
        persistenceBeforeRestart = $persistenceBefore
        persistenceAfterRestart = $persistenceAfter
        stages = @($script:stages)
        acceptance = @($script:acceptance)
        pluginDataFiles = @($pluginDataFiles)
        localDatabaseFiles = @($pluginDataFiles | Where-Object { $_ -match '\.(db|sqlite|sqlite3)$' })
        redisProcessExitCodes = @($script:redisExitCodes)
        redisLifecycle = @($script:redisStages)
        redisForcedStopFallbackUsed = $script:redisForcedStop
        serverCleanupActions = @($script:serverCleanupActions)
        gradleDaemonsBeforeStop = @($script:gradleDaemonsBeforeStop)
        gradleDaemonsAfterStop = @($script:gradleDaemonsAfterStop)
        gradleStopExitCode = $script:gradleStopExit
        failure = $script:failure
        cleanupFailure = $script:cleanupFailure
        runMarker = $script:runMarker
        runDirectory = $script:runRoot
        resultManifestPath = $script:manifestPath
    }
    if ($null -ne $script:manifestPath) {
        try {
            if ($manifestInRunRoot) { Assert-NoReparsePointAncestors $script:runRoot }
            else { Assert-NoReparsePointAncestors $reportsRoot }
            $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $script:manifestPath -Encoding utf8
        } catch {
            $script:status = 'FAIL'
            $script:cleanupFailure = "Could not write result manifest: $($_.Exception.Message)"
            if ($manifestInRunRoot) {
                try {
                    Assert-NoReparsePointAncestors $reportsRoot
                    $fallbackPath = Join-Path $reportsRoot ([IO.Path]::GetFileName($script:runRoot) + '.result.json')
                    if (Test-Path -LiteralPath $fallbackPath) { throw "Refusing to overwrite existing sibling manifest: $fallbackPath" }
                    $manifest.status = 'FAIL'
                    $manifest.cleanupFailure = $script:cleanupFailure
                    $manifest.resultManifestPath = $fallbackPath
                    $manifestInRunRoot = $false
                    $script:manifestPath = $fallbackPath
                    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $script:manifestPath -Encoding utf8
                } catch {
                    $script:cleanupFailure = "Could not write run or sibling result manifest: $($_.Exception.Message)"
                    $script:manifestPath = $null
                }
            } else {
                $script:manifestPath = $null
            }
        }
    }
    } catch {
        $finalizationError = $_.Exception.Message
        $script:status = 'FAIL'
        $failureManifest = [ordered]@{
            task = 'T21 M06 LOCAL_REDIS legacy-key and AOF restart acceptance'
            status = 'FAIL'
            platform = $Project
            minecraftVersion = $MinecraftVersion
            buildNumber = $BuildNumber
            serverJar = [IO.Path]::GetFileName($serverJarPath)
            serverSha256 = $serverSha256
            java = $javaBanner.Trim()
            candidatePlugin = $CandidateVersion
            redisServer = $redisBanner
            redisPort = $script:redisPort
            redisDatabase = 15
            accounts = @($accountObservations)
            stages = @($script:stages)
            acceptance = @($script:acceptance)
            failure = $script:failure
            cleanupFailure = $script:cleanupFailure
            finalizationFailure = $finalizationError
            runMarker = $script:runMarker
            runDirectory = $script:runRoot
        }
        $failureManifestPaths = [Collections.Generic.List[string]]::new()
        try {
            if (Test-Path -LiteralPath $script:runRoot -PathType Container) {
                $failureManifestPaths.Add((Join-Path $script:runRoot 'result.json'))
            }
        } catch { }
        $failureManifestPaths.Add((Join-Path $reportsRoot ("finalizer-failure-$($script:runMarker).json")))
        foreach ($failureManifestPath in $failureManifestPaths) {
            try {
                $failureManifestParent = Split-Path -Path $failureManifestPath -Parent
                Assert-NoReparsePointAncestors $failureManifestParent
                if (Test-Path -LiteralPath $failureManifestPath) { continue }
                $failureManifest.resultManifestPath = $failureManifestPath
                $failureManifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $failureManifestPath -Encoding utf8
                $script:manifestPath = $failureManifestPath
                break
            } catch {
                if ($null -eq $script:cleanupFailure) {
                    $script:cleanupFailure = "Could not write a finalizer failure manifest: $($_.Exception.Message)"
                }
            }
        }
    } finally {
        if ($null -ne $environmentBefore) {
            foreach ($name in $environmentBefore.Keys) {
                $value = $environmentBefore[$name]
                if ($null -eq $value) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
                else { Set-Item "Env:$name" -Value $value }
            }
        }
    }
}

if ($null -ne $script:manifestPath -and (Test-Path -LiteralPath $script:manifestPath -PathType Leaf)) {
    Get-Content -LiteralPath $script:manifestPath
} else {
    throw "T21 M06 did not create a result manifest. Failure=$($script:failure); cleanup=$($script:cleanupFailure)."
}
if ($script:status -ne 'PASS') { throw "T21 M06 failed. Failure=$($script:failure); cleanup=$($script:cleanupFailure)." }
