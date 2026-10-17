$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$auditPath = Join-Path $PSScriptRoot 'm10-runtime-matrix-offline-audit.ps1'
$auditSource = Get-Content -LiteralPath $auditPath -Raw
$tokens = $null
$parseErrors = $null
$auditAst = [System.Management.Automation.Language.Parser]::ParseInput($auditSource, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) { throw "M10 offline audit has PowerShell parse errors: $($parseErrors -join '; ')" }
foreach ($stableAuditMarker in @(
        '[switch]$SupplementalCurrentStableOnly',
        '[string]$CurrentStableRunId',
        '^paper-263-currentstable-[0-9]{8}-[0-9]{6}-[0-9a-f]{32}$',
        'Paper 26.3#194 (CurrentStable)',
        'build/reports/foundation/T21-M10/artifacts/paper-26.3-194.jar',
        'runnerSourceSnapshotPath',
        '$expectedReportObserver = $expectedCurrentObserver')) {
    if (-not $auditSource.Contains($stableAuditMarker, [StringComparison]::Ordinal)) {
        throw "M10 offline audit is missing the supplemental CurrentStable report contract: $stableAuditMarker"
    }
}
if (-not $auditSource.Contains("`$expectedCurrentStableRunner = 'a65c6c2e2515e2218b2793ad701cd3d378388edfb25e879348186d5c9a8f09e1'", [StringComparison]::Ordinal) -or
    -not $auditSource.Contains("`$expectedCurrentRunner = 'f0d184eebea376fc5a0d357ab81a8315f9506d12f6ff5696cbdd52a7920b094c'", [StringComparison]::Ordinal)) {
    throw 'M10 offline audit does not keep the historical CurrentStable runner snapshot pin separate from the current selector-capable runner pin.'
}

$script:assertions = 0
foreach ($helperName in @('Assert-M10', 'ConvertTo-M10WhitespaceNormalizedSource', 'Assert-M10MissedPubSubTransition', 'Assert-M10ReportIdentity', 'Assert-M10MissedPubSubProvenance', 'Assert-M10ReportSourceEquivalent', 'Assert-M10ReportedPath', 'Assert-M10AuditNoReparsePath', 'Assert-M10AuditExpectedPlugDevRoot', 'Assert-M10AuditExpectedMineflayerRoot', 'Get-M10CliDistManifest', 'Assert-M10CliDistManifest')) {
    $helperAst = $auditAst.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $helperName
    }, $true)
    if ($null -eq $helperAst) { throw "M10 offline audit helper is missing: $helperName" }
    . ([scriptblock]::Create($helperAst.Extent.Text))
}

function Assert-Throws([scriptblock]$Action, [string]$Message) {
    $thrown = $false
    try { & $Action } catch { $thrown = $true }
    if (-not $thrown) { throw $Message }
}

$reportSource = "function Set-M10Probe { `r`n    return `$true`r`n}`r`n`r`n"
$currentSource = "function Set-M10Probe {`n    return `$true`n}`n"
$null = Assert-M10ReportSourceEquivalent $reportSource $currentSource 'whitespace-only report source fixture'
Assert-Throws {
    Assert-M10ReportSourceEquivalent $reportSource $currentSource.Replace('$true', '$false') 'semantic report source fixture'
} 'M10 offline audit accepted a non-whitespace source change as report-time equivalent.'

