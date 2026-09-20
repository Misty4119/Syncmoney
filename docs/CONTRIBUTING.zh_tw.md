# 貢獻 Syncmoney

感謝協助改善 Syncmoney。本專案接受 bug fix、economy/synchronization 改善、scheduler/lifecycle 修正、Web Admin、PlaceholderAPI、文件與可重現的 acceptance tooling。

英文原文：[`CONTRIBUTING.md`](../CONTRIBUTING.md)

貢獻前請閱讀根目錄 `AGENTS.md`、`CONTEXT.md`、`SECURITY.md` 與 `docs/` 相關文件。`AGENTS.md` 的正確性規則即使小變更也適用。

## 開發需求

- Git
- JDK 21，供 Gradle toolchain/build 使用
- repository Gradle wrapper（Windows 為 `gradlew.bat`）
- Node.js 與 pnpm，供 `syncmoney-web` 使用
- 相容的 Paper/Folia/Canvas 測試 server，供 runtime acceptance
- 只有測試需要 Redis/SQL 的 mode/feature 時才需要對應服務
- 測試 CMI integration 時需自行提供合法授權的 CMI；repository 不散布 CMI

較新的 Minecraft server 可能要求比專案 compiler 更高版本的 runtime JVM。依 `tools/plugdev/README.md` 為準；目前 PlugDev 對較新 Paper 26.1+ 環境記載 Java 25。

## 儲存庫配置

| 路徑 | 用途 |
|---|---|
| `src/main/java/noietime/syncmoney` | Core plugin implementation |
| `src/main/resources` | 預設 config/message、plugin descriptor、Lua/assets、embedded web bundle |
| `syncmoney-papi-expansion` | 獨立 PlaceholderAPI expansion |
| `syncmoney-web` | Vue/Vite Web Admin frontend |
| `docs` | Architecture、API、developer 與繁中文件 |
| `tools/plugdev` | 外部實機 acceptance tooling |
| `src/acceptance` | `acceptanceJar` 使用的 acceptance plugin source |

根目錄 `build.gradle` 擁有 release version，PAPI module 繼承它。涉及 web assets 的 release 要同步更新 web package/public metadata 與 embedded frontend bundle。

## 修改前

1. 執行 `git status --short --branch`，保留不相關的本機變更。
2. 閱讀實作、caller、test、預設設定與公開文件。
3. 重新命名/移除相容性邊界前追蹤 reflection 使用者。
4. 修改 lifecycle 前確認 resource owner。
5. 修改 scheduler-sensitive code 前確認 entity ownership。
6. 經濟變更要找出 accepted-write boundary、memory/version update、persistence path 與 recovery behavior。

調查問題時不得輸出或 commit 真實 credentials。

## 經濟正確性

金額使用 `BigDecimal` 與既有 normalization；失敗或被拒交易不得建立或消滅資金。

Vault API 是同步的，因此 hot-path read 刻意由 memory 提供。不要把阻塞 Redis、SQL、Mojang、filesystem 或 remote HTTP call 加入 Vault method、placeholder、tab completion、entity task 或 player-message path。

使用既有架構：

- `EconomyFacade`：公開 economy boundary；
- `MemoryStateManager`：memory balance/version state；
- `TransactionWriter`：accepted mutation 與 transaction event；
- `TransferOrchestrator`：cross-account transfer；
- `EconomyModeRouter` 與 mode strategy：authority/persistence 決策。

保留單調 balance version、duplicate/echo suppression、insufficient-funds 行為、queue backpressure/recovery、shutdown 時已接受寫入的 draining。延遲 remote/CMI update 套用前要重新檢查 version freshness。

`cmi` mode 由 CMI 維持 economy authority。Migration 與 Shadow Sync 不得變成隱性的 live-authority switch。

## Scheduler 與 Folia

專案 compile against Paper API，並以 common scheduler API 支援 Paper/Folia/Canvas ownership。

- I/O/data：async scheduler 或明確擁有的 background executor。
- Plugin/global work：global region scheduler。
- Player message、teleport、CMI mutation、entity state：player entity scheduler。
- Teleport 使用 `teleportAsync`。
- 全域 collection 先 snapshot，再把各 player 工作派到正確 owner。
- Async callback 碰觸 Bukkit entity 前要重新進入正確 owner。
- 不得讓一個 region 阻塞等待另一個 region。

`folia-supported: true` 只是 metadata，不是新程式 thread-safe 的證據。

## Lifecycle 與 optional module

每個 executor、scheduler、listener、queue、subscription、HTTP server、storage connection 與 optional service 只能有一個 owner。配置停用時，不要先建立昂貴資源；保存 cancellation/close handle。

`Syncmoney.onDisable()` 的 shutdown order 依 dependency 設計。已接受的 economic event 要在 economy/audit/storage dependency 關閉前排空。重排 shutdown 需要明確理由與 test/acceptance evidence。

停用模組應保持低成本，不得偷偷建立只屬於該功能的 schema、executor、folder、subscription 或 listener。

## 設定變更

`SyncmoneyConfig` 是 runtime snapshot。`ConfigReloadPolicy` 目前只允許 `display`、`pay`、`permissions`、`admin-permissions`、`debug` live change。

