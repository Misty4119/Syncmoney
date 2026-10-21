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

`run-upgrade-cycle.ps1` creates an atomically claimed, GUID-named disposable project under `build/reports/foundation/T21-upgrade-cycles`, rejects reparse-point ancestors, validates version/path inputs, requires exact server and baseline plugin SHA-256 pins, verifies the 1.3.2 source checkout commit, requires Java 25 and the local `sqlite3` CLI, and invokes only the local PlugDev checkout. It sets both `PLUGDEV_HOME` and `GRADLE_USER_HOME` below that run directory. Pass the verified T00/T19 baseline artifact; do not use a same-named JAR from the 1.4 worktree. Server, baseline, candidate, acceptance, and API-consumer JARs are copied into the isolated run; their staged byte counts and hashes are checked before and after each PlugDev CLI invocation and included in the result manifest. After `prepare.mjs` copies the acceptance and API-consumer JARs into `.plugdev/run/plugins`, the runner also pins those loaded copies against the staged build artifacts and includes both paths in the artifact manifest. Every later PlugDev invocation and report emission rechecks the loaded copies. The runtime uses the staged server JAR. The runner requires a clean local PlugDev checkout and hashes every file under `packages/cli/dist`; it rechecks that manifest before and after each CLI invocation. It imports `syncmoney-testkit/src/testFixtures/resources/sql/legacy-local-sqlite-v1.3.2.sql` into a custom configured SQLite path, verifies the historical `REAL` balance column and transaction row, then checks Syncmoney itself reads both fixture accounts under 1.3.2, 1.4, and rollback to 1.3.2. The candidate writes distinct balances through the existing acceptance-only admin probe; after each normal stop the helper checks SQLite values and the unchanged historical transaction. It also checks the actual config schema/path and a synthetic unknown operator config entry across versions. The cycle's version, read, and write probes validate the plugin descriptor version; the separate T15 lifecycle probe is not part of this migration cycle. The 1.4 candidate stage also requires the compileOnly API consumer to log `API CLASSLOADER PASS`. Normal stops use RCON only after the session PID is matched to the run-marked Java process. Failure cleanup force-stops only Java processes whose command line contains the unique run marker. The runner records its Java and isolated Gradle-daemon cleanup; a separate post-run process query must be described separately and is not represented by those runner checks. Logs, configs, worlds, caches, and database files stay under ignored build output; the result manifest records artifact/source pins, isolated cache homes, config values, SQLite rows, stop exit codes, and cleanup results.

`-GuardProfile guard-on` is an additional bounded M04 profile. It keeps `LOCAL`, disables Redis/shared SQL and optional modules, enables per-player protection, and disables both the global circuit breaker and global lock. The runner asserts those exact config values at all six preparation/baseline/candidate/rollback config observations and records them with source pins and a SHA-256/byte-count manifest for every retained runner log. The offline audit also enforces the exact baseline start/stop → candidate upgrade start/stop → rollback start/stop chronology using the report-pinned shared validation predicate. Run the reusable offline audit after each cycle to independently rehash the staged artifacts, complete PlugDev distribution, and retained logs, and to query the final SQLite fixture state:

Before an expensive runtime cycle, verify that a missing offline auditor is rejected before the report root is created:

```powershell
pwsh -NoProfile -File tools/plugdev/run-upgrade-cycle.test.ps1
```

The M04 profile loads VaultUnlocked `2.20.2`. After its first startup, the runner records the PlugDev dependency-cache JAR and the runtime plugin copy, requires their byte counts and SHA-256 values to match, and checks the loaded version in each stage log. The offline auditor independently rehashes both JARs and rechecks the three startup logs. It also rehashes the Acceptance and API-consumer runtime copies and verifies each against its staged build artifact.

```powershell
pwsh -NoProfile -File tools/plugdev/t21-upgrade-cycle-audit.ps1 `
  -RunDirectory 'C:\absolute\path\to\the\run-directory' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main'
```

For Folia or Canvas, pass the matching `-PlatformEndpoint` selector. Historical selectors `MaximumFolia` and `MaximumCanvas` still identify Folia `26.2#7` and Canvas `26.3#972`; current maximum selectors are `CurrentMaximumFolia` and `CurrentMaximumCanvas` for Folia `26.3#3` and Canvas `26.3#984`. Paper uses `CurrentMaximum` for `26.3#200`. The offline auditor checks the report's endpoint/hash and actual startup identity in baseline, candidate, and rollback logs. Omitting the selector retains the minimum-Paper default; the existing `-PaperEndpoint` alias remains supported.

This verifies the selected profile and lifecycle only; the M04 probe does not exercise a player's rate-limit decision.

As of 2026-10-20, guard-on LOCAL SQLite normal-drain/rollback and shutdown-timeout-handoff/replay have separate reports on the formal current maxima. Seeded duplicate-receipt replay is accepted on the then-pinned Paper 1.21.6#48 / 26.3#166, Folia 1.21.6#6 / 26.2#7, and Canvas 1.21.8#603 / 26.3#972 endpoints, and on current-maximum Paper 26.3#200, Folia 26.3#3, and Canvas 26.3#984. Each current-maximum report-specific audit passed 446 assertions. These reports do not substitute for the distinct no-pre-existing-receipt schema-migration case.

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

### LOCAL SQLite shutdown-timeout handoff case

To exercise the graceful shutdown-timeout scenario, add `-GuardProfile guard-on -RecoveryScenario shutdown-timeout-handoff` to the `run-upgrade-cycle.ps1` invocation above. The runner holds an isolated SQLite WAL writer lock while two accepted LOCAL writes are pending through the 10-second drain deadline, verifies the checksummed journal handoff, releases the lock, and checks replay state before its first recovery balance read. This bounded scenario passed on the six endpoints pinned when those historical runs were performed: Paper `1.21.6#48` and `26.3#166`, Folia `1.21.6#6` and `26.2#7`, and Canvas `1.21.8#603` and `26.3#972`. Exact artifact/report/audit hashes and evidence limits are recorded in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`. These are graceful-stop observations; they do not establish forced-kill or power-loss durability.
The acceptance probe supports `smaccept legacy-sqlite [read [balance-a balance-b]|write balance-a balance-b]` for the two fixed fixture UUIDs, plus `smaccept read` and `smaccept write` for the existing disposable accounts. These commands do not address arbitrary players.

## Confirmed platform endpoints and runtime smoke

The current T21 formal endpoint range, updated by the user on 2026-10-17, is Paper `1.21.6#48`–`26.3#200`, Folia `1.21.6#6`–`26.3#3`, and Canvas `1.21.8#603`–`26.3#984`. The 2026-10-09 T15 artifact pins and previous maximum reports remain historical evidence for their exact builds. The new maximum JAR SHA-256 pins and runtime reports are recorded in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`. All nine current-maximum M05 `SYNC` SQL cells now have bounded report-specific audits; current-maximum M10, player-scheduler, and other T21 scenarios are separate and are not covered by those cells.

Paper `26.3#175` and `26.3#178` are historical supplemental stable-build observations. Paper `26.3#194` is the maintained `CurrentStable` supplemental selector with later bounded M04/M05/M06/V03 observations and SHA-256 `5fac0b6937b7aecea1b5b40e6a992f7b0802e4271413be35a6913d30df960784`. These builds do not replace the current formal Paper maximum `26.3#200`. Verify each exact JAR against its own SHA-256 before a run; a numeric version passed to `prepare.mjs` does not pin a build.

These are version-family preparation examples only; they do not select or pin the current maximum build. Use the current `26.3` family, then verify the exact server JAR and SHA-256 from the foundation validation plan before runtime acceptance:

```powershell
node tools/plugdev/prepare.mjs paper 26.3 local off 1.4.0-alpha.1
node tools/plugdev/prepare.mjs folia 26.3 local off 1.4.0-alpha.1
node tools/plugdev/prepare.mjs canvas 26.3 local off 1.4.0-alpha.1
```

The current user-confirmed maximum endpoints are Paper `26.3#200`, Folia `26.3#3`, and Canvas `26.3#984`. Use `CurrentMaximum`, `CurrentMaximumFolia`, and `CurrentMaximumCanvas` for T21 SQL validation; use the `*-263-currentmax` selectors for the M10 and player-scheduler runners. The M10 and player-scheduler runners resolve these artifacts under `build/reports/foundation/T21-sql-runtime/current-maximum-artifacts/` and verify each JAR against its endpoint pin. Existing `Maximum` and `*-max` selectors retain their previous exact-build mappings for report reproduction. Previously accepted T21 reports on Paper `26.3#166`, Folia `26.2#7`, and Canvas `26.3#972` remain historical bounded results for those exact builds. Supplemental Paper `26.3#194` reports remain outside the formal range. All nine current-maximum candidate-created-account M05 `SYNC` SQL cells and all three current-maximum M10 reconciliation cells have bounded report-specific audits. The 1.3.2-seeded M05 formal-maximum legacy-data matrix now has `PASS_BOUNDED_SUBCASE (E2)` evidence for all nine Paper/Folia/Canvas × MySQL/MariaDB/PostgreSQL cells; this matrix is complete and other T21 gates remain open. The current-maximum required-v11 DDL-denial row-preservation extension is also complete at 9/9 Paper/Folia/Canvas × MySQL/MariaDB/PostgreSQL cells, with one seeded row per report; it does not establish all-row preservation or whole-database atomic rollback. The three standard current-maximum player-scheduler cells have bounded runtime evidence; Folia current-maximum cross-owner and Canvas current-maximum paired-owner guard-plus-breaker profiles have each passed as separate bounded E2 scenarios. The current-maximum Canvas 26.3#984 breaker-only profile has passed as a separate PASS_BOUNDED_SUBCASE (E2); its report-specific offline audit passed 150 assertions. Four Redis persistence files were hashed but no AOF reload was performed. Other T21 scenarios remain open. Selector support alone does not count as runtime evidence.

## T21 same-version two-backend SYNC network acceptance

The network runner has endpoint selectors `paper-1216-48`, `paper-263-200`, `folia-1216-6`, `folia-263-3`, `canvas-1218-603`, and `canvas-263-984`. Use `--preflight-only` to check the pinned artifacts, local toolchain/backends, and temporary loopback port reservations without starting the network. A runtime run creates isolated PostgreSQL and Redis services and should use the local, pinned artifacts only:

```powershell
node tools/plugdev/t21-paper-network-runner.mjs --endpoint canvas-263-984 --preflight-only
node tools/plugdev/t21-paper-network-runner.mjs --endpoint canvas-263-984
node tools/plugdev/t21-paper-network-auditor.mjs <run-directory> canvas-263-984 <workspace-root>
```

As of 2026-10-20, all six formal endpoints have separate bounded accepted reports for the same-version, two-backend SYNC scenario. Each run observes cross-server payment/reconnect, PostgreSQL outage/recovery, and normal drain of accepted writes. The result is endpoint-specific; it does not establish mixed-build networking, live mixed-version write guarantees, or completion of T21. Report and audit identities are recorded in the Architecture Foundation validation plan.

`prepare.mjs` rejects Paper versions below 1.21.6. `local` disables Redis and SQL; `local_redis` and `sync` require isolated local services as described by the test profile, never production services. `features=on` enables optional test modules and creates a random local Web Admin key; do not expose that server.

