# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- /syncmoney migrate stop now requests a safe-boundary pause for LOCAL-to-SYNC as well as CMI. An in-progress atomic transaction-history SQL import is checked after it returns; the reply confirms request acceptance, not completion. `/syncmoney migrate clear` refuses to delete the checkpoint while either migration is running or draining.
- Administrative give/take no longer synchronously loads a cold balance; a failed take reports N/A when no in-memory balance is available.
- Non-force LOCAL-to-SYNC migration now uses an insert-only shared-row check for MySQL/MariaDB. Existing shared rows are preserved and are not copied into Redis; use the explicit force option to replace one.
- LOCAL-to-SYNC migration rejects raw negative SQLite balances before normalization, including negative sub-cent values that would otherwise round to 0.00, and rejects normalized balances above the shared `DECIMAL(20,2)` ceiling before preparing insert-only or forced SQL. MySQL `INSERT IGNORE` cannot silently reduce a high row to its maximum; rejected rows remain unchanged in LOCAL and are not copied to Redis.
- LOCAL-to-SYNC migration now requires standard 36-character UUID text; abbreviated UUID strings accepted by Java's parser fail the source snapshot before any shared write.
- CMI Redis mirror writes now atomically compare versions, reject stale/conflicting or incomplete mirror state, and prevent rejected outbound updates from being published or rejected inbound updates from scheduling CMI mutations.

- Redis mutations with a missing Lua script SHA are classified as `NOT_SENT` before any balance command; recovery keeps the account retryable instead of quarantining it as ambiguous.
- Node proxy requests now accept safe `/api` path-only targets on the configured node origin and reject absolute URLs, authorities, fragments, and multiply encoded traversal paths.
- Scoped node-view keys can no longer use the remote node's configured API key for state-changing proxy methods; writes require the node-manage or central permission.
- Proxy calls now require the caller's permission for the target API path; unmapped paths require central scope. Structured request bodies are forwarded as JSON, request bodies over 1 MiB receive HTTP 413 before parsing or node-key use, and upstream responses are capped at 8 MiB with HTTP 502 on overflow.
- Public `CacheManager` construction now disables paired transfers by default, and VaultUnlocked transfers fail closed in CMI mode before balance reads, including zero-value transfers.
- Queued `/pay` transfers now persist a forced paired-admission checkpoint before queue commit. Ambiguous transfers remain quarantined across restart and are visible to admins through `/syncmoney recovery list|inspect|reconcile <id>`; reconciliation requires the exact stable-ID Redis receipt. Direct Vault/plugin recovery remains deferred under ADR-07.
- `GET /api/nodes` now reads the same status cache updated by periodic node health checks.
- Failed node health checks no longer log configured URLs or exception messages; node-status SSE URL values strip URI user-info before broadcast.
- Node URL displays in node list, status, management responses, and node-status SSE omit URI user-info, query, and fragment; creation and update reject URLs containing those components.

### Security

- Web Admin CORS now parses list and comma-separated scalar origins and returns headers only for an exact allowed origin or an explicitly configured wildcard. Node URL validation rejects local/reserved address classes including IPv6 ULA at validation time. DNS-rebinding resistance and WebDownloader transfer/extraction resource limits remain `DEFERRED / NOT VERIFIED`.
- Node proxy methods and target-path permissions are checked before the remote node key is used; oversized proxy bodies are rejected before parsing or credential use.

### Candidate verification record (unpublished)

### Historical R53 R5 candidate verification (unpublished; superseded for current readiness)

R53 R5 Java 25.0.4 / Gradle 9.8.0 source/package gate: 68/68 actionable tasks; 167 unit suites / 932 tests and 10 Sync integration suites / 42 tests (177 suites / 974 tests), with zero failures, errors, or skips. Architecture and classfile-major-69 packaging, API-consumer and acceptance packaging, Central publisher isolation, and NMCP aggregation passed. Six targeted regression classes passed 39 tests. Core JAR: 31,614,140 bytes; SHA-256 `D2DD4DC274946DDA0B4D3E475241E67C9726674E17A7F52E13E282E8720D7FC3`. The build log is 10,406 bytes; SHA-256 `1DC9AC0B42BDCB3055CE7CF493B7BAE4F82005BB4A6C614CA6B96A69F3706B5C`. The other artifact pins are recorded in Proposal 06. Frontend checks remain applicable because their source inputs were unchanged. Storage-service integration was not run. Exact-candidate runtime and full T22 Beta remain NOT VERIFIED; overall readiness remains BLOCKED / NOT VERIFIED. Stable metadata remains 1.3.2; no external release action occurred.

### Current 2026-10-26 R63 source/package disposition (unpublished)

