# Syncmoney 1.3.2 compatibility baseline

## Evidence identity

- Source commit: 71cc55dfeefe9a8f40a3835b051ddd324d353767.
- Release tag: annotated 1.3.2, tag object 24da5a6cafdb4803f06d81a3f7a85518fb0f2ff5, peeled commit 9acbced657aaff410e712b2d3e4ecf948fd4002a.
- The tag is present locally and has a published GitHub release. Signature check reports “no signature found”; tag identity and asset evidence are recorded separately from cryptographic verification.
- The only tracked source delta from the peeled release commit to the task source commit is .github/workflows/release.yml. Twelve packaged resource files differ byte-for-byte between the official JAR and Windows checkout, but their Git blob IDs match at both commits and their contents match after CRLF-to-LF normalization. All 9,349 JAR entries exist in both builds; class entries have no size/CRC differences. This is checkout line-ending behavior, not source drift.
- This fixture contains compatibility metadata only. No runtime database, operator config values, credentials, or player data are copied.

## Release artifacts and package inventory

Official assets were downloaded from the 1.3.2 GitHub release and verified against its published CHECKSUMS.txt.

| Artifact | Official SHA-256 | Observed inventory |
|---|---|---|
| Syncmoney-1.3.2.jar | 10efeeeb0f2511afe450dd978ce7543828289823d27734e2dde40142813c1627 | 31,277,107 bytes; 9,349 ZIP entries; contains plugin.yml, config.yml, noietime/syncmoney/Syncmoney.class, all eight Lua resources below, and DatabaseManager.class with the player_uuid constant |
| SyncmoneyExpansion-1.3.2.jar | fd5353010ed15b86578f6461f4f85f6dc46f5ce20934f10a9db753a65ea6b21f | 13,828 bytes; 11 ZIP entries; contains expansions.yml and noietime/syncmoney/expansion/SyncmoneyExpansion.class |

The official core descriptor reports name Syncmoney, main noietime.syncmoney.Syncmoney, api-version 1.20, and folia-supported true; bundled config-version is 12. These are historical 1.3.2 values, not the 1.4.0 Java/Paper baseline.

The local Java 21 / Gradle 9.1.0 baseline build emitted Syncmoney-1.3.2.jar, SyncmoneyExpansion-1.3.2.jar, and build/acceptance/SyncmoneyAcceptance.jar. Local core JAR SHA-256: 442a404276b538ce807b0f063fb37920be85e60961a52a475b3f3524d08f4f10. Its normalized resource content and class-entry inventory were compared with the official asset above. The local PAPI JAR SHA-256 equals the official release asset.

## Plugin descriptors and legacy Java signatures

- Plugin entry point: noietime.syncmoney.Syncmoney. Descriptor, commands, permissions, Vault dependency, optional integrations, and API metadata are in src/main/resources/plugin.yml.
- legacy-public-signatures.txt was generated with javap -public against the checksum-verified official core JAR. It freezes public descriptors of Syncmoney, EconomyFacade, BaltopManager, RankEntry, and NameResolver; this is a static inventory, not a compiled third-party compatibility test.
- The main plugin class has public lifecycle methods onEnable() and onDisable(), service/configuration getters, reload/message methods, and getPluginContext(). Keep existing return FQCNs/descriptors when moving implementation.
- PlaceholderAPI expansion id is syncmoney; it is persistent and resolves the plugin lazily through reflection. Current reflected seams include getEconomyFacade(), getBaltopManager(), getNameResolver(), getBalanceForPlaceholder(UUID), getPlayerRank(UUID), getTopRank(int), getTotalSupply(), getTotalRegisteredPlayers(), and resolveUUIDForPlaceholder(String). Rank extraction reads balance() and has a field fallback. The expansion remains separately installable.
- Reflection call sites and lifecycle ownership are static evidence. No separately compiled historical consumer fixture was run in T00.

## Configuration shape

