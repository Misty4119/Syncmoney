Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 't21-upgrade-validation.ps1')

function Test-T21NewerAuditSchemaStartupFailureTarget(
    [ValidateSet('MySQL', 'MariaDB', 'PostgreSQL')][string]$DatabaseProduct,
    [ValidateSet('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')]
    [string]$PlatformEndpoint
) {
    return $PlatformEndpoint -in @('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')
}

function Test-T21IncompleteAuditTableStartupFailureTarget(
    [ValidateSet('MySQL', 'MariaDB', 'PostgreSQL')][string]$DatabaseProduct,
    [ValidateSet('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')]
    [string]$PlatformEndpoint
) {
    if ($PlatformEndpoint -ceq 'MaximumFolia') {
        return $DatabaseProduct -in @('MySQL', 'MariaDB', 'PostgreSQL')
    }
    if ($PlatformEndpoint -ceq 'MinimumCanvas') {
        return $DatabaseProduct -in @('MySQL', 'MariaDB', 'PostgreSQL')
    }
    if ($PlatformEndpoint -ceq 'MaximumCanvas') {
        return $DatabaseProduct -in @('MySQL', 'MariaDB', 'PostgreSQL')
    }

    return $DatabaseProduct -in @('MySQL', 'MariaDB', 'PostgreSQL') -and
        $PlatformEndpoint -in @('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia')
}