The user authorized a durable recovery architecture amendment and narrowed ADR-16 to live queued /pay recovery only. The R9 Java 25.0.4 / Gradle 9.8.0 source/package gate passed 59 actionable tasks (59 executed): 170 unit suites / 945 tests plus 9 Sync integration suites / 44 tests (179 suites / 989 tests total), with zero failures, errors, or skips. Architecture, packaging (classfile major 69), API-consumer/acceptance packaging, Central publisher isolation, and frontend typecheck, 4 unit files / 22 tests, and production build passed. Current core JAR: 31,646,522 bytes / SHA-256 BB8C014ADB37CD407AEEED93BDDE16EBE13BA64272D0511F26155DB7EF3B1916; PAPI JAR: 13,872 bytes / SHA-256 A4D893AC4892E9E715A1710A4760D7FB168EE84A7987BC43D1BAEA86EC6C319C; acceptance JAR: 54,905 bytes / SHA-256 29C41C996026B6275AD9276587D05E800759C710E4B485E5891CF1F85C281824. The production queued /pay integration test uses isolated Redis. Direct Vault/plugin recovery remains DEFERRED / NOT VERIFIED under ADR-07. Exact-candidate Paper runtime/T22 Beta, storage-service integration, minimum-Paper SQL, licensed/live CMI (authorization UNKNOWN), broad V03, full public API compatibility, and T21 completion remain NOT VERIFIED or BLOCKED; Stable and external release remain NOT VERIFIED / NOT PERFORMED. Stable metadata remains 1.3.2; no external release action occurred.

### Historical 2026-10-26 R62 test and gate record (superseded by R63)

R62 `:syncmoney-sync:integrationTest` executed 44 tests: 41 passed, 3 failed, 0 errors, and 0 skipped. The two raw Lua receipt-fixture classes passed all 23 tests. All three failures were in `PayLuaScriptManagerNoscriptIT`, which had no production caller; that task did not exercise or establish a failure in live `/pay`. `verifyArchitecture` and `verifyPackaging` with `-PreleaseVersion=1.4.0` passed; storage-service integration was not run. Four on-disk artifact hashes were rechecked, but no current source manifest bound them to the dirty candidate. These observations are R62 history only and do not qualify the R63 candidate.
### Historical 2026-10-26 R59 readiness disposition (superseded by R62)

The user retained ADR-07, deferred direct-transfer recovery repair/integration, and left target main unmerged at stable metadata 1.3.2. R58 focused runs passed 33 tests across four Bukkit test classes; the full syncmoney-bukkit:test task passed 134 JUnit suites / 771 tests with zero failures, errors, or skips. Two independent read-only R59 reviews approved the complete current four-class test diff. These results do not establish the full source/package gate, current exact-candidate artifact pins, or runtime/Beta acceptance. Overall readiness remains BLOCKED / NOT VERIFIED. No runtime, merge, or external release action occurred.
### Historical 2026-10-26 R57 readiness disposition (superseded by R59)

The user reaffirmed ADR-07, deferred direct-transfer recovery repair/integration, and left local `main` unmerged at stable metadata `1.3.2`. The current R56 `:syncmoney-bukkit:test` run completed 771 tests with 6 failures. A read-only audit suggests the failing tests use stale operation-ID/receipt mocks; this remains unverified because no fixture was repaired or rerun. There is no current complete source/package gate or artifact pin. Exact-candidate runtime and T22 Beta remain `NOT VERIFIED`; overall readiness remains `BLOCKED / NOT VERIFIED`. No runtime, merge, or external release action occurred.

### Historical 2026-10-26 R54 disposition (superseded by R56/R57/R59)

The user chose to retain ADR-07, not adopt ADR-15, defer the direct Vault/plugin-transfer recovery repair/integration, and leave local `main` unmerged at 1.3.2. R5 reviews found direct-transfer `SENT_UNKNOWN` quarantine may be lost after restart and no operator-resolution path is verified. An early 05:33 UTC run captured nine test-failure markers without a terminal build summary. The 05:52 UTC Java 25 / Gradle 9.8.0 attempt reported 3 test failures. The latest run completed 201 tests (10 failed, 1 skipped) before Gradle Test Executor 4 failed with Java heap space at `:syncmoney-bukkit:test`. The log shows `:apiConsumerJar`, `:acceptanceJar`, and `:shadowJar` were reached before the test failure. The overall gate failed; Sync integration, architecture, and later verification tasks were not established. Latest log SHA-256: `44C49CDB9176D739E30479A00ED10E933E020FCB3D0EF026FF6B426BC72519E1`. The on-disk candidate JAR is 31,661,116 bytes / SHA-256 `5699B42B709751430F349F338130FE0A88BF76B041D7D566C940086C8400DD53`; no current manifest or exact-candidate artifact pin was generated, so this is not a verified release artifact pin. No exact-candidate runtime or Beta acceptance exists. R53 R5 pins are historical. Overall readiness remains BLOCKED / NOT VERIFIED; stable metadata remains 1.3.2 and no external release action occurred.

### Historical R53 R4 candidate verification (superseded by R5; unpublished)

R53 R4 Java 25.0.4 / Gradle 9.8.0 source/package gate: 68/68 actionable tasks; 167 unit suites / 931 tests and 10 Sync integration suites / 42 tests (177 suites / 973 tests), zero failures/errors/skips. Architecture, classfile-major-69 packaging, API-consumer and acceptance packaging, Central publisher isolation, and NMCP aggregation passed. Core JAR: 31,613,386 bytes; SHA-256 AAE5B297D9553109C9619ECF02CF373EE0444B5C056447817A9DD7A66138C660. Five targeted regressions passed (36 tests total). Frontend typecheck, 4 unit files / 22 tests, production build, and 39-file embedded synchronization passed using logs whose source inputs remain unchanged. Storage-service integration was not run. Exact-candidate runtime and full T22 Beta remain NOT VERIFIED; overall readiness remains BLOCKED / NOT VERIFIED. Stable metadata remains 1.3.2; no external release action occurred. See Proposal 06 for all five artifact pins and evidence-log digests.

