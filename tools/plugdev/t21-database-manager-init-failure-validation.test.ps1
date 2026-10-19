$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot 't21-database-manager-init-failure-validation.ps1')
. (Join-Path $PSScriptRoot 't21-startup-failure-validation.ps1')

$assertions = 0

function Assert-Predicate([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
    $script:assertions++
}

$runnerSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'run-mysql-sync-runtime.ps1') -Raw
$runnerPath = Join-Path $PSScriptRoot 'run-mysql-sync-runtime.ps1'
$runnerTokens = $null
$runnerParseErrors = $null
$runnerAst = [System.Management.Automation.Language.Parser]::ParseFile($runnerPath, [ref]$runnerTokens, [ref]$runnerParseErrors)
Assert-Predicate ($runnerParseErrors.Count -eq 0) 'The runtime runner must parse before checking its validator call contract.'
$databaseManagerReportCalls = @($runnerAst.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.CommandAst] -and
        $node.GetCommandName() -ceq 'Test-T21DatabaseManagerInitFailureReport'
}, $true))
Assert-Predicate ($databaseManagerReportCalls.Count -eq 1) 'The runtime runner must contain exactly one DatabaseManager report-validator call.'
$reportCallArguments = @($databaseManagerReportCalls[0].CommandElements | Select-Object -Skip 1 | ForEach-Object { $_.Extent.Text })
$expectedReportCallArguments = @(
    '$script:finalReport', '$ExpectedServerSha256', '$ExpectedCandidateSha256',
    '$script:plugDevSourceCommit', '$script:plugDevCliSha256', '$script:plugDevCliDistManifest.sha256',
    '$acceptanceSha256', '$apiConsumerSha256', '$script:sourceCommit', '$PlatformEndpoint'
)
Assert-Predicate (($reportCallArguments -join "`n") -ceq ($expectedReportCallArguments -join "`n")) 'The runtime runner must pass all report pins and the selected Paper endpoint to the DatabaseManager predicate.'
Assert-Predicate (([regex]::Matches($runnerSource, 'T21-SQL-POOL-INITIAL-SCHEMA-FAILURE-PostgreSQL-\$PlatformEndpoint')).Count -eq 2) 'Both in-progress and final DatabaseManager reports must use the endpoint identifier accepted by the report predicate.'
Assert-Predicate ($runnerSource -match '\$DatabaseManagerInitFailureStartupFailure\.IsPresent -and\s+\$script:sourceWorkingTreeDirty') 'The clean-source guard must be limited to the DatabaseManager initialization-failure case.'
Assert-Predicate ($runnerSource -notmatch 'if \(\$script:sourceWorkingTreeDirty\)') 'The clean-source guard must not change behavior for other T21 runner modes.'
Assert-Predicate ($runnerSource -match 'The completed report did not satisfy Test-T21DatabaseManagerInitFailureReport\.') 'DatabaseManager report failures must name their own validator.'
foreach ($catalog in @('pg_catalog\.pg_class', 'pg_catalog\.pg_proc', 'pg_catalog\.pg_type')) {
    Assert-Predicate ($runnerSource -match $catalog) "PostgreSQL schema snapshot must count objects from $catalog."
}
Assert-Predicate ($runnerSource -match "'publicObjectCount'") 'PostgreSQL schema snapshot must expose its public object count.'
Assert-Predicate ($runnerSource -match '(?s)if \(\$script:databaseManagerInitFailureStartupFailure\)\s*\{\s*\$script:finalReport\.runner\s*=\s*\$script:report\.runner\s*\}') 'Only the DatabaseManager report should retain the runner provenance object consumed by its validator and offline auditor.'

