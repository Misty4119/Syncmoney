import { ref } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import type { TransactionEvent, PlayerJoinEvent, PlayerQuitEvent, CircuitBreakEvent, AuditEvent, SystemAlertEvent } from '@/types/events'
import { handleError } from '@/types/errors'

export type SSEEventType = 'transaction' | 'circuit_break' | 'system' | 'system_alert' | 'connected' | 'player_join' | 'player_quit' | 'audit' | 'authenticated' | 'error'


export type SSEMessageData =
  | TransactionEvent
  | PlayerJoinEvent
  | PlayerQuitEvent
  | CircuitBreakEvent
  | AuditEvent
  | SystemAlertEvent
  | Record<string, unknown>

export interface SSEMessage {
  type: SSEEventType
  data: SSEMessageData
  timestamp: number
}


let sseInstance: ReturnType<typeof createSSEInstance> | null = null

/**
 * [SYNC-WEB-008]
 * SSE (Server-Sent Events) Composable
 * Used for receiving real-time event pushes from the server.
 * 
 * Implemented as a singleton - all components share the same SSE connection.
 * The connection is only closed when all components that use it are unmounted.
 */
export function useSSE() {
  if (sseInstance) {
    console.log('[SSE] Reusing existing singleton instance')
    return sseInstance
  }

  sseInstance = createSSEInstance()
  console.log('[SSE] Created new SSE singleton instance')
  return sseInstance
}

/**
 * [SYNC-WEB-027] Create SSE instance with reference counting
 */
