[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('paper-1216-min', 'paper-263-max', 'paper-263-currentmax', 'paper-263-currentstable', 'folia-1216-min', 'folia-262-max', 'folia-263-currentmax', 'canvas-1218-min', 'canvas-263-max', 'canvas-263-currentmax')]
    [string]$Endpoint,
    [string]$PlugDevRoot = 'C:\Users\margo\IdeaProjects\plugdev-main',
    [string]$MineflayerRoot = 'C:\Users\margo\IdeaProjects\mineflayer-master'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'M10 runtime runner requires PowerShell 7 or later; invoke it with pwsh.' }

$canonicalPlugDevRoot = 'C:\Users\margo\IdeaProjects\plugdev-main'
$canonicalMineflayerRoot = 'C:\Users\margo\IdeaProjects\mineflayer-master'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $repoRoot 'tools\plugdev\recovery-spool-validator.ps1')
$candidateVersion = '1.4.0-alpha.1'
$javaHome = 'C:\Program Files\Java\jdk-25.0.4'
$plugDevCli = Join-Path $PlugDevRoot 'packages\cli\dist\cli.js'
$plugDevCliDistRoot = Join-Path $PlugDevRoot 'packages\cli\dist'
$nodeCommand = Get-Command node -ErrorAction Stop
$nodeExe = $nodeCommand.Source
$redisServer = (Get-Command redis-server.exe, redis-server -ErrorAction Stop | Select-Object -First 1).Source
$redisCli = (Get-Command redis-cli.exe, redis-cli -ErrorAction Stop | Select-Object -First 1).Source
$endpointPins = @{
    'paper-1216-min' = [ordered]@{ software = 'paper'; label = 'Paper 1.21.6#48'; minecraftVersion = '1.21.6'; build = 48; artifact = 'paper-1.21.6-48.jar'; sha256 = '35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214' }
    'paper-263-max' = [ordered]@{ software = 'paper'; label = 'Paper 26.3#166'; minecraftVersion = '26.3'; build = 166; artifact = 'paper-26.3-166.jar'; sha256 = 'dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd' }
    'paper-263-currentmax' = [ordered]@{ software = 'paper'; label = 'Paper 26.3#200'; minecraftVersion = '26.3'; build = 200; artifact = 'paper-26.3-200.jar'; sha256 = '0e104a19ad818f05266a4571c0a9b0cada241c2016ee64883e5d38435332fa8d' }
    'paper-263-currentstable' = [ordered]@{ software = 'paper'; label = 'Paper 26.3#194 (CurrentStable)'; minecraftVersion = '26.3'; build = 194; artifact = 'paper-26.3-194.jar'; sha256 = '5fac0b6937b7aecea1b5b40e6a992f7b0802e4271413be35a6913d30df960784' }
    'folia-1216-min' = [ordered]@{ software = 'folia'; label = 'Folia 1.21.6#6'; minecraftVersion = '1.21.6'; build = 6; artifact = 'folia-1.21.6-6.jar'; sha256 = '917a8abaa542c1d0ef0e969693e227a7bfe1c42e671aaa806309e00a64efc234' }
    'folia-262-max' = [ordered]@{ software = 'folia'; label = 'Folia 26.2#7'; minecraftVersion = '26.2'; build = 7; artifact = 'folia-26.2-7.jar'; sha256 = '128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3' }
    'folia-263-currentmax' = [ordered]@{ software = 'folia'; label = 'Folia 26.3#3'; minecraftVersion = '26.3'; build = 3; artifact = 'folia-26.3-3.jar'; sha256 = '0c4cc7dd82c40bd1effa8a9c4cacfad55d41b7dc38377900957c961c2952c022' }
    'canvas-1218-min' = [ordered]@{ software = 'canvas'; label = 'Canvas 1.21.8#603'; minecraftVersion = '1.21.8'; build = 603; artifact = 'canvas-1.21.8-603.jar'; sha256 = '6577181e4bc8ac40a7a48ff00e262f2ac7a798f48ada446a07eb06618dc36f1b' }
    'canvas-263-max' = [ordered]@{ software = 'canvas'; label = 'Canvas 26.3#972'; minecraftVersion = '26.3'; build = 972; artifact = 'canvas-26.3-972.jar'; sha256 = 'aac025fe36a61f84fecb04de74e94bbf933c7081c4c88f13a9d4b96de42243f5' }
    'canvas-263-currentmax' = [ordered]@{ software = 'canvas'; label = 'Canvas 26.3#984'; minecraftVersion = '26.3'; build = 984; artifact = 'canvas-26.3-984.jar'; sha256 = '0b94c4cba3e415586dddb69915b5aacf250636c62d058f4651c1ab34eaf265fa' }
}
$selectedEndpoint = $endpointPins[$Endpoint]
$serverJarPath = if ($Endpoint -eq 'paper-263-currentstable') {
    Join-Path $repoRoot 'build\reports\foundation\T21-M10\artifacts\paper-26.3-194.jar'
} elseif ($Endpoint -in @('paper-263-currentmax', 'folia-263-currentmax', 'canvas-263-currentmax')) {
    Join-Path $repoRoot ('build\reports\foundation\T21-sql-runtime\current-maximum-artifacts\' + $selectedEndpoint.artifact)
} else {
    Join-Path $repoRoot ('build\reports\foundation\T15-runtime-pins\artifacts\' + $selectedEndpoint.artifact)
}
$endpointInfo = [ordered]@{
    label = $selectedEndpoint.label
    software = $selectedEndpoint.software
    minecraftVersion = $selectedEndpoint.minecraftVersion
    build = $selectedEndpoint.build
    serverJar = $serverJarPath
    serverSha256 = $selectedEndpoint.sha256
}
$candidateJar = Join-Path $repoRoot "build\libs\Syncmoney-$candidateVersion.jar"
$acceptanceJar = Join-Path $repoRoot 'build\acceptance\SyncmoneyAcceptance.jar'
$apiConsumerJar = Join-Path $repoRoot 'build\acceptance\SyncmoneyApiConsumer.jar'
$prepareTool = Join-Path $repoRoot 'tools\plugdev\prepare.mjs'
$clientTool = Join-Path $repoRoot 'tools\plugdev\m10-online-client.mjs'
$identityHelperTool = Join-Path $repoRoot 'tools\plugdev\m10-online-client-identity.mjs'
$identityHelperTestTool = Join-Path $repoRoot 'tools\plugdev\m10-online-client-identity.test.mjs'
$observerTool = Join-Path $repoRoot 'tools\plugdev\m10-redis-pubsub-observer.mjs'
$runnerTestTool = Join-Path $repoRoot 'tools\plugdev\run-m10-reconciliation.test.ps1'
$runId = "$($Endpoint.ToLowerInvariant())-$(Get-Date -Format 'yyyyMMdd-HHmmss')-$([guid]::NewGuid().ToString('N'))"
$reportRoot = Join-Path $repoRoot 'build\reports\foundation\T21-M10\runtime'
$runRoot = Join-Path $reportRoot $runId
$redisPort = 16391
$gamePorts = @{ a = 25731; b = 25732 }
$redisServerPid = $null
$redisInfoPid = $null
$redisLauncherPid = $null
$redisConfig = Join-Path $runRoot 'redis\redis.conf'
$script:runRootOwned = $false
$script:clientProcess = $null
$script:pubsubObserver = $null
$script:m10DepositCommand = $null
$nodes = @{}
$script:manifest = [ordered]@{
    task = 'T21/M10'
    status = 'RUNNING'
    claimMaturity = 'BOUNDED_RUNTIME_SUBCASE'
    runId = $runId
    endpoint = $endpointInfo
    startedAtUtc = [DateTime]::UtcNow.ToString('o')
    repositoryRoot = $repoRoot
    sourceCommit = (& git -C $repoRoot rev-parse HEAD 2>$null | Out-String).Trim()
    sourceTree = (& git -C $repoRoot rev-parse 'HEAD^{tree}' 2>$null | Out-String).Trim()
    java = [ordered]@{ home = $javaHome; version = $null }
    candidate = [ordered]@{ version = $candidateVersion; path = $candidateJar; sha256 = $null }
    acceptance = [ordered]@{ path = $acceptanceJar; sha256 = $null; apiConsumerPath = $apiConsumerJar; apiConsumerSha256 = $null; probeSourceSha256 = $null; clientSourceSha256 = $null; identityHelperPath = $identityHelperTool; identityHelperSourceSha256 = $null; identityHelperTestPath = $identityHelperTestTool; identityHelperTestSha256 = $null; observerPath = $observerTool; observerSourceSha256 = $null; runnerSha256 = $null; runnerSourceSnapshotPath = $null; runnerTestPath = $runnerTestTool; runnerTestSha256 = $null; prepareSha256 = $null }
    plugDev = [ordered]@{
        root = [IO.Path]::GetFullPath($PlugDevRoot); cli = $plugDevCli; cliSha256 = $null; cliDistRoot = $plugDevCliDistRoot
        cliDistManifest = @(); commit = $null; version = $null; workingTreeClean = $false
        workingTreeStatusEntryCount = $null; packageJsonSha256 = $null
    }
    mineflayer = [ordered]@{ root = [IO.Path]::GetFullPath($MineflayerRoot); version = $null; commit = $null; workingTreeClean = $false; packageJsonSha256 = $null; packageLockPath = $null; packageLockSha256 = $null; entrypointPath = $null; entrypointSha256 = $null; resolvedRequirePath = $null; dependencyInstallation = 'LOCAL_NODE_MODULES_NOT_HASHED' }
    redis = [ordered]@{ host = '127.0.0.1'; port = $redisPort; database = 15; version = $null; pid = $null; infoPid = $null; launcherPid = $null; configPath = $redisConfig; initialDbSize = $null; finalDbSize = $null; cleanShutdown = $false }
    nodes = [ordered]@{}
    account = [ordered]@{ name = 'AcceptanceM10'; uuid = $null; initial = $null; missedPubSub = $null; authority = $null; reconciled = $null; elapsedFromPluginEnableSeconds = $null }
    pubsubDisconnectAttempts = [System.Collections.Generic.List[object]]::new()
    pubsubObservations = [System.Collections.Generic.List[object]]::new()
    commands = [System.Collections.Generic.List[object]]::new()
    shutdown = [System.Collections.Generic.List[object]]::new()
    cleanup = [System.Collections.Generic.List[object]]::new()
    failure = $null
    cleanupFailure = $null
}

function Save-Result {
    if (-not $script:runRootOwned) { return }
    $script:manifest.updatedAtUtc = [DateTime]::UtcNow.ToString('o')
    $script:manifest | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath (Join-Path $runRoot 'result.json') -Encoding utf8
}

function Get-Sha256([string]$path) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required input is missing: $path" }
    return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-M10NoReparsePath([string]$Path) {
    $cursor = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $cursor)) { throw "M10 provenance path does not exist: $cursor" }
    while (-not [string]::IsNullOrWhiteSpace($cursor)) {
        $item = Get-Item -LiteralPath $cursor -Force -ErrorAction Stop
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "M10 provenance path contains a reparse point: $cursor" }
        $parent = [IO.Directory]::GetParent($cursor)
        if ($null -eq $parent -or [string]::Equals($parent.FullName, $cursor, [StringComparison]::OrdinalIgnoreCase)) { break }
        $cursor = $parent.FullName
    }
    return [IO.Path]::GetFullPath($Path)
}

