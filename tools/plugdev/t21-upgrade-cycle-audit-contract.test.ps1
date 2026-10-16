Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw 'PowerShell 7 or newer is required for T21 offline-auditor contract tests.'
}

$auditPath = Join-Path $PSScriptRoot 't21-upgrade-cycle-audit.ps1'
$tokens = $null
$parseErrors = $null
$auditAst = [System.Management.Automation.Language.Parser]::ParseFile($auditPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) {
    $details = @($parseErrors | ForEach-Object { '{0}: {1}' -f $_.Extent.StartLineNumber, $_.Message }) -join '; '
    throw "The T21 offline auditor has PowerShell parse errors: $details"
}

$platformEndpointParameter = $auditAst.ParamBlock.Parameters | Where-Object {
    $_.Name.VariablePath.UserPath -ceq 'PlatformEndpoint'
} | Select-Object -First 1
$platformEndpointValidateSet = $platformEndpointParameter.Attributes | Where-Object {
    $_.TypeName.Name -ceq 'ValidateSet'
} | Select-Object -First 1
$acceptedPlatformEndpoints = @($platformEndpointValidateSet.PositionalArguments | ForEach-Object { [string]$_.Value })
if ($acceptedPlatformEndpoints -notcontains 'CurrentStable') {
    throw 'The M04 offline auditor must accept the explicitly pinned current-stable Paper endpoint.'
}
if ($acceptedPlatformEndpoints -notcontains 'Maximum') {
    throw 'The M04 offline auditor must continue to accept the separate formal maximum endpoint.'
}
Write-Output 'PASS: M04 offline auditor accepts current-stable without replacing the formal maximum selector'

function Import-AuditPredicate([string]$Name) {
    $predicate = {
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $Name
    }.GetNewClosure()
    $definition = $auditAst.Find($predicate, $true)
    if ($null -eq $definition) { throw "The offline auditor does not define required predicate '$Name'." }
    return [scriptblock]::Create($definition.Extent.Text)
}

function Assert-ThrowsContaining([string]$Name, [scriptblock]$Action, [string]$Message) {
    try {
        $null = & $Action
    } catch {
        if ($_.Exception.Message -notlike ('*' + $Message + '*')) {
            throw "$Name failed for the wrong reason: $($_.Exception.Message)"
        }
        Write-Output "PASS: $Name"
        return
    }
    throw "$Name was accepted"
}

$startupPredicate = Import-AuditPredicate 'Assert-T21StartupRecoveryChronology'
$receiptPredicate = Import-AuditPredicate 'Assert-T21ReplayReceiptBinding'
$absentReceiptPredicate = Import-AuditPredicate 'Assert-T21AbsentPreReplayReceipt'
$fieldPredicate = Import-AuditPredicate 'Get-Field'
$recoveryScenarioPredicate = Import-AuditPredicate 'Resolve-T21RecoveryScenario'
$recoveryTaskPredicate = Import-AuditPredicate 'Assert-T21RecoveryTask'
$candidateCheckoutPredicate = Import-AuditPredicate 'Assert-T21CandidateCheckoutCommit'
$journalHelperPinPredicate = Import-AuditPredicate 'Get-T21LocalJournalHelperPin'
$sourcePinPredicate = Import-AuditPredicate 'Assert-T21SourcePin'
. $startupPredicate
. $receiptPredicate
. $absentReceiptPredicate
. $fieldPredicate
. $recoveryScenarioPredicate
. $recoveryTaskPredicate
. $candidateCheckoutPredicate
. $journalHelperPinPredicate
. $sourcePinPredicate

$recoveryMarker = 'Recovered 1 accepted LOCAL SQLite write(s) from the shutdown journal'
$enabledMarker = 'Syncmoney enabled successfully.'
$replayReadMarker = 'T21_LEGACY_SQLITE_PASS operation=read expected-a=117.25 expected-b=0.00'
$validLog = "$recoveryMarker`n$enabledMarker`n$replayReadMarker"
Assert-T21StartupRecoveryChronology $validLog $recoveryMarker $enabledMarker $replayReadMarker
Write-Output 'PASS: startup journal replay precedes plugin readiness and the first replay read'

Assert-ThrowsContaining 'late journal recovery after plugin readiness is rejected' {
    Assert-T21StartupRecoveryChronology "$enabledMarker`n$recoveryMarker`n$replayReadMarker" $recoveryMarker $enabledMarker $replayReadMarker
} 'startup recovery chronology'
Assert-ThrowsContaining 'first replay read before recovery is rejected' {
    Assert-T21StartupRecoveryChronology "$enabledMarker`n$replayReadMarker`n$recoveryMarker" $recoveryMarker $enabledMarker $replayReadMarker
} 'startup recovery chronology'
Assert-ThrowsContaining 'duplicate startup markers are rejected' {
    Assert-T21StartupRecoveryChronology "$recoveryMarker`n$enabledMarker`n$replayReadMarker`n$recoveryMarker" $recoveryMarker $enabledMarker $replayReadMarker
} 'exactly once'

$expectedReceipt = '2|00000000-0000-0000-0000-000000000001|fixture-a|DEPOSIT|real|17.25|real|100.00|real|117.25|VAULT_DEPOSIT|1792130562269|2|t21-local-replay-ee09d2a80c014837bde6536ba988d7ee'
$validObservation = [pscustomobject]@{ transactionRows = @($expectedReceipt) }
Assert-T21ReplayReceiptBinding $validObservation $expectedReceipt @($expectedReceipt)
Write-Output 'PASS: report replay receipt matches the independently queried SQLite row'

