# Syncmoney Architecture

> Project version: `1.3.2`
> Configuration schema: `12`
> 1.4.0 target toolchain: Java 25 / Gradle 9.8.0
> This document describes the implementation in the current repository. Code and executable configuration remain authoritative.

Traditional Chinese: [`ARCHITECTURE.zh_tw.md`](ARCHITECTURE.zh_tw.md)

## 1. System boundary

Syncmoney is a Minecraft economy plugin with a synchronous Vault-facing API and asynchronous persistence/synchronization. Its core responsibility is money state and the infrastructure required to keep that state safe across server threads, Redis, SQL, optional CMI authority, auditing, protection, and Web Admin.

The repository has three runtime deliverables:

- the core plugin from `syncmoney-bukkit/src/main/java/noietime/syncmoney`;
- the separate PlaceholderAPI expansion from `syncmoney-papi-expansion`;
- the Vue Web Admin frontend from `syncmoney-web`, embedded into the core JAR at `src/main/resources/syncmoney-web/dist`.

`tools/plugdev` is acceptance tooling and is not a production dependency.

## 2. Architectural priorities

The implementation is shaped by five constraints:

1. **Vault calls are synchronous.** Balance reads used by Vault must normally complete from memory. Redis/SQL/network work must not be inserted into hot server or entity paths.
2. **Money must not be lost or duplicated.** Accepted mutations have an explicit asynchronous persistence path and shutdown drain semantics.
3. **Distributed state is versioned.** Redis-backed economy updates use per-account Redis versions. CMI retains a legacy timestamp-derived version that is not globally monotonic across clock rollback, restart, or multiple servers; delayed CMI callbacks still re-check the locally observed watermark before mutation.
4. **Folia ownership matters.** Player/entity work runs on the owning entity scheduler, while pure I/O can run asynchronously.
5. **Optional modules have explicit owners.** Disabled features should not start unrelated executors, schemas, listeners, subscriptions, or HTTP services.

## 3. Major runtime layers

```text
Vault / commands / PAPI / Web Admin
              |
              v
      Economy mode routing
              |
     +--------+---------+
     |                  |
     v                  v
Syncmoney economy      CMI authority
     |
     v
MemoryStateManager
     |
     v
TransactionWriter / TransferOrchestrator
     |
     +----------+-------------+----------------+
     |          |             |                |
     v          v             v                v
 accepted   Redis/Lua     SQL/local        Pub/Sub /
 events     operations    persistence      audit/events
```

### 3.1 Plugin lifecycle

`Syncmoney` is the Bukkit/Paper entry point and composes the runtime managers. Lifecycle order is important because accepted economy writes can still depend on audit, storage, and synchronization components during shutdown.

The observed shutdown sequence is:

1. Web service manager
2. Command service manager
3. Permission manager
4. Listener service manager
5. Sync manager
6. Event consumer manager, including draining accepted writes while dependencies are alive
7. Economy service manager
8. Audit service manager
9. Breaker manager
10. Baltop save
11. Storage manager
12. `SyncmoneyEventBus.clearAll()`

Changes to this ordering require a dependency analysis, not just a compile check.

### 3.2 Economy mode routing

`economy.mode` accepts:

- `auto`
- `local`
- `local_redis`
- `sync`
- `cmi`

The internal router selects the active implementation. In Syncmoney-owned modes, the economy facade, memory state, writer, persistence, and synchronization paths cooperate. In `cmi` mode, CMI remains the economy authority; Syncmoney observes/synchronizes it rather than silently becoming the source of truth.

Syncmoney `/pay` is disabled in CMI mode because the adapter does not expose an atomic two-account transfer contract. The command fails closed before balance mutation.

All Syncmoney-owned paired-transfer entrypoints follow that boundary: explicit Vault target-player and plugin transfers, VaultUnlocked delegates, and the public low-level Redis transfer API reject CMI-mode requests before Syncmoney memory, queue, or Redis mutation. CMI continues to own single-account operations. These source-level checks do not establish licensed/live CMI acceptance; CMI remains outside the verified 1.4.0 support scope.

Migration and Shadow Sync are separate explicit workflows. They are not live authority switches.

## 4. Economy state and transaction flow

### 4.1 Money representation

