function Read-RecoveryJournal([string]$journalPath) {
    if (-not (Test-Path -LiteralPath $journalPath -PathType Leaf)) {
        return [ordered]@{ path = $journalPath; exists = $false; bytes = 0; sha256 = $null; frameCount = 0; recordFrames = 0; ackFrames = 0; pendingCount = 0; validation = 'PASS_EMPTY' }
    }

    $expectedLength = [long](Get-Item -LiteralPath $journalPath -ErrorAction Stop).Length
    if ($expectedLength -gt 64MB) { throw 'Recovery journal exceeds the existing 64 MiB format limit.' }
    $bytes = [IO.File]::ReadAllBytes($journalPath)
    if ($bytes.Length -gt 64MB) { throw 'Recovery journal exceeds the existing 64 MiB format limit.' }
    if ($bytes.LongLength -ne $expectedLength) { throw 'Recovery journal changed size while it was being read.' }
    if ($bytes.Length -gt 0 -and $bytes[$bytes.Length - 1] -ne 10) { throw 'Recovery journal has a partial final line.' }
    $encoding = [Text.UTF8Encoding]::new($false, $true)
    $contents = $encoding.GetString($bytes)
    $lines = $contents.Split([char[]]@([char]10))
    $pending = [Collections.Generic.Dictionary[string, long]]::new([StringComparer]::Ordinal)
    $previousSequence = [long]0
    $recordFrames = 0
    $ackFrames = 0
    $allowedProperties = @('version', 'sequence', 'kind', 'id', 'payload', 'checksum')

    for ($lineIndex = 0; $lineIndex -lt $lines.Length - 1; $lineIndex++) {
        $rawLine = $lines[$lineIndex]
        if ($encoding.GetByteCount($rawLine) -gt 4MB) { throw "Recovery journal frame $($lineIndex + 1) exceeds the existing 4 MiB frame limit." }
        $line = $rawLine.TrimEnd([char]13)
        if ([string]::IsNullOrWhiteSpace($line)) { throw "Recovery journal frame $($lineIndex + 1) is empty." }

        $document = $null
        try {
            $document = [System.Text.Json.JsonDocument]::Parse($line)
            if ($document.RootElement.ValueKind -ne [System.Text.Json.JsonValueKind]::Object) { throw 'frame root is not an object' }
            $seenProperties = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
            foreach ($property in $document.RootElement.EnumerateObject()) {
                if ($property.Name -notin $allowedProperties -or -not $seenProperties.Add($property.Name)) {
                    throw "frame has an unknown or duplicate property '$($property.Name)'"
                }
            }
            if ($seenProperties.Count -ne $allowedProperties.Count) { throw 'frame is missing a required property' }
            $versionElement = $document.RootElement.GetProperty('version')
            $sequenceElement = $document.RootElement.GetProperty('sequence')
            if ($versionElement.ValueKind -ne [System.Text.Json.JsonValueKind]::Number -or
                    $sequenceElement.ValueKind -ne [System.Text.Json.JsonValueKind]::Number) {
                throw 'version and sequence must be JSON integers'
            }
            $null = $versionElement.GetInt32()
            $null = $sequenceElement.GetInt64()
            foreach ($name in @('kind', 'id', 'payload', 'checksum')) {
                if ($document.RootElement.GetProperty($name).ValueKind -ne [System.Text.Json.JsonValueKind]::String) {
                    throw "$name must be a JSON string"
                }
            }
        } catch {
            throw "Recovery journal frame $($lineIndex + 1) has invalid JSON shape: $($_.Exception.Message)"
        } finally {
            if ($null -ne $document) { $document.Dispose() }
        }

        try { $frame = ConvertFrom-Json -InputObject $line -AsHashtable -ErrorAction Stop }
        catch { throw "Recovery journal frame $($lineIndex + 1) is invalid JSON." }
        $version = [int]$frame.version
        $sequence = [long]$frame.sequence
        $kind = [string]$frame.kind
        $id = [string]$frame.id
        $payload = [string]$frame.payload
        $checksum = [string]$frame.checksum
        if ($version -ne 1 -or $sequence -le $previousSequence -or [string]::IsNullOrWhiteSpace($id) -or $null -eq $frame.payload) {
            throw "Recovery journal frame $($lineIndex + 1) violates the existing version, sequence, identity, or payload contract."
        }
        $canonical = $version.ToString() + [char]10 + $sequence.ToString() + [char]10 + $kind + [char]10 + $id + [char]10 + $payload
        $expectedChecksum = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($canonical))).ToLowerInvariant()
        if (-not [string]::Equals($checksum, $expectedChecksum, [StringComparison]::Ordinal)) {
            throw "Recovery journal frame $($lineIndex + 1) checksum mismatch."
        }
        $previousSequence = $sequence

        if ($kind -ceq 'RECORD') {
            if ($payload -notmatch '\A[A-Za-z0-9_-]*={0,2}\z') {
                throw "Recovery journal frame $($lineIndex + 1) has invalid base64url payload."
            }
            $unpaddedBase64 = $payload.TrimEnd('=')
            $paddingCount = $payload.Length - $unpaddedBase64.Length
            $base64LengthRemainder = $unpaddedBase64.Length % 4
            if ($base64LengthRemainder -eq 1 -or
                    ($paddingCount -gt 0 -and (
                        ($base64LengthRemainder -eq 0) -or
                        ($base64LengthRemainder -eq 2 -and $paddingCount -ne 2) -or
                        ($base64LengthRemainder -eq 3 -and $paddingCount -ne 1)
                    ))) {
                throw "Recovery journal frame $($lineIndex + 1) has invalid base64url payload."
            }
            $base64 = $unpaddedBase64.Replace('-', '+').Replace('_', '/')
            switch ($base64LengthRemainder) { 2 { $base64 += '==' } 3 { $base64 += '=' } }
            try { $null = $encoding.GetString([Convert]::FromBase64String($base64)) }
            catch { throw "Recovery journal frame $($lineIndex + 1) has invalid base64url or UTF-8 payload." }
            $pending[$id] = $sequence
            $recordFrames++
        } elseif ($kind -ceq 'ACK' -and $payload.Length -eq 0) {
            $null = $pending.Remove($id)
            $ackFrames++
        } else {
            throw "Recovery journal frame $($lineIndex + 1) has an unsupported kind or nonempty ACK payload."
        }
    }

    return [ordered]@{
        path = $journalPath
        exists = $true
        bytes = $bytes.Length
        sha256 = (Get-FileHash -LiteralPath $journalPath -Algorithm SHA256).Hash.ToLowerInvariant()
        frameCount = $recordFrames + $ackFrames
        recordFrames = $recordFrames
        ackFrames = $ackFrames
        pendingCount = $pending.Count
        validation = 'PASS_FORMAT_AND_CHECKSUM'
    }
}

