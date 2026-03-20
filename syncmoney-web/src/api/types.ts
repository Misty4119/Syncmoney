

export interface ApiResponse<T> {
  success: boolean
  data?: T
  error?: {
    code: string
    message: string
  }
  meta?: {
    timestamp: number
    version: string
  }
}

export interface Pagination {
  page: number
  pageSize: number
  totalItems: number
  totalPages: number
  nextCursor?: string | null
  hasMore?: boolean
}

export type AuditType = 'DEPOSIT' | 'WITHDRAW' | 'TRANSFER' | 'SET_BALANCE'

export interface AuditRecord {
  id: string
  playerUuid: string
  playerName: string
  type: AuditType
  amount: string
  balanceBefore: string
  balanceAfter: string
  source: string
  targetName?: string
  reason?: string
  timestamp: number
  serverName: string
  sequence?: number
}

export interface SystemStatus {
  plugin: {
    name: string
    version: string
    dbVersion: number
    mode: string
    uptime: number
  }
  economy: {
    mode: string
    currencyName: string
  }
  redis: {
    connected: boolean
    enabled?: boolean
  }
  database: {
    type: string
    connected: boolean
    enabled?: boolean
  }
  circuitBreaker: {
    state: 'NORMAL' | 'WARNING' | 'LOCKED'
    lastTrigger?: number
    enabled?: boolean
    failCount?: number
  }
  serverName?: string
  onlinePlayers?: number
  maxPlayers?: number
}

export interface DashboardStats {
  totalSupply: string
  totalPlayers: number
  todayTransactions: number
  onlinePlayers: number
  circuitBreaker: 'NORMAL' | 'WARNING' | 'LOCKED'
}

export interface Settings {
  theme: 'dark' | 'light'
  language: 'zh-TW' | 'en-US'
  serverName?: string
  currencyName?: string
  timezone?: string
  dedupServerWindowSeconds?: number
  dedupClientCacheSize?: number
}

export interface ServerSettings extends Settings {
  timezone?: string
}

export interface MetricsResponse {
  cpu: number
  memory: {
    used: number
    total: number
    percentage: number
  }
  tps: number
  players: {
    online: number
    peak: number
  }
}

export interface SystemMetrics {
  memory: {
    used: number
    total: number
    free: number
    max: number
    percentage: number
  }
  threads: number
  tps: number
  cpu?: number
  players?: {
    online: number
    peak: number
  }
}

export interface AuthVerifyResponse {
  valid: boolean
  permissions?: string[]
}

export interface ConfigResponse {
  config: Record<string, unknown>
  version: string
}

export interface PluginConfig {
  serverName: string
  economyMode: string
  redis?: {
    enabled: boolean
    host: string
    port: number
    database: number
  }
  database?: {
    type: string
    host: string
    port: number
    database: string
  }
  circuitBreaker?: {
    enabled: boolean
    threshold: number
    timeout: number
  }
  audit?: {
    enabled: boolean
    batchSize?: number
    flushInterval?: number
  }
  economy?: {
    currencyName?: string
    decimalPlaces?: number
  }
}

export interface PaginatedResponse<T> {
  data: T[]
  pagination: Pagination
}


export interface TransactionEventData {
  playerName: string
  playerUuid: string
  amount: number
  type: string
  balanceAfter: number
  source: string
}

export interface CircuitBreakEventData {
  state: string
  reason: string
  timestamp: number
}

export interface SystemAlertEventData {
  level: 'info' | 'warning' | 'error'
  message: string
  timestamp: number
}

export interface PlayerEventData {
  playerName: string
  playerUuid: string
}

export type SSEEventData =
  | { type: 'transaction'; data: TransactionEventData }
  | { type: 'audit'; data: AuditRecord }
  | { type: 'circuit_break'; data: CircuitBreakEventData }
  | { type: 'system_alert'; data: SystemAlertEventData }
  | { type: 'player_join'; data: PlayerEventData }
  | { type: 'player_quit'; data: PlayerEventData }

/**
 * Node info from central mode config
 */
export interface NodeInfo {
  name: string
  url: string
  apiKey: string
  enabled: boolean
  status?: NodeHealthStatus
}

/**
 * Node configuration for central mode
 */
export interface NodeConfig {
  name: string
  url: string
  apiKey: string
  enabled: boolean
}

/**
 * Node health status type
 */
export type NodeHealthStatus = 'online' | 'offline' | 'unknown'

/**
 * Response from /api/nodes
 */
export interface NodesResponse {
  nodes: NodeConfig[]
  centralMode: boolean
  selfUrl: string | null
}

/**
 * Node status information
 * Response from /api/nodes/status
 */
export interface NodeStatus {
  serverName: string
  serverId?: string
  onlinePlayers: number
  maxPlayers: number
  economyMode: string
  status: NodeHealthStatus | string
  lastPing?: number
}

/**
 * Aggregated cross-server statistics
 * Response from /api/economy/cross-server-stats
 */
export interface AggregatedStats {
  totalSupply: number
  totalPlayers: number
  totalOnlinePlayers: number
  todayTransactions: number
  nodesStatus: Record<string, number>
  source: 'aggregated' | 'local' | 'cache'
  timestamp: number
  topPlayers?: Array<{
    rank: number
    uuid: string
    balance: string
    serverName: string
  }>
}

/**
 * @deprecated Use NodeStatusResponse instead
 */
export interface NodeStatusResponse {
  serverName: string
  serverId: string
  onlinePlayers: number
  maxPlayers: number
  economyMode: string
  selfUrl: string | null
}