- Config schema key is config-version; bundled value in the official release is 12.
- config-leaf-paths.txt contains the 193 unique dotted leaf paths parsed from default config.yml. Sequence members use []. Key paths only; values are excluded.
- Required key server-name has a blank bundled default. Operators must configure it for normal operation.
- Preserve supported 1.3.2 keys, user values, and unknown leaves during migration. ConfigMerger fills missing default leaves, preserves existing values/unknown leaves, does not rename/remove keys, and does not automatically increment an existing schema value. Backup/write failure behavior remains for later characterization.
- Local SQLite setting path is economy.local.sqlite-path; fallback is plugins/Syncmoney/syncmoney.db. CMI Redis prefix setting path is economy.cmi.redis-prefix; default prefix is syncmoney:cmi. No secret-bearing config values are reproduced.
- Plugin release version, bundled config schema, shared-player schema, audit schema, and wire format are separate version domains.

## SQL and local data contracts

Static contracts below come from named source files. No live operator database was read or copied.

| Store | Observed contract |
|---|---|
| Shared MySQL/PostgreSQL players | player_uuid VARCHAR(36) PRIMARY KEY; player_name VARCHAR(16); balance DECIMAL(20,2) NOT NULL DEFAULT 0; version BIGINT NOT NULL DEFAULT 0; last_server VARCHAR(64); updated_at TIMESTAMP; index idx_player_name(player_name). Keep player_uuid; do not rename it to uuid. |
| Shared schema version | syncmoney_schema_version; players initialization uses id 1, version 1. |
| Audit schema version | Same version table, audit id audit, target version 11. Audit table syncmoney_audit_log; preserve existing fields and added request/sequence columns. |
| Local SQLite | player_balances(uuid TEXT PRIMARY KEY, name TEXT, balance REAL, version INTEGER, last_update INTEGER, created_at INTEGER) and transactions(id INTEGER PRIMARY KEY AUTOINCREMENT, uuid, name, type, amount, balance_before, balance_after, source, target_name, timestamp, sequence). Local Java version remains int; SQLite INTEGER does not imply Java long. |
| Baltop | SQL table syncmoney_baltop keyed by player_uuid, with player name, balance, rank, and last-update metadata. |
| Shadow | Separate shadow_sync_history tables belong to MySQL, PostgreSQL, and SQLite Shadow adapters; do not unify them with players/audit/baltop. |
| Local path and bundled migration | Preserve plugins/Syncmoney/syncmoney.db fallback and bundled database/migration.sql resource. |

## Redis keys and Pub/Sub

Key type/TTL facts are source-observed, not live Redis measurements.

| Key / channel | Type and compatibility notes |
|---|---|
| syncmoney:balance:<uuid> | String decimal balance; UUID appended without braces. |
| syncmoney:version:<uuid> | String integer version; keep its current ordering domain. |
| syncmoney:baltop | Sorted set; UUID members and balance scores. |
| syncmoney:baltop:total | String total-supply value. |
| syncmoney:online:players | Set of UUID strings. |
| syncmoney:online:player:<uuid> | Hash fields name and server; 90-second per-hash TTL. |
| syncmoney:audit:recent | List of audit JSON; 30-day TTL. |
| syncmoney:audit:index | Integer index string; 30-day TTL. |
| syncmoney:audit:dedup:<timestamp>:<type>:<uuid>:<amount> | Audit dedup marker; preserve composite naming contract. |
| syncmoney:bank:<name>, syncmoney:bank:owner:<name>, syncmoney:bank:version:<name> | Bank balance, owner, and version keys; names are normalized by current handler behavior. |
| <configured-cmi-prefix>:<uuid>, <configured-cmi-prefix>:<uuid>:version | CMI mirror balance/version; default prefix syncmoney:cmi. |
| syncmoney:overflow:events | Sorted set of overflow records, scored by timestamp; file fallback also exists. |
| syncmoney:plugin_transfer:<senderVersionKey>:<epochSeconds> | Plugin transfer audit hash written by Lua; 24-hour TTL. |
| syncmoney:balance:update | Primary balance Pub/Sub channel. |
| syncmoney:cmi:balance:update | CMI balance Pub/Sub channel. |

