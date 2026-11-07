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
Assert-Predicate ($runnerSource.Contains("'MinimumCanvas', 'MaximumCanvas', 'CurrentMaximumCanvas'")) 'The runtime runner must expose the selected current-maximum Canvas endpoint.'
Assert-Predicate ($runnerSource.Contains('current-maximum Folia, minimum Canvas, or current-maximum Canvas; minimum-Paper MySQL remains blocked')) 'The runtime runner must describe the bounded Canvas targets while preserving the minimum-Paper MySQL blocker.'
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
    '$acceptanceSha256', '$apiConsumerSha256', '$script:sourceCommit', '$PlatformEndpoint', '$script:databaseProduct'
)
Assert-Predicate (($reportCallArguments -join "`n") -ceq ($expectedReportCallArguments -join "`n")) 'The runtime runner must pass all report pins and the selected platform endpoint to the DatabaseManager predicate.'
Assert-Predicate (([regex]::Matches($runnerSource, 'T21-SQL-POOL-INITIAL-SCHEMA-FAILURE-\$script:databaseProduct-\$PlatformEndpoint')).Count -eq 2) 'Both in-progress and final DatabaseManager reports must use the selected SQL product and endpoint identifiers accepted by the report predicate.'
Assert-Predicate ($runnerSource -match '\$DatabaseManagerInitFailureStartupFailure\.IsPresent -and\s+\$script:sourceWorkingTreeDirty') 'The clean-source guard must be limited to the DatabaseManager initialization-failure case.'
Assert-Predicate ($runnerSource -notmatch 'if \(\$script:sourceWorkingTreeDirty\)') 'The clean-source guard must not change behavior for other T21 runner modes.'
$databaseManagerGuardPosition = $runnerSource.IndexOf('if ($DatabaseManagerInitFailureStartupFailure.IsPresent -and')
$runtimeSetupPosition = $runnerSource.IndexOf('$script:root = (Resolve-Path')
Assert-Predicate ($databaseManagerGuardPosition -ge 0 -and $runtimeSetupPosition -gt $databaseManagerGuardPosition) 'A blocked DatabaseManager case must be rejected before runtime artifact, port, or service setup.'
Assert-Predicate ($runnerSource.Contains('The DatabaseManager initial-schema failure case is enabled only for PostgreSQL on minimum Paper, minimum Folia, current-maximum Paper, current-maximum Folia, minimum Canvas, or current-maximum Canvas; minimum-Paper MySQL remains blocked before runtime until database provisioning and table-creation permissions can be separated.')) 'The runner must explain the selected endpoint support and why the MySQL cell is blocked before runtime.'
Assert-Predicate ($runnerSource -match 'The completed report did not satisfy Test-T21DatabaseManagerInitFailureReport\.') 'DatabaseManager report failures must name their own validator.'
foreach ($catalog in @('pg_catalog\.pg_class', 'pg_catalog\.pg_proc', 'pg_catalog\.pg_type')) {
    Assert-Predicate ($runnerSource -match $catalog) "PostgreSQL schema snapshot must count objects from $catalog."
}
Assert-Predicate ($runnerSource -match "'publicObjectCount'") 'PostgreSQL schema snapshot must expose its public object count.'
foreach ($catalog in @('information_schema\.tables', 'information_schema\.routines', 'information_schema\.triggers', 'information_schema\.events')) {
    Assert-Predicate ($runnerSource -match $catalog) "MySQL schema snapshot must count objects from $catalog."
}
Assert-Predicate ($runnerSource -match 'databaseObjectCount') 'MySQL schema snapshot must expose its database object count.'
Assert-Predicate ($runnerSource -match 'GRANT SELECT, INSERT, UPDATE, DELETE ON \$\(\$script:databaseName\)\.\* TO') 'The MySQL initial-schema case must provision only the exact DML grants needed to connect and inspect the empty database.'
Assert-Predicate ($runnerSource -match '(?s)if \(\$script:databaseManagerInitFailureStartupFailure\)\s*\{\s*\$script:finalReport\.runner\s*=\s*\$script:report\.runner\s*\}') 'Only the DatabaseManager report should retain the runner provenance object consumed by its validator and offline auditor.'

