$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$validationPath = Join-Path $PSScriptRoot 't21-audit-schema-upgrade-validation.ps1'
if (-not (Test-Path -LiteralPath $validationPath -PathType Leaf)) {
    throw 'The T21 v10-to-v11 audit-schema upgrade validator is missing.'
}
. $validationPath

function Assert-T21Upgrade([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

function Copy-T21UpgradeState([System.Collections.IDictionary]$State) {
    $copy = [ordered]@{}
    foreach ($key in $State.Keys) { $copy[$key] = $State[$key] }
    return $copy
}

function New-T21UpgradeColumnMetadata(
    [string]$DatabaseProduct,
    [System.Collections.IDictionary]$Definition,
    [int]$Position,
    [string]$DatabaseName
) {
    if ($DatabaseProduct -ceq 'PostgreSQL') {
        return [ordered]@{
            table_catalog = $DatabaseName
            table_schema = 'public'
            table_name = 'syncmoney_audit_log'
            column_name = $Definition.name
            ordinal_position = $Position
            column_default = $Definition.defaultValue
            is_nullable = $Definition.nullable
            data_type = $Definition.postgresDataType
            character_maximum_length = $Definition.characterLength
            numeric_precision = $Definition.numericPrecision
            numeric_scale = $Definition.numericScale
            udt_name = $Definition.postgresUdt
            is_identity = 'NO'
            is_generated = 'NEVER'
            generation_expression = $null
            is_updatable = 'YES'
        }
    }

    $columnType = if ($DatabaseProduct -ceq 'MariaDB') { $Definition.mariaDbType } else { $Definition.mySqlType }
    $hasDeclaredDefault = $Definition.hasDefault
    $usesImplicitMariaDbNull = $DatabaseProduct -ceq 'MariaDB' -and $Definition.nullable -ceq 'YES' -and -not $hasDeclaredDefault
    return [ordered]@{
        table_schema = $DatabaseName
        table_name = 'syncmoney_audit_log'
        column_name = $Definition.name
        ordinal_position = $Position
        column_default = if ($hasDeclaredDefault) { $Definition.defaultValue } elseif ($usesImplicitMariaDbNull) { 'NULL' } else { $null }
        column_default_is_null = if ($hasDeclaredDefault -or $usesImplicitMariaDbNull) { 'false' } else { 'true' }
        is_nullable = $Definition.nullable
        column_type = $columnType
        extra = ''
        character_set_name = if ($Definition.character) { 'utf8mb4' } else { $null }
        collation_name = if ($Definition.character -and $DatabaseProduct -ceq 'MySQL') { 'utf8mb4_0900_ai_ci' } elseif ($Definition.character) { 'utf8mb4_general_ci' } else { $null }
        generation_expression = if ($DatabaseProduct -ceq 'MariaDB') { $null } else { '' }
    }
}

function New-T21UpgradeState([string]$DatabaseProduct, [bool]$AfterUpgrade) {
    $v10Columns = @(
        'id', 'timestamp', 'type', 'player_uuid', 'player_name', 'amount', 'balance_after',
        'source', 'server', 'target_uuid', 'target_name', 'reason', 'merged_count'
    )
    $v11Columns = @($v10Columns + @('request_id', 'sequence'))
    $columnDefinitions = @(
        [ordered]@{ name='id'; nullable='NO'; hasDefault=$false; defaultValue=$null; mySqlType='varchar(36)'; mariaDbType='varchar(36)'; postgresDataType='character varying'; postgresUdt='varchar'; character=$true; characterLength=36; numericPrecision=$null; numericScale=$null }
        [ordered]@{ name='timestamp'; nullable='NO'; hasDefault=$false; defaultValue=$null; mySqlType='bigint'; mariaDbType='bigint(20)'; postgresDataType='bigint'; postgresUdt='int8'; character=$false; characterLength=$null; numericPrecision=64; numericScale=0 }
        [ordered]@{ name='type'; nullable='NO'; hasDefault=$false; defaultValue=$null; mySqlType='varchar(20)'; mariaDbType='varchar(20)'; postgresDataType='character varying'; postgresUdt='varchar'; character=$true; characterLength=20; numericPrecision=$null; numericScale=$null }
        [ordered]@{ name='player_uuid'; nullable='NO'; hasDefault=$false; defaultValue=$null; mySqlType='varchar(36)'; mariaDbType='varchar(36)'; postgresDataType='character varying'; postgresUdt='varchar'; character=$true; characterLength=36; numericPrecision=$null; numericScale=$null }
        [ordered]@{ name='player_name'; nullable='YES'; hasDefault=$false; defaultValue=$null; mySqlType='varchar(16)'; mariaDbType='varchar(16)'; postgresDataType='character varying'; postgresUdt='varchar'; character=$true; characterLength=16; numericPrecision=$null; numericScale=$null }
        [ordered]@{ name='amount'; nullable='NO'; hasDefault=$false; defaultValue=$null; mySqlType='decimal(20,2)'; mariaDbType='decimal(20,2)'; postgresDataType='numeric'; postgresUdt='numeric'; character=$false; characterLength=$null; numericPrecision=20; numericScale=2 }
        [ordered]@{ name='balance_after'; nullable='NO'; hasDefault=$false; defaultValue=$null; mySqlType='decimal(20,2)'; mariaDbType='decimal(20,2)'; postgresDataType='numeric'; postgresUdt='numeric'; character=$false; characterLength=$null; numericPrecision=20; numericScale=2 }
        [ordered]@{ name='source'; nullable='NO'; hasDefault=$false; defaultValue=$null; mySqlType='varchar(30)'; mariaDbType='varchar(30)'; postgresDataType='character varying'; postgresUdt='varchar'; character=$true; characterLength=30; numericPrecision=$null; numericScale=$null }
        [ordered]@{ name='server'; nullable='NO'; hasDefault=$false; defaultValue=$null; mySqlType='varchar(50)'; mariaDbType='varchar(50)'; postgresDataType='character varying'; postgresUdt='varchar'; character=$true; characterLength=50; numericPrecision=$null; numericScale=$null }
        [ordered]@{ name='target_uuid'; nullable='YES'; hasDefault=$false; defaultValue=$null; mySqlType='varchar(36)'; mariaDbType='varchar(36)'; postgresDataType='character varying'; postgresUdt='varchar'; character=$true; characterLength=36; numericPrecision=$null; numericScale=$null }
        [ordered]@{ name='target_name'; nullable='YES'; hasDefault=$false; defaultValue=$null; mySqlType='varchar(16)'; mariaDbType='varchar(16)'; postgresDataType='character varying'; postgresUdt='varchar'; character=$true; characterLength=16; numericPrecision=$null; numericScale=$null }
        [ordered]@{ name='reason'; nullable='YES'; hasDefault=$false; defaultValue=$null; mySqlType='varchar(255)'; mariaDbType='varchar(255)'; postgresDataType='character varying'; postgresUdt='varchar'; character=$true; characterLength=255; numericPrecision=$null; numericScale=$null }
        [ordered]@{ name='merged_count'; nullable='YES'; hasDefault=$true; defaultValue='1'; mySqlType='int'; mariaDbType='int(11)'; postgresDataType='integer'; postgresUdt='int4'; character=$false; characterLength=$null; numericPrecision=32; numericScale=0 }
        [ordered]@{ name='request_id'; nullable='YES'; hasDefault=$false; defaultValue=$null; mySqlType='varchar(36)'; mariaDbType='varchar(36)'; postgresDataType='character varying'; postgresUdt='varchar'; character=$true; characterLength=36; numericPrecision=$null; numericScale=$null }
        [ordered]@{ name='sequence'; nullable='YES'; hasDefault=$true; defaultValue='0'; mySqlType='bigint'; mariaDbType='bigint(20)'; postgresDataType='bigint'; postgresUdt='int8'; character=$false; characterLength=$null; numericPrecision=64; numericScale=0 }
    )
    $databaseName = 'syncmoney_t21_0123abcd'
    $definitionCount = if ($AfterUpgrade) { $v11Columns.Count } else { $v10Columns.Count }
    $columns = @(
        for ($index = 0; $index -lt $definitionCount; $index++) {
            New-T21UpgradeColumnMetadata $DatabaseProduct $columnDefinitions[$index] ($index + 1) $databaseName
        }
    )
    $primaryIndex = if ($DatabaseProduct -ceq 'PostgreSQL') { 'syncmoney_audit_log_pkey' } else { 'PRIMARY' }
    $indexNames = if ($AfterUpgrade) {
        @($primaryIndex, 'idx_audit_timestamp', 'idx_audit_player', 'idx_audit_type_timestamp_sequence')
    } else { @($primaryIndex) }
    $indexes = @(
        foreach ($indexName in $indexNames) {
            if ($DatabaseProduct -ceq 'PostgreSQL') {
                $indexColumnSql = switch ($indexName) {
                    'syncmoney_audit_log_pkey' { 'id' }
                    'idx_audit_timestamp' { 'timestamp DESC, sequence DESC' }
                    'idx_audit_player' { 'player_uuid, timestamp DESC, sequence DESC' }
                    'idx_audit_type_timestamp_sequence' { 'type, timestamp DESC, sequence DESC' }
                }
                $kind = if ($indexName -ceq 'syncmoney_audit_log_pkey') { 'CREATE UNIQUE INDEX' } else { 'CREATE INDEX' }
                $isPrimaryIndex = $indexName -ceq 'syncmoney_audit_log_pkey'
                [ordered]@{
                    name = $indexName
                    definition = "$kind $indexName ON public.syncmoney_audit_log USING btree ($indexColumnSql)"
                    isPrimaryConstraint = $isPrimaryIndex
                    isPrimaryIndex = $isPrimaryIndex
                    isUnique = $isPrimaryIndex
                    isValid = $true
                    isReady = $true
                    isLive = $true
                }
            } else {
                $indexColumns = switch ($indexName) {
                    'PRIMARY' { @([ordered]@{ position = 1; columnName = 'id'; direction = 'A'; subPart = $null }) }
                    'idx_audit_timestamp' { @(
                        [ordered]@{ position = 1; columnName = 'timestamp'; direction = 'D'; subPart = $null }
                        [ordered]@{ position = 2; columnName = 'sequence'; direction = 'D'; subPart = $null }
                    ) }
                    'idx_audit_player' { @(
                        [ordered]@{ position = 1; columnName = 'player_uuid'; direction = 'A'; subPart = $null }
                        [ordered]@{ position = 2; columnName = 'timestamp'; direction = 'D'; subPart = $null }
                        [ordered]@{ position = 3; columnName = 'sequence'; direction = 'D'; subPart = $null }
                    ) }
                    'idx_audit_type_timestamp_sequence' { @(
                        [ordered]@{ position = 1; columnName = 'type'; direction = 'A'; subPart = $null }
                        [ordered]@{ position = 2; columnName = 'timestamp'; direction = 'D'; subPart = $null }
                        [ordered]@{ position = 3; columnName = 'sequence'; direction = 'D'; subPart = $null }
                    ) }
                }
                [ordered]@{ name = $indexName; nonUnique = [int]($indexName -cne 'PRIMARY'); indexType = 'BTREE'; visibility = 'YES'; columns = $indexColumns }
            }
        }
    )

    $row = [ordered]@{
        id = '00000000-0000-0000-0000-000000000330'
        timestamp = 1791936000000
        type = 'DEPOSIT'
        player_uuid = '00000000-0000-0000-0000-000000000330'
        player_name = 'T21Fixture'
        amount = 1.25
        balance_after = 99.99
        source = 'TEST'
        server = 't21-schema-upgrade'
        target_uuid = $null
        target_name = $null
        reason = 'v10 upgrade preservation sentinel'
        merged_count = 1
    }
    if ($AfterUpgrade) {
        $row.request_id = $null
        $row.sequence = 0
    }
    $auditVersion = if ($AfterUpgrade) { 11 } else { 10 }
    $description = if ($AfterUpgrade) { 'v1.1.0 Cursor pagination optimization' } else { 'T21 v10 migration sentinel' }
    return [ordered]@{
        mainVersion = 1
        mainVersionRowJson = '{"id":"1","version":1,"description":"T21 pre-existing main schema"}'
        auditVersion = $auditVersion
        auditVersionRowJson = (ConvertTo-Json -InputObject ([ordered]@{ id = 'audit'; version = $auditVersion; description = $description }) -Compress)
        auditTableExists = $true
        playersTableExists = [bool]$AfterUpgrade
        playerRows = if ($AfterUpgrade) { [long]0 } else { $null }
        auditColumnsJson = ConvertTo-Json -InputObject ([object[]]$columns) -Depth 8 -Compress
        auditIndexesJson = ConvertTo-Json -InputObject ([object[]]$indexes) -Depth 8 -Compress
        auditRowsJson = ConvertTo-Json -InputObject ([object[]]@($row)) -Depth 8 -Compress
    }
}

foreach ($databaseProduct in @('MySQL', 'MariaDB', 'PostgreSQL')) {
    Assert-T21Upgrade (Test-T21AuditSchemaV10UpgradeTarget -DatabaseProduct $databaseProduct -PlatformEndpoint Minimum) `
        "The minimum-Paper target was not admitted for $databaseProduct."
    Assert-T21Upgrade (Test-T21AuditSchemaV10UpgradeTarget -DatabaseProduct $databaseProduct -PlatformEndpoint Maximum) `
        "The user-pinned maximum-Paper target was not admitted for $databaseProduct."
    $before = New-T21UpgradeState $databaseProduct $false
    $after = New-T21UpgradeState $databaseProduct $true
    Assert-T21Upgrade (Test-T21AuditSchemaV10UpgradeState $before $after -DatabaseProduct $databaseProduct) `
        "The valid $databaseProduct v10-to-v11 schema and sentinel transition was rejected."

    $wrongColumnType = Copy-T21UpgradeState $after
    $columns = @(ConvertFrom-Json -InputObject $wrongColumnType.auditColumnsJson -AsHashtable)
    $sequenceColumn = @($columns | Where-Object { $_.column_name -ceq 'sequence' })[0]
    if ($databaseProduct -ceq 'PostgreSQL') {
        $sequenceColumn.data_type = 'integer'
        $sequenceColumn.udt_name = 'int4'
        $sequenceColumn.numeric_precision = 32
    } else {
        $sequenceColumn.column_type = if ($databaseProduct -ceq 'MariaDB') { 'int(11)' } else { 'int' }
    }
    $wrongColumnType.auditColumnsJson = ConvertTo-Json -InputObject ([object[]]$columns) -Depth 10 -Compress
    Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $wrongColumnType -DatabaseProduct $databaseProduct)) `
        "The $databaseProduct validator accepted an integer sequence column instead of BIGINT."

    $wrongRequestIdLength = Copy-T21UpgradeState $after
    $columns = @(ConvertFrom-Json -InputObject $wrongRequestIdLength.auditColumnsJson -AsHashtable)
    $requestIdColumn = @($columns | Where-Object { $_.column_name -ceq 'request_id' })[0]
    if ($databaseProduct -ceq 'PostgreSQL') {
        $requestIdColumn.character_maximum_length = 8
    } else {
        $requestIdColumn.column_type = 'varchar(8)'
    }
    $wrongRequestIdLength.auditColumnsJson = ConvertTo-Json -InputObject ([object[]]$columns) -Depth 10 -Compress
    Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $wrongRequestIdLength -DatabaseProduct $databaseProduct)) `
        "The $databaseProduct validator accepted request_id VARCHAR(8) instead of VARCHAR(36)."

    $wrongColumnDefault = Copy-T21UpgradeState $after
    $columns = @(ConvertFrom-Json -InputObject $wrongColumnDefault.auditColumnsJson -AsHashtable)
    $sequenceColumn = @($columns | Where-Object { $_.column_name -ceq 'sequence' })[0]
    $sequenceColumn.column_default = '7'
    if ($databaseProduct -ne 'PostgreSQL') { $sequenceColumn.column_default_is_null = 'false' }
    $wrongColumnDefault.auditColumnsJson = ConvertTo-Json -InputObject ([object[]]$columns) -Depth 10 -Compress
    Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $wrongColumnDefault -DatabaseProduct $databaseProduct)) `
        "The $databaseProduct validator accepted a sequence default other than zero."

    $wrongColumnDefaultType = Copy-T21UpgradeState $after
    $columns = @(ConvertFrom-Json -InputObject $wrongColumnDefaultType.auditColumnsJson -AsHashtable)
    $sequenceColumn = @($columns | Where-Object { $_.column_name -ceq 'sequence' })[0]
    $sequenceColumn.column_default = 0
    if ($databaseProduct -ne 'PostgreSQL') { $sequenceColumn.column_default_is_null = 'false' }
    $wrongColumnDefaultType.auditColumnsJson = ConvertTo-Json -InputObject ([object[]]$columns) -Depth 10 -Compress
    Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $wrongColumnDefaultType -DatabaseProduct $databaseProduct)) `
        "The $databaseProduct validator accepted a numeric report value for the SQL column default."

    $wrongColumnNullability = Copy-T21UpgradeState $after
    $columns = @(ConvertFrom-Json -InputObject $wrongColumnNullability.auditColumnsJson -AsHashtable)
    $sequenceColumn = @($columns | Where-Object { $_.column_name -ceq 'sequence' })[0]
    $sequenceColumn.is_nullable = 'NO'
    $wrongColumnNullability.auditColumnsJson = ConvertTo-Json -InputObject ([object[]]$columns) -Depth 10 -Compress
    Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $wrongColumnNullability -DatabaseProduct $databaseProduct)) `
        "The $databaseProduct validator accepted incorrect sequence nullability."

    $wrongBalance = Copy-T21UpgradeState $after
    $rows = @(ConvertFrom-Json -InputObject $wrongBalance.auditRowsJson -AsHashtable)
    $rows[0].balance_after = 100.00
    $wrongBalance.auditRowsJson = ConvertTo-Json -InputObject ([object[]]$rows) -Depth 8 -Compress
    Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $wrongBalance -DatabaseProduct $databaseProduct)) `
        "The $databaseProduct validator accepted a changed audit sentinel balance."

    $missingIndex = Copy-T21UpgradeState $after
    $indexes = @(ConvertFrom-Json -InputObject $missingIndex.auditIndexesJson -AsHashtable)
    $missingIndex.auditIndexesJson = ConvertTo-Json -InputObject ([object[]]@($indexes | Where-Object { $_.name -cne 'idx_audit_player' })) -Depth 8 -Compress
    Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $missingIndex -DatabaseProduct $databaseProduct)) `
        "The $databaseProduct validator accepted a missing v11 player index."

    $wrongVersion = Copy-T21UpgradeState $after
    $wrongVersion.auditVersion = 10
    Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $wrongVersion -DatabaseProduct $databaseProduct)) `
        "The $databaseProduct validator accepted v10 metadata after migration."

    $wrongSentinel = Copy-T21UpgradeState $after
    $rows = @(ConvertFrom-Json -InputObject $wrongSentinel.auditRowsJson -AsHashtable)
    $rows[0].reason = 'different sentinel'
    $wrongSentinel.auditRowsJson = ConvertTo-Json -InputObject ([object[]]$rows) -Depth 8 -Compress
    Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $wrongSentinel -DatabaseProduct $databaseProduct)) `
        "The $DatabaseProduct validator accepted changed sentinel data."

    $wrongIndexOrder = Copy-T21UpgradeState $after
    $indexes = @(ConvertFrom-Json -InputObject $wrongIndexOrder.auditIndexesJson -AsHashtable)
    if ($databaseProduct -ceq 'PostgreSQL') {
        $indexes | Where-Object { $_.name -ceq 'idx_audit_timestamp' } | ForEach-Object {
            $_.definition = 'CREATE INDEX idx_audit_timestamp ON public.syncmoney_audit_log USING btree (sequence DESC, timestamp DESC)'
        }
    } else {
        $indexes | Where-Object { $_.name -ceq 'idx_audit_timestamp' } | ForEach-Object { $_.columns[0].columnName = 'sequence' }
    }
    $wrongIndexOrder.auditIndexesJson = ConvertTo-Json -InputObject ([object[]]$indexes) -Depth 8 -Compress
    Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $wrongIndexOrder -DatabaseProduct $databaseProduct)) `
        "The $DatabaseProduct validator accepted the wrong v11 index column order."

    if ($databaseProduct -ceq 'PostgreSQL') {
        $nonUniquePrimaryIndex = Copy-T21UpgradeState $after
        $indexes = @(ConvertFrom-Json -InputObject $nonUniquePrimaryIndex.auditIndexesJson -AsHashtable)
        $indexes | Where-Object { $_.name -ceq 'syncmoney_audit_log_pkey' } | ForEach-Object {
            $_.definition = 'CREATE INDEX syncmoney_audit_log_pkey ON public.syncmoney_audit_log USING btree (id)'
            $_.isPrimaryConstraint = $false
            $_.isPrimaryIndex = $false
            $_.isUnique = $false
        }
        $nonUniquePrimaryIndex.auditIndexesJson = ConvertTo-Json -InputObject ([object[]]$indexes) -Depth 8 -Compress
        Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $nonUniquePrimaryIndex -DatabaseProduct $databaseProduct)) `
            'The PostgreSQL validator accepted a non-unique syncmoney_audit_log_pkey index.'

        $nonPrimaryUniqueIndex = Copy-T21UpgradeState $after
        $indexes = @(ConvertFrom-Json -InputObject $nonPrimaryUniqueIndex.auditIndexesJson -AsHashtable)
        $indexes | Where-Object { $_.name -ceq 'syncmoney_audit_log_pkey' } | ForEach-Object {
            $_.isPrimaryConstraint = $false
            $_.isPrimaryIndex = $false
            $_.isUnique = $true
        }
        $nonPrimaryUniqueIndex.auditIndexesJson = ConvertTo-Json -InputObject ([object[]]$indexes) -Depth 8 -Compress
        Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $nonPrimaryUniqueIndex -DatabaseProduct $databaseProduct)) `
            'The PostgreSQL validator accepted a unique index named like the primary key without a primary constraint.'

        $invalidPrimaryIndex = Copy-T21UpgradeState $after
        $indexes = @(ConvertFrom-Json -InputObject $invalidPrimaryIndex.auditIndexesJson -AsHashtable)
        $indexes | Where-Object { $_.name -ceq 'syncmoney_audit_log_pkey' } | ForEach-Object { $_.isValid = $false }
        $invalidPrimaryIndex.auditIndexesJson = ConvertTo-Json -InputObject ([object[]]$indexes) -Depth 8 -Compress
        Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $invalidPrimaryIndex -DatabaseProduct $databaseProduct)) `
            'The PostgreSQL validator accepted an invalid unique primary index.'

        $unreadyPrimaryIndex = Copy-T21UpgradeState $after
        $indexes = @(ConvertFrom-Json -InputObject $unreadyPrimaryIndex.auditIndexesJson -AsHashtable)
        $indexes | Where-Object { $_.name -ceq 'syncmoney_audit_log_pkey' } | ForEach-Object { $_.isReady = $false }
        $unreadyPrimaryIndex.auditIndexesJson = ConvertTo-Json -InputObject ([object[]]$indexes) -Depth 8 -Compress
        Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $unreadyPrimaryIndex -DatabaseProduct $databaseProduct)) `
            'The PostgreSQL validator accepted a primary index that is not ready for inserts.'

        $notLivePrimaryIndex = Copy-T21UpgradeState $after
        $indexes = @(ConvertFrom-Json -InputObject $notLivePrimaryIndex.auditIndexesJson -AsHashtable)
        $indexes | Where-Object { $_.name -ceq 'syncmoney_audit_log_pkey' } | ForEach-Object { $_.isLive = $false }
        $notLivePrimaryIndex.auditIndexesJson = ConvertTo-Json -InputObject ([object[]]$indexes) -Depth 8 -Compress
        Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $before $notLivePrimaryIndex -DatabaseProduct $databaseProduct)) `
            'The PostgreSQL validator accepted a primary index marked for concurrent removal.'
    }
}