function Test-T21IncompleteAuditTablePlayerRowPreservationTarget(
    [ValidateSet('MySQL', 'MariaDB', 'PostgreSQL')][string]$DatabaseProduct,
    [ValidateSet('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')]
    [string]$PlatformEndpoint
) {
    return $DatabaseProduct -ceq 'MySQL' -and $PlatformEndpoint -in @('Minimum', 'Maximum', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')
}

function Test-T21IncompleteV10PlayerRowSentinel([object]$RowsJson) {
    if ($RowsJson -isnot [string] -or [string]::IsNullOrWhiteSpace($RowsJson)) { return $false }
    try {
        $rows = @(ConvertFrom-Json -InputObject $RowsJson -AsHashtable -ErrorAction Stop)
        if ($null -eq $rows -or @($rows).Count -ne 1 -or $rows[0] -isnot [Collections.IDictionary]) { return $false }
        $expected = [ordered]@{
            player_uuid = '00000000-0000-0000-0000-00000000cafe'
            player_name = 'T21-ROW-SENTINEL'
            balance = '1234.56'
            version = '73'
            last_server = 't21-player-preservation'
            updated_at = '2026-10-16 12:34:56'
        }
        if ($rows[0].Count -ne $expected.Count) { return $false }
        foreach ($key in $expected.Keys) {
            if (-not $rows[0].Contains($key) -or [string]$rows[0][$key] -cne [string]$expected[$key]) { return $false }
        }
        return $true
    } catch { return $false }
}

function Test-T21RequiredV11DdlFailureTarget(
    [ValidateSet('MySQL', 'MariaDB', 'PostgreSQL')][string]$DatabaseProduct,
    [ValidateSet('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')]
    [string]$PlatformEndpoint
) {
    if ($DatabaseProduct -notin @('MySQL', 'MariaDB', 'PostgreSQL')) { return $false }
    if ($PlatformEndpoint -ceq 'Minimum') { return $true }
    if ($PlatformEndpoint -ceq 'MinimumFolia') { return $DatabaseProduct -in @('MySQL', 'MariaDB', 'PostgreSQL') }
    if ($PlatformEndpoint -ceq 'MaximumFolia') { return $DatabaseProduct -in @('MySQL', 'MariaDB', 'PostgreSQL') }
    if ($PlatformEndpoint -ceq 'MinimumCanvas') { return $DatabaseProduct -in @('MySQL', 'MariaDB', 'PostgreSQL') }
    if ($PlatformEndpoint -ceq 'MaximumCanvas') { return $DatabaseProduct -in @('MySQL', 'MariaDB', 'PostgreSQL') }
    return $PlatformEndpoint -in @('Maximum', 'CurrentStable')
}

function Test-T21MySqlDeniedRequestIdDdlEvent([object]$Event, [string]$RuntimeUser) {
    if ($null -eq $Event -or [string]::IsNullOrWhiteSpace($RuntimeUser) -or
        -not (Test-T21ExactDictionaryFields $Event @('userHost', 'commandType', 'argument'))) { return $false }
    $userHost = [string](Get-T21ObservedValue $Event 'userHost')
    $expectedUserHost = $RuntimeUser + '[' + $RuntimeUser + '] @ localhost [127.0.0.1]'
    $statement = [string](Get-T21ObservedValue $Event 'argument')
    $requestIdDdlPattern = '(?i)^\s*ALTER\s+TABLE\s+`?syncmoney_audit_log`?\s+ADD\s+COLUMN\s+`?request_id`?\s+VARCHAR\s*\(\s*36\s*\)\s*;?\s*$'
    return [string](Get-T21ObservedValue $Event 'commandType') -ceq 'Query' -and
        $userHost -ceq $expectedUserHost -and
        $statement -match $requestIdDdlPattern
}

function Test-T21PostgreSqlAuditOwnerBoundary([object]$Privileges, [string]$DatabaseName, [string]$UserName) {
    $expectedFields = @(
        'product', 'grantee', 'databaseName', 'schemaName', 'databaseOwner', 'schemaOwner', 'auditTableOwner',
        'roleSuperuser', 'roleMemberOfAuditOwner', 'databaseConnectGranted', 'schemaUsageGranted',
        'schemaCreateGranted', 'tableSelectGranted', 'tableInsertGranted', 'tableUpdateGranted',
        'tableDeleteGranted', 'tableOwnedByGrantee', 'alterAllowed', 'createGranted', 'verifiedBeforeStartup',
        'evidenceJson'
    )
    if (-not (Test-T21ExactDictionaryFields $Privileges $expectedFields) -or
        (Get-T21ObservedValue $Privileges 'product') -cne 'PostgreSQL' -or
        (Get-T21ObservedValue $Privileges 'grantee') -cne $UserName -or
        (Get-T21ObservedValue $Privileges 'databaseName') -cne $DatabaseName -or
        (Get-T21ObservedValue $Privileges 'schemaName') -cne 'public' -or
        (Get-T21ObservedValue $Privileges 'databaseOwner') -cne 'postgres' -or
        (Get-T21ObservedValue $Privileges 'auditTableOwner') -cne 'postgres' -or
        [string]::IsNullOrWhiteSpace([string](Get-T21ObservedValue $Privileges 'schemaOwner')) -or
        (Get-T21ObservedValue $Privileges 'schemaOwner') -ceq $UserName -or
        -not (Test-T21ObservedBoolean (Get-T21ObservedValue $Privileges 'roleSuperuser') $false) -or
        -not (Test-T21ObservedBoolean (Get-T21ObservedValue $Privileges 'roleMemberOfAuditOwner') $false) -or
        -not (Test-T21ObservedBoolean (Get-T21ObservedValue $Privileges 'databaseConnectGranted') $true) -or
        -not (Test-T21ObservedBoolean (Get-T21ObservedValue $Privileges 'schemaUsageGranted') $true) -or
        -not (Test-T21ObservedBoolean (Get-T21ObservedValue $Privileges 'schemaCreateGranted') $true) -or
        -not (Test-T21ObservedBoolean (Get-T21ObservedValue $Privileges 'tableSelectGranted') $true) -or
        -not (Test-T21ObservedBoolean (Get-T21ObservedValue $Privileges 'tableInsertGranted') $true) -or
        -not (Test-T21ObservedBoolean (Get-T21ObservedValue $Privileges 'tableUpdateGranted') $true) -or
        -not (Test-T21ObservedBoolean (Get-T21ObservedValue $Privileges 'tableDeleteGranted') $true) -or
        -not (Test-T21ObservedBoolean (Get-T21ObservedValue $Privileges 'tableOwnedByGrantee') $false) -or
        -not (Test-T21ObservedBoolean (Get-T21ObservedValue $Privileges 'alterAllowed') $false) -or
        -not (Test-T21ObservedBoolean (Get-T21ObservedValue $Privileges 'createGranted') $true) -or
        -not (Test-T21ObservedBoolean (Get-T21ObservedValue $Privileges 'verifiedBeforeStartup') $true)) { return $false }

    $evidenceJson = Get-T21ObservedValue $Privileges 'evidenceJson'
    if ($evidenceJson -isnot [string] -or [string]::IsNullOrWhiteSpace($evidenceJson)) { return $false }
    $expectedEvidenceFields = @(
        'databaseName', 'databaseOwner', 'schemaName', 'schemaOwner', 'grantee', 'roleSuperuser',
        'roleMemberOfAuditOwner', 'auditTableOwner', 'databaseConnectGranted', 'schemaUsageGranted',
        'schemaCreateGranted', 'tableSelectGranted', 'tableInsertGranted', 'tableUpdateGranted', 'tableDeleteGranted'
    )
    try {
        $document = [System.Text.Json.JsonDocument]::Parse([string]$evidenceJson)
        try {
            if ($document.RootElement.ValueKind -ne [System.Text.Json.JsonValueKind]::Object) { return $false }
            $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
            foreach ($property in $document.RootElement.EnumerateObject()) {
                if (-not $seen.Add($property.Name)) { return $false }
            }
            if ($seen.Count -ne $expectedEvidenceFields.Count -or
                @($expectedEvidenceFields | Where-Object { -not $seen.Contains($_) }).Count -ne 0) { return $false }
        } finally { $document.Dispose() }
        $evidence = ConvertFrom-Json -InputObject ([string]$evidenceJson) -AsHashtable -ErrorAction Stop
        foreach ($field in $expectedEvidenceFields) {
            $reportedValue = Get-T21ObservedValue $Privileges $field
            if ($null -eq $reportedValue) {
                if ($field -ceq 'grantee') { $reportedValue = Get-T21ObservedValue $Privileges 'grantee' }
                elseif ($field -ceq 'databaseName') { $reportedValue = Get-T21ObservedValue $Privileges 'databaseName' }
                elseif ($field -ceq 'schemaName') { $reportedValue = Get-T21ObservedValue $Privileges 'schemaName' }
                elseif ($field -ceq 'databaseOwner') { $reportedValue = Get-T21ObservedValue $Privileges 'databaseOwner' }
                elseif ($field -ceq 'schemaOwner') { $reportedValue = Get-T21ObservedValue $Privileges 'schemaOwner' }
                elseif ($field -ceq 'auditTableOwner') { $reportedValue = Get-T21ObservedValue $Privileges 'auditTableOwner' }
                elseif ($field -ceq 'roleSuperuser' -or $field -ceq 'roleMemberOfAuditOwner' -or
                    $field -ceq 'databaseConnectGranted' -or $field -ceq 'schemaUsageGranted' -or
                    $field -ceq 'schemaCreateGranted' -or $field -ceq 'tableSelectGranted' -or
                    $field -ceq 'tableInsertGranted' -or $field -ceq 'tableUpdateGranted' -or $field -ceq 'tableDeleteGranted') {
                    $reportedValue = Get-T21ObservedValue $Privileges $field
                }
            }
            if ($field -in @('roleSuperuser', 'roleMemberOfAuditOwner', 'databaseConnectGranted', 'schemaUsageGranted',
                    'schemaCreateGranted', 'tableSelectGranted', 'tableInsertGranted', 'tableUpdateGranted', 'tableDeleteGranted')) {
                if (-not (Test-T21ObservedBoolean (Get-T21ObservedValue $evidence $field) ([bool]$reportedValue))) { return $false }
            } elseif ((Get-T21ObservedValue $evidence $field) -cne [string]$reportedValue) { return $false }
        }
        return $true
    } catch {
        return $false
    }
}

function Test-T21ExactDictionaryFields([object]$Value, [string[]]$ExpectedFields) {
    if ($Value -isnot [System.Collections.IDictionary]) { return $false }
    $actualFields = @($Value.Keys | ForEach-Object { [string]$_ })
    return $actualFields.Count -eq $ExpectedFields.Count -and
        @($ExpectedFields | Where-Object { $_ -cnotin $actualFields }).Count -eq 0
}

function Test-T21JsonNumber([object]$Value) {
    if ($null -eq $Value) { return $false }
    $numericTypes = @([byte], [sbyte], [int16], [uint16], [int32], [uint32], [int64], [uint64], [single], [double], [decimal])
    return $Value.GetType() -in $numericTypes
}

function Test-T21ExactJsonDecimalToken([string]$Token, [string]$ExpectedSignificantDigits, [int]$ExpectedExponent) {
    $numberMatch = [regex]::Match($Token, '^(?<sign>-?)(?<integer>0|[1-9][0-9]*)(?:\.(?<fraction>[0-9]+))?(?:[eE](?<exponent>[+-]?[0-9]+))?$')
    if (-not $numberMatch.Success -or $numberMatch.Groups['sign'].Value -ceq '-') { return $false }

    $fractionDigits = $numberMatch.Groups['fraction'].Value
    $significantDigits = $numberMatch.Groups['integer'].Value + $fractionDigits
    $significantDigits = [regex]::Replace($significantDigits, '^0+', '')
    if ($significantDigits.Length -eq 0) { return $false }

    $trailingZeroCount = [regex]::Match($significantDigits, '0+$').Length
    if ($trailingZeroCount -gt 0) {
        $significantDigits = $significantDigits.Substring(0, $significantDigits.Length - $trailingZeroCount)
    }
    if ($significantDigits -cne $ExpectedSignificantDigits) { return $false }

    $decimalExponent = [System.Numerics.BigInteger]::Zero
    if ($numberMatch.Groups['exponent'].Success) {
        $decimalExponent = [System.Numerics.BigInteger]::Parse(
            $numberMatch.Groups['exponent'].Value,
            [Globalization.CultureInfo]::InvariantCulture)
    }
    $normalizedExponent = $decimalExponent - [System.Numerics.BigInteger]$fractionDigits.Length +
        [System.Numerics.BigInteger]$trailingZeroCount
    return $normalizedExponent -eq [System.Numerics.BigInteger]$ExpectedExponent
}

function Test-T21RequiredV11AuditSentinelMoneyJson([string]$RowsJson) {
    $expectedFields = @('id', 'timestamp', 'type', 'player_uuid', 'player_name', 'amount', 'balance_after',
        'source', 'server', 'target_uuid', 'target_name', 'reason', 'merged_count', 't21_sentinel')
    $document = $null
    try {
        $document = [System.Text.Json.JsonDocument]::Parse($RowsJson)
        $root = $document.RootElement
        if ($root.ValueKind -ne [System.Text.Json.JsonValueKind]::Array -or $root.GetArrayLength() -ne 1) { return $false }
        $row = $root[0]
        if ($row.ValueKind -ne [System.Text.Json.JsonValueKind]::Object) { return $false }

        $propertyNames = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($property in $row.EnumerateObject()) {
            if (-not $propertyNames.Add($property.Name) -or $property.Name -cnotin $expectedFields) { return $false }
        }
        if ($propertyNames.Count -ne $expectedFields.Count) { return $false }
        foreach ($expectedField in $expectedFields) {
            if (-not $propertyNames.Contains($expectedField)) { return $false }
        }

        $amountElement = $row.GetProperty('amount')
        $balanceAfterElement = $row.GetProperty('balance_after')
        if ($amountElement.ValueKind -ne [System.Text.Json.JsonValueKind]::Number -or
            $balanceAfterElement.ValueKind -ne [System.Text.Json.JsonValueKind]::Number) { return $false }
        return (Test-T21ExactJsonDecimalToken $amountElement.GetRawText() '125' -2) -and
            (Test-T21ExactJsonDecimalToken $balanceAfterElement.GetRawText() '9999' -2)
    } catch {
        return $false
    } finally {
        if ($null -ne $document) { $document.Dispose() }
    }
}

function Test-T21RequiredV11AuditSentinelRow([object]$Row) {
    $expectedFields = @('id', 'timestamp', 'type', 'player_uuid', 'player_name', 'amount', 'balance_after',
        'source', 'server', 'target_uuid', 'target_name', 'reason', 'merged_count', 't21_sentinel')
    if (-not (Test-T21ExactDictionaryFields $Row $expectedFields)) { return $false }

    $expectedStrings = [ordered]@{
        id = '00000000-0000-0000-0000-000000000330'
        type = 'DEPOSIT'
        player_uuid = '00000000-0000-0000-0000-000000000330'
        player_name = 'T21Fixture'
        source = 'TEST'
        server = 't21-v11-ddl-permission'
        reason = 'v10 preservation sentinel'
        t21_sentinel = 't21-v10-preservation-sentinel'
    }
    foreach ($field in $expectedStrings.Keys) {
        if ($Row[$field] -isnot [string] -or [string]$Row[$field] -cne $expectedStrings[$field]) { return $false }
    }
    if (-not (Test-T21IntegralNumber $Row['timestamp'] 1791936000000 1791936000000) -or
        -not (Test-T21IntegralNumber $Row['merged_count'] 1 1) -or
        $null -ne $Row['target_uuid'] -or $null -ne $Row['target_name']) { return $false }
    if (-not (Test-T21JsonNumber $Row['amount']) -or -not (Test-T21JsonNumber $Row['balance_after'])) { return $false }
    try {
        if ([decimal]$Row['amount'] -ne [decimal]1.25 -or [decimal]$Row['balance_after'] -ne [decimal]99.99) { return $false }
    } catch {
        return $false
    }
    return $true
}

function Protect-T21MariaDbGrantLines([string[]]$GrantLines) {
    if ($null -eq $GrantLines) { return @() }
    $safeLines = [Collections.Generic.List[string]]::new()
    $authenticationClausePattern = '(?i)IDENTIFIED\s+BY\s+PASSWORD\b'
    $observedMariaDbGrantPattern = "^(?<prefix>\s*GRANT\s+USAGE\s+ON\s+\*\.\*\s+TO\s+(?:\x60[^\x60\r\n]+\x60|'[^'\r\n]+')\s*@\s*(?:\x60[^\x60\r\n]+\x60|'[^'\r\n]+')\s+IDENTIFIED\s+BY\s+PASSWORD\s+)'(?<hash>\*[0-9a-fA-F]{40})'(?<suffix>\s*)$"
    foreach ($line in $GrantLines) {
        if ($line -isnot [string]) {
            throw 'MariaDB SHOW GRANTS returned a non-text line; output was discarded before logging.'
        }
        if ($line -notmatch $authenticationClausePattern) {
            $safeLines.Add($line)
            continue
        }
        $match = [regex]::Match($line, $observedMariaDbGrantPattern)
        if (-not $match.Success) {
            throw 'MariaDB SHOW GRANTS returned an unrecognized authentication clause; output was discarded before logging.'
        }
        $safeLines.Add($match.Groups['prefix'].Value + "'<redacted>'" + $match.Groups['suffix'].Value)
    }
    return $safeLines.ToArray()
}

function Test-T21MySqlCreateWithoutAlterGrantSet(
    [string[]]$GrantLines,
    [string]$DatabaseName,
    [string]$UserName,
    [string]$HostName,
    [ValidateSet('MySQL', 'MariaDB')][string]$DatabaseProduct = 'MySQL'
) {
    if ($null -eq $GrantLines -or $GrantLines.Count -ne 2 -or
        [string]::IsNullOrWhiteSpace($DatabaseName) -or [string]::IsNullOrWhiteSpace($UserName) -or [string]::IsNullOrWhiteSpace($HostName)) {
        return $false
    }

    $grantPattern = '^\s*GRANT\s+(?<privileges>.*?)\s+ON\s+(?<scope>\*\.\*|\x60(?<schema>[^\x60]+)\x60\.\*)\s+TO\s+\x60(?<user>[^\x60]+)\x60@\x60(?<host>[^\x60]+)\x60\s*$'
    $usageGrantCount = 0
    $schemaGrantCount = 0
    foreach ($grantLine in $GrantLines) {
        if ($grantLine -isnot [string] -or [string]::IsNullOrWhiteSpace($grantLine)) { return $false }
        $hasAuthenticationSuffix = $false
        if ($grantLine -match '(?i)IDENTIFIED\s+BY\s+PASSWORD\b') {
            if ($DatabaseProduct -cne 'MariaDB') { return $false }
            $authenticationSuffixMatch = [regex]::Match(
                $grantLine,
                "^(?<base>\s*GRANT\s+USAGE\s+ON\s+\*\.\*\s+TO\s+\x60[^\x60]+\x60@\x60[^\x60]+\x60)\s+IDENTIFIED\s+BY\s+PASSWORD\s+'<redacted>'\s*$",
                [Text.RegularExpressions.RegexOptions]::IgnoreCase
            )
            if (-not $authenticationSuffixMatch.Success) { return $false }
            $grantLine = $authenticationSuffixMatch.Groups['base'].Value
            $hasAuthenticationSuffix = $true
        }
        $grantMatch = [regex]::Match($grantLine, $grantPattern, [Text.RegularExpressions.RegexOptions]::IgnoreCase)
        if (-not $grantMatch.Success -or
            $grantMatch.Groups['user'].Value -cne $UserName -or
            $grantMatch.Groups['host'].Value -cne $HostName) { return $false }

        $privilegeNames = @($grantMatch.Groups['privileges'].Value.Split(',') | ForEach-Object { $_.Trim().ToUpperInvariant() })
        if ($privilegeNames.Count -eq 0 -or @($privilegeNames | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -gt 0) {
            return $false
        }

        if ($grantMatch.Groups['scope'].Value -ceq '*.*') {
            if ($privilegeNames.Count -ne 1 -or $privilegeNames[0] -cne 'USAGE') { return $false }
            $usageGrantCount++
            continue
        }

        if ($hasAuthenticationSuffix -or $grantMatch.Groups['schema'].Value -cne $DatabaseName -or
            'ALTER' -in $privilegeNames -or 'CREATE' -notin $privilegeNames) { return $false }
        $schemaGrantCount++
    }

    return $usageGrantCount -eq 1 -and $schemaGrantCount -eq 1
}

function Get-T21PlugDevCliDistManifestHash([object[]]$Files) {
    if ($null -eq $Files -or $Files.Count -eq 0) { throw 'The PlugDev CLI runtime manifest cannot be empty.' }
    $entriesByPath = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($entry in $Files) {
        $path = [string](Get-T21ObservedValue $entry 'path')
        $bytes = Get-T21ObservedValue $entry 'bytes'
        $sha256 = [string](Get-T21ObservedValue $entry 'sha256')
        if ([string]::IsNullOrWhiteSpace($path) -or $path.Contains('\') -or $path.StartsWith('/') -or
            $path -match '[\r\n\t]' -or $path -match '(^|/)\.{1,2}(/|$)' -or
            -not (Test-T21IntegralNumber $bytes 0 ([long]::MaxValue)) -or $sha256 -notmatch '^[0-9a-fA-F]{64}$' -or
            $entriesByPath.ContainsKey($path)) {
            throw 'The PlugDev CLI runtime manifest contains an invalid or duplicate entry.'
        }
        $entriesByPath.Add($path, [ordered]@{ path=$path; bytes=[long]$bytes; sha256=$sha256.ToLowerInvariant() })
    }

    $paths = [string[]]@($entriesByPath.Keys)
    [Array]::Sort($paths, [StringComparer]::Ordinal)
    $utf8 = [Text.UTF8Encoding]::new($false)
    $lines = [Collections.Generic.List[string]]::new()
    foreach ($path in $paths) {
        $entry = $entriesByPath[$path]
        $encodedPath = [Convert]::ToBase64String($utf8.GetBytes($path))
        $lines.Add("$encodedPath`t$($entry.bytes)`t$($entry.sha256)")
    }
    $canonicalBytes = $utf8.GetBytes(($lines -join "`n") + "`n")
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($canonicalBytes))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Get-T21PlugDevCliDistManifest([string]$PlugDevRoot) {
    $distPath = Join-Path $PlugDevRoot 'packages\cli\dist'
    if (-not (Test-Path -LiteralPath $distPath -PathType Container)) { throw 'The local PlugDev CLI runtime directory is unavailable.' }
    $items = @(Get-ChildItem -LiteralPath $distPath -Force -Recurse -ErrorAction Stop)
    if (@($items | Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 }).Count -gt 0) {
        throw 'The local PlugDev CLI runtime directory contains a reparse point.'
    }
    $files = @($items | Where-Object { -not $_.PSIsContainer })
    if ($files.Count -eq 0) { throw 'The local PlugDev CLI runtime directory has no files.' }
    $entries = [Collections.Generic.List[object]]::new()
    foreach ($file in $files) {
        $relativePath = $file.FullName.Substring($distPath.Length).TrimStart([char[]]@([char]92, [char]47)).Replace([string][char]92, '/')
        $entries.Add([ordered]@{
            path=$relativePath
            bytes=[long]$file.Length
            sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
        })
    }
    $manifestFiles = [object[]]$entries.ToArray()
    return [ordered]@{
        directory='packages/cli/dist'
        files=$manifestFiles
        sha256=(Get-T21PlugDevCliDistManifestHash $manifestFiles)
    }
}

function Test-T21PlugDevCliDistManifest([object]$Manifest, [string]$ExpectedSha256, [string]$ExpectedCliSha256) {
    try {
        if ((Get-T21ObservedValue $Manifest 'directory') -cne 'packages/cli/dist') { return $false }
        $files = Get-T21ObservedValue $Manifest 'files'
        $manifestSha256 = Get-T21PlugDevCliDistManifestHash ([object[]]$files)
        if ($manifestSha256 -cne $ExpectedSha256.ToLowerInvariant() -or
            ([string](Get-T21ObservedValue $Manifest 'sha256')).ToLowerInvariant() -cne $manifestSha256) { return $false }
        $cliFile = @($files | Where-Object { (Get-T21ObservedValue $_ 'path') -ceq 'cli.js' })
        return $cliFile.Count -eq 1 -and
            ([string](Get-T21ObservedValue $cliFile[0] 'sha256')).ToLowerInvariant() -ceq $ExpectedCliSha256.ToLowerInvariant()
    } catch {
        return $false
    }
}

function Test-T21NewerAuditSchemaStartupFailureReport(
    [object]$report,
    [string]$expectedServerSha256,
    [string]$expectedCandidateSha256,
    [string]$expectedAcceptanceSha256,
    [string]$expectedApiConsumerSha256,
    [Parameter(Mandatory = $true)][string]$ExpectedPlugDevRoot,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$ExpectedPlugDevSourceCommit,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedPlugDevCliSha256,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedPlugDevCliDistManifestSha256,
    [ValidateSet('MySQL', 'MariaDB', 'PostgreSQL')][string]$DatabaseProduct,
    [ValidateSet('Minimum', 'Maximum', 'CurrentStable', 'MinimumFolia', 'MaximumFolia', 'MinimumCanvas', 'MaximumCanvas')]
    [string]$PlatformEndpoint = 'Minimum',
    [ValidateSet('NewerAuditSchema', 'IncompleteV10AuditTable', 'IncompleteV10AuditTablePlayerRowPreservation', 'RequiredV11DdlPermissionDenied')][string]$FailureScenario = 'NewerAuditSchema'
) {
    try {
        $targetAccepted = switch ($FailureScenario) {
            'NewerAuditSchema' {
                Test-T21NewerAuditSchemaStartupFailureTarget -DatabaseProduct $DatabaseProduct -PlatformEndpoint $PlatformEndpoint
            }
            'IncompleteV10AuditTable' {
                Test-T21IncompleteAuditTableStartupFailureTarget -DatabaseProduct $DatabaseProduct -PlatformEndpoint $PlatformEndpoint
            }
            'IncompleteV10AuditTablePlayerRowPreservation' {
                Test-T21IncompleteAuditTablePlayerRowPreservationTarget -DatabaseProduct $DatabaseProduct -PlatformEndpoint $PlatformEndpoint
            }
            default {
                Test-T21RequiredV11DdlFailureTarget -DatabaseProduct $DatabaseProduct -PlatformEndpoint $PlatformEndpoint
            }
        }
        if (-not $targetAccepted) { return $false }
        $expectedPlatform = Get-T21PlatformEndpoint -PlatformEndpoint $PlatformEndpoint
        $expectedTask = switch ($FailureScenario) {
            'NewerAuditSchema' { 'T21-V03-SYNC-newer-audit-schema-startup-failure' }
            'IncompleteV10AuditTable' { 'T21-V03-SYNC-incomplete-v10-audit-table-startup-failure' }
            'IncompleteV10AuditTablePlayerRowPreservation' { "T21-V03-SYNC-incomplete-v10-audit-table-player-row-preservation-MySQL-$($expectedPlatform.taskSuffix)" }
            default { 'T21-V03-SYNC-v11-audit-ddl-permission-failure' }
        }
        $acceptedTasks = @($expectedTask)
        if ($FailureScenario -ceq 'IncompleteV10AuditTablePlayerRowPreservation' -and $PlatformEndpoint -ceq 'Minimum') {
            $acceptedTasks += 'T21-V03-SYNC-incomplete-v10-audit-table-player-row-preservation-MySQL-MinimumPaper'
        }
        $sqlReportKey = if ($DatabaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
        $expectedDatabaseType = if ($DatabaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }
        $expectedDatabaseVersion = switch ($DatabaseProduct) {
            'MySQL' { '8.0.42' }
            'MariaDB' { '12.3.3-MariaDB' }
            'PostgreSQL' { '18.1' }
        }
        if ((Get-T21ObservedValue $report 'schemaVersion') -ne 1 -or
            (Get-T21ObservedValue $report 'task') -cnotin $acceptedTasks -or
            (Get-T21ObservedValue $report 'status') -cne 'PASS_BOUNDED_SUBCASE' -or
            $null -ne (Get-T21ObservedValue $report 'failure') -or
            @(Get-T21ObservedValue $report 'cleanupFailures').Count -ne 0) { return $false }

        $server = Get-T21ObservedValue $report 'server'
        if ((Get-T21ObservedValue $server 'software') -cne $expectedPlatform.software -or
            (Get-T21ObservedValue $server 'minecraftVersion') -cne $expectedPlatform.minecraftVersion -or
            [int](Get-T21ObservedValue $server 'build') -ne [int]$expectedPlatform.build -or
            ([string](Get-T21ObservedValue $server 'sha256')).ToLowerInvariant() -cne $expectedServerSha256.ToLowerInvariant()) { return $false }

        $candidate = Get-T21ObservedValue $report 'candidate'
        $artifacts = Get-T21ObservedValue $report 'artifacts'
        $java = Get-T21ObservedValue $report 'java'
        $plugDev = Get-T21ObservedValue $report 'plugDev'
        $expectedPlugDevRoot = [IO.Path]::GetFullPath($ExpectedPlugDevRoot)
        $reportedPlugDevRoot = [IO.Path]::GetFullPath([string](Get-T21ObservedValue $plugDev 'root'))
        $plugDevManifest = Get-T21ObservedValue $plugDev 'cliDistManifest'
        $plugDevManifestFiles = Get-T21ObservedValue $plugDevManifest 'files'
        $plugDevObservations = @(Get-T21ObservedValue $plugDev 'cliDistInvocationObservations')
        if ((Get-T21ObservedValue $candidate 'version') -cne '1.4.0-alpha.1' -or
            ([string](Get-T21ObservedValue $candidate 'sha256')).ToLowerInvariant() -cne $expectedCandidateSha256.ToLowerInvariant() -or
            ([string](Get-T21ObservedValue $artifacts 'acceptanceSha256')).ToLowerInvariant() -cne $expectedAcceptanceSha256.ToLowerInvariant() -or
            ([string](Get-T21ObservedValue $artifacts 'apiConsumerSha256')).ToLowerInvariant() -cne $expectedApiConsumerSha256.ToLowerInvariant() -or
            [int](Get-T21ObservedValue $java 'feature') -ne 25 -or
            -not [string]::Equals($reportedPlugDevRoot, $expectedPlugDevRoot, [StringComparison]::OrdinalIgnoreCase) -or
            ([string](Get-T21ObservedValue $plugDev 'sourceCommit')).ToLowerInvariant() -cne $ExpectedPlugDevSourceCommit.ToLowerInvariant() -or
            ([string](Get-T21ObservedValue $plugDev 'sourceCommit')) -notmatch '^[0-9a-fA-F]{40}$' -or
            ([string](Get-T21ObservedValue $plugDev 'cliSha256')).ToLowerInvariant() -cne $ExpectedPlugDevCliSha256.ToLowerInvariant() -or
            ([string](Get-T21ObservedValue $plugDev 'cliSha256')) -notmatch '^[0-9a-fA-F]{64}$' -or
            -not (Test-T21PlugDevCliDistManifest $plugDevManifest $ExpectedPlugDevCliDistManifestSha256 $ExpectedPlugDevCliSha256) -or
            ([string](Get-T21ObservedValue $plugDev 'cliDistManifestSha256Before')).ToLowerInvariant() -cne $ExpectedPlugDevCliDistManifestSha256.ToLowerInvariant() -or
            ([string](Get-T21ObservedValue $plugDev 'cliDistManifestSha256After')).ToLowerInvariant() -cne $ExpectedPlugDevCliDistManifestSha256.ToLowerInvariant() -or
            $plugDevObservations.Count -eq 0) { return $false }
        foreach ($observation in $plugDevObservations) {
            if ([string]::IsNullOrWhiteSpace([string](Get-T21ObservedValue $observation 'label')) -or
                ([string](Get-T21ObservedValue $observation 'beforeSha256')).ToLowerInvariant() -cne $ExpectedPlugDevCliDistManifestSha256.ToLowerInvariant() -or
                ([string](Get-T21ObservedValue $observation 'afterSha256')).ToLowerInvariant() -cne $ExpectedPlugDevCliDistManifestSha256.ToLowerInvariant()) { return $false }
        }

        $configuration = Get-T21ObservedValue $report 'configuration'
        if ((Get-T21ObservedValue $configuration 'mode') -cne 'sync' -or
            (Get-T21ObservedValue $configuration 'databaseType') -cne $expectedDatabaseType -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'databaseEnabled') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'databaseIsolated') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'redisEnabled') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $configuration 'redisIsolated') $true) -or
            [int](Get-T21ObservedValue $configuration 'redisDatabase') -ne 15) { return $false }

        $sqlBackend = Get-T21ObservedValue $report 'sqlBackend'
        if ((Get-T21ObservedValue $sqlBackend 'product') -cne $DatabaseProduct -or
            (Get-T21ObservedValue $sqlBackend 'version') -cne $expectedDatabaseVersion) { return $false }

        $startup = Get-T21ObservedValue $report 'startupObservation'
        $startupIdentity = Get-T21ObservedValue $startup 'runtimeIdentity'
        $expectedFailureMessage = switch ($FailureScenario) {
            'NewerAuditSchema' { 'Audit schema version 12 is newer than this plugin supports (11); no audit schema changes were made.' }
            'IncompleteV10AuditTable' { 'Failed audit schema migration to version 10; version metadata was not advanced past 9' }
            'IncompleteV10AuditTablePlayerRowPreservation' { 'Failed audit schema migration to version 10; version metadata was not advanced past 9' }
            default { 'Failed audit schema migration to version 11; version metadata was not advanced past 10' }
        }
        $expectedFailureKind = switch ($FailureScenario) {
            'NewerAuditSchema' { 'newer-audit-schema' }
            'IncompleteV10AuditTable' { 'incomplete-v10-audit-table' }
            'IncompleteV10AuditTablePlayerRowPreservation' { 'incomplete-v10-audit-table' }
            default { 'required-v11-audit-ddl-permission-failure' }
        }
        $expectedFailureDetailMarker = switch ($FailureScenario) {
            'IncompleteV10AuditTable' { 'Required v10 audit columns are missing:' }
            'IncompleteV10AuditTablePlayerRowPreservation' { 'Required v10 audit columns are missing:' }
            'RequiredV11DdlPermissionDenied' {
                if ($DatabaseProduct -ceq 'PostgreSQL') { 'must be owner of table syncmoney_audit_log' }
                else { 'ALTER command denied' }
            }
            default { $null }
        }
        if ((Get-T21ObservedValue $startup 'stage') -cne 'candidate-startup-failure' -or
            (Get-T21ObservedValue $startup 'pluginVersion') -cne '1.4.0-alpha.1' -or
            ([string](Get-T21ObservedValue $startup 'artifactSha256')).ToLowerInvariant() -cne $expectedCandidateSha256.ToLowerInvariant() -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $startup 'enabled') $false) -or
            (Get-T21ObservedValue $startup 'expectedFailure') -cne $expectedFailureKind -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $startup 'failureObserved') $true) -or
            (Get-T21ObservedValue $startup 'failureMessage') -cne $expectedFailureMessage -or
            ($null -ne $expectedFailureDetailMarker -and
                (Get-T21ObservedValue $startup 'failureDetailMarker') -cne $expectedFailureDetailMarker) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $startup 'rollbackComplete') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $startup 'sqlPoolShutdownObserved') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $startup 'disabledMarker') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $startup 'successMarkerObserved') $false) -or
            [int](Get-T21ObservedValue $startupIdentity 'build') -ne [int]$expectedPlatform.build -or
            (Get-T21ObservedValue $startupIdentity 'software') -cne $expectedPlatform.software -or
            (Get-T21ObservedValue $startupIdentity 'minecraftVersion') -cne $expectedPlatform.minecraftVersion -or
            (Get-T21ObservedValue $startupIdentity 'runtimeVersion') -cne $expectedPlatform.minecraftVersion) { return $false }

        $schemaBefore = Get-T21ObservedValue $report 'schemaStateBefore'
        $schemaAfter = Get-T21ObservedValue $report 'schemaStateAfter'
        if ($FailureScenario -ceq 'NewerAuditSchema') {
            if ($null -ne (Get-T21ObservedValue $schemaBefore 'mainVersion') -or
                (Get-T21ObservedValue $schemaBefore 'mainVersionRowJson') -cne 'null' -or
                [int](Get-T21ObservedValue $schemaBefore 'auditVersion') -ne 12 -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $schemaBefore 'auditTableExists') $true) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $schemaBefore 'playersTableExists') $false) -or
                $null -ne (Get-T21ObservedValue $schemaBefore 'playerRows') -or
                [int](Get-T21ObservedValue $schemaAfter 'mainVersion') -ne 1 -or
                [int](Get-T21ObservedValue $schemaAfter 'auditVersion') -ne 12 -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $schemaAfter 'auditTableExists') $true) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $schemaAfter 'playersTableExists') $true) -or
                [int](Get-T21ObservedValue $schemaAfter 'playerRows') -ne 0) { return $false }
            $mainVersionRow = ConvertFrom-Json -InputObject ([string](Get-T21ObservedValue $schemaAfter 'mainVersionRowJson')) -AsHashtable -ErrorAction Stop
            if ($mainVersionRow['id'] -cne '1' -or [int]$mainVersionRow['version'] -ne 1) { return $false }
        } elseif ($FailureScenario -ceq 'IncompleteV10AuditTablePlayerRowPreservation') {
            if ([int](Get-T21ObservedValue $schemaBefore 'mainVersion') -ne 1 -or
                [int](Get-T21ObservedValue $schemaBefore 'auditVersion') -ne 9 -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $schemaBefore 'auditTableExists') $true) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $schemaBefore 'playersTableExists') $true) -or
                [int](Get-T21ObservedValue $schemaBefore 'playerRows') -ne 1 -or
                [int](Get-T21ObservedValue $schemaAfter 'mainVersion') -ne 1 -or
                [int](Get-T21ObservedValue $schemaAfter 'auditVersion') -ne 9 -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $schemaAfter 'auditTableExists') $true) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $schemaAfter 'playersTableExists') $true) -or
                [int](Get-T21ObservedValue $schemaAfter 'playerRows') -ne 1 -or
                [string](Get-T21ObservedValue $schemaBefore 'playerRowsJson') -cne [string](Get-T21ObservedValue $schemaAfter 'playerRowsJson') -or
                -not (Test-T21IncompleteV10PlayerRowSentinel (Get-T21ObservedValue $schemaBefore 'playerRowsJson')) -or
                -not (Test-T21IncompleteV10PlayerRowSentinel (Get-T21ObservedValue $schemaAfter 'playerRowsJson')) -or
                (Get-T21ObservedValue $schemaBefore 'mainVersionRowJson') -cne (Get-T21ObservedValue $schemaAfter 'mainVersionRowJson')) { return $false }
            $mainVersionRow = ConvertFrom-Json -InputObject ([string](Get-T21ObservedValue $schemaBefore 'mainVersionRowJson')) -AsHashtable -ErrorAction Stop
            if ($mainVersionRow['id'] -cne '1' -or [int]$mainVersionRow['version'] -ne 1) { return $false }
        } else {
            $expectedPreexistingAuditVersion = if ($FailureScenario -in @('IncompleteV10AuditTable', 'IncompleteV10AuditTablePlayerRowPreservation')) { 9 } else { 10 }
            if ([int](Get-T21ObservedValue $schemaBefore 'mainVersion') -ne 1 -or
                [int](Get-T21ObservedValue $schemaBefore 'auditVersion') -ne $expectedPreexistingAuditVersion -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $schemaBefore 'auditTableExists') $true) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $schemaBefore 'playersTableExists') $false) -or
                $null -ne (Get-T21ObservedValue $schemaBefore 'playerRows') -or
                [int](Get-T21ObservedValue $schemaAfter 'mainVersion') -ne 1 -or
                [int](Get-T21ObservedValue $schemaAfter 'auditVersion') -ne $expectedPreexistingAuditVersion -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $schemaAfter 'auditTableExists') $true) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $schemaAfter 'playersTableExists') $true) -or
                [int](Get-T21ObservedValue $schemaAfter 'playerRows') -ne 0 -or
                (Get-T21ObservedValue $schemaBefore 'mainVersionRowJson') -cne (Get-T21ObservedValue $schemaAfter 'mainVersionRowJson')) { return $false }
            $mainVersionRow = ConvertFrom-Json -InputObject ([string](Get-T21ObservedValue $schemaBefore 'mainVersionRowJson')) -AsHashtable -ErrorAction Stop
            if ($mainVersionRow['id'] -cne '1' -or [int]$mainVersionRow['version'] -ne 1) { return $false }
        }

        $preservedAuditProperties = @('auditVersionRowJson', 'auditColumnsJson', 'auditIndexesJson', 'auditRowsJson')
        foreach ($property in $preservedAuditProperties) {
            $beforeValue = [string](Get-T21ObservedValue $schemaBefore $property)
            $afterValue = [string](Get-T21ObservedValue $schemaAfter $property)
            if ([string]::IsNullOrWhiteSpace($beforeValue) -or $beforeValue -cne $afterValue) { return $false }
        }
        $auditVersionRow = ConvertFrom-Json -InputObject ([string](Get-T21ObservedValue $schemaBefore 'auditVersionRowJson')) -AsHashtable -ErrorAction Stop
        $expectedAuditVersion = switch ($FailureScenario) {
            'NewerAuditSchema' { 12 }
            'IncompleteV10AuditTable' { 9 }
            'IncompleteV10AuditTablePlayerRowPreservation' { 9 }
            default { 10 }
        }
        $expectedAuditDescription = switch ($FailureScenario) {
            'NewerAuditSchema' { 'T21 newer audit schema preservation sentinel' }
            'IncompleteV10AuditTable' { 'T21 incomplete audit v9 sentinel' }
            'IncompleteV10AuditTablePlayerRowPreservation' { 'T21 incomplete audit v9 sentinel' }
            default { 'T21 required v11 DDL failure sentinel' }
        }
        if ($auditVersionRow['id'] -cne 'audit' -or [int]$auditVersionRow['version'] -ne $expectedAuditVersion -or
            $auditVersionRow['description'] -cne $expectedAuditDescription -or
            [string]::IsNullOrWhiteSpace([string]$auditVersionRow['applied_at'])) { return $false }

        $auditColumns = @(ConvertFrom-Json -InputObject ([string](Get-T21ObservedValue $schemaBefore 'auditColumnsJson')) -AsHashtable -ErrorAction Stop)
        $requiredAuditColumns = switch ($FailureScenario) {
            'NewerAuditSchema' {
                @('id', 'timestamp', 'type', 'player_uuid', 'player_name', 'amount', 'balance_after',
                    'source', 'server', 'target_uuid', 'target_name', 'reason', 'merged_count', 'request_id', 'sequence', 't21_sentinel')
            }
            'IncompleteV10AuditTable' { @('id') }
            'IncompleteV10AuditTablePlayerRowPreservation' { @('id') }
            default {
                @('id', 'timestamp', 'type', 'player_uuid', 'player_name', 'amount', 'balance_after',
                    'source', 'server', 'target_uuid', 'target_name', 'reason', 'merged_count', 't21_sentinel')
            }
        }
        $requiredAuditColumns = @($requiredAuditColumns)
        $actualAuditColumns = @($auditColumns | ForEach-Object { [string]$_['column_name'] })
        $requiredColumnMetadataFields = if ($DatabaseProduct -ceq 'PostgreSQL') {
            @(
                'table_catalog', 'table_schema', 'table_name', 'column_name', 'ordinal_position', 'column_default', 'is_nullable', 'data_type',
                'character_maximum_length', 'character_octet_length', 'numeric_precision', 'numeric_precision_radix', 'numeric_scale',
                'datetime_precision', 'interval_type', 'interval_precision', 'character_set_catalog', 'character_set_schema', 'character_set_name',
                'collation_catalog', 'collation_schema', 'collation_name', 'domain_catalog', 'domain_schema', 'domain_name', 'udt_catalog',
                'udt_schema', 'udt_name', 'scope_catalog', 'scope_schema', 'scope_name', 'maximum_cardinality', 'dtd_identifier',
                'is_self_referencing', 'is_identity', 'identity_generation', 'identity_start', 'identity_increment', 'identity_maximum',
                'identity_minimum', 'identity_cycle', 'is_generated', 'generation_expression', 'is_updatable'
            )
        } else {
            @('table_schema', 'table_name', 'column_name', 'ordinal_position', 'column_default', 'is_nullable', 'column_type',
                'column_default_is_null', 'extra', 'character_set_name', 'collation_name', 'generation_expression')
        }
        if ($actualAuditColumns.Count -ne $requiredAuditColumns.Count -or $auditColumns.Count -ne $requiredAuditColumns.Count) { return $false }
        if ($FailureScenario -in @('IncompleteV10AuditTable', 'IncompleteV10AuditTablePlayerRowPreservation')) {
            $column = $auditColumns[0]
            $columnMetadataFields = @($column.Keys | ForEach-Object { [string]$_ })
            if ($column -isnot [System.Collections.IDictionary] -or
                $columnMetadataFields.Count -ne $requiredColumnMetadataFields.Count -or
                @($requiredColumnMetadataFields | Where-Object { $_ -cnotin $columnMetadataFields }).Count -ne 0 -or
                [string]$column['table_name'] -cne 'syncmoney_audit_log' -or
                [string]$column['column_name'] -cne 'id' -or
                -not (Test-T21IntegralNumber $column['ordinal_position'] 1 1) -or
                [string]$column['is_nullable'] -cne 'NO') { return $false }
            if ($DatabaseProduct -ceq 'PostgreSQL') {
                if ([string]$column['table_schema'] -cne 'public' -or
                    [string]$column['data_type'] -cne 'character varying' -or
                    [int]$column['character_maximum_length'] -ne 36 -or
                    [int]$column['character_octet_length'] -ne 144 -or
                    [string]$column['udt_name'] -cne 'varchar' -or
                    [string]$column['is_identity'] -cne 'NO' -or
                    [string]$column['is_generated'] -cne 'NEVER' -or
                    [string]$column['is_updatable'] -cne 'YES' -or
                    [string]::IsNullOrWhiteSpace([string]$column['table_catalog'])) { return $false }
            } else {
                $configuration = Get-T21ObservedValue $report 'configuration'
                $databaseName = [string](Get-T21ObservedValue $configuration 'databaseName')
                $observedDatabaseName = [string](Get-T21ObservedValue $configuration 'databaseNameObserved')
                $expectedCollation = if ($DatabaseProduct -ceq 'MySQL') { 'utf8mb4_0900_ai_ci' } else { 'utf8mb4_general_ci' }
                $generationExpressionValid = if ($DatabaseProduct -ceq 'MariaDB') {
                    $null -eq $column['generation_expression']
                } else {
                    $column['generation_expression'] -is [string] -and [string]$column['generation_expression'] -ceq ''
                }
                if ($databaseName -notmatch '^syncmoney_t21_[a-f0-9]{8}$' -or
                    $observedDatabaseName -cne $databaseName -or
                    [string]$column['table_schema'] -cne $databaseName -or
                    [string]$column['column_type'] -cne 'varchar(36)' -or
                    [string]$column['column_default_is_null'] -cne 'true' -or
                    $null -ne $column['column_default'] -or
                    $column['extra'] -isnot [string] -or [string]$column['extra'] -cne '' -or
                    [string]$column['character_set_name'] -cne 'utf8mb4' -or
                    [string]$column['collation_name'] -cne $expectedCollation -or
                    -not $generationExpressionValid) { return $false }
            }
            $schemaBeforeMainRow = ConvertFrom-Json -InputObject ([string](Get-T21ObservedValue $schemaBefore 'mainVersionRowJson')) -AsHashtable -ErrorAction Stop
            if ($schemaBeforeMainRow['id'] -cne '1' -or [int]$schemaBeforeMainRow['version'] -ne 1) { return $false }
        } else {
        $expectedTableCatalog = $null
        for ($columnIndex = 0; $columnIndex -lt $requiredAuditColumns.Count; $columnIndex++) {
            $column = $auditColumns[$columnIndex]
            if ($column -isnot [System.Collections.IDictionary]) { return $false }
            $columnMetadataFields = @($column.Keys | ForEach-Object { [string]$_ })
            if ($columnMetadataFields.Count -ne $requiredColumnMetadataFields.Count -or
                @($requiredColumnMetadataFields | Where-Object { $_ -cnotin $columnMetadataFields }).Count -ne 0 -or
                $actualAuditColumns[$columnIndex] -cne $requiredAuditColumns[$columnIndex] -or
                [int]$column['ordinal_position'] -ne ($columnIndex + 1) -or
                [string]$column['table_name'] -cne 'syncmoney_audit_log') { return $false }
            if ($DatabaseProduct -ceq 'PostgreSQL') {
                if ([string]::IsNullOrWhiteSpace([string]$column['table_catalog']) -or [string]$column['table_schema'] -cne 'public') { return $false }
                if ($null -eq $expectedTableCatalog) { $expectedTableCatalog = [string]$column['table_catalog'] }
                elseif ([string]$column['table_catalog'] -cne $expectedTableCatalog) { return $false }
            } else {
            if ([string]::IsNullOrWhiteSpace([string]$column['table_schema'])) { return $false }
                if ($null -eq $expectedTableCatalog) { $expectedTableCatalog = [string]$column['table_schema'] }
                elseif ([string]$column['table_schema'] -cne $expectedTableCatalog) { return $false }

            $expectedColumnMetadata = @(
                @{ typePattern = '^varchar\(36\)$'; nullable = 'NO'; default = $null; character = $true }
                @{ typePattern = '^(?:bigint|bigint\(20\))$'; nullable = 'NO'; default = $null; character = $false }
                @{ typePattern = '^varchar\(20\)$'; nullable = 'NO'; default = $null; character = $true }
                @{ typePattern = '^varchar\(36\)$'; nullable = 'NO'; default = $null; character = $true }
                @{ typePattern = '^varchar\(16\)$'; nullable = 'YES'; default = $null; character = $true }
                @{ typePattern = '^decimal\(20,2\)$'; nullable = 'NO'; default = $null; character = $false }
                @{ typePattern = '^decimal\(20,2\)$'; nullable = 'NO'; default = $null; character = $false }
                @{ typePattern = '^varchar\(30\)$'; nullable = 'NO'; default = $null; character = $true }
                @{ typePattern = '^varchar\(50\)$'; nullable = 'NO'; default = $null; character = $true }
                @{ typePattern = '^varchar\(36\)$'; nullable = 'YES'; default = $null; character = $true }
                @{ typePattern = '^varchar\(16\)$'; nullable = 'YES'; default = $null; character = $true }
                @{ typePattern = '^varchar\(255\)$'; nullable = 'YES'; default = $null; character = $true }
                @{ typePattern = '^(?:int|int\(11\))$'; nullable = 'YES'; default = '1'; character = $false }
                @{ typePattern = '^varchar\(36\)$'; nullable = 'YES'; default = $null; character = $true }
                @{ typePattern = '^(?:bigint|bigint\(20\))$'; nullable = 'YES'; default = '0'; character = $false }
                @{ typePattern = '^text$'; nullable = 'NO'; default = $null; character = $true }
            )
            if ($FailureScenario -ceq 'RequiredV11DdlPermissionDenied') {
                $expectedColumnMetadata = @($expectedColumnMetadata[0..12]) + @($expectedColumnMetadata[15])
            }
            $expected = $expectedColumnMetadata[$columnIndex]
            $default = $column['column_default']
            $defaultIsNull = [string]$column['column_default_is_null']
            $defaultMatches = if ($null -eq $expected.default) {
                if ($DatabaseProduct -ceq 'MariaDB' -and $expected.nullable -ceq 'YES') {
                    $defaultIsNull -ceq 'false' -and [string]$default -ceq 'NULL'
                } else {
                    $defaultIsNull -ceq 'true' -and $null -eq $default
                }
            } else {
                $defaultIsNull -ceq 'false' -and [string]$default -ceq [string]$expected.default
            }
            if ([string]$column['column_type'] -cnotmatch $expected.typePattern -or
                [string]$column['is_nullable'] -cne $expected.nullable -or
                -not $defaultMatches -or
                [string]$column['extra'] -cne '' -or
                ($null -ne $column['generation_expression'] -and [string]$column['generation_expression'] -cne '')) { return $false }
            $expectedCollation = if ($DatabaseProduct -ceq 'MySQL') { 'utf8mb4_0900_ai_ci' } else { 'utf8mb4_general_ci' }
            if ($expected.character) {
                if ([string]$column['character_set_name'] -cne 'utf8mb4' -or
                    [string]$column['collation_name'] -cne $expectedCollation) { return $false }
            } elseif ($null -ne $column['character_set_name'] -or $null -ne $column['collation_name']) {
                return $false
            }
            }
        }
        }

        $auditIndexes = @(ConvertFrom-Json -InputObject ([string](Get-T21ObservedValue $schemaBefore 'auditIndexesJson')) -AsHashtable -ErrorAction Stop)
        if ($FailureScenario -in @('IncompleteV10AuditTable', 'IncompleteV10AuditTablePlayerRowPreservation', 'RequiredV11DdlPermissionDenied')) {
            if ($auditIndexes.Count -ne 1) { return $false }
            if ($DatabaseProduct -ceq 'PostgreSQL') {
                if ([string]$auditIndexes[0]['name'] -cne 'syncmoney_audit_log_pkey' -or
                    [string]$auditIndexes[0]['definition'] -notmatch 'UNIQUE.*\(id\)') { return $false }
            } else {
                $index = $auditIndexes[0]
                $columns = @(Get-T21ObservedValue $index 'columns')
                if (-not (Test-T21ExactDictionaryFields $index @('name', 'nonUnique', 'indexType', 'visibility', 'columns')) -or
                    -not (Test-T21IntegralNumber (Get-T21ObservedValue $index 'nonUnique') 0 0) -or
                    $index['name'] -isnot [string] -or [string]$index['name'] -cne 'PRIMARY' -or
                    $index['indexType'] -isnot [string] -or [string]$index['indexType'] -cne 'BTREE' -or
                    $index['visibility'] -isnot [string] -or [string]$index['visibility'] -cne 'YES' -or
                    $columns.Count -ne 1 -or
                    -not (Test-T21ExactDictionaryFields $columns[0] @('position', 'columnName', 'direction', 'subPart')) -or
                    -not (Test-T21IntegralNumber (Get-T21ObservedValue $columns[0] 'position') 1 1) -or
                    $columns[0]['columnName'] -isnot [string] -or [string]$columns[0]['columnName'] -cne 'id' -or
                    $columns[0]['direction'] -isnot [string] -or [string]$columns[0]['direction'] -cne 'A' -or
                    -not $columns[0].Contains('subPart') -or $null -ne $columns[0]['subPart']) { return $false }
            }
        } elseif ($DatabaseProduct -ceq 'PostgreSQL') {
            $requiredAuditIndexes = @('idx_audit_timestamp', 'idx_audit_player', 'idx_audit_type_timestamp_sequence', 'syncmoney_audit_log_pkey')
            $actualAuditIndexNames = @($auditIndexes | ForEach-Object { [string]$_['name'] })
            if ($auditIndexes.Count -ne $requiredAuditIndexes.Count -or
                @($requiredAuditIndexes | Where-Object { $_ -notin $actualAuditIndexNames }).Count -ne 0) { return $false }
            foreach ($index in $auditIndexes) {
                if ([string]::IsNullOrWhiteSpace([string]$index['definition'])) { return $false }
            }
            foreach ($expectedIndex in @(
                    @{ name = 'idx_audit_timestamp'; pattern = 'timestamp.*DESC.*sequence.*DESC' }
                    @{ name = 'idx_audit_player'; pattern = 'player_uuid.*timestamp.*DESC.*sequence.*DESC' }
                    @{ name = 'idx_audit_type_timestamp_sequence'; pattern = 'type.*timestamp.*DESC.*sequence.*DESC' }
                    @{ name = 'syncmoney_audit_log_pkey'; pattern = 'UNIQUE.*\(id\)' }
                )) {
                $matchingIndex = @($auditIndexes | Where-Object { $_['name'] -ceq $expectedIndex.name })
                if ($matchingIndex.Count -ne 1 -or [string]$matchingIndex[0]['definition'] -notmatch $expectedIndex.pattern) { return $false }
            }
        } else {
            $expectedIndexes = [ordered]@{
                PRIMARY = @([ordered]@{ columnName = 'id'; direction = 'A' })
                idx_audit_timestamp = @([ordered]@{ columnName = 'timestamp'; direction = 'D' }, [ordered]@{ columnName = 'sequence'; direction = 'D' })
                idx_audit_player = @([ordered]@{ columnName = 'player_uuid'; direction = 'A' }, [ordered]@{ columnName = 'timestamp'; direction = 'D' }, [ordered]@{ columnName = 'sequence'; direction = 'D' })
                idx_audit_type_timestamp_sequence = @([ordered]@{ columnName = 'type'; direction = 'A' }, [ordered]@{ columnName = 'timestamp'; direction = 'D' }, [ordered]@{ columnName = 'sequence'; direction = 'D' })
            }
            $actualIndexNames = @($auditIndexes | ForEach-Object { [string]$_['name'] })
            if ($auditIndexes.Count -ne $expectedIndexes.Count -or
                @($expectedIndexes.Keys | Where-Object { $_ -cnotin $actualIndexNames }).Count -ne 0) { return $false }
            foreach ($index in $auditIndexes) {
                $name = [string]$index['name']
                if (-not $expectedIndexes.Contains($name) -or
                    [string]$index['indexType'] -cne 'BTREE' -or
                    (Get-T21ObservedValue $index 'visibility') -cne 'YES' -or
                    [int]$index['nonUnique'] -ne $(if ($name -ceq 'PRIMARY') { 0 } else { 1 })) { return $false }
                $columns = @($index['columns'])
                $expectedColumns = @($expectedIndexes[$name])
                if ($columns.Count -ne $expectedColumns.Count) { return $false }
                for ($columnIndex = 0; $columnIndex -lt $columns.Count; $columnIndex++) {
                    if ([int]$columns[$columnIndex]['position'] -ne ($columnIndex + 1) -or
                        [string]$columns[$columnIndex]['columnName'] -cne [string]$expectedColumns[$columnIndex]['columnName'] -or
                        [string]$columns[$columnIndex]['direction'] -cne [string]$expectedColumns[$columnIndex]['direction'] -or
                        $null -ne $columns[$columnIndex]['subPart']) { return $false }
                }
            }
        }

        $auditRowsJson = [string](Get-T21ObservedValue $schemaBefore 'auditRowsJson')
        $auditRows = @(ConvertFrom-Json -InputObject $auditRowsJson -AsHashtable -ErrorAction Stop)
        if ($FailureScenario -in @('IncompleteV10AuditTable', 'IncompleteV10AuditTablePlayerRowPreservation')) {
            if ($auditRows.Count -ne 1 -or
                $auditRows[0].Count -ne 1 -or
                $auditRows[0]['id'] -cne '00000000-0000-0000-0000-000000000329') { return $false }
        } elseif ($FailureScenario -ceq 'RequiredV11DdlPermissionDenied') {
            if ($auditRows.Count -ne 1 -or
                -not (Test-T21RequiredV11AuditSentinelRow $auditRows[0]) -or
                -not (Test-T21RequiredV11AuditSentinelMoneyJson $auditRowsJson)) { return $false }
        } elseif ($auditRows.Count -ne 1 -or
            $auditRows[0]['id'] -cne '00000000-0000-0000-0000-000000000321' -or
            $auditRows[0]['t21_sentinel'] -cne 't21-v12-preservation-sentinel') { return $false }

        if ($FailureScenario -ceq 'RequiredV11DdlPermissionDenied') {
            $privileges = Get-T21ObservedValue $report 'databaseDdlPrivileges'
            $configuration = Get-T21ObservedValue $report 'configuration'
            $databaseName = [string](Get-T21ObservedValue $configuration 'databaseName')
            $observedDatabaseName = [string](Get-T21ObservedValue $configuration 'databaseNameObserved')
            $databaseMatch = [regex]::Match($databaseName, '^syncmoney_t21_(?<suffix>[a-f0-9]{8})$')
            $grantee = [string](Get-T21ObservedValue $privileges 'grantee')
            $expectedGranteePattern = if ($DatabaseProduct -ceq 'PostgreSQL') {
                '^sm_t21_(?<suffix>[a-f0-9]{8})$'
            } else {
                '^sm_t21_(?<suffix>[a-f0-9]{8})@127\.0\.0\.1$'
            }
            $granteeMatch = [regex]::Match($grantee, $expectedGranteePattern)
            $expectedUser = if ($databaseMatch.Success) { 'sm_t21_' + $databaseMatch.Groups['suffix'].Value } else { '' }
            $expectedGrantee = if ($DatabaseProduct -ceq 'PostgreSQL') { $expectedUser } else { $expectedUser + '@127.0.0.1' }
            if (-not $databaseMatch.Success -or -not $granteeMatch.Success -or
                $observedDatabaseName -cne $databaseName -or
                $granteeMatch.Groups['suffix'].Value -cne $databaseMatch.Groups['suffix'].Value -or
                $grantee -cne $expectedGrantee) { return $false }

            if ($DatabaseProduct -ceq 'PostgreSQL') {
                if (-not (Test-T21PostgreSqlAuditOwnerBoundary $privileges $databaseName $expectedUser)) { return $false }
            } else {
                $grants127 = @(Get-T21ObservedValue $privileges 'grants')
                $grantsLocal = @(Get-T21ObservedValue $privileges 'localGrants')
                $validGrants127 = Test-T21MySqlCreateWithoutAlterGrantSet ([string[]]$grants127) $databaseName $expectedUser '127.0.0.1' $DatabaseProduct
                $validGrantsLocal = Test-T21MySqlCreateWithoutAlterGrantSet ([string[]]$grantsLocal) $databaseName $expectedUser 'localhost' $DatabaseProduct
                if (-not (Test-T21ExactDictionaryFields $privileges @('product', 'grantee', 'grants', 'localGrants', 'alterGranted', 'createGranted', 'verifiedBeforeStartup')) -or
                    (Get-T21ObservedValue $privileges 'product') -cne $DatabaseProduct -or
                    -not $validGrants127 -or -not $validGrantsLocal -or
                    -not (Test-T21ObservedBoolean (Get-T21ObservedValue $privileges 'alterGranted') $false) -or
                    -not (Test-T21ObservedBoolean (Get-T21ObservedValue $privileges 'createGranted') $true) -or
                    -not (Test-T21ObservedBoolean (Get-T21ObservedValue $privileges 'verifiedBeforeStartup') $true)) { return $false }
            }
        }
        $resourceCleanup = Get-T21ObservedValue $report 'resourceCleanup'
        $sqlOtherConnections = Get-T21ObservedValue $resourceCleanup 'sqlOtherConnections'
        $redisOtherClients = Get-T21ObservedValue $resourceCleanup 'redisOtherClients'
        $redisDbSize = Get-T21ObservedValue $resourceCleanup 'redisDbSize'
        if (-not (Test-T21IntegralNumber $sqlOtherConnections 0 ([long]::MaxValue)) -or $sqlOtherConnections -ne 0 -or
            -not (Test-T21IntegralNumber $redisOtherClients 0 ([long]::MaxValue)) -or $redisOtherClients -ne 0 -or
            -not (Test-T21IntegralNumber $redisDbSize 0 ([long]::MaxValue)) -or $redisDbSize -ne 0) { return $false }

        $startupPid = [int](Get-T21ObservedValue $startup 'processId')
        if ($startupPid -le 0) { return $false }
        $normalStops = @(Get-T21ObservedValue $report 'normalStops')
        if ($normalStops.Count -ne 1) { return $false }
        $normalStop = $normalStops[0]
        if ((Get-T21ObservedValue $normalStop 'stage') -cne 'after-startup-failure' -or
            [int](Get-T21ObservedValue $normalStop 'processId') -ne $startupPid -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $normalStop 'commandExitCode') 0) -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $normalStop 'processExitCode') 0) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $normalStop 'processExitCodeObserved') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $normalStop 'processExited') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $normalStop 'disabledMarker') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $normalStop 'forced') $false)) { return $false }

        $gradleCleanup = Get-T21ObservedValue $report 'gradleDaemonCleanup'
        $expectedGradleHome = [IO.Path]::GetFullPath((Join-Path ([string](Get-T21ObservedValue $report 'runDirectory')) '.gradle-user-home'))
        $reportedGradleHome = [IO.Path]::GetFullPath([string](Get-T21ObservedValue $gradleCleanup 'isolatedUserHome'))
        $processIdsBeforeStop = @(Get-T21ObservedValue $gradleCleanup 'processIdsBeforeStop')
        $processIdsAfterStop = @(Get-T21ObservedValue $gradleCleanup 'processIdsAfterStop')
        $processExitVerified = Get-T21ObservedValue $gradleCleanup 'processExitVerified'
        $stopCommandExitCode = Get-T21ObservedValue $gradleCleanup 'stopCommandExitCode'
        $stopLogPath = [string](Get-T21ObservedValue $gradleCleanup 'stopLogPath')
        if ([string]::IsNullOrWhiteSpace([string](Get-T21ObservedValue $gradleCleanup 'isolatedUserHome')) -or
            -not [string]::Equals($reportedGradleHome, $expectedGradleHome, [StringComparison]::OrdinalIgnoreCase) -or
            (Test-T21PathContainsReparsePoint $expectedGradleHome) -or
            -not (Test-T21ObservedBoolean $processExitVerified $true) -or
            $processIdsAfterStop.Count -ne 0 -or
            @($processIdsBeforeStop | Where-Object { -not (Test-T21IntegralNumber $_ 1 ([long]::MaxValue)) }).Count -gt 0 -or
            @($processIdsBeforeStop | Select-Object -Unique).Count -ne $processIdsBeforeStop.Count) { return $false }

        $logMap = Get-T21ObservedValue $report 'logs'
        if ($processIdsBeforeStop.Count -gt 0) {
            if (-not (Test-T21ExpectedExitCode $stopCommandExitCode 0) -or
                $stopLogPath -cne 'logs\gradle-daemon-stop.txt' -or
                $null -eq (Get-T21ObservedValue $logMap $stopLogPath)) { return $false }
        } elseif ((-not (Test-T21ExpectedExitCode $stopCommandExitCode 0) -and $null -ne $stopCommandExitCode) -or
            -not [string]::IsNullOrEmpty($stopLogPath)) { return $false }

        $backendProcesses = Get-T21ObservedValue $report 'backendProcesses'
        $sqlProcess = Get-T21ObservedValue $backendProcesses $sqlReportKey
        $redisProcess = Get-T21ObservedValue $backendProcesses 'redis'
        if ((Get-T21ObservedValue $sqlProcess 'product') -cne $DatabaseProduct -or
            (Get-T21ObservedValue $sqlProcess 'version') -cne $expectedDatabaseVersion -or
            [int](Get-T21ObservedValue $sqlProcess 'processId') -le 0 -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlProcess 'runOwned') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlProcess 'listenerOwnerVerified') $true) -or
            (Get-T21ObservedValue $redisProcess 'version') -cne '8.10.1' -or
            [int](Get-T21ObservedValue $redisProcess 'processId') -le 0 -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisProcess 'runOwned') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisProcess 'listenerOwnerVerified') $true)) { return $false }

        $backendShutdowns = @(Get-T21ObservedValue $report 'backendShutdowns')
        if ($backendShutdowns.Count -ne 2) { return $false }
        $expectedSqlBackendName = $sqlReportKey
        $expectedSqlShutdownCommand = if ($DatabaseProduct -ceq 'PostgreSQL') { 'pg_ctl stop -m fast -w' } else { 'mysqladmin shutdown' }
        $sqlShutdown = @($backendShutdowns | Where-Object { (Get-T21ObservedValue $_ 'backend') -ceq $expectedSqlBackendName })
        $redisShutdown = @($backendShutdowns | Where-Object { (Get-T21ObservedValue $_ 'backend') -ceq 'redis' })
        if ($sqlShutdown.Count -ne 1 -or $redisShutdown.Count -ne 1 -or
            [int](Get-T21ObservedValue $sqlShutdown[0] 'processId') -ne [int](Get-T21ObservedValue $sqlProcess 'processId') -or
            (Get-T21ObservedValue $sqlShutdown[0] 'command') -cne $expectedSqlShutdownCommand -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $sqlShutdown[0] 'commandExitCode') 0) -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $sqlShutdown[0] 'processExitCode') 0) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'processExitCodeObserved') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'processExited') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'shutdownTargetVerified') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'forced') $false) -or
            [int](Get-T21ObservedValue $redisShutdown[0] 'processId') -ne [int](Get-T21ObservedValue $redisProcess 'processId') -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $redisShutdown[0] 'commandExitCode') 0) -or
            -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $redisShutdown[0] 'processExitCode') 0) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisShutdown[0] 'processExitCodeObserved') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisShutdown[0] 'processExited') $true) -or
            -not (Test-T21ObservedBoolean (Get-T21ObservedValue $redisShutdown[0] 'forced') $false)) { return $false }
        if ($DatabaseProduct -cne 'PostgreSQL' -and
            ([int](Get-T21ObservedValue $sqlShutdown[0] 'launchProcessId') -le 0 -or
                [int](Get-T21ObservedValue $sqlShutdown[0] 'launchProcessId') -ne [int](Get-T21ObservedValue $sqlProcess 'launchProcessId') -or
                -not (Test-T21ExpectedExitCode (Get-T21ObservedValue $sqlShutdown[0] 'launchProcessExitCode') 0) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'launchProcessExitCodeObserved') $true) -or
                -not (Test-T21ObservedBoolean (Get-T21ObservedValue $sqlShutdown[0] 'launchProcessExited') $true))) { return $false }

        $sourcePins = Get-T21ObservedValue $report 'sourcePins'
        $pinFiles = [ordered]@{
            runnerSha256 = (Join-Path $PSScriptRoot 'run-mysql-sync-runtime.ps1')
            validatorSha256 = (Join-Path $PSScriptRoot 't21-startup-failure-validation.ps1')
            validatorTestSha256 = (Join-Path $PSScriptRoot 't21-startup-failure-validation.test.ps1')
            sharedValidatorSha256 = (Join-Path $PSScriptRoot 't21-upgrade-validation.ps1')
            sharedValidatorTestSha256 = (Join-Path $PSScriptRoot 't21-upgrade-validation.test.ps1')
        }
        foreach ($pinName in $pinFiles.Keys) {
            $pinValue = [string](Get-T21ObservedValue $sourcePins $pinName)
            $expectedPin = (Get-FileHash -LiteralPath $pinFiles[$pinName] -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
            if ($pinValue.ToLowerInvariant() -cne $expectedPin) { return $false }
        }
        if (([string](Get-T21ObservedValue $sourcePins 'acceptanceJarSha256')).ToLowerInvariant() -cne $expectedAcceptanceSha256.ToLowerInvariant() -or
            ([string](Get-T21ObservedValue $sourcePins 'apiConsumerJarSha256')).ToLowerInvariant() -cne $expectedApiConsumerSha256.ToLowerInvariant() -or
            ([string](Get-T21ObservedValue $sourcePins 'plugDevCliDistManifestSha256')).ToLowerInvariant() -cne $ExpectedPlugDevCliDistManifestSha256.ToLowerInvariant()) { return $false }

        $runRoot = [IO.Path]::GetFullPath([string](Get-T21ObservedValue $report 'runDirectory'))
        if (-not (Test-Path -LiteralPath $runRoot -PathType Container) -or (Test-T21PathContainsReparsePoint $runRoot)) { return $false }
        $runRootPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        $logsRoot = [IO.Path]::GetFullPath((Join-Path $runRoot 'logs')).TrimEnd([char[]]@('\', '/'))
        $logsRootPrefix = $logsRoot + [IO.Path]::DirectorySeparatorChar
        if (-not (Test-Path -LiteralPath $logsRoot -PathType Container) -or (Test-T21PathContainsReparsePoint $logsRoot)) { return $false }
        $logMap = Get-T21ObservedValue $report 'logs'
        $logEntries = if ($logMap -is [Collections.IDictionary]) {
            @($logMap.GetEnumerator())
        } else {
            @($logMap.PSObject.Properties | Where-Object { $_.MemberType -in @('NoteProperty', 'Property') })
        }
        if ($logEntries.Count -eq 0) { return $false }
        $pathComparison = if ([IO.Path]::DirectorySeparatorChar -eq '\') { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
        $logPathComparer = if ([IO.Path]::DirectorySeparatorChar -eq '\') { [StringComparer]::OrdinalIgnoreCase } else { [StringComparer]::Ordinal }
        $declaredLogPaths = [Collections.Generic.HashSet[string]]::new($logPathComparer)
        foreach ($logEntry in $logEntries) {
            $logPath = if ($logEntry -is [Collections.DictionaryEntry]) { [string]$logEntry.Key } else { [string]$logEntry.Name }
            $logEvidence = $logEntry.Value
            if ([string]::IsNullOrWhiteSpace($logPath) -or [IO.Path]::IsPathRooted($logPath)) { return $false }
            $fullManifestLogPath = [IO.Path]::GetFullPath((Join-Path $runRoot $logPath))
            if (-not $fullManifestLogPath.StartsWith($logsRootPrefix, $pathComparison) -or
                (Test-T21PathContainsReparsePoint $fullManifestLogPath) -or
                -not (Test-Path -LiteralPath $fullManifestLogPath -PathType Leaf)) { return $false }
            $relativeManifestLogPath = $fullManifestLogPath.Substring($runRootPrefix.Length).Replace('/', '\')
            if (-not $declaredLogPaths.Add($relativeManifestLogPath)) { return $false }
            $manifestLogInfo = Get-Item -LiteralPath $fullManifestLogPath -Force -ErrorAction Stop
            $manifestLogBytes = Get-T21ObservedValue $logEvidence 'bytes'
            $manifestLogSha256 = [string](Get-T21ObservedValue $logEvidence 'sha256')
            if (-not (Test-T21IntegralNumber $manifestLogBytes 0 ([long]::MaxValue)) -or
                $manifestLogSha256 -notmatch '^[0-9a-fA-F]{64}$' -or
                [long]$manifestLogBytes -ne [long]$manifestLogInfo.Length -or
                $manifestLogSha256.ToLowerInvariant() -cne (Get-FileHash -LiteralPath $fullManifestLogPath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()) { return $false }
        }
        $logTreeItems = @(Get-ChildItem -LiteralPath $logsRoot -Recurse -Force -ErrorAction Stop)
        foreach ($logTreeItem in $logTreeItems) {
            if (($logTreeItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { return $false }
        }
        $actualLogFiles = @($logTreeItems | Where-Object { -not $_.PSIsContainer })
        if ($actualLogFiles.Count -ne $declaredLogPaths.Count) { return $false }
        foreach ($actualLogFile in $actualLogFiles) {
            $actualRelativeLogPath = $actualLogFile.FullName.Substring($runRootPrefix.Length).Replace('/', '\')
            if (-not $declaredLogPaths.Contains($actualRelativeLogPath)) { return $false }
        }

        if ($FailureScenario -ceq 'RequiredV11DdlPermissionDenied') {
            $privileges = Get-T21ObservedValue $report 'databaseDdlPrivileges'
            if ($DatabaseProduct -ceq 'PostgreSQL') {
                $privilegeLogPath = 'logs\postgresql-audit-ddl-privileges.txt'
                if (-not $declaredLogPaths.Contains($privilegeLogPath) -or
                    $null -eq (Get-T21ObservedValue $logMap $privilegeLogPath)) { return $false }
                $reportedEvidenceJson = Get-T21ObservedValue $privileges 'evidenceJson'
                $rawEvidenceJson = [IO.File]::ReadAllText((Join-Path $runRoot $privilegeLogPath), [Text.UTF8Encoding]::new($false)).Trim()
                $configuration = Get-T21ObservedValue $report 'configuration'
                if ($reportedEvidenceJson -isnot [string] -or
                    -not [string]::Equals([string]$reportedEvidenceJson, $rawEvidenceJson, [StringComparison]::Ordinal) -or
                    -not (Test-T21PostgreSqlAuditOwnerBoundary $privileges ([string](Get-T21ObservedValue $configuration 'databaseName')) ([string](Get-T21ObservedValue $privileges 'grantee')))) {
                    return $false
                }
            } else {
                $grantLogPaths = [ordered]@{
                    grants = 'logs\runtime-user-grants-127.txt'
                    localGrants = 'logs\runtime-user-grants-local.txt'
                }
                foreach ($grantField in $grantLogPaths.Keys) {
                    $grantLogPath = [string]$grantLogPaths[$grantField]
                    if (-not $declaredLogPaths.Contains($grantLogPath) -or
                        $null -eq (Get-T21ObservedValue $logMap $grantLogPath)) { return $false }

                    $reportedGrantLines = @(Get-T21ObservedValue $privileges $grantField)
                    $rawGrantLines = [IO.File]::ReadAllLines((Join-Path $runRoot $grantLogPath), [Text.UTF8Encoding]::new($false))
                    if ($reportedGrantLines.Count -ne $rawGrantLines.Count) { return $false }
                    for ($lineIndex = 0; $lineIndex -lt $rawGrantLines.Count; $lineIndex++) {
                        if ($reportedGrantLines[$lineIndex] -isnot [string] -or
                            -not [string]::Equals([string]$reportedGrantLines[$lineIndex], $rawGrantLines[$lineIndex], [StringComparison]::Ordinal)) {
                            return $false
                        }
                    }
                }
            }
            if ($DatabaseProduct -in @('MySQL', 'MariaDB') -and $PlatformEndpoint -ceq 'Maximum') {
                $evidencePath = 'logs\mysql-denied-request-id-ddl-evidence.jsonl'
                $ddlEvidence = Get-T21ObservedValue $report 'deniedDdlEvidence'
                $expectedEvidenceFields = @('source', 'databaseName', 'runtimeUser', 'eventCount', 'logPath')
                $configuration = Get-T21ObservedValue $report 'configuration'
                $reportedDatabase = [string](Get-T21ObservedValue $configuration 'databaseNameObserved')
                $grantee = [string](Get-T21ObservedValue $privileges 'grantee')
                $expectedRuntimeUser = if ($grantee -match '^([^@]+)@127\.0\.0\.1$') { $Matches[1] } else { '' }
                if (-not (Test-T21ExactDictionaryFields $ddlEvidence $expectedEvidenceFields) -or
                    (Get-T21ObservedValue $ddlEvidence 'source') -cne 'mysql.general_log' -or
                    (Get-T21ObservedValue $ddlEvidence 'databaseName') -cne $reportedDatabase -or
                    (Get-T21ObservedValue $ddlEvidence 'runtimeUser') -cne $expectedRuntimeUser -or
                    -not (Test-T21IntegralNumber (Get-T21ObservedValue $ddlEvidence 'eventCount') 1 1) -or
                    (Get-T21ObservedValue $ddlEvidence 'logPath') -cne $evidencePath -or
                    -not $declaredLogPaths.Contains($evidencePath) -or
                    $null -eq (Get-T21ObservedValue $logMap $evidencePath)) { return $false }
                $eventLines = [IO.File]::ReadAllLines((Join-Path $runRoot $evidencePath), [Text.UTF8Encoding]::new($false))
                if ($eventLines.Count -ne 1 -or [string]::IsNullOrWhiteSpace($eventLines[0])) { return $false }
                $ddlEvent = ConvertFrom-Json -InputObject $eventLines[0] -AsHashtable -ErrorAction Stop
                if (-not (Test-T21MySqlDeniedRequestIdDdlEvent $ddlEvent $expectedRuntimeUser)) { return $false }
            }
        }

        $platformLogs = @(Get-T21ObservedValue $report 'platformLogs')
        if ($platformLogs.Count -ne 1) { return $false }
        $platformLog = $platformLogs[0]
        $relativePath = [string](Get-T21ObservedValue $platformLog 'path')
        if ($relativePath -cne 'logs\platform-candidate-startup-failure.txt' -or
            (Get-T21ObservedValue $platformLog 'stage') -cne 'candidate-startup-failure') { return $false }
        $fullLogPath = [IO.Path]::GetFullPath((Join-Path $runRoot $relativePath))
        if (-not $fullLogPath.StartsWith($runRootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
            (Test-T21PathContainsReparsePoint $fullLogPath) -or
            -not (Test-Path -LiteralPath $fullLogPath -PathType Leaf)) { return $false }
        $actualLog = Get-Item -LiteralPath $fullLogPath -ErrorAction Stop
        $actualLogHash = (Get-FileHash -LiteralPath $fullLogPath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
        $expectedBytes = [long](Get-T21ObservedValue $platformLog 'bytes')
        $expectedHash = ([string](Get-T21ObservedValue $platformLog 'sha256')).ToLowerInvariant()
        if ($expectedBytes -le 0 -or [long]$actualLog.Length -ne $expectedBytes -or
            $actualLogHash -cne $expectedHash -or $expectedHash -notmatch '^[0-9a-f]{64}$') { return $false }
        $mappedLog = Get-T21ObservedValue $logMap $relativePath
        if ([long](Get-T21ObservedValue $mappedLog 'bytes') -ne $expectedBytes -or
            ([string](Get-T21ObservedValue $mappedLog 'sha256')).ToLowerInvariant() -cne $expectedHash) { return $false }
        $logText = Get-Content -LiteralPath $fullLogPath -Raw -ErrorAction Stop
        $scenarioMarkers = switch ($FailureScenario) {
            'NewerAuditSchema' {
                @('Audit schema version 12 is newer than this plugin supports (11); no audit schema changes were made.')
            }
            'IncompleteV10AuditTable' {
                @('Syncmoney audit schema version: current 9, target 11',
                    'Failed audit schema migration to version 10; version metadata was not advanced past 9',
                    'Required v10 audit columns are missing:')
            }
            'IncompleteV10AuditTablePlayerRowPreservation' {
                @('Syncmoney audit schema version: current 9, target 11',
                    'Failed audit schema migration to version 10; version metadata was not advanced past 9',
                    'Required v10 audit columns are missing:')
            }
            default {
                $ddlFailureMarker = if ($DatabaseProduct -ceq 'PostgreSQL') {
                    'must be owner of table syncmoney_audit_log'
                } else {
                    'ALTER command denied'
                }
                @('Syncmoney audit schema version: current 10, target 11',
                    'Failed audit schema migration to version 11; version metadata was not advanced past 10',
                    $ddlFailureMarker)
            }
        }
        foreach ($requiredMarker in @(
                'Loading server plugin Syncmoney v1.4.0-alpha.1',
                'Syncmoney initialization failed; rolling back initialized owners') + $scenarioMarkers + @(
                '[noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Shutdown completed.',
                'Syncmoney disabled.')) {
            if (-not $logText.Contains($requiredMarker)) { return $false }
        }
        $infoLevelPoolShutdownPattern = '(?m)^\[[^\]\r\n]+\] \[[^\]\r\n]+/INFO\]: \[noietime\.libs\.hikari\.HikariDataSource\] Syncmoney-HikariCP - Shutdown completed\.\r?$'
        if ($logText -notmatch $infoLevelPoolShutdownPattern) { return $false }
        if ($logText.Contains('Syncmoney enabled successfully')) { return $false }
        $loggedRuntimeIdentity = Get-T21PlatformIdentity $logText
        Assert-T21PlatformIdentity $loggedRuntimeIdentity $expectedPlatform.software $expectedPlatform.minecraftVersion $expectedPlatform.build

        return $true
    } catch {
        Write-Verbose ("Startup-failure report validation rejected the report: " + $_.Exception.Message + " at " + $_.ScriptStackTrace)
        return $false
    }
}
