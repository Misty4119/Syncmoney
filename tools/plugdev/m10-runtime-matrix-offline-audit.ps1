param(
    [Parameter(Mandatory=$true)][string]$RepositoryRoot,
    [string]$PlugDevRoot = 'C:\Users\margo\IdeaProjects\plugdev-main',
    [string]$MineflayerRoot = 'C:\Users\margo\IdeaProjects\mineflayer-master',
    [switch]$SupplementalCurrentStableOnly,
    [string]$CurrentStableRunId
)
$ErrorActionPreference = 'Stop'
$RepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot)
$canonicalPlugDevRoot = [IO.Path]::GetFullPath('C:\Users\margo\IdeaProjects\plugdev-main')
$canonicalMineflayerRoot = [IO.Path]::GetFullPath('C:\Users\margo\IdeaProjects\mineflayer-master')
$expectedPlugDevRoot = [IO.Path]::GetFullPath($PlugDevRoot)
$expectedMineflayerRoot = [IO.Path]::GetFullPath($MineflayerRoot)
if (-not [string]::Equals($expectedPlugDevRoot, $canonicalPlugDevRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The M10 evidence audit is pinned to the configured local PlugDev checkout: $canonicalPlugDevRoot"
}
if (-not [string]::Equals($expectedMineflayerRoot, $canonicalMineflayerRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The M10 evidence audit is pinned to the configured local Mineflayer checkout: $canonicalMineflayerRoot"
}
if ($SupplementalCurrentStableOnly) {
    if ($CurrentStableRunId -notmatch '^paper-263-currentstable-[0-9]{8}-[0-9]{6}-[0-9a-f]{32}$') {
        throw 'SupplementalCurrentStableOnly requires the exact M10 paper-263-currentstable run ID.'
    }
} elseif (-not [string]::IsNullOrWhiteSpace($CurrentStableRunId)) {
    throw 'CurrentStableRunId is valid only with SupplementalCurrentStableOnly.'
}
$runtimeRoot = Join-Path $RepositoryRoot 'build/reports/foundation/T21-M10/runtime'
$reportTimeSourceRoot = Join-Path $runtimeRoot 'source-pins/report-time'
$expectedReportRunner = '6c1a60d2fd2afe92b12f06f261ef27e8a6d9ea163c09f24d00db3b326d361164'
$expectedCurrentRunner = 'a65c6c2e2515e2218b2793ad701cd3d378388edfb25e879348186d5c9a8f09e1'
$expectedReportObserver = 'd02ce11780123c00f1cddb3be20edd8b7fbf1d8d9c42ab9a5c7f44777fe177cf'
$expectedCurrentObserver = 'edaf79362470be7b3961922ad6c0f687d4a6686c88f26948fbdb17ae53eb4593'
$expectedCandidate = 'f57bf8535c628e77d7b12edc4926899e28c99c30d7a08b9da3e0036f0e254452'
$expectedCommit = '54165c4de5a4b03aae830ba2e344a920814cf7a7'
$expectedTree = '95eb700fca9f06d9e30efd47c3ddf52d9adc2404'
$expectedJavaMarker = '25.0.4+7-LTS-189'
$matrix = @(
    [pscustomobject]@{ RunId='paper-1216-min-20261014-151853-2556267b77b245f59d21cc1f6bc864ab'; Label='Paper 1.21.6#48'; ArtifactFile='paper-1.21.6-48.jar'; Artifact='35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214' },
    [pscustomobject]@{ RunId='paper-263-max-20261014-152619-9cae4856b84746d597b5c56accba2f14'; Label='Paper 26.3#166'; ArtifactFile='paper-26.3-166.jar'; Artifact='dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd' },
    [pscustomobject]@{ RunId='folia-1216-min-20261014-153251-b54b60dbc0b049a9b63b5aeffe4e2db1'; Label='Folia 1.21.6#6'; ArtifactFile='folia-1.21.6-6.jar'; Artifact='917a8abaa542c1d0ef0e969693e227a7bfe1c42e671aaa806309e00a64efc234' },
    [pscustomobject]@{ RunId='folia-262-max-20261014-153924-939e5411161445eb8504225b430f10a6'; Label='Folia 26.2#7'; ArtifactFile='folia-26.2-7.jar'; Artifact='128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3' },
    [pscustomobject]@{ RunId='canvas-1218-min-20261014-154543-b4bf95d399724455ad09be97b07acbcf'; Label='Canvas 1.21.8#603'; ArtifactFile='canvas-1.21.8-603.jar'; Artifact='6577181e4bc8ac40a7a48ff00e262f2ac7a798f48ada446a07eb06618dc36f1b' },
    [pscustomobject]@{ RunId='canvas-263-max-20261014-155301-4af1ed9de5574f36bd01edb5806a345a'; Label='Canvas 26.3#972'; ArtifactFile='canvas-26.3-972.jar'; Artifact='aac025fe36a61f84fecb04de74e94bbf933c7081c4c88f13a9d4b96de42243f5' }
)
if ($SupplementalCurrentStableOnly) {
    $expectedReportRunner = $expectedCurrentRunner
    $expectedCandidate = '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662'
    $expectedCommit = '3aa078203c659a8360791adececbfe40d0b8cdba'
    $expectedTree = 'd76026dfb7ed856a118305d815e0f121b1b1f75d'
    $matrix = @(
        [pscustomobject]@{
            RunId=$CurrentStableRunId
            Label='Paper 26.3#194 (CurrentStable)'
            ArtifactFile='paper-26.3-194.jar'
            Artifact='5fac0b6937b7aecea1b5b40e6a992f7b0802e4271413be35a6913d30df960784'
        }
    )
}
$script:assertions = 0
function Assert-M10([bool]$Condition, [string]$Name) {
    $script:assertions++
    if (-not $Condition) { throw "FAIL: $Name" }
}
function ConvertTo-M10WhitespaceNormalizedSource([string]$Source) {
    $normalized = $Source.Replace("`r`n", "`n").Replace("`r", "`n")
    $normalized = [regex]::Replace($normalized, '[\t ]+(?=\n)', '')
    return [regex]::Replace($normalized, '\n{2,}\z', "`n")
}
function Assert-M10ReportSourceEquivalent([string]$ReportSource, [string]$CurrentSource, [string]$Label) {
    $reportNormalized = ConvertTo-M10WhitespaceNormalizedSource $ReportSource
    $currentNormalized = ConvertTo-M10WhitespaceNormalizedSource $CurrentSource
    $equivalent = [string]::Equals($reportNormalized, $currentNormalized, [StringComparison]::Ordinal)
    Assert-M10 $equivalent "$Label current source differs beyond line-ending or trailing whitespace cleanup"
    return $equivalent
}
function Assert-M10MissedPubSubTransition([object]$Missed, [object]$Account, [string]$Label) {
    $staleBalanceText = [string]$Missed.oldMemory.balance
    $staleVersionText = [string]$Missed.oldMemory.version
    $authorityBalanceText = [string]$Missed.redisAuthority.balance
    $authorityVersionText = [string]$Missed.redisAuthority.version
    $balancePattern = '^-?[0-9]+\.[0-9]{2}$'
    $staleBalance = [decimal]0
    $authorityBalance = [decimal]0
    $staleBalanceValid = [decimal]::TryParse($staleBalanceText, [Globalization.NumberStyles]::Number,
        [Globalization.CultureInfo]::InvariantCulture, [ref]$staleBalance)
    $authorityBalanceValid = [decimal]::TryParse($authorityBalanceText, [Globalization.NumberStyles]::Number,
        [Globalization.CultureInfo]::InvariantCulture, [ref]$authorityBalance)
    $staleVersion = [long]0
    $authorityVersion = [long]0
    $versionPattern = '^(0|[1-9][0-9]*)$'
    $staleVersionValid = $staleVersionText -match $versionPattern -and [long]::TryParse($staleVersionText,
        [Globalization.NumberStyles]::None, [Globalization.CultureInfo]::InvariantCulture, [ref]$staleVersion)
    $authorityVersionValid = $authorityVersionText -match $versionPattern -and [long]::TryParse($authorityVersionText,
        [Globalization.NumberStyles]::None, [Globalization.CultureInfo]::InvariantCulture, [ref]$authorityVersion)

    Assert-M10 ($staleBalanceText -match $balancePattern -and $authorityBalanceText -match $balancePattern -and
        $staleBalanceValid -and $authorityBalanceValid -and $staleVersionValid -and $authorityVersionValid -and $staleVersion -ge 1) "$Label valid missed-update balance/version"
    Assert-M10 ($authorityBalance -eq ($staleBalance + [decimal]1.00) -and $authorityVersion -eq ($staleVersion + 1)) "$Label one missed deposit at Redis authority"
    Assert-M10 ([string]$Missed.oldMemory.uuid -eq [string]$Account.uuid) "$Label stale snapshot account identity"

    $samples = @($Missed.postReconnectObservations)
    Assert-M10 ($samples.Count -eq 3 -and @($samples | Where-Object {
        [string]$_.balance -cne $staleBalanceText -or [string]$_.version -cne $staleVersionText
    }).Count -eq 0) "$Label three stale post-reconnect samples"
    Assert-M10 ([string]$Account.authority.balance -ceq $authorityBalanceText -and [string]$Account.authority.version -ceq $authorityVersionText) "$Label final Redis authority matches missed update"
    Assert-M10 ([string]$Account.reconciled.balance -ceq $authorityBalanceText -and [string]$Account.reconciled.version -ceq $authorityVersionText) "$Label reconciliation matches Redis authority"
    return $true
}
function Assert-M10ReportIdentity([object]$Report, [string]$ExpectedRunId, [string]$Label) {
    Assert-M10 ([string]$Report.runId -ceq $ExpectedRunId) "$Label report run ID matches pinned matrix entry"
    return $true
}
function Assert-M10MissedPubSubProvenance([object]$Report, [string]$NodeALogText, [string]$NodeBLogText,
    [object[]]$ObserverEvents, [string]$Label) {
    $accountUuid = [string]$Report.account.uuid
    $uuidPattern = '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
    Assert-M10 ($accountUuid -match $uuidPattern) "$Label canonical account UUID"

    $missed = $Report.account.missedPubSub
    $publication = $missed.publication
    $probeRequestId = [string]$publication.probeRequestId
    Assert-M10 ($probeRequestId -match '^[0-9a-fA-F]{32}$') "$Label canonical publication probe request ID"
    Assert-M10 ([string]$publication.event -ceq 'publicationObserved' -and
        [string]$publication.channel -ceq 'syncmoney:balance:update' -and
        [string]$publication.eventType -ceq 'DEPOSIT' -and [string]$publication.sourceServer -ceq 'm10-a' -and
        [string]::Equals([string]$publication.uuid, $accountUuid, [StringComparison]::OrdinalIgnoreCase)) "$Label missed publication event, channel, source, and account identity"
    Assert-M10 ([string]$publication.balance -ceq [string]$missed.redisAuthority.balance -and
        [string]$publication.version -ceq [string]$missed.redisAuthority.version -and
        [string]$publication.balance -ceq [string]$Report.account.authority.balance -and
        [string]$publication.version -ceq [string]$Report.account.authority.version) "$Label publication pair matches Redis authority"
    Assert-M10 ([string]$publication.messageId -match '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' -and
        [string]$publication.payloadSha256 -match '^[0-9a-f]{64}$') "$Label publication identity and payload digest"

    $reportObservationMatches = @($Report.pubsubObservations | Where-Object {
        [string]::Equals([string]$_.probeRequestId, $probeRequestId, [StringComparison]::OrdinalIgnoreCase)
    })
    Assert-M10 ($reportObservationMatches.Count -eq 1 -and
        [string]::Equals([string]$reportObservationMatches[0].uuid, $accountUuid, [StringComparison]::OrdinalIgnoreCase) -and
        [string]$reportObservationMatches[0].balance -ceq [string]$publication.balance -and
        [string]$reportObservationMatches[0].version -ceq [string]$publication.version -and
        [string]$reportObservationMatches[0].channel -ceq [string]$publication.channel -and
        [string]$reportObservationMatches[0].messageId -ceq [string]$publication.messageId -and
        [string]$reportObservationMatches[0].payloadSha256 -ceq [string]$publication.payloadSha256) "$Label publication linked to report observer record"

    $observerMatches = @($ObserverEvents | Where-Object {
        [string]$_.event -ceq 'publicationObserved' -and
        [string]::Equals([string]$_.probeRequestId, $probeRequestId, [StringComparison]::OrdinalIgnoreCase)
    })
    Assert-M10 ($observerMatches.Count -eq 1 -and
        [string]$observerMatches[0].channel -ceq [string]$publication.channel -and
        [string]$observerMatches[0].eventType -ceq [string]$publication.eventType -and
        [string]::Equals([string]$observerMatches[0].uuid, $accountUuid, [StringComparison]::OrdinalIgnoreCase) -and
        [string]$observerMatches[0].balance -ceq [string]$publication.balance -and
        [string]$observerMatches[0].version -ceq [string]$publication.version -and
        [string]$observerMatches[0].sourceServer -ceq [string]$publication.sourceServer -and
        [string]$observerMatches[0].messageId -ceq [string]$publication.messageId -and
        [string]$observerMatches[0].payloadSha256 -ceq [string]$publication.payloadSha256) "$Label raw Pub/Sub observer event matches publication"

    $depositCommands = @($Report.commands | Where-Object {
        $parts = [regex]::Split(([string]$_.command).Trim(), '\s+')
        $parts.Count -eq 6 -and $parts[0] -ceq 'smaccept' -and $parts[1] -ceq 'm10' -and
            $parts[2] -ceq 'deposit' -and [string]::Equals($parts[5], $probeRequestId, [StringComparison]::OrdinalIgnoreCase)
    })
    Assert-M10 ($depositCommands.Count -eq 1 -and $depositCommands[0].node -ceq 'a' -and
        [int]$depositCommands[0].exitCode -eq 0) "$Label publication request linked to one successful node-A deposit command"
    if ($depositCommands.Count -eq 1) {
        $depositParts = [regex]::Split(([string]$depositCommands[0].command).Trim(), '\s+')
        Assert-M10 ([string]::Equals($depositParts[3], $accountUuid, [StringComparison]::OrdinalIgnoreCase) -and
            $depositParts[4] -ceq '1.00') "$Label accepted deposit command account and amount"
    }
    $depositMarkerPattern = 'T21_M10_DEPOSIT_PASS request=' + [regex]::Escape($probeRequestId) +
        '[ \t]+uuid=(?<uuid>[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})' +
        '[ \t]+amount=(?<amount>[0-9]+\.[0-9]{2})[ \t]+balance=(?<balance>[0-9]+\.[0-9]{2})[ \t]+version=(?<version>[0-9]+)'
    $depositMarkers = [regex]::Matches($NodeALogText, $depositMarkerPattern)
    Assert-M10 ($depositMarkers.Count -eq 1) "$Label successful deposit receipt marker in node-A server log"
    if ($depositMarkers.Count -eq 1) {
        $depositMarker = $depositMarkers[0]
        Assert-M10 ([string]::Equals($depositMarker.Groups['uuid'].Value, $accountUuid, [StringComparison]::OrdinalIgnoreCase) -and
            $depositMarker.Groups['amount'].Value -ceq '1.00' -and
            $depositMarker.Groups['balance'].Value -ceq [string]$publication.balance -and
            $depositMarker.Groups['version'].Value -ceq [string]$publication.version) "$Label deposit receipt identity and resulting pair"
    }

    $samples = @($missed.postReconnectObservations)
    Assert-M10 ($samples.Count -eq 3) "$Label expected stale snapshot samples"
    for ($index = 0; $index -lt $samples.Count; $index++) {
        $sample = $samples[$index]
        $requestId = [string]$sample.requestId
        Assert-M10 ($requestId -match '^[0-9a-fA-F]{32}$' -and [int]$sample.sample -eq ($index + 1)) "$Label stale sample $($index + 1) request identity"
        $snapshotCommands = @($Report.commands | Where-Object {
            $parts = [regex]::Split(([string]$_.command).Trim(), '\s+')
            $parts.Count -eq 5 -and $parts[0] -ceq 'smaccept' -and $parts[1] -ceq 'm10' -and
                $parts[2] -ceq 'snapshot' -and $parts[3] -ceq 'AcceptanceM10' -and
                [string]::Equals($parts[4], $requestId, [StringComparison]::OrdinalIgnoreCase)
        })
        Assert-M10 ($snapshotCommands.Count -eq 1 -and $snapshotCommands[0].node -ceq 'b' -and
            [int]$snapshotCommands[0].exitCode -eq 0) "$Label stale sample $($index + 1) linked to one successful node-B snapshot command"

        $memoryPattern = 'T21_M10_MEMORY request=' + [regex]::Escape($requestId) +
            '[ \t]+name=AcceptanceM10[ \t]+uuid=(?<uuid>[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})' +
            '[ \t]+balance=(?<balance>[0-9]+(?:\.[0-9]{2})?)[ \t]+version=(?<version>[0-9]+)'
        $memoryMarkers = [regex]::Matches($NodeBLogText, $memoryPattern)
        Assert-M10 ($memoryMarkers.Count -eq 1) "$Label stale sample $($index + 1) unique server-log marker"
        if ($memoryMarkers.Count -eq 1) {
            $memoryMarker = $memoryMarkers[0]
            Assert-M10 ([string]::Equals($memoryMarker.Groups['uuid'].Value, $accountUuid, [StringComparison]::OrdinalIgnoreCase) -and
                $memoryMarker.Groups['balance'].Value -ceq [string]$sample.balance -and
                $memoryMarker.Groups['version'].Value -ceq [string]$sample.version) "$Label stale sample $($index + 1) server-log account and snapshot pair"
        }
    }
    Assert-M10 ([string]::Equals([string]$missed.oldMemory.requestId, [string]$samples[2].requestId, [StringComparison]::OrdinalIgnoreCase) -and
        [string]::Equals([string]$missed.oldMemory.uuid, $accountUuid, [StringComparison]::OrdinalIgnoreCase)) "$Label final stale sample matches old memory snapshot"
    return $true
}
function Assert-M10ReportedPath([string]$ReportedPath, [string]$ExpectedPath, [string]$Label) {
    $reportedFullPath = [IO.Path]::GetFullPath($ReportedPath)
    $expectedFullPath = [IO.Path]::GetFullPath($ExpectedPath)
    Assert-M10 ([string]::Equals($reportedFullPath, $expectedFullPath, [StringComparison]::OrdinalIgnoreCase)) "$Label path pin"
    return $expectedFullPath
}
function Assert-M10AuditNoReparsePath([string]$Path) {
    $cursor = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $cursor)) { throw "M10 audit provenance path does not exist: $cursor" }
    while (-not [string]::IsNullOrWhiteSpace($cursor)) {
        $item = Get-Item -LiteralPath $cursor -Force -ErrorAction Stop
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "M10 audit provenance path contains a reparse point: $cursor" }
        $parent = [IO.Directory]::GetParent($cursor)
        if ($null -eq $parent -or [string]::Equals($parent.FullName, $cursor, [StringComparison]::OrdinalIgnoreCase)) { break }
        $cursor = $parent.FullName
    }
    return [IO.Path]::GetFullPath($Path)
}
function Assert-M10AuditExpectedPlugDevRoot([string]$ObservedRoot, [string]$ExpectedRoot) {
    $observedFullPath = [IO.Path]::GetFullPath($ObservedRoot)
    $expectedFullPath = [IO.Path]::GetFullPath($ExpectedRoot)
    if (-not [string]::Equals($observedFullPath, $expectedFullPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Reported M10 PlugDev root does not match the explicit configured checkout: $observedFullPath"
    }
    $null = Assert-M10AuditNoReparsePath $observedFullPath
    $packageJson = Join-Path $observedFullPath 'package.json'
    $null = Assert-M10AuditNoReparsePath $packageJson
    $package = Get-Content -LiteralPath $packageJson -Raw | ConvertFrom-Json
    if ($package.private -ne $true) { throw 'Reported M10 PlugDev checkout is not the local private project.' }
    return $package
}
function Assert-M10AuditExpectedMineflayerRoot([string]$ObservedRoot, [string]$ExpectedRoot) {
    $observedFullPath = [IO.Path]::GetFullPath($ObservedRoot)
    $expectedFullPath = [IO.Path]::GetFullPath($ExpectedRoot)
    if (-not [string]::Equals($observedFullPath, $expectedFullPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Reported M10 Mineflayer root does not match the explicit configured checkout: $observedFullPath"
    }
    $null = Assert-M10AuditNoReparsePath $observedFullPath
    $packageJson = Join-Path $observedFullPath 'package.json'
    $null = Assert-M10AuditNoReparsePath $packageJson
    $package = Get-Content -LiteralPath $packageJson -Raw | ConvertFrom-Json
    if ($package.name -ne 'mineflayer' -or [string]::IsNullOrWhiteSpace([string]$package.version)) {
        throw 'Reported M10 Mineflayer checkout is not the expected local Mineflayer project.'
    }
    return $package
}
function Get-M10CliDistManifest([string]$CliDistRoot) {
    if (-not (Test-Path -LiteralPath $CliDistRoot -PathType Container)) { throw "PlugDev CLI dist directory is missing: $CliDistRoot" }
    $root = [IO.Path]::GetFullPath($CliDistRoot)
    $null = Assert-M10AuditNoReparsePath $root
    $prefix = $root.TrimEnd([char[]]@('\', '/')) + [IO.Path]::DirectorySeparatorChar
    $directories = @(Get-ChildItem -LiteralPath $root -Directory -Recurse -Force -ErrorAction Stop)
    if (@($directories | Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 }).Count -gt 0) {
        throw 'PlugDev CLI dist contains a reparse-point directory.'
    }
    $files = @(Get-ChildItem -LiteralPath $root -File -Recurse -Force -ErrorAction Stop | Sort-Object FullName)
    if ($files.Count -eq 0) { throw 'PlugDev CLI dist has no files.' }
    $entries = [System.Collections.Generic.List[object]]::new()
    foreach ($file in $files) {
        if (($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "PlugDev CLI dist file is a reparse point: $($file.FullName)" }
        $fullPath = [IO.Path]::GetFullPath($file.FullName)
        if (-not $fullPath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "PlugDev CLI dist file escapes its root: $fullPath" }
        $relativePath = $fullPath.Substring($prefix.Length).Replace('\', '/')
        $entries.Add([ordered]@{
            path = $relativePath
            length = [long]$file.Length
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $fullPath).Hash.ToLowerInvariant()
        })
    }
    return $entries.ToArray()
}
function Assert-M10CliDistManifest([string]$CliDistRoot, [object[]]$ReportedManifest, [string]$ExpectedCliSha256, [string]$Label) {
    $actual = @(Get-M10CliDistManifest $CliDistRoot)
    $reported = @($ReportedManifest)
    Assert-M10 ($reported.Count -gt 0 -and $actual.Count -eq $reported.Count) "$Label PlugDev CLI dist manifest count"
    for ($index = 0; $index -lt $actual.Count; $index++) {
        $actualEntry = $actual[$index]
        $reportedEntry = $reported[$index]
        $relativePath = [string]$reportedEntry.path
        Assert-M10 ($relativePath -ceq [string]$actualEntry.path -and $relativePath -notmatch '(^|/)\.\.(/|$)' -and
            -not [IO.Path]::IsPathRooted($relativePath) -and [long]$reportedEntry.length -eq [long]$actualEntry.length -and
            [string]$reportedEntry.sha256 -ceq [string]$actualEntry.sha256) "$Label PlugDev CLI dist file $relativePath"
    }
    $reportedCliEntry = @($reported | Where-Object { [string]$_.path -ceq 'cli.js' })
    Assert-M10 ($reportedCliEntry.Count -eq 1 -and [string]$reportedCliEntry[0].sha256 -ceq $ExpectedCliSha256) "$Label PlugDev CLI manifest entrypoint cross-check"
    return $true
}
function ConvertTo-M10ObservationTime([string]$Value) {
    $parsed = [DateTimeOffset]::MinValue
    $valid = [DateTimeOffset]::TryParse($Value, [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::RoundtripKind, [ref]$parsed)
    if (-not $valid) { throw "Invalid M10 observation timestamp: $Value" }
    return $parsed.ToUniversalTime()
}
$runnerPath = Join-Path $RepositoryRoot 'tools/plugdev/run-m10-reconciliation.ps1'
$reportRunnerSourcePath = if ($SupplementalCurrentStableOnly) {
    Join-Path (Join-Path (Join-Path $runtimeRoot $CurrentStableRunId) 'source-pins') 'run-m10-reconciliation.ps1'
} else {
    Join-Path $reportTimeSourceRoot 'run-m10-reconciliation.ps1'
}
$observerPath = Join-Path $RepositoryRoot 'tools/plugdev/m10-redis-pubsub-observer.mjs'
$reportObserverSourcePath = if ($SupplementalCurrentStableOnly) {
    $expectedReportObserver = $expectedCurrentObserver
    $observerPath
} else {
    Join-Path $reportTimeSourceRoot 'm10-redis-pubsub-observer.mjs'
}
Assert-M10 (Test-Path -LiteralPath $reportRunnerSourcePath) 'report-time runner source snapshot exists'
Assert-M10 (Test-Path -LiteralPath $reportObserverSourcePath) 'report-time observer source snapshot exists'
$null = Assert-M10AuditNoReparsePath $reportRunnerSourcePath
$null = Assert-M10AuditNoReparsePath $reportObserverSourcePath
Assert-M10 ((Get-FileHash -Algorithm SHA256 -LiteralPath $reportRunnerSourcePath).Hash.ToLowerInvariant() -eq $expectedReportRunner) 'report-time runner source snapshot sha'
Assert-M10 ((Get-FileHash -Algorithm SHA256 -LiteralPath $runnerPath).Hash.ToLowerInvariant() -eq $expectedCurrentRunner) 'current runner sha'
if ($SupplementalCurrentStableOnly) {
    $null = Assert-M10ReportSourceEquivalent (Get-Content -LiteralPath $reportRunnerSourcePath -Raw) (Get-Content -LiteralPath $runnerPath -Raw) 'CurrentStable M10 runner'
}
Assert-M10 ((Get-FileHash -Algorithm SHA256 -LiteralPath $reportObserverSourcePath).Hash.ToLowerInvariant() -eq $expectedReportObserver) 'report-time observer source snapshot sha'
Assert-M10 ((Get-FileHash -Algorithm SHA256 -LiteralPath $observerPath).Hash.ToLowerInvariant() -eq $expectedCurrentObserver) 'current observer sha'
$null = Assert-M10ReportSourceEquivalent (Get-Content -LiteralPath $reportObserverSourcePath -Raw) (Get-Content -LiteralPath $observerPath -Raw) 'M10 observer'
foreach ($entry in $matrix) {
    $path = Join-Path (Join-Path $runtimeRoot $entry.RunId) 'result.json'
    Assert-M10 (Test-Path -LiteralPath $path) "$($entry.Label) report exists"
    $report = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    $null = Assert-M10ReportIdentity $report $entry.RunId $entry.Label
    Assert-M10 ($report.status -eq 'PASS_BOUNDED_SUBCASE') "$($entry.Label) status"
    Assert-M10 ($report.endpoint.label -eq $entry.Label) "$($entry.Label) endpoint label"
    Assert-M10 ($report.endpoint.serverSha256.ToLowerInvariant() -eq $entry.Artifact) "$($entry.Label) artifact pin"
    $expectedEndpointJar = if ($SupplementalCurrentStableOnly) {
        Join-Path $RepositoryRoot 'build/reports/foundation/T21-M10/artifacts/paper-26.3-194.jar'
    } else {
        Join-Path $RepositoryRoot ('build/reports/foundation/T15-runtime-pins/artifacts/' + $entry.ArtifactFile)
    }
    $endpointJar = Assert-M10ReportedPath $report.endpoint.serverJar $expectedEndpointJar "$($entry.Label) server artifact"
    $null = Assert-M10ReportedPath $report.endpoint.path $expectedEndpointJar "$($entry.Label) observed server artifact"
    Assert-M10 ((Get-FileHash -Algorithm SHA256 -LiteralPath $endpointJar).Hash.ToLowerInvariant() -eq $entry.Artifact) "$($entry.Label) artifact bytes"
    Assert-M10 ($report.sourceCommit -eq $expectedCommit -and $report.sourceTree -eq $expectedTree) "$($entry.Label) source commit/tree"
    Assert-M10 ($report.candidate.sha256.ToLowerInvariant() -eq $expectedCandidate) "$($entry.Label) candidate pin"
    $expectedCandidatePath = Join-Path $RepositoryRoot 'build/libs/Syncmoney-1.4.0-alpha.1.jar'
    $candidatePath = Assert-M10ReportedPath $report.candidate.path $expectedCandidatePath "$($entry.Label) candidate artifact"
    Assert-M10 ((Get-FileHash -Algorithm SHA256 -LiteralPath $candidatePath).Hash.ToLowerInvariant() -eq $expectedCandidate) "$($entry.Label) candidate bytes"
    Assert-M10 ($report.acceptance.runnerSha256.ToLowerInvariant() -eq $expectedReportRunner) "$($entry.Label) report runner pin"
    if ($SupplementalCurrentStableOnly) {
        $null = Assert-M10ReportedPath $report.acceptance.runnerSourceSnapshotPath $reportRunnerSourcePath "$($entry.Label) runner snapshot path"
    }
    Assert-M10 ($report.java.version.Contains($expectedJavaMarker)) "$($entry.Label) Java runtime pin"
    $expectedAcceptancePath = Join-Path $RepositoryRoot 'build/acceptance/SyncmoneyAcceptance.jar'
    $acceptancePath = Assert-M10ReportedPath $report.acceptance.path $expectedAcceptancePath "$($entry.Label) acceptance artifact"
    Assert-M10 ($report.acceptance.sha256 -and (Get-FileHash -Algorithm SHA256 -LiteralPath $acceptancePath).Hash.ToLowerInvariant() -eq $report.acceptance.sha256.ToLowerInvariant()) "$($entry.Label) acceptance jar"
    $expectedApiConsumerPath = Join-Path $RepositoryRoot 'build/acceptance/SyncmoneyApiConsumer.jar'
    $apiConsumerPath = Assert-M10ReportedPath $report.acceptance.apiConsumerPath $expectedApiConsumerPath "$($entry.Label) API consumer artifact"
    Assert-M10 ($report.acceptance.apiConsumerSha256 -and (Get-FileHash -Algorithm SHA256 -LiteralPath $apiConsumerPath).Hash.ToLowerInvariant() -eq $report.acceptance.apiConsumerSha256.ToLowerInvariant()) "$($entry.Label) API consumer jar"
    $expectedIdentityHelper = Join-Path $RepositoryRoot 'tools/plugdev/m10-online-client-identity.mjs'
    $expectedIdentityHelperTest = Join-Path $RepositoryRoot 'tools/plugdev/m10-online-client-identity.test.mjs'
    $expectedObserver = $observerPath
    $expectedRunnerTest = Join-Path $RepositoryRoot 'tools/plugdev/run-m10-reconciliation.test.ps1'
    $null = Assert-M10ReportedPath $report.acceptance.identityHelperPath $expectedIdentityHelper "$($entry.Label) identity helper source"
    $null = Assert-M10ReportedPath $report.acceptance.identityHelperTestPath $expectedIdentityHelperTest "$($entry.Label) identity helper test source"
    $null = Assert-M10ReportedPath $report.acceptance.observerPath $expectedObserver "$($entry.Label) observer source"
    $null = Assert-M10ReportedPath $report.acceptance.runnerTestPath $expectedRunnerTest "$($entry.Label) runner test source"
    $sourcePins = @(
        [pscustomobject]@{ Path=(Join-Path $RepositoryRoot 'src/acceptance/java/noietime/syncmoney/acceptance/AcceptanceProbe.java'); Sha=$report.acceptance.probeSourceSha256 },
        [pscustomobject]@{ Path=(Join-Path $RepositoryRoot 'tools/plugdev/m10-online-client.mjs'); Sha=$report.acceptance.clientSourceSha256 },
        [pscustomobject]@{ Path=$expectedIdentityHelper; Sha=$report.acceptance.identityHelperSourceSha256 },
        [pscustomobject]@{ Path=$expectedIdentityHelperTest; Sha=$report.acceptance.identityHelperTestSha256 },
        [pscustomobject]@{ Path=$reportObserverSourcePath; Sha=$report.acceptance.observerSourceSha256 },
        [pscustomobject]@{ Path=$expectedRunnerTest; Sha=$report.acceptance.runnerTestSha256 },
        [pscustomobject]@{ Path=$reportRunnerSourcePath; Sha=$report.acceptance.runnerSha256 },
        [pscustomobject]@{ Path=(Join-Path $RepositoryRoot 'tools/plugdev/prepare.mjs'); Sha=$report.acceptance.prepareSha256 }
    )
    foreach ($pin in $sourcePins) {
        Assert-M10 ($pin.Sha -and (Get-FileHash -Algorithm SHA256 -LiteralPath $pin.Path).Hash.ToLowerInvariant() -eq $pin.Sha.ToLowerInvariant()) "$($entry.Label) source pin $($pin.Path)"
    }
    Assert-M10 ($report.mineflayer.workingTreeClean -eq $true) "$($entry.Label) Mineflayer clean claim"
    $mineflayerPackage = Assert-M10AuditExpectedMineflayerRoot $report.mineflayer.root $expectedMineflayerRoot
    $mineflayerPackageJson = Join-Path $expectedMineflayerRoot 'package.json'
    $mineflayerPackageLock = Join-Path $expectedMineflayerRoot 'package-lock.json'
    Assert-M10 ($mineflayerPackage.version -eq $report.mineflayer.version -and $mineflayerPackage.name -eq 'mineflayer') "$($entry.Label) local Mineflayer package metadata"
    Assert-M10 ($report.mineflayer.dependencyInstallation -eq 'LOCAL_NODE_MODULES_NOT_HASHED') "$($entry.Label) Mineflayer dependency provenance limitation"
    Assert-M10 ((Get-FileHash -Algorithm SHA256 -LiteralPath $mineflayerPackageJson).Hash.ToLowerInvariant() -eq $report.mineflayer.packageJsonSha256.ToLowerInvariant()) "$($entry.Label) Mineflayer package pin"
    Assert-M10 ([IO.Path]::GetFullPath($report.mineflayer.packageLockPath) -eq [IO.Path]::GetFullPath($mineflayerPackageLock)) "$($entry.Label) Mineflayer lock path"
    Assert-M10 ((Get-FileHash -Algorithm SHA256 -LiteralPath $mineflayerPackageLock).Hash.ToLowerInvariant() -eq $report.mineflayer.packageLockSha256.ToLowerInvariant()) "$($entry.Label) Mineflayer lock pin"
    $mineflayerEntryPath = Join-Path $expectedMineflayerRoot ([string]$mineflayerPackage.main)
    Assert-M10 ([IO.Path]::GetFullPath($report.mineflayer.entrypointPath) -eq [IO.Path]::GetFullPath($mineflayerEntryPath)) "$($entry.Label) Mineflayer entrypoint path"
    Assert-M10 ((Get-FileHash -Algorithm SHA256 -LiteralPath $mineflayerEntryPath).Hash.ToLowerInvariant() -eq $report.mineflayer.entrypointSha256.ToLowerInvariant()) "$($entry.Label) Mineflayer entrypoint pin"
    $resolvedMineflayerPath = Assert-M10ReportedPath $report.mineflayer.resolvedRequirePath $mineflayerEntryPath "$($entry.Label) Mineflayer resolved require entrypoint"
    Assert-M10 ((Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedMineflayerPath).Hash.ToLowerInvariant() -eq $report.mineflayer.entrypointSha256.ToLowerInvariant()) "$($entry.Label) resolved Mineflayer entrypoint pin"
    $mineflayerCommit = (& git -C $expectedMineflayerRoot rev-parse HEAD 2>$null | Out-String).Trim()
    $mineflayerCommitExitCode = $LASTEXITCODE
    Assert-M10 ($mineflayerCommitExitCode -eq 0 -and $mineflayerCommit -eq $report.mineflayer.commit) "$($entry.Label) local Mineflayer commit"
    $mineflayerWorkingTree = @(& git -C $expectedMineflayerRoot status --porcelain=v1 --untracked-files=all 2>$null)
    $mineflayerWorkingTreeExitCode = $LASTEXITCODE
    Assert-M10 ($mineflayerWorkingTreeExitCode -eq 0 -and $report.mineflayer.workingTreeClean -eq $true -and $mineflayerWorkingTree.Count -eq 0) "$($entry.Label) Mineflayer clean source checkout"
    $plugDevPackage = Assert-M10AuditExpectedPlugDevRoot $report.plugDev.root $expectedPlugDevRoot
    $plugDevCommit = (& git -C $expectedPlugDevRoot rev-parse HEAD 2>$null | Out-String).Trim()
    $plugDevCommitExitCode = $LASTEXITCODE
    Assert-M10 ($plugDevCommitExitCode -eq 0 -and $plugDevCommit -eq $report.plugDev.commit) "$($entry.Label) local PlugDev commit"
    $plugDevPackageJson = Join-Path $expectedPlugDevRoot 'package.json'
    Assert-M10 ($plugDevPackage.version -eq $report.plugDev.version -and $plugDevPackage.private -eq $true) "$($entry.Label) local private PlugDev package metadata"
    Assert-M10 ((Get-FileHash -Algorithm SHA256 -LiteralPath $plugDevPackageJson).Hash.ToLowerInvariant() -eq $report.plugDev.packageJsonSha256.ToLowerInvariant()) "$($entry.Label) PlugDev package.json hash"
    $plugDevWorkingTree = @(& git -C $expectedPlugDevRoot status --porcelain=v1 --untracked-files=all 2>$null)
    $plugDevWorkingTreeExitCode = $LASTEXITCODE
    Assert-M10 ($plugDevWorkingTreeExitCode -eq 0 -and $report.plugDev.workingTreeClean -eq $true -and
        [int]$report.plugDev.workingTreeStatusEntryCount -eq 0 -and $plugDevWorkingTree.Count -eq 0) "$($entry.Label) PlugDev clean source checkout"
    $plugDevCli = Join-Path $expectedPlugDevRoot 'packages/cli/dist/cli.js'
    $plugDevCliDistRoot = Join-Path $expectedPlugDevRoot 'packages/cli/dist'
    $null = Assert-M10ReportedPath $report.plugDev.cli $plugDevCli "$($entry.Label) PlugDev CLI"
    $null = Assert-M10ReportedPath $report.plugDev.cliDistRoot $plugDevCliDistRoot "$($entry.Label) PlugDev CLI dist"
    Assert-M10 ($report.plugDev.cliSha256 -match '^[0-9a-f]{64}$' -and
        (Get-FileHash -Algorithm SHA256 -LiteralPath $plugDevCli).Hash.ToLowerInvariant() -eq $report.plugDev.cliSha256.ToLowerInvariant()) "$($entry.Label) PlugDev CLI entrypoint hash"
    $reportedPlugDevCliManifest = @($report.plugDev.cliDistManifest)
    $null = Assert-M10CliDistManifest $plugDevCliDistRoot $reportedPlugDevCliManifest ([string]$report.plugDev.cliSha256) $entry.Label
    Assert-M10 ($report.pubsubDisconnectAttempts.Count -gt 0) "$($entry.Label) disconnect evidence"
    $missed = $report.account.missedPubSub
    Assert-M10 ($null -ne $missed) "$($entry.Label) missed Pub/Sub event"
    Assert-M10 ($missed.subscriberReconnected -eq $true -and $missed.replayObserved -eq $false) "$($entry.Label) reconnect without replay"
    $null = Assert-M10MissedPubSubTransition $missed $report.account $entry.Label
    $runRoot = Join-Path $runtimeRoot $entry.RunId
    $expectedNodeALog = Join-Path $runRoot 'node-a/.plugdev/run/logs/latest.log'
    $expectedNodeBLog = Join-Path $runRoot 'node-b/.plugdev/run/logs/latest.log'
    $nodeALogPath = Assert-M10ReportedPath $report.nodes.a.serverLog $expectedNodeALog "$($entry.Label) node-A server log"
    $nodeBLogPath = Assert-M10ReportedPath $report.nodes.b.serverLog $expectedNodeBLog "$($entry.Label) node-B server log"
    $null = Assert-M10AuditNoReparsePath $nodeALogPath
    $null = Assert-M10AuditNoReparsePath $nodeBLogPath
    $observerRequestId = [string]$missed.publication.probeRequestId
    $observerReportEntries = @($report.pubsubObservations | Where-Object {
        [string]::Equals([string]$_.probeRequestId, $observerRequestId, [StringComparison]::OrdinalIgnoreCase)
    })
    Assert-M10 ($observerReportEntries.Count -eq 1) "$($entry.Label) exact missed-publication observer report entry"
    $expectedObserverLog = Join-Path $runRoot "pubsub-observer-$observerRequestId.jsonl"
    $observerLogPath = Assert-M10ReportedPath $observerReportEntries[0].log $expectedObserverLog "$($entry.Label) missed-publication observer log"
    $null = Assert-M10AuditNoReparsePath $observerLogPath
    $observerEvents = [System.Collections.Generic.List[object]]::new()
    foreach ($line in (Get-Content -LiteralPath $observerLogPath)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $observerEvents.Add(($line | ConvertFrom-Json))
    }
    $null = Assert-M10MissedPubSubProvenance $report (Get-Content -LiteralPath $nodeALogPath -Raw) `
        (Get-Content -LiteralPath $nodeBLogPath -Raw) $observerEvents.ToArray() $entry.Label
    $samples = @($missed.postReconnectObservations)
    $sampleRequestIds = @($samples | ForEach-Object { [string]$_.requestId })
    $uniqueSampleRequestIds = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($requestId in $sampleRequestIds) { $null = $uniqueSampleRequestIds.Add($requestId) }
    Assert-M10 ($sampleRequestIds.Count -eq 3 -and @($sampleRequestIds | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -eq 0 -and
        $uniqueSampleRequestIds.Count -eq 3) "$($entry.Label) distinct stale-sample request IDs"
    $sampleTimes = @($samples | ForEach-Object { ConvertTo-M10ObservationTime ([string]$_.observedAtUtc) })
    Assert-M10 ($sampleTimes.Count -eq 3 -and $sampleTimes[0] -lt $sampleTimes[1] -and $sampleTimes[1] -lt $sampleTimes[2]) "$($entry.Label) ordered stale-sample timestamps"
    Assert-M10 ($report.account.elapsedFromPluginEnableSeconds -ge 290) "$($entry.Label) periodic lower bound"
    $commandTexts = @($report.commands | ForEach-Object { [string]$_.command })
    Assert-M10 ($commandTexts.Count -ge 13 -and @($report.commands | Where-Object { $_.exitCode -ne 0 }).Count -eq 0) "$($entry.Label) command results"
    Assert-M10 (@($commandTexts | Where-Object { $_ -match '^smaccept m10 ready ' }).Count -eq 1 -and
        @($commandTexts | Where-Object { $_ -match '^smaccept m10 warm ' }).Count -eq 1 -and
        @($commandTexts | Where-Object { $_ -match '^smaccept m10 deposit ' }).Count -ge 2 -and
        @($commandTexts | Where-Object { $_ -match '^smaccept m10 snapshot ' }).Count -ge 6 -and
        @($commandTexts | Where-Object { $_ -match '^smaccept m10 await ' }).Count -eq 1 -and
        @($commandTexts | Where-Object { $_ -ceq 'stop' }).Count -eq 2) "$($entry.Label) required acceptance and shutdown commands"
    Assert-M10 ($report.shutdown.Count -eq 2 -and (@($report.shutdown | ForEach-Object node | Sort-Object) -join ',') -eq 'a,b') "$($entry.Label) both node shutdowns"
    Assert-M10 (@($report.shutdown | Where-Object { -not $_.processExitVerified -or -not $_.pluginDrainVerified -or $_.forced -or -not $_.listenerCheck.closed -or $_.recoveryJournal.pendingCount -ne 0 -or $_.recoveryJournal.validation -notin @('PASS_EMPTY','PASS_FORMAT_AND_CHECKSUM') }).Count -eq 0) "$($entry.Label) clean drain/journal/listener state"
    Assert-M10 (@($report.cleanup | Where-Object { $_.forced }).Count -eq 0) "$($entry.Label) no forced cleanup"
    Assert-M10 ($report.redis.cleanShutdown -eq $true) "$($entry.Label) isolated Redis clean shutdown"
    Assert-M10 ($null -eq $report.failure -and $null -eq $report.cleanupFailure) "$($entry.Label) no failures"
    $reportHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
    Write-Output ("PASS $($entry.Label) reportSha256=$reportHash elapsed=$($report.account.elapsedFromPluginEnableSeconds)s commands=$($report.commands.Count)")
}
Write-Output ("PASS_OFFLINE_AUDIT assertions=$script:assertions endpoints=$($matrix.Count)")
