# Syncmoney API 參考文檔

Syncmoney v1.1.1 完整 API 參考文檔

> **最後更新**：2026-03-21

---

## 目錄

1. [認證](#認證)
2. [API 回應格式](#api-回應格式)
3. [REST 端點](#rest-端點)
   - [健康檢查](#健康檢查)
   - [系統](#系統)
   - [經濟](#經濟)
   - [節點（中央模式）](#節點（中央模式）)
   - [跨伺服器統計](#跨伺服器統計)
   - [設定](#設定)
   - [審計日誌](#審計日誌)
   - [設定值](#設定值)
   - [認證](#認證-1)
4. [即時事件](#即時事件)
   - [Server-Sent Events (SSE)](#server-sent-events-(sse)-—-推薦)
   - [WebSocket](#websocket)
5. [API 擴展框架](#api-擴展框架)
6. [錯誤回應](#錯誤回應)
7. [速率限制](#速率限制)
8. [最佳實踐](#最佳實踐)

---

## 認證

大多數 API 端點需要使用 API 金鑰進行認證。`/health` 端點為公開端點，無需認證。

### 請求頭

```
Authorization: Bearer <your-api-key>
```

API 金鑰在 `config.yml` 中設定：

```yaml
web-admin:
  security:
    api-key: "your-secure-api-key-here"
```

### 權限系統

API 使用基於權限的授權系統：

| 權限 | 說明 |
|------------|-------------|
| `syncmoney.web.nodes.view` | 檢視節點資訊 |
| `syncmoney.web.nodes.manage` | 創建、更新、刪除節點 |
| `syncmoney.web.central` | 存取中央模式功能 |

權限透過遊戲的權限系統（LuckPerms、PermissionsEx 等）進行分配。

### 回應碼

| 碼 | 說明 |
|------|-------------|
| 200 | 成功 |
| 400 | 錯誤請求（驗證錯誤） |
| 401 | 未授權（API 金鑰無效） |
| 403 | 禁止（權限不足） |
| 404 | 找不到 |
| 429 | 速率限制 |
| 500 | 內部伺服器錯誤 |

---

## API 回應格式

所有成功回應遵循以下格式：

```json
{
  "success": true,
  "data": { ... },
  "meta": {
    "timestamp": 1709337000000,
    "version": "1.1.1"
  }
}
```

所有錯誤回應遵循以下格式：

```json
{
  "success": false,
  "error": {
    "code": "ERROR_CODE",
    "message": "Human readable message"
  },
  "meta": {
    "timestamp": 1709337000000,
    "version": "1.1.1"
  }
}
```

### 分頁（基於偏移量）

```json
{
  "success": true,
  "data": [ ... ],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "totalItems": 100,
    "totalPages": 5
  },
  "meta": {
    "timestamp": 1709337000000,
    "version": "1.1.1"
  }
}
```

### 分頁（基於游標）

```json
{
  "success": true,
  "data": [ ... ],
  "pagination": {
    "nextCursor": "1709337000000,3",
    "hasMore": true,
    "pageSize": 20
  }
}
```

> **游標格式**：`<timestamp>,<sequence>` — 用於審計日誌分頁，其中 Redis + DB 記錄會被合併和去重。

---

## REST 端點

### 健康檢查

#### GET /health

健康檢查端點（**無需認證**）。

**回應：**
```json
{
  "success": true,
  "data": {
    "status": "ok",
    "version": "1.1.1"
  }
}
```

---

### 系統

#### GET /api/system/status

取得整體系統狀態，包括插件資訊、運行時間、玩家數量和資料庫狀態。

**回應：**
```json
{
  "success": true,
  "data": {
    "plugin": {
      "name": "Syncmoney",
      "version": "1.1.1",
      "mode": "SYNC",
      "uptime": 3600000
    },
    "economy": {
      "mode": "SYNC",
      "currencyName": "dollars"
    },
    "redis": {
      "connected": true,
      "enabled": true
    },
    "database": {
      "connected": true,
      "enabled": true,
      "type": "mysql"
    },
    "circuitBreaker": {
      "enabled": true,
      "state": "NORMAL",
      "lastTrigger": null
    },
    "serverName": "lobby",
    "onlinePlayers": 15,
    "maxPlayers": 100
  }
}
```

**circuitBreaker.state 可能值：** `NORMAL`、`WARNING`、`LOCKED`

---

#### GET /api/system/redis

取得 Redis 連線狀態。

**回應：**
```json
{
  "success": true,
  "data": {
    "connected": true,
    "enabled": true
  }
}
```

---

#### GET /api/system/breaker

取得斷路器狀態。

**回應：**
```json
{
  "success": true,
  "data": {
    "enabled": true,
    "state": "NORMAL"
  }
}
```

**可能狀態：** `NORMAL`、`WARNING`、`LOCKED`

---

#### GET /api/system/metrics

取得系統指標，包括記憶體使用量、執行緒數量和 TPS。

**回應：**
```json
{
  "success": true,
  "data": {
    "memory": {
      "total": 536870912,
      "free": 268435456,
      "used": 268435456,
      "max": 1073741824
    },
    "threads": 45,
    "tps": 20.0
  }
}
```

---

### 經濟

#### GET /api/economy/stats

取得經濟統計資料，包括總供應量、玩家數量和交易資料。

**回應：**
```json
{
  "success": true,
  "data": {
    "totalSupply": 1500000.50,
    "totalPlayers": 250,
    "todayTransactions": 42,
    "cachedPlayers": 15,
    "currencyName": "$"
  }
}
```

---

#### GET /api/economy/player/{uuid}/balance

透過 UUID 取得指定玩家的當前餘額。

**參數：**
- `uuid`（路徑，必填）— 玩家 UUID（例如 `a1b2c3d4-e5f6-7890-abcd-ef1234567890`）

**回應：**
```json
{
  "success": true,
  "data": {
    "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "balance": 5000.00,
    "currencyName": "$"
  }
}
```

**錯誤碼：**
- `PLAYER_UUID_REQUIRED` — UUID 參數缺失
- `INVALID_UUID` — UUID 格式無效

---

#### GET /api/economy/top

取得餘額排名前 10 的玩家（排行榜）。

**回應：**
```json
{
  "success": true,
  "data": {
    "topPlayers": [
      { "rank": 1, "uuid": "a1b2c3d4-...", "balance": 99999.00 },
      { "rank": 2, "uuid": "e5f6a7b8-...", "balance": 75000.00 }
    ],
    "currencyName": "$"
  }
}
```

---

### 節點（中央模式）

這些端點在設定中啟用中央模式時可用。

#### GET /api/nodes

取得所有已設定節點的列表。

**回應：**
```json
{
  "success": true,
  "data": {
    "nodes": [
      {
        "index": 0,
        "name": "Survival Server 1",
        "url": "http://192.168.1.105:8080",
        "enabled": true,
        "status": "online"
      }
    ],
    "centralMode": true,
    "total": 1,
    "selfUrl": null
  }
}
```

**需要權限：** `syncmoney.web.nodes.view`

---

#### GET /api/nodes/status

取得所有節點的詳細狀態（僅中央模式）。

**回應：**
```json
{
  "success": true,
  "data": {
    "http://192.168.1.105:8080": {
      "serverName": "Survival-1",
      "serverId": "survival-1",
      "onlinePlayers": 42,
      "maxPlayers": 100,
      "economyMode": "SYNC",
      "status": "online",
      "lastPing": 1711111111111
    }
  }
}
```

| 欄位 | 類型 | 說明 |
|-------|------|------|
| `serverName` | String | 伺服器顯示名稱 |
| `serverId` | String | 伺服器唯一識別碼 |
| `onlinePlayers` | Integer | 目前線上玩家數 |
| `maxPlayers` | Integer | 最大玩家容量 |
| `economyMode` | String | 經濟模式（LOCAL、SYNC、CMI 等） |
| `status` | String | 連線狀態（online/offline/unknown/disabled） |
| `lastPing` | Long | 最後成功 ping 的時間戳 |

**需要權限：** `syncmoney.web.nodes.view`

---

#### POST /api/nodes

創建新節點。

**請求主體：**
```json
{
  "name": "New Server",
  "url": "http://192.168.1.106:8080",
  "apiKey": "your-api-key",
  "enabled": true
}
```

**回應：**
```json
{
  "success": true,
  "data": {
    "index": 1,
    "name": "New Server",
    "url": "http://192.168.1.106:8080",
    "enabled": true,
    "status": "offline"
  }
}
```

**需要權限：** `syncmoney.web.nodes.manage`

---

#### PUT /api/nodes/{index}

更新現有節點。

**參數：**
- `index`（路徑，必填）— 要更新的節點索引

**請求主體：**
```json
{
  "name": "Updated Server Name",
  "url": "http://192.168.1.107:8080",
  "apiKey": "new-api-key",
  "enabled": false
}
```

**回應：**
```json
{
  "success": true,
  "data": {
    "index": 0,
    "name": "Updated Server Name",
    "url": "http://192.168.1.107:8080",
    "enabled": false,
    "status": "online"
  }
}
```

**需要權限：** `syncmoney.web.nodes.manage`

---

#### DELETE /api/nodes/{index}

刪除節點。

**參數：**
- `index`（路徑，必填）— 要刪除的節點索引

**回應：**
```json
{
  "success": true,
  "data": {
    "deleted": true,
    "index": 0,
    "deletedUrl": "http://192.168.1.105:8080"
  }
}
```

**需要權限：** `syncmoney.web.nodes.manage`

---

#### POST /api/nodes/{index}/ping

手動 ping 指定節點。

**參數：**
- `index`（路徑，必填）— 要 ping 的節點索引

**回應：**
```json
{
  "success": true,
  "data": {
    "index": 0,
    "status": "online",
    "latencyMs": 15
  }
}
```

**需要權限：** `syncmoney.web.nodes.view` 或 `syncmoney.web.central`

---

#### POST /api/nodes/{index}/proxy

將 HTTP 請求代理至遠端節點（僅中央模式）。允許中央節點將 API 請求轉發到其他遊戲伺服器節點。

**參數：**
- `index`（路徑，必填）— 要代理請求的節點索引

**請求 Body：**
```json
{
  "method": "GET",
  "path": "/api/economy/stats",
  "body": null
}
```

**支援的方法：** `GET`、`POST`、`PUT`、`PATCH`、`DELETE`

**回應：**
遠端節點的回應會被直接轉發。HTTP 狀態碼和 body 與目標節點的回應相同。

**需要權限：** `syncmoney.web.nodes.view` 或 `syncmoney.web.central`

---

#### POST /api/nodes/sync

將設定變更推送至所有已啟用的節點（僅中央模式）。此端點僅限中央模式使用。

**請求 Body：**
```json
{
  "changes": [
    { "section": "economy", "key": "pay.max-amount", "value": 100000 },
    { "section": "messages", "key": "prefix", "value": "[Syncmoney]" }
  ],
  "reload": true
}
```

| 欄位 | 類型 | 必填 | 說明 |
|-------|------|------|------|
| `changes` | Array | 是 | 要套用的設定變更清單 |
| `changes[].section` | String | 是 | 設定區段（例如 `economy`、`messages`） |
| `changes[].key` | String | 是 | 設定鍵（支援點標記法） |
| `changes[].value` | Any | 是 | 新值 |
| `reload` | Boolean | 否 | 是否在套用後重新載入設定（預設：`true`） |

**回應：**
```json
{
  "success": true,
  "data": {
    "total": 3,
    "succeeded": 2,
    "failed": 1,
    "results": [
      { "index": 0, "name": "Survival-1", "status": "synced" },
      { "index": 1, "name": "Factions-1", "status": "failed", "error": "http_403" }
    ]
  }
}
```

**需要權限：** `syncmoney.web.central`

---

#### POST /api/nodes/{index}/sync

將設定變更推送至指定節點（僅中央模式）。

**參數：**
- `index`（路徑，必填）— 要同步設定的節點索引

**請求 Body：** 與 `POST /api/nodes/sync` 相同

**回應：**
```json
{
  "success": true,
  "data": {
    "applied": 1,
    "reloaded": true,
    "changes": ["economy.pay.max-amount=100000"]
  }
}
```

**需要權限：** `syncmoney.web.central`

---

#### POST /api/config/sync

接收來自中央節點的設定同步（節點端點）。此端點用於節點接收來自中央管理伺服器的設定。

**請求 Body：**
```json
{
  "changes": [
    { "section": "economy", "key": "pay.max-amount", "value": 100000 }
  ],
  "reload": true
}
```

**回應：**
```json
{
  "success": true,
  "data": {
    "applied": 1,
    "reloaded": true,
    "changes": ["economy.pay.max-amount=100000"]
  }
}
```

---

### 跨伺服器統計

在中央模式下跨所有伺服器的聚合統計。

#### GET /api/economy/cross-server-stats

取得所有節點的聚合統計（僅中央模式）。

**回應：**
```json
{
  "success": true,
  "data": {
    "totalSupply": 5000000.00,
    "totalPlayers": 5000,
    "totalOnlinePlayers": 150,
    "todayTransactions": 2000,
    "nodesStatus": {
      "Survival-1": 50,
      "Factions-1": 60,
      "SMP-1": 40
    },
    "source": "aggregated",
    "timestamp": 1711111111111
  }
}
```

**需要權限：** `syncmoney.web.central`

---

#### GET /api/economy/cross-server-top

取得聚合的跨伺服器排行榜。

**回應：**
```json
{
  "success": true,
  "data": {
    "topPlayers": [
      {
        "rank": 1,
        "uuid": "a1b2c3d4-...",
        "playerName": "Steve",
        "balance": 99999.00,
        "serverName": "Survival-1"
      }
    ],
    "currencyName": "$"
  }
}
```

**需要權限：** `syncmoney.web.central`

---

### 設定

#### GET /api/config

取得當前插件設定（敏感資料已隱藏）。

**回應：**
```json
{
  "success": true,
  "data": {
    "redis": {
      "enabled": true,
      "host": "localhost",
      "port": 6379,
      "password": "***HIDDEN***"
    },
    "database": {
      "type": "mysql",
      "host": "localhost",
      "port": 3306,
      "database": "syncmoney"
    },
    "circuitBreaker": {
      "enabled": true
    },
    "audit": {
      "enabled": true,
      "batchSize": 1
    },
    "economy": {
      "currencyName": "$",
      "currencySymbol": "$",
      "decimalPlaces": 2
    }
  }
}
```

---

#### PUT /api/config

批次更新設定。

**請求主體（格式 1 — 批次變更）：**
```json
{
  "changes": [
    { "section": "economy", "key": "currencyName", "value": "coins" },
    { "section": "audit", "key": "batchSize", "value": 10 }
  ]
}
```

**請求主體（格式 2 — 單一變更）：**
```json
{
  "section": "economy",
  "key": "currencyName",
  "value": "coins"
}
```

**可選：儲存後觸發熱重載：**
```json
{
  "changes": [...],
  "hotReload": true
}
```

**回應：**
```json
{
  "success": true,
  "data": {
    "message": "Configuration saved successfully",
    "hotReload": true
  }
}
```

---

#### POST /api/config/validate

在儲存前驗證設定值。

**請求主體：**
```json
{
  "section": "economy",
  "key": "currencyName",
  "value": "coins"
}
```

**回應（有效）：**
```json
{
  "success": true,
  "data": {
    "valid": true
  }
}
```

**回應（無效）：**
```json
{
  "success": false,
  "error": {
    "code": "INVALID_CONFIG",
    "message": "Invalid value for currencyName"
  }
}
```

---

#### POST /api/config/reload

從磁碟重載插件設定。

**回應：**
```json
{
  "success": true,
  "data": {
    "message": "Configuration reloaded successfully"
  }
}
```

---

### 審計日誌

審計 API 支援兩種分頁模式：
1. **基於偏移量**（`page` + `pageSize`）— 用於簡單的頁面導航
2. **基於游標**（`cursor` + `pageSize`）— 用於 Redis + DB 混合查詢的高效無限滾動

處理器從**三個優先來源**查詢資料：
1. `HybridAuditManager`（Redis 即時 + DB 歷史，合併和去重）
2. `LocalEconomyHandler`（LOCAL 模式 SQLite 回退）
3. `AuditLogger`（基本 DB 查詢回退）

---

#### GET /api/audit/player/{name}

取得特定玩家的審計記錄。

**參數：**
- `name`（路徑，必填）— 玩家名稱
- `page`（查詢，可選）— 頁碼（預設：1）
- `pageSize`（查詢，可選）— 每頁項目數（預設：20，最大：100）

**回應：**
```json
{
  "success": true,
  "data": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "timestamp": 1709337000000,
      "sequence": 0,
      "type": "DEPOSIT",
      "playerUuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "playerName": "Steve",
      "amount": "1000",
      "balanceAfter": "6000",
      "source": "VAULT",
      "serverName": "lobby"
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "totalItems": 1,
    "totalPages": 1
  }
}
```

**審計記錄欄位：**

| 欄位 | 類型 | 說明 |
|-------|------|-------------|
| `id` | string | 唯一記錄 ID（UUID） |
| `timestamp` | number | Epoch 毫秒 |
| `sequence` | number | 序列號（用於同一毫秒內的排序） |
| `type` | string | `DEPOSIT`、`WITHDRAW`、`TRANSFER`、`SET_BALANCE`、`CRITICAL_FAILURE` |
| `playerUuid` | string | 玩家 UUID |
| `playerName` | string | 玩家名稱 |
| `amount` | string | 交易金額（帶符號：存款為正，提款為負） |
| `balanceAfter` | string | 交易後餘額 |
| `source` | string | 事件來源（見下表） |
| `serverName` | string | 處理交易的伺服器 |
| `targetUuid` | string? | 目標玩家 UUID（僅轉帳） |
| `targetName` | string? | 目標玩家名稱（僅轉帳） |
| `reason` | string? | 交易原因（可選） |
| `mergedCount` | number? | 合併的交易次數（僅在 > 1 時出現） |

**事件來源值：**

| 來源 | 說明 |
|--------|-------------|
| `VAULT` | 透過 Vault API（其他插件） |
| `COMMAND_PAY` | `/pay` 指令 |
| `COMMAND_ADMIN` | 管理員指令 |
| `ADMIN_GIVE` | `/syncmoney admin give` |
| `ADMIN_TAKE` | `/syncmoney admin take` |
| `ADMIN_SET` | `/syncmoney admin set` |
| `REDIS_SYNC` | 跨伺服器同步 |
| `PLAYER_TRANSFER` | 玩家對玩家轉帳 |
| `CMI_SYNC` | CMI 經濟同步 |
| `MIGRATION` | 資料遷移 |

**錯誤碼：**
- `INVALID_PLAYER` — 玩家名稱為空
- `PLAYER_NOT_FOUND` — 在伺服器快取中找不到玩家

---

#### GET /api/audit/search

使用篩選條件搜尋審計記錄（基於偏移量的分頁）。

**參數：**
- `player`（查詢，可選）— 按玩家名稱篩選
- `type`（查詢，可選）— 按類型篩選：`DEPOSIT`、`WITHDRAW`、`TRANSFER`、`SET_BALANCE`、`all`
- `startTime`（查詢，可選）— 開始時間（Epoch 毫秒）
- `endTime`（查詢，可選）— 結束時間（Epoch 毫秒）
- `page`（查詢，可選）— 頁碼（預設：1）
- `pageSize`（查詢，可選）— 每頁項目數（預設：20，最大：100）
- `cursor`（查詢，可選）— 如果提供，則切換到基於游標的分頁（見下文）

**範例：**
```
GET /api/audit/search?player=Steve&type=DEPOSIT&page=1&pageSize=20
```

**回應：**
```json
{
  "success": true,
  "data": [ ... ],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "totalItems": 42,
    "totalPages": 3
  }
}
```

> **注意：** 當存在 `cursor` 查詢參數時，此端點自動委派給基於游標的分頁模式（與 `/api/audit/search-cursor` 相同行為）。

---

#### GET /api/audit/search-cursor

使用基於游標的分頁搜尋審計記錄。此端點合併**Redis（即時）和資料庫（歷史）**的資料，按記錄 ID 去重，並按最新優先排序。

**參數：**
- `cursor`（查詢，可選）— 來自上一個回應的 `pagination.nextCursor`。首次取得時為空或省略。
- `pageSize`（查詢，可選）— 每頁項目數（預設：20，最大：100）
- `player`（查詢，可選）— 按玩家名稱**或 UUID** 篩選
- `type`（查詢，可選）— 按審計類型篩選：`DEPOSIT`、`WITHDRAW`、`TRANSFER`、`SET_BALANCE`
- `startTime`（查詢，可選）— 開始時間篩選（Epoch 毫秒）
- `endTime`（查詢，可選）— 結束時間篩選（Epoch 毫秒）

**玩家解析：** `player` 參數接受玩家名稱和 UUID 字串。名稱解析順序：線上玩家 → 離線快取 → `NameResolver`（Redis + DB）。如果無法解析，返回空結果。

**範例（第一頁）：**
```
GET /api/audit/search-cursor?pageSize=20
```

**範例（下一頁）：**
```
GET /api/audit/search-cursor?cursor=1709337000000,3&pageSize=20
```

**回應：**
```json
{
  "success": true,
  "data": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "timestamp": 1709337000000,
      "sequence": 3,
      "type": "DEPOSIT",
      "playerUuid": "a1b2c3d4-...",
      "playerName": "Steve",
      "amount": "1000",
      "balanceAfter": "6000",
      "source": "VAULT",
      "serverName": "lobby"
    }
  ],
  "pagination": {
    "nextCursor": "1709336500000,0",
    "hasMore": true,
    "pageSize": 20
  }
}
```

**游標格式：** `<timestamp>,<sequence>` — 返回比游標時間戳更舊的記錄（或相同時間戳但序列號更低）。

**去重：** 當同一記錄同時存在於 Redis 和資料庫時，Redis 版本優先（按記錄 ID 先到先得）。

---

#### GET /api/audit/stats

取得審計模組統計資料。

**回應：**
```json
{
  "success": true,
  "data": {
    "bufferSize": 0,
    "enabled": true
  }
}
```

---

### 設定值

#### GET /api/settings

取得當前使用者設定（主題、語言、時區）。

**回應：**
```json
{
  "success": true,
  "data": {
    "theme": "dark",
    "language": "zh-TW",
    "timezone": "Asia/Taipei"
  }
}
```

---

#### POST /api/settings/theme

更新主題偏好。

**請求主體：**
```json
{
  "theme": "dark"
}
```

**有效值：** `dark`、`light`

**回應：**
```json
{
  "success": true,
  "data": {
    "theme": "dark"
  }
}
```

**錯誤：** `INVALID_THEME` — 主題必須是 `dark` 或 `light`

---

#### POST /api/settings/language

更新語言偏好。

**請求主體：**
```json
{
  "language": "zh-TW"
}
```

**有效值：** `zh-TW`、`en-US`

**回應：**
```json
{
  "success": true,
  "data": {
    "language": "zh-TW"
  }
}
```

**錯誤：** `INVALID_LANGUAGE` — 語言必須是 `zh-TW` 或 `en-US`

---

#### POST /api/settings/timezone

更新時區偏好。

**請求主體：**
```json
{
  "timezone": "Asia/Taipei"
}
```

**有效值：** 任何有效的 IANA 時區識別符（例如 `UTC`、`Asia/Taipei`、`America/New_York`）

**回應：**
```json
{
  "success": true,
  "data": {
    "timezone": "Asia/Taipei"
  }
}
```

**錯誤：** `INVALID_TIMEZONE` — 無效的時區識別符

---

### 認證

#### POST /api/auth/ws-token

生成 WebSocket 連線的一次性工作階段權杖。這樣可以避免直接在 WebSocket URL 中傳遞 API 金鑰。

**請求頭：**
```
Authorization: Bearer <your-api-key>
```

**回應：**
```json
{
  "success": true,
  "data": {
    "token": "550e8400-e29b-41d4-a716-446655440000",
    "expires": 1709337060000,
    "validityMs": 60000
  }
}
```

**權杖屬性：**
- **一次性使用**：權杖在首次成功 WebSocket 連線後失效
- **短期有效**：60 秒後過期
- **常數時間驗證**：使用 `MessageDigest.isEqual()` 防止時序攻擊
- **自動清理**：過期權杖會定期從記憶體中移除

**使用流程：**
```
1. POST /api/auth/ws-token  →  { token: "abc-123" }
2. 連線 WebSocket: ws://host:port/ws?token=abc-123
3. 連線時消耗權杖（無法重複使用）
```

---

## 即時事件

### Server-Sent Events (SSE) — 推薦

連線至 `http://<host>:<port>/sse` 接收伺服器推送通知。**這是 v1.1.1 中推薦的即時更新方法。**

**認證：** 透過查詢參數或 Authorization 請求頭使用 API 金鑰。

```javascript
// 方法 1：查詢參數
const eventSource = new EventSource('http://localhost:8080/sse?apiKey=your-key');

// 方法 2：自訂 EventSource 帶請求頭（需要 polyfill）
const eventSource = new EventSource('http://localhost:8080/sse', {
  headers: { 'Authorization': 'Bearer your-key' }
});

eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data);
    console.log('SSE event:', data);
};

eventSource.onerror = (error) => {
    console.error('SSE connection error:', error);
    // 自動重連由瀏覽器處理
};
```

**透過 SSE 推送的事件類型：**

| `type` 值 | 觸發時機 | 主要 `data` 欄位 |
|---|---|---|
| `connected` | 客戶端連線 | `message` |
| `transaction` | `PostTransactionEvent` 觸發 | `playerName`、`type`、`amount`、`balanceBefore`、`balanceAfter`、`success`、`timestamp`（epoch ms） |
| `circuit_break` | `TransactionCircuitBreakEvent` 觸發 | `previousState`、`currentState`、`reason`、`message` |
| `system_alert` | 手動廣播 / 內部警報 | `level`、`message` |

---

### WebSocket

> **v1.1.1 狀態：** WebSocket 支援**實驗性且不完整**。`/ws` 端點接受升級請求，但底層訊息分派尚未完全實作。**在生產環境中使用 SSE（`/sse`）以獲得可靠的即時更新。**

連線至 `ws://<host>:<port>/ws` 接收即時更新。

**認證：** 使用來自 `POST /api/auth/ws-token` 的工作階段權杖：

```javascript
// 步驟 1：取得工作階段權杖
const response = await fetch('http://localhost:8080/api/auth/ws-token', {
    method: 'POST',
    headers: { 'Authorization': 'Bearer your-api-key' }
});
const { data: { token } } = await response.json();

// 步驟 2：使用權杖連線
const ws = new WebSocket(`ws://localhost:8080/ws?token=${token}`);

ws.onopen = () => {
    console.log('Connected to Syncmoney WebSocket');
};

ws.onmessage = (event) => {
    const data = JSON.parse(event.data);
    console.log('Event:', data);
};

ws.onerror = (error) => {
    console.error('WebSocket error:', error);
};
```

#### 事件：交易

```json
{
  "type": "transaction",
  "event": "PostTransactionEvent",
  "data": {
    "playerName": "Steve",
    "type": "DEPOSIT",
    "amount": "1000",
    "balanceBefore": "5000",
    "balanceAfter": "6000",
    "success": true,
    "timestamp": 1709337000000
  }
}
```

> **注意：** `timestamp` 是 epoch 毫秒（不是 ISO 字串）。`playerUuid` 不包含在事件負載中。

#### 事件：斷路

```json
{
  "type": "circuit_break",
  "data": {
    "previousState": "WARNING",
    "currentState": "LOCKED",
    "reason": "RATE_LIMIT",
    "message": "Transaction rate limit exceeded",
    "timestamp": 1709337000000
  }
}
```

---

## API 擴展框架

擴展框架允許第三方開發者向 Syncmoney Web API 添加自訂端點。

### 創建擴展

**1. 實作 `ApiExtension` 介面：**

```java
package com.example.syncext;

import noietime.syncmoney.web.api.extension.*;

public class MyExtension implements ApiExtension {

    @Override
    public String getName() {
        return "my-extension";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "My custom extension";
    }

    @Override
    public String getAuthor() {
        return "Your Name";
    }

    @Override
    public void registerRoutes(ApiExtensionRouter router) {
        // 註冊路由：/api/extensions/my-extension/stats
        router.get("stats", exchange -> handleGetStats(exchange));
        router.post("action", exchange -> handlePostAction(exchange));
    }

    @Override
    public void onEnable() {
        // 初始化邏輯
    }

    @Override
    public void onDisable() {
        // 清理邏輯
    }
}
```

**2. 使用 `RequestContext` 方便地處理請求：**

```java
private void handleGetStats(io.undertow.server.HttpServerExchange exchange) {
    RequestContext ctx = new RequestContext(exchange, plugin, getName(), "/stats");

    // 取得查詢參數
    int page = ctx.getQueryParamAsInt("page", 1);
    int pageSize = ctx.getQueryParamAsInt("pageSize", 20);

    // 取得 API 金鑰
    String apiKey = ctx.getApiKey().orElse("");

    // 取得請求主體
    String body = ctx.getBody();

    // 解析 JSON 主體
    var jsonData = ctx.getBodyAsJson();

    // 構建回應
    ctx.respondSuccess(Map.of(
        "stats", myData,
        "page", page
    ));
}
```

**3. 使用 `ResponseBuilder` 流暢地構建回應：**

```java
ResponseBuilder.success()
    .put("player", playerName)
    .put("balance", balance)
    .put("currency", "$")
    .build()
```

**4. 註冊擴展：**

```java
// 在插件的 onEnable() 中
ApiExtensionManager manager = syncmoney.getExtensionManager();
manager.registerExtension(new MyExtension());
```

### 擴展路由模式

所有擴展路由都會自動加上前綴：

```
/api/extensions/{extension-name}/{route}
```

範例：
- 擴展名稱：`my-extension`
- 註冊路由：`stats`
- 完整路徑：`/api/extensions/my-extension/stats`

### 擴展設定

擴展可以從 `config.yml` 讀取設定：

```yaml
web-admin:
  extensions:
    my-extension:
      enabled: true
      setting1: "value1"
```

### 類別參考

| 類別 | 用途 |
|-------|---------|
| `ApiExtension` | 擴展的主要介面 — 定義名稱、版本、路由、生命週期 |
| `ApiExtensionRouter` | 註冊帶路徑模式的 GET/POST/PUT/DELETE 路由 |
| `ApiExtensionManager` | 註冊/取消註冊擴展，管理擴展生命週期 |
| `RequestContext` | 方便地存取查詢參數、主體、請求頭、API 金鑰 |
| `ResponseBuilder` | 流暢的 JSON 回應構建 API |

---

## 錯誤回應

### 未授權 (401)

```json
{
  "success": false,
  "error": {
    "code": "AUTHENTICATION_FAILED",
    "message": "Missing or invalid authorization header"
  }
}
```

### 禁止 (403)

```json
{
  "success": false,
  "error": {
    "code": "NODE_ACCESS_DENIED",
    "message": "Permission denied for node operations"
  }
}
```

### 找不到 (404)

```json
{
  "success": false,
  "error": {
    "code": "NOT_FOUND",
    "message": "Endpoint not found"
  }
}
```

### 速率限制 (429)

```json
{
  "success": false,
  "error": {
    "code": "RATE_LIMITED",
    "message": "Too many requests, please try again later"
  }
}
```

### 伺服器錯誤 (500)

```json
{
  "success": false,
  "error": {
    "code": "INTERNAL_ERROR",
    "message": "Internal server error"
  }
}
```

### 序列化錯誤 (500)

當 API 回應無法序列化為 JSON 時返回：

```json
{
  "success": false,
  "error": {
    "code": "SERIALIZATION_ERROR",
    "message": "Failed to serialize response"
  }
}
```

### 常見錯誤碼

| 碼 | 說明 |
|------|-------------|
| `NODE_ACCESS_DENIED` | 節點操作權限不足 |
| `INVALID_NODE_NAME` | 節點名稱為必填 |
| `INVALID_NODE_URL` | 節點 URL 為必填 |
| `SSRF_BLOCKED` | URL 被 SSRF 防護阻止 |
| `CENTRAL_MODE_DISABLED` | 功能僅在中央模式下可用 |
| `ECONOMY_NOT_AVAILABLE` | 經濟系統不可用 |
| `PLAYER_UUID_REQUIRED` | 玩家 UUID 為必填 |
| `INVALID_UUID` | UUID 格式無效 |
| `INVALID_CONFIG` | 設定值無效 |

---

## 速率限制

API 實作速率限制以防止濫用。預設：**每客戶端 IP 每分鐘 60 個請求**。如果超過速率限制，你將收到 429 回應。

速率限制可以在 `config.yml` 中設定：

```yaml
web-admin:
  security:
    rate-limit:
      enabled: true
      requests-per-minute: 60
```

---

## 最佳實踐

### 1. 始終驗證輸入

```java
private void handleRequest(HttpServerExchange exchange) {
    String param = exchange.getQueryParameters().getFirst("param");
    if (param == null || param.isBlank()) {
        sendError(exchange, 400, "PARAM_REQUIRED", "Parameter 'param' is required");
        return;
    }

    // 繼續處理...
}
```

### 2. 使用適當的 HTTP 方法

| 方法 | 用途 |
|--------|-------|
| GET | 讀取資料，無副作用 |
| POST | 創建資源 |
| PUT | 更新資源 |
| DELETE | 刪除資源 |

### 3. 優雅地處理異常

```java
try {
    // 業務邏輯
    sendJson(exchange, ApiResponse.success(result));
} catch (IllegalArgumentException e) {
    sendError(exchange, 400, "INVALID_INPUT", e.getMessage());
} catch (Exception e) {
    plugin.getLogger().log(Level.WARNING, "Error handling request", e);
    sendError(exchange, 500, "INTERNAL_ERROR", "An unexpected error occurred");
}
```

### 4. 使用常數作為權限名稱

```java
private static final String PERMISSION_VIEW = "syncmoney.web.nodes.view";
private static final String PERMISSION_MANAGE = "syncmoney.web.nodes.manage";
```

### 5. 在日誌中遮罩敏感資料

```java
private String maskApiKey(String key) {
    if (key == null || key.length() <= 8) {
        return "****";
    }
    return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
}
```

### 6. 遵循命名規範

| 元素 | 規範 | 範例 |
|---------|-------------|---------|
| 端點路徑 | 小寫、連字符分隔 | `/api/nodes/status` |
| 錯誤碼 | UPPER_SNAKE | `NODE_NOT_FOUND` |
| 查詢參數 | camelCase | `pageSize`、`startTime` |

---

## 端點摘要

| 方法 | 路徑 | 認證 | 說明 |
|--------|------|------|-------------|
| GET | `/health` | — | 健康檢查 |
| GET | `/api/system/status` | ✓ | 系統狀態 |
| GET | `/api/system/redis` | ✓ | Redis 狀態 |
| GET | `/api/system/breaker` | ✓ | 斷路器狀態 |
| GET | `/api/system/metrics` | ✓ | 系統指標 |
| GET | `/api/economy/stats` | ✓ | 經濟統計 |
| GET | `/api/economy/player/{uuid}/balance` | ✓ | 玩家餘額 |
| GET | `/api/economy/top` | ✓ | 排行榜 |
| GET | `/api/economy/cross-server-stats` | ✓ | 跨伺服器統計（中央） |
| GET | `/api/economy/cross-server-top` | ✓ | 跨伺服器排行（中央） |
| GET | `/api/config` | ✓ | 取得設定 |
| PUT | `/api/config` | ✓ | 更新設定 |
| POST | `/api/config/validate` | ✓ | 驗證設定 |
| POST | `/api/config/reload` | ✓ | 重載設定 |
| GET | `/api/audit/player/{name}` | ✓ | 玩家審計記錄 |
| GET | `/api/audit/search` | ✓ | 搜尋審計（偏移量或游標） |
| GET | `/api/audit/search-cursor` | ✓ | 搜尋審計（基於游標） |
| GET | `/api/audit/stats` | ✓ | 審計統計 |
| GET | `/api/nodes` | ✓ | 列出節點 |
| GET | `/api/nodes/status` | ✓ | 節點詳細狀態 |
| POST | `/api/nodes` | ✓ | 創建節點 |
| PUT | `/api/nodes/{index}` | ✓ | 更新節點 |
| DELETE | `/api/nodes/{index}` | ✓ | 刪除節點 |
| POST | `/api/nodes/{index}/ping` | ✓ | Ping 節點 |
| GET | `/api/settings` | ✓ | 取得設定值 |
| POST | `/api/settings/theme` | ✓ | 更新主題 |
| POST | `/api/settings/language` | ✓ | 更新語言 |
| POST | `/api/settings/timezone` | ✓ | 更新時區 |
| POST | `/api/auth/ws-token` | ✓ | 生成 WebSocket 權杖 |
| GET | `/sse` | ✓ | Server-Sent Events（推薦） |
| GET | `/ws` | 權杖 | WebSocket（實驗性） |

---

## Base URL

當網頁伺服器運行時，base URL 是：

```
http://<host>:<port>
```

預設：`http://localhost:8080`
