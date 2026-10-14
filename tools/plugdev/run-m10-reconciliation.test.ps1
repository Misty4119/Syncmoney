$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$runnerPath = Join-Path $PSScriptRoot 'run-m10-reconciliation.ps1'
$tokens = $null
$parseErrors = $null
$runnerAst = [System.Management.Automation.Language.Parser]::ParseFile($runnerPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) {
    $messages = @($parseErrors | ForEach-Object { $_.Message }) -join '; '
    throw "M10 runner has PowerShell syntax errors: $messages"
}
$runnerSource = Get-Content -LiteralPath $runnerPath -Raw
if (-not $runnerSource.Contains('requires PowerShell 7 or later; invoke it with pwsh.', [StringComparison]::Ordinal)) {
    throw 'M10 runner does not clearly reject unsupported Windows PowerShell versions.'
}
foreach ($boundedRedisMarker in @(
        '[System.Diagnostics.ProcessStartInfo]::new()',
        '$startInfo.ArgumentList.Add([string]$argument)',
        'ReadToEndAsync()',
        'WaitForExit($timeoutMilliseconds)',
        '$process.Kill()')) {
    if (-not $runnerSource.Contains($boundedRedisMarker, [StringComparison]::Ordinal)) {
        throw "M10 runner does not enforce bounded redis-cli execution: $boundedRedisMarker"
    }
}
foreach ($pin in @(
        '$identityHelperTool',
        '$identityHelperTestTool',
        'identityHelperPath = $identityHelperTool',
        'identityHelperSourceSha256 = $null',
        'identityHelperTestPath = $identityHelperTestTool',
        'identityHelperTestSha256 = $null',
        'cliSha256 = $null',
        'cliDistManifest = @()',
        'workingTreeStatusEntryCount = $null',
        '$script:manifest.mineflayer.commit = $mineflayerCommit',
        '$script:manifest.mineflayer.workingTreeClean = [string]::IsNullOrWhiteSpace($mineflayerStatus)',
        'packageLockSha256 = $null',
        'entrypointSha256 = $null',
        '$script:manifest.mineflayer.packageLockSha256 = Get-Sha256 $mineflayerPackageLock',
        '$script:manifest.mineflayer.entrypointSha256 = Get-Sha256 $mineflayerEntry',
        '$script:manifest.mineflayer.resolvedRequirePath = Assert-M10ExpectedLocalMineflayerEntry $resolvedMineflayerEntry $mineflayerEntry',
        '$null = Assert-M10ExpectedLocalMineflayerEntry $resolvedEntry ([string]$script:manifest.mineflayer.entrypointPath)',
        '$script:manifest.plugDev.cliSha256 = Get-Sha256 $plugDevCli',
        '$script:manifest.plugDev.workingTreeClean = $plugDevCheckout.workingTreeClean',
        '$null = Assert-PlugDevCliDistManifest $script:manifest.plugDev.cliDistRoot @($script:manifest.plugDev.cliDistManifest)',
        'status --porcelain=v1 --untracked-files=all')) {
    if (-not $runnerSource.Contains($pin, [StringComparison]::Ordinal)) {
        throw "M10 runner does not record required source provenance: $pin"
    }
}
if (-not $runnerSource.Contains('$prepareTool, $clientTool, $identityHelperTool, $identityHelperTestTool, $observerTool', [StringComparison]::Ordinal)) {
    throw 'M10 runner does not require both identity-helper source files before runtime.'
}
if ([regex]::Matches($runnerSource, '\$null = Assert-PlugDevCliUnchanged').Count -ne 3) {
    throw 'M10 runner does not revalidate the full local PlugDev CLI bundle before all three CLI invocation paths.'
}
$expectedEndpoints = @(
    "'paper-1216-min' = [ordered]@{ software = 'paper'; label = 'Paper 1.21.6#48'; minecraftVersion = '1.21.6'; build = 48; artifact = 'paper-1.21.6-48.jar'; sha256 = '35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214' }",
    "'paper-263-max' = [ordered]@{ software = 'paper'; label = 'Paper 26.3#166'; minecraftVersion = '26.3'; build = 166; artifact = 'paper-26.3-166.jar'; sha256 = 'dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd' }",
    "'folia-1216-min' = [ordered]@{ software = 'folia'; label = 'Folia 1.21.6#6'; minecraftVersion = '1.21.6'; build = 6; artifact = 'folia-1.21.6-6.jar'; sha256 = '917a8abaa542c1d0ef0e969693e227a7bfe1c42e671aaa806309e00a64efc234' }",
    "'folia-262-max' = [ordered]@{ software = 'folia'; label = 'Folia 26.2#7'; minecraftVersion = '26.2'; build = 7; artifact = 'folia-26.2-7.jar'; sha256 = '128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3' }",
    "'canvas-1218-min' = [ordered]@{ software = 'canvas'; label = 'Canvas 1.21.8#603'; minecraftVersion = '1.21.8'; build = 603; artifact = 'canvas-1.21.8-603.jar'; sha256 = '6577181e4bc8ac40a7a48ff00e262f2ac7a798f48ada446a07eb06618dc36f1b' }",
    "'canvas-263-max' = [ordered]@{ software = 'canvas'; label = 'Canvas 26.3#972'; minecraftVersion = '26.3'; build = 972; artifact = 'canvas-26.3-972.jar'; sha256 = 'aac025fe36a61f84fecb04de74e94bbf933c7081c4c88f13a9d4b96de42243f5' }"
)
foreach ($endpoint in $expectedEndpoints) {
    if (-not $runnerSource.Contains($endpoint, [StringComparison]::Ordinal)) {
        throw "M10 runner is missing or changed a user-pinned platform endpoint: $endpoint"
    }
}
if (-not $runnerSource.Contains("ValidateSet('paper-1216-min', 'paper-263-max', 'folia-1216-min', 'folia-262-max', 'canvas-1218-min', 'canvas-263-max')", [StringComparison]::Ordinal)) {
    throw 'M10 runner parameter does not expose the complete six-endpoint platform matrix.'
}
$probePath = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'src\acceptance\java\noietime\syncmoney\acceptance\AcceptanceProbe.java'
$probeSource = Get-Content -LiteralPath $probePath -Raw
$dispatchStart = $probeSource.IndexOf('private void dispatchM10OnlinePlayer(', [StringComparison]::Ordinal)
$dispatchEnd = $probeSource.IndexOf('private void runM10Deposit(', $dispatchStart, [StringComparison]::Ordinal)
if ($dispatchStart -lt 0 -or $dispatchEnd -lt 0) { throw 'M10 entity-scheduler helper was not found in AcceptanceProbe.' }
$dispatchSource = $probeSource.Substring($dispatchStart, $dispatchEnd - $dispatchStart)
if (-not $dispatchSource.Contains('player.getScheduler().run(this', [StringComparison]::Ordinal) -or
    -not $dispatchSource.Contains('player.isOnline()', [StringComparison]::Ordinal) -or
    -not $dispatchSource.Contains('player.getUniqueId()', [StringComparison]::Ordinal) -or
    -not $dispatchSource.Contains('reason=entity-retired', [StringComparison]::Ordinal)) {
    throw 'M10 player lookup must check online state and read UUID on its entity scheduler with a retired callback.'
}
$globalPrefix = $dispatchSource.Substring(0, $dispatchSource.IndexOf('player.getScheduler().run(this', [StringComparison]::Ordinal))
if ($globalPrefix.Contains('player.isOnline()', [StringComparison]::Ordinal) -or $globalPrefix.Contains('player.getUniqueId()', [StringComparison]::Ordinal)) {
    throw 'M10 global-scheduler dispatch reads player-owned state before entering the entity scheduler.'
}
$resolverAst = $runnerAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Resolve-RedisRunProcesses'
}, $true)
if ($null -eq $resolverAst) { throw 'M10 Redis process ownership resolver was not found in the runner.' }
. ([scriptblock]::Create($resolverAst.Extent.Text))
$presenceGuardAst = $runnerAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-RedisRunPresence'
}, $true)
if ($null -eq $presenceGuardAst) { throw 'M10 Redis cleanup presence guard was not found in the runner.' }
. ([scriptblock]::Create($presenceGuardAst.Extent.Text))
$disconnectClientAst = $runnerAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Disconnect-BPubSubClient'
}, $true)
if ($null -eq $disconnectClientAst) { throw 'M10 Pub/Sub client disconnect helper was not found in the runner.' }
. ([scriptblock]::Create($disconnectClientAst.Extent.Text))
$disconnectPubSubAst = $runnerAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Disconnect-BPubSub'
}, $true)
if ($null -eq $disconnectPubSubAst) { throw 'M10 Pub/Sub disconnect wrapper was not found in the runner.' }
. ([scriptblock]::Create($disconnectPubSubAst.Extent.Text))
$waitPubSubAst = $runnerAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Wait-BPubSub'
}, $true)
if ($null -eq $waitPubSubAst) { throw 'M10 Pub/Sub state waiter was not found in the runner.' }
. ([scriptblock]::Create($waitPubSubAst.Extent.Text))
$observeMemoryAst = $runnerAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Observe-BMemoryAfterReconnect'
}, $true)
if ($null -eq $observeMemoryAst) { throw 'M10 post-reconnect memory observer was not found in the runner.' }
. ([scriptblock]::Create($observeMemoryAst.Extent.Text))
$redisInfoProcessIdAst = $runnerAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-RedisInfoProcessId'
}, $true)
if ($null -eq $redisInfoProcessIdAst) { throw 'M10 Redis INFO process id parser was not found in the runner.' }
. ([scriptblock]::Create($redisInfoProcessIdAst.Extent.Text))

