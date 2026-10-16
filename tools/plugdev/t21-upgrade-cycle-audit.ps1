param(
    [Parameter(Mandatory = $true)][string]$RunDirectory,
    [Parameter(Mandatory = $true)][string]$PlugDevRoot,
    [Alias('PaperEndpoint')]
    [ValidateSet('Minimum', 'Maximum', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')]
    [string]$PlatformEndpoint = 'Minimum'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$script:assertionCount = 0

function Assert-True([string]$name, [bool]$condition) {
    $script:assertionCount++
    if (-not $condition) { throw "FAIL: $name" }
}

function Assert-Equal([string]$name, $expected, $actual) {
    $script:assertionCount++
    if ($expected -is [bool]) {
        if ($actual -isnot [bool] -or $actual -ne $expected) {
            throw "FAIL: $name (expected '$expected', observed '$actual')"
        }
        return
    }
    if ($null -eq $expected) {
        if ($null -ne $actual) { throw "FAIL: $name (expected null, observed '$actual')" }
        return
    }
    if ([string]$expected -cne [string]$actual) {
        throw "FAIL: $name (expected '$expected', observed '$actual')"
    }
}

function Get-Field($inputObject, [string]$name) {
    if ($null -eq $inputObject) { return $null }
    if ($inputObject -is [Collections.IDictionary]) {
        if ($inputObject.Contains($name)) { return $inputObject[$name] }
        return $null
    }
    $property = $inputObject.PSObject.Properties[$name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Assert-NoReparsePointAncestors([string]$path) {
    $current = [IO.Path]::GetFullPath($path)
    while (-not [string]::IsNullOrWhiteSpace($current)) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Refusing an evidence path whose ancestor is a reparse point: $current"
            }
        }
        $parent = Split-Path -Parent $current
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $current) { break }
        $current = $parent
    }
}

function Resolve-RunRelativePath([string]$relativePath) {
    if ([string]::IsNullOrWhiteSpace($relativePath) -or [IO.Path]::IsPathRooted($relativePath) -or
        $relativePath -match '(^|[\\/])\.\.([\\/]|$)') {
        throw "Unsafe run-relative evidence path: '$relativePath'."
    }
    $normalized = $relativePath.Replace('/', '\')
    $fullPath = [IO.Path]::GetFullPath((Join-Path $runRoot $normalized))
    if (-not $fullPath.StartsWith($runRootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Evidence path escapes the run directory: '$relativePath'."
    }
    Assert-NoReparsePointAncestors $fullPath
    return $fullPath
}

function Get-ManifestSha256([object[]]$manifest) {
    $json = ConvertTo-Json -InputObject @($manifest) -Depth 5 -Compress
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $digest = $algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($json))
        return [BitConverter]::ToString($digest).Replace('-', '').ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
    }
}

function Get-PlugDevDistManifest([string]$plugDevPath) {
    $distRoot = Join-Path $plugDevPath 'packages\cli\dist'
    if (-not (Test-Path -LiteralPath $distRoot -PathType Container)) {
        throw "Local PlugDev CLI distribution directory not found: $distRoot"
    }
    Assert-NoReparsePointAncestors $distRoot
    $files = @(Get-ChildItem -LiteralPath $distRoot -File -Recurse -Force | Sort-Object FullName)
    if ($files.Count -eq 0) { throw "Local PlugDev CLI distribution is empty: $distRoot" }
    return @($files | ForEach-Object {
        Assert-NoReparsePointAncestors $_.FullName
        [ordered]@{
            path = $_.FullName.Substring($distRoot.Length + 1).Replace('\', '/')
            bytes = [long]$_.Length
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    })
}

function Invoke-SqliteRead([string]$sqliteExecutable, [string]$databasePath, [string]$query) {
    $output = @(& $sqliteExecutable -batch -noheader -separator '|' -readonly $databasePath $query 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw "sqlite3 read-only query exited ${exitCode}: $($output -join ' ')" }
    return @($output | ForEach-Object { $_.ToString() })
}

function Assert-LogMarker([string]$relativePath, [string]$marker) {
    $path = Resolve-RunRelativePath (Join-Path 'logs' $relativePath)
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required M04 runtime log is missing: $relativePath" }
    $contents = Get-Content -LiteralPath $path -Raw
    Assert-True "runtime marker '$marker' in $relativePath" $contents.Contains($marker)
}

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$reportsRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'build\reports\foundation\T21-upgrade-cycles'))
$runRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $RunDirectory).Path)
$reportsPrefix = $reportsRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $runRoot.StartsWith($reportsPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to audit a run outside the T21 upgrade-cycle report directory: $runRoot"
}
$runRootPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
Assert-NoReparsePointAncestors $runRoot
$resultPath = Join-Path $runRoot 'result.json'
if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) { throw "Runtime report not found: $resultPath" }
$report = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json

