# AGENTS.md — Syncmoney

This file is the operating guide for coding agents and automated contributors working in this repository. Treat implementation and executable configuration as authoritative. When documentation disagrees with code, verify behavior in code and update the documentation in the same change.

## Project scope and sources of truth

Syncmoney is a Minecraft economy plugin that provides a Vault-compatible economy, optional VaultUnlocked/CMI integration, Redis-backed cross-server synchronization, relational persistence, audit/guard features, an embedded web administration service, and an optional PlaceholderAPI expansion. It does not replicate arbitrary permissions or arbitrary third-party commands.

Release metadata is owned by root `build.gradle` and currently remains `1.3.2` until the release task; configuration schema is `12`. The 1.4.0 foundation target uses Java 25 and Gradle 9.8.0; the preserved 1.3.2 baseline used Java 21 and Gradle 9.1.0. These are separate version domains.

**Current 1.4.0 status (2026-10-26 R63 R9): BLOCKED / NOT VERIFIED.** With the local `-PreleaseVersion=1.4.0` override, the Java 25.0.4 / Gradle 9.8.0 source/package gate passed 59 actionable tasks (59 executed): 170 unit suites / 945 tests and 9 Sync integration suites / 44 tests (179 suites / 989 tests total), with zero failures, errors, or skips. Architecture, packaging (classfile major 69), API-consumer/acceptance packaging, Central publisher isolation, and frontend typecheck, 4 unit files / 22 tests, and production build passed. Current core JAR: 31,646,522 bytes / SHA-256 `BB8C014ADB37CD407AEEED93BDDE16EBE13BA64272D0511F26155DB7EF3B1916`; PAPI JAR: 13,872 bytes / `A4D893AC4892E9E715A1710A4760D7FB168EE84A7987BC43D1BAEA86EC6C319C`; acceptance JAR: 54,905 bytes / `29C41C996026B6275AD9276587D05E800759C710E4B485E5891CF1F85C281824`. R63 verifies durable recovery for the live queued `/pay` path; ADR-16 does not cover direct Vault/plugin recovery, which remains `DEFERRED / NOT VERIFIED` under ADR-07. Exact-candidate Paper runtime/T22 Beta, storage-service integration, minimum-Paper SQL, licensed/live CMI (authorization UNKNOWN), broad V03, full public API compatibility, and T21 completion remain `NOT VERIFIED` or `BLOCKED`; Stable and external release remain `NOT VERIFIED / NOT PERFORMED`. Stable metadata remains 1.3.2; no external release action occurred.

- Core plugin: `syncmoney-bukkit/src/main/java/noietime/syncmoney`
- Runtime defaults/descriptors: `src/main/resources`
- Main configuration: `src/main/resources/config.yml`
- Plugin descriptor: `src/main/resources/plugin.yml`
- PlaceholderAPI expansion: `syncmoney-papi-expansion`
- Web frontend: `syncmoney-web`
- Embedded web bundle: `src/main/resources/syncmoney-web/dist`
- Public overview: `README.md`
- Architecture/API/developer docs: `docs/`
- Real-server acceptance tooling: `tools/plugdev/README.md`

Historical prose can lag behind code. For API behavior inspect the actual handler plus `RouteRegistry`/`HttpHandlerRegistry`. For configuration behavior read the default YAML together with `SyncmoneyConfig`, the relevant config object, and `ConfigReloadPolicy`.

## Working sequence

1. Inspect `git status --short --branch`, relevant history, implementation, callers, tests, config, and docs before editing.
2. Preserve unrelated worktree changes. Never clean or overwrite user work to obtain a clean tree.
3. Trace reflection users and lifecycle ownership before renaming/removing classes, wrappers, executors, listeners, schemas, subscriptions, or servers.
4. Trace scheduler ownership before touching Bukkit/Paper entities.
5. For economic changes, identify memory state, accepted-write boundary, persistence path, version behavior, and recovery behavior.
6. Keep local `Proposal/` and `reference/` material on disk.

Never print credentials while reviewing configuration.

## Economy invariants

