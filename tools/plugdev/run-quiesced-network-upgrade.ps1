[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('paper','folia','canvas')][string]$Project,
    [Parameter(Mandatory = $true)][ValidatePattern('^\d+\.\d+(?:\.\d+)?$')][string]$MinecraftVersion,
    [Parameter(Mandatory = $true)][ValidateRange(1, 1000000)][int]$BuildNumber,
    [Parameter(Mandatory = $true)][string]$ServerJar,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedServerSha256,
    [Parameter(Mandatory = $true)][string]$BaselinePluginJar,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedBaselinePluginSha256,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$BaselineSourceCommit,
    [Parameter(Mandatory = $true)][string]$PlugDevRoot,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\18\bin',
    [string]$CandidateVersion = '1.4.0-alpha.1',
    [switch]$EnableAudit
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'T21 quiesced upgrade requires PowerShell 7 or newer.' }
. (Join-Path $PSScriptRoot 'run-sync-upgrade-cleanup.ps1')
. (Join-Path $PSScriptRoot 'recovery-spool-validator.ps1')
. (Join-Path $PSScriptRoot 't21-upgrade-validation.ps1')

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$baselineRoot = [IO.Path]::GetFullPath((Join-Path $root '..\..\syncmoney-132-baseline\Syncmoney'))
$nodeExe = (Get-Command node -ErrorAction Stop).Source
$plugDevRoot = [IO.Path]::GetFullPath($PlugDevRoot)
$plugDevCli = Join-Path $plugDevRoot 'packages\cli\dist\cli.js'
$javaExe = Join-Path ([IO.Path]::GetFullPath($JavaHome)) 'bin\java.exe'
$serverPath = [IO.Path]::GetFullPath($ServerJar)
$baselinePath = [IO.Path]::GetFullPath($BaselinePluginJar)
$candidatePath = Join-Path $root "build\libs\Syncmoney-$CandidateVersion.jar"
$acceptancePath = Join-Path $root 'build\acceptance\SyncmoneyAcceptance.jar'
$apiConsumerPath = Join-Path $root 'build\acceptance\SyncmoneyApiConsumer.jar'
$recoveryValidatorPath = Join-Path $PSScriptRoot 'recovery-spool-validator.ps1'
$script:originalEnvironment = [ordered]@{}
$script:auditEnabled = $EnableAudit.IsPresent
$environmentNames = @(
    'JAVA_HOME','Path','PLUGDEV_ROOT','PLUGDEV_HOME','GRADLE_USER_HOME','SYNCMONEY_TEST_SERVER_JAR',
    'SYNCMONEY_CANDIDATE_VERSION','SYNCMONEY_TEST_GAME_PORT','SYNCMONEY_TEST_DB_TYPE',
    'SYNCMONEY_TEST_DB_PORT','SYNCMONEY_TEST_DB_USER','SYNCMONEY_TEST_DB_PASSWORD',
    'SYNCMONEY_TEST_DB_NAME','SYNCMONEY_TEST_REDIS_PORT','T21_RUN_MARKER','T21_NODE_ROOT',
    'T21_NODE_VERSION','T21_NODE_PORT','T21_NODE_MARKER','T21_NODE_SERVER_NAME','T21_REDIS_PORT','T21_AUDIT_ENABLED',
    'PGSSLMODE'
)
foreach ($name in $environmentNames) { $script:originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

function Assert-NoReparsePointAncestors([string]$path) {
    $current = [IO.Path]::GetFullPath($path)
    while (-not [string]::IsNullOrWhiteSpace($current)) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Refusing a reparse-point path ancestor: $current" }
        }
        $parent = Split-Path -Parent $current
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $current) { break }
        $current = $parent
    }
}

function Get-OfflineUuid([string]$name) {
    $bytes = [Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes('OfflinePlayer:' + $name))
    $bytes[6] = [byte](($bytes[6] -band 15) -bor 48)
    $bytes[8] = [byte](($bytes[8] -band 63) -bor 128)
    $hex = [Convert]::ToHexString($bytes).ToLowerInvariant()
    return ($hex.Substring(0,8)+'-'+$hex.Substring(8,4)+'-'+$hex.Substring(12,4)+'-'+$hex.Substring(16,4)+'-'+$hex.Substring(20,12))
}

function Test-FreeLoopbackPort([int]$port) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $listener.Start(); return $true } catch { return $false } finally { $listener.Stop() }
}

function Get-FreePort([int]$minimum, [int]$maximum, [int[]]$reserved = @(), [int]$derivedOffset = 0) {
    for ($attempt=0; $attempt -lt 200; $attempt++) {
        $port = Get-Random -Minimum $minimum -Maximum ($maximum + 1)
        if ($port -in $reserved -or -not (Test-FreeLoopbackPort $port)) { continue }
        if ($derivedOffset -gt 0 -and (($port + $derivedOffset) -in $reserved -or -not (Test-FreeLoopbackPort ($port + $derivedOffset)))) { continue }
        return $port
    }
    throw "Could not reserve a free loopback port in $minimum-$maximum."
}

function Invoke-Logged([string]$exe, [string[]]$arguments, [string]$log, [string]$label) {
    $output = @(& $exe @arguments 2>&1)
    $exit = $LASTEXITCODE
    $output | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath $log -Encoding utf8
    if ($exit -ne 0) { throw "$label exited $exit; see $log." }
}

function Invoke-PgCtl([string[]]$arguments, [string]$label, [string]$logBase) {
    $stdout = Join-Path $script:logsRoot ($logBase + '.stdout.txt')
    $stderr = Join-Path $script:logsRoot ($logBase + '.stderr.txt')
    $line = ($arguments | ForEach-Object { '"' + ([string]$_).Replace('"','\"') + '"' }) -join ' '
    $process = Start-Process -FilePath $script:pgCtl -ArgumentList $line -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    if (-not $process.WaitForExit(45000)) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue; throw "$label timed out; see $stdout." }
    $process.Refresh()
    if ($process.ExitCode -ne 0) { throw "$label exited $($process.ExitCode); see $stdout and $stderr." }
}

function Invoke-Psql([string]$sql) {
    $args = @('--no-password','--no-psqlrc','--host=127.0.0.1',"--port=$script:pgPort", "--username=$script:pgUser", "--dbname=$script:dbName", '--no-align','--tuples-only','--field-separator=|','--set=ON_ERROR_STOP=1',"--command=$sql")
    $output = @(& $script:psql @args 2>&1)
    $exit = $LASTEXITCODE
    if ($exit -ne 0) {
        $script:psqlFailureSequence++
        $failureLog = Join-Path $script:logsRoot ('postgres-query-{0:D2}.txt' -f $script:psqlFailureSequence)
        $output | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath $failureLog -Encoding utf8
        throw "Isolated PostgreSQL query failed with exit $exit; inspect $failureLog."
    }
    return @($output | ForEach-Object { $_.ToString().TrimEnd() } | Where-Object { $_ -ne '' })
}

function ConvertTo-CygwinPath([string]$path) {
    $full = [IO.Path]::GetFullPath($path)
    if ($full -notmatch '^([A-Za-z]):\\(.*)$') { throw "Expected an absolute drive path for Redis: $full" }
    return '/cygdrive/' + $Matches[1].ToLowerInvariant() + '/' + $Matches[2].Replace('\','/')
}

function Resolve-RedisServerExecutable([string]$commandPath) {
    if ([string]::IsNullOrWhiteSpace($commandPath) -or -not (Test-Path -LiteralPath $commandPath -PathType Leaf)) {
        throw 'The configured Redis server executable is missing.'
    }
    $resolvedCommand = (Resolve-Path -LiteralPath $commandPath).Path
    if ($resolvedCommand -notmatch '(?i)\\scoop\\shims\\redis-server(?:\.exe)?$') { return $resolvedCommand }

    $scoop = Get-Command scoop -ErrorAction Stop
    $output = @(& $scoop.Source which redis-server 2>&1)
    $exit = $LASTEXITCODE
    $paths = @($output | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ -ne '' })
    if ($exit -ne 0 -or $paths.Count -ne 1) { throw 'Could not resolve Scoop Redis server shim to one executable.' }
    $resolvedServer = (Resolve-Path -LiteralPath $paths[0] -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $resolvedServer -PathType Leaf) -or
        $resolvedServer -match '(?i)\\scoop\\shims\\redis-server(?:\.exe)?$') {
        throw 'Scoop Redis server resolution did not return a real executable.'
    }
    return $resolvedServer
}

function Invoke-Redis([string[]]$arguments) {
    $output = @(& $script:redisCli -h 127.0.0.1 -p $script:redisPort @arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'Isolated Redis command failed; inspect the private run logs.' }
    return @($output | ForEach-Object { $_.ToString().TrimEnd() })
}

function Get-BackendSnapshot([string]$stage) {
    $uuidA = $script:accounts.AcceptanceA; $uuidB = $script:accounts.AcceptanceB
    $rows = @(Invoke-Psql "SELECT player_name || '|' || player_uuid || '|' || balance::text || '|' || version::text FROM players WHERE player_uuid IN ('$uuidA','$uuidB') ORDER BY player_name;")
    if ($rows.Count -ne 2) { throw "Expected two synthetic SQL rows after $stage; observed $($rows.Count)." }
    $accounts = @()
    foreach ($row in $rows) {
        $p = $row.Split('|')
        if ($p.Count -ne 4 -or $p[0] -notin @('AcceptanceA','AcceptanceB')) { throw "Unexpected SQL row after $stage." }
        $name = $p[0]; $uuid = $p[1].ToLowerInvariant()
        if ($uuid -ne $script:accounts[$name]) { throw "UUID mismatch for $name after $stage." }
        $balance = ([decimal]::Parse($p[2],[Globalization.CultureInfo]::InvariantCulture)).ToString('0.00',[Globalization.CultureInfo]::InvariantCulture)
        $version = [long]$p[3]
        $rBalance = ((Invoke-Redis @('-n','15','GET',"syncmoney:balance:$uuid")) -join '').Trim()
        $rVersion = ((Invoke-Redis @('-n','15','GET',"syncmoney:version:$uuid")) -join '').Trim()
        if ($rBalance -eq '' -or $rVersion -eq '' -or ([decimal]::Parse($rBalance,[Globalization.CultureInfo]::InvariantCulture)).ToString('0.00',[Globalization.CultureInfo]::InvariantCulture) -ne $balance -or [long]$rVersion -ne $version) {
            throw "Redis and PostgreSQL disagree for $name after $stage."
        }
        $accounts += [ordered]@{name=$name;uuid=$uuid;balance=$balance;version=$version;redisBalance=$balance;redisVersion=$version}
    }
    $count = @(Invoke-Psql 'SELECT COUNT(*) FROM players;')
    if ($count.Count -ne 1 -or [int]$count[0] -ne 2) { throw "Unexpected player row count after $stage." }
    return [ordered]@{stage=$stage;accounts=$accounts;sqlPlayerRowCount=2;redisDb15KeyCount=[int]((Invoke-Redis @('-n','15','DBSIZE')) -join '');schemaVersionRows=@(Invoke-Psql 'SELECT id || ''|'' || version::text FROM syncmoney_schema_version ORDER BY id;')}
}

