# Syncmoney Developer Guide

> Current release: `1.3.2`
> 1.4.0 target build toolchain: Java 25 / Gradle 9.8.0
> Configuration schema: `12`

Traditional Chinese: [`DEVELOPER_GUIDE.zh_tw.md`](DEVELOPER_GUIDE.zh_tw.md)

This guide is for contributors working on the core plugin, PlaceholderAPI expansion, Web Admin backend/frontend, or acceptance tooling. Read the root [`AGENTS.md`](../AGENTS.md) first; it contains correctness and lifecycle rules that apply to all code changes.

## 1. Prerequisites

Use:

- Git;
- JDK 25 or newer for 1.4.0 Gradle compilation/toolchains and server runtime;
- the repository Gradle wrapper;
- Node.js + pnpm for `syncmoney-web`;
- an appropriate Paper/Folia/Canvas server for runtime acceptance;
- Redis and SQL only for the modes/features being tested;
- locally supplied CMI and CMILib builds for CMI runtime checks; verify any applicable license separately (the current 1.4.0 probe did not establish license status).

The 1.4.0 target requires Java 25 or newer at build and runtime and Paper 1.21.6 or newer. The preserved 1.3.2 baseline used Java 21 / Gradle 9.1.0; that is historical context, not the 1.4.0 toolchain or runtime minimum.

**Current 1.4.0 status (2026-10-26 R63 R9): BLOCKED / NOT VERIFIED.** With the local `-PreleaseVersion=1.4.0` override, the Java 25.0.4 / Gradle 9.8.0 source/package gate passed 59 actionable tasks (59 executed): 170 unit suites / 945 tests and 9 Sync integration suites / 44 tests (179 suites / 989 tests total), with zero failures, errors, or skips. Architecture, packaging (classfile major 69), API-consumer/acceptance packaging, Central publisher isolation, and frontend typecheck, 4 unit files / 22 tests, and production build passed. Current core JAR: 31,646,522 bytes / SHA-256 `BB8C014ADB37CD407AEEED93BDDE16EBE13BA64272D0511F26155DB7EF3B1916`; PAPI JAR: 13,872 bytes / `A4D893AC4892E9E715A1710A4760D7FB168EE84A7987BC43D1BAEA86EC6C319C`; acceptance JAR: 54,905 bytes / `29C41C996026B6275AD9276587D05E800759C710E4B485E5891CF1F85C281824`. R63 verifies durable recovery for the live queued `/pay` path; ADR-16 does not cover direct Vault/plugin recovery, which remains `DEFERRED / NOT VERIFIED` under ADR-07. Exact-candidate Paper runtime/T22 Beta, storage-service integration, minimum-Paper SQL, licensed/live CMI (authorization UNKNOWN), broad V03, full public API compatibility, and T21 completion remain `NOT VERIFIED` or `BLOCKED`; Stable and external release remain `NOT VERIFIED / NOT PERFORMED`. Stable metadata remains 1.3.2; no external release action occurred.

## 2. Repository map

| Path | Responsibility |
|---|---|
| `syncmoney-bukkit/src/main/java/noietime/syncmoney` | Core plugin |
| `src/main/resources/config.yml` | Shipped configuration |
| `src/main/resources/plugin.yml` | Plugin descriptor, commands, permissions |
| `src/main/resources/syncmoney-web/dist` | Embedded built frontend |
| `syncmoney-papi-expansion` | Separate PlaceholderAPI JAR |
| `syncmoney-web` | Vue/Vite frontend |
| `src/acceptance` | Acceptance probe plugin source |
| `tools/plugdev` | External real-server test harness |
| `docs` | Public architecture/API/developer docs and Traditional Chinese companions |

Root `build.gradle` owns the release version. The PAPI module inherits it. The frontend package/public metadata and embedded distribution need to stay aligned when web assets are part of a release.

## 3. Start every change from evidence

Before editing:

1. run `git status --short --branch`;
2. preserve unrelated worktree changes;
3. read the implementation and callers;
4. read the relevant default config and tests;
5. check public documentation for promises that depend on the behavior;
6. trace reflection users before renaming/removing API seams;
7. trace lifecycle ownership before moving initialization/shutdown code;
8. trace scheduler ownership before touching Bukkit/CMI entities.

Do not print secrets while inspecting runtime configuration.

## 4. Build and test commands

Core, PAPI, shaded JAR, and acceptance JAR:

```powershell
.\gradlew.bat test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar
```

Web frontend:

```powershell
cd syncmoney-web
pnpm typecheck
pnpm test:unit --run
pnpm build
```

Expected artifacts:

- `build/libs/Syncmoney-<version>.jar`
- `syncmoney-papi-expansion/build/libs/SyncmoneyExpansion-<version>.jar`
- `build/acceptance/SyncmoneyAcceptance.jar`

Use tests appropriate to the change. Documentation-only changes do not require a full server matrix, while scheduler/storage/lifecycle/cross-server/CMI changes do.

## 5. Economy development rules

### 5.1 Money type

Use `BigDecimal` and the existing normalization path. Do not introduce floating-point money calculations.

Syncmoney-owned `LOCAL_REDIS` and `SYNC` writes must keep each balance within `999999999999999999.99`, the shared SQL `DECIMAL(20,2)` and Redis cents limit. Check projected deposits before write admission and again in the account-locked commit path. Keep the existing `LOCAL` policy and stored values unchanged; do not clamp or migrate local balances as part of this guard. The explicit LOCAL-to-SYNC migration rejects raw negative SQLite balances before normalization, including negative sub-cent values that would otherwise round to 0.00, then rejects normalized balances above this ceiling before preparing insert-only or force-replacement SQL. Player UUID text in the LOCAL SQLite source must use the standard 36-character 8-4-4-4-12 hexadecimal form (case-insensitive); abbreviated forms accepted by `UUID.fromString` are rejected. Missing, malformed, or noncanonical UUID text fails the snapshot before shared SQL or Redis writes and is recorded as a failed source row. Rejected rows remain unchanged in LOCAL and are not copied to Redis.

Before moving existing Redis data from an earlier, wider balance policy into shared `LOCAL_REDIS` or `SYNC`, inspect the stored balance values with arbitrary-precision decimal comparison against this ceiling. Version 1.4.0 does not scan, clamp, or migrate legacy values; deposits, withdrawals, and transfers reject an account whose stored balance exceeds the ceiling before memory mutation. Back up the authority data and explicitly reconcile any over-ceiling account before enabling the shared mode. An admin set is an operator balance change, not an automatic migration; when audit is enabled, a set whose delta exceeds SQL `DECIMAL(20,2)` is rejected before mutation. If no verified reconciliation procedure is available, keep that data out of the 1.4.0 shared-mode deployment.

Test failure paths, not only success:

- insufficient funds;
- concurrent writes;
- stale versions;
- queue saturation/backpressure;
- persistence failure and recovery;
- shutdown with accepted writes still queued.

### 5.2 Respect the existing transaction boundaries

Use the established components:

- `EconomyFacade`
- `MemoryStateManager`
- `TransactionWriter`
- `TransferOrchestrator`
- economy mode router/strategies

Do not bypass them with direct Redis/SQL mutation for convenience.

`AsyncPreTransactionEvent` is currently fired and cancellable behavior is honored. `PostTransactionEvent` is emitted after transaction result processing and feeds telemetry. Changes to these semantics need regression coverage.

### 5.3 Synchronous callers need memory-backed behavior

Vault is synchronous. Placeholder and command/entity paths can also run on latency-sensitive schedulers. Never add blocking Redis, SQL, Mojang, filesystem, or HTTP lookups to these hot paths.

On a cache miss, use the existing asynchronous warm/reconcile approach and return behavior appropriate to the current API.

### 5.4 Versions and cross-server state

Redis-backed economy updates use per-account Redis versions. CMI retains a legacy timestamp-derived version that is not globally monotonic across clock rollback, restart, or multiple servers. The shared CMI Redis balance/version pair now uses an atomic compare-and-write with exact decimal-string version ordering; stale versions, conflicting equal versions, incomplete or malformed pairs, and unavailable Redis are rejected. Inbound CMI work updates its local watermark and schedules an entity mutation only after Redis accepts the pair; outbound callers do not publish a rejected mirror write. This ordering guard does not make CMI versions globally monotonic, and licensed/live CMI behavior remains NOT VERIFIED.

Primary channels:

- `syncmoney:balance:update`
- `syncmoney:cmi:balance:update`

CMI remote application needs echo suppression so applying a received state does not create an endless publish loop.