`run-sync-upgrade-cycle.ps1` runs a bounded M01–M03 SYNC cycle against one or two sequential node identities with isolated PostgreSQL and Redis: 1.3.2 baseline, 1.4 Vault-provider writes, normal drain, backend/config verification, then normal 1.3.2 rollback. Pass `-PlatformSoftware` (`paper`, `folia`, or `canvas`), `-MinecraftVersion`, and `-PlatformBuildNumber` with the exact server JAR path and SHA-256 pin, baseline paths and pins, baseline source commit, local PlugDev checkout, and Java 25. The runner checks the startup log against the requested platform identity at every stage and derives its report label from that observed runtime. For each baseline, candidate, and rollback start it requires exactly one deployed Syncmoney JAR with the expected filename and SHA-256; the pass gate also requires exact runtime and normal-shutdown stage coverage for every node. It preserves a synthetic operator config entry and requires each candidate `recovery-v1.jsonl` to exist, contain validated frames, pass format/checksum validation, and have zero pending records before rollback; after rollback it revalidates each journal and requires its SHA-256 to remain unchanged. Shutdown checks detect numeric stale-transfer and audit-flush failure markers. `PASS_BOUNDED_SUBCASE` is bounded evidence for the selected endpoint only; it does not close M01–M03 or T21's fleet, mode, backend, failure, scheduler, or platform matrix. Final cleanup stops Gradle daemons only when their command lines match this run's isolated Gradle User Home, verifies they exit, and records the before/after PIDs and stop result. Stop, process-query, or timeout failures fail the cycle. Run-owned server fallback cleanup stops only a uniquely identified PID and verifies process exit. Verify these runner checks with `pwsh -NoProfile -File tools/plugdev/t21-upgrade-validation.test.ps1`, the cleanup policy with `pwsh -NoProfile -File tools/plugdev/run-sync-upgrade-cleanup.test.ps1`, and recovery journal validation with `pwsh -NoProfile -File tools/plugdev/recovery-spool-validator.test.ps1`.

`run-quiesced-network-upgrade.ps1` runs the bounded two-node M01–M03 SYNC baseline → candidate → normal rollback cycle and requires all synthetic nodes stopped with verified drain before backup. It records four candidate owner-drain reports and never restores pre-upgrade backups for normal rollback. The run is limited to the two synthetic Syncmoney nodes and its acceptance probe; it does not establish production third-party writer quiescence. Pass `-EnableAudit` to enable SQL audit and Redis hybrid-audit only, leaving cleanup/export and other optional modules disabled. That profile verifies each disposable account gained persisted audit rows after the candidate write and retained them through rollback; its result report records the audit profile and per-stage counts. Audit remains disabled by default.

## T21 M10 supplemental CurrentStable Paper reconciliation

The M10 two-node missed-Pub/Sub reconciliation runner has a separate `paper-263-currentstable` selector for Paper `26.3#194`. Its JAR is stored under `build/reports/foundation/T21-M10/artifacts/paper-26.3-194.jar` and pinned to SHA-256 `5fac0b6937b7aecea1b5b40e6a992f7b0802e4271413be35a6913d30df960784`. That accepted report is a historical supplemental observation and does not satisfy the updated formal maximum Paper `26.3#200`.

```powershell
pwsh -NoProfile -File tools/plugdev/run-m10-reconciliation.ps1 -Endpoint paper-263-currentstable
```

The runner uses only the configured local PlugDev and Mineflayer checkouts, isolated Redis DB 15, and run-owned server ports. It records the exact runner source inside each run report directory. Runtime output is under ignored `build/reports/foundation/T21-M10/runtime/`; acceptance remains bounded to this one reconnect/reconciliation scenario.

The accepted CurrentStable report is `build/reports/foundation/T21-M10/runtime/paper-263-currentstable-20261017-021441-e62a05ae5c2f4ca68f80af468e99a6e6/result.json` (SHA-256 `93ab68a595807b7cd4366dde46197a970cce504f6842a230bade925f5f5ce2ae`). Reproduce its offline audit with the configured local PlugDev and Mineflayer checkouts:

```powershell
pwsh -NoProfile -File tools/plugdev/m10-runtime-matrix-offline-audit.ps1 `
  -RepositoryRoot (Get-Location).Path `
  -SupplementalCurrentStableOnly `
  -CurrentStableRunId 'paper-263-currentstable-20261017-021441-e62a05ae5c2f4ca68f80af468e99a6e6'
```

This audit validates that supplemental run only and does not change the formal endpoint range. It verifies report, log, artifact/source, command-to-event, and cleanup provenance. It does not establish a bounded reconciliation deadline or live mixed-version safety.

## T21 M10 current-maximum Paper, Folia, and Canvas reconciliation

The current-maximum selectors are `paper-263-currentmax`, `folia-263-currentmax`, and `canvas-263-currentmax`. Each run uses the pinned server artifact under `build/reports/foundation/T21-sql-runtime/current-maximum-artifacts/`, the `1.4.0-alpha.1` candidate, isolated Redis DB 15, and the local PlugDev and Mineflayer checkouts. The runner sets `NODE_PATH` to the local Mineflayer checkout's `node_modules` directory for the client process and retains its stdout/stderr logs for failure diagnosis.

| Endpoint | Selector | Run ID | Runtime report SHA-256 | Offline audit |
|---|---|---|---|---|
| Paper `26.3#200` | `paper-263-currentmax` | `paper-263-currentmax-20261017-172728-4412e0d3111e4916aa9dc1f207c34632` | `ff62935f7f9172bc8e803ce194850a5ab1605801cf016608b4a604f210a67791` | PASS, 136 assertions |
| Folia `26.3#3` | `folia-263-currentmax` | `folia-263-currentmax-20261017-173357-aa736efb81d143099d453a87636c29c2` | `e1c3b85be1c4f1e10007858af84d6aba83906a50354660803bcd7b988c7caea1` | PASS, 136 assertions |
| Canvas `26.3#984` | `canvas-263-currentmax` | `canvas-263-currentmax-20261017-174026-4dda3dc3de654e9f9844e35d3be75127` | `cb62410cc59eb2a20a87d10798f22cce4a9d84173067d9bd62451bbfe8d79787` | PASS, 136 assertions |

Each accepted report records a `MESSAGE_MISSED_NO_REPLAY` observation, then a five-minute periodic reconciliation to Redis authority `3.00/v3`. Both nodes stopped normally with drain verified and game/RCON listeners closed; Redis shut down cleanly, the Mineflayer client exited gracefully, and no cleanup was forced. All three results are bounded M10 observations; they do not establish the remaining T21 modes, backends, failure matrix, separate Folia current-maximum cross-owner scheduler gate, or licensed CMI behavior.

Reproduce a report-specific offline audit by supplying its run ID, the pinned candidate hash, and the source commit recorded in that report:

```powershell
pwsh -NoProfile -File tools/plugdev/m10-runtime-matrix-offline-audit.ps1 `
  -RepositoryRoot (Get-Location).Path `
  -CurrentMaximumOnly `
  -CurrentMaximumRunId 'paper-263-currentmax-20261017-172728-4412e0d3111e4916aa9dc1f207c34632' `
  -CurrentMaximumCandidateSha256 '7d4fb070ecdb61ece3d5e39b7a2bf6ceb72c0c6a939f269588344b41f908b354' `
  -CurrentMaximumSourceCommit 'e093d59d03bf55ddb607fb0a205d70a5c1cc3417'
```

Replace the endpoint run ID with the Folia or Canvas ID above to audit that report. The offline audit validates only the selected report and its retained provenance.

## T21 M05 SYNC SQL runtime cells by platform

The focused runner retains historical selectors for report reproduction: `Minimum` / `Maximum` / `CurrentStable` map to Paper `1.21.6#48` / `26.3#166` / `26.3#194`; `MinimumFolia` / `MaximumFolia` map to Folia `1.21.6#6` / `26.2#7`; and `MinimumCanvas` / `MaximumCanvas` map to Canvas `1.21.8#603` / `26.3#972`. It also provides `CurrentMaximum`, `CurrentMaximumFolia`, and `CurrentMaximumCanvas` for the 2026-10-17 pins Paper `26.3#200`, Folia `26.3#3`, and Canvas `26.3#984`. All nine current-maximum M05 SQL cells (three platforms × MySQL `8.0.42`, MariaDB `12.3.3-MariaDB`, and PostgreSQL `18.1`) now have `PASS_BOUNDED_SUBCASE` reports and report-specific offline audits. These do not cover other T21 modes, resources, failure cases, or gates. Paper `-PaperEndpoint` remains an alias for existing invocations.

Each run starts Syncmoney in `SYNC` with config schema 12 and isolated loopback SQL/Redis, performs two accepted Vault deposits through the registered provider, stops normally, checks SQL/Redis balances and versions, restarts for a persisted read, and stops normally again. After each normal stop it copies that session's server `latest.log` into the run-owned log directory. The report gate requires the exact pinned server and candidate JAR hashes, successful cold-read semantics, complete SQL/Redis parity, observed process exit codes, backend ownership and graceful shutdown, both stage-specific server logs, and verified cleanup of any Gradle daemon under the run's isolated Gradle User Home. For PostgreSQL startup-timeout cleanup, process ownership can be recovered from `postmaster.pid` before listener creation, but shutdown proceeds only after validating the PID, process start time, executable, data directory, port, and `-D`/`-p` arguments; failed identity checks fail closed.

New schema-4 reports use `platformLogs`; historical schema-3 Paper reports remain valid with `paperLogs`. Both formats require the `logs/` manifest to match every retained file and recompute every file's byte count and SHA-256. For schema 4, the validator parses both server startup logs and requires the recorded platform/version/build to match the selected pinned endpoint; missing, conflicting, or relabeled platform identities are rejected. It also requires the API consumer to report `PASS` on both starts, confirms each marker in its corresponding retained startup log with no API-consumer failure marker, and binds the recorded API-consumer SHA-256 to the caller-supplied staged-artifact hash. The predicate checks the captured write/read markers and configured SQL product identity, and rejects a surviving Gradle daemon. Historical M05 candidate-created-account `SYNC` cells and the nine current-maximum candidate-created-account M05 `SYNC` SQL cells have bounded runtime evidence; the separate 1.3.2-seeded current-maximum legacy-data matrix now has all nine Paper/Folia/Canvas × MySQL/MariaDB/PostgreSQL cells accepted as bounded E2 evidence. This matrix is complete; other T21 gates remain open. The current-maximum reports, report hashes, offline-audit hashes, source pins, and validation scope are in the T21 M05 section of the foundation validation plan. These are bounded observations, not coverage of other modes/resources, schema migration, fault injection, or all remaining T21 gates.

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

For Folia or Canvas, set `-PlatformEndpoint` to the exact selector above and pass the matching SHA-256-pinned JAR from the T15 endpoint table. For PostgreSQL, add `-PostgreSqlBin 'C:\Program Files\PostgreSQL\18\bin' -DatabaseProduct PostgreSQL`. The run writes its machine report and per-run logs under ignored `build/reports/foundation/T21-sql-runtime/` by default. If that volume lacks space or a database tool cannot initialize under the generated path, `-RuntimeReportsRoot` accepts an absolute, non-root directory on another volume; the runner checks the path for reparse-point ancestors, and the offline audit uses the report's declared run directory. The first current-maximum Paper/MariaDB attempt failed during isolated data-directory initialization under the default report root; retrying the same pinned cell under the shorter C-drive `build/reports/T21/` root passed. This is a bounded local path observation, not a general MariaDB-on-Windows path limit. In another retained local MariaDB initialization case, creation of `sys\x@0024statements_with_runtimes_in_95th_percentile.frm~` failed at a measured 260-character path (`Errcode: 2`); rerunning that case with the same pinned artifacts and a shorter root passed at 238 characters. See the T21 M05 section of the foundation validation plan for the current-maximum report and audit hashes. Run `pwsh -NoProfile -File tools/plugdev/t21-upgrade-validation.test.ps1` to check the report predicates and runner regressions. Accepted bounded reports and their exact artifact, source, and validation hashes are recorded in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`. Each bounded result leaves all untested cells `NOT RUN`; T21 remains in progress.