### Historical R52R candidate verification (historical; superseded by R53)

R52R Java 25.0.4 / Gradle 9.8.0 source/package gate: 68 actionable tasks, 164 unit suites / 920 tests and 10 Sync integration suites / 42 tests (174 suites / 962 tests), zero failures/errors/skips. Architecture, classfile-major-69 packaging, API-consumer and acceptance packaging, Central publisher isolation, and NMCP aggregation passed. Core JAR: 31,608,675 bytes; SHA-256 4629284db89ea05336c49fb224208d8ccf0f4a8f9bc32c7cd23d201478c2a10a. Frontend typecheck and production build passed; 4 unit files / 22 tests passed; embedded synchronization passed for 39 files. Storage-service integration was not run. Exact-candidate runtime and full T22 Beta remain NOT VERIFIED; overall readiness remains BLOCKED / NOT VERIFIED. Stable metadata remains 1.3.2. Five artifact pins and exact source/package log digest are recorded in Proposal 06.

Historical R52F R14 candidate source/package evidence (superseded by R52R) (2026-10-26; unpublished; source/package only): Java 25 toolchain / Gradle 9.8.0, 68/68 tasks; 164 unit suites / 913 tests and 9 Sync integration suites / 39 tests (952 total), with zero failures, errors, or skips. Architecture verification, classfile-major-69 packaging, API-consumer and acceptance packaging, Central publisher isolation, and NMCP aggregation passed. The core JAR is 31,605,493 bytes, SHA-256 `6c76a79a6b0119609ada212fd732d846bc224e4a5f7e7b5d23aed534f0cefd09`; five artifact pins and the gate digest are in the R14 evidence record and Proposal 06. R11's canonical UUID source-text guard remains in the candidate. The R14 exact-candidate runtime attempt returned no PASS/FAIL marker within 90 seconds; only two synthetic fixture rows were confirmed, and no restart-read was attempted. Exact-candidate runtime acceptance and full T22 Beta remain NOT VERIFIED; the historical R9 M04 result uses a different JAR. Storage-service integration remains NOT VERIFIED; T21 remains PARTIAL / IN_PROGRESS; broad V03 remains SKIPPED / NOT VERIFIED; minimum-Paper SQL initial schema and licensed/live CMI remain BLOCKED / NOT VERIFIED (CMI authorization UNKNOWN); full public API compatibility remains NOT VERIFIED. Stable metadata remains 1.3.2; no external release action occurred.

Historical R52F r6 1.4.0 candidate source/package evidence (2026-10-25; unpublished; source/package only): Java 25.0.4 toolchain / Gradle 9.8.0 (launcher JVM 21.0.6), 68/68 tasks; 163 unit suites / 909 tests and 9 Sync integration suites / 39 tests (948 total), zero failures/errors/skips. The migration reader rejects raw negative SQLite balances before rounding; positive sub-cent normalization remains intact. Five artifact pins and final gate log digest are recorded in Proposal 06. Storage-service integration, exact-candidate runtime, and full T22 Beta remain NOT VERIFIED. Stable metadata remains 1.3.2; no external release action occurred.
Historical R52F r5 source/package snapshot (superseded by the negative sub-cent correction and r6; 2026-10-25; unpublished): 68/68 tasks, 163 unit suites / 908 tests, 9 Sync integration suites / 39 tests, and zero failures/errors/skips. It did not cover the -0.004 migration case; its artifact pins are not current.
Historical R52F r4 source/package snapshot (superseded by r5; 2026-10-25; unpublished): the r4 gate also reported 68/68 tasks and 163 unit suites / 908 tests plus 9 Sync integration suites / 39 tests (947 total), all clean. Its core JAR matched r5 at 31,601,861 bytes / SHA-256 `46A593BBEC73FE7BA8759ED822EF1734ED6704CAD1688A1BF61150906FDE2DD4`; its gate log SHA-256 was `7A2A506DC4CD0FFF24F47FD81B320F1429E2B379BFCBF4F96CBE469385E25466`.
Historical R52F r3 snapshot (superseded by r4; 2026-10-25; unpublished): the r3 source/package gate had 163 unit suites / 906 tests and 9 fresh Sync integration suites / 39 tests (945 total), with zero failures, errors, or skips. The older r3 summary JSON counted a stale `syncmoney-storage` integration XML and is retained as an incorrect intermediate inventory; it is excluded from these corrected totals. Core JAR: 31,601,746 bytes, SHA-256 `7417CAF6071DDB3033EA8603FB314EB9E0F789A0DCF6784ABCB6B6A554759CC8`. Its bounded M04 runtime result is historical and does not verify r4.