Queued `/pay` transfers use the existing `recovery-v1.jsonl` spool. Startup validates the queued spool and legacy overflow log before opening shared-mode write admission; unreadable or malformed recovery evidence keeps admission closed, while affected accounts remain unavailable until their pending recovery is resolved. A forced `RETRYABLE` paired-admission checkpoint precedes queue commit. After Redis dispatch, `ATTEMPTING`/`AMBIGUOUS` outcomes remain quarantined across restart and are visible through admin-only `/syncmoney recovery list|inspect|reconcile <id>` (permission `syncmoney.admin.recovery` or `syncmoney.admin`). Reconciliation runs asynchronously and accepts only an exact Redis receipt matching the stable operation ID, canonical fingerprint, account pair, and versions. Missing, malformed, colliding, in-flight, mismatched, or unreadable receipts retain quarantine; balances alone do not resolve an ambiguous operation, and there is no automatic replay or force-clear path. FileChannel force supports local process-restart recovery; power-loss and storage-device durability beyond that behavior are not claimed. Direct Vault/plugin atomic-transfer recovery remains `DEFERRED / NOT VERIFIED` under ADR-07; no direct-transfer journal, capability, provider wiring, or command recovery path is included. Exact-candidate Paper runtime acceptance remains NOT VERIFIED.

Legacy Redis add/set/transfer overloads without a caller-supplied stable operation ID use a 300-second receipt TTL to bound key growth. The queued `/pay` recovery path supplies stable IDs; those receipts do not expire automatically and may be removed only after explicit recovery acknowledgement.

## 6. Scheduler rules

Use Paper common scheduler APIs so ownership remains valid on Paper, Folia, and Canvas.

- Pure I/O/data work: async scheduler or an owned executor.
- Plugin/global operations: global region scheduler.
- Player/entity state, player messages, CMI mutations: player's entity scheduler.
- Teleports: `teleportAsync`.
- Global player iteration: snapshot first, then schedule per-player work on each owner.
- Async callback touching an entity: re-enter that entity's scheduler.
- Never block a region while waiting for another region.

`folia-supported: true` is descriptor metadata; it is not a substitute for auditing ownership.

## 7. Lifecycle and optional modules

Each executor, listener, queue, subscription, storage connection, scheduled task, HTTP server, and optional feature needs one owner.

Check feature configuration before allocating expensive resources. Disabled modules should not silently create schemas, directories, executors, subscriptions, or listeners that belong only to that feature.

Store cancellation/close handles and make partial initialization failure clean up what it created.

The current shutdown order intentionally drains accepted economic work before closing economy/audit/storage dependencies. If you change shutdown ownership, document the dependency reason and test the failure/stop path.

## 8. Configuration changes

`SyncmoneyConfig` is a runtime snapshot. `ConfigReloadPolicy` currently allows live reload only under:

- `display`
- `pay`
- `permissions`
- `admin-permissions`
- `debug`

A new setting that creates a connection, executor, schema, subscription, listener, HTTP service, or authority change should default to restart-required unless the reload lifecycle is explicitly implemented.

For a config change:

1. update `src/main/resources/config.yml`;
2. update parsing/default/validation code;
3. decide whether config schema version changes;
4. update reload policy where justified;
5. update README/docs and both languages;
6. add focused validation/reload tests when behavior is non-trivial.

Release version and config schema version are separate.

A blank `server-name` prevents normal operation, so test startup validation when changing identity/network configuration.

## 9. CMI integration

In `cmi` mode, CMI remains the economy authority.

CMI API mutations that touch a player must run on that player's entity scheduler. Redis/network work stays off that scheduler.

When applying cross-server CMI updates:

- compare versions/freshness;
- suppress outbound echo while applying remote state;
- re-check player availability/ownership after asynchronous work;
- keep migration and Shadow Sync separate from live authority.

CMI is licensed and not distributed by this repository. Acceptance depends on a locally supplied plugin.

One earlier `1.4.0-alpha.1` candidate was used for a bounded read-only check on Paper 1.21.6 with locally supplied CMI 9.8.10.5 and CMILib 1.6.0.1; CMI's direct balance, Syncmoney's CMI route, and the public `BalanceSnapshot` API matched for one online player. Exact final-candidate CMI runtime acceptance remains **NOT VERIFIED**. The observation does not establish an active CMI license, Syncmoney Vault provider registration in CMI mode, any mutation, cross-server behavior, or Folia/Canvas behavior. Under ADR-12, CMI mode is outside the verified 1.4.0 support scope and is not validated for production use; full CMI runtime acceptance remains **NOT VERIFIED**.

