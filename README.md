# Syncmoney

<p align="center">
  <a href="https://github.com/Misty4119/Syncmoney">
    <img src="https://img.shields.io/github/stars/Misty4119/Syncmoney" alt="Stars">
    <img src="https://img.shields.io/github/downloads/Misty4119/Syncmoney/total" alt="Downloads">
  </a>
  <a href="https://github.com/Misty4119/Syncmoney/releases/latest"><img src="https://img.shields.io/github/v/release/Misty4119/Syncmoney" alt="Release"></a>
  <a href="https://github.com/Misty4119/Syncmoney/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-green.svg" alt="License"></a>
  <a href="https://github.com/Misty4119/Syncmoney/actions/workflows/ci.yml"><img src="https://github.com/Misty4119/Syncmoney/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/Misty4119/Syncmoney/actions/workflows/codeql.yml"><img src="https://github.com/Misty4119/Syncmoney/actions/workflows/codeql.yml/badge.svg" alt="CodeQL"></a>
</p>
<p align="center">
  <a href="https://docs.papermc.io/paper/getting-started/"><img src="https://img.shields.io/badge/Platform-Paper%20%7C%20Folia%20%7C%20Canvas-orange.svg" alt="Platform"></a>
  <img src="https://img.shields.io/badge/Java-25%2B%20%281.4%20target%29-blueviolet.svg" alt="Java">
  <a href="https://official.noie.fun"><img src="https://img.shields.io/badge/Discord-Community-7289DA.svg?logo=discord&logoColor=white" alt="Discord"></a>
  <a href="https://paypal.me/NoieSrv"><img src="https://img.shields.io/badge/Donate-PayPal-blue" alt="Donate">
  </a>
</p>

<p align="center">
  <b>Vault / VaultUnlocked compatible cross-server economy synchronization with Redis Pub/Sub, multi-layer transaction guards, auditing, shadow backups, and embedded Web Admin.</b>
</p>

---

## Overview

Syncmoney is an enterprise-oriented Minecraft economy plugin focused on safe, consistent cross-server balance synchronization. It bridges Vault and VaultUnlocked economies across network nodes using Redis Pub/Sub and relational database persistence, backed by in-memory caching and comprehensive circuit breakers.

Syncmoney manages balances, currency operations, and its own economy commands and administration tiers. It is **not** a general-purpose permission or arbitrary command replicator.

---

## Key Features

- **Standard Economy Compatibility**: Integrates seamlessly with Vault 1.7 and VaultUnlocked (Vault2 API).
- **Economy Modes**: The code provides `local`, `local_redis`, `sync`, and `cmi` mode paths. Under ADR-12, full `cmi` mode is outside the verified 1.4.0 support scope and is not validated for production use.
- **Distributed Synchronization**: Redis-backed balance updates use atomic Lua scripts and per-account versions. CMI retains its legacy timestamp-derived version, which is not globally monotonic; its mirror uses an atomic exact-string compare-and-write guard, while licensed/live CMI remains outside verified 1.4.0 support.
- **In-Memory Performance**: O(1) balance reads served directly from memory; persistent storage writes handled asynchronously off the tick thread.
- **Multi-Layer Circuit Breaker**: Real-time per-transaction limits, anomaly rate detection, and an independent per-player guard (L1–L4 states).
- **Comprehensive Auditing & Analytics**: High-throughput asynchronous audit log pipeline with search, stats, automated cleanup, and external export.
- **Shadow Backups**: Independent background account synchronization designed for cold-standby snapshots and state tracking.
- **Embedded Web Admin Dashboard**: Modern web interface (Vue 3 / Undertow) for health telemetry, configuration inspection, audit viewing, and economy monitoring.
- **Observed 1.3.2 runtime coverage (historical)**: Paper 1.20.4/26.2, Folia `26.2.build.7-beta`, and Canvas 26.2; these observations do not establish 1.4.0 coverage.
- **Extensible Ecosystem**: Official PlaceholderAPI expansion and Bukkit transaction events (`PostTransactionEvent`).

---