Assert-Equal 'report run directory binding' $runRoot ([IO.Path]::GetFullPath([string]$report.runDirectory))
Assert-Equal 'M04 report status' 'PASS' ([string]$report.status)
Assert-Equal 'M04 report task' 'T21 M04 LOCAL SQLite upgrade and normal-drain rollback cycle' ([string]$report.task)
Assert-Equal '1.3.2 baseline JAR pin' '442a404276b538ce807b0f063fb37920be85e60961a52a475b3f3524d08f4f10' ([string]$report.expectedBaselinePluginSha256)
Assert-Equal '1.3.2 baseline source commit pin' '71cc55dfeefe9a8f40a3835b051ddd324d353767' ([string]$report.baselineSourceCommit)
Assert-Equal 'VaultUnlocked dependency version' '2.20.2' ([string]$report.vaultUnlockedDependencyVersion)
Assert-Equal 'runtime profile mode' 'local' ([string]$report.runtimeProfile.mode)
Assert-Equal 'runtime guard profile' 'guard-on' ([string]$report.runtimeProfile.guardProfile)
Assert-Equal 'runtime Redis disabled' $false ([bool]$report.runtimeProfile.redisEnabled)
Assert-Equal 'runtime shared SQL disabled' $false ([bool]$report.runtimeProfile.sharedSqlEnabled)
Assert-Equal 'runtime optional modules disabled' $false ([bool]$report.runtimeProfile.optionalModulesEnabled)
Assert-Equal 'runtime per-player protection enabled' $true ([bool]$report.runtimeProfile.playerProtectionEnabled)
Assert-Equal 'runtime global lock disabled' $false ([bool]$report.runtimeProfile.globalLockEnabled)
Assert-Equal 'runtime failure absent' $null $report.failure
Assert-Equal 'runtime cleanup failure absent' $null $report.cleanupFailure
Assert-Equal 'isolated Gradle daemon exit' '0' ([string]$report.gradleDaemonStopExitCode)

