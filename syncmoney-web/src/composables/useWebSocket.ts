import { ref, onUnmounted } from 'vue'
import { handleError } from '@/types/errors'

export type WebSocketEventType = 'open' | 'close' | 'error' | 'message'

export type WebSocketMessageData = Record<string, unknown>

export interface WebSocketMessage {
  type: WebSocketEventType
  data: WebSocketMessageData
  timestamp: number
}

export interface UseWebSocketOptions {
  url?: string
  protocols?: string | string[]
  reconnect?: boolean
  reconnectInterval?: number
  maxReconnectAttempts?: number
}

/**
 * [SYNC-WEB-001]
 * WebSocket Composable for real-time bidirectional communication.
 * Includes automatic reconnection with Exponential Backoff + Jitter to prevent server overload.
 */
export function useWebSocket(options: UseWebSocketOptions = {}) {
  const {
    reconnect = true,
    reconnectInterval = 3000,
    maxReconnectAttempts = 5
  } = options

  const connected = ref(false)
  const connectionStatus = ref<'connecting' | 'connected' | 'disconnected' | 'error'>('disconnected')
  const lastMessage = ref<WebSocketMessage | null>(null)
  const errorMessage = ref<string>('')

  let ws: WebSocket | null = null
  let reconnectAttempts = 0
  let reconnectTimeout: ReturnType<typeof setTimeout> | null = null
  let currentBaseUrl = '/ws'

  const messageHandlers = new Map<WebSocketEventType, ((data: WebSocketMessageData) => void)[]>()

  function connect(url: string = '') {

    currentBaseUrl = url || '/ws'
    connectionStatus.value = 'connecting'

    disconnect()

    const apiKey = localStorage.getItem('apiKey')
    
    if (!apiKey) {
      errorMessage.value = 'No API key found. Please login first.'
      console.error('WebSocket connection failed: No API key')
      return
    }


    obtainSessionToken(apiKey)
      .then(token => {
        if (!token) {
          errorMessage.value = 'Failed to obtain session token'
          return
        }
        
        const fullUrl = `${currentBaseUrl}?token=${encodeURIComponent(token)}`
        establishConnection(fullUrl)
      })
      .catch(err => {
        errorMessage.value = err.message || 'Failed to connect'
        console.error('WebSocket connection error:', err)
      })
  }

  /**
   * [SYNC-WEB-004] Fetch secure WS token.
   */
  async function obtainSessionToken(apiKey: string): Promise<string | null> {
    try {
      const response = await fetch('/api/auth/ws-token', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${apiKey}`,
          'Content-Type': 'application/json'
        }
      })

      if (!response.ok) {
        throw new Error(`Token request failed: ${response.status}`)
      }

      const data = await response.json()
      
      if (data.success && data.data && data.data.token) {
        return data.data.token
      }
      
      throw new Error('Invalid token response')
    } catch (err) {
      console.error('Failed to obtain session token:', err)
      return null
    }
  }

  /**
   * [SYNC-WEB-005] Establish WebSocket connection with the given URL.
   */
  function establishConnection(fullUrl: string) {
    try {
      ws = new WebSocket(fullUrl)

      ws.onopen = () => {
        connected.value = true
        connectionStatus.value = 'connected'
        errorMessage.value = ''
        reconnectAttempts = 0
        console.log('WebSocket connection established')

        const handlers = messageHandlers.get('open')
        if (handlers) {
          handlers.forEach(handler => handler({}))
        }
      }

      ws.onmessage = (event) => {
        try {
          const message: WebSocketMessage = {
            type: 'message',
            data: JSON.parse(event.data),
            timestamp: Date.now()
          }
          lastMessage.value = message

          const handlers = messageHandlers.get('message')
          if (handlers) {
            handlers.forEach(handler => handler(message.data))
          }
        } catch (e) {
          console.error('Failed to parse WebSocket message:', e)
        }
      }

      ws.onerror = (e) => {
        errorMessage.value = 'WebSocket connection error'
        connectionStatus.value = 'error'
        console.error('WebSocket error:', e)

        const handlers = messageHandlers.get('error')
        if (handlers) {
          handlers.forEach(handler => handler({ type: 'error', message: e.type }))
        }
      }

      ws.onclose = (event) => {
        connected.value = false
        connectionStatus.value = 'disconnected'
        console.log('WebSocket connection closed:', event.code, event.reason)

        const handlers = messageHandlers.get('close')
        if (handlers) {
          handlers.forEach(handler => handler({ code: event.code, reason: event.reason }))
        }


        if (reconnect && reconnectAttempts < maxReconnectAttempts) {
          reconnectAttempts++
          const baseDelay = reconnectInterval * Math.pow(2, reconnectAttempts - 1)
          const jitter = Math.random() * 1000

          const delay = Math.min(baseDelay + jitter, 30000)
          
          console.log(`WebSocket reconnecting in ${Math.round(delay)}ms... Attempt ${reconnectAttempts}/${maxReconnectAttempts}`)
          reconnectTimeout = setTimeout(() => {
            connect(currentBaseUrl)
          }, delay)
        } else if (reconnectAttempts >= maxReconnectAttempts) {
          errorMessage.value = 'WebSocket connection failed after maximum retry attempts'
          connectionStatus.value = 'error'
          console.error('WebSocket max reconnect attempts reached')
        }
      }
    } catch (error: unknown) {
      const err = handleError(error)
      errorMessage.value = err.message || 'Failed to connect'
      console.error('WebSocket connection error:', error)
    }
  }

  function disconnect() {
    if (reconnectTimeout) {
      clearTimeout(reconnectTimeout)
      reconnectTimeout = null
    }

    if (ws) {
      ws.close()
      ws = null
    }
    connected.value = false
    reconnectAttempts = 0
  }

  function send(data: unknown): boolean {
    if (ws && ws.readyState === WebSocket.OPEN) {
      try {
        ws.send(JSON.stringify(data))
        return true
      } catch (e) {
        console.error('Failed to send WebSocket message:', e)
        return false
      }
    }
    console.warn('WebSocket is not connected, cannot send message')
    return false
  }

  function on(type: WebSocketEventType, handler: (data: WebSocketMessageData) => void) {
    const handlers = messageHandlers.get(type) || []
    handlers.push(handler)
    messageHandlers.set(type, handlers)
  }

  function off(type: WebSocketEventType, handler: (data: WebSocketMessageData) => void) {
    const handlers = messageHandlers.get(type)
    if (handlers) {
      const index = handlers.indexOf(handler)
      if (index > -1) {
        handlers.splice(index, 1)
      }
    }
  }

  onUnmounted(() => {
    disconnect()
  })

  return {
    connected,
    connectionStatus,
    lastMessage,
    error: errorMessage,
    connect,
    disconnect,
    send,
    on,
    off
  }
}