Historical R52C local candidate snapshot (superseded by R52D) (2026-10-25; unpublished; source/package evidence only): JDK 25.0.4 / Gradle 9.8.0 full gate completed 58 actionable tasks (11 executed, 47 up-to-date), with 162 unit suites / 899 tests and 8 Sync integration suites / 38 tests; failures, errors, and skips were zero. Architecture verification and classfile-major-69 packaging passed. A separate 15-task package check passed API-consumer packaging, Central publisher isolation, and NMCP aggregation. The earlier R52B full gate attempt failed 7 tests and is retained; after correcting the failing regressions and adjusting the test fixtures/assertions, this R52C gate passed. The full test inventory includes RedisMissingAccountMutationIT 3/3 and LocalToSyncTransactionHistoryAtomicityTest 1/1. Core JAR: 31,601,520 bytes, SHA-256 FD06656233434508FE967B7373C74F1298EFAE8445AFCACA4A46A1E7CB154D60. Exact-candidate runtime and full T22 Beta remain NOT VERIFIED; storage-service integration is DEFERRED / NOT VERIFIED. Stable metadata remains 1.3.2. No runtime or external release action occurred. The previous R52 pin is historical and superseded; see Proposal 06 for all current artifact pins.
R52D local candidate snapshot (2026-10-25; unpublished; source/package evidence only): Java 25.0.4 toolchain / Gradle 9.8.0 full source/package gate completed 68 actionable tasks, all executed; 163 unit suites / 901 tests and 9 Sync integration suites / 39 tests passed with zero failures, errors, or skips. The MySQL migration duplicate regression and isolated real-Redis Consumer-to-CacheManager authority-recovery regression passed using a synthetic strict SQL fixture at 900.00/version 41. Architecture verification, classfile-major-69 packaging, API-consumer packaging, Central publisher isolation, and NMCP aggregation passed. Core JAR: 31,601,526 bytes, SHA-256 FD06DE8DF79B5D16714A10603DB04525F25171761618C74733B8CCE17E8DAF9E. The optional MySQL/MariaDB storage-service integration was not run and remains NOT VERIFIED; exact-candidate runtime and full T22 Beta remain NOT VERIFIED; T21 remains PARTIAL / IN_PROGRESS; broad V03 remains SKIPPED / NOT VERIFIED; minimum-Paper SQL and licensed/live CMI remain BLOCKED / NOT VERIFIED; full public API compatibility remains NOT VERIFIED; Stable and external release remain NOT VERIFIED / NOT PERFORMED. Stable metadata remains 1.3.2; no runtime or external release action occurred.

Historical R51 local candidate snapshot (2026-10-25; superseded by R52; unpublished; these pins bind only the R51 source): JDK 25.0.4 / Gradle 9.8.0 full source/package gate passed 68/68 tasks (all executed), with 159 unit suites / 887 tests and 7 Sync integration suites / 35 tests; failures, errors, and skips were zero. Architecture, classfile-major-69 packaging, Central publisher isolation, acceptance/API consumer packaging, and local NMCP aggregation passed. Failed node health checks no longer log configured URLs or exception messages; node-status SSE URLs strip URI user-info. Core JAR: 31,597,492 bytes, SHA-256 BEDDD3F2F62961E98B3F21A600544FD2F3FF140725518062A15DD9300FCDAC46. Exact-candidate runtime and full T22 Beta remain NOT VERIFIED; storage-service integration is DEFERRED / NOT VERIFIED. Stable metadata remains 1.3.2. No runtime or external release action occurred.
Historical pre-R52C R52 local candidate snapshot (2026-10-25; superseded by R52C; unpublished; source/package evidence only): JDK 25.0.4 / Gradle 9.8.0 gate passed 68 actionable tasks (14 executed, 54 up-to-date), with 159 unit suites / 892 tests and 7 Sync integration suites / 35 tests, zero failures, errors, or skips. Core JAR was 31,597,376 bytes, SHA-256 8295A8B9D0CB0E17D5CCBAED07B4163949AF4AD48947EE1D00A0C75EB137F8E4. These pins bind only the earlier R52 source/package snapshot and do not apply to R52C or R52D. Runtime/T22 Beta remained NOT VERIFIED; storage-service integration remained DEFERRED / NOT VERIFIED.

Historical R50 local candidate snapshot (superseded by R51; 2026-10-25; unpublished): JDK 25.0.4 / Gradle 9.8.0 full source/package gate passed 68/68 tasks (all executed), with 157 unit suites / 884 tests and 7 Sync integration suites / 35 tests; failures, errors, and skips were zero. Architecture, packaging, Central publisher isolation, acceptance/API consumer packaging, and local NMCP aggregation passed. Core JAR: 31,596,440 bytes, SHA-256 F493F561429D7AA84B6D32F28D3D722108366F4F83D8A05EC939A1E701818ACC. Node management responses redact URI user-info from legacy configured URLs; invalid URLs are labeled, and configuration validation rejects new URLs containing user-info. Exact-candidate runtime and full T22 Beta remain NOT VERIFIED; storage-service integration is DEFERRED / NOT VERIFIED. Stable metadata remains 1.3.2. No external staging, signing, upload, or publication occurred.
Historical R49 local candidate snapshot (superseded by R50; 2026-10-25; unpublished): JDK 25.0.4 / Gradle 9.8.0 source/package gate passed 68 actionable tasks (10 executed, 58 up-to-date), with 157 unit suites / 864 tests and 7 Sync integration suites / 35 tests; all test failures, errors, and skips were zero. Architecture, packaging, Central publisher isolation, acceptance/API consumer packaging, and NMCP aggregation passed. Core JAR: 31,593,700 bytes, SHA-256 E1564BADC993B9792D963806BC5D878CE8FDAAADBC7227765F3A7970CF4C3CA7. Exact-candidate runtime and full T22 Beta remain NOT VERIFIED; storage-service integration is DEFERRED / NOT VERIFIED. Stable metadata remained 1.3.2.

