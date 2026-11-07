# CONTEXT.md — Syncmoney 專案背景

本文件提供維護者、貢獻者與程式代理一份可驗證的專案快照，說明 Syncmoney 的產品邊界、行為所在位置與容易忽略的架構限制。

## 快照

- 專案：Syncmoney
- 儲存庫：`Misty4119/Syncmoney`
- 目前 build 版本：`1.4.0`
- 設定 schema：`12`
- 編譯 toolchain：Java 25（1.4.0 target）；Gradle 9.8.0
- Plugin API descriptor：`api-version: 26.2`（目前的 descriptor metadata）
- 1.4.0 執行環境目標：Minecraft 26.2–26.3 的 Paper、Folia 與 Canvas；候選版實際驗收狀態記錄於發布準備快照。
- 主要伺服器目標：Paper、Folia、Canvas
- 選用整合：VaultUnlocked、CMI、PlaceholderAPI
- 授權：Apache License 2.0

目前候選版狀態、artifact pins、測試證據與未完成 gate，集中記錄於[版本化發布準備快照](release/1.4.0-readiness.zh_tw.md)，讓本架構背景文件不需每次測試都改動。

## 產品邊界

Syncmoney 負責經濟餘額、轉帳/管理命令、同步、監控、稽核與相關保護。它不是通用權限同步器，也不會複製任意第三方命令。

核心設計是以記憶體支援同步 Vault API，耐久化與網路工作則非同步進行。

## 模組

### Core plugin

`syncmoney-bukkit/src/main/java/noietime/syncmoney` 包含 lifecycle、economy、persistence、synchronization、commands、configuration、audit、breaker/guard、web、migration 與選用整合。

`src/main/resources` 包含 plugin descriptor、預設設定/訊息，以及嵌入式 web distribution。

### PlaceholderAPI expansion

`syncmoney-papi-expansion` 產生 `SyncmoneyExpansion-<version>.jar`。identifier 為 `syncmoney`，可跨 PAPI reload 持續存在，以 lazy reflection 找到 core plugin；reflection 是刻意保留的相容性邊界。

### Web 管理

`syncmoney-web` 使用 Vue 3、Vite、Pinia、vue-i18n。production assets 會嵌入 `src/main/resources/syncmoney-web/dist`；後端由 core plugin 內的 Undertow 提供。

### PlugDev

`tools/plugdev` 是外部驗收工具，不是 production dependency，也是本專案實機 smoke/cross-server 驗收程序的主要來源。

## 經濟模式

| 設定值 | 權威 / 持久化 |
|---|---|
| `auto` | 依環境選擇/偵測 |
| `local` | 本機 SQLite 經濟 |
| `local_redis` | Redis 同步，不使用 shared SQL persistence |
| `sync` | Redis 同步加 shared relational database persistence |
| `cmi` | CMI 保持經濟權威，Syncmoney 在其周邊同步 |

內部 enum 為 `LOCAL`、`LOCAL_REDIS`、`SYNC`、`CMI`。`LOCAL_REDIS` 與 `SYNC` 共用同步策略機制，但 persistence 設定不同。

## 經濟資料流

Vault 呼叫必須同步回傳，所以一般 balance read 由 `EconomyFacade` 經 `MemoryStateManager` 取得。

Mutation 沿用現有 strategy/writer pipeline：

1. 驗證輸入與 breaker/guard 狀態；
2. 適用時觸發 `AsyncPreTransactionEvent`，並真正尊重 cancellation；
3. 更新已接受的 memory state/version；
4. enqueue persistence/synchronization 工作；
5. 非同步 persist/publish；
6. result 可用後發出 `PostTransactionEvent`；
7. 遠端節點只套用更新版本，並抑制 echo/duplicate。

`TransferOrchestrator` 負責多帳戶轉帳；若繞過它分別更新兩個帳戶，可能破壞資金守恆。

## 分散式一致性

Balance 與 version 成對存在。遠端 handler 套用狀態前先比對 version；Pub/Sub message 帶 source/message identity 以抑制 echo/duplicate。

主要 channel：