$m10TestUuid = '1ef97e85-b60f-3c2d-8fca-45edad0b7102'
foreach ($pair in @(
        [pscustomobject]@{ staleBalance = '1.00'; staleVersion = '1'; authorityBalance = '2.00'; authorityVersion = '2' },
        [pscustomobject]@{ staleBalance = '2.00'; staleVersion = '2'; authorityBalance = '3.00'; authorityVersion = '3' }
    )) {
    $samples = @(
        [ordered]@{ uuid = $m10TestUuid; balance = $pair.staleBalance; version = $pair.staleVersion },
        [ordered]@{ uuid = $m10TestUuid; balance = $pair.staleBalance; version = $pair.staleVersion },
        [ordered]@{ uuid = $m10TestUuid; balance = $pair.staleBalance; version = $pair.staleVersion }
    )
    $authority = [ordered]@{ balance = $pair.authorityBalance; version = $pair.authorityVersion }
    $missed = [ordered]@{
        oldMemory = [ordered]@{ uuid = $m10TestUuid; balance = $pair.staleBalance; version = $pair.staleVersion }
        redisAuthority = $authority
        postReconnectObservations = $samples
    }
    $account = [ordered]@{ uuid = $m10TestUuid; authority = $authority; reconciled = $authority }
    $null = Assert-M10MissedPubSubTransition $missed $account 'transition fixture'
}
$invalidMissed = [ordered]@{
    oldMemory = [ordered]@{ uuid = $m10TestUuid; balance = '1.00'; version = '1' }
    redisAuthority = [ordered]@{ balance = '3.00'; version = '3' }
    postReconnectObservations = @(
        [ordered]@{ uuid = $m10TestUuid; balance = '1.00'; version = '1' },
        [ordered]@{ uuid = $m10TestUuid; balance = '1.00'; version = '1' },
        [ordered]@{ uuid = $m10TestUuid; balance = '1.00'; version = '1' }
    )
}
$invalidAuthority = [ordered]@{ balance = '3.00'; version = '3' }
$invalidAccount = [ordered]@{ uuid = $m10TestUuid; authority = $invalidAuthority; reconciled = $invalidAuthority }
Assert-Throws { Assert-M10MissedPubSubTransition $invalidMissed $invalidAccount 'invalid transition fixture' } 'M10 offline audit accepted a stale-to-authority gap larger than one missed deposit.'
foreach ($versionText in @('+1', ' 1 ', '-1', '１', '01')) {
    $nonCanonicalMissed = [ordered]@{
        oldMemory = [ordered]@{ uuid = $m10TestUuid; balance = '1.00'; version = $versionText }
        redisAuthority = [ordered]@{ balance = '2.00'; version = '2' }
        postReconnectObservations = @(
            [ordered]@{ balance = '1.00'; version = $versionText },
            [ordered]@{ balance = '1.00'; version = $versionText },
            [ordered]@{ balance = '1.00'; version = $versionText }
        )
    }
    $canonicalAccount = [ordered]@{
        uuid = $m10TestUuid
        authority = [ordered]@{ balance = '2.00'; version = '2' }
        reconciled = [ordered]@{ balance = '2.00'; version = '2' }
    }
    Assert-Throws { Assert-M10MissedPubSubTransition $nonCanonicalMissed $canonicalAccount "non-canonical version fixture $versionText" } "M10 offline audit accepted non-canonical version text '$versionText'."
}

function New-M10ProvenanceFixture {
    $uuid = '1ef97e85-b60f-3c2d-8fca-45edad0b7102'
    $probeRequestId = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
    $sampleRequestIds = @('bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 'cccccccccccccccccccccccccccccccc', 'dddddddddddddddddddddddddddddddd')
    $publication = [ordered]@{
        event = 'publicationObserved'; channel = 'syncmoney:balance:update'; probeRequestId = $probeRequestId
        uuid = $uuid; balance = '3.00'; version = '3'; eventType = 'DEPOSIT'; sourceServer = 'm10-a'
        messageId = '8f57ef64-2f47-4e7f-b1be-5e77904d5c14'; payloadSha256 = ('a' * 64)
    }
    $samples = @(
        [ordered]@{ sample = 1; requestId = $sampleRequestIds[0]; balance = '2.00'; version = '2' },
        [ordered]@{ sample = 2; requestId = $sampleRequestIds[1]; balance = '2.00'; version = '2' },
        [ordered]@{ sample = 3; requestId = $sampleRequestIds[2]; balance = '2.00'; version = '2' }
    )
    $commands = [System.Collections.Generic.List[object]]::new()
    $commands.Add([ordered]@{ node = 'a'; command = "smaccept m10 deposit $uuid 1.00 $probeRequestId"; exitCode = 0 })
    foreach ($sample in $samples) {
        $commands.Add([ordered]@{ node = 'b'; command = "smaccept m10 snapshot AcceptanceM10 $($sample.requestId)"; exitCode = 0 })
    }
    $missed = [ordered]@{
        oldMemory = [ordered]@{ requestId = $sampleRequestIds[2]; uuid = $uuid; balance = '2.00'; version = '2' }
        redisAuthority = [ordered]@{ balance = '3.00'; version = '3' }
        subscriberReconnected = $true; replayObserved = $false; publication = $publication
        postReconnectObservations = $samples
    }
    $report = [ordered]@{
        runId = 'paper-1216-min-fixture'
        account = [ordered]@{
            uuid = $uuid; missedPubSub = $missed
            authority = [ordered]@{ balance = '3.00'; version = '3' }
            reconciled = [ordered]@{ balance = '3.00'; version = '3' }
        }
        pubsubObservations = @([ordered]@{
            probeRequestId = $probeRequestId; uuid = $uuid; balance = '3.00'; version = '3'
            channel = 'syncmoney:balance:update'; messageId = $publication.messageId
            payloadSha256 = $publication.payloadSha256
        })
        commands = $commands.ToArray()
    }
    $serverLogA = "[INFO] T21_M10_DEPOSIT_PASS request=$probeRequestId uuid=$uuid amount=1.00 balance=3.00 version=3"
    $serverLogB = ($samples | ForEach-Object {
        "[INFO] T21_M10_MEMORY request=$($_.requestId) name=AcceptanceM10 uuid=$uuid balance=$($_.balance) version=$($_.version)"
    }) -join "`n"
    return [ordered]@{
        report = $report; serverLogA = $serverLogA; serverLogB = $serverLogB
        observerEvents = @($publication)
    }
}

function Copy-M10ProvenanceFixture([object]$Fixture) {
    return ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $Fixture -Depth 30) -AsHashtable
}

