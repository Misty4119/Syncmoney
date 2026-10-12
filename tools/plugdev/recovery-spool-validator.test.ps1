$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or newer is required for these validator tests.' }
. (Join-Path $PSScriptRoot 'recovery-spool-validator.ps1')

function New-TestFrame([long]$sequence, [string]$kind, [string]$id, [string]$payload) {
    $version = 1
    $canonical = $version.ToString() + [char]10 + $sequence.ToString() + [char]10 + $kind + [char]10 + $id + [char]10 + $payload
    $checksum = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($canonical))).ToLowerInvariant()
    return [ordered]@{ version = $version; sequence = $sequence; kind = $kind; id = $id; payload = $payload; checksum = $checksum } | ConvertTo-Json -Compress
}

function Write-TestJournal([string]$path, [string[]]$frames, [bool]$crlf = $false) {
    $newline = if ($crlf) { ([char]13).ToString() + [char]10 } else { [char]10 }
    [IO.File]::WriteAllText($path, ($frames -join $newline) + $newline, [Text.UTF8Encoding]::new($false))
}

function Assert-ThrowsContaining([string]$name, [scriptblock]$action, [string]$message) {
    try {
        $null = & $action
    } catch {
        if ($_.Exception.Message -notlike ('*' + $message + '*')) {
            throw ($name + ' failed for the wrong reason: ' + $_.Exception.Message)
        }
        Write-Output ('PASS: ' + $name)
        return
    }
    throw ($name + ' was accepted')
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('syncmoney-recovery-validator-' + [guid]::NewGuid().ToString('N'))
$testRoot = [IO.Path]::GetFullPath($testRoot)
New-Item -ItemType Directory -Path $testRoot -ErrorAction Stop | Out-Null
try {
    $validPath = Join-Path $testRoot 'valid.jsonl'
    $validFrames = @(
        (New-TestFrame 1 'RECORD' 'operation-1' 'YXR0ZW1wdGluZw'),
        (New-TestFrame 2 'RECORD' 'operation-1' 'Y29uZmlybWVk'),
        (New-TestFrame 3 'ACK' 'operation-1' '')
    )
    Write-TestJournal $validPath $validFrames $true
    $valid = Read-RecoveryJournal $validPath
    if ($valid.frameCount -ne 3 -or $valid.recordFrames -ne 2 -or $valid.ackFrames -ne 1 -or $valid.pendingCount -ne 0) {
        throw 'Repeated stage RECORD frames and ACK did not fold to the expected empty state.'
    }
    Write-Output 'PASS: valid CRLF journal and repeated operation stages'

    $validSha256 = (Get-FileHash -LiteralPath $validPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $stableJournal = Assert-RecoveryJournalUnchanged $validPath $validSha256
    if ($stableJournal.sha256 -cne $validSha256 -or $stableJournal.pendingCount -ne 0) {
        throw 'Post-rollback journal validation did not preserve the expected hash and empty pending state.'
    }
    Write-Output 'PASS: verify a validated recovery journal remains byte-identical after rollback'
    Assert-ThrowsContaining 'reject a recovery journal whose expected post-rollback hash differs' {
        Assert-RecoveryJournalUnchanged $validPath ('0' * 64)
    } 'changed or was replaced'
    Assert-ThrowsContaining 'reject a recovery journal deleted by rollback' {
        Assert-RecoveryJournalUnchanged (Join-Path $testRoot 'deleted-after-rollback.jsonl') $validSha256
    } 'absent after the rollback'

    $stateStable = Assert-RecoveryJournalStateUnchanged $validPath $true $validSha256
    if (-not $stateStable.exists -or $stateStable.sha256 -cne $validSha256) {
        throw 'The recovery journal state checkpoint did not preserve the existing journal hash.'
    }
    Write-Output 'PASS: verify an existing recovery journal state checkpoint'

    $absentPath = Join-Path $testRoot 'absent-at-checkpoint.jsonl'
    $absentStable = Assert-RecoveryJournalStateUnchanged $absentPath $false $null
    if ($absentStable.exists -or (Test-Path -LiteralPath $absentPath)) {
        throw 'An absent recovery journal checkpoint did not remain absent.'
    }
    Write-Output 'PASS: preserve an absent recovery journal checkpoint'
    [IO.File]::WriteAllText($absentPath, '', [Text.UTF8Encoding]::new($false))
    Assert-ThrowsContaining 'reject a recovery journal created after an absent rollback checkpoint' {
        Assert-RecoveryJournalStateUnchanged $absentPath $false $null
    } 'was created after the rollback checkpoint'

    Assert-ThrowsContaining 'reject an existing recovery journal changed after its rollback checkpoint' {
        Assert-RecoveryJournalStateUnchanged $validPath $true ('0' * 64)
    } 'changed or was replaced'

    $badChecksumPath = Join-Path $testRoot 'bad-checksum.jsonl'
    $badChecksum = $validFrames[0] | ConvertFrom-Json -AsHashtable
    $badChecksum.checksum = '0' * 64
    Write-TestJournal $badChecksumPath @($badChecksum | ConvertTo-Json -Compress)
    Assert-ThrowsContaining 'reject a well-formed frame with an altered checksum' { Read-RecoveryJournal $badChecksumPath } 'checksum mismatch'

    $missingPath = Join-Path $testRoot 'missing.jsonl'
    $missing = Read-RecoveryJournal $missingPath
    Assert-ThrowsContaining 'refuse rollback when the recovery journal is absent' { Assert-RecoveryJournalForRollback $missing } 'must exist'

    $emptyPath = Join-Path $testRoot 'empty.jsonl'
    [IO.File]::WriteAllText($emptyPath, '', [Text.UTF8Encoding]::new($false))
    $empty = Read-RecoveryJournal $emptyPath
    Assert-ThrowsContaining 'refuse rollback when the existing recovery journal has no frames' { Assert-RecoveryJournalForRollback $empty } 'at least one validated frame'

    $invalidAlphabetPath = Join-Path $testRoot 'invalid-base64-alphabet.jsonl'
    $invalidAlphabetFrames = @(
        (New-TestFrame 1 'RECORD' 'operation-invalid' 'w7/Dvw'),
        (New-TestFrame 2 'ACK' 'operation-invalid' '')
    )
    Write-TestJournal $invalidAlphabetPath $invalidAlphabetFrames
    Assert-ThrowsContaining 'reject standard Base64 alphabet in a Base64URL payload' { Read-RecoveryJournal $invalidAlphabetPath } 'invalid base64url'

    $caseSensitiveKindPath = Join-Path $testRoot 'case-sensitive-kind.jsonl'
    $caseSensitiveKindFrames = @(
        (New-TestFrame 1 'RECORD' 'operation-case' 'YXR0ZW1wdGluZw'),
        (New-TestFrame 2 'ack' 'operation-case' '')
    )
    Write-TestJournal $caseSensitiveKindPath $caseSensitiveKindFrames
    Assert-ThrowsContaining 'reject noncanonical lowercase ACK that would clear pending recovery' { Read-RecoveryJournal $caseSensitiveKindPath } 'unsupported kind'

    $mixedCaseKindPath = Join-Path $testRoot 'mixed-case-kind.jsonl'
    $mixedCaseKindFrames = @((New-TestFrame 1 'RecOrd' 'operation-mixed-case' 'YXR0ZW1wdGluZw'))
    Write-TestJournal $mixedCaseKindPath $mixedCaseKindFrames
    Assert-ThrowsContaining 'reject mixed-case recovery RECORD' { Read-RecoveryJournal $mixedCaseKindPath } 'unsupported kind'

    $oversizePath = Join-Path $testRoot 'oversize-crlf-frame.jsonl'
    $maxFrameBytes = 4 * 1024 * 1024
    $prefix = '{"version":1,"sequence":1,"kind":"RECORD","id":"oversize","payload":"'
    $suffix = '","checksum":"invalid"}'
    $payloadLength = $maxFrameBytes - [Text.Encoding]::UTF8.GetByteCount($prefix) - [Text.Encoding]::UTF8.GetByteCount($suffix)
    $oversizeFrame = $prefix + ('A' * $payloadLength) + $suffix
    if ([Text.Encoding]::UTF8.GetByteCount($oversizeFrame) -ne $maxFrameBytes) { throw 'Oversize fixture did not reach the exact frame boundary.' }
    Write-TestJournal $oversizePath @($oversizeFrame) $true
    Assert-ThrowsContaining 'count the CR byte against the 4 MiB frame limit' { Read-RecoveryJournal $oversizePath } '4 MiB frame limit'

    $oversizeJournalPath = Join-Path $testRoot 'oversize-journal.jsonl'
    $oversizeStream = [IO.File]::Open($oversizeJournalPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        $oversizeStream.SetLength(64MB + 1)
        $oversizeStream.Flush($true)
        Assert-ThrowsContaining 'reject a journal over 64 MiB before opening it for content reads' { Read-RecoveryJournal $oversizeJournalPath } '64 MiB'
    } finally {
        $oversizeStream.Dispose()
    }

    Write-Output 'Recovery spool validator tests PASS'
} finally {
    foreach ($name in @('valid.jsonl', 'bad-checksum.jsonl', 'missing.jsonl', 'empty.jsonl', 'invalid-base64-alphabet.jsonl', 'case-sensitive-kind.jsonl', 'mixed-case-kind.jsonl', 'oversize-crlf-frame.jsonl', 'oversize-journal.jsonl')) {
        $file = Join-Path $testRoot $name
        if (Test-Path -LiteralPath $file -PathType Leaf) { Remove-Item -LiteralPath $file -Force }
    }
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $testRoot.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.Path]::GetFileName($testRoot).StartsWith('syncmoney-recovery-validator-', [StringComparison]::Ordinal)) {
        throw 'Refusing to remove test directory outside its verified unique temp path.'
    }
    if (@(Get-ChildItem -LiteralPath $testRoot -Force).Count -eq 0) { Remove-Item -LiteralPath $testRoot -Force }
}
