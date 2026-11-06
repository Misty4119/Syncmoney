# Syncmoney

English: [README.md](README.md)

<p align="center">
  <a href="https://github.com/Misty4119/Syncmoney"><img src="https://img.shields.io/github/stars/Misty4119/Syncmoney" alt="GitHub stars"></a>
  <a href="https://github.com/Misty4119/Syncmoney"><img src="https://img.shields.io/github/downloads/Misty4119/Syncmoney/total" alt="總下載數"></a>
  <a href="https://github.com/Misty4119/Syncmoney/releases/latest"><img src="https://img.shields.io/github/v/release/Misty4119/Syncmoney" alt="最新版本"></a>
  <a href="https://github.com/Misty4119/Syncmoney/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-green.svg" alt="Apache 2.0 授權"></a>
  <a href="https://github.com/Misty4119/Syncmoney/actions/workflows/ci.yml"><img src="https://github.com/Misty4119/Syncmoney/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/Misty4119/Syncmoney/actions/workflows/codeql.yml"><img src="https://github.com/Misty4119/Syncmoney/actions/workflows/codeql.yml/badge.svg" alt="CodeQL"></a>
</p>
<p align="center">
  <a href="https://docs.papermc.io/paper/getting-started/"><img src="https://img.shields.io/badge/Targets-Paper%20%7C%20Folia%20%7C%20Canvas-orange.svg" alt="Paper、Folia 與 Canvas 目標"></a>
  <img src="https://img.shields.io/badge/Java-21%20%2F%2025-blueviolet.svg" alt="Java 21 與 25">
  <a href="https://official.noie.fun"><img src="https://img.shields.io/badge/Discord-Community-7289DA.svg?logo=discord&logoColor=white" alt="Discord 社群"></a>
  <a href="https://paypal.me/NoieSrv"><img src="https://img.shields.io/badge/Donate-PayPal-blue" alt="以 PayPal 贊助"></a>
</p>

<p align="center"><strong>相容 Vault 的 Minecraft 經濟插件，提供選用的 Redis 跨服同步、管理、審計工具與 PlaceholderAPI 支援。</strong></p>

## 專案簡介

Syncmoney 管理玩家餘額、貨幣交易與自身經濟指令。它支援單伺服器 SQLite 經濟、以 Redis 同步跨服餘額、關聯式資料庫持久化，以及由 CMI 維持經濟權威的選用同步模式。Syncmoney 不會複製任意權限或第三方指令。

