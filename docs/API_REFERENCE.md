# Syncmoney Web API Reference

> Project version: `1.4.0`
> Backend: Undertow
> Default bind in shipped configuration: `localhost:8080`
> This reference documents routes and behavior verified in the current source tree.

Traditional Chinese: [`API_REFERENCE.zh_tw.md`](API_REFERENCE.zh_tw.md)

## 1. Base behavior

Web Admin is disabled by default. When enabled, the server hosts the SPA, health endpoint, REST API, and SSE endpoint. `/ws` is retained as an unavailable endpoint and always responds with `503 WEBSOCKET_UNAVAILABLE`.

Normal API calls use:

```http
Authorization: Bearer <api-key>
```

`/health` is intentionally unauthenticated. Normal `/api/*` routes pass through API-key authentication. `ApiKeyAuthFilter` compares keys in constant time and can reject requests with `429 RATE_LIMITED`.

When `web-admin.security.trust-proxy=true`, forwarded client addresses may be read from `X-Forwarded-For`. When it is false, that header is not trusted for the client identity used by rate limiting.

The shipped config contains `change-me-in-production` as the API-key placeholder and a rate limit of 120 requests/minute. The `WebAdminConfig` code fallback is 60/minute when that setting is absent. `WebServiceManager` disables Web Admin when the key is blank or equals `change-me` or `change-me-in-production` (case-insensitive after trimming). The economy plugin continues to start; configure a non-placeholder key to enable Web Admin.

## 2. Response envelope

Most successful JSON endpoints use:

```json
{
  "success": true,
  "data": {},
  "meta": {
    "timestamp": 0,
    "version": "1.4.0"
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
    "version": "1.4.0"
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

Requests with a missing or invalid credential receive `401` and do not establish an SSE connection.

## 6. WebSocket status

### `GET /ws` / WebSocket upgrade path

The current WebSocket implementation is incomplete and is **not a supported production transport**.

Every `/ws` request, including a WebSocket upgrade with a non-empty token, receives `503 WEBSOCKET_UNAVAILABLE`; the server never emits an upgrade response. Full WebSocket transport remains deferred.

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

Lists configured/known nodes. Node URL fields and the central-node `selfUrl` omit URI user-info, query, and fragment; invalid URLs are represented as `[invalid URL]`.

### `POST /api/nodes`

Creates/registers a node using the current node request model. The URL must not contain URI user-info, a query, or a fragment and must pass the configured address restrictions.

### `PUT /api/nodes/{index}`

Updates the node at the specified index. A supplied URL must not contain URI user-info, a query, or a fragment and must pass the configured address restrictions.

### `DELETE /api/nodes/{index}`

Deletes the node at the specified index.

### `POST /api/nodes/{index}/ping`

Runs the node ping/health action.

### `GET /api/nodes/status`

Returns node status data; URL values omit URI user-info, query, and fragment before API responses and SSE broadcasts, and invalid URLs are represented as `[invalid URL]`.

### `POST /api/nodes/{index}/proxy`

Proxies an allowed request through the central node handler. The `path` must be a relative URI under `/api` with no authority or fragment; its optional query string is forwarded to the same configured node origin. Absolute URLs, URI authorities, fragments, dot-segment traversal (including percent-encoded forms), and excessive path encoding are rejected. Accepted proxy methods are `GET`, `HEAD`, `OPTIONS`, `POST`, `PUT`, `PATCH`, and `DELETE`. A scoped API key must have `syncmoney.web.nodes.view` for this endpoint and the permission mapped to the target path; unmapped target paths require `syncmoney.web.central`. State-changing methods (`POST`, `PUT`, `PATCH`, and `DELETE`) additionally require `syncmoney.web.nodes.manage` or `syncmoney.web.central`. Unsupported methods and permission failures are rejected before the configured node key is used. The outer request body is limited to 1 MiB; larger requests receive HTTP 413 before JSON parsing or node-key use. The upstream body is fully read into a bounded buffer of up to 8 MiB before the response is sent; a larger body receives HTTP 502 without forwarding a partial body. For `POST`, `PUT`, and `PATCH`, the optional `body` field is any JSON value and is forwarded as JSON; other methods are sent without a body. The request is sent to the configured node origin with that node's Bearer API key only after path and permission checks. Configured node URLs reject user-info, query, fragment, and a static list of local/special-use IPv4 ranges, including shared, protocol-assignment, documentation, benchmarking, multicast, and reserved blocks, plus IPv6 unspecified, loopback, link-local, site-local, multicast, and ULA addresses. Node URL validation failures log only the exception type; they do not log the URL, user-info, query, or fragment. Node URLs and credentials are security-sensitive. DNS-rebinding resistance is `DEFERRED / NOT VERIFIED`; restrict server egress to intended nodes and treat the trusted Web Admin API key as privileged.

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

The shipped `config.yml` has Web Admin disabled, host `localhost`, port `8080`, rate limit enabled, 120 requests/minute, and an empty `cors-allowed-origins` list. A missing or empty value denies cross-origin browser access. Configure a YAML list or a scalar containing comma-separated origins; `*` is allowed only when explicitly configured. Matching is exact. Allowed origins receive `Access-Control-Allow-Origin` on REST and SSE success and error responses. The service does not enable credential sharing. An unlisted origin receives no `Access-Control-Allow-Origin` header. CORS is a browser response policy; it does not replace Bearer API-key authentication or block non-browser clients.

Node proxy paths are restricted to `/api` on the configured node origin. Static node URL validation rejects user-info, query, fragment, and local/special-use address ranges, but it does not pin DNS results to the eventual HTTP connection; DNS-rebinding resistance remains `DEFERRED / NOT VERIFIED`. Keep the Web Admin API key private and restrict outbound network access to the intended node hosts.

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
