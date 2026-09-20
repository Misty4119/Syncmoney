# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.2] - 2026-09-20

### Security

- **Frontend Dependency Remediation**: Updated the Web Admin dependency graph to patched Axios, Vite 6, Vitest 4.1.11, Happy DOM, PostCSS, and Sharp releases. Targeted pnpm overrides keep vulnerable transitive packages on patched versions without taking unrelated Java/runtime major upgrades.
- **Audit Baseline**: The resolved Web Admin dependency graph now reports zero known vulnerabilities with `pnpm audit`.

### Changed

- **Release Metadata**: Unified root, PlaceholderAPI, Web Admin, documentation, configuration metadata, and acceptance version checks on `1.3.2`.
- **Embedded Web Bundle**: Rebuilt the embedded Web Admin assets and synchronized `WebAdminServer.extractIndividualFiles` with the generated bundle.

## [1.3.1] - 2026-09-09

### Fixed

- **Adventure Runtime Compatibility**: Adventure and MiniMessage are now supplied by the server runtime instead of being partially relocated into the plugin JAR. This prevents the Adventure 4.x/5.x `ClickEvent` binary mismatch on Canvas 26.2 while retaining Paper 1.20.4 compatibility.
- **Message Safety**: Interactive MiniMessage tags are removed while colors and decorations remain available; message parsing now falls back safely on parse/linkage failures.

### Added

- **Support Reports**: Added `/syncmoney version`, `/syncmoney version full`, and `/syncmoney version save`. Full reports collect sanitized server, Java, dependency, module, Redis, and database diagnostics without exposing credentials or endpoints. Redis and database probes are read-only, run asynchronously, time out after three seconds, and report three latency samples.

## [1.3.0] - 2026-09-06

### Fixed

#### Scheduler Correctness (Folia / Paper / Canvas)
- **Entity Scheduler Routing**: All per-player work — CMI updates, payment notifications, breaker alerts, CMI Pub/Sub callbacks — now runs on the owning player's entity scheduler. Global and plugin-level operations use the global region scheduler. Legacy `BukkitScheduler` calls have been removed throughout.
- **Teleport Safety**: `PlayerTransferGuard` rewrites the deferred-teleport loop to use `player.getScheduler().runAtFixedRate()` and `teleportAsync()`. A newer teleport supersedes any stale deferred destination immediately. Pending economic writes are never silently discarded on logout or kick; only the deferred teleport task is cancelled.
- **Placeholder Hot Path**: `EconomyFacade.getBalanceForPlaceholder()` and `NameResolver.resolveUUIDForPlaceholder()` check in-memory state first and warm the cache asynchronously on a miss. PlaceholderAPI callers on any region thread never block on a cold Redis, database, or Mojang lookup.
- **Audit Cleanup Interval**: `AuditLogCleanup` was scheduling its repeating task with a tick value where hours were expected, causing the cleanup to fire every ~1 second instead of the configured interval. The task now uses `AsyncScheduler.runAtFixedRate()` with `TimeUnit.HOURS`. Cleanup and export tasks hold cancellable handles and stop cleanly on shutdown.

#### Module Lifecycle & Reload
- **Single Guard Owner**: `BreakerManager` is the sole owner of `PlayerTransactionGuard`. The duplicate construction path inside `EconomyServiceManager` has been removed.
- **Gated Optional Resources**: Breaker, Shadow, audit, and notifier modules check configuration before constructing executors, schedules, or subscriptions. Each failed or disabled module leaves the others unaffected.
- **Restart-Required Reload Gate**: `ConfigReloadPolicy` rejects changes to storage, connection, or service settings before the runtime snapshot is published. Only display, command, and permission keys are live-reloadable. A plain `/syncmoney reload` no longer tears down cross-server notifications.
- **Disabled Command Messages**: Commands for disabled modules (`audit`, `shadow`, `breaker`) return an explanatory message instead of throwing a null-pointer exception.
- **`OnlinePlayerRegistry` Reload**: The Redis subscription now closes cleanly during a reload without dropping the cross-server notification channel.

