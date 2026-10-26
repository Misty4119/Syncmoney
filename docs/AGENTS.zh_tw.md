# AGENTS.md — Syncmoney

本文件是本儲存庫中程式代理與自動化貢獻者的操作指南。實作與可執行設定是最高事實來源；若文件與程式不一致，先以程式驗證行為，並在同一變更更新文件。

英文原文：[`AGENTS.md`](../AGENTS.md)

## 專案範圍與事實來源

Syncmoney 是 Minecraft 經濟插件，提供 Vault-compatible economy、選用 VaultUnlocked/CMI、Redis 跨服 balance synchronization、relational persistence、audit/guard、embedded Web Admin 與 PlaceholderAPI expansion；不負責同步任意 permission 或第三方 command。

root `build.gradle` release metadata 目前仍為 `1.3.2`，待 release task 更新；config schema 為 `12`。1.4.0 foundation target 使用 Java 25 與 Gradle 9.8.0；保留的 1.3.2 baseline 使用 Java 21 與 Gradle 9.1.0。這些是不同版本領域。

**截至 2026-10-26 R63 R9，1.4.0 整體狀態為 BLOCKED / NOT VERIFIED。** 使用本機 `-PreleaseVersion=1.4.0` override，Java 25.0.4／Gradle 9.8.0 source/package gate 通過 59 個 actionable tasks（59 executed）：170 個 unit suites／945 個 tests，以及 9 個 Sync integration suites／44 個 tests（合計 179 suites／989 tests），failures、errors、skips 皆為 0。Architecture、classfile-major-69 packaging、API-consumer／acceptance packaging、Central publisher isolation，以及 frontend typecheck、4 個 unit files／22 個 tests 與 production build 均通過。Core JAR 為 31,646,522 bytes，SHA-256 `BB8C014ADB37CD407AEEED93BDDE16EBE13BA64272D0511F26155DB7EF3B1916`；PAPI JAR 為 13,872 bytes，SHA-256 `A4D893AC4892E9E715A1710A4760D7FB168EE84A7987BC43D1BAEA86EC6C319C`；acceptance JAR 為 54,905 bytes，SHA-256 `29C41C996026B6275AD9276587D05E800759C710E4B485E5891CF1F85C281824`。R63 驗證 live queued `/pay` recovery；ADR-16 不涵蓋 direct Vault/plugin recovery，該項依 ADR-07 維持 `DEFERRED / NOT VERIFIED`。Exact-candidate Paper runtime／T22 Beta、storage-service integration、minimum-Paper SQL、licensed/live CMI（授權 UNKNOWN）、廣泛 V03、完整 public API 相容性與 T21 完成仍為 `NOT VERIFIED` 或 `BLOCKED`；Stable 與外部發布仍為 `NOT VERIFIED / NOT PERFORMED`。Stable metadata 維持 1.3.2；沒有執行外部發布。

- Core：`syncmoney-bukkit/src/main/java/noietime/syncmoney`
- Runtime defaults：`src/main/resources`
- 設定：`src/main/resources/config.yml`
- Descriptor：`src/main/resources/plugin.yml`
- PAPI：`syncmoney-papi-expansion`
- Web：`syncmoney-web`
- Embedded bundle：`src/main/resources/syncmoney-web/dist`
- 公開概覽：`README.md`
- Architecture/API/developer 文件：`docs/`
- Acceptance：`tools/plugdev/README.md`

歷史文件可能落後。API 以 actual handler、`RouteRegistry`、`HttpHandlerRegistry` 為準；設定要把 default YAML、`SyncmoneyConfig`、相關 config object 與 `ConfigReloadPolicy` 一起讀。

## 工作順序

1. 修改前查看 `git status --short --branch`、history、implementation、caller、test、config 與 docs。
2. 保留不相關的 worktree changes，不得為了乾淨工作樹覆蓋 user work。
3. rename/remove class、wrapper、executor、listener、schema、subscription、server 前先追蹤 reflection 使用者與 lifecycle ownership。
4. 碰 Bukkit/Paper entity 前確認 scheduler ownership。
5. 經濟變更先找出 memory state、accepted-write boundary、persistence path、version 與 recovery。
6. 保留本機 `Proposal/` 與 `reference/`。

