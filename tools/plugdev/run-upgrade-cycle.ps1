param(
    [Parameter(Mandatory = $true)][ValidateSet('paper', 'folia', 'canvas')][string]$Project,
    [Parameter(Mandatory = $true)][string]$MinecraftVersion,
    [Parameter(Mandatory = $true)][int]$BuildNumber,
    [Parameter(Mandatory = $true)][string]$ServerJar,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedServerSha256,
    [Parameter(Mandatory = $true)][string]$BaselinePluginJar,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedBaselinePluginSha256,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$BaselineSourceCommit,
    [Parameter(Mandatory = $true)][string]$PlugDevRoot,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$CandidateVersion = '1.4.0-alpha.1'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$environmentNames = @(
    'JAVA_HOME', 'Path', 'PLUGDEV_ROOT', 'PLUGDEV_HOME', 'GRADLE_USER_HOME',
    'SYNCMONEY_TEST_SERVER_JAR', 'SYNCMONEY_CANDIDATE_VERSION', 'T21_RUN_MARKER',
    'T21_CANDIDATE_SHA256', 'T21_BASELINE_SHA256'
)
$script:originalEnvironment = [ordered]@{}
foreach ($environmentName in $environmentNames) {
    $script:originalEnvironment[$environmentName] = [Environment]::GetEnvironmentVariable($environmentName, 'Process')
}

function Assert-NoReparsePointAncestors([string]$path) {
    $current = [IO.Path]::GetFullPath($path)
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

if ($MinecraftVersion -notmatch '^\d{1,3}\.\d{1,3}(?:\.\d{1,3})?$') {
    throw "Invalid Minecraft version '$MinecraftVersion'; expected a numeric version such as 1.21.6 or 26.3."
}
if ($BuildNumber -le 0) { throw 'BuildNumber must be a positive integer.' }
if ($CandidateVersion -notmatch '^\d+\.\d+\.\d+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?$') {
    throw "Invalid candidate version '$CandidateVersion'; expected a numeric release version with an optional prerelease suffix."
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$reportsRoot = [IO.Path]::GetFullPath((Join-Path $root 'build\reports\foundation\T21-upgrade-cycles'))
Assert-NoReparsePointAncestors $root
Assert-NoReparsePointAncestors $reportsRoot
if (-not (Test-Path -LiteralPath $reportsRoot -PathType Container)) {
    New-Item -ItemType Directory -Force -Path $reportsRoot -ErrorAction Stop | Out-Null
    Assert-NoReparsePointAncestors $reportsRoot
}
$serverJarPath = (Resolve-Path -LiteralPath $ServerJar).Path
$serverSha256 = (Get-FileHash -LiteralPath $serverJarPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($serverSha256 -ne $ExpectedServerSha256.ToLowerInvariant()) {
    throw "Server artifact SHA-256 mismatch: expected $($ExpectedServerSha256.ToLowerInvariant()), observed $serverSha256."
}
$baselineJarPath = (Resolve-Path -LiteralPath $BaselinePluginJar).Path
$baselineSha256 = (Get-FileHash -LiteralPath $baselineJarPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($baselineSha256 -ne $ExpectedBaselinePluginSha256.ToLowerInvariant()) {
    throw "Baseline plugin SHA-256 mismatch: expected $($ExpectedBaselinePluginSha256.ToLowerInvariant()), observed $baselineSha256."
}
$baselineSourceRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $baselineJarPath))
$actualBaselineSourceCommit = (& git -C $baselineSourceRoot rev-parse HEAD 2>$null | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $actualBaselineSourceCommit -ne $BaselineSourceCommit.ToLowerInvariant()) {
    throw "Baseline source checkout mismatch: expected commit $($BaselineSourceCommit.ToLowerInvariant()), observed '$actualBaselineSourceCommit'."
}

$candidateJar = Join-Path $root "build\libs\Syncmoney-$CandidateVersion.jar"
$baselineJar = Join-Path $root 'build\libs\Syncmoney-1.3.2.jar'
$acceptanceJar = Join-Path $root 'build\acceptance\SyncmoneyAcceptance.jar'
$apiConsumerJar = Join-Path $root 'build\acceptance\SyncmoneyApiConsumer.jar'
foreach ($required in @($candidateJar, $acceptanceJar, $apiConsumerJar)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required candidate/acceptance artifact is missing: $required"
    }
}

$javaHomePath = (Resolve-Path -LiteralPath $JavaHome).Path
$java = Join-Path $javaHomePath 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw "Java executable not found: $java" }
$javaBanner = (& $java -version 2>&1 | Out-String)
if ($javaBanner -notmatch 'version "25(?:\.|"|\+)') {
    throw "T21 requires Java 25 for this runtime cycle; observed: $($javaBanner.Trim())"
}
$plugDevRootPath = (Resolve-Path -LiteralPath $PlugDevRoot).Path
$cli = Join-Path $plugDevRootPath 'packages\cli\dist\cli.js'
if (-not (Test-Path -LiteralPath $cli -PathType Leaf)) { throw "Local PlugDev CLI not found: $cli" }
$sqliteCommand = Get-Command sqlite3.exe, sqlite3 -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $sqliteCommand -or $sqliteCommand.CommandType -ne 'Application') {
    throw 'T21 M04 requires the local sqlite3 command-line tool to seed and inspect the disposable historical database.'
}
$sqliteExecutable = $sqliteCommand.Source
$sqliteVersion = (& $sqliteExecutable --version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw "sqlite3 --version exited $LASTEXITCODE." }