foreach ($helperName in @('Assert-M10NoReparsePath', 'Assert-M10ExpectedLocalPlugDevRoot', 'Assert-M10ExpectedLocalMineflayerRoot', 'Get-M10ResolvedMineflayerEntry', 'Assert-M10ExpectedLocalMineflayerEntry', 'Get-Sha256', 'Get-PlugDevCliDistManifest', 'Assert-PlugDevCliDistManifest', 'Get-GitCheckoutProvenance')) {
    $helperAst = $runnerAst.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $helperName
    }, $true)
    if ($null -eq $helperAst) { throw "M10 PlugDev provenance helper was not found: $helperName" }
    . ([scriptblock]::Create($helperAst.Extent.Text))
}

function New-TestRedisProcess([int]$processId, [int]$parentProcessId, [string]$commandLine) {
    return [pscustomobject]@{
        ProcessId = $processId
        ParentProcessId = $parentProcessId
        Name = 'redis-server.exe'
        CommandLine = $commandLine
    }
}

function Assert-Throws([scriptblock]$action, [string]$message) {
    $thrown = $false
    try { & $action } catch { $thrown = $true }
    if (-not $thrown) { throw $message }
}

$provenanceTempRoot = Join-Path ([IO.Path]::GetTempPath()) "syncmoney-m10-provenance-$([guid]::NewGuid().ToString('N'))"
$provenanceDistRoot = Join-Path $provenanceTempRoot 'packages/cli/dist'
$provenanceRepoRoot = Join-Path $provenanceTempRoot 'source'
try {
    $null = New-Item -ItemType Directory -Path (Join-Path $provenanceDistRoot 'spec') -Force
    $null = New-Item -ItemType Directory -Path $provenanceRepoRoot -Force
    [IO.File]::WriteAllText((Join-Path $provenanceDistRoot 'cli.js'), 'export const cli = true;', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $provenanceDistRoot 'spec/schema.json'), '{"version":1}', [Text.UTF8Encoding]::new($false))
    $distManifest = @(Get-PlugDevCliDistManifest $provenanceDistRoot)
    if ($distManifest.Count -ne 2 -or $distManifest[0].path -cne 'cli.js' -or
        $distManifest[1].path -cne 'spec/schema.json' -or
        @($distManifest | Where-Object { $_.sha256 -notmatch '^[0-9a-f]{64}$' }).Count -ne 0) {
        throw 'M10 PlugDev CLI manifest did not include sorted relative paths, lengths, and SHA-256 hashes.'
    }
    if (-not (Assert-PlugDevCliDistManifest $provenanceDistRoot $distManifest)) {
        throw 'M10 PlugDev CLI manifest failed its unchanged-bundle check.'
    }
    [IO.File]::WriteAllText((Join-Path $provenanceDistRoot 'cli.js'), 'export const cli = false;', [Text.UTF8Encoding]::new($false))
    Assert-Throws { Assert-PlugDevCliDistManifest $provenanceDistRoot $distManifest } 'M10 PlugDev CLI manifest accepted a modified ignored bundle file.'
    [IO.File]::WriteAllText((Join-Path $provenanceDistRoot 'cli.js'), 'export const cli = true;', [Text.UTF8Encoding]::new($false))
    Remove-Item -LiteralPath (Join-Path $provenanceDistRoot 'spec/schema.json')
    Assert-Throws { Assert-PlugDevCliDistManifest $provenanceDistRoot $distManifest } 'M10 PlugDev CLI manifest accepted a removed ignored bundle file.'
    [IO.File]::WriteAllText((Join-Path $provenanceDistRoot 'spec/schema.json'), '{"version":1}', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $provenanceDistRoot 'new-module.js'), 'export const added = true;', [Text.UTF8Encoding]::new($false))
    Assert-Throws { Assert-PlugDevCliDistManifest $provenanceDistRoot $distManifest } 'M10 PlugDev CLI manifest accepted an added ignored bundle file.'

    $plugDevRoot = Join-Path $provenanceTempRoot 'plugdev'
    $null = New-Item -ItemType Directory -Path $plugDevRoot -Force
    $plugDevPackagePath = Join-Path $plugDevRoot 'package.json'
    [IO.File]::WriteAllText($plugDevPackagePath, '{"name":"plugdev","version":"1.3.0","private":true}', [Text.UTF8Encoding]::new($false))
    $plugDevPackage = Assert-M10ExpectedLocalPlugDevRoot $plugDevRoot $plugDevRoot
    if ($plugDevPackage.private -ne $true -or $plugDevPackage.version -ne '1.3.0') {
        throw 'M10 PlugDev local-project metadata validation returned unexpected metadata.'
    }
    [IO.File]::WriteAllText($plugDevPackagePath, '{"name":"plugdev","version":"1.3.0","private":false}', [Text.UTF8Encoding]::new($false))
    Assert-Throws { Assert-M10ExpectedLocalPlugDevRoot $plugDevRoot $plugDevRoot } 'M10 PlugDev provenance accepted package metadata that is not private.'

    $mineflayerRoot = Join-Path $provenanceTempRoot 'mineflayer'
    $null = New-Item -ItemType Directory -Path $mineflayerRoot -Force
    [IO.File]::WriteAllText((Join-Path $mineflayerRoot 'package.json'), '{"name":"mineflayer","version":"4.39.0","main":"index.js"}', [Text.UTF8Encoding]::new($false))
    $mineflayerPackage = Assert-M10ExpectedLocalMineflayerRoot $mineflayerRoot $mineflayerRoot
    if ($mineflayerPackage.name -ne 'mineflayer' -or $mineflayerPackage.version -ne '4.39.0') {
        throw 'M10 Mineflayer local-project metadata validation returned unexpected metadata.'
    }
    Assert-Throws { Assert-M10ExpectedLocalMineflayerRoot $mineflayerRoot (Join-Path $provenanceTempRoot 'substituted-mineflayer') } 'M10 Mineflayer provenance accepted a substituted checkout root.'
    $localMineflayerRoot = 'C:\Users\margo\IdeaProjects\mineflayer-master'
    $localMineflayerPackage = Join-Path $localMineflayerRoot 'package.json'
    $localMineflayerMain = (Get-Content -LiteralPath $localMineflayerPackage -Raw | ConvertFrom-Json).main
    $resolvedMineflayer = Get-M10ResolvedMineflayerEntry (Get-Command node -ErrorAction Stop).Source $localMineflayerPackage
    $expectedMineflayerEntry = Join-Path $localMineflayerRoot $localMineflayerMain
    if ((Assert-M10ExpectedLocalMineflayerEntry $resolvedMineflayer $expectedMineflayerEntry) -ne [IO.Path]::GetFullPath($expectedMineflayerEntry)) {
        throw 'M10 Mineflayer resolver did not return the pinned local entrypoint.'
    }
    Assert-Throws { Assert-M10ExpectedLocalMineflayerEntry $resolvedMineflayer (Join-Path $provenanceTempRoot 'substituted-index.js') } 'M10 Mineflayer resolver accepted an entrypoint outside the local checkout.'

    $junctionTarget = Join-Path $provenanceTempRoot 'junction-target'
    $junctionRoot = Join-Path $provenanceTempRoot 'junction-root'
    $junctionCheckout = Join-Path $junctionRoot 'plugdev'
    $null = New-Item -ItemType Directory -Path (Join-Path $junctionTarget 'plugdev') -Force
    [IO.File]::WriteAllText((Join-Path $junctionTarget 'plugdev/package.json'), '{"name":"plugdev","version":"1.3.0","private":true}', [Text.UTF8Encoding]::new($false))
    $null = New-Item -ItemType Junction -Path $junctionRoot -Target $junctionTarget
    Assert-Throws { Assert-M10ExpectedLocalPlugDevRoot $junctionCheckout $junctionCheckout } 'M10 PlugDev provenance accepted an ancestor junction.'

    $gitInitOutput = @(& git -C $provenanceRepoRoot init --quiet 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "M10 temporary Git fixture initialization failed: $($gitInitOutput -join ' ')" }
    [IO.File]::WriteAllText((Join-Path $provenanceRepoRoot 'tracked.txt'), 'baseline', [Text.UTF8Encoding]::new($false))
    & git -C $provenanceRepoRoot add -- tracked.txt | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'M10 temporary Git fixture could not stage its baseline file.' }
    & git -C $provenanceRepoRoot -c user.name=M10-Test -c user.email=m10-test@example.invalid commit --quiet -m baseline
    if ($LASTEXITCODE -ne 0) { throw 'M10 temporary Git fixture could not create its baseline commit.' }
    $cleanCheckout = Get-GitCheckoutProvenance $provenanceRepoRoot
    if (-not $cleanCheckout.workingTreeClean -or $cleanCheckout.workingTreeStatusEntryCount -ne 0 -or $cleanCheckout.commit -notmatch '^[0-9a-f]{40}$') {
        throw 'M10 PlugDev provenance did not identify a clean Git source checkout.'
    }
    [IO.File]::WriteAllText((Join-Path $provenanceRepoRoot 'new-file.txt'), 'untracked', [Text.UTF8Encoding]::new($false))
    $dirtyCheckout = Get-GitCheckoutProvenance $provenanceRepoRoot
    if ($dirtyCheckout.workingTreeClean -or $dirtyCheckout.workingTreeStatusEntryCount -ne 1) {
        throw 'M10 PlugDev provenance failed to detect an untracked source-tree file.'
    }
} finally {
    if (Test-Path -LiteralPath $provenanceTempRoot) { Remove-Item -LiteralPath $provenanceTempRoot -Recurse -Force }
}

