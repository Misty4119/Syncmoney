param(
    [Parameter(Mandatory = $true)][string]$RunDirectory,
    [Parameter(Mandatory = $true)][string]$PlugDevRoot,
    [Alias('PaperEndpoint')]
    [ValidateSet('Minimum', 'Maximum', 'CurrentMaximum', 'CurrentStable', 'CurrentStable209', 'MinimumFolia', 'MaximumFolia', 'CurrentMaximumFolia', 'MinimumCanvas', 'MaximumCanvas', 'CurrentMaximumCanvas')]
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

function Assert-T21StartupRecoveryChronology([string]$LogText, [string]$RecoveryMarker,
        [string]$EnabledMarker, [string]$FirstReplayReadMarker) {
    $markers = @($RecoveryMarker, $EnabledMarker, $FirstReplayReadMarker)
    $positions = [System.Collections.Generic.List[int]]::new()
    foreach ($marker in $markers) {
        $first = $LogText.IndexOf($marker, [StringComparison]::Ordinal)
        $last = $LogText.LastIndexOf($marker, [StringComparison]::Ordinal)
        if ($first -lt 0) { throw "FAIL: startup recovery chronology is missing marker '$marker'." }
        if ($first -ne $last) { throw "FAIL: startup recovery chronology marker must occur exactly once: '$marker'." }
        $positions.Add($first)
    }
    if (-not ($positions[0] -lt $positions[1] -and $positions[1] -lt $positions[2])) {
        throw 'FAIL: startup recovery chronology requires journal replay before plugin readiness and the first replay read.'
    }
}

function Assert-T21ReplayReceiptBinding($Observation, [string]$ExpectedRow, [object[]]$SqliteRows) {
    $reportedRows = @($Observation.transactionRows)
    if ($reportedRows.Count -ne 1) {
        throw "FAIL: reported receipt row count must be 1, observed $($reportedRows.Count)."
    }
    if ([string]$reportedRows[0] -cne $ExpectedRow) {
        throw 'FAIL: reported receipt differs from the expected replay transaction.'
    }
    if ($SqliteRows.Count -ne 1) {
        throw "FAIL: SQLite receipt row count must be 1, observed $($SqliteRows.Count)."
    }
    if ([string]$SqliteRows[0] -cne $ExpectedRow) {
        throw 'FAIL: SQLite receipt differs from the expected replay transaction.'
    }
    if ([string]$reportedRows[0] -cne [string]$SqliteRows[0]) {
        throw 'FAIL: reported receipt differs from the independently queried SQLite receipt.'
    }
}

function Assert-T21AbsentPreReplayReceipt($ReportedReceipt, [object[]]$SqliteRows) {
    if ($null -ne $ReportedReceipt -and -not [string]::IsNullOrEmpty([string]$ReportedReceipt)) {
        throw 'FAIL: seed report contains a pre-replay receipt for the absent-receipt scenario.'
    }
    if ($SqliteRows.Count -ne 0) {
        throw "FAIL: SQLite contains a pre-replay receipt for the absent-receipt scenario; row count=$($SqliteRows.Count)."
    }
}

function Assert-T21SourcePin([string]$Name, [string]$ExpectedSha256, [string]$ActualSha256) {
    if ($ExpectedSha256 -cne $ActualSha256) {
        throw "FAIL: $Name source SHA-256 pin does not match the source used for this audit. Historical predicate normalization does not establish full-report provenance."
    }
}

function Resolve-T21RecoveryScenario($Report) {
    $recoveryScenario = [string](Get-Field $Report 'recoveryScenario')
    if ([string]::IsNullOrWhiteSpace($recoveryScenario)) { return 'normal-drain' }
    if ($recoveryScenario -notin @('normal-drain', 'seeded-journal-replay', 'seeded-journal-replay-no-receipt', 'shutdown-timeout-handoff')) {
        throw "Unknown M04 recovery scenario: '$recoveryScenario'."
    }
    return $recoveryScenario
}

function Assert-T21RecoveryTask([string]$RecoveryScenario, [string]$Task) {
    $expectedTasks = switch ($RecoveryScenario) {
        'seeded-journal-replay' {
            @(
                'T21 M04 LOCAL SQLite seeded journal replay and rollback cycle',
                'T21 M04 LOCAL SQLite seeded duplicate-receipt journal replay and rollback cycle'
            )
        }
        'seeded-journal-replay-no-receipt' { @('T21 M04 LOCAL SQLite seeded missing-receipt journal replay and rollback cycle') }
        'shutdown-timeout-handoff' { @('T21 M04 LOCAL SQLite shutdown-timeout journal handoff and replay cycle') }
        'normal-drain' { @('T21 M04 LOCAL SQLite upgrade and normal-drain rollback cycle') }
        default { throw "Unknown M04 recovery scenario: '$RecoveryScenario'." }
    }
    if ($Task -cnotin $expectedTasks) {
        throw "FAIL: M04 report task '$Task' does not match recovery scenario '$RecoveryScenario'."
    }
}

function Get-T21AuditSha256([byte[]]$Bytes) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString($algorithm.ComputeHash($Bytes)).Replace('-', '').ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
    }
}

function ConvertFrom-T21AuditBase64Url([string]$Value) {
    if ($Value -notmatch '^[A-Za-z0-9_-]*$') { throw 'FAIL: retained LOCAL journal has an invalid base64url field.' }
    $standard = $Value.Replace('-', '+').Replace('_', '/')
    switch ($standard.Length % 4) {
        1 { throw 'FAIL: retained LOCAL journal has an invalid base64url field length.' }
        2 { $standard += '==' }
        3 { $standard += '=' }
    }
    try {
        return [Text.UTF8Encoding]::new($false, $true).GetString([Convert]::FromBase64String($standard))
    } catch {
        throw "FAIL: retained LOCAL journal has invalid UTF-8 base64url data: $($_.Exception.Message)"
    }
}

