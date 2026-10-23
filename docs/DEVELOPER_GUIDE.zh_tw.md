# Syncmoney 開發者指南

> 專案版本：`1.3.2`
> Config schema：`12`
> 1.4.0 target build toolchain：Java 25／Gradle 9.8.0

英文版：[`DEVELOPER_GUIDE.md`](DEVELOPER_GUIDE.md)

本文件供 core plugin、PlaceholderAPI expansion、Web Admin backend/frontend 與 acceptance tooling 的貢獻者使用。開始修改前先閱讀 root [`AGENTS.md`](../AGENTS.md)；其中的 correctness 與 lifecycle rules 適用於所有 code change。

本文件說明目前 source tree 的開發流程與不變條件。若文件與 implementation 不一致，以程式、default config、descriptor 與 executable test 為準。

## 1. Prerequisites

- Git
- JDK 25 或更新版本，供 1.4.0 Gradle toolchain/build 與 server runtime
- repository Gradle wrapper
- Node.js + pnpm，供 `syncmoney-web`
- 依測試範圍準備 Paper/Folia/Canvas
- 需要對應 mode/feature 時才啟動 Redis/SQL
- CMI runtime check 需自行提供 CMI 與 CMILib；適用的 license 需另行確認（目前 1.4.0 probe 未確認 license 狀態）

1.4.0 target 的 build 與 runtime 最低為 Java 25，Paper 最低為 1.21.6。保留的 1.3.2 baseline 使用 Java 21／Gradle 9.1.0；這些僅是歷史基準，不是 1.4.0 的 toolchain 或 runtime minimum。

## 2. Repository map

| Path | 用途 |
|---|---|
| `src/main/java/noietime/syncmoney` | Core plugin |
| `src/main/resources/config.yml` | Shipped configuration |
| `src/main/resources/plugin.yml` | Plugin descriptor、commands、permissions |
| `src/main/resources/syncmoney-web/dist` | Embedded built frontend |
| `syncmoney-papi-expansion` | PlaceholderAPI expansion |
| `syncmoney-web` | Vue/Vite Web Admin |
| `src/acceptance` | acceptance plugin source |
| `tools/plugdev` | real-server acceptance tooling |
| `docs` | architecture/API/developer/zh_tw docs |

Root `build.gradle` 擁有 release version；PAPI 繼承它。涉及 web asset 的 release 要同步 frontend metadata 與 embedded bundle。

## 3. 每個變更先從證據開始

1. 執行 `git status --short --branch`。
2. 保留不相關 worktree change。
3. 閱讀 implementation 與 callers。
4. 閱讀相關 default config 與 tests。
5. 檢查 public documentation 中依賴該行為的承諾。
6. rename/remove API seam 前追蹤 reflection users。
7. 移動 initialization/shutdown code 前追蹤 lifecycle ownership。
8. 碰 Bukkit/CMI entity 前追蹤 scheduler ownership。

不要在調查過程輸出 credentials。

## 4. Build 與 test commands

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

Expected artifact：

- `build/libs/Syncmoney-<version>.jar`
- `syncmoney-papi-expansion/build/libs/SyncmoneyExpansion-<version>.jar`
- `build/acceptance/SyncmoneyAcceptance.jar`

依變更範圍選擇適當測試。Documentation-only change 不需要完整 server matrix；scheduler/storage/lifecycle/cross-server/CMI 變更則需要 PlugDev runtime evidence。Compile success 不能證明 distributed consistency、queue durability 或 Folia safety。

## 5. Economy 開發規則

### 5.1 Money type

Money 使用 `BigDecimal` 與既有 normalization。失敗/拒絕 transaction 不得建立或消滅資金。

Failure path 也必須測試，至少涵蓋：

- insufficient funds；
- concurrent writes；
- stale versions；
- queue saturation/backpressure；
- persistence failure/recovery；
- shutdown 時仍有 accepted writes queued。

### 5.2 Respect the existing transaction boundaries