- Use `BigDecimal` and the existing normalization rules for money.
- Failed or rejected transactions must not create or destroy funds.
- `EconomyFacade` is memory-first because Vault methods are synchronous. Do not add blocking Redis, SQL, Mojang, filesystem, or other network I/O to Vault hot paths, placeholders, tab completion, entity tasks, or player-message paths.
- Follow `EconomyFacade`, `MemoryStateManager`, `TransactionWriter`, `TransferOrchestrator`, and the economy mode router instead of bypassing their state/version contracts.
- Accepted queued writes must survive ordinary logout/teleport timing and be drained during shutdown while their dependencies remain alive.
- Preserve Redis-backed version ordering and Pub/Sub echo suppression. CMI keeps its legacy timestamp-derived version, which is not globally monotonic; delayed CMI work must still re-check locally observed freshness before changing player state. The in-memory CMI watermark is capped at 10,000 entries and evicts oldest entries only under capacity pressure; it must not expire entries by age alone.
- Treat insufficient funds, concurrent writes, queue saturation, partial persistence failure, and recovery as correctness cases.
- In shared `LOCAL_REDIS` and `SYNC` modes, enforce the `DECIMAL(20,2)` / Redis limit of `999999999999999999.99` on projected balances before acceptance and again under the account mutation lock. Keep the existing `LOCAL` policy unchanged; do not clamp or migrate local balances as part of this shared-backend guard.
- The explicit LOCAL-to-SYNC migration rejects raw negative SQLite balances before normalization, including negative sub-cent values that would otherwise round to 0.00, then checks the normalized balance against the shared ceiling before either insert-only or forced-replacement SQL is prepared. Player UUID text must use the standard 36-character 8-4-4-4-12 hexadecimal form (case-insensitive); abbreviated forms accepted by `UUID.fromString` are rejected. Missing, malformed, or noncanonical UUID text fails the snapshot before shared SQL or Redis writes and is recorded as a failed source row. Rejected rows are not clamped or changed in LOCAL and are not copied to Redis.
- Before switching existing Redis balances from an earlier, wider policy into shared `LOCAL_REDIS`/`SYNC`, operators must check for values above that ceiling. 1.4.0 does not scan, clamp, or migrate them; deposits, withdrawals, and transfers reject an over-ceiling stored account before memory mutation. An explicit admin set is an operator balance change, not an automatic migration; when audit is enabled, a delta outside SQL `DECIMAL(20,2)` is rejected before mutation. Keep unreconciled data outside the verified support scope.
- Queued `/pay` transfers use the existing `recovery-v1.jsonl` spool. Startup validates the queued spool and legacy overflow log before opening shared-mode write admission; unreadable or malformed recovery evidence keeps admission closed, while affected accounts remain unavailable until their pending recovery is resolved. A forced `RETRYABLE` paired-admission checkpoint precedes queue commit. After Redis dispatch, `ATTEMPTING`/`AMBIGUOUS` outcomes remain quarantined across restart and are visible through admin-only `/syncmoney recovery list|inspect|reconcile <id>` (permission `syncmoney.admin.recovery` or `syncmoney.admin`). Reconciliation runs asynchronously and accepts only an exact Redis receipt matching the stable operation ID, canonical fingerprint, account pair, and versions. Missing, malformed, colliding, in-flight, mismatched, or unreadable receipts retain quarantine; balances alone do not resolve an ambiguous operation, and there is no automatic replay or force-clear path. FileChannel force supports local process-restart recovery; power-loss and storage-device durability beyond that behavior are not claimed. Direct Vault/plugin atomic-transfer recovery remains `DEFERRED / NOT VERIFIED` under ADR-07; no direct-transfer journal, capability, provider wiring, or command recovery path is included. Exact-candidate Paper runtime acceptance remains NOT VERIFIED.
- Legacy Redis add/set/transfer overloads without a caller-supplied stable operation ID use a 300-second receipt TTL to bound key growth. The queued `/pay` recovery path supplies stable IDs; those receipts do not expire automatically and may be removed only after explicit recovery acknowledgement.
- `DbWriteQueue` retains a bounded process-local receipt cache for stable operation IDs. Retries with the same account UUID/balance/version snapshot reuse the in-flight or successful SQL completion; changed snapshots under the same ID fail closed. Failed SQL receipts may be retried with the same snapshot. Keep SQL snapshot version checks in place because the queue cache is bounded and process-local.
- CMI mode leaves CMI as economy authority. CMI player mutations must run on the owning entity scheduler.
- Syncmoney /pay is disabled in CMI mode because the adapter has no atomic two-account CMI transfer contract. Reject it before balance mutation; do not emulate it with separate debit/credit calls.
- Reject every Syncmoney paired-transfer entrypoint in CMI mode, including Vault's explicit target-player and plugin transfers, VaultUnlocked delegates, and the exposed low-level Redis transfer API. Single-account CMI operations remain under CMI authority.
- Migration and Shadow Sync are explicit workflows, not implicit live-authority switches.

Internal modes are `LOCAL`, `LOCAL_REDIS`, `SYNC`, and `CMI`. Configuration also accepts `auto`.

## Storage and synchronization

The shared SQL `players` table stores UUID, player name, `DECIMAL(20,2)` balance, monotonic version, last server, and update timestamp. Audit, baltop, local SQLite, schema-version, and Shadow data have their own tables/workflows.

Representative Redis contracts include:

- `syncmoney:balance:{uuid}`
- `syncmoney:version:{uuid}`
- `syncmoney:online:players` and `syncmoney:online:player:*`
- `syncmoney:baltop`
- audit recent/index/dedup keys
- bank/bank-owner/bank-version keys