$artifactManifest = @($report.artifactManifest)
$expectedArtifactNames = @('server', 'candidate', 'baseline', 'acceptance', 'api-consumer', 'acceptance-runtime', 'api-consumer-runtime', 'vault-unlocked-cache', 'vault-unlocked-runtime')
Assert-Equal 'artifact manifest entry count' ([string]$expectedArtifactNames.Count) ([string]$artifactManifest.Count)
$artifactByName = @{}
foreach ($entry in $artifactManifest) {
    $name = [string](Get-Field $entry 'name')
    if ($name -notin $expectedArtifactNames) { throw "Unexpected staged artifact manifest entry: '$name'." }
    if ($artifactByName.ContainsKey($name)) { throw "Duplicate staged artifact manifest entry: '$name'." }
    $artifactByName[$name] = $entry
    $relativePath = [string](Get-Field $entry 'path')
    $path = Resolve-RunRelativePath $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Staged artifact is missing: $relativePath" }
    $file = Get-Item -LiteralPath $path
    $actualSha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-True "staged artifact SHA-256 $name" ($actualSha256 -ceq ([string](Get-Field $entry 'sha256')).ToLowerInvariant())
    Assert-Equal "staged artifact byte count $name" ([long](Get-Field $entry 'bytes')) ([long]$file.Length)
}
foreach ($name in $expectedArtifactNames) {
    if (-not $artifactByName.ContainsKey($name)) { throw "Staged artifact manifest is missing '$name'." }
    $entry = $artifactByName[$name]
    $reportField = switch ($name) {
        'server' { 'serverSha256' }
        'candidate' { 'candidatePluginSha256' }
        'baseline' { 'baselinePluginSha256' }
        'acceptance' { 'acceptanceJarSha256' }
        'api-consumer' { 'apiConsumerJarSha256' }
        'acceptance-runtime' { 'acceptanceJarSha256' }
        'api-consumer-runtime' { 'apiConsumerJarSha256' }
        'vault-unlocked-cache' { 'vaultUnlockedPluginSha256' }
        'vault-unlocked-runtime' { 'vaultUnlockedPluginSha256' }
    }
    Assert-Equal "report-to-staged-artifact pin $name" ([string](Get-Field $entry 'sha256')) ([string](Get-Field $report $reportField))
}
Assert-Equal 'report server filename' ([IO.Path]::GetFileName([string]$artifactByName.server.path)) ([string]$report.serverArtifact)
Assert-Equal 'report candidate staged byte count' ([long]$artifactByName.candidate.bytes) ([long]$report.candidatePluginBytes)
Assert-Equal 'report VaultUnlocked staged byte count' ([long]$artifactByName.'vault-unlocked-runtime'.bytes) ([long]$report.vaultUnlockedPluginBytes)
Assert-Equal 'Acceptance staged build artifact path' 'build/acceptance/SyncmoneyAcceptance.jar' ([string]$artifactByName.acceptance.path)
Assert-Equal 'API consumer staged build artifact path' 'build/acceptance/SyncmoneyApiConsumer.jar' ([string]$artifactByName.'api-consumer'.path)
Assert-Equal 'Acceptance runtime artifact path' '.plugdev/run/plugins/SyncmoneyAcceptance.jar' ([string]$artifactByName.'acceptance-runtime'.path)
Assert-Equal 'API consumer runtime artifact path' '.plugdev/run/plugins/SyncmoneyApiConsumer.jar' ([string]$artifactByName.'api-consumer-runtime'.path)
Assert-Equal 'VaultUnlocked cache and runtime hashes match' ([string]$artifactByName.'vault-unlocked-cache'.sha256) ([string]$artifactByName.'vault-unlocked-runtime'.sha256)
Assert-Equal 'VaultUnlocked cache artifact path' '.plugdev-home/deps/TNE-VaultUnlocked-2.20.2.jar' ([string]$artifactByName.'vault-unlocked-cache'.path)
Assert-Equal 'VaultUnlocked loaded artifact path' '.plugdev/run/plugins/TNE-VaultUnlocked-2.20.2.jar' ([string]$artifactByName.'vault-unlocked-runtime'.path)
Assert-Equal 'expected server hash matches staged JAR' ([string]$report.expectedServerSha256) ([string]$report.serverSha256)
Assert-Equal 'expected baseline hash matches staged JAR' ([string]$report.expectedBaselinePluginSha256) ([string]$report.baselinePluginSha256)