- `syncmoney:balance:update`
- `syncmoney:cmi:balance:update`

代表性 key：

- `syncmoney:balance:{uuid}`
- `syncmoney:version:{uuid}`
- `syncmoney:online:players`
- `syncmoney:online:player:*`
- `syncmoney:baltop`
- `syncmoney:bank:*`
- audit recent/index/dedup keys

分散式系統本來就可能收到延遲訊息；不能為了讓同步「看起來更快」而移除 version check。

## 持久化

Shared `players` table 包含 UUID、player name、`DECIMAL(20,2)` balance、單調 `BIGINT` version、last server 與 updated timestamp。Syncmoney-owned `LOCAL_REDIS` 與 `SYNC` balance 上限為 `999999999999999999.99`；既有 `LOCAL` policy 不變，也不會 migration local data。

live `/pay` 成對轉帳使用既有 `recovery-v1.jsonl` spool。啟動時會先驗證 queued spool 與 legacy overflow log，再開啟 shared-mode write admission；recovery evidence 無法讀取或格式錯誤時會保持 admission 關閉，受影響帳戶則在 pending recovery 解決前維持 unavailable。queue commit 前會先 force 一筆 `RETRYABLE` 成對 admission checkpoint。Redis dispatch 後的 `ATTEMPTING`／`AMBIGUOUS` 結果會在 restart 後持續 quarantine，並可透過限管理員使用的 `/syncmoney recovery list|inspect|reconcile <id>` 查閱（permission `syncmoney.admin.recovery` 或 `syncmoney.admin`）。Reconciliation 以非同步方式執行，只接受 stable operation ID、canonical fingerprint、帳戶配對與版本皆相符的 exact Redis receipt。receipt 缺失、格式錯誤、collision、in-flight、mismatch 或無法讀取時都保留 quarantine；不能只靠 balance snapshot 解決 ambiguous operation，也不會自動重播或提供 force-clear。FileChannel force 支援本機 process restart recovery；不宣稱超出該行為的斷電或儲存裝置耐久性。Direct Vault/plugin atomic-transfer recovery 依 ADR-07 維持 `DEFERRED / NOT VERIFIED`；不包含 direct-transfer journal、capability、provider wiring 或 command recovery path。

其他 storage 分別處理 baltop、audit log 與 audit schema 演進、schema-version tracking、本機 SQLite player balance/transaction，以及 Shadow Sync history。

Storage 必須等已接受 event/write consumer 排空後才關閉。

## 執行緒模型

專案使用 Paper common scheduler API，以維持 Paper、Folia、Canvas 的 ownership 規則。

- Storage/network/computation：async。
- Plugin/global operation：global region scheduler。
- Player/entity state：entity scheduler。
- Teleport：async teleport API。

非同步 callback 即使起源於 entity task，也不能因此直接碰玩家或世界 entity。

## Lifecycle ownership

選用模組是由設定控制且有明確 owner 的資源，不是任意 ambient singleton。停用功能不應建立重型資源。

重要事實：

- `BreakerManager` 擁有唯一的 `PlayerTransactionGuard`。
- `EconomyServiceManager` 接收 guard/economy dependency，不重複建立 owner。
- Web server 擁有 Undertow/SSE/WebSocket helper。
- Audit 擁有 queue/export/cleanup resource。
- Storage manager 擁有 pool/connection。

目前關機順序依 dependency 設計：web → commands → permissions/listeners/sync → event consumers/drain → economy → audit → breaker → baltop persistence → storage → event bus cleanup。

## 設定預設值與 reload

目前重要預設：

- `config-version: 12`
- `server-name` 預設空白，operator 必須設定
- queue capacity `50000`
- Redis 預設 localhost:6379、database 0
- SQL 預設啟用，database name `syncmoney`
- economy mode `auto`
- currency `$`、兩位小數
- pay cooldown 30 秒
- pay minimum 1、maximum 1,000,000
- degraded payments 預設關閉
- high-value confirmation threshold 100,000
- Web Admin central mode 預設關閉
- Web Admin API key placeholder `change-me-in-production`

