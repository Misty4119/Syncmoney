# Syncmoney

繁體中文：[README.zh_tw.md](README.zh_tw.md)

<p align="center">
  <a href="https://github.com/Misty4119/Syncmoney"><img src="https://img.shields.io/github/stars/Misty4119/Syncmoney" alt="GitHub stars"></a>
  <a href="https://github.com/Misty4119/Syncmoney"><img src="https://img.shields.io/github/downloads/Misty4119/Syncmoney/total" alt="Total downloads"></a>
  <a href="https://github.com/Misty4119/Syncmoney/releases/latest"><img src="https://img.shields.io/github/v/release/Misty4119/Syncmoney" alt="Latest release"></a>
  <a href="https://github.com/Misty4119/Syncmoney/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-green.svg" alt="Apache 2.0 license"></a>
  <a href="https://github.com/Misty4119/Syncmoney/actions/workflows/ci.yml"><img src="https://github.com/Misty4119/Syncmoney/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/Misty4119/Syncmoney/actions/workflows/codeql.yml"><img src="https://github.com/Misty4119/Syncmoney/actions/workflows/codeql.yml/badge.svg" alt="CodeQL"></a>
</p>
<p align="center">
  <a href="https://docs.papermc.io/paper/getting-started/"><img src="https://img.shields.io/badge/Targets-Paper%20%7C%20Folia%20%7C%20Canvas-orange.svg" alt="Paper, Folia, and Canvas targets"></a>
  <img src="https://img.shields.io/badge/Java-21%20%2F%2025-blueviolet.svg" alt="Java 21 and 25">
  <a href="https://official.noie.fun"><img src="https://img.shields.io/badge/Discord-Community-7289DA.svg?logo=discord&logoColor=white" alt="Discord community"></a>
  <a href="https://paypal.me/NoieSrv"><img src="https://img.shields.io/badge/Donate-PayPal-blue" alt="Donate with PayPal"></a>
</p>

<p align="center"><strong>A Vault-compatible Minecraft economy with optional Redis-backed synchronization, administration, audit tools, and PlaceholderAPI support.</strong></p>

## Overview

Syncmoney manages player balances, currency operations, and its own economy commands. It supports single-server SQLite economies, Redis-backed cross-server synchronization, relational persistence, and an optional CMI-authoritative currency sync mode. It does not replicate arbitrary permissions or third-party commands.

