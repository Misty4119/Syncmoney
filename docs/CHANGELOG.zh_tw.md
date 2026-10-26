# 更新日誌 (Changelog)

本專案的所有重大變更都將記錄於此檔案中。

本變更日誌格式基於 [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)，且本專案遵循 [語意化版本](https://semver.org/spec/v2.0.0.html)。

## [Unreleased]

### 已修復 (Fixed)
- /syncmoney migrate stop 現在也會要求 LOCAL-to-SYNC 遷移在安全邊界暫停。若 transaction-history 匯入正在進行，該原子 SQL transaction 不會被中斷；停止要求會在它返回後檢查。回覆只代表已接受要求，不代表工作已停止。任一遷移仍執行或等待收尾時，`/syncmoney migrate clear` 會拒絕刪除 checkpoint。
- Admin give/take 不再同步載入 cold balance 以執行 mutation 或產生失敗回覆；take 失敗且餘額不在記憶體時會顯示 N/A。
- 非 force 的 LOCAL-to-SYNC 遷移現在使用 insert-only shared-row 檢查處理 MySQL／MariaDB。既有 shared row 會保留且不會複製至 Redis；只有明確使用 force 才會取代。
- LOCAL-to-SYNC migration 會先在 normalization 前拒絕 SQLite 原始負餘額，包括原本會四捨五入成 0.00 的負 sub-cent 值，並在準備 insert-only 或 force SQL 前拒絕正規化後超出 shared `DECIMAL(20,2)` 上限的餘額。MySQL `INSERT IGNORE` 不會再靜默將高餘額降至欄位上限；遭拒列在 LOCAL 的原值保持不變，也不會複製到 Redis。
- LOCAL-to-SYNC migration 現在要求來源 UUID 使用標準 36 字元格式；Java parser 接受的縮寫 UUID 會在任何 shared write 前使來源 snapshot 失敗。
- CMI Redis mirror 現在以 atomic version compare-and-write 更新；過期、衝突或不完整狀態會被拒絕。遭拒的 outbound update 不會發布，遭拒的 inbound update 不會排程 CMI mutation。

- Redis Lua script SHA 缺失時，mutation 會在任何 balance command 傳送前標記為 `NOT_SENT`；recovery 會保留 retry 能力，不會把 account 誤判為 ambiguous 並 quarantine。
- Node proxy 現在接受已設定 node origin 上安全的 `/api` path-only target，並拒絕 absolute URL、authority、fragment 與多層編碼 traversal path。
- Scoped node-view key 不能再透過 remote node 設定的 API key 呼叫會改變狀態的 proxy method；寫入需要 node-manage 或 central permission。
- Proxy 呼叫現在會在使用 remote node key 前檢查 caller 對 target API path 的 permission；未映射 path 只允許 central scope。Structured request body 會以 JSON 傳送；超過 1 MiB 的 request body 會在解析或使用 node key 前回 HTTP 413；上游 response 上限為 8 MiB，超限時回 HTTP 502。
- Public `CacheManager` 建構預設停用 paired transfer；CMI mode 下 VaultUnlocked transfer 會在讀取 balance 前 fail closed，包含零額轉帳。
- queued `/pay` 轉帳會在 queue commit 前先持久化並 force 成對 admission checkpoint。ambiguous transfer 在 restart 後仍維持 quarantine，管理員可透過 `/syncmoney recovery list|inspect|reconcile <id>` 查閱；reconciliation 必須使用 stable ID 相符的 exact Redis receipt。Direct Vault/plugin recovery 依 ADR-07 維持 deferred。
- `GET /api/nodes` 現在讀取 periodic node health check 更新的同一份 status cache。
- Node health check 失敗時不再記錄設定 URL 或例外訊息；node-status SSE URL 會在 broadcast 前移除 URI user-info。
- Node list、status、管理 response 與 node-status SSE 會移除 URI user-info、query 與 fragment；建立或更新節點時會拒絕含有這些元件的 URL。

### 安全性 (Security)

- Web Admin CORS 現在能解析 list 與逗號分隔 scalar，並僅對 exact allowed origin 或明確設定的 wildcard 回傳 header。Node URL 驗證會拒絕 local/reserved address class，包含 IPv6 ULA。DNS rebinding 防護及 WebDownloader 傳輸／解壓資源上限仍為 `DEFERRED / NOT VERIFIED`。
- Node proxy method 與 target-path permission 現在都會在使用 remote node key 前檢查；超限 body 會在解析或使用憑證前被拒絕。

### 候選驗證紀錄（未發布）

### 歷史 R53 R5 候選驗證（未發布；不代表目前 readiness）

R53 R5 Java 25.0.4／Gradle 9.8.0 source/package gate：68/68 個 actionable tasks；167 個 unit suites／932 個 tests，以及 10 個 Sync integration suites／42 個 tests（合計 177 suites／974 tests），failures、errors、skips 皆為 0。Architecture 與 classfile-major-69 packaging、API-consumer 與 acceptance packaging、Central publisher isolation、NMCP aggregation 均通過。六個 targeted regression classes 共 39 tests 通過。Core JAR 為 31,614,140 bytes；SHA-256 `D2DD4DC274946DDA0B4D3E475241E67C9726674E17A7F52E13E282E8720D7FC3`。Build log 為 10,406 bytes；SHA-256 `1DC9AC0B42BDCB3055CE7CF493B7BAE4F82005BB4A6C614CA6B96A69F3706B5C`。其餘 artifact pins 見 Proposal 06。Frontend checks 仍適用，因其 source inputs 未變。Storage-service integration 未執行。Exact-candidate runtime 與完整 T22 Beta 仍為 NOT VERIFIED；整體 readiness 仍為 BLOCKED / NOT VERIFIED。Stable metadata 維持 1.3.2；未執行外部發布動作。

### 目前 2026-10-26 R63 source/package 狀態（未發布）

使用者授權 durable recovery 架構修訂，並將 ADR-16 限定為 live queued /pay recovery。R9 Java 25.0.4／Gradle 9.8.0 source/package gate 通過 59 個 actionable tasks（59 executed）：170 個 unit suites／945 個 tests，加上 9 個 Sync integration suites／44 個 tests（合計 179 suites／989 tests），failures、errors、skips 皆為 0。Architecture、classfile-major-69 packaging、API-consumer／acceptance packaging、Central publisher isolation，以及 frontend typecheck、4 個 unit files／22 個 tests 與 production build 均通過。Current core JAR：31,646,522 bytes / SHA-256 BB8C014ADB37CD407AEEED93BDDE16EBE13BA64272D0511F26155DB7EF3B1916；PAPI JAR：13,872 bytes / SHA-256 A4D893AC4892E9E715A1710A4760D7FB168EE84A7987BC43D1BAEA86EC6C319C；acceptance JAR：54,905 bytes / SHA-256 29C41C996026B6275AD9276587D05E800759C710E4B485E5891CF1F85C281824。Production queued /pay integration test 使用隔離 Redis。Direct Vault/plugin recovery 依 ADR-07 維持 DEFERRED / NOT VERIFIED。Exact-candidate Paper runtime／T22 Beta、storage-service integration、minimum-Paper SQL、licensed/live CMI（授權 UNKNOWN）、廣泛 V03、完整 public API 相容性與 T21 完成仍為 NOT VERIFIED 或 BLOCKED；Stable 與外部發布仍為 NOT VERIFIED / NOT PERFORMED。Stable metadata 維持 1.3.2；沒有執行外部發布。

### 歷史 2026-10-26 R62 test 與 gate 紀錄（已由 R63 取代）

R62 `:syncmoney-sync:integrationTest` 執行 44 個 tests：41 通過、3 失敗、0 errors、0 skipped。兩個 raw Lua receipt-fixture 類別的 23 個 tests 全數通過。三個失敗都在沒有 production caller 的 `PayLuaScriptManagerNoscriptIT`；該 task 沒有執行或證明 live `/pay` 失敗。`verifyArchitecture` 與 `-PreleaseVersion=1.4.0` 的 `verifyPackaging` 通過；storage-service integration 未執行。四個磁碟 artifact hashes 已重算，但沒有 current source manifest 綁定 dirty candidate。這些只屬 R62 歷史，不能驗收 R63 candidate。
### 歷史 2026-10-26 R59 readiness 決定（由 R62 取代）

使用者決定保留 ADR-07、延期 direct-transfer recovery 修復／整合，並讓 target main 維持未 merge、stable metadata 為 1.3.2。R58 的四個 Bukkit 測試類別 focused run 共 33 個 tests 通過；完整 syncmoney-bukkit:test task 為 134 個 JUnit suites／771 個 tests，失敗、錯誤與跳過皆為 0。兩位獨立唯讀 R59 reviewer 批准了四類測試完整現有 diff。這些結果不代表完整 source/package gate、目前 exact-candidate artifact pins 或 runtime／Beta 驗收。整體 readiness 仍為 BLOCKED / NOT VERIFIED。未啟動 runtime、未 merge、未執行外部發布。
### 歷史 2026-10-26 R57 readiness 決定（由 R59 取代）

使用者重申保留 ADR-07、延期 direct-transfer recovery 修復／整合，並讓本機 `main` 維持未 merge、stable metadata 為 `1.3.2`。目前 R56 `:syncmoney-bukkit:test` 完成 771 個 tests，其中 6 個失敗。唯讀 audit 認為測試可能仍使用舊的 operation-ID／receipt mock；因尚未修正 fixture 或重跑，這只是初步判斷，六項失敗仍未解決。目前沒有完整的 source/package gate 或 current artifact pin。Exact-candidate runtime 與 T22 Beta 仍為 `NOT VERIFIED`；整體 readiness 維持 `BLOCKED / NOT VERIFIED`。未啟動 runtime、未 merge、未執行外部發布。

### 歷史 2026-10-26 R54 readiness snapshot（已由 R56／R57／R59 取代）

使用者選擇保留 ADR-07、不採納 ADR-15、延期 direct Vault/plugin-transfer recovery 修復／整合，並讓 local `main` 保持未 merge、版本仍為 1.3.2。R5 review 指出 direct-transfer `SENT_UNKNOWN` quarantine 可能在 restart 後遺失，且 operator-resolution path 尚未驗證。較早的 05:33 UTC run 擷取到 9 個測試失敗標記，但沒有終端 build summary。05:52 UTC Java 25／Gradle 9.8.0 attempt 記錄 3 個測試失敗。最新 run 完成 201 個 tests（10 個失敗、1 個 skipped）後，Gradle Test Executor 4 在 `:syncmoney-bukkit:test` 因 Java heap space 失敗。log 顯示 `:apiConsumerJar`、`:acceptanceJar` 和 `:shadowJar` 在測試失敗前已執行；整體 gate 仍失敗，Sync integration、architecture 和後續驗證 tasks 未建立。最新 log SHA-256：`44C49CDB9176D739E30479A00ED10E933E020FCB3D0EF026FF6B426BC72519E1`。磁碟上的候選 JAR 為 31,661,116 bytes／SHA-256 `5699B42B709751430F349F338130FE0A88BF76B041D7D566C940086C8400DD53`；未產生 current manifest 或 exact-candidate artifact pin，因此這不是已驗證的發布 artifact pin。沒有 exact-candidate runtime 或 Beta acceptance。R53 R5 pins 僅屬歷史證據。整體 readiness 維持 BLOCKED / NOT VERIFIED；stable metadata 維持 1.3.2，未執行外部發布。

### 歷史 R53 R4 候選驗證（已由 R5 取代；未發布）

R53 R4 Java 25.0.4／Gradle 9.8.0 source/package gate：68/68 個 actionable tasks；167 個 unit suites／931 個 tests，以及 10 個 Sync integration suites／42 個 tests（合計 177 suites／973 tests），failures／errors／skips 皆為 0。Architecture、classfile-major-69 packaging、API-consumer 與 acceptance packaging、Central publisher isolation、NMCP aggregation 均通過。Core JAR 為 31,613,386 bytes；SHA-256 AAE5B297D9553109C9619ECF02CF373EE0444B5C056447817A9DD7A66138C660。五項 targeted regressions 共 36 tests 通過。Frontend typecheck、4 個 unit files／22 個 tests、production build、39 個 embedded files synchronization 均通過，且其 source inputs 與前次已驗證建置相同。Storage-service integration 未執行。Exact-candidate runtime 與完整 T22 Beta 仍為 NOT VERIFIED；整體 readiness 仍為 BLOCKED / NOT VERIFIED。Stable metadata 維持 1.3.2；未執行外部發布動作。五項 artifact pins 與 evidence log digests 請見 Proposal 06。

### 歷史 R52R 候選驗證 (historical; superseded by R53)

R52R Java 25.0.4／Gradle 9.8.0 source/package gate：68 個 actionable tasks、164 個 unit suites／920 個測試及 10 個 Sync integration suites／42 個測試（合計 174 suites／962 tests），失敗、錯誤與跳過皆為 0。Architecture、classfile-major-69 packaging、API-consumer 與 acceptance packaging、Central publisher isolation，以及 NMCP aggregation 通過。Core JAR 為 31,608,675 bytes；SHA-256 4629284db89ea05336c49fb224208d8ccf0f4a8f9bc32c7cd23d201478c2a10a。Frontend typecheck 與 production build 通過；4 個 unit files／22 個測試通過；39 個 embedded files 同步通過。Storage-service integration 未執行。Exact-candidate runtime 與完整 T22 Beta 仍為 NOT VERIFIED；整體 readiness 仍為 BLOCKED / NOT VERIFIED。Stable metadata 維持 1.3.2。五項 artifact pins 與 exact source/package log digest 記錄於 Proposal 06。

歷史 R52F R14 候選 source/package 證據（已由 R52R 取代）（2026-10-26；未發布；僅 source/package）：Java 25 toolchain／Gradle 9.8.0，68/68 個 tasks；164 個 unit suites／913 個測試及 9 個 Sync integration suites／39 個測試（合計 952），失敗、錯誤與跳過皆為 0。Architecture verification、classfile-major-69 packaging、API-consumer 與 acceptance packaging、Central publisher isolation，以及 NMCP aggregation 均通過。Core JAR 為 31,605,493 bytes，SHA-256 `6c76a79a6b0119609ada212fd732d846bc224e4a5f7e7b5d23aed534f0cefd09`；五項 artifact pins 與 gate digest 記錄於 R14 evidence 及 Proposal 06。R11 加入的 canonical UUID source-text guard 仍在目前候選程式碼中。R14 exact-candidate runtime attempt 在 90 秒內沒有 PASS/FAIL marker；僅確認兩筆 synthetic fixture rows，且未執行 restart-read。Exact-candidate runtime acceptance 與完整 T22 Beta 仍為 NOT VERIFIED；歷史 R9 M04 使用不同 JAR。Storage-service integration 仍為 NOT VERIFIED；T21 為 PARTIAL / IN_PROGRESS；廣泛 V03 為 SKIPPED / NOT VERIFIED；minimum-Paper SQL initial schema 與 licensed/live CMI 為 BLOCKED / NOT VERIFIED（CMI 授權狀態 UNKNOWN）；完整 public API 相容性仍為 NOT VERIFIED。Stable metadata 維持 1.3.2；未執行外部發布動作。

歷史 R52F r6 1.4.0 候選 source/package 證據（2026-10-25；未發布；僅 source/package）：Java 25.0.4 toolchain／Gradle 9.8.0（launcher JVM 21.0.6），68/68 個 tasks；163 個 unit suites／909 個測試及 9 個 Sync integration suites／39 個測試（合計 948），失敗、錯誤與跳過皆為 0。Migration reader 會在 rounding 前拒絕 SQLite 原始負餘額；正值 sub-cent 正規化規則維持不變。五項 artifact pins 與最終 gate log digest 記錄於 Proposal 06。Storage-service integration、exact-candidate runtime 與完整 T22 Beta 仍為 NOT VERIFIED。Stable metadata 維持 1.3.2，未執行外部發布動作。
歷史 R52F r5 source/package snapshot（已由負 sub-cent 修正與 r6 取代；2026-10-25；未發布）：68/68 tasks、163 個 unit suites／908 個測試、9 個 Sync integration suites／39 個測試，失敗／錯誤／跳過皆為 0。r5 未涵蓋 -0.004 migration case，其 artifact pins 已非目前版本。
歷史 R52F r4 source/package snapshot（已由 r5 取代；2026-10-25；未發布）：r4 gate 同樣通過 68/68 tasks，包含 163 個 unit suites／908 個測試及 9 個 Sync integration suites／39 個測試（合計 947），皆無失敗。Core JAR 與 r5 相同，為 31,601,861 bytes／SHA-256 `46A593BBEC73FE7BA8759ED822EF1734ED6704CAD1688A1BF61150906FDE2DD4`；r4 gate log SHA-256 為 `7A2A506DC4CD0FFF24F47FD81B320F1429E2B379BFCBF4F96CBE469385E25466`。
歷史 R52F r3 snapshot（已由 r4 取代；2026-10-25；未發布）：r3 source/package gate 的正確統計為 163 個 unit suites／906 個測試及 9 個新鮮 Sync integration suites／39 個測試（合計 945），失敗、錯誤與跳過皆為 0。較早的 r3 summary JSON 誤將舊的 `syncmoney-storage` integration XML 計入，該中間 inventory 保留為歷史但不採用。Core JAR 為 31,601,746 bytes，SHA-256 `7417CAF6071DDB3033EA8603FB314EB9E0F789A0DCF6784ABCB6B6A554759CC8`。其 bounded M04 runtime 只屬歷史證據，不能驗證 r4。

歷史 R52C 本機候選紀錄（已由 R52D 取代）（2026-10-25；未發布；僅為 source/package 證據）：JDK 25.0.4／Gradle 9.8.0 完整 gate 完成 58 個 actionable tasks（11 個 executed、47 個 up-to-date），包含 162 個 unit suites／899 個測試及 8 個 Sync integration suites／38 個測試；失敗、錯誤與跳過皆為 0。Architecture verification 與 classfile-major-69 packaging 通過。另有 15 個 tasks 的 packaging 檢查通過，涵蓋 API-consumer packaging、Central publisher isolation 與 NMCP aggregation。較早的 R52B full gate 有 7 個測試失敗並予以保留；修正 regression 並調整測試 fixture／assertion 後，R52C gate 通過。完整測試清單包含 RedisMissingAccountMutationIT 3/3 與 LocalToSyncTransactionHistoryAtomicityTest 1/1。Core JAR：31,601,520 bytes，SHA-256 FD06656233434508FE967B7373C74F1298EFAE8445AFCACA4A46A1E7CB154D60。Exact-candidate runtime 與完整 T22 Beta 仍為 NOT VERIFIED；storage-service integration 為 DEFERRED / NOT VERIFIED。Stable metadata 維持 1.3.2。未啟動 runtime，亦未執行外部 release action。前一版 R52 pin 僅為歷史資料且已被取代；五項目前 artifact pins 請見 Proposal 06。
R52D 本機候選紀錄（2026-10-25；未發布；僅為 source/package 證據）：Java 25.0.4 toolchain／Gradle 9.8.0 完整 source/package gate 完成 68 個 actionable tasks（全部執行）；163 個 unit suites／901 個測試及 9 個 Sync integration suites／39 個測試通過，失敗、錯誤與跳過皆為 0。MySQL 遷移重複列 regression 與使用合成 strict SQL fixture（900.00／version 41）的真實隔離 Redis Consumer-to-CacheManager authority-recovery regression 通過。Architecture verification、classfile-major-69 packaging、API-consumer packaging、Central publisher isolation 與 NMCP aggregation 通過。Core JAR 為 31,601,526 bytes，SHA-256 FD06DE8DF79B5D16714A10603DB04525F25171761618C74733B8CCE17E8DAF9E。可選 MySQL／MariaDB storage-service integration 未執行，仍為 NOT VERIFIED；exact-candidate runtime 與完整 T22 Beta 仍為 NOT VERIFIED；T21 為 PARTIAL / IN_PROGRESS；廣泛 V03 為 SKIPPED / NOT VERIFIED；minimum-Paper SQL 與 licensed/live CMI 為 BLOCKED / NOT VERIFIED；完整 public API compatibility 為 NOT VERIFIED；Stable 與外部發布為 NOT VERIFIED / NOT PERFORMED。Stable metadata 維持 1.3.2，未啟動 runtime 或執行外部發布。

歷史 R51 本機候選紀錄（2026-10-25；已由 R52 取代；未發布；此 pins 僅綁定 R51 source）：JDK 25.0.4／Gradle 9.8.0 完整 source/package gate 通過 68/68 tasks（全部執行），包含 159 個 unit suites／887 個測試及 7 個 Sync integration suites／35 個測試；失敗、錯誤與跳過皆為 0。Architecture、classfile major 69 packaging、Central publisher isolation、acceptance/API consumer packaging 與本機 NMCP aggregation 均通過。Node health check 失敗時不再記錄設定 URL 或例外訊息；node-status SSE URL 會在 broadcast 前移除 URI user-info。Core JAR：31,597,492 bytes，SHA-256 BEDDD3F2F62961E98B3F21A600544FD2F3FF140725518062A15DD9300FCDAC46。Exact-candidate runtime 與完整 T22 Beta 仍為 NOT VERIFIED；storage-service integration 為 DEFERRED / NOT VERIFIED。Stable metadata 仍為 1.3.2。未啟動 runtime，亦未執行外部 release action。
歷史 R52（R52C 之前）本機候選紀錄（2026-10-25；已由 R52C 取代；未發布；僅綁定較早的 source/package snapshot）：JDK 25.0.4／Gradle 9.8.0 gate 通過 68 個 actionable tasks（14 個 executed、54 個 up-to-date），包含 159 個 unit suites／892 個測試及 7 個 Sync integration suites／35 個測試；失敗、錯誤與跳過皆為 0。較早 Core JAR 為 31,597,376 bytes，SHA-256 8295A8B9D0CB0E17D5CCBAED07B4163949AF4AD48947EE1D00A0C75EB137F8E4。這些 pins 不適用於 R52C 或 R52D。Exact-candidate runtime／T22 Beta 仍為 NOT VERIFIED，storage-service integration 為 DEFERRED / NOT VERIFIED。

歷史 R50 本機候選紀錄（已由 R51 取代；2026-10-25；未發布）：JDK 25.0.4／Gradle 9.8.0 完整 source/package gate 通過 68/68 tasks（全部執行），包含 157 個 unit suites／884 個測試及 7 個 Sync integration suites／35 個測試；失敗、錯誤與跳過皆為 0。Architecture、packaging、Central publisher isolation、acceptance/API consumer packaging 與本機 NMCP aggregation 均通過。Core JAR：31,596,440 bytes，SHA-256 F493F561429D7AA84B6D32F28D3D722108366F4F83D8A05EC939A1E701818ACC。Node 管理 response 會從舊設定 URL 移除 URI user-info；無效 URL 會以 `[invalid URL]` 標示，設定驗證也會拒絕新加入的 user-info URL。Exact-candidate runtime 與完整 T22 Beta 仍為 NOT VERIFIED；storage-service integration 為 DEFERRED / NOT VERIFIED。Stable metadata 仍為 1.3.2。未執行外部 staging、signing、upload 或 publication。
歷史 R49 本機候選紀錄（由 R50 取代；2026-10-25；未發布）：JDK 25.0.4／Gradle 9.8.0 source/package gate 通過 68 個 actionable tasks（10 個 executed、58 個 up-to-date），包含 157 個 unit suites／864 個測試及 7 個 Sync integration suites／35 個測試；失敗、錯誤與跳過皆為 0。Architecture、packaging、Central publisher isolation、acceptance/API consumer packaging 與 NMCP aggregation 均通過。Core JAR：31,593,700 bytes，SHA-256 E1564BADC993B9792D963806BC5D878CE8FDAAADBC7227765F3A7970CF4C3CA7。Exact-candidate runtime 與完整 T22 Beta 仍為 NOT VERIFIED；storage-service integration 為 DEFERRED / NOT VERIFIED。Stable metadata 仍為 1.3.2，未執行發布。

歷史 R46 source/package snapshot（2026-10-25；已由下列後續修正取代）：Java 25.0.4／Gradle 9.8.0 gate 通過 68/68 個 tasks（14 個 executed、54 個 up-to-date），包含 157 個 unit suites／854 個測試及 7 個 Sync integration suites／35 個測試。其 core JAR 為 31,591,955 bytes，SHA-256 FE754399BCB0F625AEA005D5CF1511AD7CB8B7618AF6F4816D2839A4A8547094。後續 candidate source 變更使舊 pins 與 review 失效。R39 runtime 證據不適用於後續候選；exact-candidate runtime 與完整 T22 Beta 仍為 NOT VERIFIED。Storage-service integration 為 DEFERRED / NOT VERIFIED；minimum-Paper SQL 與 licensed/live CMI 為 BLOCKED / NOT VERIFIED。Stable metadata 保持 1.3.2。
歷史 R45 source/package snapshot（2026-10-25）：Java 25／Gradle 9.8.0 gate 通過 68/68 個 tasks，包含 157 個 unit suites／851 個測試及 7 個 Sync integration suites／35 個測試，失敗、錯誤與跳過均為 0。R45 core JAR SHA-256 為 C12F52AAA5EE3F03E8582CB706703495B11D34564485FF4EED95BF880E83129A。R45 未執行 exact-candidate runtime acceptance；R39 有界 runtime 結果不適用於 R46。Storage-service integration 仍為 DEFERRED / NOT VERIFIED，完整 T22 Beta 仍為 NOT VERIFIED。

## [1.4.0-alpha.3] - 未發佈預覽

此為 Java 25+ 與 Paper 1.21.6+ 的實驗性、未發佈工程候選版；並非穩定版本。ADR-03 僅凍結 T11 唯讀查詢 SDK；public Maven 解析與第三方採用仍未驗證。1.4.0 已驗證的支援範圍依據現有證據界定：完整 CMI mode、minimum-Paper MySQL/MariaDB initial-schema 案例及若干 mode/resource/failure 組合均未驗證。請勿將此候選版視為正式環境相容性承諾。

R39 source/package gate 通過 68/68 個 tasks、155 個 unit suites／834 個測試，以及 7 個 Sync integration suites／35 個測試，失敗、錯誤與跳過均為 0。R39 core JAR 為 31,587,551 bytes，SHA-256 63E25A450FA38F0AE57B535825617515979DBD6257BA7059629C48B21D80DE19。一項 exact-candidate Paper 1.21.6#48／Java 25.0.4+7-LTS-189／LOCAL 合成帳戶 runtime subcase 記錄到 API classloader 通過、acceptance total 1520.00、重啟後 A=1000.00／B=520.00／VaultUnlocked B=520.00，以及 graceful final stop。此結果僅為 PASS_BOUNDED_SUBCASE；完整 T22 Beta 仍為 NOT VERIFIED。先前 r24 JAR 390499303A2FD2F065BA7E0535768C61A297514148B2F3C600934DB57391501E 與 R29 JAR 4BE022B1760209D610A390144D5B3328A094E290C2AB53A9E557BDF27FAA92F6 的結果屬於歷史版本，不適用於 R39。Storage-service integration 為 DEFERRED / NOT VERIFIED；更廣的平台／mode 與 licensed/live CMI 覆蓋仍為 NOT VERIFIED。

matched synthetic facade/receipt-adapter benchmark 未達全部原始 B00 thresholds（throughput 27/48、allocation 12/48、SampleTime p99 18/48 案例達標）。依 ADR-11，僅在該有界 synthetic profile 範圍內接受已量測的 regression；thresholds 與 correctness hard gates 均未改變。此例外不是 production、SQL、Redis 或 real-server capacity 的性能承諾。

### 安全性 (Security)

- Web Admin 會因兩種 shipped API-key placeholder（含空白值）自動停用，但不影響 economy plugin 啟動。
- Web archive extraction 會拒絕以句點或空格結尾的 TAR path component，避免 Windows 將路徑正規化成不同位置。
- 延後的 `/ws` endpoint 固定回傳 `503 WEBSOCKET_UNAVAILABLE`，不再產生假的 WebSocket upgrade response。
- SSE session token 改為原子消費；缺少或無效認證時回傳 `401`，不建立未認證 stream。

### 已變更 (Changed)

- Undertow route 會先從 I/O thread dispatch 到 worker thread，再執行 handler。

### 已修復 (Fixed)

- CMI mode 現在會在讀取或修改 Syncmoney balance 前拒絕 `/syncmoney admin` 與 `/syncmoney sync-balance`，因為這些命令路徑不會更新 CMI authority。
- CMI adapter 沒有原子式雙帳戶轉帳契約，因此 CMI mode 會停用 Syncmoney `/pay`，並在餘額 mutation 前拒絕。
- CMI mode 會在 Syncmoney mutation 前拒絕 Vault 明確的雙帳戶轉帳入口、VaultUnlocked transfer 委派及公開的低階 Redis 雙帳戶轉帳 API；這不代表 licensed/live CMI acceptance 已通過。
- `LOCAL_REDIS` 與 `SYNC` shared write 現在會在接受前拒絕超過 `999999999999999999.99` 的 projected balance，並在 account lock 內再次檢查。既有 `LOCAL` policy 不變。
- 升級注意事項：1.4.0 不會 migration 以舊版較寬 policy 寫入的 Redis balance。升級前請檢查既有 shared-mode data；超過新上限的既有 account 會在記憶體 mutation 前被 deposit、withdraw、transfer 拒絕。Admin set 是 operator 明確修改餘額，不是自動 migration。

## [1.3.2] - 2026-09-20

### 安全性 (Security)

- **前端依賴修復**：將 Web Admin 依賴圖更新至已修補的 Axios、Vite 6、Vitest 4.1.11、Happy DOM、PostCSS 與 Sharp 版本。對仍有漏洞的間接依賴採用精準 pnpm override，避免為安全修復一併導入無關的 Java／執行期大版本升級。
- **稽核基準**：解析完成的 Web Admin 依賴圖目前以 `pnpm audit` 檢查為零已知漏洞。

### 已變更 (Changed)

- **版本中繼資料**：根專案、PlaceholderAPI、Web Admin、文件、設定中繼資料與 acceptance 版本檢查統一為 `1.3.2`。
- **內嵌 Web Bundle**：重新建置 Web Admin 內嵌資產，並同步 `WebAdminServer.extractIndividualFiles` 與產生的 bundle。
- **Release 自動化**：新增 GitHub Actions CI artifact、alpha/beta preview、受保護的 stable release、provenance attestation，以及同步 `Syncmoney-web` release 的 workflow。
- **Web Release 契約**：Web repository 發佈帶有 `v` 前綴的 deterministic source archive；core downloader 同時接受帶前綴與不帶前綴的 Web release tag。

### 驗證 (Validation)

- PlugDev acceptance 通過 Paper 1.20.4、Paper 26.2、Folia `26.2.build.7-beta`、Canvas 26.2，以及雙後端 Paper 26.2 network。Folia 覆蓋範圍特指實際測試的 beta build。

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
- **權威性與發布機制**：重寫 CMI 同步，將 CMI API 作為本地權威（Authority）；發布帶有 legacy timestamp-derived version 的絕對餘額，而非在執行期查詢 CMI 資料庫。Clock rollback、restart 或多伺服器時不保證全域單調排序。
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
- **即時通訊**：新增初始 WebSocket scaffold 與事件 payload hooks，並搭配伺服器傳送事件（SSE）。完整 WebSocket transport 並未完成；`/ws` 目前回傳 `503 WEBSOCKET_UNAVAILABLE`，可用的即時通道為 SSE。

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

歷史 R52F r9 的 1.4.0 候選 source/package 證據（已由 r10 取代；2026-10-25；未發布）：Java 25.0.4 toolchain／Gradle 9.8.0，68/68 個 tasks、164 個 unit suites／911 個測試及 9 個 Sync integration suites／39 個測試（共 950 個），失敗、錯誤與跳過皆為 0。Architecture、classfile-major-69 packaging、API-consumer packaging、Central publisher isolation 與 NMCP aggregation 通過。r9 core JAR 為 31,604,354 bytes，SHA-256 93c20c49fc40f2fa9c7cc9c716c1882c88451efe64c12b31617573d3292d84fd；該版本的 artifact pins 與 gate/test evidence hash 記錄於 Proposal 06。相同 r9 JAR 另通過一項有界 T21 M04 runtime subcase：Paper 1.21.6#48、Java 25.0.4、LOCAL、guard-on、normal-drain、synthetic SQLite。Result JSON SHA-256 8bab087aca408487f4e60ec75b6e7b8219a105e368f7c1ccf0774dfbee19db30；report-specific offline auditor 通過 398 項 assertions。此結果僅為 PASS_BOUNDED_SUBCASE，不代表完整 LOCAL 支援或完整 T22 Beta，也不能驗證 r10。Storage-service integration 仍為 NOT VERIFIED；整體 release readiness 為 BLOCKED / NOT VERIFIED；stable metadata 維持 1.3.2；未執行外部發布動作。

歷史 R52F R10 1.4.0 候選 source/package 證據（2026-10-25；未發布）：Java 25.0.4 toolchain／Gradle 9.8.0，68/68 個 tasks、164 個 unit suites／912 個測試及 9 個 Sync integration suites／39 個測試（共 951 個），失敗、錯誤與跳過皆為 0。Architecture、classfile-major-69 packaging、API-consumer packaging、Central publisher isolation 與 NMCP aggregation 通過。R10 core JAR 為 31,605,447 bytes，SHA-256 5c4e70294cdc39fa1f21adc312adb99e699e6fd814550bdafc48b6735fefe771；其五項 pins 與 gate/test evidence hash 記錄於 Proposal 06。LOCAL migration 遇到缺失或格式錯誤的玩家 UUID 時會在 shared 寫入前拒絕 snapshot 並記錄來源列失敗；其 regression 已納入 R10 完整 gate。R10 沒有 runtime 驗收，exact-candidate runtime 與完整 T22 Beta 仍為 NOT VERIFIED。Storage-service integration 仍為 NOT VERIFIED；T21 為 PARTIAL / IN_PROGRESS；廣泛 V03 為 SKIPPED / NOT VERIFIED；minimum-Paper SQL initial-schema 與 licensed/live CMI 為 BLOCKED / NOT VERIFIED（CMI 授權狀態 UNKNOWN）；完整 public API 相容性為 NOT VERIFIED。整體 release readiness 為 BLOCKED / NOT VERIFIED；stable metadata 維持 1.3.2；未執行外部發布動作。
歷史 R52F r8 1.4.0 候選 source/package 證據（已由 r9 取代）（2026-10-25；未發布）：Java 25.0.4 toolchain／Gradle 9.8.0（launcher JVM 21.0.6），68/68 個 tasks；163 個 unit suites／909 個測試及 9 個 Sync integration suites／39 個測試（合計 948），失敗、錯誤與跳過皆為 0。Negative sub-cent migration regression 會在 rounding 前拒絕原始 -0.004；正值 1.234 仍會正規化為 1.23。Frontend typecheck、production build 與 22 個 unit tests 通過；embedded build 使用 Corepack pnpm 10.22.0 完成。五項 artifact pins 與 gate log digest 記錄於 Proposal 06。Storage-service integration、exact-candidate runtime 與完整 T22 Beta 仍為 NOT VERIFIED；Stable metadata 維持 1.3.2，未執行外部發布動作。
