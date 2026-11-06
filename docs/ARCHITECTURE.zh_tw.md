# Syncmoney 架構

> 專案版本：`1.3.2`
> Config schema：`12`
> 1.4.0 target toolchain：Java 25／Gradle 9.8.0

英文版：[`ARCHITECTURE.md`](ARCHITECTURE.md)

## 1. 系統邊界

Syncmoney 是 Vault-compatible Minecraft economy，對 Vault 提供同步 API，並以非同步方式處理 persistence 與 synchronization。

本儲存庫有三個 runtime deliverable：core plugin `syncmoney-bukkit/src/main/java/noietime/syncmoney`、獨立 PlaceholderAPI expansion `syncmoney-papi-expansion`，以及 Vue/Vite Web Admin frontend `syncmoney-web`；frontend build 會嵌入 `src/main/resources/syncmoney-web/dist`。

`tools/plugdev` 是外部 acceptance tooling，不是 production dependency。

它不是通用 permission/command replication system。CMI mode 下 CMI 保持 economy authority，Syncmoney 只同步 CMI 權威管理的遊戲幣；migration 與 Shadow Sync 也不是隱式 authority switch。

## 2. 架構優先順序

目前實作由五個核心限制塑造：

1. **Vault call 是同步的。** Vault balance read 通常必須直接由 memory 完成；Redis、SQL 或其他 network I/O 不得插入 hot server/entity path。
2. **資金不能遺失或重複。** 已接受的 mutation 有明確的 async persistence path 與 shutdown drain semantics。
3. **分散式 state 有版本。** Redis-backed economy update 使用 per-account Redis version。CMI 保留 legacy timestamp-derived version，遇到 clock rollback、restart 或多伺服器時不保證 global monotonic；延遲 CMI callback 仍會在 mutation 前重新檢查本機 watermark。
4. **Folia ownership 必須正確。** Player/entity work 要在 owning entity scheduler 執行；純 I/O 才能直接放到 async path。
5. **Optional module 有明確 owner。** Disabled feature 不應啟動只屬於該功能的 executor、schema、listener、subscription 或 HTTP service。

## 3. 主要 Runtime Layer

```text
Vault / commands / PAPI / Web Admin
              |
              v
      Economy mode routing
              |
     +--------+---------+
     |                  |
     v                  v
Syncmoney economy      CMI authority
     |
     v
MemoryStateManager
     |
     v
TransactionWriter / TransferOrchestrator
     |
     +----------+-------------+----------------+
     |          |             |                |
     v          v             v                v
 accepted   Redis/Lua     SQL/local        Pub/Sub /
 events     operations    persistence      audit/events
```

### 3.1 Plugin lifecycle

`Syncmoney` 建立並協調 config、storage、economy、sync、listener、permission、command、audit、breaker 與 web service manager。Optional feature 應在 config enabled 後才建立重型 executor、schema、subscription、folder、HTTP listener。

目前觀察到的 shutdown sequence 是：

1. Web service manager
2. Command service manager
3. Permission manager
4. Listener service manager
5. Sync manager
6. Event consumer manager，包含在 dependency 仍存活時 drain 已接受的 writes
7. Economy service manager
8. Audit service manager
9. Breaker manager
10. Baltop save
11. Storage manager
12. `SyncmoneyEventBus.clearAll()`

此順序先停止 producer，讓已接受 transaction work 在 dependency 尚存活時完成。修改順序需要 dependency analysis，不能只看 compile success。

### 3.2 Economy mode routing

設定接受 `auto`、`local`、`local_redis`、`sync`、`cmi`；內部 enum 為 `LOCAL`、`LOCAL_REDIS`、`SYNC`、`CMI`。

- `local`：本機 SQLite authority/persistence。
- `local_redis`：Redis synchronized economy，不使用 shared SQL persistence。
- `sync`：Redis synchronization 加 shared relational persistence。
- `cmi`：CMI 為 economy authority，Syncmoney 僅同步 CMI 餘額。
- `auto`：依 environment/detection 選擇。

Mode strategy/router 決定 authority 與 persistence，caller 不應自行繞過。

CMI mode 僅負責同步，必須安裝 CMI 與 Redis。Vault 為選用依賴，在此 mode 不會註冊 Vault provider，也不會註冊 Syncmoney economy query API。Syncmoney 不建構一般 economy facade/loaders，只啟動 CMI balance listener 與專用 CMI Pub/Sub/apply path。Outbound balance event 依 UUID 直接發布 event 中變更後的絕對餘額，不要求收款玩家在線於來源伺服器，因此涵蓋離線 Vault／QuickShop payee。此 mode 不啟動一般 balance channel/cache、SQL、通用週期版本修補、錢包／轉帳／排行榜指令、player tracking/protection、permission service、breaker、Shadow Sync、audit/event consumer、Web Admin 或 bStats，也不提供 economy balance/rank/leaderboard placeholders。此 mode 唯一的 Syncmoney 指令為 `/syncmoney version`。套用遠端餘額時只抑制 from/to 完全相符的 inbound CMI event，避免寬鬆時間窗吞掉緊接著的本地交易。