Primary Pub/Sub channels are `syncmoney:balance:update` and `syncmoney:cmi:balance:update`. Preserve compatibility fields, version ordering, source identity, and echo suppression when changing messages.

## Scheduler rules

Syncmoney uses Paper common scheduler APIs and must remain safe on Paper, Folia, and Canvas. `folia-supported: true` is metadata, not proof of thread safety.

- Pure I/O/data work: async scheduler or a clearly owned executor.
- `/syncmoney migrate` database, SQLite preview, and checkpoint work runs on Paper's async scheduler. Capture a command block's location before dispatch; route player replies through the entity scheduler, command-block replies through the owning region, and console replies through the global scheduler.
- `/syncmoney migrate stop` requests a safe-boundary pause for active CMI or LOCAL-to-SYNC work. The response confirms acceptance; LOCAL-to-SYNC checks the request after an in-progress atomic transaction-history SQL import returns.
- Plugin/global operations: global region scheduler.
- Player messages, CMI player mutations, teleports, and entity state: the player's entity scheduler.
- Snapshot global collections, then dispatch player work to each player's owner.
- Async callbacks must re-enter the correct owner before touching entities.
- Use `teleportAsync`; preserve destination/cause and cancel obsolete deferred requests.
- Never block one region waiting for another region.

Use existing player lookup/routing helpers and handle disconnect/retirement.

## Event semantics

`TransactionWriter` fires `AsyncPreTransactionEvent` before accepted deposit/withdraw paths and honors cancellation. `PostTransactionEvent` is emitted after the transaction result is known and feeds telemetry consumers such as SSE; the `/ws` endpoint remains unavailable.

`SyncmoneyEventBus` is an internal bus, not Bukkit's event bus. Read its dispatch method before assuming main-thread semantics.

## Optional-module lifecycle

- One owner creates, starts, and closes each module.
- `BreakerManager` owns the single `PlayerTransactionGuard`; other components receive it rather than creating duplicates.
- Check config before constructing optional executors, schedules, schemas, folders, subscriptions, or HTTP listeners.
- Store cancellation handles and release resources on normal shutdown and partial initialization failure.
- Closing one optional resource must not prevent unrelated resources from being released.
- Audit cleanup/export depend on the parent audit switch.
- Per-player protection is independent of the global breaker switch.
- Disabled modules may expose lightweight status/no-op facades but must not silently start heavy resources.

The observed shutdown order intentionally stops producers before persistence dependencies: web → commands → permissions/listeners/sync → event consumers/drain → economy → audit → breaker → baltop save → storage → event bus cleanup. Preserve dependency-aware ordering.

## Configuration and reload

`SyncmoneyConfig` is a runtime snapshot. `ConfigReloadPolicy` currently permits live changes only under:

- `display`
- `pay`
- `permissions`
- `admin-permissions`
- `debug`

Connection, authority, scheduler, and module-owner changes require restart unless the reload policy explicitly says otherwise. Report restart-required changes before publishing a replacement snapshot; do not half-apply configuration.

`server-name` must be configured; a blank default prevents normal plugin operation.

The default Web Admin API key is `change-me-in-production`. `WebServiceManager` disables Web Admin when the key is blank or equals `change-me` / `change-me-in-production` after trimming and case folding; the economy plugin continues to start normally. Operators must configure a non-placeholder key to enable Web Admin.

## Commands and permissions

Top-level commands are `/money`, `/pay`, `/baltop`, and `/syncmoney`.

`/syncmoney` registers `migrate`, `audit`, `admin`, `web`, `monitor`, `debug`, `sync-balance`, `test`, `reload`, `version`, the admin-only queued recovery operations `recovery list|inspect|reconcile`, and conditionally `breaker`, `shadow`, and `econstats`.

Important permission nodes include `syncmoney.money`, `syncmoney.money.others`, `syncmoney.pay`, `syncmoney.admin`, and the specialized `syncmoney.admin.*` nodes declared in `plugin.yml`. Some router checks are coarser than subcommand checks, so document effective behavior from both.

## Web/API rules

The backend is Undertow. Authenticated REST calls normally use `Authorization: Bearer <api-key>`. `/health` is the health probe.

The current frontend connects SSE at `/api/sse`. It obtains a session token from `POST /api/auth/ws-token` using the API key and connects with that token. Do not restore stale `/sse` documentation.

Every `/ws` request returns `503 WEBSOCKET_UNAVAILABLE`; the endpoint does not upgrade connections and is not a production real-time channel.

`SystemApiHandler` and `NodesApiHandler` both register `GET /api/nodes` and `GET /api/nodes/status`. `HttpHandlerRegistry.register` uses map replacement semantics and `NodesApiHandler` registers later, so its handlers are effective. Node list, status, and management responses strip URI user-info, query, and fragment from configured URLs and label invalid URLs as `[invalid URL]`; validation rejects new or updated URLs containing user-info, query, or fragment.

