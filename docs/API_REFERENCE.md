# Syncmoney Web API Reference

> Project version: `1.3.2`
> Backend: Undertow
> Default bind in shipped configuration: `localhost:8080`
> This reference documents routes and behavior verified in the current source tree.

Traditional Chinese: [`API_REFERENCE.zh_tw.md`](API_REFERENCE.zh_tw.md)

## 1. Base behavior

Web Admin is disabled by default. When enabled, the server hosts the SPA, health endpoint, REST API, SSE endpoint, and the current partial WebSocket endpoint.

Normal API calls use:

```http
Authorization: Bearer <api-key>
```

`/health` is intentionally unauthenticated. Normal `/api/*` routes pass through API-key authentication. `ApiKeyAuthFilter` compares keys in constant time and can reject requests with `429 RATE_LIMITED`.

When `web-admin.security.trust-proxy=true`, forwarded client addresses may be read from `X-Forwarded-For`. When it is false, that header is not trusted for the client identity used by rate limiting.

The shipped config contains `change-me-in-production` as the API-key placeholder and a rate limit of 120 requests/minute. The `WebAdminConfig` code fallback is 60/minute when that setting is absent. Configuration validation warns about the placeholder, but current server auto-disable logic checks only exact `change-me`; replace the shipped key before exposing the service.

## 2. Response envelope

Most successful JSON endpoints use:

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

Errors normally use:

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

Cursor-paginated responses are a special case and omit the normal `meta` envelope.

Unhandled route exceptions are mapped by `HttpHandlerRegistry`:

| Exception | HTTP |
|---|---:|
| `IllegalArgumentException`, `IllegalStateException` | 400 |
| `SecurityException` | 403 |
| `NoSuchElementException` | 404 |
| `UnsupportedOperationException` | 405 |
| other exceptions | 500 |

Unknown routes return `404 NOT_FOUND` with an endpoint-not-found message.

## 3. Health

### `GET /health`

Unauthenticated health probe intended for service monitoring.

Do not expose secrets or privileged configuration through this endpoint.

## 4. Authentication and live-session token

### `POST /api/auth/ws-token`

Requires the normal Bearer API key.

Returns a one-time UUID token. The current token lifetime is 60,000 ms. Successful validation consumes/removes the token.

Representative response data:

```json
{
  "token": "<uuid>",
  "expires": 0,
  "validityMs": 60000
}
```

The token is used by SSE. Despite the historical route/class name, the current production live path is `/api/sse`.

## 5. Server-Sent Events

### `GET /api/sse`

This is the implemented frontend live transport.

Accepted authentication forms:

- `Authorization: Bearer <api-key>`
- `Authorization: Bearer <one-time-token>`
- `?token=<one-time-token>`

The API key comparison is constant-time. One-time tokens are consumed when validated. The manager sends keepalive activity about every 15 seconds. `PostTransactionEvent` feeds transaction broadcasts; other server modules can also publish live events.

Clients should reconnect through the frontend token flow rather than assuming a token can be reused indefinitely.

## 6. WebSocket status

### `GET /ws` / WebSocket upgrade path

The current WebSocket implementation is incomplete and is **not a supported production transport**.

Verified current behavior:

- a request that does not look like an upgrade receives `503 WEBSOCKET_UNAVAILABLE`;
- upgrade-shaped requests require only a non-empty query token;
- the handler currently does **not** call `WsTokenHandler.validateToken`;
- it emits a 101-shaped upgrade response and internal bookkeeping/logging;
- broadcast methods do not provide a complete working Undertow WebSocket message transport.

Use `/api/sse` for the frontend live channel. Do not treat the presence of `/ws` as proof of hardened authentication or complete message delivery.

## 7. System API

### `GET /api/system/status`

Returns plugin/system status for the Web Admin dashboard.

### `GET /api/system/redis`

Returns Redis connectivity/status information suitable for administration.

### `GET /api/system/breaker`

Returns breaker/protection status.

### `GET /api/system/metrics`

Returns runtime metrics exposed by the current system handler.

## 8. Economy API

### `GET /api/economy/stats`

Returns economy summary statistics such as supply/player/activity data available to the current node.

### `GET /api/economy/player/{uuid}/balance`

Returns the balance for the requested UUID.

The path argument must be a valid player UUID accepted by the handler.

### `GET /api/economy/top`

Returns leaderboard data.

## 9. Cross-server economy API

These routes are intended for central/cross-server operation.

### `GET /api/economy/cross-server-stats`

Returns aggregated node data.

Current aggregation semantics matter:

- `onlinePlayers` is summed across online nodes;
- `totalSupply`, `totalPlayers`, and `todayTransactions` are shared/global database values and are **not** summed;
- for those shared values, the current aggregator takes the value from the first online node that provides it.

Consumers must not sum the already-shared totals again.

### `GET /api/economy/cross-server-top`