### T21 V03 incomplete-v10 audit-table startup failure on Paper, Folia, and selected Canvas cells

Use `-IncompleteV10AuditTableStartupFailure` to seed an isolated audit schema-version-9 database whose existing audit table contains only its `id` primary key and a sentinel row, then verify that candidate startup rejects the missing required v10 columns while preserving the captured audit state. Historical selector runs cover MySQL, MariaDB, or PostgreSQL on Paper `1.21.6#48` / `26.3#166`, Folia `1.21.6#6` / `26.2#7`, and Canvas `1.21.8#603` / `26.3#972` (18/18 historical bounded E2 endpoint/backend cells); the current formal maximum Paper 26.3#200, Folia 26.3#3, and Canvas 26.3#984 endpoints now have bounded E2 incomplete-v10 startup-rejection and one-row-preservation evidence across MySQL, MariaDB, and PostgreSQL (9/9 cells; see below and the foundation validation plan). This does not assert whole-database atomic rollback because normal startup creates the empty `players` table before audit migration fails. Historical supplemental current-stable Paper `26.3#178` has bounded E2 evidence for the incomplete-v10 fixture with MySQL `8.0.42`, MariaDB `12.3.3-MariaDB`, and PostgreSQL `18.1`; it does not change the current formal maximum endpoints. CurrentStable selects Paper `26.3#194`; bounded E2 audit-v12 and incomplete-v10 startup-rejection evidence now covers MySQL `8.0.42`, MariaDB `12.3.3-MariaDB`, and PostgreSQL `18.1`. The v12 case preserves its version row, 16 columns, four indexes, and sentinel; the incomplete-v10 case preserves audit version 9, its id-only table and primary index, and sentinel. Both create main schema v1 and an empty `players` table before rejection, so whole-database atomic rollback is not established. Unpinned endpoints remain rejected. The runner requires Java feature version 25 and records the full `java -version` banner. The maximum-Canvas/PostgreSQL report records `25.0.4+7-LTS-189`; its offline audit matches the `25.0.4` version-string prefix but does not enforce the complete build suffix. This is minimum-runtime evidence, not a claim covering every Java 25+ runtime. Candidate/server JAR hashes and the local PlugDev source revision must also match their recorded pins. Supply `-MariaDbBin` when the MariaDB 12.3.3 binaries are outside the runner's default Program Files path.

Add `-IncompleteV10AuditTablePlayerRowPreservation` to seed one pre-existing `players` row and require byte-equivalent normalized row JSON before and after the expected startup rejection. Historical bounded E2 evidence covers MySQL `8.0.42`, MariaDB `12.3.3-MariaDB`, and PostgreSQL `18.1` on the six endpoint builds pinned at the time: Paper `1.21.6#48` / `26.3#166`, Folia `1.21.6#6` / `26.2#7`, and Canvas `1.21.8#603` / `26.3#972` (18/18 cells). Each run verifies one row only; it does not prove whole-database atomic rollback. The nine current-formal-maximum endpoint by SQL-product cells now also have bounded E2 row-preservation evidence; each run verifies one seeded row only. Supplemental CurrentStable Paper evidence is separately scoped.

### T21 V03 successful v10-to-v11 migration with an existing player row

Use `-SuccessfulV10AuditSchemaPlayerRowPreservation` to run the normal successful v10-to-v11 audit-schema migration after seeding one existing `players` row. The report predicate requires the row count and normalized row JSON to remain identical before and after candidate startup, alongside the existing audit-schema sentinel, v11 columns/indexes, pinned artifact identity, and normal cleanup checks. The historical harness run covered MySQL `8.0.42`, MariaDB `12.3.3-MariaDB`, and PostgreSQL `18.1` on the six endpoint builds pinned at the time: Paper `1.21.6#48` / `26.3#166`, Folia `1.21.6#6` / `26.2#7`, and Canvas `1.21.8#603` / `26.3#972`. Accepted E2 evidence covers those 18 endpoint × SQL-product cells, with one preserved row per report. Current-maximum Paper `26.3#200`, Folia `26.3#3`, and Canvas `26.3#984` now each have bounded single-row migration evidence for MySQL `8.0.42`, MariaDB `12.3.3-MariaDB`, and PostgreSQL `18.1`; all nine current-maximum platform-by-SQL cells passed. This single-row case alone does not establish multi-row preservation or whole-database atomicity; the separate three-row E2 case is documented below. Report and audit hashes are recorded in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`.

Use `-SuccessfulV10AuditSchemaMultiplePlayerRowPreservation` for the separate three-row preservation case. It seeds three distinct UUIDs and requires the exact row set before and after migration; snapshots are canonicalized by UUID so SQL aggregate ordering does not affect the comparison. **PASS_BOUNDED_SUBCASE (E2)** covers the six Paper/Folia/Canvas endpoint builds pinned when these reports ran—Paper `1.21.6#48` / `26.3#166`, Folia `1.21.6#6` / `26.2#7`, and Canvas `1.21.8#603` / `26.3#972`—with three SQL products (18/18 historical cells). Current-maximum Paper `26.3#200`, Folia `26.3#3`, and Canvas `26.3#984` now each pass the separate three-row fixture for MySQL `8.0.42`, MariaDB `12.3.3-MariaDB`, and PostgreSQL `18.1`, completing all nine current-maximum platform-by-SQL cells. For the historical 18-cell matrix, report-specific offline audits and its current-source verifier passed; that verifier checked 2,866 assertions and rehashed 492 retained runtime logs / 930,287 bytes. Current-maximum report audits and verifier results are recorded separately in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`. Evidence is limited to this seeded three-row fixture, not a complete database inventory or whole-database atomicity. Do not combine this switch with the single-row switch or the ordinary successful-migration switch. Exact report/audit hashes, source pins, and validation logs are recorded in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`. Java/product tests were NOT RUN because no product source changed.

```powershell
$paperJar = (Resolve-Path 'build\reports\foundation\T15-runtime-pins\artifacts\paper-1.21.6-48.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $paperJar `
  -ExpectedServerSha256 '35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -PlatformEndpoint Minimum `
  -DatabaseProduct MySQL `
  -SuccessfulV10AuditSchemaPlayerRowPreservation
```

For the additional minimum-Paper MariaDB cell, pass the MariaDB 12.3.3 binary directory explicitly when it is outside the runner's default Program Files path. Use a short absolute report root on Windows to avoid the MariaDB system-table path-length failure recorded in the validation plan:

```powershell
$paperJar = (Resolve-Path 'build\reports\foundation\T15-runtime-pins\artifacts\paper-1.21.6-48.jar').Path
$mariaDbBin = 'C:\Users\margo\scoop\apps\mariadb\12.3.3\bin'
$reportRoot = [IO.Path]::GetFullPath('build\r')
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $paperJar `
  -ExpectedServerSha256 '35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -RuntimeReportsRoot $reportRoot `
  -MariaDbBin $mariaDbBin `
  -PlatformEndpoint Minimum `
  -DatabaseProduct MariaDB `
  -SuccessfulV10AuditSchemaPlayerRowPreservation
```

For historical T15 maximum Paper `26.3#166` reproduction only, use its recorded artifact and `Maximum` selector. This old selector does not validate the current formal maximum Paper `26.3#200`:

```powershell
$paperJar = (Resolve-Path 'build\reports\foundation\T15-runtime-pins\artifacts\paper-26.3-166.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $paperJar `
  -ExpectedServerSha256 'dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -PlatformEndpoint Maximum `
  -DatabaseProduct MySQL `
  -SuccessfulV10AuditSchemaPlayerRowPreservation
```

For maximum-Paper MariaDB, reuse `$mariaDbBin` and `$reportRoot` above with the pinned Paper artifact and MariaDB selector:

```powershell
$paperJar = (Resolve-Path 'build\reports\foundation\T15-runtime-pins\artifacts\paper-26.3-166.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $paperJar `
  -ExpectedServerSha256 'dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -RuntimeReportsRoot $reportRoot `
  -MariaDbBin $mariaDbBin `
  -PlatformEndpoint Maximum `
  -DatabaseProduct MariaDB `
  -SuccessfulV10AuditSchemaPlayerRowPreservation
```

For minimum Paper with PostgreSQL `18.1`, use the pinned PostgreSQL 18 binaries and Java 25:

```powershell
$paperJar = (Resolve-Path 'build\reports\foundation\T15-runtime-pins\artifacts\paper-1.21.6-48.jar').Path
$reportRoot = [IO.Path]::GetFullPath('build\r')
$postgreSqlBin = 'C:\Program Files\PostgreSQL\18\bin'
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $paperJar `
  -ExpectedServerSha256 '35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome 'C:\Program Files\Java\jdk-25.0.4' `
  -RuntimeReportsRoot $reportRoot `
  -PostgreSqlBin $postgreSqlBin `
  -PlatformEndpoint Minimum `
  -DatabaseProduct PostgreSQL `
  -SuccessfulV10AuditSchemaPlayerRowPreservation