Historical R46 source/package snapshot (2026-10-25; superseded by the follow-up fixes below): the Java 25.0.4 / Gradle 9.8.0 gate passed 68/68 tasks (14 executed, 54 up-to-date), with 157 unit suites / 854 tests and 7 Sync integration suites / 35 tests. Its core JAR was 31,591,955 bytes, SHA-256 FE754399BCB0F625AEA005D5CF1511AD7CB8B7618AF6F4816D2839A4A8547094. Later candidate source changes invalidate those pins and reviews. R39 runtime evidence does not transfer to later candidates; exact-candidate runtime and full T22 Beta remain NOT VERIFIED. Storage-service integration remains DEFERRED / NOT VERIFIED; minimum-Paper SQL and licensed/live CMI remain BLOCKED / NOT VERIFIED. Stable metadata remains 1.3.2.
Historical R45 source/package snapshot (2026-10-25): the Java 25 / Gradle 9.8.0 gate passed 68/68 tasks, with 157 unit suites / 851 tests and 7 Sync integration suites / 35 tests, zero failures/errors/skips. The R45 core JAR SHA-256 is C12F52AAA5EE3F03E8582CB706703495B11D34564485FF4EED95BF880E83129A. No R45 exact-candidate runtime acceptance was run. R39's bounded runtime result does not transfer to R46. Storage-service integration remains DEFERRED / NOT VERIFIED, and full T22 Beta remains NOT VERIFIED.

## [1.4.0-alpha.3] - Unpublished preview

This is an experimental, unpublished engineering candidate for Java 25+ and Paper 1.21.6+. It is not a stable release. ADR-03 freezes only the T11 read/query SDK; public Maven resolution and third-party adoption remain unverified. The 1.4.0 verified support scope is evidence-bounded: full CMI mode, minimum-Paper MySQL/MariaDB initial-schema cases, and several mode/resource/failure combinations are not verified. Do not use this candidate as a production compatibility claim.

The R39 source/package gate passed 68/68 tasks, 155 unit suites / 834 tests, and 7 Sync integration suites / 35 tests with zero failures, errors, or skips. The R39 core JAR is 31,587,551 bytes, SHA-256 63E25A450FA38F0AE57B535825617515979DBD6257BA7059629C48B21D80DE19. One exact-candidate Paper 1.21.6#48 / Java 25.0.4+7-LTS-189 / LOCAL synthetic-account runtime subcase recorded API classloader pass, acceptance total 1520.00, persisted restart read A=1000.00 / B=520.00 / VaultUnlocked B=520.00, and graceful final stop. It is PASS_BOUNDED_SUBCASE only; full T22 Beta remains NOT VERIFIED. Earlier r24 JAR 390499303A2FD2F065BA7E0535768C61A297514148B2F3C600934DB57391501E and R29 JAR 4BE022B1760209D610A390144D5B3328A094E290C2AB53A9E557BDF27FAA92F6 results remain historical and do not apply to R39. Storage-service integration is DEFERRED / NOT VERIFIED; broader platform/mode and licensed/live CMI coverage remains NOT VERIFIED.

The matched synthetic facade/receipt-adapter benchmark did not meet all original B00 thresholds (throughput 27/48, allocation 12/48, and SampleTime p99 18/48 cases met threshold). Under ADR-11, the measured regression is accepted only for that bounded synthetic profile; thresholds and correctness hard gates are unchanged. This is not a production, SQL, Redis, or real-server capacity claim.

### Security

- Web Admin now disables itself for both shipped API-key placeholders (including blank values), without preventing the economy plugin from starting.
- TAR web extraction rejects path components ending in a dot or space, which Windows can normalize into a different path.
- The deferred `/ws` endpoint always returns `503 WEBSOCKET_UNAVAILABLE`; it no longer sends a fake WebSocket upgrade response.
- SSE session-token consumption is atomic, and missing or invalid credentials receive `401` instead of opening an unauthenticated stream.

### Changed

- Undertow routes dispatch from I/O threads to worker threads before running handlers.

### Fixed

- CMI mode now rejects `/syncmoney admin` and `/syncmoney sync-balance` before reading or changing Syncmoney balances, because these command paths do not update CMI authority.
- Syncmoney `/pay` is disabled in CMI mode because the CMI adapter does not expose an atomic two-account transfer contract; rejection occurs before balance mutation.
- CMI mode rejects Vault's explicit paired-transfer routes, VaultUnlocked transfer delegates, and the public low-level Redis paired-transfer API before Syncmoney mutation; this does not establish licensed/live CMI acceptance.
- Shared `LOCAL_REDIS` and `SYNC` writes now reject projected balances above `999999999999999999.99` before acceptance and recheck the limit under the account lock. The existing `LOCAL` policy is unchanged.
- Upgrade note: 1.4.0 does not migrate Redis balances written under an earlier, wider policy. Check existing shared-mode data before upgrading; deposits, withdrawals, and transfers reject an account whose stored balance exceeds the new ceiling before memory mutation. An admin set is a deliberate operator balance change, not an automatic migration.