function Read-T21AuditLocalJournal([string]$Path) {
    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -eq 0) { throw 'FAIL: retained LOCAL shutdown journal is empty.' }
    try {
        $text = [Text.UTF8Encoding]::new($false, $true).GetString($bytes)
    } catch {
        throw "FAIL: retained LOCAL shutdown journal is not valid UTF-8: $($_.Exception.Message)"
    }
    if (-not $text.EndsWith("`n", [StringComparison]::Ordinal) -or $text.Contains("`r")) {
        throw 'FAIL: retained LOCAL shutdown journal must contain complete LF-terminated records.'
    }
    $lines = $text.Split([char[]]@("`n"), [StringSplitOptions]::None)
    if ($lines.Length -lt 2 -or $lines[$lines.Length - 1] -cne '') {
        throw 'FAIL: retained LOCAL shutdown journal has invalid final framing.'
    }

    $records = [System.Collections.Generic.List[object]]::new()
    $requestIds = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    for ($index = 0; $index -lt $lines.Length - 1; $index++) {
        $line = $lines[$index]
        if ([string]::IsNullOrWhiteSpace($line)) { throw "FAIL: retained LOCAL journal has an empty record at line $($index + 1)." }
        $parts = $line.Split([char[]]@("`t"), [StringSplitOptions]::None)
        if ($parts.Length -ne 11 -or $parts[0] -cne 'SYNCLOCAL1') {
            throw "FAIL: retained LOCAL journal record $($index + 1) has invalid SYNCLOCAL1 framing."
        }
        $payload = [string]::Join("`t", $parts[0..9])
        if ((Get-T21AuditSha256 ([Text.Encoding]::UTF8.GetBytes($payload))) -cne $parts[10]) {
            throw "FAIL: retained LOCAL journal checksum differs at line $($index + 1)."
        }
        $uuid = ConvertFrom-T21AuditBase64Url $parts[1]
        $delta = ConvertFrom-T21AuditBase64Url $parts[2]
        $balanceAfter = ConvertFrom-T21AuditBase64Url $parts[3]
        $requestId = ConvertFrom-T21AuditBase64Url $parts[7]
        $playerName = ConvertFrom-T21AuditBase64Url $parts[9]
        if ($uuid -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' -or
            $delta -notmatch '^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$' -or
            $balanceAfter -notmatch '^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$' -or
            $parts[4] -notmatch '^[0-9]+$' -or $parts[8] -notmatch '^[0-9]+$' -or
            [string]::IsNullOrWhiteSpace($requestId) -or [string]::IsNullOrWhiteSpace($playerName) -or
            $parts[5] -notin @('DEPOSIT', 'WITHDRAW', 'SET_BALANCE', 'TRANSFER_IN', 'TRANSFER_OUT') -or
            [string]::IsNullOrWhiteSpace($parts[6])) {
            throw "FAIL: retained LOCAL journal record $($index + 1) has an invalid event field."
        }
        if (-not $requestIds.Add($requestId)) { throw "FAIL: retained LOCAL journal repeats request ID '$requestId'." }
        $records.Add([pscustomobject][ordered]@{
            uuid = $uuid
            delta = $delta
            balanceAfter = $balanceAfter
            eventVersion = [long]$parts[4]
            type = $parts[5]
            source = $parts[6]
            requestId = $requestId
            timestamp = [long]$parts[8]
            playerName = $playerName
            recordSha256 = Get-T21AuditSha256 ([Text.Encoding]::UTF8.GetBytes($line))
        })
    }
    return @($records.ToArray())
}

function Assert-T21CandidateCheckoutCommit($Commit, [bool]$Required) {
    if ([string]::IsNullOrWhiteSpace([string]$Commit)) {
        if ($Required) { throw 'FAIL: candidate checkout commit metadata is required for this new runtime report.' }
        return
    }
    if ([string]$Commit -cnotmatch '^[0-9a-fA-F]{40}$') {
        throw 'FAIL: candidate checkout commit metadata must be a 40-character Git SHA.'
    }
}