Legacy balance message fields and Java types: uuid:String, balance:double, version:long, sourceServer:String, messageId:String, timestamp:long, eventType:String, amount:double, sourcePlugin:String, sourcePlayerName:String. Null metadata is generally omitted by Gson; unknown JSON fields are ignored; missing primitive fields take Java defaults; unknown event values map to UNKNOWN. Keep source identity, monotonic-version comparison, and echo suppression. This does not establish global mixed-version consistency.

## Lua script call contracts

These are static argument/result shapes and exact source snapshots. They do not imply rollback on Lua runtime error or deduplication of player mutations.

| Script | KEYS order | ARGV order | Successful result |
|---|---|---|---|
| atomic_add_balance.lua | balance, version | signed amount | [balanceString, versionString] |
| atomic_set_balance.lua | version, balance | target balance | version string |
| atomic_transfer.lua | sender balance, sender version, receiver balance, receiver version | amount | [senderBalance, receiverBalance, senderVersion, receiverVersion] |
| atomic_plugin_transfer.lua | sender balance, sender version, receiver balance, receiver version | amount, plugin name | same four-field transfer result; writes a 24-hour plugin audit hash |
| atomic_bank_deposit.lua | bank balance, bank version | amount | [newBalance, normalizedAmount, newVersion] |
| atomic_bank_withdraw.lua | bank balance, bank version | amount, optional new owner balance | [newBalance, normalizedAmount, newVersion] |
| atomic_bank_transfer.lua | source bank balance, destination bank balance, source version, destination version | amount | [sourceBalance, destinationBalance, sourceVersion, destinationVersion] |
| atomic_audit.lua | audit list, audit index | max size, record JSON | [currentSize, newIndex] |

Preserve error names and KEYS/ARGV order. Lua atomic execution prevents command interleaving but does not roll back earlier writes if a later command errors. Existing player scripts have no request-id dedup marker.

## Source SHA inventory

The source commit SHA above identifies the Git commit. The table below records Git blob object IDs for individual files at that commit; line-number references are relative to that tree. Artifact SHA-256 checksums are above. Tag and source commit have identical blob IDs for every source path listed here.

