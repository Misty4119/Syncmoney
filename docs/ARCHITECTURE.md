# Syncmoney Architecture

> Project version: `1.3.2`
> Configuration schema: `12`
> Build toolchain: Java 21
> This document describes the implementation in the current repository. Code and executable configuration remain authoritative.

Traditional Chinese: [`ARCHITECTURE.zh_tw.md`](ARCHITECTURE.zh_tw.md)

## 1. System boundary

Syncmoney is a Minecraft economy plugin with a synchronous Vault-facing API and asynchronous persistence/synchronization. Its core responsibility is money state and the infrastructure required to keep that state safe across server threads, Redis, SQL, optional CMI authority, auditing, protection, and Web Admin.

The repository has three runtime deliverables:

- the core plugin from `src/main/java/noietime/syncmoney`;
- the separate PlaceholderAPI expansion from `syncmoney-papi-expansion`;
- the Vue Web Admin frontend from `syncmoney-web`, embedded into the core JAR at `src/main/resources/syncmoney-web/dist`.

`tools/plugdev` is acceptance tooling and is not a production dependency.

## 2. Architectural priorities

The implementation is shaped by five constraints:

1. **Vault calls are synchronous.** Balance reads used by Vault must normally complete from memory. Redis/SQL/network work must not be inserted into hot server or entity paths.
2. **Money must not be lost or duplicated.** Accepted mutations have an explicit asynchronous persistence path and shutdown drain semantics.
3. **Distributed state is versioned.** Balance updates carry monotonic versions; stale remote work must not overwrite newer local state.
4. **Folia ownership matters.** Player/entity work runs on the owning entity scheduler, while pure I/O can run asynchronously.
5. **Optional modules have explicit owners.** Disabled features should not start unrelated executors, schemas, listeners, subscriptions, or HTTP services.

## 3. Major runtime layers

```text
Vault / commands / PAPI / Web Admin
              |
              v
      Economy mode routing
              |
     +--------+---------+
     |                  |
     v                  v
Syncmoney economy      CMI authority
     |
     v
MemoryStateManager
     |
     v
TransactionWriter / TransferOrchestrator
     |
     +----------+-------------+----------------+
     |          |             |                |
     v          v             v                v
 accepted   Redis/Lua     SQL/local        Pub/Sub /
 events     operations    persistence      audit/events
```

### 3.1 Plugin lifecycle

`Syncmoney` is the Bukkit/Paper entry point and composes the runtime managers. Lifecycle order is important because accepted economy writes can still depend on audit, storage, and synchronization components during shutdown.

The observed shutdown sequence is:

1. Web service manager
2. Command service manager
3. Permission manager
4. Listener service manager
5. Sync manager
6. Event consumer manager, including draining accepted writes while dependencies are alive
7. Economy service manager
8. Audit service manager
9. Breaker manager
10. Baltop save
11. Storage manager
12. `SyncmoneyEventBus.clearAll()`

Changes to this ordering require a dependency analysis, not just a compile check.

### 3.2 Economy mode routing

`economy.mode` accepts:

- `auto`
- `local`
- `local_redis`
- `sync`
- `cmi`

The internal router selects the active implementation. In Syncmoney-owned modes, the economy facade, memory state, writer, persistence, and synchronization paths cooperate. In `cmi` mode, CMI remains the economy authority; Syncmoney observes/synchronizes it rather than silently becoming the source of truth.

Migration and Shadow Sync are separate explicit workflows. They are not live authority switches.

## 4. Economy state and transaction flow

### 4.1 Money representation

Money is represented with `BigDecimal` and existing normalization rules. New code must preserve precision, configured decimal behavior, non-negative/insufficient-funds constraints, and the current response semantics.

### 4.2 Read path

Vault-facing balance reads are designed to be memory-first. Cache warming and slower persistence lookups belong on asynchronous paths. Placeholder evaluation follows the same rule: a cold lookup must not turn a player/entity scheduler path into a blocking Redis, SQL, Mojang, or filesystem call.

Conceptually:

```text
caller -> mode router -> memory state -> immediate result
                         |
                         +-> asynchronous warm/reconcile when required
```

### 4.3 Mutation path

Syncmoney-owned deposits, withdrawals, sets, and transfers flow through the existing economy facade/state/writer/orchestrator boundaries rather than mutating persistence directly.

