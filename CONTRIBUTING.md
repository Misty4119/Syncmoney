# Contributing to Syncmoney

Thanks for helping improve Syncmoney. The project accepts bug fixes, economy/synchronization improvements, scheduler and lifecycle fixes, Web Admin work, PlaceholderAPI work, documentation, and reproducible acceptance tooling.

Traditional Chinese: [`docs/CONTRIBUTING.zh_tw.md`](docs/CONTRIBUTING.zh_tw.md)

Before contributing, read [`AGENTS.md`](AGENTS.md), [`CONTEXT.md`](CONTEXT.md), [`SECURITY.md`](SECURITY.md), and the relevant material under `docs/`. `AGENTS.md` contains repository-specific correctness rules that apply even to small changes.

## Development prerequisites

- Git
- JDK 21 for the Gradle toolchain/build
- the repository Gradle wrapper (`gradlew.bat` on Windows)
- Node.js and pnpm for `syncmoney-web`
- a compatible Paper/Folia/Canvas test server for runtime acceptance
- Redis and/or SQL only when testing modes/features that require them
- licensed CMI supplied locally when testing CMI integration; CMI is not distributed by this repository

Newer Minecraft server releases can require a newer runtime JVM than the project compiler. Follow `tools/plugdev/README.md`; current PlugDev notes use Java 25 for newer Paper 26.1+ environments.

## Repository layout

| Path | Purpose |
|---|---|
| `src/main/java/noietime/syncmoney` | Core plugin implementation |
| `src/main/resources` | Default config/messages, plugin descriptors, Lua/assets, embedded web bundle |
| `syncmoney-papi-expansion` | Separate PlaceholderAPI expansion |
| `syncmoney-web` | Vue/Vite Web Admin frontend |
| `docs` | Architecture, API, developer and Traditional Chinese documentation |
| `tools/plugdev` | External real-server acceptance tooling |
| `src/acceptance` | Acceptance plugin source used by `acceptanceJar` |

Root `build.gradle` owns the release version. The PAPI module inherits it. Web package/public metadata and the embedded frontend bundle must be updated together for releases that change web assets.

## Before editing

1. Inspect `git status --short --branch` and preserve unrelated local changes.
2. Read the implementation, callers, tests, configuration defaults, and public docs for the behavior you are changing.
3. Trace reflection users before renaming/removing compatibility seams.
4. Identify resource ownership before changing lifecycle code.
5. Identify entity ownership before changing scheduler-sensitive code.
6. For economy changes, identify the accepted-write boundary, memory/version update, persistence path, and recovery behavior.

Do not print or commit live credentials while investigating a report.

## Economy correctness

Money uses `BigDecimal` with existing normalization rules. A failed/rejected transaction must not create or destroy funds.

Vault APIs are synchronous, so hot-path reads are intentionally memory-backed. Do not add blocking Redis, SQL, Mojang, filesystem, or remote HTTP calls to Vault methods, placeholders, tab completion, entity tasks, or player-message paths.

Use the existing architecture:

- `EconomyFacade` for the public economy boundary;
- `MemoryStateManager` for in-memory balance/version state;
- `TransactionWriter` for accepted mutations and transaction events;
- `TransferOrchestrator` for cross-account transfers;
- `EconomyModeRouter` and mode strategies for authority/persistence decisions.

Preserve monotonic balance versions, duplicate/echo suppression, insufficient-funds behavior, queue backpressure/recovery, and shutdown draining of accepted writes. Re-check version freshness before applying delayed remote or CMI updates.

CMI remains the economy authority in `cmi` mode. Do not convert migration or Shadow Sync into implicit live-authority changes.

## Scheduler and Folia rules

The project compiles against Paper's API and uses common scheduler APIs across Paper, Folia, and Canvas.

- I/O/data work belongs on the async scheduler or a clearly owned background executor.
- Plugin/global work belongs on the global region scheduler.
- Player messages, teleports, CMI mutations, and entity state belong on the player's entity scheduler.
- Use `teleportAsync` for teleports.
- Snapshot global collections, then dispatch each player's work to the proper owner.
- An async callback must re-enter the correct owner before touching Bukkit entities.
- Never block one region waiting for another region.

