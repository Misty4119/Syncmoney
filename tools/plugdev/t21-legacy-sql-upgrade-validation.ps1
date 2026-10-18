function Test-T21LegacySqlHasField([object]$observation, [string]$name) {
    if ($null -eq $observation) { return $false }
    if ($observation -is [Collections.IDictionary]) { return $observation.Contains($name) }
    return $null -ne $observation.PSObject.Properties[$name]
}

function Test-T21LegacySqlRuntimeTarget([string]$DatabaseProduct, [string]$PlatformEndpoint) {
    if ($DatabaseProduct -notin @('MySQL', 'MariaDB', 'PostgreSQL')) { return $false }
    try {
        [void](Get-T21PlatformEndpoint -PlatformEndpoint $PlatformEndpoint)
        return $true
    } catch {
        return $false
    }
}

function Test-T21LegacySqlAccountState(
    [object]$snapshot,
    [string]$expectedBalanceA,
    [string]$expectedBalanceB
) {
    try {
        if ($null -eq $snapshot) { return $false }
        $expected = [ordered]@{
            AcceptanceA = [ordered]@{ uuid='35c93f74-c540-3b38-8093-b5cb3c2263be'; balance=$expectedBalanceA }
            AcceptanceB = [ordered]@{ uuid='b748781d-824c-36d9-9de4-59cee731f37f'; balance=$expectedBalanceB }
        }
        $sql = @{}
        $redis = @{}
        foreach ($side in @(
                [ordered]@{ property='sqlAccounts'; target=$sql },
                [ordered]@{ property='redisAccounts'; target=$redis })) {
            $rows = @(Get-T21ObservedValue $snapshot $side.property)
            if ($rows.Count -ne 2) { return $false }
            foreach ($row in $rows) {
                $name = [string](Get-T21ObservedValue $row 'name')
                $uuid = ([string](Get-T21ObservedValue $row 'uuid')).ToLowerInvariant()
                $balance = [string](Get-T21ObservedValue $row 'balance')
                $version = Get-T21ObservedValue $row 'version'
                if (-not $expected.Contains($name) -or $side.target.ContainsKey($name) -or
                    $uuid -cne $expected[$name].uuid -or $balance -cne $expected[$name].balance -or
                    -not (Test-T21IntegralNumber $version 1 ([long]::MaxValue))) { return $false }
                $side.target[$name] = [ordered]@{ uuid=$uuid; balance=$balance; version=[long]$version }
            }
        }
        foreach ($name in $expected.Keys) {
            if (-not $sql.ContainsKey($name) -or -not $redis.ContainsKey($name)) { return $false }
            if ($sql[$name].uuid -cne $redis[$name].uuid -or
                $sql[$name].balance -cne $redis[$name].balance -or
                [long]$sql[$name].version -ne [long]$redis[$name].version) { return $false }
        }
        return $true
    } catch {
        return $false
    }
}

function Test-T21LegacySqlAccountsEqual([object]$leftSnapshot, [object]$rightSnapshot) {
    try {
        foreach ($side in @('sqlAccounts', 'redisAccounts')) {
            $left = @(Get-T21ObservedValue $leftSnapshot $side)
            $right = @(Get-T21ObservedValue $rightSnapshot $side)
            if ($left.Count -ne 2 -or $right.Count -ne 2) { return $false }
            $leftMap = @{}
            $rightMap = @{}
            foreach ($row in $left) {
                $name = [string](Get-T21ObservedValue $row 'name')
                $leftMap[$name] = '{0}|{1}|{2}|{3}' -f $name,
                    [string](Get-T21ObservedValue $row 'uuid'),
                    [string](Get-T21ObservedValue $row 'balance'),
                    [string](Get-T21ObservedValue $row 'version')
            }
            foreach ($row in $right) {
                $name = [string](Get-T21ObservedValue $row 'name')
                $rightMap[$name] = '{0}|{1}|{2}|{3}' -f $name,
                    [string](Get-T21ObservedValue $row 'uuid'),
                    [string](Get-T21ObservedValue $row 'balance'),
                    [string](Get-T21ObservedValue $row 'version')
            }
            foreach ($name in @('AcceptanceA', 'AcceptanceB')) {
                if (-not $leftMap.ContainsKey($name) -or -not $rightMap.ContainsKey($name) -or
                    $leftMap[$name] -cne $rightMap[$name]) { return $false }
            }
        }
        return $true
    } catch {
        return $false
    }
}