$sourcePins = $report.sourcePins
$validationSourcePath = Join-Path $repoRoot 'tools\plugdev\t21-upgrade-validation.ps1'
Assert-True 'shared validation source exists' (Test-Path -LiteralPath $validationSourcePath -PathType Leaf)
$validationSourceSha256 = (Get-FileHash -LiteralPath $validationSourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
Assert-Equal 'shared validation source SHA-256' ([string]$sourcePins.validationSha256) $validationSourceSha256
. $validationSourcePath
Assert-True "exact $PlatformEndpoint platform endpoint pin" (Test-T21M04PlatformEndpointReport $report -PlatformEndpoint $PlatformEndpoint)
foreach ($stage in @('baseline-1.3.2', 'upgrade-1.4', 'rollback-1.3.2')) {
    $startupLogPath = Resolve-RunRelativePath (Join-Path 'logs' "$stage-start.txt")
    if (-not (Test-Path -LiteralPath $startupLogPath -PathType Leaf)) {
        throw "Required M04 server startup log is missing: $stage-start.txt"
    }
    $startupLogText = Get-Content -LiteralPath $startupLogPath -Raw
    Assert-True "observed $PlatformEndpoint identity in $stage startup log" (
        Test-T21M04PlatformStartupIdentity $startupLogText -PlatformEndpoint $PlatformEndpoint
    )
}
Assert-True 'Acceptance runtime copy matches staged build artifact' (Test-T21M04RuntimeArtifactCopy $artifactByName.acceptance $artifactByName.'acceptance-runtime' 'acceptance' 'build/acceptance/SyncmoneyAcceptance.jar' 'acceptance-runtime' '.plugdev/run/plugins/SyncmoneyAcceptance.jar')
Assert-True 'API consumer runtime copy matches staged build artifact' (Test-T21M04RuntimeArtifactCopy $artifactByName.'api-consumer' $artifactByName.'api-consumer-runtime' 'api-consumer' 'build/acceptance/SyncmoneyApiConsumer.jar' 'api-consumer-runtime' '.plugdev/run/plugins/SyncmoneyApiConsumer.jar')
$plugDevPath = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $PlugDevRoot).Path)
Assert-Equal 'PlugDev root path pin' $plugDevPath ([IO.Path]::GetFullPath([string]$sourcePins.plugDevRoot))
$plugDevStatus = @(& git -C $plugDevPath status --porcelain=v1 --untracked-files=all 2>$null)
if ($LASTEXITCODE -ne 0) { throw 'Could not independently inspect the PlugDev working tree.' }
Assert-Equal 'PlugDev checkout clean' '0' ([string]$plugDevStatus.Count)
Assert-Equal 'PlugDev commit pin' ([string]$sourcePins.plugDevCommit) ((& git -C $plugDevPath rev-parse HEAD 2>$null | Out-String).Trim())
$plugDevDistManifest = @(Get-PlugDevDistManifest $plugDevPath)
$pinnedPlugDevDistManifest = @($sourcePins.plugDevDistManifest)
Assert-Equal 'PlugDev distribution manifest entry count' ([string]$pinnedPlugDevDistManifest.Count) ([string]$plugDevDistManifest.Count)
for ($index = 0; $index -lt $plugDevDistManifest.Count; $index++) {
    $actualEntry = $plugDevDistManifest[$index]
    $pinnedEntry = $pinnedPlugDevDistManifest[$index]
    Assert-Equal "PlugDev distribution path $index" ([string]$pinnedEntry.path) ([string]$actualEntry.path)
    Assert-Equal "PlugDev distribution byte count $index" ([long]$pinnedEntry.bytes) ([long]$actualEntry.bytes)
    Assert-Equal "PlugDev distribution SHA-256 $index" ([string]$pinnedEntry.sha256) ([string]$actualEntry.sha256)
}
$plugDevDistSha256 = Get-ManifestSha256 $plugDevDistManifest
Assert-Equal 'PlugDev distribution manifest digest' ([string]$sourcePins.plugDevDistSha256) $plugDevDistSha256
$plugDevCliEntry = @($plugDevDistManifest | Where-Object path -ceq 'cli.js')
Assert-Equal 'PlugDev CLI distribution entry count' '1' ([string]$plugDevCliEntry.Count)
Assert-Equal 'PlugDev CLI SHA-256 pin' ([string]$sourcePins.plugDevCliSha256) ([string]$plugDevCliEntry[0].sha256)

