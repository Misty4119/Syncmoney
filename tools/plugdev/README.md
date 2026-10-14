# PlugDev acceptance for Syncmoney 1.4.0

Use the local PlugDev source checkout and its CLI build. Do not install or invoke the public npm package. The profile helper writes only ignored `.plugdev/` state and refuses to prepare a profile while a PlugDev server session is running. It pins the candidate JAR, VaultUnlocked 2.20.2, and acceptance version; PlugDev runs Gradle `help` and deploys that exact JAR without rebuilding it.

## Minimum Paper smoke

Use Java 25 and build the candidate and acceptance probe first. `PLUGDEV_ROOT` is needed when this repository is running in a worktree that is not beside the local PlugDev checkout.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:PLUGDEV_ROOT = 'C:\path\to\plugdev-main'

.\gradlew.bat -PreleaseVersion=1.4.0-alpha.1 test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar apiConsumerJar jacocoTestReport :syncmoney-papi-expansion:jacocoTestReport
$acceptanceLog = '.plugdev/run/logs/latest.log'
function Get-ServerLogLineCount {
    param([Parameter(Mandatory)][string]$Path)
    if (Test-Path -LiteralPath $Path) { return @(Get-Content -LiteralPath $Path).Count }
    return 0
}
function Wait-ServerLogMarker {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Pattern,
        [string]$FailurePattern,
        [int]$AfterLine = 0,
        [int]$TimeoutSeconds = 60
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $allLines = @(Get-Content -LiteralPath $Path -ErrorAction SilentlyContinue)
        $skip = if ($allLines.Count -ge $AfterLine) { $AfterLine } else { 0 }
        $sessionLines = @($allLines | Select-Object -Skip $skip)
        if ($FailurePattern) {
            $failure = @($sessionLines | Select-String -Pattern $FailurePattern | Select-Object -First 1)
            if ($failure.Count -gt 0) { throw "Server reported failure marker: $($failure[0].Line)" }
        }
        $match = @($sessionLines | Select-String -Pattern $Pattern | Select-Object -Last 1)
        if ($match.Count -gt 0) { return $match[0].Line }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for server log marker '$Pattern' in $Path"
}
node tools/plugdev/prepare.mjs paper 1.21.6 local off 1.4.0-alpha.1
$beforeStart = Get-ServerLogLineCount -Path $acceptanceLog
node "$env:PLUGDEV_ROOT\packages\cli\dist\cli.js" server start --config .plugdev/profile.yml
Wait-ServerLogMarker -Path $acceptanceLog -Pattern 'API CLASSLOADER PASS' -FailurePattern 'API CLASSLOADER FAIL' -AfterLine $beforeStart
node "$env:PLUGDEV_ROOT\packages\cli\dist\cli.js" server command "version"
node "$env:PLUGDEV_ROOT\packages\cli\dist\cli.js" server command "plugins"
$beforeAcceptance = Get-ServerLogLineCount -Path $acceptanceLog
node "$env:PLUGDEV_ROOT\packages\cli\dist\cli.js" server command "smaccept"
# The RCON reply only means the asynchronous acceptance command was queued.
Wait-ServerLogMarker -Path $acceptanceLog -Pattern 'ACCEPTANCE PASS' -FailurePattern 'ACCEPTANCE FAIL' -AfterLine $beforeAcceptance
node "$env:PLUGDEV_ROOT\packages\cli\dist\cli.js" server stop