function Get-T21LegacySqlAuditSchema([object]$snapshot, [string]$databaseProduct) {
    try {
        $metadata = Get-T21ObservedValue $snapshot 'auditSchema'
        $columnRows = @(Get-T21ObservedValue $metadata 'columns')
        $indexRows = @(Get-T21ObservedValue $metadata 'indexes')
        if ($columnRows.Count -lt 15 -or $indexRows.Count -lt 1) { Write-Verbose "Invalid audit schema row counts: columns=$($columnRows.Count) indexes=$($indexRows.Count)."; return $null }

        $requiredColumns = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($name in @('id', 'timestamp', 'sequence', 'type', 'player_uuid', 'player_name', 'amount', 'balance_after', 'source', 'server', 'target_uuid', 'target_name', 'reason', 'merged_count', 'request_id')) {
            [void]$requiredColumns.Add($name)
        }
        $observedColumns = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($rawRow in $columnRows) {
            $row = ConvertFrom-Json -InputObject ([string]$rawRow)
            if ($row -isnot [Array] -or $row.Count -ne 10 -or
                [string]$row[0] -cne 'syncmoney_audit_log' -or
                -not (Test-T21IntegralNumber $row[1] 1 ([int]::MaxValue)) -or
                [string]::IsNullOrWhiteSpace([string]$row[2]) -or
                [string]::IsNullOrWhiteSpace([string]$row[3])) { Write-Verbose "Invalid audit column metadata row: $rawRow"; return $null }
            if (-not $observedColumns.Add([string]$row[2])) { Write-Verbose "Duplicate audit column metadata row: $rawRow"; return $null }
        }
        foreach ($name in $requiredColumns) {
            if (-not $observedColumns.Contains($name)) { Write-Verbose "Missing required audit column metadata: $name"; return $null }
        }

        $canonicalIndexes = [Collections.Generic.List[string]]::new()
        $uniqueIndexes = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($rawRow in $indexRows) {
            $row = ConvertFrom-Json -InputObject ([string]$rawRow)
            if ($row -isnot [Array]) { Write-Verbose "Invalid audit index metadata row: $rawRow"; return $null }
            if ($databaseProduct -ceq 'PostgreSQL') {
                if ($row.Count -ne 8 -or
                    [string]::IsNullOrWhiteSpace([string]$row[0]) -or
                    $row[1] -isnot [bool] -or
                    -not (Test-T21IntegralNumber $row[2] 0 1) -or
                    -not (Test-T21IntegralNumber $row[3] 1 ([int]::MaxValue)) -or
                    [string]::IsNullOrWhiteSpace([string]$row[4]) -or
                    ([string]$row[5] -cnotin @('A', 'D')) -or
                    [string]$row[6] -cne 'BTREE') { Write-Verbose "Invalid PostgreSQL audit index metadata row: $rawRow"; return $null }
                $subPart = if ($null -eq $row[7]) { '<NULL>' } elseif (Test-T21IntegralNumber $row[7] 1 ([int]::MaxValue)) { [string]$row[7] } else { return $null }
                $primary = if ([bool]$row[1]) { 1 } else { 0 }
                $canonical = '{0}|{1}|{2}|{3}|{4}|{5}|{6}|{7}' -f [string]$row[0], $primary, [int]$row[2], [int]$row[3], [string]$row[4], [string]$row[5], [string]$row[6], $subPart
            } else {
                if ($row.Count -ne 7 -or
                    [string]::IsNullOrWhiteSpace([string]$row[0]) -or
                    -not (Test-T21IntegralNumber $row[1] 0 1) -or
                    -not (Test-T21IntegralNumber $row[2] 1 ([int]::MaxValue)) -or
                    [string]::IsNullOrWhiteSpace([string]$row[3]) -or
                    ([string]$row[4] -cnotin @('A', 'D')) -or
                    [string]$row[5] -cne 'BTREE') { Write-Verbose "Invalid audit index metadata row: $rawRow"; return $null }
                $subPart = if ($null -eq $row[6]) { '<NULL>' } elseif (Test-T21IntegralNumber $row[6] 1 ([int]::MaxValue)) { [string]$row[6] } else { return $null }
                $canonical = '{0}|{1}|{2}|{3}|{4}|{5}|{6}' -f [string]$row[0], [int]$row[1], [int]$row[2], [string]$row[3], [string]$row[4], [string]$row[5], $subPart
            }
            if (-not $uniqueIndexes.Add($canonical)) { Write-Verbose "Duplicate audit index metadata row: $canonical"; return $null }
            $canonicalIndexes.Add($canonical)
        }
        $primaryIndexName = if ($databaseProduct -ceq 'PostgreSQL') { 'syncmoney_audit_log_pkey' } else { 'PRIMARY' }
        $primaryIndex = if ($databaseProduct -ceq 'PostgreSQL') { "$primaryIndexName|1|0|1|id|A|BTREE|<NULL>" } else { "$primaryIndexName|0|1|id|A|BTREE|<NULL>" }
        if (-not $uniqueIndexes.Contains($primaryIndex)) { Write-Verbose 'Audit schema metadata is missing the primary id index.'; return $null }

        return [ordered]@{
            columns=@($columnRows | Sort-Object -CaseSensitive)
            indexes=@($canonicalIndexes | Sort-Object -CaseSensitive)
        }
    } catch {
        Write-Verbose "Could not parse audit schema metadata: $($_.Exception.Message)"
        return $null
    }
}

function Test-T21LegacyStringSetEqual([object[]]$left, [object[]]$right) {
    if ($left.Count -ne $right.Count) { return $false }
    return @(Compare-Object -ReferenceObject $left -DifferenceObject $right -CaseSensitive).Count -eq 0
}