$provenanceFixture = New-M10ProvenanceFixture
$null = Assert-M10ReportIdentity $provenanceFixture.report 'paper-1216-min-fixture' 'identity fixture'
Assert-Throws { Assert-M10ReportIdentity $provenanceFixture.report 'paper-1216-other-run' 'mismatched identity fixture' } 'M10 offline audit accepted a report from a different pinned run.'
$null = Assert-M10MissedPubSubProvenance $provenanceFixture.report $provenanceFixture.serverLogA $provenanceFixture.serverLogB $provenanceFixture.observerEvents 'provenance fixture'

$splitDepositMarker = Copy-M10ProvenanceFixture $provenanceFixture
$splitDepositMarker.serverLogA = $splitDepositMarker.serverLogA.Replace(
    "request=$($splitDepositMarker.report.account.missedPubSub.publication.probeRequestId) uuid=",
    "request=$($splitDepositMarker.report.account.missedPubSub.publication.probeRequestId)`n uuid=")
Assert-Throws {
    Assert-M10MissedPubSubProvenance $splitDepositMarker.report $splitDepositMarker.serverLogA $splitDepositMarker.serverLogB $splitDepositMarker.observerEvents 'split-line deposit marker fixture'
} 'M10 offline audit accepted a deposit receipt assembled across separate log lines.'

$splitMemoryMarker = Copy-M10ProvenanceFixture $provenanceFixture
$splitMemoryRequestId = [string]$splitMemoryMarker.report.account.missedPubSub.postReconnectObservations[0].requestId
$splitMemoryMarker.serverLogB = $splitMemoryMarker.serverLogB.Replace(
    "request=$splitMemoryRequestId name=AcceptanceM10 uuid=",
    "request=$splitMemoryRequestId`n name=AcceptanceM10 uuid=")
Assert-Throws {
    Assert-M10MissedPubSubProvenance $splitMemoryMarker.report $splitMemoryMarker.serverLogA $splitMemoryMarker.serverLogB $splitMemoryMarker.observerEvents 'split-line memory marker fixture'
} 'M10 offline audit accepted a memory snapshot assembled across separate log lines.'

$badEventType = Copy-M10ProvenanceFixture $provenanceFixture
$badEventType.report.account.missedPubSub.publication.eventType = 'WITHDRAW'
Assert-Throws { Assert-M10MissedPubSubProvenance $badEventType.report $badEventType.serverLogA $badEventType.serverLogB $badEventType.observerEvents 'wrong event type fixture' } 'M10 offline audit accepted a non-deposit Pub/Sub event.'

$badChannel = Copy-M10ProvenanceFixture $provenanceFixture
$badChannel.report.account.missedPubSub.publication.channel = 'syncmoney:cmi:balance:update'
Assert-Throws { Assert-M10MissedPubSubProvenance $badChannel.report $badChannel.serverLogA $badChannel.serverLogB $badChannel.observerEvents 'wrong channel fixture' } 'M10 offline audit accepted a publication on the wrong channel.'

$badPublicationUuid = Copy-M10ProvenanceFixture $provenanceFixture
$badPublicationUuid.report.account.missedPubSub.publication.uuid = '8a3d9988-a783-46d4-bf22-f4c9f1f4fcd3'
Assert-Throws { Assert-M10MissedPubSubProvenance $badPublicationUuid.report $badPublicationUuid.serverLogA $badPublicationUuid.serverLogB $badPublicationUuid.observerEvents 'wrong publication UUID fixture' } 'M10 offline audit accepted a publication for a different account.'

$badProbeRequestId = Copy-M10ProvenanceFixture $provenanceFixture
$badProbeRequestId.report.account.missedPubSub.publication.probeRequestId = 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'
Assert-Throws { Assert-M10MissedPubSubProvenance $badProbeRequestId.report $badProbeRequestId.serverLogA $badProbeRequestId.serverLogB $badProbeRequestId.observerEvents 'wrong probe request fixture' } 'M10 offline audit accepted an unlinked Pub/Sub probe request ID.'