Returns cross-server leaderboard data for central-mode use.

## 10. Audit API

Audit routes depend on `audit.enabled`. If audit is disabled, the handler returns `503 FEATURE_DISABLED` and indicates that enabling the feature requires configuration/restart as implemented.

### `GET /api/audit/player/{name}`

Returns audit history for a player name.

### `GET /api/audit/search`

Searches audit records.

The handler supports conventional `page` / `pageSize` pagination and can also enter cursor-oriented behavior where supported by the supplied parameters. `pageSize` is clamped to the range 1..100.

### `GET /api/audit/search-cursor`

Dedicated cursor-based audit search.

Cursor-paginated responses do not use the normal response `meta` envelope.

### `GET /api/audit/stats`

Returns audit statistics.

## 11. Configuration API

### `GET /api/config`

Returns configuration data exposed by the Web Admin configuration handler.

### `PUT /api/config`

Accepts either a batch:

```json
{
  "changes": [
    {
      "section": "display",
      "key": "example",
      "value": "value"
    }
  ],
  "hotReload": true
}
```

or a single change:

```json
{
  "section": "display",
  "key": "example",
  "value": "value",
  "hotReload": true
}
```

`hotReload` defaults to `true`.

The REST handler can request reload behavior, but runtime reload safety is still governed by `ConfigReloadPolicy`. The current live-reload roots are `display`, `pay`, `permissions`, `admin-permissions`, and `debug`. Settings that create connections/resources or change authority should be treated as restart-required unless the implementation explicitly supports them.

### `POST /api/config/validate`

Request body:

```json
{
  "section": "display",
  "key": "example",
  "value": "value"
}
```

Validates the requested setting/value according to the current configuration handler.

### `POST /api/config/reload`

Requests configuration reload through the backend.

## 12. Web UI settings API

### `GET /api/settings`

Returns Web Admin UI settings. Current response data includes:

- `theme`
- `language`
- `timezone`
- `dedupServerWindowSeconds`
- `dedupClientCacheSize` (currently 1000)

### `POST /api/settings/theme`

Accepted values: `dark`, `light`.

### `POST /api/settings/language`

Accepted values: `zh-TW`, `en-US`.

### `POST /api/settings/timezone`

Accepts `UTC` or `UTC+/-<hours>` in the current parser. The implementation constrains the absolute offset to 12 hours.

Settings persistence is debounced by about 500 ms.

## 13. Node-management API

The node handler provides the effective implementations for `/api/nodes` routes because it is registered after the system handler for duplicate method/path keys.

### `GET /api/nodes`

Lists configured/known nodes.

### `POST /api/nodes`

Creates/registers a node using the current node request model.

### `PUT /api/nodes/{index}`

Updates the node at the specified index.

### `DELETE /api/nodes/{index}`

Deletes the node at the specified index.

### `POST /api/nodes/{index}/ping`

Runs the node ping/health action.

### `GET /api/nodes/status`

Returns node status data.

### `POST /api/nodes/{index}/proxy`

Proxies an allowed request through the central node handler. Node URLs, credentials, and proxy restrictions are security-sensitive; follow the implementation rather than constructing arbitrary forwarding behavior in clients.

### `POST /api/nodes/sync`

Triggers synchronization across configured nodes.

### `POST /api/nodes/{index}/sync`

Triggers synchronization for one node.

### `POST /api/config/sync`

Synchronizes supported configuration through the central/node mechanism.

## 14. Extension API

### `/api/extensions/{extensionName}/...`

Registered API extensions live under this prefix. Exact child routes are defined by the installed/registered extension, so clients should not assume arbitrary extension names or methods.

## 15. CORS, rate limiting, and proxy deployment

The shipped `config.yml` has Web Admin disabled, host `localhost`, port `8080`, rate limit enabled, 120 requests/minute, and an empty `cors-allowed-origins` list. `WebAdminConfig` has its own fallback values when keys are absent, including a `*` CORS fallback.

For an Internet-facing reverse proxy:

- replace the API-key placeholder;
- configure explicit trusted origins;
- enable `trust-proxy` only when the service is actually behind a trusted proxy that controls forwarded headers;
- use TLS at the proxy;
- restrict network access to node APIs and protect node API keys.

## 16. API change rules

When changing this API:

1. inspect the actual handler, `RouteRegistry`, `HttpHandlerRegistry`, auth filter, CORS layer, and frontend caller;
2. preserve response/error envelope compatibility unless intentionally versioning the contract;
3. avoid blocking Redis/SQL/remote calls on Undertow I/O threads;
4. verify duplicate method/path registrations;
5. update this file and `API_REFERENCE.zh_tw.md` together;
6. update frontend types/client/tests and embedded frontend assets when applicable;
7. treat `/ws` authentication/transport work as security-sensitive and add targeted tests before describing it as supported.