依既有 boundary 工作：`EconomyFacade`、`MemoryStateManager`、`TransactionWriter`、`TransferOrchestrator` 與 mode router。不要為了方便直接改 Redis/SQL。

`AsyncPreTransactionEvent` **目前會由 transaction path 觸發，而且 cancellation 會生效**。`PostTransactionEvent` 在 result processing 後發出並供 telemetry 使用。

### 5.3 Synchronous callers need memory-backed behavior

Vault 是 synchronous，因此 Vault-facing read 必須保持 memory-backed；cache miss 走既有 async warm/reconcile。Vault、placeholder、tab completion、entity task 不得加入 blocking Redis/SQL/Mojang/HTTP/file I/O。

### 5.4 Versions and cross-server state

Redis-backed economy update 使用 per-account Redis version。CMI 維持 legacy timestamp-derived version，clock rollback、restart 或多伺服器時不保證 global monotonic。CMI callback 套用前仍須重新比對本機已觀察到的 freshness。主要 channel：

- `syncmoney:balance:update`
- `syncmoney:cmi:balance:update`

CMI remote apply 要 suppress outbound echo。

## 6. Scheduler 規則

- Pure I/O/data：async scheduler 或 owned executor。
- Plugin/global operation：global region scheduler。
- Player/entity state、message、CMI mutation：player entity scheduler。
- Teleport：`teleportAsync`。
- Global collection 先 snapshot，再把各 player work 派到 owner。
- Async callback 碰 entity 前重新進入正確 scheduler。
- 不得讓 region 互相阻塞。

`folia-supported: true` 是 descriptor metadata，不是 thread-safety proof。

## 7. Lifecycle 與 optional module

每個 executor、listener、queue、subscription、storage connection、scheduled task、HTTP server 與 optional feature 都要有單一 owner。

Feature disabled 時不要建立屬於它的 heavy resource；保存 cancellation/close handle，partial initialization failure 也要 cleanup。

目前 shutdown order 刻意讓 accepted economic work 在 economy/audit/storage dependency 存活時排空。變更 shutdown ownership/order 時要說明 dependency reason 並測 failure/stop path。

## 8. Configuration changes

`SyncmoneyConfig` 是 runtime snapshot。`ConfigReloadPolicy` 目前只允許：

- `display`
- `pay`
- `permissions`
- `admin-permissions`
- `debug`

新增 setting 若會建立 connection、executor、schema、subscription、listener、HTTP service 或改變 economy authority，在 reload lifecycle 未明確實作前應視為 restart-required。

Config change 應依序：

1. 更新 `src/main/resources/config.yml`；
2. 更新 parsing/default/validation code；
3. 判斷 config schema version 是否真的需要變更；
4. 僅在 lifecycle 確實支援時更新 reload policy；
5. 同步 README/docs 與雙語文件；
6. 對非 trivial 行為加入 focused validation/reload tests。

Release version 與 config schema version 分離。`server-name` 空白會阻止正常 operation。

## 9. CMI integration

`cmi` mode 中 CMI 維持 economy authority。

CMI API 的 player mutation 必須在 owning entity scheduler 執行；Redis/network work 留在 entity scheduler 外。

Cross-server CMI update 要比較 version/freshness、套用時 suppress echo，async work 後重新確認 player availability/ownership。Migration 與 Shadow Sync 要與 live authority 分開。

CMI 為授權軟體，不隨 repository 分發；acceptance 需要本機合法 plugin。

先前的 `1.4.0-alpha.1` 候選版使用本機提供的 CMI 9.8.10.5 與 CMILib 1.6.0.1，在 Paper 1.21.6 上對一名線上玩家進行過一次有界唯讀檢查；CMI direct balance、Syncmoney CMI route 與公開的 `BalanceSnapshot` API 讀值相符。最終候選版的 CMI runtime acceptance 仍為 **NOT VERIFIED**。此結果不能證明 CMI license 目前有效，也未驗證 CMI mode 下 Syncmoney Vault provider 的註冊、任何 mutation、跨服行為或 Folia/Canvas 行為。依 ADR-12，CMI mode 不屬於 1.4.0 已驗證的支援範圍，未驗證可用於正式環境；完整 CMI runtime acceptance 仍為 **NOT VERIFIED**。