In CMI mode, `/syncmoney admin` balance operations and `/syncmoney sync-balance` are disabled because they do not update CMI's authoritative balance.

Syncmoney `/pay` is also disabled in CMI mode because the adapter has no atomic two-account transfer contract. The command rejects before balance mutation, and its response is scheduled on the sender's entity owner. This source-level behavior does not establish licensed/live CMI acceptance.

The same fail-closed rule covers Vault's explicit target-player and plugin transfer methods, VaultUnlocked transfer delegates, and the public low-level Redis transfer API. These entrypoints reject before Syncmoney memory, queue, or Redis mutation. Single-account CMI operations remain under CMI authority. Exact-candidate licensed/live CMI acceptance remains **NOT VERIFIED**.

## 10. Web Admin backend development

The backend is Undertow.

### Authentication

Normal `/api/*` calls use `Authorization: Bearer <api-key>`. `/health` is unauthenticated. Keep constant-time key comparison and rate limiting intact.

The shipped key is `change-me-in-production`. Web Admin is disabled when the key is blank or equals `change-me` or `change-me-in-production` after trimming and case folding. This does not prevent the economy plugin from starting.

### Route registration

Check both the route-specific handler and the registry. `HttpHandlerRegistry` replaces an existing handler on the same method/path key.

The current code has duplicate registrations for `GET /api/nodes` and `GET /api/nodes/status`; `NodesApiHandler` registers later and is effective.

### SSE and WebSocket

The frontend live stream is `/api/sse`. One-time tokens come from `POST /api/auth/ws-token`, are valid for 60 seconds, and are consumed on validation.

Every `/ws` request, including an upgrade-shaped request, returns `503 WEBSOCKET_UNAVAILABLE`. Do not write client code that depends on `/ws`; the endpoint remains disabled until a complete transport and authentication path are implemented and tested.

### API changes

When changing an endpoint:

1. update handler validation and response model;
2. preserve shared error conventions;
3. ensure blocking work is dispatched off Undertow I/O threads;
4. verify authentication, CORS, rate limits, proxy trust, and node-to-node auth;
5. update `docs/API_REFERENCE.md` and `docs/API_REFERENCE.zh_tw.md`;
6. update frontend API client/types/tests;
7. rebuild the embedded frontend if frontend source changed.


## 11. Web frontend development

The Web Admin frontend lives in `syncmoney-web` and uses Vue 3, Vite, Pinia, `vue-i18n`, and PWA tooling.

Use the existing API client and stores so authentication, deduplication, and shared response handling stay consistent. Keep the implemented live path on `/api/sse`; do not make the UI depend on `/ws` until the backend transport and token validation are completed and tested.

Validate frontend changes with:

```powershell
cd syncmoney-web
pnpm typecheck
pnpm test:unit --run
pnpm build
```

The production build is embedded under `src/main/resources/syncmoney-web/dist`. If frontend source or public release metadata changes, rebuild the bundle and verify the packaged JAR contains the current output. Keep the frontend package version aligned with the root release when web assets ship as part of that release.

## 12. PlaceholderAPI expansion development

`syncmoney-papi-expansion` is a separate artifact. It identifies as `syncmoney`, persists across PlaceholderAPI reloads, and lazily reaches the core plugin through reflection. Treat that reflection boundary as a compatibility seam: trace both the expansion and the reflected core methods before renaming or removing APIs.

Placeholder resolution must remain non-blocking. Expensive or global values are refreshed asynchronously and cached. The current cache expires entries after about five seconds and bounds cached/pending work at roughly 10,000 entries.

Supported families include balance variants, rank/my-rank, total supply/players, version, online players, `top_<n>`, and target-player balance variants. When changing placeholder behavior, update the expansion tests and public documentation together.

## 13. REST response and error conventions

Most successful JSON responses use `success`, `data`, and `meta` fields; `meta` contains a timestamp and plugin version. Errors normally use `success: false`, an `error` object with code/message, and the same metadata. Cursor-paginated responses are a known exception and omit the normal `meta` envelope.

`HttpHandlerRegistry` maps `IllegalArgumentException` and `IllegalStateException` to HTTP 400, `SecurityException` to 403, `NoSuchElementException` to 404, `UnsupportedOperationException` to 405, and unexpected failures to 500. Unknown routes return `404 NOT_FOUND`.

Reuse the existing `ApiResponse` helpers and shared exception path so frontend and external clients can handle errors consistently.