檢查設定時不要輸出 credentials。

## 經濟不變條件

- 金額使用 `BigDecimal` 與既有 normalization。
- 失敗/拒絕交易不得建立或消滅資金。
- `EconomyFacade` 是 memory-first；Vault hot path、placeholder、tab completion、entity task、player-message path 不得加入 blocking Redis/SQL/Mojang/filesystem/network I/O。
- 遵循 `EconomyFacade`、`MemoryStateManager`、`TransactionWriter`、`TransferOrchestrator` 與 mode router 的 state/version contract。
- 已接受 queued write 要能跨一般 logout/teleport timing，shutdown 時在 dependency 存活期間排空。
- 保留 Redis-backed version ordering 與 Pub/Sub echo suppression。CMI 維持 legacy timestamp-derived version，不保證 global monotonic；延遲 CMI work 仍要在修改 player state 前重新檢查本機已觀察到的 freshness。本機 CMI watermark 上限為 10,000 筆，只會在容量壓力下淘汰最舊項目，不可只因項目年齡而過期。
- insufficient funds、concurrent write、queue saturation、partial persistence failure、recovery 都是 correctness case。
- `LOCAL_REDIS` 與 `SYNC` shared mode 的 projected balance 不得超過 `DECIMAL(20,2)`／Redis 上限 `999999999999999999.99`；接受前與 account mutation lock 內都要檢查。保留既有 `LOCAL` policy，不在這項 shared-backend guard 中 clamp 或 migration local balance。
- 明確執行 LOCAL-to-SYNC migration 時，必須先在 normalization 前拒絕 SQLite 原始負餘額，包括原本會四捨五入成 0.00 的負 sub-cent 值；接著在準備 insert-only 或 force replacement SQL 前，驗證正規化後的餘額不得超過 shared 上限。來源 player UUID 文字必須使用標準 36 字元 8-4-4-4-12 十六進位格式（不區分大小寫）；即使 `UUID.fromString` 接受，也會拒絕縮寫形式。缺失、格式錯誤或非標準 UUID 會在 shared SQL／Redis 寫入前讓整個 snapshot 失敗，並將來源列記錄為失敗。遭拒列不會 clamp 或改動 LOCAL 原值，也不會寫入 Redis。
- 將舊版較寬鬆政策下的 Redis balance 切換至 shared `LOCAL_REDIS`／`SYNC` 前，operator 必須先檢查是否有值超過此上限。1.4.0 不會掃描、clamp 或 migration 這些值；deposit、withdraw、transfer 會在記憶體 mutation 前拒絕超限的既有 account。Admin set 是 operator 明確修改帳戶，不是自動 migration；若 audit 開啟且 delta 超出 SQL `DECIMAL(20,2)`，系統會在 mutation 前拒絕。尚未 reconciliation 的資料不列入已驗證支援範圍。
- live `/pay` 成對轉帳使用既有 `recovery-v1.jsonl` spool。啟動時會先驗證 queued spool 與 legacy overflow log，再開啟 shared-mode write admission；recovery evidence 無法讀取或格式錯誤時會保持 admission 關閉，受影響帳戶則在 pending recovery 解決前維持 unavailable。queue commit 前會先 force 一筆 `RETRYABLE` 成對 admission checkpoint。Redis dispatch 後的 `ATTEMPTING`／`AMBIGUOUS` 結果會在 restart 後持續 quarantine，並可透過限管理員使用的 `/syncmoney recovery list|inspect|reconcile <id>` 查閱（permission `syncmoney.admin.recovery` 或 `syncmoney.admin`）。Reconciliation 以非同步方式執行，只接受 stable operation ID、canonical fingerprint、帳戶配對與版本皆相符的 exact Redis receipt。receipt 缺失、格式錯誤、collision、in-flight、mismatch 或無法讀取時都保留 quarantine；不能只靠 balance snapshot 解決 ambiguous operation，也不會自動重播或提供 force-clear。FileChannel force 支援本機 process restart recovery；不宣稱超出該行為的斷電或儲存裝置耐久性。Direct Vault/plugin atomic-transfer recovery 依 ADR-07 維持 `DEFERRED / NOT VERIFIED`；不包含 direct-transfer journal、capability、provider wiring 或 command recovery path。Exact-candidate Paper runtime acceptance 仍為 NOT VERIFIED。
- 沒有 caller-supplied stable operation ID 的 Redis add/set/transfer 相容性 overload，會使用 300 秒 receipt TTL 限制 key 累積。queued `/pay` recovery path 會提供 stable ID；這些 receipt 不會自動到期，只能在明確完成 recovery acknowledgement 後移除。
- `DbWriteQueue` 會以有界 process-local receipt cache 保留 stable operation ID。相同 account UUID/balance/version snapshot 的 retry 會重用 in-flight 或已成功的 SQL completion；同 ID 不同 snapshot 會 fail-closed。SQL receipt 失敗後可用相同 snapshot 重試。由於 queue cache 有界且只在 process 內，必須保留 SQL snapshot version checks。
- `cmi` mode 由 CMI 維持 authority；CMI player mutation 在 owning entity scheduler 執行。
- CMI mode 會停用 Syncmoney /pay，因為 adapter 沒有原子式雙帳戶 CMI 轉帳契約。必須在餘額 mutation 前拒絕；不可用分開扣款與入帳模擬。
- CMI mode 會拒絕所有 Syncmoney 雙帳戶轉帳入口，包括 Vault 指定目標玩家／plugin transfer、VaultUnlocked 委派呼叫及公開的低階 Redis transfer API。CMI 單帳戶操作仍由 CMI 維持 authority。
- Migration 與 Shadow Sync 是明確 workflow，不是隱式 live-authority switch。