$redisInfoProcessId = Get-RedisInfoProcessId "# Server`r`nprocess_id:2768`r`nredis_version:8.10.1`r`n"
if ($redisInfoProcessId -ne 2768) { throw 'M10 Redis INFO parser did not tolerate CRLF output from redis-cli.' }
Assert-Throws { Get-RedisInfoProcessId "# Server`nredis_version:8.10.1`n" } 'M10 Redis INFO parser accepted a response without a process_id field.'
Assert-Throws { Get-RedisInfoProcessId "# Server`r`nprocess_id:2768`r`nprocess_id:2769`r`n" } 'M10 Redis INFO parser accepted an ambiguous duplicate process_id field.'
Assert-Throws { Get-RedisInfoProcessId "# Server`r`nprocess_id:2768`r`nprocess_id:bad`r`n" } 'M10 Redis INFO parser accepted a valid process_id mixed with a malformed duplicate.'
Assert-Throws { Get-RedisInfoProcessId "# Server`r`nprocess_id:0`r`n" } 'M10 Redis INFO parser accepted process_id zero.'

$marker = '/cygdrive/c/temp/m10-run/redis/redis.conf'
$launcher = New-TestRedisProcess 100 10 "C:\scoop\shims\redis-server.exe $marker"
$server = New-TestRedisProcess 200 100 "C:\scoop\apps\redis\redis-server.exe $marker"
$topology = Resolve-RedisRunProcesses @($launcher, $server) $marker 200
if ([int]$topology.server.ProcessId -ne 200 -or [int]$topology.launcher.ProcessId -ne 100) {
    throw 'M10 Redis resolver did not identify the listener-owned child and its unique launcher.'
}

