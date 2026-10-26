# Syncmoney Web API 參考

> 專案版本：`1.3.2`
> Backend：Undertow
> shipped config 預設綁定：`localhost:8080`
> 本文件描述目前 source tree 中已驗證的 route 與行為。

英文版：[`API_REFERENCE.md`](API_REFERENCE.md)

## 1. 基本行為

Web Admin 預設停用。啟用後，server 提供 SPA、health endpoint、REST API 與 SSE endpoint。`/ws` 保留為 unavailable endpoint，所有 request 都固定回傳 `503 WEBSOCKET_UNAVAILABLE`。

一般 API request 使用：

```http
Authorization: Bearer <api-key>
```

`/health` 刻意不需 authentication；一般 `/api/*` route 會經過 API-key authentication。`ApiKeyAuthFilter` 使用 constant-time key comparison，且可因 rate limit 回傳 `429 RATE_LIMITED`。

只有 `web-admin.security.trust-proxy=true` 時，才會從 `X-Forwarded-For` 讀取 forwarded client address；未啟用時，rate limiting 使用的 client identity 不信任該 header。

Shipped config 的 API-key placeholder 是 `change-me-in-production`，rate limit 是 120 requests/minute；若 rate-limit setting 缺少，`WebAdminConfig` code fallback 為 60/minute。API key 空白或 trim 後等於 `change-me` / `change-me-in-production`（不分大小寫）時，`WebServiceManager` 會停用 Web Admin，但 economy plugin 仍正常啟動。要啟用 Web Admin，請設定非 placeholder API key。

## 2. Response envelope

多數成功 JSON：

```json
{
  "success": true,
  "data": {},
  "meta": {
    "timestamp": 0,
    "version": "1.3.2"
  }
}
```

一般錯誤：

```json
{
  "success": false,
  "error": {
    "code": "ERROR_CODE",
    "message": "Human-readable message"
  },
  "meta": {
    "timestamp": 0,
    "version": "1.3.2"
  }
}
```

Cursor pagination 是例外，不含一般 `meta` envelope。

`HttpHandlerRegistry` exception mapping：

| Exception | HTTP |
|---|---:|
| `IllegalArgumentException`、`IllegalStateException` | 400 |
| `SecurityException` | 403 |
| `NoSuchElementException` | 404 |
| `UnsupportedOperationException` | 405 |
| 其他未處理例外 | 500 |

未知 route 回傳 `404 NOT_FOUND` / endpoint-not-found message。

## 3. Health

### `GET /health`

不需 authentication 的 health probe，供服務監控使用。不得從此 endpoint 暴露 secret 或 privileged config。

## 4. Authentication 與 live-session token

### `POST /api/auth/ws-token`

需要一般 API-key authentication。回傳一次性 UUID token；目前 lifetime 為 60,000 ms，成功 validation 後即 consume/remove。

Representative data：

```json
{
  "token": "<uuid>",
  "expires": 0,
  "validityMs": 60000
}
```

雖然 route/class 名稱仍含 ws，現在 production live path 使用此 token 的主要實作是 `/api/sse`。

## 5. Server-Sent Events

### `GET /api/sse`

這是目前前端實際使用的 live transport。

可接受：

- Bearer API key；
- Bearer one-time token；
- `?token=<one-time-token>`。

API key comparison 為 constant-time；one-time token validation 成功後即 consume。Manager 約每 15 秒送 keepalive。`PostTransactionEvent` 會提供 transaction broadcast，其他 server module 也可發布 live event。

Client 應依 frontend token flow 重連，不應假設 token 可重複使用。

缺少或無效認證的 request 會回傳 `401`，不會建立 SSE connection。

## 6. WebSocket 狀態

### `GET /ws` / WebSocket upgrade path

目前 WebSocket implementation **不完整，不是受支援的 production transport**。

所有 `/ws` request（包含帶有非空 token 的 WebSocket upgrade）都回 `503 WEBSOCKET_UNAVAILABLE`；server 不會回傳 upgrade response。完整 WebSocket transport 延後處理。

前端即時通道應使用 `/api/sse`。不能因 `/ws` endpoint 存在就推定 authentication hardened 或 message delivery 完整。

## 7. System API

### `GET /api/system/status`

回傳 plugin/system status。

### `GET /api/system/redis`

回傳 Redis connectivity/status。

### `GET /api/system/breaker`