CMI adapter 沒有原子式雙帳戶轉帳契約，因此 CMI mode 會停用 Syncmoney `/pay`，並在餘額 mutation 前 fail closed。

所有 Syncmoney 雙帳戶轉帳入口也遵循此限制：Vault 指定目標玩家與 plugin transfer、VaultUnlocked 委派呼叫及公開的低階 Redis transfer API，都會在修改 Syncmoney memory、queue 或 Redis 前拒絕 CMI mode 請求。此 mode 中 Syncmoney 唯一會執行的餘額寫入，是套用遠端 CMI 同步更新。這些 source-level 檢查不代表 exact-candidate licensed/live CMI acceptance 已通過；請查閱版本化發布準備紀錄。

## 4. Economy state 與 transaction flow

### 4.1 金額表示

Money 使用 `BigDecimal` 與既有 normalization。Syncmoney-owned `LOCAL_REDIS` 與 `SYNC` shared mode 的單一 balance 上限為 `999999999999999999.99`，符合 SQL `DECIMAL(20,2)` 與 Redis script 的 cents 範圍。Projected deposit 在 write admission 前與持有 account mutation lock 時都會檢查。此變更不縮小既有 `LOCAL` policy，也不會為 shared-mode guard clamp 或 migration local balance。明確執行 LOCAL-to-SYNC migration 時，會先在 normalization 前拒絕 SQLite 原始負餘額，包括原本會四捨五入成 0.00 的負 sub-cent 值；接著在準備 insert-only 或 force-replacement SQL 前，拒絕正規化後超過此上限的餘額。遭拒列的 LOCAL 原值保持不變，也不會複製到 Redis。所有 mutation 必須維持 insufficient-funds 與資金守恆。

較舊、採用較寬 balance policy 寫入的 Redis 值不會自動掃描或 migration。Deposit、withdraw、transfer 會在記憶體 mutation 前拒絕超限的既有 account。Operator 在將這些 account 納入 1.4.0 shared mode 前，必須先備份並明確 reconciliation；admin set 是刻意修改餘額，不是 migration。Audit 開啟時，若 set 的 delta 超出 SQL `DECIMAL(20,2)`，系統會在 mutation 前拒絕。完成 reconciliation 前，這些 account 不屬於已驗證支援範圍。

### 4.2 Read path

Vault API 是同步的，因此 `EconomyFacade` 的正常 read 由 `MemoryStateManager` 提供。Redis/SQL/Mojang/HTTP/file I/O 不得加入同步 hot path。

Cache miss 或 reconcile 依既有 async 路徑處理。

### 4.3 Mutation path

一般 accepted mutation：

1. 驗證 input 與 breaker/guard；
2. 適用時觸發 `AsyncPreTransactionEvent`，cancellation 會被尊重；
3. 更新 accepted memory state 與 version；
4. enqueue persistence/synchronization；
5. async persist/publish；
6. result 可用後發出 `PostTransactionEvent`；
7. remote side 僅套用較新 version 並抑制 echo/duplicate。

`TransferOrchestrator` 協調 multi-account transfer；不可把兩側獨立更新而破壞 atomic/ordered semantics。

- live `/pay` 成對轉帳使用既有 `recovery-v1.jsonl` spool。啟動時會先驗證 queued spool 與 legacy overflow log，再開啟 shared-mode write admission；recovery evidence 無法讀取或格式錯誤時會保持 admission 關閉，受影響帳戶則在 pending recovery 解決前維持 unavailable。queue commit 前會先 force 一筆 `RETRYABLE` 成對 admission checkpoint。Redis dispatch 後的 `ATTEMPTING`／`AMBIGUOUS` 結果會在 restart 後持續 quarantine，並可透過限管理員使用的 `/syncmoney recovery list|inspect|reconcile <id>` 查閱（permission `syncmoney.admin.recovery` 或 `syncmoney.admin`）。Reconciliation 以非同步方式執行，只接受 stable operation ID、canonical fingerprint、帳戶配對與版本皆相符的 exact Redis receipt。receipt 缺失、格式錯誤、collision、in-flight、mismatch 或無法讀取時都保留 quarantine；不能只靠 balance snapshot 解決 ambiguous operation，也不會自動重播或提供 force-clear。FileChannel force 支援本機 process restart recovery；不宣稱超出該行為的斷電或儲存裝置耐久性。Direct Vault/plugin atomic-transfer recovery 依 ADR-07 維持 `DEFERRED / NOT VERIFIED`；不包含 direct-transfer journal、capability、provider wiring 或 command recovery path。版本特定 runtime 覆蓋與其測試範圍記錄於 release-readiness 文件；不得從有界子案例推定更廣泛的相容性。

