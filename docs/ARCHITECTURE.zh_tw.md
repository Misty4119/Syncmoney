# Syncmoney 架構概覽

> **目標受眾**：為 Syncmoney 貢獻或擴展的開發者
> **版本**：1.1.0
> **最後更新**：2026-03-19

---

## 目錄

1. [系統架構](#系統架構)
2. [模組結構](#模組結構)
3. [資料流程](#資料流程)
4. [執行緒模型](#執行緒模型)
5. [儲存架構](#儲存架構)
6. [網頁後端架構](#網頁後端架構)
7. [前端架構](#前端架構)

---

## 系統架構

Syncmoney 遵循 **事件驅動、樂觀更新** 的架構，靈感來自 LMAX Disruptor 模式。核心設計原則是**永遠不在主伺服器執行緒上阻塞 Redis/DB 操作**。

### 高層架構

```
┌─────────────────────────────────────────────────────────────┐
│                    Minecraft 伺服器                          │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Vault API 層                                        │   │
│  │  ┌──────────────────────────────────────────────┐    │   │
│  │  │  SyncmoneyVaultProvider (1276 行)            │    │   │
│  │  │  - 實作 net.milkbowl.vault.Economy           │    │   │
│  │  │  - 透過 NameResolver 解析名稱 → UUID         │    │   │
│  │  │  - 銀行帳戶支援（Redis 備份）                │    │   │
│  │  └─────────────────────┬────────────────────────┘    │   │
│  └────────────────────────┼──────────────────────────────┘   │
│                           ▼                                   │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  經濟核心                                            │  │
│  │  ┌────────────────┐  ┌──────────────────────────────┐ │  │
│  │  │ EconomyMode    │  │ EconomyModeRouter (216 行)   │ │  │
│  │  │ Router         │──│ LOCAL → LocalEconomyHandler   │ │  │
│  │  │                │  │ SYNC  → EconomyFacade        │ │  │
│  │  │                │  │ CMI   → CMIEconomyHandler    │ │  │
│  │  │                │  │ LOCAL_REDIS → EconomyFacade  │ │  │
│  │  └────────────────┘  └──────────────┬───────────────┘ │  │
│  │                                      │                  │  │
│  │  ┌──────────────────────────────────▼───────────────┐ │  │
│  │  │ EconomyFacade (1064 行)                          │ │  │
│  │  │ - ConcurrentHashMap<UUID, EconomyState>         │ │  │
│  │  │ - 樂觀鎖定（基於版本）                           │ │  │
│  │  │ - 記憶體優先讀取/寫入                           │ │  │
│  │  │ - 事件佇列（BlockingQueue，容量=50000）         │ │  │
│  │  └──────────────────────────────────┬───────────────┘ │  │
│  └─────────────────────────────────────┼─────────────────┘  │
│                                        │ 非同步事件         │
│  ┌─────────────────────────────────────▼─────────────────┐  │
│  │  非同步持久化層                                       │  │
│  │  ┌────────────────┐  ┌────────────────────────────┐   │  │
│  │  │ EconomyEvent   │  │ CrossServerSyncManager      │   │  │
│  │  │ Consumer       │  │ - Redis Pub/Sub 發布        │   │  │
│  │  │（單一執行緒）   │  │ - 通知其他伺服器           │   │  │
│  │  └───────┬────────┘  └────────────────────────────┘   │  │
│  │          │                                            │  │
│  │  ┌───────▼────────┐  ┌────────────────────────────┐   │  │
│  │  │ Redis (Jedis)  │  │ 資料庫 (HikariCP)           │   │  │
│  │  │ - 餘額快取     │  │ - MySQL/PostgreSQL/SQLite  │   │  │
│  │  │ - Pub/Sub      │  │ - DbWriteQueue + Consumer  │   │  │
│  │  │ - Lua 腳本     │  │ - 批次寫入                  │   │  │
│  │  │ - ZSET 排行榜  │  │ - 審計日誌持久化            │   │  │
│  │  └────────────────┘  └────────────────────────────┘   │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                               │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  網頁管理後端 (Undertow)                               │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐│  │
│  │  │ REST API │  │ SSE      │  │ 靜態檔案伺服器        ││  │
│  │  │ Handlers │  │ Manager  │  │ (Vue 3 SPA)          ││  │
│  │  └──────────┘  └──────────┘  └──────────────────────┘│  │
│  └────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## 模組結構

Java 程式碼庫（`noietime.syncmoney`）組織成 **27 個套件**，包含 **167 個 Java 類別**（約 36,480 行）：

### 核心層（插件入口）

| 套件 | 檔案 | 用途 |
|---------|-------|---------|
| `(root)` | 2 | `Syncmoney.java`（876 行）、`PluginContext.java`（356 行） |
| `config` | 4 | `SyncmoneyConfig`（1340 行）、`ConfigManager`、`ServerIdentityManager`、`ConfigFieldMetadata` |
| `initialization` | 2 | `PluginInitializationManager`、`Initializable` |

### 經濟層

| 套件 | 檔案 | 用途 |
|---------|-------|---------|
| `economy` | 15 | 核心經濟操作、模式路由、事件處理 |
| `vault` | 1 | `SyncmoneyVaultProvider`（1276 行）— Vault Economy API 包裝器 |

### 儲存層

| 套件 | 檔案 | 用途 |
|---------|-------|---------|
| `storage` | 5 | Redis、快取、儲存協調、轉帳鎖 |
| `storage.db` | 3 | 資料庫管理、寫入佇列、批次寫入器 |

### 同步與事件

| 套件 | 檔案 | 用途 |
|---------|-------|---------|
| `sync` | 5 | 跨伺服器同步、Pub/Sub、防抖 |
| `event` | 7 | Bukkit 事件、內部事件匯流排（`SyncmoneyEventBus`） |

### 安全

| 套件 | 檔案 | 用途 |
|---------|-------|---------|
| `breaker` | 9 | 斷路器、玩家守衛、Discord Webhook、資源監控 |
| `guard` | 1 | `PlayerTransferGuard` — 伺服器傳送保護 |
| `permission` | 2 | 權限管理、管理員分級服務 |

### 審計與影子同步

| 套件 | 檔案 | 用途 |
|---------|-------|---------|
| `audit` | 10 | 審計日誌記錄、清理、匯出、Lua 腳本、混合管理器 |
| `shadow` | 6 | 背景同步、CMI 寫入器、回滾保護 |
| `shadow.storage` | 6 | 多資料庫影子儲存實作 |

### 指令

| 套件 | 檔案 | 用途 |
|---------|-------|---------|
| `command` | 19 | 所有指令、冷卻、付款確認/執行 |
| `baltop` | 3 | 排行榜指令、管理器、資料模型 |

### 網頁管理

| 套件 | 檔案 | 用途 |
|---------|-------|---------|
| `web` | 3 | 服務管理、模組設定、網頁管理設定 |
| `web.server` | 2 | Undertow 伺服器（`WebAdminServer` 682 行）、路由註冊 |
| `web.security` | 4 | 認證過濾器、速率限制器、權限檢查器、節點 API 金鑰儲存 |
| `web.api.*` | 14 | REST API 處理器（見[網頁後端架構](#網頁後端架構)） |
| `web.builder` | 3 | 前端自動下載/建構/版本檢查 |
| `web.websocket` | 2 | WebSocket + SSE 管理器 |

### 工具

| 套件 | 檔案 | 用途 |
|---------|-------|---------|
| `util` | 10 | 格式化、訊息、JSON、平台偵測、設定合併 |
| `uuid` | 1 | 名稱 → UUID 解析 |
| `listener` | 5 | 玩家加入/離開、CMI 經濟、事件監聽器管理 |
| `exception` | 3 | 自訂例外階層 |
| `schema` | 1 | 資料庫結構管理 |
| `migration` | 9 | CMI 遷移、備份、斷點續傳、local-to-sync |

---

## 資料流程

### 讀取路徑（餘額查詢）

```
VaultAPI.getBalance(player)
  → SyncmoneyVaultProvider.getBalance()
    → NameResolver: playerName → UUID
    → EconomyFacade.getBalance(uuid)
      → 1. 檢查 ConcurrentHashMap（O(1)）→ 命中 → 返回
      → 2. 檢查 Redis（GET syncmoney:balance:{uuid}）→ 命中 → 快取 + 返回
      → 3. 查詢資料庫（SELECT balance FROM players）→ 命中 → 快取 + 返回
      → 4. 檢查 LocalSQLite（回退）→ 如果找不到返回 0
```

*注意：內部來自玩家指令的餘額查詢（例如 `/money`）會將此讀取路徑包裝在非同步任務（`AsyncScheduler`）中，以確保快取未命中（回退到 Redis/資料庫）絕對不會阻塞主伺服器執行緒。*

### 寫入路徑（存款/提款）

```
VaultAPI.depositPlayer(player, amount)
  → SyncmoneyVaultProvider.depositPlayer()
    → EconomyFacade.deposit(uuid, amount, source)
      → 1. 檢查斷路器 → 已鎖定 → 拒絕
      → 2. 檢查玩家保護 → 已鎖定 → 拒絕
      → 3. 更新 ConcurrentHashMap（樂觀鎖定 + 版本遞增）
      → 4. 立即返回 SUCCESS（不阻塞！）
      → 5. 將 EconomyEvent 加入 BlockingQueue
        → EconomyEventConsumer（背景執行緒）：
          → Redis: EVAL atomic_add_balance.lua
          → 資料庫: 透過 DbWriteQueue 插入/更新（回退：直接 DB 寫入 → WAL 溢位日誌）
          → Pub/Sub: 向其他伺服器發布餘額更新
          → 審計: 記錄交易
          → EventBus: 觸發 PostTransactionEvent
```

### 跨伺服器同步

```
伺服器 A：玩家存款 1000
  → Pub/Sub 發布：{uuid, newBalance, version, source}
    → 伺服器 B 透過 PubsubSubscriber 接收
      → EconomyFacade.updateMemoryState(uuid, balance, version)
        → 版本檢查：僅在新版本 > 目前版本時更新
        → 更新 ConcurrentHashMap
        → 玩家 UI 刷新（透過 EntityScheduler）
```

---

## 執行緒模型

### Folia 相容性

Syncmoney 完全相容於 Folia 的區域化執行緒模型。**嚴格禁止使用 `Bukkit.getScheduler()`。**

| 排程器 | 用途 |
|-----------|-------|
| `AsyncScheduler` | Redis/DB 通訊、寫入佇列處理、Pub/Sub 訂閱 |
| `EntityScheduler` | 玩家 UI 更新（計分板、ActionBar）— 必須在玩家的區域執行緒上執行 |
| `GlobalRegionScheduler` | 定期任務（清理、影子同步、心跳） |

### 關鍵執行緒安全

- `EconomyFacade.economyStates` — `ConcurrentHashMap<UUID, EconomyState>`
- `EconomyState.balance` — 透過樂觀鎖定（版本比較）更新
- `EconomyEventConsumer` — 單一背景執行緒（單一寫入者模式）。啟動時重播溢位事件。
- `PluginContext` — 建構後不可變

---

## 儲存架構

### Redis 鍵

| 鍵模式 | 類型 | 用途 |
|-------------|------|---------|
| `syncmoney:balance:{uuid}` | STRING | 玩家餘額 |
| `syncmoney:version:{uuid}` | STRING | 版本計數器（用於樂觀鎖定） |
| `syncmoney:baltop` | ZSET | 排行榜有序集合 |
| `syncmoney:bank:{name}` | STRING | 銀行帳戶餘額 |
| `syncmoney:bank:version:{name}` | STRING | 銀行版本計數器 |
| `syncmoney:audit:{uuid}` | LIST | 審計日誌條目 |

### 資料庫資料表

| 資料表 | 用途 |
|-------|---------|
| `players` | 玩家餘額（UUID → DECIMAL(20,2)） |
| `syncmoney_audit_log` | 交易審計軌跡（帶毫秒序列排序） |

### Lua 腳本（7 個腳本）

| 腳本 | 用途 |
|--------|---------|
| `atomic_add_balance.lua` | 帶版本遞增的原子存款/提款 |
| `atomic_set_balance.lua` | 原子餘額設定 |
| `atomic_transfer.lua` | 原子玩家對玩家轉帳 |
| `atomic_audit.lua` | 原子審計日誌條目 |
| `atomic_bank_deposit.lua` | 原子銀行存款 |
| `atomic_bank_withdraw.lua` | 原子銀行提款 |
| `atomic_bank_transfer.lua` | 原子銀行對銀行轉帳 |

---

## 網頁後端架構

### Undertow 伺服器

網頁管理執行嵌入式 Undertow HTTP 伺服器（非阻塞、基於 XNIO）。

```
WebAdminServer (682 行)
├── 靜態檔案服務（從 dist/ 提供 Vue 3 SPA）
├── REST API 路由 (/api/*)
│   ├── SystemApiHandler         → /api/system/*
│   ├── EconomyApiHandler        → /api/economy/*
│   ├── AuditApiHandler          → /api/audit/*
│   ├── ConfigApiHandler         → /api/config/*
│   ├── SettingsApiHandler       → /api/settings/*
│   ├── WsTokenHandler          → /api/auth/ws-token
│   ├── NodesApiHandler         → /api/nodes/*（中央模式）
│   ├── CrossServerStatsApiHandler → /api/economy/cross-server-*
│   └── ApiExtensionManager      → /api/extensions/{name}/*
├── SSE 管理器 (/sse)
├── WebSocket 管理器 (/ws)（v1.1.0 部分實作）
└── 健康端點 (/health)
```

### REST API 處理器套件

| 套件 | 處理器 | 路由 |
|---------|---------|--------|
| `web.api.system` | `SystemApiHandler` | `/api/system/status`、`/api/system/redis`、`/api/system/breaker`、`/api/system/metrics` |
| `web.api.economy` | `EconomyApiHandler` | `/api/economy/stats`、`/api/economy/player/{uuid}/balance`、`/api/economy/top` |
| `web.api.audit` | `AuditApiHandler` | `/api/audit/player/{name}`、`/api/audit/search`、`/api/audit/search-cursor`、`/api/audit/stats` |
| `web.api.config` | `ConfigApiHandler` | `/api/config`、`/api/config/reload`、`/api/config/validate` |
| `web.api.settings` | `SettingsApiHandler` | `/api/settings`、`/api/settings/theme`、`/api/settings/language`、`/api/settings/timezone` |
| `web.api.auth` | `WsTokenHandler` | `/api/auth/ws-token` |
| `web.api.nodes` | `NodesApiHandler` | `/api/nodes`、`/api/nodes/status`、`/api/nodes/{index}`、`/api/nodes/{index}/ping` |
| `web.api.crossserver` | `CrossServerStatsApiHandler` | `/api/economy/cross-server-stats`、`/api/economy/cross-server-top` |
| `web.api.extension` | `ApiExtensionManager` | `/api/extensions/{name}/*`（動態） |

### 安全層

1. **ApiKeyAuthFilter** — Bearer 權杖驗證
2. **RateLimiter** — 每 IP 請求限制（預設：60/分鐘）
3. **PermissionChecker** — 端點層級權限驗證

---

## 前端架構

### 技術棧

- **Vue 3**（Composition API，`<script setup>`）
- **Pinia 3**（狀態管理）
- **Vue Router 5**（帶認證守衛的 SPA 路由）
- **Axios**（帶攔截器的 HTTP 客戶端）
- **TailwindCSS 3**（工具優先 CSS）
- **vue-i18n 11**（國際化）
- **Vite 6**（建構工具，帶 PWA 插件）

### 狀態管理（Pinia Stores）

| Store | 用途 |
|-------|---------|
| `auth` | 認證狀態（localStorage 中的 API 金鑰） |
| `notification` | Toast 通知佇列 |
| `settings` | 主題、語言、時區偏好 |
| `sse` | SSE 連線生命週期管理 |

### API 整合

```
api/client.ts（Axios 實例）
├── 請求攔截器：添加 Bearer 權杖
├── 回應攔截器：401→登入、403/429/500→通知
│
services/
├── systemService.ts    → /api/system/*
├── economyService.ts   → /api/economy/*
├── auditService.ts     → /api/audit/*
└── configService.ts    → /api/config/*
│
composables/
├── useSystem.ts        → 系統狀態輪詢
├── useAudit.ts         → 審計日誌分頁
├── useSSE.ts           → SSE 事件處理（帶指數退避和抖動）
└── useWebSocket.ts      → WebSocket 連線（帶指數退避和抖動）
```

### 建構與部署

```bash
# 開發（帶 HMR + API 代理至 :8080）
pnpm dev

# 生產建構（輸出至 dist/）
pnpm build

# dist/ 會複製到 src/main/resources/syncmoney-web/
# 並透過 Gradle processResources 嵌入 JAR
```