$auditPath = Join-Path $PSScriptRoot 'audit-t21-database-manager-init-failure.ps1'
$auditSource = Get-Content -LiteralPath $auditPath -Raw
Assert-Predicate ($auditSource -match "\[ValidateSet\('Minimum', 'CurrentMaximum'\)\]\[string\]\`$PlatformEndpoint = 'CurrentMaximum'") 'The offline auditor must support minimum Paper while keeping CurrentMaximum as its default endpoint.'
Assert-Predicate ($auditSource -match '(?m)^\$reportPredicate = Test-T21DatabaseManagerInitFailureReport .*\$PlatformEndpoint\s*$') 'The offline auditor must pass its selected endpoint to the report predicate.'
Assert-Predicate ($auditSource -match 'Test-T21DatabaseManagerInitFailureStringFields \$sourcePins \$sourcePinExpected') 'The offline auditor must validate source pins as scalar strings.'
foreach ($field in @('databaseManagerSourceSha256', 'storageManagerSourceSha256', 'syncmoneyLifecycleSourceSha256')) {
    Assert-Predicate ($auditSource -match ('{0} = \$sourceHashes\.' -f [regex]::Escape($field))) "The offline auditor must compare the Java source pin against its current file hash: $field"
}
Assert-Predicate ($auditSource -match 'Test-T21DatabaseManagerInitFailureCleanGitStatus -StatusEntries \$plugDevDirty -ExitCode \$plugDevStatusExitCode') 'The offline auditor must reject failed PlugDev git status checks.'
Assert-Predicate ($auditSource -match 'Test-T21DatabaseManagerInitFailureCleanGitStatus -StatusEntries \$syncmoneySourceDirty -ExitCode \$syncmoneySourceStatusExitCode') 'The offline auditor must reject failed Syncmoney git status checks.'
Assert-Predicate ($auditSource -match 'Test-T21DatabaseManagerInitFailureStringField \$server ''path'' \$serverJarPath') 'The offline auditor must validate the server path as a scalar string.'
Assert-Predicate ($auditSource -match 'Test-T21DatabaseManagerInitFailureInteger \$server ''bytes''') 'The offline auditor must validate server artifact byte count as an integer.'

function New-ValidPrivilegeEvidence {
    [pscustomobject]@{
        appRole = 'sm_t21_test'
        appRoleIsSuperuser = $false
        appRoleCanCreateDatabase = $false
        appRoleOwnsDatabase = $false
        appRoleOwnsPublicSchema = $false
        targetDatabaseOwner = 'postgres'
        publicSchemaOwner = 'pg_database_owner'
        bootstrapConnectGranted = $true
        targetConnectGranted = $true
        appRoleHasPublicUsage = $true
        appRoleHasPublicCreate = $false
        publicHasCreate = $false
    }
}

function New-ValidSchemaEvidence {
    [pscustomobject]@{
        before = [pscustomobject]@{ playersTableExists = $false; schemaVersionTableExists = $false; publicTableCount = 0; publicObjectCount = 0 }
        after = [pscustomobject]@{ playersTableExists = $false; schemaVersionTableExists = $false; publicTableCount = 0; publicObjectCount = 0 }
    }
}

function New-ValidCleanupEvidence {
    [pscustomobject]@{
        forcedCleanup = $false
        serverStopped = $true
        serverProcessCount = 0
        postgresStopped = $true
        redisStopped = $true
        runOwnedPortsClosed = $true
        sqlOtherConnections = 0
        redisOtherClients = 0
        redisDbSize = 0
        cleanupFailures = @()
    }
}