$auditPath = Join-Path $PSScriptRoot 'audit-t21-database-manager-init-failure.ps1'
$auditSource = Get-Content -LiteralPath $auditPath -Raw
$auditLogHelperSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 't21-database-manager-init-audit-log.ps1') -Raw
Assert-Predicate ($auditSource -match "\[ValidateSet\('Minimum', 'MinimumFolia', 'CurrentMaximum', 'CurrentMaximumFolia', 'MinimumCanvas', 'CurrentMaximumCanvas'\)\]\[string\]\`$PlatformEndpoint = 'CurrentMaximum'") 'The offline auditor must support the selected PostgreSQL current-maximum Canvas endpoint while keeping CurrentMaximum as its default endpoint.'
Assert-Predicate ($auditSource.Contains('$serverJarPinName = Get-T21DatabaseManagerInitServerJarPinName -PlatformEndpoint $PlatformEndpoint -SourcePins $sourcePins')) 'The offline auditor must use the tested server-JAR pin selector.'
Assert-Predicate ($auditSource.Contains('Test-T21DatabaseManagerInitExpectedPlatformServerPin -PlatformEndpoint $PlatformEndpoint -ExpectedServerSha256 $ExpectedServerSha256')) 'The offline auditor must use the executable formal endpoint server-hash predicate.'
Assert-Predicate ((Get-T21DatabaseManagerInitServerJarPinName 'Minimum' ([pscustomobject]@{paperJarSha256='a'*64})) -ceq 'paperJarSha256') 'A historical Paper report without serverJarSha256 must retain its paperJarSha256 fallback.'
Assert-Predicate ((Get-T21DatabaseManagerInitServerJarPinName 'CurrentMaximum' ([pscustomobject]@{serverJarSha256='a'*64})) -ceq 'serverJarSha256') 'A current Paper report must select serverJarSha256 when present.'
Assert-Predicate ((Get-T21DatabaseManagerInitServerJarPinName 'MinimumFolia' ([pscustomobject]@{paperJarSha256='a'*64})) -ceq 'serverJarSha256') 'Minimum Folia must require the server-neutral JAR pin even if paperJarSha256 is present.'
Assert-Predicate ((Get-T21DatabaseManagerInitServerJarPinName 'CurrentMaximumFolia' ([pscustomobject]@{paperJarSha256='a'*64})) -ceq 'serverJarSha256') 'Current-maximum Folia must require the server-neutral JAR pin even if paperJarSha256 is present.'
Assert-Predicate ((Get-T21DatabaseManagerInitServerJarPinName 'MinimumCanvas' ([pscustomobject]@{paperJarSha256='a'*64})) -ceq 'serverJarSha256') 'Minimum Canvas must require the server-neutral JAR pin even if paperJarSha256 is present.'
Assert-Predicate ((Get-T21DatabaseManagerInitServerJarPinName 'CurrentMaximumCanvas' ([pscustomobject]@{paperJarSha256='a'*64})) -ceq 'serverJarSha256') 'Current-maximum Canvas must require the server-neutral JAR pin even if paperJarSha256 is present.'
Assert-Predicate ($auditSource.Contains('[ValidateSet(''PostgreSQL'', ''MySQL'')][string]$DatabaseProduct = ''PostgreSQL''')) 'The offline auditor must select the expected SQL product explicitly.'
Assert-Predicate ($auditSource -match '(?m)^\$reportPredicate = Test-T21DatabaseManagerInitFailureReport .*\$PlatformEndpoint\s+\$DatabaseProduct\s*$') 'The offline auditor must pass its selected endpoint and SQL product to the report predicate.'
Assert-Predicate ($auditSource -match 'Test-T21DatabaseManagerInitFailureStartupLog .*MySqlAppRole \(\[string\]\(Get-T21ObjectField \$observedPrivileges ''appRole''\)\)') 'The offline audit must bind the exact MySQL denial log line to its retained authenticated account.'
Assert-Predicate ($auditSource -match '(?m)^\$reportPredicate = Test-T21DatabaseManagerInitFailureReport .*\$DatabaseProduct\s*$') 'The offline auditor must pass its selected database product to the report predicate.'
foreach ($log in @('mysql-database-manager-init-authenticated-identity.txt', 'mysql-database-manager-init-grants.txt')) {
    Assert-Predicate ($auditLogHelperSource.Contains($log)) "The offline audit helper must construct the retained MySQL evidence log path: $log"
}
Assert-Predicate ($auditSource.Contains("`$databaseLogPrefix = if (`$DatabaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' }")) 'The offline auditor must select product-specific schema snapshot logs.'
foreach ($stage in @('before', 'after')) {
    Assert-Predicate ($auditSource.Contains("{0}-database-manager-schema-$stage.txt")) "The offline auditor must check the $stage MySQL schema snapshot log."
}
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