The published stable line is 1.3.2. Version `1.4.0-Beta.1` is the prerelease build for the 1.4.0 line and requires Java 25; it is not a stable 1.4.0 release. Check the [release page](https://github.com/Misty4119/Syncmoney/releases) for availability, downloads, and version-specific notes.

## Key Features

- Vault 1.7 economy compatibility and optional VaultUnlocked integration.
- `local` SQLite, `local_redis`, and `sync` economy modes.
- Redis Pub/Sub cross-server balance synchronization with version checks and echo suppression.
- Optional MySQL, MariaDB, or PostgreSQL persistence for shared `sync` mode.
- CMI-authoritative currency synchronization over Redis. CMI remains the economy authority in this mode.
- Player balance, payment, leaderboard, and administration commands.
- Transaction audit history, account safeguards, circuit-breaker tools, and Shadow Sync snapshots.
- Optional embedded Web Admin with authenticated HTTP endpoints and server-sent events (SSE).
- Separate PlaceholderAPI expansion and transaction events for internal telemetry.
- Recovery records for accepted queued payments with ambiguous Redis outcomes; unresolved operations remain quarantined for administrator review.

## Platform & Compatibility

| Component | Requirement or status |
|---|---|
| **Server engine** | Paper-compatible servers. Minecraft 26.2–26.3 on Paper, Folia, and Canvas are the 1.4.0 target matrix. Plain Spigot is not supported. |
| **Java runtime** | Java 21 or newer for the published 1.3.2 stable line; Java 25 or newer for 1.4.0 prereleases. |
| **Economy bridge** | Vault 1.7 or compatible VaultUnlocked for interoperability. Vault is optional. CMI mode does not register a Syncmoney economy provider. |
| **Message broker** | Redis is required for `local_redis`, `sync`, and `cmi` modes. |
| **Database** | Local SQLite for `local`; MySQL, MariaDB, or PostgreSQL for shared SQL persistence in `sync`. |
| **Optional integrations** | PlaceholderAPI uses the separate `SyncmoneyExpansion` JAR. CMI mode requires CMI, CMILib, and Redis; obtain CMI and CMILib from their publishers and comply with their licenses. |

The 1.4.0 target range is not a blanket guarantee for every mode or integration. The [1.4.0-Beta.1 release notes](https://github.com/Misty4119/Syncmoney/releases/tag/1.4.0-Beta.1) identify the exact server builds and scenarios exercised for that release. Validate upgrades on a staging server before changing production economy data.

The configuration schema has its own version and is separate from the plugin version. Back up configuration and economy data before upgrading.

## Installation & Setup

1. **Back up your data.** Stop the server and back up the Syncmoney configuration, SQLite or SQL database, and Redis data used by the economy.
2. **Install the JAR.** For the 1.4.0-Beta.1 preview, install `Syncmoney-1.4.0-Beta.1.jar` from its release page in the server's `plugins/` directory. Remove older Syncmoney JARs so only one version loads.
3. **Install mode dependencies.** `local` uses SQLite. `local_redis` needs Redis. `sync` needs Redis and a relational database. `cmi` needs CMI, CMILib, and Redis. Vault or VaultUnlocked is optional interoperability for Syncmoney-managed economy modes.
4. **Generate and edit the configuration.** Start the server once, then stop it. Set a unique, non-empty `server-name` in `plugins/Syncmoney/config.yml` for each server.
5. **Choose the economy mode.** Configure the Redis and database connections required by that mode. Keep CMI as the sole economy authority when using `cmi` mode.
6. **Set up PlaceholderAPI (optional).** For the 1.4.0-Beta.1 preview, install `SyncmoneyExpansion-1.4.0-Beta.1.jar` in `plugins/PlaceholderAPI/expansions/`, then run `/papi reload`. The expansion version must match the core JAR.
7. **Set up Web Admin (optional).** Replace the shipped API-key placeholder before enabling Web Admin. CMI sync-only mode does not start Web Admin.
8. **Verify before opening transactions.** Confirm the configured economy authority and required services load, then test the selected mode on staging.

## Economy Modes

| Mode (`economy.mode`) | Storage and architecture | Intended environment |
|---|---|---|
| `local` | Per-server SQLite storage. Redis and shared SQL are not required. | A standalone server. |
| `local_redis` | Redis-backed balances without shared SQL persistence. | A network that uses Redis for shared balances. |
| `sync` | Redis Pub/Sub plus shared SQL persistence. | A multi-server network requiring relational storage. |
| `cmi` | CMI remains the currency authority; Redis carries CMI balance updates between servers. | A network that already uses CMI as its economy. Syncmoney-managed economy commands and services are disabled. |
| `auto` | Detects the installed environment and recommends a mode. | Evaluation. Set a mode explicitly for production. |

Before switching an existing Redis economy to `local_redis` or `sync`, check balances against the shared-mode maximum of `999999999999999999.99`. Version 1.4.0 does not automatically scan, clamp, or migrate existing Redis balances.

### Minimal Standalone Configuration (`local`)

Use a distinct server name for each installation. The following example disables Redis and shared SQL while retaining local SQLite storage:

```yaml
server-name: "single-01"
pubsub-enabled: false
db-enabled: false
economy:
  mode: "local"
redis:
  enabled: false
database:
  enabled: false
```

### Shared Network Configuration (`sync`)

Enable Redis Pub/Sub and the shared database. Point every participating server to the same Redis database and SQL schema, give each server a unique `server-name`, and supply connection credentials in each local configuration file.

```yaml
server-name: "survival-01"
pubsub-enabled: true
db-enabled: true
economy:
  mode: "sync"
redis:
  enabled: true
  host: "redis.example.net"
  port: 6379
database:
  enabled: true
  type: "mysql"
  host: "sql.example.net"
  port: 3306
  database: "syncmoney"
```

Set credentials locally; do not publish them. For more server-specific settings, edit the generated configuration and consult the [developer guide](docs/DEVELOPER_GUIDE.md).

## Optional Modules & Lifecycle

Optional modules are configured in `plugins/Syncmoney/config.yml`.

| Module | Configuration switch | Description |
|---|---|---|
| **Global circuit breaker** | `circuit-breaker.enabled` | Stops managed economy operations when configured anomaly limits are reached. |
| **Per-player protection** | `circuit-breaker.player-protection.enabled` | Adds player-level rate limits and account protection; it is separate from the global breaker switch. |
| **Transfer guard** | `transfer-guard.enabled` | Protects players from balance inconsistencies around cross-server transfers and teleports. |
| **Audit logging** | `audit.enabled` | Records transaction history. Cleanup, export, and Redis audit features depend on the parent audit switch. |
| **Shadow Sync** | `shadow-sync.enabled` | Mirrors data to a secondary storage target for snapshots; it does not replace database backups. |
| **Discord webhooks** | `discord-webhook.enabled` | Sends configured notifications for supported events. |
| **Web Admin** | `web-admin.enabled` | Starts the embedded HTTP dashboard when its security configuration is valid. |

`/syncmoney reload` applies live changes only to `display`, `pay`, `permissions`, `admin-permissions`, and `debug`. Changes to connections, economy authority, scheduler ownership, or module construction require a full server restart.

CMI mode is sync-only. It keeps CMI as the economy authority and starts only its CMI balance synchronization path and `/syncmoney version`; it does not register Syncmoney's Vault provider or economy query API, and it disables Syncmoney wallet, transfer, leaderboard, and management modules. Syncmoney balance, rank, and leaderboard placeholders are unavailable in CMI mode. `/pay` and other Syncmoney paired-transfer operations are not supported in this mode.

## Commands & Permissions

### Player Commands

| Command | Description | Permission |
|---|---|---|
| `/money` | View your balance. | `syncmoney.money` (default: true) |
| `/money <player>` | View another player's balance. | `syncmoney.money.others` (default: op) |
| `/pay <player> <amount>` | Send money to another player. | `syncmoney.pay` (default: true) |
| `/baltop [page]` | View the balance leaderboard. | `syncmoney.money` (default: true) |
| `/baltop me` | View your leaderboard position. | `syncmoney.money` (default: true) |

These Syncmoney-managed economy commands are unavailable in CMI mode, where CMI remains the economy authority.

### Administrative Commands

| Command | Description | Permission |
|---|---|---|
| `/syncmoney admin give <player> <amount>` | Add balance to a player. | `syncmoney.admin.give` or configured admin tier |
| `/syncmoney admin take <player> <amount>` | Deduct balance from a player. | `syncmoney.admin.take` or configured admin tier |
| `/syncmoney admin set <player> <amount>` | Set a player's balance. | `syncmoney.admin.set` or configured admin tier |
| `/syncmoney admin reset <player>` | Reset a player's balance. | `syncmoney.admin.set` or configured admin tier |
| `/syncmoney audit <player> [page]` | View audit history. | `syncmoney.admin.audit` |
| `/syncmoney monitor [redis\|cache\|db]` | View system and resource status. | `syncmoney.admin.monitor` |
| `/syncmoney migrate <cmi\|local-to-sync>` | Run an explicit economy migration. | `syncmoney.admin` |
| `/syncmoney recovery list` | List queued transfers awaiting recovery review. | `syncmoney.admin.recovery` or `syncmoney.admin` |
| `/syncmoney recovery inspect <id>` | Inspect one quarantined transfer. | `syncmoney.admin.recovery` or `syncmoney.admin` |
| `/syncmoney recovery reconcile <id>` | Reconcile using the exact Redis operation receipt. | `syncmoney.admin.recovery` or `syncmoney.admin` |
| `/syncmoney version [full\|save]` | Show version and support diagnostics. | Basic: public; `full`/`save`: `syncmoney.admin` |

Other administrative subcommands include `breaker`, `debug`, `econstats`, `reload`, `shadow`, `sync-balance`, `test`, and `web` where enabled. Command availability depends on the selected mode and module configuration. Check `src/main/resources/plugin.yml` and the command's specific permission for the effective access rule.

### Administrative Permission Tiers

Configure tiers under `admin-permissions` in `config.yml`.

| Tier | Permission node | Default daily limits |
|---|---|---|
| **Observe** | `syncmoney.admin.observe` | View only; 0 give / 0 take |
| **Reward** | `syncmoney.admin.reward` | 100,000 give / 0 take |
| **General** | `syncmoney.admin.general` | 1,000,000 give / 1,000,000 take |
| **Full** | `syncmoney.admin.full` | Unlimited economy operations |

The daily-limit switch and tier limits are configurable. The root `syncmoney.admin` and command-specific permission nodes are also declared in `src/main/resources/plugin.yml`.

## PlaceholderAPI Identifiers

Install `SyncmoneyExpansion-1.4.0-Beta.1.jar` alongside the 1.4.0-Beta.1 core JAR to use these PlaceholderAPI identifiers:

| Placeholder | Description |
|---|---|
| `%syncmoney_balance%` | Your numeric balance. |
| `%syncmoney_balance_formatted%` | Your balance with currency formatting. |
| `%syncmoney_balance_abbreviated%` | Your abbreviated balance, such as `1.5K`. |
| `%syncmoney_balance_<player>%` | A named player's balance. |
| `%syncmoney_balance_formatted_<player>%` | A named player's formatted balance. |
| `%syncmoney_balance_abbreviated_<player>%` | A named player's abbreviated balance. |
| `%syncmoney_rank%` / `%syncmoney_my_rank%` | Your leaderboard rank. |
| `%syncmoney_top_<n>%` | The balance at leaderboard rank `n`. |
| `%syncmoney_total_supply%` | Total currency supply. |
| `%syncmoney_total_players%` | Number of accounts tracked by the leaderboard. |
| `%syncmoney_online_players%` | Online player count. |
| `%syncmoney_version%` | Active Syncmoney version. |

Global and rank lookups use cached asynchronous work. A cache miss does not block the server thread. Syncmoney balance, rank, and leaderboard placeholders are unavailable in CMI mode.

## Web Admin Setup & Security

Web Admin is optional and is disabled by default. When enabled, it serves the embedded dashboard and authenticated REST endpoints.

```yaml
web-admin:
  enabled: true
  server:
    host: "127.0.0.1"
    port: 8080
  security:
    api-key: "change-me-in-production" # Replace before enabling Web Admin.
    cors-allowed-origins:
      - "https://admin.example.com"
```

A blank key or the shipped placeholder disables Web Admin while allowing the economy plugin to start. CMI sync-only mode does not start this module.

### Security Recommendations

1. **Bind locally.** Keep Web Admin on loopback and use a reverse proxy with HTTPS for remote browser access.
2. **Use a private key.** Replace the shipped API-key placeholder with a long random value and do not publish it.
3. **Limit browser origins.** Set only the required CORS origins; do not use `*` in production.
4. **Restrict network access.** Firewall the HTTP port and protect any reverse-proxy route.
5. **Use the current live endpoint.** The dashboard uses HTTP and `/api/sse`; `/ws` always responds with `503 WEBSOCKET_UNAVAILABLE` and is not a WebSocket service.

## Developer Integration

### Vault Provider Access

In Syncmoney-managed economy modes, plugins can discover the Vault 1.7 provider through Bukkit's services manager:

```java
RegisteredServiceProvider<Economy> registration =
    getServer().getServicesManager().getRegistration(Economy.class);

if (registration != null) {
    Economy economy = registration.getProvider();
    double balance = economy.getBalance(player);
}
```

When VaultUnlocked is present, Syncmoney also registers its Vault2 provider. CMI mode is sync-only and does not register a Syncmoney Vault provider or economy query API.

### Transaction Events

`AsyncPreTransactionEvent` is sent before accepted deposit and withdrawal paths and can cancel the operation. `PostTransactionEvent` reports the result after it is known. These event classes are dispatched through Syncmoney's internal event bus; they are not delivered through Bukkit's `PluginManager` event registration. See the [developer guide](docs/DEVELOPER_GUIDE.md) before integrating with internal event behavior.

## Building from Source

The 1.4.0 target uses Java 25 and Gradle 9.8.0. Java 21 and Gradle 9.1.0 describe the preserved 1.3.2 baseline.

```bash
# Core plugin, PAPI expansion, API consumer, and acceptance artifacts
./gradlew test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar

# Windows PowerShell
.\gradlew.bat test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar

# Web frontend checks
cd syncmoney-web
pnpm install --frozen-lockfile
pnpm typecheck
pnpm test:unit --run
pnpm build
```

The core JAR is written under `build/libs/`; the expansion JAR is written under `syncmoney-papi-expansion/build/libs/`. After changing Web Admin source, run `pnpm build:embedded` from `syncmoney-web` and review the generated bundle with `WebAdminServer.extractIndividualFiles`.

## Release and CI workflow

Pull requests and `main` updates run the repository's Java and Web checks. CI artifacts are for testing and are not public GitHub Releases.

Core release tags are unprefixed, such as `1.4.0-Beta.1`. The release workflow treats alpha and beta tags as prereleases. The Web Admin source is maintained in this repository and mirrored to [Syncmoney-web](https://github.com/Misty4119/Syncmoney-web); the mirror uses the corresponding `v`-prefixed tag and a deterministic source archive. Check the GitHub release page for official JARs and version-specific notes.

For a frontend release, run `pnpm build:embedded` from `syncmoney-web` so the embedded distribution and Java extraction list are updated together.

## Troubleshooting & Support

- **Balances do not synchronize:** Confirm every node uses the same Redis instance and database, Pub/Sub is enabled, and each server has a distinct `server-name`.
- **The plugin does not start:** Check that `server-name` is not blank, required mode dependencies are installed, and CMI is enabled when using `cmi` mode.
- **Shared-mode transactions are rejected:** Check for stored Redis balances above the shared-mode maximum before switching an existing network to `local_redis` or `sync`.
- **Configuration changes do not take effect:** Connection, mode, scheduler, and module-owner changes require a full server restart.
- **Web Admin does not start:** Replace the shipped API-key placeholder and enable Web Admin only in a mode that supports it.
- **Report an issue:** Include `/syncmoney version`, the server software build, Java version, and sanitized logs/configuration. Redact passwords, API keys, and webhook URLs. Administrators can use `/syncmoney version full` for read-only backend probes or `/syncmoney version save` to write a sanitized report.
- **GitHub Issues:** [Issue tracker](https://github.com/Misty4119/Syncmoney/issues)
- **Community Discord:** [Join the community](https://official.noie.fun)

## License

This project is licensed under the [Apache License 2.0](LICENSE).