#### Web Admin
- **SSE Reconnect Race**: Token-refresh callbacks in `useSSE.ts` no longer attempt to reopen a session after `disconnect()` has been called.
- **`WebAdminServer` Resource Leaks**: Exception handling improved in the server lifecycle; the SSE session map is cleared on stop, preventing zombie callbacks from writing into closed channels.
- **Adventure / MiniMessage Linkage**: The Adventure dependency is pinned to `4.16.0`, matching the Paper 1.20.4 bundle. This prevents a `ShadowColorTag` `NoClassDefFoundError` at runtime.
- **Web UI Token Inconsistencies**: Color token and CSS variable mismatches corrected across `Button`, `Card`, `Input`, `Select`, `Switch`, `Sidebar`, and `Header` components and related views.

### Changed

#### Toolchain & Version Metadata
- Java 21 API and bytecode baseline; Gradle wrapper updated to 9.1 (supports running on Java 25 for newer server environments).
- `BuildVersion` reads version from a build-time-expanded `syncmoney-version.properties` resource, replacing the runtime `plugin.getDescription()` call.
- `ServerPlatformDetector` adds Canvas detection alongside Paper and Folia.
- PAPI expansion version is now derived from the root Gradle version; no more manual syncing.
- Core, PAPI, and web frontend metadata unified at 1.3.0.

#### Cleanup
- `WebModuleConfig` removed; its functionality was already covered by `WebAdminConfig`. The duplicate `EconomyFacade` construction it guarded is also gone. Reflection-facing PAPI wrappers are unchanged.

#### Web Admin Frontend
- `Badge`, `Button`, `Card`, `Input`, `Select`, `Switch`, `Header`, `Sidebar` components refactored for consistency.
- `ConfigView`, `LoginView`, `SettingsView`, `SystemStatusView`, `AuditLogFilters` views updated.
- Added `config` MSW mock handler for offline development and frontend unit tests.
- Frontend dist rebuilt at 1.3.0.

### Added

- **PlugDev Acceptance Tooling**: `tools/plugdev/` scripts and config for running an isolated multi-server development network. `AcceptanceProbe` is a standalone plugin triggered via RCON that exercises the full `EconomyFacade` API end-to-end.
- **New Unit Tests**: `ConfigReloadPolicyTest`, `PlayerTransferGuardTest`, `ConsumerShutdownTest`, `PlayerLookupUtilTest`, `DisabledAuditTest`, `DisabledBreakerTest`, `ResourceMonitorTest`, `RedisOnlyBaltopTest`.

### Validated Acceptance Matrix

Paper 1.20.4, Paper 26.2, Folia 26.2 BETA, Canvas 26.2 — multi-backend (PostgreSQL + Redis), two-server network, all unit and integration tests passing.

### Previously Unlisted 1.2.x Changes

- **1.2.1** (`1f816df`): dependency updates, PAPI formatting/reflection enhancements, schema identifier fixes, independent expansion packaging.
- **1.2.2** (`cb641ad`): VaultUnlocked/Vault2 runtime detection, isolated integration loader, provider/registrar and compatibility tests.
- **1.2.3** (`10d914b`): Vault provider/transfer handling consolidation and redundant logic removal.

These entries summarize tagged Git history and are not newly reimplemented features.

## [1.2.0] - 2026-06-26

### Added

#### Cross-Server Online Players
- **Online Player Registry**: New `OnlinePlayerRegistry` tracks online players across servers via Redis heartbeat, powering command tab completion.

#### CMI Sync Architecture
- **CMI API Layer**: New `CMIApi`, `CMIPubsubHandler`, and `CMIVersioning` with dedicated Pub/Sub channels for CMI mode cross-server sync.

#### Core Refactoring
- **Economy Core Split**: Extracted `MemoryStateManager`, `TransactionWriter`, and `TransferOrchestrator` from `EconomyFacade`.
- **Circuit Breaker Lock Reasons**: Added `LockReason` enum to replace string-based lock cause matching.
- **Web Backend Modularization**: Introduced `RouteRegistry`, `StaticFileHandler`, `CorsHandler`, and `AbstractApiHandler`.
- **Audit Log Frontend**: Refactored audit page into `useAuditData` / `useAuditExport` composables and sub-components.
- **Test Coverage**: Expanded unit tests for CMI versioning, debounce, Vault, Web API, and PAPI expansion.