$v10UpgradeStateCommand = Get-Command Test-T21AuditSchemaV10UpgradeState
Assert-T21Upgrade ($v10UpgradeStateCommand.Parameters.ContainsKey('PreserveExistingPlayerRow')) `
    'The successful-migration state predicate does not expose the explicit existing-player-row case.'
$playerRowBefore = New-T21UpgradeState 'MySQL' $false
$playerRowAfter = New-T21UpgradeState 'MySQL' $true
$playerRow = [ordered]@{
    player_uuid='00000000-0000-0000-0000-00000000cafe'
    player_name='T21-ROW-SENTINEL'
    balance='1234.56'
    version='73'
    last_server='t21-player-preservation'
    updated_at='2026-10-16 12:34:56'
}
$playerRowsJson = ConvertTo-Json -InputObject ([object[]]@($playerRow)) -Depth 5 -Compress
foreach ($state in @($playerRowBefore, $playerRowAfter)) {
    $state.playersTableExists = $true
    $state.playerRows = [long]1
    $state.playerRowsJson = $playerRowsJson
}
Assert-T21Upgrade (Test-T21AuditSchemaV10UpgradeState $playerRowBefore $playerRowAfter `
        -DatabaseProduct MySQL -PreserveExistingPlayerRow) `
    'The successful migration state predicate rejected the unchanged existing MySQL player row.'
$deletedPlayerRow = Copy-T21UpgradeState $playerRowAfter
$deletedPlayerRow.playerRows = [long]0
$deletedPlayerRow.playerRowsJson = '[]'
Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $playerRowBefore $deletedPlayerRow `
            -DatabaseProduct MySQL -PreserveExistingPlayerRow)) `
    'The successful migration state predicate accepted deletion of the existing player row.'
$changedPlayerRow = Copy-T21UpgradeState $playerRowAfter
$changedPlayerRow.playerRowsJson = $playerRowsJson.Replace('1234.56', '1234.57')
Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $playerRowBefore $changedPlayerRow `
            -DatabaseProduct MySQL -PreserveExistingPlayerRow)) `
    'The successful migration state predicate accepted a changed existing player balance.'
$extraPlayerRow = Copy-T21UpgradeState $playerRowAfter
$extraPlayerRow.playerRows = [long]2
$extraPlayerRow.playerRowsJson = ConvertTo-Json -InputObject ([object[]]@($playerRow, $playerRow)) -Depth 5 -Compress
Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $playerRowBefore $extraPlayerRow `
            -DatabaseProduct MySQL -PreserveExistingPlayerRow)) `
    'The successful migration state predicate accepted more than the one seeded player row.'
Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeState $playerRowBefore $playerRowAfter -DatabaseProduct MySQL)) `
    'The ordinary empty-players migration predicate accepted the preservation fixture.'

