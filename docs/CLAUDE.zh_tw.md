# CLAUDE.md — Syncmoney

本文件提供 Claude 系列程式代理在此儲存庫中的精簡操作契約。進行非小型變更前，請先閱讀根目錄 `AGENTS.md` 的完整代理指南與 `CONTEXT.md` 的專案快照。

## 任務

Syncmoney 是相容 Vault 的 Minecraft 經濟插件，支援選用 VaultUnlocked/CMI 整合、Redis 跨伺服器同步、SQL/本機持久化、稽核與保護模組、Undertow Web 管理服務，以及獨立的 PlaceholderAPI 擴充。

正確性優先順序為：不得憑空增減資金、保留 Redis-backed per-account ordering 並明確記錄 CMI legacy timestamp version 不具 global ordering、區域執行緒安全、生命週期擁有權清楚、熱門路徑不得阻塞，之後才是易用性與效能改善。

## 真實來源

文字說明若與程式或可執行設定衝突，以後者為準：

- `src/main/java/noietime/syncmoney`
- `src/main/resources/config.yml`
- `src/main/resources/plugin.yml`
- `build.gradle`
- `syncmoney-papi-expansion`
- `syncmoney-web`
- `tools/plugdev/README.md`

公開文件位於 `README.md` 與 `docs/`。行為變更時，英文與 `.zh_tw.md` 文件必須同步更新。

## 經濟規則

- 金額使用 `BigDecimal` 與既有正規化規則。
- Vault 面向的讀取必須維持記憶體優先，不得加入阻塞 Redis、SQL 或網路 I/O。
- 寫入應走既有 facade / state / writer / orchestrator 路徑。
- 已接受的寫入在玩家登出與關機流程中必須可被妥善排空。
- 保留 Redis-backed per-account version ordering 與 Pub/Sub 回音抑制。CMI 維持 legacy timestamp-derived version，不保證 global monotonic。
- 延遲的非同步工作（含 CMI entity callback）套用前要重新檢查本機已觀察到的新鮮度。
- `cmi` 模式由 CMI 維持經濟權威；玩家異動必須在該玩家的 entity scheduler 上執行。
- Migration 與 Shadow Sync 是明確工作流程，不是透明切換即時權威的機制。

## Scheduler 規則

- I/O / 純資料工作：async scheduler 或明確擁有的 executor。
- 插件層級 / 全域狀態：global region scheduler。
- 玩家或實體動作、訊息、CMI 異動、傳送：entity scheduler。
- 傳送使用 `teleportAsync`。
- 不得讓一個 region 阻塞等待另一個 region。
- 非同步工作完成後，碰觸 Bukkit entity 前要重新進入正確的 owner scheduler。

Paper/Folia/Canvas 安全性必須由 ownership 正確性證明，不能只因 `folia-supported` metadata 就推定安全。

## 生命週期規則

選用模組停用時必須保持低成本。建立 executor、schema、subscription、目錄、HTTP listener 或排程前先檢查設定。每項資源只能有一個 owner，要保存取消/關閉 handle，且部分初始化失敗時能乾淨回收。

關機時先停止 producer，再在 audit、Shadow、storage 等依賴仍存活時排空已接受的交易事件。不得任意重排 `Syncmoney` 的關機順序。

## 設定

`SyncmoneyConfig` 是 runtime snapshot。目前只有 `display`、`pay`、`permissions`、`admin-permissions`、`debug` 可即時重載。連線、權威或模組 owner 變更，除非 `ConfigReloadPolicy` 明確允許，否則視為需要重啟。

Web Admin 預設 API key 為 `change-me-in-production`。Key 空白或 trim 並忽略大小寫後等於 `change-me` / `change-me-in-production` 時，Web Admin 會自動停用，但 economy plugin 仍正常啟動。

## Web 與 API

Web Admin 後端使用 Undertow。REST 通常使用 `Authorization: Bearer <api-key>`；`/health` 為健康檢查。前端即時串流是 `/api/sse`，session token 由 `POST /api/auth/ws-token` 取得。

所有 `/ws` request 都回 `503 WEBSOCKET_UNAVAILABLE`，不得文件化成 production WebSocket transport。

修改路由時，需同時檢查 handler、`RouteRegistry`、`HttpHandlerRegistry`，並同步更新中英文 API reference。

## PlaceholderAPI

PAPI expansion 是獨立建置的 JAR，使用 reflection 作為與 core 的相容性邊界。Placeholder 求值必須非阻塞；昂貴/全域資料使用快取與非同步刷新。

## 工作方式

修改前先查看 `git status`、呼叫端/歷史、測試、設定與文件，保留不相關的現有修改。不要輸出 secrets。重新命名公開/core 類別前追蹤 reflection 使用者；移除 wrapper 前先確認生命週期 owner。

優先採取小型、保持行為的變更。可能造成資金增減、版本排序破壞、已接受寫入遺失或 scheduler ownership 改變時，要加入有意義的 regression test。

## 驗證

Core：

```powershell
.\gradlew.bat test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar
```

Web：

```powershell
cd syncmoney-web
pnpm typecheck
pnpm test:unit --run
pnpm build
```

scheduler、storage、lifecycle、cross-server 變更要用 PlugDev，並只報告實際測過的 matrix。收尾前執行 `git diff --check`。

## 發布事實

根目錄 Gradle release metadata 目前仍為 `1.3.2`，待 release task 更新；1.4.0 foundation branch 使用 Java 25 與 Gradle 9.8.0。保留的 1.3.2 baseline 使用 Java 21／Gradle 9.1.0。PAPI 繼承根版本。主要產物為 `Syncmoney-<version>.jar`、`SyncmoneyExpansion-<version>.jar`、`SyncmoneyAcceptance.jar`。

涉及 web 資產的 release，frontend release metadata 與 `src/main/resources/syncmoney-web/dist` 必須一起更新。Core repository 是 canonical frontend source；Core tag 不帶 `v`，公開 Web mirror 使用 `v` tag 並先發佈 deterministic source archive。Frontend 變更使用 `pnpm build:embedded`，確保 embedded bundle 與 `WebAdminServer.extractIndividualFiles` 同步。

## 文件語言

根目錄操作與社群文件以英文為 canonical；繁體中文對應檔放在 `docs/`，使用 `.zh_tw.md`。兩種語言要維持語意一致，不得只在單一語言加入承諾。