| Source path | Git blob SHA |
|---|---|
| build.gradle | b09ca7378ab913a56134c88bcb10d853966de579 |
| settings.gradle | ab0cf3c66aa1a152fa8afe4b0f35f25ca5cd1e26 |
| gradle/wrapper/gradle-wrapper.properties | 2e1113280ef142e60ad69ba98918fc408d759137 |
| src/main/resources/plugin.yml | bf1f9663e9587d4f568a12457e3eb6fe82c42d69 |
| syncmoney-papi-expansion/src/main/resources/expansions.yml | a5436dd9a713e2bef190620fd3e7127f5d58c1da |
| src/main/resources/config.yml | aeebd779bda1eb382ee1e75ad7aa8fd7870fce27 |
| src/main/resources/messages.yml | 8576e35f75a8f9fa1cc46f4f43e8e30bc22d379b |
| src/main/resources/alias.yml | 89481206d8e13a43ca8f5ac4e42b73cdd454eda4 |
| src/main/resources/database/migration.sql | 4e2d6ed9fd48d4763d058ef35230fada8b95a580 |
| src/main/java/noietime/syncmoney/Syncmoney.java | adfc2398ddae26b9eecea945ac714cc4d390aac6 |
| src/main/java/noietime/syncmoney/config/ConfigManager.java | d9c97830b1e1b002c9f797ffc96a28893d742f44 |
| src/main/java/noietime/syncmoney/config/ConfigReloadPolicy.java | d04a8d3eaf42f6ae9308c7c9749f695db77d900b |
| src/main/java/noietime/syncmoney/config/LocalConfig.java | c7e512045e9b22757473530dfc5c5771d1a361b5 |
| src/main/java/noietime/syncmoney/config/CMIConfig.java | 6508942cd6af9331978047d684196ef0a8b06a67 |
| src/main/java/noietime/syncmoney/util/ConfigMerger.java | 5f004e940d1c853d46b1266cb3c71950272467b6 |
| src/main/java/noietime/syncmoney/storage/db/DatabaseManager.java | f2c1cf9df9f753963967b7c4348364e12a6dac07 |
| src/main/java/noietime/syncmoney/schema/SchemaManager.java | 633f93002fd69673d061db660eebdd76db7b2c1b |
| src/main/java/noietime/syncmoney/economy/LocalEconomyHandler.java | 41eb310f0bb0dec1c8a4bb72c337b2b5354bea66 |
| src/main/java/noietime/syncmoney/economy/RedisOverflowLog.java | 85aa6adf2b7b65bdec516ec92d057eae7ad3a4e1 |
| src/main/java/noietime/syncmoney/storage/CacheManager.java | 4af05bf2eb10c44e6bef06967935cee436558410 |
| src/main/java/noietime/syncmoney/uuid/OnlinePlayerRegistry.java | bedfaaf85e111aaef96c579db62c29daf99a5762 |
| src/main/java/noietime/syncmoney/baltop/BaltopManager.java | 0a208a32c6de20fb30827b79e901e2f43c4804c2 |
| src/main/java/noietime/syncmoney/baltop/RankEntry.java | e686942cd3ed06af49597c9e9fff7de169701692 |
| src/main/java/noietime/syncmoney/audit/HybridAuditManager.java | f6da906210213cd788331585b0c5c83b24ca2295 |
| src/main/java/noietime/syncmoney/vault/VaultBankHandler.java | 816b7739bbf3a2c93fc4bb5df43c5544741f5293 |
| src/main/java/noietime/syncmoney/sync/PubSubChannels.java | 47f2e17206cefcf3a9b62f155fa3c395cb5974d3 |
| src/main/java/noietime/syncmoney/sync/PubSubMessage.java | 22dfae98901cc8741b0d3ffb8cfdc88865c86ace |
| src/main/java/noietime/syncmoney/sync/PubsubSubscriber.java | 0cc6bdf86ad4254ce4a1f720aee064964cd9cfcc |
| src/main/java/noietime/syncmoney/sync/CMIPubsubHandler.java | 9cce4c51263ae75905ebfab5af0ef89c4860333e |
| src/main/java/noietime/syncmoney/economy/CMIEconomyHandler.java | 8ba49e59887705e24ec1de542b154fcebfe23c07 |
| src/main/java/noietime/syncmoney/shadow/storage/MysqlShadowStorage.java | cd47be3d592cc66a6ea06a3074d8c0ddc077b05e |
| src/main/java/noietime/syncmoney/shadow/storage/PostgresShadowStorage.java | 90350585c591c458158be488f481457b2b3e9598 |
| src/main/java/noietime/syncmoney/shadow/storage/SqliteShadowStorage.java | 285bdd67d9917abe4551525cf5d4fb8c7e3cfece |
| syncmoney-papi-expansion/build.gradle | 6632006757c1b435f75e22431e941eb80b2637dc |
| syncmoney-papi-expansion/src/main/java/noietime/syncmoney/expansion/SyncmoneyExpansion.java | cd9aef76945afbacb12c4619b07ce5eacf2df32f |
| syncmoney-papi-expansion/src/main/java/noietime/syncmoney/expansion/PlaceholderHandler.java | 5f9e88278077b45b1e456b61d0c2fde74611472a |
| src/main/resources/lua/atomic_add_balance.lua | e83af431a8d8872fafb976766ac048ad8fd6e19e |
| src/main/resources/lua/atomic_audit.lua | 624e214e12382bf58aa5956b54594c7fe3201607 |
| src/main/resources/lua/atomic_bank_deposit.lua | 890a1fc5210e285c728b951c886f64bad904d671 |
| src/main/resources/lua/atomic_bank_transfer.lua | e55b277a9df793ec7ba59cb5733c2606cd7ec632 |
| src/main/resources/lua/atomic_bank_withdraw.lua | 71d4066ca7f399354690da69d57d3dc645b8ce7d |
| src/main/resources/lua/atomic_plugin_transfer.lua | 94e81c62179619d37a702cb4f19f95d4c68787a6 |
| src/main/resources/lua/atomic_set_balance.lua | 73aadbb2efbc6828cf42ed0d744e49857cd31715 |
| src/main/resources/lua/atomic_transfer.lua | 4e3af19121845079cdf583c0c782025847da2320 |