CMI mode 會停用 `/syncmoney admin` 餘額操作及 `/syncmoney sync-balance`，因為這些命令不會更新 CMI 的權威餘額。

## 10. Web Admin backend development

Backend 是 Undertow。

### Authentication

一般 `/api/*` request 使用 `Authorization: Bearer <api-key>`。`/health` 不需 authentication。修改 auth path 時要保留 constant-time key comparison 與 rate limiting。

Shipped key 是 `change-me-in-production`。API key 空白或 trim 並忽略大小寫後等於 `change-me` / `change-me-in-production` 時，Web Admin 會停用；economy plugin 仍可正常啟動。

### Route registration

修改 route 時同時檢查 route-specific handler 與 registry。`HttpHandlerRegistry` 對相同 method/path key 使用 replacement semantics。

目前 `GET /api/nodes` 和 `GET /api/nodes/status` 有 duplicate registration；`NodesApiHandler` 較晚註冊，因此實際生效。

### SSE and WebSocket

Frontend live stream 是 `/api/sse`。One-time token 由 `POST /api/auth/ws-token` 發出，有效 60 秒，validation 成功後 consume。

所有 `/ws` request（含 upgrade-shaped request）都回傳 `503 WEBSOCKET_UNAVAILABLE`。在完整 transport 與 authentication path 實作並通過測試前，不要新增依賴 `/ws` 的 client code。

### API changes

修改 endpoint 時：

1. 更新 handler validation 與 response model；
2. 維持 shared error convention；
3. 確保 blocking work 被 dispatch 離開 Undertow I/O thread；
4. 驗證 authentication、CORS、rate limit、proxy trust 與 node-to-node auth；
5. 同步更新 `docs/API_REFERENCE.md` 與 `docs/API_REFERENCE.zh_tw.md`；
6. 更新 frontend API client、types 與 tests；
7. 若 frontend source 有改動，rebuild embedded frontend。

## 11. Web frontend development

`syncmoney-web` 使用 Vue 3、Vite、Pinia、`vue-i18n`、PWA tooling。沿用既有 API client/store，不要繞過 shared auth、dedup 與 response handling。

Live path 維持 `/api/sse`；在 backend transport 與 token validation 完成並測試前，不要讓 UI 依賴 `/ws`。

驗證：

```powershell
cd syncmoney-web
pnpm typecheck
pnpm test:unit --run
pnpm build
```

Production build 嵌入 `src/main/resources/syncmoney-web/dist`。Frontend source 或 public release metadata 改變時要 rebuild，並確認 packaged JAR 內包含最新 output。Web asset 隨 release 發佈時，frontend package version 要和 root release 對齊。

## 12. PlaceholderAPI expansion development

`syncmoney-papi-expansion` 是獨立 artifact，identifier `syncmoney`，persistent，以 lazy reflection 連到 core。Reflection 是 compatibility seam，rename/remove API 前要追蹤兩側。

Placeholder resolution 保持 non-blocking；昂貴/全域值使用 async refresh 與 bounded cache，目前 expiry 約 5 秒、cached/pending bound 約 10,000。

支援的 placeholder family 包含 balance variants、rank/my-rank、total supply/players、version、online players、`top_<n>` 與 target-player balance variants。Placeholder behavior 有變更時，expansion tests 與 public documentation 必須一起更新。

## 13. REST response 與 error conventions

大多數成功 JSON response 使用 `success`、`data`、`meta`；`meta` 包含 timestamp 與 plugin version。錯誤通常使用 `success: false`、含 code/message 的 `error` object，以及相同 metadata。Cursor pagination 是已知例外，不包含一般 `meta` envelope。