# Start the same profile again after the first graceful stop.
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
node "$env:PLUGDEV_ROOT\packages\cli\dist\cli.js" server start --config .plugdev/profile.yml
$beforeRead = Get-ServerLogLineCount -Path $acceptanceLog
node "$env:PLUGDEV_ROOT\packages\cli\dist\cli.js" server command "smaccept read"
Wait-ServerLogMarker -Path $acceptanceLog -Pattern 'ACCEPTANCE READ a=' -FailurePattern 'ACCEPTANCE READ FAIL' -AfterLine $beforeRead
node "$env:PLUGDEV_ROOT\packages\cli\dist\cli.js" server stop
```

The generated profile disables PlugDev's idle/orphan auto-stop so the detached server remains available for the RCON acceptance commands. `smaccept` runs asynchronously: its RCON response is not completion evidence. Capture the current log line count before server start and each asynchronous `smaccept` command, then wait for a new success marker (the sample above also fails immediately on its matching failure marker) before stopping the server. The first `smaccept` call seeds only the synthetic `AcceptanceA` and `AcceptanceB` accounts and checks Vault and VaultUnlocked service registration plus deposit, withdrawal, transfer, and insufficient-funds behavior. The probe waits up to 10 seconds for the existing asynchronous account loader before seeding; it never turns a cold mutation into a synchronous load. After graceful stop and restart, verify the `ACCEPTANCE READ` line reports `cold-vault-a=NaN`, `cold-vault-unlocked-b=null`, `a=1000`, `b=520`, and `vault-unlocked-b=520`; the command response alone is not the assertion. The cold getter checks use separate accounts so the first asynchronous load cannot warm the second check. Check the normal shutdown log for persistence completion. Unit tests separately verify that cold reads return while the cache/SQL loader is blocked. Record the exact Paper build, Java version, Syncmoney JAR SHA-256, VaultUnlocked JAR version/hash, startup/shutdown results, and restart balances. Do not copy server config, RCON credentials, logs, worlds, or `.plugdev/` data into Git.

## Exact server artifact pin and migration cycle

Set `SYNCMONEY_TEST_SERVER_JAR` to an already verified local server JAR when the matrix requires a beta build or an exact build rather than PlugDev's current stable resolver. The helper hashes the file, configures PlugDev's built-in `custom` provider to use that exact artifact, and continues to run Gradle `help` so the plugin JAR is not rebuilt or substituted. This does not change the project minimum or assert support; record the source build/channel and compare the artifact hash with the official manifest before starting.

`run-upgrade-cycle.ps1` creates an atomically claimed, GUID-named disposable project under `build/reports/foundation/T21-upgrade-cycles`, rejects reparse-point ancestors, validates version/path inputs, requires exact server and baseline plugin SHA-256 pins, verifies the 1.3.2 source checkout commit, requires Java 25 and the local `sqlite3` CLI, and invokes only the local PlugDev checkout. It sets both `PLUGDEV_HOME` and `GRADLE_USER_HOME` below that run directory. Pass the verified T00/T19 baseline artifact; do not use a same-named JAR from the 1.4 worktree. It imports `syncmoney-testkit/src/testFixtures/resources/sql/legacy-local-sqlite-v1.3.2.sql` into a custom configured SQLite path, verifies the historical `REAL` balance column and transaction row, then checks Syncmoney itself reads both fixture accounts under 1.3.2, 1.4, and rollback to 1.3.2. The candidate writes distinct balances through the existing acceptance-only admin probe; after each normal stop the helper checks SQLite values and the unchanged historical transaction. It also checks the actual config schema/path and a synthetic unknown operator config entry across versions. The cycle's version, read, and write probes validate the plugin descriptor version; the separate T15 lifecycle probe is not part of this migration cycle. The 1.4 candidate stage also requires the compileOnly API consumer to log `API CLASSLOADER PASS`. Normal stops use RCON only after the session PID is matched to the run-marked Java process. Failure cleanup force-stops only Java processes whose command line contains the unique run marker. Logs, configs, worlds, caches, and database files stay under ignored build output; the result manifest records observed hashes, isolated cache homes, config values, SQLite rows, stop exit codes, and cleanup results.

Example for the minimum endpoint (use the verified local artifacts from the T15 manifest):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\tools\plugdev\run-upgrade-cycle.ps1 `
  -Project paper -MinecraftVersion 1.21.6 -BuildNumber 48 `
  -ServerJar 'C:\absolute\path\paper-1.21.6-48.jar' `
  -ExpectedServerSha256 '35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214' `
  -BaselinePluginJar 'C:\Users\margo\.codex\worktrees\syncmoney-132-baseline\Syncmoney\build\libs\Syncmoney-1.3.2.jar' `
  -ExpectedBaselinePluginSha256 '442a404276b538ce807b0f063fb37920be85e60961a52a475b3f3524d08f4f10' `
  -BaselineSourceCommit '71cc55dfeefe9a8f40a3835b051ddd324d353767' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME
```