$topology = Resolve-RedisRunProcesses @($server) $marker 200
if ([int]$topology.server.ProcessId -ne 200 -or $null -ne $topology.launcher) {
    throw 'M10 Redis resolver rejected or misclassified a uniquely run-marked standalone server.'
}

$topology = Resolve-RedisRunProcesses @($launcher, $server) $marker $null
if ([int]$topology.launcher.ProcessId -ne 100 -or [int]$topology.server.ProcessId -ne 200) {
    throw 'M10 Redis resolver did not identify the partial-start launcher/server pair.'
}

Assert-Throws { Resolve-RedisRunProcesses @($launcher, $server) $marker 100 } 'M10 Redis resolver accepted the launcher as the listener owner.'
Assert-Throws { Resolve-RedisRunProcesses @($launcher, $server, (New-TestRedisProcess 300 10 "C:\other\redis-server.exe $marker")) $marker $null } 'M10 Redis resolver accepted more than one launcher/server pair.'
Assert-Throws { Resolve-RedisRunProcesses @($launcher, (New-TestRedisProcess 200 11 "C:\scoop\apps\redis\redis-server.exe $marker")) $marker 200 } 'M10 Redis resolver accepted two run-marked processes without one parent-child relationship.'
Assert-Throws { Resolve-RedisRunProcesses @((New-TestRedisProcess 200 11 'C:\scoop\apps\redis\redis-server.exe C:\other\redis.conf')) $marker 200 } 'M10 Redis resolver accepted a process without the exact run marker.'
Assert-RedisRunPresence 0 $false $false
Assert-RedisRunPresence 1 $false $true
Assert-Throws { Assert-RedisRunPresence 0 $false $true } 'M10 Redis cleanup accepted disappearance after startup as a clean shutdown.'