Assert-ThrowsContaining 'missing reported replay receipt is rejected' {
    Assert-T21ReplayReceiptBinding ([pscustomobject]@{ transactionRows = @() }) $expectedReceipt @($expectedReceipt)
} 'reported receipt row count'
Assert-ThrowsContaining 'tampered reported replay receipt is rejected' {
    Assert-T21ReplayReceiptBinding ([pscustomobject]@{ transactionRows = @($expectedReceipt.Replace('|17.25|', '|18.25|')) }) $expectedReceipt @($expectedReceipt)
} 'reported receipt differs'
Assert-ThrowsContaining 'tampered SQLite replay receipt is rejected' {
    Assert-T21ReplayReceiptBinding $validObservation $expectedReceipt @($expectedReceipt.Replace('|17.25|', '|18.25|'))
} 'SQLite receipt differs'
Assert-ThrowsContaining 'duplicate reported replay receipts are rejected' {
    Assert-T21ReplayReceiptBinding ([pscustomobject]@{ transactionRows = @($expectedReceipt, $expectedReceipt) }) $expectedReceipt @($expectedReceipt)
} 'reported receipt row count'

Assert-T21AbsentPreReplayReceipt $null @()
Write-Output 'PASS: no-receipt replay seed has no reported or SQLite request-ID receipt before replay'
Assert-ThrowsContaining 'reported pre-replay receipt is rejected for no-receipt seed' {
    Assert-T21AbsentPreReplayReceipt $expectedReceipt @()
} 'seed report contains a pre-replay receipt'
Assert-ThrowsContaining 'SQLite pre-replay receipt is rejected for no-receipt seed' {
    Assert-T21AbsentPreReplayReceipt $null @($expectedReceipt)
} 'SQLite contains a pre-replay receipt'

Assert-T21CandidateCheckoutCommit '93ac0e1e284d15f64eab6a5c7ad330a7891fab1d' $true
Write-Output 'PASS: candidate checkout metadata predicate accepts a recorded commit'
Assert-T21CandidateCheckoutCommit $null $false
Write-Output 'PASS: candidate checkout metadata predicate permits omission for reports that do not require it'
Assert-ThrowsContaining 'missing candidate source commit is rejected for new replay report' {
    Assert-T21CandidateCheckoutCommit $null $true
} 'candidate checkout commit metadata is required'
Assert-ThrowsContaining 'malformed candidate source commit is rejected' {
    Assert-T21CandidateCheckoutCommit 'not-a-commit' $true
} 'candidate checkout commit metadata must be a 40-character Git SHA'

$legacyReport = [pscustomobject]@{
    task = 'T21 M04 LOCAL SQLite upgrade and normal-drain rollback cycle'
}
$legacyScenario = Resolve-T21RecoveryScenario $legacyReport
if ($legacyScenario -cne 'normal-drain') { throw 'A legacy report without recoveryScenario did not normalize to normal-drain.' }
Assert-T21RecoveryTask $legacyScenario $legacyReport.task
Write-Output 'PASS: legacy report without recoveryScenario retains its normal-drain contract'

Assert-T21RecoveryTask 'seeded-journal-replay' 'T21 M04 LOCAL SQLite seeded journal replay and rollback cycle'
Assert-T21RecoveryTask 'seeded-journal-replay' 'T21 M04 LOCAL SQLite seeded duplicate-receipt journal replay and rollback cycle'
Write-Output 'PASS: canonical and historical duplicate-receipt task labels are accepted'
Assert-ThrowsContaining 'task label inconsistent with replay scenario is rejected' {
    Assert-T21RecoveryTask 'seeded-journal-replay' 'T21 M04 LOCAL SQLite upgrade and normal-drain rollback cycle'
} 'does not match recovery scenario'

$legacySourcePins = [pscustomobject]@{}
$null = Get-T21LocalJournalHelperPin $legacySourcePins $false
Write-Output 'PASS: legacy reports may omit the LOCAL journal helper source pin'
Assert-ThrowsContaining 'new report without LOCAL journal helper pin is rejected' {
    Get-T21LocalJournalHelperPin $legacySourcePins $true
} 'must pin the LOCAL journal helper source'
$newSourcePins = [pscustomobject]@{ localJournalHelperSha256 = ('a' * 64) }
if ((Get-T21LocalJournalHelperPin $newSourcePins $true) -cne ('a' * 64)) {
    throw 'The LOCAL journal helper source pin was not retained.'
}
Write-Output 'PASS: new reports retain their LOCAL journal helper source pin'

Assert-T21SourcePin 'runner' ('a' * 64) ('a' * 64)
Write-Output 'PASS: exact source pin matches its audited source'
Assert-ThrowsContaining 'stale source pin is rejected for full-report provenance' {
    Assert-T21SourcePin 'runner' ('a' * 64) ('b' * 64)
} 'does not match the source used for this audit'

$runnerText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'run-upgrade-cycle.ps1') -Raw
if ($runnerText -notmatch "'seeded-journal-replay'\s*\{\s*'T21 M04 LOCAL SQLite seeded journal replay and rollback cycle'\s*\}") {
    throw 'The runtime runner must emit the canonical seeded journal replay task label.'
}
Write-Output 'PASS: runner emits the canonical seeded replay task label'

Write-Output 'T21 offline-auditor contract tests PASS'
