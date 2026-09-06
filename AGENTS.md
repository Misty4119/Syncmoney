# AGENTS.md — Syncmoney

## Scope and sources of truth

Syncmoney provides a Vault-compatible economy and Redis-backed cross-server balance synchronization. It does not replicate arbitrary permissions or commands; permission checks control its own commands and administration.

- Core plugin: `src/main/java/noietime/syncmoney`; defaults and descriptors: `src/main/resources`.
- PlaceholderAPI expansion: `syncmoney-papi-expansion`; reflection is a compatibility seam with the core plugin.
- Web administration: `syncmoney-web`; the embedded bundle in `src/main/resources/syncmoney-web/dist` is a runtime asset.
- For user-visible behavior, read `README.md` and the relevant config class/default YAML together. For REST changes, consult `docs/API_REFERENCE.md` and the actual handler. Historical developer documents may lag behind code.
- For server acceptance, read `tools/plugdev/README.md`. PlugDev is external tooling, not a production dependency.
- Root `build.gradle` owns the release version; resource expansion and manifests supply runtime metadata. PAPI inherits the root version. Update frontend package/public metadata and rebuild the embedded bundle in the same release. Config/message schema versions are independent of release versions.

## Working sequence

1. Inspect `git status`, relevant history and the actual implementation before editing. Preserve unrelated worktree changes, especially dependency/build changes.
2. Trace callers, reflection usage and shutdown ownership before removing a class or wrapper. Favor small behavior-preserving changes with regression tests.
3. Validate the modified module and its consumers; a successful build is not proof of Folia safety or cross-server consistency.
4. Before committing, inspect staged paths and generated artifacts. Keep local reports under ignored `Proposal/`; preserve `Proposal/` and `reference/` on disk.

## Economy invariants

- Use `BigDecimal` and existing normalization rules. A failed transaction must not create/destroy funds; test insufficient funds, concurrent writes, queue saturation and recovery.
- Follow `EconomyFacade`, `MemoryStateManager`, `TransactionWriter`, `TransferOrchestrator` and the mode router rather than bypassing their state/version handling.
- Vault returns synchronously. Keep hot-path reads memory-backed and dispatch storage work off server region threads. Cache misses and existing fallback paths deserve explicit review: do not add blocking Redis/SQL/Mojang lookups to placeholders, tab completion or entity tasks.
- Preserve monotonic versions and echo suppression when applying Pub/Sub or delayed CMI updates. Recheck stale work before changing player state.
- Queued economic events represent accepted writes. Logout, teleport timeout and shutdown must not silently discard them. Stop producers, drain consumers while their dependencies are alive, then close storage.
- CMI mode leaves CMI as the economy authority; its API must run on the owning entity thread. Database migration and Shadow backups are separate workflows, not live authority switches.

## Scheduler rules

- Compile against the supported Paper baseline; use Paper's common schedulers on Paper, Folia and Canvas. Platform labels and `folia-supported` are not safety proofs.
- Pure I/O/data work: async scheduler or an owned background executor.
- Plugin-level/global operations: global region scheduler; it does not own arbitrary players or worlds.
- Player messages, CMI player mutations and teleports: the player's entity scheduler. Use `PlayerLookupUtil` routing helpers and account for retirement/disconnect.
- Snapshot global collections, then dispatch each player's work to its owner. An async callback must re-enter the correct owner before touching entities.
- Use `teleportAsync`; preserve destination/cause and cancel obsolete deferred requests. Never block a region waiting for another region.

## Optional-module lifecycle

- One owner creates, starts and closes each module. `BreakerManager` owns the only `PlayerTransactionGuard`; `EconomyServiceManager` receives it through the facade.
- Check config before constructing optional executors, schedules, schemas, folders, subscriptions or HTTP listeners. A disabled module may retain a lightweight no-op/query facade and a command explaining that it is disabled.
- Store cancellation handles. Cover normal shutdown and partial initialization failure. Closing one resource must not prevent unrelated resources from being released.
- Audit cleanup/export require their parent audit switch; player protection is independent of the global breaker switch. Preserve existing defaults unless a product decision changes them.
- `SyncmoneyConfig` is a runtime snapshot. `ConfigReloadPolicy` defines live settings; changes requiring new owners/connections must report restart-required before publishing a new snapshot. Do not silently apply half a configuration.

## Validation and release

```powershell
.\gradlew.bat test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar
cd syncmoney-web
pnpm typecheck
pnpm test:unit --run
pnpm build
```

Inspect `build.gradle` and `vite.config.ts` for bundle-copy behavior; verify the release JAR contains the current frontend and metadata. Build/test compilation uses the Java toolchain declared by Gradle; the test server must use the JVM required by its Minecraft version.

For scheduler/storage/lifecycle changes, run the PlugDev matrix and inspect actual server version, enabled plugin version, player-side commands and logs. Record separately: unit tests, runtime smoke, real-player tests, cross-server tests, unavailable optional dependencies. Claims in README must match observed coverage; future versions require new acceptance runs.

## Repository hygiene

Track source, public docs and reproducible test scripts/config. Keep server JARs, worlds, runtime YAML, RCON/API secrets, database files, logs, reports, editor/agent caches and dependency directories ignored. Embedded web assets and the Gradle wrapper JAR are intentional tracked exceptions.

Review credentials without printing values. `.gitignore` does not remove already tracked secrets or rewrite history; report a discovered credential and require rotation before publishing. Keep public examples as placeholders and isolated tests bound to local infrastructure.