foreach ($databaseProduct in @('MySQL', 'MariaDB', 'PostgreSQL')) {
    foreach ($endpoint in @('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')) {
        Assert-T21Upgrade (Test-T21AuditSchemaV10UpgradeTarget -DatabaseProduct $databaseProduct -PlatformEndpoint $endpoint) `
            "The bounded positive-migration runner rejected pinned $databaseProduct endpoint $endpoint."
    }
}

$playerRowTarget = Get-Command Test-T21AuditSchemaV10UpgradePlayerRowPreservationTarget -ErrorAction SilentlyContinue
Assert-T21Upgrade ($null -ne $playerRowTarget) 'The successful-migration player-row target contract is missing.'
foreach ($databaseProduct in @('MySQL', 'MariaDB', 'PostgreSQL')) {
    foreach ($endpoint in @('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')) {
        $expectedTarget = ($endpoint -in @('Minimum', 'Maximum'))
        Assert-T21Upgrade ((Test-T21AuditSchemaV10UpgradePlayerRowPreservationTarget `
                    -DatabaseProduct $databaseProduct -PlatformEndpoint $endpoint) -eq $expectedTarget) `
            "The successful-migration player-row target contract returned the wrong result for $databaseProduct/$endpoint."
    }
}

$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('t21-audit-schema-upgrade-validation-' + [guid]::NewGuid().ToString('N'))
try {
    $fixtureLogs = Join-Path $fixtureRoot 'logs'
    New-Item -ItemType Directory -Force -Path $fixtureLogs | Out-Null
    $platformLogPath = Join-Path $fixtureLogs 'platform-candidate-audit-v10-upgrade.txt'
    $platformLogText = @'
Syncmoney audit schema version: current 10, target 11
Starting audit schema upgrade: from version 10 to 11
Audit schema version recorded: 11 - v1.1.0 Cursor pagination optimization
Audit schema upgrade completed at version 11
Syncmoney enabled successfully
Syncmoney disabled.
'@
    [IO.File]::WriteAllText($platformLogPath, $platformLogText, [Text.UTF8Encoding]::new($false))
    $logItem = Get-Item -LiteralPath $platformLogPath
    $logHash = (Get-FileHash -LiteralPath $platformLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $platform = Get-T21PlatformEndpoint -PlatformEndpoint Minimum
    $candidateSha = 'a' * 64
    $databaseName = 'syncmoney_t21_0123abcd'
    $sqlKey = if ($databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
    $databaseVersion = switch ($databaseProduct) { 'MariaDB' { '12.3.3-MariaDB' } 'PostgreSQL' { '18.1' } default { '8.0.42' } }
    $databaseType = if ($databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
    $sqlPid = 101
    $redisPid = 202
    $runnerPath = Join-Path $PSScriptRoot 'run-mysql-sync-runtime.ps1'
    $validatorPath = Join-Path $PSScriptRoot 't21-audit-schema-upgrade-validation.ps1'
    $testPath = Join-Path $PSScriptRoot 't21-audit-schema-upgrade-validation.test.ps1'
    $sharedValidatorPath = Join-Path $PSScriptRoot 't21-upgrade-validation.ps1'
    $sharedTestPath = Join-Path $PSScriptRoot 't21-upgrade-validation.test.ps1'
    $failureValidatorPath = Join-Path $PSScriptRoot 't21-startup-failure-validation.ps1'
    $failureTestPath = Join-Path $PSScriptRoot 't21-startup-failure-validation.test.ps1'
    $runnerInfo = [ordered]@{
        path='tools/plugdev/run-mysql-sync-runtime.ps1'
        sha256=(Get-FileHash -LiteralPath $runnerPath -Algorithm SHA256).Hash.ToLowerInvariant()
        auditSchemaUpgradeValidatorSha256=(Get-FileHash -LiteralPath $validatorPath -Algorithm SHA256).Hash.ToLowerInvariant()
        auditSchemaUpgradeTestSha256=(Get-FileHash -LiteralPath $testPath -Algorithm SHA256).Hash.ToLowerInvariant()
        validatorSha256=(Get-FileHash -LiteralPath $sharedValidatorPath -Algorithm SHA256).Hash.ToLowerInvariant()
        testSha256=(Get-FileHash -LiteralPath $sharedTestPath -Algorithm SHA256).Hash.ToLowerInvariant()
        startupFailureValidatorSha256=(Get-FileHash -LiteralPath $failureValidatorPath -Algorithm SHA256).Hash.ToLowerInvariant()
        startupFailureValidatorTestSha256=(Get-FileHash -LiteralPath $failureTestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    foreach ($databaseProduct in @('MySQL', 'MariaDB', 'PostgreSQL')) {
        $databaseVersion = switch ($databaseProduct) { 'MariaDB' { '12.3.3-MariaDB' } 'PostgreSQL' { '18.1' } default { '8.0.42' } }
        $databaseType = if ($databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
        $sqlKey = if ($databaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
        $sqlBackend = [ordered]@{ product=$databaseProduct; version=$databaseVersion; processId=$sqlPid; runOwned=$true; listenerOwnerVerified=$true }
        $sqlShutdown = [ordered]@{ backend=$sqlKey; commandExitCode=0; processId=$sqlPid; processExitCode=0; processExitCodeObserved=$true; processExited=$true; forced=$false; shutdownTargetVerified=$true }
        if ($databaseProduct -in @('MySQL', 'MariaDB')) {
            $sqlShutdown.launchProcessExitCode = 0
        }
        $backendProcessFixture = [ordered]@{ redis=[ordered]@{ version='8.10.1'; processId=$redisPid; runOwned=$true; listenerOwnerVerified=$true } }
        $backendProcessFixture[$sqlKey] = $sqlBackend
        $report = [ordered]@{
            schemaVersion=4
            task="T21-V03-SYNC-v10-to-v11-audit-schema-upgrade-$databaseProduct-minimum-Paper"
            status='PASS_BOUNDED_SUBCASE'
            runDirectory=$fixtureRoot
            failure=$null
            cleanupFailures=@()
            server=[ordered]@{ software='paper'; minecraftVersion='1.21.6'; build=48; sha256=$platform.sha256 }
            candidate=[ordered]@{ version='1.4.0-alpha.1'; sha256=$candidateSha }
            java=[ordered]@{ feature=25 }
            configuration=[ordered]@{
                schema=12; configSchema=12; mode='sync'; observedMode='sync'; databaseEnabled=$true; databaseType=$databaseType
                databaseIsolated=$true; databaseHost='127.0.0.1'; databasePort=3310; databaseName=$databaseName; databaseNameObserved=$databaseName
                redisEnabled=$true; redisDatabase=15; redisIsolated=$true; redisHost='127.0.0.1'; redisPort=6379
            }
            backendProcesses=$backendProcessFixture
            redis=[ordered]@{ version='8.10.1' }
            startupObservations=@([ordered]@{
                stage='candidate-audit-v10-upgrade'; pluginVersion='1.4.0-alpha.1'; artifactSha256=$candidateSha; enabled=$true
                processId=$sqlPid; apiConsumerMarker='PASS'; runtimeIdentity=[ordered]@{ software='paper'; runtimeVersion='1.21.6'; minecraftVersion='1.21.6'; build=48 }
            })
            normalStops=@([ordered]@{
                stage='after-audit-v10-upgrade'; commandExitCode=0; processId=$sqlPid; processExitCode=0
                processExitCodeObserved=$true; processExited=$true; disabledMarker=$true; forced=$false
            })
            schemaStateBefore=(New-T21UpgradeState $databaseProduct $false)
            schemaStateAfter=(New-T21UpgradeState $databaseProduct $true)
            platformLogs=@([ordered]@{ stage='candidate-audit-v10-upgrade'; path='logs\platform-candidate-audit-v10-upgrade.txt'; bytes=[long]$logItem.Length; sha256=$logHash })
            logs=[ordered]@{ 'logs\platform-candidate-audit-v10-upgrade.txt'=[ordered]@{ bytes=[long]$logItem.Length; sha256=$logHash } }
            backendShutdowns=@($sqlShutdown, [ordered]@{
                backend='redis'; commandExitCode=1; processId=$redisPid; processExitCode=0; processExitCodeObserved=$true; processExited=$true; forced=$false
            })
            runner=$runnerInfo
            plugDev=[ordered]@{ sourceCommit=('b' * 40); cliSha256=('c' * 64); cliDistManifestSha256Before=('d' * 64); cliDistManifestSha256After=('d' * 64) }
        }
        $report[$sqlKey] = [ordered]@{ product=$databaseProduct; version=$databaseVersion }
        Assert-T21Upgrade (Test-T21AuditSchemaV10UpgradeReport $report $platform.sha256 $candidateSha -DatabaseProduct $databaseProduct -PlatformEndpoint Minimum) `
            "The complete successful $databaseProduct minimum-Paper upgrade report fixture was rejected."
        if ($databaseProduct -in @('MySQL', 'MariaDB', 'PostgreSQL')) {
            $playerRowReport = $report | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
            $playerRowReport.task = "T21-V03-SYNC-v10-to-v11-audit-schema-player-row-preservation-$databaseProduct-minimum-Paper"
            $playerRowReport.schemaStateBefore = New-T21UpgradeState $databaseProduct $false
            $playerRowReport.schemaStateAfter = New-T21UpgradeState $databaseProduct $true
            $playerRowReport.schemaStateBefore.playersTableExists = $true
            $playerRowReport.schemaStateBefore.playerRows = [long]1
            $playerRowReport.schemaStateAfter.playersTableExists = $true
            $playerRowReport.schemaStateAfter.playerRows = [long]1
            $productPlayerRowsJson = $playerRowsJson
            if ($databaseProduct -ceq 'PostgreSQL') {
                $postgresPlayerRow = [ordered]@{
                    player_uuid='00000000-0000-0000-0000-00000000cafe'
                    player_name='T21-ROW-SENTINEL'
                    balance='1234.56'
                    version='73'
                    last_server='t21-player-preservation'
                    updated_at='2026-10-16 12:34:56.000000'
                }
                $productPlayerRowsJson = ConvertTo-Json -InputObject ([object[]]@($postgresPlayerRow)) -Depth 5 -Compress
            }
            foreach ($snapshotName in @('schemaStateBefore', 'schemaStateAfter')) {
                $playerRowReport[$snapshotName].playerRowsJson = $productPlayerRowsJson
            }
            Assert-T21Upgrade (Test-T21AuditSchemaV10UpgradeReport $playerRowReport $platform.sha256 $candidateSha `
                    -DatabaseProduct $databaseProduct -PlatformEndpoint Minimum) `
                "The complete successful $databaseProduct minimum-Paper report with its unchanged player row was rejected."
            $maximumPlayerRowReport = $playerRowReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
            $maximumPlatform = Get-T21PlatformEndpoint -PlatformEndpoint Maximum
            $maximumPlayerRowReport.task = "T21-V03-SYNC-v10-to-v11-audit-schema-player-row-preservation-$databaseProduct-$($maximumPlatform.taskSuffix)"
            $maximumPlayerRowReport.server.software = $maximumPlatform.software
            $maximumPlayerRowReport.server.minecraftVersion = $maximumPlatform.minecraftVersion
            $maximumPlayerRowReport.server.build = $maximumPlatform.build
            $maximumPlayerRowReport.server.sha256 = $maximumPlatform.sha256
            $maximumPlayerRowReport.startupObservations[0].runtimeIdentity.software = $maximumPlatform.software
            $maximumPlayerRowReport.startupObservations[0].runtimeIdentity.runtimeVersion = $maximumPlatform.minecraftVersion
            $maximumPlayerRowReport.startupObservations[0].runtimeIdentity.minecraftVersion = $maximumPlatform.minecraftVersion
            $maximumPlayerRowReport.startupObservations[0].runtimeIdentity.build = $maximumPlatform.build
            Assert-T21Upgrade (Test-T21AuditSchemaV10UpgradeReport $maximumPlayerRowReport $maximumPlatform.sha256 $candidateSha `
                    -DatabaseProduct $databaseProduct -PlatformEndpoint Maximum) `
                "The complete successful $databaseProduct maximum-Paper report with its unchanged player row was rejected."
            Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeReport $playerRowReport $platform.sha256 $candidateSha `
                        -DatabaseProduct $databaseProduct -PlatformEndpoint CurrentStable)) `
                "The successful-migration $databaseProduct player-row report was accepted for supplemental CurrentStable Paper."
            Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeReport $maximumPlayerRowReport $maximumPlatform.sha256 $candidateSha `
                        -DatabaseProduct $databaseProduct -PlatformEndpoint MinimumFolia)) `
                "The successful-migration $databaseProduct Paper player-row report was accepted for Folia."
            $changedPlayerReport = $playerRowReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
            $changedPlayerReport.schemaStateAfter.playerRowsJson = $productPlayerRowsJson.Replace('1234.56', '1234.57')
            Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeReport $changedPlayerReport $platform.sha256 $candidateSha `
                        -DatabaseProduct $databaseProduct -PlatformEndpoint Minimum)) `
                "The successful-migration $databaseProduct report predicate accepted a changed player balance."
            if ($databaseProduct -ceq 'PostgreSQL') {
                $changedTimestampReport = $playerRowReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
                $changedTimestampReport.schemaStateAfter.playerRowsJson = $productPlayerRowsJson.Replace('12:34:56.000000', '12:34:56.000001')
                Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeReport $changedTimestampReport $platform.sha256 $candidateSha `
                            -DatabaseProduct $databaseProduct -PlatformEndpoint Minimum)) `
                    'The successful-migration PostgreSQL report predicate accepted a changed player timestamp microsecond.'
            }
        }
        foreach ($endpoint in @('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')) {
            $endpointPlatform = Get-T21PlatformEndpoint -PlatformEndpoint $endpoint
            $endpointReport = $report | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
            $endpointReport.task = "T21-V03-SYNC-v10-to-v11-audit-schema-upgrade-$databaseProduct-$($endpointPlatform.taskSuffix)"
            $endpointReport.server.software = $endpointPlatform.software
            $endpointReport.server.minecraftVersion = $endpointPlatform.minecraftVersion
            $endpointReport.server.build = $endpointPlatform.build
            $endpointReport.server.sha256 = $endpointPlatform.sha256
            $endpointReport.startupObservations[0].runtimeIdentity.software = $endpointPlatform.software
            $endpointReport.startupObservations[0].runtimeIdentity.runtimeVersion = $endpointPlatform.minecraftVersion
            $endpointReport.startupObservations[0].runtimeIdentity.minecraftVersion = $endpointPlatform.minecraftVersion
            $endpointReport.startupObservations[0].runtimeIdentity.build = $endpointPlatform.build
            Assert-T21Upgrade (Test-T21AuditSchemaV10UpgradeReport $endpointReport $endpointPlatform.sha256 $candidateSha `
                    -DatabaseProduct $databaseProduct -PlatformEndpoint $endpoint) `
                "The complete successful $databaseProduct $endpoint upgrade report fixture was rejected."
            if ($endpoint -ceq 'CurrentStable') {
                Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeReport $endpointReport $endpointPlatform.sha256 $candidateSha `
                        -DatabaseProduct $databaseProduct -PlatformEndpoint Maximum)) `
                    "A supplemental current-stable $databaseProduct report was accepted under the user-pinned maximum endpoint."
            }
            $wrongEndpointIdentity = $endpointReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
            $wrongEndpointIdentity.server.build = $endpointPlatform.build - 1
            Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeReport $wrongEndpointIdentity $endpointPlatform.sha256 $candidateSha `
                    -DatabaseProduct $databaseProduct -PlatformEndpoint $endpoint)) `
                "The $databaseProduct report validator accepted a $endpoint report with the wrong server build."
        }

        $java26Report = $report | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
        $java26Report.java.feature = 26
        Assert-T21Upgrade (Test-T21AuditSchemaV10UpgradeReport $java26Report $platform.sha256 $candidateSha `
                -DatabaseProduct $databaseProduct -PlatformEndpoint Minimum) `
            "The $databaseProduct report validator rejected Java 26, above the Java 25 minimum."
        $java24Report = $report | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
        $java24Report.java.feature = 24
        Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeReport $java24Report $platform.sha256 $candidateSha `
                -DatabaseProduct $databaseProduct -PlatformEndpoint Minimum)) `
            "The $databaseProduct report validator accepted Java 24, below the Java 25 minimum."
        $fractionalJavaReport = $report | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
        $fractionalJavaReport.java.feature = [decimal]25.5
        Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeReport $fractionalJavaReport $platform.sha256 $candidateSha `
                -DatabaseProduct $databaseProduct -PlatformEndpoint Minimum)) `
            "The $databaseProduct report validator accepted a fractional Java feature value."
        $nonnumericJavaReport = $report | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
        $nonnumericJavaReport.java.feature = 'not-a-number'
        Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeReport $nonnumericJavaReport $platform.sha256 $candidateSha `
                -DatabaseProduct $databaseProduct -PlatformEndpoint Minimum)) `
            "The $databaseProduct report validator accepted a nonnumeric Java feature value."
        $invalidReport = $report | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
        $invalidReport.schemaStateAfter.auditRowsJson = '[]'
        Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeReport $invalidReport $platform.sha256 $candidateSha -DatabaseProduct $databaseProduct -PlatformEndpoint Minimum)) `
            "The $databaseProduct report validator accepted a report with a missing sentinel row."

        $wrongReportDatabaseIdentity = $report | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
        foreach ($snapshotName in @('schemaStateBefore', 'schemaStateAfter')) {
            $columns = @(ConvertFrom-Json -InputObject $wrongReportDatabaseIdentity[$snapshotName].auditColumnsJson -AsHashtable)
            $databaseIdentityField = if ($databaseProduct -ceq 'PostgreSQL') { 'table_catalog' } else { 'table_schema' }
            foreach ($column in $columns) { $column[$databaseIdentityField] = $databaseName + '_other' }
            $wrongReportDatabaseIdentity[$snapshotName].auditColumnsJson = ConvertTo-Json -InputObject ([object[]]$columns) -Depth 10 -Compress
        }
        Assert-T21Upgrade (-not (Test-T21AuditSchemaV10UpgradeReport $wrongReportDatabaseIdentity $platform.sha256 $candidateSha -DatabaseProduct $databaseProduct -PlatformEndpoint Minimum)) `
            "The $databaseProduct report validator accepted matching before/after snapshots bound to a database other than the configured database."
    }
} finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]@('\', '/'))
        $rootFull = [IO.Path]::GetFullPath($fixtureRoot).TrimEnd([char[]]@('\', '/'))
        if ([IO.Path]::GetDirectoryName($rootFull) -cne $tempRoot -or
            [IO.Path]::GetFileName($rootFull) -notmatch '^t21-audit-schema-upgrade-validation-[0-9a-f]{32}$' -or
            (Test-T21PathContainsReparsePoint $rootFull)) {
            throw "Refusing to remove unexpected T21 audit-upgrade fixture path: $rootFull"
        }
        Remove-Item -LiteralPath $rootFull -Recurse -Force
    }
}
$runnerSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'run-mysql-sync-runtime.ps1') -Raw -ErrorAction Stop
$offlineAuditSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 't21-audit-schema-upgrade-offline-audit.ps1') -Raw -ErrorAction Stop
foreach ($requiredRunnerContract in @(
        '[switch]$SuccessfulV10AuditSchemaUpgrade',
        '[switch]$SuccessfulV10AuditSchemaPlayerRowPreservation',
        'Test-T21AuditSchemaV10UpgradePlayerRowPreservationTarget -DatabaseProduct',
        'Test-T21AuditSchemaV10UpgradeTarget -DatabaseProduct',
        'Seed-T21AuditSchemaV10Upgrade -SeedExistingPlayerRow:',
        'Get-T21StartupFailureSchemaState -AfterAuditSchemaV10Upgrade',
        'Test-T21AuditSchemaV10UpgradeState',
        'Test-T21AuditSchemaV10UpgradeReport',
        "'isPrimaryConstraint', EXISTS (",
        'index_state.indisprimary',
        'index_state.indisvalid',
        'index_state.indisready',
        'index_state.indislive')) {
    Assert-T21Upgrade ($runnerSource.Contains($requiredRunnerContract)) `
        "The runtime runner is missing successful v10-to-v11 migration contract: $requiredRunnerContract"
}
Assert-T21Upgrade ($offlineAuditSource.Contains("[ValidateSet('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')][string]`$PlatformEndpoint") -and
    $offlineAuditSource.Contains('Get-T21PlatformEndpoint -PlatformEndpoint $PlatformEndpoint') -and
    $offlineAuditSource.Contains('-DatabaseProduct $DatabaseProduct -PlatformEndpoint $PlatformEndpoint') -and
    $offlineAuditSource.Contains('platform="$platformName $($platform.minecraftVersion)#$($platform.build)"')) `
    'The offline auditor does not bind its expected server hash, platform identity, and strict report predicate to an explicit pinned endpoint.'
Write-Output 'PASS: T21 v10-to-v11 target and migration-state predicates'