內部 mode 為 `LOCAL`、`LOCAL_REDIS`、`SYNC`、`CMI`；設定也接受 `auto`。

## Storage 與 synchronization

Shared SQL `players` table 儲存 UUID、player name、`DECIMAL(20,2)` balance、monotonic version、last server、updated timestamp。Audit、baltop、local SQLite、schema-version、Shadow 另有各自 workflow。

代表性 Redis contract：`syncmoney:balance:{uuid}`、`syncmoney:version:{uuid}`、`syncmoney:online:players`、`syncmoney:online:player:*`、`syncmoney:baltop`、audit recent/index/dedup 與 bank/bank-owner/bank-version keys。

主要 Pub/Sub channel 是 `syncmoney:balance:update` 與 `syncmoney:cmi:balance:update`。修改 message 時維持 compatibility field、version ordering、source identity 與 echo suppression。

## Scheduler 規則

Syncmoney 使用 Paper common scheduler API，需維持 Paper、Folia、Canvas 安全。`folia-supported: true` 只是 metadata。

- 純 I/O/data：async scheduler 或 owned executor。
- `/syncmoney migrate` 的資料庫、SQLite preview 與 checkpoint 工作使用 Paper async scheduler。Dispatch 前先擷取 command block 位置；玩家回覆走 entity scheduler、command block 回覆走所屬 region、console 回覆走 global scheduler。
- `/syncmoney migrate stop` 會要求正在執行的 CMI 或 LOCAL-to-SYNC 工作在安全邊界暫停。回覆只確認要求已接受；若 transaction-history 原子 SQL 匯入正在執行，LOCAL-to-SYNC 會在該交易返回後才檢查停止要求。
- Plugin/global operation：global region scheduler。
- Player message、CMI mutation、teleport、entity state：player entity scheduler。
- 全域 collection 先 snapshot，再派到各 player owner。
- Async callback 碰 entity 前重新進入正確 owner。
- 使用 `teleportAsync`，保留 destination/cause 並取消過時 deferred request。
- 不得讓 region 互相阻塞等待。

使用既有 player lookup/routing helper，並處理 disconnect/retirement。

## Event semantics

`TransactionWriter` 會在 deposit/withdraw 接受前觸發 `AsyncPreTransactionEvent`，且 cancellation 會生效。`PostTransactionEvent` 在交易結果確定後發出，供 SSE 等 telemetry consumer 使用；`/ws` endpoint 目前仍不可用。