回傳 breaker/protection status。

### `GET /api/system/metrics`

回傳目前 system handler 暴露的 runtime metrics。

## 8. Economy API

### `GET /api/economy/stats`

目前 node 可提供的 economy summary。
### `GET /api/economy/player/{uuid}/balance`

指定 UUID balance；path argument 必須是 handler 可接受的有效 UUID。
### `GET /api/economy/top`

leaderboard data。

## 9. Cross-server Economy API

以下 route 用於 central/cross-server operation：

### `GET /api/economy/cross-server-stats`

Current aggregation semantics：

- `onlinePlayers` 會加總各 online node；
- `totalSupply`、`totalPlayers`、`todayTransactions` 是 shared/global database values，**不可再次加總**；
- 這些 shared values 目前取第一個提供資料的 online node 值。

### `GET /api/economy/cross-server-top`

Central-mode cross-server leaderboard data。

## 10. Audit API

Audit route 依賴 `audit.enabled`。停用時回 `503 FEATURE_DISABLED`，並依實作提示啟用需要設定/重啟。

### `GET /api/audit/player/{name}`

玩家 audit history。
### `GET /api/audit/search`

搜尋 audit record；支援 page/pageSize，也可依參數進入 cursor-oriented behavior，`pageSize` 會 clamp 到 1..100。
### `GET /api/audit/search-cursor`

專用 cursor search；cursor response 不使用一般 `meta` envelope。
### `GET /api/audit/stats`

audit statistics。

## 11. Configuration API

### `GET /api/config`

回傳 Web Admin config handler 暴露的設定資料。

### `PUT /api/config`

可接受 batch：

```json
{
  "changes": [
    {"section": "display", "key": "example", "value": "value"}
  ],
  "hotReload": true
}
```

或 single change：

```json
{
  "section": "display",
  "key": "example",
  "value": "value",
  "hotReload": true
}
```

`hotReload` 預設為 `true`。REST handler 可要求 reload，但 runtime safety 仍由 `ConfigReloadPolicy` 決定；目前 live-reload roots 是 `display`、`pay`、`permissions`、`admin-permissions`、`debug`。會建立 connection/resource 或改變 authority 的設定，在 implementation 未明確支援前視為 restart-required。

### `POST /api/config/validate`

Body：

```json
{"section": "display", "key": "example", "value": "value"}
```

依目前 config handler 驗證 value。

### `POST /api/config/reload`

要求 backend 執行 config reload。

## 12. Web UI Settings API

### `GET /api/settings`

目前 data 包含 `theme`、`language`、`timezone`、`dedupServerWindowSeconds`、`dedupClientCacheSize`（目前 1000）。

### `POST /api/settings/theme`

接受 `dark`、`light`。
### `POST /api/settings/language`

接受 `zh-TW`、`en-US`。
### `POST /api/settings/timezone`

接受 `UTC` 或目前 parser 支援的 `UTC+/-<hours>`；absolute offset 目前限制在 12 小時。

Settings persistence debounce 約 500 ms。

## 13. Node Management API

Node handler 對 duplicate method/path 較晚註冊，因此提供目前有效的 `/api/nodes` implementation。

### `GET /api/nodes`

列出 nodes。Node URL 欄位與 central-node `selfUrl` 會移除 URI user-info、query 與 fragment；無效 URL 以 `[invalid URL]` 表示。
### `POST /api/nodes`

依目前 request model 建立/註冊 node。URL 不得包含 URI user-info、query 或 fragment，並須通過設定的 address restriction。
### `PUT /api/nodes/{index}`

更新指定 index node。若有提供 URL，不得包含 URI user-info、query 或 fragment，並須通過設定的 address restriction。
### `DELETE /api/nodes/{index}`

刪除指定 index node。
### `POST /api/nodes/{index}/ping`

執行 node ping/health。
### `GET /api/nodes/status`

node status；URL 欄位會在 API response 與 SSE broadcast 前移除 URI user-info、query 與 fragment，無效 URL 以 `[invalid URL]` 表示。
### `POST /api/nodes/{index}/proxy`