function Test-T21LegacySqlSnapshotLogRows(
    [object]$report,
    [string]$snapshotStage,
    [string]$suffix,
    [string[]]$expectedRows
) {
    try {
        if ([string]::IsNullOrWhiteSpace($snapshotStage) -or
            $snapshotStage -notmatch '^after-(legacy-seed|candidate-existing-read|legacy-rollback-read)-stop$' -or
            $suffix -cnotin @('mysql-snapshot', 'mariadb-snapshot', 'postgresql-snapshot', 'schema-version-snapshot', 'audit-columns-snapshot', 'audit-indexes-snapshot')) {
            return $false
        }
        $relativePath = 'logs\' + $snapshotStage + '-' + $suffix + '.txt'
        $runRoot = [IO.Path]::GetFullPath([string](Get-T21ObservedValue $report 'runDirectory')).TrimEnd([char[]]@('\', '/'))
        $logsRoot = [IO.Path]::GetFullPath((Join-Path $runRoot 'logs')).TrimEnd([char[]]@('\', '/'))
        $fullPath = [IO.Path]::GetFullPath((Join-Path $runRoot $relativePath))
        $comparison = if ([OperatingSystem]::IsWindows()) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
        $logsPrefix = $logsRoot + [IO.Path]::DirectorySeparatorChar
        if (-not $fullPath.StartsWith($logsPrefix, $comparison) -or
            (Test-T21PathContainsReparsePoint $fullPath) -or
            -not (Test-Path -LiteralPath $fullPath -PathType Leaf)) { return $false }

        $manifest = Get-T21ObservedValue $report 'logs'
        $entry = Get-T21ObservedValue $manifest $relativePath
        if ($null -eq $entry) { return $false }
        $file = Get-Item -LiteralPath $fullPath -Force -ErrorAction Stop
        $hash = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
        if ((Get-T21ObservedValue $entry 'bytes') -ne [long]$file.Length -or
            ([string](Get-T21ObservedValue $entry 'sha256')).ToLowerInvariant() -cne $hash) { return $false }

        $actualRows = @(Get-Content -LiteralPath $fullPath -Encoding UTF8 -ErrorAction Stop)
        if ($actualRows.Count -ne $expectedRows.Count) { return $false }
        for ($index = 0; $index -lt $expectedRows.Count; $index++) {
            if ([string]$actualRows[$index] -cne [string]$expectedRows[$index]) { return $false }
        }
        return $true
    } catch {
        return $false
    }
}

function Test-T21LegacySqlSnapshotLogBinding([object]$report, [object]$snapshot, [string]$databaseKey, [string]$databaseProduct) {
    try {
        $snapshotStage = [string](Get-T21ObservedValue $snapshot 'stage')
        $sqlAccounts = @(Get-T21ObservedValue $snapshot 'sqlAccounts' | Sort-Object -Property name)
        if ($sqlAccounts.Count -ne 2) { return $false }
        $sqlRows = @($sqlAccounts | ForEach-Object {
            '{0}|{1}|{2}|{3}' -f [string](Get-T21ObservedValue $_ 'name'),
                [string](Get-T21ObservedValue $_ 'uuid'), [string](Get-T21ObservedValue $_ 'balance'),
                [string](Get-T21ObservedValue $_ 'version')
        })
        $schemaRows = @(Get-T21ObservedValue $snapshot 'schemaVersionRows' | ForEach-Object { [string]$_ })
        $auditSchema = Get-T21ObservedValue $snapshot 'auditSchema'
        $columnRows = @(Get-T21ObservedValue $auditSchema 'columns' | ForEach-Object { [string]$_ })
        $indexRows = @(Get-T21ObservedValue $auditSchema 'indexes' | ForEach-Object { [string]$_ })
        $sqlBound = Test-T21LegacySqlSnapshotLogRows $report $snapshotStage ($databaseProduct.ToLowerInvariant() + '-snapshot') $sqlRows
        $schemaBound = Test-T21LegacySqlSnapshotLogRows $report $snapshotStage 'schema-version-snapshot' $schemaRows
        $columnsBound = Test-T21LegacySqlSnapshotLogRows $report $snapshotStage 'audit-columns-snapshot' $columnRows
        $indexesBound = Test-T21LegacySqlSnapshotLogRows $report $snapshotStage 'audit-indexes-snapshot' $indexRows
        Write-Verbose "T21 $databaseKey snapshot log binding stage=$snapshotStage sql=$sqlBound schema=$schemaBound columns=$columnsBound indexes=$indexesBound"
        return $sqlBound -and $schemaBound -and $columnsBound -and $indexesBound
    } catch {
        return $false
    }
}

function Test-T21LegacySqlDataUpgradeReport(
    [object]$report,
    [string]$expectedServerSha256,
    [string]$expectedBaselineSha256,
    [string]$expectedCandidateSha256,
    [string]$expectedAcceptanceSha256,
    [string]$expectedApiConsumerSha256,
    [ValidateSet('MySQL', 'MariaDB', 'PostgreSQL')]
    [string]$DatabaseProduct = 'MySQL',
    [Alias('PaperEndpoint')]
    [ValidateSet('Minimum', 'Maximum', 'CurrentMaximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'CurrentMaximumFolia', 'MinimumCanvas', 'MaximumCanvas', 'CurrentMaximumCanvas')]
    [string]$PlatformEndpoint = 'Minimum'
) {
    try {
        if ($null -eq $report) { return $false }
        $databaseKey = if ($DatabaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
        $expectedDatabaseVersion = switch ($DatabaseProduct) { 'MariaDB' { '12.3.3-MariaDB' } 'PostgreSQL' { '18.1' } default { '8.0.42' } }
        $expectedDatabaseType = if ($DatabaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
        $expectedPlatform = Get-T21PlatformEndpoint -PlatformEndpoint $PlatformEndpoint
        $expectedTask = "T21-M05-LEGACY-SQL-DATA-$DatabaseProduct-$($expectedPlatform.taskSuffix)"
        if ((Get-T21ObservedValue $report 'schemaVersion') -ne 2 -or
            (Get-T21ObservedValue $report 'task') -cne $expectedTask -or
            (Get-T21ObservedValue $report 'status') -cne 'PASS_BOUNDED_SUBCASE' -or
            ([string]$expectedServerSha256).ToLowerInvariant() -cne $expectedPlatform.sha256 -or
            ([string](Get-T21ObservedValue (Get-T21ObservedValue $report 'server') 'sha256')).ToLowerInvariant() -cne $expectedPlatform.sha256 -or
            -not (Test-T21LegacySqlHasField $report 'failure') -or
            -not (Test-T21LegacySqlHasField $report 'cleanupFailures') -or
            (Get-T21ObservedValue $report 'failure') -ne $null) { return $false }
        if ($report -is [Collections.IDictionary]) {
            $cleanupFailures = $report['cleanupFailures']
        } else {
            $cleanupFailures = $report.PSObject.Properties['cleanupFailures'].Value
        }
        if ($cleanupFailures -isnot [Array] -or $cleanupFailures.Count -ne 0) { return $false }

        $server = Get-T21ObservedValue $report 'server'
        if ((Get-T21ObservedValue $server 'software') -cne $expectedPlatform.software -or
            (Get-T21ObservedValue $server 'minecraftVersion') -cne $expectedPlatform.minecraftVersion -or
            (Get-T21ObservedValue $server 'build') -ne $expectedPlatform.build) { return $false }
        $baseline = Get-T21ObservedValue $report 'baseline'
        $candidate = Get-T21ObservedValue $report 'candidate'
        if ((Get-T21ObservedValue $baseline 'version') -cne '1.3.2' -or
            ([string](Get-T21ObservedValue $baseline 'sha256')).ToLowerInvariant() -cne $expectedBaselineSha256.ToLowerInvariant() -or
            (Get-T21ObservedValue $candidate 'version') -cne '1.4.0-alpha.1' -or
            ([string](Get-T21ObservedValue $candidate 'sha256')).ToLowerInvariant() -cne $expectedCandidateSha256.ToLowerInvariant()) { return $false }

        if ((Get-T21ObservedValue (Get-T21ObservedValue $report 'java') 'feature') -ne 25 -or
            (Get-T21ObservedValue (Get-T21ObservedValue $report $databaseKey) 'product') -cne $DatabaseProduct -or
            (Get-T21ObservedValue (Get-T21ObservedValue $report $databaseKey) 'version') -cne $expectedDatabaseVersion -or
            (Get-T21ObservedValue (Get-T21ObservedValue $report 'redis') 'version') -cne '8.10.1') { return $false }
        $artifacts = Get-T21ObservedValue $report 'artifacts'
        if (([string](Get-T21ObservedValue $artifacts 'acceptanceSha256')).ToLowerInvariant() -cne $expectedAcceptanceSha256.ToLowerInvariant() -or
            ([string](Get-T21ObservedValue $artifacts 'apiConsumerSha256')).ToLowerInvariant() -cne $expectedApiConsumerSha256.ToLowerInvariant()) { return $false }
        $configuration = Get-T21ObservedValue $report 'configuration'
        if ((Get-T21ObservedValue $configuration 'schema') -ne 12 -or
            (Get-T21ObservedValue $configuration 'mode') -cne 'sync' -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'databaseEnabled') $true) -or
            (Get-T21ObservedValue $configuration 'databaseType') -cne $expectedDatabaseType -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'databaseIsolated') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'redisEnabled') $true) -or
            (Get-T21ObservedValue $configuration 'redisDatabase') -ne 15 -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'redisIsolated') $true)) { return $false }

        $sourcePins = Get-T21ObservedValue $report 'sourcePins'
        foreach ($pinName in @('runnerSha256', 'validatorSha256', 'validatorTestSha256', 'sharedValidatorSha256', 'sharedValidatorTestSha256', 'plugDevCliSha256', 'paperJarSha256', 'baselineJarSha256', 'candidateJarSha256', 'acceptanceJarSha256', 'apiConsumerJarSha256')) {
            if ([string](Get-T21ObservedValue $sourcePins $pinName) -cnotmatch '^[0-9a-f]{64}$') { return $false }
        }
        if ([string](Get-T21ObservedValue $sourcePins 'plugDevCommit') -cnotmatch '^[0-9a-f]{40}$') { return $false }

        $expectedStages = @(
            [ordered]@{ name='legacy-seed'; version='1.3.2'; sha=$expectedBaselineSha256.ToLowerInvariant(); markerKind='write'; expectedVersion='1000.00'; expectedVersionB='520.00'; api='NOT_REQUIRED_FOR_BASELINE' },
            [ordered]@{ name='candidate-existing-read'; version='1.4.0-alpha.1'; sha=$expectedCandidateSha256.ToLowerInvariant(); markerKind='read'; expectedVersion='1000.00'; expectedVersionB='520.00'; api='PASS' },
            [ordered]@{ name='legacy-rollback-read'; version='1.3.2'; sha=$expectedBaselineSha256.ToLowerInvariant(); markerKind='rollback'; expectedVersion='1000.00'; expectedVersionB='520.00'; api='NOT_REQUIRED_FOR_BASELINE' }
        )
        $stages = @(Get-T21ObservedValue $report 'stages')
        if ($stages.Count -ne $expectedStages.Count) { return $false }
        $processIds = [Collections.Generic.HashSet[int]]::new()
        $baselineSnapshot = $null
        $baselineAuditSchema = $null
        $candidateAuditSchema = $null
        $logPaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        $logManifest = Get-T21ObservedValue $report 'logs'
        $platformLogs = @(Get-T21ObservedValue $report 'platformLogs')
        if ($platformLogs.Count -ne $expectedStages.Count -or $null -eq $logManifest) { return $false }
        $runDirectory = [string](Get-T21ObservedValue $report 'runDirectory')
        if ([string]::IsNullOrWhiteSpace($runDirectory)) { return $false }
        $runRoot = [IO.Path]::GetFullPath($runDirectory).TrimEnd([char[]]@('\', '/'))
        $logsRoot = [IO.Path]::GetFullPath((Join-Path $runRoot 'logs')).TrimEnd([char[]]@('\', '/'))
        if ((Test-T21PathContainsReparsePoint $logsRoot) -or -not (Test-Path -LiteralPath $logsRoot -PathType Container)) { return $false }
        $logsRootPrefix = $logsRoot + [IO.Path]::DirectorySeparatorChar
        $comparison = if ([IO.Path]::DirectorySeparatorChar -eq '\') { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }

        for ($index = 0; $index -lt $expectedStages.Count; $index++) {
            $expected = $expectedStages[$index]
            $stage = $stages[$index]
            if ((Get-T21ObservedValue $stage 'stage') -cne $expected.name -or
                (Get-T21ObservedValue $stage 'pluginVersion') -cne $expected.version -or
                (Get-T21ObservedValue $stage 'artifactName') -cne "Syncmoney-$($expected.version).jar" -or
                ([string](Get-T21ObservedValue $stage 'artifactSha256')).ToLowerInvariant() -cne $expected.sha -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $stage 'enabled') $true) -or
                (Get-T21ObservedValue $stage 'apiConsumerMarker') -cne $expected.api) { return $false }
            $processId = Get-T21ObservedValue $stage 'processId'
            $processIdValid = Test-T21IntegralNumber $processId 1 ([int]::MaxValue)
            $processIdUnique = $processIds.Add([int]$processId)
            Write-Verbose ("T21 legacy SQL report processId {0}: valid={1} unique={2}" -f $processId, $processIdValid, $processIdUnique)
            if (-not $processIdValid -or -not $processIdUnique) { return $false }
            $identity = Get-T21ObservedValue $stage 'runtimeIdentity'
            if ((Get-T21ObservedValue $identity 'software') -cne $expectedPlatform.software -or
                (Get-T21ObservedValue $identity 'runtimeVersion') -cne $expectedPlatform.minecraftVersion -or
                (Get-T21ObservedValue $identity 'minecraftVersion') -cne $expectedPlatform.minecraftVersion -or
                (Get-T21ObservedValue $identity 'build') -ne $expectedPlatform.build) { return $false }
            Write-Verbose "T21 legacy SQL report accepted runtime identity for $($expected.name)."

            $marker = [string](Get-T21ObservedValue $stage 'acceptanceMarker')
            $markerPattern = switch ($expected.markerKind) {
                'write' { '^\[\d{2}:\d{2}:\d{2}\] \[[^\]]+/INFO\]: \[SyncmoneyAcceptance\] T21_WRITE_PASS expected-a=1000\.00 expected-b=520\.00 receipt-a=SUCCESS:DEPOSIT:1000\.00:1000\.00 receipt-b=SUCCESS:DEPOSIT:520\.00:520\.00$' }
                'read' { '^\[\d{2}:\d{2}:\d{2}\] \[[^\]]+/INFO\]: \[SyncmoneyAcceptance\] T21_READ_PASS expected-a=1000\.00 expected-b=520\.00$' }
                default { '^\[\d{2}:\d{2}:\d{2}\] \[[^\]]+/INFO\]: \[SyncmoneyAcceptance\] T21_ROLLBACK_READ_PASS expected-a=1000\.00 expected-b=520\.00 actual-a=1000\.00 actual-b=520\.00$' }
            }
            if ($marker -cnotmatch $markerPattern) { return $false }
            Write-Verbose "T21 legacy SQL report accepted acceptance marker for $($expected.name)."

            $normalStop = Get-T21ObservedValue $stage 'normalStop'
            if ((Get-T21ObservedValue $normalStop 'stage') -cne "after-$($expected.name)" -or
                -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $normalStop 'commandExitCode') 0) -or
                (Get-T21ObservedValue $normalStop 'processId') -ne $processId -or
                -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $normalStop 'processExitCode') 0) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $normalStop 'processExitCodeObserved') $true) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $normalStop 'processExited') $true) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $normalStop 'disabledMarker') $true) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $normalStop 'forced') $false)) { return $false }
            Write-Verbose "T21 legacy SQL report accepted normal stop for $($expected.name)."

            $snapshot = Get-T21ObservedValue $stage 'snapshot'
            if ((Get-T21ObservedValue $snapshot 'stage') -cne "after-$($expected.name)-stop" -or
                -not (Test-T21LegacySqlAccountState $snapshot $expected.expectedVersion $expected.expectedVersionB) -or
                -not (Test-T21LegacySqlSnapshotLogBinding $report $snapshot $databaseKey $DatabaseProduct)) { return $false }
            Write-Verbose "T21 legacy SQL report accepted SQL/Redis snapshot for $($expected.name)."
            $auditSchema = Get-T21LegacySqlAuditSchema $snapshot $DatabaseProduct
            if ($null -eq $auditSchema) { return $false }
            $schemaRows = @(Get-T21ObservedValue $snapshot 'schemaVersionRows')
            if ($index -eq 0) {
                $baselineSnapshot = $snapshot
                $baselineAuditSchema = $auditSchema
                try { $null = Assert-T21SchemaVersionRows $schemaRows $schemaRows "1.3.2 $DatabaseProduct baseline" }
                catch { return $false }
            } elseif ($index -eq 1) {
                $candidateAuditSchema = $auditSchema
                if (-not (Test-T21LegacyStringSetEqual @($baselineAuditSchema.columns) @($candidateAuditSchema.columns))) { return $false }
                if ($DatabaseProduct -ceq 'PostgreSQL') {
                    $expectedPostgresBaselineIndexes = @(
                        'idx_audit_player|0|1|1|player_uuid|A|BTREE|<NULL>',
                        'idx_audit_player|0|1|2|timestamp|D|BTREE|<NULL>',
                        'idx_audit_player|0|1|3|sequence|D|BTREE|<NULL>',
                        'idx_audit_timestamp|0|1|1|timestamp|D|BTREE|<NULL>',
                        'idx_audit_timestamp|0|1|2|sequence|D|BTREE|<NULL>',
                        'idx_audit_type|0|1|1|type|A|BTREE|<NULL>',
                        'idx_audit_type|0|1|2|timestamp|D|BTREE|<NULL>',
                        'idx_audit_type|0|1|3|sequence|D|BTREE|<NULL>',
                        'syncmoney_audit_log_pkey|1|0|1|id|A|BTREE|<NULL>'
                    )
                    $expectedPostgresCandidateIndexes = @($expectedPostgresBaselineIndexes + @(
                        'idx_audit_type_timestamp_sequence|0|1|1|type|A|BTREE|<NULL>',
                        'idx_audit_type_timestamp_sequence|0|1|2|timestamp|D|BTREE|<NULL>',
                        'idx_audit_type_timestamp_sequence|0|1|3|sequence|D|BTREE|<NULL>'
                    ))
                    if (-not (Test-T21LegacyStringSetEqual @($baselineAuditSchema.indexes) $expectedPostgresBaselineIndexes) -or
                        -not (Test-T21LegacyStringSetEqual @($candidateAuditSchema.indexes) $expectedPostgresCandidateIndexes)) { return $false }
                } elseif ($DatabaseProduct -ceq 'MariaDB') {
                    $expectedMariaDbBaselineIndexes = @(
                        'idx_audit_player|1|1|player_uuid|A|BTREE|<NULL>',
                        'idx_audit_player|1|2|timestamp|D|BTREE|<NULL>',
                        'idx_audit_player|1|3|sequence|D|BTREE|<NULL>',
                        'idx_audit_timestamp|1|1|timestamp|D|BTREE|<NULL>',
                        'idx_audit_timestamp|1|2|sequence|D|BTREE|<NULL>',
                        'idx_audit_type|1|1|type|A|BTREE|<NULL>',
                        'idx_audit_type|1|2|timestamp|D|BTREE|<NULL>',
                        'idx_audit_type|1|3|sequence|D|BTREE|<NULL>',
                        'PRIMARY|0|1|id|A|BTREE|<NULL>'
                    )
                    $expectedMariaDbCandidateIndexes = @($expectedMariaDbBaselineIndexes + @(
                        'idx_audit_type_timestamp_sequence|1|1|type|A|BTREE|<NULL>',
                        'idx_audit_type_timestamp_sequence|1|2|timestamp|D|BTREE|<NULL>',
                        'idx_audit_type_timestamp_sequence|1|3|sequence|D|BTREE|<NULL>'
                    ))
                    if (-not (Test-T21LegacyStringSetEqual @($baselineAuditSchema.indexes) $expectedMariaDbBaselineIndexes) -or
                        -not (Test-T21LegacyStringSetEqual @($candidateAuditSchema.indexes) $expectedMariaDbCandidateIndexes)) { return $false }
                } else {
                    $removedIndexes = @($baselineAuditSchema.indexes | Where-Object { $_ -notin $candidateAuditSchema.indexes })
                    $addedIndexes = @($candidateAuditSchema.indexes | Where-Object { $_ -notin $baselineAuditSchema.indexes })
                    $expectedAddedIndexes = @(
                        'idx_audit_timestamp|1|1|timestamp|D|BTREE|<NULL>',
                        'idx_audit_timestamp|1|2|sequence|D|BTREE|<NULL>',
                        'idx_audit_player|1|1|player_uuid|A|BTREE|<NULL>',
                        'idx_audit_player|1|2|timestamp|D|BTREE|<NULL>',
                        'idx_audit_player|1|3|sequence|D|BTREE|<NULL>',
                        'idx_audit_type_timestamp_sequence|1|1|type|A|BTREE|<NULL>',
                        'idx_audit_type_timestamp_sequence|1|2|timestamp|D|BTREE|<NULL>',
                        'idx_audit_type_timestamp_sequence|1|3|sequence|D|BTREE|<NULL>'
                    )
                    if ($removedIndexes.Count -ne 0 -or -not (Test-T21LegacyStringSetEqual $addedIndexes $expectedAddedIndexes)) { return $false }
                }
            } else {
                $expectedRollbackIndexes = $candidateAuditSchema.indexes
                if (-not (Test-T21LegacyStringSetEqual @($baselineAuditSchema.columns) @($auditSchema.columns)) -or
                    -not (Test-T21LegacyStringSetEqual @($expectedRollbackIndexes) @($auditSchema.indexes))) { return $false }
            }
            if ($index -gt 0) {
                try { $null = Assert-T21SchemaVersionRows @(Get-T21ObservedValue $baselineSnapshot 'schemaVersionRows') $schemaRows $expected.name }
                catch { return $false }
                if (-not (Test-T21LegacySqlAccountsEqual $baselineSnapshot $snapshot)) { return $false }
            }
            Write-Verbose "T21 legacy SQL report accepted schema version for $($expected.name)."

            $log = @($platformLogs | Where-Object { (Get-T21ObservedValue $_ 'stage') -ceq $expected.name })
            if ($log.Count -ne 1) { return $false }
            Write-Verbose "T21 legacy SQL report matched retained log entry for $($expected.name)."
            $logPath = [string](Get-T21ObservedValue $log[0] 'path')
            if ([string]::IsNullOrWhiteSpace($logPath) -or [IO.Path]::IsPathRooted($logPath) -or -not $logPaths.Add($logPath)) { return $false }
            $fullLogPath = [IO.Path]::GetFullPath((Join-Path $runRoot $logPath))
            if (-not $fullLogPath.StartsWith($logsRootPrefix, $comparison) -or
                (Test-T21PathContainsReparsePoint $fullLogPath) -or -not (Test-Path -LiteralPath $fullLogPath -PathType Leaf)) { return $false }
            Write-Verbose "T21 legacy SQL report accepted retained log path for $($expected.name)."
            $manifestEntry = Get-T21ObservedValue $logManifest $logPath
            $actualLog = Get-Item -LiteralPath $fullLogPath -Force -ErrorAction Stop
            $actualSha = (Get-FileHash -LiteralPath $fullLogPath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
            if ($null -eq $manifestEntry -or
                (Get-T21ObservedValue $log[0] 'bytes') -ne [long]$actualLog.Length -or
                ([string](Get-T21ObservedValue $log[0] 'sha256')).ToLowerInvariant() -cne $actualSha -or
                (Get-T21ObservedValue $manifestEntry 'bytes') -ne [long]$actualLog.Length -or
                ([string](Get-T21ObservedValue $manifestEntry 'sha256')).ToLowerInvariant() -cne $actualSha) { return $false }
            Write-Verbose "T21 legacy SQL report verified retained log hash for $($expected.name)."
            $logText = Get-Content -LiteralPath $fullLogPath -Raw -ErrorAction Stop
            if ($logText -notmatch [regex]::Escape("Loading server plugin Syncmoney v$($expected.version)") -or
                $logText -notmatch 'Syncmoney enabled successfully' -or
                -not $logText.Contains($marker)) { return $false }
            Write-Verbose "T21 legacy SQL report matched startup and acceptance markers in $($expected.name) log."
            $loggedIdentity = Get-T21PlatformIdentity $logText
            Write-Verbose ("T21 legacy SQL report parsed {0} {1}#{2} from $($expected.name) log." -f $loggedIdentity.software, $loggedIdentity.runtimeVersion, $loggedIdentity.build)
            Assert-T21PlatformIdentity $loggedIdentity $expectedPlatform.software $expectedPlatform.minecraftVersion $expectedPlatform.build
            Write-Verbose "T21 legacy SQL report accepted retained log for $($expected.name)."
        }

        $backendProcesses = Get-T21ObservedValue $report 'backendProcesses'
        $databaseProcess = Get-T21ObservedValue $backendProcesses $databaseKey
        $redisProcess = Get-T21ObservedValue $backendProcesses 'redis'
        Write-Verbose ("T21 backend evidence {0}={1}/{2}/{3}/{4}/{5}; redis={6}/{7}/{8}/{9}" -f
            $databaseKey, (Get-T21ObservedValue $databaseProcess 'product'), (Get-T21ObservedValue $databaseProcess 'version'),
            (Get-T21ObservedValue $databaseProcess 'runOwned'), (Get-T21ObservedValue $databaseProcess 'listenerOwnerVerified'),
            (Get-T21ObservedValue $databaseProcess 'processId'), (Get-T21ObservedValue $redisProcess 'version'),
            (Get-T21ObservedValue $redisProcess 'runOwned'), (Get-T21ObservedValue $redisProcess 'listenerOwnerVerified'),
            (Get-T21ObservedValue $redisProcess 'processId'))
        Write-Verbose ("T21 backend predicates databaseProduct={0} databaseVersion={1} databaseOwned={2} databaseListener={3} databasePid={4}; redisVersion={5} redisOwned={6} redisListener={7} redisPid={8}" -f
            ((Get-T21ObservedValue $databaseProcess 'product') -ceq $DatabaseProduct),
            ((Get-T21ObservedValue $databaseProcess 'version') -ceq $expectedDatabaseVersion),
            (Test-T21ObservedBoolean (Get-T21ObservedValue $databaseProcess 'runOwned') $true),
            (Test-T21ObservedBoolean (Get-T21ObservedValue $databaseProcess 'listenerOwnerVerified') $true),
            (Test-T21IntegralNumber (Get-T21ObservedValue $databaseProcess 'processId') 1 ([int]::MaxValue)),
            ((Get-T21ObservedValue $redisProcess 'version') -ceq '8.10.1'),
            (Test-T21ObservedBoolean (Get-T21ObservedValue $redisProcess 'runOwned') $true),
            (Test-T21ObservedBoolean (Get-T21ObservedValue $redisProcess 'listenerOwnerVerified') $true),
            (Test-T21IntegralNumber (Get-T21ObservedValue $redisProcess 'processId') 1 ([int]::MaxValue)))
        if ((Get-T21ObservedValue $databaseProcess 'product') -cne $DatabaseProduct -or
            (Get-T21ObservedValue $databaseProcess 'version') -cne $expectedDatabaseVersion -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $databaseProcess 'runOwned') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $databaseProcess 'listenerOwnerVerified') $true) -or
            -not (Test-T21IntegralNumber (Get-T21ObservedValue $databaseProcess 'processId') 1 ([int]::MaxValue)) -or
            (Get-T21ObservedValue $redisProcess 'version') -cne '8.10.1' -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisProcess 'runOwned') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisProcess 'listenerOwnerVerified') $true) -or
            -not (Test-T21IntegralNumber (Get-T21ObservedValue $redisProcess 'processId') 1 ([int]::MaxValue))) { return $false }
        if ($DatabaseProduct -in @('MySQL', 'MariaDB') -and
            -not (Test-T21IntegralNumber (Get-T21ObservedValue $databaseProcess 'launchProcessId') 1 ([int]::MaxValue))) { return $false }
        Write-Verbose "T21 legacy SQL report accepted $DatabaseProduct and Redis process ownership."
        $backendStops = @(Get-T21ObservedValue $report 'backendShutdowns')
        Write-Verbose ("T21 backend shutdown count={0}" -f $backendStops.Count)
        if ($backendStops.Count -ne 2) { return $false }
        foreach ($backend in @($databaseKey, 'redis')) {
            $matches = @($backendStops | Where-Object { [string](Get-T21ObservedValue $_ 'backend') -ceq $backend })
            $expectedPid = if ($backend -ceq $databaseKey) { Get-T21ObservedValue $databaseProcess 'processId' } else { Get-T21ObservedValue $redisProcess 'processId' }
            Write-Verbose ("T21 backend shutdown {0}: matches={1} observedPid={2} expectedPid={3} exit={4}/{5}/{6} forced={7}" -f
                $backend, $matches.Count,
                $(if ($matches.Count -gt 0) { Get-T21ObservedValue $matches[0] 'processId' } else { '<missing>' }),
                $expectedPid,
                $(if ($matches.Count -gt 0) { Get-T21ObservedValue $matches[0] 'processExitCode' } else { '<missing>' }),
                $(if ($matches.Count -gt 0) { Get-T21ObservedValue $matches[0] 'processExitCodeObserved' } else { '<missing>' }),
                $(if ($matches.Count -gt 0) { Get-T21ObservedValue $matches[0] 'processExited' } else { '<missing>' }),
                $(if ($matches.Count -gt 0) { Get-T21ObservedValue $matches[0] 'forced' } else { '<missing>' }))
            $commandExitCode = if ($matches.Count -eq 1) { Get-T21ObservedValue $matches[0] 'commandExitCode' } else { $null }
            $commandExitValid = if ($backend -ceq $databaseKey) {
                Test-T21ExpectedExitCode $commandExitCode 0
            } else {
                (Test-T21ExpectedExitCode $commandExitCode 0) -or (Test-T21ExpectedExitCode $commandExitCode 1)
            }
            if ($matches.Count -ne 1 -or (Get-T21ObservedValue $matches[0] 'processId') -ne $expectedPid -or
                -not $commandExitValid -or
                -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $matches[0] 'processExitCode') 0) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $matches[0] 'processExitCodeObserved') $true) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $matches[0] 'processExited') $true) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $matches[0] 'forced') $false)) { return $false }
            if ($backend -ceq $databaseKey -and
                (-not (Test-T21ObservedBoolean (Get-T21ObservedValue $matches[0] 'shutdownTargetVerified') $true) -or
                 ($DatabaseProduct -in @('MySQL', 'MariaDB') -and
                  (-not (Test-T21IntegralNumber (Get-T21ObservedValue $matches[0] 'launchProcessId') 1 ([int]::MaxValue)) -or
                   (Get-T21ObservedValue $matches[0] 'launchProcessId') -ne (Get-T21ObservedValue $databaseProcess 'launchProcessId') -or
                   -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $matches[0] 'launchProcessExitCode') 0) -or
                   -not (Test-T21ObservedBoolean (Get-T21ObservedValue $matches[0] 'launchProcessExitCodeObserved') $true) -or
                   -not (Test-T21ObservedBoolean (Get-T21ObservedValue $matches[0] 'launchProcessExited') $true))))) { return $false }
        }
        Write-Verbose "T21 legacy SQL report accepted graceful $DatabaseProduct and Redis shutdown."

        $gradleCleanup = Get-T21ObservedValue $report 'gradleDaemonCleanup'
        $expectedGradleHome = [IO.Path]::GetFullPath((Join-Path $runRoot '.gradle-user-home'))
        $reportedGradleHome = [string](Get-T21ObservedValue $gradleCleanup 'isolatedUserHome')
        $beforePids = @(Get-T21ObservedValue $gradleCleanup 'processIdsBeforeStop')
        $afterPids = @(Get-T21ObservedValue $gradleCleanup 'processIdsAfterStop')
        if ([string]::IsNullOrWhiteSpace($reportedGradleHome) -or
            -not (Test-T21PathsEqual $reportedGradleHome $expectedGradleHome) -or
            (Test-T21PathContainsReparsePoint $expectedGradleHome) -or $afterPids.Count -ne 0 -or
            @($beforePids | Where-Object { -not (Test-T21IntegralNumber $_ 1 ([int]::MaxValue)) }).Count -gt 0 -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $gradleCleanup 'processExitVerified') $true)) { return $false }
        if ($beforePids.Count -gt 0 -and -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $gradleCleanup 'stopCommandExitCode') 0)) { return $false }
        Write-Verbose 'T21 legacy SQL report accepted isolated Gradle daemon cleanup.'
        return $true
    } catch {
        Write-Verbose "T21 legacy SQL report rejected during validation: $($_.Exception.Message)"
        return $false
    }
}