```

For maximum Paper, reuse `$reportRoot` and `$postgreSqlBin` with the maximum artifact and endpoint selector:

```powershell
$paperJar = (Resolve-Path 'build\reports\foundation\T15-runtime-pins\artifacts\paper-26.3-166.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $paperJar `
  -ExpectedServerSha256 'dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome 'C:\Program Files\Java\jdk-25.0.4' `
  -RuntimeReportsRoot $reportRoot `
  -PostgreSqlBin $postgreSqlBin `
  -PlatformEndpoint Maximum `
  -DatabaseProduct PostgreSQL `
  -SuccessfulV10AuditSchemaPlayerRowPreservation
```

Run `t21-audit-schema-upgrade-offline-audit.ps1` against each emitted report with the matching `-PlatformEndpoint`, `-DatabaseProduct` (`MySQL`, `MariaDB`, or `PostgreSQL`), and pinned candidate SHA-256. It rehashes the report, pinned artifacts, source pins, and every retained runtime log. The full report/audit identities and evidence boundaries belong in the foundation validation plan.

For minimum Folia with MySQL `8.0.42`, use its exact pinned artifact and endpoint selector:

```powershell
$foliaJar = (Resolve-Path 'build\reports\foundation\T15-runtime-pins\artifacts\folia-1.21.6-6.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $foliaJar `
  -ExpectedServerSha256 '917a8abaa542c1d0ef0e969693e227a7bfe1c42e671aaa806309e00a64efc234' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -PlatformEndpoint MinimumFolia `
  -DatabaseProduct MySQL `
  -IncompleteV10AuditTableStartupFailure
```

For historical maximum Folia `26.2#7` with MySQL `8.0.42`, use its pinned artifact and endpoint selector. This is not the current formal maximum Folia `26.3#3`:

```powershell
$foliaJar = (Resolve-Path 'build\reports\foundation\T15-runtime-pins\artifacts\folia-26.2-7.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $foliaJar `
  -ExpectedServerSha256 '128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome 'C:\Program Files\Java\jdk-25.0.4' `
  -PlatformEndpoint MaximumFolia `
  -DatabaseProduct MySQL `
  -IncompleteV10AuditTableStartupFailure `
  -IncompleteV10AuditTablePlayerRowPreservation
```

For minimum Canvas `1.21.8#603` with MySQL `8.0.42`, use the bounded endpoint selector and the exact server artifact pin:

```powershell
$canvasJar = (Resolve-Path 'build\reports\foundation\T15-runtime-pins\artifacts\canvas-1.21.8-603.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $canvasJar `
  -ExpectedServerSha256 '6577181e4bc8ac40a7a48ff00e262f2ac7a798f48ada446a07eb06618dc36f1b' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome 'C:\Program Files\Java\jdk-25.0.4' `
  -PlatformEndpoint MinimumCanvas `
  -DatabaseProduct MySQL `
  -IncompleteV10AuditTableStartupFailure
```

For minimum Canvas `1.21.8#603` with MariaDB `12.3.3-MariaDB`, pass the MariaDB binary directory explicitly when it is outside the runner's default Program Files path:

```powershell
$canvasJar = (Resolve-Path 'build\reports\foundation\T15-runtime-pins\artifacts\canvas-1.21.8-603.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $canvasJar `
  -ExpectedServerSha256 '6577181e4bc8ac40a7a48ff00e262f2ac7a798f48ada446a07eb06618dc36f1b' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome 'C:\Program Files\Java\jdk-25.0.4' `
  -MariaDbBin 'C:\Users\margo\scoop\apps\mariadb\current\bin' `
  -PlatformEndpoint MinimumCanvas `
  -DatabaseProduct MariaDB `
  -IncompleteV10AuditTableStartupFailure
```

Run `pwsh -NoProfile -File tools/plugdev/t21-startup-failure-validation.test.ps1` for the target guard and report-predicate regressions. Retain the generated report and logs under ignored `build/reports/foundation/T21-sql-runtime/`, then record the report hash, offline log audit, and evidence limits in the foundation validation plan. Each untested dialect/platform combination remains `NOT RUN`.

For the historical maximum Canvas `26.3#972` / MySQL `8.0.42` cell, use its exact runtime artifact pin. This does not validate the current formal maximum Canvas `26.3#984`:

```powershell
$canvasJar = (Resolve-Path 'build\reports\foundation\T15-runtime-pins\artifacts\canvas-26.3-972.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $canvasJar `
  -ExpectedServerSha256 'aac025fe36a61f84fecb04de74e94bbf933c7081c4c88f13a9d4b96de42243f5' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome 'C:\Program Files\Java\jdk-25.0.4' `
  -PlatformEndpoint MaximumCanvas `
  -DatabaseProduct MySQL `
  -IncompleteV10AuditTableStartupFailure `
  -IncompleteV10AuditTablePlayerRowPreservation
```

For the historical maximum Canvas `26.3#972` / MariaDB `12.3.3-MariaDB` cell, provide the installed MariaDB binary directory explicitly. This does not validate the current formal maximum Canvas `26.3#984`:

```powershell
$canvasJar = (Resolve-Path 'build\reports\foundation\T15-runtime-pins\artifacts\canvas-26.3-972.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $canvasJar `
  -ExpectedServerSha256 'aac025fe36a61f84fecb04de74e94bbf933c7081c4c88f13a9d4b96de42243f5' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome 'C:\Program Files\Java\jdk-25.0.4' `
  -MariaDbBin 'C:\Users\margo\scoop\apps\mariadb\12.3.3\bin' `
  -PlatformEndpoint MaximumCanvas `
  -DatabaseProduct MariaDB `
  -IncompleteV10AuditTableStartupFailure
```

For the historical maximum Canvas `26.3#972` / PostgreSQL `18.1` incomplete-v10 failure cell, provide the PostgreSQL 18 binary directory. This does not validate the current formal maximum Canvas `26.3#984`:

```powershell
$canvasJar = (Resolve-Path 'build\reports\foundation\T15-runtime-pins\artifacts\canvas-26.3-972.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $canvasJar `
  -ExpectedServerSha256 'aac025fe36a61f84fecb04de74e94bbf933c7081c4c88f13a9d4b96de42243f5' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome 'C:\Program Files\Java\jdk-25.0.4' `
  -PostgreSqlBin 'C:\Program Files\PostgreSQL\18\bin' `
  -PlatformEndpoint MaximumCanvas `
  -DatabaseProduct PostgreSQL `
  -IncompleteV10AuditTableStartupFailure
```

### T21 V03 required audit v11 DDL permission failure

Use `-RequiredV11DdlPermissionDeniedStartupFailure` to seed an isolated audit schema-version-10 database with a complete v10 audit table and sentinel row, then constrain the candidate role so it can create the startup `players` table but cannot alter the existing audit table. For MySQL and MariaDB, the runner parses each `SHOW GRANTS` statement for both loopback account hosts and binds its privilege list to the exact account and schema. Each host must have only global `USAGE` plus one grant on the isolated schema that includes `CREATE` and omits `ALTER`; a global `CREATE`, unrelated schema, or mismatched host is rejected. MariaDB `SHOW GRANTS` authentication hashes are redacted before log retention.

For PostgreSQL, table alteration follows ownership rather than a MySQL-style `ALTER` grant. The isolated application role is not a superuser, database owner, audit-table owner, or member of the audit-table owner's role. The database and audit table remain owned by `postgres`; the application role receives database `CONNECT`, public-schema `USAGE` and `CREATE`, and audit-table `SELECT`, `INSERT`, `UPDATE`, and `DELETE`. The structured role evidence must match its retained query log.

The refreshed previous-maximum-Paper/MySQL and previous-maximum-Paper/MariaDB cells retain their isolated general-log event and verify that the candidate account's only ALTER statement is `ALTER TABLE syncmoney_audit_log ADD COLUMN request_id VARCHAR(36)`. The strict event predicate binds the reported runtime SQL user and the isolated loopback origin `localhost [127.0.0.1]`; negative fixtures reject a different hostname or source IP. The runner enables general logging only for candidate startup, restores its previous `log_output` and disabled state after capture, and hashes the retained JSONL event with the report. The earlier minimum-Paper reports preserve their original evidence and do not include this SQL-statement capture. Bounded E2 evidence covers MySQL `8.0.42`, MariaDB `12.3.3-MariaDB`, and PostgreSQL `18.1` on minimum Paper `1.21.6#48` and previous maximum Paper `26.3#166`. PostgreSQL reports verify the owner boundary and retained role/query evidence; MySQL/MariaDB previous-maximum-Paper reports additionally capture the exact denied ALTER statement in their general logs. Minimum-Folia `1.21.6#6` E2 cells cover all three SQL products; MySQL and MariaDB logs record `ALTER command denied`, while PostgreSQL proves the ownership denial. None of the minimum-Folia reports retains a separate statement trace. The historical maximum-Folia `26.2#7` now has bounded MySQL `8.0.42`, MariaDB `12.3.3-MariaDB`, and PostgreSQL `18.1` E2 evidence. The MySQL/MariaDB reports verify isolated-schema `CREATE` without `ALTER`; the MariaDB report records `ALTER command denied`, and neither retains a separate general-log statement. PostgreSQL confirms the application role is not an owner or superuser and records the ownership denial; no statement-level DDL trace was retained, so exact SQL remains UNKNOWN. Minimum Canvas `1.21.8#603` now has bounded required-v11 denial E2 evidence for MySQL `8.0.42`, MariaDB `12.3.3-MariaDB`, and PostgreSQL `18.1`, all `PASS_BOUNDED_SUBCASE`. The MariaDB report audit passed 57 assertions over 29 logs, including candidate build-manifest/log provenance; the PostgreSQL report audit passed 68 assertions over 37 logs and verified the non-owner application role. All three reports preserved main schema v1, audit schema v10, all 14 audit columns, the primary index, and the sentinel row. Each startup created an empty `players` table, so whole-database atomic rollback is not claimed; no report retained a statement-level DDL trace, so the exact attempted SQL remains `UNKNOWN`. All three historical maximum-Canvas `26.3#972` SQL products (MySQL `8.0.42`, MariaDB `12.3.3-MariaDB`, PostgreSQL `18.1`) have bounded `PASS_BOUNDED_SUBCASE` E2 results. The PostgreSQL report-specific audit passed 34 assertions and rehashed 37/37 logs, including the non-owner PostgreSQL application-role boundary and report/log-tree reparse-point checks. The 18 Paper/Folia/Canvas endpoint/backend pairs pinned before the 2026-10-17 update, plus the three SQL products on historical supplemental current-stable Paper `26.3#178`, have bounded required-v11 denial E2 reports. The supplemental Paper results do not change the current formal maximum endpoints Paper `26.3#200`, Folia `26.3#3`, and Canvas `26.3#984`; report hashes, audit checks, and evidence limits are in the T21 section of the foundation validation plan. Supplemental CurrentStable Paper `26.3#194` also has required-v11 denial evidence for all three SQL products, recorded in the foundation validation plan; this does not expand the formal endpoint range. Those runs used the optional absolute `-RuntimeReportsRoot` pointing under ignored workspace `build/`. The current-stable reports preserve audit v10 and the sentinel but create an empty `players` table; exact attempted DDL is UNKNOWN because no statement trace was retained. The formal current-maximum required-v11 DDL permission-denial startup-failure matrix has since passed as nine bounded E2 cells across Paper `26.3#200`, Folia `26.3#3`, and Canvas `26.3#984` with MySQL `8.0.42`, MariaDB `12.3.3-MariaDB`, and PostgreSQL `18.1`. Its aggregate verifier passed 3,160 checks across 301 retained logs / 585,083 bytes; all nine reports preserve audit-v10 metadata and sentinel state. Startup created an empty `players` table, so this evidence does not establish whole-database atomic rollback. The first Canvas/MariaDB initialization attempt remains excluded; see the foundation validation plan for its local diagnostic, report hashes, and artifact/source pins.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$paperJar = (Resolve-Path 'build\reports\foundation\T21-artifacts\paper-26.3-166.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $paperJar `
  -ExpectedServerSha256 'dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -PlatformEndpoint Maximum `
  -DatabaseProduct MySQL `
  -RequiredV11DdlPermissionDeniedStartupFailure
```

For the bounded minimum Folia `1.21.6#6` / MySQL `8.0.42` required-v11 denial cell, use the pinned Folia artifact:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$foliaJar = (Resolve-Path 'build\reports\foundation\T21-artifacts\folia-1.21.6-6.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $foliaJar `
  -ExpectedServerSha256 '917a8abaa542c1d0ef0e969693e227a7bfe1c42e671aaa806309e00a64efc234' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -PlatformEndpoint MinimumFolia `
  -DatabaseProduct MySQL `
  -RequiredV11DdlPermissionDeniedStartupFailure
```

For the bounded minimum Folia `1.21.6#6` / MariaDB `12.3.3` required-v11 denial cell, pass the installed MariaDB binary directory:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$foliaJar = (Resolve-Path 'build\reports\foundation\T21-artifacts\folia-1.21.6-6.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $foliaJar `
  -ExpectedServerSha256 '917a8abaa542c1d0ef0e969693e227a7bfe1c42e671aaa806309e00a64efc234' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -MariaDbBin 'C:\Users\margo\scoop\apps\mariadb\current\bin' `
  -PlatformEndpoint MinimumFolia `
  -DatabaseProduct MariaDB `
  -RequiredV11DdlPermissionDeniedStartupFailure
```

For the bounded minimum Folia `1.21.6#6` / PostgreSQL `18.1` required-v11 denial cell, provide the PostgreSQL 18 binary directory:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$foliaJar = (Resolve-Path 'build\reports\foundation\T21-artifacts\folia-1.21.6-6.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $foliaJar `
  -ExpectedServerSha256 '917a8abaa542c1d0ef0e969693e227a7bfe1c42e671aaa806309e00a64efc234' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -PostgreSqlBin 'C:\Program Files\PostgreSQL\18\bin' `
  -PlatformEndpoint MinimumFolia `
  -DatabaseProduct PostgreSQL `
  -RequiredV11DdlPermissionDeniedStartupFailure
```

For the historical maximum Folia `26.2#7` / MySQL `8.0.42` required-v11 denial cell (current formal maximum: Folia `26.3#3`):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$foliaJar = (Resolve-Path 'build\reports\foundation\T21-artifacts\folia-26.2-7.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $foliaJar `
  -ExpectedServerSha256 '128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -PlatformEndpoint MaximumFolia `
  -DatabaseProduct MySQL `
  -RequiredV11DdlPermissionDeniedStartupFailure
```

For the historical maximum Folia `26.2#7` / MariaDB `12.3.3-MariaDB` required-v11 denial cell (current formal maximum: Folia `26.3#3`):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$foliaJar = (Resolve-Path 'build\reports\foundation\T21-artifacts\folia-26.2-7.jar').Path
$mariaDbBin = 'C:\Users\margo\scoop\apps\mariadb\current\bin'
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $foliaJar `
  -ExpectedServerSha256 '128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -MariaDbBin $mariaDbBin `
  -PlatformEndpoint MaximumFolia `
  -DatabaseProduct MariaDB `
  -RequiredV11DdlPermissionDeniedStartupFailure
```


To reproduce the completed minimum Canvas `1.21.8#603` / MySQL `8.0.42` required-v11 denial cell, use the pinned Canvas artifact:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$canvasJar = (Resolve-Path 'build\reports\foundation\T21-artifacts\canvas-build.603.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $canvasJar `
  -ExpectedServerSha256 '6577181e4bc8ac40a7a48ff00e262f2ac7a798f48ada446a07eb06618dc36f1b' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -PlatformEndpoint MinimumCanvas `
  -DatabaseProduct MySQL `
  -RequiredV11DdlPermissionDeniedStartupFailure
```

For the bounded minimum Canvas `1.21.8#603` / MariaDB `12.3.3-MariaDB` required-v11 denial cell, pass the installed MariaDB binary directory:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$canvasJar = (Resolve-Path 'build\reports\foundation\T21-artifacts\canvas-build.603.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $canvasJar `
  -ExpectedServerSha256 '6577181e4bc8ac40a7a48ff00e262f2ac7a798f48ada446a07eb06618dc36f1b' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -MariaDbBin 'C:\Users\margo\scoop\apps\mariadb\current\bin' `
  -PlatformEndpoint MinimumCanvas `
  -DatabaseProduct MariaDB `
  -RequiredV11DdlPermissionDeniedStartupFailure
```

For the bounded minimum Canvas `1.21.8#603` / PostgreSQL `18.1` required-v11 denial cell, supply PostgreSQL 18 binaries:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$canvasJar = (Resolve-Path 'build\reports\foundation\T21-artifacts\canvas-build.603.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $canvasJar `
  -ExpectedServerSha256 '6577181e4bc8ac40a7a48ff00e262f2ac7a798f48ada446a07eb06618dc36f1b' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -PostgreSqlBin 'C:\Program Files\PostgreSQL\18\bin' `
  -PlatformEndpoint MinimumCanvas `
  -DatabaseProduct PostgreSQL `
  -RequiredV11DdlPermissionDeniedStartupFailure
```

The historical maximum Canvas `26.3#972` / MySQL `8.0.42` required-v11 denial cell passed E2. To reproduce it, use the T15-pinned Canvas artifact; this does not validate the current formal maximum `26.3#984`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$canvasJar = (Resolve-Path 'build\reports\foundation\T15-runtime-pins\artifacts\canvas-26.3-972.jar').Path
$env:SYNCMONEY_TEST_SERVER_JAR = $canvasJar
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $canvasJar `
  -ExpectedServerSha256 'aac025fe36a61f84fecb04de74e94bbf933c7081c4c88f13a9d4b96de42243f5' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -PlatformEndpoint MaximumCanvas `
  -DatabaseProduct MySQL `
  -RequiredV11DdlPermissionDeniedStartupFailure
```

The historical maximum Canvas `26.3#972` / MariaDB `12.3.3-MariaDB` cell passed as a bounded E2 observation on 2026-10-16. Its report is `build/reports/foundation/T21-sql-runtime/mariadb-sync-maximum-canvas-20261016-010220-dc12ad893c914970b9058f61d61353ad/result.json` (SHA-256 `5ebe910e924b89b525bdcba13dfaa2f17ab7b987aa21e6d51cf4b5af90676cba`); the offline audit passed 28 assertions and rehashed all 29 retained logs. Startup failed closed when the app role's required-v11 DDL was denied. The exact denied DDL is unknown, and startup created an empty `players` table, so whole-database atomic rollback is not established. This is a bounded startup-failure result, not broad Canvas compatibility evidence. Full evidence and limitations are recorded in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`. The historical maximum Canvas/PostgreSQL cell has since passed as a bounded E2 observation. The 18-cell previous-range Paper/Folia/Canvas required-v11 denial matrix and three historical supplemental current-stable Paper `26.3#178` SQL cells are complete as bounded E2 observations. The supplemental Paper run does not change the current formal maximum endpoints Paper `26.3#200`, Folia `26.3#3`, and Canvas `26.3#984`.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$canvasJar = (Resolve-Path 'build\reports\foundation\T15-runtime-pins\artifacts\canvas-26.3-972.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $canvasJar `
  -ExpectedServerSha256 'aac025fe36a61f84fecb04de74e94bbf933c7081c4c88f13a9d4b96de42243f5' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -MariaDbBin 'C:\Users\margo\scoop\apps\mariadb\current\bin' `
  -PlatformEndpoint MaximumCanvas `
  -DatabaseProduct MariaDB `
  -RequiredV11DdlPermissionDeniedStartupFailure
```

The historical maximum Canvas `26.3#972` / PostgreSQL `18.1` required-v11 denial cell passed as a bounded E2 observation on 2026-10-16. The report-specific offline audit passed 34 assertions and rehashed all 37 retained logs after its reparse-point boundary fix. PostgreSQL denied the app role because it did not own the audit table; the v10 audit schema and sentinel were preserved. The audit does not claim whole-database rollback because startup created an empty `players` table, and no statement trace was retained for the exact denied DDL. The report and limitations are recorded in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$canvasJar = (Resolve-Path 'build\reports\foundation\T15-runtime-pins\artifacts\canvas-26.3-972.jar').Path
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $canvasJar `
  -ExpectedServerSha256 'aac025fe36a61f84fecb04de74e94bbf933c7081c4c88f13a9d4b96de42243f5' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -PostgreSqlBin 'C:\Program Files\PostgreSQL\18\bin' `
  -PlatformEndpoint MaximumCanvas `
  -DatabaseProduct PostgreSQL `
  -RequiredV11DdlPermissionDeniedStartupFailure
```
For historical maximum Paper `26.3#166` with PostgreSQL `18.1`, use the same pinned Paper artifact and select PostgreSQL. This does not validate the current formal maximum Paper `26.3#200`. The PostgreSQL role and query evidence proves the non-owner DDL boundary; MySQL general-log capture is not used for PostgreSQL.

```powershell
pwsh -NoProfile -File tools/plugdev/run-mysql-sync-runtime.ps1 `
  -ServerJar $paperJar `
  -ExpectedServerSha256 'dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd' `
  -ExpectedCandidateSha256 '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662' `
  -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
  -JavaHome $env:JAVA_HOME `
  -PlatformEndpoint Maximum `
  -DatabaseProduct PostgreSQL `
  -PostgreSqlBin 'C:\Program Files\PostgreSQL\18\bin' `
  -RequiredV11DdlPermissionDeniedStartupFailure
```

Run `pwsh -NoProfile -File tools/plugdev/t21-startup-failure-validation.test.ps1` for the required-v11 target guard, report predicate, and grant-log consistency regressions. Retain the generated report and logs under ignored `build/reports/foundation/T21-sql-runtime/`, then record the report hash, offline log audit, and evidence limits in the foundation validation plan.

### MySQL, MariaDB, and PostgreSQL legacy-data compatibility on Paper, Folia, and Canvas

Pass `-LegacySqlDataUpgrade`, a verified 1.3.2 baseline JAR, and its SHA-256 to exercise the legacy-data lifecycle. The committed selectors still resolve the historical endpoint builds: Paper (`Minimum` `1.21.6#48`, `Maximum` `26.3#166`), Folia (`MinimumFolia` `1.21.6#6`, `MaximumFolia` `26.2#7`), and Canvas (`MinimumCanvas` `1.21.8#603`, `MaximumCanvas` `26.3#972`). These selectors cover the previous endpoint range only; they do not target the current formal maxima Paper `26.3#200`, Folia `26.3#3`, and Canvas `26.3#984`. The separate formal-maximum legacy-data matrix now has `PASS_BOUNDED_SUBCASE (E2)` evidence for all nine endpoint × SQL-product cells: Paper `26.3#200`, Folia `26.3#3`, and Canvas `26.3#984`, each with MySQL `8.0.42`, MariaDB `12.3.3-MariaDB`, and PostgreSQL `18.1`. This seeded matrix is complete; other T21 gates remain open. Historical Paper `CurrentStable` (`26.3#178`) legacy-data evidence is also recorded; the maintained selector now uses `26.3#194`. Neither changes the current formal maximum. Select MariaDB with `-DatabaseProduct MariaDB` and provide `-MariaDbBin`; its report identifies the product as MariaDB while Syncmoney configuration correctly remains `database.type: mysql`. Select PostgreSQL with `-DatabaseProduct PostgreSQL` and provide `-PostgreSqlBin`. The lifecycle seeds two disposable account rows under 1.3.2, verifies balances and versions through candidate startup/read and 1.3.2 rollback, and compares the audit schema at each stage. The observed MySQL candidate additively repairs three missing audit indexes. The pinned PostgreSQL and MariaDB 1.3.2 baselines contain the original four-index sets (nine metadata rows); candidate predicates preserve legacy `idx_audit_type` while requiring the additive `idx_audit_type_timestamp_sequence` index, and rollback retains both index names. PostgreSQL inventory retains expression-index keys and records `indisprimary`. MySQL, MariaDB, and PostgreSQL have product-specific report predicates and native audit metadata capture. Retained bounded E2 legacy-data evidence covers all three SQL products on every endpoint in the previous selector range, plus all three products on historical supplemental current-stable Paper `26.3#178`. These are bounded lifecycle observations; they do not establish schema migration, other modes, or the remaining T21 gates.

Historical reproduction example for the previous Paper maximum `26.3#166` only (set `$baselineJar` to the locally verified 1.3.2 artifact). The `Maximum` selector still targets that historical build and does not validate the current formal maximum `26.3#200`:

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

For the maintained supplemental current-stable Paper selector, use `paper-26.3-194.jar`, its pinned SHA-256 `5fac0b6937b7aecea1b5b40e6a992f7b0802e4271413be35a6913d30df960784`, and `-PlatformEndpoint CurrentStable`. Historical reports against `26.3#178` retain their own artifact pin.

Run both focused validators and the report-specific offline audit after a runtime cycle. The retained minimum-, previous-maximum-, and current-stable Paper reports, source pins, test outputs, audit hashes, and evidence limits are recorded in the T21 M05 legacy SQL compatibility sections of the foundation validation plan. All nine formal-maximum legacy-data endpoint × SQL-product cells now have `PASS_BOUNDED_SUBCASE (E2)` evidence, including Canvas `26.3#984` with MySQL `8.0.42`, MariaDB `12.3.3-MariaDB`, and PostgreSQL `18.1`. The matrix is complete; other T21 gates remain open. This seeded legacy-data lifecycle is separate from the nine already-audited current-maximum M05 candidate-created-account `SYNC` cells.

## T21 player lifecycle scheduler matrix

This example retains the historical selectors for reproducing earlier reports: `paper-263-max` still means Paper `26.3#166`, `folia-262-max` means Folia `26.2#7`, and `canvas-263-max` means Canvas `26.3#972`. The current formal maximum endpoints (`26.3#200`, `26.3#3`, and `26.3#984`) are available through `paper-263-currentmax`, `folia-263-currentmax`, and `canvas-263-currentmax`; all three standard lifecycle cells now have `PASS_BOUNDED_SUBCASE (E2)` reports below. Do not use the historical selectors below to claim current-maximum coverage.

| Endpoint | Current-maximum run ID | Runtime report SHA-256 | Teleport path |
|---|---|---|---|
| Paper `26.3#200` | `paper-263-currentmax-20261017-180704-c49e728dfafb427ba793191ecba399f6` | `632dc3a06d2d035289beea1eadb4e7693f0e4e81f4f0f03d7bba7dbcd19ccd8b` | `direct-success` |
| Folia `26.3#3` | `folia-263-currentmax-20261017-180832-999e20c045b34c4085f4548e483d50c5` | `f287ccb7c528a17b5e90b754e01b8b5088f593dee9cd7e61b55bde229eb8b31c` | `entity-scheduler-future` |
| Canvas `26.3#984` | `canvas-263-currentmax-20261017-180939-594e0c34d8d64503929113440b5db08a` | `1bfbaaf9f589ba7265b72d1a1fe4107ac946452457f877b56b78262b7e53986e` | `entity-scheduler-future` |

All three reports record `2/2` accepted writes pending at teleport and logout, followed by normal drain and Redis `4.00/v4`. The Mineflayer client process uses the configured local checkout's `node_modules` through a scoped `NODE_PATH`; each report records this path for both client actions. Exact endpoint, candidate, source, report pins, and limits are in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`.

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

The runner verifies each exact server JAR SHA-256 and the pinned candidate plugin SHA-256, creates a unique run directory, and isolates the project, PlugDev home, Gradle user home, and Redis database 15. Each endpoint run starts one server in `LOCAL_REDIS`, pauses its isolated Redis for 2.5 seconds, and runs two offline Mineflayer clients using that endpoint's protocol version. The client process receives `NODE_PATH` scoped to the configured local Mineflayer `node_modules`; stdout and stderr are retained for diagnosis, and the result records the module search path. Teleport passes only with exactly one successful `teleportAsync` future, a successful non-cancelled Bukkit teleport-event path or destination confirmation on the entity scheduler for an eventless path, and client observation of the Nether dimension. Logout passes only when the server log contains the exact expected kick reason for `AcceptanceA`; Mineflayer may report either the exact kick packet or `ECONNABORTED` after the command-triggered disconnect. The acceptance log must show four successful Vault deposits on the synthetic account (two before each action) and at least two accepted writes still incomplete at each player-scheduler action, including writes already dequeued by the worker; normal shutdown must log `Syncmoney disabled.` and leave Redis at `4.00/v4`. The runtime Redis server version and account version are reported separately. Server cleanup uses the unique run marker and PlugDev session PID; Redis cleanup uses the per-run config name and retained process ID. Results and logs are saved under ignored `build/reports/foundation/T21-player-scheduler/`.

The standard lifecycle run without `-CrossOwner` is bounded player lifecycle evidence. It does not establish Folia two-region cross-owner behavior, CMI callback retirement, hard-kill recovery, fleet-wide quiescence, or the remaining backend/failure matrix. The separate Folia gate below covers only its two-player current-region ownership assertions. Any unrun endpoint remains `NOT RUN`; a failed runner report is not acceptance evidence.

For historical Canvas player-guard report reproduction, run the same harness with `-Guard` on the then-pinned minimum and maximum Canvas builds. The old `canvas-263-max` selector is `26.3#972`, not the current formal maximum `26.3#984`:

```powershell
foreach ($endpoint in @('canvas-1218-min', 'canvas-263-max')) {
  .\tools\plugdev\run-player-scheduler-lifecycle.ps1 -Endpoint $endpoint -Guard -GuardWithCircuitBreaker `
    -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
    -MineflayerRoot 'C:\Users\margo\IdeaProjects\mineflayer-master'
}
```

This keeps `AcceptanceGuardA` and `AcceptanceGuardB` online, moves B 16,384 blocks, and runs each participant's six Vault deposits on that player's entity scheduler with both the player guard and global circuit breaker enabled. Each callback must report ownership of its own player and not its peer; each player's independent quota must produce `SSSSSF` and Redis `5.00/v5`. The test runs A first and waits for A's client to receive and acknowledge its rate-limit notice before it starts B. A notice delivered to the wrong client, a mismatched UUID, an out-of-order rejection/acknowledgement, or a missing acknowledgement fails the offline proof. This is a bounded two-owner quota observation only; it does not establish the wider Canvas owner/guard lifecycle matrix or Paper/Folia behavior.

For historical Folia player-guard report reproduction without the global breaker, run `-Guard` on the then-pinned minimum and maximum Folia builds. The old `folia-262-max` selector is `26.2#7`, not the current formal maximum `26.3#3`:

```powershell
foreach ($endpoint in @('folia-1216-min', 'folia-262-max')) {
  .\tools\plugdev\run-player-scheduler-lifecycle.ps1 -Endpoint $endpoint -Guard `
    -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
    -MineflayerRoot 'C:\Users\margo\IdeaProjects\mineflayer-master'
}
```

The runner admits the guard-only profile on Folia and Canvas; combined guard/circuit-breaker and breaker-only profiles remain Canvas-only. This profile uses `LOCAL_REDIS`, enables per-player protection, and disables SQL, the global circuit breaker, its global-lock monitor, and unrelated optional modules. The probe moves B 16,384 blocks, then runs each player's six deposits on that player's entity scheduler. Acceptance requires two owner proofs with `selfOwned=true` and `otherOwned=false`, independent `SSSSSF` outcomes, player-specific notices and acknowledgements, Redis `5.00/v5` for each account, and the existing successful teleport/logout and drain checks. The owner predicates do not run the separate `-CrossOwner` scenario, so Folia two-region cross-owner behavior remains unestablished by this profile. This is bounded Folia guard/lifecycle evidence, not general Folia scheduling or full guard/lifecycle coverage.

For the historical inverse Canvas report reproduction, run the same isolated lifecycle harness with `-BreakerOnly` on the then-pinned minimum and maximum Canvas builds. The old `canvas-263-max` selector is `26.3#972`, not the current formal maximum `26.3#984`; current-maximum Canvas 26.3#984 breaker-only evidence is now accepted as PASS_BOUNDED_SUBCASE (E2); the report and 150-assertion offline audit are recorded in 06-validation-benchmark-migration.md:

```powershell
foreach ($endpoint in @('canvas-1218-min', 'canvas-263-max')) {
  .\tools\plugdev\run-player-scheduler-lifecycle.ps1 -Endpoint $endpoint -BreakerOnly `
    -PlugDevRoot 'C:\Users\margo\IdeaProjects\plugdev-main' `
    -MineflayerRoot 'C:\Users\margo\IdeaProjects\mineflayer-master'
}
```

This profile enables the global circuit breaker at five transactions per second and disables per-player protection and its global-lock monitor. The two distinct entity owners each submit three Vault deposits inside one observed rate-limit second. Acceptance requires five successes and one global circuit-breaker rate-limit rejection, an unlocked breaker, no player-guard rejection, server ACKs plus client-received ACKs matching each owner's UUID and recipient, and Redis balances/versions matching the successful deposits. A TPS rejection alone does not mean that the circuit breaker entered lockdown. This remains a bounded two-owner Canvas case; it does not establish the wider Canvas owner/guard lifecycle matrix or Paper/Folia behavior.

After selecting the six endpoint reports, run `node tools/plugdev/audit-player-scheduler-lifecycle.mjs` to re-check their exact report hashes, teleport-future and player-specific logout evidence, Redis binary version, and stopped Redis data/AOF file hashes. The auditor writes a separate correction record and leaves historical report and audit bytes unchanged.

For two-owner cross-owner scheduler report reproduction, add `-CrossOwner` when running `run-player-scheduler-lifecycle.ps1`. The runner supports Folia selectors `folia-1216-min`, `folia-262-max`, and `folia-263-currentmax`, plus the pinned current-maximum Canvas selector `canvas-263-currentmax`; it rejects Paper and other Canvas selectors before creating a run directory or starting services. It keeps offline `AcceptanceA` and `AcceptanceB` connected, moves B 16,384 blocks in the Overworld, and waits for acceptance-side entity-scheduler checks from both players. Each check must observe ownership of its own player and no ownership of the other player's chunk; each player's entity scheduler then sends that player a targeted completion marker, and the client run passes only after both clients receive their own marker. The offline verifier also checks that both location snapshots match, are at least 4,096 blocks apart, and belong to distinct chunks. It rejects duplicate or suffixed proof markers and any `T21_CROSS_OWNER_FAIL` line. The historical maximum selector targets Folia `26.2#7`; use `folia-263-currentmax` for current formal maximum Folia `26.3#3`. The current-maximum Folia case has passed as one bounded E2 observation; the historical minimum/maximum reports remain pinned to their original builds. An absent owner result, retired player, canceled teleport, shared region, or malformed log remains a failure.

The current-maximum Folia cross-owner run is folia-263-currentmax-20261017-191034-3d8260f0782944a3a907e140464bb9c1; its report SHA-256 is c0702cf93d7d991d7a3a3b7d2edc64f1f4e99593e68743ead3c31a2f970b5e63. Two separate region threads each proved that their player's region was owned and the peer's was not; both live clients received their targeted completion marker, with the players 23,186 blocks apart. The same run observed the bounded teleport/logout accepted-write lifecycle and normal Redis 4.00/v4 shutdown. Its 34-assertion audit hashed the report, server/client/Redis logs, and Redis RDB/AOF files. Redis logged AOF fsync and a final snapshot, but no independent AOF reload was performed. This closes only that bounded Folia cross-owner scenario; it does not establish general scheduler safety or the wider T21 lifecycle/backend/failure matrix. See Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md for exact artifact pins, checks, and limits.

The current-maximum Canvas cross-owner run is `canvas-263-currentmax-20261020-070429-d2b163f492b643d8ad355650f717cbbf/result.json` (report SHA-256 `46fa9c2c7cb718e2f6c8fe74e2ff9ee54585bff8474dc5ada78309f0fd088171`). The report-specific R3 audit passed 55 checks and the Universal AI Verifier passed eight hard criteria. Both clients received their targeted completion markers after reciprocal entity-owner proofs; pending writes were 2/2 and final isolated Redis state was `4.00/v4`. Shutdown was normal with no forced cleanup. Four Redis persistence files were hashed; no independent AOF reload was performed. This closes only the bounded Canvas `26.3#984` cross-owner completion-marker subcase; it does not establish general Canvas/Folia scheduler safety or the wider T21 lifecycle/backend/failure matrix. Exact pins and limitations are in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`.

The current-formal-maximum Canvas `26.3#984` paired-owner guard-only run is `canvas-263-currentmax-20261018-182339-5d817ef488fb463eaa5e4dac70c4c3c3` (report SHA-256 `d12ce6d156cf18988af45ed5df8d6cfcf398341bd6811a550715406f8b5a4ab0`). With Java `25.0.4+7-LTS-189`, isolated `LOCAL_REDIS`, player protection enabled, and the global breaker disabled, both owner proofs showed `selfOwned=true`/`otherOwned=false`; each player's `SSSSSF` quota result, targeted notice/ACK, and Redis `5.00/v5` were independently checked. The same run passed the bounded teleport/logout drain to `4.00/v4`. Its corrected offline auditor passed 65 assertions and rehashed 15 retained logs plus four Redis persistence files; it did not reload AOF. This is one bounded Canvas guard-only profile and does not establish the wider owner/guard matrix or Folia cross-owner behavior. Exact pins, the retained v1 auditor diagnostic, focused validation, and scope limits are in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`.

The current-formal-maximum Folia `26.3#3` paired-owner guard-only run is `folia-263-currentmax-20261018-185915-ec09ed2b34b945688b2dab3b42fc7820` (report SHA-256 `d2dd3c523af5b6117ef3782ae3415397508230fb52cff0a689a086a308d18eb6`). Both entity-owner proofs showed `selfOwned=true`/`otherOwned=false`; each player's six deposits produced `SSSSSF`, a targeted notice and recipient-matched ACK, and Redis `5.00/v5`. The bounded teleport/logout lifecycle drained to `4.00/v4`. Its corrected offline auditor passed 65 assertions across 15 retained logs, four Redis persistence files and teardown. The first audit stopped on a PID-reuse false positive; the report's Redis PID had been reused by a later `cmd.exe`, which the corrected auditor identifies by process start time. It does not reload AOF or establish Folia two-region `-CrossOwner` behavior; that is a separate scenario. Full evidence and scope limits are in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`.

The T21/M12 observation runner validates the candidate's existing `recovery-v1.jsonl` during normal shutdown with `recovery-spool-validator.ps1`. The validator is read-only and does not replay, repair, compact, or alter the recovery journal. T21 upgrade-cycle reports pin the validator and test SHA-256 values alongside the ignored run-specific harness hash and include pre-/post-rollback per-node journal observations. Run the dedicated AOF acceptance-marker contract check with `pwsh -NoProfile -File tools/plugdev/run-redis-aof-restart.test.ps1`; it requires the current Vault receipt fields and rejects the old write marker.

The current-formal-maximum Paper `26.3#200` M06 `LOCAL_REDIS`/AOF restart report is `build/reports/foundation/T21-redis-aof/paper-26.3-200-20261017-211525-001a2f3478844c79a0511d7c3e9cc1b3/result.json` (SHA-256 `e21e99f128a97e622088f41fb295362e612a1e88d5c6934b4b2f8aa765c9e123`). With Java `25.0.4+7-LTS-189`, Redis `8.10.1`, loopback DB `15`, AOF `appendfsync always`, and RDB snapshots disabled, it read legacy `77.25/v4` and `88.50/v6`, wrote `1337.25/v5` and `765.50/v7`, then read those values after graceful Redis AOF restart and Paper restart. The report-specific audit passed `16` assertions over `28` retained evidence files; the focused receipt-marker contract test passed. This is one bounded Paper E2 graceful-restart observation and does not establish hard-kill or power-loss durability. Exact pins, AOF hashes, and limits are in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`.

The corresponding current-formal-maximum M06 reports are Folia `26.3#3` (`folia-26.3-3-20261018-174732-e7f2296693074e9cba3489f92b210ced/result.json`, SHA-256 `44ed7d2ecd7ceda12715f28935a00929378410bc0e7130313b6f661f2fd47396`) and Canvas `26.3#984` (`canvas-26.3-984-20261018-175406-a1a568f182ac473c9ebc39ec7bf80696/result.json`, SHA-256 `2baafe45ac96ff02a3f9a326d6fec8050baa5e4eb87dfccd55c8d96717f76786`). Both used the same Java/Redis `LOCAL_REDIS`/AOF profile and observed the same legacy and post-restart balances and versions. Each report-specific audit passed `16` assertions over `28` evidence files, and the focused receipt-marker test passed. Together, Paper/Folia/Canvas now have bounded E2 observations for this current-maximum graceful-restart profile; these do not prove hard-kill or power-loss durability, general platform safety, or the remaining T21 gates. Exact audit identities and evidence limits are in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`.

The bounded M12 old/new-plugin observations were recorded on Paper `1.21.6#48`, historical `26.3#166`, and current formal maximum `26.3#200`. The `26.3#200` report is `paper-26.3-200-20261017-202713-1d17f5e9c8ee458581827584b6259798` (SHA-256 `a8cda6211e09bf22f3030c8c0d8cecb22ee50df00661516ac28e6b168886c530`); after two near-simultaneous same-account admin writes and normal drains/stops, isolated Redis recorded `900.00/v10` and unchanged `520.00/v4`. Its 86-assertion audit rehashed 53 retained evidence files. The report-time runner and artifacts are retained in the originating workspace under `build/reports/foundation/T21-M12-currentmax/`; exact pins and limits are in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`. This remains one bounded Paper observation: it does not prove Redis commit ordering, live mixed-version write correctness, concurrent transfer safety, Redis/PostgreSQL dual-backend behavior, or the wider T21 matrix. M12 stays unsupported by default under 04§15. The historical `26.3#166` report can still be reproduced with `pwsh -NoProfile -File build/reports/foundation/T21-M12/run-m12-paper-26.3-166.ps1`; it verifies its exact Paper SHA before startup.

## T21 current-maximum V03 audit-schema migration: Paper 26.3#200

All three SQL dialect cells have now passed as bounded observations on the current-formal-maximum Paper endpoint. Each run kept the main schema at v1, advanced audit schema 10→11, added the expected two audit columns and three indexes, and preserved the single seeded player row 1234.56/v73.

| SQL product | Accepted run | Report SHA-256 | Retained logs independently rechecked |
|---|---|---|---|
| MySQL 8.0.42 | e7e6c3f97d9446a7848cd018939dd7a1 | 548f26c9420a06905f872eca418c33e3812f23028a0c764beedb9e38fc7cc52e | 24 / 48,125 bytes; zero mismatches |
| MariaDB 12.3.3-MariaDB | 6f289e21fa454e21b6c5a334f8aec6c6 | 45caad82224c28eba1c948da8271ab6d79202fc96f64d8f1652177e32c0820d8 | 25 / 49,919 bytes; zero mismatches |
| PostgreSQL 18.1 | 37b6fb6ffab34a00958642a9635597ae | 2136ddba273223e624ae337a056de7eba08f0be1dc8de042a8aa86e06cb355da | 33 / 62,498 bytes; zero mismatches |

All three report-specific offline audits and the focused audit-schema validator passed. The first MySQL attempt cc6d4ba762244a528c0b94aeb7902a3c is excluded: it failed during SQL seeding before Paper startup with MySQL error 3913 on a measured 261-character syncmoney_schema_version.ibd path, with zero cleanup failures. The accepted same-cell retry under build/r used the shorter 218-character path. This describes those local attempts, not a general Windows or database path limit.

This evidence covers Paper 26.3#200 and Folia 26.3#3 plus the Canvas/MySQL cell, with one seeded player row per report. Folia current-maximum MySQL, MariaDB, and PostgreSQL cells have all passed as bounded observations and are recorded below; Canvas `26.3#984` / MySQL `8.0.42` has also passed. Current-maximum Canvas/MySQL, MariaDB, and PostgreSQL have all passed as bounded one-row observations; the remaining V03 matrix remains open. Exact per-run audits, source pins, and failure dispositions are in Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md.

## T21 current-maximum V03 audit-schema migration: Folia 26.3#3

The single-row preservation fixture passed on Folia `26.3#3` / MySQL `8.0.42` with Java `25.0.4+7-LTS-189`, Redis `8.10.1`, and candidate `1.4.0-alpha.1`. Main schema remained v1; audit schema advanced `10→11`, columns `13→15`, and indexes `1→4`; the seeded row remained `1234.56/v73`. The server, SQL, Redis, and isolated Gradle daemon stopped normally with zero cleanup failures.

Accepted report: `build/r/mysql-sync-current-maximum-folia-20261017-231325-3863930da28a4aea8a9f01ea1f119168/result.json`, SHA-256 `dd460aa55c2a3d4ac15225d64cec60803348d9d0cddf485e2ca479eb61c5a8e6`. Its report-specific offline audit passed; an independent recheck matched all 24 retained logs / 50,391 bytes with zero mismatches. The focused audit-schema validator test passed. Folia artifact SHA-256 is `0c4cc7dd82c40bd1effa8a9c4cacfad55d41b7dc38377900957c961c2952c022`; candidate SHA-256 is `7d4fb070ecdb61ece3d5e39b7a2bf6ceb72c0c6a939f269588344b41f908b354`. Exact audit, test, source, acceptance/API Consumer artifact, and PlugDev manifest pins are in the validation plan.

These are bounded one-row-per-cell Folia observations and do not establish all-row preservation or whole-database atomic rollback. MySQL, MariaDB, and PostgreSQL current-maximum cells all passed. Canvas `26.3#984` / MySQL `8.0.42`, MariaDB `12.3.3-MariaDB`, and PostgreSQL `18.1` have passed as bounded one-row observations. Full Java/Gradle suites and the full PlugDev matrix were NOT RUN because no product source changed.

### MariaDB 12.3.3-MariaDB

The single-row migration fixture passed on Folia 26.3#3. Accepted run f700dfc1838344eb9927e29e89aaf6c9; report SHA-256 917aaefe050a4d1cc78cb7646b14d0cb8d16f4d86d093bc80d8914e9ef07c2fd. Its report-specific offline audit and focused validator passed; an independent review rehashed 25 retained logs / 52,193 bytes with zero mismatches. Main schema stayed v1, audit schema advanced 10→11, and the player row remained unchanged.

### PostgreSQL 18.1

The single-row migration fixture passed on Folia 26.3#3. Accepted run e2e50fb4286442229d54ca7b3cbd715b; report SHA-256 04a21965777ac13c442e95fd11080460dc15134c82586ae40733bf46947b5eff. Its report-specific offline audit and focused validator passed; an independent rehash matched 33 retained logs / 64,756 bytes with zero mismatches. Main schema stayed v1, audit schema advanced 10→11, and the seeded player row remained unchanged. The PostgreSQL v10 audit row retained all 13 original fields; v11 added sequence and request_id. Exact source/artifact pins and audit hashes are in the validation plan.

## T21 current-maximum V03 audit-schema migration: Canvas 26.3#984

### MySQL 8.0.42

The one-row migration fixture passed on Canvas `26.3#984` / MySQL `8.0.42` with Java `25.0.4+7-LTS-189`, Redis `8.10.1`, and candidate `1.4.0-alpha.1`. Main schema stayed v1, audit schema advanced `10→11`, columns `13→15`, and indexes `1→4`; the seeded row remained `1234.56/v73`. The report-specific offline audit passed, and an independent recheck matched 24 retained logs / 53,574 bytes with zero mismatches. The focused validator passed. Accepted report: `build/r/mysql-sync-current-maximum-canvas-20261018-000628-a8d4e438288142aa9cf40d31f2f7449f/result.json`, SHA-256 `0523361be049d7abb5738910513b57e4229732d55895c0e83bf2ea9b2bc772c5`. Exact audit, test, server/candidate, source and PlugDev manifest pins are in the validation plan.

This is one bounded Canvas/MySQL one-row observation and does not establish all-row preservation or whole-database atomic rollback. Full Java/Gradle suites and the full PlugDev matrix were NOT RUN because no product source changed.

### MariaDB 12.3.3-MariaDB

The single-row migration fixture passed on Canvas `26.3#984` with Java `25.0.4+7-LTS-189`, Redis `8.10.1`, and candidate `1.4.0-alpha.1`. Main schema stayed v1, audit schema advanced `10→11`, columns `13→15`, and indexes `1→4`; the seeded row remained `1234.56/v73`, and the existing audit row retained its 13 original fields. Accepted run `abf466d023744875935c2599e4ae9067`; report `build/r/mariadb-sync-current-maximum-canvas-20261018-003832-abf466d023744875935c2599e4ae9067/result.json`, SHA-256 `d9d5970b1f4056a51dd1c62b9d8f65ba85254c0c0fc0e0a497ffcb3de33f23c8`. The report-specific audit and focused validator passed; the independent recheck matched 25 retained logs / 55,384 bytes with zero mismatches; the current-source verifier passed 7/7 checks.

This is one bounded Canvas/MariaDB one-row observation and does not establish all-row preservation or whole-database atomic rollback. Current-maximum Canvas/PostgreSQL `18.1` has since passed as a bounded one-row observation. Full Java/Gradle suites and the full PlugDev matrix were NOT RUN because no product source changed. Exact audit, source, server/candidate, and PlugDev manifest pins are in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`.

## T21 current-maximum v12 audit-schema startup rejection

The current formal maxima Paper 26.3#200, Folia 26.3#3, and Canvas 26.3#984 each passed the newer-audit-schema v12 startup-rejection scenario with MySQL 8.0.42, MariaDB 12.3.3-MariaDB, and PostgreSQL 18.1 (nine bounded E2 cells). Candidate startup rejected v12, preserved the audit schema and sentinel, and completed rollback and plugin disable. Startup created main schema v1 and an empty players table; this does not establish whole-database atomic rollback.

The matrix re-audited 267 retained logs / 566,794 bytes. The nine-report manifest, report/audit hashes, source and artifact pins, focused validation outputs, and evidence limits are in Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md. This closes only current-maximum v12 startup-rejection coverage; T21 remains IN_PROGRESS, and the full PlugDev matrix and full Java/Gradle suites were not run because no product source changed.

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

## T21 DatabaseManager initial-schema permission-denial runtime

The bounded DatabaseManager initial-schema PostgreSQL startup-failure case has PASS_BOUNDED_SUBCASE (E2) reports on all six formal endpoints: Paper 1.21.6#48 / 26.3#200, Folia 1.21.6#6 / 26.3#3, and Canvas 1.21.8#603 / 26.3#984. Each candidate reached the main SQL pool, failed initial schema creation with permission denied for schema public, rolled back plugin startup, shut down the pool, and exited without forced cleanup or run-owned residue. The current-maximum Canvas / PostgreSQL report-specific audit passed 84 assertions over 30 retained logs / 58,776 bytes; its corrected direct verification passed 16/16 checks. Exact report/audit hashes and limits are in Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md.

These observations cover six PostgreSQL endpoint cells for this bounded startup-failure/cleanup case; they do not establish exact denied DDL, whole-schema atomic rollback, preservation of existing rows, MySQL/MariaDB behavior, or general platform compatibility. Minimum-Paper/MySQL remains user-directed BLOCKED / NOT VERIFIED; minimum-Paper/MariaDB remains blocked for this criterion, and the provisioning contract is unchanged. This bounded startup-failure/cleanup case does not close T21.

## T21 current-maximum required-v11 DDL denial with seeded player-row preservation

Paper `26.3#200` / MySQL `8.0.42` and MariaDB `12.3.3-MariaDB` pass this bounded one-row fixture. Folia `26.3#3` and Canvas `26.3#984` also pass with MySQL, MariaDB, and PostgreSQL, completing the current-maximum seeded-row extension at 9/9 endpoint × SQL-product cells; the base DDL-denial matrix remains 9/9. The Folia/MariaDB and Canvas/MariaDB reports have no independent MariaDB general-log statement, so exact DDL is unknown for those cells. Each report covers one row only and does not establish all-row preservation or whole-database atomic rollback; see the validation plan for report, audit, pins, and limits.

The current-maximum Folia `26.3#3` / MySQL `8.0.42` cell has also passed as a bounded E2 observation: the runtime role had `CREATE` but no `ALTER`, startup rolled back at `ALTER command denied`, and the one seeded player row and schema snapshots remained unchanged. Its final report-specific audit passed 96 assertions over 29 retained logs and rehashed the pinned artifacts and Gradle-daemon cleanup. This Folia report has no separate MySQL general-log event, so the exact submitted DDL remains unknown; see the validation plan for report, audit, pins, and limits. This does not establish all-row preservation or whole-database atomic rollback.

To reduce repeat work, keep compatible assertions in one isolated runtime and report. The Paper/MySQL and Paper/MariaDB runs each check the denied DDL, unchanged player row, schema snapshots, and teardown together. Run focused validators and parser checks once per unchanged source fingerprint. A shared source/manifest preflight may batch work, but must still validate every planned cell's distinct endpoint and artifact pins. Audit each finished report after teardown, then aggregate the reports. Keep different endpoint/backend cells and mutually exclusive schema-failure fixtures in separate runs. The runtime runner probes loopback ports by binding and releasing them before server startup, so parallel runtime cells can race for ports and violate the per-run isolation predicates. Wall-clock savings have not been measured.

The Canvas `26.3#984` / MySQL `8.0.42` row-preservation selector and report fixture passed focused tests. The first runtime attempt remains retained as `FAIL / EXCLUDED` because the source worktree was dirty during acceptance. The committed-source rerun passed as `PASS_BOUNDED_SUBCASE (E2)`; its report-specific offline audit passed 96 assertions and rehashed 29 retained logs. This is one-row evidence for this endpoint/backend pair only; exact DDL was not independently captured, and all-row preservation or whole-database atomic rollback is not established. The excluded report and all run data remain retained. Exact report, audit, hashes, and limits are in the foundation validation plan.

The current-maximum Paper `26.3#200`, Folia `26.3#3`, and Canvas `26.3#984` endpoints now each have one bounded PostgreSQL `18.1` report for required-v11 audit DDL permission denial with a single pre-existing `players` row. Each report observed initialization rollback, main schema v1, audit schema v10, and an unchanged row snapshot. These are endpoint/backend observations only; they do not establish all-row preservation, complete database inventory, or whole-database atomic rollback.

Minimum-Paper/MySQL and minimum-Paper/MariaDB initial-schema permission-denial remain `BLOCKED / NOT VERIFIED` by user disposition. The SQL provisioning contract and criteria are unchanged; Paper M12 was not repeated. T21 remains `IN_PROGRESS`. Exact report, audit, hashes, and limits are in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`.

## T21 same-version two-backend SYNC network gate

As of 2026-10-20, all six formal endpoints have separate `PASS_BOUNDED_SUBCASE (E2)` reports for the same-version, two-backend SYNC network scenario: Paper `1.21.6#48` / `26.3#200`, Folia `1.21.6#6` / `26.3#3`, and Canvas `1.21.8#603` / `26.3#984`. The current-maximum Canvas report-specific audit accepted 22 retained files, and the direct evidence verifier passed 361 checks; report/audit identities and evidence limits are in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`. These exact-build observations do not establish mixed-build networking or live mixed-version write guarantees. The accepted Paper M12 report is reused and must not be rerun. Minimum-Paper/MySQL stays `BLOCKED / NOT VERIFIED`; T21 remains `IN_PROGRESS`.

## T21 M04 seeded duplicate-receipt replay — current-maximum endpoints

Paper `26.3#200`, Folia `26.3#3`, and Canvas `26.3#984` each have a separate `PASS_BOUNDED_SUBCASE (E2)` report for the synthetic committed-receipt journal replay. The Folia and Canvas report-specific audits each passed 446 assertions; Canvas direct evidence verification passed 219 checks over its artifact, retained-log, and PlugDev distribution manifests. All three server stages on each endpoint stopped normally without run-owned residue. Exact report, audit, direct and universal verifier, source-pin, and limitation records are in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`.

The fixture creates the request-ID schema before candidate startup. This is not evidence for the separate `0/0` migration, hard-crash/power-loss durability, general SQLite recovery, live-player rate-limit behavior, or fleet-wide quiescence. The current-maximum Paper `26.3#200`, Folia `26.3#3`, and Canvas `26.3#984` seeded no-receipt `0/0` replay cells are now complete as bounded E2 observations; their fixtures migrate request-ID column/index `0/0 → 1/1` before replay. Minimum-Paper/MySQL stays user-directed `BLOCKED / NOT VERIFIED`; the provisioning contract is unchanged and Paper M12 must not be rerun. The selected Canvas `26.3#984` cross-owner completion-marker gate has since passed as `PASS_BOUNDED_SUBCASE (E2)`; see the validation plan for evidence and limits. T21 remains `IN_PROGRESS`.

## T21 M04 current-maximum Canvas seeded duplicate-receipt replay

Canvas `26.3#984` has now passed the same bounded committed-receipt replay as current-maximum Paper `26.3#200` and Folia `26.3#3`. The Canvas report-specific audit passed 446 assertions; direct verification passed 219 checks over 9/9 artifact entries, 27/27 retained logs / 128,089 bytes, and 26/26 PlugDev distribution files. Exact report, audit, verifier, source pins, and recovery limits are recorded in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`.

The committed-receipt fixture creates its request-ID schema before candidate startup. It does not establish the separate `0/0` migration, hard-crash or power-loss durability, general SQLite recovery, live-player rate limiting, or fleet-wide quiescence. All three current-maximum `0/0` no-receipt replay cells are now recorded as bounded E2 observations below. Minimum-Paper/MySQL remains user-directed `BLOCKED / NOT VERIFIED`; the provisioning contract is unchanged and Paper M12 must not be rerun. The selected Canvas `26.3#984` cross-owner completion-marker gate has since passed as `PASS_BOUNDED_SUBCASE (E2)`; see the validation plan for evidence and limits. T21 remains `IN_PROGRESS`.


## T21 M04 current-maximum Canvas seeded no-receipt replay

Canvas `26.3#984` passed the guard-on LOCAL SQLite journal replay from the historical request-ID schema with no receipt, column, or index (`0/0`) as `PASS_BOUNDED_SUBCASE (E2)`. The report-specific offline audit passed 447 assertions; the direct evidence verifier passed 162 checks across 9 runtime artifacts, 27 retained logs, source pins, and the 26-file PlugDev distribution manifest. The universal verifier passed six hard criteria. The candidate migrated the request-ID column/index to `1/1` before replay, produced one receipt for the seeded `17.25` deposit (`117.25/v8`), drained the journal to zero bytes, and completed normal candidate drain and 1.3.2 rollback reads at `1337.25` and `765.50`.

Report: `canvas-26.3-984-20261020-063032-6521b1623b224814a19d1ce496d40f61/result.json`, SHA-256 `39896fc3f29abc8a9c113a62bee596ac41457469a4644816c16132ebb5ee3991`. The report-specific audit SHA-256 is `5b7b5799ec48a62be05d3d308084221551abc8059689ad29c179aa0796ed899b`; direct-verifier output SHA-256 is `d110a801ffb21e980d9c338f5740780346a3224dc3c568d65a71deacba3bc46c`; universal-verifier record SHA-256 is `94d74e3a47e17f94aea4b38507afad4b094c7737fb1094fc9214c3b34c6d7b6f`. Full paths and pins are in `Proposal/1.4.0-Architecture-Foundation/06-validation-benchmark-migration.md`.

This is one synthetic guarded LOCAL SQLite observation. It does not establish hard-crash or power-loss durability, general SQLite recovery, live-player guard behavior, fleet-wide quiescence, or the full T21 result. No SQL service, full Java/Gradle suite, or full PlugDev matrix was run. Minimum-Paper/MySQL remains `BLOCKED / NOT VERIFIED`, its provisioning contract is unchanged, and Paper M12 was not repeated. This checkpoint has since been followed by the Canvas `26.3#984` cross-owner completion-marker gate, which passed as `PASS_BOUNDED_SUBCASE (E2)`; see the validation plan for evidence and limits.