$runId = "${Project}-${MinecraftVersion}-${BuildNumber}-$(Get-Date -Format 'yyyyMMdd-HHmmss')-$([guid]::NewGuid().ToString('N'))"
$runRoot = [IO.Path]::GetFullPath((Join-Path $reportsRoot $runId))
$reportsPrefix = $reportsRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $runRoot.StartsWith($reportsPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing run directory outside the T21 report root: $runRoot"
}
if (Test-Path -LiteralPath $runRoot) { throw "Refusing to reuse an existing isolated run directory: $runRoot" }

$logs = Join-Path $runRoot 'logs'
New-Item -ItemType Directory -Path $runRoot -ErrorAction Stop | Out-Null
Assert-NoReparsePointAncestors $runRoot
New-Item -ItemType Directory -Force -Path $logs, (Join-Path $runRoot 'gradle'), (Join-Path $runRoot 'tools\plugdev'), (Join-Path $runRoot 'src\main\resources'), (Join-Path $runRoot 'build\libs'), (Join-Path $runRoot 'build\acceptance'), (Join-Path $runRoot '.plugdev-home'), (Join-Path $runRoot '.gradle-user-home') | Out-Null
foreach ($name in @('build.gradle', 'settings.gradle', 'gradlew.bat')) {
    Copy-Item -LiteralPath (Join-Path $root $name) -Destination (Join-Path $runRoot $name)
}
Copy-Item -LiteralPath (Join-Path $root 'gradle\wrapper') -Destination (Join-Path $runRoot 'gradle') -Recurse
$settingsText = Get-Content -LiteralPath (Join-Path $runRoot 'settings.gradle') -Raw
$includedProjects = [regex]::Matches($settingsText, '(?m)^\s*include\s*\(([^)]*)\)')
$createdProjectDirectories = 0
foreach ($include in $includedProjects) {
    foreach ($projectNameMatch in [regex]::Matches($include.Groups[1].Value, '["'']([^"'']+)["'']')) {
        $projectPath = $projectNameMatch.Groups[1].Value.TrimStart(':') -replace ':', '\'
        if (-not $projectPath) { continue }
        $targetProjectPath = [IO.Path]::GetFullPath((Join-Path $runRoot $projectPath))
        if (-not $targetProjectPath.StartsWith(($runRoot.TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase)) {
            throw "Included Gradle project escapes the isolated run directory: $projectPath"
        }
        $sourceProjectPath = Join-Path $root $projectPath
        $sourceBuildFile = Join-Path $sourceProjectPath 'build.gradle'
        if (-not (Test-Path -LiteralPath $sourceBuildFile -PathType Leaf)) {
            throw "Included Gradle project is missing its build.gradle: $sourceBuildFile"
        }
        New-Item -ItemType Directory -Force -Path $targetProjectPath | Out-Null
        Copy-Item -LiteralPath $sourceBuildFile -Destination (Join-Path $targetProjectPath 'build.gradle')
        $createdProjectDirectories++
    }
}
if ($createdProjectDirectories -eq 0) {
    throw 'Could not find included Gradle subprojects to create in the isolated PlugDev project.'
}
Copy-Item -LiteralPath (Join-Path $root 'tools\plugdev\prepare.mjs') -Destination (Join-Path $runRoot 'tools\plugdev\prepare.mjs')
foreach ($name in @('config.yml', 'plugin.yml')) {
    Copy-Item -LiteralPath (Join-Path $root "src\main\resources\$name") -Destination (Join-Path $runRoot "src\main\resources\$name")
}
Copy-Item -LiteralPath $candidateJar -Destination (Join-Path $runRoot "build\libs\Syncmoney-$CandidateVersion.jar")
Copy-Item -LiteralPath $baselineJarPath -Destination (Join-Path $runRoot 'build\libs\Syncmoney-1.3.2.jar')
Copy-Item -LiteralPath $acceptanceJar -Destination (Join-Path $runRoot 'build\acceptance\SyncmoneyAcceptance.jar')
Copy-Item -LiteralPath $apiConsumerJar -Destination (Join-Path $runRoot 'build\acceptance\SyncmoneyApiConsumer.jar')

$profileEditor = Join-Path $runRoot 'edit-profile.cjs'
@'
const fs = require("node:fs");
const path = require("node:path");
const { createRequire } = require("node:module");
const yaml = createRequire(path.join(process.env.PLUGDEV_ROOT, "package.json"))("yaml");
const action = process.argv[2];
const profilePath = path.resolve(".plugdev/profile.yml");
const configPath = path.resolve(".plugdev/run/plugins/Syncmoney/config.yml");
const expectedSqlitePath = "plugins/Syncmoney/t21-data/legacy-local.db";
function readConfig() {
  const config = yaml.parse(fs.readFileSync(configPath, "utf8"));
  if (config?.["config-version"] !== 12) throw new Error(`Expected config schema 12, observed ${config?.["config-version"]}`);
  if (config?.economy?.local?.["sqlite-path"] !== expectedSqlitePath) {
    throw new Error(`Expected custom SQLite path ${expectedSqlitePath}, observed ${config?.economy?.local?.["sqlite-path"]}`);
  }
  if (config?.["t21-synthetic-operator-fixture"]?.customValue !== "preserve-through-upgrade") {
    throw new Error("Synthetic operator config fixture was not preserved.");
  }
  return { configSchema: config["config-version"], sqliteConfigPath: config.economy.local["sqlite-path"], operatorFixturePreserved: true };
}
if (action === "seed-config") {
  const config = yaml.parse(fs.readFileSync(configPath, "utf8"));
  config.economy ??= {};
  config.economy.local ??= {};
  config.economy.local["sqlite-path"] = expectedSqlitePath;
  config["t21-synthetic-operator-fixture"] = { customValue: "preserve-through-upgrade" };
  fs.writeFileSync(configPath, yaml.stringify(config), "utf8");
  console.log(JSON.stringify(readConfig()));
} else if (action === "assert-config") {
  console.log(JSON.stringify(readConfig()));
} else if (action === "set-plugin") {
  const profile = yaml.parse(fs.readFileSync(profilePath, "utf8"));
  profile.build.jarPattern = process.argv[3];
  profile.jvm.args = [
    `-Dsyncmoney.acceptance.version=${process.argv[4]}`,
    `-Dsyncmoney.t21.run=${process.env.T21_RUN_MARKER}`,
  ];
  fs.writeFileSync(profilePath, yaml.stringify(profile), "utf8");
  console.log(JSON.stringify({ pluginVersion: process.argv[4], runMarkerConfigured: Boolean(process.env.T21_RUN_MARKER) }));
} else {
  throw new Error(`Unknown profile action: ${action}`);
}
'@ | Set-Content -LiteralPath $profileEditor -Encoding utf8

$script:serverMayExist = $false
$script:gradleDaemonStopExitCode = $null
$script:currentStage = 'prepare'
$script:legacyApiConsumerFailureExpected = $false
$script:failureMessage = $null
$script:cleanupFailure = $null
$script:locationPushed = $false
$script:stageRecords = [System.Collections.Generic.List[object]]::new()
$script:configObservations = [System.Collections.Generic.List[object]]::new()
$script:sqliteObservations = [System.Collections.Generic.List[object]]::new()
$script:cleanupEvents = [System.Collections.Generic.List[object]]::new()
$databaseRelativePath = '.plugdev\run\plugins\Syncmoney\t21-data\legacy-local.db'
$databasePath = Join-Path $runRoot $databaseRelativePath
$fixtureSqlPath = Join-Path $runRoot 'legacy-local-sqlite-v1.3.2.sql'

function Invoke-ProfileEditor([string]$action, [string[]]$arguments = @()) {
    $outputPath = Join-Path $logs "profile-$action-$script:currentStage.txt"
    $editorOutput = @(& node $profileEditor $action @arguments 2>&1)
    $editorExit = $LASTEXITCODE
    $editorOutput | Set-Content -LiteralPath $outputPath -Encoding utf8
    if ($editorExit -ne 0) { throw "Profile editor action '$action' exited $editorExit." }
    if ($action -eq 'seed-config' -or $action -eq 'assert-config') {
        $jsonText = ($editorOutput | ForEach-Object { $_.ToString() }) -join "`n"
        $observation = $jsonText | ConvertFrom-Json
        $script:configObservations.Add([ordered]@{ stage = $script:currentStage; configSchema = $observation.configSchema; sqliteConfigPath = $observation.sqliteConfigPath; operatorFixturePreserved = $observation.operatorFixturePreserved })
        return $observation
    }
}

function Invoke-SqliteQuery([string]$query, [switch]$ReadOnly) {
    $sqliteArguments = @('-batch', '-noheader', '-separator', '|')
    if ($ReadOnly) { $sqliteArguments += '-readonly' }
    $sqliteArguments += @($databasePath, $query)
    $output = @(& $sqliteExecutable @sqliteArguments 2>&1)
    $sqliteExit = $LASTEXITCODE
    if ($sqliteExit -ne 0) {
        throw "sqlite3 query exited ${sqliteExit}: $($output -join ' ')"
    }
    return @($output | ForEach-Object { $_.ToString() })
}

function Assert-LegacySqliteState([string]$expectedA, [string]$expectedB, [string]$stage) {
    $balanceRows = @(Invoke-SqliteQuery "SELECT uuid || '|' || name || '|' || typeof(balance) || '|' || printf('%.2f', balance) FROM player_balances WHERE uuid IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002') ORDER BY uuid;" -ReadOnly)
    $expectedRows = @(
        "00000000-0000-0000-0000-000000000001|fixture-a|real|$expectedA",
        "00000000-0000-0000-0000-000000000002|fixture-b|real|$expectedB"
    )
    if (($balanceRows -join "`n") -ne ($expectedRows -join "`n")) {
        throw "SQLite account rows mismatch after $stage. Expected '$($expectedRows -join '; ')'; observed '$($balanceRows -join '; ')'."
    }
    $realColumn = @(Invoke-SqliteQuery "SELECT COUNT(*) FROM pragma_table_info('player_balances') WHERE lower(name)='balance' AND upper(type)='REAL';" -ReadOnly)
    if ($realColumn.Count -ne 1 -or $realColumn[0] -ne '1') { throw "Historical player_balances.balance REAL schema was not preserved after $stage." }
    $historicalTransaction = @(Invoke-SqliteQuery "SELECT id || '|' || uuid || '|' || name || '|' || type || '|' || typeof(amount) || '|' || printf('%.2f', amount) || '|' || typeof(balance_before) || '|' || printf('%.2f', balance_before) || '|' || typeof(balance_after) || '|' || printf('%.2f', balance_after) || '|' || source || '|' || timestamp || '|' || sequence FROM transactions WHERE id=1;" -ReadOnly)
    $expectedTransaction = '1|00000000-0000-0000-0000-000000000001|fixture-a|DEPOSIT|real|100.00|real|0.00|real|100.00|TEST|1700000000000|1'
    if ($historicalTransaction.Count -ne 1 -or $historicalTransaction[0] -ne $expectedTransaction) {
        throw "Historical SQLite transaction row changed or became unreadable after ${stage}: '$($historicalTransaction -join '; ')'."
    }
    $observation = [ordered]@{
        stage = $stage
        balanceRows = $balanceRows
        balanceDeclaredType = 'REAL'
        historicalTransaction = $historicalTransaction[0]
    }
    $script:sqliteObservations.Add($observation)
    return $observation
}

function Wait-ServerMarker([string]$marker, [int]$timeoutSeconds = 45) {
    $serverLog = Join-Path $runRoot '.plugdev\run\logs\latest.log'
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    do {
        if (Test-Path -LiteralPath $serverLog) {
            $fatalPatterns = 'ACCEPTANCE FAIL|T21_WRITE_FAIL|T21_READ_FAIL|T21_LEGACY_SQLITE_FAIL|Error occurred while enabling Syncmoney|Could not load plugin'
            if (-not $script:legacyApiConsumerFailureExpected) {
                $fatalPatterns += '|API CLASSLOADER FAIL'
            }
            if (Select-String -LiteralPath $serverLog -Pattern $fatalPatterns -Quiet) {
                throw "Runtime failure marker found while waiting for '$marker'; see $serverLog."
            }
            if (Select-String -LiteralPath $serverLog -SimpleMatch $marker -Quiet) { return }
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for runtime marker '$marker'; see $serverLog."
}

function Test-RunMarker([string]$commandLine) {
    if ([string]::IsNullOrWhiteSpace($commandLine)) { return $false }
    return $commandLine.IndexOf('-Dsyncmoney.t21.run=', [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $commandLine.IndexOf($runRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0
}

function Get-RunOwnedServerProcesses {
    return @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop | Where-Object {
        $_.Name -in @('java.exe', 'javaw.exe') -and
        (Test-RunMarker $_.CommandLine) -and
        $_.CommandLine -notmatch 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon'
    })
}

function Get-RunOwnedGradleDaemons {
    $isolatedGradleHome = Join-Path $runRoot '.gradle-user-home'
    return @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop | Where-Object {
        $_.Name -in @('java.exe', 'javaw.exe') -and
        $_.CommandLine -and
        $_.CommandLine.IndexOf($isolatedGradleHome, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $_.CommandLine -match 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon'
    })
}

function Stop-RunOwnedGradleDaemons {
    $owned = @(Get-RunOwnedGradleDaemons)
    if ($owned.Count -eq 0) {
        $script:cleanupEvents.Add([ordered]@{ stage = $script:currentStage; action = 'no-run-owned-gradle-daemon'; verified = $true })
        return
    }

    $stopLog = Join-Path $logs 'gradle-daemon-stop.txt'
    Push-Location $runRoot
    try {
        & (Join-Path $runRoot 'gradlew.bat') --stop *> $stopLog
        $script:gradleDaemonStopExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($script:gradleDaemonStopExitCode -ne 0) {
        throw "Gradle --stop exited $script:gradleDaemonStopExitCode; see $stopLog."
    }

    $deadline = (Get-Date).AddSeconds(20)
    do {
        $remaining = @(Get-RunOwnedGradleDaemons)
        if ($remaining.Count -eq 0) {
            $script:cleanupEvents.Add([ordered]@{ stage = $script:currentStage; action = 'run-owned-gradle-daemons-stopped'; exitCode = $script:gradleDaemonStopExitCode; processExitVerified = $true })
            return
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    $remaining = @(Get-RunOwnedGradleDaemons)
    if ($remaining.Count -gt 0) {
        throw "Run-owned Gradle daemon process did not exit; process IDs: $($remaining.ProcessId -join ', ')."
    }
}

function Wait-RunServerStopped([int]$timeoutSeconds = 20) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    do {
        $owned = @(Get-RunOwnedServerProcesses)
        if ($owned.Count -eq 0) { return }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    $left = @(Get-RunOwnedServerProcesses)
    if ($left.Count -gt 0) { throw "T21-owned server process did not exit; process IDs: $($left.ProcessId -join ', ')." }
}

function Stop-RunOwnedServerProcesses([string]$reason) {
    $owned = @(Get-RunOwnedServerProcesses)
    foreach ($process in $owned) {
        Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop
    }
    if ($owned.Count -gt 0) {
        Wait-RunServerStopped
        $script:cleanupEvents.Add([ordered]@{ stage = $script:currentStage; action = 'force-stop-owned-process'; reason = $reason; processIds = @($owned.ProcessId) })
    } else {
        $script:cleanupEvents.Add([ordered]@{ stage = $script:currentStage; action = 'no-owned-process'; reason = $reason; verified = $true })
    }
    $script:serverMayExist = $false
}

function Read-RunSession {
    $sessionPath = Join-Path $runRoot '.plugdev\session.json'
    if (-not (Test-Path -LiteralPath $sessionPath -PathType Leaf)) { return $null }
    $sessionText = Get-Content -LiteralPath $sessionPath -Raw
    if ([string]::IsNullOrWhiteSpace($sessionText)) { return $null }
    try {
        $session = $sessionText | ConvertFrom-Json
        $expectedRunDir = [IO.Path]::GetFullPath((Join-Path $runRoot '.plugdev\run'))
        $sessionRunDir = if ($session.runDir) { [IO.Path]::GetFullPath([string]$session.runDir) } else { '' }
        if ($sessionRunDir -ne $expectedRunDir) { throw 'session runDir does not match this isolated run' }
        if ([string]$session.rconHost -ne '127.0.0.1' -or [int]$session.gamePort -ne 25675 -or [int]$session.rconPort -ne 35675) {
            throw 'session endpoint does not match this isolated acceptance profile'
        }
        if ([int]$session.pid -le 0) { throw 'session PID is invalid' }
        return $session
    } catch {
        throw "Could not validate the isolated PlugDev session: $($_.Exception.Message)"
    }
}

function Get-SessionOwnedProcesses($session) {
    $sessionPid = [int]$session.pid
    return @(Get-RunOwnedServerProcesses | Where-Object { [int]$_.ProcessId -eq $sessionPid })
}

function Remove-RunSessionFile {
    $sessionPath = Join-Path $runRoot '.plugdev\session.json'
    if (-not (Test-Path -LiteralPath $sessionPath -PathType Leaf)) { return }
    $item = Get-Item -LiteralPath $sessionPath -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Refusing to remove a reparse-point PlugDev session: $sessionPath"
    }
    Remove-Item -LiteralPath $sessionPath -Force -ErrorAction Stop
}

function Start-UpgradeServer([string]$jarPattern, [string]$pluginVersion, [string]$stage) {
    $script:currentStage = $stage
    $script:legacyApiConsumerFailureExpected = ($pluginVersion -eq '1.3.2')
    $script:serverMayExist = $true
    Invoke-ProfileEditor 'set-plugin' @($jarPattern, $pluginVersion)
    $startOutput = Join-Path $logs "$stage-start.txt"
    & node $cli server start --config .plugdev\profile.yml *> $startOutput
    $startExit = $LASTEXITCODE
    if ($startExit -ne 0) {
        throw "PlugDev start for $stage exited $startExit; owned-process cleanup will run in finally; see $startOutput."
    }
    Invoke-UpgradeCommand 'smaccept version' "$stage-version-check"
    Wait-ServerMarker "T21_VERSION_PASS expected=$pluginVersion actual=$pluginVersion"
    Wait-ServerMarker 'Syncmoney enabled successfully'
    $apiClassLoader = 'NOT_APPLICABLE'
    if ($pluginVersion -ne '1.3.2') {
        Wait-ServerMarker 'API CLASSLOADER PASS provider=Syncmoney'
        $apiClassLoader = 'PASS'
    }
    $script:stageRecords.Add([ordered]@{ stage = $stage; pluginVersion = $pluginVersion; startExitCode = $startExit; versionProbe = 'PASS'; apiClassLoader = $apiClassLoader })
}

function Invoke-UpgradeCommand([string]$command, [string]$stage) {
    $script:currentStage = $stage
    $commandOutput = Join-Path $logs "$stage-command.txt"
    & node $cli server command $command *> $commandOutput
    if ($LASTEXITCODE -ne 0) { throw "RCON command for $stage exited $LASTEXITCODE; see $commandOutput." }
}

function Stop-UpgradeServer([string]$stage) {
    $script:currentStage = $stage
    $stopOutput = Join-Path $logs "$stage-stop.txt"
    $session = Read-RunSession
    if ($null -eq $session) { throw "PlugDev session missing before normal stop for ${stage}." }
    $ownedSessionProcesses = @(Get-SessionOwnedProcesses $session)
    if ($ownedSessionProcesses.Count -ne 1) {
        throw "Refusing RCON stop: session PID is not the uniquely marked process owned by this run."
    }
    & node $cli server command stop *> $stopOutput
    $stopExit = $LASTEXITCODE
    if ($stopExit -ne 0) { throw "PlugDev RCON stop for $stage exited $stopExit; see $stopOutput." }
    $forced = $false
    try {
        Wait-RunServerStopped -timeoutSeconds 10
    } catch {
        $forced = $true
        Stop-RunOwnedServerProcesses "PlugDev stop left a process running (exit $stopExit)."
    }
    if ($forced) { throw "PlugDev stop for $stage returned success but did not exit the owned server process; forced cleanup was required." }
    Remove-RunSessionFile
    $script:serverMayExist = $false
    $serverLog = Join-Path $runRoot '.plugdev\run\logs\latest.log'
    if (-not (Test-Path -LiteralPath $serverLog -PathType Leaf)) { throw "Expected server log missing after ${stage}: $serverLog" }
    Copy-Item -LiteralPath $serverLog -Destination (Join-Path $logs "$stage-server.log")
    $script:stageRecords.Add([ordered]@{ stage = $stage; stopExitCode = $stopExit; stopMethod = 'owned-session RCON command'; processExitVerified = $true; forcedCleanup = $false })
}

$fixtureSource = Join-Path $root 'syncmoney-testkit\src\testFixtures\resources\sql\legacy-local-sqlite-v1.3.2.sql'
if (-not (Test-Path -LiteralPath $fixtureSource -PathType Leaf)) { throw "Historical SQLite fixture not found: $fixtureSource" }
$fixtureSha256 = (Get-FileHash -LiteralPath $fixtureSource -Algorithm SHA256).Hash.ToLowerInvariant()
Copy-Item -LiteralPath $fixtureSource -Destination $fixtureSqlPath

try {
    $env:JAVA_HOME = $javaHomePath
    $env:Path = "$javaHomePath\bin;$env:Path"
    $env:PLUGDEV_ROOT = $plugDevRootPath
    $env:PLUGDEV_HOME = Join-Path $runRoot '.plugdev-home'
    $env:GRADLE_USER_HOME = Join-Path $runRoot '.gradle-user-home'
    $env:SYNCMONEY_TEST_SERVER_JAR = $serverJarPath
    $env:SYNCMONEY_CANDIDATE_VERSION = $CandidateVersion
    $env:T21_RUN_MARKER = $runRoot
    $env:T21_CANDIDATE_SHA256 = (Get-FileHash -LiteralPath $candidateJar -Algorithm SHA256).Hash.ToLowerInvariant()
    $env:T21_BASELINE_SHA256 = $baselineSha256

    Push-Location $runRoot
    $script:locationPushed = $true
    & node tools\plugdev\prepare.mjs $Project $MinecraftVersion local off $CandidateVersion *> (Join-Path $logs 'prepare.txt')
    if ($LASTEXITCODE -ne 0) { throw "prepare.mjs exited $LASTEXITCODE; see $logs\prepare.txt." }
    $null = Invoke-ProfileEditor 'seed-config'

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $databasePath) | Out-Null
    $sqliteScript = '.read "' + ($fixtureSqlPath -replace '\\', '/') + '"'
    $seedOutput = @(& $sqliteExecutable -batch $databasePath $sqliteScript 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Could not seed the disposable historical SQLite database: $($seedOutput -join ' ')." }
    $null = Assert-LegacySqliteState '100.00' '0.00' 'fixture initialization'

    Start-UpgradeServer 'build/libs/Syncmoney-1.3.2.jar' '1.3.2' 'baseline-1.3.2'
    Invoke-UpgradeCommand 'smaccept legacy-sqlite read' 'baseline-1.3.2-read'
    Wait-ServerMarker 'T21_LEGACY_SQLITE_PASS operation=read expected-a=100.00 expected-b=0.00'
    Stop-UpgradeServer 'baseline-1.3.2'
    $null = Invoke-ProfileEditor 'assert-config'
    $null = Assert-LegacySqliteState '100.00' '0.00' '1.3.2 stop'

    Start-UpgradeServer "build/libs/Syncmoney-$CandidateVersion.jar" $CandidateVersion 'upgrade-1.4'
    Invoke-UpgradeCommand 'smaccept legacy-sqlite read' 'upgrade-1.4-read'
    Wait-ServerMarker 'T21_LEGACY_SQLITE_PASS operation=read expected-a=100.00 expected-b=0.00'
    $null = Invoke-ProfileEditor 'assert-config'
    Invoke-UpgradeCommand 'smaccept legacy-sqlite write 1337.25 765.50' 'upgrade-1.4-write'
    Wait-ServerMarker 'T21_LEGACY_SQLITE_PASS operation=write expected-a=1337.25 expected-b=765.50'
    Stop-UpgradeServer 'upgrade-1.4'
    $null = Invoke-ProfileEditor 'assert-config'
    $null = Assert-LegacySqliteState '1337.25' '765.50' '1.4.0 normal stop'

    Start-UpgradeServer 'build/libs/Syncmoney-1.3.2.jar' '1.3.2' 'rollback-1.3.2'
    Invoke-UpgradeCommand 'smaccept legacy-sqlite read 1337.25 765.50' 'rollback-1.3.2-read'
    Wait-ServerMarker 'T21_LEGACY_SQLITE_PASS operation=read expected-a=1337.25 expected-b=765.50'
    $null = Invoke-ProfileEditor 'assert-config'
    Stop-UpgradeServer 'rollback-1.3.2'
    $null = Assert-LegacySqliteState '1337.25' '765.50' '1.3.2 rollback stop'
} catch {
    $script:failureMessage = $_.Exception.Message
} finally {
    try {
    if ($script:serverMayExist) {
        try {
            $session = Read-RunSession
            if ($null -ne $session) {
                $ownedSessionProcesses = @(Get-SessionOwnedProcesses $session)
                if ($ownedSessionProcesses.Count -eq 1) {
                    $cleanupLog = Join-Path $logs 'finally-stop.txt'
                    & node $cli server command stop *> $cleanupLog
                    $stopExit = $LASTEXITCODE
                    $script:cleanupEvents.Add([ordered]@{ stage = $script:currentStage; action = 'owned-session-rcon-stop'; exitCode = $stopExit })
                } elseif (@(Get-RunOwnedServerProcesses).Count -eq 0) {
                    $script:cleanupEvents.Add([ordered]@{ stage = $script:currentStage; action = 'no-run-owned-process-to-stop'; verified = $true })
                } else {
                    $script:cleanupEvents.Add([ordered]@{ stage = $script:currentStage; action = 'session-pid-not-run-owned'; verified = $false })
                }
            }
        } catch {
            $script:cleanupFailure = $_.Exception.Message
            $script:cleanupEvents.Add([ordered]@{ stage = $script:currentStage; action = 'session-validation-or-rcon-stop-failed'; verified = $false })
        }
        try {
            Wait-RunServerStopped -timeoutSeconds 10
        } catch {
            try {
                Stop-RunOwnedServerProcesses 'finally cleanup after failed T21 stage'
                $script:cleanupEvents.Add([ordered]@{ stage = $script:currentStage; action = 'forced-cleanup-after-failure'; verified = $true })
            } catch {
                $script:cleanupFailure = $_.Exception.Message
            }
        }
        try {
            $remaining = @(Get-RunOwnedServerProcesses)
            if ($remaining.Count -gt 0) {
                $script:cleanupFailure = "Owned server process remains after cleanup: $($remaining.ProcessId -join ', ')."
            } else {
                try {
                    Remove-RunSessionFile
                    $script:serverMayExist = $false
                } catch {
                    $script:cleanupFailure = $_.Exception.Message
                }
            }
        } catch {
            $queryFailure = "Could not verify run-owned server process cleanup: $($_.Exception.Message)"
            if ($null -eq $script:cleanupFailure) {
                $script:cleanupFailure = $queryFailure
            } else {
                $script:cleanupFailure = "$script:cleanupFailure; $queryFailure"
            }
            $script:cleanupEvents.Add([ordered]@{ stage = $script:currentStage; action = 'final-process-query-failed'; verified = $false })
        }
    }
    try {
        Stop-RunOwnedGradleDaemons
    } catch {
        if ($null -eq $script:cleanupFailure) { $script:cleanupFailure = $_.Exception.Message }
        $script:cleanupEvents.Add([ordered]@{ stage = $script:currentStage; action = 'gradle-daemon-cleanup-failed'; verified = $false })
    }
    if ($script:locationPushed) { Pop-Location }
    $result = [ordered]@{
        task = 'T21 M04 historical LOCAL SQLite upgrade and normal-drain rollback cycle'
        status = if ($null -eq $script:failureMessage -and $null -eq $script:cleanupFailure) { 'PASS' } else { 'FAIL' }
        platform = $Project
        minecraftVersion = $MinecraftVersion
        buildNumber = $BuildNumber
        serverArtifact = [IO.Path]::GetFileName($serverJarPath)
        serverSha256 = $serverSha256
        java = $javaBanner.Trim()
        sqlite3 = $sqliteVersion
        baselinePlugin = '1.3.2'
        baselinePluginSha256 = $baselineSha256
        baselineSourceCommit = $actualBaselineSourceCommit
        candidatePlugin = $CandidateVersion
        candidatePluginSha256 = $env:T21_CANDIDATE_SHA256
        fixtureSha256 = $fixtureSha256
        sqlitePath = $databaseRelativePath
        isolatedHomes = [ordered]@{ plugDev = $env:PLUGDEV_HOME; gradle = $env:GRADLE_USER_HOME }
        configObservations = @($script:configObservations)
        sqliteObservations = @($script:sqliteObservations)
        stageRecords = @($script:stageRecords)
        cleanupEvents = @($script:cleanupEvents)
        gradleDaemonStopExitCode = $script:gradleDaemonStopExitCode
        failure = $script:failureMessage
        cleanupFailure = $script:cleanupFailure
        runDirectory = $runRoot
    }
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runRoot 'result.json') -Encoding utf8
    } finally {
        foreach ($environmentName in $environmentNames) {
            $originalValue = $script:originalEnvironment[$environmentName]
            if ($null -eq $originalValue) {
                Remove-Item -LiteralPath "Env:$environmentName" -ErrorAction SilentlyContinue
            } else {
                Set-Item -LiteralPath "Env:$environmentName" -Value $originalValue
            }
        }
    }
}

Get-Content -LiteralPath (Join-Path $runRoot 'result.json')
if ($null -ne $script:failureMessage) {
    if ($null -ne $script:cleanupFailure) { throw "$($script:failureMessage) Cleanup failure: $($script:cleanupFailure)" }
    throw $script:failureMessage
}
if ($null -ne $script:cleanupFailure) { throw "T21 cleanup failed: $($script:cleanupFailure)" }