Important semantics:

- `AsyncPreTransactionEvent` is fired by `TransactionWriter` for supported mutations and cancellation is honored.
- Accepted writes become queued economic work; they must not be silently discarded on ordinary logout, teleport timeout, or shutdown.
- `PostTransactionEvent` is emitted after the result is known and is also used by live telemetry.
- Transfers must preserve both sides of the operation under failure; insufficient funds and partial persistence failure are correctness cases.
- Queue saturation/backpressure and recovery paths are part of the money-safety contract.

## 5. Distributed synchronization

### 5.1 Versions

Balance state carries a monotonically increasing version. Remote or delayed updates must compare versions before replacing state. This prevents an older Pub/Sub delivery or asynchronous callback from overwriting newer data.

### 5.2 Redis

Representative keys include:

| Key | Purpose |
|---|---|
| `syncmoney:balance:{uuid}` | Player balance |
| `syncmoney:version:{uuid}` | Balance version |
| `syncmoney:online:players` | Cross-server online-player set/state |
| `syncmoney:online:player:*` | Per-player online metadata |
| `syncmoney:baltop` | Ranking data |
| audit-related keys | Recent/index/dedup state |
| bank / bank-owner / bank-version keys | Vault bank state |

Primary Pub/Sub channels are:

- `syncmoney:balance:update`
- `syncmoney:cmi:balance:update`

The second channel belongs to CMI authority synchronization. Echo suppression is required when a remote CMI balance is applied locally.

### 5.3 SQL

The shared `players` table contains the core durable player record:

| Column | Shape |
|---|---|
| `uuid` | `VARCHAR(36)`, primary key |
| `player_name` | `VARCHAR(16)` |
| `balance` | `DECIMAL(20,2)` |
| `version` | `BIGINT` |
| `last_server` | `VARCHAR(64)` |
| `updated_at` | timestamp |

Audit, local persistence, schema migration, Shadow Sync, and other features use additional tables/workflows. Do not infer those contracts solely from the `players` table.

## 6. Scheduler and concurrency model

Syncmoney targets Paper-compatible scheduler APIs and declares `folia-supported: true`, but the declaration does not itself prove thread safety.

Use these ownership rules:

- **Pure I/O and data work:** Paper async scheduler or a clearly owned background executor.
- **Plugin/global operations:** global region scheduler.
- **Player messages, teleports, CMI player mutations, entity access:** the player's entity scheduler.
- **Teleports:** use `teleportAsync`.
- **Cross-player/global iteration:** snapshot global data first, then dispatch each player operation to that player's owner.
- **Async callbacks:** re-enter the correct scheduler before touching Bukkit entities.

Never block one region while waiting for another region.

## 7. Optional modules and protection

### 7.1 Breaker and player protection

`BreakerManager` owns the single `PlayerTransactionGuard`. Economy services receive the guard instead of creating independent copies.

Global breaker state and per-player protection are related safeguards but are not the same enable switch. Resource monitoring, player transaction limits, webhook notifications, and breaker state must keep their existing ownership and configuration boundaries.

### 7.2 Audit

Audit is optional. Cleanup/export behavior depends on the parent audit switch. When audit is disabled, the Web API reports `503 FEATURE_DISABLED` for audit operations instead of pretending data exists.

### 7.3 Shadow Sync and migration

Shadow Sync, CMI migration, and local-to-sync migration are explicit maintenance/data workflows. They must keep separate lifecycle, storage, and authority semantics from live economy routing.

## 8. Configuration model

`SyncmoneyConfig` represents a runtime configuration snapshot. `ConfigReloadPolicy` currently permits live reload only under:

- `display`
- `pay`
- `permissions`
- `admin-permissions`
- `debug`

Changes that require new connections, owners, listeners, executors, schemas, subscriptions, or economy authority are restart-required unless the reload policy and lifecycle implementation are deliberately expanded.

`server-name` must be configured. A blank server name prevents normal operation.

## 9. Web Admin backend

The embedded backend uses Undertow.

### 9.1 HTTP pipeline

