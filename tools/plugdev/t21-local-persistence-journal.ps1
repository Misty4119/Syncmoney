Set-StrictMode -Version Latest

function Assert-T21LocalJournalNoReparsePointAncestors([string]$Path) {
    $current = [IO.Path]::GetFullPath($Path)
    while (-not [string]::IsNullOrWhiteSpace($current)) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Refusing a local-journal path with a reparse-point ancestor: $current"
            }
        }
        $parent = Split-Path -Parent $current
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $current) { break }
        $current = $parent
    }
}

function Get-T21LocalJournalBase64Url([string]$Value) {
    return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Get-T21LocalJournalSha256([byte[]]$Bytes) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString($algorithm.ComputeHash($Bytes)).Replace('-', '').ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
    }
}

function Invoke-T21LocalJournalSqliteRead([string]$SqliteExecutable, [string]$DatabasePath, [string]$Query) {
    $output = @(& $SqliteExecutable -batch -noheader -separator '|' -readonly $DatabasePath $Query 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect the isolated LOCAL SQLite fixture: $($output -join ' ')"
    }
    return @($output | ForEach-Object { $_.ToString() })
}

function Invoke-T21LocalJournalSqliteWrite([string]$SqliteExecutable, [string]$DatabasePath, [string]$Sql) {
    $output = @(& $SqliteExecutable -batch -cmd '.bail on' $DatabasePath $Sql 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not seed the isolated LOCAL SQLite receipt fixture: $($output -join ' ')"
    }
}

function Invoke-T21LocalJournalSqliteBackup([string]$SqliteExecutable, [string]$DatabasePath, [string]$BackupPath) {
    if ($BackupPath.Contains('"')) { throw 'The isolated SQLite snapshot path cannot contain a quote character.' }
    $backupTarget = $BackupPath.Replace('\', '/')
    $backupCommand = '.backup "' + $backupTarget + '"'
    $output = @(& $SqliteExecutable -batch $DatabasePath $backupCommand 2>&1)
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $BackupPath -PathType Leaf)) {
        throw "Could not retain the pre-replay SQLite snapshot: $($output -join ' ')"
    }
}

function Assert-T21LocalSqliteLockTarget([string]$RunDirectory, [string]$DatabasePath, [string]$SqliteExecutable) {
    $runRoot = [IO.Path]::GetFullPath($RunDirectory)
    $marker = [Environment]::GetEnvironmentVariable('T21_RUN_MARKER', 'Process')
    if ([string]::IsNullOrWhiteSpace($marker) -or [IO.Path]::GetFullPath($marker) -cne $runRoot) {
        throw 'LOCAL SQLite write-lock injection requires the exact isolated T21 run marker.'
    }
    $expectedDatabase = [IO.Path]::GetFullPath((Join-Path $runRoot '.plugdev\run\plugins\Syncmoney\t21-data\legacy-local.db'))
    $database = [IO.Path]::GetFullPath($DatabasePath)
    if ($database -cne $expectedDatabase) {
        throw 'LOCAL SQLite write-lock injection is restricted to the isolated historical M04 SQLite path.'
    }
    $runPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $database.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "LOCAL SQLite write-lock path escapes the isolated run: $database"
    }
    foreach ($path in @($runRoot, $database, $SqliteExecutable)) {
        Assert-T21LocalJournalNoReparsePointAncestors $path
    }
    if (-not (Test-Path -LiteralPath $database -PathType Leaf)) {
        throw 'The isolated historical LOCAL SQLite database must exist before the write lock starts.'
    }
    $sqlite = [IO.Path]::GetFullPath($SqliteExecutable)
    if (-not (Test-Path -LiteralPath $sqlite -PathType Leaf)) {
        throw 'The local sqlite3 executable must exist before the write lock starts.'
    }
    return [pscustomobject]@{ runRoot = $runRoot; databasePath = $database; sqliteExecutable = $sqlite }
}