目前已發布的穩定版為 1.3.2。`1.4.0-Beta.1` 是 1.4.0 系列的預覽版，需要 Java 25；它不是穩定版 1.4.0。請查看[發布頁面](https://github.com/Misty4119/Syncmoney/releases)確認是否已提供下載與各版本說明。

## 主要功能

- 相容 Vault 1.7，並可選擇整合 VaultUnlocked。
- 提供 `local` SQLite、`local_redis` 與 `sync` 經濟模式。
- 透過 Redis Pub/Sub 同步跨服餘額，並使用版本檢查與 echo suppression。
- `sync` 模式可選擇 MySQL、MariaDB 或 PostgreSQL 持久化。
- 以 Redis 同步由 CMI 權威管理的遊戲幣；此模式中 CMI 維持經濟權威。
- 玩家餘額、支付、排行榜與管理指令。
- 交易審計紀錄、帳戶保護、斷路器工具與 Shadow Sync snapshots。
- 選用內嵌 Web Admin，提供已驗證的 HTTP endpoint 與 Server-Sent Events（SSE）。
- 獨立的 PlaceholderAPI expansion；交易事件供內部遙測使用。
- 已接受但 Redis 結果不明的佇列支付會保留復原紀錄，並隔離至管理員確認確切操作收據。

## 平台與相容性

| 元件 | 需求或狀態 |
|---|---|
| **伺服器引擎** | Paper 相容伺服器。Minecraft 26.2–26.3 的 Paper、Folia 與 Canvas 是 1.4.0 目標矩陣。不支援純 Spigot。 |
| **Java runtime** | 已發布的 1.3.2 穩定版需要 Java 21 以上；1.4.0 預覽版需要 Java 25 以上。 |
| **經濟橋接** | 可選擇 Vault 1.7 或相容的 VaultUnlocked 進行互通。CMI 模式不會註冊 Syncmoney 經濟提供者。 |
| **訊息代理** | `local_redis`、`sync` 與 `cmi` 模式需要 Redis。 |
| **資料庫** | `local` 使用本機 SQLite；`sync` 共享 SQL 可使用 MySQL、MariaDB 或 PostgreSQL。 |
| **選用整合** | PlaceholderAPI 使用獨立的 `SyncmoneyExpansion` JAR。CMI 模式需要 CMI、CMILib 與 Redis；請向其發布者取得 CMI 與 CMILib，並遵守各自授權。 |

1.4.0 的目標範圍不代表所有模式與整合都已完整驗證。[1.4.0-Beta.1 發布說明](https://github.com/Misty4119/Syncmoney/releases/tag/1.4.0-Beta.1)會列出該版本實際測試的伺服器 build 與情境。變更正式經濟資料前，請先在 staging 環境驗證升級。

設定 schema 版本與插件版本分開管理。升級前請備份設定與經濟資料。

## 安裝與設定

1. **備份資料。** 停止伺服器，備份 Syncmoney 設定、SQLite 或 SQL 資料庫，以及經濟系統使用的 Redis 資料。
2. **安裝 JAR。** 使用 1.4.0-Beta.1 預覽版時，請從其發布頁下載 `Syncmoney-1.4.0-Beta.1.jar`，放入伺服器的 `plugins/` 目錄。移除舊版 Syncmoney JAR，確保只載入一個版本。
3. **安裝模式依賴。** `local` 使用 SQLite；`local_redis` 需要 Redis；`sync` 需要 Redis 與關聯式資料庫；`cmi` 需要 CMI、CMILib 與 Redis。Vault 或 VaultUnlocked 是 Syncmoney 自行管理經濟模式的選用互通依賴。
4. **產生並編輯設定。** 啟動伺服器一次以產生設定檔，接著停止伺服器。為每台伺服器在 `plugins/Syncmoney/config.yml` 設定不同且非空白的 `server-name`。
5. **選擇經濟模式。** 設定該模式需要的 Redis 與資料庫連線。使用 `cmi` 模式時，請由 CMI 獨自維持經濟權威。
6. **選用 PlaceholderAPI。** 使用 1.4.0-Beta.1 預覽版時，請將 `SyncmoneyExpansion-1.4.0-Beta.1.jar` 放入 `plugins/PlaceholderAPI/expansions/`，然後執行 `/papi reload`。Expansion 版本必須與 core JAR 相同。
7. **選用 Web Admin。** 啟用前先替換預設 API key 佔位值。CMI 僅同步模式不會啟動 Web Admin。
8. **開放交易前先驗證。** 確認經濟權威與必要服務已載入，並在 staging 測試所選模式。

## 經濟模式

| 模式（`economy.mode`） | 儲存與架構 | 適用環境 |
|---|---|---|
| `local` | 每台伺服器各自使用 SQLite，不需要 Redis 或共享 SQL。 | 單一伺服器。 |
| `local_redis` | 以 Redis 儲存跨服共用餘額，不使用共享 SQL 持久化。 | 使用 Redis 共用餘額的伺服器網路。 |
| `sync` | Redis Pub/Sub 加上共享 SQL 持久化。 | 需要關聯式資料庫的多伺服器網路。 |
| `cmi` | CMI 維持貨幣權威，Redis 在伺服器間傳送 CMI 餘額更新。 | 已由 CMI 管理經濟的伺服器網路。Syncmoney 自行管理的經濟指令與服務會停用。 |
| `auto` | 偵測已安裝環境並建議模式。 | 評估用途；正式環境建議明確指定模式。 |

將既有 Redis 經濟切換至 `local_redis` 或 `sync` 前，請先檢查餘額是否超過共享模式上限 `999999999999999999.99`。1.4.0 不會自動掃描、截斷或遷移既有 Redis 餘額。

### 單機設定（`local`）

每個安裝環境都要設定不同的 server name。以下範例會停用 Redis 與共享 SQL，保留本機 SQLite 儲存：

```yaml
server-name: "single-01"
pubsub-enabled: false
db-enabled: false
economy:
  mode: "local"
redis:
  enabled: false
database:
  enabled: false
```

### 共享網路設定（`sync`）

啟用 Redis Pub/Sub 與共享資料庫。所有伺服器需指向相同 Redis database 與 SQL schema，同時為每台伺服器設定不同的 `server-name`。連線憑證請留在各自的本機設定檔。

```yaml
server-name: "survival-01"
pubsub-enabled: true
db-enabled: true
economy:
  mode: "sync"
redis:
  enabled: true
  host: "redis.example.net"
  port: 6379
database:
  enabled: true
  type: "mysql"
  host: "sql.example.net"
  port: 3306
  database: "syncmoney"
```

請在本機設定連線憑證，不要將它們發布。其他伺服器設定請編輯初次啟動產生的設定檔，並參考[開發者指南](docs/DEVELOPER_GUIDE.zh_tw.md)。

## 選用模組與生命週期

選用模組可在 `plugins/Syncmoney/config.yml` 設定。

| 模組 | 設定開關 | 說明 |
|---|---|---|
| **全域斷路器** | `circuit-breaker.enabled` | 達到設定的異常上限時停止受管理的經濟操作。 |
| **玩家保護** | `circuit-breaker.player-protection.enabled` | 提供玩家層級速率限制與帳戶保護，與全域斷路器分開設定。 |
| **轉移保護** | `transfer-guard.enabled` | 保護玩家避免跨服轉移或傳送時發生餘額不一致。 |
| **審計紀錄** | `audit.enabled` | 記錄交易歷史。Cleanup、export 與 Redis audit 功能需要先啟用審計主開關。 |
| **Shadow Sync** | `shadow-sync.enabled` | 將資料鏡像到次要儲存供快照使用，不能取代資料庫備份。 |
| **Discord Webhook** | `discord-webhook.enabled` | 針對支援的事件發送已設定通知。 |
| **Web Admin** | `web-admin.enabled` | 安全設定有效時啟動內嵌 HTTP dashboard。 |

`/syncmoney reload` 僅即時套用 `display`、`pay`、`permissions`、`admin-permissions` 與 `debug`。連線、經濟權威、scheduler ownership 或模組建立方式變更後，需要完整重新啟動伺服器。

CMI 模式僅負責同步：由 CMI 維持經濟權威，只啟動 CMI 餘額同步路徑與 `/syncmoney version`。此模式不註冊 Syncmoney Vault provider 或經濟查詢 API，並停用 Syncmoney 的錢包、轉帳、排行榜及管理模組。CMI 模式不提供 Syncmoney 餘額、排名或排行榜佔位符。此模式不支援 `/pay` 或其他 Syncmoney 成對轉帳操作。

## 指令與權限

### 玩家指令

| 指令 | 說明 | 權限 |
|---|---|---|
| `/money` | 查看自己的餘額。 | `syncmoney.money`（預設：true） |
| `/money <player>` | 查看其他玩家餘額。 | `syncmoney.money.others`（預設：op） |
| `/pay <player> <amount>` | 支付給其他玩家。 | `syncmoney.pay`（預設：true） |
| `/baltop [page]` | 查看餘額排行榜。 | `syncmoney.money`（預設：true） |
| `/baltop me` | 查看自己的排行榜位置。 | `syncmoney.money`（預設：true） |

CMI 模式不提供 Syncmoney 自行管理的經濟指令，經濟權威維持在 CMI。

### 管理指令

| 指令 | 說明 | 權限 |
|---|---|---|
| `/syncmoney admin give <player> <amount>` | 增加玩家餘額。 | `syncmoney.admin.give` 或設定的管理層級 |
| `/syncmoney admin take <player> <amount>` | 扣除玩家餘額。 | `syncmoney.admin.take` 或設定的管理層級 |
| `/syncmoney admin set <player> <amount>` | 設定玩家餘額。 | `syncmoney.admin.set` 或設定的管理層級 |
| `/syncmoney admin reset <player>` | 將玩家餘額歸零。 | `syncmoney.admin.set` 或設定的管理層級 |
| `/syncmoney audit <player> [page]` | 查看審計歷史。 | `syncmoney.admin.audit` |
| `/syncmoney monitor [redis\|cache\|db]` | 查看系統與資源狀態。 | `syncmoney.admin.monitor` |
| `/syncmoney migrate <cmi\|local-to-sync>` | 執行明確指定的經濟 migration。 | `syncmoney.admin` |
| `/syncmoney recovery list` | 列出等待復原檢視的佇列轉帳。 | `syncmoney.admin.recovery` 或 `syncmoney.admin` |
| `/syncmoney recovery inspect <id>` | 檢視隔離中的轉帳。 | `syncmoney.admin.recovery` 或 `syncmoney.admin` |
| `/syncmoney recovery reconcile <id>` | 使用完全相符的 Redis 操作收據進行對帳。 | `syncmoney.admin.recovery` 或 `syncmoney.admin` |
| `/syncmoney version [full\|save]` | 顯示版本與支援診斷資料。 | 基本資訊：公開；`full`／`save`：`syncmoney.admin` |

其他管理子指令包括 `breaker`、`debug`、`econstats`、`reload`、`shadow`、`sync-balance`、`test` 與 `web`（依設定啟用）。可用指令會依模式與模組設定變動。完整權限節點與實際權限判斷請查看 `src/main/resources/plugin.yml` 及各指令的專屬權限。

### 管理權限層級

在 `config.yml` 的 `admin-permissions` 區塊設定管理權限層級。

| 層級 | 權限節點 | 預設每日上限 |
|---|---|---|
| **Observe** | `syncmoney.admin.observe` | 僅檢視；give 0／take 0 |
| **Reward** | `syncmoney.admin.reward` | give 100,000／take 0 |
| **General** | `syncmoney.admin.general` | give 1,000,000／take 1,000,000 |
| **Full** | `syncmoney.admin.full` | 經濟操作不限額 |

每日上限開關與各層級限制都可設定。root `syncmoney.admin` 與各指令專屬權限也列在 `src/main/resources/plugin.yml`。

## PlaceholderAPI 佔位符

將 `SyncmoneyExpansion-1.4.0-Beta.1.jar` 與 1.4.0-Beta.1 core JAR 搭配安裝後，即可使用下列 PlaceholderAPI 佔位符：

| 佔位符 | 說明 |
|---|---|
| `%syncmoney_balance%` | 自己的數字餘額。 |
| `%syncmoney_balance_formatted%` | 已套用貨幣格式的餘額。 |
| `%syncmoney_balance_abbreviated%` | 縮寫餘額，例如 `1.5K`。 |
| `%syncmoney_balance_<player>%` | 指定玩家餘額。 |
| `%syncmoney_balance_formatted_<player>%` | 指定玩家的格式化餘額。 |
| `%syncmoney_balance_abbreviated_<player>%` | 指定玩家的縮寫餘額。 |
| `%syncmoney_rank%`／`%syncmoney_my_rank%` | 自己的排行榜名次。 |
| `%syncmoney_top_<n>%` | 排行榜第 `n` 名的餘額。 |
| `%syncmoney_total_supply%` | 經濟系統的貨幣總供應量。 |
| `%syncmoney_total_players%` | 排行榜追蹤的帳戶數。 |
| `%syncmoney_online_players%` | 線上玩家數。 |
| `%syncmoney_version%` | 目前 Syncmoney 版本。 |

全域及排名查詢使用非同步快取；快取未命中時不會阻塞伺服器執行緒。CMI 模式不提供 Syncmoney 餘額、排名或排行榜佔位符。

## Web Admin 設定與安全

Web Admin 為選用功能，預設停用。啟用後會提供內嵌 dashboard 與已驗證的 REST endpoint。

```yaml
web-admin:
  enabled: true
  server:
    host: "127.0.0.1"
    port: 8080
  security:
    api-key: "change-me-in-production" # 啟用 Web Admin 前請替換。
    cors-allowed-origins:
      - "https://admin.example.com"
```

API key 空白或仍為隨附佔位值時，Web Admin 會保持停用，經濟插件仍可正常啟動。CMI 僅同步模式不會啟動此模組。

### 安全建議

1. **僅綁定本機介面。** Web Admin 保持 loopback 綁定；遠端瀏覽器請透過 HTTPS reverse proxy 連線。
2. **使用私密金鑰。** 將預設 API key 佔位值替換成長且隨機的金鑰，並避免公開。
3. **限制瀏覽器來源。** CORS 僅列出需要的來源，正式環境不要使用 `*`。
4. **限制網路存取。** 使用防火牆限制 HTTP port，並保護 reverse-proxy 路由。
5. **使用目前的即時端點。** Dashboard 使用 HTTP 與 `/api/sse`；`/ws` 固定回傳 `503 WEBSOCKET_UNAVAILABLE`，不提供 WebSocket 服務。

## 開發者整合

### Vault 經濟提供者

在 Syncmoney 自行管理經濟的模式中，插件可透過 Bukkit services manager 取得 Vault 1.7 provider：

```java
RegisteredServiceProvider<Economy> registration =
    getServer().getServicesManager().getRegistration(Economy.class);

if (registration != null) {
    Economy economy = registration.getProvider();
    double balance = economy.getBalance(player);
}
```

偵測到 VaultUnlocked 時，Syncmoney 也會註冊 Vault2 provider。CMI 僅同步模式不註冊 Syncmoney Vault provider 或經濟查詢 API。

### 交易事件

`AsyncPreTransactionEvent` 會在符合條件的存款與提款流程前送出，並可取消交易。`PostTransactionEvent` 會在結果確定後回報。這些事件類別透過 Syncmoney 內部 event bus 派送，不會透過 Bukkit `PluginManager` 的 event registration 傳送。整合內部事件行為前，請先參考[開發者指南](docs/DEVELOPER_GUIDE.zh_tw.md)。

## 從原始碼建置

1.4.0 target 使用 Java 25 與 Gradle 9.8.0。Java 21 與 Gradle 9.1.0 是保留的 1.3.2 baseline。

```bash
# Core plugin、PAPI expansion、API consumer 與 acceptance artifacts
./gradlew test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar

# Windows PowerShell
.\gradlew.bat test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar

# Web frontend checks
cd syncmoney-web
pnpm install --frozen-lockfile
pnpm typecheck
pnpm test:unit --run
pnpm build
```

Core JAR 輸出於 `build/libs/`；expansion JAR 輸出於 `syncmoney-papi-expansion/build/libs/`。修改 Web Admin source 後，在 `syncmoney-web` 執行 `pnpm build:embedded`，並一併檢視產生的 bundle 與 `WebAdminServer.extractIndividualFiles`。

## Release 與 CI 流程

Pull request 與更新 `main` 時會執行 Java 與 Web checks。CI artifacts 僅供測試，不是公開 GitHub Release。

Core release tag 不加 `v` 前綴，例如 `1.4.0-Beta.1`。Alpha 與 beta tag 會以 prerelease 處理。Web Admin source 以本 repository 為主，並鏡像至 [Syncmoney-web](https://github.com/Misty4119/Syncmoney-web)；鏡像使用對應的 `v` 前綴 tag 與 deterministic source archive。正式 JAR 與各版本說明請查看 GitHub release page。

Web frontend 發布前，請在 `syncmoney-web` 執行 `pnpm build:embedded`，確保 embedded distribution 與 Java extraction list 一起更新。

## 疑難排解與支援

- **餘額沒有跨服同步：** 確認所有節點使用相同 Redis instance 與 database、已啟用 Pub/Sub，且每台伺服器設定不同的 `server-name`。
- **插件無法啟動：** 確認 `server-name` 非空白、已安裝所選模式的必要依賴，並在 `cmi` 模式啟用 CMI。
- **共享模式交易遭拒：** 將既有網路切換至 `local_redis` 或 `sync` 前，檢查 Redis 既有餘額是否超過共享模式上限。
- **設定變更沒有套用：** 連線、模式、scheduler 或模組擁有者設定變更後需要完整重新啟動伺服器。
- **Web Admin 無法啟動：** 替換隨附的 API key 佔位值，並確認目前模式支援 Web Admin。
- **回報問題：** 提供 `/syncmoney version`、伺服器軟體 build、Java 版本及已清除敏感資料的 logs/config。請遮蔽密碼、API key 與 webhook URL。管理員可用 `/syncmoney version full` 執行唯讀後端探測，或用 `/syncmoney version save` 儲存已清理的報告。
- **GitHub Issues：** [問題回報](https://github.com/Misty4119/Syncmoney/issues)
- **Discord 社群：** [加入社群](https://official.noie.fun)

## 授權

本專案採用 [Apache License 2.0](LICENSE) 授權。