function Assert-M10ExpectedLocalPlugDevRoot([string]$ObservedRoot, [string]$ExpectedRoot) {
    $observedFullPath = [IO.Path]::GetFullPath($ObservedRoot)
    $expectedFullPath = [IO.Path]::GetFullPath($ExpectedRoot)
    if (-not [string]::Equals($observedFullPath, $expectedFullPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "M10 PlugDev root differs from the configured local checkout: $observedFullPath"
    }
    $null = Assert-M10NoReparsePath $observedFullPath
    $packageJson = Join-Path $observedFullPath 'package.json'
    $null = Assert-M10NoReparsePath $packageJson
    $package = Get-Content -LiteralPath $packageJson -Raw | ConvertFrom-Json
    if ($package.private -ne $true) { throw 'M10 PlugDev root is not the local private project.' }
    if ([string]::IsNullOrWhiteSpace([string]$package.version)) { throw 'M10 local PlugDev package.json lacks a version.' }
    return $package
}

function Assert-M10ExpectedLocalMineflayerRoot([string]$ObservedRoot, [string]$ExpectedRoot) {
    $observedFullPath = [IO.Path]::GetFullPath($ObservedRoot)
    $expectedFullPath = [IO.Path]::GetFullPath($ExpectedRoot)
    if (-not [string]::Equals($observedFullPath, $expectedFullPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "M10 Mineflayer root differs from the configured local checkout: $observedFullPath"
    }
    $null = Assert-M10NoReparsePath $observedFullPath
    $packageJson = Join-Path $observedFullPath 'package.json'
    $null = Assert-M10NoReparsePath $packageJson
    $package = Get-Content -LiteralPath $packageJson -Raw | ConvertFrom-Json
    if ($package.name -ne 'mineflayer' -or [string]::IsNullOrWhiteSpace([string]$package.version)) {
        throw 'M10 Mineflayer root is not the expected local Mineflayer project.'
    }
    return $package
}

function Get-M10ResolvedMineflayerEntry([string]$NodeExecutable, [string]$PackageJsonPath) {
    $probe = "const { createRequire } = require('node:module'); process.stdout.write(createRequire(process.argv[1]).resolve('mineflayer'));"
    $output = @(& $NodeExecutable -e $probe $PackageJsonPath 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -or $output.Count -ne 1 -or [string]::IsNullOrWhiteSpace([string]$output[0])) {
        throw "Node could not resolve the local Mineflayer dependency from $PackageJsonPath."
    }
    return [IO.Path]::GetFullPath([string]$output[0])
}

function Assert-M10ExpectedLocalMineflayerEntry([string]$ObservedEntry, [string]$ExpectedEntry) {
    $observedFullPath = [IO.Path]::GetFullPath($ObservedEntry)
    $expectedFullPath = [IO.Path]::GetFullPath($ExpectedEntry)
    if (-not [string]::Equals($observedFullPath, $expectedFullPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Node resolved Mineflayer outside the configured local project: $observedFullPath"
    }
    return $expectedFullPath
}

function Get-PlugDevCliDistManifest([string]$CliDistRoot) {
    if (-not (Test-Path -LiteralPath $CliDistRoot -PathType Container)) { throw "PlugDev CLI dist directory is missing: $CliDistRoot" }
    $root = [IO.Path]::GetFullPath($CliDistRoot)
    $null = Assert-M10NoReparsePath $root
    $separator = [IO.Path]::DirectorySeparatorChar
    $prefix = $root.TrimEnd([char[]]@('\', '/')) + $separator
    $directories = @(Get-ChildItem -LiteralPath $root -Directory -Recurse -Force -ErrorAction Stop)
    if (@($directories | Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 }).Count -gt 0) {
        throw 'PlugDev CLI dist must not contain reparse-point directories.'
    }
    $files = @(Get-ChildItem -LiteralPath $root -File -Recurse -Force -ErrorAction Stop | Sort-Object FullName)
    if ($files.Count -eq 0) { throw 'PlugDev CLI dist contains no files.' }
    $entries = [System.Collections.Generic.List[object]]::new()
    foreach ($file in $files) {
        if (($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "PlugDev CLI dist file must not be a reparse point: $($file.FullName)" }
        $fullPath = [IO.Path]::GetFullPath($file.FullName)
        if (-not $fullPath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "PlugDev CLI dist file escapes its root: $fullPath" }
        $relativePath = $fullPath.Substring($prefix.Length).Replace('\', '/')
        if ([string]::IsNullOrWhiteSpace($relativePath) -or $relativePath.StartsWith('../', [StringComparison]::Ordinal) -or [IO.Path]::IsPathRooted($relativePath)) {
            throw "PlugDev CLI dist file has an invalid relative path: $relativePath"
        }
        $entries.Add([ordered]@{ path = $relativePath; length = [long]$file.Length; sha256 = Get-Sha256 $fullPath })
    }
    return $entries.ToArray()
}

function Assert-PlugDevCliDistManifest([string]$CliDistRoot, [object[]]$ExpectedManifest) {
    $expected = @($ExpectedManifest)
    $actual = @(Get-PlugDevCliDistManifest $CliDistRoot)
    if ($expected.Count -eq 0 -or $actual.Count -ne $expected.Count) {
        throw "PlugDev CLI dist manifest file count changed: expected $($expected.Count), observed $($actual.Count)."
    }
    for ($index = 0; $index -lt $expected.Count; $index++) {
        $expectedEntry = $expected[$index]
        $actualEntry = $actual[$index]
        if ([string]$expectedEntry.path -cne [string]$actualEntry.path -or
            [long]$expectedEntry.length -ne [long]$actualEntry.length -or
            [string]$expectedEntry.sha256 -cne [string]$actualEntry.sha256) {
            throw "PlugDev CLI dist manifest changed at entry $index ($($actualEntry.path))."
        }
    }
    return $true
}

function Get-GitCheckoutProvenance([string]$RepositoryPath) {
    if (-not (Test-Path -LiteralPath $RepositoryPath -PathType Container)) { throw "Git checkout directory is missing: $RepositoryPath" }
    $root = [IO.Path]::GetFullPath($RepositoryPath)
    $commit = (& git -C $root rev-parse HEAD 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') { throw "Cannot identify Git checkout HEAD: $root" }
    $status = @(& git -C $root status --porcelain=v1 --untracked-files=all 2>$null)
    $statusExitCode = $LASTEXITCODE
    if ($statusExitCode -ne 0) { throw "Cannot inspect Git checkout working-tree state: $root" }
    $statusEntries = @($status | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    return [ordered]@{
        commit = $commit
        workingTreeClean = ($statusEntries.Count -eq 0)
        workingTreeStatusEntryCount = [int]$statusEntries.Count
    }
}

function Assert-PlugDevCliUnchanged {
    if ((Get-Sha256 $script:manifest.plugDev.cli) -cne [string]$script:manifest.plugDev.cliSha256) {
        throw 'PlugDev CLI entrypoint changed after runtime provenance was captured.'
    }
    $null = Assert-PlugDevCliDistManifest $script:manifest.plugDev.cliDistRoot @($script:manifest.plugDev.cliDistManifest)
    return $true
}

function ConvertTo-CygwinPath([string]$windowsPath) {
    $absolute = [IO.Path]::GetFullPath($windowsPath)
    if ($absolute -notmatch '^([A-Za-z]):\\(.*)$') { throw "Redis path is not an absolute drive path: $absolute" }
    return "/cygdrive/$($Matches[1].ToLowerInvariant())/$($Matches[2].Replace('\', '/'))"
}

function Assert-PortClosed([int]$port) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $listener) { throw "Refusing M10 start because TCP port $port is already listening (PID $($listener.OwningProcess))." }
}

function Invoke-Redis([string[]]$arguments, [int]$timeoutMilliseconds = 5000) {
    if ($timeoutMilliseconds -lt 1) { throw 'Isolated Redis command timeout must be positive.' }
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $redisCli
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in (@('-h', '127.0.0.1', '-p', [string]$redisPort, '-n', '15', '--raw') + $arguments)) {
        $startInfo.ArgumentList.Add([string]$argument)
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) { throw 'Could not start isolated redis-cli process.' }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($timeoutMilliseconds)) {
            throw "Isolated Redis command exceeded its $timeoutMilliseconds ms timeout (redis-cli <test-command>)."
        }
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $null = $stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "Isolated Redis command failed (exit $($process.ExitCode)): redis-cli <test-command>." }
        return $stdout.Trim()
    } finally {
        if (-not $process.HasExited) {
            try { $process.Kill() } catch { }
            try { $null = $process.WaitForExit(1000) } catch { }
        }
        $process.Dispose()
    }
}

function Get-RedisInfoProcessId([string]$serverInfo) {
    $processIdLines = @([regex]::Split($serverInfo, '\r?\n') | Where-Object { $_ -match '^process_id(?::|$)' })
    if ($processIdLines.Count -ne 1) { throw 'Redis INFO server response does not contain exactly one process_id field.' }
    if ($processIdLines[0] -notmatch '^process_id:(\d+)$') { throw 'Redis INFO server process_id field is malformed.' }
    $processId = [int]$Matches[1]
    if ($processId -le 0) { throw 'Redis INFO server process_id must be a positive process identifier.' }
    return $processId
}

function Wait-RedisReady {
    $deadline = [DateTime]::UtcNow.AddSeconds(25)
    while ([DateTime]::UtcNow -lt $deadline) {
        try { if ((Invoke-Redis @('PING')) -eq 'PONG') { return } } catch { }
        Start-Sleep -Milliseconds 250
    }
    throw 'Run-owned Redis did not become ready within 25 seconds.'
}

function Read-Authority([string]$uuid) {
    $balance = Invoke-Redis @('GET', "syncmoney:balance:$uuid")
    $version = Invoke-Redis @('GET', "syncmoney:version:$uuid")
    if ([string]::IsNullOrWhiteSpace($balance)) { $balance = $null }
    if ([string]::IsNullOrWhiteSpace($version)) { $version = $null }
    return [ordered]@{ balance = $balance; version = $version }
}

function Wait-Authority([string]$uuid, [string]$balance, [string]$version, [int]$timeoutSeconds = 30) {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    do {
        $state = Read-Authority $uuid
        if ([string]$state.balance -eq $balance -and [string]$state.version -eq $version) { return $state }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Redis authority did not reach $balance/v$version for the test account."
}

function Get-ProcessByMarker([string]$marker, [string]$expectedName) {
    $matches = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
        $_.Name -eq $expectedName -and ([string]$_.CommandLine).Contains($marker, [StringComparison]::OrdinalIgnoreCase)
    })
    if ($matches.Count -ne 1) { throw "Expected exactly one run-owned $expectedName with the unique marker; observed $($matches.Count)." }
    return $matches[0]
}

function Get-RunOwnedRedisProcesses([string]$marker) {
    return @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
        $_.Name -in @('redis-server.exe', 'redis-server') -and
        ([string]$_.CommandLine).Contains($marker, [StringComparison]::OrdinalIgnoreCase)
    })
}