`HttpHandlerRegistry` 將 `IllegalArgumentException`、`IllegalStateException` 映射為 HTTP 400，`SecurityException` 為 403，`NoSuchElementException` 為 404，`UnsupportedOperationException` 為 405，未預期失敗為 500。未知 route 回傳 `404 NOT_FOUND`。

沿用既有 `ApiResponse` helper 與 shared exception path，讓 frontend 與外部 client 能一致處理錯誤。

## 14. Storage 與 schema changes

共享 `players` table 以 UUID 為 primary key，保存 player name、`DECIMAL(20,2)` balance、monotonic version、last server 與 update time。Redis 保存 balance/version、online-player、baltop、audit 與 bank-related state。

Schema 或 persistence 變更時：

1. 維持金額 precision 與 version ordering；
2. migration 對既有 installation 必須安全；
3. local、shared、audit、Shadow 與 migration workflow 必須保持區分；
4. 只有對應 schema contract 真正改變時才更新 schema-version metadata；
5. 測試 failure/recovery path，而不只成功寫入；
6. 記錄 upgrade/rollback 所需的 operator action。

不要直接修改 storage 來繞過 `EconomyFacade`、`MemoryStateManager`、`TransactionWriter`、`TransferOrchestrator` 或 mode router。

## 15. Commands 與 permissions

Top-level commands 為 `/money`、`/pay`、`/baltop`、`/syncmoney`。

`/syncmoney` 依目前 registration 與 enabled feature 提供 administrative/diagnostic subcommands，包括 migration、audit、admin、web、monitoring、debug、balance sync、test、reload、version，以及 optional breaker、Shadow 與 economy-stat 功能。

Permission behavior 同時由 `plugin.yml` 與 command router/subcommand checks 決定。重要 nodes 包括 `syncmoney.money`、`syncmoney.money.others`、`syncmoney.pay`、`syncmoney.admin` 與專用 `syncmoney.admin.*` permissions。

新增或修改 command 時，要驗證 permission denial、console/player restriction、player action 的 scheduler ownership、tab-completion cost、user-facing message，以及兩種語言/config default。

## 16. Events 與 third-party integration

`AsyncPreTransactionEvent` 會由 `TransactionWriter` 在適用的 transaction path 觸發，且 cancellation 會被遵守。`PostTransactionEvent` 會在 transaction result processing 後發出，並供 telemetry 使用，包含 Web Admin live update。

`SyncmoneyEventBus` 是 internal event bus，不是 Bukkit event bus。不要在未閱讀 dispatcher 前假設它具有 Bukkit main-thread semantics。

整合 Vault、CMI、PlaceholderAPI 或其他 optional dependency 時：

- 保持 hard/soft dependency declaration 正確；
- dependency 不存在時不要載入 optional API；
- CMI/player mutation 維持 entity-thread ownership；
- 有意使用 reflection 的 compatibility contract 必須維持相容；
- 文件只描述實際測試過的 compatibility，不擴大宣稱。

## 17. Real-server acceptance

實機驗收依 `tools/plugdev/README.md` 執行。PlugDev 是 development tooling，不是 production dependency。

Scheduler、lifecycle、storage、CMI、cross-server synchronization 變更不能只靠 compilation 驗證。需分別記錄實際 Minecraft/Paper/Folia/Canvas version、runtime Java version、Syncmoney artifact/version、實際存在的 optional dependency versions、player-side behavior、startup/shutdown logs、適用時的 cross-server propagation，以及無法測試的 scenario。

1.4.0 使用 JDK 25 或更新版本及 repository Gradle 9.8.0 wrapper；server 必須使用 Java 25 或更新版本與 Paper 1.21.6 或更新版本。Java 21／Gradle 9.1.0 僅描述 1.3.2 baseline。

### 升級與回復 runbook

以下是 V03/M01–M03 的 operator 流程。PlugDev 成功報告只代表其 disposable test fleet 的有限證據，不能證明 production fleet 已停止所有 writer 或排空所有 queue。

#### M01 — 維護窗口與備份

