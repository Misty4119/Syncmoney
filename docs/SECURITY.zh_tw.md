# 安全政策

Syncmoney 是經濟插件；安全問題可能影響餘額、憑證、跨服同步、稽核資料或內嵌管理服務。若懷疑有漏洞，請私下回報，讓維護者有合理機會調查後再公開。

英文原文：[`SECURITY.md`](../SECURITY.md)

## 支援版本

安全修正以目前儲存庫 release line 為主要開發目標。本文件更新時，根目錄 `build.gradle` 的版本為 `1.3.1`。

舊版可能不再收到修正。若問題只在舊 build 發生，實務可行時請也在目前 release 或 current `main` 重現，並附上實際測試版本。

## 回報漏洞

請以電子郵件私下寄至 **security@noie.fun**。這是本專案正式的安全回報信箱。

若電子郵件暫時不可用，且 `Misty4119/Syncmoney` 已啟用 GitHub Private Vulnerability Reporting / Security Advisory，可使用該私密管道。請勿改在公開 issue 或社群貼出 exploit 細節。

不要在公開 issue 放入可用 exploit、credentials、私人 server address、database dump、API key、RCON secret 或 player data。

請盡量提供：

- 受影響的 Syncmoney version/commit 與 server implementation/version；
- economy mode（`local`、`local_redis`、`sync`、`cmi`）；
- Redis、SQL、CMI、PlaceholderAPI、Web Admin、proxy/network topology 等相關元件；
- 最小重現步驟與實際結果；
- 預期結果與安全影響；
- 已移除 secrets 的 log/config excerpt；
- 是否需要 authentication、permission node、network access 或 race/concurrency 條件；
- 可用的 mitigation 或 patch。

維護者可能要求額外重現資料。本專案**不承諾回覆或修復 SLA**。

## 高影響區域

尤其重視下列問題：

- 貨幣被建立、複製、遺失或未授權轉移；
- transaction limit、player protection、circuit breaker 被繞過；
- stale/replayed update 破壞單調 balance version；
- Pub/Sub spoofing、echo loop 或 duplicate handling 改變餘額；
- queue saturation 或 shutdown 靜默遺失已接受的 economic write；
- Folia/Canvas 上不安全的 scheduler 存取造成狀態損壞；
- Web Admin auth、authorization、CORS、node proxy、config sync 或 secret exposure；
- SQL/Redis credential exposure、injection、path traversal、不安全 file export；
- 經 `/syncmoney`、Vault 或 optional integration boundary 提權。

## Web Admin 部署

Web Admin 是特權管理介面：

- 對外暴露前先更換 `web-admin.security.api-key`。
- shipped config 使用 `change-me-in-production`；validation 會警告，但 `WebServiceManager` 目前只在 key 精確等於 `change-me` 時自動停用，不能依賴此 guard 保護未修改的預設值。
- 綁定可信介面，或放在正確設定的 reverse proxy/firewall 後方。
- CORS origin 應限制為可信來源；plugin 會對 `*` 發出警告。
- 流量離開可信本機網路時，應在 reverse proxy 使用 TLS。
- Node API key 視為 secret，限制 central 與 managed node 的可達性。
- `/health` 刻意不需 authentication；一般 `/api/*` 會走 Bearer API-key auth，再依 endpoint 行為處理。
- 前端目前實作的 live transport 是 SSE；`/ws` 尚未完整，不應視為 hardened production channel。

## Secret 處理

不得 commit 真實的：

- Web Admin / node API key；
- Redis/SQL credentials；
- migration / Shadow Sync database credentials；
- Discord webhook URL；
- RCON / acceptance-test credentials；
- 私人 player/audit export。

若真實 credential 已被 commit，之後刪除檔案或字串並不會撤銷 credential，也不會移除 history。應先 rotate/revoke，再評估是否重寫 history 與處理下游複本。

## 變更的安全要求

經濟相關變更必須保留 `BigDecimal` normalization、insufficient-funds check、transfer ordering/atomicity、單調 version、已接受寫入 durability、echo/duplicate suppression。Vault/placeholder/entity hot path 不得加入阻塞 network/database I/O。

Scheduler-sensitive 變更要遵守 Paper/Folia entity ownership；CMI mutation 與 player/entity 操作必須在 owning entity scheduler 上執行。

Security-sensitive PR 應提供針對受影響 boundary 的 focused test 或可重現 acceptance 步驟。Cross-server、lifecycle、storage、scheduler 變更，在依賴可用時應依 `tools/plugdev/README.md` 驗證。

## 公開揭露

修正可用後，維護者與回報者應協調公開資訊的時間與細節，使 operator 能升級，同時避免不必要地暴露尚未修補的 installation。回報者若願意且情境適合，可給予 credit。