function New-ValidDatabaseManagerInitFailureReport([ValidateSet('Minimum', 'CurrentMaximum')][string]$PlatformEndpoint = 'CurrentMaximum') {
    $server = if ($PlatformEndpoint -ceq 'Minimum') {
        [pscustomobject]@{ software = 'paper'; minecraftVersion = '1.21.6'; build = 48; sha256 = '0' * 64 }
    } else {
        [pscustomobject]@{ software = 'paper'; minecraftVersion = '26.3'; build = 200; sha256 = '0' * 64 }
    }
    [pscustomobject]@{
        task = "T21-SQL-POOL-INITIAL-SCHEMA-FAILURE-PostgreSQL-$PlatformEndpoint"
        status = 'PASS_BOUNDED_SUBCASE'
        sourceCommit = 'd' * 40
        sourceCommitAfter = 'd' * 40
        sourceWorkingTreeDirty = $false
        sourceWorkingTreeDirtyAfter = $false
        failure = $null
        server = $server
        candidate = [pscustomobject]@{ version = '1.4.0-alpha.1'; sha256 = '1' * 64 }
        artifacts = [pscustomobject]@{ acceptanceSha256 = '4' * 64; apiConsumerSha256 = '5' * 64 }
        sqlBackend = [pscustomobject]@{ product = 'PostgreSQL'; version = '18.1' }
        redis = [pscustomobject]@{ version = '8.10.1' }
        configuration = [pscustomobject]@{
            mode = 'sync'; databaseType = 'postgresql'; databaseEnabled = $true; databaseIsolated = $true
            redisEnabled = $true; redisIsolated = $true; redisDatabase = 15
        }
        startupObservation = [pscustomobject]@{
            stage = 'candidate-startup-failure'; pluginVersion = '1.4.0-alpha.1'
            expectedFailure = 'database-manager-initial-schema-failure'
            failureMessage = 'Failed to initialize database schema: ERROR: permission denied for schema public'
            enabled = $false; failureObserved = $true; rollbackComplete = $true; sqlPoolStartObserved = $true
            sqlPoolShutdownObserved = $true; disabledMarker = $true; successMarkerObserved = $false
        }
        databaseDdlPrivileges = New-ValidPrivilegeEvidence
        schemaStateBefore = (New-ValidSchemaEvidence).before
        schemaStateAfter = (New-ValidSchemaEvidence).after
        resourceCleanup = New-ValidCleanupEvidence
        cleanupFailures = @()
        plugDev = [pscustomobject]@{
            sourceCommit = 'a' * 40; cliSha256 = 'b' * 64
            cliDistManifestSha256Before = 'c' * 64; cliDistManifestSha256After = 'c' * 64
        }
        runner = [pscustomobject]@{
            sha256 = '9' * 64
            startupFailureValidatorSha256 = '5' * 64
            startupFailureValidatorTestSha256 = '6' * 64
            validatorSha256 = '7' * 64
            testSha256 = '8' * 64
            databaseManagerInitFailureValidatorSha256 = '2' * 64
            databaseManagerInitFailureTestSha256 = '3' * 64
        }
        sourcePins = [pscustomobject]@{
            sourceCommit = 'd' * 40
            plugDevCommit = 'a' * 40; plugDevCliSha256 = 'b' * 64; plugDevCliDistManifestSha256 = 'c' * 64
            runnerSha256 = '9' * 64
            validatorSha256 = '5' * 64; validatorTestSha256 = '6' * 64
            sharedValidatorSha256 = '7' * 64; sharedValidatorTestSha256 = '8' * 64
            databaseManagerInitFailureValidatorSha256 = '2' * 64; databaseManagerInitFailureTestSha256 = '3' * 64
            databaseManagerSourceSha256 = 'a' * 64; storageManagerSourceSha256 = 'b' * 64; syncmoneyLifecycleSourceSha256 = 'c' * 64
            acceptanceJarSha256 = '4' * 64; apiConsumerJarSha256 = '5' * 64
            paperJarSha256 = '0' * 64; candidateJarSha256 = '1' * 64
        }
    }
}

$scalarPins = [pscustomobject]@{ databaseManagerSourceSha256 = 'a' * 64 }
$expectedScalarPins = [ordered]@{ databaseManagerSourceSha256 = 'a' * 64 }
Assert-Predicate (Test-T21DatabaseManagerInitFailureStringFields $scalarPins $expectedScalarPins) 'Exact scalar string pins should pass.'
$scalarPins.databaseManagerSourceSha256 = [object[]]@('a' * 64)
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureStringFields $scalarPins $expectedScalarPins)) 'A singleton array must not impersonate a scalar source hash in the offline audit.'

Assert-Predicate (Test-T21DatabaseManagerInitFailureCleanGitStatus -StatusEntries ([object[]]@()) -ExitCode 0) 'A successful empty git status should be considered clean.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureCleanGitStatus -StatusEntries ([object[]]@()) -ExitCode 128)) 'A failed git status with empty output must not be considered clean.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureCleanGitStatus -StatusEntries ([object[]]@(' M file')) -ExitCode 0)) 'A successful git status with changes must not be considered clean.'

