# Syncmoney API Reference

Complete API reference for Syncmoney v1.1.2

> **Last Updated**: 2026-03-22

---

## Table of Contents

1. [Authentication](#authentication)
2. [API Response Format](#api-response-format)
3. [REST Endpoints](#rest-endpoints)
   - [Health](#health)
   - [System](#system)
   - [Economy](#economy)
   - [Nodes (Central Mode)](#nodes-(central-mode))
   - [Cross-Server Statistics](#cross-server-statistics)
   - [Configuration](#configuration)
   - [Audit Log](#audit-log)
   - [Settings](#settings)
   - [Authentication](#authentication-1)
4. [Real-Time Events](#real-time-events)
   - [Server-Sent Events (SSE)](#server-sent-events-(sse)-—-recommended)
   - [WebSocket](#websocket)
5. [API Extension Framework](#api-extension-framework)
6. [Error Responses](#error-responses)
7. [Rate Limiting](#rate-limiting)
8. [Best Practices](#best-practices)

---

## Authentication

Most API endpoints require authentication using an API key. The `/health` endpoint is public.

### Header

```
Authorization: Bearer <your-api-key>
```

API Key is set in `config.yml`:

```yaml
web-admin:
  security:
    api-key: "your-secure-api-key-here"
```

### Permission System

The API uses a permission-based authorization system:

| Permission | Description |
|------------|-------------|
| `syncmoney.web.nodes.view` | View node information |
| `syncmoney.web.nodes.manage` | Create, update, delete nodes |
| `syncmoney.web.central` | Access central mode features |

Permissions are assigned through the game's permission system (LuckPerms, PermissionsEx, etc.).

### Response Codes

| Code | Description |
|------|-------------|
| 200 | Success |
| 400 | Bad Request (validation error) |
| 401 | Unauthorized (invalid API key) |
| 403 | Forbidden (insufficient permissions) |
| 404 | Not Found |
| 429 | Rate Limited |
| 500 | Internal Server Error |

---

## API Response Format

All successful responses follow this format:

```json
{
  "success": true,
  "data": { ... },
  "meta": {
    "timestamp": 1709337000000,
    "version": "1.1.2"
  }
}
```

All error responses follow this format:

```json
{
  "success": false,
  "error": {
    "code": "ERROR_CODE",
    "message": "Human readable message"
  },
  "meta": {
    "timestamp": 1709337000000,
    "version": "1.1.2"
  }
}
```

### Pagination (Offset-based)

```json
{
  "success": true,
  "data": [ ... ],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "totalItems": 100,
    "totalPages": 5
  },
  "meta": {
    "timestamp": 1709337000000,
    "version": "1.1.2"
  }
}
```

### Pagination (Cursor-based)

```json
{
  "success": true,
  "data": [ ... ],
  "pagination": {
    "nextCursor": "1709337000000,3",
    "hasMore": true,
    "pageSize": 20
  }
}
```

> **Cursor format**: `<timestamp>,<sequence>` — used for audit log pagination where Redis + DB records are merged and deduplicated.

---

## REST Endpoints

### Health

#### GET /health

Health check endpoint (**no authentication required**).

**Response:**
```json
{
  "success": true,
  "data": {
    "status": "ok",
    "version": "1.1.2"
  }
}
```

---

### System

#### GET /api/system/status

Get overall system status including plugin info, uptime, player counts, and database status.

**Response:**
```json
{
  "success": true,
  "data": {
    "plugin": {
      "name": "Syncmoney",
      "version": "1.1.2",
      "mode": "SYNC",
      "uptime": 3600000
    },
    "economy": {
      "mode": "SYNC",
      "currencyName": "dollars"
    },
    "redis": {
      "connected": true,
      "enabled": true
    },
    "database": {
      "connected": true,
      "enabled": true,
      "type": "mysql"
    },
    "circuitBreaker": {
      "enabled": true,
      "state": "NORMAL",
      "lastTrigger": null
    },
    "serverName": "lobby",
    "onlinePlayers": 15,
    "maxPlayers": 100
  }
}
```

**circuitBreaker.state possible values:** `NORMAL`, `WARNING`, `LOCKED`

---

#### GET /api/system/redis

Get Redis connection status.

**Response:**
```json
{
  "success": true,
  "data": {
    "connected": true,
    "enabled": true
  }
}
```

---

#### GET /api/system/breaker

Get circuit breaker status.

**Response:**
```json
{
  "success": true,
  "data": {
    "enabled": true,
    "state": "NORMAL"
  }
}
```

**Possible states:** `NORMAL`, `WARNING`, `LOCKED`

---

#### GET /api/system/metrics

Get system metrics including memory usage, thread count, and TPS.

**Response:**
```json
{
  "success": true,
  "data": {
    "memory": {
      "total": 536870912,
      "free": 268435456,
      "used": 268435456,
      "max": 1073741824
    },
    "threads": 45,
    "tps": 20.0
  }
}
```

---

### Economy

#### GET /api/economy/stats

Get economy statistics including total supply, player counts, and transaction data.

**Response:**
```json
{
  "success": true,
  "data": {
    "totalSupply": 1500000.50,
    "totalPlayers": 250,
    "todayTransactions": 42,
    "cachedPlayers": 15,
    "currencyName": "$"
  }
}
```

---

#### GET /api/economy/player/{uuid}/balance

Get a specific player's current balance by UUID.

**Parameters:**
- `uuid` (path, required) — Player UUID (e.g., `a1b2c3d4-e5f6-7890-abcd-ef1234567890`)

**Response:**
```json
{
  "success": true,
  "data": {
    "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "balance": 5000.00,
    "currencyName": "$"
  }
}
```

**Error Codes:**
- `PLAYER_UUID_REQUIRED` — UUID parameter missing
- `INVALID_UUID` — UUID format is invalid

---

#### GET /api/economy/top

Get the top 10 players by balance (leaderboard).

**Response:**
```json
{
  "success": true,
  "data": {
    "topPlayers": [
      { "rank": 1, "uuid": "a1b2c3d4-...", "balance": 99999.00 },
      { "rank": 2, "uuid": "e5f6a7b8-...", "balance": 75000.00 }
    ],
    "currencyName": "$"
  }
}
```

---

### Nodes (Central Mode)

These endpoints are available when Central Mode is enabled in the configuration.

#### GET /api/nodes

Get list of all configured nodes.

**Response:**
```json
{
  "success": true,
  "data": {
    "nodes": [
      {
        "index": 0,
        "name": "Survival Server 1",
        "url": "http://192.168.1.105:8080",
        "enabled": true,
        "status": "online"
      }
    ],
    "centralMode": true,
    "total": 1,
    "selfUrl": null
  }
}
```

**Requires permission:** `syncmoney.web.nodes.view`

---

#### GET /api/nodes/status

Get detailed status of all nodes (Central Mode only).

**Response:**
```json
{
  "success": true,
  "data": {
    "http://192.168.1.105:8080": {
      "serverName": "Survival-1",
      "serverId": "survival-1",
      "onlinePlayers": 42,
      "maxPlayers": 100,
      "economyMode": "SYNC",
      "status": "online",
      "lastPing": 1711111111111
    }
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| `serverName` | String | Display name of the server |
| `serverId` | String | Unique server identifier |
| `onlinePlayers` | Integer | Current online player count |
| `maxPlayers` | Integer | Maximum player capacity |
| `economyMode` | String | Economy mode (LOCAL, SYNC, CMI, etc.) |
| `status` | String | Connection status (online/offline/unknown/disabled) |
| `lastPing` | Long | Timestamp of last successful ping |

**Requires permission:** `syncmoney.web.nodes.view`

---

#### POST /api/nodes

Create a new node.

**Request Body:**
```json
{
  "name": "New Server",
  "url": "http://192.168.1.106:8080",
  "apiKey": "your-api-key",
  "enabled": true
}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "index": 1,
    "name": "New Server",
    "url": "http://192.168.1.106:8080",
    "enabled": true,
    "status": "offline"
  }
}
```

**Requires permission:** `syncmoney.web.nodes.manage`

---

#### PUT /api/nodes/{index}

Update an existing node.

**Parameters:**
- `index` (path, required) — Node index to update

**Request Body:**
```json
{
  "name": "Updated Server Name",
  "url": "http://192.168.1.107:8080",
  "apiKey": "new-api-key",
  "enabled": false
}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "index": 0,
    "name": "Updated Server Name",
    "url": "http://192.168.1.107:8080",
    "enabled": false,
    "status": "online"
  }
}
```

**Requires permission:** `syncmoney.web.nodes.manage`

---

#### DELETE /api/nodes/{index}

Delete a node.

**Parameters:**
- `index` (path, required) — Node index to delete

**Response:**
```json
{
  "success": true,
  "data": {
    "deleted": true,
    "index": 0,
    "deletedUrl": "http://192.168.1.105:8080"
  }
}
```

**Requires permission:** `syncmoney.web.nodes.manage`

---

#### POST /api/nodes/{index}/ping

Manually ping a specific node.

**Parameters:**
- `index` (path, required) — Node index to ping

**Response:**
```json
{
  "success": true,
  "data": {
    "index": 0,
    "status": "online",
    "latencyMs": 15
  }
}
```

**Requires permission:** `syncmoney.web.nodes.view` or `syncmoney.web.central`

---

#### POST /api/nodes/{index}/proxy

Proxy an HTTP request to a remote node (Central Mode only). This allows the central node to forward API requests to other game server nodes.

**Parameters:**
- `index` (path, required) — Node index to proxy the request to

**Request Body:**
```json
{
  "method": "GET",
  "path": "/api/economy/stats",
  "body": null
}
```

**Supported methods:** `GET`, `POST`, `PUT`, `PATCH`, `DELETE`

**Response:**
The response from the remote node is forwarded directly. HTTP status code and body are the same as returned by the target node.

**Requires permission:** `syncmoney.web.nodes.view` or `syncmoney.web.central`

---

#### POST /api/nodes/sync

Push configuration changes to all enabled nodes (Central Mode only). This endpoint is restricted to central mode.

**Request Body:**
```json
{
  "changes": [
    { "section": "economy", "key": "pay.max-amount", "value": 100000 },
    { "section": "messages", "key": "prefix", "value": "[Syncmoney]" }
  ],
  "reload": true
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `changes` | Array | Yes | List of configuration changes to apply |
| `changes[].section` | String | Yes | Configuration section (e.g., `economy`, `messages`) |
| `changes[].key` | String | Yes | Configuration key (dot notation supported) |
| `changes[].value` | Any | Yes | New value |
| `reload` | Boolean | No | Whether to reload config after applying (default: `true`) |

**Response:**
```json
{
  "success": true,
  "data": {
    "total": 3,
    "succeeded": 2,
    "failed": 1,
    "results": [
      { "index": 0, "name": "Survival-1", "status": "synced" },
      { "index": 1, "name": "Factions-1", "status": "failed", "error": "http_403" }
    ]
  }
}
```

**Requires permission:** `syncmoney.web.central`

---

#### POST /api/nodes/{index}/sync

Push configuration changes to a specific node (Central Mode only).

**Parameters:**
- `index` (path, required) — Node index to sync configuration to

**Request Body:** Same as `POST /api/nodes/sync`

**Response:**
```json
{
  "success": true,
  "data": {
    "applied": 1,
    "reloaded": true,
    "changes": ["economy.pay.max-amount=100000"]
  }
}
```

**Requires permission:** `syncmoney.web.central`

---

#### POST /api/config/sync

Receive configuration sync from the central node (Node-side endpoint). This endpoint is used by nodes to receive configuration pushed from the central management server.

**Request Body:**
```json
{
  "changes": [
    { "section": "economy", "key": "pay.max-amount", "value": 100000 }
  ],
  "reload": true
}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "applied": 1,
    "reloaded": true,
    "changes": ["economy.pay.max-amount=100000"]
  }
}
```

---

### Cross-Server Statistics

Aggregated statistics across all servers in Central Mode.

#### GET /api/economy/cross-server-stats

Get aggregated statistics across all nodes (Central Mode only).

**Response:**
```json
{
  "success": true,
  "data": {
    "totalSupply": 5000000.00,
    "totalPlayers": 5000,
    "totalOnlinePlayers": 150,
    "todayTransactions": 2000,
    "nodesStatus": {
      "Survival-1": 50,
      "Factions-1": 60,
      "SMP-1": 40
    },
    "source": "aggregated",
    "timestamp": 1711111111111
  }
}
```

**Requires permission:** `syncmoney.web.central`

---

#### GET /api/economy/cross-server-top

Get aggregated cross-server leaderboard.

**Response:**
```json
{
  "success": true,
  "data": {
    "topPlayers": [
      {
        "rank": 1,
        "uuid": "a1b2c3d4-...",
        "playerName": "Steve",
        "balance": 99999.00,
        "serverName": "Survival-1"
      }
    ],
    "currencyName": "$"
  }
}
```

**Requires permission:** `syncmoney.web.central`

---

### Configuration

#### GET /api/config

Get current plugin configuration (sensitive data hidden).

**Response:**
```json
{
  "success": true,
  "data": {
    "redis": {
      "enabled": true,
      "host": "localhost",
      "port": 6379,
      "password": "***HIDDEN***"
    },
    "database": {
      "type": "mysql",
      "host": "localhost",
      "port": 3306,
      "database": "syncmoney"
    },
    "circuitBreaker": {
      "enabled": true
    },
    "audit": {
      "enabled": true,
      "batchSize": 1
    },
    "economy": {
      "currencyName": "$",
      "currencySymbol": "$",
      "decimalPlaces": 2
    }
  }
}
```

---

#### PUT /api/config

Batch update configuration.

**Request Body (format 1 — batch changes):**
```json
{
  "changes": [
    { "section": "economy", "key": "currencyName", "value": "coins" },
    { "section": "audit", "key": "batchSize", "value": 10 }
  ]
}
```

**Request Body (format 2 — single change):**
```json
{
  "section": "economy",
  "key": "currencyName",
  "value": "coins"
}
```

**Optional: Trigger hot reload after save:**
```json
{
  "changes": [...],
  "hotReload": true
}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "message": "Configuration saved successfully",
    "hotReload": true
  }
}
```

---

#### POST /api/config/validate

Validate configuration values before saving.

**Request Body:**
```json
{
  "section": "economy",
  "key": "currencyName",
  "value": "coins"
}
```

**Response (valid):**
```json
{
  "success": true,
  "data": {
    "valid": true
  }
}
```

**Response (invalid):**
```json
{
  "success": false,
  "error": {
    "code": "INVALID_CONFIG",
    "message": "Invalid value for currencyName"
  }
}
```

---

#### POST /api/config/reload

Reload plugin configuration from disk.

**Response:**
```json
{
  "success": true,
  "data": {
    "message": "Configuration reloaded successfully"
  }
}
```

---

### Audit Log

Audit API supports two pagination modes:
1. **Offset-based** (`page` + `pageSize`) — for simple page navigation
2. **Cursor-based** (`cursor` + `pageSize`) — for efficient infinite scrolling with Redis + DB hybrid query

The handler queries data from **three priority sources**:
1. `HybridAuditManager` (Redis real-time + DB historical, merged & deduplicated)
2. `LocalEconomyHandler` (LOCAL mode SQLite fallback)
3. `AuditLogger` (basic DB query fallback)

---

#### GET /api/audit/player/{name}

Get audit records for a specific player.

**Parameters:**
- `name` (path, required) — Player name
- `page` (query, optional) — Page number (default: 1)
- `pageSize` (query, optional) — Items per page (default: 20, max: 100)

**Response:**
```json
{
  "success": true,
  "data": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "timestamp": 1709337000000,
      "sequence": 0,
      "type": "DEPOSIT",
      "playerUuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "playerName": "Steve",
      "amount": "1000",
      "balanceAfter": "6000",
      "source": "VAULT",
      "serverName": "lobby"
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "totalItems": 1,
    "totalPages": 1
  }
}
```

**Audit record fields:**

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique record ID (UUID) |
| `timestamp` | number | Epoch milliseconds |
| `sequence` | number | Sequence number (for ordering within same millisecond) |
| `type` | string | `DEPOSIT`, `WITHDRAW`, `TRANSFER`, `SET_BALANCE`, `CRITICAL_FAILURE` |
| `playerUuid` | string | Player UUID |
| `playerName` | string | Player name |
| `amount` | string | Transaction amount (signed: positive for deposit, negative for withdraw) |
| `balanceAfter` | string | Balance after transaction |
| `source` | string | Event source (see table below) |
| `serverName` | string | Server that processed the transaction |
| `targetUuid` | string? | Target player UUID (transfers only) |
| `targetName` | string? | Target player name (transfers only) |
| `reason` | string? | Transaction reason (optional) |
| `mergedCount` | number? | Number of merged transactions (only present if > 1) |

**Event source values:**

| Source | Description |
|--------|-------------|
| `VAULT` | Via Vault API (other plugins) |
| `COMMAND_PAY` | `/pay` command |
| `COMMAND_ADMIN` | Admin command |
| `ADMIN_GIVE` | `/syncmoney admin give` |
| `ADMIN_TAKE` | `/syncmoney admin take` |
| `ADMIN_SET` | `/syncmoney admin set` |
| `REDIS_SYNC` | Cross-server sync |
| `PLAYER_TRANSFER` | Player-to-player transfer |
| `CMI_SYNC` | CMI economy sync |
| `MIGRATION` | Data migration |

**Error Codes:**
- `INVALID_PLAYER` — Player name is empty
- `PLAYER_NOT_FOUND` — Player not found in server cache

---

#### GET /api/audit/search

Search audit records with filters (offset-based pagination).

**Parameters:**
- `player` (query, optional) — Filter by player name
- `type` (query, optional) — Filter by type: `DEPOSIT`, `WITHDRAW`, `TRANSFER`, `SET_BALANCE`, `all`
- `startTime` (query, optional) — Start time (epoch milliseconds)
- `endTime` (query, optional) — End time (epoch milliseconds)
- `page` (query, optional) — Page number (default: 1)
- `pageSize` (query, optional) — Items per page (default: 20, max: 100)
- `cursor` (query, optional) — If provided, switches to cursor-based pagination (see below)

**Example:**
```
GET /api/audit/search?player=Steve&type=DEPOSIT&page=1&pageSize=20
```

**Response:**
```json
{
  "success": true,
  "data": [ ... ],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "totalItems": 42,
    "totalPages": 3
  }
}
```

> **Note:** When the `cursor` query parameter is present, this endpoint automatically delegates to cursor-based pagination mode (same behavior as `/api/audit/search-cursor`).

---

#### GET /api/audit/search-cursor

Search audit records with cursor-based pagination. This endpoint merges data from **both Redis (real-time) and Database (historical)**, deduplicates by record ID, and sorts newest-first.

**Parameters:**
- `cursor` (query, optional) — Cursor from previous response's `pagination.nextCursor`. Empty or omitted for first page.
- `pageSize` (query, optional) — Items per page (default: 20, max: 100)
- `player` (query, optional) — Filter by player name **or UUID**
- `type` (query, optional) — Filter by audit type: `DEPOSIT`, `WITHDRAW`, `TRANSFER`, `SET_BALANCE`
- `startTime` (query, optional) — Start time filter (epoch milliseconds)
- `endTime` (query, optional) — End time filter (epoch milliseconds)

**Player resolution:** The `player` parameter accepts both player names and UUID strings. Name resolution follows: Online player → Offline cache → `NameResolver` (Redis + DB). If unresolved, returns empty results.

**Example (first page):**
```
GET /api/audit/search-cursor?pageSize=20
```

**Example (next page):**
```
GET /api/audit/search-cursor?cursor=1709337000000,3&pageSize=20
```

**Response:**
```json
{
  "success": true,
  "data": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "timestamp": 1709337000000,
      "sequence": 3,
      "type": "DEPOSIT",
      "playerUuid": "a1b2c3d4-...",
      "playerName": "Steve",
      "amount": "1000",
      "balanceAfter": "6000",
      "source": "VAULT",
      "serverName": "lobby"
    }
  ],
  "pagination": {
    "nextCursor": "1709336500000,0",
    "hasMore": true,
    "pageSize": 20
  }
}
```

**Cursor format:** `<timestamp>,<sequence>` — Records older than the cursor timestamp (or same timestamp but lower sequence) are returned.

**Deduplication:** When the same record exists in both Redis and Database, the Redis version takes priority (first-seen wins by record ID).

---

#### GET /api/audit/stats

Get audit module statistics.

**Response:**
```json
{
  "success": true,
  "data": {
    "bufferSize": 0,
    "enabled": true
  }
}
```

---

### Settings

#### GET /api/settings

Get current user settings (theme, language, timezone).

**Response:**
```json
{
  "success": true,
  "data": {
    "theme": "dark",
    "language": "zh-TW",
    "timezone": "Asia/Taipei"
  }
}
```

---

#### POST /api/settings/theme

Update theme preference.

**Request Body:**
```json
{
  "theme": "dark"
}
```

**Valid values:** `dark`, `light`

**Response:**
```json
{
  "success": true,
  "data": {
    "theme": "dark"
  }
}
```

**Error:** `INVALID_THEME` — Theme must be `dark` or `light`

---

#### POST /api/settings/language

Update language preference.

**Request Body:**
```json
{
  "language": "zh-TW"
}
```

**Valid values:** `zh-TW`, `en-US`

**Response:**
```json
{
  "success": true,
  "data": {
    "language": "zh-TW"
  }
}
```

**Error:** `INVALID_LANGUAGE` — Language must be `zh-TW` or `en-US`

---

#### POST /api/settings/timezone

Update timezone preference.

**Request Body:**
```json
{
  "timezone": "Asia/Taipei"
}
```

**Valid values:** Any valid IANA timezone identifier (e.g., `UTC`, `Asia/Taipei`, `America/New_York`)

**Response:**
```json
{
  "success": true,
  "data": {
    "timezone": "Asia/Taipei"
  }
}
```

**Error:** `INVALID_TIMEZONE` — Invalid timezone identifier

---

### Authentication

#### POST /api/auth/ws-token

Generate a one-time session token for WebSocket connections. This avoids passing the API key directly in the WebSocket URL.

**Headers:**
```
Authorization: Bearer <your-api-key>
```

**Response:**
```json
{
  "success": true,
  "data": {
    "token": "550e8400-e29b-41d4-a716-446655440000",
    "expires": 1709337060000,
    "validityMs": 60000
  }
}
```

**Token properties:**
- **One-time use**: Token is invalidated after first successful WebSocket connection
- **Short-lived**: Expires after 60 seconds
- **Constant-time validation**: Uses `MessageDigest.isEqual()` to prevent timing attacks
- **Auto-cleanup**: Expired tokens are periodically removed from memory

**Usage flow:**
```
1. POST /api/auth/ws-token  →  { token: "abc-123" }
2. Connect WebSocket: ws://host:port/ws?token=abc-123
3. Token consumed on connect (cannot reuse)
```

---

## Real-Time Events

### Server-Sent Events (SSE) — Recommended

Connect to `http://<host>:<port>/sse` for server-push notifications. **This is the recommended method for real-time updates in v1.1.2.**

**Authentication:** Uses API key via query parameter or Authorization header.

```javascript
// Method 1: Query parameter
const eventSource = new EventSource('http://localhost:8080/sse?apiKey=your-key');

// Method 2: Custom EventSource with header (requires polyfill)
const eventSource = new EventSource('http://localhost:8080/sse', {
  headers: { 'Authorization': 'Bearer your-key' }
});

eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data);
    console.log('SSE event:', data);
};

eventSource.onerror = (error) => {
    console.error('SSE connection error:', error);
    // Auto-reconnect is handled by the browser
};
```

**Event types pushed via SSE:**

| `type` value | Triggered by | Key `data` fields |
|---|---|---|
| `connected` | Client connects | `message` |
| `transaction` | `PostTransactionEvent` fires | `playerName`, `type`, `amount`, `balanceBefore`, `balanceAfter`, `success`, `timestamp` (epoch ms) |
| `circuit_break` | `TransactionCircuitBreakEvent` fires | `previousState`, `currentState`, `reason`, `message` |
| `system_alert` | Manual broadcast / internal alerts | `level`, `message` |

---

### WebSocket

> **v1.1.2 Status:** WebSocket support is **experimental and incomplete**. The `/ws` endpoint accepts upgrade requests, but the underlying message dispatch is not fully implemented. **Use SSE (`/sse`) for reliable real-time updates in production.**

Connect to `ws://<host>:<port>/ws` for real-time updates.

**Authentication:** Use the session token from `POST /api/auth/ws-token`:

```javascript
// Step 1: Get session token
const response = await fetch('http://localhost:8080/api/auth/ws-token', {
    method: 'POST',
    headers: { 'Authorization': 'Bearer your-api-key' }
});
const { data: { token } } = await response.json();

// Step 2: Connect with token
const ws = new WebSocket(`ws://localhost:8080/ws?token=${token}`);

ws.onopen = () => {
    console.log('Connected to Syncmoney WebSocket');
};

ws.onmessage = (event) => {
    const data = JSON.parse(event.data);
    console.log('Event:', data);
};

ws.onerror = (error) => {
    console.error('WebSocket error:', error);
};
```

#### Event: Transaction

```json
{
  "type": "transaction",
  "event": "PostTransactionEvent",
  "data": {
    "playerName": "Steve",
    "type": "DEPOSIT",
    "amount": "1000",
    "balanceBefore": "5000",
    "balanceAfter": "6000",
    "success": true,
    "timestamp": 1709337000000
  }
}
```

> **Note:** `timestamp` is epoch milliseconds (not ISO string). `playerUuid` is not included in the event payload.

#### Event: Circuit Break

```json
{
  "type": "circuit_break",
  "data": {
    "previousState": "WARNING",
    "currentState": "LOCKED",
    "reason": "RATE_LIMIT",
    "message": "Transaction rate limit exceeded",
    "timestamp": 1709337000000
  }
}
```

---

## API Extension Framework

The extension framework allows third-party developers to add custom endpoints to the Syncmoney Web API.

### Creating an Extension

**1. Implement the `ApiExtension` interface:**

```java
package com.example.syncext;

import noietime.syncmoney.web.api.extension.*;

public class MyExtension implements ApiExtension {

    @Override
    public String getName() {
        return "my-extension";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "My custom extension";
    }

    @Override
    public String getAuthor() {
        return "Your Name";
    }

    @Override
    public void registerRoutes(ApiExtensionRouter router) {
        // Register routes: /api/extensions/my-extension/stats
        router.get("stats", exchange -> handleGetStats(exchange));
        router.post("action", exchange -> handlePostAction(exchange));
    }

    @Override
    public void onEnable() {
        // Initialization logic
    }

    @Override
    public void onDisable() {
        // Cleanup logic
    }
}
```

**2. Use `RequestContext` for convenient request handling:**

```java
private void handleGetStats(io.undertow.server.HttpServerExchange exchange) {
    RequestContext ctx = new RequestContext(exchange, plugin, getName(), "/stats");

    // Get query parameters
    int page = ctx.getQueryParamAsInt("page", 1);
    int pageSize = ctx.getQueryParamAsInt("pageSize", 20);

    // Get API key
    String apiKey = ctx.getApiKey().orElse("");

    // Get request body
    String body = ctx.getBody();

    // Parse JSON body
    var jsonData = ctx.getBodyAsJson();

    // Build response
    ctx.respondSuccess(Map.of(
        "stats", myData,
        "page", page
    ));
}
```

**3. Use `ResponseBuilder` for fluent response building:**

```java
ResponseBuilder.success()
    .put("player", playerName)
    .put("balance", balance)
    .put("currency", "$")
    .build()
```

**4. Register the extension:**

```java
// In your plugin's onEnable()
ApiExtensionManager manager = syncmoney.getExtensionManager();
manager.registerExtension(new MyExtension());
```

### Extension Route Pattern

All extension routes are automatically prefixed:

```
/api/extensions/{extension-name}/{route}
```

Example:
- Extension name: `my-extension`
- Registered route: `stats`
- Full path: `/api/extensions/my-extension/stats`

### Extension Configuration

Extensions can read configuration from `config.yml`:

```yaml
web-admin:
  extensions:
    my-extension:
      enabled: true
      setting1: "value1"
```

### Class Reference

| Class | Purpose |
|-------|---------|
| `ApiExtension` | Main interface for extensions — define name, version, routes, lifecycle |
| `ApiExtensionRouter` | Register GET/POST/PUT/DELETE routes with path patterns |
| `ApiExtensionManager` | Register/unregister extensions, manage extension lifecycle |
| `RequestContext` | Convenient access to query params, body, headers, API key |
| `ResponseBuilder` | Fluent API for building JSON responses |

---

## Error Responses

### Unauthorized (401)

```json
{
  "success": false,
  "error": {
    "code": "AUTHENTICATION_FAILED",
    "message": "Missing or invalid authorization header"
  }
}
```

### Forbidden (403)

```json
{
  "success": false,
  "error": {
    "code": "NODE_ACCESS_DENIED",
    "message": "Permission denied for node operations"
  }
}
```

### Not Found (404)

```json
{
  "success": false,
  "error": {
    "code": "NOT_FOUND",
    "message": "Endpoint not found"
  }
}
```

### Rate Limited (429)

```json
{
  "success": false,
  "error": {
    "code": "RATE_LIMITED",
    "message": "Too many requests, please try again later"
  }
}
```

### Server Error (500)

```json
{
  "success": false,
  "error": {
    "code": "INTERNAL_ERROR",
    "message": "Internal server error"
  }
}
```

### Serialization Error (500)

Returned when the API response cannot be serialized to JSON:

```json
{
  "success": false,
  "error": {
    "code": "SERIALIZATION_ERROR",
    "message": "Failed to serialize response"
  }
}
```

### Common Error Codes

| Code | Description |
|------|-------------|
| `NODE_ACCESS_DENIED` | Permission denied for node operations |
| `INVALID_NODE_NAME` | Node name is required |
| `INVALID_NODE_URL` | Node URL is required |
| `SSRF_BLOCKED` | URL blocked by SSRF protection |
| `CENTRAL_MODE_DISABLED` | Feature only available in central mode |
| `ECONOMY_NOT_AVAILABLE` | Economy system not available |
| `PLAYER_UUID_REQUIRED` | Player UUID is required |
| `INVALID_UUID` | Invalid UUID format |
| `INVALID_CONFIG` | Invalid configuration value |

---

## Rate Limiting

The API implements rate limiting to prevent abuse. Default: **60 requests per minute** per client IP. If you exceed the rate limit, you will receive a 429 response.

Rate limiting can be configured in `config.yml`:

```yaml
web-admin:
  security:
    rate-limit:
      enabled: true
      requests-per-minute: 60
```

---

## Best Practices

### 1. Always Validate Input

```java
private void handleRequest(HttpServerExchange exchange) {
    String param = exchange.getQueryParameters().getFirst("param");
    if (param == null || param.isBlank()) {
        sendError(exchange, 400, "PARAM_REQUIRED", "Parameter 'param' is required");
        return;
    }

    // Continue processing...
}
```

### 2. Use Appropriate HTTP Methods

| Method | Usage |
|--------|-------|
| GET | Read data, no side effects |
| POST | Create resources |
| PUT | Update resources |
| DELETE | Delete resources |

### 3. Handle Exceptions Gracefully

```java
try {
    // Business logic
    sendJson(exchange, ApiResponse.success(result));
} catch (IllegalArgumentException e) {
    sendError(exchange, 400, "INVALID_INPUT", e.getMessage());
} catch (Exception e) {
    plugin.getLogger().log(Level.WARNING, "Error handling request", e);
    sendError(exchange, 500, "INTERNAL_ERROR", "An unexpected error occurred");
}
```

### 4. Use Constants for Permission Names

```java
private static final String PERMISSION_VIEW = "syncmoney.web.nodes.view";
private static final String PERMISSION_MANAGE = "syncmoney.web.nodes.manage";
```

### 5. Mask Sensitive Data in Logs

```java
private String maskApiKey(String key) {
    if (key == null || key.length() <= 8) {
        return "****";
    }
    return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
}
```

### 6. Follow Naming Conventions

| Element | Convention | Example |
|---------|-------------|---------|
| Endpoint paths | lowercase, hyphens | `/api/nodes/status` |
| Error codes | UPPER_SNAKE | `NODE_NOT_FOUND` |
| Query parameters | camelCase | `pageSize`, `startTime` |

---

## Endpoint Summary

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/health` | — | Health check |
| GET | `/api/system/status` | ✓ | System status |
| GET | `/api/system/redis` | ✓ | Redis status |
| GET | `/api/system/breaker` | ✓ | Circuit breaker status |
| GET | `/api/system/metrics` | ✓ | System metrics |
| GET | `/api/economy/stats` | ✓ | Economy statistics |
| GET | `/api/economy/player/{uuid}/balance` | ✓ | Player balance |
| GET | `/api/economy/top` | ✓ | Leaderboard |
| GET | `/api/economy/cross-server-stats` | ✓ | Cross-server stats (Central) |
| GET | `/api/economy/cross-server-top` | ✓ | Cross-server top (Central) |
| GET | `/api/config` | ✓ | Get configuration |
| PUT | `/api/config` | ✓ | Update configuration |
| POST | `/api/config/validate` | ✓ | Validate configuration |
| POST | `/api/config/reload` | ✓ | Reload configuration |
| GET | `/api/audit/player/{name}` | ✓ | Player audit records |
| GET | `/api/audit/search` | ✓ | Search audit (offset or cursor) |
| GET | `/api/audit/search-cursor` | ✓ | Search audit (cursor-based) |
| GET | `/api/audit/stats` | ✓ | Audit statistics |
| GET | `/api/nodes` | ✓ | List nodes |
| GET | `/api/nodes/status` | ✓ | Node detailed status |
| POST | `/api/nodes` | ✓ | Create node |
| PUT | `/api/nodes/{index}` | ✓ | Update node |
| DELETE | `/api/nodes/{index}` | ✓ | Delete node |
| POST | `/api/nodes/{index}/ping` | ✓ | Ping node |
| GET | `/api/settings` | ✓ | Get settings |
| POST | `/api/settings/theme` | ✓ | Update theme |
| POST | `/api/settings/language` | ✓ | Update language |
| POST | `/api/settings/timezone` | ✓ | Update timezone |
| POST | `/api/auth/ws-token` | ✓ | Generate WebSocket token |
| GET | `/sse` | ✓ | Server-Sent Events (recommended) |
| GET | `/ws` | Token | WebSocket (experimental) |

---

## Base URL

When the web server is running, the base URL is:

```
http://<host>:<port>
```

Default: `http://localhost:8080`
