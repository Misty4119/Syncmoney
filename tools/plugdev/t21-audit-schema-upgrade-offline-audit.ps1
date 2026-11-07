[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ReportPath,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedCandidateSha256,
    [Parameter(Mandatory = $true)][ValidateSet('MySQL', 'MariaDB', 'PostgreSQL')][string]$DatabaseProduct,
    [Parameter(Mandatory = $true)][ValidateSet('Minimum', 'Maximum', 'CurrentMaximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'CurrentMaximumFolia', 'MinimumCanvas', 'MaximumCanvas', 'CurrentMaximumCanvas')][string]$PlatformEndpoint
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 't21-upgrade-validation.ps1')
. (Join-Path $PSScriptRoot 't21-startup-failure-validation.ps1')
. (Join-Path $PSScriptRoot 't21-audit-schema-upgrade-validation.ps1')

function Assert-T21AuditUpgradeOffline([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

function Get-T21AuditUpgradeFileSha256([string]$path) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Test-T21PathContainsReparsePoint $path)) {
        throw "Required evidence file is missing or traverses a reparse point: $path"
    }
    return (Get-FileHash -LiteralPath $path -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
}

function Assert-T21AuditUpgradeArtifact([string]$path, [string]$expectedSha256, [string]$label) {
    $actual = Get-T21AuditUpgradeFileSha256 $path
    if ($actual -cne $expectedSha256.ToLowerInvariant()) { throw "$label SHA-256 mismatch." }
    return [ordered]@{ path=[IO.Path]::GetFullPath($path); sha256=$actual; bytes=[long](Get-Item -LiteralPath $path).Length }
}

$absoluteReportPath = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $ReportPath -ErrorAction Stop).Path)
if (Test-T21PathContainsReparsePoint $absoluteReportPath) { throw 'The report path traverses a reparse point.' }
$report = Get-Content -LiteralPath $absoluteReportPath -Raw -Encoding UTF8 -ErrorAction Stop | ConvertFrom-Json -AsHashtable -ErrorAction Stop
$runRoot = [IO.Path]::GetFullPath([string](Get-T21ObservedValue $report 'runDirectory')).TrimEnd([char[]]@('\', '/'))
$pathComparison = if ([OperatingSystem]::IsWindows()) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
$expectedReportPath = [IO.Path]::GetFullPath((Join-Path $runRoot 'result.json'))
Assert-T21AuditUpgradeOffline ([string]::Equals($absoluteReportPath, $expectedReportPath, $pathComparison)) `
    'The report is not the result.json directly inside its declared run directory.'
Assert-T21AuditUpgradeOffline ([string](Get-T21ObservedValue $report 'status') -ceq 'PASS_BOUNDED_SUBCASE') `
    'The report does not claim PASS_BOUNDED_SUBCASE.'

$platform = Get-T21PlatformEndpoint -PlatformEndpoint $PlatformEndpoint
$expectedServerSha256 = $platform.sha256
Assert-T21AuditUpgradeOffline (Test-T21AuditSchemaV10UpgradeReport $report $expectedServerSha256 $ExpectedCandidateSha256 `
    -DatabaseProduct $DatabaseProduct -PlatformEndpoint $PlatformEndpoint) 'The strict v10-to-v11 runtime report predicate rejected the report.'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$candidatePath = Join-Path $repositoryRoot 'build\libs\Syncmoney-1.4.0-alpha.1.jar'
$acceptancePath = Join-Path $repositoryRoot 'build\acceptance\SyncmoneyAcceptance.jar'
$apiConsumerPath = Join-Path $repositoryRoot 'build\acceptance\SyncmoneyApiConsumer.jar'
$server = Get-T21ObservedValue $report 'server'
$candidate = Get-T21ObservedValue $report 'candidate'
$acceptance = Get-T21ObservedValue $report 'acceptance'
$apiConsumer = Get-T21ObservedValue $report 'apiConsumer'
$candidatePathMatches = [string]::Equals([IO.Path]::GetFullPath([string](Get-T21ObservedValue $candidate 'path')),
    [IO.Path]::GetFullPath($candidatePath), $pathComparison)
Assert-T21AuditUpgradeOffline $candidatePathMatches `
    'The candidate artifact path is not the canonical build output.'
$artifactEvidence = [ordered]@{}
$platformName = switch -CaseSensitive ($platform.software) {
    'paper' { 'Paper' }
    'folia' { 'Folia' }
    'canvas' { 'Canvas' }
    default { throw "Unsupported pinned T21 platform software: $($platform.software)" }
}
$artifactEvidence.server = Assert-T21AuditUpgradeArtifact ([string](Get-T21ObservedValue $server 'path')) $expectedServerSha256 "$platformName server"
$artifactEvidence.candidate = Assert-T21AuditUpgradeArtifact $candidatePath $ExpectedCandidateSha256 'Syncmoney candidate'
$artifactEvidence.acceptance = Assert-T21AuditUpgradeArtifact $acceptancePath ([string](Get-T21ObservedValue $acceptance 'sha256')) 'Acceptance harness'
$artifactEvidence.apiConsumer = Assert-T21AuditUpgradeArtifact $apiConsumerPath ([string](Get-T21ObservedValue $apiConsumer 'sha256')) 'API consumer'
Assert-T21AuditUpgradeOffline ((Get-T21ObservedValue $candidate 'sha256') -ceq $artifactEvidence.candidate.sha256) `
    'The candidate artifact report pin does not match the rehashed JAR.'

$plugDev = Get-T21ObservedValue $report 'plugDev'
$plugDevRoot = [IO.Path]::GetFullPath([string](Get-T21ObservedValue $plugDev 'root'))
Assert-T21AuditUpgradeOffline (Test-Path -LiteralPath $plugDevRoot -PathType Container) 'The local PlugDev source root is missing.'
$currentPlugDevCommit = (& git -C $plugDevRoot rev-parse HEAD 2>$null | Out-String).Trim().ToLowerInvariant()
if ($LASTEXITCODE -ne 0 -or $currentPlugDevCommit -cne (Get-T21ObservedValue $plugDev 'sourceCommit')) {
    throw 'The local PlugDev source commit differs from the runtime report.'
}
$plugDevManifest = Get-T21PlugDevCliDistManifest $plugDevRoot
$reportedManifest = Get-T21ObservedValue $plugDev 'cliDistManifest'
Assert-T21AuditUpgradeOffline (Test-T21PlugDevCliDistManifest $reportedManifest ([string](Get-T21ObservedValue $plugDev 'cliDistManifestSha256Before')) ([string](Get-T21ObservedValue $plugDev 'cliSha256'))) `
    'The retained PlugDev CLI distribution manifest is invalid.'
Assert-T21AuditUpgradeOffline ($plugDevManifest.sha256 -ceq (Get-T21ObservedValue $plugDev 'cliDistManifestSha256Before') -and
    $plugDevManifest.sha256 -ceq (Get-T21ObservedValue $plugDev 'cliDistManifestSha256After')) `
    'The local PlugDev CLI distribution changed after runtime acceptance.'
$cliPath = Join-Path $plugDevRoot 'packages\cli\dist\cli.js'
$cliSha256 = Get-T21AuditUpgradeFileSha256 $cliPath
Assert-T21AuditUpgradeOffline ($cliSha256 -ceq (Get-T21ObservedValue $plugDev 'cliSha256')) 'The local PlugDev CLI artifact changed after runtime acceptance.'

$logs = Get-T21ObservedValue $report 'logs'
if ($logs -isnot [Collections.IDictionary] -or $logs.Count -lt 1) { throw 'The runtime report has no retained log manifest.' }
$logsRoot = [IO.Path]::GetFullPath((Join-Path $runRoot 'logs')).TrimEnd([char[]]@('\', '/'))
$auditedLogs = [Collections.Generic.List[object]]::new()
foreach ($relativePath in $logs.Keys) {
    if ($relativePath -isnot [string] -or [IO.Path]::IsPathRooted([string]$relativePath) -or
        ([string]$relativePath -split '[\\/]') -contains '..') { throw 'The runtime report contains an unsafe log path.' }
    $fullPath = [IO.Path]::GetFullPath((Join-Path $runRoot ([string]$relativePath)))
    if (-not $fullPath.StartsWith(($logsRoot + [IO.Path]::DirectorySeparatorChar), $pathComparison)) {
        throw "A retained log escapes the run-owned logs directory: $relativePath"
    }
    $actualSha256 = Get-T21AuditUpgradeFileSha256 $fullPath
    $logItem = Get-Item -LiteralPath $fullPath -ErrorAction Stop
    $declared = Get-T21ObservedValue $logs $relativePath
    if ((Get-T21ObservedValue $declared 'bytes') -ne [long]$logItem.Length -or
        ([string](Get-T21ObservedValue $declared 'sha256')).ToLowerInvariant() -cne $actualSha256) {
        throw "A retained runtime log failed its byte-count or SHA-256 audit: $relativePath"
    }
    $auditedLogs.Add([ordered]@{ path=[string]$relativePath; bytes=[long]$logItem.Length; sha256=$actualSha256 })
}

$processes = @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop | Where-Object {
    [int]$_.ProcessId -ne $PID -and $_.Name -in @('java.exe', 'javaw.exe', 'node.exe') -and $_.CommandLine -and
        ($_.CommandLine.IndexOf($runRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $_.CommandLine.IndexOf([string](Get-T21ObservedValue $report 'runId'), [StringComparison]::OrdinalIgnoreCase) -ge 0)
})
if ($processes.Count -ne 0) { throw "Found $($processes.Count) Java/Node process(es) still referencing the T21 runtime directory or run ID." }
$processQuery = [ordered]@{ observed=$true; javaNodeProcessesWithRunIdentity=0; rawCommandLinesRetained=$false }

$audit = [ordered]@{
    status='PASS'
    reportPath=$absoluteReportPath
    reportSha256=(Get-T21AuditUpgradeFileSha256 $absoluteReportPath)
    task=[string](Get-T21ObservedValue $report 'task')
    platform="$platformName $($platform.minecraftVersion)#$($platform.build)"
    databaseProduct=$DatabaseProduct
    artifacts=$artifactEvidence
    sourcePins=[ordered]@{
        runner=[string](Get-T21ObservedValue (Get-T21ObservedValue $report 'runner') 'sha256')
        validator=[string](Get-T21ObservedValue (Get-T21ObservedValue $report 'runner') 'auditSchemaUpgradeValidatorSha256')
        validatorTest=[string](Get-T21ObservedValue (Get-T21ObservedValue $report 'runner') 'auditSchemaUpgradeTestSha256')
        plugDevCommit=$currentPlugDevCommit
        plugDevCliSha256=$cliSha256
        plugDevDistManifestSha256=$plugDevManifest.sha256
    }
    logs=@($auditedLogs.ToArray())
    processQuery=$processQuery
    checkedAtUtc=[DateTime]::UtcNow.ToString('o')
}

$auditDirectory = Join-Path $runRoot 'validation'
New-Item -ItemType Directory -Force -Path $auditDirectory | Out-Null
$auditPath = Join-Path $auditDirectory 'offline-audit.json'
$auditTextPath = Join-Path $auditDirectory 'offline-audit.txt'
$audit | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $auditPath -Encoding utf8
$summary = "PASS: T21 successful audit schema v10-to-v11 offline audit; platform=$platformName product=$DatabaseProduct logs=$($auditedLogs.Count) reportSha256=$($audit.reportSha256)"
[IO.File]::WriteAllText($auditTextPath, $summary + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
Write-Output $summary
Write-Output "Audit: $auditTextPath"