## 14. Storage and schema changes

The shared `players` table uses UUID as the primary key and stores player name, `DECIMAL(20,2)` balance, monotonic version, last server, and update time. Redis contains balance/version, online-player, baltop, audit, and bank-related state.

For schema or persistence changes:

1. preserve money precision and version ordering;
2. make migrations safe for existing installations;
3. keep local, shared, audit, Shadow, and migration workflows distinct;
4. update schema-version metadata only when the corresponding schema contract changes;
5. test failure and recovery paths, not only the successful write;
6. document any operator action needed for upgrade or rollback.

Do not use direct storage mutation to bypass `EconomyFacade`, `MemoryStateManager`, `TransactionWriter`, `TransferOrchestrator`, or the mode router.

## 15. Commands and permissions

Top-level commands are `/money`, `/pay`, `/baltop`, and `/syncmoney`.

`/syncmoney` provides administrative and diagnostic subcommands including migration, audit, admin, web, monitoring, debug, balance sync, test, reload, version, and optional breaker, Shadow, and economy-stat functions according to current registration and enabled features.

Permission behavior comes from both `plugin.yml` and command-router/subcommand checks. Important nodes include `syncmoney.money`, `syncmoney.money.others`, `syncmoney.pay`, `syncmoney.admin`, and specialized `syncmoney.admin.*` permissions.

Migration commands run database, SQLite preview, and checkpoint work on Paper's async scheduler. Replies return through the sender's owner: the player entity scheduler, the command block's region scheduler, or the console's global scheduler. Preview output can therefore arrive after the command handler returns.

Treat LOCAL-to-SYNC migration as an offline maintenance operation, not a live-player conversion. Its write lease fences Syncmoney operations in this JVM only; it does not stop another server or out-of-band SQL, Redis, or CMI writer. Stop economic activity on every node sharing the destination stores, keep players and other economy producers from writing, and confirm accepted LOCAL writes have drained or recovered before starting. SQL and Redis population is not one cross-store transaction: if an external writer creates a Redis account after the preflight, the insert-only Redis write fails closed and the checkpoint records the row failure, but the shared SQL insert may already exist. Keep maintenance active, inspect the checkpoint, and reconcile both stores before switching to SYNC. Online-player migration and concurrent shared-destination writers remain NOT VERIFIED.

/syncmoney migrate stop requests a pause at a safe boundary. Its reply means the request was accepted, not that the migration has already stopped. LOCAL-to-SYNC checks between player accounts and after its atomic transaction-history SQL import; a request received during that import is checked after the transaction returns. `/syncmoney migrate clear` refuses to delete the checkpoint while either migration is running or draining. Use /syncmoney migrate status to inspect the checkpoint. Administrative give/take must not load a cold balance synchronously; a failed take reports N/A when no in-memory balance is available.

When adding or changing a command, verify permission denial, console/player restrictions, scheduler ownership for player actions, tab-completion cost, user-facing messages, and both language/config defaults.

## 16. Events and third-party integration

`AsyncPreTransactionEvent` is fired by `TransactionWriter` for the applicable transaction path and cancellation is honored. `PostTransactionEvent` is emitted after transaction result processing and feeds telemetry including live Web Admin updates.

`SyncmoneyEventBus` is an internal event bus, not Bukkit's event bus. Read its dispatcher before assuming Bukkit main-thread semantics.

For Vault, CMI, PlaceholderAPI, or other optional integrations:

- keep hard/soft dependency declarations accurate;
- avoid loading optional APIs when the dependency is absent;
- preserve entity-thread ownership for CMI/player mutations;
- keep reflection contracts compatible where reflection is intentionally used;
- document what is actually tested rather than implying broader compatibility.

## 17. Real-server acceptance

Use `tools/plugdev/README.md` for real-server acceptance. PlugDev is development tooling and is not a production dependency.

Scheduler, lifecycle, storage, CMI, and cross-server synchronization changes require more than compilation. Record separately the exact Minecraft/Paper/Folia/Canvas version, runtime Java version, Syncmoney artifact/version, optional dependency versions actually present, player-side behavior, startup/shutdown logs, cross-server propagation where applicable, and scenarios that could not be tested.