function Assert-T21LocalSqliteWriterLockHeld($Lock) {
    if ($null -eq $Lock -or $null -eq $Lock.process -or $Lock.process.HasExited) {
        throw 'The isolated SQLite write-lock owner is not alive.'
    }
    $target = Assert-T21LocalSqliteLockTarget $Lock.runRoot $Lock.databasePath $Lock.sqliteExecutable
    if ([int]$Lock.processId -ne [int]$Lock.process.Id -or
        $target.databasePath -cne [string]$Lock.databasePath -or
        $target.sqliteExecutable -cne [string]$Lock.sqliteExecutable) {
        throw 'The isolated SQLite write-lock identity changed before verification.'
    }
    $probeOutput = @(& $target.sqliteExecutable -batch -cmd 'PRAGMA busy_timeout=0;' `
        $target.databasePath 'BEGIN IMMEDIATE;' 2>&1)
    $probeExitCode = $LASTEXITCODE
    $probeText = ($probeOutput | ForEach-Object { $_.ToString() }) -join ' '
    if ($Lock.process.HasExited -or $probeExitCode -eq 0 -or $probeText -notmatch '(?i)database is locked') {
        throw "The isolated SQLite transaction did not block a second writer: exit=$probeExitCode output=$probeText"
    }
    return [pscustomobject]@{
        writeLockObserved = $true
        verifierExitCode = [int]$probeExitCode
        verifierOutput = $probeText
        observedUtc = [DateTimeOffset]::UtcNow.ToString('o')
    }
}

function Start-T21LocalSqliteWriterLock {
    param(
        [Parameter(Mandatory = $true)][string]$RunDirectory,
        [Parameter(Mandatory = $true)][string]$DatabasePath,
        [Parameter(Mandatory = $true)][string]$SqliteExecutable
    )

    $target = Assert-T21LocalSqliteLockTarget $RunDirectory $DatabasePath $SqliteExecutable
    $journalModeOutput = @(& $target.sqliteExecutable -batch -noheader $target.databasePath 'PRAGMA journal_mode;' 2>&1)
    $journalModeExitCode = $LASTEXITCODE
    $journalMode = ($journalModeOutput | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ }) -join ''
    if ($journalModeExitCode -ne 0 -or $journalMode -cne 'wal') {
        throw "LOCAL SQLite timeout-handoff injection requires WAL mode; exit=$journalModeExitCode mode='$journalMode'."
    }

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $target.sqliteExecutable
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.ArgumentList.Add('-batch')
    $startInfo.ArgumentList.Add($target.databasePath)
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) { throw 'Could not start the isolated SQLite write-lock owner.' }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $lock = [pscustomobject]@{
        runRoot = $target.runRoot
        databasePath = $target.databasePath
        sqliteExecutable = $target.sqliteExecutable
        journalMode = $journalMode
        processId = [int]$process.Id
        process = $process
        stdoutTask = $stdoutTask
        stderrTask = $stderrTask
        startedUtc = [DateTimeOffset]::UtcNow.ToString('o')
        lockObserved = $null
    }
    try {
        $process.StandardInput.WriteLine('BEGIN IMMEDIATE;')
        $process.StandardInput.Flush()
        $deadline = [DateTime]::UtcNow.AddSeconds(5)
        do {
            if ($process.HasExited) {
                throw 'The isolated SQLite write-lock owner exited before establishing its transaction.'
            }
            try {
                $lock.lockObserved = Assert-T21LocalSqliteWriterLockHeld $lock
                return $lock
            } catch {
                if ([DateTime]::UtcNow -ge $deadline) { throw }
                Start-Sleep -Milliseconds 100
            }
        } while ([DateTime]::UtcNow -lt $deadline)
        throw 'The isolated SQLite write-lock owner did not establish a verified writer lock within five seconds.'
    } catch {
        try {
            if (-not $process.HasExited) {
                $process.StandardInput.WriteLine('.quit')
                $process.StandardInput.Flush()
                $process.StandardInput.Close()
                $null = $process.WaitForExit(5000)
            }
        } catch { }
        throw
    }
}

function Stop-T21LocalSqliteWriterLock($Lock) {
    if ($null -eq $Lock -or $null -eq $Lock.process) {
        throw 'The isolated SQLite write-lock record is missing its owned process.'
    }
    if ($Lock.process.HasExited) {
        throw "The isolated SQLite write-lock owner exited before explicit release (exit $($Lock.process.ExitCode))."
    }
    $target = Assert-T21LocalSqliteLockTarget $Lock.runRoot $Lock.databasePath $Lock.sqliteExecutable
    if ([int]$Lock.processId -ne [int]$Lock.process.Id -or
        $target.databasePath -cne [string]$Lock.databasePath -or
        $target.sqliteExecutable -cne [string]$Lock.sqliteExecutable) {
        throw 'Refusing to release a SQLite lock whose process or path identity changed.'
    }
    $Lock.process.StandardInput.WriteLine('ROLLBACK;')
    $Lock.process.StandardInput.WriteLine('.quit')
    $Lock.process.StandardInput.Flush()
    $Lock.process.StandardInput.Close()
    if (-not $Lock.process.WaitForExit(5000)) {
        throw 'The owned SQLite write-lock process did not exit after explicit rollback and quit.'
    }
    $stdout = $Lock.stdoutTask.GetAwaiter().GetResult()
    $stderr = $Lock.stderrTask.GetAwaiter().GetResult()
    $exitCode = [int]$Lock.process.ExitCode
    if ($exitCode -ne 0) {
        throw "The isolated SQLite write-lock owner exited with $exitCode after release: $stderr $stdout"
    }
    $Lock.process.Dispose()
    return [pscustomobject]@{
        processId = [int]$Lock.processId
        processExitVerified = $true
        exitCode = $exitCode
        releasedUtc = [DateTimeOffset]::UtcNow.ToString('o')
    }
}

function ConvertFrom-T21LocalJournalBase64Url([string]$Value) {
    if ($Value -notmatch '^[A-Za-z0-9_-]*$') {
        throw 'LOCAL journal contains an invalid base64url field.'
    }
    $standard = $Value.Replace('-', '+').Replace('_', '/')
    switch ($standard.Length % 4) {
        1 { throw 'LOCAL journal contains an invalid base64url field length.' }
        2 { $standard += '==' }
        3 { $standard += '=' }
    }
    try {
        return [Text.UTF8Encoding]::new($false, $true).GetString([Convert]::FromBase64String($standard))
    } catch {
        throw "LOCAL journal contains an invalid UTF-8 base64url field: $($_.Exception.Message)"
    }
}

function Get-T21LocalPersistenceJournalRecords {
    param(
        [Parameter(Mandatory = $true)][string]$RunDirectory,
        [Parameter(Mandatory = $true)][string]$DatabasePath,
        [Parameter(Mandatory = $true)][string]$JournalPath
    )

    $runRoot = [IO.Path]::GetFullPath($RunDirectory)
    $marker = [Environment]::GetEnvironmentVariable('T21_RUN_MARKER', 'Process')
    if ([string]::IsNullOrWhiteSpace($marker) -or [IO.Path]::GetFullPath($marker) -cne $runRoot) {
        throw 'LOCAL journal inspection requires the exact isolated T21 run marker.'
    }
    $expectedDatabase = [IO.Path]::GetFullPath((Join-Path $runRoot '.plugdev\run\plugins\Syncmoney\t21-data\legacy-local.db'))
    $database = [IO.Path]::GetFullPath($DatabasePath)
    if ($database -cne $expectedDatabase) {
        throw 'LOCAL journal inspection is restricted to the isolated historical M04 SQLite path.'
    }
    $expectedJournal = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $database) 'local-persistence.journal'))
    $journal = [IO.Path]::GetFullPath($JournalPath)
    if ($journal -cne $expectedJournal) {
        throw 'LOCAL journal inspection is restricted to the adjacent M04 local-persistence.journal.'
    }
    foreach ($path in @($runRoot, $database, $journal)) {
        Assert-T21LocalJournalNoReparsePointAncestors $path
    }
    if (-not (Test-Path -LiteralPath $database -PathType Leaf) -or
        -not (Test-Path -LiteralPath $journal -PathType Leaf)) {
        throw 'The isolated LOCAL database and non-empty handoff journal must exist before inspection.'
    }

    $bytes = [IO.File]::ReadAllBytes($journal)
    if ($bytes.Length -eq 0) { throw 'The isolated LOCAL handoff journal is empty.' }
    try {
        $text = [Text.UTF8Encoding]::new($false, $true).GetString($bytes)
    } catch {
        throw "LOCAL journal is not valid UTF-8: $($_.Exception.Message)"
    }
    if (-not $text.EndsWith("`n", [StringComparison]::Ordinal) -or $text.Contains("`r")) {
        throw 'LOCAL journal must use complete LF-terminated records.'
    }

    $lines = $text.Split([char[]]@("`n"), [StringSplitOptions]::None)
    if ($lines.Length -lt 2 -or $lines[$lines.Length - 1] -cne '') {
        throw 'LOCAL journal framing must end after a complete record.'
    }
    $records = [System.Collections.Generic.List[object]]::new()
    $requestIds = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $invariant = [Globalization.CultureInfo]::InvariantCulture
    for ($index = 0; $index -lt $lines.Length - 1; $index++) {
        $line = $lines[$index]
        if ([string]::IsNullOrWhiteSpace($line)) {
            throw "LOCAL journal contains an empty record at line $($index + 1)."
        }
        $parts = $line.Split([char[]]@("`t"), [StringSplitOptions]::None)
        if ($parts.Length -ne 11 -or $parts[0] -cne 'SYNCLOCAL1') {
            throw "LOCAL journal record $($index + 1) has invalid SYNCLOCAL1 framing."
        }
        $payload = [string]::Join("`t", $parts[0..9])
        if ((Get-T21LocalJournalSha256 ([Text.Encoding]::UTF8.GetBytes($payload))) -cne $parts[10]) {
            throw "LOCAL journal checksum mismatch at line $($index + 1)."
        }

        $uuidText = ConvertFrom-T21LocalJournalBase64Url $parts[1]
        $deltaText = ConvertFrom-T21LocalJournalBase64Url $parts[2]
        $balanceAfterText = ConvertFrom-T21LocalJournalBase64Url $parts[3]
        $requestId = ConvertFrom-T21LocalJournalBase64Url $parts[7]
        $playerName = ConvertFrom-T21LocalJournalBase64Url $parts[9]
        $uuid = [Guid]::Empty
        $delta = [decimal]0
        $balanceAfter = [decimal]0
        $version = [long]0
        $timestamp = [long]0
        if (-not [Guid]::TryParseExact($uuidText, 'D', [ref]$uuid) -or $uuid.ToString('D') -cne $uuidText -or
            -not [decimal]::TryParse($deltaText, [Globalization.NumberStyles]::Number, $invariant, [ref]$delta) -or
            -not [decimal]::TryParse($balanceAfterText, [Globalization.NumberStyles]::Number, $invariant, [ref]$balanceAfter) -or
            -not [long]::TryParse($parts[4], [Globalization.NumberStyles]::Integer, $invariant, [ref]$version) -or
            -not [long]::TryParse($parts[8], [Globalization.NumberStyles]::Integer, $invariant, [ref]$timestamp) -or
            [string]::IsNullOrWhiteSpace($requestId) -or [string]::IsNullOrWhiteSpace($playerName) -or
            $parts[5] -notin @('DEPOSIT', 'WITHDRAW', 'SET_BALANCE', 'TRANSFER_IN', 'TRANSFER_OUT') -or
            [string]::IsNullOrWhiteSpace($parts[6])) {
            throw "LOCAL journal record $($index + 1) contains an invalid event field."
        }
        if (-not $requestIds.Add($requestId)) {
            throw "LOCAL journal has a duplicate request ID at line $($index + 1)."
        }
        $records.Add([pscustomobject][ordered]@{
            uuid = $uuidText
            delta = $deltaText
            balanceAfter = $balanceAfterText
            eventVersion = $version
            type = $parts[5]
            source = $parts[6]
            requestId = $requestId
            timestamp = $timestamp
            playerName = $playerName
            recordSha256 = Get-T21LocalJournalSha256 ([Text.Encoding]::UTF8.GetBytes($line))
        })
    }
    return @($records.ToArray())
}

function Write-T21LocalJournalCreateNew([string]$Path, [byte[]]$Bytes) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $stream.Write($Bytes, 0, $Bytes.Length)
        $stream.Flush($true)
    } finally {
        $stream.Dispose()
    }
}