## Source locations

- Build and root module layout: build.gradle, settings.gradle, gradle/wrapper/gradle-wrapper.properties.
- Plugin descriptor and entry point: src/main/resources/plugin.yml:1-25; src/main/java/noietime/syncmoney/Syncmoney.java:428-544,898.
- Config schema/default: src/main/resources/config.yml:6,12,94; merger: src/main/java/noietime/syncmoney/util/ConfigMerger.java:105-225; reload roots: src/main/java/noietime/syncmoney/config/ConfigReloadPolicy.java:12-26.
- Shared SQL DDL: src/main/java/noietime/syncmoney/storage/db/DatabaseManager.java:50-128,317-366; audit DDL/versioning: src/main/java/noietime/syncmoney/schema/SchemaManager.java:32-113,124-141,248-395.
- Local SQLite DDL and setting: src/main/java/noietime/syncmoney/economy/LocalEconomyHandler.java:81-146,215,284,347,390-397; src/main/java/noietime/syncmoney/config/LocalConfig.java:20-24.
- Redis key/type/TTL owners: CacheManager.java:25-26; OnlinePlayerRegistry.java:20-27,71-88; BaltopManager.java:30-31; HybridAuditManager.java:37-38,119-120,207; VaultBankHandler.java:21-23; RedisOverflowLog.java:18-25; CMI key construction in CMIConfig.java:20-21, CMIPubsubHandler.java, and CMIEconomyHandler.java.
- Protocol channels and DTO: PubSubChannels.java:13-15; PubSubMessage.java:29-69,75-101,128-151; freshness and echo handling: PubsubSubscriber.java:227-288.
- PAPI expansion/reflection: SyncmoneyExpansion.java:46,66; PlaceholderHandler.java:24-47,127-259,319,351-392,506-514.
- Lua keys, arguments, result shape, and script bytes: src/main/resources/lua/*.lua; active callers in CacheManager.java:132-140,267-268,332-333,463-466 and VaultBankHandler.java:60-72.

## Test and validation evidence

Executed in the isolated task worktree at source SHA above with Java 21.0.6, Gradle wrapper 9.1.0, Node v24.19.0, and pnpm 12.5.1:

| Command | Observed result |
|---|---|
| .\gradlew.bat test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar | Exit 0, BUILD SUCCESSFUL; 126 Java tests, 0 failures, 0 errors, 0 skipped; all three named artifacts produced. |
| pnpm --dir syncmoney-web typecheck | Exit 0. |
| pnpm --dir syncmoney-web test:unit --run | Exit 0; 4 files passed, 22 tests passed. |
| pnpm --dir syncmoney-web build | Exit 0; Vite/PWA production build completed. |
| Official release checksum and JAR inventory | Core and PAPI SHA-256 matched published CHECKSUMS.txt; descriptors, config schema, main class, SQL column constant, Lua resources, and artifact names found. |

Raw command logs and downloaded release assets are in ignored build/reports/foundation/. No real Paper server, SQL backend, Redis backend, player database, or operator config was exercised in T00; runtime and migration gates remain assigned to later plan tasks. T00 does not triage or promote any P0-CANDIDATE; T01 must reproduce or disprove each candidate before priority changes.