foreach ($sourcePin in @(
    [ordered]@{ name = 'runner'; path = Join-Path $repoRoot 'tools\plugdev\run-upgrade-cycle.ps1'; hash = [string]$sourcePins.runnerSha256 },
    [ordered]@{ name = 'prepare'; path = Join-Path $repoRoot 'tools\plugdev\prepare.mjs'; hash = [string]$sourcePins.prepareSha256 },
    [ordered]@{ name = 'prepare test'; path = Join-Path $repoRoot 'tools\plugdev\prepare.test.mjs'; hash = [string]$sourcePins.prepareTestSha256 },
    [ordered]@{ name = 'shared validation'; path = Join-Path $repoRoot 'tools\plugdev\t21-upgrade-validation.ps1'; hash = [string]$sourcePins.validationSha256 },
    [ordered]@{ name = 'offline auditor'; path = Join-Path $repoRoot 'tools\plugdev\t21-upgrade-cycle-audit.ps1'; hash = [string]$sourcePins.offlineAuditorSha256 }
)) {
    Assert-True "$($sourcePin.name) source exists" (Test-Path -LiteralPath $sourcePin.path -PathType Leaf)
    $sourceHash = (Get-FileHash -LiteralPath $sourcePin.path -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Equal "$($sourcePin.name) source SHA-256" ([string]$sourcePin.hash) $sourceHash
}
$stagedPreparePath = Resolve-RunRelativePath 'tools/plugdev/prepare.mjs'
$stagedPrepareSha256 = (Get-FileHash -LiteralPath $stagedPreparePath -Algorithm SHA256).Hash.ToLowerInvariant()
Assert-Equal 'staged prepare source SHA-256' ([string]$sourcePins.prepareStagedSha256) $stagedPrepareSha256
Assert-Equal 'staged prepare matches source' ([string]$sourcePins.prepareSha256) $stagedPrepareSha256

$configObservations = @($report.configObservations)
Assert-Equal 'config observation count' '6' ([string]$configObservations.Count)
$expectedProfile = [ordered]@{
    mode = 'local'
    redisEnabled = $false
    databaseEnabled = $false
    dbEnabled = $false
    pubsubEnabled = $false
    circuitBreakerEnabled = $false
    playerProtectionEnabled = $true
    globalLockEnabled = $false
    shadowSyncEnabled = $false
    auditEnabled = $false
    auditRedisEnabled = $false
    auditCleanupEnabled = $false
    auditExportEnabled = $false
    transferGuardEnabled = $false
    webAdminEnabled = $false
    discordWebhookEnabled = $false
}
foreach ($observation in $configObservations) {
    $stage = [string]$observation.stage
    Assert-Equal "config schema at $stage" '12' ([string]$observation.configSchema)
    Assert-Equal "SQLite config path at $stage" 'plugins/Syncmoney/t21-data/legacy-local.db' ([string]$observation.sqliteConfigPath)
    Assert-Equal "operator config fixture at $stage" $true ([bool]$observation.operatorFixturePreserved)
    Assert-Equal "guard profile at $stage" 'guard-on' ([string]$observation.guardProfile)
    foreach ($field in $expectedProfile.Keys) {
        Assert-Equal "profile field $field at $stage" $expectedProfile[$field] (Get-Field $observation.observedProfile $field)
    }
}

$stageRecords = @($report.stageRecords)
$startRecords = @($stageRecords | Where-Object { $null -ne (Get-Field $_ 'startExitCode') })
$stopRecords = @($stageRecords | Where-Object { $null -ne (Get-Field $_ 'stopExitCode') })
$expectedStartRecords = @(
    [ordered]@{ stage = 'baseline-1.3.2'; pluginVersion = '1.3.2'; apiClassLoader = 'NOT_APPLICABLE' },
    [ordered]@{ stage = 'upgrade-1.4'; pluginVersion = [string]$report.candidatePlugin; apiClassLoader = 'PASS' },
    [ordered]@{ stage = 'rollback-1.3.2'; pluginVersion = '1.3.2'; apiClassLoader = 'NOT_APPLICABLE' }
)
$expectedLifecycleStages = @('baseline-1.3.2', 'upgrade-1.4', 'rollback-1.3.2')
Assert-Equal 'complete stage record count' '6' ([string]$stageRecords.Count)
Assert-Equal 'normal start record count' '3' ([string]$startRecords.Count)
Assert-Equal 'normal stop record count' '3' ([string]$stopRecords.Count)
Assert-True 'baseline, upgrade, and rollback stage chronology' (Test-T21M04StageRecordChronology $stageRecords)
$invalidStageRecords = @($stageRecords | Where-Object {
    $hasStartExit = $null -ne (Get-Field $_ 'startExitCode')
    $hasStopExit = $null -ne (Get-Field $_ 'stopExitCode')
    $hasStartExit -eq $hasStopExit
})
Assert-Equal 'invalid stage record shape count' '0' ([string]$invalidStageRecords.Count)
foreach ($expectedStart in $expectedStartRecords) {
    $matchingStartRecords = @($startRecords | Where-Object { [string]$_.stage -ceq [string]$expectedStart.stage })
    Assert-Equal "start record count for $($expectedStart.stage)" '1' ([string]$matchingStartRecords.Count)
    Assert-Equal "start plugin version for $($expectedStart.stage)" ([string]$expectedStart.pluginVersion) ([string]$matchingStartRecords[0].pluginVersion)
    Assert-Equal "API consumer marker for $($expectedStart.stage)" ([string]$expectedStart.apiClassLoader) ([string](Get-Field $matchingStartRecords[0] 'apiClassLoader'))
}
foreach ($stage in $expectedLifecycleStages) {
    $matchingStopRecords = @($stopRecords | Where-Object { [string]$_.stage -ceq $stage })
    Assert-Equal "stop record count for $stage" '1' ([string]$matchingStopRecords.Count)
}
Assert-Equal 'all stage starts exited successfully' '0' ([string](@($startRecords | Where-Object { [int]$_.startExitCode -ne 0 }).Count))
Assert-Equal 'all stage stops exited successfully' '0' ([string](@($stopRecords | Where-Object { [int]$_.stopExitCode -ne 0 }).Count))
Assert-Equal 'all stage stops verified process exit' '0' ([string](@($stopRecords | Where-Object { -not [bool]$_.processExitVerified }).Count))
Assert-Equal 'no forced stage cleanup' '0' ([string](@($stopRecords | Where-Object { [bool]$_.forcedCleanup }).Count))
$upgradeStartRecords = @($startRecords | Where-Object { [string]$_.stage -ceq 'upgrade-1.4' -and [string]$_.pluginVersion -ceq [string]$report.candidatePlugin })
Assert-Equal 'upgrade runtime start record count' '1' ([string]$upgradeStartRecords.Count)
Assert-Equal 'candidate API consumer classloader marker' 'PASS' ([string]$upgradeStartRecords[0].apiClassLoader)
Assert-LogMarker 'baseline-1.3.2-server.log' 'T21_LEGACY_SQLITE_PASS operation=read expected-a=100.00 expected-b=0.00'
Assert-LogMarker 'upgrade-1.4-server.log' 'T21_LEGACY_SQLITE_PASS operation=read expected-a=100.00 expected-b=0.00'
Assert-LogMarker 'upgrade-1.4-server.log' 'T21_LEGACY_SQLITE_PASS operation=write expected-a=1337.25 expected-b=765.50'
Assert-LogMarker 'upgrade-1.4-server.log' 'API CLASSLOADER PASS provider=Syncmoney'
Assert-LogMarker 'rollback-1.3.2-server.log' 'T21_LEGACY_SQLITE_PASS operation=read expected-a=1337.25 expected-b=765.50'
foreach ($stage in $expectedLifecycleStages) {
    $stageLogPath = Join-Path (Join-Path $runRoot 'logs') "$stage-server.log"
    $vaultLoadMarkers = [regex]::Matches((Get-Content -LiteralPath $stageLogPath -Raw), 'Vault \(2\.20\.2\)')
    Assert-Equal "VaultUnlocked 2.20.2 loaded marker for $stage" '1' ([string]$vaultLoadMarkers.Count)
}

$sqlite3Command = Get-Command sqlite3.exe, sqlite3 -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $sqlite3Command -or $sqlite3Command.CommandType -ne 'Application') { throw 'sqlite3 executable is required for the independent M04 state audit.' }
$expectedDatabaseRelativePath = '.plugdev\run\plugins\Syncmoney\t21-data\legacy-local.db'
Assert-Equal 'report SQLite path matches M04 runtime fixture' $expectedDatabaseRelativePath ([string]$report.sqlitePath)
$databasePath = Resolve-RunRelativePath $expectedDatabaseRelativePath
Assert-True 'historical LOCAL SQLite database exists' (Test-Path -LiteralPath $databasePath -PathType Leaf)
$balanceRows = @(Invoke-SqliteRead $sqlite3Command.Source $databasePath "SELECT uuid || '|' || name || '|' || typeof(balance) || '|' || printf('%.2f', balance) FROM player_balances WHERE uuid IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002') ORDER BY uuid;")
Assert-Equal 'final SQLite account balances' "00000000-0000-0000-0000-000000000001|fixture-a|real|1337.25`n00000000-0000-0000-0000-000000000002|fixture-b|real|765.50" ($balanceRows -join "`n")
$realBalanceColumn = @(Invoke-SqliteRead $sqlite3Command.Source $databasePath "SELECT COUNT(*) FROM pragma_table_info('player_balances') WHERE lower(name)='balance' AND upper(type)='REAL';")
Assert-Equal 'historical REAL balance column preserved' '1' ([string]$realBalanceColumn[0])
$historicalTransaction = @(Invoke-SqliteRead $sqlite3Command.Source $databasePath "SELECT id || '|' || uuid || '|' || name || '|' || type || '|' || typeof(amount) || '|' || printf('%.2f', amount) || '|' || typeof(balance_before) || '|' || printf('%.2f', balance_before) || '|' || typeof(balance_after) || '|' || printf('%.2f', balance_after) || '|' || source || '|' || timestamp || '|' || sequence FROM transactions WHERE id=1;")
Assert-Equal 'historical transaction row preserved' '1|00000000-0000-0000-0000-000000000001|fixture-a|DEPOSIT|real|100.00|real|0.00|real|100.00|TEST|1700000000000|1' ([string]$historicalTransaction[0])
$recoveryJournals = @(Get-ChildItem -LiteralPath $runRoot -File -Recurse -Filter 'recovery-v1.jsonl' -Force)
Assert-Equal 'no LOCAL recovery journal remains' '0' ([string]$recoveryJournals.Count)