## [1.3.2] - 2026-09-20

### Security

- **Frontend Dependency Remediation**: Updated the Web Admin dependency graph to patched Axios, Vite 6, Vitest 4.1.11, Happy DOM, PostCSS, and Sharp releases. Targeted pnpm overrides keep vulnerable transitive packages on patched versions without taking unrelated Java/runtime major upgrades.
- **Audit Baseline**: The resolved Web Admin dependency graph now reports zero known vulnerabilities with `pnpm audit`.

### Changed

- **Release Metadata**: Unified root, PlaceholderAPI, Web Admin, documentation, configuration metadata, and acceptance version checks on `1.3.2`.
- **Embedded Web Bundle**: Rebuilt the embedded Web Admin assets and synchronized `WebAdminServer.extractIndividualFiles` with the generated bundle.
- **Release Automation**: Added GitHub Actions workflows for CI artifacts, alpha/beta previews, protected stable releases, provenance attestations, and the synchronized `Syncmoney-web` release.
- **Web Release Contract**: The Web repository now publishes `v`-prefixed deterministic source archives; the core downloader accepts both prefixed and unprefixed Web release tags.

### Validation

- PlugDev acceptance passed on Paper 1.20.4, Paper 26.2, Folia `26.2.build.7-beta`, Canvas 26.2, and a two-backend Paper 26.2 network. Folia coverage is specifically for the tested beta build.

## [1.3.1] - 2026-09-09

### Fixed

- **Adventure Runtime Compatibility**: Adventure and MiniMessage are now supplied by the server runtime instead of being partially relocated into the plugin JAR. This prevents the Adventure 4.x/5.x `ClickEvent` binary mismatch on Canvas 26.2 while retaining Paper 1.20.4 compatibility.
- **Message Safety**: Interactive MiniMessage tags are removed while colors and decorations remain available; message parsing now falls back safely on parse/linkage failures.

### Added

- **Support Reports**: Added `/syncmoney version`, `/syncmoney version full`, and `/syncmoney version save`. Full reports collect sanitized server, Java, dependency, module, Redis, and database diagnostics without exposing credentials or endpoints. Redis and database probes are read-only, run asynchronously, time out after three seconds, and report three latency samples.

## [1.3.0] - 2026-09-06

### Fixed

#### Scheduler Correctness (Folia / Paper / Canvas)
- **Entity Scheduler Routing**: All per-player work — CMI updates, payment notifications, breaker alerts, CMI Pub/Sub callbacks — now runs on the owning player's entity scheduler. Global and plugin-level operations use the global region scheduler. Legacy `BukkitScheduler` calls have been removed throughout.
- **Teleport Safety**: `PlayerTransferGuard` rewrites the deferred-teleport loop to use `player.getScheduler().runAtFixedRate()` and `teleportAsync()`. A newer teleport supersedes any stale deferred destination immediately. Pending economic writes are never silently discarded on logout or kick; only the deferred teleport task is cancelled.
- **Placeholder Hot Path**: `EconomyFacade.getBalanceForPlaceholder()` and `NameResolver.resolveUUIDForPlaceholder()` check in-memory state first and warm the cache asynchronously on a miss. PlaceholderAPI callers on any region thread never block on a cold Redis, database, or Mojang lookup.
- **Audit Cleanup Interval**: `AuditLogCleanup` was scheduling its repeating task with a tick value where hours were expected, causing the cleanup to fire every ~1 second instead of the configured interval. The task now uses `AsyncScheduler.runAtFixedRate()` with `TimeUnit.HOURS`. Cleanup and export tasks hold cancellable handles and stop cleanly on shutdown.

#### Module Lifecycle & Reload
- **Single Guard Owner**: `BreakerManager` is the sole owner of `PlayerTransactionGuard`. The duplicate construction path inside `EconomyServiceManager` has been removed.
- **Gated Optional Resources**: Breaker, Shadow, audit, and notifier modules check configuration before constructing executors, schedules, or subscriptions. Each failed or disabled module leaves the others unaffected.
- **Restart-Required Reload Gate**: `ConfigReloadPolicy` rejects changes to storage, connection, or service settings before the runtime snapshot is published. Only display, command, and permission keys are live-reloadable. A plain `/syncmoney reload` no longer tears down cross-server notifications.
- **Disabled Command Messages**: Commands for disabled modules (`audit`, `shadow`, `breaker`) return an explanatory message instead of throwing a null-pointer exception.
- **`OnlinePlayerRegistry` Reload**: The Redis subscription now closes cleanly during a reload without dropping the cross-server notification channel.

#### Web Admin
- **SSE Reconnect Race**: Token-refresh callbacks in `useSSE.ts` no longer attempt to reopen a session after `disconnect()` has been called.
- **`WebAdminServer` Resource Leaks**: Exception handling improved in the server lifecycle; the SSE session map is cleared on stop, preventing zombie callbacks from writing into closed channels.
- **Adventure / MiniMessage Linkage**: The Adventure dependency is pinned to `4.16.0`, matching the Paper 1.20.4 bundle. This prevents a `ShadowColorTag` `NoClassDefFoundError` at runtime.
- **Web UI Token Inconsistencies**: Color token and CSS variable mismatches corrected across `Button`, `Card`, `Input`, `Select`, `Switch`, `Sidebar`, and `Header` components and related views.