The acceptance probe supports `smaccept legacy-sqlite [read [balance-a balance-b]|write balance-a balance-b]` for the two fixed fixture UUIDs, plus `smaccept read` and `smaccept write` for the existing disposable accounts. These commands do not address arbitrary players.

## Confirmed platform endpoints and runtime smoke

The T21 target range is Paper `1.21.6#48`–`26.3#166`, Folia `1.21.6#6`–`26.2#7`, and Canvas `1.21.8#603`–`26.3#972`. These exact artifacts were pinned for T15 smoke; that evidence covers startup/provider/profile behavior only and does not establish Folia/Canvas entity behavior or T21 migration coverage. The required hashes are recorded in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md` and its T15 artifact manifest.

Paper `26.3#175` was separately run as the then-current stable build and remains historical supplemental evidence outside the user-confirmed T21 target range, whose Paper maximum is `26.3#166`. Official Fill API v3 later identified Paper `26.3#178` as current stable; its exact JAR and hash are pinned in the T21 artifact manifest and its M04/M05/M06 results are supplemental. Neither stable build changes the confirmed target range or a compatibility claim. Before a target run, verify the exact JAR against the pinned official SHA-256, set `SYNCMONEY_TEST_SERVER_JAR` to that file, then call `prepare.mjs` with its Minecraft version. The helper accepts numeric versions such as `paper 26.3`, `folia 26.2`, and `canvas 26.3`; a version label alone does not pin a build.

```powershell
node tools/plugdev/prepare.mjs paper 26.2 local off 1.4.0-alpha.1
node tools/plugdev/prepare.mjs folia 26.2 local off 1.4.0-alpha.1
node tools/plugdev/prepare.mjs canvas 26.2 local off 1.4.0-alpha.1
```

