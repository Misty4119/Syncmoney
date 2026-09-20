# AGENTS.md — Syncmoney

本文件是本儲存庫中程式代理與自動化貢獻者的操作指南。實作與可執行設定是最高事實來源；若文件與程式不一致，先以程式驗證行為，並在同一變更更新文件。

英文原文：[`AGENTS.md`](../AGENTS.md)

## 專案範圍與事實來源

Syncmoney 是 Minecraft 經濟插件，提供 Vault-compatible economy、選用 VaultUnlocked/CMI、Redis 跨服 balance synchronization、relational persistence、audit/guard、embedded Web Admin 與 PlaceholderAPI expansion；不負責同步任意 permission 或第三方 command。

目前 root `build.gradle` release 為 `1.3.2`，config schema `12`，build toolchain Java 21；三者是不同版本領域。

- Core：`src/main/java/noietime/syncmoney`
- Runtime defaults：`src/main/resources`
- 設定：`src/main/resources/config.yml`
- Descriptor：`src/main/resources/plugin.yml`
- PAPI：`syncmoney-papi-expansion`
- Web：`syncmoney-web`
- Embedded bundle：`src/main/resources/syncmoney-web/dist`
- 公開概覽：`README.md`
- Architecture/API/developer 文件：`docs/`
- Acceptance：`tools/plugdev/README.md`

歷史文件可能落後。API 以 actual handler、`RouteRegistry`、`HttpHandlerRegistry` 為準；設定要把 default YAML、`SyncmoneyConfig`、相關 config object 與 `ConfigReloadPolicy` 一起讀。

## 工作順序

1. 修改前查看 `git status --short --branch`、history、implementation、caller、test、config 與 docs。
2. 保留不相關的 worktree changes，不得為了乾淨工作樹覆蓋 user work。
3. rename/remove class、wrapper、executor、listener、schema、subscription、server 前先追蹤 reflection 使用者與 lifecycle ownership。
4. 碰 Bukkit/Paper entity 前確認 scheduler ownership。
5. 經濟變更先找出 memory state、accepted-write boundary、persistence path、version 與 recovery。
6. 保留本機 `Proposal/` 與 `reference/`。

檢查設定時不要輸出 credentials。

## 經濟不變條件

- 金額使用 `BigDecimal` 與既有 normalization。
- 失敗/拒絕交易不得建立或消滅資金。
- `EconomyFacade` 是 memory-first；Vault hot path、placeholder、tab completion、entity task、player-message path 不得加入 blocking Redis/SQL/Mojang/filesystem/network I/O。
- 遵循 `EconomyFacade`、`MemoryStateManager`、`TransactionWriter`、`TransferOrchestrator` 與 mode router 的 state/version contract。
- 已接受 queued write 要能跨一般 logout/teleport timing，shutdown 時在 dependency 存活期間排空。
- 保留 monotonic version、Pub/Sub echo suppression；延遲 remote/CMI work 套用前重新檢查 freshness。
- insufficient funds、concurrent write、queue saturation、partial persistence failure、recovery 都是 correctness case。
- `cmi` mode 由 CMI 維持 authority；CMI player mutation 在 owning entity scheduler 執行。
- Migration 與 Shadow Sync 是明確 workflow，不是隱式 live-authority switch。

內部 mode 為 `LOCAL`、`LOCAL_REDIS`、`SYNC`、`CMI`；設定也接受 `auto`。

## Storage 與 synchronization

Shared SQL `players` table 儲存 UUID、player name、`DECIMAL(20,2)` balance、monotonic version、last server、updated timestamp。Audit、baltop、local SQLite、schema-version、Shadow 另有各自 workflow。

代表性 Redis contract：`syncmoney:balance:{uuid}`、`syncmoney:version:{uuid}`、`syncmoney:online:players`、`syncmoney:online:player:*`、`syncmoney:baltop`、audit recent/index/dedup 與 bank/bank-owner/bank-version keys。