function Resolve-RedisRunProcesses([object[]]$processes, [string]$marker, [Nullable[int]]$listenerOwnerProcessId) {
    $matches = @($processes)
    if ($matches.Count -gt 2) { throw "Redis run marker matched $($matches.Count) processes; refusing ambiguous ownership." }
    foreach ($process in $matches) {
        if ($process.Name -notin @('redis-server.exe', 'redis-server') -or
            -not ([string]$process.CommandLine).Contains($marker, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Redis process $($process.ProcessId) does not carry this run's exact config marker and executable identity."
        }
    }
    if ($matches.Count -eq 0) {
        if ($null -ne $listenerOwnerProcessId) { throw 'Redis listener has no matching run-marked process.' }
        return $null
    }

    if ($matches.Count -eq 1) {
        $process = $matches[0]
        if ($null -ne $listenerOwnerProcessId -and [int]$process.ProcessId -ne [int]$listenerOwnerProcessId) {
            throw 'Redis listener owner does not match the unique run-marked process.'
        }
        return [ordered]@{ server = $process; launcher = $null; processCount = 1 }
    }

    $first = $matches[0]
    $second = $matches[1]
    $firstIsChild = [int]$first.ParentProcessId -eq [int]$second.ProcessId
    $secondIsChild = [int]$second.ParentProcessId -eq [int]$first.ProcessId
    if ($firstIsChild -eq $secondIsChild) { throw 'Redis run-marked processes do not form one unambiguous launcher/server parent-child pair.' }
    $server = if ($firstIsChild) { $first } else { $second }
    $launcher = if ($firstIsChild) { $second } else { $first }
    if ($null -ne $listenerOwnerProcessId -and [int]$server.ProcessId -ne [int]$listenerOwnerProcessId) {
        throw 'Redis listener owner is not the child server in the unique run-marked launcher/server pair.'
    }
    return [ordered]@{ server = $server; launcher = $launcher; processCount = 2 }
}

function Assert-RedisRunPresence([int]$markedProcessCount, [bool]$listenerPresent, [bool]$startupVerified) {
    if ($markedProcessCount -eq 0 -and -not $listenerPresent -and $startupVerified) {
        throw 'Redis disappeared after this run verified startup; it did not pass graceful shutdown cleanup.'
    }
}

function Wait-ProcessExit([int]$processId, [int]$timeoutSeconds = 30) {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($null -eq (Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue)) { return $true }
        Start-Sleep -Milliseconds 250
    }
    return $false
}

function Wait-ServerMarker([string]$nodeName, [string]$marker, [int]$timeoutSeconds = 90) {
    $logPath = Join-Path $nodes[$nodeName].root '.plugdev\run\logs\latest.log'
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-Path -LiteralPath $logPath -PathType Leaf) {
            $log = Get-Content -LiteralPath $logPath -Raw
            if ($log.Contains('T21_M10_FAIL', [StringComparison]::OrdinalIgnoreCase)) { throw "M10 acceptance probe failed on $nodeName; see its server log." }
            if ($log.Contains($marker, [StringComparison]::OrdinalIgnoreCase)) { return }
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Timed out waiting for '$marker' on $nodeName; see its server log."
}

function Invoke-NodeCommand([string]$nodeName, [string]$command, [string]$logName, [int]$timeoutSeconds = 30) {
    $null = Assert-PlugDevCliUnchanged
    $node = $nodes[$nodeName]
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $nodeExe
    $psi.WorkingDirectory = $node.root
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($argument in @($plugDevCli, 'server', 'command', $command)) { $psi.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $psi
    $null = $process.Start()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($timeoutSeconds * 1000)) {
        $process.Kill($true)
        throw "PlugDev command timed out on $nodeName; stopped only the command client PID $($process.Id)."
    }
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    $commandLog = Join-Path $runRoot $logName
    @("STARTED_UTC=$([DateTime]::UtcNow.ToString('o'))", "NODE=$nodeName", "COMMAND=$command", "EXIT=$($process.ExitCode)", $stdout, $stderr) |
        Set-Content -LiteralPath $commandLog -Encoding utf8
    if ($process.ExitCode -ne 0) { throw "PlugDev command failed on $nodeName with exit $($process.ExitCode); see $commandLog." }
    $script:manifest.commands.Add([ordered]@{ node = $nodeName; command = $command; exitCode = $process.ExitCode; log = $commandLog })
    Save-Result
}

function Start-M10DepositCommand([string]$uuid, [string]$requestId) {
    $null = Assert-PlugDevCliUnchanged
    $node = $nodes.a
    $command = "smaccept m10 deposit $uuid 1.00 $requestId"
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $nodeExe
    $psi.WorkingDirectory = $node.root
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($argument in @($plugDevCli, 'server', 'command', $command)) { $psi.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $psi
    $null = $process.Start()
    $pending = [ordered]@{
        process = $process; pid = [int]$process.Id; command = $command
        log = Join-Path $runRoot "a-missed-pubsub-deposit-$requestId.txt"
        stdoutTask = $process.StandardOutput.ReadToEndAsync(); stderrTask = $process.StandardError.ReadToEndAsync()
        completed = $false
    }
    $script:m10DepositCommand = $pending
    return $pending
}

function Complete-M10DepositCommand($pending) {
    if (-not $pending.process.HasExited) { return $false }
    $stdout = $pending.stdoutTask.GetAwaiter().GetResult()
    $stderr = $pending.stderrTask.GetAwaiter().GetResult()
    @("STARTED_UTC=$([DateTime]::UtcNow.ToString('o'))", 'NODE=a', "COMMAND=$($pending.command)",
        "EXIT=$($pending.process.ExitCode)", $stdout, $stderr) | Set-Content -LiteralPath $pending.log -Encoding utf8
    $pending.completed = $true
    $script:manifest.commands.Add([ordered]@{ node = 'a'; command = $pending.command; exitCode = $pending.process.ExitCode; log = $pending.log })
    if ([object]::ReferenceEquals($script:m10DepositCommand, $pending)) { $script:m10DepositCommand = $null }
    Save-Result
    if ($pending.process.ExitCode -ne 0) { throw "PlugDev deposit command failed on node a with exit $($pending.process.ExitCode); see $($pending.log)." }
    return $true
}

function Stop-M10DepositCommand {
    if ($null -eq $script:m10DepositCommand) { return }
    $pending = $script:m10DepositCommand
    if ($pending.process.HasExited) {
        $null = Complete-M10DepositCommand $pending
        return
    }
    try { $pending.process.Kill($true) } catch { }
    $exited = Wait-ProcessExit ([int]$pending.pid) 10
    $script:manifest.cleanup.Add([ordered]@{ target = 'M10 PlugDev deposit command'; pid = $pending.pid; forced = $true })
    $script:m10DepositCommand = $null
    if (-not $exited) { throw "Run-owned M10 PlugDev command PID $($pending.pid) survived targeted cleanup." }
    throw 'M10 PlugDev deposit command required forced cleanup; runtime result is not a clean PASS.'
}

function Read-MemoryMarker([string]$nodeName, [string]$name = 'AcceptanceM10') {
    $logPath = Join-Path $nodes[$nodeName].root '.plugdev\run\logs\latest.log'
    $log = Get-Content -LiteralPath $logPath -Raw
    $matches = [regex]::Matches($log, 'T21_M10_MEMORY request=([0-9a-fA-F-]{8,64}) name=' + [regex]::Escape($name) + ' uuid=([0-9a-fA-F-]{36}) balance=([0-9]+(?:\.[0-9]{2})?) version=(\d+)')
    if ($matches.Count -eq 0) { throw "No M10 memory snapshot marker exists in $nodeName log." }
    $last = $matches[$matches.Count - 1]
    return [ordered]@{ requestId = $last.Groups[1].Value; uuid = $last.Groups[2].Value; balance = $last.Groups[3].Value; version = $last.Groups[4].Value; observedAtUtc = [DateTime]::UtcNow.ToString('o') }
}

function Snapshot-Memory([string]$nodeName) {
    if ($nodeName -eq 'b' -and $null -ne $script:clientProcess -and $script:clientProcess.HasExited) {
        throw 'The required online acceptance player disconnected before the B memory snapshot.'
    }
    $requestId = [guid]::NewGuid().ToString('N')
    Invoke-NodeCommand $nodeName "smaccept m10 snapshot AcceptanceM10 $requestId" "$nodeName-snapshot-$requestId.txt"
    Wait-ServerMarker $nodeName "T21_M10_MEMORY request=$requestId name=AcceptanceM10" 20
    return Read-MemoryMarker $nodeName
}

function Observe-BMemoryAfterReconnect($priorMemory, [string]$expectedBalance, [string]$expectedVersion) {
    $observations = [System.Collections.Generic.List[object]]::new()
    $memory = $null
    $result = $null
    # PubsubSubscriber.handleMessage applies the balance synchronously on the Jedis callback
    # thread; repeated owner-scheduled snapshots guard against making a verdict from one sample.
    for ($sample = 1; $sample -le 3; $sample++) {
        if ($sample -gt 1) { Start-Sleep -Milliseconds 500 }
        $null = Snapshot-Memory 'b'
        $memory = Read-MemoryMarker 'b'
        if ($memory.uuid -ne $priorMemory.uuid) { throw 'B account identity changed during post-reconnect Pub/Sub observation.' }
        $observations.Add([ordered]@{
            sample = $sample; requestId = $memory.requestId; balance = $memory.balance
            version = $memory.version; observedAtUtc = $memory.observedAtUtc
        })
        if ($memory.balance -eq $expectedBalance -and $memory.version -eq $expectedVersion) {
            $result = 'MESSAGE_APPLIED_DURING_ATTEMPT'
            break
        }
        if ($memory.balance -ne $priorMemory.balance -or $memory.version -ne $priorMemory.version) {
            throw 'B memory was neither its prior snapshot nor the exact new Redis authority after Pub/Sub reconnect.'
        }
    }
    if ($null -eq $result) { $result = 'MESSAGE_MISSED_NO_REPLAY' }
    return [ordered]@{ memory = $memory; observations = $observations.ToArray(); result = $result }
}

function Get-BPubSubClient([int]$timeoutMilliseconds = 5000) {
    $serverProcessId = [int]$nodes.b.pid
    $tcpPorts = @(Get-NetTCPConnection -State Established -OwningProcess $serverProcessId -RemotePort $redisPort -ErrorAction SilentlyContinue |
        ForEach-Object { [int]$_.LocalPort } | Sort-Object -Unique)
    if ($tcpPorts.Count -eq 0) { return $null }
    $rows = Invoke-Redis @('CLIENT', 'LIST', 'TYPE', 'PUBSUB') $timeoutMilliseconds
    foreach ($line in ($rows -split "`r?`n")) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $fields = @{}
        foreach ($part in ($line -split ' ')) {
            $split = $part.IndexOf('=')
            if ($split -gt 0) { $fields[$part.Substring(0, $split)] = $part.Substring($split + 1) }
        }
        if ($fields.flags -notmatch 'P' -or $fields.sub -ne '2') { continue }
        $addressMatch = [regex]::Match([string]$fields.addr, '^127\.0\.0\.1:(\d+)$')
        if (-not $addressMatch.Success) { continue }
        $localPort = [int]$addressMatch.Groups[1].Value
        if ($tcpPorts -contains $localPort) {
            return [ordered]@{ id = [long]$fields.id; localPort = $localPort; channels = [int]$fields.sub; line = $line }
        }
    }
    return $null
}

function Disconnect-BPubSubClient($client, [string]$reason, [int]$timeoutMilliseconds = 5000) {
    $result = Invoke-Redis @('CLIENT', 'KILL', 'ID', [string]$client.id) $timeoutMilliseconds
    if ([string]$result -notin @('0', '1')) { throw "Redis CLIENT KILL ID $($client.id) returned unexpected result '$result'." }
    $script:manifest.pubsubDisconnectAttempts.Add([ordered]@{
        atUtc = [DateTime]::UtcNow.ToString('o')
        reason = $reason
        redisClientId = $client.id
        matchedServerLocalPort = $client.localPort
        channels = $client.channels
        redisResult = [string]$result
    })
    Save-Result
    return ([string]$result -eq '1')
}