$badDepositAmount = Copy-M10ProvenanceFixture $provenanceFixture
$badDepositAmount.report.commands[0].command = $badDepositAmount.report.commands[0].command.Replace('1.00', '2.00')
Assert-Throws { Assert-M10MissedPubSubProvenance $badDepositAmount.report $badDepositAmount.serverLogA $badDepositAmount.serverLogB $badDepositAmount.observerEvents 'wrong deposit amount fixture' } 'M10 offline audit accepted a deposit command with a different amount.'

$failedDeposit = Copy-M10ProvenanceFixture $provenanceFixture
$failedDeposit.report.commands[0].exitCode = 1
Assert-Throws { Assert-M10MissedPubSubProvenance $failedDeposit.report $failedDeposit.serverLogA $failedDeposit.serverLogB $failedDeposit.observerEvents 'failed deposit fixture' } 'M10 offline audit accepted an unsuccessful deposit command.'

$failedSnapshot = Copy-M10ProvenanceFixture $provenanceFixture
$failedSnapshot.report.commands[1].exitCode = 1
Assert-Throws { Assert-M10MissedPubSubProvenance $failedSnapshot.report $failedSnapshot.serverLogA $failedSnapshot.serverLogB $failedSnapshot.observerEvents 'failed snapshot fixture' } 'M10 offline audit accepted an unsuccessful stale-sample snapshot.'

$wrongSampleUuid = Copy-M10ProvenanceFixture $provenanceFixture
$wrongSampleUuid.serverLogB = $wrongSampleUuid.serverLogB.Replace('uuid=1ef97e85-b60f-3c2d-8fca-45edad0b7102', 'uuid=8a3d9988-a783-46d4-bf22-f4c9f1f4fcd3')
Assert-Throws { Assert-M10MissedPubSubProvenance $wrongSampleUuid.report $wrongSampleUuid.serverLogA $wrongSampleUuid.serverLogB $wrongSampleUuid.observerEvents 'wrong sample UUID fixture' } 'M10 offline audit accepted a stale sample log for a different account.'