主要 Pub/Sub channel 是 `syncmoney:balance:update` 與 `syncmoney:cmi:balance:update`。修改 message 時維持 compatibility field、version ordering、source identity 與 echo suppression。

## Scheduler 規則

Syncmoney 使用 Paper common scheduler API，需維持 Paper、Folia、Canvas 安全。`folia-supported: true` 只是 metadata。

- 純 I/O/data：async scheduler 或 owned executor。
- Plugin/global operation：global region scheduler。
- Player message、CMI mutation、teleport、entity state：player entity scheduler。
- 全域 collection 先 snapshot，再派到各 player owner。
- Async callback 碰 entity 前重新進入正確 owner。
- 使用 `teleportAsync`，保留 destination/cause 並取消過時 deferred request。
- 不得讓 region 互相阻塞等待。

使用既有 player lookup/routing helper，並處理 disconnect/retirement。

## Event semantics

`TransactionWriter` 會觸發 `AsyncPreTransactionEvent`，且 cancellation 會生效。`PostTransactionEvent` 在 result 確定後發出，供 SSE/WebSocket 等 telemetry 使用。

`SyncmoneyEventBus` 是內部 bus，不是 Bukkit event bus；先讀 dispatch 實作再判斷 thread semantics。

## Optional-module lifecycle

- 每個 module 只有一個 owner 負責 create/start/close。
- `BreakerManager` 擁有唯一 `PlayerTransactionGuard`，其他 component 接收它。
- Config 未啟用前不要建立 optional executor、schedule、schema、folder、subscription、HTTP listener。
- 保存 cancellation handle；normal shutdown 與 partial-init failure 都要釋放。
- 關閉一項 resource 不得阻止其他 resource cleanup。
- Audit cleanup/export 依賴 parent audit switch；per-player protection 與 global breaker 是不同開關。
- Disabled module 可保留 lightweight facade，但不得啟動 heavy resource。

目前 shutdown order：web → commands → permissions/listeners/sync → event consumers/drain → economy → audit → breaker → baltop save → storage → event bus cleanup。維持 producer 先停、consumer 排空、dependency 後關。

## 設定與 reload

`SyncmoneyConfig` 是 runtime snapshot。`ConfigReloadPolicy` 目前只允許 `display`、`pay`、`permissions`、`admin-permissions`、`debug` live change。

Connection、authority、scheduler、module owner 變更除非 policy 明確支援，否則需 restart。要在發布新的 runtime snapshot 前明確回報 restart-required change，不要 half-apply config。`server-name` 必須設定；空白會阻止正常運作。

Web Admin 預設 API key 是 `change-me-in-production`。Config validation 會警告這個值，但目前 `WebServiceManager` 的 auto-disable 邏輯實際只比對 exact `change-me`。因此不能把 shipped placeholder 描述為會自動安全停用；對外暴露 Web Admin 前，operator 必須先替換預設 API key。

## Commands 與 permissions

Top-level command：`/money`、`/pay`、`/baltop`、`/syncmoney`。

`/syncmoney` 註冊 `migrate`、`audit`、`admin`、`web`、`monitor`、`debug`、`sync-balance`、`test`、`reload`、`version`，以及條件式 `breaker`、`shadow`、`econstats`。

重要 permission 包含 `syncmoney.money`、`syncmoney.money.others`、`syncmoney.pay`、`syncmoney.admin` 與 `syncmoney.admin.*`。文件要同時看 router 與 subcommand check 的實際效果。

## Web/API 規則

Backend 是 Undertow。一般 authenticated REST request 使用 `Authorization: Bearer <api-key>`；`/health` 是 health probe。

目前 frontend live stream 為 `/api/sse`。Frontend 會先以 API key 呼叫 `POST /api/auth/ws-token` 取得 session token，再使用該 token 建立 SSE connection。不要恢復舊的 `/sse` 文件。

`/ws` 目前 transport 不完整，不得描述為 production live channel。完整 authentication 與已知限制以 [`API_REFERENCE.zh_tw.md`](API_REFERENCE.zh_tw.md) 為準。