function Test-T21MySqlLegacyDataUpgradeReport(
    [object]$report,
    [string]$expectedServerSha256,
    [string]$expectedBaselineSha256,
    [string]$expectedCandidateSha256,
    [string]$expectedAcceptanceSha256,
    [string]$expectedApiConsumerSha256,
    [Alias('PaperEndpoint')]
    [ValidateSet('Minimum', 'Maximum', 'CurrentMaximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'CurrentMaximumFolia', 'MinimumCanvas', 'MaximumCanvas', 'CurrentMaximumCanvas')]
    [string]$PlatformEndpoint = 'Minimum'
) {
    return Test-T21LegacySqlDataUpgradeReport $report $expectedServerSha256 $expectedBaselineSha256 `
        $expectedCandidateSha256 $expectedAcceptanceSha256 $expectedApiConsumerSha256 `
        -DatabaseProduct MySQL -PlatformEndpoint $PlatformEndpoint
}

function Test-T21MariaDbLegacyDataUpgradeReport(
    [object]$report,
    [string]$expectedServerSha256,
    [string]$expectedBaselineSha256,
    [string]$expectedCandidateSha256,
    [string]$expectedAcceptanceSha256,
    [string]$expectedApiConsumerSha256,
    [Alias('PaperEndpoint')]
    [ValidateSet('Minimum', 'Maximum', 'CurrentMaximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'CurrentMaximumFolia', 'MinimumCanvas', 'MaximumCanvas', 'CurrentMaximumCanvas')]
    [string]$PlatformEndpoint = 'Minimum'
) {
    return Test-T21LegacySqlDataUpgradeReport $report $expectedServerSha256 $expectedBaselineSha256 `
        $expectedCandidateSha256 $expectedAcceptanceSha256 $expectedApiConsumerSha256 `
        -DatabaseProduct MariaDB -PlatformEndpoint $PlatformEndpoint
}

function Test-T21PostgresLegacyDataUpgradeReport(
    [object]$report,
    [string]$expectedServerSha256,
    [string]$expectedBaselineSha256,
    [string]$expectedCandidateSha256,
    [string]$expectedAcceptanceSha256,
    [string]$expectedApiConsumerSha256,
    [Alias('PaperEndpoint')]
    [ValidateSet('Minimum', 'Maximum', 'CurrentMaximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'CurrentMaximumFolia', 'MinimumCanvas', 'MaximumCanvas', 'CurrentMaximumCanvas')]
    [string]$PlatformEndpoint = 'Minimum'
) {
    return Test-T21LegacySqlDataUpgradeReport $report $expectedServerSha256 $expectedBaselineSha256 `
        $expectedCandidateSha256 $expectedAcceptanceSha256 $expectedApiConsumerSha256 `
        -DatabaseProduct PostgreSQL -PlatformEndpoint $PlatformEndpoint
}