function New-ValidMySqlPrivilegeEvidence {
    $appRole = 'sm_t21_89abcdef'
    $targetDatabase = 'syncmoney_t21_89abcdef'
    $account = [char]96 + $appRole + [char]96 + '@' + [char]96 + '127.0.0.1' + [char]96
    $database = [char]96 + $targetDatabase + [char]96
    [pscustomobject]@{
        product = 'MySQL'
        appRole = $appRole
        targetDatabase = $targetDatabase
        authenticatedIdentity = $appRole + '@127.0.0.1' + [char]9 + $targetDatabase
        grantStatements = @(
            "GRANT USAGE ON *.* TO $account"
            "GRANT SELECT, INSERT, UPDATE, DELETE ON $database.* TO $account"
        )
    }
}

function New-ValidMySqlSchemaEvidence {
    [pscustomobject]@{
        before = [pscustomobject]@{ playersTableExists = $false; schemaVersionTableExists = $false; databaseTableCount = 0; databaseObjectCount = 0 }
        after = [pscustomobject]@{ playersTableExists = $false; schemaVersionTableExists = $false; databaseTableCount = 0; databaseObjectCount = 0 }
    }
}

function New-ValidMySqlCleanupEvidence {
    [pscustomobject]@{
        forcedCleanup = $false
        serverStopped = $true
        serverProcessCount = 0
        mysqlStopped = $true
        redisStopped = $true
        runOwnedPortsClosed = $true
        sqlOtherConnections = 0
        redisOtherClients = 0
        redisDbSize = 0
        cleanupFailures = @()
    }
}