- `/health` is unauthenticated.
- Normal `/api/*` routes require `Authorization: Bearer <api-key>`.
- `ApiKeyAuthFilter` uses constant-time key comparison and can return `429 RATE_LIMITED`.
- `X-Forwarded-For` is only trusted when `web-admin.security.trust-proxy=true`.
- Route exceptions are normalized by `HttpHandlerRegistry`.
- Static frontend assets are served from the embedded distribution.

The shipped YAML keeps Web Admin disabled and binds to `localhost:8080`. It ships `change-me-in-production` as the API key placeholder. Validation warns about that value, but the current auto-disable check compares only exact `change-me`; operators must replace the shipped placeholder before exposing Web Admin.

### 9.2 Route registration

Handlers register into `HttpHandlerRegistry`. Registration uses replacement semantics for the same method/path. Both `SystemApiHandler` and `NodesApiHandler` register `GET /api/nodes` and `GET /api/nodes/status`; `NodesApiHandler` is registered later and therefore provides the effective handlers.

### 9.3 SSE

The implemented frontend live channel is **`/api/sse`**.

The frontend first obtains a one-time token from `POST /api/auth/ws-token`. The token is a UUID, is valid for 60,000 ms, and is consumed on successful validation. `SseManager` accepts either:

- Bearer API key;
- Bearer one-time token; or
- `?token=<one-time-token>`.

The SSE manager sends keepalive activity approximately every 15 seconds and broadcasts transaction telemetry from `PostTransactionEvent`.

### 9.4 WebSocket limitation

`/ws` exists but the current `WebSocketManager` is incomplete and must not be treated as a production WebSocket transport.

Current behavior is materially different from SSE:

- ordinary non-upgrade requests receive `503 WEBSOCKET_UNAVAILABLE`;
- upgrade-shaped requests only check for a non-empty query token;
- the current handler does **not** validate that token through `WsTokenHandler.validateToken`;
- it performs 101-shaped response/bookkeeping/logging, but the broadcast methods do not implement full working Undertow message transport.

This is a known implementation limitation and a security-sensitive area for future work.

## 10. Web Admin frontend

`syncmoney-web` uses Vue 3, Vite, Pinia, `vue-i18n`, and PWA tooling. The built production bundle is copied/embedded into `src/main/resources/syncmoney-web/dist`.

Frontend release metadata and the embedded bundle should move with the root release when frontend assets are part of that release. The current frontend package version is `1.3.2`.

## 11. PlaceholderAPI expansion

The expansion is a separate JAR with identifier `syncmoney`. It is persistent and discovers the core plugin lazily through reflection, which is an intentional compatibility boundary.

Supported placeholder families include:

- `balance`
- `balance_formatted`
- `balance_abbreviated`
- `rank`
- `my_rank`
- `total_supply`
- `total_players`
- `version`
- `online_players`
- `top_<n>`
- `balance_<player>`
- `balance_formatted_<player>`
- `balance_abbreviated_<player>`

The expansion keeps expensive/global work off the hot path with asynchronous refresh and bounded caching. The current cache uses a five-second expiry and a roughly 10,000-entry/pending-work bound.

## 12. Build and acceptance architecture

Primary artifacts are:

- `build/libs/Syncmoney-<version>.jar`
- `syncmoney-papi-expansion/build/libs/SyncmoneyExpansion-<version>.jar`
- `build/acceptance/SyncmoneyAcceptance.jar`

Standard build validation:

```powershell
.\gradlew.bat test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar
cd syncmoney-web
pnpm typecheck
pnpm test:unit --run
pnpm build
```

The project compiles with Java 21. Runtime Java follows the Minecraft server; current PlugDev guidance notes Java 25 for newer Paper 26.1+ acceptance environments.

For scheduler, storage, lifecycle, CMI, or cross-server changes, unit/build success is insufficient. Use the applicable PlugDev matrix and report exactly which real server, dependency, player, and network paths were exercised.

## 13. Change checklist

Architecture-affecting changes should answer:

1. Who owns the resource and closes it?
2. Which scheduler owns every Bukkit/CMI entity mutation?
3. Can this introduce blocking I/O into a synchronous/hot path?
4. Where is the accepted-write boundary and how is it drained?
5. How are monotonic versions and stale work handled?
6. Is the feature optional, and is disabled state cheap?
7. Does a configuration change require restart?
8. Do the English and Traditional Chinese docs, REST contract, frontend client, and tests still agree?