### Changed

#### Toolchain & Version Metadata
- Java 21 API and bytecode baseline; Gradle wrapper updated to 9.1 (supports running on Java 25 for newer server environments).
- `BuildVersion` reads version from a build-time-expanded `syncmoney-version.properties` resource, replacing the runtime `plugin.getDescription()` call.
- `ServerPlatformDetector` adds Canvas detection alongside Paper and Folia.
- PAPI expansion version is now derived from the root Gradle version; no more manual syncing.
- Core, PAPI, and web frontend metadata unified at 1.3.0.

#### Cleanup
- `WebModuleConfig` removed; its functionality was already covered by `WebAdminConfig`. The duplicate `EconomyFacade` construction it guarded is also gone. Reflection-facing PAPI wrappers are unchanged.

#### Web Admin Frontend
- `Badge`, `Button`, `Card`, `Input`, `Select`, `Switch`, `Header`, `Sidebar` components refactored for consistency.
- `ConfigView`, `LoginView`, `SettingsView`, `SystemStatusView`, `AuditLogFilters` views updated.
- Added `config` MSW mock handler for offline development and frontend unit tests.
- Frontend dist rebuilt at 1.3.0.

### Added

- **PlugDev Acceptance Tooling**: `tools/plugdev/` scripts and config for running an isolated multi-server development network. `AcceptanceProbe` is a standalone plugin triggered via RCON that exercises the full `EconomyFacade` API end-to-end.
- **New Unit Tests**: `ConfigReloadPolicyTest`, `PlayerTransferGuardTest`, `ConsumerShutdownTest`, `PlayerLookupUtilTest`, `DisabledAuditTest`, `DisabledBreakerTest`, `ResourceMonitorTest`, `RedisOnlyBaltopTest`.

### Validated Acceptance Matrix

Paper 1.20.4, Paper 26.2, Folia 26.2 BETA, Canvas 26.2 — multi-backend (PostgreSQL + Redis), two-server network, all unit and integration tests passing.

### Previously Unlisted 1.2.x Changes

- **1.2.1** (`1f816df`): dependency updates, PAPI formatting/reflection enhancements, schema identifier fixes, independent expansion packaging.
- **1.2.2** (`cb641ad`): VaultUnlocked/Vault2 runtime detection, isolated integration loader, provider/registrar and compatibility tests.
- **1.2.3** (`10d914b`): Vault provider/transfer handling consolidation and redundant logic removal.

These entries summarize tagged Git history and are not newly reimplemented features.

## [1.2.0] - 2026-06-26

### Added

#### Cross-Server Online Players
- **Online Player Registry**: New `OnlinePlayerRegistry` tracks online players across servers via Redis heartbeat, powering command tab completion.

#### CMI Sync Architecture
- **CMI API Layer**: New `CMIApi`, `CMIPubsubHandler`, and `CMIVersioning` with dedicated Pub/Sub channels for CMI mode cross-server sync.

#### Core Refactoring
- **Economy Core Split**: Extracted `MemoryStateManager`, `TransactionWriter`, and `TransferOrchestrator` from `EconomyFacade`.
- **Circuit Breaker Lock Reasons**: Added `LockReason` enum to replace string-based lock cause matching.
- **Web Backend Modularization**: Introduced `RouteRegistry`, `StaticFileHandler`, `CorsHandler`, and `AbstractApiHandler`.
- **Audit Log Frontend**: Refactored audit page into `useAuditData` / `useAuditExport` composables and sub-components.
- **Test Coverage**: Expanded unit tests for CMI versioning, debounce, Vault, Web API, and PAPI expansion.

### Fixed

#### CMI Mode Cross-Server Sync
- **Authority & Publishing**: Rewrote CMI sync to use CMI API as the local authority; publishes absolute balances with legacy timestamp-derived versions instead of runtime CMI database queries. These versions are not globally monotonic across clock rollback, restart, or multiple servers.
- **Echo Loop Prevention**: Added `suppressOutbound` to stop re-publish loops when applying remote balances into local CMI.
- **Join Reconcile**: Version-aware balance reconcile on player join to fix cross-server balance drift.
- **Event vs Polling**: Disables polling when CMI events are available, preventing duplicate publishes during rapid `/cmi pay` spam.
- **Vault Transfer Publish**: Vault transfers in CMI mode now correctly publish via the CMI channel (`FIX-CMI-VAULT-PUBLISH`).
- **Cross-Server Pay Notification**: Incoming payment notifications now display the payer's name.

#### Scheduler & Folia
- **Periodic Version Check**: Fixed `GlobalRegionScheduler` interval to use correct tick units (actually runs every 5 minutes).
- **Folia Compatibility**: CMI polling and Redis I/O moved to Async / Global Region schedulers.

### Changed

#### Command Tab Completion
- **Cross-Server Player Suggestions**: `/pay`, `/money`, `/syncmoney admin`, and related commands now suggest all online players across the network (local + cross-server via Redis); `/pay` no longer suggests offline cached names.

#### Miscellaneous
- **PAPI Expansion**: Merged reflection utilities into `PlaceholderHandler`, reducing class count.
- **Discord Webhook**: Simplified payload construction using Jackson.
- **Web Admin**: Bundled frontend version bumped to 1.2.0.