1. 確認 Java 25 或更新版本、Paper 1.21.6 或更新版本、實際 server build、每個節點唯一的 `server-name`、economy mode、optional dependencies、SQL schema version、Redis topology、Syncmoney artifact hash 與 config schema。Acceptance 使用 [PlugDev guide](../tools/plugdev/README.md) 列出的 user-pinned server artifacts；額外的 current-stable build 只能作 supplemental evidence。保存移除 secrets 的版本清單；report 不得包含 credentials。
2. 宣告維護並在每個節點停止所有 economy producer：玩家／管理員命令、Web Admin 寫入、定時獎勵、自動化與第三方整合。若 CMI 是設定中的 authority，也要停用 CMI economy commands/module，並 quiesce 外部 CMI API 與 SQL writers。CMI 在 authority mutation 後才發出 balance event，因此該 event 無法 veto 任意直接 API mutation；完整 CMI runtime acceptance 仍是 **NOT VERIFIED**。目前只執行過 §9 的有界唯讀檢查，且依 ADR-12，CMI mode 不在 1.4.0 已驗證支援範圍。
3. 確認 accepted writes、retry、in-flight work、database writes 與 audit work 已排空後才繼續。目前 runtime 沒有提供完整的 fleet-wide queue/retry/in-flight counters。單獨看到 `Syncmoney disabled.` 或 zero pending recovery records，不能證明 production fleet 已排空。若現有證據無法確認每個節點與 writer 的狀態，維持 maintenance window，不要進行升級。
4. 正常停止每個節點並確認 process 已結束。之後才保存最新 config、messages、aliases、SQL data、local SQLite database 與可能存在的 WAL/SHM、Redis RDB/AOF state、recovery/overflow files。記錄版本與 SHA-256。這些備份只供另行授權的 disaster recovery，不是一般 rollback 流程。

#### M02 — Candidate canary 與 rollout

1. 維持 producers 停止。在 canary 節點只安裝一份 candidate `Syncmoney-1.4.0.jar` 並沿用原資料目錄；其他舊節點保持停止。核對 candidate JAR hash、startup、config/schema 保留、optional-module 狀態與 Vault provider readiness。
2. 使用 disposable accounts 驗證 read/write、transaction result，以及適用時 SQL/Redis 的 balance 與 version parity。不要對真實玩家餘額注入破壞性 fault。production canary 寫入需要另外取得 operator authorization。
3. producers 繼續停止時更新其他節點。只有所有節點都執行已驗證的 candidate、回報 ready，並核對其餘額與版本後，才恢復 producers。仍有舊 writer 執行時不可恢復；已知 1.3.2 SQL writer 可能使較新的資料列回退。

#### M03 — 正常 rollback

1. 再次 quiesce 所有 producers、確認 drain 完成，並正常停止每個節點。更換 artifact 前先保存最新 SQL、Redis、SQLite 與 recovery/overflow state。
2. recovery journal 若仍有 pending 或 ambiguous work，不可 rollback。保留資料並先解決，不能刪除 journal 讓 rollback 看起來成功。
3. 將 candidate JAR 換成 checksum 已驗證的 1.3.2 JAR，同時保留相容的目前資料與版本。確認 1.3.2 能讀取升級期間寫入的交易。可行時保留 Java 25 與受支援的 server；如果也要降級 JVM 或 server，必須選 1.3.2 支援的版本，不可假設較新的 server 能在 Java 21 執行。
4. 一般 rollback 不可 flush Redis、重設餘額或還原升級前備份。舊備份會丟失其建立後的有效交易。重新使用 1.3.2 也會重新引入已知 stale SQL-writer 限制，因此不得讓新舊 writer 同時運作。

