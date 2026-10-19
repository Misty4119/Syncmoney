Set-StrictMode -Version Latest

function Get-T21DatabaseManagerInitAuditLogField([object]$Value, [string]$Name) {
    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Collections.IDictionary]) {
        if ($Value.Contains($Name)) { return ,$Value[$Name] }
        return $null
    }
    $property = $Value.PSObject.Properties[$Name]
    if ($null -ne $property) { return ,$property.Value }
    return $null
}

function Test-T21DatabaseManagerInitAuditLogFields([object]$Value, [string[]]$ExpectedFields) {
    if ($null -eq $Value) { return $false }
    if ($Value -is [System.Collections.IDictionary]) {
        $actualFields = @($Value.Keys | ForEach-Object { [string]$_ })
    } elseif ($Value -is [System.Management.Automation.PSCustomObject]) {
        $actualFields = @($Value.PSObject.Properties | Where-Object MemberType -eq 'NoteProperty' | ForEach-Object Name)
    } else {
        return $false
    }
    if ($actualFields.Count -ne $ExpectedFields.Count) { return $false }
    foreach ($field in $ExpectedFields) {
        if ($field -cnotin $actualFields) { return $false }
    }
    return $true
}

function Test-T21DatabaseManagerInitAuditWorktreeDelta([string[]]$StatusEntries) {
    $allowedPaths = @(
        'tools/plugdev/audit-t21-database-manager-init-failure.ps1',
        'tools/plugdev/t21-database-manager-init-audit-log.ps1',
        'tools/plugdev/t21-database-manager-init-audit-log.test.ps1'
    )
    foreach ($entry in @($StatusEntries)) {
        if ($entry -isnot [string] -or $entry.Length -lt 4) { return $false }
        $status = $entry.Substring(0, 2)
        $path = $entry.Substring(3).Trim()
        if ($path.StartsWith('"') -and $path.EndsWith('"')) { $path = $path.Substring(1, $path.Length - 2) }
        $path = $path.Replace('\', '/')
        if ($path -notin $allowedPaths) { return $false }

        $allowedStatuses = switch ($path) {
            'tools/plugdev/audit-t21-database-manager-init-failure.ps1' { @(' M', 'M ') }
            'tools/plugdev/t21-database-manager-init-audit-log.ps1' { @('??', 'A ', ' A') }
            'tools/plugdev/t21-database-manager-init-audit-log.test.ps1' { @('??', 'A ', ' A') }
            default { @() }
        }
        if ($status -notin $allowedStatuses) { return $false }
    }
    return $true
}

function Get-T21DatabaseManagerInitAuditLogEvidence([object]$Report, [System.Collections.IDictionary]$Logs) {
    $platformLogs = Get-T21DatabaseManagerInitAuditLogField $Report 'platformLogs'
    if ($platformLogs -is [array]) {
        if ($platformLogs.Count -ne 1) { throw 'Runtime report must contain exactly one platform log for this Paper startup-failure case.' }
        $entry = $platformLogs[0]
    } else {
        $entry = $platformLogs
    }

    $fields = @('stage', 'path', 'bytes', 'sha256')
    if (-not (Test-T21DatabaseManagerInitAuditLogFields $entry $fields)) {
        throw 'Runtime report platform log evidence is missing or malformed.'
    }

    $stage = Get-T21DatabaseManagerInitAuditLogField $entry 'stage'
    if ($stage -isnot [string] -or $stage -cne 'candidate-database-manager-init-failure') {
        throw 'Runtime report platform log is not the candidate database-manager initialization-failure snapshot.'
    }

    $path = Get-T21DatabaseManagerInitAuditLogField $entry 'path'
    if ($path -isnot [string] -or [string]::IsNullOrWhiteSpace($path)) { throw 'Runtime report platform log path is missing.' }
    if ([IO.Path]::IsPathRooted($path) -or $path -match '(^|[\\/])\.\.([\\/]|$)' -or $path -match '^[a-zA-Z]:') {
        throw 'Runtime report platform log path must stay inside the run directory.'
    }
    if ($path -notmatch '(?i)^logs[\\/]') { throw 'Runtime report platform log must be a retained logs/ file.' }

    $manifestPath = $path.Replace('/', '\')
    if (-not $Logs.Contains($manifestPath)) { throw 'Runtime report platform log is absent from its retained-log manifest.' }
    $manifest = $Logs[$manifestPath]

    $bytes = Get-T21DatabaseManagerInitAuditLogField $entry 'bytes'
    $manifestBytes = Get-T21DatabaseManagerInitAuditLogField $manifest 'bytes'
    $integerTypes = @([byte], [sbyte], [int16], [uint16], [int32], [uint32], [int64], [uint64])
    if ($null -eq $bytes -or $bytes.GetType() -notin $integerTypes -or $null -eq $manifestBytes -or $manifestBytes.GetType() -notin $integerTypes) {
        throw 'Runtime report platform log byte counts must be integers.'
    }
    if ([long]$bytes -ne [long]$manifestBytes) { throw 'Runtime report platform log byte count differs from its retained-log manifest.' }

    $sha256 = Get-T21DatabaseManagerInitAuditLogField $entry 'sha256'
    $manifestSha256 = Get-T21DatabaseManagerInitAuditLogField $manifest 'sha256'
    if ($sha256 -isnot [string] -or $sha256 -notmatch '^[0-9a-fA-F]{64}$' -or $manifestSha256 -isnot [string] -or $manifestSha256 -notmatch '^[0-9a-fA-F]{64}$') {
        throw 'Runtime report platform log SHA-256 values are malformed.'
    }
    if ($sha256 -cne $manifestSha256) { throw 'Runtime report platform log SHA-256 differs from its retained-log manifest.' }

    return [pscustomobject]@{
        stage = $stage
        path = $manifestPath
        bytes = [long]$bytes
        sha256 = $sha256.ToLowerInvariant()
    }
}
