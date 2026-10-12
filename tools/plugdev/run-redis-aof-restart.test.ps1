$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$runnerPath = Join-Path $PSScriptRoot 'run-redis-aof-restart.ps1'
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($runnerPath, [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) { throw "The AOF restart runner does not parse: $($errors[0].Message)" }

$writeCalls = @($ast.FindAll({
    param($node)
    $node -is [Management.Automation.Language.CommandAst] -and
        $node.GetCommandName() -ceq 'Invoke-Acceptance' -and
        $node.Extent.Text.Contains('candidate-persist-write')
}, $true))
if ($writeCalls.Count -ne 1) { throw "Expected one candidate AOF write acceptance call; found $($writeCalls.Count)." }

$marker = [string]$writeCalls[0].CommandElements[3].Value
$expectedMarker = 'T21_WRITE_PASS expected-a=1337.25 expected-b=765.50 receipt-a=SUCCESS:DEPOSIT:1260.00:1337.25 receipt-b=SUCCESS:DEPOSIT:677.00:765.50'
if ($marker -cne $expectedMarker) { throw "AOF write gate does not require the exact current Vault receipts. Observed: $marker" }

$currentProbeMarker = 'T21_WRITE_PASS expected-a=1337.25 expected-b=765.50 receipt-a=SUCCESS:DEPOSIT:1260.00:1337.25 receipt-b=SUCCESS:DEPOSIT:677.00:765.50'
$legacyProbeMarker = 'T21_WRITE_PASS a=1337.25 b=765.50'
if (-not $currentProbeMarker.Contains($marker)) { throw 'The current acceptance probe marker did not satisfy the AOF write gate.' }
if ($legacyProbeMarker.Contains($marker)) { throw 'The legacy marker unexpectedly satisfied the AOF write gate.' }

Write-Output 'PASS: require exact Vault mutation receipt marker from the current acceptance probe'
Write-Output 'PASS: reject the stale pre-receipt acceptance marker'
Write-Output 'Redis AOF restart runner contract tests PASS'