function createSSEInstance() {
  const connected = ref(false)
  const lastMessage = ref<SSEMessage | null>(null)
  const errorMessage = ref<string>('')
  const connectionStatus = ref<'connecting' | 'connected' | 'disconnected' | 'error'>('disconnected')

  let eventSource: EventSource | null = null
  let reconnectAttempts = 0
  const maxReconnectAttempts = 5
  const reconnectDelay = 3000
  let reconnectTimeout: ReturnType<typeof setTimeout> | null = null
  let currentBaseUrl = '/sse'


  const seenEventIds = new Set<string>()


  function getMaxSeenEvents(): number {
    try {
      const settingsStore = useSettingsStore()
      return settingsStore.dedupClientCacheSize || 1000
    } catch {
      return 1000
    }
  }

  const messageHandlers = new Map<SSEEventType, ((data: SSEMessageData) => void)[]>()

  function connect(url: string = '') {

    currentBaseUrl = url || '/sse'
    const baseUrl = currentBaseUrl


    disconnect()



    obtainSessionToken().then(token => {
      if (!token) {
        errorMessage.value = 'Failed to obtain session token. Please login again.'
        console.error('SSE connection failed: No session token')
        return
      }


      const fullUrl = `${baseUrl}?token=${encodeURIComponent(token)}`
      establishConnection(fullUrl)
    })
  }

  /**
   * [SYNC-WEB-028] Obtain session token for SSE authentication
   */
  async function obtainSessionToken(): Promise<string | null> {
    const apiKey = localStorage.getItem('apiKey')
    if (!apiKey) {
      console.error('[SSE] No API key in localStorage')
      return null
    }

    try {
      console.log('[SSE] Requesting session token from /api/auth/ws-token')
      const response = await fetch('/api/auth/ws-token', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${apiKey}`,
          'Content-Type': 'application/json'
        }
      })

      console.log('[SSE] Token response status:', response.status)

      if (!response.ok) {
        const errorText = await response.text()
        console.error('[SSE] Token request failed:', response.status, errorText)
        return null
      }

      const data = await response.json()
      console.log('[SSE] Token response:', data)
      return data.success ? data.data.token : null
    } catch (error) {
      console.error('[SSE] Error obtaining session token:', error)
      return null
    }
  }

  /**
   * [SYNC-WEB-012] Establish SSE connection with the given URL
   */
  function establishConnection(fullUrl: string) {

    try {
      eventSource = new EventSource(fullUrl)

      eventSource.onopen = () => {
        connected.value = true
        connectionStatus.value = 'connected'
        errorMessage.value = ''
        console.log('[SSE] Connection established successfully!')




        setTimeout(() => {
          if (connected.value) {
            reconnectAttempts = 0
          }
        }, 5000)
      }

      eventSource.onmessage = (event) => {
        try {

          const messageText = event.data
          if (!messageText) return

          const message: SSEMessage = JSON.parse(messageText)
          lastMessage.value = message

          const handlers = messageHandlers.get(message.type as SSEEventType)
          if (handlers) {
            handlers.forEach(handler => handler(message.data))
          }
        } catch (e) {
          console.error('Failed to parse SSE message:', e)
        }
      }

      eventSource.addEventListener('connected', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data)
          console.log('SSE connected:', data)
          const handlers = messageHandlers.get('connected')
          if (handlers) {
            handlers.forEach(handler => handler(data))
          }
        } catch (e) {
          console.error('Failed to parse connected event:', e)
        }
      })


      eventSource.addEventListener('authenticated', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data)
          console.log('SSE authenticated:', data)

          reconnectAttempts = 0
          const handlers = messageHandlers.get('authenticated')
          if (handlers) {
            handlers.forEach(handler => handler(data))
          }
        } catch (e) {
          console.error('Failed to parse authenticated event:', e)
        }
      })


      eventSource.addEventListener('error', (event: MessageEvent) => {

        if (!event.data) {
          console.warn('[SSE] Error event with no data - underlying connection error')


          errorMessage.value = 'SSE connection error'
          return
        }

        try {
          const data = JSON.parse(event.data)
          console.log('SSE error event received:', data)


          if (data && data.code === 401) {
            console.error('SSE authentication failed (401), redirecting to login...')

            localStorage.removeItem('apiKey')

            if (typeof window !== 'undefined') {
              window.location.href = '/login'
            }
            return
          }

          const handlers = messageHandlers.get('error')
          if (handlers) {
            handlers.forEach(handler => handler(data))
          }
        } catch (e) {
          console.error('Failed to parse error event:', e)
        }
      })

      eventSource.addEventListener('transaction', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data)







          const payload = (data as Record<string, unknown>).data as Record<string, unknown> | undefined ?? data as Record<string, unknown>
          const eventId = `${payload.timestamp}-${payload.playerName}-${payload.amount}`
          if (seenEventIds.has(eventId)) {
            console.debug('[SSE] Duplicate transaction event skipped:', eventId)
            return
          }


          seenEventIds.add(eventId)
          if (seenEventIds.size > getMaxSeenEvents()) {
            const firstItem = seenEventIds.values().next().value
            if (firstItem) seenEventIds.delete(firstItem)
          }

          const handlers = messageHandlers.get('transaction')
          if (handlers) {
            handlers.forEach(handler => handler(data))
          }
        } catch (e) {
          console.error('Failed to parse transaction event:', e)
        }
      })

      eventSource.addEventListener('circuit_break', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data)
          const handlers = messageHandlers.get('circuit_break')
          if (handlers) {
            handlers.forEach(handler => handler(data))
          }
        } catch (e) {
          console.error('Failed to parse circuit_break event:', e)
        }
      })

      eventSource.addEventListener('system_alert', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data)
          const handlers = messageHandlers.get('system_alert')
          if (handlers) {
            handlers.forEach(handler => handler(data))
          }
        } catch (e) {
          console.error('Failed to parse system_alert event:', e)
        }
      })

      eventSource.addEventListener('player_join', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data)
          const handlers = messageHandlers.get('player_join')
          if (handlers) {
            handlers.forEach(handler => handler(data))
          }
        } catch (e) {
          console.error('Failed to parse player_join event:', e)
        }
      })

      eventSource.addEventListener('player_quit', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data)
          const handlers = messageHandlers.get('player_quit')
          if (handlers) {
            handlers.forEach(handler => handler(data))
          }
        } catch (e) {
          console.error('Failed to parse player_quit event:', e)
        }
      })



      eventSource.addEventListener('audit', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data) as Record<string, unknown>
          console.log('Received audit event:', data)




          const eventId = data.id
            ? String(data.id)
            : `${data.timestamp}-${data.playerName}-${data.amount}`
          if (seenEventIds.has(eventId)) {
            console.debug('[SSE] Duplicate audit event skipped:', eventId)
            return
          }


          seenEventIds.add(eventId)
          if (seenEventIds.size > getMaxSeenEvents()) {
            const firstItem = seenEventIds.values().next().value
            if (firstItem) seenEventIds.delete(firstItem)
          }

          const handlers = messageHandlers.get('audit')
          if (handlers) {
            handlers.forEach(handler => handler(data))
          }
        } catch (e) {
          console.error('Failed to parse audit event:', e)
        }
      })

      eventSource.onerror = (e) => {
        errorMessage.value = 'SSE connection error'

        console.error('[SSE] Connection error!', e)
        console.error('[SSE] EventSource readyState:', eventSource?.readyState)
        console.error('[SSE] EventSource URL:', eventSource?.url)
        connected.value = false
        connectionStatus.value = 'error'


        disconnect()


        if (reconnectAttempts < maxReconnectAttempts) {
          reconnectAttempts++
          console.log(`SSE reconnecting... Attempt ${reconnectAttempts}/${maxReconnectAttempts}`)

          if (reconnectTimeout) {
            clearTimeout(reconnectTimeout)
            reconnectTimeout = null
          }

          const baseDelay = reconnectDelay * Math.pow(1.5, reconnectAttempts - 1)
          const jitter = Math.random() * 1000
          const delay = Math.min(baseDelay + jitter, 30000)
          console.log(`[SYNC-WEB] Reconnecting in ${Math.round(delay)}ms (exponential backoff + jitter)`)
          reconnectTimeout = setTimeout(() => {
            connect(currentBaseUrl)
          }, delay)
        } else {
          errorMessage.value = 'SSE connection failed after maximum retry attempts'
          console.error('SSE max reconnect attempts reached')
          connectionStatus.value = 'error'
        }
      }
    } catch (error: unknown) {
      const err = handleError(error)
      errorMessage.value = err.message || 'Failed to connect'
      console.error('SSE connection error:', error)
    }
  }

  /**
   * [SYNC-WEB-029] Disconnect - close EventSource and clear timers.
   * The singleton persists so connect() can be called again after this.
   */
  function disconnect() {
    if (reconnectTimeout) {
      clearTimeout(reconnectTimeout)
      reconnectTimeout = null
    }

    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
    connected.value = false
    connectionStatus.value = 'disconnected'
  }

  function on(type: SSEEventType, handler: (data: SSEMessageData) => void) {
    const handlers = messageHandlers.get(type) || []
    handlers.push(handler)
    messageHandlers.set(type, handlers)
  }

  function off(type: SSEEventType, handler: (data: SSEMessageData) => void) {
    const handlers = messageHandlers.get(type)
    if (handlers) {
      const index = handlers.indexOf(handler)
      if (index > -1) {
        handlers.splice(index, 1)
      }
    }
  }


  
  
  
  
  

  return {
    connected,
    connectionStatus,
    lastMessage,
    error: errorMessage,
    connect,
    disconnect: () => { /* no-op: SSE persists across page navigations */ },
    on,
    off
  }
}