$validLog = @'
[Syncmoney] Loading server plugin Syncmoney v1.4.0-alpha.1
[noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Start completed.
[Syncmoney] Failed to initialize database schema: ERROR: permission denied for schema public
[noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Shutdown completed.
[Syncmoney] Syncmoney initialization failed; rolling back initialized owners
[Server thread/INFO]: Disabling Syncmoney v1.4.0-alpha.1
'@

$result = Test-T21DatabaseManagerInitFailureStartupLog -LogText $validLog -CandidateVersion '1.4.0-alpha.1'
Assert-Predicate $result.passed 'The ordered PostgreSQL schema-denial startup log should pass.'
Assert-Predicate ($null -ne (Get-Command Get-T21PlugDevCliDistManifest -ErrorAction SilentlyContinue)) 'The startup-failure validator must expose the PlugDev manifest helper consumed by the offline auditor.'

$missingPoolClose = $validLog.Replace('[noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Shutdown completed.', '')
$result = Test-T21DatabaseManagerInitFailureStartupLog -LogText $missingPoolClose -CandidateVersion '1.4.0-alpha.1'
Assert-Predicate (-not $result.passed) 'A missing Hikari shutdown marker must fail closed.'

$successMarker = $validLog + "`n[Syncmoney] Syncmoney enabled successfully"
$result = Test-T21DatabaseManagerInitFailureStartupLog -LogText $successMarker -CandidateVersion '1.4.0-alpha.1'
Assert-Predicate (-not $result.passed) 'A success marker must reject the startup-failure report.'

$wrongOrder = @'
[Syncmoney] Loading server plugin Syncmoney v1.4.0-alpha.1
[Syncmoney] Syncmoney initialization failed; rolling back initialized owners
[noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Shutdown completed.
[Syncmoney] Failed to initialize database schema: ERROR: permission denied for schema public
[Server thread/INFO]: Disabling Syncmoney v1.4.0-alpha.1
'@
$result = Test-T21DatabaseManagerInitFailureStartupLog -LogText $wrongOrder -CandidateVersion '1.4.0-alpha.1'
Assert-Predicate (-not $result.passed) 'Out-of-order failure and cleanup markers must fail closed.'

$wrongCandidate = Test-T21DatabaseManagerInitFailureStartupLog -LogText $validLog -CandidateVersion '1.4.0-alpha.2'
Assert-Predicate (-not $wrongCandidate.passed) 'A different candidate version must fail closed.'

$missingPoolStart = $validLog.Replace('[noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Start completed.', '')
$result = Test-T21DatabaseManagerInitFailureStartupLog -LogText $missingPoolStart -CandidateVersion '1.4.0-alpha.1'
Assert-Predicate (-not $result.passed) 'A missing Hikari start marker must fail closed.'

$privileges = New-ValidPrivilegeEvidence
Assert-Predicate ((Test-T21DatabaseManagerInitFailurePrivileges $privileges).passed) 'The isolated non-owner privilege fixture should pass.'

$privileges.appRoleIsSuperuser = $true
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailurePrivileges $privileges).passed) 'A superuser application role must be rejected.'

$privileges = New-ValidPrivilegeEvidence
$privileges.appRoleCanCreateDatabase = $true
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailurePrivileges $privileges).passed) 'An application role with CREATEDB must be rejected.'

$privileges = New-ValidPrivilegeEvidence
$privileges.appRoleOwnsDatabase = $true
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailurePrivileges $privileges).passed) 'An application role that owns the database must be rejected.'

$privileges = New-ValidPrivilegeEvidence
$privileges.appRoleOwnsPublicSchema = $true
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailurePrivileges $privileges).passed) 'An application role that owns public schema must be rejected.'

$privileges = New-ValidPrivilegeEvidence
$privileges.appRoleHasPublicUsage = $false
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailurePrivileges $privileges).passed) 'Missing public-schema USAGE must be rejected as a pre-Hikari failure risk.'

$privileges = New-ValidPrivilegeEvidence
$privileges.appRoleHasPublicCreate = $true
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailurePrivileges $privileges).passed) 'Application CREATE on public schema must be rejected.'

$privileges = New-ValidPrivilegeEvidence
$privileges.publicHasCreate = $true
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailurePrivileges $privileges).passed) 'PUBLIC CREATE on public schema must be rejected.'

$privileges = New-ValidPrivilegeEvidence
$privileges.bootstrapConnectGranted = $false
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailurePrivileges $privileges).passed) 'Missing bootstrap CONNECT must be rejected before runtime.'

$privileges = New-ValidPrivilegeEvidence
$privileges.targetConnectGranted = $false
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailurePrivileges $privileges).passed) 'Missing target-database CONNECT must be rejected.'

$privileges = New-ValidPrivilegeEvidence
$privileges.appRoleHasPublicCreate = 'false'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailurePrivileges $privileges).passed) 'String-shaped privilege evidence must fail closed.'

$schema = New-ValidSchemaEvidence
Assert-Predicate ((Test-T21DatabaseManagerInitFailureSchemaState $schema).passed) 'An unchanged empty initial schema should pass.'

$schema = New-ValidSchemaEvidence
$schema.after.publicObjectCount = 1
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureSchemaState $schema).passed) 'A public non-table schema object must fail this initial-create-denial case.'

$schema = New-ValidSchemaEvidence
$schema.after.playersTableExists = $true
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureSchemaState $schema).passed) 'A created players table must fail this initial-create-denial case.'

$schema = New-ValidSchemaEvidence
$schema.before.publicTableCount = 1
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureSchemaState $schema).passed) 'A pre-existing public table must be rejected.'