$logsRoot = Join-Path $runRoot 'logs'
$logManifest = @($report.logManifest)
$manifestedLogPaths = @{}
foreach ($log in $logManifest) {
    $relativePath = [string]$log.path
    if ($manifestedLogPaths.ContainsKey($relativePath)) { throw "Duplicate runner log manifest path: '$relativePath'." }
    $manifestedLogPaths[$relativePath] = $true
    $path = Resolve-RunRelativePath (Join-Path 'logs' $relativePath)
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Manifest-listed runner log is missing: $relativePath" }
    $file = Get-Item -LiteralPath $path
    $sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Equal "runner log byte count $relativePath" ([long]$log.bytes) ([long]$file.Length)
    Assert-Equal "runner log SHA-256 $relativePath" ([string]$log.sha256) $sha256
}
$actualLogs = @(Get-ChildItem -LiteralPath $logsRoot -File -Recurse -Force | Sort-Object FullName)
Assert-Equal 'retained runner log count' ([string]$logManifest.Count) ([string]$actualLogs.Count)
foreach ($log in $actualLogs) {
    Assert-NoReparsePointAncestors $log.FullName
    $relativePath = $log.FullName.Substring($logsRoot.Length + 1).Replace('\', '/')
    Assert-True "runner log is manifested $relativePath" $manifestedLogPaths.ContainsKey($relativePath)
}

Write-Output ("T21 M04 offline audit PASS: {0} assertions; platform endpoint {1}; report {2}" -f $script:assertionCount, $PlatformEndpoint, $resultPath)