$script:m10PubSubClientQueue = [System.Collections.Queue]::new()
$script:m10PubSubRedisResultQueue = [System.Collections.Queue]::new()
$script:m10PubSubRedisCalls = [System.Collections.Generic.List[string]]::new()
$script:m10PubSubRedisTimeouts = [System.Collections.Generic.List[int]]::new()
$script:manifest = [ordered]@{ pubsubDisconnectAttempts = [System.Collections.Generic.List[object]]::new() }
function Get-BPubSubClient([int]$timeoutMilliseconds = 5000) {
    if ($script:m10PubSubClientQueue.Count -gt 0) { return $script:m10PubSubClientQueue.Dequeue() }
    return $null
}
function Invoke-Redis([string[]]$arguments, [int]$timeoutMilliseconds = 5000) {
    $script:m10PubSubRedisCalls.Add(($arguments -join ' '))
    $script:m10PubSubRedisTimeouts.Add($timeoutMilliseconds)
    if ($script:m10PubSubRedisResultQueue.Count -gt 0) { return $script:m10PubSubRedisResultQueue.Dequeue() }
    return '1'
}
function Save-Result { }

$script:m10PubSubClientQueue.Enqueue([ordered]@{ id = 14; localPort = 62863; channels = 2 })
$script:m10PubSubClientQueue.Enqueue($null)
$script:m10PubSubRedisResultQueue.Enqueue('0')
$result = Wait-BPubSub $false 2 'test-reconnect-after-kill'
if ($null -ne $result -or $script:m10PubSubRedisCalls.Count -ne 1 -or
    [long]$script:manifest.pubsubDisconnectAttempts[0].redisClientId -ne 14 -or
    [string]$script:manifest.pubsubDisconnectAttempts[0].redisResult -ne '0' -or
    $script:m10PubSubRedisTimeouts[0] -gt 2000) {
    throw 'M10 disconnect waiter did not tolerate a reconnect race that disappeared before CLIENT KILL.'
}