$schema = New-ValidSchemaEvidence
$schema.after.publicTableCount = '0'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureSchemaState $schema).passed) 'String-shaped table counts must fail closed.'

$cleanup = New-ValidCleanupEvidence
Assert-Predicate ((Test-T21DatabaseManagerInitFailureCleanup $cleanup).passed) 'Graceful, zero-residue cleanup should pass.'

$cleanup = New-ValidCleanupEvidence
$cleanup.forcedCleanup = $true
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureCleanup $cleanup).passed) 'Forced cleanup must fail closed.'

$cleanup = New-ValidCleanupEvidence
$cleanup.sqlOtherConnections = 1
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureCleanup $cleanup).passed) 'Residual SQL connections must fail closed.'

$cleanup = New-ValidCleanupEvidence
$cleanup.redisDbSize = 1
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureCleanup $cleanup).passed) 'Residual Redis keys must fail closed.'

$cleanup = New-ValidCleanupEvidence
$cleanup.runOwnedPortsClosed = $false
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureCleanup $cleanup).passed) 'Open run-owned ports must fail closed.'

$cleanup = New-ValidCleanupEvidence
$cleanup.cleanupFailures = @('PostgreSQL shutdown failed')
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureCleanup $cleanup).passed) 'Any recorded cleanup failure must fail closed.'

$cleanup = New-ValidCleanupEvidence
$cleanup.cleanupFailures = 'none'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureCleanup $cleanup).passed) 'Scalar cleanup evidence must fail closed.'

Assert-Predicate (Test-T21DatabaseManagerInitFailureTarget 'PostgreSQL' 'CurrentMaximum') 'The pinned PostgreSQL current-maximum cell should remain enabled.'
Assert-Predicate (Test-T21DatabaseManagerInitFailureTarget 'PostgreSQL' 'Minimum') 'The pinned PostgreSQL minimum Paper cell should be enabled.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureTarget 'PostgreSQL' 'Maximum')) 'The historical Paper maximum must not be accepted for this case.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureTarget 'PostgreSQL' 'MinimumFolia')) 'An unselected Folia endpoint must remain rejected.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureTarget 'MySQL' 'CurrentMaximum')) 'A different SQL product must not be accepted for this cell.'

$report = New-ValidDatabaseManagerInitFailureReport
$reportPassed = Test-T21DatabaseManagerInitFailureReport $report ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40)
Assert-Predicate $reportPassed 'The pinned Paper/PostgreSQL startup-failure report should pass.'

$minimumReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint Minimum
$reportPassed = Test-T21DatabaseManagerInitFailureReport $minimumReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40)
Assert-Predicate (-not $reportPassed) 'A minimum-Paper report must not pass when the current-maximum endpoint is expected.'
$reportPassed = Test-T21DatabaseManagerInitFailureReport $minimumReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'Minimum'
Assert-Predicate $reportPassed 'The exactly pinned minimum-Paper/PostgreSQL startup-failure report should pass.'
$minimumReport.server.build = 200
$reportPassed = Test-T21DatabaseManagerInitFailureReport $minimumReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'Minimum'
Assert-Predicate (-not $reportPassed) 'A minimum-Paper report with the current-maximum build number must be rejected.'

$report = New-ValidDatabaseManagerInitFailureReport
$report.server.build = '200'
$reportPassed = Test-T21DatabaseManagerInitFailureReport $report ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40)
Assert-Predicate (-not $reportPassed) 'A string-shaped Paper build number must be rejected.'

foreach ($field in @('software', 'minecraftVersion', 'build')) {
    $report = New-ValidDatabaseManagerInitFailureReport
    $report.server.$field = [object[]]@($report.server.$field)
    $reportPassed = Test-T21DatabaseManagerInitFailureReport $report ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40)
    Assert-Predicate (-not $reportPassed) "A singleton array must not impersonate the pinned server identity field: $field"
}

foreach ($field in @('databaseManagerSourceSha256', 'storageManagerSourceSha256', 'syncmoneyLifecycleSourceSha256')) {
    $report = New-ValidDatabaseManagerInitFailureReport
    $report.sourcePins.$field = [object[]]@($report.sourcePins.$field)
    $reportPassed = Test-T21DatabaseManagerInitFailureReport $report ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40)
    Assert-Predicate (-not $reportPassed) "A singleton array must not impersonate a Java source hash pin: $field"
}