For the user-confirmed maximum endpoints, use the exact JARs from the T15 artifact manifest with `SYNCMONEY_TEST_SERVER_JAR` set before each command: Paper `26.3#166`, Folia `26.2#7`, and Canvas `26.3#972`. T21 M04 LOCAL SQLite upgrade/drain/rollback cycles passed on all six user-supplied endpoints; supplemental M04 and M06 current-stable Paper `26.3#178` reruns also passed. The two-node SYNC M01–M03 bounded cycle passed on Paper `1.21.6#48`, Paper `26.3#166`, Folia `1.21.6#6` and `26.2#7`, and Canvas `1.21.8#603` and `26.3#972`. Earlier `26.3#175` cycles remain supplemental historical observations. These bounded cycles do not complete the V03 mode/backend/codec/failure matrix or establish fleet-wide quiescence. Exact run manifests and hashes are in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`.

`prepare.mjs` rejects Paper versions below 1.21.6. `local` disables Redis and SQL; `local_redis` and `sync` require isolated local services as described by the test profile, never production services. `features=on` enables optional test modules and creates a random local Web Admin key; do not expose that server.

`run-sync-upgrade-cycle.ps1` runs a bounded M01–M03 SYNC cycle against one or two sequential node identities with isolated PostgreSQL and Redis: 1.3.2 baseline, 1.4 Vault-provider writes, normal drain, backend/config verification, then normal 1.3.2 rollback. Pass `-PlatformSoftware` (`paper`, `folia`, or `canvas`), `-MinecraftVersion`, and `-PlatformBuildNumber` with the exact server JAR path and SHA-256 pin, baseline paths and pins, baseline source commit, local PlugDev checkout, and Java 25. The runner checks the startup log against the requested platform identity at every stage and derives its report label from that observed runtime. For each baseline, candidate, and rollback start it requires exactly one deployed Syncmoney JAR with the expected filename and SHA-256; the pass gate also requires exact runtime and normal-shutdown stage coverage for every node. It preserves a synthetic operator config entry and requires each candidate `recovery-v1.jsonl` to exist, contain validated frames, pass format/checksum validation, and have zero pending records before rollback; after rollback it revalidates each journal and requires its SHA-256 to remain unchanged. Shutdown checks detect numeric stale-transfer and audit-flush failure markers. `PASS_BOUNDED_SUBCASE` is bounded evidence for the selected endpoint only; it does not close M01–M03 or T21's fleet, mode, backend, failure, scheduler, or platform matrix. Final cleanup stops Gradle daemons only when their command lines match this run's isolated Gradle User Home, verifies they exit, and records the before/after PIDs and stop result. Stop, process-query, or timeout failures fail the cycle. Run-owned server fallback cleanup stops only a uniquely identified PID and verifies process exit. Verify these runner checks with `pwsh -NoProfile -File tools/plugdev/t21-upgrade-validation.test.ps1`, the cleanup policy with `pwsh -NoProfile -File tools/plugdev/run-sync-upgrade-cleanup.test.ps1`, and recovery journal validation with `pwsh -NoProfile -File tools/plugdev/recovery-spool-validator.test.ps1`.

`run-quiesced-network-upgrade.ps1` runs the bounded two-node M01–M03 SYNC baseline → candidate → normal rollback cycle and requires all synthetic nodes stopped with verified drain before backup. It records four candidate owner-drain reports and never restores pre-upgrade backups for normal rollback. The run is limited to the two synthetic Syncmoney nodes and its acceptance probe; it does not establish production third-party writer quiescence. Pass `-EnableAudit` to enable SQL audit and Redis hybrid-audit only, leaving cleanup/export and other optional modules disabled. That profile verifies each disposable account gained persisted audit rows after the candidate write and retained them through rollback; its result report records the audit profile and per-stage counts. Audit remains disabled by default.

## T21 M05 SYNC SQL runtime cells by platform

The focused runner verifies exact pinned platform endpoints with `-PlatformEndpoint`. `Minimum`, `Maximum`, and `CurrentStable` select Paper `1.21.6#48`, Paper `26.3#166`, and supplemental stable Paper `26.3#178`. `MinimumFolia` and `MaximumFolia` select Folia `1.21.6#6` and `26.2#7`; `MinimumCanvas` and `MaximumCanvas` select Canvas `1.21.8#603` and `26.3#972`. Paper `-PaperEndpoint` remains an alias for existing invocations. The current-stable Paper build is supplemental and does not change the user-confirmed maximum. Select MySQL `8.0.42`, MariaDB `12.3.3`, or PostgreSQL `18.1` with `-DatabaseProduct`; PostgreSQL uses `-PostgreSqlBin` to locate `initdb`, `pg_ctl`, `psql`, and `postgres`.

Each run starts Syncmoney in `SYNC` with config schema 12 and isolated loopback SQL/Redis, performs two accepted Vault deposits through the registered provider, stops normally, checks SQL/Redis balances and versions, restarts for a persisted read, and stops normally again. After each normal stop it copies that session's server `latest.log` into the run-owned log directory. The report gate requires the exact pinned server and candidate JAR hashes, successful cold-read semantics, complete SQL/Redis parity, observed process exit codes, backend ownership and graceful shutdown, both stage-specific server logs, and verified cleanup of any Gradle daemon under the run's isolated Gradle User Home. For PostgreSQL startup-timeout cleanup, process ownership can be recovered from `postmaster.pid` before listener creation, but shutdown proceeds only after validating the PID, process start time, executable, data directory, port, and `-D`/`-p` arguments; failed identity checks fail closed.