function Assert-Balances([object]$snapshot,[string]$a,[string]$b,[string]$stage) {
    if ($snapshot.accounts.Count -ne 2 -or $snapshot.accounts[0].name -ne 'AcceptanceA' -or $snapshot.accounts[0].balance -ne $a -or $snapshot.accounts[1].name -ne 'AcceptanceB' -or $snapshot.accounts[1].balance -ne $b -or $snapshot.sqlPlayerRowCount -ne 2) { throw "Balance/backend assertion failed after $stage." }
}

function Assert-BackendVersions([object]$snapshot,[long]$versionA,[long]$versionB,[string]$stage) {
    $expected = @([ordered]@{name='AcceptanceA';version=$versionA},[ordered]@{name='AcceptanceB';version=$versionB})
    foreach ($item in $expected) {
        $account = @($snapshot.accounts | Where-Object { $_.name -ceq $item.name })
        if ($account.Count -ne 1 -or [long]$account[0].version -ne $item.version -or [long]$account[0].redisVersion -ne $item.version) {
            throw "SQL/Redis version assertion failed for $($item.name) after $stage; expected $($item.version)."
        }
    }
}

function Get-AuditSnapshot([string]$stage) {
    if (-not $script:auditEnabled) { return $null }
    $uuidA=$script:accounts.AcceptanceA; $uuidB=$script:accounts.AcceptanceB
    $sql="SELECT expected.player_uuid || '|' || COALESCE(actual.record_count,0)::text FROM (VALUES ('$uuidA'),('$uuidB')) AS expected(player_uuid) LEFT JOIN (SELECT player_uuid, COUNT(*) AS record_count FROM syncmoney_audit_log WHERE player_uuid IN ('$uuidA','$uuidB') GROUP BY player_uuid) actual USING(player_uuid) ORDER BY expected.player_uuid;"
    $rows=@(Invoke-Psql $sql)
    if($rows.Count -ne 2){throw "Expected audit counts for both disposable accounts after $stage; observed $($rows.Count)."}
    $accounts=@()
    foreach($row in $rows){
        $parts=$row.Split('|')
        if($parts.Count -ne 2 -or $parts[0] -notin @($uuidA,$uuidB)){throw "Unexpected audit count row after $stage."}
        $accounts+=[ordered]@{uuid=$parts[0].ToLowerInvariant();count=[long]$parts[1]}
    }
    $snapshot=[ordered]@{stage=$stage;accounts=$accounts}
    $script:auditSnapshots.Add($snapshot)
    return $snapshot
}

function Assert-AuditSnapshotAdvanced([object]$baseline,[object]$current) {
    if(-not $script:auditEnabled){return}
    if($null -eq $baseline -or $null -eq $current){throw 'Enabled-audit validation is missing a baseline or candidate snapshot.'}
    foreach($baselineAccount in $baseline.accounts){
        $match=@($current.accounts | Where-Object {$_.uuid -ceq $baselineAccount.uuid})
        if($match.Count -ne 1){throw "Missing audit count for disposable account $($baselineAccount.uuid)."}
        $baselineCount=[long]$baselineAccount.count
        $currentCount=[long]$match[0].count
        if($currentCount -le $baselineCount){throw "Candidate shutdown did not persist an audit row for disposable account $($baselineAccount.uuid)."}
    }
}

function Assert-AuditSnapshotRetained([object]$expected,[object]$current) {
    if(-not $script:auditEnabled){return}
    if($null -eq $expected -or $null -eq $current){throw 'Enabled-audit validation is missing a candidate or rollback snapshot.'}
    foreach($expectedAccount in $expected.accounts){
        $match=@($current.accounts | Where-Object {$_.uuid -ceq $expectedAccount.uuid})
        if($match.Count -ne 1){throw "Missing rollback audit count for disposable account $($expectedAccount.uuid)."}
        $expectedCount=[long]$expectedAccount.count
        $currentCount=[long]$match[0].count
        if($currentCount -lt $expectedCount){throw "Rollback lost audit rows for disposable account $($expectedAccount.uuid)."}
    }
}

function Assert-T21AuditOwnerProfile([object]$assessment,[bool]$expectedEnabled,[string]$stage) {
    $expectedStatus=if($expectedEnabled){'drained'}else{'disabled'}
    $expectedEnabledValue=if($expectedEnabled){'true'}else{'false'}
    foreach($owner in @('audit','hybridAudit')){
        $records=@($assessment.ownerDrainRecords | Where-Object {$_ -match "owner=$owner\s"})
        if($records.Count -ne 1 -or -not $records[0].Contains("status=$expectedStatus") -or -not $records[0].Contains("enabled=$expectedEnabledValue")){
            throw "$owner owner drain did not match the requested audit profile after $stage."
        }
    }
}

function Assert-SnapshotSchemaRows([object]$snapshot,[string]$stage) {
    if ($script:baselineSchemaVersionRows.Count -eq 0) {
        $script:baselineSchemaVersionRows=@($snapshot.schemaVersionRows)
    }
    $null=Assert-T21SchemaVersionRows $script:baselineSchemaVersionRows @($snapshot.schemaVersionRows) $stage
    $script:schemaVersionObservations.Add([ordered]@{stage=$stage;rowCount=$snapshot.schemaVersionRows.Count;rows=@($snapshot.schemaVersionRows);matchesBaseline=$true})
}

function Get-RunOwnedProcesses([string]$marker,[string[]]$names) {
    @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object { $_.Name -in $names -and ([string]$_.CommandLine).Contains($marker,[StringComparison]::OrdinalIgnoreCase) })
}

function Wait-ProcessExit([int]$processId,[int]$seconds=30) {
    $deadline=[DateTime]::UtcNow.AddSeconds($seconds)
    do { if ($null -eq (Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue)) { return $true }; Start-Sleep -Milliseconds 250 } while ([DateTime]::UtcNow -lt $deadline)
    return $false
}

function Get-NodeSession([string]$nodeName) {
    $path = Join-Path $script:nodes[$nodeName].root '.plugdev\session.json'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing PlugDev session for $nodeName." }
    $s = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    $matches = @(Get-RunOwnedProcesses $script:nodes[$nodeName].marker @('java.exe','javaw.exe') | Where-Object { [int]$_.ProcessId -eq [int]$s.pid })
    if ($matches.Count -ne 1) { throw "Session PID is not uniquely owned by $nodeName's run marker." }
    return [ordered]@{path=$path;pid=[int]$s.pid;gamePort=[int]$s.gamePort;rconPort=[int]$s.rconPort}
}