Node proxy requests check both method scope and the target endpoint permission before decrypting or sending the configured remote node key; unmapped target paths require central scope. Outer request bodies are limited to 1 MiB. Upstream responses are fully read into a bounded buffer of up to 8 MiB before the response is sent; oversized responses return 502 without partial forwarding. Configured node URLs reject user-info, query, fragment, and a static list of local/special-use IPv4 ranges, including shared, protocol-assignment, documentation, benchmarking, multicast, and reserved blocks, plus IPv6 unspecified, loopback, link-local, site-local, multicast, and ULA addresses. Validation errors log only the exception type; the URL and its user-info, query, and fragment are not logged. Structured request bodies remain JSON values. DNS-rebinding resistance remains DEFERRED / NOT VERIFIED.

When API routes change, update both API-reference languages, verify authentication/CORS behavior, use existing response/error helpers, avoid blocking storage work on Undertow I/O threads, and keep node-to-node requests authenticated.

## PlaceholderAPI

The expansion identifier is `syncmoney`, is persistent, and lazily resolves the core plugin through reflection. Supported placeholders include balance variants, rank/my-rank, total supply/players, version, online players, `top_<n>`, and target-player balance variants.

Global/rank work is cached/async. Preserve non-blocking behavior and the current bounded cache (5-second expiry, up to 10,000 entries).

## Validation

Core/backend:

```powershell
.\gradlew.bat test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar
```

Frontend:

```powershell
cd syncmoney-web
pnpm typecheck
pnpm test:unit --run
pnpm build
```

The 1.4.0 target requires a Java 25 or newer compilation toolchain and server runtime, Gradle 9.8.0, and Paper 1.21.6 or newer. The R52F Gradle launcher/daemon ran on Java 21.0.6 while its Java 25.0.4 toolchain compiled the candidate. Java 21 / Gradle 9.1.0 also describe the preserved 1.3.2 baseline.

For scheduler/storage/lifecycle/CMI/cross-server changes, run the relevant PlugDev matrix and report exactly what was exercised. Compilation alone is not proof of Folia safety, queue durability, or distributed consistency.

Before finalizing run `git diff --check`, inspect `git status --short` and the diff, and search documentation for stale release numbers, endpoints, artifact names, and removed behavior.

## Release hygiene

Root `build.gradle` owns the release version; PAPI inherits it. Web package/public metadata and `src/main/resources/syncmoney-web/dist` must match when a release changes frontend assets/metadata.

The core repository is the canonical Web Admin source. Core release tags are unprefixed; the public Web mirror uses the corresponding `v`-prefixed tag and publishes the deterministic source archive consumed by `WebDownloader`. Preview builds may use the `releaseVersion` Gradle property, while stable source metadata remains committed.

Run `pnpm build:embedded` from `syncmoney-web` after frontend changes. It clears/replaces the embedded `dist` tree and updates `WebAdminServer.extractIndividualFiles` in the same operation. Review the generated bundle and Java file together.

Expected artifacts:

- `Syncmoney-<version>.jar` from `shadowJar`
- `SyncmoneyExpansion-<version>.jar`
- `SyncmoneyAcceptance.jar`

Do not commit server worlds, runtime YAML/secrets, RCON/API credentials, database files, logs, local test-server state, reports, editor caches, or dependency directories. The embedded web bundle and Gradle wrapper JAR are intentional tracked exceptions.

If a credential is discovered in tracked history, removing it from the working tree is insufficient; report it and require rotation before publishing.

## Documentation parity

Canonical project documentation is English. Traditional Chinese companions use `.zh_tw.md` and live under `docs/`.

- `AGENTS.md` ↔ `docs/AGENTS.zh_tw.md`
- `CLAUDE.md` ↔ `docs/CLAUDE.zh_tw.md`
- `CONTEXT.md` ↔ `docs/CONTEXT.zh_tw.md`
- `SECURITY.md` ↔ `docs/SECURITY.zh_tw.md`
- `CODE_OF_CONDUCT.md` ↔ `docs/CODE_OF_CONDUCT.zh_tw.md`
- `CONTRIBUTING.md` ↔ `docs/CONTRIBUTING.zh_tw.md`
- `docs/ARCHITECTURE.md` ↔ `docs/ARCHITECTURE.zh_tw.md`
- `docs/API_REFERENCE.md` ↔ `docs/API_REFERENCE.zh_tw.md`
- `docs/DEVELOPER_GUIDE.md` ↔ `docs/DEVELOPER_GUIDE.zh_tw.md`

Do not claim support, performance, security guarantees, or runtime coverage that has not been observed.