## Platform & Compatibility

| Component | Requirement / Specification |
|---|---|
| **1.3.2 historical baseline** | Java 21 and Paper 1.20.4 baseline; the observed Paper/Folia/Canvas matrix below is historical. |
| **1.4.0 target** | Intended minimum: Java 25+ and Paper 1.21.6+. The R63 R9 Java 25.0.4 / Gradle 9.8.0 source/package gate passed 59 actionable tasks (59 executed), 170 unit suites / 945 tests, and 9 Sync integration suites / 44 tests (989 total; zero failures/errors/skips); architecture, classfile-major-69 packaging, API-consumer/acceptance packaging, and publisher isolation passed. Current preview core JAR: 31,646,522 bytes / SHA-256 `BB8C014ADB37CD407AEEED93BDDE16EBE13BA64272D0511F26155DB7EF3B1916`. Exact-candidate runtime/Beta remains NOT VERIFIED; stable metadata remains 1.3.2; no external release occurred. |
| **Economy Bridge** | Required: **Vault** (legacy 1.7) or **VaultUnlocked** (2.20.0+). *Do not load both concurrently under the same name.* |
| **Message Broker** | Redis 5.0+ (Required for `sync`, `local_redis`, and `cmi` modes). |
| **Database configuration targets** | MySQL 8.0+, MariaDB 10.5+, PostgreSQL 13+, or local SQLite. These are implementation/configuration targets, not verified 1.4.0 runtime support: storage-service integration is **DEFERRED / NOT VERIFIED**, and minimum-Paper MySQL/MariaDB initial-schema acceptance is **BLOCKED / NOT VERIFIED**. |
| **Optional Integrations** | PlaceholderAPI (with `SyncmoneyExpansion`), CMI (a locally supplied compatible release is required; CMI mode is outside the verified 1.4.0 support scope and is not validated for production use). |

> [!NOTE]
> Future server releases remain compatibility targets rather than unconditional guarantees. Always validate upgrades on a staging environment prior to updating production balances.

The user narrowed ADR-16 to live queued `/pay` recovery only; direct Vault/plugin recovery remains `DEFERRED / NOT VERIFIED` under ADR-07. The R63 R9 Java 25.0.4 / Gradle 9.8.0 source/package gate passed 59 actionable tasks (59 executed): 170 unit suites / 945 tests and 9 Sync integration suites / 44 tests (179 suites / 989 tests total), with zero failures, errors, or skips. Architecture, classfile-major-69 packaging, API-consumer/acceptance packaging, Central publisher isolation, frontend typecheck, 4 unit files / 22 tests, and production build passed. Preview artifacts: core 31,646,522 bytes / SHA-256 `BB8C014ADB37CD407AEEED93BDDE16EBE13BA64272D0511F26155DB7EF3B1916`; PAPI 13,872 bytes / SHA-256 `A4D893AC4892E9E715A1710A4760D7FB168EE84A7987BC43D1BAEA86EC6C319C`; acceptance 54,905 bytes / SHA-256 `29C41C996026B6275AD9276587D05E800759C710E4B485E5891CF1F85C281824`; API consumer fixture 2,760 bytes / SHA-256 `49325F358195BE3F0F1D4F99B2BABBB6DB3C26C212E2E5DA7DC9F4209A2A396E`. The production queued `/pay` integration exercised the production path with isolated Redis; no Paper/PlugDev server runtime acceptance was performed. Exact-candidate Paper runtime/T22 Beta and storage-service integration remain NOT VERIFIED; minimum-Paper SQL and licensed/live CMI (authorization UNKNOWN) remain BLOCKED / NOT VERIFIED; broad V03 remains SKIPPED / NOT VERIFIED; full public API compatibility and T21 completion remain NOT VERIFIED / PARTIAL. Stable metadata remains 1.3.2; Stable and external release remain NOT VERIFIED / NOT PERFORMED.