### Fixed

#### CMI Mode Cross-Server Sync
- **Authority & Publishing**: Rewrote CMI sync to use CMI API as the local authority; publishes absolute balances with monotonic version numbers instead of runtime CMI database queries.
- **Echo Loop Prevention**: Added `suppressOutbound` to stop re-publish loops when applying remote balances into local CMI.
- **Join Reconcile**: Version-aware balance reconcile on player join to fix cross-server balance drift.
- **Event vs Polling**: Disables polling when CMI events are available, preventing duplicate publishes during rapid `/cmi pay` spam.
- **Vault Transfer Publish**: Vault transfers in CMI mode now correctly publish via the CMI channel (`FIX-CMI-VAULT-PUBLISH`).
- **Cross-Server Pay Notification**: Incoming payment notifications now display the payer's name.

#### Scheduler & Folia
- **Periodic Version Check**: Fixed `GlobalRegionScheduler` interval to use correct tick units (actually runs every 5 minutes).
- **Folia Compatibility**: CMI polling and Redis I/O moved to Async / Global Region schedulers.

### Changed

#### Command Tab Completion
- **Cross-Server Player Suggestions**: `/pay`, `/money`, `/syncmoney admin`, and related commands now suggest all online players across the network (local + cross-server via Redis); `/pay` no longer suggests offline cached names.

#### Miscellaneous
- **PAPI Expansion**: Merged reflection utilities into `PlaceholderHandler`, reducing class count.
- **Discord Webhook**: Simplified payload construction using Jackson.
- **Web Admin**: Bundled frontend version bumped to 1.2.0.

---

## [1.1.3] - 2026-04-03

### Fixed

#### Folia + Paper Cross-Server Environment
- **Orphan VAULT_DEPOSIT Log Spam**: Reduced log level from WARNING to FINE and batched summary output (every 100 events) to prevent console spam in cross-server setups.
- **Player Transfer Block**: Fixed players getting permanently stuck during teleport when pending economic events existed. Transfer now forces after timeout and clears tracking state to prevent deadlock.

#### Write Queue & Overflow Handling
- **Backpressure Threshold**: Lowered from 80% to 70% for earlier rejection of new events under heavy load.
- **Overflow WAL Recovery**: Added `replayOverflowEvents()` on startup to recover dropped events from Write-Ahead Log.
- **DB Fallback**: Added direct DB write fallback when `DbWriteQueue` is full.

---

## [1.1.2] - 2026-03-22

### Fixed

#### Vault Economy Provider
- **Null Check**: `VaultPluginDetector` adds config null check to prevent NPE when config is not loaded.
- **Orphan Deposit Recovery**: `VaultProviderCore` adds orphan deposit recovery mechanism, automatically converting high-frequency transaction failures to PLUGIN_DEPOSIT to prevent money loss.

#### Configuration
- **Backward Compatibility**: `PlayerProtectionConfig` supports dual path reading (`circuit-breaker.player-protection.*` and `player-protection.*`) for backward compatibility during upgrades.

---

## [1.1.1] - 2026-03-21

### Added

#### Central Mode & Node Management
- **Central Mode Dashboard**: New `CentralDashboardView` for monitoring all registered nodes with aggregated cross-server statistics (`CrossServerStatsApiHandler`).
- **Node Health Checker**: Background service (`NodeHealthChecker`) performs health checks every 30 seconds with configurable thresholds, broadcasting status via SSE to `system` channel.
- **Node Operations API**: Full CRUD operations (`NodeOperationsHandler`) for managing node registrations with ping/latency testing.
- **Node Proxy Handler**: `NodeProxyHandler` enables central server to proxy requests to other nodes for unified API access.
- **Config Sync**: `ConfigSyncHandler` supports push-based configuration synchronization from central server to all nodes (`/api/nodes/sync`).
- **SSRF Protection**: `NodesApiContext.isUrlAllowed()` blocks private IP ranges (`10.*`, `172.16-31.*`, `192.168.*`, `127.*`, `0.*`, etc.), localhost, `.local` hostnames, and enforces `http`/`https` scheme only.