## 5. 分散式同步

### 5.1 Versions

Redis-backed economy balance update 使用 per-account Redis version。CMI 維持 legacy timestamp-derived numeric version；wall-clock rollback、restart、counter wrap 或 server clock skew 可能令版本重複或倒退，因此不代表 global ordering。CMI entity callback 套用 balance 前會重新檢查 handler 的 current watermark。本機 inbound watermark 不再按年齡過期；最多保留 10,000 個 account，只有超出上限時才淘汰最舊項目。這是 source-level 行為，不是 licensed/live CMI runtime 證據。

### 5.2 Redis

代表性 Redis keys：`syncmoney:balance:{uuid}`、`syncmoney:version:{uuid}`、`syncmoney:online:players`、`syncmoney:online:player:*`、`syncmoney:baltop`，以及 audit/bank 相關 keys。

主要 Pub/Sub channel：

- `syncmoney:balance:update`
- `syncmoney:cmi:balance:update`

CMI remote state 套用時必須 suppress echo。

### 5.3 SQL

Shared `players` table：

| Column | Shape |
|---|---|
| `uuid` | `VARCHAR(36)`, primary key |
| `player_name` | `VARCHAR(16)` |
| `balance` | `DECIMAL(20,2)` |
| `version` | `BIGINT` |
| `last_server` | `VARCHAR(64)` |
| `updated_at` | timestamp |

Audit、local persistence、schema migration、Shadow Sync 另有 workflow，不要由 `players` table 推測全部 storage contract。

## 6. Scheduler 與 concurrency

Syncmoney 使用 Paper common scheduler API；`folia-supported: true` 本身不能證明 thread safety。

- I/O/data：async scheduler 或 owned executor。
- Plugin/global：global region scheduler。
- Player message、teleport、CMI mutation、entity access：entity scheduler。
- Teleport 使用 `teleportAsync`。
- Global iteration 先 snapshot，再派到各 player owner。
- Async callback 碰 entity 前重新進入正確 scheduler。
- 不得讓 region 互相阻塞。

## 7. Optional module 與 protection

### 7.1 Breaker and player protection

`BreakerManager` 擁有唯一 `PlayerTransactionGuard`，economy service 透過 dependency 接收。Global breaker 與 per-player protection 是不同 enable boundary，resource monitor、limit、webhook、breaker state 要維持既有 ownership/config separation。

### 7.2 Audit

Audit 是 optional。Cleanup/export 依 parent audit switch；停用時相關 Web API 會回報 feature disabled，而不是假裝資料存在。

### 7.3 Shadow Sync and migration

Shadow Sync、CMI migration、local-to-sync migration 是明確的 maintenance/data workflow，與 live economy routing 的 lifecycle、storage、authority semantics 分開。

Shadow Sync 是 best-effort 的次級鏡像，不是具持久性的寫入佇列。有限佇列滿載時可能拒收快照；伺服器會記錄警告，`/syncmoney shadow status` 會顯示本次啟動後遭拒的數量。遭拒快照可能要等該玩家再次進入佇列才會補上。這不會改變主要 economy balance。

## 8. Configuration model

`SyncmoneyConfig` 是 runtime snapshot；live reload 僅限既有 `display`、`pay`、`permissions`、`admin-permissions`、`debug` roots。需要新 connection、owner、listener、executor、schema、subscription 或 economy authority 的變更，在 lifecycle 未明確支援前都視為 restart-required。`server-name` 必須設定，空白會阻止正常 operation。

## 9. Web Admin backend

### 9.1 HTTP pipeline

Embedded backend 使用 Undertow。

- `/health` 不需 authentication。
- 一般 `/api/*` route 需要 `Authorization: Bearer <api-key>`。
- `ApiKeyAuthFilter` 使用 constant-time key comparison，並可能回傳 `429 RATE_LIMITED`。
- 只有在 `web-admin.security.trust-proxy=true` 時才採信 `X-Forwarded-For`。
- Route exception 由 `HttpHandlerRegistry` 正規化成一致的 API error response。
- Static frontend asset 由 embedded distribution 提供。