function Wait-NodeMarker([string]$nodeName,[string]$marker,[int]$seconds=90) {
    $log=Join-Path $script:nodes[$nodeName].root '.plugdev\run\logs\latest.log'; $deadline=[DateTime]::UtcNow.AddSeconds($seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-Path -LiteralPath $log -PathType Leaf) {
            $c=Get-Content -LiteralPath $log -Raw
            if ($c -match 'ACCEPTANCE FAIL|T21_(?:WRITE|READ)_FAIL|Error occurred while enabling Syncmoney|Could not load plugin') { throw "Runtime failure marker observed on $nodeName; see private log." }
            if ($c.Contains($marker)) { return }
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Timed out waiting for '$marker' on $nodeName."
}

function Invoke-NodeCli([string]$nodeName,[string[]]$arguments,[string]$logName) {
    $node=$script:nodes[$nodeName]; $log=Join-Path $script:logsRoot $logName
    Push-Location $node.root
    try { $out=@(& $script:nodeExe $script:plugDevCli @arguments 2>&1); $exit=$LASTEXITCODE } finally { Pop-Location }
    $out | ForEach-Object { $_.ToString() } | Set-Content -LiteralPath $log -Encoding utf8
    if ($exit -ne 0) { throw "Local PlugDev command on $nodeName exited $exit; see $log." }
    return [ordered]@{exitCode=$exit;log=$log}
}

function Get-NodeConfigObservation([string]$nodeName,[string]$stage) {
    $node=$script:nodes[$nodeName]; $env:T21_NODE_ROOT=$node.root
    $js=@'
const fs=require('node:fs'); const path=require('node:path'); const {createRequire}=require('node:module');
const YAML=createRequire(path.join(process.env.PLUGDEV_ROOT,'package.json'))('yaml'); const cp=path.join(process.env.T21_NODE_ROOT,'.plugdev','run','plugins','Syncmoney','config.yml');
const c=YAML.parse(fs.readFileSync(cp,'utf8'));
process.stdout.write(JSON.stringify({serverName:c['server-name'],configVersion:c['config-version'],mode:c.economy?.mode,dbEnabled:c['db-enabled'],pubsubEnabled:c['pubsub-enabled'],redisEnabled:c.redis?.enabled,redisHost:c.redis?.host,redisPort:c.redis?.port,redisDatabase:c.redis?.database,databaseEnabled:c.database?.enabled,databaseType:c.database?.type,databaseHost:c.database?.host,databasePort:c.database?.port,databaseName:c.database?.database,syntheticOperatorFixture:c['t21-synthetic-operator-fixture']?.customValue}));
'@
    $path=Join-Path $script:runRoot 'observe-node-config.cjs'; Set-Content -LiteralPath $path -Value $js -Encoding utf8
    $out=@(& $script:nodeExe $path 2>&1); if ($LASTEXITCODE -ne 0) { throw "Failed to read $nodeName config at $stage." }
    $observation=($out -join "`n") | ConvertFrom-Json
    $null=Assert-T21NodeConfigObservation $observation $node.serverName $script:pgPort $script:dbName $script:redisPort
    $script:configObservations.Add([ordered]@{node=$nodeName;stage=$stage;config=$observation})
    return $observation
}

function Initialize-NodeConfig([string]$nodeName) {
    $node=$script:nodes[$nodeName]; $env:T21_NODE_ROOT=$node.root
    $env:T21_NODE_PORT=[string]$node.gamePort; $env:T21_NODE_SERVER_NAME=$node.serverName
    $env:T21_REDIS_PORT=[string]$script:redisPort; $env:SYNCMONEY_TEST_SERVER_JAR=$script:serverPath
    $env:T21_AUDIT_ENABLED=if($script:auditEnabled){'true'}else{'false'}
    $js=@'
const fs=require('node:fs'); const path=require('node:path'); const {createRequire}=require('node:module');
const YAML=createRequire(path.join(process.env.PLUGDEV_ROOT,'package.json'))('yaml'); const root=process.env.T21_NODE_ROOT;
const pp=path.join(root,'.plugdev','profile.yml'); const cp=path.join(root,'.plugdev','run','plugins','Syncmoney','config.yml');
const profile=YAML.parse(fs.readFileSync(pp,'utf8')); profile.port=Number(process.env.T21_NODE_PORT); profile.server='custom'; profile.serverJar=process.env.SYNCMONEY_TEST_SERVER_JAR; fs.writeFileSync(pp,YAML.stringify(profile),'utf8');
const config=YAML.parse(fs.readFileSync(cp,'utf8')); config['server-name']=process.env.T21_NODE_SERVER_NAME; config['t21-synthetic-operator-fixture']={customValue:'preserve-through-upgrade'};
config['db-enabled']=true; config['pubsub-enabled']=true; config.economy={...config.economy,mode:'sync'};
config.redis={...config.redis,enabled:true,host:'127.0.0.1',port:Number(process.env.T21_REDIS_PORT),database:15};
config.database={...config.database,enabled:true,type:'postgresql',host:'127.0.0.1',port:Number(process.env.SYNCMONEY_TEST_DB_PORT),username:process.env.SYNCMONEY_TEST_DB_USER,password:'',database:process.env.SYNCMONEY_TEST_DB_NAME};
const auditEnabled=process.env.T21_AUDIT_ENABLED==='true'; config.audit={...config.audit,enabled:auditEnabled,cleanup:{...config.audit?.cleanup,enabled:false},export:{...config.audit?.export,enabled:false},redis:{...config.audit?.redis,enabled:true}};
fs.writeFileSync(cp,YAML.stringify(config),'utf8');
'@
    $path=Join-Path $script:runRoot 'initialize-node-config.cjs'; Set-Content -LiteralPath $path -Value $js -Encoding utf8
    $out=@(& $script:nodeExe $path 2>&1); if ($LASTEXITCODE -ne 0) { throw "Failed to initialize the one-time config fixture for $nodeName." }
    $null=Get-NodeConfigObservation $nodeName 'after one-time fixture initialization'
}

function Set-NodeVersion([string]$nodeName,[string]$version) {
    $node=$script:nodes[$nodeName]; $env:T21_NODE_ROOT=$node.root; $env:T21_NODE_VERSION=$version; $env:T21_NODE_MARKER=$node.marker
    $env:SYNCMONEY_TEST_SERVER_JAR=$script:serverPath
    $js=@'
const fs=require('node:fs'); const path=require('node:path'); const {createRequire}=require('node:module');
const YAML=createRequire(path.join(process.env.PLUGDEV_ROOT,'package.json'))('yaml'); const root=process.env.T21_NODE_ROOT;
const pp=path.join(root,'.plugdev','profile.yml'); const profile=YAML.parse(fs.readFileSync(pp,'utf8'));
profile.build.jarPattern=`build/libs/Syncmoney-${process.env.T21_NODE_VERSION}.jar`;
profile.jvm.args=[`-Dsyncmoney.acceptance.version=${process.env.T21_NODE_VERSION}`,`-Dsyncmoney.t21.run=${process.env.T21_NODE_MARKER}`];
fs.writeFileSync(pp,YAML.stringify(profile),'utf8');
'@
    $path=Join-Path $script:runRoot 'set-node-version.cjs'; Set-Content -LiteralPath $path -Value $js -Encoding utf8
    $null=@(& $script:nodeExe $path 2>&1); if ($LASTEXITCODE -ne 0) { throw "Failed to select the $version artifact for $nodeName." }
    $null=Get-NodeConfigObservation $nodeName "before $version startup"
    $node.version=$version
}

function Set-NodeApiConsumer([string]$nodeName,[bool]$enabled) {
    $pluginsPath = Join-Path $script:nodes[$nodeName].root '.plugdev\run\plugins'
    $consumerPath = Join-Path $pluginsPath 'SyncmoneyApiConsumer.jar'
    if ($enabled) {
        Copy-Item -LiteralPath $script:apiConsumerPath -Destination $consumerPath -Force
        $actual = (Get-FileHash -LiteralPath $consumerPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $expected = (Get-FileHash -LiteralPath $script:apiConsumerPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $expected) { throw 'Could not stage the pinned candidate API consumer.' }
    } elseif (Test-Path -LiteralPath $consumerPath -PathType Leaf) {
        Remove-Item -LiteralPath $consumerPath -Force
    }
    if ((Test-Path -LiteralPath $consumerPath -PathType Leaf) -ne $enabled) {
        throw 'API consumer staging does not match the selected Syncmoney version.'
    }
}

function Start-Node([string]$nodeName,[string]$version,[string]$stage) {
    Set-NodeVersion $nodeName $version
    $candidateApiConsumer = $version -eq $CandidateVersion
    Set-NodeApiConsumer $nodeName $candidateApiConsumer
    $script:serverMayExist[$nodeName]=$true
    $started=Invoke-NodeCli $nodeName @('server','start','--config','.plugdev\profile.yml') "$stage-start.txt"
    $session=Get-NodeSession $nodeName
    Wait-NodeMarker $nodeName ("[Syncmoney] Loading server plugin Syncmoney v"+$version) 120
    Wait-NodeMarker $nodeName 'Syncmoney enabled successfully' 120
    $serverLog=Join-Path $script:nodes[$nodeName].root '.plugdev\run\logs\latest.log'
    $runtimeIdentity=Get-T21PlatformIdentity (Get-Content -LiteralPath $serverLog -Raw)
    Assert-T21PlatformIdentity $runtimeIdentity $Project $MinecraftVersion $BuildNumber
    Invoke-NodeCli $nodeName @('server','command','smaccept version') "$stage-version.txt" | Out-Null
    Wait-NodeMarker $nodeName ("T21_VERSION_PASS expected="+$version+" actual="+$version)
    if ($candidateApiConsumer) { Wait-NodeMarker $nodeName 'API CLASSLOADER PASS provider=Syncmoney' 60 }
    $script:stages.Add([ordered]@{stage=$stage;node=$nodeName;version=$version;startExitCode=$started.exitCode;pid=$session.pid;serverRuntime=$runtimeIdentity;versionProbe='PASS';candidateApiConsumer=if($candidateApiConsumer){'PASS'}else{'NOT_REQUIRED_FOR_BASELINE'}})
}

function Invoke-NodeCommand([string]$nodeName,[string]$command,[string]$stage,[string]$marker) {
    Invoke-NodeCli $nodeName @('server','command',$command) ($stage+'-command.txt') | Out-Null
    Wait-NodeMarker $nodeName $marker 45
}

function Invoke-VaultWrite([string]$nodeName,[string]$command,[string]$stage,[string]$beforeA,[string]$beforeB,[string]$expectedA,[string]$expectedB) {
    $prior=@((Get-Content -LiteralPath (Join-Path $script:nodes[$nodeName].root '.plugdev\run\logs\latest.log') | Where-Object { $_.Contains('T21_WRITE_PASS ') })).Count
    Invoke-NodeCli $nodeName @('server','command',$command) ($stage+'-command.txt') | Out-Null
    $log=Join-Path $script:nodes[$nodeName].root '.plugdev\run\logs\latest.log'; $deadline=[DateTime]::UtcNow.AddSeconds(45); $marker=$null
    while ([DateTime]::UtcNow -lt $deadline) {
        $lines=@(Get-Content -LiteralPath $log | Where-Object { $_.Contains('T21_WRITE_PASS ') })
        if ($lines.Count -gt $prior) { $marker=$lines[-1]; break }
        Start-Sleep -Milliseconds 250
    }
    if ($null -eq $marker -or -not $marker.Contains("expected-a=$expectedA expected-b=$expectedB")) { throw "Vault mutation receipt missing or mismatched on $nodeName." }
    foreach ($receipt in @('receipt-a=SUCCESS:','receipt-b=SUCCESS:')) { if (-not $marker.Contains($receipt)) { throw "Vault accepted-write response missing $receipt on $nodeName." } }
    $script:vaultReceipts.Add([ordered]@{node=$nodeName;stage=$stage;before=@($beforeA,$beforeB);after=@($expectedA,$expectedB);marker=$marker.Substring($marker.IndexOf('T21_WRITE_PASS ',[StringComparison]::Ordinal))})
}

function Get-T21NormalDrainAssessment([string]$content,[string[]]$failureMarkers,[int]$pendingRecoveryCount,[bool]$requireOwnerDrain=$false) {
    $syncmoneyDisabledMarker=$content.Contains('Syncmoney disabled.')
    $markers=@($failureMarkers)
    $ownerDrainRecords=@()
    $ownerDrainFailureMarkers=@()
    if ($requireOwnerDrain) {
        $requiredOwnerMetrics=[ordered]@{
            economy=[ordered]@{queued='0';outstanding='0';retry='0';workerTerminated='true';recoveryPending='0';recoveryBlocked='false'}
            database=[ordered]@{queued='0';inFlight='0';retry='0';stale='0';dropped='0'}
            audit=[ordered]@{enabled=$null;buffered='0';inFlight='0';retry='0';deadLetter='0';dropped='0';lateRejected='0'}
            hybridAudit=[ordered]@{enabled=$null;queued='0';inFlight='0';retry='0';lateRejected='0'}
        }
        $allOwnerMarkers=[regex]::Matches($content,'(?im)^.*T21_DRAIN[^\n]*$')
        $allMarkerOccurrences=[regex]::Matches($content,'(?i)T21_DRAIN')
        if ($allOwnerMarkers.Count -ne $requiredOwnerMetrics.Count -or $allMarkerOccurrences.Count -ne $requiredOwnerMetrics.Count) {
            $ownerDrainFailureMarkers+=('expected '+$requiredOwnerMetrics.Count+' unique owner records and marker occurrences, observed '+$allOwnerMarkers.Count+' records and '+$allMarkerOccurrences.Count+' markers')
        }
        foreach ($owner in $requiredOwnerMetrics.Keys) {
            $ownerMarkers=[regex]::Matches($content,"(?m)^.*T21_DRAIN owner=$([regex]::Escape($owner))[^\n]*$")
            if ($ownerMarkers.Count -ne 1) {
                $ownerDrainFailureMarkers+=('expected one '+$owner+' owner record, observed '+$ownerMarkers.Count)
                continue
            }
            $ownerLine=$ownerMarkers[0].Value
            $ownerDrainRecords+=$ownerLine.Trim()
            $markerIndex=$ownerLine.IndexOf('T21_DRAIN ',[StringComparison]::Ordinal)
            if ($markerIndex -lt 0) {
                $ownerDrainFailureMarkers+=($owner+' drain marker is malformed')
                continue
            }
            $tokens=$ownerLine.Substring($markerIndex+'T21_DRAIN '.Length).Trim() -split '\s+'
            $fields=@{}
            foreach ($token in $tokens) {
                if ($token -cnotmatch '^(?<name>[A-Za-z][A-Za-z0-9]*)=(?<value>[A-Za-z0-9_.-]+)$') {
                    $ownerDrainFailureMarkers+=($owner+' has malformed field '+$token)
                    continue
                }
                $name=$Matches.name
                $value=$Matches.value
                if ($fields.ContainsKey($name)) {
                    $ownerDrainFailureMarkers+=($owner+' has duplicate '+$name+' field')
                    continue
                }
                $fields[$name]=$value
            }

            $expectedMetrics=$requiredOwnerMetrics[$owner]
            $expectedFields=@('owner','status')+@($expectedMetrics.Keys)
            foreach ($field in $expectedFields) {
                if (-not $fields.ContainsKey($field)) {
                    $ownerDrainFailureMarkers+=($owner+' is missing '+$field)
                }
            }
            foreach ($field in $fields.Keys) {
                if ($expectedFields -cnotcontains $field) {
                    $ownerDrainFailureMarkers+=($owner+' has unexpected '+$field+' field')
                }
            }
            if ($fields.ContainsKey('owner') -and $fields['owner'] -cne $owner) {
                $ownerDrainFailureMarkers+=($owner+' marker owner does not match its record')
            }

            $status=$fields['status']
            if ($status -cne 'drained' -and $status -cne 'disabled') {
                $ownerDrainFailureMarkers+=($owner+' status is missing or non-drained')
            } elseif ($status -ceq 'disabled' -and $owner -cnotin @('audit','hybridAudit')) {
                $ownerDrainFailureMarkers+=($owner+' is required in SYNC mode and cannot report disabled')
            }
            if ($expectedMetrics.Contains('enabled')) {
                $expectedEnabled=if ($status -ceq 'disabled') {'false'} else {'true'}
                if ($fields.ContainsKey('enabled') -and $fields['enabled'] -cne $expectedEnabled) {
                    $ownerDrainFailureMarkers+=($owner+' enabled state does not match its status')
                }
            }
            foreach ($metric in $expectedMetrics.Keys) {
                if ($metric -ceq 'enabled') { continue }
                if ($fields.ContainsKey($metric) -and $fields[$metric] -cne $expectedMetrics[$metric]) {
                    $ownerDrainFailureMarkers+=($owner+' has nonzero or invalid '+$metric)
                }
            }
        }
    }
    $ownerDrainVerified=(!$requireOwnerDrain -or $ownerDrainFailureMarkers.Count -eq 0)
    $verified=($syncmoneyDisabledMarker -and $markers.Count -eq 0 -and $pendingRecoveryCount -eq 0 -and $ownerDrainVerified)
    return [ordered]@{pluginDrainVerified=$verified;syncmoneyDisabledMarker=$syncmoneyDisabledMarker;failureMarkers=$markers;pendingRecoveryRecords=$pendingRecoveryCount;ownerDrainRequired=$requireOwnerDrain;ownerDrainVerified=$ownerDrainVerified;ownerDrainRecords=$ownerDrainRecords;ownerDrainFailureMarkers=$ownerDrainFailureMarkers}
}

function Get-RecoveryJournalCheckpoint([string]$nodeName) {
    if (-not $script:nodes.Contains($nodeName)) { throw "Cannot checkpoint an unknown node: $nodeName." }
    $journalPath=Join-Path $script:nodes[$nodeName].root '.plugdev\run\plugins\Syncmoney\recovery-v1.jsonl'
    $journal=Read-RecoveryJournal $journalPath
    if ([long]$journal.pendingCount -ne 0) { throw "Recovery journal checkpoint for $nodeName contains pending records." }
    return [ordered]@{node=$nodeName;path=$journalPath;exists=[bool]$journal.exists;sha256=$journal.sha256;bytes=[long]$journal.bytes;frameCount=[long]$journal.frameCount;recordFrames=[long]$journal.recordFrames;ackFrames=[long]$journal.ackFrames;pendingCount=[long]$journal.pendingCount;validation=$journal.validation}
}

function Assert-RecoveryJournalCheckpointsUnchanged([object[]]$checkpoints,[string]$stage) {
    if ($checkpoints.Count -ne 2) { throw "Expected recovery journal checkpoints for both nodes before $stage." }
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach($checkpoint in $checkpoints) {
        if (-not $seen.Add([string]$checkpoint.node)) { throw "Duplicate recovery journal checkpoint for $($checkpoint.node)." }
        $observed=Assert-RecoveryJournalStateUnchanged ([string]$checkpoint.path) ([bool]$checkpoint.exists) ([string]$checkpoint.sha256)
        if ([bool]$observed.exists -ne [bool]$checkpoint.exists -or [long]$observed.pendingCount -ne [long]$checkpoint.pendingCount) {
            throw "Recovery journal state changed after $stage for $($checkpoint.node)."
        }
        $script:recoveryJournalRollbackChecks.Add([ordered]@{stage=$stage;node=$checkpoint.node;path=$checkpoint.path;expectedExists=$checkpoint.exists;observedExists=$observed.exists;expectedSha256=$checkpoint.sha256;observedSha256=$observed.sha256;expectedPendingCount=$checkpoint.pendingCount;observedPendingCount=$observed.pendingCount;validation=$observed.validation;result='UNCHANGED'})
    }
    if ($seen.Count -ne 2 -or -not $seen.Contains('nodeA') -or -not $seen.Contains('nodeB')) { throw "Recovery journal checkpoints before $stage do not cover nodeA and nodeB." }
}

function Stop-Node([string]$nodeName,[string]$stage,[bool]$requireOwnerDrain=$false) {
    $session=Get-NodeSession $nodeName
    $stop=Invoke-NodeCli $nodeName @('server','command','stop') "$stage-stop.txt"
    if (-not (Wait-ProcessExit $session.pid 40)) { throw "Server PID $($session.pid) remained after normal stop for $nodeName." }
    $log=Join-Path $script:nodes[$nodeName].root '.plugdev\run\logs\latest.log'; if (-not (Test-Path -LiteralPath $log -PathType Leaf)) { throw "Missing final server log for $nodeName." }
    $content=Get-Content -LiteralPath $log -Raw
    $bad=@('Accepted economy writes were handed off to recovery-v1.jsonl after the shutdown drain deadline','EconomyEventConsumer drain timed out; interrupting worker with pending writes','Interrupted while awaiting EconomyEventConsumer executor termination','Redis write failed for event','Failed to process economy event','Error in EconomyEventConsumer')
    $sharedFound=@(Get-T21ShutdownFailureMarkers $content)
    $localFound=@($bad | Where-Object { $content.Contains($_,[StringComparison]::OrdinalIgnoreCase) })
    $found=@($sharedFound + $localFound | Select-Object -Unique)
    $journalPath=Join-Path $script:nodes[$nodeName].root '.plugdev\run\plugins\Syncmoney\recovery-v1.jsonl'
    $journal=Read-RecoveryJournal $journalPath
    $save=Join-Path $script:logsRoot "$stage-server.log"; Copy-Item -LiteralPath $log -Destination $save
    Remove-Item -LiteralPath $session.path -Force
    $script:serverMayExist[$nodeName]=$false
    $assessment=Get-T21NormalDrainAssessment $content $found ([int]$journal.pendingCount) $requireOwnerDrain
    if($requireOwnerDrain){Assert-T21AuditOwnerProfile $assessment $script:auditEnabled $stage}
    $script:shutdown.Add([ordered]@{stage=$stage;node=$nodeName;pid=$session.pid;commandExit=$stop.exitCode;processExitVerified=$true;pluginDrainVerified=$assessment.pluginDrainVerified;syncmoneyDisabledMarker=$assessment.syncmoneyDisabledMarker;failureMarkers=$assessment.failureMarkers;pendingRecoveryRecords=$assessment.pendingRecoveryRecords;ownerDrainRequired=$assessment.ownerDrainRequired;ownerDrainVerified=$assessment.ownerDrainVerified;ownerDrainRecords=$assessment.ownerDrainRecords;ownerDrainFailureMarkers=$assessment.ownerDrainFailureMarkers;recoveryJournal=$journal;log=$save})
    if (-not $assessment.pluginDrainVerified) { throw "Normal drain could not be verified on $nodeName; disabled=$($assessment.syncmoneyDisabledMarker), pending=$($assessment.pendingRecoveryRecords), ownerDrainFailures=$($assessment.ownerDrainFailureMarkers -join ';'), errors=$($assessment.failureMarkers -join ';')." }
}

function Stop-PossiblyStartedNode([string]$nodeName) {
    $node=$script:nodes[$nodeName]
    $owned=@(Get-RunOwnedProcesses $node.marker @('java.exe','javaw.exe'))
    $ids=@($owned | ForEach-Object { [int]$_.ProcessId })
    $action=Get-T21PaperCleanupAction $ids ([bool]$script:serverMayExist[$nodeName])
    if ($action -ceq 'ALREADY_EXITED') {
        $script:serverMayExist[$nodeName]=$false
        $script:nodeCleanup.Add([ordered]@{node=$nodeName;action=$action;processIds=@();processExitVerified=$true})
        return
    }

    $processId=[int]$owned[0].ProcessId
    if ($action -ceq 'TRY_GRACEFUL_STOP') {
        $sessionPath=Join-Path $node.root '.plugdev\session.json'
        $session=$null
        try {
            if (Test-Path -LiteralPath $sessionPath -PathType Leaf) { $session=Get-Content -LiteralPath $sessionPath -Raw | ConvertFrom-Json }
        } catch { $session=$null }
        $sessionMatches=$false
        try {
            $sessionMatches=$null -ne $session -and [int]$session.pid -eq $processId -and
                [int]$session.gamePort -eq $node.gamePort -and [int]$session.rconPort -eq ($node.gamePort+10000)
        } catch { $sessionMatches=$false }
        if ($sessionMatches) {
            try {
                $null=Invoke-NodeCli $nodeName @('server','command','stop') ('failure-cleanup-'+$nodeName+'.txt')
                if (Wait-ProcessExit $processId 35) {
                    $left=@(Get-RunOwnedProcesses $node.marker @('java.exe','javaw.exe'))
                    if ($left.Count -eq 0) {
                        $script:serverMayExist[$nodeName]=$false
                        $script:nodeCleanup.Add([ordered]@{node=$nodeName;action='NORMAL_STOP_VERIFIED';processId=$processId;processExitVerified=$true})
                        return
                    }
                }
            } catch { }
        }
    }

    $stopResult=Invoke-RunOwnedPaperCleanupFallback $owned {
        param($ownedPid)
        $current=Get-CimInstance Win32_Process -Filter "ProcessId = $ownedPid" -ErrorAction SilentlyContinue
        if ($null -ne $current -and $current.Name -in @('java.exe','javaw.exe') -and
            ([string]$current.CommandLine).Contains($node.marker,[StringComparison]::OrdinalIgnoreCase)) {
            Stop-Process -Id $ownedPid -Force -ErrorAction Stop
        }
    } {
        param($ownedPid)
        if (-not (Wait-ProcessExit $ownedPid 20)) { throw "Run-owned Paper PID $ownedPid remained after failure cleanup." }
        if (@(Get-RunOwnedProcesses $node.marker @('java.exe','javaw.exe')).Count -ne 0) {
            throw "Run-marked Paper process remained after failure cleanup for $nodeName."
        }
    }
    $script:serverMayExist[$nodeName]=$false
    $script:forcedStops++
    $script:nodeCleanup.Add([ordered]@{node=$nodeName;action=$stopResult.action;processId=$stopResult.processId;processExitVerified=$true;unexpectedAfterNormalStop=($action -ceq 'STOP_UNEXPECTED_RUN_OWNED_PROCESS')})
    if ($action -ceq 'STOP_UNEXPECTED_RUN_OWNED_PROCESS') {
        throw "Unexpected run-owned Paper PID $processId remained after its normal lifecycle stop; it was force-stopped and verified."
    }
}

function Start-Postgres {
    $script:pgStarted=$true
    Invoke-PgCtl @('-D',$script:pgData,'-l',(Join-Path $script:logsRoot 'postgres.log'),'-o',"-h 127.0.0.1 -p $script:pgPort -c listen_addresses=127.0.0.1 -c fsync=on",'-w','-t','30','start') 'PostgreSQL start' 'postgres-start'
    $deadline=[DateTime]::UtcNow.AddSeconds(25)
    do { $null=@(& $script:pgIsReady '-h' '127.0.0.1' '-p' ([string]$script:pgPort) 2>&1); if($LASTEXITCODE -eq 0){return}; Start-Sleep -Milliseconds 250 } while([DateTime]::UtcNow -lt $deadline)
    throw 'Isolated PostgreSQL did not become ready.'
}

function Get-PostgresStatus {
    $output=@(& $script:pgCtl '-D' $script:pgData 'status' 2>&1); $exitCode=$LASTEXITCODE
    return [pscustomobject]@{exitCode=$exitCode;output=@($output | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ -ne '' });kind=(Get-RunOwnedPostgresStatusKind $exitCode $output)}
}

function Stop-Postgres([string]$stage='final-cleanup') {
    $before=Get-PostgresStatus
    $action=Get-T21PostgresStopAction $before.kind
    if ($action -ceq 'ALREADY_STOPPED') {
        $script:pgStarted=$false
        $script:backendShutdown.Add([ordered]@{backend='postgresql';stage=$stage;result='ALREADY_STOPPED';statusBefore=$before.kind;statusBeforeExitCode=$before.exitCode;statusBeforeOutput=$before.output;shutdownCommand='pg_ctl stop';shutdownExitCode=$null;statusAfter='STOPPED';statusAfterExitCode=$before.exitCode})
        return
    }

    if ($action -ceq 'ATTEMPT_BOUNDED_STOP_AND_FAIL') {
        $stopExit=$null; $stopError=$null
        try {
            Invoke-PgCtl @('-D',$script:pgData,'-m','fast','-w','-t','20','stop') 'PostgreSQL bounded cleanup after unknown status' ('postgres-'+$stage+'-unknown-stop')
            $stopExit=0
        } catch { $stopError=$_.Exception.Message }
        $after=Get-PostgresStatus
        $script:pgStarted=($after.kind -cne 'STOPPED')
        $script:backendShutdown.Add([ordered]@{backend='postgresql';stage=$stage;result='UNKNOWN_STATUS_BOUNDED_CLEANUP_ATTEMPTED';statusBefore=$before.kind;statusBeforeExitCode=$before.exitCode;statusBeforeOutput=$before.output;shutdownCommand='pg_ctl stop';shutdownExitCode=$stopExit;statusAfter=$after.kind;statusAfterExitCode=$after.exitCode;stopError=$stopError})
        throw "PostgreSQL status was unknown before cleanup; bounded stop was attempted and final status is $($after.kind). This run remains failed."
    }

    $stopExit=$null; $stopError=$null
    try {
        Invoke-PgCtl @('-D',$script:pgData,'-m','fast','-w','-t','30','stop') 'PostgreSQL stop' ('postgres-'+$stage+'-stop')
        $stopExit=0
    } catch { $stopError=$_.Exception.Message }
    $after=Get-PostgresStatus
    if ($after.kind -ceq 'UNKNOWN') {
        try {
            Invoke-PgCtl @('-D',$script:pgData,'-m','fast','-w','-t','20','stop') 'PostgreSQL bounded cleanup after unknown post-stop status' ('postgres-'+$stage+'-post-stop-unknown')
        } catch { if ($null -eq $stopError) { $stopError=$_.Exception.Message } else { $stopError+=' | '+$_.Exception.Message } }
        $after=Get-PostgresStatus
    }
    $script:pgStarted=($after.kind -cne 'STOPPED')
    $result=if($after.kind -ceq 'STOPPED' -and $stopExit -eq 0 -and $null -eq $stopError){'VERIFIED'}elseif($after.kind -ceq 'STOPPED'){'STOPPED_AFTER_COMMAND_ERROR'}else{'STOP_NOT_VERIFIED'}
    $script:backendShutdown.Add([ordered]@{backend='postgresql';stage=$stage;result=$result;statusBefore=$before.kind;statusBeforeExitCode=$before.exitCode;statusBeforeOutput=$before.output;shutdownCommand='pg_ctl stop';shutdownExitCode=$stopExit;statusAfter=$after.kind;statusAfterExitCode=$after.exitCode;stopError=$stopError})
    if ($result -cne 'VERIFIED') { throw "PostgreSQL shutdown was not verified for $stage; final status=$($after.kind), stopError=$stopError." }
}

function Start-Redis {
    $script:redisMayExist=$true
    $script:redisReady=$false
    $script:redisProcess=Start-Process -FilePath $script:redisServer -ArgumentList @($script:redisConfigArgument) -WorkingDirectory $script:redisData -WindowStyle Hidden -RedirectStandardOutput (Join-Path $script:logsRoot 'redis.stdout.log') -RedirectStandardError (Join-Path $script:logsRoot 'redis.stderr.log') -PassThru
    $script:redisLauncherPid=[int]$script:redisProcess.Id
    $deadline=[DateTime]::UtcNow.AddSeconds(25)
    do {
        if($script:redisProcess.HasExited){throw "Run-owned Redis exited $($script:redisProcess.ExitCode)."}
        try { if(((Invoke-Redis @('PING')) -join '').Trim() -eq 'PONG'){ $l=Get-NetTCPConnection -State Listen -LocalPort $script:redisPort -ErrorAction SilentlyContinue | Select-Object -First 1; if($null -ne $l){$script:redisPid=[int]$l.OwningProcess; $owned=@(Get-RunOwnedProcesses $script:redisConfigArgument @('redis-server.exe','redis-server') | Where-Object {[int]$_.ProcessId -eq $script:redisPid}); if($owned.Count -eq 1 -and $script:redisPid -eq [int]$script:redisProcess.Id){$script:redisReady=$true;return} } } } catch {}
        Start-Sleep -Milliseconds 250
    } while([DateTime]::UtcNow -lt $deadline)
    throw 'Isolated Redis failed readiness or ownership verification.'
}

function Stop-Redis([string]$stage='final-cleanup') {
    if (-not $script:redisMayExist) { return }
    $owned=@(Get-RunOwnedProcesses $script:redisConfigArgument @('redis-server.exe','redis-server'))
    $ownedIds=@($owned | ForEach-Object { [int]$_.ProcessId })
    if ($null -ne $script:redisProcess -and -not $script:redisProcess.HasExited) { $ownedIds+=@([int]$script:redisLauncherPid) }
    $ownedIds=@($ownedIds | Select-Object -Unique)
    $listeners=@(Get-NetTCPConnection -State Listen -LocalPort $script:redisPort -ErrorAction SilentlyContinue)
    $listenerIds=@($listeners | ForEach-Object { [int]$_.OwningProcess })
    $plan=Get-T21RedisCleanupPlan $ownedIds $listenerIds
    if ($plan.action -ceq 'ALREADY_EXITED') {
        $null=Assert-RunOwnedRedisProcessMayBeAlreadyExited ([bool]$script:redisReady) $true
        $script:redisMayExist=$false
        $script:backendShutdown.Add([ordered]@{backend='redis';stage=$stage;result='ALREADY_EXITED';shutdownCommand='SHUTDOWN SAVE';shutdownExitCode=$null;processExitCode=$null;processExitVerified=$true})
        return
    }

    if ($plan.action -ceq 'SHUTDOWN_RUN_OWNED_LISTENER') {
        $null=@(& $script:redisCli -h 127.0.0.1 -p $script:redisPort SHUTDOWN SAVE 2>&1); $shutdownExit=$LASTEXITCODE
        if (-not (Wait-ProcessExit $plan.processId 25)) { throw 'Run-owned Redis server remained after SHUTDOWN SAVE.' }
        if ($null -ne $script:redisProcess -and -not $script:redisProcess.HasExited) { $null=$script:redisProcess.WaitForExit(10000) }
        if ($null -ne $script:redisProcess -and -not $script:redisProcess.HasExited) { throw 'Redis launcher remained after graceful server shutdown.' }
        $left=@(Get-RunOwnedProcesses $script:redisConfigArgument @('redis-server.exe','redis-server'))
        if ($left.Count -ne 0) { throw 'Run-owned Redis process remained after SHUTDOWN SAVE.' }
        $processExitCode=Get-RunOwnedProcessExitCode $script:redisProcess $plan.processId
        $script:redisMayExist=$false
        $result=if($shutdownExit -ne 0){'SHUTDOWN_COMMAND_FAILED'}elseif($processExitCode -ne 0){'PROCESS_EXIT_CODE_UNVERIFIED_OR_NONZERO'}else{'VERIFIED'}
        $script:backendShutdown.Add([ordered]@{backend='redis';stage=$stage;result=$result;shutdownCommand='SHUTDOWN SAVE';shutdownExitCode=$shutdownExit;processExitCode=$processExitCode;processExitCodeObserved=($null -ne $processExitCode);processExitVerified=$true;processId=$plan.processId})
        if ($result -cne 'VERIFIED') { throw "Redis shutdown was not verified for $stage; command exit=$shutdownExit, process exit=$processExitCode." }
        return
    }

    $ownedProcess=@($owned | Where-Object { [int]$_.ProcessId -eq [int]$plan.processId } | Select-Object -First 1)
    $directLauncher=($null -ne $script:redisProcess -and -not $script:redisProcess.HasExited -and [int]$script:redisLauncherPid -eq [int]$plan.processId)
    if ($ownedProcess.Count -ne 1 -and -not $directLauncher) { throw 'Redis fallback cleanup lost its unique run-owned process identity.' }
    $current=Get-CimInstance Win32_Process -Filter "ProcessId = $($plan.processId)" -ErrorAction SilentlyContinue
    if ($null -eq $current) {
        $script:redisMayExist=$false
        return
    }
    $isOwned=$directLauncher -or ($current.Name -in @('redis-server.exe','redis-server') -and ([string]$current.CommandLine).Contains($script:redisConfigArgument,[StringComparison]::OrdinalIgnoreCase))
    if (-not $isOwned) { throw 'Redis fallback cleanup refused a process that no longer matches the run-owned identity.' }
    Stop-Process -Id $plan.processId -Force -ErrorAction Stop
    if (-not (Wait-ProcessExit $plan.processId 20)) { throw 'Run-owned Redis remained after readiness-failure cleanup.' }
    if (@(Get-RunOwnedProcesses $script:redisConfigArgument @('redis-server.exe','redis-server')).Count -ne 0) { throw 'Run-owned Redis process remained after readiness-failure cleanup.' }
    $fallbackExitCode=Get-RunOwnedProcessExitCode $script:redisProcess $plan.processId
    $script:redisMayExist=$false
    $script:backendShutdown.Add([ordered]@{backend='redis';stage=$stage;result='STOPPED_WITH_RUN_OWNED_FALLBACK';shutdownCommand='process stop';shutdownExitCode=$null;processExitCode=$fallbackExitCode;processExitCodeObserved=($null -ne $fallbackExitCode);processExitVerified=$true;processId=$plan.processId;unrelatedListenerPids=@($listenerIds | Where-Object { $_ -ne $plan.processId })})
    if ($stage -cne 'final-cleanup' -or $null -eq $script:failure) { throw 'Redis required forced run-owned cleanup instead of its verified graceful shutdown.' }
}

function Get-FileManifest([string]$path) {
    $base=[IO.Path]::GetFullPath($path)
    @(Get-ChildItem -LiteralPath $base -File -Recurse -Force | Sort-Object FullName | ForEach-Object { [ordered]@{path=$_.FullName.Substring($base.Length).TrimStart('\','/').Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()} })
}

function Invoke-GradleCleanup {
    $owned=@(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {$_.Name -in @('java.exe','javaw.exe') -and ([string]$_.CommandLine).Contains([IO.Path]::GetFullPath($script:gradleHome),[StringComparison]::OrdinalIgnoreCase) -and ([string]$_.CommandLine -match 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon')})
    $ids=@($owned | ForEach-Object {[int]$_.ProcessId})
    $cleanup=Invoke-RunOwnedGradleDaemonCleanup $owned {
        Push-Location $script:root
        try {$null=& (Join-Path $script:root 'gradlew.bat') --stop *> (Join-Path $script:logsRoot 'gradle-stop.txt'); return [ordered]@{exitCode=$LASTEXITCODE}} finally {Pop-Location}
    } {
        $until=[DateTime]::UtcNow.AddSeconds(20); do {$r=@(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {$_.Name -in @('java.exe','javaw.exe') -and ([string]$_.CommandLine).Contains([IO.Path]::GetFullPath($script:gradleHome),[StringComparison]::OrdinalIgnoreCase) -and ([string]$_.CommandLine -match 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon')}); if($r.Count -eq 0){return @()}; Start-Sleep -Milliseconds 250} while([DateTime]::UtcNow -lt $until); return $r
    }
    $script:gradleCleanup=[ordered]@{processIdsBeforeStop=$ids;stopExitCode=$cleanup.stopExitCode;processIdsAfterStop=@($cleanup.remainingProcessIds);action=$cleanup.action}
}

function Get-RunHarnessSourceManifest([string]$repositoryRoot) {
    $relativePaths=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach($path in @(
        'build.gradle','gradlew.bat','gradle/wrapper/gradle-wrapper.jar','gradle/wrapper/gradle-wrapper.properties',
        'src/main/resources/config.yml','src/main/resources/plugin.yml',
        'tools/plugdev/run-quiesced-network-upgrade.ps1','tools/plugdev/run-quiesced-network-upgrade.test.ps1',
        'tools/plugdev/t21-upgrade-validation.ps1','tools/plugdev/t21-upgrade-validation.test.ps1',
        'tools/plugdev/run-sync-upgrade-cleanup.ps1','tools/plugdev/run-sync-upgrade-cleanup.test.ps1',
        'tools/plugdev/recovery-spool-validator.ps1','tools/plugdev/recovery-spool-validator.test.ps1',
        'tools/plugdev/prepare.mjs','tools/plugdev/prepare.test.mjs'
    )) { $null=$relativePaths.Add($path) }
    foreach($sourceRoot in @('src/acceptance','src/api-consumer')) {
        $directory=Join-Path $repositoryRoot $sourceRoot.Replace('/','\')
        if(-not (Test-Path -LiteralPath $directory -PathType Container)) { throw "Missing harness source directory: $sourceRoot" }
        foreach($file in Get-ChildItem -LiteralPath $directory -Recurse -File) {
            $relative=[IO.Path]::GetRelativePath($repositoryRoot,$file.FullName).Replace('\','/')
            $null=$relativePaths.Add($relative)
        }
    }
    $manifest=@(foreach($relative in ($relativePaths | Sort-Object)) {
        $path=Join-Path $repositoryRoot $relative.Replace('/','\')
        if(-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing harness source file: $relative" }
        [ordered]@{path=$relative;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
    return $manifest
}

function Save-Report {
    if(Test-Path -LiteralPath $script:runRoot){
        $harnessSources=@(Get-RunHarnessSourceManifest $root)
        $report=[ordered]@{
            task='T21 M01-M03 quiesced two-node SYNC upgrade and normal downgrade';status=$script:status;startedAtUtc=$script:startedAtUtc;sourceCommit=(& git -C $root rev-parse HEAD 2>$null | Out-String).Trim();
            invocation=[ordered]@{project=$Project;minecraftVersion=$MinecraftVersion;build=$BuildNumber;expectedServerSha256=$ExpectedServerSha256.ToLowerInvariant();baselineSourceCommit=$BaselineSourceCommit.ToLowerInvariant();candidateVersion=$CandidateVersion};
            auditProfile=[ordered]@{requestedEnabled=$script:auditEnabled;databaseAuditEnabled=$script:auditEnabled;redisHybridAuditEnabled=$script:auditEnabled;cleanupAndExportEnabled=$false};
            platform=[ordered]@{project=$Project;minecraftVersion=$MinecraftVersion;build=$BuildNumber;serverSha256=$script:serverSha;java=$script:javaVersion};
            plugins=[ordered]@{baselineVersion='1.3.2';baselineSha256=$script:baselineSha;baselineSourceCommit=$BaselineSourceCommit;candidateVersion=$CandidateVersion;candidateSha256=$script:candidateSha};
            harness=[ordered]@{sourceFiles=$harnessSources;acceptanceJarSha256=(Get-FileHash -LiteralPath $acceptancePath -Algorithm SHA256).Hash.ToLowerInvariant();apiConsumerJarSha256=(Get-FileHash -LiteralPath $apiConsumerPath -Algorithm SHA256).Hash.ToLowerInvariant()};
            backends=[ordered]@{postgresVersion=$script:postgresVersion;postgresPort=$script:pgPort;database=$script:dbName;redisVersion=$script:redisVersion;redisPort=$script:redisPort;redisDatabase=15};
            plugDev=[ordered]@{root=$plugDevRoot;version=$script:plugDevVersion;commit=$script:plugDevCommit;cliSha256=$script:plugDevCliSha};
            nodes=@($script:nodes.Values | ForEach-Object {[ordered]@{name=$_.name;serverName=$_.serverName;port=$_.gamePort;rconPort=($_.gamePort+10000);baselineAndCandidateVersions=$_.versions}});
            quiescence=[ordered]@{scenario="Only two synthetic $Project nodes and the acceptance probe are deployed. Every node was normally stopped and drain-verified before the pre-upgrade backup; the harness issues no producer calls between maintenance start and candidate acceptance probes.";allNodesOfflineForBackup=$script:allOfflineForBackup;forcedStops=$script:forcedStops;pendingRecoveryAtGates=$script:pendingRecoveryAtGates};
            backups=$script:backupManifest;configObservations=$script:configObservations;schemaVersionObservations=$script:schemaVersionObservations;backendShutdown=$script:backendShutdown;snapshots=$script:snapshots;auditSnapshots=$script:auditSnapshots;vaultReceipts=$script:vaultReceipts;stages=$script:stages;shutdown=$script:shutdown;nodeCleanup=$script:nodeCleanup;recoveryJournals=$script:recoveryJournals;recoveryJournalRollbackChecks=$script:recoveryJournalRollbackChecks;gradleDaemonCleanup=$script:gradleCleanup;failure=$script:failure;cleanupFailure=$script:cleanupFailure;runDirectory=$script:runRoot
        }
        $report | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath (Join-Path $script:runRoot 'result.json') -Encoding utf8
    }
}

if(-not (Test-Path -LiteralPath $serverPath -PathType Leaf) -or -not (Test-Path -LiteralPath $baselinePath -PathType Leaf) -or -not (Test-Path -LiteralPath $candidatePath -PathType Leaf) -or -not (Test-Path -LiteralPath $acceptancePath -PathType Leaf) -or -not (Test-Path -LiteralPath $apiConsumerPath -PathType Leaf)){throw 'One or more pinned Paper/plugin/acceptance artifacts are missing.'}
$script:serverSha=(Get-FileHash -LiteralPath $serverPath -Algorithm SHA256).Hash.ToLowerInvariant(); if($script:serverSha -ne $ExpectedServerSha256.ToLowerInvariant()){throw "Server JAR SHA mismatch: $script:serverSha"}
$script:baselineSha=(Get-FileHash -LiteralPath $baselinePath -Algorithm SHA256).Hash.ToLowerInvariant(); if($script:baselineSha -ne $ExpectedBaselinePluginSha256.ToLowerInvariant()){throw "Baseline plugin SHA mismatch: $script:baselineSha"}
$script:candidateSha=(Get-FileHash -LiteralPath $candidatePath -Algorithm SHA256).Hash.ToLowerInvariant()
$script:observedBaselineCommit=(& git -C $baselineRoot rev-parse HEAD 2>$null | Out-String).Trim(); if($LASTEXITCODE -ne 0 -or $script:observedBaselineCommit -ne $BaselineSourceCommit.ToLowerInvariant()){throw 'Baseline source checkout does not match the pinned commit.'}
$script:javaVersion=(& $javaExe -version 2>&1 | Out-String).Trim(); if($LASTEXITCODE -ne 0 -or $script:javaVersion -notmatch 'version "25(?:\.|"|\+)'){throw 'T21 runtime requires Java 25.'}
$redisServerCommand=(Get-Command redis-server.exe,redis-server -ErrorAction Stop | Select-Object -First 1).Source
$script:redisServer=Resolve-RedisServerExecutable $redisServerCommand
$script:redisCli=(Get-Command redis-cli.exe,redis-cli -ErrorAction Stop | Select-Object -First 1).Source
$script:pgCtl=Join-Path $PostgresBin 'pg_ctl.exe';$script:initdb=Join-Path $PostgresBin 'initdb.exe';$script:pgIsReady=Join-Path $PostgresBin 'pg_isready.exe';$script:pgDump=Join-Path $PostgresBin 'pg_dump.exe';$script:pgRestore=Join-Path $PostgresBin 'pg_restore.exe';$script:psql=Join-Path $PostgresBin 'psql.exe'
foreach($p in @($script:pgCtl,$script:initdb,$script:pgIsReady,$script:pgDump,$script:pgRestore,$script:psql,$plugDevCli,$javaExe)){if(-not(Test-Path -LiteralPath $p -PathType Leaf)){throw "Required local executable is missing: $p"}}
$plugDevPackage=Get-Content (Join-Path $plugDevRoot 'package.json') -Raw | ConvertFrom-Json; if($plugDevPackage.private -ne $true){throw 'The PlugDev checkout must be local/private.'};$script:plugDevVersion=$plugDevPackage.version;$script:plugDevCommit=(& git -C $plugDevRoot rev-parse HEAD 2>$null | Out-String).Trim();$script:plugDevCliSha=(Get-FileHash -LiteralPath $plugDevCli -Algorithm SHA256).Hash.ToLowerInvariant()
$reportsRoot=[IO.Path]::GetFullPath((Join-Path $root 'build\reports\foundation\T21-quiesced-network-upgrade'));Assert-NoReparsePointAncestors $reportsRoot;New-Item -ItemType Directory -Force -Path $reportsRoot | Out-Null
$guid=[guid]::NewGuid().ToString('N');$script:runRoot=Join-Path $reportsRoot ($Project+'-'+$MinecraftVersion.Replace('.','-')+'-'+$BuildNumber+'-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+$guid);if(Test-Path -LiteralPath $script:runRoot){throw 'Refusing reused network-upgrade run path.'};New-Item -ItemType Directory -Path $script:runRoot | Out-Null
$script:logsRoot=Join-Path $script:runRoot 'logs';$script:pgData=Join-Path $script:runRoot 'postgres\data';$script:redisData=Join-Path $script:runRoot 'redis';$script:redisConfig=Join-Path $script:redisData 'redis.conf';$script:backupsRoot=Join-Path $script:runRoot 'preupgrade-backup';$script:gradleHome=Join-Path $script:runRoot '.gradle-user-home';$script:plugDevHome=Join-Path $script:runRoot '.plugdev-home';$script:nodeExe=$nodeExe;$script:plugDevCli=$plugDevCli;$script:root=$root
New-Item -ItemType Directory -Path $script:logsRoot,$script:pgData,$script:redisData,$script:gradleHome,$script:plugDevHome -Force | Out-Null
$script:gamePorts=@{nodeA=Get-FreePort 20000 45535 @() 10000;nodeB=0};$script:gamePorts.nodeB=Get-FreePort 20000 45535 @($script:gamePorts.nodeA,$script:gamePorts.nodeA+10000) 10000
$script:pgPort=Get-FreePort 20000 65535 @($script:gamePorts.nodeA,$script:gamePorts.nodeA+10000,$script:gamePorts.nodeB,$script:gamePorts.nodeB+10000);$script:redisPort=Get-FreePort 20000 65535 @($script:gamePorts.nodeA,$script:gamePorts.nodeA+10000,$script:gamePorts.nodeB,$script:gamePorts.nodeB+10000,$script:pgPort)
$script:pgUser='syncmoney_t21';$script:dbName='syncmoney_t21';$script:accounts=[ordered]@{AcceptanceA=Get-OfflineUuid 'AcceptanceA';AcceptanceB=Get-OfflineUuid 'AcceptanceB'}
$script:nodes=[ordered]@{};$script:serverMayExist=[ordered]@{nodeA=$false;nodeB=$false};$script:stages=[Collections.Generic.List[object]]::new();$script:shutdown=[Collections.Generic.List[object]]::new();$script:snapshots=[Collections.Generic.List[object]]::new();$script:auditSnapshots=[Collections.Generic.List[object]]::new();$script:vaultReceipts=[Collections.Generic.List[object]]::new();$script:recoveryJournals=[Collections.Generic.List[object]]::new();$script:recoveryJournalRollbackChecks=[Collections.Generic.List[object]]::new();$script:nodeCleanup=[Collections.Generic.List[object]]::new();$script:configObservations=[Collections.Generic.List[object]]::new();$script:schemaVersionObservations=[Collections.Generic.List[object]]::new();$script:backendShutdown=[Collections.Generic.List[object]]::new();$script:baselineSchemaVersionRows=@();$script:backupManifest=@();$script:pendingRecoveryAtGates=[Collections.Generic.List[int]]::new();$script:forcedStops=0;$script:allOfflineForBackup=$false;$script:failure=$null;$script:cleanupFailure=$null;$script:gradleCleanup=$null;$script:psqlFailureSequence=0;$script:startedAtUtc=[DateTime]::UtcNow.ToString('o');$script:status='RUNNING';$script:pgStarted=$false;$script:redisMayExist=$false;$script:redisReady=$false;$script:redisProcess=$null;$script:redisLauncherPid=$null;$script:redisPid=$null;$script:redisConfigArgument=ConvertTo-CygwinPath $script:redisConfig
$script:postgresVersion=$null;$script:redisVersion=(& $script:redisServer --version 2>&1 | Out-String).Trim()

try {
    $env:JAVA_HOME=[IO.Path]::GetFullPath($JavaHome);$env:Path="$JavaHome\bin;$env:Path";$env:PLUGDEV_ROOT=$plugDevRoot;$env:PLUGDEV_HOME=$script:plugDevHome;$env:GRADLE_USER_HOME=$script:gradleHome;$env:PGSSLMODE='disable';New-Item -ItemType File -Force -Path (Join-Path $script:gradleHome 'gradle.properties') | Out-Null;Set-Content -LiteralPath (Join-Path $script:gradleHome 'gradle.properties') -Value 'org.gradle.daemon=false' -Encoding ascii
    $env:SYNCMONEY_TEST_SERVER_JAR=$serverPath;$env:SYNCMONEY_CANDIDATE_VERSION=$CandidateVersion;$env:SYNCMONEY_TEST_DB_TYPE='postgresql';$env:SYNCMONEY_TEST_DB_PORT=[string]$script:pgPort;$env:SYNCMONEY_TEST_DB_USER=$script:pgUser;$env:SYNCMONEY_TEST_DB_PASSWORD='';$env:SYNCMONEY_TEST_DB_NAME=$script:dbName;$env:SYNCMONEY_TEST_REDIS_PORT=[string]$script:redisPort;$env:T21_RUN_MARKER=$script:runRoot
    @('bind 127.0.0.1',"port $script:redisPort",'protected-mode yes','daemonize no','supervised no','databases 16','save ""','appendonly yes','appendfsync always','appendfilename "appendonly.aof"','appenddirname "appendonlydir"',"dir $((ConvertTo-CygwinPath $script:redisData))",'dbfilename dump.rdb',"logfile $((ConvertTo-CygwinPath (Join-Path $script:redisData 'redis.log')))" ) | Set-Content -LiteralPath $script:redisConfig -Encoding ascii
    $null=Invoke-Logged $script:initdb @('-D',$script:pgData,'-U',$script:pgUser,'--no-locale','-E','UTF8','--auth-local=trust','--auth-host=trust') (Join-Path $script:logsRoot 'postgres-initdb.txt') 'PostgreSQL initdb'
    Start-Postgres
    $create=@(& $script:psql '--no-password' '--no-psqlrc' '--host=127.0.0.1' "--port=$script:pgPort" "--username=$script:pgUser" '--dbname=postgres' '--set=ON_ERROR_STOP=1' "--command=CREATE DATABASE $script:dbName;" 2>&1);if($LASTEXITCODE -ne 0){throw 'Could not create the isolated Syncmoney database.'}
    $script:postgresVersion=((Invoke-Psql 'SELECT version();') -join ' ').Trim()
    Start-Redis
    if([int]((Invoke-Redis @('-n','15','DBSIZE')) -join '') -ne 0){throw 'The isolated Redis database was not empty before baseline seeding.'}

    foreach($name in @('nodeA','nodeB')){
        $nodeRoot=Join-Path $script:runRoot $name;New-Item -ItemType Directory -Path (Join-Path $nodeRoot 'build\libs'),(Join-Path $nodeRoot 'build\acceptance'),(Join-Path $nodeRoot 'src\main\resources'),(Join-Path $nodeRoot 'tools\plugdev'),(Join-Path $nodeRoot 'gradle\wrapper') -Force | Out-Null
        foreach($file in @('config.yml','plugin.yml')){Copy-Item -LiteralPath (Join-Path $root "src\main\resources\$file") -Destination (Join-Path $nodeRoot "src\main\resources\$file")}
        Copy-Item -LiteralPath (Join-Path $root 'tools\plugdev\prepare.mjs') -Destination (Join-Path $nodeRoot 'tools\plugdev\prepare.mjs')
        Copy-Item -LiteralPath (Join-Path $root 'gradlew.bat') -Destination $nodeRoot;Copy-Item -LiteralPath (Join-Path $root 'gradle\wrapper\gradle-wrapper.jar') -Destination (Join-Path $nodeRoot 'gradle\wrapper');Copy-Item -LiteralPath (Join-Path $root 'gradle\wrapper\gradle-wrapper.properties') -Destination (Join-Path $nodeRoot 'gradle\wrapper')
        Copy-Item -LiteralPath $baselinePath -Destination (Join-Path $nodeRoot 'build\libs\Syncmoney-1.3.2.jar');Copy-Item -LiteralPath $candidatePath -Destination (Join-Path $nodeRoot "build\libs\Syncmoney-$CandidateVersion.jar");Copy-Item -LiteralPath $acceptancePath -Destination (Join-Path $nodeRoot 'build\acceptance\SyncmoneyAcceptance.jar');Copy-Item -LiteralPath $apiConsumerPath -Destination (Join-Path $nodeRoot 'build\acceptance\SyncmoneyApiConsumer.jar')
        Set-Content -LiteralPath (Join-Path $nodeRoot 'settings.gradle') -Value "rootProject.name = 'syncmoney-t21-$name'`n" -Encoding utf8;Set-Content -LiteralPath (Join-Path $nodeRoot 'build.gradle') -Value "plugins { id 'java' }`n" -Encoding utf8
        $port=$script:gamePorts[$name];$env:SYNCMONEY_TEST_GAME_PORT=[string]$port
        Push-Location $nodeRoot;try{$o=@(& $nodeExe (Join-Path $nodeRoot 'tools\plugdev\prepare.mjs') $Project $MinecraftVersion 'sync' 'off' $CandidateVersion 2>&1);$ec=$LASTEXITCODE;$o|ForEach-Object{$_.ToString()}|Set-Content -LiteralPath (Join-Path $script:logsRoot "$name-prepare.txt") -Encoding utf8}finally{Pop-Location};if($ec -ne 0){throw "Could not prepare the isolated $name PlugDev profile."}
        $serverName="t21-$name";$marker=Join-Path $script:runRoot "owned-$name"
        $script:nodes[$name]=[ordered]@{name=$name;root=$nodeRoot;gamePort=$port;serverName=$serverName;marker=$marker;version='1.3.2';versions=@('1.3.2',$CandidateVersion)}
        Initialize-NodeConfig $name
        Set-NodeVersion $name '1.3.2'
    }

    # Seed one shared legacy state, then prove the other stopped baseline node can read it.
    Start-Node 'nodeA' '1.3.2' 'baseline-seed-nodeA';Invoke-VaultWrite 'nodeA' 'smaccept write 1000.00 520.00' 'baseline-seed' '0.00' '0.00' '1000.00' '520.00';Stop-Node 'nodeA' 'baseline-seed-nodeA'
    $baselineSnapshot=Get-BackendSnapshot 'baseline node A normal drain';Assert-Balances $baselineSnapshot '1000.00' '520.00' 'baseline node A';Assert-BackendVersions $baselineSnapshot 1 1 'baseline node A';Assert-SnapshotSchemaRows $baselineSnapshot 'baseline node A normal drain';$script:snapshots.Add($baselineSnapshot)
    Start-Node 'nodeB' '1.3.2' 'baseline-read-nodeB';Invoke-NodeCommand 'nodeB' 'smaccept state-read 1000.00 520.00' 'baseline-read' 'T21_READ_PASS expected-a=1000.00 expected-b=520.00';Stop-Node 'nodeB' 'baseline-read-nodeB'
    $baselineAuditSnapshot=Get-AuditSnapshot 'after baseline seed and shared read'
    $script:pendingRecoveryAtGates.Add(0)

    # Maintenance window: every baseline node has exited and its recovery journal has no pending records.
    $script:allOfflineForBackup=($script:serverMayExist.nodeA -eq $false -and $script:serverMayExist.nodeB -eq $false -and $script:shutdown.Count -eq 2 -and @($script:shutdown|Where-Object{$_.pluginDrainVerified -ne $true}).Count -eq 0)
    if(-not $script:allOfflineForBackup){throw 'Pre-upgrade snapshot is blocked because not every node is stopped and drain-verified.'}
    $pgDumpPath=Join-Path $script:backupsRoot 'syncmoney_t21.pgdump';New-Item -ItemType Directory -Path $script:backupsRoot -Force | Out-Null
    Invoke-Logged $script:pgDump @('--no-password','--host=127.0.0.1',"--port=$script:pgPort","--username=$script:pgUser","--dbname=$script:dbName",'--format=custom',"--file=$pgDumpPath") (Join-Path $script:logsRoot 'preupgrade-pg-dump.txt') 'PostgreSQL backup';Invoke-Logged $script:pgRestore @('--list',$pgDumpPath) (Join-Path $script:logsRoot 'preupgrade-pg-restore-list.txt') 'PostgreSQL backup validation'
    $save=((Invoke-Redis @('-n','15','SAVE')) -join '').Trim();if($save -ne 'OK'){throw 'Redis did not confirm the pre-upgrade RDB save.'}
    Stop-Redis 'preupgrade-backup';Stop-Postgres 'preupgrade-backup'
    foreach($name in @('nodeA','nodeB')){Copy-Item -LiteralPath (Join-Path $script:nodes[$name].root '.plugdev\run\plugins\Syncmoney') -Destination (Join-Path $script:backupsRoot "$name-plugin-data") -Recurse}
    Copy-Item -LiteralPath $script:redisData -Destination (Join-Path $script:backupsRoot 'redis-data') -Recurse
    $script:backupManifest=@([ordered]@{path='syncmoney_t21.pgdump';bytes=(Get-Item $pgDumpPath).Length;sha256=(Get-FileHash $pgDumpPath -Algorithm SHA256).Hash.ToLowerInvariant()})
    foreach($name in @('nodeA','nodeB')){$script:backupManifest+=@(Get-FileManifest (Join-Path $script:backupsRoot "$name-plugin-data") | ForEach-Object{$_.path="$name-plugin-data/"+$_.path;$_})}
    $script:backupManifest+=@(Get-FileManifest (Join-Path $script:backupsRoot 'redis-data') | ForEach-Object{$_.path='redis-data/'+$_.path;$_})
    $backupCheck=@($script:backupManifest | ForEach-Object{$actual=(Get-FileHash (Join-Path $script:backupsRoot $_.path) -Algorithm SHA256).Hash.ToLowerInvariant();if($actual -ne $_.sha256){throw "Backup hash changed: $($_.path)"};$true});if($backupCheck.Count -lt 8){throw 'Expected SQL, Redis and both node config backup artifacts.'}
    $script:pendingRecoveryAtGates.Add(0)
    Start-Postgres;Start-Redis
    $afterRestart=Get-BackendSnapshot 'after verified pre-upgrade backup and backend restart';Assert-Balances $afterRestart '1000.00' '520.00' 'backend restart';Assert-BackendVersions $afterRestart 1 1 'backend restart';Assert-SnapshotSchemaRows $afterRestart 'after verified pre-upgrade backup and backend restart';$script:snapshots.Add($afterRestart)

    # Canary node A is upgraded with node B offline; then node B consumes only the new shared state.
    Start-Node 'nodeA' $CandidateVersion 'upgrade-canary-nodeA';Invoke-NodeCommand 'nodeA' 'smaccept state-read 1000.00 520.00' 'upgrade-canary-read' 'T21_READ_PASS expected-a=1000.00 expected-b=520.00';Invoke-VaultWrite 'nodeA' 'smaccept write 1337.25 765.50' 'upgrade-canary-write' '1000.00' '520.00' '1337.25' '765.50';Stop-Node 'nodeA' 'upgrade-canary-nodeA' $true
    $candidateSnapshot=Get-BackendSnapshot 'after candidate node A normal drain';Assert-Balances $candidateSnapshot '1337.25' '765.50' 'candidate node A';Assert-BackendVersions $candidateSnapshot 2 2 'candidate node A';Assert-SnapshotSchemaRows $candidateSnapshot 'after candidate node A normal drain';$script:snapshots.Add($candidateSnapshot)
    $candidateAuditSnapshot=Get-AuditSnapshot 'after candidate node A normal drain';Assert-AuditSnapshotAdvanced $baselineAuditSnapshot $candidateAuditSnapshot
    $null=Get-NodeConfigObservation 'nodeA' 'after candidate shutdown before rollback'
    $journalPath=Join-Path $script:nodes.nodeA.root '.plugdev\run\plugins\Syncmoney\recovery-v1.jsonl';$journal=Assert-RecoveryJournalForRollback (Read-RecoveryJournal $journalPath);$candidateNodeAJournalCheckpoint=[ordered]@{node='nodeA';path=$journalPath;exists=$true;sha256=$journal.sha256;bytes=$journal.bytes;frameCount=$journal.frameCount;recordFrames=$journal.recordFrames;ackFrames=$journal.ackFrames;pendingCount=$journal.pendingCount;validation=$journal.validation}
    Start-Node 'nodeB' $CandidateVersion 'upgrade-nodeB';Invoke-NodeCommand 'nodeB' 'smaccept state-read 1337.25 765.50' 'upgrade-nodeB-read' 'T21_READ_PASS expected-a=1337.25 expected-b=765.50';Stop-Node 'nodeB' 'upgrade-nodeB' $true
    $newNodeSnapshot=Get-BackendSnapshot 'after candidate node B normal drain';Assert-Balances $newNodeSnapshot '1337.25' '765.50' 'candidate node B';Assert-BackendVersions $newNodeSnapshot 2 2 'candidate node B';Assert-SnapshotSchemaRows $newNodeSnapshot 'after candidate node B normal drain';$script:snapshots.Add($newNodeSnapshot)
    $null=Get-NodeConfigObservation 'nodeB' 'after candidate shutdown before rollback'
    $nodeARecoveryCheckpoint=Get-RecoveryJournalCheckpoint 'nodeA'
    $null=Assert-RecoveryJournalStateUnchanged $candidateNodeAJournalCheckpoint.path $true $candidateNodeAJournalCheckpoint.sha256
    $nodeBRecoveryCheckpoint=Get-RecoveryJournalCheckpoint 'nodeB'
    $script:recoveryJournals.Add([ordered]@{stage='before-rollback';node='nodeA';path=$nodeARecoveryCheckpoint.path;exists=$nodeARecoveryCheckpoint.exists;sha256=$nodeARecoveryCheckpoint.sha256;bytes=$nodeARecoveryCheckpoint.bytes;frameCount=$nodeARecoveryCheckpoint.frameCount;recordFrames=$nodeARecoveryCheckpoint.recordFrames;ackFrames=$nodeARecoveryCheckpoint.ackFrames;pendingCount=$nodeARecoveryCheckpoint.pendingCount;validation=$nodeARecoveryCheckpoint.validation})
    $script:recoveryJournals.Add([ordered]@{stage='before-rollback';node='nodeB';path=$nodeBRecoveryCheckpoint.path;exists=$nodeBRecoveryCheckpoint.exists;sha256=$nodeBRecoveryCheckpoint.sha256;bytes=$nodeBRecoveryCheckpoint.bytes;frameCount=$nodeBRecoveryCheckpoint.frameCount;recordFrames=$nodeBRecoveryCheckpoint.recordFrames;ackFrames=$nodeBRecoveryCheckpoint.ackFrames;pendingCount=$nodeBRecoveryCheckpoint.pendingCount;validation=$nodeBRecoveryCheckpoint.validation})
    $rollbackJournalCheckpoints=@($nodeARecoveryCheckpoint,$nodeBRecoveryCheckpoint)
    $script:pendingRecoveryAtGates.Add([int]($nodeARecoveryCheckpoint.pendingCount+$nodeBRecoveryCheckpoint.pendingCount))

    # Normal downgrade changes only the plugin artifacts. The pre-upgrade database/Redis backups are never restored.
    foreach($name in @('nodeA','nodeB')){Start-Node $name '1.3.2' ("rollback-"+$name);Invoke-NodeCommand $name 'smaccept rollback-read 1337.25 765.50' ("rollback-read-"+$name) 'T21_ROLLBACK_READ_PASS expected-a=1337.25 expected-b=765.50';Stop-Node $name ("rollback-"+$name);$null=Assert-RecoveryJournalCheckpointsUnchanged $rollbackJournalCheckpoints ("rollback-"+$name);$snap=Get-BackendSnapshot ("after rollback "+$name+" normal stop");Assert-Balances $snap '1337.25' '765.50' ("rollback "+$name);Assert-BackendVersions $snap 2 2 ("rollback "+$name);Assert-SnapshotSchemaRows $snap ("after rollback "+$name+" normal stop");$script:snapshots.Add($snap)}
    $rollbackAuditSnapshot=Get-AuditSnapshot 'after both normal rollback stops';Assert-AuditSnapshotRetained $candidateAuditSnapshot $rollbackAuditSnapshot
    $final=$script:snapshots[-1];foreach($name in @('AcceptanceA','AcceptanceB')){$pre=$newNodeSnapshot.accounts | Where-Object name -eq $name;$post=$final.accounts | Where-Object name -eq $name;if($pre.version -ne $post.version){throw "Read-only 1.3.2 rollback changed version for $name."}}
    $script:status='PASS_BOUNDED_QUIESCED_UPGRADE'
} catch {
    $script:failure=$_.Exception.Message
} finally {
    foreach($name in @('nodeA','nodeB')){
        try { if($script:nodes.Contains($name)){Stop-PossiblyStartedNode $name} } catch { if($null -eq $script:cleanupFailure){$script:cleanupFailure=$_.Exception.Message}else{$script:cleanupFailure += ' | '+$_.Exception.Message} }
    }
    try { Stop-Redis 'final-cleanup' } catch { if($null -eq $script:cleanupFailure){$script:cleanupFailure=$_.Exception.Message}else{$script:cleanupFailure += ' | '+$_.Exception.Message} }
    try { if($script:pgStarted){Stop-Postgres 'final-cleanup'} } catch { if($null -eq $script:cleanupFailure){$script:cleanupFailure=$_.Exception.Message}else{$script:cleanupFailure += ' | '+$_.Exception.Message} }
    try { Invoke-GradleCleanup } catch { if($null -eq $script:cleanupFailure){$script:cleanupFailure=$_.Exception.Message}else{$script:cleanupFailure += ' | '+$_.Exception.Message} }
    if ($script:status -ceq 'PASS_BOUNDED_QUIESCED_UPGRADE' -and -not (Test-T21BackendShutdownCoverage $script:backendShutdown.ToArray())) {
        if ($null -eq $script:cleanupFailure) { $script:cleanupFailure='Final isolated PostgreSQL/Redis shutdown evidence is incomplete or unverified.' }
        else { $script:cleanupFailure += ' | Final isolated PostgreSQL/Redis shutdown evidence is incomplete or unverified.' }
    }
    if($script:failure -or $script:cleanupFailure){$script:status='FAIL_OR_INCOMPLETE'}
    try { Save-Report } catch { if($null -eq $script:cleanupFailure){$script:cleanupFailure=$_.Exception.Message}else{$script:cleanupFailure += ' | '+$_.Exception.Message} }
    foreach($name in $environmentNames){[Environment]::SetEnvironmentVariable($name,$script:originalEnvironment[$name],'Process')}
}

Write-Output (Join-Path $script:runRoot 'result.json')
if($script:cleanupFailure){throw "Quiesced network-upgrade cleanup failed: $($script:cleanupFailure)"}
if($script:failure){throw "Quiesced network-upgrade run failed: $($script:failure)"}