`SystemApiHandler` 與 `NodesApiHandler` 有重複 node GET route；registry 採 replacement semantics，且 `NodesApiHandler` 較晚註冊，因此它生效。

API route 變更要同步更新雙語 reference，驗證 auth/CORS/rate-limit/proxy trust/node auth，並避免 Undertow I/O thread 上的 blocking storage work。

## PlaceholderAPI

Expansion identifier 是 `syncmoney`，persistent，以 lazy reflection 連接 core。Placeholder family 包含 balance variants、rank/my-rank、total supply/players、version、online players、`top_<n>` 與 target-player balance variants。

Global/rank work 使用 cache/async；現行 bounded cache 約 5 秒 expiry，最多約 10,000 cached/pending entry，必須維持 non-blocking behavior。

## 驗證

Core/backend：

```powershell
.\gradlew.bat test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar
```

Frontend：

```powershell
cd syncmoney-web
pnpm typecheck
pnpm test:unit --run
pnpm build
```

Build toolchain 是 Java 21；runtime Java 依 Minecraft server，PlugDev 對較新 Paper 26.1+ acceptance environment 記載 Java 25。

scheduler/storage/lifecycle/CMI/cross-server 變更要執行相關 PlugDev matrix，只回報實際測試範圍。Compilation alone 不能證明 Folia safety、queue durability 或 distributed consistency。收尾前執行 `git diff --check`、`git status --short`，並搜尋 stale release、endpoint、artifact name、removed behavior。

## Release hygiene

Root `build.gradle` 擁有 release version，PAPI 繼承它。涉及 web asset 的 release 要同步更新 frontend metadata 與 `src/main/resources/syncmoney-web/dist`。

Core repository 是 Web Admin 的 canonical source。Core release tag 不帶前綴；公開 Web mirror 使用對應的 `v` 前綴 tag，並發佈由 `WebDownloader` 使用的 deterministic source archive。Preview build 可以使用 `releaseVersion` Gradle property；stable source metadata 則保留在 committed source。

Frontend 變更後在 `syncmoney-web` 執行 `pnpm build:embedded`。它會清空/覆蓋 embedded `dist` tree，並在同一操作更新 `WebAdminServer.extractIndividualFiles`。Generated bundle 與 Java file 必須一起 review。

預期 artifact：

- `build/libs/Syncmoney-<version>.jar`
- `syncmoney-papi-expansion/build/libs/SyncmoneyExpansion-<version>.jar`
- `build/acceptance/SyncmoneyAcceptance.jar`

不得 commit server world、runtime secret/config、database、log、local test state、report、editor/agent cache、dependency directory。Embedded web bundle 與 Gradle wrapper JAR 是刻意 tracked 例外。

若 credential 已進入 tracked history，只改最新檔案不足以解決，應 rotate 並評估 history/exposure。

## 文件雙語一致性

Canonical project docs 為英文；繁中 companion 使用 `.zh_tw.md` 並放在 `docs/`。

- `AGENTS.md` ↔ `docs/AGENTS.zh_tw.md`
- `CLAUDE.md` ↔ `docs/CLAUDE.zh_tw.md`
- `CONTEXT.md` ↔ `docs/CONTEXT.zh_tw.md`
- `SECURITY.md` ↔ `docs/SECURITY.zh_tw.md`
- `CODE_OF_CONDUCT.md` ↔ `docs/CODE_OF_CONDUCT.zh_tw.md`
- `CONTRIBUTING.md` ↔ `docs/CONTRIBUTING.zh_tw.md`
- `docs/ARCHITECTURE.md` ↔ `docs/ARCHITECTURE.zh_tw.md`
- `docs/API_REFERENCE.md` ↔ `docs/API_REFERENCE.zh_tw.md`
- `docs/DEVELOPER_GUIDE.md` ↔ `docs/DEVELOPER_GUIDE.zh_tw.md`

不得宣稱未經觀察的 support、performance、security guarantee 或 runtime coverage。
