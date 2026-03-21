# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