目前只有 `display`、`pay`、`permissions`、`admin-permissions`、`debug` 是 live-reload root，其餘變更可能需要重啟。

## 命令

玩家入口：

- `/money [player]`
- `/pay <player> <amount>`，包含確認流程
- `/baltop [page|me]`

管理入口為 `/syncmoney`。註冊的 subcommand 包含 `migrate`、`audit`、管理員專用 queued recovery `recovery list|inspect|reconcile`、條件式 `breaker`、`admin`、`web`、條件式 `shadow`、`monitor`、`debug`、`sync-balance`、`test`、條件式 `econstats`、`reload`、`version`。

Permission 宣告在 `src/main/resources/plugin.yml`，部分 subcommand 另有更細的 specialized/tier 檢查。

## PlaceholderAPI

已知 placeholder：

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

目前 expansion 對昂貴/全域查詢使用 5 秒 cache，最大 cache/pending 規模約 10,000。

## Web API

Web service 使用 Undertow。一般 REST request 使用 `Authorization: Bearer <api-key>`；`/health` 是 health probe。

主要 route group：

- `/api/system/*`
- `/api/economy/*`
- `/api/config*`
- `/api/audit/*`
- `/api/settings*`
- `/api/nodes*`
- `/api/auth/ws-token`
- `/api/extensions/{extensionName}/...`

Cross-server aggregate route 只在 central mode 註冊。

SSE 位於 `/api/sse`。前端先向 `/api/auth/ws-token` 取得 session token，再以該 token 開啟 SSE；舊的 `/sse` 說明已過時。

所有 `/ws` request 目前都回 `503 WEBSOCKET_UNAVAILABLE`，不會執行 WebSocket upgrade。完整 transport 延後處理。

`SystemApiHandler` 與 `NodesApiHandler` 都註冊 node GET route；registry 使用 `Map.put` 取代相同 key，且 `NodesApiHandler` 較晚註冊，因此 duplicate route 最後由它生效。

## 安全敏感區域

敏感資料包含 Web/node API key、Redis/SQL credentials、migration/Shadow credentials、audit export、acceptance/RCON secrets。不得 commit 真實值。

預設 Web Admin API key 不適合對外暴露。Key 空白或 trim 並忽略大小寫後等於 `change-me` / `change-me-in-production` 時，service 會停用 Web Admin，economy plugin 仍正常啟動。

若真實 secret 已進入 tracked file/history，只從最新 tree 刪除並不夠；必須 rotate/revoke 並評估 history/exposure。

## Build 與產物

```powershell
.\gradlew.bat test
.\gradlew.bat shadowJar
.\gradlew.bat :syncmoney-papi-expansion:test
.\gradlew.bat :syncmoney-papi-expansion:jar
.\gradlew.bat acceptanceJar
```

目前產物：

- `Syncmoney-1.4.0.jar`
- `SyncmoneyExpansion-1.4.0.jar`
- `SyncmoneyAcceptance.jar`

Web checks：

```powershell
cd syncmoney-web
pnpm typecheck
pnpm test:unit --run
pnpm build
```

web source 變更時，要同步處理 build output 與 embedded distribution。

## 驗收基線

歷史 1.3.2 PlugDev 紀錄涵蓋 Paper 1.20.4、Paper 26.2、Folia `26.2.build.7-beta`、Canvas 26.2，以及使用 shared Redis/PostgreSQL test infrastructure 的雙後端 Paper 26.2 network。Folia 26.2 Beta 已實際通過測試；這是該 beta build 的觀察結果，不是對未來 Folia release 的保證。

CMI 為授權軟體且不隨儲存庫提供；CMI acceptance 需要本機另備合法 plugin。不能把目前觀察到的 matrix 當成未來 server release 的保證。

## 文件契約

根目錄 `AGENTS.md`、`CLAUDE.md`、`CONTEXT.md`、`SECURITY.md`、`CODE_OF_CONDUCT.md`、`CONTRIBUTING.md` 是 canonical English。繁中對應檔置於 `docs/` 並使用 `.zh_tw.md`；architecture/API/developer 文件也必須維持雙語語意一致。