Money is represented with `BigDecimal` and existing normalization rules. New code must preserve precision, configured decimal behavior, non-negative/insufficient-funds constraints, and the current response semantics. Syncmoney-owned shared `LOCAL_REDIS` and `SYNC` modes cap each balance at `999999999999999999.99`, matching SQL `DECIMAL(20,2)` and the Redis scripts' cents range. Projected deposits are checked before write admission and again while holding the account mutation lock. This does not narrow the existing `LOCAL` policy or clamp or migrate local balances as part of the shared-mode guard. The explicit LOCAL-to-SYNC migration rejects raw negative SQLite balances before normalization, including negative sub-cent values that would otherwise round to 0.00, then rejects normalized balances above this ceiling before preparing either insert-only or force-replacement SQL. Rejected rows remain unchanged in LOCAL and are not copied to Redis.

Existing Redis values written under an earlier, wider balance policy are not automatically scanned or migrated. Deposits, withdrawals, and transfers reject an over-ceiling stored account before memory mutation. Operators must back up and explicitly reconcile such accounts before moving them to the 1.4.0 shared-mode scope; an admin set is a deliberate balance change, not a migration. When audit is enabled, a set whose delta exceeds SQL `DECIMAL(20,2)` is rejected before mutation. Until reconciliation, these accounts are outside the verified support scope.

### 4.2 Read path

Vault-facing balance reads are designed to be memory-first. Cache warming and slower persistence lookups belong on asynchronous paths. Placeholder evaluation follows the same rule: a cold lookup must not turn a player/entity scheduler path into a blocking Redis, SQL, Mojang, or filesystem call.

Conceptually:

```text
caller -> mode router -> memory state -> immediate result
                         |
                         +-> asynchronous warm/reconcile when required
```

### 4.3 Mutation path

Syncmoney-owned deposits, withdrawals, sets, and transfers flow through the existing economy facade/state/writer/orchestrator boundaries rather than mutating persistence directly.

Important semantics:

- `AsyncPreTransactionEvent` is fired by `TransactionWriter` for supported mutations and cancellation is honored.
- Accepted writes become queued economic work; they must not be silently discarded on ordinary logout, teleport timeout, or shutdown.
- `PostTransactionEvent` is emitted after the result is known and is also used by live telemetry.
- Transfers must preserve both sides of the operation under failure; insufficient funds and partial persistence failure are correctness cases.
- Queued `/pay` transfers use the existing `recovery-v1.jsonl` spool. Startup validates the queued spool and legacy overflow log before opening shared-mode write admission; unreadable or malformed recovery evidence keeps admission closed, while affected accounts remain unavailable until their pending recovery is resolved. A forced `RETRYABLE` paired-admission checkpoint precedes queue commit. After Redis dispatch, `ATTEMPTING`/`AMBIGUOUS` outcomes remain quarantined across restart and are visible through admin-only `/syncmoney recovery list|inspect|reconcile <id>` (permission `syncmoney.admin.recovery` or `syncmoney.admin`). Reconciliation runs asynchronously and accepts only an exact Redis receipt matching the stable operation ID, canonical fingerprint, account pair, and versions. Missing, malformed, colliding, in-flight, mismatched, or unreadable receipts retain quarantine; balances alone do not resolve an ambiguous operation, and there is no automatic replay or force-clear path. FileChannel force supports local process-restart recovery; power-loss and storage-device durability beyond that behavior are not claimed. Direct Vault/plugin atomic-transfer recovery remains `DEFERRED / NOT VERIFIED` under ADR-07; no direct-transfer journal, capability, provider wiring, or command recovery path is included. Exact-candidate Paper runtime acceptance remains NOT VERIFIED.
- Queue saturation/backpressure and recovery paths are part of the money-safety contract.

## 5. Distributed synchronization

### 5.1 Versions

Redis-backed economy balance updates use per-account Redis versions. CMI retains its legacy timestamp-derived numeric version; it can repeat or regress across wall-clock rollback, restart, counter wrap, or server clock skew and does not establish global order. CMI entity callbacks re-check the handler's current watermark before applying a balance. The in-memory inbound watermark no longer expires entries by age; it holds up to 10,000 accounts and evicts the oldest entry only when the cap is exceeded. This source-level behavior is not licensed/live CMI runtime evidence.

### 5.2 Redis

Representative keys include:

| Key | Purpose |
|---|---|
| `syncmoney:balance:{uuid}` | Player balance |
| `syncmoney:version:{uuid}` | Balance version |
| `syncmoney:online:players` | Cross-server online-player set/state |
| `syncmoney:online:player:*` | Per-player online metadata |
| `syncmoney:baltop` | Ranking data |
| audit-related keys | Recent/index/dedup state |
| bank / bank-owner / bank-version keys | Vault bank state |

Primary Pub/Sub channels are:

- `syncmoney:balance:update`
- `syncmoney:cmi:balance:update`

The second channel belongs to CMI authority synchronization. Echo suppression is required when a remote CMI balance is applied locally.

### 5.3 SQL

The shared `players` table contains the core durable player record:

| Column | Shape |
|---|---|
| `uuid` | `VARCHAR(36)`, primary key |
| `player_name` | `VARCHAR(16)` |
| `balance` | `DECIMAL(20,2)` |
| `version` | `BIGINT` |
| `last_server` | `VARCHAR(64)` |
| `updated_at` | timestamp |

Audit, local persistence, schema migration, Shadow Sync, and other features use additional tables/workflows. Do not infer those contracts solely from the `players` table.

## 6. Scheduler and concurrency model

Syncmoney targets Paper-compatible scheduler APIs and declares `folia-supported: true`, but the declaration does not itself prove thread safety.

Use these ownership rules:

- **Pure I/O and data work:** Paper async scheduler or a clearly owned background executor.
- **Plugin/global operations:** global region scheduler.
- **Player messages, teleports, CMI player mutations, entity access:** the player's entity scheduler.
- **Teleports:** use `teleportAsync`.
- **Cross-player/global iteration:** snapshot global data first, then dispatch each player operation to that player's owner.
- **Async callbacks:** re-enter the correct scheduler before touching Bukkit entities.

Never block one region while waiting for another region.

## 7. Optional modules and protection

### 7.1 Breaker and player protection

`BreakerManager` owns the single `PlayerTransactionGuard`. Economy services receive the guard instead of creating independent copies.

Global breaker state and per-player protection are related safeguards but are not the same enable switch. Resource monitoring, player transaction limits, webhook notifications, and breaker state must keep their existing ownership and configuration boundaries.

### 7.2 Audit

Audit is optional. Cleanup/export behavior depends on the parent audit switch. When audit is disabled, the Web API reports `503 FEATURE_DISABLED` for audit operations instead of pretending data exists.

### 7.3 Shadow Sync and migration

Shadow Sync, CMI migration, and local-to-sync migration are explicit maintenance/data workflows. They must keep separate lifecycle, storage, and authority semantics from live economy routing.

Shadow Sync is a best-effort secondary mirror, not a durable write queue. Its bounded queue can reject snapshots when full; the server logs this condition and `/syncmoney shadow status` reports the number rejected since startup. A rejected snapshot may remain absent from the secondary mirror until that player is queued again. This does not change the primary economy balance.

## 8. Configuration model

`SyncmoneyConfig` represents a runtime configuration snapshot. `ConfigReloadPolicy` currently permits live reload only under:

- `display`
- `pay`
- `permissions`
- `admin-permissions`
- `debug`

Changes that require new connections, owners, listeners, executors, schemas, subscriptions, or economy authority are restart-required unless the reload policy and lifecycle implementation are deliberately expanded.

`server-name` must be configured. A blank server name prevents normal operation.

## 9. Web Admin backend

The embedded backend uses Undertow.

### 9.1 HTTP pipeline

- `/health` is unauthenticated.
- Normal `/api/*` routes require `Authorization: Bearer <api-key>`.
- `ApiKeyAuthFilter` uses constant-time key comparison and can return `429 RATE_LIMITED`.
- `X-Forwarded-For` is only trusted when `web-admin.security.trust-proxy=true`.
- Route exceptions are normalized by `HttpHandlerRegistry`.
- Static frontend assets are served from the embedded distribution.

The shipped YAML keeps Web Admin disabled and binds to `localhost:8080`. It ships `change-me-in-production` as the API key placeholder. `WebServiceManager` disables Web Admin when the key is blank or equals `change-me` or `change-me-in-production` after trimming and case folding; the economy plugin continues to start.

### 9.2 Route registration