function Wait-BPubSub([bool]$connected, [int]$timeoutSeconds = 20, [string]$reconnectReason = 'reconnected-during-disconnect-window') {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    do {
        $remainingMilliseconds = [int][Math]::Ceiling(($deadline - [DateTime]::UtcNow).TotalMilliseconds)
        if ($remainingMilliseconds -lt 1) { break }
        $commandTimeoutMilliseconds = [Math]::Min(5000, $remainingMilliseconds)
        $client = Get-BPubSubClient $commandTimeoutMilliseconds
        if ($connected) {
            if ($null -ne $client) { return $client }
        } elseif ($null -eq $client) {
            return $null
        } else {
            # Jedis may reconnect a killed Pub/Sub socket before the next polling interval.
            # Keep suppressing replacement subscribers until a gap is observed.
            $remainingMilliseconds = [int][Math]::Ceiling(($deadline - [DateTime]::UtcNow).TotalMilliseconds)
            if ($remainingMilliseconds -lt 1) { break }
            $commandTimeoutMilliseconds = [Math]::Min(5000, $remainingMilliseconds)
            $null = Disconnect-BPubSubClient $client $reconnectReason $commandTimeoutMilliseconds
        }
        Start-Sleep -Milliseconds 10
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "B node Pub/Sub client did not reach connected=$connected within $timeoutSeconds seconds."
}

function Disconnect-BPubSub([string]$reason) {
    $client = Get-BPubSubClient
    if ($null -eq $client) {
        $null = Wait-BPubSub $false 5 "reconnect-after-$reason"
        return $false
    }
    $null = Disconnect-BPubSubClient $client $reason
    $null = Wait-BPubSub $false 5 "reconnect-after-$reason"
    # We found the Java subscriber before the kill; a CLIENT KILL result of 0 is a valid
    # race where the socket closed before Redis processed the kill command.
    return $true
}

function Read-PubSubObserverEvents($observer) {
    if (-not (Test-Path -LiteralPath $observer.logPath -PathType Leaf)) { return @() }
    $events = [System.Collections.Generic.List[object]]::new()
    foreach ($line in (Get-Content -LiteralPath $observer.logPath)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        try { $events.Add(($line | ConvertFrom-Json -ErrorAction Stop)) }
        catch { throw "M10 Pub/Sub observer wrote malformed evidence at $($observer.logPath)." }
    }
    return @($events)
}

function Start-M10PubSubObserver([string]$uuid, [string]$requestId, [string]$balance, [string]$version) {
    $observerLog = Join-Path $runRoot "pubsub-observer-$requestId.jsonl"
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $nodeExe
    $psi.WorkingDirectory = $repoRoot
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($argument in @($observerTool, '127.0.0.1', [string]$redisPort, 'syncmoney:balance:update',
        $uuid, $balance, $version, $requestId, $observerLog, '60000')) { $psi.ArgumentList.Add($argument) }
    $proc = [Diagnostics.Process]::new()
    $proc.StartInfo = $psi
    $null = $proc.Start()
    $observer = [ordered]@{
        process = $proc; pid = [int]$proc.Id; requestId = $requestId; uuid = $uuid
        balance = $balance; version = $version; logPath = $observerLog
        stdoutTask = $proc.StandardOutput.ReadToEndAsync(); stderrTask = $proc.StandardError.ReadToEndAsync()
    }
    $script:pubsubObserver = $observer
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        if ($proc.HasExited) {
            $stderr = $observer.stderrTask.GetAwaiter().GetResult()
            throw "M10 Redis Pub/Sub observer exited before subscription acknowledgement (exit $($proc.ExitCode)): $stderr"
        }
        $events = @(Read-PubSubObserverEvents $observer)
        if (@($events | Where-Object { $_.event -eq 'subscribed' -and $_.channel -eq 'syncmoney:balance:update' }).Count -eq 1) {
            return $observer
        }
        Start-Sleep -Milliseconds 50
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "M10 Redis Pub/Sub observer did not subscribe within 15 seconds; see $observerLog."
}

function Complete-M10PubSubObserver($observer, [int]$timeoutSeconds = 15) {
    $proc = $observer.process
    if (-not $proc.HasExited -and -not $proc.WaitForExit($timeoutSeconds * 1000)) {
        throw "M10 Redis Pub/Sub observer did not close after its publication acknowledgement; PID $($proc.Id)."
    }
    $stdout = $observer.stdoutTask.GetAwaiter().GetResult()
    $stderr = $observer.stderrTask.GetAwaiter().GetResult()
    if ($proc.ExitCode -ne 0) { throw "M10 Redis Pub/Sub observer exited $($proc.ExitCode): $stderr $stdout" }
    $events = @(Read-PubSubObserverEvents $observer)
    $observed = @($events | Where-Object { $_.event -eq 'publicationObserved' -and
        $_.probeRequestId -eq $observer.requestId -and $_.uuid -eq $observer.uuid -and
        $_.balance -eq $observer.balance -and $_.version -eq $observer.version -and
        $_.channel -eq 'syncmoney:balance:update' -and $_.eventType -eq 'DEPOSIT' -and
        $_.sourceServer -eq 'm10-a' -and $_.messageId -match '^[0-9a-fA-F-]{36}$' -and
        $_.payloadSha256 -match '^[0-9a-f]{64}$' })
    if ($observed.Count -ne 1) { throw "M10 Pub/Sub observer did not produce exactly one matching publication record; see $($observer.logPath)." }
    $script:manifest.pubsubObservations.Add([ordered]@{
        probeRequestId = $observer.requestId; uuid = $observer.uuid; balance = $observer.balance
        version = $observer.version; channel = 'syncmoney:balance:update'; messageId = $observed[0].messageId
        payloadSha256 = $observed[0].payloadSha256; observedAtUtc = $observed[0].atUtc
        observerPid = $observer.pid; log = $observer.logPath
    })
    $script:manifest.cleanup.Add([ordered]@{ target = 'M10 Redis Pub/Sub observer'; pid = $observer.pid; forced = $false })
    if ([object]::ReferenceEquals($script:pubsubObserver, $observer)) { $script:pubsubObserver = $null }
    return $observed[0]
}

function Stop-M10PubSubObserver {
    if ($null -eq $script:pubsubObserver) { return }
    $observer = $script:pubsubObserver
    $proc = $observer.process
    if (-not $proc.HasExited) {
        try { $proc.Kill($true) } catch { }
        $exited = Wait-ProcessExit ([int]$observer.pid) 10
        $script:manifest.cleanup.Add([ordered]@{ target = 'M10 Redis Pub/Sub observer'; pid = $observer.pid; forced = $true })
        $script:pubsubObserver = $null
        if (-not $exited) { throw "Run-owned M10 Redis Pub/Sub observer PID $($observer.pid) survived targeted cleanup." }
        throw 'M10 Redis Pub/Sub observer required forced cleanup; runtime result is not a clean PASS.'
    }
    $script:pubsubObserver = $null
}

function Wait-DepositAuthorityWhileSuppressingB([string]$uuid, [string]$requestId, [string]$balance, [string]$version, $observer, $depositCommand, [int]$timeoutSeconds = 60) {
    $marker = "T21_M10_DEPOSIT_PASS request=$requestId uuid=$uuid"
    $logPath = $script:manifest.nodes.a.serverLog
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    $authority = $null
    do {
        # Keep B disconnected until an independent subscriber proves the exact publication reached Redis.
        $client = Get-BPubSubClient
        if ($null -ne $client) { $null = Disconnect-BPubSub "hold-disconnected-through-publish-$requestId" }
        else { $null = Wait-BPubSub $false 5 }
        if (-not $depositCommand.completed -and $depositCommand.process.HasExited) {
            $null = Complete-M10DepositCommand $depositCommand
        }
        $authority = Read-Authority $uuid
        $receiptObserved = (Test-Path -LiteralPath $logPath -PathType Leaf) -and
            (Get-Content -LiteralPath $logPath -Raw).Contains($marker, [StringComparison]::OrdinalIgnoreCase)
        $observerEvents = @(Read-PubSubObserverEvents $observer)
        $publicationObserved = @($observerEvents | Where-Object { $_.event -eq 'publicationObserved' -and
            $_.probeRequestId -eq $requestId -and $_.uuid -eq $uuid -and $_.balance -eq $balance -and
            $_.version -eq $version -and $_.channel -eq 'syncmoney:balance:update' }).Count -eq 1
        if ($observer.process.HasExited -and -not $publicationObserved) {
            $stderr = $observer.stderrTask.GetAwaiter().GetResult()
            throw "Independent Redis observer exited before the exact publish barrier (exit $($observer.process.ExitCode)): $stderr"
        }
        if ($depositCommand.completed -and $receiptObserved -and [string]$authority.balance -eq $balance -and
            [string]$authority.version -eq $version -and $publicationObserved) {
            # Re-check after the independent publication is visible. A reconnect racing the
            # observer is disconnected and acknowledged before this barrier is accepted.
            $barrierClient = Get-BPubSubClient
            if ($null -ne $barrierClient) { $null = Disconnect-BPubSub "barrier-recheck-$requestId" }
            else { $null = Wait-BPubSub $false 5 }
            $barrierClient = Get-BPubSubClient
            if ($null -ne $barrierClient) { $null = Disconnect-BPubSub "barrier-final-check-$requestId" }
            else { $null = Wait-BPubSub $false 5 }
            $publication = Complete-M10PubSubObserver $observer
            $postObserverClient = Get-BPubSubClient
            if ($null -ne $postObserverClient) { $null = Disconnect-BPubSub "post-publish-no-replay-$requestId" }
            else { $null = Wait-BPubSub $false 5 }
            return [ordered]@{
                authority = $authority; acceptedReceiptObserved = $true; subscriberDisconnectedAtBarrierAcceptance = ($null -eq (Get-BPubSubClient)); publication = $publication
                completedAtUtc = [DateTime]::UtcNow.ToString('o')
            }
        }
        Start-Sleep -Milliseconds 75
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "A's accepted Vault deposit did not reach receipt, independent exact Pub/Sub observation, and Redis authority $balance/v$version while B's subscriber was suppressed."
}

function New-NodeProject([string]$nodeName, [int]$gamePort) {
    $nodeRoot = Join-Path $runRoot "node-$nodeName"
    $null = New-Item -ItemType Directory -Path @(
        (Join-Path $nodeRoot 'build\libs'), (Join-Path $nodeRoot 'build\acceptance'),
        (Join-Path $nodeRoot 'src\main\resources'), (Join-Path $nodeRoot 'tools\plugdev'),
        (Join-Path $nodeRoot 'gradle\wrapper')
    ) -Force
    Copy-Item -LiteralPath (Join-Path $repoRoot 'src\main\resources\config.yml') -Destination (Join-Path $nodeRoot 'src\main\resources\config.yml')
    Copy-Item -LiteralPath (Join-Path $repoRoot 'src\main\resources\plugin.yml') -Destination (Join-Path $nodeRoot 'src\main\resources\plugin.yml')
    Copy-Item -LiteralPath $prepareTool -Destination (Join-Path $nodeRoot 'tools\plugdev\prepare.mjs')
    Copy-Item -LiteralPath (Join-Path $repoRoot 'gradlew.bat') -Destination $nodeRoot
    Copy-Item -LiteralPath (Join-Path $repoRoot 'gradle\wrapper\gradle-wrapper.jar') -Destination (Join-Path $nodeRoot 'gradle\wrapper')
    Copy-Item -LiteralPath (Join-Path $repoRoot 'gradle\wrapper\gradle-wrapper.properties') -Destination (Join-Path $nodeRoot 'gradle\wrapper')
    Copy-Item -LiteralPath $candidateJar -Destination (Join-Path $nodeRoot "build\libs\Syncmoney-$candidateVersion.jar")
    Copy-Item -LiteralPath $acceptanceJar -Destination (Join-Path $nodeRoot 'build\acceptance\SyncmoneyAcceptance.jar')
    Copy-Item -LiteralPath $apiConsumerJar -Destination (Join-Path $nodeRoot 'build\acceptance\SyncmoneyApiConsumer.jar')
    Set-Content -LiteralPath (Join-Path $nodeRoot 'settings.gradle') -Value "rootProject.name = 'syncmoney-t21-m10-$nodeName'`n" -Encoding utf8
    Set-Content -LiteralPath (Join-Path $nodeRoot 'build.gradle') -Value "plugins { id 'java' }`n" -Encoding utf8
    $marker = "T21_M10_RUN=$runId-$nodeName"
    $nodes[$nodeName] = [ordered]@{
        root = $nodeRoot; gamePort = $gamePort; rconPort = $gamePort + 10000; marker = $marker
        pid = $null; enabledAtUtc = $null; startAttempted = $false; confirmedStart = $false; pluginEnabled = $false
    }

    $env:SYNCMONEY_TEST_SERVER_JAR = [IO.Path]::GetFullPath($endpointInfo.serverJar)
    $env:SYNCMONEY_TEST_REDIS_PORT = [string]$redisPort
    $env:SYNCMONEY_TEST_GAME_PORT = [string]$gamePort
    $nodePrepareTool = Join-Path $nodeRoot 'tools\plugdev\prepare.mjs'
    $prepareOutput = @(& $nodeExe $nodePrepareTool $endpointInfo.software $endpointInfo.minecraftVersion local_redis off $candidateVersion 2>&1)
    $prepareExit = $LASTEXITCODE
    $prepareOutput | Set-Content -LiteralPath (Join-Path $runRoot "$nodeName-prepare.txt") -Encoding utf8
    if ($prepareExit -ne 0) { throw "PlugDev profile preparation failed for node $nodeName (exit $prepareExit)." }

    $configureSource = @'
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const YAML = createRequire(path.join(process.env.PLUGDEV_ROOT, 'package.json'))('yaml');
const root = process.env.T21_NODE_ROOT;
const profilePath = path.join(root, '.plugdev', 'profile.yml');
const configPath = path.join(root, '.plugdev', 'run', 'plugins', 'Syncmoney', 'config.yml');
const profile = YAML.parse(fs.readFileSync(profilePath, 'utf8'));
profile.port = Number(process.env.T21_NODE_PORT);
profile.server = 'custom';
profile.serverJar = process.env.SYNCMONEY_TEST_SERVER_JAR;
profile.build.jarPattern = `build/libs/Syncmoney-${process.env.T21_NODE_VERSION}.jar`;
profile.rcon = { ...profile.rcon, enabled: true };
profile.jvm.args = [...(profile.jvm.args ?? []), `-Dsyncmoney.t21.run=${process.env.T21_NODE_MARKER}`];
const config = YAML.parse(fs.readFileSync(configPath, 'utf8'));
config['server-name'] = process.env.T21_NODE_SERVER_NAME;
config['db-enabled'] = false;
config['pubsub-enabled'] = true;
config.redis = { ...config.redis, enabled: true, host: '127.0.0.1', port: Number(process.env.T21_REDIS_PORT), database: 15 };
config.database = { ...config.database, enabled: false };
config.economy = { ...config.economy, mode: 'local_redis' };
fs.writeFileSync(profilePath, YAML.stringify(profile), 'utf8');
fs.writeFileSync(configPath, YAML.stringify(config), 'utf8');
const savedProfile = YAML.parse(fs.readFileSync(profilePath, 'utf8'));
process.stdout.write(JSON.stringify({ serverName: config['server-name'], version: process.env.T21_NODE_VERSION, gamePort: profile.port, profileServer: savedProfile.server, profileVersion: savedProfile.version, profilePort: savedProfile.port, serverJar: savedProfile.serverJar, vaultDependency: (savedProfile.deps ?? []).some(dep => dep.name === 'VaultUnlocked'), mode: config.economy.mode, redisEnabled: config.redis.enabled, redisHost: config.redis.host, redisPort: config.redis.port, redisDatabase: config.redis.database, sqlEnabled: config.database.enabled, legacyDbEnabled: config['db-enabled'], pubsubEnabled: config['pubsub-enabled'] }));
'@
    $configurePath = Join-Path $runRoot 'configure-node.cjs'
    if (-not (Test-Path -LiteralPath $configurePath)) { Set-Content -LiteralPath $configurePath -Value $configureSource -Encoding utf8 }
    $env:T21_NODE_ROOT = $nodeRoot
    $env:T21_NODE_VERSION = $candidateVersion
    $env:T21_NODE_PORT = [string]$gamePort
    $env:T21_NODE_MARKER = $marker
    $env:T21_NODE_SERVER_NAME = "m10-$nodeName"
    $env:T21_REDIS_PORT = [string]$redisPort
    $configureOutput = @(& $nodeExe $configurePath 2>&1)
    $configureExit = $LASTEXITCODE
    $configureOutput | Set-Content -LiteralPath (Join-Path $runRoot "$nodeName-configure.txt") -Encoding utf8
    if ($configureExit -ne 0) { throw "M10 PlugDev config preparation failed for $nodeName (exit $configureExit)." }
    $parsed = ($configureOutput -join [Environment]::NewLine) | ConvertFrom-Json
    if ($parsed.profileServer -ne 'custom' -or $parsed.profileVersion -ne $endpointInfo.minecraftVersion -or
        [int]$parsed.profilePort -ne $gamePort -or $parsed.vaultDependency -ne $true -or
        $parsed.mode -ne 'local_redis' -or $parsed.redisEnabled -ne $true -or $parsed.redisHost -ne '127.0.0.1' -or
        [int]$parsed.redisPort -ne $redisPort -or [int]$parsed.redisDatabase -ne 15 -or
        $parsed.sqlEnabled -ne $false -or $parsed.legacyDbEnabled -ne $false -or $parsed.pubsubEnabled -ne $true -or
        $parsed.serverJar -ne [IO.Path]::GetFullPath($endpointInfo.serverJar)) {
        throw "Configured node $nodeName does not match the isolated LOCAL_REDIS/no-SQL M10 profile."
    }
    $script:manifest.nodes[$nodeName] = [ordered]@{
        serverName = "m10-$nodeName"; gamePort = $gamePort; rconPort = $gamePort + 10000
        marker = $marker; mode = 'LOCAL_REDIS'; redisDatabase = 15; sqlEnabled = $false
        profile = (Join-Path $nodeRoot '.plugdev\profile.yml'); configuration = (Join-Path $nodeRoot '.plugdev\run\plugins\Syncmoney\config.yml')
        serverLog = (Join-Path $nodeRoot '.plugdev\run\logs\latest.log'); pid = $null; enabledAtUtc = $null
        startAttempted = $false; confirmedStart = $false; pluginEnabled = $false
    }
}

function Start-Node([string]$nodeName) {
    $null = Assert-PlugDevCliUnchanged
    $node = $nodes[$nodeName]
    $node.startAttempted = $true
    $script:manifest.nodes[$nodeName].startAttempted = $true
    Save-Result
    Push-Location $node.root
    try {
        $output = @(& $nodeExe $plugDevCli server start --config '.plugdev\profile.yml' 2>&1)
        $exit = $LASTEXITCODE
    } finally { Pop-Location }
    $output | Set-Content -LiteralPath (Join-Path $runRoot "$nodeName-start.txt") -Encoding utf8
    if ($exit -ne 0) { throw "PlugDev server start failed for $nodeName (exit $exit)." }
    $sessionPath = Join-Path $node.root '.plugdev\session.json'
    if (-not (Test-Path -LiteralPath $sessionPath -PathType Leaf)) { throw "PlugDev did not persist the $nodeName session." }
    $session = Get-Content -LiteralPath $sessionPath -Raw | ConvertFrom-Json
    $javaProcess = Get-ProcessByMarker $node.marker 'java.exe'
    if ([int]$session.pid -ne [int]$javaProcess.ProcessId) { throw "PlugDev session PID for $nodeName does not match the run-owned marked JVM." }
    $node.pid = [int]$javaProcess.ProcessId
    $node.confirmedStart = $true
    $node.enabledAtUtc = [DateTime]::UtcNow
    $script:manifest.nodes[$nodeName].pid = $node.pid
    $script:manifest.nodes[$nodeName].confirmedStart = $true
    Wait-ServerMarker $nodeName 'Syncmoney enabled successfully' 120
    $node.pluginEnabled = $true
    $node.enabledAtUtc = [DateTime]::UtcNow
    $script:manifest.nodes[$nodeName].enabledAtUtc = $node.enabledAtUtc.ToString('o')
    $script:manifest.nodes[$nodeName].pluginEnabled = $true
    Save-Result
}

function Start-M10Client {
    $packageJson = Join-Path $MineflayerRoot 'package.json'
    $resolvedEntry = Get-M10ResolvedMineflayerEntry $nodeExe $packageJson
    $null = Assert-M10ExpectedLocalMineflayerEntry $resolvedEntry ([string]$script:manifest.mineflayer.entrypointPath)
    if ((Get-Sha256 $resolvedEntry) -cne [string]$script:manifest.mineflayer.entrypointSha256) {
        throw 'The locally resolved Mineflayer entrypoint changed before client start.'
    }
    $script:manifest.mineflayer.resolvedRequirePath = $resolvedEntry
    Save-Result
    $clientLog = Join-Path $runRoot 'mineflayer-client.log'
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $nodeExe
    $psi.WorkingDirectory = $repoRoot
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($argument in @($clientTool, $endpointInfo.minecraftVersion, [string]$nodes.b.gamePort,
        'AcceptanceM10', "T21_M10_RUN=$runId-client", $MineflayerRoot, $clientLog)) { $psi.ArgumentList.Add($argument) }
    $proc = [Diagnostics.Process]::new()
    $proc.StartInfo = $psi
    $null = $proc.Start()
    $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
    $stderrTask = $proc.StandardError.ReadToEndAsync()
    $nodes.client = [ordered]@{ process = $proc; pid = [int]$proc.Id; marker = "T21_M10_RUN=$runId-client"; logPath = $clientLog; stdoutTask = $stdoutTask; stderrTask = $stderrTask }
    $script:clientProcess = $proc
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($proc.HasExited) { throw "Mineflayer acceptance client exited early with code $($proc.ExitCode); see $clientLog." }
        if (Test-Path -LiteralPath $clientLog -PathType Leaf) {
            $log = Get-Content -LiteralPath $clientLog -Raw
            if ($log -match 'T21_M10_BOT_FAIL') { throw "Mineflayer acceptance client reported failure; see $clientLog." }
            if ($log -match 'T21_M10_BOT_READY .*uuid=([0-9a-fA-F-]{36})') {
                $script:manifest.account.uuid = $Matches[1]
                $script:manifest.mineflayer.version = (Get-Content -LiteralPath (Join-Path $MineflayerRoot 'package.json') -Raw | ConvertFrom-Json).version
                Save-Result
                return
            }
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Mineflayer did not join node B within 90 seconds; see $clientLog."
}

function Stop-M10Client {
    if ($null -eq $script:clientProcess) { return }
    if ($script:clientProcess.HasExited) {
        $existingLog = Get-Content -LiteralPath $nodes.client.logPath -Raw
        if (-not $existingLog.Contains('T21_M10_BOT_EXIT_PASS') -or $existingLog.Contains('T21_M10_BOT_EXIT_FAIL') -or
            $existingLog.Contains('T21_M10_BOT_FAIL')) { throw 'Mineflayer acceptance client disconnected before graceful cleanup.' }
        return
    }
    $script:clientProcess.StandardInput.WriteLine('quit')
    $script:clientProcess.StandardInput.Flush()
    if (-not $script:clientProcess.WaitForExit(10000)) {
        $process = Get-ProcessByMarker $nodes.client.marker 'node.exe'
        Stop-Process -Id ([int]$process.ProcessId) -Force -ErrorAction Stop
        if (-not (Wait-ProcessExit ([int]$process.ProcessId) 10)) { throw 'Run-owned Mineflayer client failed to exit after targeted cleanup.' }
        $script:manifest.cleanup.Add([ordered]@{ target = 'Mineflayer client'; pid = $process.ProcessId; forced = $true })
        throw 'Mineflayer client required forced cleanup; runtime result is not a clean PASS.'
    }
    $log = Get-Content -LiteralPath $nodes.client.logPath -Raw
    if (-not $log.Contains('T21_M10_BOT_EXIT_PASS')) { throw 'Mineflayer client did not record a graceful quit marker.' }
    $null = $nodes.client.stdoutTask.GetAwaiter().GetResult()
    $null = $nodes.client.stderrTask.GetAwaiter().GetResult()
    $script:manifest.cleanup.Add([ordered]@{ target = 'Mineflayer client'; pid = $script:clientProcess.Id; forced = $false })
}

function Get-NodeListeners([string]$nodeName) {
    $node = $nodes[$nodeName]
    $listeners = [System.Collections.Generic.List[object]]::new()
    foreach ($port in @([int]$node.gamePort, [int]$node.rconPort)) {
        foreach ($listener in @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)) {
            $listeners.Add([ordered]@{ port = $port; localAddress = [string]$listener.LocalAddress; ownerPid = [int]$listener.OwningProcess })
        }
    }
    return @($listeners)
}

function Get-RunOwnedNodeProcesses([string]$nodeName) {
    $marker = [string]$nodes[$nodeName].marker
    return @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
        $_.Name -eq 'java.exe' -and ([string]$_.CommandLine).Contains($marker, [StringComparison]::OrdinalIgnoreCase)
    })
}

