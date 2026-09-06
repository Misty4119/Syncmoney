# Syncmoney

Minecraft economy synchronization with Vault/VaultUnlocked integration, Redis Pub/Sub, optional transaction protection, auditing, Shadow backups and a Web Admin panel.

Syncmoney manages balances and its own economy commands/permissions. It is **not** a general-purpose command or permission synchronization plugin.

## Compatibility

- Build/API baseline: Paper **1.20.4**, Java **21** bytecode. Plain Spigot is not supported.
- Folia and Canvas use the region-threaded scheduling path. Install compatible versions of every dependency; a Folia declaration alone does not guarantee third-party plugin safety.
- Minecraft 26.1+ Paper requires **Java 25**; older supported versions use Java 21. See [Paper's Java requirements](https://docs.papermc.io/paper/getting-started/).
- Release validation is in progress: Paper 1.20.4 build 499 has passed core economy smoke tests with VaultUnlocked 2.20.0. The 26.2 matrix will be updated after runtime acceptance; do not treat an untested version as verified.
- Future versions are compatibility targets, not unconditional guarantees. Test upgrades on a separate server before touching live balances.

Required: one plugin providing Vault (legacy Vault or VaultUnlocked). Optional: PlaceholderAPI plus SyncmoneyExpansion; a compatible licensed CMI build for CMI mode. Do not install Vault and VaultUnlocked simultaneously under the same plugin name.

## Install or upgrade

1. Stop the server and back up plugin configuration, economy databases and Redis persistence. Existing economy balances are not automatically imported from every other plugin.
2. Install `Syncmoney-1.3.0.jar` and a compatible Vault provider in `plugins/`; remove the previous Syncmoney JAR from the active plugins directory.
3. Start once to generate configuration, then stop and select the economy mode. Give each network node a unique `server-name`.
4. Configure storage and optional features, restart, and check the log for successful Syncmoney/Vault registration. Test `/money` and a small `/pay` before allowing normal traffic.
5. For placeholders, install `SyncmoneyExpansion-1.3.0.jar` in `plugins/PlaceholderAPI/expansions/`, alongside PlaceholderAPI itself, and restart.

Existing YAML values are preserved when missing defaults are merged. Release version and config/message schema version are different; do not manually change schema numbers. Review renamed/deprecated options and [the changelog](CHANGELOG.md) when upgrading.

## Choose an economy mode

| `economy.mode` | Storage and purpose |
|---|---|
| `local` | Single-server SQLite; no Redis/MySQL required. |
| `local_redis` | Redis-backed network economy without SQL persistence; configure Redis persistence/backups. |
| `sync` | Redis plus database persistence for a shared network economy. |
| `cmi` | CMI remains local economy authority; CMI API changes are synchronized through Redis. Requires compatible CMI. |
| `auto` | Detects the available mode; production operators should explicitly select the intended mode. |

Minimal standalone configuration (merge into the generated file):

```yaml
server-name: "survival-01"
economy:
  mode: "local"
redis:
  enabled: false
database:
  enabled: false
db-enabled: false
pubsub-enabled: false
```

For a shared network, select `sync`, enable Redis/database and `db-enabled`/`pubsub-enabled`, and configure all nodes with the same isolated Redis database and SQL database. Use different `server-name` values. SQL implementations include MySQL/MariaDB, PostgreSQL and SQLite; a local SQLite file is not shared network storage.

Keep Redis and SQL on a private network, use dedicated credentials and backup retention, and restrict access with a firewall. Never point test servers at production storage. Disconnections and backpressure can reject operations; do not rely on fallback as a substitute for backups.

## Optional features and config switches

All paths below are in `plugins/Syncmoney/config.yml`. Existing defaults are retained; toggle services while stopped, then restart.

| Feature | Switch | Disabled behavior |
|---|---|---|
| Global economic breaker | `circuit-breaker.enabled` | No breaker monitoring/cleanup/inflation tasks. Status command reports disabled. |
| Per-player protection | `circuit-breaker.player-protection.enabled` | No player Guard or its executor; independent of the global breaker switch. |
| Shadow backup | `shadow-sync.enabled` | No Shadow task/storage; command reports disabled. |
| Audit history | `audit.enabled` | No audit schema/writer/Redis pipeline/cleanup/export; API returns `FEATURE_DISABLED`. Core economy persistence remains enabled. |
| Audit cleanup/export/Redis | `audit.cleanup.enabled`, `audit.export.enabled`, `audit.redis.enabled` | Each requires the parent audit switch; disabled exporter does not create an export folder. |
| Teleport protection | `transfer-guard.enabled` | No transfer-guard listener/task. This is not a proxy transfer protocol. |
| Discord alerts | `discord-webhook.enabled` | No webhook sending executor or outbound alerts. |
| Web Admin | `web-admin.enabled` | No HTTP listener. |
| Cross-server notifications | `cross-server-notifications.enabled` | Controls notification presentation, not authoritative balance synchronization. |

The global breaker limits transaction amounts/rates and detects abnormal growth. Player protection separately provides rate limiting, warnings and locks. Tune thresholds for your economy; disabling safeguards is not a performance recommendation. Investigate the cause before using administrative reset/unlock commands.

Shadow Sync is an optional background copy with safety/rollback checks, not a replacement for database backups. Review target/storage settings before enabling it. CMI migration is a separate, administrative workflow: back up and preview data before executing `/syncmoney migrate`; do not force a migration on a live network without a maintenance window.

### Reload versus restart

`/syncmoney reload` reloads messages and permitted command/display settings (`display`, `pay`, `permissions`, `admin-permissions`, `debug`). Redis/SQL connections, economy mode, feature switches, scheduler owners, audit/shadow pipelines and Web settings require restart. If any restart-only key changed, reload reports the affected keys and keeps the running configuration unchanged. Saving via Web Admin does not rebuild these services.

## Common commands and permissions

| Command | Permission |
|---|---|
| `/money`, `/money <player>` | `syncmoney.money`, `syncmoney.money.others` |
| `/pay <player> <amount>`, `/pay confirm` | `syncmoney.pay` |
| `/baltop [page]` | `syncmoney.money` |
| `/syncmoney admin give/take/set <player> <amount>` | Corresponding `syncmoney.admin.give/take/set` and configured admin tier |
| `/syncmoney breaker status`, `/syncmoney breaker unlock <player>` | `syncmoney.admin` |
| `/syncmoney audit <player>` | `syncmoney.admin.audit` |
| `/syncmoney shadow status`, `/syncmoney web status` | `syncmoney.admin` |
| `/syncmoney reload` | Configured administrative permission |

See in-game help for subcommands. The `observe`, `reward`, `general` and `full` admin tiers have configurable limits under `admin-permissions`. Grant only required permissions. Stress-test commands belong on disposable servers.

## Vault, CMI and placeholders

On VaultUnlocked, Syncmoney registers Vault2 and the legacy Vault economy interface; on legacy Vault it uses the legacy interface. In CMI mode, CMI remains the provider. Other economy plugins may compete for provider registration: verify the selected provider instead of assuming automatic import or universal compatibility.

Useful placeholders: `%syncmoney_balance%`, `%syncmoney_balance_formatted%`, `%syncmoney_balance_abbreviated%`, `%syncmoney_rank%`, `%syncmoney_total_supply%`, `%syncmoney_total_players%`, `%syncmoney_online_players%`, `%syncmoney_version%`, `%syncmoney_top_1%`, `%syncmoney_balance_<player>%`.

Balance/name cache misses are warmed asynchronously and may initially show `N/A`. Use the matching expansion release. PlaceholderAPI and CMI must independently support your server platform.

## Web Admin security

Web Admin is optional and disabled by default. Bind it to loopback behind an HTTPS reverse proxy, configure a long random API key, and restrict CORS origins and firewall access. Keep API keys, node credentials and webhook URLs private. Do not expose an unauthenticated development server.

```yaml
web-admin:
  enabled: true
  server:
    host: "127.0.0.1"
    port: 8080
  security:
    api-key: "REPLACE_WITH_A_LONG_RANDOM_SECRET"
```

Open the configured address and authenticate with the key. `/health` is unauthenticated; protected API routes require `Authorization: Bearer <key>`. Proxy SSE without buffering for live updates. See [API reference](docs/API_REFERENCE.md) for endpoints.

## Troubleshooting and support

For missing synchronization, check mode, unique server names, shared Redis database, Pub/Sub switches and connection errors. For locked transactions, inspect protection/audit status before resetting anything. For failed reload, restart after reviewing the listed changed keys. On Paper 1.20.4 the acceptance profile uses VaultUnlocked 2.20.0; 2.20.1's descriptor was rejected by that server.

Report issues at [GitHub Issues](https://github.com/Misty4119/Syncmoney/issues) with Syncmoney version, exact server build, Java version, dependency versions, reproduction steps and redacted logs/config. Remove passwords, API keys, player-private data and webhook URLs. [Discord](https://official.noie.fun) is also available.

## Build and test

```powershell
.\gradlew.bat test shadowJar :syncmoney-papi-expansion:jar
```

Release artifacts are under `build/libs` and `syncmoney-papi-expansion/build/libs`. Frontend changes require `pnpm typecheck`, `pnpm test:unit --run`, `pnpm build` and refreshing the embedded bundle before packaging. See [PlugDev testing](tools/plugdev/README.md) for reproducible isolated runtime checks. Download releases from [GitHub Releases](https://github.com/Misty4119/Syncmoney/releases).

[Apache License 2.0](LICENSE).