`SyncmoneyEventBus` 是內部 bus，不是 Bukkit event bus；先讀 dispatch 實作再判斷 thread semantics。

## Optional-module lifecycle

- 每個 module 只有一個 owner 負責 create/start/close。
- `BreakerManager` 擁有唯一 `PlayerTransactionGuard`，其他 component 接收它。
- Config 未啟用前不要建立 optional executor、schedule、schema、folder、subscription、HTTP listener。
- 保存 cancellation handle；normal shutdown 與 partial-init failure 都要釋放。
- 關閉一項 resource 不得阻止其他 resource cleanup。
- Audit cleanup/export 依賴 parent audit switch；per-player protection 與 global breaker 是不同開關。
- Disabled module 可保留 lightweight facade，但不得啟動 heavy resource。

目前 shutdown order：web → commands → permissions/listeners/sync → event consumers/drain → economy → audit → breaker → baltop save → storage → event bus cleanup。維持 producer 先停、consumer 排空、dependency 後關。

## 設定與 reload

`SyncmoneyConfig` 是 runtime snapshot。`ConfigReloadPolicy` 目前只允許 `display`、`pay`、`permissions`、`admin-permissions`、`debug` live change。

Connection、authority、scheduler、module owner 變更除非 policy 明確支援，否則需 restart。要在發布新的 runtime snapshot 前明確回報 restart-required change，不要 half-apply config。`server-name` 必須設定；空白會阻止正常運作。

Web Admin 預設 API key 是 `change-me-in-production`。Key 空白或 trim 並忽略大小寫後等於 `change-me` / `change-me-in-production` 時，`WebServiceManager` 會停用 Web Admin；economy plugin 仍正常啟動。要啟用 Web Admin，operator 必須設定非 placeholder key。

## Commands 與 permissions

Top-level command：`/money`、`/pay`、`/baltop`、`/syncmoney`。

`/syncmoney` 註冊 `migrate`、`audit`、`admin`、`web`、`monitor`、`debug`、`sync-balance`、`test`、`reload`、`version`、管理員專用 queued recovery `recovery list|inspect|reconcile`，以及條件式 `breaker`、`shadow`、`econstats`。

重要 permission 包含 `syncmoney.money`、`syncmoney.money.others`、`syncmoney.pay`、`syncmoney.admin` 與 `syncmoney.admin.*`。文件要同時看 router 與 subcommand check 的實際效果。

## Web/API 規則

Backend 是 Undertow。一般 authenticated REST request 使用 `Authorization: Bearer <api-key>`；`/health` 是 health probe。

目前 frontend live stream 為 `/api/sse`。Frontend 會先以 API key 呼叫 `POST /api/auth/ws-token` 取得 session token，再使用該 token 建立 SSE connection。不要恢復舊的 `/sse` 文件。

所有 `/ws` request 都回 `503 WEBSOCKET_UNAVAILABLE`，不得描述為 production live channel。完整 authentication 與 endpoint 行為以 [`API_REFERENCE.zh_tw.md`](API_REFERENCE.zh_tw.md) 為準。

`SystemApiHandler` 與 `NodesApiHandler` 有重複 node GET route；registry 採 replacement semantics，且 `NodesApiHandler` 較晚註冊，因此它生效。Node list、status 與管理 response 會從設定 URL 移除 URI user-info、query 與 fragment，並以 `[invalid URL]` 標示無效 URL；驗證會拒絕新增或更新含有 user-info、query 或 fragment 的 URL。

Node proxy 在 decrypt/send 設定的 remote node key 前，同時檢查 method scope 與 target endpoint permission；未映射的 target path 只允許 central scope。外層 request body 上限為 1 MiB。上游 response 會先完整讀入最多 8 MiB 的有界 buffer，再送出 response；response 超限時回 HTTP 502 且不轉送部分內容。設定的 node URL 會拒絕 user-info、query、fragment 與靜態列出的 local/special-use IPv4 位址範圍，包括 shared、protocol-assignment、documentation、benchmarking、multicast 與 reserved 區段，並拒絕 IPv6 unspecified、loopback、link-local、site-local、multicast 與 ULA 位址。驗證錯誤只記錄例外類型；URL 及其中的 user-info、query 與 fragment 都不會記錄。Structured request body 維持 JSON value。DNS rebinding 防護仍為 DEFERRED / NOT VERIFIED。

