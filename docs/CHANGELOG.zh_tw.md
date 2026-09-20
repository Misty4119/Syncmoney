# 更新日誌 (Changelog)

本專案的所有重大變更都將記錄於此檔案中。

本變更日誌格式基於 [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)，且本專案遵循 [語意化版本](https://semver.org/spec/v2.0.0.html)。

## [1.3.2] - 2026-09-20

### 安全性 (Security)

- **前端依賴修復**：將 Web Admin 依賴圖更新至已修補的 Axios、Vite 6、Vitest 4.1.11、Happy DOM、PostCSS 與 Sharp 版本。對仍有漏洞的間接依賴採用精準 pnpm override，避免為安全修復一併導入無關的 Java／執行期大版本升級。
- **稽核基準**：解析完成的 Web Admin 依賴圖目前以 `pnpm audit` 檢查為零已知漏洞。

### 已變更 (Changed)

- **版本中繼資料**：根專案、PlaceholderAPI、Web Admin、文件、設定中繼資料與 acceptance 版本檢查統一為 `1.3.2`。
- **內嵌 Web Bundle**：重新建置 Web Admin 內嵌資產，並同步 `WebAdminServer.extractIndividualFiles` 與產生的 bundle。

## [1.3.1] - 2026-09-09

### 已修復 (Fixed)

- **Adventure 執行期相容性**：Adventure 與 MiniMessage 改由伺服器執行期提供，不再將其部分重定位至插件 JAR。修復 Canvas 26.2 上 Adventure 4.x/5.x `ClickEvent` 的二進位相容性錯誤，同時保留 Paper 1.20.4 相容性。
- **訊息安全性**：移除互動式 MiniMessage 標籤，保留顏色與裝飾；解析或連結錯誤時會安全降級。

### 已新增 (Added)

- **支援報告**：新增 `/syncmoney version`、`/syncmoney version full` 與 `/syncmoney version save`。完整報告會收集已清理的伺服器、Java、依賴、模組、Redis 與資料庫診斷，不會暴露憑證或端點。Redis 與資料庫探測為唯讀、非同步、三秒逾時，並記錄三次延遲樣本。

## [1.3.0] - 2026-09-06

### 已修復 (Fixed)

#### 排程器正確性 (Folia / Paper / Canvas)
- **實體排程器路由**：所有針對單一玩家的操作（CMI 更新、支付通知、斷路器告警、CMI Pub/Sub 回呼）現在均在玩家所屬的實體排程器（Entity Scheduler）上執行。全域與插件層級的操作則使用全域區域排程器（Global Region Scheduler）。舊版的 `BukkitScheduler` 呼叫已全面移除。
- **傳送安全性**：`PlayerTransferGuard` 重寫了延遲傳送迴圈，改用 `player.getScheduler().runAtFixedRate()` 與 `teleportAsync()`。若有新的傳送請求，會立即覆蓋過期的延遲傳送目的地。在玩家登出或被剔除（Kick）時，未完成的經濟寫入操作絕不會被靜默丟棄；僅會取消延遲傳送任務。
- **佔位符熱點路徑**：`EconomyFacade.getBalanceForPlaceholder()` 與 `NameResolver.resolveUUIDForPlaceholder()` 會優先檢查記憶體狀態，未命中時則以非同步方式預熱快取。任何區域執行緒上的 PlaceholderAPI 呼叫者均不會因未命中的 Redis、資料庫或 Mojang 查詢而遭到阻塞。
- **審計清理間隔**：`AuditLogCleanup` 原先在排程重複任務時，將原本預期為小時的數值誤傳入為 Tick 值，導致清理任務約每 1 秒就觸發一次而非設定的間隔。該任務現已改用 `AsyncScheduler.runAtFixedRate()` 並搭配 `TimeUnit.HOURS`。清理與匯出任務皆持有可取消的控制代碼（Handle），能在關機時乾淨停止。

#### 模組生命週期與重載
- **單一防護擁有者**：`BreakerManager` 為 `PlayerTransactionGuard` 的唯一擁有者。已移除 `EconomyServiceManager` 內重複建構的路徑。
- **可選資源閘控**：斷路器（Breaker）、影子同步（Shadow）、審計（Audit）與通知模組在建構執行器、排程或訂閱前會先檢查設定。任何失敗或停用的模組都不會影響其他模組。
- **需重啟之重載閘門**：`ConfigReloadPolicy` 在發布執行期快照前，會拒絕對儲存、連線或服務設定的變更。僅有顯示、指令與權限相關的設定鍵支援熱重載（Live-reload）。執行一般的 `/syncmoney reload` 不再會中斷跨伺服器通知。
- **已停用模組的指令訊息**：已停用模組（`audit`、`shadow`、`breaker`）的指令現在會回傳說明訊息，而非拋出 NullPointerException。
- **`OnlinePlayerRegistry` 重載**：Redis 訂閱現在能在重載期間乾淨關閉，且不會中斷跨伺服器通知通道。

#### Web 管理後台
- **SSE 重連競態（Race Condition）**：`useSSE.ts` 中的 Token 刷新回呼（Callback）在呼叫 `disconnect()` 後，不再會嘗試重新開啟工作階段（Session）。
- **`WebAdminServer` 資源洩漏**：改善了伺服器生命週期中的例外處理；停止時會清空 SSE 工作階段對映表，防止殭屍回呼寫入已關閉的通道。
- **Adventure / MiniMessage 連結**：將 Adventure 相依性固定為 `4.16.0`，以符合 Paper 1.20.4 的打包版本。這能防止執行期出現 `ShadowColorTag` 的 `NoClassDefFoundError`。
- **Web UI Token 不一致**：修復了 `Button`、`Card`、`Input`、`Select`、`Switch`、`Sidebar` 與 `Header` 元件及相關檢視中顏色 Token 與 CSS 變數不吻合的問題。

### 已變更 (Changed)

#### 工具鏈與版本元資料
- 以 Java 21 API 與位元組碼為基準；Gradle Wrapper 更新至 9.1（支援在較新的伺服器環境中以 Java 25 執行）。
- `BuildVersion` 改為從建置階段展開的 `syncmoney-version.properties` 資源檔讀取版本，取代執行期的 `plugin.getDescription()` 呼叫。
- `ServerPlatformDetector` 在 Paper 與 Folia 之外新增了對 Canvas 的偵測支援。
- PAPI 擴充功能（Expansion）版本現在直接衍生自根目錄的 Gradle 版本，不再需要手動同步。
- 核心（Core）、PAPI 與 Web 前端元資料統一提升至 1.3.0。

#### 清理
- 移除 `WebModuleConfig`；其功能已由 `WebAdminConfig` 涵蓋。原先由其保護的重複 `EconomyFacade` 建構路徑也已移除。面向反射的 PAPI 包裝器保持不變。

#### Web 管理前端
- 重構 `Badge`、`Button`、`Card`、`Input`、`Select`、`Switch`、`Header`、`Sidebar` 元件以保持一致性。
- 更新 `ConfigView`、`LoginView`、`SettingsView`、`SystemStatusView` 與 `AuditLogFilters` 頁面檢視。
- 新增 `config` MSW 模擬處理常式（Mock Handler），用於離線開發與前端單元測試。
- 前端發行包（Dist）重新建置至 1.3.0。

### 已新增 (Added)

- **PlugDev 驗收工具**：新增 `tools/plugdev/` 腳本與設定，用於運行獨立的多伺服器開發網路。`AcceptanceProbe` 為獨立插件，可透過 RCON 觸發，對完整的 `EconomyFacade` API 進行端到端（End-to-End）測試。
- **新增單元測試**：包含 `ConfigReloadPolicyTest`、`PlayerTransferGuardTest`、`ConsumerShutdownTest`、`PlayerLookupUtilTest`、`DisabledAuditTest`、`DisabledBreakerTest`、`ResourceMonitorTest` 與 `RedisOnlyBaltopTest`。

### 已驗證的驗收矩陣 (Validated Acceptance Matrix)

Paper 1.20.4、Paper 26.2、Folia 26.2 BETA、Canvas 26.2 — 多後端（PostgreSQL + Redis）、雙伺服器網路，所有單元與整合測試均通過。

### 先前未列出的 1.2.x 變更 (Previously Unlisted 1.2.x Changes)

- **1.2.1** (`1f816df`)：相依性更新、PAPI 格式化／反射增強、資料庫 Schema 識別碼修復、獨立擴充功能打包。
- **1.2.2** (`cb641ad`)：VaultUnlocked/Vault2 執行期偵測、獨立整合載入器、提供者／註冊器與相容性測試。
- **1.2.3** (`10d914b`)：Vault 提供者／轉帳處理整合與冗餘邏輯移除。

這些項目為已標籤 Git 歷史紀錄的總結，並非新重新實作的功能。

## [1.2.0] - 2026-06-26

### 已新增 (Added)

#### 跨伺服器線上玩家
- **線上玩家註冊表**：新增 `OnlinePlayerRegistry`，透過 Redis 心跳追蹤跨伺服器的線上玩家，用於支援指令 Tab 自動補全。

#### CMI 同步架構
- **CMI API 層**：新增 `CMIApi`、`CMIPubsubHandler` 與 `CMIVersioning`，並提供專用的 Pub/Sub 通道以進行 CMI 模式的跨伺服器同步。

#### 核心重構
- **經濟核心拆分**：從 `EconomyFacade` 拆分出 `MemoryStateManager`、`TransactionWriter` 與 `TransferOrchestrator`。
- **斷路器鎖定原因**：新增 `LockReason` 列舉（Enum）以取代字串形式的鎖定原因比對。
- **Web 後端模組化**：導入 `RouteRegistry`、`StaticFileHandler`、`CorsHandler` 與 `AbstractApiHandler`。
- **審計日誌前端**：將審計頁面重構為 `useAuditData` / `useAuditExport` 組合式函式（Composables）與子元件。
- **測試涵蓋率**：擴充針對 CMI 版本控制、防抖（Debounce）、Vault、Web API 與 PAPI 擴充功能的單元測試。

### 已修復 (Fixed)

#### CMI 模式跨伺服器同步
- **權威性與發布機制**：重寫 CMI 同步，將 CMI API 作為本地權威（Authority）；發布帶有單調遞增版本號的絕對餘額，而非在執行期查詢 CMI 資料庫。
- **防止迴圈廣播（Echo Loop）**：新增 `suppressOutbound` 標記，在將遠端餘額套用至本地 CMI 時停止重複發布迴圈。
- **加入伺服器同步（Join Reconcile）**：玩家加入時進行具版本意識的餘額對帳，修復跨伺服器餘額偏差問題。
- **事件與輪詢切換**：當 CMI 事件可用時停用輪詢，防止在頻繁洗板 `/cmi pay` 時發生重複發布。
- **Vault 轉帳發布**：CMI 模式下的 Vault 轉帳現在會正確透過 CMI 通道發布（`FIX-CMI-VAULT-PUBLISH`）。
- **跨伺服器付款通知**：收到付款時的通知現在會顯示付款者的名稱。

#### 排程器與 Folia
- **定期版本檢查**：修復 `GlobalRegionScheduler` 時間間隔以使用正確的 Tick 單位（確實每 5 分鐘執行一次）。
- **Folia 相容性**：將 CMI 輪詢與 Redis I/O 移動至非同步 / 全域區域排程器。

### 已變更 (Changed)

#### 指令 Tab 自動補全
- **跨伺服器玩家建議**：`/pay`、`/money`、`/syncmoney admin` 及相關指令現在會建議全網路上所有的線上玩家（本地 + 透過 Redis 獲取的跨伺服器玩家）；`/pay` 不再會建議離線快取的名稱。

#### 雜項
- **PAPI 擴充功能**：將反射工具類別合併至 `PlaceholderHandler` 中，減少類別數量。
- **Discord Webhook**：使用 Jackson 簡化了 Payload 構建方式。
- **Web 管理後台**：內建前端版本提升至 1.2.0。

---

## [1.1.3] - 2026-04-03

### 已修復 (Fixed)

#### Folia + Paper 跨伺服器環境
- **孤立 VAULT_DEPOSIT 日誌洗板**：將日誌層級從 WARNING 調降至 FINE，並進行摘要輸出批次化（每 100 個事件一次），防止跨伺服器環境下控制台被日誌洗板。
- **玩家傳送卡住**：修復當存在未完成的經濟事件時，玩家在傳送過程中永久卡住的問題。傳送任務現在會在逾時後強制執行並清除追蹤狀態，以防止死鎖。

#### 寫入佇列與溢位處理
- **背壓門檻（Backpressure Threshold）**：從 80% 調降至 70%，以便在高負載下更早拒絕新事件。
- **溢位 WAL 復原**：啟動時新增 `replayOverflowEvents()`，從預寫式日誌（Write-Ahead Log）中復原丟棄的事件。
- **資料庫備援機制**：當 `DbWriteQueue` 額滿時，新增直接寫入資料庫的備援機制。

---

## [1.1.2] - 2026-03-22

### 已修復 (Fixed)

#### Vault 經濟提供者
- **Null 檢查**：`VaultPluginDetector` 新增設定 Null 檢查，防止設定未載入時拋出 NPE（空指標例外）。
- **孤立存款復原**：`VaultProviderCore` 新增孤立存款復原機制，自動將高頻交易失敗轉化為 PLUGIN_DEPOSIT 以防止金額遺失。

#### 設定
- **向下相容性**：`PlayerProtectionConfig` 支援雙重路徑讀取（`circuit-breaker.player-protection.*` 與 `player-protection.*`），以確保升級時的向下相容性。

---

## [1.1.1] - 2026-03-21

### 已新增 (Added)

#### 中央模式與節點管理
- **中央模式儀表板**：新增 `CentralDashboardView`，用於透過聚合的跨伺服器統計數據（`CrossServerStatsApiHandler`）監控所有已註冊的節點。
- **節點健康檢查器**：背景服務（`NodeHealthChecker`）每 30 秒執行一次健康檢查（門檻可設定），並透過 SSE 廣播狀態至 `system` 通道。
- **節點操作 API**：完整的 CRUD 操作（`NodeOperationsHandler`），用於管理節點註冊並支援 Ping / 延遲測試。
- **節點代理處理常式**：`NodeProxyHandler` 允許中央伺服器將請求代理至其他節點，以實現統一的 API 存取。
- **設定同步**：`ConfigSyncHandler` 支援從中央伺服器推播設定同步至所有節點（`/api/nodes/sync`）。
- **SSRF 防護**：`NodesApiContext.isUrlAllowed()` 會封鎖私有 IP 範圍（`10.*`、`172.16-31.*`、`192.168.*`、`127.*`、`0.*` 等）、localhost 與 `.local` 主機名稱，並強制僅允許 `http`/`https` 協定。

#### 第三方插件 API（開發者 API）
- **`PLUGIN_DEPOSIT` / `PLUGIN_WITHDRAW` 事件**：在 `EconomyEvent` 中新增 `EventSource` 列舉值，允許第三方插件觸發經濟變更並繞過 Vault 配對邏輯。
- **直接 API 方法**：提供 `EconomyFacade.pluginDeposit()`、`pluginWithdraw()` 與 `pluginAtomicTransfer()` 方法，供插件發起交易並獲得完整的斷路器（CircuitBreaker）保護。
- **Vault 提供者橋接器**：`VaultProviderCore.depositPlayerForPlugin()` / `withdrawPlayerForPlugin()` 委派給 `EconomyFacade.pluginDeposit()` / `pluginWithdraw()`，並享有完整的 CircuitBreaker 與 AsyncPreTransactionEvent 保護；`pluginTransfer()` 則使用 `atomic_transfer.lua` 實現原子化跨玩家轉帳。

#### 資料庫與儲存
- **PostgreSQL PreparedStatement 快取**：HikariCP 設定 `prepareThreshold=1` 與 `cacheMode=PREPARE` 以提升查詢效能。
- **PostgreSQL Upsert 語法**：遷移至 `ON CONFLICT (id) DO UPDATE SET` 模式，取代 MySQL 的 `ON DUPLICATE KEY UPDATE`。
- **`BIGSERIAL` 自增鍵**：PostgreSQL 資料表主鍵改用 `BIGSERIAL` 取代 `AUTO_INCREMENT`。
- **專用 `PostgresShadowStorage`**：完整實作針對 PostgreSQL 的影子同步，包含連線池調優與自動創建資料庫。

### 已變更 (Changed)

#### 前端改進
- **從資料庫取得總玩家數**：`BaltopManager.getTotalRegisteredPlayers()` 現在直接從資料庫查詢 `COUNT(*) FROM players WHERE balance > 0`，透過 `/api/economy/stats` 公開。`%syncmoney_total_players%` PAPI 擴充功能亦傳回此資料庫導出的數值。
- **Toast 通知系統**：`NotificationStore` 提供統一的通知管理，包含 `addToast()`、`addAlert()`、`addBreakerNotification()` 與 `addTransactionNotification()`。`NotificationToast.vue` 元件具備 CSS 過渡動畫。`client.ts` 中的全域錯誤攔截器會自動為所有 API 回應顯示成功／錯誤 Toast 通知。

#### SSE 與即時通訊
- **`node_status` SSE 事件（部分實作）**：`NodeHealthChecker` 廣播 `{"type":"node_status","event":"NodeStatusChange",...}` 至 `system` SSE 通道。前端 `useSSE.ts` 處理常式尚未實作（列入未來版本追蹤）。

#### 程式碼重構
- **VaultProvider 重構**：將 `SyncmoneyVaultProvider`（1276 行）拆分為 7 個職責明確的類別：
  - `SyncmoneyVaultProvider` — 輕量 Vault 外觀類別（505 行）
  - `VaultProviderCore` — 核心 Vault API 委派（658 行）
  - `VaultPlayerHandler` — 玩家帳戶與餘額操作（165 行）
  - `VaultTransferHandler` — 轉帳關聯與回滾（317 行）
  - `VaultBankHandler` — 使用 Lua 腳本的銀行操作（524 行）
  - `VaultLuaScriptManager` — Lua 腳本 SHA 快取（90 行）
  - `VaultPluginDetector` — 透過 StackWalker 偵測呼叫插件（56 行）
- **SyncmoneyConfig 重構**：採用外觀模式（Facade Pattern），包含 18 個子設定類別（`RedisConfig`、`DatabaseConfig`、`NodeConfig`、`CircuitBreakerConfig` 等），提供統一的 `config.redis()`、`config.database()` 等存取器。

### 已修復 (Fixed)

- **PostgreSQL 索引建立**：修復影子同步 Schema 初始化時 PostgreSQL 的 `CREATE INDEX IF NOT EXISTS` 相容性。
- *（註：1.1.1-patch1 的所有關鍵修復均已整合）*

---

## [1.1.0] - 2026-03-20

### 已新增 (Added)

#### Web 介面與管理儀表板
- **Web 管理後台**：全新內建的管理 Web 介面，採用 Vue 3、Vite 與 TailwindCSS 建構（`syncmoney-web`）。
- **國際化（i18n）**：完整支援英文（`en-US`）與繁體中文（`zh-TW`），並支援無縫動態切換。
- **主題支援**：包含動態深色／淺色主題切換與狀態持久化。

#### 開發者 API 與 REST API
- **系統 API**：提供全新的 `/api/system/status`、`/api/system/redis`、`/api/system/breaker` 與 `/api/system/metrics` 端點。
- **經濟與審計 API**：無縫獲取總供給量、玩家餘額、排行榜統計數據與交易審計日誌。
- **設定與配置 API**：用於動態讀取與更新插件設定的 REST 介面（`/api/config/reload`）。
- **即時通訊**：新增 WebSocket 支援以提供即時交易與斷路器告警，並搭配伺服器傳送事件（SSE）。

#### 核心系統與基礎架構
- **初始化管理器**：導入 `PluginInitializationManager`，可靠地協調元件的啟動／關閉相依性。
- **Schema 管理器**：新增 `SchemaManager` 用於資料庫 Schema 增量升級、自動索引建置與欄位補全。
- **設定與訊息合併器**：導入 `ConfigMerger`，能安全地自動更新設定檔（v1.0.0 → v1.1.0）而不進行破壞性覆蓋。
- **Lua 支援升級**：擴充 Redis Lua 腳本，新增原子操作（`atomic_bank_deposit`、`atomic_bank_withdraw`、`atomic_bank_transfer`）。
- **測試**：大幅提升測試涵蓋率，包含原生 Java 單元測試與前端 Playwright 端到端（E2E）測試套件。

#### 事件系統（開發者 API）
- 新增 `SyncmoneyEventBus`，作為內部與第三方開發者的中央事件匯流排。
- 導入 `AsyncPreTransactionEvent`、`PostTransactionEvent`、`ShadowSyncEvent` 與 `TransactionCircuitBreakEvent`。

#### 安全性與防護
- **API 防護**：包含自動檢測並封鎖不安全的 `change-me` API Key，以及保護 REST API 的 `RateLimiter` 機制。
- **玩家防護系統**：精確的單一玩家交易速率限制，內建自動封鎖與警告功能以防止刷錢漏洞。
- **Discord 告警**：針對資源異常飆升、網路故障或斷路器觸發狀態提供即時 Webhook 通知。

### 已變更 (Changed)

- **程式碼重構**：重大程式碼架構調整；將邏輯自 `Syncmoney.java` 及指令（`PayCommand`）解耦，移至結構化的管理器（如 `PayConfirmationManager`、`PluginContext`）與儲存層，大幅消除技術債。
- **註解標準化**：對後端、Web 前端與 PAPI 擴充功能的 Javadoc 及區塊註解進行大規模全域重構。強制執行嚴格的 `[SYNC-XXX]` 英文標籤標準，並清理所有已廢棄的行內與非英文註解。
- **審計日誌記錄**：透過全新的 `HybridAuditManager` 與強大的批次處理機制提升 `AuditLogger` 的吞吐量。
- **資料庫 Schema**：透過為審計日誌新增資料庫索引，顯著提升查詢效能。
- **設定升級**：將 `config.yml` 提升至 `config-version: 11`，包含全新的 Web 管理後台設定與 `decimalPlaces` 設定。
- **PAPI 擴充功能更新**：整合遺失的 `expansions.yml`，並增強 `syncmoney-papi-expansion` 的內部版本相容性。
- **影子同步疊代**：重構狀態回滾邏輯，以更平滑地解決跨伺服器資料不一致問題。

### 已修復 (Fixed)

- **訊息系統**：捨棄 `CMIEconomyListener` 中的硬編碼訊息，將其統一納入 `MessageHelper` 動態對映。
- **Web 介面 Bug**：修正靜態模擬資料版本，並修復損壞的硬編碼 i18n 佔位符（例如頁面標題）。
- *（註：1.1.0-patch1 的所有關鍵修復均已整合：`/syncmoney migrate` 註冊問題、Folia 相容性退化、內部路徑變數以及 Adventure Text API 空頁面 Bug）*

---

## [1.0.0] - 2026-03-01

### 已新增 (Added)
- 首次發布
- 透過 Redis Pub/Sub 實現**跨伺服器經濟同步**
- 實現高效能的 **Redis 分散式快取**
- **資料庫支援**：SQLite、MySQL、PostgreSQL
- **Vault API 整合**：相容基於 Vault 的插件
- **PlaceholderAPI 擴充功能**：`%syncmoney_balance%`、`%syncmoney_balance_formatted%` 等
- **CMI 經濟遷移工具**：匯入現有的 CMI 經濟資料
- **Web 管理介面**：基礎儀表板與設定
- **審計日誌系統**：具搜尋功能的完整交易歷史紀錄
- **斷路器防護**：在服務中斷期間防止經濟刷錢漏洞
- **影子同步機制**：背景資料一致性驗證