function Stop-Node([string]$nodeName) {
    $node = $nodes[$nodeName]
    if ($null -eq $node) { return }
    $processes = @(Get-RunOwnedNodeProcesses $nodeName)
    if ($processes.Count -eq 0) {
        $listeners = @(Get-NodeListeners $nodeName)
        $script:manifest.shutdown.Add([ordered]@{
            node = $nodeName; pid = $node.pid; processExitVerified = $true
            pluginDrainVerified = $null; drainNotEvaluatedReason = 'No run-marked JVM remained; plugin enable was not established.'
            listenerCheck = [ordered]@{ gamePort = $node.gamePort; rconPort = $node.rconPort; listeners = @($listeners); closed = ($listeners.Count -eq 0) }
            forced = $false; partialStartup = (-not $node.confirmedStart)
        })
        Save-Result
        if ($listeners.Count -gt 0) { throw "M10 node $nodeName has a listener on its reserved game/RCON ports but no uniquely marked JVM; refusing to stop an unrelated process." }
        if ($node.confirmedStart) { throw "Run-owned Paper node $nodeName disappeared before the runner requested graceful shutdown." }
        return
    }
    if ($processes.Count -ne 1) { throw "Expected exactly one run-owned JVM during $nodeName cleanup; observed $($processes.Count). Refusing ambiguous process cleanup." }
    $process = $processes[0]
    $gracefulFailure = $null
    try { Invoke-NodeCommand $nodeName 'stop' "$nodeName-stop.txt" 45 }
    catch { $gracefulFailure = $_.Exception.Message }

    if (-not (Wait-ProcessExit ([int]$process.ProcessId) 60)) {
        $stillOwned = @(Get-RunOwnedNodeProcesses $nodeName | Where-Object { [int]$_.ProcessId -eq [int]$process.ProcessId })
        if ($stillOwned.Count -ne 1) { throw "Paper node $nodeName remained after stop request and its run marker no longer uniquely proves ownership; refusing force cleanup." }
        Stop-Process -Id ([int]$process.ProcessId) -Force -ErrorAction Stop
        $exited = Wait-ProcessExit ([int]$process.ProcessId) 10
        $listeners = @(Get-NodeListeners $nodeName)
        $script:manifest.cleanup.Add([ordered]@{ target = "Paper node $nodeName"; pid = [int]$process.ProcessId; forced = $true; reason = 'Graceful stop did not exit within 60 seconds.' })
        $script:manifest.shutdown.Add([ordered]@{
            node = $nodeName; pid = [int]$process.ProcessId; processExitVerified = $exited
            pluginDrainVerified = $false; blockers = @('forced process termination after graceful stop timeout')
            listenerCheck = [ordered]@{ gamePort = $node.gamePort; rconPort = $node.rconPort; listeners = @($listeners); closed = ($listeners.Count -eq 0) }
            forced = $true; log = $script:manifest.nodes[$nodeName].serverLog
        })
        Save-Result
        throw "Paper node $nodeName required forced cleanup; runtime result is not a clean PASS."
    }
    if (-not [string]::IsNullOrWhiteSpace($gracefulFailure)) {
        throw "Paper node $nodeName exited but the graceful stop command failed: $gracefulFailure"
    }

    $listeners = @(Get-NodeListeners $nodeName)
    $bad = [System.Collections.Generic.List[string]]::new()
    if ($listeners.Count -gt 0) { $bad.Add("game/RCON ports still listen after JVM exit: $($listeners | ConvertTo-Json -Compress)") }
    $journal = $null
    $logPath = $script:manifest.nodes[$nodeName].serverLog
    if ($node.pluginEnabled) {
        $log = Get-Content -LiteralPath $logPath -Raw
        foreach ($marker in @('CRITICAL HEALTH: EconomyEventConsumer drain timed out', 'Accepted economy writes were handed off to recovery-v1.jsonl',
            'CRITICAL HEALTH: accepted economy writes remain owned', 'Redis write failed for event', 'Failed to process economy event')) {
            if ($log.Contains($marker, [StringComparison]::OrdinalIgnoreCase)) { $bad.Add($marker) }
        }
        $recoveryPath = Join-Path $node.root '.plugdev\run\plugins\Syncmoney\recovery-v1.jsonl'
        $journal = Read-RecoveryJournal $recoveryPath
        if ($journal.pendingCount -gt 0) { $bad.Add("recovery-v1.jsonl has $($journal.pendingCount) unacknowledged record(s)") }
    }
    $drained = $node.pluginEnabled -and $bad.Count -eq 0
    $script:manifest.shutdown.Add([ordered]@{
        node = $nodeName; pid = [int]$process.ProcessId; processExitVerified = $true
        pluginDrainVerified = if ($node.pluginEnabled) { $drained } else { $null }
        drainNotEvaluatedReason = if ($node.pluginEnabled) { $null } else { 'Plugin enable was not established before partial-start cleanup.' }
        blockers = @($bad); recoveryJournal = $journal; log = $logPath; forced = $false
        listenerCheck = [ordered]@{ gamePort = $node.gamePort; rconPort = $node.rconPort; listeners = @($listeners); closed = ($listeners.Count -eq 0) }
    })
    Save-Result
    if ($bad.Count -gt 0) { throw "Paper node $nodeName shutdown did not pass port closure and/or queue-drain checks." }
}