New schema-4 reports use `platformLogs`; historical schema-3 Paper reports remain valid with `paperLogs`. Both formats require the `logs/` manifest to match every retained file and recompute every file's byte count and SHA-256. For schema 4, the validator also parses both server startup logs and requires the recorded platform/version/build to match the selected pinned endpoint; missing, conflicting, or relabeled platform identities are rejected. It checks the captured write/read markers and configured SQL product identity, and rejects a surviving Gradle daemon. Paper M05 runtime cells cover minimum and maximum Paper for all three SQL dialects plus supplemental current-stable Paper for all three. The user-range Folia/Canvas endpoints now also have bounded `SYNC` runtime cells for MySQL `8.0.42`, MariaDB `12.3.3`, and PostgreSQL `18.1`; the exact endpoint builds, report hashes, and offline audits are in the T21 M05 section of the foundation validation plan. These are bounded observations, not coverage of other modes/resources, schema migration, fault injection, or all remaining T21 gates.

Build the pinned candidate and prepare the local PlugDev checkout and exact server JAR from the T15 artifact table in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`. The runner uses the repository-local PlugDev source; do not substitute a public npm package.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$paperJar = (Resolve-Path 'build\reports\foundation\T21-inputs\paper-1.21.6-48.jar').Path
$plugDevRoot = 'C:\Users\margo\IdeaProjects\plugdev-main'
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $paperJar `
  -ExpectedServerSha256 '35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214' `
  -ExpectedCandidateSha256 '5fdc79aae7ae715f6fcdb3cddbb858ba001855420755c46458102979ca495dbd' `
  -PlugDevRoot $plugDevRoot `
  -JavaHome $env:JAVA_HOME `
  -PlatformEndpoint Minimum
```

For Folia or Canvas, set `-PlatformEndpoint` to the exact selector above and pass the matching SHA-256-pinned JAR from the T15 endpoint table. For PostgreSQL, add `-PostgreSqlBin 'C:\Program Files\PostgreSQL\18\bin' -DatabaseProduct PostgreSQL`. The run writes its machine report and per-run logs under ignored `build/reports/foundation/T21-sql-runtime/`. Run `pwsh -NoProfile -File tools/plugdev/t21-upgrade-validation.test.ps1` to check the report predicates and runner regressions. Accepted bounded reports and their exact artifact, source, and validation hashes are recorded in the T21 M05 evidence section of `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`. Each bounded result leaves all untested cells `NOT RUN`; T21 remains in progress.

### MySQL, MariaDB, and PostgreSQL legacy-data compatibility on Paper, Folia, and Canvas

Pass `-LegacySqlDataUpgrade`, a verified 1.3.2 baseline JAR, and its SHA-256 to exercise the legacy-data lifecycle. The runner accepts MySQL `8.0.42`, MariaDB `12.3.3`, and PostgreSQL `18.1` on the exact pinned Paper (`Minimum` `1.21.6#48`, user-pinned `Maximum` `26.3#166`), Folia (`MinimumFolia` `1.21.6#6`, `MaximumFolia` `26.2#7`), and Canvas (`MinimumCanvas` `1.21.8#603`, `MaximumCanvas` `26.3#972`) endpoints. Supplemental Paper `CurrentStable` (`26.3#178`) evidence is also recorded and does not change the user-confirmed maximum. Select MariaDB with `-DatabaseProduct MariaDB` and provide `-MariaDbBin`; its report identifies the product as MariaDB while Syncmoney configuration correctly remains `database.type: mysql`. Select PostgreSQL with `-DatabaseProduct PostgreSQL` and provide `-PostgreSqlBin`. The lifecycle seeds two disposable account rows under 1.3.2, verifies balances and versions through candidate startup/read and 1.3.2 rollback, and compares the audit schema at each stage. The observed MySQL candidate additively repairs three missing audit indexes. The pinned PostgreSQL and MariaDB 1.3.2 baselines already contain their expected nine-index sets; their predicates require those exact sets to remain unchanged through candidate read and rollback. PostgreSQL inventory retains expression-index keys and records `indisprimary`. MySQL, MariaDB, and PostgreSQL have product-specific report predicates and native audit metadata capture. Current bounded E2 legacy-data evidence covers all three SQL products on the user-pinned minimum and maximum Paper/Folia/Canvas endpoints, plus all three products on supplemental current-stable Paper `26.3#178`. These are bounded lifecycle observations; they do not establish schema migration, other modes, or the remaining T21 gates.

