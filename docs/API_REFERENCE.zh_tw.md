# Syncmoney Web API 參考

> 專案版本：`1.3.1`
> Backend：Undertow
> shipped config 預設綁定：`localhost:8080`
> 本文件描述目前 source tree 中已驗證的 route 與行為。

英文版：[`API_REFERENCE.md`](API_REFERENCE.md)

## 1. 基本行為

Web Admin 預設停用。啟用後，server 提供 SPA、health endpoint、REST API、SSE，以及目前仍不完整的 WebSocket endpoint。

一般 API request 使用：

```http
Authorization: Bearer <api-key>
```

`/health` 刻意不需 authentication；一般 `/api/*` route 會經過 API-key authentication。`ApiKeyAuthFilter` 使用 constant-time key comparison，且可因 rate limit 回傳 `429 RATE_LIMITED`。

只有 `web-admin.security.trust-proxy=true` 時，才會從 `X-Forwarded-For` 讀取 forwarded client address；未啟用時，rate limiting 使用的 client identity 不信任該 header。

Shipped config 的 API-key placeholder 是 `change-me-in-production`，rate limit 是 120 requests/minute；若 rate-limit setting 缺少，`WebAdminConfig` code fallback 為 60/minute。Config validation 會警告 `change-me-in-production`，但目前 server auto-disable logic 只檢查精確字串 `change-me`。因此在對外暴露服務前必須更換 shipped key。

## 2. Response envelope

多數成功 JSON：

```json
{
  "success": true,
  "data": {},
  "meta": {
    "timestamp": 0,
    "version": "1.3.1"
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
    "version": "1.3.1"
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

## 6. WebSocket 狀態

### `GET /ws` / WebSocket upgrade path

目前 WebSocket implementation **不完整，不是受支援的 production transport**。

已驗證行為：

- 非 upgrade-shaped request 回 `503 WEBSOCKET_UNAVAILABLE`；
- upgrade-shaped request 目前只要求 query token 非空；
- handler 目前沒有呼叫 `WsTokenHandler.validateToken` 驗證該 token；
- 會產生 101-shaped response 與 internal bookkeeping/logging；
- broadcast method 尚未提供完整可用的 Undertow WebSocket message transport。

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

列出 nodes。
### `POST /api/nodes`

依目前 request model 建立/註冊 node。
### `PUT /api/nodes/{index}`

更新指定 index node。
### `DELETE /api/nodes/{index}`

刪除指定 index node。
### `POST /api/nodes/{index}/ping`

執行 node ping/health。
### `GET /api/nodes/status`

node status。
### `POST /api/nodes/{index}/proxy`

透過 central node handler proxy 允許的 request；node URL、credential 與 proxy restriction 屬安全敏感內容。
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

Shipped `config.yml` 中 Web Admin 預設 disabled、host `localhost`、port `8080`、rate limit enabled 且 120/minute、`cors-allowed-origins` 為空 list。`WebAdminConfig` 在 key 缺失時有自己的 fallback，包括 `*` CORS fallback。

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