透過 central node handler proxy 允許的 request。`path` 必須是 `/api` 下不含 authority 或 fragment 的相對 URI；可選 query string 會原樣轉送至同一個設定的 node origin。Absolute URL、URI authority、fragment、dot-segment traversal（包含 percent-encoded 形式）與過度編碼都會被拒絕。允許的 proxy method 為 `GET`、`HEAD`、`OPTIONS`、`POST`、`PUT`、`PATCH`、`DELETE`。受 scope 限制的 API key 必須具備 `syncmoney.web.nodes.view` 才能使用此 endpoint，且也必須具備 target path 對應的 permission；未映射的 target path 只允許 `syncmoney.web.central`。會改變狀態的 method（`POST`、`PUT`、`PATCH`、`DELETE`）還需要 `syncmoney.web.nodes.manage` 或 `syncmoney.web.central`。不支援的 method 或權限不足會在使用已設定的 node key 前被拒絕。外層 request body 上限為 1 MiB；超過時會在 JSON 解析或使用 node key 前回 HTTP 413。上游 response body 會先完整讀入最多 8 MiB 的有界 buffer，再送出 response；超過上限時回 HTTP 502，且不會轉送部分內容。`POST`、`PUT`、`PATCH` 可在 `body` 欄位提供任意 JSON value，並以 JSON 傳送；其他 method 不會帶 request body。只有 path 與權限檢查通過後，request 才會送往設定的 node origin 並附上該 node 的 Bearer API key。設定的 node URL 會拒絕 user-info、query、fragment 與靜態列出的 local/special-use IPv4 位址範圍，包括 shared、protocol-assignment、documentation、benchmarking、multicast 與 reserved 區段，並拒絕 IPv6 unspecified、loopback、link-local、site-local、multicast 與 ULA 位址。Node URL 驗證失敗時只記錄例外類型，不會記錄 URL、user-info、query 或 fragment。Node URL 與 credential 屬安全敏感內容。DNS rebinding 防護為 `DEFERRED / NOT VERIFIED`；請將 server egress 限制於預期 node，並把受信任的 Web Admin API key 視為高權限憑證。
### `POST /api/nodes/sync`

同步所有 configured nodes。
### `POST /api/nodes/{index}/sync`

同步指定 node。
### `POST /api/config/sync`

透過 central/node mechanism 同步受支援 config。

## 14. Extension API

### `/api/extensions/{extensionName}/...`

Registered API extension 位於此 prefix；實際 child route 由已安裝/註冊 extension 決定，client 不應假設任意 extension name 或 method 都存在。

## 15. CORS、rate limit 與 proxy 部署

Shipped `config.yml` 中 Web Admin 預設 disabled、host `localhost`、port `8080`、rate limit enabled 且 120/minute、`cors-allowed-origins` 為空 list。缺少或空值會拒絕跨來源瀏覽器存取。可設定 YAML list，或以逗號分隔的 scalar；只有明確設定 `*` 才允許 wildcard，origin 以完整字串比對。允許的 origin 會在 REST 與 SSE 的成功及錯誤 response 收到 `Access-Control-Allow-Origin`；service 不啟用 credential sharing。未列入的 origin 不會收到此 header。CORS 是瀏覽器 response policy，不能取代 Bearer API-key authentication，也不會阻止非瀏覽器 client。

Node proxy path 限制在已設定 node origin 的 `/api` 路徑下。靜態 node URL 檢查會拒絕 user-info、query、fragment 與 local/special-use address range，但不會將 DNS 結果固定至最終 HTTP connection，因此 DNS rebinding 防護仍為 `DEFERRED / NOT VERIFIED`。請保護 Web Admin API key，並限制對外 network access 只到預期 node host。

Internet-facing reverse proxy 應：

- 更換 shipped API-key placeholder；
- 設定明確 trusted origins；
- 只有實際位於會控制 forwarded header 的 trusted proxy 後方時才啟用 `trust-proxy`；
- 在 proxy 使用 TLS；
- 限制 node API network access 並保護 node API key。

## 16. API 變更規則

修改 API 時：

1. 檢查 actual handler、`RouteRegistry`、`HttpHandlerRegistry`、auth filter、CORS layer 與 frontend caller；
2. 除非刻意 version contract，否則維持 response/error envelope compatibility；
3. 不得在 Undertow I/O thread 上阻塞 Redis/SQL/remote call；
4. 檢查 duplicate method/path registration；
5. 同步更新本文件與 `API_REFERENCE.md`；
6. 適用時更新 frontend type/client/test 與 embedded frontend asset；
7. `/ws` authentication/transport 屬安全敏感變更，完成 targeted test 前不得描述為已支援。