function Stop-RedisProcessesBeforeReady([string]$redisMarker) {
    $stopped = [System.Collections.Generic.List[int]]::new()
    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        $processes = @(Get-RunOwnedRedisProcesses $redisMarker)
        if ($processes.Count -eq 0) { break }
        $topology = Resolve-RedisRunProcesses $processes $redisMarker $null
        # Stop the shim/launcher first so it cannot create another marked server while
        # partial-start cleanup is in progress. The next pass re-resolves the remaining tree.
        $candidate = if ($null -ne $topology.launcher) { $topology.launcher } else { $topology.server }
        $candidatePid = [int]$candidate.ProcessId
        $fresh = Get-CimInstance Win32_Process -Filter "ProcessId = $candidatePid" -ErrorAction SilentlyContinue
        if ($null -eq $fresh) { continue }
        if ($fresh.Name -notin @('redis-server.exe', 'redis-server') -or
            -not ([string]$fresh.CommandLine).Contains($redisMarker, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Redis process $candidatePid no longer carries this run's exact marker; refusing cleanup."
        }
        Stop-Process -Id $candidatePid -Force -ErrorAction Stop
        if (-not (Wait-ProcessExit $candidatePid 10)) { throw "Run-owned Redis PID $candidatePid survived targeted startup cleanup." }
        $stopped.Add($candidatePid)
        $script:manifest.cleanup.Add([ordered]@{ target = 'isolated Redis launcher/server'; pid = $candidatePid; forced = $true; reason = 'Startup failed before a Redis listener was established.' })
    }
    if (@(Get-RunOwnedRedisProcesses $redisMarker).Count -gt 0) {
        throw 'Redis partial-start process tree remained after bounded, run-marker-verified cleanup.'
    }
    if ($stopped.Count -gt 0) { throw 'Redis required forced cleanup before its listener started; runtime result is not a clean PASS.' }
}