function Assert-RecoveryJournalForRollback([object]$journal) {
    if ($null -eq $journal -or -not $journal.exists) {
        throw 'Recovery journal must exist before the rollback stage.'
    }
    if ([long]$journal.frameCount -lt 1) {
        throw 'Recovery journal must contain at least one validated frame before the rollback stage.'
    }
    if ($journal.validation -cne 'PASS_FORMAT_AND_CHECKSUM') {
        throw 'Recovery journal must pass format and checksum validation before the rollback stage.'
    }
    if ([long]$journal.pendingCount -ne 0) {
        throw 'Recovery journal contains pending or ambiguous work; refusing the rollback stage.'
    }
    return $journal
}

function Assert-RecoveryJournalStateUnchanged([string]$journalPath, [bool]$expectedExists, [string]$expectedSha256) {
    if (-not $expectedExists) {
        if (Test-Path -LiteralPath $journalPath) {
            throw 'Recovery journal was created after the rollback checkpoint.'
        }
        return [ordered]@{ path = $journalPath; exists = $false; bytes = 0; sha256 = $null; frameCount = 0; recordFrames = 0; ackFrames = 0; pendingCount = 0; validation = 'PASS_EMPTY' }
    }

    if ($expectedSha256 -notmatch '^[0-9a-fA-F]{64}$') {
        throw 'Expected recovery journal SHA-256 is invalid.'
    }
    if (-not (Test-Path -LiteralPath $journalPath -PathType Leaf)) {
        throw 'Recovery journal is absent after the rollback stage.'
    }
    $item = Get-Item -LiteralPath $journalPath -ErrorAction Stop
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Recovery journal became a reparse point after the rollback stage.'
    }

    $beforeHash = (Get-FileHash -LiteralPath $journalPath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
    if ($beforeHash -cne $expectedSha256.ToLowerInvariant()) {
        throw "Recovery journal changed or was replaced after the rollback stage: expected $($expectedSha256.ToLowerInvariant()), observed $beforeHash."
    }
    $journal = Read-RecoveryJournal $journalPath
    if (-not $journal.exists -or $journal.sha256 -cne $beforeHash) {
        throw 'Recovery journal changed while post-rollback validation was running.'
    }
    $afterHash = (Get-FileHash -LiteralPath $journalPath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
    if ($afterHash -cne $beforeHash) {
        throw 'Recovery journal changed while post-rollback validation was running.'
    }
    return $journal
}

function Assert-RecoveryJournalUnchanged([string]$journalPath, [string]$expectedSha256) {
    $journal = Assert-RecoveryJournalStateUnchanged $journalPath $true $expectedSha256
    return Assert-RecoveryJournalForRollback $journal
}
