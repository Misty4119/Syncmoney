# Syncmoney Architecture Overview

> **Audience**: Developers contributing to or extending Syncmoney
> **Version**: 1.1.2
> **Last Updated**: 2026-03-22 (refactored)

---

## Table of Contents

1. [System Architecture](#system-architecture)
2. [Module Structure](#module-structure)
3. [Data Flow](#data-flow)
4. [Thread Model](#thread-model)
5. [Storage Architecture](#storage-architecture)
6. [Web Backend Architecture](#web-backend-architecture)
7. [Frontend Architecture](#frontend-architecture)

---

## System Architecture

Syncmoney follows an **event-driven, optimistic-update** architecture inspired by the LMAX Disruptor pattern. The core design principle is **never block the main server thread** for Redis/DB operations.

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Minecraft Server                          │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Vault API Layer                                      │   │
│  │  ┌──────────────────────────────────────────────┐    │   │
│  │  │  SyncmoneyVaultProvider (1276 lines)          │    │   │
│  │  │  - Implements net.milkbowl.vault.Economy      │    │   │
│  │  │  - Name → UUID resolution via NameResolver    │    │   │
│  │  │  - Bank account support (Redis-backed)        │    │   │
│  │  └─────────────────────┬────────────────────────┘    │   │
│  └────────────────────────┼──────────────────────────────┘   │
│                           ▼                                   │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  Economy Core                                          │  │
│  │  ┌────────────────┐  ┌──────────────────────────────┐ │  │
│  │  │ EconomyMode    │  │ EconomyModeRouter (216 lines)│ │  │
│  │  │ Router         │──│ LOCAL → LocalEconomyHandler   │ │  │
│  │  │                │  │ SYNC  → EconomyFacade         │ │  │
│  │  │                │  │ CMI   → CMIEconomyHandler     │ │  │
│  │  │                │  │ LOCAL_REDIS → EconomyFacade   │ │  │
│  │  └────────────────┘  └──────────────┬───────────────┘ │  │
│  │                                      │                 │  │
│  │  ┌──────────────────────────────────▼───────────────┐ │  │
│  │  │ EconomyFacade (1064 lines)                        │ │  │
│  │  │ - ConcurrentHashMap<UUID, EconomyState>          │ │  │
│  │  │ - Optimistic locking (version-based)             │ │  │
│  │  │ - Memory-first read/write                        │ │  │
│  │  │ - Event queue (BlockingQueue, cap=50000)         │ │  │
│  │  └──────────────────────────────────┬───────────────┘ │  │
│  └─────────────────────────────────────┼─────────────────┘  │
│                                        │ async events        │
│  ┌─────────────────────────────────────▼─────────────────┐  │
│  │  Async Persistence Layer                               │  │
│  │  ┌────────────────┐  ┌────────────────────────────┐   │  │
│  │  │ EconomyEvent   │  │ CrossServerSyncManager     │   │  │
│  │  │ Consumer       │  │ - Redis Pub/Sub publish    │   │  │
│  │  │ (single thread)│  │ - Notify other servers     │   │  │
│  │  └───────┬────────┘  └────────────────────────────┘   │  │
│  │          │                                             │  │
│  │  ┌───────▼────────┐  ┌────────────────────────────┐   │  │
│  │  │ Redis (Jedis)  │  │ Database (HikariCP)        │   │  │
│  │  │ - Balance cache│  │ - MySQL/PostgreSQL/SQLite  │   │  │
│  │  │ - Pub/Sub      │  │ - DbWriteQueue + Consumer  │   │  │
│  │  │ - Lua scripts  │  │ - Batch writes             │   │  │
│  │  │ - ZSET baltop  │  │ - Audit log persistence    │   │  │
│  │  └────────────────┘  └────────────────────────────┘   │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                               │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  Web Admin (Undertow)                                  │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐│  │
│  │  │ REST API │  │ SSE      │  │ Static File Server   ││  │
│  │  │ Handlers │  │ Manager  │  │ (Vue 3 SPA)          ││  │
│  │  └──────────┘  └──────────┘  └──────────────────────┘│  │
│  └────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## Module Structure

The Java codebase (`noietime.syncmoney`) is organized into **27 packages** with **167 Java classes** (~36,480 lines):

### Core Layer (Plugin Entry)

| Package | Files | Purpose |
|---------|-------|---------|
| `(root)` | 2 | `Syncmoney.java` (876 lines), `PluginContext.java` (356 lines) |
| `config` | 19 | `SyncmoneyConfig` (Facade), 18 sub-config classes, 4 manager classes |
| `initialization` | 2 | `PluginInitializationManager`, `Initializable` |

**Config Class Architecture:**
```
SyncmoneyConfig (Facade)
├── RedisConfig
├── DatabaseConfig
├── MigrationConfig
├── ShadowSyncConfig
├── CircuitBreakerConfig
├── PlayerProtectionConfig
├── DiscordWebhookConfig
├── AuditConfig
├── CMIConfig
├── BaltopConfig
├── AdminPermissionConfig
├── PayConfig
├── DisplayConfig
├── TransferGuardConfig
├── VaultConfig
├── LocalConfig
├── CrossServerConfig
└── WebAdminConfig
```

### Economy Layer

| Package | Files | Purpose |
|---------|-------|---------|
| `economy` | 15 | Core economic operations, mode routing, event processing |
| `vault` | 6 | VaultProviderCore, VaultPlayerHandler, VaultTransferHandler, VaultBankHandler, VaultLuaScriptManager, VaultPluginDetector |

### Storage Layer

| Package | Files | Purpose |
|---------|-------|---------|
| `storage` | 5 | Redis, cache, storage coordination, transfer locks |
| `storage.db` | 3 | Database management, write queue, batch writer |

### Sync & Events

| Package | Files | Purpose |
|---------|-------|---------|
| `sync` | 5 | Cross-server sync, Pub/Sub, debounce |
| `event` | 7 | Bukkit events, internal event bus (`SyncmoneyEventBus`) |

### Security

| Package | Files | Purpose |
|---------|-------|---------|
| `breaker` | 9 | Circuit breaker, player guard, Discord webhook, resource monitor |
| `guard` | 1 | `PlayerTransferGuard` — server transfer protection |
| `permission` | 2 | Permission management, admin tier service |

### Audit & Shadow Sync

| Package | Files | Purpose |
|---------|-------|---------|
| `audit` | 10 | Audit logging, cleanup, export, Lua scripts, hybrid manager |
| `shadow` | 6 | Background sync, CMI writer, rollback protection |
| `shadow.storage` | 6 | Multi-database shadow storage implementations |

### Commands

| Package | Files | Purpose |
|---------|-------|---------|
| `command` | 19 | All commands, cooldown, pay confirmation/execution |
| `baltop` | 3 | Leaderboard command, manager, data model |

### Web Admin

| Package | Files | Purpose |
|---------|-------|---------|
| `web` | 3 | Service management, module config, web admin config |
| `web.server` | 2 | Undertow server (`WebAdminServer` 682 lines), route registry |
| `web.security` | 4 | Auth filter, rate limiter, permission checker, node API key store |
| `web.api.*` | 14 | REST API handlers (see [Web Backend Architecture](#web-backend-architecture)) |
| `web.builder` | 3 | Frontend auto-download/build/version check |
| `web.websocket` | 2 | WebSocket + SSE managers |

### Utilities

| Package | Files | Purpose |
|---------|-------|---------|
| `util` | 10 | Formatting, messages, JSON, platform detection, config merger |
| `uuid` | 1 | Name → UUID resolution |
| `listener` | 5 | Player join/quit, CMI economy, event listener management |
| `exception` | 3 | Custom exception hierarchy |
| `schema` | 1 | Database schema management |
| `migration` | 9 | CMI migration, backup, checkpoint resume, local-to-sync |

---

## Data Flow

### Read Path (Balance Query)

```
VaultAPI.getBalance(player)
  → SyncmoneyVaultProvider.getBalance()
    → NameResolver: playerName → UUID
    → EconomyFacade.getBalance(uuid)
      → 1. Check ConcurrentHashMap (O(1)) → HIT → return
      → 2. Check Redis (GET syncmoney:balance:{uuid}) → HIT → cache + return
      → 3. Query Database (SELECT balance FROM players) → HIT → cache + return
      → 4. Check LocalSQLite (fallback) → return 0 if not found
```

*Note: Internally, balance queries from player commands (e.g., `/money`) wrap this Read Path in an asynchronous task (`AsyncScheduler`) to ensure that cache misses (which fallback to Redis/Database) absolutely never block the main server thread.*

### Write Path (Deposit/Withdraw)

```
VaultAPI.depositPlayer(player, amount)
  → SyncmoneyVaultProvider.depositPlayer()
    → EconomyFacade.deposit(uuid, amount, source)
      → 1. Check circuit breaker → LOCKED → reject
      → 2. Check player protection → LOCKED → reject  
      → 3. Update ConcurrentHashMap (optimistic lock + version increment)
      → 4. Return SUCCESS immediately (no blocking!)
      → 5. Queue EconomyEvent to BlockingQueue
        → EconomyEventConsumer (background thread):
          → Redis: EVAL atomic_add_balance.lua
          → Database: INSERT/UPDATE via DbWriteQueue (Fallback: direct DB write → WAL overflow log)
          → Pub/Sub: Publish balance update to other servers
          → Audit: Record transaction
          → EventBus: Fire PostTransactionEvent
```

### Plugin API (Third-Party Integration)

Third-party plugins (e.g., chest shops, auction houses) should use the `SyncmoneyVaultProvider` extended API instead of standard Vault methods to bypass the pairing mechanism.

**Standard Vault flow (with pairing):**
```
Plugin withdrawPlayer() → SyncmoneyVaultProvider.withdrawPlayer()
  → Records to recentWithdrawals (30s sliding window)
Plugin depositPlayer() → SyncmoneyVaultProvider.depositPlayer()
  → findCorrelatedTransfer() ← May fail in high-frequency scenarios
```

**Plugin API flow (bypasses pairing):**
```
Plugin depositPlayerForPlugin() → EconomyFacade.pluginDeposit()
  → EconomyEvent.EventSource = PLUGIN_DEPOSIT
  → No pairing lookup, direct atomic operation
  → Uses atomic_plugin_transfer.lua with plugin attribution metadata
```

**New EventSource values:**

| Value | Purpose |
|-------|---------|
| `PLUGIN_DEPOSIT` | Third-party plugin deposit (bypasses Vault pairing) |
| `PLUGIN_WITHDRAW` | Third-party plugin withdrawal (bypasses Vault pairing) |

**Orphan VAULT_DEPOSIT handling:** When `findCorrelatedTransfer()` returns null (no matching withdrawal found), the deposit is now processed as `PLUGIN_DEPOSIT` instead of being rejected. This prevents money loss during high-frequency transactions while maintaining audit trail.

### Cross-Server Sync

```
Server A: Player deposits 1000
  → Pub/Sub publish: {uuid, newBalance, version, source}
    → Server B receives via PubsubSubscriber
      → EconomyFacade.updateMemoryState(uuid, balance, version)
        → Version check: only update if newVersion > currentVersion
        → ConcurrentHashMap updated
        → Player UI refresh (via EntityScheduler)
```

---

## Thread Model

### Folia Compatibility

Syncmoney is fully compatible with Folia's region-based threading. **`Bukkit.getScheduler()` is strictly forbidden.**

| Scheduler | Usage |
|-----------|-------|
| `AsyncScheduler` | Redis/DB communication, write queue processing, Pub/Sub subscription |
| `EntityScheduler` | Player UI updates (scoreboard, ActionBar) — must run on player's region thread |
| `GlobalRegionScheduler` | Periodic tasks (cleanup, shadow sync, heartbeat) |

### Key Thread Safety

- `EconomyFacade.economyStates` — `ConcurrentHashMap<UUID, EconomyState>`
- `EconomyState.balance` — Updated via optimistic locking (version comparison)
- `EconomyEventConsumer` — Single background thread (single-writer pattern). Replays overflow events on startup.
- `PluginContext` — Immutable after construction

---

## Storage Architecture

### Redis Keys

| Key Pattern | Type | Purpose |
|-------------|------|---------|
| `syncmoney:balance:{uuid}` | STRING | Player balance |
| `syncmoney:version:{uuid}` | STRING | Version counter (for optimistic locking) |
| `syncmoney:baltop` | ZSET | Leaderboard sorted set |
| `syncmoney:bank:{name}` | STRING | Bank account balance |
| `syncmoney:bank:version:{name}` | STRING | Bank version counter |
| `syncmoney:audit:{uuid}` | LIST | Audit log entries |

### Database Tables

| Table | Purpose |
|-------|---------|
| `players` | Player balances (UUID → DECIMAL(20,2)) |
| `syncmoney_audit_log` | Transaction audit trail (with millisecond sequence ordering) |

### Lua Scripts (8 scripts)

| Script | Purpose |
|--------|---------|
| `atomic_add_balance.lua` | Atomic deposit/withdraw with version increment |
| `atomic_set_balance.lua` | Atomic balance set |
| `atomic_transfer.lua` | Atomic player-to-player transfer |
| `atomic_audit.lua` | Atomic audit log entry |
| `atomic_bank_deposit.lua` | Atomic bank deposit |
| `atomic_bank_withdraw.lua` | Atomic bank withdrawal |
| `atomic_bank_transfer.lua` | Atomic bank-to-bank transfer |
| `atomic_plugin_transfer.lua` | Atomic plugin-initiated transfer (with plugin attribution metadata) |

---

## Web Backend Architecture

### Undertow Server

The web admin runs an embedded Undertow HTTP server (non-blocking, XNIO-based).

```
WebAdminServer (682 lines)
├── Static File Serving (Vue 3 SPA from dist/)
├── REST API Routes (/api/*)
│   ├── SystemApiHandler         → /api/system/*
│   ├── EconomyApiHandler        → /api/economy/*
│   ├── AuditApiHandler          → /api/audit/*
│   ├── ConfigApiHandler         → /api/config/*
│   ├── SettingsApiHandler       → /api/settings/*
│   ├── WsTokenHandler          → /api/auth/ws-token
│   ├── NodesApiHandler         → /api/nodes/* (Central Mode)
│   ├── CrossServerStatsApiHandler → /api/economy/cross-server-*
│   └── ApiExtensionManager      → /api/extensions/{name}/*
├── SSE Manager (/sse)
├── WebSocket Manager (/ws) [partial in v1.1.2]
└── Health Endpoint (/health)
```

### REST API Handler Packages

| Package | Handler | Routes |
|---------|---------|--------|
| `web.api.system` | `SystemApiHandler` | `/api/system/status`, `/api/system/redis`, `/api/system/breaker`, `/api/system/metrics` |
| `web.api.economy` | `EconomyApiHandler` | `/api/economy/stats`, `/api/economy/player/{uuid}/balance`, `/api/economy/top` |
| `web.api.audit` | `AuditApiHandler` | `/api/audit/player/{name}`, `/api/audit/search`, `/api/audit/search-cursor`, `/api/audit/stats` |
| `web.api.config` | `ConfigApiHandler` | `/api/config`, `/api/config/reload`, `/api/config/validate` |
| `web.api.settings` | `SettingsApiHandler` | `/api/settings`, `/api/settings/theme`, `/api/settings/language`, `/api/settings/timezone` |
| `web.api.auth` | `WsTokenHandler` | `/api/auth/ws-token` |
| `web.api.nodes` | `NodesApiHandler` + 4 sub-handlers | `/api/nodes`, `/api/nodes/status`, `/api/nodes/{index}`, `/api/nodes/{index}/ping`, `/api/nodes/{index}/proxy`, `/api/nodes/sync`, `/api/nodes/{index}/sync`, `/api/config/sync` |
| `web.api.crossserver` | `CrossServerStatsApiHandler` | `/api/economy/cross-server-stats`, `/api/economy/cross-server-top` |
| `web.api.extension` | `ApiExtensionManager` | `/api/extensions/{name}/*` (dynamic) |

#### Nodes API Sub-Handlers Architecture

The `NodesApiHandler` coordinates four specialized sub-handlers:

| Handler | Responsibility |
|---------|---------------|
| `NodeOperationsHandler` | CRUD operations for nodes (create, read, update, delete, ping) |
| `NodeStatusHandler` | Parallel fetching of detailed node status |
| `NodeProxyHandler` | HTTP request proxying to remote nodes |
| `ConfigSyncHandler` | Bidirectional configuration sync (push from central, receive on nodes) |

All sub-handlers inherit from `NodesApiContext` which provides common utilities (JSON parsing, SSRF validation, API key masking, permission checking).
```

### Security Layers

1. **ApiKeyAuthFilter** — Bearer token validation
2. **RateLimiter** — Per-IP request limiting (default: 60/min)
3. **PermissionChecker** — Endpoint-level permission validation

---

## Frontend Architecture

### Tech Stack

- **Vue 3** (Composition API, `<script setup>`)
- **Pinia 3** (state management)
- **Vue Router 5** (SPA routing with auth guards)
- **Axios** (HTTP client with interceptors)
- **TailwindCSS 3** (utility-first CSS)
- **vue-i18n 11** (internationalization)
- **Vite 6** (build tool with PWA plugin)

### State Management (Pinia Stores)

| Store | Purpose |
|-------|---------|
| `auth` | Authentication state (API key in localStorage) |
| `notification` | Dual-track notifications: Toast (temporary, 5s auto-dismiss) + Alert (persistent, localStorage-backed) |
| `settings` | Theme, language, timezone preferences |
| `sse` | SSE connection lifecycle management |

#### Notification System Architecture

The notification system implements a **dual-track** architecture:

- **Toast**: Temporary notifications displayed in the top-right corner, auto-dismiss after 5 seconds
- **Alert**: Persistent notifications stored in localStorage, displayed in a dropdown panel from the header bell icon

Notifications are categorized as: `system`, `security`, `transaction`, `audit`, `general`.

Key store methods:
- `addToast(type, title, message, category)` — Add temporary notification
- `addAlert(type, title, message, category)` — Add persistent alert
- `addBreakerNotification(state)` — Circuit breaker alerts (both Toast + Alert)
- `addSystemAlertNotification(message)` — System alerts (both Toast + Alert)
- `success/error/info/warning(title, message)` — Convenience shortcut methods

### API Integration

```
api/client.ts (Axios instance)
├── Request interceptor: Add Bearer token
├── Response interceptor: 401→login, 403/429/500→notification
│
services/
├── systemService.ts    → /api/system/*
├── economyService.ts   → /api/economy/*
├── auditService.ts     → /api/audit/*
└── configService.ts    → /api/config/*
│
composables/
├── useSystem.ts        → System status polling
├── useAudit.ts         → Audit log pagination
├── useSSE.ts           → SSE event handling (with exponential backoff & jitter)
└── useWebSocket.ts     → WebSocket connection (with exponential backoff & jitter)
```

### Build & Deploy

```bash
# Development (with HMR + API proxy to :8080)
pnpm dev

# Production build (outputs to dist/)
pnpm build

# dist/ is copied to src/main/resources/syncmoney-web/
# and embedded into the JAR via Gradle processResources
```