#### Third-Party Plugin API (Developer API)
- **`PLUGIN_DEPOSIT` / `PLUGIN_WITHDRAW` Events**: New `EventSource` enum values in `EconomyEvent` enabling third-party plugins to trigger economy changes bypassing Vault pairing logic.
- **Direct API Methods**: `EconomyFacade.pluginDeposit()`, `pluginWithdraw()`, `pluginAtomicTransfer()` methods for plugin-initiated transactions with full CircuitBreaker protection.
- **Vault Provider Bridge**: `VaultProviderCore.depositPlayerForPlugin()` / `withdrawPlayerForPlugin()` delegate to `EconomyFacade.pluginDeposit()` / `pluginWithdraw()` with full CircuitBreaker and AsyncPreTransactionEvent protection; `pluginTransfer()` uses `atomic_transfer.lua` for atomic cross-player transfers.

#### Database & Storage
- **PostgreSQL PreparedStatement Caching**: HikariCP configured with `prepareThreshold=1` and `cacheMode=PREPARE` for improved query performance.
- **PostgreSQL Upsert Syntax**: Migrated to `ON CONFLICT (id) DO UPDATE SET` pattern replacing MySQL's `ON DUPLICATE KEY UPDATE`.
- **`BIGSERIAL` for Auto-Increment**: PostgreSQL tables use `BIGSERIAL` primary keys instead of `AUTO_INCREMENT`.
- **Dedicated `PostgresShadowStorage`**: Full PostgreSQL-specific shadow sync implementation with connection pool tuning and auto-database creation.

### Changed

#### Frontend Improvements
- **Total Players from Database**: `BaltopManager.getTotalRegisteredPlayers()` now queries `COUNT(*) FROM players WHERE balance > 0` directly from database, exposed via `/api/economy/stats`. `%syncmoney_total_players%` PAPI expansion returns this database-derived value.
- **Toast Notification System**: `NotificationStore` provides unified notification management with `addToast()`, `addAlert()`, `addBreakerNotification()`, and `addTransactionNotification()`. `NotificationToast.vue` component includes CSS transition animations. Global error interceptor in `client.ts` automatically displays error/success toasts for all API responses.

#### SSE & Real-Time Communications
- **`node_status` SSE Event (Partial)**: `NodeHealthChecker` broadcasts `{"type":"node_status","event":"NodeStatusChange",...}` to the `system` SSE channel. Frontend `useSSE.ts` handler not yet implemented (tracked for future release).

#### Code Refactoring
- **VaultProvider Refactoring**: `SyncmoneyVaultProvider` (1276 lines) split into 7 focused classes:
  - `SyncmoneyVaultProvider` — Thin Vault facade (505 lines)
  - `VaultProviderCore` — Core Vault API delegation (658 lines)
  - `VaultPlayerHandler` — Player account & balance operations (165 lines)
  - `VaultTransferHandler` — Transfer correlation & rollback (317 lines)
  - `VaultBankHandler` — Bank operations with Lua scripts (524 lines)
  - `VaultLuaScriptManager` — Lua script SHA caching (90 lines)
  - `VaultPluginDetector` — Calling plugin detection via StackWalker (56 lines)
- **SyncmoneyConfig Refactoring**: Adopted Facade pattern with 18 sub-configuration classes (`RedisConfig`, `DatabaseConfig`, `NodeConfig`, `CircuitBreakerConfig`, etc.) providing unified `config.redis()`, `config.database()`, etc. accessors.

### Fixed

- **PostgreSQL Index Creation**: Fixed `CREATE INDEX IF NOT EXISTS` compatibility for PostgreSQL in shadow sync schema initialization.
- *(Note: All critical fixes from 1.1.1-patch1 have been integrated)*

---

## [1.1.0] - 2026-03-20

### Added

#### Web Interface & Admin Dashboard
- **Web Dashboard**: Brand new built-in administration web interface constructed with Vue 3, Vite, and TailwindCSS (`syncmoney-web`).
- **Internationalization (i18n)**: Full support for English (`en-US`) and Traditional Chinese (`zh-TW`) with seamless dynamic switching.
- **Theme Support**: Includes dynamic Dark/Light theme toggle with state persistence.

