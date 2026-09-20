# AGENTS.md — Syncmoney

This file is the operating guide for coding agents and automated contributors working in this repository. Treat implementation and executable configuration as authoritative. When documentation disagrees with code, verify behavior in code and update the documentation in the same change.

## Project scope and sources of truth

Syncmoney is a Minecraft economy plugin that provides a Vault-compatible economy, optional VaultUnlocked/CMI integration, Redis-backed cross-server synchronization, relational persistence, audit/guard features, an embedded web administration service, and an optional PlaceholderAPI expansion. It does not replicate arbitrary permissions or arbitrary third-party commands.

Current release metadata is owned by root `build.gradle`. At this update the release is `1.3.2`, configuration schema is `12`, and the build toolchain is Java 21. These are separate version domains.

- Core plugin: `src/main/java/noietime/syncmoney`
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
- Preserve monotonic versions and Pub/Sub echo suppression. Delayed remote/CMI work must re-check freshness before changing player state.
- Treat insufficient funds, concurrent writes, queue saturation, partial persistence failure, and recovery as correctness cases.
- CMI mode leaves CMI as economy authority. CMI player mutations must run on the owning entity scheduler.
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
- Plugin/global operations: global region scheduler.
- Player messages, CMI player mutations, teleports, and entity state: the player's entity scheduler.
- Snapshot global collections, then dispatch player work to each player's owner.
- Async callbacks must re-enter the correct owner before touching entities.
- Use `teleportAsync`; preserve destination/cause and cancel obsolete deferred requests.
- Never block one region waiting for another region.

Use existing player lookup/routing helpers and handle disconnect/retirement.

## Event semantics

`TransactionWriter` fires `AsyncPreTransactionEvent` before accepted deposit/withdraw paths and honors cancellation. `PostTransactionEvent` is emitted after the transaction result is known and feeds telemetry such as SSE/WebSocket listeners.

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

The default Web Admin API key is `change-me-in-production`. Configuration validation warns about it, but current `WebServiceManager` auto-disable logic checks `change-me`. Never describe the placeholder as automatically safe: operators must replace it before exposing Web Admin.

## Commands and permissions

Top-level commands are `/money`, `/pay`, `/baltop`, and `/syncmoney`.

`/syncmoney` registers `migrate`, `audit`, `admin`, `web`, `monitor`, `debug`, `sync-balance`, `test`, `reload`, `version`, and conditionally `breaker`, `shadow`, and `econstats`.

Important permission nodes include `syncmoney.money`, `syncmoney.money.others`, `syncmoney.pay`, `syncmoney.admin`, and the specialized `syncmoney.admin.*` nodes declared in `plugin.yml`. Some router checks are coarser than subcommand checks, so document effective behavior from both.

## Web/API rules

The backend is Undertow. Authenticated REST calls normally use `Authorization: Bearer <api-key>`. `/health` is the health probe.

The current frontend connects SSE at `/api/sse`. It obtains a session token from `POST /api/auth/ws-token` using the API key and connects with that token. Do not restore stale `/sse` documentation.

`/ws` exists, but `WebSocketManager` is incomplete: it performs upgrade-shaped handling and bookkeeping/logging while broadcast methods do not provide full Undertow WebSocket message transport. Do not document it as a production real-time channel.

`SystemApiHandler` and `NodesApiHandler` both register `GET /api/nodes` and `GET /api/nodes/status`. `HttpHandlerRegistry.register` uses map replacement semantics and `NodesApiHandler` registers later, so its handlers are effective.

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

The build toolchain is Java 21. Runtime Java follows the Minecraft server; PlugDev documents Java 25 for newer Paper 26.1+ acceptance environments.

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