function Stop-Redis {
    if (-not (Test-Path -LiteralPath $redisConfig -PathType Leaf)) { return }
    $redisMarker = ConvertTo-CygwinPath $redisConfig
    $markerProcesses = @(Get-RunOwnedRedisProcesses $redisMarker)
    $listener = Get-NetTCPConnection -State Listen -LocalPort $redisPort -ErrorAction SilentlyContinue | Select-Object -First 1
    $startupVerified = ($null -ne $redisServerPid -or $null -ne $script:manifest.redis.initialDbSize)
    Assert-RedisRunPresence $markerProcesses.Count ($null -ne $listener) $startupVerified
    if ($markerProcesses.Count -eq 0 -and $null -eq $listener) { return }
    if ($null -eq $listener) {
        Stop-RedisProcessesBeforeReady $redisMarker
        return
    }
    $topology = Resolve-RedisRunProcesses $markerProcesses $redisMarker ([int]$listener.OwningProcess)
    if ($null -ne $redisServerPid -and [int]$listener.OwningProcess -ne [int]$redisServerPid) {
        throw 'Redis listener owner changed from the recorded Windows server PID; refusing cleanup.'
    }
    if ($null -ne $topology.launcher -and $null -ne $redisLauncherPid -and
        [int]$topology.launcher.ProcessId -ne [int]$redisLauncherPid) {
        throw 'Redis launcher/server parent-child identity differs from the recorded run launcher; refusing cleanup.'
    }
    $owned = $topology.server
    $redisServerPid = [int]$owned.ProcessId
    $gracefulFailure = $null
    try {
        $null = & $redisCli -h 127.0.0.1 -p $redisPort SHUTDOWN NOSAVE 2>&1
    } catch { $gracefulFailure = $_.Exception.Message }
    if (-not (Wait-ProcessExit ([int]$owned.ProcessId) 25)) {
        $remaining = @(Get-RunOwnedRedisProcesses $redisMarker)
        $currentListener = Get-NetTCPConnection -State Listen -LocalPort $redisPort -ErrorAction SilentlyContinue | Select-Object -First 1
        $currentOwner = if ($null -ne $currentListener) { [int]$currentListener.OwningProcess } else { $null }
        $stillOwned = Resolve-RedisRunProcesses $remaining $redisMarker $currentOwner
        if ([int]$stillOwned.server.ProcessId -ne [int]$owned.ProcessId) { throw 'Redis remained after SHUTDOWN but the run-marked listener/server identity changed; refusing force cleanup.' }
        Stop-Process -Id ([int]$owned.ProcessId) -Force -ErrorAction Stop
        $exited = Wait-ProcessExit ([int]$owned.ProcessId) 10
        $script:manifest.cleanup.Add([ordered]@{ target = 'isolated Redis'; pid = [int]$owned.ProcessId; forced = $true; reason = 'SHUTDOWN NOSAVE did not exit within 25 seconds.' })
        if (-not $exited) { throw 'Run-owned Redis survived targeted force cleanup.' }
        throw 'Redis required forced cleanup; runtime result is not a clean PASS.'
    }
    if (-not [string]::IsNullOrWhiteSpace($gracefulFailure)) { throw "Run-owned Redis exited but graceful SHUTDOWN failed: $gracefulFailure" }
    if ($null -ne (Get-NetTCPConnection -State Listen -LocalPort $redisPort -ErrorAction SilentlyContinue | Select-Object -First 1)) {
        throw 'Redis port remains open after the run-owned server exited.'
    }
    $redisLogPath = Join-Path (Split-Path -Parent $redisConfig) 'redis.log'
    $redisLog = Get-Content -LiteralPath $redisLogPath -Raw
    if (-not $redisLog.Contains('User requested shutdown', [StringComparison]::OrdinalIgnoreCase) -or
        -not $redisLog.Contains('Redis is now ready to exit', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Redis exited without both graceful SHUTDOWN log markers.'
    }
    $remainingProcesses = @(Get-RunOwnedRedisProcesses $redisMarker)
    if ($remainingProcesses.Count -gt 0) {
        $remainingTopology = Resolve-RedisRunProcesses $remainingProcesses $redisMarker $null
        if ($remainingProcesses.Count -ne 1 -or
            ($null -ne $redisLauncherPid -and [int]$remainingTopology.server.ProcessId -ne [int]$redisLauncherPid)) {
            throw 'Redis process tree remained after graceful shutdown without a unique recorded launcher; refusing force cleanup.'
        }
        $remainingPid = [int]$remainingTopology.server.ProcessId
        $fresh = Get-CimInstance Win32_Process -Filter "ProcessId = $remainingPid" -ErrorAction SilentlyContinue
        if ($null -eq $fresh) { throw 'Redis launcher disappeared during residual-process verification.' }
        if ($fresh.Name -notin @('redis-server.exe', 'redis-server') -or
            -not ([string]$fresh.CommandLine).Contains($redisMarker, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Redis launcher $remainingPid no longer carries this run's exact marker; refusing cleanup."
        }
        Stop-Process -Id $remainingPid -Force -ErrorAction Stop
        $exited = Wait-ProcessExit $remainingPid 10
        $script:manifest.cleanup.Add([ordered]@{ target = 'isolated Redis launcher'; pid = $remainingPid; forced = $true; reason = 'Launcher remained after graceful Redis shutdown.' })
        if (-not $exited) { throw 'Run-owned Redis launcher failed to exit after targeted cleanup.' }
        throw 'Redis launcher required forced cleanup; runtime result is not a clean PASS.'
    }
    $script:manifest.redis.cleanShutdown = $true
    $script:manifest.cleanup.Add([ordered]@{ target = 'isolated Redis'; pid = $owned.ProcessId; forced = $false; method = 'SHUTDOWN NOSAVE' })
}

try {
    $mineflayerPackage = Assert-M10ExpectedLocalMineflayerRoot $MineflayerRoot $canonicalMineflayerRoot
    $MineflayerRoot = [IO.Path]::GetFullPath($MineflayerRoot)
    $mineflayerPackageJson = Join-Path $MineflayerRoot 'package.json'
    $mineflayerPackageLock = Join-Path $MineflayerRoot 'package-lock.json'
    foreach ($requiredPath in @($endpointInfo.serverJar, $candidateJar, $acceptanceJar, $apiConsumerJar, $plugDevCli, $prepareTool, $clientTool, $identityHelperTool, $identityHelperTestTool, $observerTool, $runnerTestTool, $redisServer, $redisCli,
        $mineflayerPackageJson, $mineflayerPackageLock, (Join-Path $javaHome 'bin\java.exe'))) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) { throw "Required runtime input is missing: $requiredPath" }
    }
    if ($script:manifest.sourceCommit -notmatch '^[0-9a-f]{40}$') { throw 'Cannot identify the repository source commit.' }
    $actualServerSha = Get-Sha256 $endpointInfo.serverJar
    if ($actualServerSha -ne $endpointInfo.serverSha256) { throw "Server artifact hash mismatch: expected $($endpointInfo.serverSha256), observed $actualServerSha." }
    $script:manifest.endpoint.path = [IO.Path]::GetFullPath($endpointInfo.serverJar)
    $script:manifest.endpoint.sha256 = $actualServerSha
    $script:manifest.candidate.sha256 = Get-Sha256 $candidateJar
    $script:manifest.acceptance.sha256 = Get-Sha256 $acceptanceJar
    $script:manifest.acceptance.apiConsumerSha256 = Get-Sha256 $apiConsumerJar
    $script:manifest.acceptance.probeSourceSha256 = Get-Sha256 (Join-Path $repoRoot 'src\acceptance\java\noietime\syncmoney\acceptance\AcceptanceProbe.java')
    $script:manifest.acceptance.clientSourceSha256 = Get-Sha256 $clientTool
    $script:manifest.acceptance.identityHelperSourceSha256 = Get-Sha256 $identityHelperTool
    $script:manifest.acceptance.identityHelperTestSha256 = Get-Sha256 $identityHelperTestTool
    $script:manifest.acceptance.observerSourceSha256 = Get-Sha256 $observerTool
    $script:manifest.acceptance.runnerTestSha256 = Get-Sha256 $runnerTestTool
    $script:manifest.acceptance.runnerSha256 = Get-Sha256 $PSCommandPath
    $script:manifest.acceptance.prepareSha256 = Get-Sha256 $prepareTool
    $javaVersion = (& (Join-Path $javaHome 'bin\java.exe') -version 2>&1 | Out-String).Trim()
    if ($javaVersion -notmatch 'version "25(?:\.|"|\+)') { throw 'The runtime matrix requires Java 25.' }
    $script:manifest.java.version = $javaVersion
    $plugDevPackage = Assert-M10ExpectedLocalPlugDevRoot $PlugDevRoot $canonicalPlugDevRoot
    $plugDevCheckout = Get-GitCheckoutProvenance $PlugDevRoot
    if (-not $plugDevCheckout.workingTreeClean) { throw "The local PlugDev source checkout has $($plugDevCheckout.workingTreeStatusEntryCount) tracked or untracked change(s)." }
    $script:manifest.plugDev.commit = $plugDevCheckout.commit
    $script:manifest.plugDev.version = $plugDevPackage.version
    $script:manifest.plugDev.workingTreeClean = $plugDevCheckout.workingTreeClean
    $script:manifest.plugDev.workingTreeStatusEntryCount = $plugDevCheckout.workingTreeStatusEntryCount
    $script:manifest.plugDev.cli = [IO.Path]::GetFullPath($plugDevCli)
    $script:manifest.plugDev.cliDistRoot = [IO.Path]::GetFullPath($plugDevCliDistRoot)
    $script:manifest.plugDev.packageJsonSha256 = Get-Sha256 (Join-Path $PlugDevRoot 'package.json')
    $script:manifest.plugDev.cliSha256 = Get-Sha256 $plugDevCli
    $script:manifest.plugDev.cliDistManifest = @(Get-PlugDevCliDistManifest $plugDevCliDistRoot)
    $cliEntry = @($script:manifest.plugDev.cliDistManifest | Where-Object { [string]$_.path -ceq 'cli.js' })
    if ($cliEntry.Count -ne 1 -or [string]$cliEntry[0].sha256 -cne [string]$script:manifest.plugDev.cliSha256) {
        throw 'PlugDev CLI entrypoint hash is missing or differs from the recorded dist manifest.'
    }
    $mineflayerPackage = Get-Content -LiteralPath $mineflayerPackageJson -Raw | ConvertFrom-Json
    $script:manifest.mineflayer.version = $mineflayerPackage.version
    $script:manifest.mineflayer.packageJsonSha256 = Get-Sha256 $mineflayerPackageJson
    $script:manifest.mineflayer.packageLockPath = [IO.Path]::GetFullPath($mineflayerPackageLock)
    $null = Assert-M10NoReparsePath $mineflayerPackageLock
    $script:manifest.mineflayer.packageLockSha256 = Get-Sha256 $mineflayerPackageLock
    if ([string]::IsNullOrWhiteSpace([string]$mineflayerPackage.main)) { throw 'The local Mineflayer package does not declare its runtime entry point.' }
    $mineflayerEntry = Join-Path $MineflayerRoot ([string]$mineflayerPackage.main)
    $null = Assert-M10NoReparsePath $mineflayerEntry
    $script:manifest.mineflayer.entrypointPath = [IO.Path]::GetFullPath($mineflayerEntry)
    $script:manifest.mineflayer.entrypointSha256 = Get-Sha256 $mineflayerEntry
    $resolvedMineflayerEntry = Get-M10ResolvedMineflayerEntry $nodeExe $mineflayerPackageJson
    $script:manifest.mineflayer.resolvedRequirePath = Assert-M10ExpectedLocalMineflayerEntry $resolvedMineflayerEntry $mineflayerEntry
    $mineflayerCommit = (& git -C $MineflayerRoot rev-parse HEAD 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $mineflayerCommit -notmatch '^[0-9a-f]{40}$') { throw 'Cannot identify the local Mineflayer source commit.' }
    $script:manifest.mineflayer.commit = $mineflayerCommit
    $mineflayerStatus = (& git -C $MineflayerRoot status --porcelain 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify the local Mineflayer source checkout state.' }
    $script:manifest.mineflayer.workingTreeClean = [string]::IsNullOrWhiteSpace($mineflayerStatus)
    if (-not $script:manifest.mineflayer.workingTreeClean) { throw 'The local Mineflayer source checkout has uncommitted changes.' }
    $script:manifest.redis.version = (& $redisServer --version 2>&1 | Out-String).Trim()
    if ($script:manifest.redis.version -notmatch 'v=8\.10\.1') { throw "Expected isolated Redis 8.10.1, observed: $($script:manifest.redis.version)" }
    if (Test-Path -LiteralPath $runRoot) { throw "Refusing to reuse existing M10 report path: $runRoot" }
    $runnerSourceSnapshotDir = Join-Path $runRoot 'source-pins'
    $runnerSourceSnapshotPath = Join-Path $runnerSourceSnapshotDir 'run-m10-reconciliation.ps1'
    New-Item -ItemType Directory -Path @($runRoot, (Join-Path $runRoot 'redis'), (Join-Path $runRoot '.plugdev-home'), (Join-Path $runRoot '.gradle-user-home'), $runnerSourceSnapshotDir) -ErrorAction Stop | Out-Null
    $script:runRootOwned = $true
    $null = Assert-M10NoReparsePath $runnerSourceSnapshotDir
    Copy-Item -LiteralPath $PSCommandPath -Destination $runnerSourceSnapshotPath -ErrorAction Stop
    $script:manifest.acceptance.runnerSourceSnapshotPath = [IO.Path]::GetFullPath($runnerSourceSnapshotPath)
    if ((Get-Sha256 $runnerSourceSnapshotPath) -cne [string]$script:manifest.acceptance.runnerSha256) {
        throw 'The retained M10 runner source snapshot does not match the report pin.'
    }
    foreach ($port in @($redisPort, $gamePorts.a, ($gamePorts.a + 10000), $gamePorts.b, ($gamePorts.b + 10000))) { Assert-PortClosed $port }
    $env:JAVA_HOME = $javaHome
    $env:Path = "$javaHome\bin;$env:Path"
    $env:PLUGDEV_ROOT = [IO.Path]::GetFullPath($PlugDevRoot)
    $env:PLUGDEV_HOME = Join-Path $runRoot '.plugdev-home'
    $env:GRADLE_USER_HOME = Join-Path $runRoot '.gradle-user-home'
    Set-Content -LiteralPath (Join-Path $env:GRADLE_USER_HOME 'gradle.properties') -Value 'org.gradle.daemon=false' -Encoding ascii

    $redisDir = Join-Path $runRoot 'redis'
    $redisData = ConvertTo-CygwinPath $redisDir
    $redisConfigArgument = ConvertTo-CygwinPath $redisConfig
    @('bind 127.0.0.1', "port $redisPort", 'protected-mode yes', 'daemonize no', 'databases 16', 'save ""', 'appendonly no',
        "dir $redisData", 'dbfilename dump.rdb', "logfile $redisData/redis.log", 'loglevel notice') |
        Set-Content -LiteralPath $redisConfig -Encoding ascii
    $redisProcess = Start-Process -FilePath $redisServer -ArgumentList @($redisConfigArgument) -WorkingDirectory $redisDir -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $redisDir 'stdout.log') -RedirectStandardError (Join-Path $redisDir 'stderr.log') -PassThru
    $redisLauncherPid = [int]$redisProcess.Id
    Wait-RedisReady
    $redisInfo = Invoke-Redis @('INFO', 'server')
    $redisInfoPid = Get-RedisInfoProcessId $redisInfo
    $listener = Get-NetTCPConnection -State Listen -LocalPort $redisPort -ErrorAction Stop | Select-Object -First 1
    $redisProcesses = @(Get-RunOwnedRedisProcesses $redisConfigArgument)
    $redisTopology = Resolve-RedisRunProcesses $redisProcesses $redisConfigArgument ([int]$listener.OwningProcess)
    $redisServerPid = [int]$listener.OwningProcess
    if ([int]$redisTopology.server.ProcessId -ne $redisServerPid) { throw 'Redis listener is not owned by the unique run-marked server process.' }
    if ($null -ne $redisTopology.launcher -and [int]$redisTopology.launcher.ProcessId -ne $redisLauncherPid) {
        throw 'Redis shim launcher PID differs from the exact process started for this run.'
    }
    $script:manifest.redis.pid = $redisServerPid
    $script:manifest.redis.infoPid = $redisInfoPid
    $script:manifest.redis.launcherPid = $redisLauncherPid
    $script:manifest.redis.initialDbSize = [int](Invoke-Redis @('DBSIZE'))
    if ($script:manifest.redis.initialDbSize -ne 0) { throw 'Isolated Redis DB 15 was not empty before the runtime nodes started.' }

    New-NodeProject 'a' $gamePorts.a
    New-NodeProject 'b' $gamePorts.b
    Save-Result
    Start-Node 'a'
    Start-Node 'b'
    $null = Wait-BPubSub $true 60

    Start-M10Client
    $uuid = [string]$script:manifest.account.uuid
    Invoke-NodeCommand 'b' 'smaccept m10 ready AcceptanceM10 60' 'b-account-ready.txt' 45
    Wait-ServerMarker 'b' 'T21_M10_READY_PASS name=AcceptanceM10' 70
    $null = Snapshot-Memory 'b'
    $baseline = Read-MemoryMarker 'b'
    if ($baseline.uuid -ne $uuid) { throw 'The Mineflayer UUID and Paper B online player UUID differ.' }
    if ($baseline.balance -ne '0.00' -or $baseline.version -ne '0') { throw "Unexpected initial B memory state $($baseline.balance)/v$($baseline.version)." }

    Invoke-NodeCommand 'a' "smaccept m10 warm $uuid 60" 'a-account-warm.txt' 70
    Wait-ServerMarker 'a' "T21_M10_WARM_PASS uuid=$uuid" 75

    $initialDepositId = [guid]::NewGuid().ToString('N')
    Invoke-NodeCommand 'a' "smaccept m10 deposit $uuid 1.00 $initialDepositId" 'a-initial-deposit.txt'
    Wait-ServerMarker 'a' "T21_M10_DEPOSIT_PASS request=$initialDepositId uuid=$uuid" 20
    $initialAuthority = Wait-Authority $uuid '1.00' '1' 30
    $null = Snapshot-Memory 'b'
    $baseline = Read-MemoryMarker 'b'
    if ($baseline.balance -ne '1.00' -or $baseline.version -ne '1') { throw 'B did not apply the live initial Pub/Sub update to 1.00/v1.' }
    $script:manifest.account.initial = $baseline
    Save-Result

    $missed = $false
    $targetAuthority = $null
    for ($attempt = 1; $attempt -le 4 -and -not $missed; $attempt++) {
        $before = Read-MemoryMarker 'b'
        if ($before.uuid -ne $uuid) { throw 'The B memory snapshot UUID changed during the bounded retry loop.' }
        $oldBalance = [decimal]::Parse($before.balance, [Globalization.CultureInfo]::InvariantCulture)
        $nextBalance = ($oldBalance + [decimal]1).ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
        $nextVersion = ([long]$before.version + 1).ToString([Globalization.CultureInfo]::InvariantCulture)
        $killed = Disconnect-BPubSub "missed-message-attempt-$attempt"
        if (-not $killed) { throw 'B Pub/Sub client was not identifiable by its Java process socket before disconnect.' }
        $null = Wait-BPubSub $false 5

        $depositId = [guid]::NewGuid().ToString('N')
        $observer = Start-M10PubSubObserver $uuid $depositId $nextBalance $nextVersion
        $pendingDeposit = Start-M10DepositCommand $uuid $depositId
        $depositOutcome = Wait-DepositAuthorityWhileSuppressingB $uuid $depositId $nextBalance $nextVersion $observer $pendingDeposit 60
        $authority = $depositOutcome.authority
        Start-Sleep -Seconds 2
        $null = Snapshot-Memory 'b'
        $after = Read-MemoryMarker 'b'
        $null = Wait-BPubSub $true 30
        $reconnectObservation = Observe-BMemoryAfterReconnect $before $nextBalance $nextVersion
        $afterReconnect = $reconnectObservation.memory
        $script:manifest.pubsubDisconnectAttempts.Add([ordered]@{
            attempt = $attempt; priorMemory = $before; authority = $authority; afterPublishMemory = $after
            afterReconnectMemory = $afterReconnect; afterReconnectObservations = $reconnectObservation.observations
            result = $reconnectObservation.result
        })
        if ($reconnectObservation.result -eq 'MESSAGE_MISSED_NO_REPLAY') {
            $missed = $true
            $targetAuthority = [ordered]@{ balance = $nextBalance; version = $nextVersion }
            $script:manifest.account.missedPubSub = [ordered]@{
                oldMemory = $afterReconnect; redisAuthority = $targetAuthority; subscriberReconnected = $true
                replayObserved = $false; publication = $depositOutcome.publication
                postReconnectObservations = $reconnectObservation.observations
            }
        }
        Save-Result
    }
    if (-not $missed) { throw 'The runtime could not observe a lost Pub/Sub message after four bounded reconnect attempts.' }

    $minimumWait = $nodes.b.enabledAtUtc.AddSeconds(290)
    if ([DateTime]::UtcNow -lt $minimumWait) { Start-Sleep -Milliseconds ([int][Math]::Min(60000, ($minimumWait - [DateTime]::UtcNow).TotalMilliseconds)) }
    $remaining = 600
    Invoke-NodeCommand 'b' "smaccept m10 await AcceptanceM10 $($targetAuthority.balance) $($targetAuthority.version) $remaining" 'b-await-reconciliation.txt' 45
    Wait-ServerMarker 'b' 'T21_M10_RECONCILE_PASS name=AcceptanceM10' ($remaining + 10)
    if ($script:clientProcess.HasExited) { throw 'The online acceptance player disconnected before reconciliation was observed.' }
    $reconcileLog = Get-Content -LiteralPath $script:manifest.nodes.b.serverLog -Raw
    $reconcileMatches = [regex]::Matches($reconcileLog, 'T21_M10_RECONCILE_PASS name=AcceptanceM10 uuid=([0-9a-fA-F-]{36}) balance=([0-9]+(?:\.[0-9]{2})?) version=(\d+)')
    if ($reconcileMatches.Count -eq 0) { throw 'The final periodic reconciliation success marker was not retained in B server log.' }
    $reconcile = $reconcileMatches[$reconcileMatches.Count - 1]
    if ($reconcile.Groups[1].Value -ne $uuid -or $reconcile.Groups[2].Value -ne [string]$targetAuthority.balance -or
        $reconcile.Groups[3].Value -ne [string]$targetAuthority.version) { throw 'B reconciliation marker did not match the Redis authority pair.' }
    $finalMemory = [ordered]@{ uuid = $reconcile.Groups[1].Value; balance = $reconcile.Groups[2].Value; version = $reconcile.Groups[3].Value; observedAtUtc = [DateTime]::UtcNow.ToString('o') }
    $elapsed = ([DateTime]::UtcNow - $nodes.b.enabledAtUtc).TotalSeconds
    if ($elapsed -lt 290) { throw "Reconciliation appeared before the five-minute periodic schedule lower bound; elapsed=$elapsed seconds." }
    $finalAuthority = Read-Authority $uuid
    if ($finalAuthority.balance -ne [string]$targetAuthority.balance -or $finalAuthority.version -ne [string]$targetAuthority.version) {
        throw 'Redis authority changed after B reconciliation; the expected account pair is not stable.'
    }
    $script:manifest.account.authority = $finalAuthority
    $script:manifest.account.reconciled = $finalMemory
    $script:manifest.account.elapsedFromPluginEnableSeconds = [Math]::Round($elapsed, 1)
    $script:manifest.redis.finalDbSize = [int](Invoke-Redis @('DBSIZE'))
    $script:manifest.status = 'PASS_BOUNDED_SUBCASE'
    $script:manifest.finishedAtUtc = [DateTime]::UtcNow.ToString('o')
    Save-Result
} catch {
    $script:manifest.status = 'FAIL'
    $script:manifest.failure = $_.Exception.Message
    Save-Result
    throw
} finally {
    foreach ($cleanup in @(
        { Stop-M10DepositCommand },
        { Stop-M10PubSubObserver },
        { Stop-M10Client },
        { Stop-Node 'b' },
        { Stop-Node 'a' },
        { Stop-Redis }
    )) {
        try { & $cleanup } catch {
            if ([string]::IsNullOrWhiteSpace([string]$script:manifest.cleanupFailure)) { $script:manifest.cleanupFailure = $_.Exception.Message }
            else { $script:manifest.cleanupFailure = [string]$script:manifest.cleanupFailure + ' | ' + $_.Exception.Message }
        }
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$script:manifest.cleanupFailure)) {
        $script:manifest.status = 'FAIL'
    }
    if ($script:manifest.status -eq 'PASS_BOUNDED_SUBCASE' -and -not $script:manifest.redis.cleanShutdown) {
        $script:manifest.status = 'FAIL'
        if ([string]::IsNullOrWhiteSpace([string]$script:manifest.cleanupFailure)) {
            $script:manifest.cleanupFailure = 'Redis graceful shutdown was not verified.'
        }
    }
    Save-Result
}

if ($script:manifest.status -ne 'PASS_BOUNDED_SUBCASE' -or -not [string]::IsNullOrWhiteSpace([string]$script:manifest.cleanupFailure)) {
    throw "M10 runtime did not complete cleanly: status=$($script:manifest.status); cleanup=$($script:manifest.cleanupFailure); report=$(Join-Path $runRoot 'result.json')"
}
Write-Output "PASS_BOUNDED_SUBCASE: $($endpointInfo.label) M10 Redis reconciliation. Report: $(Join-Path $runRoot 'result.json')"