Handlers register into `HttpHandlerRegistry`. Registration uses replacement semantics for the same method/path. Both `SystemApiHandler` and `NodesApiHandler` register `GET /api/nodes` and `GET /api/nodes/status`; `NodesApiHandler` is registered later and therefore provides the effective handlers.

### 9.3 SSE

The implemented frontend live channel is **`/api/sse`**.

The frontend first obtains a one-time token from `POST /api/auth/ws-token`. The token is a UUID, is valid for 60,000 ms, and is consumed on successful validation. `SseManager` accepts either:

- Bearer API key;
- Bearer one-time token; or
- `?token=<one-time-token>`.

The SSE manager sends keepalive activity approximately every 15 seconds and broadcasts transaction telemetry from `PostTransactionEvent`.

### 9.4 WebSocket limitation

`/ws` always returns `503 WEBSOCKET_UNAVAILABLE`; it does not upgrade requests and must not be treated as a production WebSocket transport.

Every `/ws` request, including an upgrade with a non-empty token, receives `503 WEBSOCKET_UNAVAILABLE`. The server does not perform an upgrade. Full WebSocket transport remains deferred.

This is a known implementation limitation and a security-sensitive area for future work.

## 10. Web Admin frontend

`syncmoney-web` uses Vue 3, Vite, Pinia, `vue-i18n`, and PWA tooling. The built production bundle is copied/embedded into `src/main/resources/syncmoney-web/dist`.

The core repository is the canonical frontend source. Frontend release metadata and the embedded bundle move with the root release when frontend assets are part of that release; the public `Syncmoney-web` mirror receives the same source and publishes a `v`-prefixed deterministic source archive. Core tags are unprefixed, while Web tags use `v`. The current frontend package version is `1.3.2`.

## 11. PlaceholderAPI expansion

The expansion is a separate JAR with identifier `syncmoney`. It is persistent and discovers the core plugin lazily through reflection, which is an intentional compatibility boundary.

Supported placeholder families include:

- `balance`
- `balance_formatted`
- `balance_abbreviated`
- `rank`
- `my_rank`
- `total_supply`
- `total_players`
- `version`
- `online_players`
- `top_<n>`
- `balance_<player>`
- `balance_formatted_<player>`
- `balance_abbreviated_<player>`

The expansion keeps expensive/global work off the hot path with asynchronous refresh and bounded caching. The current cache uses a five-second expiry and a roughly 10,000-entry/pending-work bound.

## 12. Build and acceptance architecture

Primary artifacts are:

- `build/libs/Syncmoney-<version>.jar`
- `syncmoney-papi-expansion/build/libs/SyncmoneyExpansion-<version>.jar`
- `build/acceptance/SyncmoneyAcceptance.jar`

Standard build validation:

```powershell
.\gradlew.bat test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar
cd syncmoney-web
pnpm typecheck
pnpm test:unit --run
pnpm build
```

Historical R52F r8 source/package evidence (superseded by r9): the Java 25.0.4 / Gradle 9.8.0 gate passed 68/68 tasks, 163 unit suites / 909 tests, and 9 Sync integration suites / 39 tests. Its artifact pins remain historical and are superseded below.
Historical R52F r9 source/package and bounded M04 evidence were superseded by R11; R11 is also historical and does not identify the current integration candidate. R11 passed 68/68 tasks on Java 25.0.4 / Gradle 9.8.0, with 164 unit suites / 913 tests and 9 Sync integration suites / 39 tests (952 total), zero failures, errors, or skips. The R11 core JAR was 31,605,493 bytes, SHA-256 `6c76a79a6b0119609ada212fd732d846bc224e4a5f7e7b5d23aed534f0cefd09`; the five artifact pins and gate log digest are recorded in Proposal 06. R11 rejects abbreviated UUID strings accepted by Java parsing. The earlier exact-r9 M04 result remains PASS_BOUNDED_SUBCASE only and does not validate the R11 JAR. Exact-R11 runtime, full LOCAL support, and full T22 Beta remain NOT VERIFIED; T21 remains PARTIAL / IN_PROGRESS. Storage-service integration and full public API compatibility remain NOT VERIFIED. Stable metadata remains 1.3.2; no external release action occurred.