function New-ValidDatabaseManagerInitFailureReport(
    [ValidateSet('Minimum', 'MinimumFolia', 'CurrentMaximum', 'CurrentMaximumFolia', 'MinimumCanvas', 'CurrentMaximumCanvas')][string]$PlatformEndpoint = 'CurrentMaximum',
    [ValidateSet('PostgreSQL', 'MySQL')][string]$DatabaseProduct = 'PostgreSQL'
) {
    $endpoint = Get-T21PlatformEndpoint -PlatformEndpoint $PlatformEndpoint
    $server = [pscustomobject]@{
        software = $endpoint.software
        minecraftVersion = $endpoint.minecraftVersion
        build = $endpoint.build
        sha256 = '0' * 64
    }
    $databaseName = 'syncmoney_t21_89abcdef'
    $startupFailureMessage = if ($DatabaseProduct -ceq 'PostgreSQL') {
        'Failed to initialize database schema: ERROR: permission denied for schema public'
    } else {
        "Failed to initialize database schema: CREATE command denied to user 'sm_t21_89abcdef'@'127.0.0.1' for table 'players'"
    }
    $schemaEvidence = if ($DatabaseProduct -ceq 'PostgreSQL') { New-ValidSchemaEvidence } else { New-ValidMySqlSchemaEvidence }
    $cleanupEvidence = if ($DatabaseProduct -ceq 'PostgreSQL') { New-ValidCleanupEvidence } else { New-ValidMySqlCleanupEvidence }
    [pscustomobject]@{
        task = "T21-SQL-POOL-INITIAL-SCHEMA-FAILURE-$DatabaseProduct-$PlatformEndpoint"
        status = 'PASS_BOUNDED_SUBCASE'
        sourceCommit = 'd' * 40
        sourceCommitAfter = 'd' * 40
        sourceWorkingTreeDirty = $false
        sourceWorkingTreeDirtyAfter = $false
        failure = $null
        server = $server
        candidate = [pscustomobject]@{ version = '1.4.0-alpha.1'; sha256 = '1' * 64 }
        artifacts = [pscustomobject]@{ acceptanceSha256 = '4' * 64; apiConsumerSha256 = '5' * 64 }
        sqlBackend = if ($DatabaseProduct -ceq 'PostgreSQL') {
            [pscustomobject]@{ product = 'PostgreSQL'; version = '18.1' }
        } else {
            [pscustomobject]@{ product = 'MySQL'; version = '8.0.42' }
        }
        redis = [pscustomobject]@{ version = '8.10.1' }
        configuration = [pscustomobject]@{
            mode = 'sync'; databaseType = $(if ($DatabaseProduct -ceq 'PostgreSQL') { 'postgresql' } else { 'mysql' })
            databaseName = $databaseName; databaseEnabled = $true; databaseIsolated = $true
            redisEnabled = $true; redisIsolated = $true; redisDatabase = 15
        }
        startupObservation = [pscustomobject]@{
            stage = 'candidate-startup-failure'; pluginVersion = '1.4.0-alpha.1'
            expectedFailure = 'database-manager-initial-schema-failure'
            failureMessage = $startupFailureMessage
            enabled = $false; failureObserved = $true; rollbackComplete = $true; sqlPoolStartObserved = $true
            sqlPoolShutdownObserved = $true; disabledMarker = $true; successMarkerObserved = $false
        }
        databaseDdlPrivileges = if ($DatabaseProduct -ceq 'PostgreSQL') { New-ValidPrivilegeEvidence } else { New-ValidMySqlPrivilegeEvidence }
        schemaStateBefore = $schemaEvidence.before
        schemaStateAfter = $schemaEvidence.after
        resourceCleanup = $cleanupEvidence
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
            serverJarSha256 = '0' * 64; candidateJarSha256 = '1' * 64
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

$validMySqlLog = @'
[Syncmoney] Loading server plugin Syncmoney v1.4.0-alpha.1
[noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Start completed.
[Syncmoney] Failed to initialize database schema: CREATE command denied to user 'sm_t21_89abcdef'@'127.0.0.1' for table 'players'
[noietime.libs.hikari.HikariDataSource] Syncmoney-HikariCP - Shutdown completed.
[Syncmoney] Syncmoney initialization failed; rolling back initialized owners
[Server thread/INFO]: Disabling Syncmoney v1.4.0-alpha.1
'@
$result = Test-T21DatabaseManagerInitFailureStartupLog -LogText $validMySqlLog -CandidateVersion '1.4.0-alpha.1' -DatabaseProduct MySQL -MySqlAppRole 'sm_t21_89abcdef'
Assert-Predicate $result.passed 'The ordered MySQL CREATE-denial startup log should pass.'
$wrongMySqlFailure = $validMySqlLog.Replace('CREATE command denied to user', 'Access denied for user')
$result = Test-T21DatabaseManagerInitFailureStartupLog -LogText $wrongMySqlFailure -CandidateVersion '1.4.0-alpha.1' -DatabaseProduct MySQL -MySqlAppRole 'sm_t21_89abcdef'
Assert-Predicate (-not $result.passed) 'A MySQL connection failure must not impersonate a schema CREATE denial.'
$wrongMySqlAccountLog = $validMySqlLog.Replace("user 'sm_t21_89abcdef'@'127.0.0.1'", "user 'sm_t21_01234567'@'127.0.0.1'")
$result = Test-T21DatabaseManagerInitFailureStartupLog -LogText $wrongMySqlAccountLog -CandidateVersion '1.4.0-alpha.1' -DatabaseProduct MySQL -MySqlAppRole 'sm_t21_89abcdef'
Assert-Predicate (-not $result.passed) 'A MySQL denial for a different account must fail the retained-log predicate.'
$wrongMySqlTableLog = $validMySqlLog.Replace("for table 'players'", "for table 'other'")
$result = Test-T21DatabaseManagerInitFailureStartupLog -LogText $wrongMySqlTableLog -CandidateVersion '1.4.0-alpha.1' -DatabaseProduct MySQL -MySqlAppRole 'sm_t21_89abcdef'
Assert-Predicate (-not $result.passed) 'A MySQL denial for a different table must fail the retained-log predicate.'
$result = Test-T21DatabaseManagerInitFailureStartupLog -LogText $validMySqlLog -CandidateVersion '1.4.0-alpha.1' -DatabaseProduct MySQL
Assert-Predicate (-not $result.passed) 'A MySQL retained-log audit must require its pinned application account.'

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

$mySqlPrivileges = New-ValidMySqlPrivilegeEvidence
Assert-Predicate ((Test-T21DatabaseManagerInitFailureMySqlPrivileges $mySqlPrivileges).passed) 'The isolated MySQL account with only target-database DML privileges should pass.'
$mySqlPrivileges = New-ValidMySqlPrivilegeEvidence
$mySqlPrivileges.grantStatements[1] = $mySqlPrivileges.grantStatements[1].Replace('SELECT, INSERT, UPDATE, DELETE', 'SELECT, INSERT, UPDATE, DELETE, CREATE')
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureMySqlPrivileges $mySqlPrivileges).passed) 'A MySQL CREATE privilege on the isolated database must be rejected.'
$mySqlPrivileges = New-ValidMySqlPrivilegeEvidence
$mySqlPrivileges.grantStatements += 'GRANT CREATE ON *.* TO sm_t21_89abcdef@127.0.0.1'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureMySqlPrivileges $mySqlPrivileges).passed) 'An extra global MySQL grant must be rejected.'
$mySqlPrivileges = New-ValidMySqlPrivilegeEvidence
$mySqlPrivileges.authenticatedIdentity = 'sm_t21_89abcdef@localhost' + [char]9 + 'syncmoney_t21_89abcdef'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureMySqlPrivileges $mySqlPrivileges).passed) 'A MySQL connection authenticated as a different host account must be rejected.'
$mySqlPrivileges = New-ValidMySqlPrivilegeEvidence
$mySqlPrivileges.grantStatements = 'GRANT USAGE ON *.*'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureMySqlPrivileges $mySqlPrivileges).passed) 'Scalar MySQL grant evidence must fail closed.'

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