$script:m10PubSubClientQueue = [System.Collections.Queue]::new()
$script:m10PubSubRedisResultQueue = [System.Collections.Queue]::new()
$script:m10PubSubRedisCalls = [System.Collections.Generic.List[string]]::new()
$script:m10PubSubRedisTimeouts = [System.Collections.Generic.List[int]]::new()
$script:manifest = [ordered]@{ pubsubDisconnectAttempts = [System.Collections.Generic.List[object]]::new() }
$script:m10PubSubClientQueue.Enqueue([ordered]@{ id = 24; localPort = 62913; channels = 2 })
$script:m10PubSubClientQueue.Enqueue($null)
$script:m10PubSubRedisResultQueue.Enqueue('0')
$result = Disconnect-BPubSub 'test-disconnect-race'
if (-not $result -or $script:m10PubSubRedisCalls.Count -ne 1 -or
    [string]$script:manifest.pubsubDisconnectAttempts[0].reason -ne 'test-disconnect-race') {
    throw 'M10 disconnect wrapper treated a Redis CLIENT KILL disappearance race as an unidentified subscriber.'
}

$script:m10PubSubClientQueue = [System.Collections.Queue]::new()
$script:m10PubSubRedisResultQueue = [System.Collections.Queue]::new()
$script:m10PubSubRedisCalls = [System.Collections.Generic.List[string]]::new()
$script:m10PubSubRedisTimeouts = [System.Collections.Generic.List[int]]::new()
$script:manifest = [ordered]@{ pubsubDisconnectAttempts = [System.Collections.Generic.List[object]]::new() }
$script:m10PubSubClientQueue.Enqueue([ordered]@{ id = 25; localPort = 62914; channels = 2 })
$script:m10PubSubClientQueue.Enqueue([ordered]@{ id = 26; localPort = 62915; channels = 2 })
$script:m10PubSubClientQueue.Enqueue($null)
$result = Wait-BPubSub $false 2 'test-auto-reconnect'
if ($null -ne $result -or $script:m10PubSubRedisCalls.Count -ne 2 -or
    [long]$script:manifest.pubsubDisconnectAttempts[0].redisClientId -ne 25 -or
    [long]$script:manifest.pubsubDisconnectAttempts[1].redisClientId -ne 26 -or
    @($script:m10PubSubRedisTimeouts | Where-Object { $_ -gt 2000 -or $_ -lt 1 }).Count -ne 0 -or
    @($script:manifest.pubsubDisconnectAttempts | Where-Object { $_.reason -ne 'test-auto-reconnect' }).Count -ne 0) {
    throw 'M10 disconnect waiter did not suppress and record a replacement Pub/Sub subscriber.'
}

