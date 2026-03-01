# Syncmoney

<p align="center">
  <a href="https://github.com/Misty4119/Syncmoney">
    <img src="https://img.shields.io/github/stars/Misty4119/Syncmoney?color=yellow" alt="Stars">
    <img src="https://img.shields.io/github/downloads/Misty4119/Syncmoney" alt="Downloads">
  </a>
  <br>
  <img src="https://img.shields.io/badge/Platform-Paper%201.20%2B-orange" alt="Platform">
  <img src="https://img.shields.io/badge/Java-21%2B-blue" alt="Java">
  <img src="https://img.shields.io/github/v/release/Misty4119/Syncmoney" alt="Release">
  <img src="https://img.shields.io/github/license/Misty4119/Syncmoney" alt="License">
</p>

<p align="center">
  <b>Enterprise-grade cross-server economy synchronization plugin for Minecraft</b>
  <br>
  <a href="https://official.noie.fun">
    <img src="https://img.shields.io/discord/1453099158884978850?label=Discord&logo=discord" alt="Discord">
  </a>
</p>

---

## Why Syncmoney?

| Problem | Syncmoney Solution |
|---------|-------------------|
| Players lose money when switching servers | Cross-server sync via Redis Pub/Sub |
| Economy desync between servers | Atomic transactions with Lua scripts |
| CMI migration pain | Migration tool with multi-server merge |
| Economic exploits | 4-layer circuit breaker protection |
| Performance bottlenecks | Async write queues + memory caching |

**Perfect for**: Survival networks, factions servers, SMPs, and any multi-server economy ecosystem.

---

## Features

### Core
- **Real-time Sync** — Balance updates propagate across all servers via Redis Pub/Sub
- **Vault Integration** — Compatible with any Vault-enabled economy plugin
- **Atomic Transactions** — Lua scripts prevent money duplication
- **Graceful Degradation** — Memory → Redis → Database fallback chain

### Security
- **Circuit Breaker** — Single transaction limits, rate limiting, anomaly detection, periodic inflation monitoring
- **Transfer Guard** — Prevents money loss during server teleportation
- **Audit Trail** — Full transaction history with search & export (stored in MySQL)

### Advanced
- **CMI Migration** — Import from CMI with multi-server merge support
- **Shadow Sync** — Automated backup to external databases
- **Baltop** — Global leaderboard via Redis sorted sets
- **PlaceholderAPI** — Dynamic placeholders for scoreboards & plugins
- **Folia Support** — Region-based scheduling compatibility

---

## Supported Platforms

| Platform | Support |
|----------|---------|
| Paper 1.20+ | Full |
| Spigot 1.20+ | Full |
| Folia 1.20+ | Full |

**Requirements**: Java 21+, Vault, Redis, MySQL/PostgreSQL/MariaDB

---

## Quick Start

### 1. Installation
```
plugins/Syncmoney.jar
```

### 2. PlaceholderAPI Expansion (Optional)
If you want to use placeholders in scoreboards or other plugins:

1. Download `SyncmoneyExpansion.jar` from [Releases](https://github.com/Misty4119/Syncmoney/releases)
2. Place it in `plugins/PlaceholderAPI/expansions/`
3. Restart the server
4. The expansion will register automatically if PlaceholderAPI and Syncmoney are installed

### 3. Configuration
```yaml
# config.yml
server-name: "survival-01"

redis:
  enabled: true
  host: "localhost"
  port: 6379

database:
  enabled: true
  type: "mysql"
  host: "localhost"
  port: 3306
  database: "syncmoney"
```

### 4. Multi-Server Setup
1. Install on all servers
2. Connect to same Redis instance
3. Set unique `server-name` per server
4. Restart — economy syncs automatically

---

## Commands

### Players
| Command | Description | Permission |
|---------|-------------|------------|
| `/money` | View balance | `syncmoney.money` |
| `/pay <player> <amount>` | Transfer money | `syncmoney.pay` |
| `/baltop` | Leaderboard | `syncmoney.money` |

### Admins
| Command | Description | Permission |
|---------|-------------|------------|
| `/syncmoney admin give <player> <amount>` | Give money | `syncmoney.admin.give` |
| `/syncmoney admin take <player> <amount>` | Take money | `syncmoney.admin.take` |
| `/syncmoney admin set <player> <amount>` | Set balance | `syncmoney.admin.set` |
| `/syncmoney migrate cmi` | Migrate from CMI | `syncmoney.admin` |
| `/syncmoney breaker status` | View protection status | `syncmoney.admin` |
| `/syncmoney reload` | Reload config | `syncmoney.admin` |

### Permission Hierarchy
```
syncmoney.admin.full
    │
    ├── syncmoney.admin.general (give/take ≤1M/day)
    │       │
    │       ├── syncmoney.admin.reward (give ≤100K/day)
    │       │       │
    │       │       └── syncmoney.admin.observe
```

---

## Economy Modes

| Mode | Use Case |
|------|----------|
| `auto` | Auto-detect based on Redis/DB availability (recommended) |
| `local` | Single server only, in-memory with SQLite backup |
| `local_redis` | Multi-server sync via Redis (no MySQL), data stored in Redis |
| `sync` | Full cross-server sync with Redis + MySQL persistence |
| `cmi` | Direct CMI database integration |

### Auto Detection Logic

When set to `auto`, the mode is automatically determined:

| Redis | Database | Result Mode |
|-------|----------|-------------|
| Enabled | Enabled | `sync` |
| Enabled | Disabled | `local_redis` |
| Disabled | Disabled | `local` |

---

## Configuration Reference

### Pay Settings
```yaml
pay:
  cooldown-seconds: 30
  min-amount: 1
  max-amount: 1000000
  confirm-threshold: 100000
```

### Circuit Breaker
```yaml
circuit-breaker:
  enabled: true
  max-single-transaction: 100000000
  max-transactions-per-second: 10
  rapid-inflation-threshold: 0.2
  sudden-change-threshold: 100
```

### CMI Migration
```yaml
migration:
  cmi:
    auto-detect: true
    multi-server:
      enabled: true
      sqlite-paths:
        - "plugins/CMI/cmi.sqlite.db"
      merge-strategy: "latest"  # latest | sum | max
```

---

## Placeholders

| Placeholder | Description |
|-------------|-------------|
| `%syncmoney_balance%` | Player's current balance |
| `%syncmoney_balance_formatted%` | Balance with smart formatting |
| `%syncmoney_balance_abbreviated%` | Balance with abbreviated format (e.g., 1.5K, 2.3億) |
| `%syncmoney_rank%` | Player's rank on the leaderboard |
| `%syncmoney_my_rank%` | Player's rank (alias) |
| `%syncmoney_total_supply%` | Total money in circulation |
| `%syncmoney_total_players%` | Number of players in leaderboard |
| `%syncmoney_online_players%` | Current online player count |
| `%syncmoney_version%` | Plugin version |
| `%syncmoney_top_<n>%` | Balance of player at rank n (e.g., `%syncmoney_top_1%`) |
| `%syncmoney_balance_<player>%` | Specific player's balance by name |

---

## Migration from CMI

```bash
# Preview migration data first
/syncmoney migrate cmi -preview

# Start migration
/syncmoney migrate cmi

# Force migration (skip validation)
/syncmoney migrate cmi -force

# Migrate without backup
/syncmoney migrate cmi -force -no-backup
```

**Features**:
- PostgreSQL & MySQL & SQLite support
- Multi-server merge (configured in config.yml)
- Auto-backup before migration
- Checkpoint resume support
- Progress tracking

---

## Performance

Syncmoney uses asynchronous processing and connection pooling to handle high-throughput scenarios:

- Redis connection pooling (`pool-size: 30` recommended)
- Async write queues to prevent main thread blocking
- In-memory caching for frequently accessed data
- Batch database writes for audit logs

---

## API (Developers)

```java
// Get economy via Vault
Economy economy = Bukkit.getServicesManager()
    .getRegistration(Economy.class).getProvider();

// Access internal event data
// EconomyEvent contains: playerUuid, delta, balanceAfter, type, source, timestamp
```

**Internal Event Data**:
- `EconomyEvent` — Transaction data (deposit, withdraw, transfer, set balance)
- Event sources include: `COMMAND_PAY`, `COMMAND_ADMIN`, `ADMIN_GIVE`, `ADMIN_TAKE`, `MIGRATION`, etc.

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| "Server name not configured" | Set `server-name` in config.yml |
| Redis connection failed | Verify host/port/password |
| Balances not syncing | Check same Redis/DB across servers |
| Performance issues | Increase `queue-capacity` + `pool-size` |

Enable `debug: true` in config for detailed logs.

---

## Support

- **Discord**: [Join](https://official.noie.fun)
- **Issues**: [GitHub](https://github.com/Misty4119/Syncmoney/issues)

---

## License

[Apache License 2.0](LICENSE) — Free to use, modify, and distribute.

---

<p align="center">
  <sub>Built for high-scale Minecraft networks</sub>
</p>