The historical R52F R14 source/package gate revalidated the R11 code candidate on Java 25.0.4 / Gradle 9.8.0: 68/68 tasks; 164 unit suites / 913 tests and 9 Sync integration suites / 39 tests (952 total), with zero failures, errors, or skips. The five R14 artifact pins are recorded in Proposal 06. The later exact-candidate runtime attempt used that JAR, but `smaccept` returned 0 without a PASS/FAIL marker within 90 seconds; only two synthetic fixture rows were confirmed, no restart-read ran, and Paper stopped gracefully with both ports closed. Exact-candidate runtime acceptance and full T22 Beta remain NOT VERIFIED. Storage-service integration remains NOT VERIFIED; T21 remains PARTIAL / IN_PROGRESS; broad V03 is SKIPPED / NOT VERIFIED; minimum-Paper SQL initial schema and licensed/live CMI are BLOCKED / NOT VERIFIED (CMI authorization UNKNOWN); full public API compatibility is NOT VERIFIED. Overall release readiness remains BLOCKED / NOT VERIFIED. Stable metadata remains 1.3.2; no external release action occurred.

Release automation builds preview versions through a Gradle property, publishes the Web archive before the core JARs, and gates stable publication through the `production-release` environment. The Web archive excludes generated `dist`, dependency, coverage, Playwright, and editor directories because the plugin builds the frontend after downloading it.

For scheduler, storage, lifecycle, CMI, or cross-server changes, unit/build success is insufficient. Use the applicable PlugDev matrix and report exactly which real server, dependency, player, and network paths were exercised.

## 13. Change checklist

Architecture-affecting changes should answer:

1. Who owns the resource and closes it?
2. Which scheduler owns every Bukkit/CMI entity mutation?
3. Can this introduce blocking I/O into a synchronous/hot path?
4. Where is the accepted-write boundary and how is it drained?
5. How are per-account Redis versions and stale work handled?
6. Is the feature optional, and is disabled state cheap?
7. Does a configuration change require restart?
8. Do the English and Traditional Chinese docs, REST contract, frontend client, and tests still agree?


## Historical 2026-10-26 R62 — raw Lua fixtures and test-only gate result (superseded by R63)

R62 changed only the RedisMutationSafetyIT and RedisMissingAccountMutationIT raw Lua fixtures to supply receipt keys/canonical fingerprints and assert the current receipt envelope. All 23 raw-fixture tests passed. The full `:syncmoney-sync:integrationTest` task failed with 3 `PayLuaScriptManagerNoscriptIT` failures (41/44 passed, 0 errors, 0 skipped). That class had no production caller, so those failures did not exercise or establish a failure in live `/pay`. `verifyArchitecture` and `verifyPackaging` with `-PreleaseVersion=1.4.0` passed; storage-service integration was not run. The on-disk artifact hashes were not bound to a current source manifest. These are historical R62 observations only.
## 2026-10-26 R63 source/package evidence and recovery contract

**Current 1.4.0 status (2026-10-26 R63 R9): BLOCKED / NOT VERIFIED.** With the local `-PreleaseVersion=1.4.0` override, the Java 25.0.4 / Gradle 9.8.0 source/package gate passed 59 actionable tasks (59 executed): 170 unit suites / 945 tests and 9 Sync integration suites / 44 tests (179 suites / 989 tests total), with zero failures, errors, or skips. Architecture, packaging (classfile major 69), API-consumer/acceptance packaging, Central publisher isolation, and frontend typecheck, 4 unit files / 22 tests, and production build passed. Current core JAR: 31,646,522 bytes / SHA-256 `BB8C014ADB37CD407AEEED93BDDE16EBE13BA64272D0511F26155DB7EF3B1916`; PAPI JAR: 13,872 bytes / `A4D893AC4892E9E715A1710A4760D7FB168EE84A7987BC43D1BAEA86EC6C319C`; acceptance JAR: 54,905 bytes / `29C41C996026B6275AD9276587D05E800759C710E4B485E5891CF1F85C281824`. R63 verifies durable recovery for the live queued `/pay` path; ADR-16 does not cover direct Vault/plugin recovery, which remains `DEFERRED / NOT VERIFIED` under ADR-07. Exact-candidate Paper runtime/T22 Beta, storage-service integration, minimum-Paper SQL, licensed/live CMI (authorization UNKNOWN), broad V03, full public API compatibility, and T21 completion remain `NOT VERIFIED` or `BLOCKED`; Stable and external release remain `NOT VERIFIED / NOT PERFORMED`. Stable metadata remains 1.3.2; no external release action occurred.