The historical 1.3.2 acceptance matrix covers Paper 1.20.4, Paper 26.2, Folia `26.2.build.7-beta`, Canvas 26.2, and a two-backend Paper 26.2 network behind the latest `velocity-ctd` build. Folia was tested successfully on that beta server build; this remains an observed compatibility baseline, not a promise that an unreleased server build will remain binary-compatible.

---

The install instructions below describe the published 1.3.2 artifact. For the 1.4.0 Java 25 / Paper 1.21.6 upgrade and rollback procedure, see the [developer guide](docs/DEVELOPER_GUIDE.md#upgrade-and-rollback).

## Installation & Setup

1. **Backup**: Stop your server and take a full backup of existing plugin configs, economy databases, and Redis persistence.
2. **Install Plugin**: Place `Syncmoney-1.3.2.jar` and your preferred Vault provider in the server's `plugins/` directory. Remove any older Syncmoney JAR versions.
3. **Initialize Configuration**: Start the server once to generate default configuration files, then stop it.
4. **Configure Node**: In `plugins/Syncmoney/config.yml`:
   - Assign a unique `server-name` (e.g., `survival-01`, `lobby-01`).
   - Select your target `economy.mode`.
   - Configure Redis and SQL database connection credentials if using synchronized modes.
5. **Start & Verify**: Start the server. Confirm in the console logs that Syncmoney and Vault have registered successfully. Verify basic transactions with `/money` and `/pay`.
6. **Placeholders (Optional)**: If using PlaceholderAPI, copy `SyncmoneyExpansion-1.3.2.jar` into `plugins/PlaceholderAPI/expansions/` and run `/papi reload`.

---

## Economy Modes

The table describes available configuration/code paths, not full runtime-verified support. The historical R52F r9 M04 result is tied to its own JAR and does not validate later candidates. R14 did not produce an acceptance marker, and no R53 Paper runtime was launched. Exact runtime acceptance, full LOCAL support, and full T22 Beta remain NOT VERIFIED.

| Mode (`economy.mode`) | Storage & Architecture | Intended Environment |
|---|---|---|
| `local` | Single-server SQLite file. No Redis or external database required. | Standalone servers without cross-server requirements. |
| `local_redis` | Redis-backed network economy without SQL persistence. | Networks relying on persistent Redis storage (AOF/RDB). |
| `sync` | Redis Pub/Sub + central SQL database (MySQL / MariaDB / PostgreSQL). | Standard multi-server networks requiring robust relational persistence. SQLite is for LOCAL/Shadow storage, not this shared database connection. |
| `cmi` | CMI remains the configured authority, but CMI mode is outside the verified 1.4.0 support scope. Do not treat it as production-validated for 1.4.0. | Not a verified 1.4.0 deployment profile. |
| `auto` | Automatically detects installed environment and suggests mode. | For quick evaluations; explicit mode configuration is recommended for production. |

The Syncmoney `/pay` command is unavailable in `cmi` mode: the CMI adapter has no atomic two-account transfer contract, so Syncmoney rejects the command before changing balances.

The same restriction applies to paired transfers called through Vault's explicit target-player or plugin transfer methods, VaultUnlocked delegates, and the exposed low-level Redis transfer API. Syncmoney rejects these before memory, queue, or Redis mutation. Single-account CMI operations remain under CMI authority. This fail-closed source behavior does not verify licensed/live CMI; CMI mode remains outside the verified 1.4.0 support scope.

Syncmoney-owned shared `LOCAL_REDIS` and `SYNC` balances have a per-account ceiling of `999999999999999999.99`, matching the shared SQL `DECIMAL(20,2)` and Redis cents range. This change does not narrow the existing `LOCAL` policy or clamp or migrate local balances as part of the shared-mode guard. The explicit LOCAL-to-SYNC migration first rejects raw negative SQLite balances before normalization, including negative sub-cent values that would otherwise round to 0.00. It then rejects normalized balances above the shared ceiling before insert-only or force-replacement SQL is prepared. A player UUID source value must use the standard 36-character 8-4-4-4-12 hexadecimal form (either hex case); missing, malformed, or abbreviated UUID text fails the local snapshot before shared SQL or Redis writes and is recorded as a failed source row. Rejected rows stay unchanged in LOCAL and are not copied to Redis. Before switching older Redis data to a shared mode, check for existing values above this ceiling: 1.4.0 does not change them automatically, and shared deposits, withdrawals, and transfers reject those accounts before memory mutation. An admin set is an explicit operator change, not an automatic migration; when audit is enabled, Syncmoney rejects a balance change whose delta cannot fit SQL `DECIMAL(20,2)` before mutation. Queued `/pay` transfers use the existing `recovery-v1.jsonl` spool. Startup validates the queued spool and legacy overflow log before opening shared-mode write admission; unreadable or malformed recovery evidence keeps admission closed, while affected accounts remain unavailable until their pending recovery is resolved. A forced `RETRYABLE` paired-admission checkpoint precedes queue commit. After Redis dispatch, `ATTEMPTING`/`AMBIGUOUS` outcomes remain quarantined across restart and are visible through admin-only `/syncmoney recovery list|inspect|reconcile <id>` (permission `syncmoney.admin.recovery` or `syncmoney.admin`). Reconciliation runs asynchronously and accepts only an exact Redis receipt matching the stable operation ID, canonical fingerprint, account pair, and versions. Missing, malformed, colliding, in-flight, mismatched, or unreadable receipts retain quarantine; balances alone do not resolve an ambiguous operation, and there is no automatic replay or force-clear path. FileChannel force supports local process-restart recovery; power-loss and storage-device durability beyond that behavior are not claimed. Direct Vault/plugin atomic-transfer recovery remains `DEFERRED / NOT VERIFIED` under ADR-07; no direct-transfer journal, capability, provider wiring, or command recovery path is included. Exact-candidate Paper runtime acceptance remains NOT VERIFIED. The SQL write queue keeps a bounded process-local receipt cache for recent stable operation IDs, reuses matching in-flight/successful snapshots, and rejects an ID reused with different account UUID/balance/version data; SQL snapshot version ordering remains required after cache eviction or restart.

Run LOCAL-to-SYNC migration only in a maintenance window after stopping economy producers on every node that shares the destination SQL/Redis stores. The migration lease fences Syncmoney operations in the current JVM only. SQL insertion and Redis population are not one cross-store transaction; if another writer creates a Redis account after preflight, the Redis write fails closed but the SQL insert may already exist. Keep players and other writers from making economy changes, inspect the checkpoint, and reconcile both stores before switching to SYNC. Migration with online players or concurrent shared-store writers is **NOT VERIFIED**.

/syncmoney migrate stop requests a safe-boundary pause for an active migration. Its reply confirms that the request was accepted, not that the task has already stopped. If LOCAL-to-SYNC is importing transaction history, that atomic SQL transaction cannot be interrupted; the stop is checked after it returns. `/syncmoney migrate clear` refuses to delete the checkpoint while either migration is running or draining. Use /syncmoney migrate status to inspect the checkpoint.

Administrative give/take commands do not synchronously load a cold balance to perform a mutation or format a failure reply. A failed take can show N/A when the balance is not available in memory.

An earlier `1.4.0-alpha.1` candidate had a bounded, read-only CMI check on Paper 1.21.6 with CMI 9.8.10.5 and CMILib 1.6.0.1. One online Acceptance player's direct CMI balance, Syncmoney CMI route, and public `BalanceSnapshot` read matched. Exact final-candidate CMI runtime acceptance remains NOT VERIFIED. This observation does not establish active CMI license status, Syncmoney Vault provider registration in CMI mode, mutations, cross-server behavior, or Folia/Canvas behavior. Under ADR-12, CMI mode remains outside verified 1.4.0 support claims.

The matched synthetic facade/receipt-adapter benchmark did not meet all original performance thresholds. Under ADR-11, the measured regression is accepted only for that bounded synthetic profile; correctness hard gates remain unchanged, and this is not a production, SQL, Redis, or real-server capacity claim.

### Minimal Standalone Configuration (`local`)

```yaml
server-name: "single-01"
economy:
  mode: "local"
redis:
  enabled: false
database:
  enabled: false
db-enabled: false
pubsub-enabled: false
```

### Shared Network Configuration (`sync`)

Set `economy.mode: "sync"`, enable both `redis` and `database`, and ensure `db-enabled: true` and `pubsub-enabled: true`. Point all participating nodes to the same isolated Redis database and relational database schema while giving each server a distinct `server-name`.

---

## Optional Modules & Lifecycle

All optional modules can be toggled in `plugins/Syncmoney/config.yml`.

| Module | Configuration Switch | Description & Disabled Behavior |
|---|---|---|
| **Global Circuit Breaker** | `circuit-breaker.enabled` | Halts global economy operations upon abnormal inflation or anomalies. Status reports disabled when inactive. |
| **Per-Player Protection** | `circuit-breaker.player-protection.enabled` | Independent rate-limiting, warnings, and automatic account freeze for suspicious player activities. |
| **Transaction Guard** | `transfer-guard.enabled` | Protects players from balance inconsistency during cross-server transfers or teleports. |
| **Audit Logging** | `audit.enabled` | Asynchronous transaction audit trail. Sub-features (`cleanup`, `export`, `redis`) require this parent switch. |
| **Shadow Sync** | `shadow-sync.enabled` | Background snapshot mirroring to secondary databases. Does not replace primary database backups. |
| **Discord Webhooks** | `discord-webhook.enabled` | Real-time notifications for circuit breaker triggers, locks, and migration events. |
| **Web Admin Panel** | `web-admin.enabled` | Embedded Undertow HTTP/SSE service for the administrative dashboard; `/ws` is unavailable. |

> [!IMPORTANT]
> **Configuration Reload vs. Full Restart**:
> `/syncmoney reload` updates localization messages and safe runtime parameters (`display`, `pay`, `permissions`, `admin-permissions`, `debug`).
> Changes to database/Redis connections, economy modes, module switches, schedulers, or Web Admin server endpoints require a **full server restart**. If modified, reload will notify you of the restart-only keys.

---

## Commands & Permissions

### Player Commands

| Command | Description | Default Permission |
|---|---|---|
| `/money` | View personal balance | `syncmoney.money` (default: true) |
| `/money <player>` | View another player's balance | `syncmoney.money.others` (default: op) |
| `/pay <player> <amount>` | Send money to another player | `syncmoney.pay` (default: true) |
| `/pay confirm` | Confirm high-value transaction above threshold | None (session verified) |
| `/baltop [page]` | View global wealth leaderboard | `syncmoney.money` (default: true) |
| `/baltop me` | Check personal leaderboard position | `syncmoney.money` (default: true) |

### Administrative Commands

| Command | Description | Permission |
|---|---|---|
| `/syncmoney admin give <player> <amount>` | Add balance to player | `syncmoney.admin.give` / Admin tier |
| `/syncmoney admin take <player> <amount>` | Deduct balance from player | `syncmoney.admin.take` / Admin tier |
| `/syncmoney admin set <player> <amount>` | Set player balance directly | `syncmoney.admin.set` / Admin tier |
| `/syncmoney admin reset <player>` | Reset player balance to zero | `syncmoney.admin.set` / Admin tier |
| `/syncmoney admin view <player>` | Inspect detailed balance state | `syncmoney.admin` |
| `/syncmoney recovery list|inspect|reconcile <id>` | Inspect or reconcile durable queued `/pay` recovery; never force-clears or replays an ambiguous transfer | `syncmoney.admin.recovery` or `syncmoney.admin` |
| `/syncmoney breaker status` | View circuit breaker health and state | `syncmoney.admin` |
| `/syncmoney breaker reset` | Reset tripped global breaker | `syncmoney.admin` |
| `/syncmoney breaker unlock <player>` | Manually unlock a frozen player | `syncmoney.admin` |
| `/syncmoney audit <player> [page]` | View player audit history | `syncmoney.admin.audit` |
| `/syncmoney audit search <args>` | Advanced audit record search | `syncmoney.admin.audit` |
| `/syncmoney audit stats` | View audit storage statistics | `syncmoney.admin.audit` |
| `/syncmoney monitor [redis\|cache\|db]` | View real-time system and resource health | `syncmoney.admin.monitor` |
| `/syncmoney econstats [supply\|players]` | Inspect total supply and distribution | `syncmoney.admin.econstats` |
| `/syncmoney debug <player\|system>` | Multi-tier balance inspection across layers | `syncmoney.admin` |
| `/syncmoney version [full\|save]` | Generate basic or administrator support diagnostics | Basic: public; full/save: `syncmoney.admin` |
| `/syncmoney sync-balance <player>` | Force push memory balance to Redis and SQL | `syncmoney.admin` |
| `/syncmoney shadow [status\|now\|logs]` | Monitor and trigger Shadow Sync tasks | `syncmoney.admin` |
| `/syncmoney web [status\|open\|reload]` | Manage embedded web service | `syncmoney.admin` |
| `/syncmoney migrate <cmi\|local-to-sync\|status\|stop\|resume\|clear>` | Run, inspect, stop, or resume an economy migration | `syncmoney.admin` |
| `/syncmoney test concurrent-pay <t> <i>` | Run concurrent load tests on disposable setups | `syncmoney.admin.test` |
| `/syncmoney reload [config\|messages]` | Reload allowable configuration sections | `syncmoney.admin.reload` |

### Administrative Permission Tiers

Configurable in `config.yml` under `admin-permissions`:

| Tier | Permission Node | Default Daily Limits |
|---|---|---|
| **Observe** | `syncmoney.admin.observe` | View only (0 give / 0 take) |
| **Reward** | `syncmoney.admin.reward` | 100,000 give / 0 take |
| **General** | `syncmoney.admin.general` | 1,000,000 give / 1,000,000 take |
| **Full** | `syncmoney.admin.full` | Unlimited |

---

## PlaceholderAPI Identifiers

Syncmoney provides an optional expansion (`SyncmoneyExpansion`) for PlaceholderAPI:

| Placeholder | Description |
|---|---|
| `%syncmoney_balance%` | Unformatted numeric balance |
| `%syncmoney_balance_formatted%` | Balance formatted with comma groupings (`1,250.00`) |
| `%syncmoney_balance_abbreviated%` | Abbreviated balance notation (`1.5K`, `2.4M`) |
| `%syncmoney_balance_<player>%` | Balance of a specific target player |
| `%syncmoney_rank%` or `%syncmoney_my_rank%` | Player's rank on the leaderboard |
| `%syncmoney_top_<n>%` | Balance of the player at rank `n` |
| `%syncmoney_total_supply%` | Total currency circulating in the economy |
| `%syncmoney_total_players%` | Total registered accounts tracked in leaderboard |
| `%syncmoney_online_players%` | Current online player count |
| `%syncmoney_version%` | Active Syncmoney plugin version |

> [!TIP]
> Cache misses for offline player lookups resolve asynchronously in the background. If data is still loading, placeholders return `N/A` without freezing the main server thread.

---

## Web Admin Setup & Security

The Web Admin module includes a lightweight Undertow HTTP server and pre-built frontend distribution.

```yaml
web-admin:
  enabled: true
  server:
    host: "127.0.0.1"
    port: 8080
  security:
    api-key: "GENERATE_A_LONG_SECURE_RANDOM_KEY"
    cors-allowed-origins: "https://admin.yournetwork.com"
```

### Security Recommendations

1. **Reverse Proxy**: Bind Web Admin to `127.0.0.1` and route external traffic through Nginx or Caddy with HTTPS.
2. **API Key**: Always replace the placeholder API key with a cryptographically secure token.
3. **SSE**: When reverse proxying, ensure proxy buffering is disabled to support live SSE updates. `/ws` currently returns `503 WEBSOCKET_UNAVAILABLE`.
4. **Firewall**: Restrict external access to the configured HTTP port.

---

## Developer Integration

### Vault Provider Access

Syncmoney registers itself as a standard Vault provider:

```java
RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
if (rsp != null) {
    Economy economy = rsp.getProvider();
    BigDecimal balance = BigDecimal.valueOf(economy.getBalance(player));
}
```

On servers running VaultUnlocked, Syncmoney also registers modern Vault2 services automatically.

### Transaction Events

Subscribe to `PostTransactionEvent` for transaction auditing:

```java
@EventHandler
public void onPostTransaction(PostTransactionEvent event) {
    if (!event.isSuccess()) {
        return;
    }
    UUID playerUuid = event.getPlayerUuid();
    BigDecimal amount = event.getAmount();
    AsyncPreTransactionEvent.TransactionType type = event.getType();
    // Handle post-transaction telemetry
}
```

---

## Building from Source

The 1.4.0 foundation branch uses Java 25 and Gradle 9.8.0. Stable release metadata still reads 1.3.2 until the release task; the preserved 1.3.2 baseline used Java 21 and Gradle 9.1.0.

```bash
# Build the plugin JAR and PAPI expansion
./gradlew test shadowJar :syncmoney-papi-expansion:jar

# Build Web Admin frontend (requires Node.js and pnpm)
cd syncmoney-web
pnpm install
pnpm typecheck
pnpm test:unit --run
pnpm build
```

Compiled JAR files are produced in `build/libs/` and `syncmoney-papi-expansion/build/libs/`.

## Release and CI workflow

Every pull request and `main` push runs Java and Web checks and retains build artifacts in GitHub Actions. CI preview artifacts use the repository variable `NEXT_VERSION` when set, otherwise falling back to the version in root `build.gradle`; their name is `<base-version>-alpha.<run-number>`. The hosted repository-variable value is not stored in this checkout. Preview artifacts are test artifacts, not public GitHub Releases.

The core repository is the canonical Web Admin source. A release tag in the core repository has no `v` prefix (`<version>-alpha.<number>`, `<version>-beta.<number>`, or `<version>`); the automation synchronizes the frontend to [Syncmoney-web](https://github.com/Misty4119/Syncmoney-web), where the corresponding tag has a `v` prefix. The Web release publishes `syncmoney-web.tar.gz`, a deterministic source archive. The plugin downloads that archive, installs dependencies, and builds locally before loading it.

To publish a preview, push an explicit core tag such as `<version>-beta.1`. The Web release is published first, then the core release. A stable tag such as `<version>` additionally requires the `production-release` environment approval and a matching `CHANGELOG.md` section. The cross-repository job uses the `SYNC_WEB_RELEASE_TOKEN` secret, which must be a fine-grained token limited to the `Misty4119/Syncmoney-web` repository.

When Web source changes, run `pnpm build:embedded` from `syncmoney-web`. The command clears and replaces `src/main/resources/syncmoney-web/dist` and updates `WebAdminServer.extractIndividualFiles` in the same operation. Commit the generated bundle and the Java extraction lists together.

---

## Troubleshooting & Support

- **Balances Not Synchronizing**: Ensure all nodes have identical Redis server settings and databases. Verify that each node has a unique `server-name` in `config.yml` and `pubsub-enabled: true`.
- **Account Locked**: If a player account trips rate or anomaly limits, inspect `/syncmoney audit <player>` to diagnose the trigger, then unlock with `/syncmoney breaker unlock <player>`.
- **Configuration Reload Incomplete**: Check the console output when executing `/syncmoney reload`. Settings requiring service reconstruction require a full server restart.
- **Reporting Issues**: Include the basic report from `/syncmoney version`. Administrators can use `/syncmoney version full` for read-only Redis/database probes, or `/syncmoney version save` to save a sanitized report under `plugins/Syncmoney/reports/`. Also include server software build (`/version`), Java version, and sanitized logs/configs (redact passwords, API keys, and webhook URLs).

- **GitHub Issues**: [Issues Tracker](https://github.com/Misty4119/Syncmoney/issues)
- **Community Discord**: [Join Discord](https://official.noie.fun)

---

## License

This project is licensed under the [Apache License 2.0](LICENSE).