The earlier pre-fix 66a25c4 artifact smoke (JAR SHA-256 663B1FB6CD3B4FD83F94C0CEEE69AD93252268E066F36ABC940634218F0D0D18), historical r24 snapshot (JAR SHA-256 390499303A2FD2F065BA7E0535768C61A297514148B2F3C600934DB57391501E), and R29 artifact (JAR SHA-256 4BE022B1760209D610A390144D5B3328A094E290C2AB53A9E557BDF27FAA92F6) remain artifact-bound historical results. R39 core JAR SHA-256 63E25A450FA38F0AE57B535825617515979DBD6257BA7059629C48B21D80DE19 produced API CLASSLOADER PASS and ACCEPTANCE PASS total=1520.00 on Paper 1.21.6#48 / Java 25.0.4+7-LTS-189 / LOCAL synthetic accounts. A separate read-only recovery verified the restart read cold-vault-a=NaN, cold-vault-unlocked-b=null, a=1000.00, b=520.00, vault-unlocked-b=520.00 and graceful final stop. The composite watcher action remains failed; the recovery action is confirmed. This is PASS_BOUNDED_SUBCASE only; full T22 Beta remains NOT VERIFIED. Storage-service integration remains DEFERRED / NOT VERIFIED.

For 1.4.0, use JDK 25 or newer with the repository Gradle 9.8.0 wrapper; servers must run Java 25 or newer and Paper 1.21.6 or newer. The Java 21 / Gradle 9.1.0 values describe the 1.3.2 baseline only.

### Upgrade and rollback runbook

This is the operator sequence for V03/M01–M03. A successful PlugDev report is bounded evidence for its disposable test fleet; it does not establish that a production fleet has stopped every writer or drained every queue.

#### M01 — Maintenance window and backup

1. Confirm Java 25 or newer, Paper 1.21.6 or newer, the exact server builds, unique `server-name` values, configured economy modes, optional dependencies, SQL schema versions, Redis topology, Syncmoney artifact hashes, and config schema. For acceptance, use the exact user-pinned server artifacts listed in the [PlugDev guide](../tools/plugdev/README.md); any additional current-stable build is supplemental. Keep a redacted inventory; do not put credentials in reports.
2. Announce maintenance and stop all economy producers on every node: player/admin commands, Web Admin writes, scheduled rewards, automation, and third-party integrations. If CMI is the configured authority, stop its economy commands/module and quiesce external CMI API and SQL writers too. A post-change CMI balance event cannot veto an arbitrary direct API mutation; full CMI runtime acceptance remains **NOT VERIFIED**. Only the bounded read check in §9 has run, and CMI mode is outside 1.4.0 verified support claims.
3. Establish that accepted writes, retries, in-flight work, database writes, and audit work have drained before continuing. The current runtime does not expose complete fleet-wide queue/retry/in-flight counters. A `Syncmoney disabled.` log line and zero pending recovery records alone do not prove a production fleet is drained. If the available evidence cannot establish the state for every node and writer, keep the maintenance window open and do not proceed with the upgrade.
4. Stop every node normally and verify its process has exited. Only then capture the latest configuration, messages, aliases, SQL data, local SQLite database and any WAL/SHM files, Redis RDB/AOF state, and recovery/overflow files. Record versions and SHA-256 hashes. These backups are for separately authorized disaster recovery, not the ordinary rollback path.

#### M02 — Candidate canary and rollout

1. Keep producers disabled. Install exactly one candidate `Syncmoney-1.4.0.jar` on a canary node, preserve its existing data directory, and leave the remaining old nodes stopped. Check the candidate JAR hash, startup, config/schema retention, optional-module state, and Vault provider readiness.
2. Use disposable accounts to verify reads and writes, accepted transaction results, and SQL/Redis balance and version parity where configured. Do not inject destructive faults into real player balances. A production canary write requires separate operator authorization.
3. Upgrade the remaining nodes while producers remain disabled. Resume producers only after every node runs the verified candidate, reports ready, and reconciles its recorded balances and versions. Do not resume while an old writer remains active; the legacy 1.3.2 SQL writer can regress a newer row.

#### M03 — Normal rollback

1. Quiesce all producers again, establish drain completion, and normally stop every node. Preserve the latest SQL, Redis, SQLite, and recovery/overflow state before changing artifacts.
2. Do not roll back while a recovery journal contains pending or ambiguous work. Preserve that state and resolve it before starting 1.3.2; never delete a journal to make rollback appear successful.
3. Replace the candidate JAR with a checksum-verified 1.3.2 JAR while keeping compatible current data and versions. Verify that 1.3.2 reads transactions written during the upgrade. Keep Java 25 and the supported server where possible; if the JVM or server must also be downgraded, use versions supported by 1.3.2 rather than assuming a newer server runs on Java 21.
4. Do not flush Redis, reset balances, or restore the pre-upgrade backup as ordinary rollback. Restoring an old backup can discard valid transactions made afterward. Reintroducing 1.3.2 also restores its known stale SQL-writer limitation, so do not run old and new writers together.

