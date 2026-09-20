# Syncmoney 架構

> 專案版本：`1.3.2`
> Config schema：`12`
> Build toolchain：Java 21

英文版：[`ARCHITECTURE.md`](ARCHITECTURE.md)

## 1. 系統邊界

Syncmoney 是 Vault-compatible Minecraft economy，對 Vault 提供同步 API，並以非同步方式處理 persistence 與 synchronization。

本儲存庫有三個 runtime deliverable：core plugin `src/main/java/noietime/syncmoney`、獨立 PlaceholderAPI expansion `syncmoney-papi-expansion`，以及 Vue/Vite Web Admin frontend `syncmoney-web`；frontend build 會嵌入 `src/main/resources/syncmoney-web/dist`。

`tools/plugdev` 是外部 acceptance tooling，不是 production dependency。

它不是通用 permission/command replication system。CMI mode 下 CMI 保持 economy authority；migration 與 Shadow Sync 也不是隱式 authority switch。

## 2. 架構優先順序

目前實作由五個核心限制塑造：

1. **Vault call 是同步的。** Vault balance read 通常必須直接由 memory 完成；Redis、SQL 或其他 network I/O 不得插入 hot server/entity path。
2. **資金不能遺失或重複。** 已接受的 mutation 有明確的 async persistence path 與 shutdown drain semantics。
3. **分散式 state 有版本。** Balance update 攜帶 monotonic version；較舊的 remote work 不得覆蓋較新的 local state。
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
- `cmi`：CMI 為 economy authority，Syncmoney 負責周邊 synchronization。
- `auto`：依 environment/detection 選擇。

Mode strategy/router 決定 authority 與 persistence，caller 不應自行繞過。

## 4. Economy state 與 transaction flow

### 4.1 金額表示

Money 使用 `BigDecimal` 與既有 normalization。Shared SQL balance 使用 `DECIMAL(20,2)`。所有 mutation 必須維持 insufficient-funds 與資金守恆。

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

## 5. 分散式同步

### 5.1 Versions

Balance state 帶 monotonic version；remote/delayed update 套用前必須比較 version，避免舊 Pub/Sub delivery 或 async callback 覆蓋新狀態。

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

Shipped YAML 預設停用 Web Admin 並綁定 `localhost:8080`。預設 API key placeholder 是 `change-me-in-production`；config validation 會對此值提出警告，但目前 Web Service 的自動停用檢查只比對精確字串 `change-me`。因此在對外開放 Web Admin 前，operator 必須主動替換 shipped placeholder。

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

`/ws` 存在，但目前 `WebSocketManager` 尚未完成，不應視為 production WebSocket transport。

目前行為和 SSE 有實質差異：

- 一般非 upgrade request 會收到 `503 WEBSOCKET_UNAVAILABLE`；
- upgrade-shaped request 只檢查 query token 是否非空；
- 現行 handler **沒有**透過 `WsTokenHandler.validateToken` 驗證該 token；
- 它會執行 101-shaped response、bookkeeping 與 logging，但 broadcast methods 尚未實作完整可用的 Undertow message transport。

這是已知 implementation limitation，也是未來修改時需要特別審查的 security-sensitive area。

## 10. Web Admin frontend

`syncmoney-web` 使用 Vue 3、Vite、Pinia、`vue-i18n` 與 PWA tooling。Production build 會嵌入 `src/main/resources/syncmoney-web/dist`。

目前 frontend package version 為 `1.3.2`。Release 若包含 web asset，frontend metadata 與 embedded bundle 要和 root release 一起更新。

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

Project compile toolchain 是 Java 21；runtime Java 依 server，PlugDev 對較新 Paper 26.1+ environment 記載 Java 25。

Scheduler、storage、lifecycle、CMI、cross-server 變更不能只靠 build success，需依 `tools/plugdev/README.md` 執行適用 matrix 並精確紀錄實際測試範圍。

## 13. 架構變更檢查

Architecture-affecting change 應能回答：

1. 誰擁有並關閉 resource？
2. 每個 Bukkit/CMI entity mutation 由哪個 scheduler owner 執行？
3. 是否把 blocking I/O 加入 sync/hot path？
4. accepted-write boundary 在哪裡，shutdown 如何排空？
5. monotonic version 與 stale work 如何處理？
6. Optional feature 停用時是否保持低成本？
7. Config change 是否需要 restart？
8. 英文/繁中 docs、REST contract、frontend client 與 tests 是否仍一致？