$wrongObserverRequest = Copy-M10ProvenanceFixture $provenanceFixture
$wrongObserverRequest.observerEvents[0].probeRequestId = 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'
Assert-Throws { Assert-M10MissedPubSubProvenance $wrongObserverRequest.report $wrongObserverRequest.serverLogA $wrongObserverRequest.serverLogB $wrongObserverRequest.observerEvents 'wrong observer request fixture' } 'M10 offline audit accepted an observer event with a different request ID.'

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) "syncmoney-m10-audit-$([guid]::NewGuid().ToString('N'))"
$localPlugDevRoot = Join-Path $tempRoot 'plugdev'
$distRoot = Join-Path $localPlugDevRoot 'packages/cli/dist'
try {
    $null = New-Item -ItemType Directory -Path (Join-Path $distRoot 'spec') -Force
    [IO.File]::WriteAllText((Join-Path $localPlugDevRoot 'package.json'), '{"name":"plugdev","version":"1.3.0","private":true}', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $distRoot 'cli.js'), 'export const cli = true;', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $distRoot 'spec/schema.json'), '{"version":1}', [Text.UTF8Encoding]::new($false))

    $pinnedSourcePath = Join-Path $distRoot 'cli.js'
    $substitutedSourcePath = Join-Path $tempRoot 'substituted-cli.js'
    Copy-Item -LiteralPath $pinnedSourcePath -Destination $substitutedSourcePath
    $resolvedSourcePath = Assert-M10ReportedPath $pinnedSourcePath $pinnedSourcePath 'pinned fixture'
    if ($resolvedSourcePath -ne [IO.Path]::GetFullPath($pinnedSourcePath)) { throw 'M10 audit returned an unexpected pinned source path.' }
    Assert-Throws { Assert-M10ReportedPath $substitutedSourcePath $pinnedSourcePath 'substituted fixture' } 'M10 audit accepted a report-supplied source path substitution.'

    $package = Assert-M10AuditExpectedPlugDevRoot $localPlugDevRoot $localPlugDevRoot
    if ($package.private -ne $true) { throw 'M10 audit did not return the validated local private PlugDev package.' }
    Assert-Throws { Assert-M10AuditExpectedPlugDevRoot $localPlugDevRoot (Join-Path $tempRoot 'substituted-plugdev') } 'M10 audit accepted a substituted PlugDev checkout root.'

    $mineflayerRoot = Join-Path $tempRoot 'mineflayer'
    $null = New-Item -ItemType Directory -Path $mineflayerRoot -Force
    [IO.File]::WriteAllText((Join-Path $mineflayerRoot 'package.json'), '{"name":"mineflayer","version":"4.39.0"}', [Text.UTF8Encoding]::new($false))
    $mineflayerPackage = Assert-M10AuditExpectedMineflayerRoot $mineflayerRoot $mineflayerRoot
    if ($mineflayerPackage.name -ne 'mineflayer') { throw 'M10 audit did not return the validated local Mineflayer package.' }
    Assert-Throws { Assert-M10AuditExpectedMineflayerRoot $mineflayerRoot (Join-Path $tempRoot 'substituted-mineflayer') } 'M10 audit accepted a substituted Mineflayer checkout root.'

    $manifest = @(Get-M10CliDistManifest $distRoot)
    if ($manifest.Count -ne 2) { throw 'M10 audit CLI manifest did not include all initial dist files.' }
    if (-not (Assert-M10CliDistManifest $distRoot $manifest (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $distRoot 'cli.js')).Hash.ToLowerInvariant() 'initial fixture')) {
        throw 'M10 audit rejected an unchanged PlugDev CLI bundle.'
    }
    Remove-Item -LiteralPath (Join-Path $distRoot 'spec/schema.json')
    Assert-Throws { Assert-M10CliDistManifest $distRoot $manifest $manifest[0].sha256 'removed-file fixture' } 'M10 audit accepted a removed PlugDev CLI dist file.'
    [IO.File]::WriteAllText((Join-Path $distRoot 'spec/schema.json'), '{"version":1}', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $distRoot 'added-module.js'), 'export const added = true;', [Text.UTF8Encoding]::new($false))
    Assert-Throws { Assert-M10CliDistManifest $distRoot $manifest $manifest[0].sha256 'added-file fixture' } 'M10 audit accepted an added PlugDev CLI dist file.'

    $junctionTarget = Join-Path $tempRoot 'junction-target'
    $junctionRoot = Join-Path $tempRoot 'junction-root'
    $junctionCheckout = Join-Path $junctionRoot 'plugdev'
    $null = New-Item -ItemType Directory -Path (Join-Path $junctionTarget 'plugdev') -Force
    [IO.File]::WriteAllText((Join-Path $junctionTarget 'plugdev/package.json'), '{"name":"plugdev","version":"1.3.0","private":true}', [Text.UTF8Encoding]::new($false))
    $null = New-Item -ItemType Junction -Path $junctionRoot -Target $junctionTarget
    Assert-Throws { Assert-M10AuditExpectedPlugDevRoot $junctionCheckout $junctionCheckout } 'M10 audit accepted a PlugDev root with a junction ancestor.'

    [IO.File]::WriteAllText((Join-Path $localPlugDevRoot 'package.json'), '{"name":"plugdev","version":"1.3.0","private":false}', [Text.UTF8Encoding]::new($false))
    Assert-Throws { Assert-M10AuditExpectedPlugDevRoot $localPlugDevRoot $localPlugDevRoot } 'M10 audit accepted a non-private PlugDev package.'

    $pwsh = (Get-Command pwsh -ErrorAction Stop).Source
    $invalidRunOutput = @(& $pwsh -NoProfile -File $auditPath -RepositoryRoot $PSScriptRoot -SupplementalCurrentStableOnly -CurrentStableRunId 'paper-263-currentstable-invalid' 2>&1)
    $invalidRunExitCode = $LASTEXITCODE
    if ($invalidRunExitCode -eq 0 -or (($invalidRunOutput -join ' ') -notmatch 'requires the exact M10 paper-263-currentstable run ID')) {
        throw 'M10 offline audit accepted an invalid CurrentStable run identity.'
    }
    $alternateRoot = Join-Path $tempRoot 'alternate-root'
    $alternateOutput = @(& $pwsh -NoProfile -File $auditPath -RepositoryRoot $PSScriptRoot -PlugDevRoot $alternateRoot 2>&1)
    $alternateExitCode = $LASTEXITCODE
    if ($alternateExitCode -eq 0 -or (($alternateOutput -join "`n") -notmatch 'pinned to the configured local PlugDev checkout')) {
        throw 'M10 offline audit entrypoint did not reject an alternate local PlugDev root before report inspection.'
    }

    Write-Output 'PASS M10 offline audit provenance and transition regressions (run identity, current-stable selector, event/deposit/snapshot linkage, account UUIDs, canonical versions, and path/bundle provenance)'
} finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}