`folia-supported: true` is metadata, not evidence that new code is thread-safe.

## Lifecycle and optional modules

Each executor, scheduler, listener, queue, subscription, HTTP server, storage connection, and optional service must have one owner. Check feature configuration before allocating heavy resources and store cancellation/close handles.

The shutdown order in `Syncmoney.onDisable()` is dependency-aware. Accepted economic events drain before economy/audit/storage dependencies close. Changes that reorder shutdown require a specific reason and tests/acceptance evidence.

Disabled modules should remain cheap and must not silently start schemas, executors, folders, subscriptions, or listeners that belong to the disabled feature.

## Configuration changes

`SyncmoneyConfig` is a runtime snapshot. `ConfigReloadPolicy` currently allows live changes only under `display`, `pay`, `permissions`, `admin-permissions`, and `debug`.

If a new setting requires new owners, connections, schemas, subscriptions, or authority changes, treat it as restart-required unless the reload implementation is explicitly expanded safely.

When adding/changing configuration:

1. update the default YAML;
2. update config parsing/validation;
3. consider config schema migration/versioning separately from the plugin release version;
4. update README/docs in both languages;
5. add focused tests for validation/reload semantics when behavior is non-trivial.

## Web Admin and REST API

The backend is Undertow. Normal `/api/*` requests use `Authorization: Bearer <api-key>`; `/health` is unauthenticated. The frontend live stream is `/api/sse` and uses a session token issued by `POST /api/auth/ws-token`.

Do not describe `/ws` as production-ready: the current WebSocket transport is incomplete.

For API changes:

- inspect the actual handler plus `RouteRegistry`, `HttpHandlerRegistry`, authentication and CORS code;
- keep the shared success/error response conventions;
- do not perform blocking storage/network work on Undertow I/O threads;
- preserve node-to-node authentication and secret handling;
- update `docs/API_REFERENCE.md` and `docs/API_REFERENCE.zh_tw.md` in the same change;
- update frontend API types/clients/tests when the contract changes.

The current registry uses map replacement semantics. Be careful when adding a route that duplicates an existing method/path.

## PlaceholderAPI expansion

The PAPI expansion is a separate JAR and intentionally uses reflection to discover the core plugin. Preserve that compatibility seam unless the packaging contract changes deliberately.

Placeholder evaluation must remain non-blocking. Expensive/global work uses bounded caching/asynchronous refresh. Update expansion tests and placeholder documentation when identifiers or formatting change.

## Documentation

Canonical operational/community docs are English. Traditional Chinese companions live in `docs/` with `.zh_tw.md`. Keep semantic parity.

When code changes user-visible behavior, update the relevant README/config comments plus architecture/API/developer docs. Do not document planned behavior as implemented behavior.

`CHANGELOG.md` is the English changelog; its Traditional Chinese companion lives at `docs/CHANGELOG.zh_tw.md`.

## Build and test

Run the checks relevant to your change. The standard repository validation is:

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

Expected artifacts are:

- `build/libs/Syncmoney-<version>.jar`
- `syncmoney-papi-expansion/build/libs/SyncmoneyExpansion-<version>.jar`
- `build/acceptance/SyncmoneyAcceptance.jar`

For scheduler, storage, lifecycle, cross-server, CMI, or server-version compatibility changes, run the applicable PlugDev matrix and report exactly which runtime checks were completed. A green compile/unit test does not establish Folia safety or distributed durability.

## Pull requests

Keep each change reviewable and scoped. Explain the concrete problem, resulting behavior, and important tradeoffs. Include reproduction steps for bugs and list verification actually performed.

Before submitting:

```powershell
git diff --check
git status --short
```

Inspect the full diff, including generated/embedded assets. Do not include server worlds, runtime config containing secrets, database files, logs, editor caches, dependency directories, or local PlugDev state.

## Security and conduct

Report vulnerabilities using [`SECURITY.md`](SECURITY.md), not a public exploit report. Participation in project spaces is governed by [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).