Example for the maximum Paper endpoint (set `$baselineJar` to the locally verified 1.3.2 artifact):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$paperJar = (Resolve-Path 'build\reports\foundation\T21-artifacts\paper-26.3-166.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $paperJar `
  -ExpectedServerSha256 'dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd' `
  -ExpectedCandidateSha256 '5fdc79aae7ae715f6fcdb3cddbb858ba001855420755c46458102979ca495dbd' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -PlatformEndpoint Maximum `
  -LegacySqlDataUpgrade `
  -BaselinePluginJar $baselineJar `
  -ExpectedBaselinePluginSha256 '442a404276b538ce807b0f063fb37920be85e60961a52a475b3f3524d08f4f10'
```

For supplemental current-stable Paper, use `paper-26.3-178.jar`, its pinned SHA-256 `5dd0c8fb20a96eb7c98f3c4b1c890e99ef6db56fd689a2cf06975a3471fda757`, and `-PlatformEndpoint CurrentStable`.

Run both focused validators and the report-specific offline audit after a runtime cycle. The final minimum-, maximum-, and current-stable Paper reports, source pins, test outputs, audit hashes, and evidence limits are recorded in the T21 M05 legacy SQL compatibility sections of the foundation validation plan.

## T21 player lifecycle scheduler matrix

Rebuild `acceptanceJar` after changing the acceptance probe, then run the exact six endpoint pins from the user-confirmed range with Java 25 and the local PlugDev and Mineflayer checkouts:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$endpoints = @('paper-1216-min', 'paper-263-max', 'folia-1216-min', 'folia-262-max', 'canvas-1218-min', 'canvas-263-max')
foreach ($endpoint in $endpoints) {
  .\tools\plugdev\run-player-scheduler-lifecycle.ps1 -Endpoint $endpoint `
    -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
    -MineflayerRoot 'C:\Users\margo\IdeaProjects\mineflayer-master'
}
```

The runner verifies each exact server JAR SHA-256 and the pinned candidate plugin SHA-256, creates a unique run directory, and isolates the project, PlugDev home, Gradle user home, and Redis database 15. Each endpoint run starts one server in `LOCAL_REDIS`, pauses its isolated Redis for 2.5 seconds, and runs two offline Mineflayer clients using that endpoint's protocol version. Teleport passes only with exactly one successful `teleportAsync` future, a successful non-cancelled Bukkit teleport-event path or destination confirmation on the entity scheduler for an eventless path, and client observation of the Nether dimension. Logout passes only when the server log contains the exact expected kick reason for `AcceptanceA`; Mineflayer may report either the exact kick packet or `ECONNABORTED` after the command-triggered disconnect. The acceptance log must show four successful Vault deposits on the synthetic account (two before each action) and at least two accepted writes still incomplete at each player-scheduler action, including writes already dequeued by the worker; normal shutdown must log `Syncmoney disabled.` and leave Redis at `4.00/v4`. The runtime Redis server version and account version are reported separately. Server cleanup uses the unique run marker and PlugDev session PID; Redis cleanup uses the per-run config name and retained process ID. Results and logs are saved under ignored `build/reports/foundation/T21-player-scheduler/`.

The standard lifecycle run without `-CrossOwner` is bounded player lifecycle evidence. It does not establish Folia two-region cross-owner behavior, CMI callback retirement, hard-kill recovery, fleet-wide quiescence, or the remaining backend/failure matrix. The separate Folia gate below covers only its two-player current-region ownership assertions. Any unrun endpoint remains `NOT RUN`; a failed runner report is not acceptance evidence.

For the bounded Canvas player-guard case, run the same harness with `-Guard` on the user-pinned minimum and maximum Canvas builds:

```powershell
foreach ($endpoint in @('canvas-1218-min', 'canvas-263-max')) {
  .\tools\plugdev\run-player-scheduler-lifecycle.ps1 -Endpoint $endpoint -Guard `
    -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
    -MineflayerRoot 'C:\Users\margo\IdeaProjects\mineflayer-master'
}
```

This keeps `AcceptanceGuardA` and `AcceptanceGuardB` online, moves B 16,384 blocks, and runs each participant's six Vault deposits on that player's entity scheduler. Each callback must report ownership of its own player and not its peer; each player's independent quota must produce `SSSSSF`, a targeted player notice, and Redis `5.00/v5`. The offline proof requires canonical UUIDs and exactly one rejection warning per participant. This is a bounded two-owner quota observation only; it does not establish the wider Canvas owner/guard lifecycle matrix or Paper/Folia behavior.

After selecting the six endpoint reports, run `node tools/plugdev/audit-player-scheduler-lifecycle.mjs` to re-check their exact report hashes, teleport-future and player-specific logout evidence, Redis binary version, and stopped Redis data/AOF file hashes. The auditor writes a separate correction record and leaves historical report and audit bytes unchanged.

For the Folia two-region scheduler gate, add `-CrossOwner` when running `run-player-scheduler-lifecycle.ps1`. The runner rejects this switch for Paper and Canvas before creating a run directory or starting services. It keeps offline `AcceptanceA` and `AcceptanceB` connected, moves B 16,384 blocks in the Overworld, and waits for acceptance-side entity-scheduler checks from both players. Each check must observe ownership of its own player and no ownership of the other player's chunk; each player's entity scheduler then sends that player a targeted completion marker, and the client run passes only after both clients receive their own marker. The offline verifier also checks that both location snapshots match, are at least 4,096 blocks apart, and belong to distinct chunks. It rejects duplicate or suffixed proof markers and any `T21_CROSS_OWNER_FAIL` line. Run this gate on the user-pinned minimum and maximum Folia builds. An absent owner result, retired player, canceled teleport, shared region, or malformed log remains a failure.

The T21/M12 observation runner validates the candidate's existing `recovery-v1.jsonl` during normal shutdown with `recovery-spool-validator.ps1`. The validator is read-only and does not replay, repair, compact, or alter the recovery journal. T21 upgrade-cycle reports pin the validator and test SHA-256 values alongside the ignored run-specific harness hash and include pre-/post-rollback per-node journal observations. Run the dedicated AOF acceptance-marker contract check with `pwsh -NoProfile -File tools/plugdev/run-redis-aof-restart.test.ps1`; it requires the current Vault receipt fields and rejects the old write marker.

## T21 M05 minimum-Paper MariaDB SYNC runtime cell

The MariaDB sibling cell exercises Paper `1.21.6#48`, Java 25, MariaDB `12.3.3-MariaDB` through the MySQL protocol, and isolated Redis `8.10.1`/DB 15. It accepts two Vault deposits, stops normally, checks SQL/Redis balance and version parity, restarts for persisted reads, then stops normally again. This is one bounded runtime observation; it does not establish MariaDB coverage on other Paper/Folia/Canvas endpoints or close the wider T21 matrix.

Use the verified local PlugDev source and exact minimum Paper artifact:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$paperJar = (Resolve-Path 'build\reports\foundation\T21-inputs\paper-1.21.6-48.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $paperJar `
  -ExpectedServerSha256 '35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214' `
  -ExpectedCandidateSha256 '5fdc79aae7ae715f6fcdb3cddbb858ba001855420755c46458102979ca495dbd' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -DatabaseProduct MariaDB `
  -MariaDbBin 'C:\Users\margo\scoop\apps\mariadb\current\bin'
```

The runner limits the database to loopback and explicitly disables MariaDB's default server-certificate verification option so its warning cannot contaminate machine-parsed query output. This option is used only for MariaDB client calls in this isolated local runtime; it does not provide remote TLS coverage. Run `pwsh -NoProfile -File tools/plugdev/t21-upgrade-validation.test.ps1` for the report-predicate and runner regression checks. The final minimum-Paper report and offline audit are recorded in the T21 M05 MariaDB section of `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`. Other untested combinations remain `NOT RUN`.