#### Developer API & REST API
- **System API**: Provided new `/api/system/status`, `/api/system/redis`, `/api/system/breaker`, and `/api/system/metrics` endpoints.
- **Economy & Audit API**: Retrieve total supply, player balances, top statistics, and transaction audit logs seamlessly.
- **Settings & Config API**: REST interface to read and update plugin configurations dynamically (`/api/config/reload`).
- **Real-Time Communications**: Added WebSocket support for instant transaction & circuit breaker alerts alongside Server-Sent Events (SSE).

#### Core System & Infrastructure
- **Initialization Manager**: Introduced `PluginInitializationManager` to reliably coordinate component startup/shutdown dependencies.
- **Schema Manager**: Added `SchemaManager` for incremental database schema upgrades, automatic index building, and field completion.
- **Config & Message Merger**: Introduced `ConfigMerger` to safely auto-update configuration files (v1.0.0 → v1.1.0) without destructive overwrites.
- **Lua Support Upgrade**: Expanded Redis Lua scripts with new atomic operations (`atomic_bank_deposit`, `atomic_bank_withdraw`, `atomic_bank_transfer`).
- **Testing**: Enormous test coverage increase using native Java Unit Tests and Frontend Playwright E2E suites.

#### Event System (API for developers)
- Added `SyncmoneyEventBus` acting as the central event bus for internal and third-party developers.
- Introduced `AsyncPreTransactionEvent`, `PostTransactionEvent`, `ShadowSyncEvent` and `TransactionCircuitBreakEvent`.

#### Security & Protection
- **API Protection**: Included automated detection blocking insecure `change-me` API keys along with `RateLimiter` structures securing the REST API.
- **Player Protection System**: Precise player-based exact transaction rate-limits with built-in auto-ban and warning features to prevent exploits.
- **Discord Alerts**: Real-time webhook notifications for abnormal resource spikes, network failures, or circuit breaker status triggers.

### Changed

- **Code Refactoring**: Major codebase architectural shifts; logic decoupled from `Syncmoney.java` and commands (`PayCommand`) into organized managers like `PayConfirmationManager`, `PluginContext`, and storage layers, drastically removing technical debt.
- **Commenting Standardization**: Executed a massive global refactoring of all Javadoc and block comments across Backend, Web Frontend, and PAPI Expansions. Enforced a rigorous `[SYNC-XXX]` English tagging standard while purging all deprecated inline and non-English comments.
- **Audit Logging**: Enhanced `AuditLogger` throughput with robust batching mechanisms via the new `HybridAuditManager`.
- **Database Schema**: Significant query performance improvements with added database indexes for audit logs.
- **Configuration Upgrade**: Brought `config.yml` to `config-version: 11` featuring new web admin settings and `decimalPlaces` configuration.
- **PAPI Expansion Updates**: Integrated missing `expansions.yml` and strengthened internal version compatibility for `syncmoney-papi-expansion`.
- **Shadow Sync Iteration**: Restructured state-rollback logic for smoother cross-server inconsistency resolutions.

### Fixed

- **Message System**: Discarded hardcoded messages within `CMIEconomyListener` and unified them into `MessageHelper` dynamic mapping.
- **Web Interface Bugs**: Rectified static mock data versions and addressed broken hardcoded i18n placeholders (e.g., page titles).
- *(Note: All critical fixes from 1.1.0-patch1 have been integrated: `/syncmoney migrate` registration issues, Folia compatibility regressions, internal path variables, and Adventure Text API empty page bugs)*

---

## [1.0.0] - 2026-03-01

### Added
- Initial release
- **Cross-server economy synchronization** via Redis pub/sub
- **Redis-based distributed caching** for high performance
- **Database support**: SQLite, MySQL, PostgreSQL
- **Vault API integration**: Compatible with Vault-based plugins
- **PlaceholderAPI expansion**: `%syncmoney_balance%`, `%syncmoney_balance_formatted%`, etc.
- **CMI economy migration tool**: Import existing CMI economy data
- **Web Admin interface**: Basic dashboard and configuration
- **Audit logging system**: Full transaction history with search
- **Circuit breaker protection**: Prevents economy exploits during outages
- **Shadow sync mechanism**: Background data consistency verification
