

export interface TransactionEvent {
  playerUuid: string
  playerName: string
  type: 'DEPOSIT' | 'WITHDRAW' | 'SET_BALANCE' | 'TRANSFER'
  amount: number
  balance: number
  timestamp: number
  [key: string]: unknown
}

export interface PlayerJoinEvent {
  playerUuid: string
  playerName: string
  serverName: string
}

export interface PlayerQuitEvent {
  playerUuid: string
  playerName: string
}

export interface CircuitBreakEvent {
  reason: string
  timestamp: number
  details?: Record<string, unknown>
}

export interface AuditEvent {
  id?: string
  recordId?: string | number
  playerUuid: string
  playerName: string
  type: string
  source?: string
  amount: number
  balance: number
  timestamp: number
  serverName?: string
  sequence?: number
  details?: Record<string, unknown>
  [key: string]: unknown
}

export interface SystemAlertEvent {
  level: 'INFO' | 'WARNING' | 'CRITICAL'
  message: string
  timestamp: number
}

export interface ConnectedEvent {
  message: string
  timestamp: number
}

export interface AuthenticatedEvent {
  success: boolean
  timestamp: number
}

export interface ErrorEvent {
  code: number
  message: string
  timestamp: number
}

export type AppEventType =
  | TransactionEvent
  | PlayerJoinEvent
  | PlayerQuitEvent
  | CircuitBreakEvent
  | AuditEvent
  | SystemAlertEvent
  | ConnectedEvent
  | AuthenticatedEvent
  | ErrorEvent
