# CLAUDE.md — Syncmoney

This file gives Claude-family coding agents a compact repository-specific operating contract. Read `AGENTS.md` for the full agent guide and `CONTEXT.md` for the project snapshot before non-trivial changes.

## Mission

Syncmoney is a Vault-compatible Minecraft economy with optional VaultUnlocked/CMI integration, Redis cross-server synchronization, SQL/local persistence, audit and protection modules, an Undertow web administration service, and a separate PlaceholderAPI expansion.

Correctness priorities are: no fund creation/loss, monotonic distributed state, region-thread safety, clean lifecycle ownership, non-blocking hot paths, and then ergonomics/performance improvements.

## Ground truth

Use code and executable configuration before prose:

- `src/main/java/noietime/syncmoney`
- `src/main/resources/config.yml`
- `src/main/resources/plugin.yml`
- `build.gradle`
- `syncmoney-papi-expansion`
- `syncmoney-web`
- `tools/plugdev/README.md`

Public docs live in `README.md` and `docs/`. If behavior changes, update both English and `.zh_tw.md` documentation.

## Economy rules

- Money uses `BigDecimal` and existing normalization.
- Vault-facing reads must remain memory-backed and must not introduce blocking Redis/SQL/network I/O.
- Writes go through the existing facade/state/writer/orchestrator path.
- Preserve accepted-write durability across logout and shutdown.
- Preserve monotonic versions and Pub/Sub echo suppression.
- Re-check freshness before applying delayed asynchronous work.
- CMI remains authority in `cmi` mode; player mutations run on the player's owning entity scheduler.
- Migration and Shadow Sync are explicit workflows, not transparent authority changes.

## Scheduler rules

- I/O/data work: async scheduler or owned executor.
- Plugin/global state: global region scheduler.
- Player/entity actions, messages, CMI mutations, teleports: entity scheduler.
- Use `teleportAsync`.
- Never block one region waiting for another.
- After async completion, re-enter the correct owner before touching Bukkit entities.

Paper/Folia/Canvas safety must be demonstrated by ownership, not inferred from `folia-supported` metadata.

## Lifecycle rules

Optional modules must be cheap when disabled. Check config before creating executors, schemas, subscriptions, directories, HTTP listeners, or scheduled work. Give each resource one owner, store cancellation handles, and make partial initialization cleanly reversible.

During shutdown, stop producers first and drain accepted transaction events while audit/Shadow/storage dependencies are alive. Do not casually reorder `Syncmoney` shutdown.

## Configuration

`SyncmoneyConfig` is a snapshot. Only `display`, `pay`, `permissions`, `admin-permissions`, and `debug` are currently live-reload roots. Treat connection/authority/module-owner changes as restart-required unless `ConfigReloadPolicy` says otherwise.

The default Web Admin API key is `change-me-in-production`. Current validation warns about it, while `WebServiceManager` only auto-disables for `change-me`. Do not claim the shipped placeholder is automatically blocked.

## Web and API

Undertow serves Web Admin. REST calls normally use `Authorization: Bearer <api-key>`. `/health` is the health probe. The frontend live stream is `/api/sse`, using a session token issued by `POST /api/auth/ws-token`.

`/ws` is not a complete production WebSocket transport in the current implementation. Do not document it as fully supported.

When changing routes, inspect the handler plus `RouteRegistry` and `HttpHandlerRegistry` and update both API-reference languages.

## PlaceholderAPI

The PAPI expansion is separately built and uses reflection as a compatibility seam. Keep placeholder resolution non-blocking; expensive/global values are cached and refreshed asynchronously.

## Working method

Before editing, inspect `git status`, callers/history, tests, config, and docs. Preserve unrelated modifications. Do not print secrets. Trace reflection users before renaming public/core classes and lifecycle owners before removing wrappers.

Prefer small behavior-preserving changes. Add meaningful regression tests when changes could create/loss funds, break version ordering, lose accepted writes, or alter scheduler ownership.

## Verification

Core:

```powershell
.\gradlew.bat test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar
```

Web:

```powershell
cd syncmoney-web
pnpm typecheck
pnpm test:unit --run
pnpm build
```

For scheduler/storage/lifecycle/cross-server changes, use PlugDev and report the exact matrix actually tested. Run `git diff --check` before finalizing.

## Release facts

Root Gradle currently resolves to `1.3.1`; Java compilation uses toolchain 21. PAPI inherits the root version. Main artifacts are `Syncmoney-<version>.jar`, `SyncmoneyExpansion-<version>.jar`, and `SyncmoneyAcceptance.jar`.

Frontend release metadata and embedded `src/main/resources/syncmoney-web/dist` assets must move together when web release assets change.

## Documentation language

Root operational/community documents are canonical English. Traditional Chinese counterparts live in `docs/` with `.zh_tw.md`. Keep semantic parity and do not add promises only in one language.