API route 變更要同步更新雙語 reference，驗證 auth/CORS/rate-limit/proxy trust/node auth，並避免 Undertow I/O thread 上的 blocking storage work。

## PlaceholderAPI

Expansion identifier 是 `syncmoney`，persistent，以 lazy reflection 連接 core。Placeholder family 包含 balance variants、rank/my-rank、total supply/players、version、online players、`top_<n>` 與 target-player balance variants。

Global/rank work 使用 cache/async；現行 bounded cache 約 5 秒 expiry，最多約 10,000 cached/pending entry，必須維持 non-blocking behavior。

## 驗證

Core/backend：

```powershell
.\gradlew.bat test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar
```

Frontend：

```powershell
cd syncmoney-web
pnpm typecheck
pnpm test:unit --run
pnpm build
```

1.4.0 target 編譯 toolchain 與 server runtime 最低為 Java 25，並使用 Gradle 9.8.0、Paper 1.21.6。R52F Gradle launcher／daemon 使用 Java 21.0.6，而 Java 25.0.4 toolchain 負責編譯候選版。Java 21／Gradle 9.1.0 也描述保留的 1.3.2 baseline。

scheduler/storage/lifecycle/CMI/cross-server 變更要執行相關 PlugDev matrix，只回報實際測試範圍。Compilation alone 不能證明 Folia safety、queue durability 或 distributed consistency。收尾前執行 `git diff --check`、`git status --short`，並搜尋 stale release、endpoint、artifact name、removed behavior。

## Release hygiene

Root `build.gradle` 擁有 release version，PAPI 繼承它。涉及 web asset 的 release 要同步更新 frontend metadata 與 `src/main/resources/syncmoney-web/dist`。

Core repository 是 Web Admin 的 canonical source。Core release tag 不帶前綴；公開 Web mirror 使用對應的 `v` 前綴 tag，並發佈由 `WebDownloader` 使用的 deterministic source archive。Preview build 可以使用 `releaseVersion` Gradle property；stable source metadata 則保留在 committed source。

Frontend 變更後在 `syncmoney-web` 執行 `pnpm build:embedded`。它會清空/覆蓋 embedded `dist` tree，並在同一操作更新 `WebAdminServer.extractIndividualFiles`。Generated bundle 與 Java file 必須一起 review。

預期 artifact：

- `build/libs/Syncmoney-<version>.jar`
- `syncmoney-papi-expansion/build/libs/SyncmoneyExpansion-<version>.jar`
- `build/acceptance/SyncmoneyAcceptance.jar`

不得 commit server world、runtime secret/config、database、log、local test state、report、editor/agent cache、dependency directory。Embedded web bundle 與 Gradle wrapper JAR 是刻意 tracked 例外。

若 credential 已進入 tracked history，只改最新檔案不足以解決，應 rotate 並評估 history/exposure。

## 文件雙語一致性

Canonical project docs 為英文；繁中 companion 使用 `.zh_tw.md` 並放在 `docs/`。

- `AGENTS.md` ↔ `docs/AGENTS.zh_tw.md`
- `CLAUDE.md` ↔ `docs/CLAUDE.zh_tw.md`
- `CONTEXT.md` ↔ `docs/CONTEXT.zh_tw.md`
- `SECURITY.md` ↔ `docs/SECURITY.zh_tw.md`
- `CODE_OF_CONDUCT.md` ↔ `docs/CODE_OF_CONDUCT.zh_tw.md`
- `CONTRIBUTING.md` ↔ `docs/CONTRIBUTING.zh_tw.md`
- `docs/ARCHITECTURE.md` ↔ `docs/ARCHITECTURE.zh_tw.md`
- `docs/API_REFERENCE.md` ↔ `docs/API_REFERENCE.zh_tw.md`
- `docs/DEVELOPER_GUIDE.md` ↔ `docs/DEVELOPER_GUIDE.zh_tw.md`

不得宣稱未經觀察的 support、performance、security guarantee 或 runtime coverage。