function New-T21LocalJournalReplaySeed {
    param(
        [Parameter(Mandatory = $true)][string]$RunDirectory,
        [Parameter(Mandatory = $true)][string]$DatabasePath,
        [Parameter(Mandatory = $true)][string]$SqliteExecutable,
        [ValidateSet('committed', 'absent')][string]$ReceiptState = 'committed'
    )

    $runRoot = [IO.Path]::GetFullPath($RunDirectory)
    $marker = [Environment]::GetEnvironmentVariable('T21_RUN_MARKER', 'Process')
    if ([string]::IsNullOrWhiteSpace($marker) -or
        [IO.Path]::GetFullPath($marker) -cne $runRoot) {
        throw 'LOCAL journal replay seeding requires the exact isolated T21 run marker.'
    }

    $expectedDatabase = [IO.Path]::GetFullPath((Join-Path $runRoot '.plugdev\run\plugins\Syncmoney\t21-data\legacy-local.db'))
    $database = [IO.Path]::GetFullPath($DatabasePath)
    if ($database -cne $expectedDatabase) {
        throw 'LOCAL journal replay seeding is restricted to the isolated historical M04 SQLite path.'
    }

    $journalPath = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $database) 'local-persistence.journal'))
    $archivePath = [IO.Path]::GetFullPath((Join-Path $runRoot 'inputs\seeded-local-persistence.journal'))
    $preReplayDatabasePath = [IO.Path]::GetFullPath((Join-Path $runRoot 'inputs\pre-replay-local.db'))
    $runPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    foreach ($path in @($database, $journalPath, $archivePath, $preReplayDatabasePath)) {
        if (-not $path.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "LOCAL journal replay path escapes the isolated run: $path"
        }
        Assert-T21LocalJournalNoReparsePointAncestors $path
    }
    if (-not (Test-Path -LiteralPath $database -PathType Leaf)) {
        throw 'The isolated historical LOCAL SQLite fixture does not exist.'
    }
    if (Test-Path -LiteralPath $journalPath) {
        throw 'Refusing to overwrite a pre-existing LOCAL persistence journal.'
    }
    if (Test-Path -LiteralPath $archivePath) {
        throw 'Refusing to overwrite the retained LOCAL journal seed artifact.'
    }
    if (Test-Path -LiteralPath $preReplayDatabasePath) {
        throw 'Refusing to overwrite the retained pre-replay SQLite snapshot.'
    }

    $sourceBalanceRows = @(Invoke-T21LocalJournalSqliteRead $SqliteExecutable $database `
        "SELECT uuid || '|' || name || '|' || typeof(balance) || '|' || printf('%.2f', balance) || '|' || version FROM player_balances WHERE uuid IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002') ORDER BY uuid;")
    $expectedSourceBalances = @(
        '00000000-0000-0000-0000-000000000001|fixture-a|real|100.00|7',
        '00000000-0000-0000-0000-000000000002|fixture-b|real|0.00|3'
    )
    if (($sourceBalanceRows -join "`n") -cne ($expectedSourceBalances -join "`n")) {
        throw "Refusing to seed a LOCAL journal over unexpected historical rows: $($sourceBalanceRows -join '; ')"
    }
    $transactionCount = @(Invoke-T21LocalJournalSqliteRead $SqliteExecutable $database 'SELECT COUNT(*) FROM transactions;')
    if ($transactionCount.Count -ne 1 -or $transactionCount[0] -cne '1') {
        throw 'Refusing to seed a LOCAL journal over an unexpected historical transaction set.'
    }
    $historicalTransaction = @(Invoke-T21LocalJournalSqliteRead $SqliteExecutable $database `
        "SELECT id || '|' || uuid || '|' || name || '|' || type || '|' || printf('%.2f', amount) || '|' || source FROM transactions WHERE id=1;")
    if ($historicalTransaction.Count -ne 1 -or
        $historicalTransaction[0] -cne '1|00000000-0000-0000-0000-000000000001|fixture-a|DEPOSIT|100.00|TEST') {
        throw 'Refusing to seed a LOCAL journal over an unexpected historical transaction row.'
    }

    $requestId = 't21-local-replay-' + [guid]::NewGuid().ToString('N')
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    if ($ReceiptState -eq 'committed') {
        $receiptSchemaSql = @"
BEGIN IMMEDIATE;
ALTER TABLE transactions ADD COLUMN request_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_transactions_request_id ON transactions(request_id) WHERE request_id IS NOT NULL;
COMMIT;
"@
        Invoke-T21LocalJournalSqliteWrite $SqliteExecutable $database $receiptSchemaSql
        $receiptSql = @"
BEGIN IMMEDIATE;
UPDATE player_balances SET balance=117.25, version=8 WHERE uuid='00000000-0000-0000-0000-000000000001';
INSERT INTO transactions (uuid, name, type, amount, balance_before, balance_after, source, target_name, timestamp, sequence, request_id)
VALUES ('00000000-0000-0000-0000-000000000001', 'fixture-a', 'DEPOSIT', 17.25, 100.00, 117.25, 'VAULT_DEPOSIT', NULL, $timestamp, 2, '$requestId');
COMMIT;
"@
        Invoke-T21LocalJournalSqliteWrite $SqliteExecutable $database $receiptSql
    }
    Invoke-T21LocalJournalSqliteBackup $SqliteExecutable $database $preReplayDatabasePath
    $preReplayDatabaseItem = Get-Item -LiteralPath $preReplayDatabasePath
    $preReplayDatabaseSha256 = (Get-FileHash -LiteralPath $preReplayDatabasePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $preReplayRequestIdColumnCount = @(Invoke-T21LocalJournalSqliteRead $SqliteExecutable $preReplayDatabasePath `
        "SELECT COUNT(*) FROM pragma_table_info('transactions') WHERE lower(name)='request_id';")
    $preReplayRequestIdIndexCount = @(Invoke-T21LocalJournalSqliteRead $SqliteExecutable $preReplayDatabasePath `
        "SELECT COUNT(*) FROM pragma_index_list('transactions') WHERE lower(name)='idx_transactions_request_id';")
    $expectedRequestIdSchemaCount = if ($ReceiptState -eq 'committed') { '1' } else { '0' }
    if ($preReplayRequestIdColumnCount.Count -ne 1 -or $preReplayRequestIdColumnCount[0] -cne $expectedRequestIdSchemaCount -or
        $preReplayRequestIdIndexCount.Count -ne 1 -or $preReplayRequestIdIndexCount[0] -cne $expectedRequestIdSchemaCount) {
        throw "The pre-replay SQLite snapshot has an unexpected request-ID schema for receipt state '$ReceiptState'."
    }
    $preReplayBalances = @(Invoke-T21LocalJournalSqliteRead $SqliteExecutable $preReplayDatabasePath `
        "SELECT uuid || '|' || name || '|' || typeof(balance) || '|' || printf('%.2f', balance) || '|' || version FROM player_balances WHERE uuid IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002') ORDER BY uuid;")
    $livePreReplayBalances = @(Invoke-T21LocalJournalSqliteRead $SqliteExecutable $database `
        "SELECT uuid || '|' || name || '|' || typeof(balance) || '|' || printf('%.2f', balance) || '|' || version FROM player_balances WHERE uuid IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002') ORDER BY uuid;")
    $expectedPreReplayBalances = if ($ReceiptState -eq 'committed') {
        @(
            '00000000-0000-0000-0000-000000000001|fixture-a|real|117.25|8',
            '00000000-0000-0000-0000-000000000002|fixture-b|real|0.00|3'
        )
    } else {
        @(
            '00000000-0000-0000-0000-000000000001|fixture-a|real|100.00|7',
            '00000000-0000-0000-0000-000000000002|fixture-b|real|0.00|3'
        )
    }
    if (($preReplayBalances -join "`n") -cne ($expectedPreReplayBalances -join "`n")) {
        throw "The SQLite replay receipt did not leave the expected pre-replay balances: $($preReplayBalances -join '; ')"
    }
    if (($livePreReplayBalances -join "`n") -cne ($preReplayBalances -join "`n")) {
        throw 'The retained pre-replay SQLite snapshot does not match the isolated runtime database.'
    }
    $expectedPreReplayReceipt = "2|00000000-0000-0000-0000-000000000001|fixture-a|DEPOSIT|real|17.25|real|100.00|real|117.25|VAULT_DEPOSIT|$timestamp|2|$requestId"
    $preReplayReplayTransaction = $null
    if ($ReceiptState -eq 'committed') {
        $preReplayReceipt = @(Invoke-T21LocalJournalSqliteRead $SqliteExecutable $preReplayDatabasePath `
            "SELECT id || '|' || uuid || '|' || name || '|' || type || '|' || typeof(amount) || '|' || printf('%.2f', amount) || '|' || typeof(balance_before) || '|' || printf('%.2f', balance_before) || '|' || typeof(balance_after) || '|' || printf('%.2f', balance_after) || '|' || source || '|' || timestamp || '|' || sequence || '|' || request_id FROM transactions WHERE request_id='$requestId';")
        $livePreReplayReceipt = @(Invoke-T21LocalJournalSqliteRead $SqliteExecutable $database `
            "SELECT id || '|' || uuid || '|' || name || '|' || type || '|' || typeof(amount) || '|' || printf('%.2f', amount) || '|' || typeof(balance_before) || '|' || printf('%.2f', balance_before) || '|' || typeof(balance_after) || '|' || printf('%.2f', balance_after) || '|' || source || '|' || timestamp || '|' || sequence || '|' || request_id FROM transactions WHERE request_id='$requestId';")
        if ($preReplayReceipt.Count -ne 1 -or $preReplayReceipt[0] -cne $expectedPreReplayReceipt) {
            throw "The matching SQLite request-ID receipt was not durable before replay: $($preReplayReceipt -join '; ')"
        }
        if ($livePreReplayReceipt.Count -ne 1 -or $livePreReplayReceipt[0] -cne $preReplayReceipt[0]) {
            throw 'The retained pre-replay SQLite snapshot does not contain the runtime database receipt.'
        }
        $preReplayReplayTransaction = $preReplayReceipt[0]
    }

    $fields = [string[]]@(
        'SYNCLOCAL1',
        (Get-T21LocalJournalBase64Url '00000000-0000-0000-0000-000000000001'),
        (Get-T21LocalJournalBase64Url '17.25'),
        (Get-T21LocalJournalBase64Url '117.25'),
        '7',
        'DEPOSIT',
        'VAULT_DEPOSIT',
        (Get-T21LocalJournalBase64Url $requestId),
        [string]$timestamp,
        (Get-T21LocalJournalBase64Url 'fixture-a')
    )
    $payload = $fields -join "`t"
    $checksum = Get-T21LocalJournalSha256 ([Text.Encoding]::UTF8.GetBytes($payload))
    $record = $payload + "`t" + $checksum
    $fileBytes = [Text.Encoding]::UTF8.GetBytes($record + "`n")
    $temporaryPath = Join-Path (Split-Path -Parent $journalPath) ('.local-persistence-seed-' + [guid]::NewGuid().ToString('N') + '.tmp')

    try {
        Write-T21LocalJournalCreateNew $archivePath $fileBytes
        Write-T21LocalJournalCreateNew $temporaryPath $fileBytes
        [IO.File]::Move($temporaryPath, $journalPath)
    } finally {
        if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
        }
    }

    return [pscustomobject][ordered]@{
        databasePath = '.plugdev/run/plugins/Syncmoney/t21-data/legacy-local.db'
        journalPath = '.plugdev/run/plugins/Syncmoney/t21-data/local-persistence.journal'
        archivePath = 'inputs/seeded-local-persistence.journal'
        preReplayDatabasePath = 'inputs/pre-replay-local.db'
        preReplayDatabaseBytes = [long]$preReplayDatabaseItem.Length
        preReplayDatabaseSha256 = $preReplayDatabaseSha256
        preReplayRequestIdColumnCount = [int]$preReplayRequestIdColumnCount[0]
        preReplayRequestIdIndexCount = [int]$preReplayRequestIdIndexCount[0]
        requestId = $requestId
        receiptState = $ReceiptState
        uuid = '00000000-0000-0000-0000-000000000001'
        playerName = 'fixture-a'
        delta = '17.25'
        balanceAfter = '117.25'
        eventVersion = 7
        type = 'DEPOSIT'
        source = 'VAULT_DEPOSIT'
        timestamp = [long]$timestamp
        preReplayBalances = $preReplayBalances
        preReplayTransaction = $historicalTransaction[0]
        preReplayReplayTransaction = $preReplayReplayTransaction
        record = $record
        recordSha256 = Get-T21LocalJournalSha256 ([Text.Encoding]::UTF8.GetBytes($record))
        fileBytes = [long]$fileBytes.Length
        fileSha256 = Get-T21LocalJournalSha256 $fileBytes
    }
}
