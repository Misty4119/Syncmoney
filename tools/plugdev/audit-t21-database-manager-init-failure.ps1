[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ReportPath,
    [Parameter(Mandatory = $true)][string]$PlugDevRoot,
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [string]$JavaHome = 'C:\Program Files\Java\jdk-25.0.4',
    [string]$ServerJar = 'build\reports\foundation\T21-sql-runtime\current-maximum-artifacts\paper-26.3-200.jar',
    [string]$CandidateJar = 'build\libs\Syncmoney-1.4.0-alpha.1.jar',
    [ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedServerSha256 = '0e104a19ad818f05266a4571c0a9b0cada241c2016ee64883e5d38435332fa8d',
    [ValidateSet('Minimum', 'MinimumFolia', 'CurrentMaximum', 'CurrentMaximumFolia', 'MinimumCanvas', 'CurrentMaximumCanvas')][string]$PlatformEndpoint = 'CurrentMaximum',
    [ValidateSet('PostgreSQL', 'MySQL')][string]$DatabaseProduct = 'PostgreSQL',
    [ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedCandidateSha256 = '69ddd979a896884dc3bacfa4d271041e46b279dcccc357c7c158e03f9e7189c1',
    [ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedAcceptanceSha256 = '4a8e5ffc8d32c482cd7cf2a474cfd480d5c46268c0ea9ec469d513ed6ff554df',
    [ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedApiConsumerSha256 = '49325f358195be3f0f1d4f99b2babbb6db3c26c212e2e5da7dc9f4209a2a396e',
    [ValidatePattern('^[0-9a-fA-F]{40}$')][string]$ExpectedPlugDevSourceCommit = 'b59871ae8d9f2731d2bcbca061a735edddd23eb2'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'The DatabaseManager initialization-failure auditor requires PowerShell 7 or newer.' }

$script:repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $PSScriptRoot 't21-database-manager-init-failure-validation.ps1')
. (Join-Path $PSScriptRoot 't21-startup-failure-validation.ps1')
. (Join-Path $PSScriptRoot 't21-database-manager-init-audit-log.ps1')

function Assert-T21DatabaseManagerInitFailure([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:assertions++
}

function Get-T21ObjectField([object]$Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    if ($Object -is [System.Collections.IDictionary]) {
        if ($Object.Contains($Name)) { return ,$Object[$Name] }
        return $null
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -ne $property) { return ,$property.Value }
    return $null
}

function Get-T21Hash([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Required evidence file is missing: $Path" }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Compare-T21JsonValue([object]$Left, [object]$Right) {
    $leftJson = ConvertTo-Json -InputObject $Left -Depth 16 -Compress
    $rightJson = ConvertTo-Json -InputObject $Right -Depth 16 -Compress
    return $leftJson -ceq $rightJson
}

$script:assertions = 0
$reportFullPath = (Resolve-Path -LiteralPath $ReportPath).Path
$runRoot = [IO.Path]::GetFullPath((Split-Path -Parent $reportFullPath))
$outputFullPath = [IO.Path]::GetFullPath($OutputPath)
$repositoryDrive = [IO.Path]::GetPathRoot($runRoot)
Assert-T21DatabaseManagerInitFailure ($repositoryDrive -ceq 'C:\') 'The report and retained runtime evidence must be on the C: SSD.'
Assert-T21DatabaseManagerInitFailure ($reportFullPath -cne $outputFullPath) 'The audit output cannot overwrite the source runtime report.'
if (Test-Path -LiteralPath $outputFullPath) { throw "Refusing to overwrite existing audit output: $outputFullPath" }

$report = Get-Content -LiteralPath $reportFullPath -Raw -ErrorAction Stop | ConvertFrom-Json -AsHashtable -ErrorAction Stop
$plugDevPath = (Resolve-Path -LiteralPath $PlugDevRoot).Path
$javaHomePath = (Resolve-Path -LiteralPath $JavaHome).Path
$serverJarPath = [IO.Path]::GetFullPath((Join-Path $script:repoRoot $ServerJar))
$candidateJarPath = [IO.Path]::GetFullPath((Join-Path $script:repoRoot $CandidateJar))
$acceptanceJarPath = Join-Path $script:repoRoot 'build\acceptance\SyncmoneyAcceptance.jar'
$apiConsumerJarPath = Join-Path $script:repoRoot 'build\acceptance\SyncmoneyApiConsumer.jar'

$serverHash = Get-T21Hash $serverJarPath
$candidateHash = Get-T21Hash $candidateJarPath
$acceptanceHash = Get-T21Hash $acceptanceJarPath
$apiConsumerHash = Get-T21Hash $apiConsumerJarPath
Assert-T21DatabaseManagerInitFailure (Test-T21DatabaseManagerInitExpectedPlatformServerPin -PlatformEndpoint $PlatformEndpoint -ExpectedServerSha256 $ExpectedServerSha256) 'Expected server SHA-256 does not match the selected formal platform endpoint pin.'
Assert-T21DatabaseManagerInitFailure ($serverHash -ceq $ExpectedServerSha256.ToLowerInvariant()) 'Selected server endpoint artifact hash differs from the pinned SHA-256.'
Assert-T21DatabaseManagerInitFailure ($candidateHash -ceq $ExpectedCandidateSha256.ToLowerInvariant()) 'Candidate artifact hash differs from the pinned SHA-256.'
Assert-T21DatabaseManagerInitFailure ($acceptanceHash -ceq $ExpectedAcceptanceSha256.ToLowerInvariant()) 'Acceptance JAR hash differs from the pinned SHA-256.'
Assert-T21DatabaseManagerInitFailure ($apiConsumerHash -ceq $ExpectedApiConsumerSha256.ToLowerInvariant()) 'API consumer JAR hash differs from the pinned SHA-256.'

$plugDevCommit = (& git -C $plugDevPath rev-parse HEAD 2>$null | Out-String).Trim().ToLowerInvariant()
if ($LASTEXITCODE -ne 0) { throw 'Could not read the local PlugDev source commit.' }
$plugDevDirty = @(& git -C $plugDevPath status --porcelain --untracked-files=all 2>$null)
$plugDevStatusExitCode = $LASTEXITCODE
if (-not (Test-T21DatabaseManagerInitFailureCleanGitStatus -StatusEntries $plugDevDirty -ExitCode $plugDevStatusExitCode)) {
    throw 'Could not verify that the PlugDev checkout is clean during offline audit.'
}
Assert-T21DatabaseManagerInitFailure ($plugDevCommit -ceq $ExpectedPlugDevSourceCommit.ToLowerInvariant()) 'PlugDev source commit differs from the pinned commit.'
Assert-T21DatabaseManagerInitFailure ($plugDevDirty.Count -eq 0) 'PlugDev worktree must be clean for this runtime evidence audit.'
$plugDevCliPath = Join-Path $plugDevPath 'packages\cli\dist\cli.js'
$plugDevCliHash = Get-T21Hash $plugDevCliPath
$plugDevManifest = Get-T21PlugDevCliDistManifest $plugDevPath
$plugDevManifestValid = Test-T21PlugDevCliDistManifest $plugDevManifest $plugDevManifest.sha256 $plugDevCliHash
Assert-T21DatabaseManagerInitFailure ([bool]$plugDevManifestValid) 'PlugDev CLI distribution manifest does not match the pinned CLI entrypoint.'

$sourcePaths = [ordered]@{
    runner = Join-Path $script:repoRoot 'tools\plugdev\run-mysql-sync-runtime.ps1'
    sharedValidator = Join-Path $script:repoRoot 'tools\plugdev\t21-upgrade-validation.ps1'
    sharedValidatorTest = Join-Path $script:repoRoot 'tools\plugdev\t21-upgrade-validation.test.ps1'
    startupFailureValidator = Join-Path $script:repoRoot 'tools\plugdev\t21-startup-failure-validation.ps1'
    startupFailureValidatorTest = Join-Path $script:repoRoot 'tools\plugdev\t21-startup-failure-validation.test.ps1'
    validator = Join-Path $script:repoRoot 'tools\plugdev\t21-database-manager-init-failure-validation.ps1'
    validatorTest = Join-Path $script:repoRoot 'tools\plugdev\t21-database-manager-init-failure-validation.test.ps1'
    databaseManager = Join-Path $script:repoRoot 'syncmoney-bukkit\src\main\java\noietime\syncmoney\storage\db\DatabaseManager.java'
    storageManager = Join-Path $script:repoRoot 'syncmoney-bukkit\src\main\java\noietime\syncmoney\storage\StorageManager.java'
    syncmoneyLifecycle = Join-Path $script:repoRoot 'syncmoney-bukkit\src\main\java\noietime\syncmoney\Syncmoney.java'
}
$sourceHashes = [ordered]@{}
foreach ($name in $sourcePaths.Keys) { $sourceHashes[$name] = Get-T21Hash $sourcePaths[$name] }

$syncmoneySourceCommit = (& git -C $script:repoRoot rev-parse HEAD 2>$null | Out-String).Trim().ToLowerInvariant()
if ($LASTEXITCODE -ne 0 -or $syncmoneySourceCommit -notmatch '^[0-9a-f]{40}$') { throw 'Could not read the current Syncmoney source commit.' }
$syncmoneySourceWorktreeDelta = @(& git -C $script:repoRoot status --porcelain --untracked-files=all 2>$null)
$syncmoneySourceStatusExitCode = $LASTEXITCODE
if ($syncmoneySourceStatusExitCode -ne 0) { throw 'Could not inspect the Syncmoney checkout status during offline audit.' }
$auditOnlyPaths = @(
    'tools/plugdev/audit-t21-database-manager-init-failure.ps1',
    'tools/plugdev/t21-database-manager-init-audit-log.ps1',
    'tools/plugdev/t21-database-manager-init-audit-log.test.ps1'
)
$syncmoneySourceDirty = @($syncmoneySourceWorktreeDelta | Where-Object {
    if (-not (Test-T21DatabaseManagerInitAuditPorcelainSeparator $_)) {
        $true
    } else {
        $path = $_.Substring(3).Trim().Replace('\', '/')
        $path -notin $auditOnlyPaths
    }
})
if (-not (Test-T21DatabaseManagerInitFailureCleanGitStatus -StatusEntries $syncmoneySourceDirty -ExitCode $syncmoneySourceStatusExitCode)) {
    throw 'Could not verify that the Syncmoney checkout contains no product-source changes during offline audit.'
}
$auditWorktreeDeltaValid = Test-T21DatabaseManagerInitAuditWorktreeDelta $syncmoneySourceWorktreeDelta
Assert-T21DatabaseManagerInitFailure ([bool]$auditWorktreeDeltaValid) 'Syncmoney checkout contains changes outside the pinned offline-auditor implementation and focused test.'

$javaExe = Join-Path $javaHomePath 'bin\java.exe'
$javaVersionOutput = @(& $javaExe -version 2>&1 | ForEach-Object { $_.ToString() }) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Could not verify the configured Java runtime.' }
Assert-T21DatabaseManagerInitFailure ($javaVersionOutput -match '25\.0\.4') 'Java runtime is not the pinned Java 25.0.4.'

$plugDevEvidence = Get-T21ObjectField $report 'plugDev'
$sourcePins = Get-T21ObjectField $report 'sourcePins'
$runnerEvidence = Get-T21ObjectField $report 'runner'
$serverJarPinName = Get-T21DatabaseManagerInitServerJarPinName -PlatformEndpoint $PlatformEndpoint -SourcePins $sourcePins
$sourcePinExpected = [ordered]@{
    runnerSha256 = $sourceHashes.runner
    validatorSha256 = $sourceHashes.startupFailureValidator
    validatorTestSha256 = $sourceHashes.startupFailureValidatorTest
    sharedValidatorSha256 = $sourceHashes.sharedValidator
    sharedValidatorTestSha256 = $sourceHashes.sharedValidatorTest
    databaseManagerInitFailureValidatorSha256 = $sourceHashes.validator
    databaseManagerInitFailureTestSha256 = $sourceHashes.validatorTest
    databaseManagerSourceSha256 = $sourceHashes.databaseManager
    storageManagerSourceSha256 = $sourceHashes.storageManager
    syncmoneyLifecycleSourceSha256 = $sourceHashes.syncmoneyLifecycle
    candidateJarSha256 = $candidateHash
    acceptanceJarSha256 = $acceptanceHash
    apiConsumerJarSha256 = $apiConsumerHash
    plugDevCommit = $plugDevCommit
    plugDevCliSha256 = $plugDevCliHash
    plugDevCliDistManifestSha256 = $plugDevManifest.sha256
    sourceCommit = $syncmoneySourceCommit
}
$sourcePinExpected[$serverJarPinName] = $serverHash
$runnerPinExpected = [ordered]@{
    sha256 = $sourceHashes.runner
    validatorSha256 = $sourceHashes.sharedValidator
    testSha256 = $sourceHashes.sharedValidatorTest
    startupFailureValidatorSha256 = $sourceHashes.startupFailureValidator
    startupFailureValidatorTestSha256 = $sourceHashes.startupFailureValidatorTest
}
$plugDevPinExpected = [ordered]@{
    sourceCommit = $plugDevCommit
    cliSha256 = $plugDevCliHash
    cliDistManifestSha256Before = $plugDevManifest.sha256
    cliDistManifestSha256After = $plugDevManifest.sha256
}
$artifactPinsMatch =
    (Test-T21DatabaseManagerInitFailureStringFields $sourcePins $sourcePinExpected) -and
    (Test-T21DatabaseManagerInitFailureStringFields $runnerEvidence $runnerPinExpected) -and
    (Test-T21DatabaseManagerInitFailureStringFields $plugDevEvidence $plugDevPinExpected)
Assert-T21DatabaseManagerInitFailure $artifactPinsMatch 'Runtime report source, artifact, or PlugDev manifest pins differ from the retained files.'

$server = Get-T21ObjectField $report 'server'
Assert-T21DatabaseManagerInitFailure (Test-T21DatabaseManagerInitFailureStringField $server 'path' $serverJarPath) 'Runtime report server path differs from the pinned input artifact.'
Assert-T21DatabaseManagerInitFailure (Test-T21DatabaseManagerInitFailureInteger $server 'bytes' ([long](Get-Item -LiteralPath $serverJarPath).Length)) 'Runtime report server artifact byte count differs from the pinned input.'
$reportPredicate = Test-T21DatabaseManagerInitFailureReport $report $serverHash $candidateHash $plugDevCommit $plugDevCliHash $plugDevManifest.sha256 $acceptanceHash $apiConsumerHash $syncmoneySourceCommit $PlatformEndpoint $DatabaseProduct
Assert-T21DatabaseManagerInitFailure ([bool]$reportPredicate) 'The report-specific DatabaseManager initial-schema predicate rejected the runtime report.'

$logs = Get-T21ObjectField $report 'logs'
if ($logs -isnot [System.Collections.IDictionary] -or $logs.Count -lt 1) { throw 'Runtime report log manifest is missing or malformed.' }
$rootPrefix = $runRoot.TrimEnd('\') + '\'
$auditedLogCount = 0
foreach ($relativePath in $logs.Keys) {
    $relativeText = [string]$relativePath
    if ([IO.Path]::IsPathRooted($relativeText) -or $relativeText -match '(^|[\\/])\.\.([\\/]|$)') {
        throw "Refusing unsafe retained-log path in report: $relativeText"
    }
    $logPath = [IO.Path]::GetFullPath((Join-Path $runRoot $relativeText))
    if (-not $logPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Retained log escapes the run directory: $relativeText" }
    $logManifest = Get-T21ObjectField $logs $relativeText
    $actualLogHash = Get-T21Hash $logPath
    $actualBytes = [long](Get-Item -LiteralPath $logPath).Length
    Assert-T21DatabaseManagerInitFailure ($actualBytes -eq [long](Get-T21ObjectField $logManifest 'bytes')) "Retained log byte count changed: $relativeText"
    Assert-T21DatabaseManagerInitFailure ($actualLogHash -ceq [string](Get-T21ObjectField $logManifest 'sha256')) "Retained log SHA-256 changed: $relativeText"
    $auditedLogCount++
}

$databaseLogPrefix = if ($DatabaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
$beforeLog = Join-Path $runRoot ("logs\{0}-database-manager-schema-before.txt" -f $databaseLogPrefix)
$afterLog = Join-Path $runRoot ("logs\{0}-database-manager-schema-after.txt" -f $databaseLogPrefix)
$privilegeLogPaths = @(Get-T21DatabaseManagerInitPrivilegeLogPaths -RunRoot $runRoot -DatabaseProduct $DatabaseProduct)
$requiredEvidenceLogs = @($privilegeLogPaths) + @($beforeLog, $afterLog)
foreach ($path in $requiredEvidenceLogs) {
    $relativePath = $path.Substring($runRoot.Length).TrimStart('\')
    Assert-T21DatabaseManagerInitFailure ($logs.Contains($relativePath)) "Required $DatabaseProduct evidence log is absent from the report manifest: $relativePath"
}
$reportPrivileges = Get-T21ObjectField $report 'databaseDdlPrivileges'
if ($DatabaseProduct -ceq 'PostgreSQL') {
    $observedPrivileges = Get-Content -LiteralPath $privilegeLogPaths[0] -Raw | ConvertFrom-Json -AsHashtable -ErrorAction Stop
} else {
    $identityLines = @(Get-Content -LiteralPath $privilegeLogPaths[0] -ErrorAction Stop)
    $grantLines = @(Get-Content -LiteralPath $privilegeLogPaths[1] -ErrorAction Stop)
    Assert-T21DatabaseManagerInitFailure ($identityLines.Count -eq 1 -and $grantLines.Count -eq 2) 'MySQL must retain exactly one authenticated-identity row and two grant rows.'
    $observedPrivileges = [ordered]@{
        product = 'MySQL'
        appRole = Get-T21ObjectField $reportPrivileges 'appRole'
        targetDatabase = Get-T21ObjectField $reportPrivileges 'targetDatabase'
        authenticatedIdentity = $identityLines[0]
        grantStatements = [string[]]$grantLines
    }
}
Assert-T21DatabaseManagerInitFailure (Compare-T21JsonValue $observedPrivileges $reportPrivileges) "$DatabaseProduct privilege logs differ from the report evidence."
$reportBefore = Get-T21ObjectField $report 'schemaStateBefore'
$observedBefore = Get-Content -LiteralPath $beforeLog -Raw | ConvertFrom-Json -ErrorAction Stop
Assert-T21DatabaseManagerInitFailure (Compare-T21JsonValue $observedBefore $reportBefore) "Pre-start $DatabaseProduct schema JSON log differs from the report evidence."
$reportAfter = Get-T21ObjectField $report 'schemaStateAfter'
$observedAfter = Get-Content -LiteralPath $afterLog -Raw | ConvertFrom-Json -ErrorAction Stop
Assert-T21DatabaseManagerInitFailure (Compare-T21JsonValue $observedAfter $reportAfter) "Post-failure $DatabaseProduct schema JSON log differs from the report evidence."

$serverLogEvidence = Get-T21DatabaseManagerInitAuditLogEvidence -Report $report -Logs $logs
$serverLogPath = [IO.Path]::GetFullPath((Join-Path $runRoot $serverLogEvidence.path))
$serverLogText = Get-Content -LiteralPath $serverLogPath -Raw -ErrorAction Stop
$startupLogAudit = Test-T21DatabaseManagerInitFailureStartupLog -LogText $serverLogText -CandidateVersion '1.4.0-alpha.1' -DatabaseProduct $DatabaseProduct -MySqlAppRole ([string](Get-T21ObjectField $observedPrivileges 'appRole'))
Assert-T21DatabaseManagerInitFailure ([bool]$startupLogAudit.passed) "Retained server startup log failed the ordered startup/cleanup predicate: $($startupLogAudit.failures -join '; ')"

$runDirectory = [IO.Path]::GetFullPath([string](Get-T21ObjectField $report 'runDirectory'))
Assert-T21DatabaseManagerInitFailure ($runDirectory -ceq $runRoot) 'Runtime report directory differs from the report file directory.'

$outputDirectory = Split-Path -Parent $outputFullPath
if (-not (Test-Path -LiteralPath $outputDirectory -PathType Container)) {
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
}
$resultLines = @(
    'status=PASS_BOUNDED_SUBCASE_E2',
    "report=$reportFullPath",
    "reportSha256=$(Get-T21Hash $reportFullPath)",
    "reportBytes=$((Get-Item -LiteralPath $reportFullPath).Length)",
    "platformLogStage=$($serverLogEvidence.stage)",
    "serverLog=$serverLogPath",
    "databaseProduct=$DatabaseProduct",
    "serverLogSha256=$(Get-T21Hash $serverLogPath)",
    "retainedLogsAudited=$auditedLogCount",
    "sourceCommit=$syncmoneySourceCommit",
    "runtimeSourceWorkingTreeDirty=$([bool](Get-T21ObjectField $report 'sourceWorkingTreeDirty'))",
    "auditWorktreeDelta=$($syncmoneySourceWorktreeDelta -join ';')",
    "auditLogHelperSha256=$(Get-T21Hash (Join-Path $PSScriptRoot 't21-database-manager-init-audit-log.ps1'))",
    "auditScriptSha256=$(Get-T21Hash $PSCommandPath)",
    "auditLogHelperTestSha256=$(Get-T21Hash (Join-Path $PSScriptRoot 't21-database-manager-init-audit-log.test.ps1'))",
    "plugDevCommit=$plugDevCommit",
    "plugDevCliSha256=$plugDevCliHash",
    "plugDevManifestSha256=$($plugDevManifest.sha256)",
    "assertions=$($script:assertions)"
)
[IO.File]::WriteAllLines($outputFullPath, [string[]]$resultLines, [Text.UTF8Encoding]::new($false))
Write-Output ($resultLines -join "`n")