Shipped YAML 預設停用 Web Admin 並綁定 `localhost:8080`。API key 空白或 trim 並忽略大小寫後等於 `change-me` / `change-me-in-production` 時，`WebServiceManager` 會停用 Web Admin，但 economy plugin 仍正常啟動。

### 9.2 Route registration

Handler 會註冊到 `HttpHandlerRegistry`。相同 method/path 使用 replacement semantics，較晚註冊的 handler 會取代先前 handler。

`SystemApiHandler` 與 `NodesApiHandler` 都會註冊 `GET /api/nodes` 和 `GET /api/nodes/status`；目前 `NodesApiHandler` 較晚註冊，因此實際生效的是它提供的 handler。

### 9.3 SSE

目前實作且 frontend 使用的 live channel 是 **`/api/sse`**。

Frontend 會先呼叫 `POST /api/auth/ws-token` 取得一次性 token。Token 是 UUID，有效時間為 60,000 ms，且 successful validation 後會被 consume。`SseManager` 接受以下三種認證形式：

- Bearer API key；
- Bearer one-time token；或
- `?token=<one-time-token>`。

SSE manager 約每 15 秒送出 keepalive activity，並從 `PostTransactionEvent` broadcast transaction telemetry。

### 9.4 WebSocket limitation

`/ws` 路由目前固定回應 `503 WEBSOCKET_UNAVAILABLE`，不會執行 upgrade，因此不是 production WebSocket transport。

所有 `/ws` request（包含帶非空 token 的 upgrade）都會收到 `503 WEBSOCKET_UNAVAILABLE`。完整 WebSocket transport 延後處理。

這是已知 implementation limitation，也是未來修改時需要特別審查的 security-sensitive area。

## 10. Web Admin frontend

`syncmoney-web` 使用 Vue 3、Vite、Pinia、`vue-i18n` 與 PWA tooling。Production build 會嵌入 `src/main/resources/syncmoney-web/dist`。

Core repository 是 frontend 的 canonical source。Release 若包含 web asset，frontend metadata 與 embedded bundle 要和 root release 一起更新；公開的 `Syncmoney-web` mirror 會接收相同 source，並發佈帶 `v` 前綴的 deterministic source archive。Core tag 不帶前綴，Web tag 使用 `v`。目前 frontend package version 為 `1.3.2`。

## 11. PlaceholderAPI expansion

Expansion 是獨立 JAR，identifier `syncmoney`，persistent，並以 lazy reflection discover core plugin；reflection 是刻意的 compatibility boundary。

支援的 placeholder family 包含：

- `balance`
- `balance_formatted`
- `balance_abbreviated`
- `rank`
- `my_rank`
- `total_supply`
- `total_players`
- `version`
- `online_players`
- `top_<n>`
- `balance_<player>`
- `balance_formatted_<player>`
- `balance_abbreviated_<player>`

昂貴/全域工作以 async refresh 與 bounded cache 離開 hot path；目前 expiry 約 5 秒、cached/pending bound 約 10,000。

## 12. Build 與 acceptance architecture

主要 artifact：

- `build/libs/Syncmoney-<version>.jar`
- `syncmoney-papi-expansion/build/libs/SyncmoneyExpansion-<version>.jar`
- `build/acceptance/SyncmoneyAcceptance.jar`

標準 validation：

```powershell
.\gradlew.bat test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar
cd syncmoney-web
pnpm typecheck
pnpm test:unit --run
pnpm build
```



Release automation 透過 Gradle property 建置 preview version，先發佈 Web archive 再發佈 core JAR，stable publication 則由 `production-release` environment gate。Web archive 排除 generated `dist`、dependency、coverage、Playwright 與 editor directory，因為 plugin 下載後會自行建置 frontend。

Scheduler、storage、lifecycle、CMI、cross-server 變更不能只靠 build success，需依 `tools/plugdev/README.md` 執行適用 matrix 並精確紀錄實際測試範圍。

## 13. 架構變更檢查

Architecture-affecting change 應能回答：

1. 誰擁有並關閉 resource？
2. 每個 Bukkit/CMI entity mutation 由哪個 scheduler owner 執行？
3. 是否把 blocking I/O 加入 sync/hot path？
4. accepted-write boundary 在哪裡，shutdown 如何排空？
5. per-account Redis version 與 stale work 如何處理？
6. Optional feature 停用時是否保持低成本？
7. Config change 是否需要 restart？
8. 英文/繁中 docs、REST contract、frontend client 與 tests 是否仍一致？


## 發布證據

目前版本的測試結果、artifact pins 與未完成 acceptance gate，集中記錄於[版本化發布準備文件](release/1.4.0-readiness.zh_tw.md)，不在此長期架構指南重複維護。
