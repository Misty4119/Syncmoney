# CONTEXT.md — Syncmoney Project Context

This document is a factual project snapshot for maintainers, contributors, and coding agents. It explains what Syncmoney is, where behavior lives, and which architectural constraints are easy to miss.

## Snapshot

- Project: Syncmoney
- Repository: `Misty4119/Syncmoney`
- Current build version: `1.3.2`
- Configuration schema: `12`
- Build toolchain: Java 21
- Plugin API descriptor: `api-version: 1.20`
- Main server targets: Paper, Folia, Canvas
- Optional integrations: VaultUnlocked, CMI, PlaceholderAPI
- License: Apache License 2.0

Runtime Java requirements depend on the Minecraft server. PlugDev documents Java 25 for newer Paper 26.1+ environments even though project compilation uses Java 21.

## Product boundary

Syncmoney owns economy balances, transfer/admin commands, synchronization, monitoring, auditing, and related protection. It is not a general permissions synchronizer and does not replicate arbitrary third-party commands.

The main design goal is a synchronous Vault-compatible API backed by memory, while durable/network work happens asynchronously.

## Modules

### Core plugin

`src/main/java/noietime/syncmoney` contains lifecycle, economy, persistence, synchronization, commands, configuration, audit, breaker/guard, web, migration, and optional integrations.

`src/main/resources` contains the plugin descriptor, default configuration/messages, and embedded web distribution.

### PlaceholderAPI expansion

`syncmoney-papi-expansion` builds `SyncmoneyExpansion-<version>.jar`. It identifies as `syncmoney`, persists across PAPI reloads, lazily discovers the core plugin, and uses reflection as a compatibility boundary.

### Web administration

`syncmoney-web` is a Vue 3/Vite/Pinia/vue-i18n frontend. Production assets are embedded under `src/main/resources/syncmoney-web/dist`. The backend is Undertow inside the core plugin.

### PlugDev

`tools/plugdev` is external acceptance tooling, not a production dependency. It is the repository source of truth for real-server smoke and cross-server acceptance procedures.

## Economy modes

| Config mode | Authority / persistence |
|---|---|
| `auto` | Environment-based selection/detection |
| `local` | Local SQLite-backed economy |
| `local_redis` | Redis-backed synchronized economy without shared SQL persistence |
| `sync` | Redis synchronization plus shared relational database persistence |
| `cmi` | CMI remains economy authority; Syncmoney synchronizes around it |

The internal enum uses `LOCAL`, `LOCAL_REDIS`, `SYNC`, and `CMI`. `LOCAL_REDIS` and `SYNC` share synchronized strategy machinery but differ in persistence configuration.

## Economy data flow

Vault callers need immediate return values, so normal balance reads come from `MemoryStateManager` through `EconomyFacade`.

Mutations follow the existing strategy/writer pipeline:

1. validate input and breaker/guard state;
2. fire `AsyncPreTransactionEvent` where applicable and honor cancellation;
3. update accepted memory state/version;
4. enqueue persistence/synchronization work;
5. persist/publish asynchronously;
6. emit `PostTransactionEvent` when result data is available;
7. remote nodes apply only newer versions and suppress echoes/duplicates.

`TransferOrchestrator` coordinates multi-account transfers. Updating two accounts independently outside its invariants risks money creation/loss.

## Distributed consistency

Balances and versions are paired. Remote handlers compare versions before applying state. Pub/Sub messages carry source/message identity for echo/duplicate suppression.

Primary channels:

- `syncmoney:balance:update`
- `syncmoney:cmi:balance:update`

Representative keys:

- `syncmoney:balance:{uuid}`
- `syncmoney:version:{uuid}`
- `syncmoney:online:players`
- `syncmoney:online:player:*`
- `syncmoney:baltop`
- `syncmoney:bank:*`
- audit recent/index/dedup keys

Delayed messages are expected in distributed systems; version checks must not be removed merely to make propagation appear faster.

## Persistence

The shared `players` table includes UUID, player name, `DECIMAL(20,2)` balance, monotonic `BIGINT` version, last server, and update timestamp.

Additional storage covers baltop, audit logs and audit schema evolution, schema-version tracking, local SQLite player balances/transactions, and Shadow Sync history.

Storage shutdown happens after accepted event/write consumers have drained.

## Threading model

The code targets Paper common scheduler APIs so ownership rules work on Paper, Folia, and Canvas.

- Storage/network/computation: async.
- Plugin/global operations: global region scheduler.
- Player/entity state: entity scheduler.
- Teleports: async teleport API.

An async callback cannot touch a player/world entity merely because it originated from an entity task.

## Lifecycle ownership

Optional modules are configured owners, not ambient singletons. Disabled features should not create heavy resources.

Key facts:

- `BreakerManager` owns the only `PlayerTransactionGuard`.
- `EconomyServiceManager` receives guard/economy dependencies rather than duplicating owners.
- Web server owns Undertow/SSE/WebSocket helpers.
- Audit owns queue/export/cleanup resources.
- Storage managers own their pools/connections.

