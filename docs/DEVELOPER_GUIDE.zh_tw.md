# Syncmoney 開發者指南

為想要與 Syncmoney 整合或擴展其功能的開發者提供的綜合指南。

> **另請參閱：**[架構概覽](ARCHITECTURE.zh_tw.md) 以了解系統層級設計和資料流程圖。

---

## 目錄

1. [事件系統](#事件系統)
2. [REST API](#rest-api)
3. [設定](#設定)
4. [Vault API 整合](#vault-api-整合)
5. [PlaceholderAPI 擴展](#placeholderapi-擴展)
6. [SSE API](#sse-api)
7. [指令](#指令)
8. [從原始碼建構](#從原始碼建構)
9. [編碼標準](#編碼標準)
10. [已知限制](#已知限制)

---

## 事件系統

Syncmoney 提供了多個事件，開發者可以監聽這些事件以與其他插件整合。

### 可用事件

| 事件類別 | 說明 |
|------------|-------------|
| `AsyncPreTransactionEvent` | 在交易處理前觸發（可取消） |
| `PostTransactionEvent` | 在交易完成後觸發 |
| `ShadowSyncEvent` | 在背景同步操作發生時觸發 |
| `TransactionCircuitBreakEvent` | 在斷路器觸發時觸發 |

### 監聽事件

```java
import noietime.syncmoney.event.AsyncPreTransactionEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class MyPluginListener implements Listener {

    @EventHandler
    public void onPreTransaction(AsyncPreTransactionEvent event) {
        // 取得交易詳情
        String playerName = event.getPlayerName();
        java.math.BigDecimal amount = event.getAmount();
        AsyncPreTransactionEvent.TransactionType type = event.getType();

        // 在此放置你的自訂邏輯
        getLogger().info("Transaction pending: " + playerName + " - " + amount);

        // 如有需要可取消交易
        // event.setCancelled(true);
    }
}
```

### 事件類別參考

#### AsyncPreTransactionEvent

> **⚠️ v1.1.0 已知限制：** `AsyncPreTransactionEvent` 已定義但**尚未由 `EconomyFacade` 觸發**。呼叫 `event.setCancelled(true)` **對實際交易沒有影響**。此事件將在未來版本中完全連接。使用 `PostTransactionEvent` 進行可靠的交易監控。

```java
// 欄位
UUID getPlayerUuid();
String getPlayerName();
TransactionType getType(); // DEPOSIT, WITHDRAW, SET_BALANCE, TRANSFER
java.math.BigDecimal getAmount();
java.math.BigDecimal getCurrentBalance();
String getSource();
UUID getTargetUuid();
String getTargetName();
String getReason();

// 取消（目前無操作 — 見上方警告）
boolean isCancelled();
void setCancelled(boolean cancelled);
void setCancelled(boolean cancelled, String reason);
String getCancelReason();
```

#### PostTransactionEvent

```java
// 欄位
UUID getPlayerUuid();
String getPlayerName();
TransactionType getType();
java.math.BigDecimal getAmount();
java.math.BigDecimal getBalanceBefore();
java.math.BigDecimal getBalanceAfter();
String getSource(); // 見下方 EconomyEvent.EventSource 值
UUID getTargetUuid();
String getTargetName();
String getReason();
boolean isSuccess();
String getErrorMessage();

// 工具方法
java.math.BigDecimal getBalanceChange(); // 淨變化（可為負）
```

**`EconomyEvent.EventSource` 值**（作為 `source` 字串傳遞）：

| 值 | 說明 |
|-------|-------------|
| `VAULT_DEPOSIT` | 透過 Vault API `depositPlayer()` 觸發 |
| `VAULT_WITHDRAW` | 透過 Vault API `withdrawPlayer()` 觸發 |
| `COMMAND_PAY` | 玩家 `/pay` 指令 |
| `COMMAND_ADMIN` | 管理員指令（`/syncmoney admin`） |
| `ADMIN_SET` | 管理員設定餘額 |
| `ADMIN_GIVE` | 管理員給予貨幣 |
| `ADMIN_TAKE` | 管理員收取貨幣 |
| `PLAYER_TRANSFER` | 直接 EconomyFacade 轉帳 |
| `MIGRATION` | 資料遷移過程 |
| `SHADOW_SYNC` | 背景影子同步 |
| `TEST` | 壓力測試指令 |

#### ShadowSyncEvent

在執行影子同步操作時呼叫。

**事件方法：**

| 方法 | 回傳類型 | 說明 |
|--------|-------------|-------------|
| `getSyncType()` | `SyncType` | 同步操作類型（FULL、INCREMENTAL、MANUAL） |
| `getStatus()` | `SyncStatus` | 同步狀態（STARTED、IN_PROGRESS、COMPLETED、FAILED） |
| `getPlayersProcessed()` | `int` | 已處理的玩家數量 |
| `getTotalPlayers()` | `int` | 要同步的總玩家數量 |
| `getProgressPercentage()` | `int` | 進度百分比（0-100） |
| `getServerName()` | `String` | 來源/目標伺服器名稱 |
| `getErrorMessage()` | `String` | 如果失敗則為錯誤訊息，否則為 null |
| `getDuration()` | `Duration` | 同步操作的持續時間 |
| `getAffectedPlayers()` | `Set<UUID>` | 受影響玩家 UUID 的集合 |
| `isFinalStatus()` | `boolean` | 是否為最終狀態（COMPLETED 或 FAILED） |
| `isSuccessful()` | `boolean` | 同步是否成功完成 |

**範例：**

```java
@EventHandler
public void onShadowSync(ShadowSyncEvent event) {
    if (event.getStatus() == SyncStatus.COMPLETED) {
        plugin.getLogger().info("Sync completed: " +
            event.getPlayersProcessed() + "/" + event.getTotalPlayers());
    }
}
```

#### TransactionCircuitBreakEvent

在經濟斷路器觸發或改變狀態時呼叫。

**事件方法：**

| 方法 | 回傳類型 | 說明 |
|--------|-------------|-------------|
| `getPreviousState()` | `CircuitState` | 轉換前的狀態 |
| `getCurrentState()` | `CircuitState` | 轉換後的狀態 |
| `getReason()` | `TriggerReason` | 電路改變的原因（`SINGLE_TRANSACTION_LIMIT`、`RATE_LIMIT`、`INFLATION_DETECTED`、`SUDDEN_CHANGE`、`MANUAL_LOCK`） |
| `getMessage()` | `String` | 人類可讀的描述 |
| `getAffectedPlayers()` | `Set<UUID>` | 受影響玩家 UUID 的集合 |
| `getThreshold()` | `BigDecimal` | 超出的閾值 |
| `getActualValue()` | `BigDecimal` | 觸發事件的實際值 |
| `isStateTransition()` | `boolean` | previousState 是否不等於 currentState |
| `isLocked()` | `boolean` | 目前狀態是否為 LOCKED |
| `isUnlocked()` | `boolean` | 是否從 LOCKED 轉換離開 |

**CircuitState 值：** `NORMAL`、`WARNING`、`LOCKED`

---

## REST API

Syncmoney 透過內建的 Undertow 網頁伺服器暴露 REST API。

### 認證

大多數 API 端點需要在 Authorization 請求頭中提供 API 金鑰：

```
Authorization: Bearer <your-api-key>
```

`/health` 端點不需要認證。

### 端點

#### 健康檢查（無需認證）

```
GET /health
```

回應：
```json
{"success":true,"data":{"status":"ok","version":"1.1.0"}}
```

#### 系統 API

| 方法 | 端點 | 說明 |
|--------|----------|-------------|
| GET | `/api/system/status` | 插件狀態、運行時間、玩家數量、資料庫狀態 |
| GET | `/api/system/redis` | Redis 連線狀態 |
| GET | `/api/system/breaker` | 斷路器狀態 |
| GET | `/api/system/metrics` | 記憶體使用量、執行緒數量、TPS |

#### 經濟 API

| 方法 | 端點 | 說明 |
|--------|----------|-------------|
| GET | `/api/economy/stats` | 總供應量、玩家數量、今日交易、貨幣名稱 |
| GET | `/api/economy/player/{uuid}/balance` | 透過 UUID 取得指定玩家的餘額 |
| GET | `/api/economy/top` | 餘額排名前 10 的玩家 |

#### 審計 API

| 方法 | 端點 | 說明 |
|--------|----------|-------------|
| GET | `/api/audit/player/{playerName}` | 取得玩家的審計記錄（分頁） |
| GET | `/api/audit/search` | 使用篩選條件搜尋審計記錄 |
| GET | `/api/audit/stats` | 審計模組緩衝區大小和啟用狀態 |

搜尋的查詢參數：`player`、`type`、`startTime`、`endTime`、`page`、`pageSize`

#### 設定 API

| 方法 | 端點 | 說明 |
|--------|----------|-------------|
| GET | `/api/config` | 取得目前設定（密碼已隱藏） |
| POST | `/api/config/reload` | 從磁碟重載設定 |

#### 設定值 API

| 方法 | 端點 | 說明 |
|--------|----------|-------------|
| GET | `/api/settings` | 取得主題和語言偏好 |
| POST | `/api/settings/theme` | 更新主題（`dark` 或 `light`） |
| POST | `/api/settings/language` | 更新語言（`zh-TW` 或 `en-US`） |

### 回應格式

所有回應都包含 `meta` 欄位：

```json
{
  "success": true,
  "data": { ... },
  "meta": { "timestamp": 1709337000000, "version": "1.1.0" }
}
```

完整的請求/回應範例，請參閱 [API_REFERENCE.md](API_REFERENCE.md)。

---

## 設定

### 主設定（config.yml）

```yaml
# ==========================================
# 1. 核心和基本設定
# ==========================================
server-name: ""              # 多伺服器識別
queue-capacity: 50000        # 事件佇列容量
pubsub-enabled: true         # 啟用發布/訂閱
db-enabled: true            # 啟用資料庫
debug: false                # 偵錯模式

# ==========================================
# 2. 資料庫和 Redis
# ==========================================
redis:
  enabled: true
  host: "localhost"
  port: 6379
  password: ""
  database: 0
  pool-size: 30

database:
  enabled: true
  type: "mysql"             # MySQL、PostgreSQL
  host: "localhost"
  port: 3306
  username: "root"
  password: ""
  database: "syncmoney"

# ==========================================
# 3. 經濟和交易設定
# ==========================================
economy:
  mode: "auto"              # auto、local、local_redis、sync、cmi
  sync:
    vault-intercept: true
  cmi:
    balance-mode: "internal"
    debounce-ticks: 5

display:
  currency-name: "$"
  decimal-places: 2

pay:
  cooldown-seconds: 30
  min-amount: 1
  max-amount: 1000000
  confirm-threshold: 100000

baltop:
  enabled: true
  cache-seconds: 30
  format: "smart"

# ==========================================
# 4. 安全和保護
# ==========================================
circuit-breaker:
  enabled: true
  max-single-transaction: 100000000
  max-transactions-per-second: 10
  rapid-inflation-threshold: 0.2
  sudden-change-threshold: 100
  redis-disconnect-lock-seconds: 5
  memory-warning-threshold: 80

player-protection:
  enabled: true
  rate-limit:
    max-transactions-per-second: 5
    max-transactions-per-minute: 50
    max-amount-per-minute: 1000000

# Discord Webhook
discord-webhook:
  enabled: false
  webhooks:
    - name: "admin-alerts"
      url: "https://discord.com/api/webhooks/YOUR_WEBHOOK_URL_HERE"
      type: "private"
      events:
        - "player_warning"
        - "player_locked"
        - "player_unlocked"
        - "global_lock"

# 審計日誌系統
audit:
  enabled: true
  batch-size: 1
  retention-days: 90

# ==========================================
# 8. 網頁管理面板
# ==========================================
web-admin:
  enabled: false
  bundled-version: "1.1.0"
  server:
    host: "localhost"
    port: 8080
  web:
    path: "syncmoney-web"
    auto-update: false
    github-repo: "Misty4119/Syncmoney"
  security:
    api-key: "change-me-in-production"
    rate-limit:
      enabled: true
      requests-per-minute: 60
  ui:
    theme: "dark"
    language: "zh-TW"
```

### 訊息設定（messages.yml）

所有面向玩家的訊息都可透過 `messages.yml` 設定。使用 MiniMessage 格式：

```yaml
# 範例：自訂付款成功訊息
pay:
  success-sender: '<prefix>你已發送 {amount} 給 {player}'
```

---

## Vault API 整合

Syncmoney 註冊為 Vault Economy 提供者。其他插件可以使用：

```java
// 取得經濟服務
Economy economy = VaultAPI.getEconomy();

// 檢查餘額
if (economy.hasAccount(player)) {
    double balance = economy.getBalance(player);
}

// 存款
economy.depositPlayer(player, amount);

// 提款
economy.withdrawPlayer(player, amount);
```

### Vault 權限

#### 玩家權限

| 權限 | 預設 | 說明 |
|------------|---------|-------------|
| `syncmoney.money` | `true`（全部） | 檢視自己的餘額 |
| `syncmoney.money.others` | `op` | 檢視其他玩家的餘額 |
| `syncmoney.pay` | `true`（全部） | 轉帳給其他人 |
| `syncmoney.baltop` | `true`（全部） | 檢視財富排行榜 |

#### 管理員權限（基本）

| 權限 | 預設 | 說明 |
|------------|---------|-------------|
| `syncmoney.admin` | `op` | 一般管理員指令（頂層） |
| `syncmoney.admin.set` | `op` | 設定玩家餘額 |
| `syncmoney.admin.give` | `op` | 給予玩家貨幣 |
| `syncmoney.admin.take` | `op` | 收取玩家貨幣 |
| `syncmoney.admin.audit` | `op` | 檢視審計日誌 |
| `syncmoney.admin.monitor` | `op` | 檢視系統監控 |
| `syncmoney.admin.econstats` | `op` | 檢視經濟統計 |
| `syncmoney.admin.reload` | `op` | 重載設定 |
| `syncmoney.admin.test` | `op` | 執行壓力測試指令 |

#### 管理員分級權限（每日限額）

| 權限 | 預設 | 說明 | 每日給予限額 | 每日收取限額 |
|------------|---------|-------------|-----------------|-----------------|
| `syncmoney.admin.observe` | `false` | 唯讀觀察者（無經濟操作） | 0 | 0 |
| `syncmoney.admin.reward` | `false` | 獎勵管理員 | 100,000 | 0 |
| `syncmoney.admin.general` | `false` | 一般管理員 | 1,000,000 | 1,000,000 |
| `syncmoney.admin.full` | `op` | 完整管理員（無限制） | 無限 | 無限 |

> **注意：** 四個分級權限（`observe`、`reward`、`general`、`full`）控制 `give` 和 `take` 操作的每日交易限額。`syncmoney.admin` 節點是一個頂層便利節點（預設相當於 `op`）。

---

## PlaceholderAPI 擴展

Syncmoney 提供以下佔位符：

### 玩家佔位符

| 佔位符 | 說明 |
|-------------|-------------|
| `%syncmoney_balance%` | 玩家餘額（原始數字） |
| `%syncmoney_balance_formatted%` | 玩家餘額（智慧格式化） |
| `%syncmoney_balance_abbreviated%` | 玩家餘額（縮寫，例如 1.5K） |
| `%syncmoney_rank%` | 玩家財富排名 |
| `%syncmoney_my_rank%` | 玩家財富排名（別名） |
| `%syncmoney_balance_<player>%` | 取得指定玩家的餘額 |

### 伺服器佔位符

| 佔位符 | 說明 |
|-------------|-------------|
| `%syncmoney_total_supply%` | 經濟中的總貨幣量 |
| `%syncmoney_total_players%` | 排行榜中的總玩家數 |
| `%syncmoney_online_players%` | 目前線上的玩家 |
| `%syncmoney_version%` | 插件版本 |
| `%syncmoney_top_<n>%` | 排名第 n 的玩家餘額 |

### 使用範例

```
# 玩家餘額
%syncmoney_balance%

# 玩家排名
%syncmoney_rank%

# 伺服器總供應量
%syncmoney_total_supply%

# 第 5 名玩家
%syncmoney_top_5%

# 檢查玩家餘額
%syncmoney_balance_Steve%
```

---

## WebSocket API

**注意：** 完整的 WebSocket 支援目前尚未實作。以下文件描述了計劃中的 API。

如需即時更新，請改用 SSE（Server-Sent Events）API。

### SSE API

Server-Sent Events 提供單向伺服器推送通知。

### 連線

```
GET http://<host>:<port>/sse
```

透過 API 金鑰查詢參數或 Authorization 請求頭進行認證。

### 範例

```javascript
const eventSource = new EventSource('http://localhost:8080/sse?apiKey=your-key');

eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data);
    console.log('SSE event:', data);
};

eventSource.onerror = (error) => {
    console.error('SSE error:', error);
    // 注意：內建前端使用指數退避加抖動進行重連
    // 以防止伺服器重啟時的驚群效應。
};
```

### SSE 事件類型

| `type` 值 | 觸發時機 | 主要 `data` 欄位 |
|---|---|---|
| `connected` | 客戶端連線 | `message` |
| `transaction` | `PostTransactionEvent` 觸發 | `playerName`、`type`、`amount`、`balanceBefore`、`balanceAfter`、`success`、`timestamp`（epoch ms） |
| `circuit_break` | `TransactionCircuitBreakEvent` 觸發 | `previousState`、`currentState`、`reason`、`message` |
| `system_alert` | 手動廣播 / 內部警報 | `level`、`message` |

**交易事件範例：**
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

---

## 指令

### 玩家指令

| 指令 | 說明 | 權限 |
|---------|-------------|------------|
| `/money [player]` | 檢視自己或其他玩家的餘額 | `syncmoney.money` |
| `/pay <player> <amount>` | 轉帳給其他玩家 | `syncmoney.pay` |
| `/baltop [page\|me]` | 財富排行榜（`me` 顯示你的排名） | `syncmoney.money` |

### 管理員指令

| 指令 | 說明 | 權限 |
|---------|-------------|------------|
| `/syncmoney admin set <player> <amount>` | 設定玩家餘額 | `syncmoney.admin` + `canExecute(set)` |
| `/syncmoney admin give <player> <amount>` | 給予玩家貨幣 | `syncmoney.admin` + `canExecute(give)` |
| `/syncmoney admin take <player> <amount>` | 收取玩家貨幣 | `syncmoney.admin` + `canExecute(take)` |
| `/syncmoney admin reset <player>` | 將玩家餘額重設為零 | `syncmoney.admin` + `canExecute(set)` |
| `/syncmoney admin view <player>` | 檢視玩家餘額 | `syncmoney.money.others` |
| `/syncmoney admin confirm` | 確認大型管理員操作 | `syncmoney.admin` |
| `/syncmoney breaker status` | 檢視斷路器狀態 | `syncmoney.admin` |
| `/syncmoney breaker reset` | 重置斷路器 | `syncmoney.admin` |
| `/syncmoney breaker info` | 檢視斷路器詳細資訊 | `syncmoney.admin` |
| `/syncmoney breaker resources` | 檢視資源狀態 | `syncmoney.admin` |
| `/syncmoney breaker player <player>` | 檢視玩家保護狀態 | `syncmoney.admin` |
| `/syncmoney breaker unlock <player>` | 手動解鎖玩家 | `syncmoney.admin` |
| `/syncmoney audit <player> [page]` | 檢視玩家審計日誌 | `syncmoney.admin.audit` |
| `/syncmoney audit search [--player <name>] [--type <type>] [--start <time>] [--end <time>] [--limit <n>]` | 進階審計搜尋 | `syncmoney.admin.audit` |
| `/syncmoney audit stats` | 檢視審計統計 | `syncmoney.admin.audit` |
| `/syncmoney audit cleanup` | 清理舊審計日誌 | `syncmoney.admin.full` |
| `/syncmoney monitor [overview]` | 系統概覽 | `syncmoney.admin` |
| `/syncmoney monitor redis` | Redis 詳細狀態 | `syncmoney.admin` |
| `/syncmoney monitor cache` | 快取狀態 | `syncmoney.admin` |
| `/syncmoney monitor db` | 資料庫狀態 | `syncmoney.admin` |
| `/syncmoney monitor memory` | 記憶體狀態 | `syncmoney.admin` |
| `/syncmoney monitor messages` | 訊息快取狀態 | `syncmoney.admin` |
| `/syncmoney econstats [overview]` | 經濟統計概覽 | `syncmoney.admin.econstats` |
| `/syncmoney econstats supply` | 貨幣供應統計 | `syncmoney.admin.econstats` |
| `/syncmoney econstats players` | 玩家統計 | `syncmoney.admin.econstats` |
| `/syncmoney econstats transactions` | 交易統計 | `syncmoney.admin.econstats` |
| `/syncmoney reload [all]` | 重載設定 | `syncmoney.admin.reload` |
| `/syncmoney reload config` | 重載 config.yml | `syncmoney.admin.reload` |
| `/syncmoney reload messages` | 重載 messages.yml | `syncmoney.admin.reload` |
| `/syncmoney reload permissions` | 重載權限 | `syncmoney.admin.reload` |
| `/syncmoney web download [latest]` | 下載網頁前端 | `syncmoney.admin` |
| `/syncmoney web build` | 建構網頁前端（需要 Node.js + pnpm） | `syncmoney.admin` |
| `/syncmoney web reload` | 重載網頁伺服器 | `syncmoney.admin` |
| `/syncmoney web open` | 在瀏覽器中開啟網頁管理 | `syncmoney.admin` |
| `/syncmoney web status` | 檢視網頁前端狀態 | `syncmoney.admin` |
| `/syncmoney web check` | 檢查更新 | `syncmoney.admin` |
| `/syncmoney web version` | `web check` 的別名 | `syncmoney.admin` |
| `/syncmoney migrate cmi [-force] [-no-backup] [-preview]` | 遷移 CMI 經濟資料 | `syncmoney.admin` |
| `/syncmoney migrate local-to-sync [-force] [-no-backup]` | 從 LOCAL 模式遷移到 SYNC | `syncmoney.admin` |
| `/syncmoney migrate status` | 檢視遷移狀態 | `syncmoney.admin` |
| `/syncmoney migrate stop` | 停止正在執行的遷移 | `syncmoney.admin` |
| `/syncmoney migrate resume` | 恢復中斷的遷移 | `syncmoney.admin` |
| `/syncmoney migrate clear` | 清除遷移斷點 | `syncmoney.admin` |
| `/syncmoney shadow status` | 檢視影子同步狀態 | `syncmoney.admin` |
| `/syncmoney shadow now` | 觸發立即同步 | `syncmoney.admin` |
| `/syncmoney shadow logs` | 檢視最近的同步日誌 | `syncmoney.admin` |
| `/syncmoney shadow history <player> [page]` | 檢視玩家的同步歷史 | `syncmoney.admin` |
| `/syncmoney shadow export <player> [startDate] [endDate]` | 將同步記錄匯出為 JSONL | `syncmoney.admin` |
| `/syncmoney debug player <player>` | 診斷玩家在所有層級的餘額 | `syncmoney.admin` |
| `/syncmoney debug system` | 診斷系統狀態 | `syncmoney.admin` |
| `/syncmoney sync-balance <player>` | 強制將玩家餘額同步至 Redis/DB | `syncmoney.admin` |
| `/syncmoney test concurrent-pay <threads> <iterations>` | 壓力測試（需要非 LOCAL 模式） | `syncmoney.admin.test` |
| `/syncmoney test total-supply` | 驗證總供應量一致性 | `syncmoney.admin.test` |

---

## 從原始碼建構

### 前提條件

- Java 21+
- Gradle（包含 wrapper）
- Node.js 20+ 和 pnpm（用於網頁前端）

### 建構指令

```bash
# 克隆
git clone https://github.com/Misty4119/Syncmoney.git
cd Syncmoney

# 建構插件 JAR（包含陰影重定位）
./gradlew shadowJar
# 輸出：build/libs/Syncmoney-1.1.0.jar

# 建構 PAPI 擴展
cd syncmoney-papi-expansion && ../gradlew jar
# 輸出：build/libs/SyncmoneyExpansion-1.1.0.jar

# 建構網頁前端
cd syncmoney-web
npm install
npm run build
# 輸出：syncmoney-web/dist/

# 執行測試
./gradlew test              # Java 單元測試
cd syncmoney-web && npm run test:unit   # 前端單元測試
cd syncmoney-web && npm run test:e2e    # 前端 E2E 測試
```

### Shadow JAR 重定位

所有執行期依賴都在 `build.gradle` 中重定位到 `noietime.libs.*`，以防止與其他 Minecraft 插件的類別路徑衝突。添加新的執行期依賴時，你**必須**添加相應的 `relocate()` 規則。

---

## 編碼標準

### 註解標準

為維護高品質、專業且全球可存取的程式碼庫，Syncmoney 嚴格執行以下註解規則：
- **區塊註解（`/** */`）**：所有類別、介面和重要方法都必須有以獨特標籤開頭的區塊註解：
  - 後端層：`[SYNC-<CATEGORY>-<NUM>]`（例如 `[SYNC-CMD-001]`、`[SYNC-CONFIG-005]`）
  - 網頁前端層：`[SYNC-WEB-<NUM>]`
  - PAPI 擴展：`[SYNC-PAPI-<NUM>]`
- **行內註解（`//`）**：強烈不鼓勵使用行內註解。程式碼應該是自說明的。僅在解釋高度複雜的演算法、競態條件預防或非明顯的數學公式時使用行內註解。
- **語言**：所有註解、變數名稱和文件都必須使用**英文**。程式碼庫中嚴格禁止中文或其他非英文註解（翻譯locale檔案如 `zh-TW.json` 除外）。
- **語氣**：註解必須簡潔、準確且嚴謹。避免冗餘的陈述只是重複程式碼所做的操作。

---

## 已知限制

| 限制 | 狀態 | 解決方法 |
|------------|--------|------------|
| `AsyncPreTransactionEvent` 未觸發 | v1.1.0 | 事件已定義但尚未在 `EconomyFacade` 中連接。改用 `PostTransactionEvent`。 |
| WebSocket 未完全實作 | v1.1.0 | `/ws` 接受連線但分派不完整。生產環境使用 SSE（`/sse`）。 |
| `event.setCancelled(true)` 無操作 | v1.1.0 | 交易前取消沒有效果，將在未來版本中連接。 |

---

## 支援

- GitHub Issues：回報錯誤和功能請求
- Discord：加入我們的社群以獲得支援