目前 M01–M03 runtime reports 覆蓋六個 user-pinned Paper/Folia/Canvas endpoints 上的兩個 synthetic nodes；另有一份 supplemental current-stable Paper report。它們驗證這些測試節點的正常停止、candidate writes、data snapshots 與 recovery journal state，尚未證明 production fleet-wide quiescence、完整 drain counters 或完整 CMI acceptance。依 ADR-12，完整 CMI mode 及尚未執行的 T21 案例不屬於 1.4.0 已驗證的支援聲明。T00A minimum-Paper smoke 步驟見 [`tools/plugdev/README.md`](../tools/plugdev/README.md)；此 smoke 不取代 V03/M00–M12 完整 persisted-state upgrade 與 rollback gate。

## 18. Documentation、security 與 release hygiene

Canonical operational/community 文件以 repository root 的英文版為準；繁體中文版統一放在 `docs/`，檔名使用 `.zh_tw.md`。Behavior 改變時兩種語言需維持語意一致。

不得公開 credentials、runtime API keys、database secrets、RCON secrets、private server data、worlds、logs 或 generated local test state。Public example 必須使用 placeholder。

Security issue 請私下寄至 `security@noie.fun`。除非專案明確採用 SLA，否則不要承諾 response/remediation 時程。

Release 前：

1. 驗證 root `build.gradle` version 與 frontend package/public metadata；
2. web assets 有變更時重建 embedded frontend；
3. build core、PAPI expansion 與 acceptance artifacts；
4. 驗證 JAR contents 與 runtime descriptors；
5. 更新 changelog 與雙語文件；
6. 執行 `git diff --check`、檢查 `git status --short`，並 review 最終 diff 是否有 secrets、stale versions、stale endpoints 或 unrelated changes。

預期 artifacts 為 `build/libs/Syncmoney-<version>.jar`、`syncmoney-papi-expansion/build/libs/SyncmoneyExpansion-<version>.jar`、`build/acceptance/SyncmoneyAcceptance.jar`。

## 19. Release 自動化與 frontend mirror

Root repository 是 Web Admin 的 canonical source。穩定 source metadata 保留在 `build.gradle` 的 committed version；preview job 傳入 `-PreleaseVersion`，因此不必把 preview metadata commit 進去。CI 在 `NEXT_VERSION` repository variable 已設定時使用它，否則退回 root version。此 checkout 無法取得 hosted variable 的值，因此 preview version 應寫成 `<base-version>-alpha.<run-number>`。

1.4.0 matched synthetic facade/receipt-adapter benchmark 未達全部原始 B00 thresholds。ADR-11 僅在該有界 profile 範圍內接受已量測的 regression；thresholds 與 correctness hard gates 均未改變。此結果不能證明 production、SQL、Redis 或 real-server performance，且此例外不適用於 correctness failure。

Core repository 使用不帶前綴的 tag。Alpha/beta tag 會在 Web repository 更新後發佈 prerelease；stable tag 必須先通過 `production-release` environment approval，且有對應 changelog section。Release workflow 先更新 [Syncmoney-web](https://github.com/Misty4119/Syncmoney-web)，推送對應的 `v` 前綴 tag，等待 Web Release 完成，再發佈 core 與 PlaceholderAPI JAR。Web Release 內容是 deterministic source archive 與 checksum；刻意不包含 `dist`、`node_modules`、coverage、Playwright output 或 editor files。Syncmoney 下載 archive 後在目標 server 本機建置。

跨 repository update 使用 `SYNC_WEB_RELEASE_TOKEN` secret。它必須是權限限定在 Web repository 的 fine-grained token，不得把值寫入檔案、log、release notes 或 runtime config。

Frontend 有變更時，在 core 的 `syncmoney-web` 目錄執行 `pnpm build:embedded`。script 會暫時套用 `SYNCMONEY_VERSION`、執行 production build、清空並覆蓋 `src/main/resources/syncmoney-web/dist`，並重寫 `WebAdminServer.extractIndividualFiles` 的 `rootFiles`、`assetFiles`、`iconFiles` arrays。Generated bundle 與 Java lists 是同一個 review unit。Web mirror 的 source 與 public metadata 由 core release workflow 接收；不要在 mirror 內獨立修改 release content。
