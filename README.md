# Syncmoney

<p align="center">
  <a href="https://github.com/Misty4119/Syncmoney">
    <img src="https://img.shields.io/github/stars/Misty4119/Syncmoney" alt="Stars">
    <img src="https://img.shields.io/github/downloads/Misty4119/Syncmoney/total" alt="Downloads">
  </a>
  <br>
  <img src="https://img.shields.io/badge/Platform-Paper%201.20%2B-orange" alt="Platform">
  <img src="https://img.shields.io/badge/Java-21%2B-blue" alt="Java">
  <img src="https://img.shields.io/github/v/release/Misty4119/Syncmoney" alt="Release">
  <img src="https://img.shields.io/github/license/Misty4119/Syncmoney" alt="License">
</p>

<p align="center">
  <b>Enterprise-grade cross-server economy synchronization for Minecraft</b>
  <br>
  <a href="https://official.noie.fun">
    <img src="https://img.shields.io/discord/1453099158884978850?label=Discord&logo=discord" alt="Discord">
  </a>
</p>

---

## Overview

Syncmoney is a high-performance Minecraft economy plugin designed for multi-server networks. It synchronizes player balances across all servers in real-time using Redis Pub/Sub, with comprehensive protection against economic exploits and data loss.

**Perfect for**: Survival servers, factions, SMPs, minigame networks, and any multi-server economy ecosystem.

---

## Features

### Core Synchronization
- **Real-time Cross-Server Sync** — Balance changes instantly propagate to all servers via Redis Pub/Sub
- **Vault Integration** — Full compatibility with any Vault-enabled economy plugin
- **Atomic Transactions** — Lua scripts prevent money duplication during race conditions
- **Graceful Degradation** — Automatic fallback: Memory → Redis → Database

### Security Protection
- **4-Layer Circuit Breaker**
  - Single transaction limits
  - Rate limiting (transactions per second)
  - Sudden balance change detection
  - Periodic inflation monitoring
- **Transfer Guard** — Prevents money loss when players teleport during transactions
- **Rollback Protection** — Guards against failed database writes

### Migration & Backup
- **CMI Migration Tool** — One-command import from CMI economy
- **Multi-Server Merge** — Combines economy data from multiple CMI servers
- **Shadow Sync** — Automatic backup to external databases
- **Checkpoint Resume** — Large migrations can resume if interrupted

### Leaderboards & Integration
- **Global Baltop** — Redis-powered sorted set leaderboard
- **PlaceholderAPI Expansion** — Dynamic placeholders for scoreboards
- **Folia Support** — Region-based scheduling compatibility
- **Audit Trail** — Full transaction history with search and export

---

## Requirements

| Component | Version |
|-----------|---------|
| Server | Paper 1.20+ / Spigot 1.20+ / Folia 1.20+ |
| Java | 21+ |
| Redis | 5.0+ |
| Database | MySQL 8.0+ / MariaDB 10.5+ / PostgreSQL 13+ |
| Plugin | Vault |

---

## Quick Start

### 1. Install

Place `Syncmoney.jar` in your server's `plugins/` folder.

### 2. Configure Redis

Edit `plugins/Syncmoney/config.yml`:

```yaml
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
  username: "root"
  password: ""
  database: "syncmoney"
```

### 3. Multi-Server Setup

1. Install Syncmoney on **all** servers
2. Connect all servers to the **same Redis instance**
3. Set a unique `server-name` for each server
4. Restart all servers — economy syncs automatically

### 4. PlaceholderAPI (Optional)

For scoreboard placeholders:

1. Download `SyncmoneyExpansion.jar` from [Releases](https://github.com/Misty4119/Syncmoney/releases)
2. Place in `plugins/PlaceholderAPI/expansions/`
3. Restart the server

---

## Configuration

### Economy Modes

| Mode | Description |
|------|-------------|
| `auto` | Auto-detect based on Redis/DB availability (recommended) |
| `local` | Single server only, SQLite backup |
| `local_redis` | Multi-server sync via Redis (no MySQL) |
| `sync` | Full cross-server sync with Redis + MySQL |
| `cmi` | Direct CMI database integration |

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

---

## Commands

### Players

| Command | Description | Permission |
|---------|-------------|------------|
| `/money` | View your balance | `syncmoney.money` |
| `/pay <player> <amount>` | Transfer money to player | `syncmoney.pay` |
| `/baltop` | View global leaderboard | `syncmoney.money` |

### Administrators

| Command | Description | Permission |
|---------|-------------|------------|
| `/syncmoney admin give <player> <amount>` | Give money to player | `syncmoney.admin.give` |
| `/syncmoney admin take <player> <amount>` | Take money from player | `syncmoney.admin.take` |
| `/syncmoney admin set <player> <amount>` | Set player balance | `syncmoney.admin.set` |
| `/syncmoney migrate cmi` | Migrate from CMI | `syncmoney.admin` |
| `/syncmoney breaker status` | View protection status | `syncmoney.admin` |
| `/syncmoney audit` | View transaction history | `syncmoney.admin` |
| `/syncmoney reload` | Reload configuration | `syncmoney.admin` |

---

## Placeholders

| Placeholder | Description |
|-------------|-------------|
| `%syncmoney_balance%` | Player's current balance |
| `%syncmoney_balance_formatted%` | Balance with smart formatting |
| `%syncmoney_balance_abbreviated%` | Balance abbreviated (1.5K, 2.3億) |
| `%syncmoney_rank%` | Player's leaderboard rank |
| `%syncmoney_total_supply%` | Total money in circulation |
| `%syncmoney_top_<n>%` | Balance of player at rank n |
| `%syncmoney_balance_<player>%` | Specific player's balance by name |

---

## Migration from CMI

```bash
# Preview migration data
/syncmoney migrate cmi -preview

# Start migration
/syncmoney migrate cmi

# Force migration (skip validation)
/syncmoney migrate cmi -force

# Force migration without backup
/syncmoney migrate cmi -force -no-backup
```

**Features**:
- PostgreSQL, MySQL, and SQLite support
- Multi-server data merge
- Automatic backup before migration
- Checkpoint resume for large datasets

---

## Performance

Syncmoney is optimized for high-throughput scenarios:

- **Redis connection pooling** (default: 30 connections)
- **Async write queues** — prevents main thread blocking
- **In-memory caching** — O(1) balance reads
- **Batch database writes** — efficient audit log persistence

---

## API for Developers

```java
// Get economy via Vault
Economy economy = Bukkit.getServicesManager()
    .getRegistration(Economy.class).getProvider();

// Economy events contain:
// playerUuid, delta, balanceAfter, type, source, timestamp
```

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| "Server name not configured" | Set `server-name` in config.yml |
| Redis connection failed | Verify host/port/password in config |
| Balances not syncing | Ensure all servers use same Redis instance |
| Performance issues | Increase `pool-size` and `queue-capacity` |

Enable `debug: true` in config.yml for detailed logs.

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