若新設定需要新的 owner、connection、schema、subscription 或 authority change，除非 reload implementation 被安全擴充，否則視為 restart-required。

新增/修改設定時：

1. 更新 default YAML；
2. 更新 parsing/validation；
3. 將 config schema migration/version 與 plugin release version 分開考慮；
4. 更新 README 與雙語 docs；
5. 非簡單行為要加入 focused validation/reload test。

## Web Admin 與 REST API

Backend 是 Undertow。一般 `/api/*` 使用 `Authorization: Bearer <api-key>`；`/health` 不需 authentication。前端 live stream 是 `/api/sse`，session token 由 `POST /api/auth/ws-token` 發出。

不要把 `/ws` 描述為 production-ready；目前 WebSocket transport 不完整。

API 變更時：

- 檢查 handler、`RouteRegistry`、`HttpHandlerRegistry`、authentication、CORS；
- 維持 shared success/error response convention；
- Undertow I/O thread 上不得執行 blocking storage/network work；
- 維持 node-to-node auth 與 secret handling；
- 同步更新 `docs/API_REFERENCE.md` 與 `docs/API_REFERENCE.zh_tw.md`；
- contract 改變時同步 frontend API type/client/test。

Registry 對相同 method/path 使用 map replacement semantics，新增 route 前要檢查 duplicate。

## PlaceholderAPI expansion

PAPI expansion 是獨立 JAR，刻意以 reflection discover core plugin。除非 packaging contract 有意改變，應維持這個相容性邊界。

Placeholder evaluation 必須非阻塞；昂貴/全域工作使用 bounded cache 與 asynchronous refresh。identifier 或 formatting 改變時更新 expansion test 與 placeholder docs。

## 文件

Canonical operational/community docs 為英文；繁中 companion 位於 `docs/` 並使用 `.zh_tw.md`，維持語意一致。

程式改變 user-visible behavior 時，更新相關 README/config comment 與 architecture/API/developer docs。不得把 planned behavior 當成 implemented behavior。

`CHANGELOG.md` 是英文 changelog；繁中 companion 位於 `docs/CHANGELOG.zh_tw.md`。

## Build 與測試

依變更範圍執行相關 checks。標準 repository validation：

```powershell
.\gradlew.bat test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar
```

Web frontend：

```powershell
cd syncmoney-web
pnpm typecheck
pnpm test:unit --run
pnpm build
```

預期 artifact：

- `build/libs/Syncmoney-<version>.jar`
- `syncmoney-papi-expansion/build/libs/SyncmoneyExpansion-<version>.jar`
- `build/acceptance/SyncmoneyAcceptance.jar`

## Release 流程

Core repository 是 Web Admin 的 canonical source。穩定版本保存在 root `build.gradle`；preview build 由 CI 傳入 `-PreleaseVersion`，因此不需要把 alpha/beta 版本提交到 source metadata。目前 repository variable `NEXT_VERSION` 選擇下一條 preview line：`1.3.3`。

Pull request 與 `main` 都會執行 Java/Web checks 並產生 CI artifact；preview artifact 預設命名為 `1.3.3-alpha.<run-number>`，只保留在 GitHub Actions，不會建立公開 Release。

Core repository tag 不帶前綴：

- `1.3.3-alpha.1` 或 `1.3.3-beta.1` 發佈 prerelease；
- `1.3.3` 在 `production-release` environment 審核通過且 `CHANGELOG.md` 有對應 section 後發佈 stable release。

Release workflow 會把 canonical `syncmoney-web` source 同步到 [Misty4119/Syncmoney-web](https://github.com/Misty4119/Syncmoney-web)，套用 release version，先推送對應的 `v` 前綴 tag。Web workflow 先發佈 deterministic `syncmoney-web.tar.gz` 與 checksum；Web release 存在後，core workflow 才發佈 plugin 與 PlaceholderAPI JAR。跨 repository job 需要 fine-grained `SYNC_WEB_RELEASE_TOKEN` secret，且 write access 限定在 Web repository。

Frontend 有變更時，在 `syncmoney-web` 執行 `pnpm build:embedded`。它會清空並覆蓋 `src/main/resources/syncmoney-web/dist`，同時重生 `WebAdminServer.extractIndividualFiles` 的 `rootFiles`、`assetFiles`、`iconFiles` arrays。這些產生檔必須一起 review 與 commit。

scheduler、storage、lifecycle、cross-server、CMI 或 server-version compatibility 變更，應執行適用的 PlugDev matrix，並只報告實際完成的 runtime check。Compile/unit test 綠燈不能證明 Folia safety 或 distributed durability。

## Pull request

每個變更保持可 review 且 scope 明確。說明具體問題、變更後行為與重要 tradeoff；bug 要附 reproduction，並列出實際執行的 verification。

提交前：

```powershell
git diff --check
git status --short
```

檢查完整 diff，包括 generated/embedded asset。不要包含 server world、含 secrets 的 runtime config、database file、log、editor cache、dependency directory 或本機 PlugDev state。

## 安全與行為

漏洞依 [`SECURITY.md`](../SECURITY.md) 私下回報，不要公開 exploit。專案空間參與行為遵守 [`CODE_OF_CONDUCT.md`](../CODE_OF_CONDUCT.md)。
