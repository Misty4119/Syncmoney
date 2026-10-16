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