---

## [1.1.3] - 2026-04-03

### Fixed

#### Folia + Paper Cross-Server Environment
- **Orphan VAULT_DEPOSIT Log Spam**: Reduced log level from WARNING to FINE and batched summary output (every 100 events) to prevent console spam in cross-server setups.
- **Player Transfer Block**: Fixed players getting permanently stuck during teleport when pending economic events existed. Transfer now forces after timeout and clears tracking state to prevent deadlock.

#### Write Queue & Overflow Handling
- **Backpressure Threshold**: Lowered from 80% to 70% for earlier rejection of new events under heavy load.
- **Overflow WAL Recovery**: Added `replayOverflowEvents()` on startup to recover dropped events from Write-Ahead Log.
- **DB Fallback**: Added direct DB write fallback when `DbWriteQueue` is full.

---

## [1.1.2] - 2026-03-22

### Fixed

#### Vault Economy Provider
- **Null Check**: `VaultPluginDetector` adds config null check to prevent NPE when config is not loaded.
- **Orphan Deposit Recovery**: `VaultProviderCore` adds orphan deposit recovery mechanism, automatically converting high-frequency transaction failures to PLUGIN_DEPOSIT to prevent money loss.

#### Configuration
- **Backward Compatibility**: `PlayerProtectionConfig` supports dual path reading (`circuit-breaker.player-protection.*` and `player-protection.*`) for backward compatibility during upgrades.

---

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
- **Real-Time Communications**: Added initial WebSocket scaffolding and event payload hooks alongside Server-Sent Events (SSE). Full WebSocket transport was not completed; `/ws` currently returns `503 WEBSOCKET_UNAVAILABLE`, and SSE is the available live channel.

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

Historical R52F r8 1.4.0 candidate source/package evidence (superseded by r9) (2026-10-25; unpublished): Java 25.0.4 toolchain / Gradle 9.8.0 (launcher JVM 21.0.6), 68/68 tasks; 163 unit suites / 909 tests and 9 Sync integration suites / 39 tests (948 total), zero failures/errors/skips. The negative sub-cent migration regression rejects raw -0.004 before rounding; positive 1.234 still normalizes to 1.23. Frontend typecheck and production build passed, 22 unit tests passed, and embedded build completed with Corepack pnpm 10.22.0. The current five artifact pins and gate log digest are recorded in Proposal 06. Storage-service integration and exact-candidate runtime/T22 Beta remain NOT VERIFIED; Stable metadata remains 1.3.2 and no external release action occurred.

Historical R52F r9 unpublished candidate source/package evidence (superseded by r10; 2026-10-25): Java 25.0.4 toolchain / Gradle 9.8.0, 68/68 tasks, 164 unit suites / 911 tests and 9 Sync integration suites / 39 tests (950 total), zero failures/errors/skips. Architecture, classfile-major-69 packaging, API-consumer packaging, Central publisher isolation, and NMCP aggregation passed. The r9 core JAR was 31,604,354 bytes, SHA-256 93c20c49fc40f2fa9c7cc9c716c1882c88451efe64c12b31617573d3292d84fd; its five artifact pins and gate/test evidence hashes are in Proposal 06. That exact r9 JAR passed one bounded T21 M04 runtime subcase using Paper 1.21.6#48, Java 25.0.4, LOCAL, guard-on, normal-drain, and synthetic SQLite. Result JSON SHA-256 8bab087aca408487f4e60ec75b6e7b8219a105e368f7c1ccf0774dfbee19db30; the report-specific offline auditor passed 398 assertions. This is PASS_BOUNDED_SUBCASE only and does not verify full LOCAL support or full T22 Beta, nor does it validate r10. Storage-service integration remains NOT VERIFIED; overall release readiness remains BLOCKED / NOT VERIFIED; stable metadata remains 1.3.2; no external release action occurred.

Historical R52F R10 unpublished candidate source/package evidence (2026-10-25): Java 25.0.4 toolchain / Gradle 9.8.0, 68/68 tasks, 164 unit suites / 912 tests and 9 Sync integration suites / 39 tests (951 total), zero failures/errors/skips. Architecture, classfile-major-69 packaging, API-consumer packaging, Central publisher isolation, and NMCP aggregation passed. The R10 core JAR was 31,605,447 bytes, SHA-256 5c4e70294cdc39fa1f21adc312adb99e699e6fd814550bdafc48b6735fefe771; its five pins and gate/test evidence hashes are in Proposal 06. The invalid LOCAL source UUID finding failed the migration snapshot before shared writes and recorded the source row failure; its regression passed in the R10 full gate. No runtime was run for R10, so exact-candidate runtime and full T22 Beta remain NOT VERIFIED. Storage-service integration remains NOT VERIFIED; T21 remains PARTIAL / IN_PROGRESS; broad V03 is SKIPPED / NOT VERIFIED; minimum-Paper SQL initial-schema and licensed/live CMI are BLOCKED / NOT VERIFIED (CMI authorization UNKNOWN); full public API compatibility remains NOT VERIFIED. Overall release readiness remains BLOCKED / NOT VERIFIED; stable metadata remains 1.3.2; no external release action occurred.
