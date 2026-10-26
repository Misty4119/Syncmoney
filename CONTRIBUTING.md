# Contributing to Syncmoney

Thanks for helping improve Syncmoney. The project accepts bug fixes, economy/synchronization improvements, scheduler and lifecycle fixes, Web Admin work, PlaceholderAPI work, documentation, and reproducible acceptance tooling.

Traditional Chinese: [`docs/CONTRIBUTING.zh_tw.md`](docs/CONTRIBUTING.zh_tw.md)

Before contributing, read [`AGENTS.md`](AGENTS.md), [`CONTEXT.md`](CONTEXT.md), [`SECURITY.md`](SECURITY.md), and the relevant material under `docs/`. `AGENTS.md` contains repository-specific correctness rules that apply even to small changes.

## Development prerequisites

- Git
- JDK 25 or newer for the 1.4.0 Gradle toolchain/build
- the repository Gradle wrapper (`gradlew.bat` on Windows)
- Node.js and pnpm for `syncmoney-web`
- a compatible Paper/Folia/Canvas test server for runtime acceptance
- Redis and/or SQL only when testing modes/features that require them
- licensed CMI supplied locally when testing CMI integration; CMI is not distributed by this repository

**Current R63 status: BLOCKED / NOT VERIFIED.** The user authorized a durable recovery architecture amendment for live queued /pay only; direct Vault/plugin recovery remains DEFERRED / NOT VERIFIED under ADR-07. The Java 25.0.4 / Gradle 9.8.0 source/package gate passed 59 actionable tasks (59 executed): 170 unit suites / 945 tests plus 9 Sync integration suites / 44 tests (179 suites / 989 tests total), with zero failures, errors, or skips. Architecture, packaging (classfile major 69), API-consumer/acceptance packaging, Central publisher isolation, and frontend typecheck, 4 unit files / 22 tests, and production build passed. Current core JAR: 31,646,522 bytes / SHA-256 BB8C014ADB37CD407AEEED93BDDE16EBE13BA64272D0511F26155DB7EF3B1916; PAPI JAR: 13,872 bytes / SHA-256 A4D893AC4892E9E715A1710A4760D7FB168EE84A7987BC43D1BAEA86EC6C319C; acceptance JAR: 54,905 bytes / SHA-256 29C41C996026B6275AD9276587D05E800759C710E4B485E5891CF1F85C281824. The production paired /pay recovery path passed isolated Redis integration coverage; this is source-level evidence and does not establish Paper runtime or Beta acceptance. Exact-candidate Paper runtime/T22 Beta, storage-service integration, minimum-Paper SQL, licensed/live CMI (authorization UNKNOWN), broad V03, full public API compatibility, and T21 completion remain NOT VERIFIED or BLOCKED; Stable and external release remain NOT VERIFIED / NOT PERFORMED. Stable metadata remains 1.3.2; no external release action occurred.

**Historical R62 status (superseded by the R63 update below): BLOCKED / NOT VERIFIED.** The designated candidate is linco/t21-currentmax-guard at 66a25c4f8327932eae850bcfa52b1060942b1fd3 (350 commits ahead / 0 behind main, no upstream) with a dirty worktree; target main remains 71cc55dfeefe9a8f40a3835b051ddd324d353767, clean and equal to origin/main, with stable metadata 1.3.2. The R62 :syncmoney-sync:integrationTest run executed 44 tests: 41 passed, 3 failed, 0 errors, 0 skipped. All 23 raw Lua receipt-fixture tests passed. The 3 remaining PayLuaScriptManagerNoscriptIT failures are in the ADR-07-held /pay path (INVALID_OPERATION_RECEIPT / SENT_UNKNOWN), so the full integration task failed and this behavior remains BLOCKED / NOT VERIFIED. verifyArchitecture passed; -PreleaseVersion=1.4.0 verifyPackaging passed with classfile major 69; storage-service integration was not run. Four on-disk candidate JARs were rehashed, but there is no current source manifest, so these hashes are observations rather than source-bound release pins. The user retained ADR-07, did not adopt ADR-15, deferred direct-transfer recovery repair/integration, and left main unmerged. R5 findings about restart persistence of direct-transfer SENT_UNKNOWN quarantine and operator resolution remain BLOCKED / NOT VERIFIED. R59 Bukkit test evidence was not rerun; its 720-entry R5 manifest is historical. T21 remains PARTIAL / IN_PROGRESS; broad V03 remains SKIPPED / NOT VERIFIED; minimum-Paper SQL and licensed/live CMI remain BLOCKED / NOT VERIFIED (CMI authorization UNKNOWN); exact-candidate runtime/Beta, storage-service integration, and full public API compatibility remain NOT VERIFIED. Stable/external release remains NOT VERIFIED / NOT PERFORMED. No runtime, merge, or external release action occurred.

## Repository layout

| Path | Purpose |
|---|---|
| `syncmoney-bukkit/src/main/java/noietime/syncmoney` | Core plugin implementation |
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

Preserve per-account Redis version ordering for Redis-backed economy updates, duplicate/echo suppression, insufficient-funds behavior, queue backpressure/recovery, and shutdown draining of accepted writes. CMI retains its legacy timestamp-derived version, which is not globally monotonic; delayed CMI work must re-check locally observed freshness without claiming global ordering.

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

## Release workflow

The core repository is the canonical source for the Web Admin. The committed stable version lives in root `build.gradle`; preview builds pass `-PreleaseVersion` from CI so alpha and beta versions do not need to be committed as source metadata. CI uses the repository variable `NEXT_VERSION` when set and otherwise falls back to the root version. The hosted variable value is not available in this checkout, so documentation must not assume a fixed preview base.

CI runs on pull requests and `main`, builds Java and Web artifacts, and labels preview artifacts as `<base-version>-alpha.<run-number>`. These artifacts are retained by GitHub Actions and do not create public Releases.

Release tags use no prefix in this repository:

- `<version>-alpha.1` or `<version>-beta.1` publishes a prerelease;
- `<version>` publishes a stable release after the `production-release` environment approval and a `CHANGELOG.md` section.

The release workflow synchronizes the canonical `syncmoney-web` source to [Misty4119/Syncmoney-web](https://github.com/Misty4119/Syncmoney-web), applies the release version, and pushes the corresponding `v`-prefixed tag there first. The Web workflow publishes the deterministic `syncmoney-web.tar.gz` source archive and checksum; only after that release exists does the core workflow publish the plugin and PlaceholderAPI JARs. The cross-repository job requires the fine-grained `SYNC_WEB_RELEASE_TOKEN` secret with write access limited to the Web repository.

For a frontend change, run `pnpm build:embedded` in `syncmoney-web`. It clears and replaces `src/main/resources/syncmoney-web/dist` and regenerates the `rootFiles`, `assetFiles`, and `iconFiles` arrays in `WebAdminServer.extractIndividualFiles`. Review and commit those generated changes together.

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