Observed shutdown order is dependency-aware: web → commands → permissions/listeners/sync → event consumers/drain → economy → audit → breaker → baltop persistence → storage → event bus cleanup.

## Configuration defaults and reload

Current defaults include:

- `config-version: 12`
- blank `server-name`; operator must set it
- queue capacity `50000`
- Redis enabled at localhost:6379, database 0
- SQL enabled with database name `syncmoney`
- economy mode `auto`
- display currency `$` with two decimals
- pay cooldown 30 seconds
- pay minimum 1 and maximum 1,000,000
- degraded payments disabled
- high-value confirmation threshold 100,000
- Web Admin central mode disabled
- Web Admin API key placeholder `change-me-in-production`

Only `display`, `pay`, `permissions`, `admin-permissions`, and `debug` are current live-reload roots. Other changes can require restart.

## Commands

Player entry points:

- `/money [player]`
- `/pay <player> <amount>` plus confirmation flow
- `/baltop [page|me]`

Administration is rooted at `/syncmoney`. Registered subcommands include `migrate`, `audit`, conditionally `breaker`, `admin`, `web`, conditionally `shadow`, `monitor`, `debug`, `sync-balance`, `test`, conditionally `econstats`, `reload`, and `version`.

Permissions are declared in `src/main/resources/plugin.yml`; several subcommands also enforce specialized/tier checks.

## PlaceholderAPI

Known placeholders:

- `%syncmoney_balance%`
- `%syncmoney_balance_formatted%`
- `%syncmoney_balance_abbreviated%`
- `%syncmoney_rank%` / `%syncmoney_my_rank%`
- `%syncmoney_total_supply%`
- `%syncmoney_total_players%`
- `%syncmoney_version%`
- `%syncmoney_online_players%`
- `%syncmoney_top_<n>%`
- `%syncmoney_balance_<player>%`
- `%syncmoney_balance_formatted_<player>%`
- `%syncmoney_balance_abbreviated_<player>%`

The current expansion uses a five-second cache and a maximum cache size of 10,000 for expensive lookup/global data.

## Web API

The web service uses Undertow. Normal REST requests use `Authorization: Bearer <api-key>`. `/health` is the health probe.

Route groups include:

- `/api/system/*`
- `/api/economy/*`
- `/api/config*`
- `/api/audit/*`
- `/api/settings*`
- `/api/nodes*`
- `/api/auth/ws-token`
- extension routes under `/api/extensions/{extensionName}/...`

Cross-server aggregate routes are registered only in central mode.

SSE is served at `/api/sse`. The frontend obtains a session token from `/api/auth/ws-token` and opens SSE with that token. Older `/sse` documentation is stale.

`/ws` currently has incomplete transport support. `WebSocketManager` performs upgrade-shaped handling, subscription bookkeeping, and logging, but its broadcast methods do not send through a full Undertow WebSocket channel. Treat it as limited/non-production until implementation changes.

`SystemApiHandler` and `NodesApiHandler` both register node GET routes. The registry replaces duplicate keys with `Map.put` and `NodesApiHandler` registers later, so it is effective for duplicates.

## Security-sensitive areas

Sensitive data includes Web/node API keys, Redis/SQL credentials, migration/Shadow credentials, audit exports, and acceptance/RCON secrets. Never commit real values.

The default Web Admin API key is unsafe for exposure. Current code warns when it remains `change-me-in-production`, while the service disable guard checks `change-me`; operators must explicitly replace the default.

If a real secret is found in a tracked file/history, removing it from the latest tree is insufficient. Rotate it and assess history/exposure.

## Build and artifacts

```powershell
.\gradlew.bat test
.\gradlew.bat shadowJar
.\gradlew.bat :syncmoney-papi-expansion:test
.\gradlew.bat :syncmoney-papi-expansion:jar
.\gradlew.bat acceptanceJar
```

Current artifacts:

- `Syncmoney-1.3.2.jar`
- `SyncmoneyExpansion-1.3.2.jar`
- `SyncmoneyAcceptance.jar`

Web checks:

```powershell
cd syncmoney-web
pnpm typecheck
pnpm test:unit --run
pnpm build
```

When web source changes, reconcile the built frontend with the embedded distribution.

## Acceptance baseline

The current PlugDev documentation covers Paper 1.20.4, Paper 26.2, Folia 26.2, Canvas 26.2, and a two-backend Paper 26.2 network using shared Redis/PostgreSQL test infrastructure.

Licensed CMI is not bundled; CMI acceptance requires a locally supplied licensed plugin. Do not turn this observed matrix into guarantees for future server releases.

## Documentation contract

Root `AGENTS.md`, `CLAUDE.md`, `CONTEXT.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, and `CONTRIBUTING.md` are canonical English. Traditional Chinese companions live under `docs/` with `.zh_tw.md`. Architecture/API/developer docs also exist in both languages and must remain semantically aligned.