function Get-T21LocalJournalHelperPin($SourcePins, [bool]$Required) {
    $pin = [string](Get-Field $SourcePins 'localJournalHelperSha256')
    if ([string]::IsNullOrWhiteSpace($pin) -and $Required) {
        throw 'FAIL: new runtime reports must pin the LOCAL journal helper source.'
    }
    return $pin
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
$hasExplicitRecoveryScenario = -not [string]::IsNullOrWhiteSpace([string](Get-Field $report 'recoveryScenario'))
$recoveryScenario = Resolve-T21RecoveryScenario $report
$expectedLifecycleStages = if ($recoveryScenario -eq 'shutdown-timeout-handoff') {
    @('baseline-1.3.2', 'upgrade-1.4', 'recovery-1.4', 'rollback-1.3.2')
} else {
    @('baseline-1.3.2', 'upgrade-1.4', 'rollback-1.3.2')
}
Assert-T21RecoveryTask $recoveryScenario ([string]$report.task)
$candidateCheckoutCommit = Get-Field $report 'candidateCheckoutCommit'
if ($null -eq $candidateCheckoutCommit) { $candidateCheckoutCommit = Get-Field $report 'candidateSourceCommit' }
Assert-T21CandidateCheckoutCommit $candidateCheckoutCommit ($recoveryScenario -in @('seeded-journal-replay-no-receipt', 'shutdown-timeout-handoff'))
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
foreach ($stage in $expectedLifecycleStages) {
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

$localJournalHelperPin = Get-T21LocalJournalHelperPin $sourcePins $hasExplicitRecoveryScenario
$sourcePinsToVerify = @(
    [ordered]@{ name = 'runner'; path = Join-Path $repoRoot 'tools\plugdev\run-upgrade-cycle.ps1'; hash = [string]$sourcePins.runnerSha256 },
    [ordered]@{ name = 'prepare'; path = Join-Path $repoRoot 'tools\plugdev\prepare.mjs'; hash = [string]$sourcePins.prepareSha256 },
    [ordered]@{ name = 'prepare test'; path = Join-Path $repoRoot 'tools\plugdev\prepare.test.mjs'; hash = [string]$sourcePins.prepareTestSha256 },
    [ordered]@{ name = 'shared validation'; path = Join-Path $repoRoot 'tools\plugdev\t21-upgrade-validation.ps1'; hash = [string]$sourcePins.validationSha256 },
    [ordered]@{ name = 'offline auditor'; path = Join-Path $repoRoot 'tools\plugdev\t21-upgrade-cycle-audit.ps1'; hash = [string]$sourcePins.offlineAuditorSha256 }
)
if (-not [string]::IsNullOrWhiteSpace($localJournalHelperPin)) {
    $sourcePinsToVerify += [ordered]@{ name = 'LOCAL journal helper'; path = Join-Path $repoRoot 'tools\plugdev\t21-local-persistence-journal.ps1'; hash = $localJournalHelperPin }
}
foreach ($sourcePin in $sourcePinsToVerify) {
    Assert-True "$($sourcePin.name) source exists" (Test-Path -LiteralPath $sourcePin.path -PathType Leaf)
    $sourceHash = (Get-FileHash -LiteralPath $sourcePin.path -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-T21SourcePin $sourcePin.name ([string]$sourcePin.hash) $sourceHash
}
$stagedPreparePath = Resolve-RunRelativePath 'tools/plugdev/prepare.mjs'
$stagedPrepareSha256 = (Get-FileHash -LiteralPath $stagedPreparePath -Algorithm SHA256).Hash.ToLowerInvariant()
Assert-Equal 'staged prepare source SHA-256' ([string]$sourcePins.prepareStagedSha256) $stagedPrepareSha256
Assert-Equal 'staged prepare matches source' ([string]$sourcePins.prepareSha256) $stagedPrepareSha256

$configObservations = @($report.configObservations)
$expectedConfigObservationCount = if ($recoveryScenario -eq 'shutdown-timeout-handoff') { '8' } else { '6' }
Assert-Equal 'config observation count' $expectedConfigObservationCount ([string]$configObservations.Count)
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
$expectedStartRecords = [System.Collections.Generic.List[object]]::new()
$expectedStartRecords.Add([ordered]@{ stage = 'baseline-1.3.2'; pluginVersion = '1.3.2'; apiClassLoader = 'NOT_APPLICABLE' })
$expectedStartRecords.Add([ordered]@{ stage = 'upgrade-1.4'; pluginVersion = [string]$report.candidatePlugin; apiClassLoader = 'PASS' })
if ($recoveryScenario -eq 'shutdown-timeout-handoff') {
    $expectedStartRecords.Add([ordered]@{ stage = 'recovery-1.4'; pluginVersion = [string]$report.candidatePlugin; apiClassLoader = 'PASS' })
}
$expectedStartRecords.Add([ordered]@{ stage = 'rollback-1.3.2'; pluginVersion = '1.3.2'; apiClassLoader = 'NOT_APPLICABLE' })
$expectedStartRecords = @($expectedStartRecords.ToArray())
$expectedStageRecordCount = [string]($expectedLifecycleStages.Count * 2)
Assert-Equal 'complete stage record count' $expectedStageRecordCount ([string]$stageRecords.Count)
Assert-Equal 'normal start record count' ([string]$expectedLifecycleStages.Count) ([string]$startRecords.Count)
Assert-Equal 'normal stop record count' ([string]$expectedLifecycleStages.Count) ([string]$stopRecords.Count)
Assert-True 'M04 lifecycle stage chronology' (Test-T21M04StageRecordChronology $stageRecords $expectedLifecycleStages)
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
if ($recoveryScenario -in @('seeded-journal-replay', 'seeded-journal-replay-no-receipt')) {
    $upgradeLogPath = Resolve-RunRelativePath (Join-Path 'logs' 'upgrade-1.4-server.log')
    if (-not (Test-Path -LiteralPath $upgradeLogPath -PathType Leaf)) {
        throw 'Required M04 runtime log is missing: upgrade-1.4-server.log'
    }
    $upgradeLog = Get-Content -LiteralPath $upgradeLogPath -Raw
    Assert-T21StartupRecoveryChronology $upgradeLog `
        'Recovered 1 accepted LOCAL SQLite write(s) from the shutdown journal' `
        'Syncmoney enabled successfully.' `
        'T21_LEGACY_SQLITE_PASS operation=read expected-a=117.25 expected-b=0.00'
} elseif ($recoveryScenario -eq 'shutdown-timeout-handoff') {
    $upgradeLogPath = Resolve-RunRelativePath (Join-Path 'logs' 'upgrade-1.4-server.log')
    $upgradeLog = Get-Content -LiteralPath $upgradeLogPath -Raw
    Assert-True 'shutdown timeout reached the configured LOCAL drain deadline' $upgradeLog.Contains('LOCAL shutdown deadline expired; accepted writes remain owned while durable journal handoff runs outside the shutdown callback', [StringComparison]::Ordinal)
    Assert-True 'durable LOCAL journal handoff completed before server exit' $upgradeLog.Contains('LOCAL accepted writes were durably journaled for idempotent startup recovery', [StringComparison]::Ordinal)
    $recoveryLogPath = Resolve-RunRelativePath (Join-Path 'logs' 'recovery-1.4-server.log')
    Assert-True 'recovery candidate runtime log exists' (Test-Path -LiteralPath $recoveryLogPath -PathType Leaf)
    $recoveryLog = Get-Content -LiteralPath $recoveryLogPath -Raw
    Assert-T21StartupRecoveryChronology $recoveryLog `
        'Recovered 2 accepted LOCAL SQLite write(s) from the shutdown journal' `
        'Syncmoney enabled successfully.' `
        'T21_LEGACY_SQLITE_PASS operation=read expected-a=1337.25 expected-b=765.50'
    Assert-True 'recovery candidate API consumer marker' $recoveryLog.Contains('API CLASSLOADER PASS provider=Syncmoney', [StringComparison]::Ordinal)
} else {
    Assert-LogMarker 'upgrade-1.4-server.log' 'T21_LEGACY_SQLITE_PASS operation=read expected-a=100.00 expected-b=0.00'
}
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
if ($recoveryScenario -in @('seeded-journal-replay', 'seeded-journal-replay-no-receipt')) {
    $replay = Get-Field $report 'recoveryJournalReplay'
    Assert-True 'seeded replay report is present' ($null -ne $replay)
    $seed = Get-Field $replay 'seed'
    Assert-True 'seeded replay input is present' ($null -ne $seed)
    $expectedReceiptState = if ($recoveryScenario -eq 'seeded-journal-replay-no-receipt') { 'absent' } else { 'committed' }
    $expectedPreReplayRequestIdSchemaCount = if ($expectedReceiptState -eq 'committed') { '1' } else { '0' }
    Assert-Equal 'seeded replay receipt state' $expectedReceiptState ([string](Get-Field $seed 'receiptState'))
    Assert-Equal 'journal seed database path' '.plugdev/run/plugins/Syncmoney/t21-data/legacy-local.db' ([string](Get-Field $seed 'databasePath'))
    Assert-Equal 'journal seed path' '.plugdev/run/plugins/Syncmoney/t21-data/local-persistence.journal' ([string](Get-Field $seed 'journalPath'))
    Assert-Equal 'retained journal seed path' 'inputs/seeded-local-persistence.journal' ([string](Get-Field $seed 'archivePath'))

    $seedArchivePath = Resolve-RunRelativePath ([string](Get-Field $seed 'archivePath'))
    Assert-True 'retained journal seed file exists' (Test-Path -LiteralPath $seedArchivePath -PathType Leaf)
    $seedBytes = [IO.File]::ReadAllBytes($seedArchivePath)
    Assert-Equal 'retained journal seed byte count' ([long](Get-Field $seed 'fileBytes')) ([long]$seedBytes.Length)
    $seedFileHash = (Get-FileHash -LiteralPath $seedArchivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Equal 'retained journal seed SHA-256' ([string](Get-Field $seed 'fileSha256')) $seedFileHash
    $expectedSeedBytes = [Text.Encoding]::UTF8.GetBytes([string](Get-Field $seed 'record') + "`n")
    Assert-Equal 'seed input has exact single LF record bytes' ([Convert]::ToBase64String($expectedSeedBytes)) ([Convert]::ToBase64String($seedBytes))
    $record = [string](Get-Field $seed 'record')
    Assert-Equal 'seed record SHA-256' ([string](Get-Field $seed 'recordSha256')) ((Get-FileHash -InputStream ([IO.MemoryStream]::new([Text.Encoding]::UTF8.GetBytes($record))) -Algorithm SHA256).Hash.ToLowerInvariant())
    $parts = $record.Split([char[]]@("`t"), [StringSplitOptions]::None)
    Assert-Equal 'SYNCLOCAL1 field count' '11' ([string]$parts.Count)
    Assert-Equal 'SYNCLOCAL1 format marker' 'SYNCLOCAL1' $parts[0]
    $payload = [string]::Join("`t", $parts[0..9])
    $checksumAlgorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $checksum = [BitConverter]::ToString($checksumAlgorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($payload))).Replace('-', '').ToLowerInvariant()
    } finally {
        $checksumAlgorithm.Dispose()
    }
    Assert-Equal 'SYNCLOCAL1 payload checksum' $checksum $parts[10]

    $decodeBase64Url = {
        param([string]$value)
        $standard = $value.Replace('-', '+').Replace('_', '/')
        switch ($standard.Length % 4) {
            2 { $standard += '==' }
            3 { $standard += '=' }
            1 { throw 'Invalid base64url field in retained LOCAL journal seed.' }
        }
        return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($standard))
    }
    Assert-Equal 'seed event UUID' '00000000-0000-0000-0000-000000000001' (& $decodeBase64Url $parts[1])
    Assert-Equal 'seed event delta' '17.25' (& $decodeBase64Url $parts[2])
    Assert-Equal 'seed event balance after' '117.25' (& $decodeBase64Url $parts[3])
    Assert-Equal 'seed event version' '7' $parts[4]
    Assert-Equal 'seed event type' 'DEPOSIT' $parts[5]
    Assert-Equal 'seed event source' 'VAULT_DEPOSIT' $parts[6]
    $requestId = & $decodeBase64Url $parts[7]
    Assert-Equal 'seed request ID matches report' ([string](Get-Field $seed 'requestId')) $requestId
    Assert-True 'seed request ID is unique T21 synthetic data' ($requestId -match '^t21-local-replay-[0-9a-f]{32}$')
    Assert-Equal 'seed event timestamp matches report' ([string](Get-Field $seed 'timestamp')) $parts[8]
    Assert-Equal 'seed event player name' 'fixture-a' (& $decodeBase64Url $parts[9])
    $expectedReplayedTransaction = "2|00000000-0000-0000-0000-000000000001|fixture-a|DEPOSIT|real|17.25|real|100.00|real|117.25|VAULT_DEPOSIT|$([long](Get-Field $seed 'timestamp'))|2|$requestId"
    $expectedPreReplayBalances = if ($expectedReceiptState -eq 'committed') {
        "00000000-0000-0000-0000-000000000001|fixture-a|real|117.25|8`n00000000-0000-0000-0000-000000000002|fixture-b|real|0.00|3"
    } else {
        "00000000-0000-0000-0000-000000000001|fixture-a|real|100.00|7`n00000000-0000-0000-0000-000000000002|fixture-b|real|0.00|3"
    }
    Assert-Equal 'pre-replay account rows match receipt state' $expectedPreReplayBalances (@(Get-Field $seed 'preReplayBalances') -join "`n")
    Assert-Equal 'pre-replay historical transaction' '1|00000000-0000-0000-0000-000000000001|fixture-a|DEPOSIT|100.00|TEST' ([string](Get-Field $seed 'preReplayTransaction'))

    $preReplayDatabaseRelativePath = [string](Get-Field $seed 'preReplayDatabasePath')
    Assert-Equal 'pre-replay SQLite snapshot path' 'inputs/pre-replay-local.db' $preReplayDatabaseRelativePath
    $preReplayDatabasePath = Resolve-RunRelativePath $preReplayDatabaseRelativePath
    Assert-True 'retained pre-replay SQLite snapshot exists' (Test-Path -LiteralPath $preReplayDatabasePath -PathType Leaf)
    $preReplayDatabaseItem = Get-Item -LiteralPath $preReplayDatabasePath
    Assert-Equal 'retained pre-replay SQLite snapshot byte count' ([long](Get-Field $seed 'preReplayDatabaseBytes')) ([long]$preReplayDatabaseItem.Length)
    $preReplayDatabaseSha256 = (Get-FileHash -LiteralPath $preReplayDatabasePath -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Equal 'retained pre-replay SQLite snapshot SHA-256' ([string](Get-Field $seed 'preReplayDatabaseSha256')) $preReplayDatabaseSha256
    $preReplayDatabaseBalances = @(Invoke-SqliteRead $sqlite3Command.Source $preReplayDatabasePath "SELECT uuid || '|' || name || '|' || typeof(balance) || '|' || printf('%.2f', balance) || '|' || version FROM player_balances WHERE uuid IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002') ORDER BY uuid;")
    Assert-Equal 'snapshot account rows match receipt state' $expectedPreReplayBalances ($preReplayDatabaseBalances -join "`n")
    Assert-Equal 'snapshot balances match seed report' (@(Get-Field $seed 'preReplayBalances') -join "`n") ($preReplayDatabaseBalances -join "`n")
    Assert-Equal 'seed pre-replay request-ID column count' $expectedPreReplayRequestIdSchemaCount ([string](Get-Field $seed 'preReplayRequestIdColumnCount'))
    Assert-Equal 'seed pre-replay request-ID index count' $expectedPreReplayRequestIdSchemaCount ([string](Get-Field $seed 'preReplayRequestIdIndexCount'))
    $preReplayDatabaseRequestIdColumnCount = @(Invoke-SqliteRead $sqlite3Command.Source $preReplayDatabasePath "SELECT COUNT(*) FROM pragma_table_info('transactions') WHERE lower(name)='request_id';")
    $preReplayDatabaseRequestIdIndexCount = @(Invoke-SqliteRead $sqlite3Command.Source $preReplayDatabasePath "SELECT COUNT(*) FROM pragma_index_list('transactions') WHERE lower(name)='idx_transactions_request_id';")
    Assert-Equal 'snapshot pre-replay request-ID column count' $expectedPreReplayRequestIdSchemaCount ([string]$preReplayDatabaseRequestIdColumnCount[0])
    Assert-Equal 'snapshot pre-replay request-ID index count' $expectedPreReplayRequestIdSchemaCount ([string]$preReplayDatabaseRequestIdIndexCount[0])
    if ($expectedReceiptState -eq 'committed') {
        $preReplayDatabaseReceipt = @(Invoke-SqliteRead $sqlite3Command.Source $preReplayDatabasePath "SELECT id || '|' || uuid || '|' || name || '|' || type || '|' || typeof(amount) || '|' || printf('%.2f', amount) || '|' || typeof(balance_before) || '|' || printf('%.2f', balance_before) || '|' || typeof(balance_after) || '|' || printf('%.2f', balance_after) || '|' || source || '|' || timestamp || '|' || sequence || '|' || request_id FROM transactions WHERE request_id='$requestId';")
        Assert-Equal 'same-request SQLite receipt predates replay' $expectedReplayedTransaction ([string](Get-Field $seed 'preReplayReplayTransaction'))
        $preReplayObservation = [pscustomobject]@{ transactionRows = @([string](Get-Field $seed 'preReplayReplayTransaction')) }
        Assert-T21ReplayReceiptBinding $preReplayObservation $expectedReplayedTransaction $preReplayDatabaseReceipt
    } else {
        Assert-T21AbsentPreReplayReceipt (Get-Field $seed 'preReplayReplayTransaction') @()
        Assert-Equal 'no-receipt snapshot retains only historical transaction' '1' ([string](@(Invoke-SqliteRead $sqlite3Command.Source $preReplayDatabasePath 'SELECT COUNT(*) FROM transactions;')[0]))
        Assert-Equal 'no-receipt snapshot historical transaction' '1|00000000-0000-0000-0000-000000000001|fixture-a|DEPOSIT|100.00|TEST' ([string](@(Invoke-SqliteRead $sqlite3Command.Source $preReplayDatabasePath "SELECT id || '|' || uuid || '|' || name || '|' || type || '|' || printf('%.2f', amount) || '|' || source FROM transactions WHERE id=1;")[0]))
    }

    $startupSchemaObservation = Get-Field $replay 'startupSchemaObservation'
    Assert-True 'candidate startup schema observation is present before replay read' ($null -ne $startupSchemaObservation)
    Assert-Equal 'candidate startup schema observation stage' 'candidate-enabled-before-replay-read' ([string](Get-Field $startupSchemaObservation 'stage'))
    Assert-Equal 'candidate startup request-ID column count' '1' ([string](Get-Field $startupSchemaObservation 'requestIdColumnCount'))
    Assert-Equal 'candidate startup request-ID index count' '1' ([string](Get-Field $startupSchemaObservation 'requestIdIndexCount'))

    $observation = Get-Field $replay 'replayObservation'
    Assert-True 'runtime journal replay observation is present' ($null -ne $observation)
    Assert-Equal 'replayed account rows' "00000000-0000-0000-0000-000000000001|fixture-a|real|117.25|8`n00000000-0000-0000-0000-000000000002|fixture-b|real|0.00|3" (@(Get-Field $observation 'balanceRows') -join "`n")
    Assert-Equal 'one replay transaction receipt' '1' ([string](Get-Field $observation 'requestIdCount'))
    Assert-Equal 'runtime replay journal path' ([string](Get-Field $seed 'journalPath')) ([string](Get-Field $observation 'journalPath'))
    Assert-Equal 'runtime journal was empty after replay receipt' '0' ([string](Get-Field $observation 'journalBytes'))

    $journalPath = Resolve-RunRelativePath ([string](Get-Field $seed 'journalPath'))
    Assert-True 'cleared journal remains as the receipt-backed file' (Test-Path -LiteralPath $journalPath -PathType Leaf)
    Assert-Equal 'journal empty after candidate normal stop' '0' ([long](Get-Item -LiteralPath $journalPath).Length)
    if ($requestId -notmatch '^[a-z0-9-]+$') { throw 'Refusing an unsafe request ID in the independent SQLite query.' }
    Assert-Equal 'final request-ID column exists after candidate startup' '1' ([string](@(Invoke-SqliteRead $sqlite3Command.Source $databasePath "SELECT COUNT(*) FROM pragma_table_info('transactions') WHERE lower(name)='request_id';")[0]))
    Assert-Equal 'final request-ID index exists after candidate startup' '1' ([string](@(Invoke-SqliteRead $sqlite3Command.Source $databasePath "SELECT COUNT(*) FROM pragma_index_list('transactions') WHERE lower(name)='idx_transactions_request_id';")[0]))
    $replayedTransaction = @(Invoke-SqliteRead $sqlite3Command.Source $databasePath "SELECT id || '|' || uuid || '|' || name || '|' || type || '|' || typeof(amount) || '|' || printf('%.2f', amount) || '|' || typeof(balance_before) || '|' || printf('%.2f', balance_before) || '|' || typeof(balance_after) || '|' || printf('%.2f', balance_after) || '|' || source || '|' || timestamp || '|' || sequence || '|' || request_id FROM transactions WHERE request_id='$requestId';")
    Assert-Equal 'SQLite request ID has exactly one replay row' '1' ([string](@(Invoke-SqliteRead $sqlite3Command.Source $databasePath "SELECT COUNT(*) FROM transactions WHERE request_id='$requestId';")[0]))
    Assert-T21ReplayReceiptBinding $observation $expectedReplayedTransaction $replayedTransaction
} elseif ($recoveryScenario -eq 'shutdown-timeout-handoff') {
    Assert-Equal 'timeout-handoff scenario has no seeded replay report' $null (Get-Field $report 'recoveryJournalReplay')
    $handoff = Get-Field $report 'recoveryJournalHandoff'
    Assert-True 'shutdown-timeout handoff report is present' ($null -ne $handoff)

    $writeLock = Get-Field $handoff 'writeLock'
    Assert-Equal 'timeout-handoff SQLite journal mode' 'wal' ([string](Get-Field $writeLock 'journalMode'))
    Assert-Equal 'second SQLite writer was blocked' $true ([bool](Get-Field $writeLock 'writeLockObserved'))
    Assert-True 'SQLite competing-writer probe reported lock contention' ([string](Get-Field $writeLock 'verifierOutput') -match '(?i)database is locked')
    Assert-True 'owned SQLite lock process ID was recorded' ([int](Get-Field $writeLock 'processId') -gt 0)
    Assert-Equal 'SQLite writer lock exited after explicit rollback' $true ([bool](Get-Field (Get-Field $handoff 'lockRelease') 'processExitVerified'))
    Assert-Equal 'SQLite writer lock release exit code' '0' ([string](Get-Field (Get-Field $handoff 'lockRelease') 'exitCode'))
    Assert-Equal 'SQLite accepts a new writer after release' '0' ([string](Get-Field $handoff 'releaseProbeExitCode'))

    $stopObservation = Get-Field $handoff 'stop'
    Assert-Equal 'graceful timeout stop stage' 'upgrade-1.4' ([string](Get-Field $stopObservation 'stage'))
    Assert-Equal 'timeout stop RCON exit code' '0' ([string](Get-Field $stopObservation 'stopExitCode'))
    Assert-Equal 'timeout stop process exit verified' $true ([bool](Get-Field $stopObservation 'processExitVerified'))
    Assert-Equal 'timeout stop used no forced cleanup' $false ([bool](Get-Field $stopObservation 'forcedCleanup'))
    Assert-Equal 'reported LOCAL drain deadline' '10000' ([string](Get-Field $stopObservation 'requiredDrainDeadlineMilliseconds'))
    Assert-True 'graceful shutdown exceeded the LOCAL drain deadline' ([long](Get-Field $stopObservation 'elapsedMilliseconds') -ge 10000)
    Assert-Equal 'timeout marker was observed' $true ([bool](Get-Field $stopObservation 'timeoutMarkerObserved'))
    Assert-Equal 'journal handoff completion marker was observed' $true ([bool](Get-Field $stopObservation 'durableHandoffMarkerObserved'))
    Assert-Equal 'timeout stop log path' 'upgrade-1.4-server.log' ([string](Get-Field $stopObservation 'serverLog'))

    $journal = Get-Field $handoff 'journal'
    Assert-Equal 'runtime LOCAL journal path' '.plugdev/run/plugins/Syncmoney/t21-data/local-persistence.journal' ([string](Get-Field $journal 'path'))
    Assert-Equal 'archived timeout journal path' 'inputs/timeout-handoff-local-persistence.journal' ([string](Get-Field $journal 'archivePath'))
    Assert-True 'timeout journal byte count is nonzero' ([long](Get-Field $journal 'bytes') -gt 0)
    $journalArchivePath = Resolve-RunRelativePath ([string](Get-Field $journal 'archivePath'))
    Assert-NoReparsePointAncestors $journalArchivePath
    Assert-True 'retained timeout journal archive exists' (Test-Path -LiteralPath $journalArchivePath -PathType Leaf)
    $journalArchiveItem = Get-Item -LiteralPath $journalArchivePath
    $journalArchiveSha256 = (Get-FileHash -LiteralPath $journalArchivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Equal 'retained timeout journal byte count' ([long](Get-Field $journal 'bytes')) ([long]$journalArchiveItem.Length)
    Assert-Equal 'retained timeout journal SHA-256' ([string](Get-Field $journal 'sha256')) $journalArchiveSha256
    $archiveRecords = @(Read-T21AuditLocalJournal $journalArchivePath)
    $reportedRecords = @(Get-Field $journal 'records')
    Assert-Equal 'runtime and retained journal record count' '2' ([string]$archiveRecords.Count)
    Assert-Equal 'reported and independently parsed journal record count' ([string]$archiveRecords.Count) ([string]$reportedRecords.Count)
    $expectedHandoffRecords = @(
        [ordered]@{ uuid = '00000000-0000-0000-0000-000000000001'; playerName = 'fixture-a'; balanceAfter = '1337.25' },
        [ordered]@{ uuid = '00000000-0000-0000-0000-000000000002'; playerName = 'fixture-b'; balanceAfter = '765.50' }
    )
    for ($index = 0; $index -lt $expectedHandoffRecords.Count; $index++) {
        $parsed = $archiveRecords[$index]
        $reported = $reportedRecords[$index]
        $expected = $expectedHandoffRecords[$index]
        Assert-Equal "handoff event account $index" ([string]$expected.uuid) ([string]$parsed.uuid)
        Assert-Equal "handoff event player name $index" ([string]$expected.playerName) ([string]$parsed.playerName)
        Assert-Equal "handoff event target balance $index" ([string]$expected.balanceAfter) ([string]$parsed.balanceAfter)
        Assert-Equal "handoff event type $index" 'SET_BALANCE' ([string]$parsed.type)
        Assert-Equal "handoff event source $index" 'COMMAND_ADMIN' ([string]$parsed.source)
        Assert-Equal "handoff record checksum pin $index" ([string]$parsed.recordSha256) ([string](Get-Field $reported 'recordSha256'))
        Assert-Equal "reported handoff request ID $index" ([string]$parsed.requestId) ([string](Get-Field $reported 'requestId'))
    }

    $preReplay = Get-Field $handoff 'preReplay'
    $preReplayRows = @(
        '00000000-0000-0000-0000-000000000001|fixture-a|real|100.00|7',
        '00000000-0000-0000-0000-000000000002|fixture-b|real|0.00|3'
    )
    Assert-Equal 'pre-replay LOCAL balances and versions stayed unchanged' ($preReplayRows -join "`n") (@(Get-Field $preReplay 'balanceRows') -join "`n")
    Assert-Equal 'only historical transaction exists before replay' '1' ([string](Get-Field $preReplay 'transactionCount'))
    $preReplayReceiptStates = @(Get-Field $preReplay 'receiptStates')
    Assert-Equal 'pre-replay receipt-state count' '2' ([string]$preReplayReceiptStates.Count)
    foreach ($record in $archiveRecords) {
        $matchingReceiptState = @($preReplayReceiptStates | Where-Object { [string](Get-Field $_ 'requestId') -ceq [string]$record.requestId })
        Assert-Equal "no SQLite receipt before replay for $($record.requestId)" '1' ([string]$matchingReceiptState.Count)
        Assert-Equal "pre-replay request count for $($record.requestId)" '0' ([string](Get-Field $matchingReceiptState[0] 'count'))
    }

    $snapshot = Get-Field $preReplay 'snapshot'
    Assert-Equal 'pre-replay database snapshot path' 'inputs/pre-timeout-handoff-local.db' ([string](Get-Field $snapshot 'path'))
    $snapshotPath = Resolve-RunRelativePath ([string](Get-Field $snapshot 'path'))
    Assert-NoReparsePointAncestors $snapshotPath
    Assert-True 'pre-replay database snapshot exists' (Test-Path -LiteralPath $snapshotPath -PathType Leaf)
    $snapshotItem = Get-Item -LiteralPath $snapshotPath
    Assert-Equal 'pre-replay database snapshot byte count' ([long](Get-Field $snapshot 'bytes')) ([long]$snapshotItem.Length)
    $snapshotSha256 = (Get-FileHash -LiteralPath $snapshotPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Equal 'pre-replay database snapshot SHA-256' ([string](Get-Field $snapshot 'sha256')) $snapshotSha256
    $snapshotRows = @(Invoke-SqliteRead $sqlite3Command.Source $snapshotPath "SELECT uuid || '|' || name || '|' || typeof(balance) || '|' || printf('%.2f', balance) || '|' || version FROM player_balances WHERE uuid IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002') ORDER BY uuid;")
    Assert-Equal 'pre-replay database snapshot balance/version rows' ($preReplayRows -join "`n") ($snapshotRows -join "`n")
    Assert-Equal 'pre-replay database snapshot transaction count' '1' ([string](@(Invoke-SqliteRead $sqlite3Command.Source $snapshotPath 'SELECT COUNT(*) FROM transactions;')[0]))
    foreach ($record in $archiveRecords) {
        $escapedRequestId = ([string]$record.requestId).Replace("'", "''")
        Assert-Equal "pre-replay snapshot has no receipt for $($record.requestId)" '0' ([string](@(Invoke-SqliteRead $sqlite3Command.Source $snapshotPath "SELECT COUNT(*) FROM transactions WHERE request_id='$escapedRequestId';")[0]))
    }

    $startupSchemaObservation = Get-Field $handoff 'replayStartupSchemaObservation'
    Assert-True 'recovery candidate schema observation is present before the first replay read' ($null -ne $startupSchemaObservation)
    Assert-Equal 'recovery candidate request-ID column count' '1' ([string](Get-Field $startupSchemaObservation 'requestIdColumnCount'))
    Assert-Equal 'recovery candidate request-ID index count' '1' ([string](Get-Field $startupSchemaObservation 'requestIdIndexCount'))
    $replayObservation = Get-Field $handoff 'replay'
    Assert-True 'timeout-handoff replay observation is present' ($null -ne $replayObservation)
    Assert-Equal 'timeout-handoff replay stage precedes first balance read' 'recovery-1.4-before-first-balance-read' ([string](Get-Field $replayObservation 'stage'))
    $expectedReplayRows = [System.Collections.Generic.List[string]]::new()
    foreach ($record in $archiveRecords) {
        $before = @($preReplayRows | Where-Object { $_.StartsWith([string]$record.uuid + '|', [StringComparison]::Ordinal) })[0].Split('|')
        $expectedVersion = [Math]::Max([long]$before[4] + 1L, [long]$record.eventVersion + 1L)
        $expectedReplayRows.Add("$($record.uuid)|$($record.playerName)|real|$($record.balanceAfter)|$expectedVersion")
    }
    $expectedReplayBalanceRows = @($expectedReplayRows | Sort-Object)
    Assert-Equal 'replayed LOCAL balances and versions' ($expectedReplayBalanceRows -join "`n") (@(Get-Field $replayObservation 'balanceRows') -join "`n")
    Assert-Equal 'one receipt for each timeout-handoff request' '2' ([string](Get-Field $replayObservation 'requestIdCount'))
    Assert-Equal 'replay transaction count' '3' ([string](Get-Field $replayObservation 'transactionCount'))
    Assert-Equal 'replay journal path' ([string](Get-Field $journal 'path')) ([string](Get-Field $replayObservation 'journalPath'))
    Assert-Equal 'replay cleared journal bytes' '0' ([string](Get-Field $replayObservation 'journalBytes'))
    $liveJournalPath = Resolve-RunRelativePath ([string](Get-Field $journal 'path'))
    Assert-True 'receipt-backed journal remains present after replay' (Test-Path -LiteralPath $liveJournalPath -PathType Leaf)
    Assert-Equal 'live journal is empty after replay receipts' '0' ([string](Get-Item -LiteralPath $liveJournalPath).Length)
    $actualReceiptRows = @(Get-Field $replayObservation 'transactionRows')
    Assert-Equal 'replay observation receipt rows match accepted requests' '2' ([string]$actualReceiptRows.Count)
    foreach ($record in $archiveRecords) {
        $escapedRequestId = ([string]$record.requestId).Replace("'", "''")
        $expectedBefore = ([string](@($preReplayRows | Where-Object { $_.StartsWith([string]$record.uuid + '|', [StringComparison]::Ordinal) })[0])).Split('|')[3]
        $expectedReceipt = "$($record.uuid)|$($record.playerName)|SET_BALANCE|$($record.balanceAfter)|$expectedBefore|$($record.balanceAfter)|COMMAND_ADMIN|$($record.requestId)"
        $matchingReportedReceipt = @($actualReceiptRows | Where-Object { [string]$_ -ceq $expectedReceipt })
        Assert-Equal "report has the matching replay receipt for $($record.requestId)" '1' ([string]$matchingReportedReceipt.Count)
        Assert-Equal "SQLite has exactly one replay receipt for $($record.requestId)" '1' ([string](@(Invoke-SqliteRead $sqlite3Command.Source $databasePath "SELECT COUNT(*) FROM transactions WHERE request_id='$escapedRequestId';")[0]))
        $actualReceipt = @(Invoke-SqliteRead $sqlite3Command.Source $databasePath "SELECT uuid || '|' || name || '|' || type || '|' || printf('%.2f', amount) || '|' || printf('%.2f', balance_before) || '|' || printf('%.2f', balance_after) || '|' || source || '|' || request_id FROM transactions WHERE request_id='$escapedRequestId';")
        Assert-Equal "SQLite replay receipt matches accepted event for $($record.requestId)" $expectedReceipt ([string]$actualReceipt[0])
    }
} else {
    Assert-Equal 'normal-drain scenario has no seeded journal report' $null (Get-Field $report 'recoveryJournalReplay')
    Assert-Equal 'non-timeout scenario has no handoff report' $null (Get-Field $report 'recoveryJournalHandoff')
}
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