$sourcePinFields = @(
    'runnerSha256', 'validatorSha256', 'validatorTestSha256',
    'sharedValidatorSha256', 'sharedValidatorTestSha256',
    'databaseManagerInitFailureValidatorSha256', 'databaseManagerInitFailureTestSha256'
)
foreach ($field in $sourcePinFields) {
    $report = New-ValidDatabaseManagerInitFailureReport
    $report.sourcePins.$field = 'f' * 64
    $reportPassed = Test-T21DatabaseManagerInitFailureReport $report ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40)
    Assert-Predicate (-not $reportPassed) "A report source pin mismatch must fail closed: $field"
}

$report = New-ValidDatabaseManagerInitFailureReport
$report.sourcePins.runnerSha256 = [object[]]@('9' * 64)
$reportPassed = Test-T21DatabaseManagerInitFailureReport $report ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40)
Assert-Predicate (-not $reportPassed) 'A one-element array must not impersonate a scalar SHA-256 source pin.'

$report = New-ValidDatabaseManagerInitFailureReport
$report.sourceCommit = 'e' * 40
$report.sourceCommitAfter = 'e' * 40
$report.sourcePins.sourceCommit = 'e' * 40
$reportPassed = Test-T21DatabaseManagerInitFailureReport $report ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40)
Assert-Predicate (-not $reportPassed) 'A report from a different source commit must be rejected.'

$report = New-ValidDatabaseManagerInitFailureReport
$report.artifacts.acceptanceSha256 = 'f' * 64
$reportPassed = Test-T21DatabaseManagerInitFailureReport $report ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40)
Assert-Predicate (-not $reportPassed) 'Top-level acceptance artifact hashes must match the pinned acceptance artifact.'

$report = New-ValidDatabaseManagerInitFailureReport
$report.artifacts.apiConsumerSha256 = 'f' * 64
$reportPassed = Test-T21DatabaseManagerInitFailureReport $report ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40)
Assert-Predicate (-not $reportPassed) 'Top-level API consumer hashes must match the pinned API consumer artifact.'

$report = New-ValidDatabaseManagerInitFailureReport
$report.sqlBackend.version = '17.0'
$reportPassed = Test-T21DatabaseManagerInitFailureReport $report ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40)
Assert-Predicate (-not $reportPassed) 'A different PostgreSQL runtime version must fail closed.'

$report = New-ValidDatabaseManagerInitFailureReport
$report.resourceCleanup.cleanupFailures = 'none'
$reportPassed = Test-T21DatabaseManagerInitFailureReport $report ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40)
Assert-Predicate (-not $reportPassed) 'Malformed report cleanup evidence must fail closed.'

$report = New-ValidDatabaseManagerInitFailureReport
$report.status = 'FAIL'
$reportPassed = Test-T21DatabaseManagerInitFailureReport $report ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40)
Assert-Predicate (-not $reportPassed) 'A failed or unaccepted runtime report must be rejected.'

$platformLogStages = @(
    [pscustomobject]@{ stopStage = 'after-write'; snapshotStage = 'candidate-write' }
    [pscustomobject]@{ stopStage = 'after-restart-read'; snapshotStage = 'candidate-restart-read' }
    [pscustomobject]@{ stopStage = 'after-legacy-seed'; snapshotStage = 'legacy-seed' }
    [pscustomobject]@{ stopStage = 'after-candidate-existing-read'; snapshotStage = 'candidate-existing-read' }
    [pscustomobject]@{ stopStage = 'after-legacy-rollback-read'; snapshotStage = 'legacy-rollback-read' }
    [pscustomobject]@{ stopStage = 'after-startup-failure'; snapshotStage = 'candidate-startup-failure' }
    [pscustomobject]@{ stopStage = 'after-database-manager-init-failure'; snapshotStage = 'candidate-database-manager-init-failure' }
    [pscustomobject]@{ stopStage = 'after-audit-v10-upgrade'; snapshotStage = 'candidate-audit-v10-upgrade' }
)
foreach ($mapping in $platformLogStages) {
    $actualStage = Get-T21PlatformLogSnapshotStageName $mapping.stopStage
    Assert-Predicate ($actualStage -ceq $mapping.snapshotStage) "The server stop stage '$($mapping.stopStage)' must map to snapshot '$($mapping.snapshotStage)'."
}
$unknownStageRejected = $false
try { $null = Get-T21PlatformLogSnapshotStageName 'unrecognized-stop-stage' } catch { $unknownStageRejected = $true }
Assert-Predicate $unknownStageRejected 'An unknown server stop stage must remain rejected.'

Write-Output ("Assertions passed: {0}/{0}" -f $assertions)