$mySqlSchema = New-ValidMySqlSchemaEvidence
Assert-Predicate ((Test-T21DatabaseManagerInitFailureSchemaState $mySqlSchema MySQL).passed) 'An unchanged empty MySQL database schema should pass.'
$mySqlSchema = New-ValidMySqlSchemaEvidence
$mySqlSchema.after.databaseObjectCount = 1
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureSchemaState $mySqlSchema MySQL).passed) 'A MySQL routine, trigger, event, view, or other schema object must fail the empty-schema assertion.'
$mySqlSchema = New-ValidMySqlSchemaEvidence
$mySqlSchema.before.playersTableExists = $true
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureSchemaState $mySqlSchema MySQL).passed) 'A pre-existing MySQL players table must be rejected.'
$mySqlSchema = New-ValidMySqlSchemaEvidence
$mySqlSchema.after.databaseTableCount = '0'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureSchemaState $mySqlSchema MySQL).passed) 'String-shaped MySQL schema counts must fail closed.'

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

$mySqlCleanup = New-ValidMySqlCleanupEvidence
Assert-Predicate ((Test-T21DatabaseManagerInitFailureCleanup $mySqlCleanup MySQL).passed) 'Graceful MySQL cleanup with zero residual resources should pass.'
$mySqlCleanup = New-ValidMySqlCleanupEvidence
$mySqlCleanup.mysqlStopped = $false
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureCleanup $mySqlCleanup MySQL).passed) 'A MySQL backend that did not stop must fail closed.'

Assert-Predicate (Test-T21DatabaseManagerInitFailureTarget 'PostgreSQL' 'CurrentMaximum') 'The pinned PostgreSQL current-maximum cell should remain enabled.'
Assert-Predicate (Test-T21DatabaseManagerInitFailureTarget 'PostgreSQL' 'Minimum') 'The pinned PostgreSQL minimum Paper cell should be enabled.'
Assert-Predicate (Test-T21DatabaseManagerInitFailureTarget 'PostgreSQL' 'MinimumFolia') 'The pinned PostgreSQL minimum-Folia cell should be enabled.'
Assert-Predicate (Test-T21DatabaseManagerInitFailureTarget 'PostgreSQL' 'CurrentMaximumFolia') 'The pinned PostgreSQL current-maximum Folia cell should be enabled.'
Assert-Predicate (Test-T21DatabaseManagerInitFailureTarget 'PostgreSQL' 'MinimumCanvas') 'The pinned PostgreSQL minimum-Canvas cell should be enabled.'
Assert-Predicate (Test-T21DatabaseManagerInitFailureTarget 'PostgreSQL' 'CurrentMaximumCanvas') 'The pinned PostgreSQL current-maximum Canvas cell should be enabled.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureTarget 'MySQL' 'Minimum')) 'MySQL minimum-Paper must remain disabled while CREATE DATABASE runs before the main pool and cannot be separated from CREATE TABLE denial.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureTarget 'PostgreSQL' 'Maximum')) 'The historical Paper maximum must not be accepted for this case.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureTarget 'PostgreSQL' 'MaximumFolia')) 'An unselected Folia endpoint must remain rejected.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureTarget 'MySQL' 'CurrentMaximum')) 'A different SQL product must not be accepted for this cell.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureTarget 'MariaDB' 'Minimum')) 'The unselected MariaDB product must remain rejected for this bounded cell.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureTarget 'MySQL' 'MinimumFolia')) 'The unselected Folia platform must remain rejected for this bounded cell.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureTarget 'MySQL' 'MinimumCanvas')) 'The minimum-Canvas MySQL startup-failure cell must remain rejected.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureTarget 'MariaDB' 'MinimumCanvas')) 'The minimum-Canvas MariaDB startup-failure cell must remain rejected.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureTarget 'MySQL' 'CurrentMaximumCanvas')) 'The current-maximum Canvas MySQL startup-failure cell must remain rejected.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureTarget 'MariaDB' 'CurrentMaximumCanvas')) 'The current-maximum Canvas MariaDB startup-failure cell must remain rejected.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitFailureTarget 'PostgreSQL' 'MaximumCanvas')) 'The unselected historical Canvas endpoint must remain rejected.'

$currentMaximumCanvasEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint CurrentMaximumCanvas
Assert-Predicate ($currentMaximumCanvasEndpoint.software -ceq 'canvas' -and $currentMaximumCanvasEndpoint.minecraftVersion -ceq '26.3' -and $currentMaximumCanvasEndpoint.build -eq 1010 -and $currentMaximumCanvasEndpoint.sha256 -ceq 'e2572e9bd101cb657e0d16dea2dc8a622208aaa8bd39afdc6d96bad4f4fee057') 'The selected current-maximum Canvas endpoint must retain its formal version, build, and SHA-256 pin.'
Assert-Predicate (Test-T21DatabaseManagerInitExpectedPlatformServerPin -PlatformEndpoint CurrentMaximumCanvas -ExpectedServerSha256 'e2572e9bd101cb657e0d16dea2dc8a622208aaa8bd39afdc6d96bad4f4fee057') 'The current-maximum Canvas auditor pin helper must accept the formal SHA-256.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitExpectedPlatformServerPin -PlatformEndpoint CurrentMaximumCanvas -ExpectedServerSha256 ('0' * 64))) 'The current-maximum Canvas auditor pin helper must reject a mismatched expected SHA-256.'
$currentMaximumCanvasReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint CurrentMaximumCanvas
$currentMaximumCanvasReportPassed = Test-T21DatabaseManagerInitFailureReport $currentMaximumCanvasReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'CurrentMaximumCanvas'
Assert-Predicate $currentMaximumCanvasReportPassed 'The exactly pinned current-maximum Canvas/PostgreSQL startup-failure report should pass.'
$currentMaximumCanvasReport.server.software = 'paper'
$currentMaximumCanvasReportPassed = Test-T21DatabaseManagerInitFailureReport $currentMaximumCanvasReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'CurrentMaximumCanvas'
Assert-Predicate (-not $currentMaximumCanvasReportPassed) 'A current-maximum Canvas report identifying itself as Paper must be rejected.'
$currentMaximumCanvasReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint CurrentMaximumCanvas
$currentMaximumCanvasReport.server.build = 985
$currentMaximumCanvasReportPassed = Test-T21DatabaseManagerInitFailureReport $currentMaximumCanvasReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'CurrentMaximumCanvas'
Assert-Predicate (-not $currentMaximumCanvasReportPassed) 'A current-maximum Canvas report with the wrong build must be rejected.'
$currentMaximumCanvasReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint CurrentMaximumCanvas
$currentMaximumCanvasReport.server.minecraftVersion = '26.2'
$currentMaximumCanvasReportPassed = Test-T21DatabaseManagerInitFailureReport $currentMaximumCanvasReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'CurrentMaximumCanvas'
Assert-Predicate (-not $currentMaximumCanvasReportPassed) 'A current-maximum Canvas report with the wrong Minecraft version must be rejected.'
$currentMaximumCanvasReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint CurrentMaximumCanvas
$currentMaximumCanvasReport.server.sha256 = 'f' * 64
$currentMaximumCanvasReportPassed = Test-T21DatabaseManagerInitFailureReport $currentMaximumCanvasReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'CurrentMaximumCanvas'
Assert-Predicate (-not $currentMaximumCanvasReportPassed) 'A current-maximum Canvas report with the wrong pinned server SHA-256 must be rejected.'

$minimumCanvasReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint MinimumCanvas
$minimumCanvasReportPassed = Test-T21DatabaseManagerInitFailureReport $minimumCanvasReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'MinimumCanvas'
Assert-Predicate $minimumCanvasReportPassed 'The exactly pinned minimum-Canvas/PostgreSQL startup-failure report should pass.'
$minimumCanvasEndpoint = Get-T21PlatformEndpoint -PlatformEndpoint MinimumCanvas
Assert-Predicate ($minimumCanvasEndpoint.software -ceq 'canvas' -and $minimumCanvasEndpoint.minecraftVersion -ceq '26.2' -and $minimumCanvasEndpoint.build -eq 994 -and $minimumCanvasEndpoint.sha256 -ceq '8c4e36c0545a4e8d3332c6c0d6dce922001fd986ff47deec4b93fd26ba0de2b2') 'The selected minimum-Canvas endpoint must retain its formal version, build, and SHA-256 pin.'
Assert-Predicate (Test-T21DatabaseManagerInitExpectedPlatformServerPin -PlatformEndpoint MinimumCanvas -ExpectedServerSha256 '8c4e36c0545a4e8d3332c6c0d6dce922001fd986ff47deec4b93fd26ba0de2b2') 'The minimum-Canvas auditor pin helper must accept the formal SHA-256.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitExpectedPlatformServerPin -PlatformEndpoint MinimumCanvas -ExpectedServerSha256 ('0' * 64))) 'The minimum-Canvas auditor pin helper must reject a mismatched expected SHA-256.'
Assert-Predicate (-not (Test-T21DatabaseManagerInitExpectedPlatformServerPin -PlatformEndpoint MinimumCanvas -ExpectedServerSha256 'not-a-hash')) 'The minimum-Canvas auditor pin helper must reject a malformed expected SHA-256.'
$minimumCanvasReport.server.software = 'paper'
$minimumCanvasReportPassed = Test-T21DatabaseManagerInitFailureReport $minimumCanvasReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'MinimumCanvas'
Assert-Predicate (-not $minimumCanvasReportPassed) 'A minimum-Canvas report identifying itself as Paper must be rejected.'
$minimumCanvasReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint MinimumCanvas
$minimumCanvasReport.server.build = 604
$minimumCanvasReportPassed = Test-T21DatabaseManagerInitFailureReport $minimumCanvasReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'MinimumCanvas'
Assert-Predicate (-not $minimumCanvasReportPassed) 'A minimum-Canvas report with the wrong build must be rejected.'
$minimumCanvasReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint MinimumCanvas
$minimumCanvasReport.server.minecraftVersion = '1.21.9'
$minimumCanvasReportPassed = Test-T21DatabaseManagerInitFailureReport $minimumCanvasReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'MinimumCanvas'
Assert-Predicate (-not $minimumCanvasReportPassed) 'A minimum-Canvas report with the wrong Minecraft version must be rejected.'
$minimumCanvasReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint MinimumCanvas
$minimumCanvasReport.server.sha256 = 'f' * 64
$minimumCanvasReportPassed = Test-T21DatabaseManagerInitFailureReport $minimumCanvasReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'MinimumCanvas'
Assert-Predicate (-not $minimumCanvasReportPassed) 'A minimum-Canvas report with the wrong pinned server SHA-256 must be rejected.'
$minimumCanvasReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint MinimumCanvas
$minimumCanvasReport.sourcePins | Add-Member -MemberType NoteProperty -Name paperJarSha256 -Value ('0' * 64)
$null = $minimumCanvasReport.sourcePins.PSObject.Properties.Remove('serverJarSha256')
$minimumCanvasReportPassed = Test-T21DatabaseManagerInitFailureReport $minimumCanvasReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'MinimumCanvas'
Assert-Predicate (-not $minimumCanvasReportPassed) 'A minimum-Canvas report must not fall back to a Paper-only source pin.'
$minimumCanvasMySqlReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint MinimumCanvas -DatabaseProduct MySQL
$minimumCanvasMySqlReportPassed = Test-T21DatabaseManagerInitFailureReport $minimumCanvasMySqlReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'MinimumCanvas' -ExpectedPlatformEndpoint MinimumCanvas -ExpectedDatabaseProduct MySQL
Assert-Predicate (-not $minimumCanvasMySqlReportPassed) 'A minimum-Canvas MySQL report must remain unaccepted for this PostgreSQL-only cell.'

$minimumFoliaReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint MinimumFolia
$minimumFoliaReportPassed = Test-T21DatabaseManagerInitFailureReport $minimumFoliaReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'MinimumFolia'
Assert-Predicate $minimumFoliaReportPassed 'The exactly pinned minimum-Folia/PostgreSQL startup-failure report should pass.'
$minimumFoliaReport.server.software = 'paper'
$minimumFoliaReportPassed = Test-T21DatabaseManagerInitFailureReport $minimumFoliaReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'MinimumFolia'
Assert-Predicate (-not $minimumFoliaReportPassed) 'A minimum-Folia report identifying itself as Paper must be rejected.'

$currentMaximumFoliaReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint CurrentMaximumFolia
$currentMaximumFoliaReportPassed = Test-T21DatabaseManagerInitFailureReport $currentMaximumFoliaReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'CurrentMaximumFolia'
Assert-Predicate $currentMaximumFoliaReportPassed 'The exactly pinned current-maximum Folia/PostgreSQL startup-failure report should pass.'
$currentMaximumFoliaReport.server.software = 'paper'
$currentMaximumFoliaReportPassed = Test-T21DatabaseManagerInitFailureReport $currentMaximumFoliaReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'CurrentMaximumFolia'
Assert-Predicate (-not $currentMaximumFoliaReportPassed) 'A current-maximum Folia report identifying itself as Paper must be rejected.'
$currentMaximumFoliaReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint CurrentMaximumFolia
$currentMaximumFoliaReport.server.build = 2
$currentMaximumFoliaReportPassed = Test-T21DatabaseManagerInitFailureReport $currentMaximumFoliaReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'CurrentMaximumFolia'
Assert-Predicate (-not $currentMaximumFoliaReportPassed) 'A current-maximum Folia report with the wrong build must be rejected.'
$currentMaximumFoliaReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint CurrentMaximumFolia
$currentMaximumFoliaReport.server.sha256 = 'f' * 64
$currentMaximumFoliaReportPassed = Test-T21DatabaseManagerInitFailureReport $currentMaximumFoliaReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'CurrentMaximumFolia'
Assert-Predicate (-not $currentMaximumFoliaReportPassed) 'A current-maximum Folia report with the wrong pinned server SHA-256 must be rejected.'

$legacyMinimumReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint Minimum
$legacyPaperJarSha256 = $legacyMinimumReport.sourcePins.serverJarSha256
$legacyMinimumReport.sourcePins | Add-Member -MemberType NoteProperty -Name paperJarSha256 -Value $legacyPaperJarSha256
$null = $legacyMinimumReport.sourcePins.PSObject.Properties.Remove('serverJarSha256')
$legacyMinimumReportPassed = Test-T21DatabaseManagerInitFailureReport $legacyMinimumReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) 'Minimum'
Assert-Predicate $legacyMinimumReportPassed 'A previously accepted minimum-Paper report using paperJarSha256 should remain auditable.'

$report = New-ValidDatabaseManagerInitFailureReport
$reportPassed = Test-T21DatabaseManagerInitFailureReport $report ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40)
Assert-Predicate $reportPassed 'The pinned Paper/PostgreSQL startup-failure report should pass.'

$mySqlReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint Minimum -DatabaseProduct MySQL
$mySqlReportPassed = Test-T21DatabaseManagerInitFailureReport $mySqlReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) -ExpectedPlatformEndpoint Minimum -ExpectedDatabaseProduct MySQL
Assert-Predicate (-not $mySqlReportPassed) 'A MySQL report must remain unaccepted while the minimum-Paper case is blocked before runtime.'

$mySqlMaximumReport = New-ValidDatabaseManagerInitFailureReport -PlatformEndpoint CurrentMaximum -DatabaseProduct MySQL
$mySqlMaximumReportPassed = Test-T21DatabaseManagerInitFailureReport $mySqlMaximumReport ('0' * 64) ('1' * 64) ('a' * 40) ('b' * 64) ('c' * 64) ('4' * 64) ('5' * 64) ('d' * 40) -ExpectedPlatformEndpoint CurrentMaximum -ExpectedDatabaseProduct MySQL
Assert-Predicate (-not $mySqlMaximumReportPassed) 'A MySQL CurrentMaximum report must be rejected by the minimum-Paper-only case predicate.'

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
Assert-Predicate (-not $reportPassed) 'A string-shaped server build number must be rejected.'

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