$script:m10MemoryQueue = [System.Collections.Queue]::new()
function Snapshot-Memory([string]$nodeName) { }
function Read-MemoryMarker([string]$nodeName) { return $script:m10MemoryQueue.Dequeue() }
$priorMemory = [ordered]@{ uuid = '1ef97e85-b60f-3c2d-8fca-45edad0b7102'; balance = '1.00'; version = '1' }
$script:m10MemoryQueue.Enqueue([ordered]@{ requestId = 'sample-1'; uuid = $priorMemory.uuid; balance = '1.00'; version = '1'; observedAtUtc = 'first' })
$script:m10MemoryQueue.Enqueue([ordered]@{ requestId = 'sample-2'; uuid = $priorMemory.uuid; balance = '2.00'; version = '2'; observedAtUtc = 'second' })
$observation = Observe-BMemoryAfterReconnect $priorMemory '2.00' '2'
if ($observation.result -ne 'MESSAGE_APPLIED_DURING_ATTEMPT' -or $observation.observations.Count -ne 2 -or
    $observation.memory.version -ne '2') {
    throw 'M10 post-reconnect observer did not classify a delivered update from repeated memory samples.'
}

$script:m10MemoryQueue = [System.Collections.Queue]::new()
foreach ($sample in 1..3) {
    $script:m10MemoryQueue.Enqueue([ordered]@{ requestId = "old-$sample"; uuid = $priorMemory.uuid; balance = '1.00'; version = '1'; observedAtUtc = "old-$sample" })
}
$observation = Observe-BMemoryAfterReconnect $priorMemory '2.00' '2'
if ($observation.result -ne 'MESSAGE_MISSED_NO_REPLAY' -or $observation.observations.Count -ne 3 -or
    @($observation.observations | Where-Object { $_.balance -ne $priorMemory.balance -or $_.version -ne $priorMemory.version }).Count -ne 0) {
    throw 'M10 post-reconnect observer classified a missed update without three stable old-state samples.'
}

'M10 endpoint matrix, scheduler ownership, provenance, Redis cleanup, bounded CLI, reconnect, and repeated-memory tests passed.'
