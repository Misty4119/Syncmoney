# Syncmoney Developer Guide

> Current release: `1.3.1`
> Java build toolchain: 21
> Configuration schema: `12`

Traditional Chinese: [`DEVELOPER_GUIDE.zh_tw.md`](DEVELOPER_GUIDE.zh_tw.md)

This guide is for contributors working on the core plugin, PlaceholderAPI expansion, Web Admin backend/frontend, or acceptance tooling. Read the root [`AGENTS.md`](../AGENTS.md) first; it contains correctness and lifecycle rules that apply to all code changes.

## 1. Prerequisites

Use:

- Git;
- JDK 21 for Gradle compilation/toolchains;
- the repository Gradle wrapper;
- Node.js + pnpm for `syncmoney-web`;
- an appropriate Paper/Folia/Canvas server for runtime acceptance;
- Redis and SQL only for the modes/features being tested;
- a locally supplied licensed CMI build when testing CMI integration.

The Minecraft server may require a newer JVM than the project compiler. Current PlugDev notes use Java 25 for newer Paper 26.1+ environments.

## 2. Repository map

| Path | Responsibility |
|---|---|
| `src/main/java/noietime/syncmoney` | Core plugin |
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

Preserve monotonic versions. A delayed Pub/Sub message or async callback must not replace a newer balance.

Primary channels:

- `syncmoney:balance:update`
- `syncmoney:cmi:balance:update`

CMI remote application needs echo suppression so applying a received state does not create an endless publish loop.

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

## 10. Web Admin backend development

The backend is Undertow.

### Authentication

Normal `/api/*` calls use `Authorization: Bearer <api-key>`. `/health` is unauthenticated. Keep constant-time key comparison and rate limiting intact.

The shipped key is `change-me-in-production`. Validation warns about it, but current auto-disable logic checks only exact `change-me`; do not describe the default as automatically safe.

### Route registration

Check both the route-specific handler and the registry. `HttpHandlerRegistry` replaces an existing handler on the same method/path key.

The current code has duplicate registrations for `GET /api/nodes` and `GET /api/nodes/status`; `NodesApiHandler` registers later and is effective.

### SSE and WebSocket

The frontend live stream is `/api/sse`. One-time tokens come from `POST /api/auth/ws-token`, are valid for 60 seconds, and are consumed on validation.

`/ws` is currently incomplete. Upgrade-shaped requests only require a non-empty query token and the manager does not call `WsTokenHandler.validateToken`; message transport is also incomplete. Do not write new client code that depends on `/ws` until the implementation is completed and tested.

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

The Gradle toolchain is Java 21. Runtime Java follows the selected server; current PlugDev guidance notes Java 25 for newer Paper 26.1+ environments.

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
