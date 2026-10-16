Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw 'PowerShell 7 or newer is required for the T21 local journal replay regression test.'
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixtureRoot = Join-Path $temporaryRoot "syncmoney-t21-local-journal-$([guid]::NewGuid().ToString('N'))"
$runRoot = Join-Path $fixtureRoot 'run'
$databasePath = Join-Path $runRoot '.plugdev\run\plugins\Syncmoney\t21-data\legacy-local.db'
$journalPath = Join-Path (Split-Path -Parent $databasePath) 'local-persistence.journal'
$archivePath = Join-Path $runRoot 'inputs\seeded-local-persistence.journal'
$oldMarker = [Environment]::GetEnvironmentVariable('T21_RUN_MARKER', 'Process')
$lock = $null
$sqliteCommand = Get-Command sqlite3.exe, sqlite3 -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $sqliteCommand -or $sqliteCommand.CommandType -ne 'Application') {
    throw 'sqlite3 is required for the T21 local journal replay helper regression test.'
}

try {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $databasePath), (Split-Path -Parent $archivePath) | Out-Null
    $fixtureSql = Join-Path $repositoryRoot 'syncmoney-testkit\src\testFixtures\resources\sql\legacy-local-sqlite-v1.3.2.sql'
    $seedCommand = '.read "' + ($fixtureSql -replace '\\', '/') + '"'
    $seedOutput = @(& $sqliteCommand.Source -batch $databasePath $seedCommand 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Could not seed the isolated SQLite test fixture: $($seedOutput -join ' ')" }

    . (Join-Path $PSScriptRoot 't21-local-persistence-journal.ps1')
    [Environment]::SetEnvironmentVariable('T21_RUN_MARKER', $runRoot, 'Process')
    $seed = New-T21LocalJournalReplaySeed -RunDirectory $runRoot -DatabasePath $databasePath -SqliteExecutable $sqliteCommand.Source
    $parsedSeedRecords = @(Get-T21LocalPersistenceJournalRecords -RunDirectory $runRoot `
        -DatabasePath $databasePath -JournalPath $journalPath)
    if ($parsedSeedRecords.Count -ne 1 -or
        [string]$parsedSeedRecords[0].uuid -cne '00000000-0000-0000-0000-000000000001' -or
        [string]$parsedSeedRecords[0].delta -cne '17.25' -or
        [string]$parsedSeedRecords[0].balanceAfter -cne '117.25' -or
        [long]$parsedSeedRecords[0].eventVersion -ne 7 -or
        [string]$parsedSeedRecords[0].type -cne 'DEPOSIT' -or
        [string]$parsedSeedRecords[0].source -cne 'VAULT_DEPOSIT' -or
        [string]$parsedSeedRecords[0].requestId -cne [string]$seed.requestId) {
        throw 'The journal inspector must decode the seeded SYNCLOCAL1 event and verify its fields.'
    }

    $preReplayBalances = @(Invoke-T21LocalJournalSqliteRead $sqliteCommand.Source $databasePath `
        "SELECT uuid || '|' || name || '|' || typeof(balance) || '|' || printf('%.2f', balance) || '|' || version FROM player_balances WHERE uuid IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002') ORDER BY uuid;")
    $expectedPreReplayBalances = @(
        '00000000-0000-0000-0000-000000000001|fixture-a|real|117.25|8',
        '00000000-0000-0000-0000-000000000002|fixture-b|real|0.00|3'
    )
    if (($preReplayBalances -join "`n") -cne ($expectedPreReplayBalances -join "`n")) {
        throw 'The SQLite receipt must be committed before the retained journal is replayed.'
    }
    if (($preReplayBalances -join "`n") -cne (@($seed.preReplayBalances) -join "`n")) {
        throw 'The seed report must describe the independently queried pre-replay SQLite balances.'
    }
    $preReplayDatabasePath = Join-Path $runRoot ([string]$seed.preReplayDatabasePath).Replace('/', '\')
    if (-not (Test-Path -LiteralPath $preReplayDatabasePath -PathType Leaf)) {
        throw 'The pre-replay SQLite database snapshot must be retained as an audit input.'
    }
    $preReplayDatabaseItem = Get-Item -LiteralPath $preReplayDatabasePath
    $preReplayDatabaseHash = (Get-FileHash -LiteralPath $preReplayDatabasePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($preReplayDatabaseItem.Length -ne [long]$seed.preReplayDatabaseBytes -or
        $preReplayDatabaseHash -cne [string]$seed.preReplayDatabaseSha256) {
        throw 'The retained pre-replay SQLite snapshot must match its reported byte count and SHA-256.'
    }
    $archivedBalances = @(Invoke-T21LocalJournalSqliteRead $sqliteCommand.Source $preReplayDatabasePath `
        "SELECT uuid || '|' || name || '|' || typeof(balance) || '|' || printf('%.2f', balance) || '|' || version FROM player_balances WHERE uuid IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002') ORDER BY uuid;")
    if (($archivedBalances -join "`n") -cne ($expectedPreReplayBalances -join "`n")) {
        throw 'The retained pre-replay SQLite snapshot must contain the committed balance and version.'
    }
    $preReplayReceipt = @(Invoke-T21LocalJournalSqliteRead $sqliteCommand.Source $preReplayDatabasePath `
        "SELECT id || '|' || uuid || '|' || name || '|' || type || '|' || typeof(amount) || '|' || printf('%.2f', amount) || '|' || typeof(balance_before) || '|' || printf('%.2f', balance_before) || '|' || typeof(balance_after) || '|' || printf('%.2f', balance_after) || '|' || source || '|' || timestamp || '|' || sequence || '|' || request_id FROM transactions WHERE request_id='$($seed.requestId)';")
    if ($preReplayReceipt.Count -ne 1 -or $preReplayReceipt[0] -cne [string]$seed.preReplayReplayTransaction) {
        throw 'The same request-ID receipt must already be durable while the journal remains pending.'
    }
    if ([int]$seed.preReplayRequestIdColumnCount -ne 1 -or [int]$seed.preReplayRequestIdIndexCount -ne 1) {
        throw 'The committed-receipt fixture must retain its request-ID column and unique index before candidate startup.'
    }

    $noReceiptRunRoot = Join-Path $fixtureRoot 'run-no-receipt'
    $noReceiptDatabasePath = Join-Path $noReceiptRunRoot '.plugdev\run\plugins\Syncmoney\t21-data\legacy-local.db'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $noReceiptDatabasePath), (Join-Path $noReceiptRunRoot 'inputs') | Out-Null
    $noReceiptSeedOutput = @(& $sqliteCommand.Source -batch $noReceiptDatabasePath $seedCommand 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Could not seed the no-receipt SQLite test fixture: $($noReceiptSeedOutput -join ' ')" }
    [Environment]::SetEnvironmentVariable('T21_RUN_MARKER', $noReceiptRunRoot, 'Process')
    $noReceiptSeed = New-T21LocalJournalReplaySeed -RunDirectory $noReceiptRunRoot `
        -DatabasePath $noReceiptDatabasePath -SqliteExecutable $sqliteCommand.Source -ReceiptState 'absent'
    $noReceiptRequestIdColumn = @(Invoke-T21LocalJournalSqliteRead $sqliteCommand.Source $noReceiptDatabasePath `
        "SELECT COUNT(*) FROM pragma_table_info('transactions') WHERE lower(name)='request_id';")
    $noReceiptRequestIdIndex = @(Invoke-T21LocalJournalSqliteRead $sqliteCommand.Source $noReceiptDatabasePath `
        "SELECT COUNT(*) FROM pragma_index_list('transactions') WHERE lower(name)='idx_transactions_request_id';")
    if ($noReceiptRequestIdColumn.Count -ne 1 -or $noReceiptRequestIdColumn[0] -cne '0' -or
        $noReceiptRequestIdIndex.Count -ne 1 -or $noReceiptRequestIdIndex[0] -cne '0') {
        throw 'The no-receipt fixture must preserve the legacy transactions schema for candidate startup migration.'
    }
    $noReceiptBalances = @(Invoke-T21LocalJournalSqliteRead $sqliteCommand.Source $noReceiptDatabasePath `
        "SELECT uuid || '|' || name || '|' || typeof(balance) || '|' || printf('%.2f', balance) || '|' || version FROM player_balances WHERE uuid IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002') ORDER BY uuid;")
    $expectedNoReceiptBalances = @(
        '00000000-0000-0000-0000-000000000001|fixture-a|real|100.00|7',
        '00000000-0000-0000-0000-000000000002|fixture-b|real|0.00|3'
    )
    if (($noReceiptBalances -join "`n") -cne ($expectedNoReceiptBalances -join "`n") -or
        ($noReceiptBalances -join "`n") -cne (@($noReceiptSeed.preReplayBalances) -join "`n")) {
        throw 'The no-receipt replay fixture must retain the pre-write balance and version.'
    }
    $noReceiptSnapshotPath = Join-Path $noReceiptRunRoot ([string]$noReceiptSeed.preReplayDatabasePath).Replace('/', '\')
    $noReceiptSnapshotColumn = @(Invoke-T21LocalJournalSqliteRead $sqliteCommand.Source $noReceiptSnapshotPath `
        "SELECT COUNT(*) FROM pragma_table_info('transactions') WHERE lower(name)='request_id';")
    $noReceiptSnapshotIndex = @(Invoke-T21LocalJournalSqliteRead $sqliteCommand.Source $noReceiptSnapshotPath `
        "SELECT COUNT(*) FROM pragma_index_list('transactions') WHERE lower(name)='idx_transactions_request_id';")
    $noReceiptTransactionCount = @(Invoke-T21LocalJournalSqliteRead $sqliteCommand.Source $noReceiptDatabasePath `
        'SELECT COUNT(*) FROM transactions;')
    $noReceiptHistoricalTransaction = @(Invoke-T21LocalJournalSqliteRead $sqliteCommand.Source $noReceiptDatabasePath `
        "SELECT id || '|' || uuid || '|' || name || '|' || type || '|' || printf('%.2f', amount) || '|' || source FROM transactions WHERE id=1;")
    if ($noReceiptSnapshotColumn.Count -ne 1 -or $noReceiptSnapshotColumn[0] -cne '0' -or
        $noReceiptSnapshotIndex.Count -ne 1 -or $noReceiptSnapshotIndex[0] -cne '0' -or
        [int]$noReceiptSeed.preReplayRequestIdColumnCount -ne 0 -or
        [int]$noReceiptSeed.preReplayRequestIdIndexCount -ne 0 -or
        $noReceiptTransactionCount.Count -ne 1 -or $noReceiptTransactionCount[0] -cne '1' -or
        $noReceiptHistoricalTransaction.Count -ne 1 -or
        $noReceiptHistoricalTransaction[0] -cne '1|00000000-0000-0000-0000-000000000001|fixture-a|DEPOSIT|100.00|TEST' -or
        $null -ne $noReceiptSeed.preReplayReplayTransaction -or
        [string]$noReceiptSeed.receiptState -cne 'absent') {
        throw 'The retained no-receipt snapshot must prove the legacy schema had no request-ID column, index, or receipt before candidate startup.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $noReceiptRunRoot ([string]$noReceiptSeed.archivePath).Replace('/', '\')) -PathType Leaf)) {
        throw 'The no-receipt scenario must retain the seeded journal input.'
    }

    if (-not (Test-Path -LiteralPath $journalPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
        throw 'The replay seed and its retained input copy must both be written.'
    }
    $actualJournal = [IO.File]::ReadAllBytes($journalPath)
    $actualArchive = [IO.File]::ReadAllBytes($archivePath)
    if ([Convert]::ToBase64String($actualJournal) -cne [Convert]::ToBase64String($actualArchive)) {
        throw 'The live journal and retained seed input differ.'
    }
    if ($actualJournal.Length -ne [long]$seed.fileBytes -or
        (Get-T21LocalJournalSha256 $actualJournal) -cne [string]$seed.fileSha256) {
        throw 'The seed report does not match the retained journal bytes.'
    }
    $record = [Text.Encoding]::UTF8.GetString($actualJournal).TrimEnd("`r", "`n")
    $parts = $record.Split("`t")
    if ($parts.Count -ne 11 -or $parts[0] -cne 'SYNCLOCAL1') {
        throw 'The generated seed must have the existing 11-field SYNCLOCAL1 framing.'
    }
    $payload = ($parts[0..9] -join "`t")
    if ((Get-T21LocalJournalSha256 ([Text.Encoding]::UTF8.GetBytes($payload))) -cne $parts[10]) {
        throw 'The generated seed checksum does not cover the complete journal payload.'
    }

    [Environment]::SetEnvironmentVariable('T21_RUN_MARKER', (Join-Path $fixtureRoot 'wrong'), 'Process')
    $markerRejected = $false
    try {
        New-T21LocalJournalReplaySeed -RunDirectory $runRoot -DatabasePath $databasePath -SqliteExecutable $sqliteCommand.Source | Out-Null
    } catch {
        $markerRejected = $_.Exception.Message -match 'exact isolated T21 run marker'
    }
    if (-not $markerRejected) { throw 'The helper must reject a mismatched run marker.' }

    [Environment]::SetEnvironmentVariable('T21_RUN_MARKER', $runRoot, 'Process')
    $outsideDatabaseRejected = $false
    try {
        New-T21LocalJournalReplaySeed -RunDirectory $runRoot -DatabasePath (Join-Path $fixtureRoot 'outside.db') -SqliteExecutable $sqliteCommand.Source | Out-Null
    } catch {
        $outsideDatabaseRejected = $_.Exception.Message -match 'restricted to the isolated historical M04 SQLite path'
    }
    if (-not $outsideDatabaseRejected) { throw 'The helper must reject a database path outside the isolated M04 fixture.' }

    $overwriteRejected = $false
    try {
        New-T21LocalJournalReplaySeed -RunDirectory $runRoot -DatabasePath $databasePath -SqliteExecutable $sqliteCommand.Source | Out-Null
    } catch {
        $overwriteRejected = $_.Exception.Message -match 'pre-existing LOCAL persistence journal'
    }
    if (-not $overwriteRejected) { throw 'The helper must never overwrite a pre-existing journal.' }

    $lockRunRoot = Join-Path $fixtureRoot 'lock-run'
    $lockDatabasePath = Join-Path $lockRunRoot '.plugdev\run\plugins\Syncmoney\t21-data\legacy-local.db'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $lockDatabasePath) | Out-Null
    $lockSeedOutput = @(& $sqliteCommand.Source -batch -noheader $lockDatabasePath 'PRAGMA journal_mode=WAL;' $seedCommand 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Could not seed the isolated SQLite write-lock fixture: $($lockSeedOutput -join ' ')" }
    $lockJournalMode = @(& $sqliteCommand.Source -batch -noheader $lockDatabasePath 'PRAGMA journal_mode;' 2>&1)
    if ($LASTEXITCODE -ne 0 -or $lockJournalMode.Count -ne 1 -or $lockJournalMode[0] -cne 'wal') {
        throw "The isolated SQLite write-lock fixture must use WAL mode; observed '$($lockJournalMode -join ' ')'."
    }
    [Environment]::SetEnvironmentVariable('T21_RUN_MARKER', $lockRunRoot, 'Process')

    $lock = Start-T21LocalSqliteWriterLock -RunDirectory $lockRunRoot `
        -DatabasePath $lockDatabasePath -SqliteExecutable $sqliteCommand.Source
    $heldLock = Assert-T21LocalSqliteWriterLockHeld -Lock $lock
    if (-not [bool]$heldLock.writeLockObserved -or
        [int]$heldLock.verifierExitCode -eq 0 -or
        [string]$heldLock.verifierOutput -notmatch 'database is locked') {
        throw 'The controlled SQLite delay must prove a second writer is blocked by the held transaction.'
    }
    if ([string]$lock.journalMode -cne 'wal' -or
        [IO.Path]::GetFullPath([string]$lock.databasePath) -cne [IO.Path]::GetFullPath($lockDatabasePath) -or
        [int]$lock.processId -le 0 -or
        [string]$lock.sqliteExecutable -cne [IO.Path]::GetFullPath($sqliteCommand.Source)) {
        throw 'The isolated WAL lock record must retain its mode, database, executable, and process identity.'
    }

    $lockSnapshotPath = Join-Path $lockRunRoot 'inputs\pre-lock-snapshot.db'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $lockSnapshotPath) | Out-Null
    Invoke-T21LocalJournalSqliteBackup $sqliteCommand.Source $lockDatabasePath $lockSnapshotPath
    $lockSnapshotBalances = @(Invoke-T21LocalJournalSqliteRead $sqliteCommand.Source $lockSnapshotPath `
        "SELECT uuid || '|' || printf('%.2f', balance) FROM player_balances WHERE uuid IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002') ORDER BY uuid;")
    if ($lockSnapshotBalances.Count -ne 2 -or $lockSnapshotBalances[0] -cne '00000000-0000-0000-0000-000000000001|100.00' -or
        $lockSnapshotBalances[1] -cne '00000000-0000-0000-0000-000000000002|0.00') {
        throw 'A read-only SQLite backup under the WAL writer lock must retain the committed pre-replay balances.'
    }

    $outsideLockRejected = $false
    try {
        Start-T21LocalSqliteWriterLock -RunDirectory $lockRunRoot `
            -DatabasePath (Join-Path $fixtureRoot 'outside.db') -SqliteExecutable $sqliteCommand.Source | Out-Null
    } catch {
        $outsideLockRejected = $_.Exception.Message -match 'restricted to the isolated historical M04 SQLite path'
    }
    if (-not $outsideLockRejected) { throw 'SQLite write-lock injection must reject a database outside the M04 fixture.' }

    $releasedLock = Stop-T21LocalSqliteWriterLock -Lock $lock
    $lock = $null
    if (-not [bool]$releasedLock.processExitVerified -or [int]$releasedLock.exitCode -ne 0) {
        throw 'The SQLite lock owner must release its transaction and exit normally.'
    }
    $releaseProbe = @(& $sqliteCommand.Source -batch -cmd 'PRAGMA busy_timeout=0;' `
        $lockDatabasePath 'BEGIN IMMEDIATE; ROLLBACK;' 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "The SQLite database must accept a writer after explicit lock release: $($releaseProbe -join ' ')"
    }

    Write-Output 'PASS: journal event decoding, committed receipt state, legacy no-receipt schema snapshot, WAL writer exclusion, framing/checksum, isolated paths, and overwrite refusal'
} finally {
    if ($null -ne $lock -and $null -ne $lock.process) {
        try {
            [Environment]::SetEnvironmentVariable('T21_RUN_MARKER', [string]$lock.runRoot, 'Process')
            if (-not $lock.process.HasExited) {
                Stop-T21LocalSqliteWriterLock -Lock $lock | Out-Null
            }
        } catch {
            throw "Could not release the owned SQLite test writer lock before cleanup: $($_.Exception.Message)"
        }
    }
    [Environment]::SetEnvironmentVariable('T21_RUN_MARKER', $oldMarker, 'Process')
    $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot)
    $temporaryPrefix = $temporaryRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedFixture.StartsWith($temporaryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean a test fixture outside the temp directory: $resolvedFixture"
    }
    if (Test-Path -LiteralPath $resolvedFixture) {
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
    }
}