The existing M01–M03 runtime reports cover two synthetic nodes on the six user-pinned Paper/Folia/Canvas endpoints, with a separate current-stable Paper report as supplemental evidence. Those reports are from earlier candidates and do not establish exact-final-candidate upgrade/rollback acceptance or production fleet-wide quiescence. They also do not prove complete drain counters or full CMI acceptance. Under ADR-12, full CMI mode and the unrun T21 cases are outside verified 1.4.0 support claims. The 2026-10-24 r24 minimum-Paper smoke used the r24 candidate JAR hash `390499303A2FD2F065BA7E0535768C61A297514148B2F3C600934DB57391501E` and passed one bounded `LOCAL` transaction plus persisted-read subcase. It does not establish the entire Paper/Java range or full T22 Beta, and it does not replace the persisted-state upgrade and rollback gate in V03/M00–M12.

## 18. Documentation, security, and release hygiene

Canonical operational/community documents are English at repository root. Traditional Chinese companions live in `docs/` and use `.zh_tw.md`. Keep both languages semantically aligned when behavior changes.

Do not publish credentials, runtime API keys, database secrets, RCON secrets, private server data, worlds, logs, or generated local test state. Public examples must use placeholders.

Report security issues privately to `security@noie.fun`. Do not promise a response or remediation SLA unless the project explicitly adopts one.

Before a release:

1. verify root `build.gradle` version and frontend package/public metadata;
2. rebuild the embedded frontend when web assets changed;
3. build the core, PAPI expansion, and acceptance artifacts;
4. verify JAR contents and runtime descriptors;
5. update changelog and both documentation languages;
6. run `git diff --check`, inspect `git status --short`, and review the final diff for secrets, stale versions, stale endpoints, and unrelated changes.

Expected artifacts are `build/libs/Syncmoney-<version>.jar`, `syncmoney-papi-expansion/build/libs/SyncmoneyExpansion-<version>.jar`, and `build/acceptance/SyncmoneyAcceptance.jar`.

## 19. Release automation and frontend mirroring

The root repository is the canonical source for the Web Admin. Stable source metadata stays at the committed version in `build.gradle`; preview jobs pass `-PreleaseVersion` without committing preview metadata. CI uses the repository variable `NEXT_VERSION` when set and otherwise falls back to the root version. The hosted variable value is not available in this checkout, so preview versions should be described as `<base-version>-alpha.<run-number>`.

The 1.4.0 matched synthetic facade/receipt-adapter benchmark did not meet all original B00 thresholds. ADR-11 accepts the measured regression only for that bounded profile; the thresholds and correctness hard gates remain unchanged. This does not establish production, SQL, Redis, or real-server performance, and the exception does not cover a correctness failure.

The core repository uses unprefixed tags. A beta or alpha tag publishes a prerelease after the Web repository is updated. A stable tag requires the `production-release` environment approval and a matching changelog section. The release workflow updates [Syncmoney-web](https://github.com/Misty4119/Syncmoney-web) first, pushes its corresponding `v`-prefixed tag, waits for its Web Release, and then publishes the core and PlaceholderAPI JARs. The Web Release contains a deterministic source archive and checksum; it intentionally does not publish `dist`, `node_modules`, coverage, Playwright output, or editor files. Syncmoney downloads the archive and builds it on the target server.

The cross-repository update uses the `SYNC_WEB_RELEASE_TOKEN` secret. It must be a fine-grained token limited to the Web repository. Do not copy that value into files, logs, release notes, or runtime configuration.

When the frontend changes, run `pnpm build:embedded` from the core `syncmoney-web` directory. The script temporarily applies `SYNCMONEY_VERSION`, runs the production build, clears and replaces `src/main/resources/syncmoney-web/dist`, and rewrites the `rootFiles`, `assetFiles`, and `iconFiles` arrays in `WebAdminServer.extractIndividualFiles`. The generated bundle and Java lists are one review unit. The Web mirror receives source and public metadata from the core release workflow; do not edit the mirror independently for release content.
